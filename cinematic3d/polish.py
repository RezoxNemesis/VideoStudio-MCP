"""Optional configured ComfyUI and RIFE/FILM execution with bounded evidence.

Trusted local configuration selects code, weights and a checksum-vetted graph.
Timeline JSON never selects a command, workflow or endpoint. Interpolation wrappers
receive exact output sample times, then report actual PNG files in ``manifest.json``.
Cut-separated segments are independent jobs; impact holds bypass the model entirely.
"""
from dataclasses import dataclass
from fractions import Fraction
import hashlib
import json
import math
import os
from pathlib import Path, PurePosixPath
import re
import selectors
import shutil
import signal
import struct
import subprocess
import sys
import tempfile
import time
from urllib.parse import urlencode, urlsplit
import uuid
import zlib

from .providers import (ProviderFailure, check_file, check_runner, check_source, check_timeout,
                        execute_runner, failure_receipt, read_json, sha256_file)


@dataclass(frozen=True)
class InterpolationConfig:
    family: str
    command: tuple[str, ...]
    weights: Path
    weights_sha256: str
    timeout_seconds: float = 120.0


@dataclass(frozen=True)
class ComfyModel:
    path: Path
    sha256: str
    node_id: str
    input_name: str
    model_name: str | None = None


@dataclass(frozen=True)
class ComfyUIConfig:
    endpoint: str
    workflow_file: Path
    workflow_sha256: str
    allowed_node_types: tuple[str, ...]
    models: tuple[ComfyModel, ...]
    image_input_binding: tuple[str, str]
    output_nodes: tuple[str, ...]
    timeout_seconds: float = 120.0
    poll_interval_seconds: float = 0.25


def _png_bytes(data):
    """Validate PNG chunks, CRCs, bounded decompression and complete raster size."""
    if len(data) > 32*1024*1024 or not data.startswith(b"\x89PNG\r\n\x1a\n"):
        raise ValueError("Expected a bounded PNG")
    offset, compressed, header, ended = 8, bytearray(), None, False
    while offset < len(data):
        if offset + 12 > len(data): raise ValueError("Truncated PNG")
        length = struct.unpack_from(">I",data,offset)[0]
        if length > 32*1024*1024 or offset+12+length > len(data): raise ValueError("Truncated PNG chunk")
        kind, body = data[offset+4:offset+8], data[offset+8:offset+8+length]
        crc = struct.unpack_from(">I",data,offset+8+length)[0]
        if zlib.crc32(kind+body)&0xffffffff != crc: raise ValueError("Invalid PNG CRC")
        if header is None and kind != b"IHDR": raise ValueError("PNG requires first IHDR")
        if kind == b"IHDR":
            if header is not None or len(body) != 13: raise ValueError("Invalid PNG IHDR")
            header=struct.unpack(">IIBBBBB",body)
        elif kind == b"IDAT": compressed.extend(body)
        elif kind == b"IEND":
            if length or offset+12 != len(data): raise ValueError("Invalid PNG end")
            ended=True
        offset += 12+length
    if header is None or not ended or not compressed: raise ValueError("Incomplete PNG")
    width,height,bits,color,compression,filtering,interlace=header
    valid_bits={0:(1,2,4,8,16),2:(8,16),3:(1,2,4,8),4:(8,16),6:(8,16)}
    if not 1<=width<=8192 or not 1<=height<=8192 or bits not in valid_bits.get(color,()) or compression or filtering or interlace:
        raise ValueError("Unsupported PNG raster")
    channels={0:1,2:3,3:1,4:2,6:4}[color]
    expected=((width*channels*bits+7)//8+1)*height
    if expected>256*1024*1024: raise ValueError("Oversized PNG raster")
    decoder=zlib.decompressobj()
    raster=decoder.decompress(compressed,expected+1)
    if len(raster)!=expected or decoder.unconsumed_tail or decoder.unused_data or not decoder.eof:
        raise ValueError("Invalid PNG raster length")
    row_bytes=(width*channels*bits+7)//8+1
    if any(raster[i] > 4 for i in range(0,len(raster),row_bytes)): raise ValueError("Invalid PNG row filter")
    return width,height


def _png_file(path):
    path=Path(path)
    if path.is_symlink() or not path.is_file() or path.stat().st_size>32*1024*1024: raise ValueError("Invalid PNG file")
    return _png_bytes(path.read_bytes())


def _output_directory(path):
    directory=Path(path).expanduser().absolute()
    if directory.is_symlink() or (directory.exists() and (not directory.is_dir() or any(directory.iterdir()))):
        raise ProviderFailure("OUTPUT_NOT_EMPTY")
    directory.mkdir(parents=True,exist_ok=True)
    return directory


def _relative_file(directory, relative):
    if not isinstance(relative,str) or not relative or len(relative)>512 or "\\" in relative or ":" in relative:
        raise ValueError("Invalid output path")
    name=PurePosixPath(relative)
    if name.is_absolute() or any(p in (".","..") for p in name.parts): raise ValueError("Invalid output path")
    candidate=directory.joinpath(*name.parts)
    current=candidate
    while current != directory:
        if current.is_symlink(): raise ValueError("Linked output path")
        current=current.parent
    candidate.resolve().relative_to(directory.resolve())
    return candidate


def _ceil(value):
    return -(-value.numerator//value.denominator)


def _integer_fps(value):
    return isinstance(value,int) and not isinstance(value,bool) and 1<=value<=240


def interpolate_frames(config,frames,input_fps,output_fps,output_dir,*,cuts=(),hit_stop_ranges=()):
    """Execute independent segments on an endpoint-exclusive rational frame clock."""
    if config is None:
        return failure_receipt(ProviderFailure("INTERPOLATION_NOT_CONFIGURED",status="unavailable"),"interpolation")
    provider=getattr(config,"family","interpolation")
    work=None
    try:
        if not isinstance(config,InterpolationConfig) or config.family not in ("rife","film"):
            raise ProviderFailure("INVALID_CONFIG")
        timeout=check_timeout(config.timeout_seconds)
        if not _integer_fps(input_fps) or not _integer_fps(output_fps) or output_fps<=input_fps or not isinstance(frames,(list,tuple)) or not 1<=len(frames)<=12000:
            raise ProviderFailure("INVALID_INTERPOLATION_INPUT")
        count=Fraction(len(frames)*output_fps,input_fps)
        if count.denominator != 1 or count>48000: raise ProviderFailure("NONINTEGRAL_OUTPUT_CLOCK")
        target_count=int(count)
        if not isinstance(cuts,(list,tuple)) or len(cuts)>80 or len(set(cuts))!=len(cuts) or any(isinstance(c,bool) or not isinstance(c,int) or not 0<c<len(frames) for c in cuts):
            raise ProviderFailure("INVALID_INTERPOLATION_INPUT")
        if not isinstance(hit_stop_ranges,(list,tuple)) or len(hit_stop_ranges)>80: raise ProviderFailure("INVALID_INTERPOLATION_INPUT")
        protected=set()
        boundaries={0,len(frames),*cuts}
        for interval in hit_stop_ranges:
            if not isinstance(interval,(list,tuple)) or len(interval)!=2 or any(isinstance(v,bool) or not isinstance(v,int) for v in interval): raise ProviderFailure("INVALID_INTERPOLATION_INPUT")
            start,end=interval
            if not 0<=start<end<=len(frames) or any(i in protected for i in range(start,end)): raise ProviderFailure("INVALID_INTERPOLATION_INPUT")
            protected.update(range(start,end));boundaries.update((start,end))
        sources=[Path(p).expanduser().resolve(strict=True) for p in frames]
        dimensions=[_png_file(p) for p in sources]
        if len(set(dimensions))!=1: raise ProviderFailure("FRAME_DIMENSIONS_MISMATCH")
        source_hashes=[sha256_file(p) for p in sources]
        command,weights=check_runner(config.command,config.weights,config.weights_sha256)
        directory=_output_directory(output_dir)
        work=directory/".runner";work.mkdir()
        deadline=time.monotonic()+timeout
        output_entries=[None]*target_count
        protected_outputs=[]
        runner_invocations=0
        bounds=sorted(boundaries)
        for segment,(start,end) in enumerate(zip(bounds,bounds[1:])):
            first,last=_ceil(Fraction(start*output_fps,input_fps)),_ceil(Fraction(end*output_fps,input_fps))
            indices=list(range(first,min(last,target_count)))
            if not indices: continue
            hold=start in protected
            generated=None
            if not hold:
                remaining=deadline-time.monotonic()
                if remaining<=0: raise ProviderFailure("RUNNER_TIMEOUT")
                segment_output=work/("segment-"+str(segment))
                request={"schema":"videostudio.cinematic3d.interpolation-request.v1","family":provider,
                         "frames":[str(p) for p in sources[start:end]],"sourceSha256":source_hashes[start:end],
                         "inputFps":input_fps,"outputFps":output_fps,"expectedFrameCount":len(indices),
                         "outputSampleTimesSeconds":[float(Fraction(i,output_fps)-Fraction(start,input_fps)) for i in indices],
                         "endpointPolicy":"hold_last_source","crossCutInterpolation":False}
                execute_runner(command,weights,request,segment_output,remaining)
                runner_invocations+=1
                try:
                    manifest=read_json(segment_output/"manifest.json",limit=512*1024)
                    if not isinstance(manifest,dict) or set(manifest)!={"schema","fps","frameCount","durationSeconds","frames"} or manifest["schema"]!="videostudio.cinematic3d.interpolation.v1": raise ValueError("Invalid interpolation manifest")
                    if type(manifest["fps"]) is not int or manifest["fps"]!=output_fps or type(manifest["frameCount"]) is not int or manifest["frameCount"]!=len(indices): raise ValueError("Wrong output clock")
                    duration=manifest["durationSeconds"]
                    if isinstance(duration,bool) or not isinstance(duration,(int,float)) or not math.isfinite(duration) or abs(duration-len(indices)/output_fps)>1e-9: raise ValueError("Wrong output duration")
                    if not isinstance(manifest["frames"],list) or len(manifest["frames"])!=len(indices) or len(set(manifest["frames"]))!=len(indices): raise ValueError("Wrong output frame files")
                    generated=[_relative_file(segment_output,p) for p in manifest["frames"]]
                    if any(_png_file(p)!=dimensions[0] for p in generated): raise ValueError("Wrong output raster")
                    if {p.resolve() for p in segment_output.rglob("*.png")} != {p.resolve() for p in generated}: raise ValueError("Undeclared output frames")
                except (OSError,ValueError,TypeError,KeyError,RecursionError,zlib.error):
                    raise ProviderFailure("INVALID_INTERPOLATION_OUTPUT") from None
            for offset,index in enumerate(indices):
                clock=Fraction(index*input_fps,output_fps)
                source_index=clock.numerator//clock.denominator
                # Original samples and the last-source interval are exact copies;
                # a neural runner cannot silently alter a cut or original frame.
                copied=hold or clock.denominator==1 or clock>=end-1
                source=sources[source_index] if copied else generated[offset]
                target=directory/("frame_%06d.png"%index)
                with source.open("rb") as stream,target.open("xb") as output:
                    shutil.copyfileobj(stream,output)
                if hold: protected_outputs.append(index)
                output_entries[index]={"frame":index,"timeSeconds":float(Fraction(index,output_fps)),
                                       "path":str(target),"bytes":target.stat().st_size,"sha256":sha256_file(target)}
        if any(entry is None for entry in output_entries): raise ProviderFailure("INVALID_INTERPOLATION_OUTPUT")
        if any(sha256_file(path)!=sha for path,sha in zip(sources,source_hashes)): raise ProviderFailure("SOURCE_CHANGED")
        check_file(weights,config.weights_sha256)
        return {"status":"completed","provider":provider,"fps":output_fps,"frameCount":target_count,
                "durationSeconds":target_count/output_fps,"frames":output_entries,"protectedOutputFrames":protected_outputs,
                "cuts":list(cuts),"evidence":{"runnerExecuted":runner_invocations>0,"runnerInvocationCount":runner_invocations,"weightsSha256":config.weights_sha256,
                "crossCutInterpolation":False,"originalImageReconstruction":False}}
    except ProviderFailure as error:
        return failure_receipt(error,provider)
    except (OSError,ValueError,TypeError,zlib.error):
        return failure_receipt(ProviderFailure("INVALID_INTERPOLATION_INPUT"),provider)
    finally:
        if work is not None: shutil.rmtree(work,ignore_errors=True)


# A persistent request worker preserves urllib's configured proxy and CA trust.
# Parent-side process cancellation bounds DNS, HTTP headers and trickled bodies,
# where socket idle timeouts and Python thread cancellation cannot do so safely.
# Paths/data arrive through private files; this code is fixed trusted source.
_HTTP_WORKER_CODE = r'''
import json,sys
from pathlib import Path
from urllib.error import HTTPError,URLError
from urllib.request import HTTPRedirectHandler,Request,build_opener
class NoRedirect(HTTPRedirectHandler):
    def redirect_request(self,*args,**kwargs): return None
opener=build_opener(NoRedirect())
for line in sys.stdin.buffer:
    try:
        control=json.loads(line)
        config=json.loads(Path(control['request']).read_text())
        body=Path(control['body']).read_bytes() if config['hasBody'] else None
        headers={'Content-Type':config['contentType']} if body is not None else {}
        request=Request(config['url'],data=body,headers=headers)
        with opener.open(request,timeout=config['socketTimeout']) as response,Path(control['response']).open('xb') as output:
            length=0
            while True:
                chunk=response.read(min(65536,config['limit']+1-length))
                if not chunk: break
                length+=len(chunk)
                if length>config['limit']: raise OverflowError()
                output.write(chunk)
        result={'sequence':control['sequence'],'status':'completed'}
    except TimeoutError:
        result={'sequence':control['sequence'],'status':'failed','code':'COMFYUI_TIMEOUT'}
    except URLError as error:
        timeout=isinstance(error.reason,TimeoutError)
        result={'sequence':control['sequence'],'status':'failed' if timeout else 'unavailable','code':'COMFYUI_TIMEOUT' if timeout else 'COMFYUI_UNAVAILABLE'}
    except (HTTPError,OSError):
        result={'sequence':control['sequence'],'status':'unavailable','code':'COMFYUI_UNAVAILABLE'}
    except OverflowError:
        result={'sequence':control['sequence'],'status':'failed','code':'COMFYUI_RESPONSE_TOO_LARGE'}
    except Exception:
        result={'sequence':control['sequence'],'status':'failed','code':'INVALID_COMFYUI_RESPONSE'}
    sys.stdout.write(json.dumps(result)+'\n');sys.stdout.flush()
'''


class _ComfyHTTP:
    def __init__(self,endpoint,deadline):
        parts=urlsplit(endpoint)
        if parts.scheme not in ("http","https") or not parts.netloc or parts.username or parts.password or parts.query or parts.fragment:
            raise ProviderFailure("INVALID_CONFIG")
        self.endpoint=endpoint.rstrip("/");self.deadline=deadline;self.sequence=0
        self.temporary=tempfile.TemporaryDirectory(prefix="c3d-comfy-http-")
        self.directory=Path(self.temporary.name)
        try:
            self.child=subprocess.Popen([sys.executable,"-c",_HTTP_WORKER_CODE],stdin=subprocess.PIPE,stdout=subprocess.PIPE,
                                        stderr=subprocess.DEVNULL,bufsize=0,start_new_session=True)
        except OSError:
            self.temporary.cleanup()
            raise ProviderFailure("COMFYUI_UNAVAILABLE",status="unavailable") from None

    def _abort(self):
        if self.child.poll() is None:
            try: os.killpg(self.child.pid,signal.SIGKILL)
            except ProcessLookupError: pass
            self.child.wait()

    def close(self):
        try:
            if self.child.stdin is not None: self.child.stdin.close()
            try: self.child.wait(timeout=.1)
            except subprocess.TimeoutExpired: self._abort()
        finally:
            if self.child.stdout is not None: self.child.stdout.close()
            self.temporary.cleanup()

    def request(self,path,*,data=None,content_type="application/json",raw=False,limit=512*1024):
        remaining=self.deadline-time.monotonic()
        if remaining<=0:
            self._abort()
            raise ProviderFailure("COMFYUI_TIMEOUT")
        body=data
        if data is not None and not isinstance(data,bytes): body=json.dumps(data,allow_nan=False).encode()
        self.sequence+=1
        request_file=self.directory/(str(self.sequence)+".json")
        body_file=self.directory/(str(self.sequence)+".body")
        response_file=self.directory/(str(self.sequence)+".response")
        request_file.write_text(json.dumps({"url":self.endpoint+path,"hasBody":body is not None,
            "contentType":content_type,"socketTimeout":min(remaining,10),"limit":limit}),encoding="utf-8")
        if body is not None: body_file.write_bytes(body)
        control={"sequence":self.sequence,"request":str(request_file),"body":str(body_file),"response":str(response_file)}
        try:
            self.child.stdin.write((json.dumps(control)+"\n").encode())
            acknowledgement=bytearray()
            with selectors.DefaultSelector() as selector:
                selector.register(self.child.stdout,selectors.EVENT_READ)
                while True:
                    remaining=self.deadline-time.monotonic()
                    if remaining<=0 or not selector.select(timeout=remaining):
                        self._abort()
                        raise ProviderFailure("COMFYUI_TIMEOUT")
                    part=os.read(self.child.stdout.fileno(),4096)
                    if not part: raise ProviderFailure("COMFYUI_UNAVAILABLE",status="unavailable")
                    acknowledgement.extend(part)
                    if len(acknowledgement)>4096: raise ProviderFailure("INVALID_COMFYUI_RESPONSE")
                    if b"\n" in acknowledgement: break
            status=json.loads(acknowledgement)
            if status.get("sequence")!=self.sequence: raise ProviderFailure("INVALID_COMFYUI_RESPONSE")
            if status.get("status")!="completed":
                code=status.get("code")
                if code not in ("COMFYUI_TIMEOUT","COMFYUI_UNAVAILABLE","COMFYUI_RESPONSE_TOO_LARGE","INVALID_COMFYUI_RESPONSE"): raise ProviderFailure("INVALID_COMFYUI_RESPONSE")
                raise ProviderFailure(code,status="unavailable" if code=="COMFYUI_UNAVAILABLE" else "failed")
            if time.monotonic()>=self.deadline:
                self._abort()
                raise ProviderFailure("COMFYUI_TIMEOUT")
            if response_file.stat().st_size>limit: raise ProviderFailure("COMFYUI_RESPONSE_TOO_LARGE")
            result=response_file.read_bytes()
            if time.monotonic()>=self.deadline:
                self._abort()
                raise ProviderFailure("COMFYUI_TIMEOUT")
        except (OSError,ValueError,TypeError):
            raise ProviderFailure("COMFYUI_UNAVAILABLE",status="unavailable") from None
        finally:
            for path in (request_file,body_file,response_file):
                path.unlink(missing_ok=True)
        if raw: return result
        try:
            def no_constant(_): raise ValueError("Nonfinite JSON")
            parsed=json.loads(result,parse_constant=no_constant)
            if time.monotonic()>=self.deadline:
                self._abort()
                raise ProviderFailure("COMFYUI_TIMEOUT")
            return parsed
        except (ValueError,UnicodeError,RecursionError): raise ProviderFailure("INVALID_COMFYUI_RESPONSE") from None


def _workflow(config):
    checked=check_file(config.workflow_file,config.workflow_sha256,missing_code="WORKFLOW_MISSING",mismatch_code="WORKFLOW_SHA256_MISMATCH")
    try:
        graph=read_json(checked,limit=512*1024)
        if not isinstance(graph,dict) or not 1<=len(graph)<=256 or not isinstance(config.allowed_node_types,(list,tuple)) or not config.allowed_node_types: raise ValueError("Invalid graph")
        for node_id,node in graph.items():
            if not re.fullmatch(r"[A-Za-z0-9_-]{1,80}",node_id) or not isinstance(node,dict) or not {"class_type","inputs"}<=set(node) or set(node)-{"class_type","inputs","_meta"}: raise ValueError("Invalid node")
            kind=node["class_type"]
            if not isinstance(kind,str) or kind not in config.allowed_node_types or any(word in kind.lower() for word in ("python","execute","shell","script")) or not isinstance(node["inputs"],dict): raise ValueError("Unvetted node")
        dependencies={node_id:set() for node_id in graph}
        for node_id,node in graph.items():
            for value in node["inputs"].values():
                if isinstance(value,list) and len(value)==2 and isinstance(value[0],str) and isinstance(value[1],int) and not isinstance(value[1],bool):
                    if value[0] not in graph or not 0<=value[1]<=255: raise ValueError("Dangling node reference")
                    dependencies[node_id].add(value[0])
        binding=config.image_input_binding
        if not isinstance(binding,(tuple,list)) or len(binding)!=2 or binding[0] not in graph or binding[1] not in graph[binding[0]]["inputs"] or graph[binding[0]]["class_type"]!="LoadImage": raise ValueError("Invalid image binding")
        if not isinstance(config.output_nodes,(tuple,list)) or not config.output_nodes or len(set(config.output_nodes))!=len(config.output_nodes) or any(n not in graph for n in config.output_nodes): raise ValueError("Invalid output bindings")
        visited,visiting=set(),set()
        def visit(node_id):
            if node_id in visiting: raise ValueError("Cyclic workflow")
            if node_id in visited: return
            visiting.add(node_id)
            for dependency in dependencies[node_id]: visit(dependency)
            visiting.remove(node_id);visited.add(node_id)
        for node_id in graph: visit(node_id)
        reachable,pending=set(),list(config.output_nodes)
        while pending:
            node_id=pending.pop()
            if node_id not in reachable:
                reachable.add(node_id);pending.extend(dependencies[node_id])
        if binding[0] not in reachable:
            raise ProviderFailure("DISCONNECTED_IMAGE_BINDING")
    except (OSError,ValueError,TypeError,KeyError,RecursionError): raise ProviderFailure("INVALID_COMFYUI_WORKFLOW") from None
    if not isinstance(config.models,(tuple,list)) or not config.models:
        raise ProviderFailure("MODEL_BINDINGS_REQUIRED",status="unavailable")
    models=[]
    for model in config.models:
        if not isinstance(model,ComfyModel): raise ProviderFailure("INVALID_CONFIG")
        checked_model=check_file(model.path,model.sha256)
        name=model.model_name or checked_model.name
        if model.node_id not in graph or model.input_name not in graph[model.node_id]["inputs"] or graph[model.node_id]["inputs"][model.input_name]!=name:
            raise ProviderFailure("INVALID_MODEL_BINDING")
        if model.node_id not in reachable:
            raise ProviderFailure("DISCONNECTED_MODEL_BINDING")
        models.append((model,checked_model,name))
    return graph,checked,models


def run_comfyui(config,input_image,source_sha256,output_dir):
    """Upload, queue a vetted graph, wait for its own history and fetch real outputs.

    ComfyUI's stock HTTP API exposes model names, not runtime checkpoint hashes.
    Receipts therefore distinguish verified local artifact hashes from the actual
    endpoint's loader-name evidence; they never assert a remote runtime SHA match.
    A timeout terminates and reaps this client's HTTP worker. Stock ComfyUI has
    no isolated running-prompt cancellation endpoint; server execution is then
    unmeasured, and its global interrupt endpoint is deliberately not invoked.
    """
    if config is None:
        return failure_receipt(ProviderFailure("COMFYUI_NOT_CONFIGURED",status="unavailable"),"comfyui")
    http=None
    prompt_id=None
    try:
        if not isinstance(config,ComfyUIConfig): raise ProviderFailure("INVALID_CONFIG")
        timeout=check_timeout(config.timeout_seconds)
        if isinstance(config.poll_interval_seconds,bool) or not isinstance(config.poll_interval_seconds,(int,float)) or not math.isfinite(config.poll_interval_seconds) or not 0<config.poll_interval_seconds<=10: raise ProviderFailure("INVALID_CONFIG")
        source=check_source(input_image,source_sha256)
        _png_file(source)
        graph,workflow_file,models=_workflow(config)
        http=_ComfyHTTP(config.endpoint,time.monotonic()+timeout)
        stats=http.request("/system_stats")
        registry=http.request("/object_info",limit=8*1024*1024)
        if not isinstance(stats,dict) or "system" not in stats or not isinstance(registry,dict): raise ProviderFailure("COMFYUI_NOT_READY",status="unavailable")
        if any(node["class_type"] not in registry for node in graph.values()): raise ProviderFailure("COMFYUI_NODE_UNAVAILABLE",status="unavailable")
        for model,checked,name in models:
            try:
                options=registry[graph[model.node_id]["class_type"]]["input"]["required"][model.input_name][0]
                if not isinstance(options,list) or name not in options: raise KeyError("Absent model")
            except (KeyError,TypeError,IndexError): raise ProviderFailure("COMFYUI_MODEL_UNAVAILABLE",status="unavailable") from None
        directory=_output_directory(output_dir)
        boundary="Cinematic"+uuid.uuid4().hex
        image=source.read_bytes()
        multipart=("--"+boundary+'\r\nContent-Disposition: form-data; name="image"; filename="c3d-'+uuid.uuid4().hex+'.png"\r\nContent-Type: image/png\r\n\r\n').encode()+image+("\r\n--"+boundary+'\r\nContent-Disposition: form-data; name="overwrite"\r\n\r\nfalse\r\n--'+boundary+"--\r\n").encode()
        uploaded=http.request("/upload/image",data=multipart,content_type="multipart/form-data; boundary="+boundary)
        if not isinstance(uploaded,dict) or not isinstance(uploaded.get("name"),str) or not isinstance(uploaded.get("subfolder",""),str): raise ProviderFailure("INVALID_COMFYUI_RESPONSE")
        uploaded_name=uploaded.get("subfolder","")+('/' if uploaded.get("subfolder") else '')+uploaded["name"]
        _relative_file(directory,uploaded_name)
        graph[config.image_input_binding[0]]["inputs"][config.image_input_binding[1]]=uploaded_name
        submission=http.request("/prompt",data={"prompt":graph,"client_id":uuid.uuid4().hex})
        prompt_id=submission.get("prompt_id") if isinstance(submission,dict) else None
        if not isinstance(prompt_id,str) or not re.fullmatch(r"[A-Za-z0-9_-]{1,128}",prompt_id): raise ProviderFailure("COMFYUI_SUBMISSION_FAILED")
        while True:
            history=http.request("/history/"+prompt_id)
            record=history.get(prompt_id) if isinstance(history,dict) else None
            if record is not None:
                if not isinstance(record,dict): raise ProviderFailure("INVALID_COMFYUI_RESPONSE")
                status=record.get("status",{})
                if not isinstance(status,dict): raise ProviderFailure("INVALID_COMFYUI_RESPONSE")
                if status.get("status_str")=="error" or any(isinstance(message,list) and message and message[0]=="execution_error" for message in status.get("messages",[])):
                    raise ProviderFailure("COMFYUI_EXECUTION_FAILED")
                if status.get("completed") is True: break
            remaining=http.deadline-time.monotonic()
            if remaining<=0: raise ProviderFailure("COMFYUI_TIMEOUT")
            time.sleep(min(config.poll_interval_seconds,remaining))
        outputs=record.get("outputs")
        if not isinstance(outputs,dict): raise ProviderFailure("INVALID_COMFYUI_OUTPUT")
        images=[]
        for node_id in config.output_nodes:
            node=outputs.get(node_id,{})
            if not isinstance(node,dict) or not isinstance(node.get("images"),list) or not node["images"]: raise ProviderFailure("INVALID_COMFYUI_OUTPUT")
            for metadata in node["images"]:
                if len(images)>=256 or not isinstance(metadata,dict): raise ProviderFailure("INVALID_COMFYUI_OUTPUT")
                filename,subfolder,kind=metadata.get("filename"),metadata.get("subfolder",""),metadata.get("type")
                if not isinstance(filename,str) or "/" in filename or not isinstance(subfolder,str) or kind!="output": raise ProviderFailure("INVALID_COMFYUI_OUTPUT")
                _relative_file(directory,(subfolder+"/" if subfolder else "")+filename)
                data=http.request("/view?"+urlencode({"filename":filename,"subfolder":subfolder,"type":kind}),raw=True,limit=32*1024*1024)
                _png_bytes(data)
                path=directory/("output_%03d.png"%len(images))
                with path.open("xb") as stream: stream.write(data)
                images.append({"path":str(path),"bytes":len(data),"sha256":hashlib.sha256(data).hexdigest(),"nodeId":node_id})
        check_source(source,source_sha256)
        check_file(workflow_file,config.workflow_sha256,missing_code="WORKFLOW_MISSING",mismatch_code="WORKFLOW_SHA256_MISMATCH")
        for model,checked,_ in models: check_file(checked,model.sha256)
        if time.monotonic()>=http.deadline: raise ProviderFailure("COMFYUI_TIMEOUT")
        return {"status":"completed","provider":"comfyui","promptId":prompt_id,"sourceSha256":source_sha256,"outputs":images,
                "evidence":{"workflowSha256":config.workflow_sha256,"modelArtifacts":[{"name":name,"sha256":model.sha256} for model,_,name in models],
                "modelChecksumScope":"configured_local_artifacts","runtimeModelNamesVerified":True,"runtimeWeightsSha256Verified":None,
                "historyCompleted":True,"originalImageReconstruction":False}}
    except ProviderFailure as error:
        receipt=failure_receipt(error,"comfyui")
        if error.code=="COMFYUI_TIMEOUT" and prompt_id is not None:
            receipt.update({"promptId":prompt_id,"serverCancellationPerformed":False,
                            "serverExecutionState":"unmeasured_after_client_timeout"})
        return receipt
    except (OSError,ValueError,TypeError,KeyError,RecursionError,zlib.error):
        return failure_receipt(ProviderFailure("INVALID_COMFYUI_OUTPUT"),"comfyui")
    finally:
        if http is not None: http.close()
