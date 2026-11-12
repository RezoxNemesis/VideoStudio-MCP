#!/usr/bin/env python3
"""Open up Kokoro's prosody: split the ONNX graph where it decides timing and pitch, so both can be read and set.

   dur.onnx  : tokens, style, speed                    -> frames (float per token: how long each phoneme lasts, 40 frames = 1 s)
   pros.onnx : tokens, style, durations                -> f0, energy (the model's own melody and loudness for those durations; 80 values = 1 s)
   voc.onnx  : tokens, style, durations, f0, energy    -> audio (24 kHz)

   Running dur -> round -> pros -> voc gives exactly what the unsplit model gives. Changing durations, f0 or energy in
   between is how VolTune shapes tone (questions, emphasis, moods) and how it sings.

   usage: split_kokoro.py <kokoro-v1.0[.int8].onnx> <out dir>
"""
import os
import sys
import onnx
from onnx.utils import Extractor

DUR_F = "/encoder/Div_output_0"
DUR_I = "/encoder/Cast_output_0"
F0 = "/encoder/F0_proj/Conv_output_0"
EN = "/encoder/N_proj/Conv_output_0"
NICE = {DUR_F: "frames", DUR_I: "durations", F0: "f0", EN: "energy"}


def rename(model):
    g = model.graph
    for node in g.node:
        for i, n in enumerate(node.input):
            if n in NICE:
                node.input[i] = NICE[n]
        for i, n in enumerate(node.output):
            if n in NICE:
                node.output[i] = NICE[n]
    for v in list(g.input) + list(g.output) + list(g.value_info):
        if v.name in NICE:
            v.name = NICE[v.name]
    return model


def main(src, out):
    os.makedirs(out, exist_ok=True)
    m = onnx.load(src)
    produced = {o for n in m.graph.node for o in n.output}
    for t in (DUR_F, DUR_I, F0, EN):
        if t not in produced:
            raise SystemExit(f"This model has no tensor {t}: it is not the Kokoro v1.0 export this tool knows.")
    e = Extractor(m)
    for name, ins, outs in (
        ("dur", ["tokens", "style", "speed"], [DUR_F]),
        ("pros", ["tokens", "style", DUR_I], [F0, EN]),
        ("voc", ["tokens", "style", DUR_I, F0, EN], ["audio"]),
    ):
        sub = rename(e.extract_model(ins, outs))
        onnx.save(sub, f"{out}/{name}.onnx")
        print(name, len(sub.graph.node), "nodes", [i.name for i in sub.graph.input], "->", [o.name for o in sub.graph.output])


if __name__ == "__main__":
    if len(sys.argv) != 3:
        raise SystemExit(__doc__)
    main(sys.argv[1], sys.argv[2])
