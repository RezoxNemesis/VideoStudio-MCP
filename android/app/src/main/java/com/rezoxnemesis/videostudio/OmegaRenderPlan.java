package com.rezoxnemesis.videostudio;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Phase 8's bounded, side-effect-free render/recovery decision engine.
 *
 * A caller must freeze the full native Sketch document/dependency inventory,
 * compute each frame's canonical visual-input hash, and rehash the bytes of
 * every proposed reusable app-private PNG. This class NEVER reads files,
 * touches the renderer, resumes jobs or overrides NativeStartupGuard.
 * Integration is held for the integration-owner adapter review.
 */
final class OmegaRenderPlan {
    static final int MAX_FRAMES = 1200;
    static final int MAX_EDITS = 4096;
    static final long MAX_FRAME_BYTES = 8L * 1024 * 1024;
    private static final String TICKET_VERSION = "OMEGA_RENDER_RESTART_V2";
    private OmegaRenderPlan() {}

    static final class Range {
        final int first, last; // inclusive authored-frame indices
        Range(int first, int last) {
            if (first < 0 || first > last || last >= MAX_FRAMES)
                throw new IllegalArgumentException("Dirty frame bounds invalid");
            this.first = first;
            this.last = last;
        }
    }

    /** Legacy preview-only checkpoint. Not a recovery authorization. */
    static final class Checkpoint {
        final String sourceSha256;
        final long projectRevision;
        final int nextFrame;
        Checkpoint(String sourceSha256, long projectRevision, int nextFrame) {
            this.sourceSha256 = sha(sourceSha256);
            if (projectRevision < 1 || nextFrame < 0 || nextFrame > MAX_FRAMES)
                throw new IllegalArgumentException("Invalid checkpoint");
            this.projectRevision = projectRevision;
            this.nextFrame = nextFrame;
        }
    }

    /**
     * A frozen input identity. Each frame hash MUST cover every contributing
     * master/instance, exposure, key, paint/reference dependency, compositor
     * setting, and any scene effect that can change that frame. Shared output
     * settings (renderer version, canvas, crop, quality, color mode, etc.)
     * belong to the profile hash. Unknown dependencies require fresh rendering.
     */
    static final class Snapshot {
        final String sketchId, documentSha256, profileSha256;
        final long revision;
        final int frameCount, fps;
        final List<String> frameInputs;
        private final String identity;

        Snapshot(String sketchId, String documentSha256, long revision,
                 String profileSha256, int fps, List<String> frameInputs) {
            this.sketchId = identifier(sketchId);
            this.documentSha256 = sha(documentSha256);
            this.profileSha256 = sha(profileSha256);
            if (revision < 1 || revision == Long.MAX_VALUE || frameInputs == null ||
                    frameInputs.isEmpty() || frameInputs.size() > MAX_FRAMES)
                throw new IllegalArgumentException("Unbounded or invalid render snapshot");
            this.revision = revision;
            this.fps = NativeFrameClock.requireFps(fps);
            this.frameCount = frameInputs.size();
            List<String> inputs = new ArrayList<>(frameCount);
            for (String hash : frameInputs) inputs.add(sha(hash));
            this.frameInputs = Collections.unmodifiableList(inputs);
            StringBuilder manifest = new StringBuilder(80 * frameCount + 256);
            manifest.append("OMEGA_SNAPSHOT_V2\n").append(sketchId).append('\n')
                    .append(documentSha256).append('\n').append(revision).append('\n')
                    .append(profileSha256).append('\n').append(fps).append('\n')
                    .append(frameCount).append('\n');
            for (String hash : inputs) manifest.append(hash).append('\n');
            this.identity = digest(manifest.toString());
        }
        String identity() { return identity; }
    }

    /** One frame from a committed manifest, or a freshly hashed on-disk PNG. */
    static final class FrameProof {
        final int frame;
        final String frameInputSha256, pngSha256;
        final long bytes, presentationTimeUs;
        FrameProof(int frame, String frameInputSha256, String pngSha256,
                   long bytes, long presentationTimeUs) {
            if (frame < 0 || frame >= MAX_FRAMES || bytes < 32 || bytes > MAX_FRAME_BYTES ||
                    presentationTimeUs < 0)
                throw new IllegalArgumentException("Invalid frame proof bounds");
            this.frame = frame;
            this.frameInputSha256 = sha(frameInputSha256);
            this.pngSha256 = sha(pngSha256);
            this.bytes = bytes;
            this.presentationTimeUs = presentationTimeUs;
        }
    }

    /** Construct only after the native raster encoder has fully persisted a PNG. */
    static FrameProof proof(Snapshot snapshot, int frame, String pngSha256, long bytes) {
        frame(snapshot, frame);
        return new FrameProof(frame, snapshot.frameInputs.get(frame), pngSha256, bytes,
                NativeFrameClock.presentationTimeUs(frame, snapshot.fps));
    }

    /**
     * Stable raster key independent of the overall project revision: a prior
     * frame may be reused only when the *exact* canonical visual input and
     * renderer profile match AND the old persisted bytes are reverified.
     * The newly exported MP4 remains pinned to the NEW document revision.
     */
    static String rasterKey(Snapshot snapshot, int frame) {
        frame(snapshot, frame);
        return digest("OMEGA_RASTER_V2\n" + snapshot.sketchId + "\n" +
                snapshot.profileSha256 + "\n" + frame + "\n" + snapshot.fps + "\n" +
                snapshot.frameInputs.get(frame) + "\n");
    }

    static final class Plan {
        final List<Range> toRender, toReuse;
        final int renderCount, reuseCount;
        final String targetIdentity;
        private Plan(Snapshot target, boolean[] render) {
            targetIdentity = target.identity();
            toRender = runs(render, true);
            toReuse = runs(render, false);
            int changed = 0;
            for (boolean b : render) if (b) changed++;
            renderCount = changed;
            reuseCount = render.length - changed;
        }
    }

    /**
     * Fail-closed selective plan. Manifest claims and independent fresh disk
     * hashes are BOTH required for reuse; a missing proof rerenders that frame.
     * If both exist but disagree, reject the entire plan as tampering/corruption.
     * Never infer a frame's safety from an edit's bounds alone.
     */
    static Plan plan(Snapshot target, Snapshot prior,
                     List<FrameProof> committed, List<FrameProof> verifiedDisk,
                     List<Range> dirtyRanges) {
        if (target == null || dirtyRanges == null || dirtyRanges.size() > MAX_EDITS)
            throw new IllegalArgumentException("Invalid render planning inputs");
        if (prior == null && ((committed != null && !committed.isEmpty()) ||
                (verifiedDisk != null && !verifiedDisk.isEmpty())))
            throw new IllegalArgumentException("Orphan frame proofs cannot be reused");
        FrameProof[] recorded = evidence(prior, committed);
        FrameProof[] observed = evidence(prior, verifiedDisk);
        boolean[] render = new boolean[target.frameCount];
        Arrays.fill(render, true);
        boolean compatible = prior != null && prior.sketchId.equals(target.sketchId)
                && prior.profileSha256.equals(target.profileSha256)
                && prior.fps == target.fps && prior.frameCount == target.frameCount;
        if (compatible) for (int i = 0; i < render.length; i++) {
            FrameProof a = recorded[i], b = observed[i];
            if (a != null && b != null && !same(a, b))
                throw new IllegalStateException("OMEGA_FRAME_PROOF_MISMATCH at frame " + i);
            if (a != null && b != null && target.frameInputs.get(i).equals(a.frameInputSha256))
                render[i] = false;
        }
        for (Range range : dirtyRanges) {
            if (range == null || range.last >= target.frameCount)
                throw new IllegalArgumentException("Dirty frame outside snapshot");
            Arrays.fill(render, range.first, range.last + 1, true);
        }
        return new Plan(target, render);
    }

    /** Merge overlaps AND immediately adjacent ranges; all other frames stay clean. */
    static List<Range> invalidated(int frameCount, List<Range> edits) {
        if (frameCount < 1 || frameCount > MAX_FRAMES || edits == null || edits.size() > MAX_EDITS)
            throw new IllegalArgumentException("Render invalidation budget exceeded");
        boolean[] dirty = new boolean[frameCount];
        for (Range range : edits) {
            if (range == null || range.last >= frameCount)
                throw new IllegalArgumentException("Invalid dirty range");
            Arrays.fill(dirty, range.first, range.last + 1, true);
        }
        return runs(dirty, true);
    }

    /** Existing content/revision-scoped identity retained for the first slice. */
    static String cacheKey(String projectId, String contentSha256, int frame, int fps,
                           long projectRevision) {
        identifier(projectId);
        sha(contentSha256);
        if (frame < 0 || frame >= MAX_FRAMES || projectRevision < 1)
            throw new IllegalArgumentException("Invalid render input");
        NativeFrameClock.requireFps(fps);
        return digest("OMEGA_FRAME_V1\n" + projectId + "\n" + contentSha256 + "\n" +
                frame + "\n" + fps + "\n" + projectRevision + "\n");
    }

    /** Legacy compatibility check: advisory only. Never launch recovery from it. */
    static boolean mayResume(Checkpoint prior, String expectedSha256, long expectedRevision,
                             int totalFrames, boolean ownerRecoveryHeld) {
        sha(expectedSha256);
        if (totalFrames < 1 || totalFrames > MAX_FRAMES || expectedRevision < 1)
            throw new IllegalArgumentException("Invalid resume target");
        return !ownerRecoveryHeld && prior != null &&
                prior.sourceSha256.equals(expectedSha256) &&
                prior.projectRevision == expectedRevision && prior.nextFrame <= totalFrames;
    }

    /** A serialized resume proposal, NOT a capability to bypass owner admission. */
    static final class RecoveryTicket {
        final String operationId, snapshotIdentity, prefixSha256;
        final int nextFrame;
        private RecoveryTicket(String operationId, String snapshotIdentity,
                               int nextFrame, String prefixSha256) {
            this.operationId = identifier(operationId);
            this.snapshotIdentity = sha(snapshotIdentity);
            if (nextFrame < 0 || nextFrame > MAX_FRAMES)
                throw new IllegalArgumentException("Invalid restart cursor");
            this.nextFrame = nextFrame;
            this.prefixSha256 = sha(prefixSha256);
        }
        String encode() {
            return TICKET_VERSION + "\n" + operationId + "\n" + snapshotIdentity + "\n"
                    + nextFrame + "\n" + prefixSha256 + "\n";
        }
        static RecoveryTicket decode(String text) {
            if (text == null || text.length() > 320 || text.indexOf('\r') >= 0)
                throw new IllegalArgumentException("Invalid restart ticket size/format");
            String[] fields = text.split("\n", -1);
            if (fields.length != 6 || !TICKET_VERSION.equals(fields[0]) ||
                    !fields[5].isEmpty() || !fields[3].matches("0|[1-9][0-9]{0,3}"))
                throw new IllegalArgumentException("Invalid restart ticket format/version");
            return new RecoveryTicket(fields[1], fields[2],
                    Integer.parseInt(fields[3]), fields[4]);
        }
    }

    /** Evidence MUST be ordered and contiguous from frame 0 to nextFrame-1. */
    static RecoveryTicket checkpoint(Snapshot snapshot, String operationId,
                                     List<FrameProof> completed) {
        if (snapshot == null || completed == null || completed.size() > snapshot.frameCount)
            throw new IllegalArgumentException("Invalid render checkpoint");
        return new RecoveryTicket(operationId, snapshot.identity(), completed.size(),
                prefix(snapshot, completed));
    }

    /**
     * A recovered process may restart ONLY after its native owner-controlled
     * guard separately admits that recovery. Every committed PNG in the prefix
     * must still exist with the same verified bytes and frame-clock position.
     * A completed encode (cursor==frameCount) is not a resumable frame render.
     */
    static boolean mayResumeVerified(RecoveryTicket ticket, Snapshot frozen,
                                     String expectedOperationId,
                                     List<FrameProof> recorded, List<FrameProof> observed,
                                     boolean ownerRecoveryHeld, boolean recoveryAdmitted) {
        if (ownerRecoveryHeld || !recoveryAdmitted || ticket == null || frozen == null ||
                expectedOperationId == null || !ticket.operationId.equals(expectedOperationId) ||
                !ticket.snapshotIdentity.equals(frozen.identity()) ||
                ticket.nextFrame >= frozen.frameCount) return false;
        if (recorded == null || observed == null ||
                recorded.size() != ticket.nextFrame || observed.size() != ticket.nextFrame)
            return false;
        try {
            if (!ticket.prefixSha256.equals(prefix(frozen, recorded))) return false;
            for (int i = 0; i < recorded.size(); i++) {
                validateProof(frozen, observed.get(i), i);
                if (!same(recorded.get(i), observed.get(i))) return false;
            }
            return true;
        } catch (IllegalArgumentException | IllegalStateException corrupt) {
            return false;
        }
    }

    private static String prefix(Snapshot snapshot, List<FrameProof> proofs) {
        StringBuilder data = new StringBuilder("OMEGA_COMMITTED_PREFIX_V2\n");
        data.append(snapshot.identity()).append('\n');
        for (int i = 0; i < proofs.size(); i++) {
            FrameProof proof = proofs.get(i);
            validateProof(snapshot, proof, i);
            data.append(proof.frame).append('\n').append(proof.frameInputSha256)
                    .append('\n').append(proof.pngSha256).append('\n')
                    .append(proof.bytes).append('\n').append(proof.presentationTimeUs).append('\n');
        }
        return digest(data.toString());
    }

    private static FrameProof[] evidence(Snapshot prior, List<FrameProof> proofs) {
        if (prior == null) {
            if (proofs != null && !proofs.isEmpty())
                throw new IllegalArgumentException("Proofs require prior frozen snapshot");
            return new FrameProof[0];
        }
        if (proofs == null || proofs.size() > prior.frameCount)
            throw new IllegalArgumentException("Invalid or excessive prior evidence");
        FrameProof[] result = new FrameProof[prior.frameCount];
        for (FrameProof proof : proofs) {
            if (proof == null || proof.frame >= prior.frameCount || result[proof.frame] != null)
                throw new IllegalArgumentException("Duplicate/missing frame proof identity");
            validateProof(prior, proof, proof.frame);
            result[proof.frame] = proof;
        }
        return result;
    }

    private static void validateProof(Snapshot snapshot, FrameProof proof, int index) {
        if (snapshot == null || proof == null || index < 0 || index >= snapshot.frameCount ||
                proof.frame != index || !snapshot.frameInputs.get(index).equals(proof.frameInputSha256) ||
                proof.presentationTimeUs != NativeFrameClock.presentationTimeUs(index, snapshot.fps))
            throw new IllegalArgumentException("Proof does not match frozen frame inputs/clock");
    }

    private static boolean same(FrameProof a, FrameProof b) {
        return a.frame == b.frame && a.bytes == b.bytes &&
                a.presentationTimeUs == b.presentationTimeUs &&
                a.frameInputSha256.equals(b.frameInputSha256) &&
                a.pngSha256.equals(b.pngSha256);
    }

    private static List<Range> runs(boolean[] mask, boolean selected) {
        List<Range> out = new ArrayList<>();
        int start = -1;
        for (int i = 0; i <= mask.length; i++) {
            boolean active = i < mask.length && mask[i] == selected;
            if (active && start < 0) start = i;
            if (!active && start >= 0) {
                out.add(new Range(start, i - 1));
                start = -1;
            }
        }
        return Collections.unmodifiableList(out);
    }

    private static void frame(Snapshot snapshot, int index) {
        if (snapshot == null || index < 0 || index >= snapshot.frameCount)
            throw new IllegalArgumentException("Frame outside frozen snapshot");
    }

    private static String identifier(String id) {
        if (id == null || id.isEmpty() || id.length() > 100)
            throw new IllegalArgumentException("Invalid Sketch/render operation ID");
        for (int i = 0; i < id.length(); i++) {
            char c = id.charAt(i);
            if (!(c >= 'A' && c <= 'Z' || c >= 'a' && c <= 'z' ||
                    c >= '0' && c <= '9' || c == '_' || c == '-'))
                throw new IllegalArgumentException("Invalid Sketch/render operation ID");
        }
        return id;
    }

    private static String sha(String value) {
        if (value == null || value.length() != 64)
            throw new IllegalArgumentException("Expected lowercase SHA-256");
        for (int i = 0; i < 64; i++) {
            char c = value.charAt(i);
            if (!(c >= 'a' && c <= 'f' || c >= '0' && c <= '9'))
                throw new IllegalArgumentException("Expected lowercase SHA-256");
        }
        return value;
    }

    private static String digest(String value) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            char[] out = new char[hash.length * 2];
            char[] symbols = "0123456789abcdef".toCharArray();
            for (int i = 0; i < hash.length; i++) {
                out[2 * i] = symbols[(hash[i] >>> 4) & 15];
                out[2 * i + 1] = symbols[hash[i] & 15];
            }
            return new String(out);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }
}
