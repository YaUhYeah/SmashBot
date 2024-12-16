package org.discord.handlers.strategies;

import org.discord.ChallongeDataClasses;

import java.io.IOException;

public interface TournamentCompletionStrategy {
    boolean isComplete(ChallongeDataClasses.Tournament tournament) throws IOException;
}
