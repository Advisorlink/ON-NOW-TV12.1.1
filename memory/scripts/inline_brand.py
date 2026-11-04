import base64, io
from PIL import Image

def to_data_url(path, max_w):
    im = Image.open(path).convert('RGBA')
    if im.width > max_w:
        im = im.resize((max_w, round(im.height * max_w / im.width)), Image.LANCZOS)
    im = im.quantize(colors=256, method=Image.Quantize.FASTOCTREE, dither=Image.Dither.FLOYDSTEINBERG).convert('RGBA')
    buf = io.BytesIO()
    im.save(buf, 'PNG', optimize=True)
    data = buf.getvalue()
    print(path, im.size, len(data), 'bytes')
    return 'data:image/png;base64,' + base64.b64encode(data).decode()

word = to_data_url('/app/frontend/src/assets/brand/reborn-play.png', 720)
mark = to_data_url('/app/frontend/src/assets/brand/reborn-mark.png', 192)
open('/app/frontend/src/assets/brand/rebornAssets.js', 'w').write(
    "/* Inlined so the logos resolve inside the APK WebView (file:///android_asset base). */\n"
    f"export const REBORN_WORDMARK_DATA = '{word}';\n"
    f"export const REBORN_MARK_DATA = '{mark}';\n"
)
p = '/app/frontend/src/lib/brand.js'; s = open(p).read()
s = s.replace("import rebornWordmark from '@/assets/brand/reborn-play.png';\nimport rebornMark from '@/assets/brand/reborn-mark.png';",
              "import { REBORN_WORDMARK_DATA as rebornWordmark, REBORN_MARK_DATA as rebornMark } from '@/assets/brand/rebornAssets';")
open(p, 'w').write(s)
print('ok')
