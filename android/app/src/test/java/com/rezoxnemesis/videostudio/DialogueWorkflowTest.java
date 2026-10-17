package com.rezoxnemesis.videostudio;
import android.content.Context;
import android.net.Uri;
import android.widget.Spinner;
import org.json.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.Config;
import java.io.*;
import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import java.util.zip.ZipOutputStream;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class) @Config(sdk=33,manifest=Config.NONE)
public class DialogueWorkflowTest {
    static JSONObject controls()throws Exception {return new JSONObject().put("language","hi-IN").put("durationMs",12000).put("lines",new JSONArray().put(new JSONObject().put("lineId","first").put("characterId","kael").put("voice","hm_omega").put("text","मैं लड़ना नहीं चाहता।").put("startMs",1000).put("slotMs",5000)));}
    static byte[] wave(int milliseconds){int count=24*milliseconds;ByteBuffer b=ByteBuffer.allocate(44+count*2).order(ByteOrder.LITTLE_ENDIAN);b.put("RIFF".getBytes(StandardCharsets.US_ASCII)).putInt(b.capacity()-8).put("WAVEfmt ".getBytes(StandardCharsets.US_ASCII)).putInt(16).putShort((short)1).putShort((short)1).putInt(24000).putInt(48000).putShort((short)2).putShort((short)16).put("data".getBytes(StandardCharsets.US_ASCII)).putInt(count*2);for(int i=0;i<count;i++)b.putShort((short)(4000*Math.sin(i*.03)));return b.array();}
    @Test public void validatesRealPcmAndRejectsTruncationOrCorruptLayout()throws Exception {File f=File.createTempFile("dialogue_",".wav");try{byte[] data=wave(1000);Files.write(f.toPath(),data);assertEquals(24000,PcmWaveFile.inspect(f).getLong("sampleFrames"));Files.write(f.toPath(),Arrays.copyOf(data,data.length-2));assertThrows(IllegalArgumentException.class,()->PcmWaveFile.inspect(f));data[32]=3;Files.write(f.toPath(),data);assertThrows(IllegalArgumentException.class,()->PcmWaveFile.inspect(f));}finally{f.delete();}}
    @Test public void manualDialogueFormUsesTheSameControlsAsMcpAndExportsWithoutJson()throws Exception {
        Context c=RuntimeEnvironment.getApplication();ProjectStore projects=new ProjectStore(c);ProjectStore.Project project=projects.create("Voice form");
        try(org.robolectric.android.controller.ActivityController<OpenPipelineActivity> controller=Robolectric.buildActivity(OpenPipelineActivity.class,new android.content.Intent(c,OpenPipelineActivity.class).putExtra("projectId",project.id)).setup()){
            OpenPipelineActivity activity=controller.get();java.lang.reflect.Field field=OpenPipelineActivity.class.getDeclaredField("backend");field.setAccessible(true);((Spinner)field.get(activity)).setSelection(4);Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
            field=OpenPipelineActivity.class.getDeclaredField("dialogueFields");field.setAccessible(true);DialogueControlsView form=(DialogueControlsView)field.get(activity);assertEquals(android.view.View.VISIBLE,form.getVisibility());form.language.setSelection(1);Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
            JSONObject values=form.controls();assertEquals("hi-IN",values.getString("language"));assertEquals("hf_alpha",values.getJSONArray("lines").getJSONObject(0).getString("voice"));assertEquals(values.toString(),OpenPipelineOperations.validateControls("kokoro",values).toString());
            JSONObject out=OpenPipelineOperations.execute(c,projects,"open_pipeline_export_request",new JSONObject().put("projectId",project.id).put("sourceProjectUpdatedAt",project.updatedAt).put("requestId","form_"+UUID.randomUUID()).put("backend","kokoro").put("controls",values).put("assetIds",new JSONArray()),null);assertEquals("exported",out.getString("state"));
        }
    }
    @Test public void rejectsChangedCastOverlapAndForeignSourceBeforeCreatingRequest()throws Exception {
        JSONObject c=controls();c.getJSONArray("lines").put(new JSONObject(c.getJSONArray("lines").getJSONObject(0).toString()).put("lineId","second").put("startMs",7000).put("voice","hf_alpha"));assertThrows(IllegalArgumentException.class,()->DialogueControls.validate(c));
        c.getJSONArray("lines").getJSONObject(1).put("voice","hm_omega").put("startMs",5000);assertThrows(IllegalArgumentException.class,()->DialogueControls.validate(c));
        Context context=RuntimeEnvironment.getApplication();ProjectStore projects=new ProjectStore(context);ProjectStore.Project p=projects.create("No voice references");JSONObject parameters=new JSONObject().put("projectId",p.id).put("sourceProjectUpdatedAt",p.updatedAt).put("requestId","foreign_"+UUID.randomUUID()).put("backend","kokoro").put("controls",controls()).put("assetIds",new JSONArray().put("unknown"));assertThrows(IllegalArgumentException.class,()->OpenPipelineOperations.execute(context,projects,"open_pipeline_export_request",parameters,null));
    }
    @Test public void importsTimedAudioIntoASeparateEditableProjectAndKeepsHumanEditsOnRetry()throws Exception {Fixture f=new Fixture(false);JSONObject receipt=f.importResult();assertTrue(receipt.getBoolean("imported"));ProjectStore.Project output=f.projects.get(receipt.getString("outputProjectId"));assertEquals(2,output.assets.size());assertEquals(1,output.studioLayers.size());StudioLayer lane=output.studioLayers.get(0);assertEquals(12000,lane.durationMs);assertEquals(StudioLayer.AUDIO,lane.type);assertEquals(f.sourceRevision,f.projects.get(f.source.id).updatedAt);lane.gain=.4f;f.projects.save(output);f.importResult();assertEquals(.4f,f.projects.get(output.id).studioLayers.get(0).gain,.001f);assertEquals(2,f.projects.get(output.id).assets.size());assertFalse(receipt.optBoolean("nativeRendered"));}
    @Test public void rejectsReturnedSpeechWhoseTextDoesNotMatchPendingDialogue()throws Exception {Fixture f=new Fixture(true);assertThrows(IllegalArgumentException.class,f::importResult);assertFalse(f.journal.get(f.id).optBoolean("importReady"));}
    @Test public void manualEditorReloadsAnAutonomousRequestWithoutLosingCastingOrTiming()throws Exception {
        Fixture f=new Fixture(false);try(org.robolectric.android.controller.ActivityController<OpenPipelineActivity> controller=Robolectric.buildActivity(OpenPipelineActivity.class,new android.content.Intent(f.context,OpenPipelineActivity.class).putExtra("projectId",f.source.id)).setup()){
            OpenPipelineActivity activity=controller.get();activity.loadDialogueRequest(f.journal.get(f.id));Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();java.lang.reflect.Field field=OpenPipelineActivity.class.getDeclaredField("dialogueFields");field.setAccessible(true);JSONObject received=((DialogueControlsView)field.get(activity)).controls();JSONObject expected=controls();assertEquals(expected.getString("language"),received.getString("language"));JSONObject a=expected.getJSONArray("lines").getJSONObject(0),b=received.getJSONArray("lines").getJSONObject(0);for(String key:new String[]{"lineId","characterId","voice","text","startMs","slotMs"})assertEquals(key,a.get(key),b.get(key));
        }
    }
    private static final class Fixture {
        final Context context=RuntimeEnvironment.getApplication();final ProjectStore projects=new ProjectStore(context);final ProjectStore.Project source=projects.create("Dialogue fixture");final OpenPipelineStore journal=new OpenPipelineStore(context);final String id="speech_"+UUID.randomUUID();final long sourceRevision=source.updatedAt;
        Fixture(boolean changeText)throws Exception {
            OpenPipelineOperations.execute(context,projects,"open_pipeline_export_request",new JSONObject().put("projectId",source.id).put("sourceProjectUpdatedAt",sourceRevision).put("requestId",id).put("backend","kokoro").put("controls",controls()).put("assetIds",new JSONArray()),null);JSONObject record=journal.get(id);byte[] master=wave(12000),stem=wave(1000);JSONObject line=controls().getJSONArray("lines").getJSONObject(0).put("path","line_00.wav");if(changeText)line.put("text","changed");byte[] timing=new JSONObject().put("schema","videostudio.dialogue.v1").put("language","hi-IN").put("durationMs",12000).put("cast",new JSONObject().put("kael","hm_omega")).put("lines",new JSONArray().put(line)).toString().getBytes(StandardCharsets.UTF_8);JSONArray files=new JSONArray();files.put(descriptor("dialogue.wav","audio","audio/wav",master)).put(descriptor("line_00.wav","dialogue_stem","audio/wav",stem)).put(descriptor("dialogue.json","dialogue_timing","application/json",timing));
            JSONObject manifest=new JSONObject().put("schema",OpenPipelineArtifacts.RESULT_SCHEMA).put("state","completed").put("jobId","speech_job").put("requestId",id).put("projectId",source.id).put("sourceProjectUpdatedAt",sourceRevision).put("requestSha256",record.getString("requestSha256")).put("files",files);File result=new File(journal.directory(id),id+".vsresult");try(ZipOutputStream z=new ZipOutputStream(new FileOutputStream(result))){OpenPipelineArtifacts.entry(z,"manifest.json",manifest.toString().getBytes(StandardCharsets.UTF_8));OpenPipelineArtifacts.entry(z,"outputs/dialogue.wav",master);OpenPipelineArtifacts.entry(z,"outputs/line_00.wav",stem);OpenPipelineArtifacts.entry(z,"outputs/dialogue.json",timing);}record.put("state","completed").put("jobId","speech_job").put("resultSha256",OpenPipelineArtifacts.hash(result)).put("resultBytes",result.length());journal.save(record);
        }
        JSONObject descriptor(String name,String role,String mime,byte[] bytes)throws Exception {return new JSONObject().put("path","outputs/"+name).put("role",role).put("mime",mime).put("bytes",bytes.length).put("sha256",OpenPipelineArtifacts.hash(bytes));}
        JSONObject importResult()throws Exception {return OpenPipelineOperations.execute(context,projects,"open_pipeline_import_result",new JSONObject().put("requestId",id),null);}
    }
}
