package com.rezoxnemesis.videostudio;

import android.content.Context;
import android.net.Uri;
import android.os.Bundle;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.speech.tts.Voice;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
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
                                 Progress progress) throws Exception {
        if (project == null) throw new IllegalArgumentException("Project is required");
        String spoken = text == null ? "" : text.trim();
        if (spoken.isEmpty()) throw new IllegalArgumentException("Narration text is required");
        if (spoken.length() > 12000) throw new IllegalArgumentException("Narration text exceeds the local synthesis limit");
        final Progress callback = progress == null ? (p, d) -> {} : progress;

        callback.onProgress(3, "Initialising local Android speech engine");
        CountDownLatch initLatch = new CountDownLatch(1);
        AtomicInteger initStatus = new AtomicInteger(Integer.MIN_VALUE);
        TextToSpeech tts = new TextToSpeech(context, status -> {
            initStatus.set(status);
            initLatch.countDown();
        });
        File generationDirectory=null,outputFile=null;boolean completed=false;
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

            File parent = new File(new File(workspace.projectRoot(project.id), "audio"), "voices");
            if (!parent.isDirectory() && !parent.mkdirs() && !parent.isDirectory()) {
                throw new IllegalStateException("Could not create narration workspace");
            }
            String generationId=UUID.randomUUID().toString();File dir=new File(parent,generationId);
            if(!dir.mkdir())throw new IllegalStateException("Could not create an immutable narration generation");
            generationDirectory=dir;
            String safeName = sanitize(fileName == null || fileName.trim().isEmpty()
                    ? "voice_" + System.currentTimeMillis() + ".wav"
                    : fileName);
            if (!safeName.toLowerCase(Locale.US).endsWith(".wav")) safeName += ".wav";
            File target = new File(dir, safeName);
            if(!target.createNewFile())throw new IllegalStateException("Narration generation output already exists");
            outputFile=target;

            CountDownLatch synthLatch = new CountDownLatch(1);
            AtomicReference<String> error = new AtomicReference<>();
            String utteranceId = "vs_" + UUID.randomUUID();
            tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                @Override
                public void onStart(String id) {
                    if (utteranceId.equals(id)) {
                        try { callback.onProgress(18, "Synthesising narration locally"); }
                        catch (Exception ignored) {}
                    }
                }

                @Override
                public void onDone(String id) {
                    if (utteranceId.equals(id)) synthLatch.countDown();
                }

                @Override
                @SuppressWarnings("deprecation")
                public void onError(String id) {
                    if (utteranceId.equals(id)) {
                        error.set("Local speech synthesis failed");
                        synthLatch.countDown();
                    }
                }

                @Override
                public void onError(String id, int errorCode) {
                    if (utteranceId.equals(id)) {
                        error.set("Local speech synthesis failed with code " + errorCode);
                        synthLatch.countDown();
                    }
                }
            });

            callback.onProgress(12, "Preparing local voice");
            Bundle params = new Bundle();
            int queued = tts.synthesizeToFile(spoken, params, target, utteranceId);
            if (queued != TextToSpeech.SUCCESS) {
                throw new IllegalStateException("Local speech engine rejected the synthesis request");
            }

            long deadline = System.currentTimeMillis() + Math.max(45_000L, Math.min(240_000L, spoken.length() * 95L));
            while (!synthLatch.await(500, TimeUnit.MILLISECONDS)) {
                if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
                if (System.currentTimeMillis() >= deadline) {
                    throw new IllegalStateException("Local speech synthesis timed out");
                }
            }
            if (error.get() != null) throw new IllegalStateException(error.get());
            if (!target.isFile() || target.length() <= 44) {
                throw new IllegalStateException("Local speech engine produced no usable audio");
            }
            // The installed engine has finished writing this generation. Make
            // its bytes durable without truncating or replacing an older file.
            try (FileOutputStream durable = new FileOutputStream(target, true)) {
                durable.getFD().sync();
            }

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
            out.put("generationId",generationId);
            out.put("workspaceRelativePath", "audio/voices/"+generationId+"/" + target.getName());
            completed=true;
            callback.onProgress(100, "Narration audio ready");
            return out;
        } finally {
            try { tts.stop(); } catch (Exception ignored) {}
            try { tts.shutdown(); } catch (Exception ignored) {}
            if(!completed&&generationDirectory!=null){if(outputFile!=null)outputFile.delete();generationDirectory.delete();}
        }
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
