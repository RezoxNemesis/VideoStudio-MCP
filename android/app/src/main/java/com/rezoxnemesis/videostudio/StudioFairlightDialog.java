package com.rezoxnemesis.videostudio;

import android.app.Activity;
import android.app.AlertDialog;
import android.text.InputType;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.function.Consumer;

/**
 * Native mobile Fairlight-style audio inspector; same authoritative guarded
 * edit command as the user's ChatGPT MCP. No external DAW or source rewrites.
 */
public final class StudioFairlightDialog {
    private StudioFairlightDialog(){}

    public static AlertDialog show(Activity context,ProjectStore store,
                                   ProjectStore.Project project,String targetLayerId,
                                   Consumer<ProjectStore.Project> onSaved){
        if(context==null||store==null||project==null||targetLayerId==null||onSaved==null)
            throw new IllegalArgumentException("Select a saved owned audio lane first");
        final String owner=project.id;final long revision=project.updatedAt;
        StudioLayer target=null;
        for(StudioLayer layer:project.studioLayers)
            if(targetLayerId.equals(layer.id)&&StudioLayer.AUDIO.equals(layer.type))target=layer;
        if(target==null)throw new IllegalArgumentException("Owned audio lane no longer exists");
        final StudioLayer existing=target;

        LinearLayout box=new LinearLayout(context);box.setOrientation(LinearLayout.VERTICAL);
        int inset=StudioUi.dp(context,16);box.setPadding(inset,inset,inset,inset);
        ScrollView scroll=new ScrollView(context);scroll.addView(box);
        TextView explanation=new TextView(context);
        explanation.setText("Native PCM Fairlight-style processing for this imported music or dialogue. "
                +"EQ, compressor, noise gate, high/low-pass and sample-peak limiting are applied "
                +"to the actual edited composition. Not true-peak mastering or LUFS certification.");
        box.addView(explanation);
        Spinner mode=new Spinner(context);
        mode.setContentDescription("Fairlight style");
        String[] styles={"Custom processor","Dialogue clarity","Music dynamics","Broadcast","Ambient","Neutral bypass"};
        mode.setAdapter(new ArrayAdapter<>(context,
                android.R.layout.simple_spinner_dropdown_item,styles));
        box.addView(mode);

        final String[] keys={"eqLowDb","eqMidDb","eqHighDb",
                "compressorThresholdDb","compressorRatio","attackMs","releaseMs",
                "makeupDb","noiseGateThresholdDb","highpassHz","lowpassHz",
                "limiterCeilingDb","outputDb"};
        final String[] descriptions={
                "Low EQ (dB -12..12)","Mid EQ (dB -12..12)","High EQ (dB -12..12)",
                "Compressor threshold (dB -60..0)","Compressor ratio (1..20)",
                "Attack (ms 1..150)","Release (ms 10..1500)",
                "Makeup gain (dB -12..24)","Gate threshold (dB -80..0; -80 off)",
                "Highpass (Hz 0..400; 0 off)","Lowpass (Hz 0 or 1000..20000)",
                "Sample-peak ceiling (dB -18..0)","Output gain (dB -24..12)"
        };
        final JSONObject defaults;
        try{defaults=StudioFairlightSettings.validate(existing.fairlightFx==null
                ?new JSONObject():existing.fairlightFx);}
        catch(Exception e){throw new IllegalArgumentException("Saved audio FX invalid",e);}
        EditText[] fields=new EditText[keys.length];
        for(int i=0;i<keys.length;i++){
            TextView label=new TextView(context);label.setText(descriptions[i]);
            label.setPadding(0,StudioUi.dp(context,6),0,0);
            box.addView(label);
            EditText input=new EditText(context);
            input.setContentDescription(descriptions[i]);
            input.setSingleLine(true);
            input.setInputType(InputType.TYPE_CLASS_NUMBER|
                    InputType.TYPE_NUMBER_FLAG_DECIMAL|InputType.TYPE_NUMBER_FLAG_SIGNED);
            input.setText(Double.toString(defaults.optDouble(keys[i])));
            input.setMinHeight(StudioUi.dp(context,48));
            fields[i]=input;box.addView(input);
        }
        CheckBox all=new CheckBox(context);
        all.setText("Apply to all imported AUDIO lanes (max 16)");
        box.addView(all);
        final AlertDialog dialog=new AlertDialog.Builder(context)
                .setTitle("Audio · Fairlight FX")
                .setView(scroll)
                .setPositiveButton("Apply audio",null)
                .setNeutralButton("Reset audio FX",null)
                .setNegativeButton("Cancel",null).create();

        dialog.setOnShowListener(v->{
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(button->{
                try{
                    JSONObject request=basicRequest(project,targetLayerId,revision,all.isChecked(),"apply");
                    if(mode.getSelectedItemPosition()>0){
                        request.put("preset",new String[]{"","dialogue","music",
                                "broadcast","ambient","neutral"}[mode.getSelectedItemPosition()]);
                    }else{
                        JSONObject fx=new JSONObject();
                        for(int i=0;i<keys.length;i++)
                            fx.put(keys[i],Double.parseDouble(fields[i].getText().toString().trim()));
                        request.put("fx",StudioFairlightSettings.validate(fx));
                    }
                    save(context,store,request,owner,onSaved);
                    dialog.dismiss();
                }catch(Exception error){new AlertDialog.Builder(context)
                        .setTitle("Audio unchanged").setMessage(error.getMessage())
                        .setPositiveButton("OK",null).show();}
            });
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(button->{
                try{
                    JSONObject request=basicRequest(project,targetLayerId,revision,all.isChecked(),"reset");
                    save(context,store,request,owner,onSaved);dialog.dismiss();
                }catch(Exception error){new AlertDialog.Builder(context)
                        .setTitle("Audio reset not completed").setMessage(error.getMessage())
                        .setPositiveButton("OK",null).show();}
            });
        });
        dialog.show();
        StudioUi.prepareTouchLayout(box);
        return dialog;
    }

    private static JSONObject basicRequest(ProjectStore.Project project,String target,
                                           long revision,boolean all,String operation)throws Exception{
        JSONArray ids=new JSONArray();
        for(StudioLayer audio:project.studioLayers){
            if(!StudioLayer.AUDIO.equals(audio.type))continue;
            if(all||target.equals(audio.id))ids.put(audio.id);
        }
        if(ids.length()<1||ids.length()>16)
            throw new IllegalArgumentException("Select 1..16 owned audio lanes");
        return new JSONObject().put("projectId",project.id)
                .put("expectedUpdatedAt",revision).put("operation",operation)
                .put("layerIds",ids);
    }

    private static void save(Activity context,ProjectStore store,JSONObject request,String owner,
                             Consumer<ProjectStore.Project> onSaved)throws Exception{
        if(context.isFinishing()||context.isDestroyed())
            throw new IllegalStateException("Audio dialog is closed");
        StudioFairlightEdits.plan(store.get(owner),request);
        JSONObject receipt=StudioFairlightEdits.apply(store,request);
        ProjectStore.Project saved=store.get(receipt.getString("projectId"));
        if(saved==null)throw new IllegalStateException("Saved audio state unavailable");
        onSaved.accept(saved);
    }
}
