package com.fimtale;

import android.os.Bundle;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.Lifecycle;
import com.fimtale.ui.ArticleFragment;
import com.fimtale.ui.HomeFragment;
import com.fimtale.ui.TimelineFragment;
import com.fimtale.ui.ProfileFragment;
import com.fimtale.utils.MdiIcons;
import com.fimtale.utils.UpdateChecker;
import com.fimtale.utils.UserPreferences;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;
import android.graphics.Rect;
import android.view.Gravity;
import android.view.View;
import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.bottomnavigation.BottomNavigationView;

import java.util.HashMap;
import java.util.Map;

public class MainActivity extends AppCompatActivity {

    private int currentItemId = 0;
    private HomeFragment homeFragment;
    private TimelineFragment timelineFragment;
    private ArticleFragment articleFragment;
    private ProfileFragment profileFragment;
    private Fragment currentFragment;
    private retrofit2.Call<Integer> unreadCall;
    private int unreadRequest;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        MaterialToolbar toolbar = findViewById(R.id.toolbar);
        if (toolbar.getOverflowIcon() != null) {
            toolbar.getOverflowIcon().setTint(getColor(R.color.app_bar_on_surface));
        }
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayShowTitleEnabled(false);
        }

        BottomNavigationView bottomNav = findViewById(R.id.bottom_navigation);
        // Material positions labels by their baseline at the bottom for icon-and-text items.
        // Center the label group itself for text-only navigation, independently of font size.
        for (int i = 0; i < bottomNav.getMenu().size(); i++) {
            View item = bottomNav.findViewById(bottomNav.getMenu().getItem(i).getItemId());
            View labels = item.findViewById(
                    com.google.android.material.R.id.navigation_bar_item_labels_group);
            FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) labels.getLayoutParams();
            params.gravity = Gravity.CENTER;
            labels.setLayoutParams(params);
        }

        final View rootView = findViewById(android.R.id.content);
        rootView.getViewTreeObserver().addOnGlobalLayoutListener(() -> {
            Rect r = new Rect();
            rootView.getWindowVisibleDisplayFrame(r);
            int screenHeight = rootView.getRootView().getHeight();
            int keypadHeight = screenHeight - r.bottom;

            if (keypadHeight > screenHeight * 0.15) {
                if (currentItemId == R.id.nav_article) {
                    bottomNav.setVisibility(View.GONE);
                }
            } else {
                bottomNav.setVisibility(View.VISIBLE);
            }
        });

        if (savedInstanceState != null) {
            currentItemId = savedInstanceState.getInt("currentItemId", R.id.nav_home);
            homeFragment = (HomeFragment) getSupportFragmentManager().findFragmentByTag("HOME");
            timelineFragment = (TimelineFragment) getSupportFragmentManager().findFragmentByTag("TIMELINE");
            articleFragment = (ArticleFragment) getSupportFragmentManager().findFragmentByTag("ARTICLE");
            profileFragment = (ProfileFragment) getSupportFragmentManager().findFragmentByTag("PROFILE");

            if (currentItemId == R.id.nav_home) currentFragment = homeFragment;
            else if (currentItemId == R.id.nav_timeline) currentFragment = timelineFragment;
            else if (currentItemId == R.id.nav_article) currentFragment = articleFragment;
            else if (currentItemId == R.id.nav_profile) currentFragment = profileFragment;
        }

        updateToolbar(currentItemId);

        bottomNav.setOnItemSelectedListener(item -> {
            int newItemId = item.getItemId();
            if (currentItemId == newItemId && currentFragment != null) {
                return true;
            }

            Fragment targetFragment = null;
            String tag = "";

            updateToolbar(newItemId);

            if (newItemId == R.id.nav_home) {
                if (homeFragment == null) homeFragment = new HomeFragment();
                targetFragment = homeFragment;
                tag = "HOME";
            } else if (newItemId == R.id.nav_timeline) {
                if (timelineFragment == null) timelineFragment = new TimelineFragment();
                targetFragment = timelineFragment;
                tag = "TIMELINE";
            } else if (newItemId == R.id.nav_article) {
                if (articleFragment == null) articleFragment = new ArticleFragment();
                targetFragment = articleFragment;
                tag = "ARTICLE";
            } else if (newItemId == R.id.nav_profile) {
                if (profileFragment == null) profileFragment = new ProfileFragment();
                targetFragment = profileFragment;
                tag = "PROFILE";
            }

            if (targetFragment != null) {
                androidx.fragment.app.FragmentTransaction transaction = getSupportFragmentManager().beginTransaction();

                Map<Integer, Integer> menuOrder = new HashMap<>();
                menuOrder.put(R.id.nav_home, 0);
                menuOrder.put(R.id.nav_timeline, 1);
                menuOrder.put(R.id.nav_article, 2);
                menuOrder.put(R.id.nav_profile, 3);

                Integer currentOrder = menuOrder.get(currentItemId);
                Integer newOrder = menuOrder.get(newItemId);

                if (currentOrder != null && newOrder != null) {
                    if (newOrder > currentOrder) {
                        transaction.setCustomAnimations(R.anim.slide_in_right, R.anim.slide_out_left);
                    } else {
                        transaction.setCustomAnimations(R.anim.slide_in_left, R.anim.slide_out_right);
                    }
                }

                if (!targetFragment.isAdded()) {
                    transaction.add(R.id.fragment_container, targetFragment, tag);
                    if (currentFragment != null) {
                        transaction.hide(currentFragment);
                        transaction.setMaxLifecycle(currentFragment, Lifecycle.State.STARTED);
                    }
                } else {
                    if (currentFragment != null) {
                        transaction.hide(currentFragment);
                        transaction.setMaxLifecycle(currentFragment, Lifecycle.State.STARTED);
                    }
                    transaction.show(targetFragment);
                    transaction.setMaxLifecycle(targetFragment, Lifecycle.State.RESUMED);
                }
                
                transaction.commit();
                
                currentFragment = targetFragment;
                currentItemId = newItemId;
                return true;
            }
            return false;
        });

        if (savedInstanceState == null) {
            bottomNav.setSelectedItemId(R.id.nav_home);
        } else {
            bottomNav.setSelectedItemId(currentItemId);
        }

        com.fimtale.crash.CrashFeedback.whenNoPendingReport(this, () -> UpdateChecker.checkUpdate(this, false));
    }

    private void updateToolbar(int itemId) {
        ImageView toolbarIcon = findViewById(R.id.toolbar_icon);
        TextView toolbarTitle = findViewById(R.id.toolbar_title);
        
        if (toolbarIcon == null || toolbarTitle == null) return;

        if (itemId == R.id.nav_home) {
            toolbarTitle.setText(R.string.app_name);
            toolbarIcon.setImageDrawable(MdiIcons.drawable(this, "home"));
        } else if (itemId == R.id.nav_timeline) {
            toolbarTitle.setText(R.string.nav_timeline);
            toolbarIcon.setImageDrawable(MdiIcons.drawable(this, "rss"));
        } else if (itemId == R.id.nav_article) {
            toolbarTitle.setText(getString(R.string.work_list_title));
            toolbarIcon.setImageDrawable(
                    MdiIcons.drawable(this, "book-open-page-variant"));
        } else if (itemId == R.id.nav_profile) {
            toolbarTitle.setText(getString(R.string.profile_title));
            toolbarIcon.setImageDrawable(
                    MdiIcons.drawable(this, "account"));
        }
    }

    @Override
    protected void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putInt("currentItemId", currentItemId);
    }

    @Override protected void onResume() {
        super.onResume();
        refreshTimelineCount();
    }

    private void refreshTimelineCount() {
        final int request = ++unreadRequest;
        if (unreadCall != null) unreadCall.cancel();
        final String token = UserPreferences.getToken(this);
        if (token.isEmpty()) { setTimelineCount(0); return; }
        unreadCall = com.fimtale.network.RetrofitClient.getInstance().getTimelineUpdateCount(token);
        unreadCall.enqueue(new retrofit2.Callback<Integer>() {
            @Override public void onResponse(retrofit2.Call<Integer> call, retrofit2.Response<Integer> response) {
                if (isDestroyed() || request != unreadRequest || !token.equals(UserPreferences.getToken(MainActivity.this))) return;
                if (response.isSuccessful() && response.body() != null) setTimelineCount(response.body());
            }
            @Override public void onFailure(retrofit2.Call<Integer> call, Throwable error) {}
        });
    }

    public void onTimelineRead() {
        unreadRequest++;
        if (unreadCall != null) unreadCall.cancel();
        setTimelineCount(0);
    }

    private void setTimelineCount(int count) {
        BottomNavigationView navigation = findViewById(R.id.bottom_navigation);
        // A text count remains visible without an icon to anchor a Material badge to.
        navigation.getMenu().findItem(R.id.nav_timeline).setTitle(count > 0
                ? getString(R.string.timeline_unread_tab, count > 99 ? getString(R.string.common_count_overflow) : count) : getString(R.string.nav_timeline));
    }

    @Override protected void onDestroy() {
        unreadRequest++;
        if (unreadCall != null) unreadCall.cancel();
        super.onDestroy();
    }
}
