package com.rezoxnemesis.videostudio;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Bitmap;
import android.view.PixelCopy;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.OpenableColumns;
import android.database.Cursor;
import android.view.Choreographer;
import android.view.Gravity;
import android.view.SurfaceView;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.FrameLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;
import com.google.android.filament.Engine;
import com.google.android.filament.Camera;
import com.google.android.filament.EntityManager;
import com.google.android.filament.Renderer;
import com.google.android.filament.Filament;
import com.google.android.filament.android.UiHelper;
import com.google.android.filament.utils.ModelViewer;
import com.google.android.filament.gltfio.Animator;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.security.MessageDigest;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.json.JSONObject;
import org.json.JSONArray;

/**
 * Genuine Android glTF 2.0 skeletal 3D model preview with Filament PBR,
 * embedded animation playback, bone matrices and a real perspective camera.
 *
 * Imports ONLY one explicitly picked GLB or an existing VideoStudio-owned GLB.
 * No Gallery enumeration, no neural 2D->3D claims, no paid model provider.
 */
public final class FilamentGlbActivity extends Activity
        implements Choreographer.FrameCallback {
    private static final int PICK_GLB=5307;
    private static final long MAX_GLB_BYTES=64L*1024*1024;
    private ProjectStore projectStore;
    private ProjectStore.Project project;
    private ModelViewer viewer;
    private Engine engine;
    private UiHelper uiHelper;
    private SurfaceView surface;
    private TextView status,modelHint,clipTime;
    private Button playbackButton,orbitButton,rateButton,retryButton;
    private final Preview3DReadiness previewReadiness=new Preview3DReadiness();
    private String pendingModelId="",modelReadyMessage="3D model resources loaded • frame submitted";
    /** The boundary returns native driver observations; tests cannot attest GPU pixels. */
    interface PreviewFrameDriver {
        boolean hasAsset();
        float progress();
        boolean render(long frameTimeNanos);
    }
    private PreviewFrameDriver previewDriver;
    private SeekBar modelScrub;
    private final Preview3DPlayback clipClock=new Preview3DPlayback();
    private final Handler main=new Handler(Looper.getMainLooper());
    private boolean loopActive=false,orbit=false,paused=false;
    private long lastRenderedAt=0;
    private int animationIndex=0;
    private double cameraAngle=0;
    private double cameraPitch=0,cameraDistance=4;
    private String loadedModelId="",modelName="";
    private String sketchCaptureTargetId="",sketchCaptureMode="colour_regions";
    private int sketchCaptureFrame;
    private boolean sketchCaptureBusy;
    private boolean renderUnavailable,restoringModel,modelLoading;
    private double restoredClipPosition;
    private int modelLoadGeneration;
    private Future<?> modelTask;
    private volatile InputStream modelInput;
    private boolean cinematic;
    private volatile boolean destroyed;
    private boolean cinematicLoading,cinematicUnavailable;
    private int cinematicLoadGeneration,cinematicFrame,cinematicFrameCount,cinematicFps;
    private int cinematicShotIndex=-1,cinematicLastRenderedFrame=-1,cinematicPlayStartFrame,cinematicFallbackLight;
    private long cinematicPlayStartedAt;
    private String cinematicProgramId,cinematicProgramSha;
    private JSONObject cinematicProgram,cinematicBundle,cinematicTimeline,cinematicScene;
    private JSONArray cinematicShots;
    private Camera cinematicCamera;
    private SeekBar cinematicScrub;
    private final ExecutorService cinematicWorker=Executors.newSingleThreadExecutor();

    @Override protected void onCreate(Bundle state){
        super.onCreate(state);
        getWindow().setStatusBarColor(0xff090d17);
        getWindow().setNavigationBarColor(0xff090d17);
        projectStore=new ProjectStore(this);
        sketchCaptureTargetId=getIntent().getStringExtra("sketchCaptureTargetId");
        if(sketchCaptureTargetId==null)sketchCaptureTargetId="";
        sketchCaptureFrame=getIntent().getIntExtra("sketchCaptureFrame",0);
        sketchCaptureMode=getIntent().getStringExtra("sketchCaptureMode");
        if(sketchCaptureMode==null)sketchCaptureMode="colour_regions";
        cinematicProgramId=getIntent().getStringExtra("cinematicProgramId");
        cinematic=cinematicProgramId!=null&&!cinematicProgramId.isEmpty();
        if(cinematic)cinematicFrame=Math.max(0,getIntent().getIntExtra("previewStartFrame",0));
        cinematicProgramSha=getIntent().getStringExtra("expectedProgramSha256");
        String projectId=getIntent().getStringExtra("projectId");
        project=projectId==null?projectStore.active():projectStore.get(projectId);
        if(project==null){finish();return;}
        if(state!=null){
            paused=state.getBoolean("paused",false);cinematicFrame=Math.max(0,state.getInt("cinematicFrame",0));
            loadedModelId=state.getString("modelId","");animationIndex=Math.max(0,state.getInt("animationIndex",0));
            restoredClipPosition=state.getDouble("clipPosition",0);restoringModel=!loadedModelId.isEmpty();
            try{clipClock.rate(state.getDouble("playbackRate",1));}catch(IllegalArgumentException ignored){}
            cameraAngle=finiteOr(state.getDouble("cameraAngle",0),0);cameraPitch=Math.max(-80,Math.min(80,finiteOr(state.getDouble("cameraPitch",0),0)));
            cameraDistance=Math.max(2,Math.min(12,finiteOr(state.getDouble("cameraDistance",4),4)));
        }
        try{
            Native3DRuntime.initialize();
            engine=Engine.create();
            uiHelper=new UiHelper(UiHelper.ContextErrorPolicy.DONT_CHECK);
            buildUi();
        }catch(Exception|LinkageError|OutOfMemoryError error){show3DError(error);}

    }

    private void show3DError(Throwable error){
        if(destroyed||renderUnavailable)return;renderUnavailable=true;modelLoadGeneration++;cinematicLoadGeneration++;
        loopActive=false;Choreographer.getInstance().removeFrameCallback(this);
        if(modelTask!=null)modelTask.cancel(true);closeModelInput();destroyViewer();
        ActivityLog.add(this,"user","3D preview unavailable",error.getClass().getSimpleName()+": "+String.valueOf(error.getMessage()),"failed",null,null,project==null?"":project.id);
        LinearLayout screen=new LinearLayout(this);screen.setOrientation(LinearLayout.VERTICAL);screen.setPadding(24,24,24,24);screen.setBackgroundColor(0xff090d17);TextView explanation=new TextView(this);explanation.setTextColor(Color.WHITE);explanation.setTextSize(17);explanation.setText("3D preview could not start on this device. Your project is saved.\n\n"+error.getClass().getSimpleName()+": "+String.valueOf(error.getMessage()));screen.addView(explanation);screen.addView(action("Return to studio",v->finish()));setContentView(screen);
    }
    private int dp(int value){return Math.round(value*getResources().getDisplayMetrics().density);}
    private Button action(String text,View.OnClickListener listener){
        Button button=new Button(this);
        button.setText(text);
        button.setTextSize(12);
        button.setAllCaps(false);
        button.setTextColor(Color.WHITE);
        button.setBackgroundTintList(android.content.res.ColorStateList.valueOf(0xff202f49));
        button.setOnClickListener(listener);
        return button;
    }
    private void buildUi(){
        if(cinematic){buildCinematicUi();return;}
        LinearLayout root=new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xff090d17);
        TextView title=new TextView(this);
        title.setText("VideoStudio • Real 3D Character Animation");
        title.setTextColor(0xffe8f7ff);
        title.setTextSize(19);
        title.setPadding(14,18,14,9);
        root.addView(title);
        TextView description=new TextView(this);
        description.setText("Import a rigged GLB model with built-in glTF animations. This is actual 3D geometry, skinning, lighting and camera perspective—not a rotating photo.");
        description.setTextColor(0xff9fb1c7);
        description.setTextSize(12);description.setPadding(14,0,14,10);
        root.addView(description);
        LinearLayout first=new LinearLayout(this);
        first.addView(action("Import GLB",v->selectGlb()),new LinearLayout.LayoutParams(0,dp(50),1));
        first.addView(action("Existing model",v->chooseExisting()),new LinearLayout.LayoutParams(0,dp(50),1));
        first.addView(action("Return",v->finish()),new LinearLayout.LayoutParams(0,dp(50),1));
        root.addView(first);

        FrameLayout modelFrame=new FrameLayout(this);root.addView(modelFrame,new LinearLayout.LayoutParams(-1,0,1));
        surface=new CinematicSurfaceView(this);modelFrame.addView(surface,new FrameLayout.LayoutParams(-1,-1));
        modelHint=new TextView(this);modelHint.setText("No 3D model loaded in this project.\nUse Import GLB or Existing model to open your geometry.");modelHint.setTextColor(0xffb9ccdf);modelHint.setTextSize(15);modelHint.setGravity(Gravity.CENTER);modelHint.setPadding(dp(24),dp(24),dp(24),dp(24));modelFrame.addView(modelHint,new FrameLayout.LayoutParams(-1,-1));
        viewer=new ModelViewer(surface,engine,uiHelper,null);
        attachPreviewDriver();
        configureCameraGestures();

        LinearLayout second=new LinearLayout(this);
        playbackButton=action("Pause",v->togglePlayback());second.addView(playbackButton,
                new LinearLayout.LayoutParams(0,dp(50),1));
        second.addView(action("Choose motion",v->chooseAnimation()),
                new LinearLayout.LayoutParams(0,dp(50),1));
        orbitButton=action("Orbit: off",v->{orbit=!orbit;orbitButton.setText(orbit?"Orbit: on":"Orbit: off");});second.addView(orbitButton,
                new LinearLayout.LayoutParams(0,dp(50),1));
        root.addView(second);
        LinearLayout cameraTools=new LinearLayout(this);
        cameraTools.addView(action("Camera views",v->chooseCamera()),new LinearLayout.LayoutParams(0,dp(48),1));
        cameraTools.addView(action("Fit model",v->{cameraAngle=cameraPitch=0;cameraDistance=4;orbit=false;orbitButton.setText("Orbit: off");updateModelCamera();}),new LinearLayout.LayoutParams(0,dp(48),1));
        rateButton=action("Speed 1×",v->chooseRate());cameraTools.addView(rateButton,new LinearLayout.LayoutParams(0,dp(48),1));root.addView(cameraTools);
        if(!sketchCaptureTargetId.isEmpty()){
            Button capture=action("Capture this 3D pose → editable Sketch cel",v->capturePoseForSketch());
            capture.setContentDescription("Capture the currently visible 3D pose and transfer it to the chosen Sketch Studio animation frame");
            root.addView(capture,new LinearLayout.LayoutParams(-1,dp(50)));
        }
        clipTime=new TextView(this);clipTime.setTextColor(0xffb9ccdf);clipTime.setPadding(dp(14),0,dp(14),0);clipTime.setText("Drag to orbit • pinch to zoom • select a clip to scrub");root.addView(clipTime);
        modelScrub=new SeekBar(this);modelScrub.setMax(1000);modelScrub.setEnabled(false);modelScrub.setContentDescription("3D animation clip time");
        modelScrub.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){
            public void onProgressChanged(SeekBar bar,int progress,boolean fromUser){if(fromUser){paused=true;clipClock.seek(clipDuration()*progress/1000d,clipDuration());applyModelPose();updatePlaybackUi();}}
            public void onStartTrackingTouch(SeekBar bar){paused=true;clipClock.suspend();updatePlaybackUi();}
            public void onStopTrackingTouch(SeekBar bar){}
        });root.addView(modelScrub);
        status=new TextView(this);
        status.setText("Choose a VideoStudio GLB or import one. A rigged model can contain multiple reusable animation clips.");
        status.setTextColor(0xff5adff4);
        status.setTextSize(12);
        status.setPadding(14,9,14,22);
        root.addView(status);
        addPreviewRetry(root);
        setContentView(root);
        chooseFirstExisting();
    }

    private void buildCinematicUi(){
        LinearLayout root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);root.setBackgroundColor(0xff090d17);
        TextView title=new TextView(this);title.setText("VideoStudio • Cinematic 3D Preview");title.setTextColor(0xffe8f7ff);title.setTextSize(19);title.setPadding(14,18,14,9);root.addView(title);
        TextView description=new TextView(this);description.setText("Saved full-scene geometry, weighted actors, swords and effects. Authored world roots and exact clip/camera bindings match native export.");description.setTextColor(0xff9fb1c7);description.setTextSize(12);description.setPadding(14,0,14,10);root.addView(description);
        FrameLayout preview=new FrameLayout(this);root.addView(preview,new LinearLayout.LayoutParams(-1,0,1));surface=new CinematicSurfaceView(this);preview.addView(surface,new FrameLayout.LayoutParams(-1,-1,Gravity.CENTER));
        preview.addOnLayoutChangeListener((view,left,top,right,bottom,oldLeft,oldTop,oldRight,oldBottom)->{int availableWidth=right-left,availableHeight=bottom-top;if(availableWidth<1||availableHeight<1)return;int width=availableWidth,height=Math.round(width*Cinematic3DStudioActivity.OUTPUT_HEIGHT/(float)Cinematic3DStudioActivity.OUTPUT_WIDTH);if(height>availableHeight){height=availableHeight;width=Math.round(height*Cinematic3DStudioActivity.OUTPUT_WIDTH/(float)Cinematic3DStudioActivity.OUTPUT_HEIGHT);}FrameLayout.LayoutParams layout=(FrameLayout.LayoutParams)surface.getLayoutParams();if(layout.width!=width||layout.height!=height){layout.width=width;layout.height=height;surface.setLayoutParams(layout);}});
        viewer=new ModelViewer(surface,engine,uiHelper,null);
        attachPreviewDriver();
        modelHint=new TextView(this);modelHint.setTextColor(0xffb9ccdf);modelHint.setTextSize(15);modelHint.setGravity(Gravity.CENTER);modelHint.setPadding(dp(24),dp(24),dp(24),dp(24));preview.addView(modelHint,new FrameLayout.LayoutParams(-1,-1));
        // ModelViewer's standalone default sunlight is not part of the authored scene.
        viewer.getScene().remove(viewer.getLight());surface.setOnTouchListener((v,event)->true);
        Renderer.ClearOptions clear=new Renderer.ClearOptions();clear.clear=true;clear.clearColor=new double[]{.045,.055,.075,1};viewer.getRenderer().setClearOptions(clear);
        cinematicScrub=new SeekBar(this);cinematicScrub.setEnabled(false);cinematicScrub.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){
            @Override public void onProgressChanged(SeekBar bar,int progress,boolean fromUser){if(!fromUser||cinematicLoading||cinematicUnavailable||cinematicTimeline==null)return;paused=true;cinematicPlayStartedAt=0;cinematicFrame=Math.max(0,Math.min(cinematicFrameCount-1,progress));requestCinematicFrame();}
            @Override public void onStartTrackingTouch(SeekBar bar){paused=true;cinematicPlayStartedAt=0;}
            @Override public void onStopTrackingTouch(SeekBar bar){}
        });root.addView(cinematicScrub);
        LinearLayout actions=new LinearLayout(this);actions.addView(action("Play / Pause",v->togglePlayback()),new LinearLayout.LayoutParams(0,dp(50),1));actions.addView(action("Restart",v->{if(cinematicLoading||cinematicUnavailable)return;cinematicFrame=0;cinematicPlayStartedAt=0;paused=false;requestCinematicFrame();}),new LinearLayout.LayoutParams(0,dp(50),1));actions.addView(action("Return",v->finish()),new LinearLayout.LayoutParams(0,dp(50),1));root.addView(actions);
        status=new TextView(this);status.setText("Verifying the saved source, program and scene bundle…");status.setTextColor(0xff5adff4);status.setTextSize(12);status.setPadding(14,9,14,22);root.addView(status);addPreviewRetry(root);setContentView(root);loadCinematicProgram();
    }

    private void attachPreviewDriver(){
        final ModelViewer owned=viewer;
        previewDriver=new PreviewFrameDriver(){
            public boolean hasAsset(){return owned.getAsset()!=null;}
            public float progress(){return owned.getProgress();}
            public boolean render(long nanos){return owned.render(nanos);}
        };
    }
    private void addPreviewRetry(LinearLayout root){
        retryButton=action("Retry preview",v->retryPreview());retryButton.setVisibility(View.GONE);root.addView(retryButton);
    }
    private void beginPreviewReadiness(){
        previewReadiness.start(android.os.SystemClock.elapsedRealtime());clipClock.suspend();cinematicPlayStartedAt=0;
        if(retryButton!=null)retryButton.setVisibility(View.GONE);
        if(modelScrub!=null)modelScrub.setEnabled(false);
        if(cinematicScrub!=null)cinematicScrub.setEnabled(false);
        previewMessage("Loading 3D resources…");
    }
    private void previewMessage(String message){
        if(status!=null)status.setText(message);
        if(modelHint!=null){modelHint.setText(message);modelHint.setVisibility(View.VISIBLE);}
    }
    private void observePreviewSubmission(float progress,boolean submitted){
        if(!previewReadiness.monitoring()||previewReadiness.failed())return;
        previewReadiness.observe(android.os.SystemClock.elapsedRealtime(),progress,submitted);
        if(previewReadiness.ready()){
            if(modelHint!=null)modelHint.setVisibility(View.GONE);
            if(cinematic){
                cinematicLastRenderedFrame=cinematicFrame;
                if(cinematicScrub!=null)cinematicScrub.setEnabled(true);
            }else{
                if(modelScrub!=null)modelScrub.setEnabled(clipDuration()>0);
                if(status!=null)status.setText(modelReadyMessage);
            }
            return;
        }
        clipClock.suspend();cinematicPlayStartedAt=0;
        if(modelScrub!=null)modelScrub.setEnabled(false);
        if(cinematicScrub!=null)cinematicScrub.setEnabled(false);
        boolean materials=!Float.isFinite(progress)||progress<1f;
        if(previewReadiness.failed()){
            String reason=materials?"3D materials did not finish loading":"3D frame submission remained unavailable";
            previewFailure(new IllegalStateException(reason+" after 30 seconds while this screen was active"));
        }else previewMessage(materials?"Loading 3D materials…":"Waiting for 3D surface/frame submission…");
    }
    private void previewFailure(Throwable error){
        previewReadiness.fail();clipClock.suspend();cinematicPlayStartedAt=0;
        if(cinematic){paused=true;cinematicUnavailable=true;cinematicLoading=false;cinematicLoadGeneration++;try{releaseCinematicScene();}catch(RuntimeException ignored){}}
        else cancelModelLoad();
        if(modelScrub!=null)modelScrub.setEnabled(false);
        if(cinematicScrub!=null)cinematicScrub.setEnabled(false);
        String reason=error.getMessage()==null?error.getClass().getSimpleName():error.getMessage();
        previewMessage("3D preview unavailable: "+reason+". Tap Retry preview.");
        if(retryButton!=null)retryButton.setVisibility(View.VISIBLE);
    }
    private void retryPreview(){
        if(destroyed||renderUnavailable||viewer==null)return;
        if(cinematic){cinematicUnavailable=false;loadCinematicProgram();return;}
        ProjectStore.Project fresh=projectStore.get(project.id);
        ProjectStore.Asset asset=fresh==null?null:fresh.asset(pendingModelId);
        if(asset==null){previewFailure(new IllegalArgumentException("The selected project model is no longer available"));return;}
        project=fresh;
        loadRegistered(asset);
    }

    private JSONObject cinematicRequest() throws Exception{
        if(cinematicProgramSha==null||!cinematicProgramSha.matches("[0-9a-f]{64}"))throw new IllegalArgumentException("An exact saved program hash is required for cinematic preview");
        return new JSONObject().put("projectId",project.id).put("programId",cinematicProgramId).put("expectedProgramSha256",cinematicProgramSha);
    }
    private void loadCinematicProgram(){
        beginPreviewReadiness();
        cinematicLoading=true;int generation=++cinematicLoadGeneration;
        cinematicWorker.execute(()->{JSONObject program=null,bundle=null;Throwable problem=null;
            try{program=Cinematic3DProgramStore.get(getApplicationContext(),projectStore,cinematicRequest());bundle=NativeCinematicBundleStore.get(getApplicationContext(),projectStore,new JSONObject().put("projectId",project.id).put("bundleId",program.getString("bundleId")));}
            catch(Exception|OutOfMemoryError error){problem=error;}
            final JSONObject acceptedProgram=program,acceptedBundle=bundle;final Throwable failure=problem;
            main.post(()->{if(destroyed||generation!=cinematicLoadGeneration)return;cinematicLoading=false;
                if(failure!=null){cinematicFailure(failure);return;}
                try{cinematicProgram=acceptedProgram;cinematicBundle=acceptedBundle;cinematicTimeline=Cinematic3DTimeline.validate(acceptedProgram.getJSONObject("timeline"));JSONObject plan=Cinematic3DTimeline.compile(cinematicTimeline);cinematicShots=plan.getJSONArray("shots");cinematicFps=plan.getInt("fps");cinematicFrameCount=plan.getInt("totalFrames");cinematicScrub.setMax(cinematicFrameCount-1);cinematicFrame=Math.min(cinematicFrame,cinematicFrameCount-1);cinematicPlayStartedAt=0;requestCinematicFrame();}
                catch(Exception error){cinematicFailure(error);}
            });
        });
    }
    private void requestCinematicFrame(){
        if(destroyed||cinematicUnavailable||cinematicTimeline==null||cinematicLoading)return;
        try{
            JSONObject sampled=cinematicFrameBinding();int shotIndex=sampled.getInt("shotIndex");
            if(shotIndex==cinematicShotIndex&&viewer.getAsset()!=null){applyCinematicFrame(sampled);return;}
            JSONObject shot=sampled.getJSONObject("shot"),scene;
            JSONArray scenes=cinematicProgram.optJSONArray("scenes");scene=scenes!=null?scenes.getJSONObject(shotIndex):Cinematic3DSceneSampling.sceneForShot(cinematicBundle,shot);
            final JSONObject selectedScene=new JSONObject(scene.toString());final int selectedIndex=shotIndex;final int generation=++cinematicLoadGeneration;
            cinematicLoading=true;cinematicScrub.setEnabled(false);cinematicPlayStartedAt=0;status.setText("Loading authored scene • "+shot.getString("action"));
            beginPreviewReadiness();
            cinematicWorker.execute(()->{ByteBuffer data=null;Throwable problem=null;
                try{
                    Cinematic3DProgramStore.get(getApplicationContext(),projectStore,cinematicRequest());
                    File file=NativeCinematicBundleStore.sceneFile(getApplicationContext(),project.id,cinematicProgram.getString("bundleId"),selectedScene.getString("path"));
                    if(!file.isFile()||file.length()>16L*1024*1024)throw new IllegalArgumentException("Cinematic scene is missing or exceeds 16 MiB");
                    verifyGlb(file);data=ByteBuffer.allocateDirect((int)file.length());
                    try(FileInputStream input=new FileInputStream(file)){byte[] buffer=new byte[64*1024];int length;while((length=input.read(buffer))!=-1){if(Thread.currentThread().isInterrupted()||destroyed)throw new InterruptedException("Scene preview cancelled");data.put(buffer,0,length);}}data.flip();
                    JSONObject manifest=cinematicBundle.getJSONObject("manifest");JSONArray files=manifest.getJSONArray("files");String expected="";for(int i=0;i<files.length();i++){JSONObject entry=files.getJSONObject(i);if(selectedScene.getString("path").equals(entry.getString("path"))){expected=entry.getString("sha256");break;}}
                    verifyLoadedSceneBytes(data,expected);
                }catch(Exception|OutOfMemoryError error){problem=error;}
                final ByteBuffer ready=data;final Throwable failure=problem;
                main.post(()->{if(destroyed||generation!=cinematicLoadGeneration)return;cinematicLoading=false;
                    if(failure!=null){cinematicFailure(failure);return;}
                    try{
                        releaseCinematicScene();viewer.loadModelGlb(ready);
                        if(viewer.getAsset()==null||viewer.getAnimator()==null)throw new IllegalArgumentException("Filament could not create the authored scene animation");
                        // Never reset or transform the full-scene root to a unit cube.
                        if(normalizeSceneForPreview(cinematic))viewer.resetToDefaultState();
                        cinematicScene=selectedScene;cinematicShotIndex=selectedIndex;Animator animator=viewer.getAnimator();
                        animationIndex=Cinematic3DSceneSampling.requireAuthoredScene(viewer.getAsset(),animator,selectedScene);
                        cinematicCamera=Cinematic3DSceneSampling.requireCamera(viewer.getAsset(),engine,selectedScene.getString("cameraName"));viewer.getView().setCamera(cinematicCamera);
                        for(int entity:viewer.getAsset().getLightEntities())viewer.getScene().addEntity(entity);
                        cinematicFallbackLight=Cinematic3DSceneSampling.createFallbackLight(engine,viewer.getScene(),viewer.getAsset());
                        applyCinematicFrame(cinematicFrameBinding());cinematicLastRenderedFrame=-1;cinematicPlayStartFrame=cinematicFrame;cinematicPlayStartedAt=0;
                    }catch(Exception|OutOfMemoryError error){cinematicFailure(error);}
                });
            });
        }catch(Exception error){cinematicFailure(error);}
    }
    private JSONObject cinematicFrameBinding() throws Exception{
        if(cinematicFrame<0||cinematicFrame>=cinematicFrameCount)throw new IllegalArgumentException("Preview frame is outside the saved timeline");
        for(int i=0;i<cinematicShots.length();i++){JSONObject shot=cinematicShots.getJSONObject(i);if(cinematicFrame<shot.getInt("endFrame"))return new JSONObject().put("shotIndex",i).put("shotFrame",cinematicFrame-shot.getInt("startFrame")).put("shot",shot);}
        throw new IllegalArgumentException("Saved preview shot is unavailable");
    }
    private void applyCinematicFrame(JSONObject sampled) throws Exception {
        if(cinematicScene==null||cinematicCamera==null)return;
        JSONObject shot=sampled.getJSONObject("shot");JSONObject frame=Cinematic3DSceneSampling.frame(shot,sampled.getInt("shotFrame"),cinematicFps,cinematicScene);
        Cinematic3DSceneSampling.apply(engine,viewer.getAsset(),viewer.getAnimator(),animationIndex,cinematicCamera,frame,Cinematic3DStudioActivity.OUTPUT_WIDTH,Cinematic3DStudioActivity.OUTPUT_HEIGHT);
        cinematicScrub.setProgress(cinematicFrame);status.setText(cinematicTimeline.optString("name")+" • "+("procedural_mannequin".equals(cinematicTimeline.optString("motionProvider"))?"procedural mannequins":"authored 3D")+" • "+shot.getString("action")+" • frame "+(cinematicFrame+1)+"/"+cinematicFrameCount+(frame.getBoolean("hitStop")?" • impact hold":"")+(paused?" • paused":""));
    }
    private void cinematicFailure(Throwable error){previewFailure(error);}
    private void releaseCinematicScene(){
        if(viewer==null)return;viewer.getView().setCamera(viewer.getCamera());cinematicCamera=null;
        if(cinematicFallbackLight!=0){viewer.getScene().remove(cinematicFallbackLight);engine.destroyEntity(cinematicFallbackLight);EntityManager.get().destroy(cinematicFallbackLight);cinematicFallbackLight=0;}
        if(viewer.getAsset()!=null)for(int entity:viewer.getAsset().getLightEntities())viewer.getScene().remove(entity);
        viewer.destroyModel();cinematicShotIndex=-1;cinematicScene=null;
    }
    static boolean normalizeSceneForPreview(boolean fullScene){return !fullScene;}
    /** The pinned viewer's automatic detach destructor must not race Activity cleanup. */
    static final class CinematicSurfaceView extends SurfaceView {
        CinematicSurfaceView(android.content.Context context){super(context);}
        @Override public void addOnAttachStateChangeListener(View.OnAttachStateChangeListener listener){
            if(listener!=null&&"com.google.android.filament.utils.ModelViewer$addDetachListener$1".equals(listener.getClass().getName())){
                super.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener(){
                    @Override public void onViewAttachedToWindow(View view){listener.onViewAttachedToWindow(view);}
                    @Override public void onViewDetachedFromWindow(View view){/* Activity owns the one viewer.destroy() call. */}
                });
            }else super.addOnAttachStateChangeListener(listener);
        }
    }
    static void verifyLoadedSceneBytes(ByteBuffer bytes,String expected) throws Exception{
        if(expected==null||!expected.matches("[0-9a-f]{64}"))throw new IllegalArgumentException("The exact manifest scene hash is required");
        MessageDigest digest=MessageDigest.getInstance("SHA-256");digest.update(bytes.asReadOnlyBuffer());StringBuilder value=new StringBuilder(64);for(byte item:digest.digest())value.append(String.format(java.util.Locale.US,"%02x",item&255));if(!expected.equals(value.toString()))throw new IllegalArgumentException("BUNDLE_SCENE_CHANGED: loaded scene bytes do not match the accepted manifest");
    }

    private void chooseFirstExisting(){
        if(!loadedModelId.isEmpty()){ProjectStore.Asset saved=project.asset(loadedModelId);if(saved!=null&&"model/gltf-binary".equals(saved.mime)){loadRegistered(saved);return;}}
        for(ProjectStore.Asset a:project.assets)
            if("model/gltf-binary".equals(a.mime)){
                loadRegistered(a);return;
            }
    }
    private void chooseExisting(){
        List<ProjectStore.Asset> models=new ArrayList<>();
        for(ProjectStore.Asset a:project.assets)
            if("model/gltf-binary".equals(a.mime))models.add(a);
        if(models.isEmpty()){
            Toast.makeText(this,"No GLB imported in this VideoStudio project",
                    Toast.LENGTH_LONG).show();return;
        }
        String[] names=new String[models.size()];
        for(int i=0;i<models.size();i++)names[i]=models.get(i).name;
        new AlertDialog.Builder(this).setTitle("Imported 3D models")
                .setItems(names,(d,index)->loadRegistered(models.get(index)))
                .setNegativeButton("Cancel",null).show();
    }
    private void selectGlb(){
        Intent pick=new Intent(Intent.ACTION_OPEN_DOCUMENT);
        pick.addCategory(Intent.CATEGORY_OPENABLE);
        pick.setType("*/*");
        pick.putExtra(Intent.EXTRA_MIME_TYPES,new String[]{
                "model/gltf-binary","application/octet-stream"});
        startActivityForResult(pick,PICK_GLB);
    }
    @Override protected void onActivityResult(int code,int result,Intent data){
        super.onActivityResult(code,result,data);
        if(code!=PICK_GLB||result!=RESULT_OK||data==null||data.getData()==null)return;
        Uri chosen=data.getData();
        String name=safeDisplayName(chosen);
        status.setText("Importing explicitly selected 3D file...");
        cancelModelLoad();final int generation=modelLoadGeneration;
        modelTask=cinematicWorker.submit(()->{
            try{
                ProjectStore.Asset asset=storeSelectedFile(chosen,name);
                main.post(()->{if(destroyed||renderUnavailable||generation!=modelLoadGeneration)return;project=projectStore.get(project.id);loadRegistered(asset);});
            }catch(Exception|OutOfMemoryError error){
                main.post(()->{if(!destroyed&&generation==modelLoadGeneration)status.setText("3D import failed: "+error.getMessage());});
            }
        });
    }
    private String safeDisplayName(Uri uri){
        String fallback="imported_model.glb";
        try(Cursor c=getContentResolver().query(
                uri,new String[]{OpenableColumns.DISPLAY_NAME},null,null,null)){
            if(c!=null&&c.moveToFirst()){
                String name=c.getString(0);
                if(name!=null&&!name.trim().isEmpty())fallback=name.trim();
            }
        }catch(Exception ignored){}
        return fallback.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]","_");
    }
    private ProjectStore.Asset storeSelectedFile(Uri uri,String name) throws Exception{
        File folder=new File(getFilesDir(),
                "creative_workspace/projects/"+project.id+"/models");
        if(!folder.exists()&&!folder.mkdirs())
            throw new IllegalStateException("Could not prepare private 3D workspace");
        String assetId=UUID.randomUUID().toString();
        File file=new File(folder,assetId+".glb");
        long bytes=0;boolean registered=false;
        try{
        try(InputStream in=getContentResolver().openInputStream(uri);
            FileOutputStream out=new FileOutputStream(file)){
            if(in==null)throw new IllegalArgumentException("The selected GLB cannot be read");
            modelInput=in;
            byte[] buffer=new byte[256*1024];int length;
            while((length=in.read(buffer))!=-1){
                if(Thread.currentThread().isInterrupted()||destroyed)throw new InterruptedException("3D import cancelled");
                bytes+=length;
                if(bytes>MAX_GLB_BYTES)
                    throw new IllegalArgumentException("For mobile safety, GLB previews are limited to 64 MB");
                out.write(buffer,0,length);
            }
            out.getFD().sync();
        }finally{modelInput=null;}
        if(bytes<20){file.delete();throw new IllegalArgumentException("GLB is empty");}
        verifyGlb(file);
        ProjectStore.Asset asset=new ProjectStore.Asset();
        asset.id=assetId;asset.name=name;asset.mime="model/gltf-binary";
        asset.uri=Uri.fromFile(file).toString();asset.sizeBytes=bytes;
        asset.seekable=true;asset.persistedReadAccess=true;asset.role="source_glb";
        synchronized(projectStore){
            if(Thread.currentThread().isInterrupted()||destroyed)throw new InterruptedException("3D import cancelled");
            ProjectStore.Project fresh=projectStore.get(project.id);
            if(fresh==null)throw new IllegalArgumentException("VideoStudio project no longer exists");
            fresh.assets.add(asset);
            projectStore.save(fresh);
        }
        registered=true;return asset;
        }finally{if(!registered)file.delete();}
    }
    static void verifyGlb(File file) throws Exception{
        try(FileInputStream in=new FileInputStream(file)){
            byte[] header=new byte[12];
            int received=0,n;
            while(received<12&&(n=in.read(header,received,12-received))>0)received+=n;
            if(received!=12||file.length()<20)
                throw new IllegalArgumentException("Truncated GLB header");
            ByteBuffer b=ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN);
            if(b.getInt()!=0x46546c67||b.getInt()!=2||
                    Integer.toUnsignedLong(b.getInt())!=file.length())
                throw new IllegalArgumentException("Only valid GLB version 2 models are supported");
        }
    }
    private void loadRegistered(ProjectStore.Asset asset){
        if(destroyed||renderUnavailable||viewer==null||asset==null)return;
        cancelModelLoad();final int generation=modelLoadGeneration;
        pendingModelId=asset.id;modelLoading=true;beginPreviewReadiness();
        status.setText("Loading 3D scene and skinned materials...");
        modelTask=cinematicWorker.submit(()->{try{
            File file=new File(Uri.parse(asset.uri).getPath());
            if(!file.isFile()||file.length()>MAX_GLB_BYTES)
                throw new IllegalArgumentException("Model is missing or too large");
            verifyGlb(file);
            ByteBuffer buffer=ByteBuffer.allocateDirect((int)file.length());
            try(FileInputStream in=new FileInputStream(file)){
                modelInput=in;
                byte[] part=new byte[128*1024];int n;
                while((n=in.read(part))!=-1){if(Thread.currentThread().isInterrupted()||destroyed)throw new InterruptedException("3D load cancelled");buffer.put(part,0,n);}
            }finally{modelInput=null;}
            buffer.flip();
            main.post(()->{if(destroyed||renderUnavailable||generation!=modelLoadGeneration||viewer==null)return;try{
            modelLoading=false;
            viewer.loadModelGlb(buffer);
            if(viewer.getAsset()==null)
                throw new IllegalArgumentException("Filament could not create the model asset");
            if(normalizeSceneForPreview(cinematic))viewer.resetToDefaultState();
            Animator animations=viewer.getAnimator();
            int count=animations==null?0:animations.getAnimationCount();
            boolean restore=restoringModel&&asset.id.equals(loadedModelId);restoringModel=false;
            if(!restore){paused=false;animationIndex=0;cameraAngle=cameraPitch=0;cameraDistance=4;}
            loadedModelId=asset.id;modelName=asset.name;animationIndex=Math.min(animationIndex,Math.max(0,count-1));
            clipClock.seek(restore&&Double.isFinite(restoredClipPosition)&&restoredClipPosition>=0?restoredClipPosition:0,clipDuration());
            applyModelPose();updateModelCamera();updatePlaybackUi();
            modelReadyMessage=asset.name+" • glTF model • frame submitted • "+count+
                    " embedded animation(s). "+(count==0
                    ?"No authored animation clips."
                    :"Choose motion, scrub a pose, adjust speed or frame the camera.");
            previewMessage("Loading 3D materials…");
            }catch(Exception|LinkageError|OutOfMemoryError error){previewFailure(error);}});
        }catch(Exception|LinkageError|OutOfMemoryError error){
            main.post(()->{if(!destroyed&&generation==modelLoadGeneration)previewFailure(error);});
        }});
    }
    private void togglePlayback(){
        if(cinematic){if(cinematicLoading||cinematicUnavailable||cinematicTimeline==null)return;paused=!paused;cinematicPlayStartFrame=cinematicFrame;cinematicPlayStartedAt=0;return;}
        paused=!paused;
        clipClock.suspend();updatePlaybackUi();
    }
    private void nextAnimation(){
        if(viewer==null||viewer.getAnimator()==null)return;
        int count=viewer.getAnimator().getAnimationCount();
        if(count<1)return;
        animationIndex=(animationIndex+1)%count;
        paused=false;
        clipClock.seek(0,clipDuration());applyModelPose();updatePlaybackUi();
        status.setText("Playing actual glTF animation "+(animationIndex+1)+"/"+count+
                ": "+viewer.getAnimator().getAnimationName(animationIndex));
    }
    private void chooseAnimation(){
        Animator animator=viewer==null?null:viewer.getAnimator();int count=animator==null?0:animator.getAnimationCount();
        if(count==0){Toast.makeText(this,"This model has no embedded motion clips",Toast.LENGTH_SHORT).show();return;}
        String[] names=new String[count];for(int index=0;index<count;index++)names[index]=(index+1)+". "+clipName(index)+String.format(java.util.Locale.US," • %.2f s",animator.getAnimationDuration(index));
        new AlertDialog.Builder(this).setTitle("Choose embedded motion").setSingleChoiceItems(names,animationIndex,(dialog,index)->{
            animationIndex=index;clipClock.seek(0,clipDuration());applyModelPose();updatePlaybackUi();dialog.dismiss();
        }).setNegativeButton("Cancel",null).show();
    }
    private String clipName(int index){String name=viewer.getAnimator().getAnimationName(index);return name==null||name.trim().isEmpty()?"Motion "+(index+1):name;}
    private double clipDuration(){
        Animator animator=viewer==null?null:viewer.getAnimator();if(animator==null||animationIndex<0||animationIndex>=animator.getAnimationCount())return 0;
        double duration=animator.getAnimationDuration(animationIndex);return Double.isFinite(duration)&&duration>0?duration:0;
    }
    private void applyModelPose(){
        Animator animator=viewer==null?null:viewer.getAnimator();if(animator==null||animationIndex>=animator.getAnimationCount())return;
        animator.applyAnimation(animationIndex,(float)clipClock.position());animator.updateBoneMatrices();
    }
    private void updatePlaybackUi(){
        if(playbackButton!=null)playbackButton.setText(paused?"Play":"Pause");
        if(rateButton!=null)rateButton.setText(String.format(java.util.Locale.US,"Speed %s×",clipClock.rate()));
        if(modelScrub==null)return;double duration=clipDuration();modelScrub.setProgress(duration>0?(int)Math.round(1000*clipClock.position()/duration):0);
        if(duration>0)clipTime.setText(String.format(java.util.Locale.US,"%s • %.2f / %.2f s • %s",clipName(animationIndex),clipClock.position(),duration,paused?"paused":"playing"));
    }
    private void chooseRate(){
        double[] rates={.25,.5,1,1.5,2};String[] names={"0.25× · inspect","0.5× · slow","1× · authored","1.5×","2×"};
        new AlertDialog.Builder(this).setTitle("Preview playback speed").setItems(names,(dialog,index)->{clipClock.rate(rates[index]);updatePlaybackUi();}).setNegativeButton("Cancel",null).show();
    }
    private void chooseCamera(){
        new AlertDialog.Builder(this).setTitle("Model camera view").setItems(new String[]{"Front","Right","Back","Left","Above","Three-quarter"},(dialog,index)->{
            cameraAngle=new double[]{0,Math.PI/2,Math.PI,-Math.PI/2,0,Math.PI/4}[index];cameraPitch=index==4?75:index==5?20:0;cameraDistance=4;orbit=false;orbitButton.setText("Orbit: off");updateModelCamera();
        }).setNegativeButton("Cancel",null).show();
    }
    private void updateModelCamera(){
        if(viewer==null||cinematic)return;double pitch=Math.toRadians(cameraPitch),horizontal=cameraDistance*Math.cos(pitch);
        viewer.getCamera().lookAt(horizontal*Math.sin(cameraAngle),cameraDistance*Math.sin(pitch),-4+horizontal*Math.cos(cameraAngle),0,0,-4,0,1,0);
    }
    private void configureCameraGestures(){
        ScaleGestureDetector scale=new ScaleGestureDetector(this,new ScaleGestureDetector.SimpleOnScaleGestureListener(){@Override public boolean onScale(ScaleGestureDetector detector){float factor=detector.getScaleFactor();if(Float.isFinite(factor)&&factor>0){cameraDistance=Math.max(2,Math.min(12,cameraDistance/factor));updateModelCamera();}return true;}});
        float[] last={0,0};boolean[] dragging={false};surface.setContentDescription("3D model. Drag to orbit and pinch to zoom. Camera views and Fit model provide accessible alternatives.");
        surface.setOnTouchListener((view,event)->{
            for(int index=0;index<event.getPointerCount();index++)if(!Float.isFinite(event.getX(index))||!Float.isFinite(event.getY(index))){dragging[0]=false;return true;}scale.onTouchEvent(event);
            int action=event.getActionMasked();if(action==MotionEvent.ACTION_DOWN){last[0]=event.getX();last[1]=event.getY();dragging[0]=true;}
            else if(event.getPointerCount()>1){dragging[0]=false;}
            else if(action==MotionEvent.ACTION_MOVE&&dragging[0]&&!scale.isInProgress()){cameraAngle+=((double)event.getX()-last[0])*.008;cameraPitch=Math.max(-80,Math.min(80,cameraPitch+((double)event.getY()-last[1])*.25));last[0]=event.getX();last[1]=event.getY();updateModelCamera();}
            else if(action==MotionEvent.ACTION_UP||action==MotionEvent.ACTION_CANCEL){dragging[0]=false;if(action==MotionEvent.ACTION_UP)view.performClick();}return true;
        });
    }
    /** Capture only the owner's currently visible 3D viewport; never access Gallery. */
    private void capturePoseForSketch(){
        if(sketchCaptureBusy)return;
        if(cinematic||viewer==null||viewer.getAsset()==null||loadedModelId.isEmpty()||renderUnavailable){
            status.setText("Load a real GLB model before capturing a 2D animation cel.");return;
        }
        if(sketchCaptureTargetId.isEmpty()){status.setText("Open this action from Sketch Studio first");return;}
        if(!"colour_regions".equals(sketchCaptureMode)&&!"edges".equals(sketchCaptureMode)){
            status.setText("Invalid 2D tracing mode");return;
        }
        final int[] dimensions;
        try{dimensions=Sketch3DCelBridge.captureSize(surface.getWidth(),surface.getHeight(),Sketch3DCelBridge.MAX_CAPTURE_EDGE);}
        catch(Exception error){status.setText("3D capture unavailable: "+error.getMessage());return;}
        final Bitmap frame=Bitmap.createBitmap(dimensions[0],dimensions[1],Bitmap.Config.ARGB_8888);
        final String modelId=loadedModelId,projectId=project.id,sketchId=sketchCaptureTargetId,traceMode=sketchCaptureMode;
        final int sketchFrame=sketchCaptureFrame,generation=modelLoadGeneration,animation=animationIndex;
        final double yaw=cameraAngle,pitch=cameraPitch,distance=cameraDistance,seconds=clipClock.position();
        sketchCaptureBusy=true;paused=true;orbit=false;clipClock.suspend();
        applyModelPose();updatePlaybackUi();
        status.setText("Capturing visible GLB pose as an app-private 2D reference...");
        try{PixelCopy.request(surface,frame,copyResult->{
            if(copyResult!=PixelCopy.SUCCESS||destroyed||generation!=modelLoadGeneration){
                frame.recycle();sketchCaptureBusy=false;
                if(!destroyed)status.setText("3D capture was cancelled or GPU readback failed ("+copyResult+")");
                return;
            }
            cinematicWorker.execute(()->{
                File file=null;boolean registered=false;
                try{
                    File folder=new File(getFilesDir(),"creative_workspace/projects/"+projectId+"/pose_cels");
                    if(!folder.isDirectory()&&!folder.mkdirs())throw new IllegalStateException("3D pose storage unavailable");
                    file=new File(folder,UUID.randomUUID().toString()+".png");
                    try(FileOutputStream output=new FileOutputStream(file)){
                        if(!frame.compress(Bitmap.CompressFormat.PNG,100,output))throw new IllegalStateException("3D pose PNG encoding failed");
                        output.getFD().sync();
                    }
                    if(file.length()<32||file.length()>8L*1024*1024)throw new IllegalStateException("3D pose exceeded the PNG storage budget");
                    String hash=SketchProjectStore.fileHash(file);
                    ProjectStore.Project fresh=projectStore.get(projectId);
                    if(fresh==null||fresh.asset(modelId)==null)throw new IllegalStateException("Source GLB project changed during capture");
                    ProjectStore.Asset asset=projectStore.registerGeneratedAsset(fresh,
                            Uri.fromFile(file),"3D pose "+modelName+" f"+(sketchFrame+1)+".png","sketch_3d_pose",false);
                    asset.generationMetadata=new JSONObject().put("sourceGlbAssetId",modelId)
                            .put("captureSha256",hash).put("sketchId",sketchId)
                            .put("frameIndex",sketchFrame).put("traceMode",traceMode)
                            .put("cameraYawRadians",yaw).put("cameraPitchDegrees",pitch)
                            .put("cameraDistance",distance).put("animationIndex",animation)
                            .put("animationTimeSeconds",seconds)
                            .put("captureWidth",dimensions[0]).put("captureHeight",dimensions[1]);
                    projectStore.save(fresh);
                    registered=true;
                    String capturedAssetId=asset.id;
                    main.post(()->{
                        sketchCaptureBusy=false;
                        if(destroyed)return;
                        Intent result=new Intent().putExtra("sketchId",sketchId)
                                .putExtra("projectId",projectId).putExtra("assetId",capturedAssetId)
                                .putExtra("frameIndex",sketchFrame).putExtra("traceMode",traceMode);
                        setResult(RESULT_OK,result);finish();
                    });
                }catch(Exception|OutOfMemoryError error){
                    final String why=String.valueOf(error.getMessage());
                    main.post(()->{sketchCaptureBusy=false;if(!destroyed)status.setText("3D pose retained only after verified registration: "+why);});
                }finally{frame.recycle();if(!registered&&file!=null)file.delete();}
            });
        },main);}
        catch(Exception|OutOfMemoryError error){
            frame.recycle();sketchCaptureBusy=false;
            status.setText("GPU 3D capture not available: "+error.getMessage());
        }
    }

    private static double finiteOr(double value,double fallback){return Double.isFinite(value)?value:fallback;}
    private void closeModelInput(){InputStream input=modelInput;modelInput=null;if(input!=null)try{input.close();}catch(Exception ignored){}}
    private void cancelModelLoad(){modelLoading=false;modelLoadGeneration++;if(modelTask!=null){modelTask.cancel(true);modelTask=null;}closeModelInput();}
    @Override protected void onResume(){
        previewReadiness.resume(android.os.SystemClock.elapsedRealtime());
        super.onResume();loopActive=viewer!=null&&!renderUnavailable&&!destroyed;clipClock.suspend();lastRenderedAt=0;
        Choreographer.getInstance().removeFrameCallback(this);if(loopActive)Choreographer.getInstance().postFrameCallback(this);
    }
    @Override protected void onPause(){
        previewReadiness.pause(android.os.SystemClock.elapsedRealtime());
        loopActive=false;
        clipClock.suspend();
        if(cinematic)cinematicPlayStartedAt=0;
        Choreographer.getInstance().removeFrameCallback(this);
        super.onPause();
    }
    @Override public void doFrame(long frameTimeNanos){
        try{renderFrame(frameTimeNanos);}catch(Exception|LinkageError|OutOfMemoryError error){show3DError(error);}
    }
    private void renderFrame(long frameTimeNanos){
        if(!loopActive||previewDriver==null||destroyed||renderUnavailable)return;
        if(previewReadiness.failed()){Choreographer.getInstance().postFrameCallback(this);return;}
        if(cinematic){
            if(!cinematicLoading&&!cinematicUnavailable&&previewReadiness.ready()&&cinematicTimeline!=null&&!paused){if(cinematicPlayStartedAt==0){cinematicPlayStartedAt=frameTimeNanos;cinematicPlayStartFrame=cinematicFrame;}long elapsed=frameTimeNanos-cinematicPlayStartedAt;long advance=elapsed/1_000_000_000L*cinematicFps+(elapsed%1_000_000_000L)*cinematicFps/1_000_000_000L;cinematicFrame=(int)Math.min(cinematicFrameCount-1,(long)cinematicPlayStartFrame+advance);if(cinematicFrame==cinematicFrameCount-1)paused=true;}
            boolean frameChanged=!cinematicLoading&&cinematicFrame!=cinematicLastRenderedFrame;
            if(frameChanged||frameTimeNanos-lastRenderedAt>=1_000_000_000L/Math.max(1,cinematicFps==0?30:cinematicFps)){lastRenderedAt=frameTimeNanos;
                if(!cinematicLoading&&!cinematicUnavailable&&cinematicTimeline!=null){
                    requestCinematicFrame();
                }
                if(!cinematicLoading&&previewDriver.hasAsset()){
                    boolean submitted=previewDriver.render(frameTimeNanos);
                    observePreviewSubmission(previewDriver.progress(),submitted);
                }else observePreviewSubmission(0f,false);
            }
            Choreographer.getInstance().postFrameCallback(this);return;
        }
        if(modelLoading||!previewDriver.hasAsset()){
            observePreviewSubmission(0f,false);Choreographer.getInstance().postFrameCallback(this);return;
        }
        // 30fps GPU cap protects battery/thermal headroom on phone.
        if(frameTimeNanos-lastRenderedAt>=33_000_000L){
            long elapsed=lastRenderedAt==0?0:Math.max(0,frameTimeNanos-lastRenderedAt);
            lastRenderedAt=frameTimeNanos;
            if(orbit&&previewReadiness.ready()) {
                cameraAngle+=Math.min(.1,elapsed/1_000_000_000d)*.35;updateModelCamera();
            }
            Animator animator=viewer==null?null:viewer.getAnimator();
            if(animator!=null&&animator.getAnimationCount()>0){
                clipClock.frame(frameTimeNanos,!paused&&previewReadiness.ready(),clipDuration());applyModelPose();updatePlaybackUi();
            }
            boolean submitted=previewDriver.render(frameTimeNanos);
            observePreviewSubmission(previewDriver.progress(),submitted);
        }
        Choreographer.getInstance().postFrameCallback(this);
    }
    @Override protected void onDestroy(){
        destroyed=true;cancelModelLoad();cinematicLoadGeneration++;cinematicWorker.shutdownNow();main.removeCallbacksAndMessages(null);
        loopActive=false;
        Choreographer.getInstance().removeFrameCallback(this);
        destroyViewer();super.onDestroy();
    }
    private void destroyViewer(){
        previewDriver=null;
        try{if(viewer!=null){ModelViewer owned=viewer;try{if(cinematic)releaseCinematicScene();}catch(Exception|LinkageError error){ActivityLog.add(this,"user","3D scene cleanup stopped",error.getClass().getSimpleName(),"failed",null,null,project==null?"":project.id);}finally{viewer=null;engine=null;uiHelper=null;owned.destroy();}}else{if(uiHelper!=null)uiHelper.detach();if(engine!=null)engine.destroy();engine=null;uiHelper=null;}}
        catch(Exception|LinkageError error){ActivityLog.add(this,"user","3D cleanup stopped",error.getClass().getSimpleName(),"failed",null,null,project==null?"":project.id);}
    }
    @Override protected void onSaveInstanceState(Bundle state){super.onSaveInstanceState(state);state.putBoolean("paused",paused);state.putInt("cinematicFrame",cinematicFrame);state.putString("modelId",loadedModelId);state.putInt("animationIndex",animationIndex);state.putDouble("clipPosition",restoringModel?restoredClipPosition:clipClock.position());state.putDouble("playbackRate",clipClock.rate());state.putDouble("cameraAngle",cameraAngle);state.putDouble("cameraPitch",cameraPitch);state.putDouble("cameraDistance",cameraDistance);}
}
