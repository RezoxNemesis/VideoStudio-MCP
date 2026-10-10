package com.rezoxnemesis.videostudio;

import android.content.Context;
import android.graphics.Color;
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

/** Real single-pass pixel operations and time-varying alpha. Inputs/outputs stay in
 * Media3's default SDR encoded RGB pipeline; chroma comparison uses normalized chromaticity.
 * This is procedural compositing, not segmentation, optical-flow or frame synthesis.
 */
@UnstableApi
public final class NativePixelEffect implements GlEffect {
    private final MotionTimeline timeline;
    private final long offsetUs;
    private final long animationOffsetUs;
    private final float clockSpeed, vignette, grain, scanlines, posterize, pixelSize, feather, rgbSplit, sharpen, glow;
    private final int mask;
    private final float[] chroma;
    private final float tolerance, spill;

    public NativePixelEffect(JSONObject fx, MotionTimeline timeline, long offsetUs, float clockSpeed) {
        this.timeline = timeline; this.offsetUs = offsetUs; this.clockSpeed = Math.max(.0001f, clockSpeed);
        this.animationOffsetUs = AnimationClock.microseconds(fx.optLong("animationOffsetMs", 0L));
        vignette = finite(fx, "vignette", 0, 0, 1);
        grain = finite(fx, "grain", 0, 0, 1);
        scanlines = finite(fx, "scanlines", 0, 0, 1);
        posterize = finite(fx, "posterize", 0, 0, 64);
        pixelSize = finite(fx, "pixelSize", 0, 0, 128);
        rgbSplit = finite(fx, "rgbSplit", 0, 0, .05f);
        sharpen = finite(fx, "sharpen", 0, 0, 2);
        glow = finite(fx, "glow", 0, 0, 2);
        feather = finite(fx, "maskFeather", .02f, .001f, .5f);
        String shape = fx.optString("mask", "none");
        mask = "circle".equals(shape) || "ellipse".equals(shape) ? 1 : "rounded_rect".equals(shape) ? 2 : 0;
        if (fx.optBoolean("chromaKey", false)) {
            int color;
            try { color = Color.parseColor(fx.optString("chromaColor", "#00FF00")); }
            catch (IllegalArgumentException error) { throw new IllegalArgumentException("Invalid chroma key color", error); }
            chroma = new float[]{Color.red(color) / 255f, Color.green(color) / 255f, Color.blue(color) / 255f};
        } else chroma = new float[]{-1, -1, -1};
        tolerance = finite(fx, "chromaTolerance", .18f, .001f, 1);
        spill = finite(fx, "spillSuppression", .35f, 0, 1);
    }

    @Override public GlShaderProgram toGlShaderProgram(Context context, boolean useHdr)
            throws VideoFrameProcessingException {
        if (useHdr) throw new VideoFrameProcessingException("Pixel effects require the SDR export color pipeline");
        return new Program();
    }

    private final class Program extends BaseGlShaderProgram {
        private final GlProgram program;
        private int width, height;

        Program() throws VideoFrameProcessingException {
            super(false, 1);
            try {
                program = new GlProgram(
                        "attribute vec4 aFramePosition; varying vec2 vUv; void main(){ gl_Position=aFramePosition; vUv=(aFramePosition.xy+1.0)*0.5; }",
                        "precision highp float; uniform sampler2D uTexSampler; varying vec2 vUv;"
                        + "uniform vec2 uSize; uniform float uOpacity,uMix,uWhite,uTime,uVignette,uGrain,uScanlines,uPosterize,uPixel,uMask,uFeather,uTolerance,uSpill,uSplit,uSharpen,uGlow; uniform vec3 uKey;"
                        + "float noise(vec2 p){return fract(sin(dot(p,vec2(12.9898,78.233)))*43758.5453);}"
                        + "void main(){vec2 uv=vUv; if(uPixel>0.0){vec2 cells=max(vec2(1.0),uSize/uPixel);uv=(floor(uv*cells)+0.5)/cells;}"
                        + "vec4 c=texture2D(uTexSampler,uv); float a=c.a;"
                        + "if(uSplit>0.0){c.r=texture2D(uTexSampler,uv+vec2(uSplit,0.0)).r;c.b=texture2D(uTexSampler,uv-vec2(uSplit,0.0)).b;}"
                        + "if(uSharpen>0.0){vec2 px=1.0/uSize;vec3 avg=(texture2D(uTexSampler,uv+vec2(px.x,0.0)).rgb+texture2D(uTexSampler,uv-vec2(px.x,0.0)).rgb+texture2D(uTexSampler,uv+vec2(0.0,px.y)).rgb+texture2D(uTexSampler,uv-vec2(0.0,px.y)).rgb)*0.25;c.rgb+=(c.rgb-avg)*uSharpen;}"
                        + "if(uGlow>0.0){vec3 light=max(c.rgb-vec3(0.6),0.0)*0.2;for(int i=0;i<8;i++){float angle=float(i)*0.78539816;vec2 direction=vec2(cos(angle),sin(angle))/uSize;light+=max(texture2D(uTexSampler,uv+direction*4.0).rgb-vec3(0.6),0.0)*0.065;light+=max(texture2D(uTexSampler,uv+direction*9.0).rgb-vec3(0.6),0.0)*0.035;}c.rgb+=light*uGlow;}"
                        + "if(uKey.r>=0.0){vec3 cc=c.rgb/max(dot(c.rgb,vec3(1.0)),0.001);vec3 kk=uKey/max(dot(uKey,vec3(1.0)),0.001);"
                        + "float d=distance(cc,kk);float keep=smoothstep(uTolerance,uTolerance+0.08,d);a*=keep;"
                        + "if(uKey.g>uKey.r && uKey.g>uKey.b)c.g=mix(c.g,min(c.g,max(c.r,c.b)),uSpill*(1.0-keep));"
                        + "if(uKey.b>uKey.r && uKey.b>uKey.g)c.b=mix(c.b,min(c.b,max(c.r,c.g)),uSpill*(1.0-keep));"
                        + "if(uKey.r>uKey.g && uKey.r>uKey.b)c.r=mix(c.r,min(c.r,max(c.g,c.b)),uSpill*(1.0-keep));}"
                        + "vec2 p=(vUv-0.5)*2.0;if(uMask==1.0)a*=1.0-smoothstep(1.0-uFeather,1.0,length(p));"
                        + "if(uMask==2.0){vec2 q=abs(p)-vec2(0.84);float d=length(max(q,0.0))+min(max(q.x,q.y),0.0)-0.16;a*=1.0-smoothstep(-uFeather,0.0,d);}"
                        + "if(uPosterize>1.0)c.rgb=floor(c.rgb*(uPosterize-1.0)+0.5)/(uPosterize-1.0);"
                        + "c.rgb*=1.0-uVignette*smoothstep(0.20,1.35,dot(p,p));"
                        + "c.rgb*=1.0-uScanlines*(0.5+0.5*sin(vUv.y*uSize.y*3.14159265));"
                        + "c.rgb+=vec3((noise(floor(vUv*uSize)+floor(uTime*24.0))-0.5)*uGrain*0.12);"
                        + "c.rgb=mix(clamp(c.rgb,0.0,1.0),vec3(uWhite),uMix);gl_FragColor=vec4(c.rgb,a*uOpacity);}");
                program.setBufferAttribute("aFramePosition", GlUtil.getNormalizedCoordinateBounds(), GlUtil.HOMOGENEOUS_COORDINATE_VECTOR_SIZE);
                program.setFloatsUniform("uKey", chroma);
                program.setFloatUniform("uVignette", vignette);
                program.setFloatUniform("uGrain", grain);
                program.setFloatUniform("uScanlines", scanlines);
                program.setFloatUniform("uPosterize", posterize);
                program.setFloatUniform("uPixel", pixelSize);
                program.setFloatUniform("uSplit", rgbSplit);
                program.setFloatUniform("uSharpen", sharpen);
                program.setFloatUniform("uGlow", glow);
                program.setFloatUniform("uMask", mask);
                program.setFloatUniform("uFeather", feather);
                program.setFloatUniform("uTolerance", tolerance);
                program.setFloatUniform("uSpill", spill);
            } catch (GlUtil.GlException error) { throw new VideoFrameProcessingException(error); }
        }

        @Override public Size configure(int inputWidth, int inputHeight) {
            width=inputWidth; height=inputHeight;
            program.setFloatsUniform("uSize", new float[]{width,height});
            return new Size(width,height);
        }

        @Override public void drawFrame(int inputTexId, long presentationTimeUs) throws VideoFrameProcessingException {
            long timeUs=Math.max(0,(long)((presentationTimeUs-offsetUs)/(double)clockSpeed));
            MotionTimeline.Sample sample=timeline.sample(timeUs);
            try {
                program.use();
                program.setSamplerTexIdUniform("uTexSampler", inputTexId, 0);
                program.setFloatUniform("uOpacity", sample.opacity);
                program.setFloatUniform("uMix", sample.colorMix);
                program.setFloatUniform("uWhite", sample.dipWhite ? 1 : 0);
                program.setFloatUniform("uTime", AnimationClock.offsetTimeUs(timeUs,animationOffsetUs)/1000000f);
                program.bindAttributesAndUniforms();
                GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP,0,4);
            } catch (GlUtil.GlException error) { throw new VideoFrameProcessingException(error,presentationTimeUs); }
        }

        @Override public void release() throws VideoFrameProcessingException {
            VideoFrameProcessingException failure=null;
            try { super.release(); } catch(VideoFrameProcessingException error) { failure=error; }
            try { program.delete(); }
            catch (GlUtil.GlException error) {
                if(failure==null)failure=new VideoFrameProcessingException(error);else failure.addSuppressed(error);
            }
            if(failure!=null)throw failure;
        }
    }

    private static float finite(JSONObject fx,String key,float fallback,float min,float max) {
        double value=fx.optDouble(key,fallback);
        return Double.isFinite(value) ? (float)Math.max(min,Math.min(max,value)) : fallback;
    }
}
