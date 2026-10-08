package com.rezoxnemesis.videostudio;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import java.io.InputStream;

/** Bounded decoding for still selection and bin thumbnails; run off the UI thread. */
final class MediaThumbnail {
    static Bitmap load(Context context, ProjectStore.Asset asset, int edge) throws Exception {
        Uri uri=Uri.parse(asset.uri);
        if(asset.mime.startsWith("image/")){
            BitmapFactory.Options options=new BitmapFactory.Options();options.inJustDecodeBounds=true;
            try(InputStream in=context.getContentResolver().openInputStream(uri)){BitmapFactory.decodeStream(in,null,options);}
            if(options.outWidth<=0||options.outHeight<=0)throw new IllegalArgumentException("Image cannot be decoded");
            options.inSampleSize=1;while(Math.max(options.outWidth,options.outHeight)/options.inSampleSize>edge)options.inSampleSize*=2;
            options.inJustDecodeBounds=false;
            try(InputStream in=context.getContentResolver().openInputStream(uri)){return BitmapFactory.decodeStream(in,null,options);}
        }
        if(asset.mime.startsWith("video/")){
            MediaMetadataRetriever decoder=new MediaMetadataRetriever();
            try{decoder.setDataSource(context,uri);return decoder.getScaledFrameAtTime(0,MediaMetadataRetriever.OPTION_CLOSEST_SYNC,edge,edge);}
            finally{decoder.release();}
        }
        return null;
    }
}
