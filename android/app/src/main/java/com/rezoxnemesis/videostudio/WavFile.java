package com.rezoxnemesis.videostudio;
import java.io.*;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.nio.file.Files;
public final class WavFile {
    private WavFile(){}
    public static final class Info {
        public int sampleRate,channels,format,bitsPerSample,blockAlign;
        public long frameCount,dataBytes,dataOffset;
        public long durationMs(){return Math.multiplyExact(frameCount,1000L)/sampleRate;}
        boolean compatible(Info other){return sampleRate==other.sampleRate&&channels==other.channels&&format==other.format&&bitsPerSample==other.bitsPerSample;}
    }
    /** Validates PCM data ranges without reading an entire narration into memory. */
    public static Info inspect(File file)throws IOException{
        try(RandomAccessFile in=new RandomAccessFile(file,"r")){
            if(in.length()<44||in.readInt()!=0x52494646)throw new IOException("Invalid WAV RIFF header");
            long end=Math.addExact(u32(in),8);if(end!=in.length()||in.readInt()!=0x57415645)throw new IOException("Truncated or invalid WAV container");
            Info info=new Info();boolean hasFormat=false,hasData=false;
            while(in.getFilePointer()<end){
                if(end-in.getFilePointer()<8)throw new IOException("Incomplete WAV chunk header");
                int id=in.readInt();long size=u32(in),offset=in.getFilePointer(),next=offset+size+(size&1);
                if(next>end)throw new IOException("Truncated WAV chunk");
                if(id==0x666d7420){
                    if(hasFormat||size<16)throw new IOException("Invalid WAV format chunk");
                    info.format=u16(in);info.channels=u16(in);long rate=u32(in),byteRate=u32(in);
                    info.blockAlign=u16(in);info.bitsPerSample=u16(in);
                    if(rate<8000||rate>192000||info.channels<1||info.channels>8||
                       !((info.format==1&&info.bitsPerSample==16)||(info.format==3&&info.bitsPerSample==32))||
                       info.blockAlign!=info.channels*(info.bitsPerSample/8)||byteRate!=rate*info.blockAlign)
                        throw new IOException("Unsupported or inconsistent WAV PCM format");
                    info.sampleRate=(int)rate;hasFormat=true;
                }else if(id==0x64617461){
                    if(hasData)throw new IOException("Multiple WAV data chunks are unsupported");
                    info.dataOffset=offset;info.dataBytes=size;hasData=true;
                }
                in.seek(next);
            }
            if(!hasFormat||!hasData||info.dataBytes<=0||info.dataBytes%info.blockAlign!=0)throw new IOException("WAV contains no complete PCM frames");
            info.frameCount=info.dataBytes/info.blockAlign;return info;
        }
    }
    /** Publishes only a complete, synced WAV, never replacing an existing media file. */
    public static void concatenate(List<File> parts,File target,BooleanSupplier cancelled)throws IOException{
        if(parts==null||parts.isEmpty())throw new IllegalArgumentException("Narration parts are required");
        if(target.exists())throw new IOException("Narration output already exists");
        java.util.ArrayList<Info> formats=new java.util.ArrayList<>();Info base=null;long total=0;
        for(File part:parts){checkCancelled(cancelled);Info info=inspect(part);if(base==null)base=info;else if(!base.compatible(info))throw new IOException("Speech engine changed PCM format between chunks");formats.add(info);total=Math.addExact(total,info.dataBytes);}
        if(total>0xffff_ffffL-36)throw new IOException("Narration exceeds the WAV container limit");
        File parent=target.getAbsoluteFile().getParentFile();if(!parent.isDirectory())throw new IOException("Narration directory is unavailable");
        File pending=File.createTempFile("narration-",".partial",parent);
        try{
            try(FileOutputStream out=new FileOutputStream(pending)){
                out.write(new byte[]{'R','I','F','F'});put32(out,36+total);out.write(new byte[]{'W','A','V','E','f','m','t',' '});put32(out,16);
                put16(out,base.format);put16(out,base.channels);put32(out,base.sampleRate);put32(out,(long)base.sampleRate*base.blockAlign);put16(out,base.blockAlign);put16(out,base.bitsPerSample);out.write(new byte[]{'d','a','t','a'});put32(out,total);
                byte[] buffer=new byte[64*1024];
                for(int i=0;i<parts.size();i++)try(RandomAccessFile in=new RandomAccessFile(parts.get(i),"r")){
                    Info info=formats.get(i);in.seek(info.dataOffset);long left=info.dataBytes;
                    while(left>0){checkCancelled(cancelled);int count=in.read(buffer,0,(int)Math.min(left,buffer.length));if(count<0)throw new EOFException("Narration chunk changed during assembly");out.write(buffer,0,count);left-=count;}
                }
                out.getFD().sync();
            }
            inspect(pending);checkCancelled(cancelled);
            // No REPLACE_EXISTING: an existing owner file is never removed.
            Files.move(pending.toPath(),target.toPath());
        }finally{Files.deleteIfExists(pending.toPath());}
    }
    private static void checkCancelled(BooleanSupplier cancelled)throws IOException{if(Thread.currentThread().isInterrupted()||(cancelled!=null&&cancelled.getAsBoolean()))throw new InterruptedIOException("Narration assembly cancelled");}
    private static int u16(RandomAccessFile in)throws IOException{int a=in.readUnsignedByte(),b=in.readUnsignedByte();return a|(b<<8);}
    private static long u32(RandomAccessFile in)throws IOException{return (long)u16(in)|((long)u16(in)<<16);}
    private static void put16(OutputStream out,int n)throws IOException{out.write(n);out.write(n>>>8);}
    private static void put32(OutputStream out,long n)throws IOException{for(int i=0;i<4;i++)out.write((int)(n>>>(8*i)));}
}
