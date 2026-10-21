package com.rezoxnemesis.videostudio;

import org.json.*;
import java.util.*;

/** Original tailored scholar drawings. All marks become ordinary native Bezier objects. */
final class SketchCharacterArt {
    final JSONObject layer,settings;final JungleCelInk d;final String id,coat,skin,hair;final int frames;
    final Map<String,List<JSONObject>> groups=new LinkedHashMap<>();
    SketchCharacterArt(JSONObject layer,String id,JSONObject settings,int frames)throws Exception {
        this.layer=layer;this.id=id;this.settings=settings;this.frames=frames;d=new JungleCelInk(layer);
        coat=settings.getString("coatColor");skin=settings.getString("skinColor");hair=settings.getString("hairColor");
    }
    interface Draw {void run()throws Exception;}
    void part(String name,String group,Draw draw)throws Exception {
        JSONArray a=layer.getJSONArray("objects");int start=a.length();draw.run();
        for(int i=start;i<a.length();i++){JSONObject o=a.getJSONObject(i);o.put("commands",new JSONArray(o.getJSONArray("commands").toString()));o.put("id",id+"_"+name+(i==start?"":"_"+(i-start))).put("exposure",frames);groups.computeIfAbsent(group,k->new ArrayList<>()).add(o);}
    }
    static String tone(String color,double value){int c=android.graphics.Color.parseColor(color);return String.format(Locale.ROOT,"#%02X%02X%02X%02X",android.graphics.Color.alpha(c),Math.min(255,(int)(android.graphics.Color.red(c)*value)),Math.min(255,(int)(android.graphics.Color.green(c)*value)),Math.min(255,(int)(android.graphics.Color.blue(c)*value)));}
    void body()throws Exception {
        boolean back=settings.getString("view").equals("back"),profile=Set.of("left","right").contains(settings.getString("view"));
        double breadth=settings.getString("build").equals("broad")?1.12:1;
        part("coat","body",()->d.ink(coat,"#14212C",2.1).m(601,343).c(569,344,520,350,486,373).q(469,431,483,487).l(506,697).q(636,716,775,697).l(803,487).q(815,429,792,375).c(759,352,706,344,678,343).z().end());
        part("far_sleeve","body",()->d.ink(tone(coat,.78),"#152331",2).m(777,373).q(808,379,824,420).l(853,601).q(839,619,816,610).l(774,474).q(754,416,777,373).z().end());
        part("far_hand","body",()->d.ink(tone(skin,.77),"#4C3936",1.4).m(817,603).q(841,602,851,612).l(863,656).q(862,666,855,662).l(846,637).l(850,674).q(846,681,840,674).l(833,643).l(833,675).q(827,681,823,674).l(820,640).q(806,631,817,603).z().end());
        part("neck","body",()->d.ink(skin,"#473632",1.8).m(602,260).l(599,355).q(620,381,643,393).q(667,375,683,355).l(678,260).z().end());
        part("neck_plane","body",()->d.ink(tone(skin,.69),null,1).m(605,302).q(631,322,672,307).l(661,354).l(642,384).l(627,348).z().end());
        if(profile){
            part("shirt","body",()->d.ink("#C6C6BC","#38434A",1.4).m(604,349).l(641,383).l(629,482).l(607,531).l(596,426).z().end());
            part("collar","body",()->d.poly("#E2E0CD","#414B4B",1.5,603,339,639,378,615,408,584,361));
            part("lapel","body",()->d.ink(tone(coat,1.36),"#111E29",2).m(584,343).l(611,416).l(582,409).l(619,451).l(629,569).l(552,448).l(564,414).l(542,399).z().end());
            part("side_seam","body",()->d.ink(null,tone(coat,.5),1.5).m(704,402).q(689,522,710,697).end());
        }else if(!back){
            part("shirt","body",()->d.ink("#C6C6BC","#38434A",1.4).m(603,349).l(640,383).l(679,350).l(691,475).l(643,584).l(594,474).z().end());
            part("collar","body",()->{d.poly("#E2E0CD","#414B4B",1.5,603,339,639,378,615,408,584,361);d.poly("#D6D6C6","#414B4B",1.5,680,339,643,378,668,406,699,359);});
            part("lapel","body",()->{d.ink(tone(coat,1.36),"#111E29",2).m(584,343).l(611,416).l(582,409).l(619,451).l(640,587).l(552,448).l(564,414).l(542,399).z().end();d.ink(tone(coat,1.20),"#111E29",2).m(699,343).l(670,417).l(700,409).l(663,454).l(640,587).l(731,448).l(720,414).l(744,399).z().end();});
            part("waistcoat","body",()->d.ink(tone(coat,.72),"#0E1D29",1.2).m(618,457).l(641,579).l(665,456).l(688,666).l(641,695).l(595,667).z().end());
            part("buttons","body",()->{for(int i=0;i<4;i++)d.ellipse("#C5AD78","#4F493E",1,642,560+i*32,3.6,4.3);});
            part("pocket","body",()->{d.poly(tone(coat,.82),"#172632",1.3,708,467,762,454,768,470,710,481);d.line("#8B9A9B",1,711,465,761,455);});
        }else{
            part("back_seam","body",()->d.ink(null,"#879294",1.3).m(640,356).c(634,439,645,534,641,697).m(497,395).q(641,446,792,397).end());
        }
        part("structure","body",()->{d.ink(null,tone(coat,1.7),1.2).m(491,382).q(474,433,493,474).m(777,379).q(801,426,788,469).m(514,516).q(546,546,565,551).m(774,518).q(744,545,723,551).m(519,639).q(548,621,568,620).m(767,642).q(737,621,715,619).end();});
        if(settings.getString("detail").equals("tailored")){
            part("cloth_folds","body",()->{d.ink(null,tone(coat,.57),2.6).m(500,474).q(528,491,551,488).m(501,485).q(527,507,550,501).m(786,474).q(758,496,736,489).m(778,489).q(755,512,736,501).m(514,584).q(536,570,555,568).m(772,585).q(749,571,730,569).end();});
            part("weave","body",()->{for(int i=0;i<16;i++){double y=475+i*12;d.line(tone(coat,1.14),.6,524,y,546,y-7);d.line(tone(coat,1.09),.6,752,y,769,y-6);}});
            if(settings.getString("material").equals("leather"))part("leather_glints","body",()->d.ink(null,"#9FAEAB",2).m(489,388).q(481,419,489,442).m(565,369).l(595,417).m(724,363).l(687,416).m(804,429).l(825,548).m(529,650).l(557,641).end());
            else part("wool_stitch","body",()->{for(int i=0;i<13;i++)d.line(tone(coat,1.8),.7,574+i*3,376+i*5,576+i*3,379+i*5);});
        }
        if(breadth!=1||profile){double sx=profile?.69*breadth:breadth;for(JSONObject o:groups.get("body"))scaleX(o,sx,640);}
        arm(back);
    }
    static void scaleX(JSONObject o,double scale,double cx)throws Exception {JSONArray a=o.getJSONArray("commands");for(int i=0;i<a.length();i++){JSONArray c=a.getJSONArray(i);for(int n=1;n<c.length();n+=2)c.put(n,(cx+(c.getDouble(n)*1280-cx)*scale)/1280);}}
    void arm(boolean back)throws Exception {
        // Each joint has an explicit local pivot. The rigid hand follows its wrist; the flexible pendant follows its grip under world gravity.
        part("upper_arm","upper",()->d.ink(tone(coat,1.05),"#132330",2).m(467,371).q(481,363,499,379).l(508,486).q(490,507,467,492).l(451,405).q(451,382,467,371).z().end());
        part("forearm","fore",()->d.ink(coat,"#142432",2).m(467,489).q(483,481,504,489).l(503,601).l(469,601).q(461,541,467,489).z().end());
        part("sleeve_folds","fore",()->d.ink(null,tone(coat,1.65),1.4).m(470,512).q(484,522,500,514).m(469,526).q(481,537,496,529).m(471,578).l(500,570).end());
        part("cuff","fore",()->{d.poly("#CFD0BD","#24343B",1.5,469,593,504,593,506,612,470,612);d.ellipse("#C3A874","#4C493D",.8,498,603,2.4,3);});
        String glove="#36424B",gloveLight="#67737A";boolean explain=settings.getString("gesture").equals("explain");
        part("palm","hand",()->d.ink(glove,"#182A33",1.5).m(470,609).q(487,603,504,611).l(506,640).q(489,656,473,643).z().end());
        for(int finger=0;finger<4;finger++){
            final int f=finger;double x=474+f*8;final double length=explain?40-Math.abs(f-1)*4:16+f*2;
            part(finger==0?"index_tip":"finger_"+finger,"hand",()->d.ink(f%2==0?glove: tone(glove,.88),"#172731",1.2).m(x,635).q(x-3,650,x,642+length).q(x+3,647+length,x+6,642+length).l(x+7,638).q(x+4,631,x,635).z().end());
            if(settings.getString("detail").equals("tailored"))part("knuckle_"+f,"hand",()->d.ink(null,gloveLight,.8).m(x,642).q(x+3,639,x+6,642).m(x+1,650).l(x+5,650).end());
        }
        if(!back&&!explain)part("pendant","prop",()->{d.ink(null,"#CAB17D",1.4).m(492,637).c(467,648,468,673,484,680).c(502,673,514,650,492,637).end();d.poly("#BBA269","#584B37",1.5,484,675,496,687,484,704,473,687);d.poly("#48A6A7","#246471",.8,484,680,490,687,484,697,478,687);});
        part("thumb","hand",()->d.ink(tone(glove,1.2),"#152B35",1.3).m(502,613).q(515,624,513,638).q(504,648,490,642).q(485,639,490,635).l(503,630).l(498,620).z().end());
        part("hand_tendons","hand",()->d.ink(null,gloveLight,.9).m(478,614).l(480,633).m(488,614).l(490,629).m(497,615).l(498,627).end());
    }
    void hairAndPlanes()throws Exception {
        String view=settings.getString("view");boolean back=view.equals("back"),profile=Set.of("left","right").contains(view);double dir=view.equals("left")?-1:1;
        if(!back){
            part("cheek_plane","head",()->{if(profile){d.poly(tone(skin,.78),null,1,640-dir*23,210,640+dir*25,228,640+dir*52,277,640-dir*5,292,640-dir*26,260);}else{d.ink(tone(skin,.83),null,1).m(556,205).q(570,233,595,240).l(609,286).q(578,266,567,244).z().end();d.ink(tone(skin,1.1),null,1).m(687,213).q(686,230,672,237).l(672,267).q(699,251,707,222).z().end();}});
        }
        part("layered_hair","head",()->{
            if(back){d.ink(hair,"#121E2A",1.5).m(553,233).c(524,159,553,92,603,89).q(645,67,680,92).q(724,96,736,162).q(745,252,687,296).l(667,284).l(650,306).l(628,286).l(610,301).q(566,279,553,233).z().end();}
            else if(profile){double x=640;d.ink(hair,"#121E2A",1.5).m(x-dir*81,233).c(x-dir*118,180,x-dir*80,100,x-dir*27,92).q(x+dir*45,69,x+dir*78,126).l(x+dir*50,122).l(x+dir*68,149).l(x+dir*24,131).l(x+dir*39,172).q(x-dir*4,146,x-dir*20,132).l(x-dir*48,192).l(x-dir*51,168).l(x-dir*64,235).z().end();}
            else d.ink(hair,"#121E2A",1.6).m(553,233).c(524,159,553,92,603,89).q(645,67,680,92).q(724,96,736,162).l(727,224).l(709,200).l(705,161).l(682,139).l(693,174).l(656,143).l(642,175).l(618,134).l(594,173).l(584,151).l(566,216).z().end();
        });
        part("hair_locks","head",()->{for(int i=0;i<11;i++){double x=558+i*14;if(profile)x=560+i*10;d.ink(null,tone(hair,1.75),i%3==0?1.2:.65).m(x,143+i%3*9).q(x+14,109,x+33,101+i%4*4).end();}});
        if(back)part("nape_locks","head",()->d.ink(hair,"#15212C",1).m(552,195).q(548,265,593,294).l(595,277).l(617,306).l(629,287).l(649,310).l(665,287).l(686,295).q(718,263,726,198).z().end());
    }
}
