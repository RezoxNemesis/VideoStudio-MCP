package com.rezoxnemesis.videostudio;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.TreeSet;

/** Bounded sampled authored FK/IK channel graph. Drafts edit VALUE only, at a fixed key time. */
final class EditorRigValueGraph extends View {
    interface Listener {
        void onSelect(long authoredTimeMs);
        boolean onBeginDrag(long authoredTimeMs);
        boolean onCommit(long authoredTimeMs,double value);
        void onNotice(String message);
    }
    interface Sampler { AnimationRig2D.ChannelSample at(long authoredTimeMs,long selectedKeyAtMs); }
    static final class Key {
        final long atMs;
        final double value;
        final boolean jump;
        Key(long atMs,double value,boolean jump){this.atMs=atMs;this.value=value;this.jump=jump;}
    }
    static final class Source {
        final String identity,viewportIdentity,label;
        final long clockDurationMs,offsetMs,authoredDurationMs;
        final double floor,ceiling;
        final boolean locked;
        final List<Key> keys;
        final Sampler sampler;
        Source(String identity,String viewportIdentity,String label,long clockDurationMs,long offsetMs,long authoredDurationMs,
               double floor,double ceiling,boolean locked,List<Key> keys,Sampler sampler){
            this.identity=identity;this.viewportIdentity=viewportIdentity;this.label=label;this.clockDurationMs=Math.max(1,clockDurationMs);this.offsetMs=offsetMs;this.authoredDurationMs=Math.max(1,authoredDurationMs);this.floor=floor;this.ceiling=ceiling;this.locked=locked;this.keys=new ArrayList<>(keys);this.sampler=sampler;
        }
    }
    /** Ephemeral owner viewport retained across graph commits, not Activity/process recreation. */
    static final class Viewport {String identity="";double fromMs,toMs,min,max;boolean fitted;}
    private static final int MAX_SAMPLES=256;
    private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path curve=new Path();
    private final RectF plotBounds=new RectF();
    private final Listener listener;
    private final Viewport viewport;
    private final ScaleGestureDetector pinch;
    private Source source;
    private final double[] sampleTime=new double[MAX_SAMPLES],sampleRaw=new double[MAX_SAMPLES],sampleInfluence=new double[MAX_SAMPLES];
    private final boolean[] sampleJump=new boolean[MAX_SAMPLES];
    private int sampleCount;
    private long selectedAtMs=-1,playheadMs;
    private double originalValue,draftValue;
    private float downX,downY,lastX,lastY,focusX,focusY;
    private boolean dragging,moved,navigating,detached,hasFiniteSamples,dense;
    private String sampleError="";

    EditorRigValueGraph(Context context,Viewport viewport,Listener listener){
        super(context);this.viewport=viewport;this.listener=listener;
        setContentDescription("Authored animation value graph. Drag existing keys vertically to change value; swipe empty space to pan; pinch to zoom.");
        pinch=new ScaleGestureDetector(context,new ScaleGestureDetector.SimpleOnScaleGestureListener(){
            @Override public boolean onScaleBegin(ScaleGestureDetector detector){cancelDraft();navigating=true;return source!=null;}
            @Override public boolean onScale(ScaleGestureDetector detector){zoom(detector.getScaleFactor(),detector.getScaleFactor(),detector.getFocusX(),detector.getFocusY());return true;}
        });
    }

    void setSource(Source next){
        if(source!=null&&next!=null&&source.identity.equals(next.identity)){return;}
        cancelDraft();source=next;sampleError="";sampleCount=0;selectedAtMs=-1;
        if(next==null){hasFiniteSamples=false;invalidate();return;}
        if(!next.viewportIdentity.equals(viewport.identity)){viewport.identity=next.viewportIdentity;viewport.fitted=false;}
        if(!viewport.fitted)fit();else{clampViewport();rebuildSamples(-1);invalidate();}
    }
    void setSelectedKey(long atMs){if(dragging)return;selectedAtMs=atMs;invalidate();}
    void setPlayhead(long clockTimeMs,boolean playing){playheadMs=clockTimeMs;if(playing&&dragging)cancelDraft();invalidate();}
    boolean isDragging(){return dragging;}
    void discardDraft(){cancelDraft();navigating=false;releaseParent();}
    void fit(){
        if(source==null)return;cancelDraft();viewport.fromMs=0;viewport.toMs=source.clockDurationMs;
        viewport.min=source.floor;viewport.max=source.ceiling;viewport.fitted=true;rebuildSamples(-1);
        double min=Double.POSITIVE_INFINITY,max=Double.NEGATIVE_INFINITY;
        for(int index=0;index<sampleCount;index++){double value=bounded(sampleRaw[index]);if(Double.isFinite(value)){min=Math.min(min,value);max=Math.max(max,value);}}
        for(Key key:source.keys)if(clockTime(key.atMs)>=0&&clockTime(key.atMs)<=source.clockDurationMs){min=Math.min(min,key.value);max=Math.max(max,key.value);}
        if(Double.isFinite(min)&&Double.isFinite(max)){double minimumSpan=source.floor== -36000?1:.02,span=Math.max(minimumSpan,max-min),pad=span*.15;viewport.min=min-pad;viewport.max=max+pad;if(viewport.max-viewport.min<minimumSpan){double center=(viewport.max+viewport.min)/2;viewport.min=center-minimumSpan/2;viewport.max=center+minimumSpan/2;}clampViewport();}
        invalidate();
    }
    void zoomTime(double factor){RectF plot=plot();zoom(factor,1,plot.centerX(),plot.centerY());}
    void zoomValue(double factor){RectF plot=plot();zoom(1,factor,plot.centerX(),plot.centerY());}
    private RectF plot(){if(plotBounds.width()<=0||plotBounds.height()<=0)updatePlotBounds();return plotBounds;}
    private void updatePlotBounds(){plotBounds.set(dp(53),dp(29),Math.max(dp(54),getWidth()-dp(12)),Math.max(dp(30),getHeight()-dp(29)));}
    private long clockTime(long authoredMs){return authoredMs-source.offsetMs;}
    private long authoredTime(double clockMs){double authored=clockMs+source.offsetMs;return Math.max(0,Math.min(source.authoredDurationMs,Math.round(authored)));}
    private double bounded(double value){return Math.max(source.floor,Math.min(source.ceiling,value));}
    private float x(double ms){RectF plot=plot();return(float)(plot.left+(ms-viewport.fromMs)/(viewport.toMs-viewport.fromMs)*plot.width());}
    private float y(double value){RectF plot=plot();return(float)(plot.bottom-(value-viewport.min)/(viewport.max-viewport.min)*plot.height());}
    private double timeAt(float x){RectF plot=plot();return viewport.fromMs+(x-plot.left)/plot.width()*(viewport.toMs-viewport.fromMs);}
    private double valueAt(float y){RectF plot=plot();return viewport.max-(y-plot.top)/plot.height()*(viewport.max-viewport.min);}
    private void clampViewport(){
        if(source==null)return;
        if(!Double.isFinite(viewport.fromMs))viewport.fromMs=0;if(!Double.isFinite(viewport.min))viewport.min=source.floor;
        double timeSpan=viewport.toMs-viewport.fromMs;if(!Double.isFinite(timeSpan))timeSpan=source.clockDurationMs;timeSpan=Math.max(1,Math.min(source.clockDurationMs,timeSpan));viewport.fromMs=Math.max(0,Math.min(source.clockDurationMs-timeSpan,viewport.fromMs));viewport.toMs=viewport.fromMs+timeSpan;
        double full=source.ceiling-source.floor,span=viewport.max-viewport.min;if(!Double.isFinite(span))span=full;span=Math.max(Math.min(full,1e-5),Math.min(full,span));viewport.min=Math.max(source.floor,Math.min(source.ceiling-span,viewport.min));viewport.max=viewport.min+span;
    }
    private void zoom(double timeFactor,double valueFactor,float focusX,float focusY){
        if(source==null||!Double.isFinite(timeFactor)||timeFactor<=0||!Double.isFinite(valueFactor)||valueFactor<=0)return;cancelDraft();RectF plot=plot();double at=timeAt(focusX),value=valueAt(focusY),timeSpan=(viewport.toMs-viewport.fromMs)/timeFactor,valueSpan=(viewport.max-viewport.min)/valueFactor;
        viewport.fromMs=at-timeSpan*(focusX-plot.left)/plot.width();viewport.toMs=viewport.fromMs+timeSpan;viewport.min=value-valueSpan*(plot.bottom-focusY)/plot.height();viewport.max=viewport.min+valueSpan;clampViewport();rebuildSamples(-1);invalidate();
    }
    private void pan(float dx,float dy){if(source==null)return;RectF plot=plot();double dt=-dx/plot.width()*(viewport.toMs-viewport.fromMs),dv=dy/plot.height()*(viewport.max-viewport.min);viewport.fromMs+=dt;viewport.toMs+=dt;viewport.min+=dv;viewport.max+=dv;clampViewport();rebuildSamples(-1);invalidate();}

    /** At most 256 exact shared evaluator samples; existing key discontinuities are prioritized. */
    private void rebuildSamples(long draftKey){
        sampleCount=0;hasFiniteSamples=false;sampleError="";if(source==null)return;
        try{
            TreeSet<Double> times=new TreeSet<>();times.add(viewport.fromMs);times.add(viewport.toMs);ArrayList<Key> visible=new ArrayList<>();HashSet<Double> jumps=new HashSet<>();
            for(Key key:source.keys){long time=clockTime(key.atMs);if(time>=viewport.fromMs&&time<=viewport.toMs){visible.add(key);if(key.jump)jumps.add((double)time);}}
            dense=visible.size()>60;
            if(visible.size()<=60){for(Key key:visible){times.add((double)clockTime(key.atMs));if(key.jump&&clockTime(key.atMs)>viewport.fromMs)times.add(Math.max(viewport.fromMs,clockTime(key.atMs)-1d));}}
            if(draftKey>=0){double selectedTime=clockTime(draftKey);if(selectedTime>=viewport.fromMs&&selectedTime<=viewport.toMs)times.add(selectedTime);}
            int uniform=Math.max(2,MAX_SAMPLES-times.size());for(int index=0;index<uniform;index++)times.add(viewport.fromMs+(viewport.toMs-viewport.fromMs)*index/(uniform-1));
            for(double time:times){if(sampleCount>=MAX_SAMPLES)break;AnimationRig2D.ChannelSample sample=source.sampler.at(authoredTime(time),draftKey);
                if(!Double.isFinite(sample.rawValue)||!Double.isFinite(sample.keyInfluence))throw new IllegalArgumentException("The compiled channel produced a nonfinite graph value");
                sampleTime[sampleCount]=time;sampleRaw[sampleCount]=sample.rawValue;sampleInfluence[sampleCount]=sample.keyInfluence;sampleJump[sampleCount]=!dense&&jumps.contains(time);sampleCount++;
            }hasFiniteSamples=sampleCount>0;
        }catch(Exception|OutOfMemoryError error){sampleCount=0;sampleError=error.getMessage()==null?"Graph memory is unavailable":error.getMessage();listener.onNotice(sampleError);}
    }

    @Override protected void onSizeChanged(int width,int height,int oldWidth,int oldHeight){updatePlotBounds();if(source!=null){clampViewport();rebuildSamples(dragging?selectedAtMs:-1);}}
    @Override protected void onDraw(Canvas canvas){
        canvas.drawColor(Color.rgb(10,15,24));RectF plot=plot();paint.setStyle(Paint.Style.FILL);paint.setTextSize(dp(10));paint.setColor(Color.rgb(191,210,229));
        if(source==null){canvas.drawText("Select a bone or IK channel lane for its value graph",dp(8),dp(21),paint);return;}
        String caption=source.label+(source.locked?" · locked":"")+(dragging?" · draft graph only":" · authored curve");canvas.drawText(caption,dp(7),dp(17),paint);
        paint.setStrokeWidth(dp(1));paint.setTextSize(dp(9));
        for(int index=0;index<=4;index++){float yy=plot.top+plot.height()*index/4;double value=viewport.max-(viewport.max-viewport.min)*index/4;paint.setColor(Color.rgb(38,49,65));canvas.drawLine(plot.left,yy,plot.right,yy,paint);paint.setColor(Color.rgb(149,172,196));canvas.drawText(format(value),dp(3),yy+dp(3),paint);}
        for(int index=0;index<=4;index++){double time=viewport.fromMs+(viewport.toMs-viewport.fromMs)*index/4;float xx=x(time);paint.setColor(Color.rgb(38,49,65));canvas.drawLine(xx,plot.top,xx,plot.bottom,paint);paint.setColor(Color.rgb(149,172,196));canvas.drawText(String.format(Locale.US,"%.3fs",time/1000d),Math.min(plot.right-dp(39),xx),plot.bottom+dp(14),paint);}
        canvas.save();canvas.clipRect(plot);
        if(hasFiniteSamples){curve.reset();for(int index=0;index<sampleCount;index++){double value=bounded(sampleRaw[index]+(dragging?sampleInfluence[index]*(draftValue-originalValue):0));float xx=x(sampleTime[index]),yy=y(value);if(index==0)curve.moveTo(xx,yy);else{if(sampleJump[index]){double previous=bounded(sampleRaw[index-1]+(dragging?sampleInfluence[index-1]*(draftValue-originalValue):0));curve.lineTo(xx,y(previous));}curve.lineTo(xx,yy);}}paint.setColor(dragging?Color.rgb(255,207,94):Color.rgb(64,213,233));paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(dp(1.7f));canvas.drawPath(curve,paint);paint.setStyle(Paint.Style.FILL);}
        for(Key key:source.keys){long time=clockTime(key.atMs);if(time<viewport.fromMs||time>viewport.toMs)continue;boolean selected=key.atMs==selectedAtMs;double value=dragging&&selected?draftValue:key.value;paint.setColor(selected?Color.rgb(255,207,94):Color.rgb(87,220,237));canvas.drawCircle(x(time),y(value),dp(selected?5:3.5f),paint);}
        if(playheadMs>=viewport.fromMs&&playheadMs<=viewport.toMs){paint.setColor(Color.rgb(253,88,110));paint.setStrokeWidth(dp(1));canvas.drawLine(x(playheadMs),plot.top,x(playheadMs),plot.bottom,paint);}
        canvas.restore();paint.setTextSize(dp(9));paint.setColor(Color.rgb(164,182,205));String footer=!sampleError.isEmpty()?sampleError:dragging?"Draft "+format(draftValue)+" · release saves VALUE; time stays fixed":dense?"Sampled curve · dense keys; zoom to inspect steps":source.keys.isEmpty()?"No authored channel keys · numeric Add remains available":"Drag a key vertically · empty space pans · pinch zooms";
        canvas.drawText(footer,dp(7),getHeight()-dp(3),paint);
    }

    private Key hit(float x,float y){Key hit=null;double nearest=dp(20);for(Key key:source.keys){long time=clockTime(key.atMs);if(time<viewport.fromMs||time>viewport.toMs||key.value<viewport.min||key.value>viewport.max)continue;double distance=Math.hypot(x-x(time),y-y(key.value));if(distance<nearest){nearest=distance;hit=key;}}return hit;}
    private void cancelDraft(){boolean wasDragging=dragging;dragging=false;draftValue=originalValue;if(wasDragging&&source!=null)rebuildSamples(-1);invalidate();}
    @Override protected void onDetachedFromWindow(){detached=true;cancelDraft();releaseParent();super.onDetachedFromWindow();}
    @Override protected void onAttachedToWindow(){super.onAttachedToWindow();detached=false;}
    @Override protected void onVisibilityChanged(View changedView,int visibility){super.onVisibilityChanged(changedView,visibility);if(visibility!=VISIBLE)discardDraft();}
    private void releaseParent(){if(getParent()!=null)getParent().requestDisallowInterceptTouchEvent(false);}
    @Override public boolean onTouchEvent(MotionEvent event){
        if(source==null||detached||!isEnabled()||!isShown())return false;
        if(event.getActionMasked()==MotionEvent.ACTION_CANCEL){pinch.onTouchEvent(event);cancelDraft();navigating=false;releaseParent();return true;}
        if(event.getActionMasked()==MotionEvent.ACTION_DOWN){navigating=false;moved=false;downX=lastX=event.getX();downY=lastY=event.getY();}
        pinch.onTouchEvent(event);
        if(event.getPointerCount()>1){cancelDraft();float fx=(event.getX(0)+event.getX(1))/2,fy=(event.getY(0)+event.getY(1))/2;if(navigating&&!pinch.isInProgress()&&event.getActionMasked()==MotionEvent.ACTION_MOVE)pan(fx-focusX,fy-focusY);focusX=fx;focusY=fy;navigating=true;if(getParent()!=null)getParent().requestDisallowInterceptTouchEvent(true);return true;}
        if(navigating){if(event.getActionMasked()==MotionEvent.ACTION_UP){navigating=false;releaseParent();}return true;}
        switch(event.getActionMasked()){
            case MotionEvent.ACTION_DOWN:{if(!plot().contains(event.getX(),event.getY()))return false;if(getParent()!=null)getParent().requestDisallowInterceptTouchEvent(true);Source pressedSource=source;Key key=hit(event.getX(),event.getY());if(key!=null){selectedAtMs=key.atMs;listener.onSelect(key.atMs);if(detached||source!=pressedSource||!isShown()){releaseParent();return true;}if(!source.locked){boolean admitted=listener.onBeginDrag(key.atMs);if(detached||source!=pressedSource||!isShown()){releaseParent();return true;}if(admitted){originalValue=draftValue=key.value;rebuildSamples(key.atMs);dragging=hasFiniteSamples;}}}invalidate();return true;}
            case MotionEvent.ACTION_MOVE:{if(Math.hypot(event.getX()-downX,event.getY()-downY)>dp(4))moved=true;if(dragging){double next=originalValue+(downY-event.getY())/plot().height()*(viewport.max-viewport.min);if(Double.isFinite(next))draftValue=bounded(next);invalidate();}else if(moved)pan(event.getX()-lastX,event.getY()-lastY);lastX=event.getX();lastY=event.getY();return true;}
            case MotionEvent.ACTION_UP:{if(dragging){if(Math.hypot(event.getX()-downX,event.getY()-downY)>dp(4))moved=true;double finalValue=originalValue+(downY-event.getY())/plot().height()*(viewport.max-viewport.min);if(Double.isFinite(finalValue))draftValue=bounded(finalValue);}boolean changed=dragging&&moved&&Double.isFinite(draftValue)&&draftValue!=originalValue;long time=selectedAtMs;double value=draftValue;dragging=false;releaseParent();boolean saved=!changed;if(changed&&!detached)saved=listener.onCommit(time,value);if(source!=null&&!detached){rebuildSamples(-1);if(changed&&saved){sampleError="Value saved · reopen the graph to refresh its display";setEnabled(false);}else if(!saved)sampleError="Draft not saved · committed values are shown; review the edit notice";invalidate();}performClick();return true;}
            default:return true;
        }
    }
    @Override public boolean performClick(){super.performClick();return true;}
    private static String format(double value){return String.format(Locale.US,Math.abs(value)>=1000?"%.1f":"%.3f",value);}
    private float dp(float value){return value*getResources().getDisplayMetrics().density;}
}
