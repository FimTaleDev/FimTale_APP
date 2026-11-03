package com.fimtale.ui;


import android.content.Context;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.annotation.MenuRes;
import androidx.appcompat.widget.PopupMenu;
import androidx.core.view.ViewCompat;
import java.util.function.IntConsumer;
import com.fimtale.R;
import com.google.android.material.bottomsheet.BottomSheetBehavior;
import com.google.android.material.bottomsheet.BottomSheetDialog;

/** Actions using the same spacing and bottom surface as the work details menu. */
public final class BottomSheetMenu extends BottomSheetDialog {
    private final Menu menu;
    private final LinearLayout items;
    private final MenuItem.OnMenuItemClickListener listener;
    private final PopupMenu definition;
    private CharSequence heading;

    public BottomSheetMenu(Context context, @MenuRes int resource, MenuItem.OnMenuItemClickListener listener) {
        this(context, listener);
        definition.inflate(resource);
    }

    public BottomSheetMenu(Context context, CharSequence[] titles, IntConsumer selected) {
        this(context, item -> { selected.accept(item.getItemId() - Menu.FIRST); return true; });
        for (int i = 0; i < titles.length; i++) menu.add(Menu.NONE, Menu.FIRST + i, i, titles[i]);
    }

    public BottomSheetMenu(Context context, CharSequence title, CharSequence[] titles, IntConsumer selected) {
        this(context, titles, selected);
        heading = title;
        setTitle(title);
        ViewCompat.setAccessibilityPaneTitle(items, title);
    }

    private BottomSheetMenu(Context context, MenuItem.OnMenuItemClickListener listener) {
        super(context);
        this.listener = listener;
        setContentView(R.layout.dialog_bottom_menu);
        items = findViewById(R.id.bottomMenuItems);
        // Use the platform menu model for XML visibility/enabled state; only the sheet is shown.
        definition = new PopupMenu(getContext(), items);
        menu = definition.getMenu();
        View surface = findViewById(com.google.android.material.R.id.design_bottom_sheet);
        if (surface != null) surface.setBackgroundResource(R.drawable.bg_bottom_sheet_rounded);
        ViewCompat.setAccessibilityPaneTitle(items, context.getString(R.string.common_more));
        getBehavior().setSkipCollapsed(true);
    }

    public Menu getMenu() { return menu; }

    /** Hosts call this when permissions or loading state change while the menu is open. */
    public void refresh() {
        items.removeAllViews();
        if (heading != null) {
            TextView title = new TextView(getContext());
            title.setText(heading);
            title.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_TitleLarge);
            int space = Math.round(12 * getContext().getResources().getDisplayMetrics().density);
            title.setPadding(space, space, space, space);
            ViewCompat.setAccessibilityHeading(title, true);
            items.addView(title);
        }
        for (int i = 0; i < menu.size(); i++) {
            MenuItem item = menu.getItem(i);
            if (!item.isVisible()) continue;
            TextView row = (TextView) LayoutInflater.from(getContext()).inflate(R.layout.item_bottom_menu_action, items, false);
            row.setId(item.getItemId()); row.setText(item.getTitle());
            if (item.getIcon() != null) {
                row.setCompoundDrawablesRelativeWithIntrinsicBounds(item.getIcon(), null, null, null);
                row.setCompoundDrawablePadding(Math.round(12 * getContext().getResources().getDisplayMetrics().density));
                row.setCompoundDrawableTintList(row.getTextColors());
            }
            row.setEnabled(item.isEnabled()); row.setAlpha(item.isEnabled() ? 1f : .38f);
            row.setOnClickListener(view -> {
                if (!item.isVisible() || !item.isEnabled()) return;
                dismiss();
                listener.onMenuItemClick(item);
            });
            items.addView(row);
        }
    }

    @Override public void show() {
        if (isShowing()) return;
        refresh(); super.show();
        getBehavior().setState(BottomSheetBehavior.STATE_EXPANDED);
    }
}
