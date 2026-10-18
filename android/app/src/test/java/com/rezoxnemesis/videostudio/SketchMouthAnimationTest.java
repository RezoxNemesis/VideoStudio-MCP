package com.rezoxnemesis.videostudio;

import android.content.Context;
import android.graphics.*;
import org.json.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.*;
import java.io.File;
import java.nio.file.Files;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static org.junit.Assert.*;

/** Native authoring, editable recovery and full-frame rendering; no external artwork. */
@RunWith(RobolectricTestRunner.class) @Config(sdk=33,manifest=Config.NONE) @GraphicsMode(GraphicsMode.Mode.NATIVE)
public class SketchMouthAnimationTest {
    static File evidence(String name){String root=System.getenv("VIDEOSTUDIO_MOUTH_EVIDENCE");return new File(root==null?"build/mouth-evidence":root,name);}
    static String create(Context c,int frames,int fps)throws Exception {
        String id="mouth_"+UUID.randomUUID();SketchEditOps.execute(c,"sketch_create",new JSONObject().put("sketchId",id).put("operationId","create")
                .put("width",1280).put("height",720).put("frameCount",frames).put("fps",fps).put("background","#FFF2DFCB"));return id;
    }
    static JSONObject op(String style,String preset)throws Exception{return new JSONObject().put("type","add_mouth_animation").put("layerId","layer_1").put("mouthId","original")
            .put("settings",new JSONObject().put("style",style)).put("preset",preset);}
    static JSONObject request(String id,JSONObject op)throws Exception{return new JSONObject().put("sketchId",id).put("operationId","author").put("expectedRevision",1).put("operations",new JSONArray().put(op));}
    @Test public void aNearlyClosedMouthOccludesTheTongueRootAboveItsUpperLip()throws Exception {
        JSONObject doc=SketchDocument.create(new JSONObject().put("sketchId","small_opening").put("width",1280).put("height",720).put("frameCount",120).put("fps",30));
        SketchMouthAnimation.apply(doc,op("soft","tongue").put("poses",new JSONArray().put(SketchMouthAnimation.pose(0,.13,1,0,0,1,.5,.15,0))));
        RectF mouth=extent(visible(doc,"cavity",0)),tongue=extent(visible(doc,"tongue",0));
        try(SketchFrameRenderer renderer=new SketchFrameRenderer(RuntimeEnvironment.getApplication(),doc)){Bitmap image=renderer.render(0);try{SketchActionFilmTest.png(new File(evidence("small_opening"),"frame-0000.png"),image);}finally{image.recycle();}}
        assertTrue("Tongue root cannot paint across the upper lip",tongue.top>=mouth.top-.5);
    }
    @Test public void authoredViewsChangeAnatomyAndHideTheRearMouth()throws Exception {
        JSONObject[] documents=new JSONObject[6];String[] views={"front","left","right","back","high","low"};
        for(int i=0;i<views.length;i++){
            JSONObject doc=SketchDocument.create(new JSONObject().put("sketchId","view_"+views[i]).put("width",1280).put("height",720).put("frameCount",120).put("fps",30));
            SketchMouthAnimation.apply(doc,op("cartoon","tongue").put("settings",new JSONObject().put("style","cartoon").put("view",views[i])));SketchDocument.validate(doc);documents[i]=doc;
        }
        assertEquals("The head occludes the entire mouth from behind",0,documents[3].getJSONArray("layers").getJSONObject(0).getJSONArray("objects").length());
        RectF front=extent(visible(documents[0],"cavity",36)),left=extent(visible(documents[1],"cavity",36)),right=extent(visible(documents[2],"cavity",36));
        assertTrue("Profile is a distinct narrower opening",left.width()<front.width()*.8);assertEquals(left.width(),right.width(),1);
        Path profile=path(visible(documents[1],"cavity",36));float[] points=profile.approximate(.1f);double back=0,leading=0;for(int i=0;i<points.length;i+=3){if(points[i+1]<left.left+left.width()*.08)leading=Math.max(leading,Math.abs(points[i+2]-360));if(points[i+1]>left.right-left.width()*.08)back=Math.max(back,Math.abs(points[i+2]-360));}
        assertTrue("Profile anatomy must have a leading lip and a rear corner",Math.abs(leading-back)>12);
        assertTrue(extent(visible(documents[4],"cavity",36)).height()<front.height());
    }
    @Test public void interiorAnatomyIsOccludedAndTeethFollowTheirOwnJaw()throws Exception {
        JSONObject doc=SketchDocument.create(new JSONObject().put("sketchId","jaw_contacts").put("width",1280).put("height",720).put("frameCount",120).put("fps",30));
        JSONArray keys=new JSONArray().put(SketchMouthAnimation.pose(0,0,1,0,0,1,1,1,0))
                .put(SketchMouthAnimation.pose(20,.35,1,0,0,0,.3,.3,0)).put(SketchMouthAnimation.pose(40,.9,1,0,0,0,.3,.3,0))
                .put(SketchMouthAnimation.pose(60,.9,1,0,.8,0,.3,.3,0)).put(SketchMouthAnimation.pose(80,.7,1,0,0,1,.3,.3,0))
                .put(SketchMouthAnimation.pose(100,0,1,0,0,1,1,1,0));
        SketchMouthAnimation.apply(doc,op("soft","articulation").put("poses",keys));
        for(int frame:new int[]{0,20,40,60,80,100,119}){
            JSONObject cavity=visible(doc,"cavity",frame);
            if(frame==0||frame>=100){assertNull(cavity);for(String part:new String[]{"upper_teeth","lower_teeth","tongue","resting_tongue"})assertNull("Closed lips must hide "+part,visible(doc,part,frame));continue;}
            Path mask=path(cavity);Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);paint.setColor(Color.WHITE);Bitmap allowed=Bitmap.createBitmap(1280,720,Bitmap.Config.ARGB_8888);Canvas canvas=new Canvas(allowed);canvas.drawPath(mask,paint);paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(3);canvas.drawPath(mask,paint);paint.setStyle(Paint.Style.FILL);
            try{for(String part:new String[]{"upper_teeth","lower_teeth","resting_tongue"}){JSONObject object=visible(doc,part,frame);if(object==null)continue;Bitmap image=Bitmap.createBitmap(1280,720,Bitmap.Config.ARGB_8888);try{
                new Canvas(image).drawPath(path(object),paint);int[] pixels=new int[1280*720],permitted=pixels.clone();image.getPixels(pixels,0,1280,0,0,1280,720);allowed.getPixels(permitted,0,1280,0,0,1280,720);
                for(int i=0;i<pixels.length;i++)if(Color.alpha(pixels[i])>230)assertTrue(part+" cannot leak beyond lips at "+frame,Color.alpha(permitted[i])>0);
            }finally{image.recycle();}}}finally{allowed.recycle();}
        }
        assertNotNull("Open mouth must retain its upper teeth",visible(doc,"upper_teeth",40));
        RectF upper=extent(visible(doc,"upper_teeth",40)),upperShifted=extent(visible(doc,"upper_teeth",60));
        RectF lower=extent(visible(doc,"lower_teeth",40)),lowerShifted=extent(visible(doc,"lower_teeth",60));
        assertEquals("Upper teeth do not slide with lower jaw",upper.centerX(),upperShifted.centerX(),1);
        assertTrue("Lower teeth follow side-to-side jaw motion",lowerShifted.centerX()>lower.centerX()+20);
        assertTrue("Tongue protrudes beyond lower lip",extent(visible(doc,"tongue",80)).bottom>extent(visible(doc,"cavity",80)).bottom+50);
    }
    static JSONObject visible(JSONObject doc,String part,int frame)throws Exception {
        JSONArray objects=doc.getJSONArray("layers").getJSONObject(0).getJSONArray("objects");for(int i=0;i<objects.length();i++){JSONObject o=objects.getJSONObject(i);
            if(o.getString("id").matches("original_f[0-9]+_"+part)&&o.getInt("frame")<=frame&&o.getInt("frame")+o.getInt("exposure")>frame)return o;}return null;
    }
    private static Path path(JSONObject o)throws Exception{return SketchFrameRenderer.path(o.getJSONArray("commands"),1280,720);}
    private static RectF extent(JSONObject o)throws Exception{RectF r=new RectF();path(o).computeBounds(r,true);return r;}
    @Test public void malformedMouthRequestsPreserveExistingArtUndoAndHistory()throws Exception {
        Context c=RuntimeEnvironment.getApplication();String id=create(c,120,30);SketchEditOps.execute(c,"sketch_edit",request(id,op("soft","articulation")));JSONObject original;
        try(SketchProjectStore store=new SketchProjectStore(c)){original=store.get(id);}
        for(JSONObject invalid:new JSONObject[]{op("bad","chew").put("mouthId","bad"),op("soft","chew"),op("soft","chew").put("mouthId","bad").put("settings",new JSONObject().put("centerX",.94)),
                op("soft","chew").put("mouthId","bad").put("poses",new JSONArray().put(new JSONObject().put("frame",1))),op("soft","chew").put("mouthId","bad").put("poses",new JSONArray().put(new JSONObject().put("frame",0).put("open",1.2))),
                op("soft","chew").put("mouthId","bad").put("settings",new JSONObject().put("reference","forbidden"))}){
            assertThrows(Exception.class,()->SketchEditOps.execute(c,"sketch_edit",new JSONObject().put("sketchId",id).put("expectedRevision",2).put("operationId",UUID.randomUUID().toString()).put("operations",new JSONArray().put(invalid))));
            try(SketchProjectStore store=new SketchProjectStore(c)){assertEquals(SketchDocument.canonical(original),SketchDocument.canonical(store.get(id)));}
        }
        SketchEditOps.execute(c,"sketch_undo",new JSONObject().put("sketchId",id).put("expectedRevision",2).put("operationId","undo"));
        try(SketchProjectStore store=new SketchProjectStore(c)){assertEquals(0,store.get(id).getJSONArray("layers").getJSONObject(0).getJSONArray("objects").length());}
    }
    @Test public void mouthTimingIsPhysicalTimeBasedAtThirtyAndSixtyFpsAndFitsPortraitArt()throws Exception {
        JSONObject a=SketchDocument.create(new JSONObject().put("sketchId","thirty").put("width",1280).put("height",720).put("frameCount",120).put("fps",30));
        JSONObject b=SketchDocument.copy(a);b.put("frameCount",240).put("fps",60);
        for(String preset:new String[]{"articulation","tongue","chew"})for(int f=0;f<120;f++)assertArrayEquals(SketchMouthAnimation.sample(SketchMouthAnimation.preset(a,preset),f),SketchMouthAnimation.sample(SketchMouthAnimation.preset(b,preset),f*2),.025);
        SketchMouthAnimation.apply(b,op("fang","chew"));SketchDocument.validate(b);assertEquals(240,b.getInt("frameCount"));
        JSONObject portrait=SketchDocument.create(new JSONObject().put("sketchId","portrait").put("width",512).put("height",768).put("frameCount",96).put("fps",24));
        SketchMouthAnimation.apply(portrait,op("soft","tongue").put("settings",new JSONObject().put("height",.16)));SketchDocument.validate(portrait);assertEquals(96,portrait.getInt("frameCount"));
    }
    @Test public void nativeMouthDispatchCreatesEditableCelsAndRetriesAtomically()throws Exception {
        Context c=RuntimeEnvironment.getApplication();String id=create(c,120,30);JSONObject args=request(id,op("soft","articulation"));
        JSONObject first=SketchEditOps.execute(c,"sketch_edit",args);assertEquals(first.toString(),SketchEditOps.execute(c,"sketch_edit",args).toString());
        try(SketchProjectStore store=new SketchProjectStore(c)){JSONObject doc=store.get(id);SketchDocument.validate(doc);assertEquals(2,doc.getInt("revision"));
            JSONArray objects=doc.getJSONArray("layers").getJSONObject(0).getJSONArray("objects");assertTrue(objects.length()>100);
            for(int i=0;i<objects.length();i++){assertEquals("path",objects.getJSONObject(i).getString("kind"));assertFalse(objects.getJSONObject(i).has("reference"));}
        }
    }
    @Test public void separateMouthStudiesRenderNativelyAndRestoreTheirArtwork()throws Exception {
        Context c=RuntimeEnvironment.getApplication();
        for(String[] pair:new String[][]{{"soft","articulation"},{"cartoon","tongue"},{"fang","chew"}})for(String view:new String[]{"front","left","right","back","high","low"}){
            String name=pair[0]+"_"+pair[1]+("front".equals(view)?"":"_"+view),id=create(c,120,30);JSONObject operation=op(pair[0],pair[1]);operation.getJSONObject("settings").put("view",view);
            SketchEditOps.execute(c,"sketch_edit",request(id,operation));JSONObject doc;try(SketchProjectStore store=new SketchProjectStore(c)){doc=store.get(id);}
            File root=evidence(name);root.mkdirs();JSONArray hashes=new JSONArray();
            captureStudy(c,id,doc,view,root,hashes);
            Files.write(new File(root,"document.json").toPath(),doc.toString().getBytes(StandardCharsets.UTF_8));
            Files.write(new File(root,"native-evidence.json").toPath(),new JSONObject().put("operation",operation).put("frames",hashes).put("fullFrameExport",hashes.length()==120).put("fps",30).put("frameCount",120).put("width",1280).put("height",720)
                    .put("renderer","VideoStudio SketchNativeExport / native Android Canvas, workspace Robolectric").put("phoneExecution",false).put("androidVideoEncoding",false).put("speechVerified",false).toString(2).getBytes(StandardCharsets.UTF_8));
            SketchPaintArchive.Prepared archive=SketchPaintArchive.prepare(c,new JSONObject().put("sketchId",id).put("expectedRevision",2).put("operationId","archive"));
            Files.copy(archive.file.toPath(),new File(root,"editable.vssketch").toPath(),java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            JSONObject restored=SketchPaintArchive.restoreFile(c,archive.file,SketchProjectStore.fileHash(archive.file),"restore_"+name,"Restored mouth");
            try(SketchProjectStore store=new SketchProjectStore(c);SketchFrameRenderer before=new SketchFrameRenderer(c,doc);SketchFrameRenderer after=new SketchFrameRenderer(c,store.get(restored.getString("sketchId")))){
                for(int f:new int[]{0,12,24,36,48,60,75,90,119}){Bitmap a=before.render(f),b=after.render(f);try{assertTrue("Archive preserves mouth frame "+f,a.sameAs(b));}finally{a.recycle();b.recycle();}}
            }
        }
    }
    static void captureExport(SketchNativeExport.Prepared export,File root,JSONArray hashes)throws Exception {
        assertEquals(120,export.metadata.getInt("frameCount"));assertEquals(30,export.metadata.getInt("fps"));assertEquals(1280,export.metadata.getInt("width"));assertEquals(720,export.metadata.getInt("height"));
        Files.write(new File(root,"native-export-manifest.json").toPath(),export.metadata.toString(2).getBytes(StandardCharsets.UTF_8));
        for(int f=0;f<120;f++){
            if(System.getenv("VIDEOSTUDIO_MOUTH_EVIDENCE")==null&&f%12!=0&&f!=119)continue;
            File source=new File(export.file.getParentFile(),String.format(Locale.ROOT,"frame_%04d.png",f)),destination=new File(root,String.format(Locale.ROOT,"frames/frame-%04d.png",f));destination.getParentFile().mkdirs();
            Files.copy(source.toPath(),destination.toPath(),java.nio.file.StandardCopyOption.REPLACE_EXISTING);String hash=SketchProjectStore.fileHash(destination);
            assertEquals("Review frames are byte-for-byte native exports",export.metadata.getJSONArray("frames").getJSONObject(f).getString("sha256"),hash);
            hashes.put(new JSONObject().put("frame",f).put("sha256",hash));
        }
    }
    static void captureStudy(Context c,String id,JSONObject doc,String view,File root,JSONArray hashes)throws Exception {
        if(System.getenv("VIDEOSTUDIO_MOUTH_EVIDENCE")!=null||"front".equals(view)){
            SketchNativeExport.Prepared export=SketchNativeExport.prepare(c,new JSONObject().put("sketchId",id).put("expectedRevision",2).put("operationId","export").put("aspect","original").put("quality","720p"));captureExport(export,root,hashes);return;
        }
        // Routine CI checks native anatomy and archive recovery in every view;
        // private visual runs above export every frame of all 36 complete clips.
        try(SketchFrameRenderer renderer=new SketchFrameRenderer(c,doc)){
            for(int f:new int[]{0,12,24,36,48,60,75,90,119}){Bitmap image=renderer.render(f);try{
                File p=new File(root,String.format(Locale.ROOT,"frames/frame-%04d.png",f));SketchActionFilmTest.png(p,image);hashes.put(new JSONObject().put("frame",f).put("sha256",SketchProjectStore.fileHash(p)));
            }finally{image.recycle();}}
        }
    }
}
