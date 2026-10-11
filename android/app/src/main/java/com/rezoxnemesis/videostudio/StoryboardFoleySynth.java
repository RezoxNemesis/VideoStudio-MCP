package com.rezoxnemesis.videostudio;

import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Actually renders a light, synced, license-free PCM sound-effects track.
 * Events follow the REAL source-keyframe action timings (not arbitrary
 * dramatic loops). Separate Media3 audio lane can mix this WAV with
 * user-supplied music/narration later.
 *
 * This is simple on-device Foley synthesis, not generated orchestral music
 * or neural text-to-speech. Completely offline and reproducible.
 */
public final class StoryboardFoleySynth {
    public static final int SAMPLE_RATE=44100;
    private static final int MAX_SECONDS=45;
    public static final class Cue {
        public final StoryboardMotionGrammar.Action action;
        public final StoryboardMotionGrammar.SceneKind scene;
        public final float seconds,energy;
        public Cue(StoryboardMotionGrammar.Action action,
                   StoryboardMotionGrammar.SceneKind scene,float seconds,float energy){
            if(action==null||scene==null||!Float.isFinite(seconds)||
                    seconds<0||!Float.isFinite(energy)||energy<0||energy>1)
                throw new IllegalArgumentException("Invalid timed soundtrack cue");
            this.action=action;this.scene=scene;
            this.seconds=seconds;this.energy=energy;
        }
    }
    private StoryboardFoleySynth(){}

    public static final class Result {
        public final File file;
        public final int cues;
        public final long durationMs;
        Result(File file,int cues,long durationMs){
            this.file=file;this.cues=cues;this.durationMs=durationMs;
        }
    }

    public static List<Cue> cues(StoryboardBatchPlanner.Batch batch,int fps) {
        if(batch==null||fps<12||fps>60)
            throw new IllegalArgumentException("Invalid storyboard Foley timeline");
        ArrayList<Cue> events=new ArrayList<>();
        int frames=0;
        for(StoryboardBatchPlanner.Shot shot:batch.shots){
            float time=(frames+shot.frames*.62f)/fps;
            float intensity=(shot.action==StoryboardMotionGrammar.Action.IMPACT
                    ||shot.action==StoryboardMotionGrammar.Action.EXPLODE)?1f:.65f;
            events.add(new Cue(shot.action,shot.scene,time,intensity));
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
                float center=(frameClock+shot.frames*.62f)/plan.fps;
                float energy=(shot.action==StoryboardMotionGrammar.Action.IMPACT
                        ||shot.action==StoryboardMotionGrammar.Action.EXPLODE)?1f:.65f;
                result.add(new Cue(shot.action,shot.scene,center,energy));
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
        if(project==null||destination==null||!Float.isFinite(gain)
                ||gain<=0||gain>1)
            throw new IllegalArgumentException("Provide a safe project and soundtrack gain");
        if(!project.studioLayers.isEmpty())
            throw new IllegalArgumentException("Project already has audio lanes");
        long length=project.outputDurationMs();
        Result audio=render(destination,length,cues);
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
                .put("isOrchestralScore",false)
                .put("noExternalService",true);
        project.assets.add(asset);
        StudioLayer lane=new StudioLayer();
        lane.type=StudioLayer.AUDIO;
        lane.assetId=asset.id;
        lane.startMs=0;
        lane.durationMs=length;
        lane.sourceInMs=0;
        lane.gain=gain;
        StudioLayerContract.validate(project,lane);
        project.studioLayers.add(lane);
        return audio;
    }

    public static Result render(File destination,long durationMs,List<Cue> cues)
            throws Exception {
        if(destination==null||durationMs<100||durationMs>MAX_SECONDS*1000L||
                cues==null||cues.size()>160)
            throw new IllegalArgumentException("Bounded Foley track and selected output required");
        int samples=(int)Math.ceil(durationMs*SAMPLE_RATE/1000d);
        float[] values=new float[samples];
        Random random=new Random(462193);
        float wind=0;
        for(int i=0;i<samples;i++){
            double t=i/(double)SAMPLE_RATE;
            wind=wind*.975f+(random.nextFloat()*2-1)*.025f;
            // Restrained low-level shared atmosphere. Music is never
            // fabricated or passed off as artist-selected BGM.
            values[i]=(float)(.011*Math.sin(2*Math.PI*68*t)+.024*wind);
        }
        for(Cue cue:cues){
            int center=(int)(cue.seconds*SAMPLE_RATE);
            switch(cue.action){
                case IMPACT:case STRIKE:case EXPLODE:
                    burst(values,center,.42f,660,1820,.22f*cue.energy,9281);
                    if(cue.scene==StoryboardMotionGrammar.SceneKind.ACTION)
                        rumble(values,center,.55f,.17f*cue.energy);
                    break;
                case WALK:case RUN:case BOUNCE:
                    burst(values,center,.13f,115,330,.11f*cue.energy,121);
                    break;
                case LEAP:case DODGE:case FALL:case RECOIL:
                    swoosh(values,center,.29f,.16f*cue.energy);
                    break;
                case SPLASH:
                    burst(values,center,.38f,55,480,.18f*cue.energy,199);
                    break;
                case REVEAL:case PULSE:case ROTATE:
                    chime(values,center,.42f,.11f*cue.energy);
                    break;
                case PUSH:case PULL:case PAN:case ORBIT:case DRIFT:
                    swoosh(values,center,.24f,.06f*cue.energy);
                    break;
                default:break;
            }
        }
        float loudest=0;
        for(float v:values)loudest=Math.max(loudest,Math.abs(v));
        float gain=loudest>.90f?.90f/loudest:1f;
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
        return new Result(destination,cues.size(),durationMs);
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
    private static void swoosh(float[] out,int center,float seconds,float volume){
        int count=(int)(seconds*SAMPLE_RATE),start=center-count/2;
        Random random=new Random(center+37);
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
