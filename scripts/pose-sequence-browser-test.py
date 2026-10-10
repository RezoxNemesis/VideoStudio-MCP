"""Browser test for real illustrated-pose interpolation, not a camera zoom."""
import os, json
from pathlib import Path
from playwright.sync_api import sync_playwright

OUT = Path(__file__).resolve().parents[1] / ".work"
BASE = "http://127.0.0.1:8790"

with sync_playwright() as p:
    browser = p.chromium.launch(
        executable_path=os.environ.get("CHROMIUM_PATH", p.chromium.executable_path),
        headless=True, args=["--no-sandbox","--disable-dev-shm-usage",
                             "--enable-webgl","--use-gl=angle","--use-angle=swiftshader",
                             "--enable-unsafe-swiftshader"])
    page = browser.new_page(viewport={"width":390,"height":844})
    js_errors=[]
    page.on("pageerror",lambda e:js_errors.append(str(e)))
    page.goto(BASE+"/?generation=pose_sequence",wait_until="domcontentloaded")
    page.wait_for_function("window.VideoStudioPoseSequence && window.VideoStudioGeneration")
    r=page.evaluate("""async () => {
      const engine=window.VideoStudioPoseSequence;
      const makeImage=async x=>{
        const cv=document.createElement('canvas');cv.width=192;cv.height=320;
        const c=cv.getContext('2d');
        c.fillStyle='#09152c';c.fillRect(0,0,192,320);
        c.strokeStyle='#415169';c.lineWidth=2;
        for(let a=0;a<192;a+=20){c.beginPath();c.moveTo(a,0);c.lineTo(a,320);c.stroke();}
        c.fillStyle='#f82c37';
        c.beginPath();c.arc(x,143,23,0,Math.PI*2);c.fill();
        c.fillStyle='#ece8d9';c.fillRect(x-4,136,8,16);
        const image=new Image();image.src=cv.toDataURL('image/png');
        await image.decode();return image;
      };
      const images=[await makeImage(82),await makeImage(101),await makeImage(82)];
      const assets=images.map((img,i)=>({id:'picture-'+i,kind:'image',name:'pose-'+i+'.png'}));
      const project={id:'fixture',assets,settings:{aspect:'9:16',quality:'720p'}};
      const loaded=images.map(img=>({image:img,url:URL.createObjectURL(new Blob(['fixture']))}));
      const frameChecks=[];
      const services={
        loadImageAsset:async (_p,asset)=>loaded[assets.indexOf(asset)],
        dimensions:()=>[192,320],
        drawCover:(ctx,img,w,h)=>ctx.drawImage(img,0,0,w,h),
        recordCanvas:async ({draw})=>{
          const canvas=document.createElement('canvas');canvas.width=192;canvas.height=320;
          const ctx=canvas.getContext('2d',{willReadFrequently:true});
          for(const t of [0,.25,.5,.75,1]){
            draw(ctx,192,320,t,t*6,6);
            const image=ctx.getImageData(0,0,192,320).data;
            let total=0,sum=0,bright=0;
            for(let y=95;y<180;y++)for(let x=30;x<150;x++){
              const idx=(y*192+x)*4;
              if(image[idx]>140&&image[idx]>image[idx+1]+45&&image[idx]>image[idx+2]+45){
                total++;sum+=x;bright+=image[idx];
              }
            }
            frameChecks.push({t,centroid:sum/Math.max(1,total),redPixels:total,meanRed:bright/Math.max(1,total)});
          }
          return new Blob(['verified-video-frame-content'],{type:'video/webm'});
        }
      };
      const result=await engine.render(project,{anchorAssetIds:assets.map(a=>a.id),
        duration:6,fps:24,poseTimes:[0,.5,1]},services);
      let rejectedSame=false,rejectedDuplicate=false;
      try{await engine.render(project,{anchorAssetIds:['picture-0','picture-0']},services);}catch(e){rejectedDuplicate=true;}
      try{
        await engine.render({...project,assets:[assets[0],{id:'same-art',kind:'image'}]},
          {anchorAssetIds:['picture-0','same-art']},
          {...services,loadImageAsset:async()=>({image:images[0],url:URL.createObjectURL(new Blob(['fixture']))})});
      }catch(e){rejectedSame=/too similar/.test(e.message);}
      return {engine:result.engine,anchorCount:result.distinctPoseFrames,delta:result.distinctPoseDeltas,
        ratios:result.flowMotionRatios,frameChecks,rejectedDuplicate,rejectedSame};
    }""")
    assert r["engine"]=="studio-web-multiframe-pose-flow-v1",r
    assert r["anchorCount"]==3 and len(r["delta"])==2,r
    assert min(r["delta"])>12,r
    assert r["rejectedDuplicate"] and r["rejectedSame"],r
    frames=r["frameChecks"]
    assert frames[0]["redPixels"]>500 and frames[2]["redPixels"]>250,r
    assert frames[0]["centroid"]+4<frames[2]["centroid"]<frames[0]["centroid"]+30,r
    assert frames[4]["centroid"]<frames[2]["centroid"]-4,r
    assert not js_errors,js_errors
    OUT.mkdir(exist_ok=True)
    (OUT/"pose-sequence-browser.json").write_text(json.dumps(r,indent=2))
    print("PASS pose-sequence browser movement; frame centers = "+
        ", ".join(str(round(q["centroid"],2)) for q in frames),flush=True)
    browser.close()
