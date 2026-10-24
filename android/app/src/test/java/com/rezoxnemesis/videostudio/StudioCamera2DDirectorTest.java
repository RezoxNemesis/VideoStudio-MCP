package com.rezoxnemesis.videostudio;

import android.graphics.Matrix;
import androidx.media3.common.Effect;
import androidx.media3.transformer.Composition;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

/**
 * Media3-level proof of native whole-composition camera admission and
 * exact transform sampling. These tests cannot certify actual phone GPU output.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=33,manifest=Config.NONE)
public class StudioCamera2DDirectorTest {
    private ProjectStore.Project sample(ProjectStore store) {
        ProjectStore.Project project=store.create("Source with camera");
        ProjectStore.Asset image=new ProjectStore.Asset();
        image.id="original-image";image.name="Reference.png";
        image.mime="image/png";image.uri="file:///owner/unchanged.png";
        project.assets.add(image);
        ProjectStore.Asset music=new ProjectStore.Asset();
        music.id="original-audio";music.name="Original music";
        music.mime="audio/mp4";music.uri="file:///owner/song.mp4";
        music.durationMs=10000;project.assets.add(music);
        ProjectStore.Clip clip=new ProjectStore.Clip();
        clip.id="source-clip";clip.assetId=image.id;
        clip.inMs=0;clip.outMs=4000;project.clips.add(clip);
        StudioLayer audio=new StudioLayer();
        audio.id="owned-audio";audio.type=StudioLayer.AUDIO;
        audio.assetId=music.id;audio.startMs=0;audio.durationMs=4000;
        project.studioLayers.add(audio);
        StudioLayer title=new StudioLayer();
        title.id="original-title";title.type=StudioLayer.TITLE;
        title.text="PAN TO THE END";title.startMs=0;title.durationMs=4000;
        project.studioLayers.add(title);
        store.save(project);return store.get(project.id);
    }

    private JSONArray cameraKeys()throws Exception {
        return new JSONArray()
                .put(new JSONObject().put("timeMs",0).put("x",0.0)
                        .put("scale",1.0).put("rotate",0.0).put("easing","linear"))
                .put(new JSONObject().put("timeMs",4000).put("x",0.1)
                        .put("scale",1.2).put("rotate",0.0));
    }
    private JSONObject set(ProjectStore.Project project)throws Exception {
        return new JSONObject().put("projectId",project.id)
                .put("expectedUpdatedAt",project.updatedAt)
                .put("operation","set").put("motionKeyframes",cameraKeys());
    }

    @Test public void cameraPlanDoesNotEditAssetsOrSourceProject()throws Exception{
        try(ProjectStore store=new ProjectStore(RuntimeEnvironment.getApplication())){
            ProjectStore.Project source=sample(store);
            String before=SketchDocument.canonical(source.toJson());
            JSONObject plan=StudioCamera2DDirector.plan(source,set(source));
            assertTrue(plan.getBoolean("ok"));
            assertTrue(plan.getBoolean("dryRun"));
            assertFalse(plan.getBoolean("saved"));
            assertEquals(1,plan.getInt("cameraTracksAfter"));
            assertEquals(before,SketchDocument.canonical(store.get(source.id).toJson()));
        }
    }

    @Test public void entireComposedSceneGetsOneRealCameraEffectAfterOverlays()throws Exception{
        try(ProjectStore store=new ProjectStore(RuntimeEnvironment.getApplication())){
            ProjectStore.Project source=sample(store);
            JSONObject changed=StudioCamera2DDirector.apply(store,set(source));
            assertTrue(changed.getBoolean("saved"));
            assertFalse(changed.getBoolean("previewVerified"));
            ProjectStore.Project output=store.get(source.id);
            assertEquals("file:///owner/song.mp4",output.asset("original-audio").uri);
            assertEquals("file:///owner/unchanged.png",output.asset("original-image").uri);
            assertEquals(1,output.clips.size());
            assertEquals(3,output.studioLayers.size());
            Composition assembled=new NativeRenderEngine(RuntimeEnvironment.getApplication())
                    .buildPreviewComposition(output,"9:16","720p");
            assertEquals(2,assembled.effects.videoEffects.size());
            assertTrue(assembled.effects.videoEffects.get(0) instanceof StudioCompositionOverlayEffect);
            Effect camera=assembled.effects.videoEffects.get(1);
            assertTrue(camera instanceof StudioClipKeyframes);
            Matrix matrix=((StudioClipKeyframes)camera).getMatrix(2_000_000L);
            float[] values=new float[9];matrix.getValues(values);
            assertEquals(1.1f,values[Matrix.MSCALE_X],.01f);
            assertEquals(.1f,values[Matrix.MTRANS_X],.01f);
            float[] beginning=new float[9];
            ((StudioClipKeyframes)camera).getMatrix(0).getValues(beginning);
            assertEquals(1f,beginning[Matrix.MSCALE_X],.01f);
        }
    }

    @Test public void cameraHoldsLastKeyWhenUserExtendsTimelineWithoutDeletingMedia()throws Exception{
        try(ProjectStore store=new ProjectStore(RuntimeEnvironment.getApplication())){
            ProjectStore.Project source=sample(store);
            StudioCamera2DDirector.apply(store,set(source));
            ProjectStore.Project extended=store.get(source.id);
            extended.clips.get(0).outMs=4800;
            store.save(extended);
            ProjectStore.Project saved=store.get(source.id);
            // The camera has an authored 4s curve, but safe clip edits can
            // extend the composition to 4.8s; the transform holds its end.
            assertEquals(4800,saved.outputDurationMs());
            StudioLayerContract.fromJson(saved,StudioLayerContract.toJson(saved.studioLayers));
            Composition composition=new NativeRenderEngine(RuntimeEnvironment.getApplication())
                    .buildPreviewComposition(saved,"9:16","720p");
            assertEquals(2,composition.effects.videoEffects.size());
            StudioClipKeyframes camera=(StudioClipKeyframes)composition.effects.videoEffects.get(1);
            float[] end=new float[9];
            camera.getMatrix(4_800_000L).getValues(end);
            assertEquals(1.2f,end[Matrix.MSCALE_X],.01f);
            assertEquals(.2f,end[Matrix.MTRANS_X],.01f);
            assertEquals("file:///owner/song.mp4",saved.asset("original-audio").uri);
        }
    }

    @Test public void clearAndRemoveCameraDoNotDeleteImportedMedia()throws Exception{
        try(ProjectStore store=new ProjectStore(RuntimeEnvironment.getApplication())){
            ProjectStore.Project source=sample(store);
            StudioCamera2DDirector.apply(store,set(source));
            ProjectStore.Project afterSet=store.get(source.id);
            JSONObject clear=new JSONObject().put("projectId",source.id)
                    .put("expectedUpdatedAt",afterSet.updatedAt).put("operation","clear");
            JSONObject cleared=StudioCamera2DDirector.apply(store,clear);
            assertEquals(0,cleared.getInt("keyframesAfter"));
            ProjectStore.Project afterClear=store.get(source.id);
            JSONObject remove=new JSONObject().put("projectId",source.id)
                    .put("expectedUpdatedAt",afterClear.updatedAt).put("operation","remove");
            JSONObject removed=StudioCamera2DDirector.apply(store,remove);
            assertEquals(0,removed.getInt("cameraTracksAfter"));
            assertEquals(0,removed.getInt("keyframesAfter"));
            ProjectStore.Project finalProject=store.get(source.id);
            assertEquals(2,finalProject.studioLayers.size());
            assertEquals("file:///owner/song.mp4",finalProject.asset("original-audio").uri);
        }
    }

    @Test public void refusesUnrealOpacityOrAnUnownedSourceReference()throws Exception{
        try(ProjectStore store=new ProjectStore(RuntimeEnvironment.getApplication())){
            ProjectStore.Project source=sample(store);
            JSONArray badKeys=new JSONArray()
                    .put(new JSONObject().put("timeMs",0).put("opacity",.3))
                    .put(new JSONObject().put("timeMs",4000).put("x",.2));
            JSONObject request=set(source).put("motionKeyframes",badKeys);
            assertThrows(IllegalArgumentException.class,()->
                    StudioCamera2DDirector.plan(source,request));
            JSONObject forged=new JSONObject().put("type",StudioLayer.CAMERA)
                    .put("durationMs",4000).put("uri","file:///outside/source.mp4");
            assertThrows(IllegalArgumentException.class,()->StudioLayer.fromJson(forged));
        }
    }

    @Test public void noDuplicateCameraOrStaleHumanRevisionCanBeSaved()throws Exception {
        try(ProjectStore store=new ProjectStore(RuntimeEnvironment.getApplication())){
            ProjectStore.Project source=sample(store);
            JSONObject first=set(source);
            StudioCamera2DDirector.apply(store,first);
            String before=SketchDocument.canonical(store.get(source.id).toJson());
            assertThrows(IllegalStateException.class,()->
                    StudioCamera2DDirector.apply(store,first));
            assertEquals(before,SketchDocument.canonical(store.get(source.id).toJson()));
            ProjectStore.Project output=store.get(source.id);
            StudioLayer clone=null;
            for(StudioLayer layer:output.studioLayers)if(StudioLayer.CAMERA.equals(layer.type))
                clone=layer.copy();
            assertNotNull(clone);
            clone.id="second-camera";
            output.studioLayers.add(clone);
            assertThrows(IllegalArgumentException.class,()->
                    StudioLayerContract.fromJson(output,StudioLayerContract.toJson(output.studioLayers)));
        }
    }
}
