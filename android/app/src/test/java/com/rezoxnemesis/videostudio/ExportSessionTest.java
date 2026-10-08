package com.rezoxnemesis.videostudio;

import android.content.Context;
import org.json.JSONObject;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=33, manifest=Config.NONE)
public class ExportSessionTest {
    private Context context; private ExportSessionStore sessions;
    @Before public void setup(){context=RuntimeEnvironment.getApplication();context.deleteDatabase("videostudio_exports.db");sessions=new ExportSessionStore(context);}
    private ProjectStore.Project project(){ProjectStore.Project p=new ProjectStore.Project();p.id="project";p.name="Export";p.revision=17;return p;}

    @Test public void ownerSeesAnExplicitSessionImmediately() throws Exception {
        ExportSessionStore.Session s=sessions.create(project(),new JSONObject("{\"fileName\":\"result.mp4\",\"quality\":\"720p\"}"));
        assertEquals("preparing",s.state);assertEquals(17,s.projectRevision);assertFalse(s.id.isEmpty());
        assertEquals(s.id,new ExportSessionStore(context).get(s.id).id);
    }
    @Test public void exportSnapshotDoesNotChangeWhenOwnerEditsProject() throws Exception {
        ProjectStore.Project p=project();ExportSessionStore.Session s=sessions.create(p,new JSONObject());
        p.name="Edited during export";p.revision=18;
        assertEquals("Export",sessions.project(s.id).name);assertEquals(17,sessions.project(s.id).revision);
    }
    @Test public void queueAcceptanceCannotBecomeCompletedOutput() {
        ExportSessionStore.Session s=sessions.create(project(),new JSONObject());
        try{sessions.finish(s.id,"",false);fail("Unverified output accepted");}catch(IllegalArgumentException expected){}
        assertEquals("preparing",sessions.get(s.id).state);
    }
    @Test public void cancellationCannotBeResurrectedByLateProgress() {
        ExportSessionStore.Session s=sessions.create(project(),new JSONObject());
        sessions.cancel(s.id);sessions.progress(s.id,"running",50,"Rendering");
        assertEquals("cancelled",sessions.get(s.id).state);
        try{sessions.finish(s.id,"content://owned/output",true);fail("Cancelled output completed");}catch(IllegalStateException expected){}
    }
    @Test public void verificationIsPersistedBeforeCompletedStatus() {
        ExportSessionStore.Session s=sessions.create(project(),new JSONObject());
        sessions.progress(s.id,"verifying",99,"Checking playable file");
        sessions.finish(s.id,"content://owned/output",true);
        ExportSessionStore.Session read=new ExportSessionStore(context).get(s.id);
        assertEquals("completed",read.state);assertTrue(read.verified);assertEquals(100,read.progress);
    }
}
