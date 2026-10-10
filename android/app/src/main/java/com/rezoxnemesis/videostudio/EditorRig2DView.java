package com.rezoxnemesis.videostudio;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;

import org.json.JSONArray;
import org.json.JSONObject;

/** Actual sampled rig joints and mesh over the programme monitor. One commit per released handle. */
final class EditorRig2DView extends View {
    interface Listener {
        void onBoneSelected(String boneId);
        void onVertexSelected(int vertexIndex);
        void onBindCommit(String boneId, boolean tail, float u, float v);
        void onIkCommit(String ikId, long authoredTimeMs, float u, float v);
        default boolean onWeightCommit(String boneId,JSONArray points,float radius,float strength,String falloff){return false;}
        default void onBrushNotice(String message){}
    }
    final String projectId, clipId;
    final long revision;
    private final Listener listener;
    private final AnimationRig2D rig;
    private final MotionTimeline motion;
    private final JSONObject definition;
    private final JSONArray bones, targets, vertices;
    private final float canvasAspect, sourceAspect;
    private final boolean fit;
    private final long animationOffsetMs,animationDurationMs;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Matrix uvToView = new Matrix(), viewToUv = new Matrix();
    private final Path triangle = new Path();
    private AnimationRig2D.Frame frame;
    private long localTimeMs;
    private String selectedBone = "";
    private boolean bindMode, meshVisible, playing, manipulating;
    private int draggingBone = -1, draggingIk = -1;
    private boolean draggingTail, changed;
    private float draftU, draftV;
    private boolean weightCanvas,strokeLimited,weightScaling;
    private String strokeLimitNotice="";
    private Bitmap artwork;
    private float brushRadius=.1f,brushStrength=.25f,weightZoom=1,weightPanX,weightPanY;
    private String brushFalloff="smooth";
    private final java.util.ArrayList<float[]> weightStroke=new java.util.ArrayList<>();
    private final ScaleGestureDetector weightScale;
    private float scaleFocusX,scaleFocusY;
    private float weightFocusX,weightFocusY;
    private boolean weightMultiTouch;

    EditorRig2DView(Context context, ProjectStore.Project project, ProjectStore.Clip clip,
                   float programmeAspect, Listener listener) {
        super(context);
        this.listener=listener;projectId=project.id;revision=project.revision;clipId=clip.id;
        ProjectStore.Asset asset=project.asset(clip.assetId);
        sourceAspect=AnimationRigEdits.sourceAspect(asset);canvasAspect=programmeAspect;
        rig=AnimationRig2D.compileForClip(clip,sourceAspect);motion=new MotionTimeline(clip);
        definition=rig.definition();bones=definition.optJSONArray("bones");targets=definition.optJSONArray("ik");
        vertices=definition.optJSONObject("mesh").optJSONArray("vertices");
        fit="fit".equals(clip.effects.optString("crop","center_cover"));
        animationOffsetMs=clip.effects.optLong("animationOffsetMs",0);animationDurationMs=Math.max(1,clip.effects.optLong("animationDurationMs",clip.outputDurationMs()));
        weightScale=new ScaleGestureDetector(context,new ScaleGestureDetector.SimpleOnScaleGestureListener(){
            @Override public boolean onScaleBegin(ScaleGestureDetector detector){if(!weightCanvas)return false;cancelWeightStroke();weightScaling=true;scaleFocusX=detector.getFocusX();scaleFocusY=detector.getFocusY();return true;}
            @Override public boolean onScale(ScaleGestureDetector detector){
                float before=weightZoom,next=Math.max(1,Math.min(8,before*detector.getScaleFactor())),ratio=next/before;
                weightPanX=(weightPanX+getWidth()/2f-scaleFocusX)*ratio+detector.getFocusX()-getWidth()/2f;
                weightPanY=(weightPanY+getHeight()/2f-scaleFocusY)*ratio+detector.getFocusY()-getHeight()/2f;
                weightZoom=next;scaleFocusX=detector.getFocusX();scaleFocusY=detector.getFocusY();clampWeightPan();invalidate();return true;
            }
            @Override public void onScaleEnd(ScaleGestureDetector detector){weightScaling=false;}
        });
        setContentDescription("Articulated animation monitor. Select bones; drag bind joints or IK targets while paused.");
        setTime(0,false);
    }

    void setSelectedBone(String id) { selectedBone=id==null?"":id;invalidate(); }
    void setModes(boolean bind, boolean mesh) { bindMode=bind;meshVisible=mesh;invalidate(); }
    boolean isManipulating() { return manipulating; }

    /** A separate rest-artwork canvas; source UV never comes from posed monitor pixels. */
    void setWeightCanvas(Bitmap bitmap){weightCanvas=true;artwork=bitmap;playing=false;setContentDescription("Bind weight painting canvas. Original artwork and rest mesh; one stroke commits on release. Pinch with two fingers to zoom and pan.");invalidate();}
    void setWeightBrush(float radius,float strength,String falloff){brushRadius=radius;brushStrength=strength;brushFalloff=falloff;invalidate();}
    void fitWeightCanvas(){weightZoom=1;weightPanX=weightPanY=0;cancelWeightStroke();invalidate();}
    float[] weightViewport(){return new float[]{weightZoom,weightPanX,weightPanY};}
    void restoreWeightViewport(float[] viewport){if(viewport==null||viewport.length!=3)return;weightZoom=Math.max(1,Math.min(8,viewport[0]));weightPanX=viewport[1];weightPanY=viewport[2];invalidate();}
    @Override protected void onSizeChanged(int width,int height,int oldWidth,int oldHeight){if(weightCanvas)clampWeightPan();}

    void setTime(long outputLocalMs, boolean isPlaying) {
        if(manipulating)return;
        playing=isPlaying;
        if(frame==null||localTimeMs!=outputLocalMs){localTimeMs=outputLocalMs;frame=rig.sampleClip(outputLocalMs);}
        invalidate();
    }

    private RectF canvasBounds() {
        float width=Math.min(getWidth(),getHeight()*canvasAspect);
        float height=Math.min(getHeight(),getWidth()/canvasAspect);
        return new RectF((getWidth()-width)/2f,(getHeight()-height)/2f,(getWidth()+width)/2f,(getHeight()+height)/2f);
    }

    private void updateMapping() {
        if(weightCanvas){RectF viewport=weightBounds();uvToView.reset();uvToView.setScale(viewport.width(),viewport.height());uvToView.postTranslate(viewport.left,viewport.top);uvToView.invert(viewToUv);return;}
        RectF canvas=canvasBounds();
        float imageWidth=fit?Math.min(canvas.width(),canvas.height()*sourceAspect):Math.max(canvas.width(),canvas.height()*sourceAspect);
        float imageHeight=imageWidth/sourceAspect;
        // Rig UV deformation precedes fit/cover, then clip motion about the programme centre.
        uvToView.reset();uvToView.setScale(imageWidth,imageHeight);
        uvToView.postTranslate(canvas.centerX()-imageWidth/2f,canvas.centerY()-imageHeight/2f);
        MotionTimeline.Sample sample=motion.sample(AnimationClock.microseconds(localTimeMs));
        Matrix programmeMotion=new Matrix();programmeMotion.setScale(sample.scaleX,sample.scaleY,canvas.centerX(),canvas.centerY());
        programmeMotion.postRotate(-sample.rotation,canvas.centerX(),canvas.centerY());
        programmeMotion.postTranslate(sample.x*canvas.width()/2f,-sample.y*canvas.height()/2f);
        uvToView.postConcat(programmeMotion);uvToView.invert(viewToUv);
    }

    @Override protected void onDraw(Canvas canvas) {
        if(frame==null)return;updateMapping();
        if(weightCanvas){drawWeightCanvas(canvas);return;}
        RectF viewport=canvasBounds();canvas.save();canvas.clipRect(viewport);
        if(meshVisible)drawMesh(canvas);
        paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(dp(2));
        for(int index=0;index<bones.length();index++) {
            JSONObject bone=bones.optJSONObject(index);float[] xy=bonePoints(index);
            uvToView.mapPoints(xy);boolean selected=bone.optString("id").equals(selectedBone);
            paint.setColor(selected?Color.rgb(255,205,85):Color.rgb(60,226,242));
            canvas.drawLine(xy[0],xy[1],xy[2],xy[3],paint);
            paint.setStyle(Paint.Style.FILL);
            canvas.drawCircle(xy[0],xy[1],dp(selected?5:3.5f),paint);
            canvas.drawCircle(xy[2],xy[3],dp(selected?5:3.5f),paint);
            if(selected){paint.setTextSize(dp(10));canvas.drawText(bone.optString("name",bone.optString("id")),xy[0]+dp(7),xy[1]-dp(7),paint);}
            paint.setStyle(Paint.Style.STROKE);
        }
        if(!bindMode&&targets!=null)for(int index=0;index<targets.length();index++){
            float[] xy={frame.ikTargets[index*2],frame.ikTargets[index*2+1]};
            if(index==draggingIk){xy[0]=draftU;xy[1]=draftV;}uvToView.mapPoints(xy);
            paint.setColor(frame.ikClamped[index]?Color.rgb(255,160,74):Color.rgb(238,106,255));
            canvas.drawCircle(xy[0],xy[1],dp(8),paint);
            canvas.drawLine(xy[0]-dp(12),xy[1],xy[0]+dp(12),xy[1],paint);
            canvas.drawLine(xy[0],xy[1]-dp(12),xy[0],xy[1]+dp(12),paint);
        }
        canvas.restore();paint.setStyle(Paint.Style.FILL);paint.setColor(Color.WHITE);paint.setTextSize(dp(10));
        canvas.drawText(bindMode?"Bind joints · release to save layout":playing?"Sampled rig · pause to edit":"Rig · tap bone / mesh · drag IK target",viewport.left+dp(7),viewport.top+dp(16),paint);
    }

    private float[] bonePoints(int index) {
        JSONObject bone=bones.optJSONObject(index);
        float[] xy=bindMode?new float[]{(float)bone.optDouble("x"),(float)bone.optDouble("y"),(float)bone.optDouble("endX"),(float)bone.optDouble("endY")}
                :new float[]{frame.bonePositions[index*4],frame.bonePositions[index*4+1],frame.bonePositions[index*4+2],frame.bonePositions[index*4+3]};
        if(index==draggingBone){xy[draggingTail?2:0]=draftU;xy[draggingTail?3:1]=draftV;}return xy;
    }

    private void drawMesh(Canvas canvas) {
        // Native skinning is clipped to its original source-sized framebuffer
        // before Presentation. Weight geometry follows that same visible region;
        // bone and IK controls remain available outside it.
        float[] sourceCorners={0,0,1,0,1,1,0,1};uvToView.mapPoints(sourceCorners);
        Path sourceClip=new Path();sourceClip.moveTo(sourceCorners[0],sourceCorners[1]);
        for(int index=2;index<sourceCorners.length;index+=2)sourceClip.lineTo(sourceCorners[index],sourceCorners[index+1]);sourceClip.close();
        canvas.save();canvas.clipPath(sourceClip);
        float[] xy=frame.positions.clone();uvToView.mapPoints(xy);
        paint.setStyle(Paint.Style.FILL);
        for(int index=0;index<frame.triangles.length;index+=3){
            int a=frame.triangles[index],b=frame.triangles[index+1],c=frame.triangles[index+2];
            float weight=(weight(a)+weight(b)+weight(c))/3f;
            paint.setColor(Color.argb(35+(int)(weight*90),255,(int)(210*(1-weight)),80));
            triangle.reset();triangle.moveTo(xy[a*2],xy[a*2+1]);triangle.lineTo(xy[b*2],xy[b*2+1]);triangle.lineTo(xy[c*2],xy[c*2+1]);triangle.close();canvas.drawPath(triangle,paint);
        }
        paint.setColor(Color.argb(180,240,245,255));
        for(int index=0;index<xy.length;index+=2)canvas.drawCircle(xy[index],xy[index+1],dp(1.3f),paint);
        canvas.restore();
    }

    private float weight(int vertexIndex){
        JSONArray influences=vertices.optJSONObject(vertexIndex).optJSONArray("influences");
        if(influences!=null)for(int index=0;index<influences.length();index++){JSONObject value=influences.optJSONObject(index);if(selectedBone.equals(value.optString("boneId")))return(float)value.optDouble("weight");}return 0;
    }

    private RectF weightBounds(){float availableWidth=Math.max(1,getWidth()-dp(20)),availableHeight=Math.max(1,getHeight()-dp(20));float width=Math.min(availableWidth,availableHeight*sourceAspect)*weightZoom,height=width/sourceAspect;return new RectF((getWidth()-width)/2+weightPanX,(getHeight()-height)/2+weightPanY,(getWidth()+width)/2+weightPanX,(getHeight()+height)/2+weightPanY);}
    private void clampWeightPan(){RectF bounds=weightBounds();weightPanX=Math.max(-Math.max(0,(bounds.width()-getWidth())/2+dp(20)),Math.min(Math.max(0,(bounds.width()-getWidth())/2+dp(20)),weightPanX));weightPanY=Math.max(-Math.max(0,(bounds.height()-getHeight())/2+dp(20)),Math.min(Math.max(0,(bounds.height()-getHeight())/2+dp(20)),weightPanY));}
    private void drawWeightCanvas(Canvas canvas){
        canvas.drawColor(Color.rgb(9,14,22));RectF bounds=weightBounds();canvas.save();canvas.clipRect(bounds);
        float cell=dp(12);int firstX=(int)Math.floor(Math.max(0,bounds.left)/cell),firstY=(int)Math.floor(Math.max(0,bounds.top)/cell);
        for(int y=firstY;y*cell<Math.min(getHeight(),bounds.bottom);y++)for(int x=firstX;x*cell<Math.min(getWidth(),bounds.right);x++){paint.setColor((x+y)%2==0?Color.rgb(35,39,46):Color.rgb(24,29,36));canvas.drawRect(x*cell,y*cell,(x+1)*cell,(y+1)*cell,paint);}
        paint.setStyle(Paint.Style.FILL);paint.setFilterBitmap(true);if(artwork!=null&&!artwork.isRecycled())canvas.drawBitmap(artwork,null,bounds,paint);
        float[] xy=new float[vertices.length()*2];for(int index=0;index<vertices.length();index++){JSONObject vertex=vertices.optJSONObject(index);xy[index*2]=(float)vertex.optDouble("u");xy[index*2+1]=(float)vertex.optDouble("v");}uvToView.mapPoints(xy);
        for(int index=0;index<frame.triangles.length;index+=3){int a=frame.triangles[index],b=frame.triangles[index+1],c=frame.triangles[index+2];float influence=(weight(a)+weight(b)+weight(c))/3;
            paint.setColor(Color.argb(12+Math.round(influence*125),255,Math.round(190*(1-influence)),35));triangle.reset();triangle.moveTo(xy[a*2],xy[a*2+1]);triangle.lineTo(xy[b*2],xy[b*2+1]);triangle.lineTo(xy[c*2],xy[c*2+1]);triangle.close();canvas.drawPath(triangle,paint);
            paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(dp(.5f));paint.setColor(Color.argb(70,225,237,248));canvas.drawPath(triangle,paint);paint.setStyle(Paint.Style.FILL);
        }
        for(int index=0;index<vertices.length();index++){float influence=weight(index);paint.setColor(Color.rgb(80+Math.round(influence*175),Math.round(180*(1-influence))+30,Math.round(180*(1-influence))+20));canvas.drawCircle(xy[index*2],xy[index*2+1],dp(2),paint);}
        for(int index=0;index<bones.length();index++){JSONObject bone=bones.optJSONObject(index);if(!selectedBone.equals(bone.optString("id")))continue;float[] bind={(float)bone.optDouble("x"),(float)bone.optDouble("y"),(float)bone.optDouble("endX"),(float)bone.optDouble("endY")};uvToView.mapPoints(bind);paint.setColor(Color.CYAN);paint.setStrokeWidth(dp(2));canvas.drawLine(bind[0],bind[1],bind[2],bind[3],paint);canvas.drawCircle(bind[0],bind[1],dp(4),paint);canvas.drawCircle(bind[2],bind[3],dp(4),paint);}
        if(!weightStroke.isEmpty()){paint.setColor(brushStrength<0?Color.argb(170,98,195,255):Color.argb(170,255,221,87));paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(dp(1.5f));Path path=new Path();for(int index=0;index<weightStroke.size();index++){float[] point=weightStroke.get(index).clone();uvToView.mapPoints(point);if(index==0)path.moveTo(point[0],point[1]);else path.lineTo(point[0],point[1]);}canvas.drawPath(path,paint);float[] center=weightStroke.get(weightStroke.size()-1).clone();uvToView.mapPoints(center);canvas.drawCircle(center[0],center[1],brushRadius*bounds.height(),paint);}
        canvas.restore();paint.setStyle(Paint.Style.FILL);paint.setColor(Color.WHITE);paint.setTextSize(dp(10));canvas.drawText(artwork==null?"Loading actual artwork…":weightStroke.isEmpty()?"Bind source UV · committed weights":"Stroke draft · "+weightStroke.size()+" / 256 dabs"+(strokeLimited?" · "+strokeLimitNotice:" · release to save"),dp(8),dp(17),paint);
    }

    private float[] sourcePoint(float x,float y){float[] point={x,y};viewToUv.mapPoints(point);return point;}
    private boolean insideSource(float[] point){return point[0]>=0&&point[0]<=1&&point[1]>=0&&point[1]<=1;}
    private void addWeightDab(float[] next,boolean finish){
        if(strokeLimited)return;
        if(weightStroke.isEmpty()){weightStroke.add(next.clone());invalidate();return;}
        float spacing=Math.max(.001f,Math.min(.025f,brushRadius*.15f));float[] last=weightStroke.get(weightStroke.size()-1);double du=(next[0]-last[0])*sourceAspect,dv=next[1]-last[1],distance=Math.hypot(du,dv);
        int count=(int)Math.floor(distance/spacing);float fromU=last[0],fromV=last[1];
        for(int index=1;index<=count;index++){if(weightStroke.size()>=256){strokeLimited=true;strokeLimitNotice="dab limit";listener.onBrushNotice("Stroke reached 256 dabs. Release to save the displayed stroke; start another to continue.");break;}float progress=(float)(index*spacing/distance);weightStroke.add(new float[]{fromU+(next[0]-fromU)*progress,fromV+(next[1]-fromV)*progress});}
        last=weightStroke.get(weightStroke.size()-1);if(finish&&!strokeLimited&&weightStroke.size()<256&&Math.hypot((next[0]-last[0])*sourceAspect,next[1]-last[1])>spacing*.25f)weightStroke.add(next.clone());invalidate();
    }
    private void cancelWeightStroke(){manipulating=false;weightStroke.clear();strokeLimited=false;strokeLimitNotice="";if(getParent()!=null)getParent().requestDisallowInterceptTouchEvent(false);invalidate();}
    private boolean weightTouch(MotionEvent event){
        if(!isEnabled())return false;
        if(event.getActionMasked()==MotionEvent.ACTION_DOWN){weightMultiTouch=false;weightScaling=false;}
        if(event.getActionMasked()==MotionEvent.ACTION_CANCEL){weightScale.onTouchEvent(event);weightMultiTouch=false;weightScaling=false;cancelWeightStroke();return true;}
        weightScale.onTouchEvent(event);if(event.getPointerCount()>1){float focusX=(event.getX(0)+event.getX(1))/2f,focusY=(event.getY(0)+event.getY(1))/2f;if(weightMultiTouch&&!weightScale.isInProgress()&&event.getActionMasked()==MotionEvent.ACTION_MOVE){weightPanX+=focusX-weightFocusX;weightPanY+=focusY-weightFocusY;clampWeightPan();invalidate();}weightFocusX=focusX;weightFocusY=focusY;weightMultiTouch=true;cancelWeightStroke();if(getParent()!=null)getParent().requestDisallowInterceptTouchEvent(true);return true;}
        if(weightMultiTouch||weightScaling){if(event.getActionMasked()==MotionEvent.ACTION_UP||event.getActionMasked()==MotionEvent.ACTION_CANCEL){weightMultiTouch=false;cancelWeightStroke();}return true;}
        updateMapping();switch(event.getActionMasked()){
            case MotionEvent.ACTION_DOWN:{if(artwork==null||selectedBone.isEmpty())return false;float[] point=sourcePoint(event.getX(),event.getY());if(!insideSource(point))return false;cancelWeightStroke();manipulating=true;getParent().requestDisallowInterceptTouchEvent(true);addWeightDab(point,false);return true;}
            case MotionEvent.ACTION_MOVE:{if(!manipulating)return true;for(int index=0;index<event.getHistorySize();index++){float[] point=sourcePoint(event.getHistoricalX(index),event.getHistoricalY(index));weightMove(point);}weightMove(sourcePoint(event.getX(),event.getY()));return true;}
            case MotionEvent.ACTION_UP:{if(!manipulating)return true;float[] point=sourcePoint(event.getX(),event.getY());if(insideSource(point))addWeightDab(point,true);JSONArray dabs=new JSONArray();try{for(float[] dab:weightStroke)dabs.put(new JSONObject().put("u",dab[0]).put("v",dab[1]));}catch(Exception impossible){cancelWeightStroke();return true;}manipulating=false;listener.onWeightCommit(selectedBone,dabs,brushRadius,brushStrength,brushFalloff);cancelWeightStroke();performClick();return true;}
            case MotionEvent.ACTION_CANCEL:cancelWeightStroke();return true;
            default:return true;
        }
    }
    private void weightMove(float[] point){if(insideSource(point))addWeightDab(point,false);else if(!strokeLimited){strokeLimited=true;strokeLimitNotice="source edge";listener.onBrushNotice("Stroke stopped at the source edge. Release to save the displayed dabs; start inside the artwork to continue.");}}

    @Override public boolean onTouchEvent(MotionEvent event) {
        if(weightCanvas)return weightTouch(event);
        if(frame==null||playing)return false;
        if(!bindMode&&(localTimeMs+animationOffsetMs<0||localTimeMs+animationOffsetMs>animationDurationMs))return false;
        updateMapping();
        if(event.getActionMasked()==MotionEvent.ACTION_DOWN){
            float x=event.getX(),y=event.getY();if(!canvasBounds().contains(x,y))return false;
            draggingBone=-1;draggingIk=-1;changed=false;
            if(!bindMode&&targets!=null)for(int index=0;index<targets.length();index++){
                float[] point={frame.ikTargets[index*2],frame.ikTargets[index*2+1]};uvToView.mapPoints(point);
                if(Math.hypot(x-point[0],y-point[1])<dp(22)){draggingIk=index;draftU=frame.ikTargets[index*2];draftV=frame.ikTargets[index*2+1];break;}
            }
            if(draggingIk<0){
                float nearest=dp(22);int chosen=-1;boolean tail=false;
                for(int index=0;index<bones.length();index++){
                    float[] xy=bonePoints(index);uvToView.mapPoints(xy);
                    for(int end=0;end<2;end++){float distance=(float)Math.hypot(x-xy[end*2],y-xy[end*2+1]);if(distance<nearest){nearest=distance;chosen=index;tail=end==1;}}
                }
                if(chosen>=0){String id=bones.optJSONObject(chosen).optString("id");selectedBone=id;listener.onBoneSelected(id);
                    if(bindMode){float[] xy=bonePoints(chosen);draftU=xy[tail?2:0];draftV=xy[tail?3:1];draggingBone=chosen;draggingTail=tail;}}
                else if(meshVisible){float[] xy=frame.positions.clone();uvToView.mapPoints(xy);int vertex=-1;nearest=dp(14);
                    for(int index=0;index<vertices.length();index++){if(frame.positions[index*2]<0||frame.positions[index*2]>1||frame.positions[index*2+1]<0||frame.positions[index*2+1]>1)continue;float distance=(float)Math.hypot(x-xy[index*2],y-xy[index*2+1]);if(distance<nearest){nearest=distance;vertex=index;}}
                    if(vertex>=0){listener.onVertexSelected(vertex);performClick();return true;}
                    return false;
                }else return false;
            }
            manipulating=draggingBone>=0||draggingIk>=0;
            if(manipulating)getParent().requestDisallowInterceptTouchEvent(true);invalidate();return true;
        }
        if(event.getActionMasked()==MotionEvent.ACTION_MOVE&&manipulating){
            float[] xy={event.getX(),event.getY()};viewToUv.mapPoints(xy);
            draftU=Math.max(bindMode?0:-2,Math.min(bindMode?1:3,xy[0]));draftV=Math.max(bindMode?0:-2,Math.min(bindMode?1:3,xy[1]));changed=true;invalidate();return true;
        }
        if(event.getActionMasked()==MotionEvent.ACTION_UP){
            boolean edited=manipulating&&changed;manipulating=false;
            if(edited){if(draggingBone>=0)listener.onBindCommit(bones.optJSONObject(draggingBone).optString("id"),draggingTail,draftU,draftV);
                else if(draggingIk>=0)listener.onIkCommit(frame.ikIds[draggingIk],frame.authoredTimeMs,draftU,draftV);}
            draggingBone=-1;draggingIk=-1;changed=false;invalidate();performClick();return true;
        }
        if(event.getActionMasked()==MotionEvent.ACTION_CANCEL){manipulating=false;draggingBone=-1;draggingIk=-1;changed=false;invalidate();return true;}
        return manipulating;
    }
    @Override public boolean performClick(){super.performClick();return true;}
    private float dp(float value){return value*getResources().getDisplayMetrics().density;}
}
