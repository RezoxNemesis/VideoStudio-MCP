package com.rezoxnemesis.videostudio;

import org.json.JSONObject;
import org.junit.Test;

import static org.junit.Assert.*;

public class ProxyManagerTest {
    @Test public void completedProxyIsChosenOnlyForPreviewAndOriginalAssetRemainsUnchanged() throws Exception {
        ProjectStore.Project project=new ProjectStore.Project();
        project.id="project-1";

        ProjectStore.Asset source=new ProjectStore.Asset();
        source.id="source-1";
        source.uri="content://original/video";
        source.mime="video/mp4";
        source.sizeBytes=5L*1024L*1024L*1024L;
        project.assets.add(source);

        ProjectStore.Asset proxy=new ProjectStore.Asset();
        proxy.id="proxy-1";
        proxy.uri="file:///cache/proxy.mp4";
        proxy.mime="video/mp4";
        proxy.role="preview_proxy";
        proxy.generated=true;
        proxy.generationMetadata=new JSONObject()
                .put("originalAssetId","source-1")
                .put("proxyTier","720p")
                .put("complete",true);
        project.assets.add(proxy);

        assertEquals("file:///cache/proxy.mp4",ProxyManager.previewUri(project,source));
        assertEquals("content://original/video",source.uri);
        assertEquals("content://original/video",ProxyManager.originalUri(source));
    }

    @Test public void failedOrIncompleteProxyFallsBackToOriginalSource() throws Exception {
        ProjectStore.Project project=new ProjectStore.Project();
        project.id="project-1";
        ProjectStore.Asset source=new ProjectStore.Asset();
        source.id="source";
        source.uri="content://original/video";
        source.mime="video/mp4";
        source.sizeBytes=2L*1024L*1024L*1024L;
        project.assets.add(source);

        ProjectStore.Asset bad=new ProjectStore.Asset();
        bad.id="proxy";
        bad.uri="file:///cache/bad.mp4.partial";
        bad.mime="video/mp4";
        bad.role="preview_proxy";
        bad.generated=true;
        bad.generationMetadata=new JSONObject()
                .put("originalAssetId","source")
                .put("complete",false);
        project.assets.add(bad);

        assertEquals(source.uri,ProxyManager.previewUri(project,source));
    }

    @Test public void heavyVideoIsEligibleButImagesAndSmallVideosAreNotForcedToProxy() {
        ProjectStore.Asset heavy=new ProjectStore.Asset();
        heavy.mime="video/mp4";
        heavy.sizeBytes=5L*1024L*1024L*1024L;
        assertTrue(ProxyManager.shouldProxy(heavy));

        ProjectStore.Asset small=new ProjectStore.Asset();
        small.mime="video/mp4";
        small.sizeBytes=120L*1024L*1024L;
        assertFalse(ProxyManager.shouldProxy(small));

        ProjectStore.Asset image=new ProjectStore.Asset();
        image.mime="image/jpeg";
        image.sizeBytes=5L*1024L*1024L*1024L;
        assertFalse(ProxyManager.shouldProxy(image));
    }
}
