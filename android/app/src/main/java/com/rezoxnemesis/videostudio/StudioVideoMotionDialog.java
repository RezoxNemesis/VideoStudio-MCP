package com.rezoxnemesis.videostudio;

import android.app.Activity;
import android.app.AlertDialog;
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
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.function.BooleanSupplier;

/** Detached silent VIDEO recipe; Apply is one ordinary revision-guarded layer edit. */
final class StudioVideoMotionDialog implements AutoCloseable {
    private static final String[] FIELDS={"x","y","scaleX","scaleY","rotate","opacity"};
    private static final String[] EASINGS={"linear","ease_in","ease_out","ease_in_out","hold"};
    private final Activity context;
    private final LinearLayout content,keyList;
    private final TextView status,selection;
    private final EditText time;
    private final EditText[] values=new EditText[FIELDS.length];
    private final Spinner easing;
    private StudioLayer draft;
    private AlertDialog dialog;
    private boolean closed,busy=true,sourceReady;
    private final StudioVideoSourceMetadata.Session source;
    private final Consumer<StudioVideoMotionDialog> onClosed;
    private final TextView sourceStatus;

    static StudioVideoMotionDialog show(Activity context,ProjectStore.Project project,ProjectStore.Asset asset,
            StudioLayer video,long initialTime,BooleanSupplier current,Predicate<StudioLayer> save,
            Consumer<StudioVideoMotionDialog> onClosed)throws Exception{
        return new StudioVideoMotionDialog(context,project,asset,video,initialTime,current,save,onClosed);
    }

    private StudioVideoMotionDialog(Activity context,ProjectStore.Project project,ProjectStore.Asset asset,
            StudioLayer video,long initialTime,BooleanSupplier current,Predicate<StudioLayer> save,
            Consumer<StudioVideoMotionDialog> onClosed)throws Exception{
        if(video==null||!StudioLayer.VIDEO.equals(video.type))throw new IllegalArgumentException("Video overlay required");
        this.context=context;this.onClosed=onClosed;draft=video.copy();StudioLayerMotion.fromLayer(draft);
        content=column();content.setPadding(dp(18),dp(8),dp(18),dp(12));
        label("Times start at this video's appearance, not at the start of the video. Load time samples this draft's motion. Add / update key keeps your entered values in the draft; Apply animation saves all draft keys together.");
        label("X/Y move the video by fractions of the output frame, positive right/down (-2..2). Axis scale multiplies its saved bounds (0.1..8). Rotation adds degrees (-360..360). Opacity multiplies its saved opacity (0..1). The motion clock starts at the output appearance time, independent of source in.");
        status=label("");keyList=column();content.addView(keyList);
        label("Video-local time (ms), 0.."+draft.durationMs);
        time=number("Video key time (ms)");time.setInputType(InputType.TYPE_CLASS_NUMBER);content.addView(time);
        selection=label("");
        addButton(content,"Load time",()->{try{load(enteredTime());}catch(Exception error){invalid(error);}});
        for(int i=0;i<FIELDS.length;i++){label(FIELDS[i]);values[i]=number("Video motion "+FIELDS[i]);content.addView(values[i]);}
        label("Interpolation from this key to the next");
        easing=new Spinner(context);easing.setContentDescription("Video motion easing");
        easing.setAdapter(new ArrayAdapter<>(context,android.R.layout.simple_spinner_dropdown_item,EASINGS));content.addView(easing);
        addButton(content,"Add / update key",this::upsert);
        addButton(content,"Remove key at entered time",this::remove);
        addButton(content,"Clear draft keys",()->{
            try{long at=enteredTime();replace(new JSONArray());load(at);}catch(Exception error){invalid(error);}
        });
        label("Close discards this draft. Apply animation keeps the same silent video source, source in, placement, timing, other tracks and video duration. Load time only samples draft values; use the edited video preview to review applied motion.");
        sourceStatus=label("Checking the owned video source before applying motion…");
        refreshKeys();load(Math.max(0,Math.min(draft.durationMs,initialTime)));
        ScrollView scroll=new ScrollView(context);scroll.addView(content);
        dialog=new AlertDialog.Builder(context).setTitle("Animate video overlay").setView(scroll)
                .setNegativeButton("Close",null).setPositiveButton("Apply animation",null).create();
        source=new StudioVideoSourceMetadata.Session(context,project.id,project.updatedAt,asset,this::active,current,save);
        dialog.setOnCancelListener(ignored->close());dialog.setOnDismissListener(ignored->close());dialog.show();
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener(ignored->close());
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(ignored->apply());
        setBusy(true);StudioUi.prepareTouchLayout(content);
        source.prepare(proof->{sourceReady=true;setBusy(false);sourceStatus.setText("Owned video source verified. Motion does not add source audio.");},error->{sourceReady=false;setBusy(false);sourceStatus.setText(error+". Reopen the video controls to retry.");});
    }

    AlertDialog dialog(){return dialog;}
    private void apply(){
        if(!active()||busy||!sourceReady)return;
        try{
            StudioLayer candidate=draft.copy();candidate.validate();setBusy(true);sourceStatus.setText("Checking unchanged video bytes before saving motion…");
            source.apply(candidate,(saved,error)->{if(!active())return;if(saved){close();return;}setBusy(false);sourceStatus.setText(error);});
        }catch(Exception error){setBusy(false);invalid(error);}
    }
    private void setBusy(boolean value){busy=value;dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(!value&&sourceReady);}
    @Override public void close(){if(closed)return;closed=true;source.close();if(dialog.isShowing())dialog.dismiss();onClosed.accept(this);}


    private JSONArray keys()throws Exception{
        JSONArray keys=draft.toJson().optJSONArray("motionKeyframes");
        return keys==null?new JSONArray():new JSONArray(keys.toString());
    }
    private void replace(JSONArray keys)throws Exception{
        StudioLayer candidate=StudioLayer.fromJson(draft.toJson().put("motionKeyframes",keys));
        StudioLayerMotion.fromLayer(candidate);draft=candidate;refreshKeys();
    }
    private void refreshKeys()throws Exception{
        JSONArray keys=keys();keyList.removeAllViews();status.setText(keys.length()+" / 64 draft keys · video lasts "+draft.durationMs+" ms");
        if(keys.length()==0){TextView empty=new TextView(context);empty.setText("No motion keys. Static placement is retained.");keyList.addView(empty);}
        for(int i=0;i<keys.length();i++){
            long at=keys.getJSONObject(i).getLong("timeMs");
            addButton(keyList,"Key at "+at+" ms",()->{try{load(at);}catch(Exception error){invalid(error);}});
        }
        StudioUi.prepareTouchLayout(content);
    }
    private void load(long at)throws Exception{
        StudioClipKeyframes.Values sampled=StudioLayerMotion.fromLayer(draft).atUs(Math.multiplyExact(at,1000L));
        double[] channels={sampled.x,sampled.y,sampled.scaleX,sampled.scaleY,sampled.rotate,sampled.opacity};
        String selectedEasing="linear";boolean exact=false;JSONArray keys=keys();
        for(int i=0;i<keys.length();i++)if(keys.getJSONObject(i).getLong("timeMs")==at){
            selectedEasing=keys.getJSONObject(i).optString("easing","linear");exact=true;break;
        }
        time.setText(Long.toString(at));time.setError(null);
        for(int i=0;i<values.length;i++)values[i].setText(Double.toString(channels[i]));
        easing.setSelection(Math.max(0,java.util.Arrays.asList(EASINGS).indexOf(selectedEasing)));
        selection.setText((exact?"Selected key at ":"Sampled motion at ")+at+" ms"+(exact?"":" · new key"));
    }
    private void upsert(){
        try{
            long at=enteredTime();JSONObject key=new JSONObject().put("timeMs",at).put("easing",easing.getSelectedItem().toString());
            for(int i=0;i<FIELDS.length;i++)key.put(FIELDS[i],Double.parseDouble(values[i].getText().toString().trim()));
            ArrayList<JSONObject> ordered=new ArrayList<>();JSONArray old=keys();
            for(int i=0;i<old.length();i++)if(old.getJSONObject(i).getLong("timeMs")!=at)ordered.add(old.getJSONObject(i));
            ordered.add(key);ordered.sort(Comparator.comparingLong(entry->entry.optLong("timeMs")));
            replace(new JSONArray(ordered));load(at);
        }catch(Exception error){invalid(error);}
    }
    private void remove(){
        try{
            long at=enteredTime();JSONArray old=keys(),next=new JSONArray();boolean found=false;
            for(int i=0;i<old.length();i++){JSONObject key=old.getJSONObject(i);if(key.getLong("timeMs")==at)found=true;else next.put(key);}
            if(!found)throw new IllegalArgumentException("No key at the entered video time");
            replace(next);load(at);
        }catch(Exception error){invalid(error);}
    }
    private long enteredTime(){
        try{long at=Long.parseLong(time.getText().toString().trim());if(at<0||at>draft.durationMs)throw new NumberFormatException();return at;}
        catch(NumberFormatException error){throw new IllegalArgumentException("Enter an integer video time in 0.."+draft.durationMs+" ms");}
    }
    private void invalid(Exception error){time.setError(error.getMessage());Toast.makeText(context,"Video motion not changed: "+error.getMessage(),Toast.LENGTH_LONG).show();}
    private boolean active(){return !closed&&(dialog==null||dialog.isShowing())&&!context.isFinishing()&&!context.isDestroyed();}
    private int dp(int amount){return StudioUi.dp(context,amount);}
    private LinearLayout column(){LinearLayout result=new LinearLayout(context);result.setOrientation(LinearLayout.VERTICAL);return result;}
    private TextView label(String text){TextView label=new TextView(context);label.setText(text);label.setPadding(0,dp(6),0,dp(4));content.addView(label);return label;}
    private EditText number(String description){EditText field=new EditText(context);field.setInputType(InputType.TYPE_CLASS_NUMBER|InputType.TYPE_NUMBER_FLAG_DECIMAL|InputType.TYPE_NUMBER_FLAG_SIGNED);field.setContentDescription(description);field.setMinHeight(dp(48));return field;}
    private void addButton(LinearLayout parent,String title,Runnable action){Button button=new Button(context);button.setText(title);StudioUi.styleButton(button,false);button.setOnClickListener(view->{if(active()&&!busy)action.run();});parent.addView(button,new LinearLayout.LayoutParams(-1,-2));}
}
