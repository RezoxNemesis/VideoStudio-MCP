package com.rezoxnemesis.videostudio;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.os.Looper;
import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Read-only evidence-based QA for autonomous animation work.
 *
 * Native Canvas pixels (not operation counts or a 64px JPEG) are analysed.
 * Tests for coloured accents, silhouettes and changes are useful failure gates,
 * but cannot magically count realistic fingers, infer anatomy or assess likeness.
 * The result always requires full-resolution human/assistant image inspection.
 */
final class SketchQualityAudit {
    static final int MAX_SAMPLES = 24;
    static final int MAX_REGIONS = 6;
    static final int RENDER_EDGE = 384;
    private static final int STRIDE = 3;

    private SketchQualityAudit() {}

    static JSONObject inspect(Context context, JSONObject document, JSONObject args) throws Exception {
        if (context == null || document == null || args == null)
            throw SketchDocument.bad("Native quality audit requires a drawing and request");
        if (Looper.myLooper() == Looper.getMainLooper())
            throw new IllegalStateException("Sketch quality audit must run off the Android UI thread");

        SketchDocument.fields(args, "sketchId,expectedRevision,frameOffset,frameLimit,frameStep,regions,"
                + "motionRequired,minChangedFraction,minNewCels");
        SketchDocument.validate(document);
        String sketchId = SketchDocument.id(args, "sketchId");
        if (!sketchId.equals(document.getString("id")))
            throw SketchDocument.bad("Native quality audit cannot inspect another drawing owner");
        long revision = document.getLong("revision");
        if (args.has("expectedRevision")
                && SketchDocument.integer(args, "expectedRevision", 1, Long.MAX_VALUE - 1) != revision)
            throw new IllegalStateException("SKETCH_REVISION_CONFLICT: quality source changed");

        int allFrames = document.getInt("frameCount");
        int offset = args.has("frameOffset") ? (int) SketchDocument.integer(
                args, "frameOffset", 0, allFrames - 1) : 0;
        int step = args.has("frameStep") ? (int) SketchDocument.integer(
                args, "frameStep", 1, 16) : 1;
        int count = args.has("frameLimit") ? (int) SketchDocument.integer(
                args, "frameLimit", 1, MAX_SAMPLES) : Math.min(MAX_SAMPLES, 1 + (allFrames - offset - 1) / step);
        boolean motionRequired = args.optBoolean("motionRequired", false);
        if (args.has("motionRequired")) SketchDocument.bool(args, "motionRequired");
        double minChanged = args.has("minChangedFraction")
                ? SketchDocument.number(args, "minChangedFraction", 0, 1) : .004;
        int minNewCels = args.has("minNewCels")
                ? (int) SketchDocument.integer(args, "minNewCels", 0, 10000) : 0;
        List<Region> regions = regions(args.optJSONArray("regions"));

        JSONArray rows = new JSONArray();
        JSONArray alerts = new JSONArray();
        int previousFrame = -1; // checked samples are distinct frames
        int[] previousPixels = null;
        int samples = 0, newCels = 0, moving = 0;
        int width = 0, height = 0;
        try (SketchFrameRenderer renderer = new SketchFrameRenderer(context, document)) {
            for (int i = 0; i < count; i++) {
                int frame = offset + i * step;
                if (frame >= allFrames) break;
                if (Thread.currentThread().isInterrupted())
                    throw new InterruptedException("Sketch quality audit cancelled");
                Bitmap bitmap = renderer.render(frame, true, RENDER_EDGE, null);
                try {
                    width = bitmap.getWidth();
                    height = bitmap.getHeight();
                    int[] pixels = new int[width * height];
                    bitmap.getPixels(pixels, 0, width, 0, 0, width, height);
                    Metric full = measure(pixels, width, height, 0, 0, width, height);
                    double changed = previousPixels == null ? -1 :
                            changedFraction(previousPixels, pixels, width, height);
                    int started = startsAtFrame(document, frame);
                    newCels += started;
                    if (changed >= minChanged) moving++;
                    JSONObject entry = new JSONObject()
                            .put("frame", frame).put("newCels", started)
                            .put("deltaFromFrame", previousFrame)
                            .put("changedPixelFraction", changed)
                            .put("whole", full.json());
                    JSONArray regionResults = new JSONArray();
                    for (Region region : regions) {
                        int left = (int) Math.floor(region.x * width),
                                top = (int) Math.floor(region.y * height),
                                right = Math.min(width, (int) Math.ceil((region.x + region.w) * width)),
                                bottom = Math.min(height, (int) Math.ceil((region.y + region.h) * height));
                        Metric observation = measure(pixels, width, height, left, top, right, bottom);
                        regionResults.put(new JSONObject().put("id", region.id)
                                .put("bounds", region.bounds).put("evidence", observation.json()));
                        if (observation.redFraction < region.minRed)
                            alert(alerts, frame, region.id, "RED_ACCENT_MISSING",
                                    "Required crimson colour below authored region threshold");
                        if (observation.litFraction < region.minLit)
                            alert(alerts, frame, region.id, "LOW_FORM_VISIBILITY",
                                    "Very little visible non-black detail in the selected region");
                        if (observation.edgeFraction < region.minEdges)
                            alert(alerts, frame, region.id, "LOW_EDGE_DETAIL",
                                    "Selected region has fewer tonal edges than required");
                    }
                    entry.put("regions", regionResults);
                    if (motionRequired && previousPixels != null && changed < minChanged)
                        alert(alerts, frame, "timeline", "POSSIBLY_FROZEN",
                                "No significant visual change between sampled native frames");
                    rows.put(entry);
                    previousPixels = pixels;
                    previousFrame = frame;
                    samples++;
                } finally {
                    bitmap.recycle();
                }
            }
        }
        if (newCels < minNewCels)
            alert(alerts, Math.max(0, offset), "timeline", "INSUFFICIENT_NEW_CELS",
                    "Sequence reuses held art more than the requested authoring minimum");
        return new JSONObject()
                .put("sketchId", sketchId).put("revision", revision)
                .put("frameCount", allFrames).put("fps", document.getInt("fps"))
                .put("frameOffset", offset).put("frameStep", step)
                .put("sampleCount", samples).put("imageWidth", width).put("imageHeight", height)
                .put("sampledNewCels", newCels).put("changedIntervals", moving)
                .put("samples", rows).put("alerts", alerts).put("alertCount", alerts.length())
                .put("reviewOnly", true).put("imageSource", "VideoStudio native Canvas")
                .put("galleryAccess", false).put("sourceModified", false)
                .put("sourceFrameImagesTransferred", false)
                .put("everyFrameReviewed", step == 1 && offset == 0 && samples == allFrames)
                .put("visualAcceptanceVerified", false).put("anatomyVerified", false)
                .put("fingerCountVerified", false).put("roseBotanyVerified", false)
                .put("note", "Pixel metrics catch missing colour, blank areas and stale motion; "
                        + "they cannot establish correct human anatomy or reference-quality artwork. "
                        + "Review exact crops using sketch_review detail=high and native sketch_preview.");
    }

    static final class Metric {
        final double litFraction, redFraction, edgeFraction, meanLuma;
        Metric(double lit, double red, double edge, double mean) {
            litFraction=lit; redFraction=red; edgeFraction=edge; meanLuma=mean;
        }
        JSONObject json() throws Exception {
            return new JSONObject()
                    .put("litFraction", round(litFraction))
                    .put("redFraction", round(redFraction))
                    .put("edgeFraction", round(edgeFraction))
                    .put("meanLuma", round(meanLuma));
        }
    }
    static Metric measure(int[] pixels, int w, int h, int l, int t, int r, int b) {
        if (pixels == null || w<=0 || h<=0 || pixels.length!=w*h ||
                l<0 || t<0 || r>w || b>h || r<=l || b<=t)
            throw new IllegalArgumentException("Invalid native audit pixel region");
        int total=0, lit=0, red=0, edge=0;
        long lumSum=0;
        for (int y=t; y<b; y+=STRIDE) {
            for (int x=l; x<r; x+=STRIDE) {
                int px=pixels[y*w+x], rr=Color.red(px),gg=Color.green(px),bb=Color.blue(px);
                int lum=(rr*54+gg*183+bb*19)/256;
                total++; lumSum+=lum;
                if (rr>29||gg>29||bb>29) lit++;
                if(rr>65 && rr-gg>26 && rr-bb>16 && rr>gg*1.25)red++;
                int nearby=pixels[Math.min(b-1,y+STRIDE)*w+Math.min(r-1,x+STRIDE)];
                if(Math.abs(lum-luma(nearby))>=18)edge++;
            }
        }
        return new Metric(lit/(double)total,red/(double)total,
                edge/(double)total,lumSum/(double)(total*255));
    }
    static double changedFraction(int[] previous,int[] next,int w,int h){
        if(previous==null||next==null||w<1||h<1||previous.length!=w*h||next.length!=w*h)
            throw new IllegalArgumentException("Invalid pair of native audit frames");
        int different=0,total=0;
        for(int y=0;y<h;y+=STRIDE)for(int x=0;x<w;x+=STRIDE){
            int a=previous[y*w+x],b=next[y*w+x];
            if(Math.max(Math.max(Math.abs(Color.red(a)-Color.red(b)),
                    Math.abs(Color.green(a)-Color.green(b))),
                    Math.abs(Color.blue(a)-Color.blue(b)))>=18)different++;
            total++;
        }
        return different/(double)total;
    }
    private static int luma(int p){return (54*Color.red(p)+183*Color.green(p)+19*Color.blue(p))/256;}
    private static double round(double x){return Math.round(x*100000d)/100000d;}

    private static int startsAtFrame(JSONObject drawing,int frame)throws Exception {
        int count=0;JSONArray layers=drawing.getJSONArray("layers");
        for(int l=0;l<layers.length();l++){
            JSONArray objects=layers.getJSONObject(l).getJSONArray("objects");
            for(int i=0;i<objects.length();i++)
                if(objects.getJSONObject(i).getInt("frame")==frame)count++;
        }
        return count;
    }
    private static void alert(JSONArray alerts,int frame,String id,String code,String message)throws Exception{
        if(alerts.length()>=192)return;
        alerts.put(new JSONObject().put("frame",frame).put("region",id)
                .put("code",code).put("message",message));
    }
    private static final class Region {
        final String id;final JSONObject bounds;final double x,y,w,h,minRed,minLit,minEdges;
        Region(String id,JSONObject bounds,double red,double lit,double edges)throws Exception{
            this.id=id;this.bounds=bounds;x=bounds.getDouble("x");y=bounds.getDouble("y");
            w=bounds.getDouble("width");h=bounds.getDouble("height");
            minRed=red;minLit=lit;minEdges=edges;
        }
    }
    private static List<Region> regions(JSONArray list)throws Exception {
        List<Region> out=new ArrayList<>();
        if(list==null)return out;
        if(list.length()>MAX_REGIONS)throw SketchDocument.bad("Use at most six quality-focus regions");
        java.util.HashSet<String> seen=new java.util.HashSet<>();
        for(int i=0;i<list.length();i++){
            JSONObject region=list.getJSONObject(i);
            SketchDocument.fields(region,"id,bounds,minRedFraction,minLitFraction,minEdgeFraction");
            String id=SketchDocument.id(region,"id");
            if(!seen.add(id))throw SketchDocument.bad("Duplicate quality-focus region");
            JSONObject bounds=region.getJSONObject("bounds");SketchDocument.validateBounds(bounds);
            double red=region.has("minRedFraction")?SketchDocument.number(region,"minRedFraction",0,1):0;
            double lit=region.has("minLitFraction")?SketchDocument.number(region,"minLitFraction",0,1):0;
            double edges=region.has("minEdgeFraction")?SketchDocument.number(region,"minEdgeFraction",0,1):0;
            out.add(new Region(id,bounds,red,lit,edges));
        }
        return out;
    }
}
