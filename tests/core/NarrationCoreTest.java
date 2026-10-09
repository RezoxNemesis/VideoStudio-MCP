package com.rezoxnemesis.videostudio;

import java.io.*;
import java.nio.file.*;
import java.util.*;

public final class NarrationCoreTest {
    static int checks;
    interface Work { void run() throws Exception; }
    static void check(boolean ok,String reason){checks++;if(!ok)throw new AssertionError(reason);}
    static void fails(Work work,String reason)throws Exception{try{work.run();}catch(IOException|IllegalArgumentException expected){checks++;return;}throw new AssertionError(reason);}
    static void le16(OutputStream out,int n)throws Exception{out.write(n);out.write(n>>>8);}
    static void le32(OutputStream out,long n)throws Exception{for(int i=0;i<4;i++)out.write((int)(n>>>(8*i)));}
    static File wav(Path dir,String name,int rate,int channels,short... samples)throws Exception{
        File f=dir.resolve(name).toFile();try(OutputStream out=new FileOutputStream(f)){
            out.write("RIFF".getBytes("US-ASCII"));le32(out,36+samples.length*2L);out.write("WAVEfmt ".getBytes("US-ASCII"));le32(out,16);le16(out,1);le16(out,channels);le32(out,rate);le32(out,rate*channels*2L);le16(out,channels*2);le16(out,16);out.write("data".getBytes("US-ASCII"));le32(out,samples.length*2L);for(short sample:samples)le16(out,sample);
        }return f;
    }
    public static void main(String[]args)throws Exception{
        Path dir=Files.createTempDirectory("studio-narration-");
        String text="First sentence.\n"+"long narration words 😀 ".repeat(1800);
        List<String> chunks=NarrationChunks.split(text,180);
        check(chunks.size()>100,"Long narration must be chunked");
        check(String.join("",chunks).equals(text),"Chunking preserves every character");
        for(String chunk:chunks){check(chunk.length()<=180&&!chunk.isEmpty(),"Bounded nonempty chunks");check(!Character.isHighSurrogate(chunk.charAt(chunk.length()-1)),"Do not split Unicode pairs");}
        check(NarrationChunks.split("one two three",7).equals(Arrays.asList("one ","two ","three")),"Prefer word boundaries");
        fails(()->NarrationChunks.split("hello",1),"Unsafe chunk limit rejected");
        fails(()->NarrationChunks.split("",180),"Empty narration rejected");
        File first=wav(dir,"one.wav",16000,1,(short)100,(short)-100,(short)200);
        File second=wav(dir,"two.wav",16000,1,(short)300,(short)-300);
        WavFile.Info info=WavFile.inspect(first);
        check(info.sampleRate==16000&&info.channels==1&&info.frameCount==3,"Read actual PCM format and frames");
        File joined=dir.resolve("joined.wav").toFile();WavFile.concatenate(Arrays.asList(first,second),joined,()->false);
        WavFile.Info joinedInfo=WavFile.inspect(joined);check(joinedInfo.frameCount==5&&joinedInfo.dataBytes==10,"Assembly adds frames without duplicate headers");
        try(RandomAccessFile f=new RandomAccessFile(joined,"r")){f.seek(joinedInfo.dataOffset);check(f.read()==100&&f.read()==0,"First PCM bytes preserved");f.seek(joinedInfo.dataOffset+6);check(f.read()==44&&f.read()==1,"Second PCM bytes preserved");}
        fails(()->WavFile.concatenate(Arrays.asList(first,second),joined,()->false),"Never overwrite existing owner media");
        File incompatible=wav(dir,"wrong-rate.wav",22050,1,(short)1);
        File rejected=dir.resolve("rejected.wav").toFile();fails(()->WavFile.concatenate(Arrays.asList(first,incompatible),rejected,()->false),"Format changes rejected");check(!rejected.exists(),"Failed assembly publishes no output");
        File cancelled=dir.resolve("cancelled.wav").toFile();fails(()->WavFile.concatenate(Arrays.asList(first,second),cancelled,()->true),"Cancellation interrupts assembly");check(!cancelled.exists(),"Cancelled assembly publishes no output");
        File broken=wav(dir,"broken.wav",16000,1,(short)4);try(RandomAccessFile f=new RandomAccessFile(broken,"rw")){f.setLength(45);}fails(()->WavFile.inspect(broken),"Truncated PCM rejected");
        File empty=wav(dir,"empty.wav",16000,1);fails(()->WavFile.inspect(empty),"Empty audio rejected");
        File malformed=dir.resolve("malformed.wav").toFile();Files.write(malformed.toPath(),"RIFFfakeheader".getBytes());fails(()->WavFile.inspect(malformed),"Invalid container rejected");
        File stereo=wav(dir,"stereo.wav",48000,2,(short)3);fails(()->WavFile.inspect(stereo),"Partial audio frame rejected");
        System.out.println("Narration core: "+checks+" checks passed");
    }
}
