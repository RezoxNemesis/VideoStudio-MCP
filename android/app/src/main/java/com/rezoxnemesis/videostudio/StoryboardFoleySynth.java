package com.rezoxnemesis.videostudio;

import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.Collections;
import java.util.HashSet;

/**
 * Renders a light, license-free PCM track from captured or authored cue clocks.
 * Synthesized event stems are not decoded-export synchronization proof.
 * A separate Media3 audio lane can mix this WAV with
 * user-supplied music/narration later.
 *
 * This is simple on-device Foley synthesis, not generated orchestral music
 * or neural text-to-speech. Completely offline and reproducible.
 */
public final class StoryboardFoleySynth {
    public static final int SAMPLE_RATE=44100;
    private static final int MAX_SECONDS=120;
    public static final class Cue {
        public final StoryboardMotionGrammar.Action action;
        public final StoryboardMotionGrammar.SceneKind scene;
        public final float seconds,energy;
        public final String eventId;
        public final long timestampUs;
        public final StoryboardMotionEvidence.Origin timingOrigin;
        public Cue(StoryboardMotionGrammar.Action action,
                   StoryboardMotionGrammar.SceneKind scene,float seconds,float energy){
            this("authored:"+(action==null?"unknown":action.name())+":"+Math.round(seconds*1_000_000d),
                    action,scene,legacyClock(seconds),energy,StoryboardMotionEvidence.Origin.NONE);
        }
        public Cue(String eventId,StoryboardMotionGrammar.Action action,StoryboardMotionGrammar.SceneKind scene,
                   long timestampUs,float energy,StoryboardMotionEvidence.Origin timingOrigin){
            if(action==null||scene==null||timestampUs<0||timestampUs>MAX_SECONDS*1_000_000L||timingOrigin==null
                    ||!Float.isFinite(energy)||energy<=0||energy>1||eventId==null||!eventId.matches("[A-Za-z0-9_.:-]{1,180}"))
                throw new IllegalArgumentException("Invalid timed soundtrack cue");
            this.action=action;this.scene=scene;
            this.seconds=timestampUs/1_000_000f;this.energy=energy;this.eventId=eventId;
            this.timestampUs=timestampUs;this.timingOrigin=timingOrigin;
        }
    }
    /** Captured native events have their own clock; no fixed shot phase is substituted. */
    public static final class CapturedEvent {
        public final Cue cue;
        public CapturedEvent(String eventId,StoryboardMotionGrammar.Action action,StoryboardMotionGrammar.SceneKind scene,
                             long timestampUs,float energy,StoryboardMotionEvidence.Origin origin){
            if(origin==null||origin==StoryboardMotionEvidence.Origin.NONE)
                throw new IllegalArgumentException("Captured audio timing requires an explicit native-prediction or decoded-event origin");
            cue=new Cue(eventId,action,scene,timestampUs,energy,origin);
        }
    }
    public static List<Cue> cuesFromCapturedEvents(List<CapturedEvent> captured,long durationUs){
        if(captured==null||captured.isEmpty()||captured.size()>160||durationUs<100_000||durationUs>MAX_SECONDS*1_000_000L)
            throw new IllegalArgumentException("Bounded captured event list and actual output clock required");
        ArrayList<Cue> cues=new ArrayList<>();HashSet<String> ids=new HashSet<>();
        for(CapturedEvent event:captured){
            if(event==null||!ids.add(event.cue.eventId)||event.cue.timestampUs>=durationUs||!audibleAction(event.cue.action))
                throw new IllegalArgumentException("Captured event is duplicate, inactive or outside the output clock");
            cues.add(event.cue);
        }
        return Collections.unmodifiableList(cues);
    }
    public static final class EventReceipt {
        public final String eventId;
        public final long cueTimestampUs,onsetSampleIndex,peakSampleIndex,onsetTimestampUs,peakTimestampUs;
        public final int peakPcmMagnitude;
        public final StoryboardMotionEvidence.Origin timingOrigin;
        EventReceipt(Cue cue,long onset,long peak,int magnitude){
            eventId=cue.eventId;cueTimestampUs=cue.timestampUs;onsetSampleIndex=onset;peakSampleIndex=peak;
            onsetTimestampUs=Math.round(onset*1_000_000d/SAMPLE_RATE);peakTimestampUs=Math.round(peak*1_000_000d/SAMPLE_RATE);
            peakPcmMagnitude=magnitude;timingOrigin=cue.timingOrigin;
        }
        public org.json.JSONObject toJson() throws Exception{
            return new org.json.JSONObject().put("eventId",eventId).put("cueTimestampUs",cueTimestampUs)
                    .put("onsetSampleIndex",onsetSampleIndex).put("peakSampleIndex",peakSampleIndex)
                    .put("onsetTimestampUs",onsetTimestampUs).put("peakTimestampUs",peakTimestampUs)
                    .put("sampleRate",SAMPLE_RATE).put("peakPcmMagnitude",peakPcmMagnitude)
                    .put("timingOrigin",timingOrigin.name()).put("waveformEvidence","SYNTHESIZED_EVENT_STEM")
                    .put("decodedExportSynchronizationVerified",false);
        }
    }
    private StoryboardFoleySynth(){}

    public static final class Result {
        public final File file;
        public final int cues;
        public final long durationMs;
        public final List<EventReceipt> events;
        public final boolean ambienceOnly,eventSynchronizationVerified=false,decodedExportSynchronizationVerified=false;
        public final String timingScope;
        /** The generated waveform is procedural; per-event visual timing origins remain on receipts. */
        public final StoryboardMotionEvidence.Origin origin=StoryboardMotionEvidence.Origin.NONE;
        Result(File file,List<EventReceipt> events,long durationMs,boolean ambienceOnly){
            this.file=file;this.events=Collections.unmodifiableList(new ArrayList<>(events));this.cues=events.size();this.durationMs=durationMs;
            this.ambienceOnly=ambienceOnly;
            timingScope=ambienceOnly?"PROCEDURAL_AMBIENCE_ONLY":"SYNTHESIZED_PCM_EVENT_STEMS";
        }
        public org.json.JSONObject timingEvidence() throws Exception{
            org.json.JSONArray records=new org.json.JSONArray();for(EventReceipt event:events)records.put(event.toJson());
            return new org.json.JSONObject().put("events",records).put("audibleEventCount",cues)
                    .put("ambienceOnly",ambienceOnly).put("origin",origin.name())
                    .put("eventSynchronizationVerified",eventSynchronizationVerified)
                    .put("decodedExportSynchronizationVerified",decodedExportSynchronizationVerified).put("timingScope",timingScope);
        }
    }

    public static List<Cue> cues(StoryboardBatchPlanner.Batch batch,int fps) {
        if(batch==null||fps<12||fps>60)
            throw new IllegalArgumentException("Invalid storyboard Foley timeline");
        ArrayList<Cue> events=new ArrayList<>();
        int frames=0;
        for(StoryboardBatchPlanner.Shot shot:batch.shots){
            if(shot.action==StoryboardMotionGrammar.Action.IMPACT)
                throw new IllegalArgumentException("Clash audio requires captured blade-contact event timestamps, not an inferred shot phase");
            float time=(frames+shot.frames*.5f)/fps;
            float intensity=(shot.action==StoryboardMotionGrammar.Action.IMPACT
                    ||shot.action==StoryboardMotionGrammar.Action.EXPLODE)?1f:.65f;
            if(audibleAction(shot.action))events.add(new Cue(shot.action,shot.scene,time,intensity));
            frames+=shot.frames;
        }
        return events;
    }

    /** Entire film sound cues share the same animation time base. */
    public static List<Cue> cues(StoryboardBatchPlanner.Plan plan) {
        if(plan==null)throw new IllegalArgumentException("Storyboard plan required");
        ArrayList<Cue> result=new ArrayList<>();
        int frameClock=1; // first actual still source frame, never duplicated
        for(StoryboardBatchPlanner.Batch batch:plan.batches){
            for(StoryboardBatchPlanner.Shot shot:batch.shots){
                if(shot.action==StoryboardMotionGrammar.Action.IMPACT)
                    throw new IllegalArgumentException("Clash audio requires captured blade-contact event timestamps, not an inferred shot phase");
                float center=(frameClock+shot.frames*.5f)/plan.fps;
                float energy=(shot.action==StoryboardMotionGrammar.Action.IMPACT
                        ||shot.action==StoryboardMotionGrammar.Action.EXPLODE)?1f:.65f;
                if(audibleAction(shot.action))result.add(new Cue(shot.action,shot.scene,center,energy));
                frameClock+=shot.frames;
            }
        }
        return result;
    }

    /**
     * One source-owned soundtrack for any number of contiguous 20-frame
     * storyboard batches; uses the existing native Media3 audio lane.
     */
    public static Result attach(ProjectStore.Project project,File destination,
                                List<Cue> cues,float gain) throws Exception {
        validateAttachment(project,destination,gain);
        return attachRendered(project,render(destination,project.outputDurationMs(),cues),gain);
    }

    /** Registers explicit ambience through the same validated native audio lane as event Foley. */
    public static Result attachAmbience(ProjectStore.Project project,File destination,float gain) throws Exception {
        validateAttachment(project,destination,gain);
        return attachRendered(project,renderAmbience(destination,project.outputDurationMs()),gain);
    }

    private static void validateAttachment(ProjectStore.Project project,File destination,float gain) {
        if(project==null||destination==null||!Float.isFinite(gain)
                ||gain<=0||gain>1)
            throw new IllegalArgumentException("Provide a safe project and soundtrack gain");
        if(!project.studioLayers.isEmpty())
            throw new IllegalArgumentException("Project already has audio lanes");
    }

    private static Result attachRendered(ProjectStore.Project project,Result audio,float gain) throws Exception {
        File destination=audio.file;
        ProjectStore.Asset asset=new ProjectStore.Asset();
        asset.id=java.util.UUID.randomUUID().toString();
        asset.uri=android.net.Uri.fromFile(destination).toString();
        asset.mime="audio/wav";
        asset.name=destination.getName();
        asset.role="storyboard_generated_foley";
        asset.generated=true;
        asset.seekable=true;
        asset.persistedReadAccess=true;
        asset.durationMs=audio.durationMs;
        asset.sizeBytes=destination.length();
        asset.generationMetadata=new org.json.JSONObject()
                .put("provider","videostudio.local-foley-v1")
                .put("events",audio.cues)
                .put("ambienceOnly",audio.ambienceOnly).put("actionAudio",audio.cues>0)
                .put("timingEvidence",audio.timingEvidence())
                .put("synchronizationVerified",false)
                .put("isOrchestralScore",false)
                .put("noExternalService",true);
        project.assets.add(asset);
        StudioLayer lane=new StudioLayer();
        lane.type=StudioLayer.AUDIO;
        lane.assetId=asset.id;
        lane.startMs=0;
        lane.durationMs=audio.durationMs;
        lane.sourceInMs=0;
        lane.gain=gain;
        StudioLayerContract.validate(project,lane);
        project.studioLayers.add(lane);
        return audio;
    }

    public static Result render(File destination,long durationMs,List<Cue> cues)
            throws Exception {
        return renderTrack(destination,durationMs,cues,false);
    }

    /** Explicit environmental PCM with zero events; never a contact/footstep synchronization certificate. */
    public static Result renderAmbience(File destination,long durationMs) throws Exception {
        return renderTrack(destination,durationMs,Collections.emptyList(),true);
    }

    private static Result renderTrack(File destination,long durationMs,List<Cue> cues,boolean ambienceOnly)
            throws Exception {
        if(destination==null||durationMs<100||durationMs>MAX_SECONDS*1000L||
                cues==null||(ambienceOnly?!cues.isEmpty():cues.isEmpty())||cues.size()>160)
            throw new IllegalArgumentException("Bounded Foley track and selected output required");
        List<Cue> accepted=new ArrayList<>(cues);HashSet<String> ids=new HashSet<>();
        for(Cue cue:accepted){
            if(cue==null||!ids.add(cue.eventId)||cue.timestampUs>=durationMs*1000L||!audibleAction(cue.action))
                throw new IllegalArgumentException("Foley events must be distinct, audible and inside the captured output clock");
        }
        int samples=(int)Math.ceil(durationMs*SAMPLE_RATE/1000d);
        float[] values=new float[samples];
        Random random=new Random(462193);
        float wind=0;
        for(int i=0;i<samples;i++){
            if((i&1023)==0&&Thread.currentThread().isInterrupted())throw new InterruptedException();
            double t=i/(double)SAMPLE_RATE;
            wind=wind*.975f+(random.nextFloat()*2-1)*.025f;
            // Restrained low-level shared atmosphere. Music is never
            // fabricated or passed off as artist-selected BGM.
            values[i]=(float)(.011*Math.sin(2*Math.PI*68*t)+.024*wind);
        }
        for(Cue cue:accepted){
            if(Thread.currentThread().isInterrupted())throw new InterruptedException();
            Stem stem=stem(cue);
            for(int offset=0;offset<stem.values.length;offset++){
                int sample=stem.start+offset;if(sample>=0&&sample<values.length)values[sample]+=stem.values[offset];
            }
        }
        float loudest=0;
        for(float v:values)loudest=Math.max(loudest,Math.abs(v));
        float gain=loudest>.90f?.90f/loudest:1f;
        ArrayList<EventReceipt> receipts=new ArrayList<>();
        for(Cue cue:accepted){
            if(Thread.currentThread().isInterrupted())throw new InterruptedException();
            // Recreate a bounded deterministic stem after global mix gain is
            // known. Atmosphere and another event cannot prove this cue audible.
            Stem stem=stem(cue);long first=-1,peak=-1;int magnitude=0;
            for(int offset=0;offset<stem.values.length;offset++){
                int sample=stem.start+offset;if(sample<0||sample>=values.length)continue;
                int pcm=Math.abs(Math.round(Math.max(-1,Math.min(1,stem.values[offset]*gain))*32760f));
                if(pcm>0&&first<0)first=sample;
                if(pcm>magnitude){magnitude=pcm;peak=sample;}
            }
            if(first<0||peak<0||magnitude==0)
                throw new IllegalArgumentException("An inactive or sub-PCM event cannot be counted as audible Foley");
            receipts.add(new EventReceipt(cue,first,peak,magnitude));
        }
        File parent=destination.getParentFile();
        if(parent==null||(!parent.isDirectory()&&!parent.mkdirs()))
            throw new IllegalStateException("Unable to create protected Foley workspace");
        File staged=new File(parent,destination.getName()+".partial");
        try(FileOutputStream out=new FileOutputStream(staged)){
            writeRiff(out,samples);
            byte[] chunk=new byte[8192];
            int at=0;
            for(float value:values){
                if(Thread.currentThread().isInterrupted())throw new InterruptedException();
                short pcm=(short)Math.round(Math.max(-1,Math.min(1,value*gain))*32760f);
                chunk[at++]=(byte)(pcm&255);
                chunk[at++]=(byte)((pcm>>8)&255);
                if(at>=chunk.length){out.write(chunk,0,at);at=0;}
            }
            if(at>0)out.write(chunk,0,at);
            out.getFD().sync();
        }catch(Exception fail){staged.delete();throw fail;}
        if(staged.length()!=44L+samples*2L||!staged.renameTo(destination)){
            staged.delete();
            throw new IllegalStateException("Synthesized Foley audio could not be committed");
        }
        return new Result(destination,receipts,durationMs,ambienceOnly);
    }

    private static long legacyClock(float seconds){
        if(!Float.isFinite(seconds)||seconds<0||seconds>MAX_SECONDS)
            throw new IllegalArgumentException("Finite bounded authored cue clock required");
        return Math.round(seconds*1_000_000d);
    }
    private static boolean audibleAction(StoryboardMotionGrammar.Action action){
        if(action==null)return false;
        switch(action){
            case IMPACT:case STRIKE:case EXPLODE:case WALK:case RUN:case BOUNCE:
            case LEAP:case DODGE:case FALL:case RECOIL:case SPLASH:case REVEAL:case PULSE:case ROTATE:
            case PUSH:case PULL:case PAN:case ORBIT:case DRIFT:return true;
            default:return false;
        }
    }
    private static final class Stem{
        final int start;final float[] values;
        Stem(int start,int count){this.start=start;values=new float[count];}
    }
    private static Stem stem(Cue cue){
        int center=(int)((cue.timestampUs*SAMPLE_RATE+500_000)/1_000_000),length;
        Stem stem;
        switch(cue.action){
            case IMPACT:case EXPLODE:
                length=(int)(.55f*SAMPLE_RATE);stem=new Stem(center,length);
                burst(stem.values,0,.42f,660,1820,.22f*cue.energy,9281+center);
                if(cue.scene==StoryboardMotionGrammar.SceneKind.ACTION)rumble(stem.values,0,.55f,.17f*cue.energy);
                return stem;
            case WALK:case RUN:case BOUNCE:
                stem=new Stem(center,(int)(.13f*SAMPLE_RATE));burst(stem.values,0,.13f,115,330,.11f*cue.energy,121+center);return stem;
            case STRIKE:case LEAP:case DODGE:case FALL:case RECOIL:
                length=(int)(.29f*SAMPLE_RATE);stem=new Stem(center-length/2,length);
                swoosh(stem.values,length/2,.29f,.16f*cue.energy,center+37);return stem;
            case SPLASH:
                stem=new Stem(center,(int)(.38f*SAMPLE_RATE));burst(stem.values,0,.38f,55,480,.18f*cue.energy,199+center);return stem;
            case REVEAL:case PULSE:case ROTATE:
                stem=new Stem(center,(int)(.42f*SAMPLE_RATE));chime(stem.values,0,.42f,.11f*cue.energy);return stem;
            case PUSH:case PULL:case PAN:case ORBIT:case DRIFT:
                length=(int)(.24f*SAMPLE_RATE);stem=new Stem(center-length/2,length);
                swoosh(stem.values,length/2,.24f,.06f*cue.energy,center+37);return stem;
            default:throw new IllegalArgumentException("Action has no audible synthesized event");
        }
    }

    private static void burst(float[] out,int start,float seconds,
                              double low,double high,float power,int seed){
        Random rng=new Random(seed+start);
        int total=(int)(seconds*SAMPLE_RATE);
        float filtered=0;
        for(int k=0;k<total;k++){
            int p=start+k;if(p<0||p>=out.length)continue;
            double phase=k/(double)SAMPLE_RATE,decay=Math.exp(-phase*11);
            filtered=.78f*filtered+.22f*(rng.nextFloat()*2-1);
            double f=low+(high-low)*Math.exp(-phase*8);
            out[p]+=(float)(power*decay*
                    (.68*Math.sin(2*Math.PI*f*phase)+.32*filtered));
        }
    }
    private static void rumble(float[] out,int start,float seconds,float amplitude){
        int count=(int)(seconds*SAMPLE_RATE);
        for(int k=0;k<count;k++){
            int p=start+k;if(p<0||p>=out.length)continue;
            double t=k/(double)SAMPLE_RATE;
            out[p]+=(float)(amplitude*Math.exp(-t*10)
                    *Math.sin(2*Math.PI*78*t));
        }
    }
    private static void swoosh(float[] out,int center,float seconds,float volume,int seed){
        int count=(int)(seconds*SAMPLE_RATE),start=center-count/2;
        Random random=new Random(seed);
        float filtered=0;
        for(int k=0;k<count;k++){
            int p=start+k;if(p<0||p>=out.length)continue;
            float phase=k/(float)Math.max(1,count-1);
            filtered=.84f*filtered+.16f*(random.nextFloat()*2-1);
            float envelope=(float)Math.sin(Math.PI*phase);
            out[p]+=volume*envelope*filtered;
        }
    }
    private static void chime(float[] out,int start,float seconds,float volume){
        int count=(int)(seconds*SAMPLE_RATE);
        for(int k=0;k<count;k++){
            int p=start+k;if(p<0||p>=out.length)continue;
            double t=k/(double)SAMPLE_RATE;
            out[p]+=(float)(volume*Math.exp(-t*8)*
                    (.7*Math.sin(2*Math.PI*740*t)+
                     .3*Math.sin(2*Math.PI*1110*t)));
        }
    }
    private static void fourcc(FileOutputStream out,String text)throws Exception{
        for(int i=0;i<4;i++)out.write((byte)text.charAt(i));
    }
    private static void le(FileOutputStream out,long n,int bytes)throws Exception{
        for(int i=0;i<bytes;i++)out.write((int)((n>>(8*i))&255));
    }
    private static void writeRiff(FileOutputStream out,int samples)throws Exception{
        fourcc(out,"RIFF");le(out,36L+samples*2L,4);
        fourcc(out,"WAVE");fourcc(out,"fmt ");
        le(out,16,4);le(out,1,2);le(out,1,2);
        le(out,SAMPLE_RATE,4);le(out,SAMPLE_RATE*2L,4);
        le(out,2,2);le(out,16,2);
        fourcc(out,"data");le(out,samples*2L,4);
    }
}
