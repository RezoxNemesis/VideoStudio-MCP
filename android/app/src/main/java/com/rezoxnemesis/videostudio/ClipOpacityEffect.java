package com.rezoxnemesis.videostudio;

import androidx.media3.common.util.UnstableApi;
import android.content.Context;
import android.opengl.GLES20;
import androidx.media3.common.VideoFrameProcessingException;
import androidx.media3.common.util.GlProgram;
import androidx.media3.common.util.GlUtil;
import androidx.media3.common.util.Size;
import androidx.media3.effect.GlEffect;
import androidx.media3.effect.GlShaderProgram;
import androidx.media3.effect.BaseGlShaderProgram;
import java.io.IOException;

/** Frame-evaluated alpha used by the program monitor and exported layers. */
@UnstableApi
public final class ClipOpacityEffect implements GlEffect {
    private final ProjectStore.Clip clip;
    private final long sequenceStartUs;
    public ClipOpacityEffect(ProjectStore.Clip clip){this(clip,Math.multiplyExact(Math.max(0,clip.startMs),1000L));}
    public ClipOpacityEffect(ProjectStore.Clip clip,long sequenceStartUs){this.clip=ProjectStore.Clip.fromJson(clip.toJson());this.sequenceStartUs=sequenceStartUs;}
    @Override public boolean isNoOp(int width,int height){
        if(clip.effects.optDouble("opacity",1)!=1||clip.effects.optBoolean("chromaKey")||!"none".equals(clip.effects.optString("mask","none")))return false;
        for(int i=0;i<clip.keyframes.length();i++){org.json.JSONObject frame=clip.keyframes.optJSONObject(i);if(frame!=null&&"opacity".equals(frame.optString("property")))return false;}return true;
    }
    @Override public GlShaderProgram toGlShaderProgram(Context context,boolean useHdr)throws VideoFrameProcessingException{
        return new AlphaProgram(context,useHdr,clip,sequenceStartUs);
    }
    private static final class AlphaProgram extends BaseGlShaderProgram {
        final GlProgram program;
        final KeyframeCurve opacity;
        final long sequenceStartUs;
        AlphaProgram(Context context,boolean useHdr,ProjectStore.Clip clip,long sequenceStartUs)throws VideoFrameProcessingException{
            super(useHdr,1);this.sequenceStartUs=sequenceStartUs;opacity=new KeyframeCurve(clip,"opacity",clip.effects.optDouble("opacity",1));
            try{
                program=new GlProgram(context,R.raw.vertex_shader_clip_effect,R.raw.fragment_shader_clip_alpha);
                program.setBufferAttribute("aFramePosition",GlUtil.getNormalizedCoordinateBounds(),GlUtil.HOMOGENEOUS_COORDINATE_VECTOR_SIZE);
                org.json.JSONObject fx=clip.effects;
                int color=android.graphics.Color.parseColor(fx.optString("chromaColor","#00FF00"));
                program.setFloatsUniform("uKeyColor",new float[]{android.graphics.Color.red(color)/255f,android.graphics.Color.green(color)/255f,android.graphics.Color.blue(color)/255f});
                program.setFloatUniform("uKeyEnabled",fx.optBoolean("chromaKey")?1:0);
                program.setFloatUniform("uKeyTolerance",(float)fx.optDouble("chromaTolerance",.18));
                program.setFloatUniform("uKeySoftness",(float)fx.optDouble("chromaSoftness",.08));
                program.setFloatUniform("uSpill",(float)fx.optDouble("spillSuppression",.35));
                String mask=fx.optString("mask","none");
                program.setFloatUniform("uMaskType","ellipse".equals(mask)||"circle".equals(mask)?2:"rectangle".equals(mask)||"rounded_rect".equals(mask)?1:0);
                program.setFloatsUniform("uMaskCenter",new float[]{(float)fx.optDouble("maskCenterX",.5),(float)fx.optDouble("maskCenterY",.5)});
                program.setFloatsUniform("uMaskSize",new float[]{(float)fx.optDouble("maskWidth",.9),(float)fx.optDouble("maskHeight",.9)});
                program.setFloatUniform("uMaskFeather",(float)fx.optDouble("maskFeather",.08));
                program.setFloatUniform("uMaskRadius","rectangle".equals(mask)?0:(float)fx.optDouble("maskCornerRadius",.08));
                program.setFloatUniform("uMaskInvert",fx.optBoolean("maskInvert")?1:0);
                program.setFloatUniform("uHdr",useHdr?1:0);
            }catch(IOException|GlUtil.GlException error){throw new VideoFrameProcessingException(error);}
        }
        @Override public Size configure(int width,int height){return new Size(width,height);}
        @Override public void drawFrame(int texture,long timeUs)throws VideoFrameProcessingException{
            try{
                program.use();program.setSamplerTexIdUniform("uTexSampler",texture,0);
                program.setFloatUniform("uAlphaScale",(float)Math.max(0,Math.min(1,opacity.valueAt(TimelineMath.effectLocalMs(timeUs,sequenceStartUs)))));
                program.bindAttributesAndUniforms();GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP,0,4);
            }catch(GlUtil.GlException error){throw new VideoFrameProcessingException(error,timeUs);}
        }
        @Override public void release()throws VideoFrameProcessingException{
            try{program.delete();}catch(GlUtil.GlException error){throw new VideoFrameProcessingException(error);}finally{super.release();}
        }
    }
}
