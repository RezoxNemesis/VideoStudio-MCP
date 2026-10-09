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
        JSONObject result=new JSONObject();MediaExtractor extractor=new MediaExtractor();MediaMetadataRetriever decoder=new MediaMetadataRetriever();
        try(ParcelFileDescriptor fd=context.getContentResolver().openFileDescriptor(uri,"r")){
            if(fd==null)throw new IllegalArgumentException("Output is not readable");
            extractor.setDataSource(fd.getFileDescriptor());
            long durationUs=0;boolean video=false,audio=false;
            for(int i=0;i<extractor.getTrackCount();i++){
                MediaFormat format=extractor.getTrackFormat(i);String mime=format.getString(MediaFormat.KEY_MIME);
                if(mime!=null){video|=mime.startsWith("video/");audio|=mime.startsWith("audio/");}
                if(format.containsKey(MediaFormat.KEY_DURATION))durationUs=Math.max(durationUs,format.getLong(MediaFormat.KEY_DURATION));
            }
            if((requireVideo&&!video)||(!video&&!audio)||durationUs<=0)throw new IllegalArgumentException("Output has no valid playable media track");
            boolean decoded=false;
            if(video){
                decoder.setDataSource(fd.getFileDescriptor());
                for(long time:new long[]{0,durationUs/2,Math.max(0,durationUs-100_000)}){
                    if(Thread.currentThread().isInterrupted())throw new InterruptedException("Output verification cancelled");
                    Bitmap frame=decoder.getScaledFrameAtTime(time,MediaMetadataRetriever.OPTION_CLOSEST,128,128);
                    if(frame==null)throw new IllegalArgumentException("Output video frame could not be decoded at "+time+" us");
                    decoded=true;frame.recycle();
                }
            }
            result.put("ok",true);result.put("playable",true);result.put("durationMs",durationUs/1000);result.put("hasVideo",video);result.put("hasAudio",audio);result.put("decodedFrame",decoded);
            MessageDigest digest=MessageDigest.getInstance("SHA-256");long bytes=0;byte[] buffer=new byte[256*1024];
            try(InputStream in=context.getContentResolver().openInputStream(uri)){
                if(in==null)throw new IllegalArgumentException("Output became unavailable");int count;
                while((count=in.read(buffer))!=-1){if(Thread.currentThread().isInterrupted())throw new InterruptedException("Output verification cancelled");digest.update(buffer,0,count);bytes=Math.addExact(bytes,count);}
            }
            if(bytes<=0)throw new IllegalArgumentException("Output is empty");
            StringBuilder hash=new StringBuilder();for(byte b:digest.digest())hash.append(String.format(java.util.Locale.US,"%02x",b&255));
            result.put("sizeBytes",bytes);result.put("sha256",hash.toString());result.put("decodedFrameSamples",video?3:0);
            return result;
        }finally{extractor.release();decoder.release();}
    }
}
