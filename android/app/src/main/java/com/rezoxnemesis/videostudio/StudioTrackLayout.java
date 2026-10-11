package com.rezoxnemesis.videostudio;

import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Editable non-destructive multitrack timeline model.
 * This is an authoring model, not a claim that the Media3 exporter
 * already composites arbitrary overlapping tracks.
 */
public final class StudioTrackLayout {
    public enum Kind { VIDEO, AUDIO, TEXT, OVERLAY, EFFECT }
    public static final class Item {
        public final String clipId;
        public final long startMs, durationMs;
        public Item(String clipId,long startMs,long durationMs) {
            if(clipId==null||clipId.isEmpty()||startMs<0||durationMs<=0)
                throw new IllegalArgumentException("Invalid track item");
            this.clipId=clipId;this.startMs=startMs;this.durationMs=durationMs;
        }
        JSONObject json() throws Exception {
            return new JSONObject().put("clipId",clipId).put("startMs",startMs)
                    .put("durationMs",durationMs);
        }
    }
    public static final class Track {
        public final Kind kind;
        public final String id;
        private final ArrayList<Item> items=new ArrayList<>();
        public Track(String id,Kind kind){
            if(id==null||id.isEmpty()||kind==null)
                throw new IllegalArgumentException("Invalid track");
            this.id=id;this.kind=kind;
        }
        public List<Item> items(){return Collections.unmodifiableList(items);}
        public void add(Item item){
            if(item==null)throw new IllegalArgumentException("Item missing");
            for(Item existing:items)
                if(existing.clipId.equals(item.clipId))
                    throw new IllegalArgumentException("Duplicate clip on track");
            items.add(item);
            items.sort((a,b)->Long.compare(a.startMs,b.startMs));
        }
    }
    private final ArrayList<Track> tracks=new ArrayList<>();
    public List<Track> tracks(){return Collections.unmodifiableList(tracks);}
    public Track addTrack(String id,Kind kind){
        for(Track t:tracks)if(t.id.equals(id))
            throw new IllegalArgumentException("Duplicate track");
        Track track=new Track(id,kind);tracks.add(track);return track;
    }
    public JSONObject toJson() throws Exception{
        JSONArray result=new JSONArray();
        for(Track track:tracks){
            JSONArray items=new JSONArray();
            for(Item item:track.items)items.put(item.json());
            result.put(new JSONObject().put("id",track.id)
                    .put("kind",track.kind.name()).put("items",items));
        }
        return new JSONObject().put("schema",1).put("tracks",result);
    }
    public static StudioTrackLayout fromJson(JSONObject json) throws Exception{
        if(json==null||json.optInt("schema",-1)!=1)
            throw new IllegalArgumentException("Unknown timeline schema");
        StudioTrackLayout layout=new StudioTrackLayout();
        JSONArray tracks=json.getJSONArray("tracks");
        if(tracks.length()>32)throw new IllegalArgumentException("Too many tracks");
        for(int i=0;i<tracks.length();i++){
            JSONObject t=tracks.getJSONObject(i);
            Track track=layout.addTrack(t.getString("id"),Kind.valueOf(t.getString("kind")));
            JSONArray items=t.getJSONArray("items");
            if(items.length()>2000)throw new IllegalArgumentException("Too many clips");
            for(int j=0;j<items.length();j++){
                JSONObject item=items.getJSONObject(j);
                track.add(new Item(item.getString("clipId"),item.getLong("startMs"),
                        item.getLong("durationMs")));
            }
        }
        return layout;
    }
}
