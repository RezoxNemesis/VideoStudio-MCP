package com.rezoxnemesis.videostudio;

import android.util.Pair;
import androidx.media3.common.OverlaySettings;
import androidx.media3.effect.TextureOverlay;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import java.util.List;
import static org.junit.Assert.*;

/**
 * Directly tests the real native compositor transform and the same guarded
 * project update path used by the Android editor and authenticated MCP.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=33,manifest=Config.NONE)
public class StudioMotionGroupOpsTest {
    private ProjectStore.Project sample(ProjectStore store) {
        ProjectStore.Project project=store.create("Motion group test");
        ProjectStore.Asset still=new ProjectStore.Asset();
        still.id="original-still";still.uri="file:///original/private-owner-image.png";
        still.name="Original still";still.mime="image/png";
        project.assets.add(still);
        ProjectStore.Asset music=new ProjectStore.Asset();
        music.id="original-song";music.mime="audio/mp4";
        music.uri="file:///original/song.mp4";music.durationMs=12000;
        project.assets.add(music);
        ProjectStore.Clip clip=new ProjectStore.Clip();
        clip.id="original-clip";clip.assetId=still.id;clip.inMs=0;clip.outMs=6000;
        project.clips.add(clip);
        StudioLayer audio=new StudioLayer();
        audio.id="original-audio";audio.type=StudioLayer.AUDIO;audio.assetId=music.id;
        audio.startMs=0;audio.durationMs=6000;project.studioLayers.add(audio);
        StudioLayer title=new StudioLayer();
        title.id="owner-caption";title.type=StudioLayer.TITLE;
        title.text="MOVING TOGETHER";title.startMs=200;title.durationMs=3800;
        project.studioLayers.add(title);
        StudioLayer shape=StudioShapePresets.create("ring","hold",0,4500);
        shape.id="owner-ring";project.studioLayers.add(shape);
        store.save(project);
        return store.get(project.id);
    }

    private JSONObject create(ProjectStore.Project p)throws Exception {
        return new JSONObject().put("projectId",p.id).put("expectedUpdatedAt",p.updatedAt)
                .put("operation","create").put("operationId","linked-scene-001")
                .put("layerIds",new JSONArray().put("owner-caption").put("owner-ring"))
                .put("motionKeyframes",new JSONArray()
                        .put(new JSONObject().put("timeMs",0)
                                .put("x",0.0).put("opacity",1))
                        .put(new JSONObject().put("timeMs",4500)
                                .put("x",.5).put("opacity",1)));
    }

    @Test public void linkedGroupCreatesOnlyOneNativeTrackAndPreservesAllOriginalMedia() throws Exception{
        try(ProjectStore store=new ProjectStore(RuntimeEnvironment.getApplication())){
            ProjectStore.Project initial=sample(store);
            String originalAudio=initial.asset("original-song").uri;
            String originalStill=initial.asset("original-still").uri;
            String before=SketchDocument.canonical(initial.toJson());
            JSONObject request=create(initial);
            JSONObject plan=StudioMotionGroupOps.plan(initial,request);
            assertTrue(plan.getBoolean("dryRun"));
            assertEquals(before,SketchDocument.canonical(store.get(initial.id).toJson()));
            JSONObject receipt=StudioMotionGroupOps.apply(store,request);
            assertTrue(receipt.getBoolean("saved"));
            assertFalse(receipt.getBoolean("previewVerified"));
            ProjectStore.Project edited=store.get(initial.id);
            assertEquals(4,edited.studioLayers.size());
            assertEquals("original-audio",edited.studioLayers.get(0).id);
            assertEquals(originalAudio,edited.asset("original-song").uri);
            assertEquals(originalStill,edited.asset("original-still").uri);
            assertEquals(1,edited.clips.size());
            String id=receipt.getString("groupId");
            StudioLayer group=null,title=null,ring=null;
            for(StudioLayer layer:edited.studioLayers){
                if(id.equals(layer.id))group=layer;
                if("owner-caption".equals(layer.id))title=layer;
                if("owner-ring".equals(layer.id))ring=layer;
            }
            assertNotNull(group);assertNotNull(title);assertNotNull(ring);
            assertEquals(StudioLayer.GROUP,group.type);
            assertEquals(id,title.parentId);
            assertEquals(id,ring.parentId);
            assertEquals(0,group.startMs);
            assertEquals(4500,group.durationMs);
            assertEquals(2,group.motionKeyframes.length());
            StudioLayerContract.fromJson(edited,StudioLayerContract.toJson(edited.studioLayers));
        }
    }

    @Test public void parentTransformMovesImageShapeAndTitleWithSameGlobalTimelineClock() throws Exception{
        StudioLayer parent=new StudioLayer();
        parent.id="parent-motion";parent.type=StudioLayer.GROUP;
        parent.startMs=0;parent.durationMs=4000;
        parent.motionKeyframes=new JSONArray()
                .put(new JSONObject().put("timeMs",0).put("x",0).put("y",0).put("scale",1).put("opacity",1))
                .put(new JSONObject().put("timeMs",4000).put("x",.5).put("y",0).put("scale",1).put("opacity",1));
        parent.validate();
        StudioLayer child=new StudioLayer();
        child.type=StudioLayer.TITLE;child.text="TOGETHER";
        child.startMs=0;child.durationMs=4000;child.parentId=parent.id;
        StudioTimedTitleOverlay title=new StudioTimedTitleOverlay(child,parent);
        StudioLayer shape=StudioShapePresets.create("ring","hold",0,4000);
        shape.parentId=parent.id;
        StudioTimedShapeOverlay ring=new StudioTimedShapeOverlay(shape,parent);
        try{
            OverlaySettings titleStart=title.getOverlaySettings(0);
            OverlaySettings titleMid=title.getOverlaySettings(2_000_000L);
            OverlaySettings ringStart=ring.getOverlaySettings(0);
            OverlaySettings ringMid=ring.getOverlaySettings(2_000_000L);
            assertEquals(0,titleStart.getBackgroundFrameAnchor().first,.0001);
            assertEquals(.5,titleMid.getBackgroundFrameAnchor().first,.02);
            assertEquals(.5,ringMid.getBackgroundFrameAnchor().first-
                    ringStart.getBackgroundFrameAnchor().first,.02);
            assertTrue(titleMid.getAlphaScale()>.9f);
        }finally{ring.release();}
    }

    @Test public void sameMotionParentIsAcceptedInsideNativeComposition() throws Exception{
        try(ProjectStore store=new ProjectStore(RuntimeEnvironment.getApplication())){
            ProjectStore.Project source=sample(store);
            StudioMotionGroupOps.apply(store,create(source));
            ProjectStore.Project grouped=store.get(source.id);
            StudioCompositionOverlayEffect composition=new StudioCompositionOverlayEffect(
                    RuntimeEnvironment.getApplication(),grouped);
            List<TextureOverlay> visuals=composition.createOverlays();
            try{
                assertEquals(2,visuals.size());
                assertTrue(visuals.get(0) instanceof StudioTimedTitleOverlay);
                assertTrue(visuals.get(1) instanceof StudioTimedShapeOverlay);
            }finally{
                for(TextureOverlay overlay:visuals)overlay.release();
            }
        }
    }

    @Test public void motionGroupCanUpdateAndDissolveWithoutDeletingOwnerMedia() throws Exception{
        try(ProjectStore store=new ProjectStore(RuntimeEnvironment.getApplication())){
            ProjectStore.Project source=sample(store);
            JSONObject first=StudioMotionGroupOps.apply(store,create(source));
            String group=first.getString("groupId");
            ProjectStore.Project created=store.get(source.id);
            JSONObject animate=new JSONObject().put("projectId",source.id)
                    .put("expectedUpdatedAt",created.updatedAt)
                    .put("operation","animate").put("groupId",group)
                    .put("motionKeyframes",new JSONArray());
            JSONObject animated=StudioMotionGroupOps.apply(store,animate);
            assertEquals(0,animated.getInt("groupMotionKeys"));
            ProjectStore.Project changed=store.get(source.id);
            JSONObject dissolve=new JSONObject().put("projectId",source.id)
                    .put("expectedUpdatedAt",changed.updatedAt)
                    .put("operation","dissolve").put("groupId",group);
            JSONObject removed=StudioMotionGroupOps.apply(store,dissolve);
            assertEquals(2,removed.getInt("unlinkedLayerCount"));
            ProjectStore.Project finalProject=store.get(source.id);
            assertEquals(3,finalProject.studioLayers.size());
            for(StudioLayer l:finalProject.studioLayers)assertTrue(l.parentId.isEmpty());
            assertEquals("file:///original/song.mp4",finalProject.asset("original-song").uri);
            assertEquals("file:///original/private-owner-image.png",finalProject.asset("original-still").uri);
        }
    }

    @Test public void groupAdmissionRejectsCyclesOrphansAndIllegalSourceTypes() throws Exception {
        try(ProjectStore store=new ProjectStore(RuntimeEnvironment.getApplication())){
            ProjectStore.Project source=sample(store);
            JSONObject wrong=create(source);
            wrong.put("layerIds",new JSONArray().put("original-audio"));
            assertThrows(IllegalArgumentException.class,()->
                    StudioMotionGroupOps.plan(source,wrong));
            JSONObject forged=create(source);
            forged.put("layerIds",new JSONArray().put("unknown-layer"));
            assertThrows(IllegalArgumentException.class,()->
                    StudioMotionGroupOps.plan(source,forged));
            StudioLayer fake=new StudioLayer();fake.type=StudioLayer.SHAPE;
            fake.id="shape-2";fake.parentId="missing-parent";
            assertThrows(IllegalArgumentException.class,()->
                    StudioLayerContract.fromJson(source,new JSONArray().put(fake.toJson())));
            StudioLayer self=new StudioLayer();self.type=StudioLayer.SHAPE;
            self.parentId=self.id;
            assertThrows(IllegalArgumentException.class,self::validate);
            StudioLayer audio=new StudioLayer();audio.type=StudioLayer.AUDIO;
            audio.assetId="original-song";audio.parentId="motion-parent";
            assertThrows(IllegalArgumentException.class,audio::validate);
            assertThrows(IllegalArgumentException.class,()->
                    StudioLayer.fromJson(new JSONObject().put("type","shape")
                            .put("parentId",12)));
        }
    }

    @Test public void editingWithStaleRevisionOrReusedOperationIdNeverChangesProject() throws Exception{
        try(ProjectStore store=new ProjectStore(RuntimeEnvironment.getApplication())){
            ProjectStore.Project source=sample(store);
            JSONObject request=create(source);
            StudioMotionGroupOps.apply(store,request);
            String before=SketchDocument.canonical(store.get(source.id).toJson());
            assertThrows(IllegalStateException.class,()->StudioMotionGroupOps.apply(store,request));
            assertEquals(before,SketchDocument.canonical(store.get(source.id).toJson()));
            ProjectStore.Project next=store.get(source.id);
            JSONObject reused=create(next);
            assertThrows(IllegalArgumentException.class,()->StudioMotionGroupOps.apply(store,reused));
            assertEquals(before,SketchDocument.canonical(store.get(source.id).toJson()));
        }
    }
}
