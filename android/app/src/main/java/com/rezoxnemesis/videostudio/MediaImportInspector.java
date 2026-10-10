package com.rezoxnemesis.videostudio;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.MediaMetadataRetriever;
import android.media.ExifInterface;
import android.webkit.MimeTypeMap;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/** Inspects a completed private import with bounded decoding, without publishing media. */
public final class MediaImportInspector {
    public static final long MAX_IMAGE_PIXELS = 80_000_000L;
    private static final int PREFIX_BYTES = 4096;
    private static final int MAX_SAMPLE_EDGE = 512;

    public static final class Metadata {
        public final String mime;
        public final long durationMs;
        /** Encoded dimensions; rotation is reported separately. Audio dimensions are zero. */
        public final int width;
        public final int height;
        public final int rotation;
        public final long sizeBytes;
        public final boolean hasAudio;
        public final int exifOrientation;

        private Metadata(String mime, long durationMs, int width, int height, int rotation,
                         long sizeBytes, boolean hasAudio) {
            this(mime, durationMs, width, height, rotation, sizeBytes, hasAudio, ExifInterface.ORIENTATION_NORMAL);
        }

        private Metadata(String mime, long durationMs, int width, int height, int rotation,
                         long sizeBytes, boolean hasAudio, int exifOrientation) {
            this.mime = mime;
            this.durationMs = durationMs;
            this.width = width;
            this.height = height;
            this.rotation = rotation;
            this.sizeBytes = sizeBytes;
            this.hasAudio = hasAudio;
            this.exifOrientation = exifOrientation;
        }
    }

    private MediaImportInspector() {}

    /**
     * The file must be complete and remain unchanged during inspection. MIME and name are hints,
     * never evidence that an otherwise unreadable file is media. Image pixels are sampled to at
     * most 512 by 512; no video frame is read. This is not full-file integrity validation.
     */
    public static Metadata inspect(File file, String mimeHint, String displayName) throws IOException {
        if (file == null || !file.isFile() || !file.canRead()) {
            throw new IOException("Imported media file is not readable");
        }
        long size = file.length();
        if (size <= 0) throw new IOException("Imported media file is empty");

        byte[] prefix = new byte[PREFIX_BYTES];
        int prefixLength = 0;
        try (FileInputStream input = new FileInputStream(file)) {
            while (prefixLength < prefix.length) {
                int read = input.read(prefix, prefixLength, prefix.length - prefixLength);
                if (read < 0) break;
                if (read == 0) break;
                prefixLength += read;
            }
        }
        if (prefixLength == 0) throw new IOException("Imported media file is empty");
        if (isErrorDocument(prefix, prefixLength)) {
            throw new IOException("Import returned an HTML, XML or JSON document instead of media");
        }

        String signatureMime = signatureMime(prefix, prefixLength);
        String nameMime = nameMime(displayName);
        if (nameMime.isEmpty()) nameMime = nameMime(file.getName());
        String hintedMime = normalizeMime(mimeHint);

        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        try {
            BitmapFactory.decodeFile(file.getAbsolutePath(), bounds);
        } catch (RuntimeException ignored) {
            // Failure of the image decoder does not rule out a supported audio/video container.
            bounds.outWidth = -1;
            bounds.outHeight = -1;
        }
        if (bounds.outWidth > 0 && bounds.outHeight > 0) {
            long pixels = (long) bounds.outWidth * bounds.outHeight;
            if (pixels > MAX_IMAGE_PIXELS) {
                throw new IOException("Imported image exceeds VideoStudio's 80 million pixel limit");
            }
            validateImageSample(file, bounds.outWidth, bounds.outHeight);
            String mime = chooseMime("image", normalizeMime(bounds.outMimeType), signatureMime,
                    nameMime, hintedMime);
            int orientation = ExifInterface.ORIENTATION_NORMAL;
            try {
                orientation = new ExifInterface(file.getAbsolutePath()).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);
                if (orientation < ExifInterface.ORIENTATION_NORMAL || orientation > ExifInterface.ORIENTATION_ROTATE_270)
                    orientation = ExifInterface.ORIENTATION_NORMAL;
            } catch (IOException unavailable) {
                // Formats without supported EXIF retain their encoded orientation.
            }
            int imageRotation = orientation == ExifInterface.ORIENTATION_ROTATE_90 || orientation == ExifInterface.ORIENTATION_TRANSPOSE ? 90
                    : orientation == ExifInterface.ORIENTATION_ROTATE_270 || orientation == ExifInterface.ORIENTATION_TRANSVERSE ? 270
                    : orientation == ExifInterface.ORIENTATION_ROTATE_180 ? 180 : 0;
            ensureUnchanged(file, size);
            return new Metadata(mime, 0L, bounds.outWidth, bounds.outHeight, imageRotation, size, false, orientation);
        }

        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        try {
            // A descriptor pins the local file and cannot cause a remote fetch.
            try (FileInputStream input = new FileInputStream(file)) {
                retriever.setDataSource(input.getFD());
                String detectedMime = normalizeMime(
                        retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_MIMETYPE));
                long duration = nonNegativeLong(
                        retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION));
                int width = positiveInt(
                        retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH));
                int height = positiveInt(
                        retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT));
                boolean video = isYes(retriever.extractMetadata(
                        MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO)) || (width > 0 && height > 0);
                boolean audio = isYes(retriever.extractMetadata(
                        MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO));
                // Some Android extractors omit HAS_AUDIO for an otherwise identified audio file.
                if (!video && detectedMime.startsWith("audio/") && duration > 0) audio = true;
                if ((!video && !audio) || duration <= 0) {
                    throw new IOException("Import is not readable audio or video with a positive duration");
                }
                if (video && (width <= 0 || height <= 0)) {
                    throw new IOException("Imported video has no readable dimensions");
                }
                String kind = video ? "video" : "audio";
                String mime = chooseMime(kind, detectedMime, signatureMime, nameMime, hintedMime);
                int rotation = video ? rotation(retriever.extractMetadata(
                        MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)) : 0;
                ensureUnchanged(file, size);
                return new Metadata(mime, duration, video ? width : 0, video ? height : 0,
                        rotation, size, audio);
            }
        } catch (RuntimeException error) {
            throw new IOException("Import is not a supported readable image, audio or video file", error);
        } finally {
            try { retriever.release(); } catch (Exception ignored) {}
        }
    }

    private static void ensureUnchanged(File file, long size) throws IOException {
        if (!file.isFile() || file.length() != size) {
            throw new IOException("Imported media changed during inspection");
        }
    }

    private static void validateImageSample(File file, int width, int height) throws IOException {
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inPreferredConfig = Bitmap.Config.ARGB_8888;
        options.inScaled = false;
        options.inSampleSize = 1;
        // Android rounds inSampleSize down to a power of two; use powers of two explicitly.
        while (((long) width + options.inSampleSize - 1) / options.inSampleSize > MAX_SAMPLE_EDGE
                || ((long) height + options.inSampleSize - 1) / options.inSampleSize > MAX_SAMPLE_EDGE) {
            options.inSampleSize *= 2;
        }
        Bitmap sample = null;
        try {
            sample = BitmapFactory.decodeFile(file.getAbsolutePath(), options);
            if (sample == null || sample.getWidth() <= 0 || sample.getHeight() <= 0) {
                throw new IOException("Imported image has no readable pixel data");
            }
            if (sample.getWidth() > MAX_SAMPLE_EDGE || sample.getHeight() > MAX_SAMPLE_EDGE) {
                throw new IOException("Imported image could not be decoded within the safe sample limit");
            }
        } catch (RuntimeException error) {
            throw new IOException("Imported image pixel data is not readable", error);
        } finally {
            if (sample != null) sample.recycle();
        }
    }

    private static String chooseMime(String kind, String... candidates) {
        for (String candidate : candidates) {
            String mime = normalizeMime(candidate);
            // Containers can hold just audio despite a video MIME from their extractor/header.
            if ("audio".equals(kind)) {
                if ("video/mp4".equals(mime) || "application/mp4".equals(mime)) mime = "audio/mp4";
                else if ("video/webm".equals(mime)) mime = "audio/webm";
                else if ("video/x-matroska".equals(mime)) mime = "audio/x-matroska";
                else if ("video/3gpp".equals(mime)) mime = "audio/3gpp";
                else if ("video/3gpp2".equals(mime)) mime = "audio/3gpp2";
                else if ("video/quicktime".equals(mime)) mime = "audio/quicktime";
                else if ("video/ogg".equals(mime)) mime = "audio/ogg";
                else if ("video/x-msvideo".equals(mime)) mime = "audio/x-msvideo";
                else if ("video/x-ms-asf".equals(mime)) mime = "audio/x-ms-asf";
            }
            if ("application/ogg".equals(mime)) mime = kind + "/ogg";
            if (mime.startsWith(kind + "/") && !mime.endsWith("/*")) return mime;
        }
        // Decoder evidence establishes the kind even when a platform omits its format MIME.
        return kind + "/octet-stream";
    }

    static String normalizeMime(String value) {
        if (value == null) return "";
        int parameters = value.indexOf(';');
        String mime = (parameters < 0 ? value : value.substring(0, parameters))
                .trim().toLowerCase(Locale.US);
        if ("image/jpg".equals(mime)) return "image/jpeg";
        if ("audio/mp3".equals(mime) || "audio/x-mp3".equals(mime)) return "audio/mpeg";
        if ("audio/x-m4a".equals(mime) || "audio/m4a".equals(mime)) return "audio/mp4";
        if ("video/x-m4v".equals(mime)) return "video/mp4";
        if ("audio/x-wav".equals(mime) || "audio/wave".equals(mime)) return "audio/wav";
        if ("audio/x-flac".equals(mime)) return "audio/flac";
        if ("application/x-ogg".equals(mime)) return "application/ogg";
        if ("application/octet-stream".equals(mime) || "binary/octet-stream".equals(mime)
                || "application/binary".equals(mime) || "application/unknown".equals(mime)
                || mime.endsWith("/octet-stream") || mime.endsWith("/unknown")
                || mime.endsWith("/*")) return "";
        int slash = mime.indexOf('/');
        if (slash <= 0 || slash == mime.length() - 1 || slash != mime.lastIndexOf('/')) return "";
        for (int i = 0; i < mime.length(); i++) {
            char c = mime.charAt(i);
            if (!(c >= 'a' && c <= 'z') && !(c >= '0' && c <= '9')
                    && "!#$&^_.+-*/".indexOf(c) < 0) return "";
        }
        return mime;
    }

    static String nameMime(String name) {
        if (name == null) return "";
        int query = name.indexOf('?');
        if (query >= 0) name = name.substring(0, query);
        int fragment = name.indexOf('#');
        if (fragment >= 0) name = name.substring(0, fragment);
        int dot = name.lastIndexOf('.');
        if (dot < 0 || dot <= Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'))) return "";
        String extension = name.substring(dot + 1).toLowerCase(Locale.US);
        switch (extension) {
            case "jpg": case "jpeg": case "jpe": return "image/jpeg";
            case "png": return "image/png";
            case "gif": return "image/gif";
            case "webp": return "image/webp";
            case "bmp": return "image/bmp";
            case "heic": return "image/heic";
            case "heif": return "image/heif";
            case "avif": return "image/avif";
            case "mp4": case "m4v": return "video/mp4";
            case "mov": return "video/quicktime";
            case "webm": return "video/webm";
            case "mkv": return "video/x-matroska";
            case "3gp": case "3gpp": return "video/3gpp";
            case "3g2": return "video/3gpp2";
            case "ts": case "mts": case "m2ts": return "video/mp2t";
            case "mpg": case "mpeg": return "video/mpeg";
            case "avi": return "video/x-msvideo";
            case "mp3": return "audio/mpeg";
            case "m4a": case "m4b": return "audio/mp4";
            case "aac": return "audio/aac";
            case "wav": return "audio/wav";
            case "flac": return "audio/flac";
            case "ogg": case "oga": case "opus": return "audio/ogg";
            case "ogv": return "video/ogg";
            case "amr": return "audio/amr";
            case "awb": return "audio/amr-wb";
            case "aif": case "aiff": case "aifc": return "audio/aiff";
            case "ac3": return "audio/ac3";
            default: return normalizeMime(MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension));
        }
    }

    static String signatureMime(byte[] prefix, int length) {
        if (starts(prefix, length, 0, 0xff, 0xd8, 0xff)) return "image/jpeg";
        if (starts(prefix, length, 0, 0x89, 'P', 'N', 'G', 13, 10, 26, 10)) return "image/png";
        if (ascii(prefix, length, 0, "GIF87a") || ascii(prefix, length, 0, "GIF89a")) return "image/gif";
        if (ascii(prefix, length, 0, "BM")) return "image/bmp";
        if (ascii(prefix, length, 0, "RIFF")) {
            if (ascii(prefix, length, 8, "WEBP")) return "image/webp";
            if (ascii(prefix, length, 8, "WAVE")) return "audio/wav";
            if (ascii(prefix, length, 8, "AVI ")) return "video/x-msvideo";
        }
        if (ascii(prefix, length, 4, "ftyp") && length >= 12) {
            String brand = new String(prefix, 8, 4, StandardCharsets.US_ASCII);
            if ("avif".equals(brand) || "avis".equals(brand)) return "image/avif";
            if ("heic".equals(brand) || "heix".equals(brand)
                    || "hevc".equals(brand) || "hevx".equals(brand)) return "image/heic";
            if ("mif1".equals(brand) || "msf1".equals(brand)) return "image/heif";
            if ("qt  ".equals(brand)) return "video/quicktime";
            if ("M4A ".equals(brand) || "M4B ".equals(brand)
                    || "M4P ".equals(brand) || "M4R ".equals(brand)) return "audio/mp4";
            if (brand.startsWith("3gp")) return "video/3gpp";
            if (brand.startsWith("3g2")) return "video/3gpp2";
            return "video/mp4";
        }
        if (ascii(prefix, length, 0, "fLaC")) return "audio/flac";
        if (ascii(prefix, length, 0, "OggS")) return "application/ogg";
        if (ascii(prefix, length, 0, "#!AMR-WB\n")) return "audio/amr-wb";
        if (ascii(prefix, length, 0, "#!AMR\n")) return "audio/amr";
        if (ascii(prefix, length, 0, "ID3")) return "audio/mpeg";
        if (ascii(prefix, length, 0, "ADIF")) return "audio/aac";
        if (ascii(prefix, length, 0, "FORM")
                && (ascii(prefix, length, 8, "AIFF") || ascii(prefix, length, 8, "AIFC"))) return "audio/aiff";
        if (starts(prefix, length, 0, 0x1a, 0x45, 0xdf, 0xa3)) return "video/x-matroska";
        if (starts(prefix, length, 0, 0, 0, 1, 0xba)
                || starts(prefix, length, 0, 0, 0, 1, 0xb3)) return "video/mpeg";
        if (starts(prefix, length, 0, 0x0b, 0x77)) return "audio/ac3";
        if (length >= 2 && (prefix[0] & 0xff) == 0xff) {
            int second = prefix[1] & 0xff;
            if ((second & 0xf6) == 0xf0) return "audio/aac";
            if ((second & 0xe0) == 0xe0 && (second & 0x18) != 0x08
                    && (second & 0x06) != 0) return "audio/mpeg";
        }
        if (length > 376 && (prefix[0] & 0xff) == 0x47
                && (prefix[188] & 0xff) == 0x47 && (prefix[376] & 0xff) == 0x47) return "video/mp2t";
        return "";
    }

    static boolean isErrorDocument(byte[] prefix, int length) {
        int offset = starts(prefix, length, 0, 0xef, 0xbb, 0xbf) ? 3 : 0;
        while (offset < length && isWhitespace(prefix[offset] & 0xff)) offset++;
        if (offset >= length) return false;
        String text = new String(prefix, offset, Math.min(length - offset, 512),
                StandardCharsets.UTF_8).toLowerCase(Locale.US);
        if (text.startsWith("<!doctype html") || text.startsWith("<html")
                || text.startsWith("<head") || text.startsWith("<body")
                || text.startsWith("<?xml") || text.startsWith("<error")
                || text.startsWith("<svg")) return true;
        if (prefix[offset] != '{' && prefix[offset] != '[') return false;
        // JSON error responses may be longer than the prefix; only reject a text-shaped body.
        for (int i = offset; i < length; i++) {
            int value = prefix[i] & 0xff;
            if (value < 0x20 && !isWhitespace(value)) return false;
        }
        return true;
    }

    private static boolean isWhitespace(int value) {
        return value == ' ' || value == '\t' || value == '\r' || value == '\n';
    }

    private static boolean ascii(byte[] prefix, int length, int offset, String value) {
        if (offset < 0 || length - offset < value.length()) return false;
        for (int i = 0; i < value.length(); i++) {
            if ((prefix[offset + i] & 0xff) != value.charAt(i)) return false;
        }
        return true;
    }

    private static boolean starts(byte[] prefix, int length, int offset, int... bytes) {
        if (offset < 0 || length - offset < bytes.length) return false;
        for (int i = 0; i < bytes.length; i++) {
            if ((prefix[offset + i] & 0xff) != bytes[i]) return false;
        }
        return true;
    }

    private static long nonNegativeLong(String value) {
        try {
            long parsed = Long.parseLong(value);
            return parsed >= 0 ? parsed : -1L;
        } catch (NumberFormatException error) { return -1L; }
    }

    private static int positiveInt(String value) {
        long parsed = nonNegativeLong(value);
        return parsed > 0 && parsed <= Integer.MAX_VALUE ? (int) parsed : 0;
    }

    private static int rotation(String value) {
        try { return Math.floorMod(Integer.parseInt(value), 360); }
        catch (NumberFormatException error) { return 0; }
    }

    private static boolean isYes(String value) {
        return "yes".equalsIgnoreCase(value) || "true".equalsIgnoreCase(value) || "1".equals(value);
    }
}
