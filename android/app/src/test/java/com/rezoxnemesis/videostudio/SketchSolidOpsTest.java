package com.rezoxnemesis.videostudio;
import android.content.Context;
import android.graphics.Bitmap;
import org.json.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.*;
import java.util.*;
import static org.junit.Assert.*;
@RunWith(RobolectricTestRunner.class) @Config(sdk=33,manifest=Config.NONE) @GraphicsMode(GraphicsMode.Mode.NATIVE)
public class SketchSolidOpsTest {
    @Test public void worldSquashStaysVerticalAfterQuarterTurn()throws Exception {
        JSONObject p=program().put("scaleSpace","world");for(int n=0;n<2;n++)p.getJSONArray("keys").getJSONObject(n).put("pitch",90).put("yaw",0).put("scaleY",.5).put("scaleX",Math.sqrt(2)).put("scaleZ",Math.sqrt(2));
        SketchSolidOps.Model m=new SketchSolidOps.Model(new JSONObject().put("frameCount",61),p);double lo=Double.POSITIVE_INFINITY,hi=-lo;for(double[] v:m.vertices(30)){lo=Math.min(lo,v[1]);hi=Math.max(hi,v[1]);}assertEquals(70,hi-lo,1e-9);assertEquals(0,lo,1e-9);
    }
    @Test public void flightHasParabolicAccelerationAndIndependentImpactSquash()throws Exception {
        JSONObject p=program().put("preserveVolume",true);JSONObject a=p.getJSONArray("keys").getJSONObject(0),b=p.getJSONArray("keys").getJSONObject(1);
        a.put("flightHeight",200).put("scaleY",.6).put("scaleX",1/Math.sqrt(.6)).put("scaleZ",1/Math.sqrt(.6)).put("scaleInterpolation","smooth");b.put("yaw",0).put("scaleY",1.2).put("scaleX",1/Math.sqrt(1.2)).put("scaleZ",1/Math.sqrt(1.2));
        SketchSolidOps.Model m=new SketchSolidOps.Model(new JSONObject().put("frameCount",61),p);
        double[] first=m.pose(0),q=m.pose(15),mid=m.pose(30),last=m.pose(60);assertEquals(150,q[1],1e-9);assertEquals(200,mid[1],1e-9);assertEquals(0,last[1],1e-9);
        for(int f=0;f<=60;f++){double[] pose=m.pose(f);assertEquals("Squash must conserve volume between authored keys",1,pose[6]*pose[7]*pose[8],1e-9);}
        assertEquals(.6+(1.2-.6)*.15625,q[7],1e-9);assertEquals(0,first[1],0);
    }
    @Test public void shortestBounceStudyHasStrictlyIncreasingPoseFrames()throws Exception {
        JSONObject p=SketchSolidPrograms.bounce(48);new SketchSolidOps.Model(new JSONObject().put("frameCount",48),p);
    }
    @Test public void tallCanvasSupportsGroundAndThinOutlines()throws Exception {
        JSONObject d=SketchDocument.create(new JSONObject().put("sketchId","tall").put("width",1280).put("height",2048).put("fps",30).put("frameCount",61));
        JSONObject p=program().put("ground",true).put("strokePx",.5);
        SketchSolidOps.apply(d,request("tall",p).getJSONArray("operations").getJSONObject(0));SketchDocument.validate(d);
        assertEquals(.0005,SketchDocument.object(d,"crate_ground").getDouble("width"),0);
        assertEquals(.0005,SketchDocument.object(d,"crate_f0_ink").getDouble("width"),0);
    }
    @Test public void airborneRotationDoesNotMoveTheCenterOfMass()throws Exception {
        Context c=RuntimeEnvironment.getApplication();String id=create(c,61);JSONObject p=program();p.getJSONArray("keys").getJSONObject(0).put("elevation",200);p.getJSONArray("keys").getJSONObject(1).put("elevation",200).put("yaw",0).put("pitch",360);JSONObject d;try(SketchProjectStore s=new SketchProjectStore(c)){d=s.get(id);}SketchSolidOps.Model m=new SketchSolidOps.Model(d,p);
        for(int f:new int[]{0,7,23,37,53,60}){double center=0;for(double[] v:m.vertices(f))center+=v[1]/8;assertEquals("Rotation must not add vertical bobbing to an airborne center",270,center,1e-9);}
    }
    @Test public void depthCanTurnSmoothlyWithoutSlowingTheAngularChannel()throws Exception {
        Context c=RuntimeEnvironment.getApplication();String id=create(c,61);JSONObject p=program();JSONObject a=p.getJSONArray("keys").getJSONObject(0),b=p.getJSONArray("keys").getJSONObject(1);a.put("depthInterpolation","smooth");b.put("depth",600);JSONObject d;try(SketchProjectStore s=new SketchProjectStore(c)){d=s.get(id);}SketchSolidOps.Model m=new SketchSolidOps.Model(d,p);assertEquals(93.75,m.pose(15)[2],1e-9);assertEquals(90,m.pose(15)[4],1e-9);assertTrue(m.pose(1)[2]<1);
    }
    @Test public void agentCapabilitiesSupplyAUsableSourceFreeMotionExample()throws Exception {
        Context c=RuntimeEnvironment.getApplication();String id=create(c,240);JSONObject capability=SketchCapabilities.get().getJSONObject("solidConstruction");assertEquals("4.7.1",capability.getString("minimumNativeVersion"));SketchEditOps.execute(c,"sketch_edit",request(id,capability.getJSONObject("exampleProgram")));
        try(SketchProjectStore s=new SketchProjectStore(c)){JSONObject d=s.get(id);SketchDocument.validate(d);assertTrue(SketchDocument.object(d,"crate_f40_ink").getJSONArray("commands").length()>5);}
    }
    @Test public void nativeGroundAndShadowAreAuthoredWithoutImportedPictures()throws Exception {
        Context c=RuntimeEnvironment.getApplication();String id=create(c,61);JSONObject p=program().put("ground",true);SketchEditOps.execute(c,"sketch_edit",request(id,p));
        try(SketchProjectStore s=new SketchProjectStore(c)){JSONObject d=s.get(id);assertEquals("path",SketchDocument.object(d,"crate_ground").getString("kind"));JSONObject shadow=SketchDocument.object(d,"crate_f0_shadow");assertTrue(shadow.getBoolean("fill"));assertEquals(0,shadow.getInt("frame"));assertFalse(shadow.has("reference"));}
    }
    @Test public void aDifferentCuboidKeepsItsProportionsAsItMovesIntoDepth()throws Exception {
        Context c=RuntimeEnvironment.getApplication();String id=create(c,61);JSONObject p=program().put("size",new JSONArray("[120,200,60]"));p.getJSONArray("keys").getJSONObject(1).put("yaw",0).put("depth",1000);SketchEditOps.execute(c,"sketch_edit",request(id,p));
        try(SketchProjectStore s=new SketchProjectStore(c)){JSONObject d=s.get(id);JSONArray a=SketchDocument.object(d,"crate_f0_face0").getJSONArray("commands"),b=SketchDocument.object(d,"crate_f60_face0").getJSONArray("commands");double widthA=Math.abs(a.getJSONArray(3).getDouble(1)-a.getJSONArray(0).getDouble(1))*1280,widthB=Math.abs(b.getJSONArray(3).getDouble(1)-b.getJSONArray(0).getDouble(1))*1280,heightA=Math.abs(a.getJSONArray(1).getDouble(2)-a.getJSONArray(0).getDouble(2))*720;assertEquals(.6,widthA/heightA,1e-9);assertEquals(970d/1970,widthB/widthA,1e-9);}
    }
    @Test public void heldPoseChangesOnItsExactKey()throws Exception {
        Context c=RuntimeEnvironment.getApplication();String id=create(c,61);JSONObject p=program();p.getJSONArray("keys").getJSONObject(0).put("interpolation","hold");p.getJSONArray("keys").getJSONObject(1).put("yaw",180);SketchEditOps.execute(c,"sketch_edit",request(id,p));
        try(SketchProjectStore s=new SketchProjectStore(c)){JSONObject d=s.get(id);try(SketchFrameRenderer r=new SketchFrameRenderer(c,d)){Bitmap a=r.render(60);try{assertEquals("A held interval must land on its authored endpoint",0xff39b9ad,a.getPixel(640,360));}finally{a.recycle();}}}
    }
    @Test public void nativeEditConstructsAFullTurnWithoutLosingSymmetryOrFaceIdentity()throws Exception {
        Context c=RuntimeEnvironment.getApplication();String id=create(c,61);JSONObject request=request(id,program());SketchEditOps.execute(c,"sketch_edit",request);
        try(SketchProjectStore s=new SketchProjectStore(c)){
            JSONObject d=s.get(id);SketchDocument.validate(d);assertEquals(2,d.getInt("revision"));
            JSONObject front=SketchDocument.object(d,"crate_f0_face0");JSONArray p=front.getJSONArray("commands");
            assertEquals(1,p.getJSONArray(0).getDouble(1)+p.getJSONArray(3).getDouble(1),1e-10);
            for(int f:new int[]{0,15,30,45,60}){
                Set<String> ink=new HashSet<>();JSONArray commands=SketchDocument.object(d,"crate_f"+f+"_ink").getJSONArray("commands");for(int n=0;n<commands.length();n++)ink.add(commands.getJSONArray(n).toString().substring(4));
                JSONArray objects=d.getJSONArray("layers").getJSONObject(0).getJSONArray("objects");int faces=0;
                for(int n=0;n<objects.length();n++){JSONObject o=objects.getJSONObject(n);assertEquals("path",o.getString("kind"));assertFalse(o.has("reference"));if(o.getInt("frame")!=f||!o.getBoolean("fill"))continue;faces++;JSONArray q=o.getJSONArray("commands");for(int k=0;k<q.length()-1;k++)assertTrue("Face corners must share exact ink endpoints",ink.contains(q.getJSONArray(k).toString().substring(4)));}
                assertTrue(faces>=1&&faces<=3);
            }
            try(SketchFrameRenderer r=new SketchFrameRenderer(c,d)){Bitmap a=r.render(0),b=r.render(30);try{assertEquals(0xfff2a94a,a.getPixel(640,360));assertEquals(0xff39b9ad,b.getPixel(640,360));}finally{a.recycle();b.recycle();}}
        }
        SketchEditOps.execute(c,"sketch_undo",new JSONObject().put("sketchId",id).put("expectedRevision",2).put("operationId","undo"));try(SketchProjectStore s=new SketchProjectStore(c)){assertEquals(0,s.get(id).getJSONArray("layers").getJSONObject(0).getJSONArray("objects").length());}
    }
    @Test public void invalidProjectionAndOversizedCompilationLeaveTheDrawingUntouched()throws Exception {
        Context c=RuntimeEnvironment.getApplication();String id=create(c,1200);JSONObject p=program();p.getJSONObject("camera").put("distance",32);assertThrows(Exception.class,()->SketchEditOps.execute(c,"sketch_edit",request(id,p)));
        JSONObject large=program();large.getJSONArray("keys").getJSONObject(1).put("frame",1199);assertThrows(Exception.class,()->SketchEditOps.execute(c,"sketch_edit",request(id,large)));
        try(SketchProjectStore s=new SketchProjectStore(c)){assertEquals(1,s.get(id).getInt("revision"));assertEquals(0,s.get(id).getJSONArray("layers").getJSONObject(0).getJSONArray("objects").length());}
    }
    static String create(Context c,int frames)throws Exception {String id="solid_"+UUID.randomUUID();try(SketchProjectStore s=new SketchProjectStore(c)){s.importOwnedDocument(SketchDocument.create(new JSONObject().put("sketchId",id).put("width",1280).put("height",720).put("fps",30).put("frameCount",frames)),"blank");}return id;}
    static JSONObject program()throws Exception {return new JSONObject().put("size",new JSONArray("[140,140,140]")).put("camera",new JSONObject("{\"focal\":900,\"distance\":1000,\"height\":300,\"horizon\":0.2}")).put("colors",new JSONArray("[\"#FFF2A94A\",\"#FF39B9AD\",\"#FF405C9C\",\"#FFE76E5B\",\"#FFFFD87A\",\"#FF7452A3\"]")).put("ink","#FF142637").put("strokePx",3).put("keys",new JSONArray().put(key(0,0)).put(key(60,360)));}
    static JSONObject key(int f,double yaw)throws Exception{return new JSONObject().put("frame",f).put("x",0).put("elevation",0).put("depth",0).put("yaw",yaw);}
    static JSONObject request(String id,JSONObject p)throws Exception{return new JSONObject().put("sketchId",id).put("expectedRevision",1).put("operationId","construct").put("operations",new JSONArray().put(new JSONObject().put("type","add_solid").put("layerId","layer_1").put("solidId","crate").put("program",p)));}
}
