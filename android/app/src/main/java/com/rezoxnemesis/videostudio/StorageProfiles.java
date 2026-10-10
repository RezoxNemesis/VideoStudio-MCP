package com.rezoxnemesis.videostudio;

import android.content.ContentResolver;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.UriPermission;
import android.database.Cursor;
import android.net.Uri;
import android.provider.DocumentsContract;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Locale;

/**
 * Five owner-selected Storage Access Framework folder capabilities.
 * Profiles never acquire broad provider access or enumerate media. The owner
 * must select a folder with Android's picker and persist its grant before link.
 */
public final class StorageProfiles {
    public static final int MAX_PROFILES = 5;
    private static final String PREFS = "videostudio_native_v1";
    private static final String KEY_PROFILES = "storage_profiles_v1";
    private static final String KEY_ACTIVE = "storage_profiles_active_slot";
    private static final String KEY_MIGRATED = "storage_profiles_legacy_migrated_v1";
    private static final String LEGACY_TREE = "drive_workspace_tree_uri";
    private static final String LEGACY_LINKED_AT = "drive_workspace_linked_at";
    private static final Object PROFILE_LOCK = new Object();

    private final ContentResolver resolver;
    private final SharedPreferences prefs;

    public StorageProfiles(Context context) {
        Context app = context.getApplicationContext();
        resolver = app.getContentResolver();
        prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        migrateLegacyOnce();
    }

    /**
     * Returns all five slots. Provider root queries may block, so callers should
     * load these observations off the main thread. No child contents are queried.
     */
    public JSONArray profiles() {
        JSONArray configured;
        int active;
        synchronized (PROFILE_LOCK) {
            configured = readProfiles();
            active = prefs.getInt(KEY_ACTIVE, -1);
        }
        JSONArray result = new JSONArray();
        for (int slot = 0; slot < MAX_PROFILES; slot++) {
            JSONObject stored = configured.optJSONObject(slot);
            result.put(observe(slot, stored == null ? new JSONObject() : stored, active));
        }
        return result;
    }

    /** Register a persisted capability acquired through the owner's system picker. */
    public void link(int slot, Uri tree, String label, String role) {
        requireSlot(slot);
        requireTreeUri(tree);
        Grant grant = grant(tree);
        if (!grant.observed) throw new IllegalStateException("Could not verify Android's persisted folder permission");
        if (!grant.read) throw new IllegalStateException("Select this folder in Android's picker and persist read access first");
        String requestedRole = normalizeRole(role);
        String requestedLabel = cleanLabel(label, "Storage " + (slot + 1));
        synchronized (PROFILE_LOCK) {
            JSONArray configured = readProfiles();
            JSONObject profile = new JSONObject();
            try {
                profile.put("treeUri", tree.toString());
                profile.put("label", requestedLabel);
                profile.put("role", requestedRole);
                profile.put("linkedAt", System.currentTimeMillis());
                configured.put(slot, profile);
            } catch (Exception error) { throw new IllegalArgumentException("Could not save storage profile", error); }
            int active = prefs.getInt(KEY_ACTIVE, -1);
            if (!isConfigured(configured, active)) active = slot;
            persist(configured, active);
        }
    }

    /** Select the configured slot; an unavailable folder stays selected for recovery. */
    public void select(int slot) {
        requireSlot(slot);
        synchronized (PROFILE_LOCK) {
            JSONArray configured = readProfiles();
            if (!isConfigured(configured, slot)) throw new IllegalArgumentException("Link this storage slot first");
            persist(configured, slot);
        }
    }

    /** Removes configuration without deleting files or breaking shared media grants. */
    public void unlink(int slot) {
        requireSlot(slot);
        synchronized (PROFILE_LOCK) {
            JSONArray configured = readProfiles();
            try { configured.put(slot, new JSONObject()); }
            catch (Exception error) { throw new IllegalArgumentException("Could not unlink storage profile", error); }
            int active = prefs.getInt(KEY_ACTIVE, -1);
            if (active == slot || !isConfigured(configured, active)) {
                active = -1;
                for (int candidate = 0; candidate < MAX_PROFILES; candidate++) {
                    if (isConfigured(configured, candidate)) { active = candidate; break; }
                }
            }
            persist(configured, active);
        }
    }

    /** The selected scoped capability. Permission and availability may change later. */
    public Uri activeTree() {
        synchronized (PROFILE_LOCK) {
            JSONArray configured = readProfiles();
            int active = prefs.getInt(KEY_ACTIVE, -1);
            if (!isConfigured(configured, active)) return null;
            return parseTree(configured.optJSONObject(active).optString("treeUri", ""));
        }
    }

    private JSONObject observe(int slot, JSONObject configured, int active) {
        JSONObject result = new JSONObject();
        Uri tree = parseTree(configured.optString("treeUri", ""));
        Grant permission = tree == null ? new Grant() : grant(tree);
        String access = tree == null ? "unlinked" : !permission.observed ? "unknown" : permission.read
                ? permission.write ? "read_write" : "read_only" : "revoked";
        String availability = tree == null ? "unlinked" : !permission.observed ? "permission_unknown"
                : !permission.read ? "permission_required" : "unknown";
        boolean readable = false;
        Boolean canCreate = null;
        String displayName = "";
        String detail = "";
        long observedAt = System.currentTimeMillis();
        if (tree != null && permission.read) {
            try {
                Uri root = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree));
                try (Cursor cursor = resolver.query(root, new String[]{
                        DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                        DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                        DocumentsContract.Document.COLUMN_MIME_TYPE,
                        DocumentsContract.Document.COLUMN_FLAGS}, null, null, null)) {
                    if (cursor != null && cursor.moveToFirst()) {
                        int mimeIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE);
                        boolean directory = mimeIndex >= 0 && DocumentsContract.Document.MIME_TYPE_DIR.equals(cursor.getString(mimeIndex));
                        readable = directory;
                        availability = directory ? "available" : "invalid_folder";
                        int nameIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME);
                        if (nameIndex >= 0 && !cursor.isNull(nameIndex)) displayName = cursor.getString(nameIndex);
                        int flagsIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_FLAGS);
                        if (flagsIndex >= 0 && !cursor.isNull(flagsIndex)) {
                            canCreate = directory && permission.write
                                    && (cursor.getInt(flagsIndex) & DocumentsContract.Document.FLAG_DIR_SUPPORTS_CREATE) != 0;
                        }
                    } else {
                        availability = "unavailable";
                        detail = "The selected provider did not return the folder";
                    }
                }
            } catch (SecurityException denied) {
                availability = "permission_denied";
                detail = "The provider denied access to the selected folder";
            } catch (Exception unavailable) {
                availability = "unavailable";
                detail = "The selected provider could not be reached";
            }
        }
        try {
            result.put("slot", slot);
            result.put("linked", tree != null);
            result.put("active", slot == active && tree != null);
            result.put("label", cleanLabel(configured.optString("label", ""), "Storage " + (slot + 1)));
            result.put("role", configured.optString("role", "archive"));
            result.put("linkedAt", configured.optLong("linkedAt", 0L));
            result.put("displayName", displayName);
            result.put("providerAuthority", tree == null ? "" : tree.getAuthority());
            result.put("scope", "owner-selected-document-tree");
            result.put("permissionState", access);
            result.put("permissionObserved", permission.observed);
            result.put("persistedRead", permission.observed ? permission.read : JSONObject.NULL);
            result.put("persistedWrite", permission.observed ? permission.write : JSONObject.NULL);
            result.put("persistedReadWrite", permission.observed ? permission.read && permission.write : JSONObject.NULL);
            result.put("readable", readable);
            result.put("availability", availability);
            result.put("canCreateDocuments", canCreate == null ? JSONObject.NULL : canCreate);
            result.put("observedAt", observedAt);
            result.put("detail", detail);
            result.put("quotaStatus", "unknown");
            result.put("capacityBytes", JSONObject.NULL);
            result.put("availableBytes", JSONObject.NULL);
            result.put("broadProviderAccess", false);
            result.put("galleryAccess", false);
            result.put("authentication", "android-persisted-uri-grant");
        } catch (Exception error) { throw new IllegalStateException("Could not describe storage profile", error); }
        return result;
    }

    private void migrateLegacyOnce() {
        synchronized (PROFILE_LOCK) {
            if (prefs.getBoolean(KEY_MIGRATED, false)) return;
            JSONArray configured = readProfiles();
            int active = prefs.getInt(KEY_ACTIVE, -1);
            Uri legacy = parseTree(prefs.getString(LEGACY_TREE, ""));
            if (legacy != null) {
                boolean present = false;
                for (int slot = 0; slot < MAX_PROFILES; slot++) {
                    JSONObject profile = configured.optJSONObject(slot);
                    if (profile != null && legacy.toString().equals(profile.optString("treeUri"))) {
                        present = true;
                        if (!isConfigured(configured, active)) active = slot;
                    }
                }
                if (!present) for (int slot = 0; slot < MAX_PROFILES; slot++) if (!isConfigured(configured, slot)) {
                    try {
                        JSONObject profile = new JSONObject();
                        profile.put("treeUri", legacy.toString()); profile.put("label", "Linked workspace");
                        profile.put("role", "archive"); profile.put("linkedAt", prefs.getLong(LEGACY_LINKED_AT, 0L));
                        configured.put(slot, profile);
                        if (!isConfigured(configured, active)) active = slot;
                    } catch (Exception error) { throw new IllegalStateException("Could not migrate linked storage", error); }
                    break;
                }
            }
            if (!isConfigured(configured, active)) for (int slot = 0; slot < MAX_PROFILES; slot++) {
                if (isConfigured(configured, slot)) { active = slot; break; }
            }
            SharedPreferences.Editor editor = prefs.edit().putString(KEY_PROFILES, configured.toString())
                    .putInt(KEY_ACTIVE, active).putBoolean(KEY_MIGRATED, true);
            if (!editor.commit()) throw new IllegalStateException("Could not persist storage profile migration");
        }
    }

    private JSONArray readProfiles() {
        JSONArray existing;
        try { existing = new JSONArray(prefs.getString(KEY_PROFILES, "[]")); }
        catch (Exception malformed) { throw new IllegalStateException("Saved storage profiles are unreadable", malformed); }
        JSONArray configured = new JSONArray();
        for (int slot = 0; slot < MAX_PROFILES; slot++) {
            JSONObject profile = existing.optJSONObject(slot);
            configured.put(profile == null ? new JSONObject() : profile);
        }
        return configured;
    }

    private void persist(JSONArray configured, int active) {
        SharedPreferences.Editor editor = prefs.edit().putString(KEY_PROFILES, configured.toString()).putInt(KEY_ACTIVE, active);
        // Keep the previous single-profile readers working during additive rollout.
        if (isConfigured(configured, active)) {
            JSONObject profile = configured.optJSONObject(active);
            editor.putString(LEGACY_TREE, profile.optString("treeUri", ""));
            editor.putLong(LEGACY_LINKED_AT, profile.optLong("linkedAt", 0L));
        } else editor.remove(LEGACY_TREE).remove(LEGACY_LINKED_AT);
        if (!editor.commit()) throw new IllegalStateException("Could not persist storage profiles");
    }

    private Grant grant(Uri tree) {
        Grant grant = new Grant();
        try {
            for (UriPermission permission : resolver.getPersistedUriPermissions()) {
                if (tree.equals(permission.getUri())) {
                    grant.read |= permission.isReadPermission(); grant.write |= permission.isWritePermission();
                }
            }
        } catch (Exception unavailable) { grant.observed = false; }
        return grant;
    }

    private static boolean isConfigured(JSONArray configured, int slot) {
        if (slot < 0 || slot >= MAX_PROFILES) return false;
        JSONObject profile = configured.optJSONObject(slot);
        return profile != null && parseTree(profile.optString("treeUri", "")) != null;
    }

    private static Uri parseTree(String raw) {
        if (raw == null || raw.trim().isEmpty()) return null;
        try {
            Uri tree = Uri.parse(raw);
            requireTreeUri(tree);
            return tree;
        } catch (Exception invalid) { return null; }
    }

    private static void requireTreeUri(Uri tree) {
        if (tree == null || !"content".equals(tree.getScheme()) || tree.getAuthority() == null
                || tree.getAuthority().isEmpty() || !DocumentsContract.isTreeUri(tree))
            throw new IllegalArgumentException("Select a document-tree folder through Android's picker");
    }

    private static void requireSlot(int slot) {
        if (slot < 0 || slot >= MAX_PROFILES) throw new IllegalArgumentException("Storage slot must be between 0 and 4");
    }

    private static String normalizeRole(String role) {
        String requested = role == null || role.trim().isEmpty() ? "archive" : role.trim().toLowerCase(Locale.US);
        switch (requested) {
            case "workspace": case "media": case "models": case "exports": case "archive": return requested;
            default: throw new IllegalArgumentException("Storage role must be workspace, media, models, exports or archive");
        }
    }

    private static String cleanLabel(String label, String fallback) {
        String value = label == null ? "" : label.replaceAll("[\\p{Cntrl}]", " ").trim();
        if (value.isEmpty()) return fallback;
        return value.length() > 120 ? value.substring(0, 120) : value;
    }

    private static final class Grant { boolean read; boolean write; boolean observed = true; }
}
