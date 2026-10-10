package com.rezoxnemesis.videostudio;

import android.content.Context;
import android.opengl.GLES20;

import androidx.media3.common.VideoFrameProcessingException;
import androidx.media3.common.util.GlProgram;
import androidx.media3.common.util.GlUtil;
import androidx.media3.common.util.Size;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.effect.BaseGlShaderProgram;
import androidx.media3.effect.GlEffect;
import androidx.media3.effect.GlShaderProgram;

import org.json.JSONObject;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.ShortBuffer;
import java.util.ArrayList;
import java.util.List;

/** Real source-texture mesh deformation from the shared bounded 2D rig compiler.
 * UV/posed coordinates use top-left origin, +X right and +Y down. Rendering maps
 * positions to clip space and flips source V into Media3's canonical GL texture
 * coordinates. This pass precedes crop/layout, accepts straight SDR RGBA and
 * produces premultiplied RGBA so folded triangles compose in mesh order using
 * source-over. The next pass restores straight RGB for grading. Only the
 * supplied mesh is drawn; uncovered output stays transparent. */
@UnstableApi
public final class NativeRig2DEffect implements GlEffect {
    private static final int MAX_EDGE = 4096;
    private static final long MAX_PIXELS = 8_388_608L;
    private final ProjectStore.Clip clip;
    private final long timestampOffsetUs;
    private final float clockSpeed;

    public NativeRig2DEffect(ProjectStore.Clip clip, long timestampOffsetUs, float clockSpeed) {
        if (!Float.isFinite(clockSpeed) || clockSpeed <= 0f) throw new IllegalArgumentException("Rig clock speed must be finite and positive");
        List<String> errors = validate(clip);
        if (!errors.isEmpty()) throw new IllegalArgumentException(String.join(", ", errors));
        try { this.clip = ProjectStore.Clip.fromJson(new JSONObject(clip.toJson().toString())); }
        catch (Exception error) { throw new IllegalArgumentException("Could not capture the source rig", error); }
        this.timestampOffsetUs = timestampOffsetUs;
        this.clockSpeed = clockSpeed;
    }

    public static List<String> validate(ProjectStore.Clip clip) {
        ArrayList<String> errors = new ArrayList<>();
        JSONObject fx = clip == null || clip.effects == null ? new JSONObject() : clip.effects;
        if (!fx.has("rig2d")) return errors;
        JSONObject rig = fx.optJSONObject("rig2d");
        if (rig == null) { errors.add("rig2d must be a rig settings object"); return errors; }
        try { AnimationRig2D.compile(rig); }
        catch (IllegalArgumentException error) { errors.add("rig2d: " + error.getMessage()); return errors; }
        if (rig.optBoolean("enabled", true)) {
            if (fx.optBoolean("titleOnly", false)) errors.add("rig2d requires a textured image/video source; native title glyphs are drawn after the source mesh");
            if (fx.optJSONObject("proceduralScene") != null) errors.add("rig2d cannot precede a procedural overlay that redraws its source; use a generated image source without proceduralScene");
        }
        return errors;
    }

    @Override public GlShaderProgram toGlShaderProgram(Context context, boolean useHdr) throws VideoFrameProcessingException {
        if (useHdr) throw new VideoFrameProcessingException("2D rig deformation requires SDR input; tone-map HDR before this effect");
        return new Program();
    }

    private final class Program extends BaseGlShaderProgram {
        private final GlProgram program;
        private final int positionAttribute, uvAttribute, samplerUniform;
        private AnimationRig2D compiled;
        private FloatBuffer positions, uvs;
        private ShortBuffer indices;
        private int vertexCount, indexCount;
        private final int[] blendState = new int[6];

        Program() throws VideoFrameProcessingException {
            super(false, 1);
            GlProgram created = null;
            try {
                int[] range = new int[2], precision = new int[1];
                GLES20.glGetShaderPrecisionFormat(GLES20.GL_FRAGMENT_SHADER, GLES20.GL_HIGH_FLOAT, range, 0, precision, 0);
                GlUtil.checkGlError();
                if (precision[0] <= 0) throw new VideoFrameProcessingException("2D rig textures require GLES2 high precision fragment sampling");
                created = new GlProgram(
                        "attribute vec2 aPosition; attribute vec2 aSourceUv; varying vec2 vUv; void main(){gl_Position=vec4(aPosition,0.0,1.0);vUv=aSourceUv;}",
                        "precision highp float; uniform sampler2D uTexSampler; varying vec2 vUv; void main(){vec4 c=texture2D(uTexSampler,vUv);gl_FragColor=vec4(c.rgb*c.a,c.a);}");
                positionAttribute = created.getAttributeArrayLocationAndEnable("aPosition");
                uvAttribute = created.getAttributeArrayLocationAndEnable("aSourceUv");
                samplerUniform = created.getUniformLocation("uTexSampler");
                program = created;
            } catch (GlUtil.GlException | RuntimeException error) {
                if (created != null) try { created.delete(); } catch (GlUtil.GlException cleanup) { error.addSuppressed(cleanup); }
                throw new VideoFrameProcessingException(error);
            }
        }

        @Override public boolean shouldClearTextureBuffer() { return false; }

        @Override public Size configure(int width, int height) throws VideoFrameProcessingException {
            try {
                int[] maximum = new int[1];
                GLES20.glGetIntegerv(GLES20.GL_MAX_TEXTURE_SIZE, maximum, 0);
                GlUtil.checkGlError();
                if (width <= 0 || height <= 0 || width > MAX_EDGE || height > MAX_EDGE
                        || width > maximum[0] || height > maximum[0] || (long) width * height > MAX_PIXELS)
                    throw new VideoFrameProcessingException("2D rig input exceeds the device texture limit or bounded 4096-edge/8-megapixel SDR pass budget");
                compiled = AnimationRig2D.compileForClip(clip, width / (float) height);
                AnimationRig2D.Frame rest = compiled.sampleClip(0L);
                vertexCount = rest.positions.length / 2;
                indexCount = rest.triangles.length;
                if (rest.positions.length != vertexCount * 2 || vertexCount < 3 || vertexCount > AnimationRig2D.MAX_VERTICES
                        || rest.textureUvs.length != vertexCount * 2 || indexCount < 3 || indexCount % 3 != 0
                        || indexCount / 3 > AnimationRig2D.MAX_TRIANGLES)
                    throw new VideoFrameProcessingException("2D rig compiler returned invalid bounded mesh topology");
                positions = floats(vertexCount * 2);
                uvs = floats(vertexCount * 2);
                indices = ByteBuffer.allocateDirect(indexCount * 2).order(ByteOrder.nativeOrder()).asShortBuffer();
                for (int i = 0; i < vertexCount; i++) uvs.put(rest.textureUvs[i * 2]).put(1f - rest.textureUvs[i * 2 + 1]);
                for (int index : rest.triangles) {
                    if (index < 0 || index >= vertexCount) throw new VideoFrameProcessingException("2D rig triangle references an unavailable vertex");
                    indices.put((short) index);
                }
                uvs.flip(); indices.flip();
                return new Size(width, height);
            } catch (GlUtil.GlException | RuntimeException error) { throw new VideoFrameProcessingException(error); }
        }

        @Override public void drawFrame(int textureId, long presentationTimeUs) throws VideoFrameProcessingException {
            if (compiled == null) throw new VideoFrameProcessingException("2D rig was not configured", presentationTimeUs);
            boolean blend = GLES20.glIsEnabled(GLES20.GL_BLEND), cull = GLES20.glIsEnabled(GLES20.GL_CULL_FACE), depth = GLES20.glIsEnabled(GLES20.GL_DEPTH_TEST), scissor = GLES20.glIsEnabled(GLES20.GL_SCISSOR_TEST);
            boolean captured = false;
            try {
                GLES20.glGetIntegerv(GLES20.GL_BLEND_SRC_RGB, blendState, 0);
                GLES20.glGetIntegerv(GLES20.GL_BLEND_DST_RGB, blendState, 1);
                GLES20.glGetIntegerv(GLES20.GL_BLEND_SRC_ALPHA, blendState, 2);
                GLES20.glGetIntegerv(GLES20.GL_BLEND_DST_ALPHA, blendState, 3);
                GLES20.glGetIntegerv(GLES20.GL_BLEND_EQUATION_RGB, blendState, 4);
                GLES20.glGetIntegerv(GLES20.GL_BLEND_EQUATION_ALPHA, blendState, 5);
                GlUtil.checkGlError();
                captured = true;
                // Subtract in double to avoid a signed-long timestamp overflow.
                long localUs = Math.max(0L, (long) (((double) presentationTimeUs - timestampOffsetUs) / clockSpeed));
                AnimationRig2D.Frame frame = compiled.sampleClip(localUs / 1000L);
                if (frame.positions.length != vertexCount * 2) throw new IllegalArgumentException("2D rig changed mesh topology during playback");
                positions.clear();
                for (int i = 0; i < vertexCount; i++) {
                    float x = frame.positions[i * 2], y = frame.positions[i * 2 + 1];
                    if (!Float.isFinite(x) || !Float.isFinite(y)) throw new IllegalArgumentException("2D rig pose produced a nonfinite vertex");
                    float glX = x * 2f - 1f, glY = 1f - y * 2f;
                    if (!Float.isFinite(glX) || !Float.isFinite(glY)) throw new IllegalArgumentException("2D rig pose exceeds the GPU coordinate range");
                    positions.put(glX).put(glY);
                }
                positions.flip(); uvs.position(0); indices.position(0);
                program.use();
                GLES20.glDisable(GLES20.GL_CULL_FACE); GLES20.glDisable(GLES20.GL_DEPTH_TEST); GLES20.glDisable(GLES20.GL_SCISSOR_TEST);
                GlUtil.clearFocusedBuffers();
                GLES20.glEnable(GLES20.GL_BLEND);
                GLES20.glBlendEquationSeparate(GLES20.GL_FUNC_ADD, GLES20.GL_FUNC_ADD);
                GLES20.glBlendFuncSeparate(GLES20.GL_ONE, GLES20.GL_ONE_MINUS_SRC_ALPHA,
                        GLES20.GL_ONE, GLES20.GL_ONE_MINUS_SRC_ALPHA);
                GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0); GLES20.glBindBuffer(GLES20.GL_ELEMENT_ARRAY_BUFFER, 0);
                GLES20.glEnableVertexAttribArray(positionAttribute); GLES20.glEnableVertexAttribArray(uvAttribute);
                GLES20.glVertexAttribPointer(positionAttribute, 2, GLES20.GL_FLOAT, false, 0, positions);
                GLES20.glVertexAttribPointer(uvAttribute, 2, GLES20.GL_FLOAT, false, 0, uvs);
                GLES20.glActiveTexture(GLES20.GL_TEXTURE0); GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId);
                GLES20.glUniform1i(samplerUniform, 0);
                GLES20.glDrawElements(GLES20.GL_TRIANGLES, indexCount, GLES20.GL_UNSIGNED_SHORT, indices);
                GlUtil.checkGlError();
            } catch (GlUtil.GlException | RuntimeException error) { throw new VideoFrameProcessingException(error, presentationTimeUs); }
            finally {
                if (captured) {
                    GLES20.glBlendFuncSeparate(blendState[0], blendState[1], blendState[2], blendState[3]);
                    GLES20.glBlendEquationSeparate(blendState[4], blendState[5]);
                }
                if (blend) GLES20.glEnable(GLES20.GL_BLEND); else GLES20.glDisable(GLES20.GL_BLEND);
                if (cull) GLES20.glEnable(GLES20.GL_CULL_FACE); else GLES20.glDisable(GLES20.GL_CULL_FACE);
                if (depth) GLES20.glEnable(GLES20.GL_DEPTH_TEST); else GLES20.glDisable(GLES20.GL_DEPTH_TEST);
                if (scissor) GLES20.glEnable(GLES20.GL_SCISSOR_TEST); else GLES20.glDisable(GLES20.GL_SCISSOR_TEST);
            }
        }

        @Override public void release() throws VideoFrameProcessingException {
            positions = null; uvs = null; indices = null; compiled = null;
            VideoFrameProcessingException failure = null;
            try { super.release(); } catch (VideoFrameProcessingException error) { failure = error; }
            try { program.delete(); }
            catch (GlUtil.GlException error) { if (failure == null) failure = new VideoFrameProcessingException(error); else failure.addSuppressed(error); }
            if (failure != null) throw failure;
        }
    }

    private static FloatBuffer floats(int count) { return ByteBuffer.allocateDirect(count * 4).order(ByteOrder.nativeOrder()).asFloatBuffer(); }
}
