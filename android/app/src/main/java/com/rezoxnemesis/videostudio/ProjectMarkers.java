package com.rezoxnemesis.videostudio;

import org.json.JSONArray;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;
import java.util.UUID;

/** Absolute program markers and owner In/Out selection, shared by UI and MCP. */
public final class ProjectMarkers {
    public static final int MAX_MARKERS = 256;
    public static final int MAX_NAME_CHARACTERS = 120;
    public static final int MAX_NOTE_CHARACTERS = 2048;
    public static final int MAX_METADATA_BYTES = 128 * 1024;
    public static final String DEFAULT_COLOR = "#F6B654";
    private static final String INVALID_RANGE = "Program duration no longer contains the selected range";

    private ProjectMarkers() {}

    public static final class Range {
        public final boolean defined;
        public final boolean enabled;
        public final boolean valid;
        public final long inMs;
        public final long outMs;
        public final String reason;

        private Range(boolean defined, boolean enabled, boolean valid, long inMs, long outMs, String reason) {
            this.defined = defined; this.enabled = enabled; this.valid = valid;
            this.inMs = inMs; this.outMs = outMs; this.reason = reason;
        }
    }

    /** A point at program end is useful; a marker range uses an exclusive end. */
    public static JSONObject add(ProjectStore.Project project, JSONObject settings) {
        requireProject(project);
        JSONObject options = requireOptions(settings);
        allowed(options, "markerId", "atMs", "endMs", "name", "color", "note");
        validate(project);
        if (project.markers.length() >= MAX_MARKERS) throw new IllegalArgumentException("Project supports at most 256 markers");
        String id = options.has("markerId") ? identifier(options, "markerId") : UUID.randomUUID().toString();
        if (indexOf(project, id) >= 0) throw new IllegalArgumentException("Marker ID already exists; update that marker instead");
        JSONObject marker = new JSONObject();
        put(marker, "id", id);
        put(marker, "atMs", exactLong(options, "atMs"));
        put(marker, "name", options.has("name") ? name(options) : "Marker");
        put(marker, "color", options.has("color") ? color(options) : DEFAULT_COLOR);
        put(marker, "note", options.has("note") ? note(options) : "");
        if (options.has("endMs") && !options.isNull("endMs")) put(marker, "endMs", exactLong(options, "endMs"));
        validateMarker(marker);
        requireInsideProgram(project, marker);
        JSONArray next = copyArray(project.markers);
        next.put(marker);
        requireMetadataBudget(next, project.editorRange);
        project.markers = next;
        return copy(marker);
    }

    /** Timing is optional, so an off-program marker can still be renamed or removed. */
    public static JSONObject update(ProjectStore.Project project, JSONObject settings) {
        requireProject(project);
        JSONObject options = requireOptions(settings);
        allowed(options, "markerId", "atMs", "endMs", "name", "color", "note");
        validate(project);
        String id = identifier(options, "markerId");
        int index = indexOf(project, id);
        if (index < 0) throw new IllegalArgumentException("Marker not found");
        JSONObject marker = copy(project.markers.optJSONObject(index));
        if (options.has("atMs")) put(marker, "atMs", exactLong(options, "atMs"));
        if (options.has("endMs")) {
            if (options.isNull("endMs")) marker.remove("endMs");
            else put(marker, "endMs", exactLong(options, "endMs"));
        }
        if (options.has("name")) put(marker, "name", name(options));
        if (options.has("color")) put(marker, "color", color(options));
        if (options.has("note")) put(marker, "note", note(options));
        validateMarker(marker);
        if (options.has("atMs") || options.has("endMs")) requireInsideProgram(project, marker);
        JSONArray next = new JSONArray();
        for (int i = 0; i < project.markers.length(); i++) next.put(i == index ? marker : copy(project.markers.optJSONObject(i)));
        requireMetadataBudget(next, project.editorRange);
        project.markers = next;
        return copy(marker);
    }

    public static void remove(ProjectStore.Project project, JSONObject settings) {
        JSONObject options = requireOptions(settings);
        allowed(options, "markerId");
        remove(project, identifier(options, "markerId"));
    }

    public static void remove(ProjectStore.Project project, String markerId) {
        requireProject(project);
        validate(project);
        if (!validIdentifier(markerId)) throw new IllegalArgumentException("Marker ID is invalid");
        int index = indexOf(project, markerId);
        if (index < 0) throw new IllegalArgumentException("Marker not found");
        JSONArray next = new JSONArray();
        for (int i = 0; i < project.markers.length(); i++) if (i != index) next.put(copy(project.markers.optJSONObject(i)));
        project.markers = next;
    }

    /** Returns detached metadata with a derived status; it does not retime authored markers. */
    public static JSONArray list(ProjectStore.Project project) {
        requireProject(project);
        validate(project);
        JSONArray result = new JSONArray();
        long duration = project.outputDurationMs();
        for (int i = 0; i < project.markers.length(); i++) {
            JSONObject marker = copy(project.markers.optJSONObject(i));
            long end = marker.has("endMs") ? exactLong(marker, "endMs") : exactLong(marker, "atMs");
            put(marker, "inProgram", end <= duration);
            result.put(marker);
        }
        return result;
    }

    public static void setRange(ProjectStore.Project project, JSONObject settings) {
        requireProject(project);
        JSONObject options = requireOptions(settings);
        allowed(options, "inMs", "outMs", "enabled");
        long in = exactLong(options, "inMs"), out = exactLong(options, "outMs");
        if (in < 0L || out <= in || out > project.outputDurationMs())
            throw new IllegalArgumentException("Program range must satisfy 0 <= In < Out <= program duration");
        boolean enabled = options.has("enabled") ? exactBoolean(options, "enabled") : true;
        JSONObject range = new JSONObject();
        put(range, "enabled", enabled); put(range, "inMs", in); put(range, "outMs", out);
        requireMetadataBudget(project.markers, range);
        project.editorRange = range;
    }

    public static void clearRange(ProjectStore.Project project) {
        requireProject(project);
        project.editorRange = new JSONObject();
    }

    /** Safe even before a pending graph transaction is reconciled. No clamping is hidden. */
    public static Range selection(ProjectStore.Project project) {
        requireProject(project);
        JSONObject range = project.editorRange;
        if (range == null || range.length() == 0) return new Range(false, false, false, 0L, 0L, "");
        try {
            long in = exactLong(range, "inMs"), out = exactLong(range, "outMs");
            boolean valid = in >= 0L && out > in && out <= project.outputDurationMs();
            boolean requested = exactBoolean(range, "enabled");
            String reason = valid ? (requested ? "" : range.has("invalidatedReason")
                    ? "Range was disabled after the program changed; set In/Out again to enable"
                    : "Range disabled") : INVALID_RANGE;
            return new Range(true, requested && valid, valid, in, out, reason);
        } catch (IllegalArgumentException invalid) {
            return new Range(true, false, false, 0L, 0L, "Stored program range is invalid");
        }
    }

    public static JSONObject rangeJson(ProjectStore.Project project) {
        Range selection = selection(project);
        JSONObject result = project.editorRange == null ? new JSONObject() : copy(project.editorRange);
        put(result, "defined", selection.defined); put(result, "valid", selection.valid);
        put(result, "enabled", selection.enabled); put(result, "programDurationMs", project.outputDurationMs());
        if (!selection.reason.isEmpty()) put(result, "reason", selection.reason);
        return result;
    }

    /**
     * Called on mutable project commits, not on reads or pinned export graphs.
     * Shortening disables an impossible selection and preserves its exact bounds
     * with an explicit reason. Growing the project never silently re-enables it.
     */
    public static void reconcile(ProjectStore.Project project) {
        requireProject(project);
        if (project.editorRange == null || project.editorRange.length() == 0) return;
        Range range = selection(project);
        if (range.defined && !range.valid && Boolean.TRUE.equals(project.editorRange.opt("enabled"))) {
            put(project.editorRange, "enabled", false);
            put(project.editorRange, "invalidatedReason", INVALID_RANGE);
            put(project.editorRange, "invalidatedAtDurationMs", project.outputDurationMs());
        }
    }

    /** Marker positions may remain beyond the current program after a later clip edit. */
    public static void validate(ProjectStore.Project project) {
        requireProject(project);
        if (project.markers == null || project.editorRange == null)
            throw new IllegalArgumentException("Project marker and program range metadata is required");
        if (project.markers.length() > MAX_MARKERS) throw new IllegalArgumentException("Project supports at most 256 markers");
        Set<String> ids = new HashSet<>();
        for (int i = 0; i < project.markers.length(); i++) {
            JSONObject marker = project.markers.optJSONObject(i);
            validateMarker(marker);
            if (!ids.add(marker.optString("id"))) throw new IllegalArgumentException("Markers require unique stable IDs");
        }
        JSONObject range = project.editorRange;
        if (range.length() > 0) {
            allowed(range, "enabled", "inMs", "outMs", "invalidatedReason", "invalidatedAtDurationMs");
            long in = exactLong(range, "inMs"), out = exactLong(range, "outMs");
            if (in < 0L || out <= in) throw new IllegalArgumentException("Stored program range timing is invalid");
            boolean enabled = exactBoolean(range, "enabled");
            if (enabled && out > project.outputDurationMs()) throw new IllegalArgumentException("Active program range exceeds program duration");
            if (range.has("invalidatedReason")) {
                Object reason = range.opt("invalidatedReason");
                if (!(reason instanceof String) || ((String) reason).length() > 256)
                    throw new IllegalArgumentException("Range invalidation reason is invalid");
            }
            if (range.has("invalidatedAtDurationMs") && exactLong(range, "invalidatedAtDurationMs") < 0L)
                throw new IllegalArgumentException("Range invalidation duration cannot be negative");
        }
        requireMetadataBudget(project.markers, range);
    }

    private static void validateMarker(JSONObject marker) {
        if (marker == null) throw new IllegalArgumentException("Every marker must be an object");
        allowed(marker, "id", "atMs", "endMs", "name", "color", "note");
        identifier(marker, "id");
        long at = exactLong(marker, "atMs");
        if (at < 0L) throw new IllegalArgumentException("Marker program time cannot be negative");
        if (marker.has("endMs") && exactLong(marker, "endMs") <= at)
            throw new IllegalArgumentException("Marker range end must follow its program time");
        name(marker); color(marker); note(marker);
    }

    private static void requireInsideProgram(ProjectStore.Project project, JSONObject marker) {
        long duration = project.outputDurationMs();
        long end = marker.has("endMs") ? exactLong(marker, "endMs") : exactLong(marker, "atMs");
        if (end > duration) throw new IllegalArgumentException("Marker timing exceeds program duration");
    }

    private static int indexOf(ProjectStore.Project project, String id) {
        for (int i = 0; i < project.markers.length(); i++) {
            JSONObject marker = project.markers.optJSONObject(i);
            if (marker != null && id.equals(marker.optString("id", ""))) return i;
        }
        return -1;
    }

    private static String identifier(JSONObject object, String key) {
        Object value = object.opt(key);
        if (!(value instanceof String) || !validIdentifier((String) value))
            throw new IllegalArgumentException(key + " must contain 1 to 128 ID characters");
        return (String) value;
    }

    private static boolean validIdentifier(String id) {
        return id != null && id.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}");
    }

    private static String name(JSONObject object) {
        Object value = object.opt("name");
        if (!(value instanceof String)) throw new IllegalArgumentException("Marker name must be text");
        String text = ((String) value).trim();
        if (text.isEmpty() || text.length() > MAX_NAME_CHARACTERS)
            throw new IllegalArgumentException("Marker name must contain 1 to 120 characters");
        return text;
    }

    private static String note(JSONObject object) {
        Object value = object.opt("note");
        if (!(value instanceof String) || ((String) value).length() > MAX_NOTE_CHARACTERS)
            throw new IllegalArgumentException("Marker note must contain at most 2048 characters");
        return (String) value;
    }

    private static String color(JSONObject object) {
        Object value = object.opt("color");
        if (!(value instanceof String) || !((String) value).matches("#[0-9A-Fa-f]{6}"))
            throw new IllegalArgumentException("Marker color must be #RRGGBB");
        return ((String) value).toUpperCase(java.util.Locale.US);
    }

    private static long exactLong(JSONObject object, String key) {
        Object value = object.opt(key);
        if (!(value instanceof Number)) throw new IllegalArgumentException(key + " must be an integer program time in milliseconds");
        Number number = (Number) value;
        if (value instanceof Byte || value instanceof Short || value instanceof Integer || value instanceof Long) return number.longValue();
        double floating = number.doubleValue();
        if (Double.isNaN(floating) || Double.isInfinite(floating) || floating != Math.rint(floating)
                || Math.abs(floating) > 9007199254740991d)
            throw new IllegalArgumentException(key + " must be an exact integer program time in milliseconds");
        return (long) floating;
    }

    private static boolean exactBoolean(JSONObject object, String key) {
        Object value = object.opt(key);
        if (!(value instanceof Boolean)) throw new IllegalArgumentException(key + " must be boolean");
        return (Boolean) value;
    }

    private static void allowed(JSONObject object, String... keys) {
        Set<String> allowed = new HashSet<>();
        java.util.Collections.addAll(allowed, keys);
        Iterator<String> iterator = object.keys();
        while (iterator.hasNext()) {
            String key = iterator.next();
            if (!allowed.contains(key)) throw new IllegalArgumentException("Unsupported marker/range field: " + key);
        }
    }

    private static void requireMetadataBudget(JSONArray markers, JSONObject range) {
        if (markers == null || range == null) throw new IllegalArgumentException("Project marker metadata is required");
        long bytes = markers.toString().getBytes(StandardCharsets.UTF_8).length
                + (long) range.toString().getBytes(StandardCharsets.UTF_8).length;
        if (bytes > MAX_METADATA_BYTES) throw new IllegalArgumentException("Project marker metadata exceeds 128 KiB; shorten notes or remove markers");
    }

    private static JSONObject requireOptions(JSONObject options) {
        if (options == null) throw new IllegalArgumentException("Marker/range settings are required");
        return options;
    }

    private static void requireProject(ProjectStore.Project project) {
        if (project == null) throw new IllegalArgumentException("Project is required");
    }

    private static void put(JSONObject target, String key, Object value) {
        try { target.put(key, value); }
        catch (Exception failure) { throw new IllegalArgumentException("Could not encode marker metadata", failure); }
    }

    private static JSONObject copy(JSONObject object) {
        if (object == null) throw new IllegalArgumentException("Marker metadata is invalid");
        try { return new JSONObject(object.toString()); }
        catch (Exception failure) { throw new IllegalArgumentException("Could not copy marker metadata", failure); }
    }

    private static JSONArray copyArray(JSONArray array) {
        try { return new JSONArray(array.toString()); }
        catch (Exception failure) { throw new IllegalArgumentException("Could not copy markers", failure); }
    }
}
