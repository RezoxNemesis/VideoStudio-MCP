package com.rezoxnemesis.videostudio;

import android.graphics.*;
import org.json.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.*;
import java.io.*;
import java.lang.reflect.Method;
import java.security.MessageDigest;
import java.util.*;
import static org.junit.Assert.*;

/** Native skin coverage and line cleanliness, independent of the Boolean backend. */
@RunWith(RobolectricTestRunner.class) @Config(sdk=33,manifest=Config.NONE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class SketchHandSkinContourTest {
    private static final int BACKGROUND=0xFFF2DFCB;
    private static final class Loop {
        final Path path;final float[] points;final RectF box=new RectF();final double area2,perimeter;
        Loop(Path p,double length){path=p;perimeter=length;p.computeBounds(box,true);points=p.approximate(.0001f);double area=0;
            for(int i=3;i<points.length;i+=3)area+=((double)points[i-2]-points[1])*((double)points[i+2]-points[2])-((double)points[i+1]-points[1])*((double)points[i-1]-points[2]);area2=area;
        }
    }
    private static List<Loop> loops(Path p){List<Loop> out=new ArrayList<>();PathMeasure m=new PathMeasure(p,false);do{Path c=new Path();if(m.getSegment(0,m.getLength(),c,true)){c.close();out.add(new Loop(c,m.getLength()));}}while(m.nextContour());return out;}
    private static JSONObject settings(String view,String hand,boolean motion)throws Exception{return SketchHandAnimation.settings(SketchHandAnimation.defaults().put("renderStyle","cel").put("view",view).put("handedness",hand).put("size",motion?.95:1));}
    private static double[] pose(JSONObject settings,int frame)throws Exception{return frame<0?new double[]{0,0,0,0,0,0,.5,0,0}:SketchHandAnimation.poseAt(SketchHandAnimation.preset(96,24,settings),frame);}
    @SuppressWarnings("unchecked") private static List<SketchHandGeometry.Face> faces(JSONObject s,double[] p)throws Exception{Method m=SketchHandGeometry.class.getDeclaredMethod("celSurfaces",JSONObject.class,double[].class);m.setAccessible(true);return (List<SketchHandGeometry.Face>)m.invoke(null,s,p);}
    // Independent double-precision projected-triangle coverage. No native
    // Boolean, owner mask, cleanup predicate or pixel coordinate lookup.
    private static boolean meshCovers(List<SketchHandGeometry.Face> faces,double x,double y){
        for(SketchHandGeometry.Face f:faces)if(f.material==0)for(int t=1;t<f.p.length-1;t++){
            SketchHandGeometry.V a=f.p[0],b=f.p[t],c=f.p[t+1];double det=(b.y-c.y)*(a.x-c.x)+(c.x-b.x)*(a.y-c.y);if(Math.abs(det)<1e-15)continue;
            double u=((b.y-c.y)*(x-c.x)+(c.x-b.x)*(y-c.y))/det,v=((c.y-a.y)*(x-c.x)+(a.x-c.x)*(y-c.y))/det;if(u>=0&&v>=0&&u+v<=1)return true;
        }return false;
    }
    private static boolean coveredHole(Loop loop,List<SketchHandGeometry.Face> faces,double unit,double cx,double cy){
        int checked=0;float[] p=loop.points;
        for(int i=6;i<p.length;i+=3){double cross=((double)p[i-2]-p[1])*((double)p[i+2]-p[2])-((double)p[i+1]-p[1])*((double)p[i-1]-p[2]);if(cross==0)continue;
            double x=(p[1]+(double)p[i-2]+p[i+1])/3,y=(p[2]+(double)p[i-1]+p[i+2])/3;
            if(!meshCovers(faces,(x-cx)/unit,(y-cy)/unit))return false;checked++;
        }if(checked>0)return true;
        // A float conversion can collapse a closed hole all the way to a
        // segment. Its middle is still independently covered by the mesh.
        return meshCovers(faces,((loop.box.left+(double)loop.box.right)/2-cx)/unit,((loop.box.top+(double)loop.box.bottom)/2-cy)/unit);
    }
    // An independent width upper bound: distance from the longest chord,
    // rather than production's convex-hull minimum strip calculation.
    private static double chordWidth(Loop loop){float[] p=loop.points;double longest=0;int a=1,b=1;for(int i=1;i<p.length;i+=3)for(int j=i+3;j<p.length;j+=3){double dx=(double)p[j]-p[i],dy=(double)p[j+1]-p[i+1],d=dx*dx+dy*dy;if(d>longest){longest=d;a=i;b=j;}}
        if(longest==0)return 0;double lo=0,hi=0,dx=(double)p[b]-p[a],dy=(double)p[b+1]-p[a+1];for(int i=1;i<p.length;i+=3){double d=(dx*((double)p[i+1]-p[a+1])-dy*((double)p[i]-p[a]))/Math.sqrt(longest);lo=Math.min(lo,d);hi=Math.max(hi,d);}return hi-lo;
    }
    private static int inspect(JSONObject s,double[] p,int w,int h,String label)throws Exception{
        List<SketchHandGeometry.Ink> ink=SketchHandGeometry.draw(s,p,w,h);List<SketchHandGeometry.Face> faces=faces(s,p);double scale=h/720d,unit=112*scale*s.getDouble("size");int bad=0;
        for(Loop loop:loops(ink.get(ink.size()-1).path)){
            if(label.contains("front frame=13")&&RectF.intersects(loop.box,new RectF(620,410,625,418))||label.contains("front frame=65")&&RectF.intersects(loop.box,new RectF(612,410,618,419))||label.contains("front frame=83")&&RectF.intersects(loop.box,new RectF(590,377,597,383))||label.contains("high frame=0")&&RectF.intersects(loop.box,new RectF(633,323,639,330)))System.out.println("REVIEWED OUTLINE "+label+" area2="+loop.area2+" perimeter="+loop.perimeter+" chordWidth="+chordWidth(loop)+" bounds="+loop.box+" points="+(loop.points.length<90?Arrays.toString(loop.points):"outer boundary"));
            if(loop.area2<=0&&loop.perimeter>.1*scale&&Math.abs(loop.area2)/loop.perimeter<.00025*scale&&chordWidth(loop)<.00025*scale&&coveredHole(loop,faces,unit,w*s.getDouble("centerX"),h*s.getDouble("centerY"))){
            bad++;System.out.println("NUMERICAL SKIN HOLE "+label+" area2="+loop.area2+" perimeter="+loop.perimeter+" chordWidth="+chordWidth(loop)+" bounds="+loop.box+" points="+Arrays.toString(loop.points));
            }
        }return bad;
    }
    @Test public void motionOutlineDoesNotStrokeMeshCoveredSliverHoles()throws Exception{
        int bad=0;for(String view:new String[]{"front","high"})for(int frame:view.equals("front")?new int[]{12,13,14,64,65,66,82,83,84}:new int[]{0,1,41,59}){
            JSONObject s=settings(view,"right",true);double[] p=pose(s,frame);bad+=inspect(s,p,1280,720,view+" frame="+frame);
        }assertEquals("Closed internal numerical gaps must not become outline dashes",0,bad);
    }
    @Test public void bothLowOpenHandsHaveNoMeshCoveredSliverHolesAtEitherScale()throws Exception{
        int bad=0;for(String hand:new String[]{"right","left"})for(int h:new int[]{720,2048}){JSONObject s=settings("low",hand,false);bad+=inspect(s,pose(s,-1),h==720?1280:2048,h,hand+" low "+h);}
        assertEquals("Both actual low-view hands must retain continuous skin without stroked numerical holes",0,bad);
    }
    private static Path clean(Path source)throws Exception{Method m=SketchHandGeometry.class.getDeclaredMethod("celSkinCoverage",Path.class);m.setAccessible(true);return (Path)m.invoke(null,source);}
    private static void triangle(Path path,float ax,float ay,float bx,float by,float cx,float cy){path.moveTo(ax,ay);path.lineTo(bx,by);path.lineTo(cx,cy);path.close();}
    private static String geometry(Path path){StringBuilder out=new StringBuilder();for(Loop loop:loops(path))out.append(Arrays.toString(loop.points));return out.toString();}
    @Test public void cleanupPreservesRealHolesOpenGapsIslandsAndThinStrokes()throws Exception{
        for(float scale:new float[]{1,2048f/720}){
            Path expected=new Path();expected.addRect(600,300,720,440,Path.Direction.CW);
            // A genuine closed hole and a much narrower but still resolved
            // hole, an island inside a hole, and a gap connected to the outside.
            expected.addRect(630,330,640,350,Path.Direction.CCW);expected.addRect(634,336,636,340,Path.Direction.CW);
            expected.addRect(646,330,646.002f,350,Path.Direction.CCW);
            // Almost zero area is insufficient: this bent thin hole does not
            // fit a float-width straight strip and must remain untouched.
            expected.moveTo(705.83136f,356.95795f);expected.lineTo(706.61304f,358.7813f);expected.lineTo(705.947f,360.29526f);expected.lineTo(705.947f,360.2953f);expected.lineTo(706.61304f,358.7813f);expected.lineTo(705.83136f,356.9579f);expected.close();
            Path gap=new Path();gap.moveTo(600,370);gap.lineTo(610,370);gap.lineTo(610,390);gap.lineTo(600,390);gap.close();assertTrue(expected.op(gap,Path.Op.DIFFERENCE));
            // A separate thin filled stroke must survive even below the hole
            // width tolerance: outer components are never cleanup candidates.
            triangle(expected,730,320,732,321,732,Math.nextUp(321f));
            Path source=new Path(expected);triangle(source,661.08203f,328.67746f,662.2081f,328.56726f,662.208f,328.56726f);
            Matrix transform=new Matrix();transform.setScale(scale,scale);expected.transform(transform);source.transform(transform);
            Path result=clean(source);assertEquals("Only the unresolved closed hole is removed; all genuine paths are byte-for-byte retained",geometry(expected),geometry(result));
            Bitmap a=Bitmap.createBitmap(800,500,Bitmap.Config.ARGB_8888),b=Bitmap.createBitmap(800,500,Bitmap.Config.ARGB_8888);try{Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);paint.setColor(Color.WHITE);Canvas ca=new Canvas(a),cb=new Canvas(b);ca.scale(1/scale,1/scale);cb.scale(1/scale,1/scale);ca.drawPath(expected,paint);cb.drawPath(result,paint);int[] pa=new int[800*500],pb=new int[pa.length];a.getPixels(pa,0,800,0,0,800,500);b.getPixels(pb,0,800,0,0,800,500);assertArrayEquals("Native filled coverage is identical",pa,pb);}finally{a.recycle();b.recycle();}
        }
    }
    @Test public void nestedSkinIslandProtectsEvenAnUnresolvedHole()throws Exception{
        Path source=new Path();source.addRect(600,300,720,440,Path.Direction.CW);
        float ax=661.08203f,ay=328.67746f,bx=662.2081f,by=328.56726f,cx=662.208f,cy=328.56726f;
        triangle(source,ax,ay,bx,by,cx,cy);List<float[]> interior=new ArrayList<>();
        for(int i=1;i<256;i++){double t=i/256d;float x=(float)(ax+(bx-ax)*t),y=(float)(ay+(by-ay)*t);double ca=((double)bx-ax)*(y-ay)-((double)by-ay)*(x-ax),cb=((double)cx-bx)*(y-by)-((double)cy-by)*(x-bx),cc=((double)ax-cx)*(y-cy)-((double)ay-cy)*(x-cx);if(ca<0&&cb<0&&cc<0)interior.add(new float[]{x,y});}
        boolean island=false;for(int i=2;i<interior.size();i++){float[] a=interior.get(0),b=interior.get(i-1),c=interior.get(i);double cross=((double)b[0]-a[0])*(c[1]-a[1])-((double)b[1]-a[1])*(c[0]-a[0]);if(cross==0)continue;if(cross<0)triangle(source,a[0],a[1],c[0],c[1],b[0],b[1]);else triangle(source,a[0],a[1],b[0],b[1],c[0],c[1]);island=true;break;}
        assertTrue("The fixture contains an actual nonzero-area skin island",island);assertEquals("Never fill a hole containing a skin island",geometry(source),geometry(clean(source)));
    }
    @Test public void windingFilledStrokesAreNotMisclassifiedAsHoles()throws Exception{
        Path source=new Path();source.addRect(600,300,720,440,Path.Direction.CW);
        // Same direction as its containing outer loop: a filled stroke, not
        // a subtractive hole under native nonzero winding semantics.
        triangle(source,661.08203f,328.67746f,662.208f,328.56726f,662.2081f,328.56726f);
        assertEquals(Path.FillType.WINDING,source.getFillType());assertEquals(geometry(source),geometry(clean(source)));
    }
    private static JSONObject rig(JSONObject s,double[] pose)throws Exception{JSONObject p=new JSONObject().put("frame",0);for(int i=0;i<pose.length;i++)p.put(SketchHandAnimation.CHANNELS[i],pose[i]);return SketchEditOps.completeObject(new JSONObject().put("id","skin_rig").put("kind","hand").put("width",.0012).put("frame",0).put("exposure",1).put("hand",new JSONObject().put("settings",s).put("poses",new JSONArray().put(p)).put("durationFrames",96).put("fps",24)));}
    private static JSONObject frameDocument(JSONObject s,double[] p,int w,int h,boolean editable)throws Exception{JSONObject d=SketchDocument.create(new JSONObject().put("sketchId","skin_fixture").put("width",w).put("height",h).put("frameCount",1).put("fps",24).put("background","#FFF2DFCB"));JSONObject rig=rig(s,p);SketchHandAnimation.refreshBounds(d,rig);d.getJSONArray("layers").getJSONObject(0).put("objects",editable?SketchHandAnimation.vectorFrame(rig,w,h,0):new JSONArray().put(rig));return d;}
    private static void save(Bitmap bitmap,String label)throws Exception{String root=System.getProperty("videostudio.handskin.output");if(root==null)return;File f=new File(root,label+".png");assertTrue(f.getParentFile().isDirectory()||f.getParentFile().mkdirs());try(OutputStream out=new FileOutputStream(f)){assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG,100,out));}}
    @Test public void reviewedMotionPixelsStaySkinColoredInRetainedAndEditableFrames()throws Exception{
        int bad=0;String[] views={"front","front","front","high","high","high"};int[] frames={13,65,83,0,41,59},xs={622,615,593,635,645,645},ys={414,414,380,326,380,380};int[] expected={0xFFE4AB80,0xFFE4AB80,0xFFE4AB80,0xFFE4AB80,0xFFD6A078,0xFFD6A078};
        for(int i=0;i<frames.length;i++)for(boolean editable:new boolean[]{false,true}){JSONObject s=settings(views[i],"right",true);double[] p=pose(s,frames[i]);
            if(!editable){Bitmap underlay=Bitmap.createBitmap(1280,720,Bitmap.Config.ARGB_8888);try{underlay.eraseColor(BACKGROUND);Canvas canvas=new Canvas(underlay);Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);for(SketchHandGeometry.Ink ink:SketchHandGeometry.draw(s,p,1280,720))if(ink.fill){paint.setColor(Color.parseColor(ink.color));canvas.drawPath(ink.path,paint);}save(underlay,views[i]+"-"+frames[i]+"-underlay");assertEquals("Independent filled-ink control establishes the correct local palette",expected[i],underlay.getPixel(xs[i],ys[i]));}finally{underlay.recycle();}}
            try(SketchFrameRenderer renderer=new SketchFrameRenderer(RuntimeEnvironment.getApplication(),frameDocument(s,p,1280,720,editable))){Bitmap b=renderer.render(0);try{String label=views[i]+"-"+frames[i]+"-"+(editable?"editable":"retained");save(b,label);int color=b.getPixel(xs[i],ys[i]);if(color!=expected[i]){bad++;System.out.println("REVIEWED PIXEL "+label+" color="+Integer.toHexString(color));}}finally{b.recycle();}}}
        assertEquals("Previously identified outline dashes must resolve to their native skin tone",0,bad);
    }
    @Test public void projectedSkinMeshNormalsAndJointCoordinatesMatchFrozenV6()throws Exception{
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();DataOutputStream data=new DataOutputStream(bytes);
        for(String view:new String[]{"front","high","low"})for(String hand:new String[]{"right","left"})for(int frame:view.equals("low")?new int[]{-1}:view.equals("front")?new int[]{13,65,83}:new int[]{0,41,59}){
            JSONObject s=settings(view,hand,frame>=0);double[] p=pose(s,frame);
            for(SketchHandGeometry.V[] chain:SketchHandGeometry.joints(p))for(SketchHandGeometry.V v:chain){data.writeDouble(v.x);data.writeDouble(v.y);data.writeDouble(v.z);}
            for(SketchHandGeometry.Face f:faces(s,p)){data.writeInt(f.material);data.writeInt(f.owner);data.writeInt(f.p.length);for(SketchHandGeometry.V v:f.p){data.writeDouble(v.x);data.writeDouble(v.y);data.writeDouble(v.z);}for(SketchHandGeometry.V n:f.normals){data.writeDouble(n.x);data.writeDouble(n.y);data.writeDouble(n.z);}}
        }
        StringBuilder hash=new StringBuilder();for(byte b:MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray()))hash.append(String.format(Locale.ROOT,"%02x",b&255));System.out.println("FROZEN V6 MESH "+hash);
        // Captured from e00e891's unchanged v6 product class before this repair.
        assertEquals("Every projected skin/detail vertex, normal and anatomical joint remains unchanged","8358b4dae877cba5f5ff355d8fc92ab9219a29d54b728d542626971ba084d7f1",hash.toString());
    }
    @Test public void knownNativeRetakesIncludeBothScalesAndEditablePaths()throws Exception{
        if(System.getProperty("videostudio.handskin.output")==null)return;
        for(String view:new String[]{"front","high","low"})for(String hand:view.equals("low")?new String[]{"right","left"}:new String[]{"right"})for(int frame:view.equals("front")?new int[]{12,13,14,64,65,66,82,83,84}:view.equals("high")?new int[]{0,1,18,19,20,21,22,23,41,59}:new int[]{-1})for(int height:new int[]{720,2048}){
            JSONObject s=settings(view,hand,frame>=0);double[] p=pose(s,frame);int width=height==720?1280:2048;Bitmap retained=null;
            for(boolean editable:new boolean[]{false,true}){JSONObject d=frameDocument(s,p,width,height,editable);try(SketchFrameRenderer renderer=new SketchFrameRenderer(RuntimeEnvironment.getApplication(),d)){Bitmap b=renderer.render(0);try{
                String label=hand+"-"+view+"-"+(frame<0?"open":String.format(Locale.ROOT,"frame-%04d",frame))+"-"+height+"-"+(editable?"editable":"retained");save(b,label);
                if(!editable){retained=b.copy(Bitmap.Config.ARGB_8888,false);}else{
                    int[] a=new int[width*height],c=new int[a.length];retained.getPixels(a,0,width,0,0,width,height);b.getPixels(c,0,width,0,0,width,height);int deep=0,changed=0;
                    for(int y=2;y<height-2;y++)for(int x=2;x<width-2;x++){int at=y*width+x;if(a[at]==BACKGROUND&&a[at]==c[at])continue;boolean interior=true;for(int dy=-2;dy<=2&&interior;dy++)for(int dx=-2;dx<=2;dx++)if(a[at+dy*width+dx]!=a[at]){interior=false;break;}if(interior){deep++;if(a[at]!=c[at])changed++;}}
                    System.out.println("KNOWN RETAKE "+label+" deep="+changed+"/"+deep);assertTrue("Exercise native solid interiors",deep>1000);assertEquals("Known motion/static retakes preserve exact deep-interior coverage and palette",0,changed);
                }
            }finally{b.recycle();}}}
            if(retained!=null)retained.recycle();
        }
    }
}
