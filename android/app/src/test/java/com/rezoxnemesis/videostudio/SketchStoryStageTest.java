package com.rezoxnemesis.videostudio;

import android.graphics.Bitmap;
import android.graphics.RectF;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class) @Config(sdk=33,manifest=Config.NONE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public final class SketchStoryStageTest {
    private JSONObject document(int width,int height,int fps,int frames)throws Exception{return SketchDocument.create(new JSONObject().put("sketchId","stage_test").put("width",width).put("height",height).put("fps",fps).put("frameCount",frames));}
    private JSONObject layer(JSONObject doc)throws Exception{return doc.getJSONArray("layers").getJSONObject(0);}
    private JSONObject operation(String location,String time,String weather)throws Exception{return new JSONObject().put("type","add_story_stage").put("layerId","layer_1").put("sceneId","stage").put("settings",new JSONObject().put("location",location).put("time",time).put("weather",weather).put("intensity",.7));}
    private JSONObject object(JSONObject doc,String id)throws Exception{return SketchDocument.object(doc,id);}
    private RectF bounds(JSONObject object,int width,int height)throws Exception{RectF out=new RectF();SketchFrameRenderer.path(object.getJSONArray("commands"),width,height).computeBounds(out,true);return out;}

    @Test public void fourLocationsAndThreeTimesProduceDifferentActualNativeEditableScenes()throws Exception {
        Set<Integer> images=new HashSet<>();
        for(String location:new String[]{"forest_path","bridge","shelter","village"})for(String time:new String[]{"dusk","night","dawn"}){
            JSONObject doc=document(1280,720,30,180);SketchStoryStage.apply(doc,operation(location,time,"clear"));SketchDocument.validate(doc);
            assertEquals(180,doc.getInt("frameCount"));assertEquals(30,doc.getInt("fps"));assertEquals(1280,doc.getInt("width"));
            JSONArray objects=layer(doc).getJSONArray("objects");assertTrue("Scenery needs original depth and construction",objects.length()>30);boolean gradient=false;
            for(int i=0;i<objects.length();i++){JSONObject o=objects.getJSONObject(i);assertEquals("path",o.getString("kind"));assertEquals(180,o.getInt("exposure"));gradient|=o.has("gradient");}
            assertTrue(gradient);assertNotNull(object(doc,"stage_sky_0"));
            try(SketchFrameRenderer renderer=new SketchFrameRenderer(RuntimeEnvironment.getApplication(),doc)){Bitmap b=renderer.render(0);try{int[] pixels=new int[b.getWidth()*b.getHeight()];b.getPixels(pixels,0,b.getWidth(),0,0,b.getWidth(),b.getHeight());assertTrue("Distinct scene/time must change native pixels",images.add(Arrays.hashCode(pixels)));}finally{b.recycle();}}
        }
    }

    @Test public void rainHasTwelveSparseLinearStreaksWithExplicitWrapsAndExactPhysicalClock()throws Exception {
        JSONObject thirty=document(1280,720,30,240),sixty=document(1280,720,60,480);
        SketchStoryStage.apply(thirty,operation("bridge","night","rain"));SketchStoryStage.apply(sixty,operation("bridge","night","rain"));
        for(int i=0;i<12;i++){
            JSONObject a=object(thirty,"stage_rain_"+i+"_0"),b=object(sixty,"stage_rain_"+i+"_0");
            assertTrue(a.getJSONArray("keys").length()<24);assertTrue(b.getJSONArray("keys").length()<24);
            for(int frame=0;frame<240;frame++){
                JSONObject x=SketchDocument.transformAt(a,frame),y=SketchDocument.transformAt(b,frame*2);
                assertEquals(x.getDouble("x"),y.getDouble("x"),1e-9);assertEquals(x.getDouble("y"),y.getDouble("y"),1e-9);
            }
            JSONArray keys=a.getJSONArray("keys");boolean wrap=false;
            for(int k=0;k<keys.length();k++){JSONObject key=keys.getJSONObject(k);assertTrue(Arrays.asList("linear","hold").contains(key.getString("interpolation")));if(k>0){JSONObject prev=keys.getJSONObject(k-1);if(key.getJSONObject("transform").getDouble("y")<prev.getJSONObject("transform").getDouble("y")){assertEquals(prev.getInt("frame")+1,key.getInt("frame"));assertEquals("hold",prev.getString("interpolation"));wrap=true;}}}
            assertTrue("Each streak must explicitly respawn",wrap);
        }
        JSONObject duplicate=document(1280,720,30,240);SketchStoryStage.apply(duplicate,operation("bridge","night","rain"));assertEquals(SketchDocument.canonical(thirty),SketchDocument.canonical(duplicate));
    }

    @Test public void windAndRainAnimateRealNativeOutputWithoutChangingFrameCount()throws Exception {
        for(String weather:new String[]{"wind","rain"}){
            JSONObject doc=document(1280,720,30,180);SketchStoryStage.apply(doc,operation("forest_path","dawn",weather));
            try(SketchFrameRenderer renderer=new SketchFrameRenderer(RuntimeEnvironment.getApplication(),doc)){Bitmap a=renderer.render(0),b=renderer.render(17);try{assertFalse(a.sameAs(b));}finally{a.recycle();b.recycle();}}
        }
    }

    @Test public void geometryAndGradientsFitPortraitAndSquareUniformlyWithoutSharedArrayDoubleTransforms()throws Exception {
        RectF reference=null;
        for(int[] canvas:new int[][]{{1280,720},{720,1280},{900,900}}){
            JSONObject doc=document(canvas[0],canvas[1],24,96);SketchStoryStage.apply(doc,operation("shelter","dusk","clear"));SketchDocument.validate(doc);
            RectF light=bounds(object(doc,"stage_light_disc_0"),canvas[0],canvas[1]);if(reference==null)reference=light;else assertEquals(reference.width()/reference.height(),light.width()/light.height(),.001);
            JSONArray objects=layer(doc).getJSONArray("objects");for(int i=0;i<objects.length();i++){RectF b=bounds(objects.getJSONObject(i),canvas[0],canvas[1]);assertTrue(b.toString(),b.left>=-.01&&b.top>=-.01&&b.right<=canvas[0]+.01&&b.bottom<=canvas[1]+.01);}
            JSONObject fill=object(doc,"stage_shelter_roof_0"),outline=object(doc,"stage_shelter_roof_1");assertEquals(fill.getJSONArray("commands").toString(),outline.getJSONArray("commands").toString());
            JSONObject g=object(doc,"stage_sky_0").getJSONObject("gradient");assertTrue(g.getDouble("y1")>g.getDouble("y0"));
        }
    }

    @Test public void malformedLockedDuplicateAndGeometryBudgetsRejectWithoutChangingExistingArt()throws Exception {
        JSONObject doc=document(1280,720,30,180);
        for(JSONObject settings:new JSONObject[]{new JSONObject().put("location","castle"),new JSONObject().put("time","noon"),new JSONObject().put("weather","snow"),new JSONObject().put("intensity",1.1),new JSONObject().put("intensity",true),new JSONObject().put("unknown",1)}){
            String before=SketchDocument.canonical(doc);assertThrows(IllegalArgumentException.class,()->SketchStoryStage.apply(doc,operation("bridge","dusk","clear").put("settings",settings)));assertEquals(before,SketchDocument.canonical(doc));
        }
        layer(doc).put("locked",true);String locked=SketchDocument.canonical(doc);assertThrows(IllegalArgumentException.class,()->SketchStoryStage.apply(doc,operation("bridge","dusk","clear")));assertEquals(locked,SketchDocument.canonical(doc));layer(doc).put("locked",false);
        SketchStoryStage.apply(doc,operation("bridge","dusk","clear"));String first=SketchDocument.canonical(doc);assertThrows(IllegalArgumentException.class,()->SketchStoryStage.apply(doc,operation("bridge","dusk","clear")));assertEquals(first,SketchDocument.canonical(doc));
        JSONObject full=document(1280,720,30,180);JSONArray objects=layer(full).getJSONArray("objects");for(int i=0;i<4090;i++)objects.put(SketchEditOps.completeObject(new JSONObject().put("id","old_"+i).put("kind","line").put("points",new JSONArray().put(new JSONArray().put(.1).put(.1).put(1)).put(new JSONArray().put(.2).put(.2).put(1)))));
        String before=SketchDocument.canonical(full);assertThrows(IllegalArgumentException.class,()->SketchStoryStage.apply(full,operation("village","dawn","rain")));assertEquals(before,SketchDocument.canonical(full));
    }

    @Test public void foregroundSplitKeepsRailRootsAndWeatherInFrontAndPreservesOtherLayers()throws Exception {
        JSONObject doc=document(1280,720,30,180),actors=SketchDocument.copy(layer(doc)).put("id","actors"),front=SketchDocument.copy(layer(doc)).put("id","foreground");
        doc.getJSONArray("layers").put(actors).put(front);String unchanged=SketchDocument.canonical(actors);
        SketchStoryStage.apply(doc,operation("bridge","dusk","rain").put("foregroundLayerId","foreground"));
        assertEquals(unchanged,SketchDocument.canonical(SketchDocument.layer(doc,"actors")));
        JSONArray back=layer(doc).getJSONArray("objects"),near=SketchDocument.layer(doc,"foreground").getJSONArray("objects");assertTrue(back.length()>30);assertTrue(near.length()>=15);
        for(int i=0;i<back.length();i++){String name=back.getJSONObject(i).getString("id");assertFalse(name.contains("_bridge_near_rail_")||name.contains("_foreground_")||name.contains("_rain_"));}
        for(int i=0;i<near.length();i++){String name=near.getJSONObject(i).getString("id");assertTrue(name,name.contains("_bridge_near_rail_")||name.contains("_foreground_")||name.contains("_rain_"));}
        assertEquals("foreground",SketchDocument.owner(doc,"stage_bridge_near_rail_0").getString("id"));assertEquals("foreground",SketchDocument.owner(doc,"stage_rain_0_0").getString("id"));SketchDocument.validate(doc);
    }

    @Test public void splitLayerFailureIsAtomicIncludingLocksMissingTargetsAndOffCanvasForeground()throws Exception {
        JSONObject doc=document(1280,720,30,180),front=SketchDocument.copy(layer(doc)).put("id","foreground");doc.getJSONArray("layers").put(front);
        JSONObject op=operation("bridge","night","rain").put("foregroundLayerId","foreground");
        front.put("locked",true);String before=SketchDocument.canonical(doc);assertThrows(IllegalArgumentException.class,()->SketchStoryStage.apply(doc,op));assertEquals(before,SketchDocument.canonical(doc));front.put("locked",false);
        before=SketchDocument.canonical(doc);assertThrows(IllegalArgumentException.class,()->SketchStoryStage.apply(doc,new JSONObject(op.toString()).put("foregroundLayerId","missing")));assertEquals(before,SketchDocument.canonical(doc));
        assertThrows(IllegalArgumentException.class,()->SketchStoryStage.apply(doc,new JSONObject(op.toString()).put("foregroundLayerId","layer_1")));assertEquals(before,SketchDocument.canonical(doc));
        front.getJSONObject("transform").put("x",1);before=SketchDocument.canonical(doc);assertThrows(IllegalArgumentException.class,()->SketchStoryStage.apply(doc,op));assertEquals(before,SketchDocument.canonical(doc));
    }

    @Test public void walkwayMatchesActualDrawnBridgeDeckAndProvidesOtherSceneFloors()throws Exception {
        JSONObject doc=document(1280,720,30,180);SketchStoryStage.apply(doc,operation("bridge","dawn","clear"));
        for(int i:new int[]{2,9,16}){
            JSONArray commands=object(doc,"stage_bridge_plank_"+i+"_0").getJSONArray("commands");double x=0,y=0;
            for(int c=0;c<4;c++){x+=commands.getJSONArray(c).getDouble(1)/4;y+=commands.getJSONArray(c).getDouble(2)*720/4;}
            assertEquals("Floor must meet actual editable deck, not an unrelated story constant",y,SketchStoryStage.walkwayY("bridge",x),1);
        }
        for(String location:new String[]{"forest_path","shelter","village"})assertTrue(SketchStoryStage.walkwayY(location,.5)>=615&&SketchStoryStage.walkwayY(location,.5)<=630);
        assertThrows(IllegalArgumentException.class,()->SketchStoryStage.walkwayY("bridge",Double.NaN));assertThrows(IllegalArgumentException.class,()->SketchStoryStage.walkwayY("unknown",.5));
    }
}
