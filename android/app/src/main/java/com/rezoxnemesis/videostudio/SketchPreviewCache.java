package com.rezoxnemesis.videostudio;
import android.content.Context;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.LinkedHashMap;

/** Bounded process-local PNG reuse; exact document revision and viewport are part of the key. */
final class SketchPreviewCache {
    private static final int MAX_CHARACTERS=2*1024*1024;
    private static final LinkedHashMap<String,Entry> entries=new LinkedHashMap<>(8,.75f,true);
    private static int characters;
    private static final class Entry {final String reply;final JSONArray references;Entry(String reply,JSONArray references){this.reply=reply;this.references=references;}}
    static String key(Context c,String id,long revision,int frame,int dimension,JSONObject crop)throws Exception{return c.getFilesDir().getAbsolutePath()+":"+id+":"+revision+":"+frame+":"+dimension+":"+(crop==null?"full":SketchDocument.canonical(crop));}
    static JSONObject get(SketchProjectStore store,String id,String key)throws Exception {
        Entry entry;synchronized(entries){entry=entries.get(key);}if(entry==null)return null;
        // Cached pixels must never hide a changed or missing immutable source PNG.
        for(int n=0;n<entry.references.length();n++)store.referenceFile(id,entry.references.getJSONObject(n));
        return new JSONObject(entry.reply).put("previewCached",true);
    }
    static void put(String key,JSONObject result,JSONObject document)throws Exception {
        String reply=result.toString();JSONArray references=new JSONArray(),layers=document.getJSONArray("layers");for(int n=0;n<layers.length();n++){JSONArray objects=layers.getJSONObject(n).getJSONArray("objects");for(int k=0;k<objects.length();k++){JSONObject o=objects.getJSONObject(k);if("image".equals(o.getString("kind")))references.put(SketchDocument.copy(o.getJSONObject("reference")));}}
        int bytes=reply.length()+references.toString().length();if(bytes>MAX_CHARACTERS)return;
        synchronized(entries){Entry previous=entries.remove(key);if(previous!=null)characters-=previous.reply.length()+previous.references.toString().length();while(!entries.isEmpty()&&characters+bytes>MAX_CHARACTERS){String oldest=entries.keySet().iterator().next();Entry removed=entries.remove(oldest);characters-=removed.reply.length()+removed.references.toString().length();}entries.put(key,new Entry(reply,references));characters+=bytes;}
    }
}
