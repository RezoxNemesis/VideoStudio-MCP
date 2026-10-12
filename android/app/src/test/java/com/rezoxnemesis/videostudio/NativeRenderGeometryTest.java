package com.rezoxnemesis.videostudio;

import androidx.media3.effect.Presentation;
import androidx.media3.common.util.Size;
import androidx.media3.transformer.EditedMediaItem;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import java.lang.reflect.Method;
import static org.junit.Assert.*;

/** The real Media3 presentation boundary, not only source-render metadata. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=33,manifest=Config.NONE)
public class NativeRenderGeometryTest {
    @Test public void exactThreeByFourStoryFramesKeepTheirAdmittedRasterInsteadOfCropping()throws Exception{
        Size size=presentation("source:768x1024",768,1024);
        assertEquals(768,size.getWidth());assertEquals(1024,size.getHeight());
    }
    @Test public void exactFourByFiveStoryFramesKeepTheirWholeFrame()throws Exception{
        Size size=presentation("source:640x800",640,800);
        assertEquals(640,size.getWidth());assertEquals(800,size.getHeight());
    }
    @Test public void existingPortraitQualityPresetKeepsItsExistingOutputSize()throws Exception{
        Size size=presentation("9:16",720,1280);
        assertEquals(720,size.getWidth());assertEquals(1280,size.getHeight());
    }
    @Test public void sourceRasterPublicationRejectsEncoderFallbackOrTransposedDimensions(){
        assertTrue(NativeRenderEngine.outputGeometryMatches("source:768x1024",768,1024));
        assertFalse(NativeRenderEngine.outputGeometryMatches("source:768x1024",720,1280));
        assertFalse(NativeRenderEngine.outputGeometryMatches("source:768x1024",1024,768));
        assertFalse(NativeRenderEngine.outputGeometryMatches("source:768x1024",0,1024));
    }
    private Size presentation(String aspect,int width,int height)throws Exception{
        ProjectStore.Asset asset=new ProjectStore.Asset();asset.id="geometry";asset.uri="file:///synthetic-geometry.png";asset.mime="image/png";
        ProjectStore.Clip clip=new ProjectStore.Clip();clip.assetId=asset.id;clip.outMs=1000;
        Method build=NativeRenderEngine.class.getDeclaredMethod("buildItem",ProjectStore.Asset.class,ProjectStore.Clip.class,String.class,String.class,boolean.class);
        build.setAccessible(true);
        EditedMediaItem item=(EditedMediaItem)build.invoke(new NativeRenderEngine(RuntimeEnvironment.getApplication()),asset,clip,aspect,"720p",true);
        return ((Presentation)item.effects.videoEffects.get(0)).configure(width,height);
    }
}
