package com.rezoxnemesis.videostudio;
import android.graphics.*;
import org.json.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.*;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class) @Config(sdk=33,manifest=Config.NONE) @GraphicsMode(GraphicsMode.Mode.NATIVE)
public class SketchMultiplyAlphaTest {
 static JSONObject rect(String id,String color,double x,double width)throws Exception{return SketchEditOps.completeObject(new JSONObject().put("id",id).put("kind","rect").put("fill",true).put("color",color).put("bounds",new JSONObject().put("x",x).put("y",.2).put("width",width).put("height",.3)));}
 static JSONObject scene(String blend,boolean empty,double x,double opacity)throws Exception {
  JSONObject d=SketchDocument.create(new JSONObject().put("sketchId","multiply_alpha").put("width",256).put("height",256).put("frameCount",1).put("background","#00000000"));SketchEditOps.apply(d,new JSONObject().put("type","add_object").put("layerId","layer_1").put("object",rect("red","#FFFF0000",.1,.3)));SketchEditOps.apply(d,new JSONObject().put("type","add_layer").put("layer",new JSONObject().put("id","top").put("blendMode",blend).put("opacity",opacity)));if(!empty)SketchEditOps.apply(d,new JSONObject().put("type","add_object").put("layerId","top").put("object",rect("blue","#FF0000FF",x,.3)));return d;
 }
 static Bitmap render(JSONObject d,String label)throws Exception {
  try(SketchFrameRenderer r=new SketchFrameRenderer(RuntimeEnvironment.getApplication(),d)){Bitmap b=r.render(0,false);java.io.File root=new java.io.File("build/native-multiply-alpha/"+label+"-"+java.util.UUID.randomUUID());root.mkdirs();try(java.io.FileOutputStream out=new java.io.FileOutputStream(new java.io.File(root,"native.png"))){b.compress(Bitmap.CompressFormat.PNG,100,out);}return b;}
 }
 @Test public void emptyMultiplyLayerPreservesArtworkOverTransparency()throws Exception {Bitmap b=render(scene("multiply",true,.6,1),"empty");try{assertEquals(Color.RED,b.getPixel(51,77));assertEquals(0,b.getPixel(154,77));}finally{b.recycle();}}
 @Test public void separatedMultiplyPartsRetainBothSilhouettes()throws Exception {Bitmap b=render(scene("multiply",false,.6,1),"separated");try{assertEquals(Color.RED,b.getPixel(51,77));assertEquals(Color.BLUE,b.getPixel(179,77));assertEquals(0,b.getPixel(128,179));}finally{b.recycle();}}
 @Test public void partialOpacityMultipliesColorWithSourceOverAlpha()throws Exception {Bitmap b=render(scene("multiply",false,.25,.5),"partial");try{int overlap=b.getPixel(77,77);assertEquals(255,Color.alpha(overlap));assertTrue(Color.red(overlap)>=127&&Color.red(overlap)<=128);assertEquals(0,Color.green(overlap));assertEquals(0,Color.blue(overlap));assertEquals(Color.RED,b.getPixel(38,77));assertEquals(128,Color.alpha(b.getPixel(128,77)));}finally{b.recycle();}}
 @Test public void normalScreenAndAddKeepTheirExistingOverlappingColors()throws Exception {for(String mode:new String[]{"normal","screen","add"}){Bitmap b=render(scene(mode,false,.25,1),mode);try{assertEquals(mode,mode.equals("normal")?Color.BLUE:Color.MAGENTA,b.getPixel(77,77));}finally{b.recycle();}}}
}
