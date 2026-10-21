package com.rezoxnemesis.videostudio;

import android.content.Context;
import android.graphics.Bitmap;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import java.util.UUID;
import java.io.File;
import java.io.FileOutputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=33,manifest=Config.NONE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public final class SketchPaintCoveragePersistenceTest {
    private final Context context=RuntimeEnvironment.getApplication();
    private JSONObject doc(String id)throws Exception {try(SketchProjectStore store=new SketchProjectStore(context)){JSONObject d=store.get(id);store.verifyReferences(d);return d;}}
    private JSONObject args(String id,String operation)throws Exception {return new JSONObject().put("sketchId",id).put("expectedRevision",doc(id).getLong("revision")).put("operationId",operation);}
    private JSONObject edit(String id,String operation,JSONObject op)throws Exception {return SketchEditOps.execute(context,"sketch_edit",args(id,operation).put("operations",new JSONArray().put(op)));}
    private String create()throws Exception {String id=UUID.randomUUID().toString();SketchEditOps.execute(context,"sketch_create",new JSONObject().put("sketchId",id).put("operationId","create").put("width",256).put("height",256).put("frameCount",2).put("background","#00000000"));edit(id,"cel",new JSONObject().put("type","add_paint_cel").put("layerId","layer_1").put("objectId","cel").put("exposure",2));return id;}
    private JSONObject stroke(String coverage)throws Exception {return new JSONObject().put("type","paint_stroke").put("objectId","cel").put("strokeId","native_soft").put("points",new JSONArray("[[.25,.5,1],[.5,.5,1],[.75,.5,1]]")).put("brush",new JSONObject().put("mode","paint").put("color","#FF102030").put("sizePx",32).put("opacity",.58).put("flow",1).put("hardness",0).put("spacing",.1).put("coverage",coverage));}
    private Bitmap render(JSONObject document)throws Exception {try(SketchFrameRenderer renderer=new SketchFrameRenderer(context,document)){return renderer.render(0);}}
    @Test public void maximumAndExplicitLegacyCoverageSurviveReplayUndoComponentsAndArchive()throws Exception {
        ExecutorService worker=Executors.newSingleThreadExecutor();
        try {worker.submit(()->{try {
            for(String mode:new String[]{"maximum","accumulate"}) {
                String id=create();JSONObject request=args(id,"soft").put("operations",new JSONArray().put(stroke(mode)));
                SketchEditOps.execute(context,"sketch_edit",request);String hash=SketchDocument.object(doc(id),"cel").getJSONObject("paint").getString("sha256");long revision=doc(id).getLong("revision");
                SketchEditOps.execute(context,"sketch_edit",request);assertEquals("Lost-response retry cannot add pigment twice",revision,doc(id).getLong("revision"));
                Bitmap expected=render(doc(id));
                try {
                    SketchEditOps.execute(context,"sketch_undo",args(id,"undo_soft"));Bitmap empty=render(doc(id));try {assertFalse(expected.sameAs(empty));}finally {empty.recycle();}
                    SketchEditOps.execute(context,"sketch_redo",args(id,"redo_soft"));assertEquals(hash,SketchDocument.object(doc(id),"cel").getJSONObject("paint").getString("sha256"));
                    String component="soft_"+UUID.randomUUID();SketchComponentLibrary.execute(context,"sketch_component_save",args(id,"save_soft").put("componentId",component).put("category","effect").put("name","Owned soft stroke").put("frameIndex",0));
                    String target=create();SketchComponentLibrary.execute(context,"sketch_component_apply",args(target,"place_soft").put("componentId",component).put("frameIndex",0).put("exposure",2));
                    Bitmap placed=render(doc(target));try {assertTrue("Ordinary component reuse must retain actual native feather",expected.sameAs(placed));}finally {placed.recycle();}
                    SketchPaintArchive.Prepared archive=SketchPaintArchive.prepare(context,args(id,"archive_soft"));String restored=SketchPaintArchive.restoreFile(context,archive.file,SketchProjectStore.fileHash(archive.file),"restore_"+UUID.randomUUID(),null).getString("sketchId");
                    JSONObject reopened=doc(restored),paint=SketchDocument.object(reopened,"cel").getJSONObject("paint");Bitmap actual=render(reopened);try {assertTrue(expected.sameAs(actual));}finally {actual.recycle();}
                    try(SketchPaintStore store=new SketchPaintStore(context,restored)){JSONArray journal=store.journal(paint,0,8);assertEquals(1,journal.length());assertEquals(mode,journal.getJSONObject(0).getJSONObject("request").getJSONObject("brush").getString("coverage"));}
                } finally {expected.recycle();}
            }
            return null;
        }catch(Exception e){throw new RuntimeException(e);}}).get(90,TimeUnit.SECONDS);
        }finally {worker.shutdownNow();}
    }
    @Test public void invalidCoverageRejectsAtomicallyBeforePublishingPigment()throws Exception {
        String id=create();long revision=doc(id).getLong("revision");String empty=SketchDocument.object(doc(id),"cel").getJSONObject("paint").getString("sha256");JSONObject request=stroke("maximum");request.getJSONObject("brush").put("coverage",JSONObject.NULL);
        assertThrows(IllegalArgumentException.class,()->edit(id,"invalid",request));assertEquals(revision,doc(id).getLong("revision"));assertEquals(empty,SketchDocument.object(doc(id),"cel").getJSONObject("paint").getString("sha256"));
    }
    @Test public void maximumCloneRetainsItsOwnedSourceJournalThroughEditableArchive()throws Exception {
        ExecutorService worker=Executors.newSingleThreadExecutor();try {worker.submit(()->{try {
            String id=create();Bitmap source=Bitmap.createBitmap(64,64,Bitmap.Config.ARGB_8888);source.eraseColor(0xFF204060);File input=new File(context.getCacheDir(),UUID.randomUUID()+".png");try(FileOutputStream stream=new FileOutputStream(input)){assertTrue(source.compress(Bitmap.CompressFormat.PNG,100,stream));}finally {source.recycle();}
            ProjectStore projects=new ProjectStore(context);ProjectStore.Project project=projects.create("Synthetic owned clone coverage");ProjectStore.Asset asset=new ProjectStore.Asset();asset.id=UUID.randomUUID().toString();asset.name="Native test texture";asset.mime="image/png";asset.uri=android.net.Uri.fromFile(input).toString();project.assets.add(asset);projects.save(project);
            SketchEditOps.execute(context,"sketch_import_reference",args(id,"source").put("projectId",project.id).put("sourceProjectUpdatedAt",project.updatedAt).put("assetId",asset.id).put("layerId","layer_1").put("objectId","source").put("guideVisible",false));
            String hash=SketchDocument.object(doc(id),"source").getJSONObject("reference").getString("sha256");JSONObject clone=stroke("maximum");clone.getJSONObject("brush").put("mode","clone");clone.put("cloneSource",new JSONObject().put("objectId","source").put("sha256",hash));edit(id,"clone",clone);
            edit(id,"remove_visible_source",new JSONObject().put("type","delete_object").put("objectId","source"));SketchPaintArchive.Prepared packed=SketchPaintArchive.prepare(context,args(id,"archive_clone"));String restored=SketchPaintArchive.restoreFile(context,packed.file,SketchProjectStore.fileHash(packed.file),"restore_clone_"+UUID.randomUUID(),null).getString("sketchId");JSONObject paint=SketchDocument.object(doc(restored),"cel").getJSONObject("paint");
            try(SketchPaintStore store=new SketchPaintStore(context,restored)){store.verify(paint);assertThrows(Exception.class,()->store.requireAuthoredStageSource(paint));JSONObject record=store.journal(paint,0,8).getJSONObject(0);assertEquals("maximum",record.getJSONObject("request").getJSONObject("brush").getString("coverage"));assertEquals(hash,record.getJSONObject("source").getJSONObject("originalReference").getString("sha256"));}
            Bitmap before=render(doc(id)),after=render(doc(restored));try {assertTrue(before.sameAs(after));}finally {before.recycle();after.recycle();}
            return null;
        }catch(Exception failure){throw new RuntimeException(failure);}}).get(90,TimeUnit.SECONDS);}finally {worker.shutdownNow();}
    }
}
