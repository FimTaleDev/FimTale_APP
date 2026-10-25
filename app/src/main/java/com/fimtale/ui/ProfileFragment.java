package com.fimtale.ui;

import com.fimtale.utils.MdiIcons;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import android.widget.Toast;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import androidx.annotation.NonNull;
import androidx.preference.PreferenceManager;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.core.view.MenuProvider;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.Lifecycle;

import com.fimtale.FavoritesActivity;
import com.fimtale.HistoryActivity;
import com.fimtale.LoginActivity;
import com.fimtale.UserDetailActivity;
import com.fimtale.R;
import com.fimtale.SettingsActivity;
import com.fimtale.network.RetrofitClient;
import com.fimtale.utils.DialogHelper;
import com.fimtale.utils.UserPreferences;
import com.bumptech.glide.Glide;
import com.google.android.material.imageview.ShapeableImageView;
import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

public class ProfileFragment extends Fragment {

    private View layoutUserHeader;
    private ShapeableImageView ivAvatar;
    private TextView tvUsername;
    private TextView tvBio;
    private View btnFavorites, btnHistory;
    private View btnReviewQueue;
    private Call<com.fimtale.model.CurrentUser> userCall;
    private PageErrorView pageError;
    private boolean isLoggedIn = false;

    private View contentLayout;
    private View emptyStateLayout;
    private View btnLogin;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_profile, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        contentLayout = view.findViewById(R.id.contentLayout);
        emptyStateLayout = view.findViewById(R.id.emptyStateLayout);
        btnLogin = view.findViewById(R.id.btnLogin);

        layoutUserHeader = view.findViewById(R.id.layoutUserHeader);
        ivAvatar = view.findViewById(R.id.ivAvatar);
        tvUsername = view.findViewById(R.id.tvUsername);
        tvBio = view.findViewById(R.id.tvBio);

        btnFavorites = view.findViewById(R.id.btnFavorites);
        btnHistory = view.findViewById(R.id.btnHistory);
        btnReviewQueue = view.findViewById(R.id.btnReviewQueue);
        view.findViewById(R.id.btnMyReports).setOnClickListener(v ->
                startActivity(new Intent(requireContext(), com.fimtale.MyReportsActivity.class)));
        view.findViewById(R.id.btnMessages).setOnClickListener(v ->
                startActivity(new Intent(requireContext(), com.fimtale.NotificationsActivity.class)));
        btnReviewQueue.setOnClickListener(v -> startActivity(new Intent(requireContext(), com.fimtale.ReviewQueueActivity.class)));
        view.findViewById(R.id.btnPublish).setOnClickListener(v ->
                startActivity(new Intent(requireContext(), com.fimtale.DraftsActivity.class)));
        view.findViewById(R.id.btnRecycle).setOnClickListener(v ->
                startActivity(new Intent(requireContext(), com.fimtale.recycle.RecycleActivity.class)));

        setupButtons();
        setupEmptyState();
        pageError = PageErrorView.wrap(view.findViewById(R.id.profileScroll));

        requireActivity().addMenuProvider(new MenuProvider() {
            @Override
            public void onCreateMenu(@NonNull Menu menu, @NonNull MenuInflater menuInflater) {
                MdiIcons.inflateMenu(requireContext(), menuInflater, R.menu.profile_menu, menu);
            }

            @Override
            public boolean onMenuItemSelected(@NonNull MenuItem menuItem) {
                if (menuItem.getItemId() == R.id.action_settings) {
                    Intent intent = new Intent(getActivity(), SettingsActivity.class);
                    startActivity(intent);
                    return true;
                } else if (menuItem.getItemId() == R.id.action_toggle_theme) {
                    toggleTheme();
                    return true;
                }
                return false;
            }
        }, getViewLifecycleOwner(), Lifecycle.State.RESUMED);
    }

    private void setupEmptyState() {
        if (btnLogin != null) {
            btnLogin.setOnClickListener(v -> {
                DialogHelper.openLogin(requireContext());
            });
        }
    }

    private void loadContent() {
        pageError.hide();
        if (userCall != null) userCall.cancel();
        btnReviewQueue.setVisibility(View.GONE);
        if (UserPreferences.isLoggedIn(requireContext())) {
            emptyStateLayout.setVisibility(View.GONE);
            contentLayout.setVisibility(View.VISIBLE);
            loadCachedUserInfo();
            checkLoginStatus();
            btnReviewQueue.setVisibility(View.VISIBLE);
        } else {
            isLoggedIn = false;
            emptyStateLayout.setVisibility(View.VISIBLE);
            contentLayout.setVisibility(View.GONE);
        }
    }

    private void setupButtons() {
        btnFavorites.setOnClickListener(v -> {
            if (!isLoggedIn) {
                Intent intent = new Intent(getActivity(), LoginActivity.class);
                startActivity(intent);
                return;
            }
            Intent intent = new Intent(getActivity(), FavoritesActivity.class);
            startActivity(intent);
        });

        btnHistory.setOnClickListener(v -> {
            if (!isLoggedIn) {
                Intent intent = new Intent(getActivity(), LoginActivity.class);
                startActivity(intent);
                return;
            }
            Intent intent = new Intent(getActivity(), HistoryActivity.class);
            startActivity(intent);
        });
    }

    @Override
    public void onResume() {
        super.onResume();
        loadContent();
    }

    private void loadCachedUserInfo() {
        String userIdStr = UserPreferences.getUserId(requireContext());
        String userName = UserPreferences.getUserName(requireContext());

        if (!userIdStr.isEmpty() && !userName.isEmpty()) {
            try {
                int userId = Integer.parseInt(userIdStr);
                isLoggedIn = true;
                updateUserInfo(userId, userName);
            } catch (NumberFormatException e) {
                // 不做处理
            }
        }
    }

    private void checkLoginStatus() {
        String token = UserPreferences.getToken(requireContext());
        userCall = RetrofitClient.getInstance().getCurrentUser(token);
        userCall.enqueue(new Callback<com.fimtale.model.CurrentUser>() {
            @Override public void onResponse(Call<com.fimtale.model.CurrentUser> call, Response<com.fimtale.model.CurrentUser> response) {
                if (!isAdded() || getView() == null || call != userCall || call.isCanceled()) return;
                if (!token.equals(UserPreferences.getToken(requireContext()))) { loadContent(); return; }
                com.fimtale.model.CurrentUser user = response.body();
                if (response.isSuccessful() && user != null && user.id > 0) {
                    isLoggedIn = true;
                    UserPreferences.saveUserId(requireContext(), String.valueOf(user.id));
                    UserPreferences.saveUserName(requireContext(), user.username);
                    UserPreferences.saveAvatar(requireContext(), user.getAvatar());
                    updateUserInfo(user.id, user.username);
                } else if (response.code() == 401) {
                    UserPreferences.clearSession(requireContext());
                    isLoggedIn = false;
                    loadContent();
                } else pageError.show(com.fimtale.network.ApiErrors.message(response), ProfileFragment.this::loadContent);
            }
            @Override public void onFailure(Call<com.fimtale.model.CurrentUser> call, Throwable t) {
                if (!isAdded() || getView() == null || call != userCall || call.isCanceled()) return;
                if (!token.equals(UserPreferences.getToken(requireContext()))) { loadContent(); return; }
                pageError.show(null, ProfileFragment.this::loadContent);
            }
        });
    }

    @Override public void onDestroyView() {
        pageError = null;
        if (userCall != null) userCall.cancel();
        userCall = null; btnReviewQueue = null;
        super.onDestroyView();
    }

    private void updateUserInfo(int userId, String userName) {
        if (!isAdded() || getActivity() == null || getActivity().isFinishing() || getActivity().isDestroyed()) return;
        if (isLoggedIn && userId != 0 && userName != null) {
            tvUsername.setText(userName);
            tvBio.setText(getString(R.string.profile_welcome_back));
            layoutUserHeader.setOnClickListener(v -> {
                Intent intent = new Intent(getActivity(), UserDetailActivity.class);
                intent.putExtra(UserDetailActivity.EXTRA_USERNAME, userName);
                startActivity(intent);
            });

            ivAvatar.setImageTintList(null);

            String avatarUrl = UserPreferences.getAvatar(requireContext());

            Glide.with(this)
                    .load(avatarUrl)
                    .placeholder(MdiIcons.drawable(requireContext(), "account"))
                    .error(MdiIcons.drawable(requireContext(), "account"))
                    .into(ivAvatar);

        } else {
            tvUsername.setText(getString(R.string.profile_tap_login));
            tvBio.setText(getString(R.string.profile_login_more));
            int color = com.google.android.material.color.MaterialColors.getColor(ivAvatar, com.google.android.material.R.attr.colorOnSurfaceVariant);
            ivAvatar.setImageTintList(android.content.res.ColorStateList.valueOf(color));
            ivAvatar.setImageDrawable(MdiIcons.drawable(requireContext(), "account"));
            layoutUserHeader.setOnClickListener(v -> {
                Intent intent = new Intent(getActivity(), LoginActivity.class);
                startActivity(intent);
            });
        }
    }

    private void toggleTheme() {
        SharedPreferences sharedPreferences = PreferenceManager.getDefaultSharedPreferences(requireContext());
        
        int currentNightMode = getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;
        boolean isNight = currentNightMode == Configuration.UI_MODE_NIGHT_YES;
        boolean newIsNight = !isNight;

        sharedPreferences.edit()
                .putBoolean("theme_follow_system", false)
                .putBoolean("manual_dark_mode", newIsNight)
                .apply();

        if (newIsNight) {
            AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES);
        } else {
            AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO);
        }
    }
}
