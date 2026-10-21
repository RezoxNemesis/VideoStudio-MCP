package com.rezoxnemesis.videostudio;

import java.util.Arrays;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.*;

@org.junit.runner.RunWith(org.robolectric.RobolectricTestRunner.class)
@org.robolectric.annotation.Config(sdk=33,manifest=org.robolectric.annotation.Config.NONE)
@org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
public class OmegaReferenceGeometryTest {
    private static OmegaReferenceGeometry.Point p(double x,double y){return new OmegaReferenceGeometry.Point(x,y);}
    @Test public void guideAlignmentFitsTranslationScaleAndRotation(){
        List<OmegaReferenceGeometry.Pair> data=Arrays.asList(
          new OmegaReferenceGeometry.Pair(p(.1,.1),p(.65,.15),1),
          new OmegaReferenceGeometry.Pair(p(.3,.1),p(.65,.25),1),
          new OmegaReferenceGeometry.Pair(p(.1,.3),p(.55,.15),1));
        OmegaReferenceGeometry.Fit fit=OmegaReferenceGeometry.align(data);
        assertEquals(.5,fit.scale,1e-10);
        assertEquals(0,fit.cos,1e-10);
        assertEquals(1,fit.sin,1e-10);
        assertEquals(.65,fit.x(.1,.1),1e-10);
        assertEquals(.15,fit.y(.1,.1),1e-10);
        assertEquals(0,fit.rmsError,1e-10);
    }
    @Test public void duplicateGuideLandmarksCannotFabricateTransform(){
        try{OmegaReferenceGeometry.align(Arrays.asList(
          new OmegaReferenceGeometry.Pair(p(.1,.1),p(.2,.2),1),
          new OmegaReferenceGeometry.Pair(p(.1,.1),p(.5,.7),1)));fail();
        }catch(IllegalArgumentException expected){assertTrue(expected.getMessage().contains("Degenerate"));}
    }
    @Test public void rejectsInvalidSourceGeometryAndPreservesCanvasBounds(){
        try{p(Double.NaN,.3);fail();}catch(IllegalArgumentException expected){}
        try{p(1.1,.3);fail();}catch(IllegalArgumentException expected){}
        OmegaReferenceGeometry.Fit fit=OmegaReferenceGeometry.align(Arrays.asList(
          new OmegaReferenceGeometry.Pair(p(.1,.1),p(.2,.2),1),
          new OmegaReferenceGeometry.Pair(p(.3,.3),p(.4,.4),1)));
        List<OmegaReferenceGeometry.Point> result=OmegaReferenceGeometry.transform(Arrays.asList(p(0,0),p(1,1)),fit);
        assertEquals(.1,result.get(0).x,1e-8);
        assertEquals(1,result.get(1).x,1e-8);
    }

    private static final String REF_HASH="a".repeat(64), ORIGINAL_HASH="b".repeat(64);
    private static org.json.JSONObject ownedFixture(int canvasW,int canvasH,int pngW,int pngH,int orientation) throws Exception {
        org.json.JSONObject doc=SketchDocument.create(new org.json.JSONObject()
                .put("sketchId","omega_reference_fixture").put("width",canvasW).put("height",canvasH)
                .put("frameCount",24).put("fps",24).put("background","#FFFFFFFF"));
        org.json.JSONObject ref=new org.json.JSONObject()
                .put("file",REF_HASH+".png").put("sha256",REF_HASH).put("sourceSha256",ORIGINAL_HASH)
                .put("sourceProjectId","project_fixture").put("sourceAssetId","asset_fixture")
                .put("width",pngW).put("height",pngH).put("sourceWidth",pngH).put("sourceHeight",pngW)
                .put("sourceOrientation",orientation);
        org.json.JSONObject image=SketchEditOps.completeObject(new org.json.JSONObject()
                .put("id","source_image").put("kind","image").put("reference",ref)
                .put("bounds",new org.json.JSONObject().put("x",0).put("y",0).put("width",1).put("height",1)));
        SketchEditOps.apply(doc,new org.json.JSONObject().put("type","add_object")
                .put("layerId","layer_1").put("object",image));
        SketchDocument.validate(doc);
        return doc;
    }
    private static OmegaReferenceGeometry.GuideDraft corners(OmegaReferenceGeometry.Source source) {
        return new OmegaReferenceGeometry.GuideDraft(source)
                .withLandmark("left_eye",p(.1,.1),p(.1,.1),1)
                .withLandmark("right_eye",p(.3,.1),p(.3,.1),1)
                .withLandmark("chin",p(.1,.3),p(.1,.3),1);
    }
    private interface ThrowingAction {void run() throws Exception;}
    private static void rejected(ThrowingAction action) throws Exception {
        try{action.run();fail("Must fail closed");}
        catch(IllegalArgumentException expected){}
    }
    @Test public void boundReferencePinsHashesRevisionAndUserDeclaredView() throws Exception {
        org.json.JSONObject doc=ownedFixture(512,512,256,256,1);
        OmegaReferenceGeometry.Source source=OmegaReferenceGeometry.Source.fromSketch(doc,"source_image","front");
        assertEquals(REF_HASH,source.ownedSha256);
        assertEquals(ORIGINAL_HASH,source.originalSha256);
        assertEquals("front",source.declaredView);
        OmegaReferenceGeometry.GuideDraft draft=corners(source).withContour("jaw",
                Arrays.asList(p(.1,.1),p(.3,.1),p(.2,.25)),true);
        assertEquals(1,draft.project(source,4,8).contours.size());
        doc.put("revision",2);
        OmegaReferenceGeometry.Source next=OmegaReferenceGeometry.Source.fromSketch(doc,"source_image","front");
        rejected(()->draft.project(next,4,8));
        doc.put("revision",1);
        doc.getJSONArray("layers").getJSONObject(0).getJSONArray("objects").getJSONObject(0)
                .getJSONObject("reference").put("sha256","c".repeat(64)).put("file","c".repeat(64)+".png");
        OmegaReferenceGeometry.Source changed=OmegaReferenceGeometry.Source.fromSketch(doc,"source_image","front");
        rejected(()->draft.project(changed,4,8));
        rejected(()->OmegaReferenceGeometry.Source.fromSketch(doc,"source_image",""));
    }
    @Test public void orientationMappingUsesAllEightExifCasesWithoutInventedViews() throws Exception {
        final double u=.2,v=.7;
        double[][] expected={{u,v},{1-u,v},{1-u,1-v},{u,1-v},
                {v,u},{1-v,u},{1-v,1-u},{v,1-u}};
        for(int o=1;o<=8;o++){
            OmegaReferenceGeometry.Source s=OmegaReferenceGeometry.Source.fromSketch(
                    ownedFixture(512,512,256,256,o),"source_image","user-view");
            OmegaReferenceGeometry.Point q=s.originalToOwned(p(u,v));
            assertEquals("EXIF "+o,expected[o-1][0],q.x,1e-12);
            assertEquals("EXIF "+o,expected[o-1][1],q.y,1e-12);
        }
    }
    @Test public void pixelMetricFitPreservesContourProportionsAcrossAspectRatios() throws Exception {
        OmegaReferenceGeometry.Source s=OmegaReferenceGeometry.Source.fromSketch(
                ownedFixture(1024,512,256,512,1),"source_image","profile");
        OmegaReferenceGeometry.GuideDraft draft=new OmegaReferenceGeometry.GuideDraft(s)
                .withLandmark("a",p(.1,.1),p((300+512*.1)/1024,(40+1024*.1)/512),1)
                .withLandmark("b",p(.3,.1),p((300+512*.3)/1024,(40+1024*.1)/512),1)
                .withLandmark("c",p(.1,.3),p((300+512*.1)/1024,(40+1024*.3)/512),1)
                .withContour("hand",Arrays.asList(p(.1,.1),p(.3,.1),p(.1,.3)),true);
        OmegaReferenceGeometry.Projection projected=draft.project(s,4,8);
        assertEquals(0,projected.rmsPixels,1e-8);
        OmegaReferenceGeometry.Point q=projected.contours.get(0).points.get(1);
        assertEquals((300+512*.3)/1024,q.x,1e-9);
        assertEquals((40+1024*.1)/512,q.y,1e-9);
    }
    @Test public void inconsistentLandmarksMustNotBeSilentlyAccepted() throws Exception {
        OmegaReferenceGeometry.Source s=OmegaReferenceGeometry.Source.fromSketch(
                ownedFixture(512,512,256,256,1),"source_image","front");
        OmegaReferenceGeometry.GuideDraft draft=corners(s)
                .withLandmark("bad",p(.4,.4),p(.9,.9),1)
                .withContour("jaw",Arrays.asList(p(.1,.1),p(.3,.1),p(.2,.2)),true);
        rejected(()->draft.project(s,4,8));
    }
    @Test public void collinearGuidesAreNotEnoughToAcceptAContour() throws Exception {
        OmegaReferenceGeometry.Source s=OmegaReferenceGeometry.Source.fromSketch(
                ownedFixture(512,512,256,256,1),"source_image","front");
        OmegaReferenceGeometry.GuideDraft draft=new OmegaReferenceGeometry.GuideDraft(s)
                .withLandmark("a",p(.1,.1),p(.1,.1),1)
                .withLandmark("b",p(.2,.2),p(.2,.2),1)
                .withLandmark("c",p(.3,.3),p(.3,.3),1)
                .withContour("jaw",Arrays.asList(p(.1,.1),p(.2,.1),p(.2,.2)),true);
        rejected(()->draft.project(s,4,8));
    }
    @Test public void contoursOutsideCanvasAreRejectedRatherThanClipped() throws Exception {
        OmegaReferenceGeometry.Source s=OmegaReferenceGeometry.Source.fromSketch(
                ownedFixture(512,512,256,256,1),"source_image","front");
        OmegaReferenceGeometry.GuideDraft shifted=new OmegaReferenceGeometry.GuideDraft(s)
                .withLandmark("a",p(.1,.1),p(.7,.7),1)
                .withLandmark("b",p(.3,.1),p(.8,.7),1)
                .withLandmark("c",p(.1,.3),p(.7,.8),1)
                .withContour("off_canvas",Arrays.asList(p(.1,.1),p(.9,.1),p(.9,.9)),true);
        rejected(()->shifted.project(s,4,8));
    }
    @Test public void guideRevisionsAndContoursAreImmutableEditableDrafts() throws Exception {
        OmegaReferenceGeometry.Source s=OmegaReferenceGeometry.Source.fromSketch(
                ownedFixture(512,512,256,256,1),"source_image","front");
        OmegaReferenceGeometry.GuideDraft original=corners(s);
        OmegaReferenceGeometry.GuideDraft updated=original.withLandmark("left_eye",p(.1,.1),p(.11,.1),1)
                .withContour("jaw",Arrays.asList(p(.1,.1),p(.3,.1),p(.2,.2)),true);
        assertEquals(3,original.landmarks.size());
        assertEquals(0,original.contours.size());
        assertEquals(1,updated.contours.size());
        assertEquals(.1,original.landmarks.get(0).target.x,0);
        assertEquals(.11,updated.landmarks.get(0).target.x,0);
        try{updated.landmarks.clear();fail();}catch(UnsupportedOperationException expected){}
        try{updated.contours.get(0).points.clear();fail();}catch(UnsupportedOperationException expected){}
    }
    @Test public void nativeAtomicSketchOperationsProduceEditableNotRasterArtwork() throws Exception {
        org.json.JSONObject doc=ownedFixture(512,512,256,256,1);
        OmegaReferenceGeometry.Source s=OmegaReferenceGeometry.Source.fromSketch(doc,"source_image","front");
        OmegaReferenceGeometry.GuideDraft draft=corners(s).withContour("jaw",
                Arrays.asList(p(.1,.1),p(.3,.1),p(.2,.3)),true);
        org.json.JSONArray ops=OmegaReferenceGeometry.prepareEdit(doc,draft,"layer_1",0,2,
                "reference_guides","#FF000000",.003,4,8);
        assertEquals(1,ops.length());
        org.json.JSONObject object=ops.getJSONObject(0).getJSONObject("object");
        assertEquals("polygon",object.getString("kind"));
        assertFalse(object.has("reference"));
        assertFalse(object.has("paint"));
        org.json.JSONObject clone=SketchDocument.copy(doc);
        SketchEditOps.apply(clone,ops.getJSONObject(0));
        SketchDocument.validate(clone);
        assertEquals(2,clone.getJSONArray("layers").getJSONObject(0).getJSONArray("objects").length());
        assertEquals(3,object.getJSONArray("points").length());
    }
    @Test public void lockedLayerAndStaleDocumentDoNotProduceOperations() throws Exception {
        org.json.JSONObject doc=ownedFixture(512,512,256,256,1);
        OmegaReferenceGeometry.Source s=OmegaReferenceGeometry.Source.fromSketch(doc,"source_image","front");
        OmegaReferenceGeometry.GuideDraft draft=corners(s).withContour("outline",
                Arrays.asList(p(.1,.1),p(.2,.1),p(.2,.2)),false);
        doc.getJSONArray("layers").getJSONObject(0).put("locked",true);
        try{OmegaReferenceGeometry.prepareEdit(doc,draft,"layer_1",0,1,"guide","#FF000000",.003,4,8);fail();}
        catch(IllegalStateException expected){}
        doc.getJSONArray("layers").getJSONObject(0).put("locked",false);
        doc.put("revision",2);
        rejected(()->{try{OmegaReferenceGeometry.prepareEdit(doc,draft,"layer_1",0,1,"guide","#FF000000",.003,4,8);}
                catch(IllegalArgumentException error){throw error;}catch(Exception error){throw new RuntimeException(error);}});
    }



    /** Keep synthetic *native* PNG/JSON proof with Gradle's uploaded JUnit reports. */
    private static java.io.File phase2EvidenceDirectory() throws Exception {
        java.io.File current=new java.io.File(System.getProperty("user.dir")).getCanonicalFile();
        for(java.io.File root=current;root!=null;root=root.getParentFile()) {
            java.io.File app=new java.io.File(root,"app/build.gradle.kts");
            if(app.isFile()){
                java.io.File dir=new java.io.File(root,"app/build/test-results/testDebugUnitTest/omega-phase2-native-evidence");
                if(!dir.isDirectory()&&!dir.mkdirs())throw new java.io.IOException("No CI artifact directory");
                return dir;
            }
            if(new java.io.File(root,"src/test/java").isDirectory()
                    &&new java.io.File(root,"build.gradle.kts").isFile()) {
                java.io.File dir=new java.io.File(root,"build/test-results/testDebugUnitTest/omega-phase2-native-evidence");
                if(!dir.isDirectory()&&!dir.mkdirs())throw new java.io.IOException("No CI artifact directory");
                return dir;
            }
        }
        throw new java.io.IOException("Cannot resolve native test JUnit artifact directory");
    }
    private static void writeNativePng(android.graphics.Bitmap bitmap,java.io.File file) throws Exception {
        try(java.io.FileOutputStream output=new java.io.FileOutputStream(file)){
            assertTrue("Native Bitmap PNG output",bitmap.compress(
                    android.graphics.Bitmap.CompressFormat.PNG,100,output));
        }
        assertTrue(file.isFile()&&file.length()>0);
    }
    private static void nativeEvidence(org.json.JSONObject original,byte[] candidatePng,
                                       java.io.File ownedSource,String ownedHash,
                                       org.json.JSONObject preview,android.content.Context context) throws Exception {
        java.io.File root=phase2EvidenceDirectory();
        android.graphics.Bitmap source=android.graphics.BitmapFactory.decodeFile(ownedSource.getPath());
        android.graphics.Bitmap after=android.graphics.BitmapFactory.decodeByteArray(
                candidatePng,0,candidatePng.length);
        android.graphics.Bitmap before=null,triptych=null;
        if(source==null||after==null)throw new java.io.IOException("Native proof bitmap decode failed");
        try {
            try(SketchFrameRenderer renderer=new SketchFrameRenderer(context,original)){
                before=renderer.render(0);
            }
            assertEquals(before.getWidth(),after.getWidth());
            assertEquals(before.getHeight(),after.getHeight());
            assertEquals(source.getWidth(),before.getWidth());
            assertEquals(source.getHeight(),before.getHeight());
            writeNativePng(source,new java.io.File(root,"01-owned-reference-target.png"));
            writeNativePng(before,new java.io.File(root,"02-native-before.png"));
            writeNativePng(after,new java.io.File(root,"03-native-after.png"));
            triptych=android.graphics.Bitmap.createBitmap(3*before.getWidth(),before.getHeight(),
                    android.graphics.Bitmap.Config.ARGB_8888);
            android.graphics.Canvas sheet=new android.graphics.Canvas(triptych);
            sheet.drawBitmap(source,0,0,null);
            sheet.drawBitmap(before,before.getWidth(),0,null);
            sheet.drawBitmap(after,2*before.getWidth(),0,null);
            writeNativePng(triptych,new java.io.File(root,"04-target-before-after-contact-sheet.png"));
            org.json.JSONObject manifest=new org.json.JSONObject()
                    .put("provenance","synthetic original Android Canvas PNG fixture (no Gallery, no personal data)")
                    .put("rendering","SketchFrameRenderer SDK33 native Robolectric Canvas, NOT physical device")
                    .put("frameIndex",0).put("referenceView","front")
                    .put("ownedReferenceSha256",ownedHash)
                    .put("originalSourceSha256",preview.getString("sourceSha256"))
                    .put("previewPngSha256",SketchDocument.sha256(candidatePng))
                    .put("changedPixels",preview.getInt("changedPixels"))
                    .put("landmarkRmsPixels",preview.getDouble("rmsLandmarkPixels"))
                    .put("landmarkWorstPixels",preview.getDouble("maxLandmarkPixels"))
                    .put("nativeEditableObjects",preview.getInt("editableObjectCount"))
                    .put("imageWidth",before.getWidth()).put("imageHeight",before.getHeight())
                    .put("artistAccuracy","MANUAL_REVIEW_REQUIRED; synthetic fixture, not owner target");
            java.nio.file.Files.write(
                    new java.io.File(root,"05-native-evidence-manifest.json").toPath(),
                    manifest.toString(2).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }finally {
            if(triptych!=null)triptych.recycle();
            if(before!=null)before.recycle();
            after.recycle();source.recycle();
        }
    }

    @Test public void nativeOwnedReferencePreviewCommitAndUndoPreserveSourcePixels() throws Exception {
        java.util.concurrent.ExecutorService worker=java.util.concurrent.Executors.newSingleThreadExecutor();
        try {
            worker.submit(()->{
                try {
                    android.content.Context ctx=org.robolectric.RuntimeEnvironment.getApplication();
                    String id="omega_ref_"+java.util.UUID.randomUUID().toString().replace("-","");
                    android.graphics.Bitmap bitmap=android.graphics.Bitmap.createBitmap(256,256,
                            android.graphics.Bitmap.Config.ARGB_8888);
                    java.io.File png=new java.io.File(ctx.getFilesDir(),id+"_fixture.png");
                    try{
                        bitmap.eraseColor(0xffffffff);
                        android.graphics.Canvas canvas=new android.graphics.Canvas(bitmap);
                        android.graphics.Paint ink=new android.graphics.Paint();
                        ink.setColor(0xff1166aa);
                        canvas.drawCircle(128,128,70,ink);
                        try(java.io.FileOutputStream out=new java.io.FileOutputStream(png)){
                            assertTrue(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,out));
                        }
                    }finally{bitmap.recycle();}
                    ProjectStore projects=new ProjectStore(ctx);
                    ProjectStore.Project project=projects.create("OMEGA 2 owned Android Canvas fixture");
                    ProjectStore.Asset asset=projects.registerImportedAsset(project.id,
                            android.net.Uri.fromFile(png),png.getName(),"image/png",0,png.length());
                    SketchEditOps.execute(ctx,"sketch_create",new org.json.JSONObject()
                            .put("sketchId",id).put("operationId","create").put("width",256).put("height",256)
                            .put("frameCount",24).put("fps",24).put("background","#FFFFFFFF"));
                    SketchEditOps.execute(ctx,"sketch_import_reference",new org.json.JSONObject()
                            .put("sketchId",id).put("operationId","import").put("expectedRevision",1)
                            .put("projectId",project.id).put("sourceProjectUpdatedAt",projects.get(project.id).updatedAt)
                            .put("assetId",asset.id).put("layerId","layer_1").put("objectId","source_image")
                            .put("guideVisible",false));
                    org.json.JSONObject before;
                    String hash;
                    java.io.File ownedSource;
                    try(SketchProjectStore store=new SketchProjectStore(ctx)){
                        before=store.get(id);
                        ownedSource=store.referenceFile(id,
                                SketchDocument.object(before,"source_image").getJSONObject("reference"));
                        hash=SketchProjectStore.fileHash(ownedSource);
                    }
                    OmegaReferenceGeometry.Source source=OmegaReferenceGeometry.captureNative(ctx,id,"source_image","front");
                    // A known, synthetic circle has manually specified reference contours;
                    // this is an engineering fixture, NOT automatic recognition or art certification.
                    List<OmegaReferenceGeometry.Point> contour=new java.util.ArrayList<>();
                    for(int n=0;n<32;n++){
                        double angle=2*Math.PI*n/32.0;
                        contour.add(p(.5+(70.0/256)*Math.cos(angle),.5+(70.0/256)*Math.sin(angle)));
                    }
                    OmegaReferenceGeometry.GuideDraft draft=corners(source)
                            .withContour("reference_outline",contour,true);
                    org.json.JSONObject preview=OmegaReferenceGeometry.previewNative(ctx,draft,"layer_1",
                            0,2,"guide","#FFCC1133",.01,4,8,512);
                    assertFalse(preview.getBoolean("sourceModified"));
                    assertEquals(hash,preview.getString("sourceReferenceSha256"));
                    assertTrue(preview.getInt("changedPixels")>0);
                    org.json.JSONObject screenshot=preview.getJSONObject("sketchPreviewImage");
                    byte[] actual=android.util.Base64.decode(screenshot.getString("base64"),android.util.Base64.NO_WRAP);
                    assertEquals(SketchDocument.sha256(actual),screenshot.getString("sha256"));
                    nativeEvidence(before,actual,ownedSource,hash,preview,ctx);
                    try(SketchProjectStore store=new SketchProjectStore(ctx)){
                        assertEquals(SketchDocument.canonical(before),SketchDocument.canonical(store.get(id)));
                        assertEquals(hash,SketchProjectStore.fileHash(store.referenceFile(id,
                                SketchDocument.object(before,"source_image").getJSONObject("reference"))));
                    }
                    OmegaReferenceGeometry.commitNative(ctx,draft,"layer_1",0,2,"guide",
                            "#FFCC1133",.01,4,8,"commit_guides");
                    try(SketchProjectStore store=new SketchProjectStore(ctx)){
                        org.json.JSONObject committed=store.get(id);
                        assertEquals(3,committed.getLong("revision"));
                        assertEquals("polygon",SketchDocument.object(committed,"guide_reference_outline").getString("kind"));
                        assertEquals(hash,SketchProjectStore.fileHash(store.referenceFile(id,
                                SketchDocument.object(committed,"source_image").getJSONObject("reference"))));
                    }
                    SketchEditOps.execute(ctx,"sketch_undo",new org.json.JSONObject()
                            .put("sketchId",id).put("expectedRevision",3).put("operationId","undo_guides"));
                    try(SketchProjectStore store=new SketchProjectStore(ctx)){
                        org.json.JSONObject restored=store.get(id);
                        assertEquals(4,restored.getLong("revision"));
                        assertEquals(1,restored.getJSONArray("layers").getJSONObject(0).getJSONArray("objects").length());
                        assertEquals(hash,SketchProjectStore.fileHash(store.referenceFile(id,
                                SketchDocument.object(restored,"source_image").getJSONObject("reference"))));
                    }
                    return null;
                }catch(Exception e){throw new RuntimeException(e);}
            }).get(120,java.util.concurrent.TimeUnit.SECONDS);
        }finally{worker.shutdownNow();}
    }

}
