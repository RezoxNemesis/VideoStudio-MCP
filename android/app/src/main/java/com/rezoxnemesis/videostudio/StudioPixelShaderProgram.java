package com.rezoxnemesis.videostudio;

import android.opengl.GLES20;
import androidx.media3.common.VideoFrameProcessingException;
import androidx.media3.common.util.GlProgram;
import androidx.media3.common.util.GlUtil;
import androidx.media3.common.util.Size;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.effect.BaseGlShaderProgram;

@UnstableApi
public final class StudioPixelShaderProgram extends BaseGlShaderProgram {
    public static final String VERTEX_SHADER="""
            attribute vec4 aFramePosition;
            attribute vec4 aTexCoords;
            varying vec2 vTexCoords;
            void main() { gl_Position = aFramePosition; vTexCoords = aTexCoords.xy; }
            """;
    public static final String FRAGMENT_SHADER="""
            precision highp float;
            uniform sampler2D uTexSampler;
            uniform float uOpacity;
            uniform float uChromaEnabled;
            uniform vec3 uKeyColor;
            uniform float uTolerance;
            uniform float uChromaFeather;
            uniform float uMaskShape;
            uniform vec4 uMaskBounds;
            uniform float uMaskFeather;
            uniform float uGlowStrength;
            uniform float uGlowThreshold;
            uniform vec2 uGlowStep;
            varying vec2 vTexCoords;
            float keyAlpha(vec4 pixel) {
              vec3 rgb = pixel.a > 0.00001 ? pixel.rgb / pixel.a : vec3(0.0);
              float d = distance(rgb, uKeyColor) / 1.7320508;
              float keep = uChromaFeather > 0.00001
                ? smoothstep(uTolerance, uTolerance + uChromaFeather, d)
                : step(uTolerance + 0.00001, d);
              return mix(1.0, keep, uChromaEnabled);
            }
            float maskAlpha() {
              if (uMaskShape < 0.5) return 1.0;
              vec2 uv = vec2(vTexCoords.x, 1.0 - vTexCoords.y);
              vec2 center = (uMaskBounds.xy + uMaskBounds.zw) * 0.5;
              vec2 radius = (uMaskBounds.zw - uMaskBounds.xy) * 0.5;
              float inner;
              if (uMaskShape < 1.5) {
                inner = min(min(uv.x - uMaskBounds.x, uMaskBounds.z - uv.x),
                            min(uv.y - uMaskBounds.y, uMaskBounds.w - uv.y));
              } else {
                inner = (1.0 - length((uv - center) / radius)) * min(radius.x, radius.y);
              }
              return uMaskFeather > 0.00001 ? smoothstep(0.0, uMaskFeather, inner) : step(0.0, inner);
            }
            vec3 bright(vec2 uv) {
              vec4 pixel = texture2D(uTexSampler, clamp(uv, vec2(0.0), vec2(1.0)));
              vec3 rgb = pixel.a > 0.00001 ? pixel.rgb / pixel.a : vec3(0.0);
              float luminance = dot(rgb, vec3(0.2126, 0.7152, 0.0722));
              return pixel.rgb * keyAlpha(pixel) * smoothstep(uGlowThreshold, uGlowThreshold + 0.1, luminance);
            }
            void main() {
              vec4 original = texture2D(uTexSampler, vTexCoords);
              float keep = keyAlpha(original);
              vec3 bloom = vec3(0.0);
              if (uGlowStrength > 0.0) {
                vec2 s = uGlowStep;
                bloom = bright(vTexCoords) * 0.2;
                bloom += (bright(vTexCoords + vec2(s.x,0.0)) + bright(vTexCoords - vec2(s.x,0.0))
                       + bright(vTexCoords + vec2(0.0,s.y)) + bright(vTexCoords - vec2(0.0,s.y))) * 0.12;
                bloom += (bright(vTexCoords + s) + bright(vTexCoords - s)
                       + bright(vTexCoords + vec2(s.x,-s.y)) + bright(vTexCoords + vec2(-s.x,s.y))) * 0.08;
                bloom *= uGlowStrength;
              }
              float bloomAlpha = clamp(max(max(bloom.r,bloom.g),bloom.b),0.0,1.0);
              float alpha = max(original.a * keep,bloomAlpha);
              float multiplier = maskAlpha() * uOpacity;
              gl_FragColor = vec4((original.rgb * keep + bloom) * multiplier,alpha * multiplier);
            }
            """;
    private final StudioPixelEffect effect;
    private final GlProgram program;
    public StudioPixelShaderProgram(StudioPixelEffect effect,boolean useHdr)throws VideoFrameProcessingException{
        super(useHdr,1);this.effect=effect;
        try{
            program=new GlProgram(VERTEX_SHADER,FRAGMENT_SHADER);
            program.setBufferAttribute("aFramePosition",GlUtil.getNormalizedCoordinateBounds(),4);
            program.setBufferAttribute("aTexCoords",GlUtil.getTextureCoordinateBounds(),4);
            program.setFloatUniform("uChromaEnabled",effect.chromaKey?1:0);
            program.setFloatsUniform("uKeyColor",effect.keyColor);
            program.setFloatUniform("uTolerance",effect.tolerance);program.setFloatUniform("uChromaFeather",effect.feather);
            program.setFloatUniform("uMaskShape",effect.maskShape);
            program.setFloatsUniform("uMaskBounds",new float[]{effect.left,effect.top,effect.right,effect.bottom});
            program.setFloatUniform("uMaskFeather",effect.maskFeather);program.setFloatUniform("uGlowStrength",effect.glowStrength);
            program.setFloatUniform("uGlowThreshold",effect.glowThreshold);
        }catch(GlUtil.GlException error){throw new VideoFrameProcessingException("Could not create native pixel shader",error);}
    }
    @Override public Size configure(int width,int height){
        program.setFloatsUniform("uGlowStep",new float[]{effect.glowRadiusPx/Math.max(1,width),effect.glowRadiusPx/Math.max(1,height)});
        return new Size(width,height);
    }
    @Override public void drawFrame(int textureId,long presentationTimeUs)throws VideoFrameProcessingException{
        try{
            program.use();program.setSamplerTexIdUniform("uTexSampler",textureId,0);
            program.setFloatUniform("uOpacity",effect.opacityAtUs(presentationTimeUs));
            program.bindAttributesAndUniforms();GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP,0,4);GlUtil.checkGlError();
        }catch(GlUtil.GlException error){throw new VideoFrameProcessingException("Native pixel shader failed",error,presentationTimeUs);}
    }
    @Override public void release()throws VideoFrameProcessingException{
        try{super.release();}finally{try{program.delete();}catch(GlUtil.GlException error){throw VideoFrameProcessingException.from(error);}}
    }
}
