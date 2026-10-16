package com.rezoxnemesis.videostudio;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Rect;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import static org.junit.Assert.*;

/** End-to-end bitmap parity for tile-based portrait mask/cutout operations.
 * All pixels are checked; no face model or user image is loaded. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=33,manifest=Config.NONE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public final class NativePortraitMemorySafetyTest {
    private Object maskStats()throws Exception{
        Class<?> cls=Class.forName("com.rezoxnemesis.videostudio.NativePortraitMotionAnalyzer$MaskStats");
        Constructor<?> ctor=cls.getDeclaredConstructors()[0];ctor.setAccessible(true);
        int mw=18,mh=24;float[] mask=new float[mw*mh];
        for(int y=0;y<mh;y++)for(int x=0;x<mw;x++)
            mask[y*mw+x]=(x>=5&&x<14&&y>=4&&y<20)?0.85f:0.02f;
        return ctor.newInstance(mask,mw,mh,.33,.5,.5,.25,.16,.78,.85);
    }
    private static Bitmap bitmap(){
        Bitmap result=Bitmap.createBitmap(90,120,Bitmap.Config.ARGB_8888);
        for(int y=0;y<120;y++)for(int x=0;x<90;x++)
            result.setPixel(x,y,Color.argb(255,100+x,65+y,28+x/2));
        return result;
    }
    private static Object call(NativePortraitMotionAnalyzer obj,String name,Class<?>[] types,Object... args)throws Exception{
        Method method=NativePortraitMotionAnalyzer.class.getDeclaredMethod(name,types);
        method.setAccessible(true);return method.invoke(obj,args);
    }
    private static Bitmap image(Object composite,String name)throws Exception{
        Field field=composite.getClass().getDeclaredField(name);
        field.setAccessible(true);return (Bitmap)field.get(composite);
    }
    @Test public void foregroundAndThreeCutoutsComposeWithoutMissingArmsOrUnexpectedAlpha()throws Exception{
        NativePortraitMotionAnalyzer painter=new NativePortraitMotionAnalyzer(RuntimeEnvironment.getApplication());
        Object stats=maskStats();
        Bitmap source=bitmap(),fg=null;
        Bitmap head=null,torso=null,lower=null;
        try{
            fg=(Bitmap)call(painter,"buildForeground",
                    new Class<?>[]{Bitmap.class,stats.getClass()},source,stats);
            assertEquals(90,fg.getWidth());assertEquals(120,fg.getHeight());
            assertTrue(Color.alpha(fg.getPixel(45,65))>=180);
            assertTrue(Color.alpha(fg.getPixel(1,1))<=5);
            Object parts=call(painter,"buildSubjectParts",
                    new Class<?>[]{Bitmap.class,stats.getClass(),Rect.class},
                    fg,stats,new Rect(30,20,65,45));
            head=image(parts,"head");torso=image(parts,"torso");lower=image(parts,"lower");
            for(int y=0;y<120;y++)for(int x=0;x<90;x++){
                int original=Color.alpha(fg.getPixel(x,y));
                int sum=Color.alpha(head.getPixel(x,y))+Color.alpha(torso.getPixel(x,y))
                        +Color.alpha(lower.getPixel(x,y));
                assertEquals("No dropped silhouette at "+x+","+y,original,sum,2);
            }
        }finally{
            source.recycle();
            if(fg!=null)fg.recycle();
            if(head!=null)head.recycle();
            if(torso!=null)torso.recycle();
            if(lower!=null)lower.recycle();
        }
    }
    @Test public void reconstructedBackgroundUsesSourceDimensionsAndDoesNotLoseOuterScene()throws Exception{
        NativePortraitMotionAnalyzer painter=new NativePortraitMotionAnalyzer(RuntimeEnvironment.getApplication());
        Object stats=maskStats();
        Bitmap source=bitmap(),background=null;
        try{
            background=(Bitmap)call(painter,"buildReconstructedBackground",
                    new Class<?>[]{Bitmap.class,stats.getClass()},source,stats);
            assertEquals(source.getWidth(),background.getWidth());
            assertEquals(source.getHeight(),background.getHeight());
            assertEquals(255,Color.alpha(background.getPixel(1,1)));
            assertNotEquals("Subject interior should be replaced rather than ghosted",
                    source.getPixel(45,62),background.getPixel(45,62));
        }finally{
            source.recycle();
            if(background!=null)background.recycle();
        }
    }
}
