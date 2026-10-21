package com.rezoxnemesis.videostudio;

import java.lang.reflect.*;
import org.json.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

/** Recipe and native operation proofs; no rendered-quality claim. */
@RunWith(RobolectricTestRunner.class) @Config(sdk=33,manifest=Config.NONE)
public class SketchHandStudiesTest {
    static final String[] PATTERNS={"open_close","grasp","finger_cascade","thumb_opposition"};
    static JSONArray poses(String pattern,int frames,int fps,JSONObject settings)throws Exception {
        Method method=Class.forName("com.rezoxnemesis.videostudio.SketchHandStudies").getDeclaredMethod("poses",String.class,int.class,int.class,JSONObject.class);method.setAccessible(true);
        try{return (JSONArray)method.invoke(null,pattern,frames,fps,settings);}catch(InvocationTargetException e){throw (Exception)e.getCause();}
    }
    static String json(Object value)throws Exception {return SketchDocument.canonical(value instanceof JSONObject?SketchDocument.copy((JSONObject)value):new JSONArray(value.toString()));}
    @Test public void baselineExactlyMatchesUnchangedMatrixRecipesAndOriginalPreset()throws Exception {
        Class<?> studies=Class.forName("com.rezoxnemesis.videostudio.SketchHandStudies");Field names=studies.getDeclaredField("PATTERNS");names.setAccessible(true);assertArrayEquals(PATTERNS,(String[])names.get(null));
        Method original=NativeHumanCapabilityMatrixTest.class.getDeclaredMethod("handPoses",String.class);original.setAccessible(true);
        JSONObject settings=SketchHandAnimation.defaults().put("size",.95).put("renderStyle","cel");
        for(String pattern:PATTERNS){JSONArray expected=pattern.equals("open_close")?SketchHandAnimation.preset(96,24,settings):(JSONArray)original.invoke(null,pattern),actual=poses(pattern,96,24,settings);assertEquals(pattern,json(expected),json(actual));for(int frame=0;frame<96;frame++)assertArrayEquals(SketchHandAnimation.poseAt(expected,frame),SketchHandAnimation.poseAt(actual,frame),0);}
    }
    @Test public void validClocksScaleOnlyKeyFramesAndPreserveRecipeValues()throws Exception {
        JSONObject settings=SketchHandAnimation.defaults().put("motion",.4).put("wristMotion",.2);
        for(int[] clock:new int[][]{{96,24},{120,24},{120,30},{180,30},{240,30},{240,60}})for(String pattern:PATTERNS){
            JSONArray result=poses(pattern,clock[0],clock[1],settings);SketchHandAnimation.validatePoses(result,clock[0]);assertEquals(0,result.getJSONObject(0).getInt("frame"));assertEquals(clock[0]-1,result.getJSONObject(result.length()-1).getInt("frame"));
            if(pattern.equals("open_close"))assertEquals(json(SketchHandAnimation.preset(clock[0],clock[1],settings)),json(result));
            else {JSONArray baseline=poses(pattern,96,24,settings);for(int i=0;i<6;i++){JSONObject expected=new JSONObject(baseline.getJSONObject(i).toString()).put("frame",Math.round(baseline.getJSONObject(i).getInt("frame")*(clock[0]-1)/95d));assertEquals(json(expected),json(result.getJSONObject(i)));}}
        }
    }
    @Test public void rejectsUnknownPatternsInvalidClocksAndSettingsWithoutMutatingInput()throws Exception {
        JSONObject settings=SketchHandAnimation.defaults();String before=json(settings);
        for(String pattern:new String[]{"","Grasp","unknown"})assertThrows(Exception.class,()->poses(pattern,96,24,settings));
        for(int[] clock:new int[][]{{95,24},{241,30},{240,24},{96,25},{0,24}})assertThrows(Exception.class,()->poses("grasp",clock[0],clock[1],settings));
        assertThrows(Exception.class,()->poses("grasp",96,24,new JSONObject().put("unexpected",1)));assertEquals(before,json(settings));
        JSONArray changed=poses("grasp",96,24,settings);changed.getJSONObject(0).put("indexCurl",.99);assertEquals(.3,poses("grasp",96,24,settings).getJSONObject(0).getDouble("indexCurl"),0);
    }
    @Test public void capabilitiesShipFourMinimalExecutableFreshTemplates()throws Exception {
        JSONArray examples=SketchCapabilities.handAnimation().getJSONArray("studyExamples");assertEquals(4,examples.length());
        for(int i=0;i<4;i++){
            JSONObject e=examples.getJSONObject(i);assertEquals(PATTERNS[i],e.getString("id"));assertEquals(6,e.length());assertEquals(1280,e.getInt("width"));assertEquals(720,e.getInt("height"));assertEquals(96,e.getInt("frameCount"));assertEquals(24,e.getInt("fps"));
            JSONObject op=e.getJSONObject("operation"),settings=new JSONObject().put("view","front").put("handedness","right").put("size",.95).put("renderStyle","cel");JSONObject expected=new JSONObject().put("type","add_hand_animation").put("layerId","layer_1").put("handId","hand").put("settings",settings);if(i>0){Method original=NativeHumanCapabilityMatrixTest.class.getDeclaredMethod("handPoses",String.class);original.setAccessible(true);expected.put("poses",(JSONArray)original.invoke(null,PATTERNS[i]));}assertEquals(json(expected),json(op));
            JSONObject doc=SketchDocument.create(new JSONObject().put("sketchId","study_"+i).put("width",1280).put("height",720).put("frameCount",96).put("fps",24));SketchHandAnimation.apply(doc,SketchDocument.copy(op));SketchDocument.validate(doc);assertEquals("hand",doc.getJSONArray("layers").getJSONObject(0).getJSONArray("objects").getJSONObject(0).getString("kind"));
        }
        examples.getJSONObject(0).getJSONObject("operation").getJSONObject("settings").put("view","back");examples.getJSONObject(1).getJSONObject("operation").getJSONArray("poses").getJSONObject(0).put("indexCurl",.99);
        assertEquals("front",examples.getJSONObject(1).getJSONObject("operation").getJSONObject("settings").getString("view"));
        JSONArray next=SketchCapabilities.handAnimation().getJSONArray("studyExamples");assertEquals("front",next.getJSONObject(0).getJSONObject("operation").getJSONObject("settings").getString("view"));assertEquals(.3,next.getJSONObject(1).getJSONObject("operation").getJSONArray("poses").getJSONObject(0).getDouble("indexCurl"),0);
    }
}
