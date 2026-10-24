package com.rezoxnemesis.videostudio;

import androidx.media3.common.C;
import androidx.media3.common.OverlaySettings;
import androidx.media3.common.VideoCompositorSettings;
import androidx.media3.common.VideoGraph;
import androidx.media3.common.util.Size;
import androidx.media3.effect.MultipleInputVideoGraph;
import androidx.media3.effect.StaticOverlaySettings;
import androidx.media3.transformer.Composition;
import androidx.media3.transformer.CompositionPlayer;
import androidx.media3.transformer.EditedMediaItemSequence;
import org.json.JSONObject;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.annotation.LooperMode;
import org.robolectric.annotation.RealObject;
import org.robolectric.shadow.api.Shadow;
import org.robolectric.util.ReflectionHelpers.ClassParameter;
import java.util.List;
import static org.junit.Assert.*;

/**
 * UNIT ONLY: observe the factory actually requested by LiveEditPlayer at Media3's
 * public Builder seam. Build stops deliberately before any graph, GL, codec or
 * decoding work. Passing this contract proves selection, never native playback.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=33,manifest=Config.NONE,shadows={
        LiveEditPlayerGraphSelectionTest.RecordingBuilderShadow.class,
        LiveEditPlayerGraphSelectionTest.CompositionSourceShadow.class})
@LooperMode(LooperMode.Mode.PAUSED)
public class LiveEditPlayerGraphSelectionTest {
    @Before public void clearProbe(){
        RecordingBuilderShadow.selected=null;
        RecordingBuilderShadow.selections=0;
        RecordingBuilderShadow.builds=0;
        CompositionSourceShadow.override=null;
    }

    @Test public void ordinarySingleVideoRetainsTheSdkDefaultGraph()throws Exception{
        ProjectStore.Project project=StudioVideoLayerContractTest.scene();
        captureSelection(project,1);
        assertSdkDefault();
    }

    @Test public void stillWithIndependentMusicDoesNotForceAMultiVideoGraph()throws Exception{
        ProjectStore.Project project=StudioVideoLayerContractTest.scene();
        project.asset("base").mime="image/jpeg";
        project.asset("base").uri="file:///not-decoded-still.jpg";
        project.asset("base").durationMs=0;
        StudioLayer music=new StudioLayer();music.id="music";music.type=StudioLayer.AUDIO;
        music.assetId="sound";music.startMs=500;music.sourceInMs=1000;music.durationMs=2000;
        project.studioLayers.add(music);
        Composition composition=captureSelection(project,1);
        assertEquals(2,composition.sequences.size());
        assertSdkDefault();
    }

    @Test public void finalImageAndTitleEffectsKeepTheSingleVideoDefault()throws Exception{
        ProjectStore.Project project=StudioVideoLayerContractTest.scene();
        StudioLayer image=new StudioLayer();image.id="image";image.type=StudioLayer.IMAGE;
        image.assetId="still";image.durationMs=2000;project.studioLayers.add(image);
        StudioLayer title=new StudioLayer();title.id="title";title.type=StudioLayer.TITLE;
        title.text="Existing final composition title";title.durationMs=2000;project.studioLayers.add(title);
        Composition composition=captureSelection(project,1);
        assertFalse(composition.effects.videoEffects.isEmpty());
        assertSame(VideoCompositorSettings.DEFAULT,composition.videoCompositorSettings);
        assertSdkDefault();
    }

    @Test public void oneSilentVideoOverlayRequestsTheActualMultiInputFactory()throws Exception{
        ProjectStore.Project project=StudioVideoLayerContractTest.scene();
        project.studioLayers.add(StudioVideoLayerContractTest.parse(project,
                StudioVideoLayerContractTest.video("panel")));
        Composition composition=captureSelection(project,2);
        assertNotSame(VideoCompositorSettings.DEFAULT,composition.videoCompositorSettings);
        assertMultiInput();
    }

    @Test public void twoIndependentVideoPanelsAndMusicStillRequestOneMultiInputFactory()throws Exception{
        ProjectStore.Project project=StudioVideoLayerContractTest.scene();
        project.studioLayers.add(StudioVideoLayerContractTest.parse(project,
                StudioVideoLayerContractTest.video("panel-a")));
        project.studioLayers.add(StudioVideoLayerContractTest.parse(project,
                StudioVideoLayerContractTest.video("panel-b").put("startMs",4000)
                        .put("durationMs",1000).put("sourceInMs",0)));
        StudioLayer music=new StudioLayer();music.id="music";music.type=StudioLayer.AUDIO;
        music.assetId="sound";music.durationMs=1000;project.studioLayers.add(music);
        Composition composition=captureSelection(project,3);
        assertEquals(4,composition.sequences.size());
        assertMultiInput();
    }

    @Test public void existingTwoPlateImagesNeedTheMultiInputFactoryToo()throws Exception{
        captureSelection(layered(false),2);
        assertMultiInput();
    }

    @Test public void existingFourPlateArticulatedImagesNeedTheMultiInputFactoryToo()throws Exception{
        captureSelection(layered(true),4);
        assertMultiInput();
    }

    @Test public void customCompositorSettingsNeedMultiInputEvenWithOneVideoSequence()throws Exception{
        ProjectStore.Project project=StudioVideoLayerContractTest.scene();
        Composition ordinary=composition(project);
        CompositionSourceShadow.override=new Composition.Builder(ordinary.sequences)
                .setVideoCompositorSettings(new VideoCompositorSettings(){
                    @Override public Size getOutputSize(List<Size> sizes){return sizes.get(0);}
                    @Override public OverlaySettings getOverlaySettings(int input,long timeUs){
                        return new StaticOverlaySettings.Builder().setAlphaScale(.75f).build();
                    }
                }).build();
        Composition composition=captureSelection(project,1);
        assertNotSame(VideoCompositorSettings.DEFAULT,composition.videoCompositorSettings);
        assertMultiInput();
    }

    @Test public void unavailableSourceDurationStillFailsBeforeAnyGraphSelection()throws Exception{
        ProjectStore.Project project=StudioVideoLayerContractTest.scene();
        project.asset("base").durationMs=0;
        String before=SketchDocument.canonical(project.toJson());
        LiveEditPlayer live=new LiveEditPlayer(RuntimeEnvironment.getApplication());
        try{
            IllegalArgumentException failure=assertThrows(IllegalArgumentException.class,
                    ()->live.playEdit(project,new NativeRenderEngine(RuntimeEnvironment.getApplication()),
                            "9:16","720p",0,false,null,null));
            assertTrue(failure.getMessage().contains("duration"));
            assertEquals(0,RecordingBuilderShadow.selections);
            assertEquals(0,RecordingBuilderShadow.builds);
            assertEquals(before,SketchDocument.canonical(project.toJson()));
        }finally{live.release();}
    }

    private static Composition captureSelection(ProjectStore.Project project,int expectedVideos)throws Exception{
        String before=SketchDocument.canonical(project.toJson());
        Composition composition=composition(project);
        int videos=0;
        for(EditedMediaItemSequence sequence:composition.sequences)
            if(sequence.trackTypes.contains(C.TRACK_TYPE_VIDEO))videos++;
        assertEquals("The scenario must contain the stated number of actual video inputs",expectedVideos,videos);
        LiveEditPlayer live=new LiveEditPlayer(RuntimeEnvironment.getApplication());
        try{
            assertThrows(ProbeComplete.class,()->live.playEdit(project,
                    new NativeRenderEngine(RuntimeEnvironment.getApplication()),
                    "9:16","720p",0,false,null,null));
            assertEquals("Observe the builder actually used by the ordinary preview entry point",1,RecordingBuilderShadow.builds);
            assertFalse("The unit probe never admits or renders a player",live.isEditedPreview());
            assertEquals("Choosing a graph must not edit assets, clips, keys or revision",before,
                    SketchDocument.canonical(project.toJson()));
            return composition;
        }finally{live.release();}
    }

    private static void assertSdkDefault(){
        assertEquals("A single video input must retain the existing SDK-default path",0,RecordingBuilderShadow.selections);
        assertNull(RecordingBuilderShadow.selected);
    }

    private static void assertMultiInput(){
        assertEquals("Configure the real native multi-input backend once before build",1,RecordingBuilderShadow.selections);
        assertNotNull("The single-input SDK default rejects multiple videos/custom compositor settings",
                RecordingBuilderShadow.selected);
        assertTrue("Use the actual pinned Media3 implementation, not a fake graph or texture substitute",
                RecordingBuilderShadow.selected instanceof MultipleInputVideoGraph.Factory);
        assertTrue(RecordingBuilderShadow.selected.supportsMultipleInputs());
    }

    private static Composition composition(ProjectStore.Project project)throws Exception{
        return new NativeRenderEngine(RuntimeEnvironment.getApplication())
                .buildPreviewComposition(project,"9:16","720p");
    }

    private static ProjectStore.Project layered(boolean articulated)throws Exception{
        ProjectStore.Project project=StudioVideoLayerContractTest.scene();
        project.asset("base").mime="image/png";
        for(ProjectStore.Clip clip:project.clips){
            clip.effects=new JSONObject().put("animatedScene",true)
                    .put("foregroundUri","file:///not-decoded-foreground.png")
                    .put("backgroundUri","file:///not-decoded-background.png")
                    .put("animationSpec",new JSONObject());
            if(articulated)clip.effects.put("headUri","file:///not-decoded-head.png")
                    .put("torsoUri","file:///not-decoded-torso.png")
                    .put("lowerUri","file:///not-decoded-lower.png");
        }
        return project;
    }

    /** Records only ordinary product Builder calls, then prevents all GPU work. */
    @Implements(value=CompositionPlayer.Builder.class,isInAndroidSdk=false)
    public static class RecordingBuilderShadow {
        @RealObject private CompositionPlayer.Builder builder;
        static VideoGraph.Factory selected;
        static int selections,builds;
        @Implementation public CompositionPlayer.Builder setVideoGraphFactory(VideoGraph.Factory factory){
            selected=factory;selections++;
            return Shadow.directlyOn(builder,CompositionPlayer.Builder.class,"setVideoGraphFactory",
                    ClassParameter.from(VideoGraph.Factory.class,factory));
        }
        @Implementation public CompositionPlayer build(){builds++;throw new ProbeComplete();}
    }

    /** Only the custom-settings case supplies an already-built public Composition. */
    @Implements(value=NativeRenderEngine.class,isInAndroidSdk=false)
    public static class CompositionSourceShadow {
        @RealObject private NativeRenderEngine engine;
        static Composition override;
        @Implementation public Composition buildPreviewComposition(ProjectStore.Project project,
                String aspect,String quality)throws Exception{
            if(override!=null)return override;
            return Shadow.directlyOn(engine,NativeRenderEngine.class,"buildPreviewComposition",
                    ClassParameter.from(ProjectStore.Project.class,project),
                    ClassParameter.from(String.class,aspect),ClassParameter.from(String.class,quality));
        }
    }

    private static final class ProbeComplete extends RuntimeException {}
}
