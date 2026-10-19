package com.rezoxnemesis.videostudio;
import org.json.*;
import java.util.*;
/** Editable motion starting points; authoring choices, never an acting-quality certificate. */
final class SketchHumanPresets {
 static final String[] NAMES={"listen","explain","reach","recoil","relief","bow","kneel","walk","run","point","offer","tie","inspect","surprise","anger","remorse","wave","look_up","look_down","stretch","step_back","balance","lift","greet"};
 private SketchHumanPresets(){}
 static JSONObject motionDefaults()throws Exception {return new JSONObject().put("phase",0).put("speed",1).put("travelScale",1).put("groundSlope",0).put("groundSlopeX",0).put("direction",1).put("strength",1).put("armSwing",1);}
 static JSONObject motion(JSONObject in)throws Exception {SketchDocument.fields(in,"phase,speed,travelScale,groundSlope,groundSlopeX,direction,strength,armSwing");JSONObject m=motionDefaults();java.util.Iterator<String> keys=in.keys();while(keys.hasNext()){String key=keys.next();m.put(key,in.get(key));}SketchDocument.number(m,"phase",0,1);SketchDocument.number(m,"speed",.5,1.5);SketchDocument.number(m,"travelScale",0,1.5);SketchDocument.number(m,"groundSlope",-.5,.5);SketchDocument.number(m,"groundSlopeX",-.5,.5);double direction=SketchDocument.number(m,"direction",-1,1);if(direction!=-1&&direction!=1)throw SketchDocument.bad("Motion direction must be-1or1");SketchDocument.number(m,"strength",0,1);SketchDocument.number(m,"armSwing",0,1);return m;}
 static JSONArray create(String name,int frames,int fps)throws Exception {return create(name,frames,fps,new JSONObject());}
 static JSONArray create(String name,int frames,int fps,JSONObject input)throws Exception {JSONObject motion=motion(input);
  if(!Arrays.asList(NAMES).contains(name))throw SketchDocument.bad("Unknown human action");if((name.equals("walk")||name.equals("run"))&&frames>240)throw SketchDocument.bad("Walking/running uses exact output-frame contacts; use a shot of at most240frames");JSONArray out=new JSONArray();int samples=name.equals("walk")||name.equals("run")?frames:9;
  for(int n=0;n<samples;n++){int frame=(int)Math.round((frames-1)*n/(double)(samples-1));double u=frame/(double)(frames-1),t=frame/(double)fps,a=u<.16?-.03*SketchCharacterAnimation.smooth(u/.16):u<.48?SketchCharacterAnimation.smooth((u-.16)/.32):u<.72?1:1-SketchCharacterAnimation.smooth((u-.72)/.28);JSONObject p=SketchHumanFigure.poseDefaults();
   p.put("headTilt",-2*Math.sin(t*1.5)).put("gazeX",.25*a).put("blink",0);
   switch(name){
    case "listen":p.put("headTilt",8*a).put("gazeX",.6*a).put("brow",.25*a);break;
    case "explain":p.put("nearHandX",58+38*a).put("nearHandY",-166-89*a).put("farHandX",-58-12*a).put("farHandY",-166-20*a).put("bodyLean",-5*a).put("smile",.25*a);break;
    case "reach":case "point":p.put("nearHandX",58+53*a).put("nearHandY",-166-107*a).put("bodyLean",8*a).put("headTilt",-4*a);break;
    case "offer":p.put("nearHandX",58+32*a).put("nearHandY",-166-70*a).put("farHandX",-58+30*a).put("farHandY",-166-62*a).put("smile",.35*a);break;
    case "recoil":case "surprise":p.put("bodyLean",-12*a).put("hipY",-180+9*a).put("nearHandY",-166-88*a).put("nearHandX",58-12*a).put("farHandY",-166-75*a).put("brow",.8*a).put("mouth",.35*Math.max(0,a));break;
    case "relief":p.put("headTilt",10*a).put("hipY",-180+5*a).put("smile",.65*a).put("brow",.3*a);break;
    case "bow":case "remorse":p.put("bodyLean",12*a).put("headTilt",15*a).put("gazeY",.8*a).put("smile",-.35*a).put("brow",-.3*a).put("hipY",-180+6*a);break;
    case "kneel":{
     // Descend once, then keep the supporting knee down until the cel ends.
     double kneel=SketchCharacterAnimation.smooth(u/.45);
     // X=20.5 is midway between the two pelvis widths (18 and 23), so both
     // builds have the same 2.5px lateral reach. With hipY=-90, kneeY=-9
     // and ankleY=-14, solve the 90/86px bones in the IK bend plane exactly.
     double lateral=2.5/76,plane=1+lateral*lateral;
     double rearZ=Math.sqrt(90*90-81*81*plane)-Math.sqrt(86*86-5*5*plane);
     p.put("hipY",-180+90*kneel).put("bodyLean",12*kneel)
      .put("footRightX",28-7.5*kneel).put("footRightZ",9+(rearZ-9)*kneel)
      .put("footLeftX",-28+7.5*kneel).put("footLeftZ",-9+74*kneel)
      .put("nearHandX",58-26*kneel).put("farHandX",-58+26*kneel)
      .put("nearHandY",-166-14*kneel).put("farHandY",-166-14*kneel)
      .put("nearHandZ",16+44*kneel).put("farHandZ",-16+76*kneel)
      .put("nearElbowSwivel",100*kneel).put("farElbowSwivel",-100*kneel)
      .put("nearWrist",-25*kneel).put("farWrist",25*kneel).put("handOpen",1-.35*kneel)
      .put("headTilt",12*kneel).put("gazeX",0).put("gazeY",.65*kneel);break;
    }
    case "tie":p.put("nearHandX",58-33*a).put("farHandX",-58+33*a).put("nearHandY",-166-40*a).put("farHandY",-166-40*a).put("gazeY",.7*a).put("headTilt",12*a).put("bodyLean",8*a);break;
    case "inspect":p.put("nearHandX",58-12*a).put("nearHandY",-166-98*a).put("headTilt",9*a).put("gazeY",.4*a).put("brow",.45*a);break;
    case "anger":p.put("bodyLean",10*a).put("brow",-.8*a).put("smile",-.45*a).put("nearHandY",-166-45*a).put("nearHandX",58+20*a).put("farHandY",-166-60*a).put("farHandX",-58-18*a).put("farElbowSwivel",-120*a).put("farWrist",45*a);break;
    case "wave":case "greet":p.put("nearHandY",-166-141*a).put("nearHandX",58+(22+12*Math.sin(t*5))*a).put("smile",.5*a).put("headTilt",-5*a);break;
    case "look_up":p.put("headTilt",-16*a).put("gazeY",-.9*a).put("bodyLean",-4*a);break;
    case "look_down":p.put("headTilt",16*a).put("gazeY",.9*a).put("bodyLean",5*a);break;
    case "stretch":p.put("nearHandY",-166-205*a).put("farHandY",-166-205*a).put("nearHandX",58+4*a).put("farHandX",-58-4*a).put("headTilt",-8*a).put("hipY",-180+4*a);break;
    case "step_back":p.put("rootZ",-20*a).put("hipY",-180+5*a).put("footLeftZ",-9-25*a).put("footRightZ",9-10*a).put("bodyLean",-6*a);break;
    case "balance":p.put("nearHandX",58+74*a).put("nearHandY",-166-91*a).put("farHandX",-58-74*a).put("farHandY",-166-91*a).put("hipY",-180+5*a).put("headTilt",-6*a);break;
    case "lift":
     // Move both wrists forward as they rise; moderate elbow swivel lowers
     // the elbows alongside the trunk without tie's inward chest crossing.
     p.put("nearHandX",58-30*a).put("farHandX",-58+30*a).put("nearHandY",-166-84*a).put("farHandY",-166-84*a)
      .put("nearHandZ",16+56*a).put("farHandZ",-16+88*a).put("nearElbowSwivel",45*a).put("farElbowSwivel",-45*a).put("hipY",-180+12*a).put("bodyLean",6*a);break;
    case "walk":case "run":gait(p,t,name.equals("run"),motion);break;
   }
   if(Set.of("explain","offer","inspect").contains(name))p.put("nearElbowSwivel",165*a).put("nearWrist",-65*a).put("handOpen",.8);
   if(name.equals("tie")||name.equals("lift")){
    if(name.equals("tie"))p.put("nearElbowSwivel",130*a).put("farElbowSwivel",-130*a);
    p.put("nearWrist",-35*a).put("farWrist",35*a).put("handOpen",.35);
   }
   if(name.equals("point")||name.equals("reach"))p.put("nearWrist",-50*a).put("handOpen",.75);
   JSONObject rest=SketchHumanFigure.poseDefaults();for(String key:SketchHumanFigure.POSE_CHANNELS)p.put(key,rest.getDouble(key)+(p.getDouble(key)-rest.getDouble(key))*motion.getDouble("strength"));
   p=SketchHumanGround.applyPlane(p,motion.getDouble("groundSlopeX"),motion.getDouble("groundSlope"),name.equals("walk")||name.equals("run"));
   if(name.equals("kneel")&&motion.getDouble("strength")==1&&frame>=frames/2)p=SketchHumanKneelContact.apply(p,motion.getDouble("groundSlopeX"),motion.getDouble("groundSlope"));
   p.put("frame",frame).put("interpolation",name.equals("walk")||name.equals("run")?"linear":"smooth");out.put(p);
  }return out;
 }
 static JSONArray createForView(String name,int frames,int fps,JSONObject motion,String view)throws Exception {
  if(!Set.of("front","left","right","back","high","low").contains(view))throw SketchDocument.bad("Unknown human view");JSONArray poses=create(name,frames,fps,motion);if(view.equals("left"))for(int i=0;i<poses.length();i++){JSONObject p=poses.getJSONObject(i);p.put("bodyLean",-p.getDouble("bodyLean")).put("headTilt",-p.getDouble("headTilt"));}return poses;
 }
 private static void gait(JSONObject p,double t,boolean run,JSONObject motion)throws Exception {
  double frequency=(run?.8:.65)*motion.getDouble("speed"),travel=140*motion.getDouble("travelScale")*motion.getDouble("direction"),phaseOffset=motion.getDouble("phase"),clock=t*frequency+phaseOffset,root=t*frequency*travel;
  p.put("rootZ",root).put("rootY",0).put("hipY",run?-170-6*Math.cos(clock*4*Math.PI):-191+4*Math.cos(clock*4*Math.PI)).put("bodyLean",run?8:3).put("headTilt",run?-5:-2);
  for(int foot=0;foot<2;foot++){double offset=foot*.5,phase=clock+offset-Math.floor(clock+offset),plant=(Math.floor(clock+offset)-offset-phaseOffset)*travel+travel*.25,ground=run?.42:.60,lift=24;double z=plant,y=0;if(phase>ground){double u=(phase-ground)/(1-ground);z+=travel*SketchCharacterAnimation.smooth(u);y=-lift*Math.sin(Math.PI*u);}p.put(foot==0?"footLeftZ":"footRightZ",z).put(foot==0?"footLeftY":"footRightY",y);}
  if(!run){double hip=p.getDouble("hipY");for(int foot=0;foot<2;foot++){String side=foot==0?"Left":"Right";double dz=p.getDouble("foot"+side+"Z")-root,dx=10;double ankle=p.getDouble("foot"+side+"Y")-14;hip=Math.max(hip,ankle-Math.sqrt(175.5*175.5-dz*dz-dx*dx));}p.put("hipY",hip);}
  double swing=Math.sin(clock*2*Math.PI)*motion.getDouble("armSwing");if(run)p.put("nearHandY",-245-8*swing).put("nearHandZ",55+30*swing).put("farHandY",-245+8*swing).put("farHandZ",-55-30*swing).put("nearElbowSwivel",90).put("farElbowSwivel",90).put("handOpen",.35);else p.put("nearHandY",-180-18*swing).put("nearHandZ",16+18*swing).put("farHandY",-180+18*swing).put("farHandZ",-16-18*swing).put("handOpen",.75);
 }
}
