package com.fimtale.utils;

import android.content.Context;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.DrawableWrapper;
import android.util.TypedValue;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.widget.TextView;

import androidx.annotation.ColorInt;
import androidx.annotation.IntRange;
import androidx.annotation.NonNull;

import com.google.android.material.color.MaterialColors;
import com.fimtale.R;
import com.mikepenz.iconics.IconicsDrawable;
import com.mikepenz.iconics.typeface.IIcon;
import com.mikepenz.iconics.typeface.library.community.material.CommunityMaterial;

/** Creates MDI drawables for ImageView, MaterialButton, and menu icons. */
public final class MdiIcons {
    private MdiIcons() {}

    /** Accepts MDI names such as "book-open-page-variant" or "mdi-account". */
    @NonNull
    public static IconicsDrawable drawable(@NonNull Context context, @NonNull String name) {
        String key = name.startsWith("mdi-") ? name.substring(4) : name;
        key = key.replace('-', '_');
        if (!key.startsWith("cmd_")) key = "cmd_" + key;
        IconicsDrawable drawable = drawable(context, CommunityMaterial.INSTANCE.getIcon(key));
        if (key.equals("cmd_arrow_left") || key.equals("cmd_arrow_right")) {
            drawable.setAutoMirroredCompat(true);
        }
        return drawable;
    }

    public static void inflateMenu(Context context, MenuInflater inflater, int menuRes, Menu menu) {
        inflater.inflate(menuRes, menu);
        applyMenu(context, menu, menuRes);
    }

    /** A centered cover placeholder whose glyph is not enlarged by ImageView's centerCrop. */
    @NonNull
    public static Drawable coverPlaceholder(@NonNull Context context) {
        IconicsDrawable icon = drawable(context, "file-document-outline");
        icon.setColorList(ColorStateList.valueOf(MaterialColors.getColor(context,
                com.google.android.material.R.attr.colorOnSurfaceVariant, Color.GRAY)));
        icon.setAlpha(Math.round(255 * 0.3f));
        int sizePx = Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP,
                32, context.getResources().getDisplayMetrics()));
        return new DrawableWrapper(icon) {
            // With no intrinsic image size, ImageView gives us the viewport bounds without scaling.
            @Override public int getIntrinsicWidth() { return -1; }
            @Override public int getIntrinsicHeight() { return -1; }
            @Override public ConstantState getConstantState() { return null; }
            @Override protected void onBoundsChange(Rect bounds) {
                int size = Math.min(sizePx, Math.min(bounds.width(), bounds.height()));
                int left = bounds.left + (bounds.width() - size) / 2;
                int top = bounds.top + (bounds.height() - size) / 2;
                icon.setBounds(left, top, left + size, top + size);
            }
        };
    }

    /** Menu XML declares actions; their font icons are assigned here after inflation. */
    public static void applyMenu(Context context, Menu menu, int menuRes) {
        for (int i = 0; i < menu.size(); i++) {
            MenuItem item = menu.getItem(i);
            int id = item.getItemId();
            String name = null;
            if (id == R.id.action_filter) name = menuRes == R.menu.menu_tag_list ? "magnify" : "filter-variant";
            else if (id == R.id.action_notifications) name = "bell-outline";
            else if (id == R.id.action_search) name = "magnify";
            else if (id == R.id.action_edit_work) name = "pencil";
            else if (id == R.id.action_editor_metadata || id == R.id.action_settings) name = "cog";
            else if (id == R.id.action_editor_preview) name = "eye-outline";
            else if (id == R.id.action_toggle_theme) {
                boolean isNight = (context.getResources().getConfiguration().uiMode
                        & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
                name = isNight ? "weather-night" : "weather-sunny";
            }
            else if (id == R.id.action_more) name = "dots-vertical";
            else if (id == R.id.action_info || id == R.id.action_tag_info) name = "information";
            if (name != null) item.setIcon(drawable(context, name));
            if (item.hasSubMenu()) applyMenu(context, item.getSubMenu(), menuRes);
        }
    }

    /** MDI supplies battery levels in ten-percent steps; the adjacent text stays exact. */
    public static IconicsDrawable battery(Context context, int percent, boolean charging) {
        int level = Math.max(0, Math.min(100, percent)) / 10 * 10;
        String name;
        if (charging) name = level == 0 ? "battery-charging-outline" : "battery-charging-" + level;
        else name = level == 100 ? "battery" : level == 0 ? "battery-outline" : "battery-" + level;
        return drawable(context, name);
    }

    public static void setError(TextView field, CharSequence message) {
        IconicsDrawable icon = drawable(field.getContext(), "alert-circle");
        icon.setColorList(ColorStateList.valueOf(MaterialColors.getColor(field.getContext(),
                com.google.android.material.R.attr.colorError, Color.RED)));
        field.setError(message, message == null ? null : icon);
    }

    /** A fresh icon using the shared default size and current theme's foreground color. */
    @NonNull
    public static IconicsDrawable drawable(@NonNull Context context, @NonNull IIcon icon) {
        int color = MaterialColors.getColor(context,
                com.google.android.material.R.attr.colorOnSurface, Color.BLACK);
        return drawablePx(context, icon, context.getResources().getDimensionPixelSize(R.dimen.mdi_icon_size), color);
    }

    @NonNull
    public static IconicsDrawable drawable(@NonNull Context context, @NonNull IIcon icon,
            @IntRange(from = 1) int sizeDp, @ColorInt int color) {
        if (sizeDp < 1) throw new IllegalArgumentException(context.getString(R.string.common_icon_size_invalid));

        int sizePx = Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP,
                sizeDp, context.getResources().getDisplayMetrics()));
        return drawablePx(context, icon, sizePx, color);
    }

    private static IconicsDrawable drawablePx(Context context, IIcon icon, int sizePx, int color) {
        // Iconics keeps its initialization context globally; never give it an Activity.
        IconicsDrawable drawable = new IconicsDrawable(context.getApplicationContext(), icon);
        drawable.setSizeXPx(sizePx);
        drawable.setSizeYPx(sizePx);
        drawable.setColorList(ColorStateList.valueOf(color));
        return drawable;
    }
}
