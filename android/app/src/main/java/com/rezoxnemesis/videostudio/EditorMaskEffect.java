package com.rezoxnemesis.videostudio;
import android.content.Context;import android.opengl.GLES20;import androidx.media3.common.VideoFrameProcessingException;import androidx.media3.common.util.*;import androidx.media3.effect.*;

/** Feathered alpha masks and green chroma key used by both live preview and export. */
final class EditorMaskEffect implements GlEffect {
    private final int mode;private final float feather;
    EditorMaskEffect(String shape,float feather){mode=shape.equals("circle")?1:shape.equals("green_screen")?3:2;this.feather=Math.max(.001f,Math.min(.25f,feather));}
    @Override public GlShaderProgram toGlShaderProgram(Context context,boolean hdr)throws VideoFrameProcessingException{return new Shader(hdr);}
    private final class Shader extends BaseGlShaderProgram {
        private final GlProgram program;
        Shader(boolean hdr)throws VideoFrameProcessingException{super(hdr,1);try{program=new GlProgram("attribute vec4 aPosition; varying vec2 vUv; void main(){gl_Position=aPosition;vUv=aPosition.xy*.5+.5;}","precision mediump float; uniform sampler2D uTexture; uniform float uMode; uniform float uFeather; varying vec2 vUv; void main(){vec4 c=texture2D(uTexture,vUv);float a=1.;if(uMode<1.5){float d=length(vUv-vec2(.5));a=1.-smoothstep(.5-uFeather,.5,d);}else if(uMode<2.5){vec2 q=abs(vUv-vec2(.5))-vec2(.40);float d=length(max(q,vec2(0.)))+min(max(q.x,q.y),0.)-.08;a=1.-smoothstep(-uFeather,0.,d);}else{float g=c.g-max(c.r,c.b);a=1.-smoothstep(.10,.10+uFeather,g);}gl_FragColor=c*a;}");program.setBufferAttribute("aPosition",GlUtil.getNormalizedCoordinateBounds(),4);}catch(Exception e){throw new VideoFrameProcessingException(e);}}
        @Override public Size configure(int width,int height){return new Size(width,height);}
        @Override public void drawFrame(int texture,long time)throws VideoFrameProcessingException{try{program.use();program.setSamplerTexIdUniform("uTexture",texture,0);program.setFloatUniform("uMode",mode);program.setFloatUniform("uFeather",feather);program.bindAttributesAndUniforms();GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP,0,4);GlUtil.checkGlError();}catch(Exception e){throw new VideoFrameProcessingException(e);}}
        @Override public void release()throws VideoFrameProcessingException{try{super.release();program.delete();}catch(Exception e){throw new VideoFrameProcessingException(e);}}
    }
}
