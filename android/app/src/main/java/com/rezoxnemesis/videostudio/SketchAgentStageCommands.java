package com.rezoxnemesis.videostudio;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import org.json.JSONArray;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;

/**
 * Durable, bounded native-only Sketch Studio viewport/timeline control.
 *
 * A queued stage action is NOT an edit to any source frame. Only the actual
 * foreground SketchStudioActivity can mark a step "applied" after performing
 * it on the real UI. SQLite survives compatible APK upgrades, Android sleep,
 * process death and cross-component lifecycle changes. No Gallery access.
 */
public final class SketchAgentStageCommands implements AutoCloseable {
    public static final int MAX_STEPS=16, MAX_PENDING=24;
    public static final long MAX_AGE_MS=24L*60*60*1000;
    private static final int MAX_SCRIPT_BYTES=8192;
    private static final Set<String> TOOL_NAMES=Set.of(
            "stroke","eraser","line","rect","ellipse","polygon","select",
            "eyedropper","pan","curve","nodes","precision","precision_brush");
    private static final Set<String> ACTIONS=Set.of(
            "tool","brush","frame","layer","object","camera","fit","onion",
            "grid","play","pause","wait","scrub","transform_curve","shape_curve","xsheet",
            "object_drag","node_drag");

    private final SQLiteDatabase db;
    private final Helper helper;
    private static final class Helper extends SQLiteOpenHelper {
        Helper(Context context) { super(context,"sketch_agent_stage_v1.db",null,1); }
        @Override public void onCreate(SQLiteDatabase sql) {
            sql.execSQL("CREATE TABLE stage_commands("+
                    "sequence INTEGER PRIMARY KEY AUTOINCREMENT,"+
                    "sketch_id TEXT NOT NULL,"+
                    "operation_id TEXT NOT NULL,"+
                    "fingerprint TEXT NOT NULL,"+
                    "expected_revision INTEGER NOT NULL,"+
                    "steps_json TEXT NOT NULL,"+
                    "next_step INTEGER NOT NULL DEFAULT 0,"+
                    "state TEXT NOT NULL,"+
                    "lease_until_ms INTEGER NOT NULL DEFAULT 0,"+
                    "created_at_ms INTEGER NOT NULL,"+
                    "updated_at_ms INTEGER NOT NULL,"+
                    "result_json TEXT NOT NULL DEFAULT '{}',"+
                    "UNIQUE(sketch_id,operation_id))");
            sql.execSQL("CREATE INDEX stage_pending_order "+
                    "ON stage_commands(sketch_id,state,sequence)");
        }
        @Override public void onUpgrade(SQLiteDatabase sql,int oldVersion,int newVersion) {
            throw new IllegalStateException("Unknown Sketch stage database migration");
        }
    }

    public SketchAgentStageCommands(Context context){
        if(context==null)throw new IllegalArgumentException("Sketch stage requires a native context");
        helper=new Helper(context.getApplicationContext());
        db=helper.getWritableDatabase();
    }
    @Override public void close(){helper.close();}

    public static JSONObject capabilities() throws Exception {
        return new JSONObject()
                .put("schema","videostudio.sketch-visible-director.v1")
                .put("minimumNativeVersion","4.10.44")
                .put("enqueueAction","sketch_stage_execute")
                .put("statusAction","sketch_stage_status")
                .put("discoverAction","sketch_stage_capabilities")
                .put("maxStepsPerCommand",MAX_STEPS)
                .put("maxQueuedCommands",MAX_PENDING)
                .put("queueRetentionMs",MAX_AGE_MS)
                .put("availableSteps",new JSONArray()
                        .put("tool").put("brush").put("frame").put("layer").put("object")
                        .put("camera").put("fit").put("onion").put("grid")
                        .put("play").put("pause").put("wait").put("scrub").put("transform_curve")
                        .put("shape_curve").put("xsheet")
                        .put("object_drag").put("node_drag"))
                .put("camera",new JSONObject().put("zoomMin",1).put("zoomMax",12)
                        .put("centerCoordinates","normalized drawing [0,1]")
                        .put("animationMsMax",800))
                .put("visualProof","Only foreground editor acknowledges each actual UI step")
                .put("xsheetEditableFromVersion","4.10.38")
                .put("xsheetHoldEditOperation","set_exposure_cel")
                .put("brushSettingFields",new JSONArray()
                        .put("color").put("widthPx").put("fill").put("preset")
                        .put("opacity").put("hardness").put("angle").put("stabilization"))
                .put("brushSettingsOnly",true)
                .put("brushSettingRequiresVisibleWatch",true)
                .put("sourceEditsAction","sketch_edit")
                .put("strokeVisualization","existing accepted native SketchLivePlayer")
                .put("humanGestureControls",SketchAgentGestureOps.capabilities())
                .put("visibleTimelineScrub",SketchAgentTimelineScrub.capabilities())
                .put("mutatingGesturesTerminalOnly",true)
                .put("persistedWithoutBackgroundUI",true)
                .put("navigationStepsSourceUnchanged",true)
                .put("sourceChangingGestureSteps",new JSONArray().put("object_drag").put("node_drag"))
                .put("sourceChangesOnlyAfterForegroundCommit",true)
                .put("originalDocumentUnchanged",false)
                .put("galleryAccess",false);
    }

    /** Canonical admission: one tool call can author 1..16 visible UI steps. */
    public static JSONObject normalize(JSONObject program,JSONObject document)throws Exception{
        if(program==null||document==null)throw SketchDocument.bad("Native stage program and source required");
        SketchDocument.fields(program,"sketchId,operationId,expectedRevision,steps");
        String id=SketchDocument.id(program,"sketchId");
        SketchDocument.id(program,"operationId");
        long revision=SketchDocument.integer(program,"expectedRevision",1,Long.MAX_VALUE-1);
        if(!id.equals(document.getString("id"))||revision!=document.getLong("revision"))
            throw new IllegalStateException("SKETCH_STAGE_SOURCE_CHANGED: inspect the current drawing");
        JSONArray steps=program.getJSONArray("steps");
        if(steps.length()<1||steps.length()>MAX_STEPS)
            throw SketchDocument.bad("Choose 1..16 visible stage steps");
        JSONArray prepared=new JSONArray();
        for(int i=0;i<steps.length();i++){
            JSONObject step=steps.getJSONObject(i);
            String action=step.optString("action","");
            if(!ACTIONS.contains(action))throw SketchDocument.bad("Unknown visible stage action");
            switch(action){
                case "tool":
                    SketchDocument.fields(step,"action,tool");
                    String tool=step.getString("tool");
                    if(!TOOL_NAMES.contains(tool))
                        throw SketchDocument.bad("Only real, non-paint-backing sketch tools can be selected by stage");
                    break;
                case "brush": {
                    SketchDocument.fields(step,"action,color,widthPx,fill,preset,opacity,hardness,angle,stabilization");
                    if(step.length()<2)
                        throw SketchDocument.bad("Choose at least one actual brush property to change");
                    if(step.has("color")) {
                        Object c=step.opt("color");
                        if(!(c instanceof String))
                            throw SketchDocument.bad("Brush colour must be #AARRGGBB");
                        SketchDocument.color((String)c);
                    }
                    if(step.has("widthPx")) {
                        int side=Math.min(document.getInt("width"),document.getInt("height"));
                        SketchDocument.number(step,"widthPx",side*.0005,side*.2);
                    }
                    if(step.has("fill"))SketchDocument.bool(step,"fill");
                    if(step.has("preset")){
                        if(!(step.opt("preset") instanceof String)||
                                !java.util.Arrays.asList(SketchBrushes.PRESETS).contains(step.getString("preset")))
                            throw SketchDocument.bad("Unknown native brush preset");
                    }
                    if(step.has("opacity"))SketchDocument.number(step,"opacity",0,1);
                    if(step.has("hardness"))SketchDocument.number(step,"hardness",0,1);
                    if(step.has("angle"))SketchDocument.number(step,"angle",-180,180);
                    if(step.has("stabilization"))SketchDocument.number(step,"stabilization",0,.9);
                    break;
                }
                case "frame":
                    SketchDocument.fields(step,"action,frameIndex");
                    SketchDocument.integer(step,"frameIndex",0,document.getInt("frameCount")-1);
                    break;
                case "scrub":
                    SketchDocument.fields(step,"action,frameIndex,durationMs");
                    SketchDocument.integer(step,"frameIndex",0,document.getInt("frameCount")-1);
                    if(step.has("durationMs"))
                        SketchDocument.integer(step,"durationMs",
                                SketchAgentTimelineScrub.MIN_DURATION_MS,
                                SketchAgentTimelineScrub.MAX_DURATION_MS);
                    break;
                case "layer":
                    SketchDocument.fields(step,"action,layerId");
                    SketchDocument.layer(document,SketchDocument.id(step,"layerId"));
                    break;
                case "object":
                    SketchDocument.fields(step,"action,objectId");
                    SketchDocument.object(document,SketchDocument.id(step,"objectId"));
                    break;
                case "camera":
                    SketchDocument.fields(step,"action,zoom,centerX,centerY,animationMs");
                    SketchDocument.number(step,"zoom",1,12);
                    SketchDocument.number(step,"centerX",0,1);
                    SketchDocument.number(step,"centerY",0,1);
                    if(step.has("animationMs"))
                        SketchDocument.integer(step,"animationMs",0,800);
                    break;
                case "onion":
                    SketchDocument.fields(step,"action,enabled,range,alpha");
                    SketchDocument.bool(step,"enabled");
                    if(step.has("range"))SketchDocument.integer(step,"range",1,3);
                    if(step.has("alpha"))SketchDocument.integer(step,"alpha",0,128);
                    break;
                case "grid":
                    SketchDocument.fields(step,"action,enabled");
                    SketchDocument.bool(step,"enabled");
                    break;
                case "wait":
                    SketchDocument.fields(step,"action,durationMs");
                    SketchDocument.integer(step,"durationMs",50,1500);
                    break;
                case "xsheet":
                    SketchDocument.fields(step,"action,frameIndex,layerId");
                    SketchDocument.integer(step,"frameIndex",0,document.getInt("frameCount")-1);
                    JSONObject xsLayer=SketchDocument.layer(document,SketchDocument.id(step,"layerId"));
                    if(!xsLayer.getBoolean("visible"))
                        throw SketchDocument.bad("Agent Xsheet inspection needs a visible authored level");
                    break;
                case "object_drag":
                case "node_drag":
                    SketchAgentGestureOps.plan(document,step);
                    if(i!=steps.length()-1)
                        throw SketchDocument.bad("A source-changing human gesture must be the final visible stage action");
                    break;
                case "shape_curve":
                    SketchDocument.fields(step,"action,objectId");
                    JSONObject shape=SketchDocument.object(document,SketchDocument.id(step,"objectId"));
                    if(!"path".equals(shape.optString("kind")))
                        throw SketchDocument.bad("Shape curve inspector requires an editable Bézier path");
                    break;
                case "transform_curve":
                    SketchDocument.fields(step,"action,layerId,objectId");
                    if(step.has("layerId")&&step.has("objectId"))
                        throw SketchDocument.bad("Select a layer or an object for transform curves, not both");
                    if(step.has("layerId"))
                        SketchDocument.layer(document,SketchDocument.id(step,"layerId"));
                    if(step.has("objectId"))
                        SketchDocument.object(document,SketchDocument.id(step,"objectId"));
                    break;
                default:
                    SketchDocument.fields(step,"action");break;
            }
            if(("shape_curve".equals(action)||"transform_curve".equals(action)||"xsheet".equals(action))&&i!=steps.length()-1)
                throw SketchDocument.bad("A native curve inspector must be the final visible stage step");
            prepared.put(SketchDocument.copy(step));
        }
        JSONObject normalized=new JSONObject()
                .put("sketchId",id)
                .put("operationId",program.getString("operationId"))
                .put("expectedRevision",revision)
                .put("steps",prepared);
        if(normalized.toString().getBytes(StandardCharsets.UTF_8).length>MAX_SCRIPT_BYTES)
            throw SketchDocument.bad("Stage program exceeds 8 KiB");
        return normalized;
    }

    public JSONObject enqueue(Context context,JSONObject raw)throws Exception {
        if(raw==null)throw SketchDocument.bad("Stage request required");
        SketchDocument.fields(raw,"sketchId,operationId,expectedRevision,steps");
        String existingId=SketchDocument.id(raw,"sketchId");
        String existingOperation=SketchDocument.id(raw,"operationId");
        JSONObject earlier=lookup(existingId,existingOperation);
        if(earlier!=null){
            String requestHash=SketchDocument.sha256(
                    SketchDocument.canonical(raw).getBytes(StandardCharsets.UTF_8));
            if(!requestHash.equals(earlier.getString("fingerprint")))
                throw new IllegalStateException("SKETCH_STAGE_OPERATION_CONFLICT");
            return receipt(earlier,true);
        }
        JSONObject doc;
        try(SketchProjectStore store=new SketchProjectStore(context)) {
            String id=SketchDocument.id(raw,"sketchId");
            doc=store.get(id);
        }
        JSONObject program=normalize(raw,doc);
        String id=program.getString("sketchId"),
                operation=program.getString("operationId");
        String fingerprint=SketchDocument.sha256(
                SketchDocument.canonical(program).getBytes(StandardCharsets.UTF_8));
        long now=System.currentTimeMillis();
        db.beginTransaction();
        try{
            JSONObject existing=lookup(id,operation);
            if(existing!=null){
                if(!fingerprint.equals(existing.getString("fingerprint")))
                    throw new IllegalStateException("SKETCH_STAGE_OPERATION_CONFLICT");
                db.setTransactionSuccessful();
                return receipt(existing,true);
            }
            expire(now);
            try(Cursor c=db.rawQuery(
                    "SELECT COUNT(*) FROM stage_commands WHERE state IN ('queued','running')",
                    null)){
                if(c.moveToFirst()&&c.getInt(0)>=MAX_PENDING)
                    throw new IllegalStateException("SKETCH_STAGE_QUEUE_FULL");
            }
            ContentValues values=new ContentValues();
            values.put("sketch_id",id);
            values.put("operation_id",operation);
            values.put("fingerprint",fingerprint);
            values.put("expected_revision",program.getLong("expectedRevision"));
            values.put("steps_json",program.getJSONArray("steps").toString());
            values.put("state","queued");
            values.put("created_at_ms",now);
            values.put("updated_at_ms",now);
            db.insertOrThrow("stage_commands",null,values);
            prune();
            JSONObject saved=lookup(id,operation);
            db.setTransactionSuccessful();
            return receipt(saved,false);
        }finally{db.endTransaction();}
    }

    public JSONObject status(JSONObject args)throws Exception {
        SketchDocument.fields(args,"sketchId,operationId");
        String id=SketchDocument.id(args,"sketchId");
        String operation=SketchDocument.id(args,"operationId");
        JSONObject found=lookup(id,operation);
        if(found==null)throw SketchDocument.bad("Unknown stage operation for this drawing");
        return receipt(found,true);
    }

    /**
     * Pull exactly one foreground-visible step. A 30-second lease permits
     * harmless replay if Android kills the app before it records a receipt.
     */
    public JSONObject claim(String sketchId)throws Exception{
        SketchDocument.id(new JSONObject().put("sketchId",sketchId),"sketchId");
        long now=System.currentTimeMillis();
        db.beginTransaction();
        try{
            expire(now);
            JSONObject row=null;
            try(Cursor c=db.rawQuery(
                    "SELECT sequence,sketch_id,operation_id,fingerprint,expected_revision,steps_json,"+
                    "next_step,state,created_at_ms,updated_at_ms,result_json FROM stage_commands "+
                    "WHERE sketch_id=? AND (state='queued' OR (state='running' AND lease_until_ms<?)) "+
                    "ORDER BY sequence ASC LIMIT 1",
                    new String[]{sketchId,String.valueOf(now)})){
                if(c.moveToFirst())row=row(c);
            }
            if(row==null){db.setTransactionSuccessful();return null;}
            JSONArray steps=new JSONArray(row.getString("stepsJson"));
            int index=row.getInt("nextStep");
            if(index<0||index>=steps.length())
                throw new IllegalStateException("Stage queue cursor corrupted");
            ContentValues update=new ContentValues();
            update.put("state","running");
            update.put("lease_until_ms",now+30_000L);
            update.put("updated_at_ms",now);
            db.update("stage_commands",update,"sequence=?",
                    new String[]{Long.toString(row.getLong("sequence"))});
            db.setTransactionSuccessful();
            return new JSONObject().put("sketchId",sketchId)
                    .put("operationId",row.getString("operationId"))
                    .put("expectedRevision",row.getLong("expectedRevision"))
                    .put("stepIndex",index)
                    .put("stepCount",steps.length())
                    .put("step",steps.getJSONObject(index));
        }finally{db.endTransaction();}
    }

    /** Return an unexecuted foreground claim without claiming false progress. */
    public void release(JSONObject claim)throws Exception{
        if(claim==null)return;
        String id=SketchDocument.id(claim,"sketchId"),
                operation=SketchDocument.id(claim,"operationId");
        int step=(int)SketchDocument.integer(claim,"stepIndex",0,MAX_STEPS-1);
        db.beginTransaction();
        try{
            JSONObject row=lookup(id,operation);
            if(row!=null&&"running".equals(row.getString("state"))&&row.getInt("nextStep")==step){
                ContentValues update=new ContentValues();
                update.put("state","queued");
                update.put("lease_until_ms",0);
                update.put("updated_at_ms",System.currentTimeMillis());
                db.update("stage_commands",update,
                        "sketch_id=? AND operation_id=? AND next_step=? AND state='running'",
                        new String[]{id,operation,String.valueOf(step)});
            }
            db.setTransactionSuccessful();
        }finally{db.endTransaction();}
    }

    /** Foreground proof only; never mark queued work completed in the relay. */
    public JSONObject finish(JSONObject claimed,boolean succeeded,JSONObject evidence)throws Exception{
        String id=SketchDocument.id(claimed,"sketchId"),
                op=SketchDocument.id(claimed,"operationId");
        int index=(int)SketchDocument.integer(claimed,"stepIndex",0,MAX_STEPS-1);
        if(evidence==null)throw SketchDocument.bad("Stage execution needs an honest result");
        if(evidence.toString().length()>1200)
            throw SketchDocument.bad("Stage result is too large");
        long now=System.currentTimeMillis();
        db.beginTransaction();
        try{
            JSONObject row=lookup(id,op);
            if(row==null)throw SketchDocument.bad("Unknown stage receipt");
            if(!"running".equals(row.getString("state"))||row.getInt("nextStep")!=index)
                throw new IllegalStateException("SKETCH_STAGE_STEP_ALREADY_SETTLED");
            int count=new JSONArray(row.getString("stepsJson")).length();
            int next=index+(succeeded?1:0);
            String newState=!succeeded?"rejected":next>=count?"applied":"queued";
            ContentValues update=new ContentValues();
            update.put("state",newState);
            update.put("next_step",next);
            update.put("lease_until_ms",0);
            update.put("updated_at_ms",now);
            update.put("result_json",evidence.toString());
            db.update("stage_commands",update,"sequence=?",
                    new String[]{Long.toString(row.getLong("sequence"))});
            JSONObject done=lookup(id,op);
            db.setTransactionSuccessful();
            return receipt(done,false);
        }finally{db.endTransaction();}
    }

    private JSONObject lookup(String id,String operation)throws Exception {
        try(Cursor c=db.rawQuery(
                "SELECT sequence,sketch_id,operation_id,fingerprint,expected_revision,steps_json,"+
                "next_step,state,created_at_ms,updated_at_ms,result_json "+
                "FROM stage_commands WHERE sketch_id=? AND operation_id=?",
                new String[]{id,operation})){
            return c.moveToFirst()?row(c):null;
        }
    }
    private static JSONObject row(Cursor c)throws Exception{
        return new JSONObject()
                .put("sequence",c.getLong(0))
                .put("sketchId",c.getString(1))
                .put("operationId",c.getString(2))
                .put("fingerprint",c.getString(3))
                .put("expectedRevision",c.getLong(4))
                .put("stepsJson",c.getString(5))
                .put("nextStep",c.getInt(6))
                .put("state",c.getString(7))
                .put("createdAtMs",c.getLong(8))
                .put("updatedAtMs",c.getLong(9))
                .put("result",new JSONObject(c.getString(10)));
    }
    private static JSONObject receipt(JSONObject stored,boolean reused)throws Exception{
        return new JSONObject()
                .put("schema","videostudio.sketch-stage-receipt.v1")
                .put("sketchId",stored.getString("sketchId"))
                .put("operationId",stored.getString("operationId"))
                .put("expectedRevision",stored.getLong("expectedRevision"))
                .put("stepCount",new JSONArray(stored.getString("stepsJson")).length())
                .put("completedSteps",stored.getInt("nextStep"))
                .put("state",stored.getString("state"))
                .put("queued",Set.of("queued","running").contains(stored.getString("state")))
                .put("applied", "applied".equals(stored.getString("state")))
                .put("reusedOperationId",reused)
                .put("lastStepResult",stored.getJSONObject("result"))
                .put("createdAtMs",stored.getLong("createdAtMs"))
                .put("updatedAtMs",stored.getLong("updatedAtMs"))
                .put("documentModified","applied".equals(stored.getString("state"))&&
                        stored.getJSONObject("result").optBoolean("documentModified",false))
                .put("galleryAccess",false);
    }
    private void expire(long now){
        ContentValues old=new ContentValues();
        old.put("state","expired");
        old.put("updated_at_ms",now);
        old.put("lease_until_ms",0);
        old.put("result_json","{\"reason\":\"Foreground stage program expired after 24h\",\"appliedOnScreen\":false}");
        db.update("stage_commands",old,
                "state IN ('queued','running') AND created_at_ms<?",
                new String[]{String.valueOf(now-MAX_AGE_MS)});
    }
    private void prune(){
        db.execSQL("DELETE FROM stage_commands WHERE state NOT IN ('queued','running') "+
                "AND sequence NOT IN (SELECT sequence FROM stage_commands "+
                "ORDER BY sequence DESC LIMIT 128)");
    }
}
