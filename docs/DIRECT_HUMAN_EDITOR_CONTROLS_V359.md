# VideoStudio 3.5.9 — Direct Controls, Touch Trim and On-Screen Caption Preview

## Actual user-facing additions
VideoStudio 3.5.8 introduced genuinely timed native music and title layers. 3.5.9 builds on that without increasing thermal costs.
- **Direct Adjust dialog** in Color & Effects tool drawer: Brightness, Contrast, Saturation and Lightness sliders with exact percent/point labels. Writes supported Media3 brightness, contrast and HslAdjustment parameters, not just cosmetic metadata.
- **Transform dialog**: direct Zoom and Rotation sliders wired to the existing native ScaleAndRotateTransformation. Both dialogs have Reset, Cancel and Apply; changes are undoable in VideoStudio.
- **Touch Trim controls:** start/end handles on bounded 0–1000 seeks, exact numeric input, and a Use Playhead as In button; all edits are validated within the actual source duration before saving.
- **Live caption overlay preview:** VideoStudio now displays authored subtitle text over its existing video/still preview surface, with approximate top/center/bottom placement and synchronized timing during Play Edit, while scrubbing and when browsing stills. Export remains authoritative for exact font appearance.
- **Track export** from previous version remains Media3 music-mixing and title overlay. The editor shows separate music/title lanes; no Gallery enumeration.

## Tested in source
- `StudioDirectEditingTest`: supports real `brightness`, `contrast`, `saturationAdjust`, `lightnessAdjust`, `scale`, `rotate` data; validates limits/reset; touch trim arithmetic and caption start/end.
- Keep previous `StudioMultitrackTest`, native job journal separation, Android thermal safety, source-rigged 2D/3D, project and MP4 tests green.

## Remaining limitations
- Live **music mix** still requires full rendered MP4; the app does not yet synchronize four separate ExoPlayer music tracks with temporary video edits. The live title overlay is an approximate on-screen preview and the Media3 export verifies the final burned caption.
- Camera effects, green screen, masks, some transitions and real-time HSL preview remain uneven. They are not declared complete until verified in actual on-device render footage.
- Current native editor is a horizontal scroll and two-layer track display; professional draggable multi-lane waveforms, keyframes, selection handles, precise gestures and full waveform mixing still need implementation.
- Android app background execution is bounded by OS; authenticated MCP relay remains necessary for remote ChatGPT commands. Do not bypass serious Android thermal safeguards.

## Phone acceptance
Install 3.5.9 over previous version (do not uninstall), open existing projects, preview an already rendered MP4, scrub stills with captions appearing only during their authored times, use touch trim/undo, apply a color/zoom change and export a sample MP4. Verify output dimensions and audio/caption timing. Inspect light/heavy native job queue simultaneously. CI green is not a substitute for this visual test.
