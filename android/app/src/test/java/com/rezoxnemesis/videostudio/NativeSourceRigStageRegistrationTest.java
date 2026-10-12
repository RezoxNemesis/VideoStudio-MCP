package com.rezoxnemesis.videostudio;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
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
import java.util.UUID;
import static org.junit.Assert.*;

/** Aspect registration must preserve physical source pixels and honest original provenance. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=33,manifest=Config.NONE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class NativeSourceRigStageRegistrationTest {
    @Test public void coprimeAspectHasAnExplicitReversibleMappingAndPreservesBothOriginalReceipts()throws Exception {
        try(NativeSourceWorkbenchFixture f=new NativeSourceWorkbenchFixture(RuntimeEnvironment.getApplication())) {
            Rig2DProfile rig=resizedOriginal(f,1000,701,false);String rigSha=Rig2DProjectStore.sha256(rig);
            JSONObject request=f.preview().put("rigId",rig.id).put("expectedRigSha256",rigSha).put("expectedSourceSha256",rig.sourceSha256);
            JSONObject response=NativeSourceWorkbenchFixture.render(f,request);
            JSONObject map=response.getJSONObject("stage").getJSONObject("originalToStageUv"),inverse=response.getJSONObject("previewToOriginalUv");
            Bitmap image=NativeSourceWorkbenchFixture.image(response,"rigPreviewImage");
            try {
                assertTrue(image.getWidth()<=448&&image.getHeight()<=448);assertTrue(image.getWidth()>=128&&image.getHeight()>=128);
                assertEquals("Registration must preserve physical original aspect rather than independently stretching",1000.0/701,
                        map.getDouble("scaleX")*image.getWidth()/(map.getDouble("scaleY")*image.getHeight()),.000001);
                double u=.392,v=.399,su=map.getDouble("offsetX")+map.getDouble("scaleX")*u,sv=map.getDouble("offsetY")+map.getDouble("scaleY")*v;
                assertEquals(u,inverse.getDouble("offsetX")+inverse.getDouble("scaleX")*su,.0000001);
                assertEquals(v,inverse.getDouble("offsetY")+inverse.getDouble("scaleY")*sv,.0000001);
                assertEquals(SourceArtworkRasterFixture.MARKER,image.getPixel((int)(su*image.getWidth()),(int)(sv*image.getHeight())));
            }finally{image.recycle();}
            assertEquals(rig.sourceSha256,response.getString("sourceSha256"));assertEquals(rigSha,response.getString("rigSha256"));
            assertEquals(rigSha,f.rigs.captureBinding(f.project.id,rig.id).getString("sha256"));
            assertEquals(rig.sourceSha256,StoryboardSourceManifest.captureSource(f.context,f.art.from).getString("sourceSha256"));
            assertFalse(response.toString().contains("file://"));assertNoTemporaryImages(f);
        }
    }
    @Test public void shortOriginalSideDoesNotCreateActorTransparencyFromTheContainPadding()throws Exception {
        try(NativeSourceWorkbenchFixture f=new NativeSourceWorkbenchFixture(RuntimeEnvironment.getApplication())) {
            Rig2DProfile rig=resizedOriginal(f,64,512,true);
            try {NativeSourceWorkbenchFixture.render(f,f.preview().put("rigId",rig.id).put("expectedRigSha256",Rig2DProjectStore.sha256(rig))
                    .put("expectedSourceSha256",rig.sourceSha256));fail("Opaque original without an authored actor mask must not become eligible from transparent staging margins");}
            catch(IllegalArgumentException expected){assertTrue(expected.getMessage().toLowerCase(java.util.Locale.US).contains("transparen")||expected.getMessage().toLowerCase(java.util.Locale.US).contains("outline"));}
            assertNoTemporaryImages(f);
        }
    }
    @Test public void stagedNinetyDegreeRotationMovesActualBodyAndRigidSwordInOriginalPixelMetric()throws Exception {
        try(NativeSourceWorkbenchFixture f=new NativeSourceWorkbenchFixture(RuntimeEnvironment.getApplication())) {
            Rig2DProfile rig=resizedOriginal(f,1000,701,false);
            JSONObject request=f.preview().put("rigId",rig.id).put("expectedRigSha256",Rig2DProjectStore.sha256(rig)).put("expectedSourceSha256",rig.sourceSha256);
            JSONObject rest=NativeSourceWorkbenchFixture.render(f,request);
            float[][] target=SourceArtworkRasterFixture.copy(rig.joints);for(float[] point:target)turn(point,1000,701);
            float[] tip=rig.swordTip.clone();turn(tip,1000,701);
            JSONObject posed=NativeSourceWorkbenchFixture.render(f,new JSONObject(request.toString()).put("targetJoints",NativeSourceWorkbenchFixture.pairs(target))
                    .put("swordTip",new JSONArray().put(tip[0]).put(tip[1])));
            Bitmap first=NativeSourceWorkbenchFixture.image(rest,"rigPreviewImage"),second=NativeSourceWorkbenchFixture.image(posed,"rigPreviewImage");
            try {
                NativeSourceWorkbenchFixture.evidence("coprime-rotation-rest",first);
                NativeSourceWorkbenchFixture.evidence("coprime-rotation-posed",second);
                JSONObject map=posed.getJSONObject("stage").getJSONObject("originalToStageUv");
                double cx=(map.getDouble("offsetX")+.3*map.getDouble("scaleX"))*first.getWidth(),cy=(map.getDouble("offsetY")+.55*map.getDouble("scaleY"))*first.getHeight();
                for(int material:new int[]{SourceArtworkRasterFixture.MARKER,SourceArtworkRasterFixture.BLACK_SWORD}) {
                    double[] before=material==SourceArtworkRasterFixture.BLACK_SWORD?swordCentroid(first):SourceArtworkRasterFixture.centroid(first,material,false);
                    double[] after=material==SourceArtworkRasterFixture.BLACK_SWORD?swordCentroid(second):SourceArtworkRasterFixture.centroid(second,material,false);
                    assertEquals(cx-(before[1]-cy),after[0],3);assertEquals(cy+(before[0]-cx),after[1],3);
                }
            }finally{first.recycle();second.recycle();}
            assertNoTemporaryImages(f);
        }
    }
    private static double[] swordCentroid(Bitmap image) {
        int wanted=SourceArtworkRasterFixture.BLACK_SWORD,count=0;double sumX=0,sumY=0;
        for(int y=0;y<image.getHeight();y++)for(int x=0;x<image.getWidth();x++) {
            int pixel=image.getPixel(x,y);
            // Native staging+rigid rotation measured (44,238,155) from source
            // (44,240,155). Keep a tight 5-channel bound, far from all other materials.
            if(android.graphics.Color.alpha(pixel)>200
                    &&Math.abs(android.graphics.Color.red(pixel)-android.graphics.Color.red(wanted))<=5
                    &&Math.abs(android.graphics.Color.green(pixel)-android.graphics.Color.green(wanted))<=5
                    &&Math.abs(android.graphics.Color.blue(pixel)-android.graphics.Color.blue(wanted))<=5) {
                count++;sumX+=x;sumY+=y;
            }
        }
        assertTrue("Original sword color must remain visible in actual native pixels",count>10);return new double[]{sumX/count,sumY/count};
    }
    private static void turn(float[] point,int width,int height){float dx=(point[0]-.3f)*width,dy=(point[1]-.55f)*height;point[0]=.3f-dy/width;point[1]=.55f+dx/height;}
    private static Rig2DProfile resizedOriginal(NativeSourceWorkbenchFixture f,int width,int height,boolean unmasked)throws Exception {
        File original=new File(android.net.Uri.parse(f.art.from.uri).getPath());
        Bitmap decoded=BitmapFactory.decodeFile(original.getAbsolutePath()),resized=Bitmap.createScaledBitmap(decoded,width,height,true);
        try(FileOutputStream out=new FileOutputStream(original)){assertTrue(resized.compress(Bitmap.CompressFormat.PNG,100,out));}
        finally{if(resized!=decoded)resized.recycle();decoded.recycle();}
        String sourceSha=StoryboardSourceManifest.captureSource(f.context,f.art.from).getString("sourceSha256");
        Rig2DProfile rig=new Rig2DProfile(UUID.randomUUID().toString(),f.project.id,f.art.from.id,true,unmasked,
                f.rig.joints,unmasked?null:f.rig.outline,f.rig.swordTip,f.rig.swordHilt,f.rig.swordOutline,sourceSha);
        f.rigs.saveWithBinding(rig,f.project.updatedAt,"");return rig;
    }
    private static void assertNoTemporaryImages(NativeSourceWorkbenchFixture f) {
        File directory=new File(f.context.getCacheDir(),"source_rig_stage_registration");
        if(directory.isDirectory())assertEquals("Transient native registration PNGs must be released",0,directory.listFiles().length);
        File probes=new File(f.context.getCacheDir(),"storyboard_source_probes");if(probes.isDirectory())assertEquals(0,probes.listFiles().length);
    }
}
