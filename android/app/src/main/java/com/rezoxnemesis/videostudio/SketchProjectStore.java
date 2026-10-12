package com.rezoxnemesis.videostudio;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/** Own database, transactional snapshot/history/receipt updates and immutable owned references. */
public final class SketchProjectStore implements AutoCloseable {
    public interface Mutation { JSONObject apply(JSONObject current) throws Exception; }
    private static final int HISTORY_ENTRIES = 32, HISTORY_BYTES = 16 * 1024 * 1024;
    private final Context context;
    private final Db helper;
    private final SQLiteDatabase db;
    private static final class Db extends SQLiteOpenHelper {
        Db(Context context) { super(context, "sketch_studio_v1.db", null, 1); }
        @Override public void onCreate(SQLiteDatabase db) {
            db.execSQL("CREATE TABLE sketches(id TEXT PRIMARY KEY, revision INTEGER NOT NULL,json TEXT NOT NULL,position INTEGER NOT NULL,next_position INTEGER NOT NULL)");
            db.execSQL("CREATE TABLE sketch_history(sketch_id TEXT NOT NULL,position INTEGER NOT NULL,json TEXT NOT NULL,PRIMARY KEY(sketch_id,position))");
            db.execSQL("CREATE TABLE sketch_operations(sketch_id TEXT NOT NULL,operation_id TEXT NOT NULL,fingerprint TEXT NOT NULL,result TEXT NOT NULL,PRIMARY KEY(sketch_id,operation_id))");
        }
        @Override public void onUpgrade(SQLiteDatabase db, int old, int next) { throw new IllegalStateException("Sketch migration required"); }
    }
    public SketchProjectStore(Context context) {
        this.context = context.getApplicationContext(); helper = new Db(this.context); db = helper.getWritableDatabase();
    }
    @Override public void close() { helper.close(); }

    public JSONObject get(String id) throws Exception {
        SketchDocument.id(new JSONObject().put("id", id), "id");
        try (Cursor c = db.rawQuery("SELECT json FROM sketches WHERE id=?", new String[]{id})) {
            if (!c.moveToFirst()) throw new IllegalArgumentException("UNKNOWN_SKETCH: " + id);
            JSONObject doc = new JSONObject(c.getString(0)); SketchDocument.validate(doc); return doc;
        }
    }
    public JSONArray list() throws Exception {
        JSONArray out = new JSONArray();
        try (Cursor c = db.rawQuery("SELECT json FROM sketches ORDER BY rowid DESC", null)) {
            while (c.moveToNext()) {
                JSONObject doc = new JSONObject(c.getString(0));
                out.put(new JSONObject().put("sketchId", doc.getString("id")).put("name", doc.getString("name"))
                        .put("revision", doc.getLong("revision")).put("width", doc.getInt("width")).put("height", doc.getInt("height"))
                        .put("fps", doc.getInt("fps")).put("frameCount", doc.getInt("frameCount")));
            }
        }
        return out;
    }
    public JSONObject create(JSONObject args, String fingerprint) throws Exception {
        String id = SketchDocument.id(args, "sketchId"), op = SketchDocument.id(args, "operationId");
        db.beginTransaction();
        try {
            JSONObject previous = receipt(id, op, fingerprint);
            if (previous != null) { db.setTransactionSuccessful(); return previous; }
            try (Cursor c = db.rawQuery("SELECT COUNT(*) FROM sketches", null)) {
                c.moveToFirst(); if (c.getInt(0) >= 64) throw new IllegalStateException("SKETCH_STORAGE_BUDGET: at most 64 sketch documents");
            }
            JSONObject doc = SketchDocument.create(args);
            ContentValues values = values(id, doc, 0, 1);
            if (db.insert("sketches", null, values) < 0) throw new IllegalStateException("SKETCH_ALREADY_EXISTS");
            history(id, 0, doc); JSONObject result = result(doc, false, false); saveReceipt(id, op, fingerprint, result);
            db.setTransactionSuccessful(); return result;
        } finally { db.endTransaction(); }
    }
    public JSONObject mutate(String id, String operationId, long expectedRevision, String fingerprint, Mutation mutation) throws Exception {
        db.beginTransaction();
        try {
            JSONObject previous = receipt(id, operationId, fingerprint);
            if (previous != null) { db.setTransactionSuccessful(); return previous; }
            requireReceiptCapacity(id);
            JSONObject current = get(id);
            requireRevision(current, expectedRevision);
            JSONObject next = mutation.apply(SketchDocument.copy(current));
            next.put("id", id).put("revision", Math.addExact(expectedRevision, 1));
            SketchDocument.validate(next); verifyReferences(next);
            int position;
            try (Cursor c = db.rawQuery("SELECT position,next_position FROM sketches WHERE id=?", new String[]{id})) {
                c.moveToFirst(); position = c.getInt(1);
                db.delete("sketch_history", "sketch_id=? AND position>?", new String[]{id, Integer.toString(c.getInt(0))});
            }
            history(id, position, next);
            if (db.update("sketches", values(id, next, position, position + 1), "id=? AND revision=?", new String[]{id, Long.toString(expectedRevision)}) != 1)
                throw new IllegalStateException("SKETCH_REVISION_CONFLICT");
            trimHistory(id, position);
            JSONObject result = result(next, canMove(id, position, false), false);
            saveReceipt(id, operationId, fingerprint, result); db.setTransactionSuccessful(); return result;
        } finally { db.endTransaction(); }
    }
    public JSONObject historyMove(String id, String operationId, long expectedRevision, String fingerprint, boolean redo) throws Exception {
        db.beginTransaction();
        try {
            JSONObject previous = receipt(id, operationId, fingerprint);
            if (previous != null) { db.setTransactionSuccessful(); return previous; }
            requireReceiptCapacity(id);
            JSONObject current = get(id); requireRevision(current, expectedRevision);
            int position, nextPosition;
            try (Cursor c = db.rawQuery("SELECT position,next_position FROM sketches WHERE id=?", new String[]{id})) {
                c.moveToFirst(); position = c.getInt(0); nextPosition = c.getInt(1);
            }
            String sql = "SELECT position,json FROM sketch_history WHERE sketch_id=? AND position" + (redo ? ">? ORDER BY position ASC" : "<? ORDER BY position DESC") + " LIMIT 1";
            JSONObject restored; int target;
            try (Cursor c = db.rawQuery(sql, new String[]{id, Integer.toString(position)})) {
                if (!c.moveToFirst()) throw new IllegalStateException(redo ? "SKETCH_REDO_EMPTY" : "SKETCH_UNDO_EMPTY");
                target = c.getInt(0); restored = new JSONObject(c.getString(1));
            }
            restored.put("revision", Math.addExact(expectedRevision, 1)); SketchDocument.validate(restored); verifyReferences(restored);
            if (db.update("sketches", values(id, restored, target, nextPosition), "id=? AND revision=?", new String[]{id, Long.toString(expectedRevision)}) != 1)
                throw new IllegalStateException("SKETCH_REVISION_CONFLICT");
            JSONObject result = result(restored, canMove(id, target, false), canMove(id, target, true));
            saveReceipt(id, operationId, fingerprint, result); db.setTransactionSuccessful(); return result;
        } finally { db.endTransaction(); }
    }
    public static void requireRevision(JSONObject doc, long expected) throws Exception {
        if (expected < 1 || doc.getLong("revision") != expected) throw new IllegalStateException("SKETCH_REVISION_CONFLICT: reopen the sketch before editing");
    }
    private static ContentValues values(String id, JSONObject doc, int position, int next) throws Exception {
        ContentValues values = new ContentValues(); values.put("id", id); values.put("revision", doc.getLong("revision"));
        values.put("json", doc.toString()); values.put("position", position); values.put("next_position", next); return values;
    }
    private void history(String id, int position, JSONObject doc) {
        ContentValues values = new ContentValues(); values.put("sketch_id", id); values.put("position", position); values.put("json", doc.toString());
        if (db.insertOrThrow("sketch_history", null, values) < 0) throw new IllegalStateException("Sketch history save failed");
    }
    private boolean canMove(String id, int position, boolean forward) {
        try (Cursor c = db.rawQuery("SELECT 1 FROM sketch_history WHERE sketch_id=? AND position" + (forward ? ">?" : "<?") + " LIMIT 1", new String[]{id, Integer.toString(position)})) { return c.moveToFirst(); }
    }
    private void trimHistory(String id, int current) {
        int count = 0; long bytes = 0; java.util.ArrayList<Integer> remove = new java.util.ArrayList<>();
        try (Cursor c = db.rawQuery("SELECT position,length(CAST(json AS BLOB)) FROM sketch_history WHERE sketch_id=? ORDER BY position DESC", new String[]{id})) {
            while (c.moveToNext()) { count++; bytes += c.getLong(1); if (c.getInt(0) != current && (count > HISTORY_ENTRIES || bytes > HISTORY_BYTES)) remove.add(c.getInt(0)); }
        }
        for (Integer position : remove) db.delete("sketch_history", "sketch_id=? AND position=?", new String[]{id, position.toString()});
    }
    private JSONObject receipt(String id, String op, String fingerprint) throws Exception {
        try (Cursor c = db.rawQuery("SELECT fingerprint,result FROM sketch_operations WHERE sketch_id=? AND operation_id=?", new String[]{id, op})) {
            if (!c.moveToFirst()) return null;
            if (!fingerprint.equals(c.getString(0))) throw new IllegalStateException("SKETCH_OPERATION_ID_CONFLICT: operationId was used for a different request");
            return new JSONObject(c.getString(1));
        }
    }
    private void requireReceiptCapacity(String id) {
        try (Cursor c = db.rawQuery("SELECT COUNT(*) FROM sketch_operations WHERE sketch_id=?", new String[]{id})) {
            c.moveToFirst(); if (c.getInt(0) >= 8192) throw new IllegalStateException("SKETCH_OPERATION_BUDGET: duplicate this sketch to continue after 8192 operations");
        }
    }
    private void saveReceipt(String id, String op, String fingerprint, JSONObject result) {
        ContentValues values = new ContentValues(); values.put("sketch_id", id); values.put("operation_id", op);
        values.put("fingerprint", fingerprint); values.put("result", result.toString()); db.insertOrThrow("sketch_operations", null, values);
    }
    private static JSONObject result(JSONObject doc, boolean undo, boolean redo) throws Exception {
        return new JSONObject().put("sketchId", doc.getString("id")).put("revision", doc.getLong("revision"))
                .put("documentSha256", SketchDocument.sha256(SketchDocument.canonical(doc).getBytes(StandardCharsets.UTF_8)))
                .put("canUndo", undo).put("canRedo", redo).put("persisted", true);
    }

    public File referenceFile(String sketchId, JSONObject reference) throws Exception {
        String id = SketchDocument.id(new JSONObject().put("id", sketchId), "id");
        String hash = reference.getString("sha256"), name = reference.getString("file");
        if (!hash.matches("[a-f0-9]{64}") || !name.equals(hash + ".png")) throw SketchDocument.bad("Reference path/hash rejected");
        File root = new File(context.getFilesDir(), "sketch_studio/" + id + "/references");
        File file = new File(root, name);
        if (!root.getCanonicalPath().equals(context.getFilesDir().getCanonicalPath() + "/sketch_studio/" + id + "/references") ||
                !file.getCanonicalPath().equals(new File(root.getCanonicalFile(), name).getPath()) || !file.isFile() || file.length() > 8 * 1024 * 1024)
            throw new IllegalStateException("SKETCH_REFERENCE_UNAVAILABLE");
        if (!hash.equals(fileHash(file))) throw new IllegalStateException("SKETCH_REFERENCE_CHANGED");
        return file;
    }
    public void verifyReferences(JSONObject doc) throws Exception {
        java.util.HashSet<String> verified = new java.util.HashSet<>(); JSONArray layers = doc.getJSONArray("layers");
        for (int i = 0; i < layers.length(); i++) {
            JSONArray objects = layers.getJSONObject(i).getJSONArray("objects");
            for (int j = 0; j < objects.length(); j++) {
                JSONObject object = objects.getJSONObject(j);
                if ("image".equals(object.getString("kind")) && verified.add(object.getJSONObject("reference").getString("sha256")))
                    referenceFile(doc.getString("id"), object.getJSONObject("reference"));
            }
        }
    }
    /** Called inside a revision-checked transaction. Copies/reencodes; never modifies the user original. */
    public JSONObject importReference(String sketchId, ProjectStore.Project project, ProjectStore.Asset asset) throws Exception {
        SketchDocument.id(new JSONObject().put("id", sketchId), "id");
        if (asset == null || asset.mime == null || !asset.mime.startsWith("image/")) throw SketchDocument.bad("Owned source image asset required");
        Uri uri = Uri.parse(asset.uri);
        File root = new File(context.getFilesDir(), "sketch_studio/" + sketchId + "/references");
        if (!root.getCanonicalPath().equals(context.getFilesDir().getCanonicalPath() + "/sketch_studio/" + sketchId + "/references")) throw SketchDocument.bad("Owned reference directory rejected");
        long ownedBytes = 0; File[] retained = root.listFiles(); if (retained != null) for (File file : retained) { if (file.isFile()) ownedBytes += file.length(); }
        if (ownedBytes > 128L * 1024 * 1024 || context.getFilesDir().getUsableSpace() < 64L * 1024 * 1024) throw new IllegalStateException("SKETCH_REFERENCE_STORAGE_BUDGET");
        if (!root.isDirectory() && !root.mkdirs()) throw new IllegalStateException("Sketch reference storage unavailable");
        File staged = File.createTempFile("reference-", ".input", root), encoded = null; Bitmap bitmap = null;
        try {
            try (InputStream in = context.getContentResolver().openInputStream(uri); FileOutputStream out = new FileOutputStream(staged)) {
                if (in == null) throw new IllegalStateException("Source image unavailable");
                byte[] buffer = new byte[32768]; int n; long count = 0;
                while ((n = in.read(buffer)) != -1) { count += n; if (count > 32L * 1024 * 1024) throw SketchDocument.bad("Source image exceeds 32 MiB"); out.write(buffer, 0, n); }
                out.getFD().sync();
            }
            BitmapFactory.Options options = new BitmapFactory.Options(); options.inJustDecodeBounds = true; BitmapFactory.decodeFile(staged.getPath(), options);
            if (options.outWidth < 1 || options.outHeight < 1 || (long) options.outWidth * options.outHeight > 64_000_000L)
                throw SketchDocument.bad("Source image dimensions exceed decode budget");
            options.inJustDecodeBounds = false; options.inSampleSize = 1;
            while (options.outWidth / options.inSampleSize > 1280 || options.outHeight / options.inSampleSize > 1280) options.inSampleSize *= 2;
            options.inPreferredConfig = Bitmap.Config.ARGB_8888; bitmap = BitmapFactory.decodeFile(staged.getPath(), options);
            if (bitmap == null) throw SketchDocument.bad("Source image could not be decoded");
            encoded = File.createTempFile("reference-", ".png", root);
            try (FileOutputStream out = new FileOutputStream(encoded)) { if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)) throw new IllegalStateException("Reference PNG encoding failed"); out.getFD().sync(); }
            if (encoded.length() > 8L * 1024 * 1024) throw SketchDocument.bad("Owned reference exceeds 8 MiB");
            String hash = fileHash(encoded); File target = new File(root, hash + ".png");
            if (!target.exists() && ownedBytes + encoded.length() > 128L * 1024 * 1024) throw new IllegalStateException("SKETCH_REFERENCE_STORAGE_BUDGET");
            if (target.exists()) { if (!hash.equals(fileHash(target))) throw new IllegalStateException("SKETCH_REFERENCE_CHANGED"); }
            else if (!encoded.renameTo(target)) throw new IllegalStateException("Atomic reference publication failed");
            return new JSONObject().put("file", target.getName()).put("sha256", hash).put("width", bitmap.getWidth()).put("height", bitmap.getHeight())
                    .put("sourceProjectId", project.id).put("sourceAssetId", asset.id);
        } finally { staged.delete(); if (encoded != null) encoded.delete(); if (bitmap != null) bitmap.recycle(); }
    }
    public static String fileHash(File file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256"); byte[] buffer = new byte[32768];
        try (InputStream in = new FileInputStream(file)) { int n; while ((n = in.read(buffer)) != -1) digest.update(buffer, 0, n); }
        StringBuilder out = new StringBuilder(); for (byte b : digest.digest()) out.append(String.format(java.util.Locale.ROOT, "%02x", b & 255)); return out.toString();
    }
}
