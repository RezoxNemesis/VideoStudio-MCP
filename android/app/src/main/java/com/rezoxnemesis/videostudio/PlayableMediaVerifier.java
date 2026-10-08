package com.rezoxnemesis.videostudio;

import android.content.Context;
import android.graphics.Bitmap;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import org.json.JSONObject;

/** Verifies a real container, media track and decoded video frame before publication. */
public final class PlayableMediaVerifier {
    private PlayableMediaVerifier(){}
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
                Bitmap frame=decoder.getScaledFrameAtTime(0,MediaMetadataRetriever.OPTION_CLOSEST_SYNC,128,128);
                if(frame==null)throw new IllegalArgumentException("Output video frame could not be decoded");
                decoded=true;frame.recycle();
            }
            result.put("ok",true);result.put("playable",true);result.put("durationMs",durationUs/1000);result.put("hasVideo",video);result.put("hasAudio",audio);result.put("decodedFrame",decoded);
            return result;
        }finally{extractor.release();decoder.release();}
    }
}
