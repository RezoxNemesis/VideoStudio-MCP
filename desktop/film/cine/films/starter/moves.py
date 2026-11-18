"""The film's performances, compiled from captured motion. Run: cine.tool {film, script: "moves.py", args: ["s01"]}
(or in a workspace: CINE_ASSETS=… CINE_FILM=… PYTHONPATH=<engine>/tools python3 moves.py s01). See cine.help, "Performances"."""
import sys, json, os
from mocap import *
MARKS = {}
def out(name, cn, s, curl=0.6, marks=None):
    ch = s.ch; Q, root = s.done(); export(ch, name, Q, root, curl=dict(l=curl, r=curl)); gait_report(ch, Q, root, name)
    MARKS[name] = {k: [float(root[int(t * FPS), 0] - root[0, 0]), float(root[int(t * FPS), 1]), float(root[int(t * FPS), 2] - root[0, 2]), float(heading(ch, Q[int(t * FPS):int(t * FPS) + 1])[0])] for k, t in (marks or {}).items()}
def s01():
    ch = Char('hero23'); s = Seq(ch); s.loop(cycle('16_35', ch), 8.5, speed=1.0)   # a jog, eight and a half seconds
    out('s01_hero', 'hero23', s)
if __name__ == '__main__':
    for n in sys.argv[1:]: globals()[n]()
    p = DATA + 'marks.json'; old = json.load(open(p)) if os.path.exists(p) else {}; old.update(MARKS); json.dump(old, open(p, 'w'))
