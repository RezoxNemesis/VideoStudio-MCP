"""Real browser import -> local MCP edit -> save/reload -> UI edit -> encoded export.

Supply explicitly selected test media. This is browser evidence, never Android-device evidence.
Start the local Worker separately with npm run dev -- --local --port 8790.
"""
import argparse
import json
from pathlib import Path
import subprocess
import time
from urllib.parse import quote
from playwright.sync_api import sync_playwright, expect

parser = argparse.ArgumentParser()
parser.add_argument('--video', required=True)
parser.add_argument('--image', required=True)
parser.add_argument('--origin', default='http://127.0.0.1:8790')
parser.add_argument('--output', default='.work/edit-state-browser')
args = parser.parse_args()
root = Path(__file__).resolve().parents[1]
out = (root / args.output).resolve()
out.mkdir(parents=True, exist_ok=True)
results = []

def record(name):
    results.append({'check': name, 'status': 'passed'})
    print('PASS:', name, flush=True)

# Use the project's actual SDK and public browser-device pairing, with no live owner credentials.
caller = out / 'call.mjs'
caller.write_text('''
import {Client,StreamableHTTPClientTransport} from '@modelcontextprotocol/client';
const {origin,...args}=JSON.parse(process.argv[2]);
const c=new Client({name:'reference-browser-qa',version:'1.0.0'},{capabilities:{},supportedProtocolVersions:['2025-11-25','2025-06-18'],versionNegotiation:{mode:'legacy'}});
try {
 await c.connect(new StreamableHTTPClientTransport(new URL(origin+'/mcp')),{timeout:20000});
 const r=await c.callTool({name:'queue_video_edit',arguments:args},{timeout:20000});
 process.stdout.write(JSON.stringify(r));
} finally { await c.close(); }
''')

with sync_playwright() as p:
    browser = p.chromium.launch(executable_path='/usr/bin/chromium', headless=True,
        args=['--no-sandbox', '--disable-dev-shm-usage', '--autoplay-policy=no-user-gesture-required'])
    context = browser.new_context(viewport={'width': 1440, 'height': 1000}, accept_downloads=True)
    page = context.new_page()
    errors = []
    page.on('pageerror', lambda error: errors.append(str(error)))
    page.goto(args.origin, wait_until='domcontentloaded')
    expect(page.locator('#createProjectBtn')).to_be_enabled()
    title = 'Reference edit verification ' + str(time.time_ns())
    page.locator('#newProjectName').fill(title)
    page.locator('#createProjectBtn').click()
    expect(page.locator('#projectTitle')).to_have_text(title)
    device = page.evaluate("localStorage.getItem('vs-device-id')")
    project_id = page.evaluate("localStorage.getItem('vs-project-id')")
    project_url = args.origin + '/api/projects/' + quote(project_id) + '?deviceId=' + quote(device)

    def project():
        response = page.request.get(project_url)
        assert response.ok, response.text()
        return response.json()['project']

    def wait_until(check, timeout=25):
        until = time.monotonic() + timeout
        while time.monotonic() < until:
            if check():
                return
            page.wait_for_timeout(200)
        raise AssertionError('Timed out waiting for browser state')

    def command(action, parameters, expected='completed'):
        r = subprocess.run(['node', str(caller), json.dumps({'origin': args.origin, 'deviceId': device,
            'projectId': project_id, 'action': action, 'parameters': parameters})], cwd=root,
            capture_output=True, text=True, timeout=45, check=True)
        tool_result = json.loads(r.stdout)
        queued = json.loads(next(block['text'] for block in tool_result['content'] if block['type'] == 'text'))
        assert queued.get('queued'), queued
        command_id = queued['commandId']
        commands_url = args.origin + '/api/commands?deviceId=' + quote(device) + '&after=0'
        receipt = None
        def done():
            nonlocal receipt
            receipt = next((c for c in page.request.get(commands_url).json()['commands'] if c['id'] == command_id), None)
            return receipt and receipt['status'] in ['completed', 'failed']
        wait_until(done)
        assert receipt['status'] == expected, receipt
        return receipt

    page.locator('#fileInput').set_input_files([str(Path(args.video).resolve()), str(Path(args.image).resolve())])
    wait_until(lambda: len(project().get('assets', [])) == 2)
    assets = project()['assets']
    assert all(c.get('clipId') for c in project()['timeline']), 'New manual imports need stable IDs before MCP editing'
    video = next(a for a in assets if a['kind'] == 'video')
    image = next(a for a in assets if a['kind'] == 'image')
    assert video['duration'] >= 2
    record('actual UI imports explicitly supplied video and image into IndexedDB and the local Worker')

    original = project()['timeline']
    command('autonomous_request', {'clips': [{'assetId': video['id'], 'start': 0, 'end': 1,
        'effects': {'brightness': .2}}]}, expected='failed')
    assert project()['timeline'] == original
    record('the actual browser fallback rejects a native-only effect plan without dropping settings or modifying the timeline')

    clips = [dict(assetId=video['id'], clipId='video-cut', start=0, end=1.5, speed=.25,
        volume=0, audioFadeIn=.4, audioFadeOut=.5, brightness=1.15, contrast=1.1, saturation=1.2,
        zoomStart=1.1, zoomEnd=1.4, panXStart=-.2, panXEnd=.3, panYStart=0, panYEnd=.2,
        rotate=5, title='Reference edit verified', titleStyle={'position': 'top', 'size': 1.2, 'background': True}),
        dict(assetId=image['id'], clipId='image-cut', start=0, end=1.6, speed=4, volume=0,
            zoomStart=1.1, zoomEnd=1.3, brightness=1.1)]
    command('autonomous_request', {'clips': clips, 'aspect': '1:1', 'quality': '720p',
        'transition': 'none', 'mute': False, 'render': False})
    saved = project()
    assert [c['clipId'] for c in saved['timeline']] == ['video-cut', 'image-cut']
    for original, restored in zip(clips, saved['timeline']):
        for key, value in original.items():
            if key not in ['start', 'end']:
                assert restored[key] == value, (key, restored[key], value)
    record('actual MCP autonomous request retains 0.25/4x, zero audio, grade, motion and title style in saved JSON')

    page.reload(wait_until='domcontentloaded')
    expect(page.locator('#projectTitle')).to_have_text(title)
    command('set_clip_effects', {'clipId': 'video-cut', 'effects': {'brightness': 1.3}})
    saved = project()['timeline'][0]
    assert saved['brightness'] == 1.3 and saved['volume'] == 0 and saved['zoomEnd'] == 1.4
    record('reload restores source bytes and a later stable-ID color edit retains audio and motion')

    page.locator('#trimStart').fill('0.2')
    page.locator('#trimEnd').fill('1.5')
    page.locator('#applyTrimBtn').click()
    wait_until(lambda: project()['timeline'][0]['inPoint'] == .2)
    assert project()['timeline'][0]['brightness'] == 1.3
    record('manual trim edits the MCP-authored cut while retaining its saved grade')

    page.locator('#renderBtn').click()
    expect(page.locator('#downloadRender')).to_be_visible(timeout=45000)
    with page.expect_download() as download:
        page.locator('#downloadRender').click()
    artifact = out / Path(download.value.suggested_filename).name
    download.value.save_as(artifact)
    assert artifact.stat().st_size > 10000
    probe = json.loads(subprocess.run(['ffprobe', '-v', 'error', '-show_entries',
        'format=duration:stream=codec_name,width,height,codec_type', '-of', 'json', str(artifact)],
        capture_output=True, text=True, check=True).stdout)
    stream = next(s for s in probe['streams'] if s['codec_type'] == 'video')
    assert stream['width'] == 720 and stream['height'] == 720
    assert 4.8 < float(probe['format']['duration']) < 8, probe
    (out / 'encoded-output.json').write_text(json.dumps(probe, indent=2))
    pcm = subprocess.run(['ffmpeg', '-hide_banner', '-loglevel', 'error', '-i', str(artifact),
        '-vn', '-f', 's16le', '-acodec', 'pcm_s16le', '-'], capture_output=True, check=True).stdout
    assert pcm and not any(pcm), 'Zero clip volume must remain silent in the actual encoded audio'
    page.screenshot(path=str(out / 'desktop.png'), full_page=True)
    record('actual Render UI downloads a decodable 720x720 video with the edited duration and verified silent audio')

    before = project()['timeline']
    page.route('**/api/projects/' + project_id, lambda route: route.fulfill(status=503,
        content_type='application/json', body='{"error":"Injected save failure"}') if route.request.method == 'POST' else route.continue_())
    failed = command('set_clip_speed', {'clipId': 'video-cut', 'speed': 2}, expected='failed')
    assert 'Injected save failure' in failed['result']['error']
    assert project()['timeline'] == before
    page.unroute('**/api/projects/' + project_id)
    command('set_clip_effects', {'clipId': 'video-cut', 'effects': {'brightness': 1.25}})
    assert project()['timeline'][0]['speed'] == .25
    record('injected real HTTP save failure returns a failed command receipt and preserves durable and in-memory speed')

    page.set_viewport_size({'width': 390, 'height': 844})
    page.evaluate('window.scrollTo(0,0)')
    page.screenshot(path=str(out / 'mobile.png'), full_page=True)
    page.screenshot(path=str(out / 'mobile-viewport.png'))
    overflow = page.evaluate('document.documentElement.scrollWidth > innerWidth + 1')
    assert not errors, errors
    report = {'environment': 'local Chromium and Cloudflare workerd; no Android device',
        'checks': results, 'pageErrors': errors, 'mobileHorizontalOverflow': overflow,
        'artifact': artifact.name, 'nativePhoneExecutionVerified': False}
    (out / 'report.json').write_text(json.dumps(report, indent=2))
    print('Mobile horizontal overflow:', overflow, flush=True)
    browser.close()
