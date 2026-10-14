package com.rezoxnemesis.videostudio;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=33,manifest=Config.NONE)
public class NativeMediaMailboxTest {
    private static final String OWNER="synthetic_app_key_abcdefghijklmnopqrstuvwxyz123456789",PROJECT="synthetic-project";
    private static final String ID="12345678-1234-4234-8234-123456789abc";
    private static byte[] bytes(String text){return text.getBytes(StandardCharsets.UTF_8);}
    private static String hash(byte[] bytes)throws Exception{StringBuilder out=new StringBuilder();for(byte b:MessageDigest.getInstance("SHA-256").digest(bytes))out.append(String.format(Locale.ROOT,"%02x",b&255));return out.toString();}
    private static byte[] encrypt(JSONObject plain,String id,int index)throws Exception{
        Mac mac=Mac.getInstance("HmacSHA256");mac.init(new SecretKeySpec(bytes(OWNER),"HmacSHA256"));byte[] key=mac.doFinal(bytes("VideoStudio media encryption v1")),nonce=new byte[12];Arrays.fill(nonce,(byte)7);
        String channel=NativeAgentMailboxCrypto.channel(OWNER);Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.ENCRYPT_MODE,new SecretKeySpec(key,"AES"),new GCMParameterSpec(128,nonce));
        cipher.updateAAD(bytes(NativeMediaMailbox.SCHEMA+":"+channel+":"+id+":"+index));
        // Preserve malformed test names as JSON escapes rather than UTF8 replacement bytes.
        String plaintext=plain.toString().replace("\uD800","\\ud800").replace("\uDC00","\\udc00");
        return bytes(new JSONObject().put("schema",NativeMediaMailbox.SCHEMA).put("channel",channel).put("attachmentId",id).put("index",index)
                .put("nonce",Base64.getUrlEncoder().withoutPadding().encodeToString(nonce)).put("ciphertext",Base64.getUrlEncoder().withoutPadding().encodeToString(cipher.doFinal(bytes(plaintext)))).toString());
    }
    private static final class Source implements NativeMediaMailbox.Fetcher {
        byte[] data,manifestWire;JSONObject manifest,params;String id;final Map<String,byte[]> chunks=new HashMap<>();final List<String> requests=new ArrayList<>();
        Source(int length)throws Exception{
            id=UUID.randomUUID().toString();data=new byte[length];for(int i=0;i<length;i++)data[i]=(byte)(i*37+11);
            JSONArray descriptors=new JSONArray();int count=(length+NativeMediaMailbox.CHUNK_BYTES-1)/NativeMediaMailbox.CHUNK_BYTES;
            for(int i=0;i<count;i++){
                byte[] chunk=Arrays.copyOfRange(data,i*NativeMediaMailbox.CHUNK_BYTES,Math.min(length,(i+1)*NativeMediaMailbox.CHUNK_BYTES));
                descriptors.put(new JSONObject().put("index",i).put("bytes",chunk.length).put("sha256",hash(chunk)));
                chunks.put(i+".vsmedia",encrypt(new JSONObject().put("schema","videostudio.agent.media-chunk.v1").put("attachmentId",id).put("index",i).put("base64",Base64.getEncoder().encodeToString(chunk)),id,i));
            }
            long now=System.currentTimeMillis();manifest=new JSONObject().put("schema","videostudio.agent.media-manifest.v1").put("attachmentId",id).put("name","Synthetic source.mp4").put("mime","video/mp4")
                    .put("size",length).put("sha256",hash(data)).put("chunkBytes",NativeMediaMailbox.CHUNK_BYTES).put("chunks",descriptors).put("createdAtMs",now-1000).put("expiresAtMs",now+3600000);
            publish();
        }
        void publish()throws Exception{manifestWire=encrypt(manifest,id,-1);params=new JSONObject().put("projectId",PROJECT).put("attachmentId",id).put("manifestSha256",hash(manifestWire));}
        @Override public byte[] read(String relative,int maximum)throws Exception{
            String prefix="mailbox/"+NativeAgentMailboxCrypto.channel(OWNER)+"/media/"+id+"/";assertTrue(relative.startsWith(prefix));assertEquals(NativeMediaMailbox.MAX_WIRE,maximum);requests.add(relative.substring(prefix.length()));
            return relative.endsWith("manifest.vsmedia")?manifestWire:chunks.get(relative.substring(prefix.length()));
        }
        NativeMediaMailbox.Result download(NativeMediaMailbox.Progress progress)throws Exception{return NativeMediaMailbox.download(RuntimeEnvironment.getApplication(),OWNER,PROJECT,params,progress,this);}
    }
    private static void rejects(Source source)throws Exception{try{source.download(null);fail("Unverified media accepted");}catch(IllegalArgumentException|IOException|javax.crypto.AEADBadTagException expected){}}
    @Test public void nodeEncryptedChunkVectorDecryptsWithoutJavaReserialization()throws Exception{
        String vector="{\"schema\":\"videostudio.agent.media.v1\",\"channel\":\"2161c0e02f148e858b117c8217bd087e\",\"attachmentId\":\"12345678-1234-4234-8234-123456789abc\",\"index\":0,\"nonce\":\"BwcHBwcHBwcHBwcH\",\"ciphertext\":\"6bV3xAqJeMDX6BzZzE2qvmH_ZsuT-s6aNMlpPc8hB0q40ffDENw2ePcFeqwEdg3Rv1tau4AS_JP3TPMDG7QmmTMz4maIgPy-8a-niGHmhWtE4GpwE3p3tCRFq8glx3ss0Sz_Td9fWrMhA0NDRlYuAi6l4YGlYBmQ_IqU62-sm93F4c21nCfxy_OeCjQB9Bl2yskG7VUYOwp0IIPNUf3YhCz1y6p2\"}";
        assertEquals("3e69c84f5a1577233841401dec7a73ab7e7e304e4051658ac036abb9db81e968",hash(bytes(vector)));
        byte[] payload=bytes("synthetic raw source");Source source=new Source(payload.length);source.id=ID;source.data=payload;
        source.manifest.put("attachmentId",ID).put("sha256",hash(payload)).put("chunks",new JSONArray().put(new JSONObject().put("index",0).put("bytes",payload.length).put("sha256",hash(payload))));
        source.chunks.clear();source.chunks.put("0.vsmedia",bytes(vector));source.publish();
        assertArrayEquals(payload,Files.readAllBytes(source.download(null).file.toPath()));
    }
    @Test public void authenticatedDownloadPublishesExactBytesAndRechecksFinalForIdempotence()throws Exception{
        Source source=new Source(700000);List<Long> progress=new ArrayList<>();NativeMediaMailbox.Result result=source.download((complete,total)->{assertEquals(700000,total);progress.add(complete);});
        assertArrayEquals(source.data,Files.readAllBytes(result.file.toPath()));assertEquals(hash(source.data),result.sha256);assertEquals("Synthetic source.mp4",result.name);assertEquals("video/mp4",result.mime);
        assertEquals(Arrays.asList(0L,524288L,700000L),progress);assertTrue(result.file.getCanonicalPath().startsWith(RuntimeEnvironment.getApplication().getFilesDir().getCanonicalPath()));
        source.requests.clear();assertEquals(result.file,source.download(null).file);assertEquals(List.of("manifest.vsmedia"),source.requests);
        Files.write(result.file.toPath(),new byte[]{1});try{source.download(null);fail("Corrupt final reused");}catch(IOException expected){}
    }
    @Test public void cancellationKeepsOnlyVerifiedChunksAndResumesWithoutRefetchingThem()throws Exception{
        Source source=new Source(700000);
        try{source.download((complete,total)->{if(complete>0)throw new IOException("synthetic cancellation");});fail("Cancellation ignored");}catch(IOException expected){}
        source.requests.clear();NativeMediaMailbox.Result result=source.download(null);assertArrayEquals(source.data,Files.readAllBytes(result.file.toPath()));
        assertEquals(Arrays.asList("manifest.vsmedia","1.vsmedia"),source.requests);assertFalse(new File(result.file.getPath().replace(".media",".part")).exists());
    }
    @Test public void tamperedManifestAndWrongOwnerFailBeforeDownloadingChunks()throws Exception{
        Source source=new Source(42);source.manifestWire[source.manifestWire.length/2]^=1;rejects(source);assertEquals(List.of("manifest.vsmedia"),source.requests);
        source=new Source(42);Source valid=source;
        try{NativeMediaMailbox.download(RuntimeEnvironment.getApplication(),OWNER+"wrong",PROJECT,source.params,null,(relative,max)->valid.manifestWire);fail("Wrong owner accepted");}catch(IllegalArgumentException expected){}
    }
    @Test public void encryptedChunkTamperingAndFinalHashMismatchCannotPublish()throws Exception{
        Source source=new Source(42);byte[] corrupt=source.chunks.get("0.vsmedia").clone();corrupt[corrupt.length-10]^=1;source.chunks.put("0.vsmedia",corrupt);rejects(source);
        source=new Source(42);source.manifest.put("sha256","0".repeat(64));source.publish();rejects(source);
    }
    @Test public void expiryChunkOrderSizeAndUnsafeNamesRejectBeforeAnyChunkRequest()throws Exception{
        for(String invalid:new String[]{"expired","order","size","name","truncated"}){
            Source source=new Source(42);
            if(invalid.equals("expired"))source.manifest.put("expiresAtMs",System.currentTimeMillis()-1);
            if(invalid.equals("order"))source.manifest.getJSONArray("chunks").getJSONObject(0).put("index",1);
            if(invalid.equals("size"))source.manifest.put("size",43);
            if(invalid.equals("name"))source.manifest.put("name","../secret.mp4");
            if(invalid.equals("truncated"))source.manifest.remove("chunks");
            source.publish();try{source.download(null);fail("Invalid manifest accepted: "+invalid);}catch(Exception expected){}
            assertEquals(List.of("manifest.vsmedia"),source.requests);
        }
    }
    @Test public void corruptResumePrefixIsRehashedAndDownloadedAgain()throws Exception{
        Source source=new Source(700000);try{source.download((complete,total)->{if(complete>0)throw new IOException("cancel");});}catch(IOException expected){}
        File root=new File(RuntimeEnvironment.getApplication().getFilesDir(),"imports/mailbox/"+NativeAgentMailboxCrypto.channel(OWNER)+"/"+hash(bytes(PROJECT)));
        File[] parts=root.listFiles((dir,name)->name.startsWith(source.id)&&name.endsWith(".part"));assertNotNull(parts);assertEquals(1,parts.length);
        byte[] corrupt=Files.readAllBytes(parts[0].toPath());corrupt[0]^=1;Files.write(parts[0].toPath(),corrupt);
        source.requests.clear();assertArrayEquals(source.data,Files.readAllBytes(source.download(null).file.toPath()));assertEquals(Arrays.asList("manifest.vsmedia","0.vsmedia","1.vsmedia"),source.requests);
    }
    @Test public void waitingForSameAttachmentCanBeCancelledWithoutStoppingItsOwner()throws Exception{
        Source source=new Source(42);android.content.Context context=RuntimeEnvironment.getApplication();
        CountDownLatch firstChunk=new CountDownLatch(1),release=new CountDownLatch(1),secondManifest=new CountDownLatch(1);
        AtomicReference<Throwable> ownerError=new AtomicReference<>(),waiterError=new AtomicReference<>();
        AtomicReference<NativeMediaMailbox.Result> result=new AtomicReference<>();
        Thread owner=new Thread(()->{try{
            result.set(NativeMediaMailbox.download(context,OWNER,PROJECT,source.params,null,(path,max)->{
                if(path.endsWith("manifest.vsmedia"))return source.manifestWire;
                firstChunk.countDown();if(!release.await(5,TimeUnit.SECONDS))throw new IOException("Test owner timed out");return source.chunks.get("0.vsmedia");
            }));
        }catch(Throwable failure){ownerError.set(failure);}},"synthetic-media-owner");
        Thread waiter=new Thread(()->{try{
            NativeMediaMailbox.download(context,OWNER,PROJECT,source.params,null,(path,max)->{secondManifest.countDown();return source.manifestWire;});
        }catch(Throwable failure){waiterError.set(failure);}},"synthetic-media-waiter");
        try{
            owner.start();assertTrue(firstChunk.await(5,TimeUnit.SECONDS));waiter.start();assertTrue(secondManifest.await(5,TimeUnit.SECONDS));
            waiter.interrupt();waiter.join(3000);assertFalse("Cancelled waiter stayed blocked",waiter.isAlive());assertNotNull(waiterError.get());
        }finally{release.countDown();owner.join(5000);waiter.interrupt();waiter.join(3000);}
        assertNull(ownerError.get());assertNotNull(result.get());assertArrayEquals(source.data,Files.readAllBytes(result.get().file.toPath()));
    }
    @Test public void filenameLimitsMatchJavaScriptUtf16AndUtf8Boundaries()throws Exception{
        for(String name:new String[]{"a".repeat(180),"é".repeat(127)+"a","😀".repeat(63)+"abc"}){
            Source source=new Source(1);source.manifest.put("name",name);source.publish();assertEquals(name,source.download(null).name);
        }
        for(String name:new String[]{"a".repeat(181),"a".repeat(255),"é".repeat(128),"😀".repeat(63)+"abcd","\uD800","\uDC00"}){
            Source source=new Source(1);source.manifest.put("name",name);source.publish();rejects(source);
        }
    }
}
