#!/usr/bin/env python3
"""Compile all Java sources and run real Robolectric/JUnit with cached Gradle dependencies.

This does not assemble an APK. It reuses the prior resource archive, updating the
current editor schema; use Gradle/CI after manifest or resource changes.
"""
from pathlib import Path
import shutil,subprocess,sys,hashlib,zipfile,os,tempfile,json
repo=Path(__file__).resolve().parents[1]; app=repo/'android/app'
root=repo/'artifacts/offline-junit'; root.mkdir(parents=True,exist_ok=True)
out=Path(tempfile.mkdtemp(prefix='run-',dir=root))
print('Offline evidence workspace:',out,flush=True)
toolchains=Path(os.environ.get('VIDEOSTUDIO_TOOLCHAINS','/workspace/toolchains'))
gradle_home=Path(os.environ.get('GRADLE_USER_HOME',str(toolchains/'gradle-home')))
argfiles=sorted((gradle_home/'.tmp').glob('gradle-worker-classpath*txt'),key=lambda p:p.stat().st_mtime,reverse=True)
argfile=next((p for p in argfiles if str(app) in p.read_text()),None)
if argfile is None: sys.exit('A successful Gradle unit-test build must populate the cached classpath before offline verification.')
out.mkdir(parents=True,exist_ok=True)
cp=argfile.read_text().splitlines()[1].split(':')
# Use fresh classes before the cached Gradle runtime; exclude cached app/test code.
configpath=next(Path(p) for p in cp if '/unit_test_config_directory/' in p)
cp=[p for p in cp if '/runtime_app_classes_jar/' not in p and '/javac/debugUnitTest/' not in p and '/workerMain/' not in p and '/unit_test_config_directory/' not in p]
config=(configpath/'com/android/tools/test_config.properties').read_text()
resource=app/'build/intermediates/apk_for_local_test/debugUnitTest/packageDebugUnitTestForUnitTest/apk-for-local-test.ap_'
updated=out/'resources.ap_'
with zipfile.ZipFile(resource) as original,zipfile.ZipFile(updated,'w') as packed:
 for item in original.infolist():
  if item.filename!='assets/editor-operations.json': packed.writestr(item,original.read(item.filename))
 packed.writestr('assets/editor-operations.json',(repo/'protocol/editor-operations.json').read_bytes())
config=config.replace('android_resource_apk='+str(resource.relative_to(app)),'android_resource_apk='+str(updated))
configout=out/'config/com/android/tools';configout.mkdir(parents=True,exist_ok=True)
(configout/'test_config.properties').write_text(config)
cp.insert(0,str(out/'config'))
main=out/'main'; tests=out/'tests'
for d in (main,tests):
 if d.exists(): shutil.rmtree(d)
 d.mkdir()
java_home=Path(os.environ.get('JAVA_HOME',str(toolchains/'jdk')))
java=str(java_home/'bin/java'); javac=str(java_home/'bin/javac')
compilecp=[p for p in cp if not p.endswith('/transformed/android.jar')]
sdk=Path(os.environ.get('ANDROID_HOME',str(toolchains/'android-sdk')))
compilecp.insert(0,str(sdk/'platforms/android-36/android.jar'))
def compile_sources(folder,dest,classpath):
 sources=sorted(folder.rglob('*.java')); args=out/(dest.name+'-sources.txt'); args.write_text('\n'.join(str(p) for p in sources))
 result=subprocess.run([javac,'-encoding','UTF-8','-source','17','-target','17','-cp',':'.join(classpath),'-d',str(dest),'@'+str(args)])
 if result.returncode: sys.exit(result.returncode)
 print('Freshly compiled',len(sources),dest.name,'sources',flush=True)
compile_sources(app/'src/main/java',main,compilecp)
compile_sources(app/'src/test/java',tests,[str(main)]+compilecp)
# Record exactly which Java/schema source files were compiled for this run.
inputs=list((app/'src/main/java').rglob('*.java'))+list((app/'src/test/java').rglob('*.java'))+[repo/'protocol/editor-operations.json']
(out/'source-sha256.json').write_text(json.dumps({str(p.relative_to(repo)):hashlib.sha256(p.read_bytes()).hexdigest() for p in sorted(inputs)},indent=2)+'\n')
sdks=out/'sdk'; sdks.mkdir(exist_ok=True)
for jar in (toolchains/'test-user-home/.m2/repository/org/robolectric/android-all-instrumented').rglob('*.jar'):
 target=sdks/jar.name
 if not target.exists(): target.symlink_to(jar)
classes=sys.argv[1:] or ['com.rezoxnemesis.videostudio.'+p.stem for p in sorted((app/'src/test/java/com/rezoxnemesis/videostudio').glob('*Test.java'))]
cmd=[java,'-Xmx2048m','--add-opens=java.base/java.io=ALL-UNNAMED','-Duser.home='+str(toolchains/'test-user-home'),'-Drobolectric.offline=true','-Drobolectric.dependency.dir='+str(sdks),'-cp',':'.join([str(tests),str(main)]+cp),'org.junit.runner.JUnitCore']+classes
print('Running',len(classes),'JUnit test classes offline',flush=True)
r=subprocess.run(cmd,cwd=app)
sys.exit(r.returncode)
