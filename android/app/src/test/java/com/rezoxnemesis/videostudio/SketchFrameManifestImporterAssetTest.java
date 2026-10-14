package com.rezoxnemesis.videostudio;

import android.content.Context;
import android.net.Uri;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.GZIPOutputStream;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=33,manifest=Config.NONE)
public final class SketchFrameManifestImporterAssetTest {
    private final Context context=RuntimeEnvironment.getApplication();

    private JSONObject importAssets(ProjectStore projects,JSONObject request,
                                    SketchFrameManifestImporter.Progress progress)throws Exception {
        return SketchFrameManifestImporter.importFromProjectAssets(context,projects,request,progress);
    }
    private String sha(byte[] bytes)throws Exception {
        return SketchDocument.sha256(bytes);
    }
    private byte[] manifest(String sketch,String sourceSha)throws Exception {
        JSONObject header=new JSONObject().put("schema","videostudio.sketch.frame-ops.v1")
                .put("targetSketchId",sketch).put("sourceSha256",sourceSha)
                .put("targetWidth",256).put("targetHeight",256).put("fps",24)
                .put("frameCount",2).put("layers",new JSONArray());
        StringBuilder data=new StringBuilder(new JSONObject().put("header",header)
                .put("backgroundOperations",new JSONArray()).toString()).append('\n');
        for(int frame=0;frame<2;frame++)data.append(new JSONObject().put("frame",frame)
                .put("operations",new JSONArray().put(new JSONObject().put("type","add_object")
                        .put("layerId","layer_1").put("object",new JSONObject()
                                .put("id","authored_"+frame).put("kind","path")
                                .put("frame",frame).put("exposure",1)
                                .put("commands",new JSONArray("[[\"M\",0.1,0.2],[\"L\",0.7,0.8]]")))))
                .toString()).append('\n');
        return data.toString().getBytes(StandardCharsets.UTF_8);
    }
    private final class Fixture {
        final ProjectStore projects=new ProjectStore(context);
        final ProjectStore.Project project=projects.create("Private authored drawing source");
        final String sketch="asset_drawing_"+UUID.randomUUID().toString().replace("-","");
        final byte[] source="Draw an original two-frame pencil portrait.".getBytes(StandardCharsets.UTF_8);
        final byte[] packet;
        final File packetFile,sourceFile;
        final ProjectStore.Asset packetAsset,sourceAsset;
        final JSONObject request;
        Fixture(boolean gzip)throws Exception {
            byte[] plain=manifest(sketch,sha(source));
            if(gzip){ByteArrayOutputStream bytes=new ByteArrayOutputStream();try(GZIPOutputStream out=new GZIPOutputStream(bytes)){out.write(plain);}packet=bytes.toByteArray();}
            else packet=plain;
            File folder=new File(context.getFilesDir(),"imports/"+UUID.randomUUID());assertTrue(folder.mkdirs());
            packetFile=new File(folder,"packet.media");Files.write(packetFile.toPath(),packet);
            sourceFile=new File(folder,"source.media");Files.write(sourceFile.toPath(),source);
            packetAsset=projects.registerImportedAsset(project.id,Uri.fromFile(packetFile),"portrait.jsonl"+(gzip?".gz":""),"application/octet-stream",0,packet.length);
            sourceAsset=projects.registerImportedAsset(project.id,Uri.fromFile(sourceFile),"prompt.txt","text/plain",0,source.length);
            request=new JSONObject().put("projectId",project.id)
                    .put("sourceProjectUpdatedAt",projects.get(project.id).updatedAt)
                    .put("sketchId",sketch).put("manifestAssetId",packetAsset.id)
                    .put("expectedManifestSha256",sha(packet)).put("sourceAssetId",sourceAsset.id)
                    .put("expectedSourceSha256",sha(source)).put("expectedFrameCount",2)
                    .put("drawingMethod","original_strokes");
        }
    }
    private void absent(Fixture fixture)throws Exception {
        try(SketchProjectStore sketches=new SketchProjectStore(context)) {
            try{sketches.get(fixture.sketch);fail("Rejected packet created a drawing");}
            catch(IllegalArgumentException expected){assertTrue(expected.getMessage().startsWith("UNKNOWN_SKETCH"));}
        }
        assertFalse(context.getSharedPreferences("sketch_manifest_import_v1",Context.MODE_PRIVATE).contains(fixture.sketch));
    }
    private void rejected(Fixture fixture,String message)throws Exception {
        try{importAssets(fixture.projects,fixture.request,null);fail("Invalid asset packet accepted");}
        catch(Exception expected){assertTrue(expected.toString(),expected.getMessage().contains(message));}
        absent(fixture);
    }
    private void replacePacket(Fixture fixture,byte[] bytes)throws Exception {
        Files.write(fixture.packetFile.toPath(),bytes);
        ProjectStore.Project current=fixture.projects.get(fixture.project.id);
        current.asset(fixture.packetAsset.id).sizeBytes=bytes.length;
        fixture.projects.save(current);
        fixture.request.put("sourceProjectUpdatedAt",current.updatedAt).put("expectedManifestSha256",sha(bytes));
    }

    @Test public void importsGzipMailboxAssetThroughDeclaredOriginalSessionAndRetainsEveryMark()throws Exception {
        Fixture fixture=new Fixture(true);
        JSONObject result=importAssets(fixture.projects,fixture.request,(done,total)->assertEquals(2,total));
        assertTrue(result.getBoolean("ok"));
        assertEquals("original_strokes",result.getString("drawingMethod"));
        assertEquals(sha(fixture.packet),result.getString("verifiedManifestAssetSha256"));
        assertFalse(result.has("sourceVideoUnchanged"));
        try(SketchProjectStore sketches=new SketchProjectStore(context)) {
            JSONObject live=sketches.liveSession(fixture.sketch,result.getString("liveSessionId"));
            assertEquals("original_strokes",SketchDrawingPolicy.method(live));
            assertTrue(live.getBoolean("ended"));
            assertEquals(1,SketchLiveDrawing.read(context,fixture.sketch,live.getString("sessionId"))
                    .getJSONObject("document").getLong("revision"));
            JSONArray events=sketches.liveEvents(fixture.sketch,live.getLong("afterSequence"),8).getJSONArray("events");
            int marks=0;for(int i=0;i<events.length();i++)marks+=events.getJSONObject(i).getJSONArray("operations").length();
            assertEquals(2,marks);
        }
        long revision=result.getLong("revision");
        assertEquals(revision,importAssets(fixture.projects,fixture.request,null).getLong("revision"));
        assertArrayEquals(fixture.packet,Files.readAllBytes(fixture.packetFile.toPath()));
        assertArrayEquals(fixture.source,Files.readAllBytes(fixture.sourceFile.toPath()));
        assertEquals(fixture.request.getLong("sourceProjectUpdatedAt"),fixture.projects.get(fixture.project.id).updatedAt);
    }

    @Test public void packetAndSourceHashesAreVerifiedBeforeCreatingAnyDrawing()throws Exception {
        for(String field:new String[]{"expectedManifestSha256","expectedSourceSha256"}) {
            Fixture fixture=new Fixture(false);fixture.request.put(field,"f".repeat(64));
            rejected(fixture,field.equals("expectedManifestSha256")?"SKETCH_MANIFEST_SHA256_MISMATCH":"SOURCE_SHA256_MISMATCH");
        }
    }

    @Test public void projectRevisionMustStillMatchBeforeReadingTheImportedAssets()throws Exception {
        Fixture fixture=new Fixture(false);
        ProjectStore.Project current=fixture.projects.get(fixture.project.id);current.name="Human project edit";
        fixture.projects.save(current);
        rejected(fixture,"SOURCE_PROJECT_CHANGED");
        assertEquals("Human project edit",fixture.projects.get(current.id).name);
    }

    @Test public void assetFromAnotherProjectCannotBeUsedThroughItsGlobalId()throws Exception {
        Fixture fixture=new Fixture(false);ProjectStore.Project another=fixture.projects.create("Other source project");
        ProjectStore.Asset other=fixture.projects.registerImportedAsset(another.id,Uri.fromFile(fixture.packetFile),"other.jsonl","application/octet-stream",0,fixture.packet.length);
        fixture.request.put("manifestAssetId",other.id);
        rejected(fixture,"registered in the selected project");
    }

    @Test public void registeredExternalFilesAndSymlinksCannotBecomeDrawingPackets()throws Exception {
        for(boolean symlink:new boolean[]{false,true}) {
            Fixture fixture=new Fixture(false);File location;
            if(symlink) {
                location=new File(fixture.packetFile.getParentFile(),"alias.media");
                Files.createSymbolicLink(location.toPath(),fixture.packetFile.toPath());
            }else{location=File.createTempFile("unowned_drawing_",".media");Files.write(location.toPath(),fixture.packet);}
            try {
                ProjectStore.Project current=fixture.projects.get(fixture.project.id);
                current.asset(fixture.packetAsset.id).uri=Uri.fromFile(location).toString();fixture.projects.save(current);
                fixture.request.put("sourceProjectUpdatedAt",current.updatedAt);
                rejected(fixture,"ownership");
            }finally{Files.deleteIfExists(location.toPath());}
        }
    }

    @Test public void lastFrameGeometryIsPreflightedBeforeTheOriginalSessionOrAnyMarks()throws Exception {
        Fixture fixture=new Fixture(false);String[] rows=new String(fixture.packet,StandardCharsets.UTF_8).trim().split("\n");
        JSONObject last=new JSONObject(rows[2]);
        last.getJSONArray("operations").getJSONObject(0).getJSONObject("object").getJSONArray("commands").getJSONArray(1).put(1,2);
        rows[2]=last.toString();replacePacket(fixture,String.join("\n",rows).getBytes(StandardCharsets.UTF_8));
        try{importAssets(fixture.projects,fixture.request,null);fail("Invalid later geometry was accepted");}
        catch(IllegalArgumentException expected){}
        absent(fixture);
        try(SketchProjectStore sketches=new SketchProjectStore(context)){assertNull(sketches.activeDrawingSession(fixture.sketch));}
    }

    @Test public void gzipExpansionIsBoundedBeforeAnySketchWrite()throws Exception {
        Fixture fixture=new Fixture(true);ByteArrayOutputStream bytes=new ByteArrayOutputStream();
        try(GZIPOutputStream out=new GZIPOutputStream(bytes)){byte[] block=new byte[32768];for(int i=0;i<129;i++)out.write(block);}
        replacePacket(fixture,bytes.toByteArray());rejected(fixture,"expansion exceeds bounded import budget");
    }

    @Test public void interruptedOriginalImportResumesTheSameDeclaredSession()throws Exception {
        Fixture fixture=new Fixture(false);
        try{importAssets(fixture.projects,fixture.request,(done,total)->{if(done==1)throw new IOException("Paused native import");});fail();}
        catch(IOException expected){assertEquals("Paused native import",expected.getMessage());}
        String session;
        try(SketchProjectStore sketches=new SketchProjectStore(context)) {
            JSONObject live=sketches.activeDrawingSession(fixture.sketch);assertNotNull(live);
            assertEquals("original_strokes",SketchDrawingPolicy.method(live));session=live.getString("sessionId");
        }
        fixture.request.put("drawingMethod","source_assisted");
        try{importAssets(fixture.projects,fixture.request,null);fail("Interrupted original method was changed");}
        catch(IllegalStateException expected){assertTrue(expected.getMessage().contains("SKETCH_DRAWING_METHOD_CHANGED"));}
        fixture.request.put("drawingMethod","original_strokes");
        JSONObject finished=importAssets(fixture.projects,fixture.request,null);
        assertEquals(session,finished.getString("liveSessionId"));assertEquals(2,finished.getInt("validatedObjects"));
    }

    @Test public void humanTakeoverClosesAnIncompleteOriginalImportWithoutLaterSilentMarks()throws Exception {
        Fixture fixture=new Fixture(false);
        try{importAssets(fixture.projects,fixture.request,(done,total)->{
            if(done==1){try(SketchProjectStore sketches=new SketchProjectStore(context)){sketches.takeOverDrawing(fixture.sketch);}throw new IOException("Human took over");}
        });fail();}catch(IOException expected){assertEquals("Human took over",expected.getMessage());}
        long revision;try(SketchProjectStore sketches=new SketchProjectStore(context)){revision=sketches.get(fixture.sketch).getLong("revision");}
        try{importAssets(fixture.projects,fixture.request,null);fail("An ended incomplete stream was silently extended");}
        catch(IllegalStateException expected){assertTrue(expected.getMessage().contains("SKETCH_LIVE_SESSION_ENDED"));}
        try(SketchProjectStore sketches=new SketchProjectStore(context)){assertEquals(revision,sketches.get(fixture.sketch).getLong("revision"));}
    }

    @Test public void inFlightHumanTakeoverStopsTheNextBatchWhenTheProgressCallbackReturns()throws Exception {
        Fixture fixture=new Fixture(false);
        try {
            importAssets(fixture.projects,fixture.request,(done,total)->{
                if(done==1)try(SketchProjectStore sketches=new SketchProjectStore(context)){sketches.takeOverDrawing(fixture.sketch);}
            });
            fail("Import reported success after the human closed its original drawing session");
        }catch(IllegalStateException expected){assertTrue(expected.getMessage().contains("SKETCH_LIVE_SESSION_ENDED"));}
        try(SketchProjectStore sketches=new SketchProjectStore(context)) {
            assertEquals(1,sketches.get(fixture.sketch).getLong("revision"));
            assertEquals(0,sketches.liveEvents(fixture.sketch,0,8).getJSONArray("events").length());
            assertNull(sketches.activeDrawingSession(fixture.sketch));
        }
    }

    @Test public void sourceAssistedAssetAndLegacyImportsCannotBeRelabelledOriginalOnRetry()throws Exception {
        for(boolean legacy:new boolean[]{false,true}) {
            Fixture fixture=new Fixture(false);JSONObject first;
            if(legacy)first=SketchFrameManifestImporter.applyBytes(context,fixture.packet,sha(fixture.source),fixture.sketch,2,null);
            else{fixture.request.put("drawingMethod","source_assisted");first=importAssets(fixture.projects,fixture.request,null);}
            fixture.request.put("drawingMethod","original_strokes");
            try{importAssets(fixture.projects,fixture.request,null);fail("Existing assisted session gained original provenance");}
            catch(IllegalStateException expected){assertTrue(expected.getMessage().contains("SKETCH_DRAWING_METHOD_CHANGED"));}
            try(SketchProjectStore sketches=new SketchProjectStore(context)) {
                assertEquals("source_assisted",SketchDrawingPolicy.method(sketches.liveSession(fixture.sketch,first.getString("liveSessionId"))));
                assertEquals(first.getLong("revision"),sketches.get(fixture.sketch).getLong("revision"));
            }
        }
    }

    @Test public void retryPreservesHumanChangesToImportedOriginalObjects()throws Exception {
        Fixture fixture=new Fixture(false);JSONObject result=importAssets(fixture.projects,fixture.request,null);
        SketchEditOps.execute(context,"sketch_edit",new JSONObject().put("sketchId",fixture.sketch)
                .put("operationId","human_colour").put("expectedRevision",result.getLong("revision"))
                .put("operations",new JSONArray().put(new JSONObject().put("type","update_object").put("objectId","authored_0")
                        .put("patch",new JSONObject().put("color","#FF00AAFF")))));
        try{importAssets(fixture.projects,fixture.request,null);fail("Human edit was accepted as unchanged imported artwork");}
        catch(IllegalStateException expected){assertTrue(expected.getMessage().contains("SKETCH_IMPORT_CONFLICT"));}
        try(SketchProjectStore sketches=new SketchProjectStore(context)){assertEquals("#FF00AAFF",SketchDocument.object(sketches.get(fixture.sketch),"authored_0").getString("color"));}
    }

    @Test public void pausedDrawingProgressDoesNotHoldTheSourceProjectWriterLock()throws Exception {
        Fixture fixture=new Fixture(false);ProjectStore anotherWriter=new ProjectStore(context);
        ProjectStore.Project human=anotherWriter.get(fixture.project.id);human.name="Human project name during drawing";
        CountDownLatch saved=new CountDownLatch(1);AtomicReference<Throwable> error=new AtomicReference<>();
        Thread writer=new Thread(()->{try{anotherWriter.save(human);}catch(Throwable failure){error.set(failure);}finally{saved.countDown();}});
        try {
            JSONObject result=importAssets(fixture.projects,fixture.request,(done,total)->{
                if(done==1){writer.start();assertTrue("Native import held the project writer lock during a paused progress callback",saved.await(2,TimeUnit.SECONDS));}
            });
            assertFalse(result.has("sourceProjectUnchanged"));
            assertTrue(result.getBoolean("sourceProjectVerifiedAtAdmission"));
            assertFalse(result.getBoolean("sourceProjectModifiedByImporter"));
        }finally{writer.join(5000);}
        assertNull(error.get());assertFalse(writer.isAlive());
        assertEquals(human.name,fixture.projects.get(fixture.project.id).name);
    }
}
