package com.rezoxnemesis.videostudio;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.net.Uri;
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

/** Verifies NEW moving source-textured pixels, not a procedural stick overlay. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=33,manifest=Config.NONE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class NativeSkinnedSprite2DTest {
    static ProjectStore.Asset image(Context ctx,boolean black) throws Exception {
        Bitmap art=Bitmap.createBitmap(256,448,Bitmap.Config.ARGB_8888);
        art.eraseColor(0xff465766);  // Must NOT leak into scene after masking.
        int material=black?0xff131b29:0xfff0eadc;
        for(int y=105;y<280;y++)
            for(int x=74;x<180;x++)art.setPixel(x,y,material);
        File f=new File(ctx.getFilesDir(),"authored_"+UUID.randomUUID()+".png");
        try(FileOutputStream out=new FileOutputStream(f)){
            assertTrue(art.compress(Bitmap.CompressFormat.PNG,100,out));
        }finally{art.recycle();}
        ProjectStore.Asset a=new ProjectStore.Asset();
        a.id=UUID.randomUUID().toString();a.name=f.getName();
        a.mime="image/png";a.uri=Uri.fromFile(f).toString();
        return a;
    }
    static Rig2DProfile profile(String project,String asset,boolean black){
        return Rig2DProfile.create(project,asset,black,false,
                Rig2DProfileTest.joints(),
                new float[][]{{.28f,.22f},{.71f,.22f},{.71f,.73f},{.28f,.73f}});
    }

    @Test public void maskRemovesStaticCityAndActuallyWarpsCharacterPixels() throws Exception{
        Context c=RuntimeEnvironment.getApplication();
        ProjectStore.Asset a=image(c,true);
        Rig2DProfile p=profile("12345678",a.id,true);
        NativeSkinnedSprite2D mesh=new NativeSkinnedSprite2D(c,a,p,320,560);
        Bitmap sourceCutout=mesh.getCutoutForTesting();
        Bitmap before=Bitmap.createBitmap(320,560,Bitmap.Config.ARGB_8888);
        Bitmap after=Bitmap.createBitmap(320,560,Bitmap.Config.ARGB_8888);
        try{
            assertEquals("Unmasked original background leaked into rig layer",
                    0,Color.alpha(sourceCutout.getPixel(0,0)));
            assertTrue("The source-colored fighter must remain visible",
                    Color.alpha(sourceCutout.getPixel(156,245))>230);
            CombatRigSolver.Frame poseA=CombatRigSolver.at(.2,3.6,true);
            CombatRigSolver.Frame poseB=CombatRigSolver.at(2.5,3.6,true);
            mesh.render(new Canvas(before),poseA.black);
            mesh.render(new Canvas(after),poseB.black);
            assertFalse("Skinned fighter must truly change position/shape",
                    before.sameAs(after));
            assertTrue(countVisible(before)>0);
            assertTrue(countVisible(after)>0);
        }finally{
            sourceCutout.recycle();before.recycle();after.recycle();mesh.close();
        }
    }

    private static int countVisible(Bitmap b){
        int count=0;
        for(int y=0;y<b.getHeight();y+=5)
            for(int x=0;x<b.getWidth();x+=5)
                if(Color.alpha(b.getPixel(x,y))>150)count++;
        return count;
    }

    @Test(expected=IllegalArgumentException.class)
    public void cannotTreatAnOpaqueFullFrameAsAnIsolatedActor() throws Exception{
        Context c=RuntimeEnvironment.getApplication();
        ProjectStore.Asset a=image(c,false);
        Rig2DProfile p=Rig2DProfile.create(
                "12345678",a.id,false,true,Rig2DProfileTest.joints(),null);
        try(NativeSkinnedSprite2D ignored=new NativeSkinnedSprite2D(c,a,p,320,560)){}
    }

    @Test public void twoMaskedOriginalActorsProduceDistinctCombatFrames() throws Exception{
        Context c=RuntimeEnvironment.getApplication();
        ProjectStore.Asset b=image(c,true),w=image(c,false);
        NativeSkinnedSprite2D black=new NativeSkinnedSprite2D(
                c,b,profile("12345678",b.id,true),320,560);
        NativeSkinnedSprite2D white=new NativeSkinnedSprite2D(
                c,w,profile("12345678",w.id,false),320,560);
        NativeRiggedCombatRenderer renderer=
                new NativeRiggedCombatRenderer(320,560,null,black,white);
        Bitmap still=renderer.staticWorldCopy();
        Bitmap a=renderer.render(CombatRigSolver.at(.1,4.5,true),0);
        Bitmap clash=renderer.render(CombatRigSolver.at(2.4,4.5,true),58);
        try{
            assertFalse("Rigged actors must move in independently rendered pixels",
                    a.sameAs(clash));
            assertEquals(still.getPixel(6,22),a.getPixel(6,22));
            assertEquals(still.getPixel(6,22),clash.getPixel(6,22));
        }finally{
            a.recycle();clash.recycle();still.recycle();renderer.close();
        }
    }
}
