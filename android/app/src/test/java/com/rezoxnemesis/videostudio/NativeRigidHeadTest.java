package com.rezoxnemesis.videostudio;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
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

/** Pixel regression: an occluded arm must not drag the marked facial artwork. */
@RunWith(RobolectricTestRunner.class) @Config(sdk=33,manifest=Config.NONE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public final class NativeRigidHeadTest {
    private static JSONArray head()throws Exception{return new JSONArray().put(new JSONArray().put(100/256d).put(37/384d))
            .put(new JSONArray().put(150/256d).put(37/384d)).put(new JSONArray().put(150/256d).put(87/384d))
            .put(new JSONArray().put(100/256d).put(87/384d));}
    private static Rig2DProfile fixture(String asset)throws Exception {
        float[][] joints={{.40f,.60f},{.43f,.35f},{.47f,.22f},{.49f,.16f},
                {.40f,.30f},{.43f,.17f},{.35f,.08f},{.48f,.32f},{.56f,.43f},{.57f,.52f},
                {.44f,.62f},{.50f,.75f},{.53f,.91f},{.36f,.62f},{.30f,.76f},{.26f,.91f}};
        return new Rig2DProfile("head-rig-1111","head-project-1111",asset,true,true,joints,null);
    }
    private static ProjectStore.Asset art(Context context)throws Exception {
        Bitmap image=Bitmap.createBitmap(256,384,Bitmap.Config.ARGB_8888);Canvas canvas=new Canvas(image);Paint paint=new Paint();
        paint.setColor(0xff253047);canvas.drawRect(85,85,140,235,paint);
        paint.setColor(0xfff4c5a2);canvas.drawRect(105,42,147,83,paint);
        paint.setColor(0xffef4343);canvas.drawRect(114,56,121,63,paint);
        paint.setColor(0xff32dce5);canvas.drawRect(132,56,139,63,paint);
        File file=new File(context.getFilesDir(),"rig-head-"+UUID.randomUUID()+".png");
        try(FileOutputStream out=new FileOutputStream(file)){assertTrue(image.compress(Bitmap.CompressFormat.PNG,100,out));}finally{image.recycle();}
        ProjectStore.Asset asset=new ProjectStore.Asset();asset.id=UUID.randomUUID().toString();asset.uri=Uri.fromFile(file).toString();asset.mime="image/png";return asset;
    }
    private static int colorCount(Bitmap image,int expected,int left,int top,int right,int bottom){
        int count=0;for(int y=top;y<bottom;y++)for(int x=left;x<right;x++){
            int actual=image.getPixel(x,y);if((actual>>>24)>240&&Math.abs((actual>>16&255)-(expected>>16&255))<5
                    &&Math.abs((actual>>8&255)-(expected>>8&255))<5&&Math.abs((actual&255)-(expected&255))<5)count++;
        }return count;
    }
    @Test public void sourceAuthoringAndRoundTripRetainTheOptionalHeadMask()throws Exception {
        JSONObject request=fixture("head-source-1111").toJson().put("headOutline",head());
        Rig2DProfile parsed=NativeRigAuthoring.parse("head-project-1111","head-source-1111",request);
        assertTrue("Source authoring must retain the marked head region",parsed.toJson().has("headOutline"));
        JSONArray saved=parsed.toJson().getJSONArray("headOutline");assertEquals(4,saved.length());
        for(int n=0;n<4;n++)for(int axis=0;axis<2;axis++)assertEquals(head().getJSONArray(n).getDouble(axis),saved.getJSONArray(n).getDouble(axis),1e-6);
        assertEquals(Rig2DProjectStore.sha256(parsed),Rig2DProjectStore.sha256(Rig2DProfile.fromJson(parsed.toJson())));
    }
    @Test public void armSwingCannotPullStationaryFacialPixelsOutOfTheirHead()throws Exception {
        Context context=RuntimeEnvironment.getApplication();ProjectStore.Asset asset=art(context);
        Rig2DProfile profile=Rig2DProfile.fromJson(fixture(asset.id).toJson().put("headOutline",head()));
        float[][] target=Rig2DProfile.fromJson(profile.toJson()).joints;
        target[4]=new float[]{.50f,.29f};target[5]=new float[]{.75f,.43f};target[6]=new float[]{.80f,.50f};
        Bitmap output=Bitmap.createBitmap(256,384,Bitmap.Config.ARGB_8888);
        try(NativeSkinnedSprite2D renderer=new NativeSkinnedSprite2D(context,asset,profile,256,384)){
            renderer.render(new Canvas(output),target,255);
            assertTrue("The left eye pixels moved with the nearby arm",colorCount(output,0xffef4343,112,54,123,65)>=40);
            assertTrue("The right eye pixels moved with the nearby arm",colorCount(output,0xff32dce5,130,54,141,65)>=40);
        }finally{output.recycle();}
    }
    @Test public void markedHeadFollowsHeadTranslationAndWholeActorOpacity()throws Exception {
        Context context=RuntimeEnvironment.getApplication();ProjectStore.Asset asset=art(context);
        Rig2DProfile profile=Rig2DProfile.fromJson(fixture(asset.id).toJson().put("headOutline",head()));
        float[][] target=Rig2DProfile.fromJson(profile.toJson()).joints;
        for(int joint:new int[]{2,3}){target[joint][0]+=18/256f;target[joint][1]+=10/384f;}
        Bitmap output=Bitmap.createBitmap(256,384,Bitmap.Config.ARGB_8888);
        try(NativeSkinnedSprite2D renderer=new NativeSkinnedSprite2D(context,asset,profile,256,384)){
            renderer.render(new Canvas(output),target,255);
            assertTrue("The head must follow its own controls",colorCount(output,0xffef4343,130,64,141,75)>=40);
            output.eraseColor(0);renderer.render(new Canvas(output),target,128);
            assertEquals("Head opacity belongs to the same actor layer",128,output.getPixel(135,69)>>>24,1);
            output.eraseColor(0);renderer.render(new Canvas(output),target,0);
            assertEquals(0,output.getPixel(135,69));
        }finally{output.recycle();}
    }
    @Test public void malformedHeadMaskCannotSilentlyBecomeAnUnprotectedRig()throws Exception {
        JSONObject source=fixture("head-source-1111").toJson();
        for(Object invalid:new Object[]{"auto",JSONObject.NULL,new JSONArray().put(new JSONArray().put(.2).put(.3))})
            assertThrows(IllegalArgumentException.class,()->Rig2DProfile.fromJson(new JSONObject(source.toString()).put("headOutline",invalid)));
    }
    @Test public void protectedHeadPredictionRejectsTheSameDegeneratePoseAsRendering()throws Exception {
        Rig2DProfile profile=Rig2DProfile.fromJson(fixture("head-source-1111").toJson().put("headOutline",head()));
        float[][] target=Rig2DProfile.fromJson(profile.toJson()).joints;target[3]=target[2].clone();
        assertThrows(IllegalArgumentException.class,()->NativeSkinnedSprite2D.skinPoint(profile,target,.49f,.16f,256d/384));
    }
}
