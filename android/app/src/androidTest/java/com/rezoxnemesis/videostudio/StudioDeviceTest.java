package com.rezoxnemesis.videostudio;

import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Rect;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Spinner;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.uiautomator.By;
import androidx.test.uiautomator.UiDevice;
import androidx.test.uiautomator.UiObject2;
import androidx.test.uiautomator.Until;
import org.json.JSONObject;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.Assert.*;

/** Real decoder, GPU, UI and service tests; outputs are retained as CI evidence. */
@RunWith(AndroidJUnit4.class)
public class StudioDeviceTest {
    private Context context;
    private File evidence;
    private UiDevice device;

    @org.junit.Rule public final org.junit.rules.TestWatcher failureEvidence=new org.junit.rules.TestWatcher(){
        @Override protected void failed(Throwable failure,org.junit.runner.Description description){
            if(device==null||evidence==null)return;
            try{device.takeScreenshot(new File(evidence,"failure-"+description.getMethodName()+".png"));device.dumpWindowHierarchy(new File(evidence,"failure-"+description.getMethodName()+".xml"));}catch(Exception ignored){}
        }
    };

    @Before public void setup() {
        context=InstrumentationRegistry.getInstrumentation().getTargetContext();
        evidence=new File(context.getFilesDir(),"evidence");assertTrue(evidence.isDirectory()||evidence.mkdirs());
        device=UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());
    }

    @Test public void ownerImagePreviewSplitUndoAndForegroundExport() throws Exception {
        ProjectStore store=new ProjectStore(context);
        ProjectStore.Project project=store.create("Device editor proof");
        ProjectStore.Asset image=asset("cyan",png("cyan.png",Color.CYAN),"image/png",0);
        project.assets.add(image);project.clips.add(clip("photo",image.id,"video-1",0,3000));store.save(project);
        try(ActivityScenario<MainActivity> scenario=ActivityScenario.launch(new Intent(context,MainActivity.class))) {
            UiObject2 editor=device.wait(Until.findObject(By.text("Editor")),15000);assertNotNull("Editor navigation",editor);editor.click();
            UiObject2 monitor=device.wait(Until.findObject(By.desc("VideoStudio preview monitor")),15000);assertNotNull(monitor);
            Rect bounds=monitor.getVisibleBounds();assertPreviewColor(bounds,Color.CYAN);
            assertNull("Image preview requires no render session",new ExportSessionStore(context).latest(project.id));
            device.takeScreenshot(new File(evidence,"01-source-image.png"));
            scenario.onActivity(activity->{
                StudioTimelineView timeline=(StudioTimelineView)findClass(activity.getWindow().getDecorView(),StudioTimelineView.class);
                assertNotNull(timeline);float density=activity.getResources().getDisplayMetrics().density;
                long now=SystemClock.uptimeMillis();float x=timeline.headerWidth()+62*density;
                MotionEvent down=MotionEvent.obtain(now,now,MotionEvent.ACTION_DOWN,x,10*density,0);
                MotionEvent up=MotionEvent.obtain(now,now+10,MotionEvent.ACTION_UP,x,10*density,0);
                timeline.dispatchTouchEvent(down);timeline.dispatchTouchEvent(up);down.recycle();up.recycle();
                click(activity,"Split");
            });
            assertEquals(2,store.get(project.id).clips.size());
            scenario.onActivity(a->click(a,"Undo"));assertEquals(1,store.get(project.id).clips.size());
            scenario.onActivity(a->click(a,"Redo"));assertEquals(2,store.get(project.id).clips.size());
            scenario.recreate();
            device.wait(Until.findObject(By.text("Editor")),15000).click();
            assertNotNull(device.wait(Until.findObject(By.desc("VideoStudio preview monitor")),15000));
            assertEquals(2,new ProjectStore(context).get(project.id).clips.size());
            device.takeScreenshot(new File(evidence,"02-split-persisted.png"));
            scenario.onActivity(a->{
                click(a,"Export");ArrayList<Spinner> choices=new ArrayList<>();spinners(a.getWindow().getDecorView(),choices);
                assertEquals(5,choices.size());choices.get(1).setSelection(1);choices.get(4).setSelection(1);click(a,"Start Export");
            });
            ExportSessionStore sessions=new ExportSessionStore(context);ExportSessionStore.Session session=sessions.latest(project.id);
            assertNotNull("Export is an immediate durable session",session);assertNotEquals("queued",session.state);
            device.takeScreenshot(new File(evidence,"03-foreground-export.png"));
            long deadline=SystemClock.elapsedRealtime()+150000;
            while(!session.terminal()&&SystemClock.elapsedRealtime()<deadline){SystemClock.sleep(250);session=sessions.get(session.id);}
            assertEquals(session.detail,"completed",session.state);assertTrue(session.verified);
            JSONObject proof=PlayableMediaVerifier.verify(context,Uri.parse(session.uri),true);
            assertTrue(proof.getBoolean("decodedFrame"));assertTrue(proof.getLong("durationMs")>=2800);
            copy(Uri.parse(session.uri),new File(evidence,"owner-image-export.mp4"));
            write("owner-export-proof.json",session.json().toString(2));
            device.takeScreenshot(new File(evidence,"04-export-complete.png"));
        }
    }

    @Test public void vaultUsesAndroidKeystoreAndRetainsOriginal() throws Exception {
        ProjectStore store=new ProjectStore(context);ProjectStore.Project project=store.create("Vault device proof");File source=png("vault-source.png",Color.GREEN);
        ProjectStore.Asset image=asset("vault-source",source,"image/png",0);image.sizeBytes=source.length();project.assets.add(image);store.save(project);
        VaultManager vault=new VaultManager(context,store);JSONObject response=vault.create(project.id,image.id,true,null),manifest=response.getJSONObject("vault");assertTrue(manifest.getBoolean("encrypted"));assertTrue(source.isFile());
        byte[] expected=java.nio.file.Files.readAllBytes(source.toPath());try(InputStream in=vault.openRange(manifest.getString("manifestId"),0,expected.length)){byte[] actual=new byte[expected.length];int offset=0,n;while(offset<actual.length&&(n=in.read(actual,offset,actual.length-offset))!=-1)offset+=n;assertArrayEquals(expected,actual);assertEquals(-1,in.read());}
        assertNotNull(new ProjectStore(context).get(project.id).asset(image.id).generationMetadata.optJSONObject("vault"));write("vault-proof.json",manifest.toString(2));
    }

    @Test public void autonomousServiceEditsReplaysAndExportsWhileOwnerEditorIsClosed()throws Exception{
        ProjectStore store=new ProjectStore(context);ProjectStore.Project project=store.create("Autonomous checkpoint proof");File source=png("autonomous-original.png",Color.YELLOW);byte[] original=java.nio.file.Files.readAllBytes(source.toPath());
        project.assets.add(asset("autonomous-image",source,"image/png",0));project.clips.add(clip("autonomous-clip","autonomous-image",project.tracks.get(0).id,0,1000));store.save(project);
        CommandJournal journal=new CommandJournal(context);String id="device-agent-"+java.util.UUID.randomUUID();JSONObject edit=new JSONObject().put("id",id).put("action","editor_operation").put("parameters",new JSONObject().put("projectId",project.id).put("expectedRevision",project.revision).put("commandId",id).put("operation","set_title").put("args",new JSONObject().put("clipId","autonomous-clip").put("text","AUTONOMOUS")));
        try(ActivityScenario<MainActivity> scenario=ActivityScenario.launch(new Intent(context,MainActivity.class))){
            journal.begin(edit);context.startForegroundService(new Intent(context,ControlService.class).setAction(ControlService.ACTION_REMOTE_COMMAND).putExtra("commandId",id).putExtra("projectId",project.id));
            scenario.moveToState(androidx.lifecycle.Lifecycle.State.CREATED);JSONObject edited=awaitCommand(journal,id,30_000);assertEquals(edited.toString(),"completed",edited.getString("status"));assertEquals("AUTONOMOUS",store.get(project.id).clips.get(0).title);long revision=store.get(project.id).revision;
            context.startService(new Intent(context,ControlService.class).setAction(ControlService.ACTION_REMOTE_COMMAND).putExtra("commandId",id).putExtra("projectId",project.id));SystemClock.sleep(500);assertEquals("Retry cannot edit twice",revision,store.get(project.id).revision);
            String exportId="device-export-"+java.util.UUID.randomUUID();JSONObject export=new JSONObject().put("id",exportId).put("action","autonomous_edit").put("parameters",new JSONObject().put("projectId",project.id).put("expectedRevision",revision).put("render",true).put("aspect","16:9").put("quality","720p").put("fileName","DeviceAutonomousPreview.mp4"));journal.begin(export);
            context.startService(new Intent(context,ControlService.class).setAction(ControlService.ACTION_REMOTE_COMMAND).putExtra("commandId",exportId).putExtra("projectId",project.id));JSONObject completed=awaitCommand(journal,exportId,180_000);assertEquals(completed.toString(),"completed",completed.getString("status"));JSONObject result=completed.getJSONObject("result");assertTrue(result.toString(),result.getBoolean("verifiedPlayableOutput"));JSONObject actual=PlayableMediaVerifier.verify(context,Uri.parse(result.getString("outputUri")),true);assertEquals(result.getJSONObject("verification").getString("sha256"),actual.getString("sha256"));copy(Uri.parse(result.getString("outputUri")),new File(evidence,"autonomous-service-export.mp4"));
            assertEquals("Export retains the edited timeline",1,store.get(project.id).clips.size());assertEquals("AUTONOMOUS",store.get(project.id).clips.get(0).title);assertArrayEquals(original,java.nio.file.Files.readAllBytes(source.toPath()));
            write("autonomous-service-proof.json",new JSONObject().put("serviceCommandDispatch",true).put("ownerEditorClosed",true).put("retryDidNotDuplicateEdit",true).put("originalRetained",true).put("publishedOutputVerified",true).put("verification",actual).put("externalChatGptTransportVerified",false).toString(2));
        }
    }

    private JSONObject awaitCommand(CommandJournal journal,String id,long timeout)throws Exception{
        long end=SystemClock.elapsedRealtime()+timeout;JSONObject terminal;
        while((terminal=journal.terminal(id))==null&&SystemClock.elapsedRealtime()<end)SystemClock.sleep(100);
        assertNotNull("Service command timed out: "+id,terminal);return terminal;
    }

    @Test public void ownerSelectedDocumentFolderReplicatesEncryptedVaultAndReusesVerifiedObjects()throws Exception{
        String folder="VideoStudio_SAF_Replication_Test";
        device.executeShellCommand("mkdir -p /sdcard/Documents/"+folder);
        ProjectStore store=new ProjectStore(context);ProjectStore.Project project=store.create("SAF replica device proof");
        File source=png("saf-vault-original.png",Color.MAGENTA);ProjectStore.Asset image=asset("saf-vault",source,"image/png",0);image.sizeBytes=source.length();project.assets.add(image);project.clips.add(clip("saf-owned-clip",image.id,project.tracks.get(0).id,0,3000));store.save(project);
        VaultManager manager=new VaultManager(context,store);String manifest=manager.create(project.id,image.id,true,null).getJSONObject("vault").getString("manifestId");
        try(ActivityScenario<MainActivity> scenario=ActivityScenario.launch(new Intent(context,MainActivity.class))){
            scenario.onActivity(activity->{
                try{
                    java.lang.reflect.Field request=MainActivity.class.getDeclaredField("PICK_STORAGE_PROFILE");request.setAccessible(true);
                    Intent picker=new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_WRITE_URI_PERMISSION|Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION|Intent.FLAG_GRANT_PREFIX_URI_PERMISSION)
                        .putExtra(android.provider.DocumentsContract.EXTRA_INITIAL_URI,android.provider.DocumentsContract.buildDocumentUri("com.android.externalstorage.documents","primary:Documents/"+folder));
                    activity.startActivityForResult(picker,request.getInt(null));
                }catch(Exception failure){throw new RuntimeException(failure);}
            });
            UiObject2 select=device.wait(Until.findObject(By.text(java.util.regex.Pattern.compile("(?i)use this folder"))),20000);assertNotNull("System folder-picker confirmation",select);select.click();
            UiObject2 allow=device.wait(Until.findObject(By.text(java.util.regex.Pattern.compile("(?i)allow"))),10000);assertNotNull("Owner folder capability grant",allow);allow.click();
            UiObject2 connect=device.wait(Until.findObject(By.text(java.util.regex.Pattern.compile("(?i)connect"))),10000);assertNotNull("Owner storage connection naming dialog",connect);connect.click();
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            String profile=null;long deadline=SystemClock.elapsedRealtime()+10000;
            try(StorageProfileStore profiles=new StorageProfileStore(context)){
                while(profile==null&&SystemClock.elapsedRealtime()<deadline){org.json.JSONArray entries=profiles.list();for(int i=0;i<entries.length();i++){JSONObject item=entries.getJSONObject(i);if(item.optString("treeUri").contains(folder))profile=item.getString("id");}if(profile==null)SystemClock.sleep(100);}
                assertNotNull("Actual persisted system-picker profile",profile);
                JSONObject replicated=manager.replicate(project.id,image.id,manifest,java.util.Collections.singletonList(profile),1,null);
                assertTrue(replicated.getBoolean("complete"));assertEquals(1,replicated.getInt("chunkReplicas"));assertEquals(1,replicated.getInt("manifestReplicas"));
                long uploaded=profiles.get(profile).getLong("uploadedBytes");assertTrue(uploaded>0);assertTrue(profiles.get(profile).getLong("downloadedBytes")>0);
                JSONObject replay=new VaultManager(context,new ProjectStore(context)).replicate(project.id,image.id,manifest,java.util.Collections.singletonList(profile),1,null);
                assertEquals("Verified restart reuses stored remote objects",uploaded,profiles.get(profile).getLong("uploadedBytes"));
                org.json.JSONArray before=replicated.getJSONArray("locations"),after=replay.getJSONArray("locations");assertEquals(before.length(),after.length());for(int i=0;i<before.length();i++)assertEquals(before.getJSONObject(i).getString("location"),after.getJSONObject(i).getString("location"));
                assertTrue(source.isFile());assertTrue(new ProjectStore(context).get(project.id).asset(image.id).generationMetadata.getJSONObject("vaultReplication").getBoolean("complete"));
                write("saf-vault-replication-proof.json",replay.toString(2));
                File localRoot=new File(context.getFilesDir(),"videostudio_vault");VaultChunkStore.Manifest originalManifest=new VaultChunkStore(localRoot,VaultChunkStore.DEFAULT_CHUNK_BYTES).load(manifest);
                for(VaultChunkStore.Chunk chunk:originalManifest.chunks)assertTrue("Remove only this fixture's local cached object",new File(localRoot,"objects/"+chunk.objectName).delete());assertTrue(new File(localRoot,"manifests/"+manifest+".manifest").delete());
                JSONObject recovery=manager.restore(project.id,image.id,manifest,image.uri,null);assertTrue(recovery.getBoolean("complete"));assertEquals(image.uri,new ProjectStore(context).get(project.id).asset(image.id).generationMetadata.getJSONObject("vaultRecovery").getString("originalUri"));
                byte[] originalBytes=java.nio.file.Files.readAllBytes(source.toPath());try(InputStream restored=context.getContentResolver().openInputStream(Uri.parse(recovery.getString("uri")))){java.io.ByteArrayOutputStream bytes=new java.io.ByteArrayOutputStream();byte[] buffer=new byte[4096];int count;while((count=restored.read(buffer))!=-1)bytes.write(buffer,0,count);assertArrayEquals(originalBytes,bytes.toByteArray());}
                Bitmap recoveredImage=android.graphics.BitmapFactory.decodeFile(Uri.parse(recovery.getString("uri")).getPath());assertNotNull("Recovered media is independently decodable",recoveredImage);assertEquals(Color.MAGENTA,recoveredImage.getPixel(recoveredImage.getWidth()/2,recoveredImage.getHeight()/2));recoveredImage.recycle();
                assertTrue(source.isFile());write("saf-vault-recovery-proof.json",recovery.toString(2));
            }
            scenario.onActivity(activity->{try{java.lang.reflect.Method editor=MainActivity.class.getDeclaredMethod("showEditor");editor.setAccessible(true);editor.invoke(activity);}catch(Exception failure){throw new RuntimeException(failure);}});
            UiObject2 recoveredPreview=device.wait(Until.findObject(By.desc("VideoStudio preview monitor")),15000);assertNotNull(recoveredPreview);assertPreviewColor(recoveredPreview.getVisibleBounds(),Color.MAGENTA);
            device.takeScreenshot(new File(evidence,"07-saf-vault-replication.png"));
        }
    }

    @Test public void mixedVideoImageGapAndAudioRenderPreservesTimeline() throws Exception {
        ProjectStore.Project source=new ProjectStore.Project();source.id="fixture";source.name="Red fixture";
        source.assets.add(asset("red",png("red.png",Color.RED),"image/png",0));source.clips.add(clip("red-clip","red","video-1",0,1000));
        File video=new File(evidence,"red-source.mp4");render(source,video);
        ProjectStore.Project p=new ProjectStore.Project();p.id="mixed";p.name="Mixed timeline proof";
        ProjectStore.Track visual=new ProjectStore.Track();visual.id="video-1";visual.type="video";p.tracks.add(visual);
        p.assets.add(asset("video",video,"video/mp4",1000));p.assets.add(asset("blue",png("blue.png",Color.BLUE),"image/png",0));
        p.assets.add(asset("tone",wav("tone.wav",4),"audio/wav",4000));
        p.clips.add(clip("first","video","video-1",0,1000));p.clips.add(clip("second","blue","video-1",2000,2000));
        ProjectStore.Track audio=new ProjectStore.Track();audio.id="music";audio.type="audio_music";audio.name="Music";audio.order=1;p.tracks.add(audio);
        ProjectStore.Clip sound=clip("audio","tone","music",0,4000);sound.volume=.4f;p.clips.add(sound);
        File output=new File(evidence,"mixed-gap-audio.mp4");render(p,output);
        JSONObject proof=PlayableMediaVerifier.verify(context,Uri.fromFile(output),true);
        assertTrue("Source audio must survive mixed image/video export",proof.getBoolean("hasAudio"));
        assertEquals(4000,proof.getLong("durationMs"),180);
        assertFrame(output,250000,Color.RED);assertFrame(output,1500000,Color.BLACK);assertFrame(output,3000000,Color.BLUE);
        write("mixed-render-proof.json",proof.toString(2));
    }

    @Test public void alphaKeyframesChangeExportedPixels() throws Exception {
        ProjectStore store=new ProjectStore(context);ProjectStore.Project project=store.create("Alpha pixel proof");
        File image=png("alpha-source.png",Color.RED);project.assets.add(asset("alpha-image",image,"image/png",0));
        project.assets.add(asset("lead-image",png("alpha-lead.png",Color.BLUE),"image/png",0));
        project.clips.add(clip("lead-clip","lead-image",project.tracks.get(0).id,0,1000));
        ProjectStore.Clip layer=clip("alpha-clip","alpha-image",project.tracks.get(0).id,1000,2500);
        layer.keyframes.put(new JSONObject().put("property","opacity").put("timeMs",0).put("value",0).put("easing","hold"));
        layer.keyframes.put(new JSONObject().put("property","opacity").put("timeMs",1200).put("value",1).put("easing","hold"));
        project.clips.add(layer);store.save(project);File output=new File(evidence,"alpha-keyframe-export.mp4");render(project,output);
        assertFrame(output,200000,Color.BLUE);assertFrame(output,1200000,Color.BLACK);assertFrame(output,2900000,Color.RED);
        write("alpha-render-proof.json",PlayableMediaVerifier.verify(context,Uri.fromFile(output),true).toString(2));
    }

    @Test public void chromaKeyAndEllipseMaskChangeActualExportPixels() throws Exception {
        ProjectStore store=new ProjectStore(context);ProjectStore.Project project=store.create("Composite pixel proof");
        File keyed=new File(evidence,"key-source.png");Bitmap image=Bitmap.createBitmap(640,360,Bitmap.Config.ARGB_8888);image.eraseColor(Color.GREEN);
        android.graphics.Paint paint=new android.graphics.Paint();paint.setColor(Color.RED);new android.graphics.Canvas(image).drawRect(220,120,420,240,paint);
        try(OutputStream out=new FileOutputStream(keyed)){assertTrue(image.compress(Bitmap.CompressFormat.PNG,100,out));}finally{image.recycle();}
        project.assets.add(asset("key",keyed,"image/png",0));project.assets.add(asset("mask",png("mask-source.png",Color.RED),"image/png",0));
        String track=project.tracks.get(0).id;ProjectStore.Clip key=clip("key-clip","key",track,0,2000);key.effects.put("chromaKey",true).put("chromaColor","#00FF00");
        ProjectStore.Clip mask=clip("mask-clip","mask",track,2000,2000);mask.effects.put("mask","ellipse").put("maskWidth",.5).put("maskHeight",.5).put("maskFeather",0);
        project.clips.add(key);project.clips.add(mask);store.save(project);File output=new File(evidence,"key-mask-export.mp4");render(project,output);
        assertFrame(output,1000000,Color.RED);assertFramePixel(output,1000000,.1,.1,Color.BLACK);
        assertFrame(output,3000000,Color.RED);assertFramePixel(output,3000000,.1,.1,Color.BLACK);
        write("key-mask-proof.json",PlayableMediaVerifier.verify(context,Uri.fromFile(output),true).toString(2));
    }

    @Test public void smallVideoProxyRetainsAudioAndOriginalExport() throws Exception {
        ProjectStore store=new ProjectStore(context);ProjectStore.Project fixture=store.create("Proxy source fixture");String visual=fixture.tracks.get(0).id;
        fixture.assets.add(asset("red",png("proxy-red.png",Color.RED),"image/png",0));fixture.assets.add(asset("tone",wav("proxy-tone.wav",1),"audio/wav",1000));fixture.clips.add(clip("red-clip","red",visual,0,1000));
        ProjectStore.Track track=new ProjectStore.Track();track.id="proxy-fixture-audio";track.type="audio_music";track.order=1;fixture.tracks.add(track);fixture.clips.add(clip("tone-clip","tone",track.id,0,1000));store.save(fixture);
        File sourceFile=new File(evidence,"proxy-original.mp4");render(fixture,sourceFile);String originalHash=PlayableMediaVerifier.verify(context,Uri.fromFile(sourceFile),true).getString("sha256");
        ProjectStore.Project project=store.create("Proxy owner proof");ProjectStore.Asset source=asset("proxy-owned-video",sourceFile,"video/mp4",1000);source.sizeBytes=sourceFile.length();project.assets.add(source);project.clips.add(clip("proxy-source-clip",source.id,project.tracks.get(0).id,0,1000));store.save(project);
        JobManager jobs=new JobManager(context);ProxyManager proxies=new ProxyManager(context,store,jobs);CountDownLatch complete=new CountDownLatch(1);AtomicReference<Throwable> failure=new AtomicReference<>();AtomicReference<JSONObject> result=new AtomicReference<>();
        jobs.submit("Device proxy proof",JobManager.Kind.HEAVY,JobManager.Origin.OWNER,state->{try{result.set(proxies.generate(project,source,"240p",state));}catch(Throwable error){failure.set(error);}finally{complete.countDown();}});
        assertTrue("Proxy timed out",complete.await(120,TimeUnit.SECONDS));assertNull("Proxy failure: "+failure.get(),failure.get());assertNotNull(result.get());
        JSONObject proof=PlayableMediaVerifier.verify(context,Uri.parse(result.get().getString("uri")),true);assertTrue(proof.getBoolean("hasAudio"));
        MediaMetadataRetriever metadata=new MediaMetadataRetriever();try{metadata.setDataSource(result.get().getString("uri").substring("file://".length()));assertEquals("240",metadata.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT));}finally{metadata.release();}
        ProjectStore.Project latest=store.get(project.id);assertNotEquals(source.uri,ProxyManager.previewUri(latest,source,"240p"));assertEquals(source.uri,ProxyManager.previewUri(latest,source,"original"));
        File finalOutput=new File(evidence,"proxy-original-export.mp4");render(latest,finalOutput);assertFrame(finalOutput,500000,Color.RED);
        assertEquals(originalHash,PlayableMediaVerifier.verify(context,Uri.fromFile(sourceFile),true).getString("sha256"));write("proxy-proof.json",result.get().toString(2));
    }

    @Test public void conservativeAndSoftwareCodecsRenderOriginalVideoWithAudio()throws Exception {
        ProjectStore store=new ProjectStore(context);ProjectStore.Project fixture=store.create("Codec source fixture");fixture.assets.add(asset("codec-red",png("codec-red.png",Color.RED),"image/png",0));fixture.assets.add(asset("codec-tone",wav("codec-tone.wav",1),"audio/wav",1000));
        fixture.clips.add(clip("codec-image","codec-red",fixture.tracks.get(0).id,0,1000));ProjectStore.Track sound=new ProjectStore.Track();sound.id="codec-audio";sound.type="audio_music";sound.order=1;fixture.tracks.add(sound);fixture.clips.add(clip("codec-sound","codec-tone",sound.id,0,1000));store.save(fixture);
        File original=new File(evidence,"codec-source.mp4");render(fixture,original);String sourceHash=PlayableMediaVerifier.verify(context,Uri.fromFile(original),true).getString("sha256");
        ProjectStore.Project project=store.create("Codec path evidence");ProjectStore.Asset source=asset("codec-video",original,"video/mp4",1000);project.assets.add(source);project.clips.add(clip("codec-video-clip",source.id,project.tracks.get(0).id,0,1000));project.settings.put("fps",30).put("exportBitrate",2_000_000);store.save(project);
        org.json.JSONArray attempts=new org.json.JSONArray();
        for(RenderRetryController.Route route:new RenderRetryController.Route[]{RenderRetryController.Route.CONSERVATIVE,RenderRetryController.Route.SOFTWARE_CODECS}){
            File output=new File(evidence,"codec-"+route.name().toLowerCase(java.util.Locale.ROOT)+".mp4");JSONObject proof=render(project,output,route);assertEquals(route.name().toLowerCase(java.util.Locale.ROOT),proof.getString("codecRoute"));assertTrue(proof.getBoolean("playable"));assertTrue(proof.getBoolean("hasAudio"));assertEquals(1280,proof.getInt("encodedWidth"));assertEquals(720,proof.getInt("encodedHeight"));assertEquals(2_000_000,proof.getInt("requestedBitrate"));assertTrue(proof.getBoolean("codecReliabilityRecorded"));assertFrame(output,500000,Color.RED);
            assertEquals(2_000_000,proof.getInt("configuredBitrate"));
            if(route==RenderRetryController.Route.SOFTWARE_CODECS){
                boolean softwareEncoder=false,softwareDecoder=false;
                for(android.media.MediaCodecInfo codec:new android.media.MediaCodecList(android.media.MediaCodecList.ALL_CODECS).getCodecInfos()){
                    if(codec.getName().equals(proof.getString("videoEncoder")))softwareEncoder=codec.isSoftwareOnly();
                    org.json.JSONArray names=proof.getJSONArray("decoderNames");for(int i=0;i<names.length();i++)if(codec.getName().equals(names.getString(i))&&!codec.isEncoder()&&codec.isSoftwareOnly())for(String mime:codec.getSupportedTypes())if(mime.startsWith("video/"))softwareDecoder=true;
                }
                assertTrue("Actual software video encoder",softwareEncoder);assertTrue("Actual software video decoder",softwareDecoder);
            }
            attempts.put(proof);
        }
        assertEquals(sourceHash,PlayableMediaVerifier.verify(context,Uri.fromFile(original),true).getString("sha256"));
        try(CodecReliabilityStore history=new CodecReliabilityStore(context)){assertTrue(history.entries().length()>0);write("codec-reliability-proof.json",new JSONObject().put("attempts",attempts).put("history",history.entries()).put("originalRetained",true).toString(2));}
    }

    @Test public void encodedWindowRecoveryAndLosslessJoinPreserveOriginalClocksAndContinuousAudio()throws Exception {
        ProjectStore owner=new ProjectStore(context);ProjectStore.Project project=owner.create("Window recovery evidence");
        File original=png("segment-original.png",Color.RED),tone=wav("segment-tone.wav",10);project.assets.add(asset("segment-image",original,"image/png",0));project.assets.add(asset("segment-audio",tone,"audio/wav",10000));
        ProjectStore.Clip image=clip("segment-image-clip","segment-image",project.tracks.get(0).id,0,10000);image.title="CONTINUOUS TITLE";image.effects.put("textAnimation","fade");
        image.keyframes.put(new JSONObject().put("property","brightness").put("timeMs",0).put("value",0).put("easing","linear"));image.keyframes.put(new JSONObject().put("property","brightness").put("timeMs",10000).put("value",-.35).put("easing","linear"));project.clips.add(image);
        ProjectStore.Track sound=new ProjectStore.Track();sound.id="segment-sound";sound.type="audio_music";sound.order=1;project.tracks.add(sound);ProjectStore.Clip audioClip=clip("segment-audio-clip","segment-audio",sound.id,0,10000);audioClip.effects.put("audioDsp",new JSONObject().put("delayMs",100).put("delayWet",.25).put("delayFeedback",.2));project.clips.add(audioClip);project.settings.put("fps",30).put("exportBitrate",2_000_000);owner.save(project);
        byte[] originalBytes=java.nio.file.Files.readAllBytes(original.toPath());File whole=new File(evidence,"segment-whole-reference.mp4");render(project,whole,RenderRetryController.Route.CONSERVATIVE);
        File replacement=new File(evidence,"segment-inode-guard.mp4");try(var workspace=SegmentMediaMuxer.prepareOutput(project,replacement)){java.nio.file.Files.move(replacement.toPath(),new File(evidence,"segment-retired-inode.mp4").toPath());java.nio.file.Files.write(replacement.toPath(),originalBytes);assertThrows(java.io.IOException.class,workspace::ensureCurrent);assertThrows(java.io.IOException.class,workspace::discard);assertArrayEquals(originalBytes,java.nio.file.Files.readAllBytes(replacement.toPath()));}
        RenderSourceIdentity.Snapshot snapshot=new RenderSourceIdentity(context).capture(project,"16:9","720p",()->false);File root=new File(context.getFilesDir(),"segment-device-"+java.util.UUID.randomUUID());
        RenderSessionStore dying=new RenderSessionStore(context,root,"old-device-process",(file,video)->PlayableMediaVerifier.verify(context,Uri.fromFile(file),video),point->{if("encoded".equals(point))throw new java.io.IOException("Injected interruption after verified encoded intent");});
        RenderSessionStore.Writer old=dying.acquire(snapshot);File firstStage=dying.begin(old,RenderSessionStore.Kind.VIDEO,0,0,5_000_000,new JSONObject().put("windowStartUs",0));
        JSONObject firstProof=renderPrepared(snapshot.boundProject,new TimelineCompositionFactory(context).buildVideoWindow(snapshot.boundProject,"16:9","720p",new TimelineWindow(0,5_000_000)),firstStage,true);
        try{dying.complete(old,RenderSessionStore.Kind.VIDEO,0,firstProof);fail("Encoded fault did not execute");}catch(java.io.IOException expected){}assertTrue(firstStage.exists());
        try(RenderSessionStore restarted=new RenderSessionStore(context,root,"new-device-process",(file,video)->PlayableMediaVerifier.verify(context,Uri.fromFile(file),video),point->{});RenderSessionStore.Writer writer=restarted.acquire(snapshot)){
            RenderSessionStore.Checkpoint first=restarted.reusable(writer,RenderSessionStore.Kind.VIDEO,0,0,5_000_000);assertNotNull("Real encoded stage must recover without rendering again",first);assertEquals(firstProof.getString("sha256"),first.proof.getString("sha256"));assertFalse(firstStage.exists());
            File secondStage=restarted.begin(writer,RenderSessionStore.Kind.VIDEO,1,5_000_000,10_000_000,new JSONObject().put("windowStartUs",5_000_000));JSONObject secondProof=renderPrepared(snapshot.boundProject,new TimelineCompositionFactory(context).buildVideoWindow(snapshot.boundProject,"16:9","720p",new TimelineWindow(5_000_000,10_000_000)),secondStage,true);RenderSessionStore.Checkpoint second=restarted.complete(writer,RenderSessionStore.Kind.VIDEO,1,secondProof);
            File audioStage=restarted.begin(writer,RenderSessionStore.Kind.AUDIO,0,0,10_000_000,new JSONObject());JSONObject audioProof=renderPrepared(snapshot.boundProject,new TimelineCompositionFactory(context).buildAudio(snapshot.boundProject),audioStage,false);assertFalse(audioProof.getBoolean("hasVideo"));assertTrue(audioProof.getBoolean("hasAudio"));RenderSessionStore.Checkpoint audio=restarted.complete(writer,RenderSessionStore.Kind.AUDIO,0,audioProof);
            JSONObject inputTiming=new JSONObject().put("whole",mediaTiming(whole)).put("first",mediaTiming(first.file)).put("second",mediaTiming(second.file)).put("audio",mediaTiming(audio.file));write("segment-input-timing.json",inputTiming.toString(2));android.util.Log.i("StudioSegmentTiming",inputTiming.toString());
            java.nio.file.Files.write(first.file.toPath(),new byte[]{9,9,9});assertNull("Corrupt real checkpoint must not be reused",restarted.reusable(writer,RenderSessionStore.Kind.VIDEO,0,0,5_000_000));assertEquals(secondProof.getString("sha256"),restarted.reusable(writer,RenderSessionStore.Kind.VIDEO,1,5_000_000,10_000_000).proof.getString("sha256"));
            File repair=restarted.begin(writer,RenderSessionStore.Kind.VIDEO,0,0,5_000_000,new JSONObject().put("windowStartUs",0));JSONObject repairProof=renderPrepared(snapshot.boundProject,new TimelineCompositionFactory(context).buildVideoWindow(snapshot.boundProject,"16:9","720p",new TimelineWindow(0,5_000_000)),repair,true);first=restarted.complete(writer,RenderSessionStore.Kind.VIDEO,0,repairProof);
            final var protectedFirst=first;assertThrows(IllegalArgumentException.class,()->SegmentMediaMuxer.mux(context,project,java.util.Arrays.asList(protectedFirst,second),audio,original,()->false));assertArrayEquals(originalBytes,java.nio.file.Files.readAllBytes(original.toPath()));
            File joined=new File(evidence,"segment-joined.mp4");JSONObject proof=SegmentMediaMuxer.mux(context,project,java.util.Arrays.asList(first,second),audio,joined,()->false);assertTrue(proof.getBoolean("playable"));assertTrue(proof.getBoolean("hasAudio"));assertTrue(proof.getBoolean("joinedWithoutReencoding"));assertEquals(2,proof.getInt("videoWindows"));assertTrue(Math.abs(proof.getLong("durationMs")-10000)<=1);assertEquals(audioPacketDigest(audio.file,10_000_000),audioPacketDigest(joined,10_000_000));
            JSONObject sourcePcm=decodedAudioProof(audio.file,10_000_000),joinedPcm=decodedAudioProof(joined,10_000_000);assertEquals("Lossless AAC join must decode the same entire programme",sourcePcm.getString("sha256"),joinedPcm.getString("sha256"));write("segment-decoded-audio-proof.json",new JSONObject().put("source",sourcePcm).put("joined",joinedPcm).toString(2));
            File held=new File(evidence,"segment-held-inode.mp4");try(var descriptor=android.os.ParcelFileDescriptor.open(joined,android.os.ParcelFileDescriptor.MODE_READ_ONLY)){java.nio.file.Files.move(joined.toPath(),held.toPath());try{java.nio.file.Files.copy(original.toPath(),joined.toPath());JSONObject boundProof=PlayableMediaVerifier.verifyDescriptor(descriptor.getFileDescriptor(),true,()->false);assertEquals("Held inode verification must ignore the replacement PNG path",proof.getString("sha256"),boundProof.getString("sha256"));assertTrue(descriptor.getFileDescriptor().valid());}finally{java.nio.file.Files.deleteIfExists(joined.toPath());java.nio.file.Files.move(held.toPath(),joined.toPath());}}
            for(long time:new long[]{250_000,4_900_000,5_100_000,5_500_000,9_750_000})assertFrameMatches(whole,joined,time);
            File tail=new File(evidence,"segment-fractional-tail.mp4");firstFrameTail(first.file,tail,1001);JSONObject tailProof=PlayableMediaVerifier.verify(context,Uri.fromFile(tail),true);RenderSessionStore.Checkpoint fractional=new RenderSessionStore.Checkpoint(tail,tailProof,10_000_000,10_001_001);File fractionalJoin=new File(evidence,"segment-fractional-joined.mp4");JSONObject fractionalProof=SegmentMediaMuxer.mux(context,project,java.util.Arrays.asList(first,second,fractional),null,fractionalJoin,()->false);assertEquals(10_001_001,fractionalProof.getLong("plannedDurationUs"));
            android.media.MediaExtractor timing=new android.media.MediaExtractor();long exactEnd=-1;try{timing.setDataSource(fractionalJoin.getAbsolutePath());for(int i=0;i<timing.getTrackCount();i++){android.media.MediaFormat format=timing.getTrackFormat(i);if(format.getString(android.media.MediaFormat.KEY_MIME).startsWith("video/"))exactEnd=format.getLong(android.media.MediaFormat.KEY_DURATION);}}finally{timing.release();}assertTrue("Fractional last sample must not repeat the preceding33ms frame duration: "+exactEnd,Math.abs(exactEnd-10_001_001)<=1000);
            assertArrayEquals(originalBytes,java.nio.file.Files.readAllBytes(original.toPath()));write("segment-window-proof.json",new JSONObject().put("sessionId",snapshot.sessionId).put("firstRecoveredWithoutRerender",true).put("corruptWindowRepairedIndividually",true).put("otherWindowRetained",true).put("originalRetained",true).put("boundaryFramesMatched",5).put("aacPacketsAndTimesIdentical",true).put("fractionalTailDurationUs",exactEnd).put("fractionalTailFixture","one native encoded sync frame with explicit1001us end; not a tiny Transformer window").put("joined",proof).toString(2));
        }finally{old.close();dying.close();}
    }

    private org.json.JSONArray mediaTiming(File file)throws Exception {
        var tracks=new org.json.JSONArray();int count;
        var index=new android.media.MediaExtractor();try{index.setDataSource(file.getAbsolutePath());count=index.getTrackCount();}finally{index.release();}
        // Seeking to zero skips AAC preroll. Use a fresh extractor for each complete track.
        for(int track=0;track<count;track++){
            var extractor=new android.media.MediaExtractor();try{
                extractor.setDataSource(file.getAbsolutePath());var format=extractor.getTrackFormat(track);extractor.selectTrack(track);
                var times=new org.json.JSONArray();long packets=0,bytes=0,last=Long.MIN_VALUE;
                while(extractor.getSampleSize()>=0){long time=extractor.getSampleTime();if(packets<4)times.put(time);last=time;bytes=Math.addExact(bytes,extractor.getSampleSize());packets++;if(!extractor.advance())break;}
                tracks.put(new JSONObject().put("format",format.toString()).put("initialSampleTimesUs",times).put("packetCount",packets).put("sampleBytes",bytes).put("lastSampleTimeUs",last));
            }finally{extractor.release();}
        }
        return tracks;
    }

    private JSONObject renderPrepared(ProjectStore.Project project,androidx.media3.transformer.Composition composition,File output,boolean video)throws Exception {
        CountDownLatch done=new CountDownLatch(1);AtomicReference<String> error=new AtomicReference<>();AtomicReference<JSONObject> result=new AtomicReference<>();AtomicReference<NativeRenderEngine.Handle> handle=new AtomicReference<>();
        InstrumentationRegistry.getInstrumentation().runOnMainSync(()->handle.set(new NativeRenderEngine(context).exportComposition(project,composition,output,"16:9","720p",video,new NativeRenderEngine.Listener(){public void onProgress(int percent,String detail){}public void onCompleted(File file,JSONObject proof){result.set(proof);done.countDown();}public void onError(String detail){error.set(detail);done.countDown();}},RenderRetryController.Route.CONSERVATIVE)));
        boolean finished=done.await(180,TimeUnit.SECONDS);if(!finished&&handle.get()!=null)handle.get().cancel();assertTrue("Prepared native render timed out",finished);assertNull("Prepared native render: "+error.get(),error.get());assertNotNull(result.get());return result.get();
    }
    private void assertFrameMatches(File whole,File joined,long time)throws Exception {
        MediaMetadataRetriever reference=new MediaMetadataRetriever(),actual=new MediaMetadataRetriever();Bitmap a=null,b=null;
        try{reference.setDataSource(whole.getAbsolutePath());actual.setDataSource(joined.getAbsolutePath());a=reference.getScaledFrameAtTime(time,MediaMetadataRetriever.OPTION_CLOSEST,128,128);b=actual.getScaledFrameAtTime(time,MediaMetadataRetriever.OPTION_CLOSEST,128,128);assertNotNull(a);assertNotNull(b);assertEquals(a.getWidth(),b.getWidth());assertEquals(a.getHeight(),b.getHeight());long difference=0;
            for(int y=0;y<a.getHeight();y++)for(int x=0;x<a.getWidth();x++){int p=a.getPixel(x,y),q=b.getPixel(x,y);difference+=Math.abs(Color.red(p)-Color.red(q))+Math.abs(Color.green(p)-Color.green(q))+Math.abs(Color.blue(p)-Color.blue(q));}
            assertTrue("Original effect/title clock differs at "+time+"us, channel difference="+(difference/(double)(a.getWidth()*a.getHeight()*3)),difference/(double)(a.getWidth()*a.getHeight()*3)<3);
        }finally{if(a!=null)a.recycle();if(b!=null)b.recycle();reference.release();actual.release();}
    }
    private String audioPacketDigest(File file,long endUs)throws Exception {
        android.media.MediaExtractor extractor=new android.media.MediaExtractor();try{extractor.setDataSource(file.getAbsolutePath());int track=-1;for(int i=0;i<extractor.getTrackCount();i++)if(extractor.getTrackFormat(i).getString(android.media.MediaFormat.KEY_MIME).startsWith("audio/")){track=i;break;}assertTrue(track>=0);extractor.selectTrack(track);java.security.MessageDigest digest=java.security.MessageDigest.getInstance("SHA-256");ByteBuffer sample=ByteBuffer.allocateDirect(1024*1024);int packets=0;
            while(extractor.getSampleSize()>=0){sample.clear();assertTrue(extractor.getSampleSize()<=sample.capacity());int bytes=extractor.readSampleData(sample,0);assertTrue(bytes>0);digest.update(ByteBuffer.allocate(8).putLong(extractor.getSampleTime()).array());sample.position(0);sample.limit(bytes);digest.update(sample);packets++;if(!extractor.advance())break;}assertTrue(packets>100);StringBuilder out=new StringBuilder();for(byte value:digest.digest())out.append(String.format(java.util.Locale.ROOT,"%02x",value&255));return out.toString();
        }finally{extractor.release();}
    }
    private JSONObject decodedAudioProof(File file,long programmeUs)throws Exception{
        var input=new android.media.MediaExtractor();android.media.MediaCodec decoder=null;
        try{
            input.setDataSource(file.getAbsolutePath());int selected=-1;android.media.MediaFormat format=null;for(int i=0;i<input.getTrackCount();i++){var candidate=input.getTrackFormat(i);if(candidate.getString(android.media.MediaFormat.KEY_MIME).startsWith("audio/")){assertEquals("One continuous AAC track",-1,selected);selected=i;format=candidate;}}assertNotNull(format);input.selectTrack(selected);
            int rate=format.getInteger(android.media.MediaFormat.KEY_SAMPLE_RATE),channels=format.getInteger(android.media.MediaFormat.KEY_CHANNEL_COUNT);long expected=Math.multiplyExact(programmeUs,(long)rate)/1_000_000,delay=format.containsKey(android.media.MediaFormat.KEY_ENCODER_DELAY)?format.getInteger(android.media.MediaFormat.KEY_ENCODER_DELAY):0,padding=format.containsKey(android.media.MediaFormat.KEY_ENCODER_PADDING)?format.getInteger(android.media.MediaFormat.KEY_ENCODER_PADDING):0;
            // Decode every packet, then independently apply the container's gapless sample counts.
            format.removeKey(android.media.MediaFormat.KEY_ENCODER_DELAY);format.removeKey(android.media.MediaFormat.KEY_ENCODER_PADDING);decoder=android.media.MediaCodec.createDecoderByType(format.getString(android.media.MediaFormat.KEY_MIME));decoder.configure(format,null,null,0);decoder.start();
            boolean queuedEos=false,ended=false;long rawFrames=0,tailSamples=0;double tailSquares=0;int encoding=androidx.media3.common.C.ENCODING_PCM_16BIT;var info=new android.media.MediaCodec.BufferInfo();var hash=java.security.MessageDigest.getInstance("SHA-256");long deadline=SystemClock.elapsedRealtime()+60000;
            while(!ended&&SystemClock.elapsedRealtime()<deadline){
                if(!queuedEos){int index=decoder.dequeueInputBuffer(10000);if(index>=0){ByteBuffer bytes=decoder.getInputBuffer(index);assertNotNull(bytes);bytes.clear();long size=input.getSampleSize();if(size<0){decoder.queueInputBuffer(index,0,0,programmeUs,android.media.MediaCodec.BUFFER_FLAG_END_OF_STREAM);queuedEos=true;}else{assertTrue(size<=bytes.capacity());int read=input.readSampleData(bytes,0);assertTrue(read>0);decoder.queueInputBuffer(index,0,read,input.getSampleTime(),0);input.advance();}}}
                int index=decoder.dequeueOutputBuffer(info,10000);if(index==android.media.MediaCodec.INFO_OUTPUT_FORMAT_CHANGED){var actual=decoder.getOutputFormat();assertEquals(rate,actual.getInteger(android.media.MediaFormat.KEY_SAMPLE_RATE));assertEquals(channels,actual.getInteger(android.media.MediaFormat.KEY_CHANNEL_COUNT));if(actual.containsKey(android.media.MediaFormat.KEY_PCM_ENCODING))encoding=actual.getInteger(android.media.MediaFormat.KEY_PCM_ENCODING);}
                else if(index>=0){try{assertTrue(encoding==androidx.media3.common.C.ENCODING_PCM_16BIT||encoding==androidx.media3.common.C.ENCODING_PCM_FLOAT);int sampleBytes=encoding==androidx.media3.common.C.ENCODING_PCM_FLOAT?4:2,frameBytes=sampleBytes*channels;assertEquals(0,info.size%frameBytes);long frames=info.size/frameBytes;ByteBuffer bytes=info.size==0?ByteBuffer.allocate(0):decoder.getOutputBuffer(index);assertNotNull(bytes);if(info.size>0){bytes.position(info.offset);bytes.limit(info.offset+info.size);}bytes=bytes.slice().order(ByteOrder.LITTLE_ENDIAN);
                    long first=Math.max(0,delay-rawFrames),last=Math.min(frames,delay+expected-rawFrames);if(last>first){var included=bytes.duplicate();included.position((int)(first*frameBytes));included.limit((int)(last*frameBytes));hash.update(included);long tailStart=Math.max(first,delay+expected-rate/50-rawFrames);for(long frame=tailStart;frame<last;frame++)for(int channel=0;channel<channels;channel++){int at=(int)(frame*frameBytes+channel*sampleBytes);double value=sampleBytes==4?bytes.getFloat(at):bytes.getShort(at)/32768d;tailSquares+=value*value;tailSamples++;}}
                    rawFrames+=frames;ended=(info.flags&android.media.MediaCodec.BUFFER_FLAG_END_OF_STREAM)!=0;
                }finally{decoder.releaseOutputBuffer(index,false);}}
            }
            assertTrue("AAC decoder must reach EOS",ended);assertEquals("Gapless metadata must preserve the entire programme without fake duration",expected,rawFrames-delay-padding,1);assertTrue("Programme tail must contain audible source signal",tailSamples>0&&Math.sqrt(tailSquares/tailSamples)>.005);
            var sha=new StringBuilder();for(byte value:hash.digest())sha.append(String.format(java.util.Locale.ROOT,"%02x",value&255));return new JSONObject().put("decodedRawFrames",rawFrames).put("playableFrames",rawFrames-delay-padding).put("expectedFrames",expected).put("encoderDelay",delay).put("encoderPadding",padding).put("sampleRate",rate).put("channels",channels).put("tailRms",Math.sqrt(tailSquares/tailSamples)).put("sha256",sha.toString());
        }finally{if(decoder!=null){try{decoder.stop();}finally{decoder.release();}}input.release();}
    }
    private void firstFrameTail(File source,File output,long durationUs)throws Exception {
        android.media.MediaExtractor input=new android.media.MediaExtractor();android.media.MediaMuxer writer=null;try{input.setDataSource(source.getAbsolutePath());int track=-1;android.media.MediaFormat format=null;for(int i=0;i<input.getTrackCount();i++){var candidate=input.getTrackFormat(i);if(candidate.getString(android.media.MediaFormat.KEY_MIME).startsWith("video/")){track=i;format=candidate;break;}}assertTrue(track>=0);input.selectTrack(track);SegmentMediaMuxer.initialVideoSample(input.getSampleFlags());
            var originals=new ProjectStore.Project();var asset=new ProjectStore.Asset();asset.uri=Uri.fromFile(source).toString();originals.assets.add(asset);try(var workspace=SegmentMediaMuxer.prepareOutput(originals,output)){writer=new android.media.MediaMuxer(workspace.descriptor,android.media.MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);int target=writer.addTrack(format);writer.start();ByteBuffer sample=ByteBuffer.allocateDirect(SegmentMediaMuxer.sampleCapacity(input.getSampleSize()));int size=input.readSampleData(sample,0);sample.position(0);sample.limit(size);var info=new android.media.MediaCodec.BufferInfo();info.set(0,size,0,android.media.MediaCodec.BUFFER_FLAG_KEY_FRAME);writer.writeSampleData(target,sample,info);sample.clear();sample.limit(0);writer.writeSampleData(target,sample,SegmentMediaMuxer.endOfTrack(durationUs,0));writer.stop();writer.release();writer=null;android.system.Os.fsync(workspace.descriptor);}
        }finally{if(writer!=null)writer.release();input.release();}
    }

    private void render(ProjectStore.Project p,File output) throws Exception {render(p,output,RenderRetryController.Route.DEFAULT);}
    private JSONObject render(ProjectStore.Project p,File output,RenderRetryController.Route route) throws Exception {
        CountDownLatch complete=new CountDownLatch(1);AtomicReference<String> error=new AtomicReference<>();
        AtomicReference<JSONObject> proof=new AtomicReference<>();
        AtomicReference<NativeRenderEngine.Handle> handle=new AtomicReference<>();
        InstrumentationRegistry.getInstrumentation().runOnMainSync(()->handle.set(new NativeRenderEngine(context).export(p,output,"16:9","720p",new NativeRenderEngine.Listener(){
            @Override public void onProgress(int progress,String detail){}
            @Override public void onCompleted(File file,JSONObject result){proof.set(result);complete.countDown();}
            @Override public void onError(String detail){error.set(detail);complete.countDown();}
        },route)));
        boolean finished=complete.await(120,TimeUnit.SECONDS);
        if(!finished&&handle.get()!=null)handle.get().cancel();
        assertTrue("Native export timed out",finished);assertNull("Native render: "+error.get(),error.get());assertTrue(output.length()>0);assertNotNull(proof.get());return proof.get();
    }
    private File png(String name,int color)throws Exception {
        File out=new File(evidence,name);Bitmap bitmap=Bitmap.createBitmap(640,360,Bitmap.Config.ARGB_8888);bitmap.eraseColor(color);
        try(OutputStream stream=new FileOutputStream(out)){assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG,100,stream));}finally{bitmap.recycle();}return out;
    }
    private File wav(String name,int seconds)throws Exception {
        File out=new File(evidence,name);int rate=44100,bytes=rate*seconds*2;
        ByteBuffer h=ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN);
        h.put(new byte[]{'R','I','F','F'}).putInt(36+bytes).put(new byte[]{'W','A','V','E','f','m','t',' '}).putInt(16).putShort((short)1).putShort((short)1)
                .putInt(rate).putInt(rate*2).putShort((short)2).putShort((short)16).put(new byte[]{'d','a','t','a'}).putInt(bytes);
        try(OutputStream stream=new FileOutputStream(out)){stream.write(h.array());ByteBuffer chunk=ByteBuffer.allocate(4096).order(ByteOrder.LITTLE_ENDIAN);
            for(int frame=0;frame<rate*seconds;frame++){chunk.putShort((short)(Math.sin(frame*2*Math.PI*440/rate)*12000));if(!chunk.hasRemaining()){stream.write(chunk.array());chunk.clear();}}
            if(chunk.position()>0)stream.write(chunk.array(),0,chunk.position());}return out;
    }
    private static ProjectStore.Asset asset(String id,File file,String mime,long duration){ProjectStore.Asset a=new ProjectStore.Asset();a.id=id;a.name=file.getName();a.uri=Uri.fromFile(file).toString();a.mime=mime;a.durationMs=duration;return a;}
    private static ProjectStore.Clip clip(String id,String asset,String track,long start,long duration){ProjectStore.Clip c=new ProjectStore.Clip();c.id=id;c.assetId=asset;c.trackId=track;c.startMs=start;c.outMs=duration;return c;}
    private void assertPreviewColor(Rect bounds,int expected){
        long deadline=SystemClock.elapsedRealtime()+20000;int pixel=Color.BLACK;
        do{Bitmap screen=InstrumentationRegistry.getInstrumentation().getUiAutomation().takeScreenshot();
            if(screen!=null){pixel=screen.getPixel(bounds.centerX(),bounds.centerY());screen.recycle();if(near(pixel,expected))return;}
            SystemClock.sleep(100);
        }while(SystemClock.elapsedRealtime()<deadline);
        fail("Preview pixel was "+Integer.toHexString(pixel)+", expected "+Integer.toHexString(expected));
    }
    private static void assertFrame(File file,long time,int expected)throws Exception{
        MediaMetadataRetriever r=new MediaMetadataRetriever();try{r.setDataSource(file.getAbsolutePath());Bitmap frame=r.getFrameAtTime(time,MediaMetadataRetriever.OPTION_CLOSEST);
            assertNotNull("Decoded frame at "+time,frame);int pixel=frame.getPixel(frame.getWidth()/2,frame.getHeight()/2);frame.recycle();assertTrue("Frame at "+time+": "+Integer.toHexString(pixel),near(pixel,expected));}finally{r.release();}
    }
    private static void assertFramePixel(File file,long time,double x,double y,int expected)throws Exception{
        MediaMetadataRetriever decoder=new MediaMetadataRetriever();try{decoder.setDataSource(file.getAbsolutePath());Bitmap frame=decoder.getFrameAtTime(time,MediaMetadataRetriever.OPTION_CLOSEST);assertNotNull(frame);int pixel=frame.getPixel((int)(frame.getWidth()*x),(int)(frame.getHeight()*y));frame.recycle();assertTrue("Export pixel "+Integer.toHexString(pixel)+" at "+x+","+y,near(pixel,expected));}finally{decoder.release();}
    }
    private static boolean near(int a,int b){return Math.abs(Color.red(a)-Color.red(b))<35&&Math.abs(Color.green(a)-Color.green(b))<35&&Math.abs(Color.blue(a)-Color.blue(b))<35;}
    private void copy(Uri source,File out)throws Exception{try(InputStream in=context.getContentResolver().openInputStream(source);OutputStream stream=new FileOutputStream(out)){assertNotNull(in);byte[] b=new byte[256*1024];int n;while((n=in.read(b))!=-1)stream.write(b,0,n);}}
    private void write(String name,String text)throws Exception{try(OutputStream out=new FileOutputStream(new File(evidence,name))){out.write(text.getBytes(java.nio.charset.StandardCharsets.UTF_8));}}
    private static View findClass(View root,Class<?> type){if(type.isInstance(root))return root;if(root instanceof ViewGroup)for(int i=0;i<((ViewGroup)root).getChildCount();i++){View found=findClass(((ViewGroup)root).getChildAt(i),type);if(found!=null)return found;}return null;}
    private static View findDescription(View root,String label){if(label.equals(root.getContentDescription()))return root;if(root instanceof ViewGroup)for(int i=0;i<((ViewGroup)root).getChildCount();i++){View found=findDescription(((ViewGroup)root).getChildAt(i),label);if(found!=null)return found;}return null;}
    private static void click(MainActivity activity,String label){View button=findDescription(activity.getWindow().getDecorView(),label);assertNotNull(label,button);assertTrue(label,button.performClick());}
    private static void spinners(View root,ArrayList<Spinner> out){if(root instanceof Spinner)out.add((Spinner)root);if(root instanceof ViewGroup)for(int i=0;i<((ViewGroup)root).getChildCount();i++)spinners(((ViewGroup)root).getChildAt(i),out);}
}
