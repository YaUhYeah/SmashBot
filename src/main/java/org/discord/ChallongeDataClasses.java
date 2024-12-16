package org.discord;

import com.google.gson.annotations.SerializedName;

import java.util.Date;

public class ChallongeDataClasses {

    // Wrapper classes
    public static class TournamentWrapper {
        @SerializedName("tournament")
        public Tournament tournament;
    }

    public static class ParticipantWrapper {
        @SerializedName("participant")
        public Participant participant;
    }

    public static class MatchWrapper {
        @SerializedName("match")
        public MatchData match;
        public String state;
    }


    public static class Tournament {
        public Long id;
        public String name;
        public String url;
        @SerializedName("tournament_type")
        public String tournamentType;
        public String state;
        @SerializedName("created_at")
        public Date createdAt;
        @SerializedName("updated_at")
        public Date updatedAt;
        @SerializedName("started_at")
        public Date startedAt;
        @SerializedName("completed_at")
        public Date completedAt;
        public String description;
        @SerializedName("open_signup")
        public Boolean openSignup;
        @SerializedName("notify_users_when_matches_open")
        public Boolean notifyUsersWhenMatchesOpen;
        @SerializedName("notify_users_when_the_tournament_ends")
        public Boolean notifyUsersWhenTournamentEnds;
        @SerializedName("sequential_pairings")
        public Boolean sequentialPairings;
        @SerializedName("signup_cap")
        public Integer signupCap;
        @SerializedName("start_at")
        public Date scheduledStartAt;
        @SerializedName("participants_count")
        public Integer participantsCount;
        @SerializedName("progress_meter")
        public Integer progressMeter;
        @SerializedName("quick_advance")
        public Boolean quickAdvance;
        @SerializedName("hold_third_place_match")
        public Boolean holdThirdPlaceMatch;
        @SerializedName("pts_for_match_win")
        public Double pointsForMatchWin;
        @SerializedName("pts_for_match_tie")
        public Double pointsForMatchTie;
        @SerializedName("pts_for_game_win")
        public Double pointsForGameWin;
        @SerializedName("pts_for_game_tie")
        public Double pointsForGameTie;
        @SerializedName("pts_for_bye")
        public Double pointsForBye;
        @SerializedName("swiss_rounds")
        public Integer swissRounds;
        @SerializedName("private")
        public Boolean isPrivate;
        @SerializedName("ranked_by")
        public String rankedBy;
        @SerializedName("show_rounds")
        public Boolean showRounds;
        @SerializedName("hide_forum")
        public Boolean hideForum;
        @SerializedName("allow_participant_match_reporting")
        public Boolean allowParticipantMatchReporting;
        @SerializedName("teams")
        public Boolean hasTeams;
        @SerializedName("check_in_duration")
        public Integer checkInDuration;
        @SerializedName("grand_finals_modifier")
        public String grandFinalsModifier;
    }

    public static class Participant {
        public Long id;
        public String name;
        public String misc; // Used to store Discord user ID
        @SerializedName("final_rank")
        public Integer finalRank;
        @SerializedName("created_at")
        public Date createdAt;
        @SerializedName("updated_at")
        public Date updatedAt;
        @SerializedName("tournament_id")
        public Long tournamentId;
        @SerializedName("group_id")
        public Long groupId;
        public String seed;
        @SerializedName("checked_in")
        public Boolean checkedIn;
        @SerializedName("active")
        public Boolean active;
        @SerializedName("check_in_open")
        public Boolean checkInOpen;
        @SerializedName("ranked_member_id")
        public Long rankedMemberId;
        @SerializedName("username")
        public String username;
        @SerializedName("display_name")
        public String displayName;
        @SerializedName("attached_participatable_portrait_url")
        public String attachedParticipatablePortraitUrl;
        @SerializedName("can_check_in")
        public Boolean canCheckIn;
        @SerializedName("checked_in_at")
        public Date checkedInAt;
        @SerializedName("reactivatable")
        public Boolean reactivatable;
        @SerializedName("invitation_pending")
        public Boolean invitationPending;
        @SerializedName("invite_email")
        public String inviteEmail;
        @SerializedName("group_player_ids")
        public Long[] groupPlayerIds;
        @SerializedName("removable")
        public Boolean removable;
        @SerializedName("clinch")
        public String clinch;
        @SerializedName("match_plyr")
        public String matchPlyr;
    }

    public static class MatchData {
        public Long id;
        public String state;
        @SerializedName("player1_id")
        public Long player1Id;
        @SerializedName("player2_id")
        public Long player2Id;
        @SerializedName("winner_id")
        public Long winnerId;
        @SerializedName("loser_id")
        public Long loserId;
        @SerializedName("scores_csv")
        public String scoresCsv;
        @SerializedName("tournament_id")
        public Long tournamentId;
        @SerializedName("group_id")
        public Long groupId;
        @SerializedName("round")
        public Integer round;
        @SerializedName("identifier")
        public String identifier;
        @SerializedName("suggested_play_order")
        public Integer suggestedPlayOrder;
        @SerializedName("created_at")
        public Date createdAt;
        @SerializedName("updated_at")
        public Date updatedAt;
        @SerializedName("completed_at")
        public Date completedAt;
        @SerializedName("prerequisite_match_ids_csv")
        public String prerequisiteMatchIdsCsv;
        @SerializedName("underway_at")
        public Date underwayAt;
        @SerializedName("optional")
        public Boolean optional;
        @SerializedName("rushb_id")
        public Long rushbId;
        @SerializedName("attachment_count")
        public Integer attachmentCount;
        @SerializedName("scheduled_time")
        public Date scheduledTime;
        @SerializedName("location")
        public String location;
        @SerializedName("forfeited")
        public Boolean forfeited;
        @SerializedName("open_graph_image_file_name")
        public String openGraphImageFileName;
        @SerializedName("open_graph_image_content_type")
        public String openGraphImageContentType;
        @SerializedName("open_graph_image_file_size")
        public Integer openGraphImageFileSize;
    }
}