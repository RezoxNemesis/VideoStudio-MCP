import test from 'node:test';
import assert from 'node:assert/strict';
import vm from 'node:vm';
import {readFile} from 'node:fs/promises';

function startBrowserScript(script) {
  let calls=0;
  const elements=new Map();
  const element=id=>elements.get(id)||elements.set(id,{hidden:false,textContent:'',value:'',dataset:{},classList:{toggle(){}},addEventListener(){},removeAttribute(){}}).get(id);
  const sandbox={document:{addEventListener(){},getElementById:element,querySelectorAll:()=>[],documentElement:{dataset:{}}},window:{addEventListener(){}},location:{search:'',hash:'',pathname:'/personal/'},history:{replaceState(){}},localStorage:{getItem(){return 'dark';},setItem(){}},URLSearchParams,URL,console,setInterval(){},setTimeout(){},clearTimeout(){},fetch:async()=>{calls++;return {ok:false,status:401,json:async()=>({error:'Sign in'})};}};
  vm.runInNewContext(script,sandbox,{timeout:1000});
  return {calls,elements};
}

test('the generated browser payload starts its own authenticated API call',async()=>{
  const {default:script}=await import('../.generated/personal-client.js');
  assert.equal(typeof script,'string');
  assert.equal(startBrowserScript(script).calls,1);
});

test('bundler-preserved names cannot escape into the generated browser payload',async()=>{
  const source=await readFile(new URL('../.generated/personal-client.js',import.meta.url),'utf8');
  assert.match(source,/^\/\/ Generated/);
  assert.ok(!source.includes('client.toString()'));
  const {default:script}=await import('../.generated/personal-client.js');
  assert.ok(!script.includes('__name('));
});

// CI installs Wrangler's esbuild dependency. Verify the exact failure mechanism,
// then prove that serialising before bundling removes its runtime dependency.
if(process.argv.includes('--bundled')) {
  const {transform}=await import('esbuild');
  const original=await readFile(new URL('../src/personal-client.js',import.meta.url),'utf8');
  const prior=await transform(original,{format:'esm',keepNames:true,target:'es2022'});
  const broken=await import('data:text/javascript;base64,'+Buffer.from(prior.code).toString('base64'));
  assert.throws(()=>startBrowserScript(broken.default),/__name/);
  console.log('Reproduced original bundling regression: serialized client references an out-of-scope __name helper.');
  const baked=await readFile(new URL('../.generated/personal-client.js',import.meta.url),'utf8');
  const fixed=await transform(baked,{format:'esm',keepNames:true,target:'es2022'});
  const ready=await import('data:text/javascript;base64,'+Buffer.from(fixed.code).toString('base64'));
  assert.equal(startBrowserScript(ready.default).calls,1);
  console.log('PASS: pre-baked payload starts after the same production bundling transform.');
}
