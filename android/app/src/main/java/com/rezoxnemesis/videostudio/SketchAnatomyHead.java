package com.rezoxnemesis.videostudio;

import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Original measured head construction. All output is ordinary editable native Bezier ink.
 * Local +z is the face, +y is down. Dimensions are adult model pixels, not canvas pixels.
 * The caller owns neck/body, camera, settings validation and final staging normalization.
 */
final class SketchAnatomyHead {
    private static final String INK="#FF383B40",IRIS="#FF4B5150";
    private SketchAnatomyHead() {}

    static void draw(JSONObject layer,String actorId,JSONObject settings,SketchHumanFigure.Skeleton p)throws Exception {
        JSONArray objects=layer.getJSONArray("objects");int first=objects.length();
        Head h=new Head(layer,settings,p);
        if(settings.optString("hairStyle","short").equals("long"))h.longHair();
        h.ear(-1);h.ear(1);
        h.cranium();
        if(h.view[2]>-.08){
            h.planes();
            int far=h.view[0]>0?-1:1;
            h.eye(far);h.eye(-far);h.nose();h.mouth();
        }
        h.hair();
        // The near helix breaks the side hairline; the far ear stays behind the skull.
        if(Math.abs(h.view[0])>.38)h.ear(h.view[0]<0?-1:1);
        // JungleCelInk assigns provisional IDs. Rename only objects appended by this call.
        for(int i=first;i<objects.length();i++)objects.getJSONObject(i).put("id",actorId+"_anatomy_head_"+(i-first));
    }

    private static final class Head {
        final JungleCelInk ink;
        final SketchHumanFigure.Skeleton p;
        final String skin,hair,shade,soft,light;
        final double[] axis,view;
        final double angle;
        final JSONObject controls;
        Head(JSONObject layer,JSONObject settings,SketchHumanFigure.Skeleton skeleton){
            p=skeleton;ink=new JungleCelInk(layer).at(640,360,1/16d,0);
            skin=settings.optString("skinColor","#FFF1EEE8");hair=settings.optString("hairColor","#FF555150");
            shade=tone(skin,INK,.13);soft=tone(skin,INK,.48);light=tone(hair,skin,.53);
            controls=p.controls==null?new JSONObject():p.controls;
            axis=p.camera.poseAxis();angle=Math.toRadians(p.lean+p.headTilt);
            // Lean/tilt uses the old head's screen-normal rotation convention.
            // Its inverse leaves the viewing normal itself invariant.
            view=p.camera.worldPose?rotate(p.camera.normal(),axis,-angle):axis.clone();
        }
        double control(String key){return controls.optDouble(key,0);}
        double[] world(double[] local){double[] r=rotate(local,axis,angle);return v(r[0]+p.head[0],r[1]+p.head[1],r[2]+p.head[2]);}
        double[] point(double[] local){return p.camera.point(world(local));}
        double depth(double[] local){double[] r=world(local);return Math.sin(p.camera.pitch)*r[1]+Math.cos(p.camera.pitch)*p.camera.depth(r);}
        Pen pen(String fill,String stroke,double weight){return new Pen(this,ink.ink(fill,stroke,weight*16));}

        /** Adult cranial envelope with a separate narrow nasal ridge and tapered mandible.
         * Rings are original anatomical measurements, not sampled reference contours.
         * The shell determines the actual view silhouette, including profile nose/lips.
         */
        void cranium()throws Exception {
            double[][] rings={
                {-23,8,9,10,0},{-20,13,13.5,15,0},{-16,16.8,16.5,18,0},
                {-11,18.6,17.7,19.8,0},{-6,19,18,20,0},
                {-3,18.7,17.5,19.8,1.3},{1,18.2,17,19,3.6},
                {5,17.5,16.8,17.8,6.5},{7.5,16.8,16.7,16.8,8},
                {9.5,16.1,16.4,15.5,4.5},{11,15.6,16.8,14,1.2},
                {13,14.7,20.2,12.2,.4},{15,13.8,20,10.5,0},
                {17,12.6,18.8,8.6,0},{20,10.1,17.8,5.5,0},
                {23,6.4,16.3,2,0},{24.5,3,13.7,.8,0}
            };
            Mesh mesh=new Mesh();mesh.vertices.add(v(0,-25,-1));int n=48;
            for(double[] r:rings)for(int j=0;j<n;j++){
                double a=j*Math.PI*2/n,c=Math.cos(a),x=r[1]*Math.sin(a);
                double z=c>=0?r[2]*Math.pow(c,.52)+r[4]*Math.pow(c,32):r[3]*c;
                mesh.vertices.add(v(x,r[0],z));
            }
            for(int j=0;j<n;j++)mesh.triangle(0,1+(j+1)%n,1+j);
            for(int row=0;row<rings.length-1;row++)for(int j=0;j<n;j++){
                int a=1+row*n+j,b=1+row*n+(j+1)%n,c=a+n,d=b+n;
                mesh.triangle(a,b,c);mesh.triangle(b,d,c);
            }
            int end=mesh.vertices.size();mesh.vertices.add(v(0,25,10.5));
            int last=1+(rings.length-1)*n;
            for(int j=0;j<n;j++)mesh.triangle(last+j,last+(j+1)%n,end);
            mesh.draw(this,skin,INK,.62);
        }

        void ear(int s)throws Exception {
            if(s*view[0]<-.42)return;
            // Helix follows a side-facing plane, not a frontal oval pasted onto the face.
            pen(skin,soft,.36).m(s*17.8,-4,2)
                .c(s*20.4,-6,-1,s*22,-3.5,-5,s*21.8,.5,-5)
                .c(s*21.7,5,-4.5,s*21,10,-2,s*18.7,11,0)
                .q(s*17.2,10,2,s*17.6,6,3).q(s*17,2,4,s*17.8,-4,2).z().end();
            pen(null,soft,.25).m(s*18.8,-1,1)
                .c(s*21,-4,-2,s*21.8,-.5,-3.7,s*20.6,2,-2.3)
                .q(s*18.2,3,1,s*19.5,6,-.7)
                .m(s*19.4,1.5,.8).q(s*17.8,2.4,2.8,s*18.8,4.3,2).end();
            pen(null,soft,.22).m(s*20.8,4,-2.6).q(s*21,7,-1.5,s*19.4,8.7,0).end();
        }

        void planes()throws Exception {
            for(int s:new int[]{-1,1}){
                if(s*view[0]*.82+view[2]*.57<.08)continue;
                pen(shade,null,.2).m(s*15,-5,11.8).q(s*17,1,11.6,s*15.7,7,12.6)
                    .l(s*10.8,16.7,14.4).q(s*13.6,8,14,s*12,4.2,15).z().end();
                pen(null,soft,.23).m(s*12.8,4.4,15.3).q(s*15,4.8,13.5,s*16.1,7,12.4).end();
                pen(null,soft,.22).m(s*15.1,-9.6,12.7).q(s*16.9,-7.6,10.5,s*16.1,-4.3,12.6).end();
                // Three measured, tapering strokes suggest the zygomatic turn.
                for(int j=0;j<3;j++)pen(null,tone(skin,INK,.29),.18)
                    .m(s*(13.1+j*.65),7+j*.6,14.5-j*.5)
                    .q(s*(12.4+j*.65),8.2+j*.6,14.8-j*.5,s*(11.9+j*.65),9.3+j*.6,15-j*.5).end();
            }
            if(view[1]>.12){
                pen(shade,null,.2).m(-10,20,14).q(0,24,17,10,20,14)
                    .l(5,24,13).q(0,26,10,-5,24,13).z().end();
                pen(null,soft,.24).m(-5,23,15).q(0,24,16,5,23,15).end();
            }
        }

        void eye(int s)throws Exception {
            // A far socket disappears across the nasal/temporal turn; the near socket
            // remains a genuine side-plane feature in profile.
            if(s*view[0]*.50+view[2]*.866<.12)return;
            double open=1-control("blink"),brow=control("brow"),y=-.8;
            double inner=s*3.8,outer=s*13.6,cx=s*8.6;
            if(open>.025){
                pen(tone(skin,"#FFFFFFFF",.38),soft,.23).m(inner,y+.25,18.6)
                    .c(s*6,y-1.35*open,18.4,s*10.2,y-1.6*open,17,s*13.6,y-.15,14.1)
                    .c(s*11.3,y+1.7*open,16.4,s*6.8,y+1.9*open,18.2,inner,y+.25,18.6).z().end();
                // Curved globe patches move behind opaque native lid planes.
                // Vertical travel shrinks with closure; lids cover the globe at rest.
                double gx=control("gazeX")*.95,gy=control("gazeY")*.38*open;
                double ix=cx+gx,iz=17.55-s*gx*.42,iy=y+gy;
                oval(IRIS,null,.2,ix,iy,iz,1.38,1.58*open,s);
                oval(INK,null,.2,ix,iy,iz+.07,.51,.78*open,s);
                // Native skin lid planes clip the enlarged iris above and below the
                // aperture, so the resting upper lid actually overlaps the iris.
                pen(skin,null,.2).m(inner,y+.25,18.6)
                    .c(s*6,y-1.35*open,18.4,s*10.2,y-1.6*open,17,outer,y-.15,14.1)
                    .l(outer,y-3.4,14.1).q(s*8.6,y-4,17.5,inner,y-3.4,18.6).z().end();
                pen(skin,null,.2).m(outer,y-.15,14.1)
                    .c(s*11.3,y+1.7*open,16.4,s*6.8,y+1.9*open,18.2,inner,y+.25,18.6)
                    .l(inner,y+3.1,18.6).q(s*8.6,y+3.5,17.5,outer,y+3.1,14.1).z().end();
                pen(null,INK,.51).m(inner,y+.25,18.7)
                    .c(s*6,y-1.35*open,18.5,s*10.2,y-1.6*open,17.1,outer,y-.15,14.2).end();
                pen(null,soft,.22).m(s*5.1,y+1.1*open,18.5)
                    .q(s*9.2,y+2.3*open,17.5,s*12.7,y+.65*open,15.3).end();
                pen(null,soft,.2).m(s*4.8,y-2.6*open-.6,18.5)
                    .q(s*9,y-4*open,17.7,s*13.4,y-1.6*open-1,14.5).end();
            }else{
                pen(null,INK,.39).m(inner,y+.25,18.6)
                    .q(s*8.5,y+1.2,17.9,outer,y-.15,14.1).end();
            }
            double by=-5.1-brow*1.7;
            pen(tone(hair,skin,.24),null,.2).m(s*3.9,by+.5-brow*.4,18.4)
                .q(s*8.2,by-2,18.1,s*13.8,by+.2+brow*.6,13.9)
                .l(s*13.5,by+1.2+brow*.6,14.2)
                .q(s*8.5,by-.8,18.2,s*4.1,by+1.5-brow*.4,18.5).z().end();
            for(int j=0;j<5;j++){
                double x=4.5+j*1.6;
                pen(null,hair,.19).m(s*x,by+.75,18.5-(x-4.5)*.45)
                    .q(s*(x+.45+j*.08),by-.15,18.4-(x-4.5)*.45,s*(x+1+j*.12),by-.40+j*.12,18-(x-4.5)*.45).end();
            }
        }

        void oval(String fill,String stroke,double weight,double x,double y,double z,double rx,double ry,int side)throws Exception {
            // Tangent of the eyeball turns toward its own temple.
            double dx=rx*.89,dz=-side*rx*.45,k=.55228475;
            pen(fill,stroke,weight).m(x-dx,y,z-dz)
                .c(x-dx,y-k*ry,z-dz,x-k*dx,y-ry,z-k*dz,x,y-ry,z)
                .c(x+k*dx,y-ry,z+k*dz,x+dx,y-k*ry,z+dz,x+dx,y,z+dz)
                .c(x+dx,y+k*ry,z+dz,x+k*dx,y+ry,z+k*dz,x,y+ry,z)
                .c(x-k*dx,y+ry,z-k*dz,x-dx,y+k*ry,z-dz,x-dx,y,z-dz).z().end();
        }

        void nose()throws Exception {
            int s=view[0]<-.08?-1:1;
            pen(shade,null,.2).m(s*1.5,-3.5,19.2)
                .c(s*1.7,1,21.2,s*2.6,4.6,24,s*3.3,7.5,23.3)
                .q(s*4,10,20.1,s*1.4,10.3,20.3)
                .l(s*.6,8.5,24).q(s*1,1,21,s*1.5,-3.5,19.2).z().end();
            pen(null,soft,.25).m(s*1.5,-3.8,19.2)
                .c(s*1.3,1,21.5,s*2.4,4.7,24.3,s*2.7,6.5,24.5).end();
            if(view[1]>-.25)pen(null,INK,.28).m(-1.5,9.2,22.5).q(0,10,23.8,1.5,9.2,22.5).end();
            for(int side:new int[]{-1,1}){
                if(side*view[0]+view[2]*.62<.07)continue;
                pen(null,soft,.27).m(side*2.4,6.9,22.4)
                    .q(side*4.6,7.7,20.1,side*3.8,9.7,20.3).end();
                if(.70*view[1]+.45*view[2]+side*.25*view[0]>.08)
                    pen(soft,null,.2).m(side*1.6,9,22.4)
                        .q(side*2.8,7.9,21.6,side*3.6,9,20.9)
                        .q(side*2.8,9.5,21.6,side*1.6,9,22.4).z().end();
            }
            pen(null,tone(skin,INK,.30),.2).m(-.7,10.6,19.7).l(-1,12,19.1)
                .m(.7,10.6,19.7).l(1,12,19.1).end();
        }

        void mouth()throws Exception {
            double smile=control("smile"),open=control("mouth"),round=control("mouthRound");
            double w=6.1*(1+.28*control("mouthWide"))*(1-.38*round);
            double corner=14.3-smile*1.3,y=14.3,drop=open*(2.5+round*1.3);
            // Lip corners sit back on the muzzle. The cupid's bow and chin remain distinct.
            if(open>.02){
                pen(tone(INK,skin,.12),null,.2).m(-w,corner,17.6)
                    .c(-w*.5,y-.8,19.5,-1,y-.55,19.9,0,y-.25,20)
                    .c(1,y-.55,19.9,w*.5,y-.8,19.5,w,corner,17.6)
                    .c(w*.66,y+drop+1,18.7,-w*.66,y+drop+1,18.7,-w,corner,17.6).z().end();
                if(control("mouthTeeth")>0)pen(tone(skin,"#FFFFFFFF",.4),null,.2)
                    .m(-w*.55,y+.15,19.7).q(0,y+.6*open*control("mouthTeeth"),20,w*.55,y+.15,19.7)
                    .q(0,y+1.2*open*control("mouthTeeth"),20,-w*.55,y+.15,19.7).z().end();
                if(control("mouthTongue")>0)pen(tone(skin,INK,.30),null,.2)
                    .m(-w*.35,y+drop*.78,19.7).q(0,y+drop*.40,20,w*.35,y+drop*.78,19.7)
                    .q(0,y+drop*.95,20,-w*.35,y+drop*.78,19.7).z().end();
            }
            pen(null,soft,.24).m(-w,corner,17.7)
                .c(-w*.57,y-.5,19.5,-1.7,y-1.7,19.8,-.9,y-1.1,20)
                .q(0,y-.5,20.1,.9,y-1.1,20)
                .c(1.7,y-1.7,19.8,w*.57,y-.5,19.5,w,corner,17.7).end();
            if(open<=.02)pen(null,INK,.32).m(-w,corner,17.7)
                .c(-w*.5,y+.3,19.7,-1,y-.2,20,0,y+.1,20.1)
                .c(1,y-.2,20,w*.5,y+.3,19.7,w,corner,17.7).end();
            pen(null,soft,.23).m(-w*.64,y+1.6+drop,19)
                .q(0,y+2.8+drop,19.9,w*.64,y+1.6+drop,19).end();
            pen(null,tone(skin,INK,.29),.19).m(-2.9,19.3+drop*.3,18.2)
                .q(0,20+drop*.3,18.7,2.9,19.3+drop*.3,18.2).end();
        }

        /** A cap built of directional, overlapping locks; no image or texture backing. */
        void hair()throws Exception {
            Mesh mesh=new Mesh();int n=48,rows=9;
            mesh.vertices.add(v(-2,-27,-2));
            for(int i=1;i<=rows;i++)for(int j=0;j<n;j++){
                double a=j*Math.PI*2/n,c=Math.cos(a);
                double bottom=c>0?-8.7-4.8*c:3.5-7*c;
                double t=(double)i/rows*Math.acos(-(bottom+5)/22);
                // Small authored contour ridges follow the locks, not random pencil noise.
                double ridge=.65*Math.sin(7*a+.55)*Math.pow((double)i/rows,2);
                mesh.vertices.add(v((19.8+ridge)*Math.sin(t)*Math.sin(a),-5-(22+ridge)*Math.cos(t),21*Math.sin(t)*c));
            }
            for(int j=0;j<n;j++)mesh.triangle(0,1+(j+1)%n,1+j);
            for(int row=0;row<rows-1;row++)for(int j=0;j<n;j++){
                int a=1+row*n+j,b=1+row*n+(j+1)%n;
                mesh.triangle(a,b,a+n);mesh.triangle(b,b+n,a+n);
            }
            mesh.draw(this,tone(hair,skin,.26),INK,.44);
            double[][] tufts={{-16,-20,5,-19,-23,7,-14,-22,6},
                {-10,-25,7,-13,-28,8,-7,-25,9},{-2,-26,4,-1,-29,8,4,-25,9},
                {8,-24,5,12,-27,5,14,-21,7},{16,-17,-2,22,-18,0,18,-12,3}};
            for(double[] t:tufts){
                if(t[0]/20*view[0]+t[1]/25*view[1]+t[2]/20*view[2]<-.12)continue;
                pen(tone(hair,skin,.23),soft,.24).m(t[0]-3,t[1]+3,t[2]-1)
                    .q(t[0],t[1]-2,t[2],t[3],t[4],t[5])
                    .q(t[3]-1,t[4]+3,t[5],t[6],t[7],t[8])
                    .q(t[0]+3,t[1]+4,t[2],t[0]-3,t[1]+3,t[2]-1).z().end();
            }
            // Forelock clusters have separate tips, interior flow and open skin gaps.
            if(view[2]>.08){
                double[][] locks={{-15,-19,-17,-6,-7,-25},{-12,-22,-9,-10,-3,-27},
                    {-7,-23,-1,-8.5,5,-26},{-1,-24,7,-11,11,-25},
                    {5,-23,13,-8,17,-22},{12,-20,18,-4,22,-17}};
                for(double[] lock:locks){
                    double x=lock[0],y=lock[1],tx=lock[2],ty=lock[3],bx=lock[4],by=lock[5];
                    double z=17.9-Math.abs(x)*.22;
                    if(x*view[0]*.06+view[2]<.14)continue;
                    pen(tone(hair,skin,.20),null,.2).m(x-2,y,z-2)
                        .c(bx,by,z-1,tx+3,ty-4,z+2,tx,ty,z+1)
                        .q(tx+1,ty-4,z+2,tx-2,ty-6,z+1)
                        .q(x-3,y+2,z,x-2,y,z-2).z().end();
                    pen(null,light,.21).m(x-.8,y,z-.8)
                        .c(bx-1,by+1,z+1,tx+1.8,ty-5,z+2,tx+.5,ty-2,z+1.5).end();
                }
            }
            // Flow arcs lie on the actual cranial surface and are culled on the far side.
            for(int j=0;j<14;j++){
                double a=j*Math.PI*2/14;
                double bottom=Math.cos(a)>0?1.04:1.87;
                for(int k=0;k<2;k++){
                    double t=.28+k*.54+(j%3)*.045;
                    double mid=t+.28;
                    if(Math.sin(mid)*Math.sin(a)*view[0]-Math.cos(mid)*view[1]+Math.sin(mid)*Math.cos(a)*view[2]<-.02)continue;
                    double[] a0=hairPoint(a-.32,t),a1=hairPoint(a-.20,t+.18),a2=hairPoint(a+.03,t+.4),a3=hairPoint(a+.15,Math.min(bottom,t+.59));
                    pen(null,k==0?light:tone(hair,skin,.44),.20).m(a0).c(a1,a2,a3).end();
                }
            }
            if(Math.abs(view[0])>.35){
                int s=view[0]<0?-1:1;
                pen(hair,null,.2).m(s*18,-10,4).q(s*20,-4,3,s*18.4,1,2)
                    .l(s*17.7,3,3).q(s*18,-5,7,s*16.8,-8,8).z().end();
            }
        }
        double[] hairPoint(double a,double t){return v(20.5*Math.sin(t)*Math.sin(a),-5-22.8*Math.cos(t),21.7*Math.sin(t)*Math.cos(a));}

        void longHair()throws Exception {
            // A closed rear volume retains width behind the skull in profile. The
            // old almost-flat curtain projected inside the cranium at side angles.
            Mesh curtain=new Mesh();int sides=32;
            double[][] rings={{-16,18,11,-14},{0,23,12,-14},{23,21,10,-13},{42,23,7,-11}};
            curtain.vertices.add(v(0,-23,-14));
            for(double[] ring:rings)for(int j=0;j<sides;j++){
                double angle=2*Math.PI*j/sides;
                curtain.vertices.add(v(ring[1]*Math.sin(angle),ring[0],ring[3]+ring[2]*Math.cos(angle)));
            }
            for(int j=0;j<sides;j++)curtain.triangle(0,1+(j+1)%sides,1+j);
            for(int row=0;row<rings.length-1;row++)for(int j=0;j<sides;j++){
                int a=1+row*sides+j,b=1+row*sides+(j+1)%sides;
                curtain.triangle(a,b,a+sides);curtain.triangle(b,b+sides,a+sides);
            }
            int bottom=curtain.vertices.size();curtain.vertices.add(v(0,47,-11));
            for(int j=0;j<sides;j++)curtain.triangle(1+3*sides+j,1+3*sides+(j+1)%sides,bottom);
            curtain.draw(this,tone(hair,skin,.18),INK,.46);
            for(int s:new int[]{-1,1})for(int i=0;i<4;i++)pen(null,light,.23)
                .m(s*(13+i*1.5),-3,-6-i*.8)
                .c(s*(21+i*.35),12,-7-i*.8,s*(13+i*1.4),29,-7-i*.8,s*(17+i*1.3),40,-7-i*.8).end();
        }
    }

    /** Project every Bezier control point in the same local 3D frame. */
    private static final class Pen {
        final Head h;final JungleCelInk.Shape path;
        Pen(Head head,JungleCelInk.Shape shape){h=head;path=shape;}
        Pen m(double x,double y,double z)throws Exception{return m(v(x,y,z));}
        Pen m(double[] a)throws Exception{double[] p=h.point(a);path.m(p[0],p[1]);return this;}
        Pen l(double x,double y,double z)throws Exception{double[] p=h.point(v(x,y,z));path.l(p[0],p[1]);return this;}
        Pen q(double x,double y,double z,double a,double b,double c)throws Exception{double[] p=h.point(v(x,y,z)),r=h.point(v(a,b,c));path.q(p[0],p[1],r[0],r[1]);return this;}
        Pen c(double x,double y,double z,double a,double b,double c,double d,double e,double f)throws Exception{return c(v(x,y,z),v(a,b,c),v(d,e,f));}
        Pen c(double[] a,double[] b,double[] c)throws Exception{double[] p=h.point(a),q=h.point(b),r=h.point(c);path.c(p[0],p[1],q[0],q[1],r[0],r[1]);return this;}
        Pen z(){path.z();return this;}
        void end()throws Exception{path.end();}
    }

    /** Extract the apparent contour of a small construction shell. No mesh/texture is
     * saved: the result is one native cubic outline and one native fill per contour.
     */
    private static final class Mesh {
        final List<double[]> vertices=new ArrayList<>();
        final List<int[]> triangles=new ArrayList<>();
        void triangle(int a,int b,int c){triangles.add(new int[]{a,b,c});}
        void draw(Head h,String fill,String stroke,double weight)throws Exception {
            Map<Long,int[]> boundary=new LinkedHashMap<>();
            List<int[]> facing=new ArrayList<>();
            for(int[] t:triangles){
                double[] a=vertices.get(t[0]),b=vertices.get(t[1]),c=vertices.get(t[2]);
                if(dot(cross(sub(b,a),sub(c,a)),h.view)<=1e-9)continue;
                facing.add(t);
                for(int i=0;i<3;i++){
                    int u=t[i],v=t[(i+1)%3];long key=((long)Math.min(u,v)<<32)|Math.max(u,v);
                    if(boundary.containsKey(key))boundary.remove(key);else boundary.put(key,new int[]{u,v});
                }
            }
            Map<Integer,List<Integer>> next=new LinkedHashMap<>();
            for(int[] e:boundary.values())next.computeIfAbsent(e[0],key->new ArrayList<>()).add(e[1]);
            double[][] projected=new double[vertices.size()][];
            for(int i=0;i<vertices.size();i++){
                double[] q=h.point(vertices.get(i));projected[i]=v(q[0],q[1],h.depth(vertices.get(i)));
            }
            Pen p=h.pen(fill,null,weight),outline=h.pen(null,stroke,weight);boolean drawn=false,outlined=false;
            while(!next.isEmpty()){
                int start=next.keySet().iterator().next(),at=start;List<double[]> loop=new ArrayList<>();
                do{
                    loop.add(vertices.get(at));List<Integer> outgoing=next.get(at);
                    if(outgoing==null)break;
                    int to=outgoing.remove(outgoing.size()-1);if(outgoing.isEmpty())next.remove(at);at=to;
                }while(at!=start&&loop.size()<=boundary.size());
                if(at!=start||loop.size()<3)continue;
                // Preserve every cycle in one nonzero-winding native path. At a
                // saddle vertex several directed edges may leave the same vertex;
                // discarding any one of them opens an entire cranial/hair contour.
                p.m(loop.get(0));int size=loop.size();drawn=true;
                for(int i=0;i<size;i++){
                    double[] a=loop.get((i+size-1)%size),b=loop.get(i),c=loop.get((i+1)%size),d=loop.get((i+2)%size);
                    // Restrained interpolation retains jaw/nasal landmarks without facets.
                    double[] c1=add(b,mul(sub(c,a),.11)),c2=sub(c,mul(sub(d,b),.11));
                    p.c(c1,c2,c);
                    // Facing triangles can overlap in projection around nose/chin/locks.
                    // Keep their union fill, but do not ink a far edge through a nearer plane.
                    // Test on the construction edge itself: its smoothed cubic can
                    // sit slightly inside its own adjacent triangle without being hidden.
                    double[] mid=mul(add(b,c),.5),q=h.point(mid);
                    if(!occluded(q[0],q[1],h.depth(mid),projected,facing)){
                        outline.m(b).c(c1,c2,c);outlined=true;
                    }
                }
                p.z();
            }
            if(drawn)p.end();
            if(outlined)outline.end();
        }
        boolean occluded(double x,double y,double depth,double[][] projected,List<int[]> facing){
            for(int[] t:facing){
                double[] a=projected[t[0]],b=projected[t[1]],c=projected[t[2]];
                double bx=b[0]-a[0],by=b[1]-a[1],cx=c[0]-a[0],cy=c[1]-a[1],den=bx*cy-by*cx;
                if(Math.abs(den)<1e-9)continue;
                double px=x-a[0],py=y-a[1],u=(px*cy-py*cx)/den,w=(bx*py-by*px)/den;
                if(u<-.00001||w<-.00001||u+w>1.00001)continue;
                if(a[2]+u*(b[2]-a[2])+w*(c[2]-a[2])>depth+.08)return true;
            }
            return false;
        }
    }

    private static double[] v(double x,double y,double z){return new double[]{x,y,z};}
    private static double[] add(double[] a,double[] b){return v(a[0]+b[0],a[1]+b[1],a[2]+b[2]);}
    private static double[] sub(double[] a,double[] b){return v(a[0]-b[0],a[1]-b[1],a[2]-b[2]);}
    private static double[] mul(double[] a,double n){return v(a[0]*n,a[1]*n,a[2]*n);}
    private static double dot(double[] a,double[] b){return a[0]*b[0]+a[1]*b[1]+a[2]*b[2];}
    private static double[] cross(double[] a,double[] b){return v(a[1]*b[2]-a[2]*b[1],a[2]*b[0]-a[0]*b[2],a[0]*b[1]-a[1]*b[0]);}
    private static double[] rotate(double[] a,double[] axis,double angle){
        double c=Math.cos(angle),s=Math.sin(angle);return add(add(mul(a,c),mul(cross(axis,a),s)),mul(axis,dot(axis,a)*(1-c)));
    }
    private static String tone(String from,String to,double mix){
        long a=Long.parseLong(from.substring(from.length()-6),16),b=Long.parseLong(to.substring(to.length()-6),16);
        int r=(int)Math.round(((a>>16)&255)*(1-mix)+((b>>16)&255)*mix);
        int g=(int)Math.round(((a>>8)&255)*(1-mix)+((b>>8)&255)*mix);
        int bl=(int)Math.round((a&255)*(1-mix)+(b&255)*mix);
        return String.format(java.util.Locale.ROOT,"#FF%02X%02X%02X",r,g,bl);
    }
}
