package com.rezoxnemesis.videostudio;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.HashSet;
import java.util.Set;
import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;

/**
 * Native source and image evidence for a two-human cinematic fight exercise.
 * Structural contact, authored views and facial/art continuity are verified.
 * This does not claim automatic film-grade artistry or actual phone playback.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=33,manifest=Config.NONE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public final class SketchDuelAnimationExampleTest {
    static final File PROOF=new File("build/sketch-duel-film-proof");
    private final Context context=RuntimeEnvironment.getApplication();

    @Test public void sceneHasDistinctPreparedHumanViewsAccurateBatonCollisionAndTwoReactions()throws Exception{
        JSONObject built=SketchDuelAnimationExample.build(context);
        JSONObject source=built.getJSONObject("source"),program=built.getJSONObject("program"),report=built.getJSONObject("report");
        SketchDocument.validate(source);
        assertEquals(100,source.getInt("frameCount"));
        assertEquals(24,source.getInt("fps"));
        assertEquals(11,source.getJSONArray("layers").length());
        assertEquals(7,program.getJSONArray("rigs").length());
        assertEquals(2,report.getJSONArray("actors").length());
        assertEquals(3,report.getJSONArray("preparedViews").length());
        assertTrue("Two artist-prepared batons meet at impact",report.getDouble("batonWristDistancePx")<80);
        assertFalse("Artistic acceptance cannot be proven by pose constraints",report.getBoolean("professionalArtQualityVerified"));
        JSONObject compiled=SketchAnimationCompiler.compile(source,program,"standoff_compiled");
        assertEquals(2,compiled.getJSONObject("report").getInt("dialogueCount"));
        assertTrue(compiled.getJSONObject("report").getInt("mouthCelCount")>=10);
        assertTrue(compiled.getJSONObject("report").getInt("boundObjectCount")>180);
        assertTrue(compiled.getJSONObject("report").getBoolean("independentArticulation"));
        for(String layer:new String[]{"wide_courier","rook_wide","rook_pov","medium_courier","rook_medium"})
            assertTrue("Prepared human view has detailed separate native vector shapes",
                    SketchDocument.layer(source,layer).getJSONArray("objects").length()>15);
        assertTrue("Foreground POV hand and baton are deliberately fewer independent prop parts",
                SketchDocument.layer(source,"pov_gauntlet").getJSONArray("objects").length()>=10);
        for(String key:new String[]{"rook_wide_scar","rook_pov_scar","rook_medium_scar",
                "rook_wide_band","rook_pov_band","rook_medium_band"})
            assertNotNull(SketchDocument.object(source,key));
        Set<String> hero=new HashSet<>(),rival=new HashSet<>();
        for(String layerId:new String[]{"wide_courier","medium_courier"}){
            JSONArray objects=SketchDocument.layer(source,layerId).getJSONArray("objects");
            for(int i=0;i<objects.length();i++)hero.add(objects.getJSONObject(i).getString("color"));
        }
        for(String layerId:new String[]{"rook_wide","rook_medium","rook_pov"}){
            JSONArray objects=SketchDocument.layer(source,layerId).getJSONArray("objects");
            for(int i=0;i<objects.length();i++)rival.add(objects.getJSONObject(i).getString("color"));
        }
        assertTrue("Hero teal palette is stable",hero.contains("#FF248F99"));
        assertTrue("Rook wine jacket remains recognisable in alternate views",rival.contains("#FF924A67"));
        assertTrue("Rook scarf remains gold across views",rival.contains("#FFDFA13D"));
        assertFalse("One outfit is not mislabelled as two identities",rival.contains("#FF248F99"));
        int[] beats={0,16,35,36,45,59,60,72,83,99};
        JSONArray frames=SketchAnimationCompiler.sampleFrames(source,program,0,99);
        for(int beat:beats){
            JSONObject sample=frames.getJSONObject(beat);
            assertEquals(beat,sample.getInt("frame"));
            assertEquals(0,sample.getJSONArray("contactErrors").length()%2);
        }
        double distance=relative(frames.getJSONObject(16),"wide_rig","wrist","rook_wide_rig","wrist");
        assertTrue("The impact is physical wrist proximity, not only a particle effect",distance<80);
        double rMotion=relative(frames.getJSONObject(11),"rook_wide_rig","chest","rook_wide_rig","wrist");
        double rEnd=relative(frames.getJSONObject(25),"rook_wide_rig","chest","rook_wide_rig","wrist");
        assertTrue("Second actor has independently authored reaction/arm motion",Math.abs(rEnd-rMotion)>1);
        assertNotEquals("Rook acts independently from Vale after contact",
                joint(frames.getJSONObject(25),"wide_rig","chest").getDouble("rotation"),
                joint(frames.getJSONObject(25),"rook_wide_rig","chest").getDouble("rotation"),.001);
    }

    @Test public void allThreeDistinctCameraViewsProduceNativeNonFlatFrames()throws Exception{
        JSONObject built=SketchDuelAnimationExample.build(context),compiled=SketchAnimationCompiler.compile(
                built.getJSONObject("source"),built.getJSONObject("program"),"duel_preview");
        JSONObject frames=compiled.getJSONObject("document");
        try(SketchFrameRenderer painter=new SketchFrameRenderer(context,frames)){
            int[] picks={2,16,20,37,48,59,63,71,83,97};
            HashSet<Integer> colours=new HashSet<>();Set<Integer> signatures=new HashSet<>();
            for(int frame:picks){
                Bitmap image=painter.render(frame,true);
                try{
                    assertEquals(1280,image.getWidth());assertEquals(720,image.getHeight());
                    assertEquals(255,Color.alpha(image.getPixel(22,52)));
                    signatures.add(signature(image));
                    colours.add(image.getPixel(560,380));
                }finally{image.recycle();}
            }
            assertTrue("All three camera views must contain materially different authored frames",signatures.size()>=8);
            assertTrue("Character interaction/reaction produces visible changed native pixels",colours.size()>=3);
        }
    }

    @Test public void fullCinematicActionFilmCanBeRenderedToExactly100NativePngsForCi()throws Exception{
        assumeTrue("Explicit opt-in for larger full-sequence native proof",
                "1".equals(System.getenv("VIDEOSTUDIO_DUEL_FILM_PROOF")));
        JSONObject input=SketchDuelAnimationExample.build(context);
        JSONObject source=input.getJSONObject("source"),program=input.getJSONObject("program");
        JSONObject compiled=SketchAnimationCompiler.compile(source,program,"duel_proof_output"),doc=compiled.getJSONObject("document");
        File images=new File(PROOF,"frames");
        assertTrue(images.isDirectory()||images.mkdirs());
        Files.write(new File(PROOF,"editable-duel-source.json").toPath(),
                source.toString(2).getBytes(StandardCharsets.UTF_8));
        Files.write(new File(PROOF,"editable-duel-program.json").toPath(),
                program.toString(2).getBytes(StandardCharsets.UTF_8));
        Files.write(new File(PROOF,"duel-stage-metrics.json").toPath(),
                input.getJSONObject("report").toString(2).getBytes(StandardCharsets.UTF_8));
        Files.write(new File(PROOF,"duel-compiler-report.json").toPath(),
                compiled.getJSONObject("report").toString(2).getBytes(StandardCharsets.UTF_8));
        JSONArray frameProof=new JSONArray();
        Bitmap sheet=Bitmap.createBitmap(1600,1050,Bitmap.Config.ARGB_8888);
        Canvas canvas=new Canvas(sheet);canvas.drawColor(0xff112335);
        Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);paint.setColor(0xfff5f1e8);paint.setTextSize(15);
        int previous=0,changed=0;int[] selected={0,5,12,16,20,25,30,35,36,42,48,54,59,60,66,72,79,84,92,99};
        try(SketchFrameRenderer renderer=new SketchFrameRenderer(context,doc)){
            for(int frame=0;frame<100;frame++){
                Bitmap drawn=renderer.render(frame,true);
                try{
                    String name=String.format(java.util.Locale.ROOT,"frame-%04d.png",frame);
                    File f=new File(images,name);
                    try(FileOutputStream out=new FileOutputStream(f)){
                        assertTrue(drawn.compress(Bitmap.CompressFormat.PNG,100,out));
                    }
                    assertTrue(f.length()>1000);
                    int sig=signature(drawn);if(frame>0&&sig!=previous)changed++;previous=sig;
                    frameProof.put(new JSONObject().put("frame",frame).put("size",f.length())
                            .put("sha256",SketchProjectStore.fileHash(f)).put("pixelSignature",sig));
                    for(int n=0;n<selected.length;n++)if(selected[n]==frame){
                        int x=n%4*400,y=n/4*210;
                        canvas.drawBitmap(drawn,null,new Rect(x+3,y+3,x+397,y+187),null);
                        canvas.drawText("Frame "+frame+" · "+String.format(java.util.Locale.ROOT,"%.2f s",frame/24d),x+10,y+205,paint);
                    }
                }finally{drawn.recycle();}
            }
        }finally{
            try(FileOutputStream out=new FileOutputStream(new File(PROOF,"contact-sheet.png"))){
                assertTrue(sheet.compress(Bitmap.CompressFormat.PNG,100,out));
            }sheet.recycle();
        }
        assertTrue("The scene contains real movement across most adjacent frames",changed>=65);
        JSONObject metadata=new JSONObject().put("schema","videostudio.native-duel-proof.v1")
                .put("execution","Native Android Canvas in GitHub Robolectric workspace; not a phone")
                .put("width",1280).put("height",720).put("fps",24).put("frameCount",100)
                .put("changedAdjacentFrames",changed).put("separatelyDrawnCameraViews",3)
                .put("twoDistinctCharacters",true).put("audioRecorded",false)
                .put("phoneVerified",false).put("artisticQualityVerified",false)
                .put("frames",frameProof);
        Files.write(new File(PROOF,"native-duel-proof.json").toPath(),metadata.toString(2).getBytes(StandardCharsets.UTF_8));
    }

    private static JSONObject joint(JSONObject sample,String rigId,String name)throws Exception{
        JSONArray positions=sample.getJSONArray("joints");
        for(int i=0;i<positions.length();i++){
            JSONObject j=positions.getJSONObject(i);
            if(rigId.equals(j.getString("rigId"))&&name.equals(j.getString("jointId")))return j;
        }
        throw new AssertionError("Joint missing: "+rigId+":"+name);
    }
    private static double relative(JSONObject sample,String rigA,String jointA,String rigB,String jointB)throws Exception{
        JSONObject a=joint(sample,rigA,jointA),b=joint(sample,rigB,jointB);
        return Math.hypot((a.getDouble("x")-b.getDouble("x"))*1280,(a.getDouble("y")-b.getDouble("y"))*720);
    }
    private static int signature(Bitmap image){
        int hash=1;
        for(int y=24;y<720;y+=31)for(int x=16;x<1280;x+=37)hash=hash*31+image.getPixel(x,y);
        return hash;
    }
}
