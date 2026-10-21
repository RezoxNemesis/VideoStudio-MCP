package com.rezoxnemesis.videostudio;

import android.graphics.Bitmap;
import android.graphics.Matrix;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import static org.junit.Assert.*;

/** Product composition checks independent of the new garment geometry helper. */
@RunWith(RobolectricTestRunner.class) @Config(sdk=33,manifest=Config.NONE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public final class SketchHumanClothingContactTest {
    private static final String[] VIEWS={"front","left","right","back","high","low"};
    private static final String[] BUILDS={"slender","broad"};
    private static JSONObject document(int frames)throws Exception {
        return SketchDocument.create(new JSONObject().put("sketchId","clothing_contact").put("width",1280).put("height",720).put("fps",24).put("frameCount",frames));
    }
    private static JSONObject layer(JSONObject doc)throws Exception{return doc.getJSONArray("layers").getJSONObject(0);}
    private static JSONObject actor(JSONObject doc,String view,String build)throws Exception {
        JSONArray poses=new JSONArray().put(new JSONObject().put("frame",0).put("nearHandY",-180).put("farHandY",-185).put("handOpen",0).put("nearWrist",-12).put("farWrist",8))
                .put(new JSONObject().put("frame",95).put("bodyLean",8).put("nearHandY",-210).put("farHandY",-205).put("handOpen",0).put("nearWrist",15).put("farWrist",-10));
        SketchHumanAnimation.apply(doc,new JSONObject().put("type","add_human_animation").put("layerId","layer_1").put("actorId","adult")
                .put("settings",new JSONObject().put("view",view).put("build",build).put("clothingStyle","tailored").put("size",.8)).put("poses",poses));
        return SketchDocument.object(doc,"adult_rig");
    }
    @Test public void handOnlyPassRetainsExactlyTheLegacyHandInkAcrossViewsBuildsAndBothAttachments()throws Exception {
        for(String view:VIEWS)for(String build:BUILDS){
            JSONObject doc=document(96),tailored=actor(doc,view,build),legacy=SketchDocument.copy(tailored);
            legacy.getJSONObject("human").getJSONObject("settings").remove("clothingStyle");
            for(String side:new String[]{"near","far"})for(int frame:new int[]{0,47,95}){
                tailored.getJSONObject("human").put("parts",side+"_hand");legacy.getJSONObject("human").put("parts",side+"_hand");
                JSONArray actual=layer(SketchHumanAnimation.frameDocument(tailored,1280,720,frame)).getJSONArray("objects");
                JSONArray expected=layer(SketchHumanAnimation.frameDocument(legacy,1280,720,frame)).getJSONArray("objects");
                assertEquals(view+" "+build+" "+side+" hand ink must not change with clothing",SketchDocument.canonical(expected),SketchDocument.canonical(actual));
                assertTrue(actual.length()>4);
                for(int i=0;i<actual.length();i++){
                    String id=actual.getJSONObject(i).getString("id");
                    assertTrue("No sleeve/cuff/coat may leak into held-prop finger overlay: "+id,id.contains("_"+side+"_hand_")||id.contains("_"+side+"_finger_")||id.contains("_"+side+"_thumb_")||id.contains("_"+side+"_palm_crease_"));
                }
            }
        }
    }
    @Test public void transformedTailoredActorsCoverTheActualPropPinchAndKeepAnExposedHandleArc()throws Exception {
        for(String view:VIEWS)for(String attachment:new String[]{"near","far"})for(String kind:new String[]{"lantern","parcel","staff"}){
            JSONObject doc=document(96),adult=actor(doc,view,"broad");
            adult.getJSONObject("transform").put("rotation",4).put("scaleX",1.03).put("scaleY",.97);
            layer(doc).getJSONObject("transform").put("rotation",-3).put("scaleX",.94).put("scaleY",.94);
            JSONObject bare=SketchDocument.copy(doc),isolated=SketchDocument.object(bare,"adult_rig");
            // The selected hand is intentionally the prop's foreground pass.
            // Use its actual ink as the oracle even when an unheld far hand
            // would have been hidden behind the coat in the ordinary body pass.
            isolated.getJSONObject("human").put("parts",attachment+"_hand");
            layer(bare).put("objects",new JSONArray().put(isolated));
            SketchActorProp.apply(doc,new JSONObject().put("type","add_actor_prop").put("layerId","layer_1").put("propId","held").put("actorId","adult")
                    .put("settings",new JSONObject().put("kind",kind).put("attachment",attachment).put("scale",.8).put("x",.5).put("y",.75)));
            JSONObject handle=SketchDocument.object(doc,"held_handle_0");handle.put("color","#FFFF2020").put("width",.006);
            JSONObject behind=SketchDocument.copy(doc);JSONArray kept=new JSONArray(),objects=layer(behind).getJSONArray("objects");
            for(int i=0;i<objects.length();i++)if(!objects.getJSONObject(i).getString("id").equals("held_grip"))kept.put(objects.getJSONObject(i));
            layer(behind).put("objects",kept);
            try(SketchFrameRenderer before=new SketchFrameRenderer(RuntimeEnvironment.getApplication(),behind);
                SketchFrameRenderer after=new SketchFrameRenderer(RuntimeEnvironment.getApplication(),doc);
                SketchFrameRenderer skin=new SketchFrameRenderer(RuntimeEnvironment.getApplication(),bare)){
                for(int frame:new int[]{0,95}){
                    Bitmap a=before.render(frame),b=after.render(frame),c=skin.render(frame);
                    try{
                        JSONArray move=handle.getJSONArray("commands").getJSONArray(0);float[] pinch={(float)(move.getDouble(1)*1280),(float)(move.getDouble(2)*720)};
                        Matrix transform=SketchFrameRenderer.matrix(SketchDocument.transformAt(handle,frame),1280,720);transform.mapPoints(pinch);
                        SketchFrameRenderer.matrix(SketchDocument.transformAt(layer(doc),frame),1280,720).mapPoints(pinch);
                        int x=Math.round(pinch[0]),y=Math.round(pinch[1]),covered=0,exposed=0;
                        for(int dy=-34;dy<=34;dy++)for(int dx=-34;dx<=34;dx++){
                            int px=x+dx,py=y+dy;if(px<0||py<0||px>=1280||py>=720||a.getPixel(px,py)!=0xFFFF2020)continue;
                            if(Math.abs(dx)<=6&&Math.abs(dy)<=6&&b.getPixel(px,py)==c.getPixel(px,py)&&b.getPixel(px,py)!=0xFFFF2020)covered++;
                            if(b.getPixel(px,py)==0xFFFF2020)exposed++;
                        }
                        String label=view+" / "+attachment+" / "+kind+" / "+frame;
                        assertTrue(label+" actual hand must cover the transformed handle pinch",covered>0);
                        assertTrue(label+" a real handle arc must remain visible",exposed>0);
                    }finally{a.recycle();b.recycle();c.recycle();}
                }
            }
        }
    }
    @Test public void tailoredKneeInkMeetsTheIndependentProjectedGroundContactForBothBuildsAndSixViews()throws Exception {
        for(String view:VIEWS)for(String build:BUILDS){
            JSONObject settings=SketchHumanFigure.settings(new JSONObject().put("view",view).put("build",build).put("clothingStyle","tailored").put("size",1.1).put("floorY",.76));
            JSONArray poses=SketchHumanPresets.createForView("kneel",96,24,new JSONObject(),view);
            for(int frame:new int[]{48,95}){
                JSONObject pose=SketchHumanAnimation.poseAt(poses,frame),doc=document(1);SketchHumanFigure.Skeleton skeleton=SketchHumanFigure.skeleton(settings,pose);
                assertEquals("Existing physical support radius cannot be changed to fit clothing",-9,skeleton.rightKnee[1],1e-8);
                SketchHumanFigure.draw(doc,layer(doc),"kneeling",settings,pose,frame/24d);
                JSONArray all=layer(doc).getJSONArray("objects"),selected=new JSONArray();
                for(int i=0;i<all.length();i++){
                    JSONObject ink=all.getJSONObject(i);String name=ink.getString("id");
                    if(name.startsWith("kneeling_right_")&&(name.contains("trouser")||name.contains("thigh")||name.contains("shin")||name.contains("knee")))selected.put(ink);
                }
                assertTrue("Inspect actual supporting trouser ink",selected.length()>0);layer(doc).put("objects",selected);
                double[] ground=SketchHumanFigure.project(settings,new double[]{skeleton.rightKnee[0],0,skeleton.rightKnee[2]});
                int cx=(int)Math.round(1280*settings.getDouble("x")+ground[0]*settings.getDouble("size")),cy=(int)Math.round(720*settings.getDouble("floorY")+ground[1]*settings.getDouble("size"));
                try(SketchFrameRenderer renderer=new SketchFrameRenderer(RuntimeEnvironment.getApplication(),doc)){
                    Bitmap image=renderer.render(0);try{
                        boolean contact=false;
                        for(int dy=-3;dy<=3;dy++)for(int dx=-3;dx<=3;dx++)if(dx*dx+dy*dy<=9&&image.getPixel(cx+dx,cy+dy)!=0xFFFFFFFF)contact=true;
                        assertTrue(view+" "+build+" frame"+frame+" native knee must reach its ground footprint within3px",contact);
                    }finally{image.recycle();}
                }
            }
        }
    }
}
