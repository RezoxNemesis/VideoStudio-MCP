package com.rezoxnemesis.videostudio;

import android.content.Context;
import android.graphics.Bitmap;
import android.net.Uri;
import org.json.JSONArray;
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

/** Native Director must never replace original sources with camera-zoom presets. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=33,manifest=Config.NONE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class NativeAnimationDirectorTest {
    private ProjectStore.Project project(Context context,ProjectStore store,int count) throws Exception {
        ProjectStore.Project source=store.create("40 authored frames (fixture)");
        for(int index=0;index<count;index++) {
            Bitmap bitmap=Bitmap.createBitmap(256,448,Bitmap.Config.ARGB_8888);
            bitmap.eraseColor(0xff303a46);
            int x=30+(index*8)%180;
            for(int y=180;y<230;y++)
                for(int dx=0;dx<26;dx++) bitmap.setPixel(x+dx,y,0xffffffff);
            File file=new File(context.getFilesDir(),UUID.randomUUID()+".png");
            try(FileOutputStream out=new FileOutputStream(file)){
                assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG,100,out));
            }
            bitmap.recycle();
            ProjectStore.Asset asset=new ProjectStore.Asset();
            asset.id=UUID.randomUUID().toString();
            asset.uri=Uri.fromFile(file).toString();
            asset.mime="image/png";
            asset.name="frame_"+index+".png";
            source.assets.add(asset);
            ProjectStore.Clip clip=new ProjectStore.Clip();
            clip.id=UUID.randomUUID().toString();
            clip.assetId=asset.id;
            clip.outMs=3000;
            source.clips.add(clip);
        }
        store.save(source);
        return source;
    }

    @Test public void directorCreatesFortySequentialFramesWithoutEditingSource() throws Exception {
        Context c=RuntimeEnvironment.getApplication();
        ProjectStore store=new ProjectStore(c);
        ProjectStore.Project source=project(c,store,40);
        JSONArray custom=new JSONArray().put(21).put(23);
        NativeAnimationDirector.Plan plan=NativeAnimationDirector.plan(c,source,30,custom);
        assertEquals(40,plan.sourceIds.size());
        assertEquals(40,plan.frameDurationsMs.size());
        assertEquals(2,plan.impactIndices.size());
        assertTrue(plan.frameDurationsMs.get(21)>=plan.frameDurationsMs.get(20));
        ProjectStore.Project output=NativeAnimationDirector.createDirectProject(store,source,plan);
        assertNotEquals(source.id,output.id);
        assertEquals(40,output.clips.size());
        assertEquals(40,output.assets.size());
        assertEquals(plan.totalDurationMs,output.outputDurationMs());
        for(int i=0;i<40;i++) {
            assertEquals(source.clips.get(i).assetId,output.clips.get(i).assetId);
            assertEquals("none",output.clips.get(i).transition);
            assertTrue(output.clips.get(i).effects.optBoolean("directorSequenceFrame"));
            assertEquals(30,output.clips.get(i).effects.optInt("poseFps"));
            assertFalse(output.clips.get(i).effects.has("motionPreset"));
            assertTrue(output.clips.get(i).outMs>=68);
        }
        assertEquals(120000,store.get(source.id).outputDurationMs());
        assertEquals(40,store.get(source.id).clips.size());
        assertEquals(40,store.get(source.id).assets.size());
    }

    @Test public void nativeAutoDirectorAndFlowPlannerRespectFrameBounds() throws Exception {
        Context c=RuntimeEnvironment.getApplication();
        ProjectStore store=new ProjectStore(c);
        ProjectStore.Project source=project(c,store,40);
        NativeAnimationDirector.Plan plan=NativeAnimationDirector.plan(c,source,24,null);
        assertEquals(40,plan.frameDurationsMs.size());
        assertEquals(39,NativeAnimationDirector.recommendedFlowSteps(plan).length());
        int frames=1;
        for(int i=0;i<39;i++) {
            int steps=NativeAnimationDirector.recommendedFlowSteps(plan).optInt(i);
            assertTrue(steps>=2&&steps<=4);
            frames+=steps;
        }
        assertTrue(frames<=240);
    }

    @Test public void autoImpactsAreSeparatedAndDoNotMarkEveryFrame() {
        int[] marks=NativeAnimationDirector.autoImpacts(
                Arrays.asList(0d,.001,.01,.19,.04,.03,.02,.15,.01,.02,.001),3);
        assertTrue(marks.length>=1);
        assertTrue(marks.length<=3);
        for(int i=1;i<marks.length;i++) assertTrue(marks[i]-marks[i-1]>=3);
    }

    @Test(expected=IllegalArgumentException.class)
    public void rejectsOutOfBoundsUserImpactIndex() throws Exception {
        Context c=RuntimeEnvironment.getApplication();
        ProjectStore store=new ProjectStore(c);
        NativeAnimationDirector.plan(c,project(c,store,2),30,new JSONArray().put(5));
    }
}
