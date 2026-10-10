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