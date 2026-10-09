package com.rezoxnemesis.videostudio;

import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=33, manifest=Config.NONE)
public class PlayableMediaProofTest {
    private static final String ORIGINAL = "a".repeat(64);
    private static final String REPLACEMENT = "b".repeat(64);

    @Test public void rootExportChecksumRejectsAnotherPlayableVideo() throws Exception {
        JSONObject actual = proof(REPLACEMENT);
        JSONObject saved = new JSONObject().put("sha256", ORIGINAL).put("playable", true);
        assertFalse(PlayableMediaVerifier.matchesSavedProof(saved, actual));
        assertTrue(PlayableMediaVerifier.matchesSavedProof(saved, proof(ORIGINAL)));
    }

    @Test public void nestedLegacyProofAndEveryRecordedChecksumMustMatch() throws Exception {
        JSONObject saved = new JSONObject().put("verification", proof(ORIGINAL));
        assertTrue(PlayableMediaVerifier.matchesSavedProof(saved, proof(ORIGINAL)));
        assertFalse(PlayableMediaVerifier.matchesSavedProof(saved, proof(REPLACEMENT)));
        saved.put("sha256", REPLACEMENT);
        assertFalse(PlayableMediaVerifier.matchesSavedProof(saved, proof(ORIGINAL)));
        assertFalse(PlayableMediaVerifier.matchesSavedProof(saved, proof(REPLACEMENT)));
        assertTrue(PlayableMediaVerifier.matchesSavedProof(null, proof(ORIGINAL)));
        assertFalse(PlayableMediaVerifier.matchesSavedProof(null, new JSONObject()));
    }

    @Test public void freshProofReplacesRootAndNestedFieldsWithoutLosingJobIdentity() throws Exception {
        JSONObject saved = new JSONObject().put("sha256", ORIGINAL).put("sizeBytes", 1)
                .put("verification", proof(ORIGINAL)).put("projectId", "project").put("assetId", "asset");
        JSONObject result = PlayableMediaVerifier.withFreshProof(saved, proof(REPLACEMENT));
        assertEquals(REPLACEMENT, result.getString("sha256"));
        assertEquals(1000, result.getLong("sizeBytes"));
        assertEquals(REPLACEMENT, result.getJSONObject("verification").getString("sha256"));
        assertEquals("project", result.getString("projectId"));
        assertEquals("asset", result.getString("assetId"));
        assertEquals("Refreshing must not mutate the saved journal", ORIGINAL, saved.getString("sha256"));
    }

    private static JSONObject proof(String hash) throws Exception {
        return new JSONObject().put("ok", true).put("playable", true).put("sha256", hash)
                .put("sizeBytes", 1000).put("durationMs", 2000).put("decodedFrameSamples", 3);
    }
}
