package com.rezoxnemesis.videostudio;

import android.graphics.Bitmap;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.file.Files;
import java.nio.charset.StandardCharsets;
import static org.junit.Assert.*;

/** Independently authored QA subjects, not new canned output in the product generator.
 * All images come from SketchFrameRenderer. The supplied private portrait is not used.
 */
@RunWith(RobolectricTestRunner.class) @Config(sdk=33,manifest=Config.NONE) @GraphicsMode(GraphicsMode.Mode.NATIVE)
public class SketchMaterialQualityProofTest {
    private static final int FRAMES=72;
    private static JSONObject document(String id,int w,int h)throws Exception {
        JSONObject d=SketchDocument.create(new JSONObject().put("sketchId",id).put("name",id)
                .put("width",w).put("height",h).put("fps",24).put("frameCount",FRAMES).put("background","#FF151421"));
        d.put("layers",new JSONArray());return d;
    }
    private static JSONObject layer(JSONObject d,String id)throws Exception {
        JSONObject l=new JSONObject().put("id",id).put("name",id).put("visible",true).put("locked",false)
                .put("opacity",1).put("transform",SketchDocument.transform()).put("keys",new JSONArray()).put("objects",new JSONArray());
        d.getJSONArray("layers").put(l);return l;
    }
    private static void add(JSONObject l,JSONObject o)throws Exception {l.getJSONArray("objects").put(o);}
    private static JSONObject object(String id,String kind,String color,int start,int span,boolean fill)throws Exception {
        return SketchEditOps.completeObject(new JSONObject().put("id",id).put("kind",kind).put("color",color)
                .put("frame",start).put("exposure",span).put("fill",fill).put("width",.001));
    }
    private static JSONObject bounds(double x,double y,double w,double h)throws Exception {
        return new JSONObject().put("x",x).put("y",y).put("width",w).put("height",h);
    }
    private static JSONObject ellipse(String id,String color,double x,double y,double rx,double ry)throws Exception {
        return object(id,"ellipse",color,0,FRAMES,true).put("bounds",bounds(x-rx,y-ry,rx*2,ry*2));
    }
    private static JSONObject rect(String id,String color,double x,double y,double w,double h)throws Exception {
        return object(id,"rect",color,0,FRAMES,true).put("bounds",bounds(x,y,w,h));
    }
    private static JSONArray command(String kind,double...values)throws Exception {
        JSONArray a=new JSONArray().put(kind);for(double v:values)a.put(v);return a;
    }
    private static JSONObject path(String id,String color,int start,int span,boolean fill,double width,JSONArray...commands)throws Exception {
        JSONArray p=new JSONArray();for(JSONArray c:commands)p.put(c);
        return object(id,"path",color,start,span,fill).put("commands",p).put("width",width);
    }
    private static JSONObject radial(double cx,double cy,double radius,String...colors)throws Exception {
        JSONArray c=new JSONArray();for(String color:colors)c.put(color);
        return new JSONObject().put("type","radial").put("cx",cx).put("cy",cy).put("radius",radius).put("colors",c);
    }
    private static JSONObject skin()throws Exception {
        return radial(.43,.30,.85,"#FFF6D6CA","#FFE7BCC0","#FFBA8DA8","#FF735F8D");
    }
    private static double cubic(double t,double a,double b,double c,double d){double s=1-t;return s*s*s*a+3*s*s*t*b+3*s*t*t*c+t*t*t*d;}
    private static JSONObject key(int frame,double x,double rotation,String mode)throws Exception {
        JSONObject k=new JSONObject().put("frame",frame).put("transform",SketchDocument.transform().put("x",x).put("rotation",rotation)).put("interpolation",mode);
        if("bezier".equals(mode))k.put("curve",new JSONArray().put(.22).put(0).put(.18).put(1));return k;
    }
    private static JSONObject eye()throws Exception {
        JSONObject d=document("editable_iris_quality_study",1280,720);
        JSONObject skinLayer=layer(d,"skin_and_socket");
        add(skinLayer,rect("skin","#FFFFFFFF",0,0,1,1).put("gradient",skin()));
        add(skinLayer,path("socket_contour","#FF7D4F79",0,FRAMES,true,.001,
                command("M",.08,.525),command("C",.28,.18,.73,.18,.925,.52),command("C",.70,.83,.29,.83,.08,.525),command("Z")));
        JSONObject white=layer(d,"sclera");
        add(white,path("sclera_shape","#FFFFFFFF",0,FRAMES,true,.001,
                command("M",.10,.52),command("C",.30,.20,.70,.20,.90,.52),command("C",.70,.80,.30,.80,.10,.52),command("Z"))
                .put("gradient",radial(.48,.46,.5,"#FFFFFBF8","#FFE9DCE7","#FF9D85AA")));
        JSONObject iris=layer(d,"iris_and_pupil");
        iris.put("keys",new JSONArray().put(key(0,-.025,0,"bezier")).put(key(20,.018,0,"smooth"))
                .put(key(43,.018,0,"bezier")).put(key(65,-.007,0,"hold")));
        double cx=.52,cy=.49,rx=.125,ry=rx*1280/720;
        add(iris,ellipse("limbal_ring","#FF211D3C",cx,cy,rx*1.035,ry*1.035));
        add(iris,ellipse("iris_base","#FFFFFFFF",cx,cy,rx,ry).put("gradient",radial(cx,cy,ry,
                "#FFD8B9F7","#FFB18BE2","#FF6B56BE","#FF39366E","#FF12192E")));
        for(int i=0;i<220;i++){
            double a=i*Math.PI*2/220,r0=.35+.045*Math.sin(i*2.9),r1=.89+.07*Math.sin(i*5.7);
            double bend=.036*Math.sin(i*3.1),mid=(r0+r1)*.55;
            String color=i%7==0?"BBD7B981":i%3==0?"AAD6BFF9":"995B417F";
            add(iris,path("stromal_fiber_"+i,"#"+color,0,FRAMES,false,.0005+(i%4)*.00012,
                    command("M",cx+rx*r0*Math.cos(a),cy+ry*r0*Math.sin(a)),
                    command("Q",cx+rx*mid*Math.cos(a+bend),cy+ry*mid*Math.sin(a+bend),cx+rx*r1*Math.cos(a),cy+ry*r1*Math.sin(a))));
        }
        for(int ring=0;ring<4;ring++){
            JSONArray points=new JSONArray();
            for(int j=0;j<=120;j++){
                double a=j*2*Math.PI/120,r=.37+ring*.017+.018*Math.sin(j*1.7+ring);
                points.put(command(j==0?"M":"L",cx+rx*r*Math.cos(a),cy+ry*r*Math.sin(a)));
            }
            add(iris,object("collarette_"+ring,"path",ring%2==0?"#AAE6BBA8":"#99633885",0,FRAMES,false).put("commands",points).put("width",.0006));
        }
        add(iris,ellipse("pupil","#FFFFFFFF",cx,cy,rx*.315,ry*.315).put("gradient",radial(cx,cy,ry*.34,"#FF050816","#FF191329","#FF3D2754")));
        add(iris,ellipse("key_light_reflection","#F8FFF8F1",cx-.027,cy-.054,.017,.028));
        add(iris,ellipse("small_reflection","#F3E6E4FF",cx+.042,cy+.042,.007,.012));
        add(iris,path("window_reflection","#A6FFFFFF",0,FRAMES,false,.003,
                command("M",cx-.045,cy-.075),command("Q",cx-.008,cy-.106,cx+.033,cy-.08)));
        JSONObject lids=layer(d,"authored_blink_cels");
        int[] starts={0,28,30,32,34,36,38,40},ends={28,30,32,34,36,38,40,72};
        double[] opens={1,.8,.45,.1,0,.2,.6,1};
        for(int cel=0;cel<starts.length;cel++){
            int start=starts[cel],span=ends[cel]-start;double open=opens[cel],up=.52-.32*open,down=.52+.28*open;
            add(lids,path("upper_lid_cel_"+cel,"#FFFFFFFF",start,span,true,.001,
                    command("M",0,0),command("L",1,0),command("L",1,.52),command("L",.90,.52),
                    command("C",.70,up,.30,up,.10,.52),command("L",0,.52),command("Z")).put("gradient",skin()));
            add(lids,path("lower_lid_cel_"+cel,"#FFFFFFFF",start,span,true,.001,
                    command("M",0,.52),command("L",.10,.52),command("C",.30,down,.70,down,.90,.52),
                    command("L",1,.52),command("L",1,1),command("L",0,1),command("Z")).put("gradient",skin()));
            add(lids,path("lid_cast_shadow_"+cel,"#3C21142A",start,span,true,.001,
                    command("M",.10,.52),command("C",.30,up,.70,up,.90,.52),
                    command("C",.70,up+.035,.30,up+.035,.10,.52),command("Z")));
            add(lids,path("upper_ink_"+cel,"#FF2D1B36",start,span,false,.008,
                    command("M",.10,.52),command("C",.30,up,.70,up,.90,.52)));
            add(lids,path("lower_waterline_"+cel,"#FF98617C",start,span,false,.002,
                    command("M",.10,.52),command("C",.30,down,.70,down,.90,.52)));
            for(int lash=0;lash<28;lash++){
                double t=.035+lash*.93/27,x=cubic(t,.10,.30,.70,.90),y=cubic(t,.52,up,up,.52);
                double dx=(t-.5)*.041,len=.027+.027*Math.abs(t-.5)*2;
                add(lids,path("upper_lash_"+cel+"_"+lash,"#FF2A1B34",start,span,true,.001,
                        command("M",x-.003,y+.001),command("Q",x+dx*.4,y-len*.55,x+dx,y-len),
                        command("Q",x+dx*.15+.004,y-len*.25,x+.003,y+.003),command("Z")));
            }
            for(int lash=0;lash<17;lash++){
                double t=.08+lash*.84/16,x=cubic(t,.10,.30,.70,.90),y=cubic(t,.52,down,down,.52);
                add(lids,path("lower_lash_"+cel+"_"+lash,"#995F3B59",start,span,false,.0008,
                        command("M",x,y),command("Q",x+(t-.5)*.01,y+.012,x+(t-.5)*.022,y+.025)));
            }
        }
        JSONObject detail=layer(d,"brow_and_surface_detail");
        add(detail,path("eyebrow_silhouette","#FF4F344B",0,FRAMES,true,.001,
                command("M",.14,.24),command("C",.34,.13,.61,.11,.84,.23),command("C",.65,.18,.43,.18,.16,.28),command("Z")));
        for(int hair=0;hair<80;hair++){
            double t=hair/79d,x=.16+t*.64,y=.245-.07*Math.sin(t*Math.PI),length=.015+.012*Math.sin(t*Math.PI);
            add(detail,path("brow_hair_"+hair,hair%3==0?"#BBAC7D8D":"#AA291E33",0,FRAMES,false,.0008,
                    command("M",x,y),command("Q",x+.009,y-length,x+.019,y-length*.8)));
        }
        for(int line=0;line<7;line++){
            double y=.76+line*.009;
            add(detail,path("lower_skin_glaze_"+line,"#18815781",0,FRAMES,false,.002,
                    command("M",.24,y),command("Q",.52,y+.05,.79,y-.02)));
        }
        add(detail,ellipse("tear_highlight","#BFFFE2EA",.112,.525,.012,.008));
        return d;
    }
    private static JSONObject watch()throws Exception {
        JSONObject d=document("editable_metal_material_study",720,720),base=layer(d,"metal_and_glass");
        add(base,rect("backdrop","#FF141B29",0,0,1,1));
        add(base,rect("strap","#FF1B2837",.37,.02,.26,.96));
        add(base,ellipse("metal_case","#FFFFFFFF",.5,.5,.36,.36).put("gradient",radial(.43,.38,.5,
                "#FFF9E6BF","#FFB69254","#FFFAEAC6","#FF69512D","#FFDFC38D","#FF261D18")));
        add(base,ellipse("outer_bevel","#FFFFFFFF",.5,.5,.325,.325).put("gradient",radial(.55,.54,.36,
                "#FF2A2533","#FF70626A","#FFF8E3B8","#FF55422D")));
        add(base,ellipse("dial","#FFFFFFFF",.5,.5,.297,.297).put("gradient",radial(.40,.36,.49,
                "#FF517080","#FF1C3443","#FF0A1624")));
        for(int i=0;i<60;i++){
            double a=i*Math.PI/30,r=i%5==0?.247:.267;
            add(base,path("minute_mark_"+i,i%5==0?"#FFFFE4AD":"#FF9DA9AD",0,FRAMES,false,i%5==0?.006:.0015,
                    command("M",.5+r*Math.sin(a),.5-r*Math.cos(a)),command("L",.5+.28*Math.sin(a),.5-.28*Math.cos(a))));
        }
        JSONObject hands=layer(d,"independent_watch_hands");hands.put("keys",new JSONArray().put(key(0,0,-12,"ease_in_out")).put(key(71,0,22,"hold")));
        add(hands,path("hour_hand","#FFE4C187",0,FRAMES,true,.001,command("M",.49,.56),command("L",.49,.34),command("L",.50,.30),command("L",.515,.34),command("L",.515,.56),command("Z")));
        add(hands,path("minute_hand","#FFF7EACF",0,FRAMES,true,.001,command("M",.46,.51),command("L",.70,.40),command("L",.73,.40),command("L",.71,.42),command("L",.47,.53),command("Z")));
        add(hands,ellipse("pin","#FFFFFFFF",.5,.5,.017,.017).put("gradient",radial(.495,.495,.028,"#FFFFFFFF","#FFD6AA68","#FF51391D")));
        return d;
    }
    private static JSONObject typography()throws Exception {
        JSONObject d=document("editable_type_timing_study",1280,720),l=layer(d,"timed_lettering");
        add(l,rect("background","#FF11182A",0,0,1,1));
        JSONObject t=object("headline","text","#FFE6DDFB",0,FRAMES,true).put("text","MOTION WITH INTENT")
                .put("fontFamily","sans-serif-condensed").put("fontStyle","bold").put("fontSize",.09).put("textAlign","center")
                .put("bounds",bounds(.12,.4,.76,.2));
        t.put("keys",new JSONArray().put(key(0,-.8,0,"bezier")).put(key(24,0,0,"hold"))
                .put(key(50,0,0,"bezier")).put(key(71,.8,0,"hold")));
        add(l,t);return d;
    }
    @Test public void independentEditableSubjectsRenderThroughTheActualNativeEngine()throws Exception {
        File root=new File("build/shading-quality-proof");assertTrue(root.isDirectory()||root.mkdirs());
        boolean full="1".equals(System.getenv("VIDEOSTUDIO_QUALITY_STUDY_PROOF"));
        JSONArray metadata=new JSONArray();
        for(JSONObject doc:new JSONObject[]{eye(),watch(),typography()}){
            SketchDocument.validate(doc);String original=SketchDocument.canonical(doc);
            File dir=new File(root,doc.getString("id"));assertTrue(dir.isDirectory()||dir.mkdirs());
            Files.write(new File(dir,"editable-source.json").toPath(),doc.toString().getBytes(StandardCharsets.UTF_8));
            int count=0;
            try(SketchFrameRenderer renderer=new SketchFrameRenderer(RuntimeEnvironment.getApplication(),doc)){
                for(int frame=0;frame<FRAMES;frame++){
                    if(!full&&frame!=0&&frame!=18&&frame!=34&&frame!=44&&frame!=60&&frame!=71)continue;
                    Bitmap image=renderer.render(frame);try(FileOutputStream stream=new FileOutputStream(new File(dir,String.format(java.util.Locale.ROOT,"frame-%04d.png",frame)))){
                        assertTrue(image.compress(Bitmap.CompressFormat.PNG,100,stream));count++;
                    }finally{image.recycle();}
                }
            }
            assertEquals("Rendering must not flatten or replace the editable source",original,SketchDocument.canonical(doc));
            metadata.put(new JSONObject().put("subject",doc.getString("id")).put("renderedFrames",count).put("fps",24).put("editable",true));
        }
        Files.write(new File(root,"execution.json").toPath(),new JSONObject().put("execution","Local Android Canvas through Robolectric native graphics")
                .put("physicalPhoneVerified",false).put("referenceArtisticAcceptance",false).put("privatePortraitUsed",false)
                .put("subjects",metadata).toString(2).getBytes(StandardCharsets.UTF_8));
    }
}
