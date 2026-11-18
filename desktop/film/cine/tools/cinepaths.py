"""Where things are. Set by the app (cine.tool) or by hand:
CINE_ASSETS  the shared assets folder (data/ bodies, tex/ painted textures, fonts/, bvh/ captured clips, src/ sources)
CINE_FILM    the film's own folder (data/mo performances, data/marks.json, audio.wav)"""
import os
ASSETS = os.environ.get('CINE_ASSETS') or os.path.expanduser('~/Videos/VideoStudio/Cine/assets')
FILM = os.environ.get('CINE_FILM') or os.getcwd()
CHARS = os.path.join(ASSETS, 'data', ''); TEX = os.path.join(ASSETS, 'tex', ''); BVH = os.path.join(ASSETS, 'bvh', '')
MPFB = os.path.join(os.environ.get('CINE_MPFB') or os.path.join(ASSETS, 'src', 'mpfb2'), 'src', 'mpfb', 'data', '')
FILMDATA = os.path.join(FILM, 'data', '')
for d in (CHARS, TEX, BVH, os.path.join(FILMDATA, 'mo')): os.makedirs(d, exist_ok=True)
