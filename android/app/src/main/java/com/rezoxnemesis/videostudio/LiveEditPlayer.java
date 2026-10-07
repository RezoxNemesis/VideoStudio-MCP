package com.rezoxnemesis.videostudio;

import android.content.Context;
import android.net.Uri;
import android.view.ViewGroup;

import androidx.media3.common.MediaItem;
import androidx.media3.common.Player;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.ui.PlayerView;

import java.util.List;

/**
 * Reusable editor playback surface.
 *
 * Player ownership is independent from rendering/AI jobs so editor rebuilds do
 * not tear down active playback or cancel background autonomous work.
 */
@UnstableApi
public final class LiveEditPlayer {
    private final ExoPlayer player;
    private final PlayerView view;
    private Player.Listener endListener;
    private String mediaUri = "";
    private String snapshotId = "";
    private String clipId = "";

    public LiveEditPlayer(Context context) {
        Context app = context.getApplicationContext();
        player = new ExoPlayer.Builder(app).build();
        view = new PlayerView(context);
        view.setPlayer(player);
        view.setUseController(true);
        view.setControllerAutoShow(true);
        view.setKeepContentOnPlayerReset(true);
    }

    public void attach(ViewGroup host) {
        if (host == null) return;
        if (view.getParent() instanceof ViewGroup) {
            ((ViewGroup) view.getParent()).removeView(view);
        }
        host.addView(view, 0, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
        ));
    }

    public void play(Uri uri, long positionMs) {
        if (uri == null) return;
        clearEndListener();
        mediaUri = uri.toString();
        player.setMediaItem(MediaItem.fromUri(uri));
        player.prepare();
        player.seekTo(Math.max(0L, positionMs));
        player.setPlaybackSpeed(1f);
        player.play();
    }

    public void playClip(Uri uri,
                         long startMs,
                         long endMs,
                         float speed,
                         Runnable onEnded) {
        if (uri == null) return;
        clearEndListener();
        mediaUri = uri.toString();
        long safeStart = Math.max(0L, startMs);
        long safeEnd = Math.max(safeStart + 100L, endMs);
        MediaItem item = new MediaItem.Builder()
                .setUri(uri)
                .setClippingConfiguration(new MediaItem.ClippingConfiguration.Builder()
                        .setStartPositionMs(safeStart)
                        .setEndPositionMs(safeEnd)
                        .build())
                .build();
        player.setMediaItem(item);
        player.prepare();
        player.seekTo(0L);
        player.setPlaybackSpeed(Math.max(.25f, Math.min(4f, speed)));
        if (onEnded != null) {
            endListener = new Player.Listener() {
                @Override public void onPlaybackStateChanged(int playbackState) {
                    if (playbackState == Player.STATE_ENDED) {
                        clearEndListener();
                        onEnded.run();
                    }
                }
            };
            player.addListener(endListener);
        }
        player.play();
    }

    public void setPlaylist(List<MediaItem> items) {
        clearEndListener();
        if (items == null || items.isEmpty()) {
            player.clearMediaItems();
            mediaUri = "";
            return;
        }
        player.setMediaItems(items);
        player.prepare();
        MediaItem first = items.get(0);
        mediaUri = first.localConfiguration == null || first.localConfiguration.uri == null
                ? ""
                : first.localConfiguration.uri.toString();
    }

    public void setContextIds(String snapshotId, String clipId) {
        this.snapshotId = snapshotId == null ? "" : snapshotId;
        this.clipId = clipId == null ? "" : clipId;
    }

    public LivePlaybackState snapshotState() {
        return new LivePlaybackState(
                mediaUri,
                Math.max(0L, player.getCurrentPosition()),
                player.getPlayWhenReady(),
                snapshotId,
                clipId
        );
    }

    public void restoreState(LivePlaybackState state) {
        if (state == null || state.mediaUri.isEmpty()) return;
        clearEndListener();
        mediaUri = state.mediaUri;
        snapshotId = state.snapshotId;
        clipId = state.clipId;
        player.setMediaItem(MediaItem.fromUri(Uri.parse(state.mediaUri)));
        player.prepare();
        player.seekTo(state.positionMs);
        player.setPlayWhenReady(state.playWhenReady);
    }

    public boolean hasMedia() {
        return !mediaUri.isEmpty() && player.getMediaItemCount() > 0;
    }

    public boolean isPlaying() {
        return player.isPlaying();
    }

    public long currentPositionMs() {
        return Math.max(0L, player.getCurrentPosition());
    }

    public void pause() {
        player.pause();
    }

    public void release() {
        clearEndListener();
        view.setPlayer(null);
        player.release();
    }

    private void clearEndListener() {
        if (endListener != null) {
            player.removeListener(endListener);
            endListener = null;
        }
    }
}
