# VideoStudio 2 in a Claude workspace (no PC needed)

When the owner's PC is off, Claude runs the real VideoStudio 2 desktop app inside its own Linux workspace, on a
virtual screen, and drives it with the same commands the PC's connector takes. This is how films are built, checked
and rendered without the PC. It is slow (2 cores, no GPU): about 3.5 frames a second for a half-size draft, about one a
second at 1080p, and a spoken line takes minutes — plan short films with few lines.

**Steps 1–3 are written from the working setup of 14–16 Nov 2026 and have not been re-run on an empty machine. If a
step fails, fix it and correct this file.**

## 1. The repository
    git clone --depth 1 -b desktop https://github.com/RezoxNemesis/VideoStudio-MCP /home/claude/videostudio-mcp
    cd /home/claude/videostudio-mcp && npm ci && (cd desktop && npm ci) && (cd desktop/film && npm install)

## 2. The app (the newest desktop release, unpacked)
    mkdir -p /home/claude/ws && cd /home/claude/ws
    TAG=$(gh api "repos/RezoxNemesis/VideoStudio-MCP/releases?per_page=30" --jq '[.[] | select(.tag_name | startswith("desktop-v"))][0].tag_name')
    gh release download "$TAG" --repo RezoxNemesis/VideoStudio-MCP --pattern '*.AppImage' -O VideoStudio.AppImage   # about 770 MB
    chmod +x VideoStudio.AppImage && ./VideoStudio.AppImage --appimage-extract > /dev/null                           # → squashfs-root/

## 3. Wire the unpacked app to the working tree (so engine changes are live)
    cd /home/claude/ws/squashfs-root/resources
    mv app.asar app.asar.orig && ln -s /home/claude/videostudio-mcp/desktop app
    mv film film.pkg && ln -s /home/claude/videostudio-mcp/desktop/film film
    (cd /home/claude/videostudio-mcp/desktop && npm run web)        # the window bundle (30 s); again after any src/** change
stage-assets (the motion library), ffmpeg and the voices stay the release's own.

## 4. Start it and drive it
    cp /home/claude/videostudio-mcp/tools/workspace/{relay.mjs,start.sh} /home/claude/ws/ && chmod +x /home/claude/ws/start.sh
    /home/claude/ws/start.sh                       # the relay on 127.0.0.1:8787 + the app under xvfb; prints the app's status
    cd /home/claude/ws
    node relay.mjs run '[{"op":"help"}]' --wait 55
    node relay.mjs run '[{"op":"code.new","name":"My Film","template":"starter","width":1920,"height":1080,"fps":30,"langs":["hi"]}]' --wait 90
    node relay.mjs run '[{"op":"code.open","film":"My Film"},{"op":"code.build","draft":true,"only":["__none__"],"sound":false,"subs":"none","sensei":false}]' --wait 280   # solve the motion only
    node relay.mjs run '[{"op":"code.open","film":"My Film"},{"op":"code.build","draft":true,"subs":"none","sensei":false}]' --wait 285                                    # draw a draft
    node relay.mjs status ; node relay.mjs result <job>                                                                                                               # a build still running
Needs `xvfb-run` and `node`. Background processes stop between turns: run start.sh again. A relay call returns after
about five minutes even if the build goes on: poll `status` / `result`. Stop the app with
`pkill -f squashfs-root/videostudio` when done. Films live in /root/Videos/VideoStudio/Code/<Name>/ (film.js, seq/*.js,
script.json, cues.json, project.json, vs.js); finished files in its .vs/out/.

## 5. Checking without the app (fast)
    cd /home/claude/videostudio-mcp/desktop/film
    node devtests/frame.mjs <film dir> out.jpg seq_shot@1.0 seq_shot@2.0 --scale 0.5 --cols 2   # frames (t = seconds into the shot)
    node devtests/sensei.mjs <film dir> <seq>                 # the judge;  SHOW=foot-slide node devtests/sensei.mjs … prints each finding
    node devtests/gait.mjs <film dir> <seq> <actor> [from] [to]   # a walk or a run by numbers
    node devtests/steps.mjs <film dir> <seq> <actor> <bpm>        # steps and bounce against a tempo
    node devtests/where.mjs <film dir> seq_shot@t actor:wrist.L   # where a joint is
    node devtests/moves.mjs pick up a bag                         # the code collection: proven moves with rules, clips, code, checks
    python3 tools/workspace/sheet.py video.mp4 out.jpg 0 10 --n 24 --w 300   # a strip of frames from a finished video
The handbook is desktop/film/API.md. Things learnt the hard way: a long box is sorted by its middle (cut trains and
walls into short pieces); surface detail (DET.marks) is not drawn on a face that reaches behind the lens (short pieces
again); never speak many lines here; `pose: 'sit'` is a seat on the GROUND.
