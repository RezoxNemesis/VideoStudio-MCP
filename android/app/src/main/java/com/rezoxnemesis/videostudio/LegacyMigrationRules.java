package com.rezoxnemesis.videostudio;

import android.net.Uri;
import java.util.Locale;

/**
 * Rules for safely importing project manifests from an explicitly linked,
 * user-owned VideoStudio archive. Never traverse old application storage.
 */
public final class LegacyMigrationRules {
    private LegacyMigrationRules() {}

    public static boolean validId(String id) {
        return id != null && id.matches("[A-Za-z0-9_-]{8,128}");
    }

    public static String sourceStem(String filename) {
        if(filename==null)return "";
        String clean=filename.trim().toLowerCase(Locale.US);
        int slash=Math.max(clean.lastIndexOf('/'),clean.lastIndexOf('\\'));
        if(slash>=0)clean=clean.substring(slash+1);
        for(String extension:new String[]{".jpeg",".jpg",".png",".webp",".mp4",".wav",".mp3"}){
            if(clean.endsWith(extension))return clean.substring(0,clean.length()-extension.length());
        }
        return clean;
    }

    public static boolean isKnownMediaName(String filename) {
        return filename!=null && filename.length()>=1 && filename.length()<=150
                && !filename.contains("/") && !filename.contains("\\")
                && !filename.contains("..");
    }

    /** A legacy absolute URI is descriptive metadata, never a readable URI. */
    public static String archivedRelativePath(String originalUri, String projectId) {
        if(!validId(projectId)||originalUri==null)return "";
        try {
            Uri source=Uri.parse(originalUri);
            if(!"file".equalsIgnoreCase(source.getScheme()))return "";
            String path=source.getPath();
            if(path==null)return "";
            String marker="/files/creative_workspace/projects/"+projectId+"/";
            int offset=path.indexOf(marker);
            if(offset<0)return "";
            String relative=path.substring(offset+marker.length());
            if(relative.isEmpty()||relative.startsWith("/")||relative.contains("..")
                    ||relative.contains("\\"))return "";
            // Never use an archive metadata path to reach arbitrary private files.
            return relative;
        }catch(Exception ignored){return "";}
    }

    public static boolean isOldImportsFile(String originalUri) {
        if(originalUri==null)return false;
        try {
            Uri parsed=Uri.parse(originalUri);
            if(!"file".equalsIgnoreCase(parsed.getScheme()))return false;
            String path=parsed.getPath();
            return path!=null && path.contains("/files/imports/")
                    && !path.contains("..");
        }catch(Exception ignored){return false;}
    }
}
