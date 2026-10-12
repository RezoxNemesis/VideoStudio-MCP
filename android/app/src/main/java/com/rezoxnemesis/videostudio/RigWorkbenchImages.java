package com.rezoxnemesis.videostudio;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.Base64;
import org.json.JSONObject;

/** Decode only the bounded PNG emitted by the shared native workbench helpers. */
final class RigWorkbenchImages {
    private RigWorkbenchImages(){}
    static Bitmap decode(JSONObject image,int maximum)throws Exception{
        if(image==null||!"image/png".equals(image.optString("mimeType")))throw new IllegalArgumentException("Native diagnostic PNG unavailable");
        String encoded=image.getString("base64");if(encoded.length()>6*1024*1024)throw new IllegalArgumentException("Native diagnostic image exceeds the phone budget");
        byte[] bytes=Base64.decode(encoded,Base64.DEFAULT);
        BitmapFactory.Options bounds=new BitmapFactory.Options();bounds.inJustDecodeBounds=true;BitmapFactory.decodeByteArray(bytes,0,bytes.length,bounds);
        if(bounds.outWidth<1||bounds.outHeight<1||bounds.outWidth>maximum||bounds.outHeight>maximum||bounds.outWidth!=image.getInt("width")||bounds.outHeight!=image.getInt("height"))throw new IllegalArgumentException("Native diagnostic image geometry exceeds the accepted bounds");
        Bitmap decoded=BitmapFactory.decodeByteArray(bytes,0,bytes.length);if(decoded==null)throw new IllegalArgumentException("Native diagnostic image could not be decoded");return decoded;
    }
}
