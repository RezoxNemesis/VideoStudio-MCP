package com.rezoxnemesis.videostudio;

import android.content.Context;
import android.net.Uri;
import org.json.JSONObject;
import java.io.*;

/** Restores a user-selected editable document through the native archive validator. */
public final class SketchLocalArchive {
    private SketchLocalArchive(){}
    public static JSONObject restore(Context context,Uri uri,String operationId,String name)throws Exception {
        SketchDocument.id(new JSONObject().put("id",operationId),"id");
        if(name!=null)SketchDocument.text(new JSONObject().put("name",name),"name",160);
        if(uri==null||!"content".equals(uri.getScheme()))throw SketchDocument.bad("Select an editable archive through Files");
        File directory=new File(context.getFilesDir(),"sketch_local_imports");
        if(!directory.isDirectory()&&!directory.mkdirs())throw new IOException("Editable import storage unavailable");
        File staged=File.createTempFile("local-",".vssketch",directory);
        try {
            long total=0;
            try(InputStream in=context.getContentResolver().openInputStream(uri);FileOutputStream out=new FileOutputStream(staged)){
                if(in==null)throw new IOException("The selected editable archive cannot be opened");
                byte[] buffer=new byte[32768];int n;
                while((n=in.read(buffer))!=-1){
                    if(Thread.currentThread().isInterrupted())throw new InterruptedException("Editable import interrupted");
                    if(n>SketchPaintArchive.MAX_ARCHIVE_BYTES-total)throw SketchDocument.bad("Editable archive exceeds 256 MiB");
                    if((total%(8L*1024*1024))==0&&directory.getUsableSpace()<64L*1024*1024+buffer.length)throw new IOException("Editable import needs a 64 MiB storage reserve");
                    out.write(buffer,0,n);total+=n;
                }
                out.getFD().sync();
            }
            if(total==0)throw SketchDocument.bad("The selected editable archive is empty");
            return SketchPaintArchive.restoreFile(context,staged,SketchProjectStore.fileHash(staged),operationId,name).put("localDocument",true);
        }finally{staged.delete();}
    }
}
