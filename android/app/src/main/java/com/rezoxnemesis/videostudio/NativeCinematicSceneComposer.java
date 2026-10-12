package com.rezoxnemesis.videostudio;

import android.content.Context;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Handler;
import android.os.HandlerThread;
import com.google.android.filament.Camera;
import com.google.android.filament.Engine;
import com.google.android.filament.EntityManager;
import com.google.android.filament.Filament;
import com.google.android.filament.Renderer;
import com.google.android.filament.Scene;
import com.google.android.filament.SwapChain;
import com.google.android.filament.SwapChainFlags;
import com.google.android.filament.Texture;
import com.google.android.filament.View;
import com.google.android.filament.Viewport;
import com.google.android.filament.gltfio.Animator;
import com.google.android.filament.gltfio.AssetLoader;
import com.google.android.filament.gltfio.FilamentAsset;
import com.google.android.filament.gltfio.ResourceLoader;
import com.google.android.filament.gltfio.UbershaderProvider;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.json.JSONArray;
import org.json.JSONObject;

/** Native full-scene GLB replay. GPU/device execution evidence is separate from JVM fixtures. */
public final class NativeCinematicSceneComposer {
    public interface Progress {
        /** Called before GPU work and after each captured frame; exceptions cancel the generation. */
        void onFrame(int done,int total,double seconds,double impact) throws Exception;
    }
    private static final int MAX_GLB_BYTES=16*1024*1024;
    private static final int MAX_PIXELS=720*1280;
    private NativeCinematicSceneComposer() {}

    public static int requireDimensions(int width,int height) {
        if(width<256||height<256||(width&1)!=0||(height&1)!=0||
                Math.min(width,height)>720||Math.max(width,height)>1280||(long)width*height>MAX_PIXELS)
            throw new IllegalArgumentException("Cinematic GPU capture requires an even raster within 720p");
        return Math.multiplyExact(Math.multiplyExact(width,height),4);
    }

    /** Reuses the caller's array and never changes the direct buffer's position. */
    public static void copyTopLeftArgb(ByteBuffer rgba,int width,int height,int[] pixels) {
        if(rgba==null||pixels==null||width<=0||height<=0||(long)width*height>MAX_PIXELS||
                pixels.length<(long)width*height||rgba.remaining()<(long)width*height*4)
            throw new IllegalArgumentException("Invalid bounded cinematic GPU readback");
        int start=rgba.position(),stride=width*4;
        for(int y=0;y<height;y++) for(int x=0;x<width;x++) {
            int offset=start+(height-1-y)*stride+x*4;
            pixels[y*width+x]=((rgba.get(offset+3)&255)<<24)|((rgba.get(offset)&255)<<16)|
                    ((rgba.get(offset+1)&255)<<8)|(rgba.get(offset+2)&255);
        }
    }

    public static JSONObject clockMetadata(int frameIndex,int fps) throws Exception {
        return new JSONObject().put("frameClock","rational-authored-v1").put("frameIndex",frameIndex)
                .put("fps",NativeFrameClock.requireFps(fps))
                .put("presentationTimeUs",NativeFrameClock.presentationTimeUs(frameIndex,fps))
                .put("frameDurationUs",NativeFrameClock.frameDurationUs(frameIndex,fps));
    }

    public static void requireNewOutput(ProjectStore.Project source,ProjectStore.Project output) {
        if(source==null||output==null||source.id==null||output.id==null||source.id.equals(output.id)||
                !output.assets.isEmpty()||!output.clips.isEmpty()||!output.studioLayers.isEmpty()||
                (output.latestExportUri!=null&&!output.latestExportUri.isEmpty()))
            throw new IllegalArgumentException("A separate empty cinematic output project is required");
    }

    public static File createGenerationDirectory(Context context,String outputId,String generationId) throws Exception {
        if(context==null||!uuid(outputId)||!uuid(generationId))
            throw new IllegalArgumentException("Private project and generation UUIDs are required");
        File parent=new File(context.getFilesDir().getCanonicalFile(),"creative_workspace/projects/"+outputId+"/generated/cinematic3d");
        File canonicalParent=parent.getCanonicalFile();
        if(!parent.getAbsoluteFile().equals(canonicalParent))
            throw new IllegalArgumentException("Cinematic generation folders cannot contain symlinks");
        if(!parent.isDirectory()&&!parent.mkdirs())throw new IllegalStateException("Cannot create cinematic generation parent");
        File generation=new File(parent,generationId);
        if(!generation.mkdir())throw new IllegalStateException("An immutable cinematic generation cannot be reused");
        return generation;
    }

    private static boolean uuid(String value) {
        return value!=null&&value.matches("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");
    }

    public static ProjectStore.Project compose(Context context,ProjectStore store,ProjectStore.Project source,
            ProjectStore.Project output,JSONObject bundle,JSONObject program,int width,int height,Progress progress) throws Exception {
        if(context==null||store==null||bundle==null||program==null)
            throw new IllegalArgumentException("A verified native cinematic program and bundle are required");
        requireNewOutput(source,output);
        int readbackBytes=requireDimensions(width,height);
        // Snapshot caller-owned JSON and admission before folders, native engines or callbacks exist.
        JSONObject programSnapshot=new JSONObject(program.toString()),bundleSnapshot=new JSONObject(bundle.toString());
        Cinematic3DProgramStore.requireUnchanged(context,store,programSnapshot,bundleSnapshot);
        if(!source.id.equals(programSnapshot.getString("projectId"))||
                source.updatedAt!=programSnapshot.getLong("sourceProjectUpdatedAt"))
            throw new IllegalStateException("SOURCE_PROJECT_CHANGED: cinematic render revision differs");
        JSONObject timeline=Cinematic3DTimeline.validate(programSnapshot.getJSONObject("timeline"));
        JSONArray shots=timeline.getJSONArray("shots");
        JSONArray declaredBindings=programSnapshot.getJSONArray("scenes");
        if(declaredBindings.length()!=shots.length())
            throw new IllegalArgumentException("The saved program scene order differs from its timeline");
        int fps=NativeFrameClock.requireFps(timeline.getInt("fps")),total=0,maxFileBytes=0;
        JSONObject[] bindings=new JSONObject[shots.length()];
        File[] sceneFiles=new File[shots.length()];
        String[] sceneHashes=new String[shots.length()];
        for(int shotIndex=0;shotIndex<shots.length();shotIndex++) {
            JSONObject shot=shots.getJSONObject(shotIndex);
            bindings[shotIndex]=new JSONObject(declaredBindings.getJSONObject(shotIndex).toString());
            if(!shot.getString("id").equals(bindings[shotIndex].getString("shotId"))||
                    !shot.getString("action").equals(bindings[shotIndex].getString("action")))
                throw new IllegalArgumentException("The saved program has a changed scene binding");
            sceneFiles[shotIndex]=NativeCinematicBundleStore.sceneFile(context,source.id,
                    programSnapshot.getString("bundleId"),bindings[shotIndex].getString("path"));
            sceneHashes[shotIndex]=declaredSceneSha256(bundleSnapshot,bindings[shotIndex].getString("path"));
            long bytes=sceneFiles[shotIndex].length();
            if(bytes<20||bytes>MAX_GLB_BYTES)throw new IllegalArgumentException("Scene exceeds the 16 MiB GLB budget");
            maxFileBytes=Math.max(maxFileBytes,(int)bytes);
            total=Math.addExact(total,shot.getInt("durationFrames"));
        }
        if(total<1||total>12000)throw new IllegalArgumentException("Cinematic frame budget exceeded");
        ProjectStore.Project staged=ProjectStore.Project.fromJson(output.toJson());
        String generationId=UUID.randomUUID().toString();
        File generation=null;
        Engine engine=null;
        Renderer renderer=null;
        Scene scene=null;
        View view=null;
        SwapChain swap=null;
        AssetLoader loader=null;
        ResourceLoader resources=null;
        UbershaderProvider materials=null;
        FilamentAsset loaded=null;
        HandlerThread callbackThread=null;
        Bitmap bitmap=null;
        int fallbackLight=0;
        boolean published=false;
        try {
            checkpoint(progress,0,total,0,0);
            // A thermal wait can outlive admission; recheck after it and before filesystem/GPU effects.
            Cinematic3DProgramStore.requireUnchanged(context,store,programSnapshot,bundleSnapshot);
            generation=createGenerationDirectory(context,output.id,generationId);
            ByteBuffer rgba=ByteBuffer.allocateDirect(readbackBytes).order(ByteOrder.nativeOrder());
            int[] pixels=new int[width*height];
            bitmap=Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888);
            // One source buffer is reused across all shots; no per-frame native buffer allocation.
            ByteBuffer model=ByteBuffer.allocateDirect(maxFileBytes).order(ByteOrder.LITTLE_ENDIAN);
            byte[] transfer=new byte[128*1024];
            Filament.init();
            engine=Engine.create();
            materials=new UbershaderProvider(engine);
            loader=new AssetLoader(engine,materials,EntityManager.get());
            resources=new ResourceLoader(engine);
            scene=engine.createScene();
            view=engine.createView();
            view.setScene(scene);
            view.setViewport(new Viewport(0,0,width,height));
            renderer=engine.createRenderer();
            Renderer.ClearOptions clear=new Renderer.ClearOptions();
            clear.clearColor=new double[]{.045,.055,.075,1}; clear.clear=true;
            renderer.setClearOptions(clear);
            swap=engine.createSwapChain(width,height,SwapChainFlags.CONFIG_READABLE);
            callbackThread=new HandlerThread("Cinematic3DReadback"); callbackThread.start();
            Handler callbackHandler=new Handler(callbackThread.getLooper());
            int frameIndex=0;
            for(int shotIndex=0;shotIndex<shots.length();shotIndex++) {
                JSONObject shot=shots.getJSONObject(shotIndex),binding=bindings[shotIndex];
                checkpoint(progress,frameIndex,total,frameIndex/(double)fps,0);
                readModel(sceneFiles[shotIndex],model,transfer,sceneHashes[shotIndex]);
                loaded=loader.createAsset(model);
                if(loaded==null||loaded.getResourceUris().length>0)
                    throw new IllegalArgumentException("Filament requires an embedded full-scene GLB");
                resources.loadResources(loaded);
                loaded.releaseSourceData();
                Animator animator=loaded.getInstance()==null?null:loaded.getInstance().getAnimator();
                int animation=Cinematic3DSceneSampling.requireAuthoredScene(loaded,animator,binding);
                Camera camera=Cinematic3DSceneSampling.requireCamera(loaded,engine,binding.getString("cameraName"));
                scene.addEntities(loaded.getEntities());
                fallbackLight=Cinematic3DSceneSampling.createFallbackLight(engine,scene,loaded);
                view.setCamera(camera);
                for(int local=0;local<shot.getInt("durationFrames");local++,frameIndex++) {
                    JSONObject sample=Cinematic3DSceneSampling.frame(shot,local,fps,binding);
                    double seconds=frameIndex/(double)fps;
                    double impact=sample.getBoolean("hitStop")?1:0;
                    checkpoint(progress,frameIndex,total,seconds,impact);
                    Cinematic3DSceneSampling.apply(engine,loaded,animator,animation,camera,sample,width,height);
                    capture(engine,renderer,swap,view,rgba,width,height,frameIndex,fps,callbackHandler);
                    copyTopLeftArgb(rgba,width,height,pixels);
                    bitmap.setPixels(pixels,0,width,0,0,width,height);
                    File frameFile=new File(generation,String.format(Locale.US,"cinematic_%05d.jpg",frameIndex));
                    try(FileOutputStream encoded=new FileOutputStream(frameFile)) {
                        if(!bitmap.compress(Bitmap.CompressFormat.JPEG,90,encoded))
                            throw new IllegalStateException("Cinematic frame encoder failed");
                        encoded.flush(); encoded.getFD().sync();
                    }
                    if(frameFile.length()<100)throw new IllegalStateException("Cinematic GPU frame is empty");
                    appendFrame(staged,frameFile,generationId,programSnapshot,bundleSnapshot,shot,binding,sample,
                            frameIndex,local,fps,width,height);
                    checkpoint(progress,frameIndex+1,total,seconds,impact);
                }
                scene.removeEntities(loaded.getEntities());
                if(fallbackLight!=0) {
                    scene.removeEntity(fallbackLight); engine.destroyEntity(fallbackLight);
                    EntityManager.get().destroy(fallbackLight); fallbackLight=0;
                }
                loader.destroyAsset(loaded); loaded=null;
                flushGpu(engine);
            }
            if(staged.assets.size()!=total||staged.clips.size()!=total)
                throw new IllegalStateException("Cinematic frame publication count differs");
            staged.sourcePrompt="cinematic3d: authored full-scene native Filament replay; provider="+
                    timeline.getString("motionProvider")+"; generation="+generationId;
            checkpoint(progress,total,total,total/(double)fps,0);
            ProjectStore.Project result=Cinematic3DProgramStore.publishRendered(context,store,source,staged,
                    programSnapshot,bundleSnapshot);
            published=true;
            return result;
        } finally {
            // Drain descriptors before disposing their buffers or callback thread.
            try { if(engine!=null)engine.flushAndWait(TimeUnit.SECONDS.toNanos(1)); } catch(Exception ignored) {}
            try { if(view!=null&&engine!=null)engine.destroyView(view); } catch(Exception ignored) {}
            try { if(scene!=null&&loaded!=null)scene.removeEntities(loaded.getEntities()); } catch(Exception ignored) {}
            try { if(loader!=null&&loaded!=null)loader.destroyAsset(loaded); } catch(Exception ignored) {}
            try { if(fallbackLight!=0&&engine!=null)engine.destroyEntity(fallbackLight); } catch(Exception ignored) {}
            try { if(fallbackLight!=0)EntityManager.get().destroy(fallbackLight); } catch(Exception ignored) {}
            try { if(resources!=null)resources.destroy(); } catch(Exception ignored) {}
            try { if(loader!=null)loader.destroy(); } catch(Exception ignored) {}
            try { if(materials!=null)materials.destroyMaterials(); } catch(Exception ignored) {}
            try { if(materials!=null)materials.destroy(); } catch(Exception ignored) {}
            try { if(renderer!=null&&engine!=null)engine.destroyRenderer(renderer); } catch(Exception ignored) {}
            try { if(swap!=null&&engine!=null)engine.destroySwapChain(swap); } catch(Exception ignored) {}
            try { if(scene!=null&&engine!=null)engine.destroyScene(scene); } catch(Exception ignored) {}
            try { if(engine!=null)engine.destroy(); } catch(Exception ignored) {}
            if(callbackThread!=null) {
                boolean interrupted=Thread.interrupted();
                callbackThread.quitSafely();
                try { callbackThread.join(1000); }
                catch(InterruptedException cancelled) { interrupted=true; callbackThread.quit(); }
                finally { if(interrupted)Thread.currentThread().interrupt(); }
            }
            if(bitmap!=null)bitmap.recycle();
            if(!published&&generation!=null)deleteFailedGeneration(generation);
        }
    }

    private static void checkpoint(Progress progress,int done,int total,double seconds,double impact) throws Exception {
        if(Thread.currentThread().isInterrupted())throw new InterruptedException("Cinematic native render cancelled");
        if(progress!=null)progress.onFrame(done,total,seconds,impact);
        if(Thread.currentThread().isInterrupted())throw new InterruptedException("Cinematic native render cancelled");
    }

    private static String declaredSceneSha256(JSONObject bundle,String path) throws Exception {
        JSONObject manifest=bundle.optJSONObject("manifest");
        if(manifest==null)manifest=bundle;
        JSONArray files=manifest.getJSONArray("files");
        String hash=null;
        for(int index=0;index<files.length();index++) {
            JSONObject file=files.getJSONObject(index);
            if(path.equals(file.getString("path"))) {
                if(hash!=null)throw new IllegalArgumentException("Ambiguous cinematic scene artifact");
                hash=file.getString("sha256");
            }
        }
        if(hash==null||!hash.matches("[0-9a-f]{64}"))
            throw new IllegalArgumentException("A declared exact cinematic scene hash is required");
        return hash;
    }

    private static void readModel(File file,ByteBuffer buffer,byte[] transfer,String expectedSha256) throws Exception {
        buffer.clear();
        MessageDigest digest=MessageDigest.getInstance("SHA-256");
        try(FileInputStream input=new FileInputStream(file)) {
            int n;
            while((n=input.read(transfer))!=-1) {
                if(Thread.currentThread().isInterrupted())throw new InterruptedException("Cinematic GLB load cancelled");
                if(n>buffer.remaining())throw new IllegalArgumentException("Cinematic GLB changed after admission");
                digest.update(transfer,0,n);
                buffer.put(transfer,0,n);
            }
        }
        buffer.flip();
        StringBuilder hash=new StringBuilder(64);
        for(byte value:digest.digest())hash.append(String.format(Locale.US,"%02x",value&255));
        if(!expectedSha256.equals(hash.toString()))
            throw new IllegalStateException("BUNDLE_CHANGED: cinematic GLB bytes changed before GPU loading");
    }

    private static void capture(Engine engine,Renderer renderer,SwapChain swap,View view,ByteBuffer rgba,
                                int width,int height,int frame,int fps,Handler callbacks) throws Exception {
        rgba.clear();
        CountDownLatch readback=new CountDownLatch(1);
        long clock=1_000_000_000L+NativeFrameClock.presentationTimeUs(frame,fps)*1000;
        boolean began=false;
        for(int attempt=0;attempt<5&&!began;attempt++) {
            if(Thread.currentThread().isInterrupted())throw new InterruptedException("Cinematic GPU capture cancelled");
            began=renderer.beginFrame(swap,clock);
            if(!began) { flushGpu(engine); Thread.sleep(16); }
        }
        if(!began)throw new IllegalStateException("Filament GPU refused the cinematic frame");
        try {
            renderer.render(view);
            renderer.readPixels(0,0,width,height,new Texture.PixelBufferDescriptor(rgba,
                    Texture.Format.RGBA,Texture.Type.UBYTE,1,0,0,width,callbacks,readback::countDown));
        } finally { renderer.endFrame(); }
        flushGpu(engine);
        if(!readback.await(10,TimeUnit.SECONDS))throw new IllegalStateException("Cinematic GPU readback timed out");
        rgba.position(0);
    }

    private static void flushGpu(Engine engine) {
        if(!engine.flushAndWait(TimeUnit.SECONDS.toNanos(10)))
            throw new IllegalStateException("Cinematic GPU submission timed out");
    }

    private static void appendFrame(ProjectStore.Project staged,File file,String generation,JSONObject program,
            JSONObject bundle,JSONObject shot,JSONObject binding,JSONObject sample,int frame,int shotFrame,
            int fps,int width,int height) throws Exception {
        ProjectStore.Asset asset=new ProjectStore.Asset();
        asset.id=UUID.randomUUID().toString(); asset.uri=Uri.fromFile(file).toString(); asset.name=file.getName();
        asset.mime="image/jpeg"; asset.role="generated_cinematic_3d"; asset.generated=true;
        asset.sizeBytes=file.length(); asset.seekable=true; asset.persistedReadAccess=true;
        JSONObject metadata=clockMetadata(frame,fps);
        metadata.put("engine","filament-full-scene-cinematic3d").put("executionEvidence","native_filament_frame")
                .put("generationId",generation).put("programId",program.getString("programId"))
                .put("programSha256",program.getString("sha256")).put("bundleId",program.getString("bundleId"))
                .put("bundleSha256",bundle.getString("sha256")).put("sourceProjectId",program.getString("projectId"))
                .put("sourceProjectUpdatedAt",program.getLong("sourceProjectUpdatedAt"))
                .put("motionProvider",program.getJSONObject("timeline").getString("motionProvider"))
                .put("shotId",shot.getString("id")).put("action",shot.getString("action")).put("shotFrame",shotFrame)
                .put("animationName",binding.getString("animationName")).put("cameraName",binding.getString("cameraName"))
                .put("clipSeconds",sample.getDouble("clipSeconds")).put("hitStop",sample.getBoolean("hitStop"))
                .put("real3DGeometry",true).put("authoredGltfAnimations",true).put("width",width).put("height",height);
        asset.generationMetadata=metadata;
        staged.assets.add(asset);
        ProjectStore.Clip clip=new ProjectStore.Clip();
        clip.id=UUID.randomUUID().toString(); clip.assetId=asset.id; clip.inMs=0;
        clip.outMs=NativeFrameClock.frameDurationMs(frame,fps); clip.speed=1; clip.volume=0;
        clip.transition="none"; clip.title="";
        clip.effects=clockMetadata(frame,fps).put("poseFps",fps).put("directorSequenceFrame",true);
        staged.clips.add(clip);
    }

    private static void deleteFailedGeneration(File generation) {
        File[] files=generation.listFiles();
        if(files!=null)for(File file:files)if(file.isFile())file.delete();
        generation.delete();
    }
}
