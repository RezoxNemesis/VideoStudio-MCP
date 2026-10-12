package com.rezoxnemesis.videostudio;

import android.content.ContentResolver;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.os.StatFs;
import android.provider.DocumentsContract;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Folder-scoped cloud/archive storage using Android's Storage Access Framework.
 *
 * This provider intentionally avoids broad Google Drive OAuth scopes. The user
 * selects one writable document-tree folder in Android's system picker. If the
 * Google Drive DocumentsProvider is available, that folder may live in Drive.
 *
 * VideoStudio then receives only a persisted capability URI for that selected
 * tree. It cannot enumerate unrelated Drive content through this class.
 */
public final class DriveWorkspaceProvider {
    public interface Progress {
        void onProgress(int progress, String detail) throws Exception;
    }

    private static final String PREFS = "videostudio_native_v1";
    private static final String KEY_TREE = "drive_workspace_tree_uri";
    private static final String KEY_LINKED_AT = "drive_workspace_linked_at";
    private static final int BUFFER = 256 * 1024;
    private static final int MAX_REMOTE_ENTRIES = 20000;
    private static final int MAX_REMOTE_DEPTH = 32;
    private static final long MAX_FILE_BYTES = 4L * 1024 * 1024 * 1024;
    private static final long MAX_GENERATION_BYTES = 16L * 1024 * 1024 * 1024;
    private static final int MAX_MANIFEST_BYTES = 16 * 1024 * 1024;
    private static final String GENERATION_SCHEMA = "videostudio.drive.generation.v1";

    private final Context context;
    private final ContentResolver resolver;
    private final SharedPreferences prefs;

    public DriveWorkspaceProvider(Context context) {
        this.context = context.getApplicationContext();
        this.resolver = this.context.getContentResolver();
        this.prefs = this.context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public synchronized void link(Uri treeUri) {
        if (treeUri == null) throw new IllegalArgumentException("Workspace folder is required");
        String authority = treeUri.getAuthority();
        if (authority == null || authority.trim().isEmpty()) {
            throw new IllegalArgumentException("Workspace folder provider is invalid");
        }
        prefs.edit()
                .putString(KEY_TREE, treeUri.toString())
                .putLong(KEY_LINKED_AT, System.currentTimeMillis())
                .apply();
    }

    public synchronized void unlink() {
        prefs.edit().remove(KEY_TREE).remove(KEY_LINKED_AT).apply();
    }

    public synchronized boolean isLinked() {
        Uri tree = treeUri();
        return tree != null && canReadRoot(tree);
    }

    public synchronized JSONObject status() {
        JSONObject out = new JSONObject();
        Uri tree = treeUri();
        try {
            out.put("linked", tree != null && canReadRoot(tree));
            out.put("scope", "single-user-selected-document-tree");
            out.put("broadDrivePermission", false);
            out.put("galleryAccess", false);
            out.put("providerAuthority", tree == null ? "" : String.valueOf(tree.getAuthority()));
            out.put("linkedAt", prefs.getLong(KEY_LINKED_AT, 0));
            if (tree != null) {
                out.put("displayName", displayName(rootDocumentUri(tree)));
                out.put("persistedReadWrite", hasPersistedPermission(tree));
            }
        } catch (Exception ignored) {}
        return out;
    }

    public synchronized JSONObject syncProject(ProjectStore.Project project,
                                               File projectWorkspace,
                                               Progress progress) throws Exception {
        if (project == null) throw new IllegalArgumentException("Project is required");
        String projectId = requireName(project.id);
        Uri tree = requireTree();
        if (progress == null) progress = (p, d) -> {};

        // Hash a fixed project revision and every selected source before opening
        // any remote output. Originals are capabilities already owned by this
        // project, never a Gallery/Drive enumeration.
        byte[] projectBytes = project.toJson().toString().getBytes(StandardCharsets.UTF_8);
        if (projectBytes.length > MAX_MANIFEST_BYTES) throw new IllegalStateException("Project manifest is too large");
        String sourceProjectSha256 = sha256(projectBytes);
        ProjectStore.Project snapshot = ProjectStore.Project.fromJson(
                new JSONObject(new String(projectBytes, StandardCharsets.UTF_8)));
        List<ArchiveSource> sources = projectSources(snapshot, projectWorkspace, projectBytes);
        long totalBytes = 0;
        for (int i = 0; i < sources.size(); i++) {
            ArchiveSource source = sources.get(i);
            source.snapshot = hashSource(source);
            totalBytes = boundedTotal(totalBytes, source.snapshot.bytes);
            progress.onProgress(2 + (int) (12d * (i + 1) / sources.size()),
                    "Verified local archive source " + (i + 1) + "/" + sources.size());
        }
        requireProjectRevision(project, sourceProjectSha256);

        Uri projects = ensureDirectory(tree, rootDocumentUri(tree), "Projects");
        Uri projectDir = ensureDirectory(tree, projects, projectId);
        Uri generations = ensureDirectory(tree, projectDir, "Generations");
        String generationId = UUID.randomUUID().toString();
        Uri generation = createOnly(tree, generations, generationId, DocumentsContract.Document.MIME_TYPE_DIR);
        JSONArray descriptors = new JSONArray();
        long copiedBytes = 0;
        int originals = 0;
        long originalBytes = 0;
        long exportedBytes = 0;
        for (ArchiveSource source : sources) {
            Uri parent = ensureStrictRelativeDirectories(tree, generation, parentPath(source.path));
            Uri destination = createOnly(tree, parent, leafName(source.path), source.mime);
            try (InputStream in = openSource(source);
                 OutputStream out = new BufferedOutputStream(requireOutput(destination), BUFFER)) {
                requireMatch(source.snapshot, transferHash(in, out, MAX_FILE_BYTES), source.path);
            }
            requireMatch(source.snapshot, hashRemote(destination, MAX_FILE_BYTES), source.path);
            descriptors.put(new JSONObject().put("path", source.path)
                    .put("bytes", source.snapshot.bytes).put("sha256", source.snapshot.sha256)
                    .put("kind", source.kind));
            copiedBytes += source.snapshot.bytes;
            if ("original".equals(source.kind)) { originals++; originalBytes += source.snapshot.bytes; }
            if ("final_export".equals(source.kind)) exportedBytes += source.snapshot.bytes;
            progress.onProgress(15 + (int) (64d * copiedBytes / Math.max(1, totalBytes)),
                    "Verified remote archive • " + descriptors.length() + " files • " + humanBytes(copiedBytes));
        }

        // A generation remains incomplete until all local bytes and the stored
        // project revision still agree with the snapshot. New workspace files
        // appearing during the copy also invalidate this generation.
        List<ArchiveSource> current = projectSources(snapshot, projectWorkspace, projectBytes);
        if (!sourcePaths(sources).equals(sourcePaths(current))) {
            throw new IllegalStateException("Project workspace changed while archiving");
        }
        for (int i = 0; i < sources.size(); i++) {
            ArchiveSource source = sources.get(i);
            requireMatch(source.snapshot, hashSource(source), source.path);
            progress.onProgress(80 + (int) (17d * (i + 1) / sources.size()),
                    "Rechecked local archive source " + (i + 1) + "/" + sources.size());
        }
        requireProjectRevision(project, sourceProjectSha256);
        JSONObject commit = new JSONObject().put("schema", GENERATION_SCHEMA)
                .put("projectId", projectId).put("generationId", generationId)
                .put("sourceProjectUpdatedAt", snapshot.updatedAt)
                .put("sourceProjectSha256", sourceProjectSha256)
                .put("completedAt", System.currentTimeMillis()).put("files", descriptors);
        byte[] commitBytes = commit.toString().getBytes(StandardCharsets.UTF_8);
        if (commitBytes.length > MAX_MANIFEST_BYTES) throw new IllegalStateException("Archive commit is too large");
        String manifestSha256 = sha256(commitBytes);
        // LAST remote mutation: an interrupted generation has no complete commit.
        writeVerifiedBytes(tree, generation, "commit.json", commitBytes);

        JSONObject result = new JSONObject().put("ok", true).put("verified", true)
                .put("immutableGeneration", true).put("evictionEligible", true).put("generationId", generationId)
                .put("manifestSha256", manifestSha256).put("sourceProjectSha256", sourceProjectSha256)
                .put("sourceProjectUpdatedAt", snapshot.updatedAt).put("projectId", projectId)
                .put("filesWritten", descriptors.length() + 1).put("bytesWritten", copiedBytes + commitBytes.length)
                .put("originalAssetsArchived", originals).put("originalAssetBytes", originalBytes)
                .put("unavailableOriginalAssets", new JSONArray()).put("allOriginalsArchived", true)
                .put("finalMp4Archived", exportedBytes > 0).put("finalMp4Bytes", exportedBytes)
                .put("scope", "single-user-selected-document-tree")
                .put("providerAuthority", String.valueOf(tree.getAuthority()))
                .put("broadDrivePermission", false).put("galleryAccess", false);
        progress.onProgress(100, "Immutable project generation verified in linked cloud folder");
        return result;
    }

    /** Recheck the pinned remote generation and every local source immediately before eviction. */
    public synchronized JSONObject verifyArchiveForEviction(ProjectStore.Project project, File workspace,
                                                            JSONObject receipt) throws Exception {
        return verifyArchiveForEviction(project, workspace, receipt, null);
    }

    public synchronized JSONObject verifyArchiveForEviction(ProjectStore.Project project, File workspace,
                                                            JSONObject receipt, Progress progress) throws Exception {
        if (project == null || receipt == null || !receipt.optBoolean("ok")
                || !receipt.optBoolean("verified") || !receipt.optBoolean("immutableGeneration")
                || !receipt.optBoolean("evictionEligible") || !receipt.optBoolean("allOriginalsArchived")
                || !project.id.equals(receipt.optString("projectId"))) {
            throw new IllegalStateException("An immutable verified generation receipt is required for eviction");
        }
        if (progress == null) progress = (p, d) -> {};
        String projectHash = requireSha256(receipt.getString("sourceProjectSha256"));
        String manifestHash = requireSha256(receipt.getString("manifestSha256"));
        requireProjectRevision(project, projectHash);
        Uri tree = requireTree();
        Generation pinned = null;
        for (Generation generation : committedGenerations(tree, findProject(tree, requireName(project.id)), project.id)) {
            if (generation.id.equals(receipt.getString("generationId"))) { pinned = generation; break; }
        }
        if (pinned == null || !manifestHash.equals(pinned.manifestSha256)
                || !projectHash.equals(pinned.commit.getString("sourceProjectSha256"))
                || receipt.getLong("sourceProjectUpdatedAt") != pinned.commit.getLong("sourceProjectUpdatedAt")) {
            throw new IllegalStateException("Pinned cloud generation no longer matches its verified receipt");
        }
        byte[] projectBytes = project.toJson().toString().getBytes(StandardCharsets.UTF_8);
        List<ArchiveSource> sources = projectSources(project, workspace, projectBytes);
        Map<String, RemoteFile> archived = new LinkedHashMap<>();
        for (RemoteFile file : pinned.files) archived.put(file.path, file);
        if (!sourcePaths(sources).equals(archived.keySet())) {
            throw new IllegalStateException("Project file set changed after cloud archive; eviction refused");
        }
        for (int i = 0; i < sources.size(); i++) {
            ArchiveSource source = sources.get(i);
            RemoteFile stored = archived.get(source.path);
            requireMatch(stored.hash, hashSource(source), source.path);
            requireMatch(stored.hash, hashRemote(findRelativeFile(tree, pinned.folder, source.path), MAX_FILE_BYTES), source.path);
            progress.onProgress((int) (98d * (i + 1) / sources.size()),
                    "Reverified archive before eviction " + (i + 1) + "/" + sources.size());
        }
        requireProjectRevision(project, projectHash);
        if (!sourcePaths(sources).equals(sourcePaths(projectSources(project, workspace, projectBytes)))) {
            throw new IllegalStateException("Project file set changed during eviction verification");
        }
        Uri commit = findChild(tree, pinned.folder, "commit.json", false);
        if (commit == null || !manifestHash.equals(sha256(readBounded(commit)))) {
            throw new IllegalStateException("Cloud commit changed during eviction verification");
        }
        progress.onProgress(100, "Exact local sources and immutable cloud generation verified for eviction");
        return new JSONObject(receipt.toString()).put("evictionEligible", true)
                .put("verifiedForEvictionAt", System.currentTimeMillis());
    }

    /**
     * Save only the final MP4 that VideoStudio itself published for this
     * project. An arbitrary content URI, including picked Gallery media,
     * must never be treated as a generated render for cloud archiving.
     *
     * Restoring the file to the workspace does not automatically republish
     * its old MediaStore URI; callers must explicitly reopen the MP4.
     */
    /** The archive may read ONLY the single verified project-generated export. */
    static boolean isProjectPublishedMp4(ProjectStore.Project project){
        if(project==null)return false;
        String source=project.latestExportUri;
        String name=project.latestExportName;
        if(source==null||source.isEmpty()||name==null||name.isEmpty()
                ||!name.toLowerCase(java.util.Locale.US).endsWith(".mp4"))return false;
        Uri uri=Uri.parse(source);
        if(!"content".equals(uri.getScheme())||!"media".equals(uri.getAuthority()))
            return false;
        for(ProjectStore.Asset asset:project.assets){
            if(asset.generated && "final_render".equals(asset.role)
                    && source.equals(asset.uri))return true;
        }
        return false;
    }

    public JSONObject inventory() throws Exception {
        Uri tree = requireTree();
        Uri root = rootDocumentUri(tree);
        JSONArray areas = new JSONArray();
        areas.put(inventoryArea(tree, root, "Projects"));
        areas.put(inventoryArea(tree, root, "ModelPacks"));

        long bytes = 0;
        int files = 0;
        int directories = 0;
        for (int i = 0; i < areas.length(); i++) {
            JSONObject area = areas.optJSONObject(i);
            if (area == null) continue;
            bytes += area.optLong("bytes", 0);
            files += area.optInt("files", 0);
            directories += area.optInt("directories", 0);
        }

        JSONObject out = status();
        out.put("areas", areas);
        out.put("workspaceBytesVisible", bytes);
        out.put("workspaceFilesVisible", files);
        out.put("workspaceDirectoriesVisible", directories);
        out.put("scanLimit", MAX_REMOTE_ENTRIES);
        out.put("storageRole", "cold-and-warm-project-model-archive");
        return out;
    }

    public JSONObject restoreProjectWorkspace(String projectId, File projectWorkspace,
                                              Progress progress) throws Exception {
        return restoreProjectWorkspace(projectId, projectWorkspace, false, progress);
    }

    public synchronized JSONObject restoreProjectWorkspace(String projectId, File projectWorkspace,
                                              boolean preserveLocalControlMetadata,
                                              Progress progress) throws Exception {
        projectId = requireName(projectId);
        if (projectWorkspace == null) throw new IllegalArgumentException("Project workspace destination is required");
        Uri tree = requireTree();
        if (progress == null) progress = (p, d) -> {};
        final Progress restoreProgress = progress;
        ProjectStore projectsStore = new ProjectStore(context);
        ProjectStore.Project currentProject = projectsStore.get(projectId);
        if (currentProject == null) throw new IllegalStateException("Restore requires an existing native project");
        String currentProjectHash = sha256(currentProject.toJson().toString().getBytes(StandardCharsets.UTF_8));
        Uri projectDir = findProject(tree, projectId);
        List<Generation> generations = committedGenerations(tree, projectDir, projectId);
        if (generations.isEmpty()) throw new IllegalStateException(
                "No verified project generation exists; legacy archives require explicit manual recovery");
        Collections.sort(generations, (a, b) -> {
            int order = Long.compare(b.commit.optLong("completedAt"), a.commit.optLong("completedAt"));
            return order != 0 ? order : b.id.compareTo(a.id);
        });
        File parent = projectWorkspace.getCanonicalFile().getParentFile();
        if (parent == null) throw new IllegalArgumentException("Invalid workspace destination");
        ensureLocalDirectory(parent);
        Exception lastFailure = null;
        for (Generation generation : generations) {
            if (!currentProjectHash.equals(generation.commit.getString("sourceProjectSha256"))
                    || currentProject.updatedAt != generation.commit.getLong("sourceProjectUpdatedAt")) {
                lastFailure = new IllegalStateException("Archived generation does not match the current source-project revision");
                continue;
            }
            long generationBytes = 0;
            for (RemoteFile file : generation.files) generationBytes = boundedTotal(generationBytes, file.hash.bytes);
            StorageBudget.Check budget = StorageBudget.check(new StatFs(parent.getAbsolutePath()).getAvailableBytes(),
                    generationBytes, StorageBudget.DEFAULT_TRANSFER_RESERVE_BYTES);
            if (!budget.allowed) throw new LocalRestoreFailure(
                    "Project restore requires additional free storage; reserve shortfall " + budget.shortfallBytes + " bytes",
                    new IllegalStateException("waiting_storage"));
            File staging = new File(parent, ".drive_restore_" + UUID.randomUUID());
            ensureLocalDirectory(staging);
            boolean installationStarted = false;
            try {
                progress.onProgress(2, "Verifying complete cloud generation " + generation.id);
                long bytes = stageGeneration(tree, generation, staging, progress);
                installationStarted = true;
                projectsStore.guardedProject(currentProject.id, currentProject.updatedAt, guarded -> {
                    if (!currentProjectHash.equals(sha256(guarded.toJson().toString().getBytes(StandardCharsets.UTF_8)))) {
                        throw new IllegalStateException("Source project changed while staging the cloud generation");
                    }
                    installStagedFiles(projectWorkspace, staging, generation.files,
                            preserveLocalControlMetadata, restoreProgress);
                    return null;
                });
                int restored = 0;
                long restoredBytes = 0;
                for (RemoteFile file : generation.files) {
                    if (restoreFile(file.path, preserveLocalControlMetadata)) {
                        restored++;
                        restoredBytes += file.hash.bytes;
                    }
                }
                JSONObject result = new JSONObject().put("ok", true).put("verified", true)
                        .put("immutableGeneration", true).put("projectId", projectId)
                        .put("generationId", generation.id).put("manifestSha256", generation.manifestSha256)
                        .put("sourceProjectSha256", generation.commit.getString("sourceProjectSha256"))
                        .put("sourceProjectUpdatedAt", generation.commit.getLong("sourceProjectUpdatedAt"))
                        .put("filesRestored", restored).put("bytesRestored", restoredBytes)
                        .put("bytesVerified", bytes).put("scope", "single-user-selected-document-tree")
                        .put("preservedLocalControlMetadata", preserveLocalControlMetadata).put("galleryAccess", false);
                progress.onProgress(100, "Verified project generation restored");
                return result;
            } catch (InterruptedException stop) {
                throw stop;
            } catch (Exception invalid) {
                // Only a corrupt/incomplete remote generation may be skipped.
                // Once installation begins its own rollback protects local data;
                // a local commit failure is never masked by an older generation.
                if (installationStarted || invalid instanceof LocalRestoreFailure) throw invalid;
                lastFailure = invalid;
            } finally {
                removeOwnedDirectory(staging);
            }
        }
        throw new IllegalStateException("No complete checksum-verified project generation is available", lastFailure);
    }

    /** Explicit manual recovery only. Never establishes an eviction receipt. */
    public synchronized JSONObject restoreLegacyProjectWorkspace(String projectId, File projectWorkspace,
                                                                 Progress progress) throws Exception {
        projectId = requireName(projectId);
        if (projectWorkspace == null) throw new IllegalArgumentException("Project workspace destination is required");
        if (progress == null) progress = (p, d) -> {};
        Uri tree = requireTree();
        Uri projectDir = findProject(tree, projectId);
        if (findChild(tree, projectDir, "project.json", false) == null) {
            throw new IllegalStateException("No legacy project manifest exists at the project root");
        }
        List<RemoteEntry> entries = new ArrayList<>();
        collectRemoteFiles(tree, projectDir, "", entries, 0);
        File parent = projectWorkspace.getCanonicalFile().getParentFile();
        if (parent == null) throw new IllegalArgumentException("Invalid workspace destination");
        ensureLocalDirectory(parent);
        File staging = new File(parent, ".drive_legacy_" + UUID.randomUUID());
        ensureLocalDirectory(staging);
        List<RemoteFile> files = new ArrayList<>();
        long bytes = 0;
        try {
            for (RemoteEntry entry : entries) {
                if (entry.relative.startsWith("Generations/") || entry.relative.startsWith("Requests/")
                        || entry.relative.startsWith("Results/") || entry.relative.startsWith("Models/")) continue;
                String path = requireRelativePath(entry.relative);
                File target = safeTarget(staging, path);
                ensureLocalDirectory(target.getParentFile());
                Hash hash;
                try (InputStream in = requireInput(entry.uri);
                     OutputStream out = new BufferedOutputStream(new FileOutputStream(target), BUFFER)) {
                    hash = transferHash(in, out, MAX_FILE_BYTES);
                }
                if (entry.size > 0 && hash.bytes != entry.size) throw new IllegalStateException("Incomplete legacy archive file");
                bytes = boundedTotal(bytes, hash.bytes);
                files.add(new RemoteFile(path, entry.uri, hash));
                progress.onProgress(Math.min(85, 4 + files.size()), "Staged explicitly unverified legacy recovery");
            }
            installStagedFiles(projectWorkspace, staging, files, false, progress);
            return new JSONObject().put("ok", true).put("verified", false).put("immutableGeneration", false)
                    .put("evictionEligible", false).put("manualRecovery", true).put("projectId", projectId)
                    .put("filesRestored", Math.max(0, files.size() - 1)).put("bytesRestored", bytes)
                    .put("galleryAccess", false);
        } finally {
            removeOwnedDirectory(staging);
        }
    }

    /** Immutable, hash-bound exchange in the selected project Requests/Results folder. */
    public synchronized JSONObject archiveArtifact(String projectId, String area, File file,
                                                   String sha256, Progress progress) throws Exception {
        projectId = requireName(projectId);
        area = requireArtifactArea(area);
        String expected = requireSha256(sha256);
        if (file == null || !file.isFile()) throw new IllegalArgumentException("Artifact file is missing");
        String name = requireName(file.getName());
        if (progress == null) progress = (p, d) -> {};
        Hash source = hashLocal(file, OpenPipelineArtifacts.MAX_ARCHIVE);
        if (!source.sha256.equals(expected) || source.bytes <= 0) throw new IllegalStateException("Artifact source checksum mismatch");
        Uri tree = requireTree();
        Uri projects = ensureDirectory(tree, rootDocumentUri(tree), "Projects");
        Uri project = ensureDirectory(tree, projects, projectId);
        Uri folder = ensureDirectory(tree, project, area);
        Uri existing = findChild(tree, folder, name, false);
        if (existing != null) {
            requireMatch(source, hashRemote(existing, OpenPipelineArtifacts.MAX_ARCHIVE), name);
            requireMatch(source, hashLocal(file, OpenPipelineArtifacts.MAX_ARCHIVE), name);
            progress.onProgress(100, "Existing immutable artifact checksum verified");
            return artifactReceipt(projectId, area, name, source);
        }
        String pendingName = ".pending_" + UUID.randomUUID();
        Uri pending = createOnly(tree, folder, pendingName, mimeFor(name));
        progress.onProgress(5, "Archiving hash-bound " + area + " artifact");
        try (InputStream in = new BufferedInputStream(new FileInputStream(file), BUFFER);
             OutputStream out = new BufferedOutputStream(requireOutput(pending), BUFFER)) {
            requireMatch(source, transferHash(in, out, OpenPipelineArtifacts.MAX_ARCHIVE), name);
        }
        requireMatch(source, hashRemote(pending, OpenPipelineArtifacts.MAX_ARCHIVE), name);
        requireMatch(source, hashLocal(file, OpenPipelineArtifacts.MAX_ARCHIVE), name);
        if (findChild(tree, folder, name, false) != null || findChild(tree, folder, name, true) != null) {
            throw new IllegalStateException("Artifact destination appeared during archive; existing data is preserved");
        }
        Uri committed = DocumentsContract.renameDocument(resolver, pending, name);
        if (committed == null || !name.equals(displayName(committed))) {
            throw new IllegalStateException("Provider could not publish immutable artifact name");
        }
        requireMatch(source, hashRemote(committed, OpenPipelineArtifacts.MAX_ARCHIVE), name);
        progress.onProgress(100, "Immutable artifact remotely checksum verified");
        return artifactReceipt(projectId, area, name, source);
    }

    public synchronized JSONObject restoreArtifact(String projectId, String area, String name,
                                                   File target, String sha256, Progress progress) throws Exception {
        projectId = requireName(projectId);
        area = requireArtifactArea(area);
        name = requireName(name);
        String expected = requireSha256(sha256);
        if (target == null) throw new IllegalArgumentException("Artifact destination is required");
        if (progress == null) progress = (p, d) -> {};
        Uri tree = requireTree();
        Uri folder = findChild(tree, findProject(tree, projectId), area, true);
        if (folder == null) throw new IllegalStateException("Project artifact area is missing");
        Uri artifact = findChild(tree, folder, name, false);
        if (artifact == null) throw new IllegalStateException("Project artifact is missing");
        File absolute = target.getCanonicalFile();
        ensureLocalDirectory(absolute.getParentFile());
        long advertisedBytes = documentSize(artifact);
        if (advertisedBytes > OpenPipelineArtifacts.MAX_ARCHIVE) {
            throw new IllegalStateException("Remote artifact exceeds the native archive size limit");
        }
        long transferLimit = advertisedBytes > 0 ? advertisedBytes : OpenPipelineArtifacts.MAX_ARCHIVE;
        StorageBudget.Check budget = StorageBudget.check(
                new StatFs(absolute.getParentFile().getAbsolutePath()).getAvailableBytes(),
                transferLimit, StorageBudget.DEFAULT_TRANSFER_RESERVE_BYTES);
        if (!budget.allowed) throw new IllegalStateException(
                "Artifact restore requires additional free storage; reserve shortfall " + budget.shortfallBytes + " bytes");
        File partial = new File(absolute.getParentFile(), ".artifact_" + UUID.randomUUID() + ".partial");
        try {
            progress.onProgress(5, "Restoring hash-bound " + area + " artifact");
            Hash hash;
            try (InputStream in = requireInput(artifact);
                 OutputStream out = new BufferedOutputStream(new FileOutputStream(partial), BUFFER)) {
                hash = transferHash(in, out, transferLimit);
            }
            if (hash.bytes <= 0 || (advertisedBytes >= 0 && hash.bytes != advertisedBytes)
                    || !expected.equals(hash.sha256)) {
                throw new IllegalStateException("Remote artifact checksum mismatch");
            }
            requireMatch(hash, hashLocal(partial, OpenPipelineArtifacts.MAX_ARCHIVE), name);
            progress.onProgress(95, "Artifact bytes verified before local commit");
            Files.move(partial.toPath(), absolute.toPath(), StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
            progress.onProgress(100, "Verified artifact restored");
            return artifactReceipt(projectId, area, name, hash).put("path", absolute.getAbsolutePath());
        } finally {
            if (partial.exists()) partial.delete();
        }
    }

    public JSONObject syncModelPack(String packId,
                                    File packDirectory,
                                    Progress progress) throws Exception {
        if (packId == null || packId.trim().isEmpty()) throw new IllegalArgumentException("Model pack id is required");
        if (packDirectory == null || !packDirectory.isDirectory()) {
            throw new IllegalArgumentException("Installed model pack directory is missing");
        }
        Uri tree = requireTree();
        if (progress == null) progress = (p, d) -> {};

        Uri root = rootDocumentUri(tree);
        Uri modelPacks = ensureDirectory(tree, root, "ModelPacks");
        Uri packDir = ensureDirectory(tree, modelPacks, safeName(packId));

        List<File> files = new ArrayList<>();
        collectAllFiles(packDirectory, files);
        long totalBytes = 0;
        for (File file : files) totalBytes += Math.max(0, file.length());
        long copied = 0;
        int count = 0;

        progress.onProgress(2, "Opening cloud model-pack archive");
        for (File file : files) {
            if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
            String relative = relativePath(packDirectory, file);
            Uri parent = ensureRelativeDirectories(tree, packDir, parentPath(relative));
            copyFile(tree, parent, leafName(relative), mimeFor(relative), file);
            copied += Math.max(0, file.length());
            count++;
            int p = totalBytes <= 0
                    ? Math.min(98, 4 + count)
                    : 4 + (int) Math.min(94, 94d * copied / totalBytes);
            progress.onProgress(p, "Archived model pack • " + count + " files • " + humanBytes(copied));
        }

        JSONObject out = new JSONObject();
        out.put("ok", true);
        out.put("id", packId);
        out.put("filesWritten", count);
        out.put("bytesWritten", copied);
        out.put("scope", "single-user-selected-document-tree");
        out.put("galleryAccess", false);
        progress.onProgress(100, "Model pack archived to linked cloud folder");
        return out;
    }

    public JSONObject restoreModelPack(String packId,
                                       File destination,
                                       Progress progress) throws Exception {
        if (packId == null || packId.trim().isEmpty()) throw new IllegalArgumentException("Model pack id is required");
        if (destination == null) throw new IllegalArgumentException("Model pack restore destination is required");
        Uri tree = requireTree();
        if (progress == null) progress = (p, d) -> {};

        Uri root = rootDocumentUri(tree);
        Uri modelPacks = findChild(tree, root, "ModelPacks", true);
        if (modelPacks == null) throw new IllegalStateException("Cloud ModelPacks archive does not exist");
        Uri packDir = findChild(tree, modelPacks, safeName(packId), true);
        if (packDir == null) throw new IllegalStateException("Cloud model pack was not found");

        List<RemoteEntry> remote = new ArrayList<>();
        collectRemoteFiles(tree, packDir, "", remote, 0);
        long totalBytes = 0;
        for (RemoteEntry entry : remote) totalBytes += Math.max(0, entry.size);

        long copied = 0;
        int files = 0;
        progress.onProgress(2, "Restoring cloud model pack to protected staging");
        for (RemoteEntry entry : remote) {
            if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
            File target = safeTarget(destination, entry.relative);
            File parent = target.getParentFile();
            if (parent != null && !parent.exists() && !parent.mkdirs() && !parent.exists()) {
                throw new IllegalStateException("Could not create model-pack staging directory");
            }
            try (InputStream in = new BufferedInputStream(requireInput(entry.uri), BUFFER);
                 OutputStream out = new BufferedOutputStream(new FileOutputStream(target), BUFFER)) {
                copy(in, out);
            }
            copied += Math.max(0, entry.size);
            files++;
            int p = totalBytes <= 0
                    ? Math.min(98, 4 + files)
                    : 4 + (int) Math.min(94, 94d * copied / totalBytes);
            progress.onProgress(p, "Restored model pack • " + files + " files • " + humanBytes(copied));
        }

        JSONObject out = new JSONObject();
        out.put("ok", true);
        out.put("id", packId);
        out.put("filesRestored", files);
        out.put("bytesRestored", copied);
        out.put("path", destination.getAbsolutePath());
        out.put("galleryAccess", false);
        progress.onProgress(100, "Cloud model pack restored to protected staging");
        return out;
    }

    private List<ArchiveSource> projectSources(ProjectStore.Project project, File workspace,
                                               byte[] projectBytes) throws Exception {
        Map<String, ArchiveSource> sources = new LinkedHashMap<>();
        addSource(sources, new ArchiveSource("project.json", "application/json", "project", null, null, projectBytes, -1));
        if (workspace != null) {
            if (!workspace.isDirectory()) throw new IllegalStateException("Project workspace is missing");
            List<File> files = new ArrayList<>();
            collectBoundedArchiveFiles(workspace, workspace, files, 0, new HashSet<>());
            Collections.sort(files, (a, b) -> a.getAbsolutePath().compareTo(b.getAbsolutePath()));
            for (File file : files) {
                String path = requireRelativePath(relativePath(workspace, file));
                addSource(sources, new ArchiveSource(path, mimeFor(path), "workspace", file, null, null, file.length()));
            }
        }
        String importRoot = new File(context.getFilesDir(), "imports").getCanonicalPath() + File.separator;
        String workspaceRoot = workspace == null ? "" : workspace.getCanonicalPath() + File.separator;
        for (ProjectStore.Asset asset : project.assets) {
            if (asset.generated) continue;
            if (asset.uri == null || asset.uri.trim().isEmpty()) {
                throw new IllegalStateException("Original asset URI is missing: " + asset.name);
            }
            Uri uri = Uri.parse(asset.uri);
            File local = null;
            if ("file".equals(uri.getScheme())) {
                if (uri.getPath() == null) throw new IllegalStateException("Original asset path is invalid");
                local = new File(uri.getPath()).getCanonicalFile();
                String absolute = local.getPath();
                if (!workspaceRoot.isEmpty() && absolute.startsWith(workspaceRoot)) {
                    String relative = requireRelativePath(relativePath(workspace, local));
                    if (sources.containsKey(relative)) {
                        if (!local.isFile() || local.length() <= 0
                                || (asset.sizeBytes > 0 && local.length() != asset.sizeBytes)) {
                            throw new IllegalStateException("Original workspace asset is missing or changed: " + asset.name);
                        }
                        continue;
                    }
                } else if (!absolute.startsWith(importRoot)) {
                    throw new IllegalStateException("Original asset is outside the project-owned import scope: " + asset.name);
                }
                if (!local.isFile()) throw new IllegalStateException("Original asset is missing: " + asset.name);
            } else if (!"content".equals(uri.getScheme()) || !asset.persistedReadAccess) {
                throw new IllegalStateException("Original asset read capability is unavailable: " + asset.name);
            }
            String name = requireName(safeName(asset.id + "_" + asset.name));
            addSource(sources, new ArchiveSource("Originals/" + name, asset.mime, "original",
                    local, local == null ? uri : null, null, asset.sizeBytes > 0 ? asset.sizeBytes : -1));
        }
        boolean hasExport = project.latestExportUri != null && !project.latestExportUri.isEmpty();
        if (hasExport) {
            if (!isProjectPublishedMp4(project)) {
                throw new IllegalStateException("Published export is not bound to a VideoStudio final-render asset");
            }
            long expectedBytes = -1;
            for (ProjectStore.Asset asset : project.assets) {
                if (asset.generated && "final_render".equals(asset.role)
                        && project.latestExportUri.equals(asset.uri)) expectedBytes = asset.sizeBytes > 0 ? asset.sizeBytes : -1;
            }
            String name = requireName(safeName(project.latestExportName));
            addSource(sources, new ArchiveSource("Exports/" + name, "video/mp4", "final_export",
                    null, Uri.parse(project.latestExportUri), null, expectedBytes));
        }
        return new ArrayList<>(sources.values());
    }

    private static void addSource(Map<String, ArchiveSource> sources, ArchiveSource source) {
        if (sources.size() >= MAX_REMOTE_ENTRIES) throw new IllegalStateException("Project archive contains too many files");
        if (sources.put(source.path, source) != null) throw new IllegalStateException("Archive paths collide: " + source.path);
    }

    private static Set<String> sourcePaths(List<ArchiveSource> sources) {
        Set<String> paths = new HashSet<>();
        for (ArchiveSource source : sources) paths.add(source.path);
        return paths;
    }

    private void collectBoundedArchiveFiles(File root, File directory, List<File> files, int depth,
                                            Set<String> visited) throws Exception {
        if (depth > MAX_REMOTE_DEPTH) throw new IllegalStateException("Project workspace nesting is too deep");
        String canonical = directory.getCanonicalPath();
        if ((!directory.equals(root) && !canonical.startsWith(root.getCanonicalPath() + File.separator))
                || !visited.add(canonical)) throw new IllegalStateException("Project workspace has an unsafe directory link");
        File[] children = directory.listFiles();
        if (children == null) throw new IllegalStateException("Project workspace directory cannot be read");
        for (File child : children) {
            if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
            String name = child.getName();
            if ("temp".equals(name) || "previews".equals(name)) continue;
            if (child.isDirectory()) collectBoundedArchiveFiles(root, child, files, depth + 1, visited);
            else if (child.isFile()) {
                requireRelativePath(relativePath(root, child));
                if (files.size() >= MAX_REMOTE_ENTRIES) throw new IllegalStateException("Project archive contains too many files");
                files.add(child);
            } else throw new IllegalStateException("Project workspace contains an unreadable item");
        }
    }

    private void requireProjectRevision(ProjectStore.Project supplied, String expected) throws Exception {
        if (!expected.equals(sha256(supplied.toJson().toString().getBytes(StandardCharsets.UTF_8)))) {
            throw new IllegalStateException("Project changed while archiving");
        }
        ProjectStore.Project current = new ProjectStore(context).get(supplied.id);
        if (current == null || !expected.equals(sha256(current.toJson().toString().getBytes(StandardCharsets.UTF_8)))) {
            throw new IllegalStateException("Stored project revision changed while archiving");
        }
    }

    private InputStream openSource(ArchiveSource source) throws Exception {
        if (source.bytes != null) return new ByteArrayInputStream(source.bytes);
        if (source.file != null) return new BufferedInputStream(new FileInputStream(source.file), BUFFER);
        return new BufferedInputStream(requireInput(source.uri), BUFFER);
    }

    private Hash hashSource(ArchiveSource source) throws Exception {
        long length = source.file == null ? -1 : source.file.length();
        long modified = source.file == null ? -1 : source.file.lastModified();
        Hash hash;
        try (InputStream in = openSource(source)) { hash = transferHash(in, null, MAX_FILE_BYTES); }
        if ((source.expectedBytes >= 0 && source.expectedBytes != hash.bytes)
                || (source.file != null && (length != source.file.length() || modified != source.file.lastModified()))
                || (("original".equals(source.kind) || "final_export".equals(source.kind)) && hash.bytes <= 0)) {
            throw new IllegalStateException("Archive source is incomplete or changed: " + source.path);
        }
        return hash;
    }

    private Hash hashRemote(Uri uri, long limit) throws Exception {
        try (InputStream in = requireInput(uri)) { return transferHash(in, null, limit); }
    }

    private static Hash hashLocal(File file) throws Exception {
        return hashLocal(file, MAX_FILE_BYTES);
    }

    private static Hash hashLocal(File file, long limit) throws Exception {
        if (!file.isFile()) throw new IllegalStateException("Local archive file is missing");
        long length = file.length();
        if (length > limit) throw new IllegalStateException("Local archive file exceeds the bounded size limit");
        long modified = file.lastModified();
        Hash hash;
        try (InputStream in = new BufferedInputStream(new FileInputStream(file), BUFFER)) {
            hash = transferHash(in, null, limit);
        }
        if (length != file.length() || modified != file.lastModified() || length != hash.bytes) {
            throw new IllegalStateException("Local archive file changed while hashing");
        }
        return hash;
    }

    private long documentSize(Uri uri) throws Exception {
        try (Cursor cursor = resolver.query(uri,
                new String[]{DocumentsContract.Document.COLUMN_SIZE}, null, null, null)) {
            if (cursor == null || !cursor.moveToFirst() || cursor.isNull(0)) return -1;
            long size = cursor.getLong(0);
            return size < 0 ? -1 : size;
        }
    }

    private static Hash transferHash(InputStream in, OutputStream out, long limit) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] buffer = new byte[BUFFER];
        long bytes = 0;
        int count;
        while ((count = in.read(buffer)) != -1) {
            if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
            if (count == 0) continue;
            if (count > limit - bytes) throw new IllegalStateException("Archive file exceeds the bounded size limit");
            digest.update(buffer, 0, count);
            if (out != null) out.write(buffer, 0, count);
            bytes += count;
        }
        if (out != null) out.flush();
        return new Hash(bytes, hex(digest.digest()));
    }

    private static String sha256(byte[] bytes) throws Exception {
        return hex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    private static String hex(byte[] bytes) {
        StringBuilder out = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) out.append(String.format(Locale.US, "%02x", b & 0xff));
        return out.toString();
    }

    private static void requireMatch(Hash expected, Hash actual, String path) {
        if (expected.bytes != actual.bytes || !expected.sha256.equals(actual.sha256)) {
            throw new IllegalStateException("Archive checksum/length mismatch: " + path);
        }
    }

    private static long boundedTotal(long total, long bytes) {
        if (bytes < 0 || bytes > MAX_GENERATION_BYTES - total) throw new IllegalStateException("Project archive exceeds the bounded size limit");
        return total + bytes;
    }

    private static String requireName(String name) {
        if (name == null || name.isEmpty() || !name.equals(safeName(name))
                || ".".equals(name) || "..".equals(name)) throw new IllegalArgumentException("Unsafe archive name");
        return name;
    }

    private static String requireRelativePath(String path) {
        if (path == null || path.isEmpty() || path.startsWith("/") || path.contains("\\")) {
            throw new IllegalArgumentException("Unsafe archive path");
        }
        String[] parts = path.split("/", -1);
        if (parts.length > MAX_REMOTE_DEPTH + 1) throw new IllegalArgumentException("Archive path is too deep");
        for (String part : parts) requireName(part);
        return path;
    }

    private static String requireSha256(String value) {
        String hash = value == null ? "" : value.toLowerCase(Locale.US);
        if (!hash.matches("[0-9a-f]{64}")) throw new IllegalArgumentException("A SHA-256 artifact checksum is required");
        return hash;
    }

    private static String requireArtifactArea(String area) {
        if (!"Requests".equals(area) && !"Results".equals(area)) {
            throw new IllegalArgumentException("Artifact area must be Requests or Results");
        }
        return area;
    }

    private static JSONObject artifactReceipt(String projectId, String area, String name, Hash hash) throws Exception {
        return new JSONObject().put("ok", true).put("verified", true).put("immutable", true)
                .put("projectId", projectId).put("area", area).put("name", name)
                .put("sha256", hash.sha256).put("bytes", hash.bytes)
                .put("archivePath", "Projects/" + projectId + "/" + area + "/" + name)
                .put("scope", "single-user-selected-document-tree").put("galleryAccess", false);
    }

    private Uri createOnly(Uri tree, Uri parent, String name, String mime) throws Exception {
        name = requireName(name);
        if (findChild(tree, parent, name, false) != null || findChild(tree, parent, name, true) != null) {
            throw new IllegalStateException("Immutable archive destination already exists: " + name);
        }
        Uri created = DocumentsContract.createDocument(resolver, parent,
                mime == null || mime.isEmpty() ? "application/octet-stream" : mime, name);
        if (created == null || !name.equals(displayName(created))) {
            throw new IllegalStateException("Provider did not create the exact immutable archive name: " + name);
        }
        return created;
    }

    private Uri ensureStrictRelativeDirectories(Uri tree, Uri base, String path) throws Exception {
        Uri current = base;
        if (path.isEmpty()) return current;
        for (String name : requireRelativePath(path).split("/")) current = ensureDirectory(tree, current, name);
        return current;
    }

    private void writeVerifiedBytes(Uri tree, Uri folder, String name, byte[] bytes) throws Exception {
        Uri destination = createOnly(tree, folder, name, "application/json");
        Hash expected = new Hash(bytes.length, sha256(bytes));
        try (InputStream in = new ByteArrayInputStream(bytes);
             OutputStream out = new BufferedOutputStream(requireOutput(destination), BUFFER)) {
            requireMatch(expected, transferHash(in, out, MAX_MANIFEST_BYTES), name);
        }
        requireMatch(expected, hashRemote(destination, MAX_MANIFEST_BYTES), name);
    }

    private Uri findProject(Uri tree, String projectId) throws Exception {
        Uri projects = findChild(tree, rootDocumentUri(tree), "Projects", true);
        if (projects == null) throw new IllegalStateException("Cloud Projects archive does not exist");
        Uri project = findChild(tree, projects, projectId, true);
        if (project == null) throw new IllegalStateException("Cloud archive for project was not found");
        return project;
    }

    private byte[] readBounded(Uri uri) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (InputStream in = requireInput(uri)) { transferHash(in, out, MAX_MANIFEST_BYTES); }
        return out.toByteArray();
    }

    private List<Generation> committedGenerations(Uri tree, Uri project, String projectId) throws Exception {
        List<Generation> result = new ArrayList<>();
        Uri generations = findChild(tree, project, "Generations", true);
        if (generations == null) return result;
        Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, DocumentsContract.getDocumentId(generations));
        String[] columns = { DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_MIME_TYPE };
        try (Cursor cursor = resolver.query(children, columns, null, null, null)) {
            if (cursor == null) throw new IllegalStateException("Cannot enumerate cloud generations");
            int seen = 0;
            while (cursor.moveToNext()) {
                if (++seen > MAX_REMOTE_ENTRIES) throw new IllegalStateException("Cloud archive contains too many generations");
                if (!DocumentsContract.Document.MIME_TYPE_DIR.equals(cursor.getString(2))) continue;
                String id = cursor.getString(1);
                try {
                    if (!UUID.fromString(id).toString().equals(id)) continue;
                    Uri folder = DocumentsContract.buildDocumentUriUsingTree(tree, cursor.getString(0));
                    Uri receipt = findChild(tree, folder, "commit.json", false);
                    if (receipt == null) continue;
                    byte[] bytes = readBounded(receipt);
                    JSONObject commit = new JSONObject(new String(bytes, StandardCharsets.UTF_8));
                    if (!GENERATION_SCHEMA.equals(commit.optString("schema")) || !projectId.equals(commit.optString("projectId"))
                            || !id.equals(commit.optString("generationId")) || commit.optLong("completedAt") <= 0
                            || !commit.has("sourceProjectUpdatedAt")) continue;
                    String projectHash = requireSha256(commit.getString("sourceProjectSha256"));
                    JSONArray descriptors = commit.getJSONArray("files");
                    if (descriptors.length() == 0 || descriptors.length() > MAX_REMOTE_ENTRIES) continue;
                    List<RemoteFile> files = new ArrayList<>();
                    Set<String> paths = new HashSet<>();
                    long total = 0;
                    boolean hasProject = false;
                    for (int i = 0; i < descriptors.length(); i++) {
                        JSONObject item = descriptors.getJSONObject(i);
                        String path = requireRelativePath(item.getString("path"));
                        long size = item.getLong("bytes");
                        String hash = requireSha256(item.getString("sha256"));
                        if ("commit.json".equals(path) || !paths.add(path) || size < 0 || size > MAX_FILE_BYTES) {
                            throw new IllegalStateException("Invalid generation file descriptor");
                        }
                        total = boundedTotal(total, size);
                        if ("project.json".equals(path)) {
                            if (!projectHash.equals(hash) || size > MAX_MANIFEST_BYTES) throw new IllegalStateException("Project manifest binding mismatch");
                            hasProject = true;
                        }
                        files.add(new RemoteFile(path, null, new Hash(size, hash)));
                    }
                    if (hasProject) result.add(new Generation(id, folder, commit, sha256(bytes), files));
                } catch (InterruptedException stop) { throw stop; }
                  catch (Exception incomplete) { /* Incomplete generations are not committed archives. */ }
            }
        }
        return result;
    }

    private Uri findRelativeFile(Uri tree, Uri folder, String path) throws Exception {
        String[] parts = requireRelativePath(path).split("/");
        Uri current = folder;
        for (int i = 0; i < parts.length; i++) {
            current = findChild(tree, current, parts[i], i < parts.length - 1);
            if (current == null) throw new IllegalStateException("Committed generation file is missing: " + path);
        }
        return current;
    }

    private long stageGeneration(Uri tree, Generation generation, File staging, Progress progress) throws Exception {
        long bytes = 0;
        for (int i = 0; i < generation.files.size(); i++) {
            RemoteFile file = generation.files.get(i);
            Uri source = findRelativeFile(tree, generation.folder, file.path);
            File target = safeTarget(staging, file.path);
            ensureLocalDirectory(target.getParentFile());
            try (InputStream in = requireInput(source);
                 OutputStream out = new BufferedOutputStream(new FileOutputStream(target), BUFFER)) {
                requireMatch(file.hash, transferHash(in, out, file.hash.bytes), file.path);
            }
            requireMatch(file.hash, hashLocal(target), file.path);
            bytes = boundedTotal(bytes, file.hash.bytes);
            progress.onProgress(3 + (int) (82d * (i + 1) / generation.files.size()),
                    "Checksum verified " + (i + 1) + "/" + generation.files.size() + " cloud files");
        }
        JSONObject project = new JSONObject(new String(Files.readAllBytes(safeTarget(staging, "project.json").toPath()), StandardCharsets.UTF_8));
        if (!generation.commit.getString("projectId").equals(project.optString("id"))
                || generation.commit.getLong("sourceProjectUpdatedAt") != project.optLong("updatedAt", -1)) {
            throw new IllegalStateException("Generation source-project identity/revision mismatch");
        }
        // Check the commit itself again after all files; a remote mutation is
        // not allowed to redefine the generation during this restore.
        Uri receipt = findChild(tree, generation.folder, "commit.json", false);
        if (receipt == null || !generation.manifestSha256.equals(sha256(readBounded(receipt)))) {
            throw new IllegalStateException("Generation commit changed during restore");
        }
        return bytes;
    }

    private static boolean restoreFile(String path, boolean preserveControl) {
        return !"project.json".equals(path) && !(preserveControl && (path.startsWith("checkpoints/")
                || path.startsWith("scenes/") || "cloud_offload.json".equals(path)));
    }

    private static void ensureLocalDirectory(File directory) {
        if (directory == null || (!directory.isDirectory() && !directory.mkdirs() && !directory.isDirectory())) {
            throw new IllegalStateException("Could not create protected restore directory");
        }
    }

    private static void installStagedFiles(File workspace, File staging, List<RemoteFile> files,
                                          boolean preserveControl, Progress progress) throws Exception {
        List<LocalReplacement> changes = new ArrayList<>();
        File backup = new File(staging.getParentFile(), ".drive_backup_" + UUID.randomUUID());
        ensureLocalDirectory(backup);
        boolean discardBackup = false;
        try {
            // Prepare and validate every target before moving any existing data.
            for (RemoteFile file : files) {
                if (!restoreFile(file.path, preserveControl)) continue;
                File target = safeTarget(workspace, file.path);
                if (target.exists() && !target.isFile()) throw new IllegalStateException("Restore target is not a regular file");
                Hash prior = target.exists() ? hashLocal(target) : null;
                changes.add(new LocalReplacement(target, safeTarget(staging, file.path),
                        safeTarget(backup, file.path), prior));
            }
            for (int i = 0; i < changes.size(); i++) {
                if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
                LocalReplacement change = changes.get(i);
                ensureLocalDirectory(change.target.getParentFile());
                if (change.prior != null) {
                    requireMatch(change.prior, hashLocal(change.target), change.target.getName());
                    ensureLocalDirectory(change.backup.getParentFile());
                    Files.move(change.target.toPath(), change.backup.toPath(), StandardCopyOption.ATOMIC_MOVE);
                    change.backedUp = true;
                } else if (change.target.exists()) throw new IllegalStateException("Local file appeared during restore");
                Files.move(change.staged.toPath(), change.target.toPath(), StandardCopyOption.ATOMIC_MOVE);
                change.installed = true;
                progress.onProgress(86 + (int) (13d * (i + 1) / Math.max(1, changes.size())),
                        "Committed verified workspace file " + (i + 1) + "/" + changes.size());
            }
            discardBackup = true;
        } catch (Exception failed) {
            boolean rolledBack = true;
            for (int i = changes.size() - 1; i >= 0; i--) {
                LocalReplacement change = changes.get(i);
                try {
                    if (change.installed) Files.move(change.target.toPath(), change.staged.toPath(), StandardCopyOption.ATOMIC_MOVE);
                    if (change.backedUp) Files.move(change.backup.toPath(), change.target.toPath(), StandardCopyOption.ATOMIC_MOVE);
                } catch (Exception rollbackFailure) {
                    failed.addSuppressed(rollbackFailure);
                    rolledBack = false;
                }
            }
            discardBackup = rolledBack;
            if (failed instanceof InterruptedException && rolledBack) throw failed;
            throw new LocalRestoreFailure(rolledBack ? "Local restore failed; existing files were rolled back"
                    : "Local restore could not roll back every file; preserved originals remain in " + backup.getAbsolutePath(), failed);
        } finally {
            if (discardBackup) removeOwnedDirectory(backup);
        }
    }

    private static void removeOwnedDirectory(File directory) {
        File[] children = directory.listFiles();
        if (children != null) for (File child : children) {
            if (child.isDirectory()) removeOwnedDirectory(child);
            else child.delete();
        }
        directory.delete();
    }

    private static final class Hash {
        final long bytes;
        final String sha256;
        Hash(long bytes, String sha256) { this.bytes = bytes; this.sha256 = sha256; }
    }

    private static final class ArchiveSource {
        final String path, mime, kind;
        final File file;
        final Uri uri;
        final byte[] bytes;
        final long expectedBytes;
        Hash snapshot;
        ArchiveSource(String path, String mime, String kind, File file, Uri uri, byte[] bytes, long expectedBytes) {
            this.path = path; this.mime = mime; this.kind = kind; this.file = file;
            this.uri = uri; this.bytes = bytes; this.expectedBytes = expectedBytes;
        }
    }

    private static final class RemoteFile {
        final String path;
        final Uri uri;
        final Hash hash;
        RemoteFile(String path, Uri uri, Hash hash) { this.path = path; this.uri = uri; this.hash = hash; }
    }

    private static final class Generation {
        final String id, manifestSha256;
        final Uri folder;
        final JSONObject commit;
        final List<RemoteFile> files;
        Generation(String id, Uri folder, JSONObject commit, String manifestSha256, List<RemoteFile> files) {
            this.id = id; this.folder = folder; this.commit = commit;
            this.manifestSha256 = manifestSha256; this.files = files;
        }
    }

    private static final class LocalReplacement {
        final File target, staged, backup;
        final Hash prior;
        boolean backedUp, installed;
        LocalReplacement(File target, File staged, File backup, Hash prior) {
            this.target = target; this.staged = staged; this.backup = backup; this.prior = prior;
        }
    }

    private static final class LocalRestoreFailure extends Exception {
        LocalRestoreFailure(String message, Exception cause) { super(message, cause); }
    }

    private JSONObject inventoryArea(Uri tree, Uri root, String name) throws Exception {
        JSONObject out = new JSONObject();
        out.put("name", name);
        Uri area = findChild(tree, root, name, true);
        if (area == null) {
            out.put("present", false);
            out.put("files", 0);
            out.put("directories", 0);
            out.put("bytes", 0);
            return out;
        }
        RemoteStats stats = scanRemoteStats(tree, area, 0, new int[]{0});
        out.put("present", true);
        out.put("files", stats.files);
        out.put("directories", stats.directories);
        out.put("bytes", stats.bytes);
        out.put("truncated", stats.truncated);
        return out;
    }

    private RemoteStats scanRemoteStats(Uri tree, Uri directory, int depth, int[] visited) throws Exception {
        RemoteStats stats = new RemoteStats();
        if (depth > MAX_REMOTE_DEPTH) {
            stats.truncated = true;
            return stats;
        }
        String parentId = DocumentsContract.getDocumentId(directory);
        Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parentId);
        String[] projection = {
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_MIME_TYPE,
                DocumentsContract.Document.COLUMN_SIZE
        };
        try (Cursor cursor = resolver.query(children, projection, null, null, null)) {
            if (cursor == null) return stats;
            int idIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID);
            int mimeIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE);
            int sizeIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_SIZE);
            while (cursor.moveToNext()) {
                if (++visited[0] > MAX_REMOTE_ENTRIES) {
                    stats.truncated = true;
                    break;
                }
                String id = cursor.getString(idIndex);
                String mime = mimeIndex >= 0 ? cursor.getString(mimeIndex) : "";
                Uri child = DocumentsContract.buildDocumentUriUsingTree(tree, id);
                if (DocumentsContract.Document.MIME_TYPE_DIR.equals(mime)) {
                    stats.directories++;
                    RemoteStats nested = scanRemoteStats(tree, child, depth + 1, visited);
                    stats.add(nested);
                    if (nested.truncated) stats.truncated = true;
                } else {
                    stats.files++;
                    stats.bytes += sizeIndex >= 0 && !cursor.isNull(sizeIndex)
                            ? Math.max(0, cursor.getLong(sizeIndex)) : 0;
                }
            }
        }
        return stats;
    }

    private void collectRemoteFiles(Uri tree,
                                    Uri directory,
                                    String prefix,
                                    List<RemoteEntry> out,
                                    int depth) throws Exception {
        if (depth > MAX_REMOTE_DEPTH) throw new IllegalStateException("Cloud archive nesting is too deep");
        if (out.size() >= MAX_REMOTE_ENTRIES) throw new IllegalStateException("Cloud archive contains too many files");

        String parentId = DocumentsContract.getDocumentId(directory);
        Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parentId);
        String[] projection = {
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE,
                DocumentsContract.Document.COLUMN_SIZE
        };
        try (Cursor cursor = resolver.query(children, projection, null, null, null)) {
            if (cursor == null) return;
            int idIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID);
            int nameIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME);
            int mimeIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE);
            int sizeIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_SIZE);
            while (cursor.moveToNext()) {
                String id = cursor.getString(idIndex);
                String name = safeName(nameIndex >= 0 ? cursor.getString(nameIndex) : "item");
                String mime = mimeIndex >= 0 ? cursor.getString(mimeIndex) : "";
                Uri child = DocumentsContract.buildDocumentUriUsingTree(tree, id);
                String relative = prefix.isEmpty() ? name : prefix + "/" + name;
                if (DocumentsContract.Document.MIME_TYPE_DIR.equals(mime)) {
                    collectRemoteFiles(tree, child, relative, out, depth + 1);
                } else {
                    long size = sizeIndex >= 0 && !cursor.isNull(sizeIndex)
                            ? Math.max(0, cursor.getLong(sizeIndex)) : 0;
                    out.add(new RemoteEntry(child, relative, size));
                    if (out.size() > MAX_REMOTE_ENTRIES) {
                        throw new IllegalStateException("Cloud archive contains too many files");
                    }
                }
            }
        }
    }

    private void collectAllFiles(File root, List<File> out) {
        File[] children = root.listFiles();
        if (children == null) return;
        for (File child : children) {
            if (child.isDirectory()) collectAllFiles(child, out);
            else if (child.isFile()) out.add(child);
        }
    }

    private InputStream requireInput(Uri uri) throws Exception {
        InputStream in = resolver.openInputStream(uri);
        if (in == null) throw new IllegalStateException("Could not open cloud input stream");
        return in;
    }

    private static File safeTarget(File root, String relative) throws Exception {
        if (relative == null || relative.trim().isEmpty()) throw new IllegalArgumentException("Cloud archive path is empty");
        File target = new File(root, relative.replace('/', File.separatorChar));
        String rootPath = root.getCanonicalPath() + File.separator;
        String targetPath = target.getCanonicalPath();
        if (!targetPath.startsWith(rootPath)) {
            throw new IllegalArgumentException("Cloud archive attempted to escape local workspace");
        }
        return target;
    }

    private static final class RemoteEntry {
        final Uri uri;
        final String relative;
        final long size;

        RemoteEntry(Uri uri, String relative, long size) {
            this.uri = uri;
            this.relative = relative;
            this.size = size;
        }
    }

    private static final class RemoteStats {
        long bytes;
        int files;
        int directories;
        boolean truncated;

        void add(RemoteStats other) {
            if (other == null) return;
            bytes += other.bytes;
            files += other.files;
            directories += other.directories;
            truncated |= other.truncated;
        }
    }

    private Uri requireTree() {
        Uri uri = treeUri();
        if (uri == null) throw new IllegalStateException("No cloud workspace folder is linked");
        if (!hasPersistedPermission(uri)) {
            throw new IllegalStateException("Linked cloud workspace permission is no longer available");
        }
        if (!canReadRoot(uri)) {
            throw new IllegalStateException("Linked cloud workspace is currently unavailable");
        }
        return uri;
    }

    private Uri treeUri() {
        String raw = prefs.getString(KEY_TREE, "");
        if (raw == null || raw.trim().isEmpty()) return null;
        try { return Uri.parse(raw); }
        catch (Exception ignored) { return null; }
    }

    private boolean hasPersistedPermission(Uri tree) {
        for (android.content.UriPermission permission : resolver.getPersistedUriPermissions()) {
            if (permission.getUri() != null
                    && permission.getUri().equals(tree)
                    && permission.isReadPermission()
                    && permission.isWritePermission()) {
                return true;
            }
        }
        return false;
    }

    private boolean canReadRoot(Uri tree) {
        try {
            Uri root = rootDocumentUri(tree);
            try (Cursor cursor = resolver.query(
                    root,
                    new String[]{DocumentsContract.Document.COLUMN_DOCUMENT_ID},
                    null, null, null)) {
                return cursor != null && cursor.moveToFirst();
            }
        } catch (Exception ignored) {
            return false;
        }
    }

    private Uri rootDocumentUri(Uri tree) {
        String docId = DocumentsContract.getTreeDocumentId(tree);
        return DocumentsContract.buildDocumentUriUsingTree(tree, docId);
    }

    private Uri ensureRelativeDirectories(Uri tree, Uri base, String path) throws Exception {
        Uri current = base;
        if (path == null || path.isEmpty()) return current;
        String[] parts = path.split("/");
        for (String part : parts) {
            if (part == null || part.trim().isEmpty()) continue;
            current = ensureDirectory(tree, current, safeName(part));
        }
        return current;
    }

    private Uri ensureDirectory(Uri tree, Uri parent, String name) throws Exception {
        Uri existing = findChild(tree, parent, name, true);
        if (existing != null) return existing;
        Uri created = DocumentsContract.createDocument(
                resolver,
                parent,
                DocumentsContract.Document.MIME_TYPE_DIR,
                name
        );
        if (created == null) throw new IllegalStateException("Could not create cloud directory: " + name);
        if (!name.equals(displayName(created))) throw new IllegalStateException("Provider changed the cloud directory name");
        return created;
    }

    private void writeBytes(Uri tree,
                            Uri parent,
                            String name,
                            String mime,
                            byte[] bytes) throws Exception {
        Uri doc = ensureFile(tree, parent, name, mime);
        try (InputStream in = new ByteArrayInputStream(bytes);
             OutputStream out = new BufferedOutputStream(requireOutput(doc), BUFFER)) {
            copy(in, out);
        }
    }

    private void copyFile(Uri tree,
                          Uri parent,
                          String name,
                          String mime,
                          File source) throws Exception {
        Uri doc = ensureFile(tree, parent, name, mime);
        try (InputStream in = new BufferedInputStream(new FileInputStream(source), BUFFER);
             OutputStream out = new BufferedOutputStream(requireOutput(doc), BUFFER)) {
            copy(in, out);
        }
    }

    private Uri ensureFile(Uri tree, Uri parent, String name, String mime) throws Exception {
        Uri existing = findChild(tree, parent, name, false);
        if (existing != null) return existing;
        Uri created = DocumentsContract.createDocument(
                resolver,
                parent,
                mime == null || mime.isEmpty() ? "application/octet-stream" : mime,
                name
        );
        if (created == null) throw new IllegalStateException("Could not create cloud file: " + name);
        return created;
    }

    private Uri findChild(Uri tree, Uri parent, String name, boolean directory) throws Exception {
        String parentId = DocumentsContract.getDocumentId(parent);
        Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parentId);
        String[] projection = {
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE
        };
        try (Cursor cursor = resolver.query(children, projection, null, null, null)) {
            if (cursor == null) throw new IllegalStateException("Could not enumerate cloud directory");
            int idIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID);
            int nameIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME);
            int mimeIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE);
            int visited = 0;
            Uri found = null;
            while (cursor.moveToNext()) {
                if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
                if (++visited > MAX_REMOTE_ENTRIES) throw new IllegalStateException("Cloud directory contains too many entries");
                String childName = nameIndex >= 0 ? cursor.getString(nameIndex) : "";
                String childMime = mimeIndex >= 0 ? cursor.getString(mimeIndex) : "";
                boolean isDir = DocumentsContract.Document.MIME_TYPE_DIR.equals(childMime);
                if (name.equals(childName) && isDir == directory) {
                    if (found != null) throw new IllegalStateException("Cloud directory has ambiguous duplicate names: " + name);
                    String childId = cursor.getString(idIndex);
                    found = DocumentsContract.buildDocumentUriUsingTree(tree, childId);
                }
            }
            return found;
        }
    }

    private OutputStream requireOutput(Uri uri) throws Exception {
        OutputStream out = resolver.openOutputStream(uri, "w");
        if (out == null) throw new IllegalStateException("Could not open cloud output stream");
        return out;
    }

    private void collectArchiveFiles(File root, List<File> out) {
        File[] children = root.listFiles();
        if (children == null) return;
        for (File child : children) {
            String name = child.getName();
            if ("temp".equals(name) || "previews".equals(name)) continue;
            if (child.isDirectory()) collectArchiveFiles(child, out);
            else if (child.isFile()) out.add(child);
        }
    }

    private static String relativePath(File root, File file) throws Exception {
        String rootPath = root.getCanonicalPath();
        String filePath = file.getCanonicalPath();
        if (!filePath.startsWith(rootPath + File.separator)) {
            throw new IllegalArgumentException("Workspace file escaped project root");
        }
        return filePath.substring(rootPath.length() + 1).replace(File.separatorChar, '/');
    }

    private static String parentPath(String relative) {
        int slash = relative.lastIndexOf('/');
        return slash < 0 ? "" : relative.substring(0, slash);
    }

    private static String leafName(String relative) {
        int slash = relative.lastIndexOf('/');
        return slash < 0 ? relative : relative.substring(slash + 1);
    }

    private static String mimeFor(String name) {
        String n = name == null ? "" : name.toLowerCase();
        if (n.endsWith(".json")) return "application/json";
        if (n.endsWith(".png")) return "image/png";
        if (n.endsWith(".jpg") || n.endsWith(".jpeg")) return "image/jpeg";
        if (n.endsWith(".webp")) return "image/webp";
        if (n.endsWith(".mp4")) return "video/mp4";
        if (n.endsWith(".wav")) return "audio/wav";
        if (n.endsWith(".mp3")) return "audio/mpeg";
        if (n.endsWith(".glb")) return "model/gltf-binary";
        if (n.endsWith(".gltf")) return "model/gltf+json";
        if (n.endsWith(".txt") || n.endsWith(".ms")) return "text/plain";
        return "application/octet-stream";
    }

    private String displayName(Uri uri) {
        try (Cursor cursor = resolver.query(
                uri,
                new String[]{DocumentsContract.Document.COLUMN_DISPLAY_NAME},
                null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) return cursor.getString(0);
        } catch (Exception ignored) {}
        return "Linked workspace";
    }

    private static void copy(InputStream in, OutputStream out) throws Exception {
        byte[] buffer = new byte[BUFFER];
        int n;
        while ((n = in.read(buffer)) >= 0) {
            if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
            out.write(buffer, 0, n);
        }
        out.flush();
    }

    private static String safeName(String raw) {
        String value = raw == null ? "item" : raw.trim();
        if (value.isEmpty()) value = "item";
        value = value.replaceAll("[\\/:*?\"<>|\\p{Cntrl}]+", "_");
        if (value.length() > 120) value = value.substring(0, 120);
        return value;
    }

    private static String humanBytes(long bytes) {
        if (bytes < 1024L) return bytes + " B";
        if (bytes < 1024L * 1024L) return String.format(java.util.Locale.US, "%.1f KB", bytes / 1024d);
        if (bytes < 1024L * 1024L * 1024L) return String.format(java.util.Locale.US, "%.1f MB", bytes / (1024d * 1024d));
        return String.format(java.util.Locale.US, "%.2f GB", bytes / (1024d * 1024d * 1024d));
    }
}
