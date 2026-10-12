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
        Db(Context c){super(c,"sketch_books_v1.db",null,2);}
        public void onCreate(SQLiteDatabase db){db.execSQL("CREATE TABLE book_operations(book_id TEXT NOT NULL,operation_id TEXT NOT NULL,fingerprint TEXT NOT NULL,result TEXT NOT NULL,PRIMARY KEY(book_id,operation_id))");db.execSQL("CREATE TABLE books(id TEXT PRIMARY KEY,request_sha TEXT NOT NULL,json TEXT NOT NULL)");db.execSQL("CREATE TABLE pages(book_id TEXT NOT NULL,page INTEGER NOT NULL,sketch_id TEXT NOT NULL UNIQUE,PRIMARY KEY(book_id,page))");}
        public void onUpgrade(SQLiteDatabase db,int old,int next){if(old==1&&next==2){db.execSQL("CREATE TABLE book_operations(book_id TEXT NOT NULL,operation_id TEXT NOT NULL,fingerprint TEXT NOT NULL,result TEXT NOT NULL,PRIMARY KEY(book_id,operation_id))");}else throw new IllegalStateException("Sketch book migration required");}
    }
    public SketchBookStore(Context c){context=c.getApplicationContext();helper=new Db(context);db=helper.getWritableDatabase();}
    public void close(){helper.close();}
    public static JSONObject execute(Context c,String action,JSONObject args) throws Exception {
        synchronized(LOCK){try(SketchBookStore store=new SketchBookStore(c)){
            if("sketch_book_list".equals(action)){SketchDocument.fields(args,"");JSONArray books=new JSONArray();try(Cursor cursor=store.db.rawQuery("SELECT id FROM books ORDER BY rowid DESC LIMIT 128",null)){while(cursor.moveToNext())books.put(store.get(cursor.getString(0)));}return new JSONObject().put("books",books);}
            String id=SketchDocument.id(args,"bookId");if(id.length()>72)throw SketchDocument.bad("Book ID is limited to 72 characters");
            if("sketch_book_create".equals(action)){
                SketchDocument.fields(args,"bookId,operationId,name,pageCount,width,height,fps,background");SketchDocument.id(args,"operationId");int count=args.has("pageCount")?(int)SketchDocument.integer(args,"pageCount",1,10000):1000;
                JSONObject probe=SketchDocument.create(new JSONObject().put("sketchId",id).put("name",args.optString("name","Sketch book")).put("width",args.optInt("width",720)).put("height",args.optInt("height",1280)).put("fps",args.optInt("fps",24)).put("background",args.optString("background","#FFFFFFFF")).put("frameCount",1));
                String hash=SketchDocument.sha256(SketchDocument.canonical(args).getBytes(StandardCharsets.UTF_8));try(Cursor old=store.db.rawQuery("SELECT request_sha FROM books WHERE id=?",new String[]{id})){if(old.moveToFirst()){if(!hash.equals(old.getString(0)))throw new IllegalStateException("SKETCH_BOOK_OPERATION_CONFLICT");return new JSONObject().put("book",store.get(id));}}
                JSONObject book=new JSONObject().put("bookId",id).put("name",probe.getString("name")).put("pageCount",count).put("revision",1).put("width",probe.getInt("width")).put("height",probe.getInt("height")).put("fps",probe.getInt("fps")).put("background",probe.getString("background"));
                ContentValues values=new ContentValues();values.put("id",id);values.put("request_sha",hash);values.put("json",book.toString());store.db.insertOrThrow("books",null,values);return new JSONObject().put("book",store.get(id));
            }
            if("sketch_book_edit".equals(action)){
                SketchDocument.fields(args,"bookId,expectedRevision,operationId,name,fps,exposures");String op=SketchDocument.id(args,"operationId"),hash=SketchDocument.sha256(SketchDocument.canonical(args).getBytes(StandardCharsets.UTF_8));long expected=SketchDocument.integer(args,"expectedRevision",1,Long.MAX_VALUE-1);store.db.beginTransaction();
                try{try(Cursor receipt=store.db.rawQuery("SELECT fingerprint,result FROM book_operations WHERE book_id=? AND operation_id=?",new String[]{id,op})){if(receipt.moveToFirst()){if(!hash.equals(receipt.getString(0)))throw new IllegalStateException("SKETCH_BOOK_OPERATION_CONFLICT");JSONObject previous=new JSONObject(receipt.getString(1));store.db.setTransactionSuccessful();return previous;}}
                    JSONObject book=store.get(id);if(book.getLong("revision")!=expected)throw new IllegalStateException("SKETCH_BOOK_REVISION_CONFLICT");book.remove("createdPages");if(args.has("name")){SketchDocument.text(args,"name",160);book.put("name",args.getString("name"));}if(args.has("fps")){int fps=(int)SketchDocument.integer(args,"fps",24,60);NativeFrameClock.requireFps(fps);book.put("fps",fps);}
                    JSONObject holds=book.optJSONObject("exposures");if(holds==null)holds=new JSONObject();if(args.has("exposures")){JSONArray edits=args.getJSONArray("exposures");if(edits.length()>128)throw SketchDocument.bad("Set up to 128 page exposures per edit");for(int i=0;i<edits.length();i++){JSONObject entry=edits.getJSONObject(i);SketchDocument.fields(entry,"page,frames");int page=(int)SketchDocument.integer(entry,"page",0,book.getInt("pageCount")-1),frames=(int)SketchDocument.integer(entry,"frames",1,600);if(frames==1)holds.remove(String.valueOf(page));else holds.put(String.valueOf(page),frames);}}
                    book.put("exposures",holds).put("revision",expected+1);ContentValues value=new ContentValues();value.put("json",book.toString());store.db.update("books",value,"id=?",new String[]{id});JSONObject result=new JSONObject().put("book",store.get(id));ContentValues saved=new ContentValues();saved.put("book_id",id);saved.put("operation_id",op);saved.put("fingerprint",hash);saved.put("result",result.toString());store.db.insertOrThrow("book_operations",null,saved);store.db.setTransactionSuccessful();return result;
                }finally{store.db.endTransaction();}
            }
            if("sketch_book_get".equals(action)){SketchDocument.fields(args,"bookId,pageOffset,pageLimit");JSONObject book=store.get(id);int offset=args.has("pageOffset")?(int)SketchDocument.integer(args,"pageOffset",0,book.getInt("pageCount")):0,limit=args.has("pageLimit")?(int)SketchDocument.integer(args,"pageLimit",1,128):64;JSONArray pages=new JSONArray();try(Cursor cursor=store.db.rawQuery("SELECT page,sketch_id FROM pages WHERE book_id=? AND page>=? ORDER BY page LIMIT ?",new String[]{id,String.valueOf(offset),String.valueOf(limit)})){while(cursor.moveToNext())pages.put(new JSONObject().put("page",cursor.getInt(0)).put("sketchId",cursor.getString(1)));}return new JSONObject().put("book",book).put("pages",pages).put("unopenedPagesAreBlank",true);}
            if("sketch_book_open_page".equals(action)){
                SketchDocument.fields(args,"bookId,page");JSONObject book=store.get(id);int page=(int)SketchDocument.integer(args,"page",0,book.getInt("pageCount")-1);String sketch=id+"_p"+page;if(sketch.length()>96)throw SketchDocument.bad("Book ID too long for page identities");
                try(SketchProjectStore sketches=new SketchProjectStore(c)){try{sketches.get(sketch);}catch(IllegalArgumentException unknown){if(!unknown.getMessage().startsWith("UNKNOWN_SKETCH"))throw unknown;JSONObject create=new JSONObject().put("sketchId",sketch).put("operationId","open_page").put("name",book.getString("name").substring(0,Math.min(140,book.getString("name").length()))+" · page "+(page+1)).put("width",book.getInt("width")).put("height",book.getInt("height")).put("fps",book.getInt("fps")).put("background",book.getString("background")).put("frameCount",1);sketches.create(create,SketchDocument.sha256(SketchDocument.canonical(create).getBytes(StandardCharsets.UTF_8)));}}
                ContentValues values=new ContentValues();values.put("book_id",id);values.put("page",page);values.put("sketch_id",sketch);store.db.insertWithOnConflict("pages",null,values,SQLiteDatabase.CONFLICT_IGNORE);return new JSONObject().put("book",store.get(id)).put("page",page).put("sketchId",sketch);
            }
            throw SketchDocument.bad("Unknown sketch book action");
        }}
    }
    JSONObject pageForSketch(String id) throws Exception {try(Cursor c=db.rawQuery("SELECT book_id,page FROM pages WHERE sketch_id=?",new String[]{id})){return c.moveToFirst()?new JSONObject().put("bookId",c.getString(0)).put("page",c.getInt(1)):null;}}
    JSONObject get(String id) throws Exception {JSONObject book;try(Cursor c=db.rawQuery("SELECT json FROM books WHERE id=?",new String[]{id})){if(!c.moveToFirst())throw SketchDocument.bad("Unknown sketch book");book=new JSONObject(c.getString(0));}try(Cursor c=db.rawQuery("SELECT COUNT(*) FROM pages WHERE book_id=?",new String[]{id})){c.moveToFirst();book.put("createdPages",c.getInt(0));}return book;}
    JSONArray allocatedPages(String id) throws Exception {JSONArray pages=new JSONArray();try(Cursor c=db.rawQuery("SELECT page,sketch_id FROM pages WHERE book_id=? ORDER BY page",new String[]{id})){while(c.moveToNext())pages.put(new JSONObject().put("page",c.getInt(0)).put("sketchId",c.getString(1)));}return pages;}
}
