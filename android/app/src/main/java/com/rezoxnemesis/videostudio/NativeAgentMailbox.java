package com.rezoxnemesis.videostudio;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import org.json.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;

/** Independent GitHub task delivery; receipts use the existing direct metadata carrier. */
public final class NativeAgentMailbox {
    public interface Receiver { void receive(JSONObject command); }
    public interface Validator { boolean allowed(JSONObject command) throws Exception; }
    private static final String RAW="https://raw.githubusercontent.com/RezoxNemesis/VideoStudio-MCP/videostudio-agent-mailbox/mailbox/";
    private static final int RECEIPT_SWEEP_REQUESTS=16,RECEIPT_SWEEP_BYTES=640*1024;
    private static final long RECEIPT_SWEEP_MS=6000;
    private final String owner,channel;
    private final NativeAgentMailboxStore store;
    private final SharedPreferences prefs;
    private final BooleanSupplier paused;
    private final Receiver receiver;
    private final Validator validator;
    private final CommandJournal journal;
    private final Handler main=new Handler(Looper.getMainLooper());
    private final ScheduledExecutorService polling=Executors.newSingleThreadScheduledExecutor();
    private final ExecutorService delivery=Executors.newSingleThreadExecutor();
    private final ScheduledExecutorService receipts=Executors.newSingleThreadScheduledExecutor();
    private final ScheduledExecutorService receiptTimeouts=Executors.newSingleThreadScheduledExecutor();
    private final Set<HttpURLConnection> connections=ConcurrentHashMap.newKeySet();
    private final Set<String> dispatched=ConcurrentHashMap.newKeySet();
    private java.util.function.Function<String,HttpURLConnection> connectionFactory=address->{
        try{return (HttpURLConnection)new URL(address).openConnection();}
        catch(IOException error){throw new java.io.UncheckedIOException(error);}
    };
    private final Map<String,JSONObject> pendingPersistence=new ConcurrentHashMap<>();
    private volatile boolean running,closed;
    private String etag="";
    private JSONArray index=new JSONArray();

    public NativeAgentMailbox(Context context,String owner,BooleanSupplier paused,Receiver receiver,Validator validator)throws Exception{
        this.owner=owner;this.channel=NativeAgentMailboxCrypto.channel(owner);this.paused=paused;this.receiver=receiver;this.validator=validator;
        prefs=context.getSharedPreferences("videostudio_agent_mailbox_v1",Context.MODE_PRIVATE);
        store=new NativeAgentMailboxStore(new File(context.getNoBackupFilesDir(),"agent-mailbox-v1"),owner);journal=new CommandJournal(context);
    }
    public synchronized void start(){if(running||closed)return;running=true;prefs.edit().putBoolean("runningReported",true).putLong("observedAtMs",System.currentTimeMillis()).apply();polling.scheduleWithFixedDelay(this::tick,0,5,TimeUnit.SECONDS);receipts.scheduleWithFixedDelay(this::flushReceipts,0,3,TimeUnit.SECONDS);}
    public synchronized void stop(){running=false;closed=true;polling.shutdownNow();delivery.shutdownNow();receipts.shutdownNow();receiptTimeouts.shutdownNow();for(HttpURLConnection c:connections)c.disconnect();connections.clear();prefs.edit().putBoolean("connected",false).putBoolean("runningReported",false).putLong("observedAtMs",System.currentTimeMillis()).apply();}
    /** Bounded saved observations, usable from registration without opening the mailbox ledger. */
    public static JSONObject savedHealth(Context context){
        return healthFromPrefs(context.getApplicationContext().getSharedPreferences("videostudio_agent_mailbox_v1",Context.MODE_PRIVATE));
    }
    private static JSONObject healthFromPrefs(SharedPreferences saved){
        JSONObject out=new JSONObject();
        try{
            out.put("schema","videostudio.agent.health.v1");
            for(String key:new String[]{"runningReported","paused","connected"})out.put(key,saved.getBoolean(key,false));
            for(String key:new String[]{"observedAtMs","lastPollAt","lastPollSuccessAt","lastReceiptProgressAt","receiptPublishedPages","receiptPublishedBytes","lastSweepAt","lastSweepBytes","lastSweepDurationMs"})out.put(key,saved.getLong(key,0));
            for(String key:new String[]{"receiptNextPage","receiptTotalPages","lastSweepRequests"})out.put(key,saved.getInt(key,0));
            for(String key:new String[]{"lastError","lastReceiptState","lastReceiptError","receiptCommandId","lastSweepLimit"}){
                String value=saved.getString(key,"");out.put(key,value.substring(0,Math.min(160,value.length())));
            }
            long age=System.currentTimeMillis()-saved.getLong("lastPollAt",0);
            out.put("pollFresh",saved.getBoolean("connected",false)&&saved.getLong("lastPollAt",0)>0&&age>=0&&age<30000);
            out.put("sweepMaxRequests",RECEIPT_SWEEP_REQUESTS).put("sweepMaxBytes",RECEIPT_SWEEP_BYTES).put("sweepMaxDurationMs",RECEIPT_SWEEP_MS);
        }catch(JSONException ignored){}
        return out;
    }
    /** Current lifecycle flags plus the same credential-free persisted observations. */
    public JSONObject health(){JSONObject out=healthFromPrefs(prefs);try{out.put("runningReported",running).put("paused",paused.getAsBoolean());}catch(JSONException ignored){}return out;}
    public boolean owns(String id){return id!=null&&store.owns(id);}
    public JSONObject receipt(String id)throws Exception{return store.receipt(id);}
    /** Returns ciphertext only. Caller authenticates/decrypts using its existing AppProtocol. */
    public static JSONObject readSavedReceipt(Context context,String id)throws Exception{
        NativeAgentMailboxCrypto.uuid(id);
        android.util.AtomicFile file=new android.util.AtomicFile(new File(context.getNoBackupFilesDir(),"agent-mailbox-v1/"+id+".receipt"));
        if(!file.getBaseFile().exists()&&!new File(file.getBaseFile()+".bak").exists())return null;
        try(InputStream in=file.openRead()){
            JSONObject envelope=NativeAgentMailboxCrypto.parse(readBounded(in,NativeAgentMailboxCrypto.MAX_OUTER),NativeAgentMailboxCrypto.MAX_OUTER);
            if(!id.equals(envelope.optString("commandId")))throw new IOException("Saved receipt identity mismatch");
            return envelope;
        }
    }
    /** Check again after the main/authoring executor handoff, before any native effect. */
    public boolean allowExecution(JSONObject command){
        String id=command.optString("id"),state=store.state(id);
        if("complete".equals(state)||pendingPersistence.containsKey(id))return false;
        // Admission is durable native work; an expiring transport packet does not cancel it.
        if("admitted".equals(state))return true;
        long expires=command.optLong("_mailboxExpiresAtMs",0L);
        if(expires>0&&System.currentTimeMillis()>=expires){
            try{complete(command,new JSONObject().put("ok",false).put("error","PACKET_EXPIRED_BEFORE_EXECUTION")
                    .put("nativeEffectsStarted",false),"failed");}catch(Exception ignored){}
            return false;
        }
        return true;
    }

    public void accept(String text,long now)throws Exception{
        JSONObject p=NativeAgentMailboxCrypto.decodePacket(owner,text,now);JSONArray all=p.getJSONArray("commands");
        // Whole-packet structural/auth preflight precedes every semantic/privacy admission.
        for(int i=0;i<all.length();i++)if(!validator.allowed(all.getJSONObject(i)))throw new IllegalArgumentException("Packet contains unavailable or forbidden native action");
        store.accept(text,now);wakeDispatch();
    }
    public void holdBeforeExecution(JSONObject command){store.requeue(command.optString("id"));dispatched.remove(command.optString("id"));wakeDispatch();}
    public void admitted(JSONObject command){store.admitted(command.optString("id"));dispatched.add(command.optString("id"));wakeDispatch();}
    public void complete(JSONObject command,JSONObject result,String status){
        String id=command.optString("id");try{
            if(ExecutionTruthPolicy.isDeferredResult(result)){admitted(command);return;}
            if(!Arrays.asList("completed","failed","denied","cancelled").contains(status))throw new IllegalArgumentException("Receipt is not terminal");
            store.complete(id,result,status);pendingPersistence.remove(id);dispatched.remove(id);wakeDispatch();prefs.edit().putString("lastCommandId",id).putString("lastReceiptState","saved privately; awaiting carrier").apply();
        }catch(Exception error){
            try{pendingPersistence.put(id,new JSONObject().put("command",command).put("result",result).put("status",status));store.admitted(id);dispatched.remove(id);wakeDispatch();}catch(Exception ignored){}
            prefs.edit().putString("lastReceiptState","private receipt persistence retry pending").putString("lastError","Receipt could not be saved; native journal retained").apply();
        }
    }
    private void tick(){
        if(!running)return;prefs.edit().putBoolean("paused",paused.getAsBoolean()).putLong("observedAtMs",System.currentTimeMillis()).apply();if(paused.getAsBoolean()){prefs.edit().putBoolean("connected",false).apply();return;}
        prefs.edit().putString("lastError","").apply();
        for(JSONObject pending:new ArrayList<>(pendingPersistence.values()))
            complete(pending.optJSONObject("command"),pending.optJSONObject("result"),pending.optString("status"));
        try{recover();dispatchPending();}catch(Exception error){error("Private mailbox recovery unavailable");}
        try{pollIndex();long now=System.currentTimeMillis();prefs.edit().putBoolean("connected",true).putLong("lastPollAt",now).putLong("lastPollSuccessAt",now).apply();}
        catch(Exception error){prefs.edit().putBoolean("connected",false).putLong("lastPollAt",System.currentTimeMillis()).apply();error("GitHub mailbox unavailable or invalid; retrying");}
        try{dispatchPending();}catch(Exception error){error("Private mailbox dispatch unavailable");}
    }
    private void recover()throws Exception{
        JSONArray pending=store.recovering();for(int i=0;i<pending.length()&&running&&!paused.getAsBoolean();i++){
            JSONObject cmd=pending.getJSONObject(i);String id=cmd.getString("id");if(dispatched.contains(id)||pendingPersistence.containsKey(id))continue;
            JSONObject terminal=journal.terminal(id);if(terminal!=null){complete(cmd,terminal.optJSONObject("result"),terminal.optString("status","failed"));continue;}
            JSONObject inflight=journal.inflight(id);
            if(inflight!=null&&!inflight.optString("jobId").isEmpty()){
                // Native journal admission predates transport TTL. Persist it before
                // the receiver checks expiry, including death between linkJob/admitted.
                store.admitted(id);dispatched.add(id);
                if(!main.post(()->{
                    if(running&&!paused.getAsBoolean()){receiver.receive(cmd);wakeDispatch();}
                    else{dispatched.remove(id);wakeDispatch();}
                })){dispatched.remove(id);wakeDispatch();}
            }else complete(cmd,new JSONObject().put("ok",false).put("error","EXECUTION_UNCERTAIN_AFTER_RESTART").put("note","Inspect native project/journal before submitting another side effect; this ID was not replayed"),"failed");
        }
    }
    private void wakeDispatch(){
        if(!running||paused.getAsBoolean()||delivery.isShutdown())return;
        try{delivery.execute(()->{try{dispatchPending();}catch(Exception unavailable){error("Private dispatch retry pending");}});}
        catch(RejectedExecutionException stopped){/* Lifecycle stop won the race; durable queue remains. */}
    }
    void dispatchPending()throws Exception{
        if(!running||paused.getAsBoolean())return;JSONObject cmd=store.next();if(cmd==null)return;String id=cmd.getString("id");
        if(!dispatched.add(id))return;
        if(!main.post(()->{
            if(!running||paused.getAsBoolean()){dispatched.remove(id);wakeDispatch();return;}
            try{if(!allowExecution(cmd))return;store.markDispatched(id);prefs.edit().putString("lastCommandId",id).apply();receiver.receive(cmd);}
            catch(Exception error){complete(cmd,new JSONObject(),"failed");}
        })){dispatched.remove(id);wakeDispatch();}
    }
    private void pollIndex()throws Exception{
        HttpURLConnection c=openFreshMailboxRead(channel+"/index.json");
        try{
            if(!etag.isEmpty())c.setRequestProperty("If-None-Match",etag);c.setRequestProperty("Cache-Control","no-cache");int code=c.getResponseCode();
            if(code==404)return;
            if(code==200){JSONObject body=NativeAgentMailboxCrypto.parse(readBounded(c.getInputStream(),256*1024),256*1024);if(!"videostudio.agent.index.v1".equals(body.optString("schema")))throw new IOException("Invalid index");JSONArray packets=body.getJSONArray("packets");if(packets.length()>4096)throw new IOException("Index exceeds limit");Set<String> ids=new HashSet<>();for(int i=0;i<packets.length();i++){String id=packets.getString(i);NativeAgentMailboxCrypto.uuid(id);if(!ids.add(id))throw new IOException("Repeated index ID");}index=packets;String tag=c.getHeaderField("ETag");etag=tag==null?"":tag;}
            else if(code!=304)throw new IOException("Index request failed");
        }finally{close(c);}
        int attempted=0;
        // The append-only index is authoritative order. A transient failure of A
        // must hold B, even across retries and process restarts.
        for(int n=0;n<index.length()&&attempted<4&&running&&!paused.getAsBoolean();n++){
            String id=index.getString(n);long now=System.currentTimeMillis();
            if(store.hasPacket(id))continue;attempted++;
            String text;
            HttpURLConnection packet=openFreshMailboxRead(channel+"/"+id+".vscmd");
            try{
                packet.setRequestProperty("Cache-Control","no-cache");
                if(packet.getResponseCode()!=200)throw new IOException("Packet request failed");
                text=readBounded(packet.getInputStream(),NativeAgentMailboxCrypto.MAX_OUTER);
            }catch(Exception unavailable){
                error("Earlier packet unavailable; preserving native command order");
                break;
            }finally{close(packet);}
            try{
                JSONObject p=NativeAgentMailboxCrypto.decodePacket(owner,text,now);
                if(!id.equals(p.getString("packetId")))throw new IllegalArgumentException("Immutable packet identity mismatch");
                accept(text,now);
            }catch(java.io.IOException|android.database.SQLException storageFailure){
                error("Private packet persistence unavailable; preserving command order");break;
            }catch(Exception invalid){
                // Immutable rejected packets never become executable later when a
                // future timestamp matures or an overwritten ciphertext appears.
                store.rejectPacket(id);
                error("Invalid, expired or forbidden immutable packet rejected without effects");
            }
        }
    }

    private void flushReceipts(){
        if(!running||paused.getAsBoolean())return;
        long started=android.os.SystemClock.elapsedRealtime(),deadline=started+RECEIPT_SWEEP_MS;
        int requests=0;long sentBytes=0;String limit="complete";
        try{
            JSONArray ids=store.pendingReceipts(System.currentTimeMillis());
            boolean[] eligible=new boolean[ids.length()];Arrays.fill(eligible,true);
            // One page per receipt per round preserves fairness. More rounds remove
            // the old three-second delay between every pair of available fragments.
            boolean remaining=ids.length()>0;
            sweep:while(remaining&&running&&!paused.getAsBoolean()){
                remaining=false;
                for(int i=0;i<ids.length();i++){
                    if(!eligible[i])continue;
                    if(!running||paused.getAsBoolean()){limit=running?"paused":"stopped";break sweep;}
                    if(requests>=RECEIPT_SWEEP_REQUESTS){limit="requests";break sweep;}
                    if(android.os.SystemClock.elapsedRealtime()>=deadline){limit="time";break sweep;}
                    String id=ids.getString(i);try{
                        JSONObject envelope=store.receipt(id);if(envelope==null)throw new IOException("Receipt unavailable");JSONArray pages=NativeAgentMailboxCrypto.receiptPages(envelope);int page=store.receiptPage(id);
                        if(page>=pages.length()){store.pageSent(id,page,true);eligible[i]=false;continue;}
                        prefs.edit().putString("receiptCommandId",id).putInt("receiptNextPage",page).putInt("receiptTotalPages",pages.length()).apply();
                        JSONObject fragment=pages.getJSONObject(page);String device="vsm_"+channel+"_"+id.replace("-","")+"_p"+page;
                        JSONObject body=new JSONObject().put("deviceId",device).put("meta",new JSONObject().put("name","Encrypted native receipt").put("platform","android-native-receipt").put("appVersion",AppProtocol.APP_VERSION).put("capabilities",new JSONArray().put("videostudio.agent.receipt-carrier.v1").put(fragment.toString())));
                        byte[] bytes=body.toString().getBytes(StandardCharsets.UTF_8);
                        if(sentBytes+bytes.length>RECEIPT_SWEEP_BYTES){limit="bytes";break sweep;}
                        long requestMs=deadline-android.os.SystemClock.elapsedRealtime();if(requestMs<=0){limit="time";break sweep;}
                        HttpURLConnection c=open(AppProtocol.BASE+"/api/device/register");ScheduledFuture<?> timeout=null;
                        requests++;sentBytes+=bytes.length;
                        try{
                            c.setConnectTimeout((int)requestMs);c.setReadTimeout((int)requestMs);
                            timeout=receiptTimeouts.schedule(c::disconnect,requestMs,TimeUnit.MILLISECONDS);
                            c.setRequestMethod("POST");c.setDoOutput(true);c.setRequestProperty("Content-Type","application/json; charset=utf-8");c.setFixedLengthStreamingMode(bytes.length);
                            try(OutputStream out=c.getOutputStream()){out.write(bytes);}
                            if(c.getResponseCode()!=200)throw new IOException("Receipt carrier unavailable");
                            JSONObject response=NativeAgentMailboxCrypto.parse(readBounded(c.getInputStream(),128*1024),128*1024);JSONObject returned=response.optJSONObject("device");
                            if(!response.optBoolean("ok")||returned==null||!device.equals(returned.optString("deviceId"))||!body.getJSONObject("meta").getJSONArray("capabilities").toString().equals(returned.optJSONArray("capabilities")==null?"":returned.getJSONArray("capabilities").toString()))throw new IOException("Receipt carrier did not preserve fragment");
                        }finally{if(timeout!=null)timeout.cancel(false);close(c);}
                        // Persist acknowledgement before another page is attempted. A
                        // lost reply retries the identical encrypted fragment and ID.
                        boolean done=page+1==pages.length();store.pageSent(id,page+1,done);store.deferReceipt(id,System.currentTimeMillis()+1000);
                        prefs.edit().putString("lastReceiptState",done?"encrypted receipt published":"encrypted receipt pages publishing").putString("lastReceiptError","")
                                .putInt("receiptNextPage",page+1).putLong("lastReceiptProgressAt",System.currentTimeMillis())
                                .putLong("receiptPublishedPages",prefs.getLong("receiptPublishedPages",0)+1)
                                .putLong("receiptPublishedBytes",prefs.getLong("receiptPublishedBytes",0)+bytes.length).apply();
                        eligible[i]=!done;remaining|=!done;
                    }catch(Exception error){eligible[i]=false;store.deferReceipt(id,System.currentTimeMillis()+30000);prefs.edit().putString("lastReceiptState","saved privately; carrier retry pending").putString("lastReceiptError","Receipt carrier unavailable or fragment acknowledgement rejected").apply();}
                }
            }
        }catch(Exception error){error("Private receipt queue unavailable");}
        finally{prefs.edit().putLong("observedAtMs",System.currentTimeMillis()).putLong("lastSweepAt",System.currentTimeMillis()).putInt("lastSweepRequests",requests).putLong("lastSweepBytes",sentBytes).putLong("lastSweepDurationMs",android.os.SystemClock.elapsedRealtime()-started).putString("lastSweepLimit",limit).apply();}
    }
    private HttpURLConnection openFreshMailboxRead(String path)throws Exception{
        // Raw GitHub may serve a five-minute CDN snapshot despite no-cache.
        // A fresh public nonce also avoids retaining a packet's cached 404.
        // Keep ETag validation; the query contains no credential or command data.
        return open(RAW+path+"?vs_read="+UUID.randomUUID());
    }
    private synchronized HttpURLConnection open(String address)throws Exception{if(!running)throw new IOException("Mailbox stopped");HttpURLConnection c=connectionFactory.apply(address);c.setConnectTimeout(12000);c.setReadTimeout(12000);c.setInstanceFollowRedirects(false);c.setRequestProperty("Accept","application/json");c.setRequestProperty("User-Agent","VideoStudio-Android/"+AppProtocol.APP_VERSION);connections.add(c);return c;}
    private void close(HttpURLConnection c){connections.remove(c);c.disconnect();}
    private void error(String message){prefs.edit().putString("lastError",message).apply();}
    static String readBounded(InputStream input,int max)throws Exception{try(InputStream in=input;ByteArrayOutputStream out=new ByteArrayOutputStream()){byte[] buf=new byte[8192];int n;while((n=in.read(buf))!=-1){if(out.size()+n>max)throw new IOException("Response exceeds limit");out.write(buf,0,n);}return out.toString("UTF-8");}}
}
