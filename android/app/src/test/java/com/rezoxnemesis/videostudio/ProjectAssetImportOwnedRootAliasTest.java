package com.rezoxnemesis.videostudio;

import android.content.Context;
import android.content.ContextWrapper;
import android.net.Uri;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

/** Public import behavior under Android-style trusted Context ancestor aliases. No private reflection. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=33,manifest=Config.NONE)
public class ProjectAssetImportOwnedRootAliasTest {
    private final Context base=RuntimeEnvironment.getApplication();

    @Test public void trustedAncestorAliasImportsExactBytesAndReplaysWithoutChangingSourceAudioOrLaterEdits()throws Exception {
        try(Fixture f=new Fixture()){
            String sourceBefore=f.sourceJson(),destinationBefore=f.outputJson(),activeBefore=f.store.active().id;
            JSONObject info=ProjectAssetImport.inspect(f.context,f.info());
            assertTrue(info.getBoolean("readOnly"));assertEquals(f.bytes.length,info.getLong("bytes"));assertEquals(f.digest,info.getString("sha256"));
            assertEquals(destinationBefore,f.outputJson());
            f.request.put("appendToTimeline",true);
            JSONObject result=ProjectAssetImport.execute(f.context,f.request);
            assertTrue(result.getBoolean("ok"));assertTrue(result.getBoolean("independentCopy"));assertTrue(result.getBoolean("appendedToTimeline"));
            ProjectStore.Project copied=f.output();
            assertEquals(2,copied.assets.size());assertEquals(1,copied.clips.size());assertEquals(1,copied.studioLayers.size());
            assertEquals("existing_audio",copied.studioLayers.get(0).assetId);assertEquals(8000,copied.studioLayers.get(0).durationMs);
            ProjectStore.Asset asset=copied.asset(result.getString("assetId"));File output=new File(Uri.parse(asset.uri).getPath());
            assertTrue(output.getCanonicalPath().startsWith(f.realFiles.toFile().getCanonicalPath()+File.separator));
            assertArrayEquals(f.bytes,Files.readAllBytes(output.toPath()));assertEquals("generated_sketch_video",asset.role);assertTrue(asset.generated);
            assertEquals("source-bound",asset.generationMetadata.getString("fixtureProvenance"));
            assertEquals(sourceBefore,f.sourceJson());assertEquals(activeBefore,f.store.active().id);
            copied.name="Later human project name";copied.clips.get(0).title="Later human clip title";f.store.save(copied);
            String humanBefore=f.outputJson();long humanRevision=f.output().updatedAt;
            JSONObject replay=ProjectAssetImport.execute(f.context,f.request);
            assertTrue(replay.getBoolean("replayed"));assertEquals(result.getString("assetId"),replay.getString("assetId"));
            assertEquals(humanRevision,f.output().updatedAt);assertEquals(humanBefore,f.outputJson());
            assertEquals(sourceBefore,f.sourceJson());assertArrayEquals(f.bytes,Files.readAllBytes(f.file.toPath()));
        }
    }

    @Test public void sourceSymlinkInsideTrustedRootStillRejectsEvenWhenItsTargetIsAlsoOwned()throws Exception {
        try(Fixture f=new Fixture()){
            Path link=Files.createSymbolicLink(f.realFiles.resolve("linked-source.mp4"),f.file.toPath());
            f.sourceUri(new File(f.context.getFilesDir(),"linked-source.mp4"));
            assertTrue(Files.isSymbolicLink(link));String destination=f.outputJson(),source=f.sourceJson();
            assertThrows(Exception.class,()->ProjectAssetImport.inspect(f.context,f.info()));
            assertThrows(Exception.class,()->ProjectAssetImport.execute(f.context,f.request));
            assertEquals(destination,f.outputJson());assertEquals(source,f.sourceJson());assertArrayEquals(f.bytes,Files.readAllBytes(f.file.toPath()));
            assertTrue(Files.isSymbolicLink(link));
        }
    }

    @Test public void generationSymlinkInsideTrustedRootCannotRedirectPublicationToAnotherOwnedDirectory()throws Exception {
        try(Fixture f=new Fixture()){
            Path target=Files.createDirectory(f.realFiles.resolve("keep-owned-target"));
            Path marker=target.resolve("keep.txt");byte[] sentinel="Preserve this owned target".getBytes(StandardCharsets.UTF_8);Files.write(marker,sentinel);
            Path link=f.linkGeneration(target);String before=f.outputJson();
            assertTrue(ProjectAssetImport.inspect(f.context,f.info()).getBoolean("ok"));
            assertThrows(Exception.class,()->ProjectAssetImport.execute(f.context,f.request));
            assertEquals(before,f.outputJson());assertArrayEquals(sentinel,Files.readAllBytes(marker));assertTrue(Files.isSymbolicLink(link));
            try(var entries=Files.list(target)){assertEquals(1,entries.count());}
            assertArrayEquals(f.bytes,Files.readAllBytes(f.file.toPath()));
        }
    }

    @Test public void generationSymlinkEscapingTrustedRootStillRejectsAndPreservesExternalTarget()throws Exception {
        try(Fixture f=new Fixture()){
            Path external=Files.createDirectory(f.container.resolve("outside-files"));
            Path marker=external.resolve("keep.txt");byte[] sentinel="External directory must not receive import files".getBytes(StandardCharsets.UTF_8);Files.write(marker,sentinel);
            Path link=f.linkGeneration(external);String before=f.outputJson();
            assertThrows(Exception.class,()->ProjectAssetImport.execute(f.context,f.request));
            assertEquals(before,f.outputJson());assertArrayEquals(sentinel,Files.readAllBytes(marker));assertTrue(Files.isSymbolicLink(link));
            try(var entries=Files.list(external)){assertEquals(1,entries.count());}
        }
    }

    @Test public void trustedAliasDoesNotHideWrongHashesSizesOrStaleRevisionErrors()throws Exception {
        for(String kind:new String[]{"hash","size","source_revision","destination_revision"})try(Fixture f=new Fixture()){
            String expected;
            if("hash".equals(kind)){f.request.put("expectedSha256","0".repeat(64));expected="SOURCE_CHANGED";}
            else if("size".equals(kind)){
                ProjectStore.Project source=f.store.get(f.source.id);source.assets.get(0).sizeBytes=f.bytes.length+1;f.store.save(source);
                f.request.put("sourceProjectUpdatedAt",source.updatedAt);expected="Source size changed";
            }else if("source_revision".equals(kind)){f.request.put("sourceProjectUpdatedAt",0);expected="SOURCE_PROJECT_CHANGED";}
            else{f.request.put("expectedUpdatedAt",0);expected="SOURCE_PROJECT_CHANGED";}
            String before=f.outputJson();Exception rejected=assertThrows(kind,Exception.class,()->ProjectAssetImport.execute(f.context,f.request));
            assertTrue(kind+": "+rejected.getMessage(),String.valueOf(rejected.getMessage()).contains(expected));
            assertEquals(before,f.outputJson());assertArrayEquals(f.bytes,Files.readAllBytes(f.file.toPath()));
        }
    }

    @Test public void cancellationUnderTrustedAliasRetainsPrivatePartialAndExactRetryPublishesOnlyOnce()throws Exception {
        try(Fixture f=new Fixture()){
            f.request.put("appendToTimeline",true);String before=f.outputJson();AtomicBoolean reached=new AtomicBoolean();
            try{
                assertThrows(InterruptedException.class,()->ProjectAssetImport.execute(f.context,f.request,(copied,total)->{
                    if(copied>0){reached.set(true);Thread.currentThread().interrupt();}
                }));
            }finally{Thread.interrupted();}
            assertTrue("The copy must reach the real public progress callback",reached.get());assertEquals(before,f.outputJson());
            Path partial=f.generation().resolve("media.mp4.partial");assertTrue(Files.isRegularFile(partial));assertTrue(Files.size(partial)>0);
            JSONObject completed=ProjectAssetImport.execute(f.context,f.request);assertTrue(completed.getBoolean("ok"));assertFalse(Files.exists(partial));
            assertEquals(2,f.output().assets.size());assertEquals(1,f.output().clips.size());
            File output=new File(Uri.parse(f.output().asset(completed.getString("assetId")).uri).getPath());assertArrayEquals(f.bytes,Files.readAllBytes(output.toPath()));
            assertTrue(ProjectAssetImport.execute(f.context,f.request).getBoolean("replayed"));assertEquals(1,f.output().clips.size());
        }
    }

    @Test public void destinationEditDuringAliasedCopyRollsBackPublicationAndKeepsHumanRevision()throws Exception {
        try(Fixture f=new Fixture()){
            AtomicBoolean changed=new AtomicBoolean();String sourceBefore=f.sourceJson();
            Exception failure=assertThrows(Exception.class,()->ProjectAssetImport.execute(f.context,f.request,(copied,total)->{
                if(copied>0&&changed.compareAndSet(false,true)){ProjectStore.Project human=f.output();human.name="Human changed during copy";f.store.save(human);}
            }));
            assertTrue("The public copy must run before the CAS conflict",changed.get());assertTrue(String.valueOf(failure.getMessage()).contains("SOURCE_PROJECT_CHANGED"));
            assertEquals("Human changed during copy",f.output().name);assertEquals(1,f.output().assets.size());assertTrue(f.output().clips.isEmpty());
            assertEquals(sourceBefore,f.sourceJson());assertArrayEquals(f.bytes,Files.readAllBytes(f.file.toPath()));
            String human=f.outputJson();assertThrows(Exception.class,()->ProjectAssetImport.execute(f.context,f.request));assertEquals(human,f.outputJson());
        }
    }

    @Test public void modifiedAliasedPublishedBytesRejectReplayWithoutSilentRepair()throws Exception {
        try(Fixture f=new Fixture()){
            JSONObject first=ProjectAssetImport.execute(f.context,f.request);ProjectStore.Asset asset=f.output().asset(first.getString("assetId"));
            Path published=new File(Uri.parse(asset.uri).getPath()).toPath();byte[] damaged={19,27,35};Files.write(published,damaged);String before=f.outputJson();
            Exception failure=assertThrows(Exception.class,()->ProjectAssetImport.execute(f.context,f.request));
            assertTrue(String.valueOf(failure.getMessage()).contains("RESULT_CHANGED"));assertArrayEquals(damaged,Files.readAllBytes(published));
            assertEquals(before,f.outputJson());assertArrayEquals(f.bytes,Files.readAllBytes(f.file.toPath()));
        }
    }

    private static final class FilesContext extends ContextWrapper {
        private final File files;
        FilesContext(Context context,File files){super(context);this.files=files;}
        @Override public File getFilesDir(){return files;}
    }
    private final class Fixture implements AutoCloseable {
        final Path container,realFiles;
        final Context context;
        final ProjectStore store;
        final ProjectStore.Project source,destination;
        final File file;
        final byte[] bytes=new byte[192*1024];
        final String digest;
        final JSONObject request;
        Fixture()throws Exception {
            container=Files.createTempDirectory(base.getFilesDir().toPath(),"trusted_import_context_");
            Path realUser=Files.createDirectory(container.resolve("real-user"));realFiles=Files.createDirectory(realUser.resolve("files"));
            Path alias=Files.createSymbolicLink(container.resolve("user-zero-alias"),realUser);File files=alias.resolve("files").toFile();
            assertTrue(Files.isSymbolicLink(alias));assertFalse(Files.isSymbolicLink(files.toPath()));assertEquals(realFiles.toFile().getCanonicalFile(),files.getCanonicalFile());
            context=new FilesContext(base,files);store=new ProjectStore(context);source=store.create("Known source");destination=store.create("Preserve destination audio");
            for(int i=0;i<bytes.length;i++)bytes[i]=(byte)(i*37+11);
            file=realFiles.resolve("owned-source.mp4").toFile().getCanonicalFile();Files.write(file.toPath(),bytes);digest=hash(bytes);
            ProjectStore.Asset original=new ProjectStore.Asset();original.id="source_asset";original.name="Owned video.mp4";original.mime="video/mp4";
            original.uri=Uri.fromFile(file).toString();original.sizeBytes=bytes.length;original.durationMs=8000;original.generated=true;original.role="generated_sketch_video";
            original.generationMetadata.put("fixtureProvenance","source-bound");source.assets.add(original);store.save(source);
            ProjectStore.Asset audio=new ProjectStore.Asset();audio.id="existing_audio";audio.name="Keep audio.wav";audio.mime="audio/wav";audio.uri="file:///unused-keep-audio.wav";audio.durationMs=8000;destination.assets.add(audio);
            StudioLayer lane=new StudioLayer();lane.type=StudioLayer.AUDIO;lane.assetId=audio.id;lane.durationMs=8000;destination.studioLayers.add(lane);store.save(destination);
            request=new JSONObject().put("projectId",destination.id).put("expectedUpdatedAt",destination.updatedAt).put("sourceProjectId",source.id)
                    .put("sourceProjectUpdatedAt",source.updatedAt).put("assetId",original.id).put("expectedSha256",digest).put("operationId",UUID.randomUUID().toString());
        }
        JSONObject info()throws Exception{return new JSONObject().put("projectId",source.id).put("expectedUpdatedAt",request.getLong("sourceProjectUpdatedAt")).put("assetId","source_asset");}
        ProjectStore.Project output(){return store.get(destination.id);}
        String outputJson()throws Exception{return SketchDocument.canonical(output().toJson());}
        String sourceJson()throws Exception{return SketchDocument.canonical(store.get(source.id).toJson());}
        void sourceUri(File file)throws Exception{ProjectStore.Project changed=store.get(source.id);changed.assets.get(0).uri=Uri.fromFile(file).toString();store.save(changed);request.put("sourceProjectUpdatedAt",changed.updatedAt);}
        Path generation()throws Exception{return context.getFilesDir().toPath().resolve("project_asset_imports").resolve(destination.id).resolve(hash(request.getString("operationId").getBytes(StandardCharsets.UTF_8)));}
        Path linkGeneration(Path target)throws Exception{Path operation=generation();Files.createDirectories(operation.getParent());return Files.createSymbolicLink(operation,target);}
        @Override public void close(){store.close();}
    }
    private static String hash(byte[] bytes)throws Exception {
        byte[] digest=MessageDigest.getInstance("SHA-256").digest(bytes);StringBuilder result=new StringBuilder();for(byte value:digest)result.append(String.format(Locale.ROOT,"%02x",value&255));return result.toString();
    }
}
