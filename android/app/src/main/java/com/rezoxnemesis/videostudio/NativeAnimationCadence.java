package com.rezoxnemesis.videostudio;

import android.graphics.Bitmap;
import android.media.metrics.LogSessionId;
import android.os.Handler;
import android.os.Looper;

import androidx.media3.common.ColorInfo;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.util.TimestampIterator;
import androidx.media3.common.util.BitmapLoader;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.transformer.AssetLoader;
import androidx.media3.transformer.Codec;
import androidx.media3.transformer.EditedMediaItem;
import androidx.media3.transformer.ExportException;
import androidx.media3.transformer.ProgressHolder;
import androidx.media3.transformer.SampleConsumer;
import androidx.media3.transformer.Transformer;

import com.google.common.collect.ImmutableMap;
import com.google.common.util.concurrent.ListenableFuture;

import org.json.JSONObject;

import java.util.NoSuchElementException;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/** Explicit constant-rate primary clock. Media3's built-in gap uses 30 fps,
 * so it cannot serve as the clock for an authored animation cadence. This
 * bounded two-pixel bitmap supplies actual timestamps without a generated file. */
@UnstableApi
final class NativeAnimationCadence {
    private static final String CLOCK_ID = "VideoStudio-native-animation-clock-v1";
    private static final String CLOCK_URI = "videostudio-clock://transparent/v1";
    static final long TIMESTAMP_TOLERANCE_US = 100L;
    private NativeAnimationCadence() { }

    static int frameRate(ProjectStore.Project project) {
        if (project == null) throw new IllegalArgumentException("Project is required for export cadence");
        int rate = project.animationFrameRate == 0 ? 30 : project.animationFrameRate;
        if (!AnimationCelEdits.supportedFrameRate(rate))
            throw new IllegalArgumentException("Native video cadence supports integer 12, 24, 30 or 60 fps; fractional rates are unavailable");
        return rate;
    }

    static JSONObject profile(ProjectStore.Project project) {
        JSONObject result = new JSONObject();
        try {
            int rate = frameRate(project);
            result.put("frameRate", rate).put("fpsNumerator", rate).put("fpsDenominator", 1)
                    .put("mode", "constant_primary_clock").put("fractionalSupported", false)
                    .put("sourceVideoPolicy", "nearest source frame sampled at the chosen output clock")
                    .put("celPolicy", "matching frame-grid exposures use cumulative microsecond boundaries; other edits use timeline milliseconds")
                    .put("partialFramePolicy","include a partial final output frame; explicit range endpoints remain authoritative")
                    .put("timestampPrecision", "integer microseconds; encoded timestamps checked within 100us");
        } catch (IllegalArgumentException error) { throw error; }
        catch (Exception error) { throw new IllegalArgumentException("Could not describe export cadence", error); }
        return result;
    }

    static void validateExposure(JSONObject exposure,List<String> errors) {
        if(exposure==null)return;
        try {
            long numerator=integer(exposure,"fpsNumerator",24L),denominator=integer(exposure,"fpsDenominator",1L);
            if(numerator<1L||numerator>120000L||denominator<1L||denominator>10000L
                    ||numerator%denominator!=0L||!AnimationCelEdits.supportedFrameRate((int)(numerator/denominator)))
                errors.add("celExposure requires integer 12, 24, 30 or 60 fps; fractional encoding cadence is unavailable");
        }catch(IllegalArgumentException error){errors.add(error.getMessage());}
    }

    private static long integer(JSONObject object,String key,long fallback) {
        if(!object.has(key))return fallback;
        Object value=object.opt(key);
        if(!(value instanceof Number))throw new IllegalArgumentException("celExposure."+key+" must be an integer");
        double numeric=((Number)value).doubleValue();
        if(!Double.isFinite(numeric)||numeric!=Math.rint(numeric))throw new IllegalArgumentException("celExposure."+key+" must be a finite integer");
        return ((Number)value).longValue();
    }

    static EditedMediaItem clockItem(long durationUs, int rate) {
        frameCount(durationUs, rate);
        MediaItem media = new MediaItem.Builder().setMediaId(CLOCK_ID).setUri(CLOCK_URI)
                .setMimeType(MimeTypes.IMAGE_PNG).setImageDurationMs(Math.max(1L, durationUs / 1000L)).build();
        return new EditedMediaItem.Builder(media).setRemoveAudio(true).setDurationUs(durationUs).setFrameRate(rate).build();
    }

    /** Cover a partial final frame. A rounded exact frame boundary is allowed
     * its half-microsecond quantization without inventing another frame. */
    static long frameCount(long durationUs, int rate) {
        if (durationUs <= 0L || !AnimationCelEdits.supportedFrameRate(rate))
            throw new IllegalArgumentException("Invalid native cadence duration or frame rate");
        return Math.max(1L, gridIndexAtOrAfter(durationUs,rate));
    }

    private static long gridIndexAtOrAfter(long timeUs,int rate) {
        long whole = Math.multiplyExact(timeUs / 1_000_000L, rate);
        long remainder = timeUs % 1_000_000L * rate;
        long fraction = Math.max(0L, remainder - rate / 2L);
        return Math.addExact(whole, (fraction + 999_999L) / 1_000_000L);
    }

    static long timestampUs(long frame, int rate) {
        if (frame < 0L) throw new IllegalArgumentException("Frame index must be nonnegative");
        return Math.addExact(Math.multiplyExact(frame / rate, 1_000_000L),
                ((frame % rate) * 1_000_000L + rate / 2L) / rate);
    }

    static final class ImageTiming { final long startUs; ImageTiming(long startUs) { this.startUs=startUs; } }

    static AssetLoader.Factory assetLoaderFactory(AssetLoader.Factory delegate,BitmapLoader bitmaps) {
        return (item, looper, listener, settings) -> {
            if (CLOCK_ID.equals(item.mediaItem.mediaId) && item.mediaItem.localConfiguration != null
                    && CLOCK_URI.equals(item.mediaItem.localConfiguration.uri.toString()))
                return new ClockLoader(item, looper, listener,null);
            if(item.mediaItem.localConfiguration!=null&&item.mediaItem.localConfiguration.imageDurationMs!=C.TIME_UNSET)
                return new ClockLoader(item,looper,listener,bitmaps);
            return delegate.createAssetLoader(item, looper, listener, settings);
        };
    }

    /** Encoder metadata is a request, not cadence evidence. Verification scans
     * actual sample timestamps after export; devices may override codec hints. */
    static Codec.EncoderFactory encoderFactory(Codec.EncoderFactory delegate, int rate) {
        return new Codec.EncoderFactory() {
            @Override public Codec createForAudioEncoding(Format format, LogSessionId session) throws ExportException {
                return delegate.createForAudioEncoding(format, session);
            }
            @Override public Codec createForVideoEncoding(Format format, LogSessionId session) throws ExportException {
                return delegate.createForVideoEncoding(format.buildUpon().setFrameRate(rate).build(), session);
            }
            @Override public boolean audioNeedsEncoding() { return delegate.audioNeedsEncoding(); }
            @Override public boolean videoNeedsEncoding() { return true; }
        };
    }

    private static final class Frames implements TimestampIterator {
        final long count,first,originUs; final int rate; long next;
        Frames(long durationUs, int rate,long startUs) {
            long gridFirst=gridIndexAtOrAfter(startUs,rate);
            long gridCount=gridIndexAtOrAfter(Math.addExact(startUs,durationUs),rate)-gridFirst;
            // A very short off-grid item may have no output tick. One source
            // frame still registers its input; compositor visibility remains
            // bounded by the real clip interval, so it invents no visible tick.
            count=Math.max(1L,gridCount);first=gridCount==0L?0L:gridFirst;
            originUs=gridCount==0L?0L:startUs;this.rate=rate;
        }
        private Frames(long count,long first,long originUs,int rate) { this.count=count;this.first=first;this.originUs=originUs;this.rate=rate; }
        @Override public boolean hasNext() { return next < count; }
        @Override public long next() { if (!hasNext()) throw new NoSuchElementException(); return timestampUs(first+next++, rate)-originUs; }
        @Override public TimestampIterator copyOf() { return new Frames(count,first,originUs,rate); }
        @Override public long getLastTimestampUs() { return timestampUs(first+count-1L,rate)-originUs; }
    }

    private static final class ClockLoader implements AssetLoader {
        final EditedMediaItem item; final Listener listener; final Handler handler;
        final BitmapLoader bitmaps;final long startUs;final Runnable queue = this::queue;
        final AtomicReference<Bitmap> waiting=new AtomicReference<>();
        Format format;SampleConsumer consumer;Bitmap pending;ListenableFuture<Bitmap> decoding;
        volatile boolean released;boolean started,queued;
        ClockLoader(EditedMediaItem item, Looper looper, Listener listener,BitmapLoader bitmaps) {
            this.item = item; this.listener = listener; handler = new Handler(looper);
            this.bitmaps=bitmaps;
            Object tag=item.mediaItem.localConfiguration==null?null:item.mediaItem.localConfiguration.tag;
            startUs=tag instanceof ImageTiming?((ImageTiming)tag).startUs:0L;
            frameCount(item.durationUs,item.frameRate);
        }
        @Override public void start() {
            if (released || started) return; started = true;
            try {
                listener.onDurationUs(item.durationUs); listener.onTrackCount(1);
                if(bitmaps==null){acceptDecoded(Bitmap.createBitmap(2,2,Bitmap.Config.ARGB_8888));return;}
                decoding=bitmaps.loadBitmap(item.mediaItem.localConfiguration.uri);
                decoding.addListener(()->{
                    try {
                        Bitmap bitmap=decoding.get();
                        waiting.set(bitmap);
                        if(released){recycleWaiting();return;}
                        if(!handler.post(()->{Bitmap owned=waiting.getAndSet(null);if(owned==null)return;
                            if(released){owned.recycle();return;}
                            try{acceptDecoded(owned);}catch(RuntimeException|OutOfMemoryError error){fail(error);}}))recycleWaiting();
                    }catch(Exception error){if(!released)handler.post(()->fail(error));}
                },Runnable::run);
            } catch (RuntimeException | OutOfMemoryError error) { fail(error); }
        }
        void acceptDecoded(Bitmap bitmap) {
            pending=bitmap;
            format=new Format.Builder().setWidth(bitmap.getWidth()).setHeight(bitmap.getHeight()).setSampleMimeType(MimeTypes.IMAGE_RAW)
                    .setFrameRate(item.frameRate).setColorInfo(ColorInfo.SRGB_BT709_FULL).build();
            listener.onTrackAdded(format,SUPPORTED_OUTPUT_TYPE_DECODED);queue();
        }
        void queue() {
            if (released || queued) return;
            try {
                if (consumer == null) consumer = listener.onOutputFormat(format);
                if (consumer == null) { handler.postDelayed(queue, 10); return; }
                int result = consumer.queueInputBitmap(pending, new Frames(item.durationUs,item.frameRate,startUs));
                if (result == SampleConsumer.INPUT_RESULT_TRY_AGAIN_LATER) { handler.postDelayed(queue, 10); return; }
                if (result != SampleConsumer.INPUT_RESULT_SUCCESS && result != SampleConsumer.INPUT_RESULT_END_OF_STREAM)
                    throw new IllegalStateException("Native cadence clock returned an unknown bitmap result");
                // The frame processor owns and recycles an accepted bitmap.
                pending = null; queued = true;
                if (result == SampleConsumer.INPUT_RESULT_SUCCESS) consumer.signalEndOfVideoInput();
            } catch (ExportException error) { listener.onError(error); }
            catch (RuntimeException | OutOfMemoryError error) { fail(error); }
        }
        void fail(Throwable error) { if(!released)listener.onError(ExportException.createForAssetLoader(error, ExportException.ERROR_CODE_UNSPECIFIED)); }
        @Override public int getProgress(ProgressHolder progress) {
            if (!started || released) return Transformer.PROGRESS_STATE_NOT_STARTED;
            progress.progress = queued ? 100 : 0; return Transformer.PROGRESS_STATE_AVAILABLE;
        }
        @Override public ImmutableMap<Integer, String> getDecoderNames() { return ImmutableMap.of(); }
        @Override public void release() {
            released = true; handler.removeCallbacks(queue);
            if(decoding!=null)decoding.cancel(true);recycleWaiting();
            if (pending != null) { pending.recycle(); pending = null; }
            // SequenceAssetLoader may release this loader immediately after
            // queue success, while the GPU upload is still pending. Accepted
            // bitmap ownership stays with Media3; never recycle it here.
        }
        void recycleWaiting(){Bitmap bitmap=waiting.getAndSet(null);if(bitmap!=null&&!bitmap.isRecycled())bitmap.recycle();}
    }
}
