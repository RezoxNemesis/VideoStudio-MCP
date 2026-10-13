package com.rezoxnemesis.videostudio;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/** App-private request journal and a distinct Android-Keystore-protected runner credential. */
public final class OpenPipelineStore {
    static final Object LOCK=new Object();
    static final int MAX_RECORD_BYTES=1024*1024;
    private static final String KEY="videostudio_open_pipeline_runner_v1";
    private final SharedPreferences preferences;
    private final File root;
    public OpenPipelineStore(Context context){preferences=context.getSharedPreferences("videostudio_open_pipeline_v1",Context.MODE_PRIVATE);root=new File(context.getFilesDir(),"open_pipeline");}
    public static String endpoint(String value)throws Exception {
        if(value==null||value.length()>1024)throw new IllegalArgumentException("Runner HTTPS endpoint required");
        URI uri=new URI(value.trim());
        if(!"https".equalsIgnoreCase(uri.getScheme())||uri.getHost()==null||uri.getUserInfo()!=null||uri.getQuery()!=null||uri.getFragment()!=null||uri.getPort()==0)throw new IllegalArgumentException("Use your own HTTPS runner without embedded credentials, query or fragment");
        String result=uri.toASCIIString();while(result.endsWith("/"))result=result.substring(0,result.length()-1);return result;
    }
    public JSONObject configure(String endpoint,String bearer)throws Exception {
        String checked=endpoint(endpoint);if(bearer==null||bearer.length()<16||bearer.length()>4096||!bearer.matches("[A-Za-z0-9_.~+/-]+=*"))throw new IllegalArgumentException("Runner bearer must be 16-4096 safe token characters");
        String encrypted=encrypt(bearer);synchronized(LOCK){if(!preferences.edit().putString("endpoint",checked).putString("credential",encrypted).commit())throw new IllegalStateException("Cannot persist runner configuration");}return configuration();
    }
    public JSONObject configuration()throws Exception {
        String saved=preferences.getString("endpoint","");boolean present=!saved.isEmpty()&&!preferences.getString("credential","").isEmpty();
        boolean ready=false;if(present){try{ready=!bearer().isEmpty();}catch(Exception unavailable){/* Lost Keystore access requires explicit reconnection. */}}
        return new JSONObject().put("configured",present).put("endpoint",saved).put("credentialProtected",present).put("credentialReady",ready).put("inferenceVerified",false).put("storageDoesNotProvideCompute",true);
    }
    String configuredEndpoint()throws Exception {String result=preferences.getString("endpoint","");if(result.isEmpty())throw new IllegalStateException("RUNNER_NOT_CONFIGURED: configure your own inference computer");return endpoint(result);}
    String bearer()throws Exception {String value=preferences.getString("credential","");if(value.isEmpty())throw new IllegalStateException("RUNNER_NOT_CONFIGURED");try{return decrypt(value);}catch(Exception unavailable){throw new IllegalStateException("RUNNER_CREDENTIAL_UNAVAILABLE: reconnect your own runner");}}
    File root()throws Exception {if(!root.isDirectory()&&!root.mkdirs())throw new IllegalStateException("Cannot create pipeline journal");return root;}
    File directory(String requestId)throws Exception {return new File(root(),OpenPipelineArtifacts.id(requestId));}
    JSONObject get(String requestId)throws Exception {File dir=directory(requestId),file=new File(dir,"record.json");if(!file.isFile())throw new IllegalArgumentException("Unknown pipeline request");JSONObject value=OpenPipelineArtifacts.json(file,MAX_RECORD_BYTES);File accepted=new File(dir,"request.json");if(!requestId.equals(value.getString("requestId"))||!requestId.equals(value.getJSONObject("request").getString("requestId"))||!accepted.isFile()||!value.getString("requestSha256").equals(OpenPipelineArtifacts.hash(accepted))||!OpenPipelineOperations.canonical(value.getJSONObject("request")).equals(OpenPipelineOperations.canonical(OpenPipelineArtifacts.json(accepted))))throw new IllegalStateException("Request journal identity/checksum differs");return value;}
    long retainedBytes()throws Exception {return treeBytes(root(),0);}
    private static long treeBytes(File file,int depth)throws Exception {if(depth>32||java.nio.file.Files.isSymbolicLink(file.toPath()))throw new IllegalStateException("Pipeline journal contains an unsafe path");if(file.isFile())return file.length();long total=0;File[] children=file.listFiles();if(children!=null)for(File child:children){total=StorageBudget.saturatingAdd(total,treeBytes(child,depth+1));if(total>2L*1024*1024*1024)break;}return total;}
    void save(JSONObject value)throws Exception {synchronized(LOCK){OpenPipelineArtifacts.writeAtomic(new File(directory(value.getString("requestId")),"record.json"),value.toString().getBytes(StandardCharsets.UTF_8),MAX_RECORD_BYTES);}}
    JSONArray list()throws Exception {JSONArray out=new JSONArray();File[] files=root().listFiles(File::isDirectory);if(files!=null)for(File file:files){if(out.length()>=64)break;try{out.put(receipt(get(file.getName())));}catch(Exception invalid){/* Incomplete directories are not accepted jobs. */}}return out;}
    static JSONObject receipt(JSONObject value)throws Exception {JSONObject out=new JSONObject();for(String key:new String[]{"requestId","projectId","sourceProjectUpdatedAt","backend","requestSha256","archiveSha256","archiveBytes","state","stage","code","heartbeatAt","deadlineAt","progress","jobId","createdAt","updatedAt","resultSha256","resultBytes","outputProjectId","nativeInstallations","importReady","driveRequestArchived","driveResultArchived"})if(value.has(key))out.put(key,value.get(key));out.put("nativeRendered",false);out.put("cinematicAcceptance",false).put("originalImageReconstruction",false).put("producerEvidenceVerified",false);if(value.has("request")&&"cogvideox".equals(value.optString("backend")))out.put("conditioningMode",value.getJSONObject("request").getJSONObject("controls").optString("mode","text_to_video"));return out;}
    private static SecretKey key(boolean create)throws Exception {
        KeyStore store=KeyStore.getInstance("AndroidKeyStore");store.load(null);if(store.containsAlias(KEY))return ((KeyStore.SecretKeyEntry)store.getEntry(KEY,null)).getSecretKey();
        if(!create)throw new IllegalStateException("Protected runner key unavailable");
        KeyGenerator generator=KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore");generator.init(new KeyGenParameterSpec.Builder(KEY,KeyProperties.PURPOSE_ENCRYPT|KeyProperties.PURPOSE_DECRYPT).setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build());return generator.generateKey();
    }
    private static String encrypt(String value)throws Exception {Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.ENCRYPT_MODE,key(true));return Base64.encodeToString(cipher.getIV(),Base64.NO_WRAP)+":"+Base64.encodeToString(cipher.doFinal(value.getBytes(StandardCharsets.UTF_8)),Base64.NO_WRAP);}
    private static String decrypt(String value)throws Exception {String[] parts=value.split(":",-1);if(parts.length!=2)throw new IllegalStateException("Protected credential invalid");byte[] iv=Base64.decode(parts[0],Base64.NO_WRAP);if(iv.length!=12)throw new IllegalStateException("Protected credential invalid");Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.DECRYPT_MODE,key(false),new GCMParameterSpec(128,iv));return new String(cipher.doFinal(Base64.decode(parts[1],Base64.NO_WRAP)),StandardCharsets.UTF_8);}
}
