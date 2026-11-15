#!/bin/bash
# VideoStudio 2 in this workspace: the app itself on a screen of its own, linked to the local relay.
cd /home/claude/ws
pgrep -f "relay.mjs serve" > /dev/null || (nohup node relay.mjs serve > relay.log 2>&1 &)
sleep 0.5
if ! pgrep -f "squashfs-root/videostudio" > /dev/null; then
  (VSD_NO_SANDBOX=1 VSD_LINK_WORKER=http://127.0.0.1:8787 VSD_LINK_KEY=ws nohup xvfb-run -a -s "-screen 0 1600x960x24" ./squashfs-root/videostudio --no-sandbox --remote-debugging-port=9333 > app.log 2>&1 &)
fi
for i in $(seq 1 60); do s=$(node relay.mjs status 2>/dev/null); case "$s" in *'"lastPollSecondsAgo":null'*|"") sleep 1;; *) echo "$s"; exit 0;; esac; done
echo "the app did not link"; tail -20 app.log; exit 1
