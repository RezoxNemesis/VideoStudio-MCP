package com.rezoxnemesis.videostudio;

import android.content.Context;
import org.json.JSONArray;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Provider eligibility, never a certificate of convincing cinematic action. */
public final class StoryboardMotionAdmission {
    public static final String CINEMATIC_ACTION = "cinematic_action";
    public static final String SOURCE_DEFORMATION = "source_deformation";
    public static final String ENVIRONMENTAL = "environmental";
    public static final String MISSING_PROVIDER = "INSUFFICIENT_MOTION_PROVIDER";
    public static final int MAX_REQUEST_BYTES = 64 * 1024;
    private static final int MAX_MISSING_ROWS = 192;

    private StoryboardMotionAdmission() { }

    public static final class Rejected extends IllegalArgumentException {
        private final JSONObject report;
        Rejected(JSONObject report) {
            super(MISSING_PROVIDER + ": supply the reported source actor, weapon, contact and choreography annotations");
            this.report = report;
        }
        public JSONObject toJson() {
            try { return new JSONObject(report.toString()).put("error", getMessage()); }
            catch (Exception error) { throw new IllegalStateException("Could not retain admission report", error); }
        }
    }

    public static final class Admission {
        public final String sourceProjectId, motionIntent;
        public final int batchIndex;
        public final boolean cinematicAction;
        private final LinkedHashMap<String, Rig2DProfile[]> actors;
        private final JSONArray missing = new JSONArray();
        private int missingCount;

        Admission(String projectId, int selected, String intent) {
            sourceProjectId = projectId; batchIndex = selected; motionIntent = intent;
            cinematicAction = CINEMATIC_ACTION.equals(intent);
            actors = new LinkedHashMap<>();
        }
        public boolean accepted() { return missingCount == 0; }
        public void requireAccepted() {
            if (!accepted()) throw new Rejected(toJson());
        }
        public JSONObject toJson() {
            try {
                JSONObject report = new JSONObject().put("ok", accepted())
                        .put("sourceProjectId", sourceProjectId).put("batchIndex", batchIndex)
                        .put("motionIntent", motionIntent).put("cinematicActionRequested", cinematicAction)
                        .put("providerEligible", accepted()).put("cinematicAcceptance", false)
                        .put("cinematicQaStatus", "INCOMPLETE")
                        .put("missingRequirementCount", missingCount)
                        .put("missingRequirementsTruncated", missingCount > missing.length())
                        .put("missingAnnotations", new JSONArray(missing.toString()))
                        .put("neuralVideoProviderInstalled", false);
                if (!accepted()) report.put("code", MISSING_PROVIDER);
                JSONArray bindings = new JSONArray();
                for (Map.Entry<String, Rig2DProfile[]> entry : actors.entrySet()) {
                    Rig2DProfile[] pair = entry.getValue();
                    JSONObject bound = new JSONObject().put("assetId", entry.getKey());
                    if (pair[0] != null) bound.put("blackRigId", pair[0].id);
                    if (pair[1] != null) bound.put("whiteRigId", pair[1].id);
                    bindings.put(bound);
                }
                return report.put("resolvedRigIds", bindings);
            } catch (Exception error) { throw new IllegalStateException("Could not describe motion admission", error); }
        }
        private void missing(String assetId, String actor, String requirement, String reason) {
            missingCount++;
            if (missing.length() >= MAX_MISSING_ROWS) return;
            try {
                missing.put(new JSONObject().put("assetId", assetId).put("actor", actor)
                        .put("requirement", requirement).put("reason", reason));
            } catch (Exception error) { throw new IllegalStateException("Could not describe required annotation", error); }
        }
    }

    public static Rejected missingProgram(String sourceProjectId) {
        try{return new Rejected(new JSONObject().put("ok",false).put("code",MISSING_PROVIDER).put("sourceProjectId",sourceProjectId)
                .put("missing_program",true).put("providerEligible",false).put("cinematicAcceptance",false)
                .put("missingAnnotations",new JSONArray().put(new JSONObject().put("requirement","saved_ordered_program")
                        .put("reason","Legacy generic actions do not author source-bound contacts and twelve-beat choreography; save an ordered programme"))));}
        catch(Exception error){throw new IllegalStateException(error);}
    }
    public static String intent(JSONObject request) {
        Object value = request == null ? null : request.opt("motionIntent");
        String intent = value == null ? CINEMATIC_ACTION : value instanceof String ? (String) value : "";
        if (!CINEMATIC_ACTION.equals(intent) && !SOURCE_DEFORMATION.equals(intent) && !ENVIRONMENTAL.equals(intent))
            throw new IllegalArgumentException("motionIntent must be cinematic_action, source_deformation or environmental");
        return intent;
    }

    public static Admission inspect(ProjectStore.Project source, StoryboardBatchPlanner.Plan plan,
                                    int selected, List<Rig2DProfile> profiles, String motionIntent) throws Exception {
        return inspect(source, plan, selected, profiles, motionIntent, new JSONObject());
    }

    /** Cheap schema/ownership inspection; it never reads images, creates a project or starts work. */
    public static Admission inspect(ProjectStore.Project source, StoryboardBatchPlanner.Plan plan,
                                    int selected, List<Rig2DProfile> profiles, String motionIntent,
                                    JSONObject request) throws Exception {
        if (source == null || plan == null || !source.id.equals(plan.projectId))
            throw new IllegalArgumentException("An existing owned storyboard source is required");
        intent(new JSONObject().put("motionIntent", motionIntent));
        List<StoryboardBatchPlanner.Shot> shots = shots(plan, selected);
        Admission admission = new Admission(source.id, selected, motionIntent);
        boolean rigProvider = admission.cinematicAction || plan.backend == StoryboardBatchPlanner.Backend.SOURCE_RIG;
        if (plan.backend == StoryboardBatchPlanner.Backend.NEURAL_IF_AVAILABLE) {
            admission.missing("", "provider", "installed_executable_motion_provider",
                    "No audited on-device image-to-video execution adapter is installed");
        }
        if (admission.cinematicAction && plan.backend != StoryboardBatchPlanner.Backend.AUTO_LAYERS
                && plan.backend != StoryboardBatchPlanner.Backend.SOURCE_RIG) {
            admission.missing("", "provider", "source_rig_provider",
                    "This backend does not implement annotated two-actor cinematic contact motion");
        }
        Map<String, JSONObject> chosen = selections(source, plan, request);
        if (!rigProvider && (!chosen.isEmpty() || request.has("contactPlans")))
            throw new IllegalArgumentException("Rig bindings and contact plans require SOURCE_RIG or cinematic_action");
        if (rigProvider) {
            Set<String> needed = new java.util.LinkedHashSet<>();
            for (StoryboardBatchPlanner.Shot shot : shots) { needed.add(shot.fromId); needed.add(shot.toId); }
            for (String assetId : needed) {
                ProjectStore.Asset asset = source.asset(assetId);
                if (!StoryboardBatchPlanner.isImage(asset))
                    throw new IllegalArgumentException("The storyboard references a missing source image");
                Rig2DProfile[] pair = new Rig2DProfile[2];
                for (int actor = 0; actor < 2; actor++) {
                    String actorName = actor == 0 ? "black" : "white";
                    JSONObject selection = chosen.get(assetId);
                    String selectedId = selection == null ? "" : selection.getString(actorName + "RigId");
                    List<Rig2DProfile> candidates = new ArrayList<>();
                    if (profiles != null) for (Rig2DProfile profile : profiles) {
                        if (profile == null) continue;
                        if (!selectedId.isEmpty() ? selectedId.equals(profile.id)
                                : assetId.equals(profile.sourceAssetId) && profile.black == (actor == 0)) candidates.add(profile);
                    }
                    if (candidates.size() != 1) {
                        admission.missing(assetId, actorName, "unique_source_actor_profile",
                                candidates.isEmpty() ? "No source-authored actor profile is present"
                                        : "Several profiles match; explicitly select one exact rig ID");
                        continue;
                    }
                    Rig2DProfile profile = candidates.get(0);
                    if (!source.id.equals(profile.projectId) || !assetId.equals(profile.sourceAssetId)
                            || profile.black != (actor == 0)) {
                        admission.missing(assetId, actorName, "source_actor_ownership", "Profile belongs to a different source or actor");
                        continue;
                    }
                    pair[actor] = profile;
                    if (profile.joints.length != Rig2DProfile.JOINTS.length || profile.outline.length < 3)
                        admission.missing(assetId, actorName, "sixteen_joints_and_body_outline", "Trace the original actor and all sixteen source joints");
                    if (profile.swordTip == null)
                        admission.missing(assetId, actorName, "sword_tip", "Trace the owning actor's original sword endpoint");
                    if (admission.cinematicAction) {
                        JSONObject definition = profile.toJson();
                        if (profile.sourceSha256 == null)
                            admission.missing(assetId, actorName, "authored_source_sha256",
                                    "Legacy geometry is not bound to the exact displayed original image bytes");
                        JSONArray hilt = definition.optJSONArray("swordHilt"), outline = definition.optJSONArray("swordOutline");
                        if (hilt == null || hilt.length() != 2 || outline == null || outline.length() < 3)
                            admission.missing(assetId, actorName, "original_sword_hilt_and_outline",
                                    "A sword-tip point does not preserve the original weapon texture; trace its hilt and full source mask");
                    }
                }
                admission.actors.put(assetId, pair);
            }
        }
        JSONArray contacts = contactSelections(source, plan, request);
        if (admission.cinematicAction) {
            for (StoryboardBatchPlanner.Shot shot : shots) {
                JSONObject contact = contactFor(contacts, shot.fromIndex, shot.fromId, shot.toId);
                if (contact == null || !hasIntervals(contact, "black") || !hasIntervals(contact, "white"))
                    admission.missing(shot.fromId, "both", "explicit_ground_contacts",
                            "Provide source-bound black and white stance intervals for " + shot.fromId + " to " + shot.toId);
                if (StoryboardChoreography.resolveBeat(request, shot.fromIndex, shot.fromId, shot.toId) == null)
                    admission.missing(shot.fromId, "both", "explicit_choreography_beat",
                            "Author this exact ordered transition; generic filename actions are not two-fighter choreography");
            }
        }
        return admission;
    }

    public static List<StoryboardBatchPlanner.Shot> shots(StoryboardBatchPlanner.Plan plan, int selected) {
        if (plan == null || selected < -1 || selected >= plan.batches.size())
            throw new IllegalArgumentException("Requested storyboard batch is unavailable");
        List<StoryboardBatchPlanner.Shot> result = new ArrayList<>();
        int start = selected < 0 ? 0 : selected, end = selected < 0 ? plan.batches.size() : selected + 1;
        for (int index = start; index < end; index++) result.addAll(plan.batches.get(index).shots);
        return result;
    }

    private static Map<String, JSONObject> selections(ProjectStore.Project source, StoryboardBatchPlanner.Plan plan,
                                                     JSONObject request) throws Exception {
        Map<String, JSONObject> result = new HashMap<>();
        if (!request.has("sourceRigBindings")) return result;
        JSONArray entries = array(request, "sourceRigBindings", 160);
        for (int index = 0; index < entries.length(); index++) {
            JSONObject entry = object(entries, index);
            fields(entry, "assetId", "blackRigId", "whiteRigId", "blackRigSha256", "whiteRigSha256");
            String assetId = id(entry, "assetId");
            id(entry, "blackRigId"); id(entry, "whiteRigId");
            for (String hash : new String[]{"blackRigSha256", "whiteRigSha256"})
                if (entry.has(hash) && (!(entry.opt(hash) instanceof String)
                        || !((String) entry.opt(hash)).matches("[a-f0-9]{64}")))
                    throw new IllegalArgumentException(hash + " must be an expected profile SHA-256");
            if (!plan.assets.contains(assetId) || !StoryboardBatchPlanner.isImage(source.asset(assetId)))
                throw new IllegalArgumentException("Rig selection is not an owned storyboard anchor");
            if (result.put(assetId, entry) != null) throw new IllegalArgumentException("Duplicate source rig binding");
        }
        return result;
    }

    private static JSONArray contactSelections(ProjectStore.Project source, StoryboardBatchPlanner.Plan plan,
                                               JSONObject request) throws Exception {
        if (!request.has("contactPlans")) return new JSONArray();
        JSONArray entries = array(request, "contactPlans", 159);
        Set<Integer> selectedIndices = new HashSet<>();
        List<StoryboardBatchPlanner.Shot> planned = shots(plan, -1);
        for (int index = 0; index < entries.length(); index++) {
            JSONObject entry = object(entries, index);
            fields(entry, "shotIndex", "fromAssetId", "toAssetId", "contactPlan", "sourceWidth", "sourceHeight");
            String from = id(entry, "fromAssetId"), to = id(entry, "toAssetId");
            int shotIndex = entry.has("shotIndex") ? integer(entry, "shotIndex", 0, 158) : index;
            if (shotIndex >= planned.size() || !selectedIndices.add(shotIndex))
                throw new IllegalArgumentException("Contact plans must identify one unique ordered shot");
            StoryboardBatchPlanner.Shot shot = planned.get(shotIndex);
            if (!from.equals(shot.fromId) || !to.equals(shot.toId)
                    || source.asset(from) == null || source.asset(to) == null)
                throw new IllegalArgumentException("Contact plan does not match its exact ordered owned anchor pair");
            if (!(entry.opt("contactPlan") instanceof JSONObject))
                throw new IllegalArgumentException("contactPlan must be an authored object");
        }
        return entries;
    }

    public static JSONObject contactFor(JSONArray entries, int shotIndex, String from, String to) throws Exception {
        for (int index = 0; index < entries.length(); index++) {
            JSONObject entry = entries.getJSONObject(index);
            int resolvedIndex = entry.has("shotIndex") ? integer(entry, "shotIndex", 0, 158) : index;
            if (resolvedIndex == shotIndex && from.equals(entry.getString("fromAssetId")) && to.equals(entry.getString("toAssetId")))
                return entry.getJSONObject("contactPlan");
        }
        return null;
    }
    private static boolean hasIntervals(JSONObject contact, String actor) {
        JSONArray intervals = contact.optJSONArray(actor);
        return intervals != null && intervals.length() > 0 && intervals.length() <= 32;
    }
    static void fields(JSONObject value, String... allowed) {
        Set<String> names = new HashSet<>(java.util.Arrays.asList(allowed));
        Iterator<String> keys = value.keys();
        while (keys.hasNext()) if (!names.contains(keys.next())) throw new IllegalArgumentException("Unknown storyboard annotation field");
    }
    static JSONArray array(JSONObject owner, String name, int maximum) {
        Object value = owner.opt(name);
        if (!(value instanceof JSONArray) || ((JSONArray) value).length() > maximum)
            throw new IllegalArgumentException(name + " must be a bounded array");
        return (JSONArray) value;
    }
    static JSONObject object(JSONArray array, int index) throws Exception {
        Object value = array.get(index);
        if (!(value instanceof JSONObject)) throw new IllegalArgumentException("Storyboard annotation entries must be objects");
        return (JSONObject) value;
    }
    static String id(JSONObject owner, String name) {
        Object value = owner.opt(name);
        if (!(value instanceof String) || !((String) value).matches("[A-Za-z0-9_-]{8,128}"))
            throw new IllegalArgumentException(name + " must be a registered stable ID");
        return (String) value;
    }
    static int integer(JSONObject owner, String name, int minimum, int maximum) {
        Object value = owner.opt(name);
        if (!(value instanceof Number)) throw new IllegalArgumentException(name + " must be an integer");
        double number = ((Number) value).doubleValue();
        if (!Double.isFinite(number) || number != Math.rint(number) || number < minimum || number > maximum)
            throw new IllegalArgumentException(name + " is outside its integer range");
        return (int) number;
    }
    /** Immutable provider proof captured before project/job allocation. */
    public static JSONObject freeze(Context context, ProjectStore store, ProjectStore.Project source,
                                    StoryboardBatchPlanner.Plan plan, int selected, int width, int height,
                                    JSONObject request, JSONObject manifest) throws Exception {
        dimensions(width,height);
        Rig2DProjectStore profiles=new Rig2DProjectStore(context,store);
        Admission admission=inspect(source,plan,selected,profiles.list(source.id),intent(request),request);
        admission.requireAccepted();
        StoryboardSourceManifest.verifyOwnedSources(context,source,manifest);
        JSONArray bindings=new JSONArray(),rows=new JSONArray();
        Map<String,JSONObject> expected=selections(source,plan,request);
        for(Map.Entry<String,Rig2DProfile[]> entry:admission.actors.entrySet()) {
            JSONObject bound=new JSONObject().put("assetId",entry.getKey());
            for(int actor=0;actor<2;actor++) {
                Rig2DProfile rig=entry.getValue()[actor];
                JSONObject binding=profiles.captureBinding(source.id,rig.id);
                Rig2DProfile frozen=profiles.loadVerified(source.id,rig.id,binding.getString("sha256"));
                if(!Rig2DProjectStore.sha256(rig).equals(binding.getString("sha256")))
                    throw new IllegalStateException("SOURCE_RIG_CHANGED: annotation changed during admission");
                String role=actor==0?"black":"white";
                JSONObject expectedBinding=expected.get(entry.getKey());
                if(expectedBinding!=null&&expectedBinding.has(role+"RigSha256")
                        &&!expectedBinding.getString(role+"RigSha256").equals(binding.getString("sha256")))
                    throw new IllegalStateException("SOURCE_RIG_CHANGED: expected profile differs");
                if(admission.cinematicAction&&!library(manifest,entry.getKey()).getString("sourceSha256").equals(frozen.sourceSha256))
                    throw new IllegalStateException("INSUFFICIENT_MOTION_PROVIDER: rig was not authored against accepted original image bytes");
                bound.put(role,binding);
            }
            bindings.put(bound);
        }
        JSONArray contacts=contactSelections(source,plan,request);
        for(StoryboardBatchPlanner.Shot shot:shots(plan,selected)) {
            JSONObject row=new JSONObject().put("shotIndex",shot.fromIndex)
                    .put("fromAssetId",shot.fromId).put("toAssetId",shot.toId);
            if(admission.cinematicAction||plan.backend==StoryboardBatchPlanner.Backend.SOURCE_RIG) {
                int[] a=StoryboardSourceManifest.orientedDimensions(manifest,shot.fromId),
                        b=StoryboardSourceManifest.orientedDimensions(manifest,shot.toId);
                if((long)a[0]*b[1]!=(long)b[0]*a[1]||(long)a[0]*height!=(long)width*a[1])
                    throw new IllegalArgumentException("INSUFFICIENT_MOTION_PROVIDER: oriented source and output aspects need explicit registration");
                row.put("sourceWidth",a[0]).put("sourceHeight",a[1]);
                JSONObject contact=contactFor(contacts,shot.fromIndex,shot.fromId,shot.toId);
                if(contact!=null) {
                    for(int i=0;i<contacts.length();i++) {
                        JSONObject c=contacts.getJSONObject(i);
                        int index=c.has("shotIndex")?integer(c,"shotIndex",0,158):i;
                        if(index==shot.fromIndex&&((c.has("sourceWidth")&&integer(c,"sourceWidth",32,8192)!=a[0])
                                ||(c.has("sourceHeight")&&integer(c,"sourceHeight",32,8192)!=a[1])))
                            throw new IllegalArgumentException("Contact authoring geometry differs from the oriented source image");
                    }
                    Rig2DProfile[] from=admission.actors.get(shot.fromId),to=admission.actors.get(shot.toId);
                    StoryboardContactPlan parsed=StoryboardContactPlan.parse(contact,from[0],from[1],to[0],to[1],a[0],a[1]);
                    row.put("contactPlan",parsed.toJson()).put("contactBindingProof",parsed.bindingProof());
                }
            }
            StoryboardChoreography.Beat beat=StoryboardChoreography.resolveBeat(request,shot.fromIndex,shot.fromId,shot.toId);
            if(beat!=null)row.put("beat",beat.name());
            if(row.has("sourceWidth")) {
                Rig2DProfile[] from=admission.actors.get(shot.fromId),to=admission.actors.get(shot.toId);
                StoryboardContactPlan parsedContacts=row.has("contactPlan")?StoryboardContactPlan.parse(row.getJSONObject("contactPlan"),
                        from[0],from[1],to[0],to[1],row.getInt("sourceWidth"),row.getInt("sourceHeight")):null;
                try{requireRenderable(shot,from,to,parsedContacts,beat,row.getInt("sourceWidth")/(double)row.getInt("sourceHeight"),width,height,admission.cinematicAction);}
                catch(IllegalArgumentException unsupported){admission.missing(shot.fromId,"both","renderable_authored_pose",unsupported.getMessage());admission.requireAccepted();}
            }
            rows.put(row);
        }
        JSONObject artifact=new JSONObject().put("format","videostudio.storyboard-admission.v1").put("version",1)
                .put("sourceProjectId",source.id).put("sourceProjectUpdatedAt",source.updatedAt)
                .put("manifestSha256",manifest.getString("manifestSha256"))
                .put("planSha256",planDigest(plan))
                .put("motionIntent",admission.motionIntent).put("batchIndex",selected)
                .put("width",width).put("height",height).put("anchorBindings",bindings).put("shots",rows);
        if(artifact.toString().getBytes(StandardCharsets.UTF_8).length>256*1024
                ||new JSONObject().put("sourceManifest",manifest).put("admission",artifact).toString().getBytes(StandardCharsets.UTF_8).length>512*1024)
            throw new IllegalArgumentException("Frozen motion admission exceeds its native proof budget; select a smaller batch");
        return artifact;
    }

    public static void verify(Context context,ProjectStore store,ProjectStore.Project source,
                              StoryboardBatchPlanner.Plan plan,JSONObject settings) throws Exception {
        JSONObject proof=settings.getJSONObject("_storyboardAdmission"),manifest=settings.getJSONObject("_storyboardSourceManifest");
        if(!"videostudio.storyboard-admission.v1".equals(proof.getString("format"))||proof.getInt("version")!=1
                ||!source.id.equals(proof.getString("sourceProjectId"))||source.updatedAt!=proof.getLong("sourceProjectUpdatedAt")
                ||!intent(settings).equals(proof.getString("motionIntent"))
                ||!manifest.getString("manifestSha256").equals(proof.getString("manifestSha256"))
                ||!planDigest(plan).equals(proof.getString("planSha256"))
                ||settings.getInt("batchIndex")!=proof.getInt("batchIndex")
                ||settings.getInt("width")!=proof.getInt("width")||settings.getInt("height")!=proof.getInt("height"))
            throw new IllegalStateException("STORYBOARD_ACCEPTED_SOURCE_CHANGED: exact accepted program differs");
        StoryboardSourceManifest.verifyOwnedSources(context,source,manifest);
        for(StoryboardBatchPlanner.Shot shot:shots(plan,proof.getInt("batchIndex"))) {
            FrozenShot frozen=frozenShot(context,store,source,settings,shot);
            if(frozen!=null)requireRenderable(shot,frozen.from,frozen.to,frozen.contacts,frozen.beat,frozen.aspect,
                    proof.getInt("width"),proof.getInt("height"),CINEMATIC_ACTION.equals(proof.getString("motionIntent")));
        }
    }

    public static final class FrozenShot {
        public final Rig2DProfile[] from,to;
        public final StoryboardContactPlan contacts;
        public final StoryboardChoreography.Beat beat;
        public final double aspect;
        FrozenShot(Rig2DProfile[] from,Rig2DProfile[] to,StoryboardContactPlan contacts,StoryboardChoreography.Beat beat,double aspect) {
            this.from=from;this.to=to;this.contacts=contacts;this.beat=beat;this.aspect=aspect;
        }
    }
    public static FrozenShot frozenShot(Context context,ProjectStore store,ProjectStore.Project source,
                                        JSONObject settings,StoryboardBatchPlanner.Shot shot) throws Exception {
        JSONObject proof=settings.getJSONObject("_storyboardAdmission"),row=null;
        JSONArray rows=proof.getJSONArray("shots");
        for(int i=0;i<rows.length();i++)if(rows.getJSONObject(i).getInt("shotIndex")==shot.fromIndex)row=rows.getJSONObject(i);
        if(row==null||!shot.fromId.equals(row.getString("fromAssetId"))||!shot.toId.equals(row.getString("toAssetId")))
            throw new IllegalStateException("Accepted ordered shot proof is missing");
        if(!row.has("sourceWidth"))return null;
        Rig2DProjectStore profiles=new Rig2DProjectStore(context,store);
        Rig2DProfile[] from=loadPair(profiles,source.id,proof,shot.fromId),to=loadPair(profiles,source.id,proof,shot.toId);
        boolean cinematic=CINEMATIC_ACTION.equals(proof.getString("motionIntent"));
        JSONObject manifest=settings.getJSONObject("_storyboardSourceManifest");
        if(cinematic)for(Rig2DProfile rig:new Rig2DProfile[]{from[0],from[1],to[0],to[1]})
            if(!library(manifest,rig.sourceAssetId).getString("sourceSha256").equals(rig.sourceSha256))
                throw new IllegalStateException("SOURCE_RIG_CHANGED: annotation source bytes differ");
        StoryboardContactPlan contacts=null;
        if(row.has("contactPlan")) {
            contacts=StoryboardContactPlan.parse(row.getJSONObject("contactPlan"),from[0],from[1],to[0],to[1],row.getInt("sourceWidth"),row.getInt("sourceHeight"));
            contacts.requireBindingProof(row.getJSONObject("contactBindingProof"));
        }
        StoryboardChoreography.Beat beat=row.has("beat")?StoryboardChoreography.Beat.valueOf(row.getString("beat")):null;
        if(cinematic&&(contacts==null||beat==null))throw new IllegalStateException("Accepted cinematic contact/choreography proof is missing");
        return new FrozenShot(from,to,contacts,beat,row.getInt("sourceWidth")/(double)row.getInt("sourceHeight"));
    }
    private static Rig2DProfile[] loadPair(Rig2DProjectStore profiles,String pid,JSONObject proof,String assetId) throws Exception {
        JSONArray bindings=proof.getJSONArray("anchorBindings");
        for(int i=0;i<bindings.length();i++) {
            JSONObject row=bindings.getJSONObject(i);
            if(!assetId.equals(row.getString("assetId")))continue;
            Rig2DProfile[] result=new Rig2DProfile[2];
            for(int actor=0;actor<2;actor++) {
                JSONObject binding=row.getJSONObject(actor==0?"black":"white");
                Rig2DProfile rig=profiles.loadVerified(pid,binding.getString("rigId"),binding.getString("sha256"));
                if(!assetId.equals(rig.sourceAssetId)||rig.black!=(actor==0))throw new IllegalStateException("Frozen actor ownership differs");
                result[actor]=rig;
            }
            return result;
        }
        throw new IllegalStateException("Frozen source actor bindings are missing");
    }
    private static void requireRenderable(StoryboardBatchPlanner.Shot shot,Rig2DProfile[] from,Rig2DProfile[] to,
                                          StoryboardContactPlan contacts,StoryboardChoreography.Beat beat,double aspect,
                                          int width,int height,boolean cinematic) throws Exception {
        java.util.TreeSet<Double> phases=new java.util.TreeSet<>();
        if(contacts!=null)phases.addAll(contacts.validationPhases(shot.frames));
        for(int step=0;step<=shot.frames;step++){phases.add(step/(double)shot.frames);phases.add((double)(step/(float)shot.frames));}
        StoryboardPoseMotion.Action action=StoryboardSegmentComposer.rigAction(shot.action);
        for(double phase:phases) {
            StoryboardPoseMotion.Frame sample=beat!=null&&contacts!=null
                    ?StoryboardPoseMotion.between(from[0],from[1],to[0],to[1],from[0].swordTip,from[1].swordTip,to[0].swordTip,to[1].swordTip,phase,beat,contacts)
                    :contacts!=null?StoryboardPoseMotion.between(from[0],from[1],to[0],to[1],from[0].swordTip,from[1].swordTip,to[0].swordTip,to[1].swordTip,phase,action,contacts)
                    :StoryboardPoseMotion.between(from[0],from[1],to[0],to[1],from[0].swordTip,from[1].swordTip,to[0].swordTip,to[1].swordTip,phase,action,aspect);
            NativeStoryboardArtworkRenderer.requireRenderableFrame(from[0],from[1],to[0],to[1],sample,
                    beat!=null?StoryboardChoreography.phase(beat,true,phase):phase,
                    beat!=null?StoryboardChoreography.phase(beat,false,phase):phase,width,height,cinematic);
        }
    }
    private static String planDigest(StoryboardBatchPlanner.Plan plan) throws Exception {
        JSONArray rows=new JSONArray();
        for(StoryboardBatchPlanner.Shot shot:shots(plan,-1))rows.put(new JSONArray().put(shot.fromIndex).put(shot.toIndex)
                .put(shot.fromId).put(shot.toId).put(shot.frames).put(shot.action.name()).put(shot.scene.name())
                .put(shot.beat==null?JSONObject.NULL:shot.beat.name()));
        JSONArray order=new JSONArray();for(String id:plan.assets)order.put(id);
        JSONArray batches=new JSONArray();for(StoryboardBatchPlanner.Batch batch:plan.batches)batches.put(new JSONArray()
                .put(batch.index).put(batch.start).put(batch.end).put(batch.expectedFrames));
        JSONObject semantic=new JSONObject().put("projectId",plan.projectId).put("fps",plan.fps)
                .put("backend",plan.backend.name()).put("order",order).put("shots",rows)
                .put("batches",batches).put("manifestSha256",plan.sourceManifest==null?JSONObject.NULL:plan.sourceManifest.getString("manifestSha256"));
        StringBuilder canonical=new StringBuilder();appendPlanValue(canonical,semantic,0);
        byte[] bytes=canonical.toString().getBytes(StandardCharsets.UTF_8);
        if(bytes.length>256*1024)throw new IllegalArgumentException("Semantic storyboard plan exceeds 256 KiB");
        byte[] hash=java.security.MessageDigest.getInstance("SHA-256").digest(bytes);
        StringBuilder digest=new StringBuilder(64);for(byte value:hash)digest.append(String.format(java.util.Locale.US,"%02x",value&255));
        return digest.toString();
    }
    private static void appendPlanValue(StringBuilder out,Object value,int depth) throws Exception {
        if(depth>16||out.length()>256*1024)throw new IllegalArgumentException("Semantic plan exceeds its bounded schema");
        if(value instanceof JSONObject) {
            java.util.TreeSet<String> keys=new java.util.TreeSet<>();((JSONObject)value).keys().forEachRemaining(keys::add);
            out.append('{');boolean comma=false;for(String key:keys) {if(comma)out.append(',');comma=true;
                out.append(JSONObject.quote(key)).append(':');appendPlanValue(out,((JSONObject)value).get(key),depth+1);}
            out.append('}');
        } else if(value instanceof JSONArray) {
            out.append('[');for(int i=0;i<((JSONArray)value).length();i++){if(i>0)out.append(',');appendPlanValue(out,((JSONArray)value).get(i),depth+1);}out.append(']');
        } else if(value==null||value==JSONObject.NULL)out.append("null");
        else if(value instanceof String)out.append(JSONObject.quote((String)value));
        else if(value instanceof Number||value instanceof Boolean)out.append(value.toString());
        else throw new IllegalArgumentException("Invalid semantic plan value");
    }
    public static JSONObject library(JSONObject manifest,String assetId) throws Exception {
        JSONArray rows=manifest.getJSONArray("library");
        for(int i=0;i<rows.length();i++)if(assetId.equals(rows.getJSONObject(i).getString("assetId")))return rows.getJSONObject(i);
        throw new IllegalStateException("Accepted original source image is missing");
    }
    public static void dimensions(int width,int height) {
        if(width<256||height<256||width>1280||height>1280||(width&1)!=0||(height&1)!=0||(long)width*height>720L*1280L)
            throw new IllegalArgumentException("Choose even 256..1280 dimensions with at most 720×1280 pixels");
    }
    public static void requestBudget(JSONObject parameters) {
        byte[] bytes = parameters.toString().getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_REQUEST_BYTES) throw new IllegalArgumentException("Storyboard request exceeds its 64 KiB metadata budget");
    }
}
