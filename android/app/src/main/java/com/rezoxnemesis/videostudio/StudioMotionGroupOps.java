package com.rezoxnemesis.videostudio;

import org.json.JSONArray;
import org.json.JSONObject;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * Atomic one-command edit compiler for parented 2D motion tracks.
 *
 * Select existing user-owned shapes/images/titles, move them together using
 * an original empty GROUP timeline track, or unlink them. This never creates
 * media assets or takes Gallery permissions. Uses the exact store revision
 * that the human editor also checks.
 */
public final class StudioMotionGroupOps {
    private static final Set<String> OPERATIONS=Set.of("create","animate","dissolve");
    private static final Set<String> FIELDS=Set.of("projectId","expectedUpdatedAt",
            "operation","operationId","layerIds","groupId","motionKeyframes");
    private StudioMotionGroupOps(){}

    public static JSONObject capabilities() throws Exception {
        return new JSONObject()
                .put("schema","videostudio.motion-groups.v1")
                .put("minimumNativeVersion","4.10.30")
                .put("nativeAction","motion_group_edit")
                .put("planAction","motion_group_plan")
                .put("operations",new JSONArray().put("create").put("animate").put("dissolve"))
                .put("maximumGroups",8).put("maximumChildrenPerGroup",8)
                .put("maximumLevels",1).put("maximumMotionKeys",64)
                .put("types",new JSONArray().put("image").put("shape").put("title"))
                .put("usesExistingMedia3Composition",true)
                .put("sourceMediaUnchanged",true).put("onDevicePreviewVerified",false)
                .put("galleryAccess",false).put("true3D",false);
    }

    public static JSONObject plan(ProjectStore.Project source,JSONObject request) throws Exception {
        if(source==null)throw new IllegalArgumentException("Source project is unavailable");
        return editOnCopy(source,request).put("dryRun",true)
                .put("saved",false).put("previewVerified",false);
    }

    public static JSONObject apply(ProjectStore store,JSONObject request) throws Exception {
        if(store==null||request==null)throw new IllegalArgumentException("Project and motion program required");
        if(!(request.opt("projectId") instanceof String))throw new IllegalArgumentException("Project ID required");
        long expected=StudioClipKeyframes.integer(request,"expectedUpdatedAt",
                0,9_007_199_254_740_991L);
        return store.guardedProject(request.getString("projectId"),expected,current->{
            JSONObject receipt=editOnCopy(current,request);
            ProjectStore.Project edited=ProjectStore.Project.fromJson(current.toJson());
            applyOperation(edited,request);
            // The same authoritative validator checks layer budgets, parent
            // containment, source asset provenance and unique stable IDs.
            StudioLayerContract.fromJson(edited,StudioLayerContract.toJson(edited.studioLayers));
            store.save(edited);
            return receipt.put("dryRun",false).put("saved",true)
                    .put("updatedAt",edited.updatedAt)
                    .put("previewVerified",false).put("rendered",false)
                    .put("sourceMediaUnchanged",true);
        });
    }

    /** Entire preflight is pure for plan and writes only a detached copy. */
    private static JSONObject editOnCopy(ProjectStore.Project source,JSONObject request) throws Exception {
        if(request==null)throw new IllegalArgumentException("Motion program required");
        java.util.Iterator<String> iter=request.keys();
        while(iter.hasNext())if(!FIELDS.contains(iter.next()))
            throw new IllegalArgumentException("Unknown motion group editing field");
        if(!source.id.equals(request.optString("projectId","")))
            throw new IllegalArgumentException("Project identity mismatch");
        long expected=StudioClipKeyframes.integer(request,"expectedUpdatedAt",
                0,9_007_199_254_740_991L);
        if(expected!=source.updatedAt)throw new IllegalStateException("SOURCE_PROJECT_CHANGED");
        String action=request.optString("operation","");
        if(!OPERATIONS.contains(action))throw new IllegalArgumentException("Use create, animate or dissolve");
        ProjectStore.Project copy=ProjectStore.Project.fromJson(source.toJson());
        JSONObject result=applyOperation(copy,request);
        StudioLayerContract.fromJson(copy,StudioLayerContract.toJson(copy.studioLayers));
        return result.put("ok",true).put("projectId",source.id)
                .put("expectedUpdatedAt",expected)
                .put("layerCountAfter",copy.studioLayers.size())
                .put("galleryAccess",false).put("sourceMediaUnchanged",true);
    }

    private static JSONObject applyOperation(ProjectStore.Project project,JSONObject request)
            throws Exception {
        String action=request.getString("operation");
        switch(action) {
            case "create": return create(project,request);
            case "animate": return animate(project,request);
            case "dissolve": return dissolve(project,request);
            default: throw new IllegalArgumentException("Unknown motion group operation");
        }
    }

    private static JSONObject create(ProjectStore.Project project,JSONObject request)
            throws Exception {
        String op=request.optString("operationId","");
        if(!op.matches("[A-Za-z0-9_-]{8,64}"))
            throw new IllegalArgumentException("Create requires an explicit stable operationId");
        if(request.has("groupId"))
            throw new IllegalArgumentException("Create derives group ID from operationId");
        JSONArray raw=request.optJSONArray("layerIds");
        if(raw==null||raw.length()<1||raw.length()>8)
            throw new IllegalArgumentException("Select 1..8 existing visual layers");
        String groupId=UUID.nameUUIDFromBytes(
                ("motion-group/"+project.id+"/"+op).getBytes(StandardCharsets.UTF_8)).toString();
        HashSet<String> wanted=new HashSet<>();
        for(int i=0;i<raw.length();i++){
            if(!(raw.opt(i) instanceof String))throw new IllegalArgumentException("Layer IDs must be strings");
            String id=raw.getString(i);
            if(id.isEmpty()||id.length()>128||!wanted.add(id))
                throw new IllegalArgumentException("Duplicate or invalid selected layer");
        }
        int groupCount=0;long start=Long.MAX_VALUE,end=Long.MIN_VALUE;
        for(StudioLayer candidate:project.studioLayers) {
            if(StudioLayer.GROUP.equals(candidate.type))groupCount++;
            if(candidate.id.equals(groupId))throw new IllegalArgumentException(
                    "Operation ID already owns an existing timeline layer");
        }
        if(groupCount>=8||project.studioLayers.size()>=32)
            throw new IllegalArgumentException("Native motion-group or layer budget reached");
        ArrayList<StudioLayer> selected=new ArrayList<>();
        for(StudioLayer candidate:project.studioLayers) {
            if(!wanted.contains(candidate.id))continue;
            if(!Set.of(StudioLayer.SHAPE,StudioLayer.TITLE,StudioLayer.IMAGE).contains(candidate.type))
                throw new IllegalArgumentException("Only image, shape and title can be grouped");
            if(candidate.parentId!=null&&!candidate.parentId.isEmpty())
                throw new IllegalArgumentException("Nested or reparented groups are unsupported");
            selected.add(candidate);
            start=Math.min(start,candidate.startMs);
            end=Math.max(end,Math.addExact(candidate.startMs,candidate.durationMs));
        }
        if(selected.size()!=wanted.size())
            throw new IllegalArgumentException("One or more layers are no longer in the project");
        StudioLayer group=new StudioLayer();
        group.id=groupId;group.type=StudioLayer.GROUP;
        group.startMs=start;group.durationMs=end-start;
        group.motionKeyframes=detachedKeys(request.opt("motionKeyframes"));
        group.validate();
        // Add original group before its linked children, preserving all other
        // z-order relationships among existing visual tracks and the music.
        int insertion=project.studioLayers.indexOf(selected.get(0));
        project.studioLayers.add(insertion,group);
        for(StudioLayer child:selected)child.parentId=group.id;
        return new JSONObject().put("operation","create").put("groupId",groupId)
                .put("linkedLayerIds",new JSONArray(raw.toString()))
                .put("groupStartMs",start).put("groupDurationMs",end-start)
                .put("groupMotionKeys",group.motionKeyframes==null?0:group.motionKeyframes.length());
    }

    private static JSONObject animate(ProjectStore.Project project,JSONObject request)
            throws Exception {
        if(!request.has("motionKeyframes"))
            throw new IllegalArgumentException("Animate needs an authored keyframe array (empty clears)");
        StudioLayer group=requiredGroup(project,request);
        group.motionKeyframes=detachedKeys(request.opt("motionKeyframes"));
        group.validate();
        int children=0;
        for(StudioLayer lane:project.studioLayers)if(group.id.equals(lane.parentId))children++;
        return new JSONObject().put("operation","animate").put("groupId",group.id)
                .put("linkedLayerCount",children)
                .put("groupMotionKeys",group.motionKeyframes.length());
    }

    private static JSONObject dissolve(ProjectStore.Project project,JSONObject request)
            throws Exception {
        StudioLayer group=requiredGroup(project,request);
        int count=0;
        for(StudioLayer lane:project.studioLayers)if(group.id.equals(lane.parentId)){
            lane.parentId="";count++;
        }
        project.studioLayers.remove(group);
        return new JSONObject().put("operation","dissolve").put("groupId",group.id)
                .put("unlinkedLayerCount",count)
                .put("motionAttributionChanged",true)
                .put("note","Unlinks motion at the next preview without baking parent transforms");
    }

    private static StudioLayer requiredGroup(ProjectStore.Project project,JSONObject request) {
        String id=request.optString("groupId","");
        if(id.isEmpty()||id.length()>128)
            throw new IllegalArgumentException("Stable groupId required");
        for(StudioLayer lane:project.studioLayers)
            if(id.equals(lane.id)&&StudioLayer.GROUP.equals(lane.type))return lane;
        throw new IllegalArgumentException("Group is not part of this owned project");
    }

    private static JSONArray detachedKeys(Object raw) throws Exception {
        if(raw==null||raw==JSONObject.NULL)return new JSONArray();
        if(!(raw instanceof JSONArray))
            throw new IllegalArgumentException("Motion keys must be a JSON array");
        JSONArray keys=new JSONArray(raw.toString());
        if(keys.length()>StudioClipKeyframes.MAX_KEYS)
            throw new IllegalArgumentException("Maximum 64 group animation keys");
        return keys;
    }
}
