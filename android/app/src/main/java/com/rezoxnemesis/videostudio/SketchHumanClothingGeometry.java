package com.rezoxnemesis.videostudio;

import java.util.ArrayList;
import java.util.List;

/** Bounded garment construction around solved joints. Never changes a joint or a clock. */
final class SketchHumanClothingGeometry {
    private SketchHumanClothingGeometry(){}

    static final class Curve {
        final double[] a,b,c,d;
        Curve(double[] a,double[] b,double[] c,double[] d){this.a=a;this.b=b;this.c=c;this.d=d;}
        void trace(JungleCelInk.Shape ink)throws Exception{ink.c(b[0],b[1],c[0],c[1],d[0],d[1]);}
        Curve reverse(){return new Curve(d,c,b,a);}
        Curve[] split(double t){double[] ab=mix(a,b,t),bc=mix(b,c,t),cd=mix(c,d,t),abc=mix(ab,bc,t),bcd=mix(bc,cd,t),point=mix(abc,bcd,t);return new Curve[]{new Curve(a,ab,abc,point),new Curve(point,bcd,cd,d)};}
        Curve section(double start,double end){if(start==0&&end==1)return this;Curve prefix=end==1?this:split(end)[0];return start==0?prefix:prefix.split(start/end)[1];}
    }
    static final class Panel {
        final List<Curve> left,right;final double depth;
        Panel(List<Curve> left,List<Curve> right,double depth){this.left=left;this.right=right;this.depth=depth;}
        void fill(JungleCelInk.Shape ink)throws Exception{
            double[] start=left.get(0).a;ink.m(start[0],start[1]);for(Curve c:left)c.trace(ink);
            double[] end=right.get(right.size()-1).d;ink.l(end[0],end[1]);for(int i=right.size()-1;i>=0;i--)right.get(i).reverse().trace(ink);ink.z().end();
        }
        void edges(JungleCelInk.Shape ink)throws Exception{
            edge(ink,left);edge(ink,right);ink.end();
        }
        private static void edge(JungleCelInk.Shape ink,List<Curve> side)throws Exception{double[] start=side.get(0).a;ink.m(start[0],start[1]);for(Curve c:side)c.trace(ink);}
        Panel band(double width){List<Curve> inner=new ArrayList<>();for(int i=0;i<left.size();i++){Curve a=left.get(i),b=right.get(i);inner.add(new Curve(mix(a.a,b.a,width),mix(a.b,b.b,width),mix(a.c,b.c,width),mix(a.d,b.d,width)));}return new Panel(left,inner,depth);}
    }
    static final class Fold {
        final double[] a,b,c,d,e,f;
        Fold(double[] a,double[] b,double[] c,double[] d,double[] e,double[] f){this.a=a;this.b=b;this.c=c;this.d=d;this.e=e;this.f=f;}
        void trace(JungleCelInk.Shape ink)throws Exception{ink.m(a[0],a[1]).c(b[0],b[1],c[0],c[1],d[0],d[1]).c(e[0],e[1],f[0],f[1],a[0],a[1]).z().end();}
    }
    static final class Limb {
        final Panel surface;final List<Panel> panels;final List<Fold> folds;final double flexion;
        Limb(Panel surface,List<Panel> panels,List<Fold> folds,double flexion){this.surface=surface;this.panels=panels;this.folds=folds;this.flexion=flexion;}
    }
    static final class DepthPlane {
        final SketchHumanFigure.Camera camera;final double y,depth,slope;
        DepthPlane(SketchHumanFigure.Camera camera,double[] hip,double[] shoulder){this.camera=camera;double[] a=camera.point(hip),b=camera.point(shoulder);y=a[1];depth=camera.clothDepth(hip);slope=(camera.clothDepth(shoulder)-depth)/(b[1]-a[1]);}
        double relative(double[] point){return camera.clothDepth(point)-depth-slope*(camera.point(point)[1]-y);}
    }

    static Limb limb(SketchHumanFigure.Camera camera,double[] start,double[] joint,double[] end,double startWidth,double jointWidth,double endWidth,double rootAllowance,double cuffAllowance,DepthPlane torso){
        double[] u=unit(sub(joint,start)),v=unit(sub(end,joint));double cosine=clamp(dot(u,v),-1,1),flexion=Math.acos(cosine),bend=Math.sin(flexion*.5);
        double[] clothStart=add(start,mul(u,-rootAllowance)),clothEnd=add(end,mul(v,cuffAllowance));
        double[] a=camera.point(clothStart),j=camera.point(joint),b=camera.point(clothEnd),upper=sub(j,a),lower=sub(b,j);
        double upperLength=length(upper),lowerLength=length(lower);double[] tu=unit2(upper,new double[]{0,1}),tv=unit2(lower,tu);
        double[] nu={-tu[1],tu[0]},nv={-tv[1],tv[0]},inside=camera.point(unit(sub(v,u)));
        // Offsets use a camera-facing width frame; the physical bend supplies
        // compression even when the two projected bones nearly coincide.
        double[] bisector=unit2(add(nu,nv),unit2(inside,nu));double inward=dot(bisector,inside),visibleBend=Math.min(1,Math.abs(inward));
        double runU=Math.min(upperLength*.28,jointWidth*(1.3+.5*bend)),runV=Math.min(lowerLength*.28,jointWidth*(1.3+.5*bend));
        double leftRadius=jointWidth*(1-.36*bend*visibleBend*(inward>0?1:0)),rightRadius=jointWidth*(1-.36*bend*visibleBend*(inward<0?1:0));
        double[][] centres={a,add(j,mul(tu,-runU)),j,add(j,mul(tv,runV)),b};
        double[][] left=new double[5][],right=new double[5][];
        double[] widths={startWidth,jointWidth*(1+.08*bend),jointWidth,jointWidth*(1-.06*bend),endWidth};
        for(int i=0;i<5;i++){
            double[] normal=i<2?nu:i>2?nv:bisector;
            left[i]=add(centres[i],mul(normal,i==2?leftRadius:widths[i]));right[i]=add(centres[i],mul(normal,-(i==2?rightRadius:widths[i])));
        }
        List<Curve> l=curves(left),r=curves(right);Panel surface=new Panel(l,r,0);List<Panel> panels=new ArrayList<>();
        double fu=upperLength<1e-8?0:runU/upperLength,fv=lowerLength<1e-8?0:runV/lowerLength;
        double[][] physical={clothStart,mix(clothStart,joint,1-fu),joint,mix(joint,clothEnd,fv),clothEnd};
        for(int i=0;i<4;i++){
            double da=torso.relative(physical[i]),db=torso.relative(physical[i+1]);
            double cut=da*db<0?da/(da-db):-1;
            if(cut>1e-6&&cut<1-1e-6){panels.add(panel(l.get(i),r.get(i),0,cut,(da+0)*.5));panels.add(panel(l.get(i),r.get(i),cut,1,(0+db)*.5));}
            else panels.add(panel(l.get(i),r.get(i),0,1,(da+db)*.5));
        }
        List<Fold> folds=new ArrayList<>();
        double insideLength=length(inside),foldSize=bend*Math.min(1,insideLength);
        double[] inward2=unit2(inside,bisector),across={-inward2[1],inward2[0]};
        for(int f=0;f<2;f++){
            double distance=jointWidth*(.28+.25*f),span=jointWidth*(.50-.12*f)*foldSize,thickness=jointWidth*.10*foldSize;
            double[] centre=add(j,mul(inward2,distance)),p0=add(centre,mul(across,-span)),p1=add(centre,mul(across,span));
            folds.add(new Fold(p0,add(p0,mul(inward2,-thickness)),add(centre,mul(inward2,thickness)),p1,add(centre,mul(inward2,thickness*.35)),p0));
        }
        return new Limb(surface,panels,folds,flexion);
    }
    private static Panel panel(Curve left,Curve right,double from,double to,double depth){List<Curve> l=new ArrayList<>(),r=new ArrayList<>();l.add(left.section(from,to));r.add(right.section(from,to));return new Panel(l,r,depth);}
    private static List<Curve> curves(double[][] points){
        List<Curve> curves=new ArrayList<>();double[][] tangent=new double[points.length][2];
        for(int i=0;i<points.length;i++)for(int axis=0;axis<2;axis++){
            double before=points[i][axis]-points[Math.max(0,i-1)][axis],after=points[Math.min(points.length-1,i+1)][axis]-points[i][axis];
            tangent[i][axis]=i==0?after:i==points.length-1?before:before*after<=0?0:Math.copySign(Math.min(Math.abs((before+after)*.5),3*Math.min(Math.abs(before),Math.abs(after))),before);
        }
        for(int i=0;i<points.length-1;i++){
            double[] a=points[i],b=points[i+1],first=add(a,mul(tangent[i],1/3d)),second=add(b,mul(tangent[i+1],-1/3d));
            // Shared monotone tangents give C1 edges without an overshooting
            // knee control point below its physical contact envelope.
            curves.add(new Curve(a,first,second,b));
        }return curves;
    }
    static double armPull(double[] shoulder,double[] elbow){return clamp(1-(elbow[1]-shoulder[1])/76,0,1);}
    static double compression(double[] a,double[] joint,double[] b){return Math.sin(Math.acos(clamp(dot(unit(sub(joint,a)),unit(sub(b,joint))),-1,1))*.5);}
    private static double clamp(double x,double min,double max){return Math.max(min,Math.min(max,x));}
    private static double[] mix(double[] a,double[] b,double t){double[] out=new double[a.length];for(int i=0;i<out.length;i++)out[i]=a[i]+(b[i]-a[i])*t;return out;}
    private static double[] add(double[] a,double[] b){double[] out=new double[a.length];for(int i=0;i<out.length;i++)out[i]=a[i]+b[i];return out;}
    private static double[] sub(double[] a,double[] b){double[] out=new double[a.length];for(int i=0;i<out.length;i++)out[i]=a[i]-b[i];return out;}
    private static double[] mul(double[] a,double t){double[] out=a.clone();for(int i=0;i<out.length;i++)out[i]*=t;return out;}
    private static double dot(double[] a,double[] b){double out=0;for(int i=0;i<a.length;i++)out+=a[i]*b[i];return out;}
    private static double length(double[] a){return Math.sqrt(dot(a,a));}
    private static double[] unit(double[] a){double len=length(a);return len<1e-10?new double[a.length]:mul(a,1/len);}
    private static double[] unit2(double[] a,double[] fallback){double len=length(a);return len<1e-8?fallback.clone():mul(a,1/len);}
}
