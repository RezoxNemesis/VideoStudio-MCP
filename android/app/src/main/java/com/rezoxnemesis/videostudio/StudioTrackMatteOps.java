package com.rezoxnemesis.videostudio;

import org.json.JSONArray;
import org.json.JSONObject;
import java.util.Set;

/**
 * One-command, revision-guarded native track-matte linking.
 * Same operation is used by human Android controls and authenticated MCP.
 *
 * Matte clips are project-owned SHAPE tracks only. No URL/filesystem/Gallery
 * sources, hidden exports, video decoding or asynchronous image inference.
 */
public final class StudioTrackMatteOps {
    private static final Set<String> FIELDS=Set.of(
            "projectId","expectedUpdatedAt","operation","targetLayerId",
            "matteLayerId","matteMode");
    private StudioTrackMatteOps(){}

    public static JSONObject capabilities()throws Exception {
        return new JSONObject()
                .put("schema","videostudio.track-mattes.v1")
                .put("minimumNativeVersion","4.10.33")
                .put("planAction","track_matte_plan")
                .put("editAction","track_matte_edit")
                .put("targetTypes",new JSONArray().put("image").put("shape"))
                .put("matteType","shape")
                .put("modes",new JSONArray().put("alpha").put("alpha_inverted"))
                .put("dynamicMattes",false).put("videoMattes",false)
                .put("parentedMattes",false).put("sourceUnchanged",true)
                .put("rasterResolutionLimit",1024)
                .put("cachedPerCompositionGeneration",true)
                .put("galleryAccess",false);
    }

    public static JSONObject plan(ProjectStore.Project source,JSONObject request) throws Exception {
        if(source==null)throw new IllegalArgumentException("A saved project is required");
        return changedCopy(source,request).receipt
                .put("dryRun",true).put("saved",false)
                .put("previewVerified",false);
    }

    public static JSONObject apply(ProjectStore store,JSONObject request) throws Exception {
        if(store==null||request==null)
            throw new IllegalArgumentException("Project store and track matte program are required");
        if(!(request.opt("projectId") instanceof String))
            throw new IllegalArgumentException("Stable projectId is required");
        long revision=StudioClipKeyframes.integer(request,"expectedUpdatedAt",0,9_007_199_254_740_991L);
        return store.guardedProject(request.getString("projectId"),revision,current->{
            Result plan=changedCopy(current,request);
            store.save(plan.project);
            return plan.receipt.put("dryRun",false).put("saved",true)
                    .put("updatedAt",plan.project.updatedAt)
                    .put("sourceUnchanged",true).put("previewVerified",false)
                    .put("rendered",false);
        });
    }

    private static final class Result {
        final ProjectStore.Project project;
        final JSONObject receipt;
        Result(ProjectStore.Project project,JSONObject receipt){
            this.project=project;this.receipt=receipt;
        }
    }

    private static Result changedCopy(ProjectStore.Project source,JSONObject request)
            throws Exception {
        if(request==null)throw new IllegalArgumentException("Matte program required");
        java.util.Iterator<String> keys=request.keys();
        while(keys.hasNext())if(!FIELDS.contains(keys.next()))
            throw new IllegalArgumentException("Unsupported matte editing field");
        if(!source.id.equals(request.optString("projectId","")))
            throw new IllegalArgumentException("Project identity mismatch");
        long revision=StudioClipKeyframes.integer(request,"expectedUpdatedAt",0,9_007_199_254_740_991L);
        if(source.updatedAt!=revision)throw new IllegalStateException("SOURCE_PROJECT_CHANGED");
        String op=request.optString("operation","");
        if(!"link".equals(op)&&!"unlink".equals(op))
            throw new IllegalArgumentException("Operation must be link or unlink");
        String targetId=request.optString("targetLayerId","");
        if(targetId.isEmpty()||targetId.length()>128)
            throw new IllegalArgumentException("A real visual layer target is required");
        ProjectStore.Project changed=ProjectStore.Project.fromJson(source.toJson());
        StudioLayer target=null,matte=null;
        for(StudioLayer lane:changed.studioLayers)
            if(lane.id.equals(targetId))target=lane;
        if(target==null||(!StudioLayer.IMAGE.equals(target.type)&&!StudioLayer.SHAPE.equals(target.type)))
            throw new IllegalArgumentException("Target must be a saved image/vector layer");
        if("link".equals(op)){
            String matteId=request.optString("matteLayerId","");
            if(matteId.isEmpty()||matteId.length()>128||targetId.equals(matteId))
                throw new IllegalArgumentException("Another saved shape must be selected for matte");
            if(request.has("matteMode")&&!(request.opt("matteMode") instanceof String))
                throw new IllegalArgumentException("Matte mode must be a string");
            String mode=request.optString("matteMode","alpha");
            if(!"alpha".equals(mode)&&!"alpha_inverted".equals(mode))
                throw new IllegalArgumentException("Only alpha or inverted alpha matte supported");
            for(StudioLayer lane:changed.studioLayers)
                if(lane.id.equals(matteId))matte=lane;
            if(matte==null||!StudioLayer.SHAPE.equals(matte.type))
                throw new IllegalArgumentException("Matte source is not an original project-owned vector");
            matte.matteOnly=true;
            target.matteLayerId=matte.id;
            target.matteMode=mode;
            StudioTrackMatteRasterizer.validatePair(target,matte);
        }else{
            if(request.has("matteLayerId")||request.has("matteMode"))
                throw new IllegalArgumentException("Unlink selects its existing source automatically");
            if(target.matteLayerId==null||target.matteLayerId.isEmpty())
                throw new IllegalArgumentException("Target does not have a saved track matte");
            String oldId=target.matteLayerId;
            for(StudioLayer lane:changed.studioLayers)
                if(lane.id.equals(oldId))matte=lane;
            target.matteLayerId="";
            target.matteMode="alpha";
            if(matte!=null){
                boolean stillUsed=false;
                for(StudioLayer other:changed.studioLayers)
                    if(oldId.equals(other.matteLayerId))stillUsed=true;
                if(!stillUsed)matte.matteOnly=false;
            }
        }
        StudioLayerContract.fromJson(changed,StudioLayerContract.toJson(changed.studioLayers));
        JSONObject receipt=new JSONObject()
                .put("ok",true).put("operation",op)
                .put("projectId",source.id).put("sourceUpdatedAt",revision)
                .put("targetLayerId",target.id)
                .put("matteLayerId",matte==null?"":matte.id)
                .put("matteMode",target.matteLayerId.isEmpty()?"none":target.matteMode)
                .put("staticOnly",true).put("galleryAccess",false)
                .put("sourceUnchanged",true)
                .put("layerCount",changed.studioLayers.size());
        return new Result(changed,receipt);
    }
}
