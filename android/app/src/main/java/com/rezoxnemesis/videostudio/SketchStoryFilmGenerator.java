package com.rezoxnemesis.videostudio;

import android.content.Context;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Locale;
import java.util.UUID;

/**
 * Original-vector story-to-film authoring. No photographs, cloud calls, inferred anatomy
 * or hidden bitmap inserts. Builds editable independently timed cels and true pose keys.
 * Each completed frame is rasterized by the existing SketchFrameRenderer/export pipeline.
 *
 * This is a deterministic story draft engine, not a learned text-to-film model.
 */
public final class SketchStoryFilmGenerator {
    public interface Listener {
        /** Called once the blank owned sketch and its live drawing session are committed. */
        void opened(String sketchId, String sessionId);
        void progress(int completedObjects, int totalObjects);
    }
    private static final int WIDTH=1280, HEIGHT=720;
    private static final int[] TIMES={0,1,2,3,4};
    private static final String[] THEMES={"struggle","race","nature","connection","adventure"};
    private SketchStoryFilmGenerator(){}

    private static final class Palette {
        final String sky, horizon, deep, far, city, ground, skin, skinShadow, coat, coatLight, ink, light, flare, rain;
        Palette(String... c) {
            sky=c[0];horizon=c[1];deep=c[2];far=c[3];city=c[4];ground=c[5];
            skin=c[6];skinShadow=c[7];coat=c[8];coatLight=c[9];
            ink=c[10];light=c[11];flare=c[12];rain=c[13];
        }
    }
    private static Palette palette(String theme,int act) {
        if("nature".equals(theme))return act==2
                ?new Palette("#FF204A54","#FFFFC27A","#FF173944","#FF437A65","#FF376758","#FF24463D","#FFC99275","#FF94634F","#FF314851","#FF687C66","#FF102830","#FFFCE1A2","#FFFFAB58","#FFB6E8C4")
                :new Palette("#FF102D42","#FF718C80","#FF0D2438","#FF2E5660","#FF204850","#FF223F40","#FFC99275","#FF94634F","#FF273D4D","#FF526D72","#FF10242D","#FFFFDBA1","#FFFFB85A","#FF9CD0CE");
        if("race".equals(theme))return act==2
                ?new Palette("#FF3B4773","#FFFF9C6A","#FF172A45","#FF706B86","#FF45436D","#FF2C304C","#FFC99275","#FF94634F","#FF2A3554","#FF6C728F","#FF0A162A","#FFFFE7A1","#FFFFAD6D","#FF9FC8DF")
                :new Palette("#FF0C1635","#FF4E3D77","#FF10162F","#FF283957","#FF39405D","#FF1B2341","#FFC99275","#FF94634F","#FF1D294D","#FF51669A","#FF0B1128","#FFC4E8FF","#FFFF5B89","#FF8BBAF9");
        if("connection".equals(theme))return act==2
                ?new Palette("#FF775773","#FFFFC9A7","#FF55445E","#FF986E86","#FF77617A","#FF5E536B","#FFC99275","#FF94634F","#FF56536C","#FF9B8197","#FF2D3048","#FFFFE5B7","#FFFF9E89","#FFEAC2DC")
                :new Palette("#FF302B51","#FF9B667C","#FF302848","#FF5C4B70","#FF5E4464","#FF48374E","#FFC99275","#FF94634F","#FF34365B","#FF9C6988","#FF28253E","#FFFDE1D1","#FFFFA5A6","#FFE9C1DA");
        return act==2
                ?new Palette("#FF243F6F","#FFFFC27C","#FF202F4C","#FF6B7B85","#FF4A6078","#FF334158","#FFD4A486","#FFAA765A","#FF273E56","#FF71849A","#FF192B44","#FFFFEBBA","#FFFFAB58","#FFB5DFE7")
                :new Palette("#FF111D39","#FF445A73","#FF11192C","#FF29374F","#FF35435B","#FF212B3E","#FFCCA185","#FF9B6F5B","#FF1D293C","#FF566782","#FF0A1425","#FFF2D8AE","#FFE6A774","#FF84A9C3");
    }
    static String classify(String story) {
        String s=story.toLowerCase(Locale.ROOT);
        if(any(s,"race","car","bike","chase","speed","road"))return "race";
        if(any(s,"jungle","forest","mountain","tree","river","nature","dragon"))return "nature";
        if(any(s,"friend","love","family","heart","relation","mother","father"))return "connection";
        if(any(s,"fight","battle","war","sword","quest","kingdom","hero","adventure"))return "adventure";
        return "struggle";
    }
    private static boolean any(String value,String... words){for(String word:words)if(value.contains(word))return true;return false;}
    private static String prompt(String value){
        if(value==null)throw SketchDocument.bad("Write a story or topic");
        String clean=value.replaceAll("[\\p{Cntrl}&&[^\\n]]"," ").trim().replaceAll("\\s+"," ");
        if(clean.length()<4||clean.length()>1200)throw SketchDocument.bad("A story must be 4..1200 characters");
        return clean;
    }
    public static JSONObject build(String id,String story,int seconds,int fps)throws Exception{
        SketchDocument.id(new JSONObject().put("id",id),"id");
        String narrative=prompt(story);
        if(seconds<4||seconds>12)throw SketchDocument.bad("Use a 4..12 second shot film");
        if(fps!=24&&fps!=30)throw SketchDocument.bad("Use 24 or 30 FPS");
        String theme=classify(narrative);
        int total=seconds*fps;
        JSONObject document=SketchDocument.create(new JSONObject().put("sketchId",id)
                .put("name",narrative.substring(0,Math.min(76,narrative.length())))
                .put("width",WIDTH).put("height",HEIGHT).put("fps",fps)
                .put("frameCount",total).put("background","#FF111827"));
        JSONArray layers=new JSONArray();
        document.put("layers",layers);
        int seed=narrative.hashCode()&0x7fffffff;
        for(int act=0;act<3;act++){
            int start=(int)((long)act*total/3);
            int stop=(int)((long)(act+1)*total/3);
            int span=stop-start;
            Palette palette=palette(theme,act);
            JSONObject bg=layer("story_bg_"+act,"Shot "+(act+1)+" · sky and depth");
            JSONObject city=layer("story_city_"+act,"Shot "+(act+1)+" · detailed environment");
            JSONObject hero=layer("story_actor_"+act,"Shot "+(act+1)+" · separated character parts");
            JSONObject fx=layer("story_fx_"+act,"Shot "+(act+1)+" · effects and captions");
            layers.put(bg).put(city).put(hero).put(fx);
            scenery(bg,city,fx,start,span,act,seed,palette,theme);
            character(hero,city,start,span,act,palette,theme);
            lettering(fx,narrative,theme,start,span,act,palette);
            // Slight depth-specific, long-take camera travel, without moving the character as a flat card.
            camera(bg,start,span,act==1?1.00:1.025,0);
            camera(city,start,span,act==1?1.04:1.055,-.012);
            camera(hero,start,span,act==1?1.02:1.04,.009);
        }
        SketchDocument.validate(document);
        verifyCoverage(document);
        return document;
    }
    private static JSONObject layer(String id,String name)throws Exception{
        return new JSONObject().put("id",id).put("name",name).put("visible",true)
                .put("locked",false).put("opacity",1).put("transform",SketchDocument.transform())
                .put("keys",new JSONArray()).put("objects",new JSONArray());
    }
    private static void camera(JSONObject layer,int start,int span,double zoom,double dx)throws Exception{
        JSONArray keys=layer.getJSONArray("keys");JSONObject initial=SketchDocument.transform();
        keys.put(new JSONObject().put("frame",start).put("transform",initial).put("interpolation","linear"));
        keys.put(new JSONObject().put("frame",start+span-1).put("transform",SketchDocument.transform()
                .put("scaleX",zoom).put("scaleY",zoom).put("x",dx)).put("interpolation","hold"));
    }
    private static JSONObject base(String id,String kind,String color,int start,int span,boolean fill)throws Exception{
        return new JSONObject().put("id",id).put("kind",kind).put("frame",start).put("exposure",span)
                .put("color",color).put("width",.003).put("fill",fill)
                .put("transform",SketchDocument.transform()).put("keys",new JSONArray());
    }
    private static JSONObject bounds(double x,double y,double w,double h)throws Exception{
        return new JSONObject().put("x",x).put("y",y).put("width",w).put("height",h);
    }
    private static JSONObject rect(String id,String c,int start,int span,double x,double y,double w,double h)throws Exception{
        return base(id,"rect",c,start,span,true).put("bounds",bounds(x,y,w,h));
    }
    private static JSONObject oval(String id,String c,int start,int span,double x,double y,double w,double h)throws Exception{
        return base(id,"ellipse",c,start,span,true).put("bounds",bounds(x,y,w,h));
    }
    private static JSONObject poly(String id,String c,int start,int span,double...xy)throws Exception{
        JSONArray points=new JSONArray();for(int n=0;n<xy.length;n+=2)points.put(new JSONArray().put(xy[n]).put(xy[n+1]).put(1));
        return base(id,"polygon",c,start,span,true).put("points",points);
    }
    private static JSONObject line(String id,String c,int start,int span,double x0,double y0,double x1,double y1,double weight)throws Exception{
        return base(id,"line",c,start,span,false).put("width",weight).put("points",new JSONArray()
                .put(new JSONArray().put(x0).put(y0).put(1)).put(new JSONArray().put(x1).put(y1).put(1)));
    }
    private static JSONObject gradient(JSONObject obj,String from,String to)throws Exception{
        obj.put("gradient",new JSONObject().put("startColor",from).put("endColor",to)
                .put("x0",.15).put("y0",.10).put("x1",.76).put("y1",.89));return obj;
    }
    private static void add(JSONObject layer,JSONObject object)throws Exception{layer.getJSONArray("objects").put(object);}
    private static void addAll(JSONObject layer,JSONObject...objects)throws Exception{for(JSONObject object:objects)add(layer,object);}
    private static int noise(int seed,int i){int v=seed^(i*0x9e3779b9);v^=v>>>16;v*=0x7feb352d;v^=v>>>15;v*=0x846ca68b;return (v^(v>>>16))&0x7fffffff;}
    private static void scenery(JSONObject sky,JSONObject city,JSONObject fx,int start,int span,int act,int seed,Palette p,String theme)throws Exception{
        add(sky,gradient(rect("sky_"+act,p.sky,start,span,0,0,1,1),p.sky,p.horizon));
        add(sky,gradient(oval("halo_"+act,p.flare,start,span,act==2?.67:.76,act==2?.12:.17,.145,.22),p.light,p.flare));
        add(sky,oval("sun_"+act,p.light,start,span,act==2?.735:.795,act==2?.19:.235,.065,.105));
        for(int n=0;n<7;n++){
            int r=noise(seed+act*31,n);
            double x=.03+(r%78)/100d,y=.12+((r/17)%35)/100d,w=.12+((r/33)%12)/100d;
            if(x+w>.98)w=.98-x;
            add(sky,oval("cloud_"+act+"_"+n,n%2==0?p.deep:p.far,start,span,x,y,w,.038+((r/113)%5)/100d));
        }
        if("nature".equals(theme)){
            add(city,poly("hill_far_"+act,p.far,start,span,0,.7,.10,.55,.18,.61,.34,.38,.50,.63,.7,.44,.85,.59,1,.39,1,1,0,1));
            add(city,poly("hill_near_"+act,p.city,start,span,0,.84,.1,.73,.22,.76,.32,.58,.44,.72,.55,.65,.68,.77,.80,.60,.90,.68,1,.59,1,1,0,1));
            for(int n=0;n<12;n++){int r=noise(seed,n+act*50);double x=.02+(n*.081),y=.65+((r/13)%16)/100d;
                addAll(city,line("trunk_"+act+"_"+n,p.deep,start,span,x,y,x,y-.12,.008),
                    poly("tree_"+act+"_"+n,n%2==0?p.city:p.far,start,span,x-.038,y-.035,x,y-.20,x+.038,y-.035));}
        }else{
            for(int n=0;n<15;n++){
                int r=noise(seed,act*101+n);double x=n/14d-.03,w=.041+((r>>>6)%30)/1000d,h=.23+((r>>>3)%33)/100d;
                double y=.80-h; if(x<0)x=0;if(x+w>1)w=1-x;
                add(city,rect("tower_"+act+"_"+n,n%3==0?p.city:p.far,start,span,x,y,w,h));
                add(city,rect("roof_"+act+"_"+n,p.deep,start,span,x,y,w,.007));
                for(int column=0;column<2;column++)for(int row=0;row<5;row++){
                    double wy=y+.023+row*.043;
                    if(wy+.012<.79&&column==0||column==1&&wy+.012<.79){
                        double wx=x+.009+column*.019;
                        if(wx+.009<x+w)add(city,rect("window_"+act+"_"+n+"_"+column+"_"+row,
                                row==2&&n%3==0?p.flare:p.deep,start,span,wx,wy,.009,.014));
                    }
                }
            }
        }
        addAll(city,gradient(rect("street_"+act,p.ground,start,span,0,.79,1,.21),p.city,p.deep),
                line("horizon_"+act,p.light,start,span,0,.797,1,.797,.004));
        for(int n=0;n<11;n++){
            int r=noise(seed+act,n+750);double x=(r%850)/1000d,y=.818+(n%5)*.032;
            add(city,oval("puddle_"+act+"_"+n,n%2==0?p.far:p.horizon,start,span,x,y,.08+((r/17)%8)/100d,.012));
        }
        for(int n=0;n<19;n++){
            int r=noise(seed+55,act*29+n);double x=.018+(r%950)/1000d,y=.025+((r/23)%680)/1000d;
            if(y>.89)y=.89;
            JSONObject rain=line("rain_"+act+"_"+n,p.rain,start,span,x,y,x-.015,y+.075,.0015);
            double shift=act==2?.11:.19;
            motion(rain,start,span,x,y,new double[]{0,-.009,-.012,-.02,-.025},new double[]{0,shift*.2,shift*.4,shift*.7,shift},new double[]{0,0,0,0,0});
            add(fx,rain);
        }
        if("race".equals(theme)||"adventure".equals(theme)){
            for(int n=0;n<8;n++)add(fx,line("speed_"+act+"_"+n,p.flare,start,span,.03,.13+n*.078,.23+(n%3)*.07,.11+n*.078,.002));
        }
    }
    /** Three true inbetween poses per beat, eased without expanding the app's supported key schema. */
    private static void motion(JSONObject object,int start,int span,double ax,double ay,double[] dx,double[] dy,double[] rotation)throws Exception{
        JSONObject base=object.getJSONObject("transform");base.put("anchorX",ax).put("anchorY",ay);
        JSONArray keys=new JSONArray();int last=-1;
        for(int i=0;i<4;i++){
            for(int k=0;k<3;k++){
                double t=k/3d,e=t*t*(3-2*t);
                int frame=start+(int)Math.round((span-1)*(i+t)/4d);
                if(frame<=last)continue;last=frame;
                JSONObject transform=SketchDocument.copy(base)
                        .put("x",dx[i]+(dx[i+1]-dx[i])*e)
                        .put("y",dy[i]+(dy[i+1]-dy[i])*e)
                        .put("rotation",rotation[i]+(rotation[i+1]-rotation[i])*e);
                keys.put(new JSONObject().put("frame",frame).put("transform",transform).put("interpolation","linear"));
            }
        }
        int finalFrame=start+span-1;
        keys.put(new JSONObject().put("frame",finalFrame).put("transform",SketchDocument.copy(base)
                .put("x",dx[4]).put("y",dy[4]).put("rotation",rotation[4])).put("interpolation","hold"));
        object.put("keys",keys);
    }
    private static void character(JSONObject hero,JSONObject ground,int start,int span,int act,Palette p,String theme)throws Exception{
        double c=act==1?.5:.43,scale=act==1?1.10:1;
        double headY=.29, headW=.104*scale,headH=.155*scale;
        double shoulderY=.51,hipY=.78;
        double walk="race".equals(theme)?1.9:1.0;
        double[] travel=act==0?new double[]{-.07,-.045,-.012,.013,.037}:act==1?
                new double[]{.026,.014,0,-.01,-.012}:new double[]{-.025,-.018,-.014,0,.025};
        double[] bob=act==0?new double[]{.005,-.007,.007,-.004,.003}:act==1?
                new double[]{0,.013,.056,.08,.071}:new double[]{.07,.041,.024,.007,0};
        double[] still={0,0,0,0,0};
        double[] sway=act==0?new double[]{-1.5,3,-3,2,1}:act==1?
                new double[]{-4,-12,-19,-17,-15}:new double[]{-11,-7,1,0,0};
        add(ground,oval("shadow_"+act,p.deep,start,span,c-.075,.907,.19,.034));
        JSONObject leftLeg=gradient(poly("leg_left_"+act,p.coat,start,span,
                c-.051,.745,c-.004,.745,c-.009,.925,c-.075,.929,c-.076,.904),p.coatLight,p.deep);
        JSONObject rightLeg=gradient(poly("leg_right_"+act,p.coat,start,span,
                c+.005,.745,c+.047,.745,c+.075,.915,c+.008,.935,c-.006,.886),p.coatLight,p.deep);
        JSONObject bootLeft=oval("boot_left_"+act,p.ink,start,span,c-.083,.902,.096,.045);
        JSONObject bootRight=oval("boot_right_"+act,p.ink,start,span,c+.009,.906,.094,.041);
        double[] front=act==0?new double[]{-18*walk,9*walk,-14*walk,14*walk,-8}:act==1?
                new double[]{3,22,40,45,42}:new double[]{40,23,9,-7,3};
        double[] back=act==0?new double[]{16*walk,-10*walk,13*walk,-11*walk,10}:act==1?
                new double[]{-6,-28,-40,-45,-44}:new double[]{-40,-17,-10,10,-4};
        motion(leftLeg,start,span,c-.04,hipY,travel,bob,front);
        motion(rightLeg,start,span,c+.025,hipY,travel,bob,back);
        motion(bootLeft,start,span,c-.04,hipY,travel,bob,front);
        motion(bootRight,start,span,c+.025,hipY,travel,bob,back);
        addAll(hero,leftLeg,rightLeg,bootLeft,bootRight);
        JSONObject body=gradient(poly("coat_body_"+act,p.coat,start,span,
                c-.073,.49,c-.016,.475,c+.063,.489,c+.086,.786,c-.095,.798),p.coatLight,p.coat);
        motion(body,start,span,c,shoulderY,travel,bob,sway);add(hero,body);
        JSONObject lapelL=poly("lapel_left_"+act,p.coatLight,start,span,c-.036,.491,c-.006,.506,c-.020,.677,c-.058,.519);
        JSONObject lapelR=poly("lapel_right_"+act,p.far,start,span,c+.005,.509,c+.052,.498,c+.034,.545,c+.014,.666);
        motion(lapelL,start,span,c,shoulderY,travel,bob,sway);motion(lapelR,start,span,c,shoulderY,travel,bob,sway);
        addAll(hero,lapelL,lapelR);
        JSONObject leftArm=gradient(poly("arm_left_"+act,p.coat,start,span,
                c-.063,.494,c-.084,.523,c-.129,.7,c-.083,.718,c-.029,.532),p.coatLight,p.coat);
        JSONObject rightArm=gradient(poly("arm_right_"+act,p.coat,start,span,
                c+.057,.49,c+.092,.518,c+.129,.699,c+.085,.721,c+.031,.548),p.coatLight,p.deep);
        double[] left=act==0?new double[]{12,-15,11,-19,9}:act==1?new double[]{4,-21,-52,-69,-60}:new double[]{-60,-35,-12,-5,3};
        double[] right=act==0?new double[]{-16,16,-13,20,-10}:act==1?new double[]{-4,17,45,60,54}:new double[]{54,25,0,-13,-37};
        motion(leftArm,start,span,c-.068,shoulderY,travel,bob,left);
        motion(rightArm,start,span,c+.065,shoulderY,travel,bob,right);addAll(hero,leftArm,rightArm);
        JSONObject face=gradient(oval("face_"+act,p.skin,start,span,c-headW/2,headY,headW,headH),p.skin,p.skinShadow);
        motion(face,start,span,c,headY+.1,travel,bob,sway);add(hero,face);
        JSONObject ear=oval("ear_"+act,p.skinShadow,start,span,c+headW*.39,headY+.068,.016,.042);
        motion(ear,start,span,c,headY+.1,travel,bob,sway);add(hero,ear);
        JSONObject hair=poly("hair_"+act,p.ink,start,span,
                c-.061,headY+.076,c-.065,headY-.012,c-.034,headY-.037,c+.018,headY-.045,
                c+.058,headY-.016,c+.056,headY+.074,c+.027,headY+.014,c-.008,headY+.045,c-.036,headY+.024);
        motion(hair,start,span,c,headY+.1,travel,bob,sway);add(hero,hair);
        JSONObject hairRim=line("hair_rim_"+act,p.coatLight,start,span,c-.039,headY+.002,c+.027,headY-.017,.004);
        motion(hairRim,start,span,c,headY+.1,travel,bob,sway);add(hero,hairRim);
        for(int eye=0;eye<2;eye++){
            double x=c+(eye==0?-.025:.017);
            JSONObject brow=line("brow_"+act+"_"+eye,p.ink,start,span,x,headY+.085,x+.014,headY+.083,.003);
            JSONObject pupil=oval("eye_"+act+"_"+eye,p.ink,start,span,x+.003,headY+.095,.008,.01);
            motion(brow,start,span,c,headY+.1,travel,bob,sway);
            motion(pupil,start,span,c,headY+.1,travel,bob,sway);addAll(hero,brow,pupil);
        }
        JSONObject nose=line("nose_"+act,p.skinShadow,start,span,c+.003,headY+.097,c+.015,headY+.124,.002);
        JSONObject mouth=line("mouth_"+act,p.ink,start,span,c-.017,headY+.132,c+.019,headY+(act==2?.124:.14),.0028);
        motion(nose,start,span,c,headY+.1,travel,bob,sway);motion(mouth,start,span,c,headY+.1,travel,bob,sway);
        addAll(hero,nose,mouth);
        for(int n=0;n<5;n++){
            JSONObject button=oval("button_"+act+"_"+n,p.flare,start,span,c+.012,.576+n*.038,.006,.01);
            motion(button,start,span,c,shoulderY,travel,bob,sway);add(hero,button);
        }
        JSONObject scarf=poly("scarf_"+act,p.flare,start,span,c-.055,.484,c+.028,.492,c+.096,.527,c+.018,.523,c-.053,.518);
        double[] flutter=act==0?new double[]{-8,4,-7,6,-3}:act==1?new double[]{-3,-10,-16,-8,-4}:new double[]{8,14,-9,10,-6};
        motion(scarf,start,span,c,shoulderY,travel,bob,flutter);add(hero,scarf);
        if("connection".equals(theme)&&act==2){
            JSONObject companion=oval("companion_"+act,p.far,start,span,.71,.45,.105,.2);
            JSONObject silhouette=poly("companion_coat_"+act,p.deep,start,span,.68,.61,.76,.61,.81,.91,.62,.92);
            addAll(hero,companion,silhouette);
        }
    }
    private static void lettering(JSONObject layer,String story,String theme,int start,int span,int act,Palette p)throws Exception{
        String line;
        if("struggle".equals(theme)){
            line=act==0?"EVERY DAY IS A BATTLE":act==1?"EVEN STRONG PEOPLE FALL":"BUT HE RISES AGAIN";
        }else if("race".equals(theme))line=act==0?"THE STARTING LINE":act==1?"THE LIMIT":"THE WILL TO CONTINUE";
        else if("nature".equals(theme))line=act==0?"BEYOND THE KNOWN":act==1?"THE UNKNOWN PATH":"ONE STEP FORWARD";
        else if("connection".equals(theme))line=act==0?"TWO DIFFERENT WORLDS":act==1?"THE DISTANCE":"A CHOICE TO CONNECT";
        else line=act==0?"THE CALL":act==1?"THE TRIAL":"THE NEXT CHAPTER";
        String small=act==0?story.substring(0,Math.min(64,story.length())):"CHAPTER "+(act+1)+" / 3";
        add(layer,rect("caption_panel_"+act,"#B5131C2C",start,span,.055,.812,.69,.125));
        JSONObject caption=base("caption_"+act,"text",p.light,start,span,false)
                .put("bounds",bounds(.084,.827,.63,.063)).put("text",line)
                .put("fontFamily","sans-serif-condensed").put("fontStyle","bold").put("fontSize",.042);
        add(layer,caption);
        add(layer,base("caption_small_"+act,"text",p.rain,start,span,false)
                .put("bounds",bounds(.086,.889,.61,.034)).put("text",small)
                .put("fontFamily","sans-serif").put("fontStyle","normal").put("fontSize",.019));
        add(layer,line("caption_bar_"+act,p.flare,start,span,.073,.84,.073,.93,.005));
    }
    private static void verifyCoverage(JSONObject doc)throws Exception{
        int frames=doc.getInt("frameCount");
        int[] visible=new int[frames];
        HashSet<String> ids=new HashSet<>();
        JSONArray layers=doc.getJSONArray("layers");
        for(int i=0;i<layers.length();i++){
            JSONArray objects=layers.getJSONObject(i).getJSONArray("objects");
            for(int j=0;j<objects.length();j++){
                JSONObject o=objects.getJSONObject(j);
                if(!ids.add(o.getString("id")))throw SketchDocument.bad("Repeated storyboard part identity");
                int a=o.getInt("frame"),b=a+o.getInt("exposure");
                for(int f=a;f<b;f++)visible[f]++;
            }
        }
        for(int f=0;f<frames;f++)if(visible[f]<15)throw SketchDocument.bad("Storyboard contains a blank or sparsely rendered frame: "+f);
    }
    /** Serial native commits; every accepted geometry step is journaled for real live drawing replay. */
    public static JSONObject create(Context context,String story,int seconds,int fps,boolean live,Listener listener)throws Exception{
        String id="story_"+UUID.randomUUID().toString().replace("-","");
        JSONObject completed=build(id,story,seconds,fps);
        if(!live){
            try(SketchProjectStore store=new SketchProjectStore(context)){
                JSONObject receipt=store.importOwnedDocument(completed,"story_import_"+id);
                if(listener!=null)listener.progress(count(completed),count(completed));
                return receipt.put("live",false).put("storyTheme",classify(story));
            }
        }
        JSONObject empty=SketchDocument.copy(completed);
        JSONArray layers=empty.getJSONArray("layers");
        for(int i=0;i<layers.length();i++)layers.getJSONObject(i).put("objects",new JSONArray());
        SketchDocument.validate(empty);
        String session="story_live_"+UUID.randomUUID().toString().replace("-","");
        try(SketchProjectStore store=new SketchProjectStore(context)){
            store.importOwnedDocument(empty,"story_create_"+id);
        }
        SketchLiveDrawing.begin(context,new JSONObject().put("sketchId",id).put("expectedRevision",1)
                .put("operationId",session).put("strokeDurationMs",80).put("drawingMethod",SketchDrawingPolicy.ORIGINAL));
        if(listener!=null)listener.opened(id,session);
        long revision=1;
        int total=count(completed),done=0,index=0;
        try{
            for(int i=0;i<layers.length();i++){
                JSONObject fullLayer=completed.getJSONArray("layers").getJSONObject(i);
                JSONArray objects=fullLayer.getJSONArray("objects");
                for(int j=0;j<objects.length();){
                    JSONArray operations=new JSONArray();
                    for(;j<objects.length()&&operations.length()<12;j++){
                        operations.put(new JSONObject().put("type","add_object")
                                .put("layerId",fullLayer.getString("id")).put("object",objects.getJSONObject(j)));
                    }
                    String operationId=session+"_b"+index++;
                    JSONObject result=SketchEditOps.executeLiveEdit(context,new JSONObject().put("sketchId",id)
                            .put("operationId",operationId).put("expectedRevision",revision)
                            .put("operations",operations),session);
                    revision=result.getLong("revision");
                    done+=operations.length();
                    if(listener!=null)listener.progress(done,total);
                }
            }
            SketchEditOps.execute(context,"sketch_live_end",new JSONObject()
                    .put("sketchId",id).put("sessionId",session).put("expectedRevision",revision));
            try(SketchProjectStore store=new SketchProjectStore(context)){
                JSONObject actual=store.get(id);
                SketchDocument.validate(actual);
                verifyCoverage(actual);
                if(count(actual)!=total)throw SketchDocument.bad("Some storyboard parts did not persist");
                return new JSONObject().put("sketchId",id).put("revision",revision).put("sessionId",session)
                        .put("frameCount",actual.getInt("frameCount")).put("fps",fps)
                        .put("live",true).put("storyTheme",classify(story)).put("persisted",true);
            }
        }catch(Exception error){
            try{SketchEditOps.execute(context,"sketch_live_end",new JSONObject()
                    .put("sketchId",id).put("sessionId",session).put("expectedRevision",revision));}
            catch(Exception ignored){}
            throw error;
        }
    }
    private static int count(JSONObject document)throws Exception{
        int total=0;JSONArray layers=document.getJSONArray("layers");
        for(int i=0;i<layers.length();i++)total+=layers.getJSONObject(i).getJSONArray("objects").length();
        return total;
    }
}
