package org.discord.handlers;

import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.Color;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

public class EloManager {
    private static final Logger logger = LoggerFactory.getLogger(EloManager.class);
    private static final int INITIAL_ELO = 1000;
    private static final int K_FACTOR = 32;
    private static final int MIN_ELO = 100;

    private final DatabaseManager databaseManager;

    public EloManager(DatabaseManager databaseManager) {
        this.databaseManager = databaseManager;
    }

    public int getElo(User player) {
        try (Connection conn = databaseManager.getConnection()) {
            String sql = "SELECT elo FROM elo_ratings WHERE player_id = ?";
            PreparedStatement stmt = conn.prepareStatement(sql);
            stmt.setString(1, player.getId());
            ResultSet rs = stmt.executeQuery();

            if (rs.next()) {
                return rs.getInt("elo");
            } else {
                // Player not found, create a new entry with initial ELO
                createNewPlayerEntry(player);
                return INITIAL_ELO;
            }
        } catch (SQLException e) {
            logger.error("Error retrieving ELO for player " + player.getId(), e);
            return INITIAL_ELO;
        }
    }

    private void createNewPlayerEntry(User player) throws SQLException {
        try (Connection conn = databaseManager.getConnection()) {
            String sql = "INSERT INTO elo_ratings (player_id, elo) VALUES (?, ?)";
            PreparedStatement stmt = conn.prepareStatement(sql);
            stmt.setString(1, player.getId());
            stmt.setInt(2, INITIAL_ELO);
            stmt.executeUpdate();
        }
    }

    public void setElo(User user, int newElo) {
        String selectSql = "SELECT elo FROM elo_ratings WHERE player_id = ?";
        String insertSql = "INSERT INTO elo_ratings (player_id, elo) VALUES (?, ?)";
        String updateSql = "UPDATE elo_ratings SET elo = ? WHERE player_id = ?";

        try (Connection conn = databaseManager.getConnection();
             PreparedStatement selectStmt = conn.prepareStatement(selectSql);
             PreparedStatement insertStmt = conn.prepareStatement(insertSql);
             PreparedStatement updateStmt = conn.prepareStatement(updateSql)) {

            // Check if the player already exists
            selectStmt.setString(1, user.getId());
            ResultSet rs = selectStmt.executeQuery();

            if (rs.next()) {
                // Player exists, update their ELO
                updateStmt.setInt(1, newElo);
                updateStmt.setString(2, user.getId());
                updateStmt.executeUpdate();
            } else {
                // Player doesn't exist, insert new record
                insertStmt.setString(1, user.getId());
                insertStmt.setInt(2, newElo);
                insertStmt.executeUpdate();
            }

            logger.info("Updated ELO for user {} to {}", user.getId(), newElo);

        } catch (SQLException e) {
            logger.error("Error updating ELO for user " + user.getId(), e);
        }
    }

    public void updateElo(User winner, User loser) {
        int winnerElo = getElo(winner);
        int loserElo = getElo(loser);

        double expectedScoreWinner = 1.0 / (1.0 + Math.pow(10, (loserElo - winnerElo) / 400.0));
        double expectedScoreLoser = 1.0 - expectedScoreWinner;

        int newWinnerElo = (int) Math.round(winnerElo + K_FACTOR * (1 - expectedScoreWinner));
        int newLoserElo = (int) Math.round(loserElo + K_FACTOR * (0 - expectedScoreLoser));

        // Ensure ELO doesn't go below the minimum
        newWinnerElo = Math.max(newWinnerElo, MIN_ELO);
        newLoserElo = Math.max(newLoserElo, MIN_ELO);

        updatePlayerElo(winner, newWinnerElo);
        updatePlayerElo(loser, newLoserElo);
    }

    private void updatePlayerElo(User player, int newElo) {
        try (Connection conn = databaseManager.getConnection()) {
            String sql = "UPDATE elo_ratings SET elo = ? WHERE player_id = ?";
            PreparedStatement stmt = conn.prepareStatement(sql);
            stmt.setInt(1, newElo);
            stmt.setString(2, player.getId());
            stmt.executeUpdate();
        } catch (SQLException e) {
            logger.error("Error updating ELO for player " + player.getId(), e);
        }
    }

    public void handleLeaderboardCommand(SlashCommandInteractionEvent event) {
        List<PlayerElo> topPlayers = getTopPlayers();

        EmbedBuilder embed = new EmbedBuilder();
        embed.setTitle("Top 5 Players by ELO");
        embed.setColor(Color.YELLOW);

        for (int i = 0; i < topPlayers.size(); i++) {
            PlayerElo playerElo = topPlayers.get(i);
            User user = event.getJDA().retrieveUserById(playerElo.playerId).complete();
            String playerName = user != null ? user.getName() : "Unknown Player";
            embed.addField(
                    (i + 1) + ". " + playerName,
                    "ELO: " + playerElo.elo,
                    false
            );
        }

        event.replyEmbeds(embed.build()).queue();
    }

    private List<PlayerElo> getTopPlayers() {
        List<PlayerElo> topPlayers = new ArrayList<>();
        try (Connection conn = databaseManager.getConnection()) {
            String sql = "SELECT player_id, elo FROM elo_ratings ORDER BY elo DESC LIMIT ?";
            PreparedStatement stmt = conn.prepareStatement(sql);
            stmt.setInt(1, 5);
            ResultSet rs = stmt.executeQuery();

            while (rs.next()) {
                String playerId = rs.getString("player_id");
                int elo = rs.getInt("elo");
                topPlayers.add(new PlayerElo(playerId, elo));
            }
        } catch (SQLException e) {
            logger.error("Error retrieving top players", e);
        }
        return topPlayers;
    }

    private static class PlayerElo {
        String playerId;
        int elo;

        PlayerElo(String playerId, int elo) {
            this.playerId = playerId;
            this.elo = elo;
        }
    }
}