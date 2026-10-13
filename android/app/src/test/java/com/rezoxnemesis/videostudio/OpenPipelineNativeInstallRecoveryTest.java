package com.rezoxnemesis.videostudio;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.UUID;
import java.util.zip.ZipOutputStream;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class) @Config(sdk=33,manifest=Config.NONE)
public class OpenPipelineNativeInstallRecoveryTest {
    private static final class Fixture {
        final Cinematic3DStoreTest.Fixture scene;
        final OpenPipelineStore journal;
        final JSONObject record;
        final String requestId,outputId,artifactHash;
        Fixture(boolean corrupt)throws Exception {
            scene=new Cinematic3DStoreTest.Fixture(corrupt?"hash":"");journal=new OpenPipelineStore(scene.c);requestId="recover_"+UUID.randomUUID();
            JSONObject parameters=new JSONObject().put("projectId",scene.project.id).put("sourceProjectUpdatedAt",scene.project.updatedAt).put("requestId",requestId).put("backend","blender").put("controls",new JSONObject().put("timeline",scene.timeline).put("renderFrames",0)).put("assetIds",new JSONArray());OpenPipelineOperations.execute(scene.c,scene.projects,"open_pipeline_export_request",parameters,null);record=journal.get(requestId);
            byte[] bundle=Files.readAllBytes(new File(android.net.Uri.parse(scene.archive.uri).getPath()).toPath());artifactHash=OpenPipelineArtifacts.hash(bundle);
            JSONObject manifest=new JSONObject().put("schema",OpenPipelineArtifacts.RESULT_SCHEMA).put("jobId","recovery_job").put("state","completed").put("requestId",requestId).put("requestSha256",record.getString("requestSha256")).put("projectId",scene.project.id).put("sourceProjectUpdatedAt",scene.project.updatedAt).put("files",new JSONArray().put(new JSONObject().put("path","scene.vscinematic").put("role","cinematic_bundle").put("mime","application/vnd.videostudio.cinematic3d").put("bytes",bundle.length).put("sha256",artifactHash)));
            File result=new File(journal.directory(requestId),requestId+".vsresult");try(ZipOutputStream zip=new ZipOutputStream(new FileOutputStream(result))){OpenPipelineArtifacts.entry(zip,"manifest.json",manifest.toString().getBytes(StandardCharsets.UTF_8));OpenPipelineArtifacts.entry(zip,"scene.vscinematic",bundle);}
            String resultHash=OpenPipelineArtifacts.hash(result);outputId=UUID.nameUUIDFromBytes(("openpipeline:"+requestId+":"+resultHash).getBytes(StandardCharsets.UTF_8)).toString();
            record.put("jobId","recovery_job").put("state","imported").put("resultSha256",resultHash).put("resultBytes",result.length()).put("outputProjectId",outputId);journal.save(record);
            // Simulate an interrupted old implementation: registered bytes exist,
            // but no required native bundle installation receipt was published.
            ProjectStore.Project output=new ProjectStore.Project();output.id=outputId;output.name="Interrupted native import";scene.projects.save(output);File files=new File(new CreativeWorkspace(scene.c).projectRoot(outputId),"exports/openpipeline_"+resultHash);files.mkdirs();File artifact=new File(files,"scene.vscinematic");Files.write(artifact.toPath(),bundle);ProjectStore.Asset asset=scene.projects.registerGeneratedAsset(output,android.net.Uri.fromFile(artifact),"scene.vscinematic","cinematic_bundle",false);asset.mime="application/vnd.videostudio.cinematic3d";asset.sizeBytes=bundle.length;asset.generationMetadata=new JSONObject().put("requestId",requestId).put("resultSha256",resultHash).put("artifactSha256",artifactHash);scene.projects.save(output);
        }
        JSONObject retry()throws Exception {return OpenPipelineOperations.execute(scene.c,scene.projects,"open_pipeline_import_result",new JSONObject().put("requestId",requestId),null);}
    }
    @Test public void existingOutputCannotClaimImportedWhenNativeBundleInstallFails()throws Exception {Fixture f=new Fixture(true);assertThrows(IllegalArgumentException.class,f::retry);JSONObject retained=f.journal.get(f.requestId);assertEquals("completed",retained.getString("state"));assertFalse(retained.getBoolean("importReady"));assertFalse(retained.has("nativeInstallations"));assertNotNull(f.scene.projects.get(f.outputId));}
    @Test public void existingOutputReconcilesAndRetainsEachRequiredNativeInstallationReceipt()throws Exception {Fixture f=new Fixture(false);JSONObject accepted=f.retry();assertTrue(accepted.getBoolean("imported"));JSONObject installation=f.journal.get(f.requestId).getJSONObject("nativeInstallations").getJSONObject("scene.vscinematic");assertTrue(installation.getBoolean("nativeInstallationVerified"));assertEquals(f.artifactHash,installation.getString("artifactSha256"));JSONObject active=NativeCinematicBundleStore.get(f.scene.c,f.scene.projects,f.outputId,installation.getString("bundleId"));assertEquals(active.getString("receiptSha256"),installation.getString("receiptSha256"));JSONObject replay=f.retry();assertEquals(f.outputId,replay.getString("outputProjectId"));assertTrue(replay.getBoolean("imported"));assertFalse(replay.getBoolean("nativeRendered"));}
}
