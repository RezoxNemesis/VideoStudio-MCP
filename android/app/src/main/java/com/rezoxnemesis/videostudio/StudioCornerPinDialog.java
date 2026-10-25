package com.rezoxnemesis.videostudio;

import android.app.Activity;
import android.app.AlertDialog;
import android.text.InputType;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import org.json.JSONArray;
import java.util.function.Predicate;

/** Touch-first original 2D perspective pinning with exact numeric coordinates. */
public final class StudioCornerPinDialog {
    private StudioCornerPinDialog(){}
    public static AlertDialog show(Activity activity,StudioLayer layer,
                                   Predicate<StudioLayer> commit){
        if(activity==null||layer==null||commit==null)
            throw new IllegalArgumentException("Selected original visual layer required");
        if(!StudioLayer.IMAGE.equals(layer.type)&&!StudioLayer.SHAPE.equals(layer.type))
            throw new IllegalArgumentException("Only image/vector corner pinning is supported");
        final StudioLayer source=layer.copy();
        final StudioCornerPinTouchView graph=new StudioCornerPinTouchView(activity);
        graph.setCorners(source.cornerPin==null?StudioCornerPinRasterizer.identity():source.cornerPin);
        LinearLayout controls=new LinearLayout(activity);controls.setOrientation(LinearLayout.VERTICAL);
        int pad=StudioUi.dp(activity,16);controls.setPadding(pad,pad,pad,pad);
        TextView instructions=new TextView(activity);
        instructions.setText("Drag TL, TR, BR and BL to reshape the image on its own plane. "
                +"The final projective warp is decoded once for Media3 preview/export, not every animation frame. "
                +"Source pixels and music are unchanged.");
        controls.addView(instructions);
        controls.addView(graph,new LinearLayout.LayoutParams(-1,StudioUi.dp(activity,280)));
        TextView label=new TextView(activity);
        label.setText("Exact TLx,TLy,TRx,TRy,BRx,BRy,BLx,BLy (0–1)");
        controls.addView(label);
        EditText numeric=new EditText(activity);
        numeric.setInputType(InputType.TYPE_CLASS_TEXT);
        numeric.setSingleLine(false);
        numeric.setMinLines(2);
        numeric.setContentDescription("Four corner normalized perspective transform coordinates");
        numeric.setText(graph.numericCorners());
        controls.addView(numeric);
        graph.onChange(points->numeric.setText(graph.numericCorners()));
        Button sync=new Button(activity);
        StudioUi.styleButton(sync,false);
        sync.setText("Update corners from exact numbers");
        sync.setOnClickListener(v->{
            try{graph.setCorners(parse(numeric.getText().toString()));}
            catch(Exception error){
                numeric.setError(error.getMessage());
                Toast.makeText(activity,"Invalid corner pin: "+error.getMessage(),Toast.LENGTH_LONG).show();
            }
        });controls.addView(sync);
        Button reset=new Button(activity);
        StudioUi.styleButton(reset,false);
        reset.setText("Reset to original unwarped rectangle");
        reset.setOnClickListener(v->{
            graph.setCorners(StudioCornerPinRasterizer.identity());
            numeric.setText(graph.numericCorners());
        });controls.addView(reset);
        ScrollView scroll=new ScrollView(activity);scroll.addView(controls);
        AlertDialog dialog=new AlertDialog.Builder(activity).setTitle("Corner Pin · projective 2D")
                .setView(scroll).setPositiveButton("Apply corner pin",null)
                .setNegativeButton("Cancel",null).create();
        dialog.setOnShowListener(ignored->
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{
                try{
                    JSONArray corners=parse(numeric.getText().toString());
                    StudioLayer result=source.copy();
                    result.cornerPin=StudioCornerPinRasterizer.isIdentity(corners)?null:corners;
                    result.validate();
                    if(commit.test(result))dialog.dismiss();
                }catch(Exception error){
                    numeric.setError(error.getMessage());
                    Toast.makeText(activity,"Corner pin not saved: "+error.getMessage(),Toast.LENGTH_LONG).show();
                }
            }));
        dialog.show();
        StudioUi.prepareTouchLayout(controls);
        return dialog;
    }

    public static JSONArray parse(String raw){
        if(raw==null)throw new IllegalArgumentException("Eight corner values required");
        String[] tokens=raw.trim().split(",");
        if(tokens.length!=8)throw new IllegalArgumentException("Use eight comma-separated corner coordinates");
        JSONArray array=new JSONArray();
        try{
            for(String s:tokens){
                double x=Double.parseDouble(s.trim());
                if(!Double.isFinite(x))throw new NumberFormatException("Not finite");
                array.put((Object)Double.valueOf(x));
            }
        }catch(NumberFormatException bad){
            throw new IllegalArgumentException("Corner coordinates must be valid finite numbers",bad);
        }
        return StudioCornerPinRasterizer.validate(array);
    }
}
