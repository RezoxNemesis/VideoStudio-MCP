package com.rezoxnemesis.videostudio;

import android.content.Context;
import android.graphics.Bitmap;
import android.net.Uri;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import java.io.File;
import java.io.FileOutputStream;
import java.util.UUID;
import static org.junit.Assert.*;

/** Actually render a tiny source-owned mobile film inside Robolectric. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=33,manifest=Config.NONE)
public final class StoryboardNativeFilmTest {
    private static final int W=256,H=432;

    private File input(Context ctx,int step) throws Exception {
        Bitmap original=Bitmap.createBitmap(W,H,Bitmap.Config.ARGB_8888);
        int[] pixels=new int[W*H];
        for(int y=0;y<H;y++)for(int x=0;x<W;x++){
            pixels[y*W+x]=0xff000000|
                    ((130+x/5)<<16)|((72+y/13)<<8)|(45+x/10);
        }
        box(pixels,10+step*46,180,27,85,0xff15131a);
        box(pixels,210-step*16,182,22,75,0xfff0f2eb);
        original.setPixels(pixels,0,W,0,0,W,H);
        File output=new File(ctx.getFilesDir(),"source_test_"+step+"_"+UUID.randomUUID()+".png");
        try(FileOutputStream out=new FileOutputStream(output)){
            assertTrue(original.compress(Bitmap.CompressFormat.PNG,100,out));
        }
        original.recycle();
        return output;
    }
    private void box(int[] image,int x,int y,int width,int height,int color){
        for(int row=y;row<y+height;row++)
            for(int col=x;col<x+width;col++)
                image[row*W+col]=color;
    }
    @Test public void buildsTrueIntermediateFramesAndFoleyWithoutAlteringSource()
            throws Exception {
        Context ctx=RuntimeEnvironment.getApplication();
        ProjectStore store=new ProjectStore(ctx);
        ProjectStore.Project source=store.create("Two moving illustrated actors");
        source.sourcePrompt="Illustrated action";
        for(int i=0;i<2;i++){
            File file=input(ctx,i);
            ProjectStore.Asset asset=new ProjectStore.Asset();
            asset.id=UUID.randomUUID().toString();
            asset.name=String.format(java.util.Locale.US,"%02d_Action.png",i);
            asset.uri=Uri.fromFile(file).toString();
            asset.mime="image/png";
            asset.sizeBytes=file.length();asset.seekable=true;
            asset.persistedReadAccess=true;
            source.assets.add(asset);
            ProjectStore.Clip clip=new ProjectStore.Clip();
            clip.id=UUID.randomUUID().toString();
            clip.assetId=asset.id;clip.inMs=0;clip.outMs=3000;
            source.clips.add(clip);
        }
        store.save(source);
        JSONObject params=new JSONObject().put("backend","AUTO_LAYERS")
                .put("genre","action").put("fps",24)
                .put("framesPerPair",6).put("foley",true);
        StoryboardBatchPlanner.Plan plan=StoryboardBatchPlanner.plan(source,params);
        ProjectStore.Project generated=store.create("2D source movement test");
        StoryboardSeriesOrchestrator.Result finished=
                StoryboardSeriesOrchestrator.run(ctx,store,source,generated,
                        plan,0,W,H,params,null);
        assertEquals(7,finished.frames);
        assertEquals(7,generated.clips.size());
        assertTrue(generated.assets.size()>=8);
        assertEquals(1,generated.studioLayers.size());
        assertEquals("audio",generated.studioLayers.get(0).type);
        assertNotNull(generated.asset(generated.clips.get(3).assetId));
        assertTrue(new File(Uri.parse(generated.asset(
                generated.clips.get(3).assetId).uri).getPath()).isFile());
        ProjectStore.Project unchanged=store.get(source.id);
        assertEquals(2,unchanged.clips.size());
        assertEquals(2,unchanged.assets.size());
        assertEquals(7,store.get(generated.id).clips.size());
    }
}
