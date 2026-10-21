package com.rezoxnemesis.videostudio;

import android.graphics.Bitmap;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import static org.junit.Assert.*;

/** Reusable preparation preserves actual native output and owns every mutable JSON boundary. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=33,manifest=Config.NONE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public final class SketchAnimationSamplerTest {
    @Test public void preparedStartMiddleAndEndMatchPublicPreviewNativeGoldens()throws Exception {
        for(boolean retained:new boolean[]{false,true}){
            JSONObject source=retained?retainedSource():riggedSource(),programme=programme(retained);
            String sourceBefore=canonical(source),programmeBefore=canonical(programme);
            SketchAnimationCompiler.Sampler sampler=SketchAnimationCompiler.prepare(source,programme);
            for(int frame:new int[]{0,60,119}){
                String id="sampler_golden_"+frame;
                JSONObject expected=referencePreview(source,programme,frame,id),actual=sampler.preview(frame,id);
                assertEquals(canonical(expected),canonical(actual));assertNativePixels(expected,actual);
            }
            assertEquals(sourceBefore,canonical(source));assertEquals(programmeBefore,canonical(programme));
        }
    }

    @Test public void callerAndReturnedFrameMutationCannotChangeLaterSamples()throws Exception {
        for(boolean retained:new boolean[]{false,true}){
            JSONObject source=retained?retainedSource():riggedSource(),programme=programme(retained);
            String[] expected=new String[3];int[] frames={0,60,119};
            for(int i=0;i<frames.length;i++)expected[i]=canonical(referencePreview(source,programme,frames[i],"owned_frame"));
            SketchAnimationCompiler.Sampler sampler=SketchAnimationCompiler.prepare(source,programme);
            source.put("width",257);objects(source).getJSONObject(0).getJSONObject("transform").put("x",3);
            for(int i=0;i<objects(source).length();i++){
                JSONObject object=objects(source).getJSONObject(i);
                if(object.has("human"))object.getJSONObject("human").getJSONArray("poses").getJSONObject(0).put("headTilt",19);
                if(object.has("hand"))object.getJSONObject("hand").getJSONObject("settings").put("skinColor","#FF00FF00");
                if(object.has("points"))object.getJSONArray("points").getJSONArray(0).put(0,.99);
            }
            programme.getJSONArray("dialogues").getJSONObject(0).put("text","Caller changed dialogue").getJSONObject("bounds").put("x",.6);
            programme.getJSONArray("camera").getJSONObject(0).getJSONArray("curve").put(0,.95);
            if(!retained)programme.getJSONArray("rigs").getJSONObject(0).getJSONArray("poses").getJSONObject(1)
                    .getJSONObject("rotations").put("hand",150);
            programme.put("schema","changed_after_prepare");

            JSONObject returned=sampler.preview(60,"owned_frame");assertEquals(expected[1],canonical(returned));
            JSONObject drawing=returned.getJSONObject("document");drawing.put("name","Returned frame changed");
            for(int i=0;i<objects(drawing).length();i++){
                JSONObject object=objects(drawing).getJSONObject(i);object.getJSONObject("transform").put("x",2);
                if(object.has("human"))object.getJSONObject("human").getJSONArray("poses").getJSONObject(0).put("headTilt",-19);
                if(object.has("hand"))object.getJSONObject("hand").getJSONObject("settings").put("size",.4);
                if(object.has("points"))object.getJSONArray("points").getJSONArray(0).put(0,.01);
            }
            JSONArray joints=returned.getJSONObject("sample").getJSONArray("joints");
            if(joints.length()>0)joints.getJSONObject(0).put("x",8);
            for(int i=0;i<frames.length;i++)assertEquals(expected[i],canonical(sampler.preview(frames[i],"owned_frame")));
        }
    }

    @Test public void dialogueIdsAndSamplesStayStableAcrossRepeatedConcurrentRequests()throws Exception {
        JSONObject source=riggedSource(),programme=programme(false);
        SketchAnimationCompiler.Sampler sampler=SketchAnimationCompiler.prepare(source,programme);
        int[] frames={119,0,60};String[] expected=new String[frames.length];
        for(int i=0;i<frames.length;i++)expected[i]=canonical(referencePreview(source,programme,frames[i],"repeat_"+frames[i]));
        ExecutorService worker=Executors.newFixedThreadPool(3);
        try{
            List<Future<String>> requests=new ArrayList<>();
            for(int i=0;i<18;i++){final int frame=frames[i%frames.length];requests.add(worker.submit(()->canonical(sampler.preview(frame,"repeat_"+frame))));}
            for(int i=0;i<requests.size();i++)assertEquals(expected[i%frames.length],requests.get(i).get(60,TimeUnit.SECONDS));
        }finally{worker.shutdownNow();}
    }

    @Test public void validationAndSamplingFailuresMatchThePublicPreviewBoundary()throws Exception {
        JSONObject source=riggedSource(),programme=programme(false);
        JSONObject invalidSource=SketchDocument.copy(source).put("width",257),invalidProgramme=SketchDocument.copy(programme).put("schema","invalid");
        assertSameFailure(()->referencePreview(invalidSource,programme,0,"sample"),()->SketchAnimationCompiler.prepare(invalidSource,programme));
        assertSameFailure(()->referencePreview(source,invalidProgramme,0,"sample"),()->SketchAnimationCompiler.prepare(source,invalidProgramme));
        SketchAnimationCompiler.Sampler sampler=SketchAnimationCompiler.prepare(source,programme);
        for(int frame:new int[]{-1,120})assertSameFailure(()->referencePreview(source,programme,frame,"sample"),()->sampler.preview(frame,"sample"));
        assertSameFailure(()->referencePreview(source,programme,60,"invalid/id"),()->sampler.preview(60,"invalid/id"));

        JSONObject unreachable=SketchDocument.copy(programme);
        unreachable.getJSONArray("rigs").put(new JSONObject().put("id","unreachable")
                .put("joints",new JSONArray().put(joint("hip",null,.4,.3)).put(joint("knee","hip",.4,.4)).put(joint("foot","knee",.4,.5)))
                .put("ik",new JSONArray().put(new JSONObject().put("hip","hip").put("knee","knee").put("ankle","foot")
                        .put("start",0).put("end",119).put("x",.95).put("y",.95))));
        SketchAnimationCompiler.Sampler badContact=SketchAnimationCompiler.prepare(source,unreachable);
        assertSameFailure(()->referencePreview(source,unreachable,60,"sample"),()->badContact.preview(60,"sample"));
        assertEquals(canonical(referencePreview(source,programme,60,"sample")),canonical(sampler.preview(60,"sample")));
    }

    @Test public void cancelledPreparationAndSamplingLeaveTheSamplerReusable()throws Exception {
        JSONObject source=retainedSource(),programme=programme(true);
        SketchAnimationCompiler.Sampler sampler=SketchAnimationCompiler.prepare(source,programme);
        String expected=canonical(referencePreview(source,programme,60,"after_cancel"));
        Thread.currentThread().interrupt();
        try{
            assertTrue(failure(()->referencePreview(source,programme,60,"after_cancel")) instanceof InterruptedException);
            assertTrue(failure(()->sampler.preview(60,"after_cancel")) instanceof InterruptedException);
            assertTrue(failure(()->SketchAnimationCompiler.prepare(source,programme)) instanceof InterruptedException);
        }finally{Thread.interrupted();}
        assertEquals(expected,canonical(sampler.preview(60,"after_cancel")));
    }

    @Test public void oneSamplerProducesAnExactSixtyFourFrameEditableBatch()throws Exception {
        JSONObject source=retainedSource(),programme=programme(true);String before=canonical(source);
        SketchAnimationCompiler.Sampler sampler=SketchAnimationCompiler.prepare(source,programme);
        for(int frame=0;frame<64;frame++){
            String id="batch_p"+frame;JSONObject actual=sampler.preview(frame,id),drawing=actual.getJSONObject("document");
            assertEquals(1,drawing.getInt("frameCount"));assertEquals(30,drawing.getInt("fps"));assertEquals(640,drawing.getInt("width"));
            for(int i=0;i<objects(drawing).length();i++){
                JSONObject object=objects(drawing).getJSONObject(i);
                if(object.has("human"))assertEquals(1,object.getJSONObject("human").getJSONArray("poses").length());
                if(object.has("hand"))assertEquals(1,object.getJSONObject("hand").getJSONArray("poses").length());
            }
            if(frame==0||frame==32||frame==63){JSONObject expected=referencePreview(source,programme,frame,id);
                assertEquals(canonical(expected),canonical(actual));assertNativePixels(expected,actual);}
        }
        assertEquals(before,canonical(source));
    }

    private static JSONObject document()throws Exception {
        return SketchDocument.create(new JSONObject().put("sketchId","sampler_source").put("width",640).put("height",360)
                .put("frameCount",120).put("fps",30).put("background","#FFF1E9DE"));
    }
    private static JSONObject retainedSource()throws Exception {
        JSONObject source=document();
        SketchEditOps.apply(source,new JSONObject().put("type","add_human_animation").put("layerId","layer_1").put("actorId","actor")
                .put("preset","explain").put("settings",new JSONObject().put("x",.3).put("floorY",.84).put("size",.75))
                .put("speech",new JSONObject().put("transitionMs",90).put("cues",new JSONArray()
                        .put(new JSONObject().put("startMs",0).put("endMs",1800).put("shape","open"))
                        .put(new JSONObject().put("startMs",1800).put("endMs",4000).put("shape","round")))));
        SketchEditOps.apply(source,new JSONObject().put("type","add_hand_animation").put("layerId","layer_1").put("handId","hand")
                .put("settings",new JSONObject().put("centerX",.72).put("centerY",.6).put("size",.65)));
        SketchEditOps.apply(source,new JSONObject().put("type","clear_cel").put("layerId","layer_1").put("frame",31));
        objects(source).put(rectangle());SketchDocument.validate(source);return source;
    }
    private static JSONObject riggedSource()throws Exception {
        JSONObject source=document();objects(source).put(rectangle());
        objects(source).put(SketchEditOps.completeObject(new JSONObject().put("id","skin").put("kind","line").put("exposure",120)
                .put("width",.008).put("color","#FF31558A").put("points",new JSONArray().put(point(.4,.42)).put(point(.6,.42)))));
        objects(source).put(SketchEditOps.completeObject(new JSONObject().put("id","shape").put("kind","path").put("fill",true)
                .put("color","#FF9E6352").put("exposure",120).put("commands",commands(0)).put("shapeKeys",new JSONArray()
                        .put(new JSONObject().put("frame",0).put("interpolation","smooth").put("commands",commands(0)))
                        .put(new JSONObject().put("frame",119).put("interpolation","linear").put("commands",commands(.03))))));
        SketchDocument.validate(source);return source;
    }
    private static JSONObject rectangle()throws Exception {
        // Deliberately occupies the first generated dialogue identity.
        return SketchEditOps.completeObject(new JSONObject().put("id","dialogue_0").put("kind","rect").put("fill",true)
                .put("color","#FF679A70").put("exposure",120)
                .put("bounds",new JSONObject().put("x",.65).put("y",.5).put("width",.08).put("height",.1)));
    }
    private static JSONArray point(double x,double y)throws Exception {return new JSONArray().put(x).put(y).put(1);}
    private static JSONArray commands(double shift)throws Exception {
        return new JSONArray().put(new JSONArray().put("M").put(.1).put(.2+shift))
                .put(new JSONArray().put("L").put(.2).put(.2+shift))
                .put(new JSONArray().put("L").put(.18).put(.3+shift)).put(new JSONArray().put("Z"));
    }
    private static JSONObject joint(String id,String parent,double x,double y)throws Exception {
        JSONObject joint=new JSONObject().put("id",id).put("x",x).put("y",y);if(parent!=null)joint.put("parent",parent);return joint;
    }
    private static JSONObject programme(boolean retained)throws Exception {
        JSONArray rigs=new JSONArray();
        if(!retained)rigs.put(new JSONObject().put("id","rig")
                .put("joints",new JSONArray().put(joint("root",null,.4,.42)).put(joint("hand","root",.6,.42)))
                .put("bindings",new JSONArray().put(new JSONObject().put("objectId","dialogue_0").put("jointId","hand"))
                        .put(new JSONObject().put("objectId","skin").put("jointId","root").put("weights",new JSONArray()
                                .put(new JSONObject().put("root",1)).put(new JSONObject().put("hand",1)))))
                .put("poses",new JSONArray().put(new JSONObject().put("frame",0).put("interpolation","smooth").put("rotations",new JSONObject().put("hand",0)))
                        .put(new JSONObject().put("frame",119).put("rootX",.02).put("rotations",new JSONObject().put("hand",-15)))));
        return new JSONObject().put("schema",SketchAnimationCompiler.SCHEMA).put("frameCount",120).put("fps",30).put("rigs",rigs)
                .put("cameraLayerIds",new JSONArray().put("layer_1")).put("camera",new JSONArray()
                        .put(new JSONObject().put("frame",0).put("zoom",.95).put("rotation",0).put("interpolation","bezier")
                                .put("curve",new JSONArray().put(.25).put(.1).put(.25).put(1)))
                        .put(new JSONObject().put("frame",119).put("zoom",1).put("rotation",2)))
                .put("dialogues",new JSONArray().put(new JSONObject().put("start",0).put("end",119).put("text","Sampler")
                        .put("layerId","layer_1").put("bounds",new JSONObject().put("x",.1).put("y",.04).put("width",.3).put("height",.1))));
    }
    private static JSONArray objects(JSONObject source)throws Exception {return source.getJSONArray("layers").getJSONObject(0).getJSONArray("objects");}
    private static String canonical(JSONObject value)throws Exception {return SketchDocument.canonical(value);}
    private interface Checked {void run()throws Exception;}
    private static Exception failure(Checked action)throws Exception {
        try{action.run();fail("Expected the request to fail");return null;}catch(Exception failure){return failure;}
    }
    private static void assertSameFailure(Checked expected,Checked actual)throws Exception {
        Exception a=failure(expected),b=failure(actual);assertEquals(a.getClass(),b.getClass());assertEquals(a.getMessage(),b.getMessage());
    }
    private static JSONObject referencePreview(JSONObject source,JSONObject programme,int frame,String id)throws Exception {
        // Standalone repair verification supplies a frozen copy of the pre-repair public implementation.
        String reference=System.getProperty("videostudio.sampler.referenceClass","");
        if(reference.isEmpty())return SketchAnimationCompiler.preview(source,programme,frame,id);
        try{return (JSONObject)Class.forName(reference).getMethod("preview",JSONObject.class,JSONObject.class,int.class,String.class)
                .invoke(null,source,programme,frame,id);}
        catch(InvocationTargetException failure){if(failure.getCause() instanceof Exception)throw (Exception)failure.getCause();throw (Error)failure.getCause();}
    }
    private static void assertNativePixels(JSONObject expected,JSONObject actual)throws Exception {
        try(SketchFrameRenderer a=new SketchFrameRenderer(RuntimeEnvironment.getApplication(),expected.getJSONObject("document"));
            SketchFrameRenderer b=new SketchFrameRenderer(RuntimeEnvironment.getApplication(),actual.getJSONObject("document"))){
            Bitmap x=a.render(0),y=b.render(0);try{assertTrue("Native pixels changed after sampler preparation",x.sameAs(y));}
            finally{x.recycle();y.recycle();}
        }
    }
}
