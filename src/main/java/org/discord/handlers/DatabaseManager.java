package org.discord.handlers;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

public class DatabaseManager {
    private static final Logger logger = LoggerFactory.getLogger(DatabaseManager.class);
    private final String dbUrl;

    public DatabaseManager(String dbUrl) {
        this.dbUrl = dbUrl;
    }

    public Connection getConnection() throws SQLException {
        return DriverManager.getConnection(dbUrl, "sa", "");
    }

    public void initializeDatabase() {
        createEloTable();
        createTournamentTables();
    }

    private void createEloTable() {
        String sql = "CREATE TABLE IF NOT EXISTS elo_ratings ("
                + "player_id VARCHAR(255) PRIMARY KEY,"
                + "elo INT NOT NULL"
                + ")";

        try (Connection conn = getConnection();
             Statement stmt = conn.createStatement()) {
            stmt.execute(sql);
            logger.info("ELO ratings table created or already exists.");
        } catch (SQLException e) {
            logger.error("Error creating ELO ratings table", e);
        }
    }

    private void createTournamentTables() {
        String createTournamentsTable = "CREATE TABLE IF NOT EXISTS tournaments ("
                + "id VARCHAR(255) PRIMARY KEY,"
                + "name VARCHAR(255) NOT NULL,"
                + "status VARCHAR(50) NOT NULL,"
                + "created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP"
                + ")";

        String createParticipantsTable = "CREATE TABLE IF NOT EXISTS tournament_participants ("
                + "id INT AUTO_INCREMENT PRIMARY KEY,"
                + "tournament_id VARCHAR(255) NOT NULL,"
                + "player_id VARCHAR(255) NOT NULL,"
                + "FOREIGN KEY (tournament_id) REFERENCES tournaments(id),"
                + "FOREIGN KEY (player_id) REFERENCES elo_ratings(player_id)"
                + ")";

        String createMatchesTable = "CREATE TABLE IF NOT EXISTS tournament_matches ("
                + "id VARCHAR(255) PRIMARY KEY,"
                + "tournament_id VARCHAR(255) NOT NULL,"
                + "player1_id VARCHAR(255) NOT NULL,"
                + "player2_id VARCHAR(255) NOT NULL,"
                + "winner_id VARCHAR(255),"
                + "score VARCHAR(10),"
                + "status VARCHAR(50) NOT NULL,"
                + "FOREIGN KEY (tournament_id) REFERENCES tournaments(id),"
                + "FOREIGN KEY (player1_id) REFERENCES elo_ratings(player_id),"
                + "FOREIGN KEY (player2_id) REFERENCES elo_ratings(player_id),"
                + "FOREIGN KEY (winner_id) REFERENCES elo_ratings(player_id)"
                + ")";

        try (Connection conn = getConnection();
             Statement stmt = conn.createStatement()) {
            stmt.execute(createTournamentsTable);
            stmt.execute(createParticipantsTable);
            stmt.execute(createMatchesTable);
            logger.info("Tournament tables created or already exist.");
        } catch (SQLException e) {
            logger.error("Error creating tournament tables", e);
        }
    }

    public void insertEloRating(String playerId, int initialElo) {
        String sql = "MERGE INTO elo_ratings KEY (player_id) VALUES (?, ?)";

        try (Connection conn = getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, playerId);
            pstmt.setInt(2, initialElo);
            pstmt.executeUpdate();
        } catch (SQLException e) {
            logger.error("Error inserting ELO rating for player " + playerId, e);
        }
    }

    public void updateEloRating(String playerId, int newElo) {
        String sql = "UPDATE elo_ratings SET elo = ? WHERE player_id = ?";

        try (Connection conn = getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setInt(1, newElo);
            pstmt.setString(2, playerId);
            pstmt.executeUpdate();
        } catch (SQLException e) {
            logger.error("Error updating ELO rating for player " + playerId, e);
        }
    }

    public int getEloRating(String playerId) {
        String sql = "SELECT elo FROM elo_ratings WHERE player_id = ?";

        try (Connection conn = getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, playerId);
            try (ResultSet rs = pstmt.executeQuery()) {
                if (rs.next()) {
                    return rs.getInt("elo");
                }
            }
        } catch (SQLException e) {
            logger.error("Error getting ELO rating for player " + playerId, e);
        }
        return -1; // Return -1 if no rating found or error occurred
    }

    public List<PlayerElo> getTopPlayers(int limit) {
        List<PlayerElo> topPlayers = new ArrayList<>();
        String sql = "SELECT player_id, elo FROM elo_ratings ORDER BY elo DESC LIMIT ?";

        try (Connection conn = getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setInt(1, limit);
            try (ResultSet rs = pstmt.executeQuery()) {
                while (rs.next()) {
                    String playerId = rs.getString("player_id");
                    int elo = rs.getInt("elo");
                    topPlayers.add(new PlayerElo(playerId, elo));
                }
            }
        } catch (SQLException e) {
            logger.error("Error retrieving top players", e);
        }
        return topPlayers;
    }

    public void createTournament(String tournamentId, String name, String status) {
        String sql = "INSERT INTO tournaments (id, name, status) VALUES (?, ?, ?)";

        try (Connection conn = getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, tournamentId);
            pstmt.setString(2, name);
            pstmt.setString(3, status);
            pstmt.executeUpdate();
        } catch (SQLException e) {
            logger.error("Error creating tournament: " + tournamentId, e);
        }
    }

    public void addTournamentParticipant(String tournamentId, String playerId) {
        String sql = "INSERT INTO tournament_participants (tournament_id, player_id) VALUES (?, ?)";

        try (Connection conn = getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, tournamentId);
            pstmt.setString(2, playerId);
            pstmt.executeUpdate();
        } catch (SQLException e) {
            logger.error("Error adding participant to tournament: " + tournamentId + ", player: " + playerId, e);
        }
    }

    public void createTournamentMatch(String matchId, String tournamentId, String player1Id, String player2Id, String status) {
        String sql = "INSERT INTO tournament_matches (id, tournament_id, player1_id, player2_id, status) VALUES (?, ?, ?, ?, ?)";

        try (Connection conn = getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, matchId);
            pstmt.setString(2, tournamentId);
            pstmt.setString(3, player1Id);
            pstmt.setString(4, player2Id);
            pstmt.setString(5, status);
            pstmt.executeUpdate();
        } catch (SQLException e) {
            logger.error("Error creating tournament match: " + matchId, e);
        }
    }

    public void updateTournamentMatchResult(String matchId, String winnerId, String score) {
        String sql = "UPDATE tournament_matches SET winner_id = ?, score = ?, status = 'COMPLETED' WHERE id = ?";

        try (Connection conn = getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, winnerId);
            pstmt.setString(2, score);
            pstmt.setString(3, matchId);
            pstmt.executeUpdate();
        } catch (SQLException e) {
            logger.error("Error updating tournament match result: " + matchId, e);
        }
    }

    public void updateTournamentStatus(String tournamentId, String status) {
        String sql = "UPDATE tournaments SET status = ? WHERE id = ?";

        try (Connection conn = getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, status);
            pstmt.setString(2, tournamentId);
            pstmt.executeUpdate();
        } catch (SQLException e) {
            logger.error("Error updating tournament status: " + tournamentId, e);
        }
    }

    public static class PlayerElo {
        public final String playerId;
        public final int elo;

        public PlayerElo(String playerId, int elo) {
            this.playerId = playerId;
            this.elo = elo;
        }
    }
}