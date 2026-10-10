package com.rezoxnemesis.videostudio;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Iterator;

/** Exact graph URI ownership, including private layers, proxies and generated dependencies. */
final class ProjectMediaReferences {
    private static final int MAX_DEPTH = 64;
    private static final int MAX_VALUES = 262144;
    private ProjectMediaReferences() { }

    interface UriCollector { void add(String uri) throws Exception; }

    static void collectGraphUris(JSONObject graph, UriCollector collector) throws Exception {
        if (graph == null || !(graph.get("assets") instanceof JSONArray)
                || !(graph.get("clips") instanceof JSONArray))
            throw new IllegalStateException("Retained graph has incomplete source ownership evidence");
        JSONArray assets = graph.getJSONArray("assets");
        if (assets.length() > MAX_VALUES) throw new IllegalStateException("Retained source catalog exceeds its bound");
        for (int index = 0; index < assets.length(); index++)
            if (!(assets.get(index) instanceof JSONObject))
                throw new IllegalStateException("Retained source catalog contains unreadable metadata");
        JSONArray clips = graph.getJSONArray("clips");
        if (clips.length() > MAX_VALUES) throw new IllegalStateException("Retained timeline exceeds its bound");
        for (int index = 0; index < clips.length(); index++)
            if (!(clips.get(index) instanceof JSONObject))
                throw new IllegalStateException("Retained timeline contains unreadable metadata");
        collectValueUris(graph, collector);
    }

    static void collectValueUris(Object evidence, UriCollector collector) throws Exception {
        if (evidence == null || collector == null)
            throw new IllegalStateException("Retained metadata has no ownership evidence");
        new Collection(collector).collect(evidence, 0);
    }

    /** URI syntax only; collection never opens or resolves a filesystem/provider path. */
    static boolean isUriString(String value) {
        if (value == null || value.length() < 2) return false;
        char first = value.charAt(0);
        if (!asciiLetter(first)) return false;
        for (int index = 1; index < value.length(); index++) {
            char character = value.charAt(index);
            if (character == ':') return true;
            if (!asciiLetter(character) && !(character >= '0' && character <= '9')
                    && character != '+' && character != '-' && character != '.') return false;
        }
        return false;
    }

    private static boolean asciiLetter(char value) {
        return (value >= 'a' && value <= 'z') || (value >= 'A' && value <= 'Z');
    }

    private static final class Collection {
        private final UriCollector collector;
        private int remaining = MAX_VALUES;
        Collection(UriCollector collector) { this.collector = collector; }
        void collect(Object value, int depth) throws Exception {
            if (--remaining < 0 || depth > MAX_DEPTH)
                throw new IllegalStateException("Retained ownership metadata exceeds its traversal bound");
            if (value == null || value == JSONObject.NULL) return;
            if (value instanceof String) {
                String string = (String) value;
                if (isUriString(string)) collector.add(string);
            } else if (value instanceof JSONArray) {
                JSONArray array = (JSONArray) value;
                if (array.length() > remaining)
                    throw new IllegalStateException("Retained ownership array exceeds its traversal bound");
                for (int index = 0; index < array.length(); index++) collect(array.get(index), depth + 1);
            } else if (value instanceof JSONObject) {
                JSONObject object = (JSONObject) value;
                if (object.length() > remaining)
                    throw new IllegalStateException("Retained ownership object exceeds its traversal bound");
                Iterator<String> keys = object.keys();
                while (keys.hasNext()) collect(object.get(keys.next()), depth + 1);
            } else if (!(value instanceof Number) && !(value instanceof Boolean)) {
                throw new IllegalStateException("Retained ownership metadata contains an unsupported value");
            }
        }
    }

    static boolean references(JSONObject graph, String uri) {
        if (uri == null || uri.isEmpty()) return false;
        try {
            // Older graphs may omit tracks, but every readable project graph
            // must carry its source catalog and timeline. Damaged evidence is
            // insufficient to authorize deletion of the sought file.
            if (graph == null || !(graph.get("assets") instanceof JSONArray)
                    || !(graph.get("clips") instanceof JSONArray)) return true;
            JSONArray assets = graph.getJSONArray("assets");
            if (assets.length() > MAX_VALUES) return true;
            for (int index = 0; index < assets.length(); index++)
                if (!(assets.get(index) instanceof JSONObject)) return true;
            return new Scan(uri).references(graph, 0);
        } catch (Exception damaged) { return true; }
    }

    static boolean referencesValue(Object evidence, String uri) {
        if (uri == null || uri.isEmpty()) return false;
        try { return evidence == null ? true : new Scan(uri).references(evidence, 0); }
        catch (Exception damaged) { return true; }
    }

    private static final class Scan {
        private final String uri;
        private int remaining = MAX_VALUES;
        Scan(String uri) { this.uri = uri; }

        private boolean references(Object value, int depth) throws Exception {
            if (--remaining < 0 || depth > MAX_DEPTH) return true;
            if (value == null || value == JSONObject.NULL) return false;
            if (value instanceof String) return uri.equals(value);
            if (value instanceof JSONArray) {
                JSONArray array = (JSONArray) value;
                if (array.length() > remaining) return true;
                for (int index = 0; index < array.length(); index++)
                    if (references(array.get(index), depth + 1)) return true;
            } else if (value instanceof JSONObject) {
                JSONObject object = (JSONObject) value;
                if (object.length() > remaining) return true;
                Iterator<String> keys = object.keys();
                while (keys.hasNext()) if (references(object.get(keys.next()), depth + 1)) return true;
            } else if (!(value instanceof Number) && !(value instanceof Boolean)) {
                return true;
            }
            return false;
        }
    }
}
