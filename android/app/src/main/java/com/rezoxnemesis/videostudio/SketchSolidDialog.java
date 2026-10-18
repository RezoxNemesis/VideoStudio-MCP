package com.rezoxnemesis.videostudio;
import android.app.*;
import android.text.InputType;
import android.widget.*;
import org.json.*;
import java.util.*;
import java.util.function.Consumer;

/** Manual parameter/pose controls for precisely the same add_solid operation exposed to MCP. */
final class SketchSolidDialog {
    private final Activity activity;private final Consumer<JSONObject> create;private JSONObject program;private final int originalFrames,originalFps;
    private final LinkedHashMap<String,EditText> fields=new LinkedHashMap<>();private TextView status;
    static void show(Activity a,JSONObject document,Consumer<JSONObject> create)throws Exception{new SketchSolidDialog(a,document,create).show();}
    private SketchSolidDialog(Activity a,JSONObject document,Consumer<JSONObject> create)throws Exception{activity=a;this.create=create;originalFrames=document.getInt("frameCount");originalFps=document.getInt("fps");program=SketchSolidPrograms.turn(document.getInt("frameCount"));fields.put("Animation frames",input("Animation frames",document.getInt("frameCount")));fields.put("Animation FPS",input("Animation FPS",document.getInt("fps")));}
    private void show()throws Exception {
        LinearLayout form=column();status=new TextView(activity);status.setText("Shared corners keep faces joined during rotation. Creates a new editable layer. Animation frames and FPS affect this whole sketch; changing duration preserves absolute pose frames.");form.addView(status);
        for(Map.Entry<String,EditText> entry:fields.entrySet())labelled(form,entry.getKey(),entry.getValue());
        String[] dimensions={"Solid width","Solid height","Solid depth"};for(int n=0;n<3;n++)add(form,dimensions[n],program.getJSONArray("size").getDouble(n));
        for(String camera:new String[]{"focal","distance","height","horizon"})add(form,"Camera "+camera,program.getJSONObject("camera").getDouble(camera));add(form,"Outline pixels",program.getDouble("strokePx"));
        CheckBox ground=new CheckBox(activity);ground.setText("Floor guides and contact shadow");ground.setChecked(true);form.addView(ground);
        Button poses=new Button(activity);poses.setText("Pose keys");form.addView(poses);poses.setOnClickListener(v->poseList());
        Button study=new Button(activity);study.setText("Load bounce study");form.addView(study);study.setOnClickListener(v->{try{program=SketchSolidPrograms.bounce(Integer.parseInt(fields.get("Animation frames").getText().toString()));status.setText("Original three-hop study loaded. Edit its pose keys, dimensions and camera before constructing.");}catch(Exception e){error(e);}});
        ScrollView scroll=new ScrollView(activity);scroll.addView(form);AlertDialog dialog=new AlertDialog.Builder(activity).setTitle("Solid construction").setView(scroll).setNegativeButton("Cancel",null).setPositiveButton("Construct editable cels",null).create();dialog.show();dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{try{
            int frames=Integer.parseInt(fields.get("Animation frames").getText().toString()),fps=Integer.parseInt(fields.get("Animation FPS").getText().toString());if(frames<1||frames>1200)throw SketchDocument.bad("Use 1..1200 frames per shot");NativeFrameClock.requireFps(fps);
            JSONArray size=new JSONArray();for(String name:dimensions)size.put(value(name));program.put("size",size).put("strokePx",value("Outline pixels")).put("ground",ground.isChecked());JSONObject camera=new JSONObject();for(String name:new String[]{"focal","distance","height","horizon"})camera.put(name,value("Camera "+name));program.put("camera",camera);
            String id="solid_"+UUID.randomUUID().toString().replace("-","");JSONArray operations=new JSONArray();if(frames!=originalFrames||fps!=originalFps)operations.put(new JSONObject().put("type","set_timing").put("frameCount",frames).put("fps",fps));operations.put(new JSONObject().put("type","add_layer").put("layer",new JSONObject().put("id",id).put("name","Solid construction"))).put(new JSONObject().put("type","add_solid").put("layerId",id).put("solidId",id).put("program",SketchDocument.copy(program)));
            new SketchSolidOps.Model(new JSONObject().put("frameCount",frames),program);create.accept(new JSONObject().put("operations",operations));dialog.dismiss();
        }catch(Exception e){error(e);}});
    }
    private void poseList(){try{JSONArray keys=program.getJSONArray("keys");String[] labels=new String[keys.length()+1];for(int n=0;n<keys.length();n++)labels[n]="Pose "+(n+1)+" · frame "+(keys.getJSONObject(n).getInt("frame")+1);labels[keys.length()]="Add pose";new AlertDialog.Builder(activity).setTitle("Solid pose keys").setItems(labels,(d,index)->{try{JSONObject key=index<keys.length()?SketchDocument.copy(keys.getJSONObject(index)):new JSONObject().put("frame",Math.min(1199,keys.getJSONObject(keys.length()-1).getInt("frame")+1)).put("x",0).put("elevation",0).put("depth",0);poseEditor(key,index);}catch(Exception e){error(e);}}).show();}catch(Exception e){error(e);}}
    private void poseEditor(JSONObject key,int index)throws Exception {
        LinearLayout form=column();LinkedHashMap<String,EditText> inputs=new LinkedHashMap<>();EditText frame=input("Pose frame",key.getInt("frame")+1);labelled(form,"Frame (1 based)",frame);
        for(String name:new String[]{"x","elevation","depth","pitch","yaw","roll","scaleX","scaleY","scaleZ"}){EditText value=input("Pose "+name,key.optDouble(name,name.startsWith("scale")?1:0));inputs.put(name,value);labelled(form,name,value);}
        String[] modes={"linear","hold","smooth","ease_in","ease_out","ease_in_out"};Spinner interpolation=new Spinner(activity),vertical=new Spinner(activity),depth=new Spinner(activity);interpolation.setContentDescription("Pose timing");vertical.setContentDescription("Elevation timing");depth.setContentDescription("Depth timing");for(Spinner spinner:new Spinner[]{interpolation,vertical,depth})spinner.setAdapter(new ArrayAdapter<>(activity,android.R.layout.simple_spinner_dropdown_item,modes));interpolation.setSelection(Math.max(0,Arrays.asList(modes).indexOf(key.optString("interpolation","linear"))));vertical.setSelection(Math.max(0,Arrays.asList(modes).indexOf(key.optString("elevationInterpolation",key.optString("interpolation","linear")))));depth.setSelection(Math.max(0,Arrays.asList(modes).indexOf(key.optString("depthInterpolation",key.optString("interpolation","linear")))));labelled(form,"Depth timing",depth);labelled(form,"Pose timing",interpolation);labelled(form,"Elevation timing",vertical);
        ScrollView scroll=new ScrollView(activity);scroll.addView(form);AlertDialog dialog=new AlertDialog.Builder(activity).setTitle("Edit solid pose").setView(scroll).setNegativeButton("Cancel",null).setNeutralButton("Remove pose",(d,w)->{try{JSONArray next=new JSONArray();for(int n=0;n<program.getJSONArray("keys").length();n++)if(n!=index)next.put(program.getJSONArray("keys").getJSONObject(n));if(next.length()==0)throw SketchDocument.bad("Keep at least one pose");program.put("keys",next);}catch(Exception e){error(e);}}).setPositiveButton("Save pose",null).create();dialog.show();dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{try{
            JSONObject replacement=new JSONObject().put("frame",Integer.parseInt(frame.getText().toString())-1);for(Map.Entry<String,EditText> entry:inputs.entrySet())replacement.put(entry.getKey(),Double.parseDouble(entry.getValue().getText().toString()));replacement.put("interpolation",modes[interpolation.getSelectedItemPosition()]).put("elevationInterpolation",modes[vertical.getSelectedItemPosition()]).put("depthInterpolation",modes[depth.getSelectedItemPosition()]);
            ArrayList<JSONObject> keys=new ArrayList<>();for(int n=0;n<program.getJSONArray("keys").length();n++)if(n!=index)keys.add(program.getJSONArray("keys").getJSONObject(n));keys.add(replacement);keys.sort(Comparator.comparingInt(k->k.optInt("frame")));program.put("keys",new JSONArray(keys));dialog.dismiss();status.setText(keys.size()+" authored pose keys. Rotation values are unwrapped degrees; elevation is above the ground plane.");
        }catch(Exception e){error(e);}});
    }
    private LinearLayout column(){LinearLayout form=new LinearLayout(activity);form.setOrientation(1);form.setPadding(16,8,16,8);return form;}
    private void labelled(LinearLayout form,String name,android.view.View field){TextView label=new TextView(activity);label.setText(name);form.addView(label);form.addView(field);}
    private EditText input(String name,double value){EditText f=new EditText(activity);f.setContentDescription(name);f.setHint(name);f.setText(String.valueOf(value));f.setInputType(InputType.TYPE_CLASS_NUMBER|InputType.TYPE_NUMBER_FLAG_DECIMAL|InputType.TYPE_NUMBER_FLAG_SIGNED);return f;}
    private EditText input(String name,int value){EditText f=input(name,(double)value);f.setText(String.valueOf(value));return f;}
    private void add(LinearLayout form,String name,double value){EditText input=input(name,value);fields.put(name,input);TextView label=new TextView(activity);label.setText(name);form.addView(label);form.addView(input);}
    private double value(String name){return Double.parseDouble(fields.get(name).getText().toString());}
    private void error(Exception e){Toast.makeText(activity,e.getMessage(),Toast.LENGTH_LONG).show();}
}
