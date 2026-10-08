package com.rezoxnemesis.videostudio;

import android.content.Context;
import android.graphics.*;
import ai.onnxruntime.*;
import org.json.*;
import java.io.*;
import java.nio.*;
import java.util.*;

/** Phased, native SD-Turbo one-step inference. No browser or paid inference service. */
public final class NativeNeuralEngine {
    public interface Checkpoint {void step(String phase,int progress) throws Exception;}
    private final Context context;
    public NativeNeuralEngine(Context context) {this.context=context.getApplicationContext();}
    public static boolean compatible(JSONObject m) {
        JSONObject abi=m.optJSONObject("neural"),files=m.optJSONObject("files");
        if(!"onnx-sd-turbo-v1".equals(m.optString("backend"))||abi==null||files==null||abi.optJSONObject("phaseWorkingSetMb")==null) return false;
        for(String key:new String[]{"textEncoder","unet","vaeDecoder","vocabulary","merges"})
            if(abi.optString(key).isEmpty()||!files.optString(abi.optString(key)).matches("[a-fA-F0-9]{64}")) return false;
        return "epsilon".equals(abi.optString("predictionType"))&&abi.optInt("timestep")==999
                &&Math.abs(abi.optDouble("sigma",0)-14.6146)<.00001&&Math.abs(abi.optDouble("vaeScale",0)-.18215)<.000001;
    }
    public void generate(String packId,String prompt,long seed,File output,String expectedContract,Checkpoint checkpoint) throws Exception {
        File root=new ModelPackManager(context).installedDirectory(packId);
        JSONObject manifest=new JSONObject(SceneMemoryStore.readSmall(new File(root,"manifest.json")));
        NativeModelContract.verify(manifest,expectedContract);
        if(!compatible(manifest)) throw new IllegalArgumentException("Neural keyframes require a verified onnx-sd-turbo-v1 pack");
        JSONObject abi=manifest.getJSONObject("neural");
        if(!"epsilon".equals(abi.getString("predictionType")) || abi.getInt("timestep")!=999 || Math.abs(abi.getDouble("sigma")-14.6146)>.00001 || Math.abs(abi.getDouble("vaeScale")-.18215)>.000001)
            throw new IllegalArgumentException("Only the SD-Turbo one-step epsilon scheduler is supported");
        File vocab=verified(root,manifest,abi.getString("vocabulary")),merges=verified(root,manifest,abi.getString("merges"));
        ClipBpeTokenizer tokenizer=new ClipBpeTokenizer(new JSONObject(SceneMemoryStore.readSmall(vocab)),SceneMemoryStore.readSmall(merges),abi.optInt("bos",49406),abi.optInt("eos",49407),abi.optInt("pad",49407));
        int hiddenSize=abi.optInt("hiddenSize",1024);
        if(hiddenSize!=768 && hiddenSize!=1024) throw new IllegalArgumentException("Text hidden size requires 768 or 1024");
        long[] tokenIds=tokenizer.encode(prompt);float[] hidden;
        OrtEnvironment env=OrtEnvironment.getEnvironment();
        checkpoint.step("text_encoder",5);
        try(OrtSession.SessionOptions options=options(root,manifest,abi,"textEncoder");OrtSession session=env.createSession(verified(root,manifest,abi.getString("textEncoder")).getAbsolutePath(),options)) {
            NodeInfo input=session.getInputInfo().get("input_ids");if(input==null||!(input.getInfo() instanceof TensorInfo)) throw new IllegalArgumentException("Text encoder input_ids missing");
            TensorInfo info=(TensorInfo)input.getInfo();
            try(OnnxTensor tokens=tokenTensor(env,tokenIds,info.type);OrtSession.Result result=session.run(Map.of("input_ids",tokens))) {hidden=output(result,"last_hidden_state",new long[]{1,77,hiddenSize});}
        }
        checkpoint.step("unet",30);
        float[] noise=noise(seed,4*64*64,14.6146f), scaled=new float[noise.length];
        double divisor=Math.sqrt(14.6146*14.6146+1);for(int i=0;i<scaled.length;i++) scaled[i]=(float)(noise[i]/divisor);
        float[] predicted;
        try(OrtSession.SessionOptions options=options(root,manifest,abi,"unet");OrtSession session=env.createSession(verified(root,manifest,abi.getString("unet")).getAbsolutePath(),options);
            OnnxTensor sample=OnnxTensor.createTensor(env,FloatBuffer.wrap(scaled),new long[]{1,4,64,64});
            OnnxTensor timestep=OnnxTensor.createTensor(env,LongBuffer.wrap(new long[]{999}),new long[]{1});
            OnnxTensor conditioning=OnnxTensor.createTensor(env,FloatBuffer.wrap(hidden),new long[]{1,77,hiddenSize});
            OrtSession.Result result=session.run(Map.of("sample",sample,"timestep",timestep,"encoder_hidden_states",conditioning))) {
            predicted=output(result,"out_sample",new long[]{1,4,64,64});
        }
        for(int i=0;i<noise.length;i++) noise[i]=(float)((noise[i]-14.6146*predicted[i])/.18215);
        checkpoint.step("vae_decoder",70);float[] pixels;
        try(OrtSession.SessionOptions options=options(root,manifest,abi,"vaeDecoder");OrtSession session=env.createSession(verified(root,manifest,abi.getString("vaeDecoder")).getAbsolutePath(),options);
            OnnxTensor latent=OnnxTensor.createTensor(env,FloatBuffer.wrap(noise),new long[]{1,4,64,64});
            OrtSession.Result result=session.run(Map.of("latent_sample",latent))) {pixels=output(result,"sample",new long[]{1,3,512,512});}
        checkpoint.step("register_keyframe",92);
        int[] rgb=new int[512*512];for(int i=0;i<rgb.length;i++) rgb[i]=Color.rgb(channel(pixels[i]),channel(pixels[rgb.length+i]),channel(pixels[rgb.length*2+i]));
        Bitmap image=Bitmap.createBitmap(rgb,512,512,Bitmap.Config.ARGB_8888);
        try(FileOutputStream file=new FileOutputStream(output)) {if(!image.compress(Bitmap.CompressFormat.PNG,100,file)) throw new IOException("Cannot encode neural keyframe");file.getFD().sync();}
        finally {image.recycle();}
    }
    private OrtSession.SessionOptions options(File root,JSONObject manifest,JSONObject abi,String phase) throws Exception {
        JSONObject budget=new DeviceComputeProfile(context).snapshot();
        JSONObject estimates=abi.getJSONObject("phaseWorkingSetMb");long required=estimates.getLong(phase);
        File model=new File(root,abi.getString(phase));long weightMb=(model.length()+1024*1024-1)/(1024*1024);
        required=Math.max(required,Math.max(512,weightMb*2));
        if(required>budget.optLong("activeWorkingSetBudgetMb")||budget.optLong("javaArrayBudgetMb")<16) throw new IllegalStateException("Neural phase "+phase+" requires "+required+" MB, exceeds active device budget");
        if(!budget.optBoolean("thermalSafeForHeavyWork")) throw new IllegalStateException("Phone needs cooling before neural phase");
        OrtSession.SessionOptions options=new OrtSession.SessionOptions();options.setIntraOpNumThreads(2);options.setInterOpNumThreads(1);return options;
    }
    private static OnnxTensor tokenTensor(OrtEnvironment env,long[] ids,OnnxJavaType type) throws Exception {
        if(type==OnnxJavaType.INT64) return OnnxTensor.createTensor(env,LongBuffer.wrap(ids),new long[]{1,77});
        if(type==OnnxJavaType.INT32) {int[] ints=new int[77];for(int i=0;i<77;i++) ints[i]=(int)ids[i];return OnnxTensor.createTensor(env,IntBuffer.wrap(ints),new long[]{1,77});}
        throw new IllegalArgumentException("Text encoder tokens require int32 or int64");
    }
    private static File verified(File root,JSONObject manifest,String relative) throws Exception {
        File file=new File(root,relative);
        if(!file.getCanonicalPath().startsWith(root.getCanonicalPath()+File.separator)||!file.isFile()) throw new IOException("Unsafe/missing neural model file");
        String expected=manifest.getJSONObject("files").getString(relative);if(!expected.matches("[a-fA-F0-9]{64}")) throw new IllegalArgumentException("Neural model digest required");
        try(InputStream input=new FileInputStream(file)) {if(!SceneMemoryStore.hashStream(input,6L*1024*1024*1024).equalsIgnoreCase(expected)) throw new IOException("Neural model integrity mismatch");}return file;
    }
    private static float[] output(OrtSession.Result result,String name,long[] expected) throws Exception {
        OnnxValue value=result.get(name).orElseThrow(()->new IllegalArgumentException("Missing neural output: "+name));
        if(!(value instanceof OnnxTensor)) throw new IllegalArgumentException("Neural output must be a tensor");
        OnnxTensor tensor=(OnnxTensor)value;TensorInfo info=tensor.getInfo();if(info.type!=OnnxJavaType.FLOAT||!Arrays.equals(info.getShape(),expected)) throw new IllegalArgumentException("Neural output ABI mismatch: "+name);
        FloatBuffer data=tensor.getFloatBuffer();float[] out=new float[data.remaining()];data.get(out);for(float f:out) if(!Float.isFinite(f)) throw new IllegalStateException("Non-finite neural output");return out;
    }
    static float[] noise(long seed,int size,float sigma) {Random random=new Random(seed);float[] values=new float[size];for(int i=0;i<size;i++) values[i]=(float)(random.nextGaussian()*sigma);return values;}
    private static int channel(float value) {return Math.round(Math.max(0,Math.min(1,value/2+.5f))*255);}
}
