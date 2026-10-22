package com.rezoxnemesis.videostudio;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.net.Uri;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import java.io.File;
import java.io.FileOutputStream;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import static org.junit.Assert.*;

/** Reusing an authored cel is not importing reference pixels or recovering an unseen pose. */
@RunWith(RobolectricTestRunner.class) @Config(sdk=33,manifest=Config.NONE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class SketchAuthoredCelReuseTest {
    final Context context=RuntimeEnvironment.getApplication();
    interface Work { void run() throws Exception; }
    void worker(Work work)throws Exception {
        ExecutorService lane=Executors.newSingleThreadExecutor();
        try{lane.submit(()->{try{work.run();return null;}catch(Exception error){throw new RuntimeException(error);}}).get(120,TimeUnit.SECONDS);}
        finally{lane.shutdownNow();}
    }
    final class Fixture {
        final String id="authored_copy_"+UUID.randomUUID(); int serial;
        Fixture(boolean paint)throws Exception {
            File file=new File(context.getCacheDir(),id+".png");Bitmap guide=Bitmap.createBitmap(32,32,Bitmap.Config.ARGB_8888);
            try{guide.eraseColor(Color.RED);try(FileOutputStream out=new FileOutputStream(file)){assertTrue(guide.compress(Bitmap.CompressFormat.PNG,100,out));}}finally{guide.recycle();}
            ProjectStore projects=new ProjectStore(context);ProjectStore.Project project=projects.create("Hidden copy guard");
            ProjectStore.Asset asset=projects.registerImportedAsset(project.id,Uri.fromFile(file),file.getName(),"image/png",0,file.length());
            SketchEditOps.execute(context,"sketch_create",new JSONObject().put("sketchId",id).put("operationId","new").put("width",256).put("height",256).put("frameCount",3).put("background","#00000000"));
            SketchEditOps.execute(context,"sketch_import_reference",new JSONObject().put("sketchId",id).put("operationId","guide").put("expectedRevision",1).put("projectId",project.id).put("sourceProjectUpdatedAt",projects.get(project.id).updatedAt).put("assetId",asset.id).put("layerId","layer_1").put("objectId","guide").put("guideVisible",false));
            edit(new JSONObject().put("type","layered_drawing_begin").put("referenceObjectId","guide").put("prefix","actor").put("parts",new JSONArray("[{id:'head',name:'Head'},{id:'body',name:'Body'}]")));
            if(paint){edit(new JSONObject().put("type","add_paint_cel").put("layerId","actor_construction").put("objectId","paint").put("exposure",3));edit(paint("paint","first",.5,.5,"#FF143A69"));}
            else edit(new JSONObject().put("type","add_object").put("layerId","actor_construction").put("object",new JSONObject().put("id","ink").put("kind","path").put("color","#FF17212D").put("width",.013).put("exposure",3).put("commands",new JSONArray("[['M',.25,.65],['C',.24,.24,.75,.24,.76,.65]]"))));
        }
        JSONObject doc()throws Exception {try(SketchProjectStore store=new SketchProjectStore(context)){return store.get(id);}}
        JSONObject args(JSONObject op)throws Exception {return new JSONObject().put("sketchId",id).put("expectedRevision",doc().getLong("revision")).put("operationId","step_"+(serial++)).put("operations",new JSONArray().put(op));}
        JSONObject edit(JSONObject op)throws Exception {return SketchEditOps.execute(context,"sketch_edit",args(op));}
        void sameDocument(JSONObject before)throws Exception {assertEquals(SketchDocument.canonical(before),SketchDocument.canonical(doc()));}
    }
    static JSONObject copy(String layer,int from,int to,String prefix)throws Exception {return new JSONObject().put("type","duplicate_cel").put("layerId",layer).put("fromFrame",from).put("toFrame",to).put("idPrefix",prefix);}
    static JSONObject paint(String object,String stroke,double x,double y,String color)throws Exception {return new JSONObject().put("type","paint_stroke").put("objectId",object).put("strokeId",stroke).put("points",new JSONArray().put(new JSONArray().put(x).put(y).put(1))).put("brush",new JSONObject().put("mode","paint").put("color",color).put("sizePx",30).put("hardness",1).put("pressure",false));}
    int[] pixels(JSONObject document,int frame)throws Exception {try(SketchFrameRenderer renderer=new SketchFrameRenderer(context,document)){Bitmap bitmap=renderer.render(frame,false);try{int[] pixels=new int[256*256];bitmap.getPixels(pixels,0,256,0,0,256,256);return pixels;}finally{bitmap.recycle();}}}
    @Test public void copiedAuthoredOutlineIsExactIndependentAndDoesNotRevealTheGuide()throws Exception {worker(()->{
        Fixture f=new Fixture(false);JSONObject before=f.doc();int[] original=pixels(before,0);String guide=SketchDocument.canonical(SketchDocument.layer(before,"layer_1"));
        JSONObject request=f.args(copy("actor_construction",0,1,"outline")),receipt=SketchEditOps.execute(context,"sketch_edit",request),after=f.doc();
        assertEquals(SketchDocument.canonical(receipt),SketchDocument.canonical(SketchEditOps.execute(context,"sketch_edit",request)));
        assertArrayEquals(original,pixels(after,0));assertArrayEquals(original,pixels(after,1));assertArrayEquals(original,pixels(after,2));
        assertEquals(guide,SketchDocument.canonical(SketchDocument.layer(after,"layer_1")));assertEquals(3,after.getInt("frameCount"));
        f.edit(new JSONObject().put("type","update_object").put("objectId","outline_0").put("patch",new JSONObject().put("commands",new JSONArray("[['M',.25,.65],['C',.33,.35,.66,.35,.76,.65]]"))));
        assertArrayEquals(original,pixels(f.doc(),0));assertFalse(java.util.Arrays.equals(original,pixels(f.doc(),1)));assertArrayEquals(original,pixels(f.doc(),2));
    });}
    @Test public void nativePaintCopyKeepsAncestryIndependentEditsUndoAndPortableArchive()throws Exception {worker(()->{
        Fixture f=new Fixture(true);JSONObject before=f.doc();int[] original=pixels(before,0);String snapshot=SketchDocument.object(before,"paint").getJSONObject("paint").getString("sha256");
        f.edit(copy("actor_construction",0,1,"paint_copy"));JSONObject copied=f.doc();assertEquals(snapshot,SketchDocument.object(copied,"paint_copy_0").getJSONObject("paint").getString("sha256"));assertArrayEquals(original,pixels(copied,1));
        assertThrows(IllegalStateException.class,()->f.edit(paint("paint_copy_0","first",.65,.5,"#FF6A2349")));f.sameDocument(copied);
        f.edit(paint("paint_copy_0","second",.65,.5,"#FF6A2349"));JSONObject changed=f.doc();assertArrayEquals(original,pixels(changed,0));assertFalse(java.util.Arrays.equals(original,pixels(changed,1)));assertArrayEquals(original,pixels(changed,2));
        SketchPaintArchive.Prepared archive=SketchPaintArchive.prepare(context,new JSONObject().put("sketchId",f.id).put("expectedRevision",changed.getLong("revision")).put("operationId","archive"));
        JSONObject restored=SketchPaintArchive.restoreFile(context,archive.file,SketchProjectStore.fileHash(archive.file),"restored_"+UUID.randomUUID(),"Copied authored cels");
        try(SketchProjectStore store=new SketchProjectStore(context)){JSONObject reopened=store.get(restored.getString("sketchId"));for(int frame=0;frame<3;frame++)assertArrayEquals(pixels(changed,frame),pixels(reopened,frame));}
        SketchEditOps.execute(context,"sketch_undo",new JSONObject().put("sketchId",f.id).put("expectedRevision",f.doc().getLong("revision")).put("operationId","undo"));assertArrayEquals(original,pixels(f.doc(),1));
        SketchEditOps.execute(context,"sketch_redo",new JSONObject().put("sketchId",f.id).put("expectedRevision",f.doc().getLong("revision")).put("operationId","redo"));assertArrayEquals(pixels(changed,1),pixels(f.doc(),1));
        f.edit(copy("actor_construction",1,2,"again"));assertArrayEquals(pixels(changed,1),pixels(f.doc(),2));assertArrayEquals(original,pixels(f.doc(),0));
    });}
    @Test public void originalSessionMayReuseItsProtectedAuthoredStageButNotTraceOrClone()throws Exception {worker(()->{
        Fixture f=new Fixture(false);SketchEditOps.execute(context,"sketch_live_begin",new JSONObject().put("sketchId",f.id).put("expectedRevision",f.doc().getLong("revision")).put("operationId","original").put("drawingMethod","original_strokes"));
        JSONObject before=f.doc();f.edit(copy("actor_construction",0,1,"original_copy"));assertArrayEquals(pixels(before,0),pixels(f.doc(),1));
        JSONObject current=f.doc();assertThrows(IllegalStateException.class,()->f.edit(new JSONObject().put("type","trace_reference").put("layerId","actor_construction")));f.sameDocument(current);
        assertThrows(IllegalStateException.class,()->f.edit(paint("original_copy_0","clone",.5,.5,"#FF000000").put("cloneSource",new JSONObject())));f.sameDocument(current);
    });}
    @Test public void copyRejectsProtectedInactiveLockedHiddenAndOutOfClockDestinations()throws Exception {worker(()->{
        Fixture f=new Fixture(false);JSONObject before=f.doc();
        for(JSONObject operation:new JSONObject[]{copy("layer_1",0,1,"guide_copy"),copy("actor_refinement",0,1,"inactive"),copy("actor_construction",0,3,"extend")}){assertThrows(IllegalStateException.class,()->f.edit(operation));f.sameDocument(before);}
        for(String field:new String[]{"locked","visible"}){JSONObject candidate=SketchDocument.copy(before);SketchDocument.layer(candidate,"actor_construction").put(field,"locked".equals(field));assertThrows(IllegalStateException.class,()->SketchEditOps.apply(candidate,copy("actor_construction",0,1,"blocked")));}
    });}
    @Test public void restoredCloneAncestryCannotBeLaunderedByAnAuthoredCelCopy()throws Exception {worker(()->{
        Fixture f=new Fixture(true);JSONObject forged=f.doc(),detached=SketchDocument.copy(forged);detached.remove("layeredDrawing");
        JSONObject clone=paint("paint","borrowed",.5,.5,"#FFFFFFFF");clone.getJSONObject("brush").put("mode","clone");clone.put("cloneSource",new JSONObject().put("objectId","guide").put("sha256",SketchDocument.object(detached,"guide").getJSONObject("reference").getString("sha256")));
        try(SketchPaintStore assets=new SketchPaintStore(context,f.id)){SketchPaintStore.PreparedChange source=assets.prepare(detached,clone);SketchDocument.object(forged,"paint").put("paint",source.descriptor);assertThrows(IllegalArgumentException.class,()->assets.requireAuthoredStageSource(source.descriptor));}
        String target="restored_clone_"+UUID.randomUUID();try(SketchProjectStore store=new SketchProjectStore(context)){store.copyOwnedDependencies(forged,target);forged.put("id",target).put("revision",1);store.importOwnedDocument(forged,"restore_clone");}
        JSONObject request=new JSONObject().put("sketchId",target).put("expectedRevision",1).put("operationId","blocked_copy").put("operations",new JSONArray().put(copy("actor_construction",0,1,"laundered")));
        assertThrows(IllegalArgumentException.class,()->SketchEditOps.execute(context,"sketch_edit",request));try(SketchProjectStore store=new SketchProjectStore(context)){assertEquals(1,store.get(target).getLong("revision"));assertEquals(SketchDocument.canonical(forged),SketchDocument.canonical(store.get(target)));}
    });}
}
