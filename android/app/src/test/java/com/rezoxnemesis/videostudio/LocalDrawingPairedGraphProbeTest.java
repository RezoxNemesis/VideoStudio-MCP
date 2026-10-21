package com.rezoxnemesis.videostudio;

import ai.onnxruntime.*;
import ai.onnxruntime.platform.Fp16Conversions;
import org.json.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import java.io.*;
import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.Assert.*;

/** Opt-in real JNI graph probe. Synthetic tensors, no user art and no image output.
 * This proves technical input routing only, not likeness, poses or a usable provider.
 */
@RunWith(RobolectricTestRunner.class) @Config(sdk=33,manifest=Config.NONE)
public class LocalDrawingPairedGraphProbeTest {
 private static final String[] PACKS={"clip-vit-h-14-image-fp16","sd15-controlnet-canny-fp16","sd15-ipadapter-fp16"};
 private static final long[] SIZES={1264856075L,723055316L,1764924739L};
 private static final String[] HASHES={"038078ba22b67e57ec2ca5b466e0699f30e14a9613054be8c7fb24e537987689","399358929322eb5bb2f0e141e23486397a29ec871e4efa625c0f2ba4d418c698","a0287f119d85b8028d9673850322247b5978ed9b504077bc04d433f4c9fadcb7"};
 private static final long[][] RESIDUALS={{1,320,64,64},{1,320,64,64},{1,320,64,64},{1,320,32,32},{1,640,32,32},{1,640,32,32},{1,640,16,16},{1,1280,16,16},{1,1280,16,16},{1,1280,8,8},{1,1280,8,8},{1,1280,8,8},{1,1280,8,8}};
 private OrtEnvironment environment;private File output;private JSONObject evidence;private final JSONArray stages=new JSONArray();
 @Test public void realPinnedGraphsRouteIndependentImageAndStructureInputsOnNativeCpu()throws Exception {
  String location=System.getenv("VIDEOSTUDIO_PAIRED_MODEL_PROBE"),destination=System.getenv("VIDEOSTUDIO_PAIRED_PROBE_OUTPUT");Assume.assumeTrue("Explicit public real-weight compatibility probe",location!=null&&destination!=null);
  output=new File(destination);assertTrue(output.mkdirs()||output.isDirectory());long start=System.currentTimeMillis();
  evidence=new JSONObject().put("technicalStatus","RUNNING").put("visualStatus","NOT_APPLICABLE").put("hostedInference",false).put("userArtworkProcessed",false).put("imageOutputCreated",false).put("phoneExecution",false).put("providerImplemented",false).put("renderer","VideoStudio Java test and app LocalDrawingEngine.run; ONNX Runtime CPU/JNI under Robolectric").put("stages",stages);
  ScheduledExecutorService memorySampler=Executors.newSingleThreadScheduledExecutor(r->{Thread thread=new Thread(r,"paired-model-memory-samples");thread.setDaemon(true);return thread;});
  memorySampler.scheduleAtFixedRate(()->{try{String rss="",hwm="",available="";for(String line:Files.readAllLines(new File("/proc/self/status").toPath(),StandardCharsets.UTF_8)){if(line.startsWith("VmRSS:"))rss=line.substring(6).trim();if(line.startsWith("VmHWM:"))hwm=line.substring(6).trim();}for(String line:Files.readAllLines(new File("/proc/meminfo").toPath(),StandardCharsets.UTF_8))if(line.startsWith("MemAvailable:"))available=line.substring(13).trim();Files.write(new File(output,"memory-samples.tsv").toPath(),(System.currentTimeMillis()+"\t"+rss+"\t"+hwm+"\t"+available+"\n").getBytes(StandardCharsets.UTF_8),java.nio.file.StandardOpenOption.CREATE,java.nio.file.StandardOpenOption.APPEND);}catch(IOException unavailable){}},0,1,TimeUnit.SECONDS);
  try {
   File root=new File(location);for(int i=0;i<PACKS.length;i++){File file=new File(root,PACKS[i]+"/model.onnx");assertEquals("Pinned file bytes: "+PACKS[i],SIZES[i],file.length());assertEquals("Pinned file SHA: "+PACKS[i],HASHES[i],LocalDrawingModels.hash(file));}
   environment=OrtEnvironment.getEnvironment();environment.setTelemetry(false);evidence.put("runtimeVersion",environment.getVersion());save("weights verified");
   float[] imageA,imageB;
   try(OrtSession clip=open(root,PACKS[0])){
    requireNames(clip.getInputInfo(),"pixel_values");require(clip.getInputInfo(),"pixel_values",1,3,224,224);requireNames(clip.getOutputInfo(),"image_embeds","onnx::Gather_4496");require(clip.getOutputInfo(),"image_embeds",1,1024);require(clip.getOutputInfo(),"onnx::Gather_4496",1,257,1280);
    try(OnnxTensor input=half(new float[3*224*224],1,3,224,224);LocalDrawingEngine runner=new LocalDrawingEngine();OrtSession.Result result=runner.run(clip,Collections.singletonMap("pixel_values",input))){imageA=values(result,"image_embeds",1,1024);values(result,"onnx::Gather_4496",1,257,1280);}
    float[] changed=new float[3*224*224];Arrays.fill(changed,.5f);
    try(OnnxTensor input=half(changed,1,3,224,224);LocalDrawingEngine runner=new LocalDrawingEngine();OrtSession.Result result=runner.run(clip,Collections.singletonMap("pixel_values",input))){imageB=values(result,"image_embeds",1,1024);}
    double delta=difference(imageA,imageB);assertTrue("Image encoder must respond to changed image tensor",delta>0);evidence.put("imageEncoderMeanAbsoluteDelta",delta);save("image encoder responds; session closes before diffusion networks");
   }
   try(OrtSession control=open(root,PACKS[1]);OrtSession unet=open(root,PACKS[2]);LocalDrawingEngine runner=new LocalDrawingEngine()){
    requireNames(control.getInputInfo(),"sample","timestep","encoder_hidden_states","canny_image");require(control.getInputInfo(),"sample",1,4,64,64);require(control.getInputInfo(),"timestep",1);require(control.getInputInfo(),"encoder_hidden_states",1,77,768);require(control.getInputInfo(),"canny_image",1,3,512,512);
    Set<String> unetInputs=new HashSet<>(Arrays.asList("sample","timestep","encoder_hidden_states","image_embeds"));String[] outputNames=new String[13];for(int i=0;i<13;i++){String name=i<12?"down_residual_"+i:"mid_residual";outputNames[i]=name;unetInputs.add(name);require(control.getOutputInfo(),name,RESIDUALS[i]);require(unet.getInputInfo(),name,RESIDUALS[i]);}requireNames(control.getOutputInfo(),outputNames);requireNames(unet.getInputInfo(),unetInputs.toArray(new String[0]));require(unet.getInputInfo(),"sample",1,4,64,64);require(unet.getInputInfo(),"timestep",1);require(unet.getInputInfo(),"encoder_hidden_states",1,77,768);require(unet.getInputInfo(),"image_embeds",1,1,1024);requireNames(unet.getOutputInfo(),"noise_pred");require(unet.getOutputInfo(),"noise_pred",1,4,64,64);
    save("native graph contracts verified");
    Map<String,OnnxTensor> common=new LinkedHashMap<>();float[] latent=new float[4*64*64];Random random=new Random(42);for(int i=0;i<latent.length;i++)latent[i]=(float)random.nextGaussian()*.1f;common.put("sample",half(latent,1,4,64,64));common.put("timestep",half(new float[]{501},1));common.put("encoder_hidden_states",half(new float[77*768],1,77,768));
    try {
     float[][] residualA=new float[13][],residualB=new float[13][];float[] edges=new float[3*512*512];for(int c=0;c<3;c++)for(int y=64;y<448;y++)edges[c*512*512+y*512+256]=1;
     try(OnnxTensor guide=half(new float[3*512*512],1,3,512,512)){Map<String,OnnxTensor> inputs=new LinkedHashMap<>(common);inputs.put("canny_image",guide);try(OrtSession.Result result=runner.run(control,inputs)){for(int i=0;i<13;i++)residualA[i]=values(result,outputNames[i],RESIDUALS[i]);}}
     try(OnnxTensor guide=half(edges,1,3,512,512)){Map<String,OnnxTensor> inputs=new LinkedHashMap<>(common);inputs.put("canny_image",guide);try(OrtSession.Result result=runner.run(control,inputs)){for(int i=0;i<13;i++)residualB[i]=values(result,outputNames[i],RESIDUALS[i]);}}
     double controlDelta=0;for(int i=0;i<13;i++)controlDelta+=difference(residualA[i],residualB[i]);assertTrue("ControlNet must respond to changed structural edges",controlDelta>0);evidence.put("controlResidualMeanAbsoluteDeltaSum",controlDelta);save("ControlNet responds to changed structural guide");
     float[] base=prediction(runner,unet,common,imageA,residualA),identity=prediction(runner,unet,common,imageB,residualA),structure=prediction(runner,unet,common,imageA,residualB);double imageDelta=difference(base,identity),poseDelta=difference(base,structure);assertTrue("Fused network must respond to image embeddings",imageDelta>0);assertTrue("Fused network must respond to structural residuals",poseDelta>0);evidence.put("unetImageMeanAbsoluteDelta",imageDelta).put("unetStructureMeanAbsoluteDelta",poseDelta);save("independent image and structure routes executed");
    }finally{for(OnnxTensor value:common.values())value.close();}
   }
   evidence.put("technicalStatus","PASS").put("elapsedMs",System.currentTimeMillis()-start);save("technical graph routing passes; no artistic or provider readiness verdict");
  }catch(Throwable error){evidence.put("technicalStatus","BLOCKED").put("errorType",error.getClass().getSimpleName()).put("error",String.valueOf(error.getMessage())).put("elapsedMs",System.currentTimeMillis()-start);save("probe stopped; evidence preserved");throw error;}finally{memorySampler.shutdownNow();}
 }
 private OrtSession open(File root,String pack)throws Exception {save("loading "+pack);try(OrtSession.SessionOptions options=new OrtSession.SessionOptions()){options.setIntraOpNumThreads(Math.max(1,Math.min(4,Runtime.getRuntime().availableProcessors())));options.setInterOpNumThreads(1);options.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.BASIC_OPT);options.setMemoryPatternOptimization(false);options.setCPUArenaAllocator(false);options.setDeterministicCompute(true);return environment.createSession(new File(root,pack+"/model.onnx").getPath(),options);}}
 private OnnxTensor half(float[] values,long...shape)throws Exception {return OnnxTensor.createTensor(environment,Fp16Conversions.convertFloatBufferToFp16Buffer(FloatBuffer.wrap(values)),shape,OnnxJavaType.FLOAT16);}
 private static void requireNames(Map<String,NodeInfo> nodes,String...names){assertEquals(new HashSet<>(Arrays.asList(names)),nodes.keySet());}
 private static void require(Map<String,NodeInfo> nodes,String name,long...shape){NodeInfo node=nodes.get(name);assertNotNull(name,node);assertTrue(node.getInfo() instanceof TensorInfo);TensorInfo info=(TensorInfo)node.getInfo();assertEquals(name,OnnxJavaType.FLOAT16,info.type);long[] actual=info.getShape();assertEquals(name,shape.length,actual.length);for(int i=0;i<shape.length;i++)assertTrue(name+" dimension "+i,actual[i]<0||actual[i]==shape[i]);}
 private static float[] values(OrtSession.Result result,String name,long...shape){OnnxTensor tensor=(OnnxTensor)result.get(name).orElseThrow(()->new IllegalStateException("Missing output "+name));assertEquals(OnnxJavaType.FLOAT16,tensor.getInfo().type);assertArrayEquals(shape,tensor.getInfo().getShape());FloatBuffer data=tensor.getFloatBuffer();float[] values=new float[data.remaining()];data.get(values);for(float value:values)assertTrue("Finite output "+name,Float.isFinite(value));return values;}
 private float[] prediction(LocalDrawingEngine runner,OrtSession unet,Map<String,OnnxTensor> common,float[] image,float[][] residuals)throws Exception {Map<String,OnnxTensor> inputs=new LinkedHashMap<>(common);List<OnnxTensor> owned=new ArrayList<>();try{OnnxTensor embedding=half(image,1,1,1024);owned.add(embedding);inputs.put("image_embeds",embedding);for(int i=0;i<13;i++){OnnxTensor residual=half(residuals[i],RESIDUALS[i]);owned.add(residual);inputs.put(i<12?"down_residual_"+i:"mid_residual",residual);}try(OrtSession.Result result=runner.run(unet,inputs)){return values(result,"noise_pred",1,4,64,64);}}finally{for(OnnxTensor tensor:owned)tensor.close();}}
 private static double difference(float[] first,float[] second){assertEquals(first.length,second.length);double sum=0;for(int i=0;i<first.length;i++)sum+=Math.abs(first[i]-second[i]);return sum/first.length;}
 private void save(String stage)throws Exception {JSONObject entry=new JSONObject().put("stage",stage).put("timeMs",System.currentTimeMillis());try{for(String line:Files.readAllLines(new File("/proc/self/status").toPath(),StandardCharsets.UTF_8))if(line.startsWith("VmRSS:")||line.startsWith("VmHWM:"))entry.put(line.substring(0,line.indexOf(':')),line.substring(line.indexOf(':')+1).trim());}catch(IOException unavailable){entry.put("memorySampleUnavailable",true);}stages.put(entry);Files.write(new File(output,"native-paired-graph-probe.json").toPath(),evidence.toString(2).getBytes(StandardCharsets.UTF_8));}
}
