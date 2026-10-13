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
}
