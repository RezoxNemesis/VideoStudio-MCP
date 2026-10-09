package com.rezoxnemesis.videostudio;

import android.media.MediaCodec;
import android.view.Surface;
import androidx.media3.common.Format;
import androidx.media3.common.C;
import androidx.media3.decoder.DecoderInputBuffer;
import androidx.media3.transformer.Codec;
import androidx.media3.transformer.ExportException;
import java.nio.ByteBuffer;

/** Codec-boundary drain padding; the mux metadata must remove only the added tail. */
final class AacDrainCodec implements Codec {
    // Four LC AAC frames flush the measured C2 tail. More can place the edit
    // outside Media3's four-packet gapless trim window and break exact re-import.
    static final int DRAIN_FRAMES=4096;
    static final class State {
        final long expectedUs;long programmeFrames,appendedFrames;int sampleRate,frameBytes;boolean eosForwarded;GaplessAudioMp4.TrimInfo trim;
        State(long expectedUs){if(expectedUs<=0)throw new IllegalArgumentException("Invalid audio programme duration");this.expectedUs=expectedUs;}
        long programmeUs(){return Math.multiplyExact(programmeFrames,1_000_000L)/sampleRate;}
    }
    private final Codec delegate;final State state;
    private final DecoderInputBuffer padding=new DecoderInputBuffer(DecoderInputBuffer.BUFFER_REPLACEMENT_MODE_DISABLED);
    private boolean draining;private volatile boolean released;
    AacDrainCodec(Codec delegate,State state)throws ExportException {
        this.delegate=delegate;this.state=state;Format input=delegate.getInputFormat();
        // C2 may omit KEY_PCM_ENCODING from its input report. Use the explicit format
        // actually configured by AudioSampleExporter, never assume a default PCM type.
        int encoding=input.pcmEncoding==Format.NO_VALUE?delegate.getConfigurationFormat().pcmEncoding:input.pcmEncoding;
        if(input.sampleRate<=0||input.channelCount<1||input.channelCount>32||(encoding!=C.ENCODING_PCM_16BIT&&encoding!=C.ENCODING_PCM_FLOAT))throw new IllegalArgumentException("Unsupported AAC drain PCM format: "+input+", configuredPCM="+encoding);
        state.sampleRate=input.sampleRate;state.frameBytes=Math.multiplyExact(input.channelCount,encoding==C.ENCODING_PCM_FLOAT?4:2);
    }
    public Format getConfigurationFormat(){return delegate.getConfigurationFormat();}
    public String getName(){return delegate.getName();}
    public Surface getInputSurface(){return delegate.getInputSurface();}
    public int getMaxPendingFrameCount(){return delegate.getMaxPendingFrameCount();}
    public boolean maybeDequeueInputBuffer(DecoderInputBuffer buffer)throws ExportException{if(released)return false;if(draining){pump();return false;}return delegate.maybeDequeueInputBuffer(buffer);}
    public void queueInputBuffer(DecoderInputBuffer buffer)throws ExportException{
        if(released||draining)throw new IllegalStateException("AAC programme input is retired");
        int bytes=buffer.data==null?0:buffer.data.remaining();if(bytes%state.frameBytes!=0)throw new IllegalArgumentException("Incomplete AAC PCM input frame");
        state.programmeFrames=Math.addExact(state.programmeFrames,bytes/state.frameBytes);
        if(!buffer.isEndOfStream()){delegate.queueInputBuffer(buffer);return;}
        if(state.programmeFrames==0||Math.abs(state.programmeUs()-state.expectedUs)>1000)throw new IllegalArgumentException("PCM programme does not span the planned audio interval: "+state.programmeFrames+" frames at "+state.sampleRate+"Hz, "+state.programmeUs()+"us, expected "+state.expectedUs+"us");
        draining=true;
        if(bytes>0){buffer.setFlags(0);delegate.queueInputBuffer(buffer);}else feedPadding(buffer);
    }
    private void pump()throws ExportException{if(!released&&draining&&!state.eosForwarded&&delegate.maybeDequeueInputBuffer(padding))feedPadding(padding);}
    private void feedPadding(DecoderInputBuffer buffer)throws ExportException{
        if(buffer.data==null||buffer.data.capacity()<state.frameBytes)throw new IllegalArgumentException("AAC input buffer cannot hold one PCM frame");
        buffer.clear();long remaining=DRAIN_FRAMES-state.appendedFrames;int frames=(int)Math.min(remaining,buffer.data.capacity()/state.frameBytes);
        buffer.timeUs=Math.multiplyExact(Math.addExact(state.programmeFrames,state.appendedFrames),1_000_000L)/state.sampleRate;
        for(int i=0;i<frames*state.frameBytes;i++)buffer.data.put((byte)0);buffer.flip();
        buffer.setFlags(frames==0?C.BUFFER_FLAG_END_OF_STREAM:0);delegate.queueInputBuffer(buffer);state.appendedFrames+=frames;if(frames==0)state.eosForwarded=true;
    }
    public void signalEndOfInputStream()throws ExportException{delegate.signalEndOfInputStream();}
    public Format getInputFormat()throws ExportException{return delegate.getInputFormat();}
    public Format getOutputFormat()throws ExportException{pump();return delegate.getOutputFormat();}
    public ByteBuffer getOutputBuffer()throws ExportException{if(released)return null;pump();return delegate.getOutputBuffer();}
    public MediaCodec.BufferInfo getOutputBufferInfo()throws ExportException{pump();return delegate.getOutputBufferInfo();}
    public void releaseOutputBuffer(boolean render)throws ExportException{delegate.releaseOutputBuffer(render);pump();}
    public void releaseOutputBuffer(long timeUs)throws ExportException{delegate.releaseOutputBuffer(timeUs);pump();}
    public boolean isEnded(){return state.eosForwarded&&delegate.isEnded();}
    public void release(){if(!released){released=true;delegate.release();}}
}
