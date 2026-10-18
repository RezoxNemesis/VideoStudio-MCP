package com.rezoxnemesis.videostudio;
import android.content.Context;
import android.graphics.*;
import org.json.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.*;
import java.io.File;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class) @Config(sdk=33,manifest=Config.NONE) @GraphicsMode(GraphicsMode.Mode.NATIVE)
public class SketchFaceAnimationTest {
    static File evidence(String name){return SketchMouthAnimationTest.evidence(name);}
    static JSONObject op(String view,String preset)throws Exception{return new JSONObject().put("type","add_face_animation").put("layerId","layer_1").put("faceId","original_face").put("settings",new JSONObject().put("view",view)).put("preset",preset);}
    @Test public void rearJawRespondsWhileTheHeadStillOccludesFacialFeatures()throws Exception {
        JSONObject doc=SketchDocument.create(new JSONObject().put("sketchId","rear_jaw").put("width",1280).put("height",720).put("frameCount",120).put("fps",30));
        JSONArray poses=new JSONArray().put(new JSONObject().put("frame",0).put("open",0)).put(new JSONObject().put("frame",30).put("open",1).put("jawX",.5));SketchFaceAnimation.apply(doc,op("back","chew").put("poses",poses));
        assertTrue("Visible rear chin must follow the jaw even when the mouth is hidden",bounds(part(doc,"_head",30)).bottom>bounds(part(doc,"_head",0)).bottom+20);assertNull(part(doc,"_cavity",30));assertNull(part(doc,"_nose",30));assertNull(part(doc,"_iris",30));
    }
    @Test public void sharingHappensBeforeBudgetAdmissionWhenExistingArtAlreadyUsesManyObjects()throws Exception {
        JSONObject doc=SketchDocument.create(new JSONObject().put("sketchId","existing_art").put("width",1280).put("height",720).put("frameCount",240).put("fps",60));JSONArray objects=doc.getJSONArray("layers").getJSONObject(0).getJSONArray("objects");
        for(int n=0;n<1400;n++)objects.put(SketchEditOps.completeObject(new JSONObject().put("id","artist_"+n).put("kind","line").put("points",new JSONArray("[[0.03,0.03,1],[0.04,0.04,1]]"))));
        SketchDocument.validate(doc);JSONObject artist=SketchDocument.copy(objects.getJSONObject(1399));
        JSONArray poses=new JSONArray().put(new JSONObject().put("frame",0).put("gazeX",-.5).put("interpolation","linear")).put(new JSONObject().put("frame",239).put("gazeX",.5));
        SketchFaceAnimation.apply(doc,op("front","reaction").put("poses",poses));SketchDocument.validate(doc);
        assertEquals(SketchDocument.canonical(artist),SketchDocument.canonical(SketchDocument.object(doc,"artist_1399")));assertEquals(240,doc.getInt("frameCount"));assertTrue(doc.getJSONArray("layers").getJSONObject(0).getJSONArray("objects").length()<3500);
    }
    @Test public void signedZeroCannotChangeHeldCelBoundariesAcrossJsonTransport()throws Exception {
        JSONObject doc=SketchDocument.create(new JSONObject().put("sketchId","signed_zero").put("width",1280).put("height",720).put("frameCount",120).put("fps",30));
        JSONArray raw=SketchFaceAnimation.preset(doc,"reaction"),serialized=new JSONArray(raw.toString());
        for(int f=0;f<120;f++){double[] a=SketchFaceAnimation.sample(raw,f),b=SketchFaceAnimation.sample(serialized,f);for(int c=0;c<16;c++)if(a[c]==0&&b[c]==0)assertEquals("Channel "+c+" frame "+f,Double.doubleToLongBits(a[c]),Double.doubleToLongBits(b[c]));}
    }
    @Test public void independentCelSharingPreservesEveryPixelAndRimPaintOrder()throws Exception {
        JSONObject original=SketchDocument.create(new JSONObject().put("sketchId","eye_sharing").put("width",1280).put("height",720).put("frameCount",120).put("fps",30));
        JSONArray poses=SketchEyeAnimation.preset(original,"double_blink");poses.getJSONObject(0).put("leftOpen",0).put("rightOpen",0);
        SketchEyeAnimation.apply(original,new JSONObject().put("type","add_eye_animation").put("layerId","layer_1").put("eyeId","eyes").put("settings",new JSONObject()).put("poses",poses));
        JSONObject shared=SketchDocument.copy(original);JSONObject layer=shared.getJSONArray("layers").getJSONObject(0);JSONArray compact=SketchFacialCelSharing.eyes(layer.getJSONArray("objects"),"eyes");layer.put("objects",compact);SketchDocument.validate(shared);
        assertTrue("Stable white, lid and rim components must share exposures",compact.length()<original.getJSONArray("layers").getJSONObject(0).getJSONArray("objects").length()*.8);
        try(SketchFrameRenderer before=new SketchFrameRenderer(RuntimeEnvironment.getApplication(),original);SketchFrameRenderer after=new SketchFrameRenderer(RuntimeEnvironment.getApplication(),shared)){
            for(int f=0;f<120;f++){Bitmap a=before.render(f),b=after.render(f);try{assertTrue("Sharing must preserve pixel output at "+f,a.sameAs(b));if(f==30){SketchActionFilmTest.png(new File(evidence("cel_sharing"),"before-frame-30.png"),a);SketchActionFilmTest.png(new File(evidence("cel_sharing"),"after-frame-30.png"),b);}}finally{a.recycle();b.recycle();}}
        }
    }
    @Test public void supportedSmallHighFacesDoNotFailAnInternalMouthSizeLimit()throws Exception {
        JSONObject doc=SketchDocument.create(new JSONObject().put("sketchId","small_high").put("width",1280).put("height",720).put("frameCount",120).put("fps",30));
        SketchFaceAnimation.apply(doc,op("high","playful").put("settings",new JSONObject().put("view","high").put("height",.45).put("mouthSize",.8)));SketchDocument.validate(doc);
    }
    @Test public void profileEarAndLipsAttachAndNoseLengthAffectsTheProfile()throws Exception {
        Context c=RuntimeEnvironment.getApplication();JSONObject[] docs=new JSONObject[2];
        for(int i=0;i<2;i++){String id=SketchMouthAnimationTest.create(c,120,30);JSONObject operation=op("right","reaction");operation.getJSONObject("settings").put("noseLength",i==0?.08:.22);SketchEditOps.execute(c,"sketch_edit",SketchMouthAnimationTest.request(id,operation));try(SketchProjectStore store=new SketchProjectStore(c)){docs[i]=store.get(id);}}
        RectF[] noses={bounds(part(docs[0],"_nose",24)),bounds(part(docs[1],"_nose",24))};assertTrue("Profile nose length must change real geometry",noses[1].height()>noses[0].height()+30);
        JSONObject ear=part(docs[1],"_near_ear",24);assertNotNull("Profile ear must sit on the near side",ear);assertTrue("Near ear sits behind the eye, inside skull",bounds(ear).centerX()>510&&bounds(ear).centerX()<640);
        RectF mouth=bounds(part(docs[1],"_cavity",24));assertTrue("Leading lip meets the silhouette",mouth.right>855);
    }
    private static JSONObject part(JSONObject doc,String suffix,int frame)throws Exception {JSONArray objects=doc.getJSONArray("layers").getJSONObject(0).getJSONArray("objects");for(int i=0;i<objects.length();i++){JSONObject o=objects.getJSONObject(i);if(o.getString("id").endsWith(suffix)&&o.getInt("frame")<=frame&&o.getInt("frame")+o.getInt("exposure")>frame)return o;}return null;}
    private static RectF bounds(JSONObject o)throws Exception {RectF b=new RectF();SketchFrameRenderer.path(o.getJSONArray("commands"),1280,720).computeBounds(b,true);return b;}
    @Test public void faceTimingAndJawAttachmentSurvivePortraitAndSixtyFps()throws Exception {
        JSONObject a=SketchDocument.create(new JSONObject().put("sketchId","face_thirty").put("width",1280).put("height",720).put("frameCount",120).put("fps",30));JSONObject b=SketchDocument.copy(a);b.put("fps",60).put("frameCount",240);
        for(String preset:new String[]{"reaction","playful","chew"}){JSONArray low=SketchFaceAnimation.preset(a,preset),high=SketchFaceAnimation.preset(b,preset);for(int f=0;f<120;f++)assertArrayEquals("Same face state at "+f/30d+" seconds",SketchFaceAnimation.sample(low,f),SketchFaceAnimation.sample(high,f*2),.000001);}
        SketchFaceAnimation.apply(b,op("front","playful"));SketchDocument.validate(b);assertEquals(240,b.getInt("frameCount"));assertEquals(60,b.getInt("fps"));
        JSONObject portrait=SketchDocument.create(new JSONObject().put("sketchId","face_portrait").put("width",768).put("height",1024).put("frameCount",96).put("fps",24));
        JSONObject artist=SketchEditOps.completeObject(new JSONObject().put("id","artist_mark").put("kind","line").put("points",new JSONArray("[[0.1,0.1,1],[0.2,0.15,1]]")));portrait.getJSONArray("layers").getJSONObject(0).getJSONArray("objects").put(artist);
        SketchFaceAnimation.apply(portrait,op("front","reaction").put("settings",new JSONObject().put("tilt",5)));SketchDocument.validate(portrait);assertEquals(96,portrait.getInt("frameCount"));assertEquals(24,portrait.getInt("fps"));assertEquals(SketchDocument.canonical(artist),SketchDocument.canonical(SketchDocument.object(portrait,"artist_mark")));
        JSONObject d=SketchDocument.copy(a);JSONArray poses=new JSONArray().put(new JSONObject().put("frame",0).put("open",0)).put(new JSONObject().put("frame",30).put("open",.9).put("jawX",.7));SketchFaceAnimation.apply(d,op("front","reaction").put("poses",poses));
        assertTrue("Opening the jaw must move the chin",bounds(part(d,"_head",30)).bottom>bounds(part(d,"_head",0)).bottom+20);
        assertEquals("Nose stays attached to upper skull",bounds(part(d,"_nose",0)),bounds(part(d,"_nose",30)));
    }
    @Test public void rearAndProfileFacesRespectFeatureOcclusion()throws Exception {
        Context c=RuntimeEnvironment.getApplication();
        for(String view:new String[]{"front","left","right","back","high","low"}){
            String id=SketchMouthAnimationTest.create(c,120,30);SketchEditOps.execute(c,"sketch_edit",SketchMouthAnimationTest.request(id,op(view,"reaction")));
            try(SketchProjectStore store=new SketchProjectStore(c)){JSONObject doc=store.get(id);int irises=0,noses=0,cavities=0,brows=0,heads=0;JSONArray objects=doc.getJSONArray("layers").getJSONObject(0).getJSONArray("objects");
                for(int i=0;i<objects.length();i++){JSONObject o=objects.getJSONObject(i);if(o.getInt("frame")>24||o.getInt("frame")+o.getInt("exposure")<=24)continue;String n=o.getString("id");if(n.endsWith("_iris"))irises++;if(n.endsWith("_nose"))noses++;if(n.endsWith("_cavity"))cavities++;if(n.endsWith("_brow"))brows++;if(n.endsWith("_head"))heads++;}
                assertEquals("One coherent head in "+view,1,heads);
                if("back".equals(view)){assertEquals(0,irises);assertEquals(0,noses);assertEquals(0,cavities);assertEquals(0,brows);}else{
                    assertEquals("Profile must not show a second eye",Set.of("left","right").contains(view)?1:2,irises);assertEquals(1,noses);assertEquals(1,cavities);
                }
            }
        }
    }
    @Test public void faceFailuresPreserveArtworkAndUndoRemovesTheWholeConstruction()throws Exception {
        Context c=RuntimeEnvironment.getApplication();String id=SketchMouthAnimationTest.create(c,120,30);SketchEditOps.execute(c,"sketch_edit",SketchMouthAnimationTest.request(id,op("front","reaction")));JSONObject before;
        try(SketchProjectStore store=new SketchProjectStore(c)){before=store.get(id);}
        for(JSONObject invalid:new JSONObject[]{op("front","reaction"),op("wrong","reaction").put("faceId","bad"),op("front","reaction").put("faceId","bad").put("settings",new JSONObject().put("centerX",.84).put("tilt",20)),op("front","reaction").put("faceId","bad").put("poses",new JSONArray().put(new JSONObject().put("frame",0).put("browTilt",2)))}){
            assertThrows(Exception.class,()->SketchEditOps.execute(c,"sketch_edit",new JSONObject().put("sketchId",id).put("expectedRevision",2).put("operationId",UUID.randomUUID().toString()).put("operations",new JSONArray().put(invalid))));
            try(SketchProjectStore store=new SketchProjectStore(c)){assertEquals(SketchDocument.canonical(before),SketchDocument.canonical(store.get(id)));}
        }
        SketchEditOps.execute(c,"sketch_undo",new JSONObject().put("sketchId",id).put("expectedRevision",2).put("operationId","undo"));try(SketchProjectStore store=new SketchProjectStore(c)){assertEquals(0,store.get(id).getJSONArray("layers").getJSONObject(0).getJSONArray("objects").length());}
    }
    @Test public void nativeDispatchCreatesACompleteEditableFaceAndProtectsRetries()throws Exception {
        Context c=RuntimeEnvironment.getApplication();String id=SketchMouthAnimationTest.create(c,120,30);JSONObject request=SketchMouthAnimationTest.request(id,op("front","reaction"));
        JSONObject first=SketchEditOps.execute(c,"sketch_edit",request);assertEquals(first.toString(),SketchEditOps.execute(c,"sketch_edit",request).toString());
        try(SketchProjectStore store=new SketchProjectStore(c)){JSONObject doc=store.get(id);SketchDocument.validate(doc);assertEquals(2,doc.getInt("revision"));
            JSONArray objects=doc.getJSONArray("layers").getJSONObject(0).getJSONArray("objects");boolean head=false,nose=false,eye=false,mouth=false;
            for(int i=0;i<objects.length();i++){JSONObject o=objects.getJSONObject(i);String oid=o.getString("id");assertEquals("path",o.getString("kind"));head|=oid.contains("_head");nose|=oid.contains("_nose");eye|=oid.contains("_iris");mouth|=oid.contains("_cavity");}assertTrue(head&&nose&&eye&&mouth);
        }
    }
    @Test public void allFaceViewsRenderNativelyAndRestoreEditableArtwork()throws Exception {
        Context c=RuntimeEnvironment.getApplication();
        for(String preset:new String[]{"reaction","playful","chew"})for(String view:new String[]{"front","left","right","back","high","low"}){
            String selected=System.getenv("VIDEOSTUDIO_FACE_VIEW_FILTER");if(selected!=null&&!selected.equals(view))continue;
            String name="face_"+preset+"_"+view,id=SketchMouthAnimationTest.create(c,120,30);JSONObject operation=op(view,preset);
            SketchEditOps.execute(c,"sketch_edit",SketchMouthAnimationTest.request(id,operation));JSONObject doc;try(SketchProjectStore store=new SketchProjectStore(c)){doc=store.get(id);}
            File root=evidence(name);root.mkdirs();JSONArray hashes=new JSONArray();
            SketchMouthAnimationTest.captureStudy(c,id,doc,view,root,hashes);
            Files.write(new File(root,"document.json").toPath(),doc.toString().getBytes(StandardCharsets.UTF_8));
            Files.write(new File(root,"native-evidence.json").toPath(),new JSONObject().put("operation",operation).put("frames",hashes).put("fullFrameExport",hashes.length()==120).put("fps",30).put("frameCount",120).put("width",1280).put("height",720)
                    .put("renderer","VideoStudio SketchNativeExport / native Android Canvas, workspace Robolectric").put("phoneExecution",false).put("androidVideoEncoding",false).put("speechVerified",false).put("geometry","Original authored 2D views, not genuine 3D").toString(2).getBytes(StandardCharsets.UTF_8));
            SketchPaintArchive.Prepared archive=SketchPaintArchive.prepare(c,new JSONObject().put("sketchId",id).put("expectedRevision",2).put("operationId","archive"));
            Files.copy(archive.file.toPath(),new File(root,"editable.vssketch").toPath(),StandardCopyOption.REPLACE_EXISTING);
            JSONObject restored=SketchPaintArchive.restoreFile(c,archive.file,SketchProjectStore.fileHash(archive.file),"restore_"+name,"Restored face");
            try(SketchProjectStore store=new SketchProjectStore(c);SketchFrameRenderer before=new SketchFrameRenderer(c,doc);SketchFrameRenderer after=new SketchFrameRenderer(c,store.get(restored.getString("sketchId")))){
                for(int f:new int[]{0,12,24,36,48,60,75,90,119}){Bitmap a=before.render(f),b=after.render(f);try{assertTrue("Editable face archive preserves frame "+f,a.sameAs(b));}finally{a.recycle();b.recycle();}}
            }
        }
    }
}
