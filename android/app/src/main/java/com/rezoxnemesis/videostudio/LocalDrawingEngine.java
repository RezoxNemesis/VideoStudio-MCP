package com.rezoxnemesis.videostudio;

import ai.onnxruntime.*;
import android.graphics.Bitmap;
import android.graphics.Color;
import org.json.JSONObject;
import java.io.*;
import java.nio.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Actual offline SD1.5 ONNX inference. No networking, external process or drawing fallback. */
final class LocalDrawingEngine implements AutoCloseable {
    interface Progress { void report(int percent,String stage)throws Exception; }
    static final int EDGE=512, LATENT_EDGE=64, LATENTS=4*64*64, EMBEDDING=77*768;
    static final Semaphore INFERENCE_SLOT=new Semaphore(1,true);
    private final OrtEnvironment environment;
    private final ScheduledExecutorService watchdog=Executors.newSingleThreadScheduledExecutor(r->{Thread t=new Thread(r,"local-drawing-cancellation");t.setDaemon(true);return t;});
    LocalDrawingEngine(){environment=OrtEnvironment.getEnvironment();try{environment.setTelemetry(false);}catch(OrtException unavailable){throw new IllegalStateException("Cannot disable local inference telemetry",unavailable);}}

    String runtimeVersion(){return environment.getVersion();}

    static void validate(JSONObject p)throws Exception {
        SketchDocument.fields(p,"prompt,negativePrompt,seed,steps,strength,guidance");
        for(String key:new String[]{"prompt","negativePrompt"}){if(!p.has(key)&&key.equals("negativePrompt"))continue;Object value=p.get(key);if(!(value instanceof String)||((String)value).length()>1200||((String)value).indexOf(0)>=0||key.equals("prompt")&&((String)value).trim().isEmpty())throw new IllegalArgumentException("Local drawing requires a prompt of1..1200 characters");}
        SketchDocument.integer(p,"seed",0,2147483647);SketchDocument.integer(p,"steps",4,30);
        SketchDocument.number(p,"strength",.1,1);SketchDocument.number(p,"guidance",1,12);
    }
    Bitmap render(File model,Bitmap source,JSONObject settings,File checkpoints,String identity,Progress progress)throws Exception {
        validate(settings);if(source.getWidth()!=EDGE||source.getHeight()!=EDGE)throw new IllegalArgumentException("Native drawing source must be512px square");
        if(!identity.matches("[a-f0-9]{64}"))throw new IllegalArgumentException("Source/model request identity required");
        if(!checkpoints.isDirectory()&&!checkpoints.mkdirs())throw new IOException("Cannot create local drawing checkpoints");
        boolean acquired=false;
        try{
            while(!INFERENCE_SLOT.tryAcquire(100,TimeUnit.MILLISECONDS))check();acquired=true;
            check();LocalDrawingTokenizer tokenizer=new LocalDrawingTokenizer(model);
            long[] positive=tokenizer.encode(settings.getString("prompt")),negative=tokenizer.encode(settings.optString("negativePrompt",""));
            int[] times=new LocalDrawingSchedule().timesteps(settings.getInt("steps"),settings.getDouble("strength"));LocalDrawingSchedule schedule=new LocalDrawingSchedule();
            float[] conditioning=read(checkpoints,"conditioning.bin",identity,EMBEDDING*2).values;
            if(conditioning==null){
                progress.report(5,"Loading local text encoder");
                try(OrtSession session=open(model,"text_encoder/model.onnx")){
                    float[] a=encode(session,negative),b=encode(session,positive);conditioning=new float[EMBEDDING*2];System.arraycopy(a,0,conditioning,0,EMBEDDING);System.arraycopy(b,0,conditioning,EMBEDDING,EMBEDDING);
                }
                save(checkpoints,"conditioning.bin",identity,0,conditioning);
            }
            Snapshot resumed=read(checkpoints,"denoising.bin",identity,LATENTS);float[] latents=resumed.values;int completed=resumed.step;
            if(completed<0||completed>times.length)throw new IllegalStateException("LOCAL_DRAWING_CHECKPOINT_INVALID");
            if(latents==null){
                Snapshot encoded=read(checkpoints,"encoded.bin",identity,LATENTS);float[] clean=encoded.values;
                if(clean==null){
                    progress.report(12,"Encoding the selected reference on this device");int[] pixels=new int[EDGE*EDGE];source.getPixels(pixels,0,EDGE,0,0,EDGE,EDGE);float[] rgb=new float[3*EDGE*EDGE];
                    for(int i=0;i<pixels.length;i++){rgb[i]=Color.red(pixels[i])/127.5f-1;rgb[pixels.length+i]=Color.green(pixels[i])/127.5f-1;rgb[2*pixels.length+i]=Color.blue(pixels[i])/127.5f-1;}
                    try(OrtSession session=open(model,"vae_encoder/model.onnx");OnnxTensor input=tensor(rgb,1,3,EDGE,EDGE);OrtSession.Result out=run(session,Collections.singletonMap("sample",input))){clean=floats(out,0,1,4,LATENT_EDGE,LATENT_EDGE);}
                    for(int i=0;i<clean.length;i++)clean[i]*=LocalDrawingSchedule.LATENT_SCALE;
                    save(checkpoints,"encoded.bin",identity,0,clean);
                }
                Random random=new Random(settings.getLong("seed"));float[] noise=new float[LATENTS];for(int i=0;i<noise.length;i++)noise[i]=(float)random.nextGaussian();latents=schedule.addNoise(clean,noise,times[0]);completed=0;
                save(checkpoints,"denoising.bin",identity,completed,latents);
            }
            if(completed<times.length){
                progress.report(18,"Loading local diffusion network");
                try(OrtSession session=open(model,"unet/model.onnx");OnnxTensor unconditioned=tensor(Arrays.copyOfRange(conditioning,0,EMBEDDING),1,77,768);OnnxTensor conditioned=tensor(Arrays.copyOfRange(conditioning,EMBEDDING,2*EMBEDDING),1,77,768)){
                    for(int step=completed;step<times.length;step++){
                        check();progress.report(20+60*step/times.length,"Drawing locally: denoising step "+(step+1)+" of "+times.length);
                        float[] a=predict(session,latents,times[step],unconditioned),b=predict(session,latents,times[step],conditioned);
                        double guidance=settings.getDouble("guidance");for(int i=0;i<a.length;i++)a[i]=(float)(a[i]+guidance*(b[i]-a[i]));
                        latents=schedule.step(latents,a,times[step],step+1<times.length?times[step+1]:-1);
                        save(checkpoints,"denoising.bin",identity,step+1,latents);
                    }
                }
            }
            check();progress.report(85,"Decoding the locally generated raster draft");
            for(int i=0;i<latents.length;i++)latents[i]/=LocalDrawingSchedule.LATENT_SCALE;float[] rgb;
            try(OrtSession session=open(model,"vae_decoder/model.onnx");OnnxTensor input=tensor(latents,1,4,LATENT_EDGE,LATENT_EDGE);OrtSession.Result out=run(session,Collections.singletonMap("latent_sample",input))){rgb=floats(out,0,1,3,EDGE,EDGE);}
            int[] pixels=new int[EDGE*EDGE];for(int i=0;i<pixels.length;i++){if(i%EDGE==0)check();pixels[i]=Color.rgb(channel(rgb[i]),channel(rgb[pixels.length+i]),channel(rgb[2*pixels.length+i]));}
            Bitmap result=Bitmap.createBitmap(EDGE,EDGE,Bitmap.Config.ARGB_8888);result.setPixels(pixels,0,EDGE,0,0,EDGE,EDGE);return result;
        }finally{close();if(acquired)INFERENCE_SLOT.release();}
    }
    static int channel(float x)throws Exception{if(!Float.isFinite(x))throw new IOException("Non-finite decoded image");return Math.round(Math.max(0,Math.min(1,x*.5f+.5f))*255);}
    OrtSession open(File root,String path)throws Exception {
        check();File file=new File(root,path);if(!file.isFile()||!file.getCanonicalPath().startsWith(root.getCanonicalPath()+File.separator))throw new IOException("LOCAL_DRAWING_MODEL_MISSING: "+path);
        try(OrtSession.SessionOptions options=new OrtSession.SessionOptions()){
            options.setIntraOpNumThreads(Math.max(1,Math.min(4,Runtime.getRuntime().availableProcessors())));options.setInterOpNumThreads(1);
            options.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.BASIC_OPT);options.setMemoryPatternOptimization(false);options.setCPUArenaAllocator(false);options.setDeterministicCompute(true);
            return environment.createSession(file.getAbsolutePath(),options);
        }
    }
    float[] encode(OrtSession session,long[] ids)throws Exception {
        NodeInfo node=session.getInputInfo().get("input_ids");if(node==null||!(node.getInfo() instanceof TensorInfo))throw new IllegalArgumentException("CLIP input contract differs");
        OnnxJavaType type=((TensorInfo)node.getInfo()).type;OnnxTensor input;
        if(type==OnnxJavaType.INT32){int[] ints=new int[ids.length];for(int i=0;i<ints.length;i++)ints[i]=(int)ids[i];input=OnnxTensor.createTensor(environment,IntBuffer.wrap(ints),new long[]{1,77});}
        else if(type==OnnxJavaType.INT64)input=OnnxTensor.createTensor(environment,LongBuffer.wrap(ids),new long[]{1,77});
        else throw new IllegalArgumentException("Unsupported CLIP token tensor");
        try(OnnxTensor tokenInput=input;OrtSession.Result out=run(session,Collections.singletonMap("input_ids",tokenInput))){return floats(out,0,1,77,768);}
    }
    private float[] predict(OrtSession session,float[] latents,int time,OnnxTensor conditioning)throws Exception {
        try(OnnxTensor sample=tensor(latents,1,4,LATENT_EDGE,LATENT_EDGE);OnnxTensor timestep=tensor(new float[]{time},1)){
            Map<String,OnnxTensorLike> inputs=new HashMap<>();inputs.put("sample",sample);inputs.put("timestep",timestep);inputs.put("encoder_hidden_states",conditioning);
            try(OrtSession.Result out=run(session,inputs)){return floats(out,0,1,4,LATENT_EDGE,LATENT_EDGE);}
        }
    }
    OnnxTensor tensor(float[] input,long...shape)throws Exception{return OnnxTensor.createTensor(environment,FloatBuffer.wrap(input),shape);}
    OrtSession.Result run(OrtSession session,Map<String,? extends OnnxTensorLike> inputs)throws Exception {
        check();Thread owner=Thread.currentThread();Object guard=new Object();AtomicBoolean closed=new AtomicBoolean();
        try(OrtSession.RunOptions options=new OrtSession.RunOptions()){
            ScheduledFuture<?> termination=watchdog.scheduleAtFixedRate(()->{synchronized(guard){if(!closed.get()&&owner.isInterrupted())try{options.setTerminate(true);}catch(OrtException ignored){}}},50,50,TimeUnit.MILLISECONDS);
            try{return session.run(inputs,options);}catch(OrtException nativeError){if(owner.isInterrupted()){InterruptedException cancelled=new InterruptedException("Local AI drawing cancelled; saved checkpoints are kept");cancelled.initCause(nativeError);throw cancelled;}throw nativeError;}finally{termination.cancel(false);synchronized(guard){closed.set(true);}}
        }
    }
    static float[] floats(OrtSession.Result result,int index,long...shape)throws Exception {
        OnnxValue value=result.get(index);if(!(value instanceof OnnxTensor))throw new IOException("Model must return a tensor");OnnxTensor tensor=(OnnxTensor)value;
        if(tensor.getInfo().type!=OnnxJavaType.FLOAT||!Arrays.equals(shape,tensor.getInfo().getShape()))throw new IOException("LOCAL_DRAWING_TENSOR_CONTRACT_DIFFERS: "+Arrays.toString(tensor.getInfo().getShape()));
        FloatBuffer buffer=tensor.getFloatBuffer();float[] out=new float[buffer.remaining()];buffer.get(out);for(float x:out)if(!Float.isFinite(x))throw new IOException("Non-finite model tensor");return out;
    }
    static final class Snapshot{final int step;final float[] values;Snapshot(int s,float[] v){step=s;values=v;}}
    static Snapshot read(File directory,String name,String identity,int count)throws Exception {
        File f=new File(directory,name);if(!f.exists())return new Snapshot(0,null);if(f.length()!=4+64+4+4+4L*count+32)throw new IOException("LOCAL_DRAWING_CHECKPOINT_SIZE_DIFFERS");
        byte[] bytes=java.nio.file.Files.readAllBytes(f.toPath());java.security.MessageDigest digest=java.security.MessageDigest.getInstance("SHA-256");digest.update(bytes,0,bytes.length-32);
        if(!java.security.MessageDigest.isEqual(digest.digest(),Arrays.copyOfRange(bytes,bytes.length-32,bytes.length)))throw new IOException("LOCAL_DRAWING_CHECKPOINT_CHECKSUM_DIFFERS");
        try(DataInputStream in=new DataInputStream(new ByteArrayInputStream(bytes,0,bytes.length-32))){
            if(in.readInt()!=0x56444c32)throw new IOException("Invalid drawing checkpoint");byte[] id=new byte[64];in.readFully(id);if(!identity.equals(new String(id,java.nio.charset.StandardCharsets.US_ASCII)))throw new IOException("LOCAL_DRAWING_CHECKPOINT_IDENTITY_DIFFERS");int step=in.readInt();if(in.readInt()!=count)throw new IOException("Invalid latent count");float[] values=new float[count];for(int i=0;i<count;i++){values[i]=in.readFloat();if(!Float.isFinite(values[i]))throw new IOException("Invalid saved tensor");}return new Snapshot(step,values);
        }
    }
    static void save(File directory,String name,String identity,int step,float[] values)throws Exception {
        check();File part=new File(directory,".tensor_"+UUID.randomUUID());
        try{java.security.MessageDigest digest=java.security.MessageDigest.getInstance("SHA-256");try(FileOutputStream file=new FileOutputStream(part);DataOutputStream out=new DataOutputStream(new BufferedOutputStream(new java.security.DigestOutputStream(file,digest)))){out.writeInt(0x56444c32);out.write(identity.getBytes(java.nio.charset.StandardCharsets.US_ASCII));out.writeInt(step);out.writeInt(values.length);for(float value:values)out.writeFloat(value);out.flush();file.write(digest.digest());file.getFD().sync();}
            check();if(!part.renameTo(new File(directory,name)))throw new IOException("Cannot publish native tensor checkpoint");
        }finally{if(part.exists())part.delete();}
    }
    static void check()throws InterruptedException{if(Thread.currentThread().isInterrupted())throw new InterruptedException("Local neural drawing cancelled");}
    @Override public void close(){watchdog.shutdownNow();}
}
