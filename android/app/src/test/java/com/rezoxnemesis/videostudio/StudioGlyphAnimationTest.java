package com.rezoxnemesis.videostudio;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import androidx.media3.effect.TextureOverlay;
import androidx.media3.effect.TextOverlay;
import java.util.List;
import static org.junit.Assert.*;

/** Real native layer and Media3 timing tests, not claims of visual reference parity. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=33,manifest=Config.NONE)
public class StudioGlyphAnimationTest {
    private JSONObject animation(String mode) throws Exception {
        return new JSONObject().put("mode",mode);
    }

    private StudioLayer glyphTitle(String text) throws Exception {
        StudioLayer title=new StudioLayer();
        title.type=StudioLayer.TITLE;
        title.text=text;
        title.startMs=1000;
        title.durationMs=2200;
        title.fontSize=54;
        title.glyphAnimation=animation("scatter");
        title.validate();
        return title;
    }

    @Test public void perCodepointSamplesAreDistinctDeterministicAndClockBounded() throws Exception {
        JSONObject mode=animation("scatter");
        StudioGlyphAnimation.Spec spec=StudioGlyphAnimation.compile(mode,"EVOLVE",2200);
        StudioGlyphAnimation.Frame a=StudioGlyphAnimation.sample(spec,0,590);
        StudioGlyphAnimation.Frame again=StudioGlyphAnimation.sample(spec,0,590);
        StudioGlyphAnimation.Frame b=StudioGlyphAnimation.sample(spec,3,590);
        assertEquals(a.x,again.x,0f);
        assertEquals(a.y,again.y,0f);
        assertEquals(a.opacity,again.opacity,0f);
        assertNotEquals(a.x,b.x,0.0001f);
        assertEquals(0f,StudioGlyphAnimation.sample(spec,0,-1).opacity,0f);
        assertTrue(StudioGlyphAnimation.sample(spec,0,850).opacity>.9f);
        assertEquals(0f,StudioGlyphAnimation.sample(spec,0,2200).opacity,0f);
        assertTrue(StudioGlyphAnimation.sample(spec,3,1120).opacity>.9f);
    }

    @Test public void allFourModesMoveLettersIndependently() throws Exception {
        for(String mode:new String[]{"assemble","scatter","cascade","wave"}){
            StudioGlyphAnimation.Spec spec=StudioGlyphAnimation.compile(animation(mode),"NOW",2500);
            StudioGlyphAnimation.Frame early=StudioGlyphAnimation.sample(spec,1,125);
            StudioGlyphAnimation.Frame middle=StudioGlyphAnimation.sample(spec,1,1150);
            assertNotEquals(mode+" has motion",early.y+early.x,middle.y+middle.x,0.001f);
            assertTrue(middle.opacity>.9f);
        }
    }

    @Test public void punctuationAndSupplementaryUnicodeKeepStableGlyphBoundaries() {
        assertEquals(4,StudioGlyphAnimation.glyphCount("A " + "\uD83D\uDE80" + "!"));
        assertEquals("\uD83D\uDE80",StudioGlyphAnimation.glyphAt("A " + "\uD83D\uDE80" + "!",2));
        assertEquals(3,StudioGlyphAnimation.visibleGlyphCount("A " + "\uD83D\uDE80" + "!"));
    }

    @Test public void invalidParamsAndUnboundedGpuGlyphsAreRejected() throws Exception {
        assertThrows(IllegalArgumentException.class,()->StudioGlyphAnimation.validate(
                animation("scatter"),"ABCDEFGHIJKLMNOPQRS",2200));
        assertThrows(IllegalArgumentException.class,()->StudioGlyphAnimation.validate(
                animation("scatter").put("staggerMs",160),"TOO MANY GLYPHS",800));
        assertThrows(IllegalArgumentException.class,()->StudioGlyphAnimation.validate(
                animation("scatter").put("networkUrl","https://example.test"),"A",2200));
        assertThrows(IllegalArgumentException.class,()->StudioGlyphAnimation.validate(
                animation("3d_human"),"A",2200));
    }

    @Test public void oldTitlesRemainUnchangedAndOptionalProgramSurvivesRoundTrip() throws Exception {
        StudioLayer legacy=new StudioLayer();legacy.type=StudioLayer.TITLE;legacy.text="LEGACY";
        assertFalse(legacy.toJson().has("glyphAnimation"));
        StudioLayer restored=StudioLayer.fromJson(legacy.toJson());
        assertNull(restored.glyphAnimation);
        StudioLayer layered=glyphTitle("G O");
        StudioLayer parsed=StudioLayer.fromJson(layered.toJson());
        assertEquals("scatter",parsed.glyphAnimation.getString("mode"));
        assertEquals("G O",parsed.text);
        assertEquals(2200,parsed.durationMs);
        assertThrows(IllegalArgumentException.class,()->{
            StudioLayer invalid=glyphTitle("FAIL");
            invalid.backgroundColor=0xff123456;invalid.validate();
        });
        assertThrows(IllegalArgumentException.class,()->StudioLayer.fromJson(
                new JSONObject().put("type","image").put("glyphAnimation",animation("wave"))));
    }

    @Test public void media3ReallyGetsIndependentTimedOverlayObjects() throws Exception {
        StudioLayer title=glyphTitle("GO NOW");
        List<TextureOverlay> overlays=StudioTimedGlyphOverlay.expand(title,null);
        assertEquals(5,overlays.size()); // blank is spacing, not a GPU texture
        TextOverlay first=(TextOverlay)overlays.get(0);
        TextOverlay second=(TextOverlay)overlays.get(1);
        assertEquals("G",first.getText(1_600_000L).toString());
        assertEquals("O",second.getText(1_600_000L).toString());
        assertEquals(0f,first.getOverlaySettings(999_999L).getAlphaScale(),0f);
        assertTrue(first.getOverlaySettings(1_850_000L).getAlphaScale()>.9f);
        assertEquals(0f,first.getOverlaySettings(3_200_000L).getAlphaScale(),0f);
        assertNotEquals(first.getOverlaySettings(1_300_000L).getBackgroundFrameAnchor().first,
                second.getOverlaySettings(1_300_000L).getBackgroundFrameAnchor().first);
    }

    @Test public void compositorRefusesUnboundedAggregateTextTextures() throws Exception {
        try(ProjectStore store=new ProjectStore(RuntimeEnvironment.getApplication())){
            ProjectStore.Project project=store.create("Bounded overlays");
            for(int i=0;i<3;i++){
                StudioLayer title=glyphTitle("ABCDEFGHIJKLMNOPQR");
                title.id="glyph-lane-"+i;
                project.studioLayers.add(title);
            }
            StudioCompositionOverlayEffect compositor=
                    new StudioCompositionOverlayEffect(RuntimeEnvironment.getApplication(),project);
            assertThrows(IllegalArgumentException.class,compositor::createOverlays);
        }
    }

    @Test public void glyphKineticApplySavesIndependentEditableCopy() throws Exception {
        try(ProjectStore store=new ProjectStore(RuntimeEnvironment.getApplication())){
            ProjectStore.Project source=store.create("Original footage");
            ProjectStore.Asset image=new ProjectStore.Asset();
            image.id="owned-still";image.name="source.png";image.mime="image/png";
            image.uri="file:///app/private/source.png";
            source.assets.add(image);
            for(int i=0;i<4;i++){
                ProjectStore.Clip clip=new ProjectStore.Clip();
                clip.id="clip-"+i;clip.assetId=image.id;clip.inMs=0;clip.outMs=5000;
                source.clips.add(clip);
            }
            store.save(source);source=store.get(source.id);
            String before=SketchDocument.canonical(source.toJson());
            JSONObject req=new JSONObject().put("projectId",source.id)
                    .put("expectedUpdatedAt",source.updatedAt)
                    .put("style","glyph").put("glyphMode","wave")
                    .put("beatMarkersMs",new JSONArray().put(0).put(5600).put(11000))
                    .put("titles",new JSONArray().put("EVOLVE").put("AGAIN"))
                    .put("operationId","glyph-director-source-01");
            JSONObject planned=StudioKineticDirector.plan(source,req);
            assertTrue(planned.getBoolean("nativePerGlyphAnimation"));
            JSONObject result=StudioKineticDirector.apply(store,req);
            assertFalse(result.getBoolean("rendered"));
            ProjectStore.Project edited=store.get(result.getString("projectId"));
            assertNotEquals(source.id,edited.id);
            assertEquals(source.clips.size(),edited.clips.size());
            assertEquals(source.assets.size(),edited.assets.size());
            assertEquals(2,edited.studioLayers.size());
            for(StudioLayer title:edited.studioLayers){
                assertNotNull(title.glyphAnimation);
                assertEquals("wave",title.glyphAnimation.getString("mode"));
                assertEquals(0,title.motionKeyframes.length());
            }
            assertEquals(before,SketchDocument.canonical(store.get(source.id).toJson()));
            assertEquals(result.getString("projectId"),
                    StudioKineticDirector.apply(store,req).getString("projectId"));
        }
    }
}
