"""Composite the What's New 'networks' and 'studios' tiles from real brand assets."""
import math, random
from PIL import Image, ImageDraw, ImageFilter, ImageChops

W, H = 1280, 720
OUT = '/app/frontend/public/whatsnew'
NET = '/app/frontend/public/networks'
LOGOS = '/tmp/logos'


def backdrop(glow_rgb, glow_at=(0.5, 0.42)):
    bg = Image.new('RGB', (W, H), (7, 10, 20))
    glow = Image.new('RGB', (W, H), (0, 0, 0))
    d = ImageDraw.Draw(glow)
    cx, cy = int(W * glow_at[0]), int(H * glow_at[1])
    d.ellipse([cx - 520, cy - 300, cx + 520, cy + 300], fill=glow_rgb)
    glow = glow.filter(ImageFilter.GaussianBlur(170))
    bg = ImageChops.add(bg, glow)
    # faint light streaks
    streaks = Image.new('RGBA', (W, H), (0, 0, 0, 0))
    sd = ImageDraw.Draw(streaks)
    for x0 in (-200, 350, 900):
        sd.polygon([(x0, H), (x0 + 60, H), (x0 + 520, -80), (x0 + 380, -80)], fill=(255, 255, 255, 14))
    streaks = streaks.filter(ImageFilter.GaussianBlur(28))
    bg = Image.alpha_composite(bg.convert('RGBA'), streaks)
    # grain
    random.seed(7)
    noise = Image.effect_noise((W, H), 18).convert('L')
    grain = Image.merge('RGBA', (noise, noise, noise, Image.new('L', (W, H), 22)))
    bg = Image.alpha_composite(bg, grain)
    # vignette
    vig = Image.new('L', (W, H), 0)
    ImageDraw.Draw(vig).ellipse([-200, -160, W + 200, H + 160], fill=255)
    vig = vig.filter(ImageFilter.GaussianBlur(220))
    dark = Image.new('RGBA', (W, H), (0, 0, 0, 255))
    dark.putalpha(ImageChops.invert(vig).point(lambda v: int(v * 0.75)))
    return Image.alpha_composite(bg, dark)


def shadow_of(img, blur=22, alpha=170, offset=(0, 18)):
    sh = Image.new('RGBA', (img.width + blur * 4, img.height + blur * 4), (0, 0, 0, 0))
    a = img.split()[3].point(lambda v: int(v * alpha / 255))
    sh.paste((0, 0, 0, 255), (blur * 2 + offset[0], blur * 2 + offset[1]), a)
    return sh.filter(ImageFilter.GaussianBlur(blur)), blur * 2


def paste_with_shadow(canvas, img, xy, **kw):
    sh, pad = shadow_of(img, **kw)
    canvas.alpha_composite(sh, (xy[0] - pad, xy[1] - pad))
    canvas.alpha_composite(img, xy)


def rounded(img, r):
    m = Image.new('L', img.size, 0)
    ImageDraw.Draw(m).rounded_rectangle([0, 0, img.width - 1, img.height - 1], r, fill=255)
    out = img.convert('RGBA')
    out.putalpha(ImageChops.multiply(out.split()[3], m))
    return out


def networks():
    canvas = backdrop((70, 20, 90))
    slugs = ['hayu', '7plus', '9now', '10play', 'britbox']
    cw, ch = 290, 163
    angles = [-7, -3.5, 0, 3.5, 7]
    lift = [26, 9, 0, 9, 26]
    cards = []
    for i, s in enumerate(slugs):
        c = Image.open(f'{NET}/{s}.webp').convert('RGBA').resize((cw, ch), Image.LANCZOS)
        c = rounded(c, 20)
        border = Image.new('RGBA', c.size, (0, 0, 0, 0))
        ImageDraw.Draw(border).rounded_rectangle([0, 0, cw - 1, ch - 1], 20, outline=(255, 255, 255, 70), width=3)
        c.alpha_composite(border)
        c = c.rotate(angles[i], resample=Image.BICUBIC, expand=True)
        cards.append(c)
    step = 242
    total = step * 4 + cards[-1].width
    x0 = (W - total) // 2
    order = [0, 4, 1, 3, 2]  # outer cards first so the centre card sits on top
    pos = {}
    for i in range(5):
        x = x0 + i * step
        y = (H - ch) // 2 + lift[i] - (cards[i].height - ch) // 2
        pos[i] = (x, y)
    for i in order:
        paste_with_shadow(canvas, cards[i], pos[i], blur=26, alpha=200, offset=(0, 26))
    return canvas


def whiten(img, keep_hue=False):
    """Make dark ink white while keeping alpha (for black/dark-blue wordmarks)."""
    img = img.convert('RGBA')
    r, g, b, a = img.split()
    if keep_hue:
        return img
    white = Image.new('RGBA', img.size, (255, 255, 255, 255))
    white.putalpha(a)
    return white


def marvel_fix(img):
    """Marvel Studios: keep the red box, turn the black 'STUDIOS' text white."""
    img = img.convert('RGBA')
    px = img.load()
    for y in range(img.height):
        for x in range(img.width):
            r, g, b, a = px[x, y]
            if a > 0 and r < 90 and g < 90 and b < 90:
                px[x, y] = (255, 255, 255, a)
    return img


def fit(img, box):
    img = img.copy()
    img.thumbnail(box, Image.LANCZOS)
    return img


def studios():
    canvas = backdrop((20, 40, 95), glow_at=(0.5, 0.45))
    items = [
        ('hUzeosd33nzE5MCNsZxCGEKTXaQ', marvel_fix),
        ('4Y00XuSMuP1gimd0jP6JT57QbCI', lambda i: i.convert('RGBA')),
        ('wdrCwmRnLFJhEoH8GSfymY85KHT', whiten),
        ('1TjvGVDMYsj6JBxOAkUHpPEwLf7', whiten),
        ('3BPX5VGBov8SDqTV7wC1L1xShAS', whiten),
        ('zhD3hhtKB5qyv7ZeL4uLpNxgMVU', lambda i: i.convert('RGBA')),
    ]
    cols, rows = 3, 2
    cell_w, cell_h = 320, 190
    gap_x, gap_y = 40, 34
    grid_w = cols * cell_w + (cols - 1) * gap_x
    grid_h = rows * cell_h + (rows - 1) * gap_y
    gx = (W - grid_w) // 2
    gy = (H - grid_h) // 2 - 60
    for idx, (f, treat) in enumerate(items):
        logo = treat(Image.open(f'{LOGOS}/{f}.png'))
        logo = fit(logo, (cell_w - 40, cell_h - 50))
        # soft glass plate behind each logo
        plate = Image.new('RGBA', (cell_w, cell_h), (0, 0, 0, 0))
        ImageDraw.Draw(plate).rounded_rectangle([0, 0, cell_w - 1, cell_h - 1], 28, fill=(255, 255, 255, 16), outline=(255, 255, 255, 46), width=2)
        x = gx + (idx % cols) * (cell_w + gap_x)
        y = gy + (idx // cols) * (cell_h + gap_y)
        paste_with_shadow(canvas, plate, (x, y), blur=18, alpha=120, offset=(0, 14))
        glow = Image.new('RGBA', (logo.width + 80, logo.height + 80), (0, 0, 0, 0))
        glow.paste((255, 255, 255, 90), (40, 40), logo.split()[3])
        glow = glow.filter(ImageFilter.GaussianBlur(18))
        lx = x + (cell_w - logo.width) // 2
        ly = y + (cell_h - logo.height) // 2
        canvas.alpha_composite(glow, (lx - 40, ly - 40))
        canvas.alpha_composite(logo, (lx, ly))
    return canvas


if __name__ == '__main__':
    networks().convert('RGB').save(f'{OUT}/networks.webp', quality=88, method=6)
    studios().convert('RGB').save(f'{OUT}/studios.webp', quality=88, method=6)
    print('ok')
