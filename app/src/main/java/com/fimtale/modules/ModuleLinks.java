package com.fimtale.modules;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import com.fimtale.AccountSessionsActivity;
import com.fimtale.account.AccountActivity;
import com.fimtale.account.ExamActivity;
import com.fimtale.channels.ChannelActivity;
import com.fimtale.channels.ChannelDirectoryActivity;
import com.fimtale.network.SiteUrls;
import com.fimtale.recycle.RecycleActivity;
import java.util.List;

/** Only configured-site links can enter a native module. Opening a link never submits a request. */
public final class ModuleLinks {
    private ModuleLinks() { }

    public static boolean open(Context context, String link) {
        Intent intent = intent(context, link);
        if (intent == null) return false;
        if (!(context instanceof Activity)) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        context.startActivity(intent); return true;
    }
    public static Intent intent(Context context, String link) {
        if (link == null) return null;
        try {
            Uri uri = Uri.parse(link);
            if (uri.getScheme() != null ? !SiteUrls.isSite(link) : !link.startsWith("/") || uri.getAuthority() != null) return null;
            String path = uri.getPath();
            if (path == null) return null;
            if (path.endsWith("/") && path.length() > 1) path = path.substring(0, path.length() - 1);
            if (path.equals("/user/exam")) return new Intent(context, ExamActivity.class);
            if (path.equals("/user/recycle") || path.equals("/admin/recycle"))
                return new Intent(context, RecycleActivity.class).putExtra(RecycleActivity.EXTRA_ALL, path.startsWith("/admin/"));
            if (path.equals("/user/settings/session")) return new Intent(context, AccountSessionsActivity.class);
            if (path.equals("/user/settings") || path.equals("/user/settings/basic") || path.equals("/user/settings/detail") || path.equals("/user/settings/decoration"))
                return new Intent(context, AccountActivity.class).putExtra(AccountActivity.EXTRA_TAB,
                        path.endsWith("/basic") ? 1 : path.endsWith("/decoration") ? 2 : 0);
            if (path.equals("/channel") || path.equals("/channels") || path.equals("/search/channel") || path.equals("/channel/create"))
                return new Intent(context, ChannelDirectoryActivity.class).putExtra(ChannelDirectoryActivity.EXTRA_CREATE, path.endsWith("/create"));
            List<String> parts = uri.getPathSegments();
            if (parts.size() < 2 || !parts.get(0).equals("channel")) return null;
            int id = positive(parts.get(1)); if (id <= 0) return null;
            Intent intent = ChannelActivity.intent(context, id); int tab = 0;
            if (parts.size() == 4 && parts.get(2).equals("ballot")) {
                int ballotId = positive(parts.get(3)); if (ballotId <= 0) return null;
                intent.putExtra(ChannelActivity.EXTRA_BALLOT_ID, ballotId); tab = 5;
            } else if (parts.size() == 3) {
                switch (parts.get(2)) {
                    case "overview": break;
                    case "works": tab = 1; break;
                    case "discussion": tab = 2; break;
                    case "subscribers": tab = 3; break;
                    case "manage": tab = 4; break;
                    default: return null;
                }
            } else if (parts.size() != 2) return null;
            intent.putExtra(ChannelActivity.EXTRA_TAB, tab);
            String folder = uri.getQueryParameter("folder");
            if ("0".equals(folder)) intent.putExtra(ChannelActivity.EXTRA_FOLDER_ID, 0);
            else if (positive(folder) > 0) intent.putExtra(ChannelActivity.EXTRA_FOLDER_ID, positive(folder));
            String fragment = uri.getFragment();
            if (fragment != null && fragment.startsWith("comment-")) {
                int commentId = positive(fragment.substring(8));
                if (commentId > 0) intent.putExtra(ChannelActivity.EXTRA_COMMENT_ID, commentId);
            }
            return intent;
        } catch (IllegalArgumentException failure) { return null; }
    }
    private static int positive(String value) {
        if (value == null || !value.matches("[0-9]+")) return 0;
        try { return Math.max(0, Integer.parseInt(value)); } catch (NumberFormatException failure) { return 0; }
    }
}
