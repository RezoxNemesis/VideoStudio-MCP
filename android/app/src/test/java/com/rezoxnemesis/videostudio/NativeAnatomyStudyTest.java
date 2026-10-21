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
import org.robolectric.annotation.GraphicsMode;
import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import static org.junit.Assert.*;

/** Native public-request portrait study. No test geometry or external drawing.
 * Optional VIDEOSTUDIO_ANATOMY_STUDY_EVIDENCE must name a fresh directory.
 * Optional VIEWS / BUILDS (same prefix) select comma-separated native variants.
 * Technical coverage and full-figure visibility do not certify anatomical art quality. */
@RunWith(RobolectricTestRunner.class) @Config(sdk=33,manifest=Config.NONE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class NativeAnatomyStudyTest {
    private static final int WIDTH=1280,HEIGHT=1600,BACKGROUND=0xFFFAF8F3;
    private static final String PREFIX="VIDEOSTUDIO_ANATOMY_STUDY_";
    private static final String[] VIEWS={"front","left","right","back","high","low","three_quarter_left","three_quarter_right"};

    private static String[] selected(String key,String fallback,String[] allowed){
        String value=System.getenv(PREFIX+key);String[] values=(value==null?fallback:value).split(",");
        for(String v:values)assertTrue("Unknown native study "+key+": "+v,Arrays.asList(allowed).contains(v));return values;
    }
    private static void json(File root,String name,JSONObject value)throws Exception{
        if(root==null)return;File out=new File(root,name);out.getParentFile().mkdirs();
        try(Writer writer=new OutputStreamWriter(new FileOutputStream(out),StandardCharsets.UTF_8)){writer.write(value.toString(2));}
    }
    private static void png(File root,String name,Bitmap image)throws Exception{
        if(root==null)return;File out=new File(root,name);out.getParentFile().mkdirs();
        try(FileOutputStream stream=new FileOutputStream(out)){assertTrue(image.compress(Bitmap.CompressFormat.PNG,100,stream));}
    }
    private static JSONObject create(String id)throws Exception{
        return new JSONObject().put("sketchId",id).put("operationId",id+"_create").put("name","Original native anatomy study")
            .put("width",WIDTH).put("height",HEIGHT).put("fps",24).put("frameCount",24).put("background","#FFFAF8F3");
    }
    private static JSONObject edit(String id,String view,String build)throws Exception{
        JSONObject settings=new JSONObject().put("figureStyle","anatomy_sketch").put("constructionGuides",false)
            .put("view",view).put("build",build).put("hairStyle","short").put("skinColor","#FFF1EEE8")
            .put("hairColor","#FF555150").put("trouserColor","#FFD5D0C9").put("shading","cel")
            .put("x",.5).put("floorY",.94).put("size",2);
        JSONObject human=new JSONObject().put("type","add_human_animation").put("layerId","layer_1").put("actorId","study")
            .put("settings",settings).put("fit",true).put("blinkTrack",false)
            .put("poses",new JSONArray().put(new JSONObject().put("frame",0).put("interpolation","hold")
                .put("nearElbowSwivel",90).put("farElbowSwivel",-90).put("nearWrist",-6).put("farWrist",6)));
        // Portrait height does not enlarge the existing 1280x720 construction fit.
        // A normal native object transform enlarges the complete figure and its ink
        // uniformly around the supplied floor, without changing bones or pose data.
        JSONObject scale=new JSONObject().put("type","set_transform").put("objectId","study_rig")
            .put("transform",new JSONObject().put("anchorX",.5).put("anchorY",.94).put("scaleX",1.8).put("scaleY",1.8));
        return new JSONObject().put("sketchId",id).put("expectedRevision",1).put("operationId",id+"_author")
            .put("operations",new JSONArray().put(human).put(scale));
    }
    private static JSONObject coverage(Bitmap image)throws Exception{
        int left=WIDTH,top=HEIGHT,right=-1,bottom=-1;int[] bands=new int[4],pixels=new int[WIDTH*HEIGHT];
        image.getPixels(pixels,0,WIDTH,0,0,WIDTH,HEIGHT);
        for(int y=0;y<HEIGHT;y++)for(int x=0;x<WIDTH;x++)if(pixels[y*WIDTH+x]!=BACKGROUND){
            left=Math.min(left,x);top=Math.min(top,y);right=Math.max(right,x);bottom=Math.max(bottom,y);bands[Math.min(3,y*4/HEIGHT)]++;
        }
        assertTrue("Actual native full figure must occupy over1100portrait pixels",bottom-top+1>1100);
        assertTrue("Keep the real silhouette off all four image edges",left>=12&&top>=12&&right<WIDTH-12&&bottom<HEIGHT-12);
        for(int band:bands)assertTrue("Actual upper/lower body ink must occupy every portrait quarter",band>100);
        return new JSONObject().put("pixelBounds",new JSONArray().put(left).put(top).put(right+1).put(bottom+1))
            .put("quarterPixelCounts",new JSONArray(bands));
    }
    @Test public void staticPortraitUsesPublicNativeRequestsAndKeepsTheWholeEditableFigure()throws Exception{
        String evidence=System.getenv(PREFIX+"EVIDENCE");File root=evidence==null?null:new File(evidence);
        if(root!=null){assertFalse("Preserve every evidence generation",root.exists());assertTrue(root.mkdirs());}
        Context context=RuntimeEnvironment.getApplication();JSONArray records=new JSONArray();
        for(String view:selected("VIEWS","front",VIEWS))for(String build:selected("BUILDS","slender",new String[]{"slender","broad"})){
            String name=view+"_"+build,id="anatomy_"+name;JSONObject creation=create(id),authoring=edit(id,view,build);
            json(root,name+"/create-request.json",creation);json(root,name+"/edit-request.json",authoring);
            JSONObject created=SketchEditOps.execute(context,"sketch_create",creation),edited=SketchEditOps.execute(context,"sketch_edit",authoring);
            json(root,name+"/create-response.json",created);json(root,name+"/edit-response.json",edited);
            JSONObject document;try(SketchProjectStore store=new SketchProjectStore(context)){document=store.get(id);}
            SketchDocument.validate(document);assertEquals(WIDTH,document.getInt("width"));assertEquals(HEIGHT,document.getInt("height"));assertEquals(24,document.getInt("frameCount"));assertEquals(24,document.getInt("fps"));
            JSONObject actor=SketchDocument.object(document,"study_rig");assertEquals("human",actor.getString("kind"));assertEquals("anatomy_sketch",actor.getJSONObject("human").getJSONObject("settings").getString("figureStyle"));
            assertEquals(1.8,actor.getJSONObject("transform").getDouble("scaleX"),0);assertEquals(1.8,actor.getJSONObject("transform").getDouble("scaleY"),0);
            JSONObject vector=SketchHumanAnimation.frameDocument(actor,WIDTH,HEIGHT,0);JSONArray ink=vector.getJSONArray("layers").getJSONObject(0).getJSONArray("objects");assertTrue("Native anatomy produces editable ink",ink.length()>0);
            boolean curves=false;for(int i=0;i<ink.length();i++){JSONObject path=ink.getJSONObject(i);assertEquals("path",path.getString("kind"));JSONArray commands=path.getJSONArray("commands");for(int k=0;k<commands.length();k++){String op=commands.getJSONArray(k).getString(0);curves|=op.equals("C")||op.equals("Q");}}
            assertTrue("Actual native structural contours include Bezier curves",curves);
            json(root,name+"/document.json",document);json(root,name+"/native-vector-frame.json",vector);
            try(SketchFrameRenderer renderer=new SketchFrameRenderer(context,document)){
                Bitmap first=renderer.render(0),last=renderer.render(23);
                try{assertEquals(WIDTH,first.getWidth());assertEquals(HEIGHT,first.getHeight());assertTrue("Static24frame study must preserve native endpoint pixels",first.sameAs(last));
                    JSONObject record=coverage(first).put("view",view).put("build",build).put("nativePathCount",ink.length()).put("staticEndpointPixelsEqual",true).put("frames",24).put("fps",24).put("artQuality","UNREVIEWED");records.put(record);
                    png(root,name+"/frame-0000.png",first);png(root,name+"/frame-0023.png",last);json(root,name+"/Receipt.json",record);
                }finally{first.recycle();last.recycle();}
            }
            SketchPaintArchive.Prepared archive=SketchPaintArchive.prepare(context,new JSONObject().put("sketchId",id)
                .put("expectedRevision",document.getLong("revision")).put("operationId",id+"_archive"));
            String archiveHash=SketchProjectStore.fileHash(archive.file);
            if(root!=null){
                File destination=new File(root,name+"/editable-source.vssketch");
                try(java.io.InputStream in=new java.io.FileInputStream(archive.file);FileOutputStream out=new FileOutputStream(destination)){
                    byte[] buffer=new byte[32768];int count;while((count=in.read(buffer))!=-1)out.write(buffer,0,count);
                }
            }
            JSONObject restored=SketchPaintArchive.restoreFile(context,archive.file,archiveHash,id+"_restore","Restored native anatomy study");
            json(root,name+"/restore-response.json",restored);
            JSONObject restoredDocument;try(SketchProjectStore store=new SketchProjectStore(context)){restoredDocument=store.get(restored.getString("sketchId"));}
            SketchDocument.validate(restoredDocument);assertEquals(24,restoredDocument.getInt("frameCount"));assertEquals(24,restoredDocument.getInt("fps"));
            JSONObject restoredActor=SketchDocument.object(restoredDocument,"study_rig");
            assertEquals("Archive preserves complete native anatomy settings and poses",SketchDocument.canonical(actor.getJSONObject("human")),SketchDocument.canonical(restoredActor.getJSONObject("human")));
            assertEquals("Archive preserves the normal native portrait object transform",SketchDocument.canonical(actor.getJSONObject("transform")),SketchDocument.canonical(restoredActor.getJSONObject("transform")));
            json(root,name+"/restored-document.json",restoredDocument);
            try(SketchFrameRenderer originalRenderer=new SketchFrameRenderer(context,document);SketchFrameRenderer restoredRenderer=new SketchFrameRenderer(context,restoredDocument)){
                for(int frame:new int[]{0,23}){Bitmap original=originalRenderer.render(frame),roundTrip=restoredRenderer.render(frame);
                    try{assertTrue("Restored actual native anatomy pixels at frame"+frame,original.sameAs(roundTrip));png(root,String.format(java.util.Locale.ROOT,name+"/restored-frame-%04d.png",frame),roundTrip);}
                    finally{original.recycle();roundTrip.recycle();}
                }
            }
            JSONObject record=records.getJSONObject(records.length()-1);record.put("archiveRestoredPixelComparisons",2).put("archiveSha256",archiveHash);json(root,name+"/Receipt.json",record);
            json(root,name+"/Archive-Receipt.json",new JSONObject().put("sha256",archiveHash).put("bytes",archive.file.length())
                .put("surface","Actual native SketchPaintArchive.prepare / restoreFile").put("restoredPixelComparisons",2).put("pixelsEqual",true));
        }
        json(root,"Capture-Receipt.json",new JSONObject().put("status","PASS").put("studies",records).put("renderer","Actual SketchEditOps / native SketchFrameRenderer")
            .put("phoneExecution",false).put("artQuality","UNREVIEWED"));
    }
}
