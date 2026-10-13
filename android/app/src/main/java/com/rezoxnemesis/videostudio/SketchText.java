package com.rezoxnemesis.videostudio;
import android.graphics.*;
import android.text.*;
import org.json.JSONObject;
/** Editable lettering uses Android's bundled fonts and the same Canvas for preview/export. */
final class SketchText {
 static final String[] FAMILIES={"sans-serif","sans-serif-condensed","serif","monospace","cursive"};
 static final String[] STYLES={"normal","bold","italic","bold_italic"};
 static void validate(JSONObject object)throws Exception {
  SketchDocument.text(object,"text",4096);
  if(!java.util.Arrays.asList(FAMILIES).contains(object.optString("fontFamily","sans-serif")))throw SketchDocument.bad("Use an installed native font family");
  if(!java.util.Arrays.asList(STYLES).contains(object.optString("fontStyle","normal")))throw SketchDocument.bad("Unknown text font style");
  if(!java.util.Arrays.asList("left","center","right").contains(object.optString("textAlign","left")))throw SketchDocument.bad("Unknown text alignment");
  if(object.has("fontSize"))SketchDocument.number(object,"fontSize",.005,.3);
  if(object.has("letterSpacing"))SketchDocument.number(object,"letterSpacing",-.1,.5);
 }
 static void draw(Canvas canvas,JSONObject object,RectF bounds,Paint original,int height,float fraction)throws Exception {
  TextPaint paint=new TextPaint(original);String style=object.optString("fontStyle","normal");int nativeStyle=style.equals("bold")?Typeface.BOLD:style.equals("italic")?Typeface.ITALIC:style.equals("bold_italic")?Typeface.BOLD_ITALIC:Typeface.NORMAL;
  paint.setTypeface(Typeface.create(object.optString("fontFamily","sans-serif"),nativeStyle));paint.setTextSize((float)object.optDouble("fontSize",.04)*height);paint.setLetterSpacing((float)object.optDouble("letterSpacing",0));paint.setStyle(Paint.Style.FILL);
  String text=object.getString("text");if(fraction<1){int count=text.codePointCount(0,text.length());text=text.substring(0,text.offsetByCodePoints(0,Math.max(0,Math.min(count,(int)Math.ceil(count*fraction)))));}
  String align=object.optString("textAlign","left");Layout.Alignment alignment=align.equals("center")?Layout.Alignment.ALIGN_CENTER:align.equals("right")?Layout.Alignment.ALIGN_OPPOSITE:Layout.Alignment.ALIGN_NORMAL;
  StaticLayout layout=StaticLayout.Builder.obtain(text,0,text.length(),paint,Math.max(1,(int)Math.ceil(bounds.width()))).setAlignment(alignment).setIncludePad(false).setBreakStrategy(Layout.BREAK_STRATEGY_SIMPLE).build();int save=canvas.save();canvas.clipRect(bounds);canvas.translate(bounds.left,bounds.top);layout.draw(canvas);canvas.restoreToCount(save);
 }
}
