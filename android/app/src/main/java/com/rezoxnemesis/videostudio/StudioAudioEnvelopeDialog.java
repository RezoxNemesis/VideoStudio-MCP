package com.rezoxnemesis.videostudio;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.LongConsumer;

/** Read-only selected AUDIO inspection. The MCP and manual surfaces share the native decoder. */
final class StudioAudioEnvelopeDialog implements AutoCloseable {
    private static final long DEADLINE_MS=120000;
    private final Activity activity;
    private final Context app;
    private final String projectId,assetId,assetJson;
    private final long revision;
    private final StudioLayer lane;
    private final BooleanSupplier current;
    private final LongConsumer seekTimeline;
    private final Consumer<StudioAudioEnvelopeDialog> onClosed;
    private final Handler main=new Handler(Looper.getMainLooper());
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private final AtomicLong generation=new AtomicLong();
    private final AlertDialog dialog;
    private final EditText source,duration,bucketCount;
    private final TextView identity,status,coverage,selection,output;
    private final ProgressBar progress;
    private final Button cancel,copyInterval,seek,copyResult,details;
    private final LinearLayout results;
    private final SeekBar bucket;
    private final EnvelopeView graph;
    private JSONObject proof,result;
    private Future<?> pending;
    private Runnable deadline;
    private volatile boolean closed;
    private boolean busy;

    static StudioAudioEnvelopeDialog show(Activity activity,ProjectStore.Project project,ProjectStore.Asset asset,
            StudioLayer lane,BooleanSupplier current,LongConsumer seekTimeline,
            Consumer<StudioAudioEnvelopeDialog> onClosed)throws Exception {
        return new StudioAudioEnvelopeDialog(activity,project,asset,lane,current,seekTimeline,onClosed);
    }

    private StudioAudioEnvelopeDialog(Activity activity,ProjectStore.Project project,ProjectStore.Asset asset,
            StudioLayer lane,BooleanSupplier current,LongConsumer seekTimeline,
            Consumer<StudioAudioEnvelopeDialog> onClosed)throws Exception {
        if(project==null||asset==null||lane==null||!StudioLayer.AUDIO.equals(lane.type)||!asset.id.equals(lane.assetId)
                ||asset.mime==null||!asset.mime.startsWith("audio/"))throw new IllegalArgumentException("Choose an existing AUDIO lane");
        this.activity=activity;app=activity.getApplicationContext();projectId=project.id;revision=project.updatedAt;
        assetId=asset.id;assetJson=SketchDocument.canonical(asset.toJson());this.lane=lane.copy();
        this.current=current;this.seekTimeline=seekTimeline;this.onClosed=onClosed;
        LinearLayout form=column();form.setPadding(dp(18),dp(8),dp(18),dp(12));
        identity=label(form,"Source: "+asset.name+"\nAsset: "+assetId+"\nProject revision: "+revision);
        identity.setTextIsSelectable(true);
        label(form,"Measure source amplitude without changing the audio or edit. The graph uses window-normalized RMS; raw RMS and sample peak remain available for every measured bucket.");
        source=input(form,"Source start (ms), 0..86400000","Audio source start (ms)",lane.sourceInMs);
        duration=input(form,"Duration (ms), 100..30000","Audio duration (ms)",Math.max(100,Math.min(30000,lane.durationMs)));
        bucketCount=input(form,"Buckets, 4..720","Audio buckets",Math.max(4,Math.min(720,(Math.max(100,Math.min(30000,lane.durationMs))*24+999)/1000)));
        status=label(form,"Checking this selected owned audio source…");status.setContentDescription("Audio envelope status");
        progress=new ProgressBar(activity,null,android.R.attr.progressBarStyleHorizontal);progress.setMax(100);
        progress.setContentDescription("Audio envelope progress");form.addView(progress,new LinearLayout.LayoutParams(-1,dp(12)));
        cancel=button(form,"Cancel analysis",()->cancel("Cancelled. Source audio and project were preserved."));
        results=column();form.addView(results);results.setVisibility(View.GONE);
        coverage=label(results,"");coverage.setTextIsSelectable(true);
        graph=new EnvelopeView(activity);graph.setContentDescription("Measured source RMS envelope");results.addView(graph,new LinearLayout.LayoutParams(-1,dp(130)));
        label(results,"Green: complete · amber: partial · crosses: missing. Source-time axis is in seconds.");
        label(results,"Source time · select a bucket to read its interval and measured coverage.");
        bucket=new SeekBar(activity);bucket.setContentDescription("Audio envelope bucket");results.addView(bucket);
        selection=label(results,"");selection.setTextIsSelectable(true);
        bucket.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){
            @Override public void onProgressChanged(SeekBar bar,int at,boolean user){select(at);}
            @Override public void onStartTrackingTouch(SeekBar bar){}
            @Override public void onStopTrackingTouch(SeekBar bar){}
        });
        copyInterval=button(results,"Copy selected interval",()->{try{copy("Audio source interval",selectedInterval());}catch(Exception error){status.setText(message(error));}});
        seek=button(results,"Seek timeline to selected bucket",()->{try{
            if(!available())return;JSONObject selected=selected();long at=selected.getLong("sourceStartUs");
            if(!insideLane(selected))throw new IllegalArgumentException("This source interval is outside the selected timeline lane");
            seekTimeline.accept(this.lane.startMs+(at/1000-this.lane.sourceInMs));
        }catch(Exception error){status.setText(message(error));}});
        copyResult=button(results,"Copy analysis result",()->{if(result!=null)copy("Audio envelope result",result.toString());});
        output=label(results,"");output.setContentDescription("Audio envelope result JSON");output.setTextIsSelectable(true);output.setVisibility(View.GONE);
        details=button(results,"Show result JSON",()->{boolean show=output.getVisibility()!=View.VISIBLE;output.setVisibility(show?View.VISIBLE:View.GONE);detailsLabel(show);});
        label(form,"Sample peak measures decoded PCM samples. Analysis does not detect beats or speech. Missing coverage is shown explicitly; measured silence has real sample counts.");
        ScrollView scroll=new ScrollView(activity);scroll.addView(form);
        dialog=new AlertDialog.Builder(activity).setTitle("Analyze audio envelope").setView(scroll)
                .setNegativeButton("Close",null).setPositiveButton("Analyze",null).create();
        dialog.setOnCancelListener(ignored->close());dialog.setOnDismissListener(ignored->close());dialog.show();
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener(ignored->close());
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(ignored->analyse());
        StudioUi.prepareTouchLayout(form);prepare();
    }

    private void prepare(){
        final long ticket=begin("Verifying selected source ownership and SHA-256…");
        pending=worker.submit(()->{try{
            check(ticket);JSONObject inspected=ProjectAssetImport.inspect(app,new JSONObject().put("projectId",projectId).put("expectedUpdatedAt",revision).put("assetId",assetId));
            try(ProjectStore store=new ProjectStore(app)){
                ProjectStore.Project project=store.get(projectId);ProjectStore.Asset asset=project==null?null:project.asset(assetId);
                if(project==null||project.updatedAt!=revision||asset==null||!assetJson.equals(SketchDocument.canonical(asset.toJson())))
                    throw new IllegalArgumentException("The selected audio source changed. Reopen its Inspector.");
                if(asset.mime==null||!asset.mime.startsWith("audio/"))throw new IllegalArgumentException("Choose an existing AUDIO asset");
                ProjectAssetImport.sourceFile(app,asset);
            }
            check(ticket);post(ticket,()->{
                finish();if(!available())return;proof=inspected;
                identity.append("\nSHA-256: "+inspected.optString("sha256")+"\nSource bytes: "+inspected.optLong("bytes"));
                status.setText("Verified selected owned audio. Set a source window, then Analyze.");setBusy(false);
            });
        }catch(Exception error){post(ticket,()->{finish();proof=null;setBusy(false);status.setText("Source unavailable: "+message(error)+". Linked audio needs an owned import before envelope analysis.");});}});
    }

    private void analyse(){
        if(!available()||busy||proof==null)return;
        try{
            JSONObject request=NativeAudioEnvelope.validate(new JSONObject().put("mode","audio_envelope")
                    .put("projectId",projectId).put("expectedUpdatedAt",revision).put("assetId",assetId)
                    .put("expectedSha256",proof.getString("sha256")).put("sourceMs",integer(source))
                    .put("durationMs",integer(duration)).put("buckets",integer(bucketCount)));
            result=null;output.setText("");results.setVisibility(View.GONE);
            final long ticket=begin("Starting native audio measurement…");
            pending=worker.submit(()->{try{
                final int[] lastPercent={-1};final String[] lastDetail={null};
                check(ticket);JSONObject measured=NativeAudioEnvelope.analyse(app,request,(percent,detail)->{
                    check(ticket);if(percent==lastPercent[0]&&java.util.Objects.equals(detail,lastDetail[0]))return;
                    lastPercent[0]=percent;lastDetail[0]=detail;
                    post(ticket,()->{progress.setIndeterminate(false);progress.setProgress(percent);status.setText(detail);});
                });
                check(ticket);post(ticket,()->{
                    finish();if(!available())return;setBusy(false);
                    try{showResult(measured);}catch(Exception error){status.setText("Result could not be displayed: "+message(error));}
                });
            }catch(Exception error){post(ticket,()->{
                finish();setBusy(false);result=null;output.setText("");
                status.setText(NativeAudioEnvelope.code(error)+": "+message(error));
                try{JSONObject diagnostics=NativeAudioEnvelope.diagnostics(error);if(diagnostics.has("coverage"))status.append("\nCoverage: "+diagnostics.getJSONObject("coverage").toString());}catch(Exception ignored){}
            });}});
        }catch(Exception error){status.setText(message(error));}
    }

    private long begin(String text){long ticket=generation.incrementAndGet();setBusy(true);progress.setIndeterminate(true);status.setText(text);
        deadline=()->{if(!closed&&generation.get()==ticket&&busy)cancel("AUDIO_ENVELOPE_BUDGET: analysis reached its 120-second deadline; source preserved.");};
        main.postDelayed(deadline,DEADLINE_MS);return ticket;
    }
    private void finish(){if(deadline!=null)main.removeCallbacks(deadline);deadline=null;pending=null;}
    private void cancel(String text){if(closed||!busy)return;generation.incrementAndGet();main.removeCallbacksAndMessages(null);deadline=null;
        Future<?> task=pending;pending=null;if(task!=null)task.cancel(true);result=null;output.setText("");results.setVisibility(View.GONE);setBusy(false);status.setText(text);
    }
    private void check(long ticket)throws InterruptedException {if(closed||generation.get()!=ticket||Thread.currentThread().isInterrupted())throw new InterruptedException("Audio envelope cancelled");}
    private void post(long ticket,Runnable callback){if(!closed&&generation.get()==ticket)main.post(()->{if(!closed&&generation.get()==ticket&&dialog.isShowing())callback.run();});}
    private boolean available(){if(closed||!dialog.isShowing()||activity.isFinishing()||activity.isDestroyed())return false;
        if(!current.getAsBoolean()){cancel("The selected editing view changed. Reopen the Audio Inspector.");proof=null;setBusy(false);status.setText("The selected editing view changed. Reopen the Audio Inspector.");return false;}return true;
    }
    private void setBusy(boolean value){busy=value;source.setEnabled(!value);duration.setEnabled(!value);bucketCount.setEnabled(!value);
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(!value&&proof!=null);cancel.setEnabled(value);progress.setVisibility(value?View.VISIBLE:View.GONE);
    }

    private void showResult(JSONObject measured)throws Exception {
        result=measured;output.setText(measured.toString(2));JSONObject covered=measured.getJSONObject("coverage");boolean complete=covered.getBoolean("complete");
        String state=complete?"complete":"partial";status.setText(complete?"Audio envelope complete. Source unchanged.":"Partial audio envelope. Missing samples remain explicit; source unchanged.");
        long from=measured.getLong("sourceMs"),to=from+measured.getLong("durationMs");
        coverage.setText("Coverage: "+state+" · "+covered.getLong("decodedFrames")+" / "+covered.getLong("requestedFrames")+" sample frames\n"
                +covered.getInt("missingBuckets")+" missing buckets · "+covered.getInt("partialBuckets")+" partial buckets\n"
                +"Source time "+seconds(from*1000)+" – "+seconds(to*1000)+" s\n"
                +measured.getInt("sampleRate")+" Hz · "+measured.getInt("channels")+" channels · "+measured.getString("decoderRoute")+"\n"
                +"Maximum raw RMS "+metric(measured.getJSONObject("normalization"),"maximumRms"));
        JSONArray values=measured.getJSONArray("buckets");graph.set(values);bucket.setMax(values.length()-1);bucket.setProgress(0);results.setVisibility(View.VISIBLE);select(0);
    }
    private void select(int at){if(result==null)return;try{
        JSONObject value=result.getJSONArray("buckets").getJSONObject(at),covered=value.getJSONObject("coverage");
        String measured=covered.getBoolean("complete")?"complete":value.getLong("sampleFrames")==0?"missing":"partial";
        selection.setText("Bucket "+(at+1)+" · "+measured+"\nSource time "+seconds(value.getLong("sourceStartUs"))+" – "+seconds(value.getLong("sourceEndUs"))+" s\n"
                +(insideLane(value)?"Timeline time "+seconds(lane.startMs*1000+value.getLong("sourceStartUs")-lane.sourceInMs*1000)+" – "+seconds(lane.startMs*1000+value.getLong("sourceEndUs")-lane.sourceInMs*1000)+" s":"Outside this lane's timeline interval")
                +"\nRMS "+metric(value,"rms")+" · sample peak "+metric(value,"samplePeak")+" · normalized RMS "+metric(value,"normalizedRms")
                +"\n"+covered.getLong("decodedFrames")+" / "+covered.getLong("expectedFrames")+" frames · "+covered.getLong("missingFrames")+" missing");
        seek.setEnabled(insideLane(value));graph.selected=at;graph.invalidate();
    }catch(Exception error){selection.setText(message(error));seek.setEnabled(false);}}
    private JSONObject selected()throws Exception {if(result==null)throw new IllegalStateException("Analyze an audio window first");return result.getJSONArray("buckets").getJSONObject(bucket.getProgress());}
    private boolean insideLane(JSONObject value)throws Exception {return value.getLong("sourceStartUs")>=lane.sourceInMs*1000&&value.getLong("sourceEndUs")<=(lane.sourceInMs+lane.durationMs)*1000;}
    private String selectedInterval()throws Exception {JSONObject selected=selected();JSONObject interval=new JSONObject().put("projectId",projectId).put("sourceProjectUpdatedAt",revision).put("assetId",assetId)
            .put("sourceSha256",result.getString("sourceSha256")).put("sourceStartUs",selected.getLong("sourceStartUs")).put("sourceEndUs",selected.getLong("sourceEndUs"));
        if(insideLane(selected))interval.put("timelineStartUs",lane.startMs*1000+selected.getLong("sourceStartUs")-lane.sourceInMs*1000)
                .put("timelineEndUs",lane.startMs*1000+selected.getLong("sourceEndUs")-lane.sourceInMs*1000);return interval.toString(2);
    }
    private void copy(String label,String text){if(!available())return;ClipboardManager clipboard=(ClipboardManager)activity.getSystemService(Context.CLIPBOARD_SERVICE);if(clipboard!=null){clipboard.setPrimaryClip(ClipData.newPlainText(label,text));status.setText("Copied "+label.toLowerCase(Locale.ROOT)+". Project unchanged.");}}
    private void detailsLabel(boolean visible){details.setText(visible?"Hide result JSON":"Show result JSON");}
    @Override public void close(){if(closed)return;closed=true;generation.incrementAndGet();main.removeCallbacksAndMessages(null);if(pending!=null)pending.cancel(true);worker.shutdownNow();if(dialog.isShowing())dialog.dismiss();onClosed.accept(this);}

    private LinearLayout column(){LinearLayout view=new LinearLayout(activity);view.setOrientation(LinearLayout.VERTICAL);return view;}
    private TextView label(LinearLayout parent,String text){TextView view=new TextView(activity);view.setText(text);view.setPadding(0,dp(5),0,dp(4));parent.addView(view);return view;}
    private EditText input(LinearLayout parent,String label,String description,long value){label(parent,label);EditText field=new EditText(activity);field.setContentDescription(description);field.setSingleLine(true);field.setInputType(InputType.TYPE_CLASS_NUMBER);field.setMinHeight(dp(48));field.setText(Long.toString(value));parent.addView(field);return field;}
    private Button button(LinearLayout parent,String text,Runnable action){Button view=new Button(activity);view.setText(text);StudioUi.styleButton(view,false);view.setOnClickListener(ignored->{if(!closed)action.run();});parent.addView(view);return view;}
    private int dp(int value){return StudioUi.dp(activity,value);}
    private static long integer(EditText field){String value=field.getText().toString().trim();if(!value.matches("[0-9]+"))throw new IllegalArgumentException("Use whole nonnegative milliseconds and whole bucket counts");try{return Long.parseLong(value);}catch(NumberFormatException failure){throw new IllegalArgumentException("Entered number is too large",failure);}}
    private static String seconds(long us){return String.format(Locale.ROOT,"%.6f",us/1000000.);}
    private static String metric(JSONObject value,String key)throws Exception {return value.isNull(key)?"unmeasured":String.format(Locale.ROOT,"%.6f",value.getDouble(key));}
    private static String message(Exception error){return error.getMessage()==null?"Audio envelope analysis failed":error.getMessage();}

    /** Missing buckets remain visibly distinct from measured zero-amplitude buckets. */
    private static final class EnvelopeView extends View {
        private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);private double[] rms=new double[0];private boolean[] measured=new boolean[0],complete=new boolean[0];private long startUs,endUs;int selected;
        EnvelopeView(Context context){super(context);setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);}
        void set(JSONArray buckets)throws Exception {rms=new double[buckets.length()];measured=new boolean[buckets.length()];complete=new boolean[buckets.length()];for(int i=0;i<rms.length;i++){JSONObject bucket=buckets.getJSONObject(i);measured[i]=!bucket.isNull("normalizedRms");rms[i]=measured[i]?bucket.getDouble("normalizedRms"):0;complete[i]=bucket.getJSONObject("coverage").getBoolean("complete");if(i==0)startUs=bucket.getLong("sourceStartUs");endUs=bucket.getLong("sourceEndUs");}invalidate();}
        @Override protected void onDraw(Canvas canvas){super.onDraw(canvas);float width=getWidth(),height=getHeight(),axis=StudioUi.dp(getContext(),22),baseline=Math.max(0,height-axis),step=width/Math.max(1,rms.length);canvas.drawColor(StudioUi.BG);
            for(int i=0;i<rms.length;i++){float left=i*step,right=(i+1)*step;paint.setColor(i==selected?StudioUi.ACCENT:!measured[i]?0xffba7878:complete[i]?0xff6f9e8b:0xffd6ae63);
                if(measured[i]){if(rms[i]==0)canvas.drawLine(left,baseline,Math.max(left+1,right-1),baseline,paint);else canvas.drawRect(left,baseline-Math.max(1,(float)(rms[i]*Math.max(0,baseline-4))),Math.max(left+1,right-1),baseline,paint);}
                else{paint.setStrokeWidth(1);canvas.drawLine(left,0,right,baseline,paint);canvas.drawLine(left,baseline,right,0,paint);}}
            paint.setColor(StudioUi.MUTED);paint.setTextSize(10*getResources().getDisplayMetrics().scaledDensity);paint.setStrokeWidth(1);
            for(int i=0;i<3;i++){float x=width*i/2;canvas.drawLine(x,baseline,x,baseline+StudioUi.dp(getContext(),4),paint);String tick=String.format(Locale.ROOT,"%.3f",(startUs+(endUs-startUs)*i/2)/1000000.);float at=i==0?0:i==1?x-paint.measureText(tick)/2:width-paint.measureText(tick);canvas.drawText(tick,at,height-paint.descent(),paint);}
        }
    }
}
