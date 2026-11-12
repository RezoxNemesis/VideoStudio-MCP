"""VSPK container: one JSON header + named typed arrays, read zero-copy by src/stage/pack.ts.

Layout: b'VSPK' | u32 version | u32 headerBytes (padded to 8) | u32 0 | header JSON | blob.
Header: {"meta": {...}, "arrays": {name: [byteOffset, elementCount, dtype]}}; offsets are relative to the blob.
"""
import json, struct
import numpy as np

DT = {'float32': 'f32', 'uint16': 'u16', 'uint32': 'u32', 'int16': 'i16', 'uint8': 'u8', 'int8': 'i8', 'int32': 'i32'}


def write_pack(path, meta, arrays):
    index = {}
    blob = bytearray()
    for name, a in arrays.items():
        a = np.ascontiguousarray(a)
        if a.dtype.name not in DT:
            raise TypeError(f'{name}: unsupported dtype {a.dtype}')
        off = len(blob)
        blob += a.tobytes()
        while len(blob) % 8:
            blob.append(0)
        index[name] = [off, int(a.size), DT[a.dtype.name]]
    head = json.dumps({'meta': meta, 'arrays': index}, separators=(',', ':')).encode()
    head += b' ' * ((-len(head)) % 8)
    with open(path, 'wb') as f:
        f.write(b'VSPK' + struct.pack('<III', 1, len(head), 0) + head + bytes(blob))
    return 16 + len(head) + len(blob)


def read_pack(path):
    raw = open(path, 'rb').read()
    assert raw[:4] == b'VSPK'
    _v, hl, _ = struct.unpack('<III', raw[4:16])
    head = json.loads(raw[16:16 + hl])
    base = 16 + hl
    inv = {v: k for k, v in DT.items()}
    arrays = {k: np.frombuffer(raw, dtype=inv[t], count=n, offset=base + off) for k, (off, n, t) in head['arrays'].items()}
    return head['meta'], arrays
