package com.rezoxnemesis.videostudio;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.MotionEvent;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.TextView;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Saved source mesh and original sword diagnostic. Test poses never write a profile or movie. */
public final class RigPoseDiagnosticActivity extends Activity {
    private final ExecutorService previewWorker=Executors.newSingleThreadExecutor();
    private final Handler main=new Handler(Looper.getMainLooper());
    private ProjectStore projects;
    private String projectId,rigId,rigSha,sourceSha;
    private int sourceWidth,sourceHeight,selectedHandle;
    private RigPoseDiagnosticState pose;
    private RigPoseDiagnosticState.Snapshot lastValid;
    private PreviewRequest pending;
    private long generation;
    private boolean rendering,scrubUpdating;
    private volatile boolean destroyed;
    private Bitmap picture;
    private StageMapping mapping=StageMapping.IDENTITY;
    private TextView status,phaseLabel;
    private SeekBar scrub;
    private PoseCanvas drawing;

    private static final class PreviewRequest {
        final long generation;
        final RigPoseDiagnosticState.Snapshot pose;
        final JSONObject json;
        PreviewRequest(long generation,RigPoseDiagnosticState.Snapshot pose,JSONObject json){this.generation=generation;this.pose=pose;this.json=json;}
    }
    private static final class StageMapping {
        static final StageMapping IDENTITY=new StageMapping(1,1,0,0);
        final float scaleX,scaleY,offsetX,offsetY;
        StageMapping(float sx,float sy,float ox,float oy){scaleX=sx;scaleY=sy;offsetX=ox;offsetY=oy;}
        static StageMapping read(JSONObject result)throws Exception{
            JSONObject stage=result.getJSONObject("stage"),uv=stage.optJSONObject("originalToStageUv");if(uv==null)uv=stage;
            float sx=(float)uv.getDouble("scaleX"),sy=(float)uv.getDouble("scaleY"),ox=(float)uv.getDouble("offsetX"),oy=(float)uv.getDouble("offsetY");
            if(!Float.isFinite(sx)||!Float.isFinite(sy)||!Float.isFinite(ox)||!Float.isFinite(oy)||sx<=0||sy<=0||ox<0||oy<0||ox+sx>1.00001f||oy+sy>1.00001f)throw new IllegalArgumentException("Native stage mapping is invalid");
            return new StageMapping(sx,sy,ox,oy);
        }
    }

    @Override protected void onCreate(Bundle saved){
        super.onCreate(saved);getWindow().setStatusBarColor(0xff090d16);getWindow().setNavigationBarColor(0xff090d16);
        projects=new ProjectStore(this);projectId=getIntent().getStringExtra("projectId");rigId=getIntent().getStringExtra("rigId");rigSha=getIntent().getStringExtra("expectedRigSha256");
        if(projectId==null||rigId==null){finish();return;}setupUi();
        previewWorker.execute(()->{Rig2DProfile profile=null;JSONObject source=null;String problem=null;
            try{
                Rig2DProjectStore rigs=new Rig2DProjectStore(getApplicationContext(),projects);
                if(rigSha==null||rigSha.isEmpty())rigSha=rigs.captureBinding(projectId,rigId).getString("sha256");
                profile=rigs.loadVerified(projectId,rigId,rigSha);
                if(profile.sourceSha256==null||profile.swordHilt==null||profile.swordTip==null||profile.swordOutline.length<3)throw new IllegalArgumentException("Save source-bound actor marks and the original sword hilt, tip and mask first");
                source=NativeSourceArtworkInspector.inspect(getApplicationContext(),projects,new JSONObject().put("projectId",projectId).put("assetId",profile.sourceAssetId).put("expectedSourceSha256",profile.sourceSha256).put("maxDimension",720));
            }catch(Exception|OutOfMemoryError error){problem=message(error);}
            final Rig2DProfile accepted=profile;final JSONObject inspected=source;final String failure=problem;
            main.post(()->{if(destroyed)return;if(failure!=null){status.setText("Pose diagnostic unavailable: "+failure+". Source marks remain saved.");return;}
                sourceSha=inspected.optString("sourceSha256");sourceWidth=inspected.optInt("orientedWidth");sourceHeight=inspected.optInt("orientedHeight");
                pose=new RigPoseDiagnosticState(accepted.joints,accepted.swordTip);lastValid=pose.snapshot();updatePhase();requestPreview();
            });
        });
    }

    private int dp(float value){return Math.round(value*getResources().getDisplayMetrics().density);}
    private TextView label(String text,int size){TextView view=new TextView(this);view.setText(text);view.setTextColor(Color.WHITE);view.setTextSize(size);view.setPadding(dp(8),dp(4),dp(8),dp(4));return view;}
    private Button button(String text,Runnable action){Button button=new Button(this);button.setText(text);button.setTextSize(11);button.setAllCaps(false);button.setOnClickListener(view->action.run());return button;}
    private void setupUi(){
        LinearLayout root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);root.setBackgroundColor(0xff090d16);
        root.addView(label("Source-rig pose diagnostic",19));
        root.addView(label("Saved masked artwork + original sword. Drag test handles; scrub source → test. This checks deformation only. Contacts, physical action and cinematic acceptance remain unverified.",11));
        root.addView(label("Profile · "+rigId,10));status=label("Reading saved profile and verified original…",11);status.setTextColor(0xff58d9f2);root.addView(status);
        Spinner picker=new Spinner(this);String[] names=new String[17];System.arraycopy(Rig2DProfile.JOINTS,0,names,0,16);names[16]="Original sword tip";picker.setAdapter(new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,names));
        picker.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener(){public void onItemSelected(android.widget.AdapterView<?> parent,View view,int index,long id){selectedHandle=index;if(drawing!=null)drawing.invalidate();}public void onNothingSelected(android.widget.AdapterView<?> parent){}});root.addView(picker,new LinearLayout.LayoutParams(-1,dp(38)));
        drawing=new PoseCanvas();root.addView(drawing,new LinearLayout.LayoutParams(-1,0,1));phaseLabel=label("Test pose · 100%",11);root.addView(phaseLabel);
        scrub=new SeekBar(this);scrub.setMax(100);scrub.setProgress(100);scrub.setContentDescription("Interpolate saved source rest pose at zero to the separate test pose at one hundred");
        scrub.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){
            public void onProgressChanged(SeekBar bar,int progress,boolean fromUser){if(pose==null||scrubUpdating)return;drawing.cancelGesture();pose.setPhase(progress/100f);updatePhase();if(fromUser)requestPreview();}
            public void onStartTrackingTouch(SeekBar bar){}
            public void onStopTrackingTouch(SeekBar bar){}
        });root.addView(scrub,new LinearLayout.LayoutParams(-1,dp(40)));
        LinearLayout actions=new LinearLayout(this);
        actions.addView(button("Reset test",()->{if(pose==null)return;drawing.cancelGesture();pose.reset();updatePhase();requestPreview();}),new LinearLayout.LayoutParams(0,dp(44),1));
        actions.addView(button("Cancel preview",this::cancelPreview),new LinearLayout.LayoutParams(0,dp(44),1));
        actions.addView(button("Done",this::finish),new LinearLayout.LayoutParams(0,dp(44),1));root.addView(actions);setContentView(root);
    }

    private void updatePhase(){if(pose==null)return;scrubUpdating=true;scrub.setProgress(Math.round(pose.phase()*100));scrubUpdating=false;phaseLabel.setText("Source 0% → test 100% · "+Math.round(pose.phase()*100)+"% · source marks are unchanged");drawing.invalidate();}
    private void requestPreview(){
        if(destroyed||pose==null)return;
        try{
            JSONArray joints=new JSONArray();for(float[] joint:pose.targetJoints())joints.put(new JSONArray().put(joint[0]).put(joint[1]));float[] tip=pose.targetSwordTip();
            JSONObject request=new JSONObject().put("projectId",projectId).put("rigId",rigId).put("expectedRigSha256",rigSha).put("expectedSourceSha256",sourceSha).put("maxDimension",720).put("targetJoints16",joints).put("swordTip",new JSONArray().put(tip[0]).put(tip[1]));
            pending=new PreviewRequest(++generation,pose.snapshot(),request);status.setText("Rendering saved masked source + original sword…");renderNext();
        }catch(Exception error){pose.restore(lastValid);updatePhase();status.setText("Diagnostic request rejected: "+message(error)+(picture==null?". Saved source pose remains unchanged.":". Last valid pose retained."));}
    }
    /** One native render in flight and one replaceable latest request; scrubbing cannot grow a queue. */
    private void renderNext(){
        if(destroyed||rendering||pending==null)return;PreviewRequest request=pending;pending=null;rendering=true;
        previewWorker.execute(()->{
            Bitmap decoded=null;StageMapping acceptedMapping=null;String failure=null;
            try{if(destroyed)return;JSONObject result=NativeSourceRigPreview.render(getApplicationContext(),projects,request.json);decoded=RigWorkbenchImages.decode(result.getJSONObject("rigPreviewImage"),720);acceptedMapping=StageMapping.read(result);}
            catch(Exception|OutOfMemoryError error){failure=message(error);}
            final Bitmap ready=decoded;final StageMapping stageMapping=acceptedMapping;final String problem=failure;
            boolean posted=main.post(()->{
                rendering=false;
                if(destroyed||request.generation!=generation){recycle(ready);if(!destroyed)renderNext();return;}
                if(problem!=null){recycle(ready);pose.restore(lastValid);updatePhase();status.setText("Pose rejected: "+problem+(picture==null?". Saved source pose remains unchanged.":". Last valid pose and image retained."));}
                else{Bitmap previous=picture;picture=ready;mapping=stageMapping;lastValid=request.pose;recycle(previous);drawing.invalidate();status.setText("Masked-source pose diagnostic · no contact, film or cinematic approval");}
                renderNext();
            });if(!posted)recycle(ready);
        });
    }
    private void cancelPreview(){if(pose==null)return;drawing.cancelGesture();generation++;pending=null;pose.restore(lastValid);updatePhase();status.setText(picture==null?"Preview cancelled. Reset test to retry; source marks are unchanged.":"Preview cancelled. Last valid pose and image retained; source marks are unchanged.");}
    private static String message(Throwable error){return error.getMessage()==null?"native diagnostic unavailable":error.getMessage();}
    private static void recycle(Bitmap bitmap){if(bitmap!=null&&!bitmap.isRecycled())bitmap.recycle();}
    @Override protected void onDestroy(){destroyed=true;generation++;pending=null;if(drawing!=null)drawing.cancelGesture();recycle(picture);picture=null;previewWorker.shutdownNow();super.onDestroy();}

    private final class PoseCanvas extends View {
        private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG|Paint.FILTER_BITMAP_FLAG);
        private final RectF stage=new RectF();
        private RigPoseDiagnosticState.Snapshot before;
        private boolean editing;
        PoseCanvas(){super(RigPoseDiagnosticActivity.this);setContentDescription("Masked original actor pose diagnostic. Drag a named test joint or original sword tip. Test poses never save source marks.");}
        private void geometry(){if(sourceWidth<1||sourceHeight<1)return;int bitmapWidth=picture==null?sourceWidth:picture.getWidth(),bitmapHeight=picture==null?sourceHeight:picture.getHeight();float scale=Math.min(getWidth()/(float)bitmapWidth,getHeight()/(float)bitmapHeight),width=bitmapWidth*scale,height=bitmapHeight*scale;stage.set((getWidth()-width)/2,(getHeight()-height)/2,(getWidth()+width)/2,(getHeight()+height)/2);}
        private float x(float u){return stage.left+(mapping.offsetX+u*mapping.scaleX)*stage.width();}private float y(float v){return stage.top+(mapping.offsetY+v*mapping.scaleY)*stage.height();}
        private float u(float px){return((px-stage.left)/stage.width()-mapping.offsetX)/mapping.scaleX;}private float v(float py){return((py-stage.top)/stage.height()-mapping.offsetY)/mapping.scaleY;}
        private boolean originalContains(float px,float py){return stage.contains(px,py)&&u(px)>=0&&u(px)<=1&&v(py)>=0&&v(py)<=1;}
        @Override protected void onDraw(Canvas canvas){canvas.drawColor(0xff101927);geometry();if(picture!=null&&!picture.isRecycled())canvas.drawBitmap(picture,null,stage,paint);if(pose==null)return;
            float[][] points=pose.targetJoints();paint.setColor(0xff58d9f2);paint.setStrokeWidth(dp(1.4f));for(int[] bone:Rig2DProfile.BONES)canvas.drawLine(x(points[bone[0]][0]),y(points[bone[0]][1]),x(points[bone[1]][0]),y(points[bone[1]][1]),paint);
            paint.setTextSize(dp(9));for(int i=0;i<16;i++){paint.setColor(i==selectedHandle?0xffffc66b:0xff58d9f2);canvas.drawCircle(x(points[i][0]),y(points[i][1]),dp(i==selectedHandle?6:3.5f),paint);canvas.drawText(String.valueOf(i+1),x(points[i][0])+dp(6),y(points[i][1])-dp(4),paint);}
            float[] tip=pose.targetSwordTip();paint.setColor(0xffffc66b);canvas.drawCircle(x(tip[0]),y(tip[1]),dp(selectedHandle==16?7:4),paint);canvas.drawText("TIP",x(tip[0])+dp(8),y(tip[1]),paint);
        }
        private int hit(float px,float py){float[][] joints=pose.targetJoints();int selected=-1;double closest=dp(26);for(int i=0;i<17;i++){float[] point=i==16?pose.targetSwordTip():joints[i];double distance=Math.hypot(px-x(point[0]),py-y(point[1]));if(distance<closest){closest=distance;selected=i;}}return selected;}
        private void move(float px,float py){float u=Math.max(0,Math.min(1,u(px))),v=Math.max(0,Math.min(1,v(py)));if(selectedHandle==16)pose.moveSwordTip(u,v);else pose.moveJoint(selectedHandle,u,v);invalidate();}
        void cancelGesture(){if(editing&&pose!=null)pose.restore(before);before=null;editing=false;if(getParent()!=null)getParent().requestDisallowInterceptTouchEvent(false);invalidate();}
        @Override public boolean onTouchEvent(MotionEvent event){if(pose==null||picture==null||destroyed)return false;geometry();
            if(event.getActionMasked()==MotionEvent.ACTION_CANCEL||event.getPointerCount()>1){cancelGesture();pose.restore(lastValid);updatePhase();return true;}
            switch(event.getActionMasked()){
                case MotionEvent.ACTION_DOWN:if(!originalContains(event.getX(),event.getY()))return false;generation++;pending=null;before=pose.snapshot();int picked=hit(event.getX(),event.getY());if(picked>=0)selectedHandle=picked;pose.setPhase(1);updatePhase();editing=true;move(event.getX(),event.getY());if(getParent()!=null)getParent().requestDisallowInterceptTouchEvent(true);return true;
                case MotionEvent.ACTION_MOVE:if(editing)move(event.getX(),event.getY());return true;
                case MotionEvent.ACTION_UP:if(editing){move(event.getX(),event.getY());editing=false;before=null;requestPreview();if(getParent()!=null)getParent().requestDisallowInterceptTouchEvent(false);performClick();}return true;
            }return true;
        }
        @Override protected void onDetachedFromWindow(){cancelGesture();super.onDetachedFromWindow();}
        @Override public boolean performClick(){super.performClick();return true;}
    }
}
