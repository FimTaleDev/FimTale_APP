package com.fimtale.ui;

import android.content.Context;
import android.text.TextUtils;
import android.util.AttributeSet;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;
import androidx.annotation.Nullable;
import androidx.annotation.StringRes;
import com.fimtale.R;
import com.fimtale.utils.MdiIcons;

/** Shared empty, login and retryable error states, using the home page's presentation. */
public final class PageErrorView extends FrameLayout {
    private View content;
    private int previousAccessibility;
    private Runnable retry;
    private String lastMessage, dismissedMessage;
    private String iconName;
    public PageErrorView(Context context) { this(context, null); }
    public PageErrorView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        LayoutInflater.from(context).inflate(R.layout.view_page_error, this, true);
        setVisibility(GONE);
        findViewById(R.id.pageErrorRetry).setOnClickListener(view -> {
            Runnable action = retry;
            if (action != null) {
                hide();
                action.run();
            }
        });
        findViewById(R.id.pageErrorContinue).setOnClickListener(view -> {
            String message = lastMessage;
            hide(); dismissedMessage = message;
        });
    }
    /** Wrap the refresh container too, keeping the error overlay outside the translated content. */
    public static PageErrorView wrap(View content) {
        View anchor = content.getParent() instanceof PullRefreshLayout ? (View) content.getParent() : content;
        ViewGroup parent = (ViewGroup) anchor.getParent();
        // Reader page holders reuse their views when binding another chapter.
        for (int i = 0; i < parent.getChildCount(); i++) {
            View child = parent.getChildAt(i);
            if (child instanceof PageErrorView && ((PageErrorView) child).content == anchor)
                return (PageErrorView) child;
        }
        int index = parent.indexOfChild(anchor);
        ViewGroup.LayoutParams params = anchor.getLayoutParams();
        FrameLayout host = new FrameLayout(content.getContext());
        parent.removeView(anchor);
        host.addView(anchor, new FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
        PageErrorView error = new PageErrorView(content.getContext());
        error.content = anchor;
        host.addView(error, new FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
        parent.addView(host, index, params);
        return error;
    }
    /** Keep the empty state inside the same pull-to-refresh surface as its list. */
    public static View withEmptyState(View content, PageErrorView empty) {
        ViewGroup parent = (ViewGroup) content.getParent();
        int index = parent.indexOfChild(content);
        ViewGroup.LayoutParams params = content.getLayoutParams();
        parent.removeView(content);
        ((ViewGroup) empty.getParent()).removeView(empty);
        FrameLayout surface = new FrameLayout(content.getContext());
        surface.addView(content, new FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
        surface.addView(empty, new FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
        parent.addView(surface, index, params);
        return surface;
    }
    /** An inline state for sections, dialogs and list rows. */
    public static PageErrorView addTo(ViewGroup parent) {
        PageErrorView state = new PageErrorView(parent.getContext());
        parent.addView(state, new ViewGroup.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));
        return state;
    }
    public void show(String message, Runnable retry) { show(message, retry, false); }
    public void show(String message, Runnable retry, boolean hasContent) {
        String detail = message == null || message.trim().isEmpty()
                ? getContext().getString(R.string.page_error_message) : message;
        if (detail.equals(dismissedMessage)) return;
        lastMessage = detail;
        bind(R.string.page_error_title, detail, "cloud-off-outline", R.string.page_error_retry, retry);
        findViewById(R.id.pageErrorContinue).setVisibility(hasContent ? VISIBLE : GONE);
    }
    public void showEmpty(CharSequence message) { showEmpty(message, null); }
    public void showEmpty(CharSequence message, @Nullable Runnable refresh) {
        showPrompt(R.string.page_empty_title, message, "inbox-outline", R.string.common_refresh, refresh);
    }
    public void showLogin(CharSequence message, Runnable login) {
        showPrompt(R.string.page_login_title, message, "account-outline", R.string.common_login, login);
    }
    public void showPrompt(@StringRes int title, CharSequence message, String icon,
            @StringRes int actionLabel, @Nullable Runnable action) {
        lastMessage = null;
        dismissedMessage = null;
        bind(title, message, icon, actionLabel, action);
        findViewById(R.id.pageErrorContinue).setVisibility(GONE);
    }
    public void setActionEnabled(boolean enabled) {
        findViewById(R.id.pageErrorRetry).setEnabled(enabled);
    }
    private void bind(int title, CharSequence message, String icon, int actionLabel, Runnable action) {
        retry = action;
        if (!icon.equals(iconName)) {
            ((ImageView) findViewById(R.id.pageErrorIcon)).setImageDrawable(MdiIcons.drawable(getContext(), icon));
            iconName = icon;
        }
        TextView heading = findViewById(R.id.pageErrorTitle);
        if (!TextUtils.equals(heading.getText(), getContext().getText(title))) heading.setText(title);
        TextView detail = findViewById(R.id.pageErrorMessage);
        if (!TextUtils.equals(detail.getText(), message)) detail.setText(message);
        detail.setVisibility(message == null || message.length() == 0
                || getContext().getString(title).contentEquals(message) ? GONE : VISIBLE);
        TextView button = findViewById(R.id.pageErrorRetry);
        if (!TextUtils.equals(button.getText(), getContext().getText(actionLabel))) button.setText(actionLabel);
        button.setEnabled(true);
        button.setVisibility(action == null ? GONE : VISIBLE);
        if (getVisibility() != VISIBLE && content != null) {
            previousAccessibility = content.getImportantForAccessibility();
            content.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        }
        setVisibility(VISIBLE);
    }
    public void hide() {
        if (getVisibility() == VISIBLE && content != null) content.setImportantForAccessibility(previousAccessibility);
        retry = null; dismissedMessage = null; setVisibility(GONE);
    }
}
