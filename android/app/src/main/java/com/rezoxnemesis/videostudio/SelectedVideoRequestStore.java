package com.rezoxnemesis.videostudio;

import android.content.Context;
import android.util.AtomicFile;
import java.io.*;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.StandardOpenOption;
import java.util.function.BooleanSupplier;
import org.json.JSONObject;

/** Stable per-project ownership of the manual retry pointer. Does not publish/copy any source asset. */
final class SelectedVideoRequestStore {
    private static final int MAX_BYTES=1024*1024;
    private SelectedVideoRequestStore(){}

    static JSONObject save(Context context,String projectId,JSONObject request,boolean reserve,BooleanSupplier admission)throws Exception{
        JSONObject detached=copy(request);return locked(context,projectId,admission,()->{
            File file=file(context,projectId);JSONObject current=exists(file)?readFile(file,admission):null;
            if(reserve){
                if(current!=null&&current.optString("operationId").equals(detached.optString("operationId"))){
                    if(!sameSelection(current,detached))throw new IOException("Selected video operation meaning changed");
                    if(current.has("prepared"))return copy(current);
                }
            }else{
                if(current==null||!sameSelection(current,detached))throw new IOException("A newer video selection owns the saved retry request. Reopen Retry saved video copy.");
                if(current.has("prepared")){
                    if(!detached.has("prepared"))return copy(current);
                    if(!SketchDocument.canonical(current.getJSONObject("prepared")).equals(SketchDocument.canonical(detached.getJSONObject("prepared"))))throw new IOException("The selected video operation already has different pinned source bytes");
                }
            }
            byte[] bytes=detached.toString().getBytes(StandardCharsets.UTF_8);if(bytes.length>MAX_BYTES)throw new IOException("Selected video request exceeds its bound");
            check(admission);AtomicFile atomic=new AtomicFile(file);FileOutputStream output=null;
            try{
                output=atomic.startWrite();check(admission);output.write(bytes);check(admission);atomic.finishWrite(output);output=null;force(file.getParentFile());check(admission);
            }catch(Exception error){if(output!=null)atomic.failWrite(output);throw error;}
            return copy(detached);
        });
    }

    static JSONObject read(Context context,String projectId,BooleanSupplier admission)throws Exception{
        return locked(context,projectId,admission,()->readFile(file(context,projectId),admission));
    }
    private static boolean sameSelection(JSONObject first,JSONObject second)throws Exception{
        JSONObject a=copy(first),b=copy(second);a.remove("prepared");b.remove("prepared");return SketchDocument.canonical(a).equals(SketchDocument.canonical(b));
    }
    private interface Work<T>{T run()throws Exception;}
    private static <T>T locked(Context context,String projectId,BooleanSupplier admission,Work<T> work)throws Exception{
        if(projectId==null||!projectId.matches("[A-Za-z0-9_-]{1,96}")||admission==null)throw new IOException("Selected video request identity required");check(admission);
        File root=context.getFilesDir().getCanonicalFile(),locks=new File(root,".selected_video_manual_locks");plain(locks);Files.createDirectories(locks.toPath());plain(locks);File stable=new File(locks,projectId+".lock");plain(stable);
        if(Files.exists(stable.toPath(),LinkOption.NOFOLLOW_LINKS)&&!Files.isRegularFile(stable.toPath(),LinkOption.NOFOLLOW_LINKS))throw new IOException("Selected video request lock must be regular");
        FileChannel channel=null;FileLock held=null;
        try{
            channel=FileChannel.open(stable.toPath(),StandardOpenOption.CREATE,StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS);
            while(held==null){check(admission);try{held=channel.tryLock();}catch(OverlappingFileLockException busy){/* A cooperating controller owns the same stable inode. */}if(held==null)try{Thread.sleep(10);}catch(InterruptedException error){Thread.currentThread().interrupt();throw error;}}
            check(admission);plain(stable);if(!Files.isRegularFile(stable.toPath(),LinkOption.NOFOLLOW_LINKS))throw new IOException("Selected video request lock changed");channel.force(true);force(locks);force(root);check(admission);return work.run();
        }catch(IOException error){if(Thread.currentThread().isInterrupted()){InterruptedException interrupted=new InterruptedException("Selected video request cancelled");interrupted.initCause(error);throw interrupted;}throw error;}
        finally{try{if(held!=null&&held.isValid())held.release();}finally{if(channel!=null)channel.close();}}
    }
    private static File file(Context context,String projectId)throws Exception{
        File root=context.getFilesDir().getCanonicalFile(),folder=new File(root,"selected_video_manual");plain(folder);Files.createDirectories(folder.toPath());plain(folder);File file=new File(folder,projectId+".json");
        for(File candidate:new File[]{file,new File(file.getPath()+".bak"),new File(file.getPath()+".new")})plain(candidate);force(folder);force(root);return file;
    }
    private static boolean exists(File file){return file.isFile()||new File(file.getPath()+".bak").isFile();}
    private static JSONObject readFile(File file,BooleanSupplier admission)throws Exception{
        JSONObject parsed;
        check(admission);try(InputStream input=new AtomicFile(file).openRead();ByteArrayOutputStream bytes=new ByteArrayOutputStream()){
            byte[] buffer=new byte[4096];for(int count;(count=input.read(buffer))!=-1;){check(admission);if(count>MAX_BYTES-bytes.size())throw new IOException("Selected video request exceeds its bound");bytes.write(buffer,0,count);}check(admission);parsed=new JSONObject(bytes.toString("UTF-8"));
        }
        force(file.getParentFile());check(admission);return parsed;
    }
    private static void check(BooleanSupplier admission)throws InterruptedException{if(Thread.currentThread().isInterrupted()||!admission.getAsBoolean())throw new InterruptedException("Selected video request cancelled or superseded");}
    private static void plain(File file)throws IOException{if(Files.isSymbolicLink(file.toPath()))throw new IOException("Selected video request symlinks are unavailable");}
    private static void force(File directory)throws Exception{
        plain(directory);if(!Files.isDirectory(directory.toPath(),LinkOption.NOFOLLOW_LINKS))throw new IOException("Selected video request directory required");try(FileChannel channel=FileChannel.open(directory.toPath(),StandardOpenOption.READ,LinkOption.NOFOLLOW_LINKS)){channel.force(true);}
    }
    private static JSONObject copy(JSONObject json)throws Exception{return new JSONObject(json.toString());}
}
