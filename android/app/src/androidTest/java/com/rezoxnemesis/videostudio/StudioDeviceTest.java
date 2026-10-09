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
        evidence=new File(context.getExternalFilesDir(null),"evidence");assertTrue(evidence.isDirectory()||evidence.mkdirs());
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

    @Test public void ownerSelectedDocumentFolderReplicatesEncryptedVaultAndReusesVerifiedObjects()throws Exception{
        String folder="VideoStudio_SAF_Replication_Test";
        device.executeShellCommand("mkdir -p /sdcard/Documents/"+folder);
        ProjectStore store=new ProjectStore(context);ProjectStore.Project project=store.create("SAF replica device proof");
        File source=png("saf-vault-original.png",Color.MAGENTA);ProjectStore.Asset image=asset("saf-vault",source,"image/png",0);image.sizeBytes=source.length();project.assets.add(image);store.save(project);
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
            }
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

    private void render(ProjectStore.Project p,File output) throws Exception {
        CountDownLatch complete=new CountDownLatch(1);AtomicReference<String> error=new AtomicReference<>();
        AtomicReference<NativeRenderEngine.Handle> handle=new AtomicReference<>();
        InstrumentationRegistry.getInstrumentation().runOnMainSync(()->handle.set(new NativeRenderEngine(context).export(p,output,"16:9","720p",new NativeRenderEngine.Listener(){
            @Override public void onProgress(int progress,String detail){}
            @Override public void onCompleted(File file,JSONObject result){complete.countDown();}
            @Override public void onError(String detail){error.set(detail);complete.countDown();}
        })));
        boolean finished=complete.await(120,TimeUnit.SECONDS);
        if(!finished&&handle.get()!=null)handle.get().cancel();
        assertTrue("Native export timed out",finished);assertNull("Native render: "+error.get(),error.get());assertTrue(output.length()>0);
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
