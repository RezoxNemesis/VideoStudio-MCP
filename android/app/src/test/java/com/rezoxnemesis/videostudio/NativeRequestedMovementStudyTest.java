package com.rezoxnemesis.videostudio;

import android.content.Context;
import android.graphics.Bitmap;
import org.json.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.concurrent.*;
import static org.junit.Assert.*;

/** Literal ordinary native requests; diagnostic figures are never the accepted Temple actor. */
@RunWith(RobolectricTestRunner.class) @Config(sdk=33,manifest=Config.NONE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class NativeRequestedMovementStudyTest {
    @Test public void executeRequestedNativeMovementStudiesAndRecordUnsupportedRequirements() throws Exception {
        String path=System.getenv("VIDEOSTUDIO_REQUESTED_MOVEMENT_STUDY");
        Assume.assumeTrue("Explicit private movement manifest required",path!=null);
        ExecutorService worker=Executors.newSingleThreadExecutor();
        try {worker.submit(()->{try {run(path);return null;}catch(Exception e){throw new RuntimeException(e);}}).get(600,TimeUnit.SECONDS);}
        finally {worker.shutdownNow();}
    }
    private void run(String path) throws Exception {
        JSONObject manifest=new JSONObject(new String(Files.readAllBytes(new File(path).toPath()),StandardCharsets.UTF_8));
        File root=new File(manifest.getString("output"));assertFalse("Preserve previous generations",root.exists());assertTrue(root.mkdirs());
        JSONArray cases=manifest.getJSONArray("cases"),records=new JSONArray();int failures=0;
        Context context=RuntimeEnvironment.getApplication();
        for(int i=0;i<cases.length();i++) {
            JSONObject specification=cases.getJSONObject(i),record=new JSONObject().put("caseId",specification.getString("id"))
                    .put("appVersion",AppProtocol.APP_VERSION).put("sourceProof",manifest.getJSONObject("sourceProof"))
                    .put("visualStatus","UNREVIEWED").put("actor","native procedural diagnostic; not the Temple redraw")
                    .put("phoneExecution",false).put("androidCodecExecution",false).put("audioGenerated",false);
            records.put(record);File folder=new File(root,specification.getString("id"));assertTrue(folder.mkdirs());
            JSONArray calls=new JSONArray();String id="requested_"+i;
            try {
                JSONObject creation=new JSONObject().put("sketchId",id).put("operationId","create")
                        .put("width",1280).put("height",720).put("frameCount",96).put("fps",24).put("background","#FFF2DFCB");
                calls.put(new JSONObject().put("action","sketch_create").put("request",creation).put("receipt",SketchEditOps.execute(context,"sketch_create",creation)));
                JSONObject request=new JSONObject().put("sketchId",id).put("operationId","author").put("expectedRevision",1)
                        .put("operations",specification.getJSONArray("operations"));
                if(specification.has("expectedNativeRejection")) {
                    String before;try(SketchProjectStore store=new SketchProjectStore(context)){before=SketchDocument.canonical(store.get(id));}
                    try {SketchEditOps.execute(context,"sketch_edit",request);fail("Unsupported requirement was admitted");}
                    catch(IllegalArgumentException error) {
                        assertTrue(error.getMessage(),error.getMessage().contains(specification.getString("expectedNativeRejection")));
                        calls.put(new JSONObject().put("action","sketch_edit").put("request",request).put("error",error.getMessage()));
                        record.put("technicalStatus","BLOCKED").put("reason",error.getMessage()).put("requestedOutcomeAchieved",false);
                    }
                    try(SketchProjectStore store=new SketchProjectStore(context)){assertEquals("Rejected request must be atomic",before,SketchDocument.canonical(store.get(id)));}
                } else {
                    calls.put(new JSONObject().put("action","sketch_edit").put("request",request).put("receipt",SketchEditOps.execute(context,"sketch_edit",request)));
                    JSONObject document;try(SketchProjectStore store=new SketchProjectStore(context)){document=store.get(id);}
                    String before=SketchDocument.canonical(document);JSONArray frames=new JSONArray();
                    try(SketchFrameRenderer renderer=new SketchFrameRenderer(context,document)) {
                        for(int frame=0;frame<96;frame++) {
                            File png=new File(folder,String.format(java.util.Locale.ROOT,"frames/frame-%04d.png",frame));
                            Bitmap bitmap=renderer.render(frame);try {SketchActionFilmTest.png(png,bitmap);}finally {bitmap.recycle();}
                            frames.put(new JSONObject().put("frame",frame).put("sha256",SketchProjectStore.fileHash(png)));
                        }
                    }
                    SketchPaintArchive.Prepared archive=SketchPaintArchive.prepare(context,new JSONObject().put("sketchId",id).put("expectedRevision",2).put("operationId","archive"));
                    Files.copy(archive.file.toPath(),new File(folder,"editable.vssketch").toPath());
                    JSONObject restored=SketchPaintArchive.restoreFile(context,archive.file,SketchProjectStore.fileHash(archive.file),"restored_"+i,"Native movement review");
                    try(SketchProjectStore store=new SketchProjectStore(context);SketchFrameRenderer a=new SketchFrameRenderer(context,document);
                        SketchFrameRenderer b=new SketchFrameRenderer(context,store.get(restored.getString("sketchId")))) {
                        for(int frame:new int[]{0,36,60,95}) {Bitmap x=a.render(frame),y=b.render(frame);try {assertTrue("Archive changed native pixels",x.sameAs(y));}finally{x.recycle();y.recycle();}}
                        assertEquals(before,SketchDocument.canonical(store.get(id)));
                    }
                    write(new File(folder,"document.json"),document);write(new File(folder,"frame-hashes.json"),frames);
                    record.put("technicalStatus","PASS").put("allFramesRendered",true).put("frameCount",96).put("fps",24)
                            .put("durationSeconds",4).put("audioGenerated",false).put("requestedOutcomeAchieved",false)
                            .put("limitations",specification.getString("limitations"));
                }
            } catch(Exception|AssertionError error) {
                failures++;record.put("technicalStatus","FAIL").put("error",error.getClass().getSimpleName()+": "+error.getMessage());
            }
            write(new File(folder,"native-calls.json"),calls);write(new File(folder,"case-evidence.json"),record);
            write(new File(root,"case-index.json"),new JSONObject().put("cases",records).put("technicalFailures",failures)
                    .put("countSemantics","Distinct requested scenarios; frames, assertions and reruns are not additional scenarios")
                    .put("renderer","VideoStudio SketchFrameRenderer / Android native Canvas under Robolectric"));
        }
        assertEquals("Preserve every failed case; investigate without removing requests",0,failures);
    }
    private static void write(File file,Object json) throws Exception {
        Files.write(file.toPath(),json.toString().getBytes(StandardCharsets.UTF_8));
    }
}
