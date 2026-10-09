package com.rezoxnemesis.videostudio;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.UUID;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

/** Synthetic MP4 boxes and real Java FileChannel I/O; not Android syscalls or encoded audio. */
@RunWith(RobolectricTestRunner.class) @Config(sdk=33,manifest=Config.NONE)
public class GaplessAudioMp4Test {
    private GaplessAudioMp4.Io borrowed(RandomAccessFile file){return new GaplessAudioMp4.Io(){public long size()throws Exception{return file.length();}public int read(byte[] data,int offset,int length,long position)throws Exception{return file.getChannel().read(ByteBuffer.wrap(data,offset,length),position);}public int write(byte[] data,int offset,int length,long position)throws Exception{return file.getChannel().write(ByteBuffer.wrap(data,offset,length),position);}public void sync()throws Exception{file.getFD().sync();}};}
    private byte[] join(byte[]... parts)throws Exception{var out=new ByteArrayOutputStream();for(byte[] part:parts)out.write(part);return out.toByteArray();}
    private byte[] box(String type,byte[]... payload)throws Exception{byte[] bytes=join(payload);return join(ByteBuffer.allocate(8).putInt(bytes.length+8).put(type.getBytes(java.nio.charset.StandardCharsets.US_ASCII)).array(),bytes);}
    private byte[] duration(String type,boolean wide,int scale,long duration)throws Exception{var data=ByteBuffer.allocate(wide?32:20);data.putInt(wide?1<<24:0);if(wide)data.putLong(0).putLong(0);else data.putInt(0).putInt(0);data.putInt(scale);if(wide)data.putLong(duration);else data.putInt((int)duration);return box(type,data.array());}
    private byte[] tkhd(boolean wide,int id,long duration)throws Exception{var data=ByteBuffer.allocate(wide?36:24);data.putInt((wide?1<<24:0)|3);if(wide)data.putLong(0).putLong(0);else data.putInt(0).putInt(0);data.putInt(id).putInt(0);if(wide)data.putLong(duration);else data.putInt((int)duration);return box("tkhd",data.array());}
    private byte[] track(boolean wide,boolean audio,long raw,long presented,long delay)throws Exception{
        var hdlr=ByteBuffer.allocate(12).putInt(0).putInt(0).put((audio?"soun":"vide").getBytes(java.nio.charset.StandardCharsets.US_ASCII));var edit=ByteBuffer.allocate(wide?28:20).putInt(wide?1<<24:0).putInt(1);if(wide)edit.putLong(presented).putLong(delay);else edit.putInt((int)presented).putInt((int)delay);edit.putShort((short)1).putShort((short)0);
        byte[] stsd=box("stsd",ByteBuffer.allocate(8).putInt(0).putInt(1).array(),box(audio?"mp4a":"avc1",new byte[28]));
        return box("trak",tkhd(wide,audio?1:2,presented),delay<0?new byte[0]:box("edts",box("elst",edit.array())),box("mdia",duration("mdhd",wide,audio?48000:90000,raw),box("hdlr",hdlr.array()),box("minf",box("stbl",stsd,box("stts",new byte[]{0,0,0,0,4,5,6,7})))));
    }
    private byte[] fixture(boolean wide,boolean video,long raw,long presented)throws Exception{return join(box("ftyp",new byte[16]),box("mdat",new byte[]{11,12,13,14,15}),box("moov",duration("mvhd",wide,1000,Math.max(presented,video?12000:0)),track(wide,true,raw,presented,1600),video?track(wide,false,1080000,12000,0):new byte[0]));}
    private File file(byte[] bytes)throws Exception{File f=new File(RuntimeEnvironment.getApplication().getFilesDir(),"mp4-trim-"+UUID.randomUUID());Files.write(f.toPath(),bytes);return f;}
    private long value(byte[] bytes,String type,int offset,boolean wide){int start=-1;byte[] needle=type.getBytes(java.nio.charset.StandardCharsets.US_ASCII);for(int i=4;i<bytes.length-4;i++)if(Arrays.equals(needle,Arrays.copyOfRange(bytes,i,i+4))){start=i+4;break;}assertTrue(start>=0);var data=ByteBuffer.wrap(bytes);return wide?data.getLong(start+offset):Integer.toUnsignedLong(data.getInt(start+offset));}
    @Test public void versionZeroTrimKeepsRawAudioPayloadTablesAndDelayUnchanged()throws Exception{
        byte[] before=fixture(false,false,482624,10021);File f=file(before);try(var opened=new RandomAccessFile(f,"rw")){opened.seek(9);var proof=GaplessAudioMp4.trim(borrowed(opened),10_000_000,200_000,()->false);assertEquals(9,opened.getFilePointer());assertEquals(1600,proof.delayUnits());assertEquals(1024,proof.paddingUnits());assertEquals(482624,proof.rawUnits());}
        byte[] after=Files.readAllBytes(f.toPath());assertEquals(before.length,after.length);assertEquals(10000,value(after,"mvhd",16,false));assertEquals(10000,value(after,"tkhd",20,false));assertEquals(10000,value(after,"elst",8,false));assertEquals(1600,value(after,"elst",12,false));assertEquals(482624,value(after,"mdhd",16,false));int differences=0;for(int i=0;i<before.length;i++)if(before[i]!=after[i])differences++;assertTrue("Only existing duration fields may change",differences<=12);assertArrayEquals(new byte[]{11,12,13,14,15},Arrays.copyOfRange(after,32,37));
    }
    @Test public void versionOneTrimPreservesLongerVideoAndIsIdempotent()throws Exception{
        File f=file(fixture(true,true,482624,10021));try(var opened=new RandomAccessFile(f,"rw")){GaplessAudioMp4.trim(borrowed(opened),10_000_000,200_000,()->false);byte[] once=Files.readAllBytes(f.toPath());assertEquals(12000,value(once,"mvhd",24,true));assertEquals(10000,value(once,"tkhd",28,true));assertEquals(10000,value(once,"elst",8,true));assertEquals(1600,value(once,"elst",16,true));GaplessAudioMp4.trim(borrowed(opened),10_000_000,200_000,()->false);assertArrayEquals(once,Files.readAllBytes(f.toPath()));}
    }
    @Test public void shortAudioOrExcessivePaddingIsRejectedBeforeAnyMutation()throws Exception{
        for(long raw:new long[]{481599,960000}){byte[] before=fixture(false,false,raw,10021);File f=file(before);try(var opened=new RandomAccessFile(f,"rw")){assertThrows(IllegalArgumentException.class,()->GaplessAudioMp4.trim(borrowed(opened),10_000_000,200_000,()->false));}assertArrayEquals(before,Files.readAllBytes(f.toPath()));}
    }
    @Test public void cancellationAndMalformedBoxBoundsPreserveEntireFile()throws Exception{
        byte[] before=fixture(false,false,482624,10021);File f=file(before);try(var opened=new RandomAccessFile(f,"rw")){assertThrows(InterruptedIOException.class,()->GaplessAudioMp4.trim(borrowed(opened),10_000_000,200_000,()->true));}assertArrayEquals(before,Files.readAllBytes(f.toPath()));
        ByteBuffer.wrap(before).putInt(Integer.MAX_VALUE);File broken=file(before);try(var opened=new RandomAccessFile(broken,"rw")){assertThrows(IllegalArgumentException.class,()->GaplessAudioMp4.trim(borrowed(opened),10_000_000,200_000,()->false));}assertArrayEquals(before,Files.readAllBytes(broken.toPath()));
    }
    @Test public void editCannotExtendAnAlreadyShortPresentationEvenWhenRawPacketsExist()throws Exception{
        byte[] before=fixture(false,false,482624,9000);File f=file(before);try(var opened=new RandomAccessFile(f,"rw")){assertThrows(IllegalArgumentException.class,()->GaplessAudioMp4.trim(borrowed(opened),10_000_000,200_000,()->false));}assertArrayEquals(before,Files.readAllBytes(f.toPath()));
    }
    @Test public void exactZeroDelayAudioWithoutEditsIsAcceptedWithoutChangingAnyByte()throws Exception{
        for(boolean wide:new boolean[]{false,true}){byte[] before=join(box("ftyp",new byte[16]),box("mdat",new byte[]{11,12,13}),box("moov",duration("mvhd",wide,1000,10000),track(wide,true,480000,10000,-1)));File f=file(before);
            try(var opened=new RandomAccessFile(f,"rw")){opened.seek(9);var proof=GaplessAudioMp4.trim(borrowed(opened),10_000_000,200_000,()->false);assertEquals(0,proof.delayUnits());assertEquals(0,proof.paddingUnits());assertEquals(9,opened.getFilePointer());}assertArrayEquals(before,Files.readAllBytes(f.toPath()));}
    }
    @Test public void missingEditsNeverPermitPaddingOrWrongPresentationDuration()throws Exception{
        for(long[] times:new long[][]{{481024,10000},{480000,10021}}){byte[] before=box("moov",duration("mvhd",false,1000,times[1]),track(false,true,times[0],times[1],-1));File f=file(before);try(var opened=new RandomAccessFile(f,"rw")){assertThrows(IllegalArgumentException.class,()->GaplessAudioMp4.trim(borrowed(opened),10_000_000,200_000,()->false));}assertArrayEquals(before,Files.readAllBytes(f.toPath()));}
    }
    @Test public void sparseFiveGiBMediaDataIsSkippedWithBoundedMetadataReads()throws Exception{
        File f=file(new byte[0]);try(var opened=new RandomAccessFile(f,"rw")){opened.write(box("ftyp",new byte[16]));long media=5L*1024*1024*1024;opened.write(ByteBuffer.allocate(16).putInt(1).put("mdat".getBytes(java.nio.charset.StandardCharsets.US_ASCII)).putLong(media).array());opened.seek(24+media);opened.write(box("moov",duration("mvhd",true,1000,10021),track(true,true,482624,10021,1600)));long size=opened.length();var count=new java.util.concurrent.atomic.AtomicLong();var base=borrowed(opened);
            var bounded=new GaplessAudioMp4.Io(){public long size()throws Exception{return base.size();}public int read(byte[] data,int offset,int length,long position)throws Exception{count.addAndGet(length);assertTrue("Parser must not read media payload",count.get()<10000);return base.read(data,offset,length,position);}public int write(byte[] data,int offset,int length,long position)throws Exception{return base.write(data,offset,length,position);}public void sync()throws Exception{base.sync();}};
            assertEquals(1024,GaplessAudioMp4.trim(bounded,10_000_000,200_000,()->false).paddingUnits());assertEquals(size,opened.length());assertTrue(count.get()<10000);
        }finally{assertTrue(f.delete());}
    }

    /** Real mux/extractor round trip of synthetic packets; actual decoded PCM is a device check. */
    @Test public void drainedOutputCanBeReimportedWithExactGaplessSampleCounts()throws Exception{
        for(int rate:new int[]{8000,44100,48000})for(long programmeUs:new long[]{100_000,1_000_000,10_000_000}){
            long frames=programmeUs*rate/1_000_000,rawFrames=(frames+AacDrainCodec.DRAIN_FRAMES)/1024*1024;
            File output=file(new byte[0]);
            try(var stream=new FileOutputStream(output)){
                var muxer=new androidx.media3.muxer.Mp4Muxer.Builder(stream).build();
                var format=new androidx.media3.common.Format.Builder().setSampleMimeType("audio/mp4a-latm").setSampleRate(rate).setChannelCount(1)
                        .setInitializationData(java.util.List.of(androidx.media3.extractor.AacUtil.buildAacLcAudioSpecificConfig(rate,1))).build();
                int track=muxer.addTrack(format);
                for(long at=0;at<rawFrames;at+=1024)muxer.writeSampleData(track,ByteBuffer.wrap(new byte[]{1,2,3}),new androidx.media3.muxer.BufferInfo((at-1600)*1_000_000/rate,3,androidx.media3.common.C.BUFFER_FLAG_KEY_FRAME));
                muxer.close();
            }
            GaplessAudioMp4.TrimInfo proof;
            try(var opened=new RandomAccessFile(output,"rw")){proof=GaplessAudioMp4.trim(borrowed(opened),programmeUs,GaplessAudioMuxer.maxPaddingUs(rate),()->false);}
            var extracted=new java.util.concurrent.atomic.AtomicReference<androidx.media3.common.Format>();
            var extractor=new androidx.media3.extractor.mp4.Mp4Extractor();
            extractor.init(new androidx.media3.extractor.ExtractorOutput(){
                public androidx.media3.extractor.TrackOutput track(int id,int type){assertEquals(androidx.media3.common.C.TRACK_TYPE_AUDIO,type);return new androidx.media3.extractor.ForwardingTrackOutput(new androidx.media3.extractor.DiscardingTrackOutput()){
                    @Override public void format(androidx.media3.common.Format format){extracted.set(format);super.format(format);}
                };}
                public void endTracks(){}public void seekMap(androidx.media3.extractor.SeekMap map){}
            });
            try(var opened=new RandomAccessFile(output,"r")){
                var input=new androidx.media3.extractor.DefaultExtractorInput(opened::read,0,opened.length());var seek=new androidx.media3.extractor.PositionHolder();boolean ended=false;
                for(int n=0;n<2000;n++){int result=extractor.read(input,seek);if(result==androidx.media3.extractor.Extractor.RESULT_END_OF_INPUT){ended=true;break;}if(result==androidx.media3.extractor.Extractor.RESULT_SEEK){opened.seek(seek.position);input=new androidx.media3.extractor.DefaultExtractorInput(opened::read,seek.position,opened.length());}}
                assertTrue("Bounded mux/extractor fixture must finish",ended);
            }finally{extractor.release();}
            assertNotNull(extracted.get());assertEquals("Decoder must receive the exact leading trim at "+rate+"Hz",proof.delayUnits(),extracted.get().encoderDelay);
            assertEquals("Decoder must receive the exact trailing trim at "+rate+"Hz for "+programmeUs+"us",proof.paddingUnits(),extracted.get().encoderPadding);
        }
    }
}
