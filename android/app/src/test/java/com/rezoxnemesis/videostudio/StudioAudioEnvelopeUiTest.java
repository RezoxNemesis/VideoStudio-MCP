package com.rezoxnemesis.videostudio;

import android.app.AlertDialog;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.net.Uri;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.TextView;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.annotation.LooperMode;
import org.robolectric.shadows.ShadowAlertDialog;
import org.robolectric.shadows.ShadowLooper;
import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import static org.junit.Assert.*;

/** Ordinary manual admission and real PCM-WAV analysis; unit graph proves no codec/GPU output.
 * Uses only existing production symbols so root can execute genuine pre-feature RED.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=33,manifest=Config.NONE,qualifiers="w480dp-h853dp-port-mdpi",shadows=StudioEditorPreviewGraphFixture.BuilderShadow.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE) @LooperMode(LooperMode.Mode.PAUSED)
public class StudioAudioEnvelopeUiTest {
    @Test public void ordinaryTargetSelectionExposesReachableAnalysisForTheExactAudioLaneAndDraftCancelPreservesSource()throws Exception {
        try(Fixture f=new Fixture(false)) {
            String before=f.inventory();byte[] bytes=Files.readAllBytes(f.selectedFile.toPath());AlertDialog dialog=open(f);View form=dialog.getWindow().getDecorView();
            assertNotNull(textContaining(form,f.audio.name));assertNotNull(textContaining(form,f.audio.id));
            assertEquals("250",input(form,"Audio source start (ms)").getText().toString());
            assertEquals("1000",input(form,"Audio duration (ms)").getText().toString());
            input(form,"Audio source start (ms)").setText("500");input(form,"Audio duration (ms)").setText("750");input(form,"Audio buckets").setText("6");
            assertNotNull(description(form,"Audio envelope status"));assertNotNull(description(form,"Audio envelope progress"));
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE).performClick();ShadowLooper.idleMainLooper();assertFalse(dialog.isShowing());
            assertEquals(before,f.inventory());assertArrayEquals(bytes,Files.readAllBytes(f.selectedFile.toPath()));assertNull(org.robolectric.Shadows.shadowOf(f.activity).getNextStartedActivity());
        }
    }

    @Test public void manualAnalysisReturnsSelectedOwnedSourceIdentityAllChannelMetricsAndHonestCoverageWithoutSaving()throws Exception {
        try(Fixture f=new Fixture(false)) {
            String before=f.inventory(),active=f.store.active().id;byte[] bytes=Files.readAllBytes(f.selectedFile.toPath());AlertDialog dialog=open(f);View form=dialog.getWindow().getDecorView();
            input(form,"Audio source start (ms)").setText("250");input(form,"Audio duration (ms)").setText("1000");input(form,"Audio buckets").setText("4");
            awaitEnabled(dialog.getButton(AlertDialog.BUTTON_POSITIVE));dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();JSONObject result=awaitResult(form);
            assertTrue(result.toString(),result.getBoolean("ok"));assertTrue(result.getBoolean("readOnly"));assertTrue(result.getBoolean("sourceUnchanged"));
            assertEquals("videostudio.audio.envelope.v1",result.getString("schema"));assertEquals("audio_envelope",result.getString("mode"));
            assertEquals(f.project.id,result.getString("projectId"));assertEquals(f.project.updatedAt,result.getLong("sourceProjectUpdatedAt"));assertEquals(f.audio.id,result.getString("assetId"));assertEquals(f.hash,result.getString("sourceSha256"));
            assertEquals(250,result.getLong("sourceMs"));assertEquals(1000,result.getLong("durationMs"));assertEquals(4,result.getInt("bucketCount"));assertEquals(2,result.getInt("channels"));
            JSONObject coverage=result.getJSONObject("coverage");assertTrue(coverage.toString(),coverage.getBoolean("complete"));assertEquals(8000,coverage.getLong("decodedFrames"));assertEquals(0,coverage.getLong("missingFrames"));
            JSONArray buckets=result.getJSONArray("buckets");assertEquals(4,buckets.length());for(int i=0;i<4;i++) {
                JSONObject bucket=buckets.getJSONObject(i);assertEquals(250000L+i*250000L,bucket.getLong("sourceStartUs"));assertEquals(500000L+i*250000L,bucket.getLong("sourceEndUs"));
                assertEquals(2000,bucket.getLong("sampleFrames"));assertEquals(4000,bucket.getLong("channelSamples"));assertEquals(Math.sqrt(.125),bucket.getDouble("rms"),1e-8);assertEquals(.5,bucket.getDouble("samplePeak"),1e-8);assertTrue(bucket.getJSONObject("coverage").getBoolean("complete"));
            }
            assertNotNull("The Inspector must show readable coverage beside the exact result",textContaining(form,"Coverage: complete"));assertNotNull(textContaining(form,"Source time"));
            assertEquals(before,f.inventory());assertEquals(active,f.store.active().id);assertArrayEquals(bytes,Files.readAllBytes(f.selectedFile.toPath()));
        }
    }

    @Test public void cancelBeforePostedWorkerDeliveryInvalidatesTheResultAndClosedCallbacksCannotRestartAnalysis()throws Exception {
        try(Fixture f=new Fixture(false)) {
            String before=f.inventory();byte[] bytes=Files.readAllBytes(f.selectedFile.toPath());AlertDialog dialog=open(f);View form=dialog.getWindow().getDecorView();
            input(form,"Audio buckets").setText("4");Button analyze=dialog.getButton(AlertDialog.BUTTON_POSITIVE);awaitEnabled(analyze);analyze.performClick();
            assertFalse("Analysis must be asynchronous and visibly pending",analyze.isEnabled());View progress=description(form,"Audio envelope progress");assertNotNull(progress);assertEquals(View.VISIBLE,progress.getVisibility());
            Button cancel=button(form,"Cancel analysis");assertNotNull("Pending manual work needs its own cancellation control",cancel);assertTrue(cancel.isEnabled());cancel.performClick();
            ShadowLooper.idleMainLooper();assertNotNull(textContaining(form,"Cancelled"));TextView output=(TextView)description(form,"Audio envelope result JSON");assertTrue("Cancelled delivery must not become measured success",output==null||output.getText().toString().isEmpty());
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE).performClick();assertFalse(dialog.isShowing());analyze.performClick();ShadowLooper.idleMainLooper();assertFalse("A closed Inspector callback cannot reopen work",dialog.isShowing());
            assertEquals(before,f.inventory());assertArrayEquals(bytes,Files.readAllBytes(f.selectedFile.toPath()));
        }
    }

    @Test public void linkedAudioExplainsOwnedImportRequirementWithoutAnalyzingAnotherAssetOrOpeningGallery()throws Exception {
        try(Fixture f=new Fixture(true)) {
            String before=f.inventory();AlertDialog dialog=open(f);View form=dialog.getWindow().getDecorView();long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
            do {ShadowLooper.idleMainLooper();TextView status=(TextView)description(form,"Audio envelope status");if(status!=null&&status.getText().toString().toLowerCase(java.util.Locale.ROOT).contains("owned"))break;Thread.sleep(5);}while(System.nanoTime()<deadline);
            TextView status=(TextView)description(form,"Audio envelope status");assertNotNull(status);assertTrue(status.getText().toString(),status.getText().toString().toLowerCase(java.util.Locale.ROOT).contains("owned"));
            assertFalse("Linked content cannot silently enter hash-CAS audio analysis",dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled());
            TextView output=(TextView)description(form,"Audio envelope result JSON");assertTrue(output==null||output.getText().toString().isEmpty());assertEquals(before,f.inventory());assertNull(org.robolectric.Shadows.shadowOf(f.activity).getNextStartedActivity());
        }
    }

    private static AlertDialog open(Fixture f)throws Exception {
        Button choose=button(f.root,"Choose target");assertNotNull(choose);choose.performClick();ShadowLooper.idleMainLooper();AlertDialog chooser=ShadowAlertDialog.getLatestAlertDialog();ListView list=chooser.getListView();assertNotNull(list);boolean selected=false;
        for(int i=0;i<list.getCount();i++)if(("Audio · "+f.audio.name).equals(String.valueOf(list.getAdapter().getItem(i)))) {list.performItemClick(list.getAdapter().getView(i,null,list),i,i);selected=true;break;}
        assertTrue("Choose the exact second audio asset through ordinary native controls",selected);ShadowLooper.idleMainLooper();f.layout();assertNotNull(text(f.root,"Audio"));
        Button analyze=button(f.root,"Analyze envelope");assertNotNull("Selected AUDIO Inspector must expose Analyze envelope",analyze);assertTrue(analyze.isEnabled());Rect bounds=new Rect(0,0,analyze.getWidth(),analyze.getHeight());f.root.offsetDescendantRectToMyCoords(analyze,bounds);assertTrue("Audio analysis must be reachable in the workspace",new Rect(0,0,480,853).contains(bounds));assertTrue(analyze.getHeight()>=StudioUi.dp(f.activity,48));
        analyze.performClick();ShadowLooper.idleMainLooper();AlertDialog dialog=ShadowAlertDialog.getLatestAlertDialog();assertNotSame(chooser,dialog);assertEquals("Analyze audio envelope",org.robolectric.Shadows.shadowOf(dialog).getTitle().toString());assertTrue(dialog.isShowing());return dialog;
    }
    private static void awaitEnabled(Button button)throws Exception {long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);do{ShadowLooper.idleMainLooper();if(button.isEnabled())return;Thread.sleep(5);}while(System.nanoTime()<deadline);fail("Owned selected-source inspection did not finish");}
    private static JSONObject awaitResult(View form)throws Exception {long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);do{ShadowLooper.idleMainLooper();View output=description(form,"Audio envelope result JSON");if(output instanceof TextView&&!((TextView)output).getText().toString().isEmpty())return new JSONObject(((TextView)output).getText().toString());Thread.sleep(5);}while(System.nanoTime()<deadline);fail("Real PCM-WAV analysis did not deliver its shared native result");return null;}

    private static final class Fixture implements AutoCloseable {
        final MainActivity activity;final ProjectStore store;final ProjectStore.Project project;final ProjectStore.Asset audio;final ViewGroup root;final File imageFile,decoyFile,selectedFile;final String hash;
        Fixture(boolean linked)throws Exception {
            Context context=RuntimeEnvironment.getApplication();store=new ProjectStore(context);project=store.create("Audio envelope manual fixture");File directory=new File(context.getFilesDir(),"audio_envelope_ui_fixtures");assertTrue(directory.isDirectory()||directory.mkdirs());imageFile=new File(directory,UUID.randomUUID()+".png");
            Bitmap bitmap=Bitmap.createBitmap(32,64,Bitmap.Config.ARGB_8888);bitmap.eraseColor(0xff526e60);try(FileOutputStream out=new FileOutputStream(imageFile)){assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG,100,out));}finally{bitmap.recycle();}
            ProjectStore.Asset base=asset("Base technical image","image/png",Uri.fromFile(imageFile).toString(),0,imageFile.length());project.assets.add(base);ProjectStore.Clip clip=new ProjectStore.Clip();clip.id="base_"+UUID.randomUUID();clip.assetId=base.id;clip.outMs=6000;project.clips.add(clip);
            decoyFile=new File(directory,UUID.randomUUID()+".wav");short[] decoy=new short[24000];java.util.Arrays.fill(decoy,(short)4096);Files.write(decoyFile.toPath(),wav(decoy,1));ProjectStore.Asset first=asset("First audio is a decoy.wav","audio/wav",Uri.fromFile(decoyFile).toString(),3000,decoyFile.length());project.assets.add(first);
            selectedFile=new File(directory,UUID.randomUUID()+".wav");short[] stereo=new short[48000];for(int frame=0;frame<24000;frame++)stereo[frame*2+1]=16384;Files.write(selectedFile.toPath(),wav(stereo,2));hash=SketchProjectStore.fileHash(selectedFile);
            audio=asset("Exact selected right-only source.wav","audio/wav",linked?"content://manual-audio-fixture/linked-selected":Uri.fromFile(selectedFile).toString(),3000,selectedFile.length());project.assets.add(audio);
            StudioLayer firstLane=StudioLayer.fromJson(new JSONObject().put("id","decoy_lane_"+UUID.randomUUID()).put("type","audio").put("assetId",first.id).put("durationMs",1000));project.studioLayers.add(firstLane);
            StudioLayer selected=StudioLayer.fromJson(new JSONObject().put("id","selected_lane_"+UUID.randomUUID()).put("type","audio").put("assetId",audio.id).put("startMs",1200).put("sourceInMs",250).put("durationMs",1000));project.studioLayers.add(selected);store.save(project);
            activity=Robolectric.buildActivity(MainActivity.class).get();set(activity,"store",store);set(activity,"prefs",context.getSharedPreferences("audio-envelope-ui",0));set(activity,"activeProject",project);set(activity,"selectedClip",clip);set(activity,"renderEngine",new NativeRenderEngine(activity));set(activity,"studioWorkspacePane","Inspector");set(activity,"studioInspectorProjectId",project.id);set(activity,"studioSelectedLayerId",firstLane.id);set(activity,"studioPlayheadMs",1700L);
            root=(ViewGroup)invoke(activity,"buildShell",new Class<?>[]{});activity.setContentView(root);invoke(activity,"showEditor",new Class<?>[]{});ShadowLooper.idleMainLooper();layout();
        }
        String inventory()throws Exception {JSONArray all=new JSONArray();for(ProjectStore.Project value:store.list())all.put(value.toJson());return SketchDocument.canonical(all);}
        void layout(){root.measure(View.MeasureSpec.makeMeasureSpec(480,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(853,View.MeasureSpec.EXACTLY));root.layout(0,0,480,853);}
        public void close()throws Exception {AlertDialog dialog=ShadowAlertDialog.getLatestAlertDialog();if(dialog!=null)dialog.dismiss();invoke(activity,"onDestroy",new Class<?>[]{});store.close();assertTrue(imageFile.delete());assertTrue(decoyFile.delete());assertTrue(selectedFile.delete());}
        private static ProjectStore.Asset asset(String name,String mime,String uri,long duration,long bytes){ProjectStore.Asset value=new ProjectStore.Asset();value.id="asset_"+UUID.randomUUID();value.name=name;value.mime=mime;value.uri=uri;value.durationMs=duration;value.sizeBytes=bytes;value.seekable=true;return value;}
    }
    private static byte[] wav(short[] pcm,int channels){ByteBuffer data=ByteBuffer.allocate(44+pcm.length*2).order(ByteOrder.LITTLE_ENDIAN);data.put("RIFF".getBytes(StandardCharsets.US_ASCII)).putInt(data.capacity()-8).put("WAVEfmt ".getBytes(StandardCharsets.US_ASCII)).putInt(16).putShort((short)1).putShort((short)channels).putInt(8000).putInt(8000*channels*2).putShort((short)(channels*2)).putShort((short)16).put("data".getBytes(StandardCharsets.US_ASCII)).putInt(pcm.length*2);for(short value:pcm)data.putShort(value);return data.array();}
    private static Object invoke(Object object,String name,Class<?>[] types,Object... values)throws Exception {java.lang.reflect.Method method=MainActivity.class.getDeclaredMethod(name,types);method.setAccessible(true);return method.invoke(object,values);}
    private static void set(Object object,String name,Object value)throws Exception {Field field=MainActivity.class.getDeclaredField(name);field.setAccessible(true);field.set(object,value);}
    private static EditText input(View root,String value){View found=description(root,value);assertTrue("Missing manual audio analysis input: "+value,found instanceof EditText);return (EditText)found;}
    private static View description(View root,String value){if(value.contentEquals(root.getContentDescription()==null?"":root.getContentDescription()))return root;if(root instanceof ViewGroup)for(int i=0;i<((ViewGroup)root).getChildCount();i++){View found=description(((ViewGroup)root).getChildAt(i),value);if(found!=null)return found;}return null;}
    private static TextView text(View root,String value){if(root instanceof TextView&&value.contentEquals(((TextView)root).getText()))return (TextView)root;if(root instanceof ViewGroup)for(int i=0;i<((ViewGroup)root).getChildCount();i++){TextView found=text(((ViewGroup)root).getChildAt(i),value);if(found!=null)return found;}return null;}
    private static TextView textContaining(View root,String value){if(root instanceof TextView&&((TextView)root).getText().toString().contains(value))return (TextView)root;if(root instanceof ViewGroup)for(int i=0;i<((ViewGroup)root).getChildCount();i++){TextView found=textContaining(((ViewGroup)root).getChildAt(i),value);if(found!=null)return found;}return null;}
    private static Button button(View root,String value){TextView found=text(root,value);return found instanceof Button?(Button)found:null;}
}
