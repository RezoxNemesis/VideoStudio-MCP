package com.rezoxnemesis.videostudio;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=33, manifest=Config.NONE)
public class LegacyMigrationRulesTest {
    private static final String PROJECT="f2abaad2-d94e-4c72-87f3-106977f68030";

    @Test public void oldWorkspaceAssetsMapIntoTheNewPrivateProject() {
        assertEquals("generated/pose_sequence/pose_0000.png",
                LegacyMigrationRules.archivedRelativePath(
                        "file:///data/user/0/com.rezoxnemesis.videostudio/files/"
                        +"creative_workspace/projects/"+PROJECT+"/generated/pose_sequence/pose_0000.png",
                        PROJECT));
    }

    @Test public void oldGlobalImportIsNotPretendedToBeArchived() {
        String uri="file:///data/user/0/com.rezoxnemesis.videostudio/files/imports/17917_ash_frame_03.jpg";
        assertTrue(LegacyMigrationRules.isOldImportsFile(uri));
        assertEquals("", LegacyMigrationRules.archivedRelativePath(uri,PROJECT));
    }

    @Test public void originalArtworkRestoresByFileStemAcrossMimeExtensions() {
        assertEquals(LegacyMigrationRules.sourceStem("ash_frame_03.jpg"),
                LegacyMigrationRules.sourceStem("ash_frame_03.png"));
        assertEquals("ash_frame_17",LegacyMigrationRules.sourceStem("ash_frame_17.jpeg"));
    }

    @Test public void refuseRemotePathTraversalAndForgedProjects() {
        assertFalse(LegacyMigrationRules.validId("../../Gallery"));
        assertFalse(LegacyMigrationRules.isKnownMediaName("a/../../b.mp4"));
        assertEquals("",LegacyMigrationRules.archivedRelativePath(
             "file:///data/user/0/legacy/files/creative_workspace/projects/"
              +PROJECT+"/../../secret.txt",PROJECT));
        assertEquals("",LegacyMigrationRules.archivedRelativePath(
            "content://media/external/video/1234",PROJECT));
    }

    @Test public void acceptOnlySimpleSourceMediaNames() {
        assertTrue(LegacyMigrationRules.isKnownMediaName("ash_frame_01.png"));
        assertFalse(LegacyMigrationRules.isKnownMediaName("../ash_frame_01.png"));
        assertFalse(LegacyMigrationRules.isKnownMediaName("archive\\private.jpg"));
    }
}
