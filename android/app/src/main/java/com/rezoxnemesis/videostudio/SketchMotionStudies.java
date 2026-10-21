package com.rezoxnemesis.videostudio;

import org.json.*;

/** Original editable capability exercises, authored and compiled entirely inside VideoStudio. */
final class SketchMotionStudies {
    private SketchMotionStudies(){}
    static JSONObject defaults()throws Exception{return new JSONObject().put("travel",.18).put("lift",.24);}

    static void apply(JSONObject document,JSONObject operation)throws Exception {
        SketchDocument.fields(operation,"type,layerId,sceneId,study,settings");
        String prefix=SketchDocument.id(operation,"sceneId");if(prefix.length()>36)throw SketchDocument.bad("Scene ID must fit 36 characters");
        JSONObject target=SketchDocument.layer(document,SketchDocument.id(operation,"layerId"));
        if(target.getBoolean("locked"))throw SketchDocument.bad("Layer is locked");
        // A scene identity is reserved by its native object namespace, across layers.
        JSONArray layers=document.getJSONArray("layers");
        for(int l=0;l<layers.length();l++){
            JSONArray objects=layers.getJSONObject(l).getJSONArray("objects");
            for(int i=0;i<objects.length();i++)if(objects.getJSONObject(i).getString("id").startsWith(prefix+"_"))
                throw SketchDocument.bad("Scene ID already exists; choose a new identity");
        }
        JSONObject built=build(document,operation.getString("study"),prefix,operation.getJSONObject("settings"));
        JSONObject candidate=SketchDocument.copy(document),layer=SketchDocument.layer(candidate,target.getString("id"));
        JSONArray objects=layer.getJSONArray("objects"),additions=built.getJSONObject("document").getJSONArray("layers").getJSONObject(0).getJSONArray("objects");
        for(int i=0;i<additions.length();i++)objects.put(additions.getJSONObject(i));
        SketchDocument.validate(candidate);target.put("objects",objects);
    }

    static JSONObject build(JSONObject document,String study,String prefix,JSONObject settings)throws Exception {
        SketchDocument.fields(settings,"travel,lift");
        double travel=settings.has("travel")?SketchDocument.number(settings,"travel",0,.3):.18;
        double lift=settings.has("lift")?SketchDocument.number(settings,"lift",.02,.3):.24;
        int frames=document.getInt("frameCount"),fps=document.getInt("fps");
        double seconds=frames/(double)fps;
        if(frames>240||seconds<4||seconds>8)throw SketchDocument.bad("Motion studies need 4..8 seconds and at most 240 frames; sketch timing is preserved");
        JSONObject source=SketchDocument.create(new JSONObject().put("sketchId",prefix+"_source")
                .put("width",document.getInt("width")).put("height",document.getInt("height"))
                .put("frameCount",frames).put("fps",fps).put("background",document.getString("background")));
        JSONArray objects=source.getJSONArray("layers").getJSONObject(0).getJSONArray("objects");
        double floor="cartoon_jump".equals(study)?.82:.8265;
        line(objects,prefix+"_floor",.08,floor,.92,floor,.003,"#FF6B7D79");
        if("cartoon_jump".equals(study))return cartoon(source,objects,prefix,travel,lift);
        if("walk_to_run".equals(study))return character(source,objects,prefix,travel);
        throw SketchDocument.bad("Choose cartoon_jump or walk_to_run");
    }

    private static JSONObject cartoon(JSONObject source,JSONArray objects,String p,double travel,double lift)throws Exception {
        ellipse(objects,p+"_shadow",.19,.8125,.14,.015,"#FF526B69");
        ellipse(objects,p+"_body",.20,.59,.12,.23,"#FFF5AE45");
        ellipse(objects,p+"_eye_l",.222,.646,.025,.039,"#FFFFFCF0");
        ellipse(objects,p+"_eye_r",.268,.646,.025,.039,"#FFFFFCF0");
        ellipse(objects,p+"_pupil_l",.233,.657,.011,.023,"#FF183C49");
        ellipse(objects,p+"_pupil_r",.279,.657,.011,.023,"#FF183C49");
        line(objects,p+"_smile_l",.24,.719,.26,.73,.006,"#FF7D412E");
        line(objects,p+"_smile_r",.26,.73,.28,.719,.006,"#FF7D412E");
        int frames=source.getInt("frameCount");
        for(int i=1;i<objects.length();i++){
            JSONObject object=objects.getJSONObject(i);JSONArray keys=object.getJSONArray("keys");
            for(int f=0;f<frames;f++){
                double t=f/(double)(frames-1),x=0,height=0,sy=1;
                if(t<.15)sy=1-.34*smooth(t/.15);
                else if(t<.20)sy=.66+.57*smooth((t-.15)/.05);
                else if(t<.65){double u=(t-.20)/.45;x=travel*u;height=4*lift*u*(1-u);sy=1+.23*Math.cos(2*Math.PI*u);}
                else{x=travel;double u=(t-.65)/.35;sy=1-.38*Math.exp(-6*u)*Math.cos(4*Math.PI*u);}
                if(f==frames-1)sy=1;
                JSONObject transform=SketchDocument.transform().put("anchorX",.26).put("anchorY",.82).put("x",x);
                if(object.getString("id").endsWith("_shadow"))transform.put("scaleX",1+height*2).put("opacity",.24/(1+height*6));
                else transform.put("y",-height).put("scaleY",sy).put("scaleX",1/sy);
                keys.put(new JSONObject().put("frame",f).put("transform",transform).put("interpolation","hold"));
            }
        }
        exposures(objects,frames);SketchDocument.validate(source);
        return new JSONObject().put("document",source).put("report",new JSONObject().put("study","cartoon_jump")
                .put("authoring","Native editable ellipses and strokes with shared volume-preserving transform keys")
                .put("professionalArtworkCertified",false));
    }

    private static JSONObject character(JSONObject source,JSONArray objects,String p,double travel)throws Exception {
        JSONObject program=SketchAnimationUiModel.createProgram(source).put("frameCount",source.getInt("frameCount"));
        JSONObject rig=SketchAnimationUiModel.rig(program,"character_1");JSONArray joints=rig.getJSONArray("joints"),bindings=rig.getJSONArray("bindings");
        for(int i=0;i<joints.length();i++){JSONObject j=joints.getJSONObject(i);j.put("x",.30+(j.getDouble("x")-.5)*.5).put("y",.13+j.getDouble("y")*.75);}
        // Back limbs precede the body; original cutout geometry stays editable.
        for(String side:new String[]{"right","left"}){
            String color="right".equals(side)?"#FF376C80":"#FF419C9A";
            bone(objects,bindings,rig,p,"hip_"+side,"knee_"+side,.024,color);
            bone(objects,bindings,rig,p,"knee_"+side,"foot_"+side,.021,color);
            JSONObject foot=SketchAnimationUiModel.joint(rig,"foot_"+side);
            ellipse(objects,p+"_sole_"+side,foot.getDouble("x")-.012,foot.getDouble("y")-.008,.045,.022,"#FF203C53");
            bind(bindings,p+"_sole_"+side,"foot_"+side);
        }
        // Four exact rig anchors keep shoulders and hips connected through IK.
        // A rigid narrow torso line leaves visible gaps on a wide humanoid rig.
        JSONArray outline=new JSONArray(),weights=new JSONArray();
        String[] corners={"arm_left","arm_right","hip_right","hip_left"};
        for(int i=0;i<corners.length;i++){
            JSONObject joint=SketchAnimationUiModel.joint(rig,corners[i]);
            outline.put(new JSONArray().put(i==0?"M":"L").put(joint.getDouble("x")).put(joint.getDouble("y")));
            weights.put(new JSONObject().put(corners[i],1));
        }
        outline.put(new JSONArray().put("Z"));
        objects.put(SketchEditOps.completeObject(new JSONObject().put("id",p+"_torso").put("kind","path")
                .put("commands",outline).put("color","#FFE38148").put("fill",true)));
        bindings.put(new JSONObject().put("objectId",p+"_torso").put("jointId","root").put("weights",weights));
        bone(objects,bindings,rig,p,"chest","neck",.036,"#FFF6C88D");
        bone(objects,bindings,rig,p,"neck","head",.028,"#FFF6C88D");
        for(String side:new String[]{"right","left"}){
            bone(objects,bindings,rig,p,"arm_"+side,"elbow_"+side,.019,"#FFE38148");
            bone(objects,bindings,rig,p,"elbow_"+side,"hand_"+side,.016,"#FFF6C88D");
        }
        JSONObject head=SketchAnimationUiModel.joint(rig,"head");double hx=head.getDouble("x"),hy=head.getDouble("y");
        ellipse(objects,p+"_head",hx-.035,hy-.052,.07,.105,"#FFF6C88D");bind(bindings,p+"_head","head");
        ellipse(objects,p+"_hair",hx-.038,hy-.058,.076,.045,"#FF293E4D");bind(bindings,p+"_hair","head");
        for(int side:new int[]{-1,1}){String id=p+"_eye_"+(side<0?"l":"r");ellipse(objects,id,hx+side*.014-.003,hy-.004,.006,.012,"#FF203C53");bind(bindings,id,"head");}
        line(objects,p+"_mouth",hx-.01,hy+.027,hx+.012,hy+.027,.003,"#FF925041");bind(bindings,p+"_mouth","head");
        int frames=source.getInt("frameCount"),split=frames/2;
        program=SketchAnimationMotionLibrary.insert(program,"character_1",0,split,"walk");
        JSONArray poses=SketchAnimationUiModel.rig(program,"character_1").getJSONArray("poses");
        double firstTravel=travel*.35,old=poses.getJSONObject(poses.length()-1).getDouble("rootX");
        for(int i=0;i<poses.length();i++)poses.getJSONObject(i).put("rootX",poses.getJSONObject(i).getDouble("rootX")/old*firstTravel);
        program=SketchAnimationMotionLibrary.insert(program,"character_1",split,frames-split,"run");
        poses=SketchAnimationUiModel.rig(program,"character_1").getJSONArray("poses");double runStart=poses.getJSONObject(9).getDouble("rootX"),runEnd=poses.getJSONObject(17).getDouble("rootX");
        for(int i=9;i<18;i++)poses.getJSONObject(i).put("rootX",firstTravel+(poses.getJSONObject(i).getDouble("rootX")-runStart)/(runEnd-runStart)*(travel-firstTravel));
        // Establish knee reach before the first plant in each clip. Sparse smooth
        // gait keys alone leave a nearly straight leg on those early frames.
        for(int start:new int[]{0,split}){
            JSONObject crouch=SketchAnimationUiModel.previousPose(SketchAnimationUiModel.rig(program,"character_1"),start+2);
            crouch.put("frame",start+2).put("rootY",.012).put("interpolation","smooth");
            SketchAnimationUiModel.rig(program,"character_1").getJSONArray("poses").put(crouch);
            SketchAnimationUiModel.rig(program,"character_1").put("poses",SketchAnimationUiModel.sortedFrames(SketchAnimationUiModel.rig(program,"character_1").getJSONArray("poses")));
        }
        exposures(objects,frames);
        program=SketchAnimationFootwork.plant(source,program,"character_1",0,split,"walk");
        program=SketchAnimationFootwork.plant(source,program,"character_1",split,frames-split,"run");
        program=groundSwing(source,program);
        JSONObject result=SketchAnimationCompiler.compile(source,program,p+"_compiled");result.put("program",program).put("sourceDocument",source);return result;
    }

    /** Keep swing soles above the same floor without weakening hard plants.
     * All adjustments use the existing native IK solver and remain ordinary FK keys. */
    private static JSONObject groundSwing(JSONObject source,JSONObject program)throws Exception {
        int frames=program.getInt("frameCount");JSONArray sampled=SketchAnimationCompiler.sampleFrames(source,program,0,frames-1);
        JSONObject next=SketchDocument.copy(program),rig=SketchAnimationUiModel.rig(next,"character_1");
        JSONObject probe=SketchDocument.copy(program),probeRig=SketchAnimationUiModel.rig(probe,"character_1");
        JSONArray dense=new JSONArray(),contacts=rig.getJSONArray("ik"),hard=new JSONArray();
        for(int i=0;i<contacts.length();i++){
            JSONObject contact=SketchDocument.copy(contacts.getJSONObject(i));contact.remove("blendInFrames");contact.remove("blendOutFrames");hard.put(contact);
        }
        for(int f=0;f<frames;f++){
            if(Thread.currentThread().isInterrupted())throw new InterruptedException("Motion study cancelled");
            JSONObject state=SketchAnimationUiModel.previousPose(rig,f);
            JSONObject pose=new JSONObject().put("frame",f).put("rootX",state.getDouble("rootX")).put("rootY",state.getDouble("rootY"))
                    .put("rotations",localAngles(rig,sampled.getJSONObject(f))).put("interpolation","hold");
            probeRig.put("poses",new JSONArray().put(pose));JSONArray guards=new JSONArray();
            for(String side:new String[]{"left","right"}){
                JSONObject ankle=sampleJoint(sampled.getJSONObject(f),"foot_"+side);
                double floor=SketchAnimationUiModel.joint(rig,"foot_"+side).getDouble("y");
                guards.put(new JSONObject().put("hip","hip_"+side).put("knee","knee_"+side).put("ankle","foot_"+side)
                        .put("start",f).put("end",f).put("x",ankle.getDouble("x")).put("y",Math.min(floor,ankle.getDouble("y")))
                        .put("bend","left".equals(side)?1:-1).put("rotation",0));
            }
            probeRig.put("ik",guards);
            pose.put("rotations",localAngles(rig,SketchAnimationCompiler.sample(source,probe,f)));dense.put(pose);
        }
        rig.put("poses",dense).put("ik",hard);return next;
    }
    private static JSONObject localAngles(JSONObject rig,JSONObject sample)throws Exception {
        JSONObject rotations=new JSONObject();JSONArray joints=rig.getJSONArray("joints");
        for(int i=0;i<joints.length();i++){
            JSONObject joint=joints.getJSONObject(i);String id=joint.getString("id"),parent=joint.optString("parent","");
            double angle=sampleJoint(sample,id).getDouble("rotation");
            if(!parent.isEmpty())angle-=sampleJoint(sample,parent).getDouble("rotation");rotations.put(id,angle);
        }
        return rotations;
    }
    private static JSONObject sampleJoint(JSONObject sample,String id)throws Exception {
        JSONArray joints=sample.getJSONArray("joints");for(int i=0;i<joints.length();i++)if(id.equals(joints.getJSONObject(i).getString("jointId")))return joints.getJSONObject(i);
        throw SketchDocument.bad("Motion study is missing joint "+id);
    }

    private static void bone(JSONArray objects,JSONArray bindings,JSONObject rig,String p,String from,String to,double width,String color)throws Exception {
        JSONObject a=SketchAnimationUiModel.joint(rig,from),b=SketchAnimationUiModel.joint(rig,to);String id=p+"_"+from;
        line(objects,id,a.getDouble("x"),a.getDouble("y"),b.getDouble("x"),b.getDouble("y"),width,color);bind(bindings,id,from);
    }
    private static void bind(JSONArray bindings,String object,String joint)throws Exception{bindings.put(new JSONObject().put("objectId",object).put("jointId",joint));}
    private static void ellipse(JSONArray out,String id,double x,double y,double w,double h,String color)throws Exception {
        out.put(SketchEditOps.completeObject(new JSONObject().put("id",id).put("kind","ellipse").put("bounds",new JSONObject()
                .put("x",x).put("y",y).put("width",w).put("height",h)).put("fill",true).put("color",color)));
    }
    private static void line(JSONArray out,String id,double x1,double y1,double x2,double y2,double width,String color)throws Exception {
        out.put(SketchEditOps.completeObject(new JSONObject().put("id",id).put("kind","line").put("points",new JSONArray()
                .put(new JSONArray().put(x1).put(y1).put(1)).put(new JSONArray().put(x2).put(y2).put(1)))
                .put("width",width).put("color",color)));
    }
    private static void exposures(JSONArray objects,int frames)throws Exception{for(int i=0;i<objects.length();i++)objects.getJSONObject(i).put("exposure",frames);}
    private static double smooth(double t){return t*t*(3-2*t);}
}
