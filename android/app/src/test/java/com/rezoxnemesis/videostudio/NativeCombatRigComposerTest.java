package com.rezoxnemesis.videostudio;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Color;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import java.util.UUID;
import static org.junit.Assert.*;

/** Actual native pixel/asset integration tests; not a mock animation export. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=33,manifest=Config.NONE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class NativeCombatRigComposerTest {
    @Test public void lockedWorldRemainsIdenticalWhileBothSkeletonsMove(){
        NativeCombatRigRenderer renderer=new NativeCombatRigRenderer(320,560);
        Bitmap backdrop=renderer.staticBackgroundCopy();
        Bitmap before=renderer.render(CombatRigSolver.at(.15,3.4),.15,3.4);
        Bitmap strike=renderer.render(CombatRigSolver.at(1.66,3.4),1.66,3.4);
        try {
            assertEquals(backdrop.getPixel(25,50),before.getPixel(25,50));
            assertEquals(backdrop.getPixel(25,50),strike.getPixel(25,50));
            assertEquals(backdrop.getPixel(309,80),before.getPixel(309,80));
            assertEquals(backdrop.getPixel(309,80),strike.getPixel(309,80));
            assertFalse("Newly animated sword fighters must change pixels",before.sameAs(strike));
            assertEquals(320,strike.getWidth());assertEquals(560,strike.getHeight());
        } finally {
            backdrop.recycle();before.recycle();strike.recycle();renderer.recycle();
        }
    }

    @Test public void standaloneCombatSequenceCreatesActualNativeTimeline() throws Exception {
        Context context=RuntimeEnvironment.getApplication();
        ProjectStore store=new ProjectStore(context);
        ProjectStore.Project output=store.create("Native Combat Rig Test "+UUID.randomUUID());
        final int[] progress={0};
        NativeCombatRigComposer.compose(context,store,output,320,560,24,2.0,
                (current,total,time,impact)->{
                    progress[0]++;
                    assertTrue(current<=total);
                    assertTrue(time>=0 && time<=2);
                });
        assertEquals(48,progress[0]);
        assertEquals(48,output.clips.size());
        assertEquals(48,output.assets.size());
        assertEquals(48,store.get(output.id).clips.size());
        assertEquals(48*42,output.outputDurationMs());
        assertNotEquals(output.assets.get(0).uri,output.assets.get(1).uri);
        for(int i=0;i<48;i++){
            ProjectStore.Asset a=output.assets.get(i);
            assertTrue(a.generated);
            assertEquals("image/jpeg",a.mime);
            assertEquals("builtin.native-combat-rig-v1",a.generationMetadata.optString("engine"));
            assertTrue(a.generationMetadata.optBoolean("fixedEnvironment"));
            assertFalse(a.generationMetadata.optBoolean("referenceArtReconstruction"));
            assertEquals("none",output.clips.get(i).transition);
            assertTrue(output.clips.get(i).effects.optBoolean("directorSequenceFrame"));
        }
    }

    @Test(expected=IllegalArgumentException.class)
    public void oversizedFrameCountIsRejectedBeforeRendering() throws Exception {
        Context context=RuntimeEnvironment.getApplication();
        ProjectStore store=new ProjectStore(context);
        ProjectStore.Project out=store.create("Unsafe high resolution "+UUID.randomUUID());
        NativeCombatRigComposer.compose(context,store,out,1400,2500,30,4.0,null);
    }
}
