package com.rezoxnemesis.videostudio;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.os.Bundle;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.OverScroller;
import org.json.JSONObject;

/** Virtualized exposure strip: visible cells only, even for a 10000-page book. */
public final class SketchFilmstripView extends View {
    public interface Listener { void select(int index); }
    private int count = 1, current;
    private float offset, downX, lastX;
    private boolean dragging;
    private Listener listener;
    private String timelineKey;
    private JSONObject exposures;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final OverScroller scroller;
    private VelocityTracker velocity;
    private final int touchSlop, minimumVelocity, maximumVelocity;

    public SketchFilmstripView(Context context) {
        super(context);
        setBackgroundColor(StudioUi.BG);
        setFocusable(true);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);
        scroller = new OverScroller(context);
        ViewConfiguration config = ViewConfiguration.get(context);
        touchSlop = config.getScaledTouchSlop();
        minimumVelocity = config.getScaledMinimumFlingVelocity();
        maximumVelocity = config.getScaledMaximumFlingVelocity();
    }

    public void setListener(Listener value) { listener = value; }

    public void setTimelineKey(String value) {
        if (java.util.Objects.equals(timelineKey, value)) return;
        timelineKey = value;
        scroller.abortAnimation();
        offset = 0;
        revealCurrent();
        invalidate();
    }

    public void setFrames(int total, int selected) {
        int nextCount = Math.max(1, Math.min(10000, total));
        int nextCurrent = Math.max(0, Math.min(nextCount - 1, selected));
        boolean changed = current != nextCurrent || count != nextCount;
        count = nextCount;
        current = nextCurrent;
        // Save acknowledgements must not pull a browsed strip back to its
        // selected frame, or cancel an in-progress fling.
        if (changed) { scroller.abortAnimation(); revealCurrent(); }
        clampOffset();
        updateDescription();
        invalidate();
    }

    public void setExposures(JSONObject value) {
        if (exposures == value) return;
        exposures = value;
        updateDescription();
        invalidate();
    }

    private float cell() { return 64 * getResources().getDisplayMetrics().density; }
    private float maximumOffset() { return Math.max(0, count * cell() - getWidth()); }
    private void clampOffset() { offset = Math.max(0, Math.min(maximumOffset(), offset)); }
    private void revealCurrent() {
        float size = cell();
        if (current * size < offset || (current + 1) * size > offset + getWidth())
            offset = current * size - (getWidth() - size) / 2f;
        clampOffset();
    }
    private void updateDescription() {
        setContentDescription("Frame / page " + (current + 1) + " of " + count + ", exposure "
                + SketchTimelineTools.exposure(exposures, current) + " frames");
    }

    @Override protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
        super.onSizeChanged(width, height, oldWidth, oldHeight);
        scroller.abortAnimation();
        if (oldWidth == 0) revealCurrent();
        clampOffset();
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float size = cell(), density = getResources().getDisplayMetrics().density;
        int start = Math.max(0, (int) (offset / size));
        int end = Math.min(count, start + (int) (getWidth() / size) + 2);
        for (int index = start; index < end; index++) {
            float x = index * size - offset;
            paint.setColor(index == current ? StudioUi.ACCENT : StudioUi.SURFACE_ALT);
            canvas.drawRoundRect(x + 3, 4, x + size - 3, getHeight() - 4, 8, 8, paint);
            paint.setColor(index == current ? StudioUi.BG : StudioUi.TEXT);
            paint.setTextSize(12 * density);
            int hold = SketchTimelineTools.exposure(exposures, index);
            canvas.drawText(String.valueOf(index + 1), x + 10 * density,
                    hold > 1 ? getHeight() * .43f : getHeight() / 2f + 5 * density, paint);
            if (hold > 1) {
                paint.setTextSize(10 * density);
                canvas.drawText("×" + hold, x + 10 * density, getHeight() * .77f, paint);
            }
        }
    }

    @Override public void computeScroll() {
        if (scroller.computeScrollOffset()) {
            offset = scroller.getCurrX();
            clampOffset();
            postInvalidateOnAnimation();
        }
    }

    @Override public boolean onTouchEvent(MotionEvent event) {
        if (!isEnabled()) return false;
        if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
            scroller.abortAnimation();
            if (velocity != null) velocity.recycle();
            velocity = VelocityTracker.obtain();
            downX = lastX = event.getX();
            dragging = false;
            if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
        }
        if (velocity != null) velocity.addMovement(event);
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN: return true;
            case MotionEvent.ACTION_MOVE:
                if (Math.abs(event.getX() - downX) > touchSlop) dragging = true;
                if (dragging) { offset += lastX - event.getX(); clampOffset(); invalidate(); }
                lastX = event.getX();
                return true;
            case MotionEvent.ACTION_UP:
                if (!dragging) {
                    int index = (int) ((event.getX() + offset) / cell());
                    if (event.getX() >= 0 && event.getX() < getWidth() && index >= 0 && index < count && listener != null)
                        listener.select(index);
                    performClick();
                } else if (velocity != null) {
                    velocity.computeCurrentVelocity(1000, maximumVelocity);
                    int speed = (int) -velocity.getXVelocity();
                    if (Math.abs(speed) >= minimumVelocity) {
                        scroller.fling((int) offset, 0, speed, 0, 0, (int) maximumOffset(), 0, 0);
                        postInvalidateOnAnimation();
                    }
                }
                finishTouch();
                return true;
            case MotionEvent.ACTION_CANCEL:
                finishTouch();
                return true;
            default: return true;
        }
    }

    private void finishTouch() {
        if (velocity != null) { velocity.recycle(); velocity = null; }
        if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(false);
    }

    @Override public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info) {
        super.onInitializeAccessibilityNodeInfo(info);
        info.setClassName("android.widget.SeekBar");
        info.setScrollable(count > 1);
        info.setRangeInfo(AccessibilityNodeInfo.RangeInfo.obtain(AccessibilityNodeInfo.RangeInfo.RANGE_TYPE_INT, 1, count, current + 1));
        if (current < count - 1) info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_FORWARD);
        if (current > 0) info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_BACKWARD);
        info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS);
    }

    @Override public boolean performAccessibilityAction(int action, Bundle args) {
        int selected = current;
        if (action == AccessibilityNodeInfo.ACTION_SCROLL_FORWARD) selected++;
        else if (action == AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD) selected--;
        else if (action == AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS.getId() && args != null) {
            float value = args.getFloat(AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE, Float.NaN);
            if (!Float.isFinite(value)) return false;
            selected = Math.round(value) - 1;
        } else return super.performAccessibilityAction(action, args);
        if (!isEnabled() || selected < 0 || selected >= count || selected == current || listener == null) return false;
        listener.select(selected);
        return true;
    }

    @Override protected void onDetachedFromWindow() {
        scroller.abortAnimation();
        finishTouch();
        super.onDetachedFromWindow();
    }
    @Override public boolean performClick() { super.performClick(); return true; }
}
