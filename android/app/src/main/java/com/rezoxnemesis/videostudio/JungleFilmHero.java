package com.rezoxnemesis.videostudio;

/** Original character construction, independently redrawn anatomy and facial acting. */
final class JungleFilmHero {
    static final String INK="#102d36",SKIN="#d7a47e",SHADOW="#9b6557",HAIR="#122e3c";
    static double[] body(JungleCelInk world,double x,double ground,double scale,double t,String action,double p,double yaw,String emotion,boolean sword,double mouth)throws Exception {
        JungleCelInk d=world.at(x,ground,scale,0);double breathe=1.7*Math.sin(t*2.3),lean=0;
        double[] hip={0,-87},leftK={-24,-44},leftA={-44,0},rightK={40,-40},rightA={65,0};
        double[] shoulder={15,-151},elbow={42,-116},hand={76,-93},backE={-48,-113},backH={-32,-77};
        double headTilt=-3,bladeAngle=-30;
        if(action.equals("run")){double wave=Math.sin(p*Math.PI*2),other=Math.sin(p*Math.PI*2+Math.PI);lean=16;leftK=new double[]{wave*37,-48+Math.max(0,wave)*19};leftA=new double[]{-wave*66,-Math.max(0,wave)*31};rightK=new double[]{other*37,-48+Math.max(0,other)*19};rightA=new double[]{-other*66,-Math.max(0,other)*31};elbow=new double[]{34+wave*15,-122};hand=new double[]{45+wave*29,-152};backE=new double[]{-31-wave*15,-115};backH=new double[]{-47-wave*23,-80};bladeAngle=38;}
        else if(action.equals("dodge")){hip=new double[]{-8,-55};leftK=new double[]{-49,-24};leftA=new double[]{-84,0};rightK=new double[]{48,-35};rightA=new double[]{98,0};lean=24;shoulder=new double[]{12,-115};elbow=new double[]{52,-117};hand=new double[]{72,-160};backE=new double[]{-70,-80};backH=new double[]{-101,-64};headTilt=9;bladeAngle=-95;}
        else if(action.equals("slash")){double u=curve(p);lean=-18+42*u;leftK=new double[]{-42,-45};leftA=new double[]{-73,0};rightK=new double[]{51,-49};rightA=new double[]{92,0};double a=Math.toRadians(-135+185*u);elbow=new double[]{26+46*Math.cos(a),-149+43*Math.sin(a)};hand=new double[]{28+99*Math.cos(a),-145+94*Math.sin(a)};backE=new double[]{-39,-113};backH=new double[]{-55,-86};bladeAngle=-110+175*u;headTilt=-8+8*u;}
        else if(action.equals("guard")){lean=-8;leftK=new double[]{-28,-43};leftA=new double[]{-61,0};rightK=new double[]{39,-45};rightA=new double[]{70,0};elbow=new double[]{52,-135};hand=new double[]{57,-183};backE=new double[]{-4,-112};backH=new double[]{43,-169};bladeAngle=-70;headTilt=-7;}
        else if(action.equals("leap")){lean=7;leftK=new double[]{-43,-80};leftA=new double[]{-65,-32};rightK=new double[]{40,-64};rightA=new double[]{72,-81};elbow=new double[]{49,-199};hand=new double[]{25,-238};backE=new double[]{-55,-150};backH=new double[]{-81,-182};bladeAngle=-55;}
        else if(action.equals("charge")){lean=7;leftK=new double[]{-39,-43};leftA=new double[]{-67,0};rightK=new double[]{48,-40};rightA=new double[]{80,0};elbow=new double[]{49,-115};hand=new double[]{67,-126};backE=new double[]{-38,-110};backH=new double[]{-13,-135};bladeAngle=-23;}
        else if(action.equals("kneel")){hip=new double[]{0,-48};leftK=new double[]{-45,-2};leftA=new double[]{-22,0};rightK=new double[]{42,-18};rightA=new double[]{66,0};lean=9;shoulder=new double[]{13,-107};elbow=new double[]{41,-64};hand=new double[]{47,-24};backE=new double[]{-33,-66};backH=new double[]{-29,-25};headTilt=18;}
        else if(action.equals("retreat")){double wave=Math.sin(p*Math.PI*2);leftA=new double[]{-24+wave*25,0};rightA=new double[]{29-wave*25,0};leftK=new double[]{-19+wave*12,-43};rightK=new double[]{25-wave*12,-43};elbow=new double[]{34,-113};hand=new double[]{40,-76};headTilt=14;bladeAngle=65;}
        // Draw the rear limbs before the coat; every segment has a tapered cloth contour.
        limb(d,hip,leftK,15,13,"#243c52","#162c40");limb(d,leftK,leftA,12,9,"#29465b","#182f43");boot(d,leftA[0],leftA[1],-1);
        arm(d,-20,shoulder[1]+4,backE,backH,true);
        // Moving scarf is redrawn as a flowing curve rather than transformed as a card.
        double flow=9*Math.sin(t*5.5),tail=action.equals("run")||action.equals("slash")?115:76;
        d.ink("#cf754a",INK,1.9).m(-7,shoulder[1]-9).c(-35,shoulder[1]-19,-63,shoulder[1]-35-flow,-tail,shoulder[1]-9-flow).q(-tail+27,shoulder[1]+5,-tail+18,shoulder[1]+25).c(-62,shoulder[1]+9,-40,shoulder[1]-7,-12,shoulder[1]+5).z().end();
        d.ink(null,"#f5b368",1.2).m(-15,shoulder[1]-7).q(-63,shoulder[1]-19,-tail+7,shoulder[1]+2-flow).end();
        limb(d,hip,rightK,16,14,"#30495e","#182f43");limb(d,rightK,rightA,13,9,"#38576a","#20374b");boot(d,rightA[0],rightA[1],1);
        double top=shoulder[1]-5,bottom=hip[1]+16,shift=Math.sin(Math.toRadians(lean))*29;
        d.ink("#1b354a",INK,2).m(-29+shift,top).q(5+shift,top-9,32+shift,top+2).l(36,bottom+7).q(0,bottom+24,-38,bottom+6).z().end();
        if(yaw>.7){d.ink("#c6c5a3",INK,1.9).m(-28+shift,top).q(5+shift,top-9,32+shift,top+2).l(29,bottom-9).l(46,bottom+25).l(7,bottom+18).l(-5,bottom+32).l(-47,bottom+31).q(-29,bottom-6,-28+shift,top).z().end();d.ink(null,"#788e80",1.5).m(2+shift,top+14).l(1,bottom-3).m(-21+shift,top+8).q(2,top+25,25+shift,top+8).end();}
        else {
        d.ink("#e4d6ad",INK,1.9).m(-28+shift,top).l(-5+shift,top+7).l(3,bottom-9).l(-13,bottom+25).l(-47,bottom+31).q(-29,bottom-6,-28+shift,top).z().end();
        d.ink("#f4e8c8",INK,1.8).m(10+shift,top+3).l(32+shift,top+1).q(25,bottom-15,46,bottom+21).l(23,bottom+30).l(6,bottom-9).z().end();
        d.poly("#9bb19b",null,1,-25+shift,top+15,-16+shift,top+16,-22,bottom-4,-37,bottom+22);
        d.poly("#657e75",INK,1.2,2,top+12,11,top+14,18,bottom-16,7,bottom-10);
        d.ink(null,"#7d9687",1.2).m(-20,bottom+6).q(-9,bottom-8,-11,top+30).end();
        }
        d.poly("#a15d41",INK,1.4,-32,hip[1]-6,32,hip[1]-6,35,hip[1]+7,-35,hip[1]+6);
        if(yaw<=.7){d.ellipse("#d7ad63",INK,1,7,hip[1],9,7);d.ellipse("#476863",null,1,7,hip[1],4,3);}
        d.ink("#354e61",INK,1.5).m(12+shift,top-3).q(33+shift,top-13,46+shift,top+9).l(35+shift,top+23).l(14+shift,top+15).z().end();
        d.ink(null,"#9cae9d",1.3).m(19+shift,top+1).q(32+shift,top-3,40+shift,top+9).end();
        arm(d,shoulder[0]+shift,shoulder[1]+breathe,elbow,hand,false);
        double hx=shift+2,hy=top-30+breathe;
        if(yaw>.7){backHead(d.at(hx,hy,1,headTilt),t);}else face(d.at(hx,hy,1,headTilt),emotion,yaw,t,mouth);
        // Neck wrap crosses the jaw only at its base and keeps the head attached.
        d.ink("#d08a50",INK,1.2).m(-14+shift,top-9).q(1+shift,top-2,19+shift,top-9).l(18+shift,top+7).q(1+shift,top+13,-15+shift,top+3).z().end();
        if(sword)blade(d,hand[0],hand[1],bladeAngle,action.equals("charge")||action.equals("slash")&&t>39);
        // Grip is inked over the handle, maintaining hand/weapon contact on every cel.
        d.ellipse("#c99570",INK,1.2,hand[0],hand[1],7.5,6);for(int f=0;f<3;f++)d.line("#875946",.7,hand[0]-4+f*3,hand[1]-3,hand[0]-3+f*3,hand[1]+3);
        double a=Math.toRadians(bladeAngle),tx=hand[0]+138*Math.cos(a)+18*Math.sin(a),ty=hand[1]+138*Math.sin(a)-18*Math.cos(a);return new double[]{d.x(tx,ty)*1280,d.y(tx,ty)*720};
    }
    static void portrait(JungleCelInk d,double t,String emotion,double yaw,double mouth)throws Exception {
        d.ink("#263e52",INK,1.3).m(-94,130).q(-83,38,-34,23).q(0,8,37,24).q(90,45,103,130).z().end();
        d.poly("#e2d4ad",INK,1,-94,130,-68,49,-29,27,-16,76,-24,130);
        d.poly("#eee0b9",INK,1,24,130,12,77,31,26,72,52,102,130);
        d.ink(SHADOW,INK,1).m(-17,8).l(-19,39).q(0,65,20,36).l(17,6).z().end();
        face(d.at(0,-14,1.55,emotion.equals("shame")?9:emotion.equals("realize")?-2:-5),emotion,yaw,t,mouth);
        d.ink("#c57b4a",INK,1).m(-37,36).q(0,62,37,35).l(30,63).q(0,81,-38,59).z().end();d.line("#eeb573",1.2,-29,48,0,64,28,47);
        if(emotion.equals("shame")){JungleCelInk h=d.at(31,37,1,-18);h.ink(SKIN,INK,1).m(-7,33).l(-4,88).l(12,88).l(18,33).z().end();h.ink(SKIN,INK,1).m(-7,34).l(-12,1).q(-13,-6,-8,-7).l(-5,15).l(-5,-12).q(-1,-17,2,-10).l(4,12).l(7,-13).q(12,-16,13,-9).l(14,13).l(18,-4).q(23,-8,24,0).l(18,34).z().end();h.ink(null,SHADOW,.7).m(-3,18).l(15,23).end();}
    }
    static void face(JungleCelInk d,String emotion,double yaw,double t,double mouth)throws Exception {
        double turn=Math.max(-.7,Math.min(.7,yaw)),offset=turn*17,blink=Math.pow(Math.max(0,Math.cos(t*1.7)),30);boolean shame=emotion.equals("shame"),realize=emotion.equals("realize"),angry=emotion.equals("angry");
        d.ink(SKIN,INK,1.4).m(-24,-26).c(-30,-13,-26,17,-13,30).l(3,38).q(22,31,27,12).q(34,-7,23,-28).q(0,-43,-24,-26).z().end();
        d.ink(SHADOW,null,1).m(-25,-18).q(-29,12,-13,28).l(3,36).l(7,30).q(-11,21,-12,-12).z().end();
        d.ellipse(SKIN,INK,1,-25,4,5,10);d.ink(null,SHADOW,.8).m(-27,0).q(-22,-2,-24,8).end();
        double ey=shame?6:0,open=blink>.7?.12:realize?1.25:shame?.64:angry?.65:1;
        eye(d,-11+offset,ey,turn>.3?5.5:8.5,open,angry,shame,turn,t);
        eye(d,13+offset*.4,ey,turn<-.3?5:8,open,angry,shame,turn,t);
        double brow=angry?5:shame?-4:realize?-3:0;
        d.ink(null,HAIR,2.2).m(-21+offset,-10-brow).q(-13+offset,-14,-3+offset,-9+brow).end();
        d.ink(null,HAIR,2.2).m(5+offset*.4,-9+brow).q(14+offset*.4,-15,23+offset*.4,-10-brow).end();
        d.ink(null,"#93604e",.85).m(4+offset,0).l(9+offset,13).l(4+offset,15).end();d.line("#f4c39c",.7,5+offset,1,7+offset,9);
        if(mouth>.15){double w=shame?6:8,h=3+mouth*5;d.ink("#56353b",INK,.8).m(-2,24).q(w,20,w+5,24).q(w+1,24+h,0,24+h-1).z().end();if(mouth<.65)d.ink("#f4ddc2",null,1).m(-1,24).q(7,22,11,24).l(10,26).l(1,26).z().end();}
        else if(shame){d.ink(null,"#613e40",1.3).m(-1,27).q(4,23,9,27).l(14,26).end();d.line("#a86c5c",.7,3,31,10,32);}
        else if(realize){d.ellipse("#694247",INK,.6,7,26,3,3.8);}
        else d.ink(null,"#714a48",1.1).m(-1,25).q(6,angry?24:28,14,24).end();
        d.ink(HAIR,INK,1.3).m(-29,3).l(-35,-12).l(-32,-33).l(-40,-40).l(-26,-43).l(-21,-59).l(-10,-50).l(2,-64).l(7,-51).l(27,-57).l(23,-45).l(39,-39).l(28,-31).l(35,-17).l(23,-9).l(18,-26).l(6,-14).l(8,-35).l(-6,-18).l(-10,-32).l(-21,-18).l(-21,5).z().end();
        d.ink(null,"#385465",1.1).m(-26,-34).q(-21,-44,-12,-40).m(-8,-43).l(1,-51).m(10,-39).l(25,-45).m(-20,-12).l(-21,-26).end();
        if(shame){d.ink(null,"#c5daca",.8).m(-10,9).q(-12,17,-10,22).end();d.line("#714d4e",.6,-18,17,-14,20);d.line("#714d4e",.6,17,17,20,19);}
    }
    private static void eye(JungleCelInk d,double x,double y,double width,double open,boolean angry,boolean shame,double gaze,double t)throws Exception {
        double high=4.5*open,low=3*open;d.ink("#ececda",INK,.9).m(x-width,y).q(x,y-high,x+width,y).q(x+2,y+low,x-width,y).z().end();
        if(open>.3){double gx=x+gaze*3,gy=y+(shame?1.5:0);d.ellipse("#b99347",null,1,gx,gy,2.9,Math.min(high,3.8));d.ellipse("#122a32",null,1,gx,gy,1.4,Math.min(high,3.2));d.ellipse("#ffffff",null,1,gx-1,gy-1,.85,.9);}
        d.ink(null,INK,1.2).m(x-width,y).q(x,y-high,x+width,y).end();
    }
    static void overhead(JungleCelInk world,double x,double y,double size,double t,double turn)throws Exception {
        JungleCelInk d=world.at(x,y,size,turn);d.ink("#d6d0ad",INK,2).m(-37,-17).q(0,-42,38,-17).l(51,70).q(12,56,0,73).q(-22,52,-49,77).z().end();d.ink("#82988a",null,1).m(-26,-10).l(-35,57).l(-4,48).l(0,-9).z().end();d.line(INK,1.3,0,-5,0,48);
        d.ink("#263e51",INK,1.7).m(-32,-17).l(-63,-41).l(-83,-29).l(-50,2).l(-28,9).z().end();d.ink("#3f5a6b",INK,1.7).m(30,-17).l(62,-36).l(75,-83).l(60,-87).l(48,-49).l(25,3).z().end();blade(d,67,-85,-70,true);d.ellipse(SKIN,INK,1,67,-85,7,6);
        d.ink("#c87946",INK,1.3).m(-14,-18).q(-69,-38,-91,-2).l(-77,18).q(-48,-14,-7,-8).z().end();d.ellipse(HAIR,INK,1.8,0,-23,25,29);d.poly(HAIR,INK,1,-25,-35,-30,-56,-16,-50,-5,-63,5,-49,23,-54,26,-34);d.ink(null,"#526a6f",1).m(-16,-34).q(0,-47,17,-32).end();
    }
    static void profile(JungleCelInk world,double x,double y,double size)throws Exception {
        JungleCelInk d=world.at(x,y,size,0);limb(d,new double[]{-6,-80},new double[]{-22,-43},12,10,"#263e52","#17313e");limb(d,new double[]{-22,-43},new double[]{-33,0},10,7,"#334f60","#233b4a");boot(d,-33,0,1);
        d.ink("#cf754a",INK,1.2).m(-6,-160).q(-69,-191,-100,-139).l(-77,-133).q(-55,-158,-9,-149).z().end();
        limb(d,new double[]{8,-83},new double[]{20,-43},13,10,"#304b60","#203744");limb(d,new double[]{20,-43},new double[]{28,0},10,7,"#385467","#203744");boot(d,28,0,1);
        d.ink("#d3d2ae",INK,1.7).m(-16,-154).q(3,-164,16,-150).l(15,-103).l(30,-61).l(4,-46).l(-23,-59).q(-18,-103,-16,-154).z().end();d.ink("#9bac95",null,1).m(-15,-143).l(-18,-60).l(0,-59).l(-2,-148).z().end();d.line("#6a8279",1.2,4,-139,9,-89,17,-64);
        d.poly("#a15d41",INK,1,-17,-94,19,-93,21,-83,-18,-84);arm(d,9,-144,new double[]{30,-117},new double[]{38,-84},false);
        JungleCelInk h=d.at(1,-179,1,0);h.ink(SKIN,INK,1.4).m(-20,-29).q(3,-42,20,-24).l(24,-5).l(35,6).l(23,10).q(31,17,21,21).l(20,30).l(4,37).q(-14,27,-17,12).z().end();h.ink(SHADOW,null,1).m(-18,-16).q(-22,18,4,35).l(2,22).q(-12,11,-9,-14).z().end();h.ink("#ececda",INK,1).m(7,-2).q(13,-8,23,-3).q(17,2,7,-2).z().end();h.ellipse("#b99547",null,1,18,-3,2.7,3.2);h.ellipse(INK,null,1,19,-3,1.2,3);h.line(INK,1.8,8,-12,22,-10);h.line("#714a48",1,21,20,28,19);h.ellipse(SKIN,INK,1,-11,2,5,9);
        h.poly(HAIR,INK,1.3,-18,9,-31,-4,-27,-30,-35,-37,-21,-40,-14,-55,-4,-46,7,-60,14,-44,29,-46,23,-29,28,-18,18,-21,8,-13,8,-29,-8,-16,-11,7);h.ink(null,"#42616a",1).m(-19,-31).q(-7,-43,10,-35).end();d.ink("#d08a50",INK,1.1).m(-10,-154).l(13,-158).l(18,-144).l(-9,-142).z().end();blade(d,38,-84,61,false);d.ellipse(SKIN,INK,1,38,-84,7,6);
    }
    private static void backHead(JungleCelInk d,double t)throws Exception {d.ink(HAIR,INK,1.5).m(-27,10).l(-35,-9).l(-31,-34).l(-19,-53).l(-5,-46).l(8,-59).l(13,-44).l(30,-46).l(28,-27).l(36,-17).l(25,3).l(17,27).l(-9,30).z().end();d.ink(null,"#3d5664",1).m(-21,-30).q(-8,-48,14,-39).m(-25,-9).q(-12,-21,21,-17).end();}
    private static void arm(JungleCelInk d,double sx,double sy,double[] elbow,double[] hand,boolean rear)throws Exception {limb(d,new double[]{sx,sy},elbow,13,10,rear?"#617b79":"#8d9d8a","#425e61");limb(d,elbow,hand,10,7,"#374f61","#20364a");double mx=(elbow[0]+hand[0])/2,my=(elbow[1]+hand[1])/2;d.ellipse("#9c7650",INK,.9,mx,my,10,6);d.ellipse(SKIN,INK,1,hand[0],hand[1],7,6);}
    private static void limb(JungleCelInk d,double[] a,double[] b,double wa,double wb,String color,String shade)throws Exception {double dx=b[0]-a[0],dy=b[1]-a[1],len=Math.max(1,Math.hypot(dx,dy)),nx=-dy/len,ny=dx/len;d.ink(color,INK,1.6).m(a[0]+nx*wa,a[1]+ny*wa).q(a[0]+dx*.5+nx*wa,a[1]+dy*.5+ny*wa,b[0]+nx*wb,b[1]+ny*wb).q(b[0]+dx*.12,b[1]+dy*.12,b[0]-nx*wb,b[1]-ny*wb).l(a[0]-nx*wa,a[1]-ny*wa).z().end();d.poly(shade,null,1,a[0]-nx*wa*.3,a[1]-ny*wa*.3,b[0]-nx*wb*.3,b[1]-ny*wb*.3,b[0]-nx*wb,b[1]-ny*wb,a[0]-nx*wa,a[1]-ny*wa);}
    private static void boot(JungleCelInk d,double x,double y,int side)throws Exception {d.ink("#172f3e",INK,1.3).m(x-10,y-22).l(x+9,y-21).l(x+9,y-6).q(x+25*side,y-4,x+26*side,y+3).l(x-13,y+4).z().end();d.line("#8c9f8d",1,x-7,y-17,x+7,y-16);}
    static void blade(JungleCelInk d,double x,double y,double angle,boolean glow)throws Exception {JungleCelInk b=d.at(x,y,1,angle);if(glow)b.ink(null,"#3546d8ed",12).m(7,0).q(62,-3,133,-15).end();b.poly("#ccdbe0",INK,1.1,7,-4,107,-10,138,-18,111,-1,8,4);b.poly("#f3fbdf",null,1,7,-4,107,-10,138,-18,111,-7,8,0);b.line("#6ea4b2",.8,11,1,111,-4);b.poly("#b58a4d",INK,1,-1,-12,6,-10,9,12,2,14);b.poly("#77563b",INK,1,-25,-4,0,-3,1,4,-25,5);for(int i=0;i<4;i++)b.line("#d3b47d",.7,-21+i*5,-3,-19+i*5,4);b.ellipse("#bca06a",INK,.8,-28,0,4,5);}
    private static double curve(double x){return x*x*(3-2*x);}
}
