package com.rezoxnemesis.videostudio;

import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Human-edited, non-destructive multitrack timeline.
 * The primary V1 picture timeline remains Project.clips for compatibility.
 * AUDIO tracks are mixed by Media3; other lanes are preserved for authoring.
 */
public final class StudioTrackLayout {
    public enum Kind { VIDEO, AUDIO, TEXT, OVERLAY, EFFECT }
    public static final int MAX_TRACKS=24, MAX_ITEMS_PER_TRACK=250;

    public static final class Item {
        /** Unique occurrence ID; assetId can be reused in another track/clip. */
        public final String clipId,assetId;
        public final long startMs,sourceInMs,durationMs;
        public final float gain;

        /** Legacy model convenience, where clip ID also names its asset. */
        public Item(String clipId,long startMs,long durationMs) {
            this(clipId,clipId,startMs,0,durationMs,1f);
        }

        public Item(String clipId,String assetId,long startMs,long sourceInMs,
                    long durationMs,float gain) {
            if(!validId(clipId)||!validId(assetId)||
                    startMs<0||sourceInMs<0||durationMs<=0||
                    startMs>86_400_000||durationMs>86_400_000||
                    !Float.isFinite(gain)||gain<0||gain>2)
                throw new IllegalArgumentException("Invalid timeline clip or media bounds");
            this.clipId=clipId;this.assetId=assetId;
            this.startMs=startMs;this.sourceInMs=sourceInMs;
            this.durationMs=durationMs;this.gain=gain;
        }
        public long endMs(){return startMs+durationMs;}
        public Item at(long start){
            return new Item(clipId,assetId,start,sourceInMs,durationMs,gain);
        }
        public Item withDuration(long duration){
            return new Item(clipId,assetId,startMs,sourceInMs,duration,gain);
        }
        JSONObject json() throws Exception {
            return new JSONObject().put("clipId",clipId).put("assetId",assetId)
                    .put("startMs",startMs).put("sourceInMs",sourceInMs)
                    .put("durationMs",durationMs).put("gain",gain);
        }
        private static boolean validId(String id){
            return id!=null&&!id.isEmpty()&&id.length()<=160&&!id.contains("/");
        }
    }
    public static final class Track {
        public final Kind kind;
        public final String id;
        private final ArrayList<Item> items=new ArrayList<>();
        public Track(String id,Kind kind){
            if(id==null||id.isEmpty()||id.length()>100||kind==null)
                throw new IllegalArgumentException("Invalid track");
            this.id=id;this.kind=kind;
        }
        public List<Item> items(){return Collections.unmodifiableList(items);}
        public Item find(String clipId){
            for(Item item:items)if(item.clipId.equals(clipId))return item;
            return null;
        }
        public void add(Item item){
            if(item==null)throw new IllegalArgumentException("Item missing");
            if(items.size()>=MAX_ITEMS_PER_TRACK)
                throw new IllegalArgumentException("Timeline lane is full");
            for(Item existing:items){
                if(existing.clipId.equals(item.clipId))
                    throw new IllegalArgumentException("Duplicate occurrence on track");
                if(existing.startMs<item.endMs()&&item.startMs<existing.endMs())
                    throw new IllegalArgumentException(
                        "Clips overlap on the same lane. Use another track.");
            }
            items.add(item);
            items.sort((a,b)->Long.compare(a.startMs,b.startMs));
        }
        public boolean remove(String clipId){
            return items.removeIf(i->i.clipId.equals(clipId));
        }
        public void replace(String clipId,Item replacement){
            Item previous=find(clipId);
            if(previous==null||replacement==null||
                    !clipId.equals(replacement.clipId))
                throw new IllegalArgumentException("Timeline clip not found");
            items.remove(previous);
            try{add(replacement);}
            catch(RuntimeException error){items.add(previous);
                items.sort((a,b)->Long.compare(a.startMs,b.startMs));
                throw error;
            }
        }
    }
    private final ArrayList<Track> tracks=new ArrayList<>();
    public List<Track> tracks(){return Collections.unmodifiableList(tracks);}
    public Track track(String id){
        for(Track t:tracks)if(t.id.equals(id))return t;
        return null;
    }
    public Track first(Kind kind){
        for(Track t:tracks)if(t.kind==kind)return t;
        return null;
    }
    public Track addTrack(String id,Kind kind){
        if(tracks.size()>=MAX_TRACKS)
            throw new IllegalArgumentException("Maximum studio track count exceeded");
        if(track(id)!=null)throw new IllegalArgumentException("Duplicate track");
        Track t=new Track(id,kind);tracks.add(t);return t;
    }
    public long durationMs(){
        long end=0;
        for(Track track:tracks)for(Item item:track.items)end=Math.max(end,item.endMs());
        return end;
    }
    public JSONObject toJson() throws Exception{
        JSONArray result=new JSONArray();
        for(Track track:tracks){
            JSONArray items=new JSONArray();
            for(Item item:track.items)items.put(item.json());
            result.put(new JSONObject().put("id",track.id)
                    .put("kind",track.kind.name()).put("items",items));
        }
        return new JSONObject().put("schema",2).put("tracks",result);
    }
    public static StudioTrackLayout fromJson(JSONObject json) throws Exception{
        if(json==null)throw new IllegalArgumentException("Timeline missing");
        int version=json.optInt("schema",-1);
        if(version!=1&&version!=2)
            throw new IllegalArgumentException("Unknown timeline schema");
        StudioTrackLayout layout=new StudioTrackLayout();
        JSONArray tracks=json.getJSONArray("tracks");
        if(tracks.length()>MAX_TRACKS)
            throw new IllegalArgumentException("Too many tracks");
        for(int i=0;i<tracks.length();i++){
            JSONObject t=tracks.getJSONObject(i);
            Track track=layout.addTrack(t.getString("id"),
                    Kind.valueOf(t.getString("kind")));
            JSONArray items=t.getJSONArray("items");
            if(items.length()>MAX_ITEMS_PER_TRACK)
                throw new IllegalArgumentException("Too many clips");
            for(int j=0;j<items.length();j++){
                JSONObject item=items.getJSONObject(j);
                String clipId=item.getString("clipId");
                track.add(new Item(clipId,item.optString("assetId",clipId),
                        item.getLong("startMs"),item.optLong("sourceInMs",0),
                        item.getLong("durationMs"),
                        (float)item.optDouble("gain",1)));
            }
        }
        return layout;
    }
}