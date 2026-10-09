package com.rezoxnemesis.videostudio;

import java.io.FileDescriptor;
import java.io.EOFException;
import java.io.InterruptedIOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import android.system.Os;

/** Trim encoder-only padding through existing edit metadata, never AAC payload/sample tables. */
final class GaplessAudioMp4 {
    interface Io {long size()throws Exception;int read(byte[] data,int offset,int length,long position)throws Exception;int write(byte[] data,int offset,int length,long position)throws Exception;void sync()throws Exception;}
    record TrimInfo(long delayUnits,long paddingUnits,long rawUnits,long presentationMovieUnits,int mediaTimescale,int movieTimescale){}
    static TrimInfo trim(FileDescriptor descriptor,long programmeUs,long maxPaddingUs,BooleanSupplier cancelled)throws Exception{
        return trim(new Io(){public long size()throws Exception{return Os.fstat(descriptor).st_size;}public int read(byte[] data,int offset,int length,long position)throws Exception{return Os.pread(descriptor,data,offset,length,position);}public int write(byte[] data,int offset,int length,long position)throws Exception{return Os.pwrite(descriptor,data,offset,length,position);}public void sync()throws Exception{Os.fsync(descriptor);}},programmeUs,maxPaddingUs,cancelled);
    }
    static TrimInfo trim(Io io,long programmeUs,long maxPaddingUs,BooleanSupplier cancelled)throws Exception{
        if(programmeUs<=0||maxPaddingUs<0||maxPaddingUs>2_000_000)throw new IllegalArgumentException("Invalid AAC trim interval");
        Reader input=new Reader(io,cancelled);input.check();Box movie=one(input.children(0,io.size()),"moov");List<Box> contents=input.children(movie.payload,movie.end);
        Duration movieDuration=duration(input,one(contents,"mvhd"),false);Box audio=null;Duration audioHeader=null;long longestOther=0;int tracks=0;
        for(Box track:contents)if(track.type.equals("trak")){
            if(++tracks>32)throw new IllegalArgumentException("Too many MP4 tracks for bounded AAC trim");List<Box> fields=input.children(track.payload,track.end);Duration header=duration(input,one(fields,"tkhd"),true);Box media=one(fields,"mdia");Box handler=one(input.children(media.payload,media.end),"hdlr");String type=new String(input.read(handler,8,4),StandardCharsets.US_ASCII);
            if(type.equals("soun")){if(audio!=null)throw new IllegalArgumentException("Ambiguous continuous audio track");audio=track;audioHeader=header;}else longestOther=Math.max(longestOther,header.value);
        }
        if(audio==null)throw new IllegalArgumentException("Missing continuous AAC track");List<Box> track=input.children(audio.payload,audio.end);Box media=one(track,"mdia");List<Box> fields=input.children(media.payload,media.end);Duration raw=duration(input,one(fields,"mdhd"),false);
        Box minf=one(fields,"minf"),stbl=one(input.children(minf.payload,minf.end),"stbl"),stsd=one(input.children(stbl.payload,stbl.end),"stsd");if(u32(input.read(stsd,4,4))!=1||!one(input.children(stsd.payload+8,stsd.end),"mp4a").type.equals("mp4a"))throw new IllegalArgumentException("AAC sample entry is not singular");
        long plannedRaw=units(programmeUs,raw.scale),plannedMovie=units(programmeUs,movieDuration.scale);Box edits=optional(track,"edts");
        if(edits==null){
            if(plannedRaw<=0||plannedMovie<=0||raw.value!=plannedRaw||audioHeader.value!=plannedMovie||movieDuration.value!=Math.max(plannedMovie,longestOther))throw new IllegalArgumentException("AAC requires presentation trimming but has no supported edit list");
            input.check();return new TrimInfo(0,0,raw.value,plannedMovie,raw.scale,movieDuration.scale);
        }
        Box edit=one(input.children(edits.payload,edits.end),"elst");int version=version(input,edit);if(u32(input.read(edit,4,4))!=1)throw new IllegalArgumentException("Unsupported AAC edit list");
        int width=version==0?4:8;long existing=number(input.read(edit,8,width),false),delay=number(input.read(edit,8+width,width),true);byte[] rate=input.read(edit,8+width*2,4);if(delay<0||ByteBuffer.wrap(rate).getInt()!=0x00010000)throw new IllegalArgumentException("Unsupported AAC edit rate or delay");
        long padding=Math.subtractExact(Math.subtractExact(raw.value,delay),plannedRaw);
        if(plannedRaw<=0||plannedMovie<=0||padding<0||padding>units(maxPaddingUs,raw.scale)||existing<plannedMovie)throw new IllegalArgumentException("Encoded AAC does not cover the programme with bounded codec padding");
        // Validate every field before changing any byte. Raw mdhd, stts, media_time and packets remain intact.
        byte[] editValue=encoded(plannedMovie,width),audioValue=encoded(plannedMovie,audioHeader.width),movieValue=encoded(Math.max(plannedMovie,longestOther),movieDuration.width);
        input.check();input.write(edit.payload+8,editValue);input.write(audioHeader.position,audioValue);input.write(movieDuration.position,movieValue);io.sync();
        return new TrimInfo(delay,padding,raw.value,plannedMovie,raw.scale,movieDuration.scale);
    }
    private record Box(String type,long payload,long end){}
    private record Duration(long position,int width,long value,int scale){}
    private static Duration duration(Reader input,Box box,boolean track)throws Exception{
        int version=version(input,box),width=version==0?4:8,offset=track?(version==0?20:28):(version==0?16:24);
        long scale=track?0:u32(input.read(box,version==0?12:20,4));if(!track&&(scale==0||scale>Integer.MAX_VALUE))throw new IllegalArgumentException("Unsupported MP4 timescale");
        return new Duration(box.payload+offset,width,number(input.read(box,offset,width),false),(int)scale);
    }
    private static int version(Reader input,Box box)throws Exception{int version=input.read(box,0,1)[0]&255;if(version>1)throw new IllegalArgumentException("Unsupported MP4 duration version");return version;}
    private static long units(long us,int scale){return Math.addExact(Math.multiplyExact(us,(long)scale),500_000L)/1_000_000L;}
    private static long u32(byte[] bytes){return Integer.toUnsignedLong(ByteBuffer.wrap(bytes).getInt());}
    private static long number(byte[] bytes,boolean signed){long result=bytes.length==4?(signed?ByteBuffer.wrap(bytes).getInt():u32(bytes)):ByteBuffer.wrap(bytes).getLong();if(!signed&&result<0)throw new IllegalArgumentException("MP4 duration exceeds signed64-bit bound");return result;}
    private static byte[] encoded(long value,int width){if(value<0||(width==4&&value>0xffffffffL))throw new IllegalArgumentException("MP4 duration exceeds its existing field");return width==4?ByteBuffer.allocate(4).putInt((int)value).array():ByteBuffer.allocate(8).putLong(value).array();}
    private static Box optional(List<Box> boxes,String type){Box found=null;for(Box box:boxes)if(box.type.equals(type)){if(found!=null)throw new IllegalArgumentException("Duplicate MP4 "+type+" box");found=box;}return found;}
    private static Box one(List<Box> boxes,String type){Box found=optional(boxes,type);if(found==null)throw new IllegalArgumentException("Missing MP4 "+type+" box");return found;}
    private static final class Reader {
        final Io io;final BooleanSupplier cancelled;int boxes;
        Reader(Io io,BooleanSupplier cancelled){this.io=io;this.cancelled=cancelled;}
        void check()throws InterruptedIOException{if(Thread.currentThread().isInterrupted()||cancelled.getAsBoolean())throw new InterruptedIOException("AAC metadata trim cancelled");}
        List<Box> children(long start,long end)throws Exception{
            if(start<0||end<start)throw new IllegalArgumentException("Invalid MP4 parent bounds");var result=new ArrayList<Box>();long at=start;
            while(at<end){check();if(++boxes>4096||end-at<8)throw new IllegalArgumentException("MP4 box plan exceeds bounds");byte[] header=read(at,8);long size=u32(header);int head=8;if(size==1){if(end-at<16)throw new IllegalArgumentException("Truncated extended MP4 header");size=number(read(at+8,8),false);head=16;}else if(size==0)size=end-at;
                if(size<head||size>end-at)throw new IllegalArgumentException("MP4 box exceeds parent bounds");String type=new String(header,4,4,StandardCharsets.US_ASCII);result.add(new Box(type,at+head,at+size));at+=size;
            }return result;
        }
        byte[] read(Box box,int offset,int bytes)throws Exception{if(offset<0||bytes<0||box.end-box.payload-offset<bytes)throw new IllegalArgumentException("Truncated MP4 duration field");return read(box.payload+offset,bytes);}
        byte[] read(long position,int bytes)throws Exception{check();byte[] data=new byte[bytes];int done=0;while(done<bytes){check();int count=io.read(data,done,bytes-done,position+done);if(count<=0)throw new EOFException("MP4 metadata read ended early");done+=count;}return data;}
        void write(long position,byte[] data)throws Exception{int done=0;while(done<data.length){check();int count=io.write(data,done,data.length-done,position+done);if(count<=0)throw new EOFException("MP4 metadata write ended early");done+=count;}}
    }
}
