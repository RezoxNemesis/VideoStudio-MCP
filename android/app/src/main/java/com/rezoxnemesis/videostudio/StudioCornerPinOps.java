package com.rezoxnemesis.videostudio;

import org.json.JSONArray;
import org.json.JSONObject;
import java.util.Set;

/** Native revision-guarded single-action edit for static four-corner projective warps. */
public final class StudioCornerPinOps {
    private static final Set<String> FIELDS=Set.of(
            "projectId","expectedUpdatedAt","layerId","operation","corners");
    private StudioCornerPinOps(){}

    public static JSONObject capabilities()throws Exception{
        return new JSONObject()
                .put("schema","videostudio.corner-pin.v1")
                .put("minimumNativeVersion","4.10.34")
                .put("planAction","corner_pin_plan")
                .put("editAction","corner_pin_edit")
                .put("operations",new JSONArray().put("apply").put("clear"))
                .put("supportedLayers",new JSONArray().put("image").put("shape"))
                .put("cornerCoordinates",new JSONArray().put("TL").put("TR").put("BR").put("BL"))
                .put("bounds","0..1").put("projective2DHomography",true)
                .put("dynamicPerspectiveTracking",false)
                .put("independent3DCamera",false)
                .put("sourceMediaUnchanged",true)
                .put("cachedPerMedia3Generation",true)
                .put("galleryAccess",false);
    }

    public static JSONObject plan(ProjectStore.Project source,JSONObject request)throws Exception{
        if(source==null)throw new IllegalArgumentException("Project not found");
        return change(source,request).receipt.put("dryRun",true).put("saved",false)
                .put("previewVerified",false);
    }

    public static JSONObject apply(ProjectStore store,JSONObject request)throws Exception{
        if(store==null||request==null||!(request.opt("projectId") instanceof String))
            throw new IllegalArgumentException("A selected project and edit request are required");
        long expected=StudioClipKeyframes.integer(request,"expectedUpdatedAt",0,9_007_199_254_740_991L);
        return store.guardedProject(request.getString("projectId"),expected,current->{
            Result result=change(current,request);
            store.save(result.project);
            return result.receipt.put("dryRun",false).put("saved",true)
                    .put("updatedAt",result.project.updatedAt)
                    .put("previewVerified",false).put("rendered",false);
        });
    }

    private static final class Result {
        final ProjectStore.Project project;
        final JSONObject receipt;
        Result(ProjectStore.Project p,JSONObject r){project=p;receipt=r;}
    }

    private static Result change(ProjectStore.Project source,JSONObject request)throws Exception{
        java.util.Iterator<String> keys=request.keys();
        while(keys.hasNext())if(!FIELDS.contains(keys.next()))
            throw new IllegalArgumentException("Unknown corner pin field");
        if(!source.id.equals(request.optString("projectId","")))
            throw new IllegalArgumentException("Project identity mismatch");
        long expected=StudioClipKeyframes.integer(request,"expectedUpdatedAt",0,9_007_199_254_740_991L);
        if(expected!=source.updatedAt)throw new IllegalStateException("SOURCE_PROJECT_CHANGED");
        String id=request.optString("layerId","");
        if(id.length()<1||id.length()>128)throw new IllegalArgumentException("Stable layer ID required");
        String action=request.optString("operation","");
        if(!"apply".equals(action)&&!"clear".equals(action))
            throw new IllegalArgumentException("Use apply or clear");
        ProjectStore.Project detached=ProjectStore.Project.fromJson(source.toJson());
        StudioLayer target=null;
        for(StudioLayer lane:detached.studioLayers)if(id.equals(lane.id))target=lane;
        if(target==null||(!StudioLayer.IMAGE.equals(target.type)&&!StudioLayer.SHAPE.equals(target.type)))
            throw new IllegalArgumentException("Only existing image/vector overlays can be corner pinned");
        if("clear".equals(action)){
            if(request.has("corners"))throw new IllegalArgumentException("Clear does not accept new pin coordinates");
            if(target.cornerPin==null)throw new IllegalArgumentException("Layer has no saved corner pin");
            target.cornerPin=null;
        }else{
            if(!(request.opt("corners") instanceof JSONArray))
                throw new IllegalArgumentException("Apply needs eight ordered numeric corner values");
            JSONArray checked=StudioCornerPinRasterizer.validate(request.optJSONArray("corners"));
            target.cornerPin=StudioCornerPinRasterizer.isIdentity(checked)?null:checked;
        }
        target.validate();
        StudioLayerContract.fromJson(detached,StudioLayerContract.toJson(detached.studioLayers));
        JSONObject receipt=new JSONObject().put("ok",true)
                .put("operation",action).put("projectId",source.id)
                .put("layerId",id).put("sourceUpdatedAt",source.updatedAt)
                .put("hasCornerPin",target.cornerPin!=null)
                .put("projective2D",true).put("sourceMediaUnchanged",true)
                .put("galleryAccess",false);
        return new Result(detached,receipt);
    }
}
