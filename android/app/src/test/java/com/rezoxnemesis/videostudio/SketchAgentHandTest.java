package com.rezoxnemesis.videostudio;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Path;
import android.graphics.PathMeasure;
import android.graphics.drawable.Drawable;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class) @Config(sdk=33,manifest=Config.NONE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class SketchAgentHandTest {
    private JSONObject document()throws Exception {
        return SketchDocument.create(new JSONObject().put("sketchId",UUID.randomUUID().toString())
                .put("width",512).put("height",768).put("frameCount",4));
    }
    private JSONArray points(double... numbers)throws Exception {
        JSONArray out=new JSONArray();for(int i=0;i<numbers.length;i+=3)out.put(new JSONArray().put(numbers[i]).put(numbers[i+1]).put(numbers[i+2]));return out;
    }
    private JSONObject object(JSONObject document,String kind)throws Exception {
        JSONObject object=SketchEditOps.completeObject(new JSONObject().put("id","agent_mark").put("kind",kind)
                .put("frame",0).put("exposure",4).put("points",points(.1,.2,.2,.7,.2,.8,.7,.8,1)));
        document.getJSONArray("layers").getJSONObject(0).getJSONArray("objects").put(object);return object;
    }
    private SketchAgentHand.Track prepare(JSONObject doc,JSONObject operation,int frame)throws Exception {
        ExecutorService worker=Executors.newSingleThreadExecutor();
        try{return worker.submit(()->SketchAgentHand.prepare(doc,operation,frame)).get(5,TimeUnit.SECONDS);}
        finally{worker.shutdownNow();}
    }
    @Test public void handTracksTheActuallyRevealedPressurePrefixUnderKeyedTransforms()throws Exception {
        JSONObject doc=document(),object=object(doc,"stroke"),layer=doc.getJSONArray("layers").getJSONObject(0);
        object.getJSONObject("transform").put("rotation",90).put("anchorX",0).put("anchorY",0);
        layer.getJSONArray("keys").put(new JSONObject().put("frame",0).put("interpolation","linear").put("transform",SketchDocument.transform().put("x",.05)))
                .put(new JSONObject().put("frame",2).put("interpolation","linear").put("transform",SketchDocument.transform().put("x",.15)));
        SketchAgentHand.Track track=prepare(doc,new JSONObject().put("type","add_object").put("object",object).put("layerId",layer.getString("id")),1);
        SketchAgentHand.State halfway=track.at(.5);assertTrue(halfway.visible);assertTrue(halfway.drawing);assertEquals("Ink",halfway.toolLabel);
        assertEquals(-.2*768/512d+.1,halfway.x,1e-6);assertEquals(.7*512/768d,halfway.y,1e-6);assertEquals(.8,halfway.pressure,1e-6);
        assertEquals(0,track.at(0).pressure,0);assertEquals(1,track.at(1).pressure,0);
        assertEquals("agent_mark",halfway.objectId);
    }
    @Test public void strokeAppendStartsAtThePreviousEndpointRatherThanRepeatingOldInk()throws Exception {
        JSONObject doc=document(),object=object(doc,"stroke");
        SketchAgentHand.Track track=prepare(doc,new JSONObject().put("type","append_points").put("objectId","agent_mark")
                .put("points",points(.7,.8,1)),0);
        assertEquals(.7,track.at(0).x,1e-6);assertEquals(.2,track.at(0).y,1e-6);
        assertEquals(.7,track.at(.5).x,1e-6);assertEquals(.5,track.at(.5).y,1e-6);
    }
    @Test public void curvedAndMultiContourPathsFollowNativePathMeasureWithoutInventedTravel()throws Exception {
        JSONObject doc=document();JSONArray commands=new JSONArray().put(new JSONArray().put("M").put(.1).put(.2))
                .put(new JSONArray().put("Q").put(.5).put(.9).put(.8).put(.2))
                .put(new JSONArray().put("M").put(.1).put(.9)).put(new JSONArray().put("L").put(.3).put(.9));
        JSONObject object=SketchEditOps.completeObject(new JSONObject().put("id","curve").put("kind","path").put("commands",commands));
        doc.getJSONArray("layers").getJSONObject(0).getJSONArray("objects").put(object);
        SketchAgentHand.Track track=prepare(doc,new JSONObject().put("type","add_object").put("object",object).put("layerId","layer_1"),0);
        Path path=SketchFrameRenderer.path(commands,512,768);PathMeasure measure=new PathMeasure(path,false);double total=measure.getLength();measure.nextContour();total+=measure.getLength();measure.setPath(path,false);
        float[] nativePosition=new float[2];measure.getPosTan((float)(total*.4),nativePosition,null);
        SketchAgentHand.State state=track.at(.4);assertTrue(state.visible);assertEquals(nativePosition[0]/512d,state.x,3e-5);assertEquals(nativePosition[1]/768d,state.y,3e-5);
        assertEquals(.3,track.at(1).x,1e-6);assertEquals(.9,track.at(1).y,1e-6);
    }
    @Test public void nativePaintHandUsesTheSameDabIndexAsTheAppliedRasterPrefix()throws Exception {
        JSONObject doc=document(),cel=SketchEditOps.completeObject(new JSONObject().put("id","paint_cel").put("kind","paint")
                .put("bounds",new JSONObject().put("x",0).put("y",0).put("width",1).put("height",1)));
        doc.getJSONArray("layers").getJSONObject(0).getJSONArray("objects").put(cel);
        JSONObject request=new JSONObject().put("type","paint_stroke").put("objectId","paint_cel").put("strokeId","paint_test")
                .put("points",points(.1,.2,.2,.85,.7,.9))
                .put("brush",new JSONObject().put("mode","paint").put("color","#FFCC5511").put("sizePx",18).put("spacing",1));
        SketchPaintBrush.Plan plan=SketchPaintBrush.plan(request,512,768);
        JSONObject event=new JSONObject().put("type","paint_stroke").put("objectId","paint_cel")
                .put("stroke",new JSONObject().put("request",request));
        SketchAgentHand.Track track=prepare(doc,event,0);
        for(double fraction:new double[]{0,.19,.51,.89,1}){
            JSONObject expected=plan.cursorAt(fraction);SketchAgentHand.State actual=track.at(fraction);
            assertTrue(actual.visible);assertEquals(expected.getDouble("x"),actual.x,1e-7);assertEquals(expected.getDouble("y"),actual.y,1e-7);assertEquals(expected.getDouble("pressure"),actual.pressure,1e-7);
        }assertEquals("Brush",track.at(.5).toolLabel);assertEquals(0xFFCC5511,track.at(.5).color);assertEquals(18,track.at(.5).sizePx,0);
    }
    @Test public void toolActionsAreTruthfulSelectionsWithoutAPretendDrawingMotion()throws Exception {
        JSONObject doc=document();JSONObject image=SketchEditOps.completeObject(new JSONObject().put("id","guide").put("kind","image")
                .put("bounds",new JSONObject().put("x",.2).put("y",.3).put("width",.4).put("height",.5)));
        doc.getJSONArray("layers").getJSONObject(0).getJSONArray("objects").put(image);
        SketchAgentHand.Track track=prepare(doc,new JSONObject().put("type","set_reference_visibility").put("objectId","guide").put("visible",false),0);
        SketchAgentHand.State state=track.at(.6);assertTrue(state.visible);assertFalse(state.drawing);assertEquals("Reference",state.toolLabel);assertTrue(state.caption.contains("Hide reference"));
        assertEquals(.4,state.x,1e-6);assertEquals(.55,state.y,1e-6);assertEquals(state.x,track.at(.1).x,0);assertEquals(state.y,track.at(.9).y,0);
        assertFalse(prepare(doc,new JSONObject().put("type","add_object").put("object",image),0).at(.5).drawing);
    }
    @Test public void disablingBrushPressureStillShowsDrawingWhenThePhysicalPressureIsZero()throws Exception {
        JSONObject doc=document(),cel=SketchEditOps.completeObject(new JSONObject().put("id","paint_cel").put("kind","paint")
                .put("bounds",new JSONObject().put("x",0).put("y",0).put("width",1).put("height",1)));
        doc.getJSONArray("layers").getJSONObject(0).getJSONArray("objects").put(cel);
        JSONObject request=new JSONObject().put("type","paint_stroke").put("objectId","paint_cel").put("strokeId","zero_force")
                .put("points",points(.1,.2,0,.85,.7,0)).put("brush",new JSONObject().put("mode","paint")
                        .put("color","#FFCC5511").put("sizePx",18).put("pressure",false));
        SketchAgentHand.Track track=prepare(doc,new JSONObject().put("type","paint_stroke").put("objectId","paint_cel")
                .put("stroke",new JSONObject().put("request",request)),0);
        assertTrue("Disabled pressure uses a full brush footprint even at zero physical force",track.at(.5).drawing);
        assertEquals(0,track.at(.5).pressure,0);assertFalse(track.at(0).drawing);
    }
    @Test public void malformedOrNonVisualOperationsRemainQuietAndPreparationStaysOffMain()throws Exception {
        JSONObject doc=document();
        try{SketchAgentHand.prepare(doc,new JSONObject(),0);fail("Geometry scans must run on worker");}catch(IllegalStateException expected){assertTrue(expected.getMessage().contains("background"));}
        assertFalse(prepare(doc,new JSONObject(),0).at(.5).visible);
        assertFalse(prepare(doc,new JSONObject().put("type","delete_object").put("objectId","missing"),0).at(.5).visible);
        assertFalse(prepare(doc,new JSONObject().put("type","add_object").put("object",new JSONObject().put("id","empty").put("kind","stroke").put("points",new JSONArray())),0).at(.5).visible);
        SketchAgentHand.Track track=prepare(doc,new JSONObject().put("type","set_timing").put("fps",24),0);
        assertFalse(track.at(Double.NaN).visible);assertFalse(track.at(-1).visible);assertFalse(track.at(2).visible);
    }
    @Test public void handIsDetachedFromMutableOperationDataAndHasBoundedNativePixels()throws Exception {
        JSONObject doc=document(),object=object(doc,"line");JSONObject operation=new JSONObject().put("type","add_object").put("object",object);
        SketchAgentHand.Track track=prepare(doc,operation,0);object.getJSONArray("points").getJSONArray(0).put(0,.95);
        assertEquals(.1,track.at(0).x,1e-6);
        Bitmap bitmap=Bitmap.createBitmap(128,128,Bitmap.Config.ARGB_8888);
        try{SketchAgentHand.draw(new Canvas(bitmap),track.at(.5),64,64,28);int count=0;
            for(int y=0;y<128;y++)for(int x=0;x<128;x++)if((bitmap.getPixel(x,y)>>>24)>0){count++;assertTrue("Small hand cannot cover a large artwork area",x>=30&&x<=98&&y>=30&&y<=98);}
            assertTrue(count>20);bitmap.eraseColor(0);SketchAgentHand.draw(new Canvas(bitmap),SketchAgentHand.State.hidden(),64,64,28);
            assertEquals(0,bitmap.getPixel(64,64));
            Drawable control=SketchAgentHand.toolIndicator(RuntimeEnvironment.getApplication(),28);
            assertNotNull("Global tool labels have the same stationary hand glyph",control);
            control.setBounds(45,45,73,73);control.draw(new Canvas(bitmap));int glyph=0;
            for(int y=0;y<128;y++)for(int x=0;x<128;x++)if((bitmap.getPixel(x,y)>>>24)>0){glyph++;assertTrue("The control glyph stays inside its own bounds",x>=45&&x<73&&y>=45&&y<73);}
            assertTrue(glyph>10);
        }finally{bitmap.recycle();}
    }
}
