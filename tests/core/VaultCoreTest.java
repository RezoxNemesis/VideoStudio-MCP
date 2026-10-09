package com.rezoxnemesis.videostudio;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import javax.crypto.spec.SecretKeySpec;
public final class VaultCoreTest {
 private static int checks;
 private static void check(boolean pass,String message){checks++;if(!pass)throw new AssertionError(message);}
 private static byte[] range(VaultChunkStore store,VaultChunkStore.Manifest m,long offset,long length,javax.crypto.SecretKey key)throws Exception{try(InputStream in=store.openRange(m,offset,length,key)){return in.readAllBytes();}}
 public static void main(String[] args)throws Exception{
  Path root=Files.createTempDirectory("studio-vault-test");
  try{
   byte[] source=new byte[25073];new Random(12345).nextBytes(source);VaultChunkStore store=new VaultChunkStore(root.toFile(),4096);
   VaultChunkStore.Manifest m=store.pack(new ByteArrayInputStream(source),source.length,null,"",(done,total)->{});
   check(m.totalBytes==source.length,"Full 64-bit length");check(m.chunks.size()==7,"Chunk count");
   check(Arrays.equals(source,range(store,m,0,m.totalBytes,null)),"Complete round trip");
   check(Arrays.equals(Arrays.copyOfRange(source,4000,17000),range(store,m,4000,13000,null)),"Cross-chunk range");
   check(range(store,m,m.totalBytes,0,null).length==0,"Empty end range");
   VaultChunkStore.Manifest loaded=new VaultChunkStore(root.toFile(),4096).load(m.id);check(Arrays.equals(source,range(store,loaded,0,loaded.totalBytes,null)),"Restart-safe manifest");
   long objects=Files.list(root.resolve("objects")).count();store.pack(new ByteArrayInputStream(source),source.length,null,"",(done,total)->{});check(objects==Files.list(root.resolve("objects")).count(),"Identical chunks deduplicate");
   try{store.openRange(m,Long.MAX_VALUE,1,null);throw new AssertionError("Invalid range accepted");}catch(IllegalArgumentException expected){checks++;}
   javax.crypto.SecretKey key=new SecretKeySpec(new byte[32],"AES");VaultChunkStore.Manifest encrypted=store.pack(new ByteArrayInputStream(source),source.length,key,"test-key",(done,total)->{});
   check(encrypted.encrypted,"Encryption recorded");check(Arrays.equals(Arrays.copyOfRange(source,3000,source.length),range(store,encrypted,3000,source.length-3000,key)),"Encrypted range round trip");
   try{range(store,encrypted,0,10,null);throw new AssertionError("Encrypted chunk opened without key");}catch(IOException expected){checks++;}
   byte[] wrong=new byte[32];wrong[0]=1;try{range(store,encrypted,0,100,new SecretKeySpec(wrong,"AES"));throw new AssertionError("Wrong key accepted");}catch(IOException expected){checks++;}
   // Exercise the production logical chunk size, without allocating the source in memory.
   VaultChunkStore production=new VaultChunkStore(root.resolve("production").toFile(),VaultChunkStore.DEFAULT_CHUNK_BYTES);
   long productionLength=VaultChunkStore.DEFAULT_CHUNK_BYTES+257;
   List<Long> opens=new ArrayList<>();
   VaultChunkStore.Source generated=offset->{opens.add(offset);return new InputStream(){long position=offset;
    public int read(){return position>=productionLength?-1:(int)((position++*31+17)&255);}
    public int read(byte[] b,int off,int len){if(position>=productionLength)return -1;int n=(int)Math.min(len,productionLength-position);for(int i=0;i<n;i++)b[off+i]=(byte)((position++*31+17)&255);return n;}
   };};
   try{production.packResumable("stable-source",generated,productionLength,key,"production-key",(done,total)->{if(done>=VaultChunkStore.DEFAULT_CHUNK_BYTES)throw new InterruptedIOException("Simulated process interruption");});throw new AssertionError("Interruption ignored");}catch(InterruptedIOException expected){checks++;}
   check(production.completedBytes("stable-source",productionLength,true,"production-key")==VaultChunkStore.DEFAULT_CHUNK_BYTES,"Durable production-size checkpoint");
   check(Files.list(root.resolve("production/objects")).count()==1,"Interrupted copy retains one verified encrypted object");
   VaultChunkStore restarted=new VaultChunkStore(root.resolve("production").toFile(),VaultChunkStore.DEFAULT_CHUNK_BYTES);
   VaultChunkStore.Manifest real=restarted.packResumable("stable-source",generated,productionLength,key,"production-key",null);
   check(opens.equals(Arrays.asList(0L,VaultChunkStore.DEFAULT_CHUNK_BYTES)),"Resume seeks source beyond committed bytes");
   check(real.chunks.size()==2&&real.chunks.get(0).size==VaultChunkStore.DEFAULT_CHUNK_BYTES&&real.totalBytes==productionLength,"Production 256 MB logical object plus tail");
   check(Files.list(root.resolve("production/objects")).count()==2,"Encrypted resume avoids duplicating completed objects");
   java.security.MessageDigest completeHash=java.security.MessageDigest.getInstance("SHA-256");byte[] pattern=new byte[64*1024];
   for(int i=0;i<pattern.length;i++)pattern[i]=(byte)((i*31+17)&255);
   for(long position=0;position<productionLength;position+=pattern.length)completeHash.update(pattern,0,(int)Math.min(pattern.length,productionLength-position));
   StringBuilder expectedHash=new StringBuilder();for(byte value:completeHash.digest())expectedHash.append(String.format(Locale.US,"%02x",value&255));
   check(expectedHash.toString().equals(real.sha256),"Resumed full checksum includes committed prefix and new tail");
   check(range(production,real,0,1,key)[0]==17,"One-byte encrypted range under 64 MB heap");
   check(range(production,real,productionLength-1,1,key)[0]==(byte)(((productionLength-1)*31+17)&255),"End-of-object encrypted range under 64 MB heap");
   try{range(production,real,0,1,new SecretKeySpec(wrong,"AES"));throw new AssertionError("Production record accepted wrong key");}catch(IOException expected){checks++;}
   byte[] boundary=range(restarted,restarted.load(real.id),VaultChunkStore.RECORD_BYTES-5,17,key);
   for(int i=0;i<boundary.length;i++)check(boundary[i]==(byte)(((VaultChunkStore.RECORD_BYTES-5L+i)*31+17)&255),"Authenticated record boundary preserves bytes");
   long beforeReuse=Files.list(root.resolve("production/objects")).count();
   VaultChunkStore.Manifest reused=restarted.packResumable("stable-source",generated,productionLength,key,"production-key",null);
   check(reused.id.equals(real.id)&&beforeReuse==Files.list(root.resolve("production/objects")).count(),"Completed encrypted retry reuses the exact objects");
   try{restarted.completedBytes("stable-source",productionLength+1,true,"production-key");throw new AssertionError("Changed source settings resumed");}catch(IOException expected){checks++;}
   VaultChunkStore mutable=new VaultChunkStore(root.resolve("mutable").toFile(),4096);byte[][] live={source.clone()};
   VaultChunkStore.Source document=offset->new ByteArrayInputStream(live[0],(int)offset,live[0].length-(int)offset);
   try{mutable.packResumable("same-saf-uri",document,source.length,null,"",(done,total)->{throw new InterruptedIOException("Interrupted after prefix");});throw new AssertionError("Interruption ignored");}catch(InterruptedIOException expected){checks++;}
   live[0][0]^=1;VaultChunkStore.SourceDigest identity=VaultChunkStore.sourceChecksum(new ByteArrayInputStream(live[0]),null);
   check(identity.bytes==source.length,"Live source digest keeps exact byte length");
   try{mutable.packResumable("same-saf-uri",document,source.length,null,"",identity.sha256,null);throw new AssertionError("Old-prefix/new-suffix copy published");}catch(IOException expected){checks++;}
   check(Files.list(root.resolve("mutable/manifests")).findAny().isEmpty(),"Changed source publishes no complete manifest");
   VaultChunkStore.Manifest fresh=mutable.packResumable("same-saf-uri:"+identity.sha256,document,source.length,null,"",identity.sha256,null);
   check(Arrays.equals(live[0],range(mutable,fresh,0,fresh.totalBytes,null)),"Changed document starts a new checksum-bound copy");
   Path chunk=root.resolve("objects").resolve(m.chunks.get(0).objectName);try(RandomAccessFile file=new RandomAccessFile(chunk.toFile(),"rw")){file.seek(10);file.write(7);}
   try{range(store,m,0,20,null);throw new AssertionError("Damaged chunk accepted");}catch(IOException expected){checks++;}
   try{store.pack(new ByteArrayInputStream(source),source.length+1,null,"",(done,total)->{});throw new AssertionError("Truncated source published");}catch(IOException expected){checks++;}
   Thread.currentThread().interrupt();try{store.pack(new ByteArrayInputStream(source),source.length,null,"",(done,total)->{});throw new AssertionError("Cancellation ignored");}catch(InterruptedIOException expected){checks++;}finally{Thread.interrupted();}
   check(Files.list(root.resolve("partial")).findAny().isEmpty(),"Cancelled work leaves no partial chunk");
   Path large=root.resolve("20GB-sparse-source");long length=20L<<30;try(RandomAccessFile file=new RandomAccessFile(large.toFile(),"rw")){file.setLength(length);file.seek(length-3);file.write(new byte[]{11,22,33});}
   try(InputStream in=VaultChunkStore.openFileRange(large.toFile(),length-3,3)){check(Arrays.equals(new byte[]{11,22,33},in.readAllBytes()),"20GB range uses true 64-bit seek");}
   check(Files.size(large)==length,"Original sparse file is retained");
   System.out.println("PASS "+checks+" Vault/range behavior checks under a 64 MB heap");
  }finally{try(var paths=Files.walk(root)){for(Path p:paths.sorted(Comparator.reverseOrder()).toList())Files.deleteIfExists(p);}}
 }
}
