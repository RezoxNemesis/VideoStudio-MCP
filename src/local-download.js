export default String.raw`<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1,viewport-fit=cover">
<meta name="theme-color" content="#0b0d18">
<title>Save Video · VideoStudio</title>
<style>
:root{color-scheme:dark;font-family:Inter,system-ui,sans-serif}
*{box-sizing:border-box}
body{margin:0;min-height:100vh;background:radial-gradient(circle at 12% 0%,#262046 0,transparent 42%),linear-gradient(145deg,#080a12,#151223);color:#f5f3ff;display:flex;justify-content:center;align-items:center;padding:26px}
main{width:min(100%,500px);border-radius:24px;padding:28px;background:#131320ee;border:1px solid #393151;box-shadow:0 30px 70px #0008}
.brand{font-weight:900;letter-spacing:.3px;display:flex;align-items:center;gap:12px}
.logo{display:grid;place-items:center;width:38px;height:38px;border-radius:12px;background:linear-gradient(135deg,#8965fa,#27c3df);color:#fff}
.badge{border:1px solid #4b4661;border-radius:50px;padding:6px 10px;color:#bbb1dc;font-size:11px;font-weight:700;margin-left:auto}
h1{font-size:clamp(28px,8vw,38px);line-height:1.12;margin:35px 0 10px}
p{color:#aeaac3;font-size:14px;line-height:1.65;margin:8px 0 16px}
.state{padding:15px 16px;border:1px solid #403754;background:#1a1927;border-radius:13px;margin:20px 0;word-wrap:break-word;font-size:13px}
small{color:#a3a0bc}
button{display:flex;justify-content:center;align-items:center;width:100%;border:0;padding:16px 18px;border-radius:13px;font-weight:800;font-size:15px;background:linear-gradient(100deg,#a68bff,#60dcde);color:#100c24;cursor:pointer}
button:disabled{opacity:.4;cursor:wait}
a{color:#bdb1fb}
footer{margin-top:21px;font-size:12px;color:#9590ac}
</style>
</head>
<body>
<main>
<div class="brand"><span class="logo">▶</span> VideoStudio <span class="badge">LOCAL DOWNLOAD</span></div>
<h1>Your video is ready to save.</h1>
<p>This opens the exact file already generated in your VideoStudio project. No Gallery browsing, cloud media upload or re-rendering.</p>
<div class="state" id="status" role="status">Checking this browser's VideoStudio files…</div>
<button id="save" disabled>Preparing video…</button>
<p id="size" aria-live="polite"></p>
<footer>Only the same Brave/Chrome browser profile that generated or imported this file has the original bytes. If this says “Not found,” open this link in that browser instead. <a href="/">Back to VideoStudio editor</a>.</footer>
</main>
<script>
(() => {
"use strict";
const $=id=>document.getElementById(id);
const status=$("status"),button=$("save"),size=$("size");
const params=new URLSearchParams(location.search);
const projectId=params.get("projectId")||"",assetId=params.get("assetId")||"";
const uuid=/^[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}$/i;
if(!uuid.test(projectId)||!uuid.test(assetId)){
  status.textContent="Invalid VideoStudio project or video reference.";return;
}
const req=indexedDB.open("videostudio-local",3);
req.onupgradeneeded=()=> {
  const db=req.result;
  if(!db.objectStoreNames.contains("assets"))db.createObjectStore("assets",{keyPath:"key"});
};
req.onerror=()=>{status.textContent="Browser storage is unavailable here. Try opening this link in your original Brave browser.";};
req.onsuccess=()=>{
  const db=req.result;
  if(!db.objectStoreNames.contains("assets")){status.textContent="No VideoStudio local assets exist in this browser profile.";db.close();return;}
  const tx=db.transaction("assets","readonly");
  const get=tx.objectStore("assets").get(projectId+":"+assetId);
  get.onerror=()=>{status.textContent="Cannot read the requested browser-local video.";db.close();};
  get.onsuccess=()=>{
    const record=get.result;
    const blob=record?.blob;
    if(!(blob instanceof Blob)||!blob.size||!String(blob.type).startsWith("video/")){
      status.textContent="Not found in this browser. Please open this exact link in the same Brave browser profile that created the video.";
      db.close();return;
    }
    const name=(record.meta?.name||"VideoStudio-character-animation.mp4").replace(/[^A-Za-z0-9._-]/g,"_");
    status.textContent="Verified local video · "+name;
    size.textContent="Size: "+(blob.size/1024/1024).toFixed(2)+" MB · Saved only when you tap below";
    button.disabled=false;
    button.textContent="↓ Save this MP4";
    button.onclick=()=>{
      const url=URL.createObjectURL(blob);
      const a=document.createElement("a");
      a.href=url;a.download=name;
      document.body.append(a);a.click();a.remove();
      button.textContent="↓ Save again";
      setTimeout(()=>URL.revokeObjectURL(url),60000);
    };
    db.close();
  };
};
})();
</script>
</body>
</html>`;
