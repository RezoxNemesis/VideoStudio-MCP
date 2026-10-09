package com.rezoxnemesis.videostudio;

import android.media.MediaCodec;
import android.view.Surface;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.decoder.DecoderInputBuffer;
import androidx.media3.transformer.Codec;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

/** Synthetic delegate proves buffering/EOS contracts, not a real encoder's audible output. */
@RunWith(RobolectricTestRunner.class) @Config(sdk=33,manifest=Config.NONE)
public class AacDrainCodecTest {
    private record Packet(byte[] bytes,long time,int flags){}
    private static final class Fake implements Codec {
        final Format format;Format reportedInput;final ArrayList<Packet> packets=new ArrayList<>();boolean available=true,eos,outputEnded,released;int capacity=4096;
        Fake(int encoding,int channels){format=new Format.Builder().setSampleMimeType("audio/mp4a-latm").setSampleRate(48000).setChannelCount(channels).setPcmEncoding(encoding).build();reportedInput=format;}
        public Format getConfigurationFormat(){return format;}public String getName(){return "c2.android.aac.encoder";}public Surface getInputSurface(){return null;}
        public boolean maybeDequeueInputBuffer(DecoderInputBuffer buffer){if(!available||eos||released)return false;buffer.clear();buffer.data=ByteBuffer.allocate(capacity);return true;}
        public void queueInputBuffer(DecoderInputBuffer buffer){assertFalse(released);byte[] bytes=new byte[buffer.data.remaining()];buffer.data.get(bytes);packets.add(new Packet(bytes,buffer.timeUs,buffer.isEndOfStream()?C.BUFFER_FLAG_END_OF_STREAM:0));if(buffer.isEndOfStream())eos=true;}
        public void signalEndOfInputStream(){eos=true;}public Format getInputFormat(){return reportedInput;}public Format getOutputFormat(){return format;}
        public ByteBuffer getOutputBuffer(){return null;}public MediaCodec.BufferInfo getOutputBufferInfo(){return null;}public void releaseOutputBuffer(boolean render){}public void releaseOutputBuffer(long time){}
        public boolean isEnded(){return eos&&outputEnded;}public void release(){released=true;}
    }
    private DecoderInputBuffer input(Codec codec,byte[] bytes,long time,int flags)throws Exception{var buffer=new DecoderInputBuffer(DecoderInputBuffer.BUFFER_REPLACEMENT_MODE_DISABLED);assertTrue(codec.maybeDequeueInputBuffer(buffer));buffer.data.put(bytes).flip();buffer.timeUs=time;buffer.setFlags(flags);return buffer;}
    private void drain(AacDrainCodec codec,Fake fake)throws Exception{for(int i=0;i<10_000&&!fake.eos;i++)codec.getOutputBuffer();assertTrue("Drain must eventually forward a real EOS",fake.eos);}
    @Test public void emptyEosDrainsBoundedSilenceAfterUnchangedProgrammeAndThenForwardsOneEos()throws Exception{
        var fake=new Fake(C.ENCODING_PCM_16BIT,2);var state=new AacDrainCodec.State(2000);var codec=new AacDrainCodec(fake,state);byte[] original=new byte[96*4];for(int i=0;i<original.length;i++)original[i]=(byte)i;
        codec.queueInputBuffer(input(codec,original,0,0));codec.queueInputBuffer(input(codec,new byte[0],2000,C.BUFFER_FLAG_END_OF_STREAM));drain(codec,fake);
        assertArrayEquals(original,fake.packets.get(0).bytes);assertEquals(96,state.programmeFrames);assertEquals(AacDrainCodec.DRAIN_FRAMES,state.appendedFrames);assertEquals(2000,state.programmeUs());assertTrue(state.eosForwarded);long frames=0,previous=1999;int eos=0;
        for(int i=1;i<fake.packets.size();i++){var packet=fake.packets.get(i);assertTrue(packet.time>=previous);previous=packet.time;for(byte b:packet.bytes)assertEquals(0,b);if((packet.flags&C.BUFFER_FLAG_END_OF_STREAM)!=0){eos++;assertEquals(fake.packets.size()-1,i);assertEquals(0,packet.bytes.length);}else{assertEquals(0,packet.bytes.length%4);frames+=packet.bytes.length/4;}}
        assertEquals(AacDrainCodec.DRAIN_FRAMES,frames);assertEquals(1,eos);assertFalse(codec.isEnded());fake.outputEnded=true;assertTrue(codec.isEnded());
    }
    @Test public void backpressureNeverSpinsOrExposesPaddingInputToExporter()throws Exception{
        var fake=new Fake(C.ENCODING_PCM_16BIT,1);fake.capacity=64;var state=new AacDrainCodec.State(500);var codec=new AacDrainCodec(fake,state);codec.queueInputBuffer(input(codec,new byte[48],0,0));codec.queueInputBuffer(input(codec,new byte[0],500,C.BUFFER_FLAG_END_OF_STREAM));fake.available=false;int count=fake.packets.size();var external=new DecoderInputBuffer(0);assertFalse(codec.maybeDequeueInputBuffer(external));codec.getOutputBuffer();assertEquals(count,fake.packets.size());assertFalse(codec.isEnded());fake.available=true;drain(codec,fake);assertEquals(24,state.programmeFrames);assertEquals(AacDrainCodec.DRAIN_FRAMES,state.appendedFrames);
    }
    @Test public void floatPcmTailIsAlignedAndProgrammeCountsStaySeparate()throws Exception{
        var fake=new Fake(C.ENCODING_PCM_FLOAT,2);var state=new AacDrainCodec.State(1000);var codec=new AacDrainCodec(fake,state);codec.queueInputBuffer(input(codec,new byte[48*8],0,0));codec.queueInputBuffer(input(codec,new byte[0],1000,C.BUFFER_FLAG_END_OF_STREAM));drain(codec,fake);assertEquals(8,state.frameBytes);assertEquals(48,state.programmeFrames);assertEquals(AacDrainCodec.DRAIN_FRAMES,state.appendedFrames);
    }
    @Test public void incompletePcmFrameAndWrongProgrammeExtentAreExplicitFailures()throws Exception{
        var fake=new Fake(C.ENCODING_PCM_16BIT,2);var codec=new AacDrainCodec(fake,new AacDrainCodec.State(10_000_000));assertThrows(IllegalArgumentException.class,()->codec.queueInputBuffer(input(codec,new byte[3],0,0)));
        codec.queueInputBuffer(input(codec,new byte[48*4],0,0));assertThrows(IllegalArgumentException.class,()->codec.queueInputBuffer(input(codec,new byte[0],1000,C.BUFFER_FLAG_END_OF_STREAM)));assertFalse(fake.eos);
    }
    @Test public void releasingDuringDrainDoesNotQueueMoreInput()throws Exception{
        var fake=new Fake(C.ENCODING_PCM_16BIT,1);var state=new AacDrainCodec.State(500);var codec=new AacDrainCodec(fake,state);codec.queueInputBuffer(input(codec,new byte[48],0,0));codec.queueInputBuffer(input(codec,new byte[0],500,C.BUFFER_FLAG_END_OF_STREAM));int before=fake.packets.size();codec.release();codec.getOutputBuffer();assertEquals(before,fake.packets.size());assertTrue(fake.released);
    }
    @Test public void omittedPlatformPcmEncodingUsesTheExplicitConfiguredInputType()throws Exception{
        for(int encoding:new int[]{C.ENCODING_PCM_16BIT,C.ENCODING_PCM_FLOAT}){var fake=new Fake(encoding,2);fake.reportedInput=fake.format.buildUpon().setPcmEncoding(Format.NO_VALUE).build();var state=new AacDrainCodec.State(1000);var codec=new AacDrainCodec(fake,state);int frameBytes=encoding==C.ENCODING_PCM_FLOAT?8:4;codec.queueInputBuffer(input(codec,new byte[48*frameBytes],0,0));codec.queueInputBuffer(input(codec,new byte[0],1000,C.BUFFER_FLAG_END_OF_STREAM));drain(codec,fake);assertEquals(frameBytes,state.frameBytes);assertEquals(48,state.programmeFrames);assertEquals(8192,state.appendedFrames);}
    }
    @Test public void omittedPcmTypeNeverInventsOneForAnUnconfiguredCodec()throws Exception{
        assertThrows(IllegalArgumentException.class,()->new AacDrainCodec(new Fake(Format.NO_VALUE,1),new AacDrainCodec.State(1000)));
        var fake=new Fake(C.ENCODING_PCM_16BIT,1);fake.reportedInput=fake.format.buildUpon().setPcmEncoding(C.ENCODING_PCM_24BIT).build();assertThrows(IllegalArgumentException.class,()->new AacDrainCodec(fake,new AacDrainCodec.State(1000)));
    }
}
