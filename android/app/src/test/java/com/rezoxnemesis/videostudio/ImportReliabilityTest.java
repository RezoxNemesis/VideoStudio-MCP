package com.rezoxnemesis.videostudio;

import android.content.Context;
import android.net.Uri;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import java.io.File;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 33, manifest = Config.NONE)
public class ImportReliabilityTest {
    private Context context() { return RuntimeEnvironment.getApplication(); }

    @Test public void heavyResourceWaitDoesNotStarveImports() throws Exception {
        // Simulate another renderer owning the process-wide heavy lane.
        java.lang.reflect.Field field = JobManager.class.getDeclaredField("PROCESS_HEAVY_LANE");
        field.setAccessible(true);
        java.util.concurrent.Semaphore lane = (java.util.concurrent.Semaphore) field.get(null);
        lane.acquire();
        JobManager jobs = new JobManager(context());
        CountDownLatch imported = new CountDownLatch(1);
        try {
            for (int i = 0; i < 3; i++) jobs.submit("waiting render", JobManager.Kind.HEAVY, j -> {});
            jobs.submit("image import", JobManager.Kind.LIGHT, j -> imported.countDown());
            assertTrue("An import must run while three renders are waiting", imported.await(3, TimeUnit.SECONDS));
        } finally { jobs.shutdown(); lane.release(); }
    }

    @Test public void reopeningActivityCannotOverwriteLiveServiceJobSnapshot() throws Exception {
        JobManager service = new JobManager(context());
        CountDownLatch running = new CountDownLatch(1), hold = new CountDownLatch(1);
        try {
            JobManager.Job job = service.submit("live import", JobManager.Kind.LIGHT, j -> { running.countDown(); hold.await(); });
            assertTrue(running.await(3, TimeUnit.SECONDS));
            JobManager activity = new JobManager(context(), true);
            activity.shutdown();
            String snapshot = context().getSharedPreferences("videostudio_native_v1", 0).getString("job_recovery_snapshot", "");
            assertTrue(snapshot.contains(job.id));
            assertEquals("running", service.get(job.id).getJSONObject("job").getString("state"));
            assertFalse(snapshot.contains("interrupted"));
        } finally { hold.countDown(); service.shutdown(); }
    }

    @Test public void independentImportsAppendToCurrentProjectRatherThanStaleSnapshots() {
        ProjectStore store = new ProjectStore(context());
        ProjectStore.Project original = store.create("parallel attachments");
        store.registerImportedAsset(original.id, Uri.parse("file:///first.png"), "first", "image/png", 0);
        store.registerImportedAsset(original.id, Uri.parse("file:///second.png"), "second", "image/png", 0);
        ProjectStore.Project saved = store.get(original.id);
        assertEquals(2, saved.assets.size()); assertEquals(2, saved.clips.size());
        assertEquals("second", saved.assets.get(1).name);
        store.delete(original.id);
        try { store.registerImportedAsset(original.id, Uri.parse("file:///late.png"), "late", "image/png", 0); fail("Deleted project resurrected"); }
        catch (IllegalArgumentException expected) { assertNull(store.get(original.id)); }
    }

    @Test public void recoveryCanFindAndCancelRetryingOrResourceQueuedJobs() throws Exception {
        RecoveryPlanStore plans = new RecoveryPlanStore(context());
        context().getSharedPreferences("videostudio_native_v1", 0).edit().remove("durable_recovery_plans_v1").commit();
        String id = plans.begin("animate_images", new JSONObject(), "project");
        plans.markResuming(id);
        assertEquals(1, plans.pendingForAutoResume().length());
        assertEquals(1, plans.cancelActive()); assertEquals(0, plans.pendingForAutoResume().length());
        java.lang.reflect.Field field = JobManager.class.getDeclaredField("PROCESS_HEAVY_LANE"); field.setAccessible(true);
        java.util.concurrent.Semaphore lane = (java.util.concurrent.Semaphore) field.get(null); lane.acquire();
        JobManager jobs = new JobManager(context());
        String queued = plans.begin("animate_images", new JSONObject(), "project");
        try {
            JobManager.Job job = jobs.submit("queued", JobManager.Kind.HEAVY, j -> plans.attachJob(queued, j.id), j -> fail("Must remain queued"));
            assertEquals(job.id, plans.get(queued).getString("jobId"));
            jobs.cancel(job.id); plans.cancelByJob(job.id);
            assertEquals("cancelled", plans.get(queued).getString("state"));
        } finally { jobs.shutdown(); lane.release(); }
    }

    @Test public void pingDoesNotRecursivelyPersistStateOrKeepLegacySnapshots() throws Exception {
        context().getSharedPreferences("videostudio_native_v1", 0).edit().putString("mcp_v3_command_journal",
                "[{\"id\":\"old-ping\",\"action\":\"ping\",\"status\":\"completed\",\"result\":{\"commandJournal\":[]}}]").commit();
        CommandJournal journal = new CommandJournal(context());
        JSONObject ping = new JSONObject("{\"id\":\"ping\",\"action\":\"ping\"}");
        journal.begin(ping); journal.finish(ping, new JSONObject("{\"commandJournal\":[]}"), "completed");
        assertEquals(0, journal.recent(20).length());
    }

    @Test public void inlineTransferPreservesBytesAndRejectsOverflowBadHashAndTrailingPayload() throws Exception {
        byte[] payload = new byte[400_000]; new java.util.Random(7).nextBytes(payload);
        String encoded = Base64.getEncoder().encodeToString(payload);
        File output = new File(context().getCacheDir(), "inline-regression.bin");
        StringBuilder sha = new StringBuilder();
        for (byte b : MessageDigest.getInstance("SHA-256").digest(payload)) sha.append(String.format("%02x", b & 255));
        try {
            assertEquals(payload.length, InlineImageWriter.write(encoded, output, payload.length, sha.toString()));
            assertArrayEquals(payload, Files.readAllBytes(output.toPath()));
            for (String invalid : new String[] {encoded, encoded + "AAAA", "invalid!"}) {
                long limit = invalid == encoded ? payload.length - 1 : payload.length;
                try { InlineImageWriter.write(invalid, output, limit, ""); fail("Invalid transfer accepted"); }
                catch (Exception expected) { assertFalse(output.exists()); }
            }
            try { InlineImageWriter.write(encoded, output, payload.length, "00"); fail("Bad checksum accepted"); }
            catch (IllegalArgumentException expected) { assertFalse(output.exists()); }
        } finally { output.delete(); }
    }

    @Test public void serviceShutdownRetainsRecoverableWorkButUserStopCancelsIt() throws Exception {
        JobManager service = new JobManager(context());
        CountDownLatch entered = new CountDownLatch(1), interrupted = new CountDownLatch(1);
        JobManager.Job job = service.submit("restartable import", JobManager.Kind.LIGHT, j -> {
            entered.countDown();
            try { new CountDownLatch(1).await(); }
            finally { interrupted.countDown(); }
        });
        assertTrue(entered.await(3, TimeUnit.SECONDS));
        service.shutdownForRestart();
        assertTrue(interrupted.await(3, TimeUnit.SECONDS));
        assertEquals(JobManager.STATE_CHECKPOINTED, service.get(job.id).getJSONObject("job").getString("state"));
        assertTrue(service.get(job.id).getJSONObject("job").getBoolean("recoverable"));
        JobManager stopped = new JobManager(context());
        CountDownLatch blocked = new CountDownLatch(1);
        try {
            JobManager.Job cancelled = stopped.submit("user stopped", JobManager.Kind.LIGHT, j -> blocked.await());
            stopped.cancel(cancelled.id);
            assertEquals("cancelled", stopped.get(cancelled.id).getJSONObject("job").getString("state"));
        } finally { stopped.shutdown(); }
    }
}
