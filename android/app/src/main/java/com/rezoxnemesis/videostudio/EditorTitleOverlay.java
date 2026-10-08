package com.rezoxnemesis.videostudio;
import android.graphics.*;
import androidx.media3.effect.CanvasOverlay;
/** Clip text is composited into exported pixels, rather than metadata only. */
final class EditorTitleOverlay extends CanvasOverlay {
    private final String text; private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
    EditorTitleOverlay(String text){super(true);this.text=text;}
    @Override public void onDraw(Canvas canvas,long timeUs){
        canvas.drawColor(Color.TRANSPARENT,PorterDuff.Mode.CLEAR);
        paint.setColor(Color.WHITE);paint.setTextAlign(Paint.Align.CENTER);paint.setTypeface(Typeface.DEFAULT_BOLD);
        paint.setTextSize(canvas.getWidth()*.045f);paint.setShadowLayer(4,0,2,Color.BLACK);
        String[] lines=text.split("\n");float y=canvas.getHeight()*.82f;
        for(int i=0;i<Math.min(5,lines.length);i++)canvas.drawText(lines[i],canvas.getWidth()/2f,y+i*paint.getTextSize()*1.2f,paint);
    }
}
