package com.fimtale.report;

import com.fimtale.R;

import com.fimtale.utils.AppStrings;

import com.google.gson.annotations.SerializedName;

/** EntityType and CreateReportReq from ft-front/schema/openapi.json. */
public final class ReportRequest {
    public static final int WORK = 1, CHAPTER = 3, COMMENT = 4, CHANNEL = 5, CHANNEL_COMMENT = 6, USER = 7;
    @SerializedName("target_type") public final int targetType;
    @SerializedName("target_id") public final long targetId;
    public final String kind;
    public final String content;

    public ReportRequest(int targetType, long targetId, String content) {
        this(targetType, targetId, content, false);
    }
    public ReportRequest(int targetType, long targetId, String content, boolean recovery) {
        if (!isTarget(targetType) || recovery && targetType == USER || targetId <= 0)
            throw new IllegalArgumentException(AppStrings.get(R.string.report_target_invalid));
        if (trim(content).isEmpty()) throw new IllegalArgumentException(AppStrings.get(R.string.report_empty_content));
        this.targetType = targetType;
        this.kind = recovery ? "recovery_request" : "report";
        this.targetId = targetId;
        this.content = trim(content);
    }
    public static boolean isTarget(int type) {
        return type == WORK || type == CHAPTER || type == COMMENT || type == CHANNEL || type == CHANNEL_COMMENT || type == USER;
    }

    static String trim(String content) {
        if (content == null) return "";
        int start = 0, end = content.length();
        while (start < end && isSpace(content.charAt(start))) start++;
        while (end > start && isSpace(content.charAt(end - 1))) end--;
        return content.substring(start, end);
    }
    private static boolean isSpace(char c) { return Character.isWhitespace(c) || Character.isSpaceChar(c) || c == '\uFEFF'; }

    public static final class Result { public long id; }
}
