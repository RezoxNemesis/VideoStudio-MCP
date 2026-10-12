package com.rezoxnemesis.videostudio;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 33)
public class ProjectStoreRevisionTest {
    @Test public void consecutiveSavesHaveDistinctRevisionsEvenWithinTheSameClockTick() {
        ProjectStore first = new ProjectStore(RuntimeEnvironment.getApplication());
        ProjectStore.Project project = first.create("Revision fixture");
        long initial = project.updatedAt;
        first.save(project);
        assertTrue("A same-clock save must invalidate an earlier rig authoring revision", project.updatedAt > initial);
        ProjectStore second = new ProjectStore(RuntimeEnvironment.getApplication());
        ProjectStore.Project latest = second.get(project.id);
        long previous = latest.updatedAt;
        second.save(latest);
        assertTrue("Separate store instances share the persisted revision", latest.updatedAt > previous);
    }

    @Test public void aDifferentStoreInvalidatesTheAuthoringRevisionBeforeCallback() throws Exception {
        ProjectStore first = new ProjectStore(RuntimeEnvironment.getApplication());
        ProjectStore.Project source = first.create("Guarded source");
        long acceptedRevision = source.updatedAt;
        ProjectStore second = new ProjectStore(RuntimeEnvironment.getApplication());
        ProjectStore.Project edited = second.get(source.id);
        edited.name = "Changed while the annotation editor was open";
        second.save(edited);
        boolean[] invoked = {false};
        try {
            first.guardedProject(source.id, acceptedRevision, latest -> { invoked[0] = true; return null; });
            fail("Stale source must reject before touching rig files");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage().startsWith("SOURCE_PROJECT_CHANGED"));
        }
        assertFalse(invoked[0]);
    }

    @Test public void callbackReceivesLatestSourceAndFailureRollsBackSqlMutation() throws Exception {
        ProjectStore store = new ProjectStore(RuntimeEnvironment.getApplication());
        ProjectStore.Project source = store.create("Original guarded source");
        long revision = source.updatedAt;
        String observed = store.guardedProject(source.id, revision, latest -> latest.name);
        assertEquals(source.name, observed);
        assertEquals(revision, store.get(source.id).updatedAt);
        try {
            store.guardedProject(source.id, revision, latest -> {
                latest.name = "Partial change";
                store.save(latest);
                throw new java.io.IOException("Annotation write failed");
            });
            fail("Callback failure must propagate");
        } catch (java.io.IOException expected) {
            assertEquals("Annotation write failed", expected.getMessage());
        }
        assertEquals(source.name, store.get(source.id).name);
        assertEquals(revision, store.get(source.id).updatedAt);
    }
}
