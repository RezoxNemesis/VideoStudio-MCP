#!/usr/bin/env python3
"""Execute the Android GLSL assets in a headless ES context; hardware checks remain separate."""
import ctypes as c,ctypes.util,os,tempfile
os.environ.setdefault("MESA_SHADER_CACHE_DIR",tempfile.mkdtemp(prefix="videostudio-shader-cache-"))
E=c.CDLL(ctypes.util.find_library('EGL'))
E.eglGetProcAddress.restype=c.c_void_p;E.eglGetProcAddress.argtypes=[c.c_char_p]
get=c.CFUNCTYPE(c.c_void_p,c.c_uint,c.c_void_p,c.POINTER(c.c_int))(E.eglGetProcAddress(b'eglGetPlatformDisplayEXT'))
d=get(0x31DD,None,None)
E.eglInitialize.argtypes=[c.c_void_p,c.POINTER(c.c_int),c.POINTER(c.c_int)];E.eglInitialize.restype=c.c_uint
major=c.c_int();minor=c.c_int();assert E.eglInitialize(d,c.byref(major),c.byref(minor)), 'EGL initialisation failed'
E.eglChooseConfig.argtypes=[c.c_void_p,c.POINTER(c.c_int),c.POINTER(c.c_void_p),c.c_int,c.POINTER(c.c_int)];E.eglChooseConfig.restype=c.c_uint
attrs=(c.c_int*13)(0x3033,1,0x3040,4,0x3024,8,0x3023,8,0x3022,8,0x3021,8,0x3038)
config=c.c_void_p();n=c.c_int();assert E.eglChooseConfig(d,attrs,c.byref(config),1,c.byref(n)) and n.value, 'No EGL config'
E.eglCreatePbufferSurface.argtypes=[c.c_void_p,c.c_void_p,c.POINTER(c.c_int)];E.eglCreatePbufferSurface.restype=c.c_void_p
surface=E.eglCreatePbufferSurface(d,config,(c.c_int*5)(0x3057,32,0x3056,32,0x3038))
E.eglCreateContext.argtypes=[c.c_void_p,c.c_void_p,c.c_void_p,c.POINTER(c.c_int)];E.eglCreateContext.restype=c.c_void_p
ctx=E.eglCreateContext(d,config,None,(c.c_int*3)(0x3098,2,0x3038))
E.eglMakeCurrent.argtypes=[c.c_void_p,c.c_void_p,c.c_void_p,c.c_void_p];E.eglMakeCurrent.restype=c.c_uint
assert ctx and surface and E.eglMakeCurrent(d,surface,surface,ctx), 'No ES shader context'
gs=c.CFUNCTYPE(c.c_char_p,c.c_uint)(E.eglGetProcAddress(b'glGetString'));print('GL renderer',gs(0x1F01))
from pathlib import Path
root=Path(__file__).resolve().parents[1]/'android/app/src/main/res/raw'
def gl(name,result,*arguments):
    pointer=E.eglGetProcAddress(name.encode());assert pointer,name+' unavailable'
    return c.CFUNCTYPE(result,*arguments)(pointer)
create=gl('glCreateShader',c.c_uint,c.c_uint);source=gl('glShaderSource',None,c.c_uint,c.c_int,c.POINTER(c.c_char_p),c.POINTER(c.c_int));compile_shader=gl('glCompileShader',None,c.c_uint);get_shader=gl('glGetShaderiv',None,c.c_uint,c.c_uint,c.POINTER(c.c_int));shader_log=gl('glGetShaderInfoLog',None,c.c_uint,c.c_int,c.POINTER(c.c_int),c.c_char_p)
def shader(kind,path):
    shader_id=create(kind);encoded=path.read_bytes();value=(c.c_char_p*1)(encoded);source(shader_id,1,value,None);compile_shader(shader_id);ok=c.c_int();get_shader(shader_id,0x8B81,c.byref(ok))
    if not ok.value:
        message=c.create_string_buffer(16384);shader_log(shader_id,len(message),None,message);raise AssertionError(message.value.decode())
    return shader_id
vs=shader(0x8B31,root/'vertex_shader_clip_effect.glsl');fs=shader(0x8B30,root/'fragment_shader_clip_alpha.glsl')
program=gl('glCreateProgram',c.c_uint)();attach=gl('glAttachShader',None,c.c_uint,c.c_uint);attach(program,vs);attach(program,fs);gl('glLinkProgram',None,c.c_uint)(program)
ok=c.c_int();gl('glGetProgramiv',None,c.c_uint,c.c_uint,c.POINTER(c.c_int))(program,0x8B82,c.byref(ok));assert ok.value,'Shader link failed'
gl('glUseProgram',None,c.c_uint)(program)
attribute=gl('glGetAttribLocation',c.c_int,c.c_uint,c.c_char_p)(program,b'aFramePosition');vertices=(c.c_float*16)(-1,-1,0,1,1,-1,0,1,-1,1,0,1,1,1,0,1)
gl('glVertexAttribPointer',None,c.c_uint,c.c_int,c.c_uint,c.c_ubyte,c.c_int,c.c_void_p)(attribute,4,0x1406,0,0,vertices);gl('glEnableVertexAttribArray',None,c.c_uint)(attribute)
texture=c.c_uint();gl('glGenTextures',None,c.c_int,c.POINTER(c.c_uint))(1,c.byref(texture));gl('glActiveTexture',None,c.c_uint)(0x84C0);gl('glBindTexture',None,c.c_uint,c.c_uint)(0x0DE1,texture)
for parameter,value in [(0x2801,0x2600),(0x2800,0x2600),(0x2802,0x812F),(0x2803,0x812F)]:gl('glTexParameteri',None,c.c_uint,c.c_uint,c.c_int)(0x0DE1,parameter,value)
uniform=gl('glGetUniformLocation',c.c_int,c.c_uint,c.c_char_p);gl('glUniform1i',None,c.c_int,c.c_int)(uniform(program,b'uTexSampler'),0)
settings={'uAlphaScale':1,'uKeyEnabled':0,'uKeyColor':(0,1,0),'uKeyTolerance':.18,'uKeySoftness':.08,'uSpill':.35,'uMaskType':0,'uMaskCenter':(.5,.5),'uMaskSize':(.5,.5),'uMaskFeather':0,'uMaskRadius':0,'uMaskInvert':0,'uHdr':0}
def render(color=(255,0,0,255),**changes):
    values={**settings,**changes}
    for key,value in values.items():
        location=uniform(program,key.encode());assert location>=0,key+' missing from shader'
        if isinstance(value,tuple):gl('glUniform'+str(len(value))+'f',None,c.c_int,*([c.c_float]*len(value)))(location,*value)
        else:gl('glUniform1f',None,c.c_int,c.c_float)(location,value)
    pixel=(c.c_ubyte*4)(*color);gl('glTexImage2D',None,c.c_uint,c.c_int,c.c_int,c.c_int,c.c_int,c.c_int,c.c_uint,c.c_uint,c.c_void_p)(0x0DE1,0,0x1908,1,1,0,0x1908,0x1401,pixel)
    gl('glViewport',None,c.c_int,c.c_int,c.c_int,c.c_int)(0,0,32,32);gl('glDrawArrays',None,c.c_uint,c.c_int,c.c_int)(5,0,4)
    output=(c.c_ubyte*(32*32*4))();gl('glReadPixels',None,c.c_int,c.c_int,c.c_int,c.c_int,c.c_uint,c.c_uint,c.c_void_p)(0,0,32,32,0x1908,0x1401,output)
    assert gl('glGetError',c.c_uint)()==0,'GL execution failed'
    return lambda x,y:tuple(output[(y*32+x)*4:(y*32+x+1)*4])
checks=0
def check(condition,detail):
    global checks
    checks+=1;assert condition,detail
check(render()(16,16)==(255,0,0,255),'Default preserves pixels')
check(render(uAlphaScale=0)(16,16)[3]==0,'Opacity zero clears alpha')
check(abs(render(uAlphaScale=.5)(16,16)[3]-128)<=1,'Half opacity changes actual alpha')
check(abs(render((255,0,0,128),uAlphaScale=.5)(16,16)[3]-64)<=1,'Existing transparency multiplies correctly')
check(render((0,255,0,255),uKeyEnabled=1)(16,16)[3]==0,'Green is removed')
check(render((0,240,0,255),uKeyEnabled=1)(16,16)[3]==0,'Tolerance removes near-key colour')
check(render(uKeyEnabled=1)(16,16)[3]==255,'Red foreground is preserved')
check(render((0,0,255,255),uKeyEnabled=1)(16,16)[3]==255,'Blue foreground is preserved')
spill=render((30,200,30,255),uKeyEnabled=1,uKeyTolerance=.2,uKeySoftness=.3,uSpill=1)(16,16)
check(0<spill[3]<255,'Soft edge has partial alpha');check(spill[1]<200,'Spill suppression reduces the key channel')
ellipse=render(uMaskType=2);check(ellipse(16,16)[3]==255,'Ellipse centre remains');check(ellipse(1,1)[3]==0,'Ellipse clears exterior')
inverted=render(uMaskType=2,uMaskInvert=1);check(inverted(16,16)[3]==0 and inverted(1,1)[3]==255,'Inverse mask flips coverage')
rectangle=render(uMaskType=1,uMaskSize=(.8,.8));rounded=render(uMaskType=1,uMaskSize=(.8,.8),uMaskRadius=.3)
check(rectangle(5,5)[3]==255 and rounded(5,5)[3]==0,'Rounded corners differ from a rectangle')
feather=render(uMaskType=2,uMaskFeather=.2);check(0<feather(24,16)[3]<255,'Feather smooths the boundary')
print('PASS',checks,'executed GLSL pixel checks (software ES; Android compositor/device verification pending)')
E.eglMakeCurrent(d,None,None,None)
E.eglDestroyContext.argtypes=[c.c_void_p,c.c_void_p];E.eglDestroyContext(d,ctx)
E.eglDestroySurface.argtypes=[c.c_void_p,c.c_void_p];E.eglDestroySurface(d,surface)
E.eglTerminate.argtypes=[c.c_void_p];E.eglTerminate(d)
