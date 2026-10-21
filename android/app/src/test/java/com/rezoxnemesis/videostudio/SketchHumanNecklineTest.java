package com.rezoxnemesis.videostudio;

import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Path;
import android.graphics.RectF;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.security.MessageDigest;
import static org.junit.Assert.*;

/** Real Figure/Skia coverage and pre-change graphic pixels, independent of face art. */
@RunWith(RobolectricTestRunner.class) @Config(sdk=33,manifest=Config.NONE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class SketchHumanNecklineTest {
    private static final String[] VIEWS={"front","left","right","back","high","low"};
    // Captured before the neckline edit, from the native renderer's ARGB pixels.
    private static final String[] GRAPHIC_PIXELS={
        "98ab1a56e646e149a61b0dfd5da242fff98c4a859b19b416fe9475db01213723",
        "b0d90f1d89edb146557b684f4f8446bc98efde99344c28179a9d25acba562fb2",
        "c1781b9d40dc04341932a9e2aded66cbd0b626f6067b7fa9e1a02de86f324f32",
        "c3cfe44d24988034871da580b338eb9939d553e6eaeeb55bc357f3bb99ed6472",
        "7f44d3720f080ca357d306dda4961dddf8e928a54f506d74c433a38fd94cf68d",
        "f26d1490b14db71ac870d2f9627cc52cebf467a139635f283099b9e1d0adecf3",
        "ce17298c70a2675e1b3e6fb360496fb4d9b6fdf43ee34e30d296b7c9bf818523",
        "8a1f26ddc5131b81880246df500657322ff0a7934c36c2709f76660c3494d2c0",
        "41a13d2c7a3b0e5563da7ebee869e15ea73e2123c76d620c199cb07569d3102b",
        "d449afe5cf369a86872218d4c30f8847852f02ededb350ba5ac5d1211d614f12",
        "abd27b0be3d85f98d6c356e6320a72baefe5fdc86a07c63d51f9c3e4a44e8d1e",
        "066ca8ba6305273f8f0469046d9be8926223bd43269f3cb6f7efa482dfeb15d3"
    };

    @Test public void newStylesCoverTheLowerNeckWithContinuousGarmentGeometryInAllViews()throws Exception {
        for(String view:VIEWS)for(String style:new String[]{"angular","soft"})for(String build:new String[]{"slender","broad"})for(int lean:new int[]{-12,12}){
            JSONObject s=settings(style,view).put("build",build),pose=new JSONObject().put("bodyLean",lean).put("headTilt",-lean),doc=frameDocument(s,pose);
            double[] c=upperNeckPoint(s,pose,10);Path probe=new Path();
            // An eight-pixel-wide band of the lower neck must be garment, not a
            // disconnected collar line or a changed skin-color paint swatch.
            double[] shoulder=SketchHumanFigure.project(s,SketchHumanFigure.skeleton(s,pose).shoulderCentre),hip=SketchHumanFigure.project(s,SketchHumanFigure.skeleton(s,pose).hip);
            double height=Math.hypot(shoulder[0]-hip[0],shoulder[1]-hip[1]),ux=(shoulder[0]-hip[0])/height,uy=(shoulder[1]-hip[1])/height,nx=-uy,ny=ux;
            probe.moveTo((float)(c[0]-4*nx+ux),(float)(c[1]-4*ny+uy));
            probe.lineTo((float)(c[0]+4*nx+ux),(float)(c[1]+4*ny+uy));probe.lineTo((float)(c[0]+4*nx-ux),(float)(c[1]+4*ny-uy));probe.lineTo((float)(c[0]-4*nx-ux),(float)(c[1]-4*ny-uy));probe.close();
            inside(probe,garment(doc),"Raised garment must cover the lower-neck band: "+style+" "+view+" "+build+" "+lean);
        }
    }
    @Test public void necklineCoverageRisesTwelvePixelsAndGarmentRootsStayConnected()throws Exception {
        for(String view:VIEWS){JSONObject graphic=frameDocument(settings("graphic",view),new JSONObject());
            for(String style:new String[]{"angular","soft"}){
                JSONObject doc=frameDocument(settings(style,view),new JSONObject());double before=necklineY(graphic),after=necklineY(doc);
                assertEquals("Actual native collar coverage rises twelve pixels in "+view,12,before-after,.06);
                Path coat=path(part(doc,"coat",0)),other=garment(doc),intersection=new Path();assertTrue(intersection.op(coat,other,Path.Op.INTERSECT));assertFalse(intersection.isEmpty());
                for(String name:new String[]{"shirt","collar","lapel"})for(int serial:new int[]{0,2}){JSONObject piece=part(doc,name,serial);if(piece==null)continue;Path connected=new Path();assertTrue(connected.op(path(piece),coat,Path.Op.INTERSECT));assertFalse("A raised "+name+" must remain attached to the coat in "+view,connected.isEmpty());}
                // Extending the inner neckline must not move the coat's hem or
                // shoulder/arm silhouette. Compare the actual lower coat surfaces.
                Path changed=new Path(),lower=new Path(),outside=new Path();assertTrue(changed.op(coat,path(part(graphic,"coat",0)),Path.Op.XOR));
                lower.addRect(0,(float)upperNeckPoint(settings(style,view),new JSONObject(),-1)[1],1280,720,Path.Direction.CW);assertTrue(outside.op(changed,lower,Path.Op.INTERSECT));assertTrue("Only the upper coat contour changes",outside.isEmpty());
            }
        }
    }
    @Test public void nativePixelsReplaceTheExposedNeckBandAcrossAllSixViews()throws Exception {
        for(String view:VIEWS){JSONObject s=settings("graphic",view);double[] c=upperNeckPoint(s,new JSONObject(),10);Bitmap original=render(frameDocument(s,new JSONObject()));
            try{assertEquals("Old graphic neck remains exposed",Color.parseColor(s.getString("skinColor")),original.getPixel((int)Math.round(c[0]),(int)Math.round(c[1])));}finally{original.recycle();}
            for(String style:new String[]{"angular","soft"}){Bitmap b=render(frameDocument(settings(style,view),new JSONObject()));try{int pixel=b.getPixel((int)Math.round(c[0]),(int)Math.round(c[1]));assertNotEquals("Native pixels must cover the old skin band in "+style+" "+view,Color.parseColor(s.getString("skinColor")),pixel);assertNotEquals(Color.WHITE,pixel);}finally{b.recycle();}}
        }
    }
    @Test public void skeletonGripAndUnrelatedNativeContoursRemainExactlyUnchanged()throws Exception {
        for(String view:VIEWS)for(int lean:new int[]{-12,12}){
            JSONObject pose=new JSONObject().put("bodyLean",lean).put("headTilt",20).put("nearWrist",35),s=settings("graphic",view);SketchHumanFigure.Skeleton baseline=SketchHumanFigure.skeleton(s,pose);JSONObject old=frameDocument(s,pose);
            for(String style:new String[]{"angular","soft"}){JSONObject next=settings(style,view),doc=frameDocument(next,pose);SketchHumanFigure.Skeleton actual=SketchHumanFigure.skeleton(next,pose);
                for(Field field:SketchHumanFigure.Skeleton.class.getDeclaredFields())if(field.getType()==double[].class)assertArrayEquals("Unchanged skeleton "+field.getName(),(double[])field.get(baseline),(double[])field.get(actual),0);
                for(boolean near:new boolean[]{true,false})assertArrayEquals(SketchHumanFigure.grip(s,pose,near),SketchHumanFigure.grip(next,pose,near),0);
                for(String name:new String[]{"neck","neck_shade","near_upper_arm","far_upper_arm","near_forearm","far_forearm","near_hand","far_hand","left_shin","right_shin","left_shoe","right_shoe"})assertEquals("Unchanged native "+name,part(old,name,0).getJSONArray("commands").toString(),part(doc,name,0).getJSONArray("commands").toString());
            }
        }
        SketchHumanFigure.Skeleton neutral=SketchHumanFigure.skeleton(settings("angular","front"),new JSONObject());assertArrayEquals(new double[]{0,-300,0},neutral.shoulderCentre,0);assertArrayEquals(new double[]{0,-336,0},neutral.neck,0);assertArrayEquals(new double[]{0,-360,0},neutral.head,0);
    }
    @Test public void graphicPixelsMatchAllTwelvePreChangeNativeBaselines()throws Exception {
        int index=0;for(String view:VIEWS)for(int lean:new int[]{0,12}){
            Bitmap b=render(frameDocument(settings("graphic",view),new JSONObject().put("bodyLean",lean).put("headTilt",-8)));
            try{assertEquals("Graphic archive parity "+view+" lean="+lean,GRAPHIC_PIXELS[index++],pixelHash(b));}finally{b.recycle();}
        }
    }
    @Test public void optionalNativeSixViewPortraitEvidenceIncludesLongHairAndActing()throws Exception {
        String folder=System.getProperty("videostudio.neckline.output");if(folder==null)return;
        for(String style:new String[]{"graphic","angular","soft"})for(String view:VIEWS){
            JSONObject doc=frameDocument(settings(style,view).put("hairStyle",style.equals("soft")?"long":"short"),new JSONObject());portrait(doc);save(doc,new File(folder,style+"-"+view+".png"));
        }
        for(String style:new String[]{"angular","soft"}){JSONObject doc=frameDocument(settings(style,"front").put("hairStyle",style.equals("soft")?"long":"short"),new JSONObject().put("bodyLean",12).put("headTilt",-8).put("mouth",.65).put("brow",-.6));portrait(doc);save(doc,new File(folder,style+"-front-acting.png"));}
    }

    private static JSONObject settings(String style,String view)throws Exception{return SketchHumanFigure.settings(new JSONObject().put("faceStyle",style).put("view",view));}
    private static JSONObject frameDocument(JSONObject settings,JSONObject pose)throws Exception {JSONObject doc=SketchDocument.create(new JSONObject().put("sketchId","neckline").put("width",1280).put("height",720).put("frameCount",1).put("fps",30));SketchHumanFigure.draw(doc,layer(doc),"actor",settings,pose,0);return doc;}
    private static JSONObject layer(JSONObject doc)throws Exception{return doc.getJSONArray("layers").getJSONObject(0);}
    private static JSONObject part(JSONObject doc,String name,int serial)throws Exception {JSONArray a=layer(doc).getJSONArray("objects");for(int i=0;i<a.length();i++)if(a.getJSONObject(i).getString("id").equals("actor_"+name+"_"+serial))return a.getJSONObject(i);return null;}
    private static Path path(JSONObject part)throws Exception {assertNotNull(part);return SketchFrameRenderer.path(part.getJSONArray("commands"),1280,720);}
    private static Path garment(JSONObject doc)throws Exception {Path result=new Path();JSONArray a=layer(doc).getJSONArray("objects");for(int i=0;i<a.length();i++){JSONObject o=a.getJSONObject(i);if(o.getBoolean("fill")&&o.getString("id").matches("actor_(coat|shirt|collar|lapel)_\\d+"))assertTrue(result.op(path(o),Path.Op.UNION));}return result;}
    private static double[] upperNeckPoint(JSONObject s,JSONObject pose,double up)throws Exception {SketchHumanFigure.Skeleton p=SketchHumanFigure.skeleton(s,pose);double[] point=new double[3];for(int i=0;i<3;i++)point[i]=p.shoulderCentre[i]+(p.neck[i]-p.shoulderCentre[i])*up/36;double[] q=SketchHumanFigure.project(s,point);return new double[]{640+q[0],720*.88+q[1]};}
    private static double necklineY(JSONObject doc)throws Exception {Path strip=new Path(),section=new Path();strip.addRect(639.95f,200,640.05f,400,Path.Direction.CW);assertTrue(section.op(garment(doc),strip,Path.Op.INTERSECT));RectF r=new RectF();section.computeBounds(r,true);assertFalse(r.isEmpty());return r.top;}
    private static void inside(Path a,Path b,String message){Path outside=new Path();assertTrue(outside.op(a,b,Path.Op.DIFFERENCE));assertTrue(message,outside.isEmpty());}
    private static Bitmap render(JSONObject doc)throws Exception {try(SketchFrameRenderer r=new SketchFrameRenderer(RuntimeEnvironment.getApplication(),doc)){return r.render(0);}}
    private static String pixelHash(Bitmap b)throws Exception {int[] pixels=new int[b.getWidth()*b.getHeight()];b.getPixels(pixels,0,b.getWidth(),0,0,b.getWidth(),b.getHeight());ByteBuffer bytes=ByteBuffer.allocate(pixels.length*4);for(int p:pixels)bytes.putInt(p);return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes.array()));}
    private static void portrait(JSONObject doc)throws Exception {layer(doc).put("transform",SketchDocument.transform().put("scaleX",2.5).put("scaleY",2.5).put("y",.03));}
    private static void save(JSONObject doc,File file)throws Exception {assertTrue(file.getParentFile().isDirectory()||file.getParentFile().mkdirs());Bitmap b=render(doc);try(FileOutputStream out=new FileOutputStream(file)){assertTrue(b.compress(Bitmap.CompressFormat.PNG,100,out));}finally{b.recycle();}}
}
