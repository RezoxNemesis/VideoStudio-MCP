package com.rezoxnemesis.videostudio;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.view.View;

/** Monitor-only thirds and title safe guides; never baked into the exported image. */
final class PreviewGuideView extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float aspect;
    PreviewGuideView(Context context, float aspect) { super(context); this.aspect=aspect; setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO); }
    @Override protected void onDraw(Canvas canvas) {
        paint.setColor(Color.argb(90,255,255,255));paint.setStrokeWidth(getResources().getDisplayMetrics().density);
        float width=Math.min(getWidth(),getHeight()*aspect),height=Math.min(getHeight(),getWidth()/aspect);
        float left=(getWidth()-width)/2f,top=(getHeight()-height)/2f;
        for(int i=1;i<3;i++){canvas.drawLine(left+width*i/3f,top,left+width*i/3f,top+height,paint);canvas.drawLine(left,top+height*i/3f,left+width,top+height*i/3f,paint);}
        paint.setStyle(Paint.Style.STROKE);paint.setColor(Color.argb(150,53,220,237));
        canvas.drawRect(left+width*.1f,top+height*.1f,left+width*.9f,top+height*.9f,paint);paint.setStyle(Paint.Style.FILL);
    }
}
