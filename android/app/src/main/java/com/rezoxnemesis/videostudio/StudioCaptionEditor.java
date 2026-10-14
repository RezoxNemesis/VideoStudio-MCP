package com.rezoxnemesis.videostudio;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Color;
import android.text.InputType;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import java.math.BigDecimal;
import java.util.Locale;
import java.util.function.Predicate;
import org.json.JSONObject;

/** Caption draft form. The caller's guarded transaction must accept before the dialog closes. */
public final class StudioCaptionEditor {
    private StudioCaptionEditor(){}

    public static AlertDialog show(Activity activity,StudioLayer initial,long timelineDurationMs,
            Predicate<StudioLayer> onApply){
        if(activity==null||initial==null||onApply==null||!StudioLayer.TITLE.equals(initial.type))
            throw new IllegalArgumentException("Caption editor needs an activity, title and save action");
        if(timelineDurationMs<100)throw new IllegalArgumentException("Add at least 0.100 seconds to the timeline first");
        // A new caption has blank text: capture its draft without invoking copy()/validation.
        final JSONObject baseline=initial.toJson();
        LinearLayout form=new LinearLayout(activity);form.setOrientation(LinearLayout.VERTICAL);
        int padding=(int)(20*activity.getResources().getDisplayMetrics().density+.5f);
        form.setPadding(padding,padding/2,padding,padding/2);
        ScrollView scroll=new ScrollView(activity);scroll.setFillViewport(true);scroll.addView(form);
        EditText text=input(activity,form,"Caption (1–160 characters)","text",initial.text,InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        text.setSingleLine(false);text.setMinLines(2);text.setMaxLines(5);
        EditText start=input(activity,form,"Start (seconds, up to 3 decimals)","startSeconds",seconds(initial.startMs),InputType.TYPE_CLASS_NUMBER|InputType.TYPE_NUMBER_FLAG_DECIMAL);
        EditText duration=input(activity,form,"Duration (seconds, up to 3 decimals)","durationSeconds",seconds(initial.durationMs),InputType.TYPE_CLASS_NUMBER|InputType.TYPE_NUMBER_FLAG_DECIMAL);
        Spinner position=choice(activity,form,"On-screen position","position",new String[]{"Top","Center","Bottom"},initial.position);
        EditText color=input(activity,form,"Text color (#RRGGBB or #AARRGGBB)","color",argb(initial.color),InputType.TYPE_CLASS_TEXT);
        EditText size=input(activity,form,"Font size (12–96 DIP)","fontSize",Integer.toString(initial.fontSize),InputType.TYPE_CLASS_NUMBER);
        String[] families={"sans-serif","sans-serif-condensed","serif","monospace","cursive"};
        Spinner family=choice(activity,form,"Font family","fontFamily",families,index(families,initial.fontFamily));
        String[] styles={"normal","bold","italic","bold_italic"};
        Spinner style=choice(activity,form,"Font style","fontStyle",styles,index(styles,initial.fontStyle));
        String[] alignments={"left","center","right"};
        Spinner alignment=choice(activity,form,"Text alignment","textAlign",alignments,index(alignments,initial.textAlign));
        EditText background=input(activity,form,"Background (#AARRGGBB; #00000000 is transparent)","backgroundColor",argb(initial.backgroundColor),InputType.TYPE_CLASS_TEXT);
        TextView error=new TextView(activity);error.setTag("captionError");error.setTextColor(Color.RED);error.setAccessibilityLiveRegion(TextView.ACCESSIBILITY_LIVE_REGION_POLITE);form.addView(error);
        AlertDialog dialog=new AlertDialog.Builder(activity).setTitle("Timed Caption").setView(scroll)
                .setPositiveButton("Apply",null).setNegativeButton("Cancel",null).create();
        dialog.setOnShowListener(ignored->dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view->{
            if(activity.isFinishing()||activity.isDestroyed()){dialog.dismiss();return;}
            try{
                JSONObject draft=new JSONObject(baseline.toString());
                draft.put("text",text.getText().toString());
                long startMs=milliseconds(start),durationMs=milliseconds(duration);
                if(startMs>timelineDurationMs||durationMs>timelineDurationMs-startMs)
                    throw new IllegalArgumentException("Caption must fit inside the timeline");
                draft.put("startMs",startMs);draft.put("durationMs",durationMs);
                draft.put("position",position.getSelectedItemPosition());draft.put("color",parseColor(color));
                String sizeValue=size.getText().toString().trim();
                if(!sizeValue.matches("[0-9]+"))throw new IllegalArgumentException("Font size must be an integer from 12 to 96 DIP");
                draft.put("fontSize",Integer.parseInt(sizeValue));
                draft.put("fontFamily",family.getSelectedItem().toString());draft.put("fontStyle",style.getSelectedItem().toString());
                draft.put("textAlign",alignment.getSelectedItem().toString());draft.put("backgroundColor",parseColor(background));
                StudioLayer edited=StudioLayer.fromJson(draft);
                if(onApply.test(edited))dialog.dismiss();
                else error.setText("Caption was not saved. Check the current project revision and try again.");
            }catch(Exception invalid){
                error.setText(invalid.getMessage()==null?"Invalid caption":invalid.getMessage());
            }
        }));
        dialog.show();return dialog;
    }

    private static EditText input(Activity activity,LinearLayout form,String label,String tag,String value,int inputType){
        label(activity,form,label);EditText field=new EditText(activity);field.setTag(tag);field.setInputType(inputType);
        field.setSingleLine(true);field.setText(value);field.setContentDescription(label);form.addView(field,new LinearLayout.LayoutParams(-1,-2));return field;
    }
    private static Spinner choice(Activity activity,LinearLayout form,String label,String tag,String[] values,int selected){
        label(activity,form,label);Spinner spinner=new Spinner(activity);spinner.setTag(tag);spinner.setContentDescription(label);
        ArrayAdapter<String> adapter=new ArrayAdapter<>(activity,android.R.layout.simple_spinner_item,values);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);spinner.setAdapter(adapter);spinner.setSelection(selected);
        form.addView(spinner,new LinearLayout.LayoutParams(-1,-2));return spinner;
    }
    private static void label(Activity activity,LinearLayout form,String value){TextView label=new TextView(activity);label.setText(value);form.addView(label);}
    private static int index(String[] choices,String value){for(int i=0;i<choices.length;i++)if(choices[i].equals(value))return i;return 0;}
    private static String seconds(long value){return String.format(Locale.ROOT,"%d.%03d",value/1000,value%1000);}
    private static String argb(int value){return String.format(Locale.ROOT,"#%08X",value);}
    private static long milliseconds(EditText field){
        String value=field.getText().toString().trim();
        if(!value.matches("[0-9]+(?:\\.[0-9]{1,3})?"))
            throw new IllegalArgumentException("Time must use seconds with at most three decimal places");
        try{return new BigDecimal(value).movePointRight(3).longValueExact();}
        catch(ArithmeticException invalid){throw new IllegalArgumentException("Time is outside the supported range");}
    }
    private static int parseColor(EditText field){
        String value=field.getText().toString().trim();
        if(!value.matches("#[0-9a-fA-F]{6}(?:[0-9a-fA-F]{2})?"))
            throw new IllegalArgumentException("Colors must use #RRGGBB or #AARRGGBB");
        return Color.parseColor(value);
    }
}
