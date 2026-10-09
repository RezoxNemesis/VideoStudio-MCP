package com.rezoxnemesis.videostudio;

import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=33, manifest=Config.NONE)
public class ProxyManagerTest {
    @Test public void claimedProxyWithoutCurrentProofFallsBackAndOriginalAssetRemainsUnchanged() throws Exception {
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
        proxy.uri=android.net.Uri.fromFile(java.nio.file.Files.createTempFile("studio-proxy-test-",".mp4").toFile()).toString();
        proxy.mime="video/mp4";
        proxy.role="preview_proxy";
        proxy.generated=true;
        proxy.generationMetadata=new JSONObject()
                .put("originalAssetId","source-1")
                .put("proxyTier","720p")
                .put("complete",true).put("verified",true).put("sourceFingerprint",ProxyManager.sourceFingerprint(source));
        project.assets.add(proxy);

        assertEquals(source.uri,ProxyManager.previewUri(project,source));
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

    @Test public void proxyTierSelectionRejectsStaleSourceAndSupportsOriginal() throws Exception {
        ProjectStore.Project p=new ProjectStore.Project();p.id="tiers";
        ProjectStore.Asset source=new ProjectStore.Asset();source.id="source";source.uri="content://original/one";source.mime="video/mp4";source.sizeBytes=8_000_000_000L;source.durationMs=10000;p.assets.add(source);
        ProjectStore.Asset proxy=new ProjectStore.Asset();proxy.id="p360";proxy.uri="content://proxy/360";proxy.generated=true;proxy.role="preview_proxy";
        proxy.generationMetadata=ProxyManager.proxyMetadata(source,"360p",new JSONObject().put("ok",true).put("sha256","verified-digest"));p.assets.add(proxy);
        assertEquals("A remote URI has no locally current byte proof",source.uri,ProxyManager.previewUri(p,source,"360p"));
        assertEquals(source.uri,ProxyManager.previewUri(p,source,"720p"));
        assertEquals(source.uri,ProxyManager.previewUri(p,source,"original"));
        source.uri="content://original/relinked";assertEquals(source.uri,ProxyManager.previewUri(p,source,"360p"));
    }

    @Test public void deletedCacheFilesAndUnverifiedProxiesFallBackToOriginal() throws Exception {
        ProjectStore.Project p=new ProjectStore.Project();ProjectStore.Asset source=new ProjectStore.Asset();source.id="source";source.uri="content://original/one";source.mime="video/mp4";p.assets.add(source);
        ProjectStore.Asset proxy=new ProjectStore.Asset();proxy.id="proxy";proxy.uri="file:///definitely-missing-videostudio-cache.mp4";proxy.generated=true;proxy.role="preview_proxy";
        proxy.generationMetadata=ProxyManager.proxyMetadata(source,"240p",new JSONObject().put("ok",true));p.assets.add(proxy);
        assertEquals(source.uri,ProxyManager.previewUri(p,source,"240p"));
        proxy.uri="content://proxy/unverified";proxy.generationMetadata.put("verified",false);assertEquals(source.uri,ProxyManager.previewUri(p,source,"240p"));
    }

    @Test public void truncatedAndReplacedCacheFilesLosePreviewEligibility()throws Exception{
        java.nio.file.Path file=java.nio.file.Files.createTempFile("studio-proxy-proof-",".mp4");
        try{
            java.nio.file.Files.write(file,new byte[]{1,2,3,4});
            ProjectStore.Project p=new ProjectStore.Project();ProjectStore.Asset source=new ProjectStore.Asset();source.id="source";source.uri="content://original/video";source.mime="video/mp4";p.assets.add(source);
            ProjectStore.Asset proxy=new ProjectStore.Asset();proxy.id="proxy";proxy.uri=android.net.Uri.fromFile(file.toFile()).toString();proxy.generated=true;proxy.role="preview_proxy";
            JSONObject proof=new JSONObject().put("ok",true).put("sizeBytes",4).put("sha256","a".repeat(64));
            proxy.generationMetadata=ProxyManager.proxyMetadata(source,"240p",proof).put("verifiedModifiedAt",file.toFile().lastModified());p.assets.add(proxy);
            assertEquals(proxy.uri,ProxyManager.previewUri(p,source,"240p"));
            java.nio.file.Files.write(file,new byte[0]);assertEquals(source.uri,ProxyManager.previewUri(p,source,"240p"));
            java.nio.file.Files.write(file,new byte[]{4,3,2,1});proxy.generationMetadata.put("verifiedModifiedAt",file.toFile().lastModified()-1);assertEquals(source.uri,ProxyManager.previewUri(p,source,"240p"));
        }finally{java.nio.file.Files.deleteIfExists(file);}
    }

    @Test public void allProxyTiersAreExplicitAndInvalidTiersAreRejected(){
        for(String tier:new String[]{"240p","360p","540p","720p"})assertEquals(tier,ProxyManager.normaliseTier(tier));
        try{ProxyManager.normaliseTier("4k");fail("Unsupported tier silently downgraded");}catch(IllegalArgumentException expected){}
    }
}
