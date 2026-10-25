package com.fimtale;

import android.app.Application;
import android.graphics.Rect;
import android.view.ContextThemeWrapper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, application = com.fimtale.ResourceApplication.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class ReaderInsetsTest {
    private ViewGroup root;

    @Before public void setup() {
        ContextThemeWrapper context = new ContextThemeWrapper(RuntimeEnvironment.getApplication(), R.style.Theme_Fimtale);
        root = (ViewGroup) LayoutInflater.from(context).inflate(R.layout.activity_reader, null);
        ReaderActivity.installWindowInsets(root);
    }

    private int dp(int value) { return Math.round(value * root.getResources().getDisplayMetrics().density); }

    private void apply(Insets navigation, boolean visible, boolean landscape) {
        Insets status = Insets.of(0, dp(24), 0, 0);
        WindowInsetsCompat insets = new WindowInsetsCompat.Builder()
                .setInsets(WindowInsetsCompat.Type.statusBars(), visible ? status : Insets.NONE)
                .setInsetsIgnoringVisibility(WindowInsetsCompat.Type.statusBars(), status)
                .setInsets(WindowInsetsCompat.Type.navigationBars(), visible ? navigation : Insets.NONE)
                .setInsetsIgnoringVisibility(WindowInsetsCompat.Type.navigationBars(), navigation)
                .setVisible(WindowInsetsCompat.Type.systemBars(), visible).build();
        ViewCompat.dispatchApplyWindowInsets(root, insets);
        int width = dp(landscape ? 800 : 360), height = dp(landscape ? 360 : 800);
        root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
        root.layout(0, 0, width, height);
    }

    private Rect bounds(int id) {
        View view = root.findViewById(id);
        Rect rect = new Rect(0, 0, view.getWidth(), view.getHeight());
        root.offsetDescendantRectToMyCoords(view, rect);
        return rect;
    }

    private void assertFooterSafe(Insets navigation) {
        for (int id : new int[]{R.id.batteryLayout, R.id.tvBatteryLevel, R.id.ivBattery, R.id.tcSystemTime}) {
            Rect rect = bounds(id);
            assertTrue("Footer content must have a visible height", rect.height() > 0);
            assertTrue("Footer content overlaps the navigation area: " + rect,
                    rect.bottom <= root.getHeight() - navigation.bottom - dp(8));
            assertTrue(rect.left >= navigation.left + dp(16));
            assertTrue(rect.right <= root.getWidth() - navigation.right - dp(16));
        }
        assertEquals(bounds(R.id.tcSystemTime).exactCenterY(), bounds(R.id.batteryLayout).exactCenterY(), 1f);
    }

    @Test public void initiallyHiddenSystemBarsStillReserveSpaceForTheGestureHandle() {
        Insets gestures = Insets.of(0, 0, 0, dp(24));
        apply(gestures, false, false);
        assertEquals(View.INVISIBLE, root.findViewById(R.id.menuOverlay).getVisibility());
        assertFooterSafe(gestures);
    }

    @Test public void gestureAndButtonNavigationKeepTheFooterStableWhenBarsReappear() {
        for (int bottom : new int[]{24, 48}) {
            Insets navigation = Insets.of(0, 0, 0, dp(bottom));
            apply(navigation, false, false);
            assertFooterSafe(navigation);
            Rect before = bounds(R.id.batteryLayout);
            root.findViewById(R.id.menuOverlay).setVisibility(View.VISIBLE);
            apply(navigation, true, false);
            assertFooterSafe(navigation);
            assertEquals(before, bounds(R.id.batteryLayout));
            root.findViewById(R.id.menuOverlay).setVisibility(View.INVISIBLE);
            apply(navigation, false, false);
            assertEquals(before, bounds(R.id.batteryLayout));
        }
    }

    @Test public void rotatingNavigationToTheSideReplacesTheOldBottomInset() {
        apply(Insets.of(0, 0, 0, dp(48)), true, false);
        Insets side = Insets.of(0, 0, dp(48), 0);
        apply(side, false, true);
        assertFooterSafe(side);
        assertEquals(root.getHeight() - dp(8), bounds(R.id.batteryLayout).bottom);
    }

    @Test public void focusingACommentAndOpeningTheKeyboardKeepsTheBookViewportUnchanged() {
        Insets navigation = Insets.of(0, 0, 0, dp(24));
        apply(navigation, true, false);
        View pager = root.findViewById(R.id.viewPager);
        int before = pager.getHeight() - pager.getPaddingTop() - pager.getPaddingBottom();
        android.widget.EditText input = new android.widget.EditText(root.getContext());
        input.setId(R.id.readerCommentComposerInput); root.addView(input); input.requestFocus();
        assertTrue(input.hasFocus());

        for (int keyboardHeight : new int[]{300, 340, 0}) {
            Insets status = Insets.of(0, dp(24), 0, 0);
            WindowInsetsCompat insets = new WindowInsetsCompat.Builder()
                    .setInsets(WindowInsetsCompat.Type.statusBars(), status)
                    .setInsetsIgnoringVisibility(WindowInsetsCompat.Type.statusBars(), status)
                    .setInsets(WindowInsetsCompat.Type.navigationBars(), navigation)
                    .setInsetsIgnoringVisibility(WindowInsetsCompat.Type.navigationBars(), navigation)
                    .setVisible(WindowInsetsCompat.Type.systemBars(), true)
                    .setInsets(WindowInsetsCompat.Type.ime(), Insets.of(0, 0, 0, dp(keyboardHeight)))
                    .setVisible(WindowInsetsCompat.Type.ime(), keyboardHeight > 0).build();
            ViewCompat.dispatchApplyWindowInsets(root, insets);
            assertEquals(before, pager.getHeight() - pager.getPaddingTop() - pager.getPaddingBottom());
        }
    }
}
