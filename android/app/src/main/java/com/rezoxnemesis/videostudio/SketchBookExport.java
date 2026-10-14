package com.rezoxnemesis.videostudio;
import android.content.Context;
import android.graphics.Bitmap;
import android.net.Uri;
import org.json.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
/** Immutable page snapshots and sequential, bounded-memory native flipbook export. */
public final class SketchBookExport {
    private SketchBookExport(){}
    public static SketchNativeExport.Prepared prepare(Context context,JSONObject args,SketchNativeExport.Progress progress) throws Exception {
        SketchDocument.fields(args,"bookId,expectedRevision,operationId,pageStart,pageCount,aspect,quality");String id=SketchDocument.id(args,"bookId"),op=SketchDocument.id(args,"operationId");long revision=SketchDocument.integer(args,"expectedRevision",1,Long.MAX_VALUE-1);
        String fingerprint=SketchDocument.sha256(SketchDocument.canonical(args).getBytes(StandardCharsets.UTF_8));File generation=new File(context.getFilesDir(),"sketch_books/"+id+"/exports/"+op);
        if(!generation.getCanonicalPath().equals(context.getFilesDir().getCanonicalPath()+"/sketch_books/"+id+"/exports/"+op))throw SketchDocument.bad("Owned book export directory rejected");
        synchronized(SketchNativeExport.class){
            File snapshotFile=new File(generation,"snapshot.json");JSONObject snapshot;
            if(snapshotFile.isFile()){snapshot=read(snapshotFile,16*1024*1024);if(!fingerprint.equals(snapshot.getString("requestSha256")))throw new IllegalStateException("SKETCH_OPERATION_ID_CONFLICT");}
            else{
                JSONObject book;JSONArray allocated;try(SketchBookStore books=new SketchBookStore(context)){book=books.get(id);allocated=books.allocatedPages(id);}if(book.getLong("revision")!=revision)throw new IllegalStateException("SKETCH_BOOK_REVISION_CONFLICT");
                int start=args.has("pageStart")?(int)SketchDocument.integer(args,"pageStart",0,book.getInt("pageCount")-1):0,count=args.has("pageCount")?(int)SketchDocument.integer(args,"pageCount",1,1200):book.getInt("pageCount")-start;
                int totalFrames=0;JSONObject holds=book.optJSONObject("exposures");for(int page=start;page<start+count;page++)totalFrames+=holds==null?1:holds.optInt(String.valueOf(page),1);
                if(count>1200||totalFrames>1200||start+count>book.getInt("pageCount"))throw SketchDocument.bad("Export 1..1200 consecutive pages per phone segment");
                if(!generation.isDirectory()&&!generation.mkdirs())throw new IOException("Book export storage unavailable");
                JSONArray pages=new JSONArray();long bytes=0;
                try(SketchProjectStore sketches=new SketchProjectStore(context)){
                    sketches.beginSnapshot();try{
                        for(int i=0;i<allocated.length();i++){JSONObject entry=allocated.getJSONObject(i);int page=entry.getInt("page");if(page<start||page>=start+count)continue;JSONObject doc=sketches.get(entry.getString("sketchId"));sketches.verifyReferences(doc);byte[] json=SketchDocument.canonical(doc).getBytes(StandardCharsets.UTF_8);bytes+=json.length;if(bytes>512L*1024*1024||context.getFilesDir().getUsableSpace()<json.length+64L*1024*1024)throw new IllegalStateException("SKETCH_BOOK_SNAPSHOT_STORAGE");String name="page_"+page+".json";atomic(new File(generation,name),json);pages.put(new JSONObject().put("page",page).put("file",name).put("sha256",SketchDocument.sha256(json)));}
                    }finally{sketches.endSnapshot();}
                }
                int dependencyEntries=0;
                // Documents were captured under the DB snapshot. Immutable pixels are copied
                // on the export worker after releasing that read transaction, one page at a time.
                for(int i=0;i<pages.length();i++){
                    JSONObject declared=pages.getJSONObject(i);JSONObject document=read(new File(generation,declared.getString("file")),SketchDocument.MAX_BYTES);
                    File assets=new File(generation,"pages/"+declared.getInt("page")+"/assets");JSONObject inventory=SketchPaintArchive.freezeDependencies(context,document,assets);
                    bytes=Math.addExact(bytes,inventory.getLong("bytes"));dependencyEntries+=inventory.getJSONArray("files").length();
                    if(bytes>SketchPaintArchive.MAX_EXPANDED_BYTES-16L*1024*1024||dependencyEntries>SketchPaintArchive.MAX_ENTRIES-2)throw new IllegalStateException("SKETCH_BOOK_SNAPSHOT_STORAGE: export a smaller page segment");
                    declared.put("dependencyInventory",inventory);
                }
                String hash=SketchDocument.sha256(SketchDocument.canonical(new JSONObject().put("book",book).put("pages",pages).put("start",start).put("count",count)).getBytes(StandardCharsets.UTF_8));
                snapshot=new JSONObject().put("requestSha256",fingerprint).put("book",book).put("pages",pages).put("pageStart",start).put("frameCount",totalFrames).put("pageCount",count).put("documentSha256",hash);byte[] snapshotBytes=snapshot.toString().getBytes(StandardCharsets.UTF_8);if(snapshotBytes.length>16*1024*1024)throw new IllegalStateException("SKETCH_BOOK_SNAPSHOT_STORAGE: inventory metadata exceeds budget");atomic(snapshotFile,snapshotBytes);
            }
            JSONObject book=snapshot.getJSONObject("book");JSONArray pages=snapshot.getJSONArray("pages");int start=snapshot.getInt("pageStart"),count=snapshot.getInt("frameCount"),fps=book.getInt("fps");JSONObject holds=book.optJSONObject("exposures");int[] framePages=new int[count];for(int page=start,index=0;index<count;page++){int hold=holds==null?1:holds.optInt(String.valueOf(page),1);for(int i=0;i<hold&&index<count;i++)framePages[index++]=page;}
            String aspect=args.optString("aspect","original"),quality=args.optString("quality","720p");if(!java.util.Arrays.asList("original","9:16","16:9","1:1").contains(aspect)||!java.util.Arrays.asList("720p","1080p").contains(quality))throw SketchDocument.bad("Unsupported book export aspect/quality");if("original".equals(aspect))aspect="source:"+book.getInt("width")+"x"+book.getInt("height");
            String projectId=UUID.nameUUIDFromBytes(("sketch-output/"+id+"/"+op).getBytes(StandardCharsets.UTF_8)).toString();ProjectStore projects=new ProjectStore(context);File output=new File(generation,"sketch.mp4"),receipt=new File(generation,"completed.json");
            if(receipt.isFile()){JSONObject metadata=read(receipt,1024*1024);ProjectStore.Project project=projects.get(projectId);if(project==null||!output.isFile()||!metadata.getString("outputSha256").equals(SketchProjectStore.fileHash(output)))throw new IllegalStateException("SKETCH_EXPORT_OUTPUT_CHANGED");return new SketchNativeExport.Prepared(project,output,aspect,quality,metadata,true,generation);}
            for(File file:new File[]{output,new File(generation,"sketch.encoding.mp4")})if(file.exists()&&!file.delete())throw new IOException("Cannot remove interrupted book encoding");
            ProjectStore.Project project=new ProjectStore.Project();project.id=projectId;project.name=book.getString("name")+" · pages "+(start+1)+"–"+(start+count);JSONArray evidence=new JSONArray();long frameBytes=0;
            File framesFile=new File(generation,"frames.json");JSONObject old=framesFile.isFile()?read(framesFile,512*1024):null;java.util.Map<Integer,JSONObject> declarations=new java.util.HashMap<>();java.util.Set<Integer> verifiedPages=new java.util.HashSet<>();for(int i=0;i<pages.length();i++)declarations.put(pages.getJSONObject(i).getInt("page"),pages.getJSONObject(i));
            for(int frame=0;frame<count;frame++){
                if(Thread.currentThread().isInterrupted())throw new InterruptedException("Sketch book cancelled");progress.onProgress(frame,count);int page=framePages[frame];File png=new File(generation,String.format(java.util.Locale.ROOT,"frame_%04d.png",frame));String hash;
                if(old!=null){JSONObject declared=old.getJSONArray("frames").getJSONObject(frame);if(!png.getName().equals(declared.getString("file"))||!declared.getString("sha256").equals(SketchProjectStore.fileHash(png)))throw new IllegalStateException("SKETCH_EXPORT_FRAME_CHANGED");hash=declared.getString("sha256");}
                else{
                    JSONObject drawing;File ownedDependencies=null;if(declarations.containsKey(page)){JSONObject declared=declarations.get(page);File file=new File(generation,declared.getString("file"));if(!declared.getString("sha256").equals(SketchProjectStore.fileHash(file)))throw new IllegalStateException("SKETCH_BOOK_PAGE_CHANGED");drawing=read(file,SketchDocument.MAX_BYTES);if(declared.has("dependencyInventory")){ownedDependencies=new File(generation,"pages/"+page+"/assets");if(verifiedPages.add(page))SketchPaintArchive.verifyFrozen(context,drawing,ownedDependencies,declared.getJSONObject("dependencyInventory"));}}
                    else drawing=SketchDocument.create(new JSONObject().put("sketchId",id+"_blank").put("name",book.getString("name")).put("width",book.getInt("width")).put("height",book.getInt("height")).put("fps",fps).put("frameCount",1).put("background",book.getString("background")));
                    if(context.getFilesDir().getUsableSpace()<64L*1024*1024+4L*book.getInt("width")*book.getInt("height"))throw new IllegalStateException("SKETCH_EXPORT_STORAGE");
                    try(SketchFrameRenderer renderer=new SketchFrameRenderer(context,drawing,ownedDependencies)){Bitmap bitmap=renderer.render(0);File temp=new File(generation,png.getName()+".part");try{try(FileOutputStream stream=new FileOutputStream(temp)){if(!bitmap.compress(Bitmap.CompressFormat.PNG,100,stream))throw new IOException("Book PNG encode failed");stream.getFD().sync();}if(png.exists()&&!png.delete()||!temp.renameTo(png))throw new IOException("Book frame publication failed");}finally{bitmap.recycle();temp.delete();}}
                    hash=SketchProjectStore.fileHash(png);
                }
                frameBytes+=png.length();if(frameBytes>512L*1024*1024)throw new IllegalStateException("SKETCH_EXPORT_BUDGET");
                JSONObject timing=new JSONObject().put("frameClock","rational-authored-v1").put("frameIndex",frame).put("bookPage",page).put("fps",fps).put("presentationTimeUs",NativeFrameClock.presentationTimeUs(frame,fps)).put("durationUs",NativeFrameClock.frameDurationUs(frame,fps)).put("sketchId",id).put("sketchRevision",revision).put("documentSha256",snapshot.getString("documentSha256"));
                ProjectStore.Asset asset=new ProjectStore.Asset();asset.id="sketch_frame_"+frame;asset.uri=Uri.fromFile(png).toString();asset.mime="image/png";asset.name=png.getName();asset.role="generated_sketch_frame";asset.generated=true;asset.sizeBytes=png.length();asset.generationMetadata=timing;asset.seekable=true;asset.createdAt=0;project.assets.add(asset);
                ProjectStore.Clip clip=new ProjectStore.Clip();clip.id="sketch_clip_"+frame;clip.assetId=asset.id;clip.inMs=0;clip.outMs=NativeFrameClock.frameDurationMs(frame,fps);clip.effects=SketchDocument.copy(timing).put("directorSequenceFrame",true).put("poseFps",fps);project.clips.add(clip);evidence.put(new JSONObject().put("file",png.getName()).put("sha256",hash).put("bytes",png.length()));
            }
            JSONObject metadata=new JSONObject().put("sketchId",id).put("bookId",id).put("sketchRevision",revision).put("operationId",op).put("outputProjectId",projectId).put("documentSha256",snapshot.getString("documentSha256")).put("pageStart",start).put("frameCount",count).put("fps",fps).put("width",book.getInt("width")).put("height",book.getInt("height")).put("durationUs",NativeFrameClock.presentationTimeUs(count,fps)).put("renderer","android-bitmap-canvas").put("rasterFramesCaptured",true).put("decodedFrameEvidenceAvailable",false).put("encoded",false).put("frames",evidence);
            if(old==null)atomic(framesFile,metadata.toString().getBytes(StandardCharsets.UTF_8));ProjectStore.Project existing=projects.get(projectId);if(existing==null)projects.save(project);else{if(!SketchDocument.canonical(existing.toJson().getJSONArray("clips")).equals(SketchDocument.canonical(project.toJson().getJSONArray("clips")))||!SketchDocument.canonical(existing.toJson().getJSONArray("assets")).equals(SketchDocument.canonical(project.toJson().getJSONArray("assets"))))throw new IllegalStateException("SKETCH_EXPORT_OUTPUT_PROJECT_CHANGED");project=existing;}
            return new SketchNativeExport.Prepared(project,output,aspect,quality,metadata,false,generation);
        }
    }
    private static JSONObject read(File file,int limit) throws Exception {ByteArrayOutputStream bytes=new ByteArrayOutputStream();try(InputStream in=new FileInputStream(file)){byte[] buffer=new byte[32768];int n;long count=0;while((n=in.read(buffer))!=-1){if(Thread.currentThread().isInterrupted())throw new InterruptedException("Sketch book snapshot read cancelled");count+=n;if(count>limit)throw SketchDocument.bad("Book manifest budget exceeded");bytes.write(buffer,0,n);}}return SketchPaintArchive.parseOwnedJson(bytes.toByteArray());}
    private static void atomic(File file,byte[] bytes) throws Exception {android.util.AtomicFile target=new android.util.AtomicFile(file);FileOutputStream out=null;try{out=target.startWrite();out.write(bytes);target.finishWrite(out);}catch(Exception error){if(out!=null)target.failWrite(out);throw error;}}
}
