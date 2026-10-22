package com.rezoxnemesis.videostudio;

import android.content.Context;
import android.graphics.*;
import android.net.Uri;
import org.json.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.Assert.*;

/** PREPARED, NOT EXECUTED. Existing public sketch_edit entry; no new product helper.
 * Synthetic native coverage fixtures verify registration, not grip art or physics. */
@RunWith(RobolectricTestRunner.class) @Config(sdk=33,manifest=Config.NONE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class SketchLayerGroupMotionTest {
    static final int W=512,H=384,F=96;
    // Same native-float congruence bound as the already executed private held-lift case.
    static final double GRID=1d/1024;
    final Context context=RuntimeEnvironment.getApplication();
    interface Work {void run()throws Exception;}
    void worker(Work work)throws Exception {
        ExecutorService lane=Executors.newSingleThreadExecutor();
        try{lane.submit(()->{work.run();return null;}).get(180,TimeUnit.SECONDS);}
        catch(ExecutionException error){Throwable cause=error.getCause();if(cause instanceof Exception)throw (Exception)cause;if(cause instanceof Error)throw (Error)cause;throw error;}
        finally{lane.shutdownNow();}
    }
    String canonical(Object value)throws Exception{return SketchDocument.canonical(new JSONObject(new JSONObject().put("value",value).toString()).get("value"));}
    JSONObject doc(String id)throws Exception {try(SketchProjectStore store=new SketchProjectStore(context)){return store.get(id);}}
    JSONObject edit(String id,String operation,JSONObject... edits)throws Exception {
        JSONArray operations=new JSONArray();for(JSONObject value:edits)operations.put(value);
        return SketchEditOps.execute(context,"sketch_edit",new JSONObject().put("sketchId",id).put("operationId",operation).put("expectedRevision",doc(id).getLong("revision")).put("operations",operations));
    }
    JSONObject request(String id,String operation,JSONObject op)throws Exception{return new JSONObject().put("sketchId",id).put("operationId",operation).put("expectedRevision",doc(id).getLong("revision")).put("operations",new JSONArray().put(op));}
    JSONObject bounds(double x,double y,double w,double h)throws Exception{return new JSONObject().put("x",x).put("y",y).put("width",w).put("height",h);}
    String blank(int frames)throws Exception {
        String id="group_"+UUID.randomUUID();SketchEditOps.execute(context,"sketch_create",new JSONObject().put("sketchId",id).put("operationId","new").put("width",W).put("height",H).put("frameCount",frames).put("fps",24).put("background","#00000000"));return id;
    }
    JSONObject base(int side)throws Exception {
        return side==0?SketchDocument.transform().put("anchorX",.1).put("anchorY",.2).put("x",.03).put("y",.01).put("rotation",12).put("scaleX",.8).put("scaleY",.9):SketchDocument.transform().put("anchorX",.7).put("anchorY",.6).put("x",-.04).put("y",-.05).put("rotation",-17).put("scaleX",1.1).put("scaleY",.75);
    }
    String fixture()throws Exception {
        String id=blank(F);edit(id,"layers",new JSONObject().put("type","add_layer").put("layer",new JSONObject().put("id","fruit")),new JSONObject().put("type","add_layer").put("layer",new JSONObject().put("id","spare")));
        for(int side=0;side<2;side++){
            String layer=side==0?"layer_1":"fruit";JSONObject transform=base(side);Matrix inverse=new Matrix();assertTrue(SketchFrameRenderer.matrix(transform,W,H).invert(inverse));float[] centre={W*.5f,H*.5f};inverse.mapPoints(centre);
            edit(id,"art_"+side,new JSONObject().put("type","set_transform").put("layerId",layer).put("transform",transform),new JSONObject().put("type","add_object").put("layerId",layer).put("object",new JSONObject().put("id","pad_"+side).put("kind","ellipse").put("fill",true).put("width",.0005).put("color",side==0?"#FFFF0000":"#FF0000FF").put("frame",0).put("exposure",F).put("bounds",bounds(centre[0]/W-.025,centre[1]/H-.025,.05,.05))));
        }return id;
    }
    JSONObject pose(int frame,double x,double y,double angle,String interpolation)throws Exception{return new JSONObject().put("frame",frame).put("x",x).put("y",y).put("rotation",angle).put("interpolation",interpolation);}
    JSONObject motion()throws Exception {
        return new JSONObject().put("type","set_layer_group_motion").put("layerIds",new JSONArray().put("layer_1").put("fruit")).put("pivot",new JSONArray().put(.5).put(.5)).put("poses",new JSONArray().put(pose(0,0,0,0,"hold")).put(pose(12,0,0,0,"smooth")).put(pose(36,.025,-.08,-13,"hold")).put(pose(60,.025,-.08,-13,"smooth")).put(pose(84,0,0,0,"hold")).put(pose(95,0,0,0,"hold")));
    }
    JSONObject ramp(int frames)throws Exception {return new JSONObject().put("type","set_layer_group_motion").put("layerIds",new JSONArray().put("layer_1").put("fruit")).put("pivot",new JSONArray().put(.5).put(.5)).put("poses",new JSONArray().put(pose(0,0,0,0,"linear")).put(pose(frames-1,.025,-.08,-13,"hold")));}
    JSONObject zero()throws Exception {return new JSONObject().put("type","set_layer_group_motion").put("layerIds",new JSONArray().put("layer_1").put("fruit")).put("pivot",new JSONArray().put(.5).put(.5)).put("poses",new JSONArray().put(pose(0,0,0,0,"smooth")).put(pose(F-1,0,0,0,"hold")));}

    double amount(int frame){if(frame<=12||frame>=84)return 0;if(frame>=36&&frame<=60)return 1;double t=frame<36?(frame-12)/24d:(frame-60)/24d;double smooth=t*t*(3-2*t);return frame<36?smooth:1-smooth;}
    Matrix common(double amount)throws Exception {Matrix matrix=new Matrix();matrix.setTranslate((float)(W*(.5+.025*amount)),(float)(H*(.5-.08*amount)));matrix.preRotate((float)(-13*amount));matrix.preTranslate(-W*.5f,-H*.5f);return matrix;}
    void registered(JSONObject baseline,JSONObject keyed,String layer,int frame,double amount)throws Exception {
        Matrix expected=new Matrix();expected.setConcat(common(amount),SketchFrameRenderer.matrix(baseline,W,H));Matrix actual=SketchFrameRenderer.matrix(SketchDocument.transformAt(SketchDocument.layer(keyed,layer),frame),W,H);
        float[] a={0,0,W,0,0,H,W,H,W*.5f,H*.5f,W*.23f,H*.71f},b=a.clone();expected.mapPoints(a);actual.mapPoints(b);
        for(int i=0;i<a.length;i+=2)assertTrue("World registration frame="+frame+" layer="+layer+" point="+i/2,Math.hypot(a[i]-b[i],a[i+1]-b[i+1])<=GRID);
    }
    JSONObject keyAt(JSONObject layer,int frame)throws Exception {JSONArray keys=layer.getJSONArray("keys");for(int i=0;i<keys.length();i++)if(keys.getJSONObject(i).getInt("frame")==frame)return keys.getJSONObject(i);throw new AssertionError("Missing dense key "+frame);}
    int[] pixels(JSONObject drawing,int frame)throws Exception {try(SketchFrameRenderer renderer=new SketchFrameRenderer(context,drawing)){Bitmap bitmap=renderer.render(frame,false);try{int[] values=new int[W*H];bitmap.getPixels(values,0,W,0,0,W,H);return values;}finally{bitmap.recycle();}}}
    JSONObject only(JSONObject drawing,String layer)throws Exception {JSONObject copy=SketchDocument.copy(drawing);JSONArray all=copy.getJSONArray("layers");for(int i=0;i<all.length();i++)all.getJSONObject(i).put("visible",all.getJSONObject(i).getString("id").equals(layer));return copy;}
    void unchanged(String id,JSONObject before)throws Exception {assertEquals(canonical(before),canonical(doc(id)));}
    void rejected(String id,JSONObject op)throws Exception {JSONObject before=doc(id);assertThrows(Exception.class,()->edit(id,"reject_"+UUID.randomUUID(),op));unchanged(id,before);}

    @Test public void sharedDenseMotionUsesCanvasPixelsAndPreservesDistinctAnchorsScalesAndRotations()throws Exception {worker(()->{
        String id=fixture();JSONObject before=doc(id);edit(id,"motion",motion());JSONObject after=doc(id);
        for(String layer:new String[]{"layer_1","fruit"}){JSONObject original=SketchDocument.layer(before,layer),actual=SketchDocument.layer(after,layer);assertEquals(canonical(original.getJSONObject("transform")),canonical(actual.getJSONObject("transform")));assertEquals(canonical(original.getJSONArray("objects")),canonical(actual.getJSONArray("objects")));assertEquals(F,actual.getJSONArray("keys").length());for(int f=0;f<F;f++){
            JSONObject key=keyAt(actual,f),t=key.getJSONObject("transform");assertEquals("hold",key.getString("interpolation"));for(String value:new String[]{"anchorX","anchorY","scaleX","scaleY","opacity"})assertEquals(original.getJSONObject("transform").getDouble(value),t.getDouble(value),0);registered(original.getJSONObject("transform"),after,layer,f,amount(f));
        }}assertEquals(canonical(SketchDocument.layer(before,"spare")),canonical(SketchDocument.layer(after,"spare")));assertEquals(F,after.getInt("frameCount"));assertEquals(24,after.getInt("fps"));
    });}
    @Test public void neutralAndReturnedFramesRetainEveryNativeRgbaPixel()throws Exception {worker(()->{
        String id=fixture();JSONObject before=doc(id);int[] neutral=pixels(before,0);edit(id,"motion",motion());JSONObject after=doc(id);for(int f:new int[]{0,6,12,84,90,95})assertArrayEquals("Exact neutral native pixels frame "+f,neutral,pixels(after,f));assertFalse(Arrays.equals(neutral,pixels(after,48)));
    });}
    @Test public void existingContactCoverageStaysTogetherOnEveryIntegerFrameIncludingHoldAndReturn()throws Exception {worker(()->{
        String id=fixture();edit(id,"motion",motion());JSONObject keyed=doc(id),rear=only(keyed,"layer_1"),prop=only(keyed,"fruit");
        try(SketchFrameRenderer a=new SketchFrameRenderer(context,rear);SketchFrameRenderer b=new SketchFrameRenderer(context,prop)){
            for(int f=0;f<F;f++){Bitmap x=a.render(f,false),y=b.render(f,false);try{int[] px=new int[W*H],py=new int[W*H];x.getPixels(px,0,W,0,0,W,H);y.getPixels(py,0,W,0,0,W,H);int overlap=0;for(int p=0;p<px.length;p++)if((px[p]>>>24)>0&&(py[p]>>>24)>0)overlap++;assertTrue("Actual native fixture coverage remains overlapping at frame "+f,overlap>0);}finally{x.recycle();y.recycle();}}
        }
    });}
    @Test public void zeroGroupDeltaRetainsEveryExistingAnimatedNativeFrameAndUnselectedLayer()throws Exception {worker(()->{
        String id=fixture();for(int f:new int[]{0,24,50,95})edit(id,"prior_"+f,new JSONObject().put("type","set_keyframe").put("layerId","layer_1").put("frame",f).put("interpolation","smooth").put("transform",new JSONObject().put("x",.03+f/2000d).put("rotation",12+f/8d)));
        edit(id,"object_motion",new JSONObject().put("type","set_keyframe").put("objectId","pad_1").put("frame",0).put("interpolation","smooth").put("transform",new JSONObject().put("rotation",0)),new JSONObject().put("type","set_keyframe").put("objectId","pad_1").put("frame",95).put("transform",new JSONObject().put("rotation",5)));
        edit(id,"spare",new JSONObject().put("type","set_keyframe").put("layerId","spare").put("frame",10).put("transform",new JSONObject().put("rotation",21)));JSONObject before=doc(id);edit(id,"zero_motion",zero());JSONObject after=doc(id);
        for(int f=0;f<F;f++){assertArrayEquals("Zero group retains the actual source motion frame "+f,pixels(before,f),pixels(after,f));for(String layer:new String[]{"layer_1","fruit"})assertEquals(canonical(SketchDocument.transformAt(SketchDocument.layer(before,layer),f)),canonical(keyAt(SketchDocument.layer(after,layer),f).getJSONObject("transform")));}
        assertEquals(canonical(SketchDocument.layer(before,"spare")),canonical(SketchDocument.layer(after,"spare")));assertEquals(canonical(SketchDocument.layer(before,"fruit").getJSONArray("objects")),canonical(SketchDocument.layer(after,"fruit").getJSONArray("objects")));
    });}
    @Test public void eachFrameUsesItsOriginalAnimatedAnchorScaleRotationAndOpacityBeforeAnyMutation()throws Exception {worker(()->{
        String id=fixture();for(String layer:new String[]{"layer_1","fruit"}){
            edit(id,"first_"+layer,new JSONObject().put("type","set_keyframe").put("layerId",layer).put("frame",0).put("transform",base(layer.equals("layer_1")?0:1)).put("interpolation","smooth"));
            edit(id,"animated_"+layer,new JSONObject().put("type","set_keyframe").put("layerId",layer).put("frame",48).put("transform",new JSONObject().put("x",.08).put("rotation",23).put("anchorX",.65).put("anchorY",.32).put("scaleX",.73).put("scaleY",1.12).put("opacity",.4)).put("interpolation","linear"));
            edit(id,"fade_"+layer,new JSONObject().put("type","set_keyframe").put("layerId",layer).put("frame",95).put("transform",new JSONObject().put("opacity",0)));
        }
        JSONObject before=doc(id);edit(id,"motion",ramp(F));JSONObject after=doc(id);for(String layer:new String[]{"layer_1","fruit"})for(int f=0;f<F;f++){
            JSONObject sampled=SketchDocument.transformAt(SketchDocument.layer(before,layer),f),actual=keyAt(SketchDocument.layer(after,layer),f).getJSONObject("transform");registered(sampled,after,layer,f,f/(double)(F-1));for(String field:new String[]{"anchorX","anchorY","scaleX","scaleY","opacity"})assertEquals("Original per-frame "+field,sampled.getDouble(field),actual.getDouble(field),0);
        }
    });}
    @Test public void oneNativeTransactionHasOneUndoRedoAndImmutableReceiptReplay()throws Exception {worker(()->{
        String id=fixture();JSONObject before=doc(id),request=request(id,"motion",motion());JSONObject receipt=SketchEditOps.execute(context,"sketch_edit",request),keyed=doc(id);
        SketchEditOps.execute(context,"sketch_undo",new JSONObject().put("sketchId",id).put("operationId","undo").put("expectedRevision",keyed.getLong("revision")));assertEquals(canonical(before.getJSONArray("layers")),canonical(doc(id).getJSONArray("layers")));
        SketchEditOps.execute(context,"sketch_redo",new JSONObject().put("sketchId",id).put("operationId","redo").put("expectedRevision",doc(id).getLong("revision")));assertEquals(canonical(keyed.getJSONArray("layers")),canonical(doc(id).getJSONArray("layers")));JSONObject replaySource=doc(id);assertEquals(canonical(receipt),canonical(SketchEditOps.execute(context,"sketch_edit",request)));unchanged(id,replaySource);
    });}
    @Test public void oneLockedHiddenOrZeroOpacityLayerRejectsTheWholeGroup()throws Exception {worker(()->{
        for(String field:new String[]{"locked","visible","opacity"}){String id=fixture();JSONObject patch=new JSONObject().put(field,field.equals("locked")?true:field.equals("visible")?false:0);edit(id,"guard",new JSONObject().put("type","update_layer").put("layerId","fruit").put("patch",patch));rejected(id,motion());}
    });}
    @Test public void strictSelectionsPosesPivotClockAndFieldsRejectWithoutPartialWrites()throws Exception {worker(()->{
        String id=fixture();ArrayList<JSONObject> invalid=new ArrayList<>();invalid.add(motion().put("layerIds",new JSONArray().put("fruit").put("fruit")));invalid.add(motion().put("layerIds",new JSONArray().put("fruit").put("unknown")));invalid.add(motion().put("layerIds",new JSONArray().put("fruit")));invalid.add(motion().put("pivot",new JSONArray().put(1.1).put(.5)));invalid.add(motion().put("referenceFrame",0));invalid.add(motion().put("scale",1));
        JSONObject bad=motion();bad.getJSONArray("poses").getJSONObject(1).put("frame",0);invalid.add(bad);bad=motion();bad.getJSONArray("poses").getJSONObject(0).put("x","0");invalid.add(bad);bad=motion();bad.getJSONArray("poses").getJSONObject(5).put("frame",F);invalid.add(bad);bad=motion();bad.getJSONArray("poses").getJSONObject(0).put("frame",1);invalid.add(bad);bad=motion();bad.getJSONArray("poses").getJSONObject(5).put("frame",F-2);invalid.add(bad);for(JSONObject operation:invalid)rejected(id,operation);
    });}
    @Test public void staleRevisionAndChangedOperationFingerprintCannotWriteAdditionalKeys()throws Exception {worker(()->{
        String id=fixture();JSONObject stale=request(id,"stale",motion());edit(id,"other",new JSONObject().put("type","set_transform").put("layerId","spare").put("transform",new JSONObject().put("rotation",1)));JSONObject current=doc(id);assertThrows(IllegalStateException.class,()->SketchEditOps.execute(context,"sketch_edit",stale));unchanged(id,current);
        JSONObject fresh=request(id,"stable",motion());SketchEditOps.execute(context,"sketch_edit",fresh);JSONObject keyed=doc(id),conflict=SketchDocument.copy(fresh);conflict.getJSONArray("operations").getJSONObject(0).getJSONArray("poses").getJSONObject(2).put("rotation",-12);assertThrows(IllegalStateException.class,()->SketchEditOps.execute(context,"sketch_edit",conflict));unchanged(id,keyed);
    });}
    @Test public void denseOutputCountsExistingObjectKeysAndRejectsBeforeDirectOrPersistedMutation()throws Exception {worker(()->{
        String id=blank(240);JSONArray selected=new JSONArray().put("layer_1");for(int n=1;n<18;n++){String layer="member_"+n;selected.put(layer);edit(id,"layer_"+n,new JSONObject().put("type","add_layer").put("layer",new JSONObject().put("id",layer)));}
        for(int n=0;n<selected.length();n++)edit(id,"content_"+n,new JSONObject().put("type","add_object").put("layerId",selected.getString(n)).put("object",new JSONObject().put("id","mark_"+n).put("kind","rect").put("fill",true).put("exposure",240).put("bounds",bounds(.4,.4,.03,.03))));
        JSONObject op=ramp(240).put("layerIds",selected);JSONObject before=doc(id),detached=SketchDocument.copy(before);assertThrows(Exception.class,()->SketchEditOps.apply(detached,op));assertEquals("Preflight does not even partly edit the detached candidate",canonical(before),canonical(detached));rejected(id,op);
        // Actual 4096-key total, including pre-existing object keys: 3900 + 2*240 >4096.
        String second=blank(240);edit(second,"fruit",new JSONObject().put("type","add_layer").put("layer",new JSONObject().put("id","fruit")));JSONArray objects=new JSONArray();for(int n=0;n<20;n++){JSONArray keys=new JSONArray();for(int f=0;f<195;f++)keys.put(new JSONObject().put("frame",f).put("transform",SketchDocument.transform()).put("interpolation","hold"));objects.put(new JSONObject().put("id","existing_"+n).put("kind","rect").put("bounds",bounds(.2,.2,.1,.1)).put("keys",keys));}edit(second,"existing_layer",new JSONObject().put("type","add_layer").put("layer",new JSONObject().put("id","other")));
        for(int first=0;first<objects.length();first+=4){JSONObject[] batch=new JSONObject[Math.min(4,objects.length()-first)];for(int n=0;n<batch.length;n++)batch[n]=new JSONObject().put("type","add_object").put("layerId","other").put("object",objects.getJSONObject(first+n));edit(second,"existing_objects_"+first,batch);}for(String layer:new String[]{"layer_1","fruit"})edit(second,"content_"+layer,new JSONObject().put("type","add_object").put("layerId",layer).put("object",new JSONObject().put("id","content_"+layer).put("kind","rect").put("fill",true).put("exposure",240).put("bounds",bounds(.4,.4,.03,.03))));rejected(second,ramp(240));
        String tooLong=blank(241);edit(tooLong,"fruit",new JSONObject().put("type","add_layer").put("layer",new JSONObject().put("id","fruit")));rejected(tooLong,ramp(241));
    });}
    String workflow()throws Exception {
        String id=blank(F);File file=new File(context.getCacheDir(),id+".png");Bitmap image=Bitmap.createBitmap(8,8,Bitmap.Config.ARGB_8888);try{image.eraseColor(Color.WHITE);try(FileOutputStream out=new FileOutputStream(file)){assertTrue(image.compress(Bitmap.CompressFormat.PNG,100,out));}}finally{image.recycle();}
        ProjectStore projects=new ProjectStore(context);ProjectStore.Project project=projects.create("Native group guard fixture");ProjectStore.Asset asset=projects.registerImportedAsset(project.id,Uri.fromFile(file),file.getName(),"image/png",0,file.length());
        SketchEditOps.execute(context,"sketch_import_reference",new JSONObject().put("sketchId",id).put("operationId","guide").put("expectedRevision",doc(id).getLong("revision")).put("projectId",project.id).put("sourceProjectUpdatedAt",projects.get(project.id).updatedAt).put("assetId",asset.id).put("layerId","layer_1").put("objectId","guide").put("guideVisible",false));
        edit(id,"begin",new JSONObject().put("type","layered_drawing_begin").put("referenceObjectId","guide").put("prefix","actor").put("parts",new JSONArray("[{id:'head',name:'Head'},{id:'body',name:'Body'}]")));
        for(String stage:new String[]{"construction","refinement","details"}){
            if(!stage.equals("construction"))edit(id,"stage_"+stage,new JSONObject().put("type","layered_drawing_stage").put("stage",stage));edit(id,"ink_"+stage,mark("actor_"+stage,"mark_"+stage));edit(id,"review_"+stage,new JSONObject().put("type","layered_drawing_review").put("stage",stage).put("frameIndex",0).put("reviewedRevision",doc(id).getLong("revision")).put("status","ACCEPTABLE").put("note","Synthetic native fixture review"));
        }edit(id,"clean",new JSONObject().put("type","layered_drawing_stage").put("stage","clean_line"));edit(id,"clean_ink",mark("actor_head","head_mark"),mark("actor_body","body_mark"));return id;
    }
    JSONObject mark(String layer,String id)throws Exception{return new JSONObject().put("type","add_object").put("layerId",layer).put("object",new JSONObject().put("id",id).put("kind","stroke").put("exposure",F).put("points",new JSONArray("[[.3,.3,1],[.5,.6,1],[.7,.3,1]]")));}
    @Test public void frozenLayeredWorkflowAdmitsOnlyActivePartsAndPreservesGuideClockAndSourceMarks()throws Exception {worker(()->{
        String id=workflow();JSONObject before=doc(id);JSONObject allowed=motion().put("layerIds",new JSONArray().put("actor_head").put("actor_body"));edit(id,"group",allowed);JSONObject after=doc(id);assertEquals(canonical(SketchDocument.layer(before,"layer_1")),canonical(SketchDocument.layer(after,"layer_1")));assertEquals(F,after.getInt("frameCount"));for(String layer:new String[]{"actor_head","actor_body"}){assertEquals(F,SketchDocument.layer(after,layer).getJSONArray("keys").length());assertEquals(canonical(SketchDocument.layer(before,layer).getJSONArray("objects")),canonical(SketchDocument.layer(after,layer).getJSONArray("objects")));}
        rejected(id,motion().put("layerIds",new JSONArray().put("actor_head").put("layer_1")));rejected(id,motion().put("layerIds",new JSONArray().put("actor_head").put("actor_construction")));JSONObject outside=motion().put("layerIds",new JSONArray().put("actor_head").put("actor_body"));outside.getJSONArray("poses").getJSONObject(5).put("frame",F);rejected(id,outside);
    });}
    @Test public void bookPageUsesItsOneFrameLocalClockAndNeverExtendsTheSequence()throws Exception {worker(()->{
        String book="group_book_"+UUID.randomUUID();SketchEditOps.execute(context,"sketch_book_create",new JSONObject().put("bookId",book).put("operationId","new").put("pageCount",2).put("width",W).put("height",H));JSONObject page=SketchEditOps.execute(context,"sketch_book_open_page",new JSONObject().put("bookId",book).put("page",0));String id=page.getString("sketchId");edit(id,"fruit",new JSONObject().put("type","add_layer").put("layer",new JSONObject().put("id","fruit")));JSONObject beforeBook=SketchEditOps.execute(context,"sketch_book_get",new JSONObject().put("bookId",book));rejected(id,ramp(2));assertEquals(1,doc(id).getInt("frameCount"));assertEquals(canonical(beforeBook),canonical(SketchEditOps.execute(context,"sketch_book_get",new JSONObject().put("bookId",book))));
    });}
    @Test public void portableArchiveRetainsOnlyOrdinaryKeysAndExactNativeFrames()throws Exception {worker(()->{
        String id=fixture();edit(id,"motion",motion());JSONObject keyed=doc(id);SketchPaintArchive.Prepared archive=SketchPaintArchive.prepare(context,new JSONObject().put("sketchId",id).put("expectedRevision",keyed.getLong("revision")).put("operationId","archive"));JSONObject restored=SketchPaintArchive.restoreFile(context,archive.file,SketchProjectStore.fileHash(archive.file),"restore_"+UUID.randomUUID(),"Native group ordinary keys");JSONObject after=doc(restored.getString("sketchId"));assertEquals(canonical(keyed.getJSONArray("layers")),canonical(after.getJSONArray("layers")));for(int f:new int[]{0,13,35,48,61,83,95})assertArrayEquals("Portable native pixels frame "+f,pixels(keyed,f),pixels(after,f));for(String layer:new String[]{"layer_1","fruit"})for(int f=0;f<F;f++)SketchDocument.fields(keyAt(SketchDocument.layer(after,layer),f),"frame,transform,interpolation,curve");
    });}
    @Test public void existingSingleLayerKeyOperationRetainsItsOmittedAndExplicitBehavior()throws Exception {worker(()->{
        String id=fixture();JSONObject before=doc(id);edit(id,"legacy",new JSONObject().put("type","set_keyframe").put("layerId","layer_1").put("frame",24).put("transform",new JSONObject().put("rotation",31)));JSONObject after=doc(id),key=keyAt(SketchDocument.layer(after,"layer_1"),24);assertEquals("linear",key.getString("interpolation"));assertEquals(31,key.getJSONObject("transform").getDouble("rotation"),0);for(String field:new String[]{"x","y","scaleX","scaleY","anchorX","anchorY","opacity"})assertEquals(SketchDocument.layer(before,"layer_1").getJSONObject("transform").getDouble(field),key.getJSONObject("transform").getDouble(field),0);assertEquals(canonical(SketchDocument.layer(before,"fruit")),canonical(SketchDocument.layer(after,"fruit")));
    });}
}
