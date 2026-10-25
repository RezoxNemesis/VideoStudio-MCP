package com.rezoxnemesis.videostudio;

import android.text.SpannableString;
import androidx.media3.effect.TextureOverlay;
import androidx.media3.effect.TextOverlay;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import java.util.List;
import static org.junit.Assert.*;

/** First failure was a 36.54s 12-clip edit with 28 TITLE rows + music + group.
 * This test asserts the native *Composition* needs one text shader texture
 * instead of compiling all 28 into one GPU shader.
 * Android on-device rendered-frame acceptance remains a separate gate.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=33,manifest=Config.NONE)
public class StudioVirtualizedTitleOverlayTest {
    private static StudioLayer title(String text,long startMs,long durationMs){
        StudioLayer t=new StudioLayer();
        t.type=StudioLayer.TITLE;t.text=text;t.startMs=startMs;t.durationMs=durationMs;
        t.fontSize=46;t.fontStyle="bold";t.color=0xffffeabb;
        t.validate();return t;
    }

    @Test public void twentyEightSequentialTitleRowsUseOneGPUTextureWithOriginalFrameClock(){
        try(ProjectStore store=new ProjectStore(RuntimeEnvironment.getApplication())){
            ProjectStore.Project project=store.create("36.54s edited preview source");
            for(int i=0;i<28;i++)project.studioLayers.add(title("CUE "+i,i*1200L,1200));
            StudioCompositionOverlayEffect effect=new StudioCompositionOverlayEffect(
                    RuntimeEnvironment.getApplication(),project);
            List<TextureOverlay> textures=effect.createOverlays();
            assertEquals("28 scheduled captions should occupy one actual Media3 texture",1,textures.size());
            assertTrue(textures.get(0) instanceof StudioVirtualizedTitleOverlay);
            StudioVirtualizedTitleOverlay virtual=(StudioVirtualizedTitleOverlay)textures.get(0);
            assertEquals(28,virtual.logicalTitleCount());
            for(int i=0;i<28;i++){
                long at=i*1_200_000L+550_000L;
                assertEquals("CUE "+i,virtual.getText(at).toString());
                assertEquals(1f,virtual.getOverlaySettings(at).getAlphaScale(),.00001f);
            }
            assertEquals(0f,virtual.getOverlaySettings(35_000_000L).getAlphaScale(),0f);
            assertEquals(" ",virtual.getText(35_000_000L).toString());
            assertEquals("CUE 1",virtual.getText(1_200_000L).toString());
            assertEquals("CUE 0",virtual.getText(1_199_999L).toString());
        }
    }

    @Test public void twoTitleRowsRemainOrdinaryLegacyRenderWithoutChangingFormatting(){
        StudioLayer first=title("First",0,1500),second=title("Second",1500,1500);
        StudioTimedTitleOverlay existing=new StudioTimedTitleOverlay(first);
        assertEquals("First",existing.getText(500_000L).toString());
        assertEquals(1f,existing.getOverlaySettings(500_000L).getAlphaScale(),0f);
        try(ProjectStore store=new ProjectStore(RuntimeEnvironment.getApplication())){
            ProjectStore.Project p=store.create("Old project");
            p.studioLayers.add(first);p.studioLayers.add(second);
            List<TextureOverlay> textures=new StudioCompositionOverlayEffect(
                    RuntimeEnvironment.getApplication(),p).createOverlays();
            assertEquals(2,textures.size());
            assertTrue(textures.get(0) instanceof StudioTimedTitleOverlay);
        }
    }

    @Test public void overlappingTitlesAreNotMergedOrReordered(){
        StudioLayer first=title("under",0,3000);
        StudioLayer second=title("over",2000,2800);
        try(ProjectStore store=new ProjectStore(RuntimeEnvironment.getApplication())){
            ProjectStore.Project p=store.create("Overlapping captions");
            p.studioLayers.add(first);p.studioLayers.add(second);
            List<TextureOverlay> x=new StudioCompositionOverlayEffect(
                    RuntimeEnvironment.getApplication(),p).createOverlays();
            assertEquals(2,x.size());
            assertEquals("under",((TextOverlay)x.get(0)).getText(2500000L).toString());
            assertEquals("over",((TextOverlay)x.get(1)).getText(2500000L).toString());
        }
    }

    @Test public void shapeInterleavingKeepsActualZOrder() throws Exception {
        try(ProjectStore store=new ProjectStore(RuntimeEnvironment.getApplication())){
            ProjectStore.Project p=store.create("Interleaved track");
            for(int i=0;i<4;i++)p.studioLayers.add(title("before "+i,i*1000,1000));
            StudioLayer ring=StudioShapePresets.create("ring","hold",0,8500);
            p.studioLayers.add(ring);
            for(int i=0;i<4;i++)p.studioLayers.add(title("after "+i,4000+i*1000,1000));
            List<TextureOverlay> x=new StudioCompositionOverlayEffect(
                    RuntimeEnvironment.getApplication(),p).createOverlays();
            assertEquals(3,x.size());
            assertTrue(x.get(0) instanceof StudioVirtualizedTitleOverlay);
            assertTrue(x.get(1) instanceof StudioTimedShapeOverlay);
            assertTrue(x.get(2) instanceof StudioVirtualizedTitleOverlay);
        }
    }

    @Test public void linkedGroupRetainsNativeParentTransformAcrossVirtualizedTitles()throws Exception{
        try(ProjectStore store=new ProjectStore(RuntimeEnvironment.getApplication())){
            ProjectStore.Project p=store.create("Grouped titles");
            StudioLayer parent=new StudioLayer();
            parent.type=StudioLayer.GROUP;parent.id="group-motion";parent.startMs=0;parent.durationMs=5000;
            parent.motionKeyframes=new JSONArray()
                    .put(new JSONObject().put("timeMs",0).put("x",0).put("opacity",1))
                    .put(new JSONObject().put("timeMs",4900).put("x",.12).put("opacity",1));
            parent.validate();
            for(int i=0;i<4;i++){
                StudioLayer t=title("linked "+i,i*1200,1200);
                t.parentId=parent.id;p.studioLayers.add(t);
            }
            p.studioLayers.add(parent);
            List<TextureOverlay> x=new StudioCompositionOverlayEffect(
                    RuntimeEnvironment.getApplication(),p).createOverlays();
            assertEquals(1,x.size());
            TextOverlay virtual=(TextOverlay)x.get(0);
            assertEquals("linked 2",virtual.getText(2_600_000).toString());
            assertTrue("Parent group must still change frame anchor",
                    virtual.getOverlaySettings(4_200_000L).getBackgroundFrameAnchor().first
                    >virtual.getOverlaySettings(200_000L).getBackgroundFrameAnchor().first);
        }
    }

    @Test public void overlappingVisualEffectsAreSplitIntoSixSamplerShaderPasses()throws Exception{
        try(ProjectStore store=new ProjectStore(RuntimeEnvironment.getApplication())){
            ProjectStore.Project project=store.create("12 overlay graphics");
            for(int i=0;i<12;i++){
                StudioLayer effect=StudioShapePresets.create("ring","hold",0,3000);
                effect.id="shape-"+i;project.studioLayers.add(effect);
            }
            StudioCompositionOverlayEffect composer=new StudioCompositionOverlayEffect(
                    RuntimeEnvironment.getApplication(),project);
            assertEquals(java.util.Arrays.asList(6,6),composer.plannedPassSizes());
            assertEquals(2,composer.batchedEffects().size());
            assertEquals(12,composer.createOverlays().size());
        }
    }

    @Test public void dozensOfIndependentAnimatedLettersAreNeverPackedIntoOneShader()throws Exception{
        try(ProjectStore store=new ProjectStore(RuntimeEnvironment.getApplication())){
            ProjectStore.Project project=store.create("40 independently keyed letters");
            for(int i=0;i<4;i++){
                StudioLayer word=title("ABCDEFGHIJ",i*4000L,4000);
                word.glyphAnimation=new JSONObject().put("mode","scatter");
                word.validate();project.studioLayers.add(word);
            }
            StudioCompositionOverlayEffect composer=new StudioCompositionOverlayEffect(
                    RuntimeEnvironment.getApplication(),project);
            assertEquals(java.util.Arrays.asList(6,6,6,6,6,6,4),composer.plannedPassSizes());
            assertEquals(7,composer.batchedEffects().size());
        }
    }

    @Test public void virtualizerRefusesOverlappingOrGlyphProgrammedRows() throws Exception {
        StudioLayer first=title("first",0,2500);
        StudioLayer second=title("second",2000,2300);
        assertThrows(IllegalArgumentException.class,()->new StudioVirtualizedTitleOverlay(
                java.util.Arrays.asList(first,second),java.util.Arrays.asList(null,null)));
        StudioLayer glyph=title("TEXT",2800,1600);
        glyph.glyphAnimation=new JSONObject().put("mode","assemble");
        assertThrows(IllegalArgumentException.class,()->new StudioVirtualizedTitleOverlay(
                java.util.Arrays.asList(first,glyph),java.util.Arrays.asList(null,null)));
    }
}
