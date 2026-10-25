package com.fimtale.model;
import com.google.gson.annotations.SerializedName;
import com.fimtale.network.SiteUrls;
import java.util.List;
import java.util.Map;
public class UserDetailResponse {
    @SerializedName("user_id") private int id;
    private String username, avatar, intro, photo;
    private int level;
    @SerializedName("last_seen") private String lastSeen;
    private Stats stats;
    public List<Badge> badges;
    public List<Medal> medals;
    @SerializedName("user_operations") public List<com.fimtale.community.Community.Follow> follows;
    public boolean followedBy(int viewerId) {
        if (follows != null) for (com.fimtale.community.Community.Follow follow : follows)
            if (follow != null && follow.follower_id == viewerId && follow.followee_id == id) return true;
        return false;
    }
    public int getId() { return id; }
    public String getUserName() { return username; }
    public String getAvatar() { return SiteUrls.media(avatar); }
    public String getUserIntro() { return intro; }
    public String getBackground() { return SiteUrls.media(photo); }
    public int getLevel() { return level; }
    public String getLastSeen() { return lastSeen; }
    public int getFollowing() { return stats == null ? 0 : stats.following; }
    public int getFollowers() { return stats == null ? 0 : stats.followers; }
    public int getTopics() {
        if (stats == null || stats.works == null) return 0;
        int total = 0; for (int count : stats.works.values()) total += count; return total;
    }
    public static class Stats {
        @SerializedName("following_count") int following;
        @SerializedName("follower_count") int followers;
        @SerializedName("works_by_type") Map<String, Integer> works;
    }
    public static class Badge { public String name; }
    public static class Medal { public String name, image; }
}
