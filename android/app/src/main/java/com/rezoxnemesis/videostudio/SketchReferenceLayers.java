package com.rezoxnemesis.videostudio;

import android.content.Context;
import android.graphics.*;
import android.os.Looper;
import android.util.Base64;
import org.json.*;
import java.io.*;
import java.util.*;

/** Source-bound hard raster parts. Shared by touch and MCP, never hidden-surface generation. */
final class SketchReferenceLayers {
    private static final long MAX_PNG=8L*1024*1024,MAX_RETAINED=128L*1024*1024;
    private static final String FIELDS="sketchId,expectedRevision,operationId,sourceObjectId,expectedReferenceSha256,expectedSourceSha256,frame,exposure,hideSource,masks,background,edgeSnapRadius,allowPartOverlap";
    private SketchReferenceLayers() {}
    static JSONObject requireSource(JSONObject doc,String objectId,String working,String original)throws Exception{
        JSONObject object=SketchDocument.object(doc,objectId),layer=SketchDocument.owner(doc,objectId);
        if(!"image".equals(object.getString("kind"))||layer.getJSONArray("objects").length()!=1)throw SketchDocument.bad("Choose a reference image on its own dedicated layer");
        if(layer.getBoolean("locked")||!layer.getBoolean("visible")||layer.getDouble("opacity")!=1||!"normal".equals(layer.optString("blendMode","normal")))throw SketchDocument.bad("Source layer must be unlocked, visible, opaque and normal");
        identity(layer,false);identity(object,true);
        if(object.has("referenceCrop"))throw SketchDocument.bad("Use an uncropped reference for named parts");
        JSONObject ref=object.getJSONObject("reference");
        if(!working.equals(ref.getString("sha256"))||!original.equals(ref.optString("sourceSha256")))throw SketchDocument.bad("Source hashes changed; inspect the current imported reference");
        return object;
    }
    private static void identity(JSONObject node,boolean guide)throws Exception{
        JSONObject t=node.getJSONObject("transform");if(t.getDouble("x")!=0||t.getDouble("y")!=0||t.getDouble("scaleX")!=1||t.getDouble("scaleY")!=1||t.getDouble("rotation")!=0||node.getJSONArray("keys").length()!=0||(!guide&&t.getDouble("opacity")!=1)||(guide&&t.getDouble("opacity")!=0&&t.getDouble("opacity")!=1))throw SketchDocument.bad("Source layer and image require unanimated identity placement; reset transforms first");
    }
    private static void validateArgs(JSONObject a)throws Exception{
        SketchDocument.fields(a,FIELDS);SketchDocument.id(a,"sketchId");SketchDocument.id(a,"operationId");SketchDocument.id(a,"sourceObjectId");SketchDocument.integer(a,"expectedRevision",1,Long.MAX_VALUE-2);SketchSourceMask.hashField(a,"expectedReferenceSha256");SketchSourceMask.hashField(a,"expectedSourceSha256");SketchDocument.bool(a,"hideSource");
        if(a.has("edgeSnapRadius"))SketchDocument.integer(a,"edgeSnapRadius",0,16);
        if(a.has("allowPartOverlap"))SketchDocument.bool(a,"allowPartOverlap");
        JSONArray masks=a.getJSONArray("masks");if(masks.length()<1||masks.length()>8)throw SketchDocument.bad("Use 1..8 named parts");Set<String> masksIds=new HashSet<>(),ids=new HashSet<>();
        for(int i=0;i<masks.length();i++){JSONObject m=masks.getJSONObject(i);SketchDocument.fields(m,"maskId,layerId,objectId,name,rings,pivot");String id=SketchDocument.id(m,"maskId");if(!masksIds.add(id))throw SketchDocument.bad("Duplicate mask ID");descriptor(m,ids);SketchSourceMask.rings(m.getJSONArray("rings"),8);pivot(m);}
        if(a.has("background")){JSONObject bg=a.getJSONObject("background");SketchDocument.fields(bg,"layerId,objectId,name");descriptor(bg,ids);}
    }
    static void pivot(JSONObject mask)throws Exception {
        if(!mask.has("pivot"))return;JSONArray point=mask.getJSONArray("pivot");if(point.length()!=2)throw SketchDocument.bad("Part pivot requires [x,y] in full source coordinates");
        for(int i=0;i<2;i++)SketchDocument.scalar(point.get(i),0,1,"Part pivot coordinate");
    }
    private static void descriptor(JSONObject m,Set<String> ids)throws Exception{for(String f:new String[]{"layerId","objectId"})if(!ids.add(SketchDocument.id(m,f)))throw SketchDocument.bad("Duplicate target layer/object ID");SketchDocument.text(m,"name",160);}
    private static void worker(){if(Looper.myLooper()==Looper.getMainLooper())throw new IllegalStateException("SKETCH_REFERENCE_LAYERS_BACKGROUND_REQUIRED");}
    static JSONObject execute(Context context,SketchProjectStore store,JSONObject args,String fingerprint)throws Exception{
        worker();validateArgs(args);String id=args.getString("sketchId"),operation=args.getString("operationId");
        JSONObject replay=store.operationReceipt(id,operation,fingerprint);if(replay!=null)return receipt(replay,args);checkCancelled();
        JSONArray visual=new JSONArray();JSONObject details=args.has("allowPartOverlap")?new JSONObject().put("paintChanges",new JSONArray()):null;SketchProjectStore.Mutation edit=doc->{
            SketchDrawingPolicy.requireAction(store.activeDrawingSession(id),"sketch_import_reference_layers",args);
            try(Prepared p=prepare(store,doc,args)){
                if(details!=null)details.put("referenceLayers",new JSONObject().put("sharedSourcePixelCount",p.sharedPixels));
                // Validate the complete prospective document before any file is published.
                JSONObject next=assemble(doc,args,p,null,null);SketchDocument.validate(next);
                List<File> temporary=new ArrayList<>();List<JSONObject> refs=new ArrayList<>();
                File root=new File(context.getFilesDir(),"sketch_studio/"+id+"/references").getCanonicalFile();
                File owned=new File(context.getFilesDir(),"sketch_studio/"+id).getCanonicalFile();if(!root.getParentFile().equals(owned))throw SketchDocument.bad("Invalid reference directory");
                if(!root.isDirectory()&&!root.mkdirs())throw new IOException("Cannot create owned reference directory");
                try{
                    StorageBudget budget=new StorageBudget(root);int[] out=new int[p.pixels.length];
                    for(int i=0;i<p.parts.size();i++){
                        checkCancelled();
                        Part part=p.parts.get(i);Arrays.fill(out,0);for(int j=part.selected.nextSetBit(0);j>=0;j=part.selected.nextSetBit(j+1))out[j]=p.pixels[j];
                        Bitmap b=Bitmap.createBitmap(out,p.w,p.h,Bitmap.Config.ARGB_8888);File temp=File.createTempFile("parts-",".tmp",root);temporary.add(temp);
                        try(FileOutputStream stream=new FileOutputStream(temp)){if(!b.compress(Bitmap.CompressFormat.PNG,100,stream))throw new IOException("Cannot encode source part");stream.getFD().sync();}finally{b.recycle();}
                        checkCancelled();budget.part(temp.length());
                        String hash=SketchProjectStore.fileHash(temp);refs.add(reference(p,part,args,hash));
                    }
                    checkCancelled();next=assemble(doc,args,p,refs,visual);SketchDocument.validate(next);checkCancelled();
                    for(int i=0;i<temporary.size();i++){
                        checkCancelled();File temp=temporary.get(i),target=new File(root,refs.get(i).getString("file"));
                        if(target.exists()){if(!refs.get(i).getString("sha256").equals(SketchProjectStore.fileHash(target)))throw new IOException("Owned reference hash collision/corruption");}
                        else if(!temp.renameTo(target)){if(!target.exists()||!refs.get(i).getString("sha256").equals(SketchProjectStore.fileHash(target)))throw new IOException("Cannot publish owned source part");}
                    }
                    checkCancelled();return next;
                }finally{for(File temp:temporary)if(temp.exists()&&!temp.delete())temp.deleteOnExit();}
            }
        };JSONObject result=details==null?store.mutate(id,operation,args.getLong("expectedRevision"),fingerprint,edit,visual):store.mutatePrepared(id,operation,args.getLong("expectedRevision"),fingerprint,edit,visual,details);return receipt(result,args);
    }
    private static JSONObject receipt(JSONObject r,JSONObject a)throws Exception{if(a.has("allowPartOverlap"))r.put("sharedSourcePixelCount",r.getJSONObject("referenceLayers").getInt("sharedSourcePixelCount"));return r.put("sourceAssisted",true).put("partCount",a.getJSONArray("masks").length()).put("backgroundIncluded",a.has("background"));}
    static JSONObject preview(Context context,SketchProjectStore store,JSONObject args)throws Exception{
        worker();checkCancelled();validateArgs(args);JSONObject doc=store.get(args.getString("sketchId"));SketchProjectStore.requireRevision(doc,args.getLong("expectedRevision"));
        try(Prepared p=prepare(store,doc,args)){
            SketchDocument.validate(assemble(doc,args,p,null,null));
            previewStorage(p,store.referenceFile(doc.getString("id"),p.ref).getParentFile());checkCancelled();
            int w=p.w,h=p.h;double scale=Math.min(1,720.0/Math.max(w,h));int rw=Math.max(1,(int)Math.round(w*scale)),rh=Math.max(1,(int)Math.round(h*scale));
            Bitmap nativeImage=Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888),overlay=Bitmap.createBitmap(rw,rh,Bitmap.Config.ARGB_8888);
            try{
                nativeImage.setPixels(p.pixels,0,w,0,0,w,h);Canvas canvas=new Canvas(overlay);canvas.drawColor(Color.rgb(36,39,47));canvas.scale((float)scale,(float)scale);Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);paint.setFilterBitmap(true);canvas.drawBitmap(nativeImage,0,0,paint);
                int[] colors={0xff42c9ff,0xffffb847,0xffd586ff,0xff5de2a0,0xffff7797,0xffd9e257,0xffd4d9e4,0xffdba77b};
                for(int i=0;i<p.accepted.length();i++){JSONObject m=p.accepted.getJSONObject(i);Path path=path(m.getJSONArray("rings"),w,h);paint.setStyle(Paint.Style.FILL);paint.setColor((colors[i]&0xffffff)|0x50000000);canvas.drawPath(path,paint);paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth((float)(2/scale));paint.setColor(colors[i]);canvas.drawPath(path,paint);paint.setStyle(Paint.Style.FILL);}
                ByteArrayOutputStream out=new ByteArrayOutputStream();if(!overlay.compress(Bitmap.CompressFormat.PNG,100,out))throw new IOException("Cannot encode mask preview");byte[] png=out.toByteArray();
                return new JSONObject().put("sourceModified",false).put("sourceRevision",doc.getLong("revision")).put("sourceReferenceSha256",args.getString("expectedReferenceSha256")).put("sourceSha256",args.getString("expectedSourceSha256")).put("acceptedMasks",p.accepted).put("edgeAdjustedPointCount",p.adjusted).put("sourceAssisted",true).put("hiddenSurfaceReconstruction",false).put("sharedSourcePixelCount",p.sharedPixels).put("retainedPreviewImage",retainedPreview(nativeImage,p)).put("retainedPreviewSource","named_parts_without_background").put("retainedPreviewCompositing","source_over_layer_order").put("sketchPreviewImage",new JSONObject().put("mimeType","image/png").put("base64",Base64.encodeToString(png,Base64.NO_WRAP)).put("sha256",SketchDocument.sha256(png)).put("width",rw).put("height",rh));
            }finally{nativeImage.recycle();overlay.recycle();}
        }
    }
    /** Actual ordered source-over stack, reusing one private full-source bitmap and a bounded output. */
    private static JSONObject retainedPreview(Bitmap image,Prepared p)throws Exception {
        double scale=Math.min(1,360d/Math.max(p.w,p.h));int w=Math.max(1,(int)Math.round(p.w*scale)),h=Math.max(1,(int)Math.round(p.h*scale));Bitmap bounded=Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888);
        try{Canvas canvas=new Canvas(bounded);Paint paint=new Paint(Paint.FILTER_BITMAP_FLAG);paint.setStyle(Paint.Style.FILL);RectF destination=new RectF(0,0,w,h);int[] row=new int[p.w];
            for(Part part:p.parts){if(part.complement)continue;checkCancelled();for(int y=0;y<p.h;y++){checkCancelled();Arrays.fill(row,0);int offset=y*p.w;for(int x=0;x<p.w;x++)if(part.selected.get(offset+x))row[x]=p.pixels[offset+x];image.setPixels(row,0,p.w,0,y,p.w,1);}canvas.drawBitmap(image,null,destination,paint);checkCancelled();}
            ByteArrayOutputStream out=new ByteArrayOutputStream();if(!bounded.compress(Bitmap.CompressFormat.PNG,100,out))throw new IOException("Cannot encode retained artwork preview");byte[] png=out.toByteArray();if(png.length>1024*1024)throw SketchDocument.bad("Retained preview exceeds 1 MiB");return new JSONObject().put("mimeType","image/png").put("base64",Base64.encodeToString(png,Base64.NO_WRAP)).put("sha256",SketchDocument.sha256(png)).put("bytes",png.length).put("width",w).put("height",h);
        }finally{bounded.recycle();}
    }
    private static void checkCancelled()throws InterruptedIOException{if(Thread.currentThread().isInterrupted())throw new InterruptedIOException("Reference separation cancelled before commit; retry the operation ID to recover any earlier committed receipt");}
    /** Identical conservative retained/output/free-space admission for preview and execution. */
    private static final class StorageBudget {
        final File root;final long retained,available;long staged;
        StorageBudget(File root)throws Exception{
            this.root=root;long bytes=0;File[] files=root.listFiles();if(files==null)throw new IOException("Cannot inspect retained references");for(File file:files)if(file.isFile())bytes=Math.addExact(bytes,file.length());retained=bytes;available=root.getUsableSpace();require();
        }
        void part(long bytes)throws Exception{if(bytes<1||bytes>MAX_PNG)throw SketchDocument.bad("Part PNG exceeds 8 MiB; simplify the source");staged=Math.addExact(staged,bytes);require();}
        private void require(){if(retained+staged>MAX_RETAINED||available-staged<64L*1024*1024||root.getUsableSpace()<64L*1024*1024)throw SketchDocument.bad("Owned reference storage budget exhausted");}
    }
    private static final class PngCount extends OutputStream {
        long count;
        @Override public void write(int value)throws IOException{add(1);}
        @Override public void write(byte[] bytes,int offset,int length)throws IOException{add(length);}
        private void add(int length)throws IOException{count+=length;if(count>MAX_PNG)throw new IOException("Part PNG exceeds 8 MiB");}
    }
    private static void previewStorage(Prepared p,File root)throws Exception{
        StorageBudget budget=new StorageBudget(root);int[] pixels=new int[p.pixels.length];
        for(Part part:p.parts){
            checkCancelled();Arrays.fill(pixels,0);for(int n=part.selected.nextSetBit(0);n>=0;n=part.selected.nextSetBit(n+1))pixels[n]=p.pixels[n];
            Bitmap image=Bitmap.createBitmap(pixels,p.w,p.h,Bitmap.Config.ARGB_8888);PngCount count=new PngCount();
            try{if(!image.compress(Bitmap.CompressFormat.PNG,100,count)||count.count>MAX_PNG)throw SketchDocument.bad("Part PNG exceeds the preview encoding budget");}finally{image.recycle();}
            checkCancelled();budget.part(count.count);
        }
    }
    private static final class Part{JSONObject descriptor;JSONArray rings;boolean complement;BitSet selected;}
    private static final class Prepared implements AutoCloseable{JSONObject source,ref;JSONArray accepted;int w,h,adjusted,sharedPixels;int[] pixels;List<Part> parts=new ArrayList<>();public void close(){pixels=null;for(Part p:parts)p.selected=null;}}
    private static Prepared prepare(SketchProjectStore store,JSONObject doc,JSONObject a)throws Exception{
        int frame=(int)SketchDocument.integer(a,"frame",0,doc.getInt("frameCount")-1);SketchDocument.integer(a,"exposure",1,doc.getInt("frameCount")-frame);
        Prepared p=new Prepared();p.source=requireSource(doc,a.getString("sourceObjectId"),a.getString("expectedReferenceSha256"),a.getString("expectedSourceSha256"));p.ref=p.source.getJSONObject("reference");
        File file=store.referenceFile(doc.getString("id"),p.ref);BitmapFactory.Options size=new BitmapFactory.Options();size.inJustDecodeBounds=true;BitmapFactory.decodeFile(file.getPath(),size);p.w=p.ref.getInt("width");p.h=p.ref.getInt("height");
        if(size.outWidth!=p.w||size.outHeight!=p.h||p.w<1||p.h<1||p.w>2048||p.h>2048)throw SketchDocument.bad("Owned source dimensions changed");
        Bitmap bitmap=BitmapFactory.decodeFile(file.getPath());if(bitmap==null)throw SketchDocument.bad("Cannot decode owned source");try{p.pixels=new int[p.w*p.h];bitmap.getPixels(p.pixels,0,p.w,0,0,p.w,p.h);}finally{bitmap.recycle();}
        p.accepted=new JSONArray(a.getJSONArray("masks").toString());int radius=a.optInt("edgeSnapRadius",0);BitSet used=new BitSet(p.pixels.length),shared=new BitSet();boolean allowOverlap=a.optBoolean("allowPartOverlap",false);JSONArray allRings=new JSONArray();
        for(int i=0;i<p.accepted.length();i++){
            JSONObject mask=p.accepted.getJSONObject(i);JSONArray rr=mask.getJSONArray("rings");if(radius>0)p.adjusted+=snap(rr,p.pixels,p.w,p.h,radius);SketchSourceMask.rings(rr,8);
            Part part=new Part();part.descriptor=mask;part.rings=rr;part.selected=new BitSet(p.pixels.length);Region region=new Region();region.setPath(path(rr,p.w,p.h),new Region(0,0,p.w,p.h));RegionIterator it=new RegionIterator(region);Rect rect=new Rect();boolean visible=false;
            while(it.next(rect))for(int y=rect.top;y<rect.bottom;y++)for(int x=rect.left;x<rect.right;x++){int n=y*p.w+x;if((p.pixels[n]>>>24)==0)continue;if(used.get(n)){if(!allowOverlap)throw SketchDocument.bad("Named parts overlap visible source pixels; refine boundaries or explicitly allow shared joint pixels");shared.set(n);}part.selected.set(n);used.set(n);visible=true;}
            if(!visible)throw SketchDocument.bad("Each named part must contain visible source pixels");p.parts.add(part);for(int r=0;r<rr.length();r++)allRings.put(rr.getJSONArray(r));
        }
        p.sharedPixels=shared.cardinality();if(a.has("background")&&p.sharedPixels>0)throw SketchDocument.bad("Shared joint pixels cannot be combined with v1 background complement; omit background or refine nonoverlapping boundaries");
        if(a.has("background")){Part bg=new Part();bg.descriptor=a.getJSONObject("background");bg.rings=allRings;bg.complement=true;bg.selected=new BitSet(p.pixels.length);for(int i=0;i<p.pixels.length;i++)if(!used.get(i))bg.selected.set(i);p.parts.add(0,bg);}
        return p;
    }
    private static Path path(JSONArray rings,int w,int h)throws Exception{return SketchSourceMask.path(rings,w,h);}
    /** Local contrast assistance only. No object semantics or inferred hidden artwork. */
    private static int snap(JSONArray rings,int[] pixels,int w,int h,int radius)throws Exception{
        int changed=0;
        for(int r=0;r<rings.length();r++){JSONArray ring=rings.getJSONArray(r);for(int i=0;i<ring.length();i++){
            JSONArray pt=ring.getJSONArray(i);double px=pt.getDouble(0)*w,py=pt.getDouble(1)*h;int cx=(int)Math.round(px),cy=(int)Math.round(py),bestX=cx,bestY=cy,best=0;double distance=Double.POSITIVE_INFINITY;
            for(int y=Math.max(1,cy-radius);y<=Math.min(h-1,cy+radius);y++)for(int x=Math.max(1,cx-radius);x<=Math.min(w-1,cx+radius);x++){
                double d=(x-px)*(x-px)+(y-py)*(y-py);if(d>radius*radius)continue;int edge=Math.max(contrast(pixels[y*w+x],pixels[y*w+x-1]),contrast(pixels[y*w+x],pixels[(y-1)*w+x]));
                if(edge>best||edge==best&&edge>0&&d<distance){best=edge;distance=d;bestX=x;bestY=y;}
            }
            if(best>0&&(Math.abs(bestX-px)>1e-9||Math.abs(bestY-py)>1e-9)){pt.put(0,bestX/(double)w).put(1,bestY/(double)h);changed++;}
        }}return changed;
    }
    private static int contrast(int a,int b){int v=0;for(int s=0;s<32;s+=8)v+=Math.abs((a>>>s&255)-(b>>>s&255));return v;}
    private static JSONObject reference(Prepared p,Part part,JSONObject a,String hash)throws Exception{return SketchDocument.copy(p.ref).put("file",hash+".png").put("sha256",hash).put("sourceMask",SketchSourceMask.metadata(a,part.complement?"background":part.descriptor.getString("maskId"),part.rings,part.complement,p.w,p.h));}
    private static JSONObject assemble(JSONObject doc,JSONObject a,Prepared p,List<JSONObject> references,JSONArray visual)throws Exception{
        JSONObject next=SketchDocument.copy(doc);String owner=SketchDocument.owner(next,a.getString("sourceObjectId")).getString("id");JSONArray old=next.getJSONArray("layers"),layers=new JSONArray();
        if(a.getBoolean("hideSource"))SketchDocument.object(next,a.getString("sourceObjectId")).getJSONObject("transform").put("opacity",0);
        for(int i=0;i<old.length();i++){
            JSONObject layer=old.getJSONObject(i);layers.put(layer);if(!owner.equals(layer.getString("id")))continue;
            for(int j=0;j<p.parts.size();j++){
                Part part=p.parts.get(j);JSONObject descriptor=part.descriptor;String hash="0".repeat(64);JSONObject ref=references==null?reference(p,part,a,hash):references.get(j);
                JSONObject image=SketchEditOps.completeObject(new JSONObject().put("id",descriptor.getString("objectId")).put("kind","image").put("frame",a.getInt("frame")).put("exposure",a.getInt("exposure")).put("bounds",SketchDocument.copy(p.source.getJSONObject("bounds"))).put("reference",ref));
                if(descriptor.has("pivot")){JSONArray pivot=descriptor.getJSONArray("pivot");JSONObject bounds=p.source.getJSONObject("bounds");image.getJSONObject("transform").put("anchorX",bounds.getDouble("x")+pivot.getDouble(0)*bounds.getDouble("width")).put("anchorY",bounds.getDouble("y")+pivot.getDouble(1)*bounds.getDouble("height"));}
                JSONObject derived=new JSONObject().put("id",descriptor.getString("layerId")).put("name",descriptor.getString("name")).put("visible",true).put("locked",false).put("opacity",1).put("transform",SketchDocument.transform()).put("keys",new JSONArray()).put("objects",new JSONArray().put(image));layers.put(derived);
                if(visual!=null)visual.put(new JSONObject().put("type","add_layer").put("layer",SketchDocument.copy(derived)));
            }
        }
        next.put("layers",layers);if(visual!=null){int at=0;for(int n=0;n<old.length();n++)if(owner.equals(old.getJSONObject(n).getString("id")))at=n+1;for(int n=0;n<p.parts.size();n++)visual.put(new JSONObject().put("type","reorder_layer").put("layerId",p.parts.get(n).descriptor.getString("layerId")).put("index",at+n));}if(visual!=null&&a.getBoolean("hideSource"))visual.put(new JSONObject().put("type","set_reference_visibility").put("objectId",a.getString("sourceObjectId")).put("visible",false));return next;
    }
}
