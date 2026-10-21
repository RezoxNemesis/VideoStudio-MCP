package com.rezoxnemesis.videostudio;
import android.graphics.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.*;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class) @Config(sdk=33,manifest=Config.NONE) @GraphicsMode(GraphicsMode.Mode.NATIVE)
public class LocalDrawingPairedPreprocessingTest {
 @Test public void verticalContrastProducesVerticalBinaryCannyEdges()throws Exception {
  Bitmap input=Bitmap.createBitmap(512,512,Bitmap.Config.ARGB_8888);Canvas canvas=new Canvas(input);canvas.drawColor(Color.BLACK);Paint paint=new Paint();paint.setColor(Color.WHITE);canvas.drawRect(256,0,512,512,paint);Bitmap guide=null;
  try{guide=LocalDrawingPairedSource.canny(input);int edges=0;double xTotal=0;for(int y=0;y<512;y++)for(int x=0;x<512;x++){int color=guide.getPixel(x,y);assertTrue(color==Color.BLACK||color==Color.WHITE);if(color==Color.WHITE){edges++;xTotal+=x;assertTrue("Edge remains on the actual vertical transition",x>=254&&x<=257);}}assertTrue(edges>=500&&edges<=2000);assertEquals(255.5,xTotal/edges,1);assertEquals(Color.BLACK,guide.getPixel(10,256));assertEquals(Color.BLACK,guide.getPixel(500,256));}finally{input.recycle();if(guide!=null)guide.recycle();}
 }
 @Test public void clipNormalizationUsesActualRgbChannelsAndVerifiedMeans()throws Exception {
  Bitmap image=Bitmap.createBitmap(224,224,Bitmap.Config.ARGB_8888);new Canvas(image).drawColor(Color.RED);try{float[] values=LocalDrawingPairedSource.clipTensor(image);assertEquals(3*224*224,values.length);assertEquals((1-.48145466)/.26862954,values[0],1e-6);assertEquals((0-.4578275)/.26130258,values[224*224],1e-6);assertEquals((0-.40821073)/.27577711,values[2*224*224],1e-6);}finally{image.recycle();}
 }
 @Test public void nativeBicubicCompositesTransparentColorOverWhiteBeforeNormalization()throws Exception {
  int[] pixels=new int[64*64];java.util.Arrays.fill(pixels,0x00ff0000);Bitmap image=LocalDrawingPairedSource.clipBicubic(pixels,64,64);try{assertEquals(Color.WHITE,image.getPixel(0,0));assertEquals(Color.WHITE,image.getPixel(223,223));}finally{image.recycle();}
 }
 @Test public void alphaCropExcludesNeighboringGreenPixelsBeforeBicubicFiltering()throws Exception {
  LocalDrawingPairedSourceTest fixture=new LocalDrawingPairedSourceTest();fixture.fixture();org.json.JSONObject request=SketchDocument.copy(fixture.request).put("previewOnly",true);request.getJSONObject("appearanceSource").getJSONObject("sourceRect").put("width",.25);
  org.json.JSONObject preview=fixture.execute(request).getJSONObject("localDrawingAppearancePreviewImage");byte[] png=android.util.Base64.decode(preview.getString("base64"),0);Bitmap image=BitmapFactory.decodeByteArray(png,0,png.length);try{for(int x:new int[]{0,1,100,222,223})assertEquals("Excluded neighboring green cannot bleed through the integer crop",Color.RED,image.getPixel(x,100));}finally{image.recycle();}
 }
}
