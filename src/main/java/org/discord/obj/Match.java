package org.discord.obj;

import net.dv8tion.jda.api.entities.User;

import java.time.Instant;
import java.util.concurrent.ScheduledFuture;

public class Match {
    private final String matchId;
    private final User requester;
    private final Instant creationTime;
    private User opponent;
    private User winner;
    private User loser;
    private int requesterWins;
    private int opponentWins;
    private MatchType matchType;
    private MatchStatus status;
    private ScheduledFuture<?> expirationTask;

    public Match(String matchId, User requester, User opponent, MatchType matchType) {
        this.matchId = matchId;
        this.requester = requester;
        this.opponent = opponent;
        this.matchType = matchType;
        this.creationTime = Instant.now();
        this.status = MatchStatus.PENDING;
        this.requesterWins = 0;
        this.opponentWins = 0;
    }

    public String getMatchId() {
        return matchId;
    }

    public User getRequester() {
        return requester;
    }

    public User getOpponent() {
        return opponent;
    }

    public void setOpponent(User opponent) {
        this.opponent = opponent;
        this.status = MatchStatus.ACTIVE;
    }

    public User getWinner() {
        return winner;
    }

    public void setWinner(User winner) {
        this.winner = winner;
        this.status = MatchStatus.COMPLETED;
    }

    public User getOpponent(User user) {
        return user.equals(requester) ? opponent : requester;
    }

    public User getLoser() {
        return loser;
    }

    public void setLoser(User loser) {
        this.loser = loser;
    }

    public int getRequesterWins() {
        return requesterWins;
    }

    public void setRequesterWins(int requesterWins) {
        this.requesterWins = requesterWins;
    }

    public int getOpponentWins() {
        return opponentWins;
    }

    public void setOpponentWins(int opponentWins) {
        this.opponentWins = opponentWins;
    }

    public Instant getCreationTime() {
        return creationTime;
    }

    public MatchType getMatchType() {
        return matchType;
    }

    public void setMatchType(MatchType matchType) {
        this.matchType = matchType;
    }

    public MatchStatus getStatus() {
        return status;
    }

    public void setStatus(MatchStatus status) {
        this.status = status;
    }

    public ScheduledFuture<?> getExpirationTask() {
        return expirationTask;
    }

    public void setExpirationTask(ScheduledFuture<?> expirationTask) {
        this.expirationTask = expirationTask;
    }

    public boolean isPlayerInMatch(User player) {
        return player.getId().equals(requester.getId()) || (opponent != null && player.getId().equals(opponent.getId()));
    }

    public User getOtherPlayer(User player) {
        if (player.getId().equals(requester.getId())) {
            return opponent;
        } else if (opponent != null && player.getId().equals(opponent.getId())) {
            return requester;
        }
        return null;
    }

    public void incrementWins(User player) {
        if (player.getId().equals(requester.getId())) {
            requesterWins++;
        } else if (opponent != null && player.getId().equals(opponent.getId())) {
            opponentWins++;
        }
        checkMatchCompletion();
    }

    public void decrementWins(User player) {
        if (player.getId().equals(requester.getId()) && requesterWins > 0) {
            requesterWins--;
        } else if (opponent != null && player.getId().equals(opponent.getId()) && opponentWins > 0) {
            opponentWins--;
        }
    }

    private void checkMatchCompletion() {
        if (requesterWins == 3 || opponentWins == 3) {
            status = MatchStatus.COMPLETED;
            winner = (requesterWins > opponentWins) ? requester : opponent;
            loser = (requesterWins > opponentWins) ? opponent : requester;
        }
    }

    public String getScore() {
        return requesterWins + " - " + opponentWins;
    }

    @Override
    public String toString() {
        return "Match{" +
                "matchId='" + matchId + '\'' +
                ", requester=" + requester.getName() +
                ", opponent=" + (opponent != null ? opponent.getName() : "N/A") +
                ", score=" + getScore() +
                ", status=" + status +
                ", type=" + matchType +
                '}';
    }

    public enum MatchType {
        RANKED,
        TOURNAMENT
    }

    public enum MatchStatus {
        PENDING,
        ACTIVE,
        COMPLETED,
        EXPIRED
    }
}