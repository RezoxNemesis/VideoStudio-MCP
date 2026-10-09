package com.rezoxnemesis.videostudio;

import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** The transaction boundary used by the owner editor and autonomous commands. */
public final class EditorEngine {
    private final ProjectStore store;
    private static final Set<String> TRACK_TYPES = new HashSet<>(Arrays.asList(
            "video", "image", "adjustment", "text", "animation2d", "scene3d", "vfx",
            "audio_music", "audio_dialogue", "audio_sfx", "voice_over", "automation"));
    private static final Set<String> PROPERTIES = new HashSet<>(Arrays.asList(
            "x", "y", "scale", "scaleX", "scaleY", "rotate", "opacity", "anchorX", "anchorY",
            "brightness", "contrast", "saturationAdjust", "lightnessAdjust", "blur", "volume", "pan",
            "cropLeft", "cropRight", "cropTop", "cropBottom"));
    private static final Set<String> EASING = new HashSet<>(Arrays.asList("linear", "hold", "ease_in", "ease_out", "ease_in_out"));

    public EditorEngine(ProjectStore store) { this.store = store; }

    public ProjectStore.Project execute(String projectId, long expectedRevision, String actor,
                                        String commandId, String operation, JSONObject args) {
        if (args == null) throw new IllegalArgumentException("Operation arguments are required");
        validateActorRevision(actor,expectedRevision,commandId);
        return store.transact(projectId, expectedRevision, actor, commandId,
                operation.replace('_', ' '),fingerprint(operation,args,expectedRevision), p -> apply(p, operation, args));
    }

    public ProjectStore.Project executeBatch(String projectId,long expectedRevision,String actor,String commandId,JSONArray operations){
        validateActorRevision(actor,expectedRevision,commandId);
        if(operations==null||operations.length()<1||operations.length()>100)throw new IllegalArgumentException("Batch requires 1–100 operations");
        JSONObject payload=new JSONObject();try{payload.put("operations",operations);}catch(Exception error){throw new IllegalArgumentException(error);}
        return store.transact(projectId,expectedRevision,actor,commandId,"Apply editor batch",fingerprint("batch",payload,expectedRevision),p->{
            for(int i=0;i<operations.length();i++){JSONObject op=operations.getJSONObject(i);apply(p,op.getString("operation"),op.getJSONObject("args"));}
        });
    }
    private static void validateActorRevision(String actor,long revision,String commandId){
        if("agent".equals(actor)&&(revision<1||commandId==null||commandId.length()<8))throw new IllegalArgumentException("Autonomous edits require an expected project revision and durable command ID");
    }
    public static String fingerprint(String operation,JSONObject args,long revision){
        try{
            String input=operation+":"+revision+":"+canonicalJson(args);
            byte[] hash=java.security.MessageDigest.getInstance("SHA-256").digest(input.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder out=new StringBuilder();for(byte b:hash)out.append(String.format(java.util.Locale.US,"%02x",b&255));return out.toString();
        }catch(Exception error){throw new IllegalArgumentException("Could not fingerprint editor request",error);}
    }
    private static String canonicalJson(Object value)throws Exception{
        if(value instanceof JSONObject){JSONObject o=(JSONObject)value;java.util.TreeSet<String> keys=new java.util.TreeSet<>();java.util.Iterator<String> it=o.keys();while(it.hasNext())keys.add(it.next());
            StringBuilder out=new StringBuilder("{");for(String k:keys){if(out.length()>1)out.append(',');out.append(JSONObject.quote(k)).append(':').append(canonicalJson(o.get(k)));}return out.append('}').toString();}
        if(value instanceof JSONArray){JSONArray a=(JSONArray)value;StringBuilder out=new StringBuilder("[");for(int i=0;i<a.length();i++){if(i>0)out.append(',');out.append(canonicalJson(a.get(i)));}return out.append(']').toString();}
        if(value==null||value==JSONObject.NULL)return "null";if(value instanceof String)return JSONObject.quote((String)value);
        return value instanceof Number?JSONObject.numberToString((Number)value):value.toString();
    }

    private void apply(ProjectStore.Project p, String op, JSONObject a) throws Exception {
        p.ensureTimelineDefaults();
        switch (op) {
            case "rename_project":
                String name = a.optString("name", "").trim();
                if (name.isEmpty() || name.length() > 240) throw new IllegalArgumentException("Project name is required (up to 240 characters)");
                p.name = name; return;
            case "add_track": {
                String type = a.optString("type", "video");
                if (!TRACK_TYPES.contains(type)) throw new IllegalArgumentException("Unknown track type");
                ProjectStore.Track t = new ProjectStore.Track(); t.type = type; t.order = p.tracks.size();
                t.name = a.optString("name", type.replace('_', ' ') + " " + (p.tracks.size() + 1));
                p.tracks.add(t); return;
            }
            case "set_track": {
                ProjectStore.Track t = requireTrack(p, a.optString("trackId"));
                if (a.has("name")) t.name = a.getString("name");
                if (a.has("locked")) t.locked = a.getBoolean("locked");
                if (a.has("muted")) t.muted = a.getBoolean("muted");
                if (a.has("solo")) t.solo = a.getBoolean("solo");
                if (a.has("visible")) t.visible = a.getBoolean("visible");
                if (a.has("height")) t.height = Math.max(40, Math.min(240, a.getInt("height")));
                return;
            }
            case "remove_track": {
                ProjectStore.Track t = editableTrack(p, a.optString("trackId"));
                if (p.tracks.size() <= 1) throw new IllegalArgumentException("Keep at least one track");
                for (ProjectStore.Clip c : p.clips) if (t.id.equals(c.trackId)) throw new IllegalArgumentException("Move or remove clips before deleting this track");
                p.tracks.remove(t); return;
            }
            case "add_clip": {
                ProjectStore.Asset asset = p.asset(a.optString("assetId"));
                if (asset == null) throw new IllegalArgumentException("Unknown project-owned asset");
                String trackId = a.optString("trackId", p.tracks.get(0).id);
                ProjectStore.Track track = editableTrack(p, trackId); compatible(track, asset);
                ProjectStore.Clip c = new ProjectStore.Clip(); c.id = UUID.randomUUID().toString();
                c.assetId = asset.id; c.trackId = trackId; c.inMs = a.optLong("inMs", 0);
                c.outMs = a.optLong("outMs", asset.mime.startsWith("image/") ? 3000 : asset.durationMs);
                c.speed = (float) a.optDouble("speed", 1);
                c.startMs = a.optLong("startMs", trackEnd(p, trackId));
                validateRange(c, asset); if (c.startMs < 0) throw new IllegalArgumentException("Negative clip position");
                noOverlap(p, c); p.clips.add(c); return;
            }
            case "add_marker": {
                long time = a.optLong("timeMs", -1);
                if (time < 0) throw new IllegalArgumentException("Marker time must be nonnegative");
                JSONObject marker = new JSONObject(); marker.put("id", UUID.randomUUID().toString());
                marker.put("timeMs", time); marker.put("name", a.optString("name", "Marker"));
                marker.put("color", a.optString("color", "#ffd275")); p.markers.put(marker); return;
            }
            case "remove_marker": {
                JSONArray next = new JSONArray();
                for (int i = 0; i < p.markers.length(); i++) {
                    JSONObject marker = p.markers.getJSONObject(i);
                    if (!a.optString("markerId").equals(marker.optString("id"))) next.put(marker);
                }
                p.markers = next; return;
            }
            case "rename_asset": {
                ProjectStore.Asset asset = p.asset(a.optString("assetId"));
                if (asset == null) throw new IllegalArgumentException("Asset not found");
                String value = a.optString("name", "").trim();
                if (value.isEmpty()) throw new IllegalArgumentException("Asset name is required");
                asset.name = value; return;
            }
            case "remove_asset": {
                ProjectStore.Asset asset = p.asset(a.optString("assetId"));
                if (asset == null) throw new IllegalArgumentException("Asset not found");
                for (ProjectStore.Clip c : p.clips) if (asset.id.equals(c.assetId)) throw new IllegalArgumentException("Remove this asset's clips first");
                p.assets.remove(asset); return;
            }
            case "set_preview_policy": {
                String mode=a.getString("mode");if(!"auto".equals(mode)&&!"original".equals(mode))mode=ProxyManager.normaliseTier(mode);
                p.settings.put("previewTier",mode);return;
            }
            case "set_project_settings": {
                if (a.has("aspect")) {
                    String aspect = a.getString("aspect");
                    if (!Arrays.asList("16:9", "9:16", "1:1", "4:5").contains(aspect)) throw new IllegalArgumentException("Unsupported aspect ratio");
                    p.settings.put("aspect", aspect);
                }
                if (a.has("fps")) {
                    int fps = a.getInt("fps");
                    if (!Arrays.asList(24, 25, 30, 50, 60).contains(fps)) throw new IllegalArgumentException("Unsupported frame rate");
                    p.settings.put("fps", fps);
                }
                return;
            }
        }

        ProjectStore.Clip c = requireClip(p, a.optString("clipId"));
        editableTrack(p, c.trackId);
        ProjectStore.Asset asset = p.asset(c.assetId);
        switch (op) {
            case "move_clip": {
                long start = a.optLong("startMs", c.startMs);
                if (start < 0) throw new IllegalArgumentException("Negative clip position");
                String destination = a.optString("trackId", c.trackId);
                compatible(editableTrack(p, destination), asset);
                long delta = start - c.startMs;
                List<ProjectStore.Clip> linked = linkedClips(p, c);
                for (ProjectStore.Clip member : linked) {
                    editableTrack(p, member.trackId);
                    if (delta < 0 && member.startMs < -delta) throw new IllegalArgumentException("Linked clip would move before zero");
                    member.startMs = delta >= 0 ? TimelineMath.add(member.startMs, delta) : member.startMs + delta;
                }
                c.trackId = destination;
                for (ProjectStore.Clip member : linked) noOverlap(p, member);
                break;
            }
            case "trim_clip": {
                ProjectStore.Clip before=ProjectStore.Clip.fromJson(c.toJson());
                long oldEnd = TimelineMath.add(c.startMs, c.outputDurationMs());
                if (a.has("startMs")) c.startMs = a.getLong("startMs");
                if (c.startMs < 0) throw new IllegalArgumentException("Negative clip position");
                c.inMs = a.optLong("inMs", c.inMs); c.outMs = a.optLong("outMs", c.outMs);
                validateRange(c, asset);
                if (a.optBoolean("ripple", false)) shiftAfter(p, c.trackId, oldEnd,
                        c.outputDurationMs() - (oldEnd - c.startMs), c.id);
                noOverlap(p, c);
                long shift=Math.round((c.inMs-before.inMs)/(double)c.speed);
                c.keyframes=sliceKeyframes(before,shift,Math.addExact(shift,c.outputDurationMs()));break;
            }
            case "split_clip": {
                long at = a.optLong("atMs", -1);
                long local = at - c.startMs;
                if (at < c.startMs || local <= 0 || local >= c.outputDurationMs())
                    throw new IllegalArgumentException("Move the playhead inside the clip before splitting");
                long source = TimelineMath.sourceAt(c.inMs, c.outMs, c.speed, local);
                if (source <= c.inMs || source >= c.outMs) throw new IllegalArgumentException("Split has no source frame on one side");
                ProjectStore.Clip right = ProjectStore.Clip.fromJson(c.toJson()); right.id = UUID.randomUUID().toString();
                long originalDuration=c.outputDurationMs();
                JSONArray leftFrames=sliceKeyframes(c,0,local);
                JSONArray rightFrames=sliceKeyframes(c,local,originalDuration);
                right.inMs = source; right.startMs = at; c.outMs = source;
                c.keyframes=leftFrames;right.keyframes=rightFrames;
                p.clips.add(p.clips.indexOf(c) + 1, right); break;
            }
            case "remove_clip": {
                long oldEnd = TimelineMath.add(c.startMs, c.outputDurationMs());
                long duration = c.outputDurationMs();
                for (ProjectStore.Clip member : linkedClips(p, c)) {
                    editableTrack(p, member.trackId); p.clips.remove(member);
                }
                if (a.optBoolean("ripple", false)) shiftAfter(p, c.trackId, oldEnd, -duration, c.id);
                break;
            }
            case "duplicate_clip": {
                long end = TimelineMath.add(c.startMs, c.outputDurationMs());
                shiftAfter(p, c.trackId, end, c.outputDurationMs(), c.id);
                ProjectStore.Clip copy = ProjectStore.Clip.fromJson(c.toJson()); copy.id = UUID.randomUUID().toString();
                copy.startMs = end; copy.linkGroup = ""; p.clips.add(p.clips.indexOf(c) + 1, copy); break;
            }
            case "slip_clip": {
                long delta = a.getLong("deltaMs");
                if (delta < 0 && c.inMs < -delta) throw new IllegalArgumentException("Slip exceeds the source start");
                c.inMs = Math.addExact(c.inMs, delta); c.outMs = Math.addExact(c.outMs, delta);
                validateRange(c, asset); break;
            }
            case "roll_clip": {
                if(!c.linkGroup.isEmpty())throw new IllegalArgumentException("Unlink clips before rolling this boundary");
                ProjectStore.Clip right=adjacent(p,c,false);rollBoundary(p,c,right,a.getLong("deltaMs"));break;
            }
            case "slide_clip": {
                if(!c.linkGroup.isEmpty())throw new IllegalArgumentException("Unlink clips before sliding");
                ProjectStore.Clip left=adjacent(p,c,true),right=adjacent(p,c,false);
                long delta=a.getLong("deltaMs"),oldIn=c.inMs,oldOut=c.outMs;
                JSONArray originalFrames=new JSONArray(c.keyframes.toString());
                rollBoundary(p,left,c,delta);rollBoundary(p,c,right,delta);
                c.inMs=oldIn;c.outMs=oldOut;c.keyframes=originalFrames;noOverlap(p,left);noOverlap(p,c);noOverlap(p,right);break;
            }
            case "set_speed": {
                double speed = a.getDouble("speed");
                if (!Double.isFinite(speed) || speed < .25 || speed > 4) throw new IllegalArgumentException("Speed must be 0.25–4");
                long oldEnd = TimelineMath.add(c.startMs, c.outputDurationMs()); c.speed = (float) speed;
                if (a.optBoolean("ripple", true)) shiftAfter(p, c.trackId, oldEnd, c.outputDurationMs() - (oldEnd - c.startMs), c.id);
                noOverlap(p, c); trimKeyframes(c); break;
            }
            case "set_property": {
                String property = a.getString("property"); double value = a.getDouble("value");
                validateProperty(property, value);
                if ("volume".equals(property)) c.volume = (float) value;
                else if ("pan".equals(property)) c.pan = (float) value;
                else c.effects.put(property, value);
                validateCrop(c); break;
            }
            case "set_properties": {
                JSONObject values = a.getJSONObject("values");
                java.util.Iterator<String> keys = values.keys();
                while (keys.hasNext()) {
                    String property = keys.next(); double value = values.getDouble(property);
                    validateProperty(property, value);
                    if ("volume".equals(property)) c.volume = (float)value;
                    else if ("pan".equals(property)) c.pan = (float)value;
                    else c.effects.put(property, value);
                }
                validateCrop(c); break;
            }
            case "set_keyframe": {
                String property = a.getString("property"); double value = a.getDouble("value");
                validateProperty(property, value);
                if (property.startsWith("crop") || "blur".equals(property))
                    throw new IllegalArgumentException("This property supports static editing; animated crop/blur needs a dynamic effect provider");
                long time = a.getLong("timeMs");
                if (time < 0 || time > c.outputDurationMs()) throw new IllegalArgumentException("Keyframe must be within the clip");
                String easing = a.optString("easing", "linear");
                if (!EASING.contains(easing)) throw new IllegalArgumentException("Unknown easing curve");
                ArrayList<JSONObject> frames = new ArrayList<>();
                for (int i = 0; i < c.keyframes.length(); i++) {
                    JSONObject f = c.keyframes.getJSONObject(i);
                    if (!(property.equals(f.optString("property")) && time == f.optLong("timeMs"))) frames.add(f);
                }
                JSONObject frame = new JSONObject(); frame.put("property", property); frame.put("timeMs", time);
                frame.put("value", value); frame.put("easing", easing); frames.add(frame);
                frames.sort(Comparator.comparingLong(f -> f.optLong("timeMs")));
                c.keyframes = new JSONArray(); for (JSONObject f : frames) c.keyframes.put(f);
                break;
            }
            case "remove_keyframe": {
                JSONArray frames = new JSONArray();
                for (int i = 0; i < c.keyframes.length(); i++) {
                    JSONObject f = c.keyframes.getJSONObject(i);
                    if (!(a.optString("property").equals(f.optString("property")) && a.optLong("timeMs", -1) == f.optLong("timeMs"))) frames.put(f);
                }
                c.keyframes = frames; break;
            }
            case "set_creator_style": {
                if(asset==null||asset.mime.startsWith("audio/"))throw new IllegalArgumentException("Creator styles require a video or image source");
                JSONObject settings=a.getJSONObject("settings");CreatorStyleSettings.validate(settings);
                java.util.Iterator<String> keys=settings.keys();while(keys.hasNext()){String key=keys.next();c.effects.put(key,settings.getString(key));}break;
            }
            case "set_composite_effects": {
                if(asset==null||asset.mime.startsWith("audio/"))throw new IllegalArgumentException("Composite effects require a video or image source");
                JSONObject settings=a.getJSONObject("settings");ClipCompositeSettings.validate(settings);
                for(String key:ClipCompositeSettings.KEYS)c.effects.remove(key);
                java.util.Iterator<String> keys=settings.keys();while(keys.hasNext()){String key=keys.next();c.effects.put(key,settings.get(key));}break;
            }
            case "set_audio_effects": {
                if(asset==null||asset.mime.startsWith("image/"))throw new IllegalArgumentException("Audio effects require an audio or video source");
                JSONObject settings=a.getJSONObject("settings");AudioDspSettings.read(settings);
                if(settings.length()==0)c.effects.remove("audioDsp");else c.effects.put("audioDsp",new JSONObject(settings.toString()));break;
            }
            case "set_title": c.title = a.getString("text"); break;
            case "link_clips": {
                String group = UUID.randomUUID().toString(); JSONArray ids = a.getJSONArray("clipIds");
                if (ids.length() < 2) throw new IllegalArgumentException("Select at least two clips to link");
                for (int i = 0; i < ids.length(); i++) {
                    ProjectStore.Clip member = requireClip(p, ids.getString(i)); editableTrack(p, member.trackId); member.linkGroup = group;
                }
                break;
            }
            case "unlink_clip": for (ProjectStore.Clip member : linkedClips(p, c)) member.linkGroup = ""; break;
            default: throw new IllegalArgumentException("Unknown editor operation: " + op);
        }
    }

    public static ProjectStore.Clip requireClip(ProjectStore.Project p, String id) {
        ProjectStore.Clip c = p.clip(id);
        if (c == null) throw new IllegalArgumentException("Clip not found"); return c;
    }
    private static ProjectStore.Track requireTrack(ProjectStore.Project p, String id) {
        ProjectStore.Track t = p.track(id);
        if (t == null) throw new IllegalArgumentException("Track not found"); return t;
    }
    private static ProjectStore.Track editableTrack(ProjectStore.Project p, String id) {
        ProjectStore.Track t = requireTrack(p, id);
        if (t.locked) throw new IllegalArgumentException("Track is locked: " + t.name); return t;
    }
    private static void compatible(ProjectStore.Track t, ProjectStore.Asset a) {
        if (a == null || a.mime == null) throw new IllegalArgumentException("Project-owned media is missing");
        if (t.audioOnly() && !a.mime.startsWith("audio/") && !a.mime.startsWith("video/"))
            throw new IllegalArgumentException("Choose an audio or video source for this audio track");
        if (!t.audioOnly() && a.mime.startsWith("audio/")) throw new IllegalArgumentException("Choose an audio track for audio media");
    }
    private static void validateRange(ProjectStore.Clip c, ProjectStore.Asset a) {
        if (a == null) throw new IllegalArgumentException("Source asset not found");
        if (!Float.isFinite(c.speed) || c.speed < .25 || c.speed > 4 || c.inMs < 0 || c.outMs <= c.inMs)
            throw new IllegalArgumentException("Invalid clip source range or speed");
        if (a.mime != null && !a.mime.startsWith("image/") && a.durationMs > 0 && c.outMs > a.durationMs)
            throw new IllegalArgumentException("Trim exceeds the source duration");
    }
    public static long trackEnd(ProjectStore.Project p, String trackId) {
        long end = 0;
        for (ProjectStore.Clip c : p.clips) if (trackId.equals(c.trackId)) end = Math.max(end, TimelineMath.add(c.startMs, c.outputDurationMs()));
        return end;
    }
    private static void noOverlap(ProjectStore.Project p, ProjectStore.Clip c) {
        long end = TimelineMath.add(c.startMs, c.outputDurationMs());
        for (ProjectStore.Clip other : p.clips) {
            if (other == c || c.id.equals(other.id) || !c.trackId.equals(other.trackId)) continue;
            long otherEnd = TimelineMath.add(other.startMs, other.outputDurationMs());
            if (c.startMs < otherEnd && other.startMs < end) throw new IllegalArgumentException("Clips overlap on this track; use another video track or ripple editing");
        }
    }
    private static void shiftAfter(ProjectStore.Project p, String track, long at, long delta, String except) {
        editableTrack(p, track);
        for (ProjectStore.Clip c : p.clips) if (track.equals(c.trackId) && !c.id.equals(except) && c.startMs >= at)
            c.startMs = delta >= 0 ? TimelineMath.add(c.startMs, delta) : Math.max(0, c.startMs + delta);
    }
    private static List<ProjectStore.Clip> linkedClips(ProjectStore.Project p, ProjectStore.Clip source) {
        ArrayList<ProjectStore.Clip> out = new ArrayList<>();
        for (ProjectStore.Clip c : p.clips) if (c == source || (!source.linkGroup.isEmpty() && source.linkGroup.equals(c.linkGroup))) out.add(c);
        return out;
    }
    private static ProjectStore.Clip adjacent(ProjectStore.Project p,ProjectStore.Clip c,boolean before){
        for(ProjectStore.Clip other:p.clips)if(other!=c&&other.trackId.equals(c.trackId)){
            if(before&&TimelineMath.add(other.startMs,other.outputDurationMs())==c.startMs)return other;
            if(!before&&other.startMs==TimelineMath.add(c.startMs,c.outputDurationMs()))return other;
        }
        throw new IllegalArgumentException("This operation needs adjacent clips without a gap");
    }
    private static void rollBoundary(ProjectStore.Project p,ProjectStore.Clip left,ProjectStore.Clip right,long delta)throws Exception{
        editableTrack(p,left.trackId);editableTrack(p,right.trackId);
        if(!left.linkGroup.isEmpty()||!right.linkGroup.isEmpty())throw new IllegalArgumentException("Unlink clips before rolling a boundary");
        ProjectStore.Clip beforeLeft=ProjectStore.Clip.fromJson(left.toJson()),beforeRight=ProjectStore.Clip.fromJson(right.toJson());
        left.outMs=Math.addExact(left.outMs,Math.round((double)delta*left.speed));right.inMs=Math.addExact(right.inMs,Math.round((double)delta*right.speed));right.startMs=Math.addExact(right.startMs,delta);
        validateRange(left,p.asset(left.assetId));validateRange(right,p.asset(right.assetId));if(right.startMs<0)throw new IllegalArgumentException("Boundary would move before zero");
        left.keyframes=sliceKeyframes(beforeLeft,0,left.outputDurationMs());right.keyframes=sliceKeyframes(beforeRight,delta,Math.addExact(delta,right.outputDurationMs()));
        noOverlap(p,left);noOverlap(p,right);
    }
    private static void trimKeyframes(ProjectStore.Clip c) throws Exception {
        c.keyframes=sliceKeyframes(c,0,c.outputDurationMs());
    }
    /** Keep the curve's values and shape when a trim/split cuts through a segment. */
    private static JSONArray sliceKeyframes(ProjectStore.Clip clip,long from,long to) throws Exception {
        if(to<=from)throw new IllegalArgumentException("Empty keyframe slice");
        java.util.LinkedHashSet<String> properties=new java.util.LinkedHashSet<>();
        for(int i=0;i<clip.keyframes.length();i++)properties.add(clip.keyframes.getJSONObject(i).getString("property"));
        ArrayList<JSONObject> output=new ArrayList<>();
        for(String property:properties){
            ArrayList<JSONObject> original=new ArrayList<>();
            for(int i=0;i<clip.keyframes.length();i++){
                JSONObject f=clip.keyframes.getJSONObject(i);if(property.equals(f.optString("property")))original.add(f);
            }
            original.sort(Comparator.comparingLong(f->f.optLong("timeMs")));
            KeyframeCurve curve=new KeyframeCurve(clip,property,0);
            ArrayList<Long> times=new ArrayList<>();times.add(from);
            for(JSONObject f:original){long time=f.getLong("timeMs");if(time>from&&time<to)times.add(time);}
            times.add(to);
            for(int i=0;i<times.size();i++){
                long time=times.get(i);JSONObject f=new JSONObject();
                f.put("property",property);f.put("timeMs",Math.subtractExact(time,from));f.put("value",curve.valueAt(time));f.put("easing","hold");
                for(int j=0;j+1<original.size()&&i+1<times.size();j++){
                    JSONObject left=original.get(j),right=original.get(j+1);
                    long begin=left.getLong("timeMs"),end=right.getLong("timeMs");
                    if(time<begin||time>=end)continue;
                    double s=left.optDouble("easingStart",0),e=left.optDouble("easingEnd",1);
                    f.put("easing",left.optString("easing","linear"));
                    f.put("easingStart",s+(e-s)*(time-begin)/(double)(end-begin));
                    f.put("easingEnd",s+(e-s)*(Math.min(end,times.get(i+1))-begin)/(double)(end-begin));break;
                }
                output.add(f);
            }
        }
        output.sort(Comparator.comparingLong(f->f.optLong("timeMs")));
        JSONArray next=new JSONArray();for(JSONObject f:output)next.put(f);return next;
    }
    public static void validateProperty(String property, double value) {
        if (!PROPERTIES.contains(property) || !Double.isFinite(value)) throw new IllegalArgumentException("Unknown or nonfinite property");
        double min = -8, max = 8;
        if (property.startsWith("scale")) { min = .01; max = 16; }
        else if ("opacity".equals(property) || property.startsWith("crop")) { min = 0; max = 1; }
        else if ("rotate".equals(property)) { min = -36000; max = 36000; }
        else if ("volume".equals(property)) { min = 0; max = 2; }
        else if ("blur".equals(property)) { min = 0; max = 18; }
        else if (property.endsWith("Adjust")) { min = -100; max = 100; }
        else if ("brightness".equals(property) || "contrast".equals(property) || "pan".equals(property) || property.startsWith("anchor")) { min = -1; max = 1; }
        if (value < min || value > max) throw new IllegalArgumentException(property + " must be between " + min + " and " + max);
    }
    private static void validateCrop(ProjectStore.Clip c) {
        if (c.effects.optDouble("cropLeft") + c.effects.optDouble("cropRight") >= 1 ||
                c.effects.optDouble("cropTop") + c.effects.optDouble("cropBottom") >= 1)
            throw new IllegalArgumentException("Crop must retain some image area");
    }
    public static double valueAt(ProjectStore.Clip c, String property, long localMs, double fallback) {
        double base="volume".equals(property)?c.volume:"pan".equals(property)?c.pan:c.effects.optDouble(property,fallback);
        return new KeyframeCurve(c,property,base).valueAt(localMs);
    }
}
