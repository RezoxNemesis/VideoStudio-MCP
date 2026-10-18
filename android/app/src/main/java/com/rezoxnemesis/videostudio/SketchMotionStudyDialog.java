package com.rezoxnemesis.videostudio;

import android.app.*;
import android.text.InputType;
import android.widget.*;
import org.json.*;
import java.util.UUID;
import java.util.function.Consumer;

/** Manual controls issue the same bounded native request as MCP. */
final class SketchMotionStudyDialog {
    static void show(Activity activity,JSONObject document,Consumer<JSONObject> create)throws Exception {
        LinearLayout form=new LinearLayout(activity);form.setOrientation(LinearLayout.VERTICAL);form.setPadding(20,12,20,12);
        TextView status=new TextView(activity);status.setText("Add an original editable motion study on a new layer. Choose a sketch lasting 4–8 seconds, up to 240 frames. Current timing: "+document.getInt("frameCount")+" frames at "+document.getInt("fps")+" FPS.");form.addView(status);
        Spinner study=new Spinner(activity);study.setContentDescription("Motion study");study.setAdapter(new ArrayAdapter<>(activity,android.R.layout.simple_spinner_dropdown_item,new String[]{"Cartoon jump / squash and stretch","Character / walk to run"}));form.addView(study);
        EditText travel=input(activity,form,"Travel (% width)","18"),lift=input(activity,form,"Jump height (% height, cartoon only)","24");
        ScrollView scroll=new ScrollView(activity);scroll.addView(form);
        AlertDialog dialog=new AlertDialog.Builder(activity).setTitle("Motion studies").setView(scroll).setNegativeButton("Cancel",null).setPositiveButton("Create study",null).create();dialog.show();
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view->{try{
            JSONObject settings=new JSONObject().put("travel",Double.parseDouble(travel.getText().toString())/100)
                    .put("lift",Double.parseDouble(lift.getText().toString())/100);
            SketchDocument.number(settings,"travel",0,.3);SketchDocument.number(settings,"lift",.02,.3);
            double seconds=document.getInt("frameCount")/(double)document.getInt("fps");
            if(seconds<4||seconds>8||document.getInt("frameCount")>240)throw SketchDocument.bad("Use a 4–8 second sketch with at most 240 frames");
            String id="motion_"+UUID.randomUUID().toString().replace("-","").substring(0,24);
            create.accept(new JSONObject().put("operations",new JSONArray()
                    .put(new JSONObject().put("type","add_layer").put("layer",new JSONObject().put("id",id).put("name","Motion study")))
                    .put(new JSONObject().put("type","add_motion_study").put("layerId",id).put("sceneId",id)
                            .put("study",study.getSelectedItemPosition()==0?"cartoon_jump":"walk_to_run").put("settings",settings))));
            dialog.dismiss();
        }catch(Exception failure){status.setText("Study not created: "+failure.getMessage());}});
    }
    private static EditText input(Activity activity,LinearLayout form,String label,String value){
        TextView title=new TextView(activity);title.setText(label);form.addView(title);
        EditText input=new EditText(activity);input.setContentDescription(label);input.setSingleLine(true);
        input.setInputType(InputType.TYPE_CLASS_NUMBER|InputType.TYPE_NUMBER_FLAG_DECIMAL|InputType.TYPE_NUMBER_FLAG_SIGNED);input.setText(value);form.addView(input);return input;
    }
}
