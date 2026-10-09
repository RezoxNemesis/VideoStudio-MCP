package com.rezoxnemesis.videostudio;

import androidx.media3.common.C;
import androidx.media3.common.audio.BaseAudioProcessor;
import androidx.media3.common.audio.AudioProcessor;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/** PCM gain/pan automation. Per-sample evaluation is independent of video frame timing. */
public final class ClipAudioProcessor extends BaseAudioProcessor {
    private final ProjectStore.Clip clip;
    private long frame;
    private long positionOffsetUs;
    private final KeyframeCurve gainCurve,panCurve;
    private byte[] pending=new byte[0];
    private ByteBuffer pendingFrame;
    private int pendingCount;
    private final AudioDsp.Parameters dspParameters;
    private AudioDsp dsp;
    private double[] samples,processed;
    public ClipAudioProcessor(ProjectStore.Clip clip){
        this.clip=ProjectStore.Clip.fromJson(clip.toJson());
        gainCurve=new KeyframeCurve(this.clip,"volume",clip.volume);panCurve=new KeyframeCurve(this.clip,"pan",clip.pan);
        dspParameters=this.clip.effects.has("audioDsp")?AudioDspSettings.read(this.clip.effects.optJSONObject("audioDsp")):null;
    }
    @Override protected AudioFormat onConfigure(AudioFormat input) throws AudioProcessor.UnhandledAudioFormatException {
        if(input.channelCount<1||input.channelCount>32||(input.encoding!=C.ENCODING_PCM_16BIT && input.encoding!=C.ENCODING_PCM_FLOAT))throw new AudioProcessor.UnhandledAudioFormatException(input);
        return input;
    }
    @Override protected void onFlush(AudioProcessor.StreamMetadata metadata){frame=0;pendingCount=0;positionOffsetUs=Math.max(0,metadata.positionOffsetUs);
        int bytes=(inputAudioFormat.encoding==C.ENCODING_PCM_FLOAT?4:2)*Math.max(1,inputAudioFormat.channelCount);
        pending=new byte[bytes];pendingFrame=ByteBuffer.wrap(pending).order(ByteOrder.nativeOrder());
        samples=new double[inputAudioFormat.channelCount];processed=new double[inputAudioFormat.channelCount];
        dsp=dspParameters==null?null:new AudioDsp(inputAudioFormat.sampleRate,inputAudioFormat.channelCount,dspParameters);
    }
    @Override public void queueInput(ByteBuffer input){
        input.order(ByteOrder.nativeOrder());int channels=inputAudioFormat.channelCount;
        boolean floating=inputAudioFormat.encoding==C.ENCODING_PCM_FLOAT;
        int frameBytes=(floating?4:2)*channels;
        int total=Math.addExact(pendingCount,input.remaining());
        ByteBuffer output=replaceOutputBuffer(total-total%frameBytes).order(ByteOrder.nativeOrder());
        if(pendingCount>0){
            while(input.hasRemaining()&&pendingCount<frameBytes)pending[pendingCount++]=input.get();
            if(pendingCount==frameBytes){pendingFrame.position(0);processFrame(pendingFrame,output,channels,floating);pendingCount=0;}
        }
        while(input.remaining()>=frameBytes)processFrame(input,output,channels,floating);
        while(input.hasRemaining())pending[pendingCount++]=input.get();
        output.flip();
    }
    private void processFrame(ByteBuffer input,ByteBuffer output,int channels,boolean floating){
            long local=TimelineMath.audioLocalMs(positionOffsetUs,frame,inputAudioFormat.sampleRate);
            double gain=gainCurve.valueAt(local);
            double pan=panCurve.valueAt(local);
            for(int channel=0;channel<channels;channel++){
                double channelGain=gain;
                if(channels>=2 && channel==0)channelGain*=pan>0?1-pan:1;
                else if(channels>=2 && channel==1)channelGain*=pan<0?1+pan:1;
                samples[channel]=(floating?input.getFloat():input.getShort()/32768d)*channelGain;
            }
            if(dsp!=null)dsp.processFrame(samples,processed);
            for(int channel=0;channel<channels;channel++){double value=dsp==null?samples[channel]:processed[channel];
                if(floating)output.putFloat((float)Math.max(-1,Math.min(1,value)));else output.putShort((short)Math.max(Short.MIN_VALUE,Math.min(Short.MAX_VALUE,Math.round(value*32768))));
            }
            frame++;
    }
    @Override protected void onQueueEndOfStream(){if(pendingCount!=0)throw new IllegalStateException("Truncated PCM audio frame");}
}
