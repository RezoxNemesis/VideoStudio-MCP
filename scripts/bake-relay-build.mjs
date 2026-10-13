import {createHash} from 'node:crypto';
import {execFileSync} from 'node:child_process';
import {mkdir,readdir,readFile,writeFile} from 'node:fs/promises';
const root=new URL('../',import.meta.url),sourceRoot=new URL('src/',root);
async function files(dir,prefix=''){const paths=[];for(const entry of (await readdir(dir,{withFileTypes:true})).sort((a,b)=>a.name.localeCompare(b.name))){if(entry.isDirectory())paths.push(...await files(new URL(entry.name+'/',dir),prefix+entry.name+'/'));else if(entry.isFile())paths.push(prefix+entry.name);}return paths;}
const sha=createHash('sha256');for(const path of await files(sourceRoot)){sha.update(path+'\0');sha.update(await readFile(new URL(path,sourceRoot)));sha.update('\0');}
for(const path of ['wrangler.jsonc','package.json','package-lock.json']){sha.update(path+'\0');sha.update(await readFile(new URL(path,root)).catch(error=>{if(error.code==='ENOENT'&&path==='package-lock.json')return Buffer.from('[no lockfile in source checkout]');throw error;}));sha.update('\0');}
let commit=null,dirty=null;try{commit=execFileSync('git',['rev-parse','HEAD'],{cwd:root,encoding:'utf8'}).trim();dirty=execFileSync('git',['status','--porcelain','--untracked-files=normal','--','src','scripts','wrangler.jsonc','package.json','package-lock.json'],{cwd:root,encoding:'utf8'}).trim().length>0;}catch{}
const build={schema:'videostudio.relay-build.v1',commit:/^[a-f0-9]{40}$/.test(commit||'')?commit:null,sourceTreeSha256:sha.digest('hex'),sourceDirty:dirty};
const destination=new URL('.generated/relay-build.js',root);await mkdir(new URL('.generated/',root),{recursive:true});const text='// Generated source identity; no credentials or owner metadata.\nexport default Object.freeze('+JSON.stringify(build)+');\n';if(await readFile(destination,'utf8').catch(()=>null)!==text)await writeFile(destination,text);
