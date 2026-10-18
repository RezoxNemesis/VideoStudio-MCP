package com.rezoxnemesis.videostudio;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import org.json.JSONObject;

/** Real bounded PCM container validation, independent of Android decoder shadows. */
final class PcmWaveFile {
    private static final long MAX_BYTES=512L*1024*1024;

    /** Absolute descriptor offsets, not offsets relative to a provider's backing file. */
    static final class Layout {
        final int channels,sampleRate,blockAlign;
        final long dataOffset,sampleFrames;
        Layout(int channels,int rate,int align,long offset,long frames){
            this.channels=channels;sampleRate=rate;blockAlign=align;dataOffset=offset;sampleFrames=frames;
        }
    }

    static JSONObject inspect(File file)throws Exception {
        if(!file.isFile()||file.length()<44||file.length()>MAX_BYTES)throw new IllegalArgumentException("WAV size unavailable");
        try(FileInputStream in=new FileInputStream(file)){
            Layout pcm=readPcm16(in.getChannel(),0,in.getChannel().size());
            if(pcm==null)throw new IllegalArgumentException("Only PCM WAV is supported");
            return new JSONObject().put("sampleRate",pcm.sampleRate).put("channels",pcm.channels)
                    .put("sampleFrames",pcm.sampleFrames).put("durationMs",pcm.sampleFrames*1000/pcm.sampleRate)
                    .put("pcmContainerVerified",true);
        }
    }

    /** Null means a non-WAV or structurally valid non-PCM format for the codec.
     * A malformed WAV, including unsupported PCM layouts, is always an error.
     * Only bounded headers are read; data may precede fmt and is never buffered. */
    static Layout readPcm16(FileChannel in,long offset,long length)throws IOException {
        if(offset<0||length<0||offset>in.size()||length>in.size()-offset)
            throw new IllegalArgumentException("Invalid WAV descriptor bounds");
        if(length<4)return null;
        if(!tag(read(in,offset,4)).equals("RIFF"))return null;
        if(length<12)throw new IllegalArgumentException("Invalid WAV header");
        ByteBuffer header=read(in,offset+4,8);long declared=u32(header);
        if(!tag(header).equals("WAVE"))return null;
        if(length<44||length>MAX_BYTES||declared!=length-8)
            throw new IllegalArgumentException("Invalid WAV header");
        int channels=0,rate=0,align=0,encoding=0;long data=-1,dataOffset=-1;
        boolean format=false;long position=12;
        for(int chunks=0;position<length;chunks++){
            if(chunks>=128||length-position<8)throw new IllegalArgumentException("Invalid WAV chunks");
            ByteBuffer chunk=read(in,offset+position,8);String name=tag(chunk);long bytes=u32(chunk);
            long start=position+8,next=start+bytes+(bytes&1);
            if(next>length)throw new IllegalArgumentException("Truncated WAV chunk");
            if(name.equals("fmt ")){
                if(format||bytes<16||bytes>65536)throw new IllegalArgumentException("Invalid WAV format chunk");
                format=true;ByteBuffer fmt=read(in,offset+start,16);
                encoding=u16(fmt);channels=u16(fmt);long sampleRate=u32(fmt),byteRate=u32(fmt);
                align=u16(fmt);int bits=u16(fmt);
                if(encoding==1){
                    if(channels<1||channels>2||sampleRate<8000||sampleRate>192000||bits!=16||
                            align!=channels*2||byteRate!=sampleRate*align)
                        throw new IllegalArgumentException("Unsupported PCM layout");
                }else if(encoding==0||channels<1||sampleRate<1||sampleRate>Integer.MAX_VALUE||align<1||byteRate<1){
                    throw new IllegalArgumentException("Invalid WAV format");
                }
                rate=(int)sampleRate;
            }else if(name.equals("data")){
                if(data!=-1)throw new IllegalArgumentException("Duplicate WAV data");
                data=bytes;dataOffset=offset+start;
            }
            position=next;
        }
        if(!format||data<1)throw new IllegalArgumentException("Incomplete WAV");
        if(encoding!=1)return null;
        if(data%align!=0)throw new IllegalArgumentException("Incomplete PCM WAV");
        long frames=data/align,duration=frames*1000/rate;
        if(duration<100||duration>600000)throw new IllegalArgumentException("Audio duration unavailable");
        return new Layout(channels,rate,align,dataOffset,frames);
    }

    static void readFully(FileChannel in,ByteBuffer bytes,long offset)throws IOException {
        while(bytes.hasRemaining()){
            if(Thread.currentThread().isInterrupted())throw new InterruptedIOException("PCM read interrupted");
            int count=in.read(bytes,offset);
            if(count<=0)throw new IllegalArgumentException("Truncated WAV samples");
            offset+=count;
        }
        bytes.flip();
    }
    private static ByteBuffer read(FileChannel in,long offset,int count)throws IOException {
        ByteBuffer bytes=ByteBuffer.allocate(count).order(ByteOrder.LITTLE_ENDIAN);
        readFully(in,bytes,offset);return bytes;
    }
    private static String tag(ByteBuffer in){byte[] b=new byte[4];in.get(b);return new String(b,StandardCharsets.US_ASCII);}
    private static int u16(ByteBuffer in){return in.getShort()&0xffff;}
    private static long u32(ByteBuffer in){return in.getInt()&0xffffffffL;}
}
