package com.rezoxnemesis.videostudio;

import org.json.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class) @Config(sdk=33,manifest=Config.NONE)
public class SketchLayeredDrawingTest {
 static JSONObject fixture()throws Exception{
  JSONObject d=SketchDocument.create(new JSONObject().put("sketchId","layered").put("width",256).put("height",256).put("frameCount",1));
  JSONObject ref=new JSONObject().put("sha256","a".repeat(64)).put("file","a".repeat(64)+".png").put("sourceSha256","b".repeat(64)).put("width",256).put("height",256).put("sourceProjectId","source_project").put("sourceAssetId","source_asset");
  SketchEditOps.apply(d,new JSONObject().put("type","add_object").put("layerId","layer_1").put("object",new JSONObject().put("id","guide").put("kind","image").put("reference",ref).put("bounds",new JSONObject("{x:0,y:0,width:1,height:1}"))));
  return d;
 }
 static JSONObject begin()throws Exception{return new JSONObject().put("type","layered_drawing_begin").put("referenceObjectId","guide").put("prefix","actor").put("parts",new JSONArray("[{id:'head',name:'Head'},{id:'body',name:'Body'}]"));}
 static JSONObject stroke(String layer,String id)throws Exception{return new JSONObject().put("type","add_object").put("layerId",layer).put("object",new JSONObject().put("id",id).put("kind","stroke").put("points",new JSONArray("[[0.2,0.2,1],[0.7,0.7,1]]")));}
 static void review(JSONObject d,String stage)throws Exception{SketchEditOps.apply(d,new JSONObject().put("type","layered_drawing_review").put("stage",stage).put("reviewedRevision",d.getLong("revision")).put("status","ACCEPTABLE").put("note","Inspected actual authored marks."));}
 @Test public void laterPassesHideConstructionAndCleanDepthOrderBindsReviewAndAnimation()throws Exception{
  JSONObject d=fixture();SketchEditOps.apply(d,begin());int n=0;
  for(String stage:SketchLayeredDrawing.STAGES){if(!"construction".equals(stage))SketchEditOps.apply(d,new JSONObject().put("type","layered_drawing_stage").put("stage",stage));if("details".equals(stage))assertFalse("Construction cannot cross finished detail",SketchDocument.layer(d,"actor_construction").getBoolean("visible"));if("clean_line".equals(stage)){assertFalse(SketchDocument.layer(d,"actor_refinement").getBoolean("visible"));assertFalse(SketchDocument.layer(d,"actor_details").getBoolean("visible"));}JSONArray ids=SketchLayeredDrawing.activeLayerIds(d);if(!"separation".equals(stage))for(int i=0;i<ids.length();i++)SketchEditOps.apply(d,stroke(ids.getString(i),"mark_depth_"+(n++)));review(d,stage);}
  SketchEditOps.apply(d,new JSONObject().put("type","layered_drawing_finish"));String before=SketchLayeredDrawing.stageContentSha256(d,"clean_line"),guide=SketchDocument.canonical(SketchDocument.layer(d,"layer_1"));
  assertThrows(IllegalStateException.class,()->SketchEditOps.apply(d,new JSONObject().put("type","reorder_layer").put("layerId","actor_head").put("index",0)));
  SketchEditOps.apply(d,new JSONObject().put("type","reorder_layer").put("layerId","actor_head").put("index",5));assertNotEquals(before,SketchLayeredDrawing.stageContentSha256(d,"clean_line"));assertFalse(SketchLayeredDrawing.summary(d).getJSONArray("stageReviews").getJSONObject(3).getBoolean("acceptable"));assertThrows(IllegalStateException.class,()->SketchLayeredDrawing.animationSource(d));
  SketchEditOps.apply(d,new JSONObject().put("type","layered_drawing_stage").put("stage","clean_line"));review(d,"clean_line");SketchEditOps.apply(d,new JSONObject().put("type","layered_drawing_stage").put("stage","separation"));review(d,"separation");SketchEditOps.apply(d,new JSONObject().put("type","layered_drawing_finish"));JSONObject animation=SketchLayeredDrawing.animationSource(d);assertEquals("actor_body",animation.getJSONArray("layers").getJSONObject(0).getString("id"));assertEquals(guide,SketchDocument.canonical(SketchDocument.layer(d,"layer_1")));SketchDocument.validate(d);
 }
 @Test public void beginFreezesOnlyGuideAndCreatesOrderedEditableLayers()throws Exception{
  JSONObject invalid=begin();invalid.getJSONArray("parts").getJSONObject(0).put("id","x".repeat(49));assertThrows(IllegalStateException.class,()->SketchEditOps.apply(fixture(),invalid));
  JSONObject d=fixture();SketchEditOps.apply(d,begin());SketchDocument.validate(d);assertTrue(SketchDocument.layer(d,"layer_1").getBoolean("locked"));assertFalse(SketchDocument.layer(d,"layer_1").getBoolean("visible"));assertEquals(0,SketchDocument.object(d,"guide").getJSONObject("transform").getDouble("opacity"),0);assertEquals("[\"actor_construction\"]",SketchLayeredDrawing.activeLayerIds(d).toString());
  JSONObject temporary=SketchLayeredDrawing.referenceDocument(d);assertFalse(temporary.has("layeredDrawing"));assertEquals(1,temporary.getJSONArray("layers").length());assertEquals(1,SketchDocument.object(temporary,"guide").getJSONObject("transform").getDouble("opacity"),0);SketchDocument.validate(temporary);assertEquals(0,SketchDocument.object(d,"guide").getJSONObject("transform").getDouble("opacity"),0);
 }
 @Test public void shortcutsAndGuideMutationsCannotBypassPersistentPolicy()throws Exception{
  JSONObject d=fixture();SketchEditOps.apply(d,begin());
  for(String type:new String[]{"add_human_animation","trace_reference","master_instance","duplicate_cel","set_canvas","set_timing","add_layer"})assertThrows(type,IllegalStateException.class,()->SketchEditOps.apply(d,new JSONObject().put("type",type)));
  for(String kind:new String[]{"rect","ellipse","polygon","human","hand","image","paint"})assertThrows(kind,IllegalStateException.class,()->SketchEditOps.apply(d,new JSONObject().put("type","add_object").put("layerId","actor_construction").put("object",new JSONObject().put("id","bad").put("kind",kind))));
  assertThrows(IllegalStateException.class,()->SketchEditOps.apply(d,new JSONObject().put("type","update_layer").put("layerId","layer_1").put("patch",new JSONObject().put("locked",false))));
  assertThrows(IllegalStateException.class,()->SketchEditOps.apply(d,stroke("actor_details","wrong_stage")));
  assertThrows(IllegalStateException.class,()->SketchEditOps.apply(d,new JSONObject().put("type","paint_stroke").put("objectId","none").put("brush",new JSONObject().put("mode","clone"))));
 }
 @Test public void advancingRequiresExplicitFreshReviewOfNonemptyVisibleArt()throws Exception{
  JSONObject d=fixture();SketchEditOps.apply(d,begin());assertThrows(IllegalStateException.class,()->review(d,"construction"));assertThrows(IllegalStateException.class,()->SketchEditOps.apply(d,new JSONObject().put("type","layered_drawing_stage").put("stage","refinement")));
  SketchEditOps.apply(d,stroke("actor_construction","rough"));review(d,"construction");assertTrue(SketchLayeredDrawing.summary(d).getJSONArray("stageReviews").getJSONObject(0).getBoolean("acceptable"));
  SketchEditOps.apply(d,new JSONObject().put("type","update_object").put("objectId","rough").put("patch",new JSONObject().put("color","#FFFF0000")));assertFalse(SketchLayeredDrawing.summary(d).getJSONArray("stageReviews").getJSONObject(0).getBoolean("acceptable"));assertThrows(IllegalStateException.class,()->SketchEditOps.apply(d,new JSONObject().put("type","layered_drawing_stage").put("stage","refinement")));
  review(d,"construction");SketchEditOps.apply(d,new JSONObject().put("type","layered_drawing_stage").put("stage","refinement"));assertEquals("refinement",d.getJSONObject("layeredDrawing").getString("stage"));assertThrows(IllegalStateException.class,()->SketchEditOps.apply(d,new JSONObject().put("type","layered_drawing_review").put("stage","refinement").put("reviewedRevision",2).put("status","ACCEPTABLE").put("note","stale")));
 }
 @Test public void finishRequiresEveryCurrentDigestAndEveryCleanPart()throws Exception{
  JSONObject d=fixture();SketchEditOps.apply(d,begin());int sequence=0;
  for(String stage:new String[]{"construction","refinement","details","clean_line","separation"}){
   if(!stage.equals("construction"))SketchEditOps.apply(d,new JSONObject().put("type","layered_drawing_stage").put("stage",stage));
   JSONArray layers=SketchLayeredDrawing.activeLayerIds(d);if(!stage.equals("separation"))for(int i=0;i<layers.length();i++)SketchEditOps.apply(d,stroke(layers.getString(i),"authored_"+(sequence++)));review(d,stage);
  }
  SketchEditOps.apply(d,new JSONObject().put("type","layered_drawing_finish"));JSONObject animation=SketchLayeredDrawing.animationSource(d);assertFalse(animation.has("layeredDrawing"));assertEquals(2,animation.getJSONArray("layers").length());assertEquals("actor_head",animation.getJSONArray("layers").getJSONObject(0).getString("id"));assertTrue(d.has("layeredDrawing"));String originalCheckpoint=SketchDocument.canonical(d);JSONObject compiled=SketchAnimationCompiler.compile(d,new JSONObject().put("schema",SketchAnimationCompiler.SCHEMA).put("frameCount",8).put("fps",24).put("rigs",new JSONArray()),"clean_animation").getJSONObject("document");assertEquals(8,compiled.getInt("frameCount"));assertEquals(24,compiled.getInt("fps"));assertEquals(2,compiled.getJSONArray("layers").length());assertFalse(compiled.has("layeredDrawing"));assertEquals("actor_head",compiled.getJSONArray("layers").getJSONObject(0).getString("id"));assertEquals(originalCheckpoint,SketchDocument.canonical(d));assertEquals("REVIEWED",d.getJSONObject("layeredDrawing").getString("status"));assertFalse(SketchDocument.layer(d,"actor_construction").getBoolean("visible"));assertTrue(SketchDocument.layer(d,"actor_head").getBoolean("visible"));assertFalse(SketchDocument.layer(d,"actor_head").getBoolean("locked"));SketchDocument.validate(SketchDocument.copy(d));
  SketchEditOps.apply(d,stroke("actor_head","later_edit"));assertEquals("IN_PROGRESS",SketchLayeredDrawing.summary(d).getString("status"));assertFalse(SketchLayeredDrawing.summary(d).getJSONArray("stageReviews").getJSONObject(3).getBoolean("acceptable"));
 }
 @Test public void reviewFramesAndBoundedHistoryBindAllAuthoredContent()throws Exception{
  JSONObject d=fixture();d.put("frameCount",2);SketchEditOps.apply(d,begin());JSONObject mark=stroke("actor_construction","later_frame");mark.getJSONObject("object").put("frame",1);SketchEditOps.apply(d,mark);assertThrows(IllegalStateException.class,()->review(d,"construction"));
  JSONObject decision=new JSONObject().put("type","layered_drawing_review").put("stage","construction").put("reviewedRevision",d.getLong("revision")).put("frameIndex",1).put("status","ACCEPTABLE").put("note","Reviewed source frame two.");
  for(int i=0;i<40;i++)SketchEditOps.apply(d,decision);assertEquals(32,d.getJSONObject("layeredDrawing").getJSONArray("reviews").length());assertEquals(1,d.getJSONObject("layeredDrawing").getJSONArray("reviews").getJSONObject(31).getInt("frameIndex"));assertTrue(SketchLayeredDrawing.summary(d).getJSONArray("stageReviews").getJSONObject(0).getBoolean("acceptable"));assertThrows(IllegalStateException.class,()->SketchLayeredDrawing.animationSource(d));
 }
 @Test public void transitionReplayRejectsUnrecordedMutationAndFrozenGuideTampering()throws Exception{
  JSONObject before=fixture(),after=SketchDocument.copy(before);JSONObject start=begin();SketchEditOps.apply(after,start);SketchLayeredDrawing.requireTransition(before,after,new JSONArray().put(start));
  JSONObject changed=SketchDocument.copy(after);SketchDocument.layer(changed,"actor_construction").getJSONArray("objects").put(SketchEditOps.completeObject(stroke("actor_construction","forged").getJSONObject("object")));JSONObject unrecorded=changed;assertThrows(IllegalStateException.class,()->SketchLayeredDrawing.requireTransition(after,unrecorded,new JSONArray()));
  changed=SketchDocument.copy(after);SketchDocument.object(changed,"guide").getJSONObject("reference").put("sourceSha256","c".repeat(64));JSONObject tampered=changed;assertThrows(IllegalStateException.class,()->SketchLayeredDrawing.validate(tampered));
 }
}
