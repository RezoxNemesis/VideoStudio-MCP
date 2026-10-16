package com.rezoxnemesis.videostudio;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Typeface;
import android.view.View;
import android.widget.Button;
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
 * Compact touch-first read-only Xsheet. Rows=frames, columns=prepared layers,
 * with easy 12-frame and four-layer paging on a narrow Android screen.
 * Source and revision remain pinned while the sheet is open. Tapping a cell
 * seeks to the real native frame without rewriting a single exposure.
 */
final class SketchXsheetDialog {
    private static final int ROWS=12,COLS=4;
    interface Selection { void jump(int frame,String layerId); }
    private SketchXsheetDialog(){}

    static void show(Activity owner,JSONObject source,int frame,int columnStart,Selection selection){
        try{
            int total=source.getInt("frameCount");
            int start=Math.max(0,Math.min(total-1,frame));
            start=(start/ROWS)*ROWS;
            JSONObject sheet=SketchXsheetProjection.project(source,start,Math.min(ROWS,total-start));
            JSONArray levels=sheet.getJSONArray("columns");
            int col=Math.max(0,Math.min(columnStart,Math.max(0,levels.length()-COLS)));
            int last=Math.min(col+COLS,levels.length());
            LinearLayout root=new LinearLayout(owner);
            root.setOrientation(LinearLayout.VERTICAL);
            root.setPadding(StudioUi.dp(owner,8),StudioUi.dp(owner,5),StudioUi.dp(owner,8),StudioUi.dp(owner,5));
            root.setBackgroundColor(StudioUi.SURFACE);
            TextView info=text(owner,"Drawing ● · Hold = · Blank − · * means transform key",11);
            root.addView(info);
            TextView hint=text(owner,"Inspect levels without altering artwork. Tap a frame to move the editor playhead.",11);
            root.addView(hint);
            HorizontalScrollView horizontal=new HorizontalScrollView(owner);
            ScrollView vertical=new ScrollView(owner);
            TableLayout table=new TableLayout(owner);
            Map<String,Integer>[] sizes=new Map[last-col];
            for(int c=col;c<last;c++){
                JSONObject level=levels.getJSONObject(c);sizes[c-col]=new HashMap<>();
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
                TextView frameLabel=cell(owner,String.valueOf(selectedFrame+1),false,StudioUi.ACCENT,80);
                frameLabel.setOnClickListener(v->selection.jump(selectedFrame,levels.optJSONObject(col).optString("layerId")));
                row.addView(frameLabel);
                for(int c=col;c<last;c++){
                    JSONObject level=levels.getJSONObject(c),slot=level.getJSONArray("cells").getJSONObject(f);
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
                    String layerId=level.getString("layerId");
                    TextView item=cell(owner,tag,false,"blank".equals(state)?StudioUi.MUTED:StudioUi.TEXT,125);
                    item.setContentDescription("Frame "+(selectedFrame+1)+", "+level.getString("name")+", "+state);
                    item.setOnClickListener(v->selection.jump(selectedFrame,layerId));
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
            final AlertDialog[] current=new AlertDialog[1];
            addNav(owner,nav,"◀ frames",start>0,()->{
                current[0].dismiss();show(owner,source,Math.max(0,page-ROWS),selectedCol,selection);
            });
            addNav(owner,nav,"frames ▶",start+ROWS<total,()->{
                current[0].dismiss();show(owner,source,Math.min(total-1,page+ROWS),selectedCol,selection);
            });
            addNav(owner,nav,"◀ levels",selectedCol>0,()->{
                current[0].dismiss();show(owner,source,page,Math.max(0,selectedCol-COLS),selection);
            });
            addNav(owner,nav,"levels ▶",last<levels.length(),()->{
                current[0].dismiss();show(owner,source,page,Math.min(levels.length()-COLS,selectedCol+COLS),selection);
            });
            current[0]=new AlertDialog.Builder(owner).setTitle("Xsheet · frames "+(page+1)+"–"+Math.min(total,page+ROWS)
                    +" / "+total+" · r"+source.getLong("revision"))
                    .setView(root).setPositiveButton("Done",null).create();
            current[0].show();
        }catch(Exception failure){
            Toast.makeText(owner,"Xsheet: "+failure.getMessage(),Toast.LENGTH_LONG).show();
        }
    }

    private static TextView text(Activity a,String label,int size){
        TextView result=new TextView(a);
        result.setText(label);result.setTextColor(StudioUi.MUTED);
        result.setTextSize(size);result.setPadding(StudioUi.dp(a,4),StudioUi.dp(a,4),
                StudioUi.dp(a,4),StudioUi.dp(a,4));
        return result;
    }
    private static TextView cell(Activity a,String value,boolean heading,int color,int width){
        TextView result=text(a,value,heading?12:13);
        result.setTextColor(color);result.setTypeface(Typeface.MONOSPACE,heading?Typeface.BOLD:Typeface.NORMAL);
        result.setMinHeight(StudioUi.dp(a,38));
        result.setWidth(StudioUi.dp(a,width));
        result.setMaxWidth(StudioUi.dp(a,width));
        result.setPadding(StudioUi.dp(a,5),StudioUi.dp(a,9),StudioUi.dp(a,5),StudioUi.dp(a,4));
        return result;
    }
    private static void addNav(Activity owner,LinearLayout row,String title,boolean allowed,Runnable action){
        Button button=new Button(owner);button.setText(title);button.setTextSize(10);button.setEnabled(allowed);
        StudioUi.styleButton(button,false);
        button.setOnClickListener(v->action.run());
        row.addView(button,new LinearLayout.LayoutParams(0,-2,1));
    }
}
