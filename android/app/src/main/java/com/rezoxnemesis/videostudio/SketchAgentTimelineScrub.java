package com.rezoxnemesis.videostudio;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Bounded visible timeline gesture plan: actual native frame/ruler movement,
 * without mutating the authored sketch cels or simulating Android touch events.
 *
 * The foreground SketchStudioActivity owns the frame slider and canvas. This
 * pure class never accesses a remote API, Gallery, or document storage.
 */
public final class SketchAgentTimelineScrub {
    public static final int MIN_DURATION_MS=160,MAX_DURATION_MS=1400;
    public static final int MAX_VISUAL_SAMPLES=12;
    private SketchAgentTimelineScrub(){}

    public static final class Plan {
        public final String sketchId;
        public final long sourceRevision;
        public final int fromFrame,toFrame,durationMs,frameCount;
        private Plan(String id,long revision,int frameCount,int from,int to,int duration){
            this.sketchId=id;this.sourceRevision=revision;this.frameCount=frameCount;
            fromFrame=from;toFrame=to;durationMs=duration;
        }
        public int sample(float fraction){
            if(!Float.isFinite(fraction))
                throw new IllegalArgumentException("Native timeline fraction must be finite");
            double x=Math.max(0,Math.min(1,(double)fraction));
            // The same explicit smoothstep gives a convincing touch-scrub while
            // 12 UI publications bound decode pressure on a mobile renderer.
            double ease=x*x*(3-2*x);
            long value=Math.round(fromFrame+(toFrame-(long)fromFrame)*ease);
            return (int)Math.max(0,Math.min(frameCount-1,value));
        }
        public JSONObject evidence(int reachedFrame,int publishedFrames)throws Exception {
            if(reachedFrame!=toFrame||publishedFrames<1||
                    publishedFrames>MAX_VISUAL_SAMPLES+1)
                throw new IllegalStateException("Frame scrub did not visibly reach its authored target");
            return new JSONObject()
                    .put("actualScrubStartFrame",fromFrame)
                    .put("actualScrubEndFrame",reachedFrame)
                    .put("targetFrame",toFrame)
                    .put("sourceRevisionUnchanged",true)
                    .put("visibleFramePublications",publishedFrames)
                    .put("maximumVisiblePublications",MAX_VISUAL_SAMPLES+1)
                    .put("requestedDurationMs",durationMs)
                    .put("renderedPixelsVerified",false);
        }
    }

    public static Plan plan(JSONObject document,JSONObject request,int fromFrame)throws Exception{
        if(document==null||request==null)
            throw SketchDocument.bad("Visible timeline scrub requires a real native sketch");
        SketchDocument.fields(request,"action,frameIndex,durationMs");
        if(!"scrub".equals(request.optString("action","")))
            throw SketchDocument.bad("Only a native scrub request is accepted");
        String id=SketchDocument.id(document,"id");
        long revision=document.getLong("revision");
        int count=document.getInt("frameCount");
        if(count<2||fromFrame<0||fromFrame>=count)
            throw SketchDocument.bad("Timeline has no valid starting frame to scrub");
        int target=(int)SketchDocument.integer(request,"frameIndex",0,count-1);
        if(fromFrame==target)
            throw SketchDocument.bad("Timeline is already showing that frame");
        int duration=request.has("durationMs")?(int)SketchDocument.integer(request,
                "durationMs",MIN_DURATION_MS,MAX_DURATION_MS):600;
        return new Plan(id,revision,count,fromFrame,target,duration);
    }

    public static JSONObject capabilities()throws Exception{
        return new JSONObject().put("minimumNativeVersion","4.10.44")
                .put("stageAction","scrub")
                .put("gesture","Native foreground timeline seek bar and actual canvas frames")
                .put("durationMinMs",MIN_DURATION_MS)
                .put("durationMaxMs",MAX_DURATION_MS)
                .put("maximumFramePublications",MAX_VISUAL_SAMPLES+1)
                .put("stageUsesSourceRevision",true)
                .put("sourceChanged",false)
                .put("pixelOutputVerification",false)
                .put("requiresOpenSketchStudio",true)
                .put("galleryAccess",false);
    }
}
