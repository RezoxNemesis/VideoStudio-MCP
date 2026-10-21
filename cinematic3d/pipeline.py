"""Verified, atomic cloud generation publication. Never represents Android execution."""
from __future__ import annotations
import hashlib
import json
import os
from pathlib import Path, PurePosixPath
import re
import shutil
import struct
import subprocess
import tempfile
from fractions import Fraction
import zipfile
from .timeline import validate_timeline
from .characters import select_library_actions
from .conditioning import verify_conditioning

MAX_JSON = 512 * 1024
MAX_GLB_JSON = 1024 * 1024
MAX_EXPANDED = 256 * 1024 * 1024
MAX_ARCHIVE = 64 * 1024 * 1024
MAX_GLB = 16 * 1024 * 1024


def digest(path: Path) -> str:
    value=hashlib.sha256()
    with path.open('rb') as stream:
        for block in iter(lambda:stream.read(128*1024), b''):value.update(block)
    return value.hexdigest()


def read_json(path: Path) -> dict:
    if path.is_symlink() or path.stat().st_size > MAX_JSON:raise ValueError('JSON artifact exceeds its budget or is a symlink')
    def pairs(values):
        result={}
        for key,value in values:
            if key in result:raise ValueError('Duplicate JSON key')
            result[key]=value
        return result
    try:
        result=json.loads(path.read_text(encoding='utf-8'),object_pairs_hook=pairs,
                          parse_constant=lambda value:(_ for _ in ()).throw(ValueError('Nonfinite JSON')))
    except (UnicodeError,RecursionError,json.JSONDecodeError) as error:raise ValueError('Malformed JSON artifact') from error
    if not isinstance(result,dict):raise ValueError('JSON artifact must be an object')
    stack=[(result,0)];nodes=0
    while stack:
        node,depth=stack.pop();nodes+=1
        if depth>16 or nodes>65536:raise ValueError('JSON artifact exceeds structural budget')
        if isinstance(node,dict):stack.extend((value,depth+1) for value in node.values())
        elif isinstance(node,list):stack.extend((value,depth+1) for value in node)
    return result


def owned_path(root: Path, name: str) -> Path:
    if not isinstance(name,str) or not re.fullmatch(r'[A-Za-z0-9_./-]{1,180}',name):raise ValueError('Invalid artifact path')
    parts=PurePosixPath(name)
    if parts.is_absolute() or any(part in ('.','..') for part in name.split('/')):raise ValueError('Artifact path traversal')
    cursor=root
    for part in parts.parts:
        cursor=cursor/part
        if cursor.is_symlink():raise ValueError('Artifact symlinks are unavailable')
    if not cursor.is_file() or not cursor.resolve().is_relative_to(root.resolve()):raise ValueError('Artifact is not an owned file')
    return cursor


def verify_glb(path: Path) -> None:
    data=path.read_bytes()
    if len(data)<20 or len(data)>MAX_GLB:raise ValueError('Scene GLB exceeds size budget')
    magic,version,length=struct.unpack_from('<III',data)
    if (magic,version,length)!=(0x46546c67,2,len(data)):raise ValueError('Invalid GLB header')
    size,kind=struct.unpack_from('<II',data,12)
    if kind!=0x4e4f534a or size%4 or size>MAX_GLB_JSON or 20+size>len(data):raise ValueError('Invalid GLB JSON chunk')
    try:document=json.loads(data[20:20+size].decode('utf-8'))
    except (ValueError,UnicodeError,RecursionError) as error:raise ValueError('Invalid GLB JSON') from error
    if document.get('asset',{}).get('version')!='2.0':raise ValueError('GLB must use glTF 2.0')
    for category in ('buffers','images'):
        for entry in document.get(category,[]):
            if 'uri' in entry:raise ValueError('Scene must embed all resources')
    offset=20+size
    while offset<len(data):
        if offset+8>len(data):raise ValueError('Truncated GLB chunk')
        amount,_=struct.unpack_from('<II',data,offset)
        if amount%4 or offset+8+amount>len(data):raise ValueError('Invalid GLB chunk bounds')
        offset+=8+amount


def verify_generation(folder: Path) -> tuple[dict,list[tuple[str,Path]]]:
    folder=Path(folder)
    if folder.is_symlink():raise ValueError('Generation directory is a symlink')
    manifest=read_json(owned_path(folder,'manifest.json'))
    if manifest.get('schema')!='videostudio.cinematic3d.bundle.v1':raise ValueError('Unknown cinematic bundle schema')
    files=manifest.get('files')
    if not isinstance(files,list) or not 1<=len(files)<=255:raise ValueError('Invalid generation file count')
    accepted=[];seen=set();expanded=(folder/'manifest.json').stat().st_size
    for record in files:
        if not isinstance(record,dict) or set(record)!={'path','role','bytes','sha256'}:raise ValueError('Invalid artifact descriptor')
        name=record['path']
        if name in seen or name=='manifest.json':raise ValueError('Duplicate/reserved artifact path')
        seen.add(name);path=owned_path(folder,name)
        if path.suffix.lower() not in ('.json','.glb','.blend','.mp4','.png','.jpg','.jpeg'):raise ValueError('Executable artifact is unavailable')
        if type(record['bytes']) is not int or record['bytes']<1 or path.stat().st_size!=record['bytes']:raise ValueError('Artifact size changed')
        expanded+=record['bytes']
        if expanded>MAX_EXPANDED:raise ValueError('Generation expansion exceeds budget')
        if not isinstance(record['sha256'],str) or not re.fullmatch(r'[a-f0-9]{64}',record['sha256']) or digest(path)!=record['sha256']:raise ValueError('Artifact bytes changed')
        if path.suffix=='.glb':verify_glb(path)
        if path.suffix=='.json':read_json(path)
        accepted.append((name,path))
    timeline=manifest.get('timelineFile')
    if timeline not in seen:raise ValueError('Missing bound action timeline')
    validate_timeline(read_json(owned_path(folder,timeline)))
    scenes=manifest.get('scenes')
    if not isinstance(scenes,list) or not 1<=len(scenes)<=80:raise ValueError('Invalid scene binding count')
    for scene in scenes:
        if scene.get('path') not in seen or not scene['path'].endswith('.glb'):raise ValueError('Scene is not a bound GLB')
        if scene.get('cameraReplay','timeline') not in ('timeline','authored'):
            raise ValueError('Camera replay must be timeline or authored')
    return manifest,accepted


def build_bundle(folder: Path, target: Path, *, include_authoring: bool=False) -> dict:
    folder,target=Path(folder),Path(target)
    manifest,files=verify_generation(folder)
    # Blender authoring data stays in the cloud generation. The phone consumes
    # inert GLB/timeline data and should not retain a large unused .blend file.
    excluded=[]
    if not include_authoring:
        excluded=[record['path'] for record in manifest['files'] if record['role']=='authoring']
        manifest=dict(manifest,files=[record for record in manifest['files'] if record['path'] not in excluded])
        files=[(name,path) for name,path in files if name not in excluded]
    target.parent.mkdir(parents=True,exist_ok=True)
    temporary=None
    try:
        with tempfile.NamedTemporaryFile(prefix='.cinematic-bundle-',suffix='.partial',dir=target.parent,delete=False) as stream:temporary=Path(stream.name)
        with zipfile.ZipFile(temporary,'w',compression=zipfile.ZIP_DEFLATED,compresslevel=6) as archive:
            # Archive the validated manifest snapshot and hash the exact streamed
            # artifact bytes, closing the verification-to-copy race.
            encoded=json.dumps(manifest,ensure_ascii=False,separators=(',',':'),allow_nan=False).encode('utf-8')
            if len(encoded)>MAX_JSON:raise ValueError('Manifest exceeds JSON budget')
            info=zipfile.ZipInfo('manifest.json',(1980,1,1,0,0,0));info.compress_type=zipfile.ZIP_DEFLATED;info.external_attr=0o100600<<16
            archive.writestr(info,encoded)
            records={record['path']:record for record in manifest['files']}
            expanded=len(encoded)
            for name,path in sorted(files):
                path=owned_path(folder,name)
                record=records[name];actual=hashlib.sha256();count=0
                info=zipfile.ZipInfo(name,(1980,1,1,0,0,0));info.compress_type=zipfile.ZIP_DEFLATED;info.external_attr=0o100600<<16
                with path.open('rb') as source,archive.open(info,'w') as destination:
                    while block:=source.read(128*1024):
                        count+=len(block)
                        if count>record['bytes']:raise ValueError('Artifact changed during packaging')
                        actual.update(block);destination.write(block)
                if count!=record['bytes'] or actual.hexdigest()!=record['sha256']:raise ValueError('Artifact changed during packaging')
                expanded+=count
                if expanded>MAX_EXPANDED:raise ValueError('Archive expansion exceeds native budget')
        if temporary.stat().st_size>MAX_ARCHIVE:raise ValueError('Compressed bundle exceeds64MiB')
        value={'ok':True,'path':str(target),'sha256':digest(temporary),'bytes':temporary.stat().st_size,
               'execution':'artifact_packaging','nativeRendered':False,'cinematicAcceptance':False,
               'producerEvidenceVerified':False,'excludedAuthoringFiles':excluded,'motionProvider':read_json(owned_path(folder,manifest['timelineFile']))['motionProvider']}
        os.replace(temporary,target);temporary=None
        return value
    finally:
        if temporary is not None:temporary.unlink(missing_ok=True)


def validate_video(path: Path, *,frame_count: int,fps: int,width: int,height: int) -> dict:
    if type(frame_count) is not int or not 1<=frame_count<=12000 or fps not in (24,30,60):raise ValueError('Invalid video clock')
    command=['ffprobe','-v','error','-select_streams','v:0','-count_frames','-show_entries',
             'stream=width,height,avg_frame_rate,nb_read_frames,duration,codec_name:format=duration','-of','json',str(path)]
    result=subprocess.run(command,capture_output=True,text=True,timeout=120,check=True)
    data=json.loads(result.stdout);streams=data.get('streams',[])
    if len(streams)!=1:raise ValueError('Video stream is unavailable')
    video=streams[0]
    count=int(video.get('nb_read_frames',0));rate=Fraction(video.get('avg_frame_rate','0/1'))
    duration=float(video.get('duration',data.get('format',{}).get('duration',0)))
    if count!=frame_count or rate!=fps or video.get('width')!=width or video.get('height')!=height or abs(duration-frame_count/fps)>.001:
        raise ValueError('Encoded video frame count, clock, dimensions or duration differs')
    return {'frameCount':count,'fps':fps,'durationSeconds':duration,'width':width,'height':height,
            'codec':video.get('codec_name'),'decodedFrameCountVerified':True,'execution':'cloud_blender_postprocess',
            'nativeRendered':False,'cinematicAcceptance':False,'sha256':digest(Path(path))}


def encode_video(frames: Path,target: Path,*,fps: int,frame_count: int,width: int,height: int) -> dict:
    frames,target=Path(frames),Path(target)
    if fps not in (24,30,60) or type(frame_count) is not int or not 1<=frame_count<=12000:raise ValueError('Invalid frame sequence clock')
    if any(type(value) is not int or value<16 or value>1920 or value%2 for value in (width,height)) or width*height>1920*1080:raise ValueError('Bounded even video dimensions required')
    for index in range(frame_count):
        frame=owned_path(frames,f'frame_{index:06d}.png')
        with frame.open('rb') as stream:header=stream.read(24)
        if header[:8]!=b'\x89PNG\r\n\x1a\n' or len(header)<24 or struct.unpack('>II',header[16:24])!=(width,height):raise ValueError('Incomplete or wrong-size source frames')
    target.parent.mkdir(parents=True,exist_ok=True);temporary=None
    try:
        with tempfile.NamedTemporaryFile(prefix='.cinematic-video-',suffix='.mp4',dir=target.parent,delete=False) as stream:temporary=Path(stream.name)
        subprocess.run(['ffmpeg','-nostdin','-v','error','-y','-framerate',str(fps),'-start_number','0','-i',
                        str(frames/'frame_%06d.png'),'-frames:v',str(frame_count),'-c:v','libx264','-threads','2',
                        '-pix_fmt','yuv420p','-movflags','+faststart',str(temporary)],capture_output=True,timeout=1800,check=True)
        result=validate_video(temporary,frame_count=frame_count,fps=fps,width=width,height=height)
        os.replace(temporary,target);temporary=None;result['path']=str(target);return result
    finally:
        if temporary is not None:temporary.unlink(missing_ok=True)


def build(timeline_path: Path,output: Path,*,blender: str='blender',library_only: bool=False,render_frames: int=0,
          conditioning_frames: int=0,selected_actions=None,source_request_bytes: bytes|None=None) -> dict:
    timeline=validate_timeline(read_json(Path(timeline_path)))
    if type(render_frames) is not int or not 0<=render_frames<=12000:raise ValueError('Render frame count must be0..12000')
    if type(conditioning_frames) is not int or not 0<=conditioning_frames<=1200:raise ValueError('Conditioning frame count must be0..1200')
    if selected_actions is not None:
        selected_actions=select_library_actions(selected_actions)
        if not library_only:raise ValueError('Selected actions require a library pack')
        if set(selected_actions)!={s['action'] for s in timeline['shots']}:raise ValueError('Selected actions must match declared timeline shots')
    output=Path(output)
    if output.exists():raise FileExistsError('Accepted generation already exists; choose a new output directory')
    output.parent.mkdir(parents=True,exist_ok=True)
    stage=Path(tempfile.mkdtemp(prefix='.cinematic-generation-',dir=output.parent))
    try:
        request=stage/'request.json';request.write_text(json.dumps(timeline,ensure_ascii=False))
        generation=stage/'generation'
        command=[blender,'--background','--factory-startup','--disable-autoexec','-t','2','--python-exit-code','23',
                 '--python',str(Path(__file__).with_name('blender_scene.py')),'--','--timeline',str(request),'--output',str(generation)]
        if library_only:command.append('--library-only')
        if render_frames:command.extend(['--render-frames',str(render_frames)])
        if conditioning_frames:command.extend(['--conditioning-frames',str(conditioning_frames)])
        if selected_actions is not None:command.extend(['--actions',','.join(selected_actions)])
        with (stage/'blender.log').open('wb') as log:subprocess.run(command,stdout=log,stderr=subprocess.STDOUT,timeout=1800,check=True)
        manifest,_=verify_generation(generation)
        conditioning=None
        if conditioning_frames:
            conditioning=verify_conditioning(generation/'conditioning')
            if conditioning['frameCount']!=min(conditioning_frames,sum(s['durationFrames'] for s in timeline['shots'])):
                raise ValueError('Conditioning output clock differs from its request')
        if source_request_bytes is not None:
            if not isinstance(source_request_bytes,bytes) or not 0<len(source_request_bytes)<=MAX_JSON:raise ValueError('Bounded source request bytes required')
            source_request=generation/'source-request.json';source_request.write_bytes(source_request_bytes)
            request_data=read_json(source_request)
            if request_data.get('schema')!='videostudio.cinematic3d.export-request.v1' or validate_timeline(request_data.get('timeline'))!=timeline:raise ValueError('Source request differs from the generated timeline')
            manifest['files'].append({'path':'source-request.json','role':'provenance','bytes':source_request.stat().st_size,'sha256':digest(source_request)})
            (generation/'manifest.json').write_text(json.dumps(manifest,indent=2)+'\n')
        video=None
        if render_frames:
            available=min(render_frames,sum(shot['durationFrames'] for shot in timeline['shots']))
            first=generation/'frames'/'frame_000000.png'
            with first.open('rb') as stream:header=stream.read(24)
            width,height=struct.unpack('>II',header[16:24])
            video=encode_video(generation/'frames',generation/'master.mp4',fps=timeline['fps'],frame_count=available,width=width,height=height)
            data=generation/'master.mp4';manifest['files'].append({'path':'master.mp4','role':'video','bytes':data.stat().st_size,'sha256':digest(data)})
            (generation/'manifest.json').write_text(json.dumps(manifest,indent=2)+'\n')
        build_bundle(generation,generation/'cinematic.vscinematic')
        shutil.copyfile(stage/'blender.log',generation/'blender-execution.log')
        os.rename(generation,output)
        result={'ok':True,'output':str(output),'bundleSha256':digest(output/'cinematic.vscinematic'),
                'execution':'cloud_blender','nativeRendered':False,'cinematicAcceptance':False,'video':video}
        if conditioning is not None:
            conditioning['path']=str((output/'conditioning'/'manifest.json').absolute())
            result['conditioning']=conditioning
        if source_request_bytes is not None:result["sourceRequestSha256"]=hashlib.sha256(source_request_bytes).hexdigest()
        if video:result['video']['path']=str(output/'master.mp4')
        (output/'execution-receipt.json').write_text(json.dumps(result,indent=2)+'\n')
        return result
    except BaseException:
        # Preserve bounded diagnostic evidence separately without activating a partial generation.
        log=stage/'blender.log'
        if log.is_file():shutil.copyfile(log,output.parent/(output.name+'.failed.log'))
        raise
    finally:shutil.rmtree(stage,ignore_errors=True)
