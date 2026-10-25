package com.fimtale.ui;

import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.preference.Preference;
import androidx.preference.PreferenceFragmentCompat;
import androidx.preference.PreferenceManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.annotation.NonNull;
import android.content.Intent;
import com.fimtale.AboutActivity;
import com.fimtale.R;
import com.fimtale.SettingsActivity;
import com.fimtale.AccountSessionsActivity;
import com.fimtale.ContentFiltersActivity;
import com.fimtale.db.CacheManager;
import com.fimtale.utils.UserPreferences;
import com.fimtale.utils.DialogHelper;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.textfield.TextInputEditText;
import android.widget.Toast;
import androidx.preference.ListPreference;

public class SettingsFragment extends PreferenceFragmentCompat implements SharedPreferences.OnSharedPreferenceChangeListener {

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        
        setDivider(null);
        setDividerHeight(0);

        RecyclerView recyclerView = getListView();
        int paddingTop = (int) (88 * getResources().getDisplayMetrics().density);
        recyclerView.setPadding(0, paddingTop, 0, 0);
        recyclerView.setClipToPadding(false);

        recyclerView.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(@NonNull RecyclerView recyclerView, int dx, int dy) {
                if (getActivity() instanceof SettingsActivity) {
                    ((SettingsActivity) getActivity()).onScroll(recyclerView.computeVerticalScrollOffset());
                }
            }
        });
    }

    @Override
    public void onCreatePreferences(@Nullable Bundle savedInstanceState, @Nullable String rootKey) {
        setPreferencesFromResource(R.xml.preferences, rootKey);

        Preference account = findPreference("account");
        if (account != null) account.setOnPreferenceClickListener(preference -> {
            if (UserPreferences.isLoggedIn(requireContext())) startActivity(new Intent(requireContext(), com.fimtale.account.AccountActivity.class));
            else DialogHelper.openLogin(requireContext());
            return true;
        });
        modulePreference("sessions", AccountSessionsActivity.class);
        modulePreference("recycle", com.fimtale.recycle.RecycleActivity.class);
        Preference filters = findPreference("content_filters");
        if (filters != null) filters.setOnPreferenceClickListener(preference -> {
            if (UserPreferences.isLoggedIn(requireContext())) startActivity(new Intent(requireContext(), ContentFiltersActivity.class));
            else DialogHelper.openLogin(requireContext());
            return true;
        });

        Preference logoutPref = findPreference("logout");
        if (logoutPref != null) {
            if (UserPreferences.isLoggedIn(requireContext())) {
                logoutPref.setVisible(true);
                logoutPref.setOnPreferenceClickListener(preference -> {
                    new MaterialAlertDialogBuilder(requireContext())
                            .setTitle(getString(R.string.login_logout))
                            .setMessage(getString(R.string.login_logout_message))
                            .setPositiveButton(getString(R.string.common_confirm), (dialog, which) -> {
                                com.fimtale.network.RetrofitClient.getInstance().logout(UserPreferences.getToken(requireContext())).enqueue(new retrofit2.Callback<okhttp3.ResponseBody>() {
                                    @Override public void onResponse(retrofit2.Call<okhttp3.ResponseBody> call, retrofit2.Response<okhttp3.ResponseBody> response) {
                                        if (response.body() != null) response.body().close();
                                    }
                                    @Override public void onFailure(retrofit2.Call<okhttp3.ResponseBody> call, Throwable t) {}
                                });
                                UserPreferences.clearSession(requireContext());
                                CacheManager.getInstance(requireContext()).clearAllCache(null);
                                Toast.makeText(requireContext(), getString(R.string.login_signed_out), Toast.LENGTH_SHORT).show();
                                getActivity().finish();
                            })
                            .setNegativeButton(getString(R.string.common_cancel), null)
                            .show();
                    return true;
                });
            } else {
                logoutPref.setVisible(false);
            }
        }

        Preference aboutPref = findPreference("about");
        if (aboutPref != null) {
            aboutPref.setOnPreferenceClickListener(preference -> {
                startActivity(new Intent(requireContext(), AboutActivity.class));
                return true;
            });
        }

        // Cache management
        Preference cacheSizePref = findPreference("cache_size_display");
        if (cacheSizePref != null) {
            CacheManager.getInstance(requireContext()).getTotalCacheSize(size ->
                    cacheSizePref.setSummary(formatBytes(size)));
        }

        ListPreference maxCachePref = findPreference("max_cache_size");
        if (maxCachePref != null) {
            long currentMax = UserPreferences.getMaxCacheSize(requireContext());
            maxCachePref.setValue(String.valueOf(currentMax));
            maxCachePref.setSummary(formatBytes(currentMax));
            maxCachePref.setOnPreferenceChangeListener((preference, newValue) -> {
                long newMax = Long.parseLong((String) newValue);
                UserPreferences.setMaxCacheSize(requireContext(), newMax);
                maxCachePref.setSummary(formatBytes(newMax));
                CacheManager.getInstance(requireContext()).setMaxCacheSize(newMax);
                return true;
            });
        }

        Preference clearCachePref = findPreference("clear_cache");
        if (clearCachePref != null) {
            clearCachePref.setOnPreferenceClickListener(preference -> {
                new MaterialAlertDialogBuilder(requireContext())
                        .setTitle(getString(R.string.settings_clear_cache))
                        .setMessage(getString(R.string.settings_clear_cache_message))
                        .setPositiveButton(getString(R.string.common_confirm), (dialog, which) ->
                                CacheManager.getInstance(requireContext()).clearAllCache(() -> {
                                    if (cacheSizePref != null) {
                                        cacheSizePref.setSummary(formatBytes(0));
                                    }
                                    Toast.makeText(requireContext(), getString(R.string.settings_cache_cleared), Toast.LENGTH_SHORT).show();
                                }))
                        .setNegativeButton(getString(R.string.common_cancel), null)
                        .show();
                return true;
            });
        }
    }
    
    private void modulePreference(String key, Class<?> activity) {
        Preference preference = findPreference(key);
        if (preference != null) preference.setOnPreferenceClickListener(item -> {
            startActivity(new Intent(requireContext(), activity)); return true;
        });
    }

    private String formatBytes(long bytes) {
        if (bytes < 1024) return getString(R.string.settings_size_bytes, bytes);
        if (bytes < 1024 * 1024) return getString(R.string.settings_size_kilobytes, bytes / 1024.0);
        if (bytes < 1024L * 1024 * 1024) return getString(R.string.settings_size_megabytes, bytes / (1024.0 * 1024));
        return getString(R.string.settings_size_gigabytes, bytes / (1024.0 * 1024 * 1024));
    }

    @Override
    public void onResume() {
        super.onResume();
        getPreferenceScreen().getSharedPreferences()
                .registerOnSharedPreferenceChangeListener(this);
    }

    @Override
    public void onPause() {
        super.onPause();
        getPreferenceScreen().getSharedPreferences()
                .unregisterOnSharedPreferenceChangeListener(this);
    }

    @Override
    public void onSharedPreferenceChanged(SharedPreferences sharedPreferences, String key) {
        if ("theme_follow_system".equals(key)) {
            boolean followSystem = sharedPreferences.getBoolean(key, true);
            if (followSystem) {
                AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM);
            } else {
                boolean manualDarkMode = sharedPreferences.getBoolean("manual_dark_mode", false);
                if (manualDarkMode) {
                    AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES);
                } else {
                    AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO);
                }
            }
        } else if ("auto_update".equals(key)) {
            boolean autoUpdate = sharedPreferences.getBoolean(key, true);
            UserPreferences.setAutoUpdate(requireContext(), autoUpdate);
        } else if ("show_reader_progress".equals(key)) {
            boolean show = sharedPreferences.getBoolean(key, false);
            UserPreferences.setShowReaderProgress(requireContext(), show);
        }
    }
}
