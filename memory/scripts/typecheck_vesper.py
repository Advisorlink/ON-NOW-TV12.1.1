"""SDK-backed Kotlin typecheck for the Vesper (android/vesper-tv) module on hosts without aapt2.

Prereqs (see memory/CHANGELOG 2026-10-07):
  1. /tmp/vsdk/platforms/android-34/android.jar  (platform-34 zip from dl.google.com)
  2. /tmp/vsdk/compose-compiler-1.5.13.jar       (maven.google.com androidx.compose.compiler)
  3. ./gradlew --offline -I /tmp/vesper-classpath.init.gradle -Psdk.dir=/tmp/vsdk :app:printVerificationClasspath
     > /tmp/vesper-classpath-resolved.log
Only R IDs are generated placeholders; this validates Kotlin types, NOT packaging/device behaviour.
"""
import collections, pathlib, subprocess, sys, xml.etree.ElementTree as ET, zipfile

app = pathlib.Path('/app/android/vesper-tv/app')
out = pathlib.Path('/tmp/vesper-real-typecheck'); out.mkdir(exist_ok=True)
cp = [pathlib.Path('/tmp/vsdk/platforms/android-34/android.jar')]
resources = out / 'resource-stubs'; resources.mkdir(exist_ok=True)

def write_r(package, values):
    target = resources / package.replace('.', '/') / 'R.java'
    target.parent.mkdir(parents=True, exist_ok=True)
    text = [f'package {package};', 'public final class R {']
    for kind, entries in values.items():
        text += [f'public static final class {kind} {{', *entries, '}']
    text.append('}')
    target.write_text('\n'.join(text))

for line in pathlib.Path('/tmp/vesper-classpath-resolved.log').read_text().splitlines():
    if not line.startswith('VERIFY_CP:'):
        continue
    path = pathlib.Path(line.split(':', 1)[1])
    if path.suffix == '.jar':
        cp.append(path)
    elif path.suffix == '.aar':
        with zipfile.ZipFile(path) as archive:
            dest = out / path.stem
            for name in archive.namelist():
                if name == 'classes.jar' or (name.startswith('libs/') and name.endswith('.jar')):
                    archive.extract(name, dest); cp.append(dest / name)
            if 'R.txt' in archive.namelist():
                package = ET.fromstring(archive.read('AndroidManifest.xml')).attrib.get('package')
                values = collections.defaultdict(list)
                for row in archive.read('R.txt').decode().splitlines():
                    typ, kind, name, value = row.split(' ', 3)
                    values[kind].append(f'public static final {typ} {name} = {value};')
                if package:
                    write_r(package, values)

values = collections.defaultdict(list)
symbols = app / 'build/intermediates/local_only_symbol_list/debug/parseDebugLocalResources/R-def.txt'
for i, row in enumerate(symbols.read_text().splitlines()[2:], 1):
    kind, name, *_ = row.split()
    values[kind].append(f'public static final int {name.replace(".", "_")} = {i};')
write_r('tv.vesper.app', values)
resource_classes = out / 'resources-classes'
build_config = list((app / 'build/generated').rglob('BuildConfig.java'))
subprocess.run(['javac', '-d', str(resource_classes), *map(str, resources.rglob('*.java')), *map(str, build_config)], check=True)
cp.append(resource_classes)

m2 = pathlib.Path('/root/.gradle/caches/modules-2/files-2.1')
def jar(group, name, version):
    return next((m2 / group / name / version).rglob(f'{name}-{version}.jar'))
compiler_cp = [
    jar('org.jetbrains.kotlin', 'kotlin-compiler-embeddable', '1.9.23'),
    jar('org.jetbrains.kotlin', 'kotlin-daemon-embeddable', '1.9.23'),
    jar('org.jetbrains.kotlin', 'kotlin-stdlib', '1.9.24'),
    jar('org.jetbrains.kotlin', 'kotlin-reflect', '1.9.22'),
    jar('org.jetbrains.intellij.deps', 'trove4j', '1.0.20200330'),
    jar('org.jetbrains.kotlinx', 'kotlinx-coroutines-core-jvm', '1.7.3'),
    jar('org.jetbrains', 'annotations', '23.0.0'),
]
sources = list((app / 'src/main/java').rglob('*.kt'))
print(f'Typechecking {len(sources)} Kotlin sources, {len(cp)} real dependencies.', flush=True)
r = subprocess.run([
    'java', '-Xmx2500m', '-cp', ':'.join(map(str, compiler_cp)),
    'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler', '-jvm-target', '17',
    '-no-stdlib', '-no-reflect', '-classpath', ':'.join(map(str, cp)),
    '-Xplugin=/tmp/vsdk/compose-compiler-1.5.13.jar',
    '-opt-in=androidx.media3.common.util.UnstableApi', '-d', str(out / 'classes'),
    *map(str, sources),
])
print('PASS SDK-backed Kotlin typecheck (not an APK build)' if r.returncode == 0 else f'FAIL exit {r.returncode}', flush=True)
sys.exit(r.returncode)
