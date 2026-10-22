package com.rezoxnemesis.videostudio;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TestRule;
import org.junit.runner.RunWith;
import org.junit.runners.model.Statement;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import static org.junit.Assert.*;

/** Actual native image pixels are the oracle; no idealized Java bilinear expected image. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=33,manifest=Config.NONE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public final class SketchPaintFilteredCloneTest {
    private final Context context=RuntimeEnvironment.getApplication();
    /** Native full-source rendering intentionally rejects UI-thread execution. */
    @Rule public final TestRule nativeWorker=(base,description)->new Statement() {
        @Override public void evaluate()throws Throwable {
            ExecutorService worker=Executors.newSingleThreadExecutor();
            try {
                Throwable failure=worker.submit(()->{
                    try {base.evaluate();return (Throwable)null;}
                    catch(Throwable error){return error;}
                }).get(90,TimeUnit.SECONDS);
                if(failure!=null)throw failure;
            } finally {worker.shutdownNow();}
        }
    };
    private JSONObject document(String id,int width,int height)throws Exception {
        return SketchDocument.create(new JSONObject().put("sketchId",id).put("width",width).put("height",height).put("frameCount",1).put("background","#00000000"));
    }
    private JSONObject bounds(double x,double y,double w,double h)throws Exception {return new JSONObject().put("x",x).put("y",y).put("width",w).put("height",h);}
    private void cel(JSONObject d,JSONObject paint)throws Exception {
        d.getJSONArray("layers").getJSONObject(0).getJSONArray("objects").put(SketchEditOps.completeObject(new JSONObject().put("id","cel").put("kind","paint").put("paint",paint).put("bounds",bounds(0,0,1,1))));
    }
    /** 255 source pixels at x=0.5 reproduce the measured 719-pixel actor phase. */
    private Bitmap texture(boolean holes) {
        Bitmap source=Bitmap.createBitmap(255,256,Bitmap.Config.ARGB_8888);
        for(int y=0;y<256;y++)for(int x=0;x<255;x++) {
            int rgb=(x%2==0?0x00E02020:0x002030E0)|((y%7)*3<<8);
            source.setPixel(x,y,holes&&x>=99&&x<112&&y>=100&&y<118?0:0xFF000000|rgb);
        }
        return source;
    }
    private String guide(JSONObject d,boolean holes)throws Exception {
        Bitmap source=texture(holes);File root=new File(context.getFilesDir(),"sketch_studio/"+d.getString("id")+"/references");assertTrue(root.mkdirs());File temporary=new File(root,"fixture.png");
        try(FileOutputStream stream=new FileOutputStream(temporary)){assertTrue(source.compress(Bitmap.CompressFormat.PNG,100,stream));}finally{source.recycle();}
        String sha=SketchProjectStore.fileHash(temporary);assertTrue(temporary.renameTo(new File(root,sha+".png")));
        JSONObject reference=new JSONObject().put("file",sha+".png").put("sha256",sha).put("width",255).put("height",256).put("sourceProjectId","synthetic-native-sampling-test").put("sourceAssetId","owned-native-fixture");
        d.getJSONArray("layers").getJSONObject(0).getJSONArray("objects").put(SketchEditOps.completeObject(new JSONObject().put("id","guide").put("kind","image").put("reference",reference).put("bounds",bounds(.5/256,0,255d/256,1))));return sha;
    }
    private JSONObject clone(String sha,String sampling)throws Exception {
        JSONObject source=new JSONObject().put("objectId","guide").put("sha256",sha);if(sampling!=null)source.put("sampling",sampling);
        return new JSONObject().put("type","paint_stroke").put("objectId","cel").put("strokeId",UUID.randomUUID().toString()).put("frameIndex",0).put("points",new JSONArray("[[0.5,0.5,1]]"))
                .put("brush",new JSONObject().put("mode","clone").put("color","#FF000000").put("sizePx",256).put("hardness",1).put("opacity",1).put("flow",1).put("spacing",.1).put("coverage","maximum")).put("cloneSource",source);
    }
    private Bitmap original(JSONObject d)throws Exception {try(SketchFrameRenderer renderer=new SketchFrameRenderer(context,d)){return renderer.render(0,false);}}
    private Bitmap prepared(SketchPaintStore store,JSONObject d,JSONObject op)throws Exception {return store.renderSource(store.prepare(d,op).descriptor);}
    private void nativePixels(Bitmap expected,Bitmap actual,int lo,int hi,int opaqueTolerance,int alphaTolerance,int premultTolerance) {
        int opaque=0,transparent=0,partial=0;
        for(int y=lo;y<hi;y++)for(int x=lo;x<hi;x++) {
            int a=expected.getPixel(x,y),b=actual.getPixel(x,y),aa=a>>>24,ba=b>>>24;
            assertTrue("Native alpha mismatch at "+x+","+y+": "+aa+" vs "+ba,Math.abs(aa-ba)<=alphaTolerance);
            if(aa==255)opaque++;else if(aa==0)transparent++;else partial++;
            for(int bit:new int[]{16,8,0}) {
                int ac=(a>>>bit)&255,bc=(b>>>bit)&255;
                if(aa==255&&ba==255)assertTrue("Native opaque color mismatch at "+x+","+y,Math.abs(ac-bc)<=opaqueTolerance);
                assertTrue("Premultiplied edge mismatch at "+x+","+y,Math.abs(Math.round(ac*aa/255f)-Math.round(bc*ba/255f))<=premultTolerance);
            }
        }
        assertTrue("Native comparison must include opaque texture",opaque>100);
    }
    @Test public void strictOptionalSamplingRetainsOmittedNormalizedCloneBytes()throws Exception {
        JSONObject old=clone("a".repeat(64),null);old.put("strokeId","fixed");JSONObject explicit=new JSONObject(old.toString());explicit.getJSONObject("cloneSource").put("sampling","nearest");
        JSONObject legacy=SketchPaintBrush.plan(old,256,256).stroke,modern=SketchPaintBrush.plan(explicit,256,256).stroke;
        assertFalse(legacy.getJSONObject("cloneSource").has("sampling"));modern.getJSONObject("cloneSource").remove("sampling");assertEquals(SketchDocument.canonical(legacy),SketchDocument.canonical(modern));
        JSONObject filtered=new JSONObject(old.toString());filtered.getJSONObject("cloneSource").put("sampling","bilinear");assertEquals("bilinear",SketchPaintBrush.plan(filtered,256,256).stroke.getJSONObject("cloneSource").getString("sampling"));
        for(Object invalid:new Object[]{"Bilinear","filtered","",true,1,JSONObject.NULL}) {JSONObject request=new JSONObject(old.toString());request.getJSONObject("cloneSource").put("sampling",invalid);assertThrows(IllegalArgumentException.class,()->SketchPaintBrush.plan(request,256,256));}
        for(String mode:new String[]{"paint","erase"}) {JSONObject request=new JSONObject(filtered.toString());request.getJSONObject("brush").put("mode",mode);assertThrows(IllegalArgumentException.class,()->SketchPaintBrush.plan(request,256,256));}
    }
    @Test public void halfPixelIdentityCloneMatchesNativeImageAndNearestRemainsHistorical()throws Exception {
        String id=UUID.randomUUID().toString();try(SketchPaintStore store=new SketchPaintStore(context,id)) {
            JSONObject d=document(id,256,256);cel(d,store.empty(256,256));String sha=guide(d,false);Bitmap expected=original(d),old=prepared(store,d,clone(sha,null)),nearest=prepared(store,d,clone(sha,"nearest")),filtered=prepared(store,d,clone(sha,"bilinear"));
            try {assertTrue("Explicit nearest must preserve all historical clone pixels",old.sameAs(nearest));assertNotEquals("Regression needs a real filtered identity mismatch",expected.getPixel(128,128),old.getPixel(128,128));nativePixels(expected,filtered,64,192,1,1,2);}
            finally {expected.recycle();old.recycle();nearest.recycle();filtered.recycle();}
        }
    }
    @Test public void filteredIdentityRetainsNativeTransparentHolesAndPremultipliedEdgeColors()throws Exception {
        String id=UUID.randomUUID().toString();try(SketchPaintStore store=new SketchPaintStore(context,id)) {JSONObject d=document(id,256,256);cel(d,store.empty(256,256));String sha=guide(d,true);Bitmap expected=original(d),actual=prepared(store,d,clone(sha,"bilinear"));
            try {assertEquals(0,expected.getPixel(105,108));assertEquals(0,actual.getPixel(105,108));int alpha=expected.getPixel(99,108)>>>24;assertTrue("Half-pixel source alpha edge must be genuinely fractional",alpha>0&&alpha<255);nativePixels(expected,actual,64,192,1,1,2);}finally {expected.recycle();actual.recycle();}}
    }
    @Test public void croppedRotatedAndNonuniformGuidesMatchActualNativeImageAndInvalidateCache()throws Exception {
        String id=UUID.randomUUID().toString();try(SketchPaintStore store=new SketchPaintStore(context,id)) {JSONObject d=document(id,256,256);cel(d,store.empty(256,256));String sha=guide(d,false);JSONObject image=SketchDocument.object(d,"guide");
            for(int variant=0;variant<3;variant++) {
                image.put("bounds",bounds(64.5/256,64.5/256,127d/256,127d/256)).put("referenceCrop",bounds(.171,.183,.517,.493)).put("transform",SketchDocument.transform().put("anchorX",.5).put("anchorY",.5).put("rotation",variant==0?0:variant==1?180:17).put("scaleX",variant==2?1.13:1).put("scaleY",variant==2?.87:1));
                Bitmap expected=original(d);SketchPaintStore.PreparedChange change=store.prepare(d,clone(sha,"bilinear"));Bitmap actual=store.renderSource(change.descriptor);
                try {nativePixels(expected,actual,96,160,3,2,3);JSONObject pin=change.visualOperation.getJSONObject("stroke").getJSONObject("source");assertEquals("bilinear",pin.getString("sampling"));JSONArray crop=pin.getJSONArray("sourceRect");android.graphics.Rect rectangle=SketchFrameRenderer.referencePixels(image.getJSONObject("referenceCrop"),255,256);assertEquals(new JSONArray().put(rectangle.left).put(rectangle.top).put(rectangle.right).put(rectangle.bottom).toString(),crop.toString());}
                finally {expected.recycle();actual.recycle();}
            }
        }
    }
    @Test public void explicitReflectedAffineMappingMatchesNativeCanvasWithoutInvalidGuideFlips()throws Exception {
        String id=UUID.randomUUID().toString();try(SketchPaintStore store=new SketchPaintStore(context,id)) {JSONObject d=document(id,256,256);cel(d,store.empty(256,256));String sha=guide(d,true);JSONObject op=clone(sha,"bilinear");op.getJSONObject("cloneSource").put("mapping",new JSONArray("[-1,0,0,1,1,0]"));Bitmap expected=Bitmap.createBitmap(256,256,Bitmap.Config.ARGB_8888),source=texture(true),actual=prepared(store,d,op);
            try {Canvas canvas=new Canvas(expected);Matrix reflection=new Matrix();reflection.setValues(new float[]{-256f/255,0,256,0,1,0,0,0,1});canvas.concat(reflection);canvas.drawBitmap(source,0,0,new Paint(Paint.ANTI_ALIAS_FLAG|Paint.FILTER_BITMAP_FLAG));nativePixels(expected,actual,64,192,2,1,2);}
            finally {expected.recycle();source.recycle();actual.recycle();}
        }
    }
    @Test public void filteredClonePreservesEveryAlphaLockedMaskPixel()throws Exception {
        String id=UUID.randomUUID().toString();try(SketchPaintStore store=new SketchPaintStore(context,id)) {JSONObject d=document(id,256,256);cel(d,store.empty(256,256));String sha=guide(d,false);JSONObject seed=clone(sha,null);seed.remove("cloneSource");seed.getJSONObject("brush").put("mode","paint").put("color","#FFFFFFFF").put("sizePx",96).put("hardness",0).put("opacity",.65);JSONObject mask=store.prepare(d,seed).descriptor;SketchDocument.object(d,"cel").put("paint",mask);Bitmap before=store.renderSource(mask);JSONObject op=clone(sha,"bilinear").put("alphaLock",true);Bitmap after=prepared(store,d,op);
            try {for(int y=0;y<256;y++)for(int x=0;x<256;x++)assertEquals("Mask alpha changes at "+x+","+y,before.getPixel(x,y)>>>24,after.getPixel(x,y)>>>24);assertNotEquals(before.getPixel(128,128),after.getPixel(128,128));}
            finally {before.recycle();after.recycle();}
        }
    }
    @Test public void optionalFilteredSourcePinsReplayWithoutMutableGuideAndRejectWrongHash()throws Exception {
        String id=UUID.randomUUID().toString(),copy=UUID.randomUUID().toString();try(SketchPaintStore store=new SketchPaintStore(context,id)) {JSONObject d=document(id,256,256);cel(d,store.empty(256,256));String sha=guide(d,true);JSONObject op=clone(sha,"bilinear");SketchPaintStore.PreparedChange change=store.prepare(d,op);Bitmap expected=store.renderSource(change.descriptor);JSONObject record=change.visualOperation.getJSONObject("stroke"),pin=record.getJSONObject("source");assertEquals("bilinear",pin.getString("sampling"));assertEquals(sha,pin.getString("sha256"));assertEquals(sha,pin.getJSONObject("originalReference").getString("sha256"));assertTrue(pin.has("sourceRect"));store.copyTo(copy,change.descriptor);assertTrue(new File(context.getFilesDir(),"sketch_studio/"+id+"/references/"+sha+".png").delete());
            try {Bitmap replay=store.renderPrefix(change.visualOperation,1,256,null);try{assertTrue("Pinned source replay ignores changed/deleted guide",expected.sameAs(replay));}finally{replay.recycle();}try(SketchPaintStore restored=new SketchPaintStore(context,copy)){restored.verify(change.descriptor);Bitmap image=restored.renderSource(change.descriptor);try{assertTrue(expected.sameAs(image));}finally{image.recycle();}}}
            finally {expected.recycle();}
            JSONObject wrong=clone("0".repeat(64),"bilinear");assertThrows(Exception.class,()->store.prepare(d,wrong));
        }
    }
    private int files(File root){int n=0;File[] children=root.listFiles();if(children!=null)for(File f:children)n+=f.isDirectory()?files(f):1;return n;}
    @Test public void filteredGesturePreviewMatchesCommittedPixelsWithoutPublishingAssets()throws Exception {
        String id=UUID.randomUUID().toString();try(SketchPaintStore store=new SketchPaintStore(context,id)) {JSONObject d=document(id,256,256);cel(d,store.empty(256,256));String sha=guide(d,true),before=d.toString();File root=new File(context.getFilesDir(),"sketch_studio/"+id+"/paint");int count=files(root);JSONObject op=clone(sha,"bilinear");Bitmap preview=store.renderGesture(d,op,256,null);assertEquals(before,d.toString());assertEquals("Transient filtered cache must not publish paint/source artifacts",count,files(root));Bitmap committed=prepared(store,d,op);try{assertTrue(preview.sameAs(committed));}finally{preview.recycle();committed.recycle();}}
    }
    @Test public void filteredTargetBudgetRejectsBeforePublishingSourceOrTiles()throws Exception {
        String id=UUID.randomUUID().toString();try(SketchPaintStore store=new SketchPaintStore(context,id)) {JSONObject d=document(id,2048,1024);cel(d,store.empty(2048,1024));String sha=guide(d,false);File root=new File(context.getFilesDir(),"sketch_studio/"+id+"/paint");int before=files(root);String original=d.toString();assertThrows(Exception.class,()->store.prepare(d,clone(sha,"bilinear")));assertEquals(original,d.toString());assertEquals("Explicit filtered budget rejection must be atomic",before,files(root));}
    }
    /** Rehash both journal and snapshot so rejection proves metadata validation, not a broken checksum. */
    private JSONObject forgedSamplingDescriptor(SketchPaintStore.PreparedChange change,JSONObject record,File root)throws Exception {
        byte[] journal=SketchDocument.canonical(record).getBytes(StandardCharsets.UTF_8);String journalSha=SketchDocument.sha256(journal);
        Files.write(new File(new File(root,"strokes"),journalSha+".json").toPath(),journal);
        JSONObject snapshot=new JSONObject(new String(Files.readAllBytes(new File(root,change.descriptor.getString("file")).toPath()),StandardCharsets.UTF_8)).put("strokeSha256",journalSha);
        byte[] bytes=SketchDocument.canonical(snapshot).getBytes(StandardCharsets.UTF_8);String sha=SketchDocument.sha256(bytes);Files.write(new File(root,sha+".json").toPath(),bytes);
        return new JSONObject(change.descriptor.toString()).put("file",sha+".json").put("sha256",sha);
    }
    @Test public void hashConsistentSamplingMetadataCannotChangeReplayModeOrFrozenCrop()throws Exception {
        String id=UUID.randomUUID().toString();try(SketchPaintStore store=new SketchPaintStore(context,id)) {
            JSONObject d=document(id,256,256);cel(d,store.empty(256,256));String sha=guide(d,true);SketchPaintStore.PreparedChange change=store.prepare(d,clone(sha,"bilinear"));File root=new File(context.getFilesDir(),"sketch_studio/"+id+"/paint");
            String[] cases={"request_nearest_pin_bilinear","request_bilinear_pin_nearest","missing_pin_sampling","missing_source_rect","nearest_unexpected_source_rect","fractional_source_rect","source_rect_outside_width","source_rect_outside_height","source_rect_empty","source_rect_crop_mismatch","source_clip_crop_mismatch","unknown_pin_mode","unknown_request_mode"};
            for(String name:cases) {
                JSONObject record=new JSONObject(change.visualOperation.getJSONObject("stroke").toString()),pin=record.getJSONObject("source"),request=record.getJSONObject("request").getJSONObject("cloneSource");
                switch(name) {
                    case "request_nearest_pin_bilinear":request.put("sampling","nearest");break;
                    case "request_bilinear_pin_nearest":pin.put("sampling","nearest");pin.remove("sourceRect");break;
                    case "missing_pin_sampling":pin.remove("sampling");pin.remove("sourceRect");break;
                    case "missing_source_rect":pin.remove("sourceRect");break;
                    case "nearest_unexpected_source_rect":pin.put("sampling","nearest");request.put("sampling","nearest");break;
                    case "fractional_source_rect":pin.put("sourceRect",new JSONArray("[0.5,0,255,256]"));break;
                    case "source_rect_outside_width":pin.put("sourceRect",new JSONArray("[0,0,256,256]"));break;
                    case "source_rect_outside_height":pin.put("sourceRect",new JSONArray("[0,0,255,257]"));break;
                    case "source_rect_empty":pin.put("sourceRect",new JSONArray("[1,0,1,256]"));break;
                    case "source_rect_crop_mismatch":pin.put("sourceRect",new JSONArray("[1,0,255,256]"));break;
                    case "source_clip_crop_mismatch":pin.getJSONObject("sourceClip").put("x",1d/255).put("width",254d/255);break;
                    case "unknown_pin_mode":pin.put("sampling","bicubic");break;
                    case "unknown_request_mode":request.put("sampling","bicubic");break;
                    default:throw new AssertionError(name);
                }
                JSONObject forged=forgedSamplingDescriptor(change,record,root);
                assertThrows("Hash-consistent forged metadata must reject: "+name,Exception.class,()->store.verify(forged));
                assertThrows("Dependency/archive traversal must reject: "+name,Exception.class,()->store.dependencies(forged));
                store.verify(change.descriptor);
            }
        }
    }
    @Test public void alreadyInterruptedFilteredStrokeAndPreviewPublishNoAssets()throws Exception {
        String id=UUID.randomUUID().toString();try(SketchPaintStore store=new SketchPaintStore(context,id)) {
            JSONObject d=document(id,256,256),empty=store.empty(256,256);cel(d,empty);String sha=guide(d,true),before=d.toString();File root=new File(context.getFilesDir(),"sketch_studio/"+id+"/paint");int count=files(root);JSONObject op=clone(sha,"bilinear");
            try {Thread.currentThread().interrupt();assertThrows(InterruptedException.class,()->store.prepare(d,op));assertTrue("Cancellation flag remains observable to caller",Thread.currentThread().isInterrupted());}
            finally {Thread.interrupted();}
            assertEquals(before,d.toString());assertEquals("Interrupted durable stroke publishes nothing",count,files(root));
            try {Thread.currentThread().interrupt();assertThrows(InterruptedException.class,()->store.renderGesture(d,op,256,null));}
            finally {Thread.interrupted();}
            assertEquals(before,d.toString());assertEquals("Interrupted preview publishes nothing",count,files(root));store.verify(empty);
            assertEquals(sha,SketchProjectStore.fileHash(new File(context.getFilesDir(),"sketch_studio/"+id+"/references/"+sha+".png")));
        }
    }
}
