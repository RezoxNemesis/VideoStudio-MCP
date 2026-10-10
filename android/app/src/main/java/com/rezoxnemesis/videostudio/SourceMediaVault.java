package com.rezoxnemesis.videostudio;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.DocumentsContract;
import android.provider.OpenableColumns;
import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Iterator;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantLock;

/** Explicit registered-source backup. Media bytes are streamed, never materialized in a byte array. */
public final class SourceMediaVault {
    public interface Progress { void update(long completedBytes, long totalBytes, String detail) throws Exception; }
    public static final long CHUNK_BYTES = 256L * 1024L * 1024L;
    public static final long MAX_SOURCE_BYTES = CHUNK_BYTES * 256L;
    private static final int BUFFER_BYTES = 256 * 1024;
    private static final int MAX_CHUNKS = 256;
    private static final int MAX_ASSETS = 64;
    private static final int MAX_GENERATIONS = 64;
    private static final int MAX_DIRECTORY_ENTRIES = 1024;
    private static final int MAX_METADATA_BYTES = 256 * 1024;
    private static final int MAX_JOURNALS = 24;
    private static final int MAX_CATALOGS = 512;
    private static final String FORMAT = "videostudio-source-media-archive";
    private static final String FOLDER = "VideoStudio Source Media v1";
    private static final ReentrantLock TRANSFER_LOCK = new ReentrantLock(true);
    private final Context context;
    private final ContentResolver resolver;
    private final ProjectStore projects;
    private final DriveWorkspaceProvider grants;
    private final File root;
    private final File journals;
    private final File catalogs;
    private final File restoreJournals;

    public SourceMediaVault(Context context, ProjectStore projects) {
        if (projects == null) throw new IllegalArgumentException("Source archives require the project ownership ledger");
        this.context = context.getApplicationContext(); this.resolver = this.context.getContentResolver();
        this.projects = projects; this.grants = new DriveWorkspaceProvider(this.context);
        try {
            File expected = new File(this.context.getFilesDir().getCanonicalFile(), "source_media_vault");
            root = new File(this.context.getFilesDir(), "source_media_vault").getCanonicalFile();
            if (!root.equals(expected)) throw new IllegalStateException("Source archive metadata root contains a symbolic link");
        }
        catch (Exception error) { throw new IllegalStateException("Source archive metadata storage cannot be resolved", error); }
        journals = new File(root, "journals"); catalogs = new File(root, "catalogs"); restoreJournals = new File(root, "restore_journals");
        ensureOwnedDirectory(root); ensureOwnedDirectory(journals); ensureOwnedDirectory(catalogs); ensureOwnedDirectory(restoreJournals);
    }

    public static String pinId(String stableRequestId) {
        requestIdentity(stableRequestId);
        return uuid("source-archive-pin:" + stableRequestId);
    }
    public static String pinOwner(String stableRequestId) {
        requestIdentity(stableRequestId);
        return "source-archive:" + stableRequestId;
    }

    /** Explicit owner discard after its worker stops. No provider access and no media deletion. */
    public JSONObject forgetRetainedRequest(String projectId, String assetId, String pinnedTree, String generationId,
                                           String stableRequestId, boolean archive) throws Exception {
        identifier(projectId); identifier(assetId); requestIdentity(stableRequestId);
        if (pinnedTree == null || pinnedTree.length() > 16384 || !DocumentsContract.isTreeUri(Uri.parse(pinnedTree)))
            throw new IllegalArgumentException("The retained request's exact selected tree identity is required");
        if (!TRANSFER_LOCK.tryLock()) throw new IllegalStateException("A source transfer is still running; stop its worker before forgetting retained work");
        try {
            ensureReferenceLedger();
            boolean released = false, removed = false;
            if (archive) {
                String expectedGeneration = uuid("source-archive-generation:" + stableRequestId);
                if (generationId != null && !generationId.isEmpty() && !expectedGeneration.equals(generationId))
                    throw new IllegalArgumentException("Retained upload generation does not belong to this request");
                generationId = expectedGeneration;
                String pin = pinId(stableRequestId), owner = pinOwner(stableRequestId);
                // Reading first verifies exact owner/project/source even if no
                // journal was published before the request was cancelled.
                projects.readSourceArchivePin(projectId, assetId, pin, owner);
                File file = new File(journals, uuid("source-archive-journal:" + stableRequestId) + ".json");
                if (file.exists()) {
                    JSONObject journal = readJson(file); requireArchiveIdentity(journal, projectId, assetId, generationId);
                    if (!stableRequestId.equals(journal.getString("requestId")) || !pinnedTree.equals(journal.getString("archiveTreeUri")))
                        throw new IllegalStateException("Retained upload journal belongs to another exact request");
                    JSONObject committedReceipt = journal.optJSONObject("result");
                    if (committedReceipt != null && committedReceipt.optBoolean("committed", false)) saveCatalog(committedReceipt);
                    clearJournal(file, stableRequestId); removed = true;
                } else projects.removeSourceArchiveReferences("journal:" + stem(file), owner);
                released = projects.releaseSourceArchivePin(pin, owner);
            } else {
                UUID.fromString(generationId);
                File file = restoreIntentFile(projectId, assetId, pinnedTree, stableRequestId);
                if (file.exists()) {
                    JSONObject intent = readJson(file); requireArchiveIdentity(intent, projectId, assetId, generationId);
                    if (!pinnedTree.equals(intent.getString("archiveTreeUri")) || !stableRequestId.equals(intent.getString("requestId")))
                        throw new IllegalStateException("Retained restore belongs to another exact request or selected tree");
                    File directory = new File(new File(new File(root, "restored"), slot(projectId, assetId)), generationId);
                    File target = restoredTarget(directory, intent.getString("fileName"));
                    if (!Uri.fromFile(target).toString().equals(intent.getString("restoredUri"))) throw new IllegalStateException("Retained restore target identity changed");
                    File staging = new File(directory, target.getName() + ".partial");
                    if (target.exists() || staging.exists()) {
                        JSONObject receipt = new JSONObject(intent.getJSONObject("archiveReceipt").toString());
                        if (!projectId.equals(receipt.getString("projectId")) || !assetId.equals(receipt.getString("assetId"))
                                || !generationId.equals(receipt.getString("generationId")))
                            throw new IllegalStateException("Retained restore receipt ownership changed");
                        if (!pinnedTree.equals(receipt.getString("archiveTreeUri"))) throw new IllegalStateException("Retained restore receipt tree identity changed");
                        if (target.exists()) {
                            if (!regularOwned(target)) throw new IllegalStateException("Retained restored copy is aliased");
                            receipt.put("retainedRestoreTargetUri", Uri.fromFile(target).toString());
                        }
                        if (staging.exists()) {
                            if (!regularOwned(staging)) throw new IllegalStateException("Retained restore staging file is aliased");
                            receipt.put("retainedRestoreStagingUri", Uri.fromFile(staging).toString());
                        }
                        receipt.put("retainedLocalCopyVerification", "not_rechecked"); saveCatalog(receipt);
                    }
                    clearRestoreIntent(file); removed = true;
                } else projects.removeSourceArchiveReferences("restore:" + stem(file), "source-restore:" + stem(file));
            }
            return new JSONObject().put("ok", true).put("forgotten", true).put("requestId", stableRequestId)
                    .put("projectId", projectId).put("assetId", assetId).put("generationId", generationId)
                    .put("localJournalRemoved", removed).put("sourcePinReleased", released)
                    .put("remoteDraftChunksRetained", true).put("committedArchivesPreserved", true)
                    .put("localCopiedMediaPreserved", true).put("originalSourceDeleted", false);
        } finally { TRANSFER_LOCK.unlock(); }
    }

    /** Caller supplies the accepted project/source snapshot, never a remote caller's arbitrary URI. */
    public JSONObject archiveAsset(ProjectStore.Project project, ProjectStore.Asset asset, String pinnedTree,
                                   String stableRequestId, Progress progress) throws Exception {
        TRANSFER_LOCK.lockInterruptibly();
        try {
            checkInterrupted(); requestIdentity(stableRequestId);
            ensureReferenceLedger();
            if (project == null || asset == null || project.asset(asset.id) == null)
                throw new IllegalArgumentException("Select one registered source asset from its project");
            identifier(project.id); identifier(asset.id); sourceAsset(asset);
            Uri tree = validatedTree(pinnedTree, true);
            String pinId = pinId(stableRequestId), owner = pinOwner(stableRequestId);
            ProjectStore.Asset accepted = projects.readSourceArchivePin(project.id, asset.id, pinId, owner);
            if (accepted == null) accepted = projects.captureSourceArchivePin(project.id, project.revision, asset.id, pinId, owner);
            if (!assetProof(asset).equals(assetProof(accepted))) throw new IllegalStateException("Source archive snapshot differs from its trusted admission");
            sourceAsset(accepted);
            ProjectStore.Project sourcePin = projects.readExportPin(pinId, owner);
            if (sourcePin == null) throw new IllegalStateException("The durable source archive admission is unavailable");
            String generationId = uuid("source-archive-generation:" + stableRequestId);
            Uri generation = generation(tree, project.id, asset.id, generationId, true);
            File journalFile = new File(journals, uuid("source-archive-journal:" + stableRequestId) + ".json");
            JSONObject journal = journalFile.isFile() ? readJson(journalFile) : null;
            if (journal != null) requireJournal(journal, project.id, accepted, tree, stableRequestId, generationId, null);
            Uri committed = findChild(tree, generation, "COMMITTED.json", false);
            if (journal != null && journal.optJSONObject("commitIntent") != null) {
                Archive recovered = finalizeCommitIntent(tree, generation, project.id, asset.id, generationId, journal, committed, new Reporter(progress));
                if (!stableRequestId.equals(recovered.manifest.getString("requestId"))
                        || !assetProof(accepted).equals(recovered.manifest.getString("assetProof")))
                    throw new IllegalStateException("Source commit intent differs from its accepted source");
                JSONObject result = archiveResult(tree, generation, recovered.manifest, pinId, owner);
                saveCatalog(result); clearJournal(journalFile, stableRequestId);
                return result.put("reused", true).put("acceptedRevision", sourcePin.revision).put("sourceAssetSnapshot", accepted.toJson());
            }
            if (committed != null) {
                Archive complete = committedArchive(tree, generation, project.id, asset.id, generationId);
                if (!stableRequestId.equals(complete.manifest.getString("requestId"))
                        || !assetProof(accepted).equals(complete.manifest.getString("assetProof")))
                    throw new IllegalStateException("The immutable generation belongs to another source admission");
                verifyChunks(tree, generation, complete.manifest, new Reporter(progress), "Verifying committed archive");
                JSONObject result = archiveResult(tree, generation, complete.manifest, pinId, owner);
                saveCatalog(result); clearJournal(journalFile, stableRequestId);
                return result.put("reused", true).put("acceptedRevision", sourcePin.revision).put("sourceAssetSnapshot", accepted.toJson());
            }
            JSONObject stat = sourceStat(accepted);
            if (journal != null) requireJournal(journal, project.id, accepted, tree, stableRequestId, generationId, stat);
            if (journal == null) {
                if (journalFiles().size() >= MAX_JOURNALS) throw new IllegalStateException("Source upload journal capacity is full; resume and finish retained requests. Cancellation keeps resumable ownership");
                journal = new JSONObject().put("format", FORMAT).put("version", 1).put("state", "uploading")
                        .put("projectId", project.id).put("assetId", accepted.id).put("requestId", stableRequestId)
                        .put("generationId", generationId).put("archiveTreeUri", tree.toString()).put("archiveDirectoryUri", generation.toString())
                        .put("assetProof", assetProof(accepted)).put("sourceUri", accepted.uri).put("sourceStat", stat)
                        .put("sourceAssetSnapshot", new JSONObject(accepted.toJson().toString())).put("createdAt", System.currentTimeMillis())
                        .put("chunks", new JSONArray()).put("verifiedUploadedBytes", 0L);
                writeOwnedJson(journalFile, journal);
            }
            long knownLength = stat.getLong("size");
            JSONArray chunks = journal.getJSONArray("chunks");
            validateChunks(chunks, false, -1L);
            Reporter reporter = new Reporter(progress);
            MessageDigest whole = digest(); byte[] buffer = new byte[BUFFER_BYTES]; long total = 0L;
            try (InputStream source = openSource(accepted.uri)) {
                for (int index = 0; index < MAX_CHUNKS; index++) {
                    checkInterrupted();
                    JSONObject previous = index < chunks.length() ? chunks.getJSONObject(index) : null;
                    String name = chunkName(index); Uri target = findChild(tree, generation, name, false);
                    boolean reused = previous != null;
                    if (reused) {
                        if (target == null) throw new IllegalStateException("A journaled source chunk is missing; retain this request and repair its selected storage");
                        verifyRemoteChunk(target, previous.getLong("size"), previous.getString("sha256"), reporter, total, knownLength, "Verifying upload resume");
                    } else {
                        // This exact generation is uncommitted and exclusively owned
                        // by this trusted request. An interrupted partial chunk is
                        // replaceable; committed generations are never modified.
                        if (target != null && !DocumentsContract.deleteDocument(resolver, target))
                            throw new IllegalStateException("An interrupted source chunk could not be replaced");
                        target = createFile(generation, name, "application/octet-stream");
                    }
                    MessageDigest chunkHash = digest(); long size = 0L;
                    OutputStream output = reused ? null : requireOutput(target);
                    try {
                        while (size < CHUNK_BYTES) {
                            checkInterrupted();
                            int count = readProgress(source, buffer, (int) Math.min(buffer.length, CHUNK_BYTES - size));
                            if (count < 0) break;
                            if (count > MAX_SOURCE_BYTES - total - size) throw new IllegalArgumentException("Source exceeds the 64 GiB archive limit");
                            if (output != null) output.write(buffer, 0, count);
                            chunkHash.update(buffer, 0, count); whole.update(buffer, 0, count); size += count;
                            reporter.report(total + size, knownLength, "Archiving source chunk " + (index + 1), false);
                        }
                        if (output != null) output.flush();
                    } finally { if (output != null) output.close(); }
                    if (size == 0L) {
                        if (reused) throw new IllegalStateException("Source became shorter than its upload journal");
                        if (!DocumentsContract.deleteDocument(resolver, target)) throw new IllegalStateException("Unused final source chunk could not be cleared");
                        break;
                    }
                    String hash = hex(chunkHash.digest());
                    if (reused) {
                        if (size != previous.getLong("size") || !hash.equals(previous.getString("sha256")))
                            throw new IllegalStateException("Source bytes changed since this upload started; submit a new source archive request");
                    } else {
                        verifyRemoteChunk(target, size, hash, reporter, total, knownLength, "Reading back source chunk " + (index + 1));
                        chunks.put(new JSONObject().put("index", index).put("name", name).put("size", size).put("sha256", hash));
                        journal.put("verifiedUploadedBytes", total + size);
                        writeOwnedJson(journalFile, journal);
                    }
                    total += size;
                    reporter.report(total, knownLength, "Verified source chunk " + (index + 1), true);
                    if (size < CHUNK_BYTES) break;
                }
                if (readProgress(source, buffer, 1) != -1) throw new IllegalArgumentException("Source exceeds the 64 GiB archive limit or changed while uploading");
            }
            if (total <= 0L || (knownLength >= 0L && total != knownLength)) throw new IllegalStateException("Source size changed or the selected source is empty");
            validateChunks(chunks, true, total);
            String sourceHash = hex(whole.digest());
            if (!sourceStat(accepted).toString().equals(stat.toString())) throw new IllegalStateException("Source identity changed during upload");
            // A second complete source pass catches content-provider mutations
            // even when a provider reports no useful size/mtime validator.
            HashResult unchanged = hashSource(accepted.uri, total, reporter, "Verifying source unchanged");
            if (!sourceHash.equals(unchanged.sha256) || !sourceStat(accepted).toString().equals(stat.toString()))
                throw new IllegalStateException("Source bytes changed during upload; immutable commit was withheld");
            JSONObject manifest = new JSONObject().put("format", FORMAT).put("version", 1).put("projectId", project.id)
                    .put("assetId", accepted.id).put("requestId", stableRequestId).put("generationId", generationId)
                    .put("createdAt", journal.getLong("createdAt")).put("sourceUri", accepted.uri).put("sourceName", boundedName(accepted.name))
                    .put("sourceMime", accepted.mime).put("assetProof", assetProof(accepted)).put("sourceStat", stat)
                    .put("sourceBytes", total).put("sourceSha256", sourceHash).put("chunkBytes", CHUNK_BYTES).put("chunks", chunks);
            byte[] manifestBytes = metadataBytes(manifest);
            Uri manifestUri = replaceUncommittedFile(tree, generation, "MANIFEST.json", manifestBytes);
            if (!hashBytes(manifestBytes).equals(hashBytes(readBytes(manifestUri, MAX_METADATA_BYTES))))
                throw new IllegalStateException("Source archive manifest readback failed");
            verifyChunks(tree, generation, manifest, reporter, "Verifying complete source archive");
            if (!sourceStat(accepted).toString().equals(stat.toString())) throw new IllegalStateException("Source identity changed before immutable commit");
            checkInterrupted();
            if (findChild(tree, generation, "COMMITTED.json", false) != null)
                throw new IllegalStateException("Source generation was committed by another writer");
            JSONObject marker = new JSONObject().put("format", FORMAT).put("version", 1).put("projectId", project.id).put("assetId", accepted.id)
                    .put("generationId", generationId).put("manifestSha256", hashBytes(manifestBytes))
                    .put("sourceBytes", total).put("sourceSha256", sourceHash).put("committedAt", System.currentTimeMillis());
            // Durable proof precedes even creation of the final remote marker.
            // Recovery can finalize this same receipt from verified remote bytes
            // without reopening an original which has since become unavailable.
            byte[] markerBytes = metadataBytes(marker);
            journal.put("state", "committing").put("commitIntent", marker)
                    .put("commitIntentBytes", Base64.encodeToString(markerBytes, Base64.NO_WRAP))
                    .put("commitIntentSha256", hashBytes(markerBytes));
            writeOwnedJson(journalFile, journal);
            Uri markerUri = createFile(generation, "COMMITTED.json", "application/json");
            writeRemote(markerUri, markerBytes);
            Archive complete = committedArchive(tree, generation, project.id, asset.id, generationId);
            JSONObject result = archiveResult(tree, generation, complete.manifest, pinId, owner);
            saveCatalog(result);
            journal.put("state", "committed"); journal.put("result", result); writeOwnedJson(journalFile, journal);
            clearJournal(journalFile, stableRequestId);
            reporter.report(total, total, "Verified source archive committed", true);
            return result.put("acceptedRevision", sourcePin.revision).put("sourceAssetSnapshot", accepted.toJson());
        } finally { TRANSFER_LOCK.unlock(); }
    }

    /** Restore exactly one committed generation to an immutable, app-private file. Never deletes the original. */
    public JSONObject restoreAsset(String projectId, String assetId, String pinnedTree, String generationId, Progress progress) throws Exception {
        String legacyRequest = uuid("source-restore-legacy:" + projectId + "\n" + assetId + "\n" + pinnedTree + "\n" + generationId);
        return restoreAsset(projectId, assetId, pinnedTree, generationId, legacyRequest, progress);
    }

    /** Durable requests bind pending local publication to their exact trusted request identity. */
    public JSONObject restoreAsset(String projectId, String assetId, String pinnedTree, String generationId,
                                   String stableRequestId, Progress progress) throws Exception {
        TRANSFER_LOCK.lockInterruptibly();
        try {
            identifier(projectId); identifier(assetId); UUID.fromString(generationId); requestIdentity(stableRequestId); checkInterrupted();
            ensureReferenceLedger();
            Uri tree = validatedTree(pinnedTree, false);
            Uri generation = generation(tree, projectId, assetId, generationId, false);
            Archive archive = committedArchive(tree, generation, projectId, assetId, generationId);
            JSONObject manifest = archive.manifest; long total = manifest.getLong("sourceBytes");
            File directory = new File(new File(new File(root, "restored"), slot(projectId, assetId)), generationId);
            ensureOwnedDirectory(directory);
            File receiptFile = new File(directory, "RESTORED.json");
            File intentFile = restoreIntentFile(projectId, assetId, tree.toString(), stableRequestId);
            JSONObject intent = intentFile.isFile() ? readJson(intentFile) : null;
            File target = null;
            if (intent != null) {
                requireRestoreIntent(intent, tree, archive, projectId, assetId, generationId, stableRequestId);
                target = restoredTarget(directory, intent.getString("fileName"));
                if (!Uri.fromFile(target).toString().equals(intent.getString("restoredUri")))
                    throw new IllegalStateException("Restore intent target no longer matches its private ownership record");
                if (target.exists()) {
                    requireVerifiedRestore(target, manifest, new Reporter(progress));
                    return finishRestore(tree, generation, manifest, target, receiptFile, intentFile).put("reused", true);
                }
            }
            if (intent == null && receiptFile.isFile()) {
                JSONObject receipt = readJson(receiptFile);
                String name = receipt.optString("fileName", "");
                if (name.matches("source_[a-f0-9-]{36}\\.[a-z0-9]{1,8}")) {
                    File existing = new File(directory, name);
                    if (regularOwned(existing) && existing.length() == total
                            && manifest.getString("sourceSha256").equals(hashSource(Uri.fromFile(existing).toString(), total, new Reporter(progress), "Verifying existing restored copy").sha256))
                        return finishRestore(tree, generation, manifest, existing, receiptFile, intentFile).put("reused", true);
                }
            }
            if (intent == null) {
                if (metadataFiles(restoreJournals, MAX_JOURNALS).size() >= MAX_JOURNALS)
                    throw new IllegalStateException("Source restore journal capacity is full; resume retained restores to finish publication");
                target = restoredTarget(directory, "source_" + UUID.randomUUID() + "." + extension(manifest.getString("sourceMime")));
                intent = new JSONObject().put("format", FORMAT).put("version", 1).put("state", "restoring")
                        .put("projectId", projectId).put("assetId", assetId).put("generationId", generationId)
                        .put("requestId", stableRequestId)
                        .put("archiveTreeUri", tree.toString()).put("archiveDirectoryUri", generation.toString())
                        .put("manifestSha256", archive.marker.getString("manifestSha256"))
                        .put("sourceBytes", total).put("sourceSha256", manifest.getString("sourceSha256"))
                        .put("archiveReceipt", archiveResult(tree, generation, manifest, "", ""))
                        .put("fileName", target.getName()).put("restoredUri", Uri.fromFile(target).toString())
                        .put("createdAt", System.currentTimeMillis());
                // The target is discoverable and retained before either staging
                // bytes or an immutable published file can exist.
                writeOwnedJson(intentFile, intent);
            }
            File temp = new File(directory, target.getName() + ".partial");
            if (temp.exists()) {
                if (!regularOwned(temp)) throw new IllegalStateException("Restore staging path is aliased or is not a regular owned file");
                if (temp.length() == total && manifest.getString("sourceSha256").equals(hashSource(Uri.fromFile(temp).toString(), total,
                        new Reporter(progress), "Verifying interrupted restored copy").sha256)) {
                    checkInterrupted();
                    if (target.exists() || !temp.renameTo(target)) throw new IllegalStateException("Verified interrupted restore could not be atomically published");
                    return finishRestore(tree, generation, manifest, target, receiptFile, intentFile).put("reused", true);
                }
                // Only this durable intent's unpublished staging bytes can be
                // replaced. A published target with different bytes is retained.
                if (!temp.delete()) throw new IllegalStateException("Interrupted restore staging file could not be cleared");
            }
            StorageBudget.Check storage = StorageBudget.check(directory.getUsableSpace(), total, StorageBudget.DEFAULT_TRANSFER_RESERVE_BYTES);
            if (!storage.allowed) throw new IllegalStateException("Restore needs " + storage.requiredWithReserveBytes + " free bytes including the storage reserve; available " + storage.freeBytes);
            if (!temp.createNewFile()) throw new IllegalStateException("A new restore staging file could not be reserved");
            Reporter reporter = new Reporter(progress); MessageDigest whole = digest(); long copied = 0L, nextSpaceCheck = 0L;
            byte[] buffer = new byte[BUFFER_BYTES];
            try {
                try (FileOutputStream output = new FileOutputStream(temp)) {
                    JSONArray chunks = manifest.getJSONArray("chunks");
                    for (int index = 0; index < chunks.length(); index++) {
                        JSONObject chunk = chunks.getJSONObject(index); Uri source = findChild(tree, generation, chunk.getString("name"), false);
                        if (source == null) throw new IllegalStateException("A committed source archive chunk is missing");
                        MessageDigest part = digest(); long size = 0L, expected = chunk.getLong("size");
                        try (InputStream input = requireInput(source)) {
                            while (size < expected) {
                                checkInterrupted();
                                if (copied >= nextSpaceCheck) {
                                    if (directory.getUsableSpace() < StorageBudget.DEFAULT_TRANSFER_RESERVE_BYTES + 4L * 1024L * 1024L)
                                        throw new IllegalStateException("Storage reserve reached during source restore");
                                    nextSpaceCheck = copied + 4L * 1024L * 1024L;
                                }
                                int count = readProgress(input, buffer, (int) Math.min(buffer.length, expected - size));
                                if (count < 0) throw new IllegalStateException("A source archive chunk ended early");
                                output.write(buffer, 0, count); whole.update(buffer, 0, count); part.update(buffer, 0, count);
                                size += count; copied += count;
                                reporter.report(copied, total, "Restoring verified source chunk " + (index + 1), false);
                            }
                            if (readProgress(input, buffer, 1) != -1) throw new IllegalStateException("A source archive chunk exceeds its declared size");
                        }
                        if (!chunk.getString("sha256").equals(hex(part.digest()))) throw new IllegalStateException("Source restore chunk checksum failed");
                    }
                    output.flush(); output.getFD().sync();
                }
                if (copied != total || !manifest.getString("sourceSha256").equals(hex(whole.digest())))
                    throw new IllegalStateException("Complete source restore checksum or size failed");
                if (!manifest.getString("sourceSha256").equals(hashSource(Uri.fromFile(temp).toString(), total, reporter, "Reading back restored source").sha256))
                    throw new IllegalStateException("Local source restore readback checksum failed");
                intent.put("state", "verified"); writeOwnedJson(intentFile, intent);
                checkInterrupted();
                if (target.exists() || !temp.renameTo(target)) throw new IllegalStateException("Verified source restore could not be atomically published");
                JSONObject result = finishRestore(tree, generation, manifest, target, receiptFile, intentFile);
                reporter.report(total, total, "Verified original source restored", true);
                return result;
            } finally { if (temp.exists()) temp.delete(); }
        } finally { TRANSFER_LOCK.unlock(); }
    }

    /** Local receipts only. This never scans a remote folder or its media chunks. */
    public JSONArray localArchives(String projectId, String assetId) {
        try { ensureReferenceLedger(); }
        catch (Exception error) { throw new IllegalStateException("Source archive reference migration requires recovery", error); }
        identifier(projectId); identifier(assetId); ArrayList<JSONObject> results = new ArrayList<>();
        File[] files = catalogs.listFiles();
        if (files == null) return new JSONArray();
        if (files.length > MAX_CATALOGS) throw new IllegalStateException("Source archive catalog exceeds its metadata bound");
        String prefix = slot(projectId, assetId) + "_";
        for (File file : files) if (file.getName().startsWith(prefix) && file.getName().endsWith(".json")) {
            try {
                JSONObject catalog = readJson(file);
                if (!projectId.equals(catalog.getString("projectId")) || !assetId.equals(catalog.getString("assetId"))) throw new IllegalStateException("Source archive catalog identity is damaged");
                JSONArray entries = catalog.getJSONArray("archives");
                if (entries.length() > MAX_GENERATIONS) throw new IllegalStateException("Source archive catalog exceeds its generation bound");
                for (int index = 0; index < entries.length(); index++) results.add(new JSONObject(entries.getJSONObject(index).toString()));
            } catch (Exception invalid) { throw new IllegalStateException("Source archive catalog requires recovery", invalid); }
        }
        Collections.sort(results, Comparator.comparingLong((JSONObject item) -> item.optLong("createdAt", 0L)).reversed());
        JSONArray output = new JSONArray(); for (int index = 0; index < Math.min(MAX_GENERATIONS, results.size()); index++) output.put(results.get(index));
        return output;
    }

    public JSONObject localStatus(String projectId, String assetId) {
        try {
            ensureReferenceLedger(); identifier(projectId); identifier(assetId);
            JSONArray pending = new JSONArray();
            for (File file : journalFiles()) {
                JSONObject journal = readJson(file);
                if (projectId.equals(journal.optString("projectId")) && assetId.equals(journal.optString("assetId")))
                    pending.put(new JSONObject().put("state", journal.optString("state", "uploading"))
                            .put("generationId", journal.getString("generationId")).put("archiveTreeUri", journal.getString("archiveTreeUri"))
                            .put("createdAt", journal.getLong("createdAt")).put("verifiedUploadedBytes", journal.optLong("verifiedUploadedBytes", 0L)));
            }
            JSONArray restoring = new JSONArray();
            for (File file : metadataFiles(restoreJournals, MAX_JOURNALS)) {
                JSONObject intent = readJson(file);
                if (projectId.equals(intent.optString("projectId")) && assetId.equals(intent.optString("assetId")))
                    restoring.put(new JSONObject().put("state", intent.optString("state", "restoring"))
                            .put("generationId", intent.getString("generationId")).put("archiveTreeUri", intent.getString("archiveTreeUri"))
                            .put("createdAt", intent.getLong("createdAt")).put("sourceBytes", intent.getLong("sourceBytes")));
            }
            return new JSONObject().put("archives", localArchives(projectId, assetId)).put("uploads", pending).put("restores", restoring)
                    .put("quotaStatus", "unknown").put("availableBytes", JSONObject.NULL).put("chunkBytes", CHUNK_BYTES)
                    .put("maxSourceBytes", MAX_SOURCE_BYTES).put("originalSourcesDeleted", false).put("remoteScan", false);
        } catch (Exception error) { throw new IllegalStateException("Source archive status requires recovery", error); }
    }

    /** Catalog/journal evidence is read under ProjectStore's transaction before any owned-file cleanup. */
    static boolean referencesMediaUri(File appFilesRoot, String uri) {
        try {
            File base = new File(appFilesRoot, "source_media_vault").getCanonicalFile();
            for (String name : new String[]{"journals", "catalogs", "restore_journals"}) {
                File directory = new File(base, name); if (!directory.exists()) continue;
                if (!directory.getCanonicalFile().equals(directory)) return true;
                File[] files = directory.listFiles(); if (files == null || files.length > (name.equals("catalogs") ? MAX_CATALOGS : MAX_JOURNALS)) return true;
                for (File file : files) {
                    if (!file.getName().endsWith(".json")) continue;
                    if (!file.getCanonicalFile().equals(file) || !file.isFile()) return true;
                    JSONObject evidence = ProjectMediaReferenceIndex.decodeOwnershipMetadata(readLocalBytes(file));
                    if (ProjectMediaReferences.referencesValue(evidence, uri)) return true;
                }
            }
            return false;
        } catch (Exception damaged) { return true; }
    }

    private Archive committedArchive(Uri tree, Uri generation, String projectId, String assetId, String generationId) throws Exception {
        Uri markerUri = findChild(tree, generation, "COMMITTED.json", false), manifestUri = findChild(tree, generation, "MANIFEST.json", false);
        if (markerUri == null || manifestUri == null) throw new IllegalStateException("Source archive generation is incomplete; no committed source can be restored");
        JSONObject marker = ProjectMediaReferenceIndex.decodeOwnershipMetadata(readBytes(markerUri, MAX_METADATA_BYTES));
        byte[] manifestBytes = readBytes(manifestUri, MAX_METADATA_BYTES);
        return validateArchive(marker, manifestBytes, projectId, assetId, generationId);
    }

    private Archive validateArchive(JSONObject marker, byte[] manifestBytes, String projectId, String assetId, String generationId) throws Exception {
        JSONObject manifest = ProjectMediaReferenceIndex.decodeOwnershipMetadata(manifestBytes);
        requireArchiveIdentity(marker, projectId, assetId, generationId); requireArchiveIdentity(manifest, projectId, assetId, generationId);
        String manifestHash = strictHash(marker, "manifestSha256");
        if (!manifestHash.equals(hashBytes(manifestBytes))) throw new IllegalStateException("Committed source manifest checksum failed");
        long size = exactLong(manifest, "sourceBytes"), markerSize = exactLong(marker, "sourceBytes");
        String hash = strictHash(manifest, "sourceSha256");
        if (size <= 0L || size > MAX_SOURCE_BYTES || markerSize != size || !hash.equals(strictHash(marker, "sourceSha256"))
                || exactLong(manifest, "chunkBytes") != CHUNK_BYTES || exactLong(marker, "committedAt") <= 0L)
            throw new IllegalStateException("Committed source receipt is inconsistent");
        requestIdentity(manifest.getString("requestId")); strictHash(manifest, "assetProof");
        if (manifest.getString("sourceUri").length() > 16384 || manifest.getString("sourceName").length() > 256)
            throw new IllegalStateException("Source manifest labels exceed their metadata bounds");
        validateChunks(manifest.getJSONArray("chunks"), true, size);
        return new Archive(manifest, marker);
    }

    private Archive finalizeCommitIntent(Uri tree, Uri generation, String projectId, String assetId, String generationId,
                                         JSONObject journal, Uri markerUri, Reporter reporter) throws Exception {
        byte[] expected;
        if (journal.has("commitIntentBytes")) {
            String encoded = journal.getString("commitIntentBytes");
            if (encoded.length() > 16384) throw new IllegalStateException("Source marker intent exceeds its byte bound");
            expected = Base64.decode(encoded, Base64.NO_WRAP);
            if (!hashBytes(expected).equals(strictHash(journal, "commitIntentSha256")))
                throw new IllegalStateException("Source marker intent checksum failed");
        } else expected = metadataBytes(journal.getJSONObject("commitIntent"));
        JSONObject intent = ProjectMediaReferenceIndex.decodeOwnershipMetadata(expected);
        Uri manifestUri = findChild(tree, generation, "MANIFEST.json", false);
        if (manifestUri == null) throw new IllegalStateException("Source commit intent has no verified manifest");
        Archive archive = validateArchive(intent, readBytes(manifestUri, MAX_METADATA_BYTES), projectId, assetId, generationId);
        if (!journal.getString("requestId").equals(archive.manifest.getString("requestId"))
                || !journal.getString("assetProof").equals(archive.manifest.getString("assetProof")))
            throw new IllegalStateException("Source commit intent belongs to another accepted source");
        byte[] existing = markerUri == null ? null : readBytes(markerUri, MAX_METADATA_BYTES);
        if (existing != null && hashBytes(existing).equals(hashBytes(expected))) {
            verifyChunks(tree, generation, archive.manifest, reporter, "Verifying recovered source commit");
            return committedArchive(tree, generation, projectId, assetId, generationId);
        }
        // Only an exact shorter prefix of our durable marker write is an
        // interrupted write. Foreign/corrupted complete receipts remain intact.
        if (existing != null && !isPrefix(existing, expected))
            throw new IllegalStateException("Source commit marker differs from its durable intent; retain this generation for recovery");
        verifyChunks(tree, generation, archive.manifest, reporter, "Finalizing verified source commit");
        if (markerUri != null) {
            byte[] latest = readBytes(markerUri, MAX_METADATA_BYTES);
            if (!java.util.Arrays.equals(existing, latest) || !DocumentsContract.deleteDocument(resolver, markerUri))
                throw new IllegalStateException("Interrupted source marker changed during recovery");
        } else if (findChild(tree, generation, "COMMITTED.json", false) != null) {
            throw new IllegalStateException("Source generation was finalized by another writer");
        }
        Uri made = createFile(generation, "COMMITTED.json", "application/json"); writeRemote(made, expected);
        return committedArchive(tree, generation, projectId, assetId, generationId);
    }

    private static boolean isPrefix(byte[] partial, byte[] expected) {
        if (partial.length >= expected.length) return false;
        for (int index = 0; index < partial.length; index++) if (partial[index] != expected[index]) return false;
        return true;
    }

    private void verifyChunks(Uri tree, Uri generation, JSONObject manifest, Reporter reporter, String detail) throws Exception {
        JSONArray chunks = manifest.getJSONArray("chunks"); MessageDigest whole = digest(); byte[] buffer = new byte[BUFFER_BYTES];
        long total = manifest.getLong("sourceBytes"), done = 0L;
        for (int index = 0; index < chunks.length(); index++) {
            JSONObject chunk = chunks.getJSONObject(index); Uri file = findChild(tree, generation, chunk.getString("name"), false);
            if (file == null) throw new IllegalStateException("Committed source chunk is missing");
            MessageDigest part = digest(); long expected = chunk.getLong("size"), copied = 0L;
            try (InputStream input = requireInput(file)) {
                while (copied < expected) {
                    checkInterrupted(); int count = readProgress(input, buffer, (int) Math.min(buffer.length, expected - copied));
                    if (count < 0) throw new IllegalStateException("Source archive chunk is truncated");
                    part.update(buffer, 0, count); whole.update(buffer, 0, count); copied += count;
                    reporter.report(done + copied, total, detail, false);
                }
                if (readProgress(input, buffer, 1) != -1) throw new IllegalStateException("Source archive chunk exceeds its receipt");
            }
            if (!chunk.getString("sha256").equals(hex(part.digest()))) throw new IllegalStateException("Source archive chunk checksum failed");
            done += copied;
        }
        if (done != total || !manifest.getString("sourceSha256").equals(hex(whole.digest())))
            throw new IllegalStateException("Whole source archive checksum failed");
    }

    private void verifyRemoteChunk(Uri uri, long size, String hash, Reporter reporter, long before, long total, String detail) throws Exception {
        HashResult result = hashStream(requireInput(uri), size, reporter, before, total, detail);
        if (!hash.equals(result.sha256)) throw new IllegalStateException("Source chunk readback checksum failed");
    }

    private HashResult hashSource(String source, long size, Reporter reporter, String detail) throws Exception {
        return hashStream(openSource(source), size, reporter, 0L, size, detail);
    }

    private static HashResult hashStream(InputStream stream, long expected, Reporter reporter, long before, long total, String detail) throws Exception {
        MessageDigest hash = digest(); byte[] buffer = new byte[BUFFER_BYTES]; long copied = 0L;
        try (InputStream input = stream) {
            while (copied < expected) {
                checkInterrupted(); int count = readProgress(input, buffer, (int) Math.min(buffer.length, expected - copied));
                if (count < 0) throw new IllegalStateException("Verified media stream ended before its declared size");
                hash.update(buffer, 0, count); copied += count; reporter.report(before + copied, total, detail, false);
            }
            if (readProgress(input, buffer, 1) != -1) throw new IllegalStateException("Verified media stream is larger than its declared size");
        }
        return new HashResult(copied, hex(hash.digest()));
    }

    private Uri validatedTree(String tree, boolean write) {
        if (tree == null || tree.isEmpty()) throw new IllegalArgumentException("Select a persisted storage profile before archiving sources");
        grants.validateArchiveTree(tree, write); Uri uri = Uri.parse(tree);
        if (!DocumentsContract.isTreeUri(uri)) throw new IllegalArgumentException("Source archive requires the exact owner-selected document tree");
        return uri;
    }

    private Uri generation(Uri tree, String projectId, String assetId, String id, boolean create) throws Exception {
        Uri selected = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree));
        Uri vault = directory(tree, selected, FOLDER, create), assets = directory(tree, vault, "Assets", create);
        String slot = slot(projectId, assetId);
        Uri source = findChild(tree, assets, slot, true);
        if (source == null) {
            if (!create) throw new IllegalStateException("This selected profile has no archive for the requested source");
            if (childCount(tree, assets) >= MAX_ASSETS) throw new IllegalStateException("Selected source archive supports at most 64 registered asset slots");
            source = directory(tree, assets, slot, true);
        }
        Uri revisions = directory(tree, source, "Revisions", create), generation = findChild(tree, revisions, id, true);
        if (generation == null) {
            if (!create) throw new IllegalStateException("The exact committed source generation is unavailable in its pinned profile");
            if (childCount(tree, revisions) >= MAX_GENERATIONS) throw new IllegalStateException("Source archive generation capacity is full; preserve or explicitly manage older backups");
            generation = directory(tree, revisions, id, true);
        }
        return generation;
    }

    private Uri directory(Uri tree, Uri parent, String name, boolean create) throws Exception {
        if (parent == null) throw new IllegalStateException("Source archive parent folder is missing");
        Uri existing = findChild(tree, parent, name, true);
        if (existing != null) return existing;
        if (!create) throw new IllegalStateException("Source archive folder is unavailable: " + name);
        Uri made = DocumentsContract.createDocument(resolver, parent, DocumentsContract.Document.MIME_TYPE_DIR, name);
        verifyCreated(made, name, true); return made;
    }

    private Uri findChild(Uri tree, Uri parent, String name, boolean directory) throws Exception {
        if (parent == null) return null;
        Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, DocumentsContract.getDocumentId(parent));
        Uri found = null; int visited = 0;
        try (Cursor cursor = resolver.query(children, new String[]{DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_MIME_TYPE}, null, null, null)) {
            if (cursor == null) throw new IllegalStateException("Selected provider did not return a readable folder listing");
            while (cursor.moveToNext()) {
                checkInterrupted(); if (++visited > MAX_DIRECTORY_ENTRIES) throw new IllegalStateException("Selected archive folder exceeds the bounded lookup limit");
                if (!name.equals(cursor.getString(1))) continue;
                if (directory != DocumentsContract.Document.MIME_TYPE_DIR.equals(cursor.getString(2)))
                    throw new IllegalStateException("Archive entry has a conflicting type: " + name);
                if (found != null) throw new IllegalStateException("Archive provider returned duplicate entry names: " + name);
                found = DocumentsContract.buildDocumentUriUsingTree(tree, cursor.getString(0));
            }
        }
        return found;
    }

    private int childCount(Uri tree, Uri directory) throws Exception {
        Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, DocumentsContract.getDocumentId(directory)); int count = 0;
        try (Cursor cursor = resolver.query(children, new String[]{DocumentsContract.Document.COLUMN_DOCUMENT_ID}, null, null, null)) {
            if (cursor == null) throw new IllegalStateException("Selected archive folder cannot be counted");
            while (cursor.moveToNext()) { checkInterrupted(); if (++count > MAX_DIRECTORY_ENTRIES) throw new IllegalStateException("Archive lookup limit exceeded"); }
        }
        return count;
    }

    private Uri createFile(Uri parent, String name, String mime) throws Exception {
        Uri made = DocumentsContract.createDocument(resolver, parent, mime, name); verifyCreated(made, name, false); return made;
    }

    private void verifyCreated(Uri uri, String name, boolean directory) throws Exception {
        if (uri == null) throw new IllegalStateException("Selected storage could not create archive entry " + name);
        try (Cursor cursor = resolver.query(uri, new String[]{DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_MIME_TYPE}, null, null, null)) {
            if (cursor == null || !cursor.moveToFirst() || !name.equals(cursor.getString(0))
                    || directory != DocumentsContract.Document.MIME_TYPE_DIR.equals(cursor.getString(1)))
                throw new IllegalStateException("Provider changed the immutable archive entry's name or type");
        }
    }

    private Uri replaceUncommittedFile(Uri tree, Uri generation, String name, byte[] bytes) throws Exception {
        if (findChild(tree, generation, "COMMITTED.json", false) != null) throw new IllegalStateException("Committed source generation cannot be overwritten");
        Uri existing = findChild(tree, generation, name, false);
        if (existing != null && !DocumentsContract.deleteDocument(resolver, existing)) throw new IllegalStateException("Interrupted manifest could not be replaced");
        Uri made = createFile(generation, name, "application/json"); writeRemote(made, bytes); return made;
    }

    private JSONObject sourceStat(ProjectStore.Asset asset) throws Exception {
        Uri uri = Uri.parse(asset.uri); long size = -1L, modified = 0L; String identity = asset.uri;
        if ("file".equals(uri.getScheme())) {
            File file = new File(uri.getPath());
            if (!file.isFile() || !file.canRead()) throw new IllegalStateException("The accepted original source is no longer readable");
            size = file.length(); modified = file.lastModified(); identity = file.getCanonicalPath();
        } else if ("content".equals(uri.getScheme())) {
            try (Cursor cursor = resolver.query(uri, new String[]{OpenableColumns.SIZE, DocumentsContract.Document.COLUMN_LAST_MODIFIED}, null, null, null)) {
                if (cursor != null && cursor.moveToFirst()) {
                    int sizeColumn = cursor.getColumnIndex(OpenableColumns.SIZE), modifiedColumn = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_LAST_MODIFIED);
                    if (sizeColumn >= 0 && !cursor.isNull(sizeColumn)) size = cursor.getLong(sizeColumn);
                    if (modifiedColumn >= 0 && !cursor.isNull(modifiedColumn)) modified = Math.max(0L, cursor.getLong(modifiedColumn));
                }
            } catch (RuntimeException providerMetadataUnavailable) {
                // Unknown validators never become made-up size/quota evidence.
                size = -1L; modified = 0L;
            }
        } else throw new IllegalArgumentException("Only registered file or document source URIs can be archived");
        if (size == 0L || size > MAX_SOURCE_BYTES) throw new IllegalArgumentException("Source must contain bytes and fit the 64 GiB archive limit");
        return new JSONObject().put("uri", asset.uri).put("identity", identity).put("size", Math.max(-1L, size)).put("modifiedAt", modified);
    }

    private InputStream openSource(String source) throws Exception {
        Uri uri = Uri.parse(source);
        if ("file".equals(uri.getScheme())) return new FileInputStream(new File(uri.getPath()));
        if (!"content".equals(uri.getScheme())) throw new IllegalArgumentException("Original source URI is unsupported");
        return requireInput(uri);
    }

    private InputStream requireInput(Uri uri) throws Exception {
        InputStream input = resolver.openInputStream(uri); if (input == null) throw new IllegalStateException("Selected provider did not open a readable archive stream"); return input;
    }
    private OutputStream requireOutput(Uri uri) throws Exception {
        OutputStream output = resolver.openOutputStream(uri, "wt"); if (output == null) throw new IllegalStateException("Selected provider did not open a writable archive stream"); return output;
    }
    private void writeRemote(Uri uri, byte[] bytes) throws Exception {
        try (OutputStream output = requireOutput(uri)) { output.write(bytes); output.flush(); }
    }
    private byte[] readBytes(Uri uri, int max) throws Exception {
        try (InputStream input = requireInput(uri)) { return readBounded(input, max); }
    }

    private void requireJournal(JSONObject journal, String projectId, ProjectStore.Asset asset, Uri tree, String request, String generation, JSONObject stat) throws Exception {
        if (!FORMAT.equals(journal.getString("format")) || exactLong(journal, "version") != 1L
                || !projectId.equals(journal.getString("projectId")) || !asset.id.equals(journal.getString("assetId"))
                || !request.equals(journal.getString("requestId")) || !generation.equals(journal.getString("generationId"))
                || !tree.toString().equals(journal.getString("archiveTreeUri")) || !asset.uri.equals(journal.getString("sourceUri"))
                || !assetProof(asset).equals(journal.getString("assetProof"))
                || stat != null && !stat.toString().equals(journal.getJSONObject("sourceStat").toString()))
            throw new IllegalStateException("Source upload journal belongs to changed source media or a different pinned profile");
    }

    private static void validateChunks(JSONArray chunks, boolean complete, long total) throws Exception {
        if (chunks == null || chunks.length() > MAX_CHUNKS || complete && chunks.length() == 0)
            throw new IllegalStateException("Source archive chunk count exceeds its bound");
        long sum = 0L;
        for (int index = 0; index < chunks.length(); index++) {
            JSONObject chunk = chunks.getJSONObject(index); long size = exactLong(chunk, "size");
            if (exactLong(chunk, "index") != index || !chunkName(index).equals(chunk.getString("name")) || size <= 0L || size > CHUNK_BYTES
                    || index + 1 < chunks.length() && size != CHUNK_BYTES)
                throw new IllegalStateException("Source archive chunk order, size or name is invalid");
            strictHash(chunk, "sha256"); sum += size;
        }
        if (sum > MAX_SOURCE_BYTES || complete && sum != total) throw new IllegalStateException("Source archive chunk sizes do not match their receipt");
    }

    private static void requireArchiveIdentity(JSONObject value, String projectId, String assetId, String generationId) throws Exception {
        if (!FORMAT.equals(value.getString("format")) || exactLong(value, "version") != 1L
                || !projectId.equals(value.getString("projectId")) || !assetId.equals(value.getString("assetId"))
                || !generationId.equals(value.getString("generationId")))
            throw new IllegalStateException("Exact source archive format, generation or ownership identity does not match");
    }

    private static JSONObject archiveResult(Uri tree, Uri generation, JSONObject manifest, String pin, String owner) throws Exception {
        return new JSONObject().put("ok", true).put("committed", true).put("archiveMetadataVerified", true)
                .put("projectId", manifest.getString("projectId")).put("assetId", manifest.getString("assetId"))
                .put("generationId", manifest.getString("generationId")).put("archiveTreeUri", tree.toString())
                .put("archiveDirectoryUri", generation.toString()).put("providerAuthority", tree.getAuthority())
                .put("sourceUri", manifest.getString("sourceUri")).put("sourceName", manifest.getString("sourceName"))
                .put("sourceMime", manifest.getString("sourceMime")).put("sourceBytes", manifest.getLong("sourceBytes"))
                .put("sourceSha256", manifest.getString("sourceSha256")).put("chunkCount", manifest.getJSONArray("chunks").length())
                .put("createdAt", manifest.getLong("createdAt")).put("pinId", pin).put("pinOwner", owner)
                .put("quotaStatus", "unknown").put("availableBytes", JSONObject.NULL).put("originalSourceDeleted", false);
    }

    private static JSONObject restoredResult(Uri tree, Uri generation, JSONObject manifest, File target) throws Exception {
        return archiveResult(tree, generation, manifest, "", "").put("restored", true).put("uri", Uri.fromFile(target).toString())
                .put("localPath", target.getAbsolutePath()).put("verifiedRestoredBytes", manifest.getLong("sourceBytes"))
                .put("restoredSha256", manifest.getString("sourceSha256")).put("originalSourceDeleted", false);
    }

    private File restoredTarget(File directory, String name) throws Exception {
        if (!name.matches("source_[a-f0-9-]{36}\\.[a-z0-9]{1,8}")) throw new IllegalStateException("Restore target filename is invalid");
        File target = new File(directory, name);
        if (!target.getCanonicalFile().equals(target.getAbsoluteFile())) throw new IllegalStateException("Restore target is aliased");
        return target;
    }

    private File restoreIntentFile(String projectId, String assetId, String tree, String requestId) {
        return new File(restoreJournals, slot(projectId, assetId) + "_" + uuid(tree) + "_" + uuid("source-restore-journal:" + requestId) + ".json");
    }

    private static void requireRestoreIntent(JSONObject intent, Uri tree, Archive archive, String projectId, String assetId, String generationId, String requestId) throws Exception {
        requireArchiveIdentity(intent, projectId, assetId, generationId);
        if (!requestId.equals(intent.getString("requestId")) || !tree.toString().equals(intent.getString("archiveTreeUri"))
                || exactLong(intent, "sourceBytes") != exactLong(archive.manifest, "sourceBytes")
                || !strictHash(intent, "sourceSha256").equals(strictHash(archive.manifest, "sourceSha256"))
                || !strictHash(intent, "manifestSha256").equals(strictHash(archive.marker, "manifestSha256")))
            throw new IllegalStateException("Restore intent does not match its exact committed source generation");
    }

    private void requireVerifiedRestore(File target, JSONObject manifest, Reporter reporter) throws Exception {
        long total = manifest.getLong("sourceBytes");
        if (!regularOwned(target) || target.length() != total
                || !manifest.getString("sourceSha256").equals(hashSource(Uri.fromFile(target).toString(), total, reporter, "Verifying recovered restored copy").sha256))
            throw new IllegalStateException("Published restored target differs from its durable intent; it has been retained for recovery");
    }

    private JSONObject finishRestore(Uri tree, Uri generation, JSONObject manifest, File target, File receiptFile, File intentFile) throws Exception {
        JSONObject result = restoredResult(tree, generation, manifest, target);
        JSONObject catalogReceipt = archiveResult(tree, generation, manifest, "", "")
                .put("restoredUri", Uri.fromFile(target).toString()).put("restoredSha256", manifest.getString("sourceSha256"))
                .put("verifiedRestoredBytes", manifest.getLong("sourceBytes")).put("restoredAt", System.currentTimeMillis());
        // Catalog evidence is registered in SQLite before local metadata publication.
        // The pending target record remains until both durable receipts exist.
        saveCatalog(catalogReceipt);
        writeOwnedJson(receiptFile, new JSONObject().put("fileName", target.getName())
                .put("sourceSha256", manifest.getString("sourceSha256")).put("result", result));
        clearRestoreIntent(intentFile);
        return result;
    }

    private void saveCatalog(JSONObject result) throws Exception {
        String projectId = result.getString("projectId"), assetId = result.getString("assetId");
        File file = new File(catalogs, slot(projectId, assetId) + "_" + uuid(result.getString("archiveTreeUri")) + ".json");
        JSONObject catalog = file.isFile() ? readJson(file) : new JSONObject().put("projectId", projectId).put("assetId", assetId).put("archives", new JSONArray());
        if (!file.exists()) { File[] files = catalogs.listFiles(); if (files == null || files.length >= MAX_CATALOGS) throw new IllegalStateException("Source archive catalog capacity is full"); }
        JSONArray old = catalog.getJSONArray("archives"), next = new JSONArray(); boolean replaced = false;
        for (int index = 0; index < old.length(); index++) {
            JSONObject previous = old.getJSONObject(index);
            if (previous.getString("generationId").equals(result.getString("generationId"))) {
                JSONObject replacement = new JSONObject(result.toString());
                for (String key : new String[]{"restoredUri", "restoredSha256", "verifiedRestoredBytes", "restoredAt",
                        "retainedRestoreTargetUri", "retainedRestoreStagingUri", "retainedLocalCopyVerification"})
                    if (!replacement.has(key) && previous.has(key)) replacement.put(key, previous.get(key));
                next.put(replacement); replaced = true;
            }
            else next.put(previous);
        }
        if (!replaced) next.put(new JSONObject(result.toString()));
        if (next.length() > MAX_GENERATIONS) throw new IllegalStateException("Source archive catalog generation capacity is full");
        catalog.put("archives", next); writeOwnedJson(file, catalog);
    }

    private ArrayList<File> journalFiles() {
        return metadataFiles(journals, MAX_JOURNALS);
    }
    private ArrayList<File> metadataFiles(File directory, int limit) {
        File[] files = directory.listFiles(); ArrayList<File> result = new ArrayList<>();
        if (files == null || files.length > limit * 2) throw new IllegalStateException("Source metadata directory cannot be read within its bound");
        for (File file : files) if (file.getName().endsWith(".json")) result.add(file);
        if (result.size() > limit) throw new IllegalStateException("Source metadata ledger exceeds its bound"); return result;
    }

    /** Runs on the caller's worker. Unknown/corrupt evidence keeps cleanup protected. */
    private void ensureReferenceLedger() throws Exception {
        if (projects.sourceArchiveReferencesInitialized()) return;
        TRANSFER_LOCK.lockInterruptibly();
        try {
            if (projects.sourceArchiveReferencesInitialized()) return;
            for (File directory : new File[]{journals, catalogs, restoreJournals}) {
                int limit = directory.equals(catalogs) ? MAX_CATALOGS : MAX_JOURNALS;
                File[] files = directory.listFiles();
                if (files == null || files.length > limit * 2) throw new IllegalStateException("Source reference migration exceeds its metadata bound");
                java.util.HashSet<String> identities = new java.util.HashSet<>();
                for (File file : files) {
                    checkInterrupted();
                    String name = file.getName(), logicalName = name;
                    int staged = name.indexOf(".json.tmp_");
                    if (staged >= 0 && name.substring(staged + 10).matches("[a-f0-9-]{36}")) logicalName = name.substring(0, staged + 5);
                    String pattern = directory.equals(journals) ? "[a-f0-9-]{36}\\.json"
                            : directory.equals(catalogs) ? "[a-f0-9-]{36}_[a-f0-9-]{36}\\.json"
                            : "[a-f0-9-]{36}_[a-f0-9-]{36}_[a-f0-9-]{36}\\.json";
                    if (!logicalName.matches(pattern)) throw new IllegalStateException("Unknown source metadata must be recovered before cleanup can resume");
                    if (identities.add(logicalName) && identities.size() > limit) throw new IllegalStateException("Source reference migration exceeds its record bound");
                    JSONObject evidence = readJson(file); registerMetadataReferences(new File(directory, logicalName), evidence);
                }
            }
            projects.markSourceArchiveReferencesInitialized();
        } finally { TRANSFER_LOCK.unlock(); }
    }
    private void registerMetadataReferences(File file, JSONObject value) throws Exception {
        if (file.getParentFile().equals(journals))
            projects.recordSourceArchiveReferences("journal:" + stem(file), pinOwner(value.getString("requestId")), value);
        else if (file.getParentFile().equals(catalogs))
            projects.recordSourceArchiveReferences("catalog:" + stem(file), "source-catalog:" + stem(file).replace('_', ':'), value);
        else if (file.getParentFile().equals(restoreJournals))
            projects.recordSourceArchiveReferences("restore:" + stem(file), "source-restore:" + stem(file), value);
    }
    private JSONObject readJson(File file) throws Exception {
        if (!regularOwned(file)) throw new IllegalStateException("Source metadata file escaped app-owned storage");
        return ProjectMediaReferenceIndex.decodeOwnershipMetadata(readLocalBytes(file));
    }
    private void writeOwnedJson(File file, JSONObject value) throws Exception {
        ensureOwnedDirectory(file.getParentFile());
        if (!file.getCanonicalFile().equals(file.getAbsoluteFile())) throw new IllegalStateException("Source metadata path is aliased");
        byte[] bytes = metadataBytes(value);
        if (file.getParentFile().getUsableSpace() < bytes.length + 8L * 1024L * 1024L) throw new IllegalStateException("Source archive metadata reserve is unavailable");
        registerMetadataReferences(file, value);
        File temp = new File(file.getParentFile(), file.getName() + ".tmp_" + UUID.randomUUID());
        try {
            if (!temp.createNewFile()) throw new IllegalStateException("Source metadata staging file could not be reserved");
            try (FileOutputStream output = new FileOutputStream(temp)) { output.write(bytes); output.flush(); output.getFD().sync(); }
            StorageVault.commit(temp, file);
        } finally { if (temp.exists()) temp.delete(); }
    }

    private void clearJournal(File file, String requestId) throws Exception {
        if (file.exists() && !file.delete()) throw new IllegalStateException("Committed source upload journal could not be cleared");
        projects.removeSourceArchiveReferences("journal:" + stem(file), pinOwner(requestId));
    }
    private void clearRestoreIntent(File file) throws Exception {
        if (file.exists() && !file.delete()) throw new IllegalStateException("Verified source restore journal could not be cleared");
        projects.removeSourceArchiveReferences("restore:" + stem(file), "source-restore:" + stem(file));
    }
    private static String stem(File file) { return file.getName().substring(0, file.getName().length() - 5); }
    private void ensureOwnedDirectory(File directory) {
        try {
            File absolute = directory.getAbsoluteFile();
            if (!absolute.equals(root) && !absolute.getPath().startsWith(root.getPath() + File.separator)
                    || !absolute.getCanonicalFile().equals(absolute)) throw new IllegalStateException("Source vault path is outside its app-owned root or contains a symbolic link");
            if (!directory.isDirectory() && !directory.mkdirs()) throw new IllegalStateException("Source vault directory could not be created");
        } catch (java.io.IOException error) { throw new IllegalStateException("Source vault path cannot be resolved", error); }
    }
    private boolean regularOwned(File file) throws Exception {
        return file.isFile() && file.getAbsolutePath().startsWith(root.getPath() + File.separator) && file.getCanonicalFile().equals(file.getAbsoluteFile());
    }
    private static byte[] readLocalBytes(File file) throws Exception {
        if (file.length() <= 0L || file.length() > MAX_METADATA_BYTES) throw new IllegalStateException("Source archive metadata exceeds its byte bound");
        try (InputStream input = new FileInputStream(file)) { return readBounded(input, MAX_METADATA_BYTES); }
    }
    private static byte[] readBounded(InputStream input, int max) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream(Math.min(max, 16384)); byte[] buffer = new byte[16384]; int count;
        while ((count = readProgress(input, buffer, buffer.length)) != -1) {
            checkInterrupted(); if (count > max - output.size()) throw new IllegalStateException("Archive metadata exceeds its byte bound"); output.write(buffer, 0, count);
        }
        return output.toByteArray();
    }
    private static byte[] metadataBytes(JSONObject json) {
        String value = json.toString(); if (value.length() > MAX_METADATA_BYTES) throw new IllegalArgumentException("Source archive metadata exceeds 256 KiB");
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8); if (bytes.length > MAX_METADATA_BYTES) throw new IllegalArgumentException("Source archive metadata exceeds 256 KiB"); return bytes;
    }
    private static String assetProof(ProjectStore.Asset asset) throws Exception {
        JSONObject proof = new JSONObject().put("id", asset.id).put("uri", asset.uri).put("mime", asset.mime).put("durationMs", asset.durationMs)
                .put("width", asset.width).put("height", asset.height).put("rotation", asset.rotation).put("hasAudio", asset.hasAudio)
                .put("sizeBytes", asset.sizeBytes).put("importMetadata", asset.importMetadata == null ? new JSONObject() : asset.importMetadata);
        return hashBytes(canonical(proof, 0).getBytes(StandardCharsets.UTF_8));
    }
    private static String canonical(Object value, int depth) throws Exception {
        if (depth > 32) throw new IllegalArgumentException("Source identity metadata is too deep");
        if (value instanceof JSONObject) {
            JSONObject object = (JSONObject) value; ArrayList<String> keys = new ArrayList<>(); Iterator<String> iterator = object.keys();
            while (iterator.hasNext()) keys.add(iterator.next()); if (keys.size() > 4096) throw new IllegalArgumentException("Source identity metadata is too wide");
            Collections.sort(keys); StringBuilder out = new StringBuilder("{");
            for (String key : keys) { if (out.length() > 1) out.append(','); out.append(JSONObject.quote(key)).append(':').append(canonical(object.get(key), depth + 1)); }
            return out.append('}').toString();
        }
        if (value instanceof JSONArray) {
            JSONArray array = (JSONArray) value; if (array.length() > 4096) throw new IllegalArgumentException("Source identity metadata is too wide");
            StringBuilder out = new StringBuilder("["); for (int index = 0; index < array.length(); index++) { if (index > 0) out.append(','); out.append(canonical(array.get(index), depth + 1)); } return out.append(']').toString();
        }
        return value == null || value == JSONObject.NULL ? "null" : value instanceof String ? JSONObject.quote((String) value) : value.toString();
    }
    private static void sourceAsset(ProjectStore.Asset asset) {
        if (asset == null || asset.generated || !"source".equals(asset.role) || asset.uri == null || asset.uri.isEmpty() || asset.uri.length() > 16384
                || asset.mime == null || !(asset.mime.startsWith("video/") || asset.mime.startsWith("audio/") || asset.mime.startsWith("image/")))
            throw new IllegalArgumentException("Select one inspected original image, audio or video source asset");
    }
    private static void identifier(String id) { if (id == null || !id.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}")) throw new IllegalArgumentException("Trusted project and source IDs are required"); }
    private static void requestIdentity(String request) { if (request == null || !request.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}")) throw new IllegalArgumentException("A stable trusted source request identity is required"); }
    private static String slot(String project, String asset) { return uuid("source-slot:" + project + "\n" + asset); }
    private static String uuid(String identity) { return UUID.nameUUIDFromBytes(identity.getBytes(StandardCharsets.UTF_8)).toString(); }
    private static String chunkName(int index) { return String.format(Locale.ROOT, "chunk_%03d.bin", index); }
    private static String boundedName(String name) { String value = name == null || name.isEmpty() ? "Original source" : name; return value.length() > 256 ? value.substring(0, 256) : value; }
    private static String extension(String mime) {
        if ("video/mp4".equals(mime) || "audio/mp4".equals(mime)) return "mp4";
        if ("image/png".equals(mime)) return "png"; if ("image/jpeg".equals(mime)) return "jpg"; if ("image/webp".equals(mime)) return "webp";
        if ("audio/wav".equals(mime) || "audio/x-wav".equals(mime)) return "wav"; if ("audio/mpeg".equals(mime)) return "mp3"; return "media";
    }
    private static long exactLong(JSONObject object, String key) throws Exception {
        Object value = object.get(key); if (!(value instanceof Number)) throw new IllegalStateException("Archive timing/size must be exact integer metadata");
        Number number = (Number) value; if (value instanceof Long || value instanceof Integer || value instanceof Short || value instanceof Byte) return number.longValue();
        double floating = number.doubleValue(); if (!Double.isFinite(floating) || floating != Math.rint(floating) || Math.abs(floating) > 9007199254740991d) throw new IllegalStateException("Archive numeric metadata is not an exact integer"); return (long) floating;
    }
    private static String strictHash(JSONObject object, String key) throws Exception { String hash = object.getString(key); if (!hash.matches("[0-9a-f]{64}")) throw new IllegalStateException("Archive hash metadata is invalid"); return hash; }
    private static int readProgress(InputStream input, byte[] buffer, int length) throws Exception {
        for (int attempt = 0; attempt < 16; attempt++) { checkInterrupted(); int count = input.read(buffer, 0, length); if (count != 0) return count; }
        throw new IllegalStateException("Media provider repeatedly returned an empty stream read");
    }
    private static MessageDigest digest() throws Exception { return MessageDigest.getInstance("SHA-256"); }
    private static String hashBytes(byte[] bytes) throws Exception { return hex(digest().digest(bytes)); }
    private static String hex(byte[] digest) { char[] output = new char[digest.length * 2], digits = "0123456789abcdef".toCharArray(); for (int index = 0; index < digest.length; index++) { int value = digest[index] & 255; output[index * 2] = digits[value >>> 4]; output[index * 2 + 1] = digits[value & 15]; } return new String(output); }
    private static void checkInterrupted() throws InterruptedException { if (Thread.currentThread().isInterrupted()) throw new InterruptedException("Source media transfer cancelled"); }
    private static final class Archive { final JSONObject manifest, marker; Archive(JSONObject manifest, JSONObject marker) { this.manifest = manifest; this.marker = marker; } }
    private static final class HashResult { final long bytes; final String sha256; HashResult(long bytes, String hash) { this.bytes = bytes; this.sha256 = hash; } }
    private static final class Reporter {
        private final Progress progress; private long last;
        Reporter(Progress progress) { this.progress = progress; }
        void report(long completed, long total, String detail, boolean force) throws Exception { checkInterrupted(); long now = android.os.SystemClock.elapsedRealtime(); if (progress != null && (force || now - last >= 500L)) { last = now; progress.update(completed, total, detail); } }
    }
}
