package com.rezoxnemesis.videostudio;

import androidx.media3.common.C;
import androidx.media3.common.audio.AudioProcessor;
import androidx.media3.common.audio.BaseAudioProcessor;
import androidx.media3.common.util.UnstableApi;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/** Per-frame gain for every channel with saturating PCM, including gains above unity. */
@UnstableApi
public final class StudioClipAudioProcessor extends BaseAudioProcessor {
    private final StudioClipAudioEnvelope envelope;
    private long frames;
    private long positionOffsetUs;

    public StudioClipAudioProcessor(StudioClipAudioEnvelope envelope){
        if(envelope==null)throw new IllegalArgumentException("Audio envelope required");
        this.envelope=envelope;
    }

    @Override protected AudioFormat onConfigure(AudioFormat input)throws UnhandledAudioFormatException{
        if((input.encoding!=C.ENCODING_PCM_16BIT&&input.encoding!=C.ENCODING_PCM_FLOAT)||
                input.sampleRate<=0||input.channelCount<=0)
            throw new UnhandledAudioFormatException("Clip fades require 16-bit or float PCM",input);
        return envelope.changesAudio()?input:AudioFormat.NOT_SET;
    }

    @Override public void queueInput(ByteBuffer input){
        if(inputAudioFormat.sampleRate<=0||inputAudioFormat.bytesPerFrame<=0)
            throw new IllegalStateException("Configure and flush clip audio before queueing samples");
        int size=input.remaining();
        if(size%inputAudioFormat.bytesPerFrame!=0)
            throw new IllegalArgumentException("Incomplete audio sample frame");
        if(size==0)return;
        ByteBuffer output=replaceOutputBuffer(size).order(ByteOrder.nativeOrder());
        // AudioProcessor PCM is native order; use a view to preserve the caller's buffer order.
        ByteBuffer samples=input.duplicate().order(ByteOrder.nativeOrder());
        while(samples.hasRemaining()){
            float gain=envelope.gainAtOutputTimeUs(positionOffsetUs+frames*1_000_000.0/inputAudioFormat.sampleRate);
            for(int channel=0;channel<inputAudioFormat.channelCount;channel++){
                if(inputAudioFormat.encoding==C.ENCODING_PCM_16BIT){
                    int value=Math.round(samples.getShort()*gain);
                    output.putShort((short)Math.max(Short.MIN_VALUE,Math.min(Short.MAX_VALUE,value)));
                }else{
                    float value=samples.getFloat()*gain;
                    output.putFloat(Float.isNaN(value)?0f:Math.max(-1f,Math.min(1f,value)));
                }
            }
            frames++;
        }
        input.position(input.limit());output.flip();
    }

    @Override protected void onFlush(AudioProcessor.StreamMetadata metadata){
        // Metadata is edited-item local output time; Media3 applies speed before this processor.
        frames=0;positionOffsetUs=Math.max(0,metadata.positionOffsetUs);
    }
    @Override protected void onReset(){frames=0;positionOffsetUs=0;}
}
