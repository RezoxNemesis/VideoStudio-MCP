# The Blender forge of VideoStudio: Blender runs without a window, builds one 3D asset and saves it as a .glb file
# that the 3D Stage can stand beside its characters.
#   blender --background --factory-startup --python run.py -- <job.json>
# job: { "out": "/…/name.glb", "recipe": "tree", "params": {…} }  |  { "out", "script": "/…/code.py", "params" }
#      |  { "out", "convert": "/…/model.fbx" }
# The last line printed is  FORGE_RESULT {json}  (objects, triangles, size in metres) or FORGE_ERROR text.
import bpy, sys, json, os, math, traceback
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import forge_lib as F

def main():
    job = json.load(open(sys.argv[sys.argv.index('--') + 1], encoding='utf-8'))
    F.clear()
    P = job.get('params') or {}
    if job.get('convert'):
        F.import_any(job['convert'])
    elif job.get('script'):
        code = open(job['script'], encoding='utf-8').read()
        env = {'bpy': bpy, 'F': F, 'P': P, 'math': math, '__name__': '__forge__'}
        exec(compile(code, job['script'], 'exec'), env)
    else:
        import recipes
        fn = recipes.RECIPES.get(job.get('recipe'))
        if not fn: raise ValueError('No recipe "%s". Recipes: %s' % (job.get('recipe'), ', '.join(sorted(recipes.RECIPES))))
        fn(P)
    if job.get('ground', True): F.to_ground()
    print('FORGE_RESULT ' + json.dumps(F.export(job['out'])))

try:
    main()
except Exception as e:
    traceback.print_exc()
    print('FORGE_ERROR ' + str(e).replace('\n', ' ')[:600])
    sys.exit(3)
