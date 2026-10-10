package com.rezoxnemesis.videostudio;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.RandomAccessFile;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 33, manifest = Config.NONE)
public class ResumableTransferIntegrityTest {
    private static final byte[] COMPLETE = {1, 2, 3, 4, 5, 6};
    private static final String DATE = "Wed, 07 Oct 2026 10:00:00 GMT";

    @Test public void failedIntegrityCheckpointRestartsBeforeRequestingEof() throws Exception {
        try (Fixture fixture = new Fixture("failed-checksum")) {
            try {
                fixture.download(6, sha(COMPLETE), (source, offset, etag, modified) ->
                        new Response(200, new byte[]{9, 9, 9, 9, 9, 9}).header("ETag", "\"old\""));
                fail("Incorrect completed bytes must fail checksum validation");
            } catch (IllegalStateException expected) { assertTrue(expected.getMessage().contains("SHA-256 mismatch")); }
            assertEquals("failed_integrity", fixture.journal.get(fixture.id).state);
            assertEquals(6L, fixture.partial.length());
            assertFalse(fixture.target.exists());
            List<Long> offsets = new ArrayList<>();
            ResumableTransferManager.Result result = fixture.download(6, sha(COMPLETE), (source, offset, etag, modified) -> {
                offsets.add(offset);
                assertEquals("", etag);
                return new Response(200, COMPLETE);
            });
            assertEquals(Arrays.asList(0L), offsets);
            assertFalse(result.resumed);
            fixture.assertComplete(COMPLETE);
        }
    }

    @Test public void absentWeakOrInvalidValidatorsWithoutChecksumRestartFromZero() throws Exception {
        for (String etag : Arrays.asList("", "W/\"weak\"", "unquoted")) {
            try (Fixture fixture = new Fixture("no-stable-validator")) {
                fixture.checkpoint(new byte[]{9, 9, 9}, 6, etag, "not an HTTP date", "", "paused");
                ResumableTransferManager.Result result = fixture.download(6, "", (source, offset, sentTag, modified) -> {
                    assertEquals(0L, offset);
                    assertEquals("", sentTag);
                    assertEquals("", modified);
                    return new Response(200, COMPLETE);
                });
                assertFalse(result.resumed);
                fixture.assertComplete(COMPLETE);
            }
        }
    }

    @Test public void checksumAllowsResumeWithoutResponseValidator() throws Exception {
        try (Fixture fixture = new Fixture("checksum-resume")) {
            fixture.checkpoint(new byte[]{1, 2, 3}, 6, "", "", sha(COMPLETE), "paused");
            ResumableTransferManager.Result result = fixture.download(6, sha(COMPLETE), (source, offset, etag, modified) -> {
                assertEquals(3L, offset);
                return new Response(206, new byte[]{4, 5, 6}).header("Content-Range", "bytes 3-5/6");
            });
            assertTrue(result.resumed);
            fixture.assertComplete(COMPLETE);
        }
    }

    @Test public void changedEtagRestartsEvenWhenChecksumWasSupplied() throws Exception {
        try (Fixture fixture = new Fixture("changed-tag")) {
            fixture.checkpoint(new byte[]{1, 2, 3}, 6, "\"version-a\"", "", sha(COMPLETE), "paused");
            List<Long> offsets = new ArrayList<>();
            ResumableTransferManager.Result result = fixture.download(6, sha(COMPLETE), (source, offset, etag, modified) -> {
                offsets.add(offset);
                if (offset > 0) return new Response(206, new byte[]{9, 9, 9})
                        .header("Content-Range", "bytes 3-5/6").header("ETag", "\"version-b\"");
                return new Response(200, COMPLETE).header("ETag", "\"version-b\"");
            });
            assertEquals(Arrays.asList(3L, 0L), offsets);
            assertFalse(result.resumed);
            fixture.assertComplete(COMPLETE);
        }
    }

    @Test public void missingResponseEtagCannotFallBackToSecondaryDateWithoutChecksum() throws Exception {
        try (Fixture fixture = new Fixture("missing-response-tag")) {
            fixture.checkpoint(new byte[]{9, 9, 9}, 6, "\"version-a\"", DATE, "", "paused");
            List<Long> offsets = new ArrayList<>();
            ResumableTransferManager.Result result = fixture.download(6, "", (source, offset, etag, modified) -> {
                offsets.add(offset);
                if (offset > 0) return new Response(206, new byte[]{4, 5, 6})
                        .header("Content-Range", "bytes 3-5/6").header("Last-Modified", DATE);
                return new Response(200, COMPLETE);
            });
            assertEquals(Arrays.asList(3L, 0L), offsets);
            assertFalse(result.resumed);
            fixture.assertComplete(COMPLETE);
        }
    }

    @Test public void changedVersionDiscardsOldObservedSizeOnCleanRestart() throws Exception {
        try (Fixture fixture = new Fixture("changed-version-size")) {
            fixture.checkpoint(new byte[]{9, 9, 9}, 6, "\"old\"", "", "", "paused");
            List<Long> offsets = new ArrayList<>();
            byte[] replacement = {1, 2, 3, 4};
            ResumableTransferManager.Result result = fixture.download(-1, "", (source, offset, etag, modified) -> {
                offsets.add(offset);
                if (offset > 0) return new Response(206, new byte[]{9, 9, 9})
                        .header("Content-Range", "bytes 3-5/6").header("ETag", "\"new\"");
                return new Response(200, replacement).header("ETag", "\"new\"");
            });
            assertEquals(Arrays.asList(3L, 0L), offsets);
            assertEquals(4L, result.expectedBytes);
            fixture.assertComplete(replacement);
        }
    }

    @Test public void freshResponseCannotInheritOldCheckpointValidators() throws Exception {
        try (Fixture fixture = new Fixture("fresh-validators")) {
            fixture.checkpoint(new byte[0], 6, "\"old\"", DATE, "", "waiting_storage");
            Response response = new Response(200, COMPLETE);
            response.bodyOverride = new InputStream() {
                private boolean first = true;
                @Override public int read() throws java.io.IOException { throw new InterruptedIOException("stopped"); }
                @Override public int read(byte[] bytes, int offset, int length) throws java.io.IOException {
                    if (!first) throw new InterruptedIOException("stopped");
                    first = false; System.arraycopy(COMPLETE, 0, bytes, offset, 3); return 3;
                }
            };
            try {
                fixture.download(6, "", (source, offset, etag, modified) -> {
                    assertEquals("", etag); assertEquals("", modified); return response;
                });
                fail("Transfer must stop after recording the new partial");
            } catch (InterruptedIOException expected) { assertEquals("stopped", expected.getMessage()); }
            TransferJournal.Entry checkpoint = fixture.journal.get(fixture.id);
            assertEquals(3L, checkpoint.completedBytes);
            assertEquals("", checkpoint.etag);
            assertEquals("", checkpoint.lastModified);
        }
    }

    @Test public void weakEtagUsesMatchingHttpDateAndChangedDateRestarts() throws Exception {
        for (boolean changed : new boolean[]{false, true}) {
            try (Fixture fixture = new Fixture("date-resume")) {
                fixture.checkpoint(new byte[]{1, 2, 3}, 6, "W/\"weak\"", DATE, "", "paused");
                List<Long> offsets = new ArrayList<>();
                ResumableTransferManager.Result result = fixture.download(6, "", (source, offset, etag, modified) -> {
                    offsets.add(offset);
                    if (offset > 0) {
                        assertEquals("", etag);
                        assertEquals(DATE, modified);
                        return new Response(206, new byte[]{4, 5, 6}).header("Content-Range", "bytes 3-5/6")
                                .header("Last-Modified", changed ? "Thu, 08 Oct 2026 10:00:00 GMT" : DATE);
                    }
                    return new Response(200, COMPLETE);
                });
                assertEquals(changed ? Arrays.asList(3L, 0L) : Arrays.asList(3L), offsets);
                assertEquals(!changed, result.resumed);
                fixture.assertComplete(COMPLETE);
            }
        }
    }

    @Test public void unknownTotalPartialCannotUseSegmentLengthAsCompleteLength() throws Exception {
        try (Fixture fixture = new Fixture("unknown-total")) {
            Response response = new Response(206, COMPLETE).header("Content-Range", "bytes 0-5/*");
            try {
                fixture.download(-1, "", (source, offset, etag, modified) -> response);
                fail("A segment Content-Length does not prove the complete object size");
            } catch (IllegalStateException expected) {
                assertTrue(expected.getMessage().contains("complete byte count"));
            }
            assertFalse(response.bodyOpened);
            assertFalse(fixture.target.exists());
        }
    }

    @Test public void unknownTotalPartialCanCompleteUsingAuthoritativeExpectedBytes() throws Exception {
        try (Fixture fixture = new Fixture("known-size")) {
            ResumableTransferManager.Result result = fixture.download(6, "", (source, offset, etag, modified) ->
                    new Response(206, COMPLETE).header("Content-Range", "bytes 0-5/*"));
            assertEquals(6L, result.expectedBytes);
            fixture.assertComplete(COMPLETE);
        }
    }

    @Test public void declaredTotalMismatchFailsBeforeOpeningResponseBody() throws Exception {
        try (Fixture fixture = new Fixture("conflicting-total")) {
            Response response = new Response(206, COMPLETE).header("Content-Range", "bytes 0-5/6");
            try {
                fixture.download(7, "", (source, offset, etag, modified) -> response);
                fail("Response cannot override the host's expected object size");
            } catch (IllegalStateException expected) {
                assertTrue(expected.getMessage().contains("expected byte count"));
            }
            assertFalse(response.bodyOpened);
            assertFalse(fixture.target.exists());
        }
    }

    @Test public void completeCheckpointWithChecksumPromotesWithoutOpeningConnection() throws Exception {
        try (Fixture fixture = new Fixture("complete-checkpoint")) {
            fixture.checkpoint(COMPLETE, 6, "", "", sha(COMPLETE), "running");
            ResumableTransferManager.Result result = fixture.download(6, sha(COMPLETE), (source, offset, etag, modified) -> {
                fail("A checksum-proven completed partial must not issue an EOF range request");
                return null;
            });
            assertTrue(result.resumed);
            fixture.assertComplete(COMPLETE);
        }
    }

    @Test public void completeCheckpointWithoutChecksumRestartsRatherThanRequestingEof() throws Exception {
        try (Fixture fixture = new Fixture("unproven-complete")) {
            fixture.checkpoint(new byte[]{9, 9, 9, 9, 9, 9}, 6, "\"version-a\"", "", "", "running");
            ResumableTransferManager.Result result = fixture.download(6, "", (source, offset, etag, modified) -> {
                assertEquals(0L, offset);
                return new Response(200, COMPLETE);
            });
            assertFalse(result.resumed);
            fixture.assertComplete(COMPLETE);
        }
    }

    @Test public void interruptedCopyRetainsByteAccurateCheckpointThatCanResume() throws Exception {
        try (Fixture fixture = new Fixture("interrupted")) {
            Response interrupted = new Response(200, COMPLETE).header("ETag", "\"same-version\"");
            interrupted.bodyOverride = new InputStream() {
                private boolean first = true;
                @Override public int read() throws java.io.IOException { throw new InterruptedIOException("stopped"); }
                @Override public int read(byte[] bytes, int offset, int length) throws java.io.IOException {
                    if (!first) throw new InterruptedIOException("stopped");
                    first = false;
                    System.arraycopy(COMPLETE, 0, bytes, offset, 3);
                    return 3;
                }
            };
            try {
                fixture.download(6, "", (source, offset, etag, modified) -> interrupted);
                fail("Transfer must propagate interruption");
            } catch (InterruptedIOException expected) { assertEquals("stopped", expected.getMessage()); }
            assertEquals(3L, fixture.partial.length());
            assertEquals(3L, fixture.journal.get(fixture.id).completedBytes);
            assertFalse(fixture.target.exists());
            ResumableTransferManager.Result result = fixture.download(6, "", (source, offset, etag, modified) -> {
                assertEquals(3L, offset);
                return new Response(206, new byte[]{4, 5, 6}).header("Content-Range", "bytes 3-5/6")
                        .header("ETag", "\"same-version\"");
            });
            assertTrue(result.resumed);
            fixture.assertComplete(COMPLETE);
        }
    }

    @Test public void invalidContentRangesCannotPassAppendValidation() {
        assertFalse(ResumableTransferManager.canAppendResume(3, 206, "bytes 3-6/6"));
        assertFalse(ResumableTransferManager.canAppendResume(3, 206, "bytes 3-2/6"));
        assertFalse(ResumableTransferManager.canAppendResume(3, 206, "bytes 3-9223372036854775807/*"));
        assertFalse(ResumableTransferManager.canAppendResume(3, 206, "bytes 3-5/0"));
    }

    private static String sha(byte[] bytes) throws Exception {
        StringBuilder result = new StringBuilder();
        for (byte value : MessageDigest.getInstance("SHA-256").digest(bytes)) {
            result.append(String.format(Locale.US, "%02x", value & 0xff));
        }
        return result.toString();
    }

    private static final class Fixture implements AutoCloseable {
        final String id;
        final File target;
        final File partial;
        final TransferJournal journal = new TransferJournal(RuntimeEnvironment.getApplication());
        final ResumableTransferManager manager = new ResumableTransferManager(journal);
        Fixture(String label) throws Exception {
            target = File.createTempFile(label, ".bin", RuntimeEnvironment.getApplication().getCacheDir());
            target.delete();
            id = target.getName();
            partial = ResumableTransferManager.partialFileFor(target);
        }
        void checkpoint(byte[] bytes, long expected, String etag, String modified, String sha, String state) throws Exception {
            try (RandomAccessFile file = new RandomAccessFile(partial, "rw")) { file.write(bytes); }
            journal.save(new TransferJournal.Entry(id, "https://example.test/media", partial.getAbsolutePath(),
                    expected, bytes.length, etag, modified, sha, state));
        }
        ResumableTransferManager.Result download(long expected, String sha,
                                                   ResumableTransferManager.ConnectionOpener opener) throws Exception {
            return manager.download(new ResumableTransferManager.Request(id, "https://example.test/media", target, expected, sha), opener, null);
        }
        void assertComplete(byte[] expected) throws Exception {
            assertTrue(target.isFile());
            assertFalse(partial.exists());
            assertNull(journal.get(id));
            try (RandomAccessFile file = new RandomAccessFile(target, "r")) {
                assertEquals(expected.length, file.length());
                byte[] actual = new byte[expected.length]; file.readFully(actual);
                assertArrayEquals(expected, actual);
            }
        }
        @Override public void close() { target.delete(); partial.delete(); journal.remove(id); }
    }

    private static final class Response extends HttpURLConnection {
        final int code;
        final byte[] body;
        final Map<String, String> headers = new HashMap<>();
        boolean bodyOpened;
        InputStream bodyOverride;
        Response(int code, byte[] body) throws Exception {
            super(new URL("https://example.test/media")); this.code = code; this.body = body;
        }
        Response header(String name, String value) { headers.put(name, value); return this; }
        @Override public int getResponseCode() { return code; }
        @Override public String getHeaderField(String name) { return headers.get(name); }
        @Override public long getContentLengthLong() {
            String length = headers.get("Content-Length");
            return length == null ? body.length : Long.parseLong(length);
        }
        @Override public InputStream getInputStream() {
            bodyOpened = true; return bodyOverride == null ? new ByteArrayInputStream(body) : bodyOverride;
        }
        @Override public void disconnect() {}
        @Override public boolean usingProxy() { return false; }
        @Override public void connect() {}
    }
}
