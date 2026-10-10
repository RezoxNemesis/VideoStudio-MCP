"""Real Chromium interactions against the actual local Cloudflare runtime in CI."""
import json
import time
import os
from pathlib import Path
from playwright.sync_api import sync_playwright, expect

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / '.work'
BASE = 'http://127.0.0.1:8790/personal/'
KEY = 'test_owner_alpha_'.ljust(48, 'a')
results = []

def record(name):
    results.append({'test': name, 'status': 'passed'})
    print('PASS:', name, flush=True)

with sync_playwright() as p:
    browser = p.chromium.launch(executable_path=os.environ.get('CHROMIUM_PATH',p.chromium.executable_path), headless=True, args=['--no-sandbox', '--disable-dev-shm-usage'])
    context = browser.new_context(viewport={'width': 1440, 'height': 1100}, device_scale_factor=1)
    page = context.new_page()
    errors = []
    page.on('pageerror', lambda e: errors.append(str(e)))
    page.goto(BASE, wait_until='networkidle')
    expect(page.locator('#login-view')).to_be_visible()
    page.locator('#owner-key').fill(KEY)
    page.get_by_role('button', name='Open my studio').click()
    expect(page.locator('#workspace')).to_be_visible(timeout=10000)
    expect(page.locator('#cloud-label')).to_have_text('Available')
    expect(page.locator('#native-label')).to_have_text('Offline')
    cookies = context.cookies()
    assert any(c['name'] == '__Host-vs-personal' and c['httpOnly'] and c['secure'] for c in cookies)
    assert KEY not in page.evaluate('JSON.stringify(localStorage)')
    record('private login, HttpOnly cookie, offline native status, no key in localStorage')
    page.screenshot(path=str(OUT / 'overview-desktop.png'), full_page=True)

    name = 'Browser verified film ' + str(int(time.time()))
    page.locator('#overview .new-project').first.click()
    page.locator('#new-name').fill(name)
    page.get_by_role('button', name='Create project').click()
    expect(page.locator('#project-detail')).to_be_visible()
    expect(page.locator('#project-title')).to_have_text(name)
    project_hash = page.url.split('#')[1]
    page.locator('#edit-brief').fill('A private short film shaped while the phone is offline.')
    page.locator('#edit-notes').fill('Opening: the city wakes, then the camera follows the morning light.')
    page.locator('#add-shot').click()
    page.locator('.shot-title').fill('First light')
    page.locator('.shot-description').fill('A slow establishing shot with ambient sound.')
    page.locator('.shot-duration').fill('4.5')
    page.locator('#add-link').click()
    page.locator('.link-title').fill('Reference')
    page.locator('.link-url').fill('https://example.com/reference')
    page.locator('#save-project').click()
    expect(page.locator('#save-status')).to_have_text('Saved to cloud')
    page.reload(wait_until='networkidle')
    expect(page.locator('#edit-notes')).to_have_value('Opening: the city wakes, then the camera follows the morning light.')
    expect(page.locator('.shot-duration')).to_have_value('4.5')
    expect(page.locator('.link-url')).to_have_value('https://example.com/reference')
    record('create, edit notes/brief/shot/link, cloud save and reload persistence')

    page.locator('#attachments').set_input_files({'name': 'story-notes.txt', 'mimeType': 'text/plain', 'buffer': b'Local file selected explicitly for this test.'})
    expect(page.locator('#attachment-list')).to_contain_text('story-notes.txt')
    page.reload(wait_until='networkidle')
    expect(page.locator('#attachment-list')).to_contain_text('story-notes.txt')
    with page.expect_download() as info:
        page.get_by_role('button', name='Download', exact=True).click()
    path = info.value.path()
    assert Path(path).read_bytes() == b'Local file selected explicitly for this test.'
    record('local attachment selection, persistence and byte-exact download')

    with page.expect_download() as info:
        page.locator('#export').click()
    archive = json.loads(Path(info.value.path()).read_text())
    assert archive['project']['name'] == name and archive['mediaIncluded'] is False
    record('working cloud plan export with truthful media exclusion')

    page.locator('#edit-notes').fill('An unfinished draft, saved on this device.')
    expect(page.locator('#save-status')).to_have_text('Draft saved on this device')
    page.reload(wait_until='networkidle')
    expect(page.locator('#edit-notes')).to_have_value('An unfinished draft, saved on this device.')
    expect(page.locator('#save-status')).to_have_text('Draft saved on this device')
    record('unsaved IndexedDB draft survives reload without being called cloud-saved')
    page.locator('#save-project').click()
    expect(page.locator('#save-status')).to_have_text('Saved to cloud')

    page.locator('#back-projects').click()
    page.locator('#search').fill(name)
    expect(page.locator('#all-projects .project-card')).to_have_count(1)
    page.locator('#search').fill('absolutely-no-match-490287')
    expect(page.locator('#all-projects .project-card')).to_have_count(0)
    page.locator('#search').fill('')
    record('cloud search and empty-result state')

    page.locator('#import-file').set_input_files({'name':'plan.json','mimeType':'application/json','buffer':json.dumps(archive).encode()})
    expect(page.locator('#project-detail')).to_be_visible()
    imported_hash = page.url.split('#')[1]
    assert imported_hash != project_hash
    expect(page.locator('#project-title')).to_have_text(name)
    record('project import round trip creates a separate plan')

    with page.expect_response(lambda r: '/api/personal/execute' in r.url and r.request.method == 'POST') as change:
        page.locator('#theme').click()
    assert change.value.ok
    expect(page.locator('html')).to_have_attribute('data-theme', 'light')
    page.reload(wait_until='networkidle')
    expect(page.locator('html')).to_have_attribute('data-theme','light')
    with page.expect_response(lambda r: '/api/personal/execute' in r.url and r.request.method == 'POST') as change:
        page.locator('#theme').click()
    assert change.value.ok
    expect(page.locator('html')).to_have_attribute('data-theme', 'dark')
    record('working cloud-persisted dark and light themes')

    page.on('dialog', lambda d: d.accept())
    page.locator('#delete').click()
    expect(page.locator('#projects')).to_be_visible()
    page.goto(BASE+'#storage',wait_until='networkidle')
    expect(page.locator('#storage')).to_be_visible()
    expect(page.locator('#drive-badge')).to_have_text('SETUP REQUIRED')
    expect(page.locator('#connect-drive')).to_be_disabled()
    record('delete is scoped to cloud plan; unconfigured Drive is not presented as connected')

    page.goto(BASE+'#overview',wait_until='networkidle')
    page.set_viewport_size({'width':390,'height':844})
    expect(page.locator('.mobile-nav')).to_be_visible()
    assert page.evaluate('document.documentElement.scrollWidth <= innerWidth'), 'mobile horizontal overflow'
    page.screenshot(path=str(OUT/'overview-mobile.png'),full_page=True)
    page.locator('.mobile-nav [data-view="projects"]').click()
    expect(page.locator('#projects')).to_be_visible()
    page.locator('.mobile-nav [data-view="activity"]').click()
    expect(page.locator('#activity')).to_be_visible()
    page.screenshot(path=str(OUT/'activity-mobile.png'),full_page=True)
    page.locator('.mobile-nav [data-view="storage"]').click()
    expect(page.locator('#storage')).to_be_visible()
    assert page.evaluate('document.documentElement.scrollWidth <= innerWidth')
    record('390px mobile layout, no overflow, working navigation and activity view')

    page.set_viewport_size({'width':320,'height':700})
    page.goto(BASE+'#overview',wait_until='networkidle')
    assert page.evaluate('document.documentElement.scrollWidth <= innerWidth'), '320px horizontal overflow'
    page.emulate_media(reduced_motion='reduce')
    assert page.locator('.view').first.evaluate("e=>getComputedStyle(e).animationName") == 'none'
    record('320px narrow-phone layout and reduced-motion preference')

    await_cookie = context.storage_state()
    other = browser.new_context(viewport={'width':390,'height':844},storage_state=await_cookie)
    fresh = other.new_page()
    fresh.goto(BASE+'#'+project_hash,wait_until='networkidle')
    expect(fresh.locator('#edit-notes')).to_have_value('An unfinished draft, saved on this device.')
    # New context has no IndexedDB: this proves the latest text came from cloud API state.
    expect(fresh.locator('#attachment-list')).not_to_contain_text('story-notes.txt')
    record('fresh authenticated browser retrieves cloud note without local cache/media')
    other.close()

    page.set_viewport_size({'width':1440,'height':1000})
    page.locator('#logout').click()
    expect(page.locator('#login-view')).to_be_visible()
    page.reload(wait_until='networkidle')
    expect(page.locator('#login-view')).to_be_visible()
    record('logout revokes server session and stays locked after refresh')
    assert not errors, errors
    record('no unhandled JavaScript errors across the core journey')
    browser.close()

(OUT/'browser-results.json').write_text(json.dumps(results,indent=2))
print(json.dumps({'passed':len(results),'failed':0}), flush=True)
