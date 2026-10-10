package com.rezoxnemesis.videostudio;

import androidx.media3.common.C;
import androidx.media3.common.audio.BaseAudioProcessor;
import androidx.media3.common.util.UnstableApi;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/** Streaming gain for any decoded PCM channel count; clips rather than wrapping. */
@UnstableApi
final class PcmGainProcessor extends BaseAudioProcessor {
    private final AudioGainEnvelope gain;
    private long sampleIndex;

    PcmGainProcessor(ProjectStore.Clip clip) {
        if (!Float.isFinite(clip.volume) || clip.volume < 0 || clip.volume > 2) throw new IllegalArgumentException("Volume must be between 0 and 2");
        this.gain=new AudioGainEnvelope(clip);
    }

    @Override protected AudioFormat onConfigure(AudioFormat format) throws UnhandledAudioFormatException {
        if(format.encoding!=C.ENCODING_PCM_16BIT && format.encoding!=C.ENCODING_PCM_FLOAT) throw new UnhandledAudioFormatException(format);
        return gain.isConstantUnity() ? AudioFormat.NOT_SET : format;
    }

    @Override public void queueInput(ByteBuffer input) {
        input.order(ByteOrder.nativeOrder());
        int bytes=inputAudioFormat.encoding==C.ENCODING_PCM_FLOAT ? Float.BYTES : Short.BYTES;
        if(input.remaining()%bytes!=0) throw new IllegalArgumentException("Unaligned PCM samples");
        ByteBuffer output=replaceOutputBuffer(input.remaining());
        if(bytes==Float.BYTES) {
            while(input.hasRemaining()) {
                float sample=input.getFloat()*nextGain();
                output.putFloat(Float.isFinite(sample)?Math.max(-1,Math.min(1,sample)):0);
            }
        } else {
            while(input.hasRemaining()) {
                int sample=Math.round(input.getShort()*nextGain());
                output.putShort((short)Math.max(Short.MIN_VALUE,Math.min(Short.MAX_VALUE,sample)));
            }
        }
        output.flip();
    }
    @Override protected void onFlush() { sampleIndex=0; }
    private float nextGain() {
        long timeUs=sampleIndex/inputAudioFormat.channelCount*1000000L/inputAudioFormat.sampleRate;
        sampleIndex++;
        return gain.at(timeUs);
    }
}
