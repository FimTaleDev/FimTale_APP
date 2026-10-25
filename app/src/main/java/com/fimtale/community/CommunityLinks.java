package com.fimtale.community;

import android.content.Context;
import android.net.Uri;
import com.fimtale.network.SiteUrls;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class CommunityLinks {
    private static final Pattern COMMENT = Pattern.compile("comment-([0-9]+)");
    private CommunityLinks() { }
    public static boolean openComment(Context context, String path) {
        if (path == null) return false;
        Uri uri = Uri.parse(path);
        if (uri.getScheme() != null && !SiteUrls.isSite(path)) return false;
        if (uri.getAuthority() != null && uri.getScheme() == null) return false;
        if (uri.getPath() == null || !uri.getPath().matches("/work/[0-9]+(?:/chapter/[0-9]+)?")) return false;
        Matcher match = COMMENT.matcher(uri.getFragment() == null ? "" : uri.getFragment());
        if (!match.matches()) return false;
        try {
            int id = Integer.parseInt(match.group(1));
            if (id <= 0) return false;
            context.startActivity(CommentDetailActivity.intent(context, id)); return true;
        } catch (NumberFormatException ignored) { return false; }
    }
}
