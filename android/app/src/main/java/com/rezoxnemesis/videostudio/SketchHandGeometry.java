package com.rezoxnemesis.videostudio;

import android.graphics.*;
import com.github.micycle1.clipper2.core.*;
import com.github.micycle1.clipper2.engine.Clipper64;
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
        for(int d=0;d<4;d++){out[d][0]=new V(xs[d],ys[d],.02);double angle=0,fan=Math.toRadians(fans[d]*pose[6]);for(int j=0;j<3;j++){angle+=SketchFingerJointAngles.increment(pose,d,j);V direction=new V(Math.sin(fan)*Math.cos(angle),-Math.cos(fan)*Math.cos(angle),Math.sin(angle));out[d][j+1]=out[d][j].add(direction.mul(LENGTHS[d][j]));}}
        out[4][0]=new V(-.23,-.18,.12);double opposition=pose[5],fan=Math.toRadians(-56+75*opposition),angle=Math.toRadians(10+32*opposition);for(int j=0;j<3;j++){angle+=Math.toRadians(new double[]{12,48,55}[j]*pose[4]);V direction=new V(Math.sin(fan)*Math.cos(angle),-Math.cos(fan)*Math.cos(angle),Math.sin(angle));out[4][j+1]=out[4][j].add(direction.mul(LENGTHS[4][j]));}return out;
    }
    static final class Ink {final Path path;final String color;final boolean fill;JSONObject gradient;
        Ink(Path p,String c,boolean f){path=p;color=c;fill=f;}}
    static final class Face {final V[] p;final int material;V[] normals;int owner=-1;Face(int m,V...p){this.p=p;material=m;}}
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
        String style=settings.optString("renderStyle","smooth");
        if(style.equals("cel"))return drawCel(settings,pose,width,height);
        if(!style.equals("smooth"))throw SketchDocument.bad("Unknown hand render style");
        return drawSmooth(settings,pose,width,height);
    }
    /** The original smooth algorithm is deliberately unchanged for saved artwork. */
    private static List<Ink> drawSmooth(JSONObject settings,double[] pose,int width,int height)throws Exception {
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
                // Skia can reject a valid near-coincident clipped triangle at
                // intermediate smooth poses. Preserve its successful legacy
                // result, and retry only failed intersections with the same
                // bounded native vector Boolean used by the cel renderer.
                Path clipped=new Path(triangle);
                if(clipped.op(visible,Path.Op.INTERSECT))triangle.set(clipped);
                else celOp(triangle,visible,Path.Op.INTERSECT);
                if(triangle.isEmpty())continue;
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
    /** Cel geometry keeps the exact joint chains/cameras. Owners are palm=0,
     * fingers=1..5, forearm=6; detail masks use actual visible owner skin. */
    private static List<Face> celSurfaces(JSONObject settings,double[] pose)throws Exception {
        List<Face> arm=new ArrayList<>(),mesh=new ArrayList<>();V[][] chain=joints(pose);double[] radii={.155,.165,.15,.128,.19};
        V[] armRings=new V[14];double[] armRadii=new double[14];armRings[0]=new V(0,2.08,-.03);armRadii[0]=.56;armRings[1]=new V(0,1.35,0);armRadii[1]=.47;
        // Subdivide the existing straight root edges, without reshaping them:
        // sparse long quads otherwise turn the field's fade into large diamonds.
        for(int i=2;i<14;i++){double y=(13-i)*.10;armRings[i]=new V(0,y,0);armRadii[i]=y<=.4?.33+.01*y/.4:.34+.13*(y-.4)/.95;}
        tube(arm,armRings,armRadii,0,.60);celOwner(arm,0,6);
        palmSurface(mesh,new V[]{new V(0,.10,0),new V(0,-.25,0),new V(.02,-.70,0),new V(.02,-1.12,0),new V(.02,-1.31,0)},new double[]{.32,.48,.66,.65,.57});celKnuckleOverlap(mesh);celOwner(mesh,0,0);
        int at=mesh.size();celPad(mesh,new V(-.37,-.43,.13),new V(.36,.55,.22));celPad(mesh,new V(.35,-.43,.07),new V(.27,.56,.19));celOwner(mesh,at,0);
        for(int d=0;d<5;d++){
            // Same shaft rings/lengths, followed by an elliptical terminal cap.
            // More cap samples round the ink without moving any distal joint.
            V[] centers=new V[14];double[] r=new double[14];centers[0]=chain[d][0].add(new V(0,.27,0));r[0]=radii[d]*1.12;centers[1]=chain[d][0];r[1]=radii[d];
            // Bury the thumb's construction cap inside the wrist volume. Its
            // old .213-radius cap projected beyond the heel as a flat ledge.
            // All articulated joint centres and distal contact surfaces stay fixed.
            if(d==4){centers[0]=chain[d][0].add(new V(.13,.26,-.08));r[0]=radii[d]*.78;}
            for(int j=0;j<3;j++){centers[j*2+2]=chain[d][j].mul(.08).add(chain[d][j+1].mul(.92));centers[j*2+3]=chain[d][j+1];r[j*2+2]=radii[d]*(1-j*.16);r[j*2+3]=radii[d]*(j==2?.46:1.03-j*.17);}
            V tangent=chain[d][3].sub(chain[d][2]).unit();
            r[7]=radii[d]*.68;
            for(int j=1;j<=6;j++){double angle=j*Math.PI/12;centers[7+j]=chain[d][3].add(tangent.mul(radii[d]*.50*Math.sin(angle)));r[7+j]=j==6?0:radii[d]*.68*Math.cos(angle);}
            at=mesh.size();tube(mesh,centers,r,0,.82);celOwner(mesh,at,d+1);
            if(settings.getString("detail").equals("anatomical")){
                at=mesh.size();celNail(mesh,chain[d][2],chain[d][3],radii[d]);celOwner(mesh,at,d+1);
                for(int j=1;j<3;j++){at=mesh.size();celCrease(mesh,chain[d][j],chain[d][j-1],chain[d][j+1],radii[d]*(1-(j-1)*.16));celOwner(mesh,at,d+1);}
            }
        }
        if(settings.getString("detail").equals("anatomical")){
            at=mesh.size();
            celPalmLine(mesh,-.48,-.98,-.18,-.85,.30,-.86,false);
            celPalmLine(mesh,.44,-.65,.06,-.53,-.30,-.34,false);
            celPalmLine(mesh,-.43,-.94,-.17,-.53,-.24,-.14,false);
            for(int d=0;d<3;d++)celPalmLine(mesh,chain[d][0].x,chain[d][0].y+.11,chain[d][0].x*.6,-.61,chain[d][0].x*.25,-.12,true);
            celOwner(mesh,at,0);
        }
        arm=celPolygons(arm);mesh=celPolygons(mesh);smoothNormals(arm);smoothNormals(mesh);
        List<Face> out=new ArrayList<>();
        for(Face face:arm)out.add(celCamera(face,settings,pose,false,chain));
        for(Face face:mesh)out.add(celCamera(face,settings,pose,true,chain));return out;
    }
    private static void celOwner(List<Face> faces,int start,int owner){for(int i=start;i<faces.size();i++){Face face=faces.get(i);face.owner=owner;face.normals=null;}}
    /** Blend both sides of the distal palm into the thinner finger roots.
     * Only y < -1 changes: central palm/pads, widths, joints and every finger
     * contact surface remain fixed. This removes the exposed palmar cap shelf
     * as well as its dorsal counterpart, without closing any finger gap. */
    private static void celKnuckleOverlap(List<Face> palm){
        Map<V,V> shared=new IdentityHashMap<>();for(Face face:palm)for(int i=0;i<face.p.length;i++){
            V p=face.p[i],next=shared.get(p);if(next==null){double t=Math.max(0,Math.min(1,(-p.y-1.0)/.31));t=t*t*(3-2*t);next=t>0?new V(p.x,p.y,p.z*(1-.52*t)):p;shared.put(p,next);}face.p[i]=next;
        }
    }
    /** Zero-radius rings contain repeated positions. Normalize them before
     * camera handedness reversal, otherwise a valid cap can start with two
     * identical vertices and be falsely culled as a degenerate polygon. */
    private static List<Face> celPolygons(List<Face> input){
        List<Face> out=new ArrayList<>();for(Face face:input){List<V> p=new ArrayList<>();for(V v:face.p)if(p.isEmpty()||v.sub(p.get(p.size()-1)).length()>1e-10)p.add(v);
            if(p.size()>1&&p.get(0).sub(p.get(p.size()-1)).length()<=1e-10)p.remove(p.size()-1);if(p.size()<3)continue;
            V area=new V(0,0,0);for(int t=1;t<p.size()-1;t++)area=area.add(p.get(t).sub(p.get(0)).cross(p.get(t+1).sub(p.get(0))));if(area.length()<1e-12)continue;
            Face clean=new Face(face.material,p.toArray(new V[0]));clean.owner=face.owner;out.add(clean);
        }return out;
    }
    private static double celSkinZ(List<Face> mesh,double x,double y,boolean back){
        double best=back?Double.POSITIVE_INFINITY:Double.NEGATIVE_INFINITY;
        for(Face face:mesh)if(face.material==0&&face.owner==0)for(int t=1;t<face.p.length-1;t++){
            V a=face.p[0],b=face.p[t],c=face.p[t+1];double det=(b.y-c.y)*(a.x-c.x)+(c.x-b.x)*(a.y-c.y);if(Math.abs(det)<1e-12)continue;
            double u=((b.y-c.y)*(x-c.x)+(c.x-b.x)*(y-c.y))/det,v=((c.y-a.y)*(x-c.x)+(a.x-c.x)*(y-c.y))/det;if(u>=0&&v>=0&&u+v<=1){double z=u*a.z+v*b.z+(1-u-v)*c.z;best=back?Math.min(best,z):Math.max(best,z);}
        }return best;
    }
    /** Seat the whole strip on the visible piecewise-linear skin envelope.
     * Lifting only its corners leaves a chord hovering over, or cutting through,
     * pad/facet crossings. Each convex fragment now belongs to one actual skin
     * triangle, including depth crossings between overlapping palm and pads. */
    private static void celPalmLine(List<Face> mesh,double x0,double y0,double cx,double cy,double x1,double y1,boolean back){
        List<CelTriangle> skin=new ArrayList<>();double sign=back?-1:1;
        for(Face face:mesh)if(face.material==0&&face.owner==0)for(int t=1;t<face.p.length-1;t++){
            CelTriangle tri=new CelTriangle(face.p[0],face.p[t],face.p[t+1]);
            if(tri.b.sub(tri.a).cross(tri.c.sub(tri.a)).z*sign>1e-12)skin.add(tri);
        }
        V previous=null;for(int i=0;i<=32;i++){double t=i/32d,u=1-t;V point=new V(u*u*x0+2*u*t*cx+t*t*x1,u*u*y0+2*u*t*cy+t*t*y1,0);
            if(previous!=null){V dir=point.sub(previous).unit(),offset=new V(-dir.y,dir.x,0).mul(back?.004:.003);
                List<V> strip=Arrays.asList(previous.sub(offset),point.sub(offset),point.add(offset),previous.add(offset));
                double left=Double.POSITIVE_INFINITY,right=-left,top=left,bottom=-left;for(V p:strip){left=Math.min(left,p.x);right=Math.max(right,p.x);top=Math.min(top,p.y);bottom=Math.max(bottom,p.y);}
                List<CelTriangle> nearby=new ArrayList<>();for(CelTriangle tri:skin)if(tri.right>=left&&tri.left<=right&&tri.bottom>=top&&tri.top<=bottom)nearby.add(tri);
                for(CelTriangle support:nearby){List<V> clipped=celClipTriangle(strip,support);if(clipped.size()<3)continue;List<List<V>> pieces=new ArrayList<>();pieces.add(clipped);double[] plane=celPlane(support);
                    for(CelTriangle other:nearby)if(other!=support&&!pieces.isEmpty()){
                        double[] above=celPlane(other);List<V> cutter=celClipXY(Arrays.asList(other.a,other.b,other.c),sign*(above[0]-plane[0]),sign*(above[1]-plane[1]),sign*(above[2]-plane[2])-1e-10);
                        if(cutter.size()<3)continue;List<List<V>> remaining=new ArrayList<>();for(List<V> piece:pieces)remaining.addAll(celSubtractConvex(piece,cutter));pieces=remaining;
                    }
                    for(List<V> piece:pieces)if(Math.abs(celArea(piece))>1e-12){if((celArea(piece)>0)!=(!back))Collections.reverse(piece);V[] p=new V[piece.size()];for(int j=0;j<p.length;j++){V v=piece.get(j);p[j]=new V(v.x,v.y,plane[0]*v.x+plane[1]*v.y+plane[2]+sign*.004);}mesh.add(new Face(2,p));}
                }
            }previous=point;
        }
    }
    private static double[] celPlane(CelTriangle tri){V n=tri.b.sub(tri.a).cross(tri.c.sub(tri.a));double x=-n.x/n.z,y=-n.y/n.z;return new double[]{x,y,tri.a.z-x*tri.a.x-y*tri.a.y};}
    private static double celArea(List<V> p){double area=0;for(int i=0;i<p.size();i++){V a=p.get(i),b=p.get((i+1)%p.size());area+=a.x*b.y-b.x*a.y;}return area;}
    private static List<V> celClipXY(List<V> p,double x,double y,double bias){
        List<V> out=new ArrayList<>();if(p.isEmpty())return out;V a=p.get(p.size()-1);double da=x*a.x+y*a.y+bias;
        for(V b:p){double db=x*b.x+y*b.y+bias;if((da>=0)!=(db>=0)){double t=da/(da-db);out.add(a.mul(1-t).add(b.mul(t)));}if(db>=0)out.add(b);a=b;da=db;}return out;
    }
    private static List<V> celClipTriangle(List<V> polygon,CelTriangle tri){
        List<V> edge=Arrays.asList(tri.a,tri.b,tri.c);double sign=Math.signum(celArea(edge));List<V> out=polygon;
        for(int i=0;i<3;i++){V a=edge.get(i),b=edge.get((i+1)%3);out=celClipXY(out,-sign*(b.y-a.y),sign*(b.x-a.x),sign*((b.y-a.y)*a.x-(b.x-a.x)*a.y));}return out;
    }
    private static List<List<V>> celSubtractConvex(List<V> polygon,List<V> cutter){
        List<List<V>> out=new ArrayList<>();double sign=Math.signum(celArea(cutter));List<V> rest=polygon;
        for(int i=0;i<cutter.size()&&!rest.isEmpty();i++){V a=cutter.get(i),b=cutter.get((i+1)%cutter.size());double x=-sign*(b.y-a.y),y=sign*(b.x-a.x),bias=-x*a.x-y*a.y;
            List<V> outside=celClipXY(rest,-x,-y,-bias);if(outside.size()>=3&&Math.abs(celArea(outside))>1e-12)out.add(outside);rest=celClipXY(rest,x,y,bias);
        }return out;
    }
    private static V celPalmNormal(V p){
        // A continuous positional field, including z=0: component/cap normals
        // must never choose the sign of a shared palm or wrist lighting region.
        return new V(p.x/.70,(p.y+.6)*.02-.10,p.z/.26).unit();
    }
    private static Face celCamera(Face face,JSONObject settings,double[] pose,boolean hand,V[][] chain)throws Exception {
        Face result=camera(face,settings,pose,hand);result.owner=face.owner;
        if(face.material!=0)return result;
        boolean mirrored=settings.getString("handedness").equals("left");
        for(int i=0;i<face.p.length;i++){
            V world=hand?rotate(face.p[i],pose[7],pose[8]):face.p[i];
            // Arm geometry is fixed. Sample it in inverse wrist coordinates so
            // intersecting arm/palm surfaces share one posed illustration field.
            V local=rotate(rotate(world,0,-pose[8]),-pose[7],0);
            V normal=rotate(celPalmNormal(local),pose[7],pose[8]);
            if(mirrored)normal=new V(-normal.x,normal.y,normal.z);
            normal=viewPoint(normal,settings.getString("view"));
            // The thumb tube's buried root/cap overlaps the heel as well; its
            // independent cap lighting otherwise leaves small closed shards.
            double blend;
            if(face.owner>=1&&face.owner<=4){int d=face.owner-1;double along=local.sub(chain[d][0]).dot(chain[d][1].sub(chain[d][0]).unit());
                // The palmar transition reveals buried construction roots. Use
                // the same positional field there, fading over only .28 model
                // units of the proximal shaft; all further shaft ink stays exact.
                blend=Math.max(0,Math.min(1,1-along/.28));
            }else blend=face.owner==0?1:face.owner==5?Math.max(0,Math.min(1,(local.y+.70)/.40)):Math.max(0,Math.min(1,(1.10-world.y)/.60));
            blend=blend*blend*(3-2*blend);if(blend==0)continue;
            int index=mirrored?face.p.length-1-i:i;
            result.normals[index]=result.normals[index].mul(1-blend).add(normal.mul(blend)).unit();
        }return result;
    }
    private static void celPad(List<Face> mesh,V center,V radius){
        V[] points=new V[25];double[] widths=new double[25];for(int i=0;i<points.length;i++){double a=-Math.PI/2+i*Math.PI/24;points[i]=center.add(new V(0,Math.sin(a)*radius.y,0));widths[i]=i==0||i==24?0:Math.cos(a)*radius.x;}tube(mesh,points,widths,0,radius.z/radius.x);
    }
    /** Rounded shield-shaped nail seated on the actual distal tube cross-section. */
    private static void celNail(List<Face> mesh,V a,V b,double radius){
        V tangent=b.sub(a).unit(),side=new V(1,0,0).sub(tangent.mul(tangent.x)).unit(),back=tangent.cross(side).unit().mul(-1);int count=24;
        V[] edge=new V[count];for(int i=0;i<count;i++){double angle=2*Math.PI*i/count;edge[i]=celNailPoint(a,b,side,back,radius,.56+.30*Math.sin(angle),radius*.46*Math.cos(angle));}
        V center=celNailPoint(a,b,side,back,radius,.56,0);
        for(int i=0;i<count;i++){V p=edge[i],q=edge[(i+1)%count];if(p.sub(center).cross(q.sub(center)).dot(back)<0){V swap=p;p=q;q=swap;}mesh.add(new Face(1,center,p,q));}
    }
    private static V celNailPoint(V a,V b,V side,V back,double base,double u,double lateral){
        double radius=base*(.86+(.68-.86)*Math.min(.92,u)/.92),height=radius*.82*Math.sqrt(Math.max(0,1-lateral*lateral/(radius*radius)))+.010;
        return a.mul(1-u).add(b.mul(u)).add(side.mul(lateral)).add(back.mul(height));
    }
    private static void celCrease(List<Face> mesh,V center,V before,V after,double radius){
        V tangent=after.sub(before).unit(),side=new V(1,0,0).sub(tangent.mul(tangent.x)).unit(),front=tangent.cross(side).unit(),previous=null;
        for(int i=0;i<=12;i++){
            double u=-.68+1.36*i/12;V point=center.add(side.mul(u*radius)).add(front.mul(radius*.84*Math.sqrt(1-u*u)+.006)).add(tangent.mul(radius*.03*(1-u*u)));
            if(previous!=null){V offset=tangent.mul(.0045);V[] p={previous.sub(offset),point.sub(offset),point.add(offset),previous.add(offset)};if(p[1].sub(p[0]).cross(p[2].sub(p[0])).dot(front)<0){V swap=p[0];p[0]=p[3];p[3]=swap;swap=p[1];p[1]=p[2];p[2]=swap;}mesh.add(new Face(2,p));}previous=point;
        }
    }
    private static final class Lit {
        final double x,y,light;Lit(double x,double y,double light){this.x=x;this.y=y;this.light=light;}
    }
    private static List<Lit> celLevel(List<Lit> polygon,double level,boolean above){
        List<Lit> out=new ArrayList<>();if(polygon.isEmpty())return out;Lit a=polygon.get(polygon.size()-1);boolean aIn=above?a.light>=level:a.light<=level;
        for(Lit b:polygon){boolean bIn=above?b.light>=level:b.light<=level;
            if(aIn!=bIn){double t=(level-a.light)/(b.light-a.light);out.add(new Lit(a.x+(b.x-a.x)*t,a.y+(b.y-a.y)*t,level));}
            if(bIn)out.add(b);a=b;aIn=bIn;
        }return out;
    }
    private static Path celPolygon(List<Lit> points){Path p=new Path();if(points.size()<3)return p;for(int i=0;i<points.size();i++){Lit v=points.get(i);if(i==0)p.moveTo((float)v.x,(float)v.y);else p.lineTo((float)v.x,(float)v.y);}p.close();return p;}
    private static Path celProjection(Face face,double unit,double cx,double cy){Path p=new Path();for(int i=0;i<face.p.length;i++){V v=face.p[i];if(i==0)p.moveTo((float)(cx+v.x*unit),(float)(cy+v.y*unit));else p.lineTo((float)(cx+v.x*unit),(float)(cy+v.y*unit));}p.close();return p;}
    private static boolean celFront(Face face){return face.p[1].sub(face.p[0]).cross(face.p[2].sub(face.p[0])).unit().z>.00001;}
    /** Failure-only reuse for other fixed, app-owned construction surfaces. Keep
     * successful Skia paths intact; this bounded vector backend already preserves
     * native hand intersections below the existing contour conversion tolerance. */
    static void retryVectorBoolean(Path target,Path other,Path.Op op)throws Exception {celOp(target,other,op);}
    /** Robust cel vector Booleans. Native curves are sampled below the existing
     * .001px conversion tolerance; fixed micro-pixel integers preserve narrow
     * guards and topology without depending on document-coordinate float ops. */
    private static void celOp(Path target,Path other,Path.Op op)throws Exception {
        Paths64 a=celPolygons(target),b=celPolygons(other),solution=new Paths64();
        Clipper64 clipper=new Clipper64();boolean reverse=op==Path.Op.REVERSE_DIFFERENCE;
        clipper.addSubject(reverse?b:a);clipper.addClip(reverse?a:b);
        ClipType operation=op==Path.Op.UNION?ClipType.Union:op==Path.Op.INTERSECT?ClipType.Intersection:op==Path.Op.XOR?ClipType.Xor:ClipType.Difference;
        if(!clipper.execute(operation,FillRule.NonZero,solution))throw SketchDocument.bad("Native cel-hand vector Boolean failed");
        Path result=new Path();for(Path64 polygon:solution){if(polygon.size()<3)continue;for(int i=0;i<polygon.size();i++){Point64 point=polygon.get(i);if(i==0)result.moveTo((float)(point.x/1000000d),(float)(point.y/1000000d));else result.lineTo((float)(point.x/1000000d),(float)(point.y/1000000d));}result.close();}celBooleanBounds(target,other,result,op);target.set(result);
    }
    /** A successful operation may never expand beyond its mathematical bounds.
     * Allow only one native float ULP plus integer-grid rounding, not art-scale
     * slack. Independent appearance/ray tests also check missing coverage. */
    private static void celBooleanBounds(Path a,Path b,Path result,Path.Op op)throws Exception {
        if(result.isEmpty())return;RectF left=new RectF(),right=new RectF(),actual=new RectF();a.computeBounds(left,true);b.computeBounds(right,true);result.computeBounds(actual,true);
        RectF expected=new RectF(op==Path.Op.REVERSE_DIFFERENCE?right:left);
        if(op==Path.Op.INTERSECT&&!expected.intersect(right))throw SketchDocument.bad("Native cel-hand intersection exceeded its support");
        if(op==Path.Op.UNION||op==Path.Op.XOR)expected.union(right);
        float magnitude=Math.max(Math.max(Math.abs(expected.left),Math.abs(expected.right)),Math.max(Math.abs(expected.top),Math.abs(expected.bottom))),rounding=Math.ulp(magnitude)+.000001f;
        if(actual.left<expected.left-rounding||actual.top<expected.top-rounding||actual.right>expected.right+rounding||actual.bottom>expected.bottom+rounding)throw SketchDocument.bad("Native cel-hand Boolean exceeded its support");
    }
    private static Paths64 celPolygons(Path source)throws Exception {
        if(source.isInverseFillType())throw SketchDocument.bad("Inverse cel ink is unsupported");
        Paths64 paths=new Paths64();PathMeasure measure=new PathMeasure(source,false);
        do{Path contour=new Path();if(!measure.getSegment(0,measure.getLength(),contour,true))continue;contour.close();float[] points=contour.approximate(.0001f);Path64 polygon=new Path64();
            for(int i=0;i<points.length;i+=3){long x=Math.round(points[i+1]*1000000d),y=Math.round(points[i+2]*1000000d);if(polygon.isEmpty()||polygon.get(polygon.size()-1).x!=x||polygon.get(polygon.size()-1).y!=y)polygon.add(new Point64(x,y));}
            if(polygon.size()>1&&polygon.get(0).equals(polygon.get(polygon.size()-1)))polygon.remove(polygon.size()-1);if(polygon.size()>=3)paths.add(polygon);
        }while(measure.nextContour());
        if(source.getFillType()!=Path.FillType.EVEN_ODD)return paths;
        Clipper64 normalize=new Clipper64();normalize.addSubject(paths);Paths64 winding=new Paths64();if(!normalize.execute(ClipType.Union,FillRule.EvenOdd,winding))throw SketchDocument.bad("Native cel-hand fill normalization failed");return winding;
    }
    /** Pairwise unions avoid repeatedly intersecting a tiny sliver with a large
     * accumulated contour, and keep the new style practical for native preview. */
    private static Path celUnion(List<Path> paths)throws Exception {
        if(paths.isEmpty())return new Path();List<Path> current=paths;
        while(current.size()>1){List<Path> next=new ArrayList<>();for(int i=0;i<current.size();i+=2){Path p=new Path(current.get(i));if(i+1<current.size())celOp(p,current.get(i+1),Path.Op.UNION);next.add(p);}current=next;}return current.get(0);
    }
    private static Path celInterior(Path skin,double guard)throws Exception {
        Paint edge=new Paint();edge.setStyle(Paint.Style.STROKE);edge.setStrokeWidth((float)(guard*2));edge.setStrokeJoin(Paint.Join.ROUND);Path border=new Path();edge.getFillPath(skin,border);Path inside=new Path(skin);celOp(inside,border,Path.Op.DIFFERENCE);return inside;
    }
    private static final class SkinContour {
        final Path path;final List<V> points=new ArrayList<>();final RectF box=new RectF();
        final double area2,perimeter;double ulpX,ulpY;
        SkinContour(Path path,double perimeter){
            this.path=path;this.perimeter=perimeter;path.computeBounds(box,true);float[] a=path.approximate(.0001f);
            for(int i=0;i<a.length;i+=3){V p=new V(a[i+1],a[i+2],0);if(points.isEmpty()||p.x!=points.get(points.size()-1).x||p.y!=points.get(points.size()-1).y)points.add(p);ulpX=Math.max(ulpX,Math.ulp(a[i+1]));ulpY=Math.max(ulpY,Math.ulp(a[i+2]));}
            if(points.size()>1&&points.get(0).x==points.get(points.size()-1).x&&points.get(0).y==points.get(points.size()-1).y)points.remove(points.size()-1);
            double sum=0;if(!points.isEmpty()){V origin=points.get(0);for(int i=1;i+1<points.size();i++)sum+=skinCross(origin,points.get(i),points.get(i+1));}area2=sum;
        }
    }
    private static double skinCross(V a,V b,V c){return (b.x-a.x)*(c.y-a.y)-(b.y-a.y)*(c.x-a.x);}
    /** Float projection can leave a sub-ULP closed gap along a shared skin
     * edge. Its fill is negligible but stroking that hole draws a full dark
     * line. Canonicalize only unresolved INTERNAL holes before any insets or
     * detail clipping. Outer boundaries, open gaps and nested islands survive.
     * Both average width and the entire convex-hull strip must fit within one
     * directional float spacing plus the Boolean's one-micropixel grid, capped
     * below the ordinary cel export approximation. No geometry is displaced. */
    private static Path celSkinCoverage(Path source){
        List<SkinContour> contours=new ArrayList<>();PathMeasure measure=new PathMeasure(source,false);
        do{Path path=new Path();if(measure.getSegment(0,measure.getLength(),path,true)){path.close();contours.add(new SkinContour(path,measure.getLength()));}}while(measure.nextContour());
        boolean[] remove=new boolean[contours.size()];boolean changed=false;
        for(int i=0;i<contours.size();i++){
            SkinContour candidate=contours.get(i);if(candidate.points.isEmpty()||candidate.perimeter==0||!skinUnresolvedStrip(candidate))continue;
            int depth=0;double parentArea=Double.POSITIVE_INFINITY,parentSign=0;boolean protectedBoundary=false;
            for(int j=0;j<contours.size();j++)if(i!=j){
                SkinContour other=contours.get(j);if(other.points.isEmpty())continue;
                if(candidate.box.right<other.box.left||candidate.box.left>other.box.right||candidate.box.bottom<other.box.top||candidate.box.top>other.box.bottom)continue;
                // Touching/crossing contours are ambiguous, not closed holes.
                for(int a=0;a<candidate.points.size()&&!protectedBoundary;a++)for(int b=0;b<other.points.size();b++)if(skinSegmentsMeet(candidate.points.get(a),candidate.points.get((a+1)%candidate.points.size()),other.points.get(b),other.points.get((b+1)%other.points.size()))){protectedBoundary=true;break;}
                if(protectedBoundary)break;
                // Any nested contour, including a minute skin island, protects
                // its parent hole. Use exact segment/ray signs, no edge epsilon.
                for(V p:other.points)if(skinLocation(candidate.points,p)!=0){protectedBoundary=true;break;}
                if(protectedBoundary)break;
                if(other.area2!=0&&skinLocation(other.points,candidate.points.get(0))==1){depth++;if(Math.abs(other.area2)<parentArea){parentArea=Math.abs(other.area2);parentSign=Math.signum(other.area2);}}
            }
            boolean holeWinding=source.getFillType()==Path.FillType.EVEN_ODD||candidate.area2==0||Math.signum(candidate.area2)!=parentSign;
            if(!protectedBoundary&&(depth&1)==1&&holeWinding){remove[i]=true;changed=true;}
        }
        if(!changed)return source;Path result=new Path();result.setFillType(source.getFillType());for(int i=0;i<contours.size();i++)if(!remove[i])result.addPath(contours.get(i).path);return result;
    }
    private static boolean skinUnresolvedStrip(SkinContour contour){
        if(Math.abs(contour.area2)/contour.perimeter>=.00025)return false;
        List<V> sorted=new ArrayList<>(contour.points);sorted.sort((a,b)->a.x==b.x?Double.compare(a.y,b.y):Double.compare(a.x,b.x));List<V> hull=new ArrayList<>();
        for(V p:sorted){while(hull.size()>1&&skinCross(hull.get(hull.size()-2),hull.get(hull.size()-1),p)<=0)hull.remove(hull.size()-1);hull.add(p);}int lower=hull.size();
        for(int i=sorted.size()-2;i>=0;i--){V p=sorted.get(i);while(hull.size()>lower&&skinCross(hull.get(hull.size()-2),hull.get(hull.size()-1),p)<=0)hull.remove(hull.size()-1);hull.add(p);}if(hull.size()>1)hull.remove(hull.size()-1);
        if(hull.size()<2)return true;double width=Double.POSITIVE_INFINITY,precision=0;
        for(int i=0;i<hull.size();i++){V a=hull.get(i),b=hull.get((i+1)%hull.size());double dx=b.x-a.x,dy=b.y-a.y,length=Math.hypot(dx,dy);if(length==0)continue;double maximum=0;for(V p:hull)maximum=Math.max(maximum,Math.abs(skinCross(a,b,p))/length);
            if(maximum<width){width=maximum;precision=Math.min(.00025,(Math.abs(dy)*contour.ulpX+Math.abs(dx)*contour.ulpY)/length+.000001);}
        }
        return width<=precision&&Math.abs(contour.area2)/contour.perimeter<=precision;
    }
    // 1 inside, 0 outside, -1 on the boundary. Float vertices are evaluated in
    // local double differences; no global-coordinate near-edge probe is used.
    private static int skinLocation(List<V> polygon,V p){boolean inside=false;for(int i=0,j=polygon.size()-1;i<polygon.size();j=i++){
        V a=polygon.get(j),b=polygon.get(i);if(skinCross(a,b,p)==0&&p.x>=Math.min(a.x,b.x)&&p.x<=Math.max(a.x,b.x)&&p.y>=Math.min(a.y,b.y)&&p.y<=Math.max(a.y,b.y))return -1;
        if((a.y>p.y)!=(b.y>p.y)&&p.x-a.x<(b.x-a.x)*(p.y-a.y)/(b.y-a.y))inside=!inside;
    }return inside?1:0;}
    private static boolean skinSegmentsMeet(V a,V b,V c,V d){
        if(Math.max(a.x,b.x)<Math.min(c.x,d.x)||Math.min(a.x,b.x)>Math.max(c.x,d.x)||Math.max(a.y,b.y)<Math.min(c.y,d.y)||Math.min(a.y,b.y)>Math.max(c.y,d.y))return false;
        double ac=skinCross(a,b,c),ad=skinCross(a,b,d),ca=skinCross(c,d,a),cb=skinCross(c,d,b);return (ac==0||ad==0||Math.signum(ac)!=Math.signum(ad))&&(ca==0||cb==0||Math.signum(ca)!=Math.signum(cb));
    }
    /** Suppress only detached marks too small to read as a crease. This runs
     * after exact seating and depth clipping. Native difference preserves every
     * retained boundary/occluded gap; it never bridges or fills missing ink.
     * Thresholds are in model units, identical at preview and export sizes. */
    private static Path celCreasesWithoutSpecks(Path source,double unit)throws Exception {
        // Subtracting a small boundary contour can disconnect another short
        // branch. Revisit the native result until no eligible island remains;
        // the span/perimeter limits and hole-preserving difference stay exact.
        // Bound work by the source vertex budget and reject non-convergence.
        Path current=source;
        for(int budget=source.approximate(.001f).length;budget>0;budget--){
            Path next=celCreasePass(current,unit);if(next==current)return current;current=next;
        }
        throw SketchDocument.bad("Native cel-hand crease cleanup did not converge");
    }
    private static Path celCreasePass(Path source,double unit)throws Exception {
        Path removed=new Path();PathMeasure measure=new PathMeasure(source,false);
        do{if(measure.getLength()>unit*.065)continue;Path part=new Path();measure.getSegment(0,measure.getLength(),part,true);part.close();RectF box=new RectF();part.computeBounds(box,true);
            if(Math.max(box.width(),box.height())>unit*.028)continue;
            // A tiny hole contour is not painted ink and must stay a hole.
            celOp(part,source,Path.Op.INTERSECT);if(!part.isEmpty())celOp(removed,part,Path.Op.UNION);
        }while(measure.nextContour());
        if(removed.isEmpty())return source;Path result=new Path(source);celOp(result,removed,Path.Op.DIFFERENCE);return result;
    }
    /** Ordinary editable paths use winding fill. Path.op may return even-odd
     * compound regions with equally directed inner contours; exporting those
     * directly would paint over their cutouts. Orient only incompatible cel
     * contours. The .001px native approximation retains polygon vertices and
     * the tiny rounded numerical guards, rather than smoothing the silhouette. */
    private static Path celWinding(Path source)throws Exception {
        Path winding=new Path(source);winding.setFillType(Path.FillType.WINDING);Path difference=new Path(source);celOp(difference,winding,Path.Op.XOR);if(difference.isEmpty())return winding;
        List<List<V>> contours=new ArrayList<>();List<V> contour=null;float[] a=source.approximate(.001f);
        for(int i=0;i<a.length;i+=3){boolean start=i==0||a[i]==a[i-3]&&Math.hypot(a[i+1]-a[i-2],a[i+2]-a[i-1])>.0005;
            if(start){contour=new ArrayList<>();contours.add(contour);}V p=new V(a[i+1],a[i+2],0);if(contour.isEmpty()||p.sub(contour.get(contour.size()-1)).length()>1e-9)contour.add(p);
        }
        for(List<V> p:contours)if(p.size()>1&&p.get(0).sub(p.get(p.size()-1)).length()<1e-9)p.remove(p.size()-1);
        Path result=new Path();for(List<V> p:contours){if(p.size()<3)continue;double area=0,longest=-1;int edge=0;V origin=p.get(0);
            for(int i=0;i<p.size();i++){V v=p.get(i),q=p.get((i+1)%p.size());area+=(v.x-origin.x)*(q.y-origin.y)-(q.x-origin.x)*(v.y-origin.y);double length=q.sub(v).length();if(length>longest){longest=length;edge=i;}}
            if(Math.abs(area)<1e-10)continue;V v=p.get(edge),q=p.get((edge+1)%p.size());double sign=area>0?1:-1,epsilon=Math.min(.0001,longest*.001),x=(v.x+q.x)/2-sign*(q.y-v.y)/longest*epsilon,y=(v.y+q.y)/2+sign*(q.x-v.x)/longest*epsilon;int depth=0;
            for(List<V> other:contours)if(other!=p&&celContains(other,x,y))depth++;
            if((area>0)!=(depth%2==0))Collections.reverse(p);for(int i=0;i<p.size();i++){V point=p.get(i);if(i==0)result.moveTo((float)point.x,(float)point.y);else result.lineTo((float)point.x,(float)point.y);}result.close();
        }return result;
    }
    private static boolean celContains(List<V> contour,double x,double y){
        boolean inside=false;for(int i=0,j=contour.size()-1;i<contour.size();j=i++){V a=contour.get(i),b=contour.get(j);if((a.y>y)!=(b.y>y)&&x<(b.x-a.x)*(y-a.y)/(b.y-a.y)+a.x)inside=!inside;}return inside;
    }
    private static final class CelTriangle {
        final V a,b,c;final double left,right,top,bottom,near,far;
        CelTriangle(V a,V b,V c){this.a=a;this.b=b;this.c=c;left=Math.min(a.x,Math.min(b.x,c.x));right=Math.max(a.x,Math.max(b.x,c.x));top=Math.min(a.y,Math.min(b.y,c.y));bottom=Math.max(a.y,Math.max(b.y,c.y));near=Math.max(a.z,Math.max(b.z,c.z));far=Math.min(a.z,Math.min(b.z,c.z));}
    }
    /** Detail depth is compared at each projected position. Average face order
     * alone both exposes folded nails and cuts holes into seated visible nails. */
    private static Path celVisibleDetail(Face detail,List<CelTriangle> skin,double unit,double cx,double cy)throws Exception {
        List<Path> pieces=new ArrayList<>();
        for(int t=1;t<detail.p.length-1;t++){
            CelTriangle d=new CelTriangle(detail.p[0],detail.p[t],detail.p[t+1]);V normal=d.b.sub(d.a).cross(d.c.sub(d.a));if(Math.abs(normal.z)<1e-10)continue;
            double gx=-normal.x/normal.z,gy=-normal.y/normal.z;
            Path visible=celPolygon(Arrays.asList(new Lit(cx+d.a.x*unit,cy+d.a.y*unit,0),new Lit(cx+d.b.x*unit,cy+d.b.y*unit,0),new Lit(cx+d.c.x*unit,cy+d.c.y*unit,0)));
            for(CelTriangle s:skin){
                if(s.right<d.left||s.left>d.right||s.bottom<d.top||s.top>d.bottom||s.near<=d.far+.00005)continue;
                List<Lit> triangle=new ArrayList<>();for(V p:new V[]{s.a,s.b,s.c})triangle.add(new Lit(cx+p.x*unit,cy+p.y*unit,p.z-(d.a.z+gx*(p.x-d.a.x)+gy*(p.y-d.a.y))));
                Path front=celPolygon(celLevel(triangle,.00005,true));if(front.isEmpty())continue;celOp(visible,front,Path.Op.DIFFERENCE);if(visible.isEmpty())break;
            }
            if(!visible.isEmpty())pieces.add(visible);
        }return celUnion(pieces);
    }
    /** Resolve the complete skin first. Group membership never removes an occluder.
     * These are ownership classifiers, not newly drawn skin/detail/outline paths. */
    static Path[] visibleCelPartOwners(JSONObject settings,double[] pose,int width,int height)throws Exception {
        List<Face> faces=celSurfaces(settings,pose);faces.sort((a,b)->Double.compare(depth(b),depth(a)));
        double unit=Math.min(width,height)/720d*112*settings.getDouble("size"),cx=width*settings.getDouble("centerX"),cy=height*settings.getDouble("centerY");
        Path covered=new Path();List<Path> rear=new ArrayList<>(),digits=new ArrayList<>();
        for(Face face:faces){
            if(Thread.currentThread().isInterrupted())throw new InterruptedException("Hand ownership cancelled");
            if(face.material!=0||!celFront(face))continue;
            Path shape=celProjection(face,unit,cx,cy),visible=new Path(shape);celOp(visible,covered,Path.Op.DIFFERENCE);
            if(!visible.isEmpty())(face.owner==0||face.owner==6?rear:digits).add(visible);
            celOp(covered,shape,Path.Op.UNION);
        }
        return new Path[]{celUnion(rear),celUnion(digits)};
    }
    private static List<Ink> drawCel(JSONObject settings,double[] pose,int width,int height)throws Exception {
        List<Face> faces=celSurfaces(settings,pose);faces.sort((a,b)->Double.compare(depth(b),depth(a)));
        double unit=Math.min(width,height)/720d*112*settings.getDouble("size"),cx=width*settings.getDouble("centerX"),cy=height*settings.getDouble("centerY");
        Path skin=new Path(),nails=new Path(),creases=new Path();Path[] owners=new Path[7];List<List<Path>> ownerPieces=new ArrayList<>(),tonePieces=new ArrayList<>();for(int i=0;i<7;i++)ownerPieces.add(new ArrayList<>());for(int i=0;i<3;i++)tonePieces.add(new ArrayList<>());V light=new V(-.45,-.55,1).unit();
        for(Face face:faces){
            if(face.material!=0||!celFront(face))continue;
            Path shape=celProjection(face,unit,cx,cy),visible=new Path(shape);celOp(visible,skin,Path.Op.DIFFERENCE);
            if(!visible.isEmpty()){
                ownerPieces.get(face.owner).add(visible);
                for(int t=1;t<face.p.length-1;t++){
                    List<Lit> triangle=new ArrayList<>();for(int i:new int[]{0,t,t+1}){V v=face.p[i];triangle.add(new Lit(cx+v.x*unit,cy+v.y*unit,.64+.43*Math.max(0,face.normals[i].dot(light))));}
                    // Interpolate the lighting contour within the triangle. Choosing
                    // a bucket from its average would bake a checkerboard into ink.
                    for(int band:new int[]{0,2}){
                        List<Lit> fragment=triangle;if(band>0)fragment=celLevel(fragment,band==1?.84:.99,true);if(band<2)fragment=celLevel(fragment,band==0?.84:.99,false);
                        Path p=celPolygon(fragment);if(p.isEmpty())continue;celOp(p,visible,Path.Op.INTERSECT);if(!p.isEmpty())tonePieces.get(band).add(p);
                    }
                }
            }
            celOp(skin,shape,Path.Op.UNION);
        }
        // A tiny native vector inset prevents float-rounding remnants on shared
        // boundaries; at .03 reference pixels it stays beneath the contour ink.
        // Resolve holes at each of the two float representations rather than
        // increasing the tolerance to cover their accumulated rounding error.
        // Both passes precede every inset, detail clip and visible outline.
        skin=celSkinCoverage(celEditableCoordinates(celSkinCoverage(skin),width,height));
        double guard=.03*unit/112;Path interior=celInterior(skin,guard);for(int i=0;i<7;i++)owners[i]=celInterior(celSkinCoverage(celEditableCoordinates(celSkinCoverage(celUnion(ownerPieces.get(i))),width,height)),guard);
        // Skin alone controls the contour/ownership. A crease or nail cannot
        // widen it, float beside a palm, or reappear over an occluding finger.
        List<CelTriangle> skinTriangles=new ArrayList<>();for(Face face:faces)if(face.material==0)for(int t=1;t<face.p.length-1;t++)skinTriangles.add(new CelTriangle(face.p[0],face.p[t],face.p[t+1]));
        for(Face face:faces)if(face.material!=0&&celFront(face)){
            Path detail=celVisibleDetail(face,skinTriangles,unit,cx,cy);
            // Palm strips were split onto actual support facets. Exact depth
            // already establishes their visible support; an average-depth
            // owner mask would reintroduce false holes at pad/finger overlaps.
            // Nails and analytic finger creases keep their original owner mask.
            celOp(detail,face.material==2&&face.owner==0?interior:owners[face.owner],Path.Op.INTERSECT);celOp(face.material==1?nails:creases,detail,Path.Op.UNION);
        }
        String base=settings.getString("skinColor");List<Ink> out=new ArrayList<>();out.add(new Ink(skin,base,true));
        for(int band:new int[]{0,2})if(!tonePieces.get(band).isEmpty()){Path tone=celUnion(tonePieces.get(band));celOp(tone,interior,Path.Op.INTERSECT);out.add(new Ink(tone,SketchCharacterArt.tone(base,band==0?.83:1.07),true));}
        if(!nails.isEmpty()){celOp(nails,interior,Path.Op.INTERSECT);out.add(new Ink(nails,settings.getString("nailColor"),true));}
        if(!creases.isEmpty()){celOp(creases,interior,Path.Op.INTERSECT);creases=celCreasesWithoutSpecks(creases,unit);if(!creases.isEmpty())out.add(new Ink(creases,SketchCharacterArt.tone(base,.67),true));}
        out.add(new Ink(new Path(skin),SketchCharacterArt.tone(base,.52),false));for(Ink mark:out){if(mark.fill)mark.path.set(celWinding(mark.path));mark.path.set(celEditableCoordinates(mark.path,width,height));}return out;
    }
    /** The ordinary path reader multiplies normalized floats by canvas size.
     * Use that same representable coordinate grid for retained cel ink too. */
    private static Path celEditableCoordinates(Path source,int width,int height){
        Path result=new Path();result.setFillType(source.getFillType());PathMeasure measure=new PathMeasure(source,false);
        do{Path contour=new Path();if(!measure.getSegment(0,measure.getLength(),contour,true))continue;contour.close();float[] points=contour.approximate(.0001f);for(int i=0;i<points.length;i+=3){float x=points[i+1]/width*width,y=points[i+2]/height*height;if(i==0)result.moveTo(x,y);else result.lineTo(x,y);}result.close();}while(measure.nextContour());return result;
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
