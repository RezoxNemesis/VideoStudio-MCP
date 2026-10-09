package com.rezoxnemesis.videostudio;

import android.content.Context;
import android.net.Uri;
import android.os.Bundle;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.speech.tts.Voice;

import org.json.JSONObject;

import java.io.File;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.List;
import java.util.ArrayList;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.security.MessageDigest;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Local narration/voice synthesis through Android's installed TTS engine.
 *
 * This is an operating-system capability, not a paid VideoStudio cloud API.
 * By default VideoStudio selects only voices that do not declare a network
 * requirement. The generated audio is written into the project Creative
 * Workspace so it can participate in cloud archive/restore and Media Bin flow.
 */
public final class NativeSpeechEngine {
    public interface Progress {
        void onProgress(int progress, String detail) throws Exception;
    }

    private final Context context;
    private final CreativeWorkspace workspace;

    public NativeSpeechEngine(Context context) {
        this.context = context.getApplicationContext();
        this.workspace = new CreativeWorkspace(this.context);
    }

    public JSONObject synthesize(ProjectStore.Project project,
                                 String text,
                                 String languageTag,
                                 String requestedVoice,
                                 float rate,
                                 float pitch,
                                 boolean offlineOnly,
                                 String fileName,
                                 String generationId,
                                 JobManager.Job job,
                                 Progress progress) throws Exception {
        if (project == null) throw new IllegalArgumentException("Project is required");
        String spoken = text == null ? "" : text.trim();
        if (spoken.isEmpty()) throw new IllegalArgumentException("Narration text is required");
        if (spoken.length() > 120000) throw new IllegalArgumentException("Narration text exceeds 120,000 characters; split the script into project sections");
        final Progress callback = progress == null ? (p, d) -> {} : progress;

        callback.onProgress(3, "Initialising local Android speech engine");
        CountDownLatch initLatch = new CountDownLatch(1);
        AtomicInteger initStatus = new AtomicInteger(Integer.MIN_VALUE);
        TextToSpeech tts = new TextToSpeech(context, status -> {
            initStatus.set(status);
            initLatch.countDown();
        });

        try {
            if (!initLatch.await(20, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Local speech engine initialisation timed out");
            }
            if (initStatus.get() != TextToSpeech.SUCCESS) {
                throw new IllegalStateException("No usable Android speech engine is available");
            }

            Locale locale = languageTag == null || languageTag.trim().isEmpty()
                    ? Locale.getDefault()
                    : Locale.forLanguageTag(languageTag.trim());
            int language = tts.setLanguage(locale);
            if (language == TextToSpeech.LANG_MISSING_DATA || language == TextToSpeech.LANG_NOT_SUPPORTED) {
                throw new IllegalStateException("Requested narration language is not installed in the local speech engine");
            }

            Voice chosen = chooseVoice(tts, locale, requestedVoice, offlineOnly);
            if (chosen != null) {
                int voiceResult = tts.setVoice(chosen);
                if (voiceResult != TextToSpeech.SUCCESS) {
                    throw new IllegalStateException("Selected local voice could not be activated");
                }
            } else if (offlineOnly) {
                throw new IllegalStateException("No installed offline voice is available for the requested language");
            }

            rate = Math.max(.45f, Math.min(2.0f, rate));
            pitch = Math.max(.55f, Math.min(1.8f, pitch));
            tts.setSpeechRate(rate);
            tts.setPitch(pitch);

            File dir = new File(new File(workspace.projectRoot(project.id), "audio"), "voices");
            if (!dir.exists() && !dir.mkdirs() && !dir.exists()) {
                throw new IllegalStateException("Could not create narration workspace");
            }
            String safeName = sanitize(fileName == null || fileName.trim().isEmpty()
                    ? "voice_" + System.currentTimeMillis() + ".wav"
                    : fileName);
            if (!safeName.toLowerCase(Locale.US).endsWith(".wav")) safeName += ".wav";
            String runId = generationId == null || generationId.isEmpty() ? UUID.randomUUID().toString() : generationId;
            if(!runId.matches("[a-zA-Z0-9_-]{8,80}"))throw new IllegalArgumentException("Invalid narration recovery ID");
            File target = new File(dir, safeName.substring(0,safeName.length()-4)+"_"+runId+".wav");
            File chunksDir = new File(new File(dir,"chunks"),runId);
            if(!chunksDir.isDirectory()&&!chunksDir.mkdirs())throw new IllegalStateException("Could not create narration checkpoints");
            String fingerprint = hash((spoken+"\u0000"+locale.toLanguageTag()+"\u0000"+(chosen==null?"":chosen.getName())+"\u0000"+rate+"\u0000"+pitch).getBytes(StandardCharsets.UTF_8));
            File journal = new File(chunksDir,"manifest.json");
            JSONObject manifest = journal.isFile() ? new JSONObject(new String(Files.readAllBytes(journal.toPath()),StandardCharsets.UTF_8)) : new JSONObject().put("fingerprint",fingerprint).put("parts",new JSONObject());
            if(!fingerprint.equals(manifest.optString("fingerprint")))throw new IllegalStateException("Narration checkpoint differs from this text or installed voice");
            List<String> textChunks= NarrationChunks.split(spoken,Math.min(3000,TextToSpeech.getMaxSpeechInputLength()-1));
            List<File> parts=new ArrayList<>();JSONObject records=manifest.getJSONObject("parts");
            for(int index=0;index<textChunks.size();index++){
                if(Thread.currentThread().isInterrupted())throw new InterruptedException("Narration cancelled");
                String partText=textChunks.get(index);if(partText.trim().isEmpty())continue;
                File part=new File(chunksDir,String.format(Locale.US,"%05d.wav",index));String key=Integer.toString(index);
                boolean verified=false;
                if(part.isFile()&&records.has(key))try{WavFile.inspect(part);verified=records.getString(key).equals(hash(part));}catch(InterruptedException cancelled){throw cancelled;}catch(Exception ignored){}
                callback.onProgress(12+(int)(78L*index/textChunks.size()),(verified?"Resuming verified narration chunk ":"Synthesising narration chunk ")+(index+1)+" / "+textChunks.size());
                if(!verified){
                    File pending=new File(chunksDir,key+".partial.wav");Files.deleteIfExists(pending.toPath());
                    synthesizePart(tts,partText,pending);
                    WavFile.inspect(pending);
                    Files.move(pending.toPath(),part.toPath(),StandardCopyOption.REPLACE_EXISTING);
                    records.put(key,hash(part));manifest.remove("assembledSha256");Files.deleteIfExists(new File(chunksDir,"assembled.wav").toPath());writeJournal(journal,manifest);
                }
                parts.add(part);
            }
            // Never overwrite an existing published WAV. Recovery validates its exact PCM bytes.
            File assembled=new File(chunksDir,"assembled.wav");
            if(assembled.isFile()&&(!manifest.has("assembledSha256")||!manifest.getString("assembledSha256").equals(hash(assembled))))Files.delete(assembled.toPath());
            if(!assembled.isFile()){WavFile.concatenate(parts,assembled,()->Thread.currentThread().isInterrupted());manifest.put("assembledSha256",hash(assembled));writeJournal(journal,manifest);}
            WavFile.Info info=WavFile.inspect(assembled);String outputHash=hash(assembled);
            ensureActive(job);
            if(target.exists()){
                WavFile.inspect(target);if(!outputHash.equals(hash(target)))throw new IllegalStateException("Existing narration output differs from recovery checkpoint");
            }else{
                File publication=File.createTempFile("voice-",".partial",dir);
                try{
                    byte[] copy=new byte[64*1024];
                    try(InputStream input=Files.newInputStream(assembled.toPath());FileOutputStream output=new FileOutputStream(publication)){
                        int count;while((count=input.read(copy))!=-1){ensureActive(job);output.write(copy,0,count);}output.getFD().sync();
                    }
                    if(job==null){ensureActive(null);Files.move(publication.toPath(),target.toPath());}
                    else job.commit(active->Files.move(publication.toPath(),target.toPath()));
                }
                finally{Files.deleteIfExists(publication.toPath());}
            }
            WavFile.inspect(target);
            ensureActive(job);

            callback.onProgress(96, "Registering generated narration");
            JSONObject out = new JSONObject();
            out.put("ok", true);
            out.put("engine", "android-system-tts");
            out.put("local", true);
            out.put("offlineOnly", offlineOnly);
            out.put("networkRequired", chosen != null && chosen.isNetworkConnectionRequired());
            out.put("language", locale.toLanguageTag());
            out.put("voice", chosen == null ? "" : chosen.getName());
            out.put("rate", rate);
            out.put("pitch", pitch);
            out.put("uri", Uri.fromFile(target).toString());
            out.put("path", target.getAbsolutePath());
            out.put("fileName", target.getName());
            out.put("bytes", target.length());
            out.put("durationMs",info.durationMs());
            out.put("sha256",outputHash);
            out.put("sampleRate",info.sampleRate);
            out.put("channels",info.channels);
            out.put("verifiedPcmFrames",info.frameCount);
            out.put("chunkCount",parts.size());
            out.put("generationId",runId);
            out.put("workspaceRelativePath", "audio/voices/" + target.getName());
            callback.onProgress(100, "Narration audio ready");
            return out;
        } finally {
            try { tts.shutdown(); } catch (Exception ignored) {}
        }
    }

    private static void ensureActive(JobManager.Job job)throws InterruptedException{
        if(Thread.currentThread().isInterrupted())throw new InterruptedException("Narration cancelled");
        if(job!=null)job.checkActive();
    }
    private static void synthesizePart(TextToSpeech tts,String text,File output)throws Exception{
        CountDownLatch done=new CountDownLatch(1);AtomicReference<String> error=new AtomicReference<>();String id="vs_"+UUID.randomUUID();
        tts.setOnUtteranceProgressListener(new UtteranceProgressListener(){
            @Override public void onStart(String value){}
            @Override public void onDone(String value){if(id.equals(value))done.countDown();}
            @Override @SuppressWarnings("deprecation") public void onError(String value){onError(value,-1);}
            @Override public void onError(String value,int code){if(id.equals(value)){error.set("Local speech synthesis failed with code "+code);done.countDown();}}
        });
        if(tts.synthesizeToFile(text,new Bundle(),output,id)!=TextToSpeech.SUCCESS)throw new IllegalStateException("Local speech engine rejected the chunk");
        long deadline=System.currentTimeMillis()+Math.max(45000L,Math.min(240000L,text.length()*95L));
        while(!done.await(500,TimeUnit.MILLISECONDS)){
            if(Thread.currentThread().isInterrupted())throw new InterruptedException("Narration cancelled");
            if(System.currentTimeMillis()>=deadline)throw new IllegalStateException("Local speech chunk timed out");
        }
        if(error.get()!=null)throw new IllegalStateException(error.get());
    }
    private static String hash(byte[] bytes)throws Exception{return hex(MessageDigest.getInstance("SHA-256").digest(bytes));}
    private static String hash(File file)throws Exception{
        MessageDigest digest=MessageDigest.getInstance("SHA-256");byte[] buffer=new byte[64*1024];
        try(InputStream in=Files.newInputStream(file.toPath())){int count;while((count=in.read(buffer))!=-1){if(Thread.currentThread().isInterrupted())throw new InterruptedException("Narration verification cancelled");digest.update(buffer,0,count);}}
        return hex(digest.digest());
    }
    private static String hex(byte[] bytes){StringBuilder result=new StringBuilder();for(byte value:bytes)result.append(String.format(Locale.US,"%02x",value&255));return result.toString();}
    private static void writeJournal(File target,JSONObject value)throws Exception{
        File pending=new File(target.getParentFile(),"manifest.partial");try(FileOutputStream out=new FileOutputStream(pending)){out.write(value.toString().getBytes(StandardCharsets.UTF_8));out.getFD().sync();}
        Files.move(pending.toPath(),target.toPath(),StandardCopyOption.REPLACE_EXISTING);
    }

    private static Voice chooseVoice(TextToSpeech tts,
                                     Locale locale,
                                     String requested,
                                     boolean offlineOnly) {
        Set<Voice> voices;
        try { voices = tts.getVoices(); }
        catch (Exception ignored) { voices = null; }
        if (voices == null || voices.isEmpty()) return null;

        String requestedName = requested == null ? "" : requested.trim();
        Voice fallback = null;
        for (Voice voice : voices) {
            if (voice == null || voice.getLocale() == null) continue;
            boolean languageMatch = voice.getLocale().getLanguage().equalsIgnoreCase(locale.getLanguage());
            if (!languageMatch) continue;
            if (offlineOnly && voice.isNetworkConnectionRequired()) continue;
            if (!requestedName.isEmpty() && requestedName.equals(voice.getName())) return voice;

            if (fallback == null) fallback = voice;
            boolean exactLocale = voice.getLocale().toLanguageTag().equalsIgnoreCase(locale.toLanguageTag());
            if (exactLocale && requestedName.isEmpty()) fallback = voice;
        }
        return requestedName.isEmpty() ? fallback : null;
    }

    private static String sanitize(String raw) {
        String value = raw == null ? "voice.wav" : raw.trim();
        if (value.isEmpty()) value = "voice.wav";
        value = value.replaceAll("[^a-zA-Z0-9._-]+", "_");
        if (value.length() > 150) value = value.substring(0, 150);
        return value;
    }
}
