package com.rezoxnemesis.videostudio;

import java.io.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;

/** Optional full-byte local benchmark; source is synthetic data, not a playable video. */
public final class VaultLargeProjectBenchmark {
    private static String checksum(InputStream stream)throws Exception{
        MessageDigest digest=MessageDigest.getInstance("SHA-256");byte[] buffer=new byte[64*1024];int n;
        try(stream){while((n=stream.read(buffer))!=-1)digest.update(buffer,0,n);}
        return HexFormat.of().formatHex(digest.digest());
    }
    private static void require(boolean valid,String message){if(!valid)throw new AssertionError(message);}
    private static long elapsed(long started){return (System.nanoTime()-started)/1_000_000;}
    public static void main(String[] args)throws Exception{
        if(args.length!=1)throw new IllegalArgumentException("Supply a fresh evidence directory");
        Path root=Path.of(args[0]).toAbsolutePath();Files.createDirectories(root);
        Path source=root.resolve("synthetic-20GiB.source");
        require(!Files.exists(source),"Benchmark must preserve existing originals; use a fresh directory");
        long length=20L<<30,checkpoint=2L<<30;
        require(Runtime.getRuntime().maxMemory()<=64L*1024*1024,"Run with -Xmx64m to verify bounded heap");
        byte[] marker=new byte[8192];new Random(9127).nextBytes(marker);
        long middle=(5L<<30)-marker.length/2;
        try(RandomAccessFile file=new RandomAccessFile(source.toFile(),"rw")){
            file.setLength(length);for(long offset:new long[]{0,middle,length-marker.length}){file.seek(offset);file.write(marker);}file.getFD().sync();
        }
        System.out.println("Source: "+length+" logical bytes; hashing all source bytes");System.out.flush();
        long hashing=System.nanoTime();String sourceSha=checksum(Files.newInputStream(source));long hashMs=elapsed(hashing);
        ArrayList<Long> offsets=new ArrayList<>();
        VaultChunkStore.Source input=offset->{offsets.add(offset);return VaultChunkStore.openFileRange(source.toFile(),offset,length-offset);};
        VaultChunkStore vault=new VaultChunkStore(root.resolve("vault").toFile(),VaultChunkStore.DEFAULT_CHUNK_BYTES);
        long packing=System.nanoTime();
        try{
            vault.packResumable("benchmark-"+sourceSha,input,length,null,"",sourceSha,(done,total)->{
                if(done>=checkpoint)throw new InterruptedIOException("Intentional restart after 2 GiB");
            });throw new AssertionError("Checkpoint interruption was ignored");
        }catch(InterruptedIOException expected){require(expected.getMessage().contains("Intentional restart"),"Unexpected I/O interruption");}
        VaultChunkStore restarted=new VaultChunkStore(root.resolve("vault").toFile(),VaultChunkStore.DEFAULT_CHUNK_BYTES);
        require(restarted.completedBytes("benchmark-"+sourceSha,length,false,"")==checkpoint,"Exact durable checkpoint");
        VaultChunkStore.Manifest manifest=restarted.packResumable("benchmark-"+sourceSha,input,length,null,"",sourceSha,(done,total)->{
            if(done%(2L<<30)==0){System.out.println("Packed "+done+" / "+total+" bytes");System.out.flush();}
        });long packMs=elapsed(packing);
        require(offsets.equals(List.of(0L,checkpoint)),"Restart must seek beyond committed bytes");
        require(manifest.totalBytes==length&&manifest.chunks.size()==80,"Full 20 GiB manifest with 80 production-size chunks");
        require(sourceSha.equals(manifest.sha256),"Packed checksum equals independently streamed original");
        VaultChunkStore loaded=new VaultChunkStore(root.resolve("vault").toFile(),VaultChunkStore.DEFAULT_CHUNK_BYTES);
        VaultChunkStore.Manifest restored=loaded.load(manifest.id);
        long reading=System.nanoTime();String restoredSha=checksum(loaded.openRange(restored,0,length,null));long readMs=elapsed(reading);
        require(sourceSha.equals(restoredSha),"All 20 GiB restored bytes match the original SHA-256");
        for(long offset:new long[]{(4L<<30)-17,middle-17,length-marker.length-17}){
            long size=Math.min(8256,length-offset);
            try(InputStream original=VaultChunkStore.openFileRange(source.toFile(),offset,size);InputStream range=loaded.openRange(restored,offset,size,null)){
                require(Arrays.equals(original.readAllBytes(),range.readAllBytes()),"64-bit boundary range matches original");
            }
        }
        require(Files.size(source)==length,"Original source retained");
        long objectBytes=0;int objects=0;try(var files=Files.list(root.resolve("vault/objects"))){for(Path file:files.toList()){objectBytes+=Files.size(file);objects++;}}
        String json="{\n  \"logicalBytes\": "+length+",\n  \"sourceSha256\": \""+sourceSha+"\",\n  \"restoredSha256\": \""+restoredSha+"\",\n  \"chunkBytes\": "+VaultChunkStore.DEFAULT_CHUNK_BYTES+",\n  \"chunks\": 80,\n  \"uniqueObjects\": "+objects+",\n  \"storedObjectBytes\": "+objectBytes+",\n  \"resumeOffset\": "+checkpoint+",\n  \"maxHeapBytes\": "+Runtime.getRuntime().maxMemory()+",\n  \"sourceHashMs\": "+hashMs+",\n  \"packWithRestartMs\": "+packMs+",\n  \"fullRestoreReadMs\": "+readMs+",\n  \"encrypted\": false,\n  \"cloudTransferTested\": false,\n  \"deviceTested\": false,\n  \"allLogicalBytesRead\": true,\n  \"originalRetained\": true\n}\n";
        Files.writeString(root.resolve("evidence.json"),json,StandardOpenOption.CREATE_NEW);
        System.out.println("PASS full 20 GiB streaming, restart, checksum and range verification; evidence.json saved");
    }
}
