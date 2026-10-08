package com.rezoxnemesis.videostudio;

import android.content.ContentResolver;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import org.json.JSONObject;

/** Container metadata only; no media-body copy or gallery query. Run off the UI thread. */
public final class MediaTrackProbe {
    private MediaTrackProbe(){}
    public static JSONObject inspect(ContentResolver resolver,Uri uri)throws Exception{
        JSONObject result=new JSONObject();MediaExtractor extractor=new MediaExtractor();
        try(ParcelFileDescriptor fd=resolver.openFileDescriptor(uri,"r")){
            if(fd==null)throw new IllegalArgumentException("Source is unavailable");extractor.setDataSource(fd.getFileDescriptor());
            boolean audio=false,video=false;long durationUs=0;
            for(int i=0;i<extractor.getTrackCount();i++){
                MediaFormat f=extractor.getTrackFormat(i);String mime=f.getString(MediaFormat.KEY_MIME);if(mime==null)continue;
                audio|=mime.startsWith("audio/");video|=mime.startsWith("video/");
                if(f.containsKey(MediaFormat.KEY_DURATION))durationUs=Math.max(durationUs,f.getLong(MediaFormat.KEY_DURATION));
                if(mime.startsWith("video/")){if(f.containsKey(MediaFormat.KEY_WIDTH))result.put("width",f.getInteger(MediaFormat.KEY_WIDTH));if(f.containsKey(MediaFormat.KEY_HEIGHT))result.put("height",f.getInteger(MediaFormat.KEY_HEIGHT));}
            }
            result.put("audioMetadataKnown",true);result.put("hasAudio",audio);result.put("hasVideo",video);result.put("containerDurationMs",durationUs/1000);return result;
        }finally{extractor.release();}
    }
}
