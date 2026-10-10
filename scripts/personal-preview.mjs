/** Local-only QA harness. Synthetic credentials and fixtures, never deployed. */
import http from 'node:http';
import {readFile,writeFile,mkdir,rename} from 'node:fs/promises';
import {fixture,MemoryStorage,LegacyState} from './personal-fixture.mjs';
import {withPersonalStudio} from '../src/personal-state.js';
import {handlePersonal} from '../src/personal-http.js';
const directory=new URL('../.work/',import.meta.url),file=new URL('preview-state.json',directory);
await mkdir(directory,{recursive:true});
const f=await fixture();let initial=f.storage.data;
try {initial=new Map(JSON.parse(await readFile(file,'utf8')));}catch{}
class DiskStorage extends MemoryStorage {
  async persist(){const snapshot=JSON.stringify([...this.data]);this.diskChain=(this.diskChain||Promise.resolve()).catch(()=>{}).then(async()=>{const tmp=new URL('preview-state.tmp.json',directory);await writeFile(tmp,snapshot);await rename(tmp,file);});await this.diskChain;}
  async put(key,value){await super.put(key,value);await this.persist();}
  async delete(key){const result=await super.delete(key);await this.persist();return result;}
  async transaction(fn){const result=await super.transaction(fn);await this.persist();return result;}
}
const storage=new DiskStorage(initial),state=new(withPersonalStudio(LegacyState))({storage},{});
if(!(await state.personalSnapshot(f.authA)).projects.length){
  for(const [name,brief,count] of [['Afterglow','A city unwinds. A story begins.',4],['Quiet water','A slower kind of story, told by the shore.',3],['The small things','Finding a little wonder in the everyday.',5]]){
    const p=await state.personalExecute(f.authA,'project.create',{name,brief,shots:Array.from({length:count},(_,i)=>({title:'Sequence '+(i+1),description:'A planned moment in the story.',durationSeconds:3}))},'seed-'+name.replaceAll(' ','-'));
  }
}
const server=http.createServer(async(req,res)=>{
  try{
    const url='http://'+req.headers.host+req.url;
    if(req.url==='/favicon.ico'){res.writeHead(204);res.end();return;}
    if(req.url==='/'){res.writeHead(200,{'content-type':'text/html'});res.end('<p>Legacy editor is not included in this isolated QA harness. The deployed route delegates to the existing VideoStudio editor.</p>');return;}
    const chunks=[];for await(const chunk of req)chunks.push(chunk);
    const body=Buffer.concat(chunks),request=new Request(url,{method:req.method,headers:req.headers,...(body.length?{body}:{} )});
    const response=await handlePersonal(request,{},state);
    if(!response){res.writeHead(404);res.end();return;}
    res.writeHead(response.status,Object.fromEntries(response.headers));res.end(Buffer.from(await response.arrayBuffer()));
  }catch(e){res.writeHead(500,{'content-type':'application/json'});res.end(JSON.stringify({error:e.message}));}
});
server.listen(8790,'127.0.0.1',()=>console.log('Local synthetic QA server ready: http://127.0.0.1:8790/personal/'));
