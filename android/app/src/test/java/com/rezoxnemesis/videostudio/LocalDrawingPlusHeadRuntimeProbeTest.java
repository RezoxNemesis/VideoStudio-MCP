package com.rezoxnemesis.videostudio;
import ai.onnxruntime.*;
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
import static org.junit.Assert.*;
/** Opt-in actual CPU execution of both public heads; numeric diagnostic, no artwork. */
@RunWith(RobolectricTestRunner.class) @Config(sdk=33,manifest=Config.NONE)
public class LocalDrawingPlusHeadRuntimeProbeTest {
 private static final int[] INNER={320,320,640,640,1280,1280,1280,1280,1280,640,640,640,320,320,320,1280};
 @Test public void bothPinnedHeadsExecuteEveryExplicitKvPortWithFiniteDistinctValues()throws Exception {
  String path=System.getenv("VIDEOSTUDIO_PLUS_GRAPH_PROBE");Assume.assumeTrue("Explicit public Plus head runtime probe",path!=null&&!path.isEmpty());JSONObject config=new JSONObject(new String(Files.readAllBytes(new File(path).toPath()),StandardCharsets.UTF_8));Assume.assumeTrue("Explicit both-head configuration",config.has("plusHead"));File directory=new File(config.getString("outputDirectory"));assertTrue(directory.mkdirs()||directory.isDirectory());File evidence=new File(directory,"native-both-plus-heads.json");assertFalse("Preserve prior evidence",evidence.exists());JSONObject report=new JSONObject().put("status","RUNNING").put("sourceCommit",config.getString("sourceCommit")).put("appVersion",AppProtocol.APP_VERSION).put("artworkProduced",false).put("phoneExecution",false).put("creativeScenarios",0).put("renderer","VideoStudio LocalDrawingEngine ONNX Runtime CPU/JNI numeric diagnostic").put("startedAt",System.currentTimeMillis());write(evidence,report);
  try(LocalDrawingEngine runner=new LocalDrawingEngine()){
   OrtEnvironment environment=OrtEnvironment.getEnvironment();float[] hidden=new float[257*1280];for(int i=0;i<hidden.length;i++)hidden[i]=(float)(.15*Math.sin(i*.013)+.1*Math.cos(i*.003));report.put("input","Same bounded synthetic FP32 hidden tensor [1,257,1280], not a character embedding or artwork");float[][] outputs=new float[2][];JSONArray heads=new JSONArray();
   for(int variant=0;variant<2;variant++){String name=variant==0?"plus_face":"plus",key=variant==0?"head":"plusHead",sha=variant==0?"d30b57dfb4f90198486901143eae5ba8d8f2179b8677e475fcb225e696826c33":"52b2ef055e97b473ccb91dedf9e79af7783a4469793f6bcb51d22c3dfd5741c8";File file=new File(config.getString(key)).getCanonicalFile();assertEquals(sha,LocalDrawingModels.hash(file));long start=System.currentTimeMillis();JSONObject ports=new JSONObject();int count=0;for(int inner:INNER)count+=inner*16*2;float[] values=new float[count];
    try(OrtSession session=runner.open(file.getParentFile(),file.getName())){assertEquals(1,session.getInputInfo().size());tensor(session.getInputInfo().get("hidden"),1,257,1280);assertEquals(32,session.getOutputInfo().size());try(OnnxTensor input=runner.tensor(hidden,1,257,1280);OrtSession.Result result=runner.run(session,Collections.singletonMap("hidden",input))){int offset=0;for(int layer=0;layer<16;layer++)for(boolean keyTensor:new boolean[]{true,false}){String port=(keyTensor?"ipk_":"ipv_")+layer;long[] shape=keyTensor?new long[]{1,INNER[layer],16}:new long[]{1,16,INNER[layer]};tensor(session.getOutputInfo().get(port),shape);OnnxValue value=result.get(port).orElseThrow(()->new IllegalStateException("Missing actual head output "+port));assertTrue(value instanceof OnnxTensor);OnnxTensor output=(OnnxTensor)value;assertEquals(OnnxJavaType.FLOAT,output.getInfo().type);assertArrayEquals(shape,output.getInfo().getShape());FloatBuffer data=output.getFloatBuffer();int n=data.remaining();assertEquals(INNER[layer]*16,n);for(int i=0;i<n;i++){float number=data.get();assertTrue("Finite actual "+name+"/"+port,Float.isFinite(number));values[offset++]=number;}ports.put(port,new JSONObject().put("type","FLOAT").put("shape",new JSONArray(shape)));}assertEquals(values.length,offset);}}
    outputs[variant]=values;heads.put(new JSONObject().put("adapter",name).put("sha256",sha).put("bytes",file.length()).put("ports",ports).put("elapsedMs",System.currentTimeMillis()-start));
   }
   double abs=0,max=0;for(int i=0;i<outputs[0].length;i++){double delta=Math.abs((double)outputs[0][i]-outputs[1][i]);abs+=delta;max=Math.max(max,delta);}assertTrue("The two real head weights must not silently select the same provider",max>1e-6);report.put("heads",heads).put("headForwardPasses",2).put("difference",new JSONObject().put("maxAbs",max).put("meanAbs",abs/outputs[0].length)).put("runtimeVersion",runner.runtimeVersion()).put("status","TECHNICAL_BOTH_HEAD_PORTS_PASS_NO_ARTWORK");
  }catch(Exception|AssertionError|LinkageError failure){report.put("status","FAIL").put("error",failure.toString());throw failure;}finally{report.put("finishedAt",System.currentTimeMillis());JSONObject memory=new JSONObject();try{for(String line:Files.readAllLines(new File("/proc/self/status").toPath(),StandardCharsets.UTF_8))if(line.startsWith("VmRSS:")||line.startsWith("VmHWM:"))memory.put(line.substring(0,line.indexOf(':')),line.substring(line.indexOf(':')+1).trim());}catch(Exception ignored){}report.put("hostProcessMemory",memory);write(evidence,report);}
 }
 private static void tensor(NodeInfo info,long...shape){assertNotNull(info);assertTrue(info.getInfo() instanceof TensorInfo);TensorInfo tensor=(TensorInfo)info.getInfo();assertEquals(OnnxJavaType.FLOAT,tensor.type);assertArrayEquals(shape,tensor.getShape());}
 private static void write(File file,JSONObject report)throws Exception {Files.write(file.toPath(),report.toString(2).getBytes(StandardCharsets.UTF_8));}
}
