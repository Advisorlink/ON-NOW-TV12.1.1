rm -rf android/vesper-tv/app/src/main/assets/web
mkdir -p android/vesper-tv/app/src/main/assets/web
cp -r ${WEB_BUILD:-/tmp/v2web}/. android/vesper-tv/app/src/main/assets/web/

python3 - <<'PY'
import re, pathlib
p = pathlib.Path('android/vesper-tv/app/src/main/assets/web/index.html')
html = p.read_text(encoding='utf-8')
html = re.sub(
    r'<script[^>]*assets\.emergent\.sh[^>]*></script>\s*',
    '', html, flags=re.IGNORECASE)
html = re.sub(
    r'<a\s+id="emergent-badge"[\s\S]*?</a>\s*',
    '', html, flags=re.IGNORECASE)
html = re.sub(
    r'<script>\s*!\(function\s*\([^)]*\)\s*\{[\s\S]*?posthog\.init\([\s\S]*?\}\);?\s*</script>\s*',
    '', html, flags=re.IGNORECASE)
p.write_text(html, encoding='utf-8')
print('=== index.html cleaned of Emergent / PostHog tracking ===')
PY

python3 - <<'PY'
import re, pathlib
p = pathlib.Path('android/vesper-tv/app/src/main/assets/web/index.html')
html = p.read_text(encoding='utf-8')
before = html.count('="/')
html = re.sub(
    r'(\b(?:src|href|content)=)"/(?!/)([^"\s]*)"',
    r'\1"./\2"',
    html,
)
after = html.count('="/')
p.write_text(html, encoding='utf-8')
print(f'=== index.html absolute->relative paths: {before} → {after} ===')
PY

python3 - <<'PY'
import pathlib, re
js_dir = pathlib.Path('android/vesper-tv/app/src/main/assets/web/static/js')
if not js_dir.exists():
    print('!! no static/js dir, skipping wrapper')
else:
    for f in sorted(js_dir.glob('*.js')):
        if f.name.endswith('.LICENSE.txt'):
            continue
        body = f.read_text(encoding='utf-8')
        if body.startswith('/*WRAP*/'):
            continue
        wrapped = (
            "/*WRAP*/try{\n" + body + "\n}catch(e){\n"
            "try{\n"
            "  window.__bundleError = e;\n"
            "  var stack = (e && (e.stack || e.message || String(e))) || 'unknown error';\n"
            "  var t = document.getElementById('vesper-boot-title');\n"
            "  var s = document.getElementById('vesper-boot-sub');\n"
            "  var sp = document.getElementById('vesper-boot-spinner');\n"
            "  if (t) t.innerHTML = \"ON NOW TV couldn't start\";\n"
            "  if (s) s.innerHTML = "
            "    \"<span style='font-size:12px;color:#A8B5C7;'>Real error from the JS bundle:</span><br/><br/>\""
            "    + \"<pre style='text-align:left;font-size:10px;color:#FCA5A5;background:rgba(255,255,255,0.04);padding:12px;border-radius:8px;max-height:340px;overflow:auto;white-space:pre-wrap;word-break:break-all;'>\""
            "    + (stack + '').replace(/</g,'&lt;')"
            "    + \"</pre>\";\n"
            "  if (sp) sp.style.display = 'none';\n"
            "}catch(_){}\n"
            "throw e;\n"
            "}\n"
        )
        f.write_text(wrapped, encoding='utf-8')
        print('wrapped:', f.name, 'size:', f.stat().st_size)
PY

echo "=== Bundled into APK assets: ==="
