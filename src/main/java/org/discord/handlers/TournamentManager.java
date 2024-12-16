package org.discord.handlers;

import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Role;
import net.dv8tion.jda.api.entities.channel.unions.MessageChannelUnion;
import net.dv8tion.jda.api.events.interaction.GenericInteractionCreateEvent;
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;

import java.io.IOException;


import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.interactions.components.buttons.Button;
import org.discord.ChallongeDataClasses;
import org.discord.SmashEloBot;
import org.discord.handlers.strategies.DoubleEliminationCompletionStrategy;
import org.discord.handlers.strategies.RoundRobinCompletionStrategy;
import org.discord.handlers.strategies.SingleEliminationCompletionStrategy;
import org.discord.handlers.strategies.TournamentCompletionStrategy;
import org.discord.obj.Match;
import org.discord.utils.ChallongeService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import retrofit2.Call;
import retrofit2.Response;


import java.awt.Color;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;

public class TournamentManager {
    private static final Logger logger = LoggerFactory.getLogger(TournamentManager.class);
    private final Map<Long, ScheduledFuture<?>> tournamentSchedulers = new ConcurrentHashMap<>();

    private final ChallongeService challongeService;
    private final EloManager eloManager;
    private final Map<Long, ChallongeDataClasses.Participant> tournamentParticipants = new ConcurrentHashMap<>();

    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);
    private final ConcurrentHashMap<Long, TournamentData> activeTournaments = new ConcurrentHashMap<>();
    private final List<String> tournamentOrganizerRoleNames;
    private String guildId; // The ID of your Discord server
    private ChallongeDataClasses.Tournament currentTournament;
    private MessageChannelUnion tournamentChannel;

    public TournamentManager(ChallongeService challongeService, EloManager eloManager, String guildId, List<String> tournamentOrganizerRoleNames) {
        this.challongeService = challongeService;
        this.eloManager = eloManager;
        this.guildId = guildId;
        this.tournamentOrganizerRoleNames = tournamentOrganizerRoleNames;
    }

    public static String generateUniqueUrl() {
        String uniqueId = UUID.randomUUID().toString().substring(0, 8); // 8-character UUID
        return (uniqueId).replace(" ", "").replace("-", "_");
    }

    private String getOrdinal(int i) {
        String[] suffixes = new String[]{"th", "st", "nd", "rd", "th", "th", "th", "th", "th", "th"};
        return switch (i % 100) {
            case 11, 12, 13 -> i + "th";
            default -> i + suffixes[i % 10];
        };
    }

    private void finalizeTournament(Long tournamentId) {
        TournamentData tournamentData = activeTournaments.get(tournamentId);
        if (tournamentData == null) {
            logger.warn("Attempted to finalize a non-existent tournament with ID: {}", tournamentId);
            return;
        }

        MessageChannelUnion channel = tournamentData.getChannel();

        try {
            // Finalize the tournament if it's not already finalized
            if (!"complete".equalsIgnoreCase(tournamentData.getTournament().state)) {
                Call<ChallongeDataClasses.TournamentWrapper> finalizeCall = challongeService.finalizeTournament(tournamentId, new HashMap<>());
                Response<ChallongeDataClasses.TournamentWrapper> finalizeResponse = finalizeCall.execute();

                if (!finalizeResponse.isSuccessful()) {
                    logger.error("Failed to finalize tournament: {}", finalizeResponse.errorBody() != null ? finalizeResponse.errorBody().string() : "Unknown error");
                    channel.sendMessage("⚠️ Failed to finalize the tournament. Please contact an administrator.").queue();
                    return;
                }
            }

            Call<List<ChallongeDataClasses.ParticipantWrapper>> participantsCall = challongeService.getParticipants(tournamentId, new HashMap<>());
            Response<List<ChallongeDataClasses.ParticipantWrapper>> participantsResponse = participantsCall.execute();

            if (participantsResponse.isSuccessful() && participantsResponse.body() != null) {
                List<ChallongeDataClasses.ParticipantWrapper> participantWrappers = participantsResponse.body();
                participantWrappers.sort(Comparator.comparingInt(pw -> pw.participant.finalRank));

                EmbedBuilder embed = new EmbedBuilder();
                embed.setTitle("🏆 Tournament Concluded!");
                embed.setDescription("The " + tournamentData.getTournamentType() + " tournament has ended. Here are the final results:");
                embed.setColor(Color.YELLOW);

                for (int i = 0; i < Math.min(3, participantWrappers.size()); i++) {
                    ChallongeDataClasses.Participant participant = participantWrappers.get(i).participant;
                    User user = channel.getJDA().retrieveUserById(participant.misc).complete();
                    String medal = i == 0 ? "🥇" : i == 1 ? "🥈" : "🥉";
                    embed.addField(medal + " " + getOrdinal(i + 1) + " Place", user.getName(), false);
                }

                // Calculate and apply ELO changes
                Map<String, Integer> eloChanges = calculateEloChanges(participantWrappers, tournamentData.getTournamentType());
                for (Map.Entry<String, Integer> entry : eloChanges.entrySet()) {
                    User user = channel.getJDA().retrieveUserById(entry.getKey()).complete();
                    int oldElo = eloManager.getElo(user);
                    int newElo = oldElo + entry.getValue();
                    eloManager.setElo(user, newElo);
                    embed.addField(user.getName() + " ELO Change", oldElo + " → " + newElo + " (" + (entry.getValue() >= 0 ? "+" : "") + entry.getValue() + ")", true);
                }

                channel.sendMessageEmbeds(embed.build()).queue();

                logger.info("Tournament ID {} concluded successfully.", tournamentId);

                tournamentData.getScheduler().cancel(false);
                activeTournaments.remove(tournamentId);
            } else {
                String errorBody = participantsResponse.errorBody() != null ? participantsResponse.errorBody().string() : "Unknown error";
                logger.error("Failed to retrieve participant standings for tournament ID {}: {} {}", tournamentId, participantsResponse.code(), participantsResponse.message());
                channel.sendMessage("❌ Tournament concluded, but failed to retrieve participant standings.").queue();
            }
        } catch (IOException e) {
            logger.error("IOException while finalizing tournament ID {}", tournamentId, e);
            channel.sendMessage("❌ An error occurred while finalizing the tournament. Please contact an administrator.").queue();
        }
    }

    private Map<String, Integer> calculateEloChanges(List<ChallongeDataClasses.ParticipantWrapper> participants, String tournamentType) {
        Map<String, Integer> eloChanges = new HashMap<>();
        int participantCount = participants.size();

        for (int i = 0; i < participantCount; i++) {
            ChallongeDataClasses.Participant participant = participants.get(i).participant;
            int eloChange = calculateIndividualEloChange(i + 1, participantCount, tournamentType);
            eloChanges.put(participant.misc, eloChange);
        }

        return eloChanges;
    }

    private Map<String, Integer> calculateEloChanges(List<ChallongeDataClasses.ParticipantWrapper> participants) {
        Map<String, Integer> eloChanges = new HashMap<>();
        int participantCount = participants.size();

        // Calculate expected scores for each participant
        Map<String, Double> expectedScores = calculateExpectedScores(participants);

        for (int i = 0; i < participantCount; i++) {
            ChallongeDataClasses.Participant participant = participants.get(i).participant;
            int currentElo = eloManager.getElo(retrieveDiscordUser(participant.misc)); // Assuming misc stores the Discord user ID
            double actualScore = calculateActualScore(i + 1, participantCount);
            double expectedScore = expectedScores.get(participant.misc);

            int eloChange = calculateEloChange(currentElo, actualScore, expectedScore);
            eloChanges.put(participant.misc, eloChange);
        }

        return eloChanges;
    }

    private Map<String, Double> calculateExpectedScores(List<ChallongeDataClasses.ParticipantWrapper> participants) {
        Map<String, Double> expectedScores = new HashMap<>();
        int participantCount = participants.size();

        for (ChallongeDataClasses.ParticipantWrapper wrapper1 : participants) {
            String id1 = wrapper1.participant.misc;
            int elo1 = eloManager.getElo(retrieveDiscordUser(id1));
            double totalExpectedScore = 0;

            for (ChallongeDataClasses.ParticipantWrapper wrapper2 : participants) {
                if (!wrapper1.equals(wrapper2)) {
                    String id2 = wrapper2.participant.misc;
                    int elo2 = eloManager.getElo(retrieveDiscordUser(id2));
                    totalExpectedScore += 1 / (1 + Math.pow(10, (elo2 - elo1) / 400.0));
                }
            }

            expectedScores.put(id1, totalExpectedScore / (participantCount - 1));
        }

        return expectedScores;
    }

    private double calculateActualScore(int rank, int totalParticipants) {
        return (double) (totalParticipants - rank) / (totalParticipants - 1);
    }

    private int calculateEloChange(int currentElo, double actualScore, double expectedScore) {
        int kFactor = getKFactor(currentElo);
        return (int) Math.round(kFactor * (actualScore - expectedScore));
    }

    private int getKFactor(int elo) {
        if (elo < 2000) {
            return 32;
        } else if (elo < 2400) {
            return 24;
        } else {
            return 16;
        }
    }

    private String determineMatchId(SlashCommandInteractionEvent event, User opponent) {
        // Step 1: Identify the tournament associated with the event's channel
        Long tournamentId = identifyTournamentId(event);
        if (tournamentId == null) {
            logger.warn("No active tournament found for the channel: {}", event.getChannel().getIdLong());
            return null;
        }

        // Step 2: Retrieve the corresponding TournamentData
        TournamentData tournamentData = getTournamentById(tournamentId);
        if (tournamentData == null) {
            logger.error("TournamentData not found for Tournament ID: {}", tournamentId);
            return null;
        }

        // Step 3: Fetch all open matches within the identified tournament
        List<ChallongeDataClasses.MatchWrapper> openMatches = fetchOpenMatches(tournamentId);
        if (openMatches == null || openMatches.isEmpty()) {
            logger.info("No open matches found for Tournament ID: {}", tournamentId);
            return null;
        }

        // Step 4: Iterate through open matches to find a match between the reporting user and the opponent
        User reportingUser = event.getUser();
        for (ChallongeDataClasses.MatchWrapper matchWrapper : openMatches) {
            ChallongeDataClasses.MatchData match = matchWrapper.match;

            // Retrieve participants
            ChallongeDataClasses.Participant participant1 = getParticipantById(tournamentId, String.valueOf(match.player1Id));
            ChallongeDataClasses.Participant participant2 = getParticipantById(tournamentId, String.valueOf(match.player2Id));

            if (participant1 == null || participant2 == null) {
                logger.warn("Participant data missing for Match ID: {}", match.id);
                continue;
            }

            // Fetch Discord User objects
            User user1 = retrieveDiscordUser(participant1.misc);
            User user2 = retrieveDiscordUser(participant2.misc);

            if (user1 == null || user2 == null) {
                logger.warn("Discord User not found for participants in Match ID: {}", match.id);
                continue;
            }

            // Check if the reporting user and opponent are participants in this match
            boolean isMatchBetweenUsers = (user1.equals(reportingUser) && user2.equals(opponent)) ||
                    (user2.equals(reportingUser) && user1.equals(opponent));

            if (isMatchBetweenUsers) {
                logger.info("Match found between {} and {}: Match ID {}", reportingUser.getAsTag(), opponent.getAsTag(), match.id);
                return String.valueOf(match.id);
            }
        }

        // Step 5: If no matching open match is found
        logger.info("No matching open match found between {} and {} in Tournament ID {}", reportingUser.getAsTag(), opponent.getAsTag(), tournamentId);
        return null;
    }

    /**
     * Identifies the tournament ID based on the interaction event's channel.
     *
     * @param event The interaction event.
     * @return The tournament ID if found; otherwise, null.
     */
    private Long identifyTournamentId(GenericInteractionCreateEvent event) {
        MessageChannelUnion channel = (MessageChannelUnion) event.getChannel();
        for (Map.Entry<Long, TournamentData> entry : activeTournaments.entrySet()) {
            if (entry.getValue().getChannel().equals(channel)) {
                return entry.getKey();
            }
        }
        return null;
    }

    /**
     * Fetches all open matches for a specific tournament.
     *
     * @param tournamentId The unique ID of the tournament.
     * @return A list of MatchWrapper objects representing open matches; otherwise, null.
     */
    private List<ChallongeDataClasses.MatchWrapper> fetchOpenMatches(Long tournamentId) {
        try {
            Map<String, String> options = new HashMap<>();
            options.put("state", "open");

            Call<List<ChallongeDataClasses.MatchWrapper>> call = challongeService.getMatches(tournamentId, options);
            Response<List<ChallongeDataClasses.MatchWrapper>> response = call.execute();

            if (response.isSuccessful() && response.body() != null) {
                return response.body();
            } else {
                String errorBody = response.errorBody() != null ? response.errorBody().string() : "Unknown error";
                logger.error("Failed to fetch open matches for Tournament ID {}: {} {}", tournamentId, response.code(), response.message());
                return null;
            }
        } catch (IOException e) {
            logger.error("IOException while fetching open matches for Tournament ID {}", tournamentId, e);
            return null;
        }
    }

    /**
     * Retrieves a Participant object based on tournament ID and participant ID.
     *
     * @param tournamentId  The unique ID of the tournament.
     * @param participantId The unique ID of the participant.
     * @return The Participant object if found; otherwise, null.
     */


    /**
     * Retrieves a Discord User object based on their Discord ID.
     *
     * @param discordId The Discord user ID.
     * @return The User object if found; otherwise, null.
     */
    private User retrieveDiscordUser(String discordId) {
        try {
            return SmashEloBot.jda.retrieveUserById(discordId).complete();
        } catch (Exception e) {
            logger.error("Failed to retrieve Discord User with ID: {}", discordId, e);
            return null;
        }
    }

    private List<String> determineMatchIds(Long tournamentId, User player1, User player2) {
        List<String> matchIds = new ArrayList<>();
        TournamentData tournamentData = activeTournaments.get(tournamentId);
        if (tournamentData == null) {
            logger.warn("Tournament data not found for tournament ID: {}", tournamentId);
            return matchIds;
        }

        logger.info("Searching for matches between {} and {} in tournament {}", player1.getId(), player2.getId(), tournamentId);

        try {
            // Fetch all open matches for the tournament
            Map<String, String> options = new HashMap<>();
            options.put("state", "open");
            Call<List<ChallongeDataClasses.MatchWrapper>> call = challongeService.getMatches(tournamentId, options);
            Response<List<ChallongeDataClasses.MatchWrapper>> response = call.execute();

            if (response.isSuccessful() && response.body() != null) {
                List<ChallongeDataClasses.MatchWrapper> matches = response.body();
                logger.info("Found {} open matches in tournament {}", matches.size(), tournamentId);

                for (ChallongeDataClasses.MatchWrapper matchWrapper : matches) {
                    ChallongeDataClasses.MatchData match = matchWrapper.match;
                    logger.debug("Checking match ID: {}", match.id);

                    ChallongeDataClasses.Participant participant1 = getParticipantById(tournamentId, String.valueOf(match.player1Id));
                    ChallongeDataClasses.Participant participant2 = getParticipantById(tournamentId, String.valueOf(match.player2Id));

                    if (participant1 == null || participant2 == null) {
                        logger.warn("Participant data missing for match ID: {}", match.id);
                        continue;
                    }

                    logger.debug("Match {} participants: {} vs {}", match.id, participant1.misc, participant2.misc);

                    if ((participant1.misc.equals(player1.getId()) && participant2.misc.equals(player2.getId())) ||
                            (participant1.misc.equals(player2.getId()) && participant2.misc.equals(player1.getId()))) {
                        logger.info("Found matching match ID: {}", match.id);
                        matchIds.add(String.valueOf(match.id));
                    }
                }
            } else {
                String errorBody = response.errorBody() != null ? response.errorBody().string() : "Unknown error";
                logger.error("Failed to retrieve matches for Tournament ID {}: {} {}", tournamentId, response.code(), response.message());
            }
        } catch (IOException e) {
            logger.error("Error fetching match details for tournament ID: " + tournamentId, e);
        }

        logger.info("Found {} matching matches", matchIds.size());
        return matchIds;
    }

    public void handleReportCommand(SlashCommandInteractionEvent event) {
        event.getOption("opponent").getAsUser().getId();
        int yourWins = event.getOption("yourwins").getAsInt();
        int opponentWins = event.getOption("opponentwins").getAsInt();
        User reporter = event.getUser();
        User opponent = event.getOption("opponent").getAsUser();

        Long tournamentId = identifyTournamentId(event);
        if (tournamentId == null) {
            event.reply("❌ Unable to identify the tournament for this match.").setEphemeral(true).queue();
            return;
        }

        List<String> matchIds = determineMatchIds(tournamentId, reporter, opponent);
        if (matchIds.isEmpty()) {
            event.reply("❌ Unable to find an active match between you and the specified opponent. Please ensure:\n" +
                            "1. You've selected the correct opponent.\n" +
                            "2. The match is currently open (not completed or pending).\n" +
                            "3. You're reporting in the correct tournament channel.\n" +
                            "If you're still having issues, please contact a tournament organizer.")
                    .setEphemeral(true)
                    .queue();
            return;
        }

        if (matchIds.size() > 1) {
            event.reply("Multiple matches found between you and your opponent. Please specify which match you're reporting using `/report <match_id> <your_wins> <opponent_wins>`.")
                    .setEphemeral(true)
                    .queue();
            return;
        }

        String matchId = matchIds.get(0);
        if (matchId == null) {
            event.reply("❌ Unable to find an active match between you and the specified opponent.").setEphemeral(true).queue();
            return;
        }


        ChallongeDataClasses.Tournament tournament = getTournamentById(tournamentId).getTournament();
        if (tournament == null) {
            event.reply("❌ Tournament data not found. Please try again later.").setEphemeral(true).queue();
            return;
        }

        try {
            String scoresCsv = yourWins + "-" + opponentWins;

            Map<String, Object> matchParams = new HashMap<>();
            matchParams.put("scores_csv", scoresCsv);

            // Determine the winner_id based on the Challonge participant ID, not the Discord user ID
            ChallongeDataClasses.Participant reporterParticipant = getParticipantByDiscordId(tournamentId, reporter.getId());
            ChallongeDataClasses.Participant opponentParticipant = getParticipantByDiscordId(tournamentId, opponent.getId());

            if (reporterParticipant == null || opponentParticipant == null) {
                logger.error("Failed to find Challonge participants for reporter or opponent");
                event.reply("❌ Failed to identify tournament participants. Please contact a tournament organizer.")
                        .setEphemeral(true)
                        .queue();
                return;
            }

            String winnerId = yourWins > opponentWins ? reporterParticipant.id.toString() : opponentParticipant.id.toString();
            matchParams.put("winner_id", winnerId);

            Map<String, Object> params = new HashMap<>();
            params.put("match", matchParams);

            logger.info("Sending match update request with params: {}", params);

            Call<ChallongeDataClasses.MatchWrapper> call = challongeService.updateMatch(tournamentId, Long.valueOf(matchId), new HashMap<>(), params);
            Response<ChallongeDataClasses.MatchWrapper> response = call.execute();

            if (response.isSuccessful() && response.body() != null) {
                logger.info("Match report successful for match ID: {}", matchId);
                event.reply("✅ Match reported successfully!").queue();
                TournamentData tournamentData = getTournamentById(tournamentId);
                if (tournamentData != null) {
                    if ("round robin".equalsIgnoreCase(tournamentData.getTournamentType())) {
                        checkRoundCompletion(tournamentData);
                    } else {
                        checkTournamentCompletion(tournamentId);
                    }
                }
            } else {
                String errorBody = response.errorBody() != null ? response.errorBody().string() : "No error body";
                logger.error("Failed to report match. Response Code: {}, Message: {}, Body: {}",
                        response.code(), response.message(), errorBody);
                event.reply("❌ Failed to report the match. Error code: " + response.code() +
                                ". Please ensure the scores are correct and try again. If the issue persists, contact a tournament organizer.")
                        .setEphemeral(true)
                        .queue();
            }
        } catch (IOException e) {
            logger.error("IOException while reporting match", e);
            event.reply("❌ An error occurred while reporting the match. Please try again later or contact a tournament organizer if the issue persists.")
                    .setEphemeral(true)
                    .queue();
        }

    }   private void checkRoundCompletion(TournamentData tournamentData) {
        Long tournamentId = tournamentData.getTournamentId();

        try {
            Map<String, String> options = new HashMap<>();
            Call<List<ChallongeDataClasses.MatchWrapper>> call = challongeService.getMatches(tournamentId, options);
            Response<List<ChallongeDataClasses.MatchWrapper>> response = call.execute();

            if (response.isSuccessful() && response.body() != null) {
                List<ChallongeDataClasses.MatchWrapper> matches = response.body();

                if (areAllMatchesCompleted(matches)) {
                    logger.info("All matches completed for tournament ID: {}. Finalizing tournament.", tournamentId);
                    finalizeTournament(tournamentId);
                } else {
                    logger.info("Not all matches completed for tournament ID: {}. Notifying next matches.", tournamentId);
                    tournamentData.setRoundNotified(false);
                    notifyNextMatches(tournamentData);
                }
            } else {
                String errorBody = response.errorBody() != null ? response.errorBody().string() : "Unknown error";
                logger.error("Failed to retrieve matches for Tournament ID {}: {} {}", tournamentId, response.code(), response.message());
            }
        } catch (IOException e) {
            logger.error("Error checking round completion for Tournament ID {}", tournamentId, e);
        }
    }

    private boolean areAllMatchesCompleted(List<ChallongeDataClasses.MatchWrapper> matches) {
        return matches.stream().allMatch(wrapper -> "complete".equalsIgnoreCase(wrapper.match.state));
    }



    private ChallongeDataClasses.Participant getParticipantByDiscordId(Long tournamentId, String discordId) throws IOException {
        Map<String, String> options = new HashMap<>();
        Call<List<ChallongeDataClasses.ParticipantWrapper>> call = challongeService.getParticipants(tournamentId, options);
        Response<List<ChallongeDataClasses.ParticipantWrapper>> response = call.execute();

        if (response.isSuccessful() && response.body() != null) {
            for (ChallongeDataClasses.ParticipantWrapper wrapper : response.body()) {
                if (wrapper.participant.misc.equals(discordId)) {
                    return wrapper.participant;
                }
            }
        }
        return null;
    }

    private int calculateIndividualEloChange(int rank, int totalParticipants, String tournamentType) {
        int baseChange = 32; // Maximum ELO change
        double rankPercentile = (double) rank / totalParticipants;

        // Adjust the ELO change based on tournament type
        switch (tournamentType.toLowerCase()) {
            case "single elimination":
                return (int) Math.round(baseChange * (1 - rankPercentile)) - (baseChange / 2);
            case "double elimination":
                // Slightly higher ELO changes for double elimination
                return (int) Math.round(1.2 * baseChange * (1 - rankPercentile)) - (baseChange / 2);
            case "round robin":
                // More conservative ELO changes for round robin
                return (int) Math.round(0.8 * baseChange * (1 - rankPercentile)) - (baseChange / 2);
            default:
                return (int) Math.round(baseChange * (1 - rankPercentile)) - (baseChange / 2);
        }
    }

    /**
     * Retrieves the Discord User ID associated with a Challonge Participant ID.
     *
     * @param tournamentId  The unique ID of the tournament.
     * @param participantId The unique ID of the Challonge participant.
     * @return Discord User ID as a String.
     */
    private String getDiscordUserIdByChallongeParticipantId(Long tournamentId, Long participantId) {
        try {
            Call<ChallongeDataClasses.ParticipantWrapper> call = challongeService.getParticipant(tournamentId, participantId, new HashMap<>());
            Response<ChallongeDataClasses.ParticipantWrapper> response = call.execute();

            if (response.isSuccessful() && response.body() != null) {
                return response.body().participant.misc;
            }
        } catch (IOException e) {
            logger.error("IOException while fetching Discord User ID for Participant ID {}", participantId, e);
        }
        return "";
    }

    /**
     * Finds the active tournament associated with a given Discord channel ID.
     *
     * @param channelId The Discord channel ID.
     * @return TournamentData if found, else null.
     */
    private TournamentData findTournamentByChannel(Long channelId) {
        return activeTournaments.values().stream()
                .filter(tournamentData -> tournamentData.getChannel().getIdLong() == (channelId))
                .findFirst()
                .orElse(null);
    }

    public void startPeriodicChecks(Long tournamentId) {
        Runnable checkTask = () -> {
            TournamentData tournament = getTournamentById(tournamentId);
            if (tournament != null) {
                checkTournamentCompletion(tournamentId);
            }
        };

        ScheduledFuture<?> future = scheduler.scheduleAtFixedRate(checkTask, 0, 5, TimeUnit.MINUTES);
        tournamentSchedulers.put(tournamentId, future);
        logger.info("Started periodic checks for tournament ID: {}", tournamentId);
    }

    public void stopPeriodicChecks(Long tournamentId) {
        ScheduledFuture<?> future = tournamentSchedulers.remove(tournamentId);
        if (future != null) {
            future.cancel(false);
            logger.info("Stopped periodic checks for tournament ID: {}", tournamentId);
        }
    }

    private void replyToEvent(GenericInteractionCreateEvent event, String message, boolean ephemeral) {
        if (event instanceof SlashCommandInteractionEvent) {
            ((SlashCommandInteractionEvent) event).reply(message).setEphemeral(ephemeral).queue();
        } else if (event instanceof ButtonInteractionEvent) {
            ((ButtonInteractionEvent) event).reply(message).setEphemeral(ephemeral).queue();
        }
    }

    public void handleResolveDiscrepancyWithScore(ButtonInteractionEvent event, String matchIdStr, int score1, int score2) {
        // Extract match ID and validate
        Long matchId;
        try {
            matchId = Long.parseLong(matchIdStr);
        } catch (NumberFormatException e) {
            logger.error("Invalid match ID format: {}", matchIdStr, e);
            event.reply("❌ Invalid match ID format.").setEphemeral(true).queue();
            return;
        }

        // Identify the tournament based on the match ID
        Long tournamentId = extractTournamentIdFromMatchId(matchId);
        if (tournamentId == null) {
            event.reply("❌ Unable to identify the tournament for this match.").setEphemeral(true).queue();
            return;
        }

        TournamentData tournamentData = getTournamentById(tournamentId);
        if (tournamentData == null) {
            event.reply("❌ Tournament data not found. Please try again later.").setEphemeral(true).queue();
            return;
        }

        try {
            // Fetch the match details
            Call<ChallongeDataClasses.MatchWrapper> fetchMatchCall = challongeService.getMatch(tournamentId, matchId, new HashMap<>());
            Response<ChallongeDataClasses.MatchWrapper> fetchMatchResponse = fetchMatchCall.execute();

            if (!fetchMatchResponse.isSuccessful() || fetchMatchResponse.body() == null) {
                String errorBody = fetchMatchResponse.errorBody() != null ? fetchMatchResponse.errorBody().string() : "Unknown error";
                logger.error("Failed to fetch match ID {}: {} {}", matchId, fetchMatchResponse.code(), fetchMatchResponse.message());
                event.reply("❌ Failed to fetch match details. Please try again later.").setEphemeral(true).queue();
                return;
            }

            ChallongeDataClasses.MatchData match = fetchMatchResponse.body().match;

            if (!"pending".equalsIgnoreCase(match.state)) {
                event.reply("❌ This match is not pending resolution.").setEphemeral(true).queue();
                return;
            }

            // Determine the winner based on the resolved scores
            String winnerId;
            if (score1 > score2) {
                winnerId = String.valueOf(match.player1Id);
            } else if (score2 > score1) {
                winnerId = String.valueOf(match.player2Id);
            } else {
                // Tie situation, cannot resolve without a clear winner
                event.reply("⚠️ The resolved scores result in a tie. Please ensure there is a clear winner.").setEphemeral(true).queue();
                return;
            }

            // Prepare match update parameters
            Map<String, String> matchParams = new HashMap<>();
            Map<String, Object> winnerData = new HashMap<>();
            winnerData.put("winner_id", winnerId);
            matchParams.put("match", winnerData.toString());

            // Update the match with the resolved winner
            Call<ChallongeDataClasses.MatchWrapper> updateMatchCall = challongeService.updateMatch(tournamentId, matchId, matchParams, winnerData);
            Response<ChallongeDataClasses.MatchWrapper> updateMatchResponse = updateMatchCall.execute();

            if (updateMatchResponse.isSuccessful() && updateMatchResponse.body() != null) {
                event.reply("✅ The match has been resolved successfully!").queue();
                logger.info("Match ID {} in Tournament ID {} resolved with winner '{}'.", matchId, tournamentId, winnerId);

                // Optionally, trigger an immediate tournament completion check
                checkTournamentCompletion(tournamentId);
            } else {
                String errorBody = updateMatchResponse.errorBody() != null ? updateMatchResponse.errorBody().string() : "Unknown error";
                logger.error("Failed to update match ID {}: {} {}", matchId, updateMatchResponse.code(), updateMatchResponse.message());
                event.reply("❌ Failed to resolve the match. Please try again.").setEphemeral(true).queue();
            }
        } catch (IOException e) {
            logger.error("IOException while resolving discrepancy for match ID {}", matchId, e);
            event.reply("❌ An error occurred while resolving the discrepancy. Please try again later.").setEphemeral(true).queue();
        }
    }

    /**
     * Extracts the tournament ID from the match ID by searching through active tournaments.
     *
     * @param matchId The unique ID of the match.
     * @return The tournament ID if found, else null.
     */
    /**
     * Extracts the tournament ID from the match ID by searching through active tournaments.
     *
     * @param matchId The unique ID of the match.
     * @return The tournament ID if found, else null.
     */
    private Long extractTournamentIdFromMatchId(Long matchId) {
        for (Map.Entry<Long, TournamentData> entry : activeTournaments.entrySet()) {
            TournamentData tournamentData = entry.getValue();
            if (tournamentData.getMatchToTournamentMap().containsKey(matchId)) {
                return entry.getKey();
            }
        }
        logger.warn("No tournament found for match ID: {}", matchId);
        return null;
    }

    /**
     * Retrieves a match by its ID within a specific tournament.
     *
     * @param tournamentId The ID of the tournament.
     * @param matchId      The ID of the match.
     * @return The MatchData if found, else null.
     * @throws IOException If an I/O error occurs.
     */
    private ChallongeDataClasses.MatchData getMatchById(Long tournamentId, Long matchId) throws IOException {
        Call<ChallongeDataClasses.MatchWrapper> call = challongeService.getMatch(tournamentId, matchId, new HashMap<>());
        Response<ChallongeDataClasses.MatchWrapper> response = call.execute();

        if (response.isSuccessful() && response.body() != null) {
            return response.body().match;
        }
        return null;
    }


    private void notifyParticipants(User winner, User loser, String message) {
        winner.openPrivateChannel().queue(channel ->
                channel.sendMessage(message + " You won!").queue()
        );
        loser.openPrivateChannel().queue(channel ->
                channel.sendMessage(message + " You lost.").queue()
        );
    }

    public void handleCreateTournament(SlashCommandInteractionEvent event) {
        if (!userHasOrganizerRole(event.getMember())) {
            replyToEvent(event, "❌ You do not have permission to create a tournament.", true);
            return;
        }
        try {
            Map<String, Object> tournamentParams = new HashMap<>();
            String name = event.getOption("name").getAsString();
            String type = event.getOption("type").getAsString().toLowerCase();
            List<String> validTypes = Arrays.asList("single", "single elimination", "double", "double elimination", "roundrobin", "round robin");
            if (!validTypes.contains(type)) {
                event.reply("❌ Invalid tournament type. Please choose from single, double, or roundrobin.").setEphemeral(true).queue();
                return;
            }
            String url = generateUniqueUrl();
            tournamentParams.put("name", name);
            tournamentParams.put("url", url);
            tournamentParams.put("tournament_type", mapTournamentType(type));
            tournamentParams.put("private", false);
            tournamentParams.put("open_signup", true); // Allow participants to sign up

            Map<String, Object> params = new HashMap<>();
            params.put("tournament", tournamentParams);

            // Create tournament via Challonge API
            Call<ChallongeDataClasses.TournamentWrapper> call = challongeService.createTournament(params);
            Response<ChallongeDataClasses.TournamentWrapper> response = call.execute();

            if (response.isSuccessful() && response.body() != null) {
                ChallongeDataClasses.Tournament createdTournament = response.body().tournament;
                Long tournamentId = createdTournament.id;
                String mappedType = createdTournament.tournamentType.toLowerCase();

                logger.info("Tournament '{}' created with ID: {}", createdTournament.name, tournamentId);


                // Store tournament data
                TournamentData tournamentData = new TournamentData(tournamentId, mappedType, event.getChannel(), createdTournament);
                activeTournaments.put(tournamentId, tournamentData);

                String bracketUrl = "https://challonge.com/" + url;
                // Schedule periodic checks for the new tournament


//                event.getChannel().sendMessage("🏆 Tournament '" + name + "' has been created successfully!Tournament ID : " + tournamentId).queue();
                EmbedBuilder embed = new EmbedBuilder();
                embed.setTitle("🏆 " + name + " Tournament Created!");
                embed.setDescription("A new tournament has been created. Below are the details and instructions on how to participate.");
                embed.addField("Name", name, true);
                embed.addField("Type", mappedType.substring(0, 1).toUpperCase() + mappedType.substring(1), true);
                embed.addField("URL", bracketUrl, false);
                embed.addField("Instructions", "🔹 **Register:** Use the `/register` command to join the tournament (or click register).\n" +
                        "🔹 **Report Results:** After a match, use `/report` to submit your results.\n" +
                        "🔹 **Resolve Discrepancies:** If there's a score mismatch, follow the prompts to resolve it.", false);
                embed.setColor(Color.GREEN);
                embed.setTimestamp(Instant.now());

                Button registerButton = Button.primary("tournament_register", "Register");

                event.replyEmbeds(embed.build())
                        .addActionRow(registerButton)
                        .queue();
                try {
                    Call<List<ChallongeDataClasses.MatchWrapper>> matchesCall = challongeService.getMatches(tournamentId, new HashMap<>());
                    Response<List<ChallongeDataClasses.MatchWrapper>> matchesResponse = matchesCall.execute();

                    if (matchesResponse.isSuccessful() && matchesResponse.body() != null) {
                        List<ChallongeDataClasses.MatchWrapper> matches = matchesResponse.body();
                        for (ChallongeDataClasses.MatchWrapper matchWrapper : matches) {
                            tournamentData.getMatchToTournamentMap().put(matchWrapper.match.id, tournamentId);
                        }
                    } else {
                        logger.warn("No matches found for Tournament ID {} or failed to fetch matches.", tournamentId);
                    }
                } catch (IOException e) {
                    logger.error("IOException while fetching matches for Tournament ID {}", tournamentId, e);
                }
            } else {
                String errorBody = response.errorBody() != null ? response.errorBody().string() : "Unknown error";
                logger.error("Failed to create tournament: " + response.code() + " " + response.message() + "\n" + errorBody);
                event.getChannel().sendMessage("❌ Failed to create tournament. Please try again.").queue();
            }
        } catch (IOException e) {
            logger.error("IOException while creating tournament", e);
            event.getChannel().sendMessage("❌ An error occurred while creating the tournament. Please try again.").queue();
        }
    }

    private Long identifyTournamentId(SlashCommandInteractionEvent event) {
        MessageChannelUnion channel = event.getChannel();
        for (Map.Entry<Long, TournamentData> entry : activeTournaments.entrySet()) {
            if (entry.getValue().getChannel().equals(channel)) {
                return entry.getKey();
            }
        }
        return null;
    }

    public void handleStartTournament(SlashCommandInteractionEvent event) {
        Long tournamentId = identifyTournamentId(event);
        if (tournamentId == null) {
            replyToEvent(event, "❌ There is no active tournament in this channel to start.", true);
            return;
        }

        ChallongeDataClasses.Tournament tournament = getTournamentById(tournamentId).getTournament();
        if (tournament == null) {
            replyToEvent(event, "❌ Tournament data not found. Please try again later.", true);
            return;
        }

        TournamentData tournamentData = activeTournaments.get(tournamentId);
        if (tournamentData == null) {
            replyToEvent(event, "❌ Tournament data not found. Please try again later.", true);
            return;
        }
        // Check if the tournament is already started
        if (tournamentData.isStarted()) {
            replyToEvent(event, "⚠️ The tournament has already been started.", true);
            return;
        }

        // Optional: Verify if the user has organizer roles
        if (!userHasOrganizerRole(event.getMember())) {
            replyToEvent(event, "❌ You do not have permission to start the tournament.", true);
            return;
        }

        try {
            // Start the tournament via Challonge API
            Call<ChallongeDataClasses.TournamentWrapper> call = challongeService.startTournament(tournamentId, new HashMap<>());
            Response<ChallongeDataClasses.TournamentWrapper> response = call.execute();

            if (response.isSuccessful() && response.body() != null) {
                // Update the tournament as started
                tournamentData.setStarted(true);
                tournamentData.setTournament(response.body().tournament);

                // Schedule periodic checks for the tournament
                ScheduledFuture<?> scheduledTask = scheduler.scheduleAtFixedRate(() -> {
                    checkTournamentCompletion(tournamentId);
                }, 0, 5, TimeUnit.MINUTES); // Adjust interval as needed

                tournamentData.setScheduler(scheduledTask);
                EmbedBuilder embed = new EmbedBuilder();
                embed.setTitle("✅ Tournament Started!");
                embed.setDescription("The tournament has officially begun. Here's how to participate:");
                embed.addField("View Your Matches", "Check the tournament page at [Challonge] Bracket link above" + ") to see your upcoming matches.", false);
                embed.addField("Report Results", "After completing a match, use the `/report` command to submit your results.", false);
                embed.addField("Resolve Discrepancies", "If there's a disagreement on match scores, follow the prompts to resolve the discrepancy.", false);
                embed.setColor(Color.BLUE);
                embed.setTimestamp(Instant.now());

                event.replyEmbeds(embed.build())
                        .setEphemeral(false)
                        .queue();
                event.reply("✅ Tournament has been started!").setEphemeral(false).queue();
                logger.info("Tournament ID {} has been started.", tournamentId);
                notifyNextMatches(tournamentData);
            } else {
                String errorBody = response.errorBody() != null ? response.errorBody().string() : "Unknown error";
                logger.error("Failed to start tournament ID {}: " + response.code() + " " + response.message() + "\n" + errorBody);
                event.reply("❌ Failed to start the tournament. Please try again later.")
                        .setEphemeral(true)
                        .queue();
            }
        } catch (IOException e) {
            logger.error("IOException while starting tournament ID {}", tournamentId, e);
            event.reply("❌ An error occurred while starting the tournament. Please try again later.")
                    .setEphemeral(true)
                    .queue();
        }
    }

    private void updateTournamentData(Long tournamentId, ChallongeDataClasses.Tournament updatedTournament) {
        TournamentData tournamentData = activeTournaments.get(tournamentId);
        if (tournamentData != null) {
            tournamentData.setTournament(updatedTournament);
        } else {
            logger.warn("Attempted to update non-existent tournament with ID: {}", tournamentId);
        }
    }


//    public void handleMatchesCommand(SlashCommandInteractionEvent event) {
//        User user = event.getUser();
//        List<Match> activeMatches = getActiveMatches(user);
//
//        if (activeMatches.isEmpty()) {
//            event.reply("You have no ongoing matches at the moment.").setEphemeral(true).queue();
//            return;
//        }
//
//        EmbedBuilder embed = new EmbedBuilder()
//                .setTitle("Your Active Matches")
//                .setDescription("Here are your current ongoing matches:")
//                .setColor(Color.BLUE)
//                .setTimestamp(Instant.now());
//
//        for (Match match : activeMatches) {
//            String matchType = match.getMatchType() == Match.MatchType.RANKED ? "Ranked" : "Tournament";
//            String opponent = match.getOpponent(user).getName();
//            String score = match.getScore();
//
//            embed.addField(matchType + " Match",
//                    "Opponent: " + opponent + "\n" +
//                            "Current Score: " + score + "\n" +
//                            "Match ID: " + match.getMatchId(),
//                    false);
//        }
//
//        event.replyEmbeds(embed.build()).setEphemeral(true).queue();
//    }



    private boolean checkAllMatchesCompleted(TournamentData tournamentData) {
        Long tournamentId = tournamentData.getTournamentId();

        try {
            Map<String, String> options = new HashMap<>();
            Call<List<ChallongeDataClasses.MatchWrapper>> call = challongeService.getMatches(tournamentId, options);
            Response<List<ChallongeDataClasses.MatchWrapper>> response = call.execute();

            if (response.isSuccessful() && response.body() != null) {
                List<ChallongeDataClasses.MatchWrapper> matches = response.body();

                // Check if all matches are completed
                return matches.stream().allMatch(wrapper -> "complete".equalsIgnoreCase(wrapper.match.state));
            } else {
                String errorBody = response.errorBody() != null ? response.errorBody().string() : "Unknown error";
                logger.error("Failed to retrieve matches for Tournament ID {}: {} {}", tournamentId, response.code(), response.message());
                return false;
            }
        } catch (IOException e) {
            logger.error("Error checking match completion for Tournament ID {}", tournamentId, e);
            return false;
        }
    }

    private boolean isTournamentComplete(TournamentData tournamentData) {
        Long tournamentId = tournamentData.getTournamentId();

        try {
            Map<String, String> options = new HashMap<>();
            Call<ChallongeDataClasses.TournamentWrapper> call = challongeService.getTournament(tournamentId, options);
            Response<ChallongeDataClasses.TournamentWrapper> response = call.execute();

            if (response.isSuccessful() && response.body() != null) {
                ChallongeDataClasses.Tournament tournament = response.body().tournament;
                return "complete".equalsIgnoreCase(tournament.state);
            } else {
                String errorBody = response.errorBody() != null ? response.errorBody().string() : "Unknown error";
                logger.error("Failed to retrieve tournament info for ID {}: {} {}", tournamentId, response.code(), response.message());
                return false;
            }
        } catch (IOException e) {
            logger.error("Error checking tournament completion for ID {}", tournamentId, e);
            return false;
        }
    }
    private void notifyNextMatches(TournamentData tournamentData) {
        MessageChannelUnion channel = tournamentData.getChannel();
        Long tournamentId = tournamentData.getTournamentId();

        try {
            Map<String, String> options = new HashMap<>();
            options.put("state", "open");

            Call<List<ChallongeDataClasses.MatchWrapper>> call = challongeService.getMatches(tournamentId, options);
            Response<List<ChallongeDataClasses.MatchWrapper>> response = call.execute();

            if (response.isSuccessful() && response.body() != null) {
                List<ChallongeDataClasses.MatchWrapper> matches = response.body();

                if ("round robin".equalsIgnoreCase(tournamentData.getTournamentType())) {
                    if (!tournamentData.isRoundNotified()) {
                        notifyRoundRobinMatches(tournamentData, matches, channel);
                        tournamentData.setRoundNotified(true);
                    }
                } else {
                    notifyEliminationMatches(tournamentData, matches, channel);
                }
            } else {
                String errorBody = response.errorBody() != null ? response.errorBody().string() : "Unknown error";
                logger.error("Failed to retrieve matches for Tournament ID {}: {} {}", tournamentId, response.code(), response.message());
            }
        } catch (IOException e) {
            logger.error("Error notifying next matches for Tournament ID {}", tournamentId, e);
        }
    }

    private void notifyRoundRobinMatches(TournamentData tournamentData, List<ChallongeDataClasses.MatchWrapper> matches, MessageChannelUnion channel) {
        EmbedBuilder embed = new EmbedBuilder()
                .setTitle("🏓 Round Robin Matches")
                .setDescription("Here are all the matches for the round robin tournament. Play everyone listed below once.")
                .setColor(Color.BLUE);

        Map<String, List<String>> playerMatches = new HashMap<>();

        for (ChallongeDataClasses.MatchWrapper matchWrapper : matches) {
            ChallongeDataClasses.MatchData match = matchWrapper.match;

            ChallongeDataClasses.Participant participant1 = getParticipantById(tournamentData.getTournamentId(), String.valueOf(match.player1Id));
            ChallongeDataClasses.Participant participant2 = getParticipantById(tournamentData.getTournamentId(), String.valueOf(match.player2Id));

            if (participant1 == null || participant2 == null) continue;

            User user1 = channel.getJDA().retrieveUserById(participant1.misc).complete();
            User user2 = channel.getJDA().retrieveUserById(participant2.misc).complete();

            addToPlayerMatches(playerMatches, user1, user2);
            addToPlayerMatches(playerMatches, user2, user1);

            tournamentData.getNotifiedMatches().add(match.id);
        }

        for (Map.Entry<String, List<String>> entry : playerMatches.entrySet()) {
            String playerName = entry.getKey();
            List<String> opponents = entry.getValue();
            embed.addField(playerName, String.join(", ", opponents), false);
        }

        channel.sendMessageEmbeds(embed.build()).queue();
    }

    // ... rest of the methods remain the same
    private void addToPlayerMatches(Map<String, List<String>> playerMatches, User player, User opponent) {
        playerMatches.computeIfAbsent(player.getName(), k -> new ArrayList<>()).add(opponent.getName());
    }

    private void notifyEliminationMatches(TournamentData tournamentData, List<ChallongeDataClasses.MatchWrapper> matches, MessageChannelUnion channel) {
        for (ChallongeDataClasses.MatchWrapper matchWrapper : matches) {
            ChallongeDataClasses.MatchData match = matchWrapper.match;

            // Skip if already notified
            if (tournamentData.getNotifiedMatches().contains(match.id)) {
                continue;
            }

            ChallongeDataClasses.Participant participant1 = getParticipantById(tournamentData.getTournamentId(), String.valueOf(match.player1Id));
            ChallongeDataClasses.Participant participant2 = getParticipantById(tournamentData.getTournamentId(), String.valueOf(match.player2Id));

            if (participant1 == null || participant2 == null) continue;

            User user1 = channel.getJDA().retrieveUserById(participant1.misc).complete();
            User user2 = channel.getJDA().retrieveUserById(participant2.misc).complete();

            EmbedBuilder embed = new EmbedBuilder()
                    .setTitle("🏁 New Tournament Match")
                    .setDescription("A new match is ready for play.")
                    .addField("Match ID", String.valueOf(match.id), false)
                    .addField("Players", user1.getAsMention() + " vs " + user2.getAsMention(), false)
                    .addField("How to Report", "Use the `/report` command to submit your match results.", false)
                    .setColor(Color.PINK)
                    .setTimestamp(Instant.now());

            channel.sendMessageEmbeds(embed.build()).queue();

            sendDirectMatchNotification(user1, user2, match);
            sendDirectMatchNotification(user2, user1, match);

            tournamentData.getNotifiedMatches().add(match.id);
        }
    }

    private void sendDirectMatchNotification(User user, User opponent, ChallongeDataClasses.MatchData match) {
        EmbedBuilder embed = new EmbedBuilder()
                .setTitle("🏆 Your Next Tournament Match")
                .setDescription("You have a new match ready to play.")
                .addField("Match ID", String.valueOf(match.id), false)
                .addField("Opponent", opponent.getName(), false)
                .addField("How to Report", "After completing your match, use the `/report` command to submit your results.", false)
                .setColor(Color.ORANGE)
                .setTimestamp(Instant.now());

        user.openPrivateChannel().queue(privateChannel ->
                        privateChannel.sendMessageEmbeds(embed.build()).queue(),
                throwable -> logger.error("Failed to send DM to user: {}", user.getAsTag(), throwable)
        );
    }


    /**
     * Checks if the member has any of the organizer roles.
     *
     * @param member The guild member.
     * @return True if the member has an organizer role, else false.
     */
    private boolean userHasOrganizerRole(Member member) {
        if (member == null) return false;
        return member.getRoles().stream()
                .anyMatch(role -> tournamentOrganizerRoleNames.contains(role.getName()));
    }

    public void handleRandomizeSeeding(SlashCommandInteractionEvent event) {
        Long tournamentId = identifyTournamentId(event);
        if (tournamentId == null) {
            replyToEvent(event, "❌ There is no active tournament in this channel to randomize.", true);
            return;
        }

        ChallongeDataClasses.Tournament tournament = getTournamentById(tournamentId).getTournament();
        if (tournament == null) {
            replyToEvent(event, "❌ Tournament data not found. Please try again later.", true);
            return;
        }

        TournamentData tournamentData = activeTournaments.get(tournamentId);
        if (tournamentData.isStarted()) {
            replyToEvent(event, "⚠️ Cannot randomize seeding after the tournament has started.", true);
            return;
        }

        if (!userHasOrganizerRole(event.getMember())) {
            replyToEvent(event, "❌ You do not have permission to randomize seeding.", true);
            return;
        }

        try {
            Call<ChallongeDataClasses.TournamentWrapper> call = challongeService.randomizeTournamentSeeding(tournamentId, new HashMap<>());
            Response<ChallongeDataClasses.TournamentWrapper> response = call.execute();

            if (response.isSuccessful() && response.body() != null) {
                tournamentData.setTournament(response.body().tournament);
                event.reply("✅ Seeding has been randomized successfully!").setEphemeral(false).queue();
                logger.info("Seeding randomized for Tournament ID {}.", tournamentId);
            } else {
                String errorBody = response.errorBody() != null ? response.errorBody().string() : "Unknown error";
                logger.error("Failed to randomize seeding for Tournament ID {}: " + response.code() + " " + response.message() + "\n" + errorBody, tournamentId);
                event.reply("❌ Failed to randomize seeding. Please try again later.")
                        .setEphemeral(true)
                        .queue();
            }
        } catch (IOException e) {
            logger.error("IOException while randomizing seeding for Tournament ID {}", tournamentId, e);
            event.reply("❌ An error occurred while randomizing seeding. Please try again later.")
                    .setEphemeral(true)
                    .queue();
        }
    }

    public void handleRegisterCommand(GenericInteractionCreateEvent event) {
        Long tournamentId = identifyTournamentId(event);
        if (tournamentId == null) {
            replyToEvent(event, "❌ There is no active tournament in this channel to register for.", true);
            return;
        }

        ChallongeDataClasses.Tournament tournament = getTournamentById(tournamentId).getTournament();
        if (tournament == null) {
            replyToEvent(event, "❌ Tournament data not found. Please try again later.", true);
            return;
        }

        TournamentData tournamentData = activeTournaments.get(tournamentId);
        User player = event.getUser();

        if (tournamentData.getParticipants().containsKey(player.getId())) {
            replyToEvent(event, "⚠️ You are already registered for this tournament.", true);
            return;
        }

        Map<String, Object> participantParams = new HashMap<>();
        participantParams.put("name", player.getName());
        participantParams.put("misc", player.getId());

        Map<String, Object> params = new HashMap<>();
        params.put("participant", participantParams);

        try {
            Call<ChallongeDataClasses.ParticipantWrapper> call = challongeService.addParticipant(tournamentId, new HashMap<>(), params);
            Response<ChallongeDataClasses.ParticipantWrapper> response = call.execute();

            if (response.isSuccessful() && response.body() != null) {
                ChallongeDataClasses.Participant participant = response.body().participant;
                tournamentData.getParticipants().put(player.getId(), participant);
                replyToEvent(event, "✅ You have been registered for the tournament!", true);
                logger.info("User '{}' registered for Tournament ID {}.", player.getAsTag(), tournamentId);
            } else {
                String errorBody = response.errorBody() != null ? response.errorBody().string() : "Unknown error";
                logger.error("Failed to register user '{}': " + response.code() + " " + response.message() + "\n" + errorBody, player.getAsTag());
                replyToEvent(event, "❌ Failed to register for the tournament. Please ensure you meet the requirements and try again.", true);
            }
        } catch (IOException e) {
            logger.error("IOException while registering user '{}'", player.getAsTag(), e);
            replyToEvent(event, "❌ An error occurred while registering for the tournament. Please try again later.", true);
        }
    }

    /**
     * Retrieves a TournamentData object by tournament ID.
     *
     * @param tournamentId The unique ID of the tournament.
     * @return TournamentData if found, else null.
     */
    public TournamentData getTournamentById(Long tournamentId) {
        return activeTournaments.get(tournamentId);
    }


    private void checkTournamentCompletion(Long tournamentId) {
        TournamentData tournamentData = activeTournaments.get(tournamentId);
        if (tournamentData == null) {
            logger.warn("No active tournament found with ID: {}", tournamentId);
            return;
        }

        MessageChannelUnion channel = tournamentData.getChannel();

        try {
            Call<ChallongeDataClasses.TournamentWrapper> call = challongeService.getTournament(tournamentId, new HashMap<>());
            Response<ChallongeDataClasses.TournamentWrapper> response = call.execute();

            if (response.isSuccessful() && response.body() != null) {
                ChallongeDataClasses.Tournament updatedTournament = response.body().tournament;
                tournamentData.setTournament(updatedTournament);

                TournamentCompletionStrategy strategy = getCompletionStrategy(updatedTournament.tournamentType);

                if (strategy.isComplete(updatedTournament)) {
                    finalizeTournament(tournamentId);
                } else {
                    notifyNextMatches(tournamentData);
                }
            } else {
                String errorBody = response.errorBody() != null ? response.errorBody().string() : "Unknown error";
                logger.error("Failed to fetch tournament details for ID {}: {} {}", tournamentId, response.code(), response.message());
            }
        } catch (IOException e) {
            logger.error("IOException while checking tournament completion for ID {}", tournamentId, e);
        }
    }

    private TournamentCompletionStrategy getCompletionStrategy(String tournamentType) {
        switch (tournamentType.toLowerCase()) {
            case "single elimination":
                return new SingleEliminationCompletionStrategy();
            case "double elimination":
                return new DoubleEliminationCompletionStrategy();
            case "round robin":
                return new RoundRobinCompletionStrategy();
            default:
                logger.warn("Unknown tournament type: {}. Using default completion strategy.", tournamentType);
                return tournament -> "complete".equalsIgnoreCase(tournament.state);
        }
    }

    private void handleSingleEliminationCompletion(Long tournamentId, ChallongeDataClasses.Tournament updatedTournament) {
        if ("complete".equals(updatedTournament.state.toLowerCase())) {
            logger.debug("Single Elimination Tournament ID {} is complete.", tournamentId);
            finalizeTournament(tournamentId);
        } else {
            // Optionally, verify if the final match is complete
            try {
                Call<List<ChallongeDataClasses.MatchWrapper>> matchesCall = challongeService.getMatches(tournamentId, new HashMap<>());
                Response<List<ChallongeDataClasses.MatchWrapper>> matchesResponse = matchesCall.execute();

                if (matchesResponse.isSuccessful() && matchesResponse.body() != null) {
                    List<ChallongeDataClasses.MatchWrapper> matches = matchesResponse.body();
                    int totalRounds = getTotalRounds(updatedTournament);
                    Optional<ChallongeDataClasses.MatchWrapper> finalMatchOpt = matches.stream()
                            .filter(matchWrapper -> matchWrapper.match.round != null && matchWrapper.match.round == totalRounds)
                            .findFirst();

                    if (finalMatchOpt.isPresent()) {
                        ChallongeDataClasses.MatchWrapper finalMatch = finalMatchOpt.get();
                        if ("complete".equals(finalMatch.match.state.toLowerCase())) {
                            logger.debug("Final match for Single Elimination Tournament ID {} is complete.", tournamentId);
                            finalizeTournament(tournamentId);
                        } else {
                            logger.debug("Final match for Single Elimination Tournament ID {} is not yet complete.", tournamentId);
                        }
                    } else {
                        logger.warn("Final match not found for Single Elimination Tournament ID {}", tournamentId);
                    }
                } else {
                    String errorBody = matchesResponse.errorBody() != null ? matchesResponse.errorBody().string() : "Unknown error";
                    logger.error("Failed to retrieve matches for Single Elimination Tournament ID {}: {} {}", tournamentId, matchesResponse.code(), matchesResponse.message());
                }
            } catch (IOException e) {
                logger.error("IOException while handling Single Elimination completion for ID {}", tournamentId, e);
            }
        }
    }

    /**
     * Handles completion logic for Double Elimination tournaments.
     *
     * @param tournamentId      The unique ID of the tournament.
     * @param updatedTournament The latest tournament data.
     */
    private void handleDoubleEliminationCompletion(Long tournamentId, ChallongeDataClasses.Tournament updatedTournament) {
        if ("complete".equals(updatedTournament.state.toLowerCase())) {
            logger.debug("Double Elimination Tournament ID {} is complete.", tournamentId);
            finalizeTournament(tournamentId);
        } else {
            // Check if all matches are complete
            try {
                Call<List<ChallongeDataClasses.MatchWrapper>> matchesCall = challongeService.getMatches(tournamentId, new HashMap<>());
                Response<List<ChallongeDataClasses.MatchWrapper>> matchesResponse = matchesCall.execute();

                if (matchesResponse.isSuccessful() && matchesResponse.body() != null) {
                    List<ChallongeDataClasses.MatchWrapper> matches = matchesResponse.body();
                    boolean allMatchesComplete = matches.stream()
                            .allMatch(matchWrapper -> "complete".equals(matchWrapper.match.state.toLowerCase()));

                    if (allMatchesComplete) {
                        logger.debug("All matches for Double Elimination Tournament ID {} are complete.", tournamentId);
                        finalizeTournament(tournamentId);
                    } else {
                        logger.debug("Not all matches for Double Elimination Tournament ID {} are complete.", tournamentId);
                    }
                } else {
                    String errorBody = matchesResponse.errorBody() != null ? matchesResponse.errorBody().string() : "Unknown error";
                    logger.error("Failed to retrieve matches for Double Elimination Tournament ID {}: {} {}", tournamentId, matchesResponse.code(), matchesResponse.message());
                }
            } catch (IOException e) {
                logger.error("IOException while handling Double Elimination completion for ID {}", tournamentId, e);
            }
        }
    }

    /**
     * Handles completion logic for Round Robin tournaments.
     *
     * @param tournamentId      The unique ID of the tournament.
     * @param updatedTournament The latest tournament data.
     */
    private void handleRoundRobinCompletion(Long tournamentId, ChallongeDataClasses.Tournament updatedTournament) {
        if ("complete".equals(updatedTournament.state.toLowerCase())) {
            logger.debug("Round Robin Tournament ID {} is complete.", tournamentId);
            finalizeTournament(tournamentId);
        } else {
            // Check if all matches are complete
            try {
                Call<List<ChallongeDataClasses.MatchWrapper>> matchesCall = challongeService.getMatches(tournamentId, new HashMap<>());
                Response<List<ChallongeDataClasses.MatchWrapper>> matchesResponse = matchesCall.execute();

                if (matchesResponse.isSuccessful() && matchesResponse.body() != null) {
                    List<ChallongeDataClasses.MatchWrapper> matches = matchesResponse.body();
                    boolean allMatchesComplete = matches.stream()
                            .allMatch(matchWrapper -> "complete".equals(matchWrapper.match.state.toLowerCase()));

                    if (allMatchesComplete) {
                        logger.debug("All matches for Round Robin Tournament ID {} are complete.", tournamentId);
                        finalizeTournament(tournamentId);
                    } else {
                        logger.debug("Not all matches for Round Robin Tournament ID {} are complete.", tournamentId);
                    }
                } else {
                    String errorBody = matchesResponse.errorBody() != null ? matchesResponse.errorBody().string() : "Unknown error";
                    logger.error("Failed to retrieve matches for Round Robin Tournament ID {}: {} {}", tournamentId, matchesResponse.code(), matchesResponse.message());
                }
            } catch (IOException e) {
                logger.error("IOException while handling Round Robin completion for ID {}", tournamentId, e);
            }
        }
    }

    /**
     * Calculates the total number of rounds in a tournament.
     * This is a simplistic implementation and might need adjustments based on tournament structure.
     *
     * @param tournament The tournament data.
     * @return Total number of rounds.
     */
    private int getTotalRounds(ChallongeDataClasses.Tournament tournament) {
        int participants = tournament.participantsCount != null ? tournament.participantsCount : 0;
        return (int) Math.ceil(Math.log(participants) / Math.log(2));
    }

    /**
     * Finalizes the tournament by announcing the winner and cleaning up.
     *
     * @param tournamentId The unique ID of the tournament to finalize.
     */
    /**
     * Shuts down the scheduler gracefully.
     * Call this method when the bot is shutting down to prevent resource leaks.
     */
    public void shutdown() {
        logger.info("Shutting down TournamentManager scheduler...");
        scheduler.shutdown();
        try {
            if (!scheduler.awaitTermination(60, TimeUnit.SECONDS)) {
                scheduler.shutdownNow();
                logger.warn("Scheduler did not terminate within the specified time.");
            }
        } catch (InterruptedException e) {
            scheduler.shutdownNow();
            Thread.currentThread().interrupt();
            logger.error("Scheduler shutdown interrupted.", e);
        }
        logger.info("TournamentManager scheduler shut down successfully.");
    }

    public void shutdownSchedulers() {
        tournamentSchedulers.forEach((id, future) -> {
            future.cancel(false);
            logger.info("Scheduler for tournament ID: {} has been cancelled.", id);
        });
        scheduler.shutdown();
        try {
            if (!scheduler.awaitTermination(60, TimeUnit.SECONDS)) {
                scheduler.shutdownNow();
                logger.warn("Scheduler did not terminate in the specified time.");
            }
        } catch (InterruptedException e) {
            scheduler.shutdownNow();
            Thread.currentThread().interrupt();
            logger.error("Scheduler termination interrupted.", e);
        }
    }

    private void handleTournamentCompletion(MessageChannelUnion channel, ChallongeDataClasses.Tournament updatedTournament) {
        try {
            Call<List<ChallongeDataClasses.ParticipantWrapper>> participantsCall = challongeService.getParticipants(updatedTournament.id, new HashMap<>());
            Response<List<ChallongeDataClasses.ParticipantWrapper>> participantsResponse = participantsCall.execute();

            if (participantsResponse.isSuccessful() && participantsResponse.body() != null) {
                List<ChallongeDataClasses.ParticipantWrapper> participantWrappers = participantsResponse.body();
                participantWrappers.sort(Comparator.comparingInt(pw -> pw.participant.finalRank));

                ChallongeDataClasses.ParticipantWrapper winnerWrapper = participantWrappers.get(0);
                String winnerUserId = winnerWrapper.participant.misc;
                User winnerUser = channel.getJDA().retrieveUserById(winnerUserId).complete();

                channel.sendMessage("The tournament has concluded! Congratulations to the winner: " + winnerUser.getAsMention()).queue();
                logger.info("Tournament '{}' concluded with winner '{}'.", updatedTournament.name, winnerUser.getAsTag());

                // Reset tournament state
                currentTournament = null;
                tournamentParticipants.clear();
            } else {
                logger.error("Failed to retrieve participant standings.");
                channel.sendMessage("Tournament concluded, but failed to retrieve participant standings.").queue();
            }
        } catch (Exception e) {
            logger.error("Error handling tournament completion", e);
            channel.sendMessage("An error occurred while handling tournament completion.").queue();
        }
    }

    public MessageChannelUnion getTournamentChannel() {
        return tournamentChannel;
    }

    // Method to set the tournament channel (to be called when creating the tournament)
    public void setTournamentChannel(MessageChannelUnion channel) {
        this.tournamentChannel = channel;
    }

    private String mapTournamentType(String type) {
        return switch (type.toLowerCase()) {
            case "single" -> "single elimination";
            case "double" -> "double elimination";
            case "roundrobin", "round robin" -> "round robin";
            default -> null;
        };
    }


    private boolean isValidScore(int score1, int score2) {
        return (score1 + score2 >= 3 && score1 + score2 <= 5) &&
                (score1 == 3 || score2 == 3);
    }

    /**
     * Retrieves a participant by their Challonge participant ID within a specific tournament.
     *
     * @param tournamentId  The ID of the tournament.
     * @param participantId The Challonge participant ID.
     * @return The Participant object if found, else null.
     */
    private ChallongeDataClasses.Participant getParticipantById(Long tournamentId, String participantId) {
        TournamentData tournamentData = getTournamentById(tournamentId);
        if (tournamentData == null) return null;
        for (ChallongeDataClasses.Participant participant : tournamentData.getParticipants().values()) {
            if (participant.id.toString().equals(participantId)) {
                return participant;
            }
        }
        return null;
    }


    public void handleResolveDiscrepancy(ButtonInteractionEvent event, String matchId) {
        // Step 1: Identify the tournament associated with the matchId
        Long tournamentId = extractTournamentIdFromMatchId(Long.valueOf(matchId));
        if (tournamentId == null) {
            event.reply("❌ Unable to identify the tournament for this match.").setEphemeral(true).queue();
            return;
        }

        // Step 2: Retrieve the corresponding TournamentData
        TournamentData tournamentData = getTournamentById(tournamentId);
        if (tournamentData == null) {
            event.reply("❌ Tournament data not found. Please try again later.").setEphemeral(true).queue();
            return;
        }

        try {
            // Step 3: Fetch the match details within the identified tournament
            ChallongeDataClasses.MatchData match = getMatchById(tournamentId, Long.valueOf(matchId));
            if (match == null) {
                event.reply("❌ No match found with the provided ID.").setEphemeral(true).queue();
                return;
            }

            ChallongeDataClasses.Participant participant1 = getParticipantById(tournamentId, String.valueOf(match.player1Id));
            ChallongeDataClasses.Participant participant2 = getParticipantById(tournamentId, String.valueOf(match.player2Id));

            if (participant1 == null || participant2 == null) {
                event.reply("❌ Unable to retrieve match participants.").setEphemeral(true).queue();
                return;
            }

            User user1 = event.getJDA().retrieveUserById(participant1.misc).complete();
            User user2 = event.getJDA().retrieveUserById(participant2.misc).complete();

            // Step 4: Create an embed message prompting for correct scores
            EmbedBuilder embed = new EmbedBuilder()
                    .setTitle("⚠️ Resolve Match Discrepancy")
                    .setDescription("There is a discrepancy in the reported scores for this match. Please select the correct score below.")
                    .addField("Match ID", String.valueOf(match.id), false)
                    .addField("Players", user1.getAsMention() + " vs " + user2.getAsMention(), false)
                    .addField("How to Resolve", "Choose the correct score from the options below or cancel the resolution.", false)
                    .setColor(Color.YELLOW)
                    .setTimestamp(Instant.now());

            // Step 5: Present score options as buttons
            event.replyEmbeds(embed.build())
                    .setActionRow(
                            Button.primary("resolve_" + matchId + "_3_0", "3-0"),
                            Button.primary("resolve_" + matchId + "_3_1", "3-1"),
                            Button.primary("resolve_" + matchId + "_3_2", "3-2"),
                            Button.secondary("resolve_" + matchId + "_cancel", "Cancel")
                    )
                    .queue();
        } catch (IOException e) {
            logger.error("Error resolving match discrepancy for matchId {} in tournamentId {}", matchId, tournamentId, e);
            event.reply("❌ An error occurred while resolving the match discrepancy. Please try again later.")
                    .setEphemeral(true)
                    .queue();
        }
    }


    public void handleApproveMatchResult(ButtonInteractionEvent event, String matchId) {
        // Step 1: Identify the tournament associated with the matchId
        Long tournamentId = extractTournamentIdFromMatchId(Long.valueOf(matchId));
        if (tournamentId == null) {
            event.reply("❌ Unable to identify the tournament for this match.").setEphemeral(true).queue();
            return;
        }

        // Step 2: Retrieve the corresponding TournamentData
        TournamentData tournamentData = getTournamentById(tournamentId);
        if (tournamentData == null) {
            event.reply("❌ Tournament data not found. Please try again later.").setEphemeral(true).queue();
            return;
        }

        try {
            // Step 3: Fetch the match details within the identified tournament
            ChallongeDataClasses.MatchData match = getMatchById(tournamentId, Long.valueOf(matchId));
            if (match == null) {
                event.reply("❌ No match found with the provided ID.").setEphemeral(true).queue();
                return;
            }

            // Step 4: Verify if the match is still open
            if (!"open".equalsIgnoreCase(match.state)) {
                event.reply("⚠️ This match has already been processed.").setEphemeral(true).queue();
                return;
            }

            // Step 5: Prepare parameters to update the match result
            Map<String, String> options = new HashMap<>();
            // You can include additional options if required by the Challonge API
            Map<String, Object> matchParams = new HashMap<>();
            matchParams.put("scores_csv", match.scoresCsv);
            matchParams.put("winner_id", match.winnerId);

            Map<String, Object> params = new HashMap<>();
            params.put("match", matchParams);

            // Step 6: Update the match result via Challonge API
            Call<ChallongeDataClasses.MatchWrapper> call = challongeService.updateMatch(tournamentId, match.id, options, params);
            Response<ChallongeDataClasses.MatchWrapper> response = call.execute();

            if (response.isSuccessful() && response.body() != null) {
                // Step 7: Retrieve winner and loser participants
                ChallongeDataClasses.Participant winner = getParticipantById(tournamentId, String.valueOf(match.winnerId));
                String loserParticipantId = String.valueOf(match.winnerId.equals(match.player1Id) ? match.player2Id : match.player1Id);
                ChallongeDataClasses.Participant loser = getParticipantById(tournamentId, loserParticipantId);

                if (winner == null || loser == null) {
                    event.reply("❌ Unable to retrieve match participants.").setEphemeral(true).queue();
                    return;
                }

                // Step 8: Fetch Discord User objects for ELO updates and notifications
                User winnerUser = event.getJDA().retrieveUserById(winner.misc).complete();
                User loserUser = event.getJDA().retrieveUserById(loser.misc).complete();

                // Step 9: Update ELO ratings
                eloManager.updateElo(winnerUser, loserUser);

                // Step 10: Notify participants about the approved match result
                event.reply("✅ Match result has been approved and updated successfully.")
                        .setEphemeral(false)
                        .queue();

                notifyParticipants(winnerUser, loserUser, match.scoresCsv);

                // Step 11: Check if the tournament has concluded
                checkTournamentCompletion(tournamentId);
            } else {
                // Handle unsuccessful API responses
                String errorBody = "";
                try {
                    errorBody = response.errorBody() != null ? response.errorBody().string() : "Unknown error";
                } catch (IOException ioe) {
                    logger.error("Error reading error body from response", ioe);
                }
                logger.error("Failed to approve match: {} {} \n{}", response.code(), response.message(), errorBody);
                event.reply("❌ Failed to approve match result. Please try again later.")
                        .setEphemeral(true)
                        .queue();
            }
        } catch (IOException e) {
            // Handle IO exceptions during API calls
            logger.error("Error approving match result for matchId {} in tournamentId {}", matchId, tournamentId, e);
            event.reply("❌ An error occurred while approving the match result. Please try again later.")
                    .setEphemeral(true)
                    .queue();
        }
    }

    public void handleRejectMatchResult(ButtonInteractionEvent event, String matchIdStr) {
        try {
            Long matchId = Long.parseLong(matchIdStr);
            Long tournamentId = extractTournamentIdFromMatchId(matchId);

            if (tournamentId == null) {
                event.reply("❌ Unable to identify the tournament for this match.").setEphemeral(true).queue();
                return;
            }

            TournamentData tournamentData = getTournamentById(tournamentId);
            if (tournamentData == null) {
                event.reply("❌ Tournament data not found. Please try again later.").setEphemeral(true).queue();
                return;
            }

            ChallongeDataClasses.MatchData match = getMatchById(tournamentId, matchId);
            if (match == null) {
                event.reply("❌ No match found with the provided ID.").setEphemeral(true).queue();
                return;
            }

            if (!"open".equalsIgnoreCase(match.state)) {
                event.reply("⚠️ This match has already been processed.").setEphemeral(true).queue();
                return;
            }

            // Reset the match result
            Map<String, Object> matchParams = new HashMap<>();
            matchParams.put("scores_csv", null);
            matchParams.put("winner_id", null);

            Map<String, String> params = new HashMap<>();
            params.put("match", matchParams.toString());

            Call<ChallongeDataClasses.MatchWrapper> call = challongeService.updateMatch(tournamentId, matchId, params, matchParams);
            Response<ChallongeDataClasses.MatchWrapper> response = call.execute();

            if (response.isSuccessful() && response.body() != null) {
                ChallongeDataClasses.MatchData updatedMatch = response.body().match;

                // Retrieve participants
                ChallongeDataClasses.Participant participant1 = getParticipantById(tournamentId, String.valueOf(updatedMatch.player1Id));
                ChallongeDataClasses.Participant participant2 = getParticipantById(tournamentId, String.valueOf(updatedMatch.player2Id));

                if (participant1 == null || participant2 == null) {
                    event.reply("❌ Unable to retrieve match participants after rejection.").setEphemeral(true).queue();
                    return;
                }

                User user1 = event.getJDA().retrieveUserById(participant1.misc).complete();
                User user2 = event.getJDA().retrieveUserById(participant2.misc).complete();

                event.reply("✅ Match result has been rejected. The participants will be notified to report the result again.")
                        .queue();

                notifyParticipantsOfRejection(user1, user2, matchIdStr);
            } else {
                String errorBody = response.errorBody() != null ? response.errorBody().string() : "Unknown error";
                logger.error("❌ Failed to reject match ID {}: " + response.code() + " " + response.message() + "\n" + errorBody, matchIdStr);
                event.reply("❌ Failed to reject match result. Please try again later.")
                        .setEphemeral(true)
                        .queue();
            }
        } catch (NumberFormatException e) {
            logger.error("Invalid match ID format: {}", matchIdStr, e);
            event.reply("❌ Invalid match ID format.").setEphemeral(true).queue();
        } catch (IOException e) {
            logger.error("❌ Error rejecting match result for match ID {}", matchIdStr, e);
            event.reply("❌ An error occurred while rejecting the match result. Please try again later.")
                    .setEphemeral(true)
                    .queue();
        }
    }

    private ChallongeDataClasses.MatchData getMatchById(String matchId) throws IOException {
        Map<String, String> options = new HashMap<>();
        Call<ChallongeDataClasses.MatchWrapper> call = challongeService.getMatch(currentTournament.id, Long.parseLong(matchId), options);
        Response<ChallongeDataClasses.MatchWrapper> response = call.execute();

        if (response.isSuccessful() && response.body() != null) {
            return response.body().match;
        }
        return null;
    }


    private void notifyParticipantsOfRejection(User user1, User user2, String matchId) {
        String message = String.format("Your reported match result for match ID %s has been rejected. Please report the correct result using the /report command.", matchId);
        user1.openPrivateChannel().queue(channel -> channel.sendMessage(message).queue());
        user2.openPrivateChannel().queue(channel -> channel.sendMessage(message).queue());
    }
}