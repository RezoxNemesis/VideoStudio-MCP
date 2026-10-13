package com.rezoxnemesis.videostudio;

import org.json.JSONObject;
import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;

/** Owner-configured authenticated HTTPS. Redirects never receive the runner credential. */
public final class OpenPipelineClient {
    private final String endpoint,bearer;
    OpenPipelineClient(OpenPipelineStore store)throws Exception {endpoint=store.configuredEndpoint();bearer=store.bearer();}
    String endpoint(){return endpoint;}
    private HttpURLConnection connection(String route,String method)throws Exception {
        HttpURLConnection connection=(HttpURLConnection)new URL(endpoint+route).openConnection();connection.setRequestMethod(method);connection.setInstanceFollowRedirects(false);connection.setConnectTimeout(15000);connection.setReadTimeout(60000);connection.setRequestProperty("Authorization","Bearer "+bearer);connection.setRequestProperty("Accept","application/json");return connection;
    }
    private static void success(HttpURLConnection connection)throws Exception {int code=connection.getResponseCode();if(code<200||code>=300)throw new IllegalStateException("RUNNER_HTTP_"+code+": self-hosted runner did not complete this operation");}
    JSONObject capabilities()throws Exception {return json("/v1/capabilities","GET");}
    JSONObject status(String jobId)throws Exception {return json("/v1/jobs/"+OpenPipelineArtifacts.id(jobId),"GET");}
    JSONObject cancel(String jobId)throws Exception {return json("/v1/jobs/"+OpenPipelineArtifacts.id(jobId)+"/cancel","POST");}
    private JSONObject json(String route,String method)throws Exception {HttpURLConnection connection=connection(route,method);try{success(connection);try(InputStream in=connection.getInputStream()){return OpenPipelineArtifacts.json(in);}}finally{connection.disconnect();}}
    JSONObject submit(File archive,String requestId,String archiveSha)throws Exception {
        if(!archive.isFile()||archive.length()>OpenPipelineArtifacts.MAX_ARCHIVE||!OpenPipelineArtifacts.sha(archiveSha).equals(OpenPipelineArtifacts.hash(archive)))throw new IllegalArgumentException("Immutable request archive changed");
        HttpURLConnection connection=connection("/v1/jobs","POST");connection.setDoOutput(true);connection.setFixedLengthStreamingMode(archive.length());connection.setRequestProperty("Content-Type","application/vnd.videostudio.vsjob");connection.setRequestProperty("Idempotency-Key",OpenPipelineArtifacts.id(requestId));connection.setRequestProperty("X-VideoStudio-SHA256",archiveSha);
        try{try(InputStream in=new FileInputStream(archive);OutputStream out=connection.getOutputStream()){if(OpenPipelineArtifacts.copy(in,out,archive.length())!=archive.length())throw new IOException("Incomplete request upload");}success(connection);try(InputStream in=connection.getInputStream()){return OpenPipelineArtifacts.json(in);}}finally{connection.disconnect();}
    }
    /** Descriptor is persisted before a bounded Range-capable transfer begins. */
    JSONObject resultDescriptor(String jobId)throws Exception {
        HttpURLConnection connection=connection("/v1/jobs/"+OpenPipelineArtifacts.id(jobId)+"/result","GET");
        try{success(connection);long bytes=connection.getContentLengthLong();String sha=OpenPipelineArtifacts.sha(connection.getHeaderField("X-VideoStudio-SHA256"));if(bytes<1||bytes>OpenPipelineArtifacts.MAX_ARCHIVE)throw new IllegalArgumentException("Runner result size missing or exceeds limit");return new JSONObject().put("bytes",bytes).put("sha256",sha);}
        finally{connection.disconnect();}
    }
    HttpURLConnection resultConnection(String jobId,long offset,String etag,String lastModified)throws Exception {
        HttpURLConnection connection=connection("/v1/jobs/"+OpenPipelineArtifacts.id(jobId)+"/result","GET");connection.setRequestProperty("Accept","application/vnd.videostudio.vsresult");if(offset>0){connection.setRequestProperty("Range","bytes="+offset+"-");if(!etag.isEmpty())connection.setRequestProperty("If-Range",etag);else if(!lastModified.isEmpty())connection.setRequestProperty("If-Range",lastModified);}return connection;
    }
    String resultUrl(String jobId){return endpoint+"/v1/jobs/"+OpenPipelineArtifacts.id(jobId)+"/result";}
}
