package com.fimtale.community;

import android.content.Context;
import android.content.Intent;

public final class UserContentActivity extends CommunityListActivity {
    public static final String[] TABS = {"activity", "work", "post", "comment", "channel", "following", "follower"};
    static final String EXTRA_USERNAME = "username", EXTRA_TAB = "tab";
    public static Intent intent(Context context, String username, String tab) {
        return new Intent(context, UserContentActivity.class).putExtra(EXTRA_USERNAME, username).putExtra(EXTRA_TAB, tab);
    }
}
