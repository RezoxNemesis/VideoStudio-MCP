package com.rezoxnemesis.videostudio;
import android.content.*;
import android.database.Cursor;
import android.database.sqlite.*;
import org.json.*;
import java.nio.charset.StandardCharsets;
/** Thousands of independent pages; allocate documents only when opened. */
public final class SketchBookStore implements AutoCloseable {
    private static final Object LOCK=new Object();
    private final Context context;private final Db helper;private final SQLiteDatabase db;
    private static final class Db extends SQLiteOpenHelper {
        Db(Context c){super(c,"sketch_books_v1.db",null,3);}
        public void onCreate(SQLiteDatabase db){db.execSQL("CREATE TABLE book_operations(book_id TEXT NOT NULL,operation_id TEXT NOT NULL,fingerprint TEXT NOT NULL,result TEXT NOT NULL,PRIMARY KEY(book_id,operation_id))");createCues(db);db.execSQL("CREATE TABLE books(id TEXT PRIMARY KEY,request_sha TEXT NOT NULL,json TEXT NOT NULL)");db.execSQL("CREATE TABLE pages(book_id TEXT NOT NULL,page INTEGER NOT NULL,sketch_id TEXT NOT NULL UNIQUE,PRIMARY KEY(book_id,page))");}
        private static void createCues(SQLiteDatabase db){db.execSQL("CREATE TABLE book_cues(book_id TEXT NOT NULL,id TEXT NOT NULL,start_frame INTEGER NOT NULL,end_frame INTEGER NOT NULL,json TEXT NOT NULL,PRIMARY KEY(book_id,id))");db.execSQL("CREATE INDEX book_cues_frames ON book_cues(book_id,start_frame,end_frame)");}
        public void onUpgrade(SQLiteDatabase db,int old,int next){if(old<1||next!=3)throw new IllegalStateException("Sketch book migration required");if(old<2)db.execSQL("CREATE TABLE book_operations(book_id TEXT NOT NULL,operation_id TEXT NOT NULL,fingerprint TEXT NOT NULL,result TEXT NOT NULL,PRIMARY KEY(book_id,operation_id))");if(old<3)createCues(db);}

    }
    public SketchBookStore(Context c){context=c.getApplicationContext();helper=new Db(context);db=helper.getWritableDatabase();}
    public void close(){helper.close();}
    public static JSONObject execute(Context c,String action,JSONObject args) throws Exception {
        synchronized(LOCK){try(SketchBookStore store=new SketchBookStore(c)){
            if("sketch_book_list".equals(action)){SketchDocument.fields(args,"");JSONArray books=new JSONArray();try(Cursor cursor=store.db.rawQuery("SELECT id FROM books ORDER BY rowid DESC LIMIT 128",null)){while(cursor.moveToNext())books.put(projectBook(store.get(cursor.getString(0)),0,128));}return new JSONObject().put("books",books);}
            String id=SketchDocument.id(args,"bookId");if(id.length()>72)throw SketchDocument.bad("Book ID is limited to 72 characters");
            if("sketch_book_create".equals(action)){
                SketchDocument.fields(args,"bookId,operationId,name,pageCount,width,height,fps,background");SketchDocument.id(args,"operationId");int count=args.has("pageCount")?(int)SketchDocument.integer(args,"pageCount",1,SketchSequenceLimits.MAX_CELS):1000;
                JSONObject probe=SketchDocument.create(new JSONObject().put("sketchId",id).put("name",args.optString("name","Sketch book")).put("width",args.optInt("width",720)).put("height",args.optInt("height",1280)).put("fps",args.optInt("fps",24)).put("background",args.optString("background","#FFFFFFFF")).put("frameCount",1));
                String hash=SketchDocument.sha256(SketchDocument.canonical(args).getBytes(StandardCharsets.UTF_8));try(Cursor old=store.db.rawQuery("SELECT request_sha FROM books WHERE id=?",new String[]{id})){if(old.moveToFirst()){if(!hash.equals(old.getString(0)))throw new IllegalStateException("SKETCH_BOOK_OPERATION_CONFLICT");return new JSONObject().put("book",projectBook(store.get(id),0,128));}}
                JSONObject book=new JSONObject().put("bookId",id).put("name",probe.getString("name")).put("pageCount",count).put("revision",1).put("width",probe.getInt("width")).put("height",probe.getInt("height")).put("fps",probe.getInt("fps")).put("background",probe.getString("background"));
                ContentValues values=new ContentValues();values.put("id",id);values.put("request_sha",hash);values.put("json",book.toString());store.db.insertOrThrow("books",null,values);return new JSONObject().put("book",projectBook(store.get(id),0,128));
            }
            if("sketch_book_edit".equals(action)){
                SketchDocument.fields(args,"bookId,expectedRevision,operationId,name,fps,exposures,cues");String op=SketchDocument.id(args,"operationId"),hash=SketchDocument.sha256(SketchDocument.canonical(args).getBytes(StandardCharsets.UTF_8));long expected=SketchDocument.integer(args,"expectedRevision",1,Long.MAX_VALUE-1);store.db.beginTransaction();
                try{try(Cursor receipt=store.db.rawQuery("SELECT fingerprint,result FROM book_operations WHERE book_id=? AND operation_id=?",new String[]{id,op})){if(receipt.moveToFirst()){if(!hash.equals(receipt.getString(0)))throw new IllegalStateException("SKETCH_BOOK_OPERATION_CONFLICT");JSONObject previous=new JSONObject(receipt.getString(1));store.db.setTransactionSuccessful();return previous;}}
                    JSONObject book=store.get(id);if(book.getLong("revision")!=expected)throw new IllegalStateException("SKETCH_BOOK_REVISION_CONFLICT");book.remove("createdPages");if(args.has("name")){SketchDocument.text(args,"name",160);book.put("name",args.getString("name"));}if(args.has("fps")){int fps=(int)SketchDocument.integer(args,"fps",24,60);NativeFrameClock.requireFps(fps);book.put("fps",fps);}
                    JSONObject holds=book.optJSONObject("exposures");if(holds==null)holds=new JSONObject();if(args.has("exposures")){JSONArray edits=args.getJSONArray("exposures");if(edits.length()>128)throw SketchDocument.bad("Set up to 128 page exposures per edit");for(int i=0;i<edits.length();i++){JSONObject entry=edits.getJSONObject(i);SketchDocument.fields(entry,"page,frames");int page=(int)SketchDocument.integer(entry,"page",0,book.getInt("pageCount")-1),frames=(int)SketchDocument.integer(entry,"frames",1,600);if(frames==1)holds.remove(String.valueOf(page));else holds.put(String.valueOf(page),frames);}}
                    book.put("exposures",holds).put("revision",expected+1);
                    long totalFrames=SketchTimelineTools.totalFrames(holds,book.getInt("pageCount"));
                    if(totalFrames>SketchSequenceLimits.MAX_FRAMES)throw SketchDocument.bad("Sequence exceeds 1,000,000 exposed frames");
                    store.editCues(id,args.has("cues")?args.getJSONArray("cues"):new JSONArray(),(int)totalFrames);
                    if(book.toString().getBytes(StandardCharsets.UTF_8).length>16*1024*1024)throw SketchDocument.bad("Book timing metadata exceeds 16 MiB");ContentValues value=new ContentValues();value.put("json",book.toString());store.db.update("books",value,"id=?",new String[]{id});JSONObject result=new JSONObject().put("book",projectBook(store.get(id),0,128));ContentValues saved=new ContentValues();saved.put("book_id",id);saved.put("operation_id",op);saved.put("fingerprint",hash);saved.put("result",result.toString());store.db.insertOrThrow("book_operations",null,saved);store.db.setTransactionSuccessful();return result;
                }finally{store.db.endTransaction();}
            }
            if("sketch_book_timeline".equals(action))return store.timeline(args);
            if("sketch_book_get".equals(action)){SketchDocument.fields(args,"bookId,pageOffset,pageLimit");JSONObject book=store.get(id);int offset=args.has("pageOffset")?(int)SketchDocument.integer(args,"pageOffset",0,book.getInt("pageCount")):0,limit=args.has("pageLimit")?(int)SketchDocument.integer(args,"pageLimit",1,128):64;JSONArray pages=new JSONArray();try(Cursor cursor=store.db.rawQuery("SELECT page,sketch_id FROM pages WHERE book_id=? AND page>=? ORDER BY page LIMIT ?",new String[]{id,String.valueOf(offset),String.valueOf(limit)})){while(cursor.moveToNext())pages.put(new JSONObject().put("page",cursor.getInt(0)).put("sketchId",cursor.getString(1)));}return new JSONObject().put("book",projectBook(book,offset,limit)).put("pages",pages).put("unopenedPagesAreBlank",true);}
            if("sketch_book_open_page".equals(action)){
                SketchDocument.fields(args,"bookId,page");JSONObject book=store.get(id);int page=(int)SketchDocument.integer(args,"page",0,book.getInt("pageCount")-1);String sketch=id+"_p"+page;if(sketch.length()>96)throw SketchDocument.bad("Book ID too long for page identities");
                try(SketchProjectStore sketches=new SketchProjectStore(c)){try{sketches.get(sketch);}catch(IllegalArgumentException unknown){if(!unknown.getMessage().startsWith("UNKNOWN_SKETCH"))throw unknown;JSONObject create=new JSONObject().put("sketchId",sketch).put("operationId","open_page").put("name",book.getString("name").substring(0,Math.min(140,book.getString("name").length()))+" · page "+(page+1)).put("width",book.getInt("width")).put("height",book.getInt("height")).put("fps",book.getInt("fps")).put("background",book.getString("background")).put("frameCount",1);sketches.create(create,SketchDocument.sha256(SketchDocument.canonical(create).getBytes(StandardCharsets.UTF_8)));}}
                ContentValues values=new ContentValues();values.put("book_id",id);values.put("page",page);values.put("sketch_id",sketch);store.db.insertWithOnConflict("pages",null,values,SQLiteDatabase.CONFLICT_IGNORE);return new JSONObject().put("book",projectBook(store.get(id),page,128)).put("page",page).put("sketchId",sketch);
            }
            throw SketchDocument.bad("Unknown sketch book action");
        }}
    }
    static JSONObject projectBook(JSONObject source,int offset,int limit)throws Exception {
        JSONObject book=SketchDocument.copy(source),holds=book.optJSONObject("exposures");book.put("totalFrames",SketchTimelineTools.totalFrames(holds,book.getInt("pageCount")));
        if(holds!=null&&holds.length()>128){JSONObject page=new JSONObject();for(int p=offset;p<Math.min(book.getInt("pageCount"),offset+limit);p++)if(holds.has(String.valueOf(p)))page.put(String.valueOf(p),holds.getInt(String.valueOf(p)));book.put("exposures",page).put("exposuresPaginated",true).put("exposurePageOffset",offset).put("exposurePageLimit",limit);}
        return book;
    }
    private void editCues(String id,JSONArray edits,int total)throws Exception {
        if(edits.length()>128)throw SketchDocument.bad("Edit up to 128 direction cues per operation");
        java.util.Set<String> seen=new java.util.HashSet<>();
        for(int i=0;i<edits.length();i++){
            JSONObject cue=edits.getJSONObject(i);SketchDocument.fields(cue,"id,type,startFrame,endFrame,label,actorId,targetId,delete");String key=SketchDocument.id(cue,"id");if(!seen.add(key))throw SketchDocument.bad("Duplicate cue ID in batch");
            if(cue.has("delete")){if(!(cue.get("delete") instanceof Boolean))throw SketchDocument.bad("Cue delete must be boolean");if(cue.getBoolean("delete")){SketchDocument.fields(cue,"id,delete");db.delete("book_cues","book_id=? AND id=?",new String[]{id,key});continue;}}
            String type=cue.getString("type");if(!java.util.Arrays.asList("shot","pose","turn","expression","camera","contact","impact","sound").contains(type))throw SketchDocument.bad("Unsupported direction cue type");
            int first=(int)SketchDocument.integer(cue,"startFrame",0,total-1),last=(int)SketchDocument.integer(cue,"endFrame",first,total-1);SketchDocument.text(cue,"label",512);if(cue.has("actorId"))SketchDocument.id(cue,"actorId");if(cue.has("targetId"))SketchDocument.id(cue,"targetId");
            ContentValues row=new ContentValues();row.put("book_id",id);row.put("id",key);row.put("start_frame",first);row.put("end_frame",last);row.put("json",cue.toString());db.insertWithOnConflict("book_cues",null,row,SQLiteDatabase.CONFLICT_REPLACE);
        }
        try(Cursor count=db.rawQuery("SELECT COUNT(*) FROM book_cues WHERE book_id=?",new String[]{id})){count.moveToFirst();if(count.getInt(0)>65536)throw SketchDocument.bad("Book direction cue limit exceeded");}
        try(Cursor invalid=db.rawQuery("SELECT 1 FROM book_cues WHERE book_id=? AND end_frame>=? LIMIT 1",new String[]{id,String.valueOf(total)})){if(invalid.moveToFirst())throw SketchDocument.bad("An existing cue falls beyond the new sequence duration");}
    }
    private JSONObject timeline(JSONObject args)throws Exception {
        SketchDocument.fields(args,"bookId,expectedRevision,frameOffset,frameLimit,cueOffset,cueLimit");String id=SketchDocument.id(args,"bookId");JSONObject book=get(id);if(args.has("expectedRevision")&&SketchDocument.integer(args,"expectedRevision",1,Long.MAX_VALUE-1)!=book.getLong("revision"))throw new IllegalStateException("SKETCH_BOOK_REVISION_CONFLICT");
        SketchSequenceTimeline time=new SketchSequenceTimeline(book,0,book.getInt("pageCount"));int offset=args.has("frameOffset")?(int)SketchDocument.integer(args,"frameOffset",0,time.frames()-1):0,limit=args.has("frameLimit")?(int)SketchDocument.integer(args,"frameLimit",1,128):48,end=Math.min(time.frames(),offset+limit);
        int cueOffset=args.has("cueOffset")?(int)SketchDocument.integer(args,"cueOffset",0,65536):0,cueLimit=args.has("cueLimit")?(int)SketchDocument.integer(args,"cueLimit",1,128):64;JSONArray rows=new JSONArray(),cues=new JSONArray();
        for(int f=offset;f<end;f++){int page=time.page(f);JSONObject row=new JSONObject().put("frame",f).put("page",page).put("celFrame",f-time.firstFrame(page)).put("hold",SketchTimelineTools.exposure(book.optJSONObject("exposures"),page));try(Cursor c=db.rawQuery("SELECT sketch_id FROM pages WHERE book_id=? AND page=?",new String[]{id,String.valueOf(page)})){if(c.moveToFirst())row.put("sketchId",c.getString(0));else row.put("blank",true);}rows.put(row);}
        boolean more=false;try(Cursor c=db.rawQuery("SELECT json FROM book_cues WHERE book_id=? AND start_frame<? AND end_frame>=? ORDER BY start_frame,id LIMIT ? OFFSET ?",new String[]{id,String.valueOf(end),String.valueOf(offset),String.valueOf(cueLimit+1),String.valueOf(cueOffset)})){while(c.moveToNext()){if(cues.length()==cueLimit){more=true;break;}cues.put(new JSONObject(c.getString(0)));}}
        return new JSONObject().put("bookId",id).put("revision",book.getLong("revision")).put("fps",book.getInt("fps")).put("totalFrames",time.frames()).put("durationUs",NativeFrameClock.presentationTimeUs(time.frames(),book.getInt("fps"))).put("frames",rows).put("nextFrameOffset",end).put("moreFrames",end<time.frames()).put("cues",cues).put("nextCueOffset",cueOffset+cues.length()).put("moreCues",more).put("cuesExecuteDrawing",false);
    }
    JSONObject pageForSketch(String id) throws Exception {try(Cursor c=db.rawQuery("SELECT book_id,page FROM pages WHERE sketch_id=?",new String[]{id})){return c.moveToFirst()?new JSONObject().put("bookId",c.getString(0)).put("page",c.getInt(1)):null;}}
    JSONObject get(String id) throws Exception {String json=SQLiteLargeText.read(db,"SELECT json FROM books WHERE id=?",id);if(json==null)throw SketchDocument.bad("Unknown sketch book");JSONObject book=new JSONObject(json);try(Cursor c=db.rawQuery("SELECT COUNT(*) FROM pages WHERE book_id=?",new String[]{id})){c.moveToFirst();book.put("createdPages",c.getInt(0));}return book;}
    void attachImportedPage(String id,int page,String sketchId)throws Exception {
        JSONObject book=get(id);if(page<0||page>=book.getInt("pageCount")||!(id+"_p"+page).equals(sketchId))throw SketchDocument.bad("Imported cel identity rejected");
        ContentValues row=new ContentValues();row.put("book_id",id);row.put("page",page);row.put("sketch_id",sketchId);db.insertWithOnConflict("pages",null,row,SQLiteDatabase.CONFLICT_IGNORE);
    }
    interface PageVisitor {void page(int page,String sketchId)throws Exception;}
    void visitPages(String id,int start,int count,PageVisitor visitor)throws Exception {try(Cursor c=db.rawQuery("SELECT page,sketch_id FROM pages WHERE book_id=? AND page>=? AND page<? ORDER BY page",new String[]{id,String.valueOf(start),String.valueOf((long)start+count)})){while(c.moveToNext())visitor.page(c.getInt(0),c.getString(1));}}
    JSONArray allocatedPages(String id) throws Exception {JSONArray pages=new JSONArray();try(Cursor c=db.rawQuery("SELECT page,sketch_id FROM pages WHERE book_id=? ORDER BY page",new String[]{id})){while(c.moveToNext())pages.put(new JSONObject().put("page",c.getInt(0)).put("sketchId",c.getString(1)));}return pages;}
}
