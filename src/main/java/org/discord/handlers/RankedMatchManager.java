package org.discord.handlers;

import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.events.interaction.ModalInteractionEvent;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;
import net.dv8tion.jda.api.interactions.components.ActionRow;
import net.dv8tion.jda.api.interactions.components.buttons.Button;
import net.dv8tion.jda.api.interactions.components.text.TextInput;
import net.dv8tion.jda.api.interactions.components.text.TextInputStyle;
import net.dv8tion.jda.api.interactions.modals.Modal;
import org.discord.obj.Match;
import org.slf4j.LoggerFactory;

import java.awt.*;
import java.util.ArrayList;
import java.util.UUID;
import java.util.concurrent.*;

public class RankedMatchManager {private final ConcurrentMap<String, Match> activeRankedMatchesById = new ConcurrentHashMap<>();

    static {
        LoggerFactory.getLogger(RankedMatchManager.class);
    }

    private final EloManager eloManager;
    private final ConcurrentMap<String, Match> pendingRankedMatches = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, Match> activeRankedMatches = new ConcurrentHashMap<>();
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);

    public RankedMatchManager(EloManager eloManager) {
        this.eloManager = eloManager;
    }

    public void handleSeekCommand(SlashCommandInteractionEvent event) {
        User player = event.getUser();
        if (isPlayerInActiveMatch(player)) {
            event.reply("You already have an active ranked match. Please finish it before seeking a new one.").setEphemeral(true).queue();
            return;
        }

        if (hasPlayerPendingMatch(player)) {
            event.reply("You already have a pending match request. Please wait for it to be accepted or rejected.").setEphemeral(true).queue();
            return;
        }

        int playerElo = eloManager.getElo(player);
        String matchId = UUID.randomUUID().toString();
        Match match = new Match(matchId, player, null, Match.MatchType.RANKED);

        if (pendingRankedMatches.putIfAbsent(matchId, match) != null) {
            event.reply("An error occurred while creating the match. Please try again.").setEphemeral(true).queue();
            return;
        }

        scheduleMatchExpiration(matchId);
        sendMatchRequestEmbed(event, player, playerElo, matchId);
    }

    public void handleAcceptMatch(ButtonInteractionEvent event) {
        String[] parts = event.getComponentId().split("_");
        if (parts.length != 4 || !parts[0].equals("accept") || !parts[1].equals("ranked") || !parts[2].equals("match")) {
            event.reply("Invalid button ID.").setEphemeral(true).queue();
            return;
        }

        String matchId = parts[3];
        Match match = pendingRankedMatches.get(matchId);
        if (match == null) {
            event.reply("This match is no longer available.").setEphemeral(true).queue();
            return;
        }

        User opponent = event.getUser();
        if (opponent.getId().equals(match.getRequester().getId())) {
            event.reply("You cannot accept your own match request.").setEphemeral(true).queue();
            return;
        }

        if (isPlayerInActiveMatch(opponent)) {
            event.reply("You already have an active ranked match. Please finish it before accepting a new one.").setEphemeral(true).queue();
            return;
        }

        match.setOpponent(opponent);
        if (match.getExpirationTask() != null && !match.getExpirationTask().isDone()) {
            match.getExpirationTask().cancel(false);
        }
        activeRankedMatches.put(match.getRequester().getId(), match);
        activeRankedMatches.put(match.getOpponent().getId(), match);
        activeRankedMatchesById.put(match.getMatchId(), match);

        pendingRankedMatches.remove(matchId);

        String message = String.format("Ranked match between %s and %s has been accepted! Use /win to report game wins, check /rules or ask staff if you need help!",
                match.getRequester().getAsMention(), opponent.getAsMention());
        event.reply(message).queue();
    }

    public void handleWinCommand(SlashCommandInteractionEvent event) {
        User player = event.getUser();
        Match match = activeRankedMatches.get(player.getId());

        if (match == null) {
            event.reply("You don't have an active ranked match.").setEphemeral(true).queue();
            return;
        }

        if (player.equals(match.getRequester())) {
            match.setRequesterWins(match.getRequesterWins() + 1);
        } else if (player.equals(match.getOpponent())) {
            match.setOpponentWins(match.getOpponentWins() + 1);
        } else {
            event.reply("You are not part of this match.").setEphemeral(true).queue();
            return;
        }

        event.reply("Score updated: " + match.getRequesterWins() + " - " + match.getOpponentWins()).queue();

        if (match.getRequesterWins() == 3 || match.getOpponentWins() == 3) {
            finishMatch(match);
            sendMatchConfirmation(event, match);
        }
    }

    public void handleUndoCommand(SlashCommandInteractionEvent event) {
        User player = event.getUser();
        Match match = activeRankedMatches.get(player.getId());

        if (match == null) {
            event.reply("You don't have an active ranked match.").setEphemeral(true).queue();
            return;
        }

        if (player.equals(match.getRequester()) && match.getRequesterWins() > 0) {
            match.setRequesterWins(match.getRequesterWins() - 1);
        } else if (player.equals(match.getOpponent()) && match.getOpponentWins() > 0) {
            match.setOpponentWins(match.getOpponentWins() - 1);
        } else {
            event.reply("No wins to undo.").setEphemeral(true).queue();
            return;
        }

        event.reply("Win undone. Current score: " + match.getRequesterWins() + " - " + match.getOpponentWins()).queue();
    }

    public void handleSetScoreCommand(SlashCommandInteractionEvent event) {
        User player = event.getUser();
        Match match = activeRankedMatches.get(player.getId());

        if (match == null) {
            event.reply("You don't have an active ranked match.").setEphemeral(true).queue();
            return;
        }

        int yourWins = event.getOption("yourwins").getAsInt();
        int opponentWins = event.getOption("opponentwins").getAsInt();

        if (!isValidScore(yourWins, opponentWins)) {
            event.reply("Invalid score. Wins must be between 0 and 3, and one player must have 3 wins to conclude the match.").setEphemeral(true).queue();
            return;
        }

        if (player.equals(match.getRequester())) {
            match.setRequesterWins(yourWins);
            match.setOpponentWins(opponentWins);
        } else if (player.equals(match.getOpponent())) {
            match.setRequesterWins(opponentWins);
            match.setOpponentWins(yourWins);
        } else {
            event.reply("You are not part of this match.").setEphemeral(true).queue();
            return;
        }

        event.reply("Score set: " + match.getRequesterWins() + " - " + match.getOpponentWins()).queue();

        if (match.getRequesterWins() == 3 || match.getOpponentWins() == 3) {
            finishMatch(match);
            sendMatchConfirmation(event, match);
        }
    }

    private boolean isValidScore(int score1, int score2) {
        return (score1 + score2 >= 3 && score1 + score2 <= 5) &&
                (score1 == 3 || score2 == 3);
    }

    private void finishMatch(Match match) {
        if (match.getRequesterWins() > match.getOpponentWins()) {
            match.setWinner(match.getRequester());
            match.setLoser(match.getOpponent());
        } else {
            match.setWinner(match.getOpponent());
            match.setLoser(match.getRequester());
        }
    }

    private void sendMatchConfirmation(SlashCommandInteractionEvent event, Match match) {
        EmbedBuilder embed = new EmbedBuilder();
        embed.setTitle("Match Completed");
        embed.setDescription("Final score: " + match.getRequesterWins() + " - " + match.getOpponentWins());
        embed.setColor(Color.GREEN);
        embed.addField("Winner", match.getWinner().getAsMention(), true);
        embed.addField("Loser", match.getLoser().getAsMention(), true);
        Button confirmButton = Button.success("confirm_match_" + match.getMatchId(), "Confirm");

        Button adjustButton = Button.secondary("adjust_match_" + match.getMatchId(), "Adjust Score");

        event.getChannel().sendMessageEmbeds(embed.build())
                .setActionRow(confirmButton, adjustButton)
                .queue();
    }

    public void handleAdjustMatch(ButtonInteractionEvent event, String matchId) {
        Match match = activeRankedMatchesById.get(matchId);
        if (match == null) {
            event.reply("No active match found with this ID.").setEphemeral(true).queue();
            return;
        }

        // Create a modal for score adjustment
        TextInput player1Score = TextInput.create("player1_score", "Player 1 Score", TextInputStyle.SHORT)
                .setPlaceholder("Enter score for " + match.getRequester().getName())
                .setMinLength(1)
                .setMaxLength(1)
                .setRequired(true)
                .build();

        TextInput player2Score = TextInput.create("player2_score", "Player 2 Score", TextInputStyle.SHORT)
                .setPlaceholder("Enter score for " + match.getOpponent().getName())
                .setMinLength(1)
                .setMaxLength(1)
                .setRequired(true)
                .build();
        ArrayList<ActionRow> test = new ArrayList<>();
        test.add(ActionRow.of(player1Score));
        test.add(ActionRow.of(player2Score));

        Modal modal = Modal.create("adjust_score_" + matchId, "Adjust Match Score")
                .addActionRow(player1Score)
                .addActionRow(player2Score)
                .build();

        event.replyModal(modal).queue();
    }

    // This method should be called when the modal is submitted
    public void handleAdjustMatchModalSubmit(ModalInteractionEvent event) {
        String matchId = event.getModalId().split("_")[2];
        Match match = activeRankedMatches.get(matchId);
        if (match == null) {
            event.reply("No active match found with this ID.").setEphemeral(true).queue();
            return;
        }

        int player1Score = Integer.parseInt(event.getValue("player1_score").getAsString());
        int player2Score = Integer.parseInt(event.getValue("player2_score").getAsString());

        if (!isValidScore(player1Score, player2Score)) {
            event.reply("Invalid scores. Please enter valid scores (0-3) where one player has 3 wins.").setEphemeral(true).queue();
            return;
        }

        match.setRequesterWins(player1Score);
        match.setOpponentWins(player2Score);

        event.reply("Match score updated: " + player1Score + " - " + player2Score).queue();

        if (player1Score == 3 || player2Score == 3) {
            finishMatch(match);
            sendMatchConfirmation((SlashCommandInteractionEvent) event.getChannel(), match);
        }
    }

    public void handleConfirmMatch(ButtonInteractionEvent event) {
        String matchId = event.getComponentId().split("_")[2]; // Extract matchId from componentId
        Match match = activeRankedMatchesById.get(matchId);

        if (match == null) {
            event.reply("This match is no longer active.").setEphemeral(true).queue();
            return;
        }

        // Update ELO ratings
        eloManager.updateElo(match.getWinner(), match.getLoser());

        // Remove from both maps
        activeRankedMatches.remove(match.getRequester().getId());
        activeRankedMatches.remove(match.getOpponent().getId());
        activeRankedMatchesById.remove(matchId);

        // Notify players
        String message = String.format("Match confirmed. %s's new ELO: %d, %s's new ELO: %d",
                match.getWinner().getName(), eloManager.getElo(match.getWinner()),
                match.getLoser().getName(), eloManager.getElo(match.getLoser()));

        event.reply(message).queue();
    }


    private boolean isPlayerInActiveMatch(User player) {
        return activeRankedMatches.containsKey(player.getId());
    }

    private boolean hasPlayerPendingMatch(User player) {
        return pendingRankedMatches.values().stream()
                .anyMatch(match -> match.getRequester().getId().equals(player.getId()));
    }

    private void scheduleMatchExpiration(String matchId) {
        ScheduledFuture<?> expirationTask = scheduler.schedule(() -> expireSeek(matchId), 10, TimeUnit.MINUTES);
        pendingRankedMatches.get(matchId).setExpirationTask(expirationTask);
    }

    private void sendMatchRequestEmbed(SlashCommandInteractionEvent event, User player, int playerElo, String matchId) {
        EmbedBuilder embed = new EmbedBuilder()
                .setTitle("Ranked Match Request")
                .setDescription(player.getName() + " is seeking a match! Click to accept.")
                .setColor(Color.BLUE)
                .addField("ELO", String.valueOf(playerElo), true)
                .addField("Match ID", matchId, false);

        Button acceptButton = Button.primary("accept_ranked_match_" + matchId, "Accept Match");

        event.replyEmbeds(embed.build())
                .addActionRow(acceptButton)
                .queue(
                        success -> {
                        },
                        error -> handleMatchCreationError(player, matchId)
                );
    }

    private void handleMatchCreationError(User player, String matchId) {
        pendingRankedMatches.remove(matchId);
        Match match = pendingRankedMatches.get(matchId);
        if (match != null && match.getExpirationTask() != null) {
            match.getExpirationTask().cancel(false);
        }
        player.openPrivateChannel().queue(channel ->
                channel.sendMessage("Failed to create match request. Please try again.").queue()
        );
    }

    private void expireSeek(String matchId) {
        Match match = pendingRankedMatches.remove(matchId);
        if (match != null && match.getOpponent() == null) {
            User requester = match.getRequester();
            requester.openPrivateChannel().queue(channel -> {
                channel.sendMessage("Your match request has expired after 10 minutes. You can seek a new match now.").queue();
            });
        }
    }
}