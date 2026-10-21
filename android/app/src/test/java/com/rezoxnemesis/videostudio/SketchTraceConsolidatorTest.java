package com.rezoxnemesis.videostudio;

import android.content.Context;
import android.graphics.Bitmap;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=33,manifest=Config.NONE)
public final class SketchTraceConsolidatorTest {
    private static JSONObject source() throws Exception {
        JSONObject doc=SketchDocument.create(new JSONObject()
                .put("sketchId","pixel_trace_original_do_not_overwrite")
                .put("name","Native editable Drive trace experiment")
                .put("width",320).put("height",400).put("fps",24)
                .put("frameCount",200).put("background","#FF10192B"));
        JSONObject traced=new JSONObject().put("id","trace_flat_colours")
                .put("name","Only the editable trace regions")
                .put("visible",true).put("locked",false).put("opacity",1)
                .put("transform",SketchDocument.transform()).put("keys",new JSONArray())
                .put("objects",new JSONArray());
        String[] tones={"#FF152335","#FF2A3344","#FF3A4856","#FF213244"};
        int id=0;
        for(int row=0;row<12;row++){
            for(int col=0;col<16;col++){
                double x=col/16d,y=row/12d,w=1d/16,h=1d/12;
                JSONArray commands=new JSONArray()
                        .put(new JSONArray().put("M").put(x).put(y))
                        .put(new JSONArray().put("L").put(x+w).put(y))
                        .put(new JSONArray().put("L").put(x+w).put(y+h))
                        .put(new JSONArray().put("L").put(x).put(y+h))
                        .put(new JSONArray().put("Z"));
                traced.getJSONArray("objects").put(new JSONObject()
                        .put("id","rr_trace_test_"+id++)
                        .put("kind","path").put("frame",0).put("exposure",200)
                        .put("color",tones[(row+col)%tones.length]).put("width",.0006)
                        .put("fill",true).put("commands",commands)
                        .put("transform",SketchDocument.transform()).put("keys",new JSONArray()));
            }
        }
        doc.getJSONArray("layers").put(traced);
        SketchDocument.validate(doc);return doc;
    }

    private static JSONObject request(int window,int maxMerge) throws Exception {
        return new JSONObject().put("type","consolidate_path_runs")
                .put("layerId","trace_flat_colours").put("window",window)
                .put("maxMergeSize",maxMerge).put("idPrefix","rr_trace_");
    }

    @Test public void manyNativeEditablePixelsCompactIntoFewerNativeEditablePaths()
            throws Exception{
        JSONObject original=source(),modified=SketchDocument.copy(original);
        String originalHash=SketchDocument.canonical(original);
        int before=SketchDocument.layer(original,"trace_flat_colours")
                .getJSONArray("objects").length();
        SketchTraceConsolidator.apply(modified,request(32,16));
        SketchDocument.validate(modified);
        JSONArray after=SketchDocument.layer(modified,"trace_flat_colours").getJSONArray("objects");
        assertEquals(192,before);
        assertTrue("Merge must free room for many animation cels",after.length()<60);
        assertEquals("Source must not be modified",originalHash,SketchDocument.canonical(original));
        boolean foundCompound=false;
        for(int i=0;i<after.length();i++){
            JSONObject path=after.getJSONObject(i);
            assertEquals("path",path.getString("kind"));
            assertTrue(path.getString("id").startsWith("rr_trace_"));
            if(path.getJSONArray("commands").length()>6)foundCompound=true;
        }
        assertTrue("Compound paths must retain multiple native M...Z subpaths",foundCompound);
    }

    @Test public void mergedVisibleArtKeepsAlmostAllNativeCanvasPixels()throws Exception {
        JSONObject original=source(),modified=SketchDocument.copy(original);
        SketchTraceConsolidator.apply(modified,request(32,16));
        Context context=RuntimeEnvironment.getApplication();
        ExecutorService worker=Executors.newSingleThreadExecutor();
        try {
            double fraction=worker.submit(()->{
                try(SketchFrameRenderer before=new SketchFrameRenderer(context,original);
                    SketchFrameRenderer after=new SketchFrameRenderer(context,modified)){
                    Bitmap a=before.render(0,true,384,null),b=after.render(0,true,384,null);
                    try{
                        assertEquals(a.getWidth(),b.getWidth());
                        assertEquals(a.getHeight(),b.getHeight());
                        int n=a.getWidth()*a.getHeight(),errors=0;
                        int[] first=new int[n],second=new int[n];
                        a.getPixels(first,0,a.getWidth(),0,0,a.getWidth(),a.getHeight());
                        b.getPixels(second,0,b.getWidth(),0,0,b.getWidth(),b.getHeight());
                        for(int j=0;j<n;j++){
                            int left=first[j],right=second[j];
                            int dr=Math.abs(((left>>>16)&255)-((right>>>16)&255));
                            int dg=Math.abs(((left>>>8)&255)-((right>>>8)&255));
                            int db=Math.abs((left&255)-(right&255));
                            if(Math.max(dr,Math.max(dg,db))>16)errors++;
                        }
                        return errors/(double)n;
                    }finally{a.recycle();b.recycle();}
                }catch(Exception e){throw new RuntimeException(e);}
            }).get(30,TimeUnit.SECONDS);
            assertTrue("Visible pixels must remain broadly equivalent; fraction="+fraction,
                    fraction<.085);
        }finally{worker.shutdownNow();}
    }

    @Test public void opaqueOriginalStrokesOutsideTheTraceCannotBeMerged()throws Exception {
        JSONObject doc=source(),layer=SketchDocument.layer(doc,"trace_flat_colours");
        JSONArray all=layer.getJSONArray("objects");
        JSONObject authored=SketchDocument.copy(all.getJSONObject(0));
        authored.put("id","artist_original_001");
        all.put(authored);
        JSONObject guide=SketchDocument.copy(all.getJSONObject(1));
        guide.put("id","rr_trace_guide_do_not_merge");
        guide.put("color","#44556677");
        all.put(guide);
        int length=all.length();
        SketchTraceConsolidator.apply(doc,request(24,12));
        SketchDocument.validate(doc);
        JSONArray remaining=layer.getJSONArray("objects");
        assertTrue(remaining.length()<length);
        int originals=0,translucent=0;
        for(int i=0;i<remaining.length();i++){
            JSONObject p=remaining.getJSONObject(i);
            if("artist_original_001".equals(p.getString("id")))originals++;
            if("rr_trace_guide_do_not_merge".equals(p.getString("id")))translucent++;
        }
        assertEquals(1,originals);
        assertEquals(1,translucent);
    }

    @Test public void rejectsUnknownLayersAndNoOpWithoutTouchingSource()throws Exception {
        JSONObject source=source(),copy=SketchDocument.copy(source);
        String snapshot=SketchDocument.canonical(copy);
        try{
            SketchTraceConsolidator.apply(copy,new JSONObject()
                    .put("type","consolidate_path_runs")
                    .put("layerId","layer_1").put("idPrefix","rr_trace_"));
            fail("No eligible paths should fail");
        }catch(IllegalArgumentException expected){
            assertTrue("Must reject empty or non-mergeable native trace layers",
                    expected.getMessage().contains("no paths")
                    ||expected.getMessage().contains("No safe identical-style"));
        }
        assertEquals(snapshot,SketchDocument.canonical(copy));
    }
}
