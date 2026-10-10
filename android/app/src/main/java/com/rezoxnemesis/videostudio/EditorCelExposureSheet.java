package com.rezoxnemesis.videostudio;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Color;
import android.text.InputType;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.Locale;

/** Frame-aligned cel holds and duplicates over the same shared graph used by rendering and MCP. */
final class EditorCelExposureSheet {
    interface Listener {
        boolean onCommit(String projectId,long revision,String operation,JSONObject settings);
        void onSeek(String clipId,long positionMs);
        void onRedraw(String clipId);
        void onNew(String clipId);
    }
    private EditorCelExposureSheet() {}
    static void show(Activity activity,ProjectStore.Project snapshot,Listener listener){
        ProjectStore.Project project=ProjectStore.copy(snapshot);
        ArrayList<ProjectStore.Clip> exposures=new ArrayList<>();for(ProjectStore.Clip clip:project.clips)if(AnimationCelEdits.isCel(project.asset(clip.assetId)))exposures.add(clip);
        exposures.sort(Comparator.comparingLong((ProjectStore.Clip clip)->clip.startMs).thenComparing(clip->clip.trackId));
        LinearLayout box=column(activity);box.setPadding(dp(activity,16),dp(activity,8),dp(activity,16),dp(activity,12));
        addText(activity,box,"Output "+(project.animationFrameRate==0?30:project.animationFrameRate)+" fps · r"+project.revision+". Holds use cumulative integer frame boundaries. Other timeline tools can move an exposure away from its original frame grid.");
        if(exposures.isEmpty())addText(activity,box,"No drawn cels yet. Use Draw new cel to create one.");
        int shown=Math.min(256,exposures.size());
        for(int index=0;index<shown;index++){
            ProjectStore.Clip clip=exposures.get(index);ProjectStore.Asset asset=project.asset(clip.assetId);ProjectStore.Track track=project.track(clip.trackId);JSONObject timing=clip.effects.optJSONObject("celExposure");
            String frames=timing==null?"no frame metadata":timing.optLong("startFrame")+" + "+timing.optLong("frameCount")+"f @ "+timing.optInt("fpsNumerator",24)+"fps";
            boolean aligned=AnimationCelEdits.matchesFrameGrid(clip);
            Button row=new Button(activity);row.setAllCaps(false);row.setTextSize(11);row.setText(asset.name+" · "+(track==null?clip.trackId:track.name)+"\n"+frames+" · "+String.format(Locale.US,"%.3f–%.3fs",clip.startMs/1000d,clip.endMs()/1000d)+(aligned?"":" · off grid"));
            row.setOnClickListener(view->{String[] actions={"Seek this exposure","Redraw this cel","Draw next cel","Change hold frames","Duplicate exposure","Delete exposure"};new AlertDialog.Builder(activity).setTitle(asset.name).setItems(actions,(d,action)->{
                if(action==0)listener.onSeek(clip.id,clip.startMs);
                else if(action==1)listener.onRedraw(clip.id);
                else if(action==2)listener.onNew(clip.id);
                else if(action==3)timingDialog(activity,project,clip,false,listener);
                else if(action==4)timingDialog(activity,project,clip,true,listener);
                else {LinearLayout confirm=column(activity);addText(activity,confirm,"Removes this exposure from the timeline. Its image source remains in the project and undo history.");CheckBox ripple=new CheckBox(activity);ripple.setText("Ripple later clips on this track");ripple.setTextColor(Color.WHITE);confirm.addView(ripple);new AlertDialog.Builder(activity).setTitle("Delete cel exposure?").setView(confirm).setPositiveButton("Delete",(dialog,w)->listener.onCommit(project.id,project.revision,"cel_exposure_delete",json("clipId",clip.id,"ripple",ripple.isChecked()))).setNegativeButton("Cancel",null).show();}
            }).setNegativeButton("Close",null).show();});box.addView(row,new LinearLayout.LayoutParams(-1,-2));
        }
        if(exposures.size()>shown)addText(activity,box,(exposures.size()-shown)+" more exposures are outside this bounded sheet; select them in the timeline.");
        ScrollView scroll=new ScrollView(activity);scroll.addView(box);new AlertDialog.Builder(activity).setTitle("Cel exposure sheet").setView(scroll).setNegativeButton("Close",null).show();
    }
    private static void timingDialog(Activity activity,ProjectStore.Project project,ProjectStore.Clip clip,boolean duplicate,Listener listener){
        if(!AnimationCelEdits.matchesFrameGrid(clip)){Toast.makeText(activity,"This cel was changed off its original frame grid. Use shared timeline timing controls or draw a new frame-aligned cel.",Toast.LENGTH_LONG).show();return;}
        JSONObject timing=clip.effects.optJSONObject("celExposure");long suggestion=duplicate?timing.optLong("startFrame")+timing.optLong("frameCount"):timing.optLong("frameCount");
        LinearLayout box=column(activity);box.setPadding(dp(activity,18),dp(activity,8),dp(activity,18),dp(activity,8));
        addText(activity,box,duplicate?"Start frame on this exposure's existing origin and cadence":"Hold frame count at this exposure's existing cadence");
        EditText value=new EditText(activity);value.setSingleLine();value.setInputType(InputType.TYPE_CLASS_NUMBER);value.setText(Long.toString(suggestion));box.addView(value);
        CheckBox ripple=new CheckBox(activity);ripple.setText("Ripple later clips on this track");ripple.setChecked(true);ripple.setTextColor(Color.WHITE);box.addView(ripple);
        AlertDialog dialog=new AlertDialog.Builder(activity).setTitle(duplicate?"Duplicate cel exposure":"Change hold").setView(box).setPositiveButton("Apply",null).setNegativeButton("Cancel",null).create();
        dialog.setOnShowListener(shown->dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view->{try{long number=Long.parseLong(value.getText().toString());if(number<(duplicate?0:1)||number>10000000)throw new IllegalArgumentException("Enter a whole frame count within the supported range");JSONObject args=json("clipId",clip.id,duplicate?"startFrame":"frameCount",number,"ripple",ripple.isChecked());
            ProjectStore.Project candidate=ProjectStore.copy(project);AnimationCelEdits.apply(candidate,duplicate?"cel_exposure_duplicate":"cel_exposure_hold",args);if(listener.onCommit(project.id,project.revision,duplicate?"cel_exposure_duplicate":"cel_exposure_hold",args))dialog.dismiss();
        }catch(Exception error){Toast.makeText(activity,error.getMessage(),Toast.LENGTH_LONG).show();}}));dialog.show();
    }
    private static JSONObject json(Object... values){JSONObject result=new JSONObject();try{for(int i=0;i<values.length;i+=2)result.put(String.valueOf(values[i]),values[i+1]);}catch(Exception error){throw new IllegalArgumentException(error);}return result;}
    private static LinearLayout column(Activity activity){LinearLayout box=new LinearLayout(activity);box.setOrientation(LinearLayout.VERTICAL);return box;}
    private static void addText(Activity activity,LinearLayout box,String message){TextView text=new TextView(activity);text.setText(message);text.setTextColor(Color.rgb(175,190,215));text.setTextSize(11);text.setPadding(0,dp(activity,4),0,dp(activity,4));box.addView(text);}
    private static int dp(Activity activity,float value){return Math.round(value*activity.getResources().getDisplayMetrics().density);}
}
