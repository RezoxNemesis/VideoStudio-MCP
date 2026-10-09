package com.rezoxnemesis.videostudio;

import android.content.*;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.provider.DocumentsContract;
import org.json.JSONObject;
import java.io.*;

/** Writes only app-managed blobs inside an existing owner-selected tree capability. */
public final class DocumentTreeBlobStore implements VaultStorageFabric.BlobStore,AutoCloseable {
    private final ContentResolver resolver;private final StorageProfileStore profiles;
    public DocumentTreeBlobStore(Context context){resolver=context.getContentResolver();profiles=new StorageProfileStore(context);}
    private Uri tree(String id){JSONObject profile=profiles.get(id);if(profile==null)throw new IllegalArgumentException("Storage profile is no longer connected");return Uri.parse(profile.optString("treeUri"));}
    @Override public long freeBytes(String id){JSONObject profile=profiles.get(id);if(profile==null)throw new IllegalArgumentException("Storage profile is no longer connected");return profile.optBoolean("quotaKnown")?Math.max(0,profile.optLong("totalBytes")-profile.optLong("usedBytes")):-1;}
    private Uri directory(Uri tree,Uri parent,String name)throws Exception{
        Uri children=DocumentsContract.buildChildDocumentsUriUsingTree(tree,DocumentsContract.getDocumentId(parent));
        try(Cursor cursor=resolver.query(children,new String[]{DocumentsContract.Document.COLUMN_DOCUMENT_ID,DocumentsContract.Document.COLUMN_DISPLAY_NAME,DocumentsContract.Document.COLUMN_MIME_TYPE},new Bundle(),null)){
            if(cursor==null)throw new IOException("Cannot list connected storage folder");
            while(cursor.moveToNext())if(name.equals(cursor.getString(1))){if(!DocumentsContract.Document.MIME_TYPE_DIR.equals(cursor.getString(2)))throw new IOException("Vault namespace is occupied by a file");return DocumentsContract.buildDocumentUriUsingTree(tree,cursor.getString(0));}
        }
        Uri created=DocumentsContract.createDocument(resolver,parent,DocumentsContract.Document.MIME_TYPE_DIR,name);if(created==null)throw new IOException("Cannot create Vault folder in connected profile");return created;
    }
    private void identity(String name,String token){if(!name.matches("[a-f0-9]{64}(-[a-f0-9]{16})?\\.(chunk|manifest)")||!token.matches("[a-f0-9-]{36}"))throw new IllegalArgumentException("Invalid managed Vault object identity");}
    private Uri objects(String profile)throws Exception{
        Uri tree=tree(profile),root=DocumentsContract.buildDocumentUriUsingTree(tree,DocumentsContract.getTreeDocumentId(tree));return directory(tree,directory(tree,root,"VideoStudioVault"),"Objects");
    }
    private Uri find(Uri tree,Uri parent,String name)throws Exception{
        Uri children=DocumentsContract.buildChildDocumentsUriUsingTree(tree,DocumentsContract.getDocumentId(parent));
        try(Cursor cursor=resolver.query(children,new String[]{DocumentsContract.Document.COLUMN_DOCUMENT_ID,DocumentsContract.Document.COLUMN_DISPLAY_NAME},new Bundle(),null)){
            if(cursor==null)throw new IOException("Cannot list connected Vault objects");
            while(cursor.moveToNext())if(name.equals(cursor.getString(1)))return DocumentsContract.buildDocumentUriUsingTree(tree,cursor.getString(0));
        }return null;
    }
    @Override public String resolve(String profile,String name,String token)throws Exception{
        identity(name,token);Uri tree=tree(profile),parent=objects(profile),target=find(tree,parent,name+"."+token);
        if(target==null)target=find(tree,parent,name+"."+token+".pending");return target==null?null:target.toString();
    }
    @Override public String put(String profile,String name,String token,File source,VaultStorageFabric.Progress progress)throws Exception{
        identity(name,token);String existing=resolve(profile,name,token);Uri target=existing==null?DocumentsContract.createDocument(resolver,objects(profile),"application/octet-stream",name+"."+token+".pending"):Uri.parse(existing);
        if(target==null)throw new IOException("Connected provider cannot create a Vault object");
        write(profile,target,source,progress);
        Uri renamed=DocumentsContract.renameDocument(resolver,target,name+"."+token);if(renamed==null)throw new IOException("Provider cannot finalize the uploaded Vault object");return renamed.toString();
    }
    @Override public String repair(String profile,String location,File source,VaultStorageFabric.Progress progress)throws Exception{
        // A damaged app-managed object is overwritten in place. Failed writes retain its location for restart.
        Uri target=ownedLocation(profile,location);write(profile,target,source,progress);return target.toString();
    }
    private void write(String profile,Uri target,File source,VaultStorageFabric.Progress progress)throws Exception{
        long bytes=0,start=System.nanoTime(),reported=0;
        try(InputStream input=new FileInputStream(source);OutputStream output=resolver.openOutputStream(target,"wt")){
            if(output==null)throw new IOException("Connected provider cannot write a Vault object");byte[] buffer=new byte[256*1024];int n;
            while((n=input.read(buffer))!=-1){if(Thread.currentThread().isInterrupted())throw new InterruptedIOException("Vault upload cancelled");output.write(buffer,0,n);bytes=Math.addExact(bytes,n);if(progress!=null&&bytes-reported>=2L*1024*1024){progress.update(bytes,source.length());reported=bytes;}}
            output.flush();
        }
        profiles.recordTransfer(profile,bytes,0,Math.max(1,(System.nanoTime()-start)/1_000_000));
    }
    private Uri ownedLocation(String profile,String location){
        Uri tree=tree(profile),uri=Uri.parse(location);
        if(!"content".equals(uri.getScheme())||!tree.getAuthority().equals(uri.getAuthority())||!DocumentsContract.getTreeDocumentId(tree).equals(DocumentsContract.getTreeDocumentId(uri)))throw new SecurityException("Replica is outside its connected folder");
        return uri;
    }
    @Override public InputStream open(String profile,String location)throws Exception{
        InputStream input=resolver.openInputStream(ownedLocation(profile,location));if(input==null)throw new IOException("Connected provider cannot open the Vault replica");
        return new FilterInputStream(input){long bytes=0,start=System.nanoTime();boolean closed;
            @Override public int read()throws IOException{int value=in.read();if(value>=0)bytes++;return value;}
            @Override public int read(byte[] buffer,int offset,int length)throws IOException{int n=in.read(buffer,offset,length);if(n>0)bytes=Math.addExact(bytes,n);return n;}
            @Override public void close()throws IOException{if(closed)return;closed=true;try{super.close();}finally{if(profiles.get(profile)!=null)profiles.recordTransfer(profile,0,bytes,Math.max(1,(System.nanoTime()-start)/1_000_000));}}
        };
    }
    @Override public void delete(String profile,String location)throws Exception{if(!DocumentsContract.deleteDocument(resolver,ownedLocation(profile,location)))throw new IOException("Provider could not discard the failed Vault upload");}
    @Override public void close(){profiles.close();}
}
