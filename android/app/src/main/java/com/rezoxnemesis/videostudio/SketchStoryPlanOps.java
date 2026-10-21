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
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * Product-owned semantic story authoring; dispatch and manual controls live outside this sidecar.
 * sketch_story_create accepts {bookId,operationId,name,width,height,fps,shots:[
 * {shotId,frameCount,operations:[existing SketchEditOps operations],camera?:[],label?:string}]}.
 * A fresh book identity is required. All shots pass native semantic validation before any
 * book, source sketch or saved animation is published. Private journal files freeze that plan.
 * Publication uses separate existing stores and can retain owned partial output on cancellation
 * or a late conflict. Retry the identical request: completed batch receipts retain their original
 * revisions and preserve human cel edits; edited prepared sources stop an incomplete plan.
 * Generated dialogue references use SketchSpeechAssets, including explicit shotStartMs when
 * rebasing a master. This authors speech motion only; the existing project audio lane owns sound.
 */
public final class SketchStoryPlanOps {
    public interface Progress {void saved(int completed,int total)throws Exception;}
    private static final Object LOCK=new Object();
    private static final String SCHEMA="videostudio.sketch-story-plan.v1";
    private static final int PLAN_BYTES=2*1024*1024,SHOT_BYTES=SketchDocument.MAX_BYTES+SketchAnimationCompiler.MAX_PROGRAM_BYTES+128*1024;
    private static final long STAGE_BYTES=384L*1024*1024,RESERVE=128L*1024*1024;
    private SketchStoryPlanOps(){}
    public static JSONObject execute(Context context,String action,JSONObject args)throws Exception {
        if("sketch_story_example".equals(action)){
            if(args==null)throw SketchDocument.bad("Story example arguments are required");SketchDocument.fields(args,"exampleId");
            if(!"last_lantern".equals(args.getString("exampleId")))throw SketchDocument.bad("Unknown story example");return example(context);
        }
        if("sketch_story_preview".equals(action)){
            if(args==null)throw SketchDocument.bad("Story preview arguments are required");SketchDocument.fields(args,"plan,shotId,frame");
            return preview(context,args.getJSONObject("plan"),SketchDocument.id(args,"shotId"),(int)SketchDocument.integer(args,"frame",0,1199));
        }
        if(!"sketch_story_create".equals(action))throw SketchDocument.bad("Unknown story action");return create(context,args,null);
    }
    /** Explicit asset fetch only: fresh IDs, complete editable plan/phrases, no stores or journals. */
    public static JSONObject example(Context context)throws Exception {
        if(context==null)throw SketchDocument.bad("Story context is required");cancelled();
        JSONObject plan=checkedPlan(context,asset(context,"the-last-lantern.json"));validate(plan);
        JSONObject dialogue=DialogueControls.validate(asset(context,"the-last-lantern-dialogue-controls.json"));
        String token=UUID.randomUUID().toString().replace("-","");plan.put("bookId","last_lantern_"+token).put("operationId","author_"+token);
        return new JSONObject().put("exampleId","last_lantern").put("plan",plan).put("dialogueControls",dialogue)
                .put("audioSettings",SketchStoryAudio.validateSettings(asset(context,"the-last-lantern-audio-settings.json"),dialogue.getLong("durationMs")*24))
                .put("readOnly",true).put("audioEmbedded",false).put("dialogueGenerationNeedsConfiguredRunner",true);
    }
    private static JSONObject asset(Context context,String name)throws Exception {
        try(java.io.InputStream in=context.getAssets().open("animation_stories/"+name);ByteArrayOutputStream bytes=new ByteArrayOutputStream()){
            byte[] buffer=new byte[32768];int count;while((count=in.read(buffer))!=-1){cancelled();if(bytes.size()+count>PLAN_BYTES)throw SketchDocument.bad("Story example exceeds 2 MiB");bytes.write(buffer,0,count);}
            return SketchPaintArchive.parseOwnedJson(bytes.toByteArray());
        }
    }
    /** Detached validated source/programme for repeated native sampling; never publishes or journals. */
    public static JSONObject preparePreview(Context context,JSONObject plan,String shotId)throws Exception {
        JSONObject args=checkedPlan(context,plan);validate(args);cancelled();
        SketchDocument.id(new JSONObject().put("shotId",shotId),"shotId");JSONArray shots=args.getJSONArray("shots");
        for(int i=0;i<shots.length();i++)if(shotId.equals(shots.getJSONObject(i).getString("shotId"))){
            JSONObject shot=shots.getJSONObject(i),snapshot=prepareShot(context.getApplicationContext(),args,shot);
            return snapshot.put("schema","videostudio.sketch-story-preview.v1").put("shotId",shotId).put("requestSha256",hash(args))
                    .put("frameCount",shot.getInt("frameCount")).put("fps",args.getInt("fps")).put("width",args.getInt("width")).put("height",args.getInt("height"))
                    .put("sourceSha256",hash(snapshot.getJSONObject("sourceDocument"))).put("programSha256",hash(snapshot.getJSONObject("program"))).put("readOnly",true);
        }
        throw SketchDocument.bad("Unknown story shot: "+shotId);
    }
    /** Convenience one-frame preview. Call preparePreview once per shot for full-frame reviews. */
    public static JSONObject preview(Context context,JSONObject args,String shotId,int frame)throws Exception {
        JSONObject prepared=preparePreview(context,args,shotId);
        JSONObject result=SketchAnimationCompiler.preview(prepared.getJSONObject("sourceDocument"),prepared.getJSONObject("program"),frame,prepared.getJSONObject("sourceDocument").getString("id")+"_preview");
        return result.put("shotId",shotId).put("frame",frame).put("requestSha256",prepared.getString("requestSha256")).put("readOnly",true)
                .put("audioMixedIntoSketch",false).put("filmQualityCertified",false);
    }
    public static JSONObject create(Context context,JSONObject args,Progress progress)throws Exception {
        JSONObject frozen=checkedPlan(context,args);synchronized(LOCK){return run(context.getApplicationContext(),frozen,progress);}
    }
    private static JSONObject checkedPlan(Context context,JSONObject args)throws Exception {
        if(context==null||args==null)throw SketchDocument.bad("Story context and plan are required");
        if(args.toString().getBytes(StandardCharsets.UTF_8).length>PLAN_BYTES)throw SketchDocument.bad("Story plan exceeds 2 MiB");return SketchDocument.copy(args);
    }
    private static JSONObject run(Context context,JSONObject args,Progress progress)throws Exception {
        int total=validate(args);cancelled();String fingerprint=hash(args),bookId=args.getString("bookId");
        String identity=SketchDocument.sha256((bookId+"\n"+args.getString("operationId")).getBytes(StandardCharsets.UTF_8));
        File root=new File(context.getFilesDir(),"sketch_story_plans/"+identity),requestFile=new File(root,"request.json"),intentFile=new File(root,"intent.json"),completedFile=new File(root,"completed.json");
        if(present(requestFile)){
            JSONObject request=read(requestFile,64*1024);if(!fingerprint.equals(request.getString("requestSha256")))throw new IllegalStateException("SKETCH_STORY_OPERATION_ID_CONFLICT");
            if(present(completedFile)){
                JSONObject receipt=read(completedFile,256*1024);if(!SCHEMA.equals(receipt.getString("schema"))||!fingerprint.equals(receipt.getString("requestSha256")))throw new IllegalStateException("SKETCH_STORY_RECEIPT_CHANGED");return receipt;
            }
        }
        JSONObject intent;
        try(SketchProjectStore sketches=new SketchProjectStore(context);SketchBookStore books=new SketchBookStore(context)){
            if(present(intentFile))intent=read(intentFile,256*1024);
            else {
                requireNewBook(books,bookId);requireUnusedSources(context,sketches,args);
                // Check orphan documents too; a blank page owned by somebody else is still occupied.
                for(int page=0;page<total;page++){cancelled();if(sketches.revision(bookId+"_p"+page)>=0)throw new IllegalStateException("SKETCH_STORY_PAGE_OCCUPIED: "+page);}
                if(!root.isDirectory()&&!root.mkdirs())throw new IllegalStateException("SKETCH_STORY_STORAGE: staging unavailable");
                if(!present(requestFile))write(context,requestFile,new JSONObject().put("schema",SCHEMA).put("requestSha256",fingerprint));
                JSONArray index=new JSONArray();long bytes=0;int offset=0;
                for(int i=0;i<args.getJSONArray("shots").length();i++){
                    cancelled();JSONObject shot=args.getJSONArray("shots").getJSONObject(i),snapshot=prepareShot(context,args,shot),source=snapshot.getJSONObject("sourceDocument");
                    bytes+=snapshot.toString().getBytes(StandardCharsets.UTF_8).length;if(bytes>STAGE_BYTES)throw new IllegalStateException("SKETCH_STORY_STORAGE: split this plan into smaller books");
                    write(context,shotFile(root,i),snapshot);
                    index.put(new JSONObject().put("shotId",shot.getString("shotId")).put("sourceSketchId",source.getString("id"))
                            .put("animationId",animationId(args,shot)).put("frameCount",shot.getInt("frameCount")).put("pageStart",offset)
                            .put("label",shot.optString("label",shot.getString("shotId"))).put("snapshotSha256",hash(snapshot))
                            .put("sourceSha256",hash(snapshot.getJSONObject("sourceDocument"))).put("programSha256",hash(snapshot.getJSONObject("program"))));
                    offset+=shot.getInt("frameCount");
                }
                // No book/source/programme was published during full-plan semantic preflight.
                requireNewBook(books,bookId);requireUnusedSources(context,sketches,args);
                intent=new JSONObject().put("schema",SCHEMA).put("requestSha256",fingerprint).put("frameCount",total).put("shots",index);
                cancelled();write(context,intentFile,intent);
            }
            verifyIntent(intent,args,fingerprint,total);
            String sourceOperation="story_source_"+fingerprint;
            // Verify every frozen source, including earlier shots, before resuming any publication.
            for(int i=0;i<intent.getJSONArray("shots").length();i++){
                JSONObject meta=intent.getJSONArray("shots").getJSONObject(i),snapshot=staged(root,i,meta,args);
                requireSource(sketches,snapshot.getJSONObject("sourceDocument"),sourceOperation,false);
            }
            JSONObject createBook=new JSONObject().put("bookId",bookId).put("operationId","story_book_"+fingerprint).put("name",args.getString("name"))
                    .put("pageCount",total).put("width",args.getInt("width")).put("height",args.getInt("height")).put("fps",args.getInt("fps")).put("background","#FF111827");
            cancelled();SketchBookStore.execute(context,"sketch_book_create",createBook);
            JSONArray cues=new JSONArray();for(int i=0;i<intent.getJSONArray("shots").length();i++){
                JSONObject shot=intent.getJSONArray("shots").getJSONObject(i);cues.put(new JSONObject().put("id",shot.getString("shotId")).put("type","shot")
                        .put("startFrame",shot.getInt("pageStart")).put("endFrame",shot.getInt("pageStart")+shot.getInt("frameCount")-1).put("label",shot.getString("label")));
            }
            JSONObject cueReceipt=SketchBookStore.execute(context,"sketch_book_edit",new JSONObject().put("bookId",bookId).put("expectedRevision",1)
                    .put("operationId","story_cues_"+fingerprint).put("cues",cues));
            long expectedBook=cueReceipt.getJSONObject("book").getLong("revision");
            int batchNumber=0;JSONArray resultShots=new JSONArray();
            for(int i=0;i<intent.getJSONArray("shots").length();i++){
                cancelled();JSONObject meta=intent.getJSONArray("shots").getJSONObject(i),snapshot=staged(root,i,meta,args),source=snapshot.getJSONObject("sourceDocument"),program=snapshot.getJSONObject("program");
                if(!requireSource(sketches,source,sourceOperation,false))sketches.importOwnedDocument(source,sourceOperation);
                requireSource(sketches,source,sourceOperation,true);
                JSONObject saveArgs=new JSONObject().put("animationId",meta.getString("animationId")).put("sketchId",source.getString("id")).put("sourceRevision",1)
                        .put("expectedRevision",0).put("operationId","story_save_"+fingerprint).put("program",program);
                JSONObject saved=SketchAnimationOps.execute(context,"sketch_animation_save",saveArgs);
                requireAnimation(context,meta,saved.getLong("revision"));
                for(int start=0;start<meta.getInt("frameCount");start+=64){
                    cancelled();requireSource(sketches,source,sourceOperation,true);requireAnimation(context,meta,saved.getLong("revision"));
                    final int global=meta.getInt("pageStart")+start;
                    JSONObject placement=new JSONObject().put("animationId",meta.getString("animationId")).put("expectedAnimationRevision",saved.getLong("revision"))
                            .put("bookId",bookId).put("expectedBookRevision",expectedBook).put("sourceFrameStart",start).put("frameCount",Math.min(64,meta.getInt("frameCount")-start))
                            .put("destinationPageStart",global).put("operationId","story_batch_"+fingerprint+"_"+batchNumber);
                    JSONObject placed=SketchAnimationBookPlacement.place(context,placement,progress==null?null:(done,count)->progress.saved(global+done,total));
                    // Use the exact retained receipt, never rebase a retry onto the current live book.
                    expectedBook=placed.getLong("bookRevision");batchNumber++;
                }
                resultShots.put(SketchDocument.copy(meta).put("animationRevision",saved.getLong("revision")));
            }
            cancelled();if(books.get(bookId).getLong("revision")!=expectedBook)throw new IllegalStateException("SKETCH_BOOK_REVISION_CONFLICT");
            JSONObject result=new JSONObject().put("schema",SCHEMA).put("requestSha256",fingerprint).put("bookId",bookId).put("operationId",args.getString("operationId"))
                    .put("bookRevision",expectedBook).put("frameCount",total).put("fps",args.getInt("fps")).put("width",args.getInt("width")).put("height",args.getInt("height"))
                    .put("durationUs",NativeFrameClock.presentationTimeUs(total,args.getInt("fps"))).put("shots",resultShots).put("placementBatches",batchNumber)
                    .put("editable",true).put("audioMixedIntoSketch",false).put("filmQualityCertified",false).put("publication","recoverable_owned_native_shots");
            write(context,completedFile,result);return result;
        }
    }
    private static int validate(JSONObject args)throws Exception {
        SketchDocument.fields(args,"bookId,operationId,name,width,height,fps,shots");if(SketchDocument.id(args,"bookId").length()>48)throw SketchDocument.bad("Story bookId must fit 48 characters");
        SketchDocument.id(args,"operationId");SketchDocument.text(args,"name",160);int fps=(int)SketchDocument.integer(args,"fps",24,60);NativeFrameClock.requireFps(fps);
        // Native document validation owns canvas limits and even dimensions.
        SketchDocument.create(new JSONObject().put("sketchId","story_canvas_check").put("width",args.get("width")).put("height",args.get("height")).put("fps",fps));
        JSONArray shots=args.getJSONArray("shots");if(shots.length()<1||shots.length()>64)throw SketchDocument.bad("Story requires 1..64 shots");Set<String> ids=new HashSet<>();long total=0;
        for(int i=0;i<shots.length();i++){
            JSONObject shot=shots.getJSONObject(i);SketchDocument.fields(shot,"shotId,frameCount,operations,camera,label");String id=SketchDocument.id(shot,"shotId");if(id.length()>20||!ids.add(id))throw SketchDocument.bad("Shot IDs must be unique and fit 20 characters");
            total+=SketchDocument.integer(shot,"frameCount",24,1200);JSONArray operations=shot.getJSONArray("operations");if(operations.length()<1||operations.length()>128)throw SketchDocument.bad("Each shot requires 1..128 native operations");
            if(shot.has("label"))SketchDocument.text(shot,"label",512);if(shot.has("camera"))shot.getJSONArray("camera");
        }
        if(total>SketchSequenceLimits.MAX_FRAMES)throw SketchDocument.bad("Story exceeds 1,000,000 output frames");return (int)total;
    }
    private static JSONObject author(Context context,JSONObject args,JSONObject shot)throws Exception {
        JSONObject doc=SketchDocument.create(new JSONObject().put("sketchId",sourceId(args,shot)).put("name",name(args,shot))
                .put("width",args.getInt("width")).put("height",args.getInt("height")).put("fps",args.getInt("fps")).put("frameCount",shot.getInt("frameCount")).put("background","#FF111827"));
        JSONArray operations=shot.getJSONArray("operations");for(int n=0;n<operations.length();n++){cancelled();SketchEditOps.apply(doc,SketchSpeechAssets.expand(context,doc,operations.getJSONObject(n)));}
        requireClock(doc,args,shot);SketchDocument.validate(doc);
        JSONArray layers=doc.getJSONArray("layers");for(int l=0;l<layers.length();l++){JSONArray objects=layers.getJSONObject(l).getJSONArray("objects");for(int i=0;i<objects.length();i++){
            String kind=objects.getJSONObject(i).getString("kind");if(kind.equals("image")||kind.equals("paint"))throw SketchDocument.bad("STORY_CONTEXT_ONLY_ARTWORK: owned pixel imports/paint require their native import surfaces; this plan authors native geometry");
        }}
        return SketchDocument.copy(doc);
    }
    private static JSONObject prepareShot(Context context,JSONObject args,JSONObject shot)throws Exception {
        cancelled();JSONObject source=author(context,args,shot),program=program(source,shot);
        SketchAnimationCompiler.compile(source,program,source.getString("id")+"_check");
        return SketchDocument.copy(new JSONObject().put("sourceDocument",source).put("program",program));
    }
    private static JSONObject program(JSONObject source,JSONObject shot)throws Exception {
        JSONObject program=new JSONObject().put("schema",SketchAnimationCompiler.SCHEMA).put("name",source.getString("name")).put("frameCount",source.getInt("frameCount")).put("fps",source.getInt("fps")).put("rigs",new JSONArray());
        if(shot.has("camera")){
            JSONArray ids=new JSONArray(),layers=source.getJSONArray("layers");for(int l=0;l<layers.length();l++)ids.put(layers.getJSONObject(l).getString("id"));
            program.put("camera",new JSONArray(shot.getJSONArray("camera").toString())).put("cameraLayerIds",ids);
        }
        return program;
    }
    private static void requireClock(JSONObject source,JSONObject args,JSONObject shot)throws Exception {
        if(source.getInt("width")!=args.getInt("width")||source.getInt("height")!=args.getInt("height")||source.getInt("fps")!=args.getInt("fps")||source.getInt("frameCount")!=shot.getInt("frameCount"))throw SketchDocument.bad("STORY_CLOCK_OR_CANVAS: shot operations must preserve the declared book clock and canvas");
    }
    private static void requireNewBook(SketchBookStore books,String id)throws Exception {
        try{books.get(id);}catch(IllegalArgumentException missing){if(missing.getMessage()!=null&&missing.getMessage().contains("Unknown sketch book"))return;throw missing;}
        throw new IllegalStateException("SKETCH_STORY_BOOK_EXISTS: choose a fresh bookId");
    }
    private static void requireUnusedSources(Context context,SketchProjectStore sketches,JSONObject args)throws Exception {
        JSONArray existing=SketchAnimationOps.execute(context,"sketch_animation_list",new JSONObject()).getJSONArray("animations");
        if(existing.length()+args.getJSONArray("shots").length()>128)throw new IllegalStateException("SKETCH_STORY_ANIMATION_STORAGE: saved programme count would exceed 128");
        for(int i=0;i<args.getJSONArray("shots").length();i++){
            JSONObject shot=args.getJSONArray("shots").getJSONObject(i);if(sketches.revision(sourceId(args,shot))>=0)throw new IllegalStateException("SKETCH_STORY_SOURCE_OCCUPIED");
            for(int a=0;a<existing.length();a++)if(animationId(args,shot).equals(existing.getJSONObject(a).getString("animationId")))throw new IllegalStateException("SKETCH_STORY_ANIMATION_OCCUPIED");
        }
    }
    private static boolean requireSource(SketchProjectStore sketches,JSONObject source,String operation,boolean required)throws Exception {
        String id=source.getString("id");long revision=sketches.revision(id);
        if(revision<0){if(required)throw new IllegalStateException("SKETCH_STORY_SOURCE_CHANGED: source disappeared");return false;}
        String fingerprint=SketchDocument.sha256(("sketch_owned_import\n"+SketchDocument.canonical(source)).getBytes(StandardCharsets.UTF_8));
        if(sketches.operationReceipt(id,operation,fingerprint)==null)throw new IllegalStateException("SKETCH_STORY_SOURCE_OCCUPIED");
        if(revision!=1||!hash(source).equals(hash(sketches.get(id))))throw new IllegalStateException("SKETCH_STORY_SOURCE_CHANGED: prepared artwork was edited; retain it and use a new plan identity");return true;
    }
    private static void requireAnimation(Context context,JSONObject meta,long revision)throws Exception {
        JSONObject current=SketchAnimationOps.load(context,meta.getString("animationId"));
        if(current.getLong("revision")!=revision||!current.getString("sourceSha256").equals(meta.getString("sourceSha256"))||!current.getString("programSha256").equals(meta.getString("programSha256")))throw new IllegalStateException("SKETCH_STORY_ANIMATION_CHANGED");
    }
    private static void verifyIntent(JSONObject intent,JSONObject args,String fingerprint,int total)throws Exception {
        if(!SCHEMA.equals(intent.getString("schema"))||!fingerprint.equals(intent.getString("requestSha256"))||intent.getInt("frameCount")!=total||intent.getJSONArray("shots").length()!=args.getJSONArray("shots").length())throw new IllegalStateException("SKETCH_STORY_INTENT_CHANGED");
        int offset=0;for(int i=0;i<intent.getJSONArray("shots").length();i++){
            JSONObject meta=intent.getJSONArray("shots").getJSONObject(i),shot=args.getJSONArray("shots").getJSONObject(i);
            if(!shot.getString("shotId").equals(meta.getString("shotId"))||!sourceId(args,shot).equals(meta.getString("sourceSketchId"))||!animationId(args,shot).equals(meta.getString("animationId"))
                    ||meta.getInt("frameCount")!=shot.getInt("frameCount")||meta.getInt("pageStart")!=offset||!meta.getString("label").equals(shot.optString("label",shot.getString("shotId"))))throw new IllegalStateException("SKETCH_STORY_INTENT_CHANGED");
            offset+=shot.getInt("frameCount");
        }
    }
    private static JSONObject staged(File root,int index,JSONObject meta,JSONObject args)throws Exception {
        cancelled();JSONObject snapshot=read(shotFile(root,index),SHOT_BYTES),source=snapshot.getJSONObject("sourceDocument"),program=snapshot.getJSONObject("program");
        if(!hash(snapshot).equals(meta.getString("snapshotSha256"))||!hash(source).equals(meta.getString("sourceSha256"))||!hash(program).equals(meta.getString("programSha256"))||!source.getString("id").equals(meta.getString("sourceSketchId")))throw new IllegalStateException("SKETCH_STORY_FROZEN_SOURCE_CHANGED");
        SketchDocument.validate(source);requireClock(source,args,meta);return snapshot;
    }
    private static String sourceId(JSONObject args,JSONObject shot)throws Exception{return args.getString("bookId")+"_src_"+shot.getString("shotId");}
    private static String animationId(JSONObject args,JSONObject shot)throws Exception{return args.getString("bookId")+"_anim_"+shot.getString("shotId");}
    private static String name(JSONObject args,JSONObject shot)throws Exception {String name=args.getString("name")+" · "+shot.optString("label",shot.getString("shotId"));return name.substring(0,Math.min(160,name.length()));}
    private static String hash(JSONObject value)throws Exception{return SketchDocument.sha256(SketchDocument.canonical(value).getBytes(StandardCharsets.UTF_8));}
    private static File shotFile(File root,int index){return new File(root,"shot_"+index+".json");}
    private static boolean present(File file){return file.isFile()||new File(file.getPath()+".bak").isFile();}
    private static void cancelled()throws InterruptedException {if(Thread.currentThread().isInterrupted())throw new InterruptedException("Story plan cancelled; retry the exact original request to resume");}
    private static JSONObject read(File file,int limit)throws Exception {
        try(FileInputStream in=new AtomicFile(file).openRead();ByteArrayOutputStream bytes=new ByteArrayOutputStream()){
            byte[] buffer=new byte[32768];int count;while((count=in.read(buffer))!=-1){cancelled();if(bytes.size()+count>limit)throw SketchDocument.bad("Story journal byte budget exceeded");bytes.write(buffer,0,count);}return SketchPaintArchive.parseOwnedJson(bytes.toByteArray());
        }
    }
    private static void write(Context context,File file,JSONObject object)throws Exception {
        cancelled();byte[] bytes=object.toString().getBytes(StandardCharsets.UTF_8);if(bytes.length>SHOT_BYTES||context.getFilesDir().getUsableSpace()<RESERVE+3L*bytes.length)throw new IllegalStateException("SKETCH_STORY_STORAGE: native staging reserve exhausted");
        AtomicFile atomic=new AtomicFile(file);FileOutputStream out=null;try{out=atomic.startWrite();out.write(bytes);atomic.finishWrite(out);}catch(Exception failure){if(out!=null)atomic.failWrite(out);throw failure;}
    }
}
