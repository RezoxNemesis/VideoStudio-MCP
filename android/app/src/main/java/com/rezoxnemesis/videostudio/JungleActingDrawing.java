package com.rezoxnemesis.videostudio;

/** Original cleanup curves and deliberately staged acting; no reference pixels. */
final class JungleActingDrawing {
    static final String INK="#142a30",SKIN="#cfaa87",SHADE="#9b7467",DEEP="#6e5355",LIGHT="#e7c39c";
    static final String HAIR="#142b32",HAIR_MID="#23434b",HAIR_LIGHT="#41616a";
    static final class Acting {
        double yaw,gaze,recognition,disgust,shame,blink,pitch,breath,hand;
    }
    static Acting sample(double t) {
        Acting a=new Acting();
        a.gaze=-.75*ease(t,.16,.34)+.25*ease(t,2.0,3.2);
        a.yaw=.30-.52*ease(t,.48,1.35)+.16*ease(t,8.0,10.8);
        a.recognition=ease(t,.38,1.5);
        a.disgust=envelope(t,6.2,6.65,8.15,9.05);
        a.shame=ease(t,7.8,10.65);
        a.gaze+=1.05*a.shame;
        a.pitch=-1.8*a.recognition+3*a.disgust+12*a.shame;
        a.breath=envelope(t,.9,1.3,1.6,2.8)*2.4-envelope(t,8.1,8.8,9.1,10.4)*2;
        a.hand=ease(t,8.25,9.6)*(1-.35*ease(t,12.6,14));
        a.blink=Math.max(Math.max(blink(t,2.72,.06),blink(t,5.72,.05)),Math.max(blink(t,7.85,.18),blink(t,11.4,.08)));
        return a;
    }
    static void portrait(JungleCelInk world,double seconds,double mouth)throws Exception {
        Acting a=sample(seconds);double push=1+.025*ease(seconds,0,2);
        JungleCelInk d=world.at(640,349+a.pitch*.9,2.4*push,0);
        double sag=6*a.shame;JungleCelInk head=d;d=d.at(0,-28+sag-a.breath,1,0);
        // Broad adult shoulder girdle, collarbones and a neck that continues into the chest.
        d.ink("#182e3d",INK,1.1).m(-176,253).q(-160,177,-105,155).q(-62,132,-32,114).l(35,112).q(70,133,110,150).q(158,174,176,253).z().end();
        d.ink("#d5c9a7",INK,1).m(-161,253).q(-148,182,-98,161).l(-39,128).l(-17,166).l(-28,253).z().end();
        d.ink("#ece0bb",INK,.9).m(24,253).l(19,166).l(43,128).q(105,148,138,181).l(169,253).z().end();
        d.ink("#8eaa9a",null,1).m(-98,165).l(-70,156).l(-82,201).l(-133,237).q(-120,195,-98,165).z().end();
        d.ink(null,"#7a9189",.55).m(-102,178).q(-116,197,-117,216).m(82,172).q(107,194,117,226).end();
        d.ink("#2e4650",INK,1).m(54,134).q(89,133,117,157).l(108,177).q(81,156,47,154).z().end();
        d.ink(null,"#78918b",.7).m(66,140).q(92,142,108,156).end();
        d.ink(SHADE,INK,.9).m(-27,55).l(-28,113).q(-35,124,-55,134).q(-9,158,38,132).l(22,112).l(23,56).z().end();
        d.ink(SKIN,null,1).m(-10,77).l(-10,111).q(8,131,30,127).l(19,110).l(18,71).z().end();
        d.ink(null,DEEP,.6).m(-22,94).q(-20,117,-11,128).m(17,99).q(14,117,23,125).end();
        // The scarf folds around the neck instead of substituting for the neck.
        d.ink("#a76c42",INK,.9).m(-55,132).q(-29,144,0,143).q(22,141,39,130).l(52,151).q(19,173,-18,164).q(-44,158,-62,147).z().end();
        d.ink("#d49a56",null,1).m(-54,134).q(-14,151,29,137).q(15,150,-7,153).q(-35,150,-54,140).z().end();
        d.ink(null,"#efd09a",.6).m(-48,138).q(-14,150,20,143).end();
        // Lowered chin and head turn are separate from shoulder response.
        face(head.at(-2,2+a.pitch*.4,1,a.pitch*.37),a,mouth,seconds);
        if(a.hand>.001)hand(d,a.hand,a.shame);
    }
    static void face(JungleCelInk d,Acting a,double mouth,double t)throws Exception {
        double y=a.yaw,nose=10+y*24,far=1-Math.max(0,y)*.48,near=1+Math.min(0,y)*.24;
        // Back hair masses and irregular curls, with a separate nape silhouette.
        d.ink(HAIR,INK,1.1).m(-52,26).c(-73,0,-68,-45,-55,-64).c(-64,-89,-40,-111,-18,-106).c(-6,-128,15,-113,25,-106).c(55,-117,70,-83,65,-62).c(79,-44,63,-10,56,9).l(47,67).q(36,68,31,47).l(-35,48).l(-43,78).q(-57,62,-52,26).z().end();
        d.ink(SKIN,INK,.95).m(-47,-43).c(-59,-15,-52,31,-36,53).q(-18,78,3+y*12,85).q(24+y*10,80,41+y*4,55).q(56,28,54,-9).c(58,-50,22,-79,-12,-74).q(-38,-72,-47,-43).z().end();
        // Actual cheek, temple, nose and jaw shadow planes, not a single half-face block.
        d.ink(SHADE,null,1).m(-47,-43).q(-61,-4,-45,30).q(-36,51,-20,65).l(4+y*12,83).l(12+y*9,76).q(-13,62,-24,42).q(-38,18,-32,-18).l(-17,-56).z().end();
        d.ink("#bd947a",null,1).m(43,-18).q(53,14,39,47).l(23,63).q(39,34,35,20).l(44,12).z().end();
        d.ink(LIGHT,null,1).m(-13,-49).q(12,-60,31,-36).l(39,-12).q(15,-20,-8,-12).z().end();
        d.ink("#d8b68f",null,1).m(-21,35).q(-2,30,9,37).l(6,47).q(-8,47,-21,35).z().end();
        // Ear cartilage and small weathered marks remain attached to the head.
        JungleCelInk e=d.at(-49+y*4,13,1,-6);e.ink(SHADE,INK,.75).m(-2,-13).q(-16,-18,-13,2).q(-10,20,1,19).l(7,5).z().end();e.ink(SKIN,null,1).m(-6,-10).q(-11,-12,-9,2).l(-2,11).q(4,8,2,0).z().end();e.ink(null,DEEP,.5).m(-6,-5).q(2,-7,-2,4).l(-5,7).end();
        double r=a.recognition,s=a.shame,g=a.disgust;
        double open=(.50+.44*r-.29*g-.24*s)*(1-a.blink);
        eye(d,-23+y*13,2+4*s,18*near,open,a.gaze,1,g,s);
        eye(d,27+y*7,1+4*s,15*far,open*.94,a.gaze,.8,g,s);
        // Brows have tapered ends and asymmetric inner tension.
        brow(d,-41+y*13,-20,34*near,6-10*r-3*s+3*g,5*g-5*s,false);
        brow(d,13+y*7,-21,29*far,-5+9*r+4*s-5*g,-4*g+4*s,true);
        if(g>.02){d.ink(null,DEEP,.45).m(nose-6,-10).q(nose-2,-15,nose+2,-11).m(nose+2,-5).q(nose+6,-10,nose+9,-5).end();}
        // Nose bridge, light edge, alar wing and shadow under the tip.
        d.ink("#ab7c68",null,1).m(nose-2,0).q(nose+1,15,nose+8,27).q(nose+5,33,nose-1,33).l(nose-6,29).q(nose+2,25,nose-2,0).z().end();
        d.ink(null,DEEP,.55).m(nose+6,24).q(nose+10,29,nose+2,32).m(nose-7,31).q(nose-4,28,nose-1,31).end();
        d.ink(null,LIGHT,.55).m(nose-1,4).q(nose,16,nose+4,23).end();
        double mx=5+y*10,my=53+2*s,opening=Math.max(0,Math.min(1,mouth));
        opening=Math.max(opening,.13*r*(1-ease(t,3.4,5.0))*(1-g)*(1-s));
        if(opening>.10){double w=13-2*s,h=2+opening*9;d.ink("#50373b",DEEP,.55).m(mx-w,my).q(mx,my-3-3*g,mx+w,my-1).q(mx+w-1,my+h,mx+2,my+h+1).q(mx-w+2,my+h-1,mx-w,my).z().end();d.ink("#e4c9a6",null,1).m(mx-w+1,my+.3).q(mx,my-1-2*g,mx+w-1,my).l(mx+w-2,my+1.7).q(mx,my+2,mx-w+2,my+2).z().end();if(opening>.5)d.ink("#99716b",null,1).m(mx-5,my+h-1).q(mx+2,my+h-5,mx+8,my+h-1).z().end();}
        else {d.ink(null,"#6d4949",.75).m(mx-15,my+2*g).q(mx-4,my-3*g,mx+2,my).q(mx+8,my+2*g,mx+13,my-1-3*g).end();}
        d.ink(null,"#b98a77",.7).m(mx-6,my+8).q(mx+3,my+10,mx+9,my+7).end();
        if(g>.02){d.ink(null,"#9c7168",.45).m(mx+16,my-9).q(mx+20,my-5,mx+17,my+1).m(mx-17,my-6).l(mx-20,my-2).end();}
        d.ink(null,"#97746b",.5).m(-33,38).l(-29,41).m(33,36).l(38,32).m(19,68).q(9,73,1,72).end();
        // Overlapping curved locks: the outline and each highlight follow the curl.
        double lag=1.3*Math.sin(t*2)*ease(t,.5,1.6)*(1-ease(t,5,7));
        d.ink(HAIR,INK,.85).m(-54,13).c(-70,-21,-62,-64,-44,-82).c(-49,-100,-24,-115,-8,-111).q(8,-128,26,-108).q(47,-112,54,-92).q(77,-72,61,-51).q(58,-21,37,-10).q(49,-32,34,-55).q(33,-21,9+lag,-7).q(20,-30,12,-57).q(7,-26,-21+lag,1).q(-10,-26,-18,-46).q(-21,-13,-36,16).q(-32,-5,-39,-18).l(-43,39).q(-54,32,-54,13).z().end();
        d.ink(HAIR_MID,null,1).m(-48,-31).c(-56,-59,-33,-92,-11,-94).q(-31,-78,-33,-56).q(-41,-45,-48,-31).z().end();
        d.ink(HAIR_MID,null,1).m(-11,-100).q(16,-116,34,-89).q(35,-74,28,-59).q(26,-86,10,-93).q(0,-98,-11,-100).z().end();
        d.ink(HAIR_MID,null,1).m(36,-92).q(64,-78,53,-52).q(49,-40,40,-32).q(47,-57,36,-92).z().end();
        d.ink(null,HAIR_LIGHT,.65).m(-49,-42).c(-47,-65,-30,-86,-16,-88).m(-31,-42).q(-24,-61,-18,-74).m(3,-104).q(26,-103,29,-82).m(43,-77).q(52,-64,43,-47).end();
        // Thin individual flyaways and cool forest rim light.
        d.ink(null,"#547579",.42).m(-58,-45).q(-67,-68,-53,-84).m(-41,-98).q(-24,-119,-11,-114).m(28,-106).q(51,-109,60,-91).m(59,-41).q(70,-55,65,-69).end();
        d.ink(null,"#779b91",.55).m(-53,28).q(-57,13,-57,3).m(49,30).q(46,51,35,63).end();
    }
    private static void eye(JungleCelInk d,double x,double y,double w,double open,double gaze,double asym,double disgust,double shame)throws Exception {
        double h=8*Math.max(0,open);if(h<.3){d.ink(null,INK,.9).m(x-w,y).q(x,y+1.5,x+w,y-1).end();return;}
        d.ink("#eee9d2",null,1).m(x-w,y).q(x-w*.1,y-h,x+w,y-1).q(x+w*.2,y+h*.49,x-w,y).z().end();
        double pupil=x+Math.max(-1,Math.min(1,gaze))*w*.27,cy=y-.7+shame*.8;
        d.ellipse("#ad925c",null,1,pupil,cy,4.7*asym,Math.min(7,h*.69));d.ellipse("#4c6959",null,1,pupil,cy-1.8,4.3*asym,Math.min(3.8,h*.3));d.ellipse("#102c30",null,1,pupil,cy,2.1*asym,Math.min(5,h*.52));
        if(open>.34){d.ellipse("#f6f1d4",null,1,pupil-1.6,cy-2.4,1.25,1.1);d.ellipse("#c3c999",null,1,pupil+1.3,cy+2.4,.6,.6);}
        d.ink(INK,null,1).m(x-w-1,y).q(x-w*.1,y-h-1,x+w+1,y-1).l(x+w,y+.2).q(x-w*.1,y-h+1.3,x-w+2,y+.6).z().end();
        d.ink(null,"#9c766a",.45).m(x-w+2,y+2.4).q(x+1,y+h*.57+2,x+w-1,y+1).end();
        d.ink(null,"#b38a76",.4).m(x-w*.8,y-h-4).q(x,y-h-5,x+w*.7,y-h-2).end();
    }
    private static void brow(JungleCelInk d,double x,double y,double width,double slope,double lift,boolean right)throws Exception {
        d.ink(HAIR,null,1).m(x,y+lift).q(x+width*.35,y-2+lift,x+width,y+slope).l(x+width-2,y+slope+1).q(x+width*.3,y+1+lift,x-1,y+lift+2).z().end();
    }
    private static void hand(JungleCelInk d,double p,double shame)throws Exception {
        double x=80-13*p,y=216-111*p;
        // Sleeve encloses a real forearm; hand and wrist share the same edge.
        d.ink("#284350",INK,.85).m(108,253).l(x-2,y+52).l(x+21,y+42).q(139,202,151,253).z().end();
        d.ink(SHADE,INK,.7).m(x-2,y+49).l(x-9,y+19).q(x-14,y+2,x-7,y-9).q(x-4,y-15,x,y-10).l(x+3,y+6).l(x+1,y-24).q(x+1,y-32,x+6,y-30).q(x+10,y-29,x+10,y-22).l(x+12,y-2).l(x+12,y-28).q(x+13,y-35,x+18,y-31).l(x+21,y-8).l(x+23,y-22).q(x+25,y-28,x+29,y-23).l(x+33,y+7).q(x+35,y+24,x+21,y+42).l(x+18,y+48).z().end();
        d.ink(SKIN,null,1).m(x+1,y+44).l(x-5,y+19).q(x-8,y+6,x-4,y-4).l(x+4,y+13).l(x+5,y-23).q(x+7,y-29,x+8,y-20).l(x+12,y+6).l(x+16,y-25).q(x+19,y-27,x+20,y-17).l(x+22,y+9).l(x+28,y-15).l(x+31,y+9).q(x+28,y+30,x+15,y+40).z().end();
        d.ink(null,DEEP,.4).m(x+1,y+24).q(x+7,y+19,x+17,y+21).m(x+13,y+10).l(x+15,y+19).m(x+22,y+9).l(x+23,y+17).end();
    }
    static double ease(double t,double a,double b){double p=Math.max(0,Math.min(1,(t-a)/(b-a)));return p*p*(3-2*p);}
    private static double envelope(double t,double a,double b,double c,double d){return ease(t,a,b)*(1-ease(t,c,d));}
    private static double blink(double t,double center,double hold){return envelope(t,center-.12,center,center+hold,center+hold+.18);}
}
