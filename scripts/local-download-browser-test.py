"""Real Chromium: exact asset download through same-origin IndexedDB only."""
import os
from pathlib import Path
from playwright.sync_api import sync_playwright, expect

ROOT = Path(__file__).resolve().parents[1]
BASE = "http://127.0.0.1:8790"
PROJECT = "1ce24ce6-0abb-44c3-a53e-d72d21cf705b"
VIDEO = "45ce07ca-eefb-4c9b-a3bd-238ae45193c4"
WRONG = "65eb659f-016a-4d6a-80db-9488f65cbca4"
PAYLOAD = b"\x00\x00\x00\x14ftypisomVideoStudio-V2-verified-bytes"

with sync_playwright() as p:
    browser=p.chromium.launch(
      executable_path=os.environ.get("CHROMIUM_PATH",p.chromium.executable_path),
      headless=True,args=["--no-sandbox","--disable-dev-shm-usage"])
    page=browser.new_page(accept_downloads=True,viewport={"width":390,"height":844})
    page.goto(BASE,wait_until="domcontentloaded")
    page.evaluate("""async ({project,asset,bytes}) => {
      const db=await new Promise((resolve,reject)=>{
        const req=indexedDB.open('videostudio-local',3);
        req.onupgradeneeded=()=>{const db=req.result;if(!db.objectStoreNames.contains('assets'))db.createObjectStore('assets',{keyPath:'key'});};
        req.onsuccess=()=>resolve(req.result);req.onerror=()=>reject(req.error);
      });
      await new Promise((resolve,reject)=>{
        const tx=db.transaction('assets','readwrite');
        tx.objectStore('assets').put({
          key:project+':'+asset,
          blob:new Blob([new Uint8Array(bytes)],{type:'video/mp4'}),
          meta:{name:'Goku-Saitama-V2.mp4',kind:'video'}
        });
        tx.oncomplete=()=>resolve();tx.onerror=()=>reject(tx.error);
      });db.close();
    }""",{"project":PROJECT,"asset":VIDEO,"bytes":list(PAYLOAD)})
    page.goto(BASE+"/download-local/?projectId="+PROJECT+"&assetId="+VIDEO,wait_until="domcontentloaded")
    expect(page.locator("#save")).to_be_enabled()
    expect(page.locator("#status")).to_contain_text("Goku-Saitama-V2.mp4")
    with page.expect_download() as download_info:
      page.locator("#save").click()
    download=download_info.value
    assert download.suggested_filename=="Goku-Saitama-V2.mp4"
    assert Path(download.path()).read_bytes()==PAYLOAD
    print("PASS: direct local download yields exact original browser-media bytes")
    page.goto(BASE+"/download-local/?projectId="+PROJECT+"&assetId="+WRONG,wait_until="domcontentloaded")
    expect(page.locator("#save")).to_be_disabled()
    expect(page.locator("#status")).to_contain_text("Not found")
    print("PASS: unrelated media is inaccessible")
    page.goto(BASE+"/download-local/?projectId=bad&assetId="+VIDEO,wait_until="domcontentloaded")
    expect(page.locator("#save")).to_be_disabled()
    expect(page.locator("#status")).to_contain_text("Invalid VideoStudio")
    print("PASS: invalid references fail closed")
    browser.close()
