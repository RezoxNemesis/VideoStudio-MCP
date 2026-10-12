"""Remove redundant GLB metadata without changing authored replay geometry.

Blender's NLA sampler emits constant channels for every property. Constants that
never change in any clip and equal a node's rest transform need no channel.
Properties animated by any clip retain their constant reset channels in all
other clips. Named cameras/world roots always retain explicit channels.
"""
import hashlib
import json
import math
import struct

CRITICAL_NODES = {"BlackRoot", "WhiteRoot", "CinematicCamera"}
DIMENSIONS = {"SCALAR": 1, "VEC2": 2, "VEC3": 3, "VEC4": 4, "MAT2": 4, "MAT3": 9, "MAT4": 16}
COMPONENT_BYTES = {5120: 1, 5121: 1, 5122: 2, 5123: 2, 5125: 4, 5126: 4}
DEFAULTS = {"translation": [0, 0, 0], "rotation": [0, 0, 0, 1], "scale": [1, 1, 1]}


def _accessor_payload(document, binary, accessor):
    if "sparse" in accessor or "bufferView" not in accessor:
        return None
    view = document["bufferViews"][accessor["bufferView"]]
    if view.get("buffer", 0) != 0:
        raise ValueError("Producer GLB must embed its sole binary buffer")
    dimensions = DIMENSIONS[accessor["type"]]
    width = dimensions * COMPONENT_BYTES[accessor["componentType"]]
    stride = view.get("byteStride", width)
    offset = view.get("byteOffset", 0) + accessor.get("byteOffset", 0)
    count = accessor["count"]
    if count < 0 or stride < width or offset < 0 or (count and offset + (count - 1) * stride + width > len(binary)):
        raise ValueError("Producer accessor lies outside the embedded GLB buffer")
    return b"".join(binary[offset + n * stride:offset + n * stride + width] for n in range(count))


def _constant_value(document, binary, accessor_index):
    accessor = document["accessors"][accessor_index]
    if accessor["componentType"] != 5126 or accessor["type"] not in {"VEC3", "VEC4"}:
        return None
    payload = _accessor_payload(document, binary, accessor)
    if payload is None or not accessor["count"]:
        return None
    dimensions = DIMENSIONS[accessor["type"]]
    values = list(struct.iter_unpack("<" + "f" * dimensions, payload))
    first = values[0]
    if not all(math.isfinite(x) for row in values for x in row):
        raise ValueError("Producer animation contains a nonfinite transform")
    return list(first) if all(all(abs(a - b) <= 1e-6 for a, b in zip(first, row)) for row in values) else None


def _same(left, right):
    return len(left) == len(right) and all(abs(a - b) <= 1e-6 for a, b in zip(left, right))


def _accessor_references(document):
    """Yield mutable containers/key slots for standard producer accessor refs."""
    for mesh in document.get("meshes", []):
        for primitive in mesh.get("primitives", []):
            for key in primitive.get("attributes", {}):
                yield primitive["attributes"], key
            if "indices" in primitive:
                yield primitive, "indices"
            for target in primitive.get("targets", []):
                for key in target:
                    yield target, key
    for skin in document.get("skins", []):
        if "inverseBindMatrices" in skin:
            yield skin, "inverseBindMatrices"
    for animation in document.get("animations", []):
        for sampler in animation.get("samplers", []):
            yield sampler, "input"
            yield sampler, "output"


def compact_glb(data):
    """Return compact bytes and measured metadata counts; preserve BIN bytes."""
    if len(data) < 28 or struct.unpack_from("<III", data) != (0x46546c67, 2, len(data)):
        raise ValueError("Invalid producer GLB header")
    json_length, chunk_kind = struct.unpack_from("<II", data, 12)
    binary_header = 20 + json_length
    if chunk_kind != 0x4e4f534a or binary_header + 8 > len(data):
        raise ValueError("Invalid producer GLB JSON chunk")
    binary_length, binary_kind = struct.unpack_from("<II", data, binary_header)
    if binary_kind != 0x004e4942 or binary_header + 8 + binary_length != len(data):
        raise ValueError("Producer GLB must have exactly one embedded BIN chunk")
    document = json.loads(data[20:binary_header])
    binary = data[binary_header + 8:]
    properties = {}
    for animation in document.get("animations", []):
        for channel in animation.get("channels", []):
            target = channel["target"]
            node, path = target.get("node"), target.get("path")
            if node is None or path not in DEFAULTS or document["nodes"][node].get("name") in CRITICAL_NODES:
                continue
            sampler = animation["samplers"][channel["sampler"]]
            value = None if sampler.get("interpolation", "LINEAR") != "LINEAR" else _constant_value(document, binary, sampler["output"])
            properties.setdefault((node, path), []).append(value)
    redundant = set()
    for (node, path), values in properties.items():
        baseline = document["nodes"][node].get(path, DEFAULTS[path])
        if all(value is not None and _same(value, baseline) for value in values):
            redundant.add((node, path))
    removed = 0
    for animation in document.get("animations", []):
        channels = []
        used_samplers = {}
        samplers = []
        sampler_keys = {}
        for channel in animation.get("channels", []):
            target = channel["target"]
            if (target.get("node"), target.get("path")) in redundant:
                removed += 1
                continue
            old = channel["sampler"]
            sampler = animation["samplers"][old]
            key = json.dumps(sampler, sort_keys=True, separators=(",", ":"))
            if key not in sampler_keys:
                sampler_keys[key] = len(samplers)
                samplers.append(sampler)
            used_samplers[old] = sampler_keys[key]
            channel["sampler"] = used_samplers[old]
            channels.append(channel)
        animation["channels"], animation["samplers"] = channels, samplers
    # Deduplicate equal accessors by actual decoded bytes, not exporter indices.
    references = list(_accessor_references(document))
    used = sorted({container[key] for container, key in references})
    accessor_map, accessors, canonical = {}, [], {}
    for old in used:
        accessor = document["accessors"][old]
        payload = _accessor_payload(document, binary, accessor)
        metadata = {k: v for k, v in accessor.items() if k not in {"bufferView", "byteOffset"}}
        signature = (json.dumps(metadata, sort_keys=True, separators=(",", ":")), hashlib.sha256(payload).digest()) if payload is not None else ("opaque", old)
        if signature not in canonical:
            canonical[signature] = len(accessors)
            accessors.append(accessor)
        accessor_map[old] = canonical[signature]
    for container, key in references:
        container[key] = accessor_map[container[key]]
    old_accessor_count = len(document.get("accessors", []))
    document["accessors"] = accessors
    # Retain all embedded bytes, but drop metadata for unused buffer views.
    view_refs = []
    def visit(value):
        if isinstance(value, dict):
            for key, child in value.items():
                if key == "bufferView" and type(child) is int:
                    view_refs.append((value, key))
                elif key != "extras":
                    visit(child)
        elif isinstance(value, list):
            for child in value:
                visit(child)
    visit(document)
    view_indices = sorted({container[key] for container, key in view_refs})
    view_map = {old: new for new, old in enumerate(view_indices)}
    document["bufferViews"] = [document["bufferViews"][i] for i in view_indices]
    for container, key in view_refs:
        container[key] = view_map[container[key]]
    # Accessor dedup can also make distinct animation samplers identical.
    for animation in document.get("animations", []):
        samplers, indices = [], {}
        mapping = {}
        for old, sampler in enumerate(animation["samplers"]):
            key = json.dumps(sampler, sort_keys=True, separators=(",", ":"))
            if key not in indices:
                indices[key] = len(samplers)
                samplers.append(sampler)
            mapping[old] = indices[key]
        for channel in animation["channels"]:
            channel["sampler"] = mapping[channel["sampler"]]
        animation["samplers"] = samplers
    encoded = json.dumps(document, separators=(",", ":"), ensure_ascii=True, allow_nan=False).encode("utf-8")
    encoded += b" " * (-len(encoded) % 4)
    tail = data[binary_header:]
    result = struct.pack("<III", 0x46546c67, 2, 20 + len(encoded) + len(tail)) + struct.pack("<II", len(encoded), 0x4e4f534a) + encoded + tail
    return result, {"originalJsonBytes": json_length, "jsonBytes": len(encoded), "removedConstantChannels": removed,
                    "originalAccessorCount": old_accessor_count, "accessorCount": len(accessors)}
