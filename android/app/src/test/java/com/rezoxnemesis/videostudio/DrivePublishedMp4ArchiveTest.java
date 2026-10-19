package com.rezoxnemesis.videostudio;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import static org.junit.Assert.*;

/** A project backup must never scan Gallery or export arbitrary source media. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=33,manifest=Config.NONE)
public class DrivePublishedMp4ArchiveTest {
    @Test public void onlyOwnedPublishedFinalRenderIsEligible() {
        ProjectStore store=new ProjectStore(RuntimeEnvironment.getApplication());
        ProjectStore.Project project=store.create("VideoStudio archive ownership test");
        String mediaUri="content://media/external/video/media/12345";
        project.latestExportName="Ashes_storyboard.mp4";
        project.latestExportUri=mediaUri;
        assertFalse(DriveWorkspaceProvider.isProjectPublishedMp4(project));

        ProjectStore.Asset asset=new ProjectStore.Asset();
        asset.uri=mediaUri;
        asset.role="final_render";
        asset.generated=false;
        project.assets.add(asset);
        assertFalse("User-owned media isn't a generated output",
                DriveWorkspaceProvider.isProjectPublishedMp4(project));

        asset.generated=true;
        assertTrue("The current VideoStudio-generated MediaStore MP4 is eligible",
                DriveWorkspaceProvider.isProjectPublishedMp4(project));

        asset.role="source";
        assertFalse(DriveWorkspaceProvider.isProjectPublishedMp4(project));
        asset.role="final_render";

        project.latestExportUri="content://com.google.android.apps.photos.content/0/123";
        assertFalse("Other content providers cannot be enumerated or archived",
                DriveWorkspaceProvider.isProjectPublishedMp4(project));

        project.latestExportUri=mediaUri;
        project.latestExportName="not_a_video.jpg";
        assertFalse("Only an explicit MP4 render is backed up",
                DriveWorkspaceProvider.isProjectPublishedMp4(project));
    }
}
