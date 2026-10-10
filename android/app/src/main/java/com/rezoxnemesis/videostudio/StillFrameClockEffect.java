package com.rezoxnemesis.videostudio;

import android.content.Context;
import android.opengl.GLES20;
import androidx.media3.common.GlObjectsProvider;
import androidx.media3.common.GlTextureInfo;
import androidx.media3.common.VideoFrameProcessingException;
import androidx.media3.common.util.GlProgram;
import androidx.media3.common.util.GlUtil;
import androidx.media3.common.util.Size;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.effect.BaseGlShaderProgram;
import androidx.media3.effect.GlEffect;
import androidx.media3.effect.GlShaderProgram;
import java.util.concurrent.atomic.AtomicLong;

/** Adapts a cached frame's fixed timestamp to the live clip clock and back.
 * Restoring timestamp0 is required by Media3's replay-cache bookkeeping. */
@UnstableApi
final class StillFrameClockEffect implements GlEffect {
    private final AtomicLong clockUs;
    private final boolean restore;
    StillFrameClockEffect(AtomicLong clockUs,boolean restore){this.clockUs=clockUs;this.restore=restore;}
    @Override public GlShaderProgram toGlShaderProgram(Context context,boolean useHdr)throws VideoFrameProcessingException {
        if(useHdr)throw new VideoFrameProcessingException("Still monitor requires SDR input/output");return new Program();
    }
    private final class Program extends BaseGlShaderProgram {
        private final GlProgram program;
        Program()throws VideoFrameProcessingException {
            super(false,1);
            try {
                program=new GlProgram("attribute vec4 aFramePosition; varying vec2 vUv; void main(){gl_Position=aFramePosition;vUv=(aFramePosition.xy+1.0)*0.5;}",
                        "precision highp float; uniform sampler2D uTexSampler; varying vec2 vUv; void main(){vec4 c=texture2D(uTexSampler,vUv);gl_FragColor="
                                +(restore?"vec4(c.rgb*c.a,c.a)":"c")+";}");
                program.setBufferAttribute("aFramePosition",GlUtil.getNormalizedCoordinateBounds(),GlUtil.HOMOGENEOUS_COORDINATE_VECTOR_SIZE);
            }catch(GlUtil.GlException error){throw new VideoFrameProcessingException(error);}
        }
        @Override public Size configure(int width,int height){return new Size(width,height);}
        @Override public void queueInputFrame(GlObjectsProvider provider,GlTextureInfo texture,long presentationTimeUs) {
            super.queueInputFrame(provider,texture,restore?0:Math.max(0,clockUs.get()));
        }
        @Override public void drawFrame(int textureId,long presentationTimeUs)throws VideoFrameProcessingException {
            try {
                program.use();program.setSamplerTexIdUniform("uTexSampler",textureId,0);program.bindAttributesAndUniforms();
                GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP,0,4);
            }catch(GlUtil.GlException error){throw new VideoFrameProcessingException(error,presentationTimeUs);}
        }
        @Override public void release()throws VideoFrameProcessingException {
            VideoFrameProcessingException failure=null;
            try{super.release();}catch(VideoFrameProcessingException error){failure=error;}
            try{program.delete();}catch(GlUtil.GlException error){if(failure==null)failure=new VideoFrameProcessingException(error);else failure.addSuppressed(error);}
            if(failure!=null)throw failure;
        }
    }
}
