package com.rezoxnemesis.videostudio;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.InputStream;

/**
 * Runs ONLY on explicitly imported VideoStudio project images.
 * No Gallery query, storage enumeration, remote model, or cloud inference.
 */
public final class StoryboardMotionPreflight {
    private static final int W=160,H=284;
    private StoryboardMotionPreflight() {}

    public static JSONObject inspect(Context context,ProjectStore.Project project,
                                     JSONArray orderedAssets) throws Exception {
        if(context==null||project==null||orderedAssets==null||
                orderedAssets.length()<2||orderedAssets.length()>40)
            throw new IllegalArgumentException("Need 2-40 explicit VideoStudio images");
        JSONArray reports=new JSONArray();
        int needsRig=0,needsScene=0,flowCandidates=0;
        ProjectStore.Asset first=project.asset(orderedAssets.getString(0));
        int[] previous=decode(context,first);
        for(int i=1;i<orderedAssets.length();i++){
            if(Thread.currentThread().isInterrupted())throw new InterruptedException();
            ProjectStore.Asset nextAsset=project.asset(orderedAssets.getString(i));
            int[] next=decode(context,nextAsset);
            PoseSequenceFlow.Pair pair=PoseSequenceFlow.analyse(previous,next,W,H);
            StoryboardMotionPolicy.Diagnosis decision=
                    StoryboardMotionPolicy.evaluate(previous,next,W,H,pair);
            switch(decision.decision) {
                case FLOW_CANDIDATE: flowCandidates++;break;
                case NEEDS_ARTICULATED_RIG: needsRig++;break;
                default: needsScene++;break;
            }
            JSONObject d=new JSONObject();
            d.put("fromAssetId",orderedAssets.getString(i-1));
            d.put("toAssetId",orderedAssets.getString(i));
            d.put("pairIndex",i-1);
            d.put("decision",decision.decision.name());
            d.put("actionPixelChangeRatio",rounded(decision.changedActionFraction));
            d.put("actionMeanDifference",rounded(decision.actionDifference));
            d.put("backgroundMeanDifference",rounded(decision.staticRegionDifference));
            d.put("correspondenceConfidence",rounded(decision.correspondenceConfidence));
            d.put("forwardBackwardConsistency",rounded(decision.forwardBackwardConsistency));
            d.put("sceneCutRisk",pair.sceneCutRisk);
            d.put("requiresNewBodyPoses",decision.requiresPoseGeneration());
            reports.put(d);
            previous=next;
        }
        JSONObject response=new JSONObject();
        response.put("ok",true);
        response.put("projectId",project.id);
        response.put("pairs",reports);
        response.put("pairCount",reports.length());
        response.put("pixelFlowCandidates",flowCandidates);
        response.put("needsArticulatedRig",needsRig);
        response.put("needsSceneReconstruction",needsScene);
        response.put("riggedPoseSynthesisInstalled",false);
        response.put("full3DSkinningInstalled",false);
        response.put("heuristicOnly",true);
        response.put("galleryAccess",false);
        response.put("recommendation", needsRig+needsScene>0
                ?"Author source-faithful rigs, masks and body poses before interpolation."
                :"Pixel interpolation can be tested, but body physics still needs visual QA.");
        return response;
    }

    private static int[] decode(Context context,ProjectStore.Asset asset) throws Exception {
        if(asset==null||asset.mime==null||!asset.mime.startsWith("image/"))
            throw new IllegalArgumentException("Image asset not found in the selected project");
        Uri uri=Uri.parse(asset.uri);
        BitmapFactory.Options bounds=new BitmapFactory.Options();
        bounds.inJustDecodeBounds=true;
        try(InputStream stream=context.getContentResolver().openInputStream(uri)){
            if(stream==null)throw new IllegalArgumentException("Image cannot be opened");
            BitmapFactory.decodeStream(stream,null,bounds);
        }
        if(bounds.outWidth<32||bounds.outHeight<32||
                bounds.outWidth>8192||bounds.outHeight>8192)
            throw new IllegalArgumentException("Unsafe source-image dimensions");
        BitmapFactory.Options read=new BitmapFactory.Options();
        read.inPreferredConfig=Bitmap.Config.RGB_565;
        read.inSampleSize=1;
        while(bounds.outWidth/read.inSampleSize>W*2 &&
              bounds.outHeight/read.inSampleSize>H*2)read.inSampleSize*=2;
        Bitmap raw;
        try(InputStream stream=context.getContentResolver().openInputStream(uri)){
            if(stream==null)throw new IllegalArgumentException("Image no longer available");
            raw=BitmapFactory.decodeStream(stream,null,read);
        }
        if(raw==null)throw new IllegalArgumentException("Source image failed to decode");
        Bitmap scaled=Bitmap.createScaledBitmap(raw,W,H,true);
        if(scaled!=raw)raw.recycle();
        int[] pixels=new int[W*H];
        scaled.getPixels(pixels,0,W,0,0,W,H);
        scaled.recycle();
        return pixels;
    }

    private static double rounded(double value) {
        return Math.round(value*10000d)/10000d;
    }
}
