package com.rezoxnemesis.videostudio;

import android.content.Context;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.view.ViewGroup;

import androidx.media3.common.MediaItem;
import androidx.media3.common.Player;
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
        if(editPlayer!=null)editPlayer.pause();
        editedPreview=false;
        view.setPlayer(player);
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
        // Construct before disturbing a working preview when preparation rejects the edit.
        Composition composition=revision.equals(editRevision)&&editPlayer!=null?null:
                engine.buildPreviewComposition(project,aspect,quality);
        cancelClipBoundaryWatcher();player.pause();editError=onError;
        if(editPlayer==null){
            editPlayer=new CompositionPlayer.Builder(context).build();
            editPlayer.addListener(new Player.Listener(){
                @Override public void onPlayerError(PlaybackException error){
                    cancelClipBoundaryWatcher();
                    if(editError!=null)editError.accept("Edited preview unavailable: "+error.getMessage());
                }
            });
        }
        editedPreview=true;view.setPlayer(editPlayer);
        long offset=Math.max(0,Math.min(Math.max(0,project.outputDurationMs()-1),outputPositionMs));
        if(composition!=null){
            editPlayer.setComposition(composition,offset);editPlayer.prepare();editRevision=revision;
        }else editPlayer.seekTo(offset);
        editPlayer.setVolume(1f);editPlayer.setPlayWhenReady(playWhenReady);
        if(onComplete!=null){
            clipBoundaryWatcher=new Runnable(){
                private boolean completed;
                @Override public void run(){
                    if(completed||!editedPreview)return;
                    if(editPlayer.getPlaybackState()==Player.STATE_ENDED){
                        completed=true;editPlayer.pause();onComplete.run();return;
                    }
                    handler.postDelayed(this,60);
                }
            };
            handler.post(clipBoundaryWatcher);
        }
    }
    public boolean isEditedPreview(){return editedPreview;}
    private Player activePlayer(){return editedPreview&&editPlayer!=null?editPlayer:player;}

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
        if(editPlayer!=null)editPlayer.pause();editedPreview=false;view.setPlayer(player);
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
        activePlayer().pause();
    }

    public void stop() {
        cancelClipBoundaryWatcher();
        activePlayer().stop();
        editRevision="";
    }

    public void seekTo(long positionMs) {
        activePlayer().seekTo(Math.max(0L, positionMs));
    }

    public void release() {
        cancelClipBoundaryWatcher();
        view.setPlayer(null);
        player.release();
        if(editPlayer!=null)editPlayer.release();
    }

    private void cancelClipBoundaryWatcher() {
        if (clipBoundaryWatcher != null) {
            handler.removeCallbacks(clipBoundaryWatcher);
            clipBoundaryWatcher = null;
        }
    }
}
