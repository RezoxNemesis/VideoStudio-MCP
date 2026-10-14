package com.rezoxnemesis.videostudio;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.net.Uri;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.shadows.ShadowLooper;
import java.util.UUID;
import java.util.ArrayList;
import java.util.List;
import java.io.File;
import java.io.FileOutputStream;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class) @Config(sdk=33,manifest=Config.NONE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class SketchPaintPresentationTest {
    private final Context context=RuntimeEnvironment.getApplication();
    private JSONObject document(int dimension) throws Exception {
        return SketchDocument.create(new JSONObject().put("sketchId",UUID.randomUUID().toString())
                .put("width",dimension).put("height",dimension).put("frameCount",1));
    }
    private JSONObject cel(JSONObject doc,JSONObject descriptor) throws Exception {
        JSONObject cel=SketchEditOps.completeObject(new JSONObject().put("id","paint_cel").put("kind","paint")
                .put("bounds",new JSONObject().put("x",0).put("y",0).put("width",1).put("height",1)).put("paint",descriptor));
        doc.getJSONArray("layers").getJSONObject(0).getJSONArray("objects").put(cel);return cel;
    }
    private JSONObject stroke(String mode,double y) throws Exception {
        return new JSONObject().put("type","paint_stroke").put("objectId","paint_cel").put("strokeId",UUID.randomUUID().toString())
                .put("points",new JSONArray().put(new JSONArray().put(.1).put(y).put(1)).put(new JSONArray().put(.9).put(y).put(1)))
                .put("brush",new JSONObject().put("mode",mode).put("color","#FFFF0000").put("sizePx",20).put("spacing",.2).put("opacity",1));
    }
    private JSONObject referenceMetadata(File png,String hash)throws Exception{
        ProjectStore projects=new ProjectStore(context);ProjectStore.Project project=projects.create("Owned native paint reference fixture");
        ProjectStore.Asset asset=new ProjectStore.Asset();asset.id=UUID.randomUUID().toString();asset.name="Synthetic source fixture";asset.mime="image/png";asset.uri=Uri.fromFile(png).toString();
        project.assets.add(asset);projects.save(project);
        return new JSONObject().put("file",hash+".png").put("sha256",hash).put("width",256).put("height",256).put("sourceProjectId",project.id).put("sourceAssetId",asset.id);
    }
    @Test public void orderedPrefixesStartBlankAndFinishAtExactlyTheSavedPaintPixels() throws Exception {
        JSONObject doc=document(512);
        try(SketchPaintStore store=new SketchPaintStore(context,doc.getString("id"))){
            cel(doc,store.empty(512,512));SketchPaintStore.PreparedChange change=store.prepare(doc,stroke("paint",.5));
            ExecutorService worker=Executors.newSingleThreadExecutor();
            try(SketchPaintReplay replay=new SketchPaintReplay(context,doc.getString("id"),change.visualOperation)){
                int prior=0;
                for(double fraction:new double[]{0,.25,.5,.75,1}){
                    SketchPaintReplay.Frame frame=worker.submit(()->replay.render(fraction,new SketchPaintReplay.Viewport(512,null))).get(10,TimeUnit.SECONDS);
                    try{int count=colored(frame.bitmap);if(fraction==0)assertEquals(0,count);else assertTrue("Each ordered prefix adds actual covered pixels",count>prior);prior=count;
                        if(fraction==.25)assertEquals(0,frame.bitmap.getPixel(400,256));
                        if(fraction==1){Bitmap saved=store.render(change.descriptor,512,null);try{assertTrue(saved.sameAs(frame.bitmap));}finally{saved.recycle();}}
                    }finally{frame.close();}
                }
            }finally{worker.shutdownNow();}
        }
    }
    @Test public void replayCloseDoesNotRecycleAnImageStillAdoptedByTheCanvas() throws Exception {
        JSONObject doc=document(256);SketchPaintReplay.Frame adopted;
        try(SketchPaintStore store=new SketchPaintStore(context,doc.getString("id"))){
            cel(doc,store.empty(256,256));SketchPaintStore.PreparedChange change=store.prepare(doc,stroke("paint",.5));
            SketchPaintReplay replay=new SketchPaintReplay(context,doc.getString("id"),change.visualOperation);
            ExecutorService worker=Executors.newSingleThreadExecutor();try{adopted=worker.submit(()->replay.render(.5,new SketchPaintReplay.Viewport(256,null))).get(10,TimeUnit.SECONDS);}finally{worker.shutdownNow();}
            replay.close();assertFalse("Player cancellation must not recycle the UI's adopted lease",adopted.bitmap.isRecycled());
            assertTrue(colored(adopted.bitmap)>0);adopted.close();adopted.close();assertTrue(adopted.bitmap.isRecycled());
        }
    }
    @Test public void cancelledQueuedUiPublicationClosesItsUnadoptedLease() throws Exception {
        JSONObject doc=document(256);SketchPaintReplay.Frame queued;
        try(SketchPaintStore store=new SketchPaintStore(context,doc.getString("id"))){
            cel(doc,store.empty(256,256));SketchPaintStore.PreparedChange change=store.prepare(doc,stroke("paint",.5));
            ExecutorService worker=Executors.newSingleThreadExecutor();try(SketchPaintReplay replay=new SketchPaintReplay(context,doc.getString("id"),change.visualOperation)){
                queued=worker.submit(()->replay.render(.5,new SketchPaintReplay.Viewport(256,null))).get(10,TimeUnit.SECONDS);
            }finally{worker.shutdownNow();}
        }
        AtomicInteger callbacks=new AtomicInteger();SketchLivePlayer player=new SketchLivePlayer(context,new JSONObject().put("document",doc).put("sketchId",doc.getString("id"))
                .put("sessionId","session").put("afterSequence",0).put("strokeDurationMs",80),new SketchLivePlayer.Listener(){
            public void drawing(JSONObject d,String id,float fraction,String caption){}public void committed(JSONObject d){}public void idle(){}public void finished(long sequence){}public void failed(String message){fail(message);}
        });
        java.lang.reflect.Method publish=SketchLivePlayer.class.getDeclaredMethod("publish",AutoCloseable.class,Runnable.class);publish.setAccessible(true);publish.invoke(player,queued,(Runnable)callbacks::incrementAndGet);
        player.close();ShadowLooper.idleMainLooper();assertEquals(0,callbacks.get());assertTrue("Removing queued handler callbacks must also close their pending bitmap leases",queued.bitmap.isRecycled());
    }
    @Test public void paintReplayCannotRunItsPixelAndFileWorkOnTheUiThread() throws Exception {
        JSONObject doc=document(256);
        try(SketchPaintStore store=new SketchPaintStore(context,doc.getString("id"))){
            cel(doc,store.empty(256,256));SketchPaintStore.PreparedChange change=store.prepare(doc,stroke("paint",.5));
            try(SketchPaintReplay replay=new SketchPaintReplay(context,doc.getString("id"),change.visualOperation)){
                try{replay.render(.5,new SketchPaintReplay.Viewport(256,null));fail("Paint prefix work must stay off the UI");}catch(IllegalStateException expected){assertTrue(expected.getMessage().contains("background"));}
            }
        }
    }
    @Test public void workerOwnedPreviewBuffersHaveBoundedPixelsDetachedMetadataAndExplicitLifetime() throws Exception {
        JSONObject crop=new JSONObject().put("x",.25).put("y",.25).put("width",.5).put("height",.5);
        Bitmap bitmap=Bitmap.createBitmap(128,128,Bitmap.Config.ARGB_8888);bitmap.eraseColor(Color.GREEN);
        try(SketchPaintReplay.Frame frame=SketchPaintReplay.Frame.owned(bitmap,crop)){
            crop.put("x",0);assertEquals(.25,frame.crop.getDouble("x"),0);assertEquals(1,frame.fraction,0);assertEquals(Color.GREEN,frame.bitmap.getPixel(64,64));
        }assertTrue(bitmap.isRecycled());
        Bitmap excessive=Bitmap.createBitmap(1025,128,Bitmap.Config.ARGB_8888);
        try{try{SketchPaintReplay.Frame.owned(excessive,null);fail("Worker preview cannot exceed the native viewport budget");}catch(IllegalArgumentException expected){assertFalse("Rejected ownership leaves cleanup with its original caller",excessive.isRecycled());}}
        finally{excessive.recycle();}
    }
    @Test public void appendPresentationKeepsExistingInkWhileRevealingOnlyNewTravel() throws Exception {
        JSONObject doc=document(256);JSONObject mark=SketchEditOps.completeObject(new JSONObject().put("id","old_ink").put("kind","stroke").put("color","#FFFF0000").put("width",.04)
                .put("points",new JSONArray().put(new JSONArray().put(.1).put(.3).put(1)).put(new JSONArray().put(.5).put(.3).put(1))));
        doc.getJSONArray("layers").getJSONObject(0).getJSONArray("objects").put(mark);
        JSONObject operation=new JSONObject().put("type","append_points").put("objectId","old_ink").put("points",new JSONArray().put(new JSONArray().put(.5).put(.8).put(1)));
        ExecutorService worker=Executors.newSingleThreadExecutor();
        try(SketchLivePreparation.Prepared prepared=worker.submit(()->SketchLivePreparation.prepareDrawing(context,doc,operation)).get(10,TimeUnit.SECONDS)){
            SketchFrameRenderer renderer=prepared.takeRenderer();
            try{for(float fraction:new float[]{0,.5f,1}){
                renderer.setPresentation("old_ink",fraction);Bitmap image=renderer.render(0,false);
                try{assertEquals("Already authored ink must remain visible during append playback",Color.RED,image.getPixel(51,77));
                    if(fraction==0)assertEquals(0,image.getPixel(128,110));
                    else assertEquals(Color.RED,image.getPixel(128,110));
                    if(fraction<1)assertEquals("The new endpoint must wait for actual completion",0,image.getPixel(128,205));
                    else assertEquals(Color.RED,image.getPixel(128,205));
                }finally{image.recycle();}
            }}finally{renderer.close();}
        }finally{worker.shutdownNow();}
    }
    @Test public void boundedCroppedRendererPreservesPaintPixelsAndDocumentCoordinates() throws Exception {
        JSONObject doc=document(512);JSONObject crop=new JSONObject().put("x",.25).put("y",.25).put("width",.5).put("height",.5);
        try(SketchPaintStore store=new SketchPaintStore(context,doc.getString("id"))){
            JSONObject cel=cel(doc,store.empty(512,512));SketchPaintStore.PreparedChange change=store.prepare(doc,stroke("paint",.5));cel.put("paint",change.descriptor);
            ExecutorService worker=Executors.newSingleThreadExecutor();try{
                worker.submit(()->{try(SketchFrameRenderer renderer=new SketchFrameRenderer(context,doc)){
                    Bitmap full=renderer.render(0,false),bounded=renderer.render(0,false,256,crop);
                    try{assertEquals(256,bounded.getWidth());assertEquals(256,bounded.getHeight());assertEquals(256*256*4,bounded.getAllocationByteCount());
                        assertEquals(full.getPixel(256,256),bounded.getPixel(128,128));assertEquals(Color.RED,bounded.getPixel(128,128));assertEquals(0,bounded.getPixel(128,30));
                    }finally{full.recycle();bounded.recycle();}
                }return null;}).get(10,TimeUnit.SECONDS);
            }finally{worker.shutdownNow();}
        }
    }
    @Test public void manyCelsSharingAnImmutableSnapshotUseOneBoundedRasterResource() throws Exception {
        JSONObject doc=document(512);
        try(SketchPaintStore store=new SketchPaintStore(context,doc.getString("id"))){
            JSONObject descriptor=store.empty(512,512);JSONArray objects=doc.getJSONArray("layers").getJSONObject(0).getJSONArray("objects");
            for(int i=0;i<200;i++)objects.put(SketchEditOps.completeObject(new JSONObject().put("id","paint_"+i).put("kind","paint").put("paint",descriptor)
                    .put("bounds",new JSONObject().put("x",0).put("y",0).put("width",1).put("height",1))));
            ExecutorService worker=Executors.newSingleThreadExecutor();try{
                worker.submit(()->{try(SketchFrameRenderer renderer=new SketchFrameRenderer(context,doc)){renderer.prepareRasterResources(1024,0);
                    assertEquals("Repeated snapshots must share their prepared pixel resource",512L*512*4,renderer.preparedRasterBytes());
                    assertTrue(renderer.preparedRasterBytes()<=12L*1024*1024);
                }return null;}).get(10,TimeUnit.SECONDS);
            }finally{worker.shutdownNow();}
        }
    }
    @Test public void erasingOnePaintCelRevealsLowerArtworkWithoutDamagingIt() throws Exception {
        JSONObject doc=document(256);JSONObject layer=doc.getJSONArray("layers").getJSONObject(0);
        layer.getJSONArray("objects").put(SketchEditOps.completeObject(new JSONObject().put("id","lower").put("kind","rect").put("fill",true).put("color","#FF0000FF")
                .put("bounds",new JSONObject().put("x",0).put("y",0).put("width",1).put("height",1))));
        try(SketchPaintStore store=new SketchPaintStore(context,doc.getString("id"))){
            JSONObject cel=cel(doc,store.empty(256,256));SketchPaintStore.PreparedChange first=store.prepare(doc,stroke("paint",.5));cel.put("paint",first.descriptor);
            SketchPaintStore.PreparedChange erased=store.prepare(doc,stroke("erase",.5));cel.put("paint",erased.descriptor);
            ExecutorService worker=Executors.newSingleThreadExecutor();try{
                worker.submit(()->{try(SketchFrameRenderer renderer=new SketchFrameRenderer(context,doc)){Bitmap bitmap=renderer.render(0);try{assertEquals(Color.BLUE,bitmap.getPixel(128,128));assertEquals(Color.BLUE,bitmap.getPixel(128,20));}finally{bitmap.recycle();}}return null;}).get(10,TimeUnit.SECONDS);
            }finally{worker.shutdownNow();}
        }
    }
    @Test public void paintCoordinatesRespectObjectAndLayerTransformsAndOpacity() throws Exception {
        JSONObject doc=document(512);JSONObject layer=doc.getJSONArray("layers").getJSONObject(0);
        layer.put("opacity",.5);layer.getJSONObject("transform").put("x",.1);
        try(SketchPaintStore store=new SketchPaintStore(context,doc.getString("id"))){
            JSONObject cel=cel(doc,store.empty(512,512));SketchPaintStore.PreparedChange change=store.prepare(doc,stroke("paint",.5));cel.put("paint",change.descriptor);
            assertEquals("Native source pixels on both sides of the tile seam are fully opaque","#FFFF0000",store.sample(change.descriptor,258/512d,255/512d));
            assertEquals("The second tile contains the same fully opaque authored paint","#FFFF0000",store.sample(change.descriptor,258/512d,256/512d));
            cel.getJSONObject("transform").put("scaleX",.5).put("scaleY",.5).put("anchorX",0).put("anchorY",0).put("x",.2).put("y",.2).put("opacity",.5);
            ExecutorService worker=Executors.newSingleThreadExecutor();try{
                worker.submit(()->{try(SketchFrameRenderer renderer=new SketchFrameRenderer(context,doc)){Bitmap bitmap=renderer.render(0,false,512,null);try{
                    assertEquals("Layer and object opacity multiply away from a tile seam",64,Color.alpha(bitmap.getPixel(282,229)),1);
                    int sample=bitmap.getPixel(282,230);assertEquals("Tile seams must preserve multiplied opacity",64,Color.alpha(sample),1);assertEquals(255,Color.red(sample));assertEquals(0,bitmap.getPixel(128,256));
                }finally{bitmap.recycle();}}return null;}).get(10,TimeUnit.SECONDS);
            }finally{worker.shutdownNow();}
        }
    }
    @Test public void cloneLivePreparationKeepsTheReferenceHiddenAndTheBeforePointerUntilCompletion() throws Exception {
        JSONObject doc=document(256);String id=doc.getString("id");File references=new File(context.getFilesDir(),"sketch_studio/"+id+"/references");assertTrue(references.mkdirs());
        Bitmap source=Bitmap.createBitmap(256,256,Bitmap.Config.ARGB_8888);source.eraseColor(Color.GREEN);File staged=new File(references,"fixture.png");
        try(FileOutputStream out=new FileOutputStream(staged)){assertTrue(source.compress(Bitmap.CompressFormat.PNG,100,out));}finally{source.recycle();}
        String hash=SketchProjectStore.fileHash(staged);File pinned=new File(references,hash+".png");assertTrue(staged.renameTo(pinned));
        JSONObject guide=SketchEditOps.completeObject(new JSONObject().put("id","guide").put("kind","image")
                .put("bounds",new JSONObject().put("x",0).put("y",0).put("width",1).put("height",1))
                .put("reference",referenceMetadata(pinned,hash)));
        guide.getJSONObject("transform").put("opacity",0);doc.getJSONArray("layers").getJSONObject(0).getJSONArray("objects").put(guide);
        ExecutorService worker=Executors.newSingleThreadExecutor();
        try{worker.submit(()->{try(SketchPaintStore store=new SketchPaintStore(context,id)){
            JSONObject paint=cel(doc,store.empty(256,256));String before=paint.getJSONObject("paint").getString("sha256");
            JSONObject operation=stroke("clone",.5).put("cloneSource",new JSONObject().put("objectId","guide").put("sha256",hash));
            SketchPaintStore.PreparedChange change=store.prepare(doc,operation);
            try(SketchLivePreparation.Prepared prepared=SketchLivePreparation.prepareDrawing(context,doc,change.visualOperation,new SketchPaintReplay.Viewport(256,null))){
                assertEquals(before,SketchDocument.object(prepared.document,"paint_cel").getJSONObject("paint").getString("sha256"));
                assertEquals(change.descriptor.getString("sha256"),SketchDocument.object(prepared.committedDocument(),"paint_cel").getJSONObject("paint").getString("sha256"));
                assertEquals(0,SketchDocument.object(prepared.document,"guide").getJSONObject("transform").getDouble("opacity"),0);
                SketchPaintReplay.Frame baseline=prepared.takeInitialPaint();SketchFrameRenderer renderer=prepared.takeRenderer();
                try{Bitmap blank=renderer.render(0,false,256,null);try{assertEquals(0,colored(blank));}finally{blank.recycle();}
                    try(SketchPaintReplay.Frame end=prepared.renderPaint(1,new SketchPaintReplay.Viewport(256,null))){renderer.setPaintPresentation("paint_cel",end);Bitmap drawn=renderer.render(0,false,256,null);try{assertEquals(Color.GREEN,drawn.getPixel(128,128));assertEquals(0,drawn.getPixel(128,20));}finally{drawn.recycle();}renderer.setPaintPresentation(null,null);}
                }finally{renderer.setPaintPresentation(null,null);renderer.close();baseline.close();}
            }
        }return null;}).get(15,TimeUnit.SECONDS);}finally{worker.shutdownNow();}
    }
    @Test public void frameFiveCloneUsesItsAuthoredPoseAndLivePresentationKeepsThatFrame() throws Exception {
        JSONObject doc=document(256).put("frameCount",6);String id=doc.getString("id");
        File references=new File(context.getFilesDir(),"sketch_studio/"+id+"/references");assertTrue(references.mkdirs());
        Bitmap source=Bitmap.createBitmap(256,256,Bitmap.Config.ARGB_8888);
        for(int y=0;y<256;y++)for(int x=0;x<256;x++)source.setPixel(x,y,x<128?Color.GREEN:Color.BLUE);
        File staged=new File(references,"fixture.png");try(FileOutputStream out=new FileOutputStream(staged)){assertTrue(source.compress(Bitmap.CompressFormat.PNG,100,out));}finally{source.recycle();}
        String hash=SketchProjectStore.fileHash(staged);assertTrue(staged.renameTo(new File(references,hash+".png")));
        JSONObject guide=SketchEditOps.completeObject(new JSONObject().put("id","guide").put("kind","image").put("exposure",6)
                .put("bounds",new JSONObject().put("x",0).put("y",0).put("width",1).put("height",1))
                .put("reference",referenceMetadata(new File(references,hash+".png"),hash)));
        guide.getJSONObject("transform").put("opacity",0);guide.put("keys",new JSONArray()
                .put(new JSONObject().put("frame",0).put("interpolation","linear").put("transform",SketchDocument.transform().put("opacity",0)))
                .put(new JSONObject().put("frame",5).put("interpolation","linear").put("transform",SketchDocument.transform().put("x",.25).put("opacity",0))));
        doc.getJSONArray("layers").getJSONObject(0).getJSONArray("objects").put(guide);
        ExecutorService worker=Executors.newSingleThreadExecutor();SketchLivePreparation.Prepared prepared=null;
        try{
            prepared=worker.submit(()->{try(SketchPaintStore store=new SketchPaintStore(context,id)){
                JSONObject paint=cel(doc,store.empty(256,256));paint.put("exposure",6).put("keys",new JSONArray()
                        .put(new JSONObject().put("frame",0).put("interpolation","linear").put("transform",SketchDocument.transform()))
                        .put(new JSONObject().put("frame",5).put("interpolation","linear").put("transform",SketchDocument.transform().put("x",.1))));
                JSONObject marker=SketchEditOps.completeObject(new JSONObject().put("id","frame_five_marker").put("kind","paint").put("frame",5).put("paint",store.empty(256,256))
                        .put("bounds",new JSONObject().put("x",0).put("y",0).put("width",1).put("height",1)));
                doc.getJSONArray("layers").getJSONObject(0).getJSONArray("objects").put(marker);
                SketchPaintStore.PreparedChange mark=store.prepare(doc,stroke("paint",.8).put("objectId","frame_five_marker")
                        .put("points",new JSONArray().put(new JSONArray().put(.2).put(.8).put(1))));marker.put("paint",mark.descriptor);
                JSONObject operation=stroke("clone",.5).put("frameIndex",5).put("points",new JSONArray().put(new JSONArray().put(.6).put(.5).put(1)))
                        .put("cloneSource",new JSONObject().put("objectId","guide").put("sha256",hash));
                SketchPaintStore.PreparedChange change=store.prepare(doc,operation);
                return SketchLivePreparation.prepareDrawing(context,doc,change.visualOperation,new SketchPaintReplay.Viewport(256,null));
            }}).get(15,TimeUnit.SECONDS);
            java.lang.reflect.Field authoredFrame=SketchLivePreparation.Prepared.class.getDeclaredField("frameIndex");authoredFrame.setAccessible(true);
            assertEquals("The authored frame must reach the UI instead of cel start frame",5,authoredFrame.getInt(prepared));
            SketchPaintReplay.Frame baseline=prepared.takeInitialPaint();SketchFrameRenderer renderer=prepared.takeRenderer();
            try{
                SketchLivePreparation.Prepared selected=prepared;try(SketchPaintReplay.Frame end=worker.submit(()->selected.renderPaint(1,new SketchPaintReplay.Viewport(256,null))).get(15,TimeUnit.SECONDS)){
                    renderer.setPaintPresentation("paint_cel",end);Bitmap image=Bitmap.createBitmap(256,256,Bitmap.Config.ARGB_8888);
                    try{renderer.draw(new android.graphics.Canvas(image),5,false);assertEquals("Frame5 source mapping and visible keyed pose must agree",Color.GREEN,image.getPixel(179,128));assertEquals(0,image.getPixel(153,128));
                        assertEquals("Only frame5 exposes this cel, so its raster must be warmed for the authored frame",Color.RED,image.getPixel(51,205));}
                    finally{image.recycle();renderer.setPaintPresentation(null,null);}
                }
            }finally{renderer.setPaintPresentation(null,null);renderer.close();baseline.close();}
        }finally{if(prepared!=null)prepared.close();worker.shutdownNow();}
    }
    @Test public void savedLivePaintPublishesTheBlankBaselineAndMultipleRealPrefixesInOrder() throws Exception {
        String id=UUID.randomUUID().toString(),session=UUID.randomUUID().toString();
        ExecutorService author=Executors.newSingleThreadExecutor();
        JSONObject baseline;
        try{
            baseline=author.submit(()->{
                SketchEditOps.execute(context,"sketch_create",new JSONObject().put("sketchId",id).put("operationId","create").put("width",256).put("height",256).put("frameCount",1));
                SketchEditOps.execute(context,"sketch_edit",new JSONObject().put("sketchId",id).put("operationId","cel").put("expectedRevision",1)
                        .put("operations",new JSONArray().put(new JSONObject().put("type","add_paint_cel").put("objectId","paint_cel").put("layerId","layer_1"))));
                SketchLiveDrawing.begin(context,new JSONObject().put("sketchId",id).put("operationId",session).put("expectedRevision",2).put("strokeDurationMs",80));
                SketchEditOps.execute(context,"sketch_edit",new JSONObject().put("sketchId",id).put("operationId","paint").put("expectedRevision",2).put("operations",new JSONArray().put(stroke("paint",.5))));
                SketchEditOps.execute(context,"sketch_live_end",new JSONObject().put("sketchId",id).put("sessionId",session).put("expectedRevision",3));
                return SketchLiveDrawing.read(context,id,session);
            }).get(15,TimeUnit.SECONDS);
        }finally{author.shutdownNow();}
        List<Double> fractions=new ArrayList<>();List<Integer> pixels=new ArrayList<>();
        AtomicBoolean finished=new AtomicBoolean();AtomicReference<String> error=new AtomicReference<>();
        SketchLivePlayer player=new SketchLivePlayer(context,baseline,new SketchLivePlayer.Listener(){
            public void drawing(JSONObject doc,String objectId,float fraction,String caption){error.set("Paint must use prepared immutable actual stroke pixels");}
            public SketchPaintReplay.Viewport paintViewport(String objectId){return new SketchPaintReplay.Viewport(256,null);}
            public void preparedPaintDrawing(SketchLivePreparation.Prepared prepared,SketchPaintReplay.Frame frame,String objectId,float fraction,String caption){
                try{assertEquals("paint_cel",objectId);fractions.add(frame.fraction);pixels.add(colored(frame.bitmap));}finally{frame.close();}
            }
            public void committed(JSONObject doc){}public void idle(){}public void finished(long sequence){finished.set(true);}public void failed(String message){error.set(message);}
        });
        try{player.start();long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(15);
            while(!finished.get()&&error.get()==null&&System.nanoTime()<deadline){ShadowLooper.idleMainLooper(20,TimeUnit.MILLISECONDS);Thread.sleep(2);}
            assertNull(error.get());assertTrue("Saved native paint stream must complete",finished.get());assertTrue("Even a slow prefix worker must present intermediate coverage",fractions.size()>=5);
            assertEquals(0,fractions.get(0),0);assertEquals(1,fractions.get(fractions.size()-1),0);assertEquals(0,(int)pixels.get(0));
            for(int i=1;i<fractions.size();i++){assertTrue(fractions.get(i)>fractions.get(i-1));assertTrue(pixels.get(i)>pixels.get(i-1));}
        }finally{player.close();}
    }
    private static int colored(Bitmap bitmap){int count=0;for(int y=0;y<bitmap.getHeight();y++)for(int x=0;x<bitmap.getWidth();x++)if((bitmap.getPixel(x,y)>>>24)!=0)count++;return count;}
}
