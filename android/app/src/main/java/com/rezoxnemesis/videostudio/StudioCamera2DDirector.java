package com.rezoxnemesis.videostudio;

import org.json.JSONArray;
import org.json.JSONObject;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * Source-safe composition-level camera edit, callable by Android editor or
 * stable native MCP with a single guarded project transaction.
 *
 * The camera moves the WHOLE finished 2D picture after overlays. It is not
 * a physically accurate 3D camera, a proxy render, a new imported asset,
 * optical-flow motion or an After Effects expression evaluator.
 */
public final class StudioCamera2DDirector {
    private static final Set<String> FIELDS=Set.of("projectId","expectedUpdatedAt",
            "operation","motionKeyframes");
    private static final Set<String> OPERATIONS=Set.of("set","clear","remove");
    private StudioCamera2DDirector(){}

    public static JSONObject capabilities()throws Exception{
        return new JSONObject()
                .put("schema","videostudio.camera2d.v1")
                .put("minimumNativeVersion","4.10.33")
                .put("planAction","camera2d_plan")
                .put("applyAction","camera2d_apply")
                .put("operations",new JSONArray().put("set").put("clear").put("remove"))
                .put("maxCameraTracks",1)
                .put("maxKeyframes",StudioClipKeyframes.MAX_KEYS)
                .put("channels",new JSONArray().put("x").put("y").put("scale")
                        .put("scaleX").put("scaleY").put("rotate")
                        .put("anchorX").put("anchorY"))
                .put("supportsCubicBezier",true)
                .put("compositionStage","after_title_image_shape_and_video_compositing")
                .put("wholeFrame2D",true).put("true3D",false)
                .put("cameraOpacity",false)
                .put("mutatesOriginalMediaFiles",false)
                .put("galleryAccess",false)
                .put("previewVerifiedOnPhone",false);
    }

    public static JSONObject plan(ProjectStore.Project project,JSONObject request)throws Exception{
        if(project==null)throw new IllegalArgumentException("Source project not found");
        ProjectStore.Project copy=ProjectStore.Project.fromJson(project.toJson());
        JSONObject result=change(copy,request);
        StudioLayerContract.fromJson(copy,StudioLayerContract.toJson(copy.studioLayers));
        return result.put("dryRun",true).put("saved",false).put("previewVerified",false);
    }

    public static JSONObject apply(ProjectStore store,JSONObject request)throws Exception{
        if(store==null||request==null||!(request.opt("projectId") instanceof String))
            throw new IllegalArgumentException("Native project and exact camera request required");
        long expected=StudioClipKeyframes.integer(request,"expectedUpdatedAt",
                0,9_007_199_254_740_991L);
        return store.guardedProject(request.getString("projectId"),expected,current->{
            ProjectStore.Project candidate=ProjectStore.Project.fromJson(current.toJson());
            JSONObject outcome=change(candidate,request);
            StudioLayerContract.fromJson(candidate,StudioLayerContract.toJson(candidate.studioLayers));
            store.save(candidate);
            return outcome.put("dryRun",false).put("saved",true)
                    .put("updatedAt",candidate.updatedAt)
                    .put("previewVerified",false)
                    .put("rendered",false);
        });
    }

    private static JSONObject change(ProjectStore.Project project,JSONObject request)throws Exception{
        if(request==null)throw new IllegalArgumentException("Camera command required");
        java.util.Iterator<String> fields=request.keys();
        while(fields.hasNext())if(!FIELDS.contains(fields.next()))
            throw new IllegalArgumentException("Unsupported global camera property");
        if(!project.id.equals(request.optString("projectId","")))
            throw new IllegalArgumentException("Camera project identity mismatch");
        long expected=StudioClipKeyframes.integer(request,"expectedUpdatedAt",
                0,9_007_199_254_740_991L);
        if(project.updatedAt!=expected)throw new IllegalStateException("SOURCE_PROJECT_CHANGED");
        String operation=request.optString("operation","");
        if(!OPERATIONS.contains(operation))
            throw new IllegalArgumentException("Use set, clear or remove");
        if(project.clips.isEmpty()||project.outputDurationMs()<100)
            throw new IllegalArgumentException("Camera needs an existing renderable video timeline");
        StudioLayer camera=null;
        for(StudioLayer lane:project.studioLayers)if(StudioLayer.CAMERA.equals(lane.type)){
            if(camera!=null)throw new IllegalArgumentException("Multiple existing cameras are not supported");
            camera=lane;
        }
        String stableId=UUID.nameUUIDFromBytes(("camera2d/"+project.id)
                .getBytes(StandardCharsets.UTF_8)).toString();
        if("remove".equals(operation)){
            if(camera==null)throw new IllegalArgumentException("No camera to remove");
            project.studioLayers.remove(camera);
            camera=null;
        }else {
            if(camera==null){
                if("clear".equals(operation))
                    throw new IllegalArgumentException("No camera to clear");
                if(project.studioLayers.size()>=32)
                    throw new IllegalArgumentException("No remaining native layer slots");
                camera=new StudioLayer();
                camera.id=stableId;camera.type=StudioLayer.CAMERA;
                project.studioLayers.add(camera);
            }
            camera.startMs=0;
            camera.durationMs=project.outputDurationMs();
            if("clear".equals(operation)){
                camera.motionKeyframes=new JSONArray();
            }else{
                if(!(request.opt("motionKeyframes") instanceof JSONArray))
                    throw new IllegalArgumentException("Set requires exact camera keyframe array");
                camera.motionKeyframes=new JSONArray(request.getJSONArray("motionKeyframes").toString());
            }
            camera.validate();
        }
        return new JSONObject().put("ok",true)
                .put("schema","videostudio.camera2d.edit.v1")
                .put("projectId",project.id)
                .put("cameraId",camera==null?"":camera.id)
                .put("operation",operation)
                .put("cameraTracksAfter", "remove".equals(operation)?0:1)
                .put("keyframesAfter",camera==null?0:
                        camera.motionKeyframes==null?0:camera.motionKeyframes.length())
                .put("sourceMediaUnchanged",true)
                .put("galleryAccess",false)
                .put("2dOnly",true);
    }
}
