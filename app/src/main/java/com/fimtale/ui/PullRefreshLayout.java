package com.fimtale.ui;

import android.animation.ValueAnimator;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.webkit.WebView;
import android.widget.FrameLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import androidx.core.view.NestedScrollingParent3;
import androidx.core.view.NestedScrollingParentHelper;
import androidx.core.view.ViewCompat;
import androidx.core.widget.NestedScrollView;
import androidx.recyclerview.widget.RecyclerView;
import com.fimtale.R;
import com.google.android.material.color.MaterialColors;

/** Pulls the content down and draws a determinate arc that becomes a spinner on release. */
public final class PullRefreshLayout extends FrameLayout implements NestedScrollingParent3 {
    public interface OnChildScrollUpCallback { boolean canChildScrollUp(PullRefreshLayout parent, View child); }
    private final NestedScrollingParentHelper nestedHelper = new NestedScrollingParentHelper(this);
    private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final ProgressBar spinner;
    private final int spinnerSize;
    private final float trigger, maximum, radius;
    private final int touchSlop;
    private Runnable refreshListener;
    private OnChildScrollUpCallback scrollCallback;
    private ValueAnimator settleAnimator;
    private float offset, pull, downX, downY;
    private int pointer = MotionEvent.INVALID_POINTER_ID;
    private boolean dragging, nested, cancelled, refreshing;

    public PullRefreshLayout(Context context) { this(context, null); }
    public PullRefreshLayout(Context context, AttributeSet attrs) {
        super(context, attrs);
        float density = getResources().getDisplayMetrics().density;
        trigger = 72 * density; maximum = 128 * density;
        spinnerSize = getResources().getDimensionPixelSize(R.dimen.pull_refresh_ring_size);
        // Match the vector's viewport, radius and stroke, including fractional screen densities.
        float ringScale = spinnerSize / 27f;
        radius = 12 * ringScale;
        touchSlop = ViewConfiguration.get(context).getScaledTouchSlop();
        ring.setStyle(Paint.Style.STROKE); ring.setStrokeCap(Paint.Cap.ROUND);
        ring.setStrokeWidth(3 * ringScale);
        ring.setColor(MaterialColors.getColor(this, com.google.android.material.R.attr.colorPrimary, 0xff6750a4));
        spinner = new ProgressBar(context, null, android.R.attr.progressBarStyle);
        spinner.setIndeterminate(true);
        spinner.setPadding(0, 0, 0, 0);
        spinner.setIndeterminateDrawable(context.getDrawable(R.drawable.pull_refresh_loading));
        spinner.setIndeterminateTintList(ColorStateList.valueOf(ring.getColor()));
        spinner.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        spinner.setVisibility(GONE);
        addView(spinner, new LayoutParams(spinnerSize, spinnerSize));
    }
    public void setOnRefreshListener(Runnable listener) { refreshListener = listener; }
    public void setOnChildScrollUpCallback(OnChildScrollUpCallback callback) { scrollCallback = callback; }
    public boolean isRefreshing() { return refreshing; }
    public void setRefreshing(boolean value) {
        if (refreshing == value) return;
        refreshing = value;
        if (value) { startSpinner(); settleTo(trigger); }
        else { stopSpinner(); settleTo(0); }
    }
    private View content() {
        for (int i = 0; i < getChildCount(); i++) if (getChildAt(i) != spinner) return getChildAt(i);
        return null;
    }
    @Override public void onViewAdded(View child) {
        super.onViewAdded(child);
        if (spinner != null && child != spinner) spinner.bringToFront();
    }
    @Override protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        super.onLayout(changed, left, top, right, bottom);
        positionSpinner();
    }
    private float indicatorCenterY() {
        return getPaddingTop() + contentTopInset() + offset / 2;
    }
    /** Floating headers keep the reveal below them as top padding on the list, which the empty-state wrappers hide one level deeper. */
    private int contentTopInset() {
        View child = content();
        if (child == null) return 0;
        int inset = scrollingPaddingTop(child);
        return inset >= 0 ? inset : child.getPaddingTop();
    }
    private static int scrollingPaddingTop(View view) {
        if (view == null) return -1;
        if (view instanceof RecyclerView || view instanceof ScrollView
                || view instanceof NestedScrollView || view instanceof WebView) return view.getPaddingTop();
        if (!(view instanceof ViewGroup)) return -1;
        ViewGroup group = (ViewGroup) view;
        for (int i = 0; i < group.getChildCount(); i++) {
            int inset = scrollingPaddingTop(group.getChildAt(i));
            if (inset >= 0) return inset;
        }
        return -1;
    }
    private void positionSpinner() {
        // Vector drawables rasterize to pixel-sized bounds. Use those same bounds for both states.
        spinner.setX(Math.round((getWidth() - spinnerSize) / 2f));
        spinner.setY(Math.round(indicatorCenterY() - spinnerSize / 2f));
    }
    private boolean canPull() {
        View child = content();
        return isEnabled() && !refreshing && child != null
                && !(scrollCallback != null ? scrollCallback.canChildScrollUp(this, child) : child.canScrollVertically(-1));
    }
    private void movePull(float distance) {
        if (settleAnimator != null) { settleAnimator.cancel(); settleAnimator = null; }
        pull = Math.max(0, distance);
        float eased = pull * .5f;
        if (eased > trigger) eased = trigger + (maximum - trigger) * (1 - (float) Math.exp(-(eased - trigger) / (maximum - trigger)));
        setOffset(eased);
    }
    private void setOffset(float value) {
        offset = value;
        if (content() != null) content().setTranslationY(value);
        positionSpinner();
        invalidate();
    }
    private void settleTo(float target) {
        if (settleAnimator != null) settleAnimator.cancel();
        settleAnimator = ValueAnimator.ofFloat(offset, target);
        settleAnimator.setDuration(200); settleAnimator.setInterpolator(new DecelerateInterpolator());
        settleAnimator.addUpdateListener(animation -> setOffset((float) animation.getAnimatedValue()));
        settleAnimator.start();
    }
    private void release(boolean abort) {
        boolean armed = pull >= trigger * 2;
        dragging = false; pull = 0;
        if (!abort && armed && canPull() && refreshListener != null) {
            setRefreshing(true);
            refreshListener.run();
        } else if (!refreshing) settleTo(0);
    }
    private void startSpinner() {
        // The framework ProgressBar owns the drawable, timing, and visibility lifecycle.
        spinner.setVisibility(VISIBLE);
        positionSpinner();
    }
    private void stopSpinner() {
        spinner.setVisibility(GONE);
    }
    @Override protected void dispatchDraw(Canvas canvas) {
        super.dispatchDraw(canvas);
        if (offset <= 0 || refreshing) return;
        float scale = Math.min(1, offset / (radius * 3));
        float cx = spinner.getX() + spinnerSize / 2f, cy = spinner.getY() + spinnerSize / 2f;
        float r = radius * scale;
        float sweep = 20 + 340 * Math.min(1, offset / trigger);
        canvas.drawArc(cx - r, cy - r, cx + r, cy + r, -90, sweep, false, ring);
    }
    @Override public boolean dispatchTouchEvent(MotionEvent event) {
        if (event.getActionMasked() == MotionEvent.ACTION_DOWN) cancelled = false;
        if (event.getActionMasked() == MotionEvent.ACTION_CANCEL) cancelled = true;
        return super.dispatchTouchEvent(event);
    }
    @Override public boolean onInterceptTouchEvent(MotionEvent event) {
        if (nested || refreshing || !isEnabled()) return false;
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                pointer = event.getPointerId(0); downX = event.getX(); downY = event.getY(); dragging = false;
                break;
            case MotionEvent.ACTION_MOVE:
                int index = event.findPointerIndex(pointer);
                if (index < 0) return false;
                float dy = event.getY(index) - downY;
                if (!canPull()) { downX = event.getX(index); downY = event.getY(index); break; }
                if (canPull() && dy > touchSlop && dy > Math.abs(event.getX(index) - downX)) {
                    dragging = true; downY += touchSlop;
                    movePull(event.getY(index) - downY);
                    if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
                }
                break;
            case MotionEvent.ACTION_POINTER_UP: switchPointer(event); break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL: pointer = MotionEvent.INVALID_POINTER_ID; break;
        }
        return dragging;
    }
    @Override public boolean onTouchEvent(MotionEvent event) {
        if (refreshing || !isEnabled()) return false;
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                pointer = event.getPointerId(0); downX = event.getX(); downY = event.getY(); return true;
            case MotionEvent.ACTION_MOVE:
                int index = event.findPointerIndex(pointer);
                if (index < 0) return false;
                float dy = event.getY(index) - downY;
                if (!dragging && canPull() && dy > touchSlop && dy > Math.abs(event.getX(index) - downX)) {
                    dragging = true; downY += touchSlop;
                }
                if (dragging) movePull(event.getY(index) - downY);
                return true;
            case MotionEvent.ACTION_POINTER_UP: switchPointer(event); return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                if (dragging) release(event.getActionMasked() == MotionEvent.ACTION_CANCEL);
                pointer = MotionEvent.INVALID_POINTER_ID; return true;
            default: return true;
        }
    }
    private void switchPointer(MotionEvent event) {
        int old = event.getActionIndex();
        if (event.getPointerId(old) != pointer) return;
        int next = old == 0 ? 1 : 0;
        if (next >= event.getPointerCount()) { pointer = MotionEvent.INVALID_POINTER_ID; return; }
        downY += event.getY(next) - event.getY(old); downX += event.getX(next) - event.getX(old);
        pointer = event.getPointerId(next);
    }
    @Override public boolean onStartNestedScroll(View child, View target, int axes, int type) {
        return type == ViewCompat.TYPE_TOUCH && (axes & ViewCompat.SCROLL_AXIS_VERTICAL) != 0 && isEnabled() && !refreshing;
    }
    @Override public void onNestedScrollAccepted(View child, View target, int axes, int type) {
        nestedHelper.onNestedScrollAccepted(child, target, axes, type);
        nested = true; pull = 0; cancelled = false;
    }
    @Override public void onNestedPreScroll(View target, int dx, int dy, int[] consumed, int type) {
        if (type == ViewCompat.TYPE_TOUCH && dy > 0 && pull > 0) {
            int amount = Math.min(dy, (int) Math.ceil(pull));
            movePull(pull - amount); consumed[1] += amount;
        }
    }
    @Override public void onNestedScroll(View target, int dxConsumed, int dyConsumed, int dxUnconsumed, int dyUnconsumed, int type, int[] consumed) {
        if (type == ViewCompat.TYPE_TOUCH && dyUnconsumed < 0 && canPull()) {
            movePull(pull - dyUnconsumed); consumed[1] += dyUnconsumed;
        }
    }
    @Override public void onStopNestedScroll(View target, int type) {
        nestedHelper.onStopNestedScroll(target, type);
        if (type == ViewCompat.TYPE_TOUCH) { nested = false; release(cancelled); }
    }
    @Override public int getNestedScrollAxes() { return nestedHelper.getNestedScrollAxes(); }
    @Override public boolean onStartNestedScroll(View child, View target, int axes) { return onStartNestedScroll(child, target, axes, ViewCompat.TYPE_TOUCH); }
    @Override public void onNestedScrollAccepted(View child, View target, int axes) { onNestedScrollAccepted(child, target, axes, ViewCompat.TYPE_TOUCH); }
    @Override public void onStopNestedScroll(View target) { onStopNestedScroll(target, ViewCompat.TYPE_TOUCH); }
    @Override public void onNestedPreScroll(View target, int dx, int dy, int[] consumed) { onNestedPreScroll(target, dx, dy, consumed, ViewCompat.TYPE_TOUCH); }
    @Override public void onNestedScroll(View target, int dx, int dy, int ux, int uy) { onNestedScroll(target, dx, dy, ux, uy, ViewCompat.TYPE_TOUCH); }
    @Override public void onNestedScroll(View target, int dx, int dy, int ux, int uy, int type) { onNestedScroll(target, dx, dy, ux, uy, type, new int[2]); }
    @Override public boolean onNestedPreFling(View target, float vx, float vy) { return pull > 0; }
    @Override public boolean onNestedFling(View target, float vx, float vy, boolean consumed) { return false; }
    @Override protected void onDetachedFromWindow() {
        if (settleAnimator != null) settleAnimator.cancel();
        stopSpinner(); refreshing = false; dragging = false; nested = false; pull = 0;
        pointer = MotionEvent.INVALID_POINTER_ID; setOffset(0);
        super.onDetachedFromWindow();
    }
}
