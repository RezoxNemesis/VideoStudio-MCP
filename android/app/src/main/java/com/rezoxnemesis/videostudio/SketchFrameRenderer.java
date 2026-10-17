package com.rezoxnemesis.videostudio;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.RectF;
import android.graphics.Rect;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;
import java.util.LinkedHashMap;
import java.util.Map;

/** Actual Bitmap/Canvas vector renderer, shared by editor preview and PNG/native export. */
public final class SketchFrameRenderer implements AutoCloseable {
    private static final long CACHE_BYTES = 12L * 1024 * 1024;
    private JSONObject document;
    private final Context context;
    private final File ownedDependencyRoot;
    private SketchPaintStore paintStore;
    private final LinkedHashMap<String, Bitmap> paintCache = new LinkedHashMap<>(16,.75f,true);
    private long paintBytes;
    private int preparedDimension;
    private String paintPresentationObject;
    private SketchPaintReplay.Frame paintPresentation;
    private final Map<String, File> references = new java.util.HashMap<>();
    private final LinkedHashMap<String, Bitmap> imageCache = new LinkedHashMap<>(16, .75f, true);
    private long imageBytes;
    private final Map<String,Path> geometryCache=new java.util.HashMap<>();
    private final Map<String,java.util.BitSet[]> exposures=new java.util.HashMap<>();
    private Map<String,Integer> linkedColors=java.util.Collections.emptyMap();
    private void indexExposures() throws Exception {
        exposures.clear();geometryCache.clear();int frames=document.getInt("frameCount");JSONArray layers=document.getJSONArray("layers");
        for(int n=0;n<layers.length();n++){JSONObject layer=layers.getJSONObject(n);java.util.BitSet[] index=new java.util.BitSet[frames];JSONArray objects=layer.getJSONArray("objects");for(int k=0;k<objects.length();k++){JSONObject object=objects.getJSONObject(k);int start=object.getInt("frame"),end=start+object.getInt("exposure");for(int f=start;f<end;f++){if(index[f]==null)index[f]=new java.util.BitSet(objects.length());index[f].set(k);}}exposures.put(layer.getString("id"),index);}
    }
    private Path geometry(JSONObject object,String kind,int w,int h) throws Exception {
        boolean preview=object==geometryPreview;String id=object.getString("id");Path shape=preview?null:geometryCache.get(id);if(shape!=null)return shape;
        if("path".equals(kind))shape=path(object.getJSONArray("commands"),w,h);
        else {shape=new Path();if("polygon".equals(kind)){JSONArray points=object.getJSONArray("points");for(int n=0;n<points.length();n++){JSONArray p=points.getJSONArray(n);if(n==0)shape.moveTo((float)p.getDouble(0)*w,(float)p.getDouble(1)*h);else shape.lineTo((float)p.getDouble(0)*w,(float)p.getDouble(1)*h);}shape.close();}else if("rect".equals(kind))shape.addRect(bounds(object.getJSONObject("bounds"),w,h),Path.Direction.CW);else shape.addOval(bounds(object.getJSONObject("bounds"),w,h),Path.Direction.CW);}
        if(!preview)geometryCache.put(id,shape);return shape;
    }
    private JSONObject geometryPreview;public void setGeometryPreview(JSONObject object){geometryPreview=object;}
    private Path geometryAt(JSONObject object,String kind,int width,int height,int frame)throws Exception {
        if("path".equals(kind)&&SketchShapeKeys.animated(object))return path(SketchShapeKeys.commandsAt(object,frame),width,height);
        return geometry(object,kind,width,height);
    }
    /** Live snapshots warm vector paths before ownership transfers to the UI. */
    void prepareVectorGeometry() throws Exception {
        if(android.os.Looper.myLooper()==android.os.Looper.getMainLooper())
            throw new IllegalStateException("Vector preparation requires a background worker");
        int w=document.getInt("width"),h=document.getInt("height");
        JSONArray layers=document.getJSONArray("layers");
        for(int i=0;i<layers.length();i++){
            JSONArray objects=layers.getJSONObject(i).getJSONArray("objects");
            for(int j=0;j<objects.length();j++){
                if(Thread.currentThread().isInterrupted())throw new InterruptedException("Vector preparation cancelled");
                JSONObject object=objects.getJSONObject(j);String kind=object.getString("kind");
                if(!SketchShapeKeys.animated(object)&&("path".equals(kind)||"polygon".equals(kind)||"rect".equals(kind)||"ellipse".equals(kind)))
                    geometry(object,kind,w,h);
            }
        }
    }
    private String presentationObject;private float presentationFraction=1;
    private String appendPresentationObject;
    private int appendBaselinePoints;
    public void setPresentation(String objectId,float fraction){presentationObject=objectId;presentationFraction=fraction;}
    /** New live points extend existing ink; they never reveal that old ink a second time. */
    void setAppendPresentation(String objectId,int baselinePoints){appendPresentationObject=objectId;appendBaselinePoints=baselinePoints;}

    public SketchFrameRenderer(Context context, JSONObject document) throws Exception {this(context,document,null);}
    /** Immutable export generations may resolve dependencies from a checked owned assets root. */
    public SketchFrameRenderer(Context context, JSONObject document,File ownedDependencyRoot) throws Exception {
        SketchDocument.validate(document); this.document = SketchDocument.copy(document);
        linkedColors=SketchPaletteStyles.indexed(this.document);
        this.context=context.getApplicationContext();this.ownedDependencyRoot=ownedDependencyRoot;
        SketchProjectStore store=null;try {
            JSONArray layers = document.getJSONArray("layers");
            for (int i = 0; i < layers.length(); i++) {
                JSONArray objects = layers.getJSONObject(i).getJSONArray("objects");
                for (int j = 0; j < objects.length(); j++) {
                    JSONObject object = objects.getJSONObject(j);
                    if ("image".equals(object.getString("kind"))) {
                        JSONObject reference = object.getJSONObject("reference");
                        if(ownedDependencyRoot!=null)references.put(reference.getString("sha256"), frozenReference(reference));
                        else {if(store==null)store=new SketchProjectStore(context);references.put(reference.getString("sha256"), store.referenceFile(document.getString("id"), reference));}
                    }
                }
            }
        } finally {if(store!=null)store.close();}
        indexExposures();
    }

    private File frozenReference(JSONObject reference)throws Exception{
        String name=reference.getString("file");if(!name.matches("[0-9a-f]{64}\\.png")||!name.equals(reference.getString("sha256")+".png"))throw SketchDocument.bad("Invalid frozen reference filename");
        File root=ownedDependencyRoot.getCanonicalFile(),files=context.getFilesDir().getCanonicalFile();
        if(!root.getPath().startsWith(files.getPath()+File.separator))throw SketchDocument.bad("Frozen dependencies must remain app-owned");
        File expectedDir=new File(root,"references"),dir=expectedDir.getCanonicalFile(),expectedFile=new File(dir,name),file=expectedFile.getCanonicalFile();
        if(!dir.getPath().equals(expectedDir.getPath())||!file.getPath().equals(expectedFile.getPath())||!dir.equals(file.getParentFile())||!file.isFile()||file.length()>8L*1024*1024||!SketchProjectStore.fileHash(file).equals(reference.getString("sha256")))throw new IllegalStateException("SKETCH_REFERENCE_CHANGED");
        return file;
    }

    private SketchPaintStore paints()throws Exception{
        if(paintStore==null)paintStore=ownedDependencyRoot==null?new SketchPaintStore(context,document.getString("id")):new SketchPaintStore(context,document.getString("id"),ownedDependencyRoot);
        return paintStore;
    }
    /** A borrowed publication; its UI owner detaches it before closing its bitmap lease. */
    public void setPaintPresentation(String objectId,SketchPaintReplay.Frame frame){paintPresentationObject=objectId;paintPresentation=frame;}

    /** Warm raster resources on a worker before direct UI drawing. */
    public void prepareRasterResources(int maxDimension)throws Exception{prepareRasterResources(maxDimension,-1);}
    public void prepareRasterResources(int maxDimension,int frame)throws Exception {
        requireRasterWorker();
        if(maxDimension<128||maxDimension>1024)throw SketchDocument.bad("Raster preparation dimension must be 128..1024");
        clearImageCache();clearPaintCache();preparedDimension=0;
        java.util.ArrayList<JSONObject> visible=new java.util.ArrayList<>();
        java.util.HashSet<String> unique=new java.util.HashSet<>();
        JSONArray layers=document.getJSONArray("layers");
        for(int i=0;i<layers.length();i++){
            JSONObject layer=layers.getJSONObject(i);
            if(!layer.getBoolean("visible")||layer.getDouble("opacity")<=0||
                    frame>=0&&SketchDocument.transformAt(layer,frame).getDouble("opacity")<=0)continue;
            JSONArray objects=layer.getJSONArray("objects");
            for(int j=0;j<objects.length();j++){
                JSONObject object=objects.getJSONObject(j);String kind=object.getString("kind");
                if(!"paint".equals(kind)&&!"image".equals(kind))continue;
                if(frame>=0&&(!exposed(object,frame)||SketchDocument.transformAt(object,frame).getDouble("opacity")<=0))continue;
                String key=kind+":"+object.getJSONObject("paint".equals(kind)?"paint":"reference").getString("sha256");
                unique.add(key);visible.add(object);
            }
        }
        int edge=Math.min(maxDimension,Math.max(1,(int)Math.sqrt(CACHE_BYTES/(4d*Math.max(1,unique.size())))));
        preparedDimension=edge;
        for(JSONObject object:visible){
            if(Thread.currentThread().isInterrupted())throw new InterruptedException("Raster preparation cancelled");
            if("image".equals(object.getString("kind"))){image(object.getJSONObject("reference"));continue;}
            JSONObject descriptor=object.getJSONObject("paint");String hash=descriptor.getString("sha256");
            if(paintCache.containsKey(hash))continue;
            Bitmap bitmap=fitCacheBitmap(paints().render(descriptor,Math.max(128,edge),null),edge);
            paintCache.put(hash,bitmap);paintBytes+=bitmap.getAllocationByteCount();
        }
    }
    private static Bitmap fitCacheBitmap(Bitmap image,int edge){
        if(Math.max(image.getWidth(),image.getHeight())<=edge)return image;
        double scale=edge/(double)Math.max(image.getWidth(),image.getHeight());
        try{
            Bitmap scaled=Bitmap.createScaledBitmap(image,Math.max(1,(int)Math.round(image.getWidth()*scale)),
                    Math.max(1,(int)Math.round(image.getHeight()*scale)),true);
            if(scaled!=image)image.recycle();return scaled;
        }catch(RuntimeException|Error error){image.recycle();throw error;}
    }
    long preparedRasterBytes(){return imageBytes+paintBytes;}
    private static void requireRasterWorker(){if(android.os.Looper.myLooper()==android.os.Looper.getMainLooper())throw new IllegalStateException("Raster preparation requires a background worker");}

    public Bitmap render(int frame) throws Exception { return render(frame,true); }
    public Bitmap render(int frame, boolean background) throws Exception {
        int frames = document.getInt("frameCount"); if (frame < 0 || frame >= frames) throw SketchDocument.bad("Frame outside document");
        Bitmap bitmap = Bitmap.createBitmap(document.getInt("width"), document.getInt("height"), Bitmap.Config.ARGB_8888);
        try { draw(new Canvas(bitmap), frame, background); return bitmap; }
        catch (Exception|OutOfMemoryError e) { bitmap.recycle(); throw e; }
    }
    /** Render directly into destination pixels; no full-canvas bitmap is allocated first. */
    public Bitmap render(int frame,boolean background,int maxDimension,JSONObject crop)throws Exception{
        if(maxDimension<128||maxDimension>1024)throw SketchDocument.bad("Preview dimension must be 128..1024");
        int w=document.getInt("width"),h=document.getInt("height");double x=0,y=0,width=1,height=1;
        if(crop!=null){SketchDocument.validateBounds(crop);x=crop.getDouble("x");y=crop.getDouble("y");width=crop.getDouble("width");height=crop.getDouble("height");}
        double cw=width*w,ch=height*h,scale=maxDimension/Math.max(cw,ch);if(crop==null)scale=Math.min(1,scale);int outW=Math.max(1,(int)Math.round(cw*scale)),outH=Math.max(1,(int)Math.round(ch*scale));
        Bitmap bitmap=Bitmap.createBitmap(outW,outH,Bitmap.Config.ARGB_8888);
        try{Canvas canvas=new Canvas(bitmap);canvas.scale((float)(outW/cw),(float)(outH/ch));canvas.translate((float)(-x*w),(float)(-y*h));canvas.clipRect((float)(x*w),(float)(y*h),(float)((x+width)*w),(float)((y+height)*h));draw(canvas,frame,background);return bitmap;}
        catch(Exception|OutOfMemoryError error){bitmap.recycle();throw error;}
    }

    public void updateDocument(Context context,JSONObject doc) throws Exception {
        SketchDocument.validate(doc);JSONObject copy=SketchDocument.copy(doc);JSONArray layers=copy.getJSONArray("layers");
        if(!copy.getString("id").equals(document.getString("id")))throw SketchDocument.bad("Renderer owner cannot change; create a new renderer for another sketch");
        SketchProjectStore store=null;
        try {
            for(int i=0;i<layers.length();i++){
                JSONArray objects=layers.getJSONObject(i).getJSONArray("objects");
                for(int j=0;j<objects.length();j++){
                    JSONObject object=objects.getJSONObject(j);
                    if(!"image".equals(object.getString("kind")))continue;
                    JSONObject reference=object.getJSONObject("reference");String hash=reference.getString("sha256");
                    if(references.containsKey(hash))continue;
                    if(ownedDependencyRoot!=null)references.put(hash,frozenReference(reference));
                    else{
                        if(store==null)store=new SketchProjectStore(context);
                        references.put(hash,store.referenceFile(copy.getString("id"),reference));
                    }
                }
            }
        }finally{if(store!=null)store.close();}
        document=copy;linkedColors=SketchPaletteStyles.indexed(copy);indexExposures();clearPaintCache();preparedDimension=0;appendPresentationObject=null;appendBaselinePoints=0;
    }
    public void draw(Canvas canvas, int frame, boolean background) throws Exception {
        draw(canvas, frame, background, null, null);
    }

    public void draw(Canvas canvas, int frame, boolean background, String previewObjectId, JSONObject previewTransform) throws Exception {
        if (frame < 0 || frame >= document.getInt("frameCount")) throw SketchDocument.bad("Frame outside document");
        int w = document.getInt("width"), h = document.getInt("height");
        if (background) canvas.drawColor(SketchDocument.color(document.getString("background")));
        JSONArray layers = document.getJSONArray("layers");
        for (int i = 0; i < layers.length(); i++) {
            JSONObject layer = layers.getJSONObject(i); if (!layer.getBoolean("visible")) continue;
            JSONObject transform = SketchDocument.transformAt(layer, frame);
            if(layer.getDouble("opacity")<=0||transform.getDouble("opacity")<=0)continue;
            Paint composite = new Paint(Paint.ANTI_ALIAS_FLAG);
            composite.setAlpha((int) Math.round(255 * layer.getDouble("opacity") * transform.getDouble("opacity")));
            String blend=layer.optString("blendMode","normal");
            if(!"normal".equals(blend))composite.setXfermode(new PorterDuffXfermode("multiply".equals(blend)?PorterDuff.Mode.MULTIPLY:"screen".equals(blend)?PorterDuff.Mode.SCREEN:PorterDuff.Mode.ADD));
            // Each layer is isolated: DST_OUT erases its pixels and reveals lower layers.
            int save = canvas.saveLayer(new RectF(0, 0, w, h), composite);
            canvas.concat(matrix(transform, w, h));
            JSONArray objects = layer.getJSONArray("objects");
            java.util.BitSet visible=exposures.get(layer.getString("id"))[frame];
            for (int j = visible==null?-1:visible.nextSetBit(0); j>=0; j=visible.nextSetBit(j+1)) {
                JSONObject object = objects.getJSONObject(j);if(geometryPreview!=null&&object.getString("id").equals(geometryPreview.getString("id")))object=geometryPreview; if (!exposed(object, frame)) continue;
                int objectSave = canvas.save(); JSONObject objectTransform = previewTransform != null && object.getString("id").equals(previewObjectId) ? previewTransform : SketchDocument.transformAt(object, frame);
                if(objectTransform.getDouble("opacity")<=0){canvas.restoreToCount(objectSave);continue;}
                canvas.concat(matrix(objectTransform, w, h));
                drawObject(canvas, object, w, h, objectTransform.getDouble("opacity"),frame);
                canvas.restoreToCount(objectSave);
            }
            canvas.restoreToCount(save);
        }
    }

    private void drawObject(Canvas canvas, JSONObject object, int w, int h, double alpha,int frame) throws Exception {
        String kind = object.getString("kind");
        if("paint".equals(kind)){drawPaint(canvas,object,w,h,alpha);return;}
        boolean partial=object.getString("id").equals(presentationObject)&&presentationFraction<1;
        boolean append=appendBaselinePoints>0&&object.getString("id").equals(appendPresentationObject)&&("stroke".equals(kind)||"eraser".equals(kind));
        if(partial&&presentationFraction<=0&&!append)return;Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        int color = SketchPaletteStyles.resolvedColor(object,linkedColors); paint.setColor(color);
        paint.setAlpha((int) Math.round(((color >>> 24) & 255) * alpha));
        paint.setStrokeJoin(Paint.Join.ROUND); paint.setStrokeCap(Paint.Cap.ROUND);
        float width = (float) object.getDouble("width") * Math.min(w, h); paint.setStrokeWidth(width);
        if ("eraser".equals(kind)) { paint.setColor(0xFFFFFFFF); paint.setAlpha((int) Math.round(255 * alpha)); paint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.DST_OUT)); }
        if ("stroke".equals(kind) || "eraser".equals(kind)) {
            JSONArray points=object.getJSONArray("points");
            if(partial)points=append?appendPrefix(points,appendBaselinePoints,presentationFraction):SketchStrokeProgress.prefix(points,presentationFraction);
            SketchBrushes.draw(canvas,points,w,h,width,paint,object.optJSONObject("brush"));return;
        }
        paint.setStyle(object.getBoolean("fill") ? Paint.Style.FILL : Paint.Style.STROKE);
        JSONObject gradient=object.optJSONObject("gradient");
        if(gradient!=null)paint.setShader(SketchGradients.shader(gradient,w,h));
        if("path".equals(kind)){Path shape=geometryAt(object,kind,w,h,frame);if(partial)shape=partialPath(shape,presentationFraction,paint);canvas.drawPath(shape,paint);return;}
        if ("line".equals(kind)) {
            JSONArray pp = object.getJSONArray("points"), a = pp.getJSONArray(0), b = pp.getJSONArray(1);
            paint.setStyle(Paint.Style.STROKE); float f=partial?presentationFraction:1;canvas.drawLine((float)a.getDouble(0)*w,(float)a.getDouble(1)*h,(float)(a.getDouble(0)+(b.getDouble(0)-a.getDouble(0))*f)*w,(float)(a.getDouble(1)+(b.getDouble(1)-a.getDouble(1))*f)*h,paint);
        } else if ("polygon".equals(kind)) {
            Path path=geometry(object,kind,w,h);canvas.drawPath(partial?partialPath(path,presentationFraction,paint):path,paint);
        } else {
            RectF bounds = bounds(object.getJSONObject("bounds"), w, h);
            if ("rect".equals(kind)||"ellipse".equals(kind)){Path shape=geometry(object,kind,w,h);canvas.drawPath(partial?partialPath(shape,presentationFraction,paint):shape,paint);}
            else if ("text".equals(kind)) {SketchText.draw(canvas,object,bounds,paint,h,partial?presentationFraction:1);}
            else if ("image".equals(kind)) {
                paint.setAlpha((int) Math.round(255 * alpha*(partial?presentationFraction:1))); Bitmap bitmap=image(object.getJSONObject("reference"));JSONObject crop=object.optJSONObject("referenceCrop");Rect source=null;
                if(crop!=null)source=referencePixels(crop,bitmap.getWidth(),bitmap.getHeight());
                canvas.drawBitmap(bitmap, source, bounds, paint);
            }
        }
    }
    private static JSONArray appendPrefix(JSONArray points,int baseline,double fraction)throws Exception{
        if(baseline<1||baseline>points.length())throw SketchDocument.bad("Live append baseline is outside its stroke");
        JSONArray result=new JSONArray();for(int i=0;i<baseline;i++)result.put(points.getJSONArray(i));
        if(fraction<=0||baseline==points.length())return result;
        JSONArray travel=new JSONArray();for(int i=baseline-1;i<points.length();i++)travel.put(points.getJSONArray(i));
        JSONArray revealed=SketchStrokeProgress.prefix(travel,fraction);for(int i=1;i<revealed.length();i++)result.put(revealed.getJSONArray(i));return result;
    }

    private void drawPaint(Canvas canvas,JSONObject object,int w,int h,double alpha)throws Exception{
        if(alpha<=0)return;Paint composite=new Paint(Paint.ANTI_ALIAS_FLAG|Paint.FILTER_BITMAP_FLAG);composite.setAlpha((int)Math.round(255*alpha));
        SketchPaintReplay.Frame frame=object.getString("id").equals(paintPresentationObject)?paintPresentation:null;
        if(frame!=null){if(frame.bitmap.isRecycled())throw new IllegalStateException("Paint publication was recycled before detachment");RectF region=frame.crop==null?new RectF(0,0,w,h):bounds(frame.crop,w,h);canvas.drawBitmap(frame.bitmap,null,region,composite);return;}
        JSONObject descriptor=object.getJSONObject("paint");
        if(android.os.Looper.myLooper()==android.os.Looper.getMainLooper()){
            Bitmap bitmap=paintCache.get(descriptor.getString("sha256"));if(bitmap==null)throw new IllegalStateException("Paint cel must be prepared before UI drawing");canvas.drawBitmap(bitmap,null,new RectF(0,0,w,h),composite);
        }else{
            int save=canvas.saveLayer(new RectF(0,0,w,h),composite);try{paints().draw(canvas,descriptor);}finally{canvas.restoreToCount(save);}
        }
    }

    private static Path partialPath(Path full,float fraction,Paint paint){android.graphics.PathMeasure measure=new android.graphics.PathMeasure(full,false);float total=0;do{total+=measure.getLength();}while(measure.nextContour());measure.setPath(full,false);float remaining=total*fraction;Path result=new Path();do{float take=Math.min(remaining,measure.getLength());if(take>0)measure.getSegment(0,take,result,true);remaining-=take;}while(remaining>0&&measure.nextContour());paint.setStyle(Paint.Style.STROKE);return result;}
    public static Path path(JSONArray commands,int w,int h) throws Exception {
        Path p=new Path();for(int i=0;i<commands.length();i++){JSONArray a=commands.getJSONArray(i);switch(a.getString(0)){
            case "M":p.moveTo((float)a.getDouble(1)*w,(float)a.getDouble(2)*h);break;
            case "L":p.lineTo((float)a.getDouble(1)*w,(float)a.getDouble(2)*h);break;
            case "Q":p.quadTo((float)a.getDouble(1)*w,(float)a.getDouble(2)*h,(float)a.getDouble(3)*w,(float)a.getDouble(4)*h);break;
            case "C":p.cubicTo((float)a.getDouble(1)*w,(float)a.getDouble(2)*h,(float)a.getDouble(3)*w,(float)a.getDouble(4)*h,(float)a.getDouble(5)*w,(float)a.getDouble(6)*h);break;
            case "Z":p.close();break;
        }}return p;
    }
    public static JSONArray pathPoints(JSONArray commands) throws Exception {
        JSONArray out=new JSONArray(); android.graphics.PathMeasure measure=new android.graphics.PathMeasure(path(commands,1024,1024),false);float[] p=new float[2];
        do{float length=measure.getLength();int steps=Math.max(1,Math.min(1024,(int)Math.ceil(length/2)));for(int i=0;i<=steps;i++){measure.getPosTan(length*i/steps,p,null);out.put(new JSONArray().put(p[0]/1024d).put(p[1]/1024d).put(1));}}while(measure.nextContour());return out;
    }
    /** Width is sampled from pressure, with round joins and bounded interpolation along each segment. */
    public static void drawPressureStroke(Canvas canvas, JSONArray points, int w, int h, float width, Paint paint) throws Exception {
        paint.setStyle(Paint.Style.FILL);
        for (int i = 0; i < points.length(); i++) {
            JSONArray p = points.getJSONArray(i); float x = (float) p.getDouble(0) * w, y = (float) p.getDouble(1) * h;
            float pressure = (float) p.getDouble(2), radius = width * (.15f + .85f * pressure) / 2;
            if (i > 0) {
                JSONArray prior = points.getJSONArray(i - 1); float ax = (float) prior.getDouble(0) * w, ay = (float) prior.getDouble(1) * h;
                float priorRadius = width * (.15f + .85f * (float) prior.getDouble(2)) / 2;
                // A tapered quadrilateral plus endpoint discs produces continuous pressure without per-pixel subdivision.
                float dx = x - ax, dy = y - ay, length = (float) Math.hypot(dx, dy);
                if (length > .0001f) {
                    float nx = -dy / length, ny = dx / length; Path segment = new Path();
                    segment.moveTo(ax + nx * priorRadius, ay + ny * priorRadius); segment.lineTo(x + nx * radius, y + ny * radius);
                    segment.lineTo(x - nx * radius, y - ny * radius); segment.lineTo(ax - nx * priorRadius, ay - ny * priorRadius); segment.close(); canvas.drawPath(segment, paint);
                }
            }
            canvas.drawCircle(x, y, radius, paint);
        }
    }

    private Bitmap image(JSONObject reference) throws Exception {
        String hash = reference.getString("sha256"); Bitmap cached = imageCache.get(hash); if (cached != null) return cached;
        if(android.os.Looper.myLooper()==android.os.Looper.getMainLooper()&&preparedDimension>0)throw new IllegalStateException("Reference must be prepared before UI drawing");
        File file = references.get(hash); if (file == null) throw new IllegalStateException("SKETCH_REFERENCE_UNAVAILABLE");
        BitmapFactory.Options options = new BitmapFactory.Options(); options.inPreferredConfig = Bitmap.Config.ARGB_8888;
        Bitmap image = BitmapFactory.decodeFile(file.getPath(), options);
        if (image == null) throw new IllegalStateException("Owned reference PNG decode failed");
        if (image.getWidth() != reference.getInt("width") || image.getHeight() != reference.getInt("height")) { image.recycle(); throw new IllegalStateException("SKETCH_REFERENCE_CHANGED"); }
        if(preparedDimension>0)image=fitCacheBitmap(image,preparedDimension);
        while (!imageCache.isEmpty() && imageBytes + image.getAllocationByteCount() > CACHE_BYTES) {
            Map.Entry<String, Bitmap> oldest = imageCache.entrySet().iterator().next(); Bitmap removed = oldest.getValue();
            imageBytes -= removed.getAllocationByteCount(); imageCache.remove(oldest.getKey()); removed.recycle();
        }
        imageCache.put(hash, image); imageBytes += image.getAllocationByteCount(); return image;
    }
    public static boolean exposed(JSONObject object, int frame) throws Exception { int start = object.getInt("frame"); return frame >= start && frame < start + object.getInt("exposure"); }
    public static Matrix matrix(JSONObject transform, int w, int h) throws Exception {
        float ax = (float) transform.getDouble("anchorX") * w, ay = (float) transform.getDouble("anchorY") * h;
        Matrix matrix = new Matrix(); matrix.setTranslate(ax + (float) transform.getDouble("x") * w, ay + (float) transform.getDouble("y") * h);
        matrix.preRotate((float) transform.getDouble("rotation")); matrix.preScale((float) transform.getDouble("scaleX"), (float) transform.getDouble("scaleY"));
        matrix.preTranslate(-ax, -ay); return matrix;
    }
    /** Any nonempty normalized crop includes its intersecting source pixels. Shared with tracing. */
    static Rect referencePixels(JSONObject crop,int width,int height)throws Exception {
        SketchDocument.validateBounds(crop);int left=Math.min(width-1,(int)Math.floor(crop.getDouble("x")*width)),top=Math.min(height-1,(int)Math.floor(crop.getDouble("y")*height));int right=Math.min(width,Math.max(left+1,(int)Math.ceil((crop.getDouble("x")+crop.getDouble("width"))*width))),bottom=Math.min(height,Math.max(top+1,(int)Math.ceil((crop.getDouble("y")+crop.getDouble("height"))*height)));return new Rect(left,top,right,bottom);
    }
    public static RectF bounds(JSONObject bounds, int w, int h) throws Exception {
        float x = (float) bounds.getDouble("x") * w, y = (float) bounds.getDouble("y") * h;
        return new RectF(x, y, x + (float) bounds.getDouble("width") * w, y + (float) bounds.getDouble("height") * h);
    }
    /** Select topmost exposed object using the same matrices and geometry as rasterization. */
    public static String hitTest(JSONObject document, int frame, double x, double y, double tolerance) throws Exception {
        int w = document.getInt("width"), h = document.getInt("height"); JSONArray layers = document.getJSONArray("layers");
        for (int i = layers.length() - 1; i >= 0; i--) {
            JSONObject layer = layers.getJSONObject(i); if (!layer.getBoolean("visible") || layer.getBoolean("locked") || layer.getDouble("opacity") <= 0 || SketchDocument.transformAt(layer, frame).getDouble("opacity") <= 0) continue;
            JSONArray objects = layer.getJSONArray("objects"); Matrix layerMatrix = matrix(SketchDocument.transformAt(layer, frame), w, h);
            for (int j = objects.length() - 1; j >= 0; j--) {
                JSONObject object = objects.getJSONObject(j); if (!exposed(object, frame) || "eraser".equals(object.getString("kind")) || SketchDocument.transformAt(object, frame).getDouble("opacity") <= 0) continue;
                Matrix combined = new Matrix(); combined.setConcat(layerMatrix, matrix(SketchDocument.transformAt(object, frame), w, h)); Matrix inverse = new Matrix();
                if (!combined.invert(inverse)) continue; float[] p = {(float) x * w, (float) y * h}; inverse.mapPoints(p);
                float t = (float) tolerance * Math.min(w, h) + (float) object.getDouble("width") * Math.min(w, h) / 2;
                String kind = object.getString("kind");
                if (object.has("bounds")) {
                    RectF bounds = bounds(object.getJSONObject("bounds"), w, h);
                    if ("ellipse".equals(kind)) {
                        double rx = bounds.width() / 2d, ry = bounds.height() / 2d, dx = (p[0] - bounds.centerX()) / rx, dy = (p[1] - bounds.centerY()) / ry;
                        double radius = Math.sqrt(dx * dx + dy * dy), allowed = t / Math.min(rx, ry);
                        if (radius <= 1 + allowed && (object.getBoolean("fill") || Math.abs(radius - 1) <= allowed)) return object.getString("id");
                    } else {
                        RectF expanded = new RectF(bounds); expanded.inset(-t, -t);
                        if (expanded.contains(p[0], p[1]) && ("image".equals(kind) || "paint".equals(kind) || "text".equals(kind) || object.getBoolean("fill") ||
                                Math.min(Math.min(Math.abs(p[0] - bounds.left), Math.abs(p[0] - bounds.right)), Math.min(Math.abs(p[1] - bounds.top), Math.abs(p[1] - bounds.bottom))) <= t)) return object.getString("id");
                    }
                } else {
                    JSONArray points = "path".equals(kind)?pathPoints(SketchShapeKeys.commandsAt(object,frame)):object.getJSONArray("points"); Path polygon = new Path();
                    for (int k = 0; k < points.length(); k++) {
                        JSONArray point = points.getJSONArray(k); float bx = (float) point.getDouble(0) * w, by = (float) point.getDouble(1) * h;
                        if (Math.hypot(p[0] - bx, p[1] - by) <= t) return object.getString("id");
                        if (k > 0) { JSONArray prior = points.getJSONArray(k - 1); if (distance(p[0], p[1], (float) prior.getDouble(0) * w, (float) prior.getDouble(1) * h, bx, by) <= t) return object.getString("id"); }
                    }
                    if ("polygon".equals(kind)) {
                        JSONArray a = points.getJSONArray(0), b = points.getJSONArray(points.length() - 1);
                        if (distance(p[0], p[1], a.getDouble(0) * w, a.getDouble(1) * h, b.getDouble(0) * w, b.getDouble(1) * h) <= t) return object.getString("id");
                    }
                    if ("polygon".equals(kind) && object.getBoolean("fill") && insidePolygon(points, p[0] / w, p[1] / h)) return object.getString("id");
                }
            }
        }
        return null;
    }
    private static double distance(double x, double y, double ax, double ay, double bx, double by) {
        double dx = bx - ax, dy = by - ay, length = dx * dx + dy * dy;
        double t = length == 0 ? 0 : Math.max(0, Math.min(1, ((x - ax) * dx + (y - ay) * dy) / length)); return Math.hypot(x - ax - t * dx, y - ay - t * dy);
    }
    private static boolean insidePolygon(JSONArray points, double x, double y) throws Exception {
        boolean inside = false;
        for (int i = 0, j = points.length() - 1; i < points.length(); j = i++) {
            JSONArray a = points.getJSONArray(i), b = points.getJSONArray(j); double ay = a.getDouble(1), by = b.getDouble(1);
            if ((ay > y) != (by > y) && x < (b.getDouble(0) - a.getDouble(0)) * (y - ay) / (by - ay) + a.getDouble(0)) inside = !inside;
        }
        return inside;
    }
    private void clearPaintCache(){for(Bitmap bitmap:paintCache.values())bitmap.recycle();paintCache.clear();paintBytes=0;}
    private void clearImageCache(){for(Bitmap image:imageCache.values())image.recycle();imageCache.clear();imageBytes=0;}
    @Override public void close() {clearImageCache();clearPaintCache();if(paintStore!=null){paintStore.close();paintStore=null;}paintPresentation=null;paintPresentationObject=null;geometryCache.clear();exposures.clear(); }
}
