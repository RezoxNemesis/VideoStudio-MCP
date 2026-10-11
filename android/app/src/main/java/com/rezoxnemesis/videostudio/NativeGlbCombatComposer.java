package com.rezoxnemesis.videostudio;

import android.content.Context;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import com.google.android.filament.*;
import com.google.android.filament.gltfio.*;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.json.JSONObject;

/**
 * GPU-backed real skinned-3D combat animation rendered into app-private frames
 * and published only through the existing Media3 engine. Uses two GLB models,
 * their OWN encoded bone animations and solver-directed root positions.
 *
 * No fabricated 2D->3D reconstruction. No Gallery scan. No online inference.
 * First release is bounded/thermal-governed; scene is a simple fixed PBR stage.
 */
public final class NativeGlbCombatComposer {
    public interface Progress {
        void onFrame(int done,int total,double time,double impact) throws Exception;
    }

    private static final int MAX_GLB_BYTES=64*1024*1024;
    private NativeGlbCombatComposer(){}

    private static final class Model {
        final FilamentAsset asset;
        final Animator animator;
        final int rootInstance;
        final float normalizedScale;
        final float[] center;
        final float bottom;
        final boolean black;
        final String[] animationNames;
        Model(FilamentAsset asset,Engine engine,boolean black) throws Exception {
            this.asset=asset;
            this.black=black;
            FilamentInstance instance=asset.getInstance();
            if(instance==null)throw new IllegalArgumentException("GLB has no renderable instance");
            animator=instance.getAnimator();
            if(instance.getSkinCount()<1)
                throw new IllegalArgumentException("GLB needs actual weighted skeletal skinning");
            if(animator==null||animator.getAnimationCount()<1)
                throw new IllegalArgumentException("GLB model needs at least one real skeletal animation clip");
            animationNames=new String[animator.getAnimationCount()];
            for(int i=0;i<animationNames.length;i++)
                animationNames[i]=animator.getAnimationName(i);
            Box bbox=asset.getBoundingBox();
            center=bbox.getCenter();
            float[] half=bbox.getHalfExtent();
            float max=Math.max(.001f,Math.max(half[0],Math.max(half[1],half[2])));
            normalizedScale=Math.max(.08f,Math.min(10f,0.8f/max));
            bottom=center[1]-half[1];
            rootInstance=engine.getTransformManager().getInstance(asset.getRoot());
            if(rootInstance==0)throw new IllegalArgumentException("GLB model has no transformable root");
        }
        void sample(Engine engine,CombatActionPlan.Frame direction,
                    CombatActionPlan plan,double duration,boolean story){
            // Different GLBs may name clips differently. Use explicitly named
            // motion types when possible; never silently claim that clip #1
            // from an unrelated model contains sword fighting.
            CombatActionClipResolver.Choice next=
                    CombatActionClipResolver.resolve(animationNames,direction.beat,black);
            int index=next.index;
            float length=Math.max(.001f,animator.getAnimationDuration(index));
            float time=(float)(direction.seconds-direction.beatStartSeconds);
            animator.applyAnimation(index,time%length);
            if(direction.beatIndex>0 && time<.16f){
                CombatActionPlan.Beat previous=CombatActionPlan.Beat.values()[direction.beatIndex-1];
                CombatActionClipResolver.Choice prev=
                        CombatActionClipResolver.resolve(animationNames,previous,black);
                if(prev.index!=index){
                    double[] knots=plan.boundaries();
                    float previousDuration=(float)((knots[direction.beatIndex]-
                            knots[direction.beatIndex-1])*duration);
                    float clipDuration=Math.max(.001f,animator.getAnimationDuration(prev.index));
                    float alpha=Math.max(0,Math.min(1,time/.16f));
                    animator.applyCrossFade(prev.index,previousDuration%clipDuration,alpha);
                }
            }
            animator.updateBoneMatrices();
            CombatRigSolver.Frame frame=CombatRigSolver.at(
                    direction.solverSeconds,duration,story);
            CombatRigSolver.Warrior fighter=black?frame.black:frame.white;
            double screenX=fighter.pelvis.x,screenY=fighter.pelvis.y;
            float x=(float)((screenX-.5)*3.25);
            float vertical= (float)(-.3*(screenY-.69));
            // Facing fighters on one stable world-space combat plane.
            float angle=(float)(black?Math.PI*.5:-Math.PI*.5);
            float cos=(float)Math.cos(angle),sin=(float)Math.sin(angle);
            float scale=normalizedScale;
            float tx=x-scale*(cos*center[0]+sin*center[2]);
            float tz=-scale*(-sin*center[0]+cos*center[2]);
            float ty=vertical-scale*bottom;
            float[] matrix={
                scale*cos,0,-scale*sin,0,
                0,scale,0,0,
                scale*sin,0,scale*cos,0,
                tx,ty,tz,1
            };
            engine.getTransformManager().setTransform(rootInstance,matrix);
        }
    }

    public static ProjectStore.Project compose(Context context,ProjectStore store,
            ProjectStore.Project source,ProjectStore.Project output,
            ProjectStore.Asset blackAsset,ProjectStore.Asset whiteAsset,
            int width,int height,int fps,double durationSeconds,
            boolean storyboardMotion,Progress progress) throws Exception {
        return compose(context,store,source,output,blackAsset,whiteAsset,
                width,height,fps,durationSeconds,storyboardMotion,new CombatActionPlan(),progress);
    }

    public static ProjectStore.Project compose(Context context,ProjectStore store,
            ProjectStore.Project source,ProjectStore.Project output,
            ProjectStore.Asset blackAsset,ProjectStore.Asset whiteAsset,
            int width,int height,int fps,double durationSeconds,
            boolean storyboardMotion,CombatActionPlan director,
            Progress progress) throws Exception {
        if(director==null)throw new IllegalArgumentException("Shared combat action director is required");
        if(context==null||store==null||source==null||output==null||
                source.id.equals(output.id)||blackAsset==null||whiteAsset==null)
            throw new IllegalArgumentException("Two registered GLB assets and separate render project are required");
        if(!"model/gltf-binary".equals(blackAsset.mime)||
                !"model/gltf-binary".equals(whiteAsset.mime)||
                source.asset(blackAsset.id)==null||source.asset(whiteAsset.id)==null)
            throw new IllegalArgumentException("Combat requires two VideoStudio-owned GLB models");
        if(fps!=24&&fps!=30||!Double.isFinite(durationSeconds)||
                durationSeconds<2||durationSeconds>4)
            throw new IllegalArgumentException("First GPU combat export supports 2-4 seconds at 24/30 FPS");
        if(width<256||height<448||width>540||height>960||
                (width&1)!=0||(height&1)!=0)
            throw new IllegalArgumentException("GPU export is bounded to a portrait preview");
        int count=(int)Math.round(fps*durationSeconds);
        if(count>120)throw new IllegalArgumentException("GPU export exceeds 120 frames");
        if(!output.assets.isEmpty()&&
                (output.sourcePrompt==null||!output.sourcePrompt.startsWith("gltf3d:")))
            throw new IllegalArgumentException("Cannot overwrite an unrelated native project");
        File dir=new File(context.getFilesDir(),
                "creative_workspace/projects/"+output.id+"/generated/gltf3d");
        if(!dir.exists()&&!dir.mkdirs())throw new IllegalStateException("Unable to create 3D frame folder");
        File[] stale=dir.listFiles();
        if(stale!=null)for(File f:stale)
            if(f.isFile()&&f.getName().matches("gltf_[0-9]{4}\\.jpg")&&!f.delete())
                throw new IllegalStateException("Cannot reset partial 3D render");
        output.assets.clear();output.clips.clear();
        output.sourcePrompt="gltf3d: real skinned mesh combat with authored GLB animations";
        store.save(output);

        File blackFile=glbFile(context,source,blackAsset);
        File whiteFile=glbFile(context,source,whiteAsset);
        Filament.init();
        Engine engine=null;
        Renderer renderer=null;
        Scene scene=null;
        com.google.android.filament.View view=null;
        SwapChain swap=null;
        AssetLoader loader=null;
        ResourceLoader resources=null;
        UbershaderProvider material=null;
        int cameraEntity=0,lightEntity=0;
        FilamentAsset blackRaw=null,whiteRaw=null;
        try {
            engine=Engine.create();
            material=new UbershaderProvider(engine);
            loader=new AssetLoader(engine,material,EntityManager.get());
            resources=new ResourceLoader(engine);
            blackRaw=loader.createAsset(modelBytes(blackFile));
            whiteRaw=loader.createAsset(modelBytes(whiteFile));
            if(blackRaw==null||whiteRaw==null)
                throw new IllegalArgumentException("Filament rejected one of the GLB models");
            if(blackRaw.getResourceUris().length>0||whiteRaw.getResourceUris().length>0)
                throw new IllegalArgumentException("GLB must contain embedded geometry/textures for offline 3D export");
            resources.loadResources(blackRaw);
            resources.loadResources(whiteRaw);
            blackRaw.releaseSourceData();
            whiteRaw.releaseSourceData();

            Model black=new Model(blackRaw,engine,true);
            Model white=new Model(whiteRaw,engine,false);
            scene=engine.createScene();
            scene.addEntities(blackRaw.getEntities());
            scene.addEntities(whiteRaw.getEntities());
            cameraEntity=EntityManager.get().create();
            Camera camera=engine.createCamera(cameraEntity);
            camera.setProjection(57,width/(double)height,.10,45,Camera.Fov.VERTICAL);
            camera.lookAt(0,1.15,5.4,0,.88,0,0,1,0);
            lightEntity=EntityManager.get().create();
            new LightManager.Builder(LightManager.Type.SUN)
                    .color(1.0f,.73f,.46f).intensity(110000)
                    .direction(.3f,-.7f,-.5f).castShadows(true)
                    .build(engine,lightEntity);
            scene.addEntity(lightEntity);
            view=engine.createView();
            view.setCamera(camera);
            view.setScene(scene);
            view.setViewport(new Viewport(0,0,width,height));
            renderer=engine.createRenderer();
            Renderer.ClearOptions clear=new Renderer.ClearOptions();
            clear.clearColor=new double[]{.10,.038,.025,1.0};
            clear.clear=true;
            renderer.setClearOptions(clear);
            swap=engine.createSwapChain(width,height,SwapChainFlags.CONFIG_READABLE);
            long frameMs=Math.max(34,Math.round(1000.0/fps));
            Handler callbackHandler=new Handler(Looper.getMainLooper());
            for(int index=0;index<count;index++){
                if(Thread.currentThread().isInterrupted())throw new InterruptedException();
                double seconds=index*durationSeconds/Math.max(1,count-1);
                CombatActionPlan.Frame action=director.at(seconds,durationSeconds);
                black.sample(engine,action,director,durationSeconds,storyboardMotion);
                white.sample(engine,action,director,durationSeconds,storyboardMotion);
                double cameraDistance=action.cameraDistance;
                double cameraYaw=action.cameraOrbitRadians;
                camera.lookAt(Math.sin(cameraYaw)*cameraDistance,
                        1.15+action.cameraShake*.6,
                        Math.cos(cameraYaw)*cameraDistance,
                        0,.88,0,0,1,0);
                ByteBuffer rgba=ByteBuffer.allocateDirect(width*height*4).order(ByteOrder.nativeOrder());
                CountDownLatch readback=new CountDownLatch(1);
                long frameClockNs=1_000_000_000L+
                        (long)(seconds*1_000_000_000L);
                boolean began=false;
                for(int attempt=0;attempt<5&&!began;attempt++){
                    began=renderer.beginFrame(swap,frameClockNs+attempt*1_000_000L);
                    if(!began)Thread.sleep(18);
                }
                if(!began)throw new IllegalStateException("Filament GPU refused to begin export frame");
                try{
                    renderer.render(view);
                    Texture.PixelBufferDescriptor buffer=new Texture.PixelBufferDescriptor(
                            rgba,Texture.Format.RGBA,Texture.Type.UBYTE,
                            1,0,0,width,callbackHandler,readback::countDown);
                    renderer.readPixels(0,0,width,height,buffer);
                }finally{renderer.endFrame();}
                engine.flushAndWait();
                if(!readback.await(10,TimeUnit.SECONDS))
                    throw new IllegalStateException("GPU frame readback timed out");
                rgba.position(0);
                int[] topLeft=NativeGpuReadback.toTopLeftArgb(rgba,width,height);
                Bitmap bitmap=Bitmap.createBitmap(topLeft,width,height,Bitmap.Config.ARGB_8888);
                File file=new File(dir,String.format(Locale.US,"gltf_%04d.jpg",index));
                boolean encoded;
                try(FileOutputStream out=new FileOutputStream(file)){
                    encoded=bitmap.compress(Bitmap.CompressFormat.JPEG,90,out);
                    out.flush();
                }finally{bitmap.recycle();}
                if(!encoded||file.length()<100)
                    throw new IllegalStateException("Generated 3D frame is empty");

                ProjectStore.Asset asset=new ProjectStore.Asset();
                asset.id=UUID.randomUUID().toString();
                asset.uri=Uri.fromFile(file).toString();
                asset.name=file.getName();
                asset.mime="image/jpeg";
                asset.role="generated_skinned_3d";
                asset.generated=true;
                asset.sizeBytes=file.length();
                asset.seekable=true;asset.persistedReadAccess=true;
                asset.generationMetadata=new JSONObject();
                asset.generationMetadata.put("engine","filament-glb-headless-skinned-3d");
                asset.generationMetadata.put("sourceBlackGlbId",blackAsset.id);
                asset.generationMetadata.put("sourceWhiteGlbId",whiteAsset.id);
                asset.generationMetadata.put("real3DGeometry",true);
                asset.generationMetadata.put("authoredGltfAnimations",true);
                asset.generationMetadata.put("frameIndex",index);
                asset.generationMetadata.put("actionBeat",action.beat.name());
                asset.generationMetadata.put("actionCue",director.cueAt(seconds,durationSeconds));
                asset.generationMetadata.put("blackMatchedClip",
                        CombatActionClipResolver.resolve(black.animationNames,action.beat,true).semanticMatch);
                asset.generationMetadata.put("whiteMatchedClip",
                        CombatActionClipResolver.resolve(white.animationNames,action.beat,false).semanticMatch);
                output.assets.add(asset);
                ProjectStore.Clip clip=new ProjectStore.Clip();
                clip.id=UUID.randomUUID().toString();
                clip.assetId=asset.id;
                clip.inMs=0;clip.outMs=frameMs;
                clip.speed=1f;clip.volume=0;
                clip.transition="none";clip.title="";
                clip.effects=new JSONObject();
                clip.effects.put("directorSequenceFrame",true);
                clip.effects.put("poseFps",fps);
                output.clips.add(clip);
                if(progress!=null){
                    double impact=CombatRigSolver.at(seconds,durationSeconds,storyboardMotion).impactIntensity;
                    progress.onFrame(index+1,count,seconds,impact);
                }
            }
            store.save(output);
            return output;
        }finally{
            try{if(scene!=null&&blackRaw!=null)scene.removeEntities(blackRaw.getEntities());}catch(Exception ignored){}
            try{if(scene!=null&&whiteRaw!=null)scene.removeEntities(whiteRaw.getEntities());}catch(Exception ignored){}
            try{if(loader!=null&&blackRaw!=null)loader.destroyAsset(blackRaw);}catch(Exception ignored){}
            try{if(loader!=null&&whiteRaw!=null)loader.destroyAsset(whiteRaw);}catch(Exception ignored){}
            try{if(resources!=null)resources.destroy();}catch(Exception ignored){}
            try{if(loader!=null)loader.destroy();}catch(Exception ignored){}
            try{if(material!=null)material.destroyMaterials();}catch(Exception ignored){}
            try{if(material!=null)material.destroy();}catch(Exception ignored){}
            if(engine!=null){
                try{if(lightEntity!=0)engine.destroyEntity(lightEntity);}catch(Exception ignored){}
                try{if(cameraEntity!=0)engine.destroyEntity(cameraEntity);}catch(Exception ignored){}
                try{if(view!=null)engine.destroyView(view);}catch(Exception ignored){}
                try{if(scene!=null)engine.destroyScene(scene);}catch(Exception ignored){}
                try{if(renderer!=null)engine.destroyRenderer(renderer);}catch(Exception ignored){}
                try{if(swap!=null)engine.destroySwapChain(swap);}catch(Exception ignored){}
                try{engine.flushAndWait();}catch(Exception ignored){}
                try{engine.destroy();}catch(Exception ignored){}
            }
        }
    }

    /**
     * Bind ONLY a registered project asset backed by that project's private
     * models directory. A forged "file://" URI, path traversal, symlink or
     * the user's Gallery are never allowed as a hidden model input.
     */
    static File glbFile(Context context,ProjectStore.Project project,
                        ProjectStore.Asset asset) throws Exception {
        if(context==null||project==null||asset==null||
                !"model/gltf-binary".equals(asset.mime)||asset.uri==null||
                project.asset(asset.id)==null)
            throw new IllegalArgumentException("Only registered VideoStudio GLBs can be rendered");
        Uri uri=Uri.parse(asset.uri);
        if(!"file".equals(uri.getScheme())||uri.getPath()==null)
            throw new IllegalArgumentException("Import GLB to the private VideoStudio project first");
        File expectedFolder=new File(context.getFilesDir(),
                "creative_workspace/projects/"+project.id+"/models").getCanonicalFile();
        File file=new File(uri.getPath()).getCanonicalFile();
        String canonicalParent=file.getParent();
        if(canonicalParent==null||!canonicalParent.equals(expectedFolder.getPath()))
            throw new IllegalArgumentException("3D models must belong to the selected private project");
        if(!file.isFile()||file.length()<20||file.length()>MAX_GLB_BYTES)
            throw new IllegalArgumentException("GLB asset missing or exceeds the 64 MB limit");
        FilamentGlbActivity.verifyGlb(file);
        return file;
    }

    private static ByteBuffer modelBytes(File f) throws Exception {
        if(f.length()>MAX_GLB_BYTES)throw new IllegalArgumentException("Oversized 3D model");
        ByteBuffer bytes=ByteBuffer.allocateDirect((int)f.length()).order(ByteOrder.LITTLE_ENDIAN);
        try(FileInputStream in=new FileInputStream(f)){
            byte[] part=new byte[128*1024];int n;
            while((n=in.read(part))!=-1)bytes.put(part,0,n);
        }
        bytes.flip();
        return bytes;
    }
}
