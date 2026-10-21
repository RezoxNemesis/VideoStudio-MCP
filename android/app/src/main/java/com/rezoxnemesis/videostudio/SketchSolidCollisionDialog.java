package com.rezoxnemesis.videostudio;

import android.app.*;
import android.text.InputType;
import android.widget.*;
import org.json.*;
import java.util.*;
import java.util.function.Consumer;

/** Human controls emit precisely the native operation available to an MCP caller. */
final class SketchSolidCollisionDialog {
    private SketchSolidCollisionDialog(){}
    static void show(Activity activity,JSONObject document,Consumer<JSONObject> create)throws Exception{
        JSONObject defaults=SketchSolidCollision.defaults();LinkedHashMap<String,EditText> controls=new LinkedHashMap<>();LinearLayout form=new LinearLayout(activity);form.setOrientation(LinearLayout.VERTICAL);form.setPadding(20,12,20,12);
        TextView status=new TextView(activity);status.setText("Native ball / box impact • "+document.getInt("frameCount")+" frames at "+document.getInt("fps")+" FPS. Creates a new editable layer; sketch timing stays unchanged. Uses one floor axis, constant stopping friction and a contact frame rounded to the sketch clock. Requires 48–240 frames. Sizes/positions use world pixels; speed is pixels/s, friction pixels/s², mass is relative and restitution ranges from 0 to 1.");form.addView(status);
        String[][] scalars={{"ballDiameter","Ball diameter"},{"startX","Ball start X"},{"boxX","Box start X"},{"speed","Approach speed"},{"ballMass","Ball mass"},{"boxMass","Box mass"},{"restitution","Restitution"},{"friction","Stopping friction"},{"squash","Impact squash"}};
        for(String[] field:scalars)add(activity,form,controls,field[0],field[1],defaults.getDouble(field[0]));
        for(int n=0;n<3;n++)add(activity,form,controls,"size"+n,new String[]{"Box width","Box height","Box depth"}[n],defaults.getJSONArray("boxSize").getDouble(n));
        for(String name:new String[]{"focal","distance","height","horizon"})add(activity,form,controls,"camera_"+name,"Camera "+name,defaults.getJSONObject("camera").getDouble(name));
        ScrollView scroll=new ScrollView(activity);scroll.addView(form);AlertDialog dialog=new AlertDialog.Builder(activity).setTitle("Ball and box impact").setView(scroll).setNegativeButton("Cancel",null).setPositiveButton("Construct scene",null).create();dialog.show();dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view->{try{
            JSONObject settings=new JSONObject();for(String[] field:scalars)settings.put(field[0],value(controls.get(field[0])));JSONArray size=new JSONArray();for(int n=0;n<3;n++)size.put(value(controls.get("size"+n)));settings.put("boxSize",size);JSONObject camera=new JSONObject();for(String name:new String[]{"focal","distance","height","horizon"})camera.put(name,value(controls.get("camera_"+name)));settings.put("camera",camera);
            JSONArray programs=SketchSolidCollision.programs(document,settings);for(int n=0;n<programs.length();n++)new SketchSolidOps.Model(document,programs.getJSONObject(n));
            String id="impact_"+UUID.randomUUID().toString().replace("-","").substring(0,24);create.accept(new JSONObject().put("operations",new JSONArray().put(new JSONObject().put("type","add_layer").put("layer",new JSONObject().put("id",id).put("name","Ball and box impact"))).put(new JSONObject().put("type","add_solid_collision").put("layerId",id).put("sceneId",id).put("settings",settings))));dialog.dismiss();
        }catch(Exception failure){status.setText("Scene not created: "+failure.getMessage());}});
    }
    private static void add(Activity activity,LinearLayout form,Map<String,EditText> controls,String key,String label,double initial){TextView title=new TextView(activity);title.setText(label);form.addView(title);EditText input=new EditText(activity);input.setContentDescription(label);input.setInputType(InputType.TYPE_CLASS_NUMBER|InputType.TYPE_NUMBER_FLAG_DECIMAL|InputType.TYPE_NUMBER_FLAG_SIGNED);input.setSingleLine(true);input.setText(String.valueOf(initial));controls.put(key,input);form.addView(input);}
    private static double value(EditText input){double number=Double.parseDouble(input.getText().toString());if(!Double.isFinite(number))throw new IllegalArgumentException("Use a finite number");return number;}
}
