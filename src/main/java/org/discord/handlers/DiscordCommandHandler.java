package org.discord.handlers;

import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.Commands;
import net.dv8tion.jda.api.interactions.commands.build.SubcommandData;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.*;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

public class DiscordCommandHandler extends ListenerAdapter {
    private static final Logger logger = LoggerFactory.getLogger(DiscordCommandHandler.class);
    private static final int COOLDOWN_SECONDS = 5;
    private final JDA jda;
    private final TournamentManager tournamentManager;
    private final RankedMatchManager rankedMatchManager;
    private final EloManager eloManager;
    private final Map<String, Map<String, Long>> commandCooldowns = new HashMap<>();

    public DiscordCommandHandler(JDA jda, TournamentManager tournamentManager, RankedMatchManager rankedMatchManager, EloManager eloManager) {
        this.jda = jda;
        this.tournamentManager = tournamentManager;
        this.rankedMatchManager = rankedMatchManager;
        this.eloManager = eloManager;
        jda.addEventListener(this);
    }

    public void registerCommands() throws InterruptedException {
        Objects.requireNonNull(jda.awaitReady().getGuildById(1004330837472444449L)).updateCommands().addCommands(
                Commands.slash("seek", "Seek a ranked match."),
                Commands.slash("win", "Report a win in an ongoing match."),
                Commands.slash("undo", "Undo the last win reported."),
                Commands.slash("setscore", "Manually set the match score.")
                        .addOption(OptionType.INTEGER, "yourwins", "Your number of wins (0-3)", true)
                        .addOption(OptionType.INTEGER, "opponentwins", "Opponent's number of wins (0-3)", true),
                Commands.slash("report", "Report a match result.")
                        .addOption(OptionType.USER, "opponent", "Your opponent", true)
                        .addOption(OptionType.INTEGER, "yourwins", "Your number of wins (0-3)", true)
                        .addOption(OptionType.INTEGER, "opponentwins", "Opponent's number of wins (0-3)", true),
                Commands.slash("tournament", "Tournament management commands.")
                        .addSubcommands(
                                new SubcommandData("create", "Create a new tournament.")
                                        .addOption(OptionType.STRING, "name", "Name of the tournament", true)
                                        .addOption(OptionType.STRING, "type", "Type of tournament (single/double/roundrobin)", true),
                                new SubcommandData("start", "Start the current tournament."),
                                new SubcommandData("randomize", "Randomize the seeding before starting the tournament.")
                        ),
                Commands.slash("register", "Register for the current tournament."),
                Commands.slash("leaderboard", "Display the top 5 players by ELO."),
                Commands.slash("rules", "Display the Smash Ultimate rules."),
                Commands.slash("setelo", "Set a player's ELO (TO only)")
                        .addOption(OptionType.USER, "player", "The player whose ELO to set", true)
                        .addOption(OptionType.INTEGER, "elo", "The new ELO rating", true),
                Commands.slash("coinflip", "Flip a coin")
                        .addOption(OptionType.STRING, "choice", "Your guess: heads or tails", true)
        ).queue();
    }

    @Override
    public void onSlashCommandInteraction(SlashCommandInteractionEvent event) {
        String command = event.getName();
        if (isOnCooldown(event.getUser().getId(), command)) {
            event.reply("This command is on cooldown. Please wait before using it again.").setEphemeral(true).queue();
            return;
        }

        try {
            switch (command) {
                case "setelo":
                    handleSetEloCommand(event);
                    break;
                case "coinflip":
                    handleCoinFlipCommand(event);
                    break;
                case "seek":
                    rankedMatchManager.handleSeekCommand(event);
                    break;
                case "win":
                    rankedMatchManager.handleWinCommand(event);
                    break;
                case "undo":
                    rankedMatchManager.handleUndoCommand(event);
                    break;
                case "setscore":
                    rankedMatchManager.handleSetScoreCommand(event);
                    break;
                case "leaderboard":
                    eloManager.handleLeaderboardCommand(event);
                    break;
                case "tournament":
                    handleTournamentCommand(event);
                    break;
                case "register":
                    tournamentManager.handleRegisterCommand(event);
                    break;
                case "report":
                    tournamentManager.handleReportCommand(event);
                    break;
                case "rules":
                    handleRulesCommand(event);
                    break;
                default:
                    event.reply("Unknown command.").setEphemeral(true).queue();
                    break;
            }
        } catch (Exception e) {
            logger.error("Error handling command: " + command, e);
            event.reply("An error occurred while processing your command.").setEphemeral(true).queue();
        }
    }

    private void handleSetEloCommand(SlashCommandInteractionEvent event) {
        // Check if the user has the TO role
        if (!hasTORole(event.getMember())) {
            event.reply("You don't have permission to use this command.").setEphemeral(true).queue();
            return;
        }

        User player = event.getOption("player").getAsUser();
        int newElo = event.getOption("elo").getAsInt();

        if (newElo < 0 || newElo > 3000) { // Assuming a reasonable ELO range
            event.reply("Invalid ELO value. Please enter a value between 0 and 3000.").setEphemeral(true).queue();
            return;
        }

        int oldElo = eloManager.getElo(player);
        eloManager.setElo(player, newElo);

        event.reply("Updated ELO for " + player.getAsMention() + ": " + oldElo + " → " + newElo).queue();
    }

    private void handleCoinFlipCommand(SlashCommandInteractionEvent event) {
        String userChoice = event.getOption("choice").getAsString().toLowerCase();
        if (!userChoice.equals("heads") && !userChoice.equals("tails")) {
            event.reply("Invalid choice. Please choose 'heads' or 'tails'.").setEphemeral(true).queue();
            return;
        }

        String result = Math.random() < 0.5 ? "heads" : "tails";
        boolean userWon = userChoice.equals(result);

        EmbedBuilder embed = new EmbedBuilder()
                .setTitle("Coin Flip Result")
                .setDescription("The coin landed on: " + result)
                .setColor(userWon ? Color.GREEN : Color.RED)
                .addField("Your guess", userChoice, true)
                .addField("Result", userWon ? "You won!" : "You lost!", true);

        event.replyEmbeds(embed.build()).queue();
    }

    private boolean hasTORole(Member member) {
        return member.getRoles().stream()
                .anyMatch(role -> role.getName().equalsIgnoreCase("TO"));
    }


    @Override
    public void onButtonInteraction(ButtonInteractionEvent event) {
        String componentId = event.getComponentId();

        try {
            if (componentId.startsWith("accept_ranked_match_")) {
                rankedMatchManager.handleAcceptMatch(event);
            } else if (componentId.equals("tournament_register")) {
                tournamentManager.handleRegisterCommand(event);
            } else if (componentId.startsWith("confirm_match_")) {
                rankedMatchManager.handleConfirmMatch(event);
            } else if (componentId.startsWith("adjust_match_")) {
                String matchId = componentId.substring("adjust_match_".length());
                rankedMatchManager.handleAdjustMatch(event, matchId);
            } else if (componentId.startsWith("approve_result_")) {
                String matchId = componentId.substring("approve_result_".length());
                tournamentManager.handleApproveMatchResult(event, matchId);
            } else if (componentId.startsWith("reject_result_")) {
                String matchId = componentId.substring("reject_result_".length());
                tournamentManager.handleRejectMatchResult(event, matchId);
            } else if (componentId.startsWith("resolve_discrepancy_")) {
                String matchId = componentId.substring("resolve_discrepancy_".length());
                tournamentManager.handleResolveDiscrepancy(event, matchId);
            } else if (componentId.startsWith("resolve_")) {
                String[] parts = componentId.split("_");
                if (parts.length == 4) {
                    String matchId = parts[1];
                    int score1 = Integer.parseInt(parts[2]);
                    int score2 = Integer.parseInt(parts[3]);
                    tournamentManager.handleResolveDiscrepancyWithScore(event, matchId, score1, score2);
                } else {
                    throw new IllegalArgumentException("Invalid resolve button ID format");
                }
            } else {
                event.reply("Unknown button action.").setEphemeral(true).queue();
            }
        } catch (Exception e) {
            logger.error("Error handling button interaction: " + componentId, e);
            event.reply("An error occurred while processing your action.").setEphemeral(true).queue();
        }
    }

    private void handleTournamentCommand(SlashCommandInteractionEvent event) {
        String subcommand = event.getSubcommandName();
        if (subcommand == null) {
            event.reply("Invalid subcommand.").setEphemeral(true).queue();
            return;
        }

        switch (subcommand) {
            case "create":
                tournamentManager.handleCreateTournament(event);
                break;
            case "start":
                tournamentManager.handleStartTournament(event);
                break;
            case "randomize":
                tournamentManager.handleRandomizeSeeding(event);
                break;
            default:
                event.reply("Unknown subcommand.").setEphemeral(true).queue();
                break;
        }
    }


    private void handleRulesCommand(SlashCommandInteractionEvent event) {
        EmbedBuilder embed = new EmbedBuilder();
        embed.setTitle("Smash Ultimate Ruleset");
        embed.setColor(Color.BLUE);
        embed.addField("Stocks and Time", "3 stocks\n7 minutes", false);
        embed.addField("Rules", "Items: Off\nHazards: Off\nSpirits: Off", false);
        embed.addField("Legal Stages", """
                - Battlefield
                - Final Destination
                - Small Battlefield
                - Pokémon Stadium 2
                - Smashville
                - Hollow Bastion
                - Town and City
                - Lylat Cruise
                - Kalos Pokémon League
                """, false);
        embed.addField("Stage Selection", """
                No Counterpicks, No DSR

                **Stage Banning:**
                - Higher seed bans 3 stages.
                - The other player bans 4 stages.
                - Higher seed chooses between the remaining 2 stages.
                """, false);
        embed.addField("Subsequent Games", """
                - Winner of the previous game bans 3 stages.
                - Loser picks from the remaining stages.
                """, false);
        embed.addField("Character Selection", """
                - For Game 1, declare your character before stage banning.
                - Blind picks are allowed if both players agree.
                - After Game 1, the loser can ask the winner if they are switching characters **after** stage selection.
                - The winner must declare if they are switching and to whom.
                - The loser can then choose any character.
                """, false);
        event.replyEmbeds(embed.build()).queue();
    }

    private boolean isOnCooldown(String userId, String command) {
        Map<String, Long> userCooldowns = commandCooldowns.computeIfAbsent(userId, k -> new HashMap<>());
        long currentTime = System.currentTimeMillis();
        Long lastUsage = userCooldowns.get(command);

        if (lastUsage == null || currentTime - lastUsage > TimeUnit.SECONDS.toMillis(COOLDOWN_SECONDS)) {
            userCooldowns.put(command, currentTime);
            return false;
        }
        return true;
    }
}