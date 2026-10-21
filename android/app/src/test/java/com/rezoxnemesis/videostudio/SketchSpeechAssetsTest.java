package com.rezoxnemesis.videostudio;

import android.content.Context;
import org.json.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.zip.ZipOutputStream;
import static org.junit.Assert.*;

/** Native import fixture envelopes contain actual unmodified product speech PCM.
 * Envelopes are explicitly test fixtures, not fresh inference or phone receipts.
 */
@RunWith(RobolectricTestRunner.class) @Config(sdk=33,manifest=Config.NONE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class SketchSpeechAssetsTest {
    static final class Fixture {
        final Context context=RuntimeEnvironment.getApplication();final ProjectStore projects=new ProjectStore(context);
        final ProjectStore.Project source=projects.create("Original spoken line");final OpenPipelineStore store=new OpenPipelineStore(context);
        final String id="speech_fixture_"+UUID.randomUUID();final JSONObject raw,record;final File result;
        Fixture(String language)throws Exception {this(language,false);}
        Fixture(String language,boolean corruptTiming)throws Exception {this(language,corruptTiming?1:0);}
        Fixture(String language,int timingMode)throws Exception {
            raw=new JSONObject(new String(SketchSpeechStudyTest.resource(language+"-dialogue.json"),StandardCharsets.UTF_8));
            if(timingMode==1){JSONObject segment=raw.getJSONArray("lines").getJSONObject(0).getJSONArray("timing").getJSONObject(0);
                segment.getJSONArray("modelDurationUnits").put(0,1);segment.put("sampleCount",43800);}
            if(timingMode==2)raw.getJSONArray("lines").getJSONObject(0).getJSONArray("timing").getJSONObject(0).put("modelDurationUnits",JSONObject.NULL);
            JSONObject line=raw.getJSONArray("lines").getJSONObject(0),authored=new JSONObject();
            for(String key:new String[]{"lineId","characterId","voice","text","startMs","slotMs","speed"})if(line.has(key))authored.put(key,line.get(key));
            JSONObject controls=new JSONObject().put("language",raw.getString("language")).put("durationMs",raw.get("durationMs")).put("lines",new JSONArray().put(authored));
            OpenPipelineOperations.execute(context,projects,"open_pipeline_export_request",new JSONObject().put("projectId",source.id)
                    .put("sourceProjectUpdatedAt",source.updatedAt).put("requestId",id).put("backend","kokoro").put("controls",controls).put("assetIds",new JSONArray()),null);
            record=store.get(id);byte[] master=SketchSpeechStudyTest.resource(language+".wav"),stem=SketchSpeechStudyTest.resource(language+"-stem.wav"),timing=raw.toString().getBytes(StandardCharsets.UTF_8);
            JSONArray files=new JSONArray().put(descriptor("dialogue.wav","audio","audio/wav",master))
                    .put(descriptor("line_00.wav","dialogue_stem","audio/wav",stem)).put(descriptor("dialogue.json","dialogue_timing","application/json",timing));
            JSONObject manifest=new JSONObject().put("schema",OpenPipelineArtifacts.RESULT_SCHEMA).put("state","completed").put("jobId","speech_fixture")
                    .put("requestId",id).put("projectId",source.id).put("sourceProjectUpdatedAt",source.updatedAt).put("requestSha256",record.get("requestSha256")).put("files",files);
            result=new File(store.directory(id),id+".vsresult");try(ZipOutputStream z=new ZipOutputStream(new FileOutputStream(result))){
                OpenPipelineArtifacts.entry(z,"manifest.json",manifest.toString().getBytes(StandardCharsets.UTF_8));
                OpenPipelineArtifacts.entry(z,"outputs/dialogue.wav",master);OpenPipelineArtifacts.entry(z,"outputs/line_00.wav",stem);OpenPipelineArtifacts.entry(z,"outputs/dialogue.json",timing);
            }
            record.put("state","completed").put("jobId","speech_fixture").put("resultSha256",OpenPipelineArtifacts.hash(result)).put("resultBytes",result.length());store.save(record);
            OpenPipelineOperations.execute(context,projects,"open_pipeline_import_result",new JSONObject().put("requestId",id),null);
        }
        JSONObject descriptor(String name,String role,String mime,byte[] bytes)throws Exception {return new JSONObject().put("path","outputs/"+name).put("role",role).put("mime",mime).put("bytes",bytes.length).put("sha256",OpenPipelineArtifacts.hash(bytes));}
    }
    private static JSONObject load(Fixture f)throws Exception {
        try{return (JSONObject)Class.forName("com.rezoxnemesis.videostudio.SketchSpeechAssets").getDeclaredMethod("load",Context.class,String.class,int.class,double.class).invoke(null,f.context,f.id,0,6000d);}
        catch(java.lang.reflect.InvocationTargetException e){throw (Exception)e.getCause();}
    }
    @Test public void importedRealSpeechBecomesEditableCuesAndARevisionBoundNativeEdit()throws Exception {
        for(String language:new String[]{"english","hindi"}){
            Fixture f=new Fixture(language);JSONObject loaded=load(f);assertTrue(loaded.getJSONObject("speech").has("cues"));
            assertEquals(f.raw.getJSONArray("lines").getJSONObject(0).getString("text"),loaded.getString("text"));
            ProjectStore.Project owner=f.projects.get(loaded.getString("projectId"));ProjectStore.Asset audio=owner.asset(loaded.getString("assetId"));
            assertNotNull(audio);assertEquals(OpenPipelineArtifacts.hash(SketchSpeechStudyTest.resource(language+".wav")),loaded.getString("audioSha256"));
            String sketch=SketchMouthAnimationTest.create(f.context,180,30);JSONObject speech=new JSONObject().put("generated",new JSONObject().put("requestId",f.id).put("lineIndex",0));
            JSONObject request=SketchMouthAnimationTest.request(sketch,SketchSpeechAnimationTest.op(true,"front",speech));
            JSONObject receipt=SketchEditOps.execute(f.context,"sketch_edit",request);assertEquals(receipt.toString(),SketchEditOps.execute(f.context,"sketch_edit",request).toString());
            try(SketchProjectStore store=new SketchProjectStore(f.context)){assertNotNull(SketchSpeechAnimationTest.part(store.get(sketch),"_cavity","hindi".equals(language)?87:72));}
        }
    }
    @Test public void modifiedImportedAudioAndLostAssetOwnershipRejectBeforeDrawingMutation()throws Exception {
        Fixture f=new Fixture("english");JSONObject loaded=load(f);ProjectStore.Project owner=f.projects.get(loaded.getString("projectId"));
        ProjectStore.Asset audio=owner.asset(loaded.getString("assetId"));File file=new File(android.net.Uri.parse(audio.uri).getPath());
        byte[] bytes=Files.readAllBytes(file.toPath());bytes[100]^=1;Files.write(file.toPath(),bytes);
        assertThrows(Exception.class,()->load(f));
        String sketch=SketchMouthAnimationTest.create(f.context,180,30);JSONObject before;try(SketchProjectStore store=new SketchProjectStore(f.context)){before=store.get(sketch);}
        JSONObject op=SketchSpeechAnimationTest.op(true,"front",new JSONObject().put("generated",new JSONObject().put("requestId",f.id).put("lineIndex",0)));
        assertThrows(Exception.class,()->SketchEditOps.execute(f.context,"sketch_edit",SketchMouthAnimationTest.request(sketch,op)));
        try(SketchProjectStore store=new SketchProjectStore(f.context)){assertEquals(SketchDocument.canonical(before),SketchDocument.canonical(store.get(sketch)));}
        Files.write(file.toPath(),SketchSpeechStudyTest.resource("english.wav"));owner.assets.removeIf(a->a.id.equals(audio.id));f.projects.save(owner);assertThrows(Exception.class,()->load(f));
    }
    @Test public void generatedPredictionClockMustMatchTheActualUnchangedPcmStem()throws Exception {
        assertThrows("Self-consistent JSON cannot override the original 51000 PCM samples",Exception.class,()->new Fixture("english",true));
    }
    @Test public void olderAudioWithoutModelDurationsStillImportsButDoesNotInventSpeechCues()throws Exception {
        Fixture f=new Fixture("english",2);assertTrue(f.store.get(f.id).getBoolean("importReady"));
        assertThrows("Missing model predictions require manual cues; the real audio remains usable",Exception.class,()->load(f));
    }
    @Test public void generatedSpeechAlsoWorksInsideMixedPaintAndDependencyCopyBatches()throws Exception {
        Fixture f=new Fixture("english");
        for(boolean face:new boolean[]{false,true})for(String type:new String[]{"add_paint_cel","clear_cel","duplicate_cel"}){
            String sketch=SketchMouthAnimationTest.create(f.context,180,30);
            JSONObject extra=new JSONObject().put("type",type).put("layerId","ink");
            if("add_paint_cel".equals(type))extra.put("objectId","artist_paint").put("frame",0).put("exposure",180);
            else if("clear_cel".equals(type))extra.put("frame",0);
            else extra.put("fromFrame",0).put("toFrame",1).put("idPrefix","artist_copy");
            JSONObject speech=new JSONObject().put("generated",new JSONObject().put("requestId",f.id).put("lineIndex",0));
            JSONObject request=SketchMouthAnimationTest.request(sketch,SketchSpeechAnimationTest.op(face,"front",speech));
            request.put("operations",new JSONArray().put(new JSONObject().put("type","add_layer").put("layer",new JSONObject().put("id","ink").put("name","Artist ink")))
                    .put(extra).put(SketchSpeechAnimationTest.op(face,"front",speech)));
            JSONObject receipt=SketchEditOps.execute(f.context,"sketch_edit",request);assertEquals(receipt.toString(),SketchEditOps.execute(f.context,"sketch_edit",request).toString());
            try(SketchProjectStore store=new SketchProjectStore(f.context)){
                assertNotNull("Mixed "+type,SketchSpeechAnimationTest.part(store.get(sketch),"_cavity",72));
                JSONArray visual=store.liveEvents(sketch,0,8).getJSONArray("events").getJSONObject(0).getJSONArray("operations");
                JSONObject expanded=visual.getJSONObject(visual.length()-1).getJSONObject("speech");assertTrue(expanded.has("cues"));assertFalse(expanded.has("generated"));
            }
        }
    }
}
