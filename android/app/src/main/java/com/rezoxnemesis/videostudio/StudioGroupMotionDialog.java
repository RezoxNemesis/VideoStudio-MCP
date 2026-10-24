package com.rezoxnemesis.videostudio;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.text.InputType;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
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
                .setTitle("Motion Group · exact keyframes")
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
        if(context==null||group==null||!StudioLayer.GROUP.equals(group.type)||save==null)
            throw new IllegalArgumentException("A saved original motion group is required");
        this.context=context;this.save=save;
        base=group.copy();
        detached=group.motionKeyframes==null?new JSONArray():new JSONArray(group.motionKeyframes.toString());
        column=new LinearLayout(context);column.setOrientation(LinearLayout.VERTICAL);
        int pad=StudioUi.dp(context,16);column.setPadding(pad,pad,pad,pad);
        label("The motion group moves all linked titles, images and vector layers together. "
                +"Values are relative to each child: X/Y in output-frame units, scale ×, "
                +"rotation in degrees, and opacity from 0..1. Timing is group-local in milliseconds.");
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
            column.addView(fields[i]);
        }
        label("Interpolation to next key");
        easing=new Spinner(context);
        easing.setContentDescription("Group motion keyframe easing");
        easing.setAdapter(new ArrayAdapter<>(context,android.R.layout.simple_spinner_dropdown_item,EASINGS));
        column.addView(easing);
        label("Cubic Bézier handles: x1,y1,x2,y2");
        bezier=new EditText(context);bezier.setSingleLine(true);
        bezier.setContentDescription("Custom group Bézier control handles");
        bezier.setInputType(InputType.TYPE_CLASS_TEXT);
        bezier.setText(StudioBezierEasing.PRESET_FILM);
        column.addView(bezier);
        addButton(column,"Add or update key",this::upsert);
        addButton(column,"Remove key at time",this::remove);
        addButton(column,"Clear all draft keys",()->{
            try{detached=new JSONArray();renderKeys();load(0);}
            catch(Exception e){invalid(e);}
        });
        label("Apply commits the entire group once; Cancel discards every draft change. "
                +"Preview and export then use the same saved animation curves.");
    }

    private void upsert(){
        try{
            long at=enteredTime();
            JSONObject key=new JSONObject().put("timeMs",at)
                    .put("easing",easing.getSelectedItem().toString());
            for(int i=0;i<FIELDS.length;i++)
                key.put(FIELDS[i],Double.parseDouble(fields[i].getText().toString()));
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
