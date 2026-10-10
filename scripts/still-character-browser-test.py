"""Headless Chromium WebGL test for real single-still deformation (no user files)."""
import json
import os
from pathlib import Path
from playwright.sync_api import sync_playwright

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / '.work'
BASE = 'http://127.0.0.1:8790/'

with sync_playwright() as p:
    browser = p.chromium.launch(
        executable_path=os.environ.get('CHROMIUM_PATH', p.chromium.executable_path),
        headless=True,
        args=['--no-sandbox', '--disable-dev-shm-usage',
              '--enable-webgl', '--use-gl=angle', '--use-angle=swiftshader',
              '--enable-unsafe-swiftshader']
    )
    page = browser.new_page(viewport={'width': 390, 'height': 844})
    failures = []
    page.on('pageerror', lambda error: failures.append(str(error)))
    page.goto(BASE + '?generation=character_action', wait_until='domcontentloaded')
    page.wait_for_function('window.VideoStudioCharacter && window.VideoStudioActionTimeline && document.getElementById("vsGenMode")')
    assert page.locator('#vsGenMode').input_value() == 'character_action'
    page.wait_for_selector('#vsCinematicWorlds', state='attached')
    assert page.locator('#vsCinematicWorlds').evaluate("e => getComputedStyle(e).display") == 'none'
    result = page.evaluate("""async () => {
      const w=320,h=568, source=document.createElement('canvas');
      source.width=w; source.height=h;
      const base=source.getContext('2d');
      base.fillStyle='#090d24';base.fillRect(0,0,w,h);
      for(let j=0;j<h;j+=11) {
        base.fillStyle=j%22?'#ef853d':'#268bff';
        base.fillRect(0,j,w,6);
      }
      for(let i=0;i<w;i+=19) {
        base.fillStyle=i%38?'#fff0a6':'#181a2c';
        base.fillRect(i,0,5,h);
      }
      const image=new Image();
      image.src=source.toDataURL('image/png');
      await image.decode();
      const temporary=URL.createObjectURL(new Blob(['fixture']));
      let samples=0,difference=0,span=0;
      const services={
        loadImageAsset:async()=>({image,url:temporary}),
        dimensions:()=>[w,h],
        drawCover:(ctx,img,width,height)=>ctx.drawImage(img,0,0,width,height),
        recordCanvas:async({draw})=>{
          const output=document.createElement('canvas');
          output.width=w;output.height=h;
          const ctx=output.getContext('2d',{willReadFrequently:true});
          draw(ctx,w,h,0,0);
          const first=ctx.getImageData(0,0,w,h).data.slice();
          draw(ctx,w,h,.47,1.4);
          const second=ctx.getImageData(0,0,w,h).data;
          for(let y=80;y<490;y+=4){
            for(let x=30;x<290;x+=4){
              const i=(y*w+x)*4;
              difference+=Math.abs(first[i]-second[i])
                +Math.abs(first[i+1]-second[i+1])
                +Math.abs(first[i+2]-second[i+2]);
              samples++;
            }
          }
          span=output.toDataURL('image/png').length;
          return new Blob(['rendered-frame-test'],{type:'video/webm'});
        }
      };
      const project={id:'fixture-project',assets:[{id:'fixture-image',kind:'image'}],settings:{aspect:'9:16',quality:'720p'}};
      const render=await window.VideoStudioCharacter.animateImage(project,
        {assetId:'fixture-image',prompt:'Anime duel fight',motionPreset:'duel',duration:2,fps:24,intensity:1},
        services);
      const plan=window.VideoStudioActionTimeline.compile({motionPreset:'duel'});
      const strike=window.VideoStudioActionTimeline.sample(plan,.52,3.12,1);
      return {difference,samples,span,engine:render.engine,regions:render.regionCount,
        preset:render.motionPreset,bytes:render.blob.size,keyframed:render.keyframed,
        phases:render.motionPhases,poseFistDx:strike.entries[1].dx,impact:strike.impact};
    }""")
    assert result['engine'] == 'studio-web-puppet-keyframe-action-v2', result
    assert result['keyframed'] and len(result['phases']) == 6, result
    assert result['poseFistDx'] > .07 and result['impact'] > .95, result
    assert result['preset'] == 'duel' and result['regions'] == 8, result
    assert result['bytes'] > 0 and result['span'] > 10000, result
    assert result['difference']/result['samples'] > 12, (
        'Two frame samples did not change significantly; effect may be a static slideshow: ' + str(result))
    assert not failures, failures
    OUT.mkdir(exist_ok=True)
    (OUT / 'character-browser.json').write_text(json.dumps(result,indent=2))
    print('PASS: WebGL independently warps the anime-duel regions between sampled frames: ' + str(result),flush=True)
    browser.close()
