package org.discord;


import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.requests.GatewayIntent;
import net.dv8tion.jda.api.utils.cache.CacheFlag;
import net.dv8tion.jda.internal.utils.JDALogger;
import org.discord.handlers.*;
import org.discord.utils.ChallongeApiClient;
import org.discord.utils.ChallongeService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.security.auth.login.LoginException;
import java.util.Arrays;
import java.util.List;

public class SmashEloBot {
    private static final Logger logger = LoggerFactory.getLogger(SmashEloBot.class);
    public static JDA jda;
    private final DiscordCommandHandler discordCommandHandler;
    private final DatabaseManager databaseManager;
    List<String> organizerRoles = Arrays.asList("TO", "Tournament Organizer", "Admin", "Moderator");

    public SmashEloBot(String token, String challongeApiKey, String challongeUsername, String dbUrl) throws LoginException {
        jda = JDABuilder.createDefault(token)
                .disableCache(CacheFlag.EMOJI, CacheFlag.VOICE_STATE)
                .enableIntents(GatewayIntent.GUILD_MEMBERS)
                .build();

        this.databaseManager = new DatabaseManager(dbUrl);
        EloManager eloManager = new EloManager(databaseManager);
        ChallongeApiClient challongeApiClient = new ChallongeApiClient(challongeApiKey, challongeUsername);
        ChallongeService challongeService = challongeApiClient.getService();
        TournamentManager tournamentManager = new TournamentManager(challongeService, eloManager, "911034984444338186", organizerRoles);
        RankedMatchManager rankedMatchManager = new RankedMatchManager(eloManager);
        this.discordCommandHandler = new DiscordCommandHandler(jda, tournamentManager, rankedMatchManager, eloManager);

    }

    public static void main(String[] args) {
        JDALogger.setFallbackLoggerEnabled(false);
        String token = System.getenv("DISCORD_BOT_TOKEN");
        String challongeApiKey = System.getenv("CHALLONGE_API_KEY");
        String challongeUsername = System.getenv("CHALLONGE_USERNAME");
        String dbUrl = "jdbc:h2:./data/eloDB";

        if (token == null || token.isEmpty()) {
            logger.error("Bot token not found. Please set the DISCORD_BOT_TOKEN environment variable.");
            return;
        }

        if (challongeApiKey == null || challongeUsername == null) {
            logger.error("Challonge API credentials not found. Please set CHALLONGE_API_KEY and CHALLONGE_USERNAME environment variables.");
            return;
        }

        try {
            SmashEloBot bot = new SmashEloBot(token, challongeApiKey, challongeUsername, dbUrl);
            bot.initialize();
            logger.info("SmashEloBot initialized successfully.");
        } catch (LoginException | InterruptedException e) {
            logger.error("Failed to initialize SmashEloBot", e);
        }
    }

    public void initialize() throws InterruptedException {
        discordCommandHandler.registerCommands();

        databaseManager.initializeDatabase();
    }
}