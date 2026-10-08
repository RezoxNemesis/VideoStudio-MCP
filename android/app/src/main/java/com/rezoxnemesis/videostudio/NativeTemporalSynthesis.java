package com.rezoxnemesis.videostudio;
import android.content.Context;import android.graphics.*;import android.util.AtomicFile;import ai.onnxruntime.*;import org.json.*;import java.io.*;import java.nio.*;import java.util.*;

/** Experimental recurrent image-conditioned ONNX ABI; no trained weights are bundled. */
public final class NativeTemporalSynthesis {
    public interface Progress {void checkpoint(int frame,int total,boolean modelResident) throws Exception;}
    private final Context context;
    public NativeTemporalSynthesis(Context c){context=c.getApplicationContext();}
    public static boolean compatible(JSONObject manifest){JSONObject abi=manifest.optJSONObject("motion");if(!"onnx-image-to-video-v1".equals(manifest.optString("backend"))||abi==null)return false;int edge=abi.optInt("edge",0),channels=abi.optInt("stateChannels",0);return (edge==256||edge==384)&&channels>=1&&channels<=64&&"rgb-minus-one-to-one".equals(abi.optString("normalization"))&&manifest.optJSONObject("files")!=null&&manifest.optJSONObject("files").optString(abi.optString("model")).matches("[a-fA-F0-9]{64}");}
    public void generate(String packId,String contract,ProjectStore.Asset source,JSONObject plan,File folder,Progress progress)throws Exception{
        File root=new ModelPackManager(context).installedDirectory(packId);JSONObject manifest=new JSONObject(SceneMemoryStore.readSmall(new File(root,"manifest.json")));NativeModelContract.verify(manifest,contract);if(!compatible(manifest))throw new IllegalArgumentException("Install a verified onnx-image-to-video-v1 pack. SD-Turbo keyframes and RAFT interpolation cannot synthesize new subject motion.");
        JSONObject abi=manifest.getJSONObject("motion");int edge=abi.getInt("edge"),channels=abi.getInt("stateChannels"),size=edge*edge*3,stateSize=channels*(edge/8)*(edge/8);
        File model=new File(root,abi.getString("model"));if(!model.getCanonicalPath().startsWith(root.getCanonicalPath()+File.separator))throw new IOException("Unsafe model path");
        try(InputStream in=new FileInputStream(model)){if(!SceneMemoryStore.hashStream(in,6L*1024*1024*1024).equalsIgnoreCase(manifest.getJSONObject("files").getString(abi.getString("model"))))throw new IOException("Temporal model hash mismatch");}
        JSONObject profile=new DeviceComputeProfile(context).snapshot();double required=Math.max(512,Math.max(abi.optDouble("workingSetMb",1024),model.length()/1048576.0*2));if(profile==null||profile.optDouble("activeWorkingSetBudgetMb",0)<required||profile.optLong("javaArrayBudgetMb")<24||!profile.optBoolean("thermalSafeForHeavyWork"))throw new IllegalStateException("Temporal model exceeds device memory budget");
        if(!folder.isDirectory()&&!folder.mkdirs())throw new IOException("Cannot create frame workspace");
        String sourceDigest;try(InputStream in=context.getContentResolver().openInputStream(android.net.Uri.parse(source.uri))){sourceDigest=SceneMemoryStore.hashStream(in,350L*1024*1024);}
        String fingerprint=SceneMemoryStore.hash((contract+sourceDigest+SceneMemoryStore.canonical(plan)).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        Bitmap decoded=MediaThumbnail.load(context,source,edge),image=Bitmap.createScaledBitmap(decoded,edge,edge,true);if(image!=decoded)decoded.recycle();
        float[] reference=rgb(image),previous=reference.clone(),hidden=new float[stateSize],mask=mask(plan,edge);image.recycle();int next=0,count=plan.getInt("frameCount");
        AtomicFile checkpoint=new AtomicFile(new File(folder,"recurrent.checkpoint"));
        if(checkpoint.getBaseFile().isFile())try(DataInputStream input=new DataInputStream(checkpoint.openRead())){if(!input.readUTF().equals(fingerprint))throw new IllegalStateException("Source, model or motion plan changed; start a new generation");next=input.readInt();if(next<0||next>count)throw new IOException("Invalid frame checkpoint");for(int i=0;i<previous.length;i++){previous[i]=input.readFloat();if(!Float.isFinite(previous[i]))throw new IOException("Invalid recurrent frame state");}for(int i=0;i<hidden.length;i++){hidden[i]=input.readFloat();if(!Float.isFinite(hidden[i]))throw new IOException("Invalid recurrent model state");}for(int i=0;i<next;i++)if(!new File(folder,String.format(Locale.US,"frame_%05d.png",i)).isFile())throw new IOException("Checkpoint frame is missing");}
        OrtEnvironment environment=OrtEnvironment.getEnvironment();for(int chunk=next;chunk<count;chunk+=8){progress.checkpoint(chunk,count,false);try(OrtSession.SessionOptions options=new OrtSession.SessionOptions()){
            options.setIntraOpNumThreads(2);options.setInterOpNumThreads(1);
            try(OrtSession session=environment.createSession(model.getAbsolutePath(),options)){
                for(int frame=chunk;frame<Math.min(count,chunk+8);frame++){
                    progress.checkpoint(frame,count,true);if(Thread.currentThread().isInterrupted())throw new InterruptedException();
                    try(OnnxTensor ref=OnnxTensor.createTensor(environment,FloatBuffer.wrap(reference),new long[]{1,3,edge,edge});OnnxTensor prior=OnnxTensor.createTensor(environment,FloatBuffer.wrap(previous),new long[]{1,3,edge,edge});OnnxTensor state=OnnxTensor.createTensor(environment,FloatBuffer.wrap(hidden),new long[]{1,channels,edge/8,edge/8});OnnxTensor brush=OnnxTensor.createTensor(environment,FloatBuffer.wrap(mask),new long[]{1,1,edge,edge});OnnxTensor controls=OnnxTensor.createTensor(environment,FloatBuffer.wrap(ImageMotionPlan.controls(plan,frame)),new long[]{1,12});OrtSession.Result out=session.run(Map.of("reference",ref,"previous_frame",prior,"recurrent_state",state,"motion_mask",brush,"motion_controls",controls))){
                        previous=output(out,"next_frame",new long[]{1,3,edge,edge});hidden=output(out,"next_state",new long[]{1,channels,edge/8,edge/8});
                    }
                    // Only selected motion regions may change. Unpainted pixels are preserved exactly.
                    for(int channel=0;channel<3;channel++)for(int i=0;i<edge*edge;i++){int offset=channel*edge*edge+i;previous[offset]=Math.max(-1,Math.min(1,previous[offset]))*mask[i]+reference[offset]*(1-mask[i]);}
                    Bitmap bitmap=bitmap(previous,edge);File output=new File(folder,String.format(Locale.US,"frame_%05d.png",frame));AtomicFile atomic=new AtomicFile(output);FileOutputStream stream=atomic.startWrite();try{if(!bitmap.compress(Bitmap.CompressFormat.PNG,100,stream))throw new IOException("Frame encoding failed");atomic.finishWrite(stream);}catch(Exception error){atomic.failWrite(stream);throw error;}finally{bitmap.recycle();}
                    FileOutputStream raw=checkpoint.startWrite();try{DataOutputStream data=new DataOutputStream(raw);data.writeUTF(fingerprint);data.writeInt(frame+1);for(float n:previous)data.writeFloat(n);for(float n:hidden)data.writeFloat(n);data.flush();checkpoint.finishWrite(raw);}catch(Exception error){checkpoint.failWrite(raw);throw error;}
                }
            }
        }
        }
        progress.checkpoint(count,count,false);
    }
    static float[] mask(JSONObject plan,int edge)throws Exception{float[] mask=new float[edge*edge];JSONArray strokes=plan.getJSONArray("motionStrokes");if(strokes.length()==0){Arrays.fill(mask,1);return mask;}for(int i=0;i<strokes.length();i++){JSONObject s=strokes.getJSONObject(i);float x=(float)s.getDouble("x")*edge,y=(float)s.getDouble("y")*edge,r=Math.max(1,(float)s.getDouble("radius")*edge);for(int py=Math.max(0,(int)(y-r));py<Math.min(edge,y+r+1);py++)for(int px=Math.max(0,(int)(x-r));px<Math.min(edge,x+r+1);px++){float distance=(float)Math.hypot(px-x,py-y);mask[py*edge+px]=Math.max(mask[py*edge+px],Math.max(0,Math.min(1,(r-distance)/Math.max(1,r*.15f))));}}return mask;}
    static float[] rgb(Bitmap b){int n=b.getWidth()*b.getHeight();int[] pixels=new int[n];b.getPixels(pixels,0,b.getWidth(),0,0,b.getWidth(),b.getHeight());float[] f=new float[n*3];for(int i=0;i<n;i++){f[i]=Color.red(pixels[i])/127.5f-1;f[i+n]=Color.green(pixels[i])/127.5f-1;f[i+2*n]=Color.blue(pixels[i])/127.5f-1;}return f;}
    private static Bitmap bitmap(float[] f,int edge){int n=edge*edge;int[] p=new int[n];for(int i=0;i<n;i++)p[i]=Color.rgb(channel(f[i]),channel(f[i+n]),channel(f[i+2*n]));Bitmap b=Bitmap.createBitmap(edge,edge,Bitmap.Config.ARGB_8888);b.setPixels(p,0,edge,0,0,edge,edge);return b;}
    private static int channel(float f){return Math.round(Math.max(0,Math.min(1,(f+1)/2))*255);}
    private static float[] output(OrtSession.Result result,String name,long[] shape)throws Exception{OnnxValue value=result.get(name).orElseThrow(()->new IllegalArgumentException("Missing temporal output: "+name));if(!(value instanceof OnnxTensor))throw new IllegalArgumentException("Temporal output must be a tensor");OnnxTensor t=(OnnxTensor)value;if(t.getInfo().type!=OnnxJavaType.FLOAT||!Arrays.equals(t.getInfo().getShape(),shape))throw new IllegalArgumentException("Temporal output ABI mismatch");FloatBuffer data=t.getFloatBuffer();float[] out=new float[data.remaining()];data.get(out);for(float n:out)if(!Float.isFinite(n))throw new IllegalStateException("Non-finite temporal output");return out;}
}
