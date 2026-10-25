package com.fimtale.ui;

import android.content.Context;
import android.graphics.Rect;
import android.os.Bundle;
import android.view.inputmethod.InputMethodManager;
import android.widget.Toast;
import com.fimtale.R;
import com.fimtale.model.Comment;
import com.fimtale.utils.MdiIcons;
import com.fimtale.utils.UserPreferences;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;

/** Reply context belongs to the existing composer; switching/cancelling a reply preserves its text. */
public final class CommentReplyInput implements AutoCloseable {
    private final TextInputLayout layout;
    private final TextInputEditText input;
    private final CharSequence normalHint;
    private final Runnable changed;
    private Bundle target;
    private boolean closed;

    public CommentReplyInput(TextInputLayout layout, TextInputEditText input, Runnable changed) {
        this.layout = layout; this.input = input; this.changed = changed;
        normalHint = input.getContext().getText(R.string.comments_post_hint);
        render();
    }
    public static Bundle target(Context context, Comment comment, int workId) {
        Bundle result = new Bundle(); result.putInt("work", workId); result.putInt("comment", comment.id);
        result.putInt("chapter", comment.chapterId); result.putString("name", comment.getUserName());
        result.putString("owner", UserPreferences.getUserId(context)); return result;
    }
    public void reply(Comment comment, int workId) {
        if (closed || !input.isEnabled() || comment.id <= 0 || comment.statusDel != 0) return;
        target = target(input.getContext(), comment, workId); render(); changed.run();
    }
    public boolean active() { return target != null; }
    public Integer commentId() { return target == null ? null : target.getInt("comment"); }
    public Integer chapterId() { return target == null || target.getInt("chapter") <= 0 ? null : target.getInt("chapter"); }
    public boolean canSubmit() {
        if (target == null || target.getString("owner", "").equals(UserPreferences.getUserId(input.getContext()))) return true;
        Toast.makeText(input.getContext(), R.string.community_account_changed, Toast.LENGTH_LONG).show(); return false;
    }
    public void clear() { if (closed) return; target = null; render(); changed.run(); }
    public Bundle save() { return target == null ? null : new Bundle(target); }
    public void restore(Bundle state, int workId) {
        if (closed) return;
        target = state != null && state.getInt("comment") > 0 && state.getInt("work") == workId ? new Bundle(state) : null;
        render(); changed.run();
    }
    private void render() {
        layout.setHint(target == null ? normalHint : input.getContext().getString(R.string.community_reply_to,
                target.getString("name", ""), target.getInt("comment")));
        layout.setEndIconMode(target == null ? TextInputLayout.END_ICON_NONE : TextInputLayout.END_ICON_CUSTOM);
        if (target != null) {
            layout.setEndIconDrawable(MdiIcons.drawable(input.getContext(), "close"));
            layout.setEndIconContentDescription(R.string.community_cancel_reply);
            layout.setEndIconCheckable(false);
            layout.setEndIconOnClickListener(v -> { if (!closed && input.isEnabled()) clear(); });
        }
    }
    public void focus() {
        input.post(() -> {
            if (closed || !active() || !input.isAttachedToWindow() || !input.isShown() || !input.isEnabled()) return;
            input.requestFocus(); input.setSelection(input.length());
            Rect bounds = new Rect(); input.getDrawingRect(bounds); input.requestRectangleOnScreen(bounds, true);
            InputMethodManager keyboard = (InputMethodManager) input.getContext().getSystemService(Context.INPUT_METHOD_SERVICE);
            if (keyboard != null) keyboard.showSoftInput(input, InputMethodManager.SHOW_IMPLICIT);
        });
    }
    @Override public void close() { closed = true; }
}
