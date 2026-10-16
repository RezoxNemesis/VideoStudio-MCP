package com.rezoxnemesis.videostudio;

import org.json.JSONArray;
import org.json.JSONObject;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

/**
 * Real decoded-audio envelope + authored text timing for four prepared mouth shapes.
 *
 * What it can do: locate voice *activity* and silence from user-selected original
 * audio and close the mouth when no activity is measured. Text supplies approximate
 * orthographic visemes, and the waveform gates their movement by the actual signal.
 *
 * What it cannot do: identify phonemes, identify a speaker, separate music from voice,
 * perform forced alignment or attach audio to Sketch Studio's video-only MP4 export.
 */
final class SketchVoiceActivityTiming {
    private static final float MIN_ACTIVE_PEAK=.16f;
    private static final int MAX_DIALOGUE_FRAMES=720; // 30 seconds at 24 FPS
    private SketchVoiceActivityTiming(){}

    /** Non-destructive result with separate editable program and explicit evidence. */
    static JSONObject apply(JSONObject source,JSONObject original,int dialogueIndex,
                            String closed,String open,String rounded,String teeth,
                            float[] envelope)throws Exception {
        if(source==null||original==null||envelope==null)
            throw SketchDocument.bad("A prepared sketch, dialogue and decoded source-audio waveform are required");
        JSONArray dialogues=original.optJSONArray("dialogues");
        if(dialogues==null||dialogueIndex<0||dialogueIndex>=dialogues.length())
            throw SketchDocument.bad("Select an authored dialogue cue before waveform timing");
        JSONObject cue=dialogues.getJSONObject(dialogueIndex);
        int frames=(int)SketchDocument.integer(original,"frameCount",1,SketchDocument.MAX_FRAMES);
        int first=(int)SketchDocument.integer(cue,"start",0,frames-1);
        int last=(int)SketchDocument.integer(cue,"end",first,frames-1);
        int total=last-first+1;
        if(total<4||total>MAX_DIALOGUE_FRAMES||envelope.length!=total)
            throw SketchDocument.bad("The source-audio waveform must contain exactly 4..720 dialogue-frame energy samples");
        float high=0;
        for(float value:envelope){
            if(!Float.isFinite(value)||value<0||value>1)
                throw SketchDocument.bad("Decoded audio activity must consist of finite 0..1 energies");
            high=Math.max(high,value);
        }
        if(high<MIN_ACTIVE_PEAK)throw SketchDocument.bad("No speech activity was found in the selected audio window");
        // Do not mutate the authored program or assume an imported file is permanent.
        JSONObject timed=SketchAnimationVisemeTiming.apply(source,original,dialogueIndex,closed,open,rounded,teeth);
        int previousCount=original.optJSONArray("mouthCels")==null?0:original.getJSONArray("mouthCels").length();
        JSONArray generated=timed.getJSONArray("mouthCels");
        String[] ids={closed,open,rounded,teeth};
        Map<String,Integer> kinds=new HashMap<>();
        for(int i=0;i<ids.length;i++)kinds.put(ids[i],i);
        int[] proposed=new int[total];
        Arrays.fill(proposed,-1);
        for(int i=previousCount;i<generated.length();i++){
            JSONObject item=generated.getJSONObject(i);
            Integer kind=kinds.get(item.getString("objectId"));
            if(kind==null)throw SketchDocument.bad("Text timing returned unrecognized prepared mouth artwork");
            for(int f=item.getInt("start");f<=item.getInt("end");f++){
                int at=f-first;
                if(at<0||at>=total||proposed[at]!=-1)
                    throw SketchDocument.bad("Generated text mouth timing overlaps or escapes its cue");
                proposed[at]=kind;
            }
        }
        for(int kind:proposed)if(kind<0)throw SketchDocument.bad("Text timing did not cover every audio frame");
        // Three-frame RMS smoothing reduces click-like lip jitter; quiet intervals
        // still close the mouth. Energy is not a phoneme classifier.
        float[] energy=new float[total];
        int active=0,silent=0;
        boolean[] voiced=new boolean[total];
        for(int f=0;f<total;f++){
            float left=envelope[Math.max(0,f-1)],right=envelope[Math.min(total-1,f+1)];
            energy[f]=(.2f*left+.6f*envelope[f]+.2f*right);
            voiced[f]=energy[f]>=MIN_ACTIVE_PEAK;
        }
        // A lone low-energy frame inside continuous speech is smoothing noise,
        // not a convincing closed-mouth phoneme.
        for(int f=1;f+1<total;f++)if(!voiced[f]&&voiced[f-1]&&voiced[f+1])
            voiced[f]=true;
        JSONArray resultRanges=new JSONArray();
        for(int i=0;i<previousCount;i++)resultRanges.put(SketchDocument.copy(generated.getJSONObject(i)));
        int current=-1,begin=first;
        for(int f=0;f<=total;f++){
            int next=-1;
            if(f<total){
                if(voiced[f])active++;else silent++;
                next=(voiced[f]&&f<total-1)?proposed[f]:0;
            }
            if(next!=current){
                if(current>=0)resultRanges.put(new JSONObject().put("objectId",ids[current])
                        .put("start",begin).put("end",first+f-1));
                current=next;begin=first+f;
            }
        }
        timed.put("mouthCels",resultRanges);
        if(timed.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8).length>
                SketchAnimationCompiler.MAX_PROGRAM_BYTES)
            throw SketchDocument.bad("Audio-gated viseme program exceeds the saved animation budget");
        JSONObject evidence=new JSONObject()
                .put("schema","videostudio.sketch.waveform-visemes.v1")
                .put("dialogueIndex",dialogueIndex).put("startFrame",first).put("endFrame",last)
                .put("audioAnalysisFrames",total).put("voiceActivityFrames",active)
                .put("quietFrames",silent).put("activityThreshold",MIN_ACTIVE_PEAK)
                .put("mouthCelIntervals",resultRanges.length()-previousCount)
                .put("alignmentMethod","actual-decoded-audio-energy-gated-orthographic-visemes")
                .put("measuresRealAudioAmplitude",true).put("recognizedSpeechPhonemes",false)
                .put("separatesSpeechFromMusic",false).put("audioMixedIntoExport",false)
                .put("recordedSpeechPerfectlyAligned",false)
                .put("humanTimingReviewRequired",true);
        return new JSONObject().put("program",timed).put("evidence",evidence);
    }
}
