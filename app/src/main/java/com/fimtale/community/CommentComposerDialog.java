package com.fimtale.community;

import android.app.Application;
import android.app.Dialog;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.DialogFragment;
import androidx.fragment.app.FragmentActivity;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.ViewModelProvider;
import com.fimtale.R;
import com.fimtale.editor.BbCodeEditText;
import com.fimtale.editor.EditorPreviewDialog;
import com.fimtale.model.WorkCommentRequest;
import com.fimtale.network.ApiErrors;
import com.fimtale.network.RetrofitClient;
import com.fimtale.ui.FtemojiPicker;
import com.fimtale.utils.UserPreferences;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

/** A retained composer; rotation never resends a comment. */
public final class CommentComposerDialog extends DialogFragment {
    public static final String TAG = "community_comment_composer";
    private Model model;
    private View content;
    private BbCodeEditText input;
    private AlertDialog preview;

    public static void show(FragmentActivity activity, int work, int chapter, int reply, int edit, String source, String title) {
        if (activity.getSupportFragmentManager().isStateSaved()
                || activity.getSupportFragmentManager().findFragmentByTag(TAG) != null) return;
        Bundle args = new Bundle(); args.putInt("work", work); args.putInt("chapter", chapter);
        args.putInt("reply", reply); args.putInt("edit", edit); args.putString("source", source);
        args.putString("title", title); args.putString("owner", UserPreferences.getUserId(activity));
        CommentComposerDialog dialog = new CommentComposerDialog(); dialog.setArguments(args);
        dialog.show(activity.getSupportFragmentManager(), TAG);
    }

    @NonNull @Override public Dialog onCreateDialog(Bundle saved) {
        model = new ViewModelProvider(this).get(Model.class);
        if (model.text == null) model.text = saved == null ? requireArguments().getString("source", "") : saved.getString("text", "");
        content = LayoutInflater.from(requireContext()).inflate(R.layout.dialog_community_comment, null);
        input = content.findViewById(R.id.communityCommentInput); input.setText(model.text);
        input.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) { model.text = s.toString(); }
            @Override public void afterTextChanged(Editable s) { }
        });
        content.findViewById(R.id.communityCommentEmoji).setOnClickListener(v -> FtemojiPicker.show(requireContext(), name -> {
            int start = Math.max(0, Math.min(input.getSelectionStart(), input.getSelectionEnd()));
            int end = Math.max(start, Math.max(input.getSelectionStart(), input.getSelectionEnd()));
            String value = ":ftemoji_" + name + ":";
            input.getText().replace(start, end, value); input.setSelection(start + value.length());
        }));
        content.findViewById(R.id.communityCommentPreview).setOnClickListener(v -> {
            if (preview == null) {
                preview = EditorPreviewDialog.show(requireContext(), input.getText().toString(), 16);
                preview.setOnDismissListener(d -> preview = null);
            }
        });
        AlertDialog dialog = new MaterialAlertDialogBuilder(requireContext()).setTitle(requireArguments().getString("title"))
                .setView(content).setNegativeButton(R.string.common_cancel, null)
                .setPositiveButton(requireArguments().getInt("edit") > 0 ? R.string.community_save : R.string.community_send, null).create();
        dialog.setCanceledOnTouchOutside(false);
        model.changes.observe(this, ignored -> render());
        return dialog;
    }

    @Override public void onStart() {
        super.onStart();
        ((AlertDialog) requireDialog()).getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> model.send(requireArguments()));
        render();
    }
    private void render() {
        if (content == null || getDialog() == null) return;
        if (model.done) { dismissAllowingStateLoss(); return; }
        input.setEnabled(!model.busy);
        content.findViewById(R.id.communityCommentEmoji).setEnabled(!model.busy);
        TextView error = content.findViewById(R.id.communityCommentError);
        error.setText(model.error); error.setVisibility(model.error.isEmpty() ? View.GONE : View.VISIBLE);
        AlertDialog dialog = (AlertDialog) getDialog();
        if (dialog.getButton(AlertDialog.BUTTON_POSITIVE) != null) {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(!model.busy);
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setEnabled(!model.busy);
        }
        setCancelable(!model.busy);
    }
    @Override public void onDestroyView() {
        if (preview != null) { preview.setOnDismissListener(null); preview.dismiss(); preview = null; }
        content = null; input = null; super.onDestroyView();
    }
    @Override public void onSaveInstanceState(@NonNull Bundle out) {
        super.onSaveInstanceState(out); if (model != null) out.putString("text", model.text);
    }

    public static final class Model extends AndroidViewModel {
        public final MutableLiveData<Integer> changes = new MutableLiveData<>(0);
        String text, error = "";
        boolean busy, done, cleared;
        private Call<Void> call;
        public Model(@NonNull Application app) { super(app); }
        private void update() { if (!cleared) changes.setValue(changes.getValue() + 1); }
        void send(Bundle args) {
            if (busy || done) return;
            if (!args.getString("owner", "").equals(UserPreferences.getUserId(getApplication()))
                    || !UserPreferences.isLoggedIn(getApplication())) { error = getApplication().getString(R.string.community_account_changed); update(); return; }
            if (text == null || text.trim().isEmpty()) { error = getApplication().getString(R.string.comments_content_required); update(); return; }
            String token = UserPreferences.getToken(getApplication());
            busy = true; error = ""; update();
            int chapter = args.getInt("chapter"), reply = args.getInt("reply"), edit = args.getInt("edit");
            call = RetrofitClient.getInstance().createUpdateComment(token, new WorkCommentRequest(args.getInt("work"), text,
                    chapter > 0 ? chapter : null, reply > 0 ? reply : null, edit > 0 ? edit : null));
            call.enqueue(new Callback<Void>() {
                @Override public void onResponse(Call<Void> request, Response<Void> response) {
                    if (cleared || request != call) return;
                    busy = false;
                    if (!token.equals(UserPreferences.getToken(getApplication()))) error = getApplication().getString(R.string.community_account_changed);
                    else if (response.isSuccessful()) { done = true; Community.commentChanged(args.getInt("work")); }
                    else error = ApiErrors.message(response);
                    update();
                }
                @Override public void onFailure(Call<Void> request, Throwable failure) {
                    if (cleared || request != call || request.isCanceled()) return;
                    busy = false; error = getApplication().getString(R.string.community_operation_uncertain); update();
                }
            });
        }
        @Override protected void onCleared() { cleared = true; if (call != null) call.cancel(); }
    }
}
