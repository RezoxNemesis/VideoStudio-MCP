package com.rezoxnemesis.videostudio;

import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.junit.Assert.*;

/** Bounded prefix parsing and pre-decoder validation regressions. */
public class MediaImportInspectorTest {
    @Test public void normalizesParameterizedMimeAndIgnoresGenericTypes() {
        assertEquals("image/jpeg", MediaImportInspector.normalizeMime(" Image/JPG; charset=binary "));
        assertEquals("audio/mp4", MediaImportInspector.normalizeMime("audio/x-m4a"));
        assertEquals("", MediaImportInspector.normalizeMime("application/octet-stream"));
        assertEquals("", MediaImportInspector.normalizeMime("video/octet-stream"));
        assertEquals("", MediaImportInspector.normalizeMime("video/*"));
        assertEquals("", MediaImportInspector.normalizeMime("video/mp4\r\nX-Fake: value"));
    }

    @Test public void recognizesImageAudioAndContainerSignatures() {
        assertEquals("image/png", signature(new byte[]{(byte) 0x89, 'P', 'N', 'G', 13, 10, 26, 10}));
        assertEquals("image/webp", signature(ascii("RIFF\0\0\0\0WEBP")));
        assertEquals("audio/wav", signature(ascii("RIFF\0\0\0\0WAVE")));
        assertEquals("audio/flac", signature(ascii("fLaC")));
        assertEquals("application/ogg", signature(ascii("OggS")));
        assertEquals("audio/mp4", signature(ascii("\0\0\0\24ftypM4A ")));
        assertEquals("image/avif", signature(ascii("\0\0\0\24ftypavif")));
        assertEquals("video/mp4", signature(ascii("\0\0\0\24ftypisom")));
    }

    @Test public void shortAndUnknownSignaturesDoNotInventMediaTypes() {
        assertEquals("", signature(new byte[]{(byte) 0xff}));
        assertEquals("", signature(ascii("unknown")));
        byte[] padded = ascii("RIFF\0\0\0\0WEBP");
        assertEquals("", MediaImportInspector.signatureMime(padded, 8));
    }

    @Test public void recognizesSignedUrlErrorDocumentsWithWhitespaceAndBom() {
        assertTrue(document(" \n<!DOCTYPE HTML><html>Access denied</html>"));
        assertTrue(document("\ufeff\t{\"error\":\"expired\"}"));
        assertTrue(document("[ {\"error\":\"expired\"} ]"));
        assertTrue(document("<?xml version=\"1.0\"?><Error>Expired</Error>"));
        assertFalse(document("fLaC"));
        assertFalse(MediaImportInspector.isErrorDocument(new byte[]{'{', 0, 1}, 3));
    }

    @Test public void nameFallbackHandlesQuerySuffixesWithoutTreatingDirectoryAsExtension() {
        assertEquals("video/mp4", MediaImportInspector.nameMime("https://example.invalid/clip.MP4?token=redacted"));
        assertEquals("audio/mp4", MediaImportInspector.nameMime("voice.m4a"));
        assertEquals("image/heic", MediaImportInspector.nameMime("photo.heic#ignored"));
        assertEquals("", MediaImportInspector.nameMime("folder.mp4/media"));
        assertEquals("", MediaImportInspector.nameMime(null));
    }

    @Test public void rejectsMissingAndEmptyFilesBeforeCallingAndroidDecoders() throws Exception {
        assertUnreadable(null);
        File empty = File.createTempFile("media-import-empty", ".mp4");
        try { assertUnreadable(empty); }
        finally { empty.delete(); }
    }

    private static void assertUnreadable(File file) throws Exception {
        try {
            MediaImportInspector.inspect(file, "video/mp4", "clip.mp4");
            fail("Missing or empty files must not become media assets");
        } catch (IOException expected) {
            assertNotNull(expected.getMessage());
        }
    }

    private static byte[] ascii(String value) { return value.getBytes(StandardCharsets.US_ASCII); }
    private static String signature(byte[] bytes) { return MediaImportInspector.signatureMime(bytes, bytes.length); }
    private static boolean document(String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        return MediaImportInspector.isErrorDocument(bytes, bytes.length);
    }
}
