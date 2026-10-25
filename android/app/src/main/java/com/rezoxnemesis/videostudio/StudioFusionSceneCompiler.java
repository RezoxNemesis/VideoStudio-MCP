package com.rezoxnemesis.videostudio;

import org.json.JSONArray;
import org.json.JSONObject;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * Native, typed, bounded Fusion-inspired scene compiler.
 *
 * One declarative user/agent command compiles media-backed titles, images,
 * source-free vector shapes, masks, nonrendering parent groups, a global 2D
 * camera, serial GPU color nodes and PCM audio FX into one EDITABLE PROJECT.
 * All real pixels/samples are produced by previously implemented native
 * Media3 effects. No external daemon, Gallery scan, shell or arbitrary JS.
 *
 * Not a general Fusion graph, Adobe precomposition, 3D tracked camera or
 * arbitrary plugin system. Original assets and source project are untouched.
 */
public final class StudioFusionSceneCompiler {
    public static final int MAX_NODES=32;
    private static final Set<String> OUTER=Set.of(
            "projectId","expectedUpdatedAt","operationId","name","nodes");
    private static final Set<String> TYPES=Set.of(
            "title","shape","vfx","image","mask","motion_group","camera2d",
            "color_grade","audio_fx");
    private static final Set<String> VISUAL=Set.of(
            "id","type","text","shapeKind","style","assetId","startMs",
            "durationMs","fontSize","color","strokeColor","strokeWidth",
            "position","textAlign","x","y","width","height","rotation",
            "opacity","motionKeyframes","mask","glyphAnimation");
    private static final Set<String> VFX_FIELDS=Set.of("id","type","kind","particleCount","seed","speed","particleSize","startMs","durationMs","x","y","width","height","rotation","opacity","color","motionKeyframes");
    private static final Set<String> CAMERA=Set.of("id","type","motionKeyframes");
    private static final Set<String> GROUP=Set.of(
            "id","type","children","motionKeyframes");
    private static final Set<String> MASK=Set.of(
            "id","type","target","mask");
    private static final Set<String> GRADE=Set.of(
            "id","type","clipIds","colorNodes");
    private static final Set<String> AUDIO=Set.of(
            "id","type","audioLayerIds","fx");
    private StudioFusionSceneCompiler(){}

    public static JSONObject capabilities()throws Exception {
        return new JSONObject()
                .put("schema","videostudio.fusion-scenes.v1")
                .put("minimumNativeVersion","4.10.36")
                .put("maxNodes",MAX_NODES)
                .put("supportedNodes",new JSONArray().put("title").put("shape").put("vfx")
                        .put("image").put("mask").put("motion_group")
                        .put("camera2d").put("color_grade").put("audio_fx"))
                .put("planAction","fusion_scene_plan")
                .put("applyAction","fusion_scene_apply")
                .put("originalOwnedMediaOnly",true)
                .put("createsIndependentEditableProject",true)
                .put("singleCommandMultipleNativeEffects",true)
                .put("sourceMediaUnchanged",true)
                .put("nativeRenderer","Media3 native existing composer")
                .put("arbitraryThirdPartyPlugins",false)
                .put("nativeGlyphTitles",true)
                .put("glyphModes",new JSONArray().put("scatter").put("assemble").put("cascade").put("wave"))
                .put("glyphRequiresCandidateApk",true)
                .put("nativeParticleVfx",true)
                .put("particleVfxKinds",new JSONArray().put("rain").put("sparks").put("energy").put("embers").put("snow").put("confetti"))
                .put("particleVfxMaxLayers",4)
                .put("particleVfxRequiresCandidateApk",true)
                .put("real3DTracking",false)
                .put("arbitraryNestedPrecomp",false)
                .put("onDeviceRenderedPreviewVerified",false)
                .put("galleryAccess",false);
    }

    public static JSONObject plan(ProjectStore.Project source,JSONObject request)throws Exception {
        Prepared built=prepare(source,request);
        return receipt(built,source,true,false);
    }

    public static JSONObject apply(ProjectStore store,JSONObject request)throws Exception {
        if(store==null||request==null)
            throw new IllegalArgumentException("Owned project and Fusion scene program are required");
        String sourceId=request.optString("projectId","");
        long expected=StudioClipKeyframes.integer(request,"expectedUpdatedAt",0,9_007_199_254_740_991L);
        return store.guardedProject(sourceId,expected,current->{
            Prepared built=prepare(current,request);
            ProjectStore.Project prior=store.get(built.copy.id);
            if(prior!=null){
                if(!built.witness.equals(prior.sourcePrompt))
                    throw new IllegalStateException("FUSION_OPERATION_CONFLICT");
                return receipt(built,current,false,true);
            }
            store.save(built.copy);
            JSONObject result=receipt(built,current,false,false);
            result.put("outputUpdatedAt",built.copy.updatedAt);
            return result;
        });
    }

    private static JSONObject receipt(Prepared result,ProjectStore.Project source,
                                      boolean dryRun,boolean reused)throws Exception {
        return new JSONObject()
                .put("ok",true).put("schema","videostudio.fusion-scene-receipt.v1")
                .put("sourceProjectId",source.id)
                .put("sourceUpdatedAt",source.updatedAt)
                .put("outputProjectId",result.copy.id)
                .put("nodeCount",result.nodeCount)
                .put("newVisualLayers",result.createdLayers)
                .put("outputLayerCount",result.copy.studioLayers.size())
                .put("clipCount",result.copy.clips.size())
                .put("affectedVideoClips",result.gradedClips)
                .put("affectedAudioLanes",result.audioLanes)
                .put("dryRun",dryRun).put("saved",!dryRun)
                .put("reused",reused)
                .put("sourceProjectUnchanged",true)
                .put("sourceMediaUnchanged",true)
                .put("rendered",false).put("previewVerified",false)
                .put("galleryAccess",false);
    }

    private static Prepared prepare(ProjectStore.Project source,JSONObject request)
            throws Exception {
        if(source==null||request==null)
            throw new IllegalArgumentException("Original project is required");
        java.util.Iterator<String> keys=request.keys();
        while(keys.hasNext())if(!OUTER.contains(keys.next()))
            throw new IllegalArgumentException("Unsupported Fusion program field");
        if(!source.id.equals(request.optString("projectId","")))
            throw new IllegalArgumentException("Fusion project identity mismatch");
        long revision=StudioClipKeyframes.integer(request,"expectedUpdatedAt",0,9_007_199_254_740_991L);
        if(revision!=source.updatedAt)throw new IllegalStateException("SOURCE_PROJECT_CHANGED");
        if(source.clips.isEmpty())throw new IllegalArgumentException("An imported base timeline is required");
        String op=request.optString("operationId","");
        if(!op.matches("[A-Za-z0-9_-]{8,64}"))
            throw new IllegalArgumentException("Stable operationId must contain 8..64 safe characters");
        JSONArray graph=request.optJSONArray("nodes");
        if(graph==null||graph.length()<1||graph.length()>MAX_NODES)
            throw new IllegalArgumentException("Scene needs 1..32 typed original nodes");
        String outputId=UUID.nameUUIDFromBytes(
                ("videostudio/fusion/"+source.id+"/"+op).getBytes(StandardCharsets.UTF_8)).toString();
        ProjectStore.Project copy=ProjectStore.Project.fromJson(source.toJson());
        copy.id=outputId;
        String label=request.optString("name","Fusion Scene").trim();
        if(label.isEmpty()||label.length()>90)
            throw new IllegalArgumentException("Scene name must be 1..90 characters");
        copy.name=source.name+" — "+label;
        if(copy.name.length()>150)copy.name=copy.name.substring(0,150);
        copy.latestExportUri="";copy.latestExportName="";copy.latestExportAt=0;
        String witness=fingerprint(SketchDocument.canonical(request)+":"+
                source.id+":"+source.updatedAt);
        copy.sourcePrompt="videostudio.fusion-scene.v1:"+witness;

        LinkedHashMap<String,StudioLayer> named=new LinkedHashMap<>();
        int created=0,grades=0,audioChanged=0;
        int cameraCount=0;
        for(StudioLayer layer:copy.studioLayers)
            if(StudioLayer.CAMERA.equals(layer.type))cameraCount++;
        for(int i=0;i<graph.length();i++){
            JSONObject node=graph.optJSONObject(i);
            if(node==null)throw new IllegalArgumentException("Fusion nodes must be objects");
            String alias=node.optString("id","");
            if(!alias.matches("[A-Za-z][A-Za-z0-9_-]{0,31}")||named.containsKey(alias))
                throw new IllegalArgumentException("Node IDs must be unique safe aliases");
            String type=node.optString("type","");
            if(!TYPES.contains(type))throw new IllegalArgumentException("Unsupported node type "+type);
            Set<String> fields=Set.of();
            switch(type){
                case "title":case "shape":case "image":fields=VISUAL;break;
                case "vfx":fields=VFX_FIELDS;break;
                case "mask":fields=MASK;break;
                case "motion_group":fields=GROUP;break;
                case "camera2d":fields=CAMERA;break;
                case "color_grade":fields=GRADE;break;
                case "audio_fx":fields=AUDIO;break;
            }
            java.util.Iterator<String> nodeFields=node.keys();
            while(nodeFields.hasNext())if(!fields.contains(nodeFields.next()))
                throw new IllegalArgumentException("Unsupported field for "+type);
            String id=UUID.nameUUIDFromBytes(
                    (outputId+"/"+alias).getBytes(StandardCharsets.UTF_8)).toString();
            switch(type){
                case "vfx":{
                    StudioLayer layer=new StudioLayer();
                    layer.id=id;layer.type=StudioLayer.VFX;
                    layer.startMs=whole(node,"startMs",0,0,86_399_900);
                    layer.durationMs=whole(node,"durationMs",2500,100,86_400_000);
                    layer.vfxKind=node.optString("kind","rain");
                    layer.particleCount=(int)whole(node,"particleCount",32,1,96);
                    layer.particleSeed=(int)whole(node,"seed",7331,0,1_000_000);
                    layer.particleSpeed=(float)scalar(node,"speed",1,.1,6);
                    layer.particleSize=(float)scalar(node,"particleSize",.008,.001,.05);
                    applyAppearance(layer,node);
                    if(node.has("motionKeyframes"))layer.motionKeyframes=keys(node);
                    layer.validate();copy.studioLayers.add(layer);
                    named.put(alias,layer);created++;break;
                }
                case "shape":{
                    if(node.has("glyphAnimation"))throw new IllegalArgumentException("Glyph motion belongs to TITLE nodes");
                    long start=whole(node,"startMs",0,0,86_399_900);
                    long duration=whole(node,"durationMs",2500,100,86_400_000);
                    String kind=node.optString("shapeKind","ring");
                    String style=node.optString("style","drift");
                    StudioLayer layer=StudioShapePresets.create(kind,style,start,duration);
                    layer.id=id;
                    applyAppearance(layer,node);
                    if(node.has("motionKeyframes"))layer.motionKeyframes=keys(node);
                    layer.validate();copy.studioLayers.add(layer);
                    named.put(alias,layer);created++;break;
                }
                case "title":{
                    StudioLayer layer=new StudioLayer();layer.id=id;layer.type=StudioLayer.TITLE;
                    Object text=node.opt("text");
                    if(!(text instanceof String))
                        throw new IllegalArgumentException("Title node requires authored text");
                    layer.text=(String)text;
                    layer.startMs=whole(node,"startMs",0,0,86_399_900);
                    layer.durationMs=whole(node,"durationMs",2500,100,86_400_000);
                    layer.fontSize=(int)whole(node,"fontSize",40,12,96);
                    layer.position=(int)whole(node,"position",1,0,2);
                    layer.textAlign=node.optString("textAlign","center");
                    if(node.has("color"))layer.color=argb(node,"color");
                    if(node.has("glyphAnimation")){
                        if(!(node.opt("glyphAnimation") instanceof JSONObject))
                            throw new IllegalArgumentException("Glyph animation requires a typed object");
                        layer.glyphAnimation=StudioGlyphAnimation.validate(
                                node.getJSONObject("glyphAnimation"),layer.text,layer.durationMs);
                    }
                    if(node.has("motionKeyframes"))layer.motionKeyframes=keys(node);
                    layer.validate();copy.studioLayers.add(layer);
                    named.put(alias,layer);created++;break;
                }
                case "image":{
                    if(node.has("glyphAnimation"))throw new IllegalArgumentException("Glyph motion belongs to TITLE nodes");
                    Object own=node.opt("assetId");
                    if(!(own instanceof String))
                        throw new IllegalArgumentException("Image node needs owned assetId");
                    ProjectStore.Asset asset=copy.asset((String)own);
                    if(asset==null||asset.mime==null||!asset.mime.startsWith("image/")
                            ||asset.uri==null||asset.uri.isEmpty())
                        throw new IllegalArgumentException("Image node may reference only registered owned images");
                    StudioLayer layer=new StudioLayer();
                    layer.id=id;layer.type=StudioLayer.IMAGE;layer.assetId=(String)own;
                    layer.startMs=whole(node,"startMs",0,0,86_399_900);
                    layer.durationMs=whole(node,"durationMs",2500,100,86_400_000);
                    applyAppearance(layer,node);
                    if(node.has("motionKeyframes"))layer.motionKeyframes=keys(node);
                    layer.validate();copy.studioLayers.add(layer);
                    named.put(alias,layer);created++;break;
                }
                case "mask":{
                    StudioLayer target=target(named,node,"target");
                    if(!StudioLayer.IMAGE.equals(target.type)&&!StudioLayer.SHAPE.equals(target.type))
                        throw new IllegalArgumentException("Masks attach only to previously authored shapes/images");
                    JSONObject mask=node.optJSONObject("mask");
                    target.mask=StudioMaskRasterizer.validate(mask);
                    target.validate();
                    named.put(alias,target);break;
                }
                case "motion_group":{
                    JSONArray children=node.optJSONArray("children");
                    if(children==null||children.length()<1||children.length()>8)
                        throw new IllegalArgumentException("Motion group needs 1..8 authored child IDs");
                    StudioLayer group=new StudioLayer();
                    group.id=id;group.type=StudioLayer.GROUP;
                    long start=Long.MAX_VALUE,end=Long.MIN_VALUE;
                    ArrayList<StudioLayer> linked=new ArrayList<>();
                    for(int j=0;j<children.length();j++){
                        Object raw=children.opt(j);
                        if(!(raw instanceof String))
                            throw new IllegalArgumentException("Group children require node aliases");
                        StudioLayer child=named.get((String)raw);
                        if(child==null||!Set.of(StudioLayer.TITLE,StudioLayer.SHAPE,
                                StudioLayer.IMAGE).contains(child.type)||linked.contains(child)
                                ||!child.parentId.isEmpty())
                            throw new IllegalArgumentException("Group links must be unique prior visual nodes");
                        linked.add(child);
                        start=Math.min(start,child.startMs);
                        end=Math.max(end,child.startMs+child.durationMs);
                    }
                    group.startMs=start;group.durationMs=end-start;
                    if(node.has("motionKeyframes"))group.motionKeyframes=keys(node);
                    group.validate();
                    copy.studioLayers.add(group);created++;
                    for(StudioLayer child:linked)child.parentId=id;
                    named.put(alias,group);break;
                }
                case "camera2d":{
                    if(cameraCount++>0)
                        throw new IllegalArgumentException("Only one composition-wide native camera can exist");
                    StudioLayer layer=new StudioLayer();layer.id=id;layer.type=StudioLayer.CAMERA;
                    layer.startMs=0;layer.durationMs=copy.outputDurationMs();
                    if(node.has("motionKeyframes"))layer.motionKeyframes=keys(node);
                    layer.validate();copy.studioLayers.add(layer);
                    named.put(alias,layer);created++;break;
                }
                case "color_grade":{
                    JSONArray targets=node.optJSONArray("clipIds");
                    JSONArray color=node.optJSONArray("colorNodes");
                    if(targets==null||targets.length()<1||targets.length()>64)
                        throw new IllegalArgumentException("Grade needs 1..64 known clip IDs");
                    JSONArray normalized=StudioResolveColorGraph.validate(color);
                    HashSet<String> chosen=new HashSet<>();
                    for(int j=0;j<targets.length();j++){
                        Object target=targets.opt(j);
                        if(!(target instanceof String)||!chosen.add((String)target))
                            throw new IllegalArgumentException("Duplicate/invalid grade target");
                    }
                    int hits=0;
                    for(ProjectStore.Clip clip:copy.clips){
                        if(!chosen.contains(clip.id))continue;
                        if(clip.effects==null)clip.effects=new JSONObject();
                        clip.effects.put("resolveColorNodes",new JSONArray(normalized.toString()));
                        StudioPrecisionEdits.validateClip(clip,copy.asset(clip.assetId));
                        hits++;
                    }
                    if(hits!=chosen.size())throw new IllegalArgumentException("Unknown owned clip in color node");
                    grades+=hits;named.put(alias,new StudioLayer());break;
                }
                case "audio_fx":{
                    JSONArray targets=node.optJSONArray("audioLayerIds");
                    if(targets==null||targets.length()<1||targets.length()>16)
                        throw new IllegalArgumentException("Audio node needs 1..16 owned audio lane IDs");
                    JSONObject fx=StudioFairlightSettings.validate(node.optJSONObject("fx"));
                    HashSet<String> wanted=new HashSet<>();
                    for(int j=0;j<targets.length();j++){
                        Object target=targets.opt(j);
                        if(!(target instanceof String)||!wanted.add((String)target))
                            throw new IllegalArgumentException("Audio node needs distinct owned IDs");
                    }
                    int found=0;
                    for(StudioLayer lane:copy.studioLayers){
                        if(!wanted.contains(lane.id))continue;
                        if(!StudioLayer.AUDIO.equals(lane.type))
                            throw new IllegalArgumentException("Audio FX only on actual audio tracks");
                        lane.fairlightFx=new JSONObject(fx.toString());found++;
                    }
                    if(found!=wanted.size())throw new IllegalArgumentException("Unknown audio source lane");
                    audioChanged+=found;named.put(alias,new StudioLayer());break;
                }
            }
        }
        StudioLayerContract.fromJson(copy,StudioLayerContract.toJson(copy.studioLayers));
        for(ProjectStore.Clip clip:copy.clips)
            StudioPrecisionEdits.validateClip(clip,copy.asset(clip.assetId));
        return new Prepared(copy,witness,graph.length(),created,grades,audioChanged);
    }

    private static void applyAppearance(StudioLayer layer,JSONObject node)throws Exception {
        layer.x=(float)scalar(node,"x",layer.x,0,1);
        layer.y=(float)scalar(node,"y",layer.y,0,1);
        layer.width=(float)scalar(node,"width",layer.width,.01,2);
        layer.height=(float)scalar(node,"height",layer.height,.01,2);
        layer.rotation=(float)scalar(node,"rotation",layer.rotation,-360,360);
        layer.opacity=(float)scalar(node,"opacity",layer.opacity,0,1);
        if(node.has("color"))layer.color=argb(node,"color");
        if(node.has("strokeColor"))layer.strokeColor=argb(node,"strokeColor");
        layer.strokeWidth=(float)scalar(node,"strokeWidth",layer.strokeWidth,0,.20);
        if(node.has("mask"))layer.mask=StudioMaskRasterizer.validate(node.optJSONObject("mask"));
    }

    private static StudioLayer target(LinkedHashMap<String,StudioLayer> nodes,
                                       JSONObject node,String key){
        Object ref=node.opt(key);
        if(!(ref instanceof String)||!nodes.containsKey(ref))
            throw new IllegalArgumentException("Fusion target must be a prior authored node alias");
        return nodes.get((String)ref);
    }
    private static int argb(JSONObject o,String key){
        Object raw=o.opt(key);
        if(!(raw instanceof Number))
            throw new IllegalArgumentException("ARGB color must be a 32-bit integer");
        long l=((Number)raw).longValue();
        if(l<Integer.MIN_VALUE||l>0xffffffffL||((Number)raw).doubleValue()!=l)
            throw new IllegalArgumentException("ARGB outside integer range");
        return (int)l;
    }
    private static double scalar(JSONObject o,String key,double def,double lo,double hi){
        Object raw=o.opt(key);
        if(raw==null)return def;
        if(!(raw instanceof Number))
            throw new IllegalArgumentException(key+" must be numeric");
        double v=((Number)raw).doubleValue();
        if(!Double.isFinite(v)||v<lo||v>hi)
            throw new IllegalArgumentException(key+" outside native range");
        return v;
    }
    private static long whole(JSONObject o,String key,long def,long lo,long hi){
        Object raw=o.opt(key);
        if(raw==null)return def;
        if(!(raw instanceof Number))
            throw new IllegalArgumentException(key+" must be an integer");
        double v=((Number)raw).doubleValue();
        if(!Double.isFinite(v)||v<lo||v>hi||v!=Math.rint(v))
            throw new IllegalArgumentException(key+" must be a bounded integer");
        return (long)v;
    }
    private static JSONArray keys(JSONObject node)throws Exception {
        JSONArray keys=node.optJSONArray("motionKeyframes");
        if(keys==null)
            throw new IllegalArgumentException("Animation motionKeyframes must be an array");
        return new JSONArray(keys.toString());
    }
    private static String fingerprint(String value)throws Exception {
        byte[] bytes=MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        StringBuilder s=new StringBuilder();
        for(byte b:bytes)s.append(String.format(Locale.ROOT,"%02x",b&255));
        return s.toString();
    }
    private static final class Prepared {
        final ProjectStore.Project copy;
        final String witness;
        final int nodeCount,createdLayers,gradedClips,audioLanes;
        Prepared(ProjectStore.Project p,String witness,int nodes,int created,int grade,int audio){
            copy=p;this.witness="videostudio.fusion-scene.v1:"+witness;
            nodeCount=nodes;createdLayers=created;gradedClips=grade;audioLanes=audio;
        }
    }
}
