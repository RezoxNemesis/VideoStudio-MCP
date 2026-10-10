package com.rezoxnemesis.videostudio;

import android.content.Context;
import android.graphics.Bitmap;
import android.net.Uri;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import java.io.File;
import java.io.FileOutputStream;
import java.util.Arrays;
import java.util.UUID;
import static org.junit.Assert.*;

/** Verifies complete asset -> independently warped frame -> native project pipeline. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=33,manifest=Config.NONE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class NativePoseSequenceComposerTest {
    private static final int W=256,H=448;

    private ProjectStore.Asset sourceFrame(Context context,int x) throws Exception {
        Bitmap b=Bitmap.createBitmap(W,H,Bitmap.Config.ARGB_8888);
        b.eraseColor(0xff334455);
        for(int row=170;row<230;row++)
            for(int col=x;col<x+35;col++) b.setPixel(col,row,0xffeeffee);
        File f=new File(context.getFilesDir(),UUID.randomUUID()+".png");
        try(FileOutputStream file=new FileOutputStream(f)){
            assertTrue(b.compress(Bitmap.CompressFormat.PNG,100,file));
        }
        b.recycle();
        ProjectStore.Asset asset=new ProjectStore.Asset();
        asset.id=UUID.randomUUID().toString();
        asset.uri=Uri.fromFile(f).toString();
        asset.name="scene_"+x+".png";
        asset.mime="image/png";
        return asset;
    }

    @Test public void twoAuthoredPosesBecomeFiveTimeOrderedUniqueFrames() throws Exception {
        Context context=RuntimeEnvironment.getApplication();
        ProjectStore store=new ProjectStore(context);
        ProjectStore.Project source=store.create("Source frames never modified");
        ProjectStore.Asset a=sourceFrame(context,74);
        ProjectStore.Asset b=sourceFrame(context,92);
        source.assets.add(a);source.assets.add(b);store.save(source);
        ProjectStore.Project output=store.create("Generated motion");
        int[] updates={0};
        NativePoseSequenceComposer.compose(context,store,source,output,
                Arrays.asList(a.id,b.id),W,H,new int[]{4},24,
                (done,total,quality)->{updates[0]++;assertTrue(done<=total);});
        assertEquals(5,updates[0]);
        assertEquals(5,output.clips.size());
        assertEquals(5,output.assets.size());
        assertEquals(2,store.get(source.id).assets.size());
        assertEquals(0,store.get(source.id).clips.size());
        for(ProjectStore.Clip c:output.clips){
            assertEquals(42,c.outMs);
            assertEquals(24,c.effects.optInt("poseFps"));
            assertEquals("none",c.transition);
        }
        Bitmap first=BitmapFactoryFromAsset(output,0),mid=BitmapFactoryFromAsset(output,2);
        Bitmap last=BitmapFactoryFromAsset(output,4);
        try{
            assertFalse(first.sameAs(mid));
            assertFalse(mid.sameAs(last));
            assertEquals(W,first.getWidth());assertEquals(H,first.getHeight());
            assertEquals(0xff334455,first.getPixel(10,10));
        }finally{
            first.recycle();mid.recycle();last.recycle();
        }
    }

    private Bitmap BitmapFactoryFromAsset(ProjectStore.Project output,int index){
        String uri=output.assets.get(index).uri;
        return android.graphics.BitmapFactory.decodeFile(Uri.parse(uri).getPath());
    }
}
