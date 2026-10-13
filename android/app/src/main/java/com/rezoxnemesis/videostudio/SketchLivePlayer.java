package com.rezoxnemesis.videostudio;
import android.content.Context;
import android.os.*;
import org.json.*;
import java.util.concurrent.*;
/** Replays every accepted operation from the current live session, never coalescing drawing steps. */
public final class SketchLivePlayer implements AutoCloseable {
    public interface Listener {
        void drawing(JSONObject doc,String objectId,float fraction,String caption);
        void committed(JSONObject doc);
        void idle();
        void finished(long sessionSequence);
        void failed(String message);
    }
    private final Context context;private final Handler main=new Handler(Looper.getMainLooper());private final ExecutorService reader=Executors.newSingleThreadExecutor();
    private final Listener listener;private JSONObject document;private String id,sessionId;private long sequence;private final int duration;private JSONArray events=new JSONArray(),operations;private int eventIndex,operationIndex;private JSONObject event,next;private String target;private long started;private boolean closed,fetching;
    public SketchLivePlayer(Context c,JSONObject baseline,Listener listener) throws Exception {context=c.getApplicationContext();document=SketchDocument.copy(baseline.getJSONObject("document"));id=baseline.getString("sketchId");sessionId=baseline.getString("sessionId");sequence=baseline.getLong("afterSequence");duration=baseline.getInt("strokeDurationMs");this.listener=listener;}
    public void start(){listener.committed(document);main.post(tick);}
    private final Runnable tick=new Runnable(){public void run(){if(closed)return;try{
        if(next!=null){float fraction=Math.min(1,(SystemClock.uptimeMillis()-started)/(float)duration);listener.drawing(next,target,fraction,"Live ChatGPT · drawing "+(operationIndex+1)+"/"+operations.length()+" · r"+event.getLong("revision"));if(fraction<1){main.postDelayed(this,16);return;}document=next;next=null;operationIndex++;}
        if(operations!=null&&operationIndex<operations.length()){
            JSONObject op=operations.getJSONObject(operationIndex);next=SketchDocument.copy(document);SketchEditOps.apply(next,op);target=op.has("object")?op.getJSONObject("object").optString("id",null):op.optString("objectId",null);
            boolean stroke="add_object".equals(op.optString("type"))||"append_points".equals(op.optString("type"))||"update_object".equals(op.optString("type"));if(stroke&&target!=null&&SketchDocument.object(next,target).has("kind")){started=SystemClock.uptimeMillis();main.post(this);return;}
            document=next;next=null;operationIndex++;listener.drawing(document,null,1,"Live ChatGPT · "+op.optString("type"));main.post(this);return;
        }
        if(operations!=null){document.put("revision",event.getLong("revision"));sequence=event.getLong("sequence");operations=null;listener.committed(document);eventIndex++;}
        if(eventIndex<events.length()){
            event=events.getJSONObject(eventIndex);if(event.getLong("baseRevision")!=document.getLong("revision"))throw new IllegalStateException("LIVE_DRAWING_GAP: a non-stream edit changed the source; start a new live session to keep every step visible");operations=event.getJSONArray("operations");operationIndex=0;main.post(this);return;
        }
        listener.idle();if(fetching)return;fetching=true;
        reader.execute(()->{try{JSONObject batch;try(SketchProjectStore store=new SketchProjectStore(context)){batch=store.liveEvents(id,sequence,4);JSONObject stream=store.liveSession(id,sessionId);if(stream.optBoolean("ended")){JSONArray filtered=new JSONArray();JSONArray all=batch.getJSONArray("events");for(int i=0;i<all.length();i++)if(all.getJSONObject(i).getLong("sequence")<=stream.getLong("endSequence"))filtered.put(all.getJSONObject(i));batch.put("events",filtered);}batch.put("session",stream);}main.post(()->{fetching=false;if(closed)return;events=batch.optJSONArray("events");eventIndex=0;JSONObject state=batch.optJSONObject("session");if(state!=null&&state.optBoolean("ended")&&sequence>=state.optLong("endSequence")){listener.finished(state.optLong("sessionSequence"));close();return;}main.postDelayed(tick,events.length()==0?100:0);});}catch(Exception error){main.post(()->{fetching=false;if(closed)return;listener.failed(error.getMessage());close();});}});
    }catch(Exception error){listener.failed(error.getMessage());close();}}};
    @Override public void close(){closed=true;main.removeCallbacksAndMessages(null);reader.shutdownNow();}
}
