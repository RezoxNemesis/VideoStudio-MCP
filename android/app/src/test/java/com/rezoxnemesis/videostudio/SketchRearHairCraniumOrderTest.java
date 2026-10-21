package com.rezoxnemesis.videostudio;
import android.graphics.*;
import org.json.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.*;
import java.io.*;
import java.security.*;
import static org.junit.Assert.*;
/** Real rear curtain/skull overlap, plus immutable pre-repair front/profile native outputs. */
@RunWith(RobolectricTestRunner.class) @Config(sdk=33,manifest=Config.NONE) @GraphicsMode(GraphicsMode.Mode.NATIVE)
public class SketchRearHairCraniumOrderTest {
 static JSONObject portrait(String view)throws Exception{JSONObject d=SketchDocument.create(new JSONObject().put("sketchId","parted_portrait").put("width",1280).put("height",1600).put("frameCount",1));SketchHumanFigure.draw(d,d.getJSONArray("layers").getJSONObject(0),"actor",SketchRearHairOwnerOrderTest.settings(view,"long").put("fringeStyle","center_part"),new JSONObject(),0);return d;}
 /** Only the app's actual rear-hair surface, staged using the public figure/head transport. */
 static JSONObject curtain(boolean portrait)throws Exception{
  int width=portrait?1280:512,height=portrait?1600:640;JSONObject d=SketchDocument.create(new JSONObject().put("sketchId","curtain_mask").put("width",width).put("height",height).put("frameCount",1).put("background","#00000000"));JSONObject s=portrait?SketchRearHairOwnerOrderTest.settings("back","long"):SketchCenterPartFringeTest.settings("back","center_part");SketchHumanFigure.Skeleton p=SketchHumanFigure.skeleton(s,new JSONObject());SketchAnatomyHead.rearHair(d.getJSONArray("layers").getJSONObject(0),"actor",s,p);double[] centre=p.camera.point(p.head);JSONArray objects=d.getJSONArray("layers").getJSONObject(0).getJSONArray("objects");for(int i=0;i<objects.length();i++){JSONObject o=objects.getJSONObject(i);JSONArray commands=new JSONArray(o.getJSONArray("commands").toString());o.put("commands",commands);for(int j=0;j<commands.length();j++){JSONArray c=commands.getJSONArray(j);for(int k=1;k<c.length();k+=2){double x=(c.getDouble(k)*1280-640)*16,y=(c.getDouble(k+1)*720-360)*16;c.put(k,portrait?.5+x*2/width:(256+(x-centre[0])*6.5)/width).put(k+1,portrait?.9+y*2/height:(232+(y-centre[1])*6.5)/height);}}o.put("width",o.getDouble("width")*720*(portrait?2:6.5)/Math.min(width,height));}SketchDocument.validate(d);return d;
 }
 static void assertActualNearerCurtain(boolean full)throws Exception{Bitmap actual=SketchCenterPartFringeTest.render(full?portrait("back"):SketchCenterPartFringeTest.head("back","center_part")),mask=SketchCenterPartFringeTest.render(curtain(full));double[] centre=full?SketchRearHairOwnerOrderTest.centre("back"):new double[]{256,232};double scale=full?2:6.5;int samples=0,pale=0;try{for(int dy=10;dy<=22;dy++)for(int dx=-9;dx<=9;dx++){int x=(int)Math.round(centre[0]+dx*scale),y=(int)Math.round(centre[1]+dy*scale);int m=mask.getPixel(x,y);if(Color.alpha(m)<250||Color.red(m)>125)continue;samples++;int p=actual.getPixel(x,y);if(Color.alpha(p)>220&&Color.red(p)>180&&Color.green(p)>180)pale++;}assertTrue("Actual opaque curtain must cover a meaningful lower rear-cranium region",samples>150);assertEquals("Nearer rear hair must cover the owner's pale skull oval: "+(full?"public portrait":"head"),0,pale);}finally{actual.recycle();mask.recycle();}}
 @Test public void rearHeadCannotPaintSkinThroughItsActualNearerHairSurface()throws Exception{assertActualNearerCurtain(false);}
 @Test public void publicBackPortraitCannotPaintSkinThroughItsActualNearerHairSurface()throws Exception{assertActualNearerCurtain(true);}
 static Bitmap portraitPixels(String view)throws Exception{try(SketchFrameRenderer r=new SketchFrameRenderer(RuntimeEnvironment.getApplication(),portrait(view))){return r.render(0);}}
 static String pngHash(Bitmap b)throws Exception{try{ByteArrayOutputStream out=new ByteArrayOutputStream();assertTrue(b.compress(Bitmap.CompressFormat.PNG,100,out));byte[] hash=MessageDigest.getInstance("SHA-256").digest(out.toByteArray());StringBuilder s=new StringBuilder();for(byte q:hash)s.append(String.format(java.util.Locale.ROOT,"%02x",q&255));return s.toString();}finally{b.recycle();}}
 @Test public void backRepairMustPreserveAllFrozenFrontAndProfileNativePixels()throws Exception{
  String[] views={"front","left","right"},heads={"e74c47b5040daefeb78acfcf478666c4ad27e930f94bfcc0230a60f9eea69097","8321cdfc3becb730a20868751a44d16487154ba6322aecacfea0caac82eba8b2","6103d8d8b70c2edd3646b0e39fabef6df252e9f71f35a27aff1bbe8ef57c4f91"},portraits={"9725607f983c27e9baaf2d14ffb2aaef24fcf494b75e3f515b973ef423c472f0","f8c032c5c29100d072a0c7fd4608db23dcd9440ebee63d30fa4426b59ea25492","36d7d03b3aff9e84732700f45e716e5599fe41c9dbdec817b07d7a6667e6e1b9"};for(int i=0;i<views.length;i++){assertEquals("Original native head PNG "+views[i],heads[i],pngHash(SketchCenterPartFringeTest.render(SketchCenterPartFringeTest.head(views[i],"center_part"))));assertEquals("Original public portrait PNG "+views[i],portraits[i],pngHash(portraitPixels(views[i])));}
 }
}
