#!/usr/bin/env python3
"""Author original editable vector artwork and a 100-frame articulated action score.

This is synthetic engineering artwork, not imported pixels or image-generation output.
Rendering belongs to SketchFrameRenderer (see SketchActionFilmTest).
"""
import json
import math
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / 'android/app/src/test/resources/sketch-animation'
W, H = 1000.0, 562.5
INK = '#FF172638'
SKIN = '#FFE9AE82'
TEAL = '#FF248F99'
LIGHT = '#FF63D7CF'
CORAL = '#FFF26455'
NAVY = '#FF263950'


def transform():
    return dict(x=0, y=0, scaleX=1, scaleY=1, rotation=0, anchorX=.5, anchorY=.5, opacity=1)


layers = []
rigs = []
landmarks = {}


def layer(name):
    value = dict(id=name, name=name.replace('_', ' ').title(), visible=True, locked=False,
                 opacity=1, transform=transform(), keys=[], objects=[])
    layers.append(value)
    return value


def obj(ly, name, kind, color, frame=0, exposure=100, fill=True, width=1.8, **kw):
    value = dict(id=name, kind=kind, color=color, frame=frame, exposure=exposure,
                 fill=fill, width=width/min(W,H), transform=transform(), keys=[])
    value.update(kw)
    ly['objects'].append(value)
    return value


def path(ly, name, color, commands, frame=0, exposure=100, fill=True, width=1.8):
    cmds=[]
    for c in commands:
        cmds.append([c[0]]+[round(v/(W if n%2==0 else H),6) for n,v in enumerate(c[1:])])
    return obj(ly,name,'path',color,frame,exposure,fill,width,commands=cmds)


def ellipse(ly,name,color,x,y,w,h,frame=0,exposure=100):
    return obj(ly,name,'ellipse',color,frame,exposure,bounds=dict(x=x/W,y=y/H,width=w/W,height=h/H))


def rect(ly,name,color,x,y,w,h,frame=0,exposure=100):
    return obj(ly,name,'rect',color,frame,exposure,bounds=dict(x=x/W,y=y/H,width=w/W,height=h/H))


def text(ly,name,value,x,y,w,h,size,color='#FFE8F1E9',frame=0,exposure=100):
    return obj(ly,name,'text',color,frame,exposure,text=value,fontFamily='sans-serif',
               fontSize=size/H,fontStyle='bold',textAlign='left',
               bounds=dict(x=x/W,y=y/H,width=w/W,height=h/H))


bg=layer('dusk_city')
sky=rect(bg,'sky','#FF283754',0,0,1000,562.5)
sky['gradient']=dict(startColor='#FF182D4C',endColor='#FFF0AB82',x0=0,y0=0,x1=0,y1=1)
ellipse(bg,'sun_halo','#33FFD2A4',675,48,210,210)
ellipse(bg,'setting_sun','#FFFFD9A8',711,82,140,140)
path(bg,'cloud_ribbons','#44E8CAD1',[
    ['M',0,112],['C',110,98,172,120,246,113],['C',347,98,400,116,480,102],
    ['C',630,88,707,95,820,81],['L',1000,81],['L',1000,92],
    ['C',752,107,542,117,340,132],['C',210,143,89,128,0,145],['Z']])
# A coherent original roofscape, not a moving background plate.
for i,(x,y,w,h) in enumerate([(0,214,92,185),(85,183,91,215),(181,237,60,158),(247,158,76,238),(329,210,100,187),(453,195,57,201),(514,223,95,175),(613,180,70,216),(691,251,64,146),(765,206,85,190),(855,152,66,244),(930,219,70,178)]):
    rect(bg,f'far_tower_{i}','#FF68727E',x,y,w,h)
    if i%2==0:
        rect(bg,f'tower_roof_{i}','#FF87928F',x+5,y-10,w-10,10)
    for row in range(3):
        rect(bg,f'window_{i}_{row}','#FFB7AD98',x+10,y+20+row*38,8,13)
path(bg,'distant_cable',INK,[['M',0,190],['Q',370,278,1000,160]],fill=False,width=2)
path(bg,'near_parapet','#FF283E53',[['M',0,374],['L',1000,344],['L',1000,407],['L',0,421],['Z']])
path(bg,'parapet_edge','#FFBCAC94',[['M',0,371],['L',1000,341],['L',1000,352],['L',0,385],['Z']])
path(bg,'roof','#FF263849',[['M',0,416],['L',1000,402],['L',1000,562.5],['L',0,562.5],['Z']])
path(bg,'roof_lit_plane','#FF40515D',[['M',0,420],['L',1000,407],['L',1000,478],['L',0,499],['Z']])
for i,x in enumerate([12,170,350,551,780,981]):
    path(bg,f'perspective_seam_{i}','#FF1A2B3C',[['M',500+(x-500)*.65,408],['L',x,562]],fill=False,width=2)
path(bg,'roof_seam','#FF617074',[['M',0,501],['L',1000,479]],fill=False,width=1)
# Foreground vents and pipes make the staging readable in all three views.
rect(bg,'vent_body','#FF1C3044',22,340,160,100)
path(bg,'vent_top','#FF688184',[['M',22,340],['L',61,319],['L',204,325],['L',182,340],['Z']])
for i in range(5):
    path(bg,f'vent_slit_{i}','#FF668084',[['M',40,356+i*13],['L',155,356+i*13]],fill=False,width=3)
path(bg,'right_pipe',INK,[['M',934,433],['L',934,306],['Q',934,288,912,288],['L',879,288]],fill=False,width=15)
path(bg,'pipe_light','#FF779394',[['M',930,424],['L',930,308],['Q',930,293,911,293],['L',881,293]],fill=False,width=3)


def actor(prefix, frame, duration, scale=1, tx=0, ty=0, legs=True):
    ly=layer(prefix+'_courier')
    bindings=[]
    def xy(x,y): return ((x-390)*scale+390+tx,(y-360)*scale+360+ty)
    def commands(cs):
        out=[]
        for c in cs:
            out.append([c[0]]+[v for pair in [xy(*c[i:i+2]) for i in range(1,len(c),2)] for v in pair])
        return out
    def p(name,color,cs,joint,fill=True,width=1.8):
        value=path(ly,prefix+'_'+name,color,commands(cs),frame,duration,fill,width*scale)
        if joint:bindings.append(dict(objectId=value['id'],jointId=joint))
        return value
    def shape(name,color,cs,joint):
        # A narrow ink contour is geometry, not a renderer-only effect.
        p(name,color,cs,joint)
        p(name+'_edge',INK,cs,joint,False,1.8)
    def e(name,color,x,y,w,h,joint):
        a,b=xy(x,y)
        value=ellipse(ly,prefix+'_'+name,color,a,b,w*scale,h*scale,frame,duration)
        if joint:bindings.append(dict(objectId=value['id'],jointId=joint))
        return value
    # Back arm is behind the torso, with elbow and wrist independently articulated.
    shape('back_upper',TEAL,[['M',365,291],['Q',343,298,331,322],['L',347,333],['L',378,309],['Z']],'back_upper')
    shape('back_fore',SKIN,[['M',333,320],['Q',320,338,311,357],['Q',307,369,317,372],['L',333,358],['L',349,331],['Z']],'back_fore')
    if legs:
        # Trousers are broad drawn silhouettes with an explicit bend, not line limbs.
        shape('back_thigh',NAVY,[['M',380,351],['L',405,359],['Q',390,396,355,411],['L',341,398],['Z']],'back_hip')
        p('back_thigh_shadow','#FF172A41',[['M',386,361],['Q',375,387,347,406],['L',341,398],['Z']],'back_hip')
        shape('back_shin',NAVY,[['M',341,397],['L',358,410],['L',320,463],['L',301,456],['Z']],'back_knee')
        shape('back_boot',INK,[['M',299,447],['L',320,451],['L',319,473],['Q',292,478,273,474],['Q',270,467,282,462],['L',295,459],['Z']],'back_ankle')
        p('back_boot_rim','#FFB5D0C4',[['M',277,472],['L',318,471]],'back_ankle',False,3)
        shape('front_thigh','#FF334D66',[['M',401,350],['L',426,358],['Q',437,389,451,409],['L',430,420],['Q',408,395,397,374],['Z']],'front_hip')
        p('front_thigh_light','#FF567082',[['M',410,364],['L',420,364],['L',444,408],['L',437,411],['Z']],'front_hip')
        shape('front_shin',NAVY,[['M',430,407],['L',451,405],['L',469,456],['L',447,462],['Z']],'front_knee')
        shape('front_boot',INK,[['M',446,448],['L',468,448],['L',477,461],['Q',506,466,508,476],['L',447,476],['Z']],'front_ankle')
        p('front_boot_rim','#FFC4D6C3',[['M',451,473],['L',503,473]],'front_ankle',False,3)
    # Jacket, padded seam and belt follow the chest, not a global slideshow pan.
    shape('jacket',TEAL,[['M',369,281],['Q',390,274,411,285],['L',424,314],['L',425,353],['Q',396,369,370,351],['L',359,321],['Z']],'chest')
    p('jacket_shadow','#FF12606F',[['M',367,294],['L',380,305],['L',386,350],['L',373,352],['L',359,320],['Z']],'chest')
    p('jacket_lit',LIGHT,[['M',394,285],['L',405,288],['L',415,314],['L',410,337],['L',402,336],['Z']],'chest')
    p('seam',INK,[['M',391,290],['L',399,352]],'chest',False,2)
    p('pocket',INK,[['M',368,312],['L',384,315],['L',383,329],['L',372,326]],'chest',False,1.6)
    shape('belt','#FFB79163',[['M',372,346],['Q',398,357,425,346],['L',427,359],['Q',396,369,374,359],['Z']],'chest')
    p('buckle','#FFF3DEAB',[['M',395,353],['L',407,353],['L',407,362],['L',396,362],['Z']],'chest')
    # Sleeve, forearm cuff and mitten-like hand make a clear action silhouette.
    shape('front_upper',TEAL,[['M',407,288],['Q',423,287,438,307],['L',460,321],['L',448,340],['Q',415,330,402,310],['Z']],'front_upper')
    p('upper_glint',LIGHT,[['M',418,292],['Q',428,296,433,306],['L',449,318]],'front_upper',False,4)
    shape('front_fore',SKIN,[['M',448,322],['L',461,327],['Q',488,298,501,278],['L',488,270],['Q',462,289,448,322],['Z']],'front_fore')
    p('fore_shadow','#FFC17C65',[['M',456,325],['L',465,317],['L',497,276],['L',501,278],['L',461,327],['Z']],'front_fore')
    shape('cuff',NAVY,[['M',483,276],['L',496,263],['L',509,276],['L',498,289],['Z']],'front_fore')
    shape('hand',SKIN,[['M',491,264],['Q',486,252,495,248],['L',506,255],['Q',515,255,516,263],['L',509,278],['L',499,279],['Z']],'wrist')
    p('knuckle',INK,[['M',500,255],['L',507,260],['L',503,268]],'wrist',False,1.4)
    # Baton is a child of the wrist; blade/hand attachment is maintained by hierarchy.
    shape('baton',INK,[['M',499,253],['L',552,170],['Q',558,164,563,170],['L',513,261],['Z']],'wrist')
    p('baton_core','#FFB5FFE1',[['M',507,246],['L',557,174]],'wrist',False,4)
    p('baton_grip','#FFF4CF92',[['M',500,255],['L',513,261]],'wrist',False,4)
    # Neck, complete face silhouette, ear, hair, eye and expression are authored artwork.
    shape('neck',SKIN,[['M',380,265],['L',405,264],['L',410,285],['Q',396,296,381,285],['Z']],'head')
    shape('face',SKIN,[['M',371,221],['Q',390,203,410,216],['Q',423,229,422,244],['L',431,250],['L',423,256],['Q',423,270,409,277],['Q',388,280,375,264],['L',369,244],['Z']],'head')
    p('face_shadow','#FFC27E65',[['M',373,232],['Q',377,262,409,277],['Q',390,282,376,266],['L',369,245],['Z']],'head')
    e('ear',SKIN,366,238,14,20,'head')
    p('ear_line',INK,[['M',372,244],['Q',367,244,371,253]],'head',False,1.4)
    shape('hair','#FF192A3C',[['M',365,247],['Q',351,227,368,212],['L',365,203],['L',386,207],['Q',408,193,423,216],['L',420,232],['L',410,221],['L',395,224],['L',386,239],['L',378,232],['L',376,254],['Z']],'head')
    p('hair_rim','#FF426777',[['M',365,222],['Q',377,205,400,212],['L',391,216],['L',378,218]],'head',False,3)
    p('eye_white','#FFFFF1D2',[['M',400,240],['Q',410,235,420,240],['L',416,244],['L',402,244],['Z']],'head')
    e('iris',INK,410,238,5,7,'head')
    p('brow',INK,[['M',399,234],['Q',410,229,419,234]],'head',False,2.6)
    p('nose',INK,[['M',418,244],['L',424,252],['L',420,254]],'head',False,1.3)
    p('mouth',INK,[['M',410,262],['Q',416,264,422,260]],'head',False,1.6)
    # Scarf knot and tail use separate joints for follow-through.
    shape('scarf',CORAL,[['M',377,276],['Q',397,285,412,273],['L',416,284],['Q',397,300,379,289],['Z']],'chest')
    shape('tail',CORAL,[['M',379,280],['Q',349,269,319,282],['L',292,270],['L',303,292],['Q',343,294,379,289],['Z']],'scarf')
    p('tail_lit','#FFFFAC7E',[['M',303,286],['Q',345,281,373,285]],'scarf',False,2.2)
    # Rest positions are absolute artwork positions; parent-child transforms articulate them.
    joint_values=[('root',None,390,360),('chest','root',393,340),('head','chest',393,277),
        ('back_upper','chest',369,297),('back_fore','back_upper',338,326),
        ('front_upper','chest',414,300),('front_fore','front_upper',453,329),('wrist','front_fore',500,271),
        ('scarf','chest',379,284)]
    if legs:
        joint_values += [('back_hip','root',390,360),('back_knee','back_hip',348,403),('back_ankle','back_knee',307,461),
                        ('front_hip','root',410,360),('front_knee','front_hip',440,412),('front_ankle','front_knee',458,460)]
    joints=[]
    for name,parent,x,y in joint_values:
        x,y=xy(x,y)
        value=dict(id=name,x=x/W,y=y/H)
        if parent:value['parent']=parent
        joints.append(value)
    rig=dict(id=prefix+'_rig',joints=joints,bindings=bindings,poses=[])
    rigs.append(rig)
    landmarks[prefix]=dict(rest={j['id']:[j['x'],j['y']] for j in joints},frame=frame,exposure=duration)
    return rig,ly


shadow=layer('contact_shadows')
ellipse(shadow,'hero_shadow','#70101D30',265,465,250,20,0,36)
ellipse(shadow,'drone_shadow','#33101D30',717,467,145,12,0,36)
wide,wide_layer=actor('wide',0,36)


def pose(rig,frame,angles=None,x=0,y=0,interpolation='smooth'):
    rig['poses'].append(dict(frame=frame,interpolation=interpolation,rootX=x,rootY=y,rotations=angles or {}))


pose(wide,0,dict(head=-3,scarf=2))
pose(wide,5,dict(chest=-7,head=5,front_upper=-10,front_fore=-5,wrist=-7,back_upper=-10,scarf=14),y=.012)
pose(wide,11,dict(chest=-13,head=9,front_upper=-30,front_fore=-12,wrist=-8,back_upper=-20,scarf=28),y=.025)
pose(wide,16,dict(chest=8,head=-7,front_upper=20,front_fore=25,wrist=-2,back_upper=15,scarf=-24),x=.015,y=.006)
pose(wide,20,dict(chest=14,head=-5,front_upper=30,front_fore=42,wrist=10,back_upper=25,scarf=-36),x=.025,y=.004)
pose(wide,25,dict(chest=9,head=-4,front_upper=15,front_fore=10,wrist=3,back_upper=20,scarf=-27),x=.02)
pose(wide,30,dict(chest=2,head=-1,front_upper=5,front_fore=-2,wrist=0,back_upper=3,scarf=-9),x=.006)
pose(wide,35,dict(chest=-1,head=2,front_upper=5,front_fore=5,wrist=6,scarf=8))
# This is a planted parry, so weight shifts vertically inside the support polygon.
# Translating the hips away from near-extended ankle chains would be unreachable.
for beat in wide['poses']:
    beat['rootX']=0
wide['ik']=[dict(hip='front_hip',knee='front_knee',ankle='front_ankle',start=0,end=35,x=.458,y=460/H,bend=-1,rotation=0),
            dict(hip='back_hip',knee='back_knee',ankle='back_ankle',start=0,end=35,x=.307,y=461/H,bend=1,rotation=0)]

# Drone artwork remains independent of character rig and world camera.
drone_layer=layer('drone')
drone_bind=[]
def dp(name,color,cs,fill=True,width=1.8):
    value=path(drone_layer,'drone_'+name,color,cs,0,36,fill,width)
    drone_bind.append(dict(objectId=value['id'],jointId='root'))
    return value
dp('rotor_left','#AA8CE3CF',[['M',698,226],['Q',713,209,753,223],['Q',724,238,698,226],['Z']])
dp('rotor_right','#AA8CE3CF',[['M',799,223],['Q',836,207,855,222],['Q',827,237,799,223],['Z']])
dp('wings',INK,[['M',709,229],['L',752,234],['L',766,250],['L',788,250],['L',804,232],['L',846,224],['L',843,234],['L',811,244],['L',800,266],['L',753,266],['L',739,244],['L',711,240],['Z']])
dp('body','#FFC0C9BA',[['M',752,233],['Q',778,216,802,233],['L',811,258],['Q',781,281,746,258],['Z']])
dp('body_shadow','#FF63858C',[['M',747,252],['Q',780,265,807,248],['L',811,258],['Q',781,281,746,258],['Z']])
dp('lens_rim',INK,[['M',766,244],['Q',776,237,786,245],['Q',790,262,774,264],['Q',762,261,766,244],['Z']])
dp('lens','#FFFFB26C',[['M',772,246],['Q',780,240,784,248],['Q',785,258,777,260],['Q',769,257,772,246],['Z']])
dp('rim_light','#FFE5FFE0',[['M',756,234],['Q',777,224,797,235]],False,3)
drone=dict(id='drone_rig',joints=[dict(id='root',x=.777,y=250/H)],bindings=drone_bind,poses=[])
rigs.append(drone)
pose(drone,0,y=0)
pose(drone,9,y=-.009)
pose(drone,16,y=.005)
pose(drone,20,dict(root=-7),x=.01,y=-.008)
pose(drone,28,dict(root=11),x=.027,y=.012)
pose(drone,35,dict(root=5),x=.06,y=-.03)

# Hand-authored foreground POV: visibly different drawing, not a zoom on the wide.
pov=layer('pov_gauntlet')
pov_bind=[]
def pp(name,color,cs,joint='hand',fill=True,width=1.8):
    value=path(pov,'pov_'+name,color,cs,36,24,fill,width)
    if joint:pov_bind.append(dict(objectId=value['id'],jointId=joint))
    return value
pp('sleeve',INK,[['M',71,562],['L',137,420],['Q',173,384,229,396],['L',298,436],['L',297,562],['Z']])
pp('sleeve_fill',TEAL,[['M',80,562],['L',145,425],['Q',178,393,226,405],['L',287,441],['L',287,562],['Z']])
pp('sleeve_light',LIGHT,[['M',123,538],['L',170,430],['Q',196,414,224,428],['L',207,449],['Z']])
pp('cuff',NAVY,[['M',161,410],['L',201,352],['L',286,397],['L',256,454],['Z']])
pp('cuff_strip','#FFE0C499',[['M',172,402],['L',187,380],['L',275,421],['L',264,442],['Z']])
pp('palm',SKIN,[['M',201,359],['Q',198,332,213,309],['L',232,282],['Q',239,272,250,282],['L',251,314],['L',275,300],['Q',287,296,291,307],['L',286,329],['L',306,322],['Q',319,323,317,335],['L',307,353],['Q',331,344,334,358],['L',319,389],['Q',307,409,283,412],['L',239,397],['Z']])
pp('palm_shade','#FFC98166',[['M',210,361],['Q',242,384,284,399],['L',305,390],['Q',296,415,280,412],['L',239,397],['Z']])
pp('finger_detail',INK,[['M',239,324],['L',252,342],['M',274,324],['L',266,353],['M',300,342],['L',284,367],['M',313,368],['L',294,384]],fill=False,width=2.3)
pp('baton_body',INK,[['M',600,206],['L',790,112],['Q',808,104,813,115],['L',625,224],['Z']],'baton')
pp('baton_glow','#FFB9FFE1',[['M',615,210],['L',802,116]],'baton',False,5)
pov_rig=dict(id='pov_rig',joints=[dict(id='hand',x=.246,y=397/H)],bindings=[b for b in pov_bind if b['jointId']=='hand'],poses=[])
rigs.append(pov_rig)
pose(pov_rig,36,dict(hand=-8),x=0,y=.015)
pose(pov_rig,41,dict(hand=-3),x=.008,y=0)
pose(pov_rig,48,dict(hand=6),x=.015,y=-.02)
pose(pov_rig,54,dict(hand=3),x=.006,y=-.007)
pose(pov_rig,59,dict(hand=-1),x=0,y=.005)
pov_baton=dict(id='pov_baton_rig',joints=[dict(id='baton',x=.704,y=169/H)],bindings=[b for b in pov_bind if b['jointId']=='baton'],poses=[])
rigs.append(pov_baton)
pose(pov_baton,36,dict(baton=-10))
pose(pov_baton,41,dict(baton=15),x=-.06,y=.03)
pose(pov_baton,48,dict(baton=75),x=-.18,y=.10)
pose(pov_baton,54,dict(baton=140),x=-.30,y=.19)
pose(pov_baton,59,dict(baton=210),x=-.41,y=.25)

# Final medium-recovery framing uses original enlarged profile artwork. No legs outside canvas.
medium,medium_layer=actor('medium',60,40,scale=1.48,tx=80,ty=133,legs=False)
pose(medium,60,dict(chest=-5,head=4,front_upper=-26,front_fore=-30,wrist=-5,scarf=-15))
pose(medium,66,dict(chest=1,head=0,front_upper=-8,front_fore=-25,wrist=-18,scarf=-11),x=.003)
pose(medium,72,dict(chest=3,head=-3,front_upper=8,front_fore=-9,wrist=1,scarf=-2),y=.006)
pose(medium,78,dict(chest=0,head=-3,front_upper=4,front_fore=7,wrist=8,scarf=6))
pose(medium,84,dict(chest=-1,head=-5,front_upper=5,front_fore=4,wrist=5,scarf=5),y=-.003)
pose(medium,91,dict(chest=0,head=-3,front_upper=4,front_fore=3,wrist=6,scarf=1))
pose(medium,99,dict(chest=1,head=-2,front_upper=3,front_fore=4,wrist=5,scarf=-2),y=.002)

# These speech mouth replacements remain editable original path cels.
mouth=next(o for o in medium_layer['objects'] if o['id']=='medium_mouth')
mouth['frame']=60
mouth['exposure']=40
# Same headbinding and transforminheritance applies while compiler gates exposure.
def mouth_shape(name,cs):
    def m(x,y):return ((x-390)*1.48+470,(y-360)*1.48+493)
    remapped=[]
    for c in cs:remapped.append([c[0]]+[v for i in range(1,len(c),2) for v in m(*c[i:i+2])])
    v=path(medium_layer,name,INK,remapped,60,40)
    medium['bindings'].append(dict(objectId=name,jointId='head'))
    return v
mouth_shape('mouth_open',[['M',407,260],['Q',417,257,424,261],['Q',420,270,411,267],['Z']])
mouth_shape('mouth_rounded',[['M',414,260],['Q',423,257,424,264],['Q',421,271,415,267],['Z']])

effects=layer('drawn_action_effects')
# Bolt translation has a clear route to the deflection, never an all-frame dissolve.
bolt=path(effects,'incoming_bolt','#FFFFC16A',[['M',721,262],['L',683,276],['L',715,271],['L',733,263],['Z']],0,17)
bolt['keys']=[dict(frame=f,interpolation='linear',transform=dict(transform(),x=x,y=y)) for f,x,y in [(0,0,0),(5,-.035,.035),(10,-.075,.082),(16,-.142,.142)]]
ellipse(effects,'bolt_core','#FFFFF6C8',717,258,14,10,0,17)['keys']=json.loads(json.dumps(bolt['keys']))
for i,(angle,length) in enumerate([(0,66),(25,45),(60,63),(100,43),(145,54),(185,42),(220,67),(285,49),(325,63)]):
    a=math.radians(angle)
    cx,cy=585,343
    path(effects,f'impact_ray_{i}','#FFFFD696',[['M',cx+math.cos(a)*15,cy+math.sin(a)*15],['L',cx+math.cos(a)*length,cy+math.sin(a)*length]],16+i%2,5-i%2,False,3.5 if i%2 else 5)
ellipse(effects,'impact_flash','#FFFFF9D7',571,329,29,29,16,3)
path(effects,'swing_smear','#66B9FFE1',[['M',402,149],['Q',576,132,647,336],['Q',605,202,434,168],['Z']],14,5)
path(effects,'deflected_trail','#99FFD98E',[['M',585,343],['Q',675,201,813,123]],20,6,False,4)
# POV speed strokes are drawn individually; hand and baton still articulate.
for i,(x,y) in enumerate([(527,197),(696,300),(812,231),(421,111),(866,177)]):
    path(effects,f'pov_speed_{i}','#88D0F3D6',[['M',x,y],['L',x+42,y-22]],37+i,11,False,2)
for i in range(3):
    path(effects,f'catch_tick_{i}','#FFFFD49A',[['M',644+i*12,291-i*8],['L',654+i*15,276-i*10]],65,4,False,3)

dialog=layer('dialogue')
path(dialog,'speech_panel','#EE14283C',[['M',628,399],['L',939,399],['Q',954,399,954,414],['L',954,477],['Q',954,491,939,491],['L',667,491],['L',640,516],['L',644,491],['L',628,491],['Q',615,491,615,477],['L',615,414],['Q',615,399,628,399],['Z']],79,21)
text(dialog,'speaker','VALE',637,411,240,22,13,'#FF76DCD2',79,21)

letterbox=layer('cinema_frame')
rect(letterbox,'top_bar','#FF101F30',0,0,1000,27)
rect(letterbox,'bottom_bar','#FF101F30',0,535.5,1000,27)
text(letterbox,'film_title','ROOFTOP RELAY',29,6,350,19,13,'#FFD9E4DC')
text(letterbox,'film_credit','ORIGINAL VECTOR CEL ANIMATION',674,543,310,17,10,'#FF9CB4B6')

source=dict(schema='videostudio.sketch.v1',id='rooftop_relay_prepared',name='Rooftop Relay · Prepared original sketches',
            revision=1,width=1280,height=720,fps=24,frameCount=100,background='#FF182D4C',layers=layers)
# Soft cloth is genuinely deformed into editable per-frame geometry. Its attachment
# follows the chest while the free end responds to the scarf joint's follow-through.
for rig,ly,prefix,scale,tx in [(wide,wide_layer,'wide',1,0),(medium,medium_layer,'medium',1.48,80)]:
    for binding in rig['bindings']:
        if binding['objectId'] not in {prefix+'_tail',prefix+'_tail_edge',prefix+'_tail_lit'}:
            continue
        item=next(o for o in ly['objects'] if o['id']==binding['objectId'])
        weights=[]
        for command in item['commands']:
            for index in range(1,len(command),2):
                local_x=(command[index]*W-390-tx)/scale+390
                free=max(.05,min(.95,(379-local_x)/87))
                weights.append(dict(scarf=round(free,6),chest=round(1-free,6)))
        binding['weights']=weights
program=dict(schema='videostudio.sketch-animation.v1',name='Rooftop Relay · Original action study',frameCount=100,fps=24,
             rigs=rigs, cameraLayerIds=['dusk_city'],
             camera=[dict(frame=0,x=0,y=0,zoom=1,rotation=0,interpolation='hold'),
                     dict(frame=36,x=-.035,y=.035,zoom=1.15,rotation=-2,interpolation='hold'),
                     dict(frame=60,x=0,y=0,zoom=1,rotation=0,interpolation='hold')],
             dialogues=[dict(start=79,end=99,text='Not today.',layerId='dialogue',
                 bounds=dict(x=.637,y=436/H,width=.296,height=40/H),color='#FFFFEDD5',fontSize=28/H)],
             mouthCels=[dict(objectId='medium_mouth',start=60,end=78),
                 dict(objectId='mouth_open',start=79,end=82),dict(objectId='mouth_rounded',start=83,end=86),
                 dict(objectId='medium_mouth',start=87,end=89),dict(objectId='mouth_open',start=90,end=93),
                 dict(objectId='medium_mouth',start=94,end=99)])
# A prepared sketch should also look coherent when opened without its animation
# program. Put each alternative mouth cel on its first intended exposure rather
# than stacking all alternatives over the face. The program retains later repeats.
prepared_objects={item['id']:item for ly in layers for item in ly['objects']}
first_mouth_exposures=set()
for cue in program['mouthCels']:
    if cue['objectId'] in first_mouth_exposures:
        continue
    first_mouth_exposures.add(cue['objectId'])
    prepared_objects[cue['objectId']]['frame']=cue['start']
    prepared_objects[cue['objectId']]['exposure']=cue['end']-cue['start']+1
metadata=dict(title='Rooftop Relay',authoring='Original hand-authored vector paths; no source raster/image cel.',
              story='Courier Vale anticipates and deflects a scout drone bolt; a foreground POV follows the baton; a medium recovery ends with “Not today.”',
              frameCount=100,fps=24,shots=[dict(start=0,end=35,view='wide',beat='anticipate, planted deflection, follow-through'),
                   dict(start=36,end=59,view='POV',beat='foreground glove and returning baton'),
                   dict(start=60,end=99,view='medium recovery',beat='catch, settle, spoken response')],
              landmarks=landmarks)
OUT.mkdir(parents=True,exist_ok=True)
for name,value in [('rooftop-relay-source.json',source),('rooftop-relay-program.json',program),('rooftop-relay-authoring.json',metadata)]:
    (OUT/name).write_text(json.dumps(value,separators=(',',':'),ensure_ascii=False)+'\n')
print(json.dumps(dict(sourceObjects=sum(len(l['objects']) for l in layers),sourceLayers=len(layers),
                      rigBindings=sum(len(r['bindings']) for r in rigs),sourceBytes=len(json.dumps(source)),
                      output=str(OUT)),indent=2))
