package com.rezoxnemesis.videostudio;

/** Original emerald dragon anatomy, redrawn wing membranes, head turns and maternal acting. */
final class JungleFilmDragon {
    private static final String INK="#0a2c30",GREEN="#53815b",LIGHT="#89a56a",DARK="#284f49",BELLY="#bfa875";
    static void draw(JungleCelInk world,double x,double floor,double size,double t,String action,double phase,boolean cubVisible)throws Exception {
        JungleCelInk d=world.at(x,floor,size,0);double breath=3*Math.sin(t*1.9),bob=0,reach=0,wing=10*Math.sin(t*1.4),open=0;
        if(action.equals("lunge")){reach=-65*Math.sin(phase*Math.PI);bob=12*Math.sin(phase*Math.PI);open=.3+.5*Math.sin(phase*Math.PI);wing=-25*Math.sin(phase*Math.PI);}
        if(action.equals("breath")){reach=-30;bob=-6;open=.9;wing=-20;}
        if(action.equals("shield")){reach=35;bob=49;wing=57;open=.15;}
        if(action.equals("nuzzle")){reach=48;bob=86;wing=59;open=0;}
        if(action.equals("roar")){reach=-10;bob=-20;open=.75;wing=-35;}
        if(action.equals("tail")){wing=-6;open=.35;}
        // Tail volume: a tapering curve with a separate lit upper ridge.
        double sweep=action.equals("tail")?145*Math.sin(phase*Math.PI*2):15*Math.sin(t*1.3);
        d.ink(GREEN,INK,3).m(78,-118).c(174,-145,228,-50,302,-83+sweep*.3).c(364,-114+sweep*.3,329,-171+sweep*.6,395,-192+sweep).c(350,-123+sweep*.6,399,-75+sweep*.2,321,-42+sweep*.2).c(237,-8,177,-99,99,-57).z().end();
        d.ink(LIGHT,null,1).m(119,-118).c(198,-105,232,-55,302,-83+sweep*.3).q(340,-108+sweep*.3,351,-124+sweep*.5).c(334,-74+sweep*.3,286,-52+sweep*.2,241,-77).q(164,-119,119,-118).z().end();
        for(int i=0;i<8;i++){double a=i/7d,px=117+a*218,py=-111+Math.sin(a*Math.PI)*44+sweep*.22*a;d.poly("#b4ad78",INK,1.2,px-10,py,px-3,py-25*(1-a*.6),px+9,py+1);}
        leg(d,86,-84,139,-45,127,0,DARK,false);leg(d,-71,-72,-107,-42,-124,1,DARK,false);
        wing(d,22,-128,wing-8,true,t);
        d.ink(GREEN,INK,3).m(-127,-120).c(-83,-175,47,-186,108,-126).c(166,-74,115,-6,31,-22).c(-35,-19,-87,-33,-134,-67).z().end();
        d.ink(LIGHT,null,1).m(-111,-124).c(-49,-164,32,-163,85,-131).q(124,-110,120,-87).c(45,-119,-6,-98,-80,-81).z().end();
        d.ink(BELLY,INK,1.3).m(-102,-78).q(-34,-105,53,-58).q(32,-24,-4,-26).q(-60,-25,-104,-52).z().end();
        for(int i=0;i<5;i++)d.ink(null,"#897e5b",1.2).m(-90+i*27,-70+i*3).q(-81+i*27,-52,-81+i*27,-30).end();
        // Neck controls change with actual head position, preserving its root attachment.
        double hx=-185+reach,hy=-175+bob+breath;
        d.ink(GREEN,INK,3).m(-55,-128).c(-114,-143,hx+69,hy-16,hx+26,hy-22).l(hx-2,hy+28).c(hx+38,hy+75,-128,-17,-59,-48).q(-80,-75,-55,-128).z().end();
        d.ink(BELLY,INK,1.3).m(hx+1,hy+22).c(hx+27,hy+66,-121,-18,-72,-43).l(-94,-67).c(-132,-55,hx+40,hy+53,hx-2,hy+13).z().end();
        for(int i=0;i<5;i++){double a=i/5d,px=hx+17+a*72,py=hy+34+a*(-54-hy);d.ink(null,"#8b865c",1.1).m(px-4,py+4).q(px+9,py,px+23,py-3).end();}
        for(int i=0;i<7;i++){double a=i/6d,px=-59-a*99+reach*a*.5,py=-142-a*46+bob*a*.6;d.poly("#c1b88a",INK,1.2,px-10,py+8,px-14,py-20,px+9,py-2);}
        leg(d,66,-69,89,-39,76,2,GREEN,true);leg(d,-93,-81,-153,-38,-176,3,GREEN,true);
        if(action.equals("lunge")){double claw=-190-68*Math.sin(phase*Math.PI);d.ink(GREEN,INK,2).m(-108,-93).q(claw+18,-166,claw,-142).l(claw-20,-100).q(claw+18,-111,-94,-60).z().end();claws(d,claw-11,-104,-1);}
        if(cubVisible){cub(d,-48,-3,.55,t,action.equals("nuzzle")?"happy":"afraid");}
        wing(d,29,-132,wing,false,t);
        head(d.at(hx,hy,1,action.equals("nuzzle")?-22:action.equals("shield")?13:-5),t,open,action.equals("nuzzle")?"soft":"watch");
        for(int row=0;row<3;row++)for(int i=0;i<7;i++){double px=-50+i*20+(row%2)*8,py=-118+row*18;d.ink(null,row==0?"#9bb679":"#376a51",1).m(px-5,py).q(px,py+8,px+5,py).end();}
    }
    private static void wing(JungleCelInk d,double x,double y,double fold,boolean rear,double t)throws Exception {
        double f=Math.max(-45,Math.min(62,fold)),elbowX=x+61,elbowY=y-152+f*1.45,tipX=x-126+f*1.7,tipY=y-262+f*3.35;
        String membrane=rear?"#355d54":"#6d8c5d",bone=rear?"#436f59":"#9aaa70";
        d.ink(membrane,INK,2.7).m(x,y).q(x+80,y-88,elbowX,elbowY).l(tipX,tipY).q(tipX+37,tipY+54,x-93+f*1.1,y-102+f*.8).q(x-57,y-116+f*.9,x-29,y-68+f*.4).q(x+9,y-82,x,y).z().end();
        d.ink(null,bone,7).m(x,y).q(x+65,y-82,elbowX,elbowY).l(tipX,tipY).end();
        d.ink(null,bone,3).m(elbowX,elbowY).q(x-18,y-155,x-93+f*1.1,y-102+f*.8).m(elbowX,elbowY).q(x-1,y-126,x-29,y-68+f*.4).end();
        d.ink(null,rear?"#6a8870":"#b0b27b",1.2).m(tipX+11,tipY+17).q(x-18,y-194+f*2,x+31,y-119+f).end();
        d.poly("#d3c18d",INK,1.1,elbowX-5,elbowY+2,elbowX-5,elbowY-30,elbowX+10,elbowY-4);
    }
    private static void leg(JungleCelInk d,double hx,double hy,double kx,double ky,double foot,double ground,String color,boolean front)throws Exception {
        d.ink(color,INK,2.2).m(hx-19,hy-2).q(kx-22,ky-30,kx-14,ky+4).l(foot-17,ground-8).q(foot-8,ground+3,foot+18,ground+3).l(foot+33,ground-2).q(foot+26,ground-13,foot+7,ground-15).l(kx+14,ky-2).q(hx+28,hy-27,hx-19,hy-2).z().end();
        if(front)d.ink(null,LIGHT,4).m(hx-3,hy+2).q(kx-1,ky-18,kx,ky+4).l(foot-5,ground-13).end();claws(d,foot+22,ground-2,1);
    }
    private static void claws(JungleCelInk d,double x,double y,int dir)throws Exception {for(int i=0;i<3;i++)d.ink("#dfd3aa",INK,1).m(x+i*8*dir,y-i*2).q(x+12*dir+i*8*dir,y+6-i*2,x+9*dir+i*8*dir,y+11-i*2).l(x-2*dir+i*8*dir,y+5-i*2).z().end();}
    static void head(JungleCelInk d,double t,double mouth,String emotion)throws Exception {
        // Horns fork like jungle branches; the same identifying shape remains on the cub.
        d.ink("#b8b58b",INK,1.5).m(10,-28).q(7,-59,30,-78).l(23,-51).l(40,-61).l(24,-36).l(24,-22).z().end();
        d.ink("#e0d0a1",INK,1.7).m(24,-28).q(24,-60,45,-81).l(40,-56).l(57,-66).l(43,-38).l(39,-23).z().end();
        d.ink(LIGHT,INK,2.2).m(27,-32).q(-5,-49,-32,-26).l(-65,-16).q(-77,-10,-69,6).q(-55,24,-15,19).q(17,23,39,0).q(49,-20,27,-32).z().end();
        d.ink("#b3b47c",INK,1.2).m(-69,-2).q(-45,-8,-28,-5).q(-22,5,-14,9).q(-50,21,-67,8).z().end();
        d.ink(DARK,null,1).m(27,-31).q(49,-16,28,8).q(14,17,-4,17).l(10,-10).z().end();
        double brow=emotion.equals("soft")?2:-2;d.ink("#ead48b",INK,1.4).m(-24,-18).q(-7,-29,5,-17+brow).q(-6,-5,-24,-18).z().end();d.ellipse("#122c31",null,1,-8,-18,2.2,6);d.ellipse("#fff2c3",null,1,-11,-21,1.6,1.5);
        d.ink(null,DARK,2).m(-29,-22).q(-5,-34,7,-22+brow).end();d.ellipse("#334a3b",null,1,-58,-8,3,1.6);
        if(mouth>.05){d.ink("#4f3b40",INK,1.4).m(-65,8).q(-38,10,-13,6).q(-20,20+mouth*23,-52,20+mouth*14).q(-66,18+mouth*10,-65,8).z().end();for(int i=0;i<4;i++)d.poly("#eadfc0",INK,.6,-58+i*10,9,-52+i*10,9,-55+i*10,17);d.ink("#9caa72",INK,1).m(-57,21+mouth*14).q(-32,23+mouth*18,-15,12).q(-21,34+mouth*16,-52,29+mouth*11).z().end();}
        else d.ink(null,INK,1.3).m(-66,9).q(-43,14,-20,8).end();
        d.poly("#b9bb82",INK,1,35,-4,58,-14,48,2,62,12,38,14,27,22);
        for(int i=0;i<4;i++){double x=7+i*8,y=-5+i%2*7;d.ink(null,"#60825b",.8).m(x-3,y).q(x,y+5,x+3,y).end();}
    }
    static void cub(JungleCelInk world,double x,double y,double scale,double t,String expression)throws Exception {
        JungleCelInk d=world.at(x,y,scale,0);double bob=Math.sin(t*3)*2;
        d.ink("#6e9468",INK,2).m(15,-25).q(39,-37,57,-12).q(83,-10,82,-35).q(98,-1,64,4).l(27,5).z().end();
        d.ellipse("#96ad76",INK,2,8,-27+bob,40,32);d.ellipse("#d6c28d",null,1,-3,-14+bob,22,18);
        d.ink("#6c9566",INK,1.7).m(11,-43).q(17,-76,32,-64).l(47,-31).q(24,-36,11,-43).z().end();d.line("#b2ba7c",1.5,20,-48,29,-59,36,-39);
        for(int i=0;i<2;i++){double xx=-12+i*35;d.ellipse("#749b6c",INK,1.5,xx,0,13,9);d.line("#d6c9a1",1,xx+3,-1,xx+5,5);}
        JungleCelInk h=d.at(-26,-54+bob,1,expression.equals("happy")?-12:5);
        h.ink("#d7c796",INK,1.3).m(-3,-20).l(0,-43).l(8,-24).m(17,-20).l(27,-39).l(28,-15).end();
        h.ink("#a2b77d",INK,2).m(26,-23).q(-3,-40,-25,-20).q(-47,-16,-43,4).q(-23,25,9,14).q(31,7,26,-23).z().end();
        h.ink("#d9c795",null,1).m(-42,1).q(-18,-7,5,10).q(-15,20,-37,10).z().end();
        h.ellipse("#f1e0a1",INK,1.2,-9,-12,9,11);h.ellipse("#213d3c",null,1,-10,-10,4,8);h.ellipse("#ffffff",null,1,-13,-15,2.2,2.7);
        h.ink(null,DARK,1.3).m(-21,-27).q(-12,-31,-1,-24).end();h.ellipse(DARK,null,1,-35,-3,2,1.3);
        if(expression.equals("happy"))h.ink(null,INK,1.2).m(-31,8).q(-20,17,-8,8).end();else{h.ellipse("#655148",INK,.8,-22,10,4,3);h.ink(null,"#d9dbae",1).m(-15,0).q(-20,9,-17,13).end();}
    }
    static void overhead(JungleCelInk world,double x,double y,double size,double t)throws Exception {
        JungleCelInk d=world.at(x,y,size,-12);double flap=8*Math.sin(t*4);d.ink(GREEN,INK,2).m(-128,-22).c(-40,-79,84,-65,137,-4).c(201,58,248,55,286,-4).c(264,84,208,85,115,42).c(30,67,-70,45,-128,18).z().end();
        d.ink("#769465",INK,2).m(-12,-27).l(27,-143-flap).l(-161,-208-flap).q(-137,-113,-94,-90).q(-68,-94,-47,-57).z().end();d.ink("#5d815b",INK,2).m(0,24).l(44,136+flap).l(-132,203+flap).q(-122,108,-84,88).q(-47,90,-28,49).z().end();d.ink(null,"#b2b47c",4).m(7,-21).l(27,-143-flap).l(-161,-208-flap).m(11,22).l(44,136+flap).l(-132,203+flap).end();
        d.ink(LIGHT,INK,2).m(-102,-29).q(-146,-56,-186,-24).q(-218,-23,-219,-1).q(-200,31,-162,26).l(-104,19).z().end();d.poly("#d0c293",INK,1,-130,-25,-117,-61,-105,-24);d.poly("#d0c293",INK,1,-130,24,-116,60,-104,23);d.ellipse("#dcd49a",INK,1,-169,-23,9,4);d.ellipse("#dcd49a",INK,1,-169,23,9,4);
        for(int i=0;i<10;i++)d.poly("#c1b78a",INK,1,-90+i*25,-4,-80+i*25,-18,-70+i*25,-1,-80+i*25,12);
    }
    static void closeClaw(JungleCelInk world,double x,double y,double s)throws Exception {
        JungleCelInk d=world.at(x,y,s,-15);d.ink(GREEN,INK,2).m(54,-92).q(7,-104,-2,-57).l(-21,-17).q(-40,-3,-27,15).q(-9,22,6,8).q(23,15,35,2).q(51,6,59,-14).l(88,-61).z().end();d.ink(null,LIGHT,3).m(52,-76).q(18,-76,17,-34).end();
        for(int i=0;i<3;i++)d.ink("#e6d8ab",INK,1).m(-19+i*21,10-i*4).q(-26+i*22,39-i*5,-5+i*22,55-i*6).q(-7+i*23,34-i*5,0+i*22,7-i*4).z().end();
    }
}
