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

/** BitmapFactory/GLUtils bitmap upload and the rig mesh FBO use premultiplied
 * alpha. Native effect math and Media3 overlays operate on straight RGB;
 * normalize at each boundary that supplies a premultiplied texture. */
@UnstableApi
final class BitmapInputAlphaEffect implements GlEffect {
    @Override public GlShaderProgram toGlShaderProgram(Context context,boolean useHdr)throws VideoFrameProcessingException {
        if(useHdr)throw new VideoFrameProcessingException("Bitmap input requires SDR");return new Program();
    }
    private static final class Program extends BaseGlShaderProgram {
        private final GlProgram program;
        Program()throws VideoFrameProcessingException {
            super(false,1);
            try {
                program=new GlProgram("attribute vec4 aFramePosition; varying vec2 vUv; void main(){gl_Position=aFramePosition;vUv=(aFramePosition.xy+1.0)*0.5;}",
                        "precision highp float; uniform sampler2D uTexSampler; varying vec2 vUv; void main(){vec4 c=texture2D(uTexSampler,vUv);gl_FragColor=vec4(c.a>0.00001?c.rgb/c.a:vec3(0.0),c.a);}");
                program.setBufferAttribute("aFramePosition",GlUtil.getNormalizedCoordinateBounds(),GlUtil.HOMOGENEOUS_COORDINATE_VECTOR_SIZE);
            }catch(GlUtil.GlException error){throw new VideoFrameProcessingException(error);}
        }
        @Override public Size configure(int width,int height){return new Size(width,height);}
        @Override public void drawFrame(int textureId,long presentationTimeUs)throws VideoFrameProcessingException {
            try{program.use();program.setSamplerTexIdUniform("uTexSampler",textureId,0);program.bindAttributesAndUniforms();GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP,0,4);}
            catch(GlUtil.GlException error){throw new VideoFrameProcessingException(error,presentationTimeUs);}
        }
        @Override public void release()throws VideoFrameProcessingException {
            VideoFrameProcessingException failure=null;
            try{super.release();}catch(VideoFrameProcessingException error){failure=error;}
            try{program.delete();}catch(GlUtil.GlException error){if(failure==null)failure=new VideoFrameProcessingException(error);else failure.addSuppressed(error);}
            if(failure!=null)throw failure;
        }
    }
}
