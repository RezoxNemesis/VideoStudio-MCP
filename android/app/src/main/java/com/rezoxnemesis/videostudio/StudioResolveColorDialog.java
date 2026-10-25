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
import android.widget.CheckBox;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.function.Consumer;

/**
 * Original mobile Color page. Does not hide the existing Adjust panel.
 * Exactly one guarded edit for selected clip or a bounded multi-clip grade.
 * An optional exact node JSON field allows the same program used by ChatGPT
 * to be human-edited without requiring a desktop keyboard or Gallery import.
 */
public final class StudioResolveColorDialog {
    private StudioResolveColorDialog(){}

    public static AlertDialog show(Activity context,ProjectStore store,
                                   ProjectStore.Project project,String selectedClipId,
                                   Consumer<ProjectStore.Project> onSaved) {
        if(context==null||store==null||project==null||selectedClipId==null
                ||onSaved==null)
            throw new IllegalArgumentException("A selected VideoStudio project and clip are required");
        final String owner=project.id;
        final long revision=project.updatedAt;
        ScrollView scroll=new ScrollView(context);
        LinearLayout box=new LinearLayout(context);box.setOrientation(LinearLayout.VERTICAL);
        int padding=StudioUi.dp(context,16);box.setPadding(padding,padding,padding,padding);
        scroll.addView(box);

        TextView subtitle=new TextView(context);
        subtitle.setText("Native serial color grades. One 17³ GPU LUT is used by edited preview and MP4 export. The original imported media is not changed.");
        box.addView(subtitle);
        final String[] presets={"Custom primary grade","Balanced","Cinematic","Noir","Golden hour","Cool night"};
        Spinner preset=new Spinner(context);
        preset.setContentDescription("Color-grade preset");
        ArrayAdapter<String> menu=new ArrayAdapter<>(context,
                android.R.layout.simple_spinner_dropdown_item,presets);
        preset.setAdapter(menu);box.addView(preset);

        String[] channels={"Exposure (stops -4..4)","Contrast (.25..3)",
                "Pivot (0..1)","Saturation (0..3)","Temperature (-1..1)",
                "Tint (-1..1)"};
        double[] defaults={0,1,.5,1,0,0};
        EditText[] values=new EditText[channels.length];
        for(int i=0;i<channels.length;i++){
            TextView label=new TextView(context);label.setText(channels[i]);box.addView(label);
            EditText field=new EditText(context);
            field.setSingleLine(true);
            field.setInputType(InputType.TYPE_CLASS_NUMBER|
                    InputType.TYPE_NUMBER_FLAG_DECIMAL|InputType.TYPE_NUMBER_FLAG_SIGNED);
            field.setContentDescription(channels[i]);
            field.setText(Double.toString(defaults[i]));
            field.setMinHeight(StudioUi.dp(context,48));
            values[i]=field;box.addView(field);
        }
        CheckBox filmic=new CheckBox(context);
        filmic.setText("Add filmic tone rolloff");
        box.addView(filmic);

        CheckBox applyAll=new CheckBox(context);
        applyAll.setText("Apply to all timeline clips (up to 64)");
        box.addView(applyAll);
        CheckBox advanced=new CheckBox(context);
        advanced.setText("Advanced: ordered node JSON (manual / ChatGPT)");
        box.addView(advanced);
        TextView detail=new TextView(context);
        detail.setText("Optional nodes: primaries, wheels, curve, hue_qualifier and filmic. "
                +"A strict 8-node schema protects the renderer. Override the quick controls only when needed.");
        box.addView(detail);
        EditText json=new EditText(context);
        json.setContentDescription("Original DaVinci-inspired serial color node program");
        json.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        json.setMinLines(5);json.setMaxLines(10);
        json.setText("[{\"type\":\"primaries\",\"exposure\":0,\"contrast\":1,\"saturation\":1}]");
        json.setVisibility(android.view.View.GONE);
        detail.setVisibility(android.view.View.GONE);
        box.addView(json);
        advanced.setOnCheckedChangeListener((button,checked)->{
            json.setVisibility(checked?android.view.View.VISIBLE:android.view.View.GONE);
            detail.setVisibility(checked?android.view.View.VISIBLE:android.view.View.GONE);
        });

        final AlertDialog dialogue=new AlertDialog.Builder(context)
                .setTitle("Color · native GPU grade")
                .setView(scroll)
                .setPositiveButton("Apply grade",null)
                .setNeutralButton("Reset native grade",null)
                .setNegativeButton("Cancel",null).create();

        dialogue.setOnShowListener(ignored->{
            Button apply=dialogue.getButton(AlertDialog.BUTTON_POSITIVE);
            Button reset=dialogue.getButton(AlertDialog.BUTTON_NEUTRAL);
            apply.setOnClickListener(v->{
                try{
                    if(context.isFinishing()||context.isDestroyed())
                        throw new IllegalStateException("Color workspace closed");
                    JSONArray targets=targets(project,selectedClipId,applyAll.isChecked());
                    JSONObject request=new JSONObject()
                            .put("projectId",owner).put("expectedUpdatedAt",revision)
                            .put("operation","grade").put("clipIds",targets);
                    if(advanced.isChecked()){
                        JSONArray custom=new JSONArray(json.getText().toString().trim());
                        request.put("nodes",StudioResolveColorGraph.validate(custom));
                    }else if(preset.getSelectedItemPosition()>0){
                        request.put("preset",new String[]{
                                "","balanced","cinematic","noir","golden_hour","cool_night"
                        }[preset.getSelectedItemPosition()]);
                    }else{
                        JSONObject primary=new JSONObject().put("type","primaries");
                        String[] keys={"exposure","contrast","pivot","saturation","temperature","tint"};
                        for(int i=0;i<keys.length;i++)primary.put(keys[i],
                                Double.parseDouble(values[i].getText().toString().trim()));
                        JSONArray nodes=new JSONArray().put(primary);
                        if(filmic.isChecked())nodes.put(new JSONObject().put("type","filmic")
                                .put("toe",.07).put("shoulder",.18));
                        request.put("nodes",StudioResolveColorGraph.validate(nodes));
                    }
                    save(store,request,owner,onSaved);
                    dialogue.dismiss();
                }catch(Exception error){
                    new AlertDialog.Builder(context).setTitle("Grade unchanged")
                            .setMessage(error.getMessage()==null?"Invalid source or color nodes":error.getMessage())
                            .setPositiveButton("OK",null).show();
                }
            });
            reset.setOnClickListener(v->{
                try{
                    JSONObject request=new JSONObject()
                            .put("projectId",owner).put("expectedUpdatedAt",revision)
                            .put("operation","reset").put("clipIds",targets(project,selectedClipId,applyAll.isChecked()));
                    save(store,request,owner,onSaved);
                    dialogue.dismiss();
                }catch(Exception error){
                    new AlertDialog.Builder(context).setTitle("Reset not completed")
                            .setMessage(error.getMessage()).setPositiveButton("OK",null).show();
                }
            });
        });
        dialogue.show();
        StudioUi.prepareTouchLayout(box);
        return dialogue;
    }

    private static JSONArray targets(ProjectStore.Project project,String selected,boolean all){
        JSONArray ids=new JSONArray();boolean found=false;
        if(all){
            if(project.clips.size()>64)
                throw new IllegalArgumentException("Choose a maximum 64 clips for one native color edit");
            for(ProjectStore.Clip clip:project.clips)ids.put(clip.id);
        }else for(ProjectStore.Clip clip:project.clips)if(selected.equals(clip.id)){
            ids.put(clip.id);found=true;break;
        }
        if(ids.length()==0||(!all&&!found))
            throw new IllegalArgumentException("Selected clip is no longer in this project");
        return ids;
    }
    private static void save(ProjectStore store,JSONObject request,String owner,
                             Consumer<ProjectStore.Project> onSaved)throws Exception{
        StudioResolveColorEdits.plan(store.get(owner),request);
        JSONObject receipt=StudioResolveColorEdits.apply(store,request);
        ProjectStore.Project current=store.get(receipt.getString("projectId"));
        if(current==null)throw new IllegalStateException("Edited color project not saved");
        onSaved.accept(current);
    }
}
