"""SDK-backed Kotlin typecheck for Vesper native sources (no APK packaging).

Uses Gradle-resolved debug classpath + Android API stub jar. Generates lightweight
R / BuildConfig placeholders for app-local symbols only. This validates Kotlin
syntax/types for selected source files without running AAPT2/resource linking.
"""

import collections
import pathlib
import re
import subprocess
import xml.etree.ElementTree as ET
import zipfile

ROOT = pathlib.Path('/app/android/vesper-tv')
APP = ROOT / 'app'
OUT = pathlib.Path('/tmp/vesper-typecheck')
OUT.mkdir(exist_ok=True)

ANDROID_JAR = pathlib.Path('/tmp/vesper-sdk/platforms/android-34/android.jar')
CP_LOG = pathlib.Path('/tmp/vesper-classpath-resolved.log')

if not ANDROID_JAR.exists():
    raise SystemExit(f'Missing android jar: {ANDROID_JAR}')
if not CP_LOG.exists():
    raise SystemExit(f'Missing classpath log: {CP_LOG}')

cp = [ANDROID_JAR]
resources = OUT / 'resource-stubs'
resources.mkdir(exist_ok=True)


def write_r(package: str, values: dict[str, list[str]]):
    target = resources / package.replace('.', '/') / 'R.java'
    target.parent.mkdir(parents=True, exist_ok=True)
    lines = [f'package {package};', 'public final class R {']
    for kind, entries in values.items():
        lines.append(f'public static final class {kind} {{')
        lines.extend(entries)
        lines.append('}')
    lines.append('}')
    target.write_text('\n'.join(lines), encoding='utf-8')


for line in CP_LOG.read_text(encoding='utf-8').splitlines():
    if not line.startswith('VERIFY_CP:'):
        continue
    path = pathlib.Path(line.split(':', 1)[1])
    if path.suffix == '.jar':
        cp.append(path)
    elif path.suffix == '.aar':
        with zipfile.ZipFile(path) as archive:
            dest = OUT / path.stem
            for name in archive.namelist():
                if name == 'classes.jar' or (name.startswith('libs/') and name.endswith('.jar')):
                    archive.extract(name, dest)
                    cp.append(dest / name)
            if 'R.txt' in archive.namelist() and 'AndroidManifest.xml' in archive.namelist():
                try:
                    manifest = ET.fromstring(archive.read('AndroidManifest.xml'))
                    package = manifest.attrib.get('package')
                    values = collections.defaultdict(list)
                    for row in archive.read('R.txt').decode(errors='ignore').splitlines():
                        parts = row.split(' ', 3)
                        if len(parts) != 4:
                            continue
                        typ, kind, name, value = parts
                        values[kind].append(f'public static final {typ} {name} = {value};')
                    if package and values:
                        write_r(package, values)
                except Exception:
                    pass


sources = sorted((APP / 'src/main/java').rglob('*.kt'))
r_refs = collections.defaultdict(set)
build_fields = set()

r_re = re.compile(r'\bR\.([A-Za-z0-9_]+)\.([A-Za-z0-9_]+)\b')
bc_re = re.compile(r'\bBuildConfig\.([A-Za-z0-9_]+)\b')

for src in sources:
    text = src.read_text(encoding='utf-8', errors='ignore')
    for kind, name in r_re.findall(text):
        r_refs[kind].add(name)
    for fld in bc_re.findall(text):
        build_fields.add(fld)

local_r_values = collections.defaultdict(list)
counter = 1
for kind in sorted(r_refs):
    for name in sorted(r_refs[kind]):
        local_r_values[kind].append(f'public static final int {name} = {counter};')
        counter += 1

write_r('tv.vesper.app', local_r_values)

build_cfg_dir = resources / 'tv/vesper/app'
build_cfg_dir.mkdir(parents=True, exist_ok=True)
build_cfg = build_cfg_dir / 'BuildConfig.java'
lines = ['package tv.vesper.app;', 'public final class BuildConfig {']
for fld in sorted(build_fields):
    if fld == 'DEBUG':
        lines.append(f'public static final boolean {fld} = false;')
    elif fld.endswith('CODE'):
        lines.append(f'public static final int {fld} = 1;')
    else:
        lines.append(f'public static final String {fld} = "";')
lines.append('}')
build_cfg.write_text('\n'.join(lines), encoding='utf-8')

resource_classes = OUT / 'resources-classes'
javac_sources = [*resources.rglob('*.java')]
subprocess.run(['javac', '-d', str(resource_classes), *map(str, javac_sources)], check=True)
cp.append(resource_classes)

gradle = next(pathlib.Path('/root/.gradle/wrapper/dists/gradle-8.7-bin').glob('*/gradle-8.7'))

targets = sources

print(f'Typechecking {len(targets)} Vesper Kotlin files with {len(cp)} dependencies.', flush=True)
subprocess.run([
    'java', '-Xmx3072m', '-cp', '/tmp/vesper-kotlin-compiler-1.9.23.jar:' + str(gradle / 'lib/*'),
    'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler', '-jvm-target', '17',
    '-Xplugin=/tmp/vesper-compose-compiler-1.5.13.jar',
    '-no-stdlib', '-no-reflect', '-classpath', ':'.join(map(str, cp)),
    '-opt-in=androidx.media3.common.util.UnstableApi',
    '-d', str(OUT / 'classes'),
    *map(str, targets),
], check=True)

print('PASS SDK-backed Vesper Kotlin typecheck (not APK packaging)', flush=True)
