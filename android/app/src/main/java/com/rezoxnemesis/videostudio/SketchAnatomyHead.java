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
        draw(layer,actorId,settings,p,null);
    }
    static void draw(JSONObject layer,String actorId,JSONObject settings,SketchHumanFigure.Skeleton p,DepthOccluder neck)throws Exception {
        JSONArray objects=layer.getJSONArray("objects");int first=objects.length();
        Head h=new Head(layer,settings,p,neck);
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
        final JSONObject controls;final DepthOccluder neck;
        Head(JSONObject layer,JSONObject settings,SketchHumanFigure.Skeleton skeleton,DepthOccluder occluder){
            neck=occluder;
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
            mesh.draw(this,skin,INK,.62,true);
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
            // Each lock has its own near-side visibility check below. A blanket
            // frontal gate erased the entire fringe at both exact side views.
            {
                double[][] locks={{-15,-19,-17,-6,-7,-25},{-12,-22,-9,-10,-3,-27},
                    {-7,-23,-1,-8.5,5,-26},{-1,-24,7,-11,11,-25},
                    {5,-23,13,-8,17,-22},{12,-20,18,-4,22,-17}};
                for(double[] lock:locks){
                    double x=lock[0],y=lock[1],tx=lock[2],ty=lock[3],bx=lock[4],by=lock[5];
                    if(x*view[0]*.06+view[2]<.14)continue;
                    // Follow the cranial envelope in depth. Constant-z lock
                    // planes collapse into narrow strips in an exact profile.
                    pen(tone(hair,skin,.20),null,.2).m(fringePoint(x-2,y))
                        .c(fringePoint(bx,by),fringePoint(tx+3,ty-4),fringePoint(tx,ty))
                        .q(fringePoint(tx+1,ty-4),fringePoint(tx-2,ty-6))
                        .q(fringePoint(x-3,y+2),fringePoint(x-2,y)).z().end();
                    pen(null,light,.21).m(fringePoint(x-.8,y))
                        .c(fringePoint(bx-1,by+1),fringePoint(tx+1.8,ty-5),fringePoint(tx+.5,ty-2)).end();
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
        double[] fringePoint(double x,double y){
            double radial=1-x*x/(21*21)-(y+5)*(y+5)/(24*24);
            return v(x,y,1+21*Math.sqrt(Math.max(0,radial)));
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
        Pen q(double x,double y,double z,double a,double b,double c)throws Exception{return q(v(x,y,z),v(a,b,c));}
        Pen q(double[] a,double[] b)throws Exception{double[] p=h.point(a),r=h.point(b);path.q(p[0],p[1],r[0],r[1]);return this;}
        Pen c(double x,double y,double z,double a,double b,double c,double d,double e,double f)throws Exception{return c(v(x,y,z),v(a,b,c),v(d,e,f));}
        Pen c(double[] a,double[] b,double[] c)throws Exception{double[] p=h.point(a),q=h.point(b),r=h.point(c);path.c(p[0],p[1],q[0],q[1],r[0],r[1]);return this;}
        Pen z(){path.z();return this;}
        void end()throws Exception{path.end();}
    }

    /** Actual owner's projected torso triangles, shared from its surface construction.
     * Coverage alone is insufficient: a frontal chin can be nearer than the neck.
     */
    static final class DepthOccluder {
        final double[][] projected;final List<int[]> facing=new ArrayList<>();
        DepthOccluder(List<double[]> world,List<int[]> triangles,SketchHumanFigure.Camera camera){
            projected=new double[world.size()][];double[] normal=camera.normal();
            for(int i=0;i<world.size();i++){double[] point=world.get(i),xy=camera.point(point);projected[i]=v(xy[0],xy[1],camera.clothDepth(point));}
            for(int[] triangle:triangles){double[] a=world.get(triangle[0]),b=world.get(triangle[1]),c=world.get(triangle[2]);if(dot(cross(sub(b,a),sub(c,a)),normal)>1e-9)facing.add(triangle.clone());}
        }
        boolean occluded(double x,double y,double depth){return Mesh.occluded(x,y,depth,projected,facing);}
        /** Exact visibility breakpoints: camera projection and every triangle's
         * barycentric/depth tests are linear functions of a cubic's coordinates.
         * Their sign can only change at the bounded cubic roots collected here.
         */
        List<double[]> visible(Head head,double[][] local){
            double[][] curve=new double[4][];for(int i=0;i<4;i++){double[] xy=head.point(local[i]);curve[i]=v(xy[0],xy[1],head.depth(local[i]));}
            List<Double> cuts=new ArrayList<>();cuts.add(0d);cuts.add(1d);
            double minX=Double.POSITIVE_INFINITY,minY=minX,maxX=Double.NEGATIVE_INFINITY,maxY=maxX;
            for(double[] q:curve){minX=Math.min(minX,q[0]);maxX=Math.max(maxX,q[0]);minY=Math.min(minY,q[1]);maxY=Math.max(maxY,q[1]);}
            for(int[] triangle:facing){
                double[] a=projected[triangle[0]],b=projected[triangle[1]],c=projected[triangle[2]];
                if(Math.min(a[0],Math.min(b[0],c[0]))>maxX||Math.max(a[0],Math.max(b[0],c[0]))<minX||Math.min(a[1],Math.min(b[1],c[1]))>maxY||Math.max(a[1],Math.max(b[1],c[1]))<minY)continue;
                double bx=b[0]-a[0],by=b[1]-a[1],cx=c[0]-a[0],cy=c[1]-a[1],den=bx*cy-by*cx;if(Math.abs(den)<1e-9)continue;
                double[][] coefficients=new double[4][4];
                for(int i=0;i<4;i++){double px=curve[i][0]-a[0],py=curve[i][1]-a[1],u=(px*cy-py*cx)/den,w=(bx*py-by*px)/den;coefficients[0][i]=u+.00001;coefficients[1][i]=w+.00001;coefficients[2][i]=1.00001-u-w;coefficients[3][i]=a[2]+u*(b[2]-a[2])+w*(c[2]-a[2])-curve[i][2]-.08;}
                boolean impossible=false;for(double[] values:coefficients)if(Math.max(Math.max(values[0],values[1]),Math.max(values[2],values[3]))<0){impossible=true;break;}if(impossible)continue;
                for(double[] values:coefficients)roots(values,cuts);
            }
            cuts.sort(Double::compare);List<Double> unique=new ArrayList<>();for(double cut:cuts)if(unique.isEmpty()||cut-unique.get(unique.size()-1)>1e-10)unique.add(cut);
            List<double[]> result=new ArrayList<>();for(int i=0;i+1<unique.size();i++){double from=unique.get(i),to=unique.get(i+1);if(to-from<1e-10)continue;double[] q=evaluate(curve,(from+to)*.5);if(occluded(q[0],q[1],q[2]))continue;if(!result.isEmpty()&&Math.abs(result.get(result.size()-1)[1]-from)<1e-9)result.get(result.size()-1)[1]=to;else result.add(new double[]{from,to});}return result;
        }
        /** Bounded curve ribbons intersect actual torso triangles. End planes
         * preserve exposed tips; clipping is object-local, never a skin repaint.
         */
        JSONArray mask(Head h,List<double[][]> curves,double weight)throws Exception {
            List<List<double[]>> hidden=new ArrayList<>(),shown=new ArrayList<>();
            for(double[][] local:curves){List<double[]> visible=visible(h,local);double from=0;for(int i=0;i<=visible.size();i++){double to=i==visible.size()?1:visible.get(i)[0];if(to-from>1e-9)hidden.addAll(ribbon(h,local,from,to,weight,true));if(i<visible.size()){double[] range=visible.get(i);shown.addAll(ribbon(h,local,range[0],range[1],weight,false));from=range[1];}}}
            hidden=coalesce(hidden);
            // Neighboring exposed strokes share endpoint coverage. A hidden
            // curve must not mask that genuine nearer ink in the same Path.
            for(List<double[]> visible:shown){List<List<double[]>> next=new ArrayList<>();for(List<double[]> candidate:hidden){if(disjoint(candidate,visible)){next.add(candidate);continue;}double orientation=area(visible)>=0?1:-1;List<double[]> intersection=intersect(candidate,visible);if(intersection.size()<3||Math.abs(area(intersection))<1e-4){next.add(candidate);continue;}List<double[]> rest=candidate;for(int j=0;j<visible.size()&&!rest.isEmpty();j++){double[] a=visible.get(j),b=visible.get((j+1)%visible.size());double dx=b[0]-a[0],dy=b[1]-a[1],aa=-dy*orientation,bb=dx*orientation,cc=(dy*a[0]-dx*a[1])*orientation;List<double[]> outside=half(rest,-aa,-bb,-cc);if(outside.size()>=3&&Math.abs(area(outside))>1e-4)next.add(outside);rest=half(rest,aa,bb,cc);}if(next.size()>4096)throw SketchDocument.bad("Native clipping partition exceeds its bounded scratch space");}hidden=next;}
            hidden=coalesce(hidden);
            JSONArray rings=new JSONArray();int vertices=0;for(List<double[]> polygon:hidden){if(polygon.size()<3||Math.abs(area(polygon))<1e-4)continue;if(rings.length()>=SketchPathClip.MAX_RINGS||polygon.size()>SketchPathClip.MAX_RING_POINTS||(vertices+=polygon.size())>SketchPathClip.MAX_POINTS)throw SketchDocument.bad("Native neck clipping exceeds its polygon budget");JSONArray ring=new JSONArray();for(double[] q:polygon)ring.put(new JSONArray().put(h.ink.x(q[0],q[1])).put(h.ink.y(q[0],q[1])));rings.put(ring);}return rings;
        }
        /** Merge only an unchanged convex union, never a concavity or a
         * protected visible hole. Triangulation boundaries are not artwork.
         */
        private static List<List<double[]>> coalesce(List<List<double[]>> source){
            List<List<double[]>> result=new ArrayList<>(source);boolean changed;
            do{changed=false;for(int i=0;i<result.size();i++)for(int j=i+1;j<result.size();j++){
                List<double[]> a=result.get(i),b=result.get(j);if(disjoint(a,b))continue;
                List<double[]> points=new ArrayList<>(a);points.addAll(b);List<double[]> combined=hull(points);if(combined.size()>SketchPathClip.MAX_RING_POINTS)continue;
                double union=Math.abs(area(a))+Math.abs(area(b))-Math.abs(area(intersect(a,b))),covered=Math.abs(area(combined));
                if(Math.abs(covered-union)>Math.max(1e-9,union*1e-10))continue;
                result.set(i,combined);result.remove(j);changed=true;j=i;
            }}while(changed);return result;
        }
        private static List<double[]> intersect(List<double[]> source,List<double[]> clip){List<double[]> result=source;double orientation=area(clip)>=0?1:-1;for(int j=0;j<clip.size()&&!result.isEmpty();j++){double[] a=clip.get(j),b=clip.get((j+1)%clip.size());double dx=b[0]-a[0],dy=b[1]-a[1];result=half(result,-dy*orientation,dx*orientation,(dy*a[0]-dx*a[1])*orientation);}return result;}
        private static boolean disjoint(List<double[]> a,List<double[]> b){double ax=Double.POSITIVE_INFINITY,ay=ax,bx=ax,by=ax,ar=-ax,ab=-ax,br=-ax,bb=-ax;for(double[] p:a){ax=Math.min(ax,p[0]);ay=Math.min(ay,p[1]);ar=Math.max(ar,p[0]);ab=Math.max(ab,p[1]);}for(double[] p:b){bx=Math.min(bx,p[0]);by=Math.min(by,p[1]);br=Math.max(br,p[0]);bb=Math.max(bb,p[1]);}return ax>br||bx>ar||ay>bb||by>ab;}
        private List<List<double[]>> ribbon(Head h,double[][] local,double from,double to,double weight,boolean hidden)throws Exception {
            double[][] piece=section(local,from,to),curve=new double[4][];for(int i=0;i<4;i++)curve[i]=h.point(piece[i]);
            // A visible stroke needs conservative coverage beyond its
            // mathematical half-width: the octagonal hull's apothem and
            // antialiased edge must not become a mask for a neighboring tip.
            List<double[]> cloud=new ArrayList<>();double radius=weight*.5+(hidden?.14:.25);
            for(double[] q:curve)for(int k=0;k<8;k++){double a=Math.PI*k/4;cloud.add(new double[]{q[0]+radius*Math.cos(a),q[1]+radius*Math.sin(a)});}
            List<double[]> hull=hull(cloud);List<List<double[]>> result=new ArrayList<>();if(!hidden){result.add(hull);return result;}double[] first=curve[0],last=curve[3],start=sub2(curve[1],first),end=sub2(last,curve[2]);
            if(Math.hypot(start[0],start[1])>1e-10)hull=half(hull,start[0],start[1],-start[0]*first[0]-start[1]*first[1]);
            if(Math.hypot(end[0],end[1])>1e-10)hull=half(hull,-end[0],-end[1],end[0]*last[0]+end[1]*last[1]);
            for(int[] face:facing){List<double[]> polygon=hull;double[] a=projected[face[0]],b=projected[face[1]],c=projected[face[2]];double orientation=cross2(sub2(b,a),sub2(c,a))>=0?1:-1;
                for(int j=0;j<3&&!polygon.isEmpty();j++){double[] u=projected[face[j]],v=projected[face[(j+1)%3]];double dx=v[0]-u[0],dy=v[1]-u[1];polygon=half(polygon,-dy*orientation,dx*orientation,(dy*u[0]-dx*u[1])*orientation);}
                if(polygon.size()>=3&&Math.abs(area(polygon))>=1e-4)result.add(polygon);
            }return result;
        }
        private static double[] sub2(double[] a,double[] b){return new double[]{a[0]-b[0],a[1]-b[1]};}
        private static double cross2(double[] a,double[] b){return a[0]*b[1]-a[1]*b[0];}
        private static double area(List<double[]> p){if(p.size()<3)return 0;double a=0;double[] origin=p.get(0);for(int i=1;i+1<p.size();i++)a+=cross2(sub2(p.get(i),origin),sub2(p.get(i+1),origin));return a;}
        private static List<double[]> hull(List<double[]> cloud){cloud.sort((a,b)->a[0]==b[0]?Double.compare(a[1],b[1]):Double.compare(a[0],b[0]));List<double[]> p=new ArrayList<>();for(double[] q:cloud){while(p.size()>1&&cross2(sub2(p.get(p.size()-1),p.get(p.size()-2)),sub2(q,p.get(p.size()-1)))<=1e-12)p.remove(p.size()-1);p.add(q);}int lower=p.size();for(int i=cloud.size()-2;i>=0;i--){double[] q=cloud.get(i);while(p.size()>lower&&cross2(sub2(p.get(p.size()-1),p.get(p.size()-2)),sub2(q,p.get(p.size()-1)))<=1e-12)p.remove(p.size()-1);p.add(q);}if(!p.isEmpty())p.remove(p.size()-1);return p;}
        private static List<double[]> half(List<double[]> input,double a,double b,double c){List<double[]> out=new ArrayList<>();if(input.isEmpty())return out;double[] before=input.get(input.size()-1);double previous=a*before[0]+b*before[1]+c;for(double[] q:input){double value=a*q[0]+b*q[1]+c;if((value>=0)!=(previous>=0)){double t=previous/(previous-value);append(out,new double[]{before[0]+(q[0]-before[0])*t,before[1]+(q[1]-before[1])*t});}if(value>=0)append(out,q);before=q;previous=value;}if(out.size()>1&&Math.hypot(out.get(0)[0]-out.get(out.size()-1)[0],out.get(0)[1]-out.get(out.size()-1)[1])<1e-7)out.remove(out.size()-1);return out;}
        private static void append(List<double[]> out,double[] p){if(out.isEmpty()||Math.hypot(out.get(out.size()-1)[0]-p[0],out.get(out.size()-1)[1]-p[1])>1e-7)out.add(p);}
        private static double[] evaluate(double[][] p,double t){double u=1-t;return v(u*u*u*p[0][0]+3*u*u*t*p[1][0]+3*u*t*t*p[2][0]+t*t*t*p[3][0],u*u*u*p[0][1]+3*u*u*t*p[1][1]+3*u*t*t*p[2][1]+t*t*t*p[3][1],u*u*u*p[0][2]+3*u*u*t*p[1][2]+3*u*t*t*p[2][2]+t*t*t*p[3][2]);}
        private static double value(double[] p,double t){double u=1-t;return u*u*u*p[0]+3*u*u*t*p[1]+3*u*t*t*p[2]+t*t*t*p[3];}
        private static void roots(double[] p,List<Double> out){
            double min=Math.min(Math.min(p[0],p[1]),Math.min(p[2],p[3])),max=Math.max(Math.max(p[0],p[1]),Math.max(p[2],p[3]));if(min>0||max<0)return;
            double a=-p[0]+3*p[1]-3*p[2]+p[3],b=3*p[0]-6*p[1]+3*p[2],c=-3*p[0]+3*p[1];List<Double> monotone=new ArrayList<>();monotone.add(0d);
            if(Math.abs(a)<1e-12){if(Math.abs(b)>1e-12){double t=-c/(2*b);if(t>0&&t<1)monotone.add(t);}}
            else{double disc=4*b*b-12*a*c;if(disc>=0){double root=Math.sqrt(disc),r1=(-2*b-root)/(6*a),r2=(-2*b+root)/(6*a);if(r1>0&&r1<1)monotone.add(r1);if(r2>0&&r2<1)monotone.add(r2);}}
            monotone.add(1d);monotone.sort(Double::compare);
            for(int i=0;i+1<monotone.size();i++){double lo=monotone.get(i),hi=monotone.get(i+1),left=value(p,lo),right=value(p,hi);if(Math.abs(left)<1e-11)out.add(lo);if(Math.abs(right)<1e-11)out.add(hi);if(left==0||right==0||Math.signum(left)==Math.signum(right))continue;for(int step=0;step<48;step++){double mid=(lo+hi)*.5,v=value(p,mid);if(Math.signum(v)==Math.signum(left)){lo=mid;left=v;}else hi=mid;}out.add((lo+hi)*.5);}
        }
        static double[][] section(double[][] p,double from,double to){if(from==0&&to==1)return p;double[][][] first=split(p,to);return from==0?first[0]:split(first[0],from/to)[1];}
        private static double[][][] split(double[][] p,double t){double[] a=lerp(p[0],p[1],t),b=lerp(p[1],p[2],t),c=lerp(p[2],p[3],t),d=lerp(a,b,t),e=lerp(b,c,t),f=lerp(d,e,t);return new double[][][]{new double[][]{p[0],a,d,f},new double[][]{f,e,c,p[3]}};}
        private static double[] lerp(double[] a,double[] b,double t){return v(a[0]+t*(b[0]-a[0]),a[1]+t*(b[1]-a[1]),a[2]+t*(b[2]-a[2]));}
    }

    /** Extract the apparent contour of a small construction shell. No mesh/texture is
     * saved: the result is one native cubic outline and one native fill per contour.
     */
    private static final class Mesh {
        final List<double[]> vertices=new ArrayList<>();
        final List<int[]> triangles=new ArrayList<>();
        void triangle(int a,int b,int c){triangles.add(new int[]{a,b,c});}
        void draw(Head h,String fill,String stroke,double weight)throws Exception {draw(h,fill,stroke,weight,false);}
        void draw(Head h,String fill,String stroke,double weight,boolean neckOcclusion)throws Exception {
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
            Pen p=h.pen(fill,null,weight),outline=h.pen(null,stroke,weight);boolean drawn=false,outlined=false;List<double[][]> outlineCurves=new ArrayList<>();
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
                        // Retain the complete original cubic. Native local clipping
                        // removes only depth-hidden coverage without re-stroking a
                        // numerically different tail or erasing the torso beneath it.
                        outline.m(b).c(c1,c2,c);outlined=true;
                        if(neckOcclusion&&h.neck!=null)outlineCurves.add(new double[][]{b,c1,c2,c});
                    }
                }
                p.z();
            }
            if(drawn)p.end();
            if(outlined){outline.end();JSONArray clipRings=neckOcclusion&&h.neck!=null?h.neck.mask(h,outlineCurves,weight):new JSONArray();if(clipRings.length()>0){JSONArray objects=h.ink.layer.getJSONArray("objects");objects.getJSONObject(objects.length()-1).put("clipOut",clipRings);}}
        }
        static boolean occluded(double x,double y,double depth,double[][] projected,List<int[]> facing){
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
