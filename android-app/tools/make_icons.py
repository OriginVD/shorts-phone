"""앱 아이콘과 시작 화면 그림을 만들어요. (python3 tools/make_icons.py)"""
import glob
from PIL import Image, ImageDraw

RES = 'android/app/src/main/res'
BG = (14, 14, 19)        # #0E0E13
PINK = (255, 61, 127)    # #FF3D7F
CYAN = (37, 244, 238)    # #25F4EE
SS = 4                   # 부드럽게 그리려고 크게 그린 뒤 줄여요


def mark(size, scale=1.0):
    """투명 배경 위에 '세로 영상 + 재생 + 반복' 모양"""
    S = size * SS
    im = Image.new('RGBA', (S, S), (0, 0, 0, 0))
    d = ImageDraw.Draw(im)
    c = S / 2
    w, h = S * 0.36 * scale, S * 0.58 * scale
    box = [c - w / 2, c - h / 2, c + w / 2, c + h / 2]
    off = S * 0.025 * scale
    d.rounded_rectangle([box[0] - off, box[1] + off, box[2] - off, box[3] + off], radius=w * 0.18, fill=CYAN)
    d.rounded_rectangle(box, radius=w * 0.18, fill=PINK)
    t = w * 0.30
    d.polygon([(c - t * 0.8, c - t), (c - t * 0.8, c + t), (c + t * 1.0, c)], fill='white')
    return im.resize((size, size), Image.LANCZOS)


def full_icon(size, round_=False):
    S = size * SS
    bg = Image.new('RGBA', (S, S), (0, 0, 0, 0))
    d = ImageDraw.Draw(bg)
    if round_:
        d.ellipse([0, 0, S - 1, S - 1], fill=BG)
    else:
        d.rounded_rectangle([0, 0, S - 1, S - 1], radius=S * 0.22, fill=BG)
    bg = bg.resize((size, size), Image.LANCZOS)
    bg.alpha_composite(mark(size, 1.25))
    return bg


for dens, px in {'mdpi': 48, 'hdpi': 72, 'xhdpi': 96, 'xxhdpi': 144, 'xxxhdpi': 192}.items():
    full_icon(px).save(f'{RES}/mipmap-{dens}/ic_launcher.png')
    full_icon(px, True).save(f'{RES}/mipmap-{dens}/ic_launcher_round.png')
    fg = px * 108 // 48   # 적응형 아이콘 앞면은 108dp, 가운데 72dp만 보여요
    mark(fg, 0.95).save(f'{RES}/mipmap-{dens}/ic_launcher_foreground.png')

for f in glob.glob(f'{RES}/drawable*/splash.png'):
    w, h = Image.open(f).size
    im = Image.new('RGBA', (w, h), BG + (255,))
    m = mark(int(min(w, h) * 0.45), 1.2)
    im.alpha_composite(m, ((w - m.width) // 2, (h - m.height) // 2))
    im.convert('RGB').save(f)
print('done')
