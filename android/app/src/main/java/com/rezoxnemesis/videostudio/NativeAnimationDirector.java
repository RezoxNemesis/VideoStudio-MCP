package com.rezoxnemesis.videostudio;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * Native Image Animation Director: turns an ALREADY IMPORTED image timeline
 * into a timed, editable animation project, with no external editor, gallery
 * enumeration, cloud model, camera zoom, colour preset, or cross-dissolve.
 *
 * The source project stays untouched. This uses authentic supplied poses;
 * interpolation is a distinct opt-in mode handled by PoseSequenceFlow.
 */
public final class NativeAnimationDirector {
    public static final int MAX_SOURCE_FRAMES = 120;
    private static final int THUMB_W = 48, THUMB_H = 80;

    public static final class Plan {
        public final ArrayList<String> sourceIds = new ArrayList<>();
        public final ArrayList<Integer> frameDurationsMs = new ArrayList<>();
        public final ArrayList<Integer> impactIndices = new ArrayList<>();
        public final ArrayList<Double> motionScores = new ArrayList<>();
        public String method = "direct";
        public int fps;
        public int totalDurationMs;
        public JSONObject toJson() throws Exception {
            JSONObject result = new JSONObject();
            result.put("method",method);
            result.put("fps",fps);
            result.put("sourceFrameCount",sourceIds.size());
            result.put("durationMs",totalDurationMs);
            JSONArray timings = new JSONArray(), impacts = new JSONArray(), scores=new JSONArray();
            for(int i=0;i<sourceIds.size();i++) {
                timings.put(frameDurationsMs.get(i));
                scores.put(Math.round(motionScores.get(i)*10000.0)/10000.0);
            }
            for(int i:impactIndices) impacts.put(i);
            result.put("frameDurationsMs",timings);
            result.put("impactIndicesZeroBased",impacts);
            result.put("motionScores",scores);
            result.put("neuralPoseGenerator",false);
            result.put("cameraMovement",false);
            result.put("crossDissolves",false);
            return result;
        }
    }

    private NativeAnimationDirector() {}

    public static Plan plan(Context context,ProjectStore.Project project, int fps,
                            JSONArray preferredImpacts) throws Exception {
        if(project==null) throw new IllegalArgumentException("Open an image project first");
        if(fps!=24 && fps!=30) throw new IllegalArgumentException("Animation fps must be 24 or 30");
        if(project.clips.size()<2 || project.clips.size()>MAX_SOURCE_FRAMES)
            throw new IllegalArgumentException("Animation needs 2-120 image timeline clips");
        Plan plan = new Plan();
        plan.fps=fps;
        ArrayList<ProjectStore.Asset> ordered=new ArrayList<>();
        for(ProjectStore.Clip clip: project.clips) {
            ProjectStore.Asset asset=project.asset(clip.assetId);
            if(asset==null || asset.mime==null || !asset.mime.startsWith("image/"))
                throw new IllegalArgumentException("This project has non-image clips. Use an image-only timeline");
            plan.sourceIds.add(asset.id);
            ordered.add(asset);
        }
        int[][] previews=new int[ordered.size()][];
        for(int i=0;i<ordered.size();i++) previews[i]=thumbnail(context,ordered.get(i));
        plan.motionScores.add(0.0);
        for(int i=1;i<previews.length;i++)
            plan.motionScores.add(score(previews[i-1],previews[i]));
        boolean[] important=new boolean[ordered.size()];
        if(preferredImpacts!=null && preferredImpacts.length()>0) {
            if(preferredImpacts.length()>12)
                throw new IllegalArgumentException("At most 12 impact frames are supported");
            for(int j=0;j<preferredImpacts.length();j++) {
                int i=preferredImpacts.optInt(j,-1);
                if(i<0 || i>=important.length)
                    throw new IllegalArgumentException("Impact index outside image timeline");
                important[i]=true;
            }
        } else {
            for(int i:autoImpacts(plan.motionScores,3)) important[i]=true;
        }
        int base=(int)Math.round(4000.0/fps); // 4 displayed samples / authored pose
        int total=0;
        for(int i=0;i<ordered.size();i++) {
            double score=plan.motionScores.get(i);
            int ms=score>=.10 ? (int)Math.round(base*.73)
                    : score>=.045 ? (int)Math.round(base*.84)
                    : base;
            if(i==0) ms+=25; // opening anticipation
            if(i==ordered.size()-1) ms+=180; // final recovery
            if(important[i]) {
                ms+=(int)Math.round(base*.50);
                plan.impactIndices.add(i);
            }
            // No "hold forever" frames, no 30fps quantization jumps, and
            // values >=68ms satisfy Media3 image duration bounds safely.
            ms=Math.max(68,Math.min(350,ms));
            plan.frameDurationsMs.add(ms);
            total+=ms;
        }
        plan.totalDurationMs=total;
        return plan;
    }

    public static ProjectStore.Project createDirectProject(ProjectStore store,
                      ProjectStore.Project source, Plan plan) throws Exception {
        if(store==null||source==null||plan==null
                ||plan.sourceIds.size()!=plan.frameDurationsMs.size())
            throw new IllegalArgumentException("Invalid animation plan");
        ProjectStore.Project output=store.create("Animation Director - " + source.name);
        output.sourcePrompt="native-animation-director: ordered authored frames, quality-safe direct playback";
        for(int i=0;i<plan.sourceIds.size();i++) {
            ProjectStore.Asset asset=source.asset(plan.sourceIds.get(i));
            if(asset==null||asset.mime==null||!asset.mime.startsWith("image/"))
                throw new IllegalArgumentException("Image asset unavailable in source");
            // Metadata reference is safe: neither the original asset nor
            // its URI is edited, deleted, rewritten, or moved.
            output.assets.add(asset);
            ProjectStore.Clip clip=new ProjectStore.Clip();
            clip.id=UUID.randomUUID().toString();
            clip.assetId=asset.id;
            clip.inMs=0;
            clip.outMs=plan.frameDurationsMs.get(i);
            clip.speed=1;
            clip.volume=0;
            clip.transition="none";
            clip.title="";
            clip.effects=new JSONObject();
            clip.effects.put("poseInbetween",true); // short image timing, NOT claiming AI-generated pose
            clip.effects.put("poseFps",plan.fps);
            clip.effects.put("directorSequenceFrame",true);
            output.clips.add(clip);
        }
        store.save(output);
        return output;
    }

    public static JSONArray recommendedFlowSteps(Plan plan) {
        JSONArray result=new JSONArray();
        for(int i=0;i<plan.sourceIds.size()-1;i++) {
            double diff=plan.motionScores.get(i+1);
            // Big pose/camera jumps are unsafe for inferred motion: synthesize
            // fewer frames, not extra ghosted copies. 2..4 by design.
            int count=diff>.11 ? 2 : diff>.055 ? 3 : 4;
            result.put(count);
        }
        return result;
    }

    public static int[] autoImpacts(List<Double> scores,int limit) {
        ArrayList<Integer> candidates=new ArrayList<>();
        if(scores==null || scores.size()<5 || limit<1) return new int[0];
        double[] values=new double[scores.size()-1];
        for(int i=1;i<scores.size();i++) values[i-1]=scores.get(i);
        double[] sorted=values.clone(); Arrays.sort(sorted);
        double median=sorted[sorted.length/2];
        for(int i=2;i<scores.size()-1;i++) {
            double s=scores.get(i);
            if(s>=.045 && s>=median*1.3
                && s>=scores.get(i-1) && s>=scores.get(i+1))
                candidates.add(i);
        }
        candidates.sort((a,b)->Double.compare(scores.get(b),scores.get(a)));
        ArrayList<Integer> chosen=new ArrayList<>();
        for(int index:candidates) {
            boolean tooNear=false;
            for(int old:chosen) if(Math.abs(old-index)<3) tooNear=true;
            if(!tooNear) chosen.add(index);
            if(chosen.size()>=limit) break;
        }
        chosen.sort(Integer::compareTo);
        return chosen.stream().mapToInt(Integer::intValue).toArray();
    }

    private static double score(int[] a,int[] b) {
        long sum=0;
        for(int i=0;i<a.length;i++) {
            int x=a[i],y=b[i];
            sum+=Math.abs((x>>>16&255)-(y>>>16&255));
            sum+=Math.abs((x>>>8&255)-(y>>>8&255));
            sum+=Math.abs((x&255)-(y&255));
        }
        return sum/(a.length*765.0);
    }

    private static int[] thumbnail(Context context,ProjectStore.Asset asset) throws Exception {
        Uri uri=Uri.parse(asset.uri);
        BitmapFactory.Options bounds=new BitmapFactory.Options();
        bounds.inJustDecodeBounds=true;
        try(InputStream input=context.getContentResolver().openInputStream(uri)) {
            if(input==null) throw new IllegalArgumentException("Missing source image");
            BitmapFactory.decodeStream(input,null,bounds);
        }
        if(bounds.outWidth<=0 || bounds.outHeight<=0)
            throw new IllegalArgumentException("Cannot read source image dimensions");
        BitmapFactory.Options options=new BitmapFactory.Options();
        options.inPreferredConfig=Bitmap.Config.RGB_565;
        options.inSampleSize=1;
        while(bounds.outWidth/options.inSampleSize>THUMB_W*2
                || bounds.outHeight/options.inSampleSize>THUMB_H*2)
            options.inSampleSize*=2;
        Bitmap decoded;
        try(InputStream input=context.getContentResolver().openInputStream(uri)) {
            if(input==null) throw new IllegalArgumentException("Source image disappeared");
            decoded=BitmapFactory.decodeStream(input,null,options);
        }
        if(decoded==null) throw new IllegalArgumentException("Unreadable image");
        Bitmap small=Bitmap.createScaledBitmap(decoded,THUMB_W,THUMB_H,true);
        int[] samples=new int[THUMB_W*THUMB_H];
        small.getPixels(samples,0,THUMB_W,0,0,THUMB_W,THUMB_H);
        if(small!=decoded) decoded.recycle();
        small.recycle();
        return samples;
    }
}
