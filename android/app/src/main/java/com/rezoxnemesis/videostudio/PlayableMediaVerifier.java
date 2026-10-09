package com.rezoxnemesis.videostudio;

import android.content.Context;
import android.graphics.Bitmap;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import org.json.JSONObject;
import java.io.InputStream;
import java.security.MessageDigest;

/** Verifies a real container, media track and decoded video frame before publication. */
public final class PlayableMediaVerifier {
    private PlayableMediaVerifier(){}
    static JSONObject descriptorBytes(java.io.FileDescriptor descriptor,java.util.function.BooleanSupplier cancelled)throws Exception{
        // pread binds every chunk to this inode without closing or advancing the caller's descriptor.
        return bytesProof(new InputStream(){
            private long position;private final byte[] single=new byte[1];
            public int read()throws java.io.IOException{int count=read(single,0,1);return count<0?-1:single[0]&255;}
            public int read(byte[] buffer,int offset,int length)throws java.io.IOException{
                if(length==0)return 0;try{int count=android.system.Os.pread(descriptor,buffer,offset,length,position);if(count==0)return -1;position=Math.addExact(position,count);return count;}
                catch(android.system.ErrnoException failure){throw new java.io.IOException("Could not read held verification descriptor",failure);}
            }
        },cancelled);
    }
    static JSONObject bytesProof(InputStream input,java.util.function.BooleanSupplier cancelled)throws Exception{
        check(cancelled);if(input==null)throw new IllegalArgumentException("Output became unavailable");MessageDigest digest=MessageDigest.getInstance("SHA-256");long bytes=0;byte[] buffer=new byte[256*1024];int count;
        while((count=input.read(buffer))!=-1){check(cancelled);if(count==0)continue;digest.update(buffer,0,count);bytes=Math.addExact(bytes,count);}check(cancelled);
        if(bytes<=0)throw new IllegalArgumentException("Output is empty");StringBuilder hash=new StringBuilder();for(byte b:digest.digest())hash.append(String.format(java.util.Locale.US,"%02x",b&255));return new JSONObject().put("sizeBytes",bytes).put("sha256",hash.toString());
    }
    private static void check(java.util.function.BooleanSupplier cancelled)throws java.io.InterruptedIOException{if(Thread.currentThread().isInterrupted()||cancelled.getAsBoolean())throw new java.io.InterruptedIOException("Output verification cancelled");}
    /** Both current root proofs and legacy nested proofs bind the saved job to its bytes. */
    static boolean matchesSavedProof(JSONObject saved,JSONObject actual){
        String hash=actual==null?"":actual.optString("sha256","");
        if(!hash.matches("[0-9a-f]{64}"))return false;
        if(saved==null)return true;
        String rootHash=saved.optString("sha256","");
        if(!rootHash.isEmpty()&&!rootHash.equals(hash))return false;
        JSONObject nested=saved.optJSONObject("verification");
        String nestedHash=nested==null?"":nested.optString("sha256","");
        return nestedHash.isEmpty()||nestedHash.equals(hash);
    }
    static JSONObject withFreshProof(JSONObject saved,JSONObject proof)throws Exception{
        if(!matchesSavedProof(null,proof))throw new IllegalArgumentException("Verified output checksum is missing");
        JSONObject result=saved==null?new JSONObject():new JSONObject(saved.toString());
        java.util.Iterator<String> keys=proof.keys();
        while(keys.hasNext()){String key=keys.next();result.put(key,proof.get(key));}
        result.put("verification",new JSONObject(proof.toString()));
        return result;
    }
    public static JSONObject verify(Context context,Uri uri,boolean requireVideo) throws Exception{
        return verify(context,uri,requireVideo,()->false);
    }
    public static JSONObject verify(Context context,Uri uri,boolean requireVideo,java.util.function.BooleanSupplier cancelled) throws Exception{
        check(cancelled);
        try(ParcelFileDescriptor fd=context.getContentResolver().openFileDescriptor(uri,"r")){
            if(fd==null)throw new IllegalArgumentException("Output is not readable");return verifyDescriptor(fd.getFileDescriptor(),requireVideo,cancelled);
        }
    }
    /** Borrowed FD remains open; track inspection, decoded frames and SHA all refer to one held inode. */
    public static JSONObject verifyDescriptor(java.io.FileDescriptor descriptor,boolean requireVideo,java.util.function.BooleanSupplier cancelled)throws Exception{
        check(cancelled);
        JSONObject result=new JSONObject();MediaExtractor extractor=new MediaExtractor();MediaMetadataRetriever decoder=new MediaMetadataRetriever();
        try{
            extractor.setDataSource(descriptor);
            long durationUs=0;boolean video=false,audio=false;
            for(int i=0;i<extractor.getTrackCount();i++){
                check(cancelled);
                MediaFormat format=extractor.getTrackFormat(i);String mime=format.getString(MediaFormat.KEY_MIME);
                if(mime!=null){video|=mime.startsWith("video/");audio|=mime.startsWith("audio/");}
                if(format.containsKey(MediaFormat.KEY_DURATION))durationUs=Math.max(durationUs,format.getLong(MediaFormat.KEY_DURATION));
            }
            if((requireVideo&&!video)||(!video&&!audio)||durationUs<=0)throw new IllegalArgumentException("Output has no valid playable media track");
            boolean decoded=false;
            if(video){
                decoder.setDataSource(descriptor);
                for(long time:new long[]{0,durationUs/2,Math.max(0,durationUs-100_000)}){
                    check(cancelled);
                    Bitmap frame=decoder.getScaledFrameAtTime(time,MediaMetadataRetriever.OPTION_CLOSEST,128,128);
                    if(frame==null)throw new IllegalArgumentException("Output video frame could not be decoded at "+time+" us");
                    decoded=true;frame.recycle();
                    check(cancelled);
                }
            }
            result.put("ok",true);result.put("playable",true);result.put("durationMs",durationUs/1000);result.put("hasVideo",video);result.put("hasAudio",audio);result.put("decodedFrame",decoded);
            JSONObject proof=descriptorBytes(descriptor,cancelled);result.put("sizeBytes",proof.getLong("sizeBytes")).put("sha256",proof.getString("sha256"));
            check(cancelled);result.put("decodedFrameSamples",video?3:0);
            return result;
        }finally{extractor.release();decoder.release();}
    }
}
