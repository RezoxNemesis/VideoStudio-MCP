package com.rezoxnemesis.videostudio;

import java.util.Arrays;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.*;

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
    private static void rejected(Runnable action) {
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

}
