package com.rezoxnemesis.videostudio;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.PowerManager;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=33,manifest=Config.NONE)
public class NativeWorkMonitorTest {
    @Test public void nativeQueueIsReadOnlyAndAccuratelySummarizesJobs() throws Exception {
        JSONArray jobs=new JSONArray();
        jobs.put(new JSONObject().put("id","render-one")
                .put("name","Native GLB Export").put("state","waiting_thermal")
                .put("progress",22).put("detail","Android reported severe")
                .put("updatedAt",300));
        jobs.put(new JSONObject().put("id","done-two")
                .put("name","Finished video").put("state","completed")
                .put("progress",100).put("updatedAt",200));
        JSONObject summary=NativeWorkMonitor.summarize(jobs);
        assertEquals(1,summary.getInt("active"));
        assertEquals(1,summary.getInt("waiting"));
        assertEquals(1,summary.getInt("completed"));
        assertEquals("Native GLB Export",summary.getString("jobName"));
        assertEquals("waiting_thermal",summary.getString("jobState"));
    }

    @Test public void openingEditorJobManagerCannotClobberNativeCheckpoints(){
        Context app=RuntimeEnvironment.getApplication();
        SharedPreferences prefs=app.getSharedPreferences(
                "videostudio_native_v1",Context.MODE_PRIVATE);
        String original="[{\"id\":\"render-one\",\"state\":\"running\",\"name\":\"Native renderer\"}]";
        prefs.edit().putString(ExecutionTruthPolicy.JOB_RECOVERY_PREF_KEY,original).commit();
        JobManager editorLocal=new JobManager(app,"videostudio_editor_local_jobs_v2");
        try{
            assertEquals("Human editor must not rewrite the MCP work journal",
                    original,prefs.getString(
                            ExecutionTruthPolicy.JOB_RECOVERY_PREF_KEY,""));
            assertEquals(1,NativeWorkMonitor.snapshot(app).optInt("active"));
        }finally{
            editorLocal.shutdown();
        }
    }

    @Test public void severeThermalStatusIsExplainedNotSilentlyOverridden(){
        assertTrue(NativeWorkMonitor.thermalLabel(PowerManager.THERMAL_STATUS_SEVERE)
                .contains("level 3"));
        assertTrue(NativeWorkMonitor.thermalLabel(PowerManager.THERMAL_STATUS_NONE)
                .contains("No system"));
    }

}
