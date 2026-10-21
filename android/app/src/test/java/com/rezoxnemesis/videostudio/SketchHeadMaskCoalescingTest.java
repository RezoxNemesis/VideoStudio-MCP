package com.rezoxnemesis.videostudio;
import android.graphics.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.*;
import java.lang.reflect.*;
import java.util.*;
import static org.junit.Assert.*;
/** Joining construction triangles must preserve their actual filled union, including concavities and holes. */
@RunWith(RobolectricTestRunner.class) @Config(sdk=33,manifest=Config.NONE) @GraphicsMode(GraphicsMode.Mode.NATIVE)
public class SketchHeadMaskCoalescingTest {
 static List<double[]> polygon(double... xy){List<double[]> p=new ArrayList<>();for(int i=0;i<xy.length;i+=2)p.add(new double[]{xy[i],xy[i+1]});return p;}
 @SuppressWarnings("unchecked") static List<List<double[]>> merged(List<List<double[]>> p)throws Exception{Method m=SketchAnatomyHead.DepthOccluder.class.getDeclaredMethod("coalesce",List.class);m.setAccessible(true);return(List<List<double[]>>)m.invoke(null,p);}
 static Bitmap render(List<List<double[]>> polygons,double offset){Path path=new Path();for(List<double[]> p:polygons){double area=0;for(int i=0;i<p.size();i++){double[] a=p.get(i),b=p.get((i+1)%p.size());area+=a[0]*b[1]-a[1]*b[0];}for(int i=0;i<p.size();i++){double[] q=p.get(area<0?p.size()-1-i:i);if(i==0)path.moveTo((float)(q[0]-offset),(float)(q[1]-offset));else path.lineTo((float)(q[0]-offset),(float)(q[1]-offset));}path.close();}Bitmap image=Bitmap.createBitmap(128,128,Bitmap.Config.ARGB_8888);Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);paint.setColor(Color.BLACK);new Canvas(image).drawPath(path,paint);return image;}
 static void same(List<List<double[]>> input,List<List<double[]>> output,double offset){Bitmap a=render(input,offset),b=render(output,offset);try{assertTrue("Coalescing must preserve the actual native mask pixels",a.sameAs(b));}finally{a.recycle();b.recycle();}}
 @Test public void adjacentAndOverlappingConvexConstructionFacesMergeWithoutChangingTheirUnion()throws Exception{for(double o:new double[]{0,4000}){List<List<double[]>> p=new ArrayList<>();p.add(polygon(o+12,o+12,o+100,o+12,o+100,o+100));p.add(polygon(o+12,o+12,o+100,o+100,o+12,o+100));p.add(polygon(o+25,o+25,o+80,o+25,o+80,o+80,o+25,o+80));List<List<double[]>> q=merged(p);assertEquals("Shared triangulation boundaries need only one convex mask",1,q.size());same(p,q,o);}}
 @Test public void concavitiesAndVisibleHolesCannotBeReplacedByTheirConvexHull()throws Exception{List<List<double[]>> p=new ArrayList<>();p.add(polygon(12,12,100,12,100,30,12,30));p.add(polygon(12,30,30,30,30,100,12,100));p.add(polygon(30,82,100,82,100,100,30,100));p.add(polygon(82,30,100,30,100,82,82,82));List<List<double[]>> q=merged(p);same(p,q,0);Bitmap b=render(q,0);try{assertEquals("A protected visible hole must remain completely unmasked",0,b.getPixel(64,64));}finally{b.recycle();}List<List<double[]>> l=Arrays.asList(polygon(12,12,100,12,100,30,12,30),polygon(12,30,30,30,30,100,12,100));List<List<double[]>> result=merged(l);assertEquals(2,result.size());same(l,result,0);}
}
