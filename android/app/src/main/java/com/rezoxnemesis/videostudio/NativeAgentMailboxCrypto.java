package com.rezoxnemesis.videostudio;

import org.json.JSONArray;
import org.json.JSONObject;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.*;
import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/** Authenticated wire format. Never logs or publishes the device-owned key. */
public final class NativeAgentMailboxCrypto {
    public static final int MAX_PLAIN=2*1024*1024, MAX_OUTER=3*1024*1024;
    public static final String PACKET="videostudio.agent.packet.v1", COMMANDS="videostudio.agent.commands.v1";
    public static final String RECEIPT="videostudio.agent.receipt.v1", ENVELOPE="videostudio.agent.receipt-envelope.v1";
    private NativeAgentMailboxCrypto(){}
    static byte[] bytes(String value){return value.getBytes(StandardCharsets.UTF_8);}
    private static byte[] derive(String owner,String label)throws Exception{
        if(owner==null||owner.isEmpty())throw new IllegalArgumentException("Owner identity unavailable");
        Mac mac=Mac.getInstance("HmacSHA256");mac.init(new SecretKeySpec(bytes(owner),"HmacSHA256"));return mac.doFinal(bytes(label));
    }
    public static String channel(String owner)throws Exception{return hex(derive(owner,"VideoStudio agent channel v1")).substring(0,32);}
    public static String sha256(String text)throws Exception{return hex(MessageDigest.getInstance("SHA-256").digest(bytes(text)));}
    private static String hex(byte[] value){StringBuilder out=new StringBuilder();for(byte b:value)out.append(String.format(Locale.ROOT,"%02x",b&255));return out.toString();}
    private static String b64(byte[] b){return java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(b);}
    private static byte[] unb64(String text)throws Exception{if(!text.matches("[A-Za-z0-9_-]+"))throw new IllegalArgumentException("Invalid encoded bytes");byte[] b=java.util.Base64.getUrlDecoder().decode(text);if(!b64(b).equals(text))throw new IllegalArgumentException("Noncanonical bytes");return b;}
    private static byte[] crypt(int mode,String owner,byte[] nonce,byte[] value,String aad)throws Exception{
        if(nonce.length!=12)throw new IllegalArgumentException("Invalid nonce");
        Cipher c=Cipher.getInstance("AES/GCM/NoPadding");c.init(mode,new SecretKeySpec(derive(owner,"VideoStudio agent encryption v1"),"AES"),new GCMParameterSpec(128,nonce));c.updateAAD(bytes(aad));return c.doFinal(value);
    }
    private static JSONObject encrypt(String owner,JSONObject plain,String schema,String aad,String id)throws Exception{
        return encryptWithLimit(owner,plain,schema,aad,id,MAX_PLAIN);
    }
    private static JSONObject encryptWithLimit(String owner,JSONObject plain,String schema,String aad,String id,int limit)throws Exception{
        byte[] value=bytes(plain.toString());if(value.length>limit)throw new IllegalArgumentException("Payload exceeds limit");byte[] nonce=new byte[12];new SecureRandom().nextBytes(nonce);
        JSONObject out=new JSONObject().put("schema",schema).put("channel",channel(owner)).put("nonce",b64(nonce)).put("ciphertext",b64(crypt(Cipher.ENCRYPT_MODE,owner,nonce,value,aad)));
        if(id!=null)out.put("commandId",id);return out;
    }
    public static JSONObject encodePacket(String owner,JSONObject plain)throws Exception{return encrypt(owner,plain,PACKET,PACKET+":"+channel(owner),null);}
    public static JSONObject decodePacket(String owner,String envelope,long now)throws Exception{JSONObject p=decodeStoredPacket(owner,envelope);validate(p,now);return p;}
    static JSONObject decodeStoredPacket(String owner,String text)throws Exception{
        JSONObject e=parse(text,MAX_OUTER);return decrypt(owner,e,PACKET,PACKET+":"+channel(owner));
    }
    private static JSONObject decrypt(String owner,JSONObject e,String schema,String aad)throws Exception{
        if(!schema.equals(e.opt("schema"))||!channel(owner).equals(e.opt("channel")))throw new IllegalArgumentException("Envelope authentication mismatch");
        byte[] ciphertext=unb64(e.getString("ciphertext"));if(ciphertext.length<16||ciphertext.length>MAX_PLAIN+16)throw new IllegalArgumentException("Ciphertext exceeds limit");
        byte[] p=crypt(Cipher.DECRYPT_MODE,owner,unb64(e.getString("nonce")),ciphertext,aad);return parse(new String(p,StandardCharsets.UTF_8),MAX_PLAIN);
    }
    public static JSONObject encodeReceipt(String owner,String id,JSONObject plain)throws Exception{uuid(id);return encrypt(owner,plain,ENVELOPE,RECEIPT+":"+channel(owner)+":"+id,id);}
    // Local-only oversized backup; never sent through the bounded receipt wire protocol.
    static JSONObject encodePrivateResult(String owner,String id,JSONObject plain)throws Exception {
        return encryptWithLimit(owner,plain,"videostudio.agent.private-result.v1",
                "videostudio.agent.private-result.v1:"+channel(owner)+":"+id,id,Integer.MAX_VALUE);
    }
    public static JSONObject decodeReceipt(String owner,String id,JSONObject envelope)throws Exception{uuid(id);if(!id.equals(envelope.opt("commandId")))throw new IllegalArgumentException("Receipt identity mismatch");return decrypt(owner,envelope,ENVELOPE,RECEIPT+":"+channel(owner)+":"+id);}
    static void uuid(String text){if(text==null||!text.matches("(?i)[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"))throw new IllegalArgumentException("Invalid UUID");}
    public static void validate(JSONObject p,long now)throws Exception{
        if(!COMMANDS.equals(p.opt("schema")))throw new IllegalArgumentException("Unknown command schema");uuid(p.getString("packetId"));
        Object issued=p.get("issuedAtMs"),expires=p.get("expiresAtMs");if(!(issued instanceof Number)||!(expires instanceof Number))throw new IllegalArgumentException("Invalid packet time");
        long from=p.getLong("issuedAtMs"),to=p.getLong("expiresAtMs");if(((Number)issued).doubleValue()!=from||((Number)expires).doubleValue()!=to||from<=0||from>now+300000L||to<=now||to<=from||to-from>7L*24*60*60*1000)throw new IllegalArgumentException("Expired or invalid packet lifetime");
        JSONArray commands=p.getJSONArray("commands");if(commands.length()<1||commands.length()>128)throw new IllegalArgumentException("Invalid command count");Set<String> ids=new HashSet<>();
        for(int i=0;i<commands.length();i++){
            JSONObject c=commands.getJSONObject(i);String id=c.getString("id");uuid(id);if(!ids.add(id))throw new IllegalArgumentException("Duplicate command ID");
            Object action=c.get("action");if(!(action instanceof String)||!((String)action).matches("[a-z][a-z0-9_]{0,95}"))throw new IllegalArgumentException("Invalid action");
            JSONObject params=c.getJSONObject("parameters");if(bytes(params.toString()).length>512*1024)throw new IllegalArgumentException("Parameters exceed limit");
            Iterator<String> fields=c.keys();while(fields.hasNext()){String k=fields.next();if(!Arrays.asList("id","action","parameters").contains(k))throw new IllegalArgumentException("Untrusted command metadata");}
            Iterator<String> keys=params.keys();while(keys.hasNext())if(keys.next().startsWith("_"))throw new IllegalArgumentException("Reserved parameter");
            NativeWorkbenchBridge.canonicalCommand(c);
        }
    }
    /** Bound JSON nesting before the platform recursive JSON parser is invoked. */
    static JSONObject parse(String text,int limit)throws Exception{
        if(text==null||bytes(text).length>limit)throw new IllegalArgumentException("JSON exceeds limit");int depth=0;boolean quoted=false,escape=false;
        for(int i=0;i<text.length();i++){char c=text.charAt(i);if(quoted){if(escape)escape=false;else if(c=='\\')escape=true;else if(c=='"')quoted=false;}else if(c=='"')quoted=true;else if(c=='{'||c=='['){if(++depth>64)throw new IllegalArgumentException("JSON nesting exceeds limit");}else if(c=='}'||c==']'){if(--depth<0)throw new IllegalArgumentException("Invalid JSON");}}
        if(quoted||depth!=0)throw new IllegalArgumentException("Invalid JSON");return new JSONObject(text);
    }
    static String canonical(Object value)throws Exception{
        if(value instanceof JSONObject){JSONObject o=(JSONObject)value;List<String> keys=new ArrayList<>();Iterator<String> it=o.keys();while(it.hasNext())keys.add(it.next());Collections.sort(keys);StringBuilder s=new StringBuilder("{");for(String k:keys){if(s.length()>1)s.append(',');s.append(JSONObject.quote(k)).append(':').append(canonical(o.get(k)));}return s.append('}').toString();}
        if(value instanceof JSONArray){JSONArray a=(JSONArray)value;StringBuilder s=new StringBuilder("[");for(int i=0;i<a.length();i++){if(i>0)s.append(',');s.append(canonical(a.get(i)));}return s.append(']').toString();}
        return value instanceof String?JSONObject.quote((String)value):String.valueOf(value);
    }
    public static JSONArray receiptPages(JSONObject envelope)throws Exception{
        String text=envelope.toString(),hash=sha256(text),id=envelope.getString("commandId");int total=(text.length()+32767)/32768;JSONArray out=new JSONArray();
        for(int i=0;i<total;i++)out.put(new JSONObject().put("commandId",id).put("index",i).put("total",total).put("receiptSha256",hash).put("fragment",text.substring(i*32768,Math.min(text.length(),(i+1)*32768))));return out;
    }
}
