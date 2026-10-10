package com.rezoxnemesis.videostudio;

import android.content.Context;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Paint;
import android.graphics.Rect;
import android.net.Uri;
import android.provider.DocumentsContract;

import com.google.android.gms.tasks.Task;
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
import org.json.JSONArray;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

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

    private static final int MAX_ANALYSIS_EDGE = 1440;
    private static final int SOURCE_PROOF_VERSION = 1;
    private static final String[] LAYER_NAMES = {"subject.png", "head_hair.png", "torso.png", "lower_drape.png", "background.jpg"};
    private static final String[] LAYER_URI_KEYS = {"foregroundUri", "headUri", "torsoUri", "lowerUri", "backgroundUri"};

    private final Context context;

    public NativePortraitMotionAnalyzer(Context context) {
        this.context = context.getApplicationContext();
    }

    public Result analyseAndBuildLayers(ProjectStore.Asset asset, String projectId) throws Exception {
        if (asset == null) throw new IllegalArgumentException("Missing image asset");
        if (asset.mime == null || !asset.mime.startsWith("image/")) {
            throw new IllegalArgumentException("Portrait animation requires an image asset");
        }
        ProjectStore.Asset captured=ProjectStore.Asset.fromJson(new JSONObject(asset.toJson().toString()));
        JSONObject sourceIdentity=liveSourceIdentity(captured);
        Bitmap original = decode(captured.uri);
        if (original == null) throw new IllegalStateException("Could not decode image");
        Bitmap working;
        try { working = scaleForAnalysis(original); }
        catch (RuntimeException | Error failedScale) { recycle(original); throw failedScale; }
        if (working != original) original.recycle();
        Bitmap foreground=null,background=null;SubjectParts parts=null;File dir=null;
        Segmenter segmenter=null;FaceMeshDetector faceDetector=null;
        Task<SegmentationMask> segmentationTask=null;Task<List<FaceMesh>> faceTask=null;
        boolean completed=false;
        try {
        checkInterrupted();
        JSONObject sourceProof=new JSONObject().put("version",SOURCE_PROOF_VERSION)
                .put("sourceAssetId",captured.id).put("sourceUri",captured.uri)
                .put("pixelProofScope","bounded subsampled analysis pixels and dimensions; original compressed bytes are not scanned")
                .put("registrationHash",registrationHash(captured)).put("identity",sourceIdentity)
                .put("identityHash",hashText(canonical(sourceIdentity))).put("pixelsSha256",hashPixels(working));
        if(!sourceProof.getString("identityHash").equals(hashText(canonical(liveSourceIdentity(captured)))))
            throw new IllegalStateException("Portrait source changed while it was being decoded");
        segmenter = Segmentation.getClient(
                new SelfieSegmenterOptions.Builder()
                        .setDetectorMode(SelfieSegmenterOptions.SINGLE_IMAGE_MODE)
                        .build()
        );
        faceDetector = FaceMeshDetection.getClient();

        SegmentationMask mask;
        List<FaceMesh> faces;
            InputImage image = InputImage.fromBitmap(working, 0);
            segmentationTask=segmenter.process(image);
            mask = Tasks.await(segmentationTask, 25, TimeUnit.SECONDS);
            try {
                faceTask=faceDetector.process(image);
                faces = Tasks.await(faceTask, 20, TimeUnit.SECONDS);
            } catch (InterruptedException interrupted) {
                throw interrupted;
            } catch (Exception ignored) {
                faces = java.util.Collections.emptyList();
            }
        checkInterrupted();
        MaskStats stats = readMask(mask, working.getWidth(), working.getHeight());
        Rect face = faces.isEmpty() ? null : largestFace(faces);
        foreground = buildForeground(working, stats);
        parts = buildSubjectParts(foreground, stats, face);
        background = buildReconstructedBackground(working, stats);

        CreativeWorkspace workspace = new CreativeWorkspace(context);
        File projectRoot = workspace.projectRoot(projectId);
        String generationId=UUID.randomUUID().toString();
        File parent = new File(new File(new File(projectRoot, "rigs"), "layers"), safe(captured.id));
        if(!parent.isDirectory()&&!parent.mkdirs())throw new IllegalStateException("Could not create animation layer directory");
        File generation=new File(parent,generationId);
        if(!generation.mkdir())throw new IllegalStateException("Could not create an immutable portrait generation");
        dir=generation;

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
        analysis.put("generationId",generationId);
        analysis.put("sourceProof",sourceProof);
        analysis.put("artifactVersion",1);
        analysis.put("createdAt",System.currentTimeMillis());
        analysis.put("engine", "bundled-on-device-portrait-ai");
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
        analysis.put("workspaceRelativePath", "rigs/layers/" + safe(captured.id)+"/"+generationId);
        analysis.put("coldTierPortable", true);
        JSONObject artifacts=new JSONObject();
        for(String name:LAYER_NAMES){checkInterrupted();File layer=new File(dir,name);
            artifacts.put(name,new JSONObject().put("bytes",layer.length()).put("sha256",hashFile(layer)));}
        analysis.put("artifacts",artifacts);
        // Reopen the bounded source decode: provider metadata alone cannot prove
        // an unchanged same-URI image when the provider omits modification time.
        assertSourceFresh(captured,analysis);

        File rigMetadata = new File(dir, "rig.json");
        if(!rigMetadata.createNewFile())throw new IllegalStateException("Portrait generation metadata already exists");
        try (FileOutputStream out = new FileOutputStream(rigMetadata)) {
            out.write(analysis.toString(2).getBytes(StandardCharsets.UTF_8));
            out.flush();
            out.getFD().sync();
        }

        completed=true;
        return new Result(
                Uri.fromFile(fgFile),
                Uri.fromFile(headFile),
                Uri.fromFile(torsoFile),
                Uri.fromFile(lowerFile),
                Uri.fromFile(bgFile),
                analysis,
                working.getWidth(),
                working.getHeight()
        );
        }finally{
            if(segmenter!=null)try{segmenter.close();}catch(Exception ignored){}
            if(faceDetector!=null)try{faceDetector.close();}catch(Exception ignored){}
            recycle(foreground);recycle(background);
            if(parts!=null){recycle(parts.head);recycle(parts.torso);recycle(parts.lower);}
            recycleAfterInference(working,segmentationTask,faceTask);
            if(!completed&&dir!=null)cleanupOwnGeneration(dir);
        }
    }

    /** Cheap registration guard for a fresh project transaction after the worker
     * has performed assertSourceFresh outside the project lock. */
    public static void assertRegisteredSource(ProjectStore.Asset current,JSONObject analysis)throws Exception {
        JSONObject proof=sourceProof(analysis);
        if(current==null||!proof.getString("registrationHash").equals(registrationHash(current)))
            throw new IllegalStateException("Portrait source registration changed; regenerate its layers");
    }

    /** Worker-only freshness check; never call on a playback/UI tick. */
    public void assertSourceFresh(ProjectStore.Asset current,JSONObject analysis)throws Exception {
        assertRegisteredSource(current,analysis);JSONObject proof=sourceProof(analysis);
        String expected=proof.getString("identityHash");
        if(!expected.equals(hashText(canonical(liveSourceIdentity(current)))))
            throw new IllegalStateException("Portrait source changed; cached layers cannot be reused");
        Bitmap decoded=null,working=null;
        try{decoded=decode(current.uri);if(decoded==null)throw new IllegalStateException("Portrait source no longer decodes");
            working=scaleForAnalysis(decoded);if(working!=decoded){decoded.recycle();decoded=null;}
            if(!proof.getString("pixelsSha256").equals(hashPixels(working)))throw new IllegalStateException("Portrait source pixels changed; regenerate its layers");
        }finally{recycle(working);if(decoded!=working)recycle(decoded);}
        if(!expected.equals(hashText(canonical(liveSourceIdentity(current)))))
            throw new IllegalStateException("Portrait source changed during freshness validation");
    }

    /** Worker-only integrity check of all files owned by one immutable generation. */
    public void assertLayerFilesValid(JSONObject analysis)throws Exception {
        sourceProof(analysis);
        if(analysis.optInt("artifactVersion",0)!=1)throw new IllegalStateException("Portrait layers have no supported integrity manifest");
        String generationId=analysis.getString("generationId");UUID.fromString(generationId);
        JSONObject artifacts=analysis.getJSONObject("artifacts");File generation=null;
        for(int i=0;i<LAYER_NAMES.length;i++){
            Uri uri=Uri.parse(analysis.getString(LAYER_URI_KEYS[i]));
            if(!"file".equals(uri.getScheme())||uri.getPath()==null)throw new IllegalStateException("Portrait layer is not a materialized local generation");
            File file=new File(uri.getPath()).getCanonicalFile();File parent=file.getParentFile();
            if(parent==null||!generationId.equals(parent.getName())||!LAYER_NAMES[i].equals(file.getName()))throw new IllegalStateException("Portrait layer escaped its immutable generation");
            if(generation==null)generation=parent;else if(!generation.equals(parent))throw new IllegalStateException("Portrait layers refer to different generations");
            JSONObject record=artifacts.getJSONObject(LAYER_NAMES[i]);
            if(!file.isFile()||!file.canRead()||file.length()<=0||file.length()!=record.getLong("bytes")||!hashFile(file).equals(record.getString("sha256")))
                throw new IllegalStateException("Portrait "+LAYER_NAMES[i]+" is missing or changed; regenerate its layers");
        }
        File metadata=new File(generation,"rig.json");
        if(!metadata.isFile()||!metadata.canRead()||metadata.length()>1024*1024)throw new IllegalStateException("Portrait generation metadata is unavailable");
        try(InputStream input=new FileInputStream(metadata)){
            java.io.ByteArrayOutputStream bytes=new java.io.ByteArrayOutputStream();byte[] buffer=new byte[8192];int read;
            while((read=input.read(buffer))!=-1){checkInterrupted();if(bytes.size()+read>1024*1024)throw new IllegalStateException("Portrait metadata exceeds its bounded size");bytes.write(buffer,0,read);}
            JSONObject stored=new JSONObject(bytes.toString("UTF-8"));
            if(!generationId.equals(stored.optString("generationId"))||!canonical(artifacts).equals(canonical(stored.getJSONObject("artifacts")))
                    ||!canonical(sourceProof(analysis)).equals(canonical(sourceProof(stored))))throw new IllegalStateException("Portrait generation manifest does not match its published result");
        }
    }

    private JSONObject liveSourceIdentity(ProjectStore.Asset asset)throws Exception {
        if(asset==null||asset.uri==null||asset.uri.isEmpty())throw new IllegalStateException("Portrait source is unavailable");
        checkInterrupted();Uri uri=Uri.parse(asset.uri);AssetProbe.Result probe=AssetProbe.probe(context.getContentResolver(),uri);
        if(!probe.readable)throw new IllegalStateException("Portrait source is no longer readable");
        JSONObject identity=new JSONObject().put("registrationHash",registrationHash(asset)).put("sizeBytes",probe.sizeBytes).put("providerAuthority",probe.providerAuthority);
        long modified=-1;
        if("file".equals(uri.getScheme())&&uri.getPath()!=null){File file=new File(uri.getPath());modified=file.lastModified();identity.put("localBytes",file.length());}
        else if("content".equals(uri.getScheme()))try(Cursor cursor=context.getContentResolver().query(uri,new String[]{DocumentsContract.Document.COLUMN_LAST_MODIFIED},null,null,null)){
            if(cursor!=null&&cursor.moveToFirst()){int column=cursor.getColumnIndex(DocumentsContract.Document.COLUMN_LAST_MODIFIED);if(column>=0&&!cursor.isNull(column))modified=cursor.getLong(column);}
        }catch(Exception ignored){}
        return identity.put("modifiedMs",modified);
    }
    private static JSONObject sourceProof(JSONObject analysis)throws Exception {
        JSONObject proof=analysis==null?null:analysis.optJSONObject("sourceProof");
        if(proof==null||proof.optInt("version",0)!=SOURCE_PROOF_VERSION||proof.optString("registrationHash","").length()!=64
                ||proof.optString("identityHash","").length()!=64||proof.optString("pixelsSha256","").length()!=64)
            throw new IllegalStateException("Portrait result has no current source proof; regenerate its layers");
        return proof;
    }
    private static String registrationHash(ProjectStore.Asset asset)throws Exception {
        JSONObject identity=new JSONObject().put("assetId",asset.id).put("uri",asset.uri).put("mime",asset.mime).put("sizeBytes",asset.sizeBytes)
                .put("width",asset.width).put("height",asset.height).put("rotation",asset.rotation).put("generated",asset.generated).put("role",asset.role)
                .put("importMetadata",asset.importMetadata==null?new JSONObject():asset.importMetadata)
                .put("generationMetadata",asset.generationMetadata==null?new JSONObject():asset.generationMetadata);
        return hashText(canonical(identity));
    }
    private static String hashPixels(Bitmap bitmap)throws Exception {
        MessageDigest digest=MessageDigest.getInstance("SHA-256");int width=bitmap.getWidth(),height=bitmap.getHeight();
        digest.update(ByteBuffer.allocate(8).putInt(width).putInt(height).array());int[] pixels=new int[width];ByteBuffer row=ByteBuffer.allocate(width*4);
        for(int y=0;y<height;y++){checkInterrupted();bitmap.getPixels(pixels,0,width,0,y,width,1);row.clear();for(int pixel:pixels)row.putInt(pixel);digest.update(row.array());}
        return hex(digest.digest());
    }
    private static String hashFile(File file)throws Exception {
        MessageDigest digest=MessageDigest.getInstance("SHA-256");byte[] buffer=new byte[65536];
        try(InputStream input=new FileInputStream(file)){int read;while((read=input.read(buffer))!=-1){checkInterrupted();digest.update(buffer,0,read);}}
        return hex(digest.digest());
    }
    private static String hashText(String text)throws Exception {return hex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));}
    private static String hex(byte[] bytes){StringBuilder result=new StringBuilder();for(byte value:bytes)result.append(String.format(java.util.Locale.US,"%02x",value&255));return result.toString();}
    private static String canonical(Object value)throws Exception {
        if(value instanceof JSONObject){JSONObject object=(JSONObject)value;ArrayList<String> keys=new ArrayList<>();java.util.Iterator<String> iterator=object.keys();while(iterator.hasNext())keys.add(iterator.next());Collections.sort(keys);
            StringBuilder result=new StringBuilder("{");for(int i=0;i<keys.size();i++){if(i>0)result.append(',');String key=keys.get(i);result.append(JSONObject.quote(key)).append(':').append(canonical(object.get(key)));}return result.append('}').toString();}
        if(value instanceof JSONArray){JSONArray array=(JSONArray)value;StringBuilder result=new StringBuilder("[");for(int i=0;i<array.length();i++){if(i>0)result.append(',');result.append(canonical(array.get(i)));}return result.append(']').toString();}
        return value instanceof String?JSONObject.quote((String)value):String.valueOf(value);
    }
    private static void checkInterrupted()throws InterruptedException {if(Thread.currentThread().isInterrupted())throw new InterruptedException("Portrait generation cancelled");}
    private static void recycle(Bitmap bitmap){if(bitmap!=null&&!bitmap.isRecycled())bitmap.recycle();}
    private static void recycleAfterInference(Bitmap bitmap,Task<?> segmentation,Task<?> face){
        AtomicBoolean released=new AtomicBoolean();Runnable release=()->{if((segmentation==null||segmentation.isComplete())&&(face==null||face.isComplete())&&released.compareAndSet(false,true))recycle(bitmap);};
        if(segmentation!=null&&!segmentation.isComplete())segmentation.addOnCompleteListener(Runnable::run,ignored->release.run());
        if(face!=null&&!face.isComplete())face.addOnCompleteListener(Runnable::run,ignored->release.run());release.run();
    }
    private static void cleanupOwnGeneration(File directory){
        for(String name:LAYER_NAMES)new File(directory,name).delete();new File(directory,"rig.json").delete();directory.delete();
    }

    private Bitmap decode(String uriString) throws Exception {
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
        while (Math.max(bounds.outWidth, bounds.outHeight) / options.inSampleSize > MAX_ANALYSIS_EDGE)
            options.inSampleSize *= 2;
        options.inPreferredConfig = Bitmap.Config.ARGB_8888;
        // Subsample at decode time: never allocate the full camera image merely to shrink it.
        Bitmap decoded = null;
        try (InputStream in = context.getContentResolver().openInputStream(uri)) {
            if (in == null) throw new IllegalStateException("Could not reopen portrait source");
            decoded = BitmapFactory.decodeStream(in, null, options);
            checkInterrupted();
            return decoded;
        } catch (Exception | Error failedDecode) { recycle(decoded); throw failedDecode; }
    }

    private Bitmap scaleForAnalysis(Bitmap source) {
        int w = source.getWidth();
        int h = source.getHeight();
        int max = Math.max(w, h);
        if (max <= MAX_ANALYSIS_EDGE) return source;
        float scale = MAX_ANALYSIS_EDGE / (float) max;
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

    private Bitmap buildForeground(Bitmap source, MaskStats stats) {
        int w = source.getWidth(), h = source.getHeight();
        int[] pixels = new int[w * h];
        source.getPixels(pixels, 0, w, 0, 0, w, h);

        for (int y = 0; y < h; y++) {
            int my = Math.min(stats.maskHeight - 1,
                    Math.max(0, Math.round(y * (stats.maskHeight - 1f) / Math.max(1, h - 1))));
            for (int x = 0; x < w; x++) {
                int mx = Math.min(stats.maskWidth - 1,
                        Math.max(0, Math.round(x * (stats.maskWidth - 1f) / Math.max(1, w - 1))));
                float confidence = stats.values[my * stats.maskWidth + mx];
                float alpha = smoothstep(0.18f, 0.82f, confidence);
                int color = pixels[y * w + x];
                int originalAlpha = (color >>> 24) & 0xff;
                int a = Math.round(originalAlpha * alpha);
                pixels[y * w + x] = (color & 0x00ffffff) | (a << 24);
            }
        }

        Bitmap out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        out.setPixels(pixels, 0, w, 0, 0, w, h);
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

    private SubjectParts buildSubjectParts(Bitmap foreground, MaskStats stats, Rect face) {
        int w = foreground.getWidth();
        int h = foreground.getHeight();
        int[] source = new int[w * h];
        foreground.getPixels(source, 0, w, 0, 0, w, h);

        float subjectTop = (float) (stats.top * h);
        float subjectBottom = (float) (stats.bottom * h);
        float subjectHeight = Math.max(32f, subjectBottom - subjectTop);

        float headSplit = subjectTop + subjectHeight * .31f;
        if (face != null) {
            headSplit = Math.max(headSplit, face.bottom + subjectHeight * .055f);
            headSplit = Math.min(subjectTop + subjectHeight * .43f, headSplit);
        }
        float torsoSplit = subjectTop + subjectHeight * .68f;
        torsoSplit = Math.max(headSplit + subjectHeight * .18f, torsoSplit);
        torsoSplit = Math.min(subjectBottom - subjectHeight * .10f, torsoSplit);

        float headOverlap = Math.max(10f, subjectHeight * .055f);
        float lowerOverlap = Math.max(12f, subjectHeight * .070f);

        int[] head = new int[source.length];
        int[] torso = new int[source.length];
        int[] lower = new int[source.length];

        for (int y = 0; y < h; y++) {
            float headFade = 1f - smoothstep(headSplit - headOverlap, headSplit + headOverlap, y);
            float torsoIn = smoothstep(headSplit - headOverlap, headSplit + headOverlap, y);
            float torsoOut = 1f - smoothstep(torsoSplit - lowerOverlap, torsoSplit + lowerOverlap, y);
            float torsoWeight = torsoIn * torsoOut;
            float lowerWeight = smoothstep(torsoSplit - lowerOverlap, torsoSplit + lowerOverlap, y);

            // Keep the three weights normalized around the overlap bands.
            float sum = headFade + torsoWeight + lowerWeight;
            if (sum < .0001f) sum = 1f;
            headFade /= sum;
            torsoWeight /= sum;
            lowerWeight /= sum;

            for (int x = 0; x < w; x++) {
                int i = y * w + x;
                int color = source[i];
                int a = Color.alpha(color);
                int rgb = color & 0x00ffffff;
                head[i] = rgb | (Math.round(a * headFade) << 24);
                torso[i] = rgb | (Math.round(a * torsoWeight) << 24);
                lower[i] = rgb | (Math.round(a * lowerWeight) << 24);
            }
        }

        Bitmap headBitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Bitmap torsoBitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Bitmap lowerBitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        headBitmap.setPixels(head, 0, w, 0, 0, w, h);
        torsoBitmap.setPixels(torso, 0, w, 0, 0, w, h);
        lowerBitmap.setPixels(lower, 0, w, 0, 0, w, h);

        return new SubjectParts(
                headBitmap,
                torsoBitmap,
                lowerBitmap,
                clamp01(headSplit / Math.max(1f, h)),
                clamp01(torsoSplit / Math.max(1f, h))
        );
    }

    private Bitmap buildReconstructedBackground(Bitmap source, MaskStats stats) {
        int w = source.getWidth(), h = source.getHeight();

        // Build a broad low-frequency plate used only to fill the area hidden
        // behind the extracted person. The original environment stays sharp.
        int sw = Math.max(10, w / 30);
        int sh = Math.max(10, h / 30);
        Bitmap tiny = Bitmap.createScaledBitmap(source, sw, sh, true);
        Bitmap blurred = Bitmap.createScaledBitmap(tiny, w, h, true);
        tiny.recycle();

        int[] original = new int[w * h];
        int[] soft = new int[w * h];
        int[] output = new int[w * h];
        source.getPixels(original, 0, w, 0, 0, w, h);
        blurred.getPixels(soft, 0, w, 0, 0, w, h);
        blurred.recycle();

        int left = Math.max(0, Math.min(w - 1, (int) Math.floor(stats.left * (w - 1))));
        int top = Math.max(0, Math.min(h - 1, (int) Math.floor(stats.top * (h - 1))));
        int right = Math.max(left, Math.min(w - 1, (int) Math.ceil(stats.right * (w - 1))));
        int bottom = Math.max(top, Math.min(h - 1, (int) Math.ceil(stats.bottom * (h - 1))));
        int marginX = Math.max(8, (right - left) / 16);
        int marginY = Math.max(8, (bottom - top) / 18);

        for (int y = 0; y < h; y++) {
            int my = maskY(y, h, stats.maskHeight);
            for (int x = 0; x < w; x++) {
                int mx = maskX(x, w, stats.maskWidth);
                float confidence = dilatedConfidence(stats, mx, my);
                float replace = smoothstep(0.08f, 0.70f, confidence);
                int index = y * w + x;

                if (replace <= .001f) {
                    output[index] = original[index];
                    continue;
                }

                // Pull real environment color from the nearest side of the
                // subject bounds, then mix it with the broad blurred plate.
                // This is intentionally conservative: parallax only needs a
                // plausible hidden plate, while visible rocks/water remain the
                // untouched original pixels.
                int dl = Math.abs(x - left);
                int dr = Math.abs(right - x);
                int dt = Math.abs(y - top);
                int db = Math.abs(bottom - y);
                int sx = x;
                int sy = y;
                int nearest = Math.min(Math.min(dl, dr), Math.min(dt, db));
                if (nearest == dl) sx = Math.max(0, left - marginX);
                else if (nearest == dr) sx = Math.min(w - 1, right + marginX);
                else if (nearest == dt) sy = Math.max(0, top - marginY);
                else sy = Math.min(h - 1, bottom + marginY);

                int edge = original[sy * w + sx];
                int broad = soft[index];
                int fill = mixColor(edge, broad, .42f);

                // Preserve a little original texture near the matte boundary,
                // but fully replace the deep subject interior to suppress
                // ghost silhouettes when foreground parallax exposes it.
                float interior = smoothstep(0.28f, 0.78f, confidence);
                float alpha = Math.max(replace * .82f, interior);
                output[index] = mixColor(original[index], fill, alpha);
            }
        }

        Bitmap reconstructed = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        reconstructed.setPixels(output, 0, w, 0, 0, w, h);

        // A very mild grade separates the background spatially without
        // sacrificing the river/rock detail outside the reconstructed hole.
        Bitmap graded = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(graded);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        ColorMatrix matrix = new ColorMatrix();
        matrix.setSaturation(0.95f);
        ColorMatrix exposure = new ColorMatrix(new float[]{
                0.97f,0,0,0,-2,
                0,0.97f,0,0,-2,
                0,0,0.97f,0,-2,
                0,0,0,1,0
        });
        matrix.postConcat(exposure);
        paint.setColorFilter(new ColorMatrixColorFilter(matrix));
        canvas.drawBitmap(reconstructed, 0, 0, paint);
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
        checkInterrupted();
        if(!file.createNewFile())throw new IllegalStateException("Immutable portrait layer already exists");
        try (FileOutputStream out = new FileOutputStream(file)) {
            if (!bitmap.compress(format, quality, out)) throw new IllegalStateException("Could not write animation layer");
            out.flush();
            out.getFD().sync();
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

