package com.rezoxnemesis.videostudio;

import android.content.Context;
import android.util.AtomicFile;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;

/**
 * Bounded saved-programme sampling into ordinary owned book pages.
 * Pre-existing destinations fail the entire preflight. Publication across the two existing
 * databases is recoverable, not a cross-database transaction: a cancellation or late writer
 * can leave this operation's already-published pages. Exact import receipts permit resume;
 * neither a blank human page nor later human edits are ever replaced.
 */
public final class SketchAnimationBookPlacement {
    public interface Progress {void placed(int completed,int total)throws Exception;}
    private static final Object LOCK=new Object();
    private static final int INTENT_BYTES=SketchDocument.MAX_BYTES+1024*1024;
    private static final long STAGE_BYTES=272L*1024*1024,RESERVE=128L*1024*1024;
    private static final String SCHEMA="videostudio.sketch-animation-book-placement.v1";
    private SketchAnimationBookPlacement(){}

    public static JSONObject place(Context context,JSONObject args,Progress progress)throws Exception {
        if(context==null||args==null)throw SketchDocument.bad("Animation placement context and arguments are required");
        synchronized(LOCK){return run(context.getApplicationContext(),SketchDocument.copy(args),progress);}
    }

    private static JSONObject run(Context context,JSONObject args,Progress progress)throws Exception {
        validate(args);cancelled();String fingerprint=hash(args),bookId=args.getString("bookId"),operation=args.getString("operationId");
        String key=SketchDocument.sha256((bookId+"\n"+operation).getBytes(StandardCharsets.UTF_8));
        File root=new File(context.getFilesDir(),"sketch_animation_book_placements/"+key);
        File requestFile=new File(root,"request.json"),intentFile=new File(root,"intent.json"),resultFile=new File(root,"completed.json");
        // Replay identity and completed receipts before consulting any mutable live revision.
        if(present(requestFile)){
            JSONObject request=read(requestFile,64*1024);
            if(!fingerprint.equals(request.getString("requestSha256")))throw new IllegalStateException("SKETCH_ANIMATION_BOOK_OPERATION_ID_CONFLICT");
            if(present(resultFile)){
                JSONObject result=read(resultFile,128*1024);
                if(!SCHEMA.equals(result.getString("schema"))||!fingerprint.equals(result.getString("requestSha256")))throw new IllegalStateException("SKETCH_ANIMATION_BOOK_RECEIPT_CHANGED");
                return result;
            }
        }
        JSONObject intent;
        try(SketchProjectStore sketches=new SketchProjectStore(context);SketchBookStore books=new SketchBookStore(context)){
            if(present(intentFile)){
                intent=read(intentFile,INTENT_BYTES);verifyIntent(intent,args,fingerprint);
            }else{
                JSONObject saved=SketchAnimationOps.load(context,args.getString("animationId"));
                if(saved.getLong("revision")!=args.getLong("expectedAnimationRevision"))throw new IllegalStateException("SKETCH_ANIMATION_REVISION_CONFLICT");
                JSONObject book=books.get(bookId);
                requireRevision(book,args.getLong("expectedBookRevision"));requireClock(book,saved,args);
                preflight(sketches,books,args,null,null);
                if(!root.isDirectory()&&!root.mkdirs())throw new IllegalStateException("SKETCH_ANIMATION_BOOK_STORAGE: cannot create staging directory");
                if(!present(requestFile))write(context,requestFile,new JSONObject().put("requestSha256",fingerprint));
                JSONObject source=saved.getJSONObject("sourceDocument"),program=saved.getJSONObject("program");
                SketchAnimationCompiler.Sampler sampler=SketchAnimationCompiler.prepare(source,program);
                JSONArray frames=new JSONArray(),imports=new JSONArray();long bytes=source.toString().getBytes(StandardCharsets.UTF_8).length;
                for(int i=0;i<args.getInt("frameCount");i++){
                    cancelled();
                    // Hash the JSON representation that will actually be persisted. JSONObject
                    // serializes integral doubles (e.g. a zero-degree pose) as integers; hashing
                    // the pre-roundtrip Java Number types would falsely report tampering.
                    JSONObject cel=SketchDocument.copy(sampler.preview(args.getInt("sourceFrameStart")+i,celId(args,i)).getJSONObject("document"));
                    bytes+=cel.toString().getBytes(StandardCharsets.UTF_8).length;
                    if(bytes>STAGE_BYTES)throw new IllegalStateException("SKETCH_ANIMATION_BOOK_STORAGE: shorten this batch");
                    write(context,frameFile(root,i),cel);frames.put(hash(cel));imports.put(importFingerprint(cel));
                }
                intent=new JSONObject().put("schema",SCHEMA).put("requestSha256",fingerprint).put("request",args)
                        .put("sourceDocument",source).put("sourceSha256",saved.getString("sourceSha256"))
                        .put("programSha256",saved.getString("programSha256")).put("fps",saved.getInt("fps"))
                        .put("width",saved.getInt("width")).put("height",saved.getInt("height"))
                        .put("animationRevision",saved.getLong("revision")).put("frameHashes",frames).put("importFingerprints",imports)
                        .put("sourceFrameCount",program.getInt("frameCount"));
                requireRevision(books.get(bookId),args.getLong("expectedBookRevision"));preflight(sketches,books,args,null,null);
                cancelled();write(context,intentFile,intent);
            }
            verifyIntent(intent,args,fingerprint);requireClock(books.get(bookId),intent,args);
            // Validate EVERY frozen cel and collision before claiming the revision or publishing.
            for(int i=0;i<args.getInt("frameCount");i++)staged(root,intent,args,i);
            String importOperation="animation_book_"+fingerprint;
            preflight(sketches,books,args,intent,importOperation);
            cancelled();
            JSONObject claim=SketchBookStore.execute(context,"sketch_book_edit",new JSONObject().put("bookId",bookId)
                    .put("expectedRevision",args.getLong("expectedBookRevision")).put("operationId","animation_place_"+fingerprint));
            long bookRevision=claim.getJSONObject("book").getLong("revision");
            requireRevision(books.get(bookId),bookRevision);requireClock(books.get(bookId),intent,args);
            // Stage owned dependency bytes for the entire range before importing documents.
            for(int i=0;i<args.getInt("frameCount");i++){
                cancelled();JSONObject cel=staged(root,intent,args,i);String id=celId(args,i);
                if(sketches.operationReceipt(id,importOperation,importFingerprint(cel))!=null){
                    if(sketches.revision(id)<1)throw new IllegalStateException("SKETCH_ANIMATION_BOOK_PUBLISHED_PAGE_MISSING");
                    continue;
                }
                JSONObject selected=SketchDocument.copy(cel).put("id",intent.getJSONObject("sourceDocument").getString("id"));
                sketches.copyOwnedDependencies(selected,id);sketches.verifyReferences(cel);
            }
            preflight(sketches,books,args,intent,importOperation);
            JSONArray pages=new JSONArray();
            for(int i=0;i<args.getInt("frameCount");i++){
                cancelled();requireRevision(books.get(bookId),bookRevision);
                JSONObject cel=staged(root,intent,args,i);String id=celId(args,i);
                JSONObject own=sketches.operationReceipt(id,importOperation,importFingerprint(cel));
                if(own!=null&&sketches.revision(id)<1)throw new IllegalStateException("SKETCH_ANIMATION_BOOK_PUBLISHED_PAGE_MISSING");
                if(own==null){
                    if(sketches.revision(id)>=0)throw occupied(id);
                    try{sketches.importOwnedDocument(cel,importOperation);}
                    catch(IllegalStateException collision){if(collision.getMessage()!=null&&collision.getMessage().contains("SKETCH_ALREADY_EXISTS"))throw occupied(id);throw collision;}
                }
                cancelled();requireRevision(books.get(bookId),bookRevision);
                books.attachImportedPage(bookId,args.getInt("destinationPageStart")+i,id);
                pages.put(new JSONObject().put("page",args.getInt("destinationPageStart")+i).put("sketchId",id)
                        .put("sourceFrame",args.getInt("sourceFrameStart")+i).put("publishedDocumentSha256",intent.getJSONArray("frameHashes").getString(i)));
                if(progress!=null)progress.placed(i+1,args.getInt("frameCount"));
            }
            cancelled();requireRevision(books.get(bookId),bookRevision);
            JSONObject result=new JSONObject().put("schema",SCHEMA).put("animationId",args.getString("animationId"))
                    .put("animationRevision",intent.getLong("animationRevision")).put("sourceSha256",intent.getString("sourceSha256"))
                    .put("programSha256",intent.getString("programSha256")).put("requestSha256",fingerprint).put("operationId",operation)
                    .put("bookId",bookId).put("bookRevision",bookRevision).put("sourceFrameStart",args.getInt("sourceFrameStart"))
                    .put("destinationPageStart",args.getInt("destinationPageStart")).put("frameCount",args.getInt("frameCount"))
                    .put("fps",intent.getInt("fps")).put("pages",pages).put("editable",true).put("audioMixedIntoSketch",false)
                    .put("publication","recoverable_owned_cels").put("filmQualityCertified",false);
            write(context,resultFile,result);return result;
        }
    }

    private static void validate(JSONObject args)throws Exception {
        SketchDocument.fields(args,"animationId,expectedAnimationRevision,bookId,expectedBookRevision,sourceFrameStart,frameCount,destinationPageStart,operationId");
        SketchDocument.id(args,"animationId");SketchDocument.id(args,"operationId");
        if(SketchDocument.id(args,"bookId").length()>72)throw SketchDocument.bad("Book ID is limited to 72 characters");
        SketchDocument.integer(args,"expectedAnimationRevision",1,Long.MAX_VALUE-1);SketchDocument.integer(args,"expectedBookRevision",1,Long.MAX_VALUE-2);
        SketchDocument.integer(args,"sourceFrameStart",0,1199);SketchDocument.integer(args,"frameCount",1,64);SketchDocument.integer(args,"destinationPageStart",0,999999);
    }
    private static void requireRevision(JSONObject book,long expected)throws Exception {
        if(book.getLong("revision")!=expected)throw new IllegalStateException("SKETCH_BOOK_REVISION_CONFLICT");
    }
    private static void requireClock(JSONObject book,JSONObject saved,JSONObject args)throws Exception {
        int frames=saved.has("program")?saved.getJSONObject("program").getInt("frameCount"):saved.getInt("sourceFrameCount");
        if((long)args.getInt("sourceFrameStart")+args.getInt("frameCount")>frames||(long)args.getInt("destinationPageStart")+args.getInt("frameCount")>book.getInt("pageCount"))throw SketchDocument.bad("Placement range outside source animation or destination book");
        if(saved.getInt("fps")!=book.getInt("fps")||saved.getInt("width")!=book.getInt("width")||saved.getInt("height")!=book.getInt("height"))throw SketchDocument.bad("SKETCH_ANIMATION_BOOK_TIMING_OR_CANVAS: source and book must match exactly");
        for(int i=0;i<args.getInt("frameCount");i++)if(SketchTimelineTools.exposure(book.optJSONObject("exposures"),args.getInt("destinationPageStart")+i)!=1)throw SketchDocument.bad("SKETCH_ANIMATION_BOOK_EXPOSURE: destination pages must have one-frame exposures");
    }
    private static void preflight(SketchProjectStore sketches,SketchBookStore books,JSONObject args,JSONObject intent,String operation)throws Exception {
        int start=args.getInt("destinationPageStart"),count=args.getInt("frameCount");
        // Includes orphan page documents and deliberately blank human pages.
        for(int i=0;i<count;i++){
            cancelled();String id=celId(args,i);
            if(sketches.revision(id)>=0&&!owned(sketches,intent,operation,args,i))throw occupied(id);
        }
        books.visitPages(args.getString("bookId"),start,count,(page,id)->{
            if(!id.equals(celId(args,page-start))||!owned(sketches,intent,operation,args,page-start))throw occupied(id);
        });
    }
    private static boolean owned(SketchProjectStore sketches,JSONObject intent,String operation,JSONObject args,int offset)throws Exception {
        if(intent==null)return false;
        String documentHash=intent.getJSONArray("frameHashes").getString(offset);
        // Import fingerprints bind the full canonical drawing, not merely its claimed hash.
        String fingerprint=intent.getJSONArray("importFingerprints").getString(offset);
        return documentHash.matches("[a-f0-9]{64}")&&sketches.revision(celId(args,offset))>=1&&sketches.operationReceipt(celId(args,offset),operation,fingerprint)!=null;
    }
    private static JSONObject staged(File root,JSONObject intent,JSONObject args,int offset)throws Exception {
        cancelled();JSONObject cel=read(frameFile(root,offset),SketchDocument.MAX_BYTES);SketchDocument.validate(cel);
        if(!celId(args,offset).equals(cel.getString("id"))||cel.getLong("revision")!=1||cel.getInt("frameCount")!=1
                ||cel.getInt("fps")!=intent.getInt("fps")||cel.getInt("width")!=intent.getInt("width")||cel.getInt("height")!=intent.getInt("height")
                ||!hash(cel).equals(intent.getJSONArray("frameHashes").getString(offset))
                ||!importFingerprint(cel).equals(intent.getJSONArray("importFingerprints").getString(offset)))throw new IllegalStateException("SKETCH_ANIMATION_BOOK_STAGED_OUTPUT_CHANGED");
        return cel;
    }
    private static void verifyIntent(JSONObject intent,JSONObject args,String fingerprint)throws Exception {
        if(!SCHEMA.equals(intent.getString("schema"))||!fingerprint.equals(intent.getString("requestSha256"))||!fingerprint.equals(hash(intent.getJSONObject("request")))
                ||intent.getLong("animationRevision")!=args.getLong("expectedAnimationRevision")||!hash(intent.getJSONObject("sourceDocument")).equals(intent.getString("sourceSha256"))
                ||intent.getJSONArray("frameHashes").length()!=args.getInt("frameCount")||intent.getJSONArray("importFingerprints").length()!=args.getInt("frameCount"))throw new IllegalStateException("SKETCH_ANIMATION_BOOK_FROZEN_SOURCE_CHANGED");
    }
    private static String celId(JSONObject args,int offset)throws Exception{return args.getString("bookId")+"_p"+(args.getInt("destinationPageStart")+offset);}
    private static File frameFile(File root,int frame){return new File(root,"frame_"+frame+".json");}
    private static String hash(JSONObject value)throws Exception{return SketchDocument.sha256(SketchDocument.canonical(value).getBytes(StandardCharsets.UTF_8));}
    private static String importFingerprint(JSONObject cel)throws Exception{return SketchDocument.sha256(("sketch_owned_import\n"+SketchDocument.canonical(cel)).getBytes(StandardCharsets.UTF_8));}
    private static IllegalStateException occupied(String id){return new IllegalStateException("SKETCH_ANIMATION_BOOK_PAGE_OCCUPIED: "+id);}
    private static void cancelled()throws InterruptedException {if(Thread.currentThread().isInterrupted())throw new InterruptedException("Animation book placement cancelled; retry the same request to resume");}
    private static boolean present(File file){return file.isFile()||new File(file.getPath()+".bak").isFile();}
    private static JSONObject read(File file,int limit)throws Exception {
        AtomicFile atomic=new AtomicFile(file);try(FileInputStream in=atomic.openRead();ByteArrayOutputStream bytes=new ByteArrayOutputStream()){
            byte[] buffer=new byte[32768];int count;while((count=in.read(buffer))!=-1){cancelled();if(bytes.size()+count>limit)throw SketchDocument.bad("Placement metadata budget exceeded");bytes.write(buffer,0,count);}return SketchPaintArchive.parseOwnedJson(bytes.toByteArray());
        }
    }
    private static void write(Context context,File file,JSONObject value)throws Exception {
        cancelled();byte[] bytes=value.toString().getBytes(StandardCharsets.UTF_8);
        if(bytes.length>INTENT_BYTES||context.getFilesDir().getUsableSpace()<RESERVE+3L*bytes.length)throw new IllegalStateException("SKETCH_ANIMATION_BOOK_STORAGE: staging reserve exhausted");
        AtomicFile target=new AtomicFile(file);FileOutputStream out=null;
        try{out=target.startWrite();out.write(bytes);target.finishWrite(out);}catch(Exception failure){if(out!=null)target.failWrite(out);throw failure;}
    }
}
