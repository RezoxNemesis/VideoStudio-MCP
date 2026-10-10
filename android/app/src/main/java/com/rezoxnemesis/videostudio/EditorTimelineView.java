package com.rezoxnemesis.videostudio;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.HashSet;
import java.util.HashMap;
import java.util.Map;

/** Canvas timeline with independent time/track scrolling, scrubbing, snapping and trim handles. */
final class EditorTimelineView extends View {
    interface Listener {
        void onScrub(long positionMs);
        void onSelect(String clipId, long positionMs);
        void onMove(String clipId, String trackId, long startMs);
        void onTrim(String clipId, long inMs, long outMs, long startMs);
        void onTrackAction(String trackId);
    }
    private static final int BG = Color.rgb(10, 15, 25), GRID = Color.rgb(31, 39, 54);
    private static final float RULER_DP=40;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final List<ClipRect> boxes = new ArrayList<>();
    private final Set<String> thumbnailRequests = new HashSet<>();
    private final GestureDetector gestures;
    private final ScaleGestureDetector scale;
    private final EditorThumbnailCache thumbnails;
    private final EditorWaveformCache waveforms;
    private final Set<String> waveformRequests = new HashSet<>();
    private ProjectStore.Project project;
    private List<ProjectStore.Track> tracks = new ArrayList<>();
    private final Map<String,ProjectStore.Asset> assets = new HashMap<>();
    private final Map<String,ProjectStore.Track> trackLookup = new HashMap<>();
    private final Map<String,List<ProjectStore.Clip>> clipsByTrack = new HashMap<>();
    private JSONArray markers=new JSONArray();
    private ProjectMarkers.Range programRange;
    private String insertionTrackId = "";
    private String selectedLinkGroup = "";
    private Listener listener;
    private String selectedId = "";
    private long playhead;
    private float pixelsPerSecond, scrollX, scrollY;
    private float downX, downY, lastX, lastY;
    private boolean dragging, scrolling, scrubbing, scaling, wasScaling;
    private int trimEdge;
    private ClipRect touched;
    private long dragStart, dragIn, dragOut;
    private String dragTrack;

    private static final class ClipRect {
        final ProjectStore.Clip clip;
        final RectF rect;
        ClipRect(ProjectStore.Clip clip, RectF rect) { this.clip = clip; this.rect = rect; }
    }

    EditorTimelineView(Context context, EditorThumbnailCache thumbnails, EditorWaveformCache waveforms) {
        super(context);
        this.thumbnails = thumbnails;
        this.waveforms = waveforms;
        pixelsPerSecond = dp(42);
        setFocusable(true);
        setContentDescription("Timeline. Tap a clip to select. Drag the ruler to scrub. Pinch to zoom. Long press a clip to move. Drag selected clip ends to trim.");
        gestures = new GestureDetector(context, new GestureDetector.SimpleOnGestureListener() {
            @Override public boolean onDown(MotionEvent event) { return true; }
            @Override public void onLongPress(MotionEvent event) {
                if (touched != null && trimEdge == 0 && !scaling && !scrolling && !isLocked(touched.clip)) {
                    dragging = true;
                    selectedId = touched.clip.id;
                    performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS);
                    invalidate();
                }
            }
        });
        scale = new ScaleGestureDetector(context, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override public boolean onScaleBegin(ScaleGestureDetector detector) { scaling = true; wasScaling = true; dragging = false; trimEdge = 0; return true; }
            @Override public boolean onScale(ScaleGestureDetector detector) {
                float anchor = (scrollX + detector.getFocusX() - labelWidth()) / pixelsPerSecond;
                pixelsPerSecond = Math.max(dp(.002f), Math.min(dp(260), pixelsPerSecond * detector.getScaleFactor()));
                scrollX = Math.max(0, anchor * pixelsPerSecond - detector.getFocusX() + labelWidth());
                clampScroll(); invalidate(); return true;
            }
            @Override public void onScaleEnd(ScaleGestureDetector detector) { scaling = false; }
        });
    }

    void setListener(Listener listener) { this.listener = listener; }
    void setProject(ProjectStore.Project project, String selectedId) {
        this.project = project;
        markers=project==null?new JSONArray():ProjectMarkers.list(project);
        programRange=project==null?null:ProjectMarkers.selection(project);
        tracks = project == null ? new ArrayList<>() : project.renderTracks();
        assets.clear(); trackLookup.clear(); clipsByTrack.clear();
        if (project != null) {
            for (ProjectStore.Asset asset : project.assets) assets.put(asset.id, asset);
            for (ProjectStore.Track track : tracks) { trackLookup.put(track.id, track); clipsByTrack.put(track.id, new ArrayList<>()); }
            for (ProjectStore.Clip clip : project.clips) { List<ProjectStore.Clip> lane = clipsByTrack.get(clip.trackId); if (lane != null) lane.add(clip); }
        }
        this.selectedId = selectedId == null ? "" : selectedId;
        ProjectStore.Clip selection=project==null?null:project.clip(this.selectedId);selectedLinkGroup=selection==null?"":selection.linkGroupId;
        clampScroll(); invalidate();
    }
    void setInsertionTrack(String trackId) { insertionTrackId = trackId == null ? "" : trackId; invalidate(); }
    void setPlayhead(long positionMs, boolean follow) {
        playhead = Math.max(0, positionMs);
        if (follow) {
            float x = timeX(playhead);
            if (x > getWidth() - dp(28) || x < labelWidth()) {
                scrollX = Math.max(0, playhead / 1000f * pixelsPerSecond - (getWidth() - labelWidth()) * .45f);
            }
        }
        invalidate();
    }
    void zoom(float factor) { pixelsPerSecond = Math.max(dp(.002f), Math.min(dp(260), pixelsPerSecond * factor)); clampScroll(); invalidate(); }
    void fit() {
        if (project != null && project.outputDurationMs() > 0) {
            pixelsPerSecond = Math.max(dp(.002f), Math.min(dp(260), (getWidth() - labelWidth() - dp(24)) * 1000f / project.outputDurationMs()));
            scrollX = 0; invalidate();
        }
    }

    @Override protected void onDraw(Canvas canvas) {
        canvas.drawColor(BG);
        boxes.clear();
        if (project == null) return;
        float ruler = dp(RULER_DP), row = dp(58), label = labelWidth();
        paint.setColor(GRID); paint.setStrokeWidth(dp(1));
        double desired = dp(60) / pixelsPerSecond;
        double power = Math.pow(10, Math.floor(Math.log10(desired))), units = desired / power;
        long intervalMs = Math.max(1, Math.round((units <= 1 ? 1 : units <= 2 ? 2 : units <= 5 ? 5 : 10) * power * 1000));
        long first = Math.max(0, (long)(scrollX / pixelsPerSecond * 1000) / intervalMs * intervalMs);
        canvas.save(); canvas.clipRect(label, 0, getWidth(), getHeight());
        long t = first;
        for (int tick = 0; tick < 200 && timeX(t) < getWidth(); tick++) {
            float x = timeX(t);
            paint.setColor(GRID); canvas.drawLine(x, ruler, x, getHeight(), paint);
            paint.setColor(Color.rgb(139, 152, 175)); paint.setTextSize(dp(9));
            canvas.drawText(String.format(Locale.US, "%d:%02d", t / 60000, (t / 1000) % 60), x + dp(3), dp(34), paint);
            if (t > Long.MAX_VALUE - intervalMs) break;
            t += intervalMs;
        }
        canvas.save();canvas.clipRect(label,ruler,getWidth(),getHeight());
        for (int trackIndex = 0; trackIndex < tracks.size(); trackIndex++) {
            ProjectStore.Track track = tracks.get(trackIndex);
            float top = ruler + trackIndex * row - scrollY;
            if (top + row < ruler || top > getHeight()) continue;
            paint.setColor(trackIndex % 2 == 0 ? Color.rgb(13,20,31) : Color.rgb(16,24,37));
            canvas.drawRect(label, Math.max(ruler, top), getWidth(), top + row, paint);
            for (ProjectStore.Clip clip : clipsByTrack.get(track.id)) {
                boolean moving = touched != null && touched.clip.id.equals(clip.id) && (dragging || trimEdge != 0);
                long start = moving ? dragStart : clip.startMs;
                long duration = moving && trimEdge != 0 ? Math.max(1, Math.round((dragOut - dragIn) / Math.max(Float.MIN_NORMAL, clip.effectiveSpeed()))) : clip.outputDurationMs();
                float y = top + dp(5);
                if (moving && dragging) {
                    int ti = trackIndex(dragTrack); y = ruler + ti * row - scrollY + dp(5);
                }
                RectF rect = new RectF(timeX(start), y, timeX(start + duration), y + row - dp(10));
                boxes.add(new ClipRect(clip, rect));
                if (rect.right < label || rect.left > getWidth()) continue;
                boolean selected = clip.id.equals(selectedId);
                boolean linked=!selectedLinkGroup.isEmpty()&&selectedLinkGroup.equals(clip.linkGroupId);
                ProjectStore.Asset asset = assets.get(clip.assetId);
                boolean audio = track.isAudio();
                paint.setColor(audio ? Color.rgb(26,84,76) : Color.rgb(42,63,105));
                canvas.drawRoundRect(rect, dp(5), dp(5), paint);
                canvas.save(); canvas.clipRect(rect);
                Bitmap thumb = audio ? null : thumbnails.peek(asset);
                if (!audio && thumb == null && asset != null && thumbnailRequests.add(asset.uri)) {
                    thumbnails.request(asset, () -> { thumbnailRequests.remove(asset.uri); invalidate(); });
                }
                if (thumb != null) {
                    paint.setAlpha(140);
                    float thumbWidth = Math.max(dp(44), rect.height() * thumb.getWidth() / Math.max(1f, thumb.getHeight()));
                    float firstVisible = rect.left + Math.max(0, (float)Math.floor((label - rect.left) / thumbWidth)) * thumbWidth;
                    for (float x = firstVisible; x < Math.min(rect.right, getWidth()); x += thumbWidth) {
                        canvas.drawBitmap(thumb, null, new RectF(x, rect.top, x + thumbWidth, rect.bottom), paint);
                    }
                    paint.setAlpha(255);
                    paint.setColor(Color.argb(120,0,0,0)); canvas.drawRect(rect.left, rect.top, rect.right, rect.top+dp(20), paint);
                }
                String waveformLabel="";
                if(asset!=null&&(audio||asset.hasAudio)){
                    long sourceIn=moving?dragIn:clip.inMs,sourceOut=moving?dragOut:clip.outMs;
                    long span=Math.max(1,sourceOut-sourceIn);
                    float left=Math.max(label,rect.left),right=Math.min(getWidth(),rect.right),width=Math.max(1,rect.width());
                    long visibleIn=sourceIn+Math.round((left-rect.left)/width*span),visibleOut=sourceIn+Math.round((right-rect.left)/width*span);
                    EditorWaveformCache.Waveform waveform=waveforms.peek(asset,visibleIn,visibleOut);
                    if(waveform==null){
                        String status=waveforms.status(asset,visibleIn,visibleOut);
                        if("failed".equals(status)||"unavailable".equals(status))waveformLabel=" · audio peaks unavailable";
                        else {
                            final String requestKey=asset.id+":"+(visibleIn/600000);
                            if(waveformRequests.add(requestKey))waveforms.request(asset,visibleIn,visibleOut,()->{waveformRequests.remove(requestKey);invalidate();});
                            waveformLabel="decoding".equals(status)?" · decoding audio":" · audio peaks queued";
                        }
                    }else{
                        float center=rect.top+rect.height()*.60f,amplitude=audio?rect.height()*.21f:rect.height()*.13f;
                        paint.setColor(audio?Color.rgb(127,244,210):Color.argb(210,143,255,219));paint.setStrokeWidth(dp(1));
                        for(float px=left;px<right;px+=dp(2)){
                            long from=sourceIn+Math.round((px-rect.left)/width*span),to=sourceIn+Math.round((Math.min(right,px+dp(2))-rect.left)/width*span);
                            if(to<waveform.sampledStartMs||from>waveform.sampledEndMs)continue;
                            float peak=waveform.peakBetween(from,to);float height=Math.max(0,Math.min(1,peak))*amplitude;
                            canvas.drawLine(px,center-height,px,center+height,paint);
                        }
                        waveformLabel=waveform.complete?"":" · partial peaks";
                    }
                }
                paint.setColor(Color.WHITE); paint.setTextSize(dp(10));
                canvas.drawText((audio ? "♫ " : "") + (!clip.linkGroupId.isEmpty()?"↔ ":"") + (asset == null ? "Clip" : asset.name), Math.max(rect.left, label) + dp(7), rect.top + dp(14), paint);
                paint.setColor(Color.rgb(206,220,244)); paint.setTextSize(dp(9));
                canvas.drawText(String.format(Locale.US,"%.1fs · %.2f×", duration / 1000f, clip.speed)+waveformLabel, Math.max(rect.left, label) + dp(7), rect.bottom - dp(6), paint);
                canvas.restore();
                paint.setColor(selected ? Color.rgb(41,225,239) : linked ? Color.rgb(147,128,250) : Color.argb(140,116,144,191));
                paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(selected ? dp(2) : dp(1));
                canvas.drawRoundRect(rect, dp(5), dp(5), paint); paint.setStyle(Paint.Style.FILL);
                if (selected && !track.locked) {
                    paint.setColor(Color.rgb(41,225,239));
                    canvas.drawRoundRect(new RectF(rect.left, rect.top, rect.left+dp(7), rect.bottom),dp(3),dp(3),paint);
                    canvas.drawRoundRect(new RectF(rect.right-dp(7), rect.top, rect.right, rect.bottom),dp(3),dp(3),paint);
                }
            }
        }
        canvas.restore();
        if(programRange!=null&&programRange.defined&&programRange.valid){
            float start=timeX(programRange.inMs),end=timeX(programRange.outMs);
            paint.setColor(programRange.enabled?Color.argb(85,41,225,239):Color.argb(65,140,155,180));canvas.drawRect(start,dp(19),end,ruler,paint);
            paint.setColor(programRange.enabled?Color.rgb(41,225,239):Color.rgb(140,155,180));paint.setStrokeWidth(dp(2));canvas.drawLine(start,dp(18),start,ruler,paint);canvas.drawLine(end,dp(18),end,ruler,paint);
        }
        for(int index=0;index<markers.length();index++){
            JSONObject marker=markers.optJSONObject(index);if(marker==null||!marker.optBoolean("inProgram"))continue;
            float start=timeX(marker.optLong("atMs")),end=marker.has("endMs")?timeX(marker.optLong("endMs")):start;
            if(end<label||start>getWidth())continue;
            int color;try{color=Color.parseColor(marker.optString("color",ProjectMarkers.DEFAULT_COLOR));}catch(IllegalArgumentException invalid){color=Color.rgb(246,182,84);}
            if(marker.has("endMs")){paint.setColor((color&0x00FFFFFF)|0x44000000);canvas.drawRect(start,dp(2),end,dp(17),paint);}
            paint.setColor(color);canvas.drawCircle(start,dp(10),dp(4),paint);canvas.drawLine(start,dp(13),start,ruler,paint);
            paint.setTextSize(dp(9));String name=marker.optString("name","Marker");if(name.length()>18)name=name.substring(0,17)+"…";canvas.drawText(name,Math.max(start,label)+dp(6),dp(13),paint);
        }
        canvas.restore();
        paint.setColor(Color.rgb(20,29,43)); canvas.drawRect(0,0,label,getHeight(),paint);
        paint.setTextSize(dp(9)); paint.setColor(Color.rgb(154,168,190)); canvas.drawText("TRACKS",dp(7),dp(17),paint);
        for (int i=0;i<tracks.size();i++) {
            ProjectStore.Track track = tracks.get(i);
            float y=ruler+i*row-scrollY;
            if(y+row<ruler||y>getHeight())continue;
            canvas.save();canvas.clipRect(0,ruler,label,getHeight());
            paint.setColor(track.id.equals(insertionTrackId) ? Color.rgb(41,225,239) : Color.rgb(233,238,248));paint.setTextSize(dp(10));
            String name=track.name == null?track.type:track.name;
            if(name.length()>11)name=name.substring(0,10)+"…";
            canvas.drawText(name,dp(7),y+dp(20),paint);
            paint.setTextSize(dp(9));paint.setColor(track.locked?Color.rgb(255,192,96):Color.rgb(133,149,177));
            canvas.drawText((track.locked?"🔒 ":"")+(track.muted?"M ":"")+(track.solo?"S ":"")+(track.visible?"●":"○")+"  ⋮",dp(7),y+dp(40),paint);
            canvas.restore();
        }
        canvas.save();canvas.clipRect(label,0,getWidth(),getHeight());
        float x=timeX(playhead); paint.setColor(Color.rgb(255,94,120));paint.setStrokeWidth(dp(2));
        canvas.drawLine(x,dp(23),x,getHeight(),paint);canvas.drawCircle(x,dp(23),dp(4),paint);canvas.restore();
        if(project.clips.isEmpty()){
            paint.setColor(Color.rgb(151,166,188));paint.setTextSize(dp(12));
            canvas.drawText("Import media to start editing",label+dp(12),ruler+dp(34),paint);
        }
    }

    @Override public boolean onTouchEvent(MotionEvent event) {
        scale.onTouchEvent(event); gestures.onTouchEvent(event);
        float x=event.getX(),y=event.getY();
        if(project==null)return true;
        if(event.getActionMasked()==MotionEvent.ACTION_DOWN){
            getParent().requestDisallowInterceptTouchEvent(true);
            downX=lastX=x;downY=lastY=y;scrolling=false;dragging=false;wasScaling=false;trimEdge=0;touched=null;
            scrubbing=y<dp(RULER_DP)&&x>labelWidth();
            if(!scrubbing&&x>=labelWidth()&&y>=dp(RULER_DP))for(int i=boxes.size()-1;i>=0;i--)if(boxes.get(i).rect.contains(x,y)){touched=boxes.get(i);break;}
            if(touched!=null){
                ProjectStore.Clip c=touched.clip;dragStart=c.startMs;dragIn=c.inMs;dragOut=c.outMs;dragTrack=c.trackId;
                if(c.id.equals(selectedId)&&!isLocked(c)){
                    if(Math.abs(x-touched.rect.left)<dp(16))trimEdge=-1;
                    else if(Math.abs(x-touched.rect.right)<dp(16))trimEdge=1;
                }
            }
            if(scrubbing){long markerTime=y<dp(18)?markerTimeAt(x):-1;if(markerTime>=0){playhead=markerTime;if(listener!=null)listener.onScrub(playhead);invalidate();}else scrub(x);}
            return true;
        }
        if(event.getActionMasked()==MotionEvent.ACTION_MOVE&&!scaling&&event.getPointerCount()==1){
            if(scrubbing)scrub(x);
            else if(touched!=null&&(dragging||trimEdge!=0)){
                ProjectStore.Clip c=touched.clip;long delta=Math.round((x-downX)*1000f/pixelsPerSecond);
                long minimumSpan=Math.min(c.outMs-c.inMs,Math.max(1,Math.round(33*c.effectiveSpeed())));
                if(dragging){dragStart=snap(Math.max(0,c.startMs+delta),c.id);int index=Math.max(0,Math.min(tracks.size()-1,(int)((y+scrollY-dp(RULER_DP))/dp(58))));ProjectStore.Track destination=tracks.get(index);ProjectStore.Asset source=assets.get(c.assetId);boolean audio=source!=null&&source.mime!=null&&source.mime.startsWith("audio/");boolean hasAudio=audio||(source!=null&&source.hasAudio);if(!destination.locked&&(destination.isAudio()?hasAudio:!audio))dragTrack=destination.id;}
                else if(trimEdge<0){
                    long candidate=snap(Math.max(0,c.startMs+delta),c.id);
                    long available=Math.max(-c.inMs,Math.min(c.outMs-c.inMs-minimumSpan,Math.round((candidate-c.startMs)*c.effectiveSpeed())));
                    dragIn=c.inMs+available;dragStart=Math.max(0,c.startMs+Math.round(available/Math.max(Float.MIN_NORMAL,c.effectiveSpeed())));
                }else{
                    ProjectStore.Asset asset=assets.get(c.assetId);long max=asset!=null&&asset.durationMs>0&&asset.mime!=null&&!asset.mime.startsWith("image/")?asset.durationMs:Long.MAX_VALUE;
                    long candidate=snap(Math.max(c.startMs,c.endMs()+delta),c.id);
                    dragOut=Math.max(c.inMs+minimumSpan,Math.min(max,c.outMs+Math.round((candidate-c.endMs())*c.effectiveSpeed())));
                }
                invalidate();
            }else if(Math.abs(x-downX)>dp(6)||Math.abs(y-downY)>dp(6)){
                scrolling=true;scrollX-=x-lastX;scrollY-=y-lastY;clampScroll();invalidate();
            }
            lastX=x;lastY=y;return true;
        }
        if(event.getActionMasked()==MotionEvent.ACTION_UP){
            if(listener!=null){
                if(touched!=null&&dragging)listener.onMove(touched.clip.id,dragTrack,dragStart);
                else if(touched!=null&&trimEdge!=0&&(dragIn!=touched.clip.inMs||dragOut!=touched.clip.outMs))listener.onTrim(touched.clip.id,dragIn,dragOut,dragStart);
                else if(!scrolling&&!scrubbing&&!scaling&&!wasScaling){
                    if(x<labelWidth()&&y>dp(RULER_DP)){
                        int i=(int)((y+scrollY-dp(RULER_DP))/dp(58));if(i>=0&&i<tracks.size())listener.onTrackAction(tracks.get(i).id);
                    }else if(touched!=null){selectedId=touched.clip.id;listener.onSelect(selectedId,Math.max(touched.clip.startMs,Math.min(touched.clip.endMs()-1,xTime(x))));performClick();}
                    else scrub(x);
                }
            }
            dragging=false;trimEdge=0;scrubbing=false;touched=null;invalidate();return true;
        }
        if(event.getActionMasked()==MotionEvent.ACTION_CANCEL){dragging=false;trimEdge=0;scrubbing=false;touched=null;invalidate();}
        return true;
    }
    @Override public boolean performClick(){super.performClick();return true;}
    private void scrub(float x){playhead=Math.max(0,Math.min(project.outputDurationMs(),xTime(x)));if(listener!=null)listener.onScrub(playhead);invalidate();}
    private long markerTimeAt(float x){long time=-1;float distance=dp(10);for(int index=0;index<markers.length();index++){JSONObject marker=markers.optJSONObject(index);if(marker==null||!marker.optBoolean("inProgram"))continue;float delta=Math.abs(timeX(marker.optLong("atMs"))-x);if(delta<distance){distance=delta;time=marker.optLong("atMs");}}return time;}
    private boolean isLocked(ProjectStore.Clip c){ProjectStore.Track t=trackLookup.get(c.trackId);return t!=null&&t.locked;}
    private int trackIndex(String id){for(int i=0;i<tracks.size();i++)if(tracks.get(i).id.equals(id))return i;return 0;}
    private long snap(long value,String excluded){
        long tolerance=Math.round(dp(8)*1000f/pixelsPerSecond),closest=value;boolean snapped=false;
        if(Math.abs(value-playhead)<tolerance){closest=playhead;tolerance=Math.abs(value-playhead);snapped=true;}
        for(ProjectStore.Clip c:project.clips)if(!c.id.equals(excluded))for(long edge:new long[]{c.startMs,c.endMs()})if(Math.abs(value-edge)<tolerance){closest=edge;tolerance=Math.abs(value-edge);snapped=true;}
        for(int index=0;index<markers.length();index++){JSONObject marker=markers.optJSONObject(index);if(marker==null||!marker.optBoolean("inProgram"))continue;for(long edge:new long[]{marker.optLong("atMs"),marker.optLong("endMs",marker.optLong("atMs"))})if(Math.abs(value-edge)<tolerance){closest=edge;tolerance=Math.abs(value-edge);snapped=true;}}
        if(programRange!=null&&programRange.valid)for(long edge:new long[]{programRange.inMs,programRange.outMs})if(Math.abs(value-edge)<tolerance){closest=edge;tolerance=Math.abs(value-edge);snapped=true;}
        if (snapped) return Math.max(0, closest);
        return Math.max(0,Math.round(Math.round(value / 33.333333) * 33.333333));
    }
    private float timeX(long ms){return labelWidth()+ms/1000f*pixelsPerSecond-scrollX;}
    private long xTime(float x){return Math.round((x-labelWidth()+scrollX)*1000f/pixelsPerSecond);}
    private float labelWidth(){return dp(74);}
    private float dp(float value){return value*getResources().getDisplayMetrics().density;}
    private void clampScroll(){if(project==null)return;scrollX=Math.max(0,Math.min(scrollX,Math.max(0,project.outputDurationMs()/1000f*pixelsPerSecond-(getWidth()-labelWidth())+dp(64))));scrollY=Math.max(0,Math.min(scrollY,Math.max(0,tracks.size()*dp(58)-(getHeight()-dp(RULER_DP)))));}
}
