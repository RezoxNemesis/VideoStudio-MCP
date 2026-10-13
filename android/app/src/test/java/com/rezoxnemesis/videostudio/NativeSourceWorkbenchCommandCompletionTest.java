package com.rezoxnemesis.videostudio;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.UUID;
import static org.junit.Assert.*;

/** Actual native helper results must become completed durable service receipts, without networking. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=33,manifest=Config.NONE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class NativeSourceWorkbenchCommandCompletionTest {
    @Test public void artworkImageCompletesTheNativeCommandAndOutbox() throws Exception { exercise(false); }
    @Test public void rigDiagnosticCompletesTheNativeCommandAndOutbox() throws Exception { exercise(true); }

    private static void exercise(boolean preview) throws Exception {
        try (NativeSourceWorkbenchFixture fixture = new NativeSourceWorkbenchFixture(RuntimeEnvironment.getApplication())) {
            JSONObject parameters = preview ? fixture.preview() : fixture.inspection();
            JSONObject result = preview ? NativeSourceWorkbenchFixture.render(fixture, parameters)
                    : NativeSourceWorkbenchFixture.inspect(fixture, parameters);
            String imageKey = preview ? "rigPreviewImage" : "sourceArtworkImage";
            assertTrue("Fixture must contain actual native image bytes", result.getJSONObject(imageKey).getString("base64").length() > 100);
            ControlService service = Robolectric.buildService(ControlService.class).get();
            CommandJournal journal = new CommandJournal(fixture.context);
            AppProtocol protocol = new AppProtocol(fixture.context, null);
            protocol.stop(); // complete persists locally; no executor can connect or send.
            try {
                inject(service, "commandJournal", journal);
                inject(service, "protocol", protocol);
                JSONObject command = new JSONObject().put("id", UUID.randomUUID().toString()).put("seq", 1)
                        .put("action", preview ? "preview_source_rig" : "inspect_source_artwork").put("parameters", parameters);
                Method complete = ControlService.class.getDeclaredMethod("complete", JSONObject.class, JSONObject.class);
                complete.setAccessible(true);
                complete.invoke(service, command, result);
                JSONObject terminal = journal.terminal(command.getString("id"));
                assertNotNull(terminal);
                assertEquals("A valid native image must be a completed command, not a failed one", "completed", terminal.getString("status"));
                assertTrue(terminal.getJSONObject("result").getBoolean("ok"));
                try (CommandOutbox outbox = new CommandOutbox(fixture.context)) {
                    JSONArray pending = outbox.pending();
                    JSONObject receipt = null;
                    for (int i=0; i<pending.length(); i++) if (command.getString("id").equals(pending.getJSONObject(i).getString("id"))) receipt=pending.getJSONObject(i);
                    assertNotNull("Native completion must persist the actual relay ACK body", receipt);
                    assertEquals("completed", receipt.getString("status"));
                    assertEquals(result.getString("sourceSha256"), receipt.getJSONObject("result").getString("sourceSha256"));
                    assertEquals(result.getJSONObject(imageKey).getString("sha256"), receipt.getJSONObject("result").getJSONObject(imageKey).getString("sha256"));
                }
            } finally { protocol.stop(); }
        }
    }
    private static void inject(ControlService service, String name, Object value) throws Exception {
        Field field=ControlService.class.getDeclaredField(name); field.setAccessible(true); field.set(service,value);
    }
}
