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
    private static final String PREFS="studio_export_choices";
    private static final String[] ASPECTS={"16:9","9:16","1:1","4:5"};
    private static final String[] QUALITIES={"720p","1080p"};
    private StudioExportDialog(){}

    public static AlertDialog show(Activity activity,Selection selected){
        if(activity==null||selected==null)throw new IllegalArgumentException("Export activity and selection callback are required");
        SharedPreferences preferences=activity.getSharedPreferences(PREFS,Context.MODE_PRIVATE);
        LinearLayout form=new LinearLayout(activity);form.setOrientation(LinearLayout.VERTICAL);form.setPadding(24,16,24,16);
        TextView help=new TextView(activity);help.setText("Choose the canvas shape and quality for a new MP4. Find completed exports in All Renders.");form.addView(help);
        Spinner aspect=choice(activity,form,"Canvas shape","Export aspect ratio","export_aspect",
                new String[]{"16:9 · landscape","9:16 · portrait","1:1 · square","4:5 · portrait"});
        Spinner quality=choice(activity,form,"Quality","Export quality","export_quality",
                new String[]{"720p · faster export","1080p · higher detail"});
        aspect.setSelection(index(ASPECTS,preferences.getString("aspect","16:9")));
        quality.setSelection(index(QUALITIES,preferences.getString("quality","720p")));
        ScrollView scroll=new ScrollView(activity);scroll.addView(form);
        return new AlertDialog.Builder(activity).setTitle("Export this timeline").setView(scroll)
                .setPositiveButton("Export",(dialog,which)->{
                    if(activity.isFinishing()||activity.isDestroyed())return;
                    String chosenAspect=ASPECTS[aspect.getSelectedItemPosition()],chosenQuality=QUALITIES[quality.getSelectedItemPosition()];
                    preferences.edit().putString("aspect",chosenAspect).putString("quality",chosenQuality).apply();
                    selected.selected(chosenAspect,chosenQuality);
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
