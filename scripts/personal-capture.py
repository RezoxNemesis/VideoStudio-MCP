"""Capture stable real UI states and smoke-test the preserved editor in local CI."""
from pathlib import Path
from playwright.sync_api import sync_playwright, expect
import json

out = Path(__file__).resolve().parents[1] / '.work'
base = 'http://127.0.0.1:8790/personal/'
with sync_playwright() as p:
    browser = p.chromium.launch(headless=True)
    context = browser.new_context(viewport={'width':1440,'height':1100}, reduced_motion='reduce')
    page = context.new_page()
    errors = []
    page.on('pageerror', lambda e: errors.append(str(e)))
    page.goto(base, wait_until='networkidle')
    page.locator('#owner-key').fill('test_owner_alpha_'.ljust(48,'a'))
    page.get_by_role('button',name='Open my studio').click()
    expect(page.locator('#overview')).to_be_visible()
    expect(page.locator('#cloud-label')).to_have_text('Available')
    expect(page.locator('#toast')).to_be_hidden(timeout=10000)
    page.screenshot(path=str(out/'overview-desktop.png'),full_page=True,animations='disabled')
    page.set_viewport_size({'width':390,'height':844})
    page.screenshot(path=str(out/'overview-mobile.png'),animations='disabled')
    page.locator('.mobile-nav [data-view="activity"]').click()
    expect(page.locator('#activity')).to_be_visible()
    page.screenshot(path=str(out/'activity-mobile.png'),animations='disabled')
    page.locator('.mobile-nav [data-view="studio"]').click()
    page.locator('#load-editor').click()
    frame=page.frame_locator('#editor-frame')
    expect(frame.locator('#timeline')).to_be_visible(timeout=15000)
    expect(frame.locator('#fileInput')).to_be_attached()
    expect(frame.locator('#createProjectBtn')).to_be_visible()
    assert not errors, errors
    (out/'editor-smoke-results.json').write_text(json.dumps({'legacyEditorLoads':True,'timelineVisible':True,'filePickerAttached':True,'javascriptErrors':errors},indent=2))
    browser.close()
print('PASS: stable desktop/mobile screenshots and preserved editor bootstrap')
