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

/** Independent authored QA fixtures. No source photograph, generated imagery or story preset. */
@RunWith(RobolectricTestRunner.class) @Config(sdk=33,manifest=Config.NONE) @GraphicsMode(GraphicsMode.Mode.NATIVE)
public class SketchShapeQualityProofTest {
    private static final int FRAMES=72;
    private interface Geometry {JSONArray at(int frame)throws Exception;}
    private static JSONArray command(String type,double...values)throws Exception{JSONArray c=new JSONArray().put(type);for(double value:values)c.put(value);return c;}
    private static JSONArray path(JSONArray...commands)throws Exception{JSONArray p=new JSONArray();for(JSONArray c:commands)p.put(c);return p;}
    private static JSONObject document(String id)throws Exception{
        JSONObject d=SketchDocument.create(new JSONObject().put("sketchId",id).put("width",960).put("height",720).put("frameCount",FRAMES).put("fps",24).put("background","#FF141927"));
        return d;
    }
    private static JSONArray objects(JSONObject d)throws Exception{return d.getJSONArray("layers").getJSONObject(0).getJSONArray("objects");}
    private static JSONObject shape(String id,String color,boolean fill,double width,JSONArray commands)throws Exception{
        return SketchEditOps.completeObject(new JSONObject().put("id",id).put("kind","path").put("color",color).put("fill",fill).put("width",width).put("exposure",FRAMES).put("commands",commands));
    }
    private static JSONObject radial(double x,double y,double radius,String...colors)throws Exception{
        JSONArray c=new JSONArray();for(String color:colors)c.put(color);
        return new JSONObject().put("type","radial").put("cx",x).put("cy",y).put("radius",radius).put("colors",c);
    }
    private static JSONObject ellipse(String id,String color,double x,double y,double w,double h)throws Exception{
        return SketchEditOps.completeObject(new JSONObject().put("id",id).put("kind","ellipse").put("color",color).put("fill",true).put("exposure",FRAMES)
                .put("bounds",new JSONObject().put("x",x).put("y",y).put("width",w).put("height",h)));
    }
    private static JSONObject animated(String id,String color,boolean fill,double width,Geometry geometry)throws Exception{
        JSONObject o=shape(id,color,fill,width,geometry.at(0));JSONArray keys=new JSONArray();
        for(int frame:new int[]{0,18,36,54,71})keys.put(new JSONObject().put("frame",frame).put("commands",geometry.at(frame)).put("interpolation","smooth"));
        return o.put("shapeKeys",keys);
    }
    private static JSONObject blink()throws Exception{
        JSONObject d=SketchMaterialQualityProofTest.eye().put("id","editable_continuous_blink_study");
        JSONObject lids=SketchDocument.layer(d,"authored_blink_cels");JSONArray original=lids.getJSONArray("objects"),replacement=new JSONArray();
        java.util.Map<String,JSONObject> byId=new java.util.HashMap<>();
        for(int i=0;i<original.length();i++)byId.put(original.getJSONObject(i).getString("id"),original.getJSONObject(i));
        for(int i=0;i<original.length();i++){
            JSONObject open=original.getJSONObject(i);String id=open.getString("id");
            if(open.getInt("frame")!=0)continue;
            String closedId=id.endsWith("_0")?id.substring(0,id.length()-2)+"_4":id;
            if(id.startsWith("upper_lash_0_"))closedId=id.replace("upper_lash_0_","upper_lash_4_");
            if(id.startsWith("lower_lash_0_"))closedId=id.replace("lower_lash_0_","lower_lash_4_");
            JSONObject closed=byId.get(closedId);assertNotNull(closedId,closed);
            JSONObject o=SketchDocument.copy(open).put("id","morph_"+id).put("exposure",FRAMES);JSONArray keys=new JSONArray();
            for(int frame:new int[]{0,26,34,44,71})keys.put(new JSONObject().put("frame",frame)
                    .put("commands",new JSONArray((frame==34?closed:open).getJSONArray("commands").toString()))
                    .put("interpolation",frame==26?"ease_in":frame==34?"ease_out":"hold"));
            replacement.put(o.put("shapeKeys",keys));
        }
        lids.put("objects",replacement);return d;
    }
    private static double wind(int frame,double phase){return Math.sin(frame*2*Math.PI/71+phase);}
    private static JSONObject hair()throws Exception{
        JSONObject d=document("editable_hair_curve_study");JSONArray o=objects(d);
        o.put(ellipse("soft_backlight","#FFFFFFFF",.13,.04,.74,.91).put("gradient",radial(.5,.40,.63,"#FF645073","#FF24253D","#FF141927")));
        o.put(shape("collar","#FF252C46",true,.001,path(command("M",.22,.98),command("C",.24,.79,.37,.74,.43,.72),command("L",.57,.72),command("C",.63,.74,.76,.79,.78,.98),command("Z"))));
        o.put(ellipse("hair_mass","#FFFFFFFF",.23,.07,.54,.75).put("gradient",radial(.46,.24,.56,"#FF65556E","#FF302B43","#FF0D1426")));
        o.put(ellipse("face","#FFFFFFFF",.30,.20,.40,.61).put("gradient",radial(.48,.38,.43,"#FFF1CFBE","#FFBF8B95","#FF60485F")));
        o.put(shape("neck","#FFAB7B87",true,.001,path(command("M",.43,.69),command("L",.43,.84),command("Q",.50,.91,.57,.84),command("L",.57,.69),command("Z"))));
        o.put(shape("eye_left","#FF2C263D",false,.004,path(command("M",.36,.48),command("Q",.41,.45,.46,.48))));
        o.put(shape("eye_right","#FF2C263D",false,.004,path(command("M",.54,.48),command("Q",.59,.45,.64,.48))));
        o.put(shape("nose","#996D495C",false,.002,path(command("M",.50,.49),command("Q",.48,.57,.51,.58))));
        o.put(shape("mouth","#FF845264",false,.003,path(command("M",.45,.66),command("Q",.50,.68,.55,.66))));
        for(int lock=0;lock<18;lock++){
            final int n=lock,part=lock%9;final boolean right=lock>=9;
            final double root=.48-part*.023,rootY=.14+.055*Math.pow((.49-root)/.20,2),phase=part*.32,tip=.22+part*.015;
            Geometry geometry=frame->{double flow=.035*wind(frame,phase);return mirror(path(command("M",root,rootY),
                    command("C",root-.035,rootY+.08,tip-.04+flow*.4,.51,tip+flow,.84-.11*Math.sin(part*.7)),
                    command("C",tip+.025+flow,.68,root+.015,.34,root+.035,rootY),command("Z")),right);};
            o.put(animated("hair_lock_"+n,"#FFFFFFFF",true,.001,geometry).put("gradient",radial(right?1-root:root,.31,.52,
                    "#FF786477","#FF302B43","#FF0D1426")));
            for(int strand=0;strand<6;strand++){
                final double offset=.004+strand*.0045;
                o.put(animated("strand_"+n+"_"+strand,strand%3==0?"#997D6A8B":"#88534B67",false,.0007,frame->{double flow=.035*wind(frame,phase);return mirror(path(command("M",root+offset,rootY+.015),
                        command("C",root-.02+offset,.33,tip-.005+flow*.45+offset,.53,tip+flow+offset,.76-.11*Math.sin(part*.7))),right);}));
            }
        }
        return d;
    }
    private static JSONArray mirror(JSONArray commands,boolean right)throws Exception{
        if(right)for(int i=0;i<commands.length();i++){JSONArray c=commands.getJSONArray(i);for(int k=1;k<c.length();k+=2)c.put(k,1-c.getDouble(k));}return commands;
    }
    private static JSONArray cloth(int frame)throws Exception{
        double s=wind(frame,0);return path(command("M",.20,.22),command("C",.40,.10+.03*s,.65,.36+.03*s,.84,.22+.08*s),
                command("L",.84,.74+.08*s),command("C",.64,.88+.05*s,.43,.60+.03*s,.20,.74),command("Z"));
    }
    private static JSONObject fabric()throws Exception{
        JSONObject d=document("editable_fabric_curve_study");JSONArray o=objects(d);
        o.put(ellipse("light","#FFFFFFFF",.04,.03,.92,.94).put("gradient",radial(.49,.38,.69,"#FF303851","#FF172031","#FF141927")));
        o.put(shape("pole","#FFCCAE78",false,.009,path(command("M",.18,.10),command("L",.18,.94))));
        o.put(animated("fabric_surface","#FFFFFFFF",true,.001,SketchShapeQualityProofTest::cloth)
                .put("gradient",radial(.42,.38,.58,"#FFD07683","#FF873D66","#FF372641","#FF1E1C36")));
        for(int stripe=0;stripe<27;stripe++){
            final double v=(stripe+1)/28d;
            o.put(animated("weave_"+stripe,stripe%4==0?"#99E5A4AB":"#55742A53",false,stripe%4==0?.002:.0007,
                    frame->{double s=wind(frame,0);return path(command("M",.201,.22+.52*v),command("C",.40,.10+.50*v+.03*s,.65,.36+.48*v+.03*s,.84,.22+.52*v+.08*s));}));
        }
        o.put(animated("gold_hem","#FFDDBA7A",false,.004,SketchShapeQualityProofTest::cloth));
        for(int petal=0;petal<6;petal++){
            final double angle=petal*Math.PI/3;
            Geometry mark=frame->{double y=.48+.025*wind(frame,0);double x=.51,dx=.08*Math.cos(angle),dy=.085*Math.sin(angle);
                return path(command("M",x,y),command("Q",x+dx-dy*.3,y+dy+dx*.3,x+dx*1.2,y+dy*1.2),command("Q",x+dx+dy*.3,y+dy-dx*.3,x,y),command("Z"));};
            o.put(animated("embroidered_flower_"+petal,"#DDF1D79D",false,.0017,mark));
        }
        return d;
    }
    @Test public void independentCurvedSubjectsRenderAsEditableNativeShapeAnimation()throws Exception{
        File root=new File("build/shape-quality-proof");assertTrue(root.isDirectory()||root.mkdirs());
        boolean full="1".equals(System.getenv("VIDEOSTUDIO_SHAPE_STUDY_PROOF"));JSONArray subjects=new JSONArray();
        for(JSONObject d:new JSONObject[]{blink(),hair(),fabric()}){
            SketchDocument.validate(d);String before=SketchDocument.canonical(d);File dir=new File(root,d.getString("id"));assertTrue(dir.isDirectory()||dir.mkdirs());
            Files.write(new File(dir,"editable-source.json").toPath(),d.toString().getBytes(StandardCharsets.UTF_8));int count=0;
            try(SketchFrameRenderer renderer=new SketchFrameRenderer(RuntimeEnvironment.getApplication(),d)){
                for(int frame=0;frame<FRAMES;frame++){
                    if(!full&&frame!=0&&frame!=15&&frame!=30&&frame!=34&&frame!=45&&frame!=60&&frame!=71)continue;
                    Bitmap b=renderer.render(frame);try(FileOutputStream output=new FileOutputStream(new File(dir,String.format(java.util.Locale.ROOT,"frame-%04d.png",frame)))){
                        assertTrue(b.compress(Bitmap.CompressFormat.PNG,100,output));count++;
                    }finally{b.recycle();}
                }
            }
            assertEquals("Shape rendering must retain the editable source",before,SketchDocument.canonical(d));
            subjects.put(new JSONObject().put("subject",d.getString("id")).put("renderedFrames",count).put("fps",24).put("editable",true));
        }
        Files.write(new File(root,"execution.json").toPath(),new JSONObject().put("renderer","Android Canvas through local Robolectric native graphics")
                .put("privatePortraitUsed",false).put("phoneExecutionVerified",false).put("artisticReferenceAcceptance",false)
                .put("motion","independently authored topology-preserving path shape keys; no inferred anatomy or physics simulation").put("subjects",subjects).toString(2).getBytes(StandardCharsets.UTF_8));
    }
    @Test public void closedBlinkKeepsItsInkAndCastShadowOnTheAuthoredLid()throws Exception {
        JSONObject d=blink();
        for(String id:new String[]{"morph_upper_ink_0","morph_lower_waterline_0","morph_lid_cast_shadow_0"})
            assertEquals(id,.52,SketchShapeKeys.commandsAt(SketchDocument.object(d,id),34).getJSONArray(1).getDouble(2),.000001);
    }
}
