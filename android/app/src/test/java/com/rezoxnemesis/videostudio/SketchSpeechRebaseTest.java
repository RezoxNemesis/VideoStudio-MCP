package com.rezoxnemesis.videostudio;

import android.app.*;
import android.content.Context;
import android.view.*;
import android.widget.*;
import org.json.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.*;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.shadows.ShadowLooper;
import java.io.*;
import java.lang.reflect.*;
import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.*;
import java.util.zip.ZipOutputStream;
import static org.junit.Assert.*;

/** Imported real speech padded into a 120-second fixture master, not fresh synthesis. */
@RunWith(RobolectricTestRunner.class) @Config(sdk=33,manifest=Config.NONE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class SketchSpeechRebaseTest {
    private static final long START=65500,SHOT=65000;
    private static final class Fixture {
        final Context context=RuntimeEnvironment.getApplication();
        final ProjectStore projects=new ProjectStore(context);
        final OpenPipelineStore store=new OpenPipelineStore(context);
        final String id="speech_rebase_"+UUID.randomUUID();
        final JSONObject raw,record;final byte[] master;
        Fixture()throws Exception {
            raw=new JSONObject(new String(SketchSpeechStudyTest.resource("english-dialogue.json"),StandardCharsets.UTF_8));
            raw.put("durationMs",120000);JSONObject line=raw.getJSONArray("lines").getJSONObject(0);line.put("startMs",START);
            JSONObject authored=new JSONObject();for(String key:new String[]{"lineId","characterId","voice","text","startMs","slotMs","speed"})authored.put(key,line.get(key));
            ProjectStore.Project source=projects.create("Long master rebase fixture");
            OpenPipelineOperations.execute(context,projects,"open_pipeline_export_request",new JSONObject().put("projectId",source.id).put("sourceProjectUpdatedAt",source.updatedAt)
                    .put("requestId",id).put("backend","kokoro").put("assetIds",new JSONArray()).put("controls",new JSONObject().put("language","en-US").put("durationMs",120000).put("lines",new JSONArray().put(authored))),null);
            record=store.get(id);byte[] stem=SketchSpeechStudyTest.resource("english-stem.wav");
            File stemFile=new File(store.directory(id),"fixture-stem.wav");Files.write(stemFile.toPath(),stem);PcmWaveFile.Layout layout;
            try(FileInputStream input=new FileInputStream(stemFile)){layout=PcmWaveFile.readPcm16(input.getChannel(),0,stemFile.length());}
            int dataBytes=120000*24*2;master=new byte[44+dataBytes];ByteBuffer header=ByteBuffer.wrap(master).order(ByteOrder.LITTLE_ENDIAN);
            header.put("RIFF".getBytes(StandardCharsets.US_ASCII)).putInt(master.length-8).put("WAVEfmt ".getBytes(StandardCharsets.US_ASCII)).putInt(16)
                    .putShort((short)1).putShort((short)1).putInt(24000).putInt(48000).putShort((short)2).putShort((short)16)
                    .put("data".getBytes(StandardCharsets.US_ASCII)).putInt(dataBytes);
            System.arraycopy(stem,(int)layout.dataOffset,master,44+(int)START*48,(int)layout.sampleFrames*2);
            byte[] timing=raw.toString().getBytes(StandardCharsets.UTF_8);
            JSONArray files=new JSONArray().put(descriptor("dialogue.wav","audio","audio/wav",master)).put(descriptor("line_00.wav","dialogue_stem","audio/wav",stem))
                    .put(descriptor("dialogue.json","dialogue_timing","application/json",timing));
            JSONObject manifest=new JSONObject().put("schema",OpenPipelineArtifacts.RESULT_SCHEMA).put("state","completed").put("jobId","rebase_fixture").put("requestId",id)
                    .put("projectId",source.id).put("sourceProjectUpdatedAt",source.updatedAt).put("requestSha256",record.get("requestSha256")).put("files",files);
            File result=new File(store.directory(id),id+".vsresult");try(ZipOutputStream zip=new ZipOutputStream(new FileOutputStream(result))){
                OpenPipelineArtifacts.entry(zip,"manifest.json",manifest.toString().getBytes(StandardCharsets.UTF_8));
                OpenPipelineArtifacts.entry(zip,"outputs/dialogue.wav",master);OpenPipelineArtifacts.entry(zip,"outputs/line_00.wav",stem);OpenPipelineArtifacts.entry(zip,"outputs/dialogue.json",timing);
            }
            record.put("state","completed").put("jobId","rebase_fixture").put("resultSha256",OpenPipelineArtifacts.hash(result)).put("resultBytes",result.length());store.save(record);
            OpenPipelineOperations.execute(context,projects,"open_pipeline_import_result",new JSONObject().put("requestId",id),null);
        }
        JSONObject descriptor(String name,String role,String mime,byte[] bytes)throws Exception{return new JSONObject().put("path","outputs/"+name).put("role",role).put("mime",mime).put("bytes",bytes.length).put("sha256",OpenPipelineArtifacts.hash(bytes));}
    }
    private static JSONObject load(Context context,String id,double duration,long shot)throws Exception {
        try{return (JSONObject)SketchSpeechAssets.class.getDeclaredMethod("load",Context.class,String.class,int.class,double.class,long.class).invoke(null,context,id,0,duration,shot);}
        catch(InvocationTargetException error){throw (Exception)error.getCause();}
    }
    @Test public void laterLineInUnchangedLongMasterRebasesWithoutClippingOrTimewarp()throws Exception {
        Fixture f=new Fixture();String original=f.raw.toString();JSONObject loaded=load(f.context,f.id,6000,SHOT);
        JSONObject expectedLine=SketchDocument.copy(f.raw.getJSONArray("lines").getJSONObject(0));
        JSONObject sourceLine=SketchDocument.copy(loaded.getJSONObject("sourceLineTiming"));
        assertEquals(SHOT,loaded.getLong("audioTimelineOffsetMs"));assertEquals(SketchDocument.canonical(expectedLine),SketchDocument.canonical(sourceLine));
        JSONArray cues=loaded.getJSONObject("speech").getJSONArray("cues");assertEquals(500,cues.getJSONObject(0).getDouble("startMs"),0);
        assertEquals(2625,cues.getJSONObject(cues.length()-1).getDouble("endMs"),0);assertEquals(original,f.raw.toString());
        assertEquals(OpenPipelineArtifacts.hash(f.master),loaded.getString("audioSha256"));
        ProjectStore.Asset audio=f.projects.get(loaded.getString("projectId")).asset(loaded.getString("assetId"));assertEquals(OpenPipelineArtifacts.hash(f.master),OpenPipelineArtifacts.hash(new File(android.net.Uri.parse(audio.uri).getPath())));
        JSONObject atBoundary=load(f.context,f.id,2125,START);assertEquals(0,atBoundary.getJSONObject("speech").getJSONArray("cues").getJSONObject(0).getDouble("startMs"),0);
        assertThrows(Exception.class,()->load(f.context,f.id,2124,START));assertThrows(Exception.class,()->load(f.context,f.id,6000,START+1));
        assertThrows(Exception.class,()->load(f.context,f.id,6000,-1));assertThrows(Exception.class,()->load(f.context,f.id,6000,600001));
        assertThrows(Exception.class,()->SketchSpeechAssets.load(f.context,f.id,0,6000));
    }
    @Test public void legacyZeroOffsetAndBothGeneratedOperationRoutesPreserveExactTiming()throws Exception {
        SketchSpeechAssetsTest.Fixture legacy=new SketchSpeechAssetsTest.Fixture("english");
        JSONObject before=SketchSpeechAssets.load(legacy.context,legacy.id,0,6000),zero=load(legacy.context,legacy.id,6000,0);
        assertEquals(SketchDocument.canonical(before.getJSONObject("speech")),SketchDocument.canonical(zero.getJSONObject("speech")));assertEquals(0,zero.getLong("audioTimelineOffsetMs"));
        Fixture f=new Fixture();
        for(boolean face:new boolean[]{false,true}){
            JSONObject doc=SketchSpeechAnimationTest.doc(30),op=SketchSpeechAnimationTest.op(face,"front",new JSONObject().put("generated",new JSONObject().put("requestId",f.id).put("lineIndex",0).put("shotStartMs",SHOT)));
            String original=op.toString();JSONObject expanded=SketchSpeechAssets.expand(f.context,doc,op);assertEquals(original,op.toString());
            assertEquals(500,expanded.getJSONObject("speech").getJSONArray("cues").getJSONObject(0).getDouble("startMs"),0);
            for(Object bad:new Object[]{-1,600001,1.5,"65000",JSONObject.NULL}){
                JSONObject invalid=SketchDocument.copy(op);invalid.getJSONObject("speech").getJSONObject("generated").put("shotStartMs",bad);
                String unchanged=doc.toString();assertThrows(Exception.class,()->SketchSpeechAssets.expand(f.context,doc,invalid));assertEquals(unchanged,doc.toString());
            }
        }
    }
    @Test public void rebasingRetainsImportedMasterHashAndProjectOwnershipChecks()throws Exception {
        Fixture f=new Fixture();JSONObject loaded=load(f.context,f.id,6000,SHOT);ProjectStore.Project owner=f.projects.get(loaded.getString("projectId"));ProjectStore.Asset audio=owner.asset(loaded.getString("assetId"));
        File master=new File(android.net.Uri.parse(audio.uri).getPath());byte[] changed=f.master.clone();changed[100]^=1;Files.write(master.toPath(),changed);
        assertThrows(Exception.class,()->load(f.context,f.id,6000,SHOT));Files.write(master.toPath(),f.master);
        owner.assets.removeIf(asset->asset.id.equals(audio.id));f.projects.save(owner);assertThrows(Exception.class,()->load(f.context,f.id,6000,SHOT));
    }
    @Test public void bothManualDialogsLoadTheExplicitShotOffsetAndDisplayMasterPlacement()throws Exception {
        for(Class<?> type:new Class<?>[]{SketchSpeechDialog.class,SketchCharacterDialog.class}){
            SketchSpeechAssetsTest.Fixture f=new SketchSpeechAssetsTest.Fixture("english");
            try(ActivityController<Activity> activity=Robolectric.buildActivity(Activity.class).setup()){
                Constructor<?> constructor=type.getDeclaredConstructor(Activity.class,JSONObject.class,SketchConstructionSubmit.class);constructor.setAccessible(true);
                Object owner=constructor.newInstance(activity.get(),SketchSpeechAnimationTest.doc(30),(SketchConstructionSubmit)(request,done)->done.accept(null));
                Method show=type.getDeclaredMethod("show");show.setAccessible(true);show.invoke(owner);AlertDialog editor=(AlertDialog)SketchSolidUiTest.field(owner,"dialog");View form=editor.getWindow().getDecorView();
                EditText offset=(EditText)find(form,"Shot start in master (ms)");assertNotNull(offset);assertEquals("0",offset.getText().toString());offset.setText("500");
                Method load=type.getDeclaredMethod("loadGenerated");load.setAccessible(true);load.invoke(owner);flush(owner);
                AlertDialog picker=(AlertDialog)SketchSolidUiTest.field(owner,"sourcePicker");Shadows.shadowOf(picker).clickOnItem(0);ShadowLooper.idleMainLooper();flush(owner);
                assertEquals("500",((EditText)find(form,"Cue start (ms)")).getText().toString());
                assertTrue(((TextView)SketchSolidUiTest.field(owner,"status")).getText().toString().contains("500 ms"));
                editor.dismiss();
            }
        }
    }
    private static void flush(Object owner)throws Exception {((ExecutorService)SketchSolidUiTest.field(owner,"worker")).submit(()->{}).get(30,TimeUnit.SECONDS);ShadowLooper.idleMainLooper();}
    private static View find(View root,String label){if(label.equals(String.valueOf(root.getContentDescription())))return root;if(root instanceof ViewGroup){ViewGroup group=(ViewGroup)root;for(int i=0;i<group.getChildCount();i++){View found=find(group.getChildAt(i),label);if(found!=null)return found;}}return null;}
}
