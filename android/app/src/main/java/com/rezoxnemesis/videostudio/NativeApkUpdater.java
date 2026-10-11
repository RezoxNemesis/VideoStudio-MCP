package com.rezoxnemesis.videostudio;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.net.Uri;

import androidx.core.content.FileProvider;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.security.MessageDigest;
import java.util.Locale;

/**
 * Checks a user-selected APK's real Android signing identity BEFORE opening
 * the installer. Same-package replacement is refused for signature mismatch.
 *
 * The user must approve the system package installer. No privileged install,
 * silent replacement, backup deletion, or Gallery enumeration takes place.
 */
public final class NativeApkUpdater {
    public static final long MAX_APK_BYTES = 350L * 1024L * 1024L;
    private NativeApkUpdater() {}

    public static final class Inspection {
        public final File file;
        public final String installedPackage, candidatePackage;
        public final String installedSigner, candidateSigner;
        public final long installedVersion, candidateVersion;
        public final String sha256, refusal;
        Inspection(File file,String installedPackage,String candidatePackage,
                   String installedSigner,String candidateSigner,
                   long installedVersion,long candidateVersion,String sha256,
                   String refusal) {
            this.file=file;
            this.installedPackage=installedPackage;
            this.candidatePackage=candidatePackage;
            this.installedSigner=installedSigner;
            this.candidateSigner=candidateSigner;
            this.installedVersion=installedVersion;
            this.candidateVersion=candidateVersion;
            this.sha256=sha256;
            this.refusal=refusal;
        }
        public boolean canUpdate() { return refusal.isEmpty(); }
        public String report() {
            return "Installed: "+installedPackage+" (code "+installedVersion+")\n"
                    +"APK: "+candidatePackage+" (code "+candidateVersion+")\n"
                    +"Installed certificate: "+installedSigner+"\n"
                    +"APK certificate: "+candidateSigner+"\n"
                    +"APK SHA-256: "+sha256+"\n\n"
                    +(canUpdate()
                        ? "Compatible in-place update. Android will ask for confirmation."
                        : "Update blocked safely: "+refusal);
        }
    }

    public static Inspection stageAndVerify(Context context, Uri userSelectedApk)
            throws Exception {
        if (context==null||userSelectedApk==null)
            throw new IllegalArgumentException("Select one APK using Android Files.");
        File directory=new File(context.getCacheDir(),"verified_updates");
        if (!directory.isDirectory() && !directory.mkdirs())
            throw new IllegalStateException("Cannot stage the selected APK privately.");
        File candidate=new File(directory,"videostudio_selected_update.apk");
        // Never accidentally keep and verify an older APK if the new copy fails.
        if(candidate.exists()&&!candidate.delete())
            throw new IllegalStateException("Cannot replace previous staged update.");
        MessageDigest hash=MessageDigest.getInstance("SHA-256");
        long size=0;
        try(InputStream in=context.getContentResolver().openInputStream(userSelectedApk);
            FileOutputStream out=new FileOutputStream(candidate)) {
            if(in==null)throw new IllegalArgumentException("Selected APK is unavailable");
            byte[] bytes=new byte[128*1024];
            int count;
            while((count=in.read(bytes))!=-1){
                if(Thread.currentThread().isInterrupted())throw new InterruptedException();
                size+=count;
                if(size>MAX_APK_BYTES)throw new IllegalArgumentException(
                        "APK exceeds the bounded 350 MB update limit");
                out.write(bytes,0,count);
                hash.update(bytes,0,count);
            }
            out.getFD().sync();
        } catch(Exception error) {
            candidate.delete();
            throw error;
        }
        if(size<1024){candidate.delete();throw new IllegalArgumentException("Empty APK");}

        PackageManager pm=context.getPackageManager();
        PackageInfo installed=pm.getPackageInfo(context.getPackageName(),
                PackageManager.GET_SIGNING_CERTIFICATES);
        PackageInfo target=pm.getPackageArchiveInfo(candidate.getAbsolutePath(),
                PackageManager.GET_SIGNING_CERTIFICATES);
        if(target==null||target.packageName==null){
            candidate.delete();
            throw new IllegalArgumentException("Not a valid, parseable Android APK");
        }
        String installedCert=fingerprint(installed);
        String candidateCert=fingerprint(target);
        long before=installed.getLongVersionCode(),after=target.getLongVersionCode();
        String error=UpdateCompatibility.rejection(
                installed.packageName, installedCert,before,
                target.packageName,candidateCert,after);
        return new Inspection(candidate,installed.packageName,target.packageName,
                installedCert,candidateCert,before,after,hex(hash.digest()),error);
    }

    /** Read only the explicitly named legacy app package, never enumerate installed apps. */
    public static JSONObject legacyInstallationStatus(Context context) {
        JSONObject status=new JSONObject();
        try {
            PackageInfo legacy=context.getPackageManager().getPackageInfo(
                    "com.rezoxnemesis.videostudio",PackageManager.GET_SIGNING_CERTIFICATES);
            status.put("installed",true);
            status.put("packageId",legacy.packageName);
            status.put("versionCode",legacy.getLongVersionCode());
            status.put("certificateSha256",fingerprint(legacy));
            status.put("oldAppDataAccessible",false);
            status.put("migrationRequiresExplicitArchive",true);
        } catch(PackageManager.NameNotFoundException missing) {
            try {status.put("installed",false);}catch(Exception ignored){}
        } catch(Exception other) {
            try {
                status.put("installed",false);
                status.put("detail","Legacy signing information not available");
            }catch(Exception ignored){}
        }
        return status;
    }

    private static String fingerprint(PackageInfo info) throws Exception {
        if(info==null||info.signingInfo==null)
            throw new IllegalStateException("APK signing information unavailable");
        Signature[] signers=info.signingInfo.getApkContentsSigners();
        if(signers==null||signers.length!=1)throw new IllegalStateException(
                "Only single-signer APKs can use the built-in updater");
        return hex(MessageDigest.getInstance("SHA-256").digest(signers[0].toByteArray()));
    }

    private static String hex(byte[] bytes) {
        StringBuilder out=new StringBuilder(bytes.length*2);
        for(byte value:bytes)out.append(String.format(Locale.US,"%02x",value&255));
        return out.toString();
    }

    public static void openSystemInstaller(Context ctx, Inspection inspected) {
        if(inspected==null||!inspected.canUpdate()||!inspected.file.isFile())
            throw new IllegalArgumentException(
                    "APK failed the compatible signing and version preflight");
        Uri shared=FileProvider.getUriForFile(ctx,
                ctx.getPackageName()+".updatefiles",inspected.file);
        Intent request=new Intent(Intent.ACTION_VIEW);
        request.setDataAndType(shared,"application/vnd.android.package-archive");
        request.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        request.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        ctx.startActivity(request);
    }
}
