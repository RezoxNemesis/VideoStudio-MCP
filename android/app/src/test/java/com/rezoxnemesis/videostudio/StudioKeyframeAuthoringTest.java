package com.rezoxnemesis.videostudio;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.After;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class) @Config(sdk=33,manifest=Config.NONE)
public class StudioKeyframeAuthoringTest {
    private final java.util.ArrayList<ProjectStore> opened=new java.util.ArrayList<>();
    @After public void closeStores()throws Exception{java.lang.reflect.Field db=ProjectStore.class.getDeclaredField("db");db.setAccessible(true);for(ProjectStore store:opened)((android.database.sqlite.SQLiteDatabase)db.get(store)).close();}
    @Test public void incrementalSplitEditPreservesAuthoredClockOutsideKeysAndOtherTransformValues() throws Exception {
        ProjectStore store=store();ProjectStore.Project p=project(store);
        ProjectStore.Clip clip=p.clips.get(0);clip.outMs=500;
        clip.effects.put("studioKeyframeDurationMs",1000).put("studioKeyframeOffsetMs",500)
                .put("keyframes",new JSONArray().put(key(0,0,"ease_in")).put(key(1000,1,"linear")));
        store.save(p);
        JSONObject result=apply(store,p,new JSONArray().put(upsert(new JSONObject().put("timeMs",500).put("opacity",.4))));
        ProjectStore.Clip saved=store.get(p.id).clips.get(0);
        assertEquals(1000,saved.effects.getLong("studioKeyframeDurationMs"));
        assertEquals(500,saved.effects.getLong("studioKeyframeOffsetMs"));
        assertEquals(0,saved.effects.getJSONArray("keyframes").getJSONObject(0).getLong("timeMs"));
        assertEquals(.5625,StudioClipKeyframes.fromEffects(saved.effects,500).at(250).x,.000001);
        assertEquals(1,StudioClipKeyframes.fromEffects(saved.effects,500).at(500).x,0);
        assertEquals(.4,StudioClipKeyframes.fromEffects(saved.effects,500).at(500).opacity,0);
        assertTrue(result.getLong("updatedAt")>p.updatedAt);
    }

    @Test public void legacyKeysCanonicalizeOnlyOnEditAndLocalDeleteDoesNotTouchOutsideKeys() throws Exception {
        ProjectStore store=store();ProjectStore.Project p=project(store);
        ProjectStore.Clip clip=p.clips.get(0);clip.outMs=500;
        clip.effects.put("studioKeyframeDurationMs",1000).put("studioKeyframeOffsetMs",500)
                .put("keyframes",new JSONArray().put(new JSONObject().put("t",0).put("rotation",10))
                        .put(new JSONObject().put("t",.5).put("rotation",20)).put(new JSONObject().put("t",1).put("rotation",30)));
        store.save(p);
        apply(store,p,new JSONArray().put(new JSONObject().put("action","delete").put("timeMs",0)));
        JSONArray saved=store.get(p.id).clips.get(0).effects.getJSONArray("keyframes");
        assertEquals(2,saved.length());assertEquals(0,saved.getJSONObject(0).getLong("timeMs"));
        assertEquals(1000,saved.getJSONObject(1).getLong("timeMs"));
        assertEquals(30,saved.getJSONObject(1).getDouble("rotate"),0);assertFalse(saved.getJSONObject(1).has("t"));
    }

    @Test public void newPartialKeySamplesAllOtherValuesAndEditsAreOrdered() throws Exception {
        ProjectStore store=store();ProjectStore.Project p=project(store);
        p.clips.get(0).effects.put("keyframes",new JSONArray().put(key(0,0,"linear")).put(key(1000,1,"linear")));
        store.save(p);
        apply(store,p,new JSONArray().put(upsert(new JSONObject().put("timeMs",500).put("rotate",45).put("easing","hold")))
                .put(new JSONObject().put("action","delete").put("timeMs",1000)));
        JSONArray saved=store.get(p.id).clips.get(0).effects.getJSONArray("keyframes");
        assertEquals(2,saved.length());JSONObject added=saved.getJSONObject(1);
        assertEquals(500,added.getLong("timeMs"));assertEquals(.5,added.getDouble("x"),0);
        assertEquals(45,added.getDouble("rotate"),0);assertEquals("hold",added.getString("easing"));
    }

    @Test public void invalidLateEditAndStaleRevisionDoNotMutateSavedKeys() throws Exception {
        ProjectStore store=store();ProjectStore.Project p=project(store);
        JSONObject request=request(p,new JSONArray().put(upsert(new JSONObject().put("timeMs",0).put("x",.2)))
                .put(upsert(new JSONObject().put("timeMs",1001))));
        String before=store.get(p.id).toJson().toString();
        try{StudioPrecisionEdits.apply(RuntimeEnvironment.getApplication(),store,request);fail("Out-of-range patch accepted");}catch(IllegalArgumentException expected){}
        assertEquals(before,store.get(p.id).toJson().toString());
        JSONObject valid=request(p,new JSONArray().put(upsert(new JSONObject().put("timeMs",0).put("x",.3))));
        StudioPrecisionEdits.apply(RuntimeEnvironment.getApplication(),store,valid);
        try{StudioPrecisionEdits.apply(RuntimeEnvironment.getApplication(),store,valid);fail("Stale patch accepted");}catch(IllegalStateException expected){}
        assertEquals(.3,store.get(p.id).clips.get(0).effects.getJSONArray("keyframes").getJSONObject(0).getDouble("x"),0);
    }

    @Test public void stereoFieldsAreStrictAndDoNotResetPreservedFadeClock() throws Exception {
        ProjectStore store=store();ProjectStore.Project p=project(store);
        p.clips.get(0).outMs=900;p.clips.get(0).effects.put("audioFadeInMs",800).put("studioAudioDurationMs",1000).put("studioAudioOffsetMs",100);store.save(p);
        StudioPrecisionEdits.apply(RuntimeEnvironment.getApplication(),store,new JSONObject().put("projectId",p.id).put("expectedUpdatedAt",p.updatedAt)
                .put("operations",new JSONArray().put(new JSONObject().put("clipId","keyed-clip").put("set",new JSONObject().put("audioPan",-.5).put("audioMono",true)))));
        ProjectStore.Project saved=store.get(p.id);JSONObject fx=saved.clips.get(0).effects;
        assertEquals(-.5,fx.getDouble("audioPan"),0);assertTrue(fx.getBoolean("audioMono"));assertEquals(100,fx.getLong("studioAudioOffsetMs"));
        for(JSONObject bad:new JSONObject[]{new JSONObject().put("audioPan",1.01),new JSONObject().put("audioPan","0"),new JSONObject().put("audioMono",1)}){
            try{StudioPrecisionEdits.apply(RuntimeEnvironment.getApplication(),store,new JSONObject().put("projectId",saved.id).put("expectedUpdatedAt",saved.updatedAt)
                    .put("operations",new JSONArray().put(new JSONObject().put("clipId","keyed-clip").put("set",bad))));fail("Invalid stereo setting accepted");}catch(IllegalArgumentException expected){}
        }
    }

    @Test public void malformedPatchFieldsAndLimitRejectWithoutChangingEffects()throws Exception{
        JSONObject effects=new JSONObject().put("keyframes",new JSONArray().put(key(0,0,"linear")));String before=effects.toString();
        for(JSONArray edits:new JSONArray[]{new JSONArray(),new JSONArray().put(new JSONObject().put("action","delete").put("timeMs",0).put("x",1)),
                new JSONArray().put(upsert(new JSONObject().put("timeMs",0).put("unknown",1))),
                new JSONArray().put(upsert(new JSONObject().put("timeMs",0).put("easing",1))),
                new JSONArray().put(upsert(new JSONObject().put("timeMs",0).put("x","0"))),
                new JSONArray().put(new JSONObject().put("action","delete").put("timeMs",300))}){
            try{StudioKeyframeEdits.apply(effects,1000,edits);fail("Malformed patch accepted");}catch(IllegalArgumentException expected){}
            assertEquals(before,effects.toString());
        }
        JSONArray full=new JSONArray();for(int i=0;i<64;i++)full.put(key(i,0,"linear"));effects.put("keyframes",full);before=effects.toString();
        try{StudioKeyframeEdits.apply(effects,1000,new JSONArray().put(upsert(new JSONObject().put("timeMs",100))));fail("65th key accepted");}catch(IllegalArgumentException expected){}
        assertEquals(before,effects.toString());
    }

    @Test public void changingCubicEasingBackToLinearDropsResidualHandles()throws Exception{
        JSONObject fx=new JSONObject().put("keyframes",new JSONArray()
                .put(key(0,0,"linear")).put(key(1000,1,"linear")));
        JSONObject bezier=new JSONObject().put("timeMs",0).put("easing","cubic_bezier")
                .put("bezier",StudioBezierEasing.fromText("0.42,0,0.58,1"));
        fx=StudioKeyframeEdits.apply(fx,1000,new JSONArray().put(upsert(bezier)));
        assertEquals("cubic_bezier",fx.getJSONArray("keyframes").getJSONObject(0).getString("easing"));
        assertTrue(Math.abs(StudioClipKeyframes.fromEffects(fx,1000).at(250).x-.25)>.01);
        fx=StudioKeyframeEdits.apply(fx,1000,new JSONArray().put(upsert(
                new JSONObject().put("timeMs",0).put("easing","linear"))));
        JSONObject first=fx.getJSONArray("keyframes").getJSONObject(0);
        assertEquals("linear",first.getString("easing"));
        assertFalse(first.has("bezier"));
        assertEquals(.25,StudioClipKeyframes.fromEffects(fx,1000).at(250).x,.000001);
    }

    @Test public void uniformScalePatchReplacesIndependentAxesAndReplacementCannotMixWithPatches()throws Exception{
        ProjectStore store=store();ProjectStore.Project p=project(store);
        p.clips.get(0).effects.put("keyframes",new JSONArray().put(new JSONObject().put("timeMs",0).put("scaleX",2).put("scaleY",3)));store.save(p);
        apply(store,p,new JSONArray().put(upsert(new JSONObject().put("timeMs",0).put("scale",4))));
        ProjectStore.Project saved=store.get(p.id);StudioClipKeyframes.Values values=StudioClipKeyframes.fromEffects(saved.clips.get(0).effects,1000).at(0);
        assertEquals(4,values.scaleX,0);assertEquals(4,values.scaleY,0);
        JSONObject mixed=request(saved,new JSONArray().put(upsert(new JSONObject().put("timeMs",100))));mixed.getJSONArray("operations").getJSONObject(0).put("clearKeyframes",true);
        try{StudioPrecisionEdits.apply(RuntimeEnvironment.getApplication(),store,mixed);fail("Mixed clear and patch accepted");}catch(IllegalArgumentException expected){}
        assertEquals(1,store.get(p.id).clips.get(0).effects.getJSONArray("keyframes").length());
    }

    private static JSONObject key(long time,double x,String easing)throws Exception{return new JSONObject().put("timeMs",time).put("x",x).put("easing",easing);}
    private ProjectStore store(){ProjectStore store=new ProjectStore(RuntimeEnvironment.getApplication());opened.add(store);return store;}
    private static JSONObject upsert(JSONObject key)throws Exception{return new JSONObject().put("action","upsert").put("keyframe",key);}
    private static JSONObject request(ProjectStore.Project p,JSONArray edits)throws Exception{return new JSONObject().put("projectId",p.id).put("expectedUpdatedAt",p.updatedAt).put("operations",new JSONArray().put(new JSONObject().put("clipId","keyed-clip").put("keyframeEdits",edits)));}
    private static JSONObject apply(ProjectStore store,ProjectStore.Project p,JSONArray edits)throws Exception{return StudioPrecisionEdits.apply(RuntimeEnvironment.getApplication(),store,request(p,edits));}
    private static ProjectStore.Project project(ProjectStore store){ProjectStore.Project p=store.create("Keyframe authoring");ProjectStore.Asset a=new ProjectStore.Asset();a.id="owned";a.mime="image/png";a.uri="file:///unused.png";a.durationMs=2000;p.assets.add(a);ProjectStore.Clip c=new ProjectStore.Clip();c.id="keyed-clip";c.assetId=a.id;c.outMs=1000;p.clips.add(c);store.save(p);return p;}
}
