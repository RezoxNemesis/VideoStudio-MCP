package com.rezoxnemesis.videostudio;
import org.json.*;
/** Original cel production for the owner's jungle/dragon story. */
public final class SketchJungleFilm {
    private SketchJungleFilm(){}
    public interface Progress {void celSaved(int completed,int total)throws Exception;}
    public static JSONObject populateRange(android.content.Context context,String bookId,int pageStart,int pageCount,Progress progress)throws Exception {return populateRange(context,bookId,pageStart,pageCount,progress,null);}
    public static JSONObject populateRange(android.content.Context context,String bookId,int pageStart,int pageCount,Progress progress,double[] mouthFrames)throws Exception {
        int[] starts=celStarts();if(pageStart<0||pageCount<1||pageCount>64||(long)pageStart+pageCount>starts.length)throw SketchDocument.bad("Draw 1..64 original cels inside this film's book");
        SketchBookStore.execute(context,"sketch_book_create",new JSONObject().put("bookId",bookId).put("operationId","jungle_original_create").put("name","Beneath the canopy · original cel film").put("pageCount",starts.length).put("width",1280).put("height",720).put("fps",FPS).put("background","#FF092c38"));
        JSONArray holds=new JSONArray();for(int page=0;page<starts.length;page++)if(exposure(starts[page])==2)holds.put(new JSONObject().put("page",page).put("frames",2));
        int batches=(holds.length()+127)/128;
        for(int batch=0;batch<batches;batch++){JSONArray changes=new JSONArray();for(int n=batch*128;n<Math.min(holds.length(),(batch+1)*128);n++)changes.put(holds.getJSONObject(n));SketchBookStore.execute(context,"sketch_book_edit",new JSONObject().put("bookId",bookId).put("operationId","jungle_timing_"+batch).put("expectedRevision",1+batch).put("exposures",changes));}
        JSONArray cues=new JSONArray();for(int n=0;n<SHOTS.length;n++)cues.put(new JSONObject().put("id","shot_"+n).put("type","shot").put("startFrame",CUTS[n]*FPS).put("endFrame",CUTS[n+1]*FPS-1).put("label",SHOTS[n]));
        SketchBookStore.execute(context,"sketch_book_edit",new JSONObject().put("bookId",bookId).put("operationId","jungle_shots").put("expectedRevision",1+batches).put("cues",cues));
        try(SketchProjectStore sketches=new SketchProjectStore(context);SketchBookStore books=new SketchBookStore(context)){
            int preserved=0;
            for(int page=pageStart;page<pageStart+pageCount;page++){
                if(Thread.currentThread().isInterrupted())throw new InterruptedException("Original film retained for resume");
                String id=bookId+"_p"+page;
                if(hasCel(sketches,id))preserved++;
                else {
                    JSONObject drawing=drawCel(id,starts[page],mouthFrames==null?Double.NaN:mouthFrames[starts[page]]);
                    try{sketches.importOwnedDocument(drawing,"jungle_original_ink_v1");}
                    catch(IllegalStateException conflict){
                        // Navigation may allocate this page between the check and import.
                        // Keep every existing cel, including an intentionally blank one.
                        if(!"SKETCH_ALREADY_EXISTS".equals(conflict.getMessage())||!hasCel(sketches,id))throw conflict;
                        preserved++;
                    }
                }
                books.attachImportedPage(bookId,page,id);
                if(progress!=null)progress.celSaved(page+1,starts.length);
            }
            JSONObject book=books.get(bookId);SketchSequenceTimeline clock=new SketchSequenceTimeline(book,0,book.getInt("pageCount"));int fps=book.getInt("fps");return new JSONObject().put("bookId",bookId).put("pageStart",pageStart).put("savedPages",pageCount).put("preservedPages",preserved).put("allocatedCels",book.getInt("createdPages")).put("totalCels",starts.length).put("frameCount",clock.frames()).put("fps",fps).put("durationSeconds",clock.frames()/(double)fps).put("bookRevision",book.getLong("revision")).put("artwork","original editable vector cels").put("imageGenerationUsed",false).put("imageReferences",0).put("phoneExecutionVerified",false);
        }
    }
    private static boolean hasCel(SketchProjectStore store,String id)throws Exception {
        try{store.get(id);return true;}
        catch(IllegalArgumentException unknown){if(unknown.getMessage()!=null&&unknown.getMessage().startsWith("UNKNOWN_SKETCH"))return false;throw unknown;}
    }
    public static int[] celStarts(){java.util.ArrayList<Integer> values=new java.util.ArrayList<>();for(int f=0;f<FRAMES;f+=exposure(f))values.add(f);int[] result=new int[values.size()];for(int i=0;i<result.length;i++)result[i]=values.get(i);return result;}
    private static int exposure(int f){return f>=21*FPS&&f<66*FPS||f>=84*FPS&&f<98*FPS?1:2;}
    public static JSONObject dialogueControls(String language)throws Exception {boolean hindi=language.equals("hi-IN");if(!hindi&&!language.equals("en-US"))throw SketchDocument.bad("Choose Hindi or English");JSONArray lines=new JSONArray();for(int i=0;i<EN.length;i++)lines.put(new JSONObject().put("lineId","line_"+i).put("characterId","traveller").put("voice",hindi?"hm_omega":"am_adam").put("text",hindi?HI[i]:EN[i]).put("startMs",Math.round(LINE_START[i]*1000)).put("slotMs",Math.round(LINE_SLOTS[i]*1000)).put("speed",.96));return DialogueControls.validate(new JSONObject().put("language",language).put("durationMs",120000).put("lines",lines));}
    public static final int FPS=24,FRAMES=2880;
    public static JSONObject saveModelLibrary(android.content.Context context,String bookId)throws Exception {
        String id=bookId+"_design";JSONObject doc=modelSheet(id);try(SketchProjectStore store=new SketchProjectStore(context)){store.importOwnedDocument(doc,"jungle_model_v1");}
        JSONArray components=new JSONArray();for(String view:new String[]{"front","quarter","profile","back","overhead","anger","realize","shame"}){
            String component=bookId+"_"+view;JSONObject result=SketchComponentLibrary.execute(context,"sketch_component_save",new JSONObject().put("componentId",component).put("sketchId",id).put("expectedRevision",1).put("operationId","jungle_"+view).put("name","Traveller · "+view).put("category",view.equals("anger")||view.equals("realize")||view.equals("shame")?"expression":"turn").put("frameIndex",0).put("layerIds",new JSONArray().put(view)));components.put(result);
        }return new JSONObject().put("sketchId",id).put("components",components);
    }
    public static JSONObject modelSheet(String id)throws Exception {
        JSONObject doc=SketchDocument.create(new JSONObject().put("sketchId",id).put("name","Traveller · original construction, turns and acting").put("width",1280).put("height",720).put("fps",24).put("frameCount",1).put("background","#FFe1dfcb"));JSONArray layers=new JSONArray();doc.put("layers",layers);JungleCelInk page=layer(layers,"paper","Construction sheet"),labels=layer(layers,"labels","Views and expressions");page.poly("#e1dfcb",null,1,0,0,1280,0,1280,720,0,720);page.line("#a5b19c",1,25,398,1255,398);labels.text("TRAVELLER / ORIGINAL CHARACTER CONSTRUCTION","#304f50",640,20,25);
        JungleFilmHero.body(layer(layers,"front","Front figure"),135,389,1.18,1,"stance",0,0,"watch",false,0);JungleFilmHero.body(layer(layers,"quarter","Three quarter"),381,389,1.18,1,"stance",0,.4,"watch",false,0);JungleFilmHero.profile(layer(layers,"profile","Profile drawing"),627,389,1.18);JungleFilmHero.body(layer(layers,"back","Back construction"),870,389,1.18,1,"stance",0,.9,"watch",false,0);JungleFilmHero.overhead(layer(layers,"overhead","Overhead construction"),1100,277,1.1,0,0);
        String[] views={"FRONT","THREE QUARTER","PROFILE","BACK","ABOVE"};for(int n=0;n<views.length;n++)labels.text(views[n],"#36595a",135+n*246,70,18,220);
        JungleFilmHero.portrait(layer(layers,"anger","Focused / angry").at(240,640,1.45,0),1,"angry",.3,0);JungleFilmHero.portrait(layer(layers,"realize","Shock and recognition").at(640,640,1.45,0),2,"realize",.12,.2);JungleFilmHero.portrait(layer(layers,"shame","Shame / self-disgust").at(1040,640,1.45,0),3,"shame",.1,0);labels.text("ACTING STUDIES: ANGER → RECOGNITION → REMORSE","#36595a",640,430,21);SketchDocument.validate(doc);return doc;
    }
    static final int[] CUTS={0,6,11,17,21,26,30,35,39,44,49,53,57,62,66,71,78,84,90,97,104,111,120};
    static final String[] SHOTS={"A living canopy","The traveller","The guardian","Challenge","Claw and dodge","Across the roots","First exchange","Fire and earth","Wind step","Three flashes","Above the canopy","Tail sweep","Skybreaker","A mother's shield","The small cry","The cub","Mother and child","Recognition","What have I become?","The apology","Give them space","Beneath the canopy"};
    static final double[] LINE_START={7.2,17.2,36,40,57.4,67,85,92,98,112};
    static final double[] LINE_SLOTS={3.7,3.5,2.8,3.8,4,3.8,4.8,4.5,5.5,6.5};
    static final String[] EN={"Fresh claw marks... This way.","You won't get past me!","Too slow!","Wind step!","Skybreaker!","What... was that sound?","A cub... You were protecting your baby.","And I called you a monster.","I'm sorry. I should have looked.","I'll make sure no one comes this way."};
    static final String[] HI={"ताज़े पंजों के निशान... इसी तरफ़।","तुम मुझसे आगे नहीं जा सकती!","बहुत धीमी हो!","वायु चाल!","आकाश प्रहार!","यह... कैसी आवाज़ थी?","एक बच्चा... तुम अपने बच्चे को बचा रही थी।","और राक्षस मैंने तुम्हें समझ लिया।","मुझे माफ़ कर दो। मुझे पहले देखना चाहिए था।","मैं किसी को इस ओर नहीं आने दूँगा।"};
    public static JSONObject drawCel(String id,int globalFrame)throws Exception {return drawCel(id,globalFrame,Double.NaN);}
    public static JSONObject drawCel(String id,int globalFrame,double mouthOpening)throws Exception {
        if(globalFrame<0||globalFrame>=FRAMES)throw SketchDocument.bad("Frame outside the original jungle film");
        double t=globalFrame/(double)FPS,lip=Double.isNaN(mouthOpening)?mouth(t):mouthOpening;int shot=shot(t);double u=(t-CUTS[shot])/(CUTS[shot+1]-CUTS[shot]);
        JSONObject doc=SketchDocument.create(new JSONObject().put("sketchId",id).put("name",SHOTS[shot]+" · "+globalFrame).put("width",1280).put("height",720).put("fps",FPS).put("frameCount",1).put("background","#FF092c38"));
        JSONArray layers=new JSONArray();doc.put("layers",layers);
        JungleCelInk bg=layer(layers,"background","Painted canopy and deep jungle"),ground=layer(layers,"ground","Roots, rocks and nest"),backFx=layer(layers,"back_fx","Wind light and ground shadows"),dragon=layer(layers,"dragon","Mother dragon · original redrawn contours"),hero=layer(layers,"hero","Traveller · original anatomy and expressions"),front=layer(layers,"foreground","Foreground leaves and vines"),fx=layer(layers,"effects","Drawn action, particles and ink"),titles=layer(layers,"titles","Cinematic frame");
        JungleFilmScenery.draw(bg,ground,front,t,shot>=17,shot>=19?u:0);if(shot==3||shot==17||shot==18)front.layer.put("visible",false);
        double hx=325,hy=567,hs=1.38,dx=914,dy=547,ds=1.22,dp=wave(t,1),yaw=.36;
        String action="stance",de="watch",emotion="angry";boolean heroVisible=true,dragonVisible=true,sword=true;
        if(shot==0){heroVisible=false;dragonVisible=false;for(int i=0;i<5;i++){double x=370+u*220+i*46;fx.ink(null,"#223f39",2).m(x,164-i*9).q(x+7,156-i*9,x+15,164-i*9).q(x+24,154-i*9,x+32,162-i*9).end();}}
        else if(shot==1){dragonVisible=false;hx=320+u*100;action="retreat";emotion="watch";dp=wave(t,1.5);yaw=.35-.75*u;}
        else if(shot==2){heroVisible=false;dx=902-30*ease(u);ds=1.38;dy=610;de=u<.5?"watch":"roar";}
        else if(shot==3){heroVisible=false;dragonVisible=false;JungleFilmHero.portrait(hero.at(610,378,3.65,0),t,"angry",.32-curve(u)*.55,lip);}
        else if(shot==4){de="lunge";dp=pulse(u,.23,.57);hx=350-90*ease(Math.min(1,u*1.7));action="dodge";hy=568;}
        else if(shot==5){hx=220+u*315;hy=572-35*Math.sin(u*Math.PI);action="run";dp=wave(t,2.1);yaw=u>.55?.82:.35;dx=980;de="watch";}
        else if(shot==6){hx=398+75*pulse(u,.27,.64);action="slash";dp=pulse(u,.2,.7);de="lunge";dx=936;}
        else if(shot==7){de="breath";hx=290-60*u;action="dodge";dp=wave(t,1);fire(backFx,700,333,t,u);}
        else if(shot==8){hx=361;action="charge";dp=u;if(u<.45){heroVisible=false;dragonVisible=false;front.layer.put("visible",false);JungleFilmHero.portrait(hero.at(612,387,3.7,-3),t,"angry",.24,lip);}wind(backFx,hx+35,430,90+80*u,t);for(int n=0;n<5;n++)fx.ink(null,"#7df1df",1.3).m(hx-100+n*43,588).q(hx-35+n*40,520,hx-67+n*39,447).end();}
        else if(shot==9){double cycle=wave(t-44,1.35);hx=257+465*ease(cycle);hy=562-55*Math.sin(cycle*Math.PI);action=cycle>.6?"slash":"run";dp=cycle;ds=1.15;dx=934;de="lunge";speed(fx,t,hx,hy,1);}
        else if(shot==10){hx=480+132*u;hy=465-92*Math.sin(u*Math.PI);action="leap";dp=u;yaw=u<.35?.82:.32;de="roar";ds=1.2;dx=919;wind(fx,hx,hy-143,90,t);}
        else if(shot==11){de="tail";dp=u;action=u<.5?"leap":"dodge";hx=600-260*ease(u);hy=u<.5?490:569;speed(fx,t,730,550,.5);}
        else if(shot==12){action=u<.45?"charge":"leap";dp=u;hx=350+100*ease(u);hy=u<.45?567:492-92*ease((u-.45)/.55);de="shield";wind(backFx,hx+80,hy-180,120+80*u,t);energy(fx,hx+40,hy-210,125+u*110,t);}
        else if(shot==13){heroVisible=false;dx=865;ds=1.37;dy=590;de="shield";for(int i=0;i<12;i++){double a=i*.6,x=100+((t*75+i*89)%1100),y=120+((i*93)%400);fx.ink(null,"#68b6e3bd",1.3).m(x,y).l(x+38,y+19).end();}}
        else if(shot==14){action="leap";hx=473;hy=418+28*ease(u);de="shield";energy(fx,hx+45,hy-222,100*(1-u),t);dp=.7;}
        else if(shot==15){heroVisible=false;dx=970;dy=600;ds=2.0;de="shield";JungleFilmDragon.cub(hero,575,570,1.3,t,"afraid");}
        else if(shot==16){heroVisible=false;dx=970;dy=600;ds=2.0;de="nuzzle";JungleFilmDragon.cub(hero,575,570,1.3,t,u>.42?"happy":"afraid");}
        else if(shot==17||shot==18){heroVisible=false;dragonVisible=false;String e=shot==18?"shame":u<.33?"angry":"realize";double turn=shot==18?.1+u*.24:.45-.55*ease(u);JungleFilmHero.portrait(hero.at(623,380+5*Math.sin(t),3.9,0),t,e,turn,lip);if(shot==18){fx.ink(null,"#747c7774",1.1).m(478,179).q(443,314,466,422).end();}}
        else if(shot==19){hx=361;hs=1.48;action="kneel";emotion="shame";de="shield";dx=977;dy=570;sword=false;JungleFilmHero.blade(ground,449,594,8,false);}
        else if(shot==20){hx=346-92*ease(u);action="retreat";emotion="shame";yaw=.82;sword=false;de="nuzzle";JungleFilmHero.blade(ground,449,594,8,false);}
        else if(shot==21){hx=223-140*u;hy=575;hs=1.18;action="retreat";emotion="watch";yaw=.82;sword=false;de="nuzzle";dx=946;dy=553;ds=1.16;JungleFilmDragon.cub(hero,857,555,.85,t,"happy");}
        boolean macro=shot==6&&u>.68,overhead=shot==10&&u>.4;
        if(macro){heroVisible=false;dragonVisible=false;front.layer.put("visible",false);JungleFilmDragon.closeClaw(dragon,780+3*Math.sin(t*18),270,3);hero.ink("#36566a",JungleFilmHero.INK,4).m(0,610).l(0,475).q(178,432,360,410).l(399,452).q(197,550,0,610).z().end();JungleFilmHero.blade(hero.at(400,440,3,0),0,0,7,true);hero.at(400,440,3,0).ink(JungleFilmHero.SKIN,JungleFilmHero.INK,1.2).m(-11,-7).q(-5,-13,1,-8).l(4,-2).q(12,-8,14,-1).l(13,8).q(1,12,-10,4).z().end();impact(fx,807,435,t,.65+.2*Math.sin(t*13));}
        if(overhead){heroVisible=false;dragonVisible=false;bg.layer.put("objects",new JSONArray());ground.layer.put("objects",new JSONArray());front.layer.put("objects",new JSONArray());bg.poly("#355549",null,1,0,0,1280,0,1280,720,0,720);for(int i=0;i<16;i++){double xx=i*103%1280,yy=i*139%720;bg.ink(null,i%2==0?"#557258":"#193d36",9).m(xx-80,yy-90).q(xx+69,yy-40,xx+91,yy+126).end();}JungleFilmDragon.overhead(dragon,828,363,1.2,t);JungleFilmHero.overhead(hero,417+u*135,371,1.7,t,-24+u*75);wind(fx,590,360,150,t);}
        backFx.ellipse("#46202e2a",null,1,hx+10,583,80,12);backFx.ellipse("#50102628",null,1,dx+3,dy+10,190*ds,24);
        if(dragonVisible)JungleFilmDragon.draw(dragon,dx,dy,ds,t,de,dp,false);
        double[] tip=null;if(heroVisible)tip=JungleFilmHero.body(hero,hx,hy,hs,t,action,dp,yaw,emotion,sword,lip);
        if(shot==6&&!macro&&dp>.65&&dp<.9&&tip!=null){impact(fx,tip[0],tip[1],t,Math.sin((dp-.65)/.25*Math.PI));}
        if(shot==9&&wave(t-44,1.35)>.65&&tip!=null){impact(fx,tip[0],tip[1],t,.8);}
        if(shot==4&&u>.35&&u<.65){dust(fx,430,567,t,u);}
        if(shot==11&&u>.48&&u<.8){dust(fx,407,579,t,u);}
        titles.poly("#081c26",null,1,0,0,1280,0,1280,36,0,36);titles.poly("#081c26",null,1,0,684,1280,684,1280,720,0,720);
        if(t<4.5){titles.text("BENEATH THE CANOPY","#e4d9ad",640,110,30);titles.text("an original Sketch Studio cel film","#aac2af",640,154,16);}
        if(t>116){titles.text("Some battles end when we finally see.","#e1d6b2",640,615,22);}
        SketchDocument.validate(doc);return doc;
    }
    static int shot(double t){for(int n=0;n<CUTS.length-1;n++)if(t<CUTS[n+1])return n;return SHOTS.length-1;}
    private static JungleCelInk layer(JSONArray layers,String id,String name)throws Exception {JSONObject l=new JSONObject().put("id",id).put("name",name).put("visible",true).put("locked",false).put("opacity",1).put("transform",SketchDocument.transform()).put("keys",new JSONArray()).put("objects",new JSONArray());layers.put(l);return new JungleCelInk(l);}
    private static double ease(double p){return curve(Math.max(0,Math.min(1,p)));}private static double curve(double p){return p*p*(3-2*p);}private static double wave(double t,double hz){return t*hz-Math.floor(t*hz);}private static double pulse(double t,double a,double b){return ease((t-a)/(b-a));}
    static double mouth(double t){for(int i=0;i<LINE_START.length;i++)if(t>=LINE_START[i]&&t<LINE_START[i]+LINE_SLOTS[i]-.15)return Math.max(.05,Math.sin((t-LINE_START[i])*17)*.7+.2);return 0;}
    static void impact(JungleCelInk d,double x,double y,double t,double strength)throws Exception {for(int i=0;i<11;i++){double a=i*Math.PI*2/11+.2*Math.sin(t*13),r=25+80*strength;d.line(i%3==0?"#fbe5ab":"#9ceadd",i%3==0?4:1.5,x+12*Math.cos(a),y+12*Math.sin(a),x+r*Math.cos(a),y+r*Math.sin(a));}d.ellipse("#ecf8d0",null,1,x,y,7+6*strength,5+7*strength);}
    private static void dust(JungleCelInk d,double x,double y,double t,double p)throws Exception {for(int i=0;i<8;i++){double a=i*.49,xx=x+Math.cos(a)*p*120,yy=y-25-i*5-15*Math.sin(p*Math.PI);d.ellipse("#5e9da583",null,1,xx,yy,20+p*15,8+p*10);}}
    static void wind(JungleCelInk d,double x,double y,double radius,double t)throws Exception {for(int i=0;i<5;i++){double a=t*3+i*1.2;double rx=radius*(.5+i*.1),ry=radius*.27;d.ink(null,i%2==0?"#8adddfb8":"#48b4d6c5",i%2==0?3.5:1.4).m(x-rx*Math.cos(a),y-ry*Math.sin(a)).c(x-rx,y-ry*1.8,x+rx,y+ry*1.8,x+rx*Math.cos(a),y+ry*Math.sin(a)).end();}}
    private static void speed(JungleCelInk d,double t,double x,double y,double strength)throws Exception {for(int i=0;i<17;i++){double yy=160+i*24,xx=70+((i*127+t*600)%900),length=45+strength*130;d.line(i%3==0?"#778ee3da":"#465faaa7",i%3==0?2.8:1.1,xx,yy,xx+length,yy-13);}d.ink(null,"#c3dff8b4",5).m(x-170,y-12).q(x-90,y-12,x-10,y-9).end();}
    private static void fire(JungleCelInk d,double x,double y,double t,double phase)throws Exception {double r=140+145*Math.sin(Math.min(1,phase)*Math.PI);d.ink("#72d29f51",null,1).m(x,y-18).q(x-r*.5,y-80,x-r,y-15).q(x-r*1.5,y-40,x-r*2.3,y+80).q(x-r,y+67,x,y+18).z().end();for(int i=0;i<8;i++){double a=wave(t+i*.2,2.4),xx=x-a*r*2,yy=y+10*Math.sin(i*4+t*10)+a*65;d.ink("#b7e6c567",null,1).m(xx,yy).q(xx-22,yy-17,xx-62,yy+2).q(xx-20,yy+14,xx,yy).z().end();}}
    private static void energy(JungleCelInk d,double x,double y,double r,double t)throws Exception {if(r<3)return;for(int i=0;i<7;i++){double a=t*4+i*.82,rr=r*(.6+i*.07);d.ink(null,i%2==0?"#a3d2f5bd":"#50a6d8dd",i%2==0?4:1.4).m(x+rr*Math.cos(a),y+rr*.7*Math.sin(a)).q(x-rr*.9,y-rr*1.3,x-rr*Math.cos(a),y-rr*.7*Math.sin(a)).end();}}
}
