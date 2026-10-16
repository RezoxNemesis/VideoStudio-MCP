package com.rezoxnemesis.videostudio;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Paint;
import android.graphics.Rect;
import android.net.Uri;

import com.google.android.gms.tasks.Tasks;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.facemesh.FaceMesh;
import com.google.mlkit.vision.facemesh.FaceMeshDetection;
import com.google.mlkit.vision.facemesh.FaceMeshDetector;
import com.google.mlkit.vision.segmentation.Segmentation;
import com.google.mlkit.vision.segmentation.SegmentationMask;
import com.google.mlkit.vision.segmentation.Segmenter;
import com.google.mlkit.vision.segmentation.selfie.SelfieSegmenterOptions;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Bundled on-device portrait analysis for VideoStudio v3.
 *
 * No remote inference is used. The bundled ML Kit selfie model produces a
 * foreground confidence mask, and the bundled face-mesh model provides a
 * face-aware framing anchor. The generated foreground/background plates are
 * written to VideoStudio's app-private storage and can be animated as
 * independent layers by the native Media3 renderer.
 */
public final class NativePortraitMotionAnalyzer {
    public static final class Result {
        public final Uri foregroundUri;
        public final Uri headUri;
        public final Uri torsoUri;
        public final Uri lowerUri;
        public final Uri backgroundUri;
        public final JSONObject analysis;
        public final int width;
        public final int height;

        Result(Uri foregroundUri, Uri headUri, Uri torsoUri, Uri lowerUri,
               Uri backgroundUri, JSONObject analysis, int width, int height) {
            this.foregroundUri = foregroundUri;
            this.headUri = headUri;
            this.torsoUri = torsoUri;
            this.lowerUri = lowerUri;
            this.backgroundUri = backgroundUri;
            this.analysis = analysis;
            this.width = width;
            this.height = height;
        }
    }

    private static final int MAX_ANALYSIS_EDGE = 1200;

    private final Context context;

    public NativePortraitMotionAnalyzer(Context context) {
        this.context = context.getApplicationContext();
    }

    public Result analyseAndBuildLayers(ProjectStore.Asset asset, String projectId) throws Exception {
        if (asset == null) throw new IllegalArgumentException("Missing image asset");
        if (asset.mime == null || !asset.mime.startsWith("image/")) {
            throw new IllegalArgumentException("Portrait animation requires an image asset");
        }

        // The read-only original is decoded at an edge selected from actual
        // Android memory pressure. All native bitmap allocations below are
        // reclaimed even if segmentation, encoding or storage throws.
        int edge=PortraitMemoryAdmission.edgeFor(context);
        Bitmap original=null,working=null,foreground=null,background=null;
        SubjectParts parts=null;
        try{
        original=decode(asset.uri,edge);
        if(original==null)throw new IllegalStateException("PORTRAIT_DECODE_FAILED: source image could not be decoded");
        working=scaleForAnalysis(original,edge);

        Segmenter segmenter = Segmentation.getClient(
                new SelfieSegmenterOptions.Builder()
                        .setDetectorMode(SelfieSegmenterOptions.SINGLE_IMAGE_MODE)
                        .build()
        );
        FaceMeshDetector faceDetector = FaceMeshDetection.getClient();

        SegmentationMask mask;
        List<FaceMesh> faces;
        try {
            InputImage image = InputImage.fromBitmap(working, 0);
            mask = Tasks.await(segmenter.process(image), 25, TimeUnit.SECONDS);
            try {
                faces = Tasks.await(faceDetector.process(image), 20, TimeUnit.SECONDS);
            } catch (Exception ignored) {
                faces = java.util.Collections.emptyList();
            }
        } finally {
            try { segmenter.close(); } catch (Exception ignored) {}
            try { faceDetector.close(); } catch (Exception ignored) {}
        }

        MaskStats stats = readMask(mask, working.getWidth(), working.getHeight());
        Rect face = faces.isEmpty() ? null : largestFace(faces);
        foreground = buildForeground(working, stats);
        parts = buildSubjectParts(foreground, stats, face);
        background = buildReconstructedBackground(working, stats);

        CreativeWorkspace workspace = new CreativeWorkspace(context);
        File projectRoot = workspace.projectRoot(projectId);
        File dir = new File(new File(new File(projectRoot, "rigs"), "layers"), safe(asset.id));
        if (!dir.exists() && !dir.mkdirs()) throw new IllegalStateException("Could not create animation layer directory");

        File fgFile = new File(dir, "subject.png");
        File headFile = new File(dir, "head_hair.png");
        File torsoFile = new File(dir, "torso.png");
        File lowerFile = new File(dir, "lower_drape.png");
        File bgFile = new File(dir, "background.jpg");
        write(foreground, fgFile, Bitmap.CompressFormat.PNG, 100);
        write(parts.head, headFile, Bitmap.CompressFormat.PNG, 100);
        write(parts.torso, torsoFile, Bitmap.CompressFormat.PNG, 100);
        write(parts.lower, lowerFile, Bitmap.CompressFormat.PNG, 100);
        write(background, bgFile, Bitmap.CompressFormat.JPEG, 95);

        JSONObject analysis = new JSONObject();
        analysis.put("engine", "bundled-on-device-portrait-ai");
        analysis.put("memoryBoundedDecode",true);
        analysis.put("analysisEdgeLimit",edge);
        analysis.put("backgroundReconstruction", "mask-aware-edge-fill-v1");
        analysis.put("articulatedLayering", "head-torso-lower-v1");
        analysis.put("articulatedParts", true);
        analysis.put("subjectDetected", stats.coverage > 0.015);
        analysis.put("subjectCoverage", stats.coverage);
        analysis.put("subjectCenterX", stats.centerX);
        analysis.put("subjectCenterY", stats.centerY);
        analysis.put("subjectLeft", stats.left);
        analysis.put("subjectTop", stats.top);
        analysis.put("subjectRight", stats.right);
        analysis.put("subjectBottom", stats.bottom);
        analysis.put("maskWidth", mask.getWidth());
        analysis.put("maskHeight", mask.getHeight());

        if (face != null) {
            double faceCx = clamp01((face.exactCenterX()) / working.getWidth());
            double faceCy = clamp01((face.exactCenterY()) / working.getHeight());
            double faceCoverage = clamp01((face.width() * (double) face.height())
                    / (working.getWidth() * (double) working.getHeight()));
            analysis.put("faceDetected", true);
            analysis.put("faceCenterX", faceCx);
            analysis.put("faceCenterY", faceCy);
            analysis.put("faceCoverage", faceCoverage);
        } else {
            analysis.put("faceDetected", false);
            analysis.put("faceCenterX", stats.centerX);
            analysis.put("faceCenterY", Math.max(0.2, stats.centerY - 0.18));
            analysis.put("faceCoverage", 0);
        }

        analysis.put("shotType", shotType(stats.coverage, analysis.optDouble("faceCoverage", 0)));
        analysis.put("foregroundUri", Uri.fromFile(fgFile).toString());
        analysis.put("headUri", Uri.fromFile(headFile).toString());
        analysis.put("torsoUri", Uri.fromFile(torsoFile).toString());
        analysis.put("lowerUri", Uri.fromFile(lowerFile).toString());
        analysis.put("backgroundUri", Uri.fromFile(bgFile).toString());
        analysis.put("headSplitY", parts.headSplitY);
        analysis.put("torsoSplitY", parts.torsoSplitY);
        analysis.put("width", working.getWidth());
        analysis.put("height", working.getHeight());
        analysis.put("workspaceRelativePath", "rigs/layers/" + safe(asset.id));
        analysis.put("coldTierPortable", true);

        File rigMetadata = new File(dir, "rig.json");
        try (FileOutputStream out = new FileOutputStream(rigMetadata)) {
            out.write(analysis.toString(2).getBytes(StandardCharsets.UTF_8));
            out.flush();
            out.getFD().sync();
        }

        return new Result(
                Uri.fromFile(fgFile),
                Uri.fromFile(headFile),
                Uri.fromFile(torsoFile),
                Uri.fromFile(lowerFile),
                Uri.fromFile(bgFile),
                analysis,
                mask.getWidth(),
                mask.getHeight()
        );
        }finally{
            if(parts!=null)PortraitMemoryAdmission.recycle(parts.head,parts.torso,parts.lower);
            PortraitMemoryAdmission.recycle(foreground,background,working,original);
        }
    }

    private Bitmap decode(String uriString,int limit) throws Exception {
        Uri uri = Uri.parse(uriString);
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        try (InputStream in = context.getContentResolver().openInputStream(uri)) {
            if (in == null) throw new IllegalStateException("Could not open portrait source");
            BitmapFactory.decodeStream(in, null, bounds);
        }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw new IllegalArgumentException("Unsupported portrait image");
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inSampleSize = 1;
        while (Math.max(bounds.outWidth, bounds.outHeight) / options.inSampleSize > limit)
            options.inSampleSize *= 2;
        options.inPreferredConfig = Bitmap.Config.ARGB_8888;
        // Subsample at decode time: never allocate the full camera image merely to shrink it.
        try (InputStream in = context.getContentResolver().openInputStream(uri)) {
            if (in == null) throw new IllegalStateException("Could not reopen portrait source");
            return BitmapFactory.decodeStream(in, null, options);
        }
    }

    private Bitmap scaleForAnalysis(Bitmap source,int limit) {
        int w = source.getWidth();
        int h = source.getHeight();
        int max = Math.max(w, h);
        if (max <= limit) return source;
        float scale = limit / (float) max;
        return Bitmap.createScaledBitmap(source, Math.max(2, Math.round(w * scale)), Math.max(2, Math.round(h * scale)), true);
    }

    private static final class MaskStats {
        final float[] values;
        final int maskWidth;
        final int maskHeight;
        final double coverage;
        final double centerX;
        final double centerY;
        final double left;
        final double top;
        final double right;
        final double bottom;

        MaskStats(float[] values, int maskWidth, int maskHeight,
                  double coverage, double centerX, double centerY,
                  double left, double top, double right, double bottom) {
            this.values = values;
            this.maskWidth = maskWidth;
            this.maskHeight = maskHeight;
            this.coverage = coverage;
            this.centerX = centerX;
            this.centerY = centerY;
            this.left = left;
            this.top = top;
            this.right = right;
            this.bottom = bottom;
        }
    }

    private MaskStats readMask(SegmentationMask mask, int imageW, int imageH) {
        int mw = Math.max(1, mask.getWidth());
        int mh = Math.max(1, mask.getHeight());
        float[] values = new float[mw * mh];
        ByteBuffer buffer = mask.getBuffer().duplicate().order(ByteOrder.nativeOrder());
        buffer.rewind();

        int minX = mw, minY = mh, maxX = -1, maxY = -1;
        double sx = 0, sy = 0, weight = 0;
        int strong = 0;
        for (int y = 0; y < mh; y++) {
            for (int x = 0; x < mw; x++) {
                int i = y * mw + x;
                float confidence = buffer.remaining() >= 4 ? buffer.getFloat() : 0f;
                confidence = Math.max(0f, Math.min(1f, confidence));
                values[i] = confidence;
                if (confidence > 0.42f) {
                    minX = Math.min(minX, x);
                    minY = Math.min(minY, y);
                    maxX = Math.max(maxX, x);
                    maxY = Math.max(maxY, y);
                    strong++;
                }
                if (confidence > 0.2f) {
                    double w = confidence * confidence;
                    sx += x * w;
                    sy += y * w;
                    weight += w;
                }
            }
        }

        boolean found = maxX >= minX && maxY >= minY;
        double cx = weight > 0 ? sx / weight / Math.max(1, mw - 1) : 0.5;
        double cy = weight > 0 ? sy / weight / Math.max(1, mh - 1) : 0.5;
        double coverage = strong / (double) Math.max(1, mw * mh);
        double l = found ? minX / (double) Math.max(1, mw - 1) : 0.15;
        double t = found ? minY / (double) Math.max(1, mh - 1) : 0.10;
        double r = found ? maxX / (double) Math.max(1, mw - 1) : 0.85;
        double b = found ? maxY / (double) Math.max(1, mh - 1) : 0.95;

        return new MaskStats(values, mw, mh, coverage, clamp01(cx), clamp01(cy),
                clamp01(l), clamp01(t), clamp01(r), clamp01(b));
    }

    /** Memory-bounded foreground extraction: process 64-row tiles instead of
     * a full second ARGB int[] while person segmentation and face mesh live in RAM. */
    private Bitmap buildForeground(Bitmap source, MaskStats stats) {
        int w=source.getWidth(),h=source.getHeight();
        Bitmap out=Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888);
        int rows=Math.max(1,Math.min(64,65536/Math.max(1,w)));
        int[] pixels=new int[w*rows];
        for(int top=0;top<h;top+=rows){
            if(Thread.currentThread().isInterrupted())throw new IllegalStateException("Portrait separation cancelled");
            int count=Math.min(rows,h-top);
            source.getPixels(pixels,0,w,0,top,w,count);
            for(int localY=0;localY<count;localY++){
                int y=top+localY;
                int my=Math.min(stats.maskHeight-1,Math.max(0,
                        Math.round(y*(stats.maskHeight-1f)/Math.max(1,h-1))));
                for(int x=0;x<w;x++){
                    int mx=Math.min(stats.maskWidth-1,Math.max(0,
                            Math.round(x*(stats.maskWidth-1f)/Math.max(1,w-1))));
                    float confidence=stats.values[my*stats.maskWidth+mx];
                    float alpha=smoothstep(.18f,.82f,confidence);
                    int index=localY*w+x,color=pixels[index],a=Color.alpha(color);
                    pixels[index]=(color&0x00ffffff)|(Math.round(a*alpha)<<24);
                }
            }
            out.setPixels(pixels,0,w,0,top,w,count);
        }
        return out;
    }

    private static final class SubjectParts {
        final Bitmap head;
        final Bitmap torso;
        final Bitmap lower;
        final double headSplitY;
        final double torsoSplitY;

        SubjectParts(Bitmap head, Bitmap torso, Bitmap lower, double headSplitY, double torsoSplitY) {
            this.head = head;
            this.torso = torso;
            this.lower = lower;
            this.headSplitY = headSplitY;
            this.torsoSplitY = torsoSplitY;
        }
    }

    /** Process one bounded tile at a time. Previous code held FOUR full-frame
     * int[] arrays *plus* three ARGB output Bitmaps simultaneously. */
    private SubjectParts buildSubjectParts(Bitmap foreground,MaskStats stats,Rect face){
        int w=foreground.getWidth(),h=foreground.getHeight();
        float subjectTop=(float)(stats.top*h);
        float subjectBottom=(float)(stats.bottom*h);
        float subjectHeight=Math.max(32f,subjectBottom-subjectTop);
        float headSplit=subjectTop+subjectHeight*.31f;
        if(face!=null){
            headSplit=Math.max(headSplit,face.bottom+subjectHeight*.055f);
            headSplit=Math.min(subjectTop+subjectHeight*.43f,headSplit);
        }
        float torsoSplit=subjectTop+subjectHeight*.68f;
        torsoSplit=Math.max(headSplit+subjectHeight*.18f,torsoSplit);
        torsoSplit=Math.min(subjectBottom-subjectHeight*.10f,torsoSplit);
        float headOverlap=Math.max(10f,subjectHeight*.055f);
        float lowerOverlap=Math.max(12f,subjectHeight*.070f);
        Bitmap headBitmap=Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888);
        Bitmap torsoBitmap=Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888);
        Bitmap lowerBitmap=Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888);
        int rows=Math.max(1,Math.min(64,65536/Math.max(1,w)));
        int[] source=new int[w*rows],head=new int[w*rows],
                torso=new int[w*rows],lower=new int[w*rows];
        for(int top=0;top<h;top+=rows){
            if(Thread.currentThread().isInterrupted())throw new IllegalStateException("Portrait cutouts cancelled");
            int count=Math.min(rows,h-top);
            foreground.getPixels(source,0,w,0,top,w,count);
            for(int localY=0;localY<count;localY++){
                int y=top+localY;
                float headFade=1f-smoothstep(headSplit-headOverlap,headSplit+headOverlap,y);
                float torsoIn=smoothstep(headSplit-headOverlap,headSplit+headOverlap,y);
                float torsoOut=1f-smoothstep(torsoSplit-lowerOverlap,torsoSplit+lowerOverlap,y);
                float torsoWeight=torsoIn*torsoOut;
                float lowerWeight=smoothstep(torsoSplit-lowerOverlap,torsoSplit+lowerOverlap,y);
                float sum=headFade+torsoWeight+lowerWeight;
                if(sum<.0001f)sum=1f;
                headFade/=sum;torsoWeight/=sum;lowerWeight/=sum;
                for(int x=0;x<w;x++){
                    int i=localY*w+x,color=source[i],a=Color.alpha(color),rgb=color&0x00ffffff;
                    head[i]=rgb|(Math.round(a*headFade)<<24);
                    torso[i]=rgb|(Math.round(a*torsoWeight)<<24);
                    lower[i]=rgb|(Math.round(a*lowerWeight)<<24);
                }
            }
            headBitmap.setPixels(head,0,w,0,top,w,count);
            torsoBitmap.setPixels(torso,0,w,0,top,w,count);
            lowerBitmap.setPixels(lower,0,w,0,top,w,count);
        }
        return new SubjectParts(headBitmap,torsoBitmap,lowerBitmap,
                clamp01(headSplit/Math.max(1f,h)),clamp01(torsoSplit/Math.max(1f,h)));
    }

    /** Preserve original background fill pixels, but use a 64-row working set.
     * Retaining the source array is necessary for random subject-edge sampling.
     * Soft/output arrays are now tiles rather than two entire 1440px plates. */
    private Bitmap buildReconstructedBackground(Bitmap source,MaskStats stats){
        int w=source.getWidth(),h=source.getHeight();
        int sw=Math.max(10,w/30),sh=Math.max(10,h/30);
        Bitmap tiny=Bitmap.createScaledBitmap(source,sw,sh,true);
        Bitmap blurred=Bitmap.createScaledBitmap(tiny,w,h,true);
        tiny.recycle();
        int[] original=new int[w*h];
        source.getPixels(original,0,w,0,0,w,h);

        int left=Math.max(0,Math.min(w-1,(int)Math.floor(stats.left*(w-1))));
        int top=Math.max(0,Math.min(h-1,(int)Math.floor(stats.top*(h-1))));
        int right=Math.max(left,Math.min(w-1,(int)Math.ceil(stats.right*(w-1))));
        int bottom=Math.max(top,Math.min(h-1,(int)Math.ceil(stats.bottom*(h-1))));
        int marginX=Math.max(8,(right-left)/16);
        int marginY=Math.max(8,(bottom-top)/18);
        int rows=Math.max(1,Math.min(64,65536/Math.max(1,w)));
        int[] soft=new int[w*rows],output=new int[w*rows];
        Bitmap reconstructed=Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888);
        try{
            for(int rowStart=0;rowStart<h;rowStart+=rows){
                if(Thread.currentThread().isInterrupted())throw new IllegalStateException("Portrait background cancelled");
                int count=Math.min(rows,h-rowStart);
                blurred.getPixels(soft,0,w,0,rowStart,w,count);
                for(int localY=0;localY<count;localY++){
                    int y=rowStart+localY,my=maskY(y,h,stats.maskHeight);
                    for(int x=0;x<w;x++){
                        int mx=maskX(x,w,stats.maskWidth);
                        float confidence=dilatedConfidence(stats,mx,my);
                        float replace=smoothstep(.08f,.70f,confidence);
                        int i=y*w+x,tileIndex=localY*w+x;
                        if(replace<=.001f){output[tileIndex]=original[i];continue;}
                        int dl=Math.abs(x-left),dr=Math.abs(right-x),
                                dt=Math.abs(y-top),db=Math.abs(bottom-y);
                        int sx=x,sy=y;
                        int nearest=Math.min(Math.min(dl,dr),Math.min(dt,db));
                        if(nearest==dl)sx=Math.max(0,left-marginX);
                        else if(nearest==dr)sx=Math.min(w-1,right+marginX);
                        else if(nearest==dt)sy=Math.max(0,top-marginY);
                        else sy=Math.min(h-1,bottom+marginY);
                        int edge=original[sy*w+sx],broad=soft[tileIndex];
                        int fill=mixColor(edge,broad,.42f);
                        float interior=smoothstep(.28f,.78f,confidence);
                        float alpha=Math.max(replace*.82f,interior);
                        output[tileIndex]=mixColor(original[i],fill,alpha);
                    }
                }
                reconstructed.setPixels(output,0,w,0,rowStart,w,count);
            }
        }finally{blurred.recycle();}
        Bitmap graded=Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888);
        Canvas canvas=new Canvas(graded);
        Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG|Paint.FILTER_BITMAP_FLAG);
        ColorMatrix matrix=new ColorMatrix();
        matrix.setSaturation(.95f);
        ColorMatrix exposure=new ColorMatrix(new float[]{
                .97f,0,0,0,-2,
                0,.97f,0,0,-2,
                0,0,.97f,0,-2,
                0,0,0,1,0
        });
        matrix.postConcat(exposure);
        paint.setColorFilter(new ColorMatrixColorFilter(matrix));
        canvas.drawBitmap(reconstructed,0,0,paint);
        reconstructed.recycle();
        return graded;
    }

    private int maskX(int x, int imageWidth, int maskWidth) {
        return Math.min(maskWidth - 1,
                Math.max(0, Math.round(x * (maskWidth - 1f) / Math.max(1, imageWidth - 1))));
    }

    private int maskY(int y, int imageHeight, int maskHeight) {
        return Math.min(maskHeight - 1,
                Math.max(0, Math.round(y * (maskHeight - 1f) / Math.max(1, imageHeight - 1))));
    }

    private float dilatedConfidence(MaskStats stats, int mx, int my) {
        int rx = Math.max(2, stats.maskWidth / 180);
        int ry = Math.max(2, stats.maskHeight / 180);
        float best = 0f;
        int[] offsets = {-1, 0, 1};
        for (int oy : offsets) {
            int y = Math.max(0, Math.min(stats.maskHeight - 1, my + oy * ry));
            for (int ox : offsets) {
                int x = Math.max(0, Math.min(stats.maskWidth - 1, mx + ox * rx));
                best = Math.max(best, stats.values[y * stats.maskWidth + x]);
            }
        }
        return best;
    }

    private static int mixColor(int a, int b, float amount) {
        float t = Math.max(0f, Math.min(1f, amount));
        int aa = Color.alpha(a), ar = Color.red(a), ag = Color.green(a), ab = Color.blue(a);
        int ba = Color.alpha(b), br = Color.red(b), bg = Color.green(b), bb = Color.blue(b);
        return Color.argb(
                Math.round(aa + (ba - aa) * t),
                Math.round(ar + (br - ar) * t),
                Math.round(ag + (bg - ag) * t),
                Math.round(ab + (bb - ab) * t)
        );
    }

    private static Rect largestFace(List<FaceMesh> faces) {
        Rect best = null;
        long bestArea = -1;
        for (FaceMesh mesh : faces) {
            Rect r = mesh.getBoundingBox();
            long area = r.width() * (long) r.height();
            if (area > bestArea) {
                bestArea = area;
                best = r;
            }
        }
        return best;
    }

    private static String shotType(double subjectCoverage, double faceCoverage) {
        if (faceCoverage > 0.10 || subjectCoverage > 0.58) return "close";
        if (faceCoverage > 0.035 || subjectCoverage > 0.30) return "medium";
        return "wide";
    }

    private static float smoothstep(float edge0, float edge1, float x) {
        float t = Math.max(0f, Math.min(1f, (x - edge0) / Math.max(0.0001f, edge1 - edge0)));
        return t * t * (3f - 2f * t);
    }

    private void write(Bitmap bitmap, File file, Bitmap.CompressFormat format, int quality) throws Exception {
        try (FileOutputStream out = new FileOutputStream(file)) {
            if (!bitmap.compress(format, quality, out)) throw new IllegalStateException("Could not write animation layer");
            out.flush();
        }
    }

    private static String safe(String value) {
        String v = value == null ? "asset" : value.replaceAll("[^a-zA-Z0-9._-]+", "_");
        return v.isEmpty() ? "asset" : v;
    }

    private static double clamp01(double v) {
        return Math.max(0d, Math.min(1d, v));
    }
}

