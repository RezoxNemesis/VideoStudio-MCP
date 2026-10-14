package com.rezoxnemesis.videostudio;

import android.content.Context;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.Locale;
import java.util.concurrent.locks.ReentrantLock;
import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/** Authenticated, bounded owner-mailbox imports. Source files never become public plaintext. */
public final class NativeMediaMailbox {
    public static final String SCHEMA="videostudio.agent.media.v1";
    public static final int CHUNK_BYTES=524288,MAX_WIRE=1048576;
    private static final long MAX_SIZE=256L*1024*1024,MAX_TTL=7L*24*60*60*1000;
    private static final String ROOT="https://raw.githubusercontent.com/RezoxNemesis/VideoStudio-MCP/videostudio-agent-mailbox/";
    private static final ReentrantLock[] IMPORT_LOCKS=createLocks();
    private static ReentrantLock[] createLocks(){ReentrantLock[] locks=new ReentrantLock[64];for(int i=0;i<locks.length;i++)locks[i]=new ReentrantLock();return locks;}
    private NativeMediaMailbox(){}
    public interface Progress {void update(long complete,long total)throws Exception;}
    public interface Fetcher {byte[] read(String relativePath,int maxBytes)throws Exception;}
    public static final class Result {
        public final File file;public final String name,mime,sha256;public final long size;
        private Result(File file,String name,String mime,String sha256,long size){this.file=file;this.name=name;this.mime=mime;this.sha256=sha256;this.size=size;}
    }
    public static Result download(Context context,String owner,String projectId,JSONObject parameters,Progress progress)throws Exception{
        return download(context,owner,projectId,parameters,progress,NativeMediaMailbox::fetch);
    }
    /** Test seam changes transport only; authentication and path derivation remain mandatory. */
    public static Result download(Context context,String owner,String projectId,JSONObject parameters,Progress progress,Fetcher fetcher)throws Exception{
        checkpoint();
        if(context==null||fetcher==null||parameters==null||projectId==null||projectId.isEmpty()||projectId.length()>128)
            throw new IllegalArgumentException("Project and media parameters are required");
        if(!projectId.equals(parameters.opt("projectId")))throw new IllegalArgumentException("Media project identity mismatch");
        String id=string(parameters,"attachmentId");
        if(!id.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"))throw new IllegalArgumentException("Invalid attachment UUID");
        String expectedManifest=hashString(parameters,"manifestSha256"),channel=NativeAgentMailboxCrypto.channel(owner);
        String base="mailbox/"+channel+"/media/"+id+"/";
        byte[] wire=bounded(fetcher.read(base+"manifest.vsmedia",MAX_WIRE),MAX_WIRE);
        if(!expectedManifest.equals(sha(wire)))throw new IllegalArgumentException("Encrypted manifest hash mismatch");
        JSONObject manifest=decrypt(owner,channel,id,-1,wire,256*1024);
        if(!"videostudio.agent.media-manifest.v1".equals(manifest.opt("schema"))||!id.equals(manifest.opt("attachmentId")))throw new IllegalArgumentException("Manifest identity mismatch");
        String name=string(manifest,"name"),mime=string(manifest,"mime"),hash=hashString(manifest,"sha256");
        if(name.isEmpty()||name.length()>180||!StandardCharsets.UTF_8.newEncoder().canEncode(name)||name.getBytes(StandardCharsets.UTF_8).length>255||name.equals(".")||name.equals("..")||name.indexOf('/')>=0||name.indexOf('\\')>=0||name.matches("(?s).*[\\p{Cntrl}].*"))throw new IllegalArgumentException("Unsafe media filename");
        if(mime.length()>127||!mime.matches("[A-Za-z0-9][A-Za-z0-9!#$&^_.+-]*/[A-Za-z0-9][A-Za-z0-9!#$&^_.+-]*"))throw new IllegalArgumentException("Invalid media MIME type");
        long size=integer(manifest,"size",1,MAX_SIZE),created=integer(manifest,"createdAtMs",1,Long.MAX_VALUE),expires=integer(manifest,"expiresAtMs",1,Long.MAX_VALUE),now=System.currentTimeMillis();
        if(created>now+300000||expires<=now||expires<=created||expires-created>MAX_TTL)throw new IllegalArgumentException("Expired or invalid media lifetime");
        if(integer(manifest,"chunkBytes",CHUNK_BYTES,CHUNK_BYTES)!=CHUNK_BYTES)throw new IllegalArgumentException("Invalid chunk size");
        JSONArray chunks=manifest.getJSONArray("chunks");int count=(int)((size+CHUNK_BYTES-1)/CHUNK_BYTES);
        if(count>512||chunks.length()!=count)throw new IllegalArgumentException("Invalid media chunk count");
        int[] lengths=new int[count];String[] hashes=new String[count];
        for(int i=0;i<count;i++){
            JSONObject chunk=chunks.getJSONObject(i);
            if(integer(chunk,"index",0,511)!=i)throw new IllegalArgumentException("Media chunks are out of order");
            lengths[i]=(int)integer(chunk,"bytes",1,CHUNK_BYTES);
            if(lengths[i]!=Math.min(CHUNK_BYTES,size-(long)i*CHUNK_BYTES))throw new IllegalArgumentException("Media chunk dimensions mismatch");
            hashes[i]=hashString(chunk,"sha256");
        }
        checkpoint();
        byte[] lockIdentity=MessageDigest.getInstance("SHA-256").digest((channel+":"+projectId+":"+id).getBytes(StandardCharsets.UTF_8));
        ReentrantLock lock=IMPORT_LOCKS[lockIdentity[0]&63];
        try{lock.lockInterruptibly();}catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw interrupted;}
        try{
        File files=context.getFilesDir().getCanonicalFile();
        File directory=new File(files,"imports/mailbox/"+channel+"/"+sha(projectId.getBytes(StandardCharsets.UTF_8))).getCanonicalFile();
        if(!directory.getPath().startsWith(files.getPath()+File.separator)||!directory.isDirectory()&&!directory.mkdirs())throw new IOException("Private media directory unavailable");
        String intent=sha((name+"\n"+mime+"\n"+size+"\n"+hash).getBytes(StandardCharsets.UTF_8));
        File output=new File(directory,id+"-"+intent+".media"),part=new File(directory,id+"-"+intent+".part");
        if(!output.getCanonicalFile().getParentFile().equals(directory)||!part.getCanonicalFile().getParentFile().equals(directory))throw new IOException("Invalid private media path");
        if(output.exists()){
            if(output.length()!=size||!hash.equals(fileHash(output)))throw new IOException("Existing imported media failed verification");
            syncDirectory(directory);report(progress,size,size);checkpoint();return new Result(output,name,mime,hash,size);
        }
        MessageDigest digest=MessageDigest.getInstance("SHA-256");long complete=0;int next=0;
        try(RandomAccessFile file=new RandomAccessFile(part,"rw")){
            byte[] buffer=new byte[CHUNK_BYTES];
            // Never trust a prior process's partial file until every completed segment rehashes.
            while(next<count&&file.length()-complete>=lengths[next]){
                checkpoint();file.readFully(buffer,0,lengths[next]);
                if(!hashes[next].equals(sha(buffer,lengths[next])))break;
                digest.update(buffer,0,lengths[next]);complete+=lengths[next++];
            }
            file.setLength(complete);file.seek(complete);
            long usable=directory.getUsableSpace();if(usable>0&&size-complete+1048576>usable)throw new IOException("Insufficient private storage for this media");
            report(progress,complete,size);
            for(int i=next;i<count;i++){
                checkpoint();byte[] encrypted=bounded(fetcher.read(base+i+".vsmedia",MAX_WIRE),MAX_WIRE);
                // JSON may escape '/' inside canonical base64; the outer wire remains bounded.
                JSONObject plain=decrypt(owner,channel,id,i,encrypted,MAX_WIRE*3/4);
                if(!"videostudio.agent.media-chunk.v1".equals(plain.opt("schema"))||!id.equals(plain.opt("attachmentId"))||integer(plain,"index",0,511)!=i)throw new IllegalArgumentException("Media chunk identity mismatch");
                String encoded=string(plain,"base64");
                if(encoded.length()!=4*((lengths[i]+2)/3))throw new IllegalArgumentException("Media chunk base64 size mismatch");
                byte[] decoded=Base64.getDecoder().decode(encoded);
                if(decoded.length!=lengths[i]||!Base64.getEncoder().encodeToString(decoded).equals(encoded)||!hashes[i].equals(sha(decoded)))throw new IllegalArgumentException("Media chunk verification failed");
                checkpoint();file.write(decoded);file.getFD().sync();digest.update(decoded);complete+=decoded.length;report(progress,complete,size);
            }
            if(complete!=size||!hash.equals(hex(digest.digest())))throw new IllegalArgumentException("Final media hash mismatch");
            file.getFD().sync();checkpoint();
        }
        checkpoint();
        if(!part.renameTo(output))throw new IOException("Could not publish verified private media");
        syncDirectory(directory);
        return new Result(output,name,mime,hash,size);
        }finally{lock.unlock();}
    }
    private static JSONObject decrypt(String owner,String channel,String id,int index,byte[] wire,int limit)throws Exception{
        checkpoint();JSONObject envelope=NativeAgentMailboxCrypto.parse(utf8(wire),MAX_WIRE);
        if(!SCHEMA.equals(envelope.opt("schema"))||!channel.equals(envelope.opt("channel"))||!id.equals(envelope.opt("attachmentId"))||integer(envelope,"index",-1,511)!=index)throw new IllegalArgumentException("Media envelope identity mismatch");
        byte[] nonce=url64(string(envelope,"nonce")),ciphertext=url64(string(envelope,"ciphertext"));
        if(nonce.length!=12||ciphertext.length<16||ciphertext.length>limit+16)throw new IllegalArgumentException("Invalid encrypted media dimensions");
        Mac mac=Mac.getInstance("HmacSHA256");mac.init(new SecretKeySpec(owner.getBytes(StandardCharsets.UTF_8),"HmacSHA256"));
        Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.DECRYPT_MODE,new SecretKeySpec(mac.doFinal("VideoStudio media encryption v1".getBytes(StandardCharsets.UTF_8)),"AES"),new GCMParameterSpec(128,nonce));
        cipher.updateAAD((SCHEMA+":"+channel+":"+id+":"+index).getBytes(StandardCharsets.UTF_8));
        byte[] plaintext=cipher.doFinal(ciphertext);return NativeAgentMailboxCrypto.parse(utf8(plaintext),limit);
    }
    private static byte[] url64(String text){
        if(text.isEmpty()||!text.matches("[A-Za-z0-9_-]+"))throw new IllegalArgumentException("Invalid media base64url");
        byte[] bytes=Base64.getUrlDecoder().decode(text);if(!Base64.getUrlEncoder().withoutPadding().encodeToString(bytes).equals(text))throw new IllegalArgumentException("Noncanonical media base64url");return bytes;
    }
    private static String string(JSONObject o,String key)throws Exception{Object v=o.get(key);if(!(v instanceof String))throw new IllegalArgumentException(key+" must be a string");return (String)v;}
    private static String hashString(JSONObject o,String key)throws Exception{String hash=string(o,key);if(!hash.matches("[0-9a-f]{64}"))throw new IllegalArgumentException("Invalid "+key);return hash;}
    private static long integer(JSONObject o,String key,long min,long max)throws Exception{Object value=o.get(key);if(!(value instanceof Number))throw new IllegalArgumentException(key+" must be an integer");double d=((Number)value).doubleValue();long n=((Number)value).longValue();if(!Double.isFinite(d)||d!=n||n<min||n>max)throw new IllegalArgumentException("Invalid "+key);return n;}
    private static String utf8(byte[] bytes)throws Exception{return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();}
    private static byte[] bounded(byte[] value,int maximum)throws IOException{if(value==null||value.length==0||value.length>maximum)throw new IOException("Mailbox media wire exceeds bounds");return value;}
    private static String sha(byte[] bytes)throws Exception{return sha(bytes,bytes.length);}
    private static String sha(byte[] bytes,int length)throws Exception{MessageDigest digest=MessageDigest.getInstance("SHA-256");digest.update(bytes,0,length);return hex(digest.digest());}
    private static String hex(byte[] bytes){StringBuilder out=new StringBuilder();for(byte b:bytes)out.append(String.format(Locale.ROOT,"%02x",b&255));return out.toString();}
    private static String fileHash(File file)throws Exception{MessageDigest digest=MessageDigest.getInstance("SHA-256");try(InputStream in=new FileInputStream(file)){byte[] buffer=new byte[65536];int count;while((count=in.read(buffer))!=-1){checkpoint();digest.update(buffer,0,count);}}return hex(digest.digest());}
    private static void checkpoint()throws InterruptedIOException{if(Thread.currentThread().isInterrupted())throw new InterruptedIOException("Media import interrupted");}
    private static void syncDirectory(File directory)throws Exception{
        try(java.nio.channels.FileChannel channel=java.nio.channels.FileChannel.open(directory.toPath(),java.nio.file.StandardOpenOption.READ)){
            channel.force(true);
        }
    }
    private static void report(Progress progress,long complete,long total)throws Exception{checkpoint();if(progress!=null)progress.update(complete,total);checkpoint();}
    private static byte[] fetch(String path,int maximum)throws Exception{
        checkpoint();HttpURLConnection connection=(HttpURLConnection)new URL(ROOT+path).openConnection();connection.setInstanceFollowRedirects(false);connection.setConnectTimeout(20000);connection.setReadTimeout(30000);connection.setRequestProperty("Accept","application/octet-stream");
        try{
            if(connection.getResponseCode()!=200)throw new IOException("Mailbox media request failed (HTTP "+connection.getResponseCode()+")");
            if(connection.getContentLengthLong()>maximum)throw new IOException("Mailbox media response exceeds limit");
            try(InputStream in=connection.getInputStream();ByteArrayOutputStream out=new ByteArrayOutputStream()){
                byte[] buffer=new byte[16384];int read;while((read=in.read(buffer))!=-1){checkpoint();if(out.size()>maximum-read)throw new IOException("Mailbox media response exceeds limit");out.write(buffer,0,read);}return out.toByteArray();
            }
        }finally{connection.disconnect();}
    }
}
