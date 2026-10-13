package com.rezoxnemesis.videostudio;

import android.graphics.Bitmap;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import java.util.UUID;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=33,manifest=Config.NONE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class NativeSourceRigPreviewTest {
    @Test public void diagnosticMovesActualActorAndSwordMaterialAndNeverSavesTheTestPose()throws Exception {
        try(NativeSourceWorkbenchFixture f=new NativeSourceWorkbenchFixture(RuntimeEnvironment.getApplication())) {
            JSONObject rest=NativeSourceWorkbenchFixture.render(f,f.preview());
            float[][] target=SourceArtworkRasterFixture.copy(f.rig.joints);
            for(float[] point:target){point[0]+=.1f;point[1]-=.04f;}
            JSONObject posed=NativeSourceWorkbenchFixture.render(f,f.preview().put("targetJoints",NativeSourceWorkbenchFixture.pairs(target))
                    .put("swordTip",new JSONArray().put(f.rig.swordTip[0]+.1).put(f.rig.swordTip[1]-.04)));
            Bitmap first=NativeSourceWorkbenchFixture.image(rest,"rigPreviewImage"),second=NativeSourceWorkbenchFixture.image(posed,"rigPreviewImage");
            try {
                double[] before=SourceArtworkRasterFixture.centroid(first,SourceArtworkRasterFixture.MARKER,false);
                double[] after=SourceArtworkRasterFixture.centroid(second,SourceArtworkRasterFixture.MARKER,false);
                assertEquals(.1*first.getWidth(),after[0]-before[0],2);assertEquals(-.04*first.getHeight(),after[1]-before[1],2);
                double[] sword0=SourceArtworkRasterFixture.centroid(first,SourceArtworkRasterFixture.BLACK_SWORD,false);
                double[] sword1=SourceArtworkRasterFixture.centroid(second,SourceArtworkRasterFixture.BLACK_SWORD,false);
                assertEquals("Original painted weapon must move as a rigid independent source texture",.1*first.getWidth(),sword1[0]-sword0[0],2);
                assertEquals(-.04*first.getHeight(),sword1[1]-sword0[1],2);
                assertEquals("Unmasked world and other fighter must be excluded from diagnostic",0,count(first,SourceArtworkRasterFixture.WORLD_MARKER));
                assertEquals(0,count(first,SourceArtworkRasterFixture.WHITE_MATERIAL));
            }finally{first.recycle();second.recycle();}
            assertEquals("source-rig-pose-diagnostic",posed.getString("scope"));
            assertFalse(posed.getBoolean("cinematicAcceptance"));assertEquals("checkerboard",posed.getJSONObject("stage").getString("background"));
            assertTrue(posed.getBoolean("diagnosticOnly"));
            assertEquals(1,posed.getJSONObject("stage").getDouble("scaleX"),0);assertEquals(0,posed.getJSONObject("stage").getDouble("offsetX"),0);
            assertEquals(f.rigSha,f.rigs.captureBinding(f.project.id,f.rig.id).getString("sha256"));
            assertEquals(f.sourceSha,StoryboardSourceManifest.captureSource(f.context,f.art.from).getString("sourceSha256"));
        }
    }
    @Test public void legacyProfileWithoutAuthoredSwordAndUnknownTargetJointsCannotBeGuessed()throws Exception {
        try(NativeSourceWorkbenchFixture f=new NativeSourceWorkbenchFixture(RuntimeEnvironment.getApplication())) {
            Rig2DProfile legacy=new Rig2DProfile(UUID.randomUUID().toString(),f.project.id,f.art.from.id,true,false,
                    f.rig.joints,f.rig.outline,null,null,null,f.sourceSha);
            String sha=f.rigs.saveWithBinding(legacy,f.project.updatedAt,"").getString("sha256");
            rejects(f,f.preview().put("rigId",legacy.id).put("expectedRigSha256",sha));
            JSONArray unknown=NativeSourceWorkbenchFixture.pairs(f.rig.joints);unknown.put(3,JSONObject.NULL);
            rejects(f,f.preview().put("targetJoints",unknown));
            rejects(f,f.preview().put("targetJoints",new JSONArray().put(new JSONArray().put(.5).put(.5))));
        }
    }
    @Test public void sourceAndProfileCasAndNativeResolutionBoundsAreEnforced()throws Exception {
        try(NativeSourceWorkbenchFixture f=new NativeSourceWorkbenchFixture(RuntimeEnvironment.getApplication())) {
            rejects(f,f.preview().put("expectedRigSha256","0".repeat(64)));
            rejects(f,f.preview().put("expectedSourceSha256","0".repeat(64)));
            rejects(f,f.preview().put("maxDimension",721));
            rejects(f,f.preview().put("maxDimension",127));
            rejects(f,f.preview().put("rigId","foreign-rig"));
        }
    }
    @Test public void existingExecuteImageKeyIsIdenticalToCanonicalDiagnosticAndKeepsTheWholeResultBounded()throws Exception {
        try(NativeSourceWorkbenchFixture f=new NativeSourceWorkbenchFixture(RuntimeEnvironment.getApplication())) {
            JSONObject response=NativeSourceWorkbenchFixture.render(f,f.preview());
            assertTrue("Successful native diagnostic must be completed as a success by ControlService",response.optBoolean("ok",false));
            assertEquals(response.getJSONObject("rigPreviewImage").toString(),response.getJSONObject("contactSheet").toString());
            assertTrue(response.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8).length<=2*1024*1024);
        }
    }
    private static int count(Bitmap image,int sought){int count=0;for(int y=0;y<image.getHeight();y++)for(int x=0;x<image.getWidth();x++)if(image.getPixel(x,y)==sought)count++;return count;}
    private static void rejects(NativeSourceWorkbenchFixture f,JSONObject request)throws Exception {
        try {NativeSourceWorkbenchFixture.render(f,request);fail("Unverified, incomplete or invalid diagnostic pose must fail");}
        catch(IllegalArgumentException|IllegalStateException expected){}
    }
}
