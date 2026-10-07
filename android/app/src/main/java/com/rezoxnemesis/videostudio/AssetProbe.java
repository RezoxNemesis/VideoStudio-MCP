package com.rezoxnemesis.videostudio;

import android.content.ContentResolver;
import android.content.UriPermission;
import android.database.Cursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;

import java.util.List;

/**
 * Bounded metadata probe for URI-backed media.
 *
 * This class never reads the media body. Large local files remain owned by
 * their document provider and are addressed through the persisted URI.
 */
public final class AssetProbe {
    public static final class Result {
        public final long sizeBytes;
        public final boolean seekable;
        public final boolean persistedReadAccess;
        public final String providerAuthority;
        public final String mime;
        public final String displayName;
        public final boolean readable;

        Result(long sizeBytes,
               boolean seekable,
               boolean persistedReadAccess,
               String providerAuthority,
               String mime,
               String displayName,
               boolean readable) {
            this.sizeBytes = sizeBytes;
            this.seekable = seekable;
            this.persistedReadAccess = persistedReadAccess;
            this.providerAuthority = providerAuthority == null ? "" : providerAuthority;
            this.mime = mime == null || mime.isEmpty() ? "application/octet-stream" : mime;
            this.displayName = displayName == null || displayName.isEmpty() ? "Media" : displayName;
            this.readable = readable;
        }
    }

    private AssetProbe() {}

    public static Result probe(ContentResolver resolver, Uri uri) {
        if (resolver == null || uri == null) {
            return new Result(-1L, false, false, "", "application/octet-stream", "Media", false);
        }

        String authority = uri.getAuthority() == null ? "" : uri.getAuthority();
        String mime = "application/octet-stream";
        String name = "Media";
        long size = -1L;

        try {
            String resolved = resolver.getType(uri);
            if (resolved != null && !resolved.isEmpty()) mime = resolved;
        } catch (Exception ignored) {}

        try (Cursor cursor = resolver.query(
                uri,
                new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE},
                null,
                null,
                null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int nameColumn = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (nameColumn >= 0 && !cursor.isNull(nameColumn)) {
                    String value = cursor.getString(nameColumn);
                    if (value != null && !value.isEmpty()) name = value;
                }
                int sizeColumn = cursor.getColumnIndex(OpenableColumns.SIZE);
                if (sizeColumn >= 0 && !cursor.isNull(sizeColumn)) {
                    long value = cursor.getLong(sizeColumn);
                    if (value >= 0) size = value;
                }
            }
        } catch (Exception ignored) {}

        boolean persisted = hasPersistedReadAccess(resolver, uri);
        boolean readable = false;
        boolean seekable = false;
        ParcelFileDescriptor descriptor = null;
        try {
            descriptor = resolver.openFileDescriptor(uri, "r");
            if (descriptor != null) {
                readable = true;
                long statSize = descriptor.getStatSize();
                if (size < 0 && statSize >= 0) size = statSize;
                seekable = statSize >= 0;
            }
        } catch (Exception ignored) {
            readable = false;
            seekable = false;
        } finally {
            try { if (descriptor != null) descriptor.close(); } catch (Exception ignored) {}
        }

        return new Result(size, seekable, persisted, authority, mime, name, readable);
    }

    private static boolean hasPersistedReadAccess(ContentResolver resolver, Uri uri) {
        try {
            List<UriPermission> grants = resolver.getPersistedUriPermissions();
            for (UriPermission grant : grants) {
                if (grant != null && grant.isReadPermission() && uri.equals(grant.getUri())) return true;
            }
        } catch (Exception ignored) {}
        return false;
    }
}
