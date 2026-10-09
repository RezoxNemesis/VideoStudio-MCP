package com.rezoxnemesis.videostudio;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import javax.crypto.*;
import javax.crypto.spec.GCMParameterSpec;

/** Checksummed logical objects with independently authenticated, bounded AES-GCM records. */
public final class VaultChunkStore {
    public static final long DEFAULT_CHUNK_BYTES=256L*1024*1024;
    public static final int RECORD_BYTES=1024*1024;
    private static final int MAGIC=0x56535631,RECORD_MAGIC=0x56535232,RESUME_MAGIC=0x56535032,MAX_CHUNKS=100000;
    public interface Progress { void onProgress(long done,long total)throws IOException; }
    /** A resume ID must identify an unchanged source. Implementations seek with 64-bit offsets. */
    public interface Source { InputStream open(long offset)throws IOException; }
    public static final class SourceDigest {
        public final String sha256;public final long bytes;
        SourceDigest(String sha,long bytes){sha256=sha;this.bytes=bytes;}
    }
    public static SourceDigest sourceChecksum(InputStream source,Progress progress)throws IOException{
        if(source==null)throw new IllegalArgumentException("Vault source is required");MessageDigest hash=digest();byte[] buffer=new byte[64*1024];long bytes=0,reported=0;int count;
        while((count=source.read(buffer))!=-1){interrupted();if(count==0){int one=source.read();if(one<0)break;buffer[0]=(byte)one;count=1;}hash.update(buffer,0,count);bytes=Math.addExact(bytes,count);
            if(progress!=null&&bytes-reported>=8L*1024*1024){progress.onProgress(bytes,-1);reported=bytes;}}
        if(progress!=null)progress.onProgress(bytes,bytes);return new SourceDigest(hex(hash.digest()),bytes);
    }
    public static final class Chunk {
        public final long offset,size,storedBytes;
        public final int recordBytes;
        public final String objectName,sha256,storedSha256,iv;
        Chunk(long offset,long size,String plain,String stored,String iv,long storedBytes,int records){this.offset=offset;this.size=size;sha256=plain;storedSha256=stored;objectName=stored+".chunk";this.iv=iv;this.storedBytes=storedBytes;recordBytes=records;}
    }
    public static final class Manifest {
        public final String id,sha256,keyId;
        public final long totalBytes,chunkBytes;
        public final boolean encrypted;
        public final int version;
        public final List<Chunk> chunks;
        Manifest(String id,String sha,long total,long chunkBytes,String keyId,boolean encrypted,int version,List<Chunk> chunks){this.id=id;sha256=sha;totalBytes=total;this.chunkBytes=chunkBytes;this.keyId=keyId;this.encrypted=encrypted;this.version=version;this.chunks=Collections.unmodifiableList(new ArrayList<>(chunks));}
    }
    private final File objects,partial,manifests,resumes;
    private final long chunkBytes;
    public VaultChunkStore(File root,long chunkBytes)throws IOException{
        if(root==null||chunkBytes<1024||chunkBytes>1L<<30)throw new IllegalArgumentException("Invalid Vault root/chunk size");
        this.chunkBytes=chunkBytes;objects=new File(root,"objects");partial=new File(root,"partial");manifests=new File(root,"manifests");resumes=new File(root,"resumes");
        for(File dir:new File[]{root,objects,partial,manifests,resumes})if(!dir.isDirectory()&&!dir.mkdirs()&&!dir.isDirectory())throw new IOException("Cannot create Vault directory");
    }
    public synchronized Manifest pack(InputStream source,long expected,SecretKey key,String keyId,Progress progress)throws IOException{return packInternal(null,source,null,expected,key,keyId,null,progress);}
    public synchronized Manifest packResumable(String resumeId,Source source,long expected,SecretKey key,String keyId,Progress progress)throws IOException{
        return packResumable(resumeId,source,expected,key,keyId,null,progress);
    }
    public synchronized Manifest packResumable(String resumeId,Source source,long expected,SecretKey key,String keyId,String expectedSourceSha,Progress progress)throws IOException{
        if(source==null)throw new IllegalArgumentException("Vault source is required");
        if(expectedSourceSha!=null&&!expectedSourceSha.matches("[a-f0-9]{64}"))throw new IllegalArgumentException("Invalid original source checksum");
        return packInternal(checkpointFile(resumeId),null,source,expected,key,keyId,expectedSourceSha,progress);
    }
    /** Verify committed objects before budgeting only the remaining source bytes. */
    public synchronized long completedBytes(String resumeId,long expected,boolean encrypted,String keyId)throws IOException{
        List<Chunk> chunks=readCheckpoint(checkpointFile(resumeId),expected,encrypted,keyId);long total=0;
        for(Chunk c:chunks){verifyStored(new File(objects,c.objectName),c.storedSha256,c.storedBytes);total=Math.addExact(total,c.size);}return total;
    }
    private Manifest packInternal(File checkpoint,InputStream supplied,Source source,long expected,SecretKey key,String keyId,String expectedSourceSha,Progress progress)throws IOException{
        if((supplied==null&&source==null)||expected< -1||(key!=null&&(keyId==null||keyId.isEmpty())))throw new IllegalArgumentException("Invalid Vault source or encryption key ID");
        boolean encrypted=key!=null;String keyName=encrypted?keyId:"";Progress report=progress==null?(done,total)->{}:progress;
        ArrayList<Chunk> chunks=new ArrayList<>(checkpoint==null?Collections.emptyList():readCheckpoint(checkpoint,expected,encrypted,keyName));MessageDigest full=digest();long total=0;
        byte[] hashBuffer=new byte[64*1024];
        // Recover the full SHA from saved authenticated objects, not from source byte zero.
        for(Chunk c:chunks){
            verifyStored(new File(objects,c.objectName),c.storedSha256,c.storedBytes);MessageDigest plain=digest();long bytes=0;
            try(InputStream in=openChunk(c,encrypted,key,0)){int n;while((n=in.read(hashBuffer))!=-1){interrupted();plain.update(hashBuffer,0,n);full.update(hashBuffer,0,n);bytes+=n;}}
            if(bytes!=c.size||!c.sha256.equals(hex(plain.digest())))throw new IOException("Vault resume plaintext checksum mismatch");total=Math.addExact(total,c.size);
        }
        if(!chunks.isEmpty())report.onProgress(total,expected);
        InputStream input=supplied==null?source.open(total):supplied;if(input==null)throw new IOException("Cannot open Vault source");
        try{
            byte[] buffer=new byte[(int)Math.min(RECORD_BYTES,chunkBytes)];boolean eof=false;
            while(!eof){
                interrupted();if(chunks.size()>=MAX_CHUNKS)throw new IOException("Vault chunk index limit reached");
                File temp=File.createTempFile("chunk-",".partial",partial);MessageDigest plain=digest(),stored=digest();long size=0,recordIndex=0;
                try{
                    try(FileOutputStream file=new FileOutputStream(temp);DataOutputStream out=new DataOutputStream(new BufferedOutputStream(new DigestOutputStream(file,stored),64*1024))){
                        if(encrypted){out.writeInt(RECORD_MAGIC);out.writeInt(RECORD_BYTES);out.writeLong(total);}
                        while(size<chunkBytes){
                            int requested=(int)Math.min(buffer.length,chunkBytes-size),count=0;
                            while(count<requested){interrupted();int n=input.read(buffer,count,requested-count);if(n<0){eof=true;break;}if(n==0){int one=input.read();if(one<0){eof=true;break;}buffer[count++]=(byte)one;}else count+=n;}
                            if(count==0)break;if(expected>=0&&count>expected-total-size)throw new IOException("Source exceeds its declared length");plain.update(buffer,0,count);full.update(buffer,0,count);
                            if(encrypted){Cipher encoder=encryptor(key);encoder.updateAAD(aad(total,recordIndex,count));byte[] encoded=finish(encoder,buffer,0,count);out.writeInt(count);out.write(encoder.getIV());out.write(encoded);}else out.write(buffer,0,count);
                            size=Math.addExact(size,count);recordIndex++;
                        }
                        out.flush();file.getFD().sync();
                    }
                    if(size==0)continue;
                    String plainHash=hex(plain.digest()),storedHash=hex(stored.digest());long storedBytes=temp.length();File object=new File(objects,storedHash+".chunk");
                    if(object.exists()){verifyStored(object,storedHash,storedBytes);Files.delete(temp.toPath());}else atomicMove(temp,object,false);
                    chunks.add(new Chunk(total,size,plainHash,storedHash,"",storedBytes,encrypted?RECORD_BYTES:0));total=Math.addExact(total,size);
                    if(checkpoint!=null)writeCheckpoint(checkpoint,expected,encrypted,keyName,chunks);report.onProgress(total,expected);
                }finally{Files.deleteIfExists(temp.toPath());}
            }
        }catch(ArithmeticException overflow){throw new IOException("Vault size exceeds 64-bit range",overflow);}finally{if(supplied==null)input.close();}
        if(expected>=0&&total!=expected)throw new IOException("Truncated Vault source: expected "+expected+", read "+total);
        interrupted();String hash=hex(full.digest()),tag=encrypted?"-"+hashText(keyName).substring(0,16):"";
        if(expectedSourceSha!=null&&!hash.equals(expectedSourceSha))throw new IOException("Vault source changed during copy; the original is retained and no complete manifest was published");
        Manifest manifest=new Manifest(hash+tag,hash,total,chunkBytes,keyName,encrypted,2,chunks);writeManifest(manifest);return manifest;
    }
    public Manifest load(String id)throws IOException{
        return validateManifest(new File(manifests,id+".manifest"),id);
    }
    /** Validate a staged binary manifest before publishing recovered bytes. */
    public static Manifest validateManifest(File file,String id)throws IOException{
        if(id==null||!id.matches("[a-f0-9]{64}(-[a-f0-9]{16})?"))throw new IllegalArgumentException("Invalid Vault manifest ID");
        try(DataInputStream in=new DataInputStream(new BufferedInputStream(new FileInputStream(file)))){
            if(in.readInt()!=MAGIC)throw new IOException("Unsupported Vault manifest");int version=in.readInt();if(version!=1&&version!=2)throw new IOException("Unsupported Vault manifest version");
            String sha=in.readUTF();long total=in.readLong(),chunkSize=in.readLong();boolean encrypted=in.readBoolean();String keyId=in.readUTF();
            if(total<0||chunkSize<1024||chunkSize>1L<<30||!sha.matches("[a-f0-9]{64}"))throw new IOException("Invalid Vault manifest header");
            ArrayList<Chunk> chunks=readChunks(in,chunkSize,encrypted,version);
            if(sum(chunks)!=total||in.read()!=-1||!id.equals(sha+(encrypted?"-"+hashText(keyId).substring(0,16):"")))throw new IOException("Vault manifest length/hash mismatch");
            return new Manifest(id,sha,total,chunkSize,keyId,encrypted,version,chunks);
        }catch(ArithmeticException invalid){throw new IOException("Invalid Vault size",invalid);}
    }
    private static ArrayList<Chunk> readChunks(DataInputStream in,long chunkSize,boolean encrypted,int version)throws IOException{
        int count=in.readInt();if(count<0||count>MAX_CHUNKS)throw new IOException("Invalid Vault chunk count");ArrayList<Chunk> chunks=new ArrayList<>();long cursor=0;
        for(int i=0;i<count;i++){
            long offset=in.readLong(),size=in.readLong();String plain=in.readUTF(),stored=in.readUTF(),iv=in.readUTF();long storedBytes=version==1?size+(encrypted?16:0):in.readLong();int records=version==1?0:in.readInt();
            long expectedStored=encrypted&&version==2?16+size+32*((size+RECORD_BYTES-1)/RECORD_BYTES):size+(encrypted?16:0);
            if(offset!=cursor||size<=0||size>chunkSize||!plain.matches("[a-f0-9]{64}")||!stored.matches("[a-f0-9]{64}")||!iv.matches(encrypted&&version==1?"[a-f0-9]{24}":"")||storedBytes!=expectedStored||records!=(encrypted&&version==2?RECORD_BYTES:0))throw new IOException("Invalid Vault chunk index");
            cursor=Math.addExact(cursor,size);chunks.add(new Chunk(offset,size,plain,stored,iv,storedBytes,records));
        }return chunks;
    }
    private static void writeChunks(DataOutputStream out,List<Chunk> chunks)throws IOException{out.writeInt(chunks.size());for(Chunk c:chunks){out.writeLong(c.offset);out.writeLong(c.size);out.writeUTF(c.sha256);out.writeUTF(c.storedSha256);out.writeUTF(c.iv);out.writeLong(c.storedBytes);out.writeInt(c.recordBytes);}}
    private void writeManifest(Manifest m)throws IOException{writeAtomic(new File(manifests,m.id+".manifest"),out->{out.writeInt(MAGIC);out.writeInt(2);out.writeUTF(m.sha256);out.writeLong(m.totalBytes);out.writeLong(m.chunkBytes);out.writeBoolean(m.encrypted);out.writeUTF(m.keyId);writeChunks(out,m.chunks);});}
    private File checkpointFile(String resumeId){if(resumeId==null||resumeId.isEmpty()||resumeId.length()>4096)throw new IllegalArgumentException("A bounded source identity is required for Vault resume");return new File(resumes,hashText(resumeId)+".checkpoint");}
    private ArrayList<Chunk> readCheckpoint(File file,long expected,boolean encrypted,String keyId)throws IOException{
        if(!file.exists())return new ArrayList<>();
        try(DataInputStream in=new DataInputStream(new BufferedInputStream(new FileInputStream(file)))){
            if(in.readInt()!=RESUME_MAGIC||in.readLong()!=expected||in.readLong()!=chunkBytes||in.readBoolean()!=encrypted||!in.readUTF().equals(encrypted?keyId:""))throw new IOException("Vault checkpoint source/settings changed");
            ArrayList<Chunk> chunks=readChunks(in,chunkBytes,encrypted,2);if(in.read()!=-1||(expected>=0&&sum(chunks)>expected))throw new IOException("Invalid Vault checkpoint length");return chunks;
        }
    }
    private void writeCheckpoint(File file,long expected,boolean encrypted,String keyId,List<Chunk> chunks)throws IOException{writeAtomic(file,out->{out.writeInt(RESUME_MAGIC);out.writeLong(expected);out.writeLong(chunkBytes);out.writeBoolean(encrypted);out.writeUTF(keyId);writeChunks(out,chunks);});}
    private interface Writer{void write(DataOutputStream out)throws IOException;}
    private void writeAtomic(File target,Writer writer)throws IOException{
        File temp=File.createTempFile("index-",".partial",partial);
        try{try(FileOutputStream file=new FileOutputStream(temp);DataOutputStream out=new DataOutputStream(new BufferedOutputStream(file))){writer.write(out);out.flush();file.getFD().sync();}atomicMove(temp,target,true);}finally{Files.deleteIfExists(temp.toPath());}
    }
    public InputStream openRange(Manifest m,long offset,long length,SecretKey key)throws IOException{
        if(m==null||offset<0||length<0||offset>m.totalBytes||length>m.totalBytes-offset)throw new IllegalArgumentException("Vault range is outside the asset");if(m.encrypted&&key==null)throw new IOException("Vault encryption key is unavailable");
        return new InputStream(){
            long position=offset,remaining=length,currentRemaining;int index;InputStream current;boolean closed;
            private void next()throws IOException{
                if(current!=null){current.close();current=null;}while(index<m.chunks.size()&&position>=m.chunks.get(index).offset+m.chunks.get(index).size)index++;
                if(index>=m.chunks.size())throw new IOException("Vault chunk is missing");Chunk c=m.chunks.get(index++);verifyStored(new File(objects,c.objectName),c.storedSha256,c.storedBytes);current=openChunk(c,m.encrypted,key,position-c.offset);currentRemaining=c.size-(position-c.offset);
            }
            @Override public int read()throws IOException{byte[] one=new byte[1];int n=read(one,0,1);return n<0?-1:one[0]&255;}
            @Override public int read(byte[] b,int off,int len)throws IOException{
                bounds(b,off,len);if(closed)throw new IOException("Vault range is closed");if(len==0)return 0;if(remaining==0)return -1;interrupted();if(current==null||currentRemaining==0)next();
                int n=current.read(b,off,(int)Math.min(len,Math.min(remaining,currentRemaining)));if(n<0)throw new EOFException("Truncated Vault chunk");position+=n;remaining-=n;currentRemaining-=n;return n;
            }
            @Override public void close()throws IOException{closed=true;if(current!=null)current.close();}
        };
    }
    private InputStream openChunk(Chunk chunk,boolean encrypted,SecretKey key,long offset)throws IOException{
        File file=new File(objects,chunk.objectName);if(!encrypted)return openFileRange(file,offset,chunk.size-offset);
        if(chunk.recordBytes==0){
            // Legacy GCM providers buffer a whole object. Refuse unbounded allocation, retain the original.
            if(chunk.size>RECORD_BYTES)throw new IOException("Legacy encrypted Vault object needs a version 2 copy from its retained original");
            byte[] encoded=Files.readAllBytes(file.toPath()),plain=finish(cipher(Cipher.DECRYPT_MODE,key,unhex(chunk.iv)),encoded,0,encoded.length);
            if(plain.length!=chunk.size||!chunk.sha256.equals(hex(digest().digest(plain))))throw new IOException("Vault plaintext checksum mismatch");return new ByteArrayInputStream(plain,(int)offset,plain.length-(int)offset);
        }
        RandomAccessFile in=new RandomAccessFile(file,"r");
        try{
            if(in.readInt()!=RECORD_MAGIC||in.readInt()!=RECORD_BYTES||in.readLong()!=chunk.offset)throw new IOException("Invalid Vault record header");long first=offset/RECORD_BYTES;in.seek(16+first*(RECORD_BYTES+32L));
            return new InputStream(){
                long index=first,position=offset;byte[] decoded;int cursor;boolean closed;
                private boolean record()throws IOException{
                    if(position>=chunk.size)return false;interrupted();int count=in.readInt(),expected=(int)Math.min(RECORD_BYTES,chunk.size-index*RECORD_BYTES);if(count!=expected)throw new IOException("Invalid Vault authenticated record length");
                    byte[] iv=new byte[12],encoded=new byte[count+16];in.readFully(iv);in.readFully(encoded);Cipher decoder=cipher(Cipher.DECRYPT_MODE,key,iv);decoder.updateAAD(aad(chunk.offset,index,count));decoded=finish(decoder,encoded,0,encoded.length);cursor=(int)(position-index*RECORD_BYTES);index++;
                    if(decoded.length!=count)throw new IOException("Invalid Vault plaintext record length");return true;
                }
                @Override public int read()throws IOException{byte[] one=new byte[1];int n=read(one,0,1);return n<0?-1:one[0]&255;}
                @Override public int read(byte[] b,int off,int len)throws IOException{bounds(b,off,len);if(closed)throw new IOException("Vault record is closed");if(len==0)return 0;if((decoded==null||cursor==decoded.length)&&!record())return -1;int n=Math.min(len,decoded.length-cursor);System.arraycopy(decoded,cursor,b,off,n);cursor+=n;position+=n;return n;}
                @Override public void close()throws IOException{closed=true;decoded=null;in.close();}
            };
        }catch(IOException error){in.close();throw error;}
    }
    public static InputStream openFileRange(File file,long offset,long length)throws IOException{
        if(file==null||offset<0||length<0||offset>file.length()||length>file.length()-offset)throw new IllegalArgumentException("File range is outside the source");RandomAccessFile source=new RandomAccessFile(file,"r");source.seek(offset);
        return new InputStream(){long remaining=length;boolean closed;
            @Override public int read()throws IOException{if(closed)throw new IOException("Range is closed");if(remaining==0)return -1;int value=source.read();if(value<0)throw new EOFException("Source was truncated");remaining--;return value;}
            @Override public int read(byte[] b,int off,int len)throws IOException{bounds(b,off,len);if(closed)throw new IOException("Range is closed");if(len==0)return 0;if(remaining==0)return -1;interrupted();int n=source.read(b,off,(int)Math.min(len,remaining));if(n<0)throw new EOFException("Source was truncated");remaining-=n;return n;}
            @Override public void close()throws IOException{closed=true;source.close();}
        };
    }
    private static void bounds(byte[] b,int off,int len){if(off<0||len<0||off>b.length-len)throw new IndexOutOfBoundsException();}
    private static void verifyStored(File file,String expected,long size)throws IOException{
        if(!file.isFile()||file.length()!=size)throw new IOException("Vault chunk is missing or truncated");MessageDigest hash=digest();byte[] b=new byte[64*1024];try(InputStream in=new BufferedInputStream(new FileInputStream(file))){int n;while((n=in.read(b))!=-1){interrupted();hash.update(b,0,n);}}if(!expected.equals(hex(hash.digest())))throw new IOException("Vault chunk checksum mismatch");
    }
    private static long sum(List<Chunk> chunks){long total=0;for(Chunk c:chunks)total=Math.addExact(total,c.size);return total;}
    private static byte[] aad(long offset,long index,int length){return ByteBuffer.allocate(24).putLong(offset).putLong(index).putInt(length).putInt(RECORD_BYTES).array();}
    private static byte[] finish(Cipher cipher,byte[] b,int off,int len)throws IOException{try{return cipher.doFinal(b,off,len);}catch(GeneralSecurityException error){throw new IOException("Vault record authentication/encryption failed",error);}}
    private static MessageDigest digest(){try{return MessageDigest.getInstance("SHA-256");}catch(NoSuchAlgorithmException error){throw new IllegalStateException(error);}}
    private static String hashText(String value){return hex(digest().digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));}
    private static Cipher cipher(int mode,SecretKey key,byte[] iv)throws IOException{try{Cipher c=Cipher.getInstance("AES/GCM/NoPadding");c.init(mode,key,new GCMParameterSpec(128,iv));return c;}catch(GeneralSecurityException error){throw new IOException("Vault encryption failed",error);}}
    private static Cipher encryptor(SecretKey key)throws IOException{try{Cipher c=Cipher.getInstance("AES/GCM/NoPadding");c.init(Cipher.ENCRYPT_MODE,key);return c;}catch(GeneralSecurityException error){throw new IOException("Vault encryption failed",error);}}
    private static void interrupted()throws InterruptedIOException{if(Thread.currentThread().isInterrupted())throw new InterruptedIOException("Vault operation cancelled");}
    private static String hex(byte[] bytes){StringBuilder out=new StringBuilder();for(byte b:bytes)out.append(String.format(Locale.US,"%02x",b&255));return out.toString();}
    private static byte[] unhex(String value){byte[] out=new byte[value.length()/2];for(int i=0;i<out.length;i++)out[i]=(byte)Integer.parseInt(value.substring(i*2,i*2+2),16);return out;}
    private static void atomicMove(File from,File to,boolean replace)throws IOException{try{if(replace)Files.move(from.toPath(),to.toPath(),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);else Files.move(from.toPath(),to.toPath(),StandardCopyOption.ATOMIC_MOVE);}catch(AtomicMoveNotSupportedException unsupported){if(replace)Files.move(from.toPath(),to.toPath(),StandardCopyOption.REPLACE_EXISTING);else Files.move(from.toPath(),to.toPath());}}
}
