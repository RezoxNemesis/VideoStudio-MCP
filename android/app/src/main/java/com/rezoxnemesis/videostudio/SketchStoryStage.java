package com.rezoxnemesis.videostudio;

import android.graphics.Matrix;
import android.graphics.Path;
import android.graphics.RectF;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.Arrays;
import java.util.Iterator;

/** Original native scenery, saved as ordinary editable curves at the unchanged document clock. */
final class SketchStoryStage {
    private SketchStoryStage(){}
    static JSONObject defaults()throws Exception{return new JSONObject().put("location","forest_path").put("time","dusk").put("weather","clear").put("intensity",.6);}
    static JSONObject settings(JSONObject input)throws Exception {
        SketchDocument.fields(input,"location,time,weather,intensity");JSONObject s=defaults();Iterator<String> keys=input.keys();while(keys.hasNext()){String k=keys.next();s.put(k,input.get(k));}
        choice(s,"location","forest_path","bridge","shelter","village");choice(s,"time","dusk","night","dawn");choice(s,"weather","clear","wind","rain");SketchDocument.number(s,"intensity",0,1);return s;
    }
    static void choice(JSONObject s,String key,String... choices)throws Exception {if(!(s.get(key) instanceof String)||!Arrays.asList(choices).contains(s.getString(key)))throw SketchDocument.bad("Unknown "+key);}
    static void apply(JSONObject doc,JSONObject op)throws Exception {
        SketchDocument.fields(op,"type,layerId,foregroundLayerId,sceneId,settings");if(!"add_story_stage".equals(op.getString("type")))throw SketchDocument.bad("Expected add_story_stage");
        String id=SketchDocument.id(op,"sceneId"),layerId=SketchDocument.id(op,"layerId");requireTarget(doc,layerId,id);
        String foreground=op.has("foregroundLayerId")?SketchDocument.id(op,"foregroundLayerId"):null;
        if(foreground!=null){if(foreground.equals(layerId))throw SketchDocument.bad("Foreground layer must differ from the background layer");if(SketchDocument.layer(doc,foreground).getBoolean("locked"))throw SketchDocument.bad("Foreground layer is locked");}
        JSONObject s=settings(op.getJSONObject("settings"));Ink ink=new Ink(id,doc.getInt("frameCount"));Palette p=new Palette(s.getString("time"));
        sky(ink,p,s.getString("time"));land(ink,p,s.getString("location"));
        if(s.getString("location").equals("bridge"))bridge(ink,p);
        else if(s.getString("location").equals("shelter"))shelter(ink,p);
        else if(s.getString("location").equals("village"))village(ink,p);
        else forestPath(ink,p);
        foreground(ink,p);weather(ink,s,doc.getInt("frameCount"),doc.getInt("fps"));
        JSONArray objects=ink.layer.getJSONArray("objects");for(int i=0;i<objects.length();i++)fit(objects.getJSONObject(i),doc.getInt("width"),doc.getInt("height"));
        if(foreground==null)append(doc,layerId,objects);else appendSplit(doc,layerId,foreground,id,objects);
    }
    /** Reference-720 floor/deck ordinate. Apply the scene's uniform canvas fit to place actors. */
    static double walkwayY(String location,double normalizedX){
        if(!Arrays.asList("forest_path","bridge","shelter","village").contains(location)||!Double.isFinite(normalizedX)||normalizedX<0||normalizedX>1)throw SketchDocument.bad("Choose a known location and a normalized walkway X");
        return location.equals("bridge")?592.3333333333334-(1280*normalizedX)/12:location.equals("shelter")?625:620;
    }
    private static void appendSplit(JSONObject doc,String background,String foreground,String id,JSONArray additions)throws Exception {
        JSONObject candidate=SketchDocument.copy(doc);JSONArray back=SketchDocument.layer(candidate,background).getJSONArray("objects"),front=SketchDocument.layer(candidate,foreground).getJSONArray("objects");int firstBack=back.length(),firstFront=front.length();
        for(int i=0;i<additions.length();i++){
            JSONObject object=additions.getJSONObject(i);String name=object.getString("id");
            boolean near=name.startsWith(id+"_bridge_near_rail_")||name.startsWith(id+"_foreground_roots_")||name.startsWith(id+"_foreground_grass_")||name.startsWith(id+"_rain_")||name.startsWith(id+"_wind_");
            (near?front:back).put(object);
        }
        SketchDocument.validate(candidate);validatePlacement(candidate,background,firstBack);validatePlacement(candidate,foreground,firstFront);
        // Both destinations have passed the same candidate validation before either caller layer changes.
        SketchDocument.layer(doc,background).put("objects",back);SketchDocument.layer(doc,foreground).put("objects",front);
    }
    static void requireTarget(JSONObject doc,String layerId,String id)throws Exception {
        if(id.length()>20)throw SketchDocument.bad("Scene/prop identity must fit 20 characters");
        if(SketchDocument.layer(doc,layerId).getBoolean("locked"))throw SketchDocument.bad("Layer is locked");
        JSONArray layers=doc.getJSONArray("layers");for(int l=0;l<layers.length();l++){JSONArray a=layers.getJSONObject(l).getJSONArray("objects");for(int i=0;i<a.length();i++)if(a.getJSONObject(i).getString("id").startsWith(id+"_"))throw SketchDocument.bad("Scene/prop identity already exists");}
    }
    interface Draw {void run()throws Exception;}
    static final class Ink {
        final JSONObject layer;final JungleCelInk d;final String id;final int frames;
        Ink(String id,int frames)throws Exception {this.id=id;this.frames=frames;layer=new JSONObject().put("id",id).put("objects",new JSONArray());d=new JungleCelInk(layer);}
        void part(String name,Draw draw)throws Exception {JSONArray a=layer.getJSONArray("objects");int start=a.length();draw.run();for(int i=start;i<a.length();i++){
            JSONObject o=a.getJSONObject(i);o.put("id",id+"_"+name+"_"+(i-start)).put("exposure",frames);
            // Fill and outline emitted by JungleCelInk share their input array; own it before fitting.
            o.put("commands",new JSONArray(o.getJSONArray("commands").toString()));
        }}
        void gradient(String name,String from,String to,Draw draw)throws Exception {int start=layer.getJSONArray("objects").length();part(name,draw);JSONObject object=layer.getJSONArray("objects").getJSONObject(start);object.put("gradient",new JSONObject().put("startColor",from).put("endColor",to).put("x0",.5).put("y0",0).put("x1",.5).put("y1",1));}
    }
    private static final class Palette {
        final String top,horizon,far,middle,ground,wood,light;
        Palette(String time){boolean night=time.equals("night"),dawn=time.equals("dawn");
            top=night?"#FF101D33":dawn?"#FF496D86":"#FF353E62";horizon=night?"#FF526971":dawn?"#FFF2CCA1":"#FFD69A83";
            far=night?"#FF344F5A":dawn?"#FF73958A":"#FF647778";middle=night?"#FF203F43":dawn?"#FF496F60":"#FF405E58";
            ground=night?"#FF233C3B":dawn?"#FF61725B":"#FF4E5B4B";wood=night?"#FF344143":"#FF58473D";light=night?"#FFE4EDD1":"#FFFFD79E";
        }
    }
    private static void sky(Ink a,Palette p,String time)throws Exception {
        JungleCelInk d=a.d;
        a.gradient("sky",p.top,p.horizon,()->d.poly(p.top,null,1,0,0,1280,0,1280,720,0,720));
        a.part("light_halo",()->d.ellipse("#28F9DFB2",null,1,1004,142,83,83));
        a.part("light_disc",()->d.ellipse(p.light,null,1,1004,142,time.equals("night")?27:35,time.equals("night")?27:35));
        if(time.equals("night"))a.part("stars",()->{for(int i=0;i<21;i++){double x=74+(i*193%1140),y=38+(i*47%190);d.line("#99D7E7DA",1,x-1,y,x+1,y);}});
        a.part("clouds",()->{for(int i=0;i<4;i++){double x=95+i*270,y=97+i%2*74;d.ink("#228DA8AD",null,1).m(x,y).c(x+35,y-24,x+82,y-22,x+106,y-8).q(x+55,y+11,x,y).z().end();}});
        a.part("far_ridge",()->d.ink(p.far,null,1).m(0,401).c(123,342,174,379,292,319).c(386,279,471,371,593,337).c(723,283,769,352,866,322).c(1026,274,1149,353,1280,312).l(1280,720).l(0,720).z().end());
        for(int i=0;i<3;i++){final int n=i;a.part("mist_"+i,()->d.ink("#228FB5AD",null,1).m(0,350+n*40).c(240,330+n*48,494,387+n*28,728,351+n*40).q(1020,316+n*43,1280,352+n*40).l(1280,365+n*40).q(760,389+n*35,0,368+n*40).z().end());}
    }
    private static void land(Ink a,Palette p,String location)throws Exception {
        JungleCelInk d=a.d;
        for(int i=0;i<11;i++)tree(a,"distant_tree_"+i,64+i*113,475+(i%3)*13,160+(i*37%90),24+(i%3)*4,p.middle,false);
        a.part("bank",()->d.ink(p.ground,null,1).m(0,504).c(230,460,390,507,596,476).c(839,446,1014,488,1280,460).l(1280,720).l(0,720).z().end());
        a.part("ground_planes",()->{d.ink("#28406351",null,1).m(0,594).q(319,530,629,577).q(916,527,1280,568).l(1280,720).l(0,720).z().end();d.ink(null,"#55748A65",1.2).m(74,657).q(180,628,252,632).m(986,663).q(1100,626,1213,643).end();});
    }
    private static void forestPath(Ink a,Palette p)throws Exception {
        JungleCelInk d=a.d;
        a.gradient("path","#FFB39B79","#FF6B6555",()->d.ink("#FF9C876B",null,1).m(583,466).c(516,510,722,532,588,574).c(456,619,390,641,296,720).l(1012,720).c(881,622,646,602,737,551).c(811,505,642,493,657,466).z().end());
        a.part("path_marks",()->{d.ink(null,"#59746750",2).m(416,688).q(555,613,661,598).m(736,656).q(689,615,638,625).m(628,505).q(692,517,699,531).end();for(int i=0;i<12;i++){double x=180+(i*137%950),y=553+(i*37%140);d.ellipse("#FF6D7861",null,1,x,y,8+i%3*4,2+i%2);}});
        tree(a,"near_tree_left",125,679,492,105,p.wood,true);tree(a,"near_tree_right",1152,654,510,104,p.wood,true);
    }
    private static void tree(Ink a,String name,double x,double y,double height,double width,String color,boolean detail)throws Exception {
        JungleCelInk d=a.d;
        a.part(name,()->{
            d.ink(color,null,1).m(x-width*.23,y).q(x-width*.08,y-height*.53,x-width*.13,y-height).l(x+width*.12,y-height).q(x+width*.08,y-height*.45,x+width*.26,y).z().end();
            d.ink(color,null,1).m(x-width*.05,y-height*.61).q(x-width*.75,y-height*.63,x-width,y-height*.79).q(x-width*.31,y-height*.70,x+width*.03,y-height*.70).l(x+width*.50,y-height*.92).q(x+width*.43,y-height*.69,x+width*.02,y-height*.57).z().end();
            d.ink(color,null,1).m(x-width,y-height*.83).c(x-width*.93,y-height*1.02,x-width*.20,y-height*1.05,x,y-height*.98).c(x+width*.64,y-height*1.09,x+width,y-height*.96,x+width,y-height*.83).q(x+width*.28,y-height*.72,x-width,y-height*.83).z().end();
            if(detail){d.ink(null,"#827B8064",1.4).m(x-width*.09,y-8).q(x+width*.06,y-height*.38,x-width*.04,y-height*.72).m(x+width*.11,y-height*.3).q(x+width*.25,y-height*.5,x+width*.1,y-height*.63).end();d.ink(null,"#FF2A3B38",2).m(x-width*.23,y-17).q(x-width*.43,y+3,x-width*.80,y+9).m(x+width*.14,y-8).q(x+width*.52,y+8,x+width*.9,y+13).end();}
        });
    }
    private static void bridge(Ink a,Palette p)throws Exception {
        JungleCelInk d=a.d;
        a.gradient("river","#FF416D77","#FF213B50",()->d.poly("#FF345766",null,1,0,474,1280,438,1280,720,0,720));
        a.part("river_reflections",()->{for(int i=0;i<27;i++){double x=72+(i*113%1110),y=468+(i*41%236),length=24+i%5*11;d.ink(null,i%4==0?"#799EBAAA":"#466F999E",i%4==0?2.5:1).m(x,y).q(x+length*.5,y-3,x+length,y).end();}for(int i=0;i<8;i++)d.line("#65E4D3A3",2,969-i*10,503+i*23,1025+i*7,503+i*23);});
        a.part("bridge_banks",()->{d.poly(p.ground,null,1,0,482,215,487,270,568,166,720,0,720);d.poly(p.ground,null,1,1127,454,1280,440,1280,720,1193,720,1095,536);});
        a.part("bridge_shadow",()->d.poly("#51203239",null,1,211,599,1086,515,1095,539,221,636));
        a.part("bridge_far_rail",()->{d.ink(null,p.wood,10).m(231,479).l(1081,408).m(239,495).l(1083,424).end();for(int i=0;i<10;i++){double x=248+i*90,y=552-i*7.5;d.line(p.wood,8,x,y,x-7,y-74);}});
        for(int i=0;i<19;i++){final int n=i;a.part("bridge_plank_"+i,()->{double x=220+n*45,y=557-n*3.75;d.poly(n%3==0?"#FF92765A":"#FF77634E","#FF37403A",1.4,x,y,x+42,y-3.5,x+64,y+29,x+21,y+33);d.line("#7BC1A17A",.8,x+10,y+8,x+43,y+5);});}
        a.part("bridge_near_rail",()->{for(int i=0;i<10;i++){double x=256+i*90,y=596-i*7.5;d.poly(p.wood,"#FF293C3B",1,x-4,y,x+5,y,x+2,y-82,x-7,y-82);}d.ink(null,"#FF8C765A",8).m(244,511).l(1092,440).end();});
    }
    private static void shelter(Ink a,Palette p)throws Exception {
        forestPath(a,p);JungleCelInk d=a.d;
        a.part("shelter_shadow",()->d.ellipse("#58242D30",null,1,1005,641,200,30));
        a.gradient("shelter_inside","#FF303A36","#FF70513A",()->d.poly("#FF444638",null,1,853,487,1002,396,1166,491,1152,633,865,640));
        a.part("shelter_posts",()->{d.poly(p.wood,"#FF293735",1.6,848,472,865,472,879,644,858,644);d.poly(p.wood,"#FF293735",1.6,1158,472,1174,472,1170,640,1150,640);});
        a.part("shelter_roof",()->d.ink("#FF55675A","#FF283F3A",2.2).m(798,500).l(996,350).l(1211,492).l(1192,516).l(998,391).l(819,523).z().end());
        a.part("shelter_roof_seams",()->{for(int i=0;i<8;i++){double x=831+i*22,y=493-i*16.2;d.line("#829EAD83",1.1,x,y,x+17,y+10);}d.line("#FF9C9270",2,809,502,995,365,1202,498);});
        a.part("shelter_bench",()->{d.poly("#FF977951","#FF38463B",1.4,902,570,1103,565,1123,579,913,587);d.line(p.wood,8,921,583,923,623);d.line(p.wood,8,1102,578,1099,618);});
        a.part("shelter_light",()->{d.ellipse("#26F4B970",null,1,1027,634,91,17);d.ellipse("#2CF9D492",null,1,1027,633,53,9);});
        a.part("shelter_firewood",()->{for(int i=0;i<5;i++){double x=873+i*16,y=634-i%2*8;d.line("#FF604B39",10,x,y,x+26,y-7);d.ellipse("#FFA88B62","#FF4E4938",.8,x,y,5,5);}});
    }
    private static void village(Ink a,Palette p)throws Exception {
        JungleCelInk d=a.d;
        a.gradient("village_lane","#FFAB9978","#FF746F58",()->d.ink("#FF8F8469",null,1).m(610,462).q(583,576,337,720).l(989,720).q(690,574,669,460).z().end());
        house(a,"house_left",251,579,.86,p);house(a,"house_far",654,505,.59,p);house(a,"house_right",1016,596,1.1,p);
        a.part("village_fence",()->{for(int i=0;i<8;i++){double x=70+i*48,y=608+i*1.7;d.poly(p.wood,null,1,x-3,y,x+4,y,x+5,y-58,x,y-64,x-5,y-58);}d.line("#FF8B8263",4,65,574,410,585);d.line(p.wood,4,65,599,410,610);});
        a.part("village_stones",()->{for(int i=0;i<13;i++){double x=486+i*37%296,y=526+i*13;d.ink("#FF8E9581","#FF515E51",.7).m(x,y).q(x+17,y-5,x+30,y+2).l(x+21,y+9).l(x-6,y+7).z().end();}});
    }
    private static void house(Ink a,String name,double x,double y,double scale,Palette p)throws Exception {
        JungleCelInk d=a.d.at(x,y,scale,0);
        a.part(name,()->{
            d.ellipse("#38212E32",null,1,0,6,112,16);d.poly("#FFB4A58A","#FF3D4B40",2,-83,0,-83,-123,0,-181,83,-123,83,0);
            d.poly("#FF5C5C56","#FF2C3B37",2,-105,-121,0,-198,108,-124,95,-110,0,-173,-95,-110);
            d.poly("#FF49504A","#FF39433A",1,-21,0,-21,-75,23,-75,23,0);d.line(p.wood,4,-81,-118,-81,0,80,0,80,-118);d.line(p.wood,4,0,-173,0,-79);
            for(int side:new int[]{-1,1}){double wx=side*52;d.poly("#FFF1C788","#FF4F5142",2,wx-15,-94,wx+15,-94,wx+15,-61,wx-15,-61);d.line(p.wood,2,wx,-93,wx,-62);d.line(p.wood,2,wx-14,-78,wx+14,-78);d.ellipse("#21FFDB93",null,1,wx,-72,31,35);}
            for(int i=0;i<6;i++)d.line("#687E8070",1,-74,-20-i*13,-29,-20-i*13);
        });
    }
    private static void foreground(Ink a,Palette p)throws Exception {
        JungleCelInk d=a.d;
        a.part("foreground_roots",()->{d.ink("#FF263D38",null,1).m(0,661).q(43,646,87,682).q(144,662,212,720).l(0,720).z().end();d.ink("#FF263D38",null,1).m(1280,649).q(1210,655,1174,686).q(1114,670,1050,720).l(1280,720).z().end();});
        a.part("foreground_grass",()->{for(int i=0;i<18;i++){double x=16+i*17,y=706-(i*13%29);d.ink(null,p.middle,2).m(x,y+10).q(x-9,y-12,x-20,y-17).m(x,y+10).q(x+4,y-17,x+12,y-23).end();double r=1280-x;d.ink(null,"#FF416454",2).m(r,y+8).q(r-7,y-16,r-17,y-20).end();}});
    }
    private static void weather(Ink a,JSONObject s,int frames,int fps)throws Exception {
        JungleCelInk d=a.d;String weather=s.getString("weather");double intensity=s.getDouble("intensity");
        if(weather.equals("rain"))for(int i=0;i<12;i++){
            final int n=i;a.part("rain_"+i,()->d.ink(null,"#C2B1D4D7",1.2).m(90+n*97,28).l(83+n*97-12*intensity,56).end());
            JSONObject object=a.layer.getJSONArray("objects").getJSONObject(a.layer.getJSONArray("objects").length()-1);object.getJSONObject("transform").put("opacity",intensity*.75);
            object.put("keys",periodic(frames,fps*2,i*(fps*2)/12,-36*intensity,560,intensity*.75));
        }
        if(weather.equals("wind"))for(int i=0;i<4;i++){
            final int n=i;a.part("wind_"+i,()->d.ink(null,"#91C9DBB4",1.2).m(120+n*41,320+n*53).c(158+n*41,293+n*53,224+n*41,354+n*53,280+n*41,323+n*53).end());
            JSONObject object=a.layer.getJSONArray("objects").getJSONObject(a.layer.getJSONArray("objects").length()-1);object.getJSONObject("transform").put("opacity",intensity*.6);
            object.put("keys",periodic(frames,fps*4,i*fps,570*intensity,0,intensity*.6));
        }
    }
    /** Two-second particle clock: sparse linear boundaries plus a one-frame hold at each wrap. */
    private static JSONArray periodic(int frames,int period,int phase,double dx,double dy,double opacity)throws Exception {
        JSONArray keys=new JSONArray();int frame=0;
        while(frame<frames){int local=(frame+phase)%period;int beforeWrap=frame+(period-1-local);
            key(keys,frame,local/(double)period,dx,dy,opacity,local==period-1?"hold":"linear");
            if(beforeWrap>frame&&beforeWrap<frames)key(keys,beforeWrap,(period-1)/(double)period,dx,dy,opacity,"hold");
            int next=beforeWrap+1;if(next>=frames){if(frames-1>Math.min(beforeWrap,frame))key(keys,frames-1,((frames-1+phase)%period)/(double)period,dx,dy,opacity,"linear");break;}frame=next;
        }
        return keys;
    }
    private static void key(JSONArray keys,int frame,double t,double dx,double dy,double opacity,String interpolation)throws Exception {
        if(keys.length()>0&&keys.getJSONObject(keys.length()-1).getInt("frame")==frame)return;
        keys.put(new JSONObject().put("frame",frame).put("interpolation",interpolation).put("transform",SketchDocument.transform().put("x",dx*t/1280).put("y",dy*t/720).put("opacity",opacity)));
    }
    /** Uniformly fit reference ink, gradient coordinates and reference displacement keys. */
    static void fit(JSONObject object,int width,int height)throws Exception {
        double scale=Math.min(width/1280d,height/720d),dx=(width-1280*scale)/2,dy=(height-720*scale)/2;
        JSONArray commands=new JSONArray(object.getJSONArray("commands").toString());for(int i=0;i<commands.length();i++){JSONArray c=commands.getJSONArray(i);for(int n=1;n<c.length();n+=2){c.put(n,(dx+c.getDouble(n)*1280*scale)/width);c.put(n+1,(dy+c.getDouble(n+1)*720*scale)/height);}}object.put("commands",commands).put("width",Math.max(.0005,object.getDouble("width")*720*scale/Math.min(width,height)));
        if(object.has("gradient")){JSONObject g=object.getJSONObject("gradient");for(String end:new String[]{"0","1"}){g.put("x"+end,(dx+g.getDouble("x"+end)*1280*scale)/width);g.put("y"+end,(dy+g.getDouble("y"+end)*720*scale)/height);}}
        JSONArray keys=object.getJSONArray("keys");for(int i=0;i<keys.length();i++){JSONObject t=keys.getJSONObject(i).getJSONObject("transform");t.put("x",t.getDouble("x")*1280*scale/width).put("y",t.getDouble("y")*720*scale/height);}
    }
    static void append(JSONObject doc,String layerId,JSONArray additions)throws Exception {
        JSONObject candidate=SketchDocument.copy(doc),layer=SketchDocument.layer(candidate,layerId);JSONArray objects=layer.getJSONArray("objects");int first=objects.length();for(int i=0;i<additions.length();i++)objects.put(additions.getJSONObject(i));
        SketchDocument.validate(candidate);validatePlacement(candidate,layerId,first);SketchDocument.layer(doc,layerId).put("objects",objects);
    }
    static void validatePlacement(JSONObject doc,String layerId,int first)throws Exception {
        JSONObject layer=SketchDocument.layer(doc,layerId);JSONArray objects=layer.getJSONArray("objects");int w=doc.getInt("width"),h=doc.getInt("height");
        for(int i=first;i<objects.length();i++){JSONObject object=objects.getJSONObject(i);Path geometry=SketchFrameRenderer.path(object.getJSONArray("commands"),w,h);int samples=layer.getJSONArray("keys").length()==0&&object.getJSONArray("keys").length()==0?1:doc.getInt("frameCount");
            for(int frame=0;frame<samples;frame++){if(Thread.currentThread().isInterrupted())throw new InterruptedException("Stage/prop construction cancelled");Matrix world=SketchFrameRenderer.matrix(SketchDocument.transformAt(layer,frame),w,h);world.preConcat(SketchFrameRenderer.matrix(SketchDocument.transformAt(object,frame),w,h));Path path=new Path(geometry);path.transform(world);RectF bounds=new RectF();path.computeBounds(bounds,true);if(bounds.left<-.01||bounds.top<-.01||bounds.right>w+.01||bounds.bottom>h+.01)throw SketchDocument.bad("Stage/prop geometry leaves canvas through its composed placement");}
        }
    }
}
