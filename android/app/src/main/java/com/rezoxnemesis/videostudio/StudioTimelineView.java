package com.rezoxnemesis.videostudio;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import java.util.ArrayList;
import java.util.Locale;

/** Time-scaled multitrack timeline. Gesture previews never mutate the persisted graph. */
public final class StudioTimelineView extends View {
    public interface Listener {
        void onSelect(String clipId);
        void onSeek(long timeMs);
        void onMove(String clipId, long startMs, String trackId);
        void onTrim(String clipId, long inMs, long outMs, long startMs);
        void onTrackControl(String trackId, String property, boolean value);
    }
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float density;
    private final ScaleGestureDetector scale;
    private final MediaThumbnailCache thumbnails;
    private ProjectStore.Project project;
    private Listener listener;
    private String selected = "";
    private long playhead;
    private double pixelsPerSecond;
    private String dragMode = "";
    private ProjectStore.Clip dragging;
    private float downX, downY;
    private long dragStart, dragIn, dragOut;
    private int dragTrack;
    private boolean moved;
    private boolean snapping = true;

    public StudioTimelineView(Context context) {
        super(context); density = getResources().getDisplayMetrics().density;
        pixelsPerSecond = 62 * density; thumbnails = new MediaThumbnailCache(context);
        setContentDescription("Multitrack editing timeline"); setFocusable(true);
        scale = new ScaleGestureDetector(context, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override public boolean onScale(ScaleGestureDetector detector) {
                setPixelsPerSecond(pixelsPerSecond * detector.getScaleFactor()); return true;
            }
        });
    }
    public void setListener(Listener listener) { this.listener = listener; }
    public void setProject(ProjectStore.Project project, String selectedClipId) {
        this.project = project; selected = selectedClipId == null ? "" : selectedClipId;
        if (project != null) project.ensureTimelineDefaults(); requestLayout(); invalidate();
    }
    public void setPixelsPerSecond(double value) {
        pixelsPerSecond = Math.max(12, Math.min(500 * density, value)); requestLayout(); invalidate();
    }
    public void setSnapping(boolean value) { snapping = value; }
    public float headerWidth() { return 110 * density; }
    public long timeAt(float x) { return Math.max(0L, Math.round((x - headerWidth()) * 1000d / pixelsPerSecond)); }
    public int trackCount() { return project == null ? 0 : project.tracks.size(); }
    public String selectedClipId() { return selected; }
    public void setPlayhead(long value) { playhead = Math.max(0, value); invalidate(); }
    public long playhead() { return playhead; }
    private float xAt(long ms) { return headerWidth() + (float)(ms * pixelsPerSecond / 1000d); }
    private float rulerHeight() { return 32 * density; }
    private float rowTop(int index) {
        float y = rulerHeight();
        for (int i = 0; project != null && i < index; i++) y += project.tracks.get(i).height * density;
        return y;
    }
    private int rowAt(float y) {
        for (int i = 0; i < trackCount(); i++) if (y >= rowTop(i) && y < rowTop(i+1)) return i;
        return -1;
    }
    @Override protected void onMeasure(int widthSpec, int heightSpec) {
        long duration = project == null ? 10000 : Math.max(10000, project.outputDurationMs());
        // Extremely long projects use a bounded view and pan/zoom rather than integer-overflow dimensions.
        int width = (int)Math.min(16_000_000d, headerWidth() + (duration + 5000d) * pixelsPerSecond / 1000d);
        int height = (int)Math.ceil(rowTop(trackCount()) + 12 * density);
        setMeasuredDimension(Math.max(width, MeasureSpec.getSize(widthSpec)), Math.max(height, (int)(100*density)));
    }
    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas); canvas.drawColor(Color.rgb(16, 20, 29));
        paint.setTypeface(android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL));
        float left = Math.max(headerWidth(), canvas.getClipBounds().left);
        float right = canvas.getClipBounds().right;
        long step = pixelsPerSecond > 100*density ? 1000 : pixelsPerSecond > 35*density ? 2000 : 5000;
        long first = (timeAt(left) / step) * step;
        paint.setTextSize(10*density);
        for (long ms = first; xAt(ms) < right; ms += step) {
            float x = xAt(ms); paint.setColor(Color.rgb(54, 63, 79));
            canvas.drawLine(x, rulerHeight()-9*density, x, getHeight(), paint);
            paint.setColor(Color.rgb(174, 184, 201)); canvas.drawText(String.format(Locale.US, "%d:%02d", ms/60000, ms/1000%60), x+4*density, 19*density, paint);
            if (Long.MAX_VALUE - ms < step) break;
        }
        if (project == null) return;
        for (int row = 0; row < project.tracks.size(); row++) {
            ProjectStore.Track track = project.tracks.get(row); float top = rowTop(row), bottom = rowTop(row+1);
            paint.setColor(row%2==0 ? Color.rgb(19,25,35) : Color.rgb(22,28,40));
            canvas.drawRect(headerWidth(), top, getWidth(), bottom, paint);
            paint.setColor(Color.rgb(37,44,59)); canvas.drawLine(0, bottom, getWidth(), bottom, paint);
            for (ProjectStore.Clip c : project.clips) {
                int targetRow = c == dragging && "move".equals(dragMode) ? dragTrack : row;
                if (c == dragging && "move".equals(dragMode)) { if (targetRow != row) continue; }
                else if (!track.id.equals(c.trackId)) continue;
                long start = c == dragging ? dragStart : c.startMs;
                long in = c == dragging ? dragIn : c.inMs, out = c == dragging ? dragOut : c.outMs;
                float x = xAt(start), end = xAt(TimelineMath.add(start, TimelineMath.duration(in, out, c.speed)));
                if (end < left || x > right) continue;
                RectF rect = new RectF(x+1, top+5*density, Math.max(x+3*density, end-1), bottom-5*density);
                int color = track.audioOnly() ? Color.rgb(43,99,83) : "image".equals(track.type) ? Color.rgb(110,75,128) : Color.rgb(54,82,130);
                paint.setColor(color); canvas.drawRoundRect(rect, 5*density, 5*density, paint);
                ProjectStore.Asset asset = project.asset(c.assetId);
                Bitmap thumb = thumbnails.get(asset, in, this::invalidate);
                if (thumb != null && !track.audioOnly() && rect.width() > 40*density) {
                    canvas.save(); canvas.clipRect(rect);
                    paint.setAlpha(115); canvas.drawBitmap(thumb, null, new RectF(rect.left,rect.top,rect.left+82*density,rect.bottom), paint);
                    paint.setAlpha(255); canvas.restore();
                }
                paint.setColor(Color.WHITE); paint.setTextSize(11*density); canvas.save(); canvas.clipRect(rect);
                String label = !c.title.isEmpty() ? c.title : asset == null ? "Missing media" : asset.name;
                canvas.drawText(label, rect.left+8*density, rect.top+19*density, paint);
                paint.setTextSize(9*density); paint.setColor(Color.rgb(212,226,244));
                canvas.drawText(String.format(Locale.US,"%.1fs · %.2f×",c.outputDurationMs()/1000d,c.speed),rect.left+8*density,rect.bottom-9*density,paint);
                if (c.keyframes.length()>0) canvas.drawText("◆",rect.right-17*density,rect.top+18*density,paint);
                canvas.restore();
                if (c.id.equals(selected)) {
                    paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(2*density); paint.setColor(Color.rgb(125,226,213));
                    canvas.drawRoundRect(rect,5*density,5*density,paint); paint.setStyle(Paint.Style.FILL);
                    canvas.drawRect(rect.left,rect.top,rect.left+4*density,rect.bottom,paint);
                    canvas.drawRect(rect.right-4*density,rect.top,rect.right,rect.bottom,paint);
                }
            }
            paint.setColor(Color.rgb(28,34,46)); canvas.drawRect(0,top,headerWidth(),bottom,paint);
            paint.setColor(Color.rgb(226,232,240)); paint.setTextSize(11*density);
            canvas.drawText(track.name, 9*density, top+20*density, paint);
            paint.setTextSize(9*density); paint.setColor(Color.rgb(162,175,195));
            canvas.drawText(track.type.replace('_',' '),9*density,top+35*density,paint);
            paint.setTextSize(10*density);
            paint.setColor(track.locked ? Color.rgb(255,194,103) : Color.rgb(142,156,178));
            canvas.drawText(track.locked ? "LOCK" : "lock",9*density,bottom-12*density,paint);
            paint.setColor(track.muted ? Color.rgb(255,194,103) : Color.rgb(142,156,178));
            canvas.drawText("M",56*density,bottom-12*density,paint);
            paint.setColor(track.visible ? Color.rgb(125,226,213) : Color.rgb(142,156,178));
            canvas.drawText("V",83*density,bottom-12*density,paint);
        }
        paint.setColor(Color.rgb(246,187,92));
        for(int i=0;i<project.markers.length();i++) {
            org.json.JSONObject marker=project.markers.optJSONObject(i); if(marker==null)continue;
            float x=xAt(marker.optLong("timeMs")); canvas.drawCircle(x,9*density,3*density,paint);
        }
        float x=xAt(playhead); paint.setColor(Color.rgb(249,118,106)); paint.setStrokeWidth(1.5f*density);
        canvas.drawLine(x,0,x,getHeight(),paint); canvas.drawCircle(x,5*density,4*density,paint);
    }
    @Override public boolean onTouchEvent(MotionEvent event) {
        scale.onTouchEvent(event);
        if (event.getPointerCount()>1 || scale.isInProgress()) {
            if(getParent()!=null)getParent().requestDisallowInterceptTouchEvent(true); return true;
        }
        float x=event.getX(), y=event.getY();
        switch(event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX=x;downY=y;moved=false;dragging=null;dragMode="seek";
                int row=rowAt(y);
                if(x<headerWidth() && row>=0) {
                    ProjectStore.Track t=project.tracks.get(row);
                    if(listener!=null) {
                        if(x<48*density)listener.onTrackControl(t.id,"locked",!t.locked);
                        else if(x<78*density)listener.onTrackControl(t.id,"muted",!t.muted);
                        else listener.onTrackControl(t.id,"visible",!t.visible);
                    }
                    return true;
                }
                if(row>=0 && project!=null) for(ProjectStore.Clip c:project.clips) {
                    if(!project.tracks.get(row).id.equals(c.trackId))continue;
                    float head=xAt(c.startMs), tail=xAt(TimelineMath.add(c.startMs,c.outputDurationMs()));
                    if(x>=head && x<=tail) {
                        selected=c.id;if(listener!=null)listener.onSelect(c.id);
                        if(project.tracks.get(row).locked)return true;
                        dragging=c;dragStart=c.startMs;dragIn=c.inMs;dragOut=c.outMs;dragTrack=row;
                        dragMode=x-head<12*density ? "head" : tail-x<12*density ? "tail" : "move";
                        if(getParent()!=null)getParent().requestDisallowInterceptTouchEvent(true);invalidate();return true;
                    }
                }
                setPlayhead(timeAt(x));if(listener!=null)listener.onSeek(playhead);return true;
            case MotionEvent.ACTION_MOVE:
                if(Math.abs(x-downX)>4*density || Math.abs(y-downY)>4*density)moved=true;
                if(dragging==null) { setPlayhead(timeAt(x));if(listener!=null)listener.onSeek(playhead);return true; }
                long delta=Math.round((x-downX)*1000d/pixelsPerSecond);
                if("move".equals(dragMode)) {
                    dragStart=Math.max(0,dragging.startMs+delta);if(snapping)dragStart=snap(dragStart,dragging.id);
                    int target=rowAt(y);if(target>=0)dragTrack=target;
                } else if("head".equals(dragMode)) {
                    long sourceDelta=Math.round(delta*dragging.speed);
                    dragIn=Math.max(0,Math.min(dragging.outMs-1,dragging.inMs+sourceDelta));
                    dragStart=Math.max(0,dragging.startMs+TimelineMath.localAt(dragIn,dragging.inMs,dragging.speed));
                } else if("tail".equals(dragMode)) {
                    long desired=dragging.outMs+Math.round(delta*dragging.speed);
                    ProjectStore.Asset asset=project.asset(dragging.assetId);
                    long limit=asset!=null && asset.durationMs>0 && !asset.mime.startsWith("image/") ? asset.durationMs : Long.MAX_VALUE;
                    dragOut=Math.max(dragging.inMs+1,Math.min(limit,desired));
                }
                invalidate();return true;
            case MotionEvent.ACTION_UP:
                if(dragging!=null && moved && listener!=null) {
                    if("move".equals(dragMode))listener.onMove(dragging.id,dragStart,project.tracks.get(dragTrack).id);
                    else listener.onTrim(dragging.id,dragIn,dragOut,dragStart);
                }
                dragging=null;dragMode="";invalidate();performClick();return true;
            case MotionEvent.ACTION_CANCEL: dragging=null;dragMode="";invalidate();return true;
        }
        return true;
    }
    private long snap(long candidate,String except) {
        ArrayList<Long> points=new ArrayList<>();points.add(0L);points.add(playhead);
        for(ProjectStore.Clip c:project.clips)if(!c.id.equals(except)){points.add(c.startMs);points.add(TimelineMath.add(c.startMs,c.outputDurationMs()));}
        for(int i=0;i<project.markers.length();i++){org.json.JSONObject m=project.markers.optJSONObject(i);if(m!=null)points.add(m.optLong("timeMs"));}
        long[] targets=new long[points.size()];for(int i=0;i<targets.length;i++)targets[i]=points.get(i);
        return TimelineMath.snap(candidate,targets,Math.round(10*density*1000/pixelsPerSecond));
    }
    @Override public boolean performClick(){super.performClick();return true;}
    @Override protected void onDetachedFromWindow(){super.onDetachedFromWindow();thumbnails.close();}
}
