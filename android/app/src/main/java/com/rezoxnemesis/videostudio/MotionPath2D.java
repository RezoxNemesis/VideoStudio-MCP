package com.rezoxnemesis.videostudio;

import org.json.JSONArray;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

/** Compiled cubic spatial path in normalized GL program coordinates: X=2 is one canvas width. */
public final class MotionPath2D {
    public static final int MAX_POINTS = 128;
    public static final int MAX_METADATA_BYTES = 64 * 1024;

    public static final class Sample {
        public final double x, y, rotationDeg;
        public final boolean hasOrientation;
        private Sample(double x, double y, double rotationDeg, boolean hasOrientation) {
            this.x = x; this.y = y; this.rotationDeg = rotationDeg; this.hasOrientation = hasOrientation;
        }
    }

    private static final class Point {
        final double t, x, y, inX, inY, outX, outY;
        Point(JSONObject point) {
            t = number(point, "t", 0, 1); x = coordinate(point, "x"); y = coordinate(point, "y");
            if (point.has("inX") != point.has("inY") || point.has("outX") != point.has("outY"))
                throw new IllegalArgumentException("Path handles need paired X and Y coordinates");
            inX = point.has("inX") ? coordinate(point, "inX") : x;
            inY = point.has("inY") ? coordinate(point, "inY") : y;
            outX = point.has("outX") ? coordinate(point, "outX") : x;
            outY = point.has("outY") ? coordinate(point, "outY") : y;
        }
    }

    private final Point[] points;
    private final String mode;
    private final boolean orientToPath;
    private final double rotationOffset;
    private final String originalJson;

    private MotionPath2D(JSONObject path) {
        allowed(path, "version", "mode", "orientToPath", "rotationOffsetDeg", "points");
        if (!(path.opt("version") instanceof Number) || ((Number) path.opt("version")).doubleValue() != 1)
            throw new IllegalArgumentException("Motion path version must be 1");
        Object rawMode = path.opt("mode");
        if (!(rawMode instanceof String) || !("add".equals(rawMode) || "replace".equals(rawMode)))
            throw new IllegalArgumentException("Motion path mode must be add or replace");
        mode = (String) rawMode;
        if (!(path.opt("orientToPath") instanceof Boolean)) throw new IllegalArgumentException("orientToPath must be boolean");
        orientToPath = (Boolean) path.opt("orientToPath");
        rotationOffset = path.has("rotationOffsetDeg") ? number(path, "rotationOffsetDeg", -3600, 3600) : 0;
        JSONArray raw = path.optJSONArray("points");
        if (raw == null || raw.length() < 2 || raw.length() > MAX_POINTS)
            throw new IllegalArgumentException("Motion path requires 2 to 128 ordered points");
        points = new Point[raw.length()];
        Set<String> ids = new HashSet<>();
        double previous = -1;
        for (int index = 0; index < raw.length(); index++) {
            JSONObject point = raw.optJSONObject(index);
            if (point == null) throw new IllegalArgumentException("Motion path points must be objects");
            allowed(point, "id", "t", "x", "y", "inX", "inY", "outX", "outY");
            Object id = point.opt("id");
            if (!(id instanceof String) || !((String) id).matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}") || !ids.add((String) id))
                throw new IllegalArgumentException("Motion path point IDs must be unique stable IDs");
            Point compiled = new Point(point);
            if (compiled.t <= previous) throw new IllegalArgumentException("Motion path point times must increase without duplicates");
            points[index] = compiled; previous = compiled.t;
        }
        originalJson = path.toString();
        if (originalJson.getBytes(StandardCharsets.UTF_8).length > MAX_METADATA_BYTES)
            throw new IllegalArgumentException("Motion path exceeds the 64 KiB metadata budget");
    }

    public static MotionPath2D fromJson(JSONObject path) {
        if (path == null) throw new IllegalArgumentException("Motion path must be an object");
        return new MotionPath2D(path);
    }

    public String mode() { return mode; }
    public boolean orientToPath() { return orientToPath; }
    public JSONObject toJson() {
        try { return new JSONObject(originalJson); }
        catch (Exception failure) { throw new IllegalStateException("Compiled path metadata is unreadable", failure); }
    }

    public Sample sample(double progress) {
        if (Double.isNaN(progress) || Double.isInfinite(progress)) throw new IllegalArgumentException("Path progress must be finite");
        int segment = 0;
        if (progress >= points[points.length - 1].t) segment = points.length - 2;
        else while (segment < points.length - 2 && progress > points[segment + 1].t) segment++;
        Point left = points[segment], right = points[segment + 1];
        double u = Math.max(0, Math.min(1, (progress - left.t) / (right.t - left.t)));
        double x = cubic(left.x, left.outX, right.inX, right.x, u);
        double y = cubic(left.y, left.outY, right.inY, right.y, u);
        double dx = derivative(left.x, left.outX, right.inX, right.x, u);
        double dy = derivative(left.y, left.outY, right.inY, right.y, u);
        // Coincident/default endpoint handles can give a zero first derivative.
        // Recover the analytic one-sided direction without remembering playback state.
        if (Math.abs(dx) + Math.abs(dy) < 1e-12) {
            if (u <= .5) {
                dx = right.inX - left.x; dy = right.inY - left.y;
                if (Math.abs(dx) + Math.abs(dy) < 1e-12) { dx = right.x - left.x; dy = right.y - left.y; }
            } else {
                dx = right.x - left.outX; dy = right.y - left.outY;
                if (Math.abs(dx) + Math.abs(dy) < 1e-12) { dx = right.x - left.x; dy = right.y - left.y; }
            }
        }
        boolean direction = orientToPath && Math.abs(dx) + Math.abs(dy) >= 1e-12;
        double rotation = rotationOffset + (direction ? Math.toDegrees(Math.atan2(dy, dx)) : 0);
        return new Sample(x, y, rotation, orientToPath);
    }

    public static void validate(JSONObject path, List<String> errors) {
        try { fromJson(path); }
        catch (IllegalArgumentException failure) { errors.add(failure.getMessage()); }
    }

    private static double cubic(double a, double b, double c, double d, double u) {
        double v = 1 - u;
        return v * v * v * a + 3 * v * v * u * b + 3 * v * u * u * c + u * u * u * d;
    }
    private static double derivative(double a, double b, double c, double d, double u) {
        double v = 1 - u;
        return 3 * v * v * (b - a) + 6 * v * u * (c - b) + 3 * u * u * (d - c);
    }
    private static double coordinate(JSONObject point, String key) { return number(point, key, -10, 10); }
    private static double number(JSONObject object, String key, double min, double max) {
        Object value = object.opt(key);
        if (!(value instanceof Number)) throw new IllegalArgumentException(key + " must be a number");
        double number = ((Number) value).doubleValue();
        if (Double.isNaN(number) || Double.isInfinite(number) || number < min || number > max)
            throw new IllegalArgumentException(key + " is outside [" + min + "," + max + "]");
        return number;
    }
    private static void allowed(JSONObject object, String... keys) {
        Set<String> allowed = new HashSet<>(); java.util.Collections.addAll(allowed, keys);
        Iterator<String> iterator = object.keys();
        while (iterator.hasNext()) { String key = iterator.next(); if (!allowed.contains(key)) throw new IllegalArgumentException("Unsupported motion path field: " + key); }
    }
}
