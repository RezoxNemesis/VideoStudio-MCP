"""Generation publication and real FFmpeg output must verify before reporting success."""
import hashlib
import json
from pathlib import Path
import struct
import tempfile
import unittest
import zlib
import zipfile
from unittest.mock import patch
try:
    from cinematic3d import pipeline
except ImportError:
    pipeline = None


def png(path, colour):
    def chunk(kind, data):
        return struct.pack('>I', len(data)) + kind + data + struct.pack('>I', zlib.crc32(kind + data) & 0xffffffff)
    pixels = b''.join(b'\0' + bytes(colour) * 32 for _ in range(32))
    path.write_bytes(b'\x89PNG\r\n\x1a\n' + chunk(b'IHDR', struct.pack('>IIBBBBB',32,32,8,2,0,0,0))
                     + chunk(b'IDAT',zlib.compress(pixels)) + chunk(b'IEND',b''))


class PipelineTest(unittest.TestCase):
    def setUp(self):
        self.assertIsNotNone(pipeline, 'Verified cinematic generation publisher is missing')
        self.temp = tempfile.TemporaryDirectory(prefix='c3d-pipeline-')
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)

    def generation(self):
        folder=self.root/'generation'; folder.mkdir()
        timeline={'schema':'videostudio.cinematic3d.timeline.v1','name':'Test generation','fps':30,
                  'motionProvider':'procedural_mannequin','shots':[{'id':'shot_001','action':'CLASH','durationFrames':30,
                  'hitStopFrames':3,'camera':{'mode':'wide','fovDegrees':55,'shakeIntensity':.4},
                  'vfx':{'sparkBurst':True,'slashTrail':True,'rubble':False}}]}
        (folder/'timeline.json').write_text(json.dumps(timeline))
        document=json.dumps({'asset':{'version':'2.0'},'scenes':[{}],'nodes':[]}).encode()
        document+=b' '*((-len(document))%4)
        (folder/'scene.glb').write_bytes(struct.pack('<III',0x46546c67,2,20+len(document))
                                       +struct.pack('<II',len(document),0x4e4f534a)+document)
        files=[]
        for name,role in [('timeline.json','timeline'),('scene.glb','scene')]:
            data=(folder/name).read_bytes();files.append({'path':name,'role':role,'bytes':len(data),'sha256':hashlib.sha256(data).hexdigest()})
        manifest={'schema':'videostudio.cinematic3d.bundle.v1','timelineFile':'timeline.json','files':files,
                  'scenes':[{'shotId':'shot_001','action':'CLASH','path':'scene.glb','animationName':'C3D_CLASH',
                             'cameraName':'CinematicCamera','clipDurationSeconds':2,'impactPhase':.5}],
                  'generator':{'name':'fixture','actionLibrary':True},'evidence':{'nativeRenderEvidence':False}}
        (folder/'manifest.json').write_text(json.dumps(manifest))
        return folder,manifest

    def test_bundle_contains_only_bound_files_and_preserves_manifest_byte_proofs(self):
        folder,manifest=self.generation();target=self.root/'output.vscinematic'
        result=pipeline.build_bundle(folder,target)
        self.assertEqual(result['sha256'],hashlib.sha256(target.read_bytes()).hexdigest())
        self.assertFalse(result['nativeRendered'])
        with zipfile.ZipFile(target) as archive:
            self.assertEqual(set(archive.namelist()),{'manifest.json','timeline.json','scene.glb'})
            for item in manifest['files']:
                self.assertEqual(hashlib.sha256(archive.read(item['path'])).hexdigest(),item['sha256'])

    def test_authored_camera_policy_is_preserved_and_unknown_values_do_not_publish(self):
        folder,manifest=self.generation()
        scene=manifest['scenes'][0]
        scene['cameraReplay']='authored'
        (folder/'manifest.json').write_text(json.dumps(manifest))
        target=self.root/'authored.vscinematic'
        pipeline.build_bundle(folder,target)
        with zipfile.ZipFile(target) as archive:
            self.assertEqual(json.loads(archive.read('manifest.json'))['scenes'][0]['cameraReplay'],'authored')
        for bad in ('autored', True, None, 3):
            scene['cameraReplay']=bad
            (folder/'manifest.json').write_text(json.dumps(manifest))
            rejected=self.root/'invalid.vscinematic'
            with self.assertRaisesRegex(ValueError,'Camera replay'):
                pipeline.build_bundle(folder,rejected)
            self.assertFalse(rejected.exists())

    def test_cloud_conditioning_sidecars_never_bloat_phone_archive(self):
        folder,_=self.generation()
        sidecar=folder/'conditioning';sidecar.mkdir()
        (sidecar/'manifest.json').write_text('{"cloudOnly":true}')
        (sidecar/'depth.exr').write_bytes(b'private cloud guide')
        target=self.root/'phone.vscinematic';pipeline.build_bundle(folder,target)
        with zipfile.ZipFile(target) as archive:
            self.assertTrue(all(not name.startswith('conditioning/') for name in archive.namelist()))

    def test_invalid_conditioning_and_pack_inputs_fail_before_child_execution(self):
        folder,_=self.generation()
        for value in (-1,1201,True):
            with self.subTest(value=value),patch.object(pipeline.subprocess,'run') as child:
                with self.assertRaises(ValueError):
                    pipeline.build(folder/'timeline.json',self.root/'rejected',conditioning_frames=value)
                child.assert_not_called()
        with patch.object(pipeline.subprocess,'run') as child:
            with self.assertRaises(ValueError):
                pipeline.build(folder/'timeline.json',self.root/'rejected',library_only=True,selected_actions=['WAVE'])
            child.assert_not_called()

    def test_change_between_verification_and_archive_copy_cannot_publish(self):
        folder,_=self.generation();target=self.root/'output.vscinematic';target.write_bytes(b'accepted generation')
        original=pipeline.verify_generation
        def race(directory):
            verified=original(directory)
            (directory/'scene.glb').write_bytes(b'changed after admission')
            return verified
        with patch.object(pipeline,'verify_generation',side_effect=race):
            with self.assertRaises(ValueError):pipeline.build_bundle(folder,target)
        self.assertEqual(target.read_bytes(),b'accepted generation')

    def test_changed_artifact_rejects_without_overwriting_previous_archive(self):
        folder,_=self.generation();target=self.root/'output.vscinematic';target.write_bytes(b'accepted generation')
        (folder/'scene.glb').write_bytes(b'changed')
        with self.assertRaises(ValueError):pipeline.build_bundle(folder,target)
        self.assertEqual(target.read_bytes(),b'accepted generation')

    def test_traversal_duplicate_external_glb_and_symlink_never_enter_bundle(self):
        for bad in ('traversal','duplicate','external','symlink'):
            with self.subTest(bad=bad):
                folder,manifest=self.generation()
                if bad=='traversal':manifest['files'][0]['path']='../timeline.json'
                elif bad=='duplicate':manifest['files'].append(dict(manifest['files'][0]))
                elif bad=='external':
                    raw=json.dumps({'asset':{'version':'2.0'},'buffers':[{'uri':'https://private.invalid/file.bin'}]}).encode();raw+=b' '*((-len(raw))%4)
                    data=struct.pack('<III',0x46546c67,2,20+len(raw))+struct.pack('<II',len(raw),0x4e4f534a)+raw
                    (folder/'scene.glb').write_bytes(data);manifest['files'][1].update(bytes=len(data),sha256=hashlib.sha256(data).hexdigest())
                else:
                    source=folder/'scene.glb';source.rename(self.root/'external.glb');source.symlink_to(self.root/'external.glb')
                (folder/'manifest.json').write_text(json.dumps(manifest))
                with self.assertRaises(ValueError):pipeline.build_bundle(folder,self.root/'output.vscinematic')
                for entry in folder.iterdir():entry.unlink()
                folder.rmdir()

    def test_real_encoder_preserves_sixty_fps_frame_count_and_duration(self):
        frames=self.root/'frames';frames.mkdir()
        for index in range(60):png(frames/f'frame_{index:06d}.png',(index*4%255,60,180))
        output=self.root/'render.mp4'
        result=pipeline.encode_video(frames,output,fps=60,frame_count=60,width=32,height=32)
        self.assertEqual(result['frameCount'],60);self.assertEqual(result['fps'],60)
        self.assertAlmostEqual(result['durationSeconds'],1,places=5)
        self.assertEqual(result['execution'],'cloud_blender_postprocess')
        with self.assertRaises(ValueError):pipeline.validate_video(output,frame_count=59,fps=60,width=32,height=32)

    def test_incomplete_frame_sequence_rejects_before_encoder_publication(self):
        frames=self.root/'frames';frames.mkdir();png(frames/'frame_000000.png',(255,0,0))
        output=self.root/'render.mp4';output.write_bytes(b'accepted')
        with self.assertRaises(ValueError):pipeline.encode_video(frames,output,fps=30,frame_count=2,width=32,height=32)
        self.assertEqual(output.read_bytes(),b'accepted')
