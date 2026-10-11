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
import android.view.SurfaceView;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import com.google.android.filament.Engine;
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
    private TextView status;
    private final Handler main=new Handler(Looper.getMainLooper());
    private boolean loopActive=false,orbit=false,paused=false;
    private long lastRenderedAt=0;
    private int animationIndex=0;
    private double cameraAngle=0;

    @Override protected void onCreate(Bundle state){
        super.onCreate(state);
        getWindow().setStatusBarColor(0xff090d17);
        getWindow().setNavigationBarColor(0xff090d17);
        projectStore=new ProjectStore(this);
        String projectId=getIntent().getStringExtra("projectId");
        project=projectId==null?projectStore.active():projectStore.get(projectId);
        if(project==null){finish();return;}
        try{
            Filament.init();
            engine=Engine.create();
            uiHelper=new UiHelper(UiHelper.ContextErrorPolicy.DONT_CHECK);
        }catch(Throwable error){
            Toast.makeText(this,"3D engine unavailable: "+error.getMessage(),
                    Toast.LENGTH_LONG).show();
            finish();return;
        }
        buildUi();
    }

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
        first.addView(action("Import GLB",v->selectGlb()),new LinearLayout.LayoutParams(0,50,1));
        first.addView(action("Existing model",v->chooseExisting()),new LinearLayout.LayoutParams(0,50,1));
        first.addView(action("Return",v->finish()),new LinearLayout.LayoutParams(0,50,1));
        root.addView(first);

        surface=new SurfaceView(this);
        root.addView(surface,new LinearLayout.LayoutParams(-1,0,1));
        viewer=new ModelViewer(surface,engine,uiHelper,null);

        LinearLayout second=new LinearLayout(this);
        second.addView(action("Play / Pause",v->togglePlayback()),
                new LinearLayout.LayoutParams(0,50,1));
        second.addView(action("Next motion",v->nextAnimation()),
                new LinearLayout.LayoutParams(0,50,1));
        second.addView(action("Orbit camera",v->{orbit=!orbit;}),
                new LinearLayout.LayoutParams(0,50,1));
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
                main.post(()->{project=projectStore.get(project.id);loadRegistered(asset);});
            }catch(Exception error){
                main.post(()->status.setText("3D import failed: "+error.getMessage()));
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
        if(viewer==null||asset==null)return;
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
            viewer.resetToDefaultState();
            viewer.setAutoPlayAnimations(true);
            paused=false;animationIndex=0;cameraAngle=0;
            Animator animations=viewer.getAnimator();
            int count=animations==null?0:animations.getAnimationCount();
            status.setText(asset.name+" • real glTF skeletal/PBR model • "+count+
                    " embedded animation(s). "+(count==0
                    ?"Rig has no authored animation: import a model with animation clips."
                    :"Playing native skinned 3D motion."));
        }catch(Exception error){
            status.setText("Cannot load 3D model: "+error.getMessage());
        }
    }
    private void togglePlayback(){
        paused=!paused;
        if(viewer!=null)viewer.setAutoPlayAnimations(!paused);
    }
    private void nextAnimation(){
        if(viewer==null||viewer.getAnimator()==null)return;
        int count=viewer.getAnimator().getAnimationCount();
        if(count<1)return;
        animationIndex=(animationIndex+1)%count;
        viewer.setActiveAnimationIndex(animationIndex);
        viewer.setAutoPlayAnimations(true);
        paused=false;
        status.setText("Playing actual glTF animation "+(animationIndex+1)+"/"+count+
                ": "+viewer.getAnimator().getAnimationName(animationIndex));
    }
    @Override protected void onResume(){
        super.onResume();loopActive=true;
        Choreographer.getInstance().postFrameCallback(this);
    }
    @Override protected void onPause(){
        loopActive=false;
        Choreographer.getInstance().removeFrameCallback(this);
        super.onPause();
    }
    @Override public void doFrame(long frameTimeNanos){
        if(!loopActive||viewer==null)return;
        // 30fps GPU cap protects battery/thermal headroom on phone.
        if(frameTimeNanos-lastRenderedAt>=33_000_000L){
            lastRenderedAt=frameTimeNanos;
            if(orbit) {
                cameraAngle+=.009;
                double cx=3.5*Math.sin(cameraAngle);
                double cz=-4+3.5*Math.cos(cameraAngle);
                viewer.getCamera().lookAt(cx,1.2,cz,0,0,-4,0,1,0);
            }
            viewer.render(frameTimeNanos);
        }
        Choreographer.getInstance().postFrameCallback(this);
    }
    @Override protected void onDestroy(){
        loopActive=false;
        Choreographer.getInstance().removeFrameCallback(this);
        if(viewer!=null){
            viewer.destroyModel();
            viewer=null;
        }
        if(uiHelper!=null)uiHelper.detach();
        if(engine!=null){
            engine.destroy();
            engine=null;
        }
        super.onDestroy();
    }
}
