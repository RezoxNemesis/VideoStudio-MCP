import {PAGE,STYLE} from './personal-ui.js';
import CLIENT from '../.generated/personal-client.js';
import {fail} from './personal-state.js';
import {driveStatus,driveConnect,driveCallback,driveBackup} from './personal-drive.js';
const cookieName='__Host-vs-personal';
const security={'cache-control':'private, no-store','x-content-type-options':'nosniff','referrer-policy':'no-referrer','x-frame-options':'SAMEORIGIN','permissions-policy':'camera=(), microphone=(), geolocation=()'};
const csp="default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self' blob: data:; connect-src 'self'; media-src 'self' blob:; frame-src 'self'; object-src 'none'; base-uri 'none'; form-action 'self'; frame-ancestors 'self'";
const json=(data,status=200,extra={})=>new Response(JSON.stringify(data),{status,headers:{...security,'content-type':'application/json; charset=utf-8',...extra}});
function session(request){const match=(request.headers.get('cookie')||'').split(';').map(s=>s.trim()).find(s=>s.startsWith(cookieName+'='));return match?match.slice(cookieName.length+1):'';}
const setCookie=(value,seconds=604800)=>`${cookieName}=${value}; Path=/; HttpOnly; Secure; SameSite=Lax; Max-Age=${seconds}`;
async function body(request){if(!(request.headers.get('content-type')||'').toLowerCase().startsWith('application/json'))throw fail(415,'Use application/json.');if(Number(request.headers.get('content-length')||0)>95000)throw fail(413,'Request exceeds 95 KB.');const reader=request.body?.getReader();let size=0;const chunks=[];if(reader){while(true){const {done,value}=await reader.read();if(done)break;size+=value.byteLength;if(size>95000){await reader.cancel();throw fail(413,'Request exceeds 95 KB.');}chunks.push(value);}}const all=new Uint8Array(size);let offset=0;for(const part of chunks){all.set(part,offset);offset+=part.length;}try{const value=JSON.parse(new TextDecoder().decode(all));if(!value||typeof value!=='object'||Array.isArray(value))throw new Error();return value;}catch{throw fail(400,'Request must contain a valid JSON object.');}}
export async function handlePersonal(request,env,state){
  const u=new URL(request.url),path=u.pathname;
  if(!path.startsWith('/api/personal/')&&path!=='/personal'&&!path.startsWith('/personal/'))return null;
  try{
    if(request.method==='GET'&&(path==='/personal/'||path==='/personal'))return new Response(PAGE,{headers:{...security,'content-type':'text/html; charset=utf-8','content-security-policy':csp}});
    if(request.method==='GET'&&path==='/personal/style.css')return new Response(STYLE,{headers:{...security,'content-type':'text/css; charset=utf-8'}});
    if(request.method==='GET'&&path==='/personal/client.js')return new Response(CLIENT,{headers:{...security,'content-type':'application/javascript; charset=utf-8'}});
    if(request.method!=='GET'&&request.method!=='POST')throw fail(405,'Method not allowed.');
    if(request.method==='POST'&&request.headers.get('origin')!==u.origin)throw fail(403,'Same-origin requests are required.');
    const raw=session(request),auth={session:raw};
    if(path==='/api/personal/session'&&request.method==='POST'){const b=await body(request);const r=await state.personalCreateSession(b.ownerKey);return json({ok:true,expiresAt:r.expiresAt},200,{'set-cookie':setCookie(r.session)});}
    if(path==='/api/personal/logout'&&request.method==='POST'){await body(request);await state.personalRevokeSession(raw);return json({ok:true},200,{'set-cookie':setCookie('',0)});}
    await state.personalAuth(auth);
    if(path==='/api/personal/snapshot'&&request.method==='GET')return json(await state.personalSnapshot(auth,u.searchParams.get('q')||''));
    if(path==='/api/personal/execute'&&request.method==='POST'){const b=await body(request);return json(await state.personalExecute(auth,b.operation,b.data||{},b.requestId));}
    const project=path.match(/^\/api\/personal\/projects\/(pcp_[\w-]+)(\/export)?$/);
    if(project&&request.method==='GET')return json(project[2]?await state.personalExport(auth,project[1]):{project:await state.personalReadProject(auth,project[1])});
    if(path==='/api/personal/drive/status'&&request.method==='GET')return json(await driveStatus(state,auth,env));
    if(path==='/api/personal/drive/connect'&&request.method==='POST'){await body(request);return json(await driveConnect(state,auth,env,u.origin));}
    if(path==='/api/personal/drive/callback'&&request.method==='GET'){await driveCallback(state,auth,env,u);return new Response(null,{status:303,headers:{...security,location:u.origin+'/personal/?drive=connected#storage'}});}
    if(path==='/api/personal/drive/backup'&&request.method==='POST'){const b=await body(request);return json(await driveBackup(state,auth,env,b.projectId));}
    return json({error:'Personal workspace route not found.'},404);
  }catch(e){const status=Number.isInteger(e.status)&&e.status>=400&&e.status<600?e.status:500;return json({error:status===500?'The workspace could not complete this request. Retry without changing its requestId.':e.message},status);}
}
