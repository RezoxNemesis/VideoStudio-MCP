package com.rezoxnemesis.videostudio;

import android.content.Context;
import android.media.AudioFormat;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.os.Looper;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.util.BitSet;
import java.util.Locale;
import org.json.JSONArray;
import org.json.JSONObject;

/** Bounded, read-only source amplitude measurement. No beat/ASR/true-peak inference. */
public final class NativeAudioEnvelope {
    public static final String CAPABILITY_JSON="{\"schema\":\"videostudio.audio.envelope.capabilities.v1\",\"minimumNativeVersion\":\"4.10.25\",\"action\":\"analyse_media\",\"mode\":\"audio_envelope\",\"resultSchema\":\"videostudio.audio.envelope.v1\",\"readOnly\":true,\"source\":{\"projectRevisionRequired\":true,\"sha256Required\":true,\"ownedRegularFilesOnly\":true,\"mimePrefix\":\"audio/\",\"maximumBytes\":10737418240,\"linkedContentSupported\":false,\"galleryAccess\":false},\"window\":{\"sourceMs\":[0,86400000],\"durationMs\":[100,30000],\"buckets\":[4,720]},\"pcm\":{\"encoding\":\"pcm16\",\"channels\":[1,2],\"sampleRate\":[8000,192000],\"measure\":\"All-channel root-mean-square energy and absolute PCM sample peak\",\"samplePeakIsTruePeak\":false},\"normalization\":\"window_max_rms\",\"coverage\":{\"sampleCounts\":true,\"sourceTimeBuckets\":true,\"distinctFrameCoverage\":true,\"partialResultsExplicit\":true,\"missingMetricsAreNull\":true,\"silenceRequiresSamples\":true,\"codecTimestampMapping\":\"Integer codec PTS rounded to the nearest source sample-frame index; no interpolation or gap filling\"},\"budgets\":{\"maximumCompressedWindowBytes\":67108864,\"maximumDecodedFrames\":6144000,\"maximumInputBuffers\":65536,\"maximumOutputBuffers\":65536,\"maximumIdleCycles\":2200,\"wallDeadlineMs\":120000,\"hashBufferBytes\":65536},\"pcmWav\":{\"maximumSourceBytes\":536870912,\"maximumSourceDurationMs\":600000},\"manualControls\":\"Editor > Audio inspector > Analyze envelope\",\"legacyVisualOmissionUnchanged\":true,\"newCanonicalAction\":false,\"limitations\":\"Measured source amplitude only; no ASR, lyrics, beat detection, pitch, true peak, automatic editing, audio synthesis, codec/GPU or artistic certification. Linked SAF sources require an owned import; very large source hashing may reach the explicit work deadline.\"}";
    public interface Progress {void update(int percent,String detail)throws Exception;}
    private static final long MAX_SOURCE_MS=86400000L,MAX_DURATION_MS=30000L,MAX_COMPRESSED=64L*1024*1024;
    private static final int MAX_BUCKETS=720,MAX_DECODED_FRAMES=6144000,MAX_BUFFERS=65536,MAX_IDLE=2200;
    private static final long MAX_WORK_NS=120000000000L;
    private NativeAudioEnvelope(){}
    public static JSONObject capabilities()throws Exception{return new JSONObject(CAPABILITY_JSON);}

    public static JSONObject validate(JSONObject input)throws Exception {
        try{
            SketchDocument.fields(input,"mode,projectId,expectedUpdatedAt,assetId,expectedSha256,sourceMs,durationMs,buckets");
            if(!(input.get("mode") instanceof String)||!"audio_envelope".equals(input.getString("mode")))throw new IllegalArgumentException("Use explicit mode audio_envelope");
            String projectId=SketchDocument.id(input,"projectId"),assetId=SketchDocument.id(input,"assetId");
            long revision=SketchDocument.integer(input,"expectedUpdatedAt",0,9007199254740991L);
            long source=SketchDocument.integer(input,"sourceMs",0,MAX_SOURCE_MS),duration=SketchDocument.integer(input,"durationMs",100,MAX_DURATION_MS);
            int buckets=(int)SketchDocument.integer(input,"buckets",4,MAX_BUCKETS);
            if(!(input.get("expectedSha256") instanceof String)||!input.getString("expectedSha256").matches("[a-f0-9]{64}"))throw new IllegalArgumentException("An exact lowercase source SHA256 is required");
            return new JSONObject().put("mode","audio_envelope").put("projectId",projectId).put("expectedUpdatedAt",revision).put("assetId",assetId)
                    .put("expectedSha256",input.getString("expectedSha256")).put("sourceMs",source).put("durationMs",duration).put("buckets",buckets);
        }catch(Exception failure){throw new Failure("AUDIO_ENVELOPE_INPUT",failure.getMessage()==null?"Strict audio envelope request required":failure.getMessage(),failure,null);}
    }

    public static JSONObject analyse(Context context,JSONObject input)throws Exception{return analyse(context,input,(percent,detail)->{});}
    public static JSONObject analyse(Context context,JSONObject input,Progress progress)throws Exception {
        JSONObject request=validate(input);if(context==null||progress==null)throw failure("AUDIO_ENVELOPE_INPUT","Audio analysis context/progress required");
        if(Looper.myLooper()==Looper.getMainLooper())throw failure("AUDIO_ENVELOPE_INPUT","Audio envelope decoding must run on a worker");
        Work work=new Work();work.check();progress.update(2,"Verifying exact owned audio source");Source source=source(context,request);
        try(FileInputStream stream=new FileInputStream(source.file)){
            FileChannel channel=stream.getChannel();String expected=request.getString("expectedSha256");
            if(!expected.equals(hash(channel,source.bytes,work)))throw failure("AUDIO_ENVELOPE_SOURCE_CHANGED","Expected source hash differs");
            progress.update(12,"Reading selected audio window");Accumulator measured;
            PcmWaveFile.Layout pcm;
            try{pcm=PcmWaveFile.readPcm16(channel,0,source.bytes);}
            catch(IllegalArgumentException invalid){throw new Failure("AUDIO_ENVELOPE_UNSUPPORTED_FORMAT",invalid.getMessage(),invalid,null);}
            if(pcm!=null)measured=pcm(channel,pcm,request,work,progress);
            else measured=codec(context,stream,source.bytes,request,work,progress);
            work.check();progress.update(88,"Rechecking unchanged source bytes");
            if(!expected.equals(hash(channel,source.bytes,work)))throw failure("AUDIO_ENVELOPE_SOURCE_CHANGED","Source bytes changed during analysis");
            verify(context,request,source);work.check();
            JSONObject result=measured.result();
            if(measured.decodedFrames==0)throw new Failure("AUDIO_ENVELOPE_NO_SAMPLES","No audio samples decoded in the selected source window",null,new JSONObject().put("coverage",result.getJSONObject("coverage")));
            result.put("schema","videostudio.audio.envelope.v1").put("mode","audio_envelope").put("ok",true).put("readOnly",true).put("sourceUnchanged",true)
                    .put("projectId",request.getString("projectId")).put("sourceProjectUpdatedAt",request.getLong("expectedUpdatedAt")).put("assetId",request.getString("assetId"))
                    .put("sourceSha256",expected).put("sourceBytes",source.bytes).put("sourceMs",request.getLong("sourceMs")).put("durationMs",request.getLong("durationMs"))
                    .put("bucketCount",request.getInt("buckets")).put("pcmEncoding","pcm16").put("samplePeakIsTruePeak",false).put("beatsDetected",false).put("speechRecognized",false)
                    .put("status",result.getJSONObject("coverage").getBoolean("complete")?"complete":"partial");
            progress.update(100,result.getString("status").equals("complete")?"Audio envelope complete":"Partial audio coverage; inspect missing samples");return result;
        }catch(InterruptedIOException interrupted){Thread.currentThread().interrupt();InterruptedException failure=new InterruptedException("Audio envelope cancelled; source retained");failure.initCause(interrupted);throw failure;}
        catch(IOException failure){if(Thread.currentThread().isInterrupted()){InterruptedException cancelled=new InterruptedException("Audio envelope cancelled; source retained");cancelled.initCause(failure);throw cancelled;}throw new Failure("AUDIO_ENVELOPE_DECODE_FAILED",failure.getMessage(),failure,null);}
    }

    private static Source source(Context context,JSONObject request)throws Exception {
        try(ProjectStore store=new ProjectStore(context)){
            ProjectStore.Project project=store.get(request.getString("projectId"));
            if(project==null||project.updatedAt!=request.getLong("expectedUpdatedAt"))throw failure("AUDIO_ENVELOPE_SOURCE_CHANGED","Exact audio project revision is unavailable");
            ProjectStore.Asset asset=project.asset(request.getString("assetId"));
            if(asset==null||asset.mime==null||!asset.mime.startsWith("audio/"))throw failure("AUDIO_ENVELOPE_SOURCE_CHANGED","Exact selected AUDIO asset is unavailable");
            if(asset.durationMs>0&&request.getLong("sourceMs")+request.getLong("durationMs")>asset.durationMs)throw failure("AUDIO_ENVELOPE_WINDOW_OUTSIDE_SOURCE","Window exceeds selected source duration");
            File file;
            try{file=ProjectAssetImport.sourceFile(context,asset);}catch(Exception invalid){throw new Failure("AUDIO_ENVELOPE_SOURCE_CHANGED","Audio source must remain an owned regular file",invalid,null);}
            return new Source(file,file.length(),SketchDocument.canonical(asset.toJson()),Files.readAttributes(file.toPath(),BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS));
        }
    }
    private static void verify(Context context,JSONObject request,Source frozen)throws Exception {
        Source current=source(context,request);
        if(current.bytes!=frozen.bytes||!current.asset.equals(frozen.asset)||!current.file.getCanonicalFile().equals(frozen.file.getCanonicalFile())
                ||!current.attributes.lastModifiedTime().equals(frozen.attributes.lastModifiedTime())
                ||frozen.attributes.fileKey()!=null&&current.attributes.fileKey()!=null&&!frozen.attributes.fileKey().equals(current.attributes.fileKey()))
            throw failure("AUDIO_ENVELOPE_SOURCE_CHANGED","Owned source identity changed during analysis");
    }
    private static String hash(FileChannel channel,long bytes,Work work)throws Exception {
        if(channel.size()!=bytes)throw failure("AUDIO_ENVELOPE_SOURCE_CHANGED","Owned source length changed");
        MessageDigest digest=MessageDigest.getInstance("SHA-256");ByteBuffer buffer=ByteBuffer.allocate(65536);long at=0;
        while(at<bytes){work.check();buffer.clear();buffer.limit((int)Math.min(buffer.capacity(),bytes-at));int read=channel.read(buffer,at);if(read<=0)throw failure("AUDIO_ENVELOPE_SOURCE_CHANGED","Short owned source read");digest.update(buffer.array(),0,read);at+=read;}
        if(channel.size()!=bytes)throw failure("AUDIO_ENVELOPE_SOURCE_CHANGED","Owned source length changed");
        StringBuilder value=new StringBuilder();for(byte b:digest.digest())value.append(String.format(Locale.ROOT,"%02x",b&255));return value.toString();
    }
    private static Accumulator pcm(FileChannel channel,PcmWaveFile.Layout pcm,JSONObject request,Work work,Progress progress)throws Exception {
        long sourceMs=request.getLong("sourceMs"),duration=request.getLong("durationMs");
        if((sourceMs+duration)*pcm.sampleRate>pcm.sampleFrames*1000L)throw failure("AUDIO_ENVELOPE_WINDOW_OUTSIDE_SOURCE","Window exceeds actual PCM sample frames");
        Accumulator result=new Accumulator(request,pcm.sampleRate,pcm.channels,"pcm_wav","exact_pcm_sample_frame_clock");
        ByteBuffer block=ByteBuffer.allocate(8192).order(ByteOrder.LITTLE_ENDIAN);
        for(long frame=result.first;frame<result.last;){
            work.check();int frames=(int)Math.min(result.last-frame,block.capacity()/pcm.blockAlign);block.clear();block.limit(frames*pcm.blockAlign);
            PcmWaveFile.readFully(channel,block,pcm.dataOffset+frame*pcm.blockAlign);
            for(int i=0;i<frames;i++)result.accept(block,i*pcm.blockAlign,frame+i);
            frame+=frames;progress.update(12+(int)(68*(frame-result.first)/Math.max(1,result.last-result.first)),"Measuring native PCM samples");
        }
        result.reason="window_end";return result;
    }

    private static final class Source {
        final File file;final long bytes;final String asset;final BasicFileAttributes attributes;
        Source(File file,long bytes,String asset,BasicFileAttributes attributes){this.file=file;this.bytes=bytes;this.asset=asset;this.attributes=attributes;}
    }
    private static final class Work {
        final long started=System.nanoTime();
        void check()throws InterruptedException {if(Thread.currentThread().isInterrupted())throw new InterruptedException("Audio envelope cancelled; source retained");if(System.nanoTime()-started>MAX_WORK_NS)throw failure("AUDIO_ENVELOPE_BUDGET","Audio analysis work deadline reached");}
    }
    public static String code(Throwable error){for(int i=0;error!=null&&i<12;i++,error=error.getCause()){if(error instanceof Failure)return ((Failure)error).code;if(error instanceof InterruptedException||error instanceof InterruptedIOException)return "AUDIO_ENVELOPE_CANCELLED";}return "AUDIO_ENVELOPE_DECODE_FAILED";}
    public static JSONObject diagnostics(Throwable error)throws Exception {for(int i=0;error!=null&&i<12;i++,error=error.getCause())if(error instanceof Failure&&((Failure)error).diagnostics!=null)return new JSONObject(((Failure)error).diagnostics.toString());return new JSONObject();}
    private static Failure failure(String code,String message){return new Failure(code,message,null,null);}
    private static final class Failure extends IllegalArgumentException {
        final String code;final JSONObject diagnostics;
        Failure(String code,String message,Throwable cause,JSONObject diagnostics){super(message==null?code:message,cause);this.code=code;this.diagnostics=diagnostics;}
    }

    private static final class Accumulator {
        final int rate,channels,bucketCount;final long sourceMs,durationMs,first,last;
        final double[] power,peaks;final long[] frames,firstTimes,lastTimes;final BitSet seen;
        final String route,timestampMapping;String codecName,reason="decoder_eos";long decodedFrames,duplicateFrames,discontinuities,previousEnd=Long.MIN_VALUE;
        Accumulator(JSONObject request,int rate,int channels,String route,String mapping)throws Exception {
            if(rate<8000||rate>192000||channels<1||channels>2)throw failure("AUDIO_ENVELOPE_UNSUPPORTED_FORMAT","Only PCM16 mono/stereo at8..192kHz is supported");
            this.rate=rate;this.channels=channels;this.route=route;timestampMapping=mapping;sourceMs=request.getLong("sourceMs");durationMs=request.getLong("durationMs");bucketCount=request.getInt("buckets");
            first=ceil(sourceMs*rate,1000);last=ceil((sourceMs+durationMs)*rate,1000);
            if(last-first<1||last-first>5760000)throw failure("AUDIO_ENVELOPE_BUDGET","Audio sample-frame window exceeds budget");
            seen=new BitSet((int)(last-first));power=new double[bucketCount];peaks=new double[bucketCount];frames=new long[bucketCount];firstTimes=new long[bucketCount];lastTimes=new long[bucketCount];java.util.Arrays.fill(firstTimes,-1);java.util.Arrays.fill(lastTimes,-1);
        }
        void recordBuffer(long firstFrame,int count){if(previousEnd!=Long.MIN_VALUE&&firstFrame!=previousEnd)discontinuities++;previousEnd=firstFrame+count;}
        void accept(ByteBuffer input,int at,long frame){
            if(frame<first||frame>=last)return;int local=(int)(frame-first);if(seen.get(local)){duplicateFrames++;return;}seen.set(local);
            long position=frame*1000-sourceMs*rate;int bucket=(int)Math.min(bucketCount-1,position*bucketCount/(durationMs*rate));double energy=0,peak=0;
            for(int c=0;c<channels;c++){double value=input.getShort(at+c*2)/32768.;energy+=value*value;peak=Math.max(peak,Math.abs(value));}
            power[bucket]+=energy;peaks[bucket]=Math.max(peaks[bucket],peak);frames[bucket]++;decodedFrames++;
            long time=frame*1000000L/rate;if(firstTimes[bucket]<0||time<firstTimes[bucket])firstTimes[bucket]=time;lastTimes[bucket]=Math.max(lastTimes[bucket],time);
        }
        JSONObject result()throws Exception {
            double maximum=0;for(int i=0;i<bucketCount;i++)if(frames[i]>0)maximum=Math.max(maximum,Math.sqrt(power[i]/(frames[i]*channels)));
            JSONArray buckets=new JSONArray();int decoded=0,missing=0,partial=0;
            for(int i=0;i<bucketCount;i++){
                long expected=ceil(sourceMs*rate*bucketCount+(i+1L)*durationMs*rate,1000L*bucketCount)-ceil(sourceMs*rate*bucketCount+i*durationMs*rate,1000L*bucketCount);
                if(frames[i]>0)decoded++;else missing++;if(frames[i]>0&&frames[i]<expected)partial++;
                JSONObject coverage=new JSONObject().put("expectedFrames",expected).put("decodedFrames",frames[i]).put("missingFrames",Math.max(0,expected-frames[i])).put("complete",frames[i]==expected)
                        .put("firstSampleTimeUs",firstTimes[i]<0?JSONObject.NULL:firstTimes[i]).put("lastSampleTimeUs",lastTimes[i]<0?JSONObject.NULL:lastTimes[i]);
                double rms=frames[i]==0?0:Math.sqrt(power[i]/(frames[i]*channels));
                buckets.put(new JSONObject().put("index",i).put("sourceStartUs",sourceMs*1000+durationMs*1000*i/bucketCount).put("sourceEndUs",sourceMs*1000+durationMs*1000*(i+1)/bucketCount)
                        .put("sampleFrames",frames[i]).put("channelSamples",frames[i]*channels).put("rms",frames[i]==0?JSONObject.NULL:rms)
                        .put("samplePeak",frames[i]==0?JSONObject.NULL:peaks[i]).put("normalizedRms",frames[i]==0?JSONObject.NULL:maximum==0?0:rms/maximum).put("coverage",coverage));
            }
            JSONObject coverage=new JSONObject().put("requestedBuckets",bucketCount).put("decodedBuckets",decoded).put("missingBuckets",missing).put("partialBuckets",partial)
                    .put("requestedFrames",last-first).put("decodedFrames",decodedFrames).put("missingFrames",last-first-decodedFrames).put("duplicateFrames",duplicateFrames).put("timestampDiscontinuities",discontinuities)
                    .put("complete",decodedFrames==last-first).put("terminationReason",reason).put("clock","source_sample_frame_grid");
            JSONObject result=new JSONObject().put("sampleRate",rate).put("channels",channels).put("decoderRoute",route).put("timestampMapping",timestampMapping)
                    .put("normalization",new JSONObject().put("method","window_max_rms").put("maximumRms",maximum)).put("coverage",coverage).put("buckets",buckets);
            if(codecName!=null)result.put("codecName",codecName);return result;
        }
    }
    private static long ceil(long numerator,long denominator){return numerator/denominator+(numerator%denominator==0?0:1);}

    private static Accumulator codec(Context context,FileInputStream source,long bytes,JSONObject request,Work work,Progress progress)throws Exception {
        MediaExtractor extractor=new MediaExtractor();MediaCodec codec=null;boolean started=false;Throwable pending=null;
        try{
            work.check();extractor.setDataSource(source.getFD(),0,bytes);int track=-1;MediaFormat inputFormat=null;
            for(int i=0;i<extractor.getTrackCount();i++){
                work.check();MediaFormat candidate=extractor.getTrackFormat(i);String mime=candidate.getString(MediaFormat.KEY_MIME);
                if(mime!=null&&mime.startsWith("audio/")){track=i;inputFormat=candidate;break;}
            }
            if(track<0||inputFormat==null)throw failure("AUDIO_ENVELOPE_UNSUPPORTED_FORMAT","Selected source has no audio track");
            String mime=inputFormat.getString(MediaFormat.KEY_MIME);if(mime==null)throw failure("AUDIO_ENVELOPE_UNSUPPORTED_FORMAT","Audio track MIME is unavailable");
            long from=request.getLong("sourceMs")*1000,end=from+request.getLong("durationMs")*1000;
            extractor.selectTrack(track);extractor.seekTo(from,MediaExtractor.SEEK_TO_PREVIOUS_SYNC);
            inputFormat.setInteger(MediaFormat.KEY_PCM_ENCODING,AudioFormat.ENCODING_PCM_16BIT);
            codec=MediaCodec.createDecoderByType(mime);codec.configure(inputFormat,null,null,0);codec.start();started=true;
            String codecName=codec.getName();boolean inputDone=false,outputDone=false;long inputBytes=0,decodedWork=0;int inputs=0,outputs=0,idle=0;
            Accumulator measured=null;MediaCodec.BufferInfo info=new MediaCodec.BufferInfo();String reason="decoder_eos";
            while(!outputDone){
                work.check();boolean madeProgress=false;
                if(!inputDone){
                    int slot=codec.dequeueInputBuffer(2000);
                    if(slot>=0){
                        if(++inputs>MAX_BUFFERS)throw failure("AUDIO_ENVELOPE_BUDGET","Audio decoder input-buffer budget reached");
                        ByteBuffer input=codec.getInputBuffer(slot);if(input==null)throw failure("AUDIO_ENVELOPE_DECODE_FAILED","Audio decoder input buffer is missing");input.clear();
                        long time=extractor.getSampleTime();
                        if(time<0||time>end+250000L){codec.queueInputBuffer(slot,0,0,Math.max(0,time),MediaCodec.BUFFER_FLAG_END_OF_STREAM);inputDone=true;}
                        else{
                            int size=extractor.readSampleData(input,0);
                            if(size<0){codec.queueInputBuffer(slot,0,0,Math.max(0,time),MediaCodec.BUFFER_FLAG_END_OF_STREAM);inputDone=true;}
                            else{
                                if(size>input.capacity()||size>MAX_COMPRESSED-inputBytes)throw failure("AUDIO_ENVELOPE_BUDGET","Audio compressed-window byte budget reached");
                                inputBytes+=size;codec.queueInputBuffer(slot,0,size,time,0);extractor.advance();
                            }
                        }
                        madeProgress=true;
                    }
                }
                int index=codec.dequeueOutputBuffer(info,2000);
                if(index==MediaCodec.INFO_OUTPUT_FORMAT_CHANGED){
                    measured=outputFormat(request,codec.getOutputFormat(),measured);measured.codecName=codecName;madeProgress=true;
                }else if(index>=0){
                    Throwable outputFailure=null;
                    try{
                        if(++outputs>MAX_BUFFERS)throw failure("AUDIO_ENVELOPE_BUDGET","Audio decoder output-buffer budget reached");
                        if(measured==null){measured=outputFormat(request,codec.getOutputFormat(),null);measured.codecName=codecName;}
                        if(info.size<0||info.offset<0)throw failure("AUDIO_ENVELOPE_DECODE_FAILED","Invalid decoded PCM buffer bounds");
                        // Codec initialization is metadata, not decoded PCM.
                        // It still follows the same release/EOS/cancellation path.
                        if(info.size>0&&(info.flags&MediaCodec.BUFFER_FLAG_CODEC_CONFIG)==0){
                            ByteBuffer data=codec.getOutputBuffer(index);int align=measured.channels*2;
                            if(data==null||(long)info.offset+info.size>data.limit()||info.size%align!=0)throw failure("AUDIO_ENVELOPE_UNSUPPORTED_FORMAT","Decoded PCM16 buffer is missing or misaligned");
                            int frames=info.size/align;decodedWork+=frames;if(decodedWork>MAX_DECODED_FRAMES)throw failure("AUDIO_ENVELOPE_BUDGET","Audio decoded-frame budget reached");
                            long timestamp=info.presentationTimeUs;
                            if(timestamp<-2000000L||timestamp>(MAX_SOURCE_MS+MAX_DURATION_MS+2000)*1000L)throw failure("AUDIO_ENVELOPE_BUDGET","Audio decoder timestamp is outside the bounded source clock");
                            // PTS is an integer microsecond clock. Round ONCE per buffer to
                            // the nearest source sample frame, then advance exact frame indices.
                            // This is explicit quantization, never interpolation or gap filling.
                            long firstFrame=Math.floorDiv(timestamp*measured.rate+500000L,1000000L);
                            measured.recordBuffer(firstFrame,frames);ByteBuffer pcm=data.duplicate().order(ByteOrder.LITTLE_ENDIAN);
                            for(int frame=0;frame<frames;frame++){
                                if((frame&4095)==0)work.check();measured.accept(pcm,info.offset+frame*align,firstFrame+frame);
                            }
                            if(firstFrame+frames>=measured.last){outputDone=true;reason="window_end";}
                            progress.update(12+(int)Math.min(68,68*measured.decodedFrames/Math.max(1,measured.last-measured.first)),"Measuring native decoded PCM16");
                        }
                        if((info.flags&MediaCodec.BUFFER_FLAG_END_OF_STREAM)!=0){outputDone=true;if(!"window_end".equals(reason))reason="decoder_eos";}
                        madeProgress=true;
                    }catch(Exception failure){outputFailure=failure;throw failure;}catch(Error failure){outputFailure=failure;throw failure;}
                    finally{try{codec.releaseOutputBuffer(index,false);}catch(Exception release){if(outputFailure!=null)outputFailure.addSuppressed(release);else throw release;}}
                }
                idle=madeProgress?0:idle+1;if(idle>=MAX_IDLE)throw failure("AUDIO_ENVELOPE_BUDGET","Audio decoder idle budget reached");
            }
            if(measured==null){
                JSONObject coverage=new JSONObject().put("requestedBuckets",request.getInt("buckets")).put("decodedBuckets",0).put("missingBuckets",request.getInt("buckets"))
                        .put("partialBuckets",0).put("requestedFrames",JSONObject.NULL).put("decodedFrames",0).put("complete",false).put("terminationReason",reason);
                throw new Failure("AUDIO_ENVELOPE_NO_SAMPLES","Decoder produced no PCM samples",null,new JSONObject().put("coverage",coverage));
            }
            measured.reason=reason;return measured;
        }catch(Exception failure){pending=failure;throw failure;}catch(Error failure){pending=failure;throw failure;}
        finally{
            Exception cleanup=null;
            if(codec!=null){
                if(started)try{codec.stop();}catch(Exception failure){if(pending!=null)pending.addSuppressed(failure);else cleanup=failure;}
                try{codec.release();}catch(Exception failure){if(pending!=null)pending.addSuppressed(failure);else if(cleanup!=null)cleanup.addSuppressed(failure);else cleanup=failure;}
            }
            try{extractor.release();}catch(Exception failure){if(pending!=null)pending.addSuppressed(failure);else if(cleanup!=null)cleanup.addSuppressed(failure);else cleanup=failure;}
            if(pending==null&&cleanup!=null)throw new Failure("AUDIO_ENVELOPE_DECODE_FAILED","Native audio decoder cleanup failed",cleanup,null);
        }
    }
    private static Accumulator outputFormat(JSONObject request,MediaFormat format,Accumulator current)throws Exception {
        if(!format.containsKey(MediaFormat.KEY_SAMPLE_RATE)||!format.containsKey(MediaFormat.KEY_CHANNEL_COUNT))throw failure("AUDIO_ENVELOPE_UNSUPPORTED_FORMAT","Decoded sample rate/channel count is unavailable");
        int rate=format.getInteger(MediaFormat.KEY_SAMPLE_RATE),channels=format.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
        // Android's documented default decoded PCM output is signed16-bit when
        // pcm-encoding is absent; the explicit output format always overrides it.
        int encoding=format.containsKey(MediaFormat.KEY_PCM_ENCODING)?format.getInteger(MediaFormat.KEY_PCM_ENCODING):AudioFormat.ENCODING_PCM_16BIT;
        if(encoding!=AudioFormat.ENCODING_PCM_16BIT)throw failure("AUDIO_ENVELOPE_UNSUPPORTED_FORMAT","Decoder did not provide PCM16 output");
        if(current!=null){if(current.rate!=rate||current.channels!=channels)throw failure("AUDIO_ENVELOPE_UNSUPPORTED_FORMAT","Decoded audio layout changed within the selected window");return current;}
        return new Accumulator(request,rate,channels,"android_media_codec","codec_pts_nearest_source_sample_frame_v1");
    }
}
