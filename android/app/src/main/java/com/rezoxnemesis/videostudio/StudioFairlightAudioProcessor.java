package com.rezoxnemesis.videostudio;

import androidx.media3.common.C;
import androidx.media3.common.audio.AudioProcessor;
import androidx.media3.common.audio.BaseAudioProcessor;
import androidx.media3.common.util.UnstableApi;
import org.json.JSONObject;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Actual native Media3 PCM16 audio processor, not a silent preset badge.
 *
 * Processing is bounded and causal, consumes only decoded owned PCM samples
 * and writes to Media3's reusable direct native output buffer. A new instance
 * is created per source audio lane, so simultaneous tracks cannot share
 * envelope/filter state. Does not alter audio length, playback speed or files.
 */
@UnstableApi
public final class StudioFairlightAudioProcessor extends BaseAudioProcessor {
    private final JSONObject spec;
    private StudioFairlightDsp dsp;

    public StudioFairlightAudioProcessor(JSONObject fx) {
        try{
            spec=StudioFairlightSettings.validate(fx);
        }catch(Exception e){
            throw new IllegalArgumentException("Invalid Fairlight audio recipe",e);
        }
    }

    @Override
    protected AudioProcessor.AudioFormat onConfigure(
            AudioProcessor.AudioFormat input) throws AudioProcessor.UnhandledAudioFormatException {
        if(input.encoding!=C.ENCODING_PCM_16BIT
                ||input.sampleRate<8000||input.sampleRate>192000
                ||input.channelCount<1||input.channelCount>8)
            throw new AudioProcessor.UnhandledAudioFormatException(input);
        try{
            dsp=new StudioFairlightDsp(spec,input.sampleRate,input.channelCount);
        }catch(Exception invalid){
            throw new AudioProcessor.UnhandledAudioFormatException(input);
        }
        return input;
    }

    @Override public void queueInput(ByteBuffer input){
        if(input==null||dsp==null)return;
        int frameBytes=dsp.channelCount()*2;
        int complete=(input.remaining()/frameBytes)*frameBytes;
        if(complete<=0)return;
        // Output buffer remains a reusable direct allocation owned by Media3.
        ByteBuffer output=replaceOutputBuffer(complete).order(ByteOrder.nativeOrder());
        input.order(ByteOrder.nativeOrder());
        int samples=complete/2;
        for(int i=0;i<samples;i++)
            output.putShort(dsp.processSample(i%dsp.channelCount(),input.getShort()));
        output.flip();
    }

    @Override protected void onFlush(AudioProcessor.StreamMetadata metadata){
        if(dsp!=null)dsp.reset();
    }
    @Override protected void onReset(){
        dsp=null;
    }
}
