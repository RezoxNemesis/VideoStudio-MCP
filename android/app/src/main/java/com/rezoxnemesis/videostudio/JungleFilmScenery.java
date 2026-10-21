package com.rezoxnemesis.videostudio;

/** Original jungle layout drawings: buttress roots, canopy, vines and atmospheric depth. */
final class JungleFilmScenery {
    static final String INK="#09272c";
    static void draw(JungleCelInk b,JungleCelInk floor,JungleCelInk front,double t,boolean close,double warmth)throws Exception {
        b.poly("#092c38",null,1,0,0,1280,0,1280,720,0,720);
        b.ellipse("#16414a",null,1,700,290,540,440);
        b.ellipse("#265354",null,1,780,210,355,370);
        b.ellipse("#62816c",null,1,800,180,205,230);
        b.ellipse("#9ba77c",null,1,804,142,110,140);
        // Deep trunks have their own winding silhouettes, not a photographic backdrop.
        for(int i=0;i<14;i++){
            double x=i*104-30+6*Math.sin(i*2.6),w=14+(i%3)*8;
            b.ink(i%2==0?"#285459":"#346260",null,1).m(x,0).c(x-8,170,x+17,360,x-30,600).l(x+w+30,600).c(x+w,340,x+w+10,160,x+w,0).z().end();
            b.ink(null,"#47766c",2).m(x+w/2,0).c(x+4,200,x+w+8,300,x-10,520).end();
            for(int j=0;j<3;j++){double y=80+j*110;b.ink(null,"#33635d",9).m(x+w/2,y+55).q(x+70,y,x+100,y-18).end();}
        }
        b.ink("#426f64",null,1).m(0,465).c(220,410,270,490,430,455).c(650,418,910,450,1280,404).l(1280,720).l(0,720).z().end();
        b.ink("#28574f",null,1).m(0,505).c(200,450,350,530,510,478).c(790,440,1030,475,1280,430).l(1280,720).l(0,720).z().end();
        floor.ink("#173e3a",null,1).m(0,560).c(210,495,390,590,560,510).c(780,470,1050,555,1280,490).l(1280,720).l(0,720).z().end();
        floor.ink("#396151",null,1).m(0,645).c(350,495,710,492,1280,552).l(1280,720).l(0,720).z().end();
        floor.ink("#6a7858",null,1).m(90,698).c(340,555,500,546,900,590).c(980,603,1110,627,1280,676).l(1280,720).l(90,720).z().end();
        floor.ink("#4d6950",null,1).m(0,712).c(300,620,520,595,690,618).c(800,638,920,660,1280,656).l(1280,720).l(0,720).z().end();
        // Nest tree on the far right; roots create the sheltered hollow revealed later.
        floor.ink("#142f31",INK,3).m(1050,0).c(1020,170,1070,350,1008,493).c(974,533,888,539,852,583).c(936,565,1008,564,1054,537).c(1100,568,1185,570,1280,650).l(1280,0).z().end();
        floor.ink("#355043",null,1).m(1090,0).c(1055,220,1120,405,1077,504).c(1128,485,1180,514,1240,570).c(1192,452,1184,254,1225,0).z().end();
        floor.ink(null,"#5b7150",5).m(1130,0).c(1070,220,1152,406,1100,508).q(1050,544,984,548).end();
        floor.ink(null,"#25463e",7).m(1197,12).c(1128,180,1200,335,1152,481).q(1204,522,1247,575).end();
        for(int i=0;i<9;i++){double x=70+i*143,y=566+(i%3)*41;floor.ink("#45614a",INK,1.5).m(x-24,y+12).q(x-17,y-21,x+8,y-26).l(x+31,y-8).l(x+41,y+13).z().end();floor.ink(null,"#81916a",1.3).m(x-18,y-5).l(x+8,y-23).l(x+29,y-9).end();}
        // Foreground buttress roots and large hand-drawn leaves frame the clearing.
        tree(front,55,0,1,t);tree(front,1270,-22,-.82,t);
        for(int i=0;i<13;i++){double x=i*110-30,y=690+19*Math.sin(i*1.9);fern(front,x,y,1.0+(i%3)*.25,i%2==0?1:-1,t);}
        for(int i=0;i<9;i++){double x=40+i*145;front.ink(null,"#173a34",3).m(x,-8).c(x-15,90,x+50,156,x+20,270).end();if(i%2==0){leaf(front,x+24,155,.8,25+4*Math.sin(t*.7+i),"#285143");leaf(front,x+20,230,.65,-15,"#35634b");}}
        // Authored soft shafts: translucent vector polygons, no raster lighting textures.
        b.poly("#126ee4ba",null,1,658,0,704,0,525,560,396,605);
        b.poly("#17e5e7a0",null,1,822,0,857,0,750,565,658,600);
        if(warmth>0){b.poly("#18fff1be",null,1,940,0,1040,0,700,650,450,650);}
        for(int i=0;i<12;i++){double x=100+(i*103+11*Math.sin(t*.75+i))%1100,y=100+(i*47+17*Math.cos(t*.62+i))%420;b.ellipse(i%3==0?"#99e8efb6":"#448ac7ad",null,1,x,y,1.5,1.5);}
    }
    private static void tree(JungleCelInk d,double x,double y,double mirror,double t)throws Exception {
        JungleCelInk a=d.at(x,y,Math.abs(mirror),0);double f=mirror<0?-1:1;
        a.ink("#09272c",null,1).m(-105*f,0).c(-83*f,190,-80*f,430,-111*f,604).l(-10*f,720).l(129*f,720).c(51*f,634,35*f,556,47*f,455).c(55*f,260,34*f,97,85*f,0).z().end();
        a.ink("#173c36",null,1).m(-35*f,0).c(-57*f,260,-11*f,490,-34*f,612).l(75*f,708).l(30*f,590).c(10*f,437,30*f,196,29*f,0).z().end();
        a.ink(null,"#42634a",3).m(-21*f,0).c(-43*f,200,7*f,414,-24*f,593).q(0,637,42*f,674).end();
        for(int i=0;i<5;i++){double yy=55+i*48;leaf(a,20*f,yy,1.7+i*.12,f*(65-i*11)+3*Math.sin(t+i),i%2==0?"#204b3c":"#183e34");}
    }
    static void leaf(JungleCelInk d,double x,double y,double s,double angle,String color)throws Exception {
        JungleCelInk a=d.at(x,y,s,angle);a.ink(color,INK,.8).m(0,0).c(20,-15,55,-20,80,0).c(45,14,19,18,0,0).z().end();a.ink(null,"#507651",.8).m(0,0).q(40,-5,76,0).end();
    }
    static void fern(JungleCelInk d,double x,double y,double s,int flip,double t)throws Exception {
        JungleCelInk a=d.at(x,y,s,5*Math.sin(t*.8+x));for(int j=0;j<5;j++){double end=flip*(30+j*15),yy=-80+j*12;a.ink("#103f35",null,1).m(0,5).q(end*.3,yy*.8,end,yy).q(end*.6,yy+30,0,5).z().end();a.ink(null,"#3b6b49",1).m(0,5).q(end*.4,yy*.65,end,yy).end();}
    }
}
