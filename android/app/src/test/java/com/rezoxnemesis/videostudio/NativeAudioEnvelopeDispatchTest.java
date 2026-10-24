package com.rezoxnemesis.videostudio;

import android.content.Context;
import android.net.Uri;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import java.io.File;
import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import static org.junit.Assert.*;

/** Future strict audio mode through the EXISTING public native command controller.
 * Real PCM-WAV bytes; no new production symbols, decoder shadows, network or media rendering.
 * Prepared only: root owns compilation and genuine baseline/result classification.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=33,manifest=Config.NONE)
public class NativeAudioEnvelopeDispatchTest {
    private final List<Fixture> fixtures=new ArrayList<>();
    @After public void close()throws Exception {for(Fixture fixture:fixtures)fixture.close();}

    @Test public void rightOnlyStereoEnergyReturnsAbsoluteAllChannelRmsSamplePeakAndExactCoverageWithoutEditing()throws Exception {
        short[] pcm=new short[16000];
        for(int frame=2000;frame<4000;frame++)pcm[2*frame+1]=16384;
        for(int frame=4000;frame<6000;frame++){pcm[2*frame]=8192;pcm[2*frame+1]=-8192;}
        for(int frame=6000;frame<8000;frame++){pcm[2*frame]=32767;pcm[2*frame+1]=32767;}
        Fixture f=fixture(pcm,2,1000);String projects=f.inventory(),active=f.store.active().id;byte[] before=Files.readAllBytes(f.file.toPath());
        JSONObject terminal=f.run(f.request());assertEquals(terminal.toString(),"completed",terminal.getString("status"));JSONObject result=terminal.getJSONObject("result");
        assertTrue(result.getBoolean("ok"));assertTrue(result.getBoolean("readOnly"));assertTrue(result.getBoolean("sourceUnchanged"));
        assertEquals("videostudio.audio.envelope.v1",result.getString("schema"));assertEquals("audio_envelope",result.getString("mode"));
        assertEquals(f.source.id,result.getString("projectId"));assertEquals(f.source.updatedAt,result.getLong("sourceProjectUpdatedAt"));
        assertEquals(f.asset.id,result.getString("assetId"));assertEquals(f.hash,result.getString("sourceSha256"));assertEquals(f.file.length(),result.getLong("sourceBytes"));
        assertEquals(8000,result.getInt("sampleRate"));assertEquals(2,result.getInt("channels"));assertEquals("pcm16",result.getString("pcmEncoding"));
        JSONObject coverage=result.getJSONObject("coverage");assertTrue(coverage.toString(),coverage.getBoolean("complete"));
        assertEquals(4,coverage.getInt("requestedBuckets"));assertEquals(4,coverage.getInt("decodedBuckets"));assertEquals(0,coverage.getInt("missingBuckets"));assertEquals(8000,coverage.getLong("decodedFrames"));
        JSONObject normalization=result.getJSONObject("normalization");assertEquals("window_max_rms",normalization.getString("method"));assertEquals(32767/32768.,normalization.getDouble("maximumRms"),1e-8);
        JSONArray buckets=result.getJSONArray("buckets");assertEquals(4,buckets.length());double[] rms={0,Math.sqrt(.125),.25,32767/32768.},peak={0,.5,.25,32767/32768.};
        for(int i=0;i<4;i++){
            JSONObject bucket=buckets.getJSONObject(i);assertEquals(i,bucket.getInt("index"));assertEquals(i*250000L,bucket.getLong("sourceStartUs"));assertEquals((i+1)*250000L,bucket.getLong("sourceEndUs"));
            assertEquals(2000,bucket.getLong("sampleFrames"));assertEquals(4000,bucket.getLong("channelSamples"));
            assertEquals(rms[i],bucket.getDouble("rms"),1e-8);assertEquals(peak[i],bucket.getDouble("samplePeak"),1e-8);
            assertEquals(rms[i]/rms[3],bucket.getDouble("normalizedRms"),1e-8);assertTrue(bucket.getJSONObject("coverage").getBoolean("complete"));
        }
        assertEquals(projects,f.inventory());assertEquals(active,f.store.active().id);assertArrayEquals(before,Files.readAllBytes(f.file.toPath()));
    }

    @Test public void measuredSilenceHasPositiveCountsAndCompleteCoverageRatherThanMissingAudio()throws Exception {
        Fixture f=fixture(new short[8000],1,1000);JSONObject terminal=f.run(f.request());assertEquals(terminal.toString(),"completed",terminal.getString("status"));JSONObject result=terminal.getJSONObject("result");
        assertTrue(result.getJSONObject("coverage").getBoolean("complete"));assertEquals(0,result.getJSONObject("normalization").getDouble("maximumRms"),0);
        JSONArray buckets=result.getJSONArray("buckets");assertEquals(4,buckets.length());
        for(int i=0;i<4;i++){JSONObject bucket=buckets.getJSONObject(i);assertEquals(2000,bucket.getLong("sampleFrames"));assertEquals(0,bucket.getDouble("rms"),0);assertEquals(0,bucket.getDouble("samplePeak"),0);assertEquals(0,bucket.getDouble("normalizedRms"),0);assertTrue(bucket.getJSONObject("coverage").getBoolean("complete"));}
    }

    @Test public void strictAudioModeRejectsCoercionsBoundsMixedVisualFieldsAndUnknownPropertiesBeforeDecoding()throws Exception {
        Fixture f=fixture(new short[8000],1,1000);String before=f.inventory(),active=f.store.active().id;JSONArray invalid=new JSONArray();
        for(Object value:new Object[]{JSONObject.NULL,true,7,"visual","audio"})invalid.put(f.request().put("mode",value));
        for(Object value:new Object[]{JSONObject.NULL,"0",false,-1,.5,86400001L})invalid.put(f.request().put("sourceMs",value));
        for(Object value:new Object[]{JSONObject.NULL,"1000",true,99,30001,100.5})invalid.put(f.request().put("durationMs",value));
        for(Object value:new Object[]{JSONObject.NULL,"4",true,3,721,4.5})invalid.put(f.request().put("buckets",value));
        for(String field:new String[]{"frames","start","end","startMs","endMs","includeAudio","unknown"})invalid.put(f.request().put(field,1));
        for(String field:new String[]{"projectId","expectedUpdatedAt","assetId","expectedSha256","sourceMs","durationMs","buckets"}){JSONObject missing=f.request();missing.remove(field);invalid.put(missing);}
        for(int i=0;i<invalid.length();i++)assertFailure(f.run(invalid.getJSONObject(i)),"AUDIO_ENVELOPE_INPUT");
        assertEquals(before,f.inventory());assertEquals(active,f.store.active().id);
    }

    @Test public void staleProjectRevisionAndWrongExpectedHashCannotReturnAnalysisOfAnotherSourceGeneration()throws Exception {
        Fixture f=fixture(new short[8000],1,1000);String before=f.inventory(),active=f.store.active().id;
        assertFailure(f.run(f.request().put("expectedUpdatedAt",f.source.updatedAt+1)),"AUDIO_ENVELOPE_SOURCE_CHANGED");
        assertFailure(f.run(f.request().put("expectedSha256","0".repeat(64))),"AUDIO_ENVELOPE_SOURCE_CHANGED");
        assertEquals(before,f.inventory());assertEquals(active,f.store.active().id);
    }

    @Test public void missingProjectOrAssetNeverCreatesSelectsOrFallsBackToAnotherOwnedAsset()throws Exception {
        Fixture f=fixture(new short[8000],1,1000);String before=f.inventory(),active=f.store.active().id;
        JSONObject missingProject=f.run(f.request().put("projectId",UUID.randomUUID().toString()));
        JSONObject missingAsset=f.run(f.request().put("assetId",UUID.randomUUID().toString()));
        assertEquals("Read-only analysis cannot create a fallback project or change authored metadata",before,f.inventory());
        assertEquals("Read-only analysis cannot select another project",active,f.store.active().id);
        assertFailure(missingProject,"AUDIO_ENVELOPE_SOURCE_CHANGED");assertFailure(missingAsset,"AUDIO_ENVELOPE_SOURCE_CHANGED");
    }

    @Test public void windowOutsideActualPcmRejectsDespiteLongerMetadataAndDoesNotInventASilentTail()throws Exception {
        Fixture f=fixture(new short[8000],1,3000);String before=f.inventory();
        assertFailure(f.run(f.request().put("sourceMs",900).put("durationMs",500)),"AUDIO_ENVELOPE_WINDOW_OUTSIDE_SOURCE");
        assertEquals(before,f.inventory());assertEquals(f.hash,SketchProjectStore.fileHash(f.file));
    }

    private static void assertFailure(JSONObject terminal,String code)throws Exception {
        assertEquals(terminal.toString(),"failed",terminal.getString("status"));JSONObject result=terminal.getJSONObject("result");
        assertFalse(result.toString(),result.getBoolean("ok"));assertEquals(result.toString(),code,result.getString("code"));assertFalse(result.has("buckets"));
    }
    private Fixture fixture(short[] pcm,int channels,long metadataDuration)throws Exception {Fixture f=new Fixture(pcm,channels,metadataDuration);fixtures.add(f);return f;}
    private final class Fixture implements AutoCloseable {
        final Context context=RuntimeEnvironment.getApplication();final ProjectStore store=new ProjectStore(context);
        final ControlService service=Robolectric.buildService(ControlService.class).get();final CommandJournal journal=new CommandJournal(context);
        final AppProtocol protocol=new AppProtocol(context,null);final RecoveryPlanStore plans=new RecoveryPlanStore(context);
        final JobManager jobs=new JobManager(context,"audio_envelope_contract_jobs");
        final ExecutorService dispatch=Executors.newSingleThreadExecutor(),analysis=Executors.newSingleThreadExecutor();
        final File file;final String hash;final ProjectStore.Project source;final ProjectStore.Asset asset;
        Fixture(short[] pcm,int channels,long metadataDuration)throws Exception {
            protocol.stop();file=new File(context.getFilesDir(),"audio-envelope-"+UUID.randomUUID()+".wav");Files.write(file.toPath(),wav(pcm,channels));hash=SketchProjectStore.fileHash(file);
            ProjectStore.Project created=store.create("Exact audio source");asset=store.registerImportedAsset(created.id,Uri.fromFile(file),"selected.wav","audio/wav",metadataDuration,file.length());source=store.get(created.id);store.create("Keep selected edit unchanged");
            set(jobs,"lightPool",analysis);set(jobs,"heavyPool",analysis);set(jobs,"transferPool",analysis);
            set(service,"store",store);set(service,"jobs",jobs);set(service,"commandJournal",journal);set(service,"protocol",protocol);set(service,"recoveryPlans",plans);
            set(service,"mediaAnalyzer",new NativeMediaAnalyzer(context));set(service,"serviceAlive",true);set(service,"prefs",context.getSharedPreferences("videostudio_native_v1",Context.MODE_PRIVATE));
        }
        JSONObject request()throws Exception{return new JSONObject().put("mode","audio_envelope").put("projectId",source.id).put("expectedUpdatedAt",source.updatedAt).put("assetId",asset.id).put("expectedSha256",hash).put("sourceMs",0).put("durationMs",1000).put("buckets",4);}
        JSONObject run(JSONObject parameters)throws Exception {
            JSONObject command=new JSONObject().put("id",UUID.randomUUID().toString()).put("seq",1).put("action","analyse_media").put("parameters",parameters);
            dispatch.submit(()->service.onCommand(command)).get(10,TimeUnit.SECONDS);analysis.submit(()->{}).get(10,TimeUnit.SECONDS);
            JSONObject result=journal.terminal(command.getString("id"));assertNotNull("The native command must reach an honest durable terminal receipt",result);return result;
        }
        String inventory()throws Exception {JSONArray inventory=new JSONArray();for(ProjectStore.Project project:store.list())inventory.put(project.toJson());return SketchDocument.canonical(inventory);}
        public void close()throws Exception {
            jobs.shutdown();dispatch.shutdownNow();analysis.shutdownNow();dispatch.awaitTermination(5,TimeUnit.SECONDS);analysis.awaitTermination(5,TimeUnit.SECONDS);protocol.stop();
            for(String name:new String[]{"commandCompletionWatchers","directorSetupExecutor","nativeSourceExecutor","cinematic3DExecutor","authoringExecutor","pipelineExecutor"})((ExecutorService)field(service,name)).shutdownNow();
            store.close();
        }
    }
    private static byte[] wav(short[] pcm,int channels){
        ByteBuffer data=ByteBuffer.allocate(44+pcm.length*2).order(ByteOrder.LITTLE_ENDIAN);data.put("RIFF".getBytes(StandardCharsets.US_ASCII)).putInt(data.capacity()-8).put("WAVEfmt ".getBytes(StandardCharsets.US_ASCII)).putInt(16).putShort((short)1).putShort((short)channels).putInt(8000).putInt(8000*channels*2).putShort((short)(channels*2)).putShort((short)16).put("data".getBytes(StandardCharsets.US_ASCII)).putInt(pcm.length*2);for(short value:pcm)data.putShort(value);return data.array();
    }
    private static void set(Object object,String name,Object value)throws Exception {Field field=object.getClass().getDeclaredField(name);field.setAccessible(true);field.set(object,value);}
    private static Object field(Object object,String name)throws Exception {Field field=object.getClass().getDeclaredField(name);field.setAccessible(true);return field.get(object);}
}
