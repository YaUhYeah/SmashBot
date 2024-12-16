package org.discord.utils;

import org.discord.ChallongeDataClasses;
import retrofit2.Call;
import retrofit2.http.*;
import java.util.List;
import java.util.Map;

public interface ChallongeService {

    @POST("tournaments.json")
    Call<ChallongeDataClasses.TournamentWrapper> createTournament(@Body Map<String, Object> params);
    @POST("tournaments/{tournament_id}/finalize.json")
    Call<ChallongeDataClasses.TournamentWrapper> finalizeTournament(@Path("tournament_id") Long tournamentId, @QueryMap Map<String, String> options);

    @GET("tournaments/{tournament_id}.json")
    Call<ChallongeDataClasses.TournamentWrapper> getTournament(@Path("tournament_id") Long tournamentId, @QueryMap Map<String, String> options);

    @POST("tournaments/{tournament_id}/start.json")
    Call<ChallongeDataClasses.TournamentWrapper> startTournament(@Path("tournament_id") Long tournamentId, @QueryMap Map<String, String> options);

    @POST("tournaments/{tournament_id}/participants.json")
    Call<ChallongeDataClasses.ParticipantWrapper> addParticipant(@Path("tournament_id") Long tournamentId, @QueryMap Map<String, String> options, @Body Map<String, Object> params);

    @GET("tournaments/{tournament_id}/participants.json")
    Call<List<ChallongeDataClasses.ParticipantWrapper>> getParticipants(@Path("tournament_id") Long tournamentId, @QueryMap Map<String, String> options);

    @GET("tournaments/{tournament_id}/participants/{participant_id}.json")
    Call<ChallongeDataClasses.ParticipantWrapper> getParticipant(@Path("tournament_id") Long tournamentId, @Path("participant_id") Long participantId, @QueryMap Map<String, String> options);

    @GET("tournaments/{tournament_id}/matches.json")
    Call<List<ChallongeDataClasses.MatchWrapper>> getMatches(@Path("tournament_id") Long tournamentId, @QueryMap Map<String, String> options);

    @GET("tournaments/{tournament_id}/matches/{match_id}.json")
    Call<ChallongeDataClasses.MatchWrapper> getMatch(@Path("tournament_id") Long tournamentId, @Path("match_id") Long matchId, @QueryMap Map<String, String> options);

    @PUT("tournaments/{tournament_id}/matches/{match_id}.json")
    Call<ChallongeDataClasses.MatchWrapper> updateMatch(@Path("tournament_id") Long tournamentId, @Path("match_id") Long matchId, @QueryMap Map<String, String> options, @Body Map<String, Object> params);

    @POST("tournaments/{tournament_id}/randomize.json")
    Call<ChallongeDataClasses.TournamentWrapper> randomizeTournamentSeeding(@Path("tournament_id") Long tournamentId, @QueryMap Map<String, String> options);
}