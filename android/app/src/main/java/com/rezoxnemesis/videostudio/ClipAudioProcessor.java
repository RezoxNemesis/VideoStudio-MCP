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
    private final KeyframeCurve gainCurve,panCurve;
    public ClipAudioProcessor(ProjectStore.Clip clip){
        this.clip=ProjectStore.Clip.fromJson(clip.toJson());
        gainCurve=new KeyframeCurve(this.clip,"volume",clip.volume);panCurve=new KeyframeCurve(this.clip,"pan",clip.pan);
    }
    @Override protected AudioFormat onConfigure(AudioFormat input) throws AudioProcessor.UnhandledAudioFormatException {
        if(input.encoding!=C.ENCODING_PCM_16BIT && input.encoding!=C.ENCODING_PCM_FLOAT)throw new AudioProcessor.UnhandledAudioFormatException(input);
        return input;
    }
    @Override protected void onFlush(){frame=0;}
    @Override public void queueInput(ByteBuffer input){
        int bytes=input.remaining();ByteBuffer output=replaceOutputBuffer(bytes).order(ByteOrder.nativeOrder());
        input.order(ByteOrder.nativeOrder());int channels=inputAudioFormat.channelCount;
        boolean floating=inputAudioFormat.encoding==C.ENCODING_PCM_FLOAT;
        int frameBytes=(floating?4:2)*channels;
        while(input.remaining()>=frameBytes){
            long local=Math.round(frame*1000d/inputAudioFormat.sampleRate/clip.speed);
            double gain=gainCurve.valueAt(local);
            double pan=panCurve.valueAt(local);
            for(int channel=0;channel<channels;channel++){
                double channelGain=gain;
                if(channels>=2 && channel==0)channelGain*=pan>0?1-pan:1;
                else if(channels>=2 && channel==1)channelGain*=pan<0?1+pan:1;
                if(floating){float sample=input.getFloat();output.putFloat((float)Math.max(-1,Math.min(1,sample*channelGain)));}
                else{int sample=input.getShort();output.putShort((short)Math.max(Short.MIN_VALUE,Math.min(Short.MAX_VALUE,Math.round(sample*channelGain))));}
            }
            frame++;
        }
        // Media3 supplies complete PCM frames. Retain any partial bytes rather than silently dropping them.
        while(input.hasRemaining())output.put(input.get());output.flip();
    }
}
