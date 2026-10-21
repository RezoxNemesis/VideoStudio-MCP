package com.rezoxnemesis.videostudio;

import android.graphics.Color;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/** Original adult cel heads, emitted as editable native Beziers, not a raster asset.
 * The caller owns the projected centre, head angle and Painter's 1/16 staging.
 * Only stroke weights undo staging here; the caller lifts coordinates as usual.
 * High/low are authored 2D anatomy, not a continuously rotating 3D head.
 */
final class SketchHumanFace {
    private SketchHumanFace() {}

    static void draw(JungleCelInk headDrawing, JSONObject destinationLayer, String actorId,
                     JSONObject settings, JSONObject pose, String view, double verticalScale) throws Exception {
        String style=settings.optString("faceStyle","angular");
        if(!style.equals("angular")&&!style.equals("soft"))throw new IllegalArgumentException("Unknown illustrated face style");
        if(!java.util.Set.of("front","left","right","back","high","low").contains(view))throw new IllegalArgumentException("Unknown face view");
        if(headDrawing.layer!=destinationLayer||!Double.isFinite(verticalScale)||verticalScale<=0)throw new IllegalArgumentException("Face drawing needs its destination and positive vertical scale");
        new Face(headDrawing,destinationLayer,actorId,settings,pose,view,verticalScale,style.equals("soft")).draw();
    }

    private static final class Face {
        final JungleCelInk d;
        final JSONObject layer,pose;
        final String actor,skin,hair,edge,crease,shade,light;
        final boolean soft,profile,back,high,low,longHair;
        final double side,vertical;
        final Map<String,Integer> serial=new HashMap<>();
        Face(JungleCelInk d,JSONObject layer,String actor,JSONObject s,JSONObject p,String view,double vertical,boolean soft){
            this.d=d;this.layer=layer;this.actor=actor;this.pose=p;this.soft=soft;this.vertical=vertical;
            profile=view.equals("left")||view.equals("right");back=view.equals("back");high=view.equals("high");low=view.equals("low");side=view.equals("left")?-1:1;
            skin=s.optString("skinColor","#FFD6A078");hair=s.optString("hairColor","#FF302D32");longHair=s.optString("hairStyle","short").equals("long");
            edge=tone(skin,-.55);crease=tone(skin,-.34);shade=tone(skin,-.12);light=tone(skin,.13);
        }
        Mark mark(String name,String fill,String stroke,double weight){return new Mark(name,d.ink(fill,stroke,weight*16));}
        void draw()throws Exception {
            if(back){rear();return;}
            if(profile)profile();else frontal();
            hair();
        }
        void rear()throws Exception {
            Mark head=mark("head",hair,tone(hair,-.24),.65);
            if(soft)head.m(-21,-12).c(-24,-27,-8,-32,4,-30).c(20,-29,25,-18,23,-2)
                .c(24,12,18,22,12,23).q(1,25,-11,23).c(-21,19,-25,6,-21,-12);
            else head.m(-20,-12).c(-23,-26,-10,-32,3,-30).c(19,-31,24,-20,22,-3)
                .c(23,12,16,21,9,25).q(2,22,-7,25).c(-16,19,-23,7,-20,-12);
            head.z().end();
            mark("nape",tone(hair,.09),null,1).m(-15,6).q(0,13,17,5).c(15,14,9,19,7,22).q(0,20,-7,23).q(-13,18,-15,6).z().end();
            if(longHair)mark("hair",hair,null,1).m(-20,-3).c(-24,10,-20,17,-23,25).q(-16,26,-10,24).q(0,27,9,24).q(17,26,22,22).c(19,12,24,1,20,-9).z().end();
            mark("hair_highlight",tone(hair,.12),null,1).m(-17,-17).c(-9,-31,11,-29,17,-16).c(6,-24,-7,-24,-17,-17).z().end();
            Mark strands=mark("hair_strands",null,tone(hair,.23),.36);
            if(soft)strands.m(2,-27).c(-12,-24,-20,-11,-17,9).m(4,-26).c(19,-20,23,-6,19,11).m(-8,-21).q(-16,-9,-13,14);
            else strands.m(-14,-17).c(-19,-7,-14,5,-14,12).m(-4,-24).c(3,-15,-3,1,1,10).m(9,-22).c(19,-13,14,1,15,11);
            strands.end();
        }
        void frontal()throws Exception {
            // Adult temporal narrowing, malar turn and distinct mandibular/chin shapes.
            ears();
            Mark h=mark("head",skin,edge,.62);
            if(soft)h.m(-19,-16).c(-22,-5,-20,8,-15,16).c(-11,22,-6,26,0,26).c(6,26,11,22,15,16).c(20,8,22,-6,18,-18).c(11,-29,-12,-28,-19,-16);
            else h.m(-20,-15).c(-22,-4,-19,4,-19,10).l(-14,20).q(-8,25,-4,26).l(5,26).q(13,22,16,17).c(19,9,22,-3,19,-17).c(9,-29,-12,-29,-20,-15);
            h.z().end();
            mark("cheek_shade",shade,null,1).m(-19,-7).c(-19,4,-16,12,-11,17).q(-5,23,0,25).q(-10,23,-15,17).q(-20,10,-20,0).z().end();
            mark("face_light",light,null,1).m(5,-18).q(14,-18,17,-9).q(9,-12,4,-8).q(2,-12,5,-18).z().end();
            double eyeY=high?3.2:low?-3.8:-.6;
            eye("eye_near",8.9,eyeY,soft?5.25:5.1);
            eye("eye_far",-9.1,eyeY+.35,soft?5:4.85);
            double tip=high?8.4:low?6.3:8.8;
            mark("nose",shade,null,1).m(.1,eyeY-1).c(-1,3,-.5,5.5,-3.3,tip-1).q(-3.6,tip+2,1.5,tip+2).q(5.8,tip+1,3,tip-1).q(1,5,.1,eyeY-1).z().end();
            mark("nose_light",tone(skin,.18),null,1).m(.8,1).q(.5,5,-.8,7.1).q(1.5,8.4,3.1,7.3).q(1.1,5,.8,1).z().end();
            mark("nose_detail",null,crease,.32).m(-3.5,tip+.7).q(-2.3,tip-.3,-1.5,tip+.7).m(2.5,tip+.9).q(4.3,tip-.1,4.5,tip-1).end();
            if(low){
                mark("nose_under",tone(skin,-.23),null,1).m(-3.2,7).q(0,9.8,4.5,6.8).q(3,11,-.2,10).z().end();
                mark("jaw_plane",shade,null,1).m(-12,20).q(0,27,13,19).q(7,28,-3,26).q(-9,25,-12,20).z().end();
            }
            if(high)mark("cheek_light",light,null,1).m(10,7).q(15,5,17,7).q(15,12,11,13).q(12,9,10,7).z().end();
            mouth(0,high?16:low?15:16.2,soft?6.6:7.2);
            mark("chin_light",light,null,1).m(-4,23).q(0,21.8,4.5,22.8).q(1,24,-3,24).z().end();
        }
        void ears()throws Exception {
            for(int sign:new int[]{-1,1}){
                double x=sign*20;
                mark("ears",skin,edge,.42).m(x-sign,-2).c(x+sign*6,-5,x+sign*5,5,x+sign*2.5,9).q(x,11,x-sign*1.4,6).z().end();
                mark("ear_detail",null,crease,.3).m(x+sign,1).q(x+sign*3.3,-.8,x+sign*2,4).q(x,3,x+sign*.7,6).end();
            }
        }
        void profile()throws Exception {
            // This is a separate sagittal silhouette: glabella, bridge, tip, philtrum,
            // lips, labiomental notch and chin. It is never a mirrored frontal head.
            double nose=soft?20.5:24,chin=soft?8.5:12,opening=unit("mouth");
            Mark head=mark("head",skin,edge,.62);
            if(soft)head.m(-13,-18).c(-4,-29,11,-27,14,-17).q(15.5,-12,14.5,-8)
                .c(15,-4,nose-2,1,nose,3).q(nose+1,5.5,nose-3,6).l(16,7).l(16,9.3)
                .q(18.3,10.3,17.8+opening*.5,11.5).q(16.5+opening*2,13.3,15.8+opening*2.4,13.2).q(15+opening*2,14.9,16+opening,16)
                .c(17,20,13,24,chin,25).q(2,27,-4,22).c(-13,16,-18,7,-16,-4).q(-17,-12,-13,-18);
            else head.m(-13,-18).c(-4,-29,12,-27,15,-17).q(17,-12,15.5,-8)
                .c(16,-4,nose-2,1,nose,3).q(nose+1,5.8,nose-4,6.3).l(17.4,7).l(17,9.3)
                .q(20,10.3,19,11.5).q(17.5+opening*1.2,13.3,16.2+opening*2.4,13.2).q(15.3+opening*2,14.9,17+opening*.7,16)
                .q(18,21,chin,25).q(4,27,-3,23).c(-13,18,-18,7,-16,-4).q(-17,-12,-13,-18);
            head.z().end();
            mark("cheek_shade",shade,null,1).m(-7,-5).c(-3,1,3,8,10,12).q(12,17,10,23).q(2,23,-6,14).q(-11,8,-7,-5).z().end();
            mark("cheek_light",light,null,1).m(2,4).q(8,1,13,5).q(11,10,7,10).z().end();
            mark("ear",skin,edge,.4).m(-7,-3).c(-16,-9,-16,3,-11,9).q(-8,12,-5,7).l(-4,0).z().end();
            mark("ear_detail",null,crease,.32).m(-11,-.6).q(-6,-3,-7,2).q(-12,1,-9,6).end();
            eye("eye_near",10,-3.5,soft?3.8:3.6);
            mark("nose_detail",null,crease,.32).m(nose-5,5.4).q(nose-7,4.5,nose-8,6.5).end();
            mark("nose_light",light,null,1).m(16,-5).q(18,0,nose-1,3).l(nose-3,3.5).q(17,0,16,-5).z().end();
            // Keep the entire cavity within the lip/chin silhouette, including wide A.
            mouth(14.8,12.1,2.65);
        }
        void eye(String name,double x,double y,double w)throws Exception {
            double blink=unit("blink"),open=1-blink,brow=signed("brow"),gazeX=signed("gazeX"),gazeY=signed("gazeY");
            double eyeHeight=profile?.74:1,up=(soft?4.4:4)*open*eyeHeight,down=2.6*open*eyeHeight;
            mark(name+"_socket",shade,null,1).m(x-w-1,y-.4).q(x,y-4.5,x+w+1,y-.3).q(x,y-2.6,x-w-1,y-.4).z().end();
            if(open>0){
                mark(name,"#FFF5EBDD",null,1).m(x-w,y).q(x,y-up,x+w,y)
                    .q(x,y+down,x-w,y).z().end();
                // A round adult iris is naturally occluded by the upper/lower lids.
                // Bake the actual intersection into editable paths, rather than
                // reducing the whole iris to a dot to fit the aperture's height.
                double ix=x+gazeX*w*.19,iy=y+(-.24+gazeY*.45)*open;
                double radius=w*.4;
                eyePigment(name+"_iris",soft?"#FF5F5242":"#FF42565A",x,y,w,up,down,ix,iy,radius,radius,open);
                eyePigment(name+"_pupil",tone(hair,-.4),x,y,w,up,down,ix,iy,radius*.42,radius*.42,open);
                eyePigment(name+"_glint","#FFFDF7E9",x,y,w,up,down,ix-radius*.22,iy-.26*open,radius*.07,.11*open,open);
                mark(name+"_lid",null,edge,.46).m(x-w,y).q(x,y-up,x+w,y).end();
                mark(name+"_lower_lid",null,crease,.3).m(x-w*.8,y+down*.15).q(x,y+down*.7,x+w*.8,y+down*.15).end();
            }else mark(name+"_closed_lid",null,edge,.48).m(x-w,y).q(x,y+1.25,x+w,y-.1).end();
            double lift=brow*2.5,inner=profile?-1:(x>0?-1:1),by=y-5.1-lift;
            mark(name+"_brow",hair,null,1).m(x-w,by+inner*brow).q(x-w*.15,by-1.8,x+w,by-inner*brow+.2)
                .l(x+w,by-inner*brow+1).q(x,by-.15,x-w,by+inner*brow+.65).z().end();
            mark(name+"_crease",null,crease,.28).m(x-w*.8,y-up*.5-1.8).q(x,y-up*.5-3,x+w*.75,y-up*.5-1.6).end();
        }
        /** Convex ellipse/lid intersection. The quadratic's control X is exactly
         * the midpoint, so Y(x) is analytic. Both the ellipse and parabolic lens
         * are convex: chords between sampled interior points cannot leave either.
         * The inset scales with opening, retaining precision near full closure.
         * No Android clipping state, raster mask or private drawing helper is saved.
         */
        void eyePigment(String name,String color,double x,double y,double w,double up,double down,
                        double cx,double cy,double rx,double ry,double open)throws Exception {
            final int samples=64;
            double inset=.01*open,left=Math.max(x-w+inset,cx-rx),right=Math.min(x+w-inset,cx+rx);
            double[] xs=new double[samples+1],tops=new double[samples+1],bottoms=new double[samples+1];int count=0;
            for(int i=0;i<=samples;i++){
                double px=left+(right-left)*i/samples,dx=(px-x)/w,ex=(px-cx)/rx;
                double half=ry*Math.sqrt(Math.max(0,1-ex*ex));
                double top=Math.max(cy-half,y-up*.5*(1-dx*dx)+inset);
                double bottom=Math.min(cy+half,y+down*.5*(1-dx*dx)-inset);
                if(top<=bottom){xs[count]=px;tops[count]=top;bottoms[count]=bottom;count++;}
            }
            if(count<2)return;
            Mark pigment=mark(name,color,null,1).m(xs[0],tops[0]);
            for(int i=1;i<count;i++)pigment.l(xs[i],tops[i]);
            for(int i=count-1;i>=0;i--)pigment.l(xs[i],bottoms[i]);
            pigment.z().end();
        }
        void mouth(double x,double y,double baseWidth)throws Exception {
            double open=unit("mouth"),smile=signed("smile"),wide=signed("mouthWide"),round=unit("mouthRound");
            double w=baseWidth*(1+.38*wide)*(1-.43*round)*(1+.06*smile);
            double h=(.24+open*(soft?2.6:2.8))*(1+.45*round)*(1-.12*smile);
            if(profile){w=Math.min(w,2.8);h*=.68;}
            double cy=y+.28*h-smile*.35;
            // Shear the ellipse's centre line for the profile; teeth and tongue use
            // the same affine shear, preserving strict containment under all smiles.
            double tilt=profile?-.16:0;
            if(open>.02){
                mouthOval("mouth","#FF57343A",crease,.34,x,cy,w,h,tilt);
                if(unit("mouthTeeth")>0)mouthOval("teeth","#FFF0E1CA",null,1,x,cy-h*.38,w*.71,h*.18*unit("mouthTeeth"),tilt);
                if(unit("mouthTongue")>0)mouthOval("tongue","#FFB56F74",null,1,x,cy+h*.36,w*.58,h*.25*unit("mouthTongue"),tilt);
            }else mark("mouth",null,tone(skin,-.47),.48).m(x-w,y-smile*.95).c(x-w*.3,y+.6+smile*.6,x+w*.3,y+.6+smile*.6,x+w,y-smile*.95).end();
            double upper=cy-h;
            mark("upper_lip",null,tone(skin,-.3),.32).m(x-w*.85,upper+.15).q(x-w*.25,upper-.65,x,upper-.15)
                .q(x+w*.22,upper-.65,x+w*.85,upper+.15).end();
            mark("lower_lip",null,tone(skin,-.22),.32).m(x-w*.55,cy+h+1).q(x,cy+h+1.75,x+w*.55,cy+h+.8).end();
            // Corner motion remains visible during speech rather than ignoring smile.
            for(int sign:new int[]{-1,1})mark("mouth_corner",null,crease,.3).m(x+sign*w,cy).q(x+sign*(w+.6),y-smile*1.1,x+sign*(w+.3),y-smile*1.1-.35).end();
        }
        void mouthOval(String name,String fill,String stroke,double weight,double x,double y,double rx,double ry,double shear)throws Exception {
            double k=.55228475;
            mark(name,fill,stroke,weight).m(x-rx,y-shear*rx)
                .c(x-rx,y-ry*k-shear*rx,x-rx*k,y-ry-shear*rx*k,x,y-ry)
                .c(x+rx*k,y-ry+shear*rx*k,x+rx,y-ry*k+shear*rx,x+rx,y+shear*rx)
                .c(x+rx,y+ry*k+shear*rx,x+rx*k,y+ry+shear*rx*k,x,y+ry)
                .c(x-rx*k,y+ry-shear*rx*k,x-rx,y+ry*k-shear*rx,x-rx,y-shear*rx).z().end();
        }
        void oval(String name,String fill,String stroke,double weight,double x,double y,double rx,double ry)throws Exception {
            mouthOval(name,fill,stroke,weight,x,y,rx,ry,0);
        }
        void hair()throws Exception {
            if(profile){
                Mark h=mark("hair",hair,tone(hair,-.23),.55);
                if(soft)h.m(-12,6).c(-23,0,-24,-21,-11,-28).c(0,-36,15,-30,17,-19).q(16,-15,14,-12)
                    .q(11,-14,8,-21).q(1,-17,-4,-18).q(-7,-12,-7,-6).l(-11,-3).z();
                else h.m(-12,6).c(-23,-2,-24,-19,-14,-26).c(-2,-36,14,-32,18,-22).q(18,-18,14,-15)
                    .q(7,-17,4,-22).q(-2,-16,-8,-15).l(-9,-5).l(-12,-3).z();
                h.end();
                mark("hair_highlight",tone(hair,.13),null,1).m(-18,-14).c(-16,-28,1,-30,12,-25).q(2,-27,-6,-22).q(-13,-19,-18,-14).z().end();
                mark("hair_strands",null,tone(hair,.25),.35).m(-18,-9).c(-18,-20,-7,-26,1,-26).m(-14,-7).q(-12,-16,-7,-17).m(6,-26).q(14,-23,14,-20).end();
            }else if(soft){
                mark("hair",hair,tone(hair,-.23),.55).m(-20,5).c(-26,-3,-25,-22,-13,-27).c(-4,-32,6,-31,11,-27)
                    .c(22,-25,26,-11,21,5).l(18,8).q(20,-7,15,-17).q(9,-18,4,-24)
                    .c(-1,-18,-12,-19,-16,-9).q(-17,-2,-16,3).z().end();
                mark("hair_highlight",tone(hair,.14),null,1).m(-21,-12).q(-20,-26,-5,-27).l(1,-26).q(-12,-24,-17,-15).z().end();
                mark("hair_strands",null,tone(hair,.24),.34).m(3,-27).c(-8,-29,-19,-21,-20,-10).m(5,-26).q(20,-23,21,-11).m(-3,-25).q(-12,-21,-15,-14).end();
            }else{
                mark("hair",hair,tone(hair,-.23),.55).m(-20,4).c(-26,-8,-23,-23,-12,-28).c(0,-35,17,-30,21,-20)
                    .q(25,-13,20,-4).l(17,-7).q(17,-15,12,-19).c(8,-13,-3,-11,-10,-14)
                    .l(-12,-17).q(-18,-10,-17,2).z().end();
                mark("hair_highlight",tone(hair,.14),null,1).m(-19,-18).c(-10,-31,6,-31,15,-24).c(6,-27,-4,-25,-9,-21).q(-14,-18,-19,-18).z().end();
                mark("hair_strands",null,tone(hair,.26),.34).m(-19,-13).c(-14,-24,-1,-29,10,-26).m(-14,-14).q(-4,-20,4,-22).m(14,-24).q(20,-20,20,-14).end();
            }
            if(high)mark("crown",tone(hair,.1),null,1).m(-13,-27).q(1,-33,14,-26).q(4,-28,-2,-24).q(-7,-25,-13,-27).z().end();
        }
        double unit(String key){return bounded(key,0,1);}
        double signed(String key){return bounded(key,-1,1);}
        double bounded(String key,double min,double max){double n=pose.optDouble(key,0);return Double.isFinite(n)?Math.max(min,Math.min(max,n)):0;}
        final class Mark {
            final String name;final JungleCelInk.Shape path;
            Mark(String name,JungleCelInk.Shape path){this.name=name;this.path=path;}
            Mark m(double x,double y)throws Exception{path.m(x*side,y*vertical);return this;}
            Mark l(double x,double y)throws Exception{path.l(x*side,y*vertical);return this;}
            Mark q(double a,double b,double c,double e)throws Exception{path.q(a*side,b*vertical,c*side,e*vertical);return this;}
            Mark c(double a,double b,double c,double e,double f,double g)throws Exception{path.c(a*side,b*vertical,c*side,e*vertical,f*side,g*vertical);return this;}
            Mark z(){path.z();return this;}
            void end()throws Exception{
                JSONArray objects=layer.getJSONArray("objects");int start=objects.length();path.end();int next=serial.getOrDefault(name,0);
                for(int i=start;i<objects.length();i++){
                    JSONObject o=objects.getJSONObject(i);
                    // Fill and outline must not alias when the parent lifts staging.
                    o.put("commands",new JSONArray(o.getJSONArray("commands").toString()));
                    o.put("id",actor+"_"+name+"_"+(next++));
                }
                serial.put(name,next);
            }
        }
    }
    private static String tone(String color,double value){
        int c=Color.parseColor(color),goal=value<0?0:255;double t=Math.abs(value);
        return String.format(Locale.ROOT,"#%02X%02X%02X%02X",Color.alpha(c),(int)Math.round(Color.red(c)*(1-t)+goal*t),
            (int)Math.round(Color.green(c)*(1-t)+goal*t),(int)Math.round(Color.blue(c)*(1-t)+goal*t));
    }
}
