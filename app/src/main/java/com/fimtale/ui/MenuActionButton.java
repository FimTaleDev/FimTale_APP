package com.fimtale.ui;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.MenuItem;
import android.view.ViewGroup;
import com.fimtale.R;
import com.google.android.material.button.MaterialButton;
import java.util.function.Consumer;

/** Toolbar actions use the same icon and progress rendering as reaction buttons. */
public final class MenuActionButton implements AutoCloseable {
    private final MaterialButton button;
    private final MenuItem item;
    private final ButtonLoadingIcon loading = new ButtonLoadingIcon();
    public MenuActionButton(Context context, MenuItem item, Consumer<MenuItem> clicked) {
        this.item = item;
        button = (MaterialButton) LayoutInflater.from(context).inflate(R.layout.view_interaction_menu_button, null);
        int size = Math.round(48 * context.getResources().getDisplayMetrics().density);
        button.setLayoutParams(new ViewGroup.LayoutParams(size, size));
        item.setActionView(button); item.setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS);
        button.setOnClickListener(v -> { if (item.isEnabled()) clicked.accept(item); });
    }
    public void bind(String icon, boolean active, boolean busy, boolean enabled) {
        item.setEnabled(enabled && !busy); button.setEnabled(enabled && !busy);
        button.setContentDescription(item.getTitle()); button.setTooltipText(item.getTitle());
        loading.bind(button, icon, active, busy && item.isVisible());
    }
    public void setCount(int count) {
        button.setText(String.valueOf(count));
        button.setIconPadding(Math.round(4 * button.getResources().getDisplayMetrics().density));
        ViewGroup.LayoutParams params = button.getLayoutParams(); params.width = ViewGroup.LayoutParams.WRAP_CONTENT;
        button.setLayoutParams(params);
    }
    @Override public void close() { loading.close(); }
}
