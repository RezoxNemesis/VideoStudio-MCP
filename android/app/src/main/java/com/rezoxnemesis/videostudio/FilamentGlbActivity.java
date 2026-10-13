package com.rezoxnemesis.videostudio;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.OpenableColumns;
import android.database.Cursor;
import android.view.Choreographer;
import android.view.Gravity;
import android.view.SurfaceView;
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
    private TextView status,modelHint;
    private final Handler main=new Handler(Looper.getMainLooper());
    private boolean loopActive=false,orbit=false,paused=false;
    private long lastRenderedAt=0;
    private long animationStartedAt=0;
    private int animationIndex=0;
    private double cameraAngle=0;
    private boolean cinematic;
    private volatile boolean destroyed;
    private boolean cinematicLoading,cinematicUnavailable,cinematicResourcesReady;
    private int cinematicLoadGeneration,cinematicFrame,cinematicFrameCount,cinematicFps;
    private int cinematicShotIndex=-1,cinematicLastRenderedFrame=-1,cinematicPlayStartFrame,cinematicFallbackLight;
    private long cinematicPlayStartedAt,cinematicResourceStartedAt;
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
        cinematicProgramId=getIntent().getStringExtra("cinematicProgramId");
        cinematic=cinematicProgramId!=null&&!cinematicProgramId.isEmpty();
        cinematicProgramSha=getIntent().getStringExtra("expectedProgramSha256");
        String projectId=getIntent().getStringExtra("projectId");
        project=projectId==null?projectStore.active():projectStore.get(projectId);
        if(project==null){finish();return;}
        try{
            Native3DRuntime.initialize();
            engine=Engine.create();
            uiHelper=new UiHelper(UiHelper.ContextErrorPolicy.DONT_CHECK);
            buildUi();
        }catch(Exception|LinkageError|OutOfMemoryError error){show3DError(error);}

    }

    private void show3DError(Throwable error){
        loopActive=false;Choreographer.getInstance().removeFrameCallback(this);
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
        surface=new SurfaceView(this);modelFrame.addView(surface,new FrameLayout.LayoutParams(-1,-1));
        modelHint=new TextView(this);modelHint.setText("No 3D model loaded in this project.\nUse Import GLB or Existing model to open your geometry.");modelHint.setTextColor(0xffb9ccdf);modelHint.setTextSize(15);modelHint.setGravity(Gravity.CENTER);modelHint.setPadding(dp(24),dp(24),dp(24),dp(24));modelFrame.addView(modelHint,new FrameLayout.LayoutParams(-1,-1));
        viewer=new ModelViewer(surface,engine,uiHelper,null);

        LinearLayout second=new LinearLayout(this);
        second.addView(action("Play / Pause",v->togglePlayback()),
                new LinearLayout.LayoutParams(0,dp(50),1));
        second.addView(action("Next motion",v->nextAnimation()),
                new LinearLayout.LayoutParams(0,dp(50),1));
        second.addView(action("Orbit camera",v->{orbit=!orbit;}),
                new LinearLayout.LayoutParams(0,dp(50),1));
        root.addView(second);
        status=new TextView(this);
        status.setText("Choose a VideoStudio GLB or import one. A rigged model can contain multiple reusable animation clips.");
        status.setTextColor(0xff5adff4);
        status.setTextSize(12);
        status.setPadding(14,9,14,22);
        root.addView(status);
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
        // ModelViewer's standalone default sunlight is not part of the authored scene.
        viewer.getScene().remove(viewer.getLight());surface.setOnTouchListener((v,event)->true);
        Renderer.ClearOptions clear=new Renderer.ClearOptions();clear.clear=true;clear.clearColor=new double[]{.045,.055,.075,1};viewer.getRenderer().setClearOptions(clear);
        cinematicScrub=new SeekBar(this);cinematicScrub.setEnabled(false);cinematicScrub.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){
            @Override public void onProgressChanged(SeekBar bar,int progress,boolean fromUser){if(!fromUser||cinematicLoading||cinematicUnavailable||cinematicTimeline==null)return;paused=true;cinematicPlayStartedAt=0;cinematicFrame=Math.max(0,Math.min(cinematicFrameCount-1,progress));requestCinematicFrame();}
            @Override public void onStartTrackingTouch(SeekBar bar){paused=true;cinematicPlayStartedAt=0;}
            @Override public void onStopTrackingTouch(SeekBar bar){}
        });root.addView(cinematicScrub);
        LinearLayout actions=new LinearLayout(this);actions.addView(action("Play / Pause",v->togglePlayback()),new LinearLayout.LayoutParams(0,dp(50),1));actions.addView(action("Restart",v->{if(cinematicLoading||cinematicUnavailable)return;cinematicFrame=0;cinematicPlayStartedAt=0;paused=false;requestCinematicFrame();}),new LinearLayout.LayoutParams(0,dp(50),1));actions.addView(action("Return",v->finish()),new LinearLayout.LayoutParams(0,dp(50),1));root.addView(actions);
        status=new TextView(this);status.setText("Verifying the saved source, program and scene bundle…");status.setTextColor(0xff5adff4);status.setTextSize(12);status.setPadding(14,9,14,22);root.addView(status);setContentView(root);loadCinematicProgram();
    }

    private JSONObject cinematicRequest() throws Exception{
        if(cinematicProgramSha==null||!cinematicProgramSha.matches("[0-9a-f]{64}"))throw new IllegalArgumentException("An exact saved program hash is required for cinematic preview");
        return new JSONObject().put("projectId",project.id).put("programId",cinematicProgramId).put("expectedProgramSha256",cinematicProgramSha);
    }
    private void loadCinematicProgram(){
        cinematicLoading=true;int generation=++cinematicLoadGeneration;
        cinematicWorker.execute(()->{JSONObject program=null,bundle=null;Throwable problem=null;
            try{program=Cinematic3DProgramStore.get(getApplicationContext(),projectStore,cinematicRequest());bundle=NativeCinematicBundleStore.get(getApplicationContext(),projectStore,new JSONObject().put("projectId",project.id).put("bundleId",program.getString("bundleId")));}
            catch(Exception|OutOfMemoryError error){problem=error;}
            final JSONObject acceptedProgram=program,acceptedBundle=bundle;final Throwable failure=problem;
            main.post(()->{if(destroyed||generation!=cinematicLoadGeneration)return;cinematicLoading=false;
                if(failure!=null){cinematicFailure(failure);return;}
                try{cinematicProgram=acceptedProgram;cinematicBundle=acceptedBundle;cinematicTimeline=Cinematic3DTimeline.validate(acceptedProgram.getJSONObject("timeline"));JSONObject plan=Cinematic3DTimeline.compile(cinematicTimeline);cinematicShots=plan.getJSONArray("shots");cinematicFps=plan.getInt("fps");cinematicFrameCount=plan.getInt("totalFrames");cinematicScrub.setMax(cinematicFrameCount-1);cinematicFrame=0;cinematicPlayStartedAt=0;requestCinematicFrame();}
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
                        cinematicResourcesReady=false;cinematicResourceStartedAt=android.os.SystemClock.elapsedRealtime();cinematicScrub.setEnabled(true);applyCinematicFrame(cinematicFrameBinding());cinematicLastRenderedFrame=-1;cinematicPlayStartFrame=cinematicFrame;cinematicPlayStartedAt=0;
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
    private void cinematicFailure(Throwable error){paused=true;cinematicUnavailable=true;cinematicPlayStartedAt=0;cinematicLoading=false;cinematicScrub.setEnabled(false);try{releaseCinematicScene();}catch(RuntimeException ignored){}status.setText("Cinematic preview unavailable: "+(error.getMessage()==null?error.getClass().getSimpleName():error.getMessage()));}
    private void releaseCinematicScene(){
        if(viewer==null)return;viewer.getView().setCamera(viewer.getCamera());cinematicCamera=null;
        if(cinematicFallbackLight!=0){viewer.getScene().remove(cinematicFallbackLight);engine.destroyEntity(cinematicFallbackLight);EntityManager.get().destroy(cinematicFallbackLight);cinematicFallbackLight=0;}
        if(viewer.getAsset()!=null)for(int entity:viewer.getAsset().getLightEntities())viewer.getScene().remove(entity);
        viewer.destroyModel();cinematicShotIndex=-1;cinematicScene=null;cinematicResourcesReady=false;
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
        new Thread(()->{
            try{
                ProjectStore.Asset asset=storeSelectedFile(chosen,name);
                main.post(()->{if(destroyed)return;project=projectStore.get(project.id);loadRegistered(asset);});
            }catch(Exception error){
                main.post(()->{if(!destroyed)status.setText("3D import failed: "+error.getMessage());});
            }
        },"VideoStudio-glb-import").start();
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
        long bytes=0;
        try(InputStream in=getContentResolver().openInputStream(uri);
            FileOutputStream out=new FileOutputStream(file)){
            if(in==null)throw new IllegalArgumentException("The selected GLB cannot be read");
            byte[] buffer=new byte[256*1024];int length;
            while((length=in.read(buffer))!=-1){
                if(Thread.currentThread().isInterrupted())throw new InterruptedException();
                bytes+=length;
                if(bytes>MAX_GLB_BYTES)
                    throw new IllegalArgumentException("For mobile safety, GLB previews are limited to 64 MB");
                out.write(buffer,0,length);
            }
            out.getFD().sync();
        }catch(Exception error){file.delete();throw error;}
        if(bytes<20){file.delete();throw new IllegalArgumentException("GLB is empty");}
        verifyGlb(file);
        ProjectStore.Asset asset=new ProjectStore.Asset();
        asset.id=assetId;asset.name=name;asset.mime="model/gltf-binary";
        asset.uri=Uri.fromFile(file).toString();asset.sizeBytes=bytes;
        asset.seekable=true;asset.persistedReadAccess=true;asset.role="source_glb";
        synchronized(projectStore){
            ProjectStore.Project fresh=projectStore.get(project.id);
            if(fresh==null)throw new IllegalArgumentException("VideoStudio project no longer exists");
            fresh.assets.add(asset);
            projectStore.save(fresh);
        }
        return asset;
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
        if(destroyed||viewer==null||asset==null)return;
        status.setText("Loading 3D scene and skinned materials...");
        try{
            File file=new File(Uri.parse(asset.uri).getPath());
            if(!file.isFile()||file.length()>MAX_GLB_BYTES)
                throw new IllegalArgumentException("Model is missing or too large");
            verifyGlb(file);
            ByteBuffer buffer=ByteBuffer.allocateDirect((int)file.length());
            try(FileInputStream in=new FileInputStream(file)){
                byte[] part=new byte[128*1024];int n;
                while((n=in.read(part))!=-1)buffer.put(part,0,n);
            }
            buffer.flip();
            viewer.loadModelGlb(buffer);
            if(viewer.getAsset()==null)
                throw new IllegalArgumentException("Filament could not create the model asset");
            if(normalizeSceneForPreview(cinematic))viewer.resetToDefaultState();
            if(modelHint!=null)modelHint.setVisibility(View.GONE);
            paused=false;animationIndex=0;cameraAngle=0;
            animationStartedAt=0;
            Animator animations=viewer.getAnimator();
            int count=animations==null?0:animations.getAnimationCount();
            status.setText(asset.name+" • real glTF skeletal/PBR model • "+count+
                    " embedded animation(s). "+(count==0
                    ?"Rig has no authored animation: import a model with animation clips."
                    :"Playing native skinned 3D motion."));
        }catch(Exception|LinkageError|OutOfMemoryError error){
            status.setText("Cannot load 3D model: "+error.getClass().getSimpleName()+": "+error.getMessage());
        }
    }
    private void togglePlayback(){
        if(cinematic){if(cinematicLoading||cinematicUnavailable||cinematicTimeline==null)return;paused=!paused;cinematicPlayStartFrame=cinematicFrame;cinematicPlayStartedAt=0;return;}
        paused=!paused;
        if(!paused)animationStartedAt=0;
    }
    private void nextAnimation(){
        if(viewer==null||viewer.getAnimator()==null)return;
        int count=viewer.getAnimator().getAnimationCount();
        if(count<1)return;
        animationIndex=(animationIndex+1)%count;
        paused=false;
        animationStartedAt=0;
        status.setText("Playing actual glTF animation "+(animationIndex+1)+"/"+count+
                ": "+viewer.getAnimator().getAnimationName(animationIndex));
    }
    @Override protected void onResume(){
        super.onResume();loopActive=viewer!=null;
        Choreographer.getInstance().postFrameCallback(this);
    }
    @Override protected void onPause(){
        loopActive=false;
        if(cinematic)cinematicPlayStartedAt=0;
        Choreographer.getInstance().removeFrameCallback(this);
        super.onPause();
    }
    @Override public void doFrame(long frameTimeNanos){
        try{renderFrame(frameTimeNanos);}catch(Exception|LinkageError|OutOfMemoryError error){show3DError(error);}
    }
    private void renderFrame(long frameTimeNanos){
        if(!loopActive||viewer==null)return;
        if(cinematic){
            if(!cinematicLoading&&!cinematicUnavailable&&cinematicResourcesReady&&cinematicTimeline!=null&&!paused){if(cinematicPlayStartedAt==0){cinematicPlayStartedAt=frameTimeNanos;cinematicPlayStartFrame=cinematicFrame;}long elapsed=frameTimeNanos-cinematicPlayStartedAt;long advance=elapsed/1_000_000_000L*cinematicFps+(elapsed%1_000_000_000L)*cinematicFps/1_000_000_000L;cinematicFrame=(int)Math.min(cinematicFrameCount-1,(long)cinematicPlayStartFrame+advance);if(cinematicFrame==cinematicFrameCount-1)paused=true;}
            boolean frameChanged=!cinematicLoading&&cinematicFrame!=cinematicLastRenderedFrame;
            if(frameChanged||frameTimeNanos-lastRenderedAt>=1_000_000_000L/Math.max(1,cinematicFps==0?30:cinematicFps)){lastRenderedAt=frameTimeNanos;
                if(!cinematicLoading&&!cinematicUnavailable&&cinematicTimeline!=null){
                    requestCinematicFrame();
                }
                if(viewer.getAsset()!=null){viewer.render(frameTimeNanos);if(!cinematicLoading){cinematicLastRenderedFrame=cinematicFrame;if(!cinematicResourcesReady){if(viewer.getProgress()>=1f){cinematicResourcesReady=true;cinematicPlayStartedAt=0;}else if(android.os.SystemClock.elapsedRealtime()-cinematicResourceStartedAt>30_000)cinematicFailure(new IllegalStateException("Scene material loading timed out"));else status.setText("Preparing authored scene materials…");}}}
            }
            Choreographer.getInstance().postFrameCallback(this);return;
        }
        if(viewer.getAsset()==null){Choreographer.getInstance().postFrameCallback(this);return;}
        // 30fps GPU cap protects battery/thermal headroom on phone.
        if(frameTimeNanos-lastRenderedAt>=33_000_000L){
            lastRenderedAt=frameTimeNanos;
            if(orbit) {
                cameraAngle+=.009;
                double cx=3.5*Math.sin(cameraAngle);
                double cz=-4+3.5*Math.cos(cameraAngle);
                viewer.getCamera().lookAt(cx,1.2,cz,0,0,-4,0,1,0);
            }
            Animator animator=viewer.getAnimator();
            if(!paused&&animator!=null&&animator.getAnimationCount()>0){
                if(animationStartedAt==0)animationStartedAt=frameTimeNanos;
                float elapsed=(frameTimeNanos-animationStartedAt)/1_000_000_000f;
                float duration=Math.max(.001f,animator.getAnimationDuration(animationIndex));
                animator.applyAnimation(animationIndex,elapsed%duration);
                animator.updateBoneMatrices();
            }
            viewer.render(frameTimeNanos);
        }
        Choreographer.getInstance().postFrameCallback(this);
    }
    @Override protected void onDestroy(){
        destroyed=true;cinematicLoadGeneration++;cinematicWorker.shutdownNow();main.removeCallbacksAndMessages(null);
        loopActive=false;
        Choreographer.getInstance().removeFrameCallback(this);
        try{if(viewer!=null){if(cinematic)releaseCinematicScene();viewer.destroy();viewer=null;engine=null;uiHelper=null;}else{if(uiHelper!=null)uiHelper.detach();if(engine!=null)engine.destroy();}}
        catch(Exception|LinkageError error){ActivityLog.add(this,"user","3D cleanup stopped",error.getClass().getSimpleName(),"failed",null,null,project==null?"":project.id);}
        super.onDestroy();
    }
}
