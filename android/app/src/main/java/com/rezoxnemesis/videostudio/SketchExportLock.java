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

/** Coordination for cooperating private sketch-export readers and writers.
 * The stable lock is never removed. Atomic rename alone does not promise no-replace
 * against a same-UID writer that ignores this private namespace protocol.
 */
final class SketchExportLock implements AutoCloseable {
    private static final ThreadLocal<State> ACTIVE=new ThreadLocal<>();
    private final State state;
    private boolean closed;

    private SketchExportLock(State state){this.state=state;}

    static SketchExportLock acquire(Context context,File generation)throws Exception {
        if(!Thread.holdsLock(SketchNativeExport.class))throw new IllegalStateException("Sketch export monitor is required");
        cancelled();owned(context,generation);String identity=generation.getCanonicalPath();
        State current=ACTIVE.get();
        if(current!=null){
            if(!identity.equals(current.identity)||!current.lock.isValid())throw new IllegalStateException("Nested different sketch export lock rejected");
            current.depth++;return new SketchExportLock(current);
        }
        File directory=new File(context.getFilesDir(),".sketch_export_locks");owned(context,directory);
        Files.createDirectories(directory.toPath());owned(context,directory);
        if(!Files.isDirectory(directory.toPath(),LinkOption.NOFOLLOW_LINKS))throw new IOException("Sketch export lock directory unavailable");
        File file=new File(directory,SketchDocument.sha256(identity.getBytes(StandardCharsets.UTF_8))+".lock");owned(context,file);
        if(Files.exists(file.toPath(),LinkOption.NOFOLLOW_LINKS)&&!Files.isRegularFile(file.toPath(),LinkOption.NOFOLLOW_LINKS))throw new IOException("Sketch export lock must be a regular file");
        FileChannel channel=null;FileLock held=null;
        try {
            cancelled();channel=FileChannel.open(file.toPath(),StandardOpenOption.CREATE,StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS);
            while(held==null){
                cancelled();
                try{held=channel.tryLock();}catch(OverlappingFileLockException busy){/* Another cooperating channel owns this inode. */}
                if(held==null)try{Thread.sleep(10);}catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw interrupted;}
            }
            cancelled();owned(context,file);owned(context,generation);
            if(!Files.isRegularFile(file.toPath(),LinkOption.NOFOLLOW_LINKS))throw new IOException("Sketch export lock changed");
            State state=new State(identity,channel,held);ACTIVE.set(state);channel=null;held=null;return new SketchExportLock(state);
        }catch(IOException failure){
            if(Thread.currentThread().isInterrupted()){
                InterruptedException interrupted=new InterruptedException("Sketch export lock wait cancelled; source retained");interrupted.initCause(failure);throw interrupted;
            }
            throw failure;
        }finally{
            try{if(held!=null&&held.isValid())held.release();}finally{if(channel!=null)channel.close();}
        }
    }

    static void requireHeld(Context context,File generation)throws Exception {
        cancelled();owned(context,generation);State current=ACTIVE.get();
        if(!Thread.holdsLock(SketchNativeExport.class)||current==null||!current.identity.equals(generation.getCanonicalPath())||!current.lock.isValid())
            throw new IllegalStateException("Held sketch export generation lock required");
    }

    /** Persist the rename before a database ownership witness can expose the image. */
    static void syncDirectory(Context context,File directory)throws Exception {
        requireHeld(context,directory);
        if(!Files.isDirectory(directory.toPath(),LinkOption.NOFOLLOW_LINKS))throw new IOException("Sketch export directory required");
        try(FileChannel descriptor=FileChannel.open(directory.toPath(),StandardOpenOption.READ,LinkOption.NOFOLLOW_LINKS)){
            cancelled();owned(context,directory);
            if(!Files.isDirectory(directory.toPath(),LinkOption.NOFOLLOW_LINKS))throw new IOException("Sketch export directory changed");
            descriptor.force(true);cancelled();
        }catch(IOException failure){
            if(Thread.currentThread().isInterrupted()){
                InterruptedException interrupted=new InterruptedException("Sketch export directory sync cancelled; source retained");interrupted.initCause(failure);throw interrupted;
            }
            throw failure;
        }
    }

    @Override public void close()throws IOException {
        if(closed)return;
        if(state.owner!=Thread.currentThread())throw new IllegalStateException("Sketch export lock owner differs");
        closed=true;if(--state.depth>0)return;ACTIVE.remove();
        try{if(state.lock.isValid())state.lock.release();}finally{state.channel.close();}
    }

    private static void owned(Context context,File file)throws Exception {
        Path lexical=file.toPath().toAbsolutePath();if(!lexical.normalize().equals(lexical))throw new IOException("Unnormalized sketch export lock path rejected");
        Path trusted=context.getFilesDir().toPath().toAbsolutePath().normalize(),root=context.getFilesDir().getCanonicalFile().toPath(),canonical=file.getCanonicalFile().toPath();
        if(canonical.equals(root)||!canonical.startsWith(root))throw new IOException("Private sketch export lock path rejected");
        Path boundary=lexical.startsWith(trusted)?trusted:root;if(!lexical.startsWith(boundary))throw new IOException("Private sketch export lexical path rejected");
        for(Path cursor=lexical;cursor!=null&&!cursor.equals(boundary);cursor=cursor.getParent())if(Files.isSymbolicLink(cursor))throw new IOException("Sketch export lock symlink rejected");
    }
    private static void cancelled()throws InterruptedException {if(Thread.currentThread().isInterrupted())throw new InterruptedException("Sketch export cancelled; source retained");}
    private static final class State {
        final String identity;final FileChannel channel;final FileLock lock;final Thread owner=Thread.currentThread();int depth=1;
        State(String identity,FileChannel channel,FileLock lock){this.identity=identity;this.channel=channel;this.lock=lock;}
    }
}
