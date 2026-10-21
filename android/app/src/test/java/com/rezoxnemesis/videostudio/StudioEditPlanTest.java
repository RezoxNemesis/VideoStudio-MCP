package com.rezoxnemesis.videostudio;

import android.content.Context;
import java.lang.reflect.InvocationTargetException;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

/** Exercise the real controller entry point and its SQLite/renderer boundary. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=33,manifest=Config.NONE)
public class StudioEditPlanTest {
    private final Context context=RuntimeEnvironment.getApplication();
    private final ProjectStore store=new ProjectStore(context);

    private ProjectStore.Project project() {
        ProjectStore.Project p=store.create("Reference edit test");
        ProjectStore.Asset a=new ProjectStore.Asset();a.id="source";a.uri="file:///source.mp4";a.mime="video/mp4";a.durationMs=12000;p.assets.add(a);
        ProjectStore.Clip c=new ProjectStore.Clip();c.id="original";c.assetId=a.id;c.outMs=2000;p.clips.add(c);store.save(p);return p;
    }
    private JSONObject clip(String id)throws Exception {
        return new JSONObject().put("clipId",id).put("assetId","source").put("start",1).put("end",3).put("speed",.25).put("volume",0);
    }
    private JSONObject request(ProjectStore.Project p,JSONArray clips)throws Exception {
        return new JSONObject().put("projectId",p.id).put("expectedUpdatedAt",p.updatedAt).put("clips",clips);
    }
    private JSONObject apply(JSONObject request)throws Exception {
        ControlService service=Robolectric.buildService(ControlService.class).get();
        java.lang.reflect.Field field=ControlService.class.getDeclaredField("store");field.setAccessible(true);field.set(service,store);
        java.lang.reflect.Method method=ControlService.class.getDeclaredMethod("applyEditPlan",JSONObject.class);method.setAccessible(true);
        try{return (JSONObject)method.invoke(service,request);}
        catch(InvocationTargetException wrapped){Throwable cause=wrapped.getCause();if(cause instanceof Exception)throw (Exception)cause;throw wrapped;}
    }
    private void refusedUnchanged(ProjectStore.Project p,JSONObject request)throws Exception {
        String before=store.get(p.id).toJson().toString();
        try{apply(request);fail("Invalid or stale plan was accepted");}
        catch(IllegalArgumentException|IllegalStateException expected){}
        assertEquals(before,store.get(p.id).toJson().toString());
    }

    @Test public void staleAutonomousPlanCannotOverwriteANewerManualEdit()throws Exception {
        ProjectStore.Project p=project();JSONObject stale=request(p,new JSONArray().put(clip("cut-a")));
        StudioPrecisionEdits.apply(context,store,new JSONObject().put("projectId",p.id).put("expectedUpdatedAt",p.updatedAt)
                .put("operations",new JSONArray().put(new JSONObject().put("clipId","original").put("set",new JSONObject().put("brightness",.3)))));
        refusedUnchanged(p,stale);
    }
    @Test public void stableIdsColorAudioAndEasedKeysSurviveTheActualControllerAndRenderer()throws Exception {
        ProjectStore.Project p=project();JSONObject effects=new JSONObject().put("brightness",.3).put("motionPreset","none")
                .put("audioFadeInMs",300).put("audioFadeOutMs",400)
                .put("keyframes",new JSONArray().put(new JSONObject().put("timeMs",0).put("x",0).put("easing","ease_in"))
                        .put(new JSONObject().put("timeMs",8000).put("x",.4)));
        JSONObject result=apply(request(p,new JSONArray().put(clip("cut-a").put("title","Keep the edit").put("effects",effects))));
        ProjectStore.Clip saved=store.get(p.id).clips.get(0);
        assertEquals("cut-a",saved.id);assertEquals(.25,saved.speed,0);assertEquals(0,saved.volume,0);
        assertEquals("Keep the edit",saved.title);assertEquals(.3,saved.effects.getDouble("brightness"),0);
        assertEquals(8000,saved.outputDurationMs());assertTrue(result.getLong("updatedAt")>p.updatedAt);
        assertEquals(.1,StudioClipKeyframes.fromEffects(saved.effects,8000).at(4000).x,.000001);
        assertTrue(new NativeRenderEngine(context).buildPreviewEffects(saved,"9:16","720p")
                .stream().anyMatch(effect->effect instanceof StudioClipKeyframes));
        effects.put("brightness",-.5);assertEquals(.3,store.get(p.id).clips.get(0).effects.getDouble("brightness"),0);
    }
    @Test public void malformedLastEntryCannotBeSilentlySkipped()throws Exception {
        ProjectStore.Project p=project();refusedUnchanged(p,request(p,new JSONArray().put(clip("valid")).put(JSONObject.NULL)));
    }
    @Test public void unknownLastAssetRollsBackTheWholePlan()throws Exception {
        ProjectStore.Project p=project();refusedUnchanged(p,request(p,new JSONArray().put(clip("valid")).put(clip("bad").put("assetId","missing"))));
    }
    @Test public void excessivePlansFailInsteadOfDroppingTheLastCuts()throws Exception {
        ProjectStore.Project p=project();JSONArray clips=new JSONArray();for(int i=0;i<121;i++)clips.put(clip("cut-"+i));
        refusedUnchanged(p,request(p,clips));
    }
    @Test public void duplicateStableIdsFailBeforeSaving()throws Exception {
        ProjectStore.Project p=project();refusedUnchanged(p,request(p,new JSONArray().put(clip("same")).put(clip("same"))));
    }
    @Test public void imageCanBeHeldLongerThanItsDefaultDuration()throws Exception {
        ProjectStore.Project p=project();p.assets.get(0).mime="image/png";p.assets.get(0).durationMs=3000;store.save(p);
        apply(request(p,new JSONArray().put(clip("hold").put("start",0).put("end",8).put("speed",1))));
        assertEquals(8000,store.get(p.id).clips.get(0).outMs);
    }
    @Test public void outOfSourceVideoRangeIsRejectedWithoutSilentClamping()throws Exception {
        ProjectStore.Project p=project();refusedUnchanged(p,request(p,new JSONArray().put(clip("bad").put("end",13))));
    }
    @Test public void invalidSpeedAndMalformedEffectsAreRefused()throws Exception {
        for(JSONObject clip:new JSONObject[]{clip("bad").put("speed",0),clip("bad").put("speed","fast"),clip("bad").put("effects","invalid"),
                clip("bad").put("unimplementedEffect","do not silently ignore"),
                clip("bad").put("effects",new JSONObject().put("keyframes",new JSONArray().put(new JSONObject().put("timeMs",9000))))}){
            ProjectStore.Project p=project();refusedUnchanged(p,request(p,new JSONArray().put(clip)));
        }
    }
    @Test public void missingNamedProjectMustNotCreateAnEmptyReplacement()throws Exception {
        project();int count=store.list().size();
        try{apply(new JSONObject().put("projectId","does-not-exist").put("clips",new JSONArray().put(clip("bad"))));fail("Missing project accepted");}
        catch(IllegalArgumentException expected){}
        assertEquals(count,store.list().size());
    }
    @Test public void legacyActiveProjectPlansStillWorkWithoutAnExplicitRevision()throws Exception {
        ProjectStore.Project p=project();JSONObject clip=clip("ignored");clip.remove("clipId");
        JSONObject result=apply(new JSONObject().put("clips",new JSONArray().put(clip)));
        assertTrue(result.getBoolean("ok"));assertEquals(p.id,result.getString("projectId"));assertEquals(1,store.get(p.id).clips.size());
        assertFalse(store.get(p.id).clips.get(0).id.isEmpty());
    }
}
