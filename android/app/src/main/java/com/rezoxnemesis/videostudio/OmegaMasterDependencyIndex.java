package com.rezoxnemesis.videostudio;

import java.util.ArrayList;
import org.json.JSONArray;
import org.json.JSONObject;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
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

    /*
     * OMEGA Phase 1 compound / cross-shot contract.
     *
     * A compound is a deterministic, ordered group of the original "path"
     * masters. No new SketchDocument kind is introduced, so old sketches and
     * SketchMasterAssets validations stay intact. A shared authoritative native
     * store must acquire the COMPLETE dependency membership and commit all
     * staged snapshots in ONE SQLite transaction. Never implement the adapter
     * using a loop of SketchProjectStore.mutate(): that would permit half a
     * character to change after a later failure.
     */
    static final int MAX_COMPOUND_PARTS = 32;
    static final int MAX_BATCH_SKETCHES = 24;
    static final int MAX_COMBINED_SNAPSHOT_BYTES = 16 * 1024 * 1024;

    static final class PartChange {
        final String masterId, expectedArtworkSha256;
        final long expectedVersion;
        private final JSONObject patch;

        PartChange(String masterId, long expectedVersion, String expectedArtworkSha256,
                   JSONObject patch) throws Exception {
            this.masterId = id(masterId);
            if (expectedVersion < 1 || expectedVersion >= Long.MAX_VALUE - 2)
                throw new IllegalArgumentException("Invalid compound part master version");
            this.expectedVersion = expectedVersion;
            this.expectedArtworkSha256 = sha(expectedArtworkSha256);
            if (patch == null || patch.length() == 0)
                throw new IllegalArgumentException("Empty compound part patch");
            SketchDocument.fields(patch, "commands,color,fill,width,gradient");
            this.patch = SketchDocument.copy(patch);
        }

        JSONObject operation() throws Exception {
            return new JSONObject().put("type", "master_patch")
                .put("masterId", masterId).put("expectedVersion", expectedVersion)
                .put("patch", SketchDocument.copy(patch));
        }

        JSONObject identity() throws Exception {
            return new JSONObject().put("masterId", masterId)
                .put("expectedVersion", expectedVersion)
                .put("sourceArtworkSha256", expectedArtworkSha256)
                .put("patch", SketchDocument.copy(patch));
        }
    }

    /**
     * Membership must come from a store-held exact dependency ledger, not
     * from an agent guessing which sketches contain matching objects.
     */
    static final class CompoundRequest {
        final String compoundId, projectId, operationId;
        final List<PartChange> parts;
        final Set<String> registeredSketchIds;

        CompoundRequest(String compoundId, String projectId, String operationId,
                        List<PartChange> parts, Set<String> registeredSketchIds) {
            this.compoundId = id(compoundId);
            this.projectId = id(projectId);
            this.operationId = id(operationId);
            if (parts == null || parts.size() < 1 || parts.size() > MAX_COMPOUND_PARTS)
                throw new IllegalArgumentException("A compound needs 1..32 path parts");
            if (registeredSketchIds == null || registeredSketchIds.size() < 1
                    || registeredSketchIds.size() > MAX_BATCH_SKETCHES)
                throw new IllegalArgumentException("A compound needs 1..24 registered sketches");
            Set<String> masterIds = new HashSet<>();
            List<PartChange> sorted = new ArrayList<>(parts);
            for (PartChange part : sorted) {
                if (part == null || !masterIds.add(part.masterId))
                    throw new IllegalArgumentException("Duplicate/null compound path master");
            }
            sorted.sort(Comparator.comparing(p -> p.masterId));
            this.parts = Collections.unmodifiableList(sorted);
            TreeSet<String> sketches = new TreeSet<>();
            for (String sketchId : registeredSketchIds) sketches.add(id(sketchId));
            this.registeredSketchIds = Collections.unmodifiableSet(sketches);
        }
    }

    static final class CompoundPreview {
        final String compoundId, operationId, fingerprint;
        final List<Link> affected;
        final List<String> blockers;
        final int localOverrideCount, totalExposureFrames;
        final Map<String, Long> expectedRevisions;
        final Map<String, String> sourceDocumentHashes;

        CompoundPreview(String compoundId, String operationId, String fingerprint,
                        List<Link> affected, List<String> blockers, int localOverrideCount,
                        int totalExposureFrames, Map<String, Long> expectedRevisions,
                        Map<String, String> sourceDocumentHashes) {
            this.compoundId = compoundId;
            this.operationId = operationId;
            this.fingerprint = fingerprint;
            this.affected = Collections.unmodifiableList(new ArrayList<>(affected));
            this.blockers = Collections.unmodifiableList(new ArrayList<>(blockers));
            this.localOverrideCount = localOverrideCount;
            this.totalExposureFrames = totalExposureFrames;
            this.expectedRevisions = Collections.unmodifiableMap(
                new LinkedHashMap<>(expectedRevisions));
            this.sourceDocumentHashes = Collections.unmodifiableMap(
                new LinkedHashMap<>(sourceDocumentHashes));
        }
        boolean ready() { return blockers.isEmpty(); }
        void requireReady() {
            if (!ready()) throw new IllegalStateException("COMPOUND_MASTER_BLOCKED: " + blockers);
        }
        JSONObject receipt() throws Exception {
            JSONArray instances = new JSONArray(), blockersArray = new JSONArray();
            for (Link link : affected) instances.put(new JSONObject()
                .put("masterId", link.masterId).put("projectId", link.projectId)
                .put("sketchId", link.sketchId).put("objectId", link.objectId)
                .put("sourceRevision", link.sketchRevision)
                .put("localAppearanceOverride", link.localAppearanceOverride)
                .put("locked", link.locked));
            for (String blocker : blockers) blockersArray.put(blocker);
            return new JSONObject().put("readOnly", true)
                .put("compoundId", compoundId).put("operationId", operationId)
                .put("fingerprint", fingerprint).put("ready", ready())
                .put("affectedObjects", instances).put("blockers", blockersArray)
                .put("localOverrideCount", localOverrideCount)
                .put("instanceExposureFrames", totalExposureFrames)
                .put("requiresNativeAtomicBatchStore", true)
                .put("qualityCertified", false).put("installed", false);
        }
    }

    /**
     * A staged, *uncommitted* native document edit. Defensive snapshots protect
     * callers from accidentally changing a source while inspecting the plan.
     * The eventual integration owner must add authoritative CAS+receipt/history
     * to SketchProjectStore using one SQLite transaction for all sketch IDs.
     */
    static final class PreparedBatch {
        final String compoundId, operationId, fingerprint;
        final CompoundPreview impact;
        private final Map<String, JSONObject> before, after;
        private final int changedCount;

        PreparedBatch(CompoundPreview impact, Map<String, JSONObject> before,
                      Map<String, JSONObject> after) throws Exception {
            this.compoundId = impact.compoundId;
            this.operationId = impact.operationId;
            this.fingerprint = impact.fingerprint;
            this.impact = impact;
            this.before = copied(before);
            this.after = copied(after);
            int changes=0;
            for(String sketchId:before.keySet()) {
                if(!SketchDocument.canonical(before.get(sketchId))
                    .equals(SketchDocument.canonical(after.get(sketchId)))) changes++;
            }
            this.changedCount=changes;
        }
        Map<String, JSONObject> originalDocuments() throws Exception {
            return copied(before);
        }
        Map<String, JSONObject> nextDocuments() throws Exception {
            return copied(after);
        }
        int changedSketches() { return changedCount; }
        private static Map<String, JSONObject> copied(Map<String, JSONObject> docs)
                throws Exception {
            Map<String, JSONObject> result = new TreeMap<>();
            for (Map.Entry<String, JSONObject> e : docs.entrySet())
                result.put(e.getKey(), SketchDocument.copy(e.getValue()));
            return Collections.unmodifiableMap(result);
        }
        /** No sequential fallback: only a transactional store adapter may call this. */
        void commitAtomically(AtomicBatchCommitter committer) throws Exception {
            if (committer == null)
                throw new IllegalStateException("OMEGA_ATOMIC_NATIVE_ADAPTER_REQUIRED");
            committer.commit(this);
        }
    }

    interface AtomicBatchCommitter {
        /**
         * Under one SQLite writer transaction: (1) check operation receipt,
         * (2) recheck ALL source revision/hash pins and full membership,
         * (3) validate ownership + active original-drawing sessions,
         * (4) write ALL documents, history, live events and a global group
         * receipt, (5) commit once. Any error must roll back every write.
         * Undo/redo of a compound must likewise be a single group transaction.
         */
        void commit(PreparedBatch batch) throws Exception;
    }

    /**
     * Read-only impact enumeration across an exact registered set of sketches.
     * It never edits an input document or tries to bypass layer locks.
     */
    static CompoundPreview previewCompound(CompoundRequest request,
                                           Map<String, JSONObject> snapshots)
            throws Exception {
        if (request == null || snapshots == null || snapshots.size() < 1
                || snapshots.size() > MAX_BATCH_SKETCHES)
            throw new IllegalArgumentException("Invalid compound snapshot set");
        Map<String, JSONObject> sorted = new TreeMap<>();
        for (Map.Entry<String, JSONObject> e : snapshots.entrySet()) {
            String key = id(e.getKey());
            if (e.getValue() == null)
                throw new IllegalArgumentException("Null compound sketch snapshot");
            if (!key.equals(e.getValue().getString("id")))
                throw new IllegalArgumentException("Sketch ID does not match its snapshot key");
            sorted.put(key, e.getValue());
        }
        List<String> blockers = new ArrayList<>();
        if (!sorted.keySet().equals(request.registeredSketchIds))
            blockers.add("INCOMPLETE_DEPENDENCY_MEMBERSHIP");
        long byteCount = 0;
        Map<String, Long> revisions = new LinkedHashMap<>();
        Map<String, String> documentHashes = new LinkedHashMap<>();
        List<Link> impact = new ArrayList<>();
        Set<String> partCoverage = new HashSet<>();
        int localOverrides = 0;
        long exposure = 0;
        JSONArray docFingerprints = new JSONArray();
        for (Map.Entry<String, JSONObject> entry : sorted.entrySet()) {
            String sketchId = entry.getKey();
            JSONObject doc = entry.getValue();
            SketchDocument.validate(doc);
            String canonical = SketchDocument.canonical(doc);
            byteCount += canonical.getBytes(StandardCharsets.UTF_8).length;
            if (byteCount > MAX_COMBINED_SNAPSHOT_BYTES)
                throw new IllegalArgumentException("Compound document byte budget exceeded");
            long revision = doc.getLong("revision");
            String docHash = digest(canonical);
            revisions.put(sketchId, revision);
            documentHashes.put(sketchId, docHash);
            docFingerprints.put(new JSONObject().put("sketchId", sketchId)
                .put("revision", revision).put("documentSha256", docHash));
            Map<String, JSONObject> masters = SketchMasterAssets.indexed(doc);
            Set<String> found = new HashSet<>();
            for (PartChange part : request.parts) {
                JSONObject master = masters.get(part.masterId);
                if (master == null) {
                    blockers.add("MISSING_COMPOUND_PART:" + sketchId + "/" + part.masterId);
                    continue;
                }
                if (master.getLong("version") != part.expectedVersion)
                    blockers.add("STALE_PART_VERSION:" + sketchId + "/" + part.masterId);
                if (!digest(SketchDocument.canonical(master.getJSONObject("artwork")))
                        .equals(part.expectedArtworkSha256))
                    blockers.add("MASTER_ARTWORK_DRIFT:" + sketchId + "/" + part.masterId);
                found.add(part.masterId);
            }
            JSONArray layers = doc.getJSONArray("layers");
            for (int li = 0; li < layers.length(); li++) {
                JSONObject layer = layers.getJSONObject(li);
                boolean locked = layer.getBoolean("locked");
                JSONArray objects = layer.getJSONArray("objects");
                for (int oi = 0; oi < objects.length(); oi++) {
                    JSONObject object = objects.getJSONObject(oi);
                    JSONObject link = object.optJSONObject("masterLink");
                    if (link == null || !found.contains(link.getString("id"))) continue;
                    if (impact.size() >= MAX_REFERENCES)
                        throw new IllegalArgumentException("Too many linked compound instances");
                    boolean overridden = link.getJSONObject("overrides").length() > 0;
                    Link item = new Link(link.getString("id"), request.projectId, sketchId,
                        object.getString("id"), digest(SketchDocument.canonical(
                            masters.get(link.getString("id")).getJSONObject("artwork"))),
                        link.getLong("version"), revision, locked, overridden);
                    impact.add(item);
                    partCoverage.add(item.masterId);
                    if (locked) blockers.add("LOCKED_LAYER:" + sketchId + "/" + layer.getString("id"));
                    if (overridden) localOverrides++;
                    exposure += object.getLong("exposure");
                    if (exposure > Integer.MAX_VALUE)
                        throw new IllegalArgumentException("Compound exposure count overflow");
                }
            }
        }
        for (PartChange part : request.parts) {
            if (!partCoverage.contains(part.masterId))
                blockers.add("UNREFERENCED_COMPOUND_PART:" + part.masterId);
        }
        if (impact.isEmpty()) blockers.add("NO_COMPOUND_INSTANCES");
        impact.sort(Comparator.comparing(Link::identity).thenComparing(l -> l.masterId));
        Collections.sort(blockers);
        JSONArray definitions = new JSONArray();
        for (PartChange part : request.parts) definitions.put(part.identity());
        JSONArray members = new JSONArray();
        for (String key : request.registeredSketchIds) members.put(key);
        String fingerprint = digest(SketchDocument.canonical(new JSONObject()
            .put("contract", "omega-compound-master-v1")
            .put("compoundId", request.compoundId)
            .put("projectId", request.projectId)
            .put("operationId", request.operationId)
            .put("parts", definitions).put("registeredSketchIds", members)
            .put("snapshots", docFingerprints)));
        return new CompoundPreview(request.compoundId, request.operationId, fingerprint,
            impact, blockers, localOverrides, (int) exposure, revisions, documentHashes);
    }

    /** Stages an entire group. Any missing/locked/stale part fails without writes. */
    static PreparedBatch stageCompound(CompoundRequest request,
                                       Map<String, JSONObject> snapshots) throws Exception {
        CompoundPreview preview = previewCompound(request, snapshots);
        preview.requireReady();
        Map<String, JSONObject> before = new TreeMap<>(), after = new TreeMap<>();
        for (String sketchId : new TreeSet<>(snapshots.keySet())) {
            JSONObject prior = SketchDocument.copy(snapshots.get(sketchId));
            JSONObject next = SketchDocument.copy(prior);
            Map<String, JSONObject> originals = SketchMasterAssets.indexed(prior);
            for (PartChange part : request.parts) {
                JSONObject beforeMaster = originals.get(part.masterId);
                SketchMasterAssets.apply(next, part.operation());
                JSONObject afterMaster = SketchMasterAssets.indexed(next).get(part.masterId);
                if (SketchDocument.canonical(beforeMaster.getJSONObject("artwork"))
                        .equals(SketchDocument.canonical(afterMaster.getJSONObject("artwork"))))
                    throw new IllegalArgumentException("NO_OP_COMPOUND_PART:" + part.masterId);
            }
            next.put("revision", Math.addExact(prior.getLong("revision"), 1L));
            SketchDocument.validate(next);
            ensureAnimationUntouched(prior, next);
            before.put(sketchId, prior);
            after.put(sketchId, next);
        }
        return new PreparedBatch(preview, before, after);
    }

    /** Explicitly guards all local keys, timing, placement and override identity. */
    private static void ensureAnimationUntouched(JSONObject before, JSONObject after)
            throws Exception {
        JSONArray firstLayers = before.getJSONArray("layers"), nextLayers = after.getJSONArray("layers");
        if (firstLayers.length() != nextLayers.length())
            throw new IllegalStateException("COMPOUND_LAYER_COUNT_CHANGED");
        for (int i = 0; i < firstLayers.length(); i++) {
            JSONObject first = firstLayers.getJSONObject(i), next = nextLayers.getJSONObject(i);
            if (!first.getString("id").equals(next.getString("id"))
                    || !sameWithoutObjects(first, next))
                throw new IllegalStateException("COMPOUND_LAYER_METADATA_CHANGED");
            JSONArray a = first.getJSONArray("objects"), b = next.getJSONArray("objects");
            if (a.length() != b.length())
                throw new IllegalStateException("COMPOUND_OBJECT_COUNT_CHANGED");
            for (int j = 0; j < a.length(); j++) {
                JSONObject left = SketchDocument.copy(a.getJSONObject(j));
                JSONObject right = SketchDocument.copy(b.getJSONObject(j));
                if (!left.getString("id").equals(right.getString("id")))
                    throw new IllegalStateException("COMPOUND_OBJECT_IDENTITY_CHANGED");
                for (String appearance : new String[]{"commands","color","fill","width","gradient"}) {
                    left.remove(appearance);
                    right.remove(appearance);
                }
                // Only the master revision itself is expected to advance.
                if (left.has("masterLink")) left.getJSONObject("masterLink").remove("version");
                if (right.has("masterLink")) right.getJSONObject("masterLink").remove("version");
                if (!SketchDocument.canonical(left).equals(SketchDocument.canonical(right)))
                    throw new IllegalStateException("COMPOUND_LOCAL_MOTION_OR_OVERRIDES_CHANGED");
            }
        }
    }

    private static boolean sameWithoutObjects(JSONObject one, JSONObject two) throws Exception {
        JSONObject a = SketchDocument.copy(one), b = SketchDocument.copy(two);
        a.remove("objects"); b.remove("objects");
        return SketchDocument.canonical(a).equals(SketchDocument.canonical(b));
    }

    private static String digest(String canonicalText) throws Exception {
        return SketchDocument.sha256(canonicalText.getBytes(StandardCharsets.UTF_8));
    }


    /**
     * Stages a *new* shot's placement of the compound without copying pixels or
     * mutating any source document. The target receives matching, independently
     * editable path masters and precisely positioned linked instances.
     *
     * This operation remains uncommitted until the native global store validates
     * the existing membership ledger and atomically registers the new shot.
     */
    static PreparedBatch stageCompoundPlacement(CompoundRequest request,
            Map<String,JSONObject> snapshots, String sourceSketchId, String targetSketchId,
            String targetLayerId, Map<String,String> newObjectsByMaster,
            int frame, int exposure, JSONObject transform, JSONArray keys) throws Exception {
        if (request == null || snapshots == null || snapshots.size() < 2
                || snapshots.size() > MAX_BATCH_SKETCHES)
            throw new IllegalArgumentException("Placement requires source and target snapshots");
        String sourceId=id(sourceSketchId), targetId=id(targetSketchId), layerId=id(targetLayerId);
        if(sourceId.equals(targetId) || !snapshots.containsKey(sourceId)
            || !snapshots.containsKey(targetId)
            || !snapshots.keySet().equals(request.registeredSketchIds))
            throw new IllegalArgumentException("Incomplete compound placement membership");
        if(newObjectsByMaster==null || newObjectsByMaster.size()!=request.parts.size()
                || transform==null || keys==null)
            throw new IllegalArgumentException("Placement identity, keys or transform unavailable");
        Set<String> uniqueObjectIds=new HashSet<>();
        for(PartChange part:request.parts){
            String objectId=id(newObjectsByMaster.get(part.masterId));
            if(!uniqueObjectIds.add(objectId))
                throw new IllegalArgumentException("Duplicate compound placement object ID");
        }

        Map<String,JSONObject> original=new TreeMap<>(), nextDocuments=new TreeMap<>();
        for(Map.Entry<String,JSONObject> entry:snapshots.entrySet()) {
            String key=id(entry.getKey());
            JSONObject document=SketchDocument.copy(entry.getValue());
            if(!key.equals(document.getString("id")))
                throw new IllegalArgumentException("Sketch key/identity mismatch");
            SketchDocument.validate(document);
            original.put(key,document);
            nextDocuments.put(key,SketchDocument.copy(document));
        }
        JSONObject source=original.get(sourceId), beforeTarget=original.get(targetId);
        if(source.getInt("width")!=beforeTarget.getInt("width")
            ||source.getInt("height")!=beforeTarget.getInt("height")
            ||source.getInt("fps")!=beforeTarget.getInt("fps"))
            throw new IllegalArgumentException("Compound shot geometry/frame clock mismatch");
        if(frame<0||exposure<1||(long)frame+exposure>beforeTarget.getInt("frameCount"))
            throw new IllegalArgumentException("Placement frame/exposure out of bounds");

        Map<String,JSONObject> sourceMasters=SketchMasterAssets.indexed(source);
        JSONObject target=nextDocuments.get(targetId);
        JSONObject layer=SketchDocument.layer(target,layerId);
        SketchEditOps.requireUnlocked(layer);
        for(PartChange part:request.parts) {
            JSONObject origin=sourceMasters.get(part.masterId);
            if(origin==null || origin.getLong("version")!=part.expectedVersion
                ||!digest(SketchDocument.canonical(origin.getJSONObject("artwork")))
                    .equals(part.expectedArtworkSha256))
                throw new IllegalStateException("COMPOUND_SOURCE_CHANGED:"+part.masterId);
            JSONObject installed=SketchMasterAssets.indexed(target).get(part.masterId);
            if(installed==null) {
                JSONArray masters=target.optJSONArray("masterAssets");
                if(masters==null){masters=new JSONArray();target.put("masterAssets",masters);}
                if(masters.length()>=SketchMasterAssets.MAX_MASTERS)
                    throw new IllegalArgumentException("Compound target master limit exceeded");
                masters.put(SketchDocument.copy(origin));
            } else if(installed.getLong("version")!=part.expectedVersion
                || !digest(SketchDocument.canonical(installed.getJSONObject("artwork")))
                    .equals(part.expectedArtworkSha256)) {
                throw new IllegalStateException("COMPOUND_TARGET_MASTER_CONFLICT:"+part.masterId);
            }
            JSONObject place=new JSONObject().put("type","master_instance")
                .put("masterId",part.masterId).put("layerId",layerId)
                .put("objectId",newObjectsByMaster.get(part.masterId))
                .put("frame",frame).put("exposure",exposure)
                .put("transform",SketchDocument.copy(transform))
                .put("keys",new JSONArray(keys.toString()));
            SketchMasterAssets.apply(target,place);
        }
        target.put("revision",Math.addExact(beforeTarget.getLong("revision"),1L));
        SketchDocument.validate(target);
        requirePriorContentUntouched(beforeTarget,target);
        CompoundPreview afterImpact=previewCompound(request,nextDocuments);
        afterImpact.requireReady();

        JSONArray members=new JSONArray(),parts=new JSONArray();
        for(String sketchId:new TreeSet<>(original.keySet())){
            members.put(new JSONObject().put("sketchId",sketchId)
                .put("revision",original.get(sketchId).getLong("revision"))
                .put("documentSha256",digest(SketchDocument.canonical(original.get(sketchId)))));
        }
        for(PartChange part:request.parts)parts.put(part.identity());
        JSONObject newIds=new JSONObject();
        for(PartChange part:request.parts)
            newIds.put(part.masterId,newObjectsByMaster.get(part.masterId));
        String fingerprint=digest(SketchDocument.canonical(new JSONObject()
            .put("contract","omega-compound-placement-v1")
            .put("compoundId",request.compoundId).put("projectId",request.projectId)
            .put("operationId",request.operationId)
            .put("sourceSketchId",sourceId).put("targetSketchId",targetId)
            .put("targetLayerId",layerId).put("newObjectIds",newIds)
            .put("frame",frame).put("exposure",exposure)
            .put("transform",SketchDocument.copy(transform))
            .put("keys",new JSONArray(keys.toString()))
            .put("parts",parts).put("priorSnapshots",members)));
        Map<String,Long> pinnedRevisions=new TreeMap<>();
        Map<String,String> pinnedHashes=new TreeMap<>();
        for(Map.Entry<String,JSONObject> entry:original.entrySet()){
            pinnedRevisions.put(entry.getKey(),entry.getValue().getLong("revision"));
            pinnedHashes.put(entry.getKey(),digest(SketchDocument.canonical(entry.getValue())));
        }
        CompoundPreview pinnedImpact=new CompoundPreview(
            request.compoundId,request.operationId,fingerprint,afterImpact.affected,
            afterImpact.blockers,afterImpact.localOverrideCount,
            afterImpact.totalExposureFrames,pinnedRevisions,pinnedHashes);
        return new PreparedBatch(pinnedImpact,original,nextDocuments);
    }

    /** Existing untouched objects must not be rewritten by compound placement. */
    private static void requirePriorContentUntouched(JSONObject prior,JSONObject after)
            throws Exception {
        JSONArray beforeLayers=prior.getJSONArray("layers"),afterLayers=after.getJSONArray("layers");
        if(beforeLayers.length()!=afterLayers.length())
            throw new IllegalStateException("COMPOUND_PLACEMENT_LAYER_CHANGED");
        for(int li=0;li<beforeLayers.length();li++){
            JSONObject a=beforeLayers.getJSONObject(li),b=afterLayers.getJSONObject(li);
            if(!sameWithoutObjects(a,b))
                throw new IllegalStateException("COMPOUND_PLACEMENT_LAYER_CHANGED");
            JSONArray originals=a.getJSONArray("objects"),updated=b.getJSONArray("objects");
            if(originals.length()>updated.length())
                throw new IllegalStateException("COMPOUND_PLACEMENT_REMOVED_OBJECTS");
            for(int oi=0;oi<originals.length();oi++){
                if(!SketchDocument.canonical(originals.getJSONObject(oi))
                    .equals(SketchDocument.canonical(updated.getJSONObject(oi))))
                    throw new IllegalStateException("COMPOUND_PLACEMENT_EDITED_EXISTING_ART");
            }
        }
    }


    /**
     * Stages a **global** revert of the exact previously committed group.
     * The native store MUST check the original group receipt, read all source
     * documents under one writer lock and persist this inverse as one atomic
     * undo. An unrelated intervening edit (even in one shot) fails closed.
     *
     * Reverting prior snapshots is different from detaching a master: master
     * geometry, grouped links, overrides and per-shot keys return together.
     * The revision is still monotonically incremented on each changed sketch.
     */
    static PreparedBatch stageCompoundUndo(PreparedBatch committed,
            Map<String,JSONObject> currentDocuments, String undoOperationId)
            throws Exception {
        if(committed==null||currentDocuments==null
            ||!currentDocuments.keySet().equals(committed.after.keySet()))
            throw new IllegalArgumentException("Group undo requires exact staged membership");
        String undoId=id(undoOperationId);
        if(undoId.equals(committed.operationId))
            throw new IllegalArgumentException("Undo requires a fresh global operation ID");
        Map<String,JSONObject> current=new TreeMap<>(), restored=new TreeMap<>();
        JSONArray pinned=new JSONArray();
        for(String sketchId:new TreeSet<>(committed.after.keySet())){
            JSONObject authoritative=currentDocuments.get(sketchId);
            if(authoritative==null)throw new IllegalArgumentException("Missing group undo sketch");
            SketchDocument.validate(authoritative);
            String pinnedCurrent=SketchDocument.canonical(authoritative);
            String accepted=SketchDocument.canonical(committed.after.get(sketchId));
            if(!pinnedCurrent.equals(accepted))
                throw new IllegalStateException("COMPOUND_UNDO_STALE_DOCUMENT:"+sketchId);
            JSONObject before=SketchDocument.copy(authoritative);
            JSONObject after=SketchDocument.copy(committed.before.get(sketchId));
            if(!SketchDocument.canonical(committed.before.get(sketchId)).equals(accepted))
                after.put("revision",Math.addExact(before.getLong("revision"),1L));
            SketchDocument.validate(after);
            current.put(sketchId,before);
            restored.put(sketchId,after);
            pinned.put(new JSONObject().put("sketchId",sketchId)
                .put("revision",before.getLong("revision"))
                .put("documentSha256",digest(pinnedCurrent)));
        }
        String fingerprint=digest(SketchDocument.canonical(new JSONObject()
            .put("contract","omega-compound-undo-v1")
            .put("compoundId",committed.compoundId)
            .put("originalOperationId",committed.operationId)
            .put("originalFingerprint",committed.fingerprint)
            .put("undoOperationId",undoId)
            .put("sourceSnapshots",pinned)));
        Map<String,Long> revisions=new TreeMap<>();
        Map<String,String> hashes=new TreeMap<>();
        for(Map.Entry<String,JSONObject> entry:current.entrySet()){
            revisions.put(entry.getKey(),entry.getValue().getLong("revision"));
            hashes.put(entry.getKey(),digest(SketchDocument.canonical(entry.getValue())));
        }
        CompoundPreview preview=new CompoundPreview(committed.compoundId,undoId,
            fingerprint,committed.impact.affected,Collections.emptyList(),
            committed.impact.localOverrideCount,committed.impact.totalExposureFrames,
            revisions,hashes);
        return new PreparedBatch(preview,current,restored);
    }

}
