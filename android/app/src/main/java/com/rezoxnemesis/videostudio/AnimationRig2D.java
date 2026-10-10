package com.rezoxnemesis.videostudio;

import org.json.JSONArray;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Deterministic articulated image mesh: hierarchical FK, linear blend skinning and two-link IK. */
public final class AnimationRig2D {
    public static final int MAX_BONES=24, MAX_VERTICES=512, MAX_TRIANGLES=1024, MAX_KEYS=2048, MAX_JSON_BYTES=262144;
    public static final long MAX_TIME_MS=604800000L;
    private static final String[] CHANNELS={"rotation","x","y","scaleX","scaleY"};
    private static final double[] REST={0,0,0,1,1};
    private final Bone[] bones;
    private final int[] order, triangles;
    private final Vertex[] vertices;
    private final Curve[][] curves;
    private final double[][] pose;
    private final Ik[] ik;
    private final Map<String,Integer> boneById;
    private final JSONObject definition;
    private final float aspect;
    private final boolean enabled;
    private final int keyCount;
    private long clipDurationMs=MAX_TIME_MS,clipOffsetMs;

    private static final class Bone {
        String id,name,parentId; int parent=-1;
        double x,y,endX,endY,length;
        Affine bind,local,inverse;
    }
    private static final class Vertex { double u,v; int[] bones; double[] weights; }
    private static final class Ik {
        String id; int root,child,bend; double x,y,mix;
        Curve[] curves=new Curve[3];
    }

    /** Each sampled array is owned by this frame; modifying it cannot mutate the compiled rig. */
    public static final class Frame {
        public final float[] positions,textureUvs,bonePositions,ikTargets,ikMix;
        public final int[] triangles;
        public final String[] boneIds,ikIds;
        public final boolean[] ikClamped;
        public final long authoredTimeMs;
        private final double[][] pose;
        Frame(float[] positions,float[] uv,int[] triangles,float[] bonePositions,String[] ids,
              boolean[] clamped,float[] targets,float[] mix,String[] ikIds,long at,double[][] pose){
            this.positions=positions;textureUvs=uv;this.triangles=triangles;this.bonePositions=bonePositions;
            boneIds=ids;this.ikIds=ikIds;ikClamped=clamped;ikTargets=targets;ikMix=mix;authoredTimeMs=at;this.pose=pose;
        }
        public JSONObject poseJson(){
            JSONObject result=new JSONObject();
            try{for(int index=0;index<boneIds.length;index++)result.put(boneIds[index],channels(pose[index]));}
            catch(Exception error){throw invalid("Sampled bone pose cannot be described",error);}return result;
        }
    }

    public static void validate(JSONObject rig){compile(rig);}
    public static AnimationRig2D compile(JSONObject rig){
        if(rig==null)throw invalid("A rig2d object is required",null);
        return compile(rig,(float)number(rig,"sourceAspect",1,.001,1000));
    }
    public static AnimationRig2D compile(JSONObject rig,float sourceAspect){
        try{return new AnimationRig2D(rig,sourceAspect);}
        catch(IllegalArgumentException error){throw error;}
        catch(Exception error){throw invalid("2D rig metadata is invalid: "+error.getMessage(),error);}
    }
    public static AnimationRig2D compileForClip(ProjectStore.Clip clip,float sourceAspect){
        if(clip==null||clip.effects==null||!(clip.effects.opt("rig2d") instanceof JSONObject))
            throw invalid("This clip has no articulated 2D rig",null);
        AnimationRig2D result=compile((JSONObject)clip.effects.opt("rig2d"),sourceAspect);
        result.clipDurationMs=Math.max(1L,clip.effects.optLong("animationDurationMs",clip.outputDurationMs()));
        result.clipOffsetMs=clip.effects.optLong("animationOffsetMs",0L);return result;
    }
    public boolean isEnabled(){return enabled;}
    public int boneCount(){return bones.length;}
    public int vertexCount(){return vertices.length;}
    public int triangleCount(){return triangles.length/3;}
    public int keyframeCount(){return keyCount;}
    public float sourceAspect(){return aspect;}
    public JSONObject definition(){try{return new JSONObject(definition.toString());}catch(Exception error){throw invalid("Rig copy failed",error);}}
    public Frame sampleClip(long outputLocalMs){
        return sample(clipAuthoredTimeMs(outputLocalMs));
    }
    /** Exact held authored time used by all clip samplers, including the rig's bounded time domain. */
    public long clipAuthoredTimeMs(long outputLocalMs){
        return boundedTime(AnimationClock.authoredTimeUs(AnimationClock.microseconds(outputLocalMs),
                AnimationClock.microseconds(clipOffsetMs),AnimationClock.microseconds(clipDurationMs))/1000L);
    }

    /** Authored FK channels before IK, for key defaults; this never bakes the solved pose. */
    public JSONObject authoredPoseClip(long outputLocalMs){return authoredPose(clipAuthoredTimeMs(outputLocalMs));}
    public JSONObject authoredPose(long authoredTimeMs){
        double[][] values=authoredValues(authoredTimeMs);JSONObject result=new JSONObject();
        try{for(int index=0;index<bones.length;index++)result.put(bones[index].id,channels(values[index]));}
        catch(Exception error){throw invalid("Authored FK channels cannot be described",error);}
        return result;
    }

    /** Raw curve value and exact influence of one fixed-time key, before channel clamping. */
    public static final class ChannelSample {
        public final double rawValue,keyInfluence;
        private ChannelSample(double rawValue,double keyInfluence){this.rawValue=rawValue;this.keyInfluence=keyInfluence;}
    }

    /** Cached curve sampling for graph controls, without geometry or JSON allocation. */
    public double authoredChannel(String boneId,String channel,long authoredTimeMs){
        return authoredBoneValue(bone(boneId),fkChannel(channel),boundedTime(authoredTimeMs));
    }
    public double authoredIkChannel(String ikId,String channel,long authoredTimeMs){
        return authoredIkValue(ikTarget(ikId),ikChannel(channel),boundedTime(authoredTimeMs));
    }
    public double authoredChannelClip(String boneId,String channel,long outputLocalMs){return authoredChannel(boneId,channel,clipAuthoredTimeMs(outputLocalMs));}
    public double authoredIkChannelClip(String ikId,String channel,long outputLocalMs){return authoredIkChannel(ikId,channel,clipAuthoredTimeMs(outputLocalMs));}
    /** Detached [minimum, maximum] for one supported scalar graph channel. */
    public static double[] channelBounds(String kind,String channel){
        if("bone".equals(kind)){int index=fkChannel(channel);return new double[]{fkFloor(index),fkCeiling(index)};}
        if("ik".equals(kind)){int index=ikChannel(channel);return new double[]{ikFloor(index),ikCeiling(index)};}
        throw invalid("Unknown authored channel kind: "+kind,null);
    }
    /** Value-only drafts keep key time/easing fixed; clamp rawValue + keyInfluence * delta. */
    public ChannelSample authoredChannelSample(String boneId,String channel,long authoredTimeMs,long keyAtMs){
        return curves[bone(boneId)][fkChannel(channel)].sample(boundedTime(authoredTimeMs),keyAtMs);
    }
    public ChannelSample authoredIkChannelSample(String ikId,String channel,long authoredTimeMs,long keyAtMs){
        return ikTarget(ikId).curves[ikChannel(channel)].sample(boundedTime(authoredTimeMs),keyAtMs);
    }
    private static int fkChannel(String channel){
        for(int index=0;index<CHANNELS.length;index++)if(CHANNELS[index].equals(channel))return index;
        throw invalid("Unknown authored FK channel: "+channel,null);
    }
    private static int ikChannel(String channel){
        int index="targetX".equals(channel)?0:"targetY".equals(channel)?1:"mix".equals(channel)?2:-1;
        if(index<0)throw invalid("Unknown authored IK channel: "+channel,null);return index;
    }
    private Ik ikTarget(String id){
        for(Ik target:ik)if(target.id.equals(id))return target;
        throw invalid("Unknown IK chain: "+id,null);
    }

    private AnimationRig2D(JSONObject source,float sourceAspect)throws Exception{
        if(!Float.isFinite(sourceAspect)||sourceAspect<.001f||sourceAspect>1000f)throw invalid("Source image aspect is invalid",null);
        String encoded=source.toString();
        if(encoded.length()>MAX_JSON_BYTES||encoded.getBytes(StandardCharsets.UTF_8).length>MAX_JSON_BYTES)
            throw invalid("2D rig metadata exceeds 256 KiB",null);
        definition=new JSONObject(encoded);aspect=sourceAspect;
        fields(definition,"version","enabled","sourceAspect","bones","mesh","pose","keyframes","poses","ik");
        if(integer(definition,"version",-1,1,1)!=1)throw invalid("Only rig2d version 1 is supported",null);
        enabled=bool(definition,"enabled",true);number(definition,"sourceAspect",sourceAspect,.001,1000);
        JSONArray rawBones=array(definition,"bones",true);
        if(rawBones.length()<1||rawBones.length()>MAX_BONES)throw invalid("A rig requires 1 to 24 bones",null);
        bones=new Bone[rawBones.length()];boneById=new HashMap<>();
        for(int index=0;index<bones.length;index++){
            JSONObject raw=object(rawBones,index);fields(raw,"id","name","parentId","x","y","endX","endY");
            Bone bone=new Bone();bone.id=identity(raw,"id",null);bone.name=label(raw,"name",bone.id);bone.parentId=identity(raw,"parentId","");
            if(boneById.put(bone.id,index)!=null)throw invalid("Bone IDs must be unique",null);
            bone.x=number(raw,"x",Double.NaN,0,1);bone.y=number(raw,"y",Double.NaN,0,1);
            bone.endX=number(raw,"endX",Double.NaN,0,1);bone.endY=number(raw,"endY",Double.NaN,0,1);
            double dx=(bone.endX-bone.x)*aspect,dy=bone.endY-bone.y;bone.length=Math.hypot(dx,dy);
            if(bone.length<.00001)throw invalid("Bone endpoints must differ",null);
            bone.bind=Affine.pose(bone.x*aspect,bone.y,Math.atan2(dy,dx),1,1);bone.inverse=bone.bind.inverse();bones[index]=bone;
        }
        for(Bone bone:bones)if(!bone.parentId.isEmpty()){
            Integer parent=boneById.get(bone.parentId);if(parent==null)throw invalid("Bone parent does not exist",null);bone.parent=parent;
        }
        order=topology();
        for(int index:order){Bone bone=bones[index];bone.local=bone.parent<0?bone.bind:bones[bone.parent].inverse.multiply(bone.bind);}
        pose=parsePose(optionalObject(definition,"pose"));
        curves=new Curve[bones.length][5];
        for(int index=0;index<bones.length;index++)for(int channel=0;channel<5;channel++)curves[index][channel]=new Curve(pose[index][channel]);
        int counted=0;JSONArray keys=array(definition,"keyframes",false);Set<String> seen=new HashSet<>();
        for(int index=0;index<keys.length();index++){
            JSONObject key=object(keys,index);fields(key,"boneId","atMs","rotation","x","y","scaleX","scaleY","ease","bezier");
            int bone=bone(identity(key,"boneId",null));long at=integer(key,"atMs",-1,0,MAX_TIME_MS);
            if(!seen.add(bone+":"+at))throw invalid("A bone cannot have duplicate keyframe times",null);
            Ease ease=new Ease(key);int values=0;
            for(int channel=0;channel<5;channel++)if(key.has(CHANNELS[channel])){
                curves[bone][channel].keys.add(new Key(at,channel(key,channel),ease));values++;
            }
            if(values==0)throw invalid("A bone keyframe requires a transform channel",null);
            if(++counted>MAX_KEYS)throw invalid("2D rig exceeds 2048 keyframes",null);
        }
        for(Curve[] bone:curves)for(Curve curve:bone)curve.sort();
        JSONArray library=array(definition,"poses",false);if(library.length()>64)throw invalid("Pose library exceeds 64 poses",null);
        Set<String> poseIds=new HashSet<>();
        for(int index=0;index<library.length();index++){
            JSONObject saved=object(library,index);fields(saved,"id","name","pose");
            if(!poseIds.add(identity(saved,"id",null)))throw invalid("Pose IDs must be unique",null);
            label(saved,"name","Pose");parsePose(requiredObject(saved,"pose"));
        }
        JSONArray constraints=array(definition,"ik",false);if(constraints.length()>12)throw invalid("A rig supports at most 12 two-link IK chains",null);
        ik=new Ik[constraints.length()];Set<Integer> controlled=new HashSet<>();Set<String> ikIds=new HashSet<>();
        for(int index=0;index<ik.length;index++){
            JSONObject raw=object(constraints,index);fields(raw,"id","rootBoneId","childBoneId","targetX","targetY","bend","mix","keyframes");
            Ik target=new Ik();target.id=identity(raw,"id",null);if(!ikIds.add(target.id))throw invalid("IK IDs must be unique",null);
            target.root=bone(identity(raw,"rootBoneId",null));target.child=bone(identity(raw,"childBoneId",null));
            if(target.root==target.child||bones[target.child].parent!=target.root)throw invalid("Two-link IK requires an immediate parent and child bone",null);
            if(!controlled.add(target.root)||!controlled.add(target.child))throw invalid("Two-link IK chains cannot share controlled bones",null);
            Bone root=bones[target.root],child=bones[target.child];
            if(Math.hypot((root.endX-child.x)*aspect,root.endY-child.y)>.0001)
                throw invalid("IK child head must meet its parent's bind tail",null);
            target.x=number(raw,"targetX",Double.NaN,-2,3);target.y=number(raw,"targetY",Double.NaN,-2,3);
            target.bend=(int)integer(raw,"bend",1,-1,1);if(target.bend==0)throw invalid("IK bend must be -1 or 1",null);
            target.mix=number(raw,"mix",1,0,1);target.curves[0]=new Curve(target.x);target.curves[1]=new Curve(target.y);target.curves[2]=new Curve(target.mix);
            JSONArray ikKeys=array(raw,"keyframes",false);Set<Long> times=new HashSet<>();
            for(int keyIndex=0;keyIndex<ikKeys.length();keyIndex++){
                JSONObject key=object(ikKeys,keyIndex);fields(key,"atMs","targetX","targetY","mix","ease","bezier");
                long at=integer(key,"atMs",-1,0,MAX_TIME_MS);if(!times.add(at))throw invalid("An IK chain cannot have duplicate keyframe times",null);
                Ease ease=new Ease(key);int values=0;
                for(int channel=0;channel<3;channel++){
                    String name=channel==0?"targetX":channel==1?"targetY":"mix";
                    if(key.has(name)){target.curves[channel].keys.add(new Key(at,number(key,name,Double.NaN,channel==2?0:-2,channel==2?1:3),ease));values++;}
                }
                if(values==0)throw invalid("An IK keyframe requires a target or mix channel",null);
                if(++counted>MAX_KEYS)throw invalid("2D rig exceeds 2048 total bone and IK keyframes",null);
            }
            for(Curve curve:target.curves)curve.sort();ik[index]=target;
            if(!constant(target.curves[2],0)){
                for(int current=target.child;current>=0;current=bones[current].parent){
                    requireConstant(curves[current][3],1,"IK chains and ancestors must use unit scale");
                    requireConstant(curves[current][4],1,"IK chains and ancestors must use unit scale");
                }
                requireConstant(curves[target.child][1],0,"IK child translation must stay zero");
                requireConstant(curves[target.child][2],0,"IK child translation must stay zero");
            }
        }
        Arrays.sort(ik,Comparator.comparingInt(target->rank(target.root)));keyCount=counted;
        JSONObject mesh=requiredObject(definition,"mesh");fields(mesh,"vertices","triangles");JSONArray rawVertices=array(mesh,"vertices",true);
        if(rawVertices.length()<3||rawVertices.length()>MAX_VERTICES)throw invalid("A rig mesh requires 3 to 512 vertices",null);
        vertices=new Vertex[rawVertices.length()];
        for(int index=0;index<vertices.length;index++){
            JSONObject raw=object(rawVertices,index);fields(raw,"u","v","influences");Vertex vertex=new Vertex();
            vertex.u=number(raw,"u",Double.NaN,0,1);vertex.v=number(raw,"v",Double.NaN,0,1);
            JSONArray influences=array(raw,"influences",true);if(influences.length()<1||influences.length()>4)throw invalid("Each vertex requires 1 to 4 bone influences",null);
            vertex.bones=new int[influences.length()];vertex.weights=new double[influences.length()];double sum=0;Set<Integer> ids=new HashSet<>();
            for(int influence=0;influence<influences.length();influence++){
                JSONObject value=object(influences,influence);fields(value,"boneId","weight");int id=bone(identity(value,"boneId",null));
                if(!ids.add(id))throw invalid("Vertex influences cannot repeat a bone",null);
                double weight=number(value,"weight",Double.NaN,Double.MIN_NORMAL,1);vertex.bones[influence]=id;vertex.weights[influence]=weight;sum+=weight;
            }
            if(Math.abs(sum-1)>.001)throw invalid("Vertex bone weights must sum to one",null);
            for(int influence=0;influence<vertex.weights.length;influence++)vertex.weights[influence]/=sum;vertices[index]=vertex;
        }
        JSONArray rawTriangles=array(mesh,"triangles",true);if(rawTriangles.length()<1||rawTriangles.length()>MAX_TRIANGLES)throw invalid("A rig mesh requires 1 to 1024 triangles",null);
        triangles=new int[rawTriangles.length()*3];
        for(int index=0;index<rawTriangles.length();index++){
            Object raw=rawTriangles.get(index);if(!(raw instanceof JSONArray)||((JSONArray)raw).length()!=3)throw invalid("Each mesh triangle requires three vertex indices",null);
            JSONArray triangle=(JSONArray)raw;
            for(int corner=0;corner<3;corner++)triangles[index*3+corner]=(int)exact(triangle.get(corner),0,vertices.length-1,"Triangle vertex index");
            Vertex a=vertices[triangles[index*3]],b=vertices[triangles[index*3+1]],c=vertices[triangles[index*3+2]];
            if(Math.abs((b.u-a.u)*(c.v-a.v)-(b.v-a.v)*(c.u-a.u))<.00000001)throw invalid("Mesh triangles cannot have zero bind area",null);
        }
        requireBoundedDeformation();
    }

    public Frame sample(long authoredTimeMs){
        long time=boundedTime(authoredTimeMs);double[][] values=authoredValues(time);
        Affine[] current=matrices(values);boolean[] clamped=new boolean[ik.length];float[] targets=new float[ik.length*2],mixes=new float[ik.length];String[] ikIds=new String[ik.length];
        for(int index=0;index<ik.length;index++){
            Ik target=ik[index];ikIds[index]=target.id;double tx=authoredIkValue(target,0,time),ty=authoredIkValue(target,1,time),mix=authoredIkValue(target,2,time);
            targets[index*2]=(float)tx;targets[index*2+1]=(float)ty;mixes[index]=(float)mix;if(mix<=0)continue;
            Bone root=bones[target.root],child=bones[target.child];Affine parent=root.parent<0?Affine.identity():current[root.parent];Affine inverse=parent.inverse();
            double[] head=inverse.point(current[target.root].tx,current[target.root].ty),goal=inverse.point(tx*aspect,ty);
            double dx=goal[0]-head[0],dy=goal[1]-head[1],distance=Math.hypot(dx,dy);
            double min=Math.abs(root.length-child.length),max=root.length+child.length,bounded=clamp(distance,min,max);
            clamped[index]=Math.abs(distance-bounded)>.00001;
            double cosine=clamp((bounded*bounded-root.length*root.length-child.length*child.length)/(2*root.length*child.length),-1,1);
            double elbow=target.bend*Math.acos(cosine);
            double shoulder=Math.atan2(dy,dx)-Math.atan2(child.length*Math.sin(elbow),root.length+child.length*Math.cos(elbow));
            double rootAngle=(shoulder-Math.atan2(root.local.b,root.local.a))*180/Math.PI;
            double childAngle=(elbow-Math.atan2(child.local.b,child.local.a))*180/Math.PI;
            values[target.root][0]+=shortest(rootAngle-values[target.root][0])*mix;
            values[target.child][0]+=shortest(childAngle-values[target.child][0])*mix;
            values[target.root][0]=boundedRotation(values[target.root][0]);
            values[target.child][0]=boundedRotation(values[target.child][0]);
            current=matrices(values);
        }
        Affine[] skin=new Affine[bones.length];float[] joints=new float[bones.length*4];String[] ids=new String[bones.length];
        for(int index=0;index<bones.length;index++){
            Bone bone=bones[index];skin[index]=current[index].multiply(bone.inverse);ids[index]=bone.id;
            double[] tail=current[index].point(bone.length,0);joints[index*4]=finite(current[index].tx/aspect);joints[index*4+1]=finite(current[index].ty);
            joints[index*4+2]=finite(tail[0]/aspect);joints[index*4+3]=finite(tail[1]);
        }
        float[] positions=new float[vertices.length*2],uv=new float[vertices.length*2];
        for(int index=0;index<vertices.length;index++){
            Vertex vertex=vertices[index];double x=0,y=0;
            for(int influence=0;influence<vertex.bones.length;influence++){
                Affine transform=skin[vertex.bones[influence]];double sourceX=vertex.u*aspect,weight=vertex.weights[influence];
                x+=(transform.a*sourceX+transform.c*vertex.v+transform.tx)*weight;
                y+=(transform.b*sourceX+transform.d*vertex.v+transform.ty)*weight;
            }
            positions[index*2]=finite(x/aspect);positions[index*2+1]=finite(y);uv[index*2]=(float)vertex.u;uv[index*2+1]=(float)vertex.v;
        }
        return new Frame(positions,uv,triangles.clone(),joints,ids,clamped,targets,mixes,ikIds,time,values);
    }

    private double[][] authoredValues(long authoredTimeMs){
        long time=boundedTime(authoredTimeMs);double[][] values=new double[bones.length][5];
        for(int index=0;index<bones.length;index++)for(int channel=0;channel<5;channel++){
            values[index][channel]=authoredBoneValue(index,channel,time);
        }
        return values;
    }
    private static long boundedTime(long authoredTimeMs){return Math.max(0,Math.min(MAX_TIME_MS,authoredTimeMs));}
    private double authoredBoneValue(int bone,int channel,long time){
        return clamp(curves[bone][channel].at(time),fkFloor(channel),fkCeiling(channel));
    }
    private static double authoredIkValue(Ik target,int channel,long time){return clamp(target.curves[channel].at(time),ikFloor(channel),ikCeiling(channel));}
    private static double fkFloor(int channel){return channel==0?-36000:channel>=3?.05:-2;}
    private static double fkCeiling(int channel){return channel==0?36000:channel>=3?20:2;}
    private static double ikFloor(int channel){return channel==2?0:-2;}
    private static double ikCeiling(int channel){return channel==2?1:3;}

    public JSONObject describeClip(long outputLocalMs){return describe(sampleClip(outputLocalMs));}
    public JSONObject describe(long authoredTimeMs){return describe(sample(authoredTimeMs));}
    private JSONObject describe(Frame frame){
        try{
            JSONObject result=new JSONObject().put("present",true).put("version",1).put("enabled",enabled).put("sourceAspect",aspect)
                    .put("authoredTimeMs",frame.authoredTimeMs).put("boneCount",bones.length).put("vertexCount",vertices.length)
                    .put("triangleCount",triangles.length/3).put("keyframeCount",keyCount).put("wholeImageMesh",true).put("segmentation",false);
            JSONArray joints=new JSONArray();
            for(int index=0;index<bones.length;index++){
                Bone bone=bones[index];JSONObject joint=new JSONObject().put("id",bone.id).put("name",bone.name).put("parentId",bone.parentId)
                        .put("x",bone.x).put("y",bone.y).put("endX",bone.endX).put("endY",bone.endY)
                        .put("headX",frame.bonePositions[index*4]).put("headY",frame.bonePositions[index*4+1])
                        .put("tailX",frame.bonePositions[index*4+2]).put("tailY",frame.bonePositions[index*4+3])
                        .put("rotation",frame.pose[index][0]).put("translateX",frame.pose[index][1]).put("translateY",frame.pose[index][2])
                        .put("scaleX",frame.pose[index][3]).put("scaleY",frame.pose[index][4]);joints.put(joint);
            }
            JSONArray targets=new JSONArray();for(int index=0;index<ik.length;index++){
                Ik target=ik[index];targets.put(new JSONObject().put("id",target.id).put("rootBoneId",bones[target.root].id).put("childBoneId",bones[target.child].id)
                        .put("targetX",frame.ikTargets[index*2]).put("targetY",frame.ikTargets[index*2+1]).put("bend",target.bend)
                        .put("mix",frame.ikMix[index]).put("clamped",frame.ikClamped[index]));
            }
            JSONArray saved=new JSONArray(),library=array(definition,"poses",false);
            for(int index=0;index<library.length();index++){JSONObject item=object(library,index);saved.put(new JSONObject().put("id",item.getString("id")).put("name",item.optString("name","Pose")));}
            JSONArray diagnostics=new JSONArray();if(ik.length>0)diagnostics.put("Two-link IK uses connected unit-scale chains; unreachable targets clamp to bone reach.");
            return result.put("bones",joints).put("ik",targets).put("poses",saved).put("diagnostics",diagnostics);
        }catch(Exception error){throw invalid("Rig diagnostics cannot be described",error);}
    }

    private Affine[] matrices(double[][] values){
        Affine[] result=new Affine[bones.length];
        for(int index:order){Bone bone=bones[index];double[] value=values[index];Affine delta=Affine.pose(value[1],value[2],value[0]*Math.PI/180,value[3],value[4]);
            Affine local=bone.local.multiply(delta);result[index]=bone.parent<0?local:result[bone.parent].multiply(local);}
        return result;
    }
    private int[] topology(){
        int[] result=new int[bones.length];boolean[] done=new boolean[bones.length];int count=0;
        while(count<bones.length){int old=count;for(int index=0;index<bones.length;index++)if(!done[index]&&(bones[index].parent<0||done[bones[index].parent])){done[index]=true;result[count++]=index;}
            if(old==count)throw invalid("Bone hierarchy contains a cycle",null);}
        return result;
    }
    private int rank(int index){for(int rank=0;rank<order.length;rank++)if(order[rank]==index)return rank;return order.length;}
    private int bone(String id){Integer index=boneById.get(id);if(index==null)throw invalid("Unknown bone: "+id,null);return index;}
    private double[][] parsePose(JSONObject raw)throws Exception{
        double[][] result=new double[bones.length][5];for(int index=0;index<bones.length;index++)result[index]=REST.clone();if(raw==null)return result;
        Iterator<String> names=raw.keys();while(names.hasNext()){String id=names.next();int bone=bone(id);JSONObject value=requiredObject(raw,id);fields(value,CHANNELS);
            for(int channel=0;channel<5;channel++)if(value.has(CHANNELS[channel]))result[bone][channel]=channel(value,channel);}
        return result;
    }
    private static double channel(JSONObject value,int channel){return number(value,CHANNELS[channel],REST[channel],channel==0?-36000:channel>=3?.05:-2,channel==0?36000:channel>=3?20:2);}
    private static JSONObject channels(double[] values)throws Exception{JSONObject result=new JSONObject();for(int index=0;index<CHANNELS.length;index++)result.put(CHANNELS[index],values[index]);return result;}
    private static boolean constant(Curve curve,double value){if(Math.abs(curve.base-value)>1e-12)return false;for(Key key:curve.keys)if(Math.abs(key.value-value)>1e-12)return false;return true;}
    private static void requireConstant(Curve curve,double value,String message){if(!constant(curve,value))throw invalid(message,null);}

    private static final class Key { final long at;final double value;final Ease ease;Key(long at,double value,Ease ease){this.at=at;this.value=value;this.ease=ease;} }
    private static final class Curve {
        final double base;final List<Key> keys=new ArrayList<>();Curve(double base){this.base=base;}void sort(){keys.sort(Comparator.comparingLong(key->key.at));}
        double at(long time){
            if(keys.isEmpty())return base;Key first=keys.get(0);if(time<first.at)return first.at==0?first.value:base+(first.value-base)*time/(double)first.at;
            int leftIndex=indexAtOrBefore(time);Key left=keys.get(leftIndex);if(leftIndex==keys.size()-1)return left.value;Key right=keys.get(leftIndex+1);
            double fraction=(time-left.at)/(double)(right.at-left.at);return left.value+(right.value-left.value)*left.ease.at(fraction);
        }
        private int indexAtOrBefore(long time){
            int low=0,high=keys.size();while(low<high){int mid=low+(high-low)/2;if(keys.get(mid).at<=time)low=mid+1;else high=mid;}
            return low-1;
        }
        ChannelSample sample(long time,long keyAtMs){
            if(keyAtMs==-1)return new ChannelSample(at(time),0);
            int selected=indexAtOrBefore(keyAtMs);
            if(keyAtMs<0||selected<0||keys.get(selected).at!=keyAtMs)
                throw invalid("The selected authored channel has no key at "+keyAtMs+" ms",null);
            double influence=0;Key first=keys.get(0);
            if(time<first.at)influence=selected==0?time/(double)first.at:0;
            else{
                int leftIndex=indexAtOrBefore(time);
                if(leftIndex==keys.size()-1)influence=selected==leftIndex?1:0;
                else{
                    Key left=keys.get(leftIndex),right=keys.get(leftIndex+1);
                    double eased=left.ease.at((time-left.at)/(double)(right.at-left.at));
                    influence=selected==leftIndex?1-eased:selected==leftIndex+1?eased:0;
                }
            }
            return new ChannelSample(at(time),influence);
        }
        double[] extent(double floor,double ceiling){
            double min=base,max=base;
            for(int index=0;index<keys.size();index++){
                Key left=keys.get(index);min=Math.min(min,left.value);max=Math.max(max,left.value);
                if(index+1<keys.size()){
                    double delta=keys.get(index+1).value-left.value;
                    double first=left.value+delta*left.ease.minimum,last=left.value+delta*left.ease.maximum;
                    min=Math.min(min,Math.min(first,last));max=Math.max(max,Math.max(first,last));
                }
            }
            return new double[]{clamp(min,floor,ceiling),clamp(max,floor,ceiling)};
        }
    }
    private static final class Ease {
        final String name;final CubicBezierEasing cubic;final double minimum,maximum;
        Ease(JSONObject key)throws Exception{
            name=label(key,"ease","linear");if(!Arrays.asList("linear","ease_in","ease_out","ease_in_out","step","hold","cubic_bezier").contains(name))throw invalid("Unsupported rig easing: "+name,null);
            if("cubic_bezier".equals(name)){
                JSONArray controls=array(key,"bezier",true);cubic=CubicBezierEasing.fromJson(controls);
                minimum=Math.min(0,Math.min(controls.getDouble(1),controls.getDouble(3)));
                maximum=Math.max(1,Math.max(controls.getDouble(1),controls.getDouble(3)));
            }else{if(key.has("bezier"))throw invalid("Bezier controls require cubic_bezier easing",null);cubic=null;minimum=0;maximum=1;}
        }
        double at(double value){
            if(cubic!=null)return cubic.at(clamp(value,0,1));return namedEasing(value,name);
        }
    }

    /** Named FK/IK easing for editor curve drawing; custom curves use CubicBezierEasing. */
    public static double namedEasing(double progress,String name){
        double t=clamp(progress,0,1);
        if("step".equals(name)||"hold".equals(name))return t>=1?1:0;
        if("ease_in".equals(name))return t*t;if("ease_out".equals(name))return 1-(1-t)*(1-t);
        if("ease_in_out".equals(name))return t*t*(3-2*t);return t;
    }

    /** Analytic upper bounds prevent valid JSON from committing a pose that
     * would overflow the bounded sampler later. Rotations/IK only change vector
     * directions; curve hulls bound translations and hierarchical scales. */
    private void requireBoundedDeformation(){
        double[] heads=new double[bones.length],scales=new double[bones.length];
        double metricLimit=10000*Math.min(1,aspect);
        for(int index:order){
            Bone bone=bones[index];double[] x=curves[index][1].extent(-2,2),y=curves[index][2].extent(-2,2);
            double translate=Math.hypot(Math.max(Math.abs(x[0]),Math.abs(x[1])),Math.max(Math.abs(y[0]),Math.abs(y[1])));
            double sx=curves[index][3].extent(.05,20)[1],sy=curves[index][4].extent(.05,20)[1];
            double parentScale=bone.parent<0?1:scales[bone.parent];
            heads[index]=(bone.parent<0?0:heads[bone.parent])+parentScale*(Math.hypot(bone.local.tx,bone.local.ty)+translate);
            scales[index]=parentScale*Math.max(sx,sy);
            if(!Double.isFinite(heads[index])||!Double.isFinite(scales[index])||heads[index]+scales[index]*bone.length>metricLimit)
                throw invalid("Hierarchical rig transform may exceed the supported canvas deformation bounds; reduce scale or translation",null);
        }
        for(Vertex vertex:vertices){
            double bound=0;
            for(int influence=0;influence<vertex.bones.length;influence++){
                int index=vertex.bones[influence];Bone bone=bones[index];
                double radius=Math.hypot((vertex.u-bone.x)*aspect,vertex.v-bone.y);
                bound+=(heads[index]+scales[index]*radius)*vertex.weights[influence];
            }
            if(!Double.isFinite(bound)||bound>metricLimit)
                throw invalid("Weighted rig transform may exceed the supported canvas deformation bounds; reduce scale or translation",null);
        }
    }
    private static final class Affine {
        final double a,b,c,d,tx,ty;Affine(double a,double b,double c,double d,double x,double y){this.a=a;this.b=b;this.c=c;this.d=d;tx=x;ty=y;}
        static Affine identity(){return new Affine(1,0,0,1,0,0);}
        static Affine pose(double x,double y,double angle,double sx,double sy){double cos=Math.cos(angle),sin=Math.sin(angle);return new Affine(cos*sx,sin*sx,-sin*sy,cos*sy,x,y);}
        Affine multiply(Affine v){return new Affine(a*v.a+c*v.b,b*v.a+d*v.b,a*v.c+c*v.d,b*v.c+d*v.d,a*v.tx+c*v.ty+tx,b*v.tx+d*v.ty+ty);}
        Affine inverse(){double determinant=a*d-b*c;if(!Double.isFinite(determinant)||Math.abs(determinant)<1e-12)throw invalid("Bone transform is singular",null);
            double na=d/determinant,nb=-b/determinant,nc=-c/determinant,nd=a/determinant;return new Affine(na,nb,nc,nd,-na*tx-nc*ty,-nb*tx-nd*ty);}
        double[] point(double x,double y){return new double[]{a*x+c*y+tx,b*x+d*y+ty};}
    }

    public static JSONObject retime(JSONObject rig,double factor){
        validate(rig);if(!Double.isFinite(factor)||factor<=0)throw invalid("Rig retime factor must be positive",null);
        try{JSONObject result=new JSONObject(rig.toString());result.put("keyframes",retimeKeys(array(result,"keyframes",false),factor,true));
            JSONArray ik=array(result,"ik",false);for(int index=0;index<ik.length();index++){JSONObject target=object(ik,index);target.put("keyframes",retimeKeys(array(target,"keyframes",false),factor,false));}
            validate(result);return result;
        }catch(IllegalArgumentException error){throw error;}catch(Exception error){throw invalid("Rig keys cannot be retimed",error);}
    }
    private static JSONArray retimeKeys(JSONArray keys,double factor,boolean bone)throws Exception{
        Map<String,JSONObject> merged=new java.util.LinkedHashMap<>();List<JSONObject> ordered=new ArrayList<>();for(int index=0;index<keys.length();index++)ordered.add(object(keys,index));
        ordered.sort(Comparator.comparingLong(key->key.optLong("atMs",0)));
        for(JSONObject key:ordered){double time=key.getLong("atMs")*factor;if(!Double.isFinite(time)||time>MAX_TIME_MS)throw invalid("Retimed rig key exceeds seven days",null);
            long at=Math.round(time);String identity=(bone?key.getString("boneId"):"")+":"+at;JSONObject next=merged.get(identity);if(next==null){next=new JSONObject();merged.put(identity,next);}
            Iterator<String> names=key.keys();while(names.hasNext()){String name=names.next();next.put(name,key.get(name));}next.put("atMs",at);
            // An omitted ease means linear on the original key. When two
            // authored times collapse, preserve the later key's easing too.
            next.put("ease",key.optString("ease","linear"));
            if(!"cubic_bezier".equals(next.optString("ease","linear")))next.remove("bezier");
        }
        JSONArray result=new JSONArray();for(JSONObject key:merged.values())result.put(key);return result;
    }

    static void fields(JSONObject value,String... allowed){Set<String> names=new HashSet<>(Arrays.asList(allowed));Iterator<String> keys=value.keys();while(keys.hasNext()){String key=keys.next();if(!names.contains(key))throw invalid("Unknown rig field: "+key,null);}}
    static JSONObject requiredObject(JSONObject owner,String name){Object value=owner.opt(name);if(!(value instanceof JSONObject))throw invalid(name+" must be an object",null);return(JSONObject)value;}
    private static JSONObject optionalObject(JSONObject owner,String name){return owner.has(name)?requiredObject(owner,name):null;}
    static JSONObject object(JSONArray array,int index)throws Exception{Object value=array.get(index);if(!(value instanceof JSONObject))throw invalid("Rig array entries must be objects",null);return(JSONObject)value;}
    static JSONArray array(JSONObject owner,String name,boolean required){if(!owner.has(name)){if(required)throw invalid(name+" is required",null);return new JSONArray();}Object value=owner.opt(name);if(!(value instanceof JSONArray))throw invalid(name+" must be an array",null);return(JSONArray)value;}
    static long integer(JSONObject owner,String name,long fallback,long min,long max){if(owner.has(name))return exact(owner.opt(name),min,max,name);if(fallback<min||fallback>max)throw invalid(name+" is required",null);return fallback;}
    static long exact(Object value,long min,long max,String name){if(!(value instanceof Number))throw invalid(name+" must be an integer",null);double number=((Number)value).doubleValue();long integer=((Number)value).longValue();if(!Double.isFinite(number)||number!=integer||integer<min||integer>max)throw invalid(name+" is outside its integer range",null);return integer;}
    static double number(JSONObject owner,String name,double fallback,double min,double max){Object value=owner.opt(name);double number=!owner.has(name)?fallback:value instanceof Number?((Number)value).doubleValue():Double.NaN;if(!Double.isFinite(number)||number<min||number>max)throw invalid(name+" is outside its numeric range",null);return number;}
    static boolean bool(JSONObject owner,String name,boolean fallback){if(!owner.has(name))return fallback;Object value=owner.opt(name);if(!(value instanceof Boolean))throw invalid(name+" must be boolean",null);return(Boolean)value;}
    static String identity(JSONObject owner,String name,String fallback){String value=label(owner,name,fallback);if(value==null||value.length()>64||(!value.isEmpty()&&!value.matches("[A-Za-z0-9_.:-]+")))throw invalid(name+" must be a stable 1 to 64 character identifier",null);if(value.isEmpty()&&!"".equals(fallback))throw invalid(name+" is required",null);return value;}
    static String label(JSONObject owner,String name,String fallback){Object raw=owner.opt(name);String value=!owner.has(name)?fallback:raw instanceof String?(String)raw:null;if(value==null||value.length()>128)throw invalid(name+" must be a string of at most 128 characters",null);for(int index=0;index<value.length();index++)if(Character.isISOControl(value.charAt(index)))throw invalid(name+" cannot contain control characters",null);return value;}
    private static double shortest(double angle){return angle-360*Math.floor((angle+180)/360);}
    private static double boundedRotation(double angle){if(angle>36000)return angle-360;if(angle<-36000)return angle+360;return angle;}
    private static float finite(double value){if(!Double.isFinite(value)||Math.abs(value)>10000)throw invalid("Bone pose exceeds the supported canvas deformation bounds",null);return(float)value;}
    private static double clamp(double value,double min,double max){return Math.max(min,Math.min(max,value));}
    private static IllegalArgumentException invalid(String message,Throwable cause){return new IllegalArgumentException(message,cause);}
}
