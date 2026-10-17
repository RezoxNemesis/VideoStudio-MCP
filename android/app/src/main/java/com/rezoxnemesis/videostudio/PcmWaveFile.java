package com.rezoxnemesis.videostudio;
import java.io.*;
import java.nio.charset.StandardCharsets;
import org.json.JSONObject;

/** Real bounded PCM container validation, independent of Android decoder shadows. */
final class PcmWaveFile {
    static JSONObject inspect(File file)throws Exception {
        if(!file.isFile()||file.length()<44||file.length()>512L*1024*1024)throw new IllegalArgumentException("WAV size unavailable");
        try(RandomAccessFile in=new RandomAccessFile(file,"r")){
            if(!tag(in).equals("RIFF")||u32(in)!=file.length()-8||!tag(in).equals("WAVE"))throw new IllegalArgumentException("Invalid WAV header");
            int channels=0,rate=0,align=0,bits=0;long data=-1;boolean format=false;
            for(int chunks=0;in.getFilePointer()<in.length();chunks++){
                if(chunks>=128||in.length()-in.getFilePointer()<8)throw new IllegalArgumentException("Invalid WAV chunks");
                String tag=tag(in);long bytes=u32(in),start=in.getFilePointer(),next=start+bytes+(bytes&1);
                if(next>in.length())throw new IllegalArgumentException("Truncated WAV chunk");
                if(tag.equals("fmt ")){
                    if(format||bytes<16||bytes>65536||u16(in)!=1)throw new IllegalArgumentException("Only PCM WAV is supported");format=true;
                    channels=u16(in);rate=(int)u32(in);long byteRate=u32(in);align=u16(in);bits=u16(in);
                    if(channels<1||channels>2||rate<8000||rate>192000||bits!=16||align!=channels*2||byteRate!=(long)rate*align)throw new IllegalArgumentException("Unsupported PCM layout");
                }else if(tag.equals("data")){if(data!=-1)throw new IllegalArgumentException("Duplicate WAV data");data=bytes;}
                in.seek(next);
            }
            if(!format||data<1||data%align!=0)throw new IllegalArgumentException("Incomplete PCM WAV");long frames=data/align,duration=frames*1000/rate;
            if(duration<100||duration>600000)throw new IllegalArgumentException("Audio duration unavailable");
            return new JSONObject().put("sampleRate",rate).put("channels",channels).put("sampleFrames",frames).put("durationMs",duration).put("pcmContainerVerified",true);
        }
    }
    private static String tag(RandomAccessFile in)throws IOException {byte[] b=new byte[4];in.readFully(b);return new String(b,StandardCharsets.US_ASCII);}
    private static int u16(RandomAccessFile in)throws IOException {return in.readUnsignedByte()|(in.readUnsignedByte()<<8);}
    private static long u32(RandomAccessFile in)throws IOException {return (long)u16(in)|((long)u16(in)<<16);}
}
