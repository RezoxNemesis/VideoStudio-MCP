package com.rezoxnemesis.videostudio;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import java.util.List;
import java.util.UUID;
import static org.junit.Assert.*;

/**
 * Public project/layer APIs only. These tests intentionally start RED on code525.
 * They do not decode the fixture URIs and are not GPU, codec or artistic evidence.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=33,manifest=Config.NONE)
public class StudioVideoLayerContractTest {
    @Test public void ownedSilentVideoRoundTripsItsTrimPlacementAndDetachedMotionRecipe() throws Exception {
        ProjectStore.Project p=scene();
        JSONObject input=video("insert").put("motionKeyframes",keys());
        StudioLayer layer=parse(p,input);
        JSONObject stored=layer.toJson();
        assertEquals("video",layer.type);assertEquals("insert",layer.id);
        assertEquals("overlay-source",layer.assetId);
        assertEquals(1750,layer.startMs);assertEquals(2000,layer.durationMs);assertEquals(1500,layer.sourceInMs);
        assertEquals("silent",stored.getString("audioMode"));
        assertEquals(.25,layer.x,0);assertEquals(.75,layer.y,0);
        assertEquals(.5,layer.width,0);assertEquals(.25,layer.height,0);
        assertEquals(12,layer.rotation,0);assertEquals(.75,layer.opacity,0);
        assertFalse(stored.has("text"));assertFalse(stored.has("gain"));
        assertFalse(stored.has("color"));assertFalse(stored.has("position"));
        String detached=canonical(stored);
        input.getJSONArray("motionKeyframes").getJSONObject(1).put("x",-1);
        stored.getJSONArray("motionKeyframes").getJSONObject(1).put("x",1);
        assertEquals(detached,canonical(layer.toJson()));
        assertEquals(detached,canonical(layer.copy().toJson()));
    }

    @Test public void explicitSilentAudioModeIsRequiredAndCannotCoerceOtherValues() throws Exception {
        ProjectStore.Project p=scene();parse(p,video("positive"));
        JSONObject omitted=video("bad");omitted.remove("audioMode");reject(p,omitted);
        for(Object value:new Object[]{JSONObject.NULL,false,true,0,1,"","mute","source","SILENT"})
            reject(p,video("bad").put("audioMode",value));
    }

    @Test public void videoRejectsUnknownFieldsUriBypassesAndUnimplementedAudioOrCaptionControls() throws Exception {
        ProjectStore.Project p=scene();parse(p,video("positive"));
        for(String name:new String[]{"uri","sourceUri","videoUri","gain","text","position","color","fontSize","crop","speed","unexpected"})
            reject(p,video("bad").put(name,"uri".equals(name)?"file:///foreign.mp4":1));
    }

    @Test public void audioModeCannotDisappearSilentlyOnLegacyLayerKinds() throws Exception {
        ProjectStore.Project p=scene();parse(p,video("positive"));
        for(String type:new String[]{StudioLayer.AUDIO,StudioLayer.IMAGE,StudioLayer.TITLE}){
            JSONObject legacy=legacy(type).put("audioMode","silent");
            reject(p,legacy);
        }
    }

    @Test public void videoTimingPlacementAndOpacityUseStrictFiniteNumericInputs() throws Exception {
        ProjectStore.Project p=scene();parse(p,video("positive"));
        for(String field:new String[]{"startMs","durationMs","sourceInMs","x","y","width","height","rotation","opacity"}){
            reject(p,video("bad").put(field,JSONObject.NULL));
            reject(p,video("bad").put(field,"1"));
            reject(p,video("bad").put(field,true));
        }
        for(String field:new String[]{"startMs","durationMs","sourceInMs"})reject(p,video("bad").put(field,1.5));
        reject(p,video("bad").put("startMs",-1));reject(p,video("bad").put("durationMs",99));
        reject(p,video("bad").put("sourceInMs",-1));reject(p,video("bad").put("x",1.01));
        reject(p,video("bad").put("y",-.01));reject(p,video("bad").put("width",.009));
        reject(p,video("bad").put("height",2.01));reject(p,video("bad").put("rotation",361));
        reject(p,video("bad").put("opacity",1.01));
    }

    @Test public void videoMotionKeysRetainStrictExistingLocalTimeAndBoundedCountRules() throws Exception {
        ProjectStore.Project p=scene();parse(p,video("positive").put("motionKeyframes",keys()));
        reject(p,video("bad").put("motionKeyframes",JSONObject.NULL));
        reject(p,video("bad").put("motionKeyframes","[]"));
        reject(p,video("bad").put("motionKeyframes",new JSONArray().put(new JSONObject().put("timeMs",2001))));
        reject(p,video("bad").put("motionKeyframes",new JSONArray().put(new JSONObject().put("timeMs",20)).put(new JSONObject().put("timeMs",10))));
        reject(p,video("bad").put("motionKeyframes",new JSONArray().put(new JSONObject().put("timeMs",0).put("wat",1))));
        JSONArray tooMany=new JSONArray();for(int i=0;i<65;i++)tooMany.put(new JSONObject().put("timeMs",i));
        reject(p,video("bad").put("motionKeyframes",tooMany));
        assertEquals(0,parse(p,video("clear").put("motionKeyframes",new JSONArray())).toJson().getJSONArray("motionKeyframes").length());
    }

    @Test public void videoMustBeOwnedByThisProjectWithARealVideoMimeAndNonemptyUri() throws Exception {
        ProjectStore.Project p=scene();parse(p,video("positive"));
        reject(p,video("foreign").put("assetId","private-other-project"));
        for(String mime:new String[]{"image/png","audio/mp4","application/octet-stream",""}){
            ProjectStore.Project bad=scene();bad.asset("overlay-source").mime=mime;reject(bad,video("bad"));
        }
        ProjectStore.Project bad=scene();bad.asset("overlay-source").uri="";reject(bad,video("bad"));
        bad.asset("overlay-source").uri=null;reject(bad,video("bad"));
    }

    @Test public void independentVideoRequiresKnownPositiveDurationAndVerifiedSeekability() throws Exception {
        ProjectStore.Project p=scene();parse(p,video("positive"));
        for(long duration:new long[]{0,-1}){
            ProjectStore.Project bad=scene();bad.asset("overlay-source").durationMs=duration;reject(bad,video("bad"));
        }
        ProjectStore.Project bad=scene();bad.asset("overlay-source").seekable=false;reject(bad,video("bad"));
    }

    @Test public void sourceTrimAndOutputSpanHaveSeparateExactBounds() throws Exception {
        ProjectStore.Project p=scene();parse(p,video("positive"));
        reject(p,video("source-overrun").put("sourceInMs",4001));
        reject(p,video("output-overrun").put("startMs",5001));
        reject(p,video("starts-at-end").put("startMs",7000));
        assertEquals(4000,parse(p,video("source-boundary").put("sourceInMs",4000)).sourceInMs);
        assertEquals(5000,parse(p,video("output-boundary").put("startMs",5000)).startMs);
    }

    @Test public void twoIndependentVideoLanesAreBoundedWithoutChangingTheExistingTotalLimit() throws Exception {
        ProjectStore.Project p=scene();parse(p,video("positive"));
        JSONArray two=new JSONArray().put(video("first")).put(video("second").put("startMs",0));
        assertEquals(2,StudioLayerContract.fromJson(p,two).size());
        rejectAll(p,new JSONArray(two.toString()).put(video("third")));
        JSONArray bounded=new JSONArray(two.toString());
        for(int i=0;i<30;i++)bounded.put(legacy(StudioLayer.TITLE).put("id","title-"+i));
        assertEquals(32,StudioLayerContract.fromJson(p,bounded).size());
        rejectAll(p,new JSONArray(bounded.toString()).put(legacy(StudioLayer.TITLE).put("id","overflow")));
        rejectAll(p,new JSONArray().put(video("same")).put(video("same")));
    }

    @Test public void invalidAtomicReplacementPreservesProjectRevisionBaseSourcesAndExistingLanes() throws Exception {
        parse(scene(),video("positive"));
        try(ProjectStore store=new ProjectStore(RuntimeEnvironment.getApplication())){
            ProjectStore.Project p=persistedScene(store);p.studioLayers.add(StudioLayer.fromJson(legacy(StudioLayer.TITLE)));store.save(p);
            String before=canonical(store.get(p.id).toJson());
            JSONArray replacement=new JSONArray().put(video("valid")).put(video("invalid").put("sourceInMs",5000));
            rejectRequest(store,request(p,replacement));
            assertEquals(before,canonical(store.get(p.id).toJson()));
        }
    }

    @Test public void staleReplacementIsRejectedAndSuccessfulReceiptRemainsBounded() throws Exception {
        parse(scene(),video("positive"));
        try(ProjectStore store=new ProjectStore(RuntimeEnvironment.getApplication())){
            ProjectStore.Project p=persistedScene(store);
            JSONObject stale=request(p,new JSONArray().put(video("insert")));store.save(p);
            String before=canonical(store.get(p.id).toJson());rejectRequest(store,stale);
            assertEquals(before,canonical(store.get(p.id).toJson()));
            JSONObject receipt=StudioLayerContract.apply(store,request(p,new JSONArray().put(video("insert"))));
            assertTrue(receipt.getBoolean("ok"));assertEquals(1,receipt.getInt("layerCount"));
            assertEquals("insert",receipt.getJSONArray("layerIds").getString(0));
            assertFalse(receipt.has("project"));assertFalse(receipt.has("assets"));assertFalse(receipt.has("studioLayers"));
        }
    }

    @Test public void savedRecipesReopenAndUndoRedoWithoutChangingSourcesOrBaseClock() throws Exception {
        parse(scene(),video("positive"));
        String projectId,expected,sourceSnapshot,clipSnapshot;
        try(ProjectStore store=new ProjectStore(RuntimeEnvironment.getApplication())){
            ProjectStore.Project p=persistedScene(store);projectId=p.id;
            sourceSnapshot=canonical(p.toJson().getJSONArray("assets"));clipSnapshot=canonical(p.toJson().getJSONArray("clips"));
            StudioEditHistory history=new StudioEditHistory();history.checkpoint(p);
            StudioLayerContract.apply(store,request(p,new JSONArray().put(video("insert").put("motionKeyframes",keys()))));
            ProjectStore.Project updated=store.get(p.id);expected=canonical(updated.toJson().getJSONArray("studioLayers"));
            assertEquals(7000,updated.outputDurationMs());
            ProjectStore.Project undone=history.undo(updated);assertNotNull(undone);assertEquals(0,undone.studioLayers.size());
            ProjectStore.Project redone=history.redo(undone);assertNotNull(redone);
            assertEquals(expected,canonical(redone.toJson().getJSONArray("studioLayers")));
            assertEquals(sourceSnapshot,canonical(redone.toJson().getJSONArray("assets")));
            assertEquals(clipSnapshot,canonical(redone.toJson().getJSONArray("clips")));
            store.save(redone);
        }
        try(ProjectStore reopened=new ProjectStore(RuntimeEnvironment.getApplication())){
            ProjectStore.Project restored=reopened.get(projectId);assertNotNull(restored);
            assertEquals(expected,canonical(restored.toJson().getJSONArray("studioLayers")));
            assertEquals(sourceSnapshot,canonical(restored.toJson().getJSONArray("assets")));
            assertEquals(clipSnapshot,canonical(restored.toJson().getJSONArray("clips")));
        }
    }

    @Test public void omissionPreservesLegacyAudioTitleImageRecipesAndImageMotionSerialization() throws Exception {
        ProjectStore.Project p=scene();
        for(String type:new String[]{StudioLayer.AUDIO,StudioLayer.TITLE,StudioLayer.IMAGE}){
            StudioLayer before=StudioLayer.fromJson(legacy(type));String encoded=canonical(before.toJson());
            StudioLayer after=parse(p,before.toJson());
            assertEquals(encoded,canonical(after.toJson()));assertFalse(after.toJson().has("audioMode"));
            assertFalse(after.toJson().has("motionKeyframes"));
        }
        StudioLayer image=StudioLayer.fromJson(legacy(StudioLayer.IMAGE).put("motionKeyframes",keys()));
        assertEquals(canonical(image.toJson()),canonical(parse(p,image.toJson()).toJson()));
    }

    static JSONObject video(String id)throws Exception{
        return new JSONObject().put("id",id).put("type","video").put("assetId","overlay-source")
                .put("audioMode","silent").put("startMs",1750).put("durationMs",2000).put("sourceInMs",1500)
                .put("x",.25).put("y",.75).put("width",.5).put("height",.25).put("rotation",12).put("opacity",.75);
    }
    static JSONArray keys()throws Exception{
        return new JSONArray().put(new JSONObject().put("timeMs",0).put("easing","ease_in_out"))
                .put(new JSONObject().put("timeMs",2000).put("x",.2).put("y",-.1).put("rotate",80).put("scaleX",1.5).put("opacity",.4));
    }
    static ProjectStore.Project scene(){
        ProjectStore.Project p=new ProjectStore.Project();p.id=UUID.randomUUID().toString();p.name="Independent video study";
        p.assets.add(asset("base","video/mp4",12000));p.assets.add(asset("overlay-source","video/mp4",6000));
        p.assets.add(asset("sound","audio/mp4",5000));p.assets.add(asset("still","image/png",0));
        for(int i=0;i<2;i++){
            ProjectStore.Clip c=new ProjectStore.Clip();c.id="base-"+i;c.assetId="base";c.inMs=i==0?1000:4000;c.outMs=i==0?4000:8000;p.clips.add(c);
        }
        return p;
    }
    private static ProjectStore.Asset asset(String id,String mime,long duration){
        ProjectStore.Asset a=new ProjectStore.Asset();a.id=id;a.name=id;a.mime=mime;a.durationMs=duration;
        a.uri="file:///not-decoded-"+id+(mime.startsWith("image/")?".png":".mp4");a.seekable=true;a.persistedReadAccess=true;a.sizeBytes=1234;return a;
    }
    private static JSONObject legacy(String type)throws Exception{
        StudioLayer l=new StudioLayer();l.id="legacy-"+type;l.type=type;l.durationMs=2000;
        if(StudioLayer.TITLE.equals(type))l.text="Keep original title";
        else l.assetId=StudioLayer.AUDIO.equals(type)?"sound":"still";
        return l.toJson();
    }
    private static ProjectStore.Project persistedScene(ProjectStore store){
        ProjectStore.Project p=scene();store.save(p);return p;
    }
    private static JSONObject request(ProjectStore.Project p,JSONArray layers)throws Exception{
        return new JSONObject().put("projectId",p.id).put("expectedUpdatedAt",p.updatedAt).put("studioLayers",layers);
    }
    static StudioLayer parse(ProjectStore.Project p,JSONObject layer)throws Exception{
        List<StudioLayer> result=StudioLayerContract.fromJson(p,new JSONArray().put(layer));assertEquals(1,result.size());return result.get(0);
    }
    private static void reject(ProjectStore.Project p,JSONObject layer)throws Exception{rejectAll(p,new JSONArray().put(layer));}
    private static void rejectAll(ProjectStore.Project p,JSONArray layers)throws Exception{
        try{StudioLayerContract.fromJson(p,layers);fail("Invalid independent video contract was accepted: "+layers);}
        catch(IllegalArgumentException expected){assertNotNull(expected.getMessage());}
    }
    private static void rejectRequest(ProjectStore store,JSONObject request)throws Exception{
        try{StudioLayerContract.apply(store,request);fail("Invalid/stale replacement was accepted");}
        catch(IllegalArgumentException|IllegalStateException expected){assertNotNull(expected.getMessage());}
    }
    private static String canonical(Object object)throws Exception{return SketchDocument.canonical(object);}
}
