package com.fimtale.model;

import com.google.gson.annotations.SerializedName;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Current API viewer state and the payloads used by work actions. */
public final class WorkInteractions {
    private WorkInteractions() {}
    public static class Viewer {
        public List<Operation> operations;
        public List<Favorite> favs;
        public boolean isLiked() {
            if (operations != null) for (Operation item : operations) if (item != null && item.operation == 1) return true;
            return false;
        }
        public int highPraiseCount() {
            int count = 0;
            if (operations != null) for (Operation item : operations) {
                if (item != null && item.operation == 3) count++;
            }
            return count;
        }
        public Set<Integer> folderIds() {
            Set<Integer> ids = new LinkedHashSet<>();
            if (favs != null) for (Favorite item : favs) if (item != null) ids.add(item.folderId == null ? 0 : item.folderId);
            return ids;
        }
    }
    public static class Operation { public int operation; }
    public static class Favorite { @SerializedName("folder_id") public Integer folderId; }
    public static class Folder {
        public int id;
        public String name;
        @SerializedName("count_works") public int count;
        public Folder(int id, String name) { this.id = id; this.name = name; }
    }
    public static class Vote {
        @SerializedName("work_id") public final int workId;
        public final int operation = 1;
        public Vote(int workId) { this.workId = workId; }
    }
    public static class HighPraise {
        @SerializedName("work_id") public final int workId;
        public final int count;
        public HighPraise(int workId, int count) { this.workId = workId; this.count = count; }
    }
    public static class FavoriteRequest {
        @SerializedName("work_id") public final int workId;
        @SerializedName("folder_id") public final Integer folderId;
        public FavoriteRequest(int workId, int folderId) {
            this.workId = workId;
            this.folderId = folderId == 0 ? null : folderId;
        }
    }
    public static class Change {
        public final int folderId;
        public final boolean add;
        Change(int folderId, boolean add) { this.folderId = folderId; this.add = add; }
    }
    public static List<Change> changes(Set<Integer> initial, Set<Integer> selected) {
        List<Change> changes = new ArrayList<>();
        for (int id : selected) if (!initial.contains(id)) changes.add(new Change(id, true));
        for (int id : initial) if (!selected.contains(id)) changes.add(new Change(id, false));
        return changes;
    }
}
