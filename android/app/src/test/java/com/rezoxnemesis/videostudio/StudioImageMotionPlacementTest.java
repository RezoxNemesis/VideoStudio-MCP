package com.rezoxnemesis.videostudio;

import android.graphics.Bitmap;
import android.net.Uri;
import androidx.media3.common.OverlaySettings;
import androidx.media3.common.util.Size;
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
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.util.UUID;
import static org.junit.Assert.*;

/** Real Media3 CPU overlay/sampler matrices; no claim of GPU raster or codec execution. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class StudioImageMotionPlacementTest {
    private static final Size OUTPUT=new Size(320,180),SOURCE=new Size(12,6);

    @Test public void noncenterBaseAndRelativeKeysProjectRealMedia3CenterAndRotatedCorners() throws Exception {
        Fixture f=fixture();
        JSONArray keys=new JSONArray().put(key(0)).put(key(500).put("x",.125).put("y",-.25)
                .put("scaleX",1.5).put("scaleY",.5).put("rotate",75).put("opacity",.25)).put(key(1000));
        StudioTimedImageOverlay overlay=overlay(f,layer().put("width",.5).put("height",.4).put("rotation",15)
                .put("opacity",.8).put("motionKeyframes",keys));
        try{
            assertEquals(0,overlay.getOverlaySettings(299_999).getAlphaScale(),0);
            float[] initial=media3Matrix("OverlayMatrixProvider",overlay.getOverlaySettings(300_000));
            assertPixel(initial,0,0,80,135);
            OverlaySettings settings=overlay.getOverlaySettings(800_000);
            float[] actual=media3Matrix("OverlayMatrixProvider",settings);
            // Static center(.25,.75) + delta(.125,-.25) = (120,90) pixels.
            // Total90deg rotation of a 240x36 pixel panel gives these corners.
            assertPixel(actual,0,0,120,90);
            assertPixel(actual,-1,-1,138,210);assertPixel(actual,1,-1,138,-30);
            assertPixel(actual,1,1,102,-30);assertPixel(actual,-1,1,102,210);
            assertEquals(.2,settings.getAlphaScale(),.000001);
            assertSamplerInvertsActualPlacement(actual,media3Matrix("SamplerOverlayMatrixProvider",settings));
            assertEquals(0,overlay.getOverlaySettings(1_300_000).getAlphaScale(),0);
            Bitmap retained=overlay.getBitmap(800_000);assertEquals(12,retained.getWidth());assertEquals(6,retained.getHeight());
            assertEquals(0xffff0000,retained.getPixel(0,0));assertEquals(0xff0000ff,retained.getPixel(11,5));
            assertArrayEquals(f.bytes,Files.readAllBytes(f.file.toPath()));
        }finally{overlay.release();}
    }

    @Test public void offscreenGeometryRemainsOutsideViewportUntilItsAuthoredEntryAndExit() throws Exception {
        Fixture f=fixture();
        JSONArray keys=new JSONArray().put(key(0).put("x",-1.5)).put(key(500).put("y",-.5))
                .put(key(999).put("x",1.5)).put(key(1000).put("x",1.5));
        StudioTimedImageOverlay overlay=overlay(f,layer().put("width",.2).put("height",.3).put("motionKeyframes",keys));
        try{
            float[] left=media3Matrix("OverlayMatrixProvider",overlay.getOverlaySettings(300_000));
            assertPixel(left,0,0,-400,135);assertPixel(left,-1,-1,-432,162);assertPixel(left,1,1,-368,108);
            assertTrue("Every corner is left of the viewport",pixel(left,1,1)[0]<0);
            float[] entered=media3Matrix("OverlayMatrixProvider",overlay.getOverlaySettings(800_000));
            assertPixel(entered,0,0,80,45);assertPixel(entered,-1,-1,48,72);assertPixel(entered,1,1,112,18);
            OverlaySettings exitSettings=overlay.getOverlaySettings(1_299_000);
            float[] right=media3Matrix("OverlayMatrixProvider",exitSettings);
            assertPixel(right,0,0,560,135);assertPixel(right,-1,-1,528,162);assertPixel(right,1,1,592,108);
            assertTrue("Every corner is right of the viewport",pixel(right,-1,-1)[0]>OUTPUT.getWidth());
            assertSamplerInvertsActualPlacement(right,media3Matrix("SamplerOverlayMatrixProvider",exitSettings));
            assertEquals(0,overlay.getOverlaySettings(1_300_000).getAlphaScale(),0);
            assertArrayEquals(f.bytes,Files.readAllBytes(f.file.toPath()));
        }finally{overlay.release();}
    }

    private static float[] media3Matrix(String simpleName,OverlaySettings settings)throws Exception{
        Class<?> base=Class.forName("androidx.media3.effect.OverlayMatrixProvider");
        Class<?> type=Class.forName("androidx.media3.effect."+simpleName);
        Constructor<?> constructor=type.getDeclaredConstructor();constructor.setAccessible(true);Object provider=constructor.newInstance();
        Method configure=base.getDeclaredMethod("configure",Size.class);configure.setAccessible(true);configure.invoke(provider,OUTPUT);
        Method transform=type.getDeclaredMethod("getTransformationMatrix",Size.class,OverlaySettings.class);transform.setAccessible(true);
        return ((float[])transform.invoke(provider,SOURCE,settings)).clone();
    }
    private static double[] pixel(float[] matrix,float x,float y){
        float[] point=new float[4];android.opengl.Matrix.multiplyMV(point,0,matrix,0,new float[]{x,y,0,1},0);
        return new double[]{(point[0]/point[3]+1)*OUTPUT.getWidth()/2.0,(1-point[1]/point[3])*OUTPUT.getHeight()/2.0};
    }
    private static void assertPixel(float[] matrix,float x,float y,double expectedX,double expectedY){
        double[] point=pixel(matrix,x,y);assertEquals(expectedX,point[0],.0002);assertEquals(expectedY,point[1],.0002);
    }
    private static void assertSamplerInvertsActualPlacement(float[] forward,float[] inverse){
        for(float[] geometry:new float[][]{{0,0,0,1},{-1,-1,0,1},{1,1,0,1}}){
            float[] projected=new float[4],sampled=new float[4];
            android.opengl.Matrix.multiplyMV(projected,0,forward,0,geometry,0);
            android.opengl.Matrix.multiplyMV(sampled,0,inverse,0,projected,0);
            assertArrayEquals(geometry,sampled,.00001f);
        }
    }
    private static JSONObject key(long time)throws Exception{return new JSONObject().put("timeMs",time);}
    private static JSONObject layer()throws Exception{return new JSONObject().put("id","noncenter-panel").put("type","image")
            .put("assetId","owned-image").put("startMs",300).put("durationMs",1000).put("x",.25).put("y",.75);}
    private static StudioTimedImageOverlay overlay(Fixture f,JSONObject json){
        StudioTimedImageOverlay overlay=new StudioTimedImageOverlay(RuntimeEnvironment.getApplication(),f.asset,StudioLayer.fromJson(json));
        overlay.configure(OUTPUT);return overlay;
    }
    private static final class Fixture{File file;byte[] bytes;ProjectStore.Asset asset;}
    private static Fixture fixture()throws Exception{
        Fixture f=new Fixture();f.file=new File(RuntimeEnvironment.getApplication().getCacheDir(),"native-noncenter-"+UUID.randomUUID()+".png");
        Bitmap image=Bitmap.createBitmap(12,6,Bitmap.Config.ARGB_8888);image.eraseColor(0xff00ff00);image.setPixel(0,0,0xffff0000);image.setPixel(11,5,0xff0000ff);
        try(FileOutputStream output=new FileOutputStream(f.file)){assertTrue(image.compress(Bitmap.CompressFormat.PNG,100,output));}image.recycle();
        f.bytes=Files.readAllBytes(f.file.toPath());f.asset=new ProjectStore.Asset();f.asset.id="owned-image";f.asset.mime="image/png";f.asset.uri=Uri.fromFile(f.file).toString();return f;
    }
}
