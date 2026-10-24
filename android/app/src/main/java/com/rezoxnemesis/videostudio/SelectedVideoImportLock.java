package com.rezoxnemesis.videostudio;

import android.content.Context;
import java.io.File;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/** Stable OS coordination for cooperating private selected-video writers, including independent processes/loaders.
 * The lock inode is never replaced/deleted. This is not exclusion against a hostile same-UID writer.
 */
final class SelectedVideoImportLock implements AutoCloseable {
    private final Context context;private final File generation;private final FileChannel channel;private final FileLock lock;
    private final Thread owner=Thread.currentThread();private boolean closed;
    private SelectedVideoImportLock(Context context,File generation,FileChannel channel,FileLock lock){this.context=context;this.generation=generation;this.channel=channel;this.lock=lock;}

    static SelectedVideoImportLock acquire(Context context,File generation)throws Exception{
        cancelled();owned(context,generation);File root=context.getFilesDir().getCanonicalFile();File directory=new File(root,".selected_video_import_locks");owned(context,directory);Files.createDirectories(directory.toPath());owned(context,directory);
        if(!Files.isDirectory(directory.toPath(),LinkOption.NOFOLLOW_LINKS))throw new IOException("Selected-video lock directory unavailable");
        String identity=SketchDocument.sha256(generation.getCanonicalPath().getBytes(StandardCharsets.UTF_8));File file=new File(directory,identity+".lock");owned(context,file);
        if(Files.exists(file.toPath(),LinkOption.NOFOLLOW_LINKS)&&!Files.isRegularFile(file.toPath(),LinkOption.NOFOLLOW_LINKS))throw new IOException("Selected-video lock must be a regular file");
        FileChannel channel=null;FileLock held=null;
        try{
            channel=FileChannel.open(file.toPath(),StandardOpenOption.CREATE,StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS);
            while(held==null){
                cancelled();try{held=channel.tryLock();}catch(OverlappingFileLockException busy){/* Independent cooperating channel owns the stable inode. */}
                if(held==null)try{Thread.sleep(10);}catch(InterruptedException error){Thread.currentThread().interrupt();throw error;}
            }
            cancelled();owned(context,file);owned(context,generation);if(!Files.isRegularFile(file.toPath(),LinkOption.NOFOLLOW_LINKS))throw new IOException("Selected-video lock changed");
            channel.force(true);force(context,directory);force(context,root);
            SelectedVideoImportLock result=new SelectedVideoImportLock(context,generation,channel,held);channel=null;held=null;return result;
        }catch(IOException error){throw cancelledIo(error);}
        finally{try{if(held!=null&&held.isValid())held.release();}finally{if(channel!=null)channel.close();}}
    }

    /** Persist newly created generation path components under the same ownership lock. */
    void createGeneration()throws Exception{
        requireHeld();Files.createDirectories(generation.toPath());owned(context,generation);
        File root=context.getFilesDir().getCanonicalFile();for(File directory=generation;directory!=null;directory=directory.getParentFile()){
            force(context,directory);if(directory.getCanonicalFile().equals(root))break;
        }
    }
    /** A staged-file/journal rename becomes durable before exposing its DB ownership witness. */
    void syncGeneration()throws Exception{requireHeld();force(context,generation);}
    private void requireHeld()throws Exception{
        cancelled();if(closed||owner!=Thread.currentThread()||!lock.isValid())throw new IllegalStateException("Held selected-video generation lock required");owned(context,generation);
    }
    private static void force(Context context,File directory)throws Exception{
        owned(context,directory);if(!Files.isDirectory(directory.toPath(),LinkOption.NOFOLLOW_LINKS))throw new IOException("Selected-video directory required");
        try(FileChannel descriptor=FileChannel.open(directory.toPath(),StandardOpenOption.READ,LinkOption.NOFOLLOW_LINKS)){cancelled();descriptor.force(true);cancelled();}
        catch(IOException error){throw cancelledIo(error);}
    }
    private static void owned(Context context,File file)throws Exception{
        Path root=context.getFilesDir().getCanonicalFile().toPath(),path=file.toPath().toAbsolutePath();if(!path.normalize().equals(path)||!path.startsWith(root))throw new IOException("Selected-video lock path must be app-owned");
        for(Path cursor=path;cursor!=null&&!cursor.equals(root);cursor=cursor.getParent())if(Files.isSymbolicLink(cursor))throw new IOException("Selected-video lock symlinks are unavailable");
        if(!file.getCanonicalFile().toPath().startsWith(root))throw new IOException("Selected-video lock escaped app storage");
    }
    private static Exception cancelledIo(IOException cause){if(Thread.currentThread().isInterrupted()){InterruptedException cancelled=new InterruptedException("Selected-video lock or directory sync cancelled");cancelled.initCause(cause);return cancelled;}return cause;}
    private static void cancelled()throws InterruptedException{if(Thread.currentThread().isInterrupted())throw new InterruptedException("Selected-video lock wait cancelled");}
    @Override public void close()throws IOException{
        if(closed)return;if(owner!=Thread.currentThread())throw new IllegalStateException("Selected-video lock owner differs");closed=true;
        try{if(lock.isValid())lock.release();}finally{channel.close();}
    }
}
