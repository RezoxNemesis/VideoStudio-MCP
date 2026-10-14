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
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
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
            byte[] originalResult=result.toString().getBytes(StandardCharsets.UTF_8);
            String originalResultSha=RelayTransportPolicy.sha256(originalResult);
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
                assertEquals("The native journal must retain the original image bytes",result.getJSONObject(imageKey).getString("base64"),
                        terminal.getJSONObject("result").getJSONObject(imageKey).getString("base64"));
                assertEquals(fixture.sourceSha,terminal.getJSONObject("result").getString("sourceSha256"));
                try (CommandOutbox outbox = new CommandOutbox(fixture.context)) {
                    JSONArray pending = outbox.pending();
                    JSONObject receipt = null;
                    for (int i=0; i<pending.length(); i++) if (command.getString("id").equals(pending.getJSONObject(i).getString("id"))) receipt=pending.getJSONObject(i);
                    assertNotNull("Native completion must persist the actual relay ACK body", receipt);
                    assertEquals("completed", receipt.getString("status"));
                    JSONObject envelope=receipt.getJSONObject("result");
                    assertTrue(envelope.getBoolean("ok"));assertTrue(envelope.getBoolean("resultExternalized"));
                    assertTrue("Image receipts must stay within the relay budget",envelope.toString().getBytes(StandardCharsets.UTF_8).length<=RelayTransportPolicy.RESULT_BYTES);
                    assertFalse("Images belong in the retained native result",envelope.has(imageKey));assertFalse(envelope.has("contactSheet"));
                    assertTrue("The compact receipt must retain the source fingerprint",envelope.has("sourceSha256"));
                    assertEquals(fixture.sourceSha,envelope.getString("sourceSha256"));
                    assertEquals(originalResult.length,envelope.getInt("resultBytes"));assertEquals(originalResultSha,envelope.getString("resultSha256"));
                    JSONObject locator=envelope.getJSONObject("resultLocator");
                    assertEquals("get_native_result_page",locator.getString("action"));assertEquals(command.getString("id"),locator.getString("commandId"));
                    assertEquals(0,locator.getInt("pageIndex"));
                    ByteArrayOutputStream reconstructed=new ByteArrayOutputStream();
                    int pages=outbox.readResultPage(command.getString("id"),0).getInt("pageCount");
                    for(int index=0;index<pages;index++) {
                        JSONObject page=outbox.readResultPage(command.getString("id"),index);
                        assertTrue(page.getBoolean("ok"));assertEquals("completed",page.getString("status"));
                        assertEquals(command.getString("id"),page.getString("commandId"));assertEquals(index,page.getInt("pageIndex"));assertEquals(pages,page.getInt("pageCount"));
                        assertEquals("base64",page.getString("encoding"));assertFalse(page.has("base64"));
                        assertEquals(originalResult.length,page.getInt("resultBytes"));assertEquals(originalResultSha,page.getString("resultSha256"));
                        reconstructed.write(android.util.Base64.decode(page.getString("encodedChunk"),android.util.Base64.NO_WRAP));
                    }
                    byte[] restoredBytes=reconstructed.toByteArray();
                    assertArrayEquals("Paged delivery must reconstruct every native result byte",originalResult,restoredBytes);
                    assertEquals(envelope.getString("resultSha256"),RelayTransportPolicy.sha256(restoredBytes));
                    JSONObject restored=new JSONObject(new String(restoredBytes,StandardCharsets.UTF_8));
                    assertTrue(restored.getBoolean("ok"));assertEquals(fixture.sourceSha,restored.getString("sourceSha256"));
                    JSONObject originalImage=result.getJSONObject(imageKey),restoredImage=restored.getJSONObject(imageKey);
                    assertEquals(originalImage.getString("base64"),restoredImage.getString("base64"));
                    assertEquals(originalImage.getString("sha256"),restoredImage.getString("sha256"));
                    assertEquals(restoredImage.getString("sha256"),RelayTransportPolicy.sha256(android.util.Base64.decode(restoredImage.getString("base64"),android.util.Base64.DEFAULT)));
                    android.graphics.Bitmap nativeImage=NativeSourceWorkbenchFixture.image(restored,imageKey);
                    try {assertTrue(nativeImage.getWidth()>0);assertTrue(nativeImage.getHeight()>0);} finally {nativeImage.recycle();}
                }
            } finally {
                protocol.stop();Field outbox=AppProtocol.class.getDeclaredField("outbox");outbox.setAccessible(true);
                ((CommandOutbox)outbox.get(protocol)).close();
            }
        }
    }
    private static void inject(ControlService service, String name, Object value) throws Exception {
        Field field=ControlService.class.getDeclaredField(name); field.setAccessible(true); field.set(service,value);
    }
}
