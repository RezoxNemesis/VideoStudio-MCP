package com.rezoxnemesis.videostudio;

import android.content.Context;
import android.app.*;
import android.net.Uri;
import android.view.*;
import android.widget.*;
import org.json.*;
import org.junit.*;
import org.junit.experimental.runners.Enclosed;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.Config;
import java.io.*;
import java.nio.*;
import java.nio.file.*;
import java.util.*;
import static org.junit.Assert.*;

@RunWith(Enclosed.class)
public class SketchStoryAudioTest {
    @RunWith(RobolectricTestRunner.class) @Config(sdk=33,manifest=Config.NONE)
    public static class Core {
        private File root;
        @Before public void setup()throws Exception {root=Files.createTempDirectory("story_audio_test_").toFile();}
        @After public void cleanup()throws Exception {remove(root);}
        @Test public void dryDialogueRetainsEveryPcmSampleAndClock()throws Exception {
            File input=wave(root,"voice.wav",24000,7000),out=new File(root,"dry.wav");
            String before=SketchStoryAudio.sha256(input);
            JSONObject proof=SketchStoryAudio.render(input,out,dry());
            assertEquals(before,SketchStoryAudio.sha256(input));assertArrayEquals(Files.readAllBytes(input.toPath()),Files.readAllBytes(out.toPath()));
            assertEquals(24000,proof.getLong("sampleFrames"));assertEquals(1,proof.getDouble("appliedGain"),0);
            assertEquals(4096,proof.getInt("bufferFrames"));assertEquals(2,proof.getInt("passes"));assertFalse(proof.getBoolean("playbackVerified"));
        }
        @Test public void overloadUsesOneGlobalAttenuationAndNeverHardClips()throws Exception {
            File input=wave(root,"voice.wav",24000,30000),out=new File(root,"mix.wav");JSONObject settings=dry().put("dialogueGain",2);
            JSONObject proof=SketchStoryAudio.render(input,out,settings);double gain=proof.getDouble("appliedGain");assertTrue(gain>0&&gain<1);
            short[] original=samples(input),mixed=samples(out);assertEquals(original.length,mixed.length);
            for(int i=0;i<mixed.length;i++){assertEquals(Math.round(original[i]*2d*gain),mixed[i]);assertTrue(Math.abs((int)mixed[i])<=31130);}
        }
        @Test public void padAmbienceAndEventsAreDeterministicAndEventsNeverPrecedeTheirCue()throws Exception {
            File input=wave(root,"silence.wav",48000,0),one=new File(root,"one.wav"),two=new File(root,"two.wav");
            JSONObject settings=new JSONObject().put("theme","calm").put("ambience","wind").put("events",new JSONArray()
                .put(event("step",500,"footstep",.7)).put(event("ring",700,"chime",.5)));
            SketchStoryAudio.render(input,one,settings);SketchStoryAudio.render(input,two,settings);
            assertArrayEquals(Files.readAllBytes(one.toPath()),Files.readAllBytes(two.toPath()));
            File eventOnly=new File(root,"event.wav");SketchStoryAudio.render(input,eventOnly,dry().put("events",new JSONArray().put(event("wood",500,"wood",1))));
            short[] actual=samples(eventOnly);for(int i=0;i<12000;i++)assertEquals(0,actual[i]);assertTrue(energy(actual,12000,17760)>0);for(int i=17760;i<actual.length;i++)assertEquals(0,actual[i]);
            for(String kind:new String[]{"wood","metal","cloth","chime","footstep"}){
                File f=new File(root,kind+".wav");SketchStoryAudio.render(input,f,dry().put("events",new JSONArray().put(event(kind,0,kind,1))));assertTrue(energy(samples(f),0,24000)>0);
            }
        }
        @Test public void explicitlyBoundedEventDurationKeepsItsContactTimeAndEndsInsideTheUnchangedMaster()throws Exception {
            File input=wave(root,"voice.wav",24000,0),output=new File(root,"bounded.wav");String hash=SketchStoryAudio.sha256(input);
            JSONObject cue=event("last_step",900,"footstep",1).put("durationMs",100);SketchStoryAudio.render(input,output,dry().put("events",new JSONArray().put(cue)));short[] pcm=samples(output);assertEquals(24000,pcm.length);assertEquals(hash,SketchStoryAudio.sha256(input));for(int i=0;i<21600;i++)assertEquals(0,pcm[i]);assertTrue(energy(pcm,21600,24000)>0);assertEquals(0,pcm[23999]);
            for(Object bad:new Object[]{0,181,100.5,"100",true})assertThrows(Exception.class,()->SketchStoryAudio.validateSettings(dry().put("events",new JSONArray().put(event("bad",0,"footstep",1).put("durationMs",bad))),24000));
        }
        @Test public void rejectsMalformedSettingsEventClocksAndUnsafeOutputsBeforePublication()throws Exception {
            File input=wave(root,"voice.wav",24000,1000);
            List<JSONObject> bad=new ArrayList<>();bad.add(new JSONObject().put("durationMs",2000));bad.add(new JSONObject().put("theme","joy"));bad.add(new JSONObject().put("ambience","sea"));bad.add(new JSONObject().put("tempo","72"));bad.add(new JSONObject().put("musicGain",-1));bad.add(new JSONObject().put("dialogueGain",3));bad.add(new JSONObject().put("seed",1.5));
            bad.add(dry().put("events",new JSONArray().put(event("a",-1,"wood",1))));bad.add(dry().put("events",new JSONArray().put(event("a",1000,"wood",1))));bad.add(dry().put("events",new JSONArray().put(event("a",950,"chime",1))));bad.add(dry().put("events",new JSONArray().put(event("a",0,"unknown",1))));bad.add(dry().put("events",new JSONArray().put(event("a",0,"wood",1)).put(event("a",100,"wood",1))));
            JSONArray tooMany=new JSONArray();for(int i=0;i<513;i++)tooMany.put(event("e"+i,0,"wood",1));bad.add(dry().put("events",tooMany));
            for(JSONObject s:bad){File out=new File(root,"invalid.wav");assertThrows(Exception.class,()->SketchStoryAudio.render(input,out,s));assertFalse(out.exists());}
            assertThrows(Exception.class,()->SketchStoryAudio.render(input,input,dry()));
            File existing=new File(root,"existing.wav");Files.write(existing.toPath(),new byte[]{1,2,3});assertThrows(Exception.class,()->SketchStoryAudio.render(input,existing,dry()));assertArrayEquals(new byte[]{1,2,3},Files.readAllBytes(existing.toPath()));
            File link=new File(root,"link.wav");Files.createSymbolicLink(link.toPath(),input.toPath());assertThrows(Exception.class,()->SketchStoryAudio.render(link,new File(root,"out.wav"),dry()));
        }
        @Test public void cancellationRetainsSourceAndPublishesNoPartialOutput()throws Exception {
            File input=wave(root,"voice.wav",24000,1000),out=new File(root,"cancelled.wav");String before=SketchStoryAudio.sha256(input);
            Thread.currentThread().interrupt();try{assertThrows(InterruptedIOException.class,()->SketchStoryAudio.render(input,out,dry()));}finally{Thread.interrupted();}
            assertFalse(out.exists());assertEquals(before,SketchStoryAudio.sha256(input));assertEquals(1,root.listFiles().length);
        }
        @Test public void cancellationDuringSecondPassDeletesStagingAndAllowsFreshRender()throws Exception {
            File input=wave(root,"long.wav",600L*24000,0),out=new File(root,"cancelled_mix.wav");java.util.concurrent.atomic.AtomicReference<Throwable> failure=new java.util.concurrent.atomic.AtomicReference<>();
            Thread thread=new Thread(()->{try{SketchStoryAudio.render(input,out,new JSONObject());}catch(Throwable error){failure.set(error);}});thread.start();
            long deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(45);boolean writing=false;
            while(thread.isAlive()&&System.nanoTime()<deadline){File[] staging=root.listFiles((dir,name)->name.endsWith(".partial"));if(staging!=null&&staging.length>0){writing=true;break;}Thread.sleep(10);}
            thread.interrupt();thread.join(10000);assertFalse(thread.isAlive());assertTrue("Second streaming pass was reached",writing);assertTrue(String.valueOf(failure.get()),failure.get() instanceof InterruptedIOException);assertFalse(out.exists());assertEquals(1,root.listFiles().length);
            SketchStoryAudio.render(input,out,dry());assertTrue(out.isFile());
        }
        @Test public void maximumTenMinutePcmIsStreamedWithAFixedWorkingBuffer()throws Exception {
            File input=wave(root,"ten_minutes.wav",600L*24000,0),out=new File(root,"ten_minutes_mix.wav");
            JSONObject proof=SketchStoryAudio.render(input,out,dry());assertEquals(600000,proof.getLong("durationMs"));assertEquals(14400000,proof.getLong("sampleFrames"));assertEquals(4096,proof.getInt("bufferFrames"));assertEquals(input.length(),out.length());
        }
    }
    @RunWith(RobolectricTestRunner.class) @Config(sdk=33,manifest=Config.NONE)
    public static class Owned {
        @Test public void manualDialogHasNumericMixControlsAndAddsAuthoredCues()throws Exception {
            Fixture f=new Fixture();try(org.robolectric.android.controller.ActivityController<Activity> controller=Robolectric.buildActivity(Activity.class).setup()){
                AlertDialog dialog=SketchStoryAudioDialog.show(controller.get(),f.source.id);
                ViewGroup decor=(ViewGroup)dialog.getWindow().getDecorView();
                assertEquals("0.035",find(decor,"Music gain",EditText.class).getText().toString());
                find(decor,"Event time (ms)",EditText.class).setText("200");find(decor,"Event intensity",EditText.class).setText("0.4");
                find(decor,"Event duration (ms, optional)",EditText.class).setText("100");find(decor,"Add cue",Button.class).performClick();
                JSONArray events=new JSONArray(find(decor,"Events JSON",EditText.class).getText().toString());assertEquals(100,events.getJSONObject(0).getInt("durationMs"));assertEquals(200,events.getJSONObject(0).getLong("atMs"));assertEquals(.4,events.getJSONObject(0).getDouble("intensity"),0);
                dialog.dismiss();assertEquals(1,f.projects.get(f.source.id).assets.size());
            }
        }
        @Test public void manualGenerateRunsAsyncThroughOwnedExecuteAndReturnsTheNewProject()throws Exception {
            Fixture f=new Fixture();java.util.concurrent.atomic.AtomicReference<JSONObject> result=new java.util.concurrent.atomic.AtomicReference<>();
            try(org.robolectric.android.controller.ActivityController<Activity> controller=Robolectric.buildActivity(Activity.class).setup()){
                AlertDialog dialog=SketchStoryAudioDialog.show(controller.get(),f.source.id,result::set);ViewGroup decor=(ViewGroup)dialog.getWindow().getDecorView();
                find(decor,"Music gain",EditText.class).setText("0");find(decor,"Ambience gain",EditText.class).setText("0");Button generate=find(decor,"Generate story audio",Button.class);generate.performClick();assertFalse(generate.isEnabled());
                long deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(15);
                while(result.get()==null&&System.nanoTime()<deadline){Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();Thread.sleep(20);}
                assertNotNull("Async manual generation must return an owned result",result.get());JSONObject receipt=result.get();assertNotEquals(f.source.id,receipt.getString("projectId"));assertEquals(f.source.updatedAt,f.projects.get(f.source.id).updatedAt);assertEquals(1,f.projects.get(receipt.getString("projectId")).assets.size());assertEquals(1,f.projects.get(receipt.getString("projectId")).studioLayers.size());dialog.dismiss();
            }
        }
        @Test public void ownsExactSourceAndReplaysOneAudioProjectWithoutTouchingSourceOrUserEdits()throws Exception {
            Fixture f=new Fixture();JSONObject a=SketchStoryAudio.execute(f.c,f.args),b=SketchStoryAudio.execute(f.c,f.args);
            assertEquals(a.getString("projectId"),b.getString("projectId"));assertEquals(a.getString("assetId"),b.getString("assetId"));assertTrue(b.getBoolean("replayed"));
            ProjectStore.Project output=f.projects.get(a.getString("projectId"));assertEquals(1,output.assets.size());assertEquals(0,output.clips.size());assertEquals(1,output.studioLayers.size());assertEquals(StudioLayer.AUDIO,output.studioLayers.get(0).type);assertTrue(output.assets.get(0).generated);
            assertEquals(f.source.updatedAt,f.projects.get(f.source.id).updatedAt);assertEquals(1,f.projects.get(f.source.id).assets.size());
            output.name="My edited mix";output.studioLayers.get(0).gain=.4f;f.projects.save(output);SketchStoryAudio.execute(f.c,f.args);assertEquals("My edited mix",f.projects.get(output.id).name);assertEquals(.4f,f.projects.get(output.id).studioLayers.get(0).gain,0);
            JSONObject conflict=new JSONObject(f.args.toString()).put("settings",dry().put("tempo",80));assertThrows(Exception.class,()->SketchStoryAudio.execute(f.c,conflict));
        }
        @Test public void completedBackupOnlyJournalReplaysWithoutRenderingOrChangingOwnedProjects()throws Exception {
            Fixture f=new Fixture();JSONObject first=SketchStoryAudio.execute(f.c,f.args);
            ProjectStore.Project output=f.projects.get(first.getString("projectId"));
            output.name="Keep my edited mix";output.studioLayers.get(0).gain=.4f;f.projects.save(output);
            String savedOutput=SketchDocument.canonical(f.projects.get(output.id).toJson());
            String savedSource=SketchDocument.canonical(f.projects.get(f.source.id).toJson());
            String sourceHash=SketchStoryAudio.sha256(f.input);
            File mixed=new File(Uri.parse(first.getString("uri")).getPath());
            String mixedHash=SketchStoryAudio.sha256(mixed);
            Set<String> projectIds=new HashSet<>();for(ProjectStore.Project p:f.projects.list())projectIds.add(p.id);
            File folder=new File(f.c.getFilesDir(),"story_audio/"+f.args.getString("operationId"));
            Set<String> files=new HashSet<>(Arrays.asList(Objects.requireNonNull(folder.list())));
            File journal=new File(folder,"operation.json"),backup=new File(folder,"operation.json.bak");
            byte[] savedJournal=Files.readAllBytes(journal.toPath());
            // AtomicFile must recover this interrupted-write state before deciding the operation is new.
            Files.move(journal.toPath(),backup.toPath());assertFalse(journal.exists());assertTrue(backup.isFile());
            for(int retry=0;retry<2;retry++){
                JSONObject replay=SketchStoryAudio.execute(f.c,f.args);
                assertTrue(replay.getBoolean("replayed"));
                assertEquals(SketchDocument.canonical(new JSONObject(first.toString()).put("replayed",true)),SketchDocument.canonical(replay));
                assertEquals(savedOutput,SketchDocument.canonical(f.projects.get(output.id).toJson()));
                assertEquals(savedSource,SketchDocument.canonical(f.projects.get(f.source.id).toJson()));
                assertEquals(sourceHash,SketchStoryAudio.sha256(f.input));assertEquals(mixedHash,SketchStoryAudio.sha256(mixed));
                Set<String> afterIds=new HashSet<>();for(ProjectStore.Project p:f.projects.list())afterIds.add(p.id);
                assertEquals(projectIds,afterIds);
                assertEquals("Recovery must not render another mix or leave staging files",files,new HashSet<>(Arrays.asList(Objects.requireNonNull(folder.list()))));
                assertTrue(journal.isFile());assertFalse(backup.exists());assertArrayEquals(savedJournal,Files.readAllBytes(journal.toPath()));
            }
        }
        @Test public void rejectsForeignAssetsStaleRevisionsSymlinksAndChangedSourceOrResult()throws Exception {
            for(String mode:new String[]{"foreign","stale","outside","symlink","source","result"}){
                Fixture f=new Fixture();
                if(mode.equals("foreign"))f.args.put("assetId","foreign");
                if(mode.equals("stale"))f.args.put("sourceProjectUpdatedAt",f.source.updatedAt-1);
                if(mode.equals("outside")){File external=wave(Files.createTempDirectory("foreign_audio_").toFile(),"voice.wav",24000,1000);f.source.assets.get(0).uri=Uri.fromFile(external).toString();f.projects.save(f.source);f.args.put("sourceProjectUpdatedAt",f.source.updatedAt);}
                if(mode.equals("symlink")){File link=new File(f.c.getFilesDir(),UUID.randomUUID()+".wav");Files.createSymbolicLink(link.toPath(),f.input.toPath());f.source.assets.get(0).uri=Uri.fromFile(link).toString();f.projects.save(f.source);f.args.put("sourceProjectUpdatedAt",f.source.updatedAt);}
                if(mode.equals("source")){SketchStoryAudio.execute(f.c,f.args);try(RandomAccessFile in=new RandomAccessFile(f.input,"rw")){in.seek(100);in.write(7);}}
                if(mode.equals("result")){JSONObject result=SketchStoryAudio.execute(f.c,f.args);try(RandomAccessFile out=new RandomAccessFile(new File(Uri.parse(result.getString("uri")).getPath()),"rw")){out.seek(100);out.write(7);}}
                assertThrows(mode,Exception.class,()->SketchStoryAudio.execute(f.c,f.args));
            }
        }
        @Test public void cancelledOperationCanRetryWithTheSameIdentity()throws Exception {
            Fixture f=new Fixture();Thread.currentThread().interrupt();try{assertThrows(InterruptedIOException.class,()->SketchStoryAudio.execute(f.c,f.args));}finally{Thread.interrupted();}
            JSONObject result=SketchStoryAudio.execute(f.c,f.args);assertEquals(1000,result.getLong("durationMs"));
        }
        @Test public void renderedJournalResumesWithoutDuplicatingAnAlreadySavedProject()throws Exception {
            Fixture f=new Fixture();JSONObject first=SketchStoryAudio.execute(f.c,f.args);ProjectStore.Project output=f.projects.get(first.getString("projectId"));output.name="Retain my changes";f.projects.save(output);long revision=output.updatedAt;
            File journalFile=new File(f.c.getFilesDir(),"story_audio/"+f.args.getString("operationId")+"/operation.json");JSONObject journal=new JSONObject(new String(Files.readAllBytes(journalFile.toPath()),java.nio.charset.StandardCharsets.UTF_8));journal.put("state","rendered").remove("receipt");Files.write(journalFile.toPath(),journal.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            JSONObject replay=SketchStoryAudio.execute(f.c,f.args);assertEquals(first.getString("projectId"),replay.getString("projectId"));assertEquals(first.getString("assetId"),replay.getString("assetId"));assertEquals(revision,f.projects.get(output.id).updatedAt);assertEquals(1,f.projects.get(output.id).studioLayers.size());
        }
    }
    /** Opt-in native product render. Three paths are supplied by the test launcher;
     * ordinary test runs skip this evidence job instead of claiming a new render. */
    @RunWith(RobolectricTestRunner.class) @Config(sdk=33,manifest=Config.NONE)
    public static class Evidence {
        @Test public void renderConfiguredDialogueThroughNativeProductCore()throws Exception {
            String source=System.getProperty("videostudio.storyAudio.dialogue"),destination=System.getProperty("videostudio.storyAudio.output"),settingsPath=System.getProperty("videostudio.storyAudio.settings");
            Assume.assumeTrue("Explicit source, new output directory and authored settings are required",source!=null&&destination!=null&&settingsPath!=null);
            File input=new File(source),folder=new File(destination);assertFalse("Preserve earlier render generations",folder.exists());assertTrue(folder.mkdirs());
            JSONObject settings=new JSONObject(new String(Files.readAllBytes(Path.of(settingsPath)),java.nio.charset.StandardCharsets.UTF_8));File output=new File(folder,"story-audio.wav");String before=SketchStoryAudio.sha256(input);
            JSONObject pcm=PcmWaveFile.inspect(input),proof=SketchStoryAudio.render(input,output,settings);
            assertEquals(before,SketchStoryAudio.sha256(input));assertEquals(pcm.getLong("sampleFrames"),proof.getLong("sampleFrames"));assertEquals(24000,proof.getInt("sampleRate"));assertEquals(1,proof.getInt("channels"));assertTrue(proof.getBoolean("nonSilent"));assertTrue(proof.getLong("peakPcm16")<=31130);assertEquals(0,proof.getLong("hardClippedSamples"));
            proof.put("execution","native_java_cpu_workspace").put("sourcePath",input.getAbsolutePath()).put("outputPath",output.getAbsolutePath()).put("settingsPath",new File(settingsPath).getAbsolutePath()).put("settingsSha256",SketchStoryAudio.sha256(new File(settingsPath))).put("sourceUnchanged",true).put("phoneExecutionVerified",false);
            File codeRoot=new File(System.getProperty("user.dir"));while(codeRoot!=null&&!new File(codeRoot,"android/app/src/main/java/com/rezoxnemesis/videostudio/SketchStoryAudio.java").isFile())codeRoot=codeRoot.getParentFile();assertNotNull("Find the actual repository source for this render",codeRoot);JSONObject hashes=new JSONObject();for(String path:new String[]{"android/app/src/main/java/com/rezoxnemesis/videostudio/SketchStoryAudio.java","android/app/src/main/java/com/rezoxnemesis/videostudio/SketchStoryAudioDialog.java","android/app/src/test/java/com/rezoxnemesis/videostudio/SketchStoryAudioTest.java"})hashes.put(path,SketchStoryAudio.sha256(new File(codeRoot,path)));proof.put("sourceCodeSha256",hashes);java.security.MessageDigest compiled=java.security.MessageDigest.getInstance("SHA-256");try(InputStream in=SketchStoryAudio.class.getResourceAsStream("SketchStoryAudio.class")){assertNotNull(in);byte[] b=new byte[8192];int n;while((n=in.read(b))!=-1)compiled.update(b,0,n);}StringBuilder classHash=new StringBuilder();for(byte b:compiled.digest())classHash.append(String.format(Locale.ROOT,"%02x",b&255));proof.put("compiledCoreClassSha256",classHash.toString());
            Files.write(new File(folder,"proof.json").toPath(),(proof.toString(2)+"\n").getBytes(java.nio.charset.StandardCharsets.UTF_8),StandardOpenOption.CREATE_NEW);
            Files.copy(Path.of(settingsPath),new File(folder,"settings.json").toPath());System.out.println("Native story audio proof: "+new File(folder,"proof.json").getAbsolutePath());
        }
    }
    static final class Fixture {
        final Context c=RuntimeEnvironment.getApplication();final ProjectStore projects=new ProjectStore(c);final ProjectStore.Project source=projects.create("Story voice");final File input;final JSONObject args;
        Fixture()throws Exception {input=wave(c.getFilesDir(),UUID.randomUUID()+".wav",24000,1000);ProjectStore.Asset a=new ProjectStore.Asset();a.id=UUID.randomUUID().toString();a.uri=Uri.fromFile(input).toString();a.name="Voice.wav";a.mime="audio/wav";a.durationMs=1000;a.sizeBytes=input.length();source.assets.add(a);projects.save(source);args=new JSONObject().put("projectId",source.id).put("sourceProjectUpdatedAt",source.updatedAt).put("assetId",a.id).put("operationId",UUID.randomUUID().toString()).put("settings",dry());}
    }
    static JSONObject dry()throws Exception {return new JSONObject().put("musicGain",0).put("ambienceGain",0).put("dialogueGain",1).put("ambience","none");}
    static JSONObject event(String id,long ms,String type,double gain)throws Exception {return new JSONObject().put("id",id).put("atMs",ms).put("type",type).put("intensity",gain);}
    static File wave(File root,String name,long count,int amplitude)throws Exception {
        File f=new File(root,name);try(RandomAccessFile out=new RandomAccessFile(f,"rw")){out.write("RIFF".getBytes("US-ASCII"));le(out,36+count*2,4);out.write("WAVEfmt ".getBytes("US-ASCII"));le(out,16,4);le(out,1,2);le(out,1,2);le(out,24000,4);le(out,48000,4);le(out,2,2);le(out,16,2);out.write("data".getBytes("US-ASCII"));le(out,count*2,4);if(amplitude==0)out.setLength(44+count*2);else for(long i=0;i<count;i++)le(out,(short)Math.round(amplitude*Math.sin(i*.03)),2);}return f;
    }
    static void le(RandomAccessFile out,long value,int bytes)throws Exception {for(int i=0;i<bytes;i++)out.write((int)(value>>>(i*8))&255);}
    static short[] samples(File f)throws Exception {byte[] bytes=Files.readAllBytes(f.toPath());ShortBuffer b=ByteBuffer.wrap(bytes,44,bytes.length-44).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer();short[] samples=new short[b.remaining()];b.get(samples);return samples;}
    static long energy(short[] samples,int first,int end){long value=0;for(int i=first;i<end;i++)value+=Math.abs((int)samples[i]);return value;}
    static <T extends View>T find(View view,String name,Class<T> type){if(type.isInstance(view)&&name.contentEquals(view.getContentDescription()==null?"":view.getContentDescription()))return type.cast(view);if(view instanceof ViewGroup){ViewGroup g=(ViewGroup)view;for(int i=0;i<g.getChildCount();i++){T found=find(g.getChildAt(i),name,type);if(found!=null)return found;}}return null;}
    static void remove(File f)throws Exception {if(f==null)return;if(f.isDirectory())for(File child:f.listFiles())remove(child);Files.deleteIfExists(f.toPath());}
}
