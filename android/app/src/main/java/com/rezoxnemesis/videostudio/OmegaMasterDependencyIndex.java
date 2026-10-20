package com.rezoxnemesis.videostudio;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * OMEGA Phase 1 additive core. Deterministic, read-only impact planner for
 * versioned cross-sketch artwork references. This never mutates project files:
 * the native project store must provide transactionally pinned adapters first.
 */
final class OmegaMasterDependencyIndex {
    static final int MAX_REFERENCES = 4096;
    private OmegaMasterDependencyIndex() {}

    static final class Link {
        final String masterId, projectId, sketchId, objectId, sourceSha256;
        final long masterVersion, sketchRevision;
        final boolean locked, localAppearanceOverride;
        Link(String masterId, String projectId, String sketchId, String objectId,
             String sourceSha256, long masterVersion, long sketchRevision,
             boolean locked, boolean localAppearanceOverride) {
            this.masterId = id(masterId);
            this.projectId = id(projectId);
            this.sketchId = id(sketchId);
            this.objectId = id(objectId);
            this.sourceSha256 = sha(sourceSha256);
            if (masterVersion < 1 || sketchRevision < 1)
                throw new IllegalArgumentException("Revisions must be positive");
            this.masterVersion = masterVersion;
            this.sketchRevision = sketchRevision;
            this.locked = locked;
            this.localAppearanceOverride = localAppearanceOverride;
        }
        String identity() { return projectId + "/" + sketchId + "/" + objectId; }
    }

    static final class Preview {
        final String masterId, sourceSha256;
        final long expectedMasterVersion;
        final List<Link> affected;
        final List<String> blockers;
        final int overrideCount;
        Preview(String masterId, String sourceSha256, long version, List<Link> affected,
                List<String> blockers, int overrideCount) {
            this.masterId = masterId;
            this.sourceSha256 = sourceSha256;
            this.expectedMasterVersion = version;
            this.affected = Collections.unmodifiableList(affected);
            this.blockers = Collections.unmodifiableList(blockers);
            this.overrideCount = overrideCount;
        }
        boolean ready() { return blockers.isEmpty(); }
        void requireReady() {
            if (!ready()) throw new IllegalStateException("MASTER_IMPACT_BLOCKED: " + blockers);
        }
    }

    static Preview preview(List<Link> index, String masterId, String sourceSha256, long expectedVersion) {
        id(masterId);
        sha(sourceSha256);
        if (expectedVersion < 1) throw new IllegalArgumentException("Invalid master version");
        if (index == null || index.size() > MAX_REFERENCES)
            throw new IllegalArgumentException("Dependency index out of bounds");
        List<Link> hits = new ArrayList<>();
        List<String> blockers = new ArrayList<>();
        Set<String> identities = new HashSet<>();
        int overrides = 0;
        for (Link link : index) {
            if (link == null) throw new IllegalArgumentException("Null artwork dependency");
            if (!identities.add(link.identity()))
                throw new IllegalArgumentException("Duplicate object dependency: " + link.identity());
            if (!masterId.equals(link.masterId)) continue;
            hits.add(link);
            if (!sourceSha256.equals(link.sourceSha256))
                blockers.add("SOURCE_HASH_CHANGED:" + link.identity());
            if (link.masterVersion != expectedVersion)
                blockers.add("STALE_MASTER_VERSION:" + link.identity());
            if (link.locked) blockers.add("LOCKED_INSTANCE:" + link.identity());
            if (link.localAppearanceOverride) overrides++;
        }
        hits.sort(Comparator.comparing(Link::identity));
        blockers.sort(String::compareTo);
        if (hits.isEmpty()) blockers.add("MASTER_HAS_NO_LINKED_INSTANCES");
        return new Preview(masterId, sourceSha256, expectedVersion, hits, blockers, overrides);
    }

    private static String id(String value) {
        if (value == null || !value.matches("[A-Za-z0-9_-]{1,100}"))
            throw new IllegalArgumentException("Invalid identity");
        return value;
    }
    private static String sha(String value) {
        if (value == null || !value.matches("[0-9a-f]{64}"))
            throw new IllegalArgumentException("Expected lowercase SHA-256 hex");
        return value;
    }
}