package com.rezoxnemesis.videostudio;

import android.graphics.*;
import org.json.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.*;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class) @Config(sdk=33,manifest=Config.NONE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class SketchHandAnimationTest {
    static JSONObject doc(int fps)throws Exception {return SketchDocument.create(new JSONObject().put("sketchId","hand_test").put("width",1280).put("height",720).put("frameCount",fps*4).put("fps",fps).put("background","#FFF1E9DE"));}
    static JSONObject op(String view)throws Exception {return new JSONObject().put("type","add_hand_animation").put("layerId","layer_1").put("handId","hand").put("settings",new JSONObject().put("view",view));}
    @Test public void nativeDispatchBuildsEditableChangingHandWithoutImportedPictures()throws Exception {
        JSONObject d=doc(30);SketchEditOps.apply(d,op("front"));SketchDocument.validate(d);
        JSONArray objects=d.getJSONArray("layers").getJSONObject(0).getJSONArray("objects");assertEquals("Retained rig avoids dense-cel budget overflow",1,objects.length());
        for(int n=0;n<objects.length();n++)assertEquals("hand",objects.getJSONObject(n).getString("kind"));
        try(SketchFrameRenderer r=new SketchFrameRenderer(RuntimeEnvironment.getApplication(),d)){
            Bitmap a=r.render(0),b=r.render(60);try{assertFalse("The actual native artwork must move",a.sameAs(b));}finally{a.recycle();b.recycle();}
        }
        assertEquals(120,d.getInt("frameCount"));assertEquals(30,d.getInt("fps"));
    }
    @Test public void openAndCurledAnatomyHaveActualNativeSixViewScreenshots()throws Exception {
        String root=System.getenv("VIDEOSTUDIO_HAND_STATIC");if(root==null)return;
        for(String view:new String[]{"front","left","right","back","high","low"})for(double curl:new double[]{0,.9}){
            JSONObject d=doc(30),o=op(view);JSONObject p=new JSONObject().put("frame",0).put("spread",.5).put("thumbOpposition",curl*.7);
            for(String ch:new String[]{"indexCurl","middleCurl","ringCurl","littleCurl","thumbCurl"})p.put(ch,curl);o.put("poses",new JSONArray().put(p));SketchEditOps.apply(d,o);
            try(SketchFrameRenderer r=new SketchFrameRenderer(RuntimeEnvironment.getApplication(),d)){Bitmap b=r.render(0);try{SketchActionFilmTest.png(new java.io.File(root,view+"_"+(curl==0?"open":"curl")+".png"),b);}finally{b.recycle();}}
            SketchSpeechStudyTest.text(new java.io.File(root,view+"_"+(curl==0?"open":"curl")+".json").toPath(),d.toString());
        }
    }
    @Test public void handInkUsesDelicateContoursAndContinuousNativeSurfaceShading()throws Exception {
        JSONObject d=doc(30);JSONObject op=op("front").put("poses",new JSONArray().put(new JSONObject().put("frame",0)));
        SketchEditOps.apply(d,op);JSONObject rig=d.getJSONArray("layers").getJSONObject(0).getJSONArray("objects").getJSONObject(0);
        assertTrue("Anatomy must not have the default heavy brush outline",rig.getDouble("width")<=.0015);
        JSONArray cels=SketchHandAnimation.vectorFrame(rig,1280,720,0);int gradients=0;for(int i=0;i<cels.length();i++)if(cels.getJSONObject(i).has("gradient"))gradients++;
        assertTrue("Smooth surface normals must reach actual native gradients",gradients>10);
    }
    @Test public void openHandSkinIsOneConnectedSilhouetteInsteadOfDetachedFingerRoots()throws Exception {
        JSONObject d=doc(30),op=op("front");JSONObject pose=new JSONObject().put("frame",0).put("spread",.5).put("thumbOpposition",0);
        for(String ch:new String[]{"indexCurl","middleCurl","ringCurl","littleCurl","thumbCurl"})pose.put(ch,0);op.put("poses",new JSONArray().put(pose));SketchEditOps.apply(d,op);
        try(SketchFrameRenderer r=new SketchFrameRenderer(RuntimeEnvironment.getApplication(),d)){Bitmap b=r.render(0);try{
            int w=b.getWidth(),h=b.getHeight(),bg=b.getPixel(0,0),components=0;int[] pixels=new int[w*h],queue=new int[w*h];boolean[] seen=new boolean[w*h];b.getPixels(pixels,0,w,0,0,w,h);
            for(int i=0;i<pixels.length;i++){int c=pixels[i];seen[i]=Math.abs(Color.red(c)-Color.red(bg))+Math.abs(Color.green(c)-Color.green(bg))+Math.abs(Color.blue(c)-Color.blue(bg))<40;}
            for(int i=0;i<pixels.length;i++)if(!seen[i]){int head=0,tail=1;queue[0]=i;seen[i]=true;while(head<tail){int p=queue[head++];for(int q:new int[]{p%w>0?p-1:-1,p%w<w-1?p+1:-1,p-w,p+w})if(q>=0&&q<pixels.length&&!seen[q]){seen[q]=true;queue[tail++]=q;}}if(tail>8)components++;}
            assertEquals("Visible finger roots must actually join the palm",1,components);
        }finally{b.recycle();}}
    }
}
