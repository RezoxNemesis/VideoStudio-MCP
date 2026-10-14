package com.rezoxnemesis.videostudio;

import org.json.JSONArray;
import org.json.JSONObject;
import java.util.Iterator;

/** Exposure-sheet numbers use the artist's 1-based page labels at the UI boundary. */
final class SketchTimelineTools {
    private SketchTimelineTools() {}

    static int position(String text, int count) {
        final int value;
        try { value = Integer.parseInt(text.trim()); }
        catch (RuntimeException invalid) { throw new IllegalArgumentException("Enter a whole frame or page number"); }
        if (value < 1 || value > count) throw new IllegalArgumentException("Choose a frame or page from 1 to " + count);
        return value - 1;
    }

    static JSONArray exposureEdits(String first, String last, String hold, int count) throws Exception {
        int start = position(first, count), end = position(last, count), frames;
        try { frames = Integer.parseInt(hold.trim()); }
        catch (RuntimeException invalid) { throw new IllegalArgumentException("Exposure must be a whole number of frames"); }
        if (end < start) throw new IllegalArgumentException("Last page must follow the first page");
        if (end - start + 1 > 128) throw new IllegalArgumentException("Choose up to 128 pages per exposure edit");
        if (frames < 1 || frames > 600) throw new IllegalArgumentException("Exposure must be 1 to 600 frames");
        JSONArray edits = new JSONArray();
        for (int page = start; page <= end; page++) edits.put(new JSONObject().put("page", page).put("frames", frames));
        return edits;
    }

    static int exposure(JSONObject holds, int page) {
        return holds == null ? 1 : Math.max(1, Math.min(600, holds.optInt(String.valueOf(page), 1)));
    }

    static long totalFrames(JSONObject holds, int count) {
        long total = count;
        if (holds != null) for (Iterator<String> keys = holds.keys(); keys.hasNext();) {
            String key = keys.next();
            try {
                int page = Integer.parseInt(key);
                if (page >= 0 && page < count && String.valueOf(page).equals(key)) total += exposure(holds, page) - 1;
            } catch (NumberFormatException ignored) {}
        }
        return total;
    }
}
