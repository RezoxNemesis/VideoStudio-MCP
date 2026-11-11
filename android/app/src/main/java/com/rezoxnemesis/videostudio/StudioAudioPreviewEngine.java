package com.rezoxnemesis.videostudio;

import android.content.Context;
import android.net.Uri;
import androidx.media3.common.MediaItem;
import androidx.media3.common.Player;
import androidx.media3.exoplayer.ExoPlayer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Bounded live audition of explicitly imported Audio layers during the human
 * preview. The authoritative final mix is rendered by Media3 Transformer.
 *
 * Uses only source URIs already registered in VideoStudio's project; never
 * searches Android Gallery or remote sites.
 */
public final class StudioAudioPreviewEngine {
    private static final int MAX_SIMULTANEOUS=3;
    private static final long MAX_SYNC_DRIFT_MS=450;
    private final Context app;
    private final HashMap<String,Playback> playback=new HashMap<>();
    private static final class Playback {
        final ExoPlayer player;
        final String assetId;
        final long sourceIn,duration;
        Playback(ExoPlayer player,StudioLayer lane){
            this.player=player;assetId=lane.assetId;
            sourceIn=lane.sourceInMs;duration=lane.durationMs;
        }
        void close(){
            try{player.stop();}catch(Exception ignored){}
            try{player.release();}catch(Exception ignored){}
        }
    }

    public StudioAudioPreviewEngine(Context context){
        if(context==null)throw new IllegalArgumentException("Context needed");
        app=context.getApplicationContext();
    }

    public static ArrayList<StudioLayer> active(ProjectStore.Project project,long ms){
        ArrayList<StudioLayer> result=new ArrayList<>();
        if(project==null)return result;
        for(StudioLayer lane:project.studioLayers){
            if(!StudioLayer.AUDIO.equals(lane.type)||ms<lane.startMs||
                    ms>=lane.startMs+lane.durationMs)continue;
            ProjectStore.Asset asset=project.asset(lane.assetId);
            if(asset==null||asset.mime==null||!asset.mime.startsWith("audio/"))continue;
            result.add(lane);
        }
        return result;
    }

    public void sync(ProjectStore.Project project,long globalMs,boolean playing){
        if(!playing||project==null){stop();return;}
        ArrayList<StudioLayer> active=active(project,globalMs);
        Set<String> wanted=new HashSet<>();
        for(int i=0;i<Math.min(MAX_SIMULTANEOUS,active.size());i++){
            StudioLayer lane=active.get(i);
            ProjectStore.Asset asset=project.asset(lane.assetId);
            if(asset==null||asset.uri==null)continue;
            wanted.add(lane.id);
            Playback item=playback.get(lane.id);
            if(item!=null&&(!item.assetId.equals(lane.assetId)||
                    item.sourceIn!=lane.sourceInMs||item.duration!=lane.durationMs)){
                item.close();playback.remove(lane.id);item=null;
            }
            long offset=Math.max(0,Math.min(lane.durationMs-1,globalMs-lane.startMs));
            if(item==null){
                try{
                    ExoPlayer music=new ExoPlayer.Builder(app).build();
                    MediaItem source=new MediaItem.Builder()
                            .setUri(Uri.parse(asset.uri))
                            .setClippingConfiguration(
                                    new MediaItem.ClippingConfiguration.Builder()
                                            .setStartPositionMs(lane.sourceInMs)
                                            .setEndPositionMs(lane.sourceInMs+lane.durationMs)
                                            .build())
                            .build();
                    music.setMediaItem(source);
                    music.prepare();
                    music.seekTo(offset);
                    music.setVolume(Math.max(0,Math.min(1,lane.gain)));
                    music.play();
                    playback.put(lane.id,new Playback(music,lane));
                }catch(Exception error){
                    // Preview is optional; do not crash the editor or alter
                    // the final Media3 export when local audio decode fails.
                }
            }else{
                item.player.setVolume(Math.max(0,Math.min(1,lane.gain)));
                if(item.player.getPlaybackState()==Player.STATE_READY&&
                        Math.abs(item.player.getCurrentPosition()-offset)>MAX_SYNC_DRIFT_MS)
                    item.player.seekTo(offset);
                if(!item.player.isPlaying())item.player.play();
            }
        }
        for(String id:new ArrayList<>(playback.keySet())){
            if(wanted.contains(id))continue;
            Playback gone=playback.remove(id);
            if(gone!=null)gone.close();
        }
    }

    public void stop(){
        for(Playback item:playback.values())item.close();
        playback.clear();
    }
    public void release(){stop();}
    public static int previewCapacity(){return MAX_SIMULTANEOUS;}
}
