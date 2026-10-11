package com.rezoxnemesis.videostudio;

import java.util.UUID;
import org.json.JSONObject;

/**
 * Human track-edit operations. Edits are saved into the existing project JSON,
 * not an unrelated sidecar file and NEVER modify imported source media bytes.
 */
public final class StudioTrackEditor {
    private StudioTrackEditor(){}

    public static StudioTrackLayout get(ProjectStore.Project project) throws Exception {
        if(project==null)throw new IllegalArgumentException("Open a project first");
        JSONObject saved=project.trackLayout;
        if(saved==null||saved.length()==0){
            StudioTrackLayout layout=new StudioTrackLayout();
            layout.addTrack("video-primary",StudioTrackLayout.Kind.VIDEO);
            layout.addTrack("audio-1",StudioTrackLayout.Kind.AUDIO);
            layout.addTrack("text-1",StudioTrackLayout.Kind.TEXT);
            layout.addTrack("overlay-1",StudioTrackLayout.Kind.OVERLAY);
            layout.addTrack("effects-1",StudioTrackLayout.Kind.EFFECT);
            return layout;
        }
        return StudioTrackLayout.fromJson(saved);
    }

    /** Validate an autonomous edit against explicitly registered project assets. */
    public static void validate(ProjectStore.Project project,
                                StudioTrackLayout layout) {
        if(project==null||layout==null)
            throw new IllegalArgumentException("Project and track layout required");
        for(StudioTrackLayout.Track track:layout.tracks()){
            for(StudioTrackLayout.Item item:track.items()){
                ProjectStore.Asset asset=project.asset(item.assetId);
                if(asset==null||asset.mime==null)
                    throw new IllegalArgumentException("Track references missing imported asset");
                boolean audio=asset.mime.startsWith("audio/");
                boolean video=asset.mime.startsWith("video/");
                boolean image=asset.mime.startsWith("image/");
                if(track.kind==StudioTrackLayout.Kind.AUDIO&&!audio&&!video)
                    throw new IllegalArgumentException("Audio lane requires audio or video source");
                if(track.kind==StudioTrackLayout.Kind.OVERLAY&&!image&&!video)
                    throw new IllegalArgumentException("Overlay lane requires image or video");
                if(track.kind==StudioTrackLayout.Kind.AUDIO &&
                        (asset.durationMs<=0||item.sourceInMs>=asset.durationMs||
                        item.sourceInMs+item.durationMs>asset.durationMs))
                    throw new IllegalArgumentException("Audio clip exceeds source media duration");
                if(track.kind==StudioTrackLayout.Kind.AUDIO&&
                        item.startMs>=project.outputDurationMs())
                    throw new IllegalArgumentException("Audio starts after the video ends");
            }
        }
    }

    public static ProjectStore.Project commit(ProjectStore store,ProjectStore.Project project,
                              StudioTrackLayout layout) throws Exception {
        if(store==null||project==null||layout==null)
            throw new IllegalArgumentException("Active project and track layout required");
        validate(project,layout);
        JSONObject json=layout.toJson();
        ProjectStore.Project existing=store.get(project.id);
        if(existing==null)throw new IllegalArgumentException("Project was deleted");
        // Always merge into the freshest native project so that an export or
        // ChatGPT animation generated meanwhile is NOT overwritten by UI edits.
        existing.trackLayout=json;
        store.save(existing);
        return existing;
    }

    public static StudioTrackLayout.Item addMedia(StudioTrackLayout layout,
                        ProjectStore.Project project,String trackId,String assetId,
                        long startMs,long durationMs) throws Exception {
        if(layout==null||project==null)throw new IllegalArgumentException("Project required");
        StudioTrackLayout.Track track=layout.track(trackId);
        ProjectStore.Asset asset=project.asset(assetId);
        if(track==null||asset==null||asset.mime==null)
            throw new IllegalArgumentException("Track or imported media not found");
        String mime=asset.mime;
        boolean video=mime.startsWith("video/");
        boolean audio=mime.startsWith("audio/");
        boolean image=mime.startsWith("image/");
        if(track.kind==StudioTrackLayout.Kind.AUDIO&&!audio&&!video)
            throw new IllegalArgumentException("Audio track accepts only imported audio/video");
        if(track.kind==StudioTrackLayout.Kind.OVERLAY&&!video&&!image)
            throw new IllegalArgumentException("Overlay track accepts images/videos");
        if(track.kind!=StudioTrackLayout.Kind.AUDIO&&
                track.kind!=StudioTrackLayout.Kind.OVERLAY)
            throw new IllegalArgumentException("Use the main Editor for this track type");
        if(startMs<0||durationMs<=0)
            throw new IllegalArgumentException("Invalid clip timing");
        long duration=Math.min(durationMs,Math.max(1,asset.durationMs));
        if(image)duration=durationMs;
        if(project.outputDurationMs()>0 &&
                startMs>=project.outputDurationMs())
            throw new IllegalArgumentException("Choose a position inside the video timeline");
        StudioTrackLayout.Item item=new StudioTrackLayout.Item(
                UUID.randomUUID().toString(),asset.id,startMs,0,duration,1f);
        track.add(item);
        return item;
    }

    public static StudioTrackLayout.Track additionalAudioLane(
                            StudioTrackLayout layout){
        if(layout==null)throw new IllegalArgumentException("Tracks required");
        int count=1;
        while(layout.track("audio-"+count)!=null)count++;
        return layout.addTrack("audio-"+count,StudioTrackLayout.Kind.AUDIO);
    }

    public static boolean containsRenderableAudio(StudioTrackLayout layout){
        if(layout==null)return false;
        for(StudioTrackLayout.Track t:layout.tracks())
            if(t.kind==StudioTrackLayout.Kind.AUDIO&&!t.items().isEmpty())return true;
        return false;
    }

    public static int audioClipCount(StudioTrackLayout layout) {
        if(layout==null)return 0;
        int count=0;
        for(StudioTrackLayout.Track t:layout.tracks())
            if(t.kind==StudioTrackLayout.Kind.AUDIO)count+=t.items().size();
        return count;
    }
}
