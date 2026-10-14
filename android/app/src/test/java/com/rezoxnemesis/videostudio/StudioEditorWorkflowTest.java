package com.rezoxnemesis.videostudio;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=33,manifest=Config.NONE)
public class StudioEditorWorkflowTest {
    private static ProjectStore.Project project(ProjectStore store){
        ProjectStore.Project p=store.create("Human edit workflow");
        ProjectStore.Asset source=new ProjectStore.Asset();source.id="owned-source";source.uri="file:///unused.mp4";source.mime="video/mp4";source.durationMs=8000;p.assets.add(source);
        ProjectStore.Clip clip=new ProjectStore.Clip();clip.id="first";clip.assetId=source.id;clip.inMs=1000;clip.outMs=7000;clip.speed=2;p.clips.add(clip);store.save(p);return p;
    }
    private static void settings(ProjectStore store,ProjectStore.Project project,JSONObject set)throws Exception{
        StudioPrecisionEdits.apply(RuntimeEnvironment.getApplication(),store,new JSONObject().put("projectId",project.id).put("expectedUpdatedAt",project.updatedAt)
                .put("operations",new JSONArray().put(new JSONObject().put("clipId",project.clips.get(0).id).put("set",set))));
    }
    @Test public void exactPlayheadSplitKeepsSourceIdentityAndUndoRedoRestoresTheWholeEdit()throws Exception{
        ProjectStore store=new ProjectStore(RuntimeEnvironment.getApplication());ProjectStore.Project before=project(store);
        StudioEditHistory history=new StudioEditHistory();history.checkpoint(before);
        long source=StudioEditCursor.sourceAt(1000,7000,2,0,1250);
        StudioPrecisionEdits.applyTimeline(store,new JSONObject().put("projectId",before.id).put("expectedUpdatedAt",before.updatedAt)
                .put("action","split").put("clipId","first").put("sourceTimeMs",source));
        ProjectStore.Project split=store.get(before.id);
        assertEquals(3500,split.clips.get(0).outMs);assertEquals(3500,split.clips.get(1).inMs);
        assertEquals(3000,split.outputDurationMs());assertEquals("owned-source",split.clips.get(1).assetId);
        ProjectStore.Project restored=history.undo(split);assertEquals(1,restored.clips.size());assertEquals(7000,restored.clips.get(0).outMs);
        ProjectStore.Project redone=history.redo(restored);assertEquals(2,redone.clips.size());assertEquals("file:///unused.mp4",redone.assets.get(0).uri);
    }
    @Test public void splitPreservesContinuousAudioFadeAndAllowsExplicitLocalReauthoring()throws Exception{
        ProjectStore store=new ProjectStore(RuntimeEnvironment.getApplication());ProjectStore.Project p=project(store);
        settings(store,p,new JSONObject().put("audioFadeInMs",2000).put("audioFadeOutMs",1000));p=store.get(p.id);
        StudioPrecisionEdits.applyTimeline(store,new JSONObject().put("projectId",p.id).put("expectedUpdatedAt",p.updatedAt)
                .put("action","split").put("clipId","first").put("sourceTimeMs",4000));
        ProjectStore.Project split=store.get(p.id);ProjectStore.Clip a=split.clips.get(0),b=split.clips.get(1);
        float ending=StudioClipAudioEnvelope.fromEffects(a.effects,a.outputDurationMs(),a.speed,a.volume).gainAtOutputTimeUs(1500000);
        float beginning=StudioClipAudioEnvelope.fromEffects(b.effects,b.outputDurationMs(),b.speed,b.volume).gainAtOutputTimeUs(0);
        assertEquals(ending,beginning,.00001);assertEquals(.75,beginning,.00001);
        settings(store,split,new JSONObject().put("audioFadeInMs",300).put("audioFadeOutMs",200));
        assertFalse(store.get(p.id).clips.get(0).effects.has("studioAudioOffsetMs"));
    }
    @Test public void invalidFadeRejectsGainAndFadeTogetherWithoutAnyPartialSave()throws Exception{
        ProjectStore store=new ProjectStore(RuntimeEnvironment.getApplication());ProjectStore.Project p=project(store);long revision=p.updatedAt;
        try{settings(store,p,new JSONObject().put("volume",.25).put("audioFadeInMs",3001));fail("Fade exceeding edited clip accepted");}
        catch(IllegalArgumentException expected){}
        ProjectStore.Project unchanged=store.get(p.id);assertEquals(revision,unchanged.updatedAt);assertEquals(1,unchanged.clips.get(0).volume,0);
        assertFalse(unchanged.clips.get(0).effects.has("audioFadeInMs"));
    }
    @Test public void volumeDialogEditsPreserveSplitAudioClockEvenWhenAuthoredFadeExceedsSegment()throws Exception{
        for(long splitAt:new long[]{5000,9500}){
            ProjectStore store=new ProjectStore(RuntimeEnvironment.getApplication());ProjectStore.Project p=project(store);
            p.assets.get(0).durationMs=12000;ProjectStore.Clip original=p.clips.get(0);original.outMs=11000;original.speed=1;store.save(p);
            settings(store,p,new JSONObject().put("audioFadeInMs",2000).put("audioFadeOutMs",2000));p=store.get(p.id);
            StudioPrecisionEdits.applyTimeline(store,new JSONObject().put("projectId",p.id).put("expectedUpdatedAt",p.updatedAt).put("action","split").put("clipId","first").put("sourceTimeMs",1000+splitAt));
            ProjectStore.Project split=store.get(p.id);ProjectStore.Clip right=split.clips.get(1);
            float before=StudioClipAudioEnvelope.fromEffects(right.effects,right.outputDurationMs(),right.speed,right.volume).gainAtOutputTimeUs(0);
            JSONObject set=StudioVolumeEdits.settings(50,right.outputDurationMs(),2000,2000,2000,2000);
            StudioPrecisionEdits.apply(RuntimeEnvironment.getApplication(),store,new JSONObject().put("projectId",split.id).put("expectedUpdatedAt",split.updatedAt)
                    .put("operations",new JSONArray().put(new JSONObject().put("clipId",right.id).put("set",set))));
            ProjectStore.Clip changed=store.get(p.id).clips.get(1);
            assertEquals(splitAt,changed.effects.getLong("studioAudioOffsetMs"));assertEquals(10000,changed.effects.getLong("studioAudioDurationMs"));
            assertEquals(before*.5,StudioClipAudioEnvelope.fromEffects(changed.effects,changed.outputDurationMs(),changed.speed,changed.volume).gainAtOutputTimeUs(0),.00001);
        }
    }
}
