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
import java.security.MessageDigest;
import java.util.HashSet;
import static org.junit.Assert.*;

/** Original-action evidence uses the SAME Android Bitmap/Canvas renderer as Sketch Studio. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=33,manifest=Config.NONE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class SketchActionFilmTest {
    static final File EVIDENCE=new File("build/sketch-action-film");
    static JSONObject fixture(String name)throws Exception {
        try(java.io.InputStream input=SketchActionFilmTest.class.getResourceAsStream("/sketch-animation/"+name+".json")){
            assertNotNull("Authored editable source fixture is present",input);
            return new JSONObject(new String(input.readAllBytes(),StandardCharsets.UTF_8));
        }
    }
    static void png(File file,Bitmap bitmap)throws Exception {
        file.getParentFile().mkdirs();
        try(FileOutputStream out=new FileOutputStream(file)){assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG,100,out));}
    }
    @Test public void preparedArtworkUsesOriginalNativeVectorShapes()throws Exception {
        JSONObject source=fixture("rooftop-relay-source");SketchDocument.validate(source);
        int objects=0;JSONArray layers=source.getJSONArray("layers");
        for(int i=0;i<layers.length();i++){
            JSONArray items=layers.getJSONObject(i).getJSONArray("objects");
            for(int j=0;j<items.length();j++){
                assertFalse("Artwork must not insert reference pixels",java.util.Arrays.asList("image","paint").contains(items.getJSONObject(j).getString("kind")));
                objects++;
            }
        }
        assertTrue("A fully coloured scene has prepared editable drawing parts",objects>150);
        JSONArray mouthCels=fixture("rooftop-relay-program").getJSONArray("mouthCels");HashSet<String> mouthIds=new HashSet<>();
        for(int i=0;i<mouthCels.length();i++){
            JSONObject cue=mouthCels.getJSONObject(i);String id=cue.getString("objectId");
            if(!mouthIds.add(id))continue;JSONObject mouth=SketchDocument.object(source,id);
            assertEquals("Prepared mouth alternatives must not stack before animation",cue.getInt("start"),mouth.getInt("frame"));
            assertEquals(cue.getInt("end")-cue.getInt("start")+1,mouth.getInt("exposure"));
        }
        try(SketchFrameRenderer renderer=new SketchFrameRenderer(RuntimeEnvironment.getApplication(),source)){
            for(int frame:new int[]{0,36,60}){
                Bitmap bitmap=renderer.render(frame);
                try{png(new File(EVIDENCE,"prepared-"+frame+".png"),bitmap);}finally{bitmap.recycle();}
            }
        }
    }
    @Test public void exactly100ArticulatedFramesProduceNativeCanvasEvidence()throws Exception {
        JSONObject source=fixture("rooftop-relay-source"),program=fixture("rooftop-relay-program");
        JSONObject result=SketchAnimationCompiler.compile(source,program,"rooftop_relay_animation");
        JSONObject document=result.getJSONObject("document");
        assertEquals(100,document.getInt("frameCount"));assertEquals(24,document.getInt("fps"));
        SketchDocument.validate(document);
        EVIDENCE.mkdirs();Files.write(new File(EVIDENCE,"compiled-animation.json").toPath(),document.toString().getBytes(StandardCharsets.UTF_8));
        Files.write(new File(EVIDENCE,"compiler-report.json").toPath(),result.getJSONObject("report").toString(2).getBytes(StandardCharsets.UTF_8));
        JSONObject manifest=new JSONObject().put("renderer","VideoStudio SketchFrameRenderer / Robolectric native Android Canvas")
                .put("executionLocation","workspace; not a phone screenshot or phone render")
                .put("source","original editable vector paths; no inserted image or paint cel")
                .put("width",1280).put("height",720).put("fps",24).put("frameCount",100)
                .put("durationSeconds",100/24d).put("frames",new JSONArray());
        JSONArray samples=new JSONArray();double maxContactError=0,maxFootRotation=0,minElbow=Double.POSITIVE_INFINITY,maxElbow=Double.NEGATIVE_INFINITY;
        double wristTravel=0,previousWristX=Double.NaN,previousWristY=Double.NaN;
        for(int f=0;f<100;f++){
            JSONObject sample=SketchAnimationCompiler.sample(source,program,f);samples.put(sample);
            JSONArray contacts=sample.getJSONArray("contactErrors");
            for(int n=0;n<contacts.length();n++)maxContactError=Math.max(maxContactError,contacts.getJSONObject(n).getDouble("errorPixels"));
            if(f<36){
                JSONArray joints=sample.getJSONArray("joints");double upper=0,fore=0;
                for(int n=0;n<joints.length();n++){
                    JSONObject joint=joints.getJSONObject(n);
                    if(!"wide_rig".equals(joint.getString("rigId")))continue;
                    if("front_upper".equals(joint.getString("jointId")))upper=joint.getDouble("rotation");
                    if("front_fore".equals(joint.getString("jointId")))fore=joint.getDouble("rotation");
                    if("front_ankle".equals(joint.getString("jointId"))||"back_ankle".equals(joint.getString("jointId")))
                        maxFootRotation=Math.max(maxFootRotation,Math.abs(joint.getDouble("rotation")));
                    if("wrist".equals(joint.getString("jointId"))){
                        double x=joint.getDouble("x")*1280,y=joint.getDouble("y")*720;
                        if(!Double.isNaN(previousWristX))wristTravel+=Math.hypot(x-previousWristX,y-previousWristY);
                        previousWristX=x;previousWristY=y;
                    }
                }
                minElbow=Math.min(minElbow,fore-upper);maxElbow=Math.max(maxElbow,fore-upper);
            }
        }
        assertTrue("Front elbow must bend independently, not rotate a flat whole-character card",maxElbow-minElbow>45);
        assertTrue("Wrist travels through an action arc",wristTravel>80);
        assertTrue("Both planted ankles remain at authored floor contacts",maxContactError<.5);
        assertTrue("Boot soles remain level on the floor while the knees articulate",maxFootRotation<1e-8);
        JSONObject motion=new JSONObject().put("frames",samples).put("maxPlantedAnkleErrorPixels",maxContactError)
                .put("maxPlantedFootRotationErrorDegrees",maxFootRotation)
                .put("frontElbowRelativeAngleRangeDegrees",maxElbow-minElbow).put("wideWristPathPixels",wristTravel)
                .put("cameraViews",new JSONArray().put("wide").put("foreground POV").put("medium recovery"))
                .put("qualityLimit","Structural articulation/contact evidence is not a claim of hand-drawn feature-film quality.");
        Files.write(new File(EVIDENCE,"motion-landmarks.json").toPath(),motion.toString(2).getBytes(StandardCharsets.UTF_8));
        HashSet<String> pixelHashes=new HashSet<>();int[] previous=null;long totalChanged=0;int compared=0;
        int[] picks={0,5,11,16,20,25,30,35,36,43,50,59,60,66,72,79,84,90,95,99};
        Bitmap sheet=Bitmap.createBitmap(1600,1050,Bitmap.Config.ARGB_8888);Canvas contact=new Canvas(sheet);
        contact.drawColor(Color.rgb(13,24,38));Paint caption=new Paint(Paint.ANTI_ALIAS_FLAG);caption.setColor(Color.rgb(220,235,225));caption.setTextSize(17);
        try(SketchFrameRenderer renderer=new SketchFrameRenderer(RuntimeEnvironment.getApplication(),document)){
            for(int f=0;f<100;f++){
                Bitmap bitmap=renderer.render(f);try{
                    File file=new File(EVIDENCE,String.format(java.util.Locale.US,"frames/frame-%04d.png",f));png(file,bitmap);
                    byte[] encoded=Files.readAllBytes(file.toPath());String sha=SketchProjectStore.fileHash(file);pixelHashes.add(sha);
                    int[] pixels=new int[bitmap.getWidth()*bitmap.getHeight()];bitmap.getPixels(pixels,0,bitmap.getWidth(),0,0,bitmap.getWidth(),bitmap.getHeight());
                    int changed=0;if(previous!=null){for(int p=0;p<pixels.length;p++)if(pixels[p]!=previous[p])changed++;totalChanged+=changed;compared++;}previous=pixels;
                    manifest.getJSONArray("frames").put(new JSONObject().put("frame",f).put("sha256",sha).put("bytes",encoded.length).put("changedPixelsFromPrevious",f==0?JSONObject.NULL:changed));
                    for(int n=0;n<picks.length;n++)if(picks[n]==f){int x=(n%4)*400,y=(n/4)*210;contact.drawBitmap(bitmap,null,new Rect(x+5,y+5,x+395,y+185),null);contact.drawText(String.format(java.util.Locale.US,"F%03d  %.2fs",f,f/24d),x+12,y+205,caption);}
                    if(f==16||f==43||f==84)png(new File(EVIDENCE,"proof-frame-"+f+".png"),bitmap);
                }finally{bitmap.recycle();}
            }
        }finally{png(new File(EVIDENCE,"contact-sheet.png"),sheet);sheet.recycle();}
        assertEquals("All frames change through articulated motion, not duplicate-image holds",100,pixelHashes.size());
        assertTrue(totalChanged/(double)compared>1000);
        manifest.put("uniqueNativeFrames",pixelHashes.size()).put("averageChangedPixels",totalChanged/(double)compared)
                .put("pixelChangeIsNotAnimationQualityProof",true);
        Files.write(new File(EVIDENCE,"native-frame-manifest.json").toPath(),manifest.toString(2).getBytes(StandardCharsets.UTF_8));
    }
}
