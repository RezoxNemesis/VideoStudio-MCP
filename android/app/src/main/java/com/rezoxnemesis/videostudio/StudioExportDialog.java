package com.rezoxnemesis.videostudio;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.widget.ArrayAdapter;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;

/** Manual export choices only; the caller owns timeline validation and native job submission. */
public final class StudioExportDialog {
    public interface Selection {void selected(String aspect,String quality);}
    public interface OptionsSelection {void selected(String aspect,String quality,Long maxOutputSampleDelayMs);}
    private static final String PREFS="studio_export_choices";
    private static final String[] ASPECTS={"16:9","9:16","1:1","4:5"};
    private static final String[] QUALITIES={"720p","1080p"};
    private StudioExportDialog(){}

    public static AlertDialog show(Activity activity,Selection selected){
        if(activity==null||selected==null)throw new IllegalArgumentException("Export activity and selection callback are required");
        return build(activity,(aspect,quality,wait)->selected.selected(aspect,quality),false);
    }

    /** Explicit per-export options; the legacy two-argument chooser remains unchanged. */
    public static AlertDialog showOptions(Activity activity,OptionsSelection selected){
        if(activity==null||selected==null)throw new IllegalArgumentException("Export activity and selection callback are required");
        return build(activity,selected,true);
    }

    private static AlertDialog build(Activity activity,OptionsSelection selected,boolean includeWait){
        SharedPreferences preferences=activity.getSharedPreferences(PREFS,Context.MODE_PRIVATE);
        LinearLayout form=new LinearLayout(activity);form.setOrientation(LinearLayout.VERTICAL);form.setPadding(24,16,24,16);
        TextView help=new TextView(activity);help.setText("Choose the canvas shape and quality for a new MP4. Find completed exports in All Renders.");form.addView(help);
        Spinner aspect=choice(activity,form,"Canvas shape","Export aspect ratio","export_aspect",
                new String[]{"16:9 · landscape","9:16 · portrait","1:1 · square","4:5 · portrait"});
        Spinner quality=choice(activity,form,"Quality","Export quality","export_quality",
                new String[]{"720p · faster export","1080p · higher detail"});
        aspect.setSelection(index(ASPECTS,preferences.getString("aspect","16:9")));
        quality.setSelection(index(QUALITIES,preferences.getString("quality","720p")));
        final Spinner wait;
        if(includeWait){
            wait=choice(activity,form,"Allow slow decoding","Maximum wait for a slow export","export_output_sample_delay",
                    new String[]{"Default · standard limit","60 seconds · more time","120 seconds · more time"});
            long remembered=0;
            try{remembered=preferences.getLong(NativeExportOptions.FIELD,0);}catch(ClassCastException ignored){}
            wait.setSelection(remembered==60000?1:remembered==120000?2:0);
            TextView waitHelp=new TextView(activity);waitHelp.setText("Allow extra time for a slow export. Cancel remains available.");form.addView(waitHelp);
        }else wait=null;
        ScrollView scroll=new ScrollView(activity);scroll.addView(form);
        return new AlertDialog.Builder(activity).setTitle("Export this timeline").setView(scroll)
                .setPositiveButton("Export",(dialog,which)->{
                    if(activity.isFinishing()||activity.isDestroyed())return;
                    String chosenAspect=ASPECTS[aspect.getSelectedItemPosition()],chosenQuality=QUALITIES[quality.getSelectedItemPosition()];
                    Long chosenWait=wait==null||wait.getSelectedItemPosition()==0?null:wait.getSelectedItemPosition()==1?60000L:120000L;
                    SharedPreferences.Editor confirmed=preferences.edit().putString("aspect",chosenAspect).putString("quality",chosenQuality);
                    if(includeWait){if(chosenWait==null)confirmed.remove(NativeExportOptions.FIELD);else confirmed.putLong(NativeExportOptions.FIELD,chosenWait);}
                    confirmed.apply();
                    selected.selected(chosenAspect,chosenQuality,chosenWait);
                }).setNegativeButton("Cancel",null).show();
    }

    private static Spinner choice(Activity activity,LinearLayout form,String label,String description,String tag,String[] labels){
        TextView title=new TextView(activity);title.setText(label);title.setPadding(0,16,0,4);form.addView(title);
        Spinner spinner=new Spinner(activity);spinner.setTag(tag);spinner.setContentDescription(description);
        ArrayAdapter<String> adapter=new ArrayAdapter<>(activity,android.R.layout.simple_spinner_item,labels);adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinner.setAdapter(adapter);form.addView(spinner);return spinner;
    }
    private static int index(String[] values,String value){for(int i=0;i<values.length;i++)if(values[i].equals(value))return i;return 0;}
}
