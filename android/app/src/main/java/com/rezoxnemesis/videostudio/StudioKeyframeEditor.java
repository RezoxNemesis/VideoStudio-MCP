package com.rezoxnemesis.videostudio;

import android.app.AlertDialog;
import android.app.Activity;
import android.content.Context;
import android.text.InputType;
import android.view.View;
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
import java.util.function.LongConsumer;
import java.util.function.Predicate;

/** Native authoring surface; its save fragments are the public precision_edit operations. */
public final class StudioKeyframeEditor {
    private static final String[] FIELDS={"x","y","scaleX","scaleY","rotate","anchorX","anchorY","opacity"};
    private static final String[] EASINGS={"linear","ease_in","ease_out","ease_in_out","hold"};
    private final Context context;
    private final long duration;
    private final Predicate<JSONObject> save;
    private final LongConsumer seek;
    private final LinearLayout content,keyList;
    private final TextView status,selection;
    private final EditText time;
    private final EditText[] values=new EditText[FIELDS.length];
    private final Spinner easing;
    private JSONObject effects;
    private JSONArray localKeys;
    private long loadedTime;

    public static AlertDialog show(Context context,JSONObject effects,long duration,long initialTime,
                                   Predicate<JSONObject> save,LongConsumer seek)throws Exception{
        StudioKeyframeEditor editor=new StudioKeyframeEditor(context,effects,duration,initialTime,save,seek);
        ScrollView scroll=new ScrollView(context);scroll.addView(editor.content);
        AlertDialog dialog=new AlertDialog.Builder(context).setTitle("Transform keyframe manager").setView(scroll)
                .setNegativeButton("Close",null).create();
        dialog.show();StudioUi.prepareTouchLayout(editor.content);return dialog;
    }

    private StudioKeyframeEditor(Context context,JSONObject effects,long duration,long initialTime,
                                 Predicate<JSONObject> save,LongConsumer seek)throws Exception{
        this.context=context;this.duration=duration;this.save=save;this.seek=seek;
        this.effects=new JSONObject(effects==null?"{}":effects.toString());
        StudioClipKeyframes.fromEffects(this.effects,duration);
        content=column();content.setPadding(dp(18),dp(8),dp(18),dp(12));
        label("Exact clip-local edited time. Keys outside a split clip remain saved in the original motion clock.");
        status=label("");keyList=column();content.addView(keyList);
        LinearLayout navigation=column();content.addView(navigation);
        addButton(navigation,"Previous key",()->adjacent(-1));addButton(navigation,"Next key",()->adjacent(1));
        label("Clip-local edited time (ms), 0.."+duration);
        time=number("Keyframe time (ms)");time.setInputType(InputType.TYPE_CLASS_NUMBER);content.addView(time);
        selection=label("");
        addButton(content,"Load time",()->{try{load(enteredTime());}catch(Exception error){invalid(error);}});
        addButton(content,"Preview loaded time",()->{try{long at=enteredTime();load(at);seek.accept(at);}catch(Exception error){invalid(error);}});
        label("X/Y: fraction of output frame, positive right/down (-2..2). Axis scale: 0.1..8. Rotation: -360..360 degrees. Anchor and opacity: 0..1.");
        for(int i=0;i<FIELDS.length;i++){label(FIELDS[i]);values[i]=number("Keyframe "+FIELDS[i]);content.addView(values[i]);}
        label("Interpolation from this key to the next");easing=new Spinner(context);
        easing.setContentDescription("Keyframe easing");easing.setAdapter(new ArrayAdapter<>(context,android.R.layout.simple_spinner_dropdown_item,EASINGS));content.addView(easing);
        addButton(content,"Add / update key",this::upsert);
        addButton(content,"Delete key at entered time",this::delete);
        addButton(content,"Reset entered values",()->{
            double[] defaults={0,0,1,1,0,.5,.5,1};for(int i=0;i<values.length;i++)values[i].setText(Double.toString(defaults[i]));easing.setSelection(0);
        });
        addButton(content,"Clear all keys",()->new AlertDialog.Builder(context).setTitle("Clear all transform keys?")
                .setMessage("This also removes keys retained outside this split clip. Static transform values remain.")
                .setNegativeButton("Keep keys",null).setPositiveButton("Clear keys",(dialog,which)->clear()).show());
        refreshKeys();load(Math.max(0,Math.min(duration,initialTime)));
    }

    private void refreshKeys()throws Exception{
        localKeys=StudioKeyframeEdits.localKeys(effects,duration);keyList.removeAllViews();
        int total=StudioClipKeyframes.authoredKeys(effects,duration).length();
        status.setText(localKeys.length()+" keys in this clip · "+total+" / "+StudioClipKeyframes.MAX_KEYS+" authored keys · "+(total-localKeys.length())+" outside this clip");
        if(localKeys.length()==0){TextView empty=new TextView(context);empty.setText("No keys in this clip. Load a time, set values, then add a key.");keyList.addView(empty);}
        for(int i=0;i<localKeys.length();i++){
            JSONObject key=localKeys.getJSONObject(i);long at=key.getLong("timeMs");
            addButton(keyList,"Key at "+at+" ms",()->{try{load(at);}catch(Exception error){invalid(error);}});
        }
        StudioUi.prepareTouchLayout(content);
    }

    private void load(long at)throws Exception{
        JSONObject sampled=StudioKeyframeEdits.values(StudioClipKeyframes.fromEffects(effects,duration).at(at));
        String selectedEasing="linear";boolean exact=false;
        for(int i=0;i<localKeys.length();i++)if(localKeys.getJSONObject(i).getLong("timeMs")==at){selectedEasing=localKeys.getJSONObject(i).optString("easing","linear");exact=true;break;}
        loadedTime=at;time.setText(Long.toString(at));
        for(int i=0;i<FIELDS.length;i++)values[i].setText(Double.toString(sampled.getDouble(FIELDS[i])));
        easing.setSelection(Math.max(0,java.util.Arrays.asList(EASINGS).indexOf(selectedEasing)));
        time.setError(null);selection.setText((exact?"Selected key at ":"Sampled transform at ")+at+" ms"+(exact?"":" · new key"));
        for(int i=0;i<keyList.getChildCount();i++)if(keyList.getChildAt(i) instanceof Button){
            Button button=(Button)keyList.getChildAt(i);boolean selected=button.getText().toString().equals("Key at "+at+" ms");
            button.setSelected(selected);StudioUi.styleButton(button,selected);
        }
    }

    private void adjacent(int direction){
        try{
            long current=enteredTime(),target=-1;
            for(int i=0;i<localKeys.length();i++){
                long at=localKeys.getJSONObject(i).getLong("timeMs");
                if(direction<0&&at<current)target=at;
                if(direction>0&&at>current){target=at;break;}
            }
            if(target>=0)load(target);else Toast.makeText(context,direction<0?"No earlier key":"No later key",Toast.LENGTH_SHORT).show();
        }catch(Exception error){invalid(error);}
    }

    private void upsert(){
        try{
            long at=enteredTime();JSONObject key=new JSONObject().put("timeMs",at).put("easing",easing.getSelectedItem().toString());
            for(int i=0;i<FIELDS.length;i++)key.put(FIELDS[i],Double.parseDouble(values[i].getText().toString().trim()));
            JSONArray edits=new JSONArray().put(new JSONObject().put("action","upsert").put("keyframe",key));
            persist(edits,at);
        }catch(Exception error){invalid(error);}
    }

    private void delete(){
        try{long at=enteredTime();persist(new JSONArray().put(new JSONObject().put("action","delete").put("timeMs",at)),at);}
        catch(Exception error){invalid(error);}
    }

    private void persist(JSONArray edits,long at)throws Exception{
        if(!active())return;
        JSONObject candidate=StudioKeyframeEdits.apply(effects,duration,edits);
        if(save.test(new JSONObject().put("keyframeEdits",edits))){effects=candidate;refreshKeys();load(at);}
    }

    private void clear(){
        if(!active())return;
        try{
            if(save.test(new JSONObject().put("clearKeyframes",true))){effects.remove("keyframes");effects.remove("studioKeyframeDurationMs");effects.remove("studioKeyframeOffsetMs");refreshKeys();load(loadedTime);}
        }catch(Exception error){invalid(error);}
    }

    private long enteredTime(){
        try{long at=Long.parseLong(time.getText().toString().trim());if(at<0||at>duration)throw new NumberFormatException();return at;}
        catch(NumberFormatException error){throw new IllegalArgumentException("Enter an integer clip time in 0.."+duration+" ms");}
    }
    private void invalid(Exception error){time.setError(error.getMessage());Toast.makeText(context,"Keyframe not changed: "+error.getMessage(),Toast.LENGTH_LONG).show();}
    private boolean active(){return !(context instanceof Activity)||(!((Activity)context).isFinishing()&&!((Activity)context).isDestroyed());}
    private int dp(int amount){return StudioUi.dp(context,amount);}
    private LinearLayout column(){LinearLayout column=new LinearLayout(context);column.setOrientation(LinearLayout.VERTICAL);return column;}
    private TextView label(String text){TextView view=new TextView(context);view.setText(text);view.setPadding(0,dp(6),0,dp(4));content.addView(view);return view;}
    private EditText number(String description){EditText field=new EditText(context);field.setInputType(InputType.TYPE_CLASS_NUMBER|InputType.TYPE_NUMBER_FLAG_DECIMAL|InputType.TYPE_NUMBER_FLAG_SIGNED);field.setContentDescription(description);field.setMinHeight(dp(48));return field;}
    private void addButton(LinearLayout parent,String title,Runnable action){Button button=new Button(context);button.setText(title);StudioUi.styleButton(button,false);button.setOnClickListener(view->{if(active())action.run();});parent.addView(button,new LinearLayout.LayoutParams(-1,-2));}
}
