package com.rezoxnemesis.videostudio;
import android.content.Context;
import android.util.AtomicFile;
import org.json.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
/** Source-bound live session, starting before drawing; accepted operations are journalled in SQLite. */
public final class SketchLiveDrawing {
    private SketchLiveDrawing(){}
    public static JSONObject begin(Context context,JSONObject args) throws Exception {
        SketchDocument.fields(args,"sketchId,expectedRevision,operationId,strokeDurationMs");String id=SketchDocument.id(args,"sketchId"),session=SketchDocument.id(args,"operationId");long revision=SketchDocument.integer(args,"expectedRevision",1,Long.MAX_VALUE-1);int duration=args.has("strokeDurationMs")?(int)SketchDocument.integer(args,"strokeDurationMs",80,5000):120;
        JSONObject baseline;long sequence;
        try(SketchProjectStore store=new SketchProjectStore(context)){try{JSONObject previous=store.liveSession(id,session);JSONObject saved=read(context,id,session);if(saved.getJSONObject("document").getLong("revision")!=revision||saved.getInt("strokeDurationMs")!=duration)throw new IllegalStateException("SKETCH_LIVE_SESSION_CONFLICT");return previous.put("revision",revision).put("everyAcceptedOperationRecorded",true);}catch(IllegalArgumentException missing){if(!missing.getMessage().contains("Unknown live drawing session"))throw missing;}}try(SketchProjectStore store=new SketchProjectStore(context)){store.beginSnapshot();try{baseline=store.get(id);SketchProjectStore.requireRevision(baseline,revision);sequence=store.latestSequence(id);}finally{store.endSnapshot();}}
        JSONObject state=new JSONObject().put("sessionId",session).put("sketchId",id).put("afterSequence",sequence).put("strokeDurationMs",duration).put("document",baseline);
        File root=new File(context.getFilesDir(),"sketch_studio/"+id+"/live");if(!root.isDirectory()&&!root.mkdirs())throw new IOException("Live drawing storage unavailable");File file=new File(root,session+".json");AtomicFile target=new AtomicFile(file);FileOutputStream out=null;
        try{out=target.startWrite();out.write(state.toString().getBytes(StandardCharsets.UTF_8));target.finishWrite(out);}catch(Exception error){if(out!=null)target.failWrite(out);throw error;}
        try(SketchBookStore books=new SketchBookStore(context)){JSONObject page=books.pageForSketch(id);if(page!=null){state.put("bookId",page.getString("bookId")).put("page",page.getInt("page"));}}
        JSONObject metadata;try(SketchProjectStore store=new SketchProjectStore(context)){metadata=store.createLiveSession(state);}
        context.getSharedPreferences("sketch_live_workspace",Context.MODE_PRIVATE).edit().putString("sketchId",id).putString("streamSketchId",id).putString("streamSessionId",session).putLong("signal",System.nanoTime()).apply();
        return metadata.put("sketchId",id).put("sessionId",session).put("revision",revision).put("afterSequence",sequence).put("strokeDurationMs",duration).put("everyAcceptedOperationRecorded",true).put("phoneMustHaveSketchStudioOpen",true);
    }
    public static JSONObject read(Context context,String id,String session) throws Exception {
        SketchDocument.id(new JSONObject().put("id",id),"id");SketchDocument.id(new JSONObject().put("id",session),"id");File file=new File(context.getFilesDir(),"sketch_studio/"+id+"/live/"+session+".json");byte[] bytes;
        try(InputStream in=new AtomicFile(file).openRead()){bytes=in.readNBytes(SketchDocument.MAX_BYTES+4097);}if(bytes.length>SketchDocument.MAX_BYTES+4096)throw SketchDocument.bad("Live baseline exceeds budget");JSONObject state=new JSONObject(new String(bytes,StandardCharsets.UTF_8));if(!id.equals(state.getString("sketchId"))||!session.equals(state.getString("sessionId")))throw SketchDocument.bad("Live source identity changed");SketchDocument.validate(state.getJSONObject("document"));return state;
    }
}
