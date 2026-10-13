package com.rezoxnemesis.videostudio;

import android.content.Context;
import android.graphics.Bitmap;
import android.net.Uri;
import org.json.JSONArray;
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

@RunWith(RobolectricTestRunner.class)
@Config(sdk=33,manifest=Config.NONE)
public final class StoryboardMotionAdmissionTest {
    private final Context context=RuntimeEnvironment.getApplication();
    private final ProjectStore store=new ProjectStore(context);
    private ProjectStore.Project source(int count)throws Exception {
        ProjectStore.Project p=store.create("Annotated admission fixture");
        for(int i=0;i<count;i++) {
            File file=new File(context.getFilesDir(),UUID.randomUUID()+".png");
            Bitmap image=Bitmap.createBitmap(48,80,Bitmap.Config.ARGB_8888);image.eraseColor(0xff223344+i);
            try(FileOutputStream out=new FileOutputStream(file)){assertTrue(image.compress(Bitmap.CompressFormat.PNG,100,out));}finally{image.recycle();}
            ProjectStore.Asset asset=new ProjectStore.Asset();asset.id=UUID.randomUUID().toString();asset.uri=Uri.fromFile(file).toString();asset.mime="image/png";asset.name="anchor"+i;asset.sizeBytes=file.length();p.assets.add(asset);
            ProjectStore.Clip clip=new ProjectStore.Clip();clip.id=UUID.randomUUID().toString();clip.assetId=asset.id;clip.outMs=1000;p.clips.add(clip);
        }
        store.save(p);return p;
    }
    private JSONObject authored(ProjectStore.Project p)throws Exception {
        Rig2DProjectStore rigs=new Rig2DProjectStore(context,store);JSONArray bindings=new JSONArray(),contacts=new JSONArray(),beats=new JSONArray();
        for(ProjectStore.Asset asset:p.assets) {
            JSONObject row=new JSONObject().put("assetId",asset.id);String sourceHash=StoryboardSourceManifest.captureSource(context,asset).getString("sourceSha256");
            for(boolean black:new boolean[]{true,false}) {
                Rig2DProfile rig=Rig2DProfile.create(p.id,asset.id,black,false,Rig2DProfileTest.joints(),Rig2DProfileTest.outline(),new float[]{.82f,.35f});
                JSONObject definition=rig.toJson().put("sourceSha256",sourceHash).put("swordHilt",new JSONArray().put(.7).put(.4))
                        .put("swordOutline",new JSONArray().put(new JSONArray().put(.69).put(.39)).put(new JSONArray().put(.82).put(.34)).put(new JSONArray().put(.83).put(.36)).put(new JSONArray().put(.71).put(.41)));
                rig=Rig2DProfile.fromJson(definition);JSONObject bound=rigs.saveWithBinding(rig,p.updatedAt,"");String role=black?"black":"white";row.put(role+"RigId",rig.id).put(role+"RigSha256",bound.getString("sha256"));
            }
            bindings.put(row);
        }
        for(int i=0;i<p.assets.size()-1;i++) {
            JSONObject pair=new JSONObject().put("shotIndex",i).put("fromAssetId",p.assets.get(i).id).put("toAssetId",p.assets.get(i+1).id);
            contacts.put(new JSONObject(pair.toString()).put("sourceWidth",48).put("sourceHeight",80).put("contactPlan",new JSONObject().put("black",planted()).put("white",planted())));
            beats.put(new JSONObject(pair.toString()).put("beat","STANDOFF"));
        }
        return new JSONObject().put("sourceProjectId",p.id).put("backend","SOURCE_RIG").put("fps",24).put("batchIndex",0)
                .put("framesPerPair",4).put("maxFramesPerBatch",20).put("width",432).put("height",720)
                .put("sourceRigBindings",bindings).put("contactPlans",contacts).put("choreography",beats);
    }
    private static JSONArray planted() throws Exception {
        float[] foot=Rig2DProfileTest.joints()[Rig2DProfile.LEADING_FOOT];
        return new JSONArray().put(new JSONObject().put("foot","leadingFoot").put("start",0).put("end",1)
                .put("anchor",new JSONArray().put(foot[0]).put(foot[1])));
    }
    private JSONObject accepted(ProjectStore.Project p,JSONObject request)throws Exception {
        JSONObject manifest=StoryboardSourceManifest.capture(context,p,request);StoryboardBatchPlanner.Plan plan=StoryboardBatchPlanner.plan(p,request,manifest);
        return new JSONObject(request.toString()).put("_storyboardSourceManifest",manifest).put("_storyboardAdmission",
                StoryboardMotionAdmission.freeze(context,store,p,plan,0,432,720,request,manifest));
    }
    @Test public void frozenAnnotationsVerifyButChangedBatchSegmentationRejects()throws Exception {
        ProjectStore.Project source=source(3);JSONObject request=authored(source),accepted=accepted(source,request);
        StoryboardBatchPlanner.Plan original=StoryboardBatchPlanner.plan(source,accepted,accepted.getJSONObject("_storyboardSourceManifest"));StoryboardMotionAdmission.verify(context,store,source,original,accepted);
        JSONObject changed=new JSONObject(accepted.toString()).put("maxFramesPerBatch",2);
        StoryboardBatchPlanner.Plan other=StoryboardBatchPlanner.plan(source,changed,changed.getJSONObject("_storyboardSourceManifest"));
        assertNotEquals(original.batches.size(),other.batches.size());
        try{StoryboardMotionAdmission.verify(context,store,source,other,changed);fail("changed accepted segmentation");}catch(IllegalStateException expected){assertTrue(expected.getMessage().contains("ACCEPTED_SOURCE_CHANGED"));}
    }
    @Test public void unrelatedNewProfileDoesNotReselectFrozenRigAndChangedSelectedRigRejects()throws Exception {
        ProjectStore.Project source=source(2);JSONObject request=authored(source),accepted=accepted(source,request);Rig2DProjectStore rigs=new Rig2DProjectStore(context,store);
        JSONObject selected=request.getJSONArray("sourceRigBindings").getJSONObject(0);Rig2DProfile old=rigs.load(source.id,selected.getString("blackRigId"));
        Rig2DProfile other=Rig2DProfile.fromJson(old.toJson().put("id",UUID.randomUUID().toString()));rigs.saveWithBinding(other,source.updatedAt,"");
        StoryboardBatchPlanner.Plan plan=StoryboardBatchPlanner.plan(source,accepted,accepted.getJSONObject("_storyboardSourceManifest"));StoryboardMotionAdmission.verify(context,store,source,plan,accepted);
        JSONObject edit=old.toJson();edit.getJSONArray("joints").getJSONArray(1).put(0,.51);rigs.saveWithBinding(Rig2DProfile.fromJson(edit),source.updatedAt,Rig2DProjectStore.sha256(old));
        try{StoryboardMotionAdmission.verify(context,store,source,plan,accepted);fail("changed bound rig");}catch(IllegalStateException expected){assertTrue(expected.getMessage().contains("SOURCE_RIG_CHANGED"));}
    }
    @Test public void legacyGeometryWithoutOriginalByteHashIsCinematicIneligible()throws Exception {
        ProjectStore.Project source=source(2);JSONObject request=authored(source);Rig2DProjectStore rigs=new Rig2DProjectStore(context,store);
        JSONObject binding=request.getJSONArray("sourceRigBindings").getJSONObject(0);Rig2DProfile old=rigs.load(source.id,binding.getString("blackRigId"));JSONObject definition=old.toJson();definition.remove("sourceSha256");Rig2DProfile legacy=Rig2DProfile.fromJson(definition);rigs.saveWithBinding(legacy,source.updatedAt,Rig2DProjectStore.sha256(old));
        StoryboardMotionAdmission.Admission result=StoryboardMotionAdmission.inspect(source,StoryboardBatchPlanner.plan(source,request),0,rigs.list(source.id),StoryboardMotionAdmission.CINEMATIC_ACTION,request);
        assertFalse(result.accepted());assertEquals("INSUFFICIENT_MOTION_PROVIDER",result.toJson().getString("code"));assertFalse(result.toJson().getBoolean("cinematicAcceptance"));
    }
    @Test public void completeActorRigsCannotAuthorizeCinematicActionWithEmptyGroundContacts()throws Exception {
        ProjectStore.Project source=source(2);JSONObject request=authored(source);
        request.getJSONArray("contactPlans").getJSONObject(0).getJSONObject("contactPlan")
                .put("black",new JSONArray()).put("white",new JSONArray());
        int projects=store.summaries().getJSONArray("projects").length();String graph=source.toJson().toString();
        try{accepted(source,request);fail("Empty arrays must not invent support or flight physics");}
        catch(StoryboardMotionAdmission.Rejected expected){
            assertTrue(expected.getMessage().contains("INSUFFICIENT_MOTION_PROVIDER"));
        }
        assertEquals(projects,store.summaries().getJSONArray("projects").length());
        assertEquals(graph,store.get(source.id).toJson().toString());
    }
    @Test public void explicitThreeSecondLegacyShotCreatesSeventyTwoAuthoredFramesAtTwentyFourFps()throws Exception {
        ProjectStore.Project source=source(2);JSONObject request=authored(source).put("durationSeconds",3);
        java.lang.reflect.Method method=ControlService.class.getDeclaredMethod("storyboardPlan",ProjectStore.Project.class,JSONObject.class,JSONObject.class);
        method.setAccessible(true);StoryboardBatchPlanner.Plan plan=(StoryboardBatchPlanner.Plan)method.invoke(null,source,request,null);
        assertEquals(72,StoryboardSeriesOrchestrator.predictedFrames(plan,-1));
        assertEquals(71,plan.batches.get(0).shots.get(0).frames);
        assertEquals(3000,NativeFrameClock.durationMs(72,plan.fps));
        assertEquals(source.assets.get(0).id,plan.assets.get(0));assertEquals(source.assets.get(1).id,plan.assets.get(1));
    }
}
