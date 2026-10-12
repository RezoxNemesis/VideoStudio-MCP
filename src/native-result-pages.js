import {canonicalCommandResultSha256} from './native-command-store.js';
const encoder=new TextEncoder();
const sha=async bytes=>Array.from(new Uint8Array(await crypto.subtle.digest('SHA-256',bytes)),value=>value.toString(16).padStart(2,'0')).join('');
export function nativeCommandSummary(command){
  if(!command)return null;
  const summary={resultExpired:command.resultExpired===true},omitted=[];
  const scalar=(target,key,value,max=256)=>{
    if(value===undefined)return;
    if(value===null||typeof value==='boolean'||typeof value==='number'&&Number.isFinite(value))target[key]=value;
    else if(typeof value==='string'&&value.length<=max&&encoder.encode(value).byteLength<=max)target[key]=value;
    else omitted.push(key);
  };
  for(const key of ['id','seq','action','status','createdAt','completedAt','protocolVersion','resultSha256'])
    scalar(summary,key,command[key],key==='id'?256:128);
  if(command.parameters&&typeof command.parameters==='object'){
    const parameters={};const visible=new Set(['projectId','sourceProjectId','assetId','transferId','handoffId','programId','name','mime','fileName']);
    const privateKeys=new Set(['base64','sourceUrl','downloadUrl','download_url','ownerKey','privateOwnerKey','authToken','accessToken']);
    let partial=false;for(const [key,value] of Object.entries(command.parameters)){
      if(privateKeys.has(key))parameters[key]='[private command payload redacted]';
      else if(visible.has(key)&&['string','boolean','number'].includes(typeof value)&&String(value).length<=256&&encoder.encode(String(value)).byteLength<=256)parameters[key]=value;
      else partial=true;
    }
    summary.parameters=parameters;summary.parametersPartial=partial;
  }
  const result=command.result;
  if(result&&typeof result==='object')for(const key of ['ok','queued','jobId','projectId','sourceProjectId','outputProjectId','outputName','code','cinematicAcceptance','cinematicQaStatus'])
    scalar(summary,key,result[key]);
  if(omitted.length){summary.summaryPartial=true;summary.omittedSummaryFields=omitted;}
  if(result!==undefined)summary.resultBytes=encoder.encode(JSON.stringify(result)).byteLength;
  return summary;
}
/** Detached, credential-redacted JSON view. Fragments concatenate into the exact full view. */
export async function nativeResultPage(command,displayResult,pageIndex=0){
  if(!Number.isSafeInteger(pageIndex)||pageIndex<0||pageIndex>255)throw new Error('Result page index must be 0..255');
  if(!command||command.resultExpired&&!command.result)return {command:nativeCommandSummary(command),resultExpired:true,resultRetained:false};
  if(command.result===undefined||command.result===null)return {command:nativeCommandSummary(command),resultRetained:false,operationStatus:command.status};
  const canonicalResultSha256=await canonicalCommandResultSha256(command.result);
  if(command.resultSha256&&command.resultSha256!==canonicalResultSha256)throw new Error('Retained result checksum conflicts with its original receipt');
  const text=JSON.stringify(displayResult),bytes=encoder.encode(text),pages=[];
  if(bytes.byteLength>2*1024*1024)throw new Error('Retained native result exceeds its bounded retrieval contract');
  for(let start=0;start<text.length;){let end=Math.min(start+8192,text.length);
    const last=text.charCodeAt(end-1);if(end<text.length&&last>=0xd800&&last<=0xdbff)end--;
    pages.push({start,text:text.slice(start,end)});start=end;
  }
  if(!pages.length)pages.push({start:0,text:''});
  if(pageIndex>=pages.length)throw new Error('Result page is outside this retained result');
  return {command:nativeCommandSummary(command),resultRetained:true,resultPaged:true,retrievalTool:'app_get_command_result_page',
    encoding:'json-text-fragment',credentialRedactedView:true,viewSha256:await sha(bytes),canonicalResultSha256,
    viewBytes:bytes.byteLength,pageIndex,pageCount:pages.length,characterOffset:pages[pageIndex].start,
    fragment:pages[pageIndex].text,completeView:pages.length===1};
}
