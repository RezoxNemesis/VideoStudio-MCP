package com.rezoxnemesis.videostudio;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Typeface;
import android.text.InputType;
import android.widget.Button;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TableLayout;
import android.widget.TableRow;
import android.widget.TextView;
import android.widget.Toast;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.HashMap;
import java.util.Map;

/**
 * Native OpenToonz-style Xsheet.
 *
 * Tapping a cell seeks to its original frame. Long-pressing a source drawing
 * edits its held exposure through the SAME revision-checked sketch_edit path
 * used by pen gestures and the private ChatGPT MCP. No artwork pixels or
 * source paint snapshots are copied, redrawn or hidden behind fake cels.
 */
final class SketchXsheetDialog {
    private static final int ROWS=12,COLS=4;
    interface Selection {void jump(int frame,String layerId);}
    interface ExposureWriter {
        /** Must atomically submit an owned edit or throw; returns new local document. */
        JSONObject apply(int sourceFrame,String layerId,int exposureFrames,long expectedRevision) throws Exception;
    }
    private SketchXsheetDialog(){}

    static void show(Activity owner,JSONObject source,int frame,int columnStart,Selection selection){
        show(owner,source,frame,columnStart,selection,null);
    }

    static void show(Activity owner,JSONObject source,int frame,int columnStart,
                     Selection selection,ExposureWriter writer){
        try{
            int total=source.getInt("frameCount");
            int start=Math.max(0,Math.min(total-1,frame));
            start=(start/ROWS)*ROWS;
            JSONObject sheet=SketchXsheetProjection.project(source,start,Math.min(ROWS,total-start));
            JSONArray levels=sheet.getJSONArray("columns");
            int col=Math.max(0,Math.min(columnStart,Math.max(0,levels.length()-COLS)));
            int last=Math.min(col+COLS,levels.length());
            final int firstShownCol=col;
            final AlertDialog[] current=new AlertDialog[1];
            LinearLayout root=new LinearLayout(owner);
            root.setOrientation(LinearLayout.VERTICAL);
            root.setPadding(StudioUi.dp(owner,8),StudioUi.dp(owner,5),
                    StudioUi.dp(owner,8),StudioUi.dp(owner,5));
            root.setBackgroundColor(StudioUi.SURFACE);
            root.addView(text(owner,"Drawing ● · Hold = · Blank − · * transform key · ◇ shape key",11));
            root.addView(text(owner,writer==null
                    ?"Inspect artwork without changing it. Tap any cell to seek."
                    :"Tap to seek. Long-press a ● source drawing to change its exposure. "+
                    "Protected layers and animation keys cannot be shortened without review.",11));
            HorizontalScrollView horizontal=new HorizontalScrollView(owner);
            ScrollView vertical=new ScrollView(owner);
            TableLayout table=new TableLayout(owner);
            Map<String,Integer>[] sizes=new Map[last-col];
            for(int c=col;c<last;c++){
                JSONObject level=levels.getJSONObject(c);
                sizes[c-col]=new HashMap<>();
                JSONArray drawings=level.getJSONArray("drawings");
                for(int d=0;d<drawings.length();d++){
                    JSONObject drawing=drawings.getJSONObject(d);
                    sizes[c-col].put(drawing.getString("drawingId"),drawing.getJSONArray("objectIds").length());
                }
            }
            TableRow header=new TableRow(owner);
            header.addView(cell(owner,"FRAME",true,StudioUi.TEXT,80));
            for(int c=col;c<last;c++){
                JSONObject level=levels.getJSONObject(c);
                String caption=level.getString("name")+(level.getBoolean("visible")?"":" [hidden]");
                header.addView(cell(owner,caption,true,StudioUi.TEXT,125));
            }
            table.addView(header);
            for(int f=0;f<sheet.getInt("returnedFrames");f++){
                final int selectedFrame=start+f;
                TableRow row=new TableRow(owner);
                TextView frameLabel=cell(owner,String.valueOf(selectedFrame+1),
                        false,StudioUi.ACCENT,80);
                frameLabel.setOnClickListener(v->selection.jump(selectedFrame,
                        levels.optJSONObject(firstShownCol).optString("layerId")));
                row.addView(frameLabel);
                for(int c=col;c<last;c++){
                    JSONObject level=levels.getJSONObject(c),
                            slot=level.getJSONArray("cells").getJSONObject(f);
                    String state=slot.getString("state"),tag;
                    if("blank".equals(state))tag="−";
                    else if("hold".equals(state))tag="= ";
                    else tag="● ";
                    if(!"blank".equals(state)){
                        int count=sizes[c-col].get(slot.getString("drawingId"));
                        if(!"hold".equals(state))tag+=count+" part"+(count==1?"":"s");
                        else tag+="hold";
                    }
                    if(slot.optBoolean("transformKey"))tag+=" *";
                    if(slot.optBoolean("shapeKey"))tag+=" ◇";
                    String layerId=level.getString("layerId");
                    TextView item=cell(owner,tag,false,
                            "blank".equals(state)?StudioUi.MUTED:StudioUi.TEXT,125);
                    item.setContentDescription("Frame "+(selectedFrame+1)+", "+
                            level.getString("name")+", "+state+
                            (writer==null?"":". Long press source drawing to edit exposure"));
                    item.setOnClickListener(v->selection.jump(selectedFrame,layerId));
                    if(writer!=null){
                        item.setLongClickable(true);
                        final int chosenColumn=col;
                        item.setOnLongClickListener(v->{
                            try{
                                openHoldEditor(owner,source,selectedFrame,layerId,chosenColumn,
                                        selection,writer,current);
                            }catch(Exception failure){
                                Toast.makeText(owner,"Xsheet: "+failure.getMessage(),Toast.LENGTH_LONG).show();
                            }
                            return true;
                        });
                    }
                    row.addView(item);
                }
                table.addView(row);
            }
            vertical.addView(table);
            horizontal.addView(vertical);
            root.addView(horizontal,new LinearLayout.LayoutParams(-1,StudioUi.dp(owner,385)));
            final int page=start,selectedCol=col;
            LinearLayout nav=new LinearLayout(owner);
            nav.setOrientation(LinearLayout.HORIZONTAL);
            root.addView(nav);
            addNav(owner,nav,"◀ frames",start>0,()->{
                current[0].dismiss();show(owner,source,Math.max(0,page-ROWS),selectedCol,selection,writer);
            });
            addNav(owner,nav,"frames ▶",start+ROWS<total,()->{
                current[0].dismiss();show(owner,source,Math.min(total-1,page+ROWS),selectedCol,selection,writer);
            });
            addNav(owner,nav,"◀ levels",selectedCol>0,()->{
                current[0].dismiss();show(owner,source,page,
                        Math.max(0,selectedCol-COLS),selection,writer);
            });
            addNav(owner,nav,"levels ▶",last<levels.length(),()->{
                current[0].dismiss();show(owner,source,page,
                        Math.min(levels.length()-COLS,selectedCol+COLS),selection,writer);
            });
            current[0]=new AlertDialog.Builder(owner)
                    .setTitle("Xsheet · frames "+(page+1)+"–"+Math.min(total,page+ROWS)
                            +" / "+total+" · r"+source.getLong("revision"))
                    .setView(root).setPositiveButton("Done",null).create();
            current[0].show();
        }catch(Exception failure){
            Toast.makeText(owner,"Xsheet: "+failure.getMessage(),Toast.LENGTH_LONG).show();
        }
    }

    private static void openHoldEditor(Activity owner,JSONObject source,int frame,String layerId,
                                       int column,Selection selection,ExposureWriter writer,
                                       AlertDialog[] parent)throws Exception {
        JSONObject info=SketchXsheetEdits.inspect(source,layerId,frame);
        int safe=info.getInt("maximumSafeExposureFrames");
        int minimum=info.getInt("minimumCurrentExposure"),
                maximum=info.getInt("maximumCurrentExposure");
        int count=info.getInt("sourceObjectCount");
        String notice=count+" real marks begin at frame "+(frame+1)+" (holds "+
                minimum+(minimum==maximum?"":"–"+maximum)+"). "+
                "Maximum safe hold: "+safe+" frames before the next source drawing. "+
                "Changing exposure does not synthesize an in-between.";
        LinearLayout form=new LinearLayout(owner);
        form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(StudioUi.dp(owner,18),StudioUi.dp(owner,10),
                StudioUi.dp(owner,18),StudioUi.dp(owner,10));
        form.addView(text(owner,notice,13));
        EditText input=new EditText(owner);
        input.setInputType(InputType.TYPE_CLASS_NUMBER);
        input.setSingleLine(true);
        input.setText(String.valueOf(maximum));
        input.setContentDescription("Native Xsheet exposure in frames");
        form.addView(input,new LinearLayout.LayoutParams(-1,StudioUi.dp(owner,54)));
        LinearLayout presets=new LinearLayout(owner);
        presets.setOrientation(LinearLayout.HORIZONTAL);
        form.addView(presets);
        addPreset(owner,presets,"On ones",1,input,safe);
        addPreset(owner,presets,"On twos",2,input,safe);
        addPreset(owner,presets,"On threes",3,input,safe);
        AlertDialog edit=new AlertDialog.Builder(owner).setTitle("Expose drawing at frame "+(frame+1))
                .setView(form).setPositiveButton("Apply exposure",null)
                .setNegativeButton("Cancel",null).create();
        // Bind directly after show(): the actual button now exists on Android and
        // in headless tests, with no posted onShow race that can lose the edit.
        edit.show();
        edit.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view->{
                    try{
                        int hold=Integer.parseInt(input.getText().toString().trim());
                        JSONObject op=new JSONObject().put("type","set_exposure_cel")
                                .put("layerId",layerId).put("frameIndex",frame)
                                .put("exposureFrames",hold);
                        SketchXsheetEdits.plan(source,op);
                        JSONObject next=writer.apply(frame,layerId,hold,source.getLong("revision"));
                        edit.dismiss();
                        if(parent[0]!=null)parent[0].dismiss();
                        show(owner,next,frame,column,selection,writer);
                    }catch(Exception failure){
                        input.setError(failure.getMessage());
                        Toast.makeText(owner,"Exposure unchanged: "+failure.getMessage(),
                                Toast.LENGTH_LONG).show();
                    }
                });
    }

    private static void addPreset(Activity owner,LinearLayout row,String label,int duration,
                                  EditText input,int max){
        Button button=new Button(owner);
        button.setText(label);button.setTextSize(11);
        button.setEnabled(duration<=max);
        StudioUi.styleButton(button,false);
        button.setOnClickListener(v->input.setText(String.valueOf(duration)));
        row.addView(button,new LinearLayout.LayoutParams(0,StudioUi.dp(owner,44),1));
    }

    private static TextView text(Activity a,String label,int size){
        TextView result=new TextView(a);
        result.setText(label);result.setTextColor(StudioUi.MUTED);
        result.setTextSize(size);
        result.setPadding(StudioUi.dp(a,4),StudioUi.dp(a,4),
                StudioUi.dp(a,4),StudioUi.dp(a,4));
        return result;
    }
    private static TextView cell(Activity a,String value,boolean heading,int color,int width){
        TextView result=text(a,value,heading?12:13);
        result.setTextColor(color);
        result.setTypeface(Typeface.MONOSPACE,heading?Typeface.BOLD:Typeface.NORMAL);
        result.setMinHeight(StudioUi.dp(a,38));
        result.setWidth(StudioUi.dp(a,width));
        result.setMaxWidth(StudioUi.dp(a,width));
        result.setPadding(StudioUi.dp(a,5),StudioUi.dp(a,9),
                StudioUi.dp(a,5),StudioUi.dp(a,4));
        return result;
    }
    private static void addNav(Activity owner,LinearLayout row,String title,boolean allowed,
                               Runnable action){
        Button button=new Button(owner);button.setText(title);
        button.setTextSize(10);button.setEnabled(allowed);
        StudioUi.styleButton(button,false);
        button.setOnClickListener(v->action.run());
        row.addView(button,new LinearLayout.LayoutParams(0,-2,1));
    }
}
