package com.rezoxnemesis.videostudio;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.view.View;
import java.util.ArrayList;
import java.util.List;
import java.util.HashMap;
import java.util.Map;

/**
 * A touch-operable two-lane timeline. Touch the empty ruler to seek; drag a
 * colored music/title bar to move it, or grab its right handle to trim it.
 * The renderer only sees saved project timing after the gesture finishes.
 */
public final class StudioTracksView extends View {
    public interface SeekListener {void onSeek(long positionMs);}
    public interface ZoomListener {void onZoom(float spanFactor);}
    public interface ChangeListener {
        void onChange(String layerId,long startMs,long durationMs);
        void onSelect(String layerId);
        default void onTrimLeft(String layerId,long startMs,long durationMs){onChange(layerId,startMs,durationMs);}
    }
    private final Paint brush=new Paint(Paint.ANTI_ALIAS_FLAG);
    private final ArrayList<StudioLayer> layers=new ArrayList<>();
    private final Map<String,float[]> waveforms=new HashMap<>();
    private final Map<String,Float> waveformCoverage=new HashMap<>();
    private long durationMs=1,playheadMs=0;
    private SeekListener callback;
    private ChangeListener change;
    private String draggingId="";
    private boolean resize=false,resizeLeft=false,dragChanged=false,seeking=false;
    private long[] snapPoints=new long[0];
    private boolean snapping=true;
    /** Editor-only presentation; legacy callers and hit geometry retain their defaults. */
    private boolean quietEditorPalette;
    private boolean professionalRows;
    private ZoomListener zoomListener;
    private boolean pinching;
    private float pinchStartSpan,pinchFactor=1f;
    private static final int ROW_STEP_DP=48, ROW_ORIGIN_DP=28, ROW_BOTTOM_DP=25;
    private long minimumStart=0;
    private float downX=0;
    private long originalStart,originalDuration,previewStart,previewDuration;
    private long seekOriginalPlayhead;
    private static final int MIN_DURATION=100;

    public StudioTracksView(Context context){super(context);setMinimumHeight(dp(135));}
    public void setProfessionalRows(boolean enabled){professionalRows=enabled;requestLayout();invalidate();}
    public boolean hasProfessionalRows(){return professionalRows;}
    public int desiredHeightPx(){return professionalRows?dp(ROW_ORIGIN_DP+Math.max(1,layers.size())*ROW_STEP_DP+ROW_BOTTOM_DP):dp(135);}
    public void setOnPinchZoom(ZoomListener listener){zoomListener=listener;}
    private float topForRow(int row,StudioLayer layer){
        return professionalRows?dp(ROW_ORIGIN_DP+row*ROW_STEP_DP):
                StudioLayer.AUDIO.equals(layer.type)?dp(24):dp(76);
    }
    private static float twoPointerSpan(MotionEvent event){
        if(event.getPointerCount()<2)return 0;
        return (float)Math.hypot(event.getX(1)-event.getX(0),event.getY(1)-event.getY(0));
    }
    @Override protected void onMeasure(int widthSpec,int heightSpec){
        // HorizontalScrollView measures its child with UNSPECIFIED width.
        // Preserve the authored zoom width instead of collapsing this custom View to zero.
        int requested=getLayoutParams()==null?0:getLayoutParams().width;
        int width=Math.max(getSuggestedMinimumWidth(),requested>0?requested:dp(280));
        setMeasuredDimension(resolveSize(width,widthSpec),resolveSize(desiredHeightPx(),heightSpec));
    }
    public void setTimeline(long duration,long position,List<StudioLayer> content,
                            SeekListener callback){
        setTimeline(duration,position,content,callback,null);
    }
    public void setTimeline(long duration,long position,List<StudioLayer> content,
                            SeekListener callback,ChangeListener change){
        durationMs=Math.max(1,duration);
        playheadMs=Math.max(0,Math.min(durationMs,position));
        layers.clear();if(content!=null)layers.addAll(content);
        this.callback=callback;
        this.change=change;
        draggingId="";resize=false;pinching=false;
        invalidate();
    }
    public void setWaveform(String laneId,float[] amplitudes,float coverage){
        if(laneId==null||amplitudes==null||amplitudes.length==0)return;
        waveforms.put(laneId,amplitudes.clone());
        waveformCoverage.put(laneId,Math.max(0f,Math.min(1f,coverage)));
        invalidate();
    }
    public void setSnapping(long[] points,boolean enabled){
        snapPoints=points==null?new long[0]:points.clone();snapping=enabled;invalidate();
    }
    public void setQuietEditorPalette(boolean enabled){quietEditorPalette=enabled;invalidate();}
    public void setPlayhead(long position){playheadMs=Math.max(0,Math.min(durationMs,position));invalidate();}
    private int dp(int value){return Math.round(getResources().getDisplayMetrics().density*value);}
    private float begin(){return dp(56);}
    private float available(){return Math.max(1,getWidth()-dp(66));}
    private long delta(float pixels){
        return Math.round(pixels/available()*durationMs);
    }
    private float x(long ms){
        return begin()+available()*Math.max(0,Math.min(1,ms/(float)durationMs));
    }

    @Override protected void onDraw(Canvas canvas){
        super.onDraw(canvas);
        int height=getHeight();
        canvas.drawColor(quietEditorPalette?StudioEditorWorkspace.BG:0xff0b1222);
        float left=begin(),right=Math.max(left+4,getWidth()-dp(10));
        float audioY=dp(24),titleY=dp(76),barHeight=dp(33);
        brush.setStyle(Paint.Style.FILL);
        brush.setColor(quietEditorPalette?StudioEditorWorkspace.MUTED:0xffb4bacc);
        brush.setTextSize(dp(10));
        if(!professionalRows){
            canvas.drawText("MUSIC",dp(5),audioY+dp(20),brush);
            canvas.drawText("VISUAL",dp(3),titleY+dp(20),brush);
            brush.setColor(quietEditorPalette?0xff29382f:0xff1a2840);
            canvas.drawRoundRect(new RectF(left,audioY,right,audioY+barHeight),dp(6),dp(6),brush);
            canvas.drawRoundRect(new RectF(left,titleY,right,titleY+barHeight),dp(6),dp(6),brush);
        }else{
            // One independent visible row per existing studio layer. Its
            // project order is the compositor z-order; we do not reorder media.
            for(int i=0;i<layers.size();i++){
                StudioLayer layer=layers.get(i);
                float top=topForRow(i,layer);
                brush.setColor(quietEditorPalette?0xff29382f:0xff1a2840);
                canvas.drawRoundRect(new RectF(left,top,right,top+barHeight),dp(5),dp(5),brush);
                brush.setColor(quietEditorPalette?StudioEditorWorkspace.MUTED:0xffb4bacc);
                brush.setTextSize(dp(10));
                String code=StudioLayer.AUDIO.equals(layer.type)?"AU":
                        StudioLayer.IMAGE.equals(layer.type)?"IM":
                        StudioLayer.VIDEO.equals(layer.type)?"VD":
                        StudioLayer.SHAPE.equals(layer.type)?"SH":
                        StudioLayer.VFX.equals(layer.type)?"FX":
                        StudioLayer.GROUP.equals(layer.type)?"GR":
                        StudioLayer.CAMERA.equals(layer.type)?"CM":"TX";
                if(layer.parentId!=null&&!layer.parentId.isEmpty())code="↳"+code;
                canvas.drawText(code,dp(4),top+dp(21),brush);
            }
        }

        brush.setTextSize(dp(9));
        for(int tick=0;tick<=8;tick++){
            long at=Math.round(durationMs*tick/8.0);
            float tickX=x(at);brush.setColor(quietEditorPalette?StudioEditorWorkspace.RULE:0xff344660);
            canvas.drawLine(tickX,dp(19),tickX,height-dp(20),brush);
            brush.setColor(quietEditorPalette?StudioEditorWorkspace.MUTED:0xffb4bacc);
            String stamp=StudioEditCursor.timecode(at);
            canvas.drawText(stamp,Math.min(right-brush.measureText(stamp),tickX),dp(14),brush);
        }
        if(snapping){
            brush.setColor(quietEditorPalette?0x668ca48b:0x665bd5e6);brush.setStrokeWidth(dp(1));
            for(long boundary:snapPoints)canvas.drawLine(x(boundary),dp(19),x(boundary),height-dp(20),brush);
        }

        for(int row=0;row<layers.size();row++){
            StudioLayer lane=layers.get(row);
            long start=lane.id.equals(draggingId)?previewStart:lane.startMs;
            long length=lane.id.equals(draggingId)?previewDuration:lane.durationMs;
            float x=x(start),x2=x(start+length);
            if(x2-x<dp(3))x2=Math.min(right,x+dp(3));
            boolean audio=StudioLayer.AUDIO.equals(lane.type);
            float top=topForRow(row,lane);
            brush.setColor(quietEditorPalette?(audio?0xff607e6d:StudioLayer.IMAGE.equals(lane.type)?0xff557463:StudioLayer.VIDEO.equals(lane.type)?0xff5f7582:StudioLayer.SHAPE.equals(lane.type)?0xff506b85:0xff78856e):(audio?0xff188fcb:StudioLayer.IMAGE.equals(lane.type)?0xff279777:StudioLayer.VIDEO.equals(lane.type)?0xff397eac:StudioLayer.SHAPE.equals(lane.type)?0xff378bd1:0xffa13bdf));
            canvas.drawRoundRect(new RectF(x,top,x2,top+barHeight),dp(5),dp(5),brush);
            brush.setColor(quietEditorPalette?StudioEditorWorkspace.TEXT:0xffffffff);
            brush.setTextSize(dp(10));
            String label=audio?"♪ Music":StudioLayer.IMAGE.equals(lane.type)?"Image":StudioLayer.VIDEO.equals(lane.type)?"Video":StudioLayer.SHAPE.equals(lane.type)?"Shape · "+lane.shapeKind:StudioLayer.VFX.equals(lane.type)?"VFX · "+lane.vfxKind:StudioLayer.GROUP.equals(lane.type)?"Parent group":StudioLayer.CAMERA.equals(lane.type)?"2D Camera":(lane.text==null?"Text":lane.text);
            int maxChars=Math.max(1,(int)((x2-x-dp(10))/dp(6)));
            if(label.length()>maxChars)label=label.substring(0,maxChars);
            canvas.save();
            canvas.clipRect(x,top,x2,top+barHeight);
            canvas.drawText(label,x+dp(5),top+dp(21),brush);
            canvas.restore();
            if(audio){
                float[] peaks=waveforms.get(lane.id);
                if(peaks!=null&&peaks.length>0){
                    brush.setColor(quietEditorPalette?0xbbc6dbc5:0xbbd6eeff);
                    brush.setStrokeWidth(Math.max(1,dp(1)));
                    float mid=top+barHeight*.60f;
                    for(int sample=0;sample<peaks.length;sample++){
                        float coverage=waveformCoverage.containsKey(lane.id)
                                ?waveformCoverage.get(lane.id):1f;
                        float bx=x+dp(2)+(x2-x-dp(4))*coverage*
                                (sample+.5f)/peaks.length;
                        float size=Math.min(barHeight*.26f,
                                Math.max(0f,peaks[sample])*barHeight*.26f);
                        canvas.drawLine(bx,mid-size,bx,mid+size,brush);
                    }
                }
            }
            brush.setColor(quietEditorPalette?0x99d6e4cc:0x99ffffff);
            canvas.drawRect(x+dp(3),top+dp(6),x+dp(5),top+barHeight-dp(6),brush);
            canvas.drawRect(x2-dp(5),top+dp(6),x2-dp(3),top+barHeight-dp(6),brush);
            if(lane.id.equals(draggingId)){
                brush.setColor(quietEditorPalette?StudioEditorWorkspace.TEXT:Color.WHITE);
                brush.setStyle(Paint.Style.STROKE);
                brush.setStrokeWidth(dp(2));
                canvas.drawRoundRect(new RectF(x,top,x2,top+barHeight),dp(5),dp(5),brush);
                brush.setStyle(Paint.Style.FILL);
            }
        }
        float play=x(playheadMs);
        brush.setColor(quietEditorPalette?StudioEditorWorkspace.TEXT:0xffffffff);brush.setStrokeWidth(dp(2));
        canvas.drawLine(play,dp(10),play,height-dp(7),brush);
        brush.setColor(quietEditorPalette?StudioEditorWorkspace.SAGE:0xff22d7ff);
        canvas.drawCircle(play,dp(7),dp(4),brush);
        brush.setTextSize(dp(9));
        brush.setColor(quietEditorPalette?StudioEditorWorkspace.MUTED:0xff8d9dba);
        canvas.drawText(professionalRows?"One row per track • drag or trim • pinch timeline to zoom":
                "Drag bars to move • either edge to trim • ruler to seek",dp(8),
                height-dp(5),brush);
    }

    private StudioLayer hit(float px,float py){
        for(int i=layers.size()-1;i>=0;i--){
            StudioLayer lane=layers.get(i);
            float top=topForRow(i,lane),bottom=top+dp(33);
            if(py>=top&&py<=bottom&&px>=x(lane.startMs)-dp(5)&&
                    px<=x(lane.startMs+lane.durationMs)+dp(5))return lane;
        }
        return null;
    }

    @Override public boolean onTouchEvent(MotionEvent e){
        switch(e.getActionMasked()){
            case MotionEvent.ACTION_POINTER_DOWN:{
                if(e.getPointerCount()<2)return true;
                // Two-finger gestures are editor zoom, never a project edit.
                draggingId="";seeking=false;dragChanged=false;
                pinching=true;pinchStartSpan=Math.max(1,twoPointerSpan(e));pinchFactor=1f;
                if(getParent()!=null)getParent().requestDisallowInterceptTouchEvent(true);
                return true;
            }
            case MotionEvent.ACTION_POINTER_UP:{
                if(pinching&&e.getPointerCount()>=2)
                    pinchFactor=Math.max(.2f,Math.min(5f,twoPointerSpan(e)/pinchStartSpan));
                return true;
            }
            case MotionEvent.ACTION_DOWN:{
                StudioLayer selected=hit(e.getX(),e.getY());
                downX=e.getX();dragChanged=false;
                seeking=false;
                if(selected!=null&&change!=null&&StudioLayer.CAMERA.equals(selected.type)){
                    // The composition camera must cover the full output clock.
                    // Selecting it is allowed; dragging/trim would corrupt that
                    // invariant and is intentionally not a touchscreen action.
                    draggingId="";seeking=false;
                    change.onSelect(selected.id);
                    invalidate();return true;
                }
                if(selected!=null&&change!=null){
                    draggingId=selected.id;
                    originalStart=selected.startMs;
                    originalDuration=selected.durationMs;
                    previewStart=originalStart;
                    previewDuration=originalDuration;
                    resize=e.getX()>=x(originalStart+originalDuration)-dp(13);
                    resizeLeft=!resize&&e.getX()<=x(originalStart)+dp(13);
                    minimumStart=StudioLayer.AUDIO.equals(selected.type)?Math.max(0,originalStart-selected.sourceInMs):0;
                    if(getParent()!=null)getParent().requestDisallowInterceptTouchEvent(true);
                    invalidate();
                    return true;
                }
                draggingId="";
                seeking=true;
                seekOriginalPlayhead=playheadMs;
                // The containing HorizontalScrollView owns drags over empty space.
                // A tap still seeks on UP; a pan cancels this pending seek.
                if(getParent()!=null)getParent().requestDisallowInterceptTouchEvent(false);
                playheadMs=Math.round(Math.max(0,Math.min(1,
                        (e.getX()-begin())/available()))*durationMs);
                invalidate();
                return true;
            }
            case MotionEvent.ACTION_MOVE:{
                if(pinching){
                    if(e.getPointerCount()>=2)
                        pinchFactor=Math.max(.2f,Math.min(5f,twoPointerSpan(e)/pinchStartSpan));
                    return true;
                }
                if(!draggingId.isEmpty()){
                    long shift=delta(e.getX()-downX);
                    long tolerance=snapping?Math.max(1,Math.round(dp(9)/available()*durationMs)):0;
                    long[] points=snapping?gestureSnapPoints():null;
                    StudioLayerTouchMath.Timing p;
                    if(resize){
                        long end=StudioLayerTouchMath.snap(originalStart+originalDuration+shift,points,tolerance);
                        p=StudioLayerTouchMath.trimRight(durationMs,originalStart,originalDuration,end-originalStart-originalDuration);
                    }else if(resizeLeft){
                        long start=StudioLayerTouchMath.snap(originalStart+shift,points,tolerance);
                        p=StudioLayerTouchMath.trimLeft(durationMs,originalStart,originalDuration,start-originalStart,minimumStart);
                    }else p=StudioLayerTouchMath.moveSnapped(durationMs,originalStart,originalDuration,shift,points,tolerance);
                    previewStart=p.startMs;previewDuration=p.durationMs;
                    dragChanged=dragChanged||Math.abs(e.getX()-downX)>dp(5);
                    invalidate();
                    return true;
                }
                if(seeking){
                    playheadMs=Math.round(Math.max(0,Math.min(1,(e.getX()-begin())/available()))*durationMs);
                    invalidate();
                }
                return true;
            }
            case MotionEvent.ACTION_UP:{
                if(pinching){
                    pinching=false;
                    if(getParent()!=null)getParent().requestDisallowInterceptTouchEvent(false);
                    if(zoomListener!=null&&(pinchFactor>1.18f||pinchFactor<.84f))
                        zoomListener.onZoom(pinchFactor);
                    return true;
                }
                if(!draggingId.isEmpty()){
                    String selected=draggingId;
                    long start=previewStart,duration=previewDuration;
                    draggingId="";
                    if(change!=null){
                        if(dragChanged&&(start!=originalStart||duration!=originalDuration)){
                            if(resizeLeft)change.onTrimLeft(selected,start,duration);
                            else change.onChange(selected,start,duration);
                        }
                        else change.onSelect(selected);
                    }
                    if(getParent()!=null)getParent().requestDisallowInterceptTouchEvent(false);
                    invalidate();
                    return true;
                }
                if(seeking&&callback!=null)callback.onSeek(playheadMs);
                seeking=false;
                if(getParent()!=null)getParent().requestDisallowInterceptTouchEvent(false);
                performClick();
                return true;
            }
            case MotionEvent.ACTION_CANCEL:
                pinching=false;pinchFactor=1;
                if(seeking)playheadMs=seekOriginalPlayhead;
                draggingId="";seeking=false;
                if(getParent()!=null)getParent().requestDisallowInterceptTouchEvent(false);
                invalidate();return true;
            default:return true;
        }
    }

    private long[] gestureSnapPoints(){
        long[] points=new long[snapPoints.length+1+layers.size()*2];
        int at=0;for(long cut:snapPoints)points[at++]=cut;
        points[at++]=playheadMs;
        for(StudioLayer layer:layers)if(!layer.id.equals(draggingId)){
            points[at++]=layer.startMs;points[at++]=layer.startMs+layer.durationMs;
        }
        return java.util.Arrays.copyOf(points,at);
    }
    @Override public boolean performClick(){super.performClick();return true;}
}
