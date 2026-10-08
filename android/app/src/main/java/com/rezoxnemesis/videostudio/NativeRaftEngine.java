package com.rezoxnemesis.videostudio;

import android.content.Context;
import android.graphics.Bitmap;
import ai.onnxruntime.*;
import org.json.*;
import java.io.*;
import java.nio.*;
import java.util.*;

/** Native RAFT adapter. Weights come only from a hash-verified installed model pack. */
public final class NativeRaftEngine {
    private final Context context;
    public NativeRaftEngine(Context context) {this.context=context.getApplicationContext();}
    public static boolean compatible(JSONObject manifest) {
        JSONObject abi=manifest.optJSONObject("raft"),files=manifest.optJSONObject("files");
        if(!"onnx-raft-v1".equals(manifest.optString("backend"))||abi==null||files==null) return false;
        for(String key:new String[]{"model","firstInput","secondInput","output"}) if(abi.optString(key).isEmpty()) return false;
        int w=abi.optInt("width"),h=abi.optInt("height");
        return w>=64&&w<=480&&h>=64&&h<=360&&w%8==0&&h%8==0&&files.optString(abi.optString("model")).matches("[a-fA-F0-9]{64}");
    }
    public float[] estimate(String packId,Bitmap first,Bitmap second,String expectedContract) throws Exception {
        File directory=new ModelPackManager(context).installedDirectory(packId);
        JSONObject manifest=new JSONObject(SceneMemoryStore.readSmall(new File(directory,"manifest.json")));
        NativeModelContract.verify(manifest,expectedContract);
        if(!compatible(manifest)) throw new IllegalArgumentException("RAFT pack requires backend onnx-raft-v1 and raft/files contracts");
        JSONObject abi=manifest.getJSONObject("raft");
        int w=abi.getInt("width"), h=abi.getInt("height");
        if(w<64||w>480||h<64||h>360||w%8!=0||h%8!=0) throw new IllegalArgumentException("RAFT resolution must be multiples of 8, up to 480×360");
        DeviceComputeProfile profile=new DeviceComputeProfile(context); JSONObject budget=profile.snapshot();
        long estimate=Math.max(1024,manifest.optLong("estimatedRamMb",2048));
        if(estimate>budget.optLong("activeWorkingSetBudgetMb") || budget.optLong("javaArrayBudgetMb")<16)
            throw new IllegalStateException("RAFT working set exceeds current device budget; free memory or use a smaller verified pack");
        if(!budget.optBoolean("thermalSafeForHeavyWork")) throw new IllegalStateException("RAFT requires phone cooling before inference");
        String relative=abi.getString("model"); File model=new File(directory,relative);
        if(!model.getCanonicalPath().startsWith(directory.getCanonicalPath()+File.separator) || !model.isFile()) throw new IllegalArgumentException("Unsafe/missing RAFT model file");
        String expected=manifest.getJSONObject("files").getString(relative);
        if(!expected.matches("[a-fA-F0-9]{64}")) throw new IllegalArgumentException("Model SHA-256 required");
        try(InputStream input=new FileInputStream(model)) {if(!SceneMemoryStore.hashStream(input,2L*1024*1024*1024).equalsIgnoreCase(expected)) throw new IOException("RAFT model integrity mismatch");}
        OrtEnvironment env=OrtEnvironment.getEnvironment();
        try(OrtSession.SessionOptions options=new OrtSession.SessionOptions()) {
            options.setIntraOpNumThreads(Math.max(1,Math.min(2,Runtime.getRuntime().availableProcessors()/2)));
            options.setInterOpNumThreads(1);
            try(OrtSession session=env.createSession(model.getAbsolutePath(),options)) {
                String a=abi.getString("firstInput"), b=abi.getString("secondInput"), output=abi.getString("output");
                requireTensor(session.getInputInfo().get(a),new long[]{1,3,h,w}); requireTensor(session.getInputInfo().get(b),new long[]{1,3,h,w});
                try(OnnxTensor t1=OnnxTensor.createTensor(env,FloatBuffer.wrap(chw(first,w,h)),new long[]{1,3,h,w});
                    OnnxTensor t2=OnnxTensor.createTensor(env,FloatBuffer.wrap(chw(second,w,h)),new long[]{1,3,h,w});
                    OrtSession.Result result=session.run(Map.of(a,t1,b,t2))) {
                    if(Thread.currentThread().isInterrupted()) throw new InterruptedException();
                    OnnxValue value=result.get(output).orElseThrow(()->new IllegalArgumentException("RAFT output not found"));
                    if(!(value instanceof OnnxTensor)) throw new IllegalArgumentException("RAFT output must be float tensor");
                    OnnxTensor tensor=(OnnxTensor)value; TensorInfo info=tensor.getInfo();
                    boolean nchw=Arrays.equals(info.getShape(),new long[]{1,2,h,w});
                    boolean nhwc=Arrays.equals(info.getShape(),new long[]{1,h,w,2});
                    if(info.type!=OnnxJavaType.FLOAT || (!nchw&&!nhwc)) throw new IllegalArgumentException("RAFT output must be [1,2,H,W] or [1,H,W,2] float32");
                    FloatBuffer buffer=tensor.getFloatBuffer(); float[] flow=new float[w*h*2];
                    for(int i=0;i<w*h;i++) {
                        float dx=buffer.get(nchw?i:2*i), dy=buffer.get(nchw?w*h+i:2*i+1);
                        if(!Float.isFinite(dx)||!Float.isFinite(dy)) throw new IllegalStateException("Non-finite RAFT flow");
                        flow[2*i]=dx; flow[2*i+1]=dy;
                    }
                    return flow;
                }
            }
        }
    }
    private static void requireTensor(NodeInfo info,long[] shape) {
        if(info==null||!(info.getInfo() instanceof TensorInfo)) throw new IllegalArgumentException("RAFT input tensor missing");
        TensorInfo tensor=(TensorInfo)info.getInfo(); long[] actual=tensor.getShape();
        if(tensor.type!=OnnxJavaType.FLOAT || actual.length!=shape.length) throw new IllegalArgumentException("RAFT inputs must be float32 NCHW");
        for(int i=0;i<shape.length;i++) if(actual[i]>0&&actual[i]!=shape[i]) throw new IllegalArgumentException("RAFT input shape differs from pack contract");
    }
    static float[] chw(Bitmap source,int width,int height) {
        Bitmap scaled=Bitmap.createScaledBitmap(source,width,height,true); int pixels=width*height; int[] rgb=new int[pixels]; scaled.getPixels(rgb,0,width,0,0,width,height);
        float[] data=new float[pixels*3]; for(int i=0;i<pixels;i++) {data[i]=(rgb[i]>>16)&255; data[pixels+i]=(rgb[i]>>8)&255; data[pixels*2+i]=rgb[i]&255;}
        if(scaled!=source) scaled.recycle(); return data;
    }
    public static void writeFlow(File file,float[] flow) throws IOException {
        try(DataOutputStream out=new DataOutputStream(new BufferedOutputStream(new FileOutputStream(file)))) {for(float v:flow) out.writeFloat(v);}
    }
}
