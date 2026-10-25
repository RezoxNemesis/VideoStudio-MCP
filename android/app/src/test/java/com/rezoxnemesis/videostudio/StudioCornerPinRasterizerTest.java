package com.rezoxnemesis.videostudio;

import android.graphics.Bitmap;
import android.graphics.Color;
import android.view.MotionEvent;
import org.json.JSONArray;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.Assert.*;

/** Real Skia projective matrix pixel tests and non-destructive handle gestures. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=33,manifest=Config.NONE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public final class StudioCornerPinRasterizerTest {
    private static Bitmap opaque(){
        Bitmap b=Bitmap.createBitmap(100,100,Bitmap.Config.ARGB_8888);
        b.eraseColor(Color.RED);return b;
    }
    @Test public void realPerspectiveCornersClipBitmapOutsideTrapezoid()throws Exception{
        Bitmap original=opaque();
        JSONArray points=StudioCornerPinDialog.parse("0.2,0.1,0.9,0.2,0.8,0.9,0.1,0.8");
        Bitmap mapped=StudioCornerPinRasterizer.apply(original,points);
        try{
            assertNotSame(original,mapped);
            assertEquals(100,mapped.getWidth());assertEquals(100,mapped.getHeight());
            assertEquals(0,Color.alpha(mapped.getPixel(0,0)));
            assertEquals(0,Color.alpha(mapped.getPixel(99,99)));
            assertTrue("Central source survives projective transfer",
                    Color.alpha(mapped.getPixel(50,50))>200);
            assertEquals("Input is never destroyed or changed",255,Color.alpha(original.getPixel(0,0)));
            assertEquals(Color.RED,original.getPixel(50,50));
        }finally{original.recycle();mapped.recycle();}
    }

    @Test public void identityPinAddsNoNewBitmapOrProjectMutation(){
        Bitmap original=opaque();
        try{
            assertTrue(StudioCornerPinRasterizer.isIdentity(StudioCornerPinRasterizer.identity()));
            assertSame(original,StudioCornerPinRasterizer.apply(original,StudioCornerPinRasterizer.identity()));
        }finally{original.recycle();}
    }

    @Test public void convexityAndBoundsRejectCrossingOrInvertedCorners(){
        assertThrows(IllegalArgumentException.class,()->
            StudioCornerPinDialog.parse("0,0,1,0,0,1,1,1"));
        assertThrows(IllegalArgumentException.class,()->
            StudioCornerPinDialog.parse("0,0,1,0,1,0,0,1"));
        assertThrows(IllegalArgumentException.class,()->
            StudioCornerPinDialog.parse("-0.1,0,1,0,1,1,0,1"));
        assertThrows(IllegalArgumentException.class,()->
            StudioCornerPinDialog.parse("NaN,0,1,0,1,1,0,1"));
        assertThrows(IllegalArgumentException.class,()->
            StudioCornerPinDialog.parse("0,0,1,0,1,1"));
    }

    @Test public void actualTouchCornerHandlesUpdateOnlyDraftGeometry(){
        StudioCornerPinTouchView v=new StudioCornerPinTouchView(RuntimeEnvironment.getApplication());
        v.layout(0,0,360,290);
        AtomicInteger changes=new AtomicInteger();
        v.onChange(ignored->changes.incrementAndGet());
        MotionEvent down=MotionEvent.obtain(0,0,MotionEvent.ACTION_DOWN,24,24,0);
        MotionEvent move=MotionEvent.obtain(0,10,MotionEvent.ACTION_MOVE,60,55,0);
        MotionEvent up=MotionEvent.obtain(0,20,MotionEvent.ACTION_UP,60,55,0);
        try{
            assertTrue(v.onTouchEvent(down));assertTrue(v.onTouchEvent(move));assertTrue(v.onTouchEvent(up));
            assertTrue(changes.get()>0);
            assertTrue(v.corners().optDouble(0)>0);
            assertTrue(v.corners().optDouble(1)>0);
            assertEquals(8,StudioCornerPinRasterizer.validate(v.corners()).length());
        }finally{down.recycle();move.recycle();up.recycle();}
    }

    @Test public void unsupportedVideoOrTitleCornerPinsFailWithoutLegacySchemaChanges()throws Exception{
        StudioLayer source=new StudioLayer();source.type=StudioLayer.TITLE;source.text="Existing title";
        String serialized=source.toJson().toString();
        assertFalse(serialized.contains("cornerPin"));
        source.cornerPin=StudioCornerPinRasterizer.identity();
        assertThrows(IllegalArgumentException.class,source::validate);
        assertThrows(IllegalArgumentException.class,()->
            StudioLayer.fromJson(new org.json.JSONObject().put("type","video")
                    .put("assetId","owned").put("audioMode","silent")
                    .put("cornerPin",StudioCornerPinRasterizer.identity())));
    }
}
