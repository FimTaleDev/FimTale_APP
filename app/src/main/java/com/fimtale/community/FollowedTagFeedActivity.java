package com.fimtale.community;

/** A scoped feed never falls back to unfiltered search when no tags are followed. */
public final class FollowedTagFeedActivity extends CommunityListActivity {
    @Override protected boolean followedTagFeedMode() { return true; }
}
