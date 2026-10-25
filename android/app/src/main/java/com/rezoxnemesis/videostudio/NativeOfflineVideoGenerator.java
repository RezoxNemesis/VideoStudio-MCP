package com.rezoxnemesis.videostudio;

import ai.onnxruntime.NodeInfo;
import ai.onnxruntime.OnnxJavaType;
import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtSession;
import ai.onnxruntime.TensorInfo;
import android.app.ActivityManager;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.net.Uri;
import android.os.Process;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Offline-only neural video inference on explicitly installed owner-controlled ONNX model weights.
 *
 * ABI v1: CLIP text_encoder model.onnx -> [1,77,768] float conditioning.
 * Temporal video_generator.onnx inputs:
 *   image [1,3,128,128] float; text_embeddings [1,77,768] float;
 *   noise [1,9,4,16,16] float; image_mask [1] float;
 * output frames [1,9,3,128,128] float in [-1,1].
 *
 * The temporal neural model itself must be supplied by a compatible, locally installed,
 * licence-permitted model pack. It is NOT the existing procedural/slideshow renderer.
 * No network fallback, API token, external model runtime or synthetic video is supplied.
 */
public final class NativeOfflineVideoGenerator {
    public interface Progress {
        void update(int percentage,String stage)throws Exception;
    }
    public static final String MODEL_ABI="videostudio.temporal-video-onnx.v1";
    private static final String[] REQUIRED={
            "video_generator.onnx","text_encoder/model.onnx",
            "tokenizer/vocab.json","tokenizer/merges.txt"};
    private static final long IMAGE_LIMIT=64L*1024*1024;
    private final Context context;

    public NativeOfflineVideoGenerator(Context owner) {
        if(owner==null)throw new IllegalArgumentException("Native Android context required");
        context=owner.getApplicationContext();
    }

    private File modelRoot(){
        return new File(context.getFilesDir(),
                "creative_workspace/models/installed/"+NativeOfflineVideoPlan.PACK_ID);
    }

    /** No API calls. Report current install status without allocating a neural model. */
    public JSONObject capabilities()throws Exception {
        File root=modelRoot();
        JSONObject result=new JSONObject()
                .put("ok",true).put("schema","videostudio.native-offline-video.capabilities.v1")
                .put("mode","offline_onnx_video_only")
                .put("separateVideoGenerator",true)
                .put("modelPackId",NativeOfflineVideoPlan.PACK_ID)
                .put("modelPackInstalled",root.isDirectory()&&new File(root,"manifest.json").isFile())
                .put("runtimeBundled",true)
                .put("neuralVideoWeightsBundled",false)
                .put("imageToVideoRequiresVerifiedModel",true)
                .put("textToVideoRequiresVerifiedModel",true)
                .put("supportedModes",new JSONArray().put("image_to_video").put("text_to_video"))
                .put("frameWidth",NativeOfflineVideoPlan.WIDTH)
                .put("frameHeight",NativeOfflineVideoPlan.HEIGHT)
                .put("framesPerScene",NativeOfflineVideoPlan.FRAMES_PER_SHOT)
                .put("outputFps",NativeOfflineVideoPlan.FPS)
                .put("maximumReferenceImages",NativeOfflineVideoPlan.MAX_SHOTS)
                .put("paidApiRequests",false).put("cloudInference",false)
                .put("subscriptionRequired",false).put("trialExpiry",false)
                .put("internetRequiredForInference",false)
                .put("galleryEnumeration",false)
                .put("fullyCinematicModelOnPhoneVerified",false);
        result.put("requiresUserInstalledCompatibleModelPack",
                !result.getBoolean("modelPackInstalled"));
        return result;
    }

    private static final class Pack {
        final File root;final JSONObject manifest;
        Pack(File root,JSONObject manifest){this.root=root;this.manifest=manifest;}
    }

    /** Admission and model hashes are checked before any output project or frame allocation. */
    private Pack checkedPack()throws Exception {
        File root=modelRoot(),manifestFile=new File(root,"manifest.json");
        if(!root.isDirectory()||!manifestFile.isFile())
            throw new IllegalStateException("LOCAL_VIDEO_MODEL_NOT_INSTALLED: import a compatible offline ONNX pack first");
        if(Files.isSymbolicLink(root.toPath())||manifestFile.length()>256*1024)
            throw new IllegalArgumentException("Untrusted local video pack");
        JSONObject m=new JSONObject(new String(Files.readAllBytes(manifestFile.toPath()),StandardCharsets.UTF_8));
        if(!NativeOfflineVideoPlan.PACK_ID.equals(m.optString("id"))
                ||!MODEL_ABI.equals(m.optString("runtimeAdapter")))
            throw new IllegalArgumentException("LOCAL_VIDEO_MODEL_ABI_MISMATCH");
        String licence=m.optString("license","");
        if(!("Apache-2.0".equals(licence)||"MIT".equals(licence)||"BSD-3-Clause".equals(licence)))
            throw new IllegalArgumentException("Offline model licence must permit durable free use");
        if(m.has("apiKey")||m.has("endpoint")||m.has("serviceUrl")
                ||m.optBoolean("hostedInference",false)||m.optBoolean("requiresSubscription",false)
                ||m.optBoolean("timeLimited",false))
            throw new IllegalArgumentException("Remote, paid or expiring model packs are unsupported");
        JSONObject files=m.optJSONObject("files");
        if(files==null)throw new IllegalArgumentException("Local model pack needs signed file hashes");
        for(String relative:REQUIRED){
            String expected=files.optString(relative,"");
            if(!expected.matches("[0-9a-f]{64}"))throw new IllegalArgumentException("Model hash missing for "+relative);
            File f=new File(root,relative);
            if(!f.getCanonicalPath().startsWith(root.getCanonicalPath()+File.separator)
                    ||Files.isSymbolicLink(f.toPath())||!f.isFile()||f.length()==0)
                throw new IllegalArgumentException("Missing or unsafe local video model file");
            if(!expected.equals(LocalDrawingModels.hash(f)))
                throw new IllegalArgumentException("Local video model hash differs from its installed manifest");
        }
        if(!Process.is64Bit())throw new IllegalStateException("Local neural video requires a 64-bit Android process");
        ActivityManager manager=(ActivityManager)context.getSystemService(Context.ACTIVITY_SERVICE);
        ActivityManager.MemoryInfo mem=new ActivityManager.MemoryInfo();
        if(manager==null)throw new IllegalStateException("Cannot measure Android memory for neural video");
        manager.getMemoryInfo(mem);
        long need=Math.max(1024,Math.min(16384,m.optLong("requiredAvailableRamMb",2048)));
        if(mem.lowMemory||mem.availMem/1048576L<need)
            throw new IllegalStateException("LOCAL_VIDEO_MEMORY_LIMIT: model needs at least "+need+" MiB available RAM");
        return new Pack(root,m);
    }

    /** Non-mutating preflight. It does not generate a procedural replacement. */
    public JSONObject plan(ProjectStore store,JSONObject request)throws Exception {
        if(store==null)throw new IllegalArgumentException("Native project store required");
        ProjectStore.Project source=store.get(request.optString("sourceProjectId",""));
        JSONObject plan=NativeOfflineVideoPlan.validate(source,request);
        Pack pack=checkedPack();
        JSONObject summary=new JSONObject(plan.toString())
                .put("ok",true).put("modelReadyForSchemaCheck",true)
                .put("installedModelAbi",pack.manifest.getString("runtimeAdapter"))
                .put("requiresNativeOnnxExecution",true)
                .put("rendered",false).put("neuralFramesVerified",false)
                .put("sourceProjectUnchanged",true).put("galleryAccess",false);
        return summary;
    }

    private static void check()throws InterruptedException{
        if(Thread.currentThread().isInterrupted())
            throw new InterruptedException("Local temporal neural generation cancelled; checkpoints retained");
    }
    private static void ensureInputs(OrtSession session)throws Exception {
        Map<String,NodeInfo> input=session.getInputInfo();
        shape(input,"image",OnnxJavaType.FLOAT,1,3,128,128);
        shape(input,"text_embeddings",OnnxJavaType.FLOAT,1,77,768);
        shape(input,"noise",OnnxJavaType.FLOAT,1,9,4,16,16);
        shape(input,"image_mask",OnnxJavaType.FLOAT,1);
        if(input.size()!=4)throw new IllegalArgumentException("Temporal ONNX ABI requires four exact inputs");
        shape(session.getOutputInfo(),"frames",OnnxJavaType.FLOAT,1,9,3,128,128);
    }
    private static void shape(Map<String,NodeInfo> nodes,String name,OnnxJavaType type,long...wanted){
        NodeInfo node=nodes.get(name);
        if(node==null||!(node.getInfo() instanceof TensorInfo))
            throw new IllegalArgumentException("Missing ONNX tensor "+name);
        TensorInfo tensor=(TensorInfo)node.getInfo();
        if(tensor.type!=type||!Arrays.equals(wanted,tensor.getShape()))
            throw new IllegalArgumentException("Local video ONNX tensor ABI differs: "+name);
    }

    private Bitmap image(ProjectStore.Asset asset)throws Exception{
        if(asset==null)return Bitmap.createBitmap(NativeOfflineVideoPlan.WIDTH,
                NativeOfflineVideoPlan.HEIGHT,Bitmap.Config.ARGB_8888);
        if(asset.mime==null||!asset.mime.startsWith("image/"))
            throw new IllegalArgumentException("Owned image reference required");
        Uri uri=Uri.parse(asset.uri);
        BitmapFactory.Options probe=new BitmapFactory.Options();probe.inJustDecodeBounds=true;
        try(InputStream in=context.getContentResolver().openInputStream(uri)){
            if(in==null)throw new IllegalArgumentException("Selected owned image unreadable");
            BitmapFactory.decodeStream(in,null,probe);
        }
        if(probe.outWidth<8||probe.outHeight<8||probe.outWidth>32768||probe.outHeight>32768)
            throw new IllegalArgumentException("Invalid source image geometry");
        BitmapFactory.Options opt=new BitmapFactory.Options();
        opt.inPreferredConfig=Bitmap.Config.ARGB_8888;
        opt.inSampleSize=1;
        while(Math.max(probe.outWidth/opt.inSampleSize,probe.outHeight/opt.inSampleSize)>1024)
            opt.inSampleSize*=2;
        Bitmap decoded;
        try(InputStream in=context.getContentResolver().openInputStream(uri)){
            if(in==null)throw new IllegalArgumentException("Selected image access expired");
            decoded=BitmapFactory.decodeStream(in,null,opt);
        }
        if(decoded==null)throw new IllegalArgumentException("Could not decode owned image reference");
        try{
            Bitmap output=Bitmap.createBitmap(NativeOfflineVideoPlan.WIDTH,
                    NativeOfflineVideoPlan.HEIGHT,Bitmap.Config.ARGB_8888);
            Canvas canvas=new Canvas(output);
            canvas.drawColor(Color.BLACK);
            int edge=Math.min(decoded.getWidth(),decoded.getHeight());
            Rect crop=new Rect((decoded.getWidth()-edge)/2,(decoded.getHeight()-edge)/2,
                    (decoded.getWidth()+edge)/2,(decoded.getHeight()+edge)/2);
            Paint paint=new Paint(Paint.FILTER_BITMAP_FLAG);
            canvas.drawBitmap(decoded,crop,new RectF(0,0,128,128),paint);
            return output;
        }finally{decoded.recycle();}
    }

    private static float[] pixels(Bitmap source){
        int n=NativeOfflineVideoPlan.WIDTH*NativeOfflineVideoPlan.HEIGHT;
        int[] argb=new int[n];source.getPixels(argb,0,NativeOfflineVideoPlan.WIDTH,
                0,0,NativeOfflineVideoPlan.WIDTH,NativeOfflineVideoPlan.HEIGHT);
        float[] result=new float[n*3];
        for(int i=0;i<n;i++){
            result[i]=Color.red(argb[i])/127.5f-1f;
            result[n+i]=Color.green(argb[i])/127.5f-1f;
            result[2*n+i]=Color.blue(argb[i])/127.5f-1f;
        }
        return result;
    }

    private static float[] noise(long seed,int shot){
        int count=9*4*16*16;float[] noise=new float[count];
        Random rng=new Random(seed ^ (shot*0x9E3779B97F4A7C15L));
        for(int i=0;i<count;i++)noise[i]=(float)rng.nextGaussian();
        return noise;
    }
    private static String id(String sourceId,String operation){
        return UUID.nameUUIDFromBytes(("native-offline-video/"+sourceId+"/"+operation)
                .getBytes(StandardCharsets.UTF_8)).toString();
    }
    private static File checkedFile(File base,String name)throws Exception {
        File file=new File(base,name);
        if(!file.getCanonicalPath().startsWith(base.getCanonicalPath()+File.separator))
            throw new IllegalArgumentException("Unsafe native generation file");
        return file;
    }
    private static String fingerprint(JSONObject plan,JSONObject manifest)throws Exception{
        MessageDigest hash=MessageDigest.getInstance("SHA-256");
        hash.update(plan.getString("planSha256").getBytes(StandardCharsets.UTF_8));
        hash.update(manifest.getString("archiveSha256").getBytes(StandardCharsets.UTF_8));
        return OpenPipelineArtifacts.hex(hash.digest());
    }

    /**
     * One real ONNX temporal model call per supplied storyboard image. A failed
     * model call never replaces source art with static zooms or generic characters.
     * Frames are checkpointed atomically before the output project is published.
     */
    public JSONObject execute(ProjectStore store,JSONObject request,Progress progress)throws Exception{
        JSONObject plan=NativeOfflineVideoPlan.validate(
                store==null?null:store.get(request.optString("sourceProjectId","")),request);
        Pack pack=checkedPack();
        String identity=fingerprint(plan,pack.manifest);
        String outputId=id(plan.getString("sourceProjectId"),plan.getString("operationId"));
        ProjectStore.Project existing=store.get(outputId);
        if(existing!=null){
            if(!identity.equals(existing.sourcePrompt))
                throw new IllegalStateException("LOCAL_VIDEO_OPERATION_CONFLICT: edit uses another source or model");
            return new JSONObject().put("ok",true).put("projectId",outputId)
                    .put("reused",true).put("framesCreated",existing.clips.size())
                    .put("modelOrigin","local_onnx").put("sourceUnchanged",true);
        }
        File projectRoot=new CreativeWorkspace(context).projectRoot(outputId);
        File frames=new File(projectRoot,"generated/native_video");
        if(!frames.isDirectory()&&!frames.mkdirs())throw new IllegalStateException("Cannot create private video checkpoints");
        File identityFile=checkedFile(frames,"generation.sha256");
        if(identityFile.isFile()){
            String saved=new String(Files.readAllBytes(identityFile.toPath()),StandardCharsets.UTF_8).trim();
            if(!identity.equals(saved))throw new IllegalStateException("LOCAL_VIDEO_OUTPUT_CONFLICT");
        }else OpenPipelineArtifacts.writeAtomic(identityFile,
                identity.getBytes(StandardCharsets.UTF_8),128);
        JSONArray assets=plan.getJSONArray("sourceAssetIds");
        int scenes=plan.getInt("shotCount");
        long seed=plan.getLong("seed");
        java.util.ArrayList<ProjectStore.Asset> generated=new java.util.ArrayList<>();
        java.util.ArrayList<ProjectStore.Clip> clips=new java.util.ArrayList<>();
        if(progress!=null)progress.update(1,"Opening verified offline temporal model");
        // Only one on-device neural session at a time, including local still generation.
        while(!LocalDrawingEngine.INFERENCE_SLOT.tryAcquire(100,TimeUnit.MILLISECONDS))check();
        try(LocalDrawingEngine runner=new LocalDrawingEngine()){
            try(OrtSession encoder=runner.open(pack.root,"text_encoder/model.onnx");
                OrtSession video=runner.open(pack.root,"video_generator.onnx")){
                ensureInputs(video);
                LocalDrawingTokenizer tokenizer=new LocalDrawingTokenizer(pack.root);
                float[] embedded=runner.encode(encoder,tokenizer.encode(plan.getString("prompt")));
                long frameDurationMs=(long)Math.ceil(1000d/NativeOfflineVideoPlan.FPS);
                for(int shot=0;shot<scenes;shot++){
                    check();
                    ProjectStore.Project current=store.get(plan.getString("sourceProjectId"));
                    if(current==null||current.updatedAt!=plan.getLong("sourceUpdatedAt"))
                        throw new IllegalStateException("SOURCE_PROJECT_CHANGED_DURING_GENERATION");
                    ProjectStore.Asset anchor="text_to_video".equals(plan.getString("mode"))?null:
                            current.asset(assets.getString(shot));
                    Bitmap conditioning=image(anchor);
                    try{
                        Map<String,OnnxTensor> inputs=new LinkedHashMap<>();
                        try{
                            inputs.put("image",runner.tensor(pixels(conditioning),1,3,128,128));
                            inputs.put("text_embeddings",runner.tensor(embedded,1,77,768));
                            inputs.put("noise",runner.tensor(noise(seed,shot),1,9,4,16,16));
                            inputs.put("image_mask",runner.tensor(new float[]{anchor==null?0:1},1));
                            float[] decoded;
                            try(OrtSession.Result inferred=runner.run(video,inputs)){
                                ai.onnxruntime.OnnxValue raw=inferred.get("frames")
                                        .orElseThrow(()->new IllegalArgumentException("Temporal model output frames missing"));
                                if(!(raw instanceof OnnxTensor))
                                    throw new IllegalArgumentException("Temporal output is not an ONNX tensor");
                                OnnxTensor framesTensor=(OnnxTensor)raw;
                                if(framesTensor.getInfo().type!=OnnxJavaType.FLOAT
                                        ||!Arrays.equals(framesTensor.getInfo().getShape(),
                                                new long[]{1,9,3,128,128}))
                                    throw new IllegalArgumentException("Model must generate real 9-frame RGB video");
                                java.nio.FloatBuffer f=framesTensor.getFloatBuffer();
                                decoded=new float[f.remaining()];f.get(decoded);
                            }
                            int imageArea=128*128,sceneArea=3*imageArea;
                            for(int frame=0;frame<NativeOfflineVideoPlan.FRAMES_PER_SHOT;frame++){
                                check();
                                int offset=frame*sceneArea;
                                int[] argb=new int[imageArea];
                                for(int i=0;i<imageArea;i++)
                                    argb[i]=Color.rgb(LocalDrawingEngine.channel(decoded[offset+i]),
                                            LocalDrawingEngine.channel(decoded[offset+imageArea+i]),
                                            LocalDrawingEngine.channel(decoded[offset+2*imageArea+i]));
                                String fileName=String.format(java.util.Locale.ROOT,"scene_%02d_frame_%02d.png",shot,frame);
                                File file=checkedFile(frames,fileName);
                                if(!file.isFile()){
                                    Bitmap bitmap=Bitmap.createBitmap(128,128,Bitmap.Config.ARGB_8888);
                                    try{
                                        bitmap.setPixels(argb,0,128,0,0,128,128);
                                        File temp=checkedFile(frames,fileName+".partial");
                                        try(FileOutputStream output=new FileOutputStream(temp)){
                                            if(!bitmap.compress(Bitmap.CompressFormat.PNG,100,output))
                                                throw new IllegalStateException("Could not encode native neural frame");
                                            output.getFD().sync();
                                        }
                                        if(!temp.renameTo(file))throw new IllegalStateException("Cannot checkpoint generated frame");
                                    }finally{bitmap.recycle();}
                                }
                                ProjectStore.Asset result=new ProjectStore.Asset();
                                result.id=UUID.nameUUIDFromBytes((outputId+"/"+fileName).getBytes(StandardCharsets.UTF_8)).toString();
                                result.uri=Uri.fromFile(file).toString();result.name=fileName;
                                result.mime="image/png";result.generated=true;result.role="neural_video_frame";
                                result.sizeBytes=file.length();result.seekable=true;
                                result.durationMs=frameDurationMs;
                                result.generationMetadata=new JSONObject()
                                        .put("provider","videostudio.local_temporal_onnx")
                                        .put("planSha256",plan.getString("planSha256"))
                                        .put("shotIndex",shot).put("frameIndex",frame)
                                        .put("imagePromptUsed",anchor!=null)
                                        .put("proceduralFallback",false);
                                ProjectStore.Clip clip=new ProjectStore.Clip();
                                clip.id=UUID.nameUUIDFromBytes((outputId+"/clip/"+fileName)
                                        .getBytes(StandardCharsets.UTF_8)).toString();
                                clip.assetId=result.id;clip.inMs=0;clip.outMs=frameDurationMs;
                                generated.add(result);clips.add(clip);
                                if(progress!=null)progress.update(10+(int)(80d*(shot*9+frame+1)/(scenes*9)),
                                        "Decoding newly generated frame "+(shot*9+frame+1)+"/"+scenes*9);
                            }
                        }finally{for(OnnxTensor t:inputs.values())if(t!=null)t.close();}
                    }finally{conditioning.recycle();}
                }
            }
        }finally{LocalDrawingEngine.INFERENCE_SLOT.release();}
        if(store.get(plan.getString("sourceProjectId"))==null
                ||store.get(plan.getString("sourceProjectId")).updatedAt!=plan.getLong("sourceUpdatedAt"))
            throw new IllegalStateException("SOURCE_PROJECT_CHANGED_BEFORE_PUBLICATION");
        ProjectStore.Project output=new ProjectStore.Project();
        output.id=outputId;output.name="Native Video • "+plan.getString("prompt").substring(0,
                Math.min(42,plan.getString("prompt").length()));
        output.sourcePrompt=identity;output.assets.addAll(generated);output.clips.addAll(clips);
        store.save(output);
        if(progress!=null)progress.update(98,"Native neural frames saved as an editable VideoStudio project");
        return new JSONObject().put("ok",true).put("projectId",outputId)
                .put("sourceProjectId",plan.getString("sourceProjectId"))
                .put("sourceUnchanged",true).put("framesCreated",clips.size())
                .put("fps",NativeOfflineVideoPlan.FPS).put("width",128).put("height",128)
                .put("modelOrigin","local_verified_onnx").put("internetRequests",0)
                .put("paidApiRequests",0).put("encodedMp4",false)
                .put("galleryAccess",false).put("reused",false);
    }
}
