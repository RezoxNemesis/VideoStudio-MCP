package com.rezoxnemesis.videostudio;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Canvas;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.LruCache;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.view.Gravity;

import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.C;
import androidx.media3.common.audio.AudioProcessor;
import androidx.media3.exoplayer.audio.AudioSink;
import androidx.media3.exoplayer.audio.DefaultAudioSink;
import androidx.media3.exoplayer.DefaultRenderersFactory;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.ui.PlayerView;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** Live source/program monitor. It never waits for an offline render to show media. */
public final class LiveEditPlayer {
    public interface Listener {
        void onPosition(long positionMs, long durationMs, boolean playing);
        void onError(String message);
        void onReady();
    }
    // Hard bounds keep a timeline with many layers from exhausting hardware decoders.
    private static final int MAX_VIDEO_LAYERS = 2;
    private static final int MAX_VISUAL_LAYERS = 8;
    private static final int MAX_AUDIO_LAYERS = 4;
    private final Context context;
    private final FrameLayout monitor;
    private final FrameLayout programCanvas;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ThreadPoolExecutor imageLoader = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(16), task -> new Thread(task, "VideoStudio-monitor-images"));
    private final LruCache<String, Bitmap> images = new LruCache<String, Bitmap>(16 * 1024 * 1024) {
        @Override protected int sizeOf(String key, Bitmap bitmap) { return bitmap.getAllocationByteCount(); }
    };
    private final LinkedHashMap<String, Layer> programLayers = new LinkedHashMap<>();
    private final LinkedHashMap<String, String> notices = new LinkedHashMap<>();
    private String noticeScope = "";
    private Layer source;
    private ProjectStore.Project project;
    private MonitorProjectIndex projectIndex;
    private long programDurationMs;
    private Listener listener;
    private boolean programMode;
    private boolean playing;
    private boolean released;
    private boolean ticking;
    private long positionMs;
    private long sourceDurationMs;
    private long lastTick;
    private long bindingGeneration;
    private long boundaryOutMs = Long.MAX_VALUE;
    private Runnable onBoundary;
    private String snapshotId = "";
    private String clipId = "";
    private String programAspect = "9:16";
    private String programQuality = "720p";
    private String previewProxyTier = "auto";
    private int previewThermalStatus;
    private long thermalReadAtMs;
    private boolean loop;
    private boolean playRangeEnabled;
    private long playRangeInMs, playRangeOutMs;
    private float shuttleRate = 1f;
    private double clockRemainder;

    private final class Layer {
        final String key;
        final String uri;
        final boolean image;
        final boolean audioOnly;
        final String dspSignature;
        final AudioDspProcessor dsp;
        final FrameLayout host;
        final ImageView still;
        final ExoPlayer player;
        final CaptionView title;
        String effectSignature = "";
        long generation = -1L;
        MotionTimeline motion;
        StillProgramRenderer gpu;
        String gpuSignature = "";
        String gpuFailure = "";
        boolean failed;
        Future<?> imageFuture;
        boolean removed;
        Layer(String key, String uri, boolean image, boolean audioOnly) { this(key, uri, image, audioOnly, null); }
        Layer(String key, String uri, boolean image, boolean audioOnly, ProjectStore.Clip clip) {
            this(key, uri, image, audioOnly, clip, false);
        }
        Layer(String key, String uri, boolean image, boolean audioOnly, ProjectStore.Clip clip, boolean bypassAudioEffects) {
            this.key = key;
            this.uri = uri;
            this.image = image;
            this.audioOnly = audioOnly;
            dspSignature = audioSignature(clip);
            dsp = image ? null : clip == null || bypassAudioEffects ? new AudioDspProcessor((org.json.JSONObject) null, true)
                    : new AudioDspProcessor(clip, clip.effectiveSpeed());
            host = new FrameLayout(monitor.getContext());
            host.setClipChildren(false);
            if (image) {
                player = null;
                still = new ImageView(monitor.getContext());
                still.setScaleType(ImageView.ScaleType.FIT_CENTER);
                host.addView(still, fill());
                loadImage(this);
            } else {
                still = null;
                player = new ExoPlayer.Builder(context)
                        .setRenderersFactory(new DefaultRenderersFactory(context) {
                            @Override protected AudioSink buildAudioSink(Context sinkContext, boolean enableFloatOutput,
                                                                          boolean enableAudioOutputPlaybackParams) {
                                return new DefaultAudioSink.Builder(sinkContext)
                                        .setEnableFloatOutput(false)
                                        .setEnableAudioOutputPlaybackParameters(false)
                                        .setAudioProcessors(new AudioProcessor[]{dsp})
                                        .build();
                            }
                        }.setEnableDecoderFallback(true))
                        .build();
                if (audioOnly) player.setTrackSelectionParameters(player.getTrackSelectionParameters().buildUpon()
                        .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, true).build());
                if (!audioOnly) {
                    PlayerView view = (PlayerView) LayoutInflater.from(monitor.getContext())
                            .inflate(R.layout.studio_preview_player, host, false);
                    view.setPlayer(player);
                    view.setUseController(false);
                    view.setShutterBackgroundColor(android.graphics.Color.TRANSPARENT);
                    view.setBackgroundColor(android.graphics.Color.TRANSPARENT);
                    View surfaceView = view.getVideoSurfaceView();
                    if (surfaceView instanceof android.view.TextureView)
                        ((android.view.TextureView) surfaceView).setOpaque(false);
                    host.addView(view, fill());
                }
                player.addListener(new Player.Listener() {
                    @Override public void onPlaybackStateChanged(int state) {
                        if (!removed && state == Player.STATE_READY && listener != null) listener.onReady();
                    }
                    @Override public void onPlayerError(PlaybackException error) {
                        if (!removed) {
                            failed = true;
                            ProjectStore.Clip clip = programMode && projectIndex != null ? projectIndex.clip(key) : null;
                            ProjectStore.Asset asset = clip == null ? null : projectIndex.asset(clip.assetId);
                            if (asset != null && !uri.equals(ProxyManager.originalUri(asset))) {
                                ProxyManager.invalidatePreview(uri);
                                report("proxy:" + uri, "Preview cache failed to decode; switching to the original media.");
                                handler.post(() -> { if (!released && programMode) updateProgram(true); });
                                return;
                            }
                            pause();
                            report("preview:" + uri, "Playback paused: could not decode this media (" + error.getErrorCodeName() + "). Relink or convert it to continue.");
                        }
                    }
                });
                player.setMediaItem(MediaItem.fromUri(Uri.parse(uri)));
                player.prepare();
            }
            title = new CaptionView(monitor.getContext());
            title.setVisibility(View.GONE);
            host.addView(title, fill());
        }
        void close() {
            removed = true;
            if (gpu != null) { gpu.close(); gpu = null; }
            if (imageFuture != null) imageFuture.cancel(false);
            if (player != null) player.release();
            if (host.getParent() instanceof ViewGroup) ((ViewGroup) host.getParent()).removeView(host);
            if (still != null) still.setImageDrawable(null);
        }
    }

    private static final class CaptionView extends View {
        NativeTextOverlay overlay;
        long localUs;
        CaptionView(Context context) { super(context); }
        void bind(ProjectStore.Clip clip) {
            org.json.JSONObject fx = clip.effects == null ? new org.json.JSONObject() : clip.effects;
            overlay = new NativeTextOverlay(clip.title, fx, 0L, 1f,
                    Math.max(1L, fx.optLong("animationDurationMs", clip.outputDurationMs())) * 1000L);
        }
        void bindFallback(String text, long durationMs) {
            overlay = new NativeTextOverlay(text, new org.json.JSONObject(), 0L, 1f, Math.max(1L, durationMs) * 1000L);
        }
        void setTime(long localUs) { this.localUs = localUs; invalidate(); }
        @Override protected void onDraw(Canvas canvas) {
            if (overlay == null) return;
            // The shared overlay clears its canvas. Isolate that clear so lower
            // project layers remain visible beneath the transparent title.
            int save = canvas.saveLayer(0f, 0f, getWidth(), getHeight(), null);
            overlay.onDraw(canvas, localUs);
            canvas.restoreToCount(save);
        }
    }

    public LiveEditPlayer(Context context) {
        this.context = context.getApplicationContext();
        monitor = new FrameLayout(context);
        monitor.setBackgroundColor(android.graphics.Color.BLACK);
        monitor.setClipChildren(true);
        programCanvas = new FrameLayout(context);
        programCanvas.setClipChildren(true);
        programCanvas.setVisibility(View.GONE);
        monitor.addView(programCanvas, new FrameLayout.LayoutParams(1, 1, Gravity.CENTER));
        monitor.addOnLayoutChangeListener((view, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> updateProgramBounds());
    }
    private static ViewGroup.LayoutParams fill() {
        return new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
    }
    public void setListener(Listener listener) { this.listener = listener; }
    public List<String> currentWarnings() { return new ArrayList<>(notices.values()); }
    public List<EditorAudioMeterView.Level> audioLevels() {
        List<EditorAudioMeterView.Level> result=new ArrayList<>();
        if (!playing || shuttleRate<0) return result;
        if (!programMode && source!=null && source.dsp!=null) addAudioLevel(result,source,"Source");
        else if (programMode && project!=null) for (Layer layer:programLayers.values()) {
            if (layer.dsp==null || layer.player.getVolume()==0) continue;
            ProjectStore.Clip clip=projectIndex.clip(layer.key);
            ProjectStore.Asset asset=clip==null?null:projectIndex.asset(clip.assetId);
            if (asset!=null&&!asset.hasAudio&&!layer.audioOnly) continue;
            ProjectStore.Track track=clip==null?null:projectIndex.track(clip.trackId);
            String name=track==null?"Audio":track.name;
            addAudioLevel(result,layer,name);
        }
        return result;
    }
    private static void addAudioLevel(List<EditorAudioMeterView.Level> target,Layer layer,String label) {
        AudioDspProcessor.Meter meter=layer.dsp.meter();
        target.add(new EditorAudioMeterView.Level(label,meter.left,meter.right,meter.limited));
    }
    private void bindNoticeScope(String next) {
        if (!next.equals(noticeScope)) { notices.clear(); noticeScope = next; }
    }
    public void attach(ViewGroup host) {
        if (host == null || released) return;
        if (monitor.getParent() == host) return;
        if (monitor.getParent() instanceof ViewGroup) ((ViewGroup) monitor.getParent()).removeView(monitor);
        host.addView(monitor, 0, fill());
    }

    public void setProgramFormat(String aspect, String quality) {
        String nextAspect = aspect == null ? "9:16" : aspect;
        String nextQuality = quality == null ? "720p" : quality;
        if (nextAspect.equals(programAspect) && nextQuality.equals(programQuality)) return;
        programAspect = nextAspect;
        programQuality = nextQuality;
        updateProgramBounds();
        bindingGeneration++;
        if (programMode && project != null) updateProgram(true);
    }
    public void setPreviewProxyTier(String tier) {
        if (!java.util.Arrays.asList("auto", "source", "360p", "540p", "720p").contains(tier))
            throw new IllegalArgumentException("Unsupported preview tier");
        if (tier.equals(previewProxyTier)) return;
        previewProxyTier = tier;
        if (programMode && project != null) updateProgram(true);
    }
    public String previewTier() {
        if (!"auto".equals(previewProxyTier)) return previewProxyTier;
        long now=SystemClock.elapsedRealtime();
        if (now-thermalReadAtMs>2000) {
            android.os.PowerManager power=(android.os.PowerManager)context.getSystemService(Context.POWER_SERVICE);
            previewThermalStatus=power==null?0:power.getCurrentThermalStatus();thermalReadAtMs=now;
        }
        if (previewThermalStatus>=android.os.PowerManager.THERMAL_STATUS_MODERATE || Math.abs(shuttleRate)>1.5f) return "360p";
        return playing ? "540p" : "720p";
    }
    private void updateProgramBounds() {
        int width = monitor.getWidth(), height = monitor.getHeight();
        if (width <= 0 || height <= 0) return;
        int[] output = NativeVideoEffects.outputSize(programAspect, programQuality);
        float ratio = output[0] / (float) Math.max(1, output[1]);
        int fittedWidth = Math.min(width, Math.round(height * ratio));
        int fittedHeight = Math.min(height, Math.round(width / ratio));
        FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) programCanvas.getLayoutParams();
        if (params.width != fittedWidth || params.height != fittedHeight) {
            params.width = fittedWidth;
            params.height = fittedHeight;
            programCanvas.setLayoutParams(params);
        }
    }

    public void previewAsset(ProjectStore.Asset asset, long atMs, boolean playWhenReady) {
        if (asset == null || asset.uri == null || asset.uri.isEmpty()) {
            report("missing-source", "Select readable media to preview");
            return;
        }
        long duration = asset.durationMs > 0 ? asset.durationMs : 5000L;
        showSource(asset.uri, isImage(asset), atMs, 1f, playWhenReady, duration);
    }
    public void play(Uri uri, long atMs) { play(uri, atMs, 1f, true); }
    public void play(Uri uri, long atMs, float speed, boolean playWhenReady) {
        if (uri == null || released) return;
        showSource(uri.toString(), imageUri(uri.toString()), atMs, speed, playWhenReady, 0L);
    }
    private void showSource(String uri, boolean image, long atMs, float speed, boolean playWhenReady, long duration) {
        if (released) return;
        bindNoticeScope("source:" + uri);
        programMode = false;
        programCanvas.setVisibility(View.GONE);
        project = null;
        clearProgram();
        onBoundary = null;
        boundaryOutMs = Long.MAX_VALUE;
        if (source == null || !source.uri.equals(uri) || source.image != image || source.failed) {
            if (source != null) source.close();
            source = new Layer("source", uri, image, false);
            monitor.addView(source.host, fill());
        }
        sourceDurationMs = duration;
        positionMs = Math.max(0L, atMs);
        clockRemainder = 0;
        shuttleRate = 1f;
        playing = playWhenReady;
        lastTick = SystemClock.elapsedRealtime();
        if (source.player != null) {
            source.player.setPlaybackSpeed(Math.max(.1f, Math.min(8f, speed)));
            source.player.seekTo(positionMs);
            source.player.setPlayWhenReady(playing);
        }
        scheduleTick();
        notifyPosition();
    }
    public void playClip(Uri uri, long inMs, long outMs, float speed, Runnable onComplete) {
        play(uri, inMs, speed, true);
        boundaryOutMs = Math.max(inMs + 1L, outMs);
        onBoundary = onComplete;
    }
    public void setPlaylist(List<MediaItem> items) {
        if (items == null || items.isEmpty()) { stop(); return; }
        MediaItem first = items.get(0);
        if (first.localConfiguration == null) return;
        play(first.localConfiguration.uri, 0L);
        if (source != null && source.player != null) {
            source.player.setMediaItems(new ArrayList<>(items), true);
            source.player.prepare();
            source.player.play();
        }
    }

    public void previewProject(ProjectStore.Project project, long atMs, boolean playWhenReady) {
        if (project == null || released) return;
        bindNoticeScope("program:" + project.id + ":" + project.revision);
        // Retain existing layer/decoder bindings on view rebuilds and parameter edits.
        if (!programMode) {
            if (source != null) { source.close(); source = null; }
            clearProgram();
        }
        programMode = true;
        programCanvas.setVisibility(View.VISIBLE);
        updateProgramBounds();
        bindingGeneration++;
        this.project = project;
        projectIndex = new MonitorProjectIndex(project);
        int diagnosticIndex = 0;
        for (String diagnostic : projectIndex.diagnostics())
            report("project-layout:" + diagnosticIndex++, diagnostic);
        programDurationMs = project.outputDurationMs();
        ProjectMarkers.Range range = ProjectMarkers.selection(project);
        playRangeEnabled = range.enabled; playRangeInMs = range.inMs; playRangeOutMs = range.outMs;
        onBoundary = null;
        boundaryOutMs = Long.MAX_VALUE;
        positionMs = Math.max(0L, Math.min(atMs, programDurationMs));
        clockRemainder = 0;
        shuttleRate = 1f;
        playing = playWhenReady && positionMs < programDurationMs;
        if (playing) positionForTransport(1f);
        snapshotId = "";
        lastTick = SystemClock.elapsedRealtime();
        updateProgram(true);
        scheduleTick();
        notifyPosition();
    }
    public void seekProgram(long atMs) {
        if (!programMode || project == null) { seekTo(atMs); return; }
        positionMs = Math.max(0L, Math.min(atMs, programDurationMs));
        // An explicit scrub/marker seek must show the requested frame. Outside
        // an active rehearsal selection, pause instead of playing beyond its
        // bounds or silently redirecting the owner's seek back to In/Out.
        if (playing && validPlayRange()
                && (positionMs < playRangeInMs || positionMs >= playRangeOutMs)) playing = false;
        clockRemainder = 0;
        lastTick = SystemClock.elapsedRealtime();
        updateProgram(true);
        notifyPosition();
    }
    public long programPositionMs() { return getCurrentPosition(); }
    public void setLoop(boolean enabled) { loop = enabled; }
    public boolean isLooping() { return loop; }
    /** Rehearsal bounds affect program transport, not source preview or export. */
    public void setPlayRange(boolean enabled, long inMs, long outMs) {
        if (enabled && (inMs < 0 || outMs <= inMs))
            throw new IllegalArgumentException("Play range requires 0 <= In < Out");
        playRangeEnabled = enabled; playRangeInMs = inMs; playRangeOutMs = outMs;
        if (playing && programMode && project != null) {
            long before = positionMs;
            positionForTransport(shuttleRate);
            if (positionMs != before) {
                clockRemainder = 0; lastTick = SystemClock.elapsedRealtime();
                updateProgram(true); notifyPosition();
            }
        }
    }
    private long transportStartMs() { return validPlayRange() ? playRangeInMs : 0; }
    private long transportEndMs() { return validPlayRange() ? playRangeOutMs : programDurationMs; }
    private boolean validPlayRange() {
        return programMode && project != null && playRangeEnabled && playRangeInMs >= 0
                && playRangeOutMs > playRangeInMs && playRangeOutMs <= programDurationMs;
    }
    private void positionForTransport(float rate) {
        if (!programMode || project == null) return;
        long start = transportStartMs(), end = transportEndMs();
        if (positionMs < start || positionMs >= end) positionMs = rate < 0 ? Math.max(start, end - 1) : start;
        else if (loop && rate < 0 && positionMs == start) positionMs = Math.max(start, end - 1);
    }
    /** Forward shuttle keeps synchronized audio. Reverse shuttle uses paused
     * frame seeks because the decoder does not implement reverse playback. */
    public void shuttle(float rate) {
        if (!Float.isFinite(rate) || Math.abs(rate) > 8f) throw new IllegalArgumentException("Shuttle must be between -8 and 8");
        if (rate == 0f) { pause(); return; }
        if (!hasMedia() || released) return;
        shuttleRate = rate;
        positionForTransport(rate);
        clockRemainder = 0;
        playing = !(programMode && project != null && rate < 0 && !loop && positionMs <= transportStartMs());
        lastTick = SystemClock.elapsedRealtime();
        if (programMode) updateProgram(true);
        else if (source != null && source.player != null) {
            if (rate < 0) source.player.pause();
            else { source.player.setPlaybackSpeed(rate); source.player.play(); }
        }
        scheduleTick();
    }
    public void togglePlayback() {
        if (released || !hasMedia()) return;
        if (playing) { pause(); return; }
        if (!programMode && source != null && source.failed || programMode && programLayers.values().stream().anyMatch(layer -> layer.failed)) {
            report("failed-playback", "Relink or convert the media that failed to decode before resuming this segment.");
            return;
        }
        positionForTransport(1f);
        if (!programMode && source != null && source.player != null && source.player.getPlaybackState() == Player.STATE_ENDED) {
            source.player.seekTo(0L);
        } else if (!programMode && source != null && source.image && positionMs >= sourceDurationMs) {
            positionMs = 0L;
        }
        playing = true;
        shuttleRate = 1f;
        clockRemainder = 0;
        lastTick = SystemClock.elapsedRealtime();
        if (programMode) updateProgram(true);
        else if (source != null && source.player != null) { source.player.setPlaybackSpeed(1f); source.player.play(); }
        scheduleTick();
    }
    public void stepFrame(long deltaMs) {
        pause();
        if (programMode) seekProgram(positionMs + deltaMs);
        else seekTo(getCurrentPosition() + deltaMs);
    }

    private void updateProgram(boolean forceSeek) {
        if (project == null) return;
        long time = positionMs;
        // At the timeline end keep the last actual frame available while paused.
        if (!playing && time > 0L && (time == programDurationMs || validPlayRange() && time == playRangeOutMs)) time--;
        List<ProjectStore.Clip> active = projectIndex.activeAt(time);
        Set<String> retained = new HashSet<>();
        int videos = 0, visuals = 0, audios = 0;
        // Select from the frontmost layer down so the most visible media survives bounds.
        List<ProjectStore.Clip> accepted = new ArrayList<>();
        for (int i = active.size() - 1; i >= 0; i--) {
            ProjectStore.Clip clip = active.get(i);
            ProjectStore.Asset asset = projectIndex.asset(clip.assetId);
            if (asset == null || asset.uri == null || asset.uri.isEmpty()) {
                report("missing:" + clip.id, "Timeline media is missing; relink it in the Media Bin");
                continue;
            }
            ProjectStore.Track track = projectIndex.track(clip.trackId);
            boolean audio = track != null && track.isAudio() || asset.mime != null && asset.mime.startsWith("audio/");
            boolean image = isImage(asset);
            if (audio && !projectIndex.audible(clip.trackId)) continue;
            if ((!audio && visuals >= MAX_VISUAL_LAYERS) || (!audio && !image && videos >= MAX_VIDEO_LAYERS)
                    || (audio && audios >= MAX_AUDIO_LAYERS)) {
                report("layer-limit", "Preview limit reached: two video decoders, eight visual layers and four audio layers. Export evaluates the project separately.");
                continue;
            }
            if (audio) audios++;
            else { visuals++; if (!image) videos++; }
            accepted.add(0, clip);
        }
        for (ProjectStore.Clip clip : accepted) {
            ProjectStore.Asset asset = projectIndex.asset(clip.assetId);
            boolean image = isImage(asset);
            ProjectStore.Track track = projectIndex.track(clip.trackId);
            boolean audio = track != null && track.isAudio() || asset.mime != null && asset.mime.startsWith("audio/");
            String tier=previewTier();
            String uri = "source".equals(tier) ? ProxyManager.originalUri(asset)
                    : ProxyManager.previewUri(context, project, asset, tier);
            Layer layer = programLayers.get(clip.id);
            String dspSignature = layer == null || layer.generation != bindingGeneration
                    ? audioSignature(clip) : layer.dspSignature;
            if (layer != null && (!uri.equals(layer.uri) || layer.image != image || layer.audioOnly != audio
                    || !dspSignature.equals(layer.dspSignature) || layer.failed && layer.generation != bindingGeneration)) {
                layer.close(); programLayers.remove(clip.id); layer = null;
            }
            if (layer == null) {
                try { layer = new Layer(clip.id, uri, image, audio, clip); }
                catch (IllegalArgumentException invalidAudio) {
                    report("audio-effects:" + clip.id,
                            "Source audio is audible; authored audio processing is unavailable: " + invalidAudio.getMessage());
                    layer = new Layer(clip.id, uri, image, audio, clip, true);
                }
                programLayers.put(clip.id, layer);
                if (!audio) programCanvas.addView(layer.host, fill());
                forceSeek = true;
            }
            retained.add(clip.id);
            if (!audio) layer.host.bringToFront();
            String caption = clip.title == null ? "" : clip.title;
            // Video captions come from the same GPU overlay as export; this is
            // only the still-image monitor fallback.
            layer.title.setVisibility(image && !caption.isEmpty() ? View.VISIBLE : View.GONE);
            long relative = Math.max(0L, time - Math.max(0L, clip.startMs));
            if (layer.generation != bindingGeneration) {
                try { layer.motion = new MotionTimeline(clip); }
                catch (IllegalArgumentException invalidAnimation) {
                    layer.motion = new MotionTimeline(new org.json.JSONObject(),
                            AnimationClock.microseconds(Math.max(1L, clip.outputDurationMs())), "none", "flat");
                    report("animation:" + clip.id, "Source is visible; authored animation needs repair: " + invalidAnimation.getMessage());
                }
                if (audio) {
                    List<String> missing = NativeVideoEffects.unsupportedAudio(clip);
                    if (!missing.isEmpty()) report("audio-lane:" + clip.id,
                            "Audio lane preview ignores unsupported operations: " + String.join(", ", missing) + ". Remove them before exporting.");
                }
                if (image) {
                    if (!caption.isEmpty()) {
                        try { layer.title.bind(clip); }
                        catch (IllegalArgumentException invalidTitle) {
                            layer.title.bindFallback(clip.title, clip.outputDurationMs());
                            report("title:" + clip.id, "Image title uses default preview styling: " + invalidTitle.getMessage());
                        }
                    }
                    applyStillColor(layer, clip);
                    List<String> unavailable = NativeVideoEffects.unsupported(clip);
                    if (!unavailable.isEmpty()) report("native-effects:" + clip.id,
                            "Export cannot evaluate: " + String.join(", ", unavailable) + ". Remove these operations before exporting.");
                    bindStillGpu(layer, clip);
                }
                if (!image && !audio) {
                    try {
                        layer.player.setVideoEffects(NativeRenderEngine.previewEffects(clip, programAspect, programQuality));
                    } catch (IllegalArgumentException unsupported) {
                        layer.player.setVideoEffects(new ArrayList<>());
                        report("effect:" + clip.id, "Source is visible; preview effect unavailable: " + unsupported.getMessage());
                    }
                }
                layer.generation = bindingGeneration;
            }
            if (image) {
                if (layer.gpu != null) layer.gpu.renderAtUs(relative * 1000L);
                boolean gpuReady = layer.gpu != null && layer.gpu.isReady();
                layer.still.setVisibility(gpuReady ? View.GONE : View.VISIBLE);
                layer.title.setVisibility(!gpuReady && !caption.isEmpty() ? View.VISIBLE : View.GONE);
                if (layer.gpu != null) layer.gpu.view().setVisibility(layer.gpu.isFailed() ? View.GONE : View.VISIBLE);
                MotionTimeline.Sample motion = layer.motion.sample(relative * 1000L);
                layer.still.setScaleX(motion.scaleX);
                layer.still.setScaleY(motion.scaleY);
                layer.still.setTranslationX(motion.x * programCanvas.getWidth() / 2f);
                layer.still.setTranslationY(-motion.y * programCanvas.getHeight() / 2f);
                layer.still.setRotation(-motion.rotation);
                layer.still.setAlpha(motion.opacity);
                long offset = clip.effects == null ? 0L : clip.effects.optLong("animationOffsetMs", 0L);
                layer.title.setTime(AnimationClock.offsetTimeUs(AnimationClock.microseconds(relative), AnimationClock.microseconds(offset)));
                layer.title.setAlpha(motion.opacity);
                boolean titleOnly = clip.effects != null && clip.effects.optBoolean("titleOnly", false);
                layer.title.setScaleX(titleOnly ? motion.scaleX : 1f);
                layer.title.setScaleY(titleOnly ? motion.scaleY : 1f);
                layer.title.setTranslationX(titleOnly ? motion.x * programCanvas.getWidth() / 2f : 0f);
                layer.title.setTranslationY(titleOnly ? -motion.y * programCanvas.getHeight() / 2f : 0f);
                layer.title.setRotation(titleOnly ? -motion.rotation : 0f);
            } else {
                float speed = Math.max(.01f, clip.effectiveSpeed() * Math.max(.1f, shuttleRate));
                long target = clip.sourceTimeMs(time);
                if (forceSeek || shuttleRate < 0 && playing || Math.abs(layer.player.getCurrentPosition() - target) > 150L) {
                    layer.dsp.seekOutputTimeUs(relative * 1000L);
                    layer.player.seekTo(target);
                }
                if (Math.abs(layer.player.getPlaybackParameters().speed - speed) > .001f) layer.player.setPlaybackSpeed(speed);
                boolean audible = projectIndex.audible(clip.trackId);
                boolean detached = !audio && clip.effects != null && clip.effects.optBoolean("audioDetached", false);
                layer.player.setVolume(audible && !detached && shuttleRate > 0 ? 1f : 0f);
            }
        }
        for (String key : new ArrayList<>(programLayers.keySet())) {
            if (!retained.contains(key)) programLayers.remove(key).close();
        }
        // A ready decoder must not run ahead while another required layer is
        // buffering. All bindings share the program clock and start together.
        boolean ready = programReady();
        for (Layer layer : programLayers.values()) if (layer.player != null) layer.player.setPlayWhenReady(playing && ready && shuttleRate > 0);
    }
    private boolean programReady() {
        for (Layer layer : programLayers.values()) {
            if (layer.failed || layer.image && layer.still.getDrawable() == null
                    && (layer.gpu == null || !layer.gpu.isReady())) return false;
            if (layer.player != null && layer.player.getPlaybackState() != Player.STATE_READY
                    && layer.player.getPlaybackState() != Player.STATE_ENDED) return false;
        }
        return true;
    }
    private static String audioSignature(ProjectStore.Clip clip) {
        if (clip == null) return "";
        org.json.JSONObject fx = clip.effects == null ? new org.json.JSONObject() : clip.effects;
        return clip.volume + ":" + clip.effectiveSpeed() + ":" + clip.outputDurationMs() + ":" + clip.transition
                + ":" + fx.opt("audio") + ":" + fx.opt("keyframes") + ":" + fx.opt("audioKeyframes")
                + ":" + fx.opt("animationDurationMs") + ":" + fx.opt("animationOffsetMs")
                + ":" + fx.opt("transitionDurationMs") + ":" + fx.opt("audioDetached")
                + ":" + fx.opt("ease") + ":" + fx.opt("audioEasing")
                + ":" + fx.opt("bezier") + ":" + fx.opt("audioBezier");
    }
    private void clearProgram() {
        for (Layer layer : programLayers.values()) layer.close();
        programLayers.clear();
        projectIndex = null; programDurationMs = 0;
    }
    private void bindStillGpu(Layer layer, ProjectStore.Clip clip) {
        String signature = programAspect + ":" + clip.toJson();
        if (signature.equals(layer.gpuSignature)) return;
        if (layer.gpu != null) {
            View oldView = layer.gpu.view();
            layer.gpu.close();
            layer.host.removeView(oldView);
            layer.gpu = null;
        }
        layer.gpuSignature = signature;
        layer.gpuFailure = "";
        notices.remove("still-effects:" + clip.id);
        try {
            layer.gpu = new StillProgramRenderer(context, clip, layer.uri, programAspect, new StillGpuRenderer.Listener() {
                @Override public void onReady() {
                    if (released || layer.removed || !signature.equals(layer.gpuSignature) || layer.gpu == null) return;
                    layer.still.setVisibility(View.GONE);
                    layer.title.setVisibility(View.GONE);
                    layer.failed = false;
                    notices.remove("image:" + layer.uri);
                    layer.gpu.view().setVisibility(View.VISIBLE);
                    notices.remove("still-effects:" + clip.id);
                    if (listener != null) listener.onReady();
                }
                @Override public void onError(String detail) {
                    if (released || layer.removed || !signature.equals(layer.gpuSignature)) return;
                    layer.gpuFailure = detail;
                    if (layer.gpu != null) layer.gpu.view().setVisibility(View.GONE);
                    layer.still.setVisibility(View.VISIBLE);
                    layer.title.setVisibility(clip.title == null || clip.title.isEmpty() ? View.GONE : View.VISIBLE);
                    reportStillFallback(layer, clip);
                }
            });
            // Keep the TextureView attached and visible so Android creates its
            // SurfaceTexture. The basic still/title remain above it until the
            // first real GPU presentation, then become hidden.
            layer.host.addView(layer.gpu.view(), 0, fill());
            layer.gpu.renderAtUs(Math.max(0L, positionMs - clip.startMs) * 1000L);
        } catch (IllegalArgumentException | IllegalStateException unavailable) {
            layer.gpuFailure = unavailable.getMessage();
            reportStillFallback(layer, clip);
        }
    }
    private void reportStillFallback(Layer layer, ProjectStore.Clip clip) {
        List<String> missing = NativeVideoEffects.stillPreviewUnsupported(clip);
        missing.removeAll(NativeVideoEffects.unsupported(clip));
        missing.remove("title animation");
        org.json.JSONObject settings = NativeVideoEffects.resolvedSettings(clip);
        if (settings.optDouble("hueAdjust", 0) != 0) missing.add("hue");
        if (settings.optDouble("saturationAdjust", 0) != 0 || settings.optDouble("lightnessAdjust", 0) != 0)
            missing.add("exact HSL color");
        String omitted = missing.isEmpty() ? "" : " The fallback omits " + String.join(", ", missing) + ".";
        report("still-effects:" + clip.id, "Image monitor is using its bounded basic fallback: " + layer.gpuFailure + "." + omitted);
    }
    private void loadImage(Layer layer) {
        Bitmap cached = images.get(layer.uri);
        if (cached != null) {
            layer.still.setImageBitmap(cached);
            if (listener != null) listener.onReady();
            return;
        }
        try {
        imageLoader.purge();
        layer.imageFuture = imageLoader.submit(() -> {
            if (released || layer.removed || Thread.currentThread().isInterrupted()) return;
            com.google.common.util.concurrent.ListenableFuture<Bitmap> decoding =
                    new StreamingBitmapLoader(context, 1536).loadBitmap(Uri.parse(layer.uri));
            // Completion owns every decoded result, even when the editor closes
            // while a provider is reading. Cancelling a blocking get() can lose
            // a bitmap that the decoder allocates after the waiter exits.
            decoding.addListener(() -> {
                try {
                    Bitmap decoded = decoding.get();
                    if (released || layer.removed) { decoded.recycle(); return; }
                    handler.post(() -> {
                        if (released || layer.removed) { decoded.recycle(); return; }
                        images.put(layer.uri, decoded);
                        layer.still.setImageBitmap(decoded);
                        if (listener != null) listener.onReady();
                    });
                } catch (Exception error) {
                    handler.post(() -> { if (!released && !layer.removed) {
                        if (layer.gpu != null && layer.gpu.isReady()) return;
                        layer.failed = true;
                        pause();
                        report("image:" + layer.uri, "Playback paused: could not display image: " + error.getMessage());
                    } });
                }
            }, Runnable::run);
        });
        } catch (RejectedExecutionException busy) {
            layer.failed = true;
            pause();
            report("image-budget:" + layer.uri, "Image preview queue is full. Pause scrubbing and select the image again.");
        }
    }
    private void applyStillColor(Layer layer, ProjectStore.Clip clip) {
        org.json.JSONObject fx = NativeVideoEffects.resolvedSettings(clip);
        layer.still.setScaleType("fit".equals(fx.optString("crop", "center_cover"))
                ? ImageView.ScaleType.FIT_CENTER : ImageView.ScaleType.CENTER_CROP);
        float contrast = (float) fx.optDouble("contrast", 0);
        float factor = (1f + contrast) / Math.max(.0001f, 1f - contrast);
        float offset = 127.5f * (1f - factor) + 255f * (float) fx.optDouble("brightness", 0) * factor;
        ColorMatrix matrix = new ColorMatrix(new float[]{factor,0,0,0,offset, 0,factor,0,0,offset,
                0,0,factor,0,offset, 0,0,0,1,0});
        float saturation = (float) fx.optDouble("saturationAdjust", 0);
        float lightness = (float) fx.optDouble("lightnessAdjust", 0);
        float hue = (float) fx.optDouble("hueAdjust", 0);
        if (saturation != 0) {
            ColorMatrix adjustment = new ColorMatrix();
            adjustment.setSaturation(Math.max(0f, 1f + saturation / 100f));
            matrix.postConcat(adjustment);
        }
        if (lightness != 0) matrix.postConcat(new ColorMatrix(new float[]{1,0,0,0,lightness*2.55f,
                0,1,0,0,lightness*2.55f, 0,0,1,0,lightness*2.55f, 0,0,0,1,0}));
        layer.still.setColorFilter(new ColorMatrixColorFilter(matrix));
    }
    private static boolean isImage(ProjectStore.Asset asset) {
        return asset != null && ((asset.mime != null && asset.mime.startsWith("image/")) || imageUri(asset.uri));
    }
    private static boolean imageUri(String uri) {
        String value = uri == null ? "" : uri.toLowerCase(java.util.Locale.ROOT);
        return value.matches(".*\\.(png|jpe?g|webp|gif|bmp)(?:\\?.*)?$");
    }
    private void scheduleTick() {
        if (!released && !ticking) { ticking = true; handler.post(tick); }
    }
    private final Runnable tick = new Runnable() {
        @Override public void run() {
            ticking = false;
            if (released) return;
            long now = SystemClock.elapsedRealtime();
            long delta = Math.max(0L, now - lastTick);
            lastTick = now;
            if (programMode && project != null) {
                boolean mediaReady = programReady();
                boolean wrapped = false;
                if (playing && mediaReady) {
                    long start = transportStartMs(), end = transportEndMs();
                    advanceClock(delta);
                    if (positionMs >= end || shuttleRate < 0 && positionMs <= start) {
                        if (loop && end > start) {
                            positionMs = start + Math.floorMod(positionMs - start, end - start);
                            wrapped = true;
                        } else { positionMs = Math.max(start, Math.min(end, positionMs)); playing = false; }
                    }
                }
                updateProgram(wrapped);
            } else if (source != null) {
                if (source.player != null) {
                    if (playing && shuttleRate < 0) {
                        if (source.player.getPlaybackState() == Player.STATE_READY || source.player.getPlaybackState() == Player.STATE_ENDED) {
                            advanceClock(delta);
                            if (positionMs <= 0) {
                                long end = Math.max(sourceDurationMs, source.player.getDuration());
                                if (loop && end > 0) positionMs = end - 1; else { positionMs = 0; playing = false; }
                            }
                            source.player.seekTo(Math.max(0L, positionMs));
                        }
                    } else {
                        positionMs = Math.max(0L, source.player.getCurrentPosition());
                        if (source.player.getPlaybackState() == Player.STATE_ENDED) {
                            if (playing && loop && onBoundary == null) { source.player.seekTo(0); source.player.play(); positionMs = 0; }
                            else playing = false;
                        }
                    }
                } else if (playing && source.still.getDrawable() != null) {
                    advanceClock(delta);
                    if (sourceDurationMs > 0 && (positionMs >= sourceDurationMs || shuttleRate < 0 && positionMs <= 0)) {
                        if (loop) positionMs = shuttleRate < 0 ? sourceDurationMs - 1 : positionMs % sourceDurationMs;
                        else { positionMs = Math.max(0L, Math.min(positionMs, sourceDurationMs)); playing = false; }
                    }
                }
                if (onBoundary != null && (positionMs >= boundaryOutMs
                        || source.player != null && source.player.getPlaybackState() == Player.STATE_ENDED)) {
                    Runnable callback = onBoundary;
                    onBoundary = null;
                    pause();
                    callback.run();
                }
            }
            notifyPosition();
            if (hasMedia()) { ticking = true; handler.postDelayed(this, playing ? 33L : 120L); }
        }
    };
    private void advanceClock(long deltaMs) {
        double elapsed = deltaMs * (double) shuttleRate + clockRemainder;
        long whole = (long) elapsed;
        clockRemainder = elapsed - whole;
        positionMs = whole > 0 && positionMs > Long.MAX_VALUE - whole ? Long.MAX_VALUE : positionMs + whole;
    }
    private void notifyPosition() {
        if (listener == null) return;
        long duration = programMode && project != null ? programDurationMs : sourceDurationMs;
        if (!programMode && source != null && source.player != null && source.player.getDuration() > 0L) duration = source.player.getDuration();
        listener.onPosition(getCurrentPosition(), Math.max(0L, duration), isPlaying());
    }
    private void report(String key, String message) {
        boolean changed = !message.equals(notices.put(key, message));
        while (notices.size() > 32) notices.remove(notices.keySet().iterator().next());
        if (changed && listener != null) listener.onError(message);
    }
    public LivePlaybackState snapshotState() {
        String uri = source == null ? "" : source.uri;
        return new LivePlaybackState(uri, getCurrentPosition(), playing, snapshotId, clipId);
    }
    public void restoreState(LivePlaybackState state) {
        if (state == null) return;
        snapshotId = state.snapshotId;
        clipId = state.clipId;
        if (!state.mediaUri.isEmpty()) play(Uri.parse(state.mediaUri), state.positionMs, 1f, state.playWhenReady);
    }
    public void setContextIds(String snapshotId, String clipId) {
        this.snapshotId = snapshotId == null ? "" : snapshotId;
        this.clipId = clipId == null ? "" : clipId;
    }
    public long getCurrentPosition() {
        return !programMode && source != null && source.player != null && shuttleRate >= 0 ? Math.max(0L, source.player.getCurrentPosition()) : Math.max(0L, positionMs);
    }
    public long currentPositionMs() { return getCurrentPosition(); }
    public boolean isPlaying() { return playing; }
    public boolean hasMedia() { return programMode ? project != null && !project.clips.isEmpty() : source != null; }
    public void pause() {
        playing = false;
        if (source != null && source.player != null) source.player.pause();
        for (Layer layer : programLayers.values()) if (layer.player != null) layer.player.pause();
        notifyPosition();
    }
    public void stop() {
        playing = false;
        onBoundary = null;
        if (source != null) { source.close(); source = null; }
        clearProgram();
        programCanvas.setVisibility(View.GONE);
        project = null;
        handler.removeCallbacks(tick);
        ticking = false;
    }
    public void seekTo(long atMs) {
        if (programMode) { seekProgram(atMs); return; }
        positionMs = Math.max(0L, atMs);
        clockRemainder = 0;
        if (source != null && source.player != null) source.player.seekTo(positionMs);
        else if (sourceDurationMs > 0L) positionMs = Math.min(positionMs, sourceDurationMs);
        lastTick = SystemClock.elapsedRealtime();
        notifyPosition();
    }

    /** Temporary direct manipulation; persisted values are committed by the UI on release. */
    public void previewTransform(String id, float scaleX, float scaleY, float x, float y, float rotation) {
        Layer layer = programLayers.get(id);
        if (layer == null || layer.audioOnly) return;
        ProjectStore.Clip clip = project == null ? null : project.clip(id);
        if (clip != null && layer.motion != null) {
            MotionTimeline.Sample sample = layer.motion.sample(Math.max(0L, positionMs - clip.startMs) * 1000L);
            layer.host.setPivotX(programCanvas.getWidth() * (1f + sample.x) / 2f);
            layer.host.setPivotY(programCanvas.getHeight() * (1f - sample.y) / 2f);
        }
        layer.host.setScaleX(Math.max(.01f, scaleX));
        layer.host.setScaleY(Math.max(.01f, scaleY));
        layer.host.setTranslationX(x * programCanvas.getWidth() / 2f);
        layer.host.setTranslationY(-y * programCanvas.getHeight() / 2f);
        layer.host.setRotation(-rotation);
    }

    public void resetPreviewTransform(String id) {
        previewTransform(id, 1f, 1f, 0f, 0f, 0f);
    }
    public void release() {
        if (released) return;
        stop();
        released = true;
        imageLoader.shutdownNow();
        images.evictAll();
        listener = null;
    }
}
