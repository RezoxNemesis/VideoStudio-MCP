package com.rezoxnemesis.videostudio;

import android.content.*;
import android.content.pm.ProviderInfo;
import android.database.*;
import android.net.Uri;
import android.os.*;
import android.provider.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowContentResolver;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import static org.junit.Assert.*;

/** Executes the real DocumentsContract adapter against a file-backed Android DocumentsProvider. */
@RunWith(RobolectricTestRunner.class) @Config(sdk=33,manifest=Config.NONE)
public class DocumentTreeBlobStoreTest {
    private Context context;private Path directory;private String profile;private Provider provider;
    @Before public void setup()throws Exception{
        context=RuntimeEnvironment.getApplication();context.deleteDatabase("videostudio_storage.db");
        directory=Files.createTempDirectory(context.getCacheDir().toPath(),"document-provider-");
        provider=new Provider(directory);ProviderInfo info=new ProviderInfo();info.authority="studio.test.documents";info.exported=true;info.grantUriPermissions=true;info.readPermission="android.permission.MANAGE_DOCUMENTS";info.writePermission="android.permission.MANAGE_DOCUMENTS";
        provider.attachInfo(context,info);ShadowContentResolver.registerProviderInternal(info.authority,provider);
        Uri tree=Uri.parse("content://studio.test.documents/tree/root");
        try(StorageProfileStore profiles=new StorageProfileStore(context)){profile=profiles.connect(tree,"Test authorized folder");}
    }
    @After public void cleanup()throws Exception{try(var files=Files.walk(directory)){for(Path p:files.sorted(Comparator.reverseOrder()).toList())Files.deleteIfExists(p);}}
    @Test public void selectedTreeStoresAndReadsRealBytesInTheManagedNamespace()throws Exception{
        Path source=directory.resolve("selected-original");byte[] bytes=new byte[700000];new Random(87).nextBytes(bytes);Files.write(source,bytes);
        String name="a".repeat(64)+".chunk";
        try(DocumentTreeBlobStore blobs=new DocumentTreeBlobStore(context)){
            assertEquals(-1,blobs.freeBytes(profile));
            String location=blobs.put(profile,name,UUID.randomUUID().toString(),source.toFile(),(done,total)->{});
            try(InputStream stream=blobs.open(profile,location)){assertArrayEquals(bytes,stream.readAllBytes());}
            try(var stored=Files.list(directory.resolve("VideoStudioVault/Objects"))){assertTrue(stored.anyMatch(p->p.getFileName().toString().startsWith(name+".")&&!p.getFileName().toString().endsWith(".pending")));}
            assertArrayEquals(bytes,Files.readAllBytes(source));blobs.delete(profile,location);
            try(var stored=Files.list(directory.resolve("VideoStudioVault/Objects"))){assertEquals(0,stored.count());}assertTrue(Files.exists(source));
        }
    }
    @Test public void disconnectedProfilesAndForeignTreeLocationsCannotBeRead()throws Exception{
        try(DocumentTreeBlobStore blobs=new DocumentTreeBlobStore(context)){
            try{blobs.open(profile,"content://other.documents/tree/root/document/root");fail("Foreign folder accepted");}catch(SecurityException expected){}
            try(StorageProfileStore profiles=new StorageProfileStore(context)){profiles.disconnect(profile);}
            try{blobs.freeBytes(profile);fail("Disconnected capability accepted");}catch(IllegalArgumentException expected){}
        }
    }
    @Test public void pendingWriteSurvivesProcessDeathAndRenamedDocumentIsRediscovered()throws Exception{
        File source=directory.resolve("original").toFile();Files.write(source.toPath(),new byte[700000]);String name="b".repeat(64)+".chunk",token=UUID.randomUUID().toString();provider.dieOnRename=true;
        try(DocumentTreeBlobStore blobs=new DocumentTreeBlobStore(context)){try{blobs.put(profile,name,token,source,null);fail("Expected process death");}catch(SimulatedDeath expected){}}
        provider.dieOnRename=false;
        try(DocumentTreeBlobStore restarted=new DocumentTreeBlobStore(context)){
            String pending=restarted.resolve(profile,name,token);assertNotNull(pending);try(InputStream input=restarted.open(profile,pending)){assertEquals(700000,input.readAllBytes().length);}
            String finalized=restarted.put(profile,name,token,source,null);assertEquals(finalized,restarted.resolve(profile,name,token));assertNotEquals("Provider changed document ID after rename",pending,finalized);
            try(var files=Files.list(directory.resolve("VideoStudioVault/Objects"))){assertEquals("No duplicate remote allocation",1,files.count());}
        }
    }
    @Test public void damagedDocumentIsRepairedInPlaceWithoutCreatingAnotherObject()throws Exception{
        File source=directory.resolve("original").toFile();byte[] bytes=new byte[700000];new Random(9).nextBytes(bytes);Files.write(source.toPath(),bytes);
        try(DocumentTreeBlobStore blobs=new DocumentTreeBlobStore(context)){
            String location=blobs.put(profile,"c".repeat(64)+".chunk",UUID.randomUUID().toString(),source,null);
            try(OutputStream out=context.getContentResolver().openOutputStream(Uri.parse(location),"wt")){out.write(new byte[bytes.length]);}
            try(StorageProfileStore profiles=new StorageProfileStore(context)){profiles.reportQuota(profile,bytes.length,bytes.length);}assertEquals(0,blobs.freeBytes(profile));
            assertEquals(location,blobs.repair(profile,location,source,null));try(InputStream in=blobs.open(profile,location)){assertArrayEquals(bytes,in.readAllBytes());}
            try(var files=Files.list(directory.resolve("VideoStudioVault/Objects"))){assertEquals(1,files.count());}assertArrayEquals(bytes,Files.readAllBytes(source.toPath()));
        }
    }
    private static final class SimulatedDeath extends Error {}
    public static final class Provider extends DocumentsProvider {
        private final Path root;boolean dieOnRename;Provider(Path root){this.root=root;}
        @Override public boolean onCreate(){return true;}
        private Path path(String id)throws FileNotFoundException{Path result="root".equals(id)?root:root.resolve(id.substring("root/".length())).normalize();if(!result.startsWith(root))throw new FileNotFoundException();return result;}
        @Override public Cursor queryRoots(String[] projection){return new MatrixCursor(new String[]{DocumentsContract.Root.COLUMN_ROOT_ID,DocumentsContract.Root.COLUMN_DOCUMENT_ID});}
        private MatrixCursor cursor(){return new MatrixCursor(new String[]{DocumentsContract.Document.COLUMN_DOCUMENT_ID,DocumentsContract.Document.COLUMN_DISPLAY_NAME,DocumentsContract.Document.COLUMN_MIME_TYPE,DocumentsContract.Document.COLUMN_FLAGS});}
        private void row(MatrixCursor c,Path p){String id=p.equals(root)?"root":"root/"+root.relativize(p);c.addRow(new Object[]{id,p.getFileName().toString(),Files.isDirectory(p)?DocumentsContract.Document.MIME_TYPE_DIR:"application/octet-stream",DocumentsContract.Document.FLAG_SUPPORTS_WRITE|DocumentsContract.Document.FLAG_SUPPORTS_DELETE|DocumentsContract.Document.FLAG_SUPPORTS_RENAME|DocumentsContract.Document.FLAG_DIR_SUPPORTS_CREATE});}
        @Override public Cursor queryDocument(String id,String[] projection)throws FileNotFoundException{MatrixCursor c=cursor();row(c,path(id));return c;}
        @Override public Cursor queryChildDocuments(String id,String[] projection,String sort)throws FileNotFoundException{MatrixCursor c=cursor();try(var files=Files.list(path(id))){files.forEach(p->row(c,p));}catch(IOException error){throw new FileNotFoundException(error.getMessage());}return c;}
        @Override public ParcelFileDescriptor openDocument(String id,String mode,CancellationSignal signal)throws FileNotFoundException{return ParcelFileDescriptor.open(path(id).toFile(),ParcelFileDescriptor.parseMode(mode));}
        @Override public String createDocument(String parent,String mime,String name)throws FileNotFoundException{try{Path target=path(parent).resolve(name);if(DocumentsContract.Document.MIME_TYPE_DIR.equals(mime))Files.createDirectory(target);else Files.createFile(target);return "root/"+root.relativize(target);}catch(IOException error){throw new FileNotFoundException(error.getMessage());}}
        @Override public String renameDocument(String id,String name)throws FileNotFoundException{if(dieOnRename)throw new SimulatedDeath();try{Path old=path(id),target=old.resolveSibling(name);Files.move(old,target,StandardCopyOption.REPLACE_EXISTING);return "root/"+root.relativize(target);}catch(IOException error){throw new FileNotFoundException(error.getMessage());}}
        @Override public void deleteDocument(String id)throws FileNotFoundException{try{Files.delete(path(id));}catch(IOException error){throw new FileNotFoundException(error.getMessage());}}
        @Override public boolean isChildDocument(String parent,String child){return child.startsWith(parent+"/");}
    }
}
