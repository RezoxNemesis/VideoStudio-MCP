package com.rezoxnemesis.videostudio;

import android.content.Context;
import android.graphics.Bitmap;
import org.json.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import java.io.File;
import java.nio.file.Files;
import java.util.UUID;
import static org.junit.Assert.*;

/** Exercises native dispatch/persistence and real export frames, never workspace drawing code. */
@RunWith(RobolectricTestRunner.class) @Config(sdk=33,manifest=Config.NONE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class SketchMotionStudiesTest {
    @Test public void solesNeverCrossTheFloorBetweenHardPlants()throws Exception {
        JSONObject clock=SketchDocument.create(new JSONObject().put("sketchId","swing_floor").put("width",1280).put("height",720).put("frameCount",120).put("fps",30));
        JSONObject built=SketchMotionStudies.build(clock,"walk_to_run","study",new JSONObject());
        JSONArray samples=SketchAnimationCompiler.sampleFrames(built.getJSONObject("sourceDocument"),built.getJSONObject("program"),0,119);
        for(int f=0;f<samples.length();f++){
            JSONArray joints=samples.getJSONObject(f).getJSONArray("joints");
            for(int j=0;j<joints.length();j++){
                JSONObject joint=joints.getJSONObject(j);
                if(joint.getString("jointId").startsWith("foot_")){
                    assertTrue("A visible sole crosses the floor at frame "+f,joint.getDouble("y")<=.8125+1e-8);
                    assertEquals("Sole orientation stays level in this cutout exercise",0,joint.getDouble("rotation"),1e-6);
                }
            }
        }
    }
    @Test public void characterTorsoTracksAllFourLimbAttachments()throws Exception {
        JSONObject clock=SketchDocument.create(new JSONObject().put("sketchId","attachment").put("width",1280).put("height",720).put("frameCount",120).put("fps",30));
        JSONObject built=SketchMotionStudies.build(clock,"walk_to_run","study",new JSONObject());
        JSONObject shirt=SketchDocument.object(built.getJSONObject("sourceDocument"),"study_torso");
        assertNotNull("The character needs a connected torso, not detached limb segments",shirt);
        assertEquals("path",shirt.getString("kind"));
        JSONArray samples=SketchAnimationCompiler.sampleFrames(built.getJSONObject("sourceDocument"),built.getJSONObject("program"),0,119);
        String[] attachments={"arm_left","arm_right","hip_right","hip_left"};
        String cel="deform_"+SketchDocument.sha256("study_torso".getBytes(java.nio.charset.StandardCharsets.UTF_8)).substring(0,16)+"_";
        for(int f=0;f<120;f++){
            JSONArray commands=SketchDocument.object(built.getJSONObject("document"),cel+f).getJSONArray("commands"),joints=samples.getJSONObject(f).getJSONArray("joints");
            for(int v=0;v<4;v++)for(int j=0;j<joints.length();j++)if(attachments[v].equals(joints.getJSONObject(j).getString("jointId"))){
                assertEquals(joints.getJSONObject(j).getDouble("x"),commands.getJSONArray(v).getDouble(1),1e-8);
                assertEquals(joints.getJSONObject(j).getDouble("y"),commands.getJSONArray(v).getDouble(2),1e-8);
            }
        }
    }
    static JSONObject operation(String study)throws Exception {
        return new JSONObject().put("type","add_motion_study").put("layerId","layer_1")
                .put("sceneId","study").put("study",study).put("settings",new JSONObject());
    }
    static String create(Context c,int frames,int fps)throws Exception {
        String id="motion_"+UUID.randomUUID();
        SketchEditOps.execute(c,"sketch_create",new JSONObject().put("sketchId",id).put("operationId","create")
                .put("width",1280).put("height",720).put("frameCount",frames).put("fps",fps).put("background","#FFF1ECDD"));
        return id;
    }
    static JSONObject request(String id,String study)throws Exception {
        return new JSONObject().put("sketchId",id).put("expectedRevision",1).put("operationId","author")
                .put("operations",new JSONArray().put(operation(study)));
    }
    @Test public void cartoonAndCharacterStudiesCreateOriginalEditableNativeExports()throws Exception {
        Context c=RuntimeEnvironment.getApplication();
        for(String study:new String[]{"cartoon_jump","walk_to_run"}){
            String id=create(c,120,30);JSONObject args=request(id,study);
            JSONObject first=SketchEditOps.execute(c,"sketch_edit",args);
            assertEquals(first.toString(),SketchEditOps.execute(c,"sketch_edit",args).toString());
            JSONObject doc;try(SketchProjectStore store=new SketchProjectStore(c)){doc=store.get(id);}
            SketchDocument.validate(doc);assertEquals(2,doc.getInt("revision"));
            JSONArray objects=doc.getJSONArray("layers").getJSONObject(0).getJSONArray("objects");
            assertTrue(objects.length()>8);
            for(int i=0;i<objects.length();i++)assertFalse("Original study must not copy reference pixels",
                    java.util.Set.of("image","paint").contains(objects.getJSONObject(i).getString("kind")));
            File root=SketchSolidShadowClearanceTest.evidence(study);root.mkdirs();
            JSONObject authored=SketchMotionStudies.build(doc,study,"study",new JSONObject());
            Files.write(new File(root,"authoring-report.json").toPath(),authored.getJSONObject("report").toString(2).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            if("walk_to_run".equals(study)){
                assertTrue(authored.getJSONObject("report").getBoolean("independentArticulation"));
                assertTrue(authored.getJSONObject("report").getDouble("maxFootContactErrorPixels")<.02);
                Files.write(new File(root,"rig-program.json").toPath(),authored.getJSONObject("program").toString(2).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            }
            SketchNativeExport.Prepared export=SketchNativeExport.prepare(c,new JSONObject().put("sketchId",id)
                    .put("expectedRevision",2).put("operationId","export").put("aspect","original").put("quality","720p"));
            JSONArray hashes=new JSONArray();
            try(SketchExportFrameSource frames=export.openFrames()){
                for(int f=0;f<120;f++){
                        Bitmap b=frames.render(f,1280,720);try{
                        File png=new File(root,String.format(java.util.Locale.ROOT,"frames/frame-%04d.png",f));
                        SketchActionFilmTest.png(png,b);hashes.put(new JSONObject().put("frame",f).put("sha256",SketchProjectStore.fileHash(png)));
                    }finally{b.recycle();}
                }
            }
            Files.write(new File(root,"document.json").toPath(),doc.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            Files.write(new File(root,"native-frames.json").toPath(),new JSONObject().put("operation",operation(study))
                    .put("frames",hashes).put("fps",30).put("frameCount",120).put("width",1280).put("height",720)
                    .put("renderer","VideoStudio SketchNativeExport / native Android Canvas in workspace Robolectric")
                    .put("phoneExecution",false).put("androidVideoEncoding",false).toString(2).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            SketchPaintArchive.Prepared archive=SketchPaintArchive.prepare(c,new JSONObject().put("sketchId",id).put("expectedRevision",2).put("operationId","archive"));
            Files.copy(archive.file.toPath(),new File(root,"editable.vssketch").toPath(),java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            JSONObject restored=SketchPaintArchive.restoreFile(c,archive.file,SketchProjectStore.fileHash(archive.file),"restore_"+study,"Restored "+study);
            try(SketchProjectStore store=new SketchProjectStore(c);SketchFrameRenderer original=new SketchFrameRenderer(c,doc);
                    SketchFrameRenderer roundtrip=new SketchFrameRenderer(c,store.get(restored.getString("sketchId")))){
                for(int f:new int[]{0,18,50,60,78,100,119}){
                    Bitmap a=original.render(f),b=roundtrip.render(f);try{assertTrue("Archive preserves actual pixels at frame "+f,a.sameAs(b));}finally{a.recycle();b.recycle();}
                }
            }
            SketchEditOps.execute(c,"sketch_undo",new JSONObject().put("sketchId",id).put("expectedRevision",2).put("operationId","undo"));
            try(SketchProjectStore store=new SketchProjectStore(c)){assertEquals(0,store.get(id).getJSONArray("layers").getJSONObject(0).getJSONArray("objects").length());}
        }
    }
    @Test public void longerAndSixtyFpsStudiesRetainTheRequestedClockAndExistingArtwork()throws Exception {
        for(int[] clock:new int[][]{{240,60},{240,30}})for(String study:new String[]{"cartoon_jump","walk_to_run"}){
            JSONObject doc=SketchDocument.create(new JSONObject().put("sketchId","retained").put("width",1280).put("height",720).put("frameCount",clock[0]).put("fps",clock[1]));
            JSONObject old=SketchEditOps.completeObject(new JSONObject().put("id","old_ink").put("kind","line").put("points",new JSONArray("[[0.1,0.1,1],[0.2,0.2,1]]")));
            doc.getJSONArray("layers").getJSONObject(0).getJSONArray("objects").put(old);
            SketchMotionStudies.apply(doc,operation(study));SketchDocument.validate(doc);
            assertEquals(SketchDocument.canonical(old),SketchDocument.canonical(SketchDocument.object(doc,"old_ink")));
            assertEquals(clock[0],doc.getInt("frameCount"));assertEquals(clock[1],doc.getInt("fps"));
        }
    }
    @Test public void failedOrDuplicateSceneDoesNotChangeExistingArtwork()throws Exception {
        Context c=RuntimeEnvironment.getApplication();String id=create(c,120,30);
        SketchEditOps.execute(c,"sketch_edit",request(id,"cartoon_jump"));JSONObject before;
        try(SketchProjectStore store=new SketchProjectStore(c)){before=store.get(id);}
        for(JSONObject op:new JSONObject[]{operation("unknown"),operation("walk_to_run").put("sceneId","study"),
                operation("cartoon_jump").put("sceneId","other").put("settings",new JSONObject().put("url","https://example.test/reference"))}){
            assertThrows(Exception.class,()->SketchEditOps.execute(c,"sketch_edit",new JSONObject().put("sketchId",id)
                    .put("expectedRevision",2).put("operationId",UUID.randomUUID().toString()).put("operations",new JSONArray().put(op))));
            try(SketchProjectStore store=new SketchProjectStore(c)){assertEquals(SketchDocument.canonical(before),SketchDocument.canonical(store.get(id)));}
        }
    }
}
