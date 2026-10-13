package com.rezoxnemesis.videostudio;

import android.content.Context;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=33,manifest=Config.NONE)
public class PrivateCommandJournalTest {
    @Test public void runnerBearerNeverEntersPersistentCommandDiagnostics()throws Exception{
        CommandJournal journal=new CommandJournal(RuntimeEnvironment.getApplication());
        JSONObject command=new JSONObject().put("id","runner-secret-"+java.util.UUID.randomUUID()).put("seq",390)
                .put("action","open_pipeline_configure").put("parameters",new JSONObject()
                        .put("endpoint","https://owner.example/runner").put("bearer","SECRET_RUNNER_NEVER_PERSIST"));
        journal.begin(command);
        String admitted=RuntimeEnvironment.getApplication().getSharedPreferences("videostudio_native_v1",Context.MODE_PRIVATE).getString("mcp_v3_command_journal","");
        assertFalse(admitted.contains("SECRET_RUNNER_NEVER_PERSIST"));assertEquals("https://owner.example/runner",new org.json.JSONArray(admitted).getJSONObject(0).getJSONObject("command").getJSONObject("parameters").getString("endpoint"));
        journal.finish(command,new JSONObject().put("configured",true),"completed");
        String persisted=journal.recent(40).toString();assertFalse(persisted.contains("SECRET_RUNNER_NEVER_PERSIST"));assertTrue(journal.terminal(command.getString("id")).getJSONObject("result").getBoolean("configured"));
    }
    @Test public void privateMcpImageBytesAreNeverSavedToAndroidJournal() throws Exception {
        Context context=RuntimeEnvironment.getApplication();
        CommandJournal journal=new CommandJournal(context);
        String commandId="chunk-redact-" + java.util.UUID.randomUUID();
        JSONObject cmd=new JSONObject()
            .put("id",commandId).put("seq",3023)
            .put("action","append_frame_chunk")
            .put("parameters",new JSONObject()
                .put("projectId","project-1234")
                .put("base64","PRIVATE_IMAGE_DATA_DO_NOT_STORE")
                .put("sourceUrl","https://private.example/download?token=private-token")
                .put("offset",100));
        journal.begin(cmd);
        String log=journal.recent(40).toString();
        assertTrue(log.contains(commandId));
        assertFalse(log.contains("PRIVATE_IMAGE_DATA_DO_NOT_STORE"));
        assertFalse(log.contains("private-token"));
        assertTrue(log.contains("project-1234"));
        assertTrue(log.contains("offset"));
        journal.finish(cmd,new JSONObject().put("ok",true),"completed");
        assertFalse(journal.recent(40).toString().contains("PRIVATE_IMAGE_DATA_DO_NOT_STORE"));
    }
}
