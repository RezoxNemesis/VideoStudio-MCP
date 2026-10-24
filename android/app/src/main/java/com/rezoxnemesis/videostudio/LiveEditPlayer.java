package com.rezoxnemesis.videostudio;

import android.content.Context;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.view.ViewGroup;

import androidx.media3.common.MediaItem;
import androidx.media3.common.C;
import androidx.media3.common.Player;
import androidx.media3.common.VideoCompositorSettings;
import androidx.media3.effect.MultipleInputVideoGraph;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.ui.PlayerView;
import androidx.media3.transformer.Composition;
import androidx.media3.transformer.CompositionPlayer;
import androidx.media3.common.PlaybackException;

import java.util.ArrayList;
import java.util.List;

/**
 * Reusable Media3 editor player. Playback lives independently from editor view
 * rebuilds and from autonomous render/generation jobs.
 */
public final class LiveEditPlayer {
    private final ExoPlayer player;
    private final PlayerView view;
    private final Context context;
    private CompositionPlayer editPlayer;
    private boolean editedPreview;
    private String editRevision="";
    private java.util.function.Consumer<String> editError;
    private Player fallbackPlayer;
    private boolean fallbackPlayWhenReady;
    private String fallbackRevision="",fallbackSnapshotId="",fallbackClipId="";
    private java.util.function.Consumer<String> fallbackEditError;
    private Runnable fallbackBoundaryWatcher;
    private boolean settingPlaybackIntent;
    private String snapshotId = "";
    private String clipId = "";
    private final Handler handler = new Handler(Looper.getMainLooper());
    private Runnable clipBoundaryWatcher;

    public LiveEditPlayer(Context context) {
        Context app = context.getApplicationContext();
        this.context=app;
        player = new ExoPlayer.Builder(app).build();
        view = new PlayerView(context);
        view.setUseController(true);view.setShowBuffering(PlayerView.SHOW_BUFFERING_ALWAYS);
        view.setPlayer(player);
    }

    public void attach(ViewGroup host) {
        if (host == null) return;
        if (view.getParent() instanceof ViewGroup) {
            ((ViewGroup) view.getParent()).removeView(view);
        }
        host.addView(view, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
        ));
    }

    public void play(Uri uri, long positionMs) {
        play(uri, positionMs, 1f, true);
    }

    public void play(Uri uri, long positionMs, float speed, boolean playWhenReady) {
        if (uri == null) return;
        cancelClipBoundaryWatcher();
        editedPreview=false;
        view.setPlayer(player);
        discardEditedPlayers();
        MediaItem item = MediaItem.fromUri(uri);
        player.setMediaItem(item, Math.max(0L, positionMs));
        player.setPlaybackSpeed(Math.max(.25f, Math.min(4f, speed)));
        player.prepare();
        player.setPlayWhenReady(playWhenReady);
    }

    /** Real edited playback uses the same Composition that NativeRenderEngine exports. */
    public void playEdit(ProjectStore.Project project,NativeRenderEngine engine,String aspect,
                         String quality,long outputPositionMs,boolean playWhenReady,
                         Runnable onComplete,java.util.function.Consumer<String> onError)throws Exception {
        String revision=project.id+":"+project.updatedAt+":"+aspect+":"+quality;
        Composition composition=editedPreview&&revision.equals(editRevision)&&editPlayer!=null?null:
                engine.buildPreviewComposition(project,aspect,quality);
        long offset=Math.max(0,Math.min(Math.max(0,project.outputDurationMs()-1),outputPositionMs));
        if(composition!=null){
            // Export may discover unknown source metadata; interactive admission cannot.
            for(var sequence:composition.sequences)for(var item:sequence.editedMediaItems){
                if(item.durationUs==C.TIME_UNSET||item.durationUs<=0)
                    throw new IllegalArgumentException("Source duration unavailable. Re-import the source to refresh its metadata, then Retry preview.");
                try{item.getPresentationDurationUs();}
                catch(IllegalArgumentException error){throw new IllegalArgumentException("Clip timing exceeds the known source duration. Adjust the trim, then Retry preview.",error);}
            }
            Player previous=activePlayer();
            boolean previousReady=previous.getPlayWhenReady();
            Runnable previousWatcher=clipBoundaryWatcher;
            java.util.function.Consumer<String> previousError=editError;
            // Keep the current preview for rollback, but retire its older fallback
            // before a candidate can initialize another graph on the playback thread.
            releaseFallback();
            CompositionPlayer.Builder candidateBuilder=new CompositionPlayer.Builder(context);
            if(needsMultipleInputVideoGraph(composition))
                candidateBuilder.setVideoGraphFactory(new MultipleInputVideoGraph.Factory());
            CompositionPlayer candidate=candidateBuilder.build();
            candidate.addListener(new Player.Listener(){
                @Override public void onPlayWhenReadyChanged(boolean ready,int reason){
                    // PlayerView controls call Player directly. Initial app setup must
                    // not replace the previous preview's independently saved intent.
                    if(editPlayer==candidate&&fallbackPlayer!=null&&!settingPlaybackIntent&&
                            reason==Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
                        fallbackPlayWhenReady=ready;
                }
                @Override public void onPlayerError(PlaybackException error){
                    if(editPlayer!=candidate)return;
                    java.util.function.Consumer<String> failedCallback=editError;
                    cancelClipBoundaryWatcher();
                    if(fallbackPlayer!=null){
                        Player restore=fallbackPlayer;
                        boolean ready=fallbackPlayWhenReady;
                        Runnable resume=fallbackBoundaryWatcher;
                        editPlayer=restore instanceof CompositionPlayer?(CompositionPlayer)restore:null;
                        editedPreview=editPlayer!=null;editRevision=fallbackRevision;
                        snapshotId=fallbackSnapshotId;clipId=fallbackClipId;editError=fallbackEditError;
                        clearFallback();
                        view.setPlayer(restore);setPlaybackIntent(restore,ready);candidate.release();
                        clipBoundaryWatcher=resume;
                        if(resume!=null)handler.post(resume);
                    }else{
                        // An older fallback may already have been released. Keep the
                        // current context rather than restoring an unrelated source.
                        candidate.pause();editRevision="";
                    }
                    if(failedCallback!=null)failedCallback.accept("Edited preview unavailable: "+error.getMessage()+". Retry preview after checking the source.");
                }
            });
            try{
                candidate.setComposition(composition,offset);candidate.prepare();
                if(candidate.getPlayerError()!=null)throw candidate.getPlayerError();
                candidate.setVolume(1f);
                view.setPlayer(candidate);
                // Binding can expose an SDK error synchronously, before adoption.
                if(candidate.getPlayerError()!=null)throw candidate.getPlayerError();
            }catch(Exception error){
                view.setPlayer(previous);candidate.release();throw error;
            }
            cancelClipBoundaryWatcher();
            fallbackPlayer=previous;fallbackPlayWhenReady=previousReady;
            fallbackRevision=editRevision;fallbackSnapshotId=snapshotId;fallbackClipId=clipId;
            fallbackEditError=previousError;fallbackBoundaryWatcher=previousWatcher;
            setPlaybackIntent(previous,false);editPlayer=candidate;editedPreview=true;editRevision=revision;
        }else editPlayer.seekTo(offset);
        cancelClipBoundaryWatcher();editError=onError;
        editPlayer.setVolume(1f);setPlaybackIntent(editPlayer,playWhenReady);
        if(onComplete!=null){
            clipBoundaryWatcher=new Runnable(){
                private boolean completed;
                @Override public void run(){
                    if(completed||!editedPreview)return;
                    if(editPlayer.getPlaybackState()==Player.STATE_ENDED){
                        completed=true;setPlaybackIntent(editPlayer,false);onComplete.run();return;
                    }
                    handler.postDelayed(this,60);
                }
            };
            handler.post(clipBoundaryWatcher);
        }
    }
    public boolean isEditedPreview(){return editedPreview;}
    private Player activePlayer(){return editedPreview&&editPlayer!=null?editPlayer:player;}

    private static boolean needsMultipleInputVideoGraph(Composition composition){
        // The SDK default supports one video input and DEFAULT settings only.
        // Independent audio lanes and final IMAGE/TITLE effects keep that path.
        if(composition.videoCompositorSettings!=VideoCompositorSettings.DEFAULT)return true;
        int videoInputs=0;
        for(var sequence:composition.sequences)
            if(sequence.trackTypes.contains(C.TRACK_TYPE_VIDEO)&&++videoInputs>1)return true;
        return false;
    }

    private void setPlaybackIntent(Player target,boolean ready){
        boolean previous=settingPlaybackIntent;settingPlaybackIntent=true;
        try{target.setPlayWhenReady(ready);}finally{settingPlaybackIntent=previous;}
    }

    private void releaseFallback(){
        Player expired=fallbackPlayer;
        clearFallback();
        if(expired instanceof CompositionPlayer&&expired!=editPlayer)expired.release();
    }

    private void clearFallback(){
        fallbackPlayer=null;
        fallbackPlayWhenReady=false;
        fallbackRevision="";fallbackSnapshotId="";fallbackClipId="";
        fallbackEditError=null;fallbackBoundaryWatcher=null;
    }

    private void discardEditedPlayers(){
        CompositionPlayer previous=editPlayer;editPlayer=null;editRevision="";editError=null;
        releaseFallback();
        if(previous!=null)previous.release();
    }

    public void playClip(Uri uri,
                         long inMs,
                         long outMs,
                         float speed,
                         Runnable onComplete) {
        cancelClipBoundaryWatcher();
        play(uri, inMs, speed, true);
        long safeOut = Math.max(inMs + 1L, outMs);
        clipBoundaryWatcher = new Runnable() {
            private boolean completed;
            @Override public void run() {
                if (completed) return;
                if (player.getPlaybackState() == Player.STATE_ENDED || player.getCurrentPosition() >= safeOut) {
                    completed = true;
                    player.pause();
                    if (onComplete != null) onComplete.run();
                    return;
                }
                handler.postDelayed(this, 60L);
            }
        };
        handler.post(clipBoundaryWatcher);
    }

    public void setPlaylist(List<MediaItem> items) {
        cancelClipBoundaryWatcher();editedPreview=false;view.setPlayer(player);discardEditedPlayers();
        List<MediaItem> safe = items == null ? new ArrayList<>() : new ArrayList<>(items);
        player.setMediaItems(safe, true);
        player.prepare();
    }

    public LivePlaybackState snapshotState() {
        String uri = "";
        // Composition position is output-local and cannot be replayed as a source URI.
        MediaItem item = editedPreview?null:activePlayer().getCurrentMediaItem();
        if (item != null && item.localConfiguration != null && item.localConfiguration.uri != null) {
            uri = item.localConfiguration.uri.toString();
        }
        return new LivePlaybackState(
                uri,
                Math.max(0L, activePlayer().getCurrentPosition()),
                activePlayer().getPlayWhenReady(),
                snapshotId,
                clipId
        );
    }

    public void restoreState(LivePlaybackState state) {
        if (state == null) return;
        snapshotId = state.snapshotId;
        clipId = state.clipId;
        if (!state.mediaUri.isEmpty()) {
            play(Uri.parse(state.mediaUri), state.positionMs, 1f, state.playWhenReady);
        }
    }

    public void setContextIds(String snapshotId, String clipId) {
        this.snapshotId = snapshotId == null ? "" : snapshotId;
        this.clipId = clipId == null ? "" : clipId;
    }

    public long getCurrentPosition() {
        return Math.max(0L, activePlayer().getCurrentPosition());
    }

    public long currentPositionMs() {
        return getCurrentPosition();
    }

    public boolean isPlaying() {
        return activePlayer().isPlaying();
    }

    public boolean hasMedia() {
        return activePlayer().getMediaItemCount() > 0;
    }

    public void setVolume(float gain){
        activePlayer().setVolume(Math.max(0f,Math.min(1f,gain)));
    }

    public void pause() {
        fallbackPlayWhenReady=false;
        activePlayer().pause();
    }

    public void stop() {
        cancelClipBoundaryWatcher();
        activePlayer().stop();
        fallbackPlayWhenReady=false;
        editRevision="";
    }

    public void seekTo(long positionMs) {
        activePlayer().seekTo(Math.max(0L, positionMs));
    }

    public void release() {
        cancelClipBoundaryWatcher();
        view.setPlayer(null);
        editedPreview=false;
        discardEditedPlayers();
        player.release();
    }

    private void cancelClipBoundaryWatcher() {
        if (clipBoundaryWatcher != null) {
            handler.removeCallbacks(clipBoundaryWatcher);
            clipBoundaryWatcher = null;
        }
    }
}
