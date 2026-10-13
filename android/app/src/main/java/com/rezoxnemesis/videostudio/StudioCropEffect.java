package com.rezoxnemesis.videostudio;
import android.graphics.Matrix;
import androidx.media3.common.util.Size;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.effect.MatrixTransformation;
import org.json.JSONObject;
/** Source UV crop, positive x right/y down. Shared Media3 stage precedes output-aspect presentation. */
@UnstableApi
public final class StudioCropEffect implements MatrixTransformation {
 private final double x,y,width,height;
 public StudioCropEffect(JSONObject bounds){
  try{SketchDocument.validateBounds(bounds);x=bounds.getDouble("x");y=bounds.getDouble("y");width=bounds.getDouble("width");height=bounds.getDouble("height");if(width<.01||height<.01)throw SketchDocument.bad("Crop must cover at least 1% of each source axis");}catch(Exception error){throw new IllegalArgumentException("Invalid normalized source crop: "+error.getMessage(),error);}
 }
 @Override public Size configure(int inputWidth,int inputHeight){return new Size(Math.max(1,(int)Math.round(inputWidth*width)),Math.max(1,(int)Math.round(inputHeight*height)));}
 @Override public Matrix getMatrix(long presentationTimeUs){
  Matrix matrix=new Matrix();
  matrix.setValues(new float[]{(float)(1/width),0,(float)(-(2*x+width-1)/width),
   0,(float)(1/height),(float)(-(1-2*y-height)/height),0,0,1});
  return matrix;
 }
}
