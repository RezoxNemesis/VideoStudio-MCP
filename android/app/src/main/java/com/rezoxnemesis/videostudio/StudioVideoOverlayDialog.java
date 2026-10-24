package com.rezoxnemesis.videostudio;

import android.app.Activity;
import android.app.AlertDialog;
import android.text.InputType;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.math.BigDecimal;
import java.util.Locale;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Predicate;
import org.json.JSONObject;

/** Silent owned VIDEO placement; numeric drafts stay separate from worker source verification. */
final class StudioVideoOverlayDialog implements AutoCloseable {
    private static final String[] FIELDS={"start (seconds)","duration (seconds)","source in (seconds)","center X","center Y","width","height","rotation","opacity"};
    private final Activity activity;
    private final ProjectStore.Project project;
    private final StudioLayer initial;
    private final boolean fresh;
    private final Consumer<StudioVideoOverlayDialog> onClosed;
    private final AlertDialog dialog;
    private final EditText[] fields=new EditText[FIELDS.length];
    private final TextView status;
    private final Button fit;
    private final StudioVideoSourceMetadata.Session source;
    private StudioVideoSourceMetadata.Proof proof;
    private boolean closed,busy,geometryReady;

    static StudioVideoOverlayDialog show(Activity activity,ProjectStore.Project project,ProjectStore.Asset asset,
            StudioLayer layer,long playhead,BooleanSupplier current,Predicate<StudioLayer> save,
            Consumer<StudioVideoOverlayDialog> onClosed)throws Exception{
        return new StudioVideoOverlayDialog(activity,project,asset,layer,playhead,current,save,onClosed);
    }
    private StudioVideoOverlayDialog(Activity activity,ProjectStore.Project project,ProjectStore.Asset asset,
            StudioLayer layer,long playhead,BooleanSupplier current,Predicate<StudioLayer> save,
            Consumer<StudioVideoOverlayDialog> onClosed)throws Exception{
        this.activity=activity;this.project=ProjectStore.Project.fromJson(project.toJson());this.onClosed=onClosed;fresh=layer==null;
        if(fresh){
            StudioLayer draft=new StudioLayer();draft.type=StudioLayer.VIDEO;draft.audioMode="silent";draft.assetId=asset.id;
            draft.startMs=Math.max(0,Math.min(project.outputDurationMs()-100,playhead));
            draft.durationMs=Math.min(2500,Math.min(asset.durationMs,project.outputDurationMs()-draft.startMs));initial=draft;
        }else initial=layer.copy();
        geometryReady=!fresh;
        LinearLayout form=new LinearLayout(activity);form.setOrientation(LinearLayout.VERTICAL);int padding=dp(18);form.setPadding(padding,dp(8),padding,dp(12));
        label(form,"Silent video overlay · source audio is not included. The base video and independent audio tracks keep their sound.");
        label(form,"Source: "+asset.name+" · "+seconds(asset.durationMs)+" seconds. Output start and source in are independent; playback speed is 1.");
        String[] values={seconds(initial.startMs),seconds(initial.durationMs),seconds(initial.sourceInMs),Float.toString(initial.x),Float.toString(initial.y),Float.toString(initial.width),Float.toString(initial.height),Float.toString(initial.rotation),Float.toString(initial.opacity)};
        for(int i=0;i<FIELDS.length;i++){
            label(form,FIELDS[i]);EditText field=new EditText(activity);field.setContentDescription("Video "+FIELDS[i]);field.setSingleLine(true);field.setInputType(InputType.TYPE_CLASS_NUMBER|InputType.TYPE_NUMBER_FLAG_DECIMAL|InputType.TYPE_NUMBER_FLAG_SIGNED);field.setText(values[i]);field.setMinHeight(dp(48));fields[i]=field;form.addView(field);
        }
        fit=new Button(activity);fit.setText("Fit source proportions");StudioUi.styleButton(fit,false);form.addView(fit);
        label(form,"Proportions fit inside this box on the 9:16 edited preview. Refit when choosing another export aspect. Width and height remain independent until you use Fit.");
        status=label(form,"Checking this owned video's metadata and source hash…");status.setContentDescription("Video overlay source status");
        ScrollView scroll=new ScrollView(activity);scroll.addView(form);
        dialog=new AlertDialog.Builder(activity).setTitle("Video Overlay · "+asset.name).setView(scroll).setNegativeButton("Cancel",null).setPositiveButton("Apply",null).create();
        source=new StudioVideoSourceMetadata.Session(activity,project.id,project.updatedAt,asset,this::active,current,save);
        dialog.setOnCancelListener(ignored->close());dialog.setOnDismissListener(ignored->close());dialog.show();
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener(ignored->close());
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(ignored->apply());
        fit.setOnClickListener(ignored->{if(!active()||busy||proof==null)return;try{fit();geometryReady=true;setBusy(false);}catch(Exception error){status.setText(message(error));}});
        setBusy(true);StudioUi.prepareTouchLayout(form);
        source.prepare(metadata->{proof=metadata;try{if(fresh){fit();geometryReady=true;}setBusy(false);status.setText("Verified source: "+metadata.displayWidth+" × "+metadata.displayHeight+" · rotation "+metadata.rotation+"° · source bytes pinned");}
            catch(Exception error){setBusy(false);status.setText(message(error));}},failure->{setBusy(false);status.setText(failure+". Reopen the source controls to retry.");});
    }
    AlertDialog dialog(){return dialog;}
    private void fit(){double[] size=proof.fit(number(fields[5]),number(fields[6]),720,1280);fields[5].setText(Double.toString(size[0]));fields[6].setText(Double.toString(size[1]));}
    private StudioLayer read()throws Exception{
        JSONObject value=initial.toJson().put("startMs",milliseconds(fields[0])).put("durationMs",milliseconds(fields[1])).put("sourceInMs",milliseconds(fields[2]));
        String[] names={"x","y","width","height","rotation","opacity"};for(int i=0;i<names.length;i++)value.put(names[i],number(fields[i+3]));
        StudioLayer result=StudioLayer.fromJson(value);StudioLayerContract.validate(project,result);return result;
    }
    private void apply(){
        if(!active()||busy||proof==null||!geometryReady)return;
        try{StudioLayer candidate=read();setBusy(true);status.setText("Checking unchanged video bytes before saving…");
            source.apply(candidate,(saved,error)->{if(!active())return;if(saved){close();return;}setBusy(false);status.setText(error);});
        }catch(Exception error){setBusy(false);status.setText(message(error));}
    }
    private void setBusy(boolean value){busy=value;for(EditText field:fields)field.setEnabled(!value);fit.setEnabled(!value&&proof!=null);dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(!value&&proof!=null&&geometryReady);}
    private boolean active(){return !closed&&dialog.isShowing()&&!activity.isFinishing()&&!activity.isDestroyed();}
    @Override public void close(){if(closed)return;closed=true;source.close();if(dialog.isShowing())dialog.dismiss();onClosed.accept(this);}
    private TextView label(LinearLayout form,String text){TextView label=new TextView(activity);label.setText(text);label.setPadding(0,dp(5),0,dp(4));form.addView(label);return label;}
    private int dp(int value){return StudioUi.dp(activity,value);}
    private static double number(EditText field){double value=Double.parseDouble(field.getText().toString().trim());if(!Double.isFinite(value))throw new IllegalArgumentException("Use a finite number");return value;}
    private static long milliseconds(EditText field){String text=field.getText().toString().trim();if(!text.matches("[0-9]+(?:\\.[0-9]{1,3})?"))throw new IllegalArgumentException("Time must use nonnegative seconds with at most 3 decimals");return new BigDecimal(text).movePointRight(3).longValueExact();}
    private static String seconds(long value){return String.format(Locale.ROOT,"%d.%03d",value/1000,value%1000);}
    private static String message(Exception error){return error.getMessage()==null?"Video overlay was not changed":error.getMessage();}
}
