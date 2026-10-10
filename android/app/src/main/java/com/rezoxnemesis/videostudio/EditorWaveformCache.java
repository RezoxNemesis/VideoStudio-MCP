package com.rezoxnemesis.videostudio;

import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.database.Cursor;
import android.media.AudioFormat;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.net.Uri;
import android.os.CancellationSignal;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.DocumentsContract;
import android.provider.OpenableColumns;
import android.util.LruCache;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.zip.CRC32;
import java.util.zip.CheckedInputStream;
import java.util.zip.CheckedOutputStream;

/** Real decoded audio peaks. Every provider query, cache read and decode runs off the UI thread. */
final class EditorWaveformCache {
    static final long WINDOW_MS = 10L * 60L * 1000L;
    private static final int MAX_BINS = 2048;
    private static final int CACHE_VERSION = 1;
    private static final int MAGIC = 0x56535746;
    private static final int MAX_RECORD_BYTES = 12 * 1024;
    private static final int MAX_DISK_FILES = 256;
    private static final long MAX_DISK_BYTES = 4L * 1024L * 1024L;
    private static final long IDENTITY_TTL_MS = 30000L;
    private static final long RETRY_MS = 30000L;
    private static final long MAX_DECODE_MS = 120000L;
    private static final long MAX_IDLE_MS = 10000L;
    private static final int MAX_OUTPUT_BYTES = 16 * 1024 * 1024;
    // A process-wide lane also bounds overlap during Activity recreation.
    private static final ThreadPoolExecutor WORKER = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(16), runnable -> {
                Thread thread = new Thread(() -> {
                    android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND);
                    runnable.run();
                }, "VideoStudio-audio-peaks");
                thread.setDaemon(true);
                return thread;
            });

    /** A snapshot in source-media time. Only the supplied interval has measured peaks. */
    static final class Waveform {
        final long durationMs, sampledStartMs, sampledEndMs;
        final boolean complete;
        /** UI snapshot; changing this array cannot change cached peakBetween results. */
        final float[] peaks;
        private final float[] measured;

        Waveform(long durationMs, long startMs, long endMs, float[] values) {
            this.durationMs = durationMs;
            sampledStartMs = startMs; sampledEndMs = endMs;
            complete = startMs == 0L && endMs == durationMs;
            measured = values.clone(); peaks = values.clone();
        }

        float peakBetween(long fromMs, long toMs) {
            if (measured.length == 0 || toMs < sampledStartMs || fromMs >= sampledEndMs) return 0f;
            long from = Math.max(sampledStartMs, fromMs), to = Math.min(sampledEndMs, Math.max(fromMs == Long.MAX_VALUE ? fromMs : fromMs + 1L, toMs));
            if (to <= from) return 0f;
            double scale = measured.length / (double) (sampledEndMs - sampledStartMs);
            int first = Math.max(0, Math.min(measured.length - 1, (int) ((from - sampledStartMs) * scale)));
            int last = Math.max(first, Math.min(measured.length - 1, (int) Math.ceil((to - sampledStartMs) * scale) - 1));
            float peak = 0f;
            for (int bin = first; bin <= last; bin++) peak = Math.max(peak, measured[bin]);
            return peak;
        }
    }

    private static final class Source {
        final String uri, declaredKey;
        final long durationMs, sizeBytes;
        final boolean eligible;
        Source(ProjectStore.Asset asset) {
            uri = asset == null || asset.uri == null ? "" : asset.uri;
            durationMs = asset == null ? 0L : Math.max(0L, asset.durationMs);
            sizeBytes = asset == null ? -1L : asset.sizeBytes;
            eligible = asset != null && !uri.isEmpty() && (asset.hasAudio || asset.mime != null && asset.mime.startsWith("audio/"));
            String importedHash = asset == null || asset.importMetadata == null ? ""
                    : asset.importMetadata.optString("sha256", asset.importMetadata.optString("expectedSha256", ""));
            declaredKey = hash(uri + "\n" + durationMs + "\n" + sizeBytes + "\n" + importedHash);
        }
    }

    private static final class Window {
        final long startMs, endMs;
        final String key;
        Window(Source source, long requestedInMs) {
            long at = Math.max(0L, requestedInMs);
            if (source.durationMs > 0L) at = Math.min(at, source.durationMs - 1L);
            startMs = at / WINDOW_MS * WINDOW_MS;
            long windowEnd = startMs > Long.MAX_VALUE - WINDOW_MS ? Long.MAX_VALUE : startMs + WINDOW_MS;
            endMs = source.durationMs > 0L ? Math.min(source.durationMs, windowEnd) : windowEnd;
            key = source.declaredKey + ":" + startMs + ":" + endMs;
        }
    }

    private static final class Entry {
        final Source source;
        final Window window;
        final CancellationSignal cancellation = new CancellationSignal();
        final ArrayList<Runnable> callbacks = new ArrayList<>();
        String state = "queued", detail = "Audio peaks queued";
        long completedAt;
        Future<?> future;
        Entry(Source source, Window window) { this.source = source; this.window = window; }
        boolean pending() { return "queued".equals(state) || "decoding".equals(state); }
    }

    private final Context context;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Object lock = new Object();
    private final LinkedHashMap<String, Entry> entries = new LinkedHashMap<>(16, .75f, true);
    private final LruCache<String, Waveform> memory = new LruCache<String, Waveform>(2 * 1024 * 1024) {
        @Override protected int sizeOf(String key, Waveform value) { return 128 + value.peaks.length * Float.BYTES * 2; }
    };
    private volatile boolean closed;

    EditorWaveformCache(Context context) { this.context = context.getApplicationContext(); }

    Waveform peek(ProjectStore.Asset asset) { return peek(asset, 0L, asset == null ? 0L : asset.durationMs); }
    Waveform peek(ProjectStore.Asset asset, long sourceInMs, long sourceOutMs) {
        Source source = new Source(asset); if (!source.eligible || closed) return null;
        Window window = new Window(source, sourceInMs);
        synchronized (lock) {
            Entry entry = entries.get(window.key);
            if (entry == null || SystemClock.elapsedRealtime() - entry.completedAt > IDENTITY_TTL_MS) return null;
            return memory.get(window.key);
        }
    }

    String status(ProjectStore.Asset asset, long sourceInMs, long sourceOutMs) {
        Source source = new Source(asset); if (!source.eligible || closed) return "unavailable";
        Window window = new Window(source, sourceInMs);
        synchronized (lock) {
            Entry entry = entries.get(window.key);
            if (entry == null) return "missing";
            if (entry.pending()) return entry.state;
            long age = SystemClock.elapsedRealtime() - entry.completedAt;
            if ("failed".equals(entry.state)) return age < RETRY_MS ? "failed" : "missing";
            Waveform waveform = memory.get(window.key);
            return waveform == null || age > IDENTITY_TTL_MS ? "missing" : waveform.complete ? "ready" : "partial";
        }
    }
    String status(ProjectStore.Asset asset) { return status(asset, 0L, asset == null ? 0L : asset.durationMs); }
    String detail(ProjectStore.Asset asset, long sourceInMs, long sourceOutMs) {
        Source source = new Source(asset); if (!source.eligible) return "This media has no declared audio track";
        synchronized (lock) {
            Entry entry = entries.get(new Window(source, sourceInMs).key);
            return entry == null ? "Audio peaks have not been requested" : entry.detail;
        }
    }
    String detail(ProjectStore.Asset asset) { return detail(asset, 0L, asset == null ? 0L : asset.durationMs); }

    void request(ProjectStore.Asset asset, Runnable callback) { request(asset, 0L, asset == null ? 0L : asset.durationMs, callback); }
    void request(ProjectStore.Asset asset, long sourceInMs, long sourceOutMs, Runnable callback) {
        Source source = new Source(asset);
        if (closed || !source.eligible) { deliver(callback); return; }
        Window window = new Window(source, sourceInMs);
        synchronized (lock) {
            Entry existing = entries.get(window.key);
            if (existing != null && existing.pending()) { addCallback(existing, callback); return; }
            if (existing != null) {
                long age = SystemClock.elapsedRealtime() - existing.completedAt;
                if (memory.get(window.key) != null && age <= IDENTITY_TTL_MS
                        || "failed".equals(existing.state) && age < RETRY_MS) { deliver(callback); return; }
            }
            Entry entry = new Entry(source, window); addCallback(entry, callback); entries.put(window.key, entry); pruneEntries();
            try { entry.future = WORKER.submit(() -> load(entry)); }
            catch (RejectedExecutionException busy) {
                entry.state = "failed"; entry.detail = "Audio peak worker is busy; retry after pending requests complete";
                entry.completedAt = SystemClock.elapsedRealtime(); finishCallbacks(entry);
            }
        }
    }

    private void load(Entry entry) {
        Waveform waveform = null; String error = null;
        try {
            check(entry);
            synchronized (lock) { entry.state = "decoding"; entry.detail = "Reading measured audio peaks"; }
            Identity before = identity(entry);
            File directory = cacheDirectory();
            pruneDisk(directory);
            File cached = new File(directory, hash(before.fingerprint + ":" + entry.window.startMs + ":" + entry.window.endMs) + ".wave");
            waveform = readCache(cached, before.fingerprint, entry);
            if (waveform == null) {
                waveform = decode(entry);
                check(entry);
                Identity after = identity(entry);
                if (!before.fingerprint.equals(after.fingerprint)) throw new IllegalStateException("Source media changed while audio peaks were decoded");
                try { writeCache(cached, before.fingerprint, waveform, entry); }
                catch (java.io.IOException cacheUnavailable) { /* Measured peaks still work from the bounded memory cache. */ }
            } else cached.setLastModified(System.currentTimeMillis());
            pruneDisk(directory);
            check(entry);
        } catch (Exception failure) { error = failure.getMessage() == null ? "Could not decode audio peaks" : failure.getMessage(); }
        synchronized (lock) {
            if (closed || entry.cancellation.isCanceled() || entries.get(entry.window.key) != entry) return;
            entry.completedAt = SystemClock.elapsedRealtime();
            if (waveform != null && error == null) {
                memory.put(entry.window.key, waveform); entry.state = waveform.complete ? "ready" : "partial";
                entry.detail = waveform.complete ? "Measured audio peaks ready" : "Measured audio peaks for this ten-minute source window";
            } else { memory.remove(entry.window.key); entry.state = "failed"; entry.detail = error == null ? "Audio peaks unavailable" : error; }
            finishCallbacks(entry);
        }
    }

    private Waveform decode(Entry entry) throws Exception {
        MediaExtractor extractor = new MediaExtractor(); MediaCodec decoder = null;
        boolean started = false;
        try (AssetFileDescriptor descriptor = context.getContentResolver().openAssetFileDescriptor(Uri.parse(entry.source.uri), "r", entry.cancellation)) {
            if (descriptor == null) throw new IllegalStateException("Audio source is unavailable");
            check(entry);
            long offset = descriptor.getStartOffset(), length = descriptor.getLength();
            if (length < 0L && offset > 0L) {
                long statSize = descriptor.getParcelFileDescriptor().getStatSize();
                if (statSize <= offset) throw new IllegalStateException("Audio source has an unreadable descriptor range");
                length = statSize - offset;
            }
            if (length >= 0L) extractor.setDataSource(descriptor.getFileDescriptor(), offset, length);
            else extractor.setDataSource(descriptor.getFileDescriptor());
            check(entry);
            int selected = -1; MediaFormat inputFormat = null;
            for (int track = 0; track < extractor.getTrackCount(); track++) {
                MediaFormat candidate = extractor.getTrackFormat(track);
                String mime = candidate.getString(MediaFormat.KEY_MIME);
                if (mime != null && mime.startsWith("audio/")) { selected = track; inputFormat = candidate; break; }
            }
            if (selected < 0 || inputFormat == null) throw new IllegalStateException("No decodable audio track in this source");
            long durationMs = entry.source.durationMs;
            if (durationMs <= 0 && inputFormat.containsKey(MediaFormat.KEY_DURATION)) durationMs = inputFormat.getLong(MediaFormat.KEY_DURATION) / 1000L;
            if (durationMs <= 0 || durationMs > Long.MAX_VALUE / 1000L) throw new IllegalStateException("Audio duration is unavailable for bounded waveform sampling");
            long startMs = entry.window.startMs, endMs = Math.min(durationMs, entry.window.endMs);
            if (startMs >= endMs) throw new IllegalStateException("Requested source audio window is outside the media");
            long startUs = startMs * 1000L, endUs = endMs * 1000L;
            long audioEndUs = inputFormat.containsKey(MediaFormat.KEY_DURATION) ? inputFormat.getLong(MediaFormat.KEY_DURATION) : endUs;
            long expectedAudioEndUs = audioEndUs > 0L ? Math.min(endUs, audioEndUs) : endUs;
            int sampleRate = integer(inputFormat, MediaFormat.KEY_SAMPLE_RATE, 48000);
            int channels = integer(inputFormat, MediaFormat.KEY_CHANNEL_COUNT, 1);
            int encoding = integer(inputFormat, MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_16BIT);
            int bins = Math.max(1, (int) Math.min(MAX_BINS, Math.ceil((endMs - startMs) * (double) sampleRate / 1000d)));
            float[] peaks = new float[bins];
            extractor.selectTrack(selected); extractor.seekTo(startUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC);
            // For compressed tracks this requests decoder PCM16. For raw float
            // WAV, the same field describes the source bytes and must be kept.
            if (!"audio/raw".equals(inputFormat.getString(MediaFormat.KEY_MIME))) {
                inputFormat.setInteger(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_16BIT);
                encoding = AudioFormat.ENCODING_PCM_16BIT;
            }
            validatePcm(sampleRate, channels, encoding);
            inputFormat.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 256 * 1024);
            decoder = createDecoder(inputFormat);
            decoder.configure(inputFormat, null, null, 0); decoder.start(); started = true;
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            boolean inputEnded = false, outputEnded = false, measuredAny = false;
            long startedAt = SystemClock.elapsedRealtime(), lastProgress = startedAt, measuredEndUs = startUs;
            while (!outputEnded) {
                check(entry);
                long now = SystemClock.elapsedRealtime();
                if (now - startedAt > MAX_DECODE_MS) throw new IllegalStateException("Audio peak decode time limit reached; preview remains available");
                if (now - lastProgress > MAX_IDLE_MS) throw new IllegalStateException("Audio decoder stopped producing samples");
                if (!inputEnded) {
                    int input = decoder.dequeueInputBuffer(10000L);
                    if (input >= 0) {
                        ByteBuffer buffer = decoder.getInputBuffer(input);
                        if (buffer == null) throw new IllegalStateException("Audio decoder input buffer is unavailable");
                        long time = extractor.getSampleTime();
                        if (time < 0L || time >= add(endUs, 1000000L)) {
                            decoder.queueInputBuffer(input, 0, 0, Math.max(0L, time), MediaCodec.BUFFER_FLAG_END_OF_STREAM); inputEnded = true;
                        } else {
                            if (time < startUs - 30000000L) throw new IllegalStateException("Audio source cannot seek close enough to this bounded window");
                            if ((extractor.getSampleFlags() & MediaExtractor.SAMPLE_FLAG_ENCRYPTED) != 0) throw new IllegalStateException("Protected audio cannot be measured by the waveform decoder");
                            if (extractor.getSampleSize() > buffer.capacity()) throw new IllegalStateException("Audio packet exceeds the decoder buffer limit");
                            buffer.clear(); int bytes = extractor.readSampleData(buffer, 0);
                            if (bytes < 0) { decoder.queueInputBuffer(input, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inputEnded = true; }
                            else {
                                int flags = (extractor.getSampleFlags() & MediaExtractor.SAMPLE_FLAG_PARTIAL_FRAME) != 0 ? MediaCodec.BUFFER_FLAG_PARTIAL_FRAME : 0;
                                decoder.queueInputBuffer(input, 0, bytes, time, flags); extractor.advance();
                            }
                        }
                        lastProgress = SystemClock.elapsedRealtime();
                    }
                }
                int output = decoder.dequeueOutputBuffer(info, 10000L);
                if (output == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    MediaFormat format = decoder.getOutputFormat();
                    sampleRate = integer(format, MediaFormat.KEY_SAMPLE_RATE, sampleRate);
                    channels = integer(format, MediaFormat.KEY_CHANNEL_COUNT, channels);
                    encoding = integer(format, MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_16BIT);
                    validatePcm(sampleRate, channels, encoding); lastProgress = SystemClock.elapsedRealtime();
                } else if (output >= 0) {
                    try {
                        validatePcm(sampleRate, channels, encoding);
                        if (info.size > 0 && (info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                            if (info.size > MAX_OUTPUT_BYTES) throw new IllegalStateException("Decoded audio buffer exceeds waveform limits");
                            ByteBuffer raw = decoder.getOutputBuffer(output);
                            if (raw == null || info.offset < 0 || info.size > raw.capacity() - info.offset) throw new IllegalStateException("Invalid decoded audio buffer");
                            ByteBuffer pcm = raw.duplicate().order(ByteOrder.nativeOrder());
                            pcm.position(info.offset); pcm.limit(info.offset + info.size);
                            int bytes = encoding == AudioFormat.ENCODING_PCM_FLOAT ? Float.BYTES : Short.BYTES;
                            int frameBytes = bytes * channels;
                            if (pcm.remaining() % frameBytes != 0) throw new IllegalStateException("Unaligned decoded PCM audio");
                            long frameIndex = 0L;
                            while (pcm.hasRemaining()) {
                                if ((frameIndex & 4095L) == 0L) { check(entry); if (SystemClock.elapsedRealtime() - startedAt > MAX_DECODE_MS) throw new IllegalStateException("Audio peak decode time limit reached"); }
                                long time = info.presentationTimeUs + frameIndex * 1000000L / sampleRate;
                                float peak = 0f;
                                for (int channel = 0; channel < channels; channel++) {
                                    float sample = bytes == Float.BYTES ? pcm.getFloat() : pcm.getShort() / 32768f;
                                    if (Float.isFinite(sample)) peak = Math.max(peak, Math.min(1f, Math.abs(sample)));
                                }
                                if (time >= startUs && time < endUs) {
                                    int bin = (int) Math.min(bins - 1L, (time - startUs) * bins / (endUs - startUs));
                                    peaks[bin] = Math.max(peaks[bin], peak); measuredAny = true;
                                }
                                frameIndex++;
                            }
                            long bufferEndUs = info.presentationTimeUs + frameIndex * 1000000L / sampleRate;
                            measuredEndUs = Math.max(measuredEndUs, bufferEndUs);
                            if (bufferEndUs >= endUs) outputEnded = true;
                        }
                        if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) outputEnded = true;
                    } finally { decoder.releaseOutputBuffer(output, false); }
                    lastProgress = SystemClock.elapsedRealtime();
                }
            }
            if (!measuredAny) throw new IllegalStateException("No decoded audio samples overlap this source window");
            // A known shorter audio track has a deliberate silent tail in a video.
            // Unexpected early EOS must not turn an incomplete decode into ready peaks.
            if (measuredEndUs < expectedAudioEndUs - 100000L)
                throw new IllegalStateException("Audio decoding ended before the requested source window was measured");
            return new Waveform(durationMs, startMs, endMs, peaks);
        } finally {
            if (decoder != null) { if (started) try { decoder.stop(); } catch (Exception ignored) {} try { decoder.release(); } catch (Exception ignored) {} }
            extractor.release();
        }
    }

    private static MediaCodec createDecoder(MediaFormat format) throws Exception {
        String mime = format.getString(MediaFormat.KEY_MIME);
        for (MediaCodecInfo codec : new MediaCodecList(MediaCodecList.REGULAR_CODECS).getCodecInfos()) {
            if (codec.isEncoder() || !codec.isSoftwareOnly()) continue;
            try {
                if (Arrays.asList(codec.getSupportedTypes()).contains(mime) && codec.getCapabilitiesForType(mime).isFormatSupported(format))
                    return MediaCodec.createByCodecName(codec.getName());
            } catch (Exception unavailable) { /* Try another decoder before the platform default. */ }
        }
        return MediaCodec.createDecoderByType(mime);
    }

    private static void validatePcm(int rate, int channels, int encoding) {
        if (rate < 8000 || rate > 384000 || channels < 1 || channels > 32
                || encoding != AudioFormat.ENCODING_PCM_16BIT && encoding != AudioFormat.ENCODING_PCM_FLOAT)
            throw new IllegalStateException("Waveform decoder requires supported PCM16 or float audio");
    }

    private static final class Identity {
        final String fingerprint;
        Identity(String fingerprint) { this.fingerprint = fingerprint; }
    }

    private Identity identity(Entry entry) throws Exception {
        check(entry); Uri uri = Uri.parse(entry.source.uri); long size = -1L, modified = -1L;
        if ("file".equals(uri.getScheme())) {
            File file = new File(uri.getPath());
            if (!file.isFile() || !file.canRead()) throw new IllegalStateException("Audio source file is unavailable");
            size = file.length(); modified = file.lastModified();
        } else {
            try (Cursor cursor = context.getContentResolver().query(uri, new String[]{OpenableColumns.SIZE,
                    DocumentsContract.Document.COLUMN_LAST_MODIFIED}, null, null, null, entry.cancellation)) {
                if (cursor != null && cursor.moveToFirst()) {
                    int sizeColumn = cursor.getColumnIndex(OpenableColumns.SIZE), timeColumn = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_LAST_MODIFIED);
                    if (sizeColumn >= 0 && !cursor.isNull(sizeColumn)) size = cursor.getLong(sizeColumn);
                    if (timeColumn >= 0 && !cursor.isNull(timeColumn)) modified = cursor.getLong(timeColumn);
                }
            } catch (IllegalArgumentException unsupportedColumns) {
                try (Cursor cursor = context.getContentResolver().query(uri, new String[]{OpenableColumns.SIZE}, null, null, null, entry.cancellation)) {
                    if (cursor != null && cursor.moveToFirst() && !cursor.isNull(0)) size = cursor.getLong(0);
                }
            }
            try (AssetFileDescriptor descriptor = context.getContentResolver().openAssetFileDescriptor(uri, "r", entry.cancellation)) {
                if (descriptor == null) throw new IllegalStateException("Audio source is unavailable");
                if (size < 0L) size = descriptor.getLength() >= 0L ? descriptor.getLength() : descriptor.getParcelFileDescriptor().getStatSize();
            }
        }
        check(entry);
        return new Identity(hash(entry.source.declaredKey + "\n" + size + "\n" + modified));
    }

    private File cacheDirectory() {
        File directory = new File(context.getCacheDir(), "editor_waveforms");
        if (!directory.isDirectory() && !directory.mkdirs() && !directory.isDirectory()) throw new IllegalStateException("Could not prepare private audio peak cache");
        return directory;
    }

    private Waveform readCache(File file, String identity, Entry entry) {
        if (!file.isFile() || file.length() > MAX_RECORD_BYTES || file.length() < 48L) return null;
        CRC32 checksum = new CRC32();
        try (DataInputStream input = new DataInputStream(new CheckedInputStream(new BufferedInputStream(new FileInputStream(file)), checksum))) {
            check(entry);
            if (input.readInt() != MAGIC || input.readInt() != CACHE_VERSION || !identity.equals(input.readUTF())) return null;
            long duration = input.readLong(), start = input.readLong(), end = input.readLong(); int count = input.readInt();
            if (duration <= 0L || start != entry.window.startMs || end <= start || end > entry.window.endMs || end > duration || count <= 0 || count > MAX_BINS) return null;
            float[] values = new float[count];
            for (int index = 0; index < count; index++) { values[index] = input.readFloat(); if (!Float.isFinite(values[index]) || values[index] < 0f || values[index] > 1f) return null; }
            long actualChecksum = checksum.getValue();
            if (input.readLong() != actualChecksum) return null;
            if (input.read() != -1) return null;
            return new Waveform(duration, start, end, values);
        } catch (Exception invalid) { return null; }
    }

    private void writeCache(File file, String identity, Waveform waveform, Entry entry) throws Exception {
        File temp = new File(file.getParentFile(), file.getName() + "." + java.util.UUID.randomUUID() + ".tmp");
        try {
            check(entry);
            CRC32 checksum = new CRC32();
            try (FileOutputStream stream = new FileOutputStream(temp);
                 DataOutputStream output = new DataOutputStream(new CheckedOutputStream(new BufferedOutputStream(stream), checksum))) {
                output.writeInt(MAGIC); output.writeInt(CACHE_VERSION); output.writeUTF(identity);
                output.writeLong(waveform.durationMs); output.writeLong(waveform.sampledStartMs); output.writeLong(waveform.sampledEndMs);
                output.writeInt(waveform.measured.length);
                for (float peak : waveform.measured) output.writeFloat(peak);
                output.writeLong(checksum.getValue());
                output.flush(); stream.getFD().sync();
            }
            if (temp.length() > MAX_RECORD_BYTES) throw new IllegalStateException("Audio peak cache record exceeds its budget");
            check(entry);
            Files.move(temp.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally { if (temp.exists()) temp.delete(); }
    }

    private static void pruneDisk(File directory) {
        File[] files = directory.listFiles(); if (files == null) return;
        Arrays.sort(files, (a, b) -> Long.compare(a.lastModified(), b.lastModified()));
        long bytes = 0L; int count = 0;
        for (File file : files) if (file.isFile()) { if (file.getName().endsWith(".tmp") || file.length() > MAX_RECORD_BYTES) file.delete(); else { bytes += file.length(); count++; } }
        for (File file : files) if (file.isFile() && (bytes > MAX_DISK_BYTES || count > MAX_DISK_FILES)) {
            long size = file.length(); if (file.delete()) { bytes -= size; count--; }
        }
    }

    private void pruneEntries() {
        java.util.Iterator<Map.Entry<String, Entry>> iterator = entries.entrySet().iterator();
        while (entries.size() > 128 && iterator.hasNext()) { Map.Entry<String, Entry> entry = iterator.next(); if (!entry.getValue().pending()) { memory.remove(entry.getKey()); iterator.remove(); } }
    }
    private static void addCallback(Entry entry, Runnable callback) {
        if (callback != null && !entry.callbacks.contains(callback) && entry.callbacks.size() < 128) entry.callbacks.add(callback);
    }
    private void finishCallbacks(Entry entry) {
        ArrayList<Runnable> callbacks = new ArrayList<>(entry.callbacks); entry.callbacks.clear();
        for (Runnable callback : callbacks) deliver(callback);
    }
    private void deliver(Runnable callback) { if (callback != null && !closed) main.post(() -> { if (!closed) callback.run(); }); }
    private void check(Entry entry) throws InterruptedException {
        if (closed || entry.cancellation.isCanceled() || Thread.currentThread().isInterrupted()) throw new InterruptedException("Audio peak request cancelled");
    }
    void close() {
        closed = true;
        synchronized (lock) {
            for (Entry entry : entries.values()) { entry.cancellation.cancel(); if (entry.future != null) entry.future.cancel(true); entry.callbacks.clear(); }
            entries.clear(); memory.evictAll();
        }
        WORKER.purge(); main.removeCallbacksAndMessages(null);
    }
    private static int integer(MediaFormat format, String key, int fallback) { return format.containsKey(key) ? format.getInteger(key) : fallback; }
    private static long add(long a, long b) { if (b > 0L && a > Long.MAX_VALUE - b) throw new IllegalArgumentException("Audio source window is too large"); return a + b; }
    private static String hash(String value) {
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            char[] hex = new char[bytes.length * 2]; char[] digits = "0123456789abcdef".toCharArray();
            for (int index = 0; index < bytes.length; index++) { int item = bytes[index] & 255; hex[index * 2] = digits[item >>> 4]; hex[index * 2 + 1] = digits[item & 15]; }
            return new String(hex);
        } catch (Exception unavailable) { throw new IllegalStateException("SHA-256 is unavailable", unavailable); }
    }
}
