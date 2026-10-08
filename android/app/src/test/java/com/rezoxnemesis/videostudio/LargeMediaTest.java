package com.rezoxnemesis.videostudio;

import android.content.Context;
import android.net.Uri;

import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=33, manifest=Config.NONE)
public class LargeMediaTest {
    @Test public void assetMetadataRoundTripsFiveGigabytesWithoutOverflow() throws Exception {
        long fiveGb = 5L * 1024L * 1024L * 1024L;
        ProjectStore.Asset asset = new ProjectStore.Asset();
        asset.id = "large-asset";
        asset.uri = "content://large/video";
        asset.name = "source-5gb.mp4";
        asset.mime = "video/mp4";
        asset.sizeBytes = fiveGb;
        asset.seekable = true;
        asset.persistedReadAccess = true;
        asset.providerAuthority = "large";

        ProjectStore.Asset restored = ProjectStore.Asset.fromJson(asset.toJson());
        assertEquals(fiveGb, restored.sizeBytes);
        assertTrue(restored.sizeBytes > Integer.MAX_VALUE);
        assertTrue(restored.seekable);
        assertTrue(restored.persistedReadAccess);
        assertEquals("large", restored.providerAuthority);
    }

    @Test public void legacyAssetJsonDefaultsLargeFileFieldsSafely() throws Exception {
        ProjectStore.Asset restored = ProjectStore.Asset.fromJson(new JSONObject(
                "{\"id\":\"legacy\",\"uri\":\"content://legacy/a\",\"name\":\"old.mp4\",\"mime\":\"video/mp4\",\"durationMs\":1000}"
        ));
        assertEquals(-1L, restored.sizeBytes);
        assertFalse(restored.seekable);
        assertFalse(restored.persistedReadAccess);
        assertEquals("legacy", restored.providerAuthority);
    }

    @Test public void inaccessibleContentUriIsReportedWithoutThrowing() {
        Context context = RuntimeEnvironment.getApplication();
        Uri missing = Uri.parse("content://missing-provider/video/1");
        AssetProbe.Result result = AssetProbe.probe(context.getContentResolver(), missing);
        assertEquals(-1L, result.sizeBytes);
        assertFalse(result.seekable);
        assertFalse(result.persistedReadAccess);
        assertEquals("missing-provider", result.providerAuthority);
        assertFalse(result.readable);
    }

    @org.junit.Test public void explicitLocalJpegUsesItsFilenameWhenProviderMetadataIsAbsent() throws Exception {
        android.content.Context context = org.robolectric.RuntimeEnvironment.getApplication();
        java.io.File source = new java.io.File(context.getFilesDir(), "picked-reference.JPG");
        try (java.io.FileOutputStream out = new java.io.FileOutputStream(source)) { out.write(new byte[]{1,2,3}); }
        AssetProbe.Result result = AssetProbe.probe(context.getContentResolver(), android.net.Uri.fromFile(source));
        org.junit.Assert.assertEquals("image/jpeg", result.mime);
        org.junit.Assert.assertEquals("picked-reference.JPG", result.displayName);
        ProjectStore store = new ProjectStore(context);
        ProjectStore.Project project = store.create("Explicit local source");
        store.importUri(project, android.net.Uri.fromFile(source));
        org.junit.Assert.assertEquals(1, store.get(project.id).clips.size());
    }
}
