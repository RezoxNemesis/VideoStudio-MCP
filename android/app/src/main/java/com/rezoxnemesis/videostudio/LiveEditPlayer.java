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

import java.util.ArrayList;
import java.util.List;

/**
 * Reusable Media3 editor player. Playback lives independently from editor view
 * rebuilds and from autonomous render/generation jobs.
 */
public final class LiveEditPlayer {
    private final ExoPlayer player;
    private final PlayerView view;
    private String snapshotId = "";
    private String clipId = "";
    private final Handler handler = new Handler(Looper.getMainLooper());
    private Runnable clipBoundaryWatcher;

    public LiveEditPlayer(Context context) {
        Context app = context.getApplicationContext();
        player = new ExoPlayer.Builder(app).build();
        view = new PlayerView(context);
        view.setUseController(true);
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
        MediaItem item = MediaItem.fromUri(uri);
        player.setMediaItem(item, Math.max(0L, positionMs));
        player.setPlaybackSpeed(Math.max(.25f, Math.min(4f, speed)));
        player.prepare();
        player.setPlayWhenReady(playWhenReady);
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
        List<MediaItem> safe = items == null ? new ArrayList<>() : new ArrayList<>(items);
        player.setMediaItems(safe, true);
        player.prepare();
    }

    public LivePlaybackState snapshotState() {
        String uri = "";
        MediaItem item = player.getCurrentMediaItem();
        if (item != null && item.localConfiguration != null && item.localConfiguration.uri != null) {
            uri = item.localConfiguration.uri.toString();
        }
        return new LivePlaybackState(
                uri,
                Math.max(0L, player.getCurrentPosition()),
                player.getPlayWhenReady(),
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
        return Math.max(0L, player.getCurrentPosition());
    }

    public long currentPositionMs() {
        return getCurrentPosition();
    }

    public boolean isPlaying() {
        return player.isPlaying();
    }

    public boolean hasMedia() {
        return player.getMediaItemCount() > 0;
    }

    public void setVisible(boolean visible) {view.setVisibility(visible?android.view.View.VISIBLE:android.view.View.GONE);}
    public void setVolume(float volume) {player.setVolume(Math.max(0,Math.min(1,volume)));}

    public void pause() {
        player.pause();
    }

    public void stop() {
        cancelClipBoundaryWatcher();
        player.stop();
    }

    public void seekTo(long positionMs) {
        player.seekTo(Math.max(0L, positionMs));
    }

    public void release() {
        cancelClipBoundaryWatcher();
        view.setPlayer(null);
        player.release();
    }

    private void cancelClipBoundaryWatcher() {
        if (clipBoundaryWatcher != null) {
            handler.removeCallbacks(clipBoundaryWatcher);
            clipBoundaryWatcher = null;
        }
    }
}
