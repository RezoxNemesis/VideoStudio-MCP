package com.rezoxnemesis.videostudio;

import org.json.JSONArray;
import org.json.JSONObject;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Deterministic, non-destructive prepared-art animation. The result is an ordinary editable
 * SketchDocument: the existing native Canvas renderer is the only preview/export renderer.
 * Joints use absolute normalized rest positions; local rotations are unwrapped degrees.
 * This compiler does not infer anatomy, invent missing views, or certify artistic quality.
 */
public final class SketchAnimationCompiler {
    public static final String SCHEMA="videostudio.sketch-animation.v1";
    public static final int MAX_RIGS=16, MAX_JOINTS=64, MAX_PROGRAM_BYTES=512*1024, MAX_SAMPLED_KEYS=32768;
    public static final double KEYFRAME_TOLERANCE_PIXELS=.25;
    private static final double EPS=1e-8;
    private SketchAnimationCompiler(){}

    /** Complete validation and compilation happen in memory; the caller owns atomic persistence. */
    public static JSONObject compile(JSONObject source,JSONObject program,String outputId)throws Exception {
        Model m=new Model(source,program);
        SketchDocument.id(new JSONObject().put("outputId",outputId),"outputId");
        if(outputId.equals(source.getString("id")))throw bad("The output must differ from the source sketch");
        JSONObject out=SketchDocument.copy(m.source).put("id",outputId).put("revision",1)
                .put("name",program.optString("name",truncate(source.getString("name")+" · Animation",160))).put("frameCount",m.frames).put("fps",m.fps);
        JSONArray layers=out.getJSONArray("layers");Map<String,JSONObject> outputObjects=new HashMap<>();Map<String,JSONArray> deformedCels=new HashMap<>();
        int expectedObjects=0,expectedKeys=0,expectedPoints=0;
        for(int li=0;li<layers.length();li++) {
            JSONObject layer=layers.getJSONObject(li);JSONArray sourceItems=layer.getJSONArray("objects"),items=new JSONArray();layer.put("objects",items);
            if(m.cameraLayers.contains(layer.getString("id")))expectedKeys+=m.frames;
            else expectedKeys+=clippedKeys(layer,m.frames).length();
            layer.put("keys",clippedKeys(layer,m.frames));
            for(int oi=0;oi<sourceItems.length();oi++) {
                JSONObject object=sourceItems.getJSONObject(oi);int[] range=m.ranges.get(object.getString("id"));if(range==null)continue;
                Binding binding=m.bindings.get(object.getString("id"));int duration=range[1]-range[0]+1;
                int count=binding!=null&&binding.weights!=null?duration:1;expectedObjects+=count;
                expectedPoints+=geometryPoints(object)*count;
                if(binding!=null&&binding.weights==null)expectedKeys+=duration;
                else if(binding==null)expectedKeys+=clippedKeys(object,m.frames).length();
                if(expectedObjects>SketchDocument.MAX_OBJECTS||expectedPoints>SketchDocument.MAX_POINTS||expectedKeys>MAX_SAMPLED_KEYS)
                    throw bad("Animation sampling budget exceeded before frame expansion (4096 objects, 131072 points, 32768 dense keys)");
                if(binding!=null&&binding.weights!=null)continue;
                object.put("frame",range[0]).put("exposure",duration).put("keys",binding==null?clippedKeys(object,m.frames):new JSONArray());
                items.put(object);outputObjects.put(object.getString("id"),object);
            }
        }
        if(expectedObjects+m.dialogues.length()>SketchDocument.MAX_OBJECTS)throw bad("Animation object budget exceeded by dialogue cels");
        int articulatedFrames=0,rootFrames=0,animatedJoints=0,contactSamples=0;double maxContactError=0,maxContactRotationError=0;
        Set<String> movingJoints=new HashSet<>();List<Snapshot> previous=new ArrayList<>();for(int i=0;i<m.rigs.size();i++)previous.add(null);
        for(int frame=0;frame<m.frames;frame++) {
            if(Thread.currentThread().isInterrupted())throw new InterruptedException("Animation compilation cancelled");
            boolean articulated=false,rootMoved=false;
            for(int ri=0;ri<m.rigs.size();ri++) {
                Rig rig=m.rigs.get(ri);Snapshot s=sampleRig(m,rig,frame);Snapshot before=previous.get(ri);
                Set<Integer> visibleInfluences=new HashSet<>();
                for(Binding binding:rig.bindings){int[] range=m.ranges.get(binding.objectId);if(range==null||frame<range[0]||frame>range[1]||!explicitlyVisible(m,binding.objectId,frame))continue;if(binding.weights==null)visibleInfluences.add(binding.joint);else for(Map<Integer,Double> weights:binding.weights)visibleInfluences.addAll(weights.keySet());}
                if(before!=null)for(int ji=0;ji<rig.joints.size();ji++) {
                    boolean inside=false,outside=false;for(int influence:visibleInfluences){if(ancestor(rig,ji,influence))inside=true;else outside=true;}
                    if(inside&&Math.abs(s.local[ji]-before.local[ji])>EPS) {
                        movingJoints.add(rig.id+":"+rig.joints.get(ji).id);
                        // A changed but unused joint, or a single whole-image transform, is not articulation.
                        if(rig.joints.get(ji).parent>=0&&outside)articulated=true;
                    }
                    if(inside&&rig.joints.get(ji).parent<0&&(Math.abs(s.x[ji]-before.x[ji])>EPS||Math.abs(s.y[ji]-before.y[ji])>EPS))rootMoved=true;
                }
                previous.set(ri,s);contactSamples+=s.contactErrors.length();
                for(int i=0;i<s.contactErrors.length();i++){JSONObject error=s.contactErrors.getJSONObject(i);maxContactError=Math.max(maxContactError,error.getDouble("errorPixels"));maxContactRotationError=Math.max(maxContactRotationError,error.optDouble("rotationErrorDegrees",0));}
                for(Binding b:rig.bindings) {
                    int[] range=m.ranges.get(b.objectId);if(range==null||frame<range[0]||frame>range[1])continue;
                    JSONObject prepared=m.objects.get(b.objectId);
                    if(b.weights==null) {
                        JSONObject object=outputObjects.get(b.objectId);JSONObject base=SketchDocument.transformAt(prepared,Math.min(frame,m.sourceFrames-1));
                        JSONObject transform=jointTransform(m,rig,s,b.joint,base);
                        object.getJSONArray("keys").put(new JSONObject().put("frame",frame).put("transform",transform).put("interpolation","hold"));
                    } else {
                        JSONObject cel=deform(m,rig,s,b,prepared,frame);
                        deformedCels.computeIfAbsent(b.objectId,k->new JSONArray()).put(cel);
                    }
                }
            }
            if(articulated)articulatedFrames++;if(rootMoved)rootFrames++;
            if(!m.cameraLayers.isEmpty()) {
                Camera c=sampleCamera(m,frame);
                for(String layerId:m.cameraLayers) {
                    JSONObject layer=SketchDocument.layer(out,layerId);if(frame==0)layer.put("keys",new JSONArray());
                    JSONObject base=SketchDocument.transformAt(SketchDocument.layer(source,layerId),Math.min(frame,m.sourceFrames-1));
                    layer.getJSONArray("keys").put(new JSONObject().put("frame",frame).put("transform",cameraTransform(m,c,base)).put("interpolation","hold"));
                }
            }
        }
        // Preserve authored compositing order when one weighted object expands into many cels.
        for(int li=0;li<layers.length();li++){
            JSONObject layer=layers.getJSONObject(li);JSONArray original=SketchDocument.layer(m.source,layer.getString("id")).getJSONArray("objects"),ordered=new JSONArray();
            for(int oi=0;oi<original.length();oi++){String id=original.getJSONObject(oi).getString("id");JSONArray cels=deformedCels.get(id);if(cels!=null)for(int j=0;j<cels.length();j++)ordered.put(cels.getJSONObject(j));else if(outputObjects.containsKey(id))ordered.put(outputObjects.get(id));}
            layer.put("objects",ordered);
        }
        for(int i=0;i<m.dialogues.length();i++) {
            JSONObject cue=m.dialogues.getJSONObject(i);String id=uniqueId(m,"dialogue_"+i);
            JSONObject text=new JSONObject().put("id",id).put("kind","text").put("frame",cue.getInt("start"))
                    .put("exposure",cue.getInt("end")-cue.getInt("start")+1).put("color",cue.optString("color","#FFFFFFFF"))
                    .put("width",.001).put("fill",true).put("bounds",SketchDocument.copy(cue.getJSONObject("bounds")))
                    .put("text",cue.getString("text")).put("fontFamily","sans-serif").put("fontStyle","bold")
                    .put("fontSize",cue.optDouble("fontSize",.035)).put("textAlign","center")
                    .put("transform",SketchDocument.transform()).put("keys",new JSONArray());
            SketchDocument.layer(out,cue.getString("layerId")).getJSONArray("objects").put(text);
        }
        double maxApproximation=0;int denseKeys=0;
        for(int li=0;li<layers.length();li++){JSONObject layer=layers.getJSONObject(li);if(m.cameraLayers.contains(layer.getString("id"))){denseKeys+=layer.getJSONArray("keys").length();Reduction reduced=reduceKeys(m,layer.getJSONArray("keys"),new double[]{-16d*m.width,-16d*m.height,16d*m.width,16d*m.height},0);layer.put("keys",reduced.keys);maxApproximation=Math.max(maxApproximation,reduced.error);}JSONArray items=layer.getJSONArray("objects");for(int oi=0;oi<items.length();oi++){JSONObject object=items.getJSONObject(oi);Binding binding=m.bindings.get(object.getString("id"));if(binding!=null&&binding.weights==null){denseKeys+=object.getJSONArray("keys").length();double layerScale=maxLayerScale(m,m.owners.get(binding.objectId));Reduction reduced=reduceKeys(m,object.getJSONArray("keys"),geometryBounds(m,object),KEYFRAME_TOLERANCE_PIXELS/layerScale);object.put("keys",reduced.keys);maxApproximation=Math.max(maxApproximation,reduced.error*layerScale);}}}
        SketchDocument.validate(out);
        JSONObject contactEvidence=interactionEvidence(m);
        int objectCount=0,keyCount=0;for(int i=0;i<layers.length();i++){JSONObject l=layers.getJSONObject(i);keyCount+=l.getJSONArray("keys").length();JSONArray items=l.getJSONArray("objects");objectCount+=items.length();for(int j=0;j<items.length();j++)keyCount+=items.getJSONObject(j).getJSONArray("keys").length();}
        animatedJoints=movingJoints.size();int weighted=0,contacts=0,oriented=0,blended=0;for(Rig r:m.rigs){contacts+=r.contacts.size();for(Contact c:r.contacts){if(c.hasRotation)oriented++;if(c.blendIn>0||c.blendOut>0)blended++;}for(Binding b:r.bindings)if(b.weights!=null)weighted++;}
        JSONObject report=new JSONObject().put("provider","authored_articulated_2d").put("schema",SCHEMA)
                .put("frameCount",m.frames).put("fps",m.fps).put("durationMs",Math.round(m.frames*1000d/m.fps))
                .put("rigCount",m.rigs.size()).put("boundObjectCount",m.bindings.size()).put("deformedObjectCount",weighted)
                .put("animatedJointCount",animatedJoints).put("articulatedMotionFrames",articulatedFrames)
                .put("independentArticulation",articulatedFrames>0).put("motionMetric","exposed bindings with visible layers and nonzero transform opacity; no pixel-content or occlusion assessment").put("rootMotionFrames",rootFrames)
                .put("contactCount",contacts).put("blendedContactCount",blended).put("orientationContactCount",oriented).put("maxFootContactRotationErrorDegrees",maxContactRotationError).put("contactSampleCount",contactSamples).put("maxFootContactErrorPixels",maxContactError).put("contactCoordinateSpace","prepared_canvas_pixels_before_camera")
                .put("dialogueCount",m.dialogues.length()).put("mouthCelCount",m.mouthCount).put("celRangeCount",m.celCount).put("hiddenArtworkCount",m.hiddenCount)
                .put("interCharacterContactCount",m.interactionCues.length())
                .put("maxInterCharacterContactGapPixels",contactEvidence.getDouble("maxGapPixels"))
                .put("interCharacterContacts",contactEvidence.getJSONArray("contacts"))
                .put("cameraSampleCount",m.cameraLayers.isEmpty()?0:m.frames).put("outputObjectCount",objectCount).put("outputTransformKeys",keyCount)
                .put("denseTransformSampleCount",denseKeys).put("keyframeTolerancePixels",KEYFRAME_TOLERANCE_PIXELS).put("maxBakedTransformApproximationPixels",maxApproximation)
                .put("outputBytes",out.toString().getBytes(StandardCharsets.UTF_8).length).put("sourcePreserved",true)
                .put("sourceSha256",hash(source)).put("programSha256",hash(program)).put("artisticQualityVerified",false)
                .put("limitations",new JSONArray().put("Authored rig mapping is required; no anatomy or alternate-view inference")
                        .put("Timed mouth cels may follow approximate text visemes; no recorded-audio alignment, synthesized speech, or verified lip synchronization")
                        .put("Baton/hand contact cues validate joint distance only, not silhouettes, collision volume or film-grade choreography")
                        .put("Motion metrics verify constraints, not cinematic or exact-reference quality"));
        return new JSONObject().put("document",out).put("report",report);
    }

    /** Cheap standalone validation for the editor before it admits new contact markers.
     * Unlike complete bake/compile, this only measures the sparse authored contacts. */
    static JSONObject verifyInteractionCues(JSONObject source,JSONObject program)throws Exception {
        return interactionEvidence(new Model(source,program));
    }

    /** Validated authored action contact: use sampled real rig positions in prepared-canvas
     * pixels, not camera-relative screen pixels or a decorative spark. Never mutates rigs. */
    private static JSONObject interactionEvidence(Model m)throws Exception {
        JSONArray results=new JSONArray();double maximum=0;
        Map<String,Snapshot> cache=new HashMap<>();
        for(int i=0;i<m.interactionCues.length();i++){
            if(Thread.currentThread().isInterrupted())throw new InterruptedException("Action contact QA cancelled");
            JSONObject cue=m.interactionCues.getJSONObject(i);
            int frame=cue.getInt("frame");
            Rig a=rigById(m,cue.getString("rigA")),b=rigById(m,cue.getString("rigB"));
            Snapshot first=cachedSample(m,a,frame,cache),second=cachedSample(m,b,frame,cache);
            int aJoint=jointIndex(a,cue.getString("jointA")),bJoint=jointIndex(b,cue.getString("jointB"));
            double gap=Math.hypot(first.x[aJoint]-second.x[bJoint],first.y[aJoint]-second.y[bJoint]);
            double allowed=cue.getDouble("maxGapPx");
            if(gap>allowed+1e-7)throw bad("INTERACTION_CONTACT_MISS at frame "+frame+": "+
                    cue.getString("rigA")+":"+cue.getString("jointA")+" to "+
                    cue.getString("rigB")+":"+cue.getString("jointB")+" is "+
                    Math.round(gap)+" source pixels apart; allowed "+allowed);
            results.put(new JSONObject().put("frame",frame).put("rigA",a.id)
                    .put("jointA",cue.getString("jointA")).put("rigB",b.id)
                    .put("jointB",cue.getString("jointB")).put("gapPixels",gap)
                    .put("allowedPixels",allowed).put("description",cue.optString("description","")));
            maximum=Math.max(maximum,gap);
        }
        return new JSONObject().put("maxGapPixels",maximum).put("contacts",results);
    }
    private static Snapshot cachedSample(Model m,Rig rig,int frame,Map<String,Snapshot> cache)throws Exception{
        String key=rig.id+":"+frame;Snapshot found=cache.get(key);
        if(found==null){found=sampleRig(m,rig,frame);cache.put(key,found);}
        return found;
    }
    private static Rig rigById(Model m,String id){
        for(Rig r:m.rigs)if(id.equals(r.id))return r;
        throw bad("Unknown contact character rig: "+id);
    }

    /** UI joint overlays and contact diagnostics use exactly the same solver as compilation. */
    public static JSONObject sample(JSONObject source,JSONObject program,int frame)throws Exception {
        return sample(new Model(source,program),frame);
    }

    /** Bounded frame sampling shares one validated immutable model across a whole gait or QA interval.
     * This is substantially cheaper than reparsing large source documents for every output frame.
     * No frame is published or modified; callers receive fresh snapshots. */
    static JSONArray sampleFrames(JSONObject source,JSONObject program,int first,int last)throws Exception {
        Model m=new Model(source,program);
        if(first<0||last<first||last>=m.frames||last-first>=360)
            throw bad("Animation sampling interval must contain 1..360 in-range frames");
        JSONArray samples=new JSONArray();
        for(int frame=first;frame<=last;frame++){
            if(Thread.currentThread().isInterrupted())throw new InterruptedException("Animation frame sampling cancelled");
            samples.put(sample(m,frame));
        }
        return samples;
    }

    private static JSONObject sample(Model m,int frame)throws Exception {
        if(frame<0||frame>=m.frames)throw bad("Animation sample frame outside timeline");
        JSONArray joints=new JSONArray(),errors=new JSONArray();Camera camera=sampleCamera(m,frame);
        for(Rig rig:m.rigs) {
            Snapshot s=sampleRig(m,rig,frame);
            for(int i=0;i<rig.joints.size();i++) {
                Joint j=rig.joints.get(i);double[] display=rig.cameraAffected?applyCamera(m,camera,s.x[i],s.y[i]):new double[]{s.x[i],s.y[i]};
                joints.put(new JSONObject().put("rigId",rig.id).put("jointId",j.id).put("parent",j.parent<0?JSONObject.NULL:rig.joints.get(j.parent).id)
                        .put("x",s.x[i]/m.width).put("y",s.y[i]/m.height).put("rotation",s.angle[i])
                        .put("cameraApplied",rig.cameraAffected).put("displayX",display[0]/m.width).put("displayY",display[1]/m.height));
            }
            for(int i=0;i<s.contactErrors.length();i++)errors.put(s.contactErrors.getJSONObject(i));
        }
        return new JSONObject().put("frame",frame).put("joints",joints).put("contactErrors",errors);
    }

    /** A worker-thread live preview samples one frame only and never saves or mutates artwork. */
    public static JSONObject preview(JSONObject source,JSONObject program,int frame,String outputId)throws Exception {
        Model m=new Model(source,program);if(frame<0||frame>=m.frames)throw bad("Animation preview frame outside timeline");
        SketchDocument.id(new JSONObject().put("id",outputId),"id");
        JSONObject out=SketchDocument.copy(m.source).put("id",outputId).put("revision",1).put("fps",m.fps).put("frameCount",1);
        Map<String,Snapshot> snapshots=new HashMap<>();Map<String,Rig> bindingRigs=new HashMap<>();
        for(Rig rig:m.rigs){snapshots.put(rig.id,sampleRig(m,rig,frame));for(Binding binding:rig.bindings)bindingRigs.put(binding.objectId,rig);}
        JSONArray layers=out.getJSONArray("layers");Camera camera=sampleCamera(m,frame);
        for(int li=0;li<layers.length();li++){
            JSONObject layer=layers.getJSONObject(li);JSONObject originalLayer=SketchDocument.layer(m.source,layer.getString("id"));
            JSONObject transform=SketchDocument.transformAt(originalLayer,Math.min(frame,m.sourceFrames-1));
            if(m.cameraLayers.contains(layer.getString("id")))transform=cameraTransform(m,camera,transform);
            layer.put("transform",transform).put("keys",new JSONArray());JSONArray originalItems=layer.getJSONArray("objects"),visible=new JSONArray();layer.put("objects",visible);
            for(int oi=0;oi<originalItems.length();oi++){
                JSONObject object=originalItems.getJSONObject(oi);String id=object.getString("id");int[] range=m.ranges.get(id);if(range==null||frame<range[0]||frame>range[1])continue;
                Binding binding=m.bindings.get(id);JSONObject prepared=m.objects.get(id);
                if(binding==null)object.put("transform",SketchDocument.transformAt(prepared,Math.min(frame,m.sourceFrames-1)));
                else{Rig rig=bindingRigs.get(id);if(binding.weights!=null)object=deform(m,rig,snapshots.get(rig.id),binding,prepared,frame);else object.put("transform",jointTransform(m,rig,snapshots.get(rig.id),binding.joint,SketchDocument.transformAt(prepared,Math.min(frame,m.sourceFrames-1))));}
                visible.put(object.put("frame",0).put("exposure",1).put("keys",new JSONArray()));
            }
        }
        for(int i=0;i<m.dialogues.length();i++){
            JSONObject cue=m.dialogues.getJSONObject(i);if(frame<cue.getInt("start")||frame>cue.getInt("end"))continue;
            JSONObject text=new JSONObject().put("id",uniqueId(m,"dialogue_"+i)).put("kind","text").put("frame",0).put("exposure",1)
                    .put("color",cue.optString("color","#FFFFFFFF")).put("width",.001).put("fill",true).put("bounds",SketchDocument.copy(cue.getJSONObject("bounds")))
                    .put("text",cue.getString("text")).put("fontFamily","sans-serif").put("fontStyle","bold").put("fontSize",cue.optDouble("fontSize",.035))
                    .put("textAlign","center").put("transform",SketchDocument.transform()).put("keys",new JSONArray());
            SketchDocument.layer(out,cue.getString("layerId")).getJSONArray("objects").put(text);
        }
        SketchDocument.validate(out);return new JSONObject().put("document",out).put("sample",sample(m,frame));
    }

    private static final class Joint {String id;int parent=-1;double x,y;}
    private static final class Pose {int frame;String interpolation;double rootX,rootY;double[] rotations;}
    private static final class Contact {int hip,knee,ankle,start,end,bend,blendIn,blendOut;double x,y,rotation;boolean hasRotation;}
    private static final class Binding {String objectId;int joint;List<Map<Integer,Double>> weights;}
    private static final class Rig {String id;boolean cameraAffected;List<Joint> joints=new ArrayList<>();List<Pose> poses=new ArrayList<>();List<Contact> contacts=new ArrayList<>();List<Binding> bindings=new ArrayList<>();Map<String,Integer> index=new LinkedHashMap<>();}
    private static final class Snapshot {double[] x,y,angle,local;double rootX,rootY;JSONArray contactErrors=new JSONArray();Snapshot(int n){x=new double[n];y=new double[n];angle=new double[n];local=new double[n];}}
    private static final class Camera {int frame;String interpolation="linear";double x,y,zoom=1,rotation;}

    private static final class Model {
        final JSONObject source;final int frames,fps,sourceFrames,width,height;final boolean single;
        final Map<String,JSONObject> objects=new LinkedHashMap<>();final Map<String,String> owners=new HashMap<>();
        final Map<String,int[]> ranges=new HashMap<>();final Map<String,Binding> bindings=new HashMap<>();
        final Map<String,List<String>> celClones=new HashMap<>();final Map<String,List<int[]>> intervals=new HashMap<>();
        final List<Rig> rigs=new ArrayList<>();final Set<String> cameraLayers=new HashSet<>();final List<Camera> camera=new ArrayList<>();
        final JSONArray dialogues,interactionCues;final Set<String> reservedIds=new HashSet<>();int mouthCount,celCount,hiddenCount;
        Model(JSONObject source,JSONObject p)throws Exception {
            SketchDocument.validate(source);this.source=SketchDocument.copy(source);
            SketchDocument.fields(p,"schema,name,frameCount,fps,rigs,camera,cameraLayerIds,dialogues,mouthCels,celRanges,hiddenArtwork,interactionCues");
            if(p.has("name"))SketchDocument.text(p,"name",160);
            if(!SCHEMA.equals(p.getString("schema")))throw bad("Unknown animation program schema");
            if(p.toString().getBytes(StandardCharsets.UTF_8).length>MAX_PROGRAM_BYTES)throw bad("Animation program exceeds 512 KiB");
            for(String key:new String[]{"rigs","camera","cameraLayerIds","dialogues","mouthCels","celRanges","hiddenArtwork","interactionCues"})optionalArray(p,key);
            frames=(int)SketchDocument.integer(p,"frameCount",1,SketchDocument.MAX_FRAMES);
            fps=p.has("fps")?(int)SketchDocument.integer(p,"fps",24,60):source.getInt("fps");NativeFrameClock.requireFps(fps);
            sourceFrames=source.getInt("frameCount");single=sourceFrames==1;width=source.getInt("width");height=source.getInt("height");
            JSONArray layers=this.source.getJSONArray("layers");
            for(int i=0;i<layers.length();i++) {
                JSONObject layer=layers.getJSONObject(i);String layerId=layer.getString("id");reservedIds.add(layerId);JSONArray items=layer.getJSONArray("objects");
                for(int j=0;j<items.length();j++) {
                    JSONObject o=items.getJSONObject(j);String id=o.getString("id");objects.put(id,o);owners.put(id,layerId);reservedIds.add(id);
                    int start=single?0:o.getInt("frame"),end=single?frames-1:Math.min(frames-1,start+o.getInt("exposure")-1);if(start<frames)ranges.put(id,new int[]{start,end});
                }
            }
            JSONArray hidden=p.optJSONArray("hiddenArtwork");
            if(hidden!=null){
                if(hidden.length()>128)throw bad("Use at most 128 selectively hidden artwork objects");
                Set<String> seen=new HashSet<>();
                for(int i=0;i<hidden.length();i++){
                    String id=SketchDocument.id(new JSONObject().put("objectId",hidden.getString(i)),"objectId");
                    if(!objects.containsKey(id)||!seen.add(id))throw bad("Hidden artwork must reference unique existing objects");
                    ranges.remove(id);hiddenCount++;
                }
            }
            Set<String> overridden=new HashSet<>();readRanges(p.optJSONArray("mouthCels"),overridden,true);readRanges(p.optJSONArray("celRanges"),overridden,false);
            JSONArray rr=p.optJSONArray("rigs");if(rr==null)rr=new JSONArray();if(rr.length()>MAX_RIGS)throw bad("Use at most 16 animation rigs");Set<String> rigIds=new HashSet<>();
            for(int i=0;i<rr.length();i++){Rig r=readRig(rr.getJSONObject(i));if(!rigIds.add(r.id))throw bad("Duplicate rig ID");rigs.add(r);}
            JSONArray cc=p.optJSONArray("camera");if(cc!=null)readCamera(cc);
            JSONArray cl=p.optJSONArray("cameraLayerIds");if(cl!=null)for(int i=0;i<cl.length();i++){String id=cl.getString(i);SketchDocument.layer(source,id);if(!cameraLayers.add(id))throw bad("Duplicate camera layer");}
            if(!camera.isEmpty()&&cameraLayers.isEmpty())throw bad("Camera keys require explicit cameraLayerIds");
            for(Rig rig:rigs){Boolean selected=null;for(Binding binding:rig.bindings){String owner=owners.get(binding.objectId);JSONObject layer=SketchDocument.layer(this.source,owner);
                requireIdentityLayer(layer.getJSONObject("transform"));JSONArray keys=layer.getJSONArray("keys");for(int i=0;i<keys.length();i++)requireIdentityLayer(keys.getJSONObject(i).getJSONObject("transform"));
                boolean affected=cameraLayers.contains(owner);if(selected!=null&&selected!=affected)throw bad("All bound layers of one rig must share the same camera selection");selected=affected;
            }rig.cameraAffected=Boolean.TRUE.equals(selected);}
            interactionCues=p.optJSONArray("interactionCues")==null?new JSONArray():p.getJSONArray("interactionCues");
            if(interactionCues.length()>64)throw bad("Use at most 64 authored inter-character contact cues");
            for(int i=0;i<interactionCues.length();i++){
                JSONObject cue=interactionCues.getJSONObject(i);
                SketchDocument.fields(cue,"frame,rigA,jointA,rigB,jointB,maxGapPx,description");
                SketchDocument.integer(cue,"frame",0,frames-1);
                double gap=SketchDocument.number(cue,"maxGapPx",.1,512);
                String first=SketchDocument.id(cue,"rigA"),second=SketchDocument.id(cue,"rigB");
                if(first.equals(second))throw bad("Action contact requires two distinct character rigs");
                Rig a=rigById(this,first),b=rigById(this,second);
                jointIndex(a,SketchDocument.id(cue,"jointA"));
                jointIndex(b,SketchDocument.id(cue,"jointB"));
                if(cue.has("description"))SketchDocument.text(cue,"description",120);
            }
            dialogues=p.optJSONArray("dialogues")==null?new JSONArray():p.getJSONArray("dialogues");if(dialogues.length()>256)throw bad("Use at most 256 dialogue cues");
            for(int i=0;i<dialogues.length();i++) {
                JSONObject c=dialogues.getJSONObject(i);SketchDocument.fields(c,"start,end,text,layerId,bounds,color,fontSize");range(c);SketchDocument.layer(source,c.getString("layerId"));SketchDocument.text(c,"text",4096);SketchDocument.validateBounds(c.getJSONObject("bounds"));
                if(c.has("color"))SketchDocument.color(c.getString("color"));if(c.has("fontSize"))SketchDocument.number(c,"fontSize",.005,.3);
            }
        }
        private void readRanges(JSONArray array,Set<String> overridden,boolean mouth)throws Exception {
            if(array==null)return;if(array.length()>SketchDocument.MAX_OBJECTS)throw bad("Too many cel ranges");
            for(int i=0;i<array.length();i++){
                JSONObject c=array.getJSONObject(i);SketchDocument.fields(c,"objectId,start,end");String id=SketchDocument.id(c,"objectId");if(!objects.containsKey(id))throw bad("Unknown sketch object: "+id);int[] next=range(c);
                List<int[]> prior=intervals.computeIfAbsent(id,k->new ArrayList<>());for(int[] old:prior)if(next[0]<=old[1]&&old[0]<=next[1])throw bad("Object has overlapping cel ranges");prior.add(next);
                if(overridden.add(id))ranges.put(id,next);
                else{String cloneId=uniqueId(this,"cel_"+SketchDocument.sha256(id.getBytes(StandardCharsets.UTF_8)).substring(0,16)+"_"+next[0]+"_"+next[1]);JSONObject clone=SketchDocument.copy(objects.get(id)).put("id",cloneId);objects.put(cloneId,clone);owners.put(cloneId,owners.get(id));ranges.put(cloneId,next);JSONObject layer=SketchDocument.layer(this.source,owners.get(id));JSONArray oldItems=layer.getJSONArray("objects"),ordered=new JSONArray();for(int oi=0;oi<oldItems.length();oi++){JSONObject item=oldItems.getJSONObject(oi);ordered.put(item);if(item.getString("id").equals(id))ordered.put(clone);}layer.put("objects",ordered);celClones.computeIfAbsent(id,k->new ArrayList<>()).add(cloneId);}
                if(mouth)mouthCount++;else celCount++;
            }
        }
        private int[] range(JSONObject c)throws Exception {int start=(int)SketchDocument.integer(c,"start",0,frames-1),end=(int)SketchDocument.integer(c,"end",start,frames-1);return new int[]{start,end};}
        private Rig readRig(JSONObject o)throws Exception {
            SketchDocument.fields(o,"id,joints,bindings,poses,ik");for(String key:new String[]{"bindings","poses","ik"})optionalArray(o,key);Rig r=new Rig();r.id=SketchDocument.id(o,"id");JSONArray jj=o.getJSONArray("joints");
            if(jj.length()<1||jj.length()>MAX_JOINTS)throw bad("Rig requires 1..64 joints");
            for(int i=0;i<jj.length();i++){JSONObject j=jj.getJSONObject(i);SketchDocument.fields(j,"id,parent,x,y");Joint v=new Joint();v.id=SketchDocument.id(j,"id");if(r.index.put(v.id,i)!=null)throw bad("Duplicate joint ID");v.x=SketchDocument.number(j,"x",0,1)*width;v.y=SketchDocument.number(j,"y",0,1)*height;r.joints.add(v);}
            for(int i=0;i<jj.length();i++){JSONObject j=jj.getJSONObject(i);if(j.has("parent")&&!j.isNull("parent")&&!j.optString("parent").isEmpty()){String parent=j.getString("parent");if(!r.index.containsKey(parent))throw bad("Unknown parent joint");r.joints.get(i).parent=r.index.get(parent);}}
            for(int i=0;i<r.joints.size();i++){Set<Integer> seen=new HashSet<>();int cursor=i;while(cursor>=0){if(!seen.add(cursor))throw bad("Joint hierarchy contains a cycle");cursor=r.joints.get(cursor).parent;}}
            JSONArray pp=o.optJSONArray("poses");if(pp!=null){if(pp.length()>frames)throw bad("Too many rig poses");Pose before=null;for(int i=0;i<pp.length();i++){JSONObject p=pp.getJSONObject(i);SketchDocument.fields(p,"frame,interpolation,rootX,rootY,rotations");Pose v=new Pose();v.frame=(int)SketchDocument.integer(p,"frame",0,frames-1);if(before!=null&&v.frame<=before.frame)throw bad("Pose frames must be strictly ascending");v.interpolation=interpolation(p);v.rootX=p.has("rootX")?SketchDocument.number(p,"rootX",-4,4):before==null?0:before.rootX;v.rootY=p.has("rootY")?SketchDocument.number(p,"rootY",-4,4):before==null?0:before.rootY;v.rotations=before==null?new double[jj.length()]:before.rotations.clone();JSONObject rotations=p.optJSONObject("rotations");if(p.has("rotations")&&rotations==null)throw bad("Pose rotations require a joint map");if(rotations!=null){Iterator<String> ids=rotations.keys();while(ids.hasNext()){String id=ids.next();Integer index=r.index.get(id);if(index==null)throw bad("Unknown pose joint: "+id);v.rotations[index]=SketchDocument.scalar(rotations.get(id),-3600,3600,"Joint rotation");}}r.poses.add(v);before=v;}}
            JSONArray bb=o.optJSONArray("bindings");if(bb!=null)for(int i=0;i<bb.length();i++) {
                JSONObject b=bb.getJSONObject(i);SketchDocument.fields(b,"objectId,jointId,weights");Binding v=new Binding();v.objectId=SketchDocument.id(b,"objectId");JSONObject object=objects.get(v.objectId);if(object==null)throw bad("Unknown sketch object: "+v.objectId);
                if(bindings.containsKey(v.objectId))throw bad("A sketch object is bound more than once");v.joint=jointIndex(r,b.getString("jointId"));
                if(b.has("weights")){if(object.has("gradient"))throw bad("Skin weights cannot deform gradient coordinates. Use rigid gradient artwork or flat-colour vector regions");String kind=object.getString("kind");if(!java.util.Arrays.asList("stroke","eraser","line","polygon","path").contains(kind))throw bad("Skin weights require vector point or path geometry");JSONArray weights=b.getJSONArray("weights");if(weights.length()!=geometryPoints(object))throw bad("Skin weights must match every geometry vertex");v.weights=new ArrayList<>();for(int j=0;j<weights.length();j++){JSONObject weight=weights.getJSONObject(j);if(weight.length()<1||weight.length()>4)throw bad("Use 1..4 joint influences per vertex");Map<Integer,Double> map=new LinkedHashMap<>();double sum=0;Iterator<String> ids=weight.keys();while(ids.hasNext()){String id=ids.next();double value=SketchDocument.scalar(weight.get(id),0,1,"Skin weight");if(value==0)throw bad("Skin weights must be positive");map.put(jointIndex(r,id),value);sum+=value;}if(Math.abs(sum-1)>1e-7)throw bad("Skin weights must sum to one");v.weights.add(map);}}
                bindings.put(v.objectId,v);r.bindings.add(v);
                if(celClones.containsKey(v.objectId))for(String cloneId:celClones.get(v.objectId)){Binding clone=new Binding();clone.objectId=cloneId;clone.joint=v.joint;clone.weights=v.weights;bindings.put(cloneId,clone);r.bindings.add(clone);}
            }
            JSONArray ii=o.optJSONArray("ik");if(ii!=null){if(ii.length()>256)throw bad("Use at most 256 IK contact intervals per rig");for(int i=0;i<ii.length();i++){JSONObject c=ii.getJSONObject(i);SketchDocument.fields(c,"hip,knee,ankle,start,end,x,y,bend,rotation,blendInFrames,blendOutFrames");Contact v=new Contact();v.hip=jointIndex(r,c.getString("hip"));v.knee=jointIndex(r,c.getString("knee"));v.ankle=jointIndex(r,c.getString("ankle"));if(r.joints.get(v.knee).parent!=v.hip||r.joints.get(v.ankle).parent!=v.knee)throw bad("IK requires a direct hip-knee-ankle chain");int[] range=range(c);v.start=range[0];v.end=range[1];
                v.blendIn=c.has("blendInFrames")?(int)SketchDocument.integer(c,"blendInFrames",0,12):0;
                v.blendOut=c.has("blendOutFrames")?(int)SketchDocument.integer(c,"blendOutFrames",0,12):0;
                if(v.start-v.blendIn<0||v.end+v.blendOut>=frames)
                    throw bad("Foot-contact blends must stay within the animation timeline");
                v.x=SketchDocument.number(c,"x",0,1)*width;v.y=SketchDocument.number(c,"y",0,1)*height;v.bend=c.has("bend")?(int)SketchDocument.integer(c,"bend",-1,1):1;if(v.bend==0)throw bad("IK bend must be -1 or 1");v.hasRotation=c.has("rotation");if(v.hasRotation)v.rotation=SketchDocument.number(c,"rotation",-3600,3600);if(distance(r.joints.get(v.hip),r.joints.get(v.knee))<EPS||distance(r.joints.get(v.knee),r.joints.get(v.ankle))<EPS)throw bad("IK requires nonzero bone lengths");for(Contact prior:r.contacts)if(prior.end+prior.blendOut>=v.start-v.blendIn&&v.end+v.blendOut>=prior.start-prior.blendIn&&(ancestor(r,prior.hip,v.hip)||ancestor(r,v.hip,prior.hip)))throw bad("Overlapping IK contacts cannot control dependent ancestor chains");r.contacts.add(v);}}
            return r;
        }
        private void readCamera(JSONArray cc)throws Exception {
            if(cc.length()>frames)throw bad("Too many camera keys");Camera previous=null;
            for(int i=0;i<cc.length();i++){JSONObject c=cc.getJSONObject(i);SketchDocument.fields(c,"frame,x,y,zoom,rotation,interpolation");Camera v=new Camera();v.frame=(int)SketchDocument.integer(c,"frame",0,frames-1);if(previous!=null&&v.frame<=previous.frame)throw bad("Camera frames must be strictly ascending");v.interpolation=interpolation(c);v.x=c.has("x")?SketchDocument.number(c,"x",-4,4):previous==null?0:previous.x;v.y=c.has("y")?SketchDocument.number(c,"y",-4,4):previous==null?0:previous.y;v.zoom=c.has("zoom")?SketchDocument.number(c,"zoom",.01,8):previous==null?1:previous.zoom;v.rotation=c.has("rotation")?SketchDocument.number(c,"rotation",-3600,3600):previous==null?0:previous.rotation;camera.add(v);previous=v;}
        }
    }

    private static Snapshot sampleRig(Model m,Rig r,int frame)throws Exception {
        Snapshot s=new Snapshot(r.joints.size());Pose left=null,right=null;
        for(Pose p:r.poses){if(p.frame<=frame)left=p;else{right=p;break;}}
        if(left!=null){double f=right==null?0:fraction(left.interpolation,(frame-left.frame)/(double)(right.frame-left.frame));s.rootX=left.rootX+(right==null?0:(right.rootX-left.rootX)*f);s.rootY=left.rootY+(right==null?0:(right.rootY-left.rootY)*f);for(int i=0;i<s.local.length;i++)s.local[i]=left.rotations[i]+(right==null?0:(right.rotations[i]-left.rotations[i])*f);}
        forward(m,r,s);
        for(Contact contact:r.contacts) {
            boolean locked=frame>=contact.start&&frame<=contact.end;
            if(frame<contact.start-contact.blendIn||frame>contact.end+contact.blendOut)continue;
            double strength=1;
            if(frame<contact.start){
                strength=(frame-(contact.start-contact.blendIn))/(double)contact.blendIn;
            }else if(frame>contact.end){
                strength=(contact.end+contact.blendOut-frame)/(double)contact.blendOut;
            }
            strength=Math.max(0,Math.min(1,strength));
            strength=strength*strength*(3-2*strength);
            if(strength<=EPS)continue;
            Joint hip=r.joints.get(contact.hip),knee=r.joints.get(contact.knee),ankle=r.joints.get(contact.ankle);
            double upper=distance(hip,knee),lower=distance(knee,ankle);
            double dx=contact.x-s.x[contact.hip],dy=contact.y-s.y[contact.hip],distance=Math.hypot(dx,dy);
            double max=upper+lower,min=Math.abs(upper-lower);
            if(locked&&(distance>max+1e-6||distance<min-1e-6||distance<EPS))
                throw bad("UNREACHABLE_IK_TARGET at frame "+frame+" for "+r.id+":"+ankle.id);
            // During ease-in/out, use the closest reachable target. Hard plant frames still
            // reject an unreachable point, so easing can never disguise a sliding foot.
            if(!locked){
                if(distance<EPS)continue;
                double radius=Math.max(min+1e-4,Math.min(max-1e-4,distance));
                double scale=radius/distance;dx*=scale;dy*=scale;distance=radius;
            }
            double originalHip=s.local[contact.hip],originalKnee=s.local[contact.knee],originalAnkle=s.local[contact.ankle];
            double cosine=Math.max(-1,Math.min(1,(upper*upper+distance*distance-lower*lower)/(2*upper*distance)));
            double worldUpper=Math.toDegrees(Math.atan2(dy,dx)+contact.bend*Math.acos(cosine)-Math.atan2(knee.y-hip.y,knee.x-hip.x));
            s.local[contact.hip]=worldUpper-(hip.parent<0?0:s.angle[hip.parent]);forward(m,r,s);
            double targetX=s.x[contact.hip]+dx,targetY=s.y[contact.hip]+dy;
            double worldLower=Math.toDegrees(Math.atan2(targetY-s.y[contact.knee],targetX-s.x[contact.knee])-Math.atan2(ankle.y-knee.y,ankle.x-knee.x));
            s.local[contact.knee]=worldLower-s.angle[contact.hip];forward(m,r,s);
            if(contact.hasRotation){s.local[contact.ankle]=contact.rotation-s.angle[contact.knee];forward(m,r,s);}
            if(strength<1-EPS){
                s.local[contact.hip]=originalHip+(s.local[contact.hip]-originalHip)*strength;
                s.local[contact.knee]=originalKnee+(s.local[contact.knee]-originalKnee)*strength;
                if(contact.hasRotation)s.local[contact.ankle]=originalAnkle+(s.local[contact.ankle]-originalAnkle)*strength;
                forward(m,r,s);
            }
        }
        for(Contact contact:r.contacts)if(frame>=contact.start&&frame<=contact.end){
            double error=Math.hypot(s.x[contact.ankle]-contact.x,s.y[contact.ankle]-contact.y);
            if(error>1e-5)throw bad("UNSATISFIED_IK_CONTACT at frame "+frame);
            JSONObject diagnostic=new JSONObject().put("rigId",r.id).put("jointId",r.joints.get(contact.ankle).id).put("targetX",contact.x/m.width).put("targetY",contact.y/m.height).put("errorPixels",error);if(contact.hasRotation)diagnostic.put("rotationErrorDegrees",Math.abs(s.angle[contact.ankle]-contact.rotation));s.contactErrors.put(diagnostic);
        }
        return s;
    }
    private static void forward(Model m,Rig r,Snapshot s) {
        boolean[] done=new boolean[r.joints.size()];int remaining=done.length;
        while(remaining>0)for(int i=0;i<done.length;i++)if(!done[i]){Joint j=r.joints.get(i);if(j.parent>=0&&!done[j.parent])continue;
            if(j.parent<0){s.x[i]=j.x+s.rootX*m.width;s.y[i]=j.y+s.rootY*m.height;s.angle[i]=s.local[i];}
            else{Joint parent=r.joints.get(j.parent);double a=Math.toRadians(s.angle[j.parent]),dx=j.x-parent.x,dy=j.y-parent.y;s.x[i]=s.x[j.parent]+Math.cos(a)*dx-Math.sin(a)*dy;s.y[i]=s.y[j.parent]+Math.sin(a)*dx+Math.cos(a)*dy;s.angle[i]=s.angle[j.parent]+s.local[i];}
            done[i]=true;remaining--;
        }
    }
    private static JSONObject jointTransform(Model m,Rig rig,Snapshot s,int index,JSONObject base)throws Exception {
        Joint joint=rig.joints.get(index);double ax=base.getDouble("anchorX")*m.width,ay=base.getDouble("anchorY")*m.height;
        double dx=ax+base.getDouble("x")*m.width-joint.x,dy=ay+base.getDouble("y")*m.height-joint.y,a=Math.toRadians(s.angle[index]);
        JSONObject out=SketchDocument.copy(base).put("x",(s.x[index]+Math.cos(a)*dx-Math.sin(a)*dy-ax)/m.width)
                .put("y",(s.y[index]+Math.sin(a)*dx+Math.cos(a)*dy-ay)/m.height).put("rotation",base.getDouble("rotation")+s.angle[index]);
        SketchDocument.validateTransform(out);return out;
    }
    private static JSONObject deform(Model m,Rig rig,Snapshot s,Binding binding,JSONObject source,int frame)throws Exception {
        JSONObject cel=SketchDocument.copy(source).put("id","deform_"+SketchDocument.sha256(binding.objectId.getBytes(StandardCharsets.UTF_8)).substring(0,16)+"_"+frame)
                .put("frame",frame).put("exposure",1).put("transform",SketchDocument.transform()).put("keys",new JSONArray());
        JSONObject base=SketchDocument.transformAt(source,Math.min(frame,m.sourceFrames-1));cel.getJSONObject("transform").put("opacity",base.getDouble("opacity"));int vertex=0;
        if("path".equals(source.getString("kind"))) {
            JSONArray commands=cel.getJSONArray("commands");for(int i=0;i<commands.length();i++){JSONArray command=commands.getJSONArray(i);for(int j=1;j<command.length();j+=2){double[] p=weighted(m,rig,s,base,command.getDouble(j),command.getDouble(j+1),binding.weights.get(vertex++));command.put(j,p[0]).put(j+1,p[1]);}}
        } else {
            JSONArray points=cel.getJSONArray("points");for(int i=0;i<points.length();i++){JSONArray point=points.getJSONArray(i);double[] p=weighted(m,rig,s,base,point.getDouble(0),point.getDouble(1),binding.weights.get(vertex++));point.put(0,p[0]).put(1,p[1]);}
        }
        return cel;
    }
    private static double[] weighted(Model m,Rig rig,Snapshot s,JSONObject base,double x,double y,Map<Integer,Double> weights)throws Exception {
        double[] point=applyTransform(base,x*m.width,y*m.height,m.width,m.height);double outX=0,outY=0;
        for(Map.Entry<Integer,Double> e:weights.entrySet()){int i=e.getKey();Joint j=rig.joints.get(i);double a=Math.toRadians(s.angle[i]),dx=point[0]-j.x,dy=point[1]-j.y;outX+=e.getValue()*(s.x[i]+Math.cos(a)*dx-Math.sin(a)*dy);outY+=e.getValue()*(s.y[i]+Math.sin(a)*dx+Math.cos(a)*dy);}
        double nx=outX/m.width,ny=outY/m.height;if(nx<0||nx>1||ny<0||ny>1)throw bad("Deformed vector geometry leaves normalized canvas; reframe the prepared rig");return new double[]{nx,ny};
    }
    private static Camera sampleCamera(Model m,int frame) {
        Camera left=null,right=null;for(Camera c:m.camera){if(c.frame<=frame)left=c;else{right=c;break;}}
        Camera out=new Camera();if(left==null)return out;double f=right==null?0:fraction(left.interpolation,(frame-left.frame)/(double)(right.frame-left.frame));out.x=left.x+(right==null?0:(right.x-left.x)*f);out.y=left.y+(right==null?0:(right.y-left.y)*f);out.zoom=left.zoom+(right==null?0:(right.zoom-left.zoom)*f);out.rotation=left.rotation+(right==null?0:(right.rotation-left.rotation)*f);return out;
    }
    private static JSONObject cameraTransform(Model m,Camera c,JSONObject base)throws Exception {
        double ax=base.getDouble("anchorX")*m.width,ay=base.getDouble("anchorY")*m.height;
        double[] p=applyCamera(m,c,ax+base.getDouble("x")*m.width,ay+base.getDouble("y")*m.height);
        JSONObject out=SketchDocument.copy(base).put("x",(p[0]-ax)/m.width).put("y",(p[1]-ay)/m.height)
                .put("rotation",base.getDouble("rotation")+c.rotation).put("scaleX",base.getDouble("scaleX")*c.zoom).put("scaleY",base.getDouble("scaleY")*c.zoom);
        SketchDocument.validateTransform(out);return out;
    }
    private static double[] applyCamera(Model m,Camera c,double x,double y){double a=Math.toRadians(c.rotation),dx=(x-m.width*.5)*c.zoom,dy=(y-m.height*.5)*c.zoom;return new double[]{m.width*(.5+c.x)+Math.cos(a)*dx-Math.sin(a)*dy,m.height*(.5+c.y)+Math.sin(a)*dx+Math.cos(a)*dy};}
    private static double[] applyTransform(JSONObject t,double x,double y,int width,int height)throws Exception{double ax=t.getDouble("anchorX")*width,ay=t.getDouble("anchorY")*height,dx=(x-ax)*t.getDouble("scaleX"),dy=(y-ay)*t.getDouble("scaleY"),a=Math.toRadians(t.getDouble("rotation"));return new double[]{ax+t.getDouble("x")*width+Math.cos(a)*dx-Math.sin(a)*dy,ay+t.getDouble("y")*height+Math.sin(a)*dx+Math.cos(a)*dy};}
    private static final class Reduction {JSONArray keys;double error;Reduction(JSONArray keys,double error){this.keys=keys;this.error=error;}}
    /** Ramer-Douglas-Peucker over fully sampled affine transforms. The AABB corners bound every
     * prepared vertex/control point, and error is checked at every integer output frame. */
    private static Reduction reduceKeys(Model m,JSONArray dense,double[] bounds,double tolerance)throws Exception {
        if(dense.length()<2)return new Reduction(dense,0);
        boolean[] keep=new boolean[dense.length()];keep[0]=true;keep[keep.length-1]=true;
        java.util.ArrayDeque<int[]> pending=new java.util.ArrayDeque<>();pending.add(new int[]{0,keep.length-1});
        while(!pending.isEmpty()){
            if(Thread.currentThread().isInterrupted())throw new InterruptedException("Animation key reduction cancelled");
            int[] span=pending.removeLast();int a=span[0],b=span[1];if(b-a<2)continue;int worst=-1;double worstScore=1;
            JSONObject left=dense.getJSONObject(a),right=dense.getJSONObject(b);
            for(int i=a+1;i<b;i++){
                double f=(dense.getJSONObject(i).getInt("frame")-left.getInt("frame"))/(double)(right.getInt("frame")-left.getInt("frame"));
                JSONObject actual=dense.getJSONObject(i).getJSONObject("transform"),approx=interpolateTransform(left.getJSONObject("transform"),right.getJSONObject("transform"),f);
                double geometric=transformError(m,actual,approx,bounds),alpha=Math.abs(actual.getDouble("opacity")-approx.getDouble("opacity"));
                double score=Math.max(geometric/Math.max(1e-9,tolerance),alpha*1024);
                if(score>worstScore){worstScore=score;worst=i;}
            }
            if(worst>=0){keep[worst]=true;pending.add(new int[]{a,worst});pending.add(new int[]{worst,b});}
        }
        JSONArray reduced=new JSONArray();double maximum=0;int last=-1;
        for(int i=0;i<keep.length;i++)if(keep[i]){
            JSONObject key=SketchDocument.copy(dense.getJSONObject(i)).put("interpolation",i==keep.length-1?"hold":"linear");reduced.put(key);
            if(last>=0){JSONObject left=dense.getJSONObject(last),right=dense.getJSONObject(i);for(int j=last+1;j<i;j++){double f=(dense.getJSONObject(j).getInt("frame")-left.getInt("frame"))/(double)(right.getInt("frame")-left.getInt("frame"));maximum=Math.max(maximum,transformError(m,dense.getJSONObject(j).getJSONObject("transform"),interpolateTransform(left.getJSONObject("transform"),right.getJSONObject("transform"),f),bounds));}}
            last=i;
        }
        return new Reduction(reduced,maximum);
    }
    private static JSONObject interpolateTransform(JSONObject a,JSONObject b,double fraction)throws Exception {
        JSONObject out=new JSONObject();for(String key:new String[]{"x","y","scaleX","scaleY","rotation","anchorX","anchorY","opacity"})out.put(key,a.getDouble(key)+(b.getDouble(key)-a.getDouble(key))*fraction);return out;
    }
    private static double transformError(Model m,JSONObject a,JSONObject b,double[] bounds)throws Exception {
        double max=0;for(int xi=0;xi<2;xi++)for(int yi=0;yi<2;yi++){double x=bounds[xi==0?0:2],y=bounds[yi==0?1:3];double[] expected=applyTransform(a,x,y,m.width,m.height),actual=applyTransform(b,x,y,m.width,m.height);max=Math.max(max,Math.hypot(expected[0]-actual[0],expected[1]-actual[1]));}return max;
    }
    private static double[] geometryBounds(Model m,JSONObject object)throws Exception {
        double minX=Double.POSITIVE_INFINITY,minY=Double.POSITIVE_INFINITY,maxX=Double.NEGATIVE_INFINITY,maxY=Double.NEGATIVE_INFINITY;
        String kind=object.getString("kind");
        if("path".equals(kind)){JSONArray commands=object.getJSONArray("commands");for(int i=0;i<commands.length();i++){JSONArray c=commands.getJSONArray(i);for(int j=1;j<c.length();j+=2){double x=c.getDouble(j)*m.width,y=c.getDouble(j+1)*m.height;minX=Math.min(minX,x);maxX=Math.max(maxX,x);minY=Math.min(minY,y);maxY=Math.max(maxY,y);}}}
        else if(object.has("points")){JSONArray points=object.getJSONArray("points");for(int i=0;i<points.length();i++){JSONArray p=points.getJSONArray(i);double x=p.getDouble(0)*m.width,y=p.getDouble(1)*m.height;minX=Math.min(minX,x);maxX=Math.max(maxX,x);minY=Math.min(minY,y);maxY=Math.max(maxY,y);}}
        else{JSONObject bounds=object.getJSONObject("bounds");minX=bounds.getDouble("x")*m.width;minY=bounds.getDouble("y")*m.height;maxX=minX+bounds.getDouble("width")*m.width;maxY=minY+bounds.getDouble("height")*m.height;}
        double pad=object.getDouble("width")*Math.max(m.width,m.height);return new double[]{minX-pad,minY-pad,maxX+pad,maxY+pad};
    }
    private static double maxLayerScale(Model m,String layerId)throws Exception {
        double max=1;JSONObject layer=SketchDocument.layer(m.source,layerId);
        for(int frame=0;frame<m.frames;frame++){JSONObject transform=SketchDocument.transformAt(layer,Math.min(frame,m.sourceFrames-1));double scale=Math.max(transform.getDouble("scaleX"),transform.getDouble("scaleY"));if(m.cameraLayers.contains(layerId))scale*=sampleCamera(m,frame).zoom;max=Math.max(max,scale);}return max;
    }
    private static JSONArray clippedKeys(JSONObject object,int frames)throws Exception{JSONArray out=new JSONArray(),keys=object.getJSONArray("keys");for(int i=0;i<keys.length();i++){JSONObject key=keys.getJSONObject(i);if(key.getInt("frame")<frames)out.put(SketchDocument.copy(key));}return out;}
    private static int geometryPoints(JSONObject object)throws Exception {String kind=object.getString("kind");if("path".equals(kind)){int points=0;JSONArray cc=object.getJSONArray("commands");for(int i=0;i<cc.length();i++)points+=(cc.getJSONArray(i).length()-1)/2;return points;}return object.has("points")?object.getJSONArray("points").length():0;}
    private static String uniqueId(Model m,String base){String id=base;int suffix=0;while(!m.reservedIds.add(id))id=base+"_"+(++suffix);return id;}
    private static boolean explicitlyVisible(Model m,String objectId,int frame)throws Exception {
        JSONObject layer=SketchDocument.layer(m.source,m.owners.get(objectId));if(!layer.getBoolean("visible")||layer.getDouble("opacity")<=0)return false;
        int sourceFrame=Math.min(frame,m.sourceFrames-1);
        return SketchDocument.transformAt(layer,sourceFrame).getDouble("opacity")>0&&SketchDocument.transformAt(m.objects.get(objectId),sourceFrame).getDouble("opacity")>0;
    }
    private static JSONArray optionalArray(JSONObject object,String key)throws Exception{if(!object.has(key))return new JSONArray();Object value=object.get(key);if(!(value instanceof JSONArray))throw bad(key+" must be an array");return (JSONArray)value;}
    private static boolean ancestor(Rig rig,int possible,int joint){for(int cursor=joint;cursor>=0;cursor=rig.joints.get(cursor).parent)if(cursor==possible)return true;return false;}
    private static void requireIdentityLayer(JSONObject t)throws Exception{if(Math.abs(t.getDouble("x"))>EPS||Math.abs(t.getDouble("y"))>EPS||Math.abs(t.getDouble("scaleX")-1)>EPS||Math.abs(t.getDouble("scaleY")-1)>EPS||Math.abs(t.getDouble("rotation"))>EPS)throw bad("Bound artwork requires identity prepared layer transforms; apply its layer transform to the artwork before rig mapping");}
    private static int jointIndex(Rig r,String id){Integer i=r.index.get(id);if(i==null)throw bad("Unknown joint: "+id);return i;}
    private static String interpolation(JSONObject o)throws Exception{String mode=o.optString("interpolation","linear");if(!java.util.Arrays.asList("linear","smooth","hold").contains(mode))throw bad("Interpolation must be linear, smooth or hold");return mode;}
    private static double fraction(String mode,double f){return "hold".equals(mode)?0:"smooth".equals(mode)?f*f*(3-2*f):f;}
    private static double distance(Joint a,Joint b){return Math.hypot(a.x-b.x,a.y-b.y);}
    private static String hash(JSONObject o)throws Exception{return SketchDocument.sha256(SketchDocument.canonical(o).getBytes(StandardCharsets.UTF_8));}
    private static String truncate(String s,int n){return s.length()<=n?s:s.substring(0,n);}
    private static IllegalArgumentException bad(String message){return new IllegalArgumentException("INVALID_SKETCH_ANIMATION: "+message);}
}
