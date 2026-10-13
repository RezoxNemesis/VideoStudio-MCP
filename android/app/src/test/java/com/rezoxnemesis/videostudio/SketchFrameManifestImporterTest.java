package com.rezoxnemesis.videostudio;

import android.content.Context;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=33,manifest=Config.NONE)
public final class SketchFrameManifestImporterTest {
    private final Context context=RuntimeEnvironment.getApplication();
    private final String hash="a".repeat(64);

    private JSONObject object(String id,int frame,int exposure,boolean background)throws Exception {
        JSONObject base=new JSONObject().put("id",id).put("frame",frame)
                .put("exposure",exposure).put("color","#FF111111")
                .put("width",.003).put("fill",false);
        if(background)return base.put("kind","rect").put("fill",true)
                .put("bounds",new JSONObject().put("x",0).put("y",0)
                    .put("width",1).put("height",1));
        return base.put("kind","path").put("commands",new JSONArray()
                .put(new JSONArray().put("M").put(.1).put(.2))
                .put(new JSONArray().put("L").put(.3).put(.4))
                .put(new JSONArray().put("L").put(.5).put(.2)));
    }
    private byte[] manifest(String sketch,String actualHash)throws Exception{
        JSONObject header=new JSONObject()
            .put("schema","videostudio.sketch.frame-ops.v1")
            .put("source","fixture.mp4")
            .put("sourceSha256",actualHash)
            .put("targetSketchId",sketch)
            .put("targetWidth",720).put("targetHeight",1280)
            .put("fps",24).put("frameCount",3);
        JSONArray background=new JSONArray().put(new JSONObject()
            .put("type","add_object").put("layerId","layer_1")
            .put("object",object("background",0,3,true)));
        StringBuilder data=new StringBuilder(new JSONObject()
                .put("header",header)
                .put("backgroundOperations",background).toString()).append('\n');
        for(int frame=0;frame<3;frame++)data.append(
                new JSONObject().put("frame",frame)
                .put("operations",new JSONArray().put(new JSONObject()
                .put("type","add_object").put("layerId","combat_black")
                .put("object",object("black_"+frame,frame,1,false)))).toString()
        ).append('\n');
        return data.toString().getBytes(StandardCharsets.UTF_8);
    }
    private String name(){return "fixture_manifest_"+UUID.randomUUID().toString().replace("-","");}

    @Test public void importsEverySourceFrameAsEditableNativeCelAndReplays()throws Exception{
        String id=name();byte[] data=manifest(id,hash);
        AtomicInteger progress=new AtomicInteger();
        JSONObject first=SketchFrameManifestImporter.applyBytes(context,
                data,hash,id,3,(drawn,total)->{
                    assertEquals(3,total);progress.set(drawn);
                });
        assertTrue(first.getBoolean("ok"));
        try(SketchProjectStore store=new SketchProjectStore(context)){JSONObject live=store.liveSession(id,first.getString("liveSessionId"));assertTrue(live.getBoolean("ended"));JSONArray events=store.liveEvents(id,live.getLong("afterSequence"),8).getJSONArray("events");int marks=0;for(int n=0;n<events.length();n++){JSONArray ops=events.getJSONObject(n).getJSONArray("operations");for(int k=0;k<ops.length();k++)if("add_object".equals(ops.getJSONObject(k).getString("type")))marks++;}assertEquals("Bulk import retains every mark for native live presentation",4,marks);}
        assertEquals(3,first.getInt("frameCount"));
        assertEquals(3,progress.get());
        assertEquals(24,first.getInt("fps"));
        assertEquals(hash,first.getString("verifiedSourceSha256"));
        try(SketchProjectStore store=new SketchProjectStore(context)){
            JSONObject doc=store.get(id);
            assertEquals(3,doc.getInt("frameCount"));
            assertEquals(720,doc.getInt("width"));
            assertEquals(1280,doc.getInt("height"));
            JSONArray layers=doc.getJSONArray("layers");
            HashSet<String> objectIds=new HashSet<>();
            for(int i=0;i<layers.length();i++){
                JSONArray ops=layers.getJSONObject(i).getJSONArray("objects");
                for(int j=0;j<ops.length();j++)objectIds.add(
                        ops.getJSONObject(j).getString("id"));
            }
            assertEquals(4,objectIds.size()); // 1 background + 3 individual cels
            for(int i=0;i<3;i++)assertTrue(objectIds.contains("black_"+i));
            SketchDocument.validate(doc);
        }
        JSONObject replay=SketchFrameManifestImporter.applyBytes(context,
                data,hash,id,3,(done,total)->{});
        assertEquals(first.getLong("revision"),replay.getLong("revision"));
        assertEquals(first.getString("manifestSha256"),replay.getString("manifestSha256"));
    }

    @Test public void rejectsMismatchedMp4BeforeCreatingSketch()throws Exception{
        String id=name();
        try {
            SketchFrameManifestImporter.applyBytes(context,manifest(id,hash),
                    "b".repeat(64),id,3,(done,total)->{});
            fail("Expected immutable source hash check");
        }catch(IllegalArgumentException expected){
            assertTrue(expected.getMessage().toLowerCase().contains("identit"));
        }
        try(SketchProjectStore store=new SketchProjectStore(context)){
            try{store.get(id);fail("Rejected input must not create a sketch");}
            catch(IllegalArgumentException expected){
                assertTrue(expected.getMessage().startsWith("UNKNOWN_SKETCH"));
            }
        }
    }

    @Test public void rejectsMissingFrameBeforeWritingAnything()throws Exception{
        String id=name();
        String[] lines=new String(manifest(id,hash),StandardCharsets.UTF_8).trim().split("\n");
        byte[] truncated=(lines[0]+"\n"+lines[1]+"\n").getBytes(StandardCharsets.UTF_8);
        try{
            SketchFrameManifestImporter.applyBytes(context,truncated,hash,id,3,
                    (done,total)->{});
            fail("Missing source frame cannot be silently inferred");
        }catch(IllegalArgumentException expected){
            assertTrue(expected.getMessage().contains("missing"));
        }
    }

    @Test public void scopedDrivePathRejectsTraversalWithoutBrowsing()throws Exception{
        DriveWorkspaceProvider drive=new DriveWorkspaceProvider(context);
        for(String name:new String[]{"../secret.mp4","folder/other.mp4","frames.mp4/../",""})
            try{drive.openLinkedSourceFile(name);fail("Bad name accepted");}
            catch(IllegalArgumentException expected){}
    }
    @Test public void invalidGeometryInLaterFrameDoesNotCreatePartialArtwork() throws Exception {
        String id=name();String[] lines=new String(manifest(id,hash),StandardCharsets.UTF_8).trim().split("\n");JSONObject last=new JSONObject(lines[3]);last.getJSONArray("operations").getJSONObject(0).getJSONObject("object").getJSONArray("commands").getJSONArray(1).put(1,2);lines[3]=last.toString();
        try{SketchFrameManifestImporter.applyBytes(context,String.join("\n",lines).getBytes(StandardCharsets.UTF_8),hash,id,3,null);fail("Invalid geometry accepted");}catch(IllegalArgumentException expected){}
        try(SketchProjectStore store=new SketchProjectStore(context)){try{store.get(id);fail("Preflight failure left a partial sketch");}catch(IllegalArgumentException expected){assertTrue(expected.getMessage().startsWith("UNKNOWN_SKETCH"));}}
    }
    @Test public void aggregateDocumentBudgetIsCheckedBeforeAnyImportBatch() throws Exception {
        String id=name();String[] lines=new String(manifest(id,hash),StandardCharsets.UTF_8).trim().split("\n");JSONObject first=new JSONObject(lines[0]);first.getJSONObject("header").put("frameCount",300);first.getJSONArray("backgroundOperations").getJSONObject(0).getJSONObject("object").put("exposure",300);StringBuilder raw=new StringBuilder(first.toString()).append('\n');
        for(int frame=0;frame<300;frame++){JSONArray ops=new JSONArray();for(int n=0;n<16;n++)ops.put(new JSONObject().put("type","add_object").put("layerId","combat_black").put("object",object("item_"+frame+"_"+n,frame,1,false)));raw.append(new JSONObject().put("frame",frame).put("operations",ops)).append('\n');}
        try{SketchFrameManifestImporter.applyBytes(context,raw.toString().getBytes(StandardCharsets.UTF_8),hash,id,300,(a,b)->fail("Oversized document reached import batches"));fail();}catch(IllegalArgumentException expected){assertTrue(expected.getMessage().contains("budget"));}
        try(SketchProjectStore store=new SketchProjectStore(context)){try{store.get(id);fail("Budget failure left a sketch");}catch(IllegalArgumentException expected){assertTrue(expected.getMessage().startsWith("UNKNOWN_SKETCH"));}}
    }
    @Test public void retryPreservesUserEditsAndReportsConflictInsteadOfSkippingSameId() throws Exception {
        String id=name();byte[] data=manifest(id,hash);JSONObject result=SketchFrameManifestImporter.applyBytes(context,data,hash,id,3,null);
        SketchEditOps.execute(context,"sketch_edit",new JSONObject().put("sketchId",id).put("operationId","human_colour").put("expectedRevision",result.getLong("revision")).put("operations",new JSONArray().put(new JSONObject().put("type","update_object").put("objectId","black_1").put("patch",new JSONObject().put("color","#FF00FF00")))));
        try{SketchFrameManifestImporter.applyBytes(context,data,hash,id,3,null);fail("Changed source object must not be reported as a complete original import");}catch(IllegalStateException expected){assertTrue(expected.getMessage().contains("SKETCH_IMPORT_CONFLICT"));}
        try(SketchProjectStore store=new SketchProjectStore(context)){assertEquals("#FF00FF00",SketchDocument.object(store.get(id),"black_1").getString("color"));}
    }

    @Test public void customPortraitLayersAcceptDenseFrameRowsWithoutOversizedBatches() throws Exception {
        String id=name();String[] lines=new String(manifest(id,hash),StandardCharsets.UTF_8).trim().split("\n");JSONObject first=new JSONObject(lines[0]);first.getJSONObject("header").put("frameCount",2).put("name","Portrait frames").put("layers",new JSONArray().put(new JSONObject().put("id","face").put("name","Portrait face")));first.getJSONArray("backgroundOperations").getJSONObject(0).getJSONObject("object").put("exposure",2);StringBuilder raw=new StringBuilder(first.toString()).append('\n');
        for(int frame=0;frame<2;frame++){JSONArray ops=new JSONArray();for(int n=0;n<(frame==0?64:16);n++)ops.put(new JSONObject().put("type","add_object").put("layerId","face").put("object",object("feature_"+frame+"_"+n,frame,1,false)));raw.append(new JSONObject().put("frame",frame).put("operations",ops)).append('\n');}
        JSONObject result=SketchFrameManifestImporter.applyBytes(context,raw.toString().getBytes(StandardCharsets.UTF_8),hash,id,2,null);assertEquals(81,result.getInt("validatedObjects"));assertEquals("UNVERIFIED",result.getString("visualFidelity"));try(SketchProjectStore store=new SketchProjectStore(context)){assertEquals("Portrait frames",store.get(id).getString("name"));assertEquals(80,SketchDocument.layer(store.get(id),"face").getJSONArray("objects").length());}
    }

    @Test public void replayDetectsChangedTimingRatherThanReportingOriginalFps() throws Exception {
        String id=name();byte[] data=manifest(id,hash);JSONObject imported=SketchFrameManifestImporter.applyBytes(context,data,hash,id,3,null);SketchEditOps.execute(context,"sketch_edit",new JSONObject().put("sketchId",id).put("operationId","human_timing").put("expectedRevision",imported.getLong("revision")).put("operations",new JSONArray().put(new JSONObject().put("type","set_timing").put("fps",60))));
        try{SketchFrameManifestImporter.applyBytes(context,data,hash,id,3,null);fail("Replay must preserve and detect changed timing");}catch(IllegalStateException expected){assertTrue(expected.getMessage().contains("SKETCH_IMPORT_CONFLICT"));}try(SketchProjectStore store=new SketchProjectStore(context)){assertEquals(60,store.get(id).getInt("fps"));}
    }
    @Test public void replayDoesNotRestoreSourceObjectsDeletedByTheHuman() throws Exception {
        String id=name();byte[] data=manifest(id,hash);JSONObject imported=SketchFrameManifestImporter.applyBytes(context,data,hash,id,3,null);SketchEditOps.execute(context,"sketch_edit",new JSONObject().put("sketchId",id).put("operationId","human_delete").put("expectedRevision",imported.getLong("revision")).put("operations",new JSONArray().put(new JSONObject().put("type","delete_object").put("objectId","black_1"))));
        try{SketchFrameManifestImporter.applyBytes(context,data,hash,id,3,null);fail("Replay must not restore a deleted drawing");}catch(IllegalStateException expected){assertTrue(expected.getMessage().contains("SKETCH_IMPORT_CONFLICT"));}
    }
    @Test public void rasterReferencePacketsAreRejectedBeforeCreatingAnyDrawing() throws Exception {
        String id=name();String[] lines=new String(manifest(id,hash),StandardCharsets.UTF_8).trim().split("\n");JSONObject row=new JSONObject(lines[1]);JSONObject image=object("image",0,1,true).put("kind","image").put("reference",new JSONObject().put("sha256",hash).put("file",hash+".png").put("width",256).put("height",256).put("sourceProjectId","source").put("sourceAssetId","asset"));row.getJSONArray("operations").getJSONObject(0).put("object",image);lines[1]=row.toString();
        try{SketchFrameManifestImporter.applyBytes(context,String.join("\n",lines).getBytes(StandardCharsets.UTF_8),hash,id,3,null);fail("A vector drawing import cannot embed a missing raster");}catch(IllegalArgumentException expected){}
        try(SketchProjectStore store=new SketchProjectStore(context)){try{store.get(id);fail("Preflight failure wrote a sketch");}catch(IllegalArgumentException expected){assertTrue(expected.getMessage().startsWith("UNKNOWN_SKETCH"));}}
    }

    @Test public void aDetailedPortraitFrameIsSplitIntoStableNativeBatchesAndReplaysExactly() throws Exception {
        String id=name();String[] lines=new String(manifest(id,hash),StandardCharsets.UTF_8).trim().split("\n");JSONObject first=new JSONObject(lines[0]);first.getJSONObject("header").put("frameCount",1);first.getJSONArray("backgroundOperations").getJSONObject(0).getJSONObject("object").put("exposure",1);JSONArray ops=new JSONArray();for(int n=0;n<227;n++)ops.put(new JSONObject().put("type","add_object").put("layerId","combat_black").put("object",object("portrait_"+n,0,1,false)));byte[] data=(first+"\n"+new JSONObject().put("frame",0).put("operations",ops)+"\n").getBytes(StandardCharsets.UTF_8);JSONObject result=SketchFrameManifestImporter.applyBytes(context,data,hash,id,1,null);assertEquals(228,result.getInt("validatedObjects"));JSONObject replay=SketchFrameManifestImporter.applyBytes(context,data,hash,id,1,null);assertEquals(result.getLong("revision"),replay.getLong("revision"));
    }

    @Test public void legacyExplicitNormalBlendAndNumericDefaultsReplayWithoutConflict() throws Exception {
        String id=name();byte[] data=manifest(id,hash);JSONObject imported=SketchFrameManifestImporter.applyBytes(context,data,hash,id,3,null);
        SketchEditOps.execute(context,"sketch_edit",new JSONObject().put("sketchId",id).put("operationId","legacy_defaults").put("expectedRevision",imported.getLong("revision")).put("operations",new JSONArray().put(new JSONObject().put("type","update_layer").put("layerId","bg_ink").put("patch",new JSONObject().put("blendMode","normal").put("opacity",1.0)))));
        JSONObject replay=SketchFrameManifestImporter.applyBytes(context,data,hash,id,3,null);assertEquals(imported.getLong("revision")+1,replay.getLong("revision"));
    }

    @Test public void repartitionedLegacyReceiptPreservesHumanDeletedObjects() throws Exception {
        String id=name();String[] oldLines=new String(manifest(id,hash),StandardCharsets.UTF_8).trim().split("\n");JSONObject first=new JSONObject(oldLines[0]);first.getJSONObject("header").put("frameCount",7);first.getJSONArray("backgroundOperations").getJSONObject(0).getJSONObject("object").put("exposure",7);StringBuilder raw=new StringBuilder(first.toString()).append('\n');
        for(int f=0;f<7;f++){JSONArray commands=new JSONArray().put(new JSONArray().put("M").put(.1).put(.2));for(int n=1;n<1540;n++)commands.put(new JSONArray().put("L").put(.12345678901234568).put(.23456789012345678));JSONObject o=object("legacy_"+f,f,1,false).put("commands",commands);raw.append(new JSONObject().put("frame",f).put("operations",new JSONArray().put(new JSONObject().put("type","add_object").put("layerId","combat_black").put("object",o)))).append('\n');}
        byte[] data=raw.toString().getBytes(StandardCharsets.UTF_8);assertTrue(data.length>480*1024&&data.length<512*1024);
        // Retain an actual394-style complete document and its single logical frame0 receipt.
        String manifestSha=SketchDocument.sha256(data);JSONObject create=new JSONObject().put("sketchId",id).put("operationId","legacy_create").put("width",720).put("height",1280).put("frameCount",7).put("background","#FFF7EFE7");JSONObject legacy=SketchDocument.create(create);
        for(String layer:new String[]{"bg_ink","combat_black","combat_white","action_ink"})SketchEditOps.apply(legacy,new JSONObject().put("type","add_layer").put("layer",new JSONObject().put("id",layer).put("blendMode","normal")));
        JSONArray retained=new JSONArray();SketchEditOps.apply(legacy,first.getJSONArray("backgroundOperations").getJSONObject(0));String[] rows=raw.toString().trim().split("\n");for(int n=1;n<rows.length;n++){JSONObject operation=new JSONObject(rows[n]).getJSONArray("operations").getJSONObject(0);SketchEditOps.apply(legacy,operation);retained.put(operation);}
        JSONObject imported;try(SketchProjectStore store=new SketchProjectStore(context)){store.create(create,"legacy_create");imported=store.mutate(id,"manifest_"+manifestSha.substring(0,10)+"_frame_0",1,"legacy_batch",ignored->legacy,retained);}
        context.getSharedPreferences("sketch_manifest_import_v1",Context.MODE_PRIVATE).edit().putString(id,hash+":"+manifestSha).commit();
        SketchFrameManifestImporter.applyBytes(context,data,hash,id,7,null);assertFalse("Replay retains original legacy receipt provenance",context.getSharedPreferences("sketch_manifest_import_v1",Context.MODE_PRIVATE).getBoolean(id+":partition395",false));
        SketchEditOps.execute(context,"sketch_edit",new JSONObject().put("sketchId",id).put("operationId","human_delete_legacy").put("expectedRevision",imported.getLong("revision")).put("operations",new JSONArray().put(new JSONObject().put("type","delete_object").put("objectId","legacy_6"))));
        try{SketchFrameManifestImporter.applyBytes(context,data,hash,id,7,null);fail("Repartitioning must not restore human deletions from a committed legacy batch");}catch(IllegalStateException expected){assertTrue(expected.getMessage().contains("SKETCH_IMPORT_CONFLICT"));}
        try(SketchProjectStore store=new SketchProjectStore(context)){try{SketchDocument.object(store.get(id),"legacy_6");fail("Deleted source object was restored");}catch(IllegalArgumentException expected){}}
    }

    @Test public void compressedDrawingPacketsDecodeWithinBudgetAndRejectExpansionWithoutWrites() throws Exception {
        String id=name();byte[] plain=manifest(id,hash);java.io.ByteArrayOutputStream bytes=new java.io.ByteArrayOutputStream();try(java.util.zip.GZIPOutputStream gzip=new java.util.zip.GZIPOutputStream(bytes)){gzip.write(plain);}
        byte[] decoded=SketchFrameManifestImporter.readPacket(new java.io.ByteArrayInputStream(bytes.toByteArray()),true);assertArrayEquals(plain,decoded);assertEquals(4,SketchFrameManifestImporter.applyBytes(context,decoded,hash,id,3,null).getInt("validatedObjects"));
        bytes.reset();try(java.util.zip.GZIPOutputStream gzip=new java.util.zip.GZIPOutputStream(bytes)){byte[] block=new byte[32768];for(int n=0;n<129;n++)gzip.write(block);}try{SketchFrameManifestImporter.readPacket(new java.io.ByteArrayInputStream(bytes.toByteArray()),true);fail("Oversized gzip expansion must fail before any import");}catch(IllegalArgumentException expected){assertTrue(expected.getMessage().contains("budget"));}
    }
    @Test public void sourceHashStreamsBeyondTheOld64MiBLimitWithoutBufferingTheSource() throws Exception {
        final long length=65L*1024*1024;java.io.InputStream input=new java.io.InputStream(){long remaining=length;public int read(){return remaining-->0?0:-1;}public int read(byte[] b,int off,int max){if(remaining==0)return -1;int n=(int)Math.min(remaining,max);java.util.Arrays.fill(b,off,off+n,(byte)0);remaining-=n;return n;}};
        java.security.MessageDigest digest=java.security.MessageDigest.getInstance("SHA-256");byte[] zeros=new byte[32768];for(long n=0;n<length;n+=zeros.length)digest.update(zeros);StringBuilder expected=new StringBuilder();for(byte b:digest.digest())expected.append(String.format(java.util.Locale.ROOT,"%02x",b&255));assertEquals(expected.toString(),SketchFrameManifestImporter.sourceHash(input));
    }

 @Test public void oversizedCompressedMetadataFailsBeforeUnboundedHeaderConsumption() throws Exception {
  byte[] packet=new byte[4*1024*1024+65536+20];java.util.Arrays.fill(packet,(byte)'x');packet[0]=(byte)0x1f;packet[1]=(byte)0x8b;packet[2]=8;packet[3]=8;for(int n=4;n<10;n++)packet[n]=0;
  try{SketchFrameManifestImporter.readPacket(new java.io.ByteArrayInputStream(packet),true);fail("Oversized gzip header accepted");}catch(Exception expected){assertTrue(expected.toString(),String.valueOf(expected.getMessage()).contains("Compressed drawing packet exceeds"));}
 }
 @Test public void concatenatedEmptyGzipMembersCannotExhaustTheWorkerStack() throws Exception {
  java.io.ByteArrayOutputStream one=new java.io.ByteArrayOutputStream();try(java.util.zip.GZIPOutputStream gzip=new java.util.zip.GZIPOutputStream(one)){}java.io.ByteArrayOutputStream many=new java.io.ByteArrayOutputStream();for(int n=0;n<10000;n++)many.write(one.toByteArray());
  try{SketchFrameManifestImporter.readPacket(new java.io.ByteArrayInputStream(many.toByteArray()),true);fail("Multiple gzip members accepted");}catch(java.io.IOException expected){assertTrue(expected.toString(),expected.getMessage().contains("single gzip member"));}
 }
 @Test public void gzipOptionalMetadataAndHeaderCrcRemainCompatible() throws Exception {
  byte[] plain="native drawing packet π".getBytes(StandardCharsets.UTF_8);java.io.ByteArrayOutputStream compressed=new java.io.ByteArrayOutputStream();try(java.util.zip.GZIPOutputStream gzip=new java.util.zip.GZIPOutputStream(compressed)){gzip.write(plain);}byte[] base=compressed.toByteArray();java.io.ByteArrayOutputStream packet=new java.io.ByteArrayOutputStream();packet.write(base,0,10);byte[] header=packet.toByteArray();header[3]=30;packet.reset();packet.write(header);packet.write(new byte[]{3,0,1,2,3});packet.write("drawing.jsonl".getBytes(StandardCharsets.UTF_8));packet.write(0);packet.write("owned source".getBytes(StandardCharsets.UTF_8));packet.write(0);java.util.zip.CRC32 crc=new java.util.zip.CRC32();crc.update(packet.toByteArray());packet.write((int)(crc.getValue()&255));packet.write((int)((crc.getValue()>>>8)&255));packet.write(base,10,base.length-10);assertArrayEquals(plain,SketchFrameManifestImporter.readPacket(new java.io.ByteArrayInputStream(packet.toByteArray()),true));
 }
 @Test public void gzipCorruptedTrailerCannotReturnUnverifiedDrawingBytes() throws Exception {
  java.io.ByteArrayOutputStream compressed=new java.io.ByteArrayOutputStream();try(java.util.zip.GZIPOutputStream gzip=new java.util.zip.GZIPOutputStream(compressed)){gzip.write("owned source packet".getBytes(StandardCharsets.UTF_8));}byte[] packet=compressed.toByteArray();packet[packet.length-8]^=1;
  try{SketchFrameManifestImporter.readPacket(new java.io.ByteArrayInputStream(packet),true);fail("Corrupt CRC accepted");}catch(java.io.IOException expected){assertTrue(expected.getMessage().contains("checksum/size mismatch"));}
 }
}
