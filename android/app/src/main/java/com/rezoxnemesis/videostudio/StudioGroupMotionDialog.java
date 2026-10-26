package com.rezoxnemesis.videostudio;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.text.InputType;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.function.Predicate;

/**
 * Fully native, exact-keyframe edit view for a non-rendering group.
 * The immutable source GROUP is never edited here: one successful Save
 * delegates to the same revision-guarded native motion_group_edit pathway
 * used by the authenticated ChatGPT MCP.
 */
public final class StudioGroupMotionDialog {
    private static final String[] EASINGS={"linear","ease_in","ease_out","ease_in_out","hold","cubic_bezier"};
    private static final String[] FIELDS={"x","y","scale","rotate","opacity"};
    private final Context context;
    private final Predicate<JSONArray> save;
    private final StudioLayer base;
    private final LinearLayout column,keyList;
    private final EditText time,bezier;
    private final StudioBezierCurveView curve;
    private final EditText[] fields=new EditText[FIELDS.length];
    private final Spinner easing;
    private final TextView status;
    private JSONArray detached;
    private AlertDialog dialog;
    private boolean closed;

    public static AlertDialog show(Context context,StudioLayer group,
                                   long selectedTimeMs,Predicate<JSONArray> save) throws Exception {
        StudioGroupMotionDialog editor=new StudioGroupMotionDialog(context,group,save);
        ScrollView scroll=new ScrollView(context);
        scroll.addView(editor.column);
        editor.dialog=new AlertDialog.Builder(context)
                .setTitle(StudioLayer.CAMERA.equals(group.type)?
                        "Composition Camera · exact 2D keys":"Motion Group · exact keyframes")
                .setView(scroll)
                .setPositiveButton("Apply group keys",null)
                .setNegativeButton("Cancel",null).create();
        editor.dialog.setOnCancelListener(ignored->editor.closed=true);
        editor.dialog.setOnDismissListener(ignored->editor.closed=true);
        editor.dialog.setOnShowListener(ignored->{
            editor.dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view->{
                if(!editor.active())return;
                try{
                    if(editor.save.test(new JSONArray(editor.detached.toString())))
                        editor.dialog.dismiss();
                }catch(Exception failure){editor.invalid(failure);}
            });
            editor.dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener(view->{
                editor.closed=true;editor.dialog.dismiss();
            });
        });
        editor.dialog.show();
        editor.renderKeys();editor.load(Math.max(0,Math.min(group.durationMs,selectedTimeMs)));
        StudioUi.prepareTouchLayout(editor.column);
        return editor.dialog;
    }

    private StudioGroupMotionDialog(Context context,StudioLayer group,
                                    Predicate<JSONArray> save) throws Exception {
        if(context==null||group==null||(!StudioLayer.GROUP.equals(group.type)
                &&!StudioLayer.CAMERA.equals(group.type))||save==null)
            throw new IllegalArgumentException("A saved original motion group is required");
        this.context=context;this.save=save;
        base=group.copy();
        detached=group.motionKeyframes==null?new JSONArray():new JSONArray(group.motionKeyframes.toString());
        column=new LinearLayout(context);column.setOrientation(LinearLayout.VERTICAL);
        int pad=StudioUi.dp(context,16);column.setPadding(pad,pad,pad,pad);
        label(StudioLayer.CAMERA.equals(group.type)?
                "Camera moves the final entire composition AFTER image/video/title compositing. "
                +"X/Y move the whole picture, scale changes framing, rotation turns the frame. "
                +"This is 2D, not physical 3D; camera opacity is fixed at 1.":
                "The motion group moves linked title, image and vector layers together. "
                +"X/Y are relative to output-frame units, scale multiplies them, "
                +"rotation adds degrees, and opacity is 0..1. Times are group-local.");
        status=label("");
        keyList=new LinearLayout(context);keyList.setOrientation(LinearLayout.VERTICAL);
        column.addView(keyList);
        label("Group key time (0 to "+group.durationMs+" ms)");
        time=numberInput("Group motion key time");
        time.setInputType(InputType.TYPE_CLASS_NUMBER);
        column.addView(time);
        addButton(column,"Sample at time",()->{
            try{load(enteredTime());}catch(Exception e){invalid(e);}
        });
        for(int i=0;i<FIELDS.length;i++){
            label(FIELDS[i]);fields[i]=numberInput("Group transform "+FIELDS[i]);
            if(StudioLayer.CAMERA.equals(group.type)&&"opacity".equals(FIELDS[i])){
                fields[i].setText("1.0");fields[i].setEnabled(false);
                fields[i].setContentDescription("Camera opacity fixed at 1: change clip opacity instead");
            }
            column.addView(fields[i]);
        }
        label("Interpolation to next key");
        easing=new Spinner(context);
        easing.setContentDescription("Group motion keyframe easing");
        easing.setAdapter(new ArrayAdapter<>(context,android.R.layout.simple_spinner_dropdown_item,EASINGS));
        column.addView(easing);
        label("Curve presets · shape the saved transition between keys");
        HorizontalScrollView presetRail=new HorizontalScrollView(context);
        presetRail.setHorizontalScrollBarEnabled(false);
        LinearLayout presetRow=new LinearLayout(context);presetRow.setOrientation(LinearLayout.HORIZONTAL);
        presetRail.addView(presetRow);column.addView(presetRail,new LinearLayout.LayoutParams(-1,-2));
        for(String[] preset:new String[][]{
                {"Linear","0,0,1,1"},{"Smooth","0.42,0,0.58,1"},
                {"Ease in","0.42,0,1,1"},{"Ease out","0,0,0.58,1"},
                {"Film","0.22,1,0.36,1"}}){
            Button choice=new Button(context);choice.setText(preset[0]);StudioUi.styleButton(choice,false);
            choice.setOnClickListener(view->setCurvePreset(preset[1],"Linear".equals(preset[0])));
            presetRow.addView(choice,new LinearLayout.LayoutParams(StudioUi.dp(context,104),StudioUi.dp(context,48)));
        }
        label("Cubic Bézier handles: x1,y1,x2,y2");
        bezier=new EditText(context);bezier.setSingleLine(true);
        bezier.setContentDescription("Custom group Bézier control handles");
        bezier.setInputType(InputType.TYPE_CLASS_TEXT);
        bezier.setText(StudioBezierEasing.PRESET_FILM);
        column.addView(bezier);
        curve=new StudioBezierCurveView(context);
        curve.setOnHandlesChanged(handles->{
            bezier.setText(StudioBezierCurveView.format(handles));
            easing.setSelection(EASINGS.length-1);
        });
        column.addView(curve,new LinearLayout.LayoutParams(-1,StudioUi.dp(context,220)));
        addButton(column,"Update graph from numeric handles",()->{
            try{curve.setHandles(StudioBezierEasing.fromText(bezier.getText().toString()));}
            catch(Exception error){invalid(error);}
        });
        addButton(column,"Add or update key",this::upsert);
        addButton(column,"Remove key at time",this::remove);
        addButton(column,"Clear all draft keys",()->{
            try{detached=new JSONArray();renderKeys();load(0);}
            catch(Exception e){invalid(e);}
        });
        label("Apply commits the entire group once; Cancel discards every draft change. "
                +"Preview and export then use the same saved animation curves.");
    }

    private void setCurvePreset(String coordinates,boolean linear){
        JSONArray handles=StudioBezierEasing.fromText(coordinates);
        bezier.setText(StudioBezierCurveView.format(handles));
        curve.setHandles(handles);
        easing.setSelection(linear?0:EASINGS.length-1);
    }

    private void upsert(){
        try{
            long at=enteredTime();
            JSONObject key=new JSONObject().put("timeMs",at)
                    .put("easing",easing.getSelectedItem().toString());
            for(int i=0;i<FIELDS.length;i++)
                key.put(FIELDS[i],StudioLayer.CAMERA.equals(base.type)&&
                        "opacity".equals(FIELDS[i])?1.0:
                        Double.parseDouble(fields[i].getText().toString()));
            if("cubic_bezier".equals(easing.getSelectedItem().toString()))
                key.put("bezier",StudioBezierEasing.fromText(bezier.getText().toString()));
            ArrayList<JSONObject> changed=new ArrayList<>();
            for(int i=0;i<detached.length();i++) {
                JSONObject old=detached.getJSONObject(i);
                if(old.getLong("timeMs")!=at)changed.add(old);
            }
            changed.add(key);
            changed.sort(Comparator.comparingLong(x->x.optLong("timeMs")));
            JSONArray next=new JSONArray(changed);
            if(next.length()>StudioClipKeyframes.MAX_KEYS)
                throw new IllegalArgumentException("Maximum 64 grouped motion keys");
            preflight(next);
            detached=next;renderKeys();load(at);
        }catch(Exception error){invalid(error);}
    }

    private void remove(){
        try{
            long at=enteredTime();
            JSONArray next=new JSONArray();boolean found=false;
            for(int i=0;i<detached.length();i++){
                JSONObject key=detached.getJSONObject(i);
                if(key.getLong("timeMs")==at)found=true;else next.put(key);
            }
            if(!found)throw new IllegalArgumentException("No authored key at this group time");
            preflight(next);detached=next;renderKeys();load(at);
        }catch(Exception error){invalid(error);}
    }

    private void preflight(JSONArray keys)throws Exception{
        StudioLayer pending=base.copy();
        pending.motionKeyframes=new JSONArray(keys.toString());
        pending.validate();StudioLayerMotion.fromLayer(pending);
    }

    private void load(long at)throws Exception{
        StudioLayer sample=base.copy();
        sample.motionKeyframes=new JSONArray(detached.toString());
        StudioClipKeyframes.Values v=StudioLayerMotion.fromLayer(sample).atUs(at*1000L);
        double[] channels={v.x,v.y,v.scaleX,v.rotate,v.opacity};
        time.setText(Long.toString(at));
        for(int i=0;i<fields.length;i++)fields[i].setText(Double.toString(channels[i]));
        String keyEase="linear";String control=StudioBezierEasing.PRESET_FILM;
        for(int i=0;i<detached.length();i++){
            JSONObject key=detached.getJSONObject(i);
            if(key.getLong("timeMs")!=at)continue;
            keyEase=key.optString("easing","linear");
            JSONArray coordinates=key.optJSONArray("bezier");
            if(coordinates!=null&&coordinates.length()==4)
                control=coordinates.getDouble(0)+","+coordinates.getDouble(1)
                        +","+coordinates.getDouble(2)+","+coordinates.getDouble(3);
            break;
        }
        easing.setSelection(Math.max(0,java.util.Arrays.asList(EASINGS).indexOf(keyEase)));
        bezier.setText(control);
        curve.setHandles(StudioBezierEasing.fromText(bezier.getText().toString()));
    }

    private void renderKeys()throws Exception{
        keyList.removeAllViews();
        status.setText(detached.length()+" / 64 grouped motion keys");
        for(int i=0;i<detached.length();i++){
            long at=detached.getJSONObject(i).getLong("timeMs");
            addButton(keyList,"Key at "+at+" ms",()->{
                try{load(at);}catch(Exception error){invalid(error);}
            });
        }
    }

    private long enteredTime(){
        try {
            long at=Long.parseLong(time.getText().toString().trim());
            if(at<0||at>base.durationMs)throw new NumberFormatException("Outside duration");
            return at;
        }catch(NumberFormatException e){
            throw new IllegalArgumentException("Enter an integer time 0.."+base.durationMs+" ms");
        }
    }

    private TextView label(String value){
        TextView view=new TextView(context);view.setText(value);
        view.setPadding(0,StudioUi.dp(context,6),0,StudioUi.dp(context,4));
        column.addView(view);return view;
    }
    private EditText numberInput(String hint){
        EditText edit=new EditText(context);
        edit.setInputType(InputType.TYPE_CLASS_NUMBER|
                InputType.TYPE_NUMBER_FLAG_DECIMAL|InputType.TYPE_NUMBER_FLAG_SIGNED);
        edit.setContentDescription(hint);edit.setMinHeight(StudioUi.dp(context,48));return edit;
    }
    private void addButton(LinearLayout parent,String title,Runnable action){
        Button button=new Button(context);button.setText(title);
        StudioUi.styleButton(button,false);
        button.setOnClickListener(view->{if(active())action.run();});
        parent.addView(button,new LinearLayout.LayoutParams(-1,-2));
    }
    private boolean active(){return !closed&&(dialog==null||dialog.isShowing())
            &&(!(context instanceof Activity)||(!((Activity)context).isFinishing()
                &&!((Activity)context).isDestroyed()));}
    private void invalid(Exception problem){
        Toast.makeText(context,"Group animation unchanged: "+problem.getMessage(),Toast.LENGTH_LONG).show();
    }
}
