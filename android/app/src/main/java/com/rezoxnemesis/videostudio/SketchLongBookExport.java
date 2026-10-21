package com.rezoxnemesis.videostudio;

import android.content.*;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.graphics.Bitmap;
import org.json.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/** Feature-length export: disk-indexed frozen cels, bounded live raster memory. */
final class SketchLongBookExport {
    private SketchLongBookExport() {}

    static SketchNativeExport.Prepared prepare(Context context,JSONObject args,SketchNativeExport.Progress progress)throws Exception {
        synchronized(SketchNativeExport.class){return prepareLocked(context,args,progress);}
    }
    private static SketchNativeExport.Prepared prepareLocked(Context c,JSONObject args,SketchNativeExport.Progress progress)throws Exception {
        String id=SketchDocument.id(args,"bookId"),op=SketchDocument.id(args,"operationId");
        long revision=SketchDocument.integer(args,"expectedRevision",1,Long.MAX_VALUE-1);
        String fingerprint=SketchDocument.sha256(SketchDocument.canonical(args).getBytes(StandardCharsets.UTF_8));
        String aspect=args.optString("aspect","original"),quality=args.optString("quality","720p");
        if(!Arrays.asList("original","9:16","16:9","1:1").contains(aspect)||!Arrays.asList("720p","1080p").contains(quality))throw SketchDocument.bad("Unsupported book export aspect/quality");
        File generation=new File(c.getFilesDir(),"sketch_books/"+id+"/exports/"+op);
        if(!generation.getCanonicalPath().equals(c.getFilesDir().getCanonicalPath()+"/sketch_books/"+id+"/exports/"+op))throw SketchDocument.bad("Owned book export directory rejected");
        File snapshotFile=new File(generation,"sequence.json"),indexFile=new File(generation,"pages.db");JSONObject snapshot;
        if(snapshotFile.isFile()){
            snapshot=read(snapshotFile,16*1024*1024);
            if(!fingerprint.equals(snapshot.getString("requestSha256")))throw new IllegalStateException("SKETCH_OPERATION_ID_CONFLICT");
        }else{
            JSONObject book;try(SketchBookStore books=new SketchBookStore(c)){book=books.get(id);}
            if(book.getLong("revision")!=revision)throw new IllegalStateException("SKETCH_BOOK_REVISION_CONFLICT");
            int start=args.has("pageStart")?(int)SketchDocument.integer(args,"pageStart",0,book.getInt("pageCount")-1):0;
            int pages=args.has("pageCount")?(int)SketchDocument.integer(args,"pageCount",1,SketchSequenceLimits.MAX_CELS):book.getInt("pageCount")-start;
            SketchSequenceTimeline timeline=new SketchSequenceTimeline(book,start,pages);
            // No committed snapshot exists yet: only this operation's unfinished staging is removed.
            if(generation.exists())OpenPipelineArtifacts.deleteTree(generation);
            if(!generation.mkdirs())throw new IOException("Sequence snapshot storage unavailable");
            final long[] bytes={0};MessageDigest digest=MessageDigest.getInstance("SHA-256");
            try(SQLiteDatabase index=SQLiteDatabase.openOrCreateDatabase(indexFile,null)){
                try(Cursor mode=index.rawQuery("PRAGMA journal_mode=DELETE",null)){mode.moveToFirst();}index.execSQL("CREATE TABLE pages(page INTEGER PRIMARY KEY,document TEXT NOT NULL,sha TEXT NOT NULL,inventory TEXT)");
                try(SketchBookStore books=new SketchBookStore(c);SketchProjectStore sketches=new SketchProjectStore(c)){
                    sketches.beginSnapshot();index.beginTransaction();
                    try{
                        books.visitPages(id,start,pages,(page,sketchId)->{
                            cancelled();JSONObject drawing=sketches.get(sketchId);sketches.verifyReferences(drawing);
                            byte[] json=SketchDocument.canonical(drawing).getBytes(StandardCharsets.UTF_8);reserve(c,bytes,json.length);
                            ContentValues row=new ContentValues();row.put("page",page);row.put("document",new String(json,StandardCharsets.UTF_8));row.put("sha",SketchDocument.sha256(json));index.insertOrThrow("pages",null,row);
                            progress.onProgress(page-start,pages);
                        });index.setTransactionSuccessful();
                    }finally{index.endTransaction();sketches.endSnapshot();}
                }
                // Capture immutable dependencies off the source DB transaction. Only one cel is live.
                try(Cursor cursor=index.rawQuery("SELECT page,sha FROM pages ORDER BY page",null)){
                    while(cursor.moveToNext()){
                        cancelled();int page=cursor.getInt(0);JSONObject drawing=new JSONObject(SQLiteLargeText.read(index,"SELECT document FROM pages WHERE page=?",String.valueOf(page)));
                        JSONObject inventory=SketchPaintArchive.freezeDependencies(c,drawing,assets(generation,page));
                        reserve(c,bytes,inventory.getLong("bytes"));String json=SketchDocument.canonical(inventory);
                        ContentValues value=new ContentValues();value.put("inventory",json);index.update("pages",value,"page=?",new String[]{String.valueOf(page)});
                        updateDigest(digest,page+"\n"+cursor.getString(1)+"\n"+json+"\n");
                    }
                }
            }
            String hash=SketchDocument.sha256((SketchDocument.canonical(book)+"\n"+start+"\n"+pages+"\n"+hex(digest.digest())).getBytes(StandardCharsets.UTF_8));
            snapshot=new JSONObject().put("requestSha256",fingerprint).put("book",book).put("pageStart",start).put("pageCount",pages).put("frameCount",timeline.frames()).put("documentSha256",hash).put("indexSha256",SketchProjectStore.fileHash(indexFile)).put("snapshotBytes",bytes[0]);
            write(snapshotFile,snapshot);
        }
        JSONObject book=snapshot.getJSONObject("book");String projectId=UUID.nameUUIDFromBytes(("sketch-output/"+id+"/"+op).getBytes(StandardCharsets.UTF_8)).toString();
        ProjectStore projects=new ProjectStore(c);File output=new File(generation,"sketch.mp4"),receipt=new File(generation,"completed.json");
        SketchExportFrameSource.Factory factory=()->new FrozenPages(c,generation,snapshotFile);
        if(receipt.isFile()){
            JSONObject metadata=read(receipt,1024*1024);ProjectStore.Project project=projects.get(projectId);
            if(project==null||!output.isFile()||!metadata.getString("outputSha256").equals(SketchProjectStore.fileHash(output)))throw new IllegalStateException("SKETCH_EXPORT_OUTPUT_CHANGED");
            return new SketchNativeExport.Prepared(project,output,aspect,quality,metadata,true,generation,factory);
        }
        ProjectStore.Project project=projects.get(projectId);
        if(project==null){project=new ProjectStore.Project();project.id=projectId;project.name=book.getString("name")+" · sequence";projects.save(project);}
        else if(!project.assets.isEmpty()||!project.clips.isEmpty())throw new IllegalStateException("SKETCH_EXPORT_OUTPUT_PROJECT_CHANGED");
        JSONObject metadata=new JSONObject().put("sketchId",id).put("bookId",id).put("sketchRevision",revision).put("operationId",op).put("outputProjectId",projectId)
                .put("documentSha256",snapshot.getString("documentSha256")).put("pageStart",snapshot.getInt("pageStart")).put("pageCount",snapshot.getInt("pageCount"))
                .put("frameCount",snapshot.getInt("frameCount")).put("fps",book.getInt("fps")).put("width",book.getInt("width")).put("height",book.getInt("height"))
                .put("durationUs",NativeFrameClock.presentationTimeUs(snapshot.getInt("frameCount"),book.getInt("fps"))).put("renderer","android-bitmap-canvas")
                .put("frameSource","frozen-book-pages").put("snapshotBytes",snapshot.getLong("snapshotBytes")).put("rasterFramesCaptured",false).put("decodedFrameEvidenceAvailable",false).put("encoded",false);
        return new SketchNativeExport.Prepared(project,output,aspect,quality,metadata,false,generation,factory);
    }
    private static File assets(File root,int page){return new File(root,"cels/"+(page/1000)+"/"+page+"/assets");}
    private static void reserve(Context c,long[] total,long next)throws Exception {
        total[0]=Math.addExact(total[0],next);if(total[0]>SketchSequenceLimits.SNAPSHOT_BYTES||c.getFilesDir().getUsableSpace()<SketchSequenceLimits.STORAGE_RESERVE+next)throw new IllegalStateException("SKETCH_SEQUENCE_STORAGE: snapshot budget or free space exhausted; export a scene range");
    }
    private static void updateDigest(MessageDigest digest,String text){digest.update(text.getBytes(StandardCharsets.UTF_8));}
    private static String hex(byte[] digest){StringBuilder s=new StringBuilder();for(byte b:digest)s.append(String.format(Locale.ROOT,"%02x",b&255));return s.toString();}
    static void cancelled()throws InterruptedException {if(Thread.currentThread().isInterrupted())throw new InterruptedException("Sketch sequence cancelled");}
    static JSONObject read(File file,int max)throws Exception {if(!file.isFile()||file.length()>max)throw SketchDocument.bad("Sequence metadata budget exceeded");return SketchPaintArchive.parseOwnedJson(java.nio.file.Files.readAllBytes(file.toPath()));}
    static void write(File file,JSONObject value)throws Exception {android.util.AtomicFile target=new android.util.AtomicFile(file);FileOutputStream out=null;try{out=target.startWrite();byte[] bytes=value.toString().getBytes(StandardCharsets.UTF_8);if(bytes.length>16*1024*1024)throw SketchDocument.bad("Sequence metadata budget exceeded");out.write(bytes);target.finishWrite(out);}catch(Exception error){if(out!=null)target.failWrite(out);throw error;}}

    private static final class FrozenPages implements SketchExportFrameSource {
        final Context context;final File root;final SQLiteDatabase index;final JSONObject book;final SketchSequenceTimeline timeline;
        int loadedPage=-1;SketchFrameRenderer renderer;
        FrozenPages(Context c,File root,File snapshotFile)throws Exception {
            context=c;this.root=root;JSONObject snapshot=read(snapshotFile,16*1024*1024);File db=new File(root,"pages.db");
            if(!snapshot.getString("indexSha256").equals(SketchProjectStore.fileHash(db)))throw new IllegalStateException("SKETCH_BOOK_PAGE_CHANGED: frozen sequence index changed");
            book=snapshot.getJSONObject("book");timeline=new SketchSequenceTimeline(book,snapshot.getInt("pageStart"),snapshot.getInt("pageCount"));
            index=SQLiteDatabase.openDatabase(db.getPath(),null,SQLiteDatabase.OPEN_READONLY);
        }
        public Bitmap render(int frame,int width,int height)throws Exception {
            cancelled();int page=timeline.page(frame);
            if(renderer==null||loadedPage!=page){
                if(renderer!=null){renderer.close();renderer=null;}
                String json=SQLiteLargeText.read(index,"SELECT document FROM pages WHERE page=?",String.valueOf(page));JSONObject drawing;File dependencies=null;
                if(json==null)drawing=SketchDocument.create(new JSONObject().put("sketchId",book.getString("bookId")+"_blank").put("name",book.getString("name")).put("width",book.getInt("width")).put("height",book.getInt("height")).put("fps",book.getInt("fps")).put("background",book.getString("background")).put("frameCount",1));
                else{
                    String sha=SQLiteLargeText.read(index,"SELECT sha FROM pages WHERE page=?",String.valueOf(page));
                    if(!sha.equals(SketchDocument.sha256(json.getBytes(StandardCharsets.UTF_8))))throw new IllegalStateException("SKETCH_BOOK_PAGE_CHANGED");
                    drawing=new JSONObject(json);dependencies=assets(root,page);JSONObject inventory=new JSONObject(SQLiteLargeText.read(index,"SELECT inventory FROM pages WHERE page=?",String.valueOf(page)));
                    SketchPaintArchive.verifyFrozen(context,drawing,dependencies,inventory);
                }
                renderer=new SketchFrameRenderer(context,drawing,dependencies);loadedPage=page;
            }
            Bitmap bitmap=renderer.render(0);if(bitmap.getWidth()==width&&bitmap.getHeight()==height)return bitmap;
            return SketchExportFrameSource.fit(bitmap,width,height);
        }
        public void close(){if(renderer!=null){renderer.close();renderer=null;}index.close();}
    }
}
