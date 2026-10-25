package com.fimtale.community;

import android.app.Dialog;
import android.os.Bundle;
import android.text.InputFilter;
import android.widget.EditText;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.DialogFragment;
import androidx.lifecycle.ViewModelProvider;
import com.fimtale.R;
import com.fimtale.utils.MdiIcons;
import com.fimtale.utils.UserPreferences;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

public final class FolderNameDialog extends DialogFragment {
    private EditText input;
    static void show(CommunityListActivity activity, Integer id, String name) {
        if (activity.getSupportFragmentManager().isStateSaved() || activity.getSupportFragmentManager().findFragmentByTag("folder_name") != null) return;
        FolderNameDialog dialog = new FolderNameDialog(); Bundle args = new Bundle();
        args.putInt("id", id == null ? 0 : id); args.putString("name", name); args.putString("owner", UserPreferences.getUserId(activity));
        dialog.setArguments(args); dialog.show(activity.getSupportFragmentManager(), "folder_name");
    }
    @NonNull @Override public Dialog onCreateDialog(Bundle saved) {
        input = new com.google.android.material.textfield.TextInputEditText(requireContext());
        int space = Math.round(24 * getResources().getDisplayMetrics().density); input.setPadding(space, space, space, space);
        input.setHint(R.string.community_folder_name); input.setSingleLine(true); input.setFilters(new InputFilter[]{new InputFilter.LengthFilter(191)});
        input.setText(saved == null ? requireArguments().getString("name", "") : saved.getString("text", ""));
        input.setSelection(input.length());
        return new MaterialAlertDialogBuilder(requireContext()).setTitle(requireArguments().getInt("id") == 0 ? R.string.community_create_folder : R.string.community_rename_folder)
                .setView(input).setNegativeButton(R.string.common_cancel, null).setPositiveButton(R.string.common_save, null).create();
    }
    @Override public void onStart() {
        super.onStart(); ((AlertDialog) requireDialog()).getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            if (!UserPreferences.isLoggedIn(requireContext()) || !requireArguments().getString("owner", "").equals(UserPreferences.getUserId(requireContext()))) {
                MdiIcons.setError(input, getString(R.string.community_account_changed)); return;
            }
            String name = input.getText().toString().trim();
            if (name.isEmpty() || name.length() > 191) { MdiIcons.setError(input, getString(R.string.community_folder_name_required)); return; }
            CommunityListModel model = new ViewModelProvider(requireActivity()).get(CommunityListModel.class);
            if (model.mutating) return;
            int id = requireArguments().getInt("id"); model.saveFolder(id == 0 ? null : id, name); dismiss();
        });
    }
    @Override public void onSaveInstanceState(@NonNull Bundle out) { super.onSaveInstanceState(out); if (input != null) out.putString("text", input.getText().toString()); }
    @Override public void onDestroyView() { input = null; super.onDestroyView(); }
}
