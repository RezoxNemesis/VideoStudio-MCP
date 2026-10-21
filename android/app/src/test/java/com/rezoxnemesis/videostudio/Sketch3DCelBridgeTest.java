package com.rezoxnemesis.videostudio;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.net.Uri;
import java.io.File;
import java.io.FileOutputStream;
import java.util.UUID;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=33, manifest=Config.NONE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class Sketch3DCelBridgeTest {
    private final Context context=RuntimeEnvironment.getApplication();

    @Test public void cameraCaptureIsBoundedAndAspectPreserving(){
        assertArrayEquals(new int[]{768,432},Sketch3DCelBridge.captureSize(1920,1080,768));
        assertArrayEquals(new int[]{432,768},Sketch3DCelBridge.captureSize(1080,1920,768));
        assertArrayEquals(new int[]{256,128},Sketch3DCelBridge.captureSize(256,128,768));
        try{Sketch3DCelBridge.captureSize(0,100,768);fail("Empty viewport accepted");}
        catch(IllegalArgumentException expected){}
        try{Sketch3DCelBridge.captureSize(100,100,4096);fail("Huge pixel copy accepted");}
        catch(IllegalArgumentException expected){}
    }

    @Test public void traceRecipesAreEditableNativeGeometry()throws Exception{
        JSONObject colour=Sketch3DCelBridge.traceOperation("ref","vector",2,"trace","colour_regions");
        assertEquals("trace_reference",colour.getString("type"));
        assertEquals("colour_regions",colour.getString("mode"));
        assertEquals(1,colour.getInt("exposure"));
        assertTrue(colour.getInt("maxDimension")<=96);
        JSONObject ink=Sketch3DCelBridge.traceOperation("ref","vector",5,"ink","edges");
        assertEquals("edges",ink.getString("mode"));
        assertEquals(5,ink.getInt("frame"));
        assertTrue(ink.has("threshold"));
        try{Sketch3DCelBridge.traceOperation("ref","vector",0,"x","unknown");fail("Mode bypassed");}
        catch(IllegalArgumentException expected){}
    }

    private static final class Fixture {
        final String sketchId,projectId,modelId,poseId;
        Fixture(Context context)throws Exception {
            String suffix=UUID.randomUUID().toString().replace("-","");
            sketchId="sketch_"+suffix;
            SketchEditOps.execute(context,"sketch_create",new JSONObject()
                    .put("sketchId",sketchId).put("operationId","create_"+suffix)
                    .put("width",256).put("height",256).put("fps",24)
                    .put("frameCount",8));
            ProjectStore projects=new ProjectStore(context);
            ProjectStore.Project project=projects.create("Racing car 3D capture fixture");
            projectId=project.id;
            ProjectStore.Asset glb=new ProjectStore.Asset();
            glb.id="glb_"+suffix;glb.name="Blue racing car.glb";
            glb.mime="model/gltf-binary";glb.uri=Uri.fromFile(
                    new File(context.getFilesDir(),"test-car.glb")).toString();
            glb.role="source_glb";modelId=glb.id;
            project.assets.add(glb);
            projects.save(project);

            File folder=new File(context.getFilesDir(),
                    "creative_workspace/projects/"+projectId+"/pose_cels");
            assertTrue(folder.mkdirs()||folder.isDirectory());
            File file=new File(folder,"captured_pose.png");
            Bitmap bitmap=Bitmap.createBitmap(128,128,Bitmap.Config.ARGB_8888);
            Canvas canvas=new Canvas(bitmap);
            canvas.drawColor(Color.WHITE);
            Paint p=new Paint();p.setColor(0xff1976d2);p.setAntiAlias(true);
            canvas.drawRoundRect(15,40,114,91,14,14,p);
            p.setColor(Color.BLACK);canvas.drawCircle(32,90,11,p);canvas.drawCircle(96,90,11,p);
            try(FileOutputStream output=new FileOutputStream(file)){
                assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG,100,output));
                output.getFD().sync();
            }finally{bitmap.recycle();}
            ProjectStore.Asset pose=projects.registerGeneratedAsset(project,Uri.fromFile(file),
                    "blue-car-pose.png","sketch_3d_pose",false);
            pose.generationMetadata=new JSONObject()
                    .put("sourceGlbAssetId",modelId)
                    .put("captureSha256",SketchProjectStore.fileHash(file));
            projects.save(project);poseId=pose.id;
        }
    }

    @Test public void capturedPoseCreatesAHiddenGuideAndEditableFrameCel()throws Exception{
        Fixture f=new Fixture(context);
        JSONObject report=Sketch3DCelBridge.importCapturedPose(context,f.sketchId,f.projectId,
                f.poseId,3,"edges");
        assertEquals(3,report.getInt("frameIndex"));
        assertEquals("edges",report.getString("traceMode"));
        assertTrue(report.getInt("vectorObjects")>0);
        try(SketchProjectStore sketches=new SketchProjectStore(context)){
            JSONObject doc=sketches.get(f.sketchId);
            JSONObject guide=SketchDocument.layer(doc,report.getString("guideLayerId"));
            JSONObject image=guide.getJSONArray("objects").getJSONObject(0);
            assertEquals("image",image.getString("kind"));
            assertEquals(0d,image.getJSONObject("transform").getDouble("opacity"),0.00001);
            JSONObject cel=SketchDocument.layer(doc,report.getString("editableLayerId"));
            JSONArray strokes=cel.getJSONArray("objects");
            assertTrue(strokes.length()>0);
            for(int i=0;i<strokes.length();i++){
                JSONObject stroke=strokes.getJSONObject(i);
                assertEquals("stroke",stroke.getString("kind"));
                assertEquals(3,stroke.getInt("frame"));
                assertEquals(1,stroke.getInt("exposure"));
                assertFalse(stroke.has("reference"));
            }
            // Existing Layer 1 was neither flattened nor altered.
            assertEquals(0,doc.getJSONArray("layers").getJSONObject(0)
                    .getJSONArray("objects").length());
        }
    }

    @Test public void corruptedOwnedCaptureNeverModifiesTheDrawing()throws Exception{
        Fixture f=new Fixture(context);
        ProjectStore projects=new ProjectStore(context);
        ProjectStore.Project p=projects.get(f.projectId);
        p.asset(f.poseId).generationMetadata.put("captureSha256",
                "0000000000000000000000000000000000000000000000000000000000000000");
        projects.save(p);
        try{
            Sketch3DCelBridge.importCapturedPose(context,f.sketchId,f.projectId,f.poseId,0,"edges");
            fail("Modified pose pixels accepted");
        }catch(IllegalStateException expected){
            assertTrue(expected.getMessage().contains("changed"));
        }
        try(SketchProjectStore sketches=new SketchProjectStore(context)){
            JSONObject drawing=sketches.get(f.sketchId);
            assertEquals(1,drawing.getLong("revision"));
            assertEquals(1,drawing.getJSONArray("layers").length());
        }
    }

    @Test public void unknownTraceModeNeverModifiesDrawing()throws Exception{
        Fixture f=new Fixture(context);
        try{
            Sketch3DCelBridge.importCapturedPose(context,f.sketchId,f.projectId,f.poseId,1,"glb_code");
            fail("Unsupported tracing requested");
        }catch(IllegalArgumentException expected){}
        try(SketchProjectStore sketches=new SketchProjectStore(context)){
            assertEquals(1,sketches.get(f.sketchId).getLong("revision"));
        }
    }
}
