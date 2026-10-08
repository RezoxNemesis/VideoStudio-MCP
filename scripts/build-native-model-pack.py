#!/usr/bin/env python3
"""Package local, licensed ONNX weights; never download or invent model files."""
import argparse, hashlib, json, pathlib, zipfile

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--kind', required=True, choices=['raft', 'sd-turbo'])
    parser.add_argument('--source', required=True, type=pathlib.Path)
    parser.add_argument('--output', required=True, type=pathlib.Path)
    parser.add_argument('--id', required=True)
    parser.add_argument('--version', default='1')
    parser.add_argument('--license-name', required=True)
    parser.add_argument('--license-file', required=True, type=pathlib.Path)
    parser.add_argument('--hidden-size', type=int, default=1024, choices=[768, 1024])
    parser.add_argument('--text-phase-mb', type=int, default=3400)
    parser.add_argument('--unet-phase-mb', type=int, default=1800)
    parser.add_argument('--vae-phase-mb', type=int, default=768)
    parser.add_argument('--raft-output', help='Actual final flow tensor name, inspected from the model')
    args = parser.parse_args()
    root = args.source.resolve()
    if not root.is_dir() or not args.license_file.is_file():
        parser.error('Local weight folder and actual model licence file are required')
    if not args.id or any(c not in 'abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789._-' for c in args.id):
        parser.error('Pack ID contains invalid characters')
    if args.output.resolve().is_relative_to(root):
        parser.error('Write the output ZIP outside the source folder')
    required = ['raft.onnx'] if args.kind == 'raft' else ['text_encoder/model.onnx', 'unet/model.onnx', 'vae_decoder/model.onnx', 'vocab.json', 'merges.txt']
    if any(not (root/name).is_file() for name in required):
        parser.error('Missing required files: ' + ', '.join(name for name in required if not (root/name).is_file()))
    files = {}
    for file in sorted(root.rglob('*')):
        if file.is_symlink():
            parser.error('Source cannot contain symlinks')
        if not file.is_file() or file.name == 'manifest.json':
            continue
        with file.open('rb') as stream:
            files[file.relative_to(root).as_posix()] = hashlib.file_digest(stream, 'sha256').hexdigest()
    manifest = {'id': args.id, 'version': args.version, 'license': args.license_name, 'files': files,
                'estimatedRamMb': 2048, 'runtimeValidation': 'unverified-requires-device-self-test'}
    if args.kind == 'raft':
        if not args.raft_output:
            parser.error('--raft-output requires the model’s actual final flow tensor name')
        manifest.update(backend='onnx-raft-v1', capabilities=['temporal.flow'])
        manifest['raft'] = {'model': 'raft.onnx', 'width': 480, 'height': 360,
                            'firstInput': '0', 'secondInput': '1', 'output': args.raft_output}
    else:
        manifest.update(backend='onnx-sd-turbo-v1', capabilities=['image.generate'])
        manifest['neural'] = {'textEncoder': required[0], 'unet': required[1], 'vaeDecoder': required[2],
                             'vocabulary': 'vocab.json', 'merges': 'merges.txt', 'bos': 49406, 'eos': 49407, 'pad': 49407,
                             'hiddenSize': args.hidden_size, 'predictionType': 'epsilon', 'sigma': 14.6146, 'timestep': 999, 'vaeScale': .18215,
                             'phaseWorkingSetMb': {'textEncoder': args.text_phase_mb, 'unet': args.unet_phase_mb, 'vaeDecoder': args.vae_phase_mb}}
    licence = args.license_file.read_bytes()
    manifest['files']['MODEL_LICENSE.txt'] = hashlib.sha256(licence).hexdigest()
    args.output.parent.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(args.output, 'w', zipfile.ZIP_DEFLATED, allowZip64=True) as archive:
        archive.writestr('manifest.json', json.dumps(manifest, indent=2))
        archive.writestr('MODEL_LICENSE.txt', licence)
        for name in files:
            if name != 'MODEL_LICENSE.txt':
                archive.write(root/name, name)
    with args.output.open('rb') as stream:
        digest = hashlib.file_digest(stream, 'sha256').hexdigest()
    print(json.dumps({'path': str(args.output), 'sha256': digest, 'validatedInference': False}))

if __name__ == '__main__':
    main()
