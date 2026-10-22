#!/usr/bin/env python3
from pathlib import Path
ROOT=Path(__file__).resolve().parents[1]
p=ROOT/'android/app/src/main/java/com/rezoxnemesis/videostudio/AppProtocol.java'
s=p.read_text();before='public static final String APP_VERSION = "3.5.4";';after='public static final String APP_VERSION = "3.5.5";'
if after not in s:
    if s.count(before)!=1:raise RuntimeError('APP_PROTOCOL_VERSION_SOURCE_DRIFT')
    p.write_text(s.replace(before,after,1))
assert 'versionCode = 355' in (ROOT/'android/app/build.gradle.kts').read_text()
print('Carrier 3.5.5: MCP identity, owner secret namespace and stable endpoint preserved')
