package com.rezoxnemesis.videostudio;

import org.json.JSONArray;
import org.json.JSONObject;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Locale;

/** Pure edits of existing cel vectors. Factory publication owns files, revisions and undo. */
public final class AnimationCelVectorEdits {
    public static final int MAX_ACTIONS = 64;
    public static final int MAX_ACTION_BYTES = 32 * 1024;

    private AnimationCelVectorEdits() { }

    /**
     * Admission validation without reading a drawing. IDs and point indices are
     * resolved against the accepted document only when apply runs. The result
     * shares no mutable JSON objects with the caller.
     */
    public static JSONArray validateActions(JSONArray supplied) {
        try {
            if (supplied == null || supplied.length() < 1 || supplied.length() > MAX_ACTIONS)
                throw new IllegalArgumentException("A cel vector edit requires 1 to 64 actions");
            JSONArray checked = new JSONArray();
            for (int index = 0; index < supplied.length(); index++) {
                JSONObject raw = supplied.optJSONObject(index);
                if (raw == null) throw new IllegalArgumentException("Cel vector actions must be objects");
                String operation = text(raw, "op"), strokeId = identity(raw, "strokeId");
                JSONObject action = new JSONObject().put("op", operation).put("strokeId", strokeId);
                switch (operation) {
                    case "set_stroke":
                        fields(raw, "op", "strokeId", "color", "width");
                        if (!raw.has("color") && !raw.has("width"))
                            throw new IllegalArgumentException("set_stroke requires color or width");
                        if (raw.has("color")) {
                            String color = text(raw, "color");
                            if (!color.matches("#[0-9a-fA-F]{6}([0-9a-fA-F]{2})?"))
                                throw new IllegalArgumentException("Stroke color must be #RRGGBB or #AARRGGBB");
                            action.put("color", color.toUpperCase(Locale.ROOT));
                        }
                        if (raw.has("width")) action.put("width", number(raw, "width", .001, .2));
                        break;
                    case "move_point":
                    case "insert_point":
                        fields(raw, "op", "strokeId", "pointIndex", "x", "y", "pressure");
                        action.put("pointIndex", integer(raw, "pointIndex", 0,
                                AnimationCelFactory.MAX_POINTS - ("move_point".equals(operation) ? 1 : 0)));
                        action.put("x", number(raw, "x", 0, 1)).put("y", number(raw, "y", 0, 1));
                        if (raw.has("pressure")) action.put("pressure", number(raw, "pressure", .1, 1.5));
                        break;
                    case "translate_stroke":
                        fields(raw, "op", "strokeId", "dx", "dy");
                        action.put("dx", number(raw, "dx", -1, 1)).put("dy", number(raw, "dy", -1, 1));
                        break;
                    case "delete_stroke":
                        fields(raw, "op", "strokeId");
                        break;
                    case "remove_point":
                        fields(raw, "op", "strokeId", "pointIndex");
                        action.put("pointIndex", integer(raw, "pointIndex", 0, AnimationCelFactory.MAX_POINTS - 1));
                        break;
                    default:
                        throw new IllegalArgumentException("Unknown cel vector operation: " + operation);
                }
                checked.put(action);
            }
            // Serialize only detached, bounded scalar actions; unknown caller
            // trees never reach JSONObject/JSONArray serialization.
            byteBudget(checked);
            return checked;
        } catch (IllegalArgumentException error) { throw error; }
        catch (Exception error) { throw new IllegalArgumentException("Invalid cel vector actions: " + error.getMessage(), error); }
    }

    /**
     * Actions apply in order, so point indices refer to the results of previous
     * actions in this batch. Every failure leaves the supplied drawing intact.
     * The complete result uses the same normalization as PNG publication.
     */
    public static JSONObject apply(JSONObject drawing, JSONArray actions) {
        try {
            JSONArray checked = validateActions(actions);
            drawingShape(drawing);
            JSONObject edited = AnimationCelFactory.validateDrawing(drawing);
            String original = edited.toString();
            JSONArray strokes = edited.getJSONArray("strokes");
            int totalPoints = 0;
            for (int index = 0; index < strokes.length(); index++)
                totalPoints += strokes.getJSONObject(index).getJSONArray("points").length();
            for (int index = 0; index < checked.length(); index++) {
                JSONObject action = checked.getJSONObject(index);
                String operation = action.getString("op"), id = action.getString("strokeId");
                int strokeIndex = findStroke(strokes, id);
                if (strokeIndex < 0) throw new IllegalArgumentException("Cel stroke no longer exists: " + id);
                JSONObject stroke = strokes.getJSONObject(strokeIndex);
                JSONArray points = stroke.getJSONArray("points");
                switch (operation) {
                    case "set_stroke":
                        if (action.has("color")) stroke.put("color", action.getString("color"));
                        if (action.has("width")) stroke.put("width", action.getDouble("width"));
                        break;
                    case "move_point": {
                        int pointIndex = existingPoint(action, points);
                        JSONObject point = points.getJSONObject(pointIndex);
                        point.put("x", action.getDouble("x")).put("y", action.getDouble("y"));
                        if (action.has("pressure")) point.put("pressure", action.getDouble("pressure"));
                        break;
                    }
                    case "translate_stroke": {
                        double dx = action.getDouble("dx"), dy = action.getDouble("dy");
                        for (int pointIndex = 0; pointIndex < points.length(); pointIndex++) {
                            JSONObject point = points.getJSONObject(pointIndex);
                            double x = point.getDouble("x") + dx, y = point.getDouble("y") + dy;
                            if (!Double.isFinite(x) || !Double.isFinite(y) || x < 0 || x > 1 || y < 0 || y > 1)
                                throw new IllegalArgumentException("Stroke translation would move a point outside the frame");
                            point.put("x", x).put("y", y);
                        }
                        break;
                    }
                    case "delete_stroke":
                        totalPoints -= points.length();
                        strokes.remove(strokeIndex);
                        break;
                    case "insert_point": {
                        int pointIndex = action.getInt("pointIndex");
                        if (pointIndex > points.length()) throw new IllegalArgumentException("Inserted point index exceeds the stroke length");
                        if (totalPoints >= AnimationCelFactory.MAX_POINTS)
                            throw new IllegalArgumentException("Drawing exceeds 8192 points; remove a point before inserting another");
                        JSONObject point = new JSONObject().put("x", action.getDouble("x")).put("y", action.getDouble("y"))
                                .put("pressure", action.optDouble("pressure", 1));
                        JSONArray replaced = new JSONArray();
                        for (int item = 0; item <= points.length(); item++) {
                            if (item == pointIndex) replaced.put(point);
                            if (item < points.length()) replaced.put(points.getJSONObject(item));
                        }
                        stroke.put("points", replaced); totalPoints++;
                        break;
                    }
                    case "remove_point": {
                        int pointIndex = existingPoint(action, points);
                        if (points.length() <= 1) throw new IllegalArgumentException("A stroke must retain one point; delete the stroke instead");
                        points.remove(pointIndex); totalPoints--;
                        break;
                    }
                    default: throw new IllegalArgumentException("Unknown cel vector operation");
                }
            }
            JSONObject result = AnimationCelFactory.validateDrawing(edited);
            if (original.equals(result.toString())) throw new IllegalArgumentException("These vector actions leave the drawing unchanged");
            return result;
        } catch (IllegalArgumentException error) { throw error; }
        catch (Exception error) { throw new IllegalArgumentException("Cel vector edit could not be applied: " + error.getMessage(), error); }
    }

    /** Convenience entry for owner gestures; settings omit the op discriminator. */
    public static JSONObject apply(JSONObject drawing, String operation, JSONObject settings) {
        try {
            if (settings == null) throw new IllegalArgumentException("Cel vector settings are required");
            if (settings.has("op")) throw new IllegalArgumentException("Single-action settings must omit op");
            if (settings.length() > 5) throw new IllegalArgumentException("Single-action settings exceed their field bound");
            JSONObject action = new JSONObject().put("op", operation);
            Iterator<String> keys = settings.keys();
            while (keys.hasNext()) { String key = keys.next(); action.put(key, settings.get(key)); }
            return apply(drawing, new JSONArray().put(action));
        } catch (IllegalArgumentException error) { throw error; }
        catch (Exception error) { throw new IllegalArgumentException("Invalid cel vector settings", error); }
    }

    private static int findStroke(JSONArray strokes, String id) throws Exception {
        for (int index = 0; index < strokes.length(); index++)
            if (id.equals(strokes.getJSONObject(index).getString("id"))) return index;
        return -1;
    }
    private static int existingPoint(JSONObject action, JSONArray points) throws Exception {
        int index = action.getInt("pointIndex");
        if (index >= points.length()) throw new IllegalArgumentException("Cel point no longer exists at pointIndex " + index);
        return index;
    }
    private static String text(JSONObject object, String key) {
        Object value = object.opt(key);
        if (!(value instanceof String) || ((String) value).length() > 256)
            throw new IllegalArgumentException(key + " must be a bounded string");
        return (String) value;
    }
    private static String identity(JSONObject object, String key) {
        String value = text(object, key);
        if (value.isEmpty() || value.length() > 256 || !value.equals(value.trim()))
            throw new IllegalArgumentException("A stable strokeId is required");
        for (int index = 0; index < value.length(); index++)
            if (Character.isWhitespace(value.charAt(index)) || Character.isISOControl(value.charAt(index)))
                throw new IllegalArgumentException("strokeId cannot contain whitespace or control characters");
        return value;
    }
    private static double number(JSONObject object, String key, double min, double max) {
        Object value = object.opt(key);
        numericShape(value, key);
        double number = ((Number) value).doubleValue();
        if (!Double.isFinite(number) || number < min || number > max) throw new IllegalArgumentException(key + " is outside vector bounds");
        return number;
    }
    private static int integer(JSONObject object, String key, int min, int max) {
        Object value = object.opt(key);
        numericShape(value, key);
        try {
            int exact = new BigDecimal(value.toString()).intValueExact();
            if (exact < min || exact > max) throw new IllegalArgumentException(key + " is outside vector index bounds");
            return exact;
        } catch (NumberFormatException | ArithmeticException invalid) {
            throw new IllegalArgumentException(key + " must be an exact bounded integer", invalid);
        }
    }
    private static void fields(JSONObject object, String... allowed) {
        HashSet<String> names = new HashSet<>(Arrays.asList(allowed));
        Iterator<String> keys = object.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            if (key.length() > 64 || !names.contains(key)) throw new IllegalArgumentException("Unknown cel vector field");
        }
    }
    /** Shape-only guard before Factory normalization; Factory remains the drawing schema authority. */
    private static void drawingShape(JSONObject drawing) throws Exception {
        if (drawing == null) throw new IllegalArgumentException("Drawing document is required");
        fields(drawing, "version", "width", "height", "background", "strokes");
        for (String key : new String[]{"version", "width", "height"}) if (drawing.has(key)) numericShape(drawing.opt(key), key);
        if (drawing.has("background")) text(drawing, "background");
        if (!drawing.has("strokes")) return;
        Object supplied = drawing.opt("strokes");
        if (!(supplied instanceof JSONArray)) throw new IllegalArgumentException("strokes must be an array");
        JSONArray strokes = (JSONArray) supplied;
        if (strokes.length() > AnimationCelFactory.MAX_STROKES) throw new IllegalArgumentException("Drawing exceeds its stroke bound");
        int total = 0;
        for (int index = 0; index < strokes.length(); index++) {
            JSONObject stroke = strokes.optJSONObject(index);
            if (stroke == null) throw new IllegalArgumentException("Drawing stroke must be an object");
            fields(stroke, "id", "type", "color", "width", "points");
            text(stroke, "id"); text(stroke, "type");
            if (stroke.has("color")) text(stroke, "color");
            if (stroke.has("width")) numericShape(stroke.opt("width"), "width");
            JSONArray points = stroke.optJSONArray("points");
            if (points == null || points.length() < 1 || points.length() > AnimationCelFactory.MAX_POINTS)
                throw new IllegalArgumentException("Stroke needs a bounded points array");
            total += points.length();
            if (total > AnimationCelFactory.MAX_POINTS) throw new IllegalArgumentException("Drawing exceeds its point bound");
            for (int item = 0; item < points.length(); item++) {
                JSONObject point = points.optJSONObject(item);
                if (point == null) throw new IllegalArgumentException("Stroke point must be an object");
                fields(point, "x", "y", "pressure");
                numericShape(point.opt("x"), "x"); numericShape(point.opt("y"), "y");
                if (point.has("pressure")) numericShape(point.opt("pressure"), "pressure");
            }
        }
    }
    private static void numericShape(Object value, String key) {
        if (value instanceof BigDecimal) {
            BigDecimal decimal = (BigDecimal) value;
            if (decimal.precision() > 64 || decimal.scale() < -64 || decimal.scale() > 64)
                throw new IllegalArgumentException(key + " exceeds its numeric metadata bound");
        } else if (value instanceof java.math.BigInteger) {
            if (((java.math.BigInteger) value).bitLength() > 128) throw new IllegalArgumentException(key + " exceeds its numeric metadata bound");
        } else if (!(value instanceof Byte || value instanceof Short || value instanceof Integer || value instanceof Long
                || value instanceof Float || value instanceof Double)) throw new IllegalArgumentException(key + " must be numeric");
        if (!Double.isFinite(((Number) value).doubleValue())) throw new IllegalArgumentException(key + " must be finite");
    }
    private static void byteBudget(JSONArray actions) {
        String encoded = actions.toString();
        if (encoded == null || encoded.length() > MAX_ACTION_BYTES || encoded.getBytes(StandardCharsets.UTF_8).length > MAX_ACTION_BYTES)
            throw new IllegalArgumentException("Cel vector actions exceed their 32 KiB metadata budget");
    }
}
