"""SDK-backed Kotlin typecheck fallback for ARM hosts lacking modern aapt2.

Uses Gradle's resolved debug compile classpath + real Android API34. Only R
IDs are generated compile-time placeholders from actual resource symbol lists;
this does NOT validate resource linking/package/signing or device behaviour.
Run printVerificationClasspath with /tmp/livetv-classpath.init.gradle first.
"""
import collections
import pathlib
import subprocess
import xml.etree.ElementTree as ET
import zipfile

app = pathlib.Path('/app/android/onnowtv-livetv/app')
out = pathlib.Path('/tmp/livetv-real-typecheck')
out.mkdir(exist_ok=True)
cp = [pathlib.Path('/tmp/livetv-android-sdk/platforms/android-34/android.jar')]
resources = out / 'resource-stubs'
resources.mkdir(exist_ok=True)

def write_r(package, values):
    target = resources / package.replace('.', '/') / 'R.java'
    target.parent.mkdir(parents=True, exist_ok=True)
    text = [f'package {package};', 'public final class R {']
    for kind, entries in values.items():
        text.append(f'public static final class {kind} {{')
        text.extend(entries)
        text.append('}')
    text.append('}')
    target.write_text('\n'.join(text))

for line in pathlib.Path('/tmp/livetv-classpath-resolved.log').read_text().splitlines():
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
                    archive.extract(name, dest)
                    cp.append(dest / name)
            if 'R.txt' in archive.namelist():
                manifest = ET.fromstring(archive.read('AndroidManifest.xml'))
                package = manifest.attrib.get('package')
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
write_r('tv.onnowtv.livetv', values)
resource_classes = out / 'resources-classes'
build_config = list((app / 'build/generated').rglob('BuildConfig.java'))
subprocess.run(['javac', '-d', str(resource_classes), *map(str, resources.rglob('*.java')), *map(str, build_config)], check=True)
cp.append(resource_classes)
gradle = next(pathlib.Path('/root/.gradle/wrapper/dists/gradle-8.7-bin').glob('*/gradle-8.7'))
sources = list((app / 'src/main/java').rglob('*.kt'))
print(f'Typechecking {len(sources)} Kotlin sources, {len(cp)} real dependencies. Resource ID placeholders only.', flush=True)
subprocess.run([
    'java', '-Xmx2048m', '-cp', str(gradle / 'lib/*'),
    'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler', '-jvm-target', '17',
    '-no-stdlib', '-no-reflect', '-classpath', ':'.join(map(str, cp)),
    '-opt-in=androidx.media3.common.util.UnstableApi', '-d', str(out / 'classes'),
    *map(str, sources),
], check=True)
print('PASS SDK-backed Kotlin typecheck (not an APK build)', flush=True)