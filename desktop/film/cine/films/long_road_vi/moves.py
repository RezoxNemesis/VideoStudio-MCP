"""Every performance in the film, compiled per shot from captured motion. python3 moves.py s01 s02 ..."""
import sys
from mocap import *
from mocap import _frame
_ch = {}; _cy = {}
def C(n):
    if n not in _ch: _ch[n] = Char(n)
    return _ch[n]
WALKS = {'08_01', '08_03', '16_31', '16_58', '35_28', '02_01', '07_04', '82_10', '104_12', '16_15'}
def cyc(cn, clip, look=(8.0, 5.0), lean=0.0):
    k = (cn, clip, look, lean)
    if k not in _cy: w = clip in WALKS; _cy[k] = cycle(clip, C(cn), tmin=0.8 if w else 0.5, tmax=1.7 if w else 1.0, look=look, lean=lean)
    return _cy[k]
MARKS = {}
def out(name, cn, s, curl=0.6, lock=True, rep=True, norm=False, marks=None, layer=None):
    ch = C(cn); rp = {}; Q, root = s.done(lock=lock, rep=rp)
    if layer: Q = layer(ch, Q, root)   # acting laid over the captured performance (a wave, hands on a controller)
    F = export(ch, name, Q, root, curl=dict(l=curl, r=curl) if not isinstance(curl, dict) else curl)
    mk = {'dur': len(Q) / FPS, 'end': [float(root[-1, 0] - root[0, 0]), float(root[-1, 2] - root[0, 2])], 'endh': float(heading(ch, Q[-1:])[0])}
    for k, t in (marks or {}).items():
        i = min(len(Q) - 1, int(t * FPS)); mk[k] = [float(root[i, 0] - root[0, 0]), float(root[i, 1]), float(root[i, 2] - root[0, 2]), float(heading(ch, Q[i:i + 1])[0])]
    MARKS[name] = mk
    if rep: gait_report(ch, Q, root, name)
    return F
def run(cn, clip, v, secs, s=None, look=(8.0, 5.0), lean=0.0, **kw):  # kw: phase, blend, turn, face
    cy = cyc(cn, clip, look, lean); s = s or Seq(C(cn)); return s.loop(cy, secs, speed=v / cy['speed'], **kw)
SH = {}
def shot(f): SH[f.__name__] = f; return f

@shot
def s01():  # the boy sets off
    out('s01_hero', 'hero10', run('hero10', '16_46', 3.8, 14.5), 0.75)
@shot
def s02():  # friends catch up
    out('s02_hero', 'hero10', run('hero10', '16_46', 3.8, 14.5, phase=11), 0.75)
    s = run('frA10', '16_45', 4.5, 5.0, phase=5); run('frA10', '16_45', 3.8, 9.5, s, blend=0.6); out('s02_frA', 'frA10', s, 0.7)
    s = run('frB10', '127_08', 4.4, 7.5, phase=20, lean=-20); run('frB10', '127_08', 3.8, 7.0, s, blend=0.6, lean=-20); out('s02_frB', 'frB10', s, 0.7)
@shot
def s03():  # kites
    out('s03_hero', 'hero10', run('hero10', '16_46', 3.8, 14.5, phase=3), 0.75)
    out('s03_frA', 'frA10', run('frA10', '16_45', 3.8, 14.5, phase=17), 0.7)
    out('s03_frB', 'frB10', run('frB10', '127_08', 3.8, 14.5, phase=9, lean=-20), 0.7)
@shot
def s04():  # monsoon, teenagers
    out('s04_hero', 'hero16', run('hero16', '16_46', 3.9, 14.5, phase=7), 0.7)
    out('s04_frA', 'frA16', run('frA16', '127_08', 3.9, 14.5, phase=22, lean=-18), 0.7)
    out('s04_frB', 'frB16', run('frB16', '16_45', 3.9, 14.5, phase=13), 0.7)
@shot
def s05():  # the fork: friends stop, wave, take their own road
    out('s05_hero', 'hero16', run('hero16', '16_35', 2.9, 14.6), 0.55)
    s = run('frA16', '02_03', 2.9, 3.2, phase=8); s.loop(cyc('frA16', '35_28'), 1.6, speed=1.05, blend=0.7); s.clip('16_33', 0.3, 2.3, blend=0.5); s.clip('143_25', 1.0, 6.2, blend=0.6)
    s.loop(cyc('frA16', '35_28'), 3.6, speed=1.05, blend=0.7, turn=0.62, face=0.0); out('s05_frA', 'frA16', s, dict(l=0.12, r=0.4))
    s = run('frB16', '02_03', 2.9, 2.5, phase=30); s.loop(cyc('frB16', '02_01'), 2.0, speed=1.0, blend=0.7); s.clip('16_33', 0.3, 2.3, blend=0.5); s.clip('141_16', 0.2, 4.0, blend=0.6)
    s.loop(cyc('frB16', '02_01'), 5.4, speed=1.0, blend=0.7, turn=0.66, face=0.0); out('s05_frB', 'frB16', s, dict(l=0.4, r=0.12))
@shot
def s06():  # alone, into the sun
    out('s06_hero', 'hero16', run('hero16', '16_46', 3.9, 9.6, phase=5), 0.7)
@shot
def s07():  # 2020: the run becomes a walk
    s = run('hero23', '16_35', 2.7, 3.6); s.loop(cyc('hero23', '82_10', (14.0, 15.0)), 6.2, speed=1.0, blend=1.1); out('s07_hero', 'hero23', s, 0.4)
@shot
def s08():  # the hoarding
    s = Seq(C('hero23')); s.clip('139_02', 1.0, 7.6); s.loop(cyc('hero23', '82_10', (14.0, 15.0)), 3.6, blend=0.7, face=0.0); out('s08_hero', 'hero23', s, 0.4, norm=True)
@shot
def s09():  # the light on the horizon
    s = Seq(C('hero23')); s.loop(cyc('hero23', '82_10', (12.0, 12.0)), 5.0); s.clip('16_33', 0.3, 2.3, blend=0.6); s.clip('139_02', 2.0, 4.6, blend=0.5); s.clip('127_04', 1.3, 3.6, blend=0.5, face=0.0)
    run('hero23', '16_46', 4.3, 5.6, s, blend=0.4); out('s09_hero', 'hero23', s, 0.6, marks=dict(stop=7.2, go=10.3))
@shot
def s10():  # the first barrier
    s = run('hero23', '16_46', 4.3, 3.6); s.clip('127_05', 0.1, 2.7, blend=0.25); s.clip('40_10', 0.5, 9.4, blend=0.5); out('s10_hero', 'hero23', s, 0.5, marks=dict(stop=5.6))
@shot
def s11():  # the second barrier; he sits down
    s = run('hero23', '16_35', 2.7, 4.0); s.loop(cyc('hero23', '16_58'), 5.0, speed=0.95, blend=0.9, turn=-0.85); s.clip('13_05', 0.5, 6.6, blend=0.6, look=None); out('s11_hero', 'hero23', s, 0.35, marks=dict(seat=14.6))
@shot
def s12():  # the wait
    s = Seq(C('hero23')); s.clip('13_05', 3.4, 10.2, speed=0.56, look=None); out('s12_hero', 'hero23', s, 0.35, norm=True, marks=dict(seat=6.0), rep=False)
@shot
def s13():  # friends come back; everyone is on the road
    s = Seq(C('hero23')); s.clip('13_05', 6.0, 10.3, speed=0.66, look=None); s.clip('13_05', 10.3, 12.3, blend=0.1, look=None); import json; hs = (MARKS.get('s11_hero') or json.load(open(DATA + 'marks.json'))['s11_hero'])['seat'][3]
    s.loop(cyc('hero23', '16_58'), 3.2, blend=0.6, turn=-hs); run('hero23', '16_35', 2.8, 8.0, s, blend=0.9)
    out('s13_hero', 'hero23', s, 0.4, norm=True, marks=dict(seat=1.0, up=8.6))
    s = run('frA23', '02_03', 2.8, 3.0); s.clip('143_02', 0.3, 3.3, blend=0.3); s.clip('141_16', 0.2, 3.8, blend=0.5); s.loop(cyc('frA23', '35_28'), 2.2, blend=0.6, face=0.0); run('frA23', '02_03', 2.8, 7.0, s, blend=0.9, face=0.0); out('s13_frA', 'frA23', s, dict(l=0.4, r=0.12), marks=dict(stop=5.5))
    s = run('frB23', '16_35', 2.8, 3.8, phase=14); s.clip('16_57', 0.1, 2.1, blend=0.3); s.clip('139_02', 3.0, 6.3, blend=0.5); s.loop(cyc('frB23', '02_01'), 2.4, blend=0.6, face=0.0); run('frB23', '16_35', 2.8, 7.0, s, blend=0.9, face=0.0); out('s13_frB', 'frB23', s, 0.4, marks=dict(stop=5.6))
    crowd()
def crowd():
    for cn, lst in (('manA', (('walk', '35_28', 1.2), ('jog', '16_35', 2.7), ('run', '35_22', 3.4))), ('womA', (('walk', '02_01', 1.15), ('jog', '02_03', 2.6), ('run', '09_05', 3.3))),
                    ('manB', (('walk', '08_03', 1.6), ('jog', '16_35', 2.9), ('run', '16_45', 3.9))), ('teenC', (('walk', '16_58', 1.3), ('jog', '02_03', 2.7), ('run', '16_46', 4.0)))):
        for nm, clip, v in lst: out(f'cr_{cn}_{nm}', cn, run(cn, clip, v, 19.0), 0.5, rep=False)
@shot
def s14():  # the gate at midnight
    s = Seq(C('hero23')); s.clip('139_02', 1.0, 12.0); s.loop(cyc('hero23', '16_58'), 1.8, blend=0.6, face=0.0); run('hero23', '16_35', 2.9, 3.6, s, blend=0.7, face=0.0); out('s14_hero', 'hero23', s, 0.4, norm=True)
    s = Seq(C('frA23')); s.clip('40_10', 0.5, 11.2); s.clip('142_09', 1.6, 4.4, blend=0.4); run('frA23', '02_03', 3.0, 3.0, s, blend=0.5, face=0.0); out('s14_frA', 'frA23', s, 0.4, norm=True)
    s = Seq(C('frB23')); s.clip('137_41', 4.0, 15.4); s.loop(cyc('frB23', '02_01'), 1.4, blend=0.6, face=0.0); run('frB23', '16_35', 2.9, 4.0, s, blend=0.7, face=0.0); out('s14_frB', 'frB23', s, 0.4, norm=True)
    for cn, wclip, t0, joy, rclip, v in (('manA', '40_10', 22.0, False, '35_22', 3.3), ('womA', '137_41', 20.0, True, '09_05', 3.2), ('manB', '139_02', 1.5, True, '16_45', 3.8), ('teenC', '40_10', 0.5, False, '16_46', 4.0)):
        s = Seq(C(cn)); s.clip(wclip, t0, t0 + 11.0)
        if joy: s.clip('142_09', 1.6, 4.3, blend=0.4)
        run(cn, rclip, v, 6.5 if not joy else 4.0, s, blend=0.6, face=0.0); out(f'crg_{cn}', cn, s, 0.45, norm=True, rep=False)
@shot
def s15():  # the goodbye: he turns; far down the road the boy of 2013 waves; he waves back; the boy walks home
    s = Seq(C('hero23')); s.loop(cyc('hero23', '16_58'), 1.3); s.clip('36_02', 2.5, 5.0, blend=0.5); s.clip('139_02', 4.0, 11.5, blend=0.5); s.clip('139_02', 2.0, 11.5, blend=0.9); s.clip('139_02', 3.0, 9.0, blend=0.9)
    out('s15_hero', 'hero23', s, dict(l=0.4, r=0.1), marks=dict(turned=4.2), layer=lambda ch, Q, root: wave(ch, Q, root, 8.6, side='r', swings=7, hz=2.3, amp=21, height=0.12, lower_t=0.9))
    s = Seq(C('hero10')); s.clip('139_02', 1.0, 11.0); s.clip('139_02', 2.5, 10.5, blend=0.9); cy = cyc('hero10', '16_58'); s.loop(cy, 2.4, speed=0.9, blend=0.7, turn=np.pi); s.loop(cy, 7.0, speed=0.95, blend=0.4)
    out('s15_kid', 'hero10', s, dict(l=0.45, r=0.06), norm=True, rep=False, marks=dict(stand=1.0), layer=lambda ch, Q, root: wave(ch, Q, root, 5.0, side='r', swings=16, hz=2.9, amp=31, height=0.9, big=1.6, lower_t=0.8))
@shot
def m1():  # the wait goes on: he looks down into a puddle
    s = Seq(C('hero23')); s.clip('13_05', 3.6, 9.6, speed=0.56, look=None); out('m1_hero', 'hero23', s, 0.35, norm=True, marks=dict(seat=5.0), rep=False)
@shot
def m2():  # the room: the same bed edge at ten, sixteen and twenty-three
    def sit(name, cn, t0, sp, seed, lively=1.0, pad=True, cheers=(), stop=1e9, curl=0.72):
        s = Seq(C(cn)); s.clip('13_05', t0, t0 + 13.5 * sp, speed=sp, look=None)
        def lay(ch, Q, root):
            if pad: Q = hold_pad(ch, Q, root, 0.0, stop, seed=seed, lively=lively)
            else: Q = turn_subtree(ch, Q.copy(), 'spine_01', _frame(ch, Q, 61)[1] * np.radians(9.0))
            for tc in cheers: Q = cheer(ch, Q, root, tc, big=1.0)
            return Q
        out(name, cn, s, curl if pad else 0.5, norm=True, marks=dict(seat=4.0), rep=False, layer=lay)
    sit('m2_hero10', 'hero10', 3.4, 0.56, 3, 1.25); sit('m2_frA10', 'frA10', 3.9, 0.50, 5, pad=False, cheers=(2.6, 6.1)); sit('m2_frB10', 'frB10', 4.3, 0.52, 7, pad=False, cheers=(6.2,))
    sit('m2_hero16', 'hero16', 3.6, 0.54, 11, 1.0); sit('m2_frB16', 'frB16', 4.1, 0.5, 13, pad=False, cheers=(3.4,))
    sit('m2_hero23', 'hero23', 3.5, 0.52, 17, 0.6, stop=3.2)
@shot
def s16b():  # the boulevard: they slow to a walk and look up
    out('s16b_hero', 'hero23', run('hero23', '16_58', 1.25, 15.0, phase=4, look=(2.0, -6.0)), 0.4); out('s16b_frA', 'frA23', run('frA23', '35_28', 1.25, 15.0, phase=12, look=(2.0, -8.0)), 0.4); out('s16b_frB', 'frB23', run('frB23', '02_01', 1.25, 15.0, phase=20, look=(2.0, -6.0)), 0.4)
    for cn, clip in (('manA', '35_28'), ('womA', '02_01'), ('manB', '08_03'), ('teenC', '16_58')): out(f'cw_{cn}', cn, run(cn, clip, 1.3, 16.0, look=(2.0, -7.0)), 0.5, rep=False)
@shot
def s16():  # through the gate
    out('s16_hero', 'hero23', run('hero23', '16_46', 4.2, 17.0, phase=9), 0.6); out('s16_frA', 'frA23', run('frA23', '16_45', 4.1, 17.0, phase=2), 0.6); out('s16_frB', 'frB23', run('frB23', '35_22', 3.9, 17.0, phase=25), 0.6)
@shot
def s17():  # the next stone
    s = run('hero23', '16_35', 2.8, 2.6); s.clip('143_02', 0.3, 3.0, blend=0.3); s.clip('139_02', 5.0, 7.4, blend=0.5); s.clip('104_06', 0.4, 2.7, blend=0.5, face=0.0); out('s17_hero', 'hero23', s, 0.5, marks=dict(stop=4.6))
if __name__ == '__main__':
    import json, os
    for n in sys.argv[1:]: (SH.get(n) or globals()[n])()
    p = DATA + 'marks.json'; old = json.load(open(p)) if os.path.exists(p) else {}; old.update(MARKS); json.dump(old, open(p, 'w'))
