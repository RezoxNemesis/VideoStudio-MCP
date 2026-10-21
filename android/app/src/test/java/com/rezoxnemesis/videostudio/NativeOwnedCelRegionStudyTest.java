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

/** Reusable native cel review: compare actual pixels against explicit authored motion regions. */
@RunWith(RobolectricTestRunner.class) @Config(sdk=33,manifest=Config.NONE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class NativeOwnedCelRegionStudyTest {
    @Test public void ownedFrameByFrameCelsKeepAllUnaffectedArtworkPixelExact() throws Exception {
        String path=System.getenv("VIDEOSTUDIO_OWNED_CEL_REGION_STUDY");
        Assume.assumeTrue("Explicit authored cel study manifest required",path!=null);
        ExecutorService worker=Executors.newSingleThreadExecutor();
        try {worker.submit(()->{try {run(path);return null;}catch(Exception e){throw new RuntimeException(e);}}).get(300,TimeUnit.SECONDS);}
        finally {worker.shutdownNow();}
    }
    private void run(String path) throws Exception {
        JSONObject manifest=new JSONObject(new String(Files.readAllBytes(new File(path).toPath()),StandardCharsets.UTF_8));
        File output=new File(manifest.getString("output"));assertFalse("Retain earlier generations",output.exists());assertTrue(output.mkdirs());
        File source=new File(manifest.getString("sourceArchive"));String sourceHash=SketchProjectStore.fileHash(source);
        Context context=RuntimeEnvironment.getApplication();JSONObject restored=SketchPaintArchive.restoreFile(context,source,sourceHash,"owned_cel_region","Owned cel movement review");
        JSONObject document;try(SketchProjectStore store=new SketchProjectStore(context)){document=store.get(restored.getString("sketchId"));}
        String before=SketchDocument.canonical(document);int w=document.getInt("width"),h=document.getInt("height"),count=document.getInt("frameCount");
        JSONArray regions=manifest.getJSONArray("motionRegions"),changedRange=manifest.getJSONArray("changedFramesInclusive"),frames=new JSONArray();int[] first=null;int changedFrames=0;
        for(int l=0;l<document.getJSONArray("layers").length();l++) {
            JSONObject layer=document.getJSONArray("layers").getJSONObject(l);assertEquals(0,layer.getJSONArray("keys").length());
            JSONArray objects=layer.getJSONArray("objects");for(int i=0;i<objects.length();i++) {
                JSONObject object=objects.getJSONObject(i);assertTrue("Authored cels only",java.util.Arrays.asList("path","paint","line").contains(object.getString("kind")));
                assertEquals(0,object.getJSONArray("keys").length());
            }
        }
        try(SketchFrameRenderer renderer=new SketchFrameRenderer(context,document)) {
            for(int frame=0;frame<count;frame++) {
                Bitmap bitmap=renderer.render(frame);int[] pixels=new int[w*h];bitmap.getPixels(pixels,0,w,0,0,w,h);
                File png=new File(output,String.format(java.util.Locale.ROOT,"frames/frame-%04d.png",frame));
                try {SketchActionFilmTest.png(png,bitmap);}finally {bitmap.recycle();}
                if(first==null)first=pixels;int changed=0,outside=0;
                for(int i=0;i<pixels.length;i++)if(first[i]!=pixels[i]) {
                    changed++;boolean inside=false;int x=i%w,y=i/w;
                    for(int r=0;r<regions.length();r++) {JSONArray box=regions.getJSONArray(r);if(x>=box.getInt(0)&&x<box.getInt(2)&&y>=box.getInt(1)&&y<box.getInt(3))inside=true;}
                    if(!inside)outside++;
                }
                frames.put(new JSONObject().put("frame",frame).put("sha256",SketchProjectStore.fileHash(png)).put("changedPixels",changed).put("outsideMotionRegions",outside));
                write(new File(output,"frame-evidence.json"),frames.toString(2));
                assertEquals("Unaffected body/costume changed at frame"+frame,0,outside);
                if(frame>=changedRange.getInt(0)&&frame<=changedRange.getInt(1)){assertTrue("Motion cel must change",changed>0);changedFrames++;}
                else assertEquals("Return cel must reproduce original authored pixels",0,changed);
            }
        }
        assertEquals(changedRange.getInt(1)-changedRange.getInt(0)+1,changedFrames);
        try(SketchProjectStore store=new SketchProjectStore(context)){assertEquals(before,SketchDocument.canonical(store.get(document.getString("id"))));}
        assertEquals(sourceHash,SketchProjectStore.fileHash(source));
        SketchPaintArchive.Prepared archive=SketchPaintArchive.prepare(context,new JSONObject().put("sketchId",document.getString("id")).put("expectedRevision",document.getLong("revision")).put("operationId","archive"));
        Files.copy(archive.file.toPath(),new File(output,"editable.vssketch").toPath());
        JSONObject preview=SketchEditOps.execute(context,"sketch_preview",new JSONObject().put("sketchId",document.getString("id")).put("expectedRevision",document.getLong("revision")).put("frameIndex",24).put("maxDimension",720).put("crop",manifest.getJSONObject("reviewCrop")));
        Files.write(new File(output,"native-motion-closeup.png").toPath(),android.util.Base64.decode(preview.getJSONObject("sketchPreviewImage").getString("base64"),android.util.Base64.DEFAULT));
        write(new File(output,"study-evidence.json"),new JSONObject().put("technicalStatus","PASS").put("visualStatus","UNREVIEWED")
                .put("appVersion",AppProtocol.APP_VERSION).put("sourceProof",manifest.getJSONObject("sourceProof")).put("sourceArchiveSha256",sourceHash)
                .put("outsideMotionRegionChangedPixels",0).put("returnCelPixelExact",true).put("scenarioCount",1).put("frameCount",count).put("fps",document.getInt("fps"))
                .put("phoneExecution",false).put("rigsUsed",false).put("referencePixelsInArtwork",false)
                .put("renderer","VideoStudio SketchFrameRenderer / Android native Canvas under Robolectric").put("assetLikenessCertified",false).toString(2));
    }
    private static void write(File file,String value) throws Exception {Files.write(file.toPath(),value.getBytes(StandardCharsets.UTF_8));}
}
