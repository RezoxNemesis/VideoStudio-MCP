package com.rezoxnemesis.videostudio;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.util.Base64;
import java.util.ArrayList;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.shadows.ShadowMediaMetadataRetriever;
import static org.junit.Assert.*;

/** Exact24×14 signature-size alias ownership; synthetic unit frames, not actual video decoding. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=33,manifest=Config.NONE,shadows=NativeMediaAnalyzerSmallFrameTest.TinyDecoder.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class NativeMediaAnalyzerSmallFrameTest {
    @After public void cleanup(){for(Bitmap frame:TinyDecoder.frames)if(!frame.isRecycled())frame.recycle();TinyDecoder.frames.clear();}

    @Test public void signatureSizedDecoderFrameStaysAliveUntilTheContactSheetIsEncoded()throws Exception {
        TinyDecoder.frames.clear();Context context=RuntimeEnvironment.getApplication();
        ProjectStore.Asset asset=new ProjectStore.Asset();asset.id="synthetic_signature_alias";asset.name="Synthetic tiny decoder ownership";
        asset.mime="video/mp4";asset.uri="content://synthetic/tiny-decoder";asset.durationMs=1000;
        JSONObject result=new NativeMediaAnalyzer(context).analyse(asset,6,0,1000);
        assertEquals(6,result.getJSONObject("contactSheet").getInt("frameCount"));assertEquals(6,TinyDecoder.frames.size());
        byte[] bytes=Base64.decode(result.getJSONObject("contactSheet").getString("base64"),Base64.DEFAULT);
        Bitmap sheet=BitmapFactory.decodeByteArray(bytes,0,bytes.length);assertNotNull(sheet);
        try{
            int center=sheet.getPixel(150,100);assertTrue("The original green frame must survive into the actual native JPEG",Color.green(center)>240);
            assertTrue(Color.red(center)<20);assertTrue(Color.blue(center)<20);
        }finally{sheet.recycle();}
        for(Bitmap frame:TinyDecoder.frames)assertTrue("Decoder frames are released after successful encoding",frame.isRecycled());
    }

    @Implements(MediaMetadataRetriever.class)
    public static class TinyDecoder extends ShadowMediaMetadataRetriever {
        static final ArrayList<Bitmap> frames=new ArrayList<>();
        @Implementation @Override protected void setDataSource(Context context,Uri uri){}
        @Implementation @Override protected String extractMetadata(int key){
            if(key==MediaMetadataRetriever.METADATA_KEY_DURATION)return "1000";
            if(key==MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)return "24";
            if(key==MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)return "14";
            if(key==MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)return "0";
            return null;
        }
        @Implementation @Override protected Bitmap getFrameAtTime(long timeUs,int option){Bitmap frame=Bitmap.createBitmap(24,14,Bitmap.Config.ARGB_8888);frame.eraseColor(Color.GREEN);frames.add(frame);return frame;}
        @Implementation protected void release(){}
    }
}
