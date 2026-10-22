package com.rezoxnemesis.videostudio;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.BitSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;

/** Immutable owned paint tiles. Disk, sampling and painting APIs run on authoring workers. */
public final class SketchPaintStore implements AutoCloseable {
    public static final int TILE_SIZE=256;
    private static final String SNAPSHOT="videostudio.sketch.paint.snapshot.v1", STROKE="videostudio.sketch.paint.stroke.v1";
    private static final int MANIFEST_BYTES=64*1024, JOURNAL_BYTES=512*1024, TILE_BYTES=384*1024;
    private static final long QUOTA=512L*1024*1024, HEADROOM=64L*1024*1024;
    private static final int MAX_CHAIN=8192;
    private static final ConcurrentHashMap<String,OwnerQuota> OWNERS=new ConcurrentHashMap<>();
    private static final class OwnerQuota {long bytes=-1;}
    private final Context context;
    private final String id;
    private final File owner,root;
    private final String rootPath;
    private final boolean readOnly;
    private final Object lock;
    private final OwnerQuota quota;
    private String cachedSourceHash;
    private int[] cachedSourcePixels;
    private final LinkedHashMap<String,VerifiedAsset> verifiedAssets=new LinkedHashMap<>(32,.75f,true);
    private static final class VerifiedAsset {final String hash;final long size,modified;VerifiedAsset(String hash,File file){this.hash=hash;size=file.length();modified=file.lastModified();}}

    public static final class PreparedChange {
        public final JSONObject descriptor,visualOperation;
        public final String strokeSha256;
        public final int dirtyTileCount;
        PreparedChange(JSONObject descriptor,JSONObject visual,String hash,int dirty){this.descriptor=descriptor;visualOperation=visual;strokeSha256=hash;dirtyTileCount=dirty;}
    }
    /** Native restore writers invalidate quota after copying owned dependencies. */
    public static void invalidateQuota(Context context,String sketchId)throws Exception {
        String id=safeId(sketchId),path=context.getFilesDir().getCanonicalPath()+"/sketch_studio/"+id+"/paint";OwnerQuota state=OWNERS.get(path);if(state!=null)synchronized(state){state.bytes=-1;}
    }

    public SketchPaintStore(Context context,String sketchId)throws Exception {
        this.context=context.getApplicationContext();id=safeId(sketchId);readOnly=false;
        owner=new File(this.context.getFilesDir(),"sketch_studio/"+id);
        String expected=this.context.getFilesDir().getCanonicalPath()+"/sketch_studio/"+id;
        if(!expected.equals(owner.getCanonicalPath()))throw bad("SKETCH_PAINT_OWNERSHIP: unsafe owner directory");
        root=new File(owner,"paint");rootPath=expected+"/paint";if(!root.getCanonicalPath().equals(rootPath))throw bad("SKETCH_PAINT_OWNERSHIP: unsafe paint directory");
        quota=OWNERS.computeIfAbsent(rootPath,ignored->new OwnerQuota());lock=quota;
    }

    /** Read-only native export/archive assets, never an external path-bearing request surface. */
    public SketchPaintStore(Context context,String sketchId,File ownedDependencyRoot)throws Exception {
        this.context=context.getApplicationContext();id=safeId(sketchId);readOnly=true;
        if(ownedDependencyRoot==null)throw bad("SKETCH_PAINT_OWNERSHIP: archive owner required");
        String files=this.context.getFilesDir().getCanonicalPath(),canonical=ownedDependencyRoot.getCanonicalPath();
        if(!canonical.startsWith(files+"/")||!ownedDependencyRoot.isDirectory())throw bad("SKETCH_PAINT_OWNERSHIP: private archive owner required");
        String relative=canonical.substring(files.length()+1);
        if(!relative.matches("sketch_studio/[A-Za-z0-9_-]{1,96}/(exports|archives)/[A-Za-z0-9_-]{1,96}/assets")&&
           !relative.matches("sketch_books/[A-Za-z0-9_-]{1,96}/exports/[A-Za-z0-9_-]{1,96}/pages/[0-9]{1,6}/assets")&&
           !relative.matches("sketch_books/[A-Za-z0-9_-]{1,96}/exports/[A-Za-z0-9_-]{1,96}/cels/[0-9]{1,3}/[0-9]{1,6}/assets")&&
           !relative.matches("sketch_archive_staging/[A-Za-z0-9_-]{1,160}/assets"))
            throw bad("SKETCH_PAINT_OWNERSHIP: unsupported private archive scope");
        owner=ownedDependencyRoot.getCanonicalFile();root=new File(owner,"paint");rootPath=canonical+"/paint";
        if(!root.getCanonicalPath().equals(rootPath))throw bad("SKETCH_PAINT_OWNERSHIP: unsafe archived paint directory");
        quota=null;lock=new Object();
    }

    public static void validateDescriptor(JSONObject d)throws Exception {
        if(d==null)throw bad("Paint descriptor required");fields(d,"file,sha256,width,height,tileSize");
        String hash=hash(d.getString("sha256"));if(!d.getString("file").equals(hash+".json"))throw bad("Paint snapshot filename must match its hash");
        int w=integer(d,"width",256,2048),h=integer(d,"height",256,2048);dimensions(w,h);
        integer(d,"tileSize",TILE_SIZE,TILE_SIZE);
    }

    public JSONObject empty(int width,int height)throws Exception {
        writable();dimensions(width,height);
        synchronized(lock){JSONObject manifest=new JSONObject().put("schema",SNAPSHOT).put("width",width).put("height",height).put("tileSize",TILE_SIZE).put("strokeCount",0).put("tiles",new JSONArray());
            String sha=publishJson("",manifest,MANIFEST_BYTES);return descriptor(sha,width,height);}
    }

    /** Prepare assets; caller commits descriptor/history/event/receipt in one SQLite transaction. */
    public PreparedChange prepare(JSONObject document,JSONObject operation)throws Exception {
        writable();Target selected=target(document,operation);SketchPaintBrush.Plan plan=selected.plan;JSONObject object=selected.object,before=selected.before;
        int width=before.getInt("width"),height=before.getInt("height"),chosenFrame=selected.frame;String objectId=object.getString("id");
        synchronized(lock){
            JSONObject previous=manifest(before);TreeMap<Integer,String> tiles=tileMap(previous,width,height);
            if(previous.getInt("strokeCount")>=MAX_CHAIN)throw bad("SKETCH_PAINT_JOURNAL_BUDGET: use a fresh cel or page after 8192 strokes");
            if(previous.has("strokeSha256")){JSONObject head=stroke(previous.getString("strokeSha256"));if(head.getString("strokeId").equals(plan.stroke.getString("strokeId")))throw bad("SKETCH_PAINT_STROKE_ID_CONFLICT: use a new stable stroke ID");}
            JSONObject record=new JSONObject().put("schema",STROKE).put("objectId",objectId).put("strokeId",plan.stroke.getString("strokeId"))
                    .put("request",copy(plan.stroke)).put("beforePaint",before);
            if("clone".equals(plan.stroke.getJSONObject("brush").getString("mode")))record.put("source",pinSource(document,object,plan.stroke.getJSONObject("cloneSource"),chosenFrame,true));
            SketchPaintBrush.Source source=record.has("source")?source(record.getJSONObject("source"),width,height):null;
            int columns=(width+TILE_SIZE-1)/TILE_SIZE,dirty=0;BitSet affected=plan.tileIndexes;
            for(int index=affected.nextSetBit(0);index>=0;index=affected.nextSetBit(index+1)){
                interrupted();int tx=index%columns,ty=index/columns;Bitmap baseline=tiles.containsKey(index)?tile(tiles.get(index)):null;
                Bitmap target=baseline==null?Bitmap.createBitmap(TILE_SIZE,TILE_SIZE,Bitmap.Config.ARGB_8888):baseline.copy(Bitmap.Config.ARGB_8888,true);
                try{SketchPaintBrush.apply(target,baseline,tx,ty,plan,source,1);
                    if(baseline!=null&&target.sameAs(baseline)||baseline==null&&transparent(target))continue;
                    if(transparent(target))tiles.remove(index);else tiles.put(index,publishBitmap("tiles",target,TILE_BYTES));dirty++;
                }finally{target.recycle();if(baseline!=null)baseline.recycle();}
            }
            String strokeHash=publishJson("strokes",record,JOURNAL_BYTES);JSONArray declarations=new JSONArray();
            for(Map.Entry<Integer,String> tile:tiles.entrySet())declarations.put(new JSONObject().put("x",tile.getKey()%columns).put("y",tile.getKey()/columns).put("sha256",tile.getValue()));
            JSONObject after=new JSONObject().put("schema",SNAPSHOT).put("width",width).put("height",height).put("tileSize",TILE_SIZE)
                    .put("tiles",declarations).put("parentSha256",before.getString("sha256")).put("strokeSha256",strokeHash).put("strokeCount",previous.getInt("strokeCount")+1);
            JSONObject next=descriptor(publishJson("",after,MANIFEST_BYTES),width,height);
            JSONObject visual=new JSONObject().put("type","paint_stroke").put("objectId",objectId).put("strokeId",plan.stroke.getString("strokeId"))
                    .put("beforePaint",before).put("afterPaint",next).put("stroke",record);
            return new PreparedChange(copy(next),copy(visual),strokeHash,dirty);
        }
    }

    /** Paint an existing authored closed contour into ordinary immutable native tiles. */
    public PreparedChange prepareFill(JSONObject document,JSONObject operation)throws Exception {
        writable();interrupted();if(!id.equals(document.getString("id")))throw bad("Wrong contour paint owner");
        SketchContourPaint.Selection selected=SketchContourPaint.select(document,operation);JSONObject before=selected.before;int width=before.getInt("width"),height=before.getInt("height");
        synchronized(lock){
            JSONObject previous=manifest(before);if(previous.getInt("strokeCount")>=MAX_CHAIN)throw bad("Paint journal budget exceeded");
            if(selected.moveUnderInk&&(previous.getInt("strokeCount")!=0||previous.getJSONArray("tiles").length()!=0))throw bad("Choose a paint cel already behind the ink, or create a fresh base cel");
            if(previous.has("strokeSha256")&&stroke(previous.getString("strokeSha256")).getString("strokeId").equals(operation.getString("strokeId")))throw bad("SKETCH_PAINT_STROKE_ID_CONFLICT");
            JSONObject record=new JSONObject().put("schema",STROKE).put("objectId",operation.getString("objectId")).put("strokeId",operation.getString("strokeId")).put("request",copy(operation)).put("beforePaint",before).put("contour",selected.pin).put("moveUnderInk",selected.moveUnderInk);
            validateStroke(record);TreeMap<Integer,String> tiles=tileMap(previous,width,height);int columns=(width+TILE_SIZE-1)/TILE_SIZE,dirty=0;RectF bounds=new RectF();selected.path.computeBounds(bounds,true);
            int left=Math.max(0,(int)Math.floor(bounds.left)),top=Math.max(0,(int)Math.floor(bounds.top)),right=Math.min(width,(int)Math.ceil(bounds.right)),bottom=Math.min(height,(int)Math.ceil(bounds.bottom));Paint ink=new Paint(Paint.ANTI_ALIAS_FLAG);ink.setStyle(Paint.Style.FILL);ink.setColor(SketchDocument.color(operation.getString("color")));
            // Raster clipping changes Skia's curved-edge coverage/color rounding.
            // Draw the bounded area once at native canvas size, then copy raw
            // premultiplied pixels into tiles without another drawing transform.
            Bitmap composite=Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888);
            try{
                Canvas full=new Canvas(composite);draw(full,before);interrupted();full.drawPath(selected.path,ink);interrupted();
                int[] raw=new int[width*height];composite.copyPixelsToBuffer(java.nio.IntBuffer.wrap(raw));int[] tilePixels=new int[TILE_SIZE*TILE_SIZE];
                for(int ty=top/TILE_SIZE;ty<=(bottom-1)/TILE_SIZE;ty++)for(int tx=left/TILE_SIZE;tx<=(right-1)/TILE_SIZE;tx++){
                    interrupted();int index=ty*columns+tx,x=tx*TILE_SIZE,y=ty*TILE_SIZE,tileWidth=Math.min(TILE_SIZE,width-x),tileHeight=Math.min(TILE_SIZE,height-y);java.util.Arrays.fill(tilePixels,0);
                    for(int row=0;row<tileHeight;row++)System.arraycopy(raw,(y+row)*width+x,tilePixels,row*TILE_SIZE,tileWidth);
                    Bitmap baseline=tiles.containsKey(index)?tile(tiles.get(index)):null,target=Bitmap.createBitmap(TILE_SIZE,TILE_SIZE,Bitmap.Config.ARGB_8888);
                    try{target.copyPixelsFromBuffer(java.nio.IntBuffer.wrap(tilePixels));if(baseline!=null&&target.sameAs(baseline)||baseline==null&&transparent(target))continue;if(transparent(target))tiles.remove(index);else tiles.put(index,publishBitmap("tiles",target,TILE_BYTES));dirty++;}finally{target.recycle();if(baseline!=null)baseline.recycle();}
                }
            }finally{composite.recycle();}
            interrupted();String strokeHash=publishJson("strokes",record,JOURNAL_BYTES);JSONArray declarations=new JSONArray();for(Map.Entry<Integer,String> tile:tiles.entrySet())declarations.put(new JSONObject().put("x",tile.getKey()%columns).put("y",tile.getKey()/columns).put("sha256",tile.getValue()));
            JSONObject after=new JSONObject().put("schema",SNAPSHOT).put("width",width).put("height",height).put("tileSize",TILE_SIZE).put("tiles",declarations).put("parentSha256",before.getString("sha256")).put("strokeSha256",strokeHash).put("strokeCount",previous.getInt("strokeCount")+1);JSONObject next=descriptor(publishJson("",after,MANIFEST_BYTES),width,height);
            JSONObject visual=new JSONObject().put("type","paint_fill").put("objectId",operation.getString("objectId")).put("strokeId",operation.getString("strokeId")).put("beforePaint",before).put("afterPaint",next).put("stroke",record);return new PreparedChange(copy(next),copy(visual),strokeHash,dirty);
        }
    }

    public void verify(JSONObject d)throws Exception {
        JSONObject m=manifest(d);for(String sha:tileMap(m,d.getInt("width"),d.getInt("height")).values())verifyPng(owned("tiles",sha,"png",TILE_BYTES),TILE_SIZE,TILE_SIZE);
        if(m.has("strokeSha256")){JSONObject journal=stroke(m.getString("strokeSha256"));
            parent(d,m,journal);
            if(journal.has("source"))verifySource(journal.getJSONObject("source"));}
    }

    public Bitmap render(JSONObject d,int maximum,JSONObject crop)throws Exception {
        validateDescriptor(d);View view=view(d,maximum,crop);Bitmap output=Bitmap.createBitmap(view.width,view.height,Bitmap.Config.ARGB_8888);
        try{Canvas canvas=new Canvas(output);canvas.scale((float)view.width/view.area.width(),(float)view.height/view.area.height());canvas.translate(-view.area.left,-view.area.top);canvas.clipRect(view.area);draw(canvas,d);return output;}
        catch(Exception|OutOfMemoryError error){output.recycle();throw error;}
    }

    /** Exact full-canvas source for bounded deformation and alpha support, independent of viewport limits. */
    Bitmap renderSource(JSONObject descriptor)throws Exception {
        if(android.os.Looper.myLooper()==android.os.Looper.getMainLooper())throw new IllegalStateException("Full paint source requires a worker");
        validateDescriptor(descriptor);interrupted();Bitmap image=Bitmap.createBitmap(descriptor.getInt("width"),descriptor.getInt("height"),Bitmap.Config.ARGB_8888);
        try{draw(new Canvas(image),descriptor);interrupted();return image;}catch(Exception|OutOfMemoryError failure){image.recycle();throw failure;}
    }

    /** Worker-only native resolution streaming into an existing export/viewport Canvas. */
    public void draw(Canvas canvas,JSONObject d)throws Exception {
        JSONObject m=manifest(d);int w=d.getInt("width"),h=d.getInt("height"),columns=(w+TILE_SIZE-1)/TILE_SIZE;
        // Tile edges touch. Per-bitmap edge AA would composite two half-coverages
        // at transformed shared seams and dim otherwise opaque paint to 75%.
        Rect clip=canvas.getClipBounds();Paint filter=new Paint(Paint.FILTER_BITMAP_FLAG);
        for(Map.Entry<Integer,String> entry:tileMap(m,w,h).entrySet()){
            interrupted();int x=(entry.getKey()%columns)*TILE_SIZE,y=(entry.getKey()/columns)*TILE_SIZE;
            if(!Rect.intersects(clip,new Rect(x,y,Math.min(w,x+TILE_SIZE),Math.min(h,y+TILE_SIZE))))continue;
            Bitmap image=tile(entry.getValue());try{canvas.drawBitmap(image,x,y,filter);}finally{image.recycle();}
        }
    }

    public Bitmap renderPrefix(JSONObject visual,double fraction,int maximum,JSONObject crop)throws Exception {
        if(!Double.isFinite(fraction)||fraction<0||fraction>1)throw bad("Paint replay fraction must be 0..1");
        fields(visual,"type,objectId,strokeId,beforePaint,afterPaint,stroke");if(!java.util.Arrays.asList("paint_stroke","paint_fill").contains(visual.getString("type")))throw bad("Paint replay requires its authoritative native paint event");
        JSONObject before=visual.getJSONObject("beforePaint"),after=visual.getJSONObject("afterPaint"),record=visual.getJSONObject("stroke");
        validateDescriptor(before);validateDescriptor(after);validateStroke(record);
        if(!SketchDocument.canonical(record.getJSONObject("beforePaint")).equals(SketchDocument.canonical(before))||!record.getString("objectId").equals(visual.getString("objectId"))||!record.getString("strokeId").equals(visual.getString("strokeId")))throw bad("SKETCH_PAINT_REPLAY_CHANGED: event pins changed");
        JSONObject finalManifest=manifest(after);String journalHash=SketchDocument.sha256(bytes(record));
        if(!journalHash.equals(finalManifest.optString("strokeSha256"))||!before.getString("sha256").equals(finalManifest.optString("parentSha256")))throw bad("SKETCH_PAINT_REPLAY_CHANGED: committed journal mismatch");
        if(fraction==0||"paint_fill".equals(visual.getString("type"))&&fraction<1)return render(before,maximum,crop);if(fraction==1)return render(after,maximum,crop);
        int width=before.getInt("width"),height=before.getInt("height");SketchPaintBrush.Plan plan=SketchPaintBrush.plan(record.getJSONObject("request"),width,height);
        SketchPaintBrush.Source source=record.has("source")?source(record.getJSONObject("source"),width,height):null;
        return renderPlan(before,plan,source,fraction,maximum,crop);
    }

    /** Shared durable replay/human preview path; draws actual brush pixels without publishing. */
    private Bitmap renderPlan(JSONObject before,SketchPaintBrush.Plan plan,SketchPaintBrush.Source source,double fraction,int maximum,JSONObject crop)throws Exception {
        int width=before.getInt("width"),height=before.getInt("height");TreeMap<Integer,String> tiles=tileMap(manifest(before),width,height);BitSet selected=new BitSet();for(Integer index:tiles.keySet())selected.set(index);selected.or(plan.tileIndexes);
        View view=view(before,maximum,crop);Bitmap output=Bitmap.createBitmap(view.width,view.height,Bitmap.Config.ARGB_8888);int columns=(width+TILE_SIZE-1)/TILE_SIZE;
        try{Canvas canvas=new Canvas(output);canvas.scale((float)view.width/view.area.width(),(float)view.height/view.area.height());canvas.translate(-view.area.left,-view.area.top);canvas.clipRect(view.area);Paint filter=new Paint(Paint.FILTER_BITMAP_FLAG);
            for(int index=selected.nextSetBit(0);index>=0;index=selected.nextSetBit(index+1)){
                interrupted();int tx=index%columns,ty=index/columns,x=tx*TILE_SIZE,y=ty*TILE_SIZE;if(!view.area.intersects(x,y,x+TILE_SIZE,y+TILE_SIZE))continue;
                Bitmap baseline=tiles.containsKey(index)?tile(tiles.get(index)):null,target=null;
                try{if(plan.tileIndexes.get(index)){target=baseline==null?Bitmap.createBitmap(TILE_SIZE,TILE_SIZE,Bitmap.Config.ARGB_8888):baseline.copy(Bitmap.Config.ARGB_8888,true);SketchPaintBrush.apply(target,baseline,tx,ty,plan,source,fraction);}
                    Bitmap shown=target==null?baseline:target;if(shown!=null)canvas.drawBitmap(shown,x,y,filter);
                }finally{if(target!=null)target.recycle();if(baseline!=null)baseline.recycle();}
            }return output;
        }catch(Exception|OutOfMemoryError error){output.recycle();throw error;}
    }
    /** Worker-only transient brush preview. It never publishes an asset or changes saved state. */
    public Bitmap renderGesture(JSONObject document,JSONObject operation,int maximum,JSONObject crop)throws Exception {
        Target selected=target(document,operation);SketchPaintBrush.Source source=null;JSONObject request=selected.plan.stroke;
        if("clone".equals(request.getJSONObject("brush").getString("mode"))){JSONObject pin=pinSource(document,selected.object,request.getJSONObject("cloneSource"),selected.frame,false);source=source(pin,selected.before.getInt("width"),selected.before.getInt("height"),referenceFile(pin.getJSONObject("originalReference")));}
        return renderPlan(selected.before,selected.plan,source,1,maximum,crop);
    }

    public String sample(JSONObject d,double x,double y)throws Exception {
        normalized(x,"sample x");normalized(y,"sample y");JSONObject m=manifest(d);int w=d.getInt("width"),h=d.getInt("height"),px=Math.min(w-1,(int)Math.floor(x*w)),py=Math.min(h-1,(int)Math.floor(y*h));
        String sha=tileMap(m,w,h).get((py/TILE_SIZE)*((w+TILE_SIZE-1)/TILE_SIZE)+px/TILE_SIZE);if(sha==null)return "#00000000";
        Bitmap image=tile(sha);try{return color(image.getPixel(px%TILE_SIZE,py%TILE_SIZE));}finally{image.recycle();}
    }

    public String sampleReference(JSONObject doc,String referenceId,String expectedHash,double x,double y,int frame)throws Exception {
        normalized(x,"sample x");normalized(y,"sample y");if(!id.equals(doc.getString("id")))throw bad("SKETCH_PAINT_OWNERSHIP: wrong reference document");
        JSONObject object=SketchDocument.object(doc,safeId(referenceId));if(!"image".equals(object.getString("kind")))throw bad("Owned reference image required");
        JSONObject ref=object.getJSONObject("reference");if(!hash(expectedHash).equals(ref.getString("sha256")))throw bad("SKETCH_PAINT_SOURCE_CHANGED: reference hash differs");
        File file=referenceFile(ref);int w=integer(ref,"width",1,2048),h=integer(ref,"height",1,2048);verifyPng(file,w,h);double[] m=referenceMapping(doc,object,null,frame);
        double u=m[0]*x+m[2]*y+m[4],v=m[1]*x+m[3]*y+m[5];if(!insideSource(u,v,sourceClip(object)))return "#00000000";
        Bitmap image=decode(file,w,h);try{return color(image.getPixel(Math.min(w-1,(int)Math.floor(u*w)),Math.min(h-1,(int)Math.floor(v*h))));}finally{image.recycle();}
    }

    public JSONArray journal(JSONObject d,int offset,int limit)throws Exception {
        if(offset<0||offset>MAX_CHAIN||limit<1||limit>128)throw bad("Paint journal requires offset 0..8192 and limit 1..128");
        JSONArray result=new JSONArray();JSONObject current=copy(d);java.util.HashSet<String> seen=new java.util.HashSet<>();int count=0;
        while(true){interrupted();JSONObject m=manifest(current);if(!seen.add(current.getString("sha256")))throw bad("SKETCH_PAINT_JOURNAL_CHANGED: cyclic ancestry");
            if(!m.has("strokeSha256"))break;if(count>=MAX_CHAIN)throw bad("SKETCH_PAINT_JOURNAL_BUDGET");JSONObject record=stroke(m.getString("strokeSha256"));
            JSONObject prior=parent(current,m,record);if(count++>=offset)result.put(record);if(result.length()>=limit)break;current=prior;
        }return result;
    }

    /** Bounded, streaming ancestry check for reuse of current authored stage paint. */
    void requireAuthoredStageSource(JSONObject descriptor)throws Exception {
        if(android.os.Looper.myLooper()==android.os.Looper.getMainLooper())throw bad("Authored paint reuse requires a worker");verify(descriptor);JSONObject current=copy(descriptor);java.util.HashSet<String> seen=new java.util.HashSet<>();int count=0;
        while(true){interrupted();if(!seen.add(current.getString("sha256")))throw bad("Authored paint ancestry is cyclic");JSONObject m=manifest(current);
            if(count==0&&m.getJSONArray("tiles").length()==0)throw bad("Choose paint with retained authored pixels");
            if(!m.has("strokeSha256")){if(m.getInt("strokeCount")!=0||m.getJSONArray("tiles").length()!=0)throw bad("Authored paint ancestry must begin with an empty native snapshot");return;}
            if(++count>MAX_CHAIN)throw bad("Authored paint ancestry exceeds8192 steps");JSONObject record=stroke(m.getString("strokeSha256")),request=record.getJSONObject("request");
            if(record.has("source")||request.has("cloneSource"))throw bad("Reference or clone-derived paint cannot become an authored clean part");
            if(!"paint_fill".equals(request.optString("type"))){JSONObject brush=request.optJSONObject("brush");if(!"paint_stroke".equals(request.optString("type"))||brush==null||!java.util.Arrays.asList("paint","erase").contains(brush.optString("mode")))throw bad("Reuse only native authored paint/fill ancestry");}
            current=parent(current,m,record);
        }
    }

    public LinkedHashMap<String,File> dependencies(JSONObject d)throws Exception {
        LinkedHashMap<String,File> result=new LinkedHashMap<>();JSONObject current=copy(d);java.util.HashSet<String> seen=new java.util.HashSet<>();int count=0;
        while(true){interrupted();String sha=current.getString("sha256");if(!seen.add(sha))throw bad("SKETCH_PAINT_JOURNAL_CHANGED: cyclic ancestry");JSONObject m=manifest(current);result.put("paint/"+sha+".json",owned("",sha,"json",MANIFEST_BYTES));
            for(String tile:tileMap(m,current.getInt("width"),current.getInt("height")).values()){String path="paint/tiles/"+tile+".png";if(!result.containsKey(path)){File file=owned("tiles",tile,"png",TILE_BYTES);verifyPng(file,TILE_SIZE,TILE_SIZE);result.put(path,file);}}
            if(!m.has("strokeSha256"))break;if(++count>MAX_CHAIN)throw bad("SKETCH_PAINT_JOURNAL_BUDGET");String strokeHash=m.getString("strokeSha256");JSONObject record=stroke(strokeHash);result.put("paint/strokes/"+strokeHash+".json",owned("strokes",strokeHash,"json",JOURNAL_BYTES));
            if(record.has("source")){JSONObject pin=record.getJSONObject("source");validateSource(pin);String path="paint/sources/"+pin.getString("sha256")+".png";if(!result.containsKey(path)){verifySource(pin);result.put(path,owned("sources",pin.getString("sha256"),"png",8*1024*1024));}}
            current=parent(current,m,record);
        }return result;
    }

    public void copyTo(String targetId,JSONObject d)throws Exception {
        writable();LinkedHashMap<String,File> closure=dependencies(d);
        try(SketchPaintStore target=new SketchPaintStore(context,safeId(targetId))){synchronized(target.lock){for(Map.Entry<String,File> entry:closure.entrySet()){
            interrupted();String relative=entry.getKey().substring("paint/".length()),directory=relative.contains("/")?relative.substring(0,relative.lastIndexOf('/')):"";
            String filename=relative.substring(relative.lastIndexOf('/')+1),extension=filename.substring(filename.lastIndexOf('.')+1),sha=filename.substring(0,filename.lastIndexOf('.'));
            int maximum="tiles".equals(directory)?TILE_BYTES:"sources".equals(directory)?8*1024*1024:"strokes".equals(directory)?JOURNAL_BYTES:MANIFEST_BYTES;
            target.publish(directory,extension,read(entry.getValue(),maximum),sha,maximum);
        }target.verify(d);}}
    }

    private JSONObject pinSource(JSONObject doc,JSONObject target,JSONObject requested,int frame,boolean publishSource)throws Exception {
        JSONObject guide=SketchDocument.object(doc,safeId(requested.getString("objectId")));if(!"image".equals(guide.getString("kind")))throw bad("Clone brush requires an owned image reference");
        JSONObject ref=guide.getJSONObject("reference");String sha=hash(requested.getString("sha256"));if(!sha.equals(ref.getString("sha256")))throw bad("SKETCH_PAINT_SOURCE_CHANGED: clone hash differs");
        File original=referenceFile(ref);int w=integer(ref,"width",1,2048),h=integer(ref,"height",1,2048);verifyPng(original,w,h);boolean filtered=SketchCloneSourceSampling.filtered(requested);if(filtered)SketchCloneSourceSampling.requireTarget(doc.getInt("width"),doc.getInt("height"));Rect sampleRect=filtered?(requested.has("mapping")?new Rect(0,0,w,h):SketchCloneSourceSampling.sourceRect(guide,w,h)):null;double[] mapping;
        if(requested.has("mapping")){JSONArray a=requested.getJSONArray("mapping");mapping=new double[6];for(int i=0;i<6;i++)mapping[i]=a.getDouble(i);}
        else{mapping=referenceMapping(doc,guide,target,frame,sampleRect);double scale=requested.optDouble("scale",1),angle=Math.toRadians(-requested.optDouble("rotation",0)),cos=Math.cos(angle)/scale,sin=Math.sin(angle)/scale;
            double a=cos*mapping[0]-sin*mapping[1],b=sin*mapping[0]+cos*mapping[1],c=cos*mapping[2]-sin*mapping[3],dd=sin*mapping[2]+cos*mapping[3];
            double tx=cos*(mapping[4]-.5)-sin*(mapping[5]-.5)+.5+requested.optDouble("offsetX",0),ty=sin*(mapping[4]-.5)+cos*(mapping[5]-.5)+.5+requested.optDouble("offsetY",0);mapping=new double[]{a,b,c,dd,tx,ty};}
        JSONArray frozen=new JSONArray();for(double value:mapping){if(!Double.isFinite(value)||Math.abs(value)>4096)throw bad("Clone mapping exceeds finite transform budget");frozen.put(value);}
        if(publishSource){File retained=new File(new File(root,"sources"),sha+".png");if(retained.exists())verifyPng(owned("sources",sha,"png",8*1024*1024),w,h);else{byte[] data=read(original,8*1024*1024);if(!sha.equals(SketchDocument.sha256(data)))throw bad("SKETCH_PAINT_SOURCE_CHANGED: source changed while copying");publish("sources","png",data,sha,8*1024*1024);}}
        JSONObject clip=requested.has("mapping")?new JSONObject().put("x",0).put("y",0).put("width",1).put("height",1):sourceClip(guide);
        if(filtered)clip=new JSONObject().put("x",sampleRect.left/(double)w).put("y",sampleRect.top/(double)h).put("width",sampleRect.width()/(double)w).put("height",sampleRect.height()/(double)h);
        JSONObject pin=new JSONObject().put("file",sha+".png").put("sha256",sha).put("width",w).put("height",h).put("mapping",frozen).put("sourceClip",clip).put("originalReference",copy(ref));
        if(requested.has("sampling"))pin.put("sampling",SketchCloneSourceSampling.choice(requested));if(filtered)pin.put("sourceRect",SketchCloneSourceSampling.declaration(sampleRect));return pin;
    }

    private double[] referenceMapping(JSONObject doc,JSONObject guide,JSONObject target,int frame)throws Exception {return referenceMapping(doc,guide,target,frame,null);}

    private double[] referenceMapping(JSONObject doc,JSONObject guide,JSONObject target,int frame,Rect filteredCrop)throws Exception {
        int width=doc.getInt("width"),height=doc.getInt("height");if(frame<0||frame>=doc.getInt("frameCount"))throw bad("Reference sample frame outside document");
        Matrix sourceWorld=new Matrix();sourceWorld.setConcat(SketchFrameRenderer.matrix(SketchDocument.transformAt(SketchDocument.owner(doc,guide.getString("id")),frame),width,height),SketchFrameRenderer.matrix(SketchDocument.transformAt(guide,frame),width,height));
        Matrix inverse=new Matrix();if(!sourceWorld.invert(inverse))throw bad("Clone reference transform cannot be inverted");Matrix targetWorld=new Matrix();
        if(target!=null)targetWorld.setConcat(SketchFrameRenderer.matrix(SketchDocument.transformAt(SketchDocument.owner(doc,target.getString("id")),frame),width,height),SketchFrameRenderer.matrix(SketchDocument.transformAt(target,frame),width,height));
        Matrix combined=new Matrix();combined.setConcat(inverse,targetWorld);float[] coordinates={0,0,width,0,0,height};combined.mapPoints(coordinates);
        JSONObject b=guide.getJSONObject("bounds"),crop=guide.optJSONObject("referenceCrop");if(crop==null)crop=new JSONObject().put("x",0).put("y",0).put("width",1).put("height",1);SketchDocument.validateBounds(b);SketchDocument.validateBounds(crop);
        if(filteredCrop!=null){JSONObject ref=guide.getJSONObject("reference");crop=new JSONObject().put("x",filteredCrop.left/(double)ref.getInt("width")).put("y",filteredCrop.top/(double)ref.getInt("height")).put("width",filteredCrop.width()/(double)ref.getInt("width")).put("height",filteredCrop.height()/(double)ref.getInt("height"));}
        double[] u=new double[3],v=new double[3];for(int i=0;i<3;i++){u[i]=((coordinates[i*2]/width-b.getDouble("x"))/b.getDouble("width"))*crop.getDouble("width")+crop.getDouble("x");v[i]=((coordinates[i*2+1]/height-b.getDouble("y"))/b.getDouble("height"))*crop.getDouble("height")+crop.getDouble("y");}
        return new double[]{u[1]-u[0],v[1]-v[0],u[2]-u[0],v[2]-v[0],u[0],v[0]};
    }

    private File referenceFile(JSONObject reference)throws Exception {
        String sha=hash(reference.getString("sha256"));if(!reference.getString("file").equals(sha+".png"))throw bad("Owned reference filename differs");File directory=new File(owner,"references"),file=new File(directory,sha+".png");
        if(!directory.getCanonicalPath().equals(owner.getCanonicalPath()+"/references")||!file.getCanonicalPath().equals(directory.getCanonicalPath()+"/"+sha+".png"))throw bad("SKETCH_PAINT_OWNERSHIP: reference escaped owner");
        VerifiedAsset known=verifiedAssets.get(file.getCanonicalPath());if(!readOnly&&(known==null||!known.hash.equals(sha)||known.size!=file.length()||known.modified!=file.lastModified()))try(SketchProjectStore sketches=new SketchProjectStore(context)){sketches.referenceFile(id,reference);}
        checked(file,sha,8*1024*1024);return file;
    }

    private SketchPaintBrush.Source source(JSONObject pin,int canvasWidth,int canvasHeight)throws Exception {
        verifySource(pin);return source(pin,canvasWidth,canvasHeight,owned("sources",pin.getString("sha256"),"png",8*1024*1024));
    }
    private SketchPaintBrush.Source source(JSONObject pin,int canvasWidth,int canvasHeight,File file)throws Exception {
        validateSource(pin);String sha=pin.getString("sha256");int w=pin.getInt("width"),h=pin.getInt("height");checked(file,sha,8*1024*1024);verifyPng(file,w,h);
        if(SketchCloneSourceSampling.filtered(pin)){
            SketchCloneSourceSampling.requireTarget(canvasWidth,canvasHeight);String key="bilinear:"+sha+":"+canvasWidth+":"+canvasHeight+":"+SketchDocument.canonical(pin.getJSONArray("mapping"))+":"+pin.getJSONArray("sourceRect").toString();
            int[] retained=cachedSourcePixels;if(!key.equals(cachedSourceHash)||retained==null){cachedSourcePixels=null;cachedSourceHash=null;Bitmap image=decode(file,w,h);try{retained=SketchCloneSourceSampling.raster(image,pin,canvasWidth,canvasHeight);cachedSourcePixels=retained;cachedSourceHash=key;}finally{image.recycle();}}
            int[] pixels=retained;return(paintX,paintY)->{int x=(int)Math.floor(paintX),y=(int)Math.floor(paintY);return x<0||y<0||x>=canvasWidth||y>=canvasHeight?0:pixels[y*canvasWidth+x];};
        }
        int[] retained=cachedSourcePixels;if(!sha.equals(cachedSourceHash)||retained==null){Bitmap image=decode(file,w,h);try{retained=new int[w*h];image.getPixels(retained,0,w,0,0,w,h);cachedSourcePixels=retained;cachedSourceHash=sha;}finally{image.recycle();}}
        int[] pixels=retained;JSONArray values=pin.getJSONArray("mapping");double[] m=new double[6];for(int i=0;i<6;i++)m[i]=values.getDouble(i);JSONObject clip=pin.getJSONObject("sourceClip");double left=clip.getDouble("x"),top=clip.getDouble("y"),right=left+clip.getDouble("width"),bottom=top+clip.getDouble("height");
        return(paintX,paintY)->{double x=paintX/canvasWidth,y=paintY/canvasHeight,u=m[0]*x+m[2]*y+m[4],v=m[1]*x+m[3]*y+m[5];if(u<left||u>=right||v<top||v>=bottom)return 0;return pixels[Math.min(h-1,(int)Math.floor(v*h))*w+Math.min(w-1,(int)Math.floor(u*w))];};
    }

    private void verifySource(JSONObject pin)throws Exception {
        validateSource(pin);verifyPng(owned("sources",pin.getString("sha256"),"png",8*1024*1024),pin.getInt("width"),pin.getInt("height"));
    }
    private static void validateSource(JSONObject pin)throws Exception {
        fields(pin,"file,sha256,width,height,mapping,sourceClip,originalReference,sampling,sourceRect");String sha=hash(pin.getString("sha256"));if(!pin.getString("file").equals(sha+".png"))throw bad("Paint source filename differs");
        int w=integer(pin,"width",1,2048),h=integer(pin,"height",1,2048);JSONArray mapping=pin.getJSONArray("mapping");if(mapping.length()!=6)throw bad("Paint source requires six affine terms");for(int i=0;i<6;i++)SketchDocument.scalar(mapping.get(i),-4096,4096,"source mapping");
        SketchDocument.validateBounds(pin.getJSONObject("sourceClip"));boolean filtered=SketchCloneSourceSampling.filtered(pin);if(filtered!=pin.has("sourceRect"))throw bad("Filtered clone source crop missing or unexpected");
        if(filtered){Rect rect=SketchCloneSourceSampling.validatedRect(pin);JSONObject clip=pin.getJSONObject("sourceClip");if(clip.getDouble("x")!=rect.left/(double)w||clip.getDouble("y")!=rect.top/(double)h||clip.getDouble("width")!=rect.width()/(double)w||clip.getDouble("height")!=rect.height()/(double)h)throw bad("Filtered clone crop differs from its frozen source clip");}
        JSONObject ref=pin.getJSONObject("originalReference");if(!sha.equals(ref.getString("sha256"))||!ref.getString("file").equals(sha+".png")||integer(ref,"width",1,2048)!=w||integer(ref,"height",1,2048)!=h)throw bad("SKETCH_PAINT_SOURCE_CHANGED: provenance differs from frozen source");
    }
    private static JSONObject sourceClip(JSONObject guide)throws Exception {return guide.has("referenceCrop")?copy(guide.getJSONObject("referenceCrop")):new JSONObject().put("x",0).put("y",0).put("width",1).put("height",1);}
    private static boolean insideSource(double u,double v,JSONObject clip)throws Exception {return u>=clip.getDouble("x")&&u<clip.getDouble("x")+clip.getDouble("width")&&v>=clip.getDouble("y")&&v<clip.getDouble("y")+clip.getDouble("height");}
    private JSONObject parent(JSONObject descriptor,JSONObject current,JSONObject journal)throws Exception {
        JSONObject before=journal.getJSONObject("beforePaint");if(!before.getString("sha256").equals(current.getString("parentSha256"))||before.getInt("width")!=descriptor.getInt("width")||before.getInt("height")!=descriptor.getInt("height"))throw bad("SKETCH_PAINT_JOURNAL_CHANGED: parent hash/dimensions differ");
        JSONObject previous=manifest(before);if(previous.getInt("strokeCount")+1!=current.getInt("strokeCount"))throw bad("SKETCH_PAINT_JOURNAL_CHANGED: parent stroke count differs");return before;
    }
    private static final class Target {final JSONObject object,before;final SketchPaintBrush.Plan plan;final int frame;Target(JSONObject object,JSONObject before,SketchPaintBrush.Plan plan,int frame){this.object=object;this.before=before;this.plan=plan;this.frame=frame;}}
    private Target target(JSONObject document,JSONObject operation)throws Exception {
        if(!id.equals(document.getString("id")))throw bad("SKETCH_PAINT_OWNERSHIP: wrong sketch document");int width=integer(document,"width",256,2048),height=integer(document,"height",256,2048);dimensions(width,height);SketchPaintBrush.Plan plan=SketchPaintBrush.plan(operation,width,height);
        String objectId=safeId(plan.stroke.getString("objectId"));JSONObject object=SketchDocument.object(document,objectId);if(!"paint".equals(object.getString("kind")))throw bad("Paint stroke requires a paint cel");if(object.has("paintMesh"))throw bad("Remove the paint deformation grid before authoring source pixels");SketchEditOps.requireUnlocked(SketchDocument.owner(document,objectId));
        int frames=integer(document,"frameCount",1,1200),start=integer(object,"frame",0,frames-1),exposure=integer(object,"exposure",1,frames-start),frame=plan.stroke.has("frameIndex")?integer(plan.stroke,"frameIndex",0,frames-1):start;if(frame<start||frame>=start+exposure)throw bad("Paint stroke frame must be inside its target cel exposure");
        JSONObject bounds=object.getJSONObject("bounds");SketchDocument.validateBounds(bounds);if(bounds.getDouble("x")!=0||bounds.getDouble("y")!=0||bounds.getDouble("width")!=1||bounds.getDouble("height")!=1)throw bad("Paint cel bounds must cover its native canvas");
        JSONObject before=copy(object.getJSONObject("paint"));validateDescriptor(before);if(before.getInt("width")!=width||before.getInt("height")!=height)throw bad("Paint cel dimensions must match canvas");return new Target(object,before,plan,frame);
    }

    private JSONObject manifest(JSONObject d)throws Exception {
        validateDescriptor(d);JSONObject m=json(owned("",d.getString("sha256"),"json",MANIFEST_BYTES),MANIFEST_BYTES);fields(m,"schema,width,height,tileSize,tiles,parentSha256,strokeSha256,strokeCount");
        if(!SNAPSHOT.equals(m.getString("schema"))||integer(m,"width",256,2048)!=d.getInt("width")||integer(m,"height",256,2048)!=d.getInt("height")||integer(m,"tileSize",TILE_SIZE,TILE_SIZE)!=TILE_SIZE)throw bad("SKETCH_PAINT_SNAPSHOT_CHANGED: descriptor mismatch");
        int count=integer(m,"strokeCount",0,MAX_CHAIN);if(m.has("parentSha256")!=m.has("strokeSha256")||m.has("parentSha256")!=(count>0))throw bad("Paint snapshot ancestry requires paired hashes/count");if(m.has("parentSha256")){hash(m.getString("parentSha256"));hash(m.getString("strokeSha256"));}tileMap(m,d.getInt("width"),d.getInt("height"));return m;
    }

    private static TreeMap<Integer,String> tileMap(JSONObject manifest,int width,int height)throws Exception {
        JSONArray declarations=manifest.getJSONArray("tiles");int columns=(width+TILE_SIZE-1)/TILE_SIZE,rows=(height+TILE_SIZE-1)/TILE_SIZE;if(declarations.length()>columns*rows)throw bad("Paint tile budget exceeded");
        TreeMap<Integer,String> result=new TreeMap<>();int previous=-1;for(int i=0;i<declarations.length();i++){JSONObject entry=declarations.getJSONObject(i);fields(entry,"x,y,sha256");int x=integer(entry,"x",0,columns-1),y=integer(entry,"y",0,rows-1),index=y*columns+x;if(index<=previous)throw bad("Paint tiles require unique sorted coordinates");previous=index;result.put(index,hash(entry.getString("sha256")));}return result;
    }

    private JSONObject stroke(String sha)throws Exception {JSONObject record=json(owned("strokes",hash(sha),"json",JOURNAL_BYTES),JOURNAL_BYTES);validateStroke(record);return record;}
    private static void validateStroke(JSONObject record)throws Exception {
        fields(record,"schema,objectId,strokeId,request,beforePaint,source,contour,moveUnderInk");if(!STROKE.equals(record.getString("schema")))throw bad("Unknown paint journal schema");safeId(record.getString("objectId"));safeId(record.getString("strokeId"));validateDescriptor(record.getJSONObject("beforePaint"));JSONObject request=record.getJSONObject("request");
        if(!record.getString("objectId").equals(request.getString("objectId"))||!record.getString("strokeId").equals(request.getString("strokeId")))throw bad("Paint journal request IDs changed");
        if("paint_fill".equals(request.optString("type"))){SketchContourPaint.request(request);if(record.has("source")||!record.has("contour")||!(record.get("moveUnderInk") instanceof Boolean))throw bad("Contour fill provenance missing or unexpected");JSONObject pin=record.getJSONObject("contour");SketchContourPaint.path(pin);if(!request.getString("contourObjectId").equals(pin.getJSONObject("object").getString("id"))||!request.getString("expectedContourSha256").equals(pin.getString("sha256"))||pin.getInt("width")!=record.getJSONObject("beforePaint").getInt("width")||pin.getInt("height")!=record.getJSONObject("beforePaint").getInt("height"))throw bad("Contour fill provenance differs from its request");if(record.getBoolean("moveUnderInk")&&!SketchContourPaint.emptyHash(pin.getInt("width"),pin.getInt("height")).equals(record.getJSONObject("beforePaint").getString("sha256")))throw bad("Only a fresh paint cel can move behind ink");return;}
        if(record.has("contour")||record.has("moveUnderInk"))throw bad("Brush strokes cannot inject contour provenance");SketchPaintBrush.plan(request,record.getJSONObject("beforePaint").getInt("width"),record.getJSONObject("beforePaint").getInt("height"));
        boolean clone="clone".equals(request.getJSONObject("brush").getString("mode"));if(clone!=record.has("source"))throw bad("Paint clone journal source missing or unexpected");
        if(clone){JSONObject pin=record.getJSONObject("source");validateSource(pin);if(!request.getJSONObject("cloneSource").getString("sha256").equals(pin.getString("sha256")))throw bad("SKETCH_PAINT_SOURCE_CHANGED: journal source differs from authored request");JSONObject requested=request.getJSONObject("cloneSource");if(requested.has("sampling")!=pin.has("sampling")||(requested.has("sampling")&&!requested.getString("sampling").equals(pin.getString("sampling"))))throw bad("SKETCH_PAINT_JOURNAL_CHANGED: clone sampling differs from authored request");}
    }

    private Bitmap tile(String sha)throws Exception {return decode(owned("tiles",sha,"png",TILE_BYTES),TILE_SIZE,TILE_SIZE);}
    private Bitmap decode(File file,int width,int height)throws Exception {verifyPng(file,width,height);BitmapFactory.Options options=new BitmapFactory.Options();options.inPreferredConfig=Bitmap.Config.ARGB_8888;Bitmap image=BitmapFactory.decodeFile(file.getPath(),options);if(image==null)throw bad("SKETCH_PAINT_IMAGE_CHANGED: decode failed");if(image.getWidth()!=width||image.getHeight()!=height){image.recycle();throw bad("SKETCH_PAINT_IMAGE_CHANGED: dimensions differ");}return image;}
    private static void verifyPng(File file,int width,int height)throws Exception {BitmapFactory.Options header=new BitmapFactory.Options();header.inJustDecodeBounds=true;BitmapFactory.decodeFile(file.getPath(),header);if(header.outWidth!=width||header.outHeight!=height||!"image/png".equals(header.outMimeType))throw bad("SKETCH_PAINT_IMAGE_CHANGED: expected bounded PNG");}
    private File owned(String directory,String sha,String extension,int maximum)throws Exception {
        hash(sha);File folder=directory.isEmpty()?root:new File(root,directory),file=new File(folder,sha+"."+extension);String expected=rootPath+(directory.isEmpty()?"":"/"+directory);
        if(!folder.getCanonicalPath().equals(expected)||!file.getCanonicalPath().equals(expected+"/"+sha+"."+extension))throw bad("SKETCH_PAINT_OWNERSHIP: asset path escaped");checked(file,sha,maximum);return file;
    }

    private String publishJson(String directory,JSONObject json,int maximum)throws Exception {byte[] content=bytes(json);String sha=SketchDocument.sha256(content);publish(directory,"json",content,sha,maximum);return sha;}
    private String publishBitmap(String directory,Bitmap image,int maximum)throws Exception {java.io.ByteArrayOutputStream buffer=new java.io.ByteArrayOutputStream();if(!image.compress(Bitmap.CompressFormat.PNG,100,buffer))throw bad("SKETCH_PAINT_STORAGE: PNG encoding failed");byte[] content=buffer.toByteArray();String sha=SketchDocument.sha256(content);publish(directory,"png",content,sha,maximum);return sha;}
    private void publish(String directory,String extension,byte[] content,String sha,int maximum)throws Exception {
        writable();interrupted();hash(sha);if(content.length<1||content.length>maximum||!sha.equals(SketchDocument.sha256(content)))throw bad("SKETCH_PAINT_ASSET_BUDGET: encoded asset exceeds hash/budget");
        File folder=directory.isEmpty()?root:new File(root,directory);String expected=rootPath+(directory.isEmpty()?"":"/"+directory);if(!folder.getCanonicalPath().equals(expected))throw bad("SKETCH_PAINT_OWNERSHIP: unsafe publication directory");
        if(!folder.isDirectory()&&!folder.mkdirs())throw bad("SKETCH_PAINT_STORAGE: asset directory unavailable");File target=new File(folder,sha+"."+extension);if(!target.getCanonicalPath().equals(expected+"/"+sha+"."+extension))throw bad("SKETCH_PAINT_OWNERSHIP: unsafe publication path");
        if(target.exists()){checked(target,sha,maximum);return;}
        if(quota.bytes<0)quota.bytes=storageBytes(root,0);
        if(context.getFilesDir().getUsableSpace()<HEADROOM+content.length||quota.bytes>QUOTA-content.length)throw bad("SKETCH_PAINT_STORAGE_FULL: preserve the drawing and archive pages before continuing");
        File temporary=File.createTempFile(".paint-",".part",folder);
        try{try(FileOutputStream stream=new FileOutputStream(temporary)){stream.write(content);stream.getFD().sync();}interrupted();if(target.exists()){checked(target,sha,maximum);return;}
            if(!temporary.renameTo(target)){if(target.exists())checked(target,sha,maximum);else throw bad("SKETCH_PAINT_STORAGE: atomic asset publication failed");}
            else quota.bytes=Math.addExact(quota.bytes,content.length);
        }finally{if(temporary.exists())temporary.delete();}
    }

    private long storageBytes(File directory,int depth)throws Exception {
        String canonical=directory.getCanonicalPath();if(depth>3||(!canonical.equals(rootPath)&&!canonical.startsWith(rootPath+"/")))throw bad("SKETCH_PAINT_OWNERSHIP: unsafe asset inventory");File[] children=directory.listFiles();if(children==null)throw bad("SKETCH_PAINT_STORAGE: cannot inspect assets");long total=0;
        for(File child:children){interrupted();if(!child.getCanonicalPath().equals(new File(directory.getCanonicalFile(),child.getName()).getPath()))throw bad("SKETCH_PAINT_OWNERSHIP: asset link rejected");total=Math.addExact(total,child.isDirectory()?storageBytes(child,depth+1):child.length());if(total>QUOTA)return total;}return total;
    }
    private void checked(File file,String expected,int maximum)throws Exception {
        if(!file.isFile()||file.length()<1||file.length()>maximum)throw bad("SKETCH_PAINT_ASSET_UNAVAILABLE: bounded asset missing");String path=file.getCanonicalPath();VerifiedAsset old=verifiedAssets.get(path);
        if(old!=null&&old.hash.equals(expected)&&old.size==file.length()&&old.modified==file.lastModified())return;
        if(!expected.equals(SketchProjectStore.fileHash(file)))throw bad("SKETCH_PAINT_ASSET_CHANGED: immutable hash differs");verifiedAssets.put(path,new VerifiedAsset(expected,file));while(verifiedAssets.size()>256)verifiedAssets.remove(verifiedAssets.keySet().iterator().next());
    }
    private static byte[] read(File file,int maximum)throws Exception {
        try(InputStream stream=new FileInputStream(file);java.io.ByteArrayOutputStream output=new java.io.ByteArrayOutputStream()){
            byte[] buffer=new byte[32768];int count=0,length;while((length=stream.read(buffer))!=-1){interrupted();if(length>maximum-count)throw bad("SKETCH_PAINT_ASSET_BUDGET");output.write(buffer,0,length);count+=length;}return output.toByteArray();
        }
    }
    private static JSONObject json(File file,int maximum)throws Exception {return SketchPaintArchive.parseOwnedJson(read(file,maximum));}
    // Android JSONObject serialization normalizes integral doubles/negative zero.
    // Normalize before hashing so persisted journals and detached events have identical bytes.
    private static byte[] bytes(JSONObject json)throws Exception {return SketchDocument.canonical(new JSONObject(json.toString())).getBytes(StandardCharsets.UTF_8);}
    private static JSONObject copy(JSONObject json)throws Exception {return new JSONObject(json.toString());}
    private static JSONObject descriptor(String sha,int w,int h)throws Exception {return new JSONObject().put("file",sha+".json").put("sha256",sha).put("width",w).put("height",h).put("tileSize",TILE_SIZE);}
    private static int integer(JSONObject o,String field,int low,int high)throws Exception {return(int)SketchDocument.integer(o,field,low,high);}
    private static void dimensions(int w,int h){if(w<256||w>2048||h<256||h>2048||(w&1)!=0||(h&1)!=0||(long)w*h>4194304)throw bad("Paint canvas requires even 256..2048 sides and at most 2048² pixels");}
    private static String safeId(String value)throws Exception {return SketchDocument.id(new JSONObject().put("id",value),"id");}
    private static String hash(String value){if(value==null||!value.matches("[a-f0-9]{64}"))throw bad("Paint asset requires lower-case SHA256");return value;}
    private static void normalized(double value,String label){if(!Double.isFinite(value)||value<0||value>1)throw bad(label+" requires normalized 0..1");}
    private static void fields(JSONObject value,String names){SketchDocument.fields(value,names);}
    private static String color(int pixel){return String.format(java.util.Locale.ROOT,"#%08X",pixel);}
    private static boolean transparent(Bitmap image){int[] row=new int[image.getWidth()];for(int y=0;y<image.getHeight();y++){image.getPixels(row,0,image.getWidth(),0,y,image.getWidth(),1);for(int pixel:row)if((pixel>>>24)!=0)return false;}return true;}
    private static void interrupted()throws InterruptedException {if(Thread.currentThread().isInterrupted())throw new InterruptedException("Native paint cancelled before publication");}
    private void writable(){if(readOnly)throw bad("SKETCH_PAINT_READ_ONLY: immutable export assets cannot be edited");}
    private static IllegalArgumentException bad(String message){return new IllegalArgumentException(message);}
    private static final class View {final int width,height;final RectF area;View(int width,int height,RectF area){this.width=width;this.height=height;this.area=area;}}
    private static View view(JSONObject descriptor,int maximum,JSONObject crop)throws Exception {
        if(maximum<128||maximum>1024)throw bad("Paint viewport longest edge must be 128..1024");int w=descriptor.getInt("width"),h=descriptor.getInt("height");RectF area=new RectF(0,0,w,h);
        if(crop!=null){SketchDocument.validateBounds(crop);int left=Math.min(w-1,(int)Math.floor(crop.getDouble("x")*w)),top=Math.min(h-1,(int)Math.floor(crop.getDouble("y")*h)),right=Math.min(w,Math.max(left+1,(int)Math.ceil((crop.getDouble("x")+crop.getDouble("width"))*w))),bottom=Math.min(h,Math.max(top+1,(int)Math.ceil((crop.getDouble("y")+crop.getDouble("height"))*h)));area=new RectF(left,top,right,bottom);}
        double scale=maximum/(double)Math.max(area.width(),area.height());if(crop==null)scale=Math.min(1,scale);return new View(Math.max(1,(int)Math.round(area.width()*scale)),Math.max(1,(int)Math.round(area.height()*scale)),area);
    }
    @Override public void close(){cachedSourcePixels=null;cachedSourceHash=null;verifiedAssets.clear();}
}
