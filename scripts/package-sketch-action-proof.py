#!/usr/bin/env python3
"""Package measured synthetic animation evidence; never copy private native state."""
import argparse
import hashlib
import json
from pathlib import Path
import shutil
import zipfile

ROOT = Path(__file__).resolve().parents[1]


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--evidence-dir',type=Path,default=ROOT/'android/app/build/sketch-action-film')
    parser.add_argument('--output-dir',type=Path,required=True)
    parser.add_argument('--test-summary',type=Path,help='Explicit reviewed test-summary JSON to include with the proof.')
    args=parser.parse_args()
    evidence=args.evidence_dir.resolve()
    output=args.output_dir.resolve()
    manifest=json.loads((evidence/'native-frame-manifest.json').read_text())
    encode=json.loads((evidence/'workspace-encode-verification.json').read_text())
    report=json.loads((evidence/'compiler-report.json').read_text())
    motion=json.loads((evidence/'motion-landmarks.json').read_text())
    assert manifest['frameCount']==encode['frameCount']==report['frameCount']==100
    assert manifest['fps']==encode['fps']==report['fps']==24
    assert len(manifest['frames'])==100
    for frame in manifest['frames']:
        path=evidence/'frames'/f"frame-{frame['frame']:04d}.png"
        assert sha(path)==frame['sha256'],f'Stale native frame: {path}'
    video=evidence/encode['video']
    assert sha(video)==encode['sha256'],'Workspace MP4 changed after verification'
    ui=json.loads((evidence/'ui-evidence.json').read_text())
    files=['contact-sheet.png','proof-frame-16.png','proof-frame-43.png','proof-frame-84.png',
           'animation-studio-frame-16.png','animation-studio-frame-43.png','animation-studio-frame-76.png',
           'ui-evidence.json','compiled-animation.json','compiler-report.json','motion-landmarks.json',
           'native-frame-manifest.json','workspace-encode-verification.json',video.name]
    for name in files:
        assert (evidence/name).is_file(),f'Missing evidence: {name}'
    output.mkdir(parents=True,exist_ok=True)
    readme=f'''# Rooftop Relay — original 100-frame 2D animation proof

This is an original stylized articulated/cutout action study created from editable vector sketches. No source photograph, imported drawing, generated image, or paint cel is inserted. The 100 rendered animation frames come from prepared original artwork and authored poses; they are not 100 independently hand-drawn pages or a claim of feature-film artistic quality.

The real VideoStudio SketchAnimationCompiler creates editable animation from prepared sketches. VideoStudio SketchFrameRenderer renders the actual PNG frames using Android Canvas in Robolectric native graphics in the workspace. The included Animation Studio screenshots draw the actual Android Activity at 1080 × 1920 with density 3 in that same workspace. None of this is an Android-phone render or phone screenshot.

The MP4 is a workspace FFmpeg encoding of exactly the 100 ordered native-rendered PNGs: 1280 × 720, 24 FPS, 4.166667 seconds. Dialogue is authored on-screen lettering and timed mouth cels; there is no synthesized voice or audio.

Views: wide planted parry (0–35), separately authored foreground POV glove and returning baton (36–59), medium recovery and dialogue (60–99). The last dialogue section deliberately settles into an acting pose with mouth and secondary motion.

Measured structural checks:

- Front-elbow relative angle range: {motion['frontElbowRelativeAngleRangeDegrees']:.2f}°.
- Wide wrist path: {motion['wideWristPathPixels']:.2f} pixels.
- Planted-ankle samples: {report['contactSampleCount']}; maximum coordinate error {motion['maxPlantedAnkleErrorPixels']:.3g} pixels and sole-orientation error {motion['maxPlantedFootRotationErrorDegrees']:.3g}°.
- Independent-articulation frames: {report['articulatedMotionFrames']}.
- Compiled editable output: {report['outputObjectCount']} objects, {report['outputTransformKeys']} transform keys, {report['outputBytes']} bytes.
- Adaptive key reduction maximum evaluated deviation: {report['maxBakedTransformApproximationPixels']:.6f} pixels.

All 100 native PNG frames are unique. Pixel differences and constraint metrics alone are not artistic-quality proof.

Reproduce from the repository with scripts/render-sketch-action-proof.sh. See the two included guides for the actual human and ChatGPT workflows. Raw prepared-art previews are deliberately omitted so template cels are not confused with the finished animation.
'''
    (output/'README.md').write_text(readme)
    for name in files:
        shutil.copy2(evidence/name,output/name)
    archive=output/'Video-studio-2D-animation-proof.zip'
    with zipfile.ZipFile(archive,'w',compression=zipfile.ZIP_DEFLATED,compresslevel=6) as z:
        z.write(output/'README.md','README.md')
        for name in files:
            z.write(evidence/name,'native-canvas-proof/'+name)
        for f in range(100):
            path=evidence/'frames'/f'frame-{f:04d}.png'
            z.write(path,'native-canvas-proof/frames/'+path.name)
        for path in sorted((ROOT/'android/app/src/test/resources/sketch-animation').glob('*.json')):
            z.write(path,'editable-artwork/'+path.name)
        for name in ['SKETCH_2D_ANIMATION_GUIDE.md','SKETCH_2D_ANIMATION_CHATGPT_GUIDE.md',
                     'VIDEO_STUDIO_APP_KEY.md','sketch-live-drawing.md']:
            guide=(ROOT/'docs'/name).read_text()
            # Repository links remain correct in source; only the ZIP copy uses
            # the distribution's editable-artwork directory.
            guide=guide.replace('../android/app/src/test/resources/sketch-animation/','../editable-artwork/')
            z.writestr('guides/'+name,guide)
        release_notes=ROOT/'docs/VIDEO_STUDIO_440_2D_ANIMATION.md'
        if release_notes.is_file():
            z.write(release_notes,'validation/'+release_notes.name)
        if args.test_summary:
            summary=json.loads(args.test_summary.read_text())
            z.writestr('validation/android-full-suite-summary.json',json.dumps(summary,indent=2)+'\n')
        for name in ['render-sketch-action-proof.sh','generate-sketch-action-fixture.py','package-sketch-action-proof.py']:
            z.write(ROOT/'scripts'/name,'reproduction/'+name)
    integrity=dict(file=archive.name,bytes=archive.stat().st_size,sha256=sha(archive),
                   videoSha256=sha(video),nativeFrames=100,includesNativeActivityScreenshots=True)
    (output/'proof-bundle-integrity.json').write_text(json.dumps(integrity,indent=2)+'\n')
    print(json.dumps(integrity,indent=2))


if __name__=='__main__':
    main()
