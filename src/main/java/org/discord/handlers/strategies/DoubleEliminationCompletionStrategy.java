package org.discord.handlers.strategies;

import org.discord.ChallongeDataClasses;

public class DoubleEliminationCompletionStrategy implements TournamentCompletionStrategy {
    @Override
    public boolean isComplete(ChallongeDataClasses.Tournament tournament) {
        return "complete".equalsIgnoreCase(tournament.state) ||
                (tournament.participantsCount != null && tournament.participantsCount > 1 &&
                        tournament.progressMeter == 100);
    }

}
