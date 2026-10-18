package com.rezoxnemesis.videostudio;

import android.graphics.*;
import org.json.*;
import java.util.*;

/** Original anatomical joint chains and native hidden-surface drawings; no imported art. */
final class SketchHandGeometry {
    private SketchHandGeometry(){}
    static final class V {
        final double x,y,z;V(double x,double y,double z){this.x=x;this.y=y;this.z=z;}
        V add(V b){return new V(x+b.x,y+b.y,z+b.z);}V sub(V b){return new V(x-b.x,y-b.y,z-b.z);}V mul(double n){return new V(x*n,y*n,z*n);}double dot(V b){return x*b.x+y*b.y+z*b.z;}
        V cross(V b){return new V(y*b.z-z*b.y,z*b.x-x*b.z,x*b.y-y*b.x);}double length(){return Math.sqrt(dot(this));}V unit(){double n=length();return n<1e-9?new V(1,0,0):mul(1/n);}
    }
    static final double[][] LENGTHS={{.67,.43,.33},{.76,.49,.35},{.70,.46,.33},{.54,.34,.27},{.54,.43,.32}};
    static V[][] joints(double[] pose){
        V[][] out=new V[5][4];double[] xs={-.46,-.12,.24,.54},ys={-1.29,-1.39,-1.34,-1.19},fans={-14,-3,7,19};
        for(int d=0;d<4;d++){out[d][0]=new V(xs[d],ys[d],.02);double angle=0,fan=Math.toRadians(fans[d]*pose[6]);for(int j=0;j<3;j++){angle+=Math.toRadians(new double[]{60,92,48}[j]*pose[d]);V direction=new V(Math.sin(fan)*Math.cos(angle),-Math.cos(fan)*Math.cos(angle),Math.sin(angle));out[d][j+1]=out[d][j].add(direction.mul(LENGTHS[d][j]));}}
        out[4][0]=new V(-.23,-.18,.12);double opposition=pose[5],fan=Math.toRadians(-56+75*opposition),angle=Math.toRadians(10+32*opposition);for(int j=0;j<3;j++){angle+=Math.toRadians(new double[]{12,48,55}[j]*pose[4]);V direction=new V(Math.sin(fan)*Math.cos(angle),-Math.cos(fan)*Math.cos(angle),Math.sin(angle));out[4][j+1]=out[4][j].add(direction.mul(LENGTHS[4][j]));}return out;
    }
    static final class Ink {final Path path;final String color;final boolean fill;JSONObject gradient;
        Ink(Path p,String c,boolean f){path=p;color=c;fill=f;}}
    static final class Face {final V[] p;final int material;V[] normals;Face(int m,V...p){this.p=p;material=m;}}
    private static List<Face> surfaces(JSONObject settings,double[] pose)throws Exception {
        List<Face> mesh=new ArrayList<>();V[][] joints=joints(pose);double[] radii={.155,.165,.15,.128,.19};
        // Wrist-to-forearm volume remains stationary; hand flexion is rooted at its socket.
        List<Face> arm=new ArrayList<>();tube(arm,new V[]{new V(0,2.08,-.03),new V(0,1.35,0),new V(0,.40,0),new V(0,0,0)},new double[]{.56,.47,.34,.33},0,.60);
        // Palm has distinct wrist, heel, broad metacarpal and knuckle cross-sections.
        V[] palm={new V(0,.10,0),new V(0,-.25,0),new V(.02,-.70,0),new V(.02,-1.12,0),new V(.02,-1.31,0)};
        palmSurface(mesh,palm,new double[]{.32,.48,.66,.65,.57});
        for(int d=0;d<5;d++){
            V[] centers=new V[10];double[] r=new double[10];centers[0]=joints[d][0].add(new V(0,.27,0));r[0]=radii[d]*1.12;centers[1]=joints[d][0];r[1]=radii[d];
            for(int j=0;j<3;j++){centers[j*2+2]=joints[d][j].mul(.08).add(joints[d][j+1].mul(.92));centers[j*2+3]=joints[d][j+1];r[j*2+2]=radii[d]*(1-j*.16);r[j*2+3]=radii[d]*(j==2?.46:1.03-j*.17);}
            V tipDirection=joints[d][3].sub(joints[d][2]).unit();
            centers[8]=joints[d][3].add(tipDirection.mul(radii[d]*.20));r[8]=radii[d]*.28;
            centers[9]=joints[d][3].add(tipDirection.mul(radii[d]*.29));r[9]=.003;
            tube(mesh,centers,r,0,.82);
            if(settings.getString("detail").equals("anatomical")){
                nail(mesh,joints[d][2],joints[d][3],radii[d]*.70);
                for(int j=1;j<3;j++)crease(mesh,joints[d][j],joints[d][j-1],joints[d][j+1],radii[d]*(1-(j-1)*.16));
            }
        }
        // Thenar pad bridges the thumb metacarpal into the palm rather than a separate tube.
        ellipsoid(mesh,new V(-.37,-.43,.13),new V(.36,.55,.22),0);
        ellipsoid(mesh,new V(.35,-.43,.07),new V(.27,.56,.19),0);
        if(settings.getString("detail").equals("anatomical")){
            palmLine(mesh,-.48,-.98,-.18,-.85,.30,-.86,false);
            palmLine(mesh,.44,-.65,.06,-.53,-.30,-.34,false);
            palmLine(mesh,-.43,-.94,-.17,-.53,-.24,-.14,false);
            palmLine(mesh,-.37,-.91,-.10,-.51,-.17,-.18,false);
            // Fine branching creases are authored on the same skin surface and follow the wrist.
            for(int i=0;i<9;i++){double y=-.92+i*.085;palmLine(mesh,-.02,y,.08,y+.018,.15,y-.01,false);}
            for(int i=0;i<7;i++){double y=-.85+i*.075;palmLine(mesh,-.48,y,-.38,y+.025,-.31,y+.035,false);}
            for(int d=0;d<4;d++)palmLine(mesh,joints[d][0].x,joints[d][0].y+.11,joints[d][0].x*.6,-.61,joints[d][0].x*.25,-.12,true);
        }
        smoothNormals(arm);smoothNormals(mesh);
        List<Face> transformed=new ArrayList<>();for(Face face:arm)transformed.add(camera(face,settings,pose,false));for(Face face:mesh)transformed.add(camera(face,settings,pose,true));
        return transformed;
    }
    static RectF bounds(JSONObject s,double[] pose,int width,int height)throws Exception {
        double unit=Math.min(width,height)/720d*112*s.getDouble("size"),cx=width*s.getDouble("centerX"),cy=height*s.getDouble("centerY");RectF b=new RectF();List<V> points=new ArrayList<>();
        addBox(points,-.70,-1.46,-.27,.72,.22,.39);addBox(points,-.73,-.98,-.12,.05,.14,.38);
        V[][] joints=joints(pose);for(V[] chain:joints)for(V joint:chain)addBox(points,joint.x-.24,joint.y-.24,joint.z-.24,joint.x+.24,joint.y+.29,joint.z+.24);
        boolean first=true;for(V point:points){V v=rotate(point,pose[7],pose[8]);if(s.getString("handedness").equals("left"))v=new V(-v.x,v.y,v.z);v=viewPoint(v,s.getString("view"));float x=(float)(cx+v.x*unit),y=(float)(cy+v.y*unit);if(first){b.set(x,y,x,y);first=false;}else{b.left=Math.min(b.left,x);b.top=Math.min(b.top,y);b.right=Math.max(b.right,x);b.bottom=Math.max(b.bottom,y);}}
        points.clear();addBox(points,-.56,-.02,-.35,.56,2.08,.35);for(V point:points){if(s.getString("handedness").equals("left"))point=new V(-point.x,point.y,point.z);V v=viewPoint(point,s.getString("view"));b.left=Math.min(b.left,(float)(cx+v.x*unit));b.top=Math.min(b.top,(float)(cy+v.y*unit));b.right=Math.max(b.right,(float)(cx+v.x*unit));b.bottom=Math.max(b.bottom,(float)(cy+v.y*unit));}return b;
    }
    private static void addBox(List<V> out,double x0,double y0,double z0,double x1,double y1,double z1){for(double x:new double[]{x0,x1})for(double y:new double[]{y0,y1})for(double z:new double[]{z0,z1})out.add(new V(x,y,z));}
    private static V viewPoint(V v,String view){return rotate(v,view.equals("high")?48:view.equals("low")?-48:0,view.equals("left")?-90:view.equals("right")?90:view.equals("back")?180:0);}
    static List<Ink> draw(JSONObject settings,double[] pose,int width,int height)throws Exception {
        List<Face> transformed=surfaces(settings,pose);
        // Front-to-back coverage clipping preserves finger/palm occlusion before shade grouping.
        transformed.sort((a,b)->Double.compare(depth(b),depth(a)));List<Ink> surfaces=new ArrayList<>();Path covered=new Path();
        double unit=Math.min(width,height)/720d*112*settings.getDouble("size"),cx=width*settings.getDouble("centerX"),cy=height*settings.getDouble("centerY");
        for(Face face:transformed){V normal=face.p[1].sub(face.p[0]).cross(face.p[2].sub(face.p[0])).unit();if(normal.z<=.00001)continue;
            Path shape=new Path();for(int i=0;i<face.p.length;i++){float x=(float)(cx+face.p[i].x*unit),y=(float)(cy+face.p[i].y*unit);if(i==0)shape.moveTo(x,y);else shape.lineTo(x,y);}shape.close();
            Path visible=new Path(shape);if(!covered.isEmpty()&&!visible.op(covered,Path.Op.DIFFERENCE))throw SketchDocument.bad("Native hand occlusion failed");if(visible.isEmpty())continue;
            String base=face.material==1?settings.getString("nailColor"):settings.getString("skinColor");V light=new V(-.45,-.55,1).unit();
            // A triangle's luminance is an affine plane. Fitting that plane makes
            // shared-edge shading agree; a min/max diagonal gradient did not.
            for(int t=1;t<face.p.length-1;t++){
                int[] indices={0,t,t+1};double[] x=new double[3],y=new double[3],values=new double[3];Path triangle=new Path();
                for(int j=0;j<3;j++){int i=indices[j];x[j]=cx+face.p[i].x*unit;y[j]=cy+face.p[i].y*unit;values[j]=face.material==2?.68:.64+.43*Math.max(0,face.normals[i].dot(light));if(j==0)triangle.moveTo((float)x[j],(float)y[j]);else triangle.lineTo((float)x[j],(float)y[j]);}triangle.close();
                if(!triangle.op(visible,Path.Op.INTERSECT))throw SketchDocument.bad("Native triangle visibility failed");if(triangle.isEmpty())continue;
                double dx1=x[1]-x[0],dy1=y[1]-y[0],dx2=x[2]-x[0],dy2=y[2]-y[0],det=dx1*dy2-dx2*dy1;
                Ink mark=new Ink(triangle,SketchCharacterArt.tone(base,(values[0]+values[1]+values[2])/3),true);
                double low=Math.min(values[0],Math.min(values[1],values[2])),high=Math.max(values[0],Math.max(values[1],values[2]));
                if(Math.abs(det)>1e-7&&high-low>.004){double gx=((values[1]-values[0])*dy2-(values[2]-values[0])*dy1)/det,gy=(dx1*(values[2]-values[0])-dx2*(values[1]-values[0]))/det,g2=gx*gx+gy*gy;
                    if(g2>1e-10){double x0=(x[0]+gx*(low-values[0])/g2)/width,y0=(y[0]+gy*(low-values[0])/g2)/height,x1=(x[0]+gx*(high-values[0])/g2)/width,y1=(y[0]+gy*(high-values[0])/g2)/height;
                        mark.gradient=new JSONObject().put("startColor",SketchCharacterArt.tone(base,low)).put("endColor",SketchCharacterArt.tone(base,high)).put("x0",x0).put("y0",y0).put("x1",x1).put("y1",y1);}
                }surfaces.add(mark);
            }
            if(!covered.op(shape,Path.Op.UNION))throw SketchDocument.bad("Native hand coverage failed");
        }
        List<Ink> out=new ArrayList<>();out.add(new Ink(covered,settings.getString("skinColor"),true));out.addAll(surfaces);out.add(new Ink(covered,SketchCharacterArt.tone(settings.getString("skinColor"),.52),false));return out;
    }
    private static void smoothNormals(List<Face> faces){
        Map<V,V> sums=new IdentityHashMap<>();for(Face face:faces){V n=face.p[1].sub(face.p[0]).cross(face.p[2].sub(face.p[0]));for(V v:face.p)sums.put(v,sums.getOrDefault(v,new V(0,0,0)).add(n));}
        for(Face face:faces){if(face.normals!=null)continue;face.normals=new V[face.p.length];for(int i=0;i<face.p.length;i++)face.normals[i]=sums.get(face.p[i]).unit();}
    }
    private static double depth(Face a){double z=0;for(V p:a.p)z+=p.z;return z/a.p.length;}
    private static Face camera(Face face,JSONObject s,double[] pose,boolean hand)throws Exception {
        String view=s.getString("view");double yaw=view.equals("left")?-90:view.equals("right")?90:view.equals("back")?180:0,pitch=view.equals("high")?48:view.equals("low")?-48:0;
        V[] p=new V[face.p.length],normals=new V[p.length];for(int i=0;i<p.length;i++){
            V v=face.p[i],n=face.normals[i];if(hand){v=rotate(v,pose[7],pose[8]);n=rotate(n,pose[7],pose[8]);}if(s.getString("handedness").equals("left")){v=new V(-v.x,v.y,v.z);n=new V(-n.x,n.y,n.z);}p[i]=rotate(v,pitch,yaw);normals[i]=rotate(n,pitch,yaw);
        }
        if(s.getString("handedness").equals("left")){for(int i=0;i<p.length/2;i++){V v=p[i];p[i]=p[p.length-1-i];p[p.length-1-i]=v;v=normals[i];normals[i]=normals[p.length-1-i];normals[p.length-1-i]=v;}}Face out=new Face(face.material,p);out.normals=normals;return out;
    }
    static V rotate(V v,double pitch,double yaw){double p=Math.toRadians(pitch),y=Math.toRadians(yaw);double yy=v.y*Math.cos(p)-v.z*Math.sin(p),zz=v.y*Math.sin(p)+v.z*Math.cos(p);return new V(v.x*Math.cos(y)+zz*Math.sin(y),yy,-v.x*Math.sin(y)+zz*Math.cos(y));}
    private static void tube(List<Face> mesh,V[] centers,double[] radii,int material,double depthScale){
        int sides=24;V[][] rings=new V[centers.length][sides],normals=new V[centers.length][sides];
        for(int j=0;j<centers.length;j++){
            V tangent=centers[Math.min(j+1,centers.length-1)].sub(centers[Math.max(0,j-1)]).unit();V axis=new V(1,0,0).sub(tangent.mul(tangent.x)).unit(),other=tangent.cross(axis).unit();
            double depth=depthScale==.42?Math.min(.25,radii[j]*.65)/radii[j]:depthScale;
            for(int i=0;i<sides;i++){double a=2*Math.PI*i/sides;rings[j][i]=centers[j].add(axis.mul(Math.cos(a)*radii[j])).add(other.mul(Math.sin(a)*radii[j]*depth));normals[j][i]=axis.mul(Math.cos(a)).add(other.mul(Math.sin(a)/depth)).unit();}
        }
        for(int j=0;j<rings.length-1;j++)for(int i=0;i<sides;i++){int k=(i+1)%sides;Face f=new Face(material,rings[j][i],rings[j][k],rings[j+1][k],rings[j+1][i]);f.normals=new V[]{normals[j][i],normals[j][k],normals[j+1][k],normals[j+1][i]};mesh.add(f);}
        V[] first=new V[sides],last=new V[sides];for(int i=0;i<sides;i++){first[i]=rings[0][sides-1-i];last[i]=rings[rings.length-1][i];}mesh.add(new Face(material,first));mesh.add(new Face(material,last));
    }
    private static void palmSurface(List<Face> mesh,V[] points,double[] widths){
        int count=25;V[] centers=new V[count];double[] radii=new double[count];for(int j=0;j<count;j++){double u=j/(double)(count-1)*(points.length-1);int i=Math.min(points.length-2,(int)u);double t=u-i;V a=points[i],b=points[i+1];centers[j]=a.mul(1-t).add(b.mul(t));double p0=widths[Math.max(0,i-1)],p1=widths[i],p2=widths[i+1],p3=widths[Math.min(widths.length-1,i+2)];radii[j]=.5*((2*p1)+(-p0+p2)*t+(2*p0-5*p1+4*p2-p3)*t*t+(-p0+3*p1-3*p2+p3)*t*t*t);}tube(mesh,centers,radii,0,.42);
    }
    private static double skinZ(double x,double y){
        double[] ys={.10,-.25,-.70,-1.12,-1.31},r={.32,.48,.66,.65,.57};int i=0;while(i<ys.length-2&&y<ys[i+1])i++;double t=Math.max(0,Math.min(1,(y-ys[i])/(ys[i+1]-ys[i]))),radius=r[i]*(1-t)+r[i+1]*t;
        double z=Math.min(.25,radius*.65)*Math.sqrt(Math.max(0,1-Math.pow((x-.015)/radius,2)));
        double thenar=1-Math.pow((x+.37)/.36,2)-Math.pow((y+.43)/.55,2);if(thenar>0)z=Math.max(z,.13+.22*Math.sqrt(thenar));double other=1-Math.pow((x-.35)/.27,2)-Math.pow((y+.43)/.56,2);if(other>0)z=Math.max(z,.07+.19*Math.sqrt(other));return z+.012;
    }
    private static void palmLine(List<Face> mesh,double x0,double y0,double cx,double cy,double x1,double y1,boolean back){
        V previous=null;for(int n=0;n<=14;n++){double t=n/14d,u=1-t,x=u*u*x0+2*u*t*cx+t*t*x1,y=u*u*y0+2*u*t*cy+t*t*y1,z=back?-skinZ(x,y):skinZ(x,y);V point=new V(x,y,z);if(previous!=null){V dir=point.sub(previous).unit(),offset=new V(-dir.y,dir.x,0).mul(back?.004:.003);int at=mesh.size();strip(mesh,previous,point,offset);if(back){Face f=mesh.remove(at);V[] p=f.p;for(int j=0;j<p.length/2;j++){V v=p[j];p[j]=p[p.length-1-j];p[p.length-1-j]=v;}mesh.add(new Face(2,p));}}previous=point;}
    }
    private static void ellipsoid(List<Face> mesh,V c,V radius,int material){
        V[] centers=new V[7];double[] radii=new double[7];for(int j=0;j<7;j++){double a=-Math.PI/2+j*Math.PI/6;centers[j]=c.add(new V(0,Math.sin(a)*radius.y,0));radii[j]=Math.max(.005,Math.cos(a)*radius.x);}tube(mesh,centers,radii,material,radius.z/radius.x);
    }
    private static void nail(List<Face> mesh,V a,V b,double width){
        V tangent=b.sub(a).unit(),side=new V(1,0,0).sub(tangent.mul(tangent.x)).unit(),back=tangent.cross(side).unit().mul(-1);
        V start=a.mul(.72).add(b.mul(.28)).add(back.mul(width*1.15)),end=a.mul(.12).add(b.mul(.88)).add(back.mul(width*.82));
        V[] p={start.sub(side.mul(width*.65)),start.add(side.mul(width*.65)),end.add(side.mul(width*.48)),end.sub(side.mul(width*.48))};if(p[1].sub(p[0]).cross(p[2].sub(p[0])).dot(back)<0){V v=p[0];p[0]=p[3];p[3]=v;v=p[1];p[1]=p[2];p[2]=v;}mesh.add(new Face(1,p));
    }
    private static void crease(List<Face> mesh,V center,V before,V after,double radius){
        V tangent=after.sub(before).unit(),side=new V(1,0,0).sub(tangent.mul(tangent.x)).unit(),front=tangent.cross(side).unit();V c=center.add(front.mul(radius*.84));
        V a=c.sub(side.mul(radius*.67)),b=c.add(side.mul(radius*.67));strip(mesh,a,b,tangent.mul(.014));
    }
    private static void line(List<Face> mesh,V...points){for(int i=1;i<points.length;i++){V direction=points[i].sub(points[i-1]).unit(),side=new V(-direction.y,direction.x,0).mul(.008);strip(mesh,points[i-1],points[i],side);}}
    private static void strip(List<Face> mesh,V a,V b,V offset){V[] p={a.sub(offset),b.sub(offset),b.add(offset),a.add(offset)};if(p[1].sub(p[0]).cross(p[2].sub(p[0])).z<0){V v=p[0];p[0]=p[3];p[3]=v;v=p[1];p[1]=p[2];p[2]=v;}mesh.add(new Face(2,p));}
}
