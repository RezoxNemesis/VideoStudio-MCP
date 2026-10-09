package com.rezoxnemesis.videostudio;

import android.content.Context;
import android.media.MediaFormat;
import android.media.MediaExtractor;
import android.media.MediaMuxer;
import android.media.MediaCodec;
import android.net.Uri;
import java.io.File;
import java.io.InterruptedIOException;
import java.io.IOException;
import java.io.FileDescriptor;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.function.BooleanSupplier;
import org.json.JSONObject;

/** Lossless join of independently verified video windows and one continuous AAC track. */
final class SegmentMediaMuxer {
    static final class IncompatibleConfigurationException extends IllegalArgumentException {IncompatibleConfigurationException(String message){super(message);}}
    static final class UnsupportedMuxRouteException extends IllegalArgumentException {UnsupportedMuxRouteException(String message){super(message);}}
    static void supportedAudioPlatform(int sdk,long firstSampleUs){if(sdk<30&&firstSampleUs<0)throw new UnsupportedMuxRouteException("This Android version requires an alternative lossless AAC mux route for encoder preroll; verified checkpoints are retained");}
    static long audioTimestamp(long sampleUs,long endUs,long previousUs,MediaFormat format){if(endUs<=0||sampleUs < -audioPrerollUs(format)||sampleUs>=endUs||sampleUs<=previousUs)throw new IllegalArgumentException("AAC presentation timestamp is outside the program or not strictly increasing");return sampleUs;}
    static boolean hasSample(long sampleSize){return sampleSize>=0;}
    static MediaCodec.BufferInfo audioEndOfTrack(long plannedEndUs,long firstUs,long previousUs){try{long shift=firstUs<0?Math.negateExact(firstUs):0;if(previousUs<firstUs)throw new IllegalArgumentException("AAC final packet precedes its first packet");return endOfTrack(audioMuxEndUs(plannedEndUs,firstUs),Math.addExact(previousUs,shift));}catch(ArithmeticException overflow){throw new IllegalArgumentException("AAC final timestamp overflow",overflow);}}
    /** Android's MPEG4Writer shifts negative packets internally, then restores time with an edit list. */
    static long audioMuxEndUs(long plannedEndUs,long firstSampleUs){try{if(plannedEndUs<=0)throw new IllegalArgumentException("Invalid AAC program end");return Math.addExact(plannedEndUs,firstSampleUs<0?Math.negateExact(firstSampleUs):0);}catch(ArithmeticException overflow){throw new IllegalArgumentException("AAC end timestamp overflow",overflow);}}
    private static long audioPrerollUs(MediaFormat format){double rate=number(format,MediaFormat.KEY_SAMPLE_RATE,44100);if(rate<=0||!Double.isFinite(rate))throw new IllegalArgumentException("Invalid AAC sample rate");int delay=format.containsKey(MediaFormat.KEY_ENCODER_DELAY)?format.getInteger(MediaFormat.KEY_ENCODER_DELAY):2048;if(delay<0||delay>rate)throw new IllegalArgumentException("Invalid AAC encoder delay");return (long)Math.ceil(Math.max(2048,delay)*1_000_000d/rate)+1000;}
    private static final int MAX_SAMPLE=32*1024*1024;
    static OutputWorkspace prepareOutput(ProjectStore.Project originals,File output)throws Exception{
        if(originals==null)throw new IllegalArgumentException("Original source graph is required for join output protection");NativeRenderEngine.protectOriginals(originals,output);return new OutputWorkspace(output);
    }
    static MediaCodec.BufferInfo endOfTrack(long plannedEndUs,long previousUs){if(plannedEndUs<=0||previousUs<0||plannedEndUs<=previousUs)throw new IllegalArgumentException("Invalid final sample duration");MediaCodec.BufferInfo eos=new MediaCodec.BufferInfo();eos.set(0,0,plannedEndUs,MediaCodec.BUFFER_FLAG_END_OF_STREAM);return eos;}
    static void trackDuration(long actualUs,long plannedUs){if(actualUs<=0||plannedUs<=0||Math.abs(actualUs-plannedUs)>1000)throw new IllegalArgumentException("Joined track duration does not match the planned program end");}
    static void interval(MediaFormat format,long plannedUs){if(!format.containsKey(MediaFormat.KEY_DURATION)||plannedUs<=0||Math.abs(PlayableMediaVerifier.presentationDurationUs(format)-plannedUs)>rounding(format))throw new IllegalArgumentException("Checkpoint media duration does not span its planned interval");}
    static void initialTime(long sampleUs,MediaFormat format){long minimum=format.getString(MediaFormat.KEY_MIME).startsWith("audio/")?-audioPrerollUs(format):0;if(sampleUs<minimum||sampleUs>rounding(format))throw new IllegalArgumentException("Checkpoint first sample has an unplanned leading gap: mime="+format.getString(MediaFormat.KEY_MIME)+", firstUs="+sampleUs+", allowedUs="+rounding(format));}
    private static long rounding(MediaFormat format){boolean video=format.getString(MediaFormat.KEY_MIME).startsWith("video/");double rate=number(format,video?MediaFormat.KEY_FRAME_RATE:MediaFormat.KEY_SAMPLE_RATE,video?30:44100);if(rate<=0||!Double.isFinite(rate))throw new IllegalArgumentException("Invalid checkpoint sample rate");return Math.min(100_000,(long)Math.ceil((video?1_000_000d:1_024_000_000d)/rate)+1000);}
    static void compatible(MediaFormat first,MediaFormat next){compatible(first,next,false);}
    private static void compatible(MediaFormat first,MediaFormat next,boolean fractionalAverage){
        if(!"video/avc".equals(first.getString(MediaFormat.KEY_MIME))||!"video/avc".equals(next.getString(MediaFormat.KEY_MIME)))throw new IllegalArgumentException("Segment join requires matching AVC video tracks");
        for(String key:new String[]{MediaFormat.KEY_WIDTH,MediaFormat.KEY_HEIGHT})if(!first.containsKey(key)||!next.containsKey(key)||first.getInteger(key)<=0||first.getInteger(key)!=next.getInteger(key))throw incompatible(key);
        for(String key:new String[]{MediaFormat.KEY_FRAME_RATE,MediaFormat.KEY_ROTATION,MediaFormat.KEY_PROFILE,MediaFormat.KEY_LEVEL,MediaFormat.KEY_COLOR_STANDARD,MediaFormat.KEY_COLOR_RANGE,MediaFormat.KEY_COLOR_TRANSFER}){
            if(fractionalAverage&&MediaFormat.KEY_FRAME_RATE.equals(key))continue;
            if(first.containsKey(key)!=next.containsKey(key)){
                if(MediaFormat.KEY_ROTATION.equals(key)&&number(first,key,0)==number(next,key,0))continue;
                throw incompatible(key);
            }
            if(first.containsKey(key)&&Double.compare(number(first,key,0),number(next,key,0))!=0)throw incompatible(key);
        }
        for(String key:new String[]{"csd-0","csd-1"})if(!first.containsKey(key)||!next.containsKey(key)||!sameBytes(first.getByteBuffer(key),next.getByteBuffer(key),true))throw incompatible(key);
        for(String key:new String[]{"csd-2",MediaFormat.KEY_HDR_STATIC_INFO})if(first.containsKey(key)!=next.containsKey(key)||(first.containsKey(key)&&!sameBytes(first.getByteBuffer(key),next.getByteBuffer(key),false)))throw incompatible(key);
    }
    static void compatibleWindow(MediaFormat first,MediaFormat next,long durationUs,boolean terminal,boolean oneSyncSample){compatible(first,next,fractionalAverage(first,next,durationUs,terminal,oneSyncSample));}
    private static boolean fractionalAverage(MediaFormat first,MediaFormat next,long durationUs,boolean terminal,boolean oneSyncSample){
        if(!terminal||!oneSyncSample||durationUs<=0||!first.containsKey(MediaFormat.KEY_FRAME_RATE)||!next.containsKey(MediaFormat.KEY_FRAME_RATE)||!next.containsKey(MediaFormat.KEY_DURATION))return false;
        double fps=number(first,MediaFormat.KEY_FRAME_RATE,0),reported=number(next,MediaFormat.KEY_FRAME_RATE,0);long actual=next.getLong(MediaFormat.KEY_DURATION);
        return Double.isFinite(fps)&&fps>=1&&fps<=240&&durationUs<1_000_000d/fps&&actual>0&&Math.abs(actual-durationUs)<=1000&&Double.isFinite(reported)&&Math.abs(reported-1_000_000d/actual)<=1;
    }
    /** A lone final sample has an average container rate, not a measurable cadence. */
    private static void compatibleWindow(MediaFormat first,Input next,long durationUs,boolean terminal){
        boolean single=false;
        if(fractionalAverage(first,next.format,durationUs,terminal,true)&&next.extractor.getSampleTime()==0&&hasSample(next.extractor.getSampleSize())){
            initialVideoSample(next.extractor.getSampleFlags());single=!next.extractor.advance();next.extractor.seekTo(0,MediaExtractor.SEEK_TO_PREVIOUS_SYNC);
        }
        compatibleWindow(first,next.format,durationUs,terminal,single);
    }
    private static double number(MediaFormat format,String key,double fallback){if(!format.containsKey(key))return fallback;try{return format.getInteger(key);}catch(ClassCastException floating){return format.getFloat(key);}}
    private static boolean sameBytes(ByteBuffer first,ByteBuffer next,boolean required){return first!=null&&next!=null&&(!required||first.hasRemaining())&&first.duplicate().equals(next.duplicate());}
    private static IllegalArgumentException incompatible(String key){return new IncompatibleConfigurationException("Segment codec configuration differs at "+key+"; retry with a consistent codec route before joining");}
    static long timestamp(long localUs,long startUs,long endUs,long previousUs){
        if(startUs<0||endUs<=startUs||localUs<0||localUs>=endUs-startUs)throw new IllegalArgumentException("Encoded sample is outside its planned window");
        long global;try{global=Math.addExact(startUs,localUs);}catch(ArithmeticException overflow){throw new IllegalArgumentException("Encoded timestamp overflow",overflow);}
        if(global<=previousUs)throw new IllegalArgumentException("Encoded presentation timestamps are not strictly increasing");return global;
    }
    static int sampleCapacity(long bytes){if(bytes<=0||bytes>MAX_SAMPLE)throw new IllegalArgumentException("Encoded sample exceeds the bounded32MiB copy buffer");return (int)bytes;}
    static void initialVideoSample(int flags){if((flags&MediaExtractor.SAMPLE_FLAG_SYNC)==0||(flags&(MediaExtractor.SAMPLE_FLAG_ENCRYPTED|MediaExtractor.SAMPLE_FLAG_PARTIAL_FRAME))!=0)throw new IllegalArgumentException("A video window must start on a complete unencrypted sync sample");}
    static void check(BooleanSupplier cancelled)throws InterruptedIOException{if(Thread.currentThread().isInterrupted()||cancelled.getAsBoolean())throw new InterruptedIOException("Segment join cancelled");}

    static JSONObject mux(Context context,ProjectStore.Project originals,List<RenderSessionStore.Checkpoint> video,RenderSessionStore.Checkpoint audio,File output,BooleanSupplier cancelled)throws Exception{
        check(cancelled);if(video==null||video.isEmpty())throw new IllegalArgumentException("No verified video windows to join");
        if(originals==null)throw new IllegalArgumentException("Original source graph is required");NativeRenderEngine.protectOriginals(originals,output);
        File target=output.getCanonicalFile();long endUs=0,firstAudioUs=0,plannedEndUs=video.get(video.size()-1).endUs;MediaFormat common=null,audioFormat=null;
        // Preflight every input before opening/truncating any destination; owner media are never accepted as outputs.
        for(RenderSessionStore.Checkpoint checkpoint:video){
            check(cancelled);if(checkpoint.startUs!=endUs||checkpoint.endUs<=checkpoint.startUs)throw new IllegalArgumentException("Video checkpoint plan has a gap or overlap");
            if(target.equals(checkpoint.file.getCanonicalFile()))throw new IllegalArgumentException("Join output refers to a checkpoint input");verifyBound(context,checkpoint,true,cancelled);
            try(Input input=new Input(checkpoint.file,true)){interval(input.format,checkpoint.endUs-checkpoint.startUs);initialTime(input.extractor.getSampleTime(),input.format);initialVideoSample(input.extractor.getSampleFlags());if(common==null){common=input.format;compatible(common,common);}else compatibleWindow(common,input,checkpoint.endUs-checkpoint.startUs,checkpoint.endUs==plannedEndUs);}
            endUs=checkpoint.endUs;
        }
        if(audio!=null){
            if(audio.startUs!=0||audio.endUs!=endUs)throw new IllegalArgumentException("Audio checkpoint does not span the whole video program");
            if(target.equals(audio.file.getCanonicalFile()))throw new IllegalArgumentException("Join output refers to the audio input");verifyBound(context,audio,false,cancelled);
            try(Input input=new Input(audio.file,false)){audioFormat=input.format;interval(audioFormat,endUs);if(!hasSample(input.extractor.getSampleSize()))throw new IllegalArgumentException("Continuous audio has no AAC packets");firstAudioUs=input.extractor.getSampleTime();initialTime(firstAudioUs,audioFormat);supportedAudioPlatform(android.os.Build.VERSION.SDK_INT,firstAudioUs);if(!"audio/mp4a-latm".equals(audioFormat.getString(MediaFormat.KEY_MIME)))throw new IllegalArgumentException("Continuous audio join requires AAC");}
        }
        check(cancelled);try(OutputWorkspace workspace=prepareOutput(originals,output)){
        MediaMuxer muxer=null;boolean started=false,finished=false;Exception failure=null;long samples=0,audioSamples=0;
        try{
            muxer=new MediaMuxer(workspace.descriptor,MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            int videoTrack=muxer.addTrack(common),audioTrack=audioFormat==null?-1:muxer.addTrack(audioFormat);if(common.containsKey(MediaFormat.KEY_ROTATION))muxer.setOrientationHint(common.getInteger(MediaFormat.KEY_ROTATION));
            muxer.start();started=true;long previous=-1;SampleBuffer buffer=new SampleBuffer();
            for(RenderSessionStore.Checkpoint checkpoint:video){
                try(Input input=new Input(checkpoint.file,true)){
                    compatibleWindow(common,input,checkpoint.endUs-checkpoint.startUs,checkpoint.endUs==plannedEndUs);interval(input.format,checkpoint.endUs-checkpoint.startUs);initialTime(input.extractor.getSampleTime(),input.format);initialVideoSample(input.extractor.getSampleFlags());boolean first=true;long copied=0;
                    while(input.extractor.getSampleTime()>=0){
                        check(cancelled);long local=input.extractor.getSampleTime();if(local>=checkpoint.endUs-checkpoint.startUs)break;long global=timestamp(local,checkpoint.startUs,checkpoint.endUs,previous);int flags=input.extractor.getSampleFlags();if(first)initialVideoSample(flags);
                        write(muxer,videoTrack,input.extractor,global,flags,buffer);previous=global;first=false;copied++;if(!input.extractor.advance())break;
                    }
                    if(copied==0)throw new IllegalArgumentException("Video checkpoint has no encoded samples");samples=Math.addExact(samples,copied);
                }
            }
            ByteBuffer eos=ByteBuffer.allocateDirect(1);eos.limit(0);muxer.writeSampleData(videoTrack,eos,endOfTrack(endUs,previous));
            if(audio!=null)try(Input input=new Input(audio.file,false)){
                long encodedEndUs=Math.addExact(input.format.getLong(MediaFormat.KEY_DURATION),Math.min(0,firstAudioUs));long previousAudio=Long.MIN_VALUE;
                // Preserve every full AAC packet, including codec-only padding. Edit metadata
                // trims playback; shortening the raw last packet would erase padding accounting.
                while(hasSample(input.extractor.getSampleSize())){check(cancelled);long local=input.extractor.getSampleTime();long global=audioTimestamp(local,encodedEndUs,previousAudio,input.format);write(muxer,audioTrack,input.extractor,global,input.extractor.getSampleFlags(),buffer);previousAudio=global;audioSamples++;if(!input.extractor.advance())break;
                }if(audioSamples==0)throw new IllegalArgumentException("Audio checkpoint has no AAC samples");eos.clear();eos.limit(0);muxer.writeSampleData(audioTrack,eos,audioEndOfTrack(encodedEndUs,firstAudioUs,previousAudio));
            }
            check(cancelled);muxer.stop();started=false;finished=true;
        }catch(Exception error){failure=error;throw error;}
        finally{
            if(started)try{muxer.stop();}catch(RuntimeException stop){if(failure!=null)failure.addSuppressed(stop);}
            if(muxer!=null)try{muxer.release();}catch(RuntimeException release){if(failure!=null)failure.addSuppressed(release);else throw release;}
            if(!finished)try{workspace.discard();}catch(Exception cleanup){if(failure!=null)failure.addSuppressed(cleanup);else throw cleanup;}
        }
        try{
        workspace.ensureCurrent();if(audio!=null)GaplessAudioMp4.trim(workspace.descriptor,endUs,GaplessAudioMuxer.maxPaddingUs(audioFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)),cancelled);android.system.Os.fsync(workspace.descriptor);check(cancelled);
        try(Input input=new Input(workspace.descriptor,true)){trackDuration(input.format.getLong(MediaFormat.KEY_DURATION),endUs);}
        if(audio!=null)try(Input input=new Input(workspace.descriptor,false)){trackDuration(PlayableMediaVerifier.presentationDurationUs(input.format),endUs);}
        JSONObject proof=PlayableMediaVerifier.verifyDescriptor(workspace.descriptor,true,cancelled);check(cancelled);workspace.ensureCurrent();
        return proof.put("videoWindows",video.size()).put("videoSamples",samples).put("audioSamples",audioSamples).put("continuousAudio",audio!=null).put("audioPrerollUs",Math.max(0,-firstAudioUs)).put("joinedWithoutReencoding",true).put("plannedDurationUs",endUs);
        }catch(Exception error){try{workspace.discard();}catch(Exception cleanup){error.addSuppressed(cleanup);}throw error;}
        }
    }
    private static void verifyBound(Context context,RenderSessionStore.Checkpoint checkpoint,boolean video,BooleanSupplier cancelled)throws Exception{
        check(cancelled);JSONObject fresh=PlayableMediaVerifier.verify(context,Uri.fromFile(checkpoint.file),video,cancelled);check(cancelled);
        if(!PlayableMediaVerifier.matchesSavedProof(checkpoint.proof,fresh)||checkpoint.proof.optLong("sizeBytes",-1)!=fresh.getLong("sizeBytes")||(!video&&!fresh.optBoolean("hasAudio")))throw new IllegalArgumentException("Checkpoint bytes changed before joining");
    }
    static final class OutputWorkspace implements AutoCloseable {
        final FileDescriptor descriptor;private final File file;private final long device,inode;private boolean closed;
        OutputWorkspace(File file)throws Exception{
            this.file=file;if(java.nio.file.Files.exists(file.toPath(),java.nio.file.LinkOption.NOFOLLOW_LINKS))throw new IllegalArgumentException("Join output already exists; use a fresh render workspace");
            try{descriptor=android.system.Os.open(file.getAbsolutePath(),android.system.OsConstants.O_CREAT|android.system.OsConstants.O_EXCL|android.system.OsConstants.O_RDWR|android.system.OsConstants.O_NOFOLLOW,0600);}
            catch(android.system.ErrnoException error){if(error.errno==android.system.OsConstants.EEXIST)throw new IllegalArgumentException("Join output already exists; use a fresh render workspace",error);throw error;}
            android.system.StructStat stat;try{stat=android.system.Os.fstat(descriptor);}catch(Exception error){android.system.Os.close(descriptor);throw error;}device=stat.st_dev;inode=stat.st_ino;
        }
        void ensureCurrent()throws Exception{android.system.StructStat stat=android.system.Os.lstat(file.getAbsolutePath());if(stat.st_dev!=device||stat.st_ino!=inode)throw new IOException("Join output workspace changed during rendering");}
        void discard()throws Exception{if(file.exists()){ensureCurrent();android.system.Os.remove(file.getAbsolutePath());}}
        public void close()throws Exception{if(!closed){closed=true;android.system.Os.close(descriptor);}}
    }
    private static void write(MediaMuxer muxer,int track,MediaExtractor extractor,long timestamp,int flags,SampleBuffer buffers)throws Exception{
        if((flags&(MediaExtractor.SAMPLE_FLAG_ENCRYPTED|MediaExtractor.SAMPLE_FLAG_PARTIAL_FRAME))!=0)throw new IllegalArgumentException("Encrypted or partial encoded samples cannot be joined");
        int expected=sampleCapacity(extractor.getSampleSize());ByteBuffer buffer=buffers.get(expected);int read=extractor.readSampleData(buffer,0);if(read!=expected)throw new IOException("Encoded sample became unavailable during join");
        buffer.position(0);buffer.limit(read);MediaCodec.BufferInfo info=new MediaCodec.BufferInfo();info.set(0,read,timestamp,(flags&MediaExtractor.SAMPLE_FLAG_SYNC)!=0?MediaCodec.BUFFER_FLAG_KEY_FRAME:0);muxer.writeSampleData(track,buffer,info);
    }
    private static final class SampleBuffer {
        private ByteBuffer buffer=ByteBuffer.allocateDirect(1024*1024);
        ByteBuffer get(int bytes){if(buffer.capacity()<bytes)buffer=ByteBuffer.allocateDirect(Math.min(MAX_SAMPLE,Math.max(bytes,buffer.capacity()*2)));buffer.clear();buffer.limit(bytes);return buffer;}
    }
    private static final class Input implements AutoCloseable {
        private interface Opener{void open(MediaExtractor extractor)throws Exception;}
        final MediaExtractor extractor=new MediaExtractor();final MediaFormat format;
        Input(File file,boolean video)throws Exception{this(extractor->extractor.setDataSource(file.getAbsolutePath()),video);}
        Input(FileDescriptor descriptor,boolean video)throws Exception{this(extractor->extractor.setDataSource(descriptor),video);}
        private Input(Opener opener,boolean video)throws Exception{
            try{opener.open(extractor);MediaFormat found=null;int selected=-1;for(int i=0;i<extractor.getTrackCount();i++){MediaFormat candidate=extractor.getTrackFormat(i);String mime=candidate.getString(MediaFormat.KEY_MIME);if(mime!=null&&mime.startsWith(video?"video/":"audio/")){if(selected>=0)throw new IllegalArgumentException("Checkpoint contains multiple selected media tracks");found=candidate;selected=i;}}
                if(selected<0)throw new IllegalArgumentException("Checkpoint has no requested media track");format=found;extractor.selectTrack(selected);
            }catch(Exception error){extractor.release();throw error;}
        }
        public void close(){extractor.release();}
    }
}
