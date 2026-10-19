package com.rezoxnemesis.videostudio;

import android.graphics.*;
import org.json.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.*;
import java.io.*;
import java.nio.*;
import java.nio.file.Files;
import java.security.*;
import java.util.*;
import static org.junit.Assert.*;

/** Actual native garment paths and pixels, independent physical-joint probes. */
@RunWith(RobolectricTestRunner.class) @Config(sdk=33,manifest=Config.NONE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class SketchHumanClothingGeometryTest {
    static final String[] VIEWS={"front","left","right","back","high","low"},BUILDS={"slender","broad"};
    static final int BACKGROUND=0xFFF4E4D1;
    // Frozen pre-tailored Figure source/native pixels; populated by the baseline capture.
    static final String[] LEGACY={
        "8e5137292a3186b82944432444b771dfacb7f0428d5e229cbafb6f71e481ba92",
        "ac593b4a40ec9bf073e1e51bb77f7459b4a364b39bd120ceff4bbd405d574c38",
        "40ac83122d6b61a66ee3c790e796571bf81f52d9a1255c9bdd8bd87ea594fb11",
        "b270ef7d18ed4403e0cf745beefa16858ba02e01c6c7431fd59c1598bfcc442c",
        "e29000b7ebc83c67b278332cbf22051d064906606588864c931709cd7aa16a21",
        "fd9d55d5758d79a105d228be7e54ac465f600f8ab57a4fa0dae2501cb829c0ff",
        "7013b994a37e7d3f95535b73e5a76eb92b5abed065bbbf522d7f4c08cf05ec08",
        "a66ded0650f0e37fe92cf63591d2c3fb4ee2c7ce68907aea37f0137449cc0fbf",
        "3b8170edb835b9e4f00f4cd26e5447f8d2e17720f257c395a3049882e6a79fd5",
        "e606194ff8a731a3028ce5802dcee486daae3e493680cf320f755213bbb88261",
        "238f10e7c07bcef07ee6e6e84b3a2d90782e2eff4bdf2f7e03831c0514ba0db0",
        "739b9c563f1f39233378032c1d56a3019f19563b4961b99b364de565cef749d9",
        "75b7aab7207f3f5d76f8776bf35026ab76d17756f9befb74e8840b827c7415da",
        "7e29a6ddbba15c1fe09e52492a28604ea9f0895609ec546f072fe416bef7f3a1",
        "f2695e5877df5b30437f69ceabf9f70c8082d93b58cdda1fb95356739658a833",
        "63f1139d0e54eb9f1ed976adc7dff62208c768edd256c5584801848288b1ae5d",
        "18320989649b0b7746ab0b86a8f0d3067deeb4dd780e4cbbf62f4413433d5c6c",
        "1c5ea73d647b8b032f2287f51b3984cb8fb4933a157c22f6d1bab0482e60b113",
        "7d206e15a5fc3c8e45cd4330ed4214bcf5ce3a88ff85ce4d52d08eb754f04121",
        "05a0ff8ecfbc6d1be2c72bea77ea2d721585a38fc776d01ce4fabbdc7a4e92d5",
        "0972d6ed616963ccadc31512a9b4ee866e1db47126dacc026f0ac8dfe19764fa",
        "0e32c7bedba4ee4325c17d05044a5da1fddbdefed63fbe192c9cb5b095a97bc9",
        "3616e3d2afe6b1a2a1af6e5d66245b8c2ed5ec939dd8343b5cf0a1b43f3715d3",
        "66e56c55df3a692a353c751375c0f923d8fdeee43bb92e46d25b543bc479d2b2",
        "df596b6b4aa3ebaf12e93688c4cdc0b848a7c5da50e8f2fb4703797cc8fd2832",
        "5c93a223d332781eee79dec8e99b5a5d37666ef5f102794d13b5a3a44f12f1e3",
        "7ffcd3324c9a771c4c4009b671b66b76ab2d5b283c398e67f1bcc65d28acea2d",
        "409e64c40c44abe1afca157fce27113cb392200a9f67d7a5317abc65fab290f2",
        "8c8410d57b6c5554f85417269b4065cccd54a5eebfb511f2654e1455031cf599",
        "62ed690fcf7c3fbaeff6a7e78897e74c6e34c586e0ceefd53fe3884552d59e1c",
        "e745de113b62a73fd9f971ef441312b1d4e402277faa0ad1bc4c44d029179b2b",
        "f17e1310cd1ea4b436db18bcd836fa166c4fac43ad5c24d4d2018451b3f154f0",
        "cdbca472f62e6778cb827fe57fd7094a2a52dc81fdace97350a2d96e4d05c8a4",
        "b16573f0aa0d939c3a7272f7d8087e14c27e7fc03b6d2715c025f3dea5eb1349",
        "4e8748952e578c1a006852921931e03508661da1bce8f7ea8edcd59a64444159",
        "0e94e78bbb4d43e20867e341ac19b88cab648b64feb4fb39e9163dbad00d432e"
    };
    @Test public void captureOriginalLegacyPixels()throws Exception {
        String root=System.getProperty("videostudio.tailored.baseline");if(root==null)return;assertFalse("Preserve original baseline generations",new File(root,"baseline.json").exists());JSONArray hashes=new JSONArray();
        for(String build:BUILDS)for(String view:VIEWS)for(int mode=0;mode<3;mode++){
            JSONObject s=settings(build,view,null).put("faceStyle",mode==0?"graphic":mode==1?"angular":"soft").put("shading",mode==1?"soft":"cel"),doc=draw(s,pose(mode,view));Bitmap b=render(doc);try{String label=build+"-"+view+"-"+mode;save(b,new File(root,label+".png"));hashes.put(new JSONObject().put("label",label).put("pixelHash",hash(b)).put("settings",s).put("pose",pose(mode,view)));}finally{b.recycle();}
        }try(Writer w=new FileWriter(new File(root,"baseline.json"))){w.write(hashes.toString(2));}
    }
    @Test public void optionalTailoredSettingsPreserveOmissionAndRejectMalformedStyles()throws Exception {
        JSONObject legacy=SketchHumanFigure.settings(new JSONObject());assertFalse(legacy.has("clothingStyle"));
        JSONObject tailored=null;try{tailored=SketchHumanFigure.settings(new JSONObject().put("clothingStyle","tailored"));}catch(IllegalArgumentException e){fail("The explicit tailored rendering option must be accepted: "+e.getMessage());}
        assertEquals("tailored",tailored.getString("clothingStyle"));assertEquals("legacy",SketchHumanFigure.settings(new JSONObject().put("clothingStyle","legacy")).getString("clothingStyle"));
        for(Object bad:new Object[]{JSONObject.NULL,3,true,"unknown"})assertThrows(IllegalArgumentException.class,()->SketchHumanFigure.settings(new JSONObject().put("clothingStyle",bad)));
    }
    @Test public void tailoredBendChangesActualFilledClothRatherThanOnlyJointOutlines()throws Exception {
        JSONObject s=settings("slender","front","tailored"),p=new JSONObject().put("nearHandX",90).put("nearHandY",-260).put("nearHandZ",24);
        JSONObject doc;try{doc=draw(s,p);}catch(IllegalArgumentException e){fail("New garment construction is missing: "+e.getMessage());return;}
        Path cloth=parts(doc,"actor_near_sleeve_",true),old=parts(draw(settings("slender","front",null),p),"actor_near_",true);assertFalse("Continuous sleeve surface must exist",cloth.isEmpty());
        SketchHumanFigure.Skeleton skeleton=SketchHumanFigure.skeleton(s,p);double[] elbow=SketchHumanFigure.project(s,skeleton.nearElbow);Path window=new Path();window.addRect((float)(640+elbow[0]-25),(float)(633.6+elbow[1]-25),(float)(640+elbow[0]+25),(float)(633.6+elbow[1]+25),Path.Direction.CW);Path difference=new Path();assertTrue(difference.op(cloth,old,Path.Op.XOR));assertTrue(difference.op(window,Path.Op.INTERSECT));
        assertTrue("Bent garment must deliberately change filled contour at the physical elbow",area(difference)>8);
    }
    @Test public void legacyAndOmittedStylesMatchAllThirtySixPreEditNativePixelBaselines()throws Exception {
        int index=0;for(String build:BUILDS)for(String view:VIEWS)for(int mode=0;mode<3;mode++){
            JSONObject s=settings(build,view,null).put("faceStyle",mode==0?"graphic":mode==1?"angular":"soft").put("shading",mode==1?"soft":"cel");
            for(boolean explicit:new boolean[]{false,true}){
                JSONObject input=SketchDocument.copy(s);if(explicit)input.put("clothingStyle","legacy");Bitmap b=render(draw(input,pose(mode,view)));
                try{
                    assertEquals("Pre-edit pixels "+build+" "+view+" "+mode+" explicit="+explicit,LEGACY[index],hash(b));
                    String originalRoot=System.getProperty("videostudio.tailored.originalPngs");if(originalRoot!=null){Bitmap original=BitmapFactory.decodeFile(new File(originalRoot,build+"-"+view+"-"+mode+".png").getAbsolutePath());assertNotNull(original);try{assertTrue("Actual ORIGINAL PNG must equal new product legacy pixels",b.sameAs(original));}finally{original.recycle();}}
                }finally{b.recycle();}
            }index++;
        }assertEquals(36,index);
    }
    @Test public void tailoredLeavesEveryPhysicalJointGripHandAndShoeContourUnchanged()throws Exception {
        for(String build:BUILDS)for(String view:VIEWS)for(int mode=0;mode<3;mode++){
            JSONObject s=settings(build,view,"tailored"),p=pose(mode,view),old=SketchDocument.copy(s);old.remove("clothingStyle");
            SketchHumanFigure.Skeleton a=SketchHumanFigure.skeleton(s,p),b=SketchHumanFigure.skeleton(old,p);
            for(java.lang.reflect.Field f:SketchHumanFigure.Skeleton.class.getDeclaredFields())if(f.getType()==double[].class)assertArrayEquals(f.getName(),(double[])f.get(a),(double[])f.get(b),0);
            for(boolean near:new boolean[]{true,false})assertArrayEquals(SketchHumanFigure.grip(s,p,near),SketchHumanFigure.grip(old,p,near),0);
            JSONObject d=draw(s,p),legacy=draw(old,p);Map<String,JSONObject> original=byId(legacy);
            for(JSONObject o:byId(d).values())if(o.getString("id").matches("actor_((near|far)_(hand|finger.*|thumb|palm_crease|wrist|cuff)|(left|right)_shoe.*)_\\d+"))assertEquals("Unchanged contact ink "+o.getString("id"),SketchDocument.canonical(original.get(o.getString("id"))),SketchDocument.canonical(o));
        }
    }
    @Test public void compressionFoldsLieInsideTheirOwnSurfaceOnThePhysicalInnerBend()throws Exception {
        for(String build:BUILDS)for(String view:VIEWS){
            JSONObject s=settings(build,view,"tailored"),p=new JSONObject().put("nearHandX",90).put("nearHandY",-260).put("nearHandZ",24),d=draw(s,p);
            SketchHumanFigure.Skeleton skeleton=SketchHumanFigure.skeleton(s,p);double[] joint=SketchHumanFigure.project(s,skeleton.nearElbow),u=unit(sub(skeleton.nearElbow,skeleton.nearShoulder)),v=unit(sub(skeleton.nearHand,skeleton.nearElbow)),inside=SketchHumanFigure.project(s,sub(v,u));
            Path folds=parts(d,"actor_near_sleeve_compression_",true),surface=parts(d,"actor_near_sleeve_surface_",true),outside=new Path();assertFalse(folds.isEmpty());assertTrue(outside.op(folds,surface,Path.Op.DIFFERENCE));assertTrue("Inset cloth folds must not leak through their own silhouette: "+view,area(outside)<.35);
            float[] samples=folds.approximate(.03f);double signed=0;for(int i=0;i<samples.length;i+=3)signed+=(samples[i+1]-640-joint[0])*inside[0]+(samples[i+2]-633.6-joint[1])*inside[1];
            assertTrue("Physical compression, not a fixed screen-side crease: "+view,signed>0);
        }
    }
    @Test public void nearStraightBendTransportHasNoOutlineOrFoldFlip()throws Exception {
        for(String view:VIEWS){JSONObject s=settings("slender",view,"tailored"),prior=null;
            for(int step=0;step<16;step++){
                JSONObject p=new JSONObject().put("nearHandX",40).put("nearHandY",-300+145.4+step*.01).put("nearHandZ",0),current=draw(s,p);JSONObject outline=byId(current).get("actor_near_sleeve_surface_0");assertNotNull(outline);
                if(prior!=null){JSONArray a=byId(prior).get("actor_near_sleeve_surface_0").getJSONArray("commands"),b=outline.getJSONArray("commands");assertEquals(a.length(),b.length());for(int i=0;i<a.length();i++)for(int k=1;k<a.getJSONArray(i).length();k++)assertEquals("No singular frame transport in "+view,a.getJSONArray(i).getDouble(k),b.getJSONArray(i).getDouble(k),.7/1280);}
                prior=current;
            }
        }
    }
    @Test public void actualBezierEdgesShareTangentsThroughTheBendInsteadOfMeetingAtJointCaps()throws Exception {
        for(String view:VIEWS){JSONObject d=draw(settings("slender",view,"tailored"),new JSONObject().put("nearHandX",90).put("nearHandY",-260).put("nearHandZ",24));JSONArray commands=byId(d).get("actor_near_sleeve_surface_0").getJSONArray("commands");
            for(int first:new int[]{1,6})for(int n=first;n<first+3;n++){JSONArray a=commands.getJSONArray(n),b=commands.getJSONArray(n+1);assertEquals("C",a.getString(0));assertEquals("C",b.getString(0));for(int axis=0;axis<2;axis++)assertEquals("Native cubic tangent must continue across the joint: "+view,a.getDouble(5+axis)-a.getDouble(3+axis),b.getDouble(1+axis)-a.getDouble(5+axis),1e-10);}
        }
    }
    @Test public void softLightingUsesSemanticMaterialsEvenWhenAllUserColorsAreEqual()throws Exception {
        for(String view:VIEWS){JSONObject s=settings("broad",view,"tailored").put("shading","soft").put("skinColor","#FF777777").put("coatColor","#FF777777").put("trouserColor","#FF777777"),p=new JSONObject().put("nearHandY",-250),d=draw(s,p);Map<String,JSONObject> objects=byId(d);
            String coat=SketchDocument.canonical(objects.get("actor_coat_0").getJSONObject("gradient")),trousers=SketchDocument.canonical(objects.get("actor_left_trouser_surface_0").getJSONObject("gradient")),skin=SketchDocument.canonical(objects.get("actor_neck_0").getJSONObject("gradient"));assertNotEquals(coat,trousers);assertNotEquals(coat,skin);assertNotEquals(trousers,skin);
            for(JSONObject o:objects.values())if(o.getString("id").matches("actor_(near|far)_sleeve_(surface|front)_\\d+")&&o.getBoolean("fill"))assertEquals(coat,SketchDocument.canonical(o.getJSONObject("gradient")));
            JSONObject reverse=draw(SketchDocument.copy(s).put("lightAngle",-45),p);Map<String,JSONObject> other=byId(reverse);for(String id:objects.keySet())assertEquals("Lighting cannot change actual geometry",objects.get(id).getJSONArray("commands").toString(),other.get(id).getJSONArray("commands").toString());
        }
    }
    @Test public void forearmDepthCrossesTheCoatLocallyInActualNativePixels()throws Exception {
        for(String view:VIEWS)for(int z:new int[]{-70,70}){
            double yaw=view.equals("left")?-Math.PI/2:view.equals("right")?Math.PI/2:view.equals("back")?Math.PI:view.equals("high")?.20:view.equals("low")?-.16:0,pitch=view.equals("high")?.32:view.equals("low")?-.24:0;
            JSONObject s=settings("slender",view,"tailored"),p=new JSONObject().put("nearHandX",-Math.sin(yaw)*z).put("nearHandY",-210).put("nearHandZ",Math.cos(yaw)*z),d=draw(s,p);SketchHumanFigure.Skeleton sk=SketchHumanFigure.skeleton(s,p);
            JSONArray all=objects(d);for(int i=0;i<all.length();i++){JSONObject o=all.getJSONObject(i);if(o.getString("id").startsWith("actor_near_sleeve_")&&o.getBoolean("fill"))o.put("color","#FFFF2020");}
            // Isolate the sleeve/coat depth pair. Native Region diagnosis proves
            // the unchanged cuff covers this forearm point in right profile;
            // neither it nor the other arm is a coat-occlusion oracle.
            JSONArray selected=new JSONArray();for(int i=0;i<all.length();i++){String id=all.getJSONObject(i).getString("id");if(!id.startsWith("actor_far_")&&!id.startsWith("actor_near_cuff_"))selected.put(all.getJSONObject(i));}d.getJSONArray("layers").getJSONObject(0).put("objects",selected);
            if(System.getProperty("videostudio.tailored.depthDebug")!=null){File dir=new File(System.getProperty("videostudio.tailored.depthDebug"));Bitmap debug=render(d);try{save(debug,new File(dir,view+"-"+z+".png"));Files.write(new File(dir,view+"-"+z+".json").toPath(),d.toString(2).getBytes(java.nio.charset.StandardCharsets.UTF_8));}finally{debug.recycle();}}
            double[] point={sk.nearElbow[0]*.2+sk.nearHand[0]*.8,sk.nearElbow[1]*.2+sk.nearHand[1]*.8,sk.nearElbow[2]*.2+sk.nearHand[2]*.8};double[] projected=SketchHumanFigure.project(s,point);if(System.getProperty("videostudio.tailored.depthDebug")!=null)Files.write(new File(System.getProperty("videostudio.tailored.depthDebug"),view+"-"+z+"-probe.json").toPath(),new JSONObject().put("point",new JSONArray(point)).put("projected",new JSONArray(projected)).put("elbow",new JSONArray(sk.nearElbow)).put("hand",new JSONArray(sk.nearHand)).toString(2).getBytes(java.nio.charset.StandardCharsets.UTF_8));Bitmap b=render(d);
            try{int red=0,cx=(int)Math.round(640+projected[0]),cy=(int)Math.round(633.6+projected[1]);for(int dx=-2;dx<=2;dx++)for(int dy=-2;dy<=2;dy++)if(b.getPixel(cx+dx,cy+dy)==0xFFFF2020)red++;double[] hip=SketchHumanFigure.project(s,sk.hip),shoulder=SketchHumanFigure.project(s,sk.shoulderCentre);double t=(projected[1]-hip[1])/(shoulder[1]-hip[1]),expected=0;double[] normal={-Math.sin(yaw)*Math.cos(pitch),Math.sin(pitch),Math.cos(yaw)*Math.cos(pitch)};for(int axis=0;axis<3;axis++)expected+=(point[axis]-(sk.hip[axis]+(sk.shoulderCentre[axis]-sk.hip[axis])*t))*normal[axis];assertEquals("Actual forward forearm at matched torso height: "+view+" depth="+expected,expected>0,red>0);}finally{b.recycle();}
        }
    }
    @Test public void separateGarmentsPreserveActualArmpitAndLegNegativeSpace()throws Exception {
        for(String build:BUILDS)for(String view:new String[]{"front","back"}){
            JSONObject d=draw(settings(build,view,"tailored"),new JSONObject().put("nearHandX",120).put("nearHandY",-250).put("farHandX",-120).put("farHandY",-250));Path cloth=parts(d,"actor_",true),gap=new Path(),filled=new Path();
            gap.addRect(638.5f,563,641.5f,568,Path.Direction.CW);assertTrue(filled.op(cloth,gap,Path.Op.INTERSECT));assertTrue("Do not bridge separate trouser legs",area(filled)<.1);
            gap.reset();gap.addRect(706,386,710,391,Path.Direction.CW);assertTrue(filled.op(cloth,gap,Path.Op.INTERSECT));assertTrue("Do not fill the raised arm's negative space",area(filled)<.1);
        }
    }
    @Test public void shoulderRootAndBootThroatHaveContinuousNativeClothCoverage()throws Exception {
        JSONObject s=settings("slender","front","tailored"),p=new JSONObject();SketchHumanFigure.Skeleton body=SketchHumanFigure.skeleton(s,p);Bitmap b=render(draw(s,p));
        try{
            for(double[] shoulder:new double[][]{body.nearShoulder,body.farShoulder}){double[] point=SketchHumanFigure.project(s,shoulder);int x=(int)Math.round(640+point[0]+Math.copySign(6,point[0])),y=(int)Math.round(633.6+point[1]-5);assertNotEquals("Sleeve root must join coat without the observed background notch",BACKGROUND,b.getPixel(x,y));}
            double[] ankle=SketchHumanFigure.project(s,body.leftAnkle);assertNotEquals("Trouser cuff must enter the unchanged shoe throat without the observed background wedge",BACKGROUND,b.getPixel((int)Math.round(640+ankle[0]-3),(int)Math.round(633.6+ankle[1]+2)));
        }finally{b.recycle();}
    }
    @Test public void independentlyReviewedReachSleeveHeadNotchStaysFilled()throws Exception {
        JSONObject s=settings("slender","front","tailored");
        // Independently reviewed reach notch: these were coat pixels in the
        // original renderer and background in the first tailored generation.
        Bitmap b=render(draw(s,pose(1,"front")));try{
            for(int[] pixel:new int[][]{{610,322},{611,322},{612,322},{613,322},{612,324},{613,325}})assertNotEquals("Observed reach sleeve-head notch at "+Arrays.toString(pixel),BACKGROUND,b.getPixel(pixel[0],pixel[1]));
        }finally{b.recycle();}
    }
    @Test public void crossingLegsUseTheLocalPhysicalDepthAtTheirActualIntersection()throws Exception {
        JSONObject s=settings("slender","front","tailored"),p=new JSONObject().put("hipY",-155).put("footLeftX",36).put("footRightX",-36).put("footLeftZ",85).put("footRightZ",50),d=draw(s,p);SketchHumanFigure.Skeleton sk=SketchHumanFigure.skeleton(s,p);
        double[] a=SketchHumanFigure.project(s,sk.leftHip),b=SketchHumanFigure.project(s,sk.leftKnee),c=SketchHumanFigure.project(s,sk.rightHip),e=SketchHumanFigure.project(s,sk.rightKnee),u=sub(b,a),v=sub(e,c),between=sub(c,a);
        double determinant=u[0]*v[1]-u[1]*v[0],leftT=(between[0]*v[1]-between[1]*v[0])/determinant,rightT=(between[0]*u[1]-between[1]*u[0])/determinant;assertTrue(leftT>0&&leftT<1&&rightT>0&&rightT<1);
        double leftZ=sk.leftHip[2]+(sk.leftKnee[2]-sk.leftHip[2])*leftT,rightZ=sk.rightHip[2]+(sk.rightKnee[2]-sk.rightHip[2])*rightT;
        JSONArray all=objects(d);for(int i=0;i<all.length();i++){JSONObject o=all.getJSONObject(i);if(o.getBoolean("fill")&&o.getString("id").startsWith("actor_left_trouser_"))o.put("color","#FFFF2020");if(o.getBoolean("fill")&&o.getString("id").startsWith("actor_right_trouser_"))o.put("color","#FF2020FF");}
        Bitmap image=render(d);try{assertEquals("Local knee/thigh depth, not average whole-leg order: "+leftZ+" / "+rightZ,leftZ>rightZ?0xFFFF2020:0xFF2020FF,image.getPixel((int)Math.round(640+a[0]+u[0]*leftT),(int)Math.round(633.6+a[1]+u[1]*leftT)));}finally{image.recycle();}
    }
    @Test public void captureNativeTailoredSixViewTwoBuildFirstPreviews()throws Exception {
        String output=System.getProperty("videostudio.tailored.preview");if(output==null)return;File root=new File(output);assertFalse(new File(root,"Preview-Index.json").exists());JSONArray rows=new JSONArray();
        for(String build:BUILDS)for(String view:VIEWS)for(int mode=1;mode<=3;mode++){
            String label=build+"-"+view+"-"+mode;JSONObject s=settings(build,view,"tailored").put("faceStyle",mode==0?"graphic":mode==1?"angular":"soft").put("shading",mode==1?"soft":"cel"),p=pose(mode,view),d=draw(s,p);Bitmap b=render(d);
            try{File png=new File(root,label+".png");save(b,png);rows.put(new JSONObject().put("id",label).put("settings",s).put("pose",p).put("pixelHash",hash(b)).put("pngSha256",SketchProjectStore.fileHash(png)).put("visual","UNREVIEWED"));Files.write(new File(root,label+".json").toPath(),d.toString(2).getBytes(java.nio.charset.StandardCharsets.UTF_8));}finally{b.recycle();}
        }Files.write(new File(root,"Preview-Index.json").toPath(),new JSONObject().put("nativeFrames",rows).put("count",rows.length()).put("motion","UNREVIEWED").toString(2).getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }
    static Map<String,JSONObject> byId(JSONObject d)throws Exception{Map<String,JSONObject> out=new LinkedHashMap<>();JSONArray a=objects(d);for(int i=0;i<a.length();i++)out.put(a.getJSONObject(i).getString("id"),a.getJSONObject(i));return out;}
    static double[] sub(double[] a,double[] b){double[] out=a.clone();for(int i=0;i<out.length;i++)out[i]-=b[i];return out;}
    static double[] unit(double[] a){double n=0;for(double v:a)n+=v*v;n=Math.sqrt(n);for(int i=0;i<a.length;i++)a[i]/=n;return a;}
    static JSONObject settings(String build,String view,String style)throws Exception {JSONObject s=new JSONObject().put("build",build).put("view",view);if(style!=null)s.put("clothingStyle",style);return s;}
    static JSONObject pose(int mode,String view)throws Exception {return mode==0?new JSONObject():SketchHumanAnimation.poseAt(SketchHumanPresets.createForView(mode==1?"reach":mode==2?"kneel":"lift",48,24,new JSONObject(),view),mode==1?25:38);}
    static JSONObject draw(JSONObject s,JSONObject p)throws Exception {JSONObject doc=SketchDocument.create(new JSONObject().put("sketchId","clothing_fixture").put("width",1280).put("height",720).put("frameCount",1).put("fps",24).put("background","#FFF4E4D1"));SketchHumanFigure.draw(doc,doc.getJSONArray("layers").getJSONObject(0),"actor",s,p,0);return doc;}
    static Bitmap render(JSONObject doc)throws Exception {try(SketchFrameRenderer r=new SketchFrameRenderer(RuntimeEnvironment.getApplication(),doc)){return r.render(0);}}
    static JSONArray objects(JSONObject d)throws Exception{return d.getJSONArray("layers").getJSONObject(0).getJSONArray("objects");}
    static Path parts(JSONObject d,String prefix,boolean fill)throws Exception {Path path=new Path();JSONArray all=objects(d);for(int i=0;i<all.length();i++){JSONObject o=all.getJSONObject(i);if(o.getBoolean("fill")==fill&&o.getString("id").startsWith(prefix))assertTrue(path.op(SketchFrameRenderer.path(o.getJSONArray("commands"),1280,720),Path.Op.UNION));}return path;}
    static double area(Path p){float[] a=p.approximate(.02f);double total=0;for(int i=3;i<a.length;i+=3)if(a[i]!=a[i-3])total+=a[i-2]*a[i+2]-a[i+1]*a[i-1];return Math.abs(total)/2;}
    static String hash(Bitmap b)throws Exception {int[] p=new int[b.getWidth()*b.getHeight()];b.getPixels(p,0,b.getWidth(),0,0,b.getWidth(),b.getHeight());ByteBuffer out=ByteBuffer.allocate(p.length*4);for(int c:p)out.putInt(c);return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(out.array()));}
    static void save(Bitmap b,File f)throws Exception {assertTrue(f.getParentFile().isDirectory()||f.getParentFile().mkdirs());try(OutputStream o=new FileOutputStream(f)){assertTrue(b.compress(Bitmap.CompressFormat.PNG,100,o));}}
}
