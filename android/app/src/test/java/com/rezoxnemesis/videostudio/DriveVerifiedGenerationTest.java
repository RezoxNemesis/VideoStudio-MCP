package com.rezoxnemesis.videostudio;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ProviderInfo;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.provider.DocumentsContract;

import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowContentResolver;
import org.robolectric.shadows.ShadowStatFs;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static org.junit.Assert.*;

/** Executes the real SAF archive/restore flow against an app-owned fake document tree. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 33, manifest = Config.NONE)
public final class DriveVerifiedGenerationTest {
    @Test public void immutableGenerationRoundTripAndEvictionReceiptBindExactBytes() throws Exception {
        try (Fixture f = new Fixture()) {
            JSONObject receipt = f.drive.syncProject(f.project, f.workspace, null);
            assertTrue(receipt.getBoolean("verified"));
            assertTrue(receipt.getBoolean("immutableGeneration"));
            assertTrue(f.drive.verifyArchiveForEviction(f.project, f.workspace, receipt).getBoolean("evictionEligible"));
            assertEquals(1, f.remote.count("commit.json"));
            Files.write(f.source.toPath(), bytes("owner-local-version"));
            assertThrows(IllegalStateException.class, () -> f.drive.verifyArchiveForEviction(f.project, f.workspace, receipt));
            JSONObject restored = f.drive.restoreProjectWorkspace(f.project.id, f.workspace, null);
            assertEquals(receipt.getString("generationId"), restored.getString("generationId"));
            assertArrayEquals(bytes("source-version-one"), Files.readAllBytes(f.source.toPath()));
        }
    }

    @Test public void remoteChecksumCorruptionNeverCommitsAndLeavesLocalSourceUntouched() throws Exception {
        try (Fixture f = new Fixture()) {
            f.remote.corruptReadName = "source.bin";
            assertThrows(IllegalStateException.class, () -> f.drive.syncProject(f.project, f.workspace, null));
            assertEquals(0, f.remote.count("commit.json"));
            assertArrayEquals(bytes("source-version-one"), Files.readAllBytes(f.source.toPath()));
        }
    }

    @Test public void sourceChangeAfterRemoteCopyNeverPublishesCommit() throws Exception {
        try (Fixture f = new Fixture()) {
            boolean[] changed = { false };
            assertThrows(IllegalStateException.class, () -> f.drive.syncProject(f.project, f.workspace, (p, detail) -> {
                if (!changed[0] && detail.startsWith("Verified remote archive") && detail.contains("2 files")) {
                    changed[0] = true;
                    Files.write(f.source.toPath(), bytes("source-version-two"));
                }
            }));
            assertTrue("Tripwire must follow the source remote readback", changed[0]);
            assertEquals(0, f.remote.count("commit.json"));
            assertArrayEquals(bytes("source-version-two"), Files.readAllBytes(f.source.toPath()));
        }
    }

    @Test public void incompleteAndCorruptGenerationsCannotReplaceAnyLocalFile() throws Exception {
        try (Fixture f = new Fixture()) {
            JSONObject receipt = f.drive.syncProject(f.project, f.workspace, null);
            FakeTree.Node source = f.remote.inGeneration(receipt.getString("generationId"), "source.bin");
            Files.write(source.file.toPath(), bytes("remote-corruption"));
            Files.write(f.source.toPath(), bytes("owner-local-data"));
            assertThrows(IllegalStateException.class, () -> f.drive.restoreProjectWorkspace(f.project.id, f.workspace, null));
            assertArrayEquals(bytes("owner-local-data"), Files.readAllBytes(f.source.toPath()));
            f.remote.remove(f.remote.inGeneration(receipt.getString("generationId"), "commit.json"));
            assertThrows(IllegalStateException.class, () -> f.drive.restoreProjectWorkspace(f.project.id, f.workspace, null));
            assertArrayEquals(bytes("owner-local-data"), Files.readAllBytes(f.source.toPath()));
        }
    }

    @Test public void corruptNewerGenerationFallsBackToNewestCompleteMatchingGeneration() throws Exception {
        try (Fixture f = new Fixture()) {
            JSONObject first = f.drive.syncProject(f.project, f.workspace, null);
            Files.write(f.source.toPath(), bytes("source-version-two"));
            JSONObject second = f.drive.syncProject(f.project, f.workspace, null);
            Files.write(f.remote.inGeneration(second.getString("generationId"), "source.bin").file.toPath(), bytes("damaged"));
            Files.write(f.source.toPath(), bytes("owner-local-data"));
            JSONObject result = f.drive.restoreProjectWorkspace(f.project.id, f.workspace, null);
            assertEquals(first.getString("generationId"), result.getString("generationId"));
            assertArrayEquals(bytes("source-version-one"), Files.readAllBytes(f.source.toPath()));
        }
    }

    @Test public void newerNativeProjectRevisionCannotBeOverwrittenByArchive() throws Exception {
        try (Fixture f = new Fixture()) {
            f.drive.syncProject(f.project, f.workspace, null);
            f.project.name = "Owner's newer edit";
            f.store.save(f.project);
            Files.write(f.source.toPath(), bytes("newer-scene-data"));
            assertThrows(IllegalStateException.class, () -> f.drive.restoreProjectWorkspace(f.project.id, f.workspace, null));
            assertArrayEquals(bytes("newer-scene-data"), Files.readAllBytes(f.source.toPath()));
            assertEquals("Owner's newer edit", f.store.get(f.project.id).name);
        }
    }

    @Test public void projectEditDuringStagingFailsFinalWriterGuardBeforeLocalReplacement() throws Exception {
        try (Fixture f = new Fixture()) {
            f.drive.syncProject(f.project, f.workspace, null);
            Files.write(f.source.toPath(), bytes("owner-current-data"));
            boolean[] changed = { false };
            assertThrows(IllegalStateException.class, () -> f.drive.restoreProjectWorkspace(f.project.id, f.workspace, (p, detail) -> {
                if (!changed[0] && detail.startsWith("Checksum verified") && p >= 85) {
                    changed[0] = true;
                    f.project.name = "Edited during download";
                    f.store.save(f.project);
                }
            }));
            assertTrue(changed[0]);
            assertArrayEquals(bytes("owner-current-data"), Files.readAllBytes(f.source.toPath()));
            assertEquals("Edited during download", f.store.get(f.project.id).name);
        }
    }

    @Test public void legacyRootManifestRequiresExplicitUnverifiedRecovery() throws Exception {
        try (Fixture f = new Fixture()) {
            FakeTree.Node projects = f.remote.add("root", "Projects", true);
            FakeTree.Node project = f.remote.add(projects.id, f.project.id, true);
            FakeTree.Node manifest = f.remote.add(project.id, "project.json", false);
            Files.write(manifest.file.toPath(), bytes(f.project.toJson().toString()));
            FakeTree.Node source = f.remote.add(project.id, "source.bin", false);
            Files.write(source.file.toPath(), bytes("legacy-source-data"));
            assertThrows(IllegalStateException.class, () -> f.drive.restoreProjectWorkspace(f.project.id, f.workspace, null));
            assertArrayEquals(bytes("source-version-one"), Files.readAllBytes(f.source.toPath()));
            JSONObject result = f.drive.restoreLegacyProjectWorkspace(f.project.id, f.workspace, null);
            assertFalse(result.getBoolean("verified"));
            assertFalse(result.getBoolean("evictionEligible"));
            assertArrayEquals(bytes("legacy-source-data"), Files.readAllBytes(f.source.toPath()));
        }
    }

    @Test public void missingPublishedExportAbortsWithoutACommit() throws Exception {
        try (Fixture f = new Fixture()) {
            f.project.latestExportUri = "content://media/external/video/media/987654321";
            f.project.latestExportName = "published.mp4";
            ProjectStore.Asset generated = new ProjectStore.Asset();
            generated.id = UUID.randomUUID().toString();
            generated.uri = f.project.latestExportUri;
            generated.name = "published.mp4";
            generated.role = "final_render";
            generated.mime = "video/mp4";
            generated.generated = true;
            f.project.assets.add(generated);
            f.store.save(f.project);
            assertTrue(DriveWorkspaceProvider.isProjectPublishedMp4(f.project));
            assertThrows(Exception.class, () -> f.drive.syncProject(f.project, f.workspace, null));
            assertEquals(0, f.remote.count("commit.json"));
            assertArrayEquals(bytes("source-version-one"), Files.readAllBytes(f.source.toPath()));
        }
    }

    @Test public void oversizedNativeArtifactIsRejectedBeforeHashingOrRemoteWriting() throws Exception {
        try (Fixture f = new Fixture()) {
            File job = new File(f.local, "oversized.vsjob");
            try (RandomAccessFile out = new RandomAccessFile(job, "rw")) { out.setLength(OpenPipelineArtifacts.MAX_ARCHIVE + 1); }
            assertThrows(IllegalStateException.class, () -> f.drive.archiveArtifact(f.project.id, "Requests", job, "0".repeat(64), null));
            assertEquals(0, f.remote.count("Requests"));
            assertEquals(0, f.remote.reads);
        }
    }

    @Test public void artifactStorageReserveFailsBeforeRemoteReadAndKeepsExistingTarget() throws Exception {
        try (Fixture f = new Fixture()) {
            File result = new File(f.local, "result.vsresult");
            Files.write(result.toPath(), bytes("immutable-result-payload"));
            String hash = OpenPipelineArtifacts.hash(result);
            f.drive.archiveArtifact(f.project.id, "Results", result, hash, null);
            File target = new File(f.local, "restore.vsresult");
            Files.write(target.toPath(), bytes("owner-existing-result"));
            int reads = f.remote.reads;
            ShadowStatFs.registerStats(f.local.getAbsolutePath(), 131072, 131072, 131072);
            assertThrows(IllegalStateException.class, () -> f.drive.restoreArtifact(f.project.id, "Results", result.getName(), target, hash, null));
            assertEquals(reads, f.remote.reads);
            assertArrayEquals(bytes("owner-existing-result"), Files.readAllBytes(target.toPath()));
        }
    }

    @Test public void corruptArtifactNeverReplacesExistingTarget() throws Exception {
        try (Fixture f = new Fixture()) {
            File result = new File(f.local, "result.vsresult");
            Files.write(result.toPath(), bytes("immutable-result-payload"));
            String hash = OpenPipelineArtifacts.hash(result);
            f.drive.archiveArtifact(f.project.id, "Results", result, hash, null);
            Files.write(f.remote.named(result.getName()).file.toPath(), bytes("corrupted-result-payload"));
            File target = new File(f.local, "restore.vsresult");
            Files.write(target.toPath(), bytes("owner-existing-result"));
            assertThrows(IllegalStateException.class, () -> f.drive.restoreArtifact(f.project.id, "Results", result.getName(), target, hash, null));
            assertArrayEquals(bytes("owner-existing-result"), Files.readAllBytes(target.toPath()));
        }
    }

    private static byte[] bytes(String value) { return value.getBytes(StandardCharsets.UTF_8); }

    private static final class Fixture implements AutoCloseable {
        final Context context = RuntimeEnvironment.getApplication();
        final File local = new File(context.getFilesDir(), "drive_test_" + UUID.randomUUID());
        final File workspace = new File(local, "workspace");
        final File source = new File(workspace, "source.bin");
        final ProjectStore store = new ProjectStore(context);
        final ProjectStore.Project project = store.create("Verified SAF project");
        final FakeTree remote;
        final DriveWorkspaceProvider drive = new DriveWorkspaceProvider(context);
        Fixture() throws Exception {
            assertTrue(workspace.mkdirs());
            Files.write(source.toPath(), bytes("source-version-one"));
            File remoteRoot = new File(local, "remote");
            assertTrue(remoteRoot.mkdir());
            remote = new FakeTree(remoteRoot);
            String authority = "drive-test-" + UUID.randomUUID();
            ProviderInfo info = new ProviderInfo();
            info.authority = authority;
            info.name = FakeTree.class.getName();
            info.packageName = context.getPackageName();
            info.applicationInfo = context.getApplicationInfo();
            info.exported = false;
            info.grantUriPermissions = true;
            remote.attachInfo(context, info);
            ShadowContentResolver.registerProviderInternal(authority, remote);
            Uri tree = DocumentsContract.buildTreeDocumentUri(authority, "root");
            context.getContentResolver().takePersistableUriPermission(tree,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            drive.link(tree);
            ShadowStatFs.registerStats(local.getAbsolutePath(), 1048576, 1048576, 1048576);
        }
        @Override public void close() {
            drive.unlink();
            ShadowStatFs.unregisterStats(local.getAbsolutePath());
            OpenPipelineArtifacts.deleteTree(local);
        }
    }

    /** Minimal SAF contract: exact names, stream-backed files and persisted tree access. */
    private static final class FakeTree extends ContentProvider {
        final Map<String, Node> nodes = new LinkedHashMap<>();
        final File storage;
        int reads;
        String corruptReadName;
        FakeTree(File storage) {
            this.storage = storage;
            nodes.put("root", new Node("root", "", "Selected tree", true, storage));
        }
        Node add(String parent, String name, boolean directory) throws Exception {
            String id = UUID.randomUUID().toString();
            File file = new File(storage, id);
            if (directory) assertTrue(file.mkdir());
            else assertTrue(file.createNewFile());
            Node node = new Node(id, parent, name, directory, file);
            nodes.put(id, node);
            return node;
        }
        int count(String name) { int count = 0; for (Node node : nodes.values()) if (node.name.equals(name)) count++; return count; }
        Node named(String name) { for (Node node : nodes.values()) if (node.name.equals(name)) return node; throw new AssertionError("Missing remote " + name); }
        Node inGeneration(String generation, String name) {
            Node folder = named(generation);
            for (Node node : nodes.values()) if (node.parent.equals(folder.id) && node.name.equals(name)) return node;
            throw new AssertionError("Missing generation " + name);
        }
        void remove(Node node) { nodes.remove(node.id); node.file.delete(); }
        @Override public boolean onCreate() { return true; }
        @Override public Cursor query(Uri uri, String[] projection, String selection, String[] args, String order) {
            String[] columns = projection == null ? new String[]{DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_MIME_TYPE,
                    DocumentsContract.Document.COLUMN_SIZE} : projection;
            MatrixCursor rows = new MatrixCursor(columns);
            String id = DocumentsContract.getDocumentId(uri);
            for (Node node : nodes.values()) {
                boolean selected = "children".equals(uri.getLastPathSegment()) ? node.parent.equals(id) : node.id.equals(id);
                if (!selected) continue;
                Object[] values = new Object[columns.length];
                for (int i = 0; i < columns.length; i++) {
                    if (DocumentsContract.Document.COLUMN_DOCUMENT_ID.equals(columns[i])) values[i] = node.id;
                    else if (DocumentsContract.Document.COLUMN_DISPLAY_NAME.equals(columns[i])) values[i] = node.name;
                    else if (DocumentsContract.Document.COLUMN_MIME_TYPE.equals(columns[i])) values[i] = node.directory
                            ? DocumentsContract.Document.MIME_TYPE_DIR : "application/octet-stream";
                    else if (DocumentsContract.Document.COLUMN_SIZE.equals(columns[i])) values[i] = node.directory ? 0L : node.file.length();
                }
                rows.addRow(values);
            }
            return rows;
        }
        @Override public Bundle call(String method, String arg, Bundle extras) {
            try {
                Uri document = extras.getParcelable("uri");
                String id = DocumentsContract.getDocumentId(document);
                Node result;
                if ("android:createDocument".equals(method)) {
                    result = add(id, extras.getString(DocumentsContract.Document.COLUMN_DISPLAY_NAME),
                            DocumentsContract.Document.MIME_TYPE_DIR.equals(extras.getString(DocumentsContract.Document.COLUMN_MIME_TYPE)));
                } else if ("android:renameDocument".equals(method)) {
                    result = nodes.get(id);
                    if (result == null) throw new IllegalArgumentException("Missing document");
                    result.name = extras.getString(DocumentsContract.Document.COLUMN_DISPLAY_NAME);
                } else throw new UnsupportedOperationException(method);
                Bundle out = new Bundle();
                out.putParcelable("uri", DocumentsContract.buildDocumentUri(document.getAuthority(), result.id));
                return out;
            } catch (Exception failure) { throw new IllegalStateException(failure); }
        }
        @Override public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
            Node node = nodes.get(DocumentsContract.getDocumentId(uri));
            if (node == null || node.directory) throw new FileNotFoundException("Missing archive file");
            if ("r".equals(mode)) {
                reads++;
                if (node.name.equals(corruptReadName)) {
                    try { Files.write(node.file.toPath(), bytes("corrupted-copy")); }
                    catch (Exception failed) { throw new FileNotFoundException(failed.getMessage()); }
                }
            }
            return ParcelFileDescriptor.open(node.file, "r".equals(mode) ? ParcelFileDescriptor.MODE_READ_ONLY
                    : ParcelFileDescriptor.MODE_READ_WRITE | ParcelFileDescriptor.MODE_TRUNCATE);
        }
        @Override public String getType(Uri uri) { return "application/octet-stream"; }
        @Override public Uri insert(Uri uri, ContentValues values) { throw new UnsupportedOperationException(); }
        @Override public int delete(Uri uri, String selection, String[] args) { throw new UnsupportedOperationException(); }
        @Override public int update(Uri uri, ContentValues values, String selection, String[] args) { throw new UnsupportedOperationException(); }
        private static final class Node {
            final String id, parent;
            String name;
            final boolean directory;
            final File file;
            Node(String id, String parent, String name, boolean directory, File file) {
                this.id = id; this.parent = parent; this.name = name; this.directory = directory; this.file = file;
            }
        }
    }
}
