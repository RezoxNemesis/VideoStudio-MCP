package com.rezoxnemesis.videostudio;

import android.content.Context;
import android.graphics.*;
import android.net.Uri;
import android.util.Base64;
import org.json.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.*;
import java.io.*;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.Assert.*;

/** Opt-in private-reference proof. Inputs and artwork stay outside Git; all pixels use product code. */
@RunWith(RobolectricTestRunner.class) @Config(sdk=33,manifest=Config.NONE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class SketchReferenceFidelityProofTest {
    private final Context context=RuntimeEnvironment.getApplication();
    private final JSONArray calls=new JSONArray(),comparisons=new JSONArray();
    private File root;
    private String id;
    private JSONObject proofDocument,proofFixture;
    @Test public void suppliedImagePreservesPixelsThroughNestedPartsEditsAndArchive() throws Exception {
        String fixture=System.getenv("VIDEOSTUDIO_REFERENCE_FIDELITY_FIXTURE");
        Assume.assumeTrue("Optional owner-supplied reference fixture",fixture!=null&&!fixture.isBlank());
        ExecutorService worker=Executors.newSingleThreadExecutor();
        try { worker.submit(()->{try{proof(new JSONObject(new String(Files.readAllBytes(new File(fixture).toPath()),StandardCharsets.UTF_8)));return null;}catch(Exception e){throw new RuntimeException(e);}}).get(240,TimeUnit.SECONDS); }
        finally {worker.shutdownNow();}
        canvasProof();
    }
    private JSONObject call(String action,JSONObject args)throws Exception {
        JSONObject reply=SketchEditOps.execute(context,action,args);
        calls.put(new JSONObject().put("action",action).put("request",SketchDocument.copy(args)).put("receipt",SketchDocument.copy(reply)));
        return reply;
    }
    private JSONObject doc()throws Exception {try(SketchProjectStore s=new SketchProjectStore(context)){JSONObject d=s.get(id);s.verifyReferences(d);return d;}}
    private JSONObject args(String op,long revision)throws Exception{return new JSONObject().put("sketchId",id).put("operationId",op).put("expectedRevision",revision);}
    private void json(String name,Object value)throws Exception {Files.write(new File(root,name).toPath(),value.toString().getBytes(StandardCharsets.UTF_8));}
    private void png(Bitmap image,String name)throws Exception {try(FileOutputStream out=new FileOutputStream(new File(root,name))){assertTrue(image.compress(Bitmap.CompressFormat.PNG,100,out));}}
    private Bitmap render(JSONObject d)throws Exception {try(SketchFrameRenderer renderer=new SketchFrameRenderer(context,d)){return renderer.render(0);}}
    private void canvasProof()throws Exception {
        SketchCanvasView view=new SketchCanvasView(context);android.widget.FrameLayout host=new android.widget.FrameLayout(context);
        int width=proofDocument.getInt("width"),height=proofDocument.getInt("height");host.addView(view,new android.widget.FrameLayout.LayoutParams(width,height));host.layout(0,0,width,height);view.layout(0,0,width,height);
        List<JSONObject> edits=new ArrayList<>();List<String> messages=new ArrayList<>();view.setListener(new SketchCanvasView.Listener(){public void onOperation(JSONObject op){edits.add(op);}public void onSelection(String selected){}public void onMessage(String message){messages.add(message);}});
        view.setDocument(proofDocument);view.setFrame(0);view.setOnion(false);view.setTool("select");Bitmap viewport=Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888);
        try{
            view.draw(new Canvas(viewport));Field rasterField=SketchCanvasView.class.getDeclaredField("rasterWorker");rasterField.setAccessible(true);
            ((ExecutorService)rasterField.get(view)).submit(()->{}).get(60,TimeUnit.SECONDS);org.robolectric.shadows.ShadowLooper.idleMainLooper();view.draw(new Canvas(viewport));
            assertFalse(view.previewStatus(),view.previewFailed());assertTrue(view.previewStatus(),view.previewStatus().contains("ready"));png(viewport,"06-actual-sketch-canvas-native.png");
            long now=android.os.SystemClock.uptimeMillis();float x=(float)(proofFixture.getJSONArray("hit").getDouble(0)*width),y=(float)(proofFixture.getJSONArray("hit").getDouble(1)*height);
            android.view.MotionEvent down=android.view.MotionEvent.obtain(now,now,0,x,y,0),up=android.view.MotionEvent.obtain(now,now+1,1,x,y,0);
            try{view.onTouchEvent(down);view.onTouchEvent(up);}finally{down.recycle();up.recycle();}
            assertEquals(proofFixture.getString("editableObject"),view.getSelectedId());assertTrue(edits.isEmpty());view.draw(new Canvas(viewport));png(viewport,"07-actual-hand-selection-native.png");
            JSONObject status=new JSONObject().put("previewStatus",view.previewStatus()).put("actualViewClass","SketchCanvasView").put("selectedObjectId",view.getSelectedId()).put("editsFromSelection",edits.size()).put("messages",new JSONArray(messages)).put("execution","Robolectric SDK33 native Canvas; not a phone screenshot");json("Canvas-Verification.json",status);
        }finally{view.dispose();viewport.recycle();}
    }
    private void identical(Bitmap expected,Bitmap actual,String step)throws Exception {
        assertEquals(expected.getWidth(),actual.getWidth());assertEquals(expected.getHeight(),actual.getHeight());
        int count=expected.getWidth()*expected.getHeight(),changed=0,max=0;long absolute=0;
        int[] a=new int[count],b=new int[count];expected.getPixels(a,0,expected.getWidth(),0,0,expected.getWidth(),expected.getHeight());actual.getPixels(b,0,actual.getWidth(),0,0,actual.getWidth(),actual.getHeight());
        for(int i=0;i<count;i++){if(a[i]!=b[i])changed++;for(int shift=0;shift<32;shift+=8){int delta=Math.abs((a[i]>>>shift&255)-(b[i]>>>shift&255));max=Math.max(max,delta);absolute+=delta;}}
        comparisons.put(new JSONObject().put("step",step).put("pixels",count).put("changedPixels",changed).put("maximumChannelError",max).put("meanAbsoluteChannelError",absolute/(4.0*count)));
        json("Pixel-Comparisons.json",comparisons);assertEquals(step+": preserve every decoded source pixel",0,changed);
    }
    private void proof(JSONObject fixture)throws Exception {
        root=new File(Objects.requireNonNull(System.getenv("VIDEOSTUDIO_REFERENCE_FIDELITY_OUTPUT"),"Evidence output required"));assertTrue(root.isDirectory()||root.mkdirs());
        File source=new File(fixture.getString("source"));String sourceHash=SketchProjectStore.fileHash(source);
        id="reference_fidelity_"+UUID.randomUUID().toString().replace("-","");
        ProjectStore projects=new ProjectStore(context);ProjectStore.Project project=projects.create(fixture.getString("label"));
        ProjectStore.Asset asset=projects.registerImportedAsset(project.id,Uri.fromFile(source),source.getName(),"image/jpeg",0,source.length());
        call("sketch_create",new JSONObject().put("sketchId",id).put("operationId","create").put("name",fixture.getString("label")).put("width",fixture.getInt("width")).put("height",fixture.getInt("height")).put("frameCount",1).put("fps",24).put("background","#00000000"));
        call("sketch_import_reference",args("import",1).put("projectId",project.id).put("sourceProjectUpdatedAt",projects.get(project.id).updatedAt).put("assetId",asset.id).put("layerId","layer_1").put("objectId","source"));
        JSONObject imported=doc();JSONObject ref=SketchDocument.object(imported,"source").getJSONObject("reference");assertEquals(sourceHash,ref.getString("sourceSha256"));assertEquals(fixture.getInt("width"),ref.getInt("width"));assertEquals(fixture.getInt("height"),ref.getInt("height"));
        Bitmap decoded=ImageDecoder.decodeBitmap(ImageDecoder.createSource(source),(decoder,info,input)->decoder.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE));
        Bitmap original=render(imported);try{
            png(original,"01-imported-native.png");identical(decoded,original,"Owned image import at original resolution");decoded.recycle();
            JSONArray crops=fixture.optJSONArray("reviewCrops");if(crops!=null)try(SketchFrameRenderer renderer=new SketchFrameRenderer(context,imported)){
                for(int i=0;i<crops.length();i++){Bitmap closeup=renderer.render(0,true,1024,crops.getJSONObject(i));try{png(closeup,"source-detail-"+i+"-native.png");}finally{closeup.recycle();}}
            }
            JSONArray stages=fixture.getJSONArray("stages");
            for(int i=0;i<stages.length();i++){
                JSONObject d=doc(),s=stages.getJSONObject(i),reference=SketchDocument.object(d,s.getString("sourceObjectId")).getJSONObject("reference");
                JSONObject request=args("separate_"+i,d.getLong("revision")).put("sourceObjectId",s.getString("sourceObjectId")).put("expectedReferenceSha256",reference.getString("sha256")).put("expectedSourceSha256",reference.getString("sourceSha256")).put("frame",0).put("exposure",1).put("hideSource",true).put("masks",s.getJSONArray("masks")).put("background",s.getJSONObject("background"));
                String before=SketchDocument.canonical(d);JSONObject preview=call("sketch_reference_layers_preview",request);assertEquals(before,SketchDocument.canonical(doc()));assertFalse(preview.getBoolean("sourceModified"));
                Files.write(new File(root,"preview-stage-"+i+".png").toPath(),Base64.decode(preview.getJSONObject("sketchPreviewImage").getString("base64"),Base64.DEFAULT));
                call("sketch_import_reference_layers",request);
                Bitmap image=render(doc());try{png(image,"stage-"+i+"-native.png");identical(original,image,"Composite after separation stage "+i);}finally{image.recycle();}
                json("Native-Calls.json",calls);
            }
            JSONObject d=doc();Bitmap composite=render(d);try{png(composite,"02-layered-native.png");}finally{composite.recycle();}
            JSONObject figure=SketchDocument.copy(d);SketchDocument.layer(figure,fixture.getString("backgroundLayer")).put("visible",false);
            Bitmap cutout=render(figure);try{png(cutout,"03-figure-isolated-native.png");int[] pixels=new int[cutout.getWidth()*cutout.getHeight()];cutout.getPixels(pixels,0,cutout.getWidth(),0,0,cutout.getWidth(),cutout.getHeight());int visible=0;for(int p:pixels)if((p>>>24)>0)visible++;assertTrue(visible>100000&&visible<pixels.length);json("Figure-Coverage.json",new JSONObject().put("visiblePixels",visible).put("totalPixels",pixels.length).put("manualPolygonBoundary",true));}finally{cutout.recycle();}
            // Normal public edit path proves these are actual independent parts rather than an overlay.
            String object=fixture.getString("editableObject");long rev=d.getLong("revision");
            JSONObject movedTransform=SketchDocument.transform().put("x",-.065).put("rotation",-12)
                    .put("anchorX",fixture.getJSONArray("hit").getDouble(0)).put("anchorY",fixture.getJSONArray("hit").getDouble(1));
            call("sketch_edit",args("move_hand",rev).put("operations",new JSONArray().put(new JSONObject()
                    .put("type","set_transform").put("objectId",object).put("transform",movedTransform))));
            Bitmap moved=render(doc());try{png(moved,"04-independent-hand-edit-native.png");assertFalse(original.sameAs(moved));}finally{moved.recycle();}
            JSONObject afterMove=doc();call("sketch_undo",args("undo_hand",afterMove.getLong("revision")));Bitmap undone=render(doc());try{identical(original,undone,"Undo independent hand edit");}finally{undone.recycle();}
            call("sketch_redo",args("redo_hand",doc().getLong("revision")));Bitmap redone=render(doc()),expectedMoved=render(afterMove);try{identical(expectedMoved,redone,"Redo independent hand edit");}finally{redone.recycle();expectedMoved.recycle();}
            call("sketch_undo",args("restore_exact_image",doc().getLong("revision")));d=doc();
            SketchPaintArchive.Prepared archive=SketchPaintArchive.prepare(context,args("archive",d.getLong("revision")));Files.copy(archive.file.toPath(),new File(root,"Temple-Realm-Layered.vssketch").toPath());
            JSONObject restored=SketchPaintArchive.restoreFile(context,archive.file,SketchProjectStore.fileHash(archive.file),"restore_reference","Restored source-assisted target");JSONObject restoredDoc;try(SketchProjectStore store=new SketchProjectStore(context)){restoredDoc=store.get(restored.getString("sketchId"));store.verifyReferences(restoredDoc);}
            Bitmap restoredImage=render(restoredDoc);try{png(restoredImage,"05-restored-native.png");identical(original,restoredImage,"Native archive restoration");}finally{restoredImage.recycle();}
            // Independently verify every visible cutout's stored colors against the decoded source.
            JSONArray inventory=new JSONArray();int[] base=new int[original.getWidth()*original.getHeight()];original.getPixels(base,0,original.getWidth(),0,0,original.getWidth(),original.getHeight());int[] coverage=new int[base.length];
            try(SketchProjectStore store=new SketchProjectStore(context)){
                JSONArray layers=d.getJSONArray("layers");for(int n=0;n<layers.length();n++){JSONObject l=layers.getJSONObject(n);JSONArray objects=l.getJSONArray("objects");for(int j=0;j<objects.length();j++){JSONObject o=objects.getJSONObject(j);if(!"image".equals(o.getString("kind"))||o.getJSONObject("transform").getDouble("opacity")==0)continue;
                    Bitmap b=BitmapFactory.decodeFile(store.referenceFile(id,o.getJSONObject("reference")).getPath());try{int[] values=new int[base.length];b.getPixels(values,0,b.getWidth(),0,0,b.getWidth(),b.getHeight());int visible=0;for(int p=0;p<values.length;p++)if((values[p]>>>24)>0){assertEquals("Stored part colors",base[p],values[p]);coverage[p]++;visible++;}inventory.put(new JSONObject().put("layerId",l.getString("id")).put("objectId",o.getString("id")).put("name",l.getString("name")).put("visiblePixels",visible).put("sha256",o.getJSONObject("reference").getString("sha256")));}finally{b.recycle();}
                }}
            }
            for(int count:coverage)assertEquals("Every source pixel belongs to exactly one visible layer",1,count);
            assertEquals(sourceHash,SketchProjectStore.fileHash(source));json("Layer-Inventory.json",inventory);json("Native-Calls.json",calls);json("Native-Document.json",d);
            proofDocument=d;proofFixture=fixture;
            json("Study.json",new JSONObject().put("technical","PASS").put("visual","UNREVIEWED").put("appVersion",AppProtocol.APP_VERSION).put("width",fixture.getInt("width")).put("height",fixture.getInt("height")).put("sourceSha256",sourceHash).put("visibleImageLayers",inventory.length()).put("separationStages",stages.length()).put("renderer","VideoStudio SketchFrameRenderer / Android SDK33 native Canvas / Robolectric workspace").put("method","User-authorized source-assisted raster masks; source artwork copied, not independently drawn").put("hiddenSurfaceReconstruction",false).put("newAnglesOrHumanAnimationVerified",false).put("manualMcpEndToEnd","UNVERIFIED").put("phoneExecution",false).put("archiveSha256",SketchProjectStore.fileHash(new File(root,"Temple-Realm-Layered.vssketch"))));
        }finally{original.recycle();json("Native-Calls.json",calls);}
    }
}
