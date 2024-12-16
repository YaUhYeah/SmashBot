package org.discord.handlers;


import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.entities.channel.unions.MessageChannelUnion;
import org.discord.ChallongeDataClasses;
import org.discord.obj.Match;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentSkipListSet;
import java.util.concurrent.ScheduledFuture;
import java.util.stream.Collectors;

public class TournamentData {
    private final Long tournamentId;
    private final String tournamentType;
    private final MessageChannelUnion channel;
    private final Map<Long, Long> matchToTournamentMap; // Maps matchId to tournamentId
    private final ConcurrentHashMap<String, ChallongeDataClasses.Participant> participants;
    private final Set<Long> notifiedMatches; // Tracks matches that have been notified
    private boolean roundNotified;
    private ScheduledFuture<?> scheduler; // Mutable to allow assignment upon starting
    private boolean started; // Flag to indicate if the tournament has been started
    private ChallongeDataClasses.Tournament tournament;

    public TournamentData(Long tournamentId, String tournamentType, MessageChannelUnion channel, ChallongeDataClasses.Tournament tournament) {
        this.tournamentId = tournamentId;
        this.tournamentType = tournamentType;
        this.channel = channel;
        this.matchToTournamentMap = new ConcurrentHashMap<>();
        this.participants = new ConcurrentHashMap<>();
        this.started = false;
        this.notifiedMatches = ConcurrentHashMap.newKeySet();
        this.tournament = tournament;
        this.roundNotified = false;
    }


    public ChallongeDataClasses.Tournament getTournament() {
        return tournament;
    }

    public void setTournament(ChallongeDataClasses.Tournament tournament) {
        this.tournament = tournament;
    }

    public Long getTournamentId() {
        return tournamentId;
    }

    public String getTournamentType() {
        return tournamentType;
    }

    public MessageChannelUnion getChannel() {
        return channel;
    }

    public ScheduledFuture<?> getScheduler() {
        return scheduler;
    }

    public void setScheduler(ScheduledFuture<?> scheduler) {
        this.scheduler = scheduler;
    }

    public Map<Long, Long> getMatchToTournamentMap() {
        return matchToTournamentMap;
    }

    public ConcurrentHashMap<String, ChallongeDataClasses.Participant> getParticipants() {
        return participants;
    }

    public boolean isStarted() {
        return started;
    }

    public void setStarted(boolean started) {
        this.started = started;
    }

    public boolean isRoundNotified() {
        return roundNotified;
    }

    public void setRoundNotified(boolean roundNotified) {
        this.roundNotified = roundNotified;
    }

    public Set<Long> getNotifiedMatches() {
        return notifiedMatches;
    }
}
