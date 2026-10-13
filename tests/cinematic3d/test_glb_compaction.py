"""Semantic checks for the producer's GLB metadata compaction."""
import json
import struct
import unittest

from cinematic3d.portable_glb import compact_glb


def pack(document, binary):
    encoded = json.dumps(document, separators=(",", ":")).encode()
    encoded += b" " * (-len(encoded) % 4)
    binary += b"\0" * (-len(binary) % 4)
    return struct.pack("<III", 0x46546c67, 2, 28 + len(encoded) + len(binary)) + struct.pack("<II", len(encoded), 0x4e4f534a) + encoded + struct.pack("<II", len(binary), 0x004e4942) + binary


class GlbCompactionTest(unittest.TestCase):
    def test_removes_only_global_static_properties_and_preserves_switch_resets(self):
        binary = struct.pack("<2f6f8f6f", 0, 2, 1, 1, 1, 1, 1, 1, 0, 0, 0, 1, 0, 0, 0, 1, 0, 0, 0, 1, 0, 0)
        doc = {"asset": {"version": "2.0"}, "buffers": [{"byteLength": len(binary)}],
               "bufferViews": [{"buffer": 0, "byteOffset": 0, "byteLength": 8}, {"buffer": 0, "byteOffset": 8, "byteLength": 24},
                               {"buffer": 0, "byteOffset": 32, "byteLength": 32}, {"buffer": 0, "byteOffset": 64, "byteLength": 24}],
               "accessors": [{"bufferView": 0, "componentType": 5126, "count": 2, "type": "SCALAR"},
                             {"bufferView": 1, "componentType": 5126, "count": 2, "type": "VEC3"},
                             {"bufferView": 2, "componentType": 5126, "count": 2, "type": "VEC4"},
                             {"bufferView": 3, "componentType": 5126, "count": 2, "type": "VEC3"}],
               "nodes": [{"name": "BlackRoot"}, {"name": "CinematicCamera"}, {"name": "DEF-neck"}, {"name": "C3D_Spark_0"}],
               "animations": []}
        for name in ("C3D_STANDOFF", "C3D_CLASH"):
            doc["animations"].append({"name": name, "samplers": [{"input": 0, "output": 1}, {"input": 0, "output": 2}, {"input": 0, "output": 3}],
                "channels": [{"sampler": 0, "target": {"node": 0, "path": "scale"}},
                             {"sampler": 1, "target": {"node": 1, "path": "rotation"}},
                             {"sampler": 0, "target": {"node": 2, "path": "scale"}},
                             {"sampler": 1, "target": {"node": 2, "path": "rotation"}},
                             {"sampler": 2 if name == "C3D_CLASH" else 0, "target": {"node": 3, "path": "scale"}}]})
        result, metrics = compact_glb(pack(doc, binary))
        length = struct.unpack_from("<I", result, 12)[0]
        clean = json.loads(result[20:20 + length])
        self.assertEqual(metrics["removedConstantChannels"], 4)
        for animation in clean["animations"]:
            targets = {(c["target"]["node"], c["target"]["path"]) for c in animation["channels"]}
            self.assertIn((0, "scale"), targets)
            self.assertIn((1, "rotation"), targets)
            self.assertIn((3, "scale"), targets, "Static standby VFX reset must survive an animated clash property")
            self.assertFalse(any(node == 2 for node, _ in targets))
            self.assertTrue(all(c["sampler"] < len(animation["samplers"]) for c in animation["channels"]))
        self.assertEqual(result[20 + length + 8:], binary)

    def test_rejects_malformed_glb_before_metadata_rewrite(self):
        with self.assertRaises(ValueError):
            compact_glb(b"not a glb")


if __name__ == "__main__":
    unittest.main()
