package com.fimtale.report;

import android.app.Dialog;
import android.content.Intent;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.DialogFragment;
import androidx.fragment.app.FragmentActivity;
import androidx.fragment.app.FragmentManager;
import androidx.lifecycle.ViewModelProvider;
import com.fimtale.LoginActivity;
import com.fimtale.R;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;

/** Shared native report form for works, comments and users. */
public class ReportDialog extends DialogFragment {
    public static final String TAG = "report_dialog";
    private ReportViewModel model;
    private View content;
    private TextInputEditText input;

    public static void show(FragmentActivity activity, int type, long id, String label) {
        show(activity, type, id, label, false);
    }
    public static void showRecovery(FragmentActivity activity, int type, long id, String label) {
        if (type == ReportRequest.USER) return;
        show(activity, type, id, label, true);
    }
    private static void show(FragmentActivity activity, int type, long id, String label, boolean recovery) {
        FragmentManager manager = activity.getSupportFragmentManager();
        if (manager.isStateSaved() || manager.findFragmentByTag(TAG) != null) return;
        if (!ReportRequest.isTarget(type) || id <= 0) return;
        Bundle args = new Bundle(); args.putInt("target_type", type); args.putLong("target_id", id); args.putString("target_label", label);
        args.putBoolean("recovery", recovery);
        ReportDialog dialog = new ReportDialog(); dialog.setArguments(args); dialog.showNow(manager, TAG);
    }
    @NonNull @Override public Dialog onCreateDialog(Bundle state) {
        model = new ViewModelProvider(this).get(ReportViewModel.class);
        MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(requireContext());
        content = LayoutInflater.from(builder.getContext()).inflate(R.layout.dialog_report, null);
        ((TextView) content.findViewById(R.id.reportTarget)).setText(requireArguments().getString("target_label"));
        input = content.findViewById(R.id.reportContent);
        if (requireArguments().getBoolean("recovery")) {
            ((TextInputLayout) content.findViewById(R.id.reportContentLayout)).setHint(getString(R.string.recycle_request_content));
            input.setHint(R.string.recycle_request_hint);
        }
        input.setText(model.content()); input.setSelection(input.length());
        input.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) { model.setContent(s.toString()); }
            @Override public void afterTextChanged(Editable text) {}
        });
        AlertDialog dialog = builder.setTitle(requireArguments().getBoolean("recovery") ? R.string.recycle_request :
                        requireArguments().getInt("target_type") == ReportRequest.CHANNEL ? R.string.channel_report :
                        requireArguments().getInt("target_type") == ReportRequest.CHANNEL_COMMENT ? R.string.community_report_comment :
                        requireArguments().getInt("target_type") == ReportRequest.WORK || requireArguments().getInt("target_type") == ReportRequest.CHAPTER
                        ? R.string.report_work : requireArguments().getInt("target_type") == ReportRequest.COMMENT
                        ? R.string.community_report_comment : R.string.report_user)
                .setView(content)
                .setNegativeButton(R.string.report_cancel, null).setPositiveButton(R.string.report_submit, null).create();
        dialog.setCanceledOnTouchOutside(false);
        model.changes.observe(this, ignored -> render());
        return dialog;
    }
    @Override public void onStart() {
        super.onStart();
        ((AlertDialog) requireDialog()).getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            model.connect();
            if (model.needsLogin) startActivity(new Intent(requireContext(), LoginActivity.class));
            else {
                model.submit(requireArguments().getInt("target_type"), requireArguments().getLong("target_id"), requireArguments().getBoolean("recovery"));
                if (!model.contentError.isEmpty()) input.requestFocus();
            }
        });
        render();
    }
    @Override public void onResume() { super.onResume(); model.connect(); }
    private void render() {
        if (content == null || getDialog() == null) return;
        if (model.complete) {
            if (!model.successAnnounced) {
                model.successAnnounced = true;
                Toast.makeText(requireContext(), requireArguments().getBoolean("recovery") ? R.string.recycle_request_sent : R.string.report_success, Toast.LENGTH_LONG).show();
            }
            dismissAllowingStateLoss(); return;
        }
        input.setEnabled(!model.submitting);
        ((TextInputLayout) content.findViewById(R.id.reportContentLayout)).setError(model.contentError.isEmpty() ? null : model.contentError);
        content.findViewById(R.id.reportProgress).setVisibility(model.submitting ? View.VISIBLE : View.GONE);
        TextView error = content.findViewById(R.id.reportError);
        boolean recovery = requireArguments().getBoolean("recovery");
        String message = model.needsLogin ? getString(recovery ? R.string.recycle_request_login : R.string.report_login_required) : model.error;
        error.setText(message); error.setVisibility(message.isEmpty() ? View.GONE : View.VISIBLE);
        AlertDialog dialog = (AlertDialog) requireDialog();
        if (dialog.getButton(AlertDialog.BUTTON_POSITIVE) != null) {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setText(model.needsLogin ? R.string.report_login
                    : model.submitting ? R.string.report_submitting : recovery ? R.string.recycle_request_submit : R.string.report_submit);
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(!model.submitting);
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setEnabled(!model.submitting);
        }
        setCancelable(!model.submitting);
    }
    @Override public void onDestroyView() { content = null; input = null; super.onDestroyView(); }
}
