package com.rezoxnemesis.videostudio;

import org.json.JSONArray;
import org.json.JSONObject;
import java.util.UUID;

/**
 * Validates a requested source-textured 2D rig BEFORE any project-private
 * filesystem mutation. Remote calls must provide only data, never file paths:
 * the caller resolves a known VideoStudio image asset in an owner-authenticated
 * project, and this class binds the authored points to that trusted identity.
 */
public final class NativeRigAuthoring {
    private NativeRigAuthoring() {}

    public static Rig2DProfile parse(String projectId, String sourceAssetId,
                                    JSONObject request) throws Exception {
        if (request == null || !request.has("black")
                || !(request.opt("black") instanceof Boolean)) {
            throw new IllegalArgumentException("Specify whether this is the black or white actor");
        }
        JSONArray joints = request.optJSONArray("joints");
        if (joints == null || joints.length() != Rig2DProfile.JOINTS.length) {
            throw new IllegalArgumentException("Exactly sixteen labeled source joints are required");
        }
        JSONArray outline = request.optJSONArray("outline");
        if(request.has("outline")&&outline==null)throw new IllegalArgumentException("outline must be an array");
        if (outline == null) outline = new JSONArray();
        if (outline.length() > 64) {
            throw new IllegalArgumentException("Character outline exceeds 64 points");
        }

        if(request.has("rigId")&&!(request.opt("rigId") instanceof String))throw new IllegalArgumentException("rigId must be a string");
        String explicitId = request.optString("rigId", "");
        String profileId = explicitId.isEmpty() ? UUID.randomUUID().toString() : explicitId;
        JSONObject record = new JSONObject()
                .put("version", 1)
                .put("id", profileId)
                .put("projectId", projectId)
                .put("sourceAssetId", sourceAssetId)
                .put("black", request.getBoolean("black"))
                .put("transparentSource", request.optBoolean("transparentSource", false))
                .put("joints", joints)
                .put("outline", outline);
        if(request.has("transparentSource")&&!(request.opt("transparentSource") instanceof Boolean))
            throw new IllegalArgumentException("transparentSource must be boolean");
        for(String field:new String[]{"swordTip","swordHilt","swordOutline","headOutline","sourceSha256"})
            if(request.has(field))record.put(field,request.get(field));
        // Existing profile constructor checks IDs, coordinate ranges,
        // nonzero bones and nondegenerate polygons, including traversal IDs.
        return Rig2DProfile.fromJson(record);
    }
}
