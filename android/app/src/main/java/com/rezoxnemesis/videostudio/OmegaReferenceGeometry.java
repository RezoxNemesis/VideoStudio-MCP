package com.rezoxnemesis.videostudio;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** OMEGA Phase 2: transparent 2D landmark alignment. No hidden-view or AI claims. */
final class OmegaReferenceGeometry {
    private OmegaReferenceGeometry() {}
    static final int MAX_PAIRS=128;
    static final class Point {
        final double x,y;
        Point(double x,double y) {
            if (!valid(x) || !valid(y) || x<0 || y<0 || x>1 || y>1)
                throw new IllegalArgumentException("Landmark must be normalized and finite");
            this.x=x;this.y=y;
        }
    }
    static final class Pair {
        final Point source,target;
        final double weight;
        Pair(Point source,Point target,double weight) {
            if(source==null||target==null||!valid(weight)||weight<=0||weight>1000)
                throw new IllegalArgumentException("Invalid landmark pair");
            this.source=source;this.target=target;this.weight=weight;
        }
    }
    static final class Fit {
        final double scale,cos,sin,tx,ty,rmsError;
        final int landmarkCount;
        Fit(double scale,double cos,double sin,double tx,double ty,double rmsError,int count) {
            this.scale=scale;this.cos=cos;this.sin=sin;this.tx=tx;this.ty=ty;
            this.rmsError=rmsError;this.landmarkCount=count;
        }
        double x(double x,double y){return scale*(cos*x-sin*y)+tx;}
        double y(double x,double y){return scale*(sin*x+cos*y)+ty;}
    }
    static Fit align(List<Pair> pairs) {
        if(pairs==null||pairs.size()<2||pairs.size()>MAX_PAIRS)
            throw new IllegalArgumentException("Require 2..128 corresponding landmarks");
        double sum=0,sx=0,sy=0,tx=0,ty=0;
        for(Pair p:pairs){
            if(p==null)throw new IllegalArgumentException("Null landmark");
            sum+=p.weight;
            sx+=p.source.x*p.weight;sy+=p.source.y*p.weight;
            tx+=p.target.x*p.weight;ty+=p.target.y*p.weight;
        }
        sx/=sum;sy/=sum;tx/=sum;ty/=sum;
        double a=0,b=0,variance=0;
        for(Pair p:pairs){
            double x=p.source.x-sx,y=p.source.y-sy,u=p.target.x-tx,v=p.target.y-ty;
            a+=p.weight*(x*u+y*v);
            b+=p.weight*(x*v-y*u);
            variance+=p.weight*(x*x+y*y);
        }
        if(variance<1e-12 || Math.hypot(a,b)<1e-12)
            throw new IllegalArgumentException("Degenerate landmark alignment");
        double norm=Math.hypot(a,b),scale=norm/variance,c=a/norm,s=b/norm;
        if(!valid(scale)||scale<.001||scale>1000)
            throw new IllegalArgumentException("Unsafe guide scale");
        Fit partial=new Fit(scale,c,s,tx-scale*(c*sx-s*sy),ty-scale*(s*sx+c*sy),0,pairs.size());
        double error=0;
        for(Pair p:pairs){
            double dx=partial.x(p.source.x,p.source.y)-p.target.x;
            double dy=partial.y(p.source.x,p.source.y)-p.target.y;
            error+=p.weight*(dx*dx+dy*dy);
        }
        return new Fit(scale,c,s,partial.tx,partial.ty,Math.sqrt(error/sum),pairs.size());
    }
    static List<Point> transform(List<Point> points,Fit fit) {
        if(points==null||fit==null||points.size()>2048)
            throw new IllegalArgumentException("Guide transform bounds exceeded");
        List<Point> result=new ArrayList<>(points.size());
        for(Point p:points){
            if(p==null)throw new IllegalArgumentException("Null guide point");
            // Output is intentionally allowed outside canvas for preview clipping.
            result.add(new Point(clamp(fit.x(p.x,p.y)),clamp(fit.y(p.x,p.y))));
        }
        return Collections.unmodifiableList(result);
    }
    private static double clamp(double x){return Math.min(1,Math.max(0,x));}
    private static boolean valid(double v){return !Double.isNaN(v)&&!Double.isInfinite(v);}

    /**
     * Immutable provenance of one visible, user-selected imported reference.
     * This is an owned PNG already oriented by the native ImageDecoder importer.
     * The original EXIF transform is exposed for manual points in original image
     * coordinates; it is NEVER silently reapplied to working PNG landmarks.
     */
    static final class Source {
        final String sketchId, imageId, ownedSha256, originalSha256, projectId, assetId, declaredView;
        final long revision;
        final int width,height,sourceWidth,sourceHeight,orientation,canvasWidth,canvasHeight;

        private Source(String sketchId,String imageId,String owned,String original,String project,
                       String asset,String view,long revision,int width,int height,int sourceWidth,
                       int sourceHeight,int orientation,int canvasWidth,int canvasHeight) {
            this.sketchId=sketchId;this.imageId=imageId;ownedSha256=owned;originalSha256=original;
            projectId=project;assetId=asset;declaredView=view;this.revision=revision;
            this.width=width;this.height=height;this.sourceWidth=sourceWidth;
            this.sourceHeight=sourceHeight;this.orientation=orientation;
            this.canvasWidth=canvasWidth;this.canvasHeight=canvasHeight;
        }
        static Source fromSketch(org.json.JSONObject doc,String objectId,String userView) throws Exception {
            if(userView==null||!userView.matches("[A-Za-z0-9_-]{1,48}"))
                throw new IllegalArgumentException("Explicit, user-declared visible view required");
            SketchDocument.validate(doc);
            String imageId=SketchDocument.id(new org.json.JSONObject().put("id",objectId),"id");
            org.json.JSONObject obj=SketchDocument.object(doc,imageId);
            if(!"image".equals(obj.getString("kind")) || obj.has("referenceCrop"))
                throw new IllegalArgumentException("An uncropped owned reference image is required");
            org.json.JSONObject ref=obj.getJSONObject("reference");
            String owned=ref.getString("sha256"),original=ref.getString("sourceSha256");
            if(!hash(owned)||!hash(original)||!ref.getString("file").equals(owned+".png"))
                throw new IllegalArgumentException("Original and owned reference hashes required");
            int sw=ref.getInt("sourceWidth"),sh=ref.getInt("sourceHeight");
            if(sw<1||sh<1||(long)sw*sh>64_000_000L)
                throw new IllegalArgumentException("Invalid source pixel dimensions");
            int orientation=ref.getInt("sourceOrientation");
            if(orientation<1||orientation>8)throw new IllegalArgumentException("Invalid EXIF orientation");
            String project=SketchDocument.id(ref,"sourceProjectId");
            String asset=SketchDocument.id(ref,"sourceAssetId");
            return new Source(doc.getString("id"),imageId,owned,original,project,asset,userView,
                    doc.getLong("revision"),ref.getInt("width"),ref.getInt("height"),sw,sh,
                    orientation,doc.getInt("width"),doc.getInt("height"));
        }
        private boolean same(Source b) {
            return b!=null && revision==b.revision && width==b.width && height==b.height
                    && sourceWidth==b.sourceWidth && sourceHeight==b.sourceHeight
                    && orientation==b.orientation && canvasWidth==b.canvasWidth
                    && canvasHeight==b.canvasHeight && sketchId.equals(b.sketchId)
                    && imageId.equals(b.imageId) && ownedSha256.equals(b.ownedSha256)
                    && originalSha256.equals(b.originalSha256) && projectId.equals(b.projectId)
                    && assetId.equals(b.assetId) && declaredView.equals(b.declaredView);
        }
        Point originalToOwned(Point original) {
            if(original==null)throw new IllegalArgumentException("Original image point required");
            double x=original.x,y=original.y;
            switch(orientation) {
                case 1:return new Point(x,y);
                case 2:return new Point(1-x,y);
                case 3:return new Point(1-x,1-y);
                case 4:return new Point(x,1-y);
                case 5:return new Point(y,x);
                case 6:return new Point(1-y,x);
                case 7:return new Point(1-y,1-x);
                case 8:return new Point(y,1-x);
                default:throw new IllegalArgumentException("Unsupported EXIF orientation");
            }
        }
    }
    private static boolean hash(String s){return s!=null&&s.matches("[a-f0-9]{64}");}
    private static String guideId(String id){
        if(id==null||!id.matches("[A-Za-z0-9_-]{1,64}"))
            throw new IllegalArgumentException("Guide ID must be 1..64 safe characters");
        return id;
    }
    static final class Landmark {
        final String id;
        final Point source,target;
        final double weight;
        private Landmark(String id,Point src,Point dst,double w) {
            this.id=guideId(id);
            new Pair(src,dst,w);
            source=src;target=dst;weight=w;
        }
    }
    static final class Contour {
        final String id;
        final List<Point> points;
        final boolean closed;
        private Contour(String id,List<Point> points,boolean closed) {
            this.id=guideId(id);this.closed=closed;
            if(points==null||points.size()<(closed?3:2)||points.size()>2048)
                throw new IllegalArgumentException("Manual contour requires 2..2048 points");
            ArrayList<Point> safe=new ArrayList<>(points.size());
            for(Point p:points){
                if(p==null)throw new IllegalArgumentException("Null contour point");
                safe.add(p);
            }
            this.points=Collections.unmodifiableList(safe);
        }
    }
    static final class ProjectedContour {
        final String id;
        final boolean closed;
        final List<Point> points;
        private ProjectedContour(Contour source,List<Point> points) {
            id=source.id;closed=source.closed;
            this.points=Collections.unmodifiableList(points);
        }
    }
    static final class Projection {
        final List<ProjectedContour> contours;
        final double rmsPixels,maxPixels;
        final Source reference;
        private Projection(Source reference,List<ProjectedContour> contours,double rms,double max){
            this.reference=reference;
            this.contours=Collections.unmodifiableList(contours);
            rmsPixels=rms;maxPixels=max;
        }
    }
    /**
     * Artist-correctable, copy-on-write landmark/contour draft.
     * No model-inferred landmarks, poses or unseen camera views are invented.
     */
    static final class GuideDraft {
        final Source source;
        final List<Landmark> landmarks;
        final List<Contour> contours;
        GuideDraft(Source source) {
            if(source==null)throw new IllegalArgumentException("Source reference required");
            this.source=source;
            landmarks=Collections.emptyList();
            contours=Collections.emptyList();
        }
        private GuideDraft(Source source,List<Landmark> landmarks,List<Contour> contours) {
            this.source=source;
            this.landmarks=Collections.unmodifiableList(landmarks);
            this.contours=Collections.unmodifiableList(contours);
        }
        GuideDraft withLandmark(String id,Point src,Point dst,double weight) {
            Landmark replacement=new Landmark(id,src,dst,weight);
            ArrayList<Landmark> next=new ArrayList<>(landmarks.size()+1);
            boolean found=false;
            for(Landmark current:landmarks) {
                if(current.id.equals(replacement.id)){next.add(replacement);found=true;}
                else next.add(current);
            }
            if(!found)next.add(replacement);
            if(next.size()>MAX_PAIRS)throw new IllegalArgumentException("Too many guides");
            return new GuideDraft(source,next,new ArrayList<>(contours));
        }
        GuideDraft withoutLandmark(String id) {
            guideId(id);ArrayList<Landmark> next=new ArrayList<>();
            for(Landmark item:landmarks)if(!item.id.equals(id))next.add(item);
            if(next.size()==landmarks.size())throw new IllegalArgumentException("Unknown landmark");
            return new GuideDraft(source,next,new ArrayList<>(contours));
        }
        GuideDraft withContour(String id,List<Point> points,boolean closed) {
            Contour replacement=new Contour(id,points,closed);
            ArrayList<Contour> next=new ArrayList<>(contours.size()+1);
            boolean found=false,total=false;
            int count=0;
            for(Contour item:contours) {
                Contour selected=item.id.equals(replacement.id)?replacement:item;
                if(selected==replacement)found=true;
                count+=selected.points.size();
                next.add(selected);
            }
            if(!found){next.add(replacement);count+=replacement.points.size();}
            if(next.size()>32||count>4096)throw new IllegalArgumentException("Contour budget exceeded");
            return new GuideDraft(source,new ArrayList<>(landmarks),next);
        }
        GuideDraft withoutContour(String id) {
            guideId(id);ArrayList<Contour> next=new ArrayList<>();
            for(Contour item:contours)if(!item.id.equals(id))next.add(item);
            if(next.size()==contours.size())throw new IllegalArgumentException("Unknown contour");
            return new GuideDraft(source,new ArrayList<>(landmarks),next);
        }
        Projection project(Source current,double allowedRmsPixels,double allowedWorstPixels) {
            if(!source.same(current))throw new IllegalArgumentException("STALE_REFERENCE: hash/revision/view changed");
            if(!valid(allowedRmsPixels)||!valid(allowedWorstPixels)||allowedRmsPixels<0
                    ||allowedWorstPixels<allowedRmsPixels||allowedWorstPixels>32)
                throw new IllegalArgumentException("Invalid pixel-error tolerance");
            if(landmarks.size()<3||landmarks.size()>MAX_PAIRS||contours.isEmpty())
                throw new IllegalArgumentException("Three guides and one visible manual contour required");
            if(!nonCollinear(landmarks,true)||!nonCollinear(landmarks,false))
                throw new IllegalArgumentException("Guide landmarks are collinear");
            // One common pixel metric. Normalized x/y alone distort across aspect ratios.
            List<Pair> metric=new ArrayList<>(landmarks.size());
            for(Landmark l:landmarks)
                metric.add(new Pair(new Point(l.source.x*source.width/2048.0,
                                              l.source.y*source.height/2048.0),
                                    new Point(l.target.x*source.canvasWidth/2048.0,
                                              l.target.y*source.canvasHeight/2048.0),
                                    l.weight));
            Fit fit=align(metric);
            double worst=0;
            for(Pair p:metric)
                worst=Math.max(worst,2048.0*Math.hypot(fit.x(p.source.x,p.source.y)-p.target.x,
                                                       fit.y(p.source.x,p.source.y)-p.target.y));
            double rms=2048.0*fit.rmsError;
            if(rms>allowedRmsPixels+1e-9||worst>allowedWorstPixels+1e-9)
                throw new IllegalArgumentException("Guide error exceeds pixel tolerance; correct landmarks");
            ArrayList<ProjectedContour> mapped=new ArrayList<>();
            for(Contour c:contours) {
                ArrayList<Point> vertices=new ArrayList<>(c.points.size());
                for(Point p:c.points){
                    double x=2048*fit.x(p.x*source.width/2048,p.y*source.height/2048)
                            /source.canvasWidth;
                    double y=2048*fit.y(p.x*source.width/2048,p.y*source.height/2048)
                            /source.canvasHeight;
                    if(!valid(x)||!valid(y)||x< -1e-9||y< -1e-9||x>1+1e-9||y>1+1e-9)
                        throw new IllegalArgumentException("Guide contour leaves canvas; reposition source guides");
                    vertices.add(new Point(clamp(x),clamp(y)));
                }
                mapped.add(new ProjectedContour(c,vertices));
            }
            return new Projection(source,mapped,rms,worst);
        }
    }
    private static boolean nonCollinear(List<Landmark> guides,boolean isSource) {
        for(int a=0;a<guides.size()-2;a++)
            for(int b=a+1;b<guides.size()-1;b++)
                for(int c=b+1;c<guides.size();c++){
                    Point p=isSource?guides.get(a).source:guides.get(a).target;
                    Point q=isSource?guides.get(b).source:guides.get(b).target;
                    Point r=isSource?guides.get(c).source:guides.get(c).target;
                    if(Math.abs((q.x-p.x)*(r.y-p.y)-(q.y-p.y)*(r.x-p.x))>1e-6)return true;
                }
        return false;
    }
    /**
     * Produce actual native editable polygon/stroke operations, never a pasted
     * PNG. Preview on a deep copy and validate the complete document before
     * allowing a production transaction. Actual commit must go through sketch_edit.
     */
    static org.json.JSONArray prepareEdit(org.json.JSONObject doc,GuideDraft draft,
                    String targetLayerId,int frame,int exposure,String objectPrefix,
                    String color,double width,double maxRms,double maxWorst)throws Exception {
        SketchDocument.validate(doc);
        if(draft==null)throw new IllegalArgumentException("Guide draft required");
        Source actual=Source.fromSketch(doc,draft.source.imageId,draft.source.declaredView);
        Projection projection=draft.project(actual,maxRms,maxWorst);
        org.json.JSONObject layer=SketchDocument.layer(doc,SketchDocument.id(
                new org.json.JSONObject().put("id",targetLayerId),"id"));
        SketchEditOps.requireUnlocked(layer);
        if(!layer.getBoolean("visible"))
            throw new IllegalArgumentException("Target guide layer must be visible");
        if(frame<0||frame>=doc.getInt("frameCount")||exposure<1
                ||exposure>doc.getInt("frameCount")-frame)
            throw new IllegalArgumentException("Invalid native frame/exposure");
        if(!valid(width)||width<.0005||width>.2)
            throw new IllegalArgumentException("Stroke width out of native bounds");
        SketchDocument.color(color);
        if(objectPrefix==null||!objectPrefix.matches("[A-Za-z0-9_-]{1,48}"))
            throw new IllegalArgumentException("Invalid native object prefix");
        org.json.JSONArray edits=new org.json.JSONArray();
        for(ProjectedContour c:projection.contours) {
            String objectId=objectPrefix+"_"+c.id;
            SketchDocument.id(new org.json.JSONObject().put("id",objectId),"id");
            org.json.JSONArray vertices=new org.json.JSONArray();
            for(Point p:c.points)
                vertices.put(new org.json.JSONArray().put(p.x).put(p.y).put(1));
            org.json.JSONObject object=SketchEditOps.completeObject(new org.json.JSONObject()
                    .put("id",objectId).put("kind",c.closed?"polygon":"stroke")
                    .put("frame",frame).put("exposure",exposure).put("color",color)
                    .put("width",width).put("fill",false).put("points",vertices));
            edits.put(new org.json.JSONObject().put("type","add_object")
                    .put("layerId",targetLayerId).put("object",object));
        }
        org.json.JSONObject future=SketchDocument.copy(doc);
        for(int i=0;i<edits.length();i++)SketchEditOps.apply(future,edits.getJSONObject(i));
        SketchDocument.validate(future);
        return edits;
    }

    /** Native read-only ownership check: never enumerates Gallery or user media. */
    static Source captureNative(android.content.Context context,String sketchId,
                                String sourceId,String userView)throws Exception {
        if(context==null)throw new IllegalArgumentException("Native context required");
        try(SketchProjectStore store=new SketchProjectStore(context)) {
            org.json.JSONObject doc=store.get(sketchId);
            Source source=Source.fromSketch(doc,sourceId,userView);
            store.referenceFile(source.sketchId,
                    SketchDocument.object(doc,source.imageId).getJSONObject("reference"));
            return source;
        }
    }

    /**
     * Read-only native Android Canvas preview. Renders the real saved source
     * and an independently validated candidate document, measures changed
     * decoded pixels, and returns a bounded PNG plus exact owned-source hashes.
     * A still-frame pixel delta is evidence of drawing, NOT anatomical accuracy.
     */
    static org.json.JSONObject previewNative(android.content.Context context,GuideDraft draft,
                    String layerId,int frame,int exposure,String prefix,String color,
                    double strokeWidth,double maxRms,double maxWorst,int maxDimension)throws Exception {
        if(context==null||draft==null||maxDimension<128||maxDimension>720)
            throw new IllegalArgumentException("Invalid native preview input");
        try(SketchProjectStore store=new SketchProjectStore(context)) {
            org.json.JSONObject original=store.get(draft.source.sketchId);
            org.json.JSONObject ref=SketchDocument.object(original,draft.source.imageId)
                    .getJSONObject("reference");
            store.referenceFile(draft.source.sketchId,ref); // verifies SHA and owned canonical path
            org.json.JSONArray ops=prepareEdit(original,draft,layerId,frame,exposure,
                    prefix,color,strokeWidth,maxRms,maxWorst);
            Projection projection=draft.project(Source.fromSketch(original,draft.source.imageId,
                    draft.source.declaredView),maxRms,maxWorst);
            org.json.JSONObject staged=SketchDocument.copy(original);
            for(int i=0;i<ops.length();i++)SketchEditOps.apply(staged,ops.getJSONObject(i));
            SketchDocument.validate(staged);
            android.graphics.Bitmap before=null,after=null,scaled=null;
            try(SketchFrameRenderer oldRenderer=new SketchFrameRenderer(context,original);
                SketchFrameRenderer newRenderer=new SketchFrameRenderer(context,staged)) {
                before=oldRenderer.render(frame);
                after=newRenderer.render(frame);
                if(before.getWidth()!=after.getWidth()||before.getHeight()!=after.getHeight())
                    throw new IllegalStateException("Native preview dimensions changed");
                int w=before.getWidth(),h=before.getHeight(),changed=0;
                int[] basePixels=new int[w],guidePixels=new int[w];
                for(int row=0;row<h;row++){
                    before.getPixels(basePixels,0,w,0,row,w,1);
                    after.getPixels(guidePixels,0,w,0,row,w,1);
                    for(int x=0;x<w;x++)if(basePixels[x]!=guidePixels[x])changed++;
                }
                if(changed==0)throw new IllegalArgumentException(
                        "Guide did not change any native pixels; inspect layer visibility and colour");
                int dimension=maxDimension;byte[] png;
                while(true){
                    double factor=Math.min(1,dimension/(double)Math.max(w,h));
                    int pw=Math.max(1,(int)Math.round(w*factor)),ph=Math.max(1,(int)Math.round(h*factor));
                    scaled=android.graphics.Bitmap.createScaledBitmap(after,pw,ph,true);
                    java.io.ByteArrayOutputStream out=new java.io.ByteArrayOutputStream();
                    if(!scaled.compress(android.graphics.Bitmap.CompressFormat.PNG,100,out))
                        throw new IllegalStateException("Native preview PNG encoding failed");
                    png=out.toByteArray();
                    if(png.length<=1024*1024)break;
                    if(scaled!=after){scaled.recycle();scaled=null;}
                    if(dimension<=128)throw new IllegalArgumentException("Native preview exceeds 1 MiB");
                    dimension=Math.max(128,dimension*3/4);
                }
                return new org.json.JSONObject().put("sourceModified",false)
                        .put("nativeRenderer","SketchFrameRenderer / Android Canvas")
                        .put("sourceRevision",original.getLong("revision"))
                        .put("sourceReferenceSha256",projection.reference.ownedSha256)
                        .put("sourceSha256",projection.reference.originalSha256)
                        .put("sourceView",projection.reference.declaredView)
                        .put("frameIndex",frame).put("changedPixels",changed)
                        .put("landmarkCount",draft.landmarks.size())
                        .put("editableObjectCount",ops.length())
                        .put("rmsLandmarkPixels",projection.rmsPixels)
                        .put("maxLandmarkPixels",projection.maxPixels)
                        .put("operations",ops)
                        .put("sketchPreviewImage",new org.json.JSONObject()
                                .put("mimeType","image/png")
                                .put("width",scaled.getWidth()).put("height",scaled.getHeight())
                                .put("sha256",SketchDocument.sha256(png))
                                .put("base64",android.util.Base64.encodeToString(
                                        png,android.util.Base64.NO_WRAP)));
            }finally{
                if(scaled!=null&&scaled!=after)scaled.recycle();
                if(after!=null)after.recycle();
                if(before!=null)before.recycle();
            }
        }
    }

    /**
     * Explicit native transaction using existing project/history/revision/undo
     * and SketchDrawingPolicy. No new persistence store or bypass action.
     * Not reachable from Sketch Studio UI until integration owner wires controls.
     */
    static org.json.JSONObject commitNative(android.content.Context context,GuideDraft draft,
                    String layerId,int frame,int exposure,String prefix,String color,
                    double strokeWidth,double maxRms,double maxWorst,String operationId)throws Exception {
        if(context==null||draft==null)throw new IllegalArgumentException("Native draft required");
        SketchDocument.id(new org.json.JSONObject().put("id",operationId),"id");
        org.json.JSONObject request;
        try(SketchProjectStore store=new SketchProjectStore(context)) {
            org.json.JSONObject doc=store.get(draft.source.sketchId);
            store.referenceFile(draft.source.sketchId,
                    SketchDocument.object(doc,draft.source.imageId).getJSONObject("reference"));
            org.json.JSONArray edits=prepareEdit(doc,draft,layerId,frame,exposure,prefix,
                    color,strokeWidth,maxRms,maxWorst);
            request=new org.json.JSONObject().put("sketchId",draft.source.sketchId)
                    .put("expectedRevision",draft.source.revision)
                    .put("operationId",operationId).put("operations",edits);
        }
        return SketchEditOps.execute(context,"sketch_edit",request);
    }

}
