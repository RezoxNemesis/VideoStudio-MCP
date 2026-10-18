package com.rezoxnemesis.videostudio;
import org.json.*;

/** Authored planted-foot exchange. Blade and raised claw share actual drawn contact. */
final class JungleActionDrawing {
    static final double FLOOR=600,SCALE=1.1,CONTACT_X=737,CONTACT_Y=477;
    static final String INK="#142d37";
    static final class Pose {
        double root,hipY,shoulderX,shoulderY,handX,handY,blade,lean,hit;
    }
    static Pose pose(int frame){
        double t=frame/24d;
        // Three exposed contact drawings hold the weapon; recoil follows the hold.
        if(frame>=43&&frame<=45)t=43/24d;
        double wind=JungleActingDrawing.ease(t,.55,1.28),strike=JungleActingDrawing.ease(t,1.28,43/24d),recoil=JungleActingDrawing.ease(t,1.92,2.5),recover=JungleActingDrawing.ease(t,2.7,4.25);
        Pose p=new Pose();p.root=450-15*wind+53*strike-15*recoil-21*recover;p.hipY=-137+6*wind-3*strike+2*recoil;
        p.shoulderX=12-10*wind+32*strike-8*recoil-10*recover;p.shoulderY=-236+6*wind+5*strike-3*recoil;
        double a=Math.toRadians(15),tipx=SCALE*(138*Math.cos(a)+18*Math.sin(a)),tipy=SCALE*(138*Math.sin(a)-18*Math.cos(a));
        double goalx=CONTACT_X-tipx,goaly=CONTACT_Y-tipy;
        p.handX=492-80*wind+(goalx-412)*strike-19*recoil+(492-goalx+19)*recover;
        p.handY=365-60*wind+(goaly-305)*strike-31*recoil+(365-goaly+31)*recover;
        p.blade=-66-54*wind+135*strike-25*recoil-56*recover;
        p.lean=-2-6*wind+16*strike-4*recoil-4*recover;
        p.hit=frame>=43&&frame<=45?1:0;
        return p;
    }
    static void draw(JSONObject doc,int frame)throws Exception {
        SketchDocument.layer(doc,"foreground").put("visible",true);
        double t=frame/24d;Pose p=pose(frame);
        for(String layer:new String[]{"hero","dragon","effects","back_fx"})SketchDocument.layer(doc,layer).put("objects",new JSONArray());
        JSONObject hero=SketchDocument.layer(doc,"hero"),dragon=SketchDocument.layer(doc,"dragon");
        JungleCelInk body=new JungleCelInk(hero),beast=new JungleCelInk(dragon),fx=new JungleCelInk(SketchDocument.layer(doc,"effects")),ground=new JungleCelInk(SketchDocument.layer(doc,"back_fx"));
        ground.ellipse("#5c102b2d",null,1,464,604,106,11);ground.ellipse("#44142f2b",null,1,992,590,177,19);
        double retreat=JungleActingDrawing.ease(t,2.5,4.8),clawX=CONTACT_X+82*retreat,clawY=CONTACT_Y+16*retreat;
        JungleFilmDragon.drawGuard(beast,1000,577,1.09,t,clawX,clawY);
        JungleCelInk d=body.at(p.root,FLOOR,SCALE,0);double[] hip={0,p.hipY},shoulder={p.shoulderX,p.shoulderY};
        double[] left={(370-p.root)/SCALE,0},right={(543-p.root)/SCALE,0};
        double[] lk=joint(hip,left,89,83,-1),rk=joint(hip,right,89,83,-1);
        limb(d,hip,lk,15,12,"#203748","#152c3b");limb(d,lk,left,12,8,"#2c4757","#19313e");boot(d,left[0],0,-1,"boot_left");
        double[] rearShoulder={shoulder[0]-34,shoulder[1]+7},rearHand={-67+12*JungleActingDrawing.ease(t,1.3,1.8),-183};
        double[] re=joint(rearShoulder,rearHand,70,68,-1);limb(d,rearShoulder,re,12,10,"#8eaa98","#4b6c67");limb(d,re,rearHand,10,7,"#2d4854","#1b3543");
        d.ink(JungleActingDrawing.SKIN,INK,.8).m(rearHand[0]-7,rearHand[1]-2).q(rearHand[0]-16,rearHand[1]+1,rearHand[0]-13,rearHand[1]+12).l(rearHand[0]-3,rearHand[1]+19).l(rearHand[0]+6,rearHand[1]+11).l(rearHand[0]+7,rearHand[1]+1).z().end();
        limb(d,hip,rk,16,13,"#2b4354","#1b3040");limb(d,rk,right,13,9,"#355563","#1c3545");boot(d,right[0],0,1,"boot_right");
        double lag=Math.sin(t*5.3)*2+7*JungleActingDrawing.ease(t,1.5,1.9)*(1-JungleActingDrawing.ease(t,2.1,3.4));
        d.ink("#b87a46",INK,1.2).m(shoulder[0]-8,shoulder[1]-3).c(-34,shoulder[1]-14,-80,shoulder[1]-29-lag,-114,shoulder[1]+8-lag).l(-95,shoulder[1]+20-lag).q(-69,shoulder[1]-3,-7,shoulder[1]+12).z().end();
        d.ink(null,"#e6bb7f",.6).m(-16,shoulder[1]+2).q(-67,shoulder[1]-12,-102,shoulder[1]+6-lag).end();
        double sy=shoulder[1],sx=shoulder[0],hy=hip[1];
        d.ink("#1d3445",INK,1.3).m(sx-33,sy).q(sx,sy-9,sx+28,sy+1).l(31,hy+12).q(-2,hy+22,-36,hy+10).z().end();
        d.ink("#d9d0ad",INK,1.1).m(sx-33,sy).l(sx-11,sy+14).l(-3,hy-7).q(-19,hy+26,-53,hy+37).q(-33,hy-3,sx-33,sy).z().end();
        d.ink("#f0e4bd",INK,1.1).m(sx+9,sy+9).l(sx+28,sy+1).q(25,hy-14,51,hy+26).l(20,hy+40).l(4,hy-8).z().end();
        d.ink("#8ba390",null,1).m(sx-28,sy+21).l(sx-19,sy+18).l(-14,hy+7).l(-44,hy+29).q(-25,hy-6,sx-28,sy+21).z().end();
        d.ink(null,"#788f83",.6).m(sx-20,sy+34).q(-17,hy-16,-28,hy+15).m(sx+19,sy+35).q(15,hy-21,32,hy+17).end();
        d.poly("#79533e",INK,.8,-32,hy-6,32,hy-5,33,hy+4,-33,hy+5);d.ellipse("#ba9a5c",INK,.7,8,hy,7,5);d.poly("#294f54",null,1,4,hy-2,12,hy-2,12,hy+2,4,hy+2);
        d.ink("#344e5a",INK,1).m(sx+13,sy-3).q(sx+39,sy-8,sx+48,sy+13).l(sx+35,sy+25).l(sx+9,sy+14).z().end();d.ink(null,"#8fa99a",.7).m(sx+20,sy+3).q(sx+34,sy+3,sx+40,sy+14).end();
        double[] armStart={sx+26,sy+14},hand={(p.handX-p.root)/SCALE,(p.handY-FLOOR)/SCALE},elbow=joint(armStart,hand,80,74,1);
        limb(d,armStart,elbow,12,10,"#8da38e","#476567");limb(d,elbow,hand,10,7,"#2f4858","#1c3444");
        d.ink(JungleActingDrawing.SHADE,INK,.8).m(sx-7,sy-28).l(sx-8,sy+4).q(sx+1,sy+10,sx+9,sy+3).l(sx+8,sy-29).z().end();
        JungleActingDrawing.Acting focus=new JungleActingDrawing.Acting();focus.yaw=.4;focus.gaze=.6;
        JungleActingDrawing.face(d.at(sx+1,sy-43,.275,p.lean*.35),focus,0,t);
        d.ink("#bf8b4f",INK,.8).m(sx-19,sy-4).q(sx,sy+7,sx+23,sy-2).l(sx+22,sy+12).q(sx,sy+18,sx-20,sy+8).z().end();
        // Weapon is drawn from the hand's actual world position and angle.
        JSONObject weapon=new JSONObject().put("id","weapon").put("name","Blade and grip · actual contact").put("visible",true).put("locked",false).put("opacity",1).put("transform",SketchDocument.transform()).put("keys",new JSONArray()).put("objects",new JSONArray());doc.getJSONArray("layers").put(weapon);
        JungleCelInk sword=new JungleCelInk(weapon).at(p.handX,p.handY,SCALE,p.blade);int before=weapon.getJSONArray("objects").length();
        sword.poly("#c3d5d6",INK,.85,7,-4,107,-10,138,-18,111,-1,8,4);tag(weapon,before,"sword_blade");sword.poly("#eff3d4",null,1,7,-4,107,-10,138,-18,111,-7,8,0);sword.line("#648b96",.5,10,1,111,-4);sword.poly("#ab8b52",INK,.75,-1,-11,6,-10,9,11,2,13);sword.poly("#644a37",INK,.75,-26,-4,0,-3,1,4,-26,5);
        for(int n=0;n<4;n++)sword.line("#beaa77",.5,-22+n*5,-3,-20+n*5,4);
        sword.ink(JungleActingDrawing.SKIN,INK,.7).m(-16,-8).q(-9,-12,-3,-8).l(2,-3).q(6,-7,10,-1).l(7,7).q(-6,13,-16,4).z().end();for(int n=0;n<3;n++)sword.line("#916c58",.45,-10+n*4,-3,-9+n*4,5);
        if(p.hit>0)SketchJungleFilm.impact(fx,CONTACT_X,CONTACT_Y,t,.8);
        if(frame>=46&&frame<52){double v=(frame-46)/6d;for(int n=0;n<7;n++)fx.line(n%2==0?"#ead1a0":"#a9d1bd",.8,CONTACT_X+n*3,CONTACT_Y-4,CONTACT_X-20+v*95+n*6,CONTACT_Y-30+v*22+n*8);}
    }
    private static double[] joint(double[] a,double[] end,double upper,double lower,int bend){
        double dx=end[0]-a[0],dy=end[1]-a[1],distance=Math.hypot(dx,dy),length=Math.max(Math.abs(upper-lower)+.001,Math.min(upper+lower-.001,distance));
        double nx=dx/Math.max(.001,distance),ny=dy/Math.max(.001,distance),along=(upper*upper-lower*lower+length*length)/(2*length),height=Math.sqrt(Math.max(0,upper*upper-along*along));
        return new double[]{a[0]+nx*along-ny*height*bend,a[1]+ny*along+nx*height*bend};
    }
    private static void limb(JungleCelInk d,double[] a,double[] b,double wa,double wb,String fill,String shade)throws Exception {
        double dx=b[0]-a[0],dy=b[1]-a[1],len=Math.max(1,Math.hypot(dx,dy)),nx=-dy/len,ny=dx/len;
        d.ink(fill,INK,1).m(a[0]+nx*wa,a[1]+ny*wa).q(a[0]+dx*.48+nx*wa,a[1]+dy*.48+ny*wa,b[0]+nx*wb,b[1]+ny*wb).q(b[0]+dx*.1,b[1]+dy*.1,b[0]-nx*wb,b[1]-ny*wb).l(a[0]-nx*wa,a[1]-ny*wa).z().end();
        d.poly(shade,null,1,a[0]-nx*wa,a[1]-ny*wa,b[0]-nx*wb,b[1]-ny*wb,b[0]+nx*wb*.1,b[1]+ny*wb*.1,a[0]-nx*wa*.1,a[1]-ny*wa*.1);
    }
    private static void boot(JungleCelInk d,double x,double y,int facing,String id)throws Exception {
        int before=d.layer.getJSONArray("objects").length();d.ink("#1a303e",INK,.9).m(x-10,y-26).l(x+10,y-26).l(x+11,y-8).q(x+23*facing,y-8,x+26*facing,y+1).l(x-13*facing,y+1).l(x-11*facing,y-10).z().end();tag(d.layer,before,id);d.line("#768b89",.6,x-7,y-21,x+7,y-21);d.line("#b3b39a",.4,x-12*facing,y-1,x+23*facing,y-1);
    }
    static void tag(JSONObject layer,int from,String id)throws Exception {JSONArray objects=layer.getJSONArray("objects");for(int i=from;i<objects.length();i++)objects.getJSONObject(i).put("id",id+"_"+(i-from));}
}
