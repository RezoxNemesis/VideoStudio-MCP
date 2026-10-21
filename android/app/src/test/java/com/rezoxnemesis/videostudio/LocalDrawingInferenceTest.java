package com.rezoxnemesis.videostudio;
import ai.onnxruntime.*;
import android.content.Context;
import android.graphics.Bitmap;
import android.net.Uri;
import org.json.JSONObject;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.*;
import java.io.*;
import java.nio.FloatBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Collections;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class) @Config(sdk=33,manifest=Config.NONE) @GraphicsMode(GraphicsMode.Mode.NATIVE)
public class LocalDrawingInferenceTest {
 @Test public void actualOnnxJniRunsNumericFixtureWithoutAProcessOrMock()throws Exception {
  byte[] bytes;try(InputStream in=getClass().getResourceAsStream("/local_drawing/matmul.onnx")){assertNotNull(in);bytes=in.readAllBytes();}
  OrtEnvironment environment=OrtEnvironment.getEnvironment();assertEquals("1.23.2",environment.getVersion());
  try(OrtSession.SessionOptions options=new OrtSession.SessionOptions();OrtSession session=environment.createSession(bytes,options);OnnxTensor input=OnnxTensor.createTensor(environment,FloatBuffer.wrap(new float[]{1,2}),new long[]{1,2});OrtSession.Result output=session.run(Collections.singletonMap("x",input))){float[] values=new float[2];((OnnxTensor)output.get(0)).getFloatBuffer().get(values);assertArrayEquals(new float[]{7,10},values,0);}
 }
 @Test public void interruptTerminatesAnActualInFlightNativeRun()throws Exception{
  byte[] bytes;try(InputStream in=getClass().getResourceAsStream("/local_drawing/cancellable-loop.onnx")){assertNotNull(in);bytes=in.readAllBytes();}
  OrtEnvironment environment=OrtEnvironment.getEnvironment();CountDownLatch entered=new CountDownLatch(1),finished=new CountDownLatch(1);AtomicBoolean stopped=new AtomicBoolean(),completed=new AtomicBoolean();ExecutorService thread=Executors.newSingleThreadExecutor();
  try(OrtSession.SessionOptions options=new OrtSession.SessionOptions();OrtSession session=environment.createSession(bytes,options);OnnxTensor input=OnnxTensor.createTensor(environment,FloatBuffer.wrap(new float[]{0}),new long[]{1});LocalDrawingEngine engine=new LocalDrawingEngine()){
   Future<?> run=thread.submit(()->{entered.countDown();try(OrtSession.Result ignored=engine.run(session,Collections.singletonMap("x",input))){completed.set(true);}catch(Exception cancelled){stopped.set(cancelled instanceof OrtException||cancelled instanceof InterruptedException);}finally{finished.countDown();}});
   assertTrue(entered.await(5,TimeUnit.SECONDS));Thread.sleep(150);assertFalse("Numeric loop must still be in native execution before cancellation",completed.get());run.cancel(true);assertTrue("Interrupted ORT run must release its worker",finished.await(5,TimeUnit.SECONDS));assertTrue(stopped.get());assertFalse(completed.get());
  }finally{thread.shutdownNow();}
 }
 @Test public void realPrivateReferenceStudyExecutesTheNativeNeuralEngineAndReplaysItsCheckpoint()throws Exception {
  String model=System.getenv("VIDEOSTUDIO_LOCAL_DRAWING_MODEL"),manifest=System.getenv("VIDEOSTUDIO_LOCAL_DRAWING_STUDY");Assume.assumeTrue("Explicit real-model/reference study",model!=null&&manifest!=null);
  JSONObject study=new JSONObject(new String(Files.readAllBytes(new File(manifest).toPath()),StandardCharsets.UTF_8));File out=new File(study.getString("output"));assertTrue(out.mkdirs()||out.isDirectory());
  Context context=RuntimeEnvironment.getApplication();File original=new File(study.getString("sourcePath"));assertEquals(study.getString("sourceSha256"),SketchProjectStore.fileHash(original));ProjectStore projects=new ProjectStore(context);ProjectStore.Project project=projects.create("Private original reference, neural draft study");ProjectStore.Asset asset=projects.registerImportedAsset(project.id,Uri.fromFile(original),original.getName(),"image/jpeg",0,original.length());
  ExecutorService worker=Executors.newSingleThreadExecutor();try{worker.submit(()->{try{
   String id="local_drawing_source";SketchEditOps.execute(context,"sketch_create",new JSONObject().put("sketchId",id).put("operationId","create").put("width",512).put("height",512).put("frameCount",1));
   SketchEditOps.execute(context,"sketch_import_reference",new JSONObject().put("sketchId",id).put("operationId","import").put("expectedRevision",1).put("projectId",project.id).put("sourceProjectUpdatedAt",projects.get(project.id).updatedAt).put("assetId",asset.id).put("layerId","layer_1").put("objectId","guide").put("guideVisible",false));
   try(SketchProjectStore sketches=new SketchProjectStore(context)){
    JSONObject doc=sketches.get(id),reference=SketchDocument.object(doc,"guide").getJSONObject("reference");String before=SketchDocument.sha256(SketchDocument.canonical(doc).getBytes(StandardCharsets.UTF_8));
    JSONObject request=new JSONObject().put("requestId",study.getString("requestId")).put("sketchId",id).put("expectedRevision",doc.getLong("revision")).put("objectId","guide").put("sourceSha256",reference.getString("sha256")).put("sourceRect",study.getJSONObject("sourceRect")).put("settings",study.getJSONObject("settings"));
    Bitmap source=LocalDrawingSource.prepare(sketches,doc,request);try{
     save(source,new File(out,"native-prepared-reference.png"));String identity=SketchDocument.sha256(SketchDocument.canonical(request).getBytes(StandardCharsets.UTF_8));
     long started=System.currentTimeMillis();Bitmap result=new LocalDrawingEngine().render(new File(model),source,request.getJSONObject("settings"),new File(out,"checkpoints"),identity,(n,stage)->{System.out.println("Local neural study "+n+"% "+stage);Files.write(new File(out,"progress.json").toPath(),new JSONObject().put("percent",n).put("stage",stage).toString().getBytes(StandardCharsets.UTF_8));});
     try{save(result,new File(out,"native-neural-draft.png"));}finally{result.recycle();}
     long elapsed=System.currentTimeMillis()-started;
     Bitmap replay=new LocalDrawingEngine().render(new File(model),source,request.getJSONObject("settings"),new File(out,"checkpoints"),identity,(n,stage)->{});
     try{save(replay,new File(out,"native-neural-replayed.png"));}finally{replay.recycle();}
     assertEquals("Identical completed checkpoint must reproduce the same native decoded PNG",SketchProjectStore.fileHash(new File(out,"native-neural-draft.png")),SketchProjectStore.fileHash(new File(out,"native-neural-replayed.png")));
     assertEquals(before,SketchDocument.sha256(SketchDocument.canonical(sketches.get(id)).getBytes(StandardCharsets.UTF_8)));
     JSONObject evidence=new JSONObject().put("technicalStatus","PASS").put("runtimeVersion",OrtEnvironment.getEnvironment().getVersion()).put("visualStatus","UNREVIEWED").put("provider","sd15_onnx_v1").put("appVersion",AppProtocol.APP_VERSION).put("sourceUnchanged",true).put("request",request).put("elapsedMs",elapsed).put("outputSha256",SketchProjectStore.fileHash(new File(out,"native-neural-draft.png"))).put("hostedInference",false).put("externalArtworkProcess",false).put("phoneExecution",false).put("executionLocation","VideoStudio Java engine, real ONNX Runtime CPU/JNI on host; Android source preparation/PNG via native Canvas under Robolectric").put("outputKind","Reference-conditioned model-generated raster draft; not authored artist strokes or recovered semantic layers").put("vaeEncoderSampling","Model posterior sample is checkpointed; seed controls added denoising noise, not the encoder random node");
     Files.write(new File(out,"native-neural-evidence.json").toPath(),evidence.toString(2).getBytes(StandardCharsets.UTF_8));
    }finally{source.recycle();}
   }
   return null;
  }catch(Exception error){throw new RuntimeException(error);}}).get(2400,TimeUnit.SECONDS);}finally{worker.shutdownNow();}
 }
 private static void save(Bitmap image,File file)throws Exception{try(FileOutputStream stream=new FileOutputStream(file)){assertTrue(image.compress(Bitmap.CompressFormat.PNG,100,stream));}}
}
