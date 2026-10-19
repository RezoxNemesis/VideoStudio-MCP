package com.rezoxnemesis.videostudio;

import android.graphics.*;
import org.json.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.*;
import java.io.*;
import java.util.*;
import static org.junit.Assert.*;

/** Exact 96-frame retained authoring regression from the native motion matrix. */
@RunWith(RobolectricTestRunner.class) @Config(sdk=33,manifest=Config.NONE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class SketchHandMotionBooleanTest {
    static JSONObject create()throws Exception {return new JSONObject("{\"sketchId\":\"hand_cel_33f4c882-cb8d-48ba-b8aa-a9b39fbb0d62\",\"width\":1280,\"height\":720,\"frameCount\":96,\"fps\":24,\"background\":\"#FFF2DFCB\"}");}
    static JSONObject operation()throws Exception {return new JSONObject("{\"type\":\"add_hand_animation\",\"layerId\":\"layer_1\",\"handId\":\"hand\",\"settings\":{\"view\":\"right\",\"handedness\":\"right\",\"size\":0.95,\"renderStyle\":\"cel\"}}");}
    @Test public void exactRightProfileOpenCloseRequestAuthorsAllNinetySixFrames()throws Exception {
        JSONObject doc=SketchDocument.create(create());SketchEditOps.apply(doc,operation());assertEquals(96,doc.getInt("frameCount"));assertEquals(24,doc.getInt("fps"));JSONObject rig=SketchDocument.object(doc,"hand_rig");assertEquals("cel",rig.getJSONObject("hand").getJSONObject("settings").getString("renderStyle"));assertEquals(96,rig.getInt("exposure"));
    }
    @Test public void exactSavedNativeRequestsRenderBothFormerlyFailingFrames()throws Exception {
        for(String view:new String[]{"right","high"}){JSONObject document=author(view);try(SketchFrameRenderer renderer=new SketchFrameRenderer(RuntimeEnvironment.getApplication(),document)){Bitmap frame=renderer.render(view.equals("right")?19:75);try{assertEquals(1280,frame.getWidth());assertEquals(720,frame.getHeight());}finally{frame.recycle();}}}
    }
    static JSONObject author(String view)throws Exception {
        android.content.Context context=RuntimeEnvironment.getApplication();JSONObject creation=create().put("operationId","create");if(view.equals("high"))creation.put("sketchId","hand_cel_2915e522-0066-402f-b7a7-5fd0867d13bf");else if(!view.equals("right"))creation.put("sketchId","hand_cel_motion_"+view);SketchEditOps.execute(context,"sketch_create",creation);
        JSONObject request=new JSONObject().put("sketchId",creation.getString("sketchId")).put("operationId","author_cel").put("expectedRevision",1).put("operations",new JSONArray().put(operation().put("settings",operation().getJSONObject("settings").put("view",view))));SketchEditOps.execute(context,"sketch_edit",request);
        try(SketchProjectStore store=new SketchProjectStore(context)){JSONObject document=store.get(creation.getString("sketchId"));assertEquals(2,document.getInt("revision"));assertEquals(96,document.getInt("frameCount"));assertEquals(24,document.getInt("fps"));return document;}
    }
    @Test public void exactFailingPoseNineteenRetainsAllNativeDetails()throws Exception {
        JSONObject s=SketchHandAnimation.settings(operation().getJSONObject("settings"));double[] p=SketchHandAnimation.poseAt(SketchHandAnimation.preset(96,24,s),19);assertInkContained(SketchHandGeometry.draw(s,p,1280,720));
    }
    @Test public void exactHighPoseSeventyFiveRetainsAllNativeDetails()throws Exception {
        JSONObject s=SketchHandAnimation.settings(operation().getJSONObject("settings").put("view","high"));double[] p=SketchHandAnimation.poseAt(SketchHandAnimation.preset(96,24,s),75);assertInkContained(SketchHandGeometry.draw(s,p,1280,720));
    }
    @Test public void wristTurnExtremeRetainsNativeCelInsetsAndDetails()throws Exception {
        JSONObject settings=SketchHandAnimation.settings(operation().getJSONObject("settings").put("view","front").put("size",1));
        double[] pose={.9,.9,.9,.9,.63,.675,.25,5,20};
        assertInkContained(SketchHandGeometry.draw(settings,pose,1280,720));
    }
    static void assertInkContained(List<SketchHandGeometry.Ink> marks)throws Exception {
        assertTrue("Retain cel skin, tones and details rather than falling back to smooth",marks.size()>=5&&marks.size()<=7);Path skin=marks.get(0).path;
        for(int i=1;i<marks.size()-1;i++){assertTrue(marks.get(i).fill);Path outside=new Path();assertTrue(outside.op(marks.get(i).path,skin,Path.Op.DIFFERENCE));assertTrue("Every actual tone/nail/crease stays strictly within skin",outside.isEmpty());}
        Path mismatch=new Path();assertTrue(mismatch.op(skin,marks.get(marks.size()-1).path,Path.Op.XOR));assertTrue("Details cannot change the skin-only contour",mismatch.isEmpty());
    }
    @Test public void repairedAndControlMotionRequestsRenderAllNinetySixNativeFrames()throws Exception {
        String root=System.getProperty("videostudio.handmotion.output");if(root==null)return;
        for(String view:new String[]{"front","left","right","high"}){JSONObject document=author(view);String before=SketchDocument.canonical(document);File folder=new File(root,view);folder.mkdirs();try(Writer w=new FileWriter(new File(folder,"document.json"))){w.write(document.toString());}
            int[] stationaryForearm=null;JSONArray receipts=new JSONArray();try(SketchFrameRenderer renderer=new SketchFrameRenderer(RuntimeEnvironment.getApplication(),document)){for(int f=0;f<96;f++){Bitmap b=renderer.render(f);try{save(b,new File(folder,String.format(Locale.ROOT,"frames/frame-%04d.png",f)));
                // A physical forearm patch below the wrist never follows the
                // hand programme. Sample actual renderer pixels independently
                // of Boolean paths, face ownership or the lighting helper.
                int height=view.equals("high")?20:40;int[] pixels=new int[30*height];b.getPixels(pixels,0,30,625,view.equals("high")?510:550,30,height);
                if(stationaryForearm==null)stationaryForearm=pixels;else assertArrayEquals("Stationary forearm palette in "+view+" frame "+f,stationaryForearm,pixels);
            }finally{b.recycle();}receipts.put(new JSONObject().put("frame",f));System.out.println("MOTION RETAKE "+view+" "+f+"/95");}}
            assertEquals(96,receipts.length());assertEquals("Native rendering must preserve the editable programme",before,SketchDocument.canonical(document));try(Writer w=new FileWriter(new File(folder,"frames.json"))){w.write(receipts.toString());}
        }
    }
    private static void save(Bitmap b,File file)throws Exception {assertTrue(file.getParentFile().isDirectory()||file.getParentFile().mkdirs());try(OutputStream out=new FileOutputStream(file)){assertTrue(b.compress(Bitmap.CompressFormat.PNG,100,out));}}
    @Test public void stationaryFarForearmPreservesNativePaletteAcrossMotion()throws Exception {
        JSONObject document=author("left");int[] reference=null;
        String root=System.getProperty("videostudio.handmotion.output");
        try(SketchFrameRenderer renderer=new SketchFrameRenderer(RuntimeEnvironment.getApplication(),document)){
            for(int f:new int[]{23,24,25,75,76,77,90,91,92}){Bitmap b=renderer.render(f);try{
                if(root!=null)save(b,new File(root,String.format(Locale.ROOT,"left/frames/frame-%04d.png",f)));
                int[] pixels=new int[30*40];b.getPixels(pixels,0,30,625,550,30,40);
                if(reference==null)reference=pixels;else assertArrayEquals("Stationary lower forearm must preserve light field and cannot acquire crease fill at frame "+f,reference,pixels);
            }finally{b.recycle();}}
        }
    }
    @Test public void leftSeventeenCannotFloodCreaseInkIntoTheStationaryForearm()throws Exception {
        JSONObject document=author("left");
        try(SketchFrameRenderer renderer=new SketchFrameRenderer(RuntimeEnvironment.getApplication(),document)){
            for(int frame:new int[]{16,17,18}){Bitmap bitmap=renderer.render(frame);try{
                assertEquals("A hand crease cannot repaint a fixed forearm interior at frame "+frame,0xFFE4AB80,bitmap.getPixel(630,500));
            }finally{bitmap.recycle();}}
        }
    }
    // Frozen native line-only owner mask at right/right frame19. Raw float
    // bits retain every original vertex and contour marker without tracing.
    private static final int[] MASK_BITS={
        0x00000000,0x4434c753,0x433f9e58,0x3d564c05,0x442efb7a,0x4342856b,0x3e68441d,0x441eee80,0x436eb3b2,
        0x3e88a0b8,0x441e33fd,0x43802b8b,0x3e890fb0,0x441e4c2c,0x43802d4a,0x3e89e8b7,0x441e7b78,0x438030b5,
        0x3e90ba30,0x441eb345,0x437a7f4f,0x3e91adc6,0x441eddb2,0x4379ff73,0x3e94e58d,0x441f6d2c,0x43784f0b,
        0x3e98e601,0x44201f9a,0x4376354f,0x3e9b7f98,0x44209387,0x4374d7f0,0x3ecd2a8c,0x44293a18,0x435ac5d0,
        0x3edff01f,0x442c7f15,0x4350eb59,0x3ee21512,0x442cf4ad,0x435091b5,0x3eebf1c8,0x442f1183,0x434ef56e,
        0x3ef3ec77,0x4430c717,0x434da7dd,0x3ef6a745,0x44315cc9,0x434d35c0,0x3ef809e3,0x4431a8c0,0x434cfbd8,
        0x3ef8733e,0x4431b8ca,0x434d3da5,0x3f153637,0x4439547e,0x436c734a,0x3f178a04,0x4439ee67,0x436fb8c7,
        0x3f192c2c,0x443a5a6c,0x4372048f,0x3f1b22a2,0x443adc38,0x4374c6d9,0x3f1b78a1,0x443af26f,0x43753fb9,
        0x3f2d3e96,0x443b3969,0x438a1e33,0x3f2e7ece,0x443b3e68,0x438b3557,0x3f2e8b4a,0x443b3eff,0x438b402b,
        0x3f2ef59c,0x443b4402,0x438b9c5e,0x3f301afb,0x443b51d8,0x438c9ac6,0x3f3045ab,0x443b5126,0x438cbffb,
        0x3f315782,0x443b4cae,0x438daeae,0x3f317d8e,0x443b4764,0x438dce23,0x3f32939a,0x443b20bf,0x438eb408,
        0x3f3298f2,0x443b2001,0x438eb873,0x3f3384db,0x443ae5f5,0x438f625b,0x3f33e2ba,0x443acedc,0x438fa5f7,
        0x3f33e8d9,0x443accd6,0x438fa973,0x3f342aad,0x443ab715,0x438fceec,0x3f344a98,0x443aac89,0x438fe117,
        0x3f346068,0x443aa554,0x438fed82,0x3f346408,0x443aa421,0x438fef92,0x3f346564,0x443aa3ae,0x438ff058,
        0x3f3465f8,0x443aa37d,0x438ff0ac,0x3f35b5f2,0x443b356d,0x438fd63c,0x3f3705ed,0x443aa37e,0x438ff0c2,
        0x3f3864f3,0x443b3c06,0x438fd66c,0x3f39d672,0x443bd4c7,0x438f6fb9,0x3f3b3f81,0x443c593d,0x438ec568,
        0x3f3c9e94,0x443cc062,0x438de313,0x3f3df4c4,0x443d032e,0x438cd827,0x3f3f45b8,0x443d1d12,0x438bb6d5,
        0x3f40fae2,0x443d230e,0x438a39af,0x3f553688,0x443e0d27,0x437157b7,0x3f5c31d8,0x443c89af,0x4366c613,
        0x3f78d59a,0x4434c753,0x433f9e58,0x3f78d59a,0x4424bbd7,0x438490a3,0x3f7accc6,0x4424ec11,0x43863cd1,
        0x3f7b93a4,0x4424c47c,0x4385a277,0x3f7c1802,0x4424ae24,0x43853800,0x3f7ca429,0x4424967c,0x4384c746,
        0x3f7cc0e9,0x4424a09b,0x4384b878,0x3f7d0e46,0x4424bbd7,0x438490a3,0x3f7d0e46,0x44244a24,0x43859c17,
        0x3f7def50,0x442472c3,0x43864eca,0x3f7f4d00,0x44245827,0x43852271,0x3f7f9eb5,0x44243b63,0x43854c84,
        0x3f800000,0x44244a24,0x43859c17,
    };
    private static Path frozenOwnerMask(){Path p=new Path();p.setFillType(Path.FillType.EVEN_ODD);for(int i=0;i<MASK_BITS.length;i+=3){float x=Float.intBitsToFloat(MASK_BITS[i+1]),y=Float.intBitsToFloat(MASK_BITS[i+2]);if(i==0||MASK_BITS[i]==MASK_BITS[i-3]){if(i>0)p.close();p.moveTo(x,y);}else p.lineTo(x,y);}p.close();return p;}
    @Test public void conditionedInsetRetainsExactOperandsAndIndependentRasterDifference()throws Exception {
        Path owner=frozenOwnerMask(),border=new Path();Paint edge=new Paint();edge.setStyle(Paint.Style.STROKE);edge.setStrokeJoin(Paint.Join.ROUND);edge.setStrokeWidth(.057f);assertTrue(edge.getFillPath(owner,border));float[] originalBorder=border.approximate(.00005f);
        Path inset=new Path(owner);java.lang.reflect.Method op=SketchHandGeometry.class.getDeclaredMethod("celOp",Path.class,Path.class,Path.Op.class);op.setAccessible(true);op.invoke(null,inset,border,Path.Op.DIFFERENCE);assertArrayEquals("Conditioning cannot mutate the other operand",originalBorder,border.approximate(.00005f),0);
        RectF bounds=new RectF();owner.computeBounds(bounds,true);Matrix transform=new Matrix();transform.setValues(new float[]{8,0,-(float)Math.floor(bounds.left)*8+8,0,8,-(float)Math.floor(bounds.top)*8+8,0,0,1});int w=(int)Math.ceil(bounds.width()*8)+32,h=(int)Math.ceil(bounds.height()*8)+32;int[][] pixels=new int[3][w*h];Path[] paths={owner,border,inset};Paint paint=new Paint();paint.setColor(Color.WHITE);
        for(int i=0;i<3;i++){Bitmap b=Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888);try{Path path=new Path(paths[i]);path.transform(transform);new Canvas(b).drawPath(path,paint);b.getPixels(pixels[i],0,w,0,0,w,h);}finally{b.recycle();}}
        int checked=0,kept=0;for(int y=1;y<h-1;y++)for(int x=1;x<w-1;x++){int at=y*w+x;boolean stable=true;for(int q:new int[]{at-1,at+1,at-w,at+w})if(pixels[0][q]!=pixels[0][at]||pixels[1][q]!=pixels[1][at])stable=false;if(!stable)continue;boolean expected=pixels[0][at]!=0&&pixels[1][at]==0;assertEquals("Independent skin minus border samples must retain holes and all interior ink",expected,pixels[2][at]!=0);checked++;if(expected)kept++;}assertTrue(checked>10000);assertTrue(kept>10000);
    }
}
