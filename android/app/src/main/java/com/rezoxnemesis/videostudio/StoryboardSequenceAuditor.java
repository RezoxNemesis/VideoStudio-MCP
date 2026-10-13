package com.rezoxnemesis.videostudio;

import android.content.Context;
import android.graphics.Bitmap;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.List;

/**
 * Inspect actual imported project drawings before any costly render.
 *
 * Reports per-shot motion/scene risk, available original-character rigs,
 * generic auto object tracking confidence, and model requirements.
 * Never says a neural video model exists merely because a model manifest
 * was copied to Drive. This report is advisory, not rendered animation.
 */
public final class StoryboardSequenceAuditor {
    public interface Progress {
        void onPair(int completed,int total) throws Exception;
    }
    private static final int W=160,H=284;
    private StoryboardSequenceAuditor(){}

    public static JSONObject inspect(Context ctx,ProjectStore.Project project,
                                     StoryboardBatchPlanner.Plan plan,
                                     int batchIndex,Progress progress)
            throws Exception {
        if(ctx==null||project==null||plan==null||
                !project.id.equals(plan.projectId))
            throw new IllegalArgumentException("Use the explicitly selected source project");
        int start=batchIndex<0?0:batchIndex,end=batchIndex<0?plan.batches.size():
                batchIndex+1;
        if(start<0||end>plan.batches.size())
            throw new IllegalArgumentException("Unknown original storyboard batch");
        JSONArray ordered=new JSONArray();for(String id:plan.assets)ordered.put(id);
        JSONObject manifest=plan.sourceManifest==null
                ?StoryboardSourceManifest.capture(ctx,project,new JSONObject().put("assetIds",ordered))
                :StoryboardSourceManifest.validateForPlanning(project,plan.sourceManifest);
        Rig2DProjectStore storedRigs=new Rig2DProjectStore(ctx,new ProjectStore(ctx));
        List<Rig2DProfile> rigs=storedRigs.list(project.id);
        int total=0;
        for(int i=start;i<end;i++)total+=plan.batches.get(i).shots.size();
        JSONArray reports=new JSONArray();
        int readyLayers=0,readyRigs=0,graphic=0,needMasks=0,cameraCuts=0;
        int processed=0;
        for(int i=start;i<end;i++){
            for(StoryboardBatchPlanner.Shot shot:plan.batches.get(i).shots){
                if(Thread.currentThread().isInterrupted())throw new InterruptedException();
                ProjectStore.Asset from=project.asset(shot.fromId),
                                   to=project.asset(shot.toId);
                JSONObject fromEntry=StoryboardMotionAdmission.library(manifest,from.id),
                        toEntry=StoryboardMotionAdmission.library(manifest,to.id);
                int[] before=decode(ctx,from,fromEntry),after=decode(ctx,to,toEntry);
                StoryboardLayerIsolation.Result moved=
                        StoryboardLayerIsolation.analyse(before,after,W,H);
                boolean sourceRig=hasRigs(rigs,from.id)&&hasRigs(rigs,to.id);
                String provider;
                if(sourceRig){provider="source-artwork-skinned-2d";readyRigs++;}
                else if(moved.autoRenderable()){
                    provider="auto-motion-foreground-2d";readyLayers++;
                }else if(shot.scene==StoryboardMotionGrammar.SceneKind.EXPLAINER||
                        shot.scene==StoryboardMotionGrammar.SceneKind.TYPOGRAPHY||
                        shot.scene==StoryboardMotionGrammar.SceneKind.LANDSCAPE||
                        shot.scene==StoryboardMotionGrammar.SceneKind.ABSTRACT){
                    provider="graphic-source-motion";graphic++;
                }else{
                    provider="requires-character-masks-or-segmentation-model";
                    needMasks++;
                }
                if(moved.status==StoryboardLayerIsolation.Status.CAMERA_CUT)cameraCuts++;
                JSONObject line=new JSONObject()
                        .put("fromAssetId",shot.fromId)
                        .put("toAssetId",shot.toId)
                        .put("fromName",from.name)
                        .put("toName",to.name)
                        .put("motion",shot.action.name())
                        .put("genre",shot.scene.name())
                        .put("fromSourceMapping",NativeStoryboardSourceImage.fitMapping(fromEntry,W,H))
                        .put("toSourceMapping",NativeStoryboardSourceImage.fitMapping(toEntry,W,H))
                        .put("autoStatus",moved.status.name())
                        .put("trackCount",moved.tracks.size())
                        .put("estimatedSceneDifference",moved.worldDifference)
                        .put("changedFraction",moved.changedFraction)
                        .put("recommendedProvider",provider)
                        .put("rendered",false)
                        .put("neuralVideoSynthesized",false)
                        .put("requiresManualReview",!sourceRig&&
                                !moved.autoRenderable());
                reports.put(line);
                processed++;
                if(progress!=null)progress.onPair(processed,total);
            }
        }
        return new JSONObject()
                .put("ok",true)
                .put("sourceProjectId",project.id)
                .put("sourceManifestSha256",manifest.getString("manifestSha256"))
                .put("auditCoordinateSpace","oriented-source-uv-contain")
                .put("auditScope","SOURCE_ENDPOINT_SEGMENTATION_ONLY")
                .put("anatomicalCoverageCertified",false)
                .put("cinematicAcceptance",false)
                .put("physicalContactQa","UNAVAILABLE")
                .put("actionFoleyQa","UNAVAILABLE")
                .put("pairsInspected",processed)
                .put("readyOriginalRigPairs",readyRigs)
                .put("readyAutoObjectPairs",readyLayers)
                .put("eligibleGraphicScenes",graphic)
                .put("pairsNeedMoreSegmentation",needMasks)
                .put("cameraCutRisks",cameraCuts)
                .put("pairReport",reports)
                .put("automaticVideoDiffusionInstalled",false)
                .put("bundledPortraitSegmentationAvailable",true)
                .put("galleryAccess",false)
                .put("allSourcesPreserved",true)
                .put("plan",plan.toJson());
    }
    private static boolean hasRigs(List<Rig2DProfile> profiles,String assetId){
        boolean black=false,white=false;
        for(Rig2DProfile r:profiles)
            if(assetId.equals(r.sourceAssetId)&&r.swordTip!=null&&
                    r.outline.length>=3){
                if(r.black)black=true;else white=true;
            }
        return black&&white;
    }
    private static int[] decode(Context ctx,ProjectStore.Asset asset,JSONObject sourceEntry) throws Exception {
        if(!StoryboardBatchPlanner.isImage(asset))
            throw new IllegalArgumentException("Source keyframe not imported");
        Bitmap thumb=NativeStoryboardSourceImage.readVerifiedFit(ctx,asset,W,H,sourceEntry);
        try{
            int[] pixels=new int[W*H];
            thumb.getPixels(pixels,0,W,0,0,W,H);
            return pixels;
        }finally{thumb.recycle();}
    }
}
