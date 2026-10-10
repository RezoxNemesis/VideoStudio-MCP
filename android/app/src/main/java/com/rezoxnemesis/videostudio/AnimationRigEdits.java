package com.rezoxnemesis.videostudio;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Human and MCP mutations share this validated, lock-checked native transaction path. */
public final class AnimationRigEdits {
    private AnimationRigEdits() { }

    /** Invoke inside ProjectStore.edit(projectId, expectedRevision, label, mutation). */
    public static void apply(ProjectStore.Project project,String operation,JSONObject args)throws Exception{
        applyInternal(project,operation,args);
    }

    /** One complete stroke in one transaction; the returned receipt contains exact changed vertex indices. */
    public static JSONObject paintWeights(ProjectStore.Project project,JSONObject args)throws Exception{
        return applyInternal(project,"paint_weights",args);
    }

    private static JSONObject applyInternal(ProjectStore.Project project,String operation,JSONObject args)throws Exception{
        if(project==null||args==null)throw new IllegalArgumentException("Rig edits require a project and typed settings");
        String clipId=args.getString("clipId");ProjectStore.Clip clip=project.clip(clipId);
        if(clip==null)throw new IllegalArgumentException("The selected rig clip no longer exists");
        ProjectLinkedEdits.requireUnlocked(project,Collections.singletonList(clip));
        if("clear_rig".equals(operation)){clip.effects.remove("rig2d");return new JSONObject();}
        ProjectStore.Asset asset=project.asset(clip.assetId);
        if(asset==null||asset.mime==null||!asset.mime.startsWith("image/")||asset.uri==null||asset.uri.isEmpty())
            throw new IllegalArgumentException("Articulated 2D rig authoring requires an explicitly imported or generated still image");
        float aspect=sourceAspect(asset);JSONObject rig;JSONObject receipt=null;
        if("create_rig".equals(operation)){
            if(clip.effects.has("rig2d")&&!AnimationRig2D.bool(args,"replace",false))
                throw new IllegalArgumentException("This clip already has a rig; edit it or explicitly replace it");
            JSONArray bones=args.has("bones")?copyArray(AnimationRig2D.array(args,"bones",true)):defaultBones();
            rig=new JSONObject().put("version",1).put("enabled",true).put("sourceAspect",aspect).put("bones",bones)
                    .put("pose",new JSONObject()).put("keyframes",new JSONArray()).put("poses",new JSONArray()).put("ik",new JSONArray());
            rig.put("mesh",gridMesh(bones,aspect,16,16));
        }else if("apply_rig".equals(operation)){
            rig=new JSONObject(AnimationRig2D.requiredObject(args,"rig").toString());
            // Reusable rigs retain UV bind coordinates; the target image's
            // physical aspect determines angles and inverse bind matrices.
            rig.put("sourceAspect",aspect);
        }else{
            if(!(clip.effects.opt("rig2d") instanceof JSONObject))throw new IllegalArgumentException("Create or apply a 2D rig first");
            rig=new JSONObject(((JSONObject)clip.effects.opt("rig2d")).toString());
            AnimationRig2D.compile(rig,aspect);
            switch(operation==null?"":operation){
                case "set_enabled":rig.put("enabled",AnimationRig2D.bool(args,"enabled",true));break;
                case "set_bone":setBone(rig,AnimationRig2D.requiredObject(args,"bone"),aspect,AnimationRig2D.bool(args,"autoWeights",true),AnimationRig2D.bool(args,"preserveConnections",false));break;
                case "remove_bone":removeBone(rig,args.getString("boneId"),aspect);break;
                case "auto_weights":autoWeights(rig,aspect);break;
                case "set_weights":setWeights(rig,args);break;
                case "paint_weights":receipt=paintRegion(rig,args,aspect);break;
                case "set_pose":mergePose(rig,AnimationRig2D.requiredObject(args,"pose"));break;
                case "set_keyframe":{
                    AnimationRig2D.fields(args,"clipId","keyframe","replaceKeyframe");
                    JSONObject key=new JSONObject(AnimationRig2D.requiredObject(args,"keyframe").toString());
                    authoredTime(clip,key,"atMs");putBoneKey(rig,key,AnimationRig2D.bool(args,"replaceKeyframe",false));break;
                }
                case "move_keyframe":{
                    AnimationRig2D.fields(args,"clipId","boneId","fromAtMs","toAtMs");
                    String boneId=AnimationRig2D.identity(args,"boneId",null);
                    long from=AnimationRig2D.integer(args,"fromAtMs",-1,0,AnimationRig2D.MAX_TIME_MS);
                    long to=authoredTime(clip,args,"toAtMs");
                    moveTimedKey(AnimationRig2D.array(rig,"keyframes",false),boneId,from,to);break;
                }
                case "remove_keyframe":rig.put("keyframes",withoutKey(AnimationRig2D.array(rig,"keyframes",false),args.getString("boneId"),AnimationRig2D.integer(args,"atMs",-1,0,AnimationRig2D.MAX_TIME_MS)));break;
                case "capture_pose":capturePose(rig,args,aspect);break;
                case "apply_pose":applyPose(rig,args,clip);break;
                case "remove_pose":rig.put("poses",withoutId(AnimationRig2D.array(rig,"poses",false),args.getString("poseId")));break;
                case "set_ik":putById(rig,"ik",AnimationRig2D.requiredObject(args,"ik"));break;
                case "remove_ik":rig.put("ik",withoutId(AnimationRig2D.array(rig,"ik",false),args.getString("ikId")));break;
                case "set_ik_keyframe":{
                    AnimationRig2D.fields(args,"clipId","ikId","keyframe","replaceKeyframe");
                    JSONObject key=new JSONObject(AnimationRig2D.requiredObject(args,"keyframe").toString());authoredTime(clip,key,"atMs");
                    JSONObject target=find(AnimationRig2D.array(rig,"ik",false),args.getString("ikId"));
                    if(target==null)throw new IllegalArgumentException("The selected IK chain no longer exists");
                    target.put("keyframes",putTimedKey(AnimationRig2D.array(target,"keyframes",false),key,null,AnimationRig2D.bool(args,"replaceKeyframe",false)));break;
                }
                case "move_ik_keyframe":{
                    AnimationRig2D.fields(args,"clipId","ikId","fromAtMs","toAtMs");
                    JSONObject target=find(AnimationRig2D.array(rig,"ik",false),AnimationRig2D.identity(args,"ikId",null));
                    if(target==null)throw new IllegalArgumentException("The selected IK chain no longer exists");
                    long from=AnimationRig2D.integer(args,"fromAtMs",-1,0,AnimationRig2D.MAX_TIME_MS);
                    long to=authoredTime(clip,args,"toAtMs");
                    moveTimedKey(AnimationRig2D.array(target,"keyframes",false),null,from,to);break;
                }
                case "remove_ik_keyframe":{
                    JSONObject target=find(AnimationRig2D.array(rig,"ik",false),args.getString("ikId"));if(target==null)throw new IllegalArgumentException("The selected IK chain no longer exists");
                    target.put("keyframes",withoutKey(AnimationRig2D.array(target,"keyframes",false),null,AnimationRig2D.integer(args,"atMs",-1,0,AnimationRig2D.MAX_TIME_MS)));break;
                }
                default:throw new IllegalArgumentException("Unknown 2D rig edit: "+operation);
            }
            rig.put("sourceAspect",aspect);
        }
        AnimationRig2D.compile(rig,aspect);
        if(AnimationRig2D.bool(rig,"enabled",true)
                && (clip.effects.optBoolean("titleOnly",false)||clip.effects.optJSONObject("proceduralScene")!=null))
            throw new IllegalArgumentException("Active 2D rigs require a textured image source; title-only and procedural overlays are drawn after the source mesh");
        // The staged JSON becomes visible only after complete validation. Store
        // then commits one revision and one undo item for this entire mutation.
        clip.effects.put("rig2d",rig);
        return receipt==null?new JSONObject():receipt;
    }

    /** Output-local position is converted through the same authored clock as the GPU. */
    public static JSONObject describe(ProjectStore.Project project,String clipId,long outputLocalMs)throws Exception{
        if(project==null)throw new IllegalArgumentException("A project is required");ProjectStore.Clip clip=project.clip(clipId);
        if(clip==null)throw new IllegalArgumentException("Selected clip no longer exists");
        if(!clip.effects.has("rig2d"))return new JSONObject().put("present",false).put("clipId",clip.id).put("nativeRevision",project.revision);
        ProjectStore.Asset asset=project.asset(clip.assetId);if(asset==null)throw new IllegalArgumentException("Rig source is missing");
        return AnimationRig2D.compileForClip(clip,sourceAspect(asset)).describeClip(outputLocalMs)
                .put("clipId",clip.id).put("nativeRevision",project.revision).put("outputLocalMs",Math.max(0,outputLocalMs));
    }

    public static float sourceAspect(ProjectStore.Asset asset){
        if(asset==null||asset.width<=0||asset.height<=0)return 1;
        boolean rotated=Math.floorMod(asset.rotation,180)==90;
        return rotated?asset.height/(float)asset.width:asset.width/(float)asset.height;
    }
    private static long authoredTime(ProjectStore.Clip clip,JSONObject args,String name){
        long time=AnimationRig2D.integer(args,name,-1,0,AnimationRig2D.MAX_TIME_MS);
        long duration=Math.max(1L,clip.effects.optLong("animationDurationMs",clip.outputDurationMs()));
        if(time>duration)throw new IllegalArgumentException("Rig keyframes must lie inside the retained authored animation window");return time;
    }

    private static JSONArray defaultBones()throws Exception{
        return new JSONArray().put(new JSONObject().put("id","body").put("name","Body").put("parentId","")
                .put("x",.5).put("y",.75).put("endX",.5).put("endY",.25));
    }
    private static JSONObject gridMesh(JSONArray bones,float aspect,int columns,int rows)throws Exception{
        if(columns<2||rows<2||columns*rows>AnimationRig2D.MAX_VERTICES)throw new IllegalArgumentException("Mesh grid exceeds its vertex bound");
        JSONArray vertices=new JSONArray(),triangles=new JSONArray();
        for(int row=0;row<rows;row++)for(int column=0;column<columns;column++){
            double u=column/(double)(columns-1),v=row/(double)(rows-1);
            vertices.put(new JSONObject().put("u",u).put("v",v).put("influences",weights(bones,u,v,aspect)));
        }
        for(int row=0;row<rows-1;row++)for(int column=0;column<columns-1;column++){
            int a=row*columns+column,b=a+1,c=a+columns,d=c+1;
            triangles.put(new JSONArray().put(a).put(b).put(c));triangles.put(new JSONArray().put(b).put(d).put(c));
        }
        return new JSONObject().put("vertices",vertices).put("triangles",triangles);
    }
    private static JSONArray weights(JSONArray bones,double u,double v,float aspect)throws Exception{
        if(bones.length()<1||bones.length()>AnimationRig2D.MAX_BONES)throw new IllegalArgumentException("Auto weights require 1 to 24 bones");
        List<Weight> nearest=new ArrayList<>();double scale=Math.max(1,aspect),soft=.025*scale;
        for(int index=0;index<bones.length();index++){
            JSONObject bone=AnimationRig2D.object(bones,index);String id=AnimationRig2D.identity(bone,"id",null);
            double x=AnimationRig2D.number(bone,"x",Double.NaN,0,1)*aspect,y=AnimationRig2D.number(bone,"y",Double.NaN,0,1);
            double ex=AnimationRig2D.number(bone,"endX",Double.NaN,0,1)*aspect,ey=AnimationRig2D.number(bone,"endY",Double.NaN,0,1);
            double dx=ex-x,dy=ey-y,lengthSquared=dx*dx+dy*dy;if(lengthSquared<.0000000001)throw new IllegalArgumentException("Bone endpoints must differ");
            double fraction=Math.max(0,Math.min(1,((u*aspect-x)*dx+(v-y)*dy)/lengthSquared));
            double distance=Math.hypot(u*aspect-(x+fraction*dx),v-(y+fraction*dy));nearest.add(new Weight(id,distance,1/(distance*distance+soft*soft)));
        }
        nearest.sort(Comparator.comparingDouble(value->value.distance));double sum=0;int count=Math.min(4,nearest.size());for(int index=0;index<count;index++)sum+=nearest.get(index).weight;
        JSONArray result=new JSONArray();for(int index=0;index<count;index++){Weight value=nearest.get(index);result.put(new JSONObject().put("boneId",value.id).put("weight",value.weight/sum));}return result;
    }
    private static final class Weight {final String id;final double distance,weight;Weight(String id,double distance,double weight){this.id=id;this.distance=distance;this.weight=weight;}}
    private static void autoWeights(JSONObject rig,float aspect)throws Exception{
        JSONArray bones=AnimationRig2D.array(rig,"bones",true),vertices=AnimationRig2D.array(AnimationRig2D.requiredObject(rig,"mesh"),"vertices",true);
        for(int index=0;index<vertices.length();index++){
            JSONObject vertex=AnimationRig2D.object(vertices,index);vertex.put("influences",weights(bones,AnimationRig2D.number(vertex,"u",Double.NaN,0,1),AnimationRig2D.number(vertex,"v",Double.NaN,0,1),aspect));
        }
    }
    private static void setBone(JSONObject rig,JSONObject edit,float aspect,boolean auto,boolean preserve)throws Exception{
        String id=AnimationRig2D.identity(edit,"id",null);JSONArray bones=AnimationRig2D.array(rig,"bones",true);JSONObject existing=find(bones,id);
        if(existing==null){if(bones.length()>=AnimationRig2D.MAX_BONES)throw new IllegalArgumentException("Bone capacity is full");bones.put(new JSONObject(edit.toString()));}
        else{
            JSONArray original=preserve?copyArray(bones):null;JSONObject previous=preserve?find(original,id):null;
            merge(existing,edit);
            if(preserve){
                preserveJoint(bones,original,id,false,previous,existing,aspect);
                preserveJoint(bones,original,id,true,previous,existing,aspect);
            }
        }
        if(auto)autoWeights(rig,aspect);
    }
    /** A connected joint consists of one original parent tail and its touching
     * child heads. Follow only those hierarchy edges, never nearby unrelated
     * artwork or the other endpoint of a bone. Both moved endpoints stage before
     * the rig is compiled, so an IK chain never passes through a broken state. */
    private static void preserveJoint(JSONArray bones,JSONArray original,String id,boolean tail,
                                      JSONObject previous,JSONObject changed,float aspect)throws Exception{
        String x=tail?"endX":"x",y=tail?"endY":"y";
        double oldX=previous.getDouble(x),oldY=previous.getDouble(y);
        double newX=AnimationRig2D.number(changed,x,Double.NaN,0,1),newY=AnimationRig2D.number(changed,y,Double.NaN,0,1);
        if(oldX==newX&&oldY==newY)return;
        int source=-1;for(int index=0;index<original.length();index++)if(id.equals(AnimationRig2D.object(original,index).getString("id"))){source=index;break;}
        boolean[] connected=new boolean[original.length()*2];connected[source*2+(tail?1:0)]=true;
        boolean added;
        do{
            added=false;
            for(int parent=0;parent<original.length();parent++)for(int child=0;child<original.length();child++){
                JSONObject parentBone=AnimationRig2D.object(original,parent),childBone=AnimationRig2D.object(original,child);
                if(!parentBone.getString("id").equals(childBone.optString("parentId","")))continue;
                if(Math.hypot((parentBone.getDouble("endX")-childBone.getDouble("x"))*aspect,parentBone.getDouble("endY")-childBone.getDouble("y"))>.0001)continue;
                int parentEnd=parent*2+1,childHead=child*2;
                if(connected[parentEnd]&&!connected[childHead]){connected[childHead]=true;added=true;}
                if(connected[childHead]&&!connected[parentEnd]){connected[parentEnd]=true;added=true;}
            }
        }while(added);
        for(int index=0;index<original.length();index++){
            JSONObject destination=find(bones,AnimationRig2D.object(original,index).getString("id"));
            if(connected[index*2])destination.put("x",newX).put("y",newY);
            if(connected[index*2+1])destination.put("endX",newX).put("endY",newY);
        }
    }
    private static void removeBone(JSONObject rig,String id,float aspect)throws Exception{
        JSONArray bones=AnimationRig2D.array(rig,"bones",true);if(find(bones,id)==null)throw new IllegalArgumentException("The selected bone no longer exists");
        if(bones.length()<=1)throw new IllegalArgumentException("A rig must retain at least one bone");
        for(int index=0;index<bones.length();index++)if(id.equals(AnimationRig2D.object(bones,index).optString("parentId")))
            throw new IllegalArgumentException("Reparent or remove the child bones before removing their parent");
        rig.put("bones",withoutId(bones,id));JSONArray keys=AnimationRig2D.array(rig,"keyframes",false),retained=new JSONArray();
        for(int index=0;index<keys.length();index++){JSONObject key=AnimationRig2D.object(keys,index);if(!id.equals(key.optString("boneId")))retained.put(key);}rig.put("keyframes",retained);
        JSONObject baseline=rig.optJSONObject("pose");if(baseline!=null)baseline.remove(id);
        JSONArray library=AnimationRig2D.array(rig,"poses",false);for(int index=0;index<library.length();index++)AnimationRig2D.requiredObject(AnimationRig2D.object(library,index),"pose").remove(id);
        JSONArray ik=AnimationRig2D.array(rig,"ik",false),targets=new JSONArray();for(int index=0;index<ik.length();index++){JSONObject target=AnimationRig2D.object(ik,index);if(!id.equals(target.optString("rootBoneId"))&&!id.equals(target.optString("childBoneId")))targets.put(target);}rig.put("ik",targets);
        JSONArray vertices=AnimationRig2D.array(AnimationRig2D.requiredObject(rig,"mesh"),"vertices",true);
        for(int index=0;index<vertices.length();index++){
            JSONObject vertex=AnimationRig2D.object(vertices,index);JSONArray influences=AnimationRig2D.array(vertex,"influences",true),keep=new JSONArray();
            for(int item=0;item<influences.length();item++){JSONObject influence=AnimationRig2D.object(influences,item);if(!id.equals(influence.optString("boneId")))keep.put(influence);}
            vertex.put("influences",keep.length()>0?normalizedWeights(keep):weights(AnimationRig2D.array(rig,"bones",true),vertex.getDouble("u"),vertex.getDouble("v"),aspect));
        }
    }
    private static void setWeights(JSONObject rig,JSONObject args)throws Exception{
        JSONArray vertices=AnimationRig2D.array(AnimationRig2D.requiredObject(rig,"mesh"),"vertices",true);
        JSONArray influences=normalizedWeights(AnimationRig2D.array(args,"influences",true));
        if(args.has("vertexIndices")){
            JSONArray indices=AnimationRig2D.array(args,"vertexIndices",true);if(indices.length()<1||indices.length()>AnimationRig2D.MAX_VERTICES)throw new IllegalArgumentException("Weight selection requires 1 to 512 vertices");
            for(int index=0;index<indices.length();index++){int selected=(int)AnimationRig2D.exact(indices.get(index),0,vertices.length()-1,"vertexIndex");AnimationRig2D.object(vertices,selected).put("influences",copyArray(influences));}
        }else{int index=(int)AnimationRig2D.integer(args,"vertexIndex",-1,0,vertices.length()-1);AnimationRig2D.object(vertices,index).put("influences",influences);}
    }
    private static JSONArray normalizedWeights(JSONArray source)throws Exception{
        if(source.length()<1||source.length()>4)throw new IllegalArgumentException("Weight painting requires 1 to 4 bone influences");
        double total=0;JSONArray positive=new JSONArray();java.util.HashSet<String> ids=new java.util.HashSet<>();
        for(int index=0;index<source.length();index++){
            JSONObject value=AnimationRig2D.object(source,index);AnimationRig2D.fields(value,"boneId","weight");String id=AnimationRig2D.identity(value,"boneId",null);
            if(!ids.add(id))throw new IllegalArgumentException("Weight influences cannot repeat a bone");double weight=AnimationRig2D.number(value,"weight",Double.NaN,0,1);
            if(weight>0){positive.put(new JSONObject().put("boneId",id).put("weight",weight));total+=weight;}
        }
        if(total<=0)throw new IllegalArgumentException("At least one painted bone weight must be positive");
        for(int index=0;index<positive.length();index++){JSONObject value=AnimationRig2D.object(positive,index);value.put("weight",value.getDouble("weight")/total);}return positive;
    }

    private static JSONObject paintRegion(JSONObject rig,JSONObject args,float aspect)throws Exception{
        AnimationRig2D.fields(args,"clipId","boneId","u","v","points","radius","strength","falloff");
        String edited=AnimationRig2D.identity(args,"boneId",null);
        double radius=AnimationRig2D.number(args,"radius",.05,.001,2),strength=AnimationRig2D.number(args,"strength",.1,-1,1);
        String falloff=AnimationRig2D.label(args,"falloff","smooth");
        if(!"linear".equals(falloff)&&!"smooth".equals(falloff)&&!"hard".equals(falloff))
            throw new IllegalArgumentException("Weight brush falloff must be linear, smooth or hard");
        JSONArray supplied;
        if(args.has("points")){
            if(args.has("u")||args.has("v"))throw new IllegalArgumentException("Use a brush stroke points array or one u/v center, not both");
            supplied=AnimationRig2D.array(args,"points",true);
            if(supplied.length()<1||supplied.length()>256)throw new IllegalArgumentException("A weight brush stroke requires 1 to 256 dabs");
        }else supplied=new JSONArray().put(new JSONObject().put("u",AnimationRig2D.number(args,"u",Double.NaN,0,1))
                .put("v",AnimationRig2D.number(args,"v",Double.NaN,0,1)));
        double[] dabU=new double[supplied.length()],dabV=new double[supplied.length()];
        for(int index=0;index<supplied.length();index++){
            JSONObject point=AnimationRig2D.object(supplied,index);AnimationRig2D.fields(point,"u","v");
            dabU[index]=AnimationRig2D.number(point,"u",Double.NaN,0,1);dabV[index]=AnimationRig2D.number(point,"v",Double.NaN,0,1);
        }
        JSONArray bones=AnimationRig2D.array(rig,"bones",true),vertices=AnimationRig2D.array(AnimationRig2D.requiredObject(rig,"mesh"),"vertices",true);
        String[] ids=new String[bones.length()];Map<String,Integer> positions=new HashMap<>();int selected=-1;
        for(int index=0;index<bones.length();index++){
            ids[index]=AnimationRig2D.identity(AnimationRig2D.object(bones,index),"id",null);positions.put(ids[index],index);
            if(edited.equals(ids[index]))selected=index;
        }
        if(selected<0)throw new IllegalArgumentException("The painted bone no longer exists");
        // Mutable numeric buffers keep 256*512 dabs bounded without allocating
        // JSON objects per dab or influence. Only final changed vertices publish.
        double[][] weights=new double[vertices.length()][bones.length()],original=new double[vertices.length()][bones.length()];
        double[] vertexU=new double[vertices.length()],vertexV=new double[vertices.length()];
        for(int index=0;index<vertices.length();index++){
            JSONObject vertex=AnimationRig2D.object(vertices,index);vertexU[index]=vertex.getDouble("u");vertexV[index]=vertex.getDouble("v");
            JSONArray influences=AnimationRig2D.array(vertex,"influences",true);double sum=0;
            for(int item=0;item<influences.length();item++){
                JSONObject influence=AnimationRig2D.object(influences,item);Integer bone=positions.get(influence.getString("boneId"));
                if(bone==null)throw new IllegalArgumentException("Vertex references an unknown bone");
                double weight=influence.getDouble("weight");weights[index][bone]=weight;sum+=weight;
            }
            for(int bone=0;bone<bones.length();bone++){
                weights[index][bone]/=sum;original[index][bone]=weights[index][bone];
            }
        }
        int[] keep=new int[4];boolean[] touched=new boolean[vertices.length()];int touchedCount=0;
        for(int dab=0;dab<dabU.length;dab++){
            if(Thread.currentThread().isInterrupted())throw new InterruptedException("Weight brush stroke cancelled");
            for(int index=0;index<vertices.length();index++){
            double distance=Math.hypot((vertexU[index]-dabU[dab])*aspect,vertexV[index]-dabV[dab]);
            if(distance>radius)continue;
            if(!touched[index]){touched[index]=true;touchedCount++;}
            double attenuation="hard".equals(falloff)?1:Math.max(0,1-distance/radius);
            if("smooth".equals(falloff))attenuation=attenuation*attenuation*(3-2*attenuation);
            double[] value=weights[index];double next=Math.max(0,Math.min(1,value[selected]+strength*attenuation));
            if(next<Double.MIN_NORMAL)next=0;
            if(next==value[selected])continue;
            int retained=strongest(value,ids,selected,next>0?3:4,keep);double remaining=0;
            for(int item=0;item<retained;item++)remaining+=value[keep[item]];
            // Erasing the sole influence cannot create an unbound vertex. The
            // owner can add another bone first; no auto-weight guess is made.
            if(next<1&&remaining<=0)continue;
            for(int bone=0;bone<value.length;bone++){
                if(bone==selected){value[bone]=next;continue;}
                boolean retainedBone=false;for(int item=0;item<retained;item++)if(keep[item]==bone){retainedBone=true;break;}
                value[bone]=retainedBone&&remaining>0?value[bone]*(1-next)/remaining:0;
                if(value[bone]<Double.MIN_NORMAL)value[bone]=0;
            }
            double normalized=0;for(double weight:value)normalized+=weight;
            if(normalized<=0)throw new IllegalArgumentException("Weight brush cannot leave a vertex without any influence");
            for(int bone=0;bone<value.length;bone++)value[bone]/=normalized;
            }
        }
        JSONArray changed=new JSONArray();
        for(int index=0;index<vertices.length();index++){
            boolean differs=false;for(int bone=0;bone<bones.length();bone++)if(weights[index][bone]!=original[index][bone]){differs=true;break;}
            if(!differs)continue;
            int retained=strongest(weights[index],ids,-1,4,keep);JSONArray influences=new JSONArray();
            for(int item=0;item<retained;item++){int bone=keep[item];influences.put(new JSONObject().put("boneId",ids[bone]).put("weight",weights[index][bone]));}
            AnimationRig2D.object(vertices,index).put("influences",influences);changed.put(index);
        }
        if(changed.length()==0)throw new IllegalArgumentException("This brush stroke changed no vertex weights; cover a mesh vertex, add another bone influence before erasing, or use a nonzero strength");
        return new JSONObject().put("affectedVertexCount",changed.length()).put("affectedVertexIndices",changed)
                .put("verticesWithinBrush",touchedCount).put("dabCount",dabU.length).put("boneId",edited)
                .put("radius",radius).put("strength",strength).put("falloff",falloff).put("radiusUnits","source_image_height");
    }

    /** Weight ties use stable bone IDs, so rig array reorder cannot change a brush result. */
    private static int strongest(double[] values,String[] ids,int excluded,int limit,int[] result){
        int count=0;
        for(int bone=0;bone<values.length;bone++){
            if(bone==excluded||values[bone]<=0)continue;
            int slot=0;while(slot<count&&(values[result[slot]]>values[bone]
                    ||values[result[slot]]==values[bone]&&ids[result[slot]].compareTo(ids[bone])<0))slot++;
            if(slot>=limit)continue;
            for(int item=Math.min(count,limit-1);item>slot;item--)result[item]=result[item-1];
            result[slot]=bone;if(count<limit)count++;
        }
        return count;
    }
    private static void mergePose(JSONObject rig,JSONObject edit)throws Exception{
        JSONObject pose=rig.optJSONObject("pose");if(pose==null){pose=new JSONObject();rig.put("pose",pose);}Iterator<String> ids=edit.keys();
        while(ids.hasNext()){String id=ids.next();JSONObject channels=AnimationRig2D.requiredObject(edit,id);JSONObject current=pose.optJSONObject(id);if(current==null)pose.put(id,new JSONObject(channels.toString()));else merge(current,channels);}
    }
    private static void capturePose(JSONObject rig,JSONObject args,float aspect)throws Exception{
        long at=AnimationRig2D.integer(args,"atMs",0,0,AnimationRig2D.MAX_TIME_MS);
        String id=AnimationRig2D.identity(args,"poseId","pose-"+UUID.randomUUID());String name=AnimationRig2D.label(args,"name","Pose");
        JSONObject saved=new JSONObject().put("id",id).put("name",name).put("pose",AnimationRig2D.compile(rig,aspect).sample(at).poseJson());
        putById(rig,"poses",saved);
    }
    private static void applyPose(JSONObject rig,JSONObject args,ProjectStore.Clip clip)throws Exception{
        JSONObject saved=find(AnimationRig2D.array(rig,"poses",false),args.getString("poseId"));if(saved==null)throw new IllegalArgumentException("The selected pose no longer exists");
        JSONObject pose=new JSONObject(AnimationRig2D.requiredObject(saved,"pose").toString());JSONArray targets=AnimationRig2D.array(rig,"ik",false);
        if(args.has("atMs")){
            long at=authoredTime(clip,args,"atMs");Iterator<String> names=pose.keys();
            while(names.hasNext()){String id=names.next();JSONObject key=new JSONObject(AnimationRig2D.requiredObject(pose,id).toString());key.put("boneId",id).put("atMs",at).put("ease",args.optString("ease","linear"));if(args.has("bezier"))key.put("bezier",args.get("bezier"));putBoneKey(rig,key);}
            for(int index=0;index<targets.length();index++){JSONObject target=AnimationRig2D.object(targets,index);target.put("keyframes",putTimedKey(AnimationRig2D.array(target,"keyframes",false),new JSONObject().put("atMs",at).put("mix",0).put("ease","linear"),null));}
        }else{rig.put("pose",pose);for(int index=0;index<targets.length();index++)AnimationRig2D.object(targets,index).put("mix",0);}
    }
    private static void putBoneKey(JSONObject rig,JSONObject key)throws Exception{
        putBoneKey(rig,key,false);
    }
    private static void putBoneKey(JSONObject rig,JSONObject key,boolean replaceKeyframe)throws Exception{
        String id=AnimationRig2D.identity(key,"boneId",null);rig.put("keyframes",putTimedKey(AnimationRig2D.array(rig,"keyframes",false),key,id,replaceKeyframe));
    }
    private static JSONArray putTimedKey(JSONArray keys,JSONObject key,String boneId)throws Exception{
        return putTimedKey(keys,key,boneId,false);
    }
    private static JSONArray putTimedKey(JSONArray keys,JSONObject key,String boneId,boolean replaceKeyframe)throws Exception{
        long at=AnimationRig2D.integer(key,"atMs",-1,0,AnimationRig2D.MAX_TIME_MS);JSONArray result=new JSONArray();JSONObject replacement=new JSONObject(key.toString());boolean replaced=false;
        for(int index=0;index<keys.length();index++){
            JSONObject existing=AnimationRig2D.object(keys,index);
            if(existing.getLong("atMs")==at&&(boneId==null||boneId.equals(existing.optString("boneId")))){
                // Replacement intentionally drops omitted sparse channels and
                // easing fields. Full rig validation occurs before publication.
                if(replaceKeyframe){result.put(replacement);replaced=true;continue;}
                JSONObject combined=new JSONObject(existing.toString());merge(combined,replacement);
                if(!"cubic_bezier".equals(combined.optString("ease","linear")))combined.remove("bezier");result.put(combined);replaced=true;
            }else result.put(existing);
        }
        if(!replaced)result.put(replacement);return result;
    }
    private static JSONArray withoutKey(JSONArray keys,String boneId,long at)throws Exception{
        JSONArray result=new JSONArray();for(int index=0;index<keys.length();index++){JSONObject key=AnimationRig2D.object(keys,index);if(key.getLong("atMs")!=at||(boneId!=null&&!boneId.equals(key.optString("boneId"))))result.put(key);}return result;
    }
    /** Move the existing sparse row, without sampling/filling channels or merging another key. */
    private static void moveTimedKey(JSONArray keys,String boneId,long from,long to)throws Exception{
        JSONObject selected=null;
        for(int index=0;index<keys.length();index++){
            JSONObject key=AnimationRig2D.object(keys,index);
            if(boneId!=null&&!boneId.equals(key.optString("boneId")))continue;
            long at=AnimationRig2D.integer(key,"atMs",-1,0,AnimationRig2D.MAX_TIME_MS);
            if(at==from)selected=key;
            if(from!=to&&at==to)throw new IllegalArgumentException("The destination already has a keyframe; choose an unoccupied authored time");
        }
        if(selected==null)throw new IllegalArgumentException("The selected keyframe no longer exists");
        // JSON is a staged deep copy. Only atMs changes; sparse channel values,
        // easing and custom Bezier control points remain byte-equivalent data.
        selected.put("atMs",to);
    }
    private static JSONObject find(JSONArray values,String id)throws Exception{for(int index=0;index<values.length();index++){JSONObject value=AnimationRig2D.object(values,index);if(id.equals(value.optString("id")))return value;}return null;}
    private static JSONArray withoutId(JSONArray values,String id)throws Exception{JSONArray result=new JSONArray();for(int index=0;index<values.length();index++){JSONObject value=AnimationRig2D.object(values,index);if(!id.equals(value.optString("id")))result.put(value);}return result;}
    private static void putById(JSONObject rig,String field,JSONObject value)throws Exception{
        String id=AnimationRig2D.identity(value,"id",null);JSONArray values=AnimationRig2D.array(rig,field,false);JSONObject existing=find(values,id);
        if(existing==null)values.put(new JSONObject(value.toString()));else{JSONObject copy=new JSONObject(value.toString());Iterator<String> old=existing.keys();List<String> names=new ArrayList<>();while(old.hasNext())names.add(old.next());for(String name:names)existing.remove(name);merge(existing,copy);}
        rig.put(field,values);
    }
    private static void merge(JSONObject target,JSONObject source)throws Exception{Iterator<String> keys=source.keys();while(keys.hasNext()){String name=keys.next();target.put(name,source.get(name));}}
    private static JSONArray copyArray(JSONArray array)throws Exception{return new JSONArray(array.toString());}
}
