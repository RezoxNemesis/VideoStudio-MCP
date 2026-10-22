package com.rezoxnemesis.videostudio;

import android.content.Context;
import android.graphics.*;
import org.json.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.Assert.*;

/** Existing preview/cel/component/archive routes must keep the selected full-visible owner pass. */
@RunWith(RobolectricTestRunner.class) @Config(sdk=33,manifest=Config.NONE) @GraphicsMode(GraphicsMode.Mode.NATIVE)
public class SketchHandPartIntegrationTest {
 final Context context=RuntimeEnvironment.getApplication();
 @Test public void actualPreviewGridAndDirectTransformedCanvasRecomposeWithoutNativePixelSlack()throws Exception{
  for(int c:new int[]{0,2,5}){JSONObject complete=SketchHandPartTestSupport.document(SketchHandPartTestSupport.cases().getJSONObject(c),null),split=SketchHandPartTestSupport.split(complete);
   try(SketchFrameRenderer a=new SketchFrameRenderer(context,complete);SketchFrameRenderer b=new SketchFrameRenderer(context,split)){
    for(int dimension:new int[]{256,719})for(boolean paper:new boolean[]{false,true}){JSONObject crop=new JSONObject().put("x",.24).put("y",.23).put("width",.53).put("height",.65);Bitmap x=a.render(36,paper,dimension,crop),y=b.render(36,paper,dimension,crop);try{assertTrue("Each actual cropped output grid must reconstruct exactly at"+dimension,x.sameAs(y));}finally{x.recycle();y.recycle();}}
    Bitmap x=Bitmap.createBitmap(613,577,Bitmap.Config.ARGB_8888),y=Bitmap.createBitmap(613,577,Bitmap.Config.ARGB_8888);try{for(Bitmap image:new Bitmap[]{x,y}){image.setDensity(320);image.eraseColor(0xff384c61);}Canvas left=new Canvas(x),right=new Canvas(y);for(Canvas canvas:new Canvas[]{left,right}){canvas.translate(37.25f,-11.375f);canvas.rotate(11.7f,256,256);canvas.scale(.91f,1.07f);}a.draw(left,36,false);b.draw(right,36,false);assertTrue("Direct Canvas matrix and colored backdrop retain exact source-over",x.sameAs(y));}finally{x.recycle();y.recycle();}
   }
  }
 }
 @Test public void partialSharedLayerIsRejectedAndPermittedUnderpaintingOpacityRecompositionRemainsPixelExact()throws Exception{
  for(boolean sameLayer:new boolean[]{true,false}){
   JSONObject all=SketchHandPartTestSupport.document(SketchHandPartTestSupport.cases().getJSONObject(0),null);JSONObject hand=SketchDocument.object(all,"hand_rig");hand.getJSONObject("transform").put("opacity",.43);JSONArray keys=hand.getJSONArray("keys");for(int i=0;i<keys.length();i++)keys.getJSONObject(i).getJSONObject("transform").put("opacity",.43);
   JSONObject split=SketchHandPartTestSupport.split(all),under=SketchEditOps.completeObject(new JSONObject().put("id","underpaint").put("kind","rect").put("frame",0).put("exposure",96).put("fill",true).put("color","#FF35516B").put("bounds",new JSONObject().put("x",.02).put("y",.02).put("width",.96).put("height",.96)));
   for(JSONObject d:new JSONObject[]{all,split}){
    JSONArray layers=d.getJSONArray("layers");JSONObject rear=layers.getJSONObject(0);
    if(sameLayer){JSONArray objects=new JSONArray().put(SketchDocument.copy(under));JSONArray old=rear.getJSONArray("objects");for(int j=0;j<old.length();j++)objects.put(old.getJSONObject(j));rear.put("objects",objects);}
    else{for(int j=0;j<layers.length();j++)layers.getJSONObject(j).put("opacity",.73);JSONObject bottom=SketchDocument.copy(rear).put("id","underpaint_layer").put("opacity",1).put("transform",SketchDocument.transform()).put("keys",new JSONArray()).put("objects",new JSONArray().put(SketchDocument.copy(under)));JSONArray newLayers=new JSONArray().put(bottom);for(int j=0;j<layers.length();j++)newLayers.put(layers.getJSONObject(j));d.put("layers",newLayers);}
    if(sameLayer&&d==split){Exception failure=assertThrows(Exception.class,()->SketchDocument.validate(d));assertTrue("Publication rejects shared-layer isolation before rendering",failure.getMessage().contains("own layer"));}else SketchDocument.validate(d);
   }
   if(sameLayer)continue;
   try(SketchFrameRenderer a=new SketchFrameRenderer(context,all);SketchFrameRenderer b=new SketchFrameRenderer(context,split)){for(int f:new int[]{0,36})same(a.render(f,false),b.render(f,false),"Native pixel recomposition with object opacity .43 and opaque underpaint; sameLayer="+sameLayer+" frame"+f);}
  }
 }
 @Test public void nativeMixedLayerAddAndPatchRejectAtomicallyWhileExplicitAllUndoRedoKeepsLegacySharing()throws Exception{
  String id="hand_mixed_"+UUID.randomUUID();SketchEditOps.execute(context,"sketch_create",new JSONObject().put("sketchId",id).put("operationId","create").put("width",512).put("height",512).put("frameCount",96).put("fps",24));
  JSONObject rect=new JSONObject().put("id","underpaint").put("kind","rect").put("frame",0).put("exposure",96).put("fill",true).put("color","#FF35516B").put("bounds",new JSONObject().put("x",.02).put("y",.02).put("width",.96).put("height",.96));edit(id,1,"underpaint",new JSONObject().put("type","add_object").put("layerId","layer_1").put("object",rect));
  JSONObject c=SketchHandPartTestSupport.cases().getJSONObject(0),op=new JSONObject().put("type","add_hand_animation").put("layerId","layer_1").put("handId","hand").put("settings",c.getJSONObject("settings")).put("poses",c.getJSONArray("poses")).put("parts","digits");String before=SketchDocument.canonical(load(id));Exception rejected=assertThrows(Exception.class,()->edit(id,2,"reject_partial_add",op));assertTrue(rejected.getMessage().contains("own layer"));assertEquals("Failed constructor cannot publish geometry/revision",before,SketchDocument.canonical(load(id)));
  op.put("parts","all");edit(id,2,"complete_shared",op);JSONObject complete=load(id);assertEquals(2,SketchDocument.layer(complete,"layer_1").getJSONArray("objects").length());JSONObject partial=SketchDocument.copy(SketchDocument.object(complete,"hand_rig").getJSONObject("hand")).put("parts","digits");String completeBefore=SketchDocument.canonical(complete);Exception badPatch=assertThrows(Exception.class,()->edit(id,3,"reject_partial_patch",new JSONObject().put("type","update_object").put("objectId","hand_rig").put("patch",new JSONObject().put("hand",partial))));assertTrue(badPatch.getMessage().contains("own layer"));assertEquals("Failed retained patch cannot publish",completeBefore,SketchDocument.canonical(load(id)));
  SketchEditOps.execute(context,"sketch_undo",new JSONObject().put("sketchId",id).put("expectedRevision",3).put("operationId","undo_complete"));assertEquals(1,SketchDocument.layer(load(id),"layer_1").getJSONArray("objects").length());SketchEditOps.execute(context,"sketch_redo",new JSONObject().put("sketchId",id).put("expectedRevision",4).put("operationId","redo_complete"));assertEquals("all",SketchDocument.object(load(id),"hand_rig").getJSONObject("hand").getString("parts"));
 }
 @Test public void retainedSampledCelUndoComponentAndPortableArchiveKeepPartsAndNativeFrames()throws Exception{
  String id="hand_pass_"+UUID.randomUUID();SketchEditOps.execute(context,"sketch_create",new JSONObject().put("sketchId",id).put("operationId","create").put("width",512).put("height",512).put("frameCount",96).put("fps",24));
  JSONObject c=SketchHandPartTestSupport.cases().getJSONObject(0);JSONObject fixture=SketchHandPartTestSupport.document(c,"digits"),object=SketchDocument.copy(SketchDocument.object(fixture,"hand_rig"));edit(id,1,"add",new JSONObject().put("type","add_object").put("layerId","layer_1").put("object",object));JSONObject original=load(id);
  JSONObject hand=SketchDocument.copy(object.getJSONObject("hand"));hand.put("parts","palm_forearm");edit(id,2,"switch",new JSONObject().put("type","update_object").put("objectId","hand_rig").put("patch",new JSONObject().put("hand",hand)));assertEquals("palm_forearm",SketchDocument.object(load(id),"hand_rig").getJSONObject("hand").getString("parts"));SketchEditOps.execute(context,"sketch_undo",new JSONObject().put("sketchId",id).put("expectedRevision",3).put("operationId","undo"));assertEquals("digits",SketchDocument.object(load(id),"hand_rig").getJSONObject("hand").getString("parts"));
  JSONObject sampled=SketchHandAnimation.sampled(object,36);assertEquals("hand",sampled.getString("kind"));assertEquals("digits",sampled.getJSONObject("hand").getString("parts"));JSONObject frozen=SketchDocument.copy(original);SketchDocument.layer(frozen,"layer_1").put("objects",new JSONArray().put(sampled));SketchDocument.validate(frozen);try(SketchFrameRenderer a=new SketchFrameRenderer(context,original);SketchFrameRenderer b=new SketchFrameRenderer(context,frozen)){same(a.render(36,false),b.render(36,false),"Retained sampled hand preserves selected pass and original transform");}
  String component="pass_component_"+UUID.randomUUID();SketchComponentLibrary.execute(context,"sketch_component_save",new JSONObject().put("componentId",component).put("sketchId",id).put("expectedRevision",4).put("operationId","save").put("name","Source-free visible digits").put("category","pose").put("frameIndex",0));String target="pass_target_"+UUID.randomUUID();SketchEditOps.execute(context,"sketch_create",new JSONObject().put("sketchId",target).put("operationId","create").put("width",512).put("height",512).put("frameCount",96).put("fps",24));SketchComponentLibrary.execute(context,"sketch_component_apply",new JSONObject().put("componentId",component).put("sketchId",target).put("expectedRevision",1).put("operationId","apply").put("frameIndex",0));JSONObject placed=load(target);assertEquals("digits",placed.getJSONArray("layers").getJSONObject(1).getJSONArray("objects").getJSONObject(0).getJSONObject("hand").getString("parts"));try(SketchFrameRenderer a=new SketchFrameRenderer(context,original);SketchFrameRenderer b=new SketchFrameRenderer(context,placed)){same(a.render(0,false),b.render(0,false),"Component snapshot keeps actual selected native pixels");}
  ExecutorService worker=Executors.newSingleThreadExecutor();SketchPaintArchive.Prepared archive;try{archive=worker.submit(()->SketchPaintArchive.prepare(context,new JSONObject().put("sketchId",id).put("expectedRevision",4).put("operationId","archive"))).get(60,TimeUnit.SECONDS);}finally{worker.shutdownNow();}JSONObject receipt=SketchPaintArchive.restoreFile(context,archive.file,SketchProjectStore.fileHash(archive.file),"restore_"+UUID.randomUUID(),"Restored hand pass"),restored=load(receipt.getString("sketchId"));assertEquals("digits",SketchDocument.object(restored,"hand_rig").getJSONObject("hand").getString("parts"));try(SketchFrameRenderer a=new SketchFrameRenderer(context,original);SketchFrameRenderer b=new SketchFrameRenderer(context,restored)){for(int f:new int[]{0,36,95})same(a.render(f,false),b.render(f,false),"Portable pass frame"+f);}
 }
 @Test public void nativeCapabilityAndPixelBudgetAreExplicitAndCancellationDoesNotMutateCanvas()throws Exception{
  JSONObject cap=SketchCapabilities.handAnimation().getJSONObject("partPasses");assertEquals("4.10.15",cap.getString("minimumNativeVersion"));assertEquals("parts",cap.getString("field"));assertEquals("[\"all\",\"palm_forearm\",\"digits\"]",cap.getJSONArray("choices").toString());assertEquals("full_hand_before_part_selection",cap.getString("visibility"));assertTrue(cap.getBoolean("requiresOwnLayer"));assertFalse(cap.getBoolean("automaticContact"));assertFalse(cap.getBoolean("recoversUnseenAnatomy"));
  Bitmap oversized=Bitmap.createBitmap(2049,2048,Bitmap.Config.ALPHA_8);try{assertThrows(Exception.class,()->SketchHandPartClip.draw(new Canvas(oversized),new Path[]{new Path(),new Path()},1,true,c->{}));}finally{oversized.recycle();}
  Bitmap bitmap=Bitmap.createBitmap(32,32,Bitmap.Config.ARGB_8888);try{Canvas canvas=new Canvas(bitmap);Thread.currentThread().interrupt();assertThrows(InterruptedException.class,()->SketchHandPartClip.draw(canvas,new Path[]{new Path(),new Path()},1,true,c->{}));assertTrue(Thread.interrupted());assertEquals(0,bitmap.getPixel(0,0));assertEquals(new Rect(0,0,32,32),canvas.getClipBounds());}finally{Thread.interrupted();bitmap.recycle();}
 }
 private static void same(Bitmap a,Bitmap b,String message){try{assertTrue(message,a.sameAs(b));}finally{a.recycle();b.recycle();}}
 JSONObject load(String id)throws Exception{try(SketchProjectStore store=new SketchProjectStore(context)){return store.get(id);}}
 void edit(String id,long revision,String operationId,JSONObject operation)throws Exception{SketchEditOps.execute(context,"sketch_edit",new JSONObject().put("sketchId",id).put("expectedRevision",revision).put("operationId",operationId).put("operations",new JSONArray().put(operation)));}
}
