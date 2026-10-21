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
import java.util.UUID;
import static org.junit.Assert.*;

/** All authoring and rasterization use the app's actual native command/export surfaces. */
@RunWith(RobolectricTestRunner.class) @Config(sdk=33,manifest=Config.NONE) @GraphicsMode(GraphicsMode.Mode.NATIVE)
public class SketchEyeAnimationTest {
    @Test public void eyesFitPortraitArtworkAndPreserveAnExistingArtistDrawing()throws Exception {
        JSONObject doc=SketchDocument.create(new JSONObject().put("sketchId","portrait_eyes").put("width",512).put("height",768).put("frameCount",96).put("fps",24));
        JSONObject ink=SketchEditOps.completeObject(new JSONObject().put("id","artist_mark").put("kind","line").put("points",new JSONArray("[[0.1,0.1,1],[0.3,0.2,1]]")));
        doc.getJSONArray("layers").getJSONObject(0).getJSONArray("objects").put(ink);
        JSONObject operation=op("round","look_blink").put("settings",new JSONObject().put("style","round").put("height",.10).put("irisSize",.85));
        SketchEyeAnimation.apply(doc,operation);SketchDocument.validate(doc);
        assertEquals(SketchDocument.canonical(ink),SketchDocument.canonical(SketchDocument.object(doc,"artist_mark")));
        RectF bounds=new RectF();SketchFrameRenderer.path(visible(doc,"left","pupil",0).getJSONArray("commands"),512,768).computeBounds(bounds,true);
        assertEquals("A portrait canvas must not stretch a circular pupil",bounds.width(),bounds.height(),1);
        assertEquals(96,doc.getInt("frameCount"));assertEquals(24,doc.getInt("fps"));
    }
    @Test public void almondBlinkUsesAnUpperLidLeadAndKeepsTheCornersFixed() {
        double[] full=verticalExtent(SketchEyeAnimation.aperture("almond",400,300,280,160,1));
        double[] half=verticalExtent(SketchEyeAnimation.aperture("almond",400,300,280,160,.5));
        double upperTravel=half[0]-full[0],lowerTravel=full[1]-half[1];
        assertTrue("Human-like blinks must be led by the upper lid, rather than squeeze symmetrically",upperTravel>3*lowerTravel);
        assertEquals("Eye corners stay fixed",260,half[2],.2);assertEquals(540,half[3],.2);
    }
    private static double[] verticalExtent(Path path){float[] points=path.approximate(.1f);double[] b={Double.POSITIVE_INFINITY,Double.NEGATIVE_INFINITY,Double.POSITIVE_INFINITY,Double.NEGATIVE_INFINITY};for(int i=0;i<points.length;i+=3){b[0]=Math.min(b[0],points[i+2]);b[1]=Math.max(b[1],points[i+2]);b[2]=Math.min(b[2],points[i+1]);b[3]=Math.max(b[3],points[i+1]);}return b;}
    @Test public void lidsOccludeRoundPupilsWithoutSquashingOrLeakingThem()throws Exception {
        Context c=RuntimeEnvironment.getApplication();String id=create(c,120,30);JSONArray poses=new JSONArray()
                .put(SketchEyeAnimation.pose(0,0,0,1,1,1,0)).put(SketchEyeAnimation.pose(30,0,0,.4,1,1,0))
                .put(SketchEyeAnimation.pose(45,0,0,0,0,1,0)).put(SketchEyeAnimation.pose(60,.85,.65,1,1,1,0))
                .put(SketchEyeAnimation.pose(90,0,0,1,1,1.3,.5)).put(SketchEyeAnimation.pose(119,0,0,1,1,1,0));
        SketchEditOps.execute(c,"sketch_edit",request(id,op("round","look_blink").put("poses",poses)));JSONObject doc;
        try(SketchProjectStore store=new SketchProjectStore(c)){doc=store.get(id);}
        for(int f:new int[]{0,15,30,44,45,46,60,90,119})for(String side:new String[]{"left","right"}){
            JSONObject white=visible(doc,side,"sclera",f),pupil=visible(doc,side,"pupil",f),iris=visible(doc,side,"iris",f);
            if(f==45){assertNull("Closed lids reveal no sclera",white);assertNull("Closed lids reveal no pupil",pupil);assertNull(iris);continue;}
            if(white!=null){
                Bitmap mask=Bitmap.createBitmap(1280,720,Bitmap.Config.ARGB_8888);Canvas canvas=new Canvas(mask);Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);paint.setColor(Color.WHITE);
                Path opening=SketchFrameRenderer.path(white.getJSONArray("commands"),1280,720);canvas.drawPath(opening,paint);paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(3);canvas.drawPath(opening,paint);
                int[] allowed=new int[1280*720];mask.getPixels(allowed,0,1280,0,0,1280,720);
                for(JSONObject part:new JSONObject[]{iris,pupil})if(part!=null){
                    Bitmap ink=Bitmap.createBitmap(1280,720,Bitmap.Config.ARGB_8888);try{
                        paint.setStyle(Paint.Style.FILL);new Canvas(ink).drawPath(SketchFrameRenderer.path(part.getJSONArray("commands"),1280,720),paint);
                        int[] pixels=new int[1280*720];ink.getPixels(pixels,0,1280,0,0,1280,720);
                        for(int i=0;i<pixels.length;i++)if(Color.alpha(pixels[i])>220)assertTrue("Iris/pupil leak outside lid at "+f,Color.alpha(allowed[i])>0);
                    }finally{ink.recycle();}
                }
                mask.recycle();
            }
        }
        RectF open=new RectF(),half=new RectF();SketchFrameRenderer.path(visible(doc,"left","pupil",0).getJSONArray("commands"),1280,720).computeBounds(open,true);
        SketchFrameRenderer.path(visible(doc,"left","pupil",30).getJSONArray("commands"),1280,720).computeBounds(half,true);
        assertEquals("The pupil is still a circle when the eyelid partially closes",open.height(),half.height(),1.0);
        assertEquals(half.width(),half.height(),1.0);
    }
    @Test public void blinkClockAndHighFrameRateKeepTheirTimingAndDrawingBudgets()throws Exception {
        Context c=RuntimeEnvironment.getApplication();JSONObject[] docs=new JSONObject[2];
        for(int n=0;n<2;n++){
            String id=create(c,n==0?120:240,n==0?30:60);SketchEditOps.execute(c,"sketch_edit",request(id,op("almond","look_blink")));
            try(SketchProjectStore store=new SketchProjectStore(c)){docs[n]=store.get(id);SketchDocument.validate(docs[n]);}
        }
        try(SketchFrameRenderer a=new SketchFrameRenderer(c,docs[0]);SketchFrameRenderer b=new SketchFrameRenderer(c,docs[1])){
            for(double seconds:new double[]{0,.45,1.5,1.6,2.8,3.4}){
                Bitmap x=a.render((int)Math.round(seconds*30)),y=b.render((int)Math.round(seconds*60));try{assertTrue("Requested frame rate must preserve the same physical eye pose at "+seconds,x.sameAs(y));}finally{x.recycle();y.recycle();}
            }
        }
        String id=create(c,240,60);SketchEditOps.execute(c,"sketch_edit",request(id,op("round","tracking")));
        try(SketchProjectStore store=new SketchProjectStore(c)){SketchDocument.validate(store.get(id));assertEquals(240,store.get(id).getInt("frameCount"));assertEquals(60,store.get(id).getInt("fps"));}
    }
    static JSONObject visible(JSONObject doc,String side,String part,int frame)throws Exception {
        JSONArray objects=doc.getJSONArray("layers").getJSONObject(0).getJSONArray("objects");
        for(int n=0;n<objects.length();n++){JSONObject o=objects.getJSONObject(n);if(o.getString("id").contains("_"+side+"_")&&o.getString("id").endsWith("_"+part)&&o.getInt("frame")<=frame&&frame<o.getInt("frame")+o.getInt("exposure"))return o;}return null;
    }
    static File evidence(String name){return new File(System.getenv("VIDEOSTUDIO_EYE_EVIDENCE")==null?"build/native-eye-evidence":System.getenv("VIDEOSTUDIO_EYE_EVIDENCE"),name);}
    static String create(Context c,int frames,int fps)throws Exception {
        String id="eyes_"+UUID.randomUUID();
        SketchEditOps.execute(c,"sketch_create",new JSONObject().put("sketchId",id).put("operationId","create")
                .put("width",1280).put("height",720).put("frameCount",frames).put("fps",fps).put("background","#FFF2DFCB"));return id;
    }
    static JSONObject op(String style,String preset)throws Exception{return new JSONObject().put("type","add_eye_animation")
            .put("layerId","layer_1").put("eyeId","original").put("settings",new JSONObject().put("style",style)).put("preset",preset);}
    static JSONObject request(String id,JSONObject op)throws Exception{return new JSONObject().put("sketchId",id).put("operationId","author")
            .put("expectedRevision",1).put("operations",new JSONArray().put(op));}
    @Test public void nativeDispatchCreatesEditableEyesAndRetriesWithoutDuplicatingThem()throws Exception {
        Context c=RuntimeEnvironment.getApplication();String id=create(c,120,30);JSONObject args=request(id,op("almond","look_blink"));
        JSONObject first=SketchEditOps.execute(c,"sketch_edit",args);assertEquals(first.toString(),SketchEditOps.execute(c,"sketch_edit",args).toString());
        try(SketchProjectStore store=new SketchProjectStore(c)){
            JSONObject d=store.get(id);SketchDocument.validate(d);assertEquals(2,d.getInt("revision"));
            JSONArray objects=d.getJSONArray("layers").getJSONObject(0).getJSONArray("objects");assertTrue(objects.length()>100);
            for(int i=0;i<objects.length();i++){assertEquals("path",objects.getJSONObject(i).getString("kind"));assertFalse(objects.getJSONObject(i).has("reference"));}
        }
    }
    @Test public void threeSeparateEyeStylesProduceFullNativeExportsAndRecoverTheirArtwork()throws Exception {
        Context c=RuntimeEnvironment.getApplication();
        for(String[] pair:new String[][]{{"round","look_blink"},{"almond","tracking"},{"slit","double_blink"}}){
            String name=pair[0]+"_"+pair[1],id=create(c,120,30);JSONObject operation=op(pair[0],pair[1]);
            if("almond".equals(pair[0]))operation.getJSONObject("settings").put("irisColor","#FF7E694A").put("height",.18);
            if("slit".equals(pair[0]))operation.getJSONObject("settings").put("irisColor","#FFB6903C").put("height",.20).put("tilt",-10);
            SketchEditOps.execute(c,"sketch_edit",request(id,operation));JSONObject doc;
            try(SketchProjectStore store=new SketchProjectStore(c)){doc=store.get(id);}
            File root=evidence(name);root.mkdirs();JSONArray hashes=new JSONArray();
            SketchNativeExport.Prepared export=SketchNativeExport.prepare(c,new JSONObject().put("sketchId",id).put("expectedRevision",2)
                    .put("operationId","export").put("aspect","original").put("quality","720p"));
            try(SketchExportFrameSource frames=export.openFrames()){
                for(int f=0;f<120;f++){Bitmap image=frames.render(f,1280,720);try{
                    File p=new File(root,String.format(java.util.Locale.ROOT,"frames/frame-%04d.png",f));SketchActionFilmTest.png(p,image);
                    hashes.put(new JSONObject().put("frame",f).put("sha256",SketchProjectStore.fileHash(p)));
                }finally{image.recycle();}}
            }
            Files.write(new File(root,"document.json").toPath(),doc.toString().getBytes(StandardCharsets.UTF_8));
            Files.write(new File(root,"native-evidence.json").toPath(),new JSONObject().put("operation",operation).put("frames",hashes)
                    .put("fps",30).put("frameCount",120).put("width",1280).put("height",720)
                    .put("renderer","VideoStudio SketchNativeExport / native Android Canvas, workspace Robolectric")
                    .put("phoneExecution",false).put("androidVideoEncoding",false).toString(2).getBytes(StandardCharsets.UTF_8));
            SketchPaintArchive.Prepared archive=SketchPaintArchive.prepare(c,new JSONObject().put("sketchId",id).put("expectedRevision",2).put("operationId","archive"));
            Files.copy(archive.file.toPath(),new File(root,"editable.vssketch").toPath(),java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            JSONObject restored=SketchPaintArchive.restoreFile(c,archive.file,SketchProjectStore.fileHash(archive.file),"restore_"+name,"Restored eyes");
            try(SketchProjectStore store=new SketchProjectStore(c);SketchFrameRenderer before=new SketchFrameRenderer(c,doc);SketchFrameRenderer after=new SketchFrameRenderer(c,store.get(restored.getString("sketchId")))){
                for(int f:new int[]{0,15,45,51,53,60,85,105,119}){Bitmap a=before.render(f),b=after.render(f);try{assertTrue("Editable eye archive preserves frame "+f,a.sameAs(b));}finally{a.recycle();b.recycle();}}
            }
        }
    }
    @Test public void invalidEyeEditsLeaveExistingDrawingAndHistoryUntouched()throws Exception {
        Context c=RuntimeEnvironment.getApplication();String id=create(c,120,30);SketchEditOps.execute(c,"sketch_edit",request(id,op("round","look_blink")));JSONObject original;
        try(SketchProjectStore store=new SketchProjectStore(c)){original=store.get(id);}
        for(JSONObject invalid:new JSONObject[]{op("unknown","tracking").put("eyeId","bad"),op("round","tracking"),
                op("round","tracking").put("eyeId","bad").put("settings",new JSONObject().put("url","https://example.test/reference")),
                op("round","tracking").put("eyeId","bad").put("settings",new JSONObject().put("centerX",.90))}){
            assertThrows(Exception.class,()->SketchEditOps.execute(c,"sketch_edit",new JSONObject().put("sketchId",id).put("expectedRevision",2)
                    .put("operationId",UUID.randomUUID().toString()).put("operations",new JSONArray().put(invalid))));
            try(SketchProjectStore store=new SketchProjectStore(c)){assertEquals(SketchDocument.canonical(original),SketchDocument.canonical(store.get(id)));}
        }
        SketchEditOps.execute(c,"sketch_undo",new JSONObject().put("sketchId",id).put("expectedRevision",2).put("operationId","undo"));
        try(SketchProjectStore store=new SketchProjectStore(c)){assertEquals(0,store.get(id).getJSONArray("layers").getJSONObject(0).getJSONArray("objects").length());}
    }
}
