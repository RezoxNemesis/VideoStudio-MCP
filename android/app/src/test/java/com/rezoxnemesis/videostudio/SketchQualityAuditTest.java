package com.rezoxnemesis.videostudio;

import android.graphics.Color;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.util.Arrays;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=33,manifest=Config.NONE)
public final class SketchQualityAuditTest {
    @Test public void redRoseAndBlackBackgroundProduceMeasurableNativeColourEvidence(){
        int width=90,height=90;
        int[] pixels=new int[width*height];
        Arrays.fill(pixels,Color.BLACK);
        for(int y=25;y<70;y++)for(int x=20;x<70;x++)pixels[y*width+x]=0xffcb203e;
        SketchQualityAudit.Metric whole=SketchQualityAudit.measure(pixels,width,height,0,0,width,height);
        SketchQualityAudit.Metric rose=SketchQualityAudit.measure(pixels,width,height,20,25,70,70);
        SketchQualityAudit.Metric empty=SketchQualityAudit.measure(pixels,width,height,0,0,16,16);
        assertTrue(rose.redFraction>.9);
        assertTrue(rose.litFraction>.9);
        assertTrue(whole.redFraction>.15);
        assertEquals(0,empty.redFraction,0);
        assertEquals(0,empty.litFraction,0);
    }

    @Test public void unchangedAndChangingFramesAreDistinguishedWithoutClaimingArtisticQuality(){
        int w=48,h=48;int[] first=new int[w*h],second=new int[w*h];
        Arrays.fill(first,0xff101010);
        Arrays.fill(second,0xff101010);
        assertEquals(0,SketchQualityAudit.changedFraction(first,second,w,h),1e-9);
        for(int y=0;y<24;y++)for(int x=0;x<24;x++)second[y*w+x]=0xffd3334a;
        assertTrue(SketchQualityAudit.changedFraction(first,second,w,h)>.1);
    }

    @Test public void rejectsInvalidNativeRegionsRatherThanSilentlyApprovingThem(){
        int[] image=new int[64*64];
        try{SketchQualityAudit.measure(image,64,64,-1,0,32,32);fail("negative region accepted");}
        catch(IllegalArgumentException expected){assertTrue(expected.getMessage().contains("region"));}
        try{SketchQualityAudit.changedFraction(image,new int[4],64,64);fail("bad frame array accepted");}
        catch(IllegalArgumentException expected){assertTrue(expected.getMessage().contains("frames"));}
    }

    @Test public void nativeCanvasAuditFlagsHeldArtAndEnforcesPixelColourOnAuthoredRegions()throws Exception{
        JSONObject source=SketchDocument.create(new JSONObject()
                .put("sketchId","qa_original_strokes").put("name","QA native test")
                .put("width",320).put("height",400).put("fps",24)
                .put("frameCount",10).put("background","#FF000000"));
        JSONObject ink=SketchEditOps.completeObject(new JSONObject()
                .put("id","rose_red_patch").put("kind","rect").put("color","#FFC52A41")
                .put("width",.002).put("fill",true).put("frame",0).put("exposure",10)
                .put("bounds",new JSONObject().put("x",.2).put("y",.2)
                        .put("width",.26).put("height",.27)));
        source.getJSONArray("layers").getJSONObject(0).getJSONArray("objects").put(ink);
        SketchDocument.validate(source);
        String before=SketchDocument.canonical(source);
        JSONObject rose=new JSONObject().put("id","rose")
                .put("bounds",new JSONObject().put("x",.2).put("y",.2)
                        .put("width",.26).put("height",.27))
                .put("minRedFraction",.4).put("minLitFraction",.4);
        JSONObject missingGlove=new JSONObject().put("id","hand")
                .put("bounds",new JSONObject().put("x",.71).put("y",.64)
                        .put("width",.15).put("height",.15))
                .put("minLitFraction",.1);
        JSONObject args=new JSONObject().put("sketchId","qa_original_strokes")
                .put("expectedRevision",source.getLong("revision"))
                .put("frameOffset",0).put("frameLimit",10)
                .put("motionRequired",true).put("minNewCels",3)
                .put("regions",new JSONArray().put(rose).put(missingGlove));
        AtomicReference<JSONObject> result=new AtomicReference<>();
        AtomicReference<Throwable> failure=new AtomicReference<>();
        Thread background=new Thread(()->{
            try{result.set(SketchQualityAudit.inspect(RuntimeEnvironment.getApplication(),source,args));}
            catch(Throwable e){failure.set(e);}
        },"qa-native-render-test");
        background.start();
        background.join(20000);
        assertFalse("Worker thread must finish",background.isAlive());
        if(failure.get()!=null)throw new AssertionError("Native quality review failed",failure.get());
        JSONObject report=result.get();
        assertNotNull(report);
        assertEquals(10,report.getInt("sampleCount"));
        assertFalse(report.getBoolean("anatomyVerified"));
        assertFalse(report.getBoolean("visualAcceptanceVerified"));
        assertEquals(1,report.getInt("sampledNewCels"));
        assertTrue(report.getInt("alertCount")>=10);
        String findings=report.getJSONArray("alerts").toString();
        assertTrue(findings.contains("INSUFFICIENT_NEW_CELS"));
        assertTrue(findings.contains("POSSIBLY_FROZEN"));
        assertTrue(findings.contains("LOW_FORM_VISIBILITY"));
        assertEquals("Audit never changes drawing source",before,SketchDocument.canonical(source));
    }
}
