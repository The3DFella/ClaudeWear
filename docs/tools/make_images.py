"""Frames the raw watch screenshots (docs/images/raw) for the README.

Outputs (in docs/images/):
  home.png archive.png chat.png reply.png sending.png approval.png pair.png  framed watches, transparent
  hero.png          1600x800 banner
  reply-flow.gif    home -> chat -> reply -> sending -> answer, looping

Usage:  python docs/tools/make_images.py      (needs Pillow: pip install pillow)
"""
from __future__ import annotations

import math
import os
import sys
from pathlib import Path

from PIL import Image, ImageChops, ImageDraw, ImageFilter, ImageFont

ROOT = Path(__file__).resolve().parents[2]
RAW = ROOT / "docs" / "images" / "raw"
OUT = ROOT / "docs" / "images"

SS = 3  # supersampling factor for smooth edges

# Framed watch geometry at final scale (px).
CANVAS = (600, 600)
CENTER = (286, 288)
SCREEN_R = 214      # visible display radius
BEZEL_R = 226       # black glass border around the display
CASE_R = 262        # outer edge of the metal case
ACCENT = (43, 179, 163)  # teal (#2BB3A3)


# --------------------------------------------------------------------------- helpers

def font(size: int, weight: str = "regular") -> ImageFont.FreeTypeFont:
    windir = Path(os.environ.get("WINDIR", "C:/Windows")) / "Fonts"
    names = {
        "regular": ["segoeui.ttf", "DejaVuSans.ttf", "Arial.ttf"],
        "semibold": ["seguisb.ttf", "segoeui.ttf", "DejaVuSans-Bold.ttf", "Arial Bold.ttf"],
        "bold": ["segoeuib.ttf", "DejaVuSans-Bold.ttf", "Arial Bold.ttf"],
    }[weight]
    dirs = [windir, Path("/usr/share/fonts/truetype/dejavu"), Path("/Library/Fonts"), Path("/System/Library/Fonts/Supplemental")]
    for n in names:
        for d in dirs:
            p = d / n
            if p.exists():
                return ImageFont.truetype(str(p), size)
    return ImageFont.load_default(size)


def linear_gradient(size, c0, c1, angle_deg=135.0) -> Image.Image:
    """RGBA gradient from c0 to c1 along angle (0 = left->right, 90 = top->bottom)."""
    w, h = size
    small = 256
    a = math.radians(angle_deg)
    dx, dy = math.cos(a), math.sin(a)
    g = Image.new("RGBA", (small, small))
    px = g.load()
    for y in range(small):
        for x in range(small):
            t = ((x / (small - 1) - 0.5) * dx + (y / (small - 1) - 0.5) * dy) / (abs(dx) * 0.5 + abs(dy) * 0.5) * 0.5 + 0.5
            t = min(1.0, max(0.0, t))
            px[x, y] = tuple(round(c0[i] + (c1[i] - c0[i]) * t) for i in range(4))
    return g.resize((w, h), Image.BICUBIC)


def radial_mask(size, r_inner, r_outer, center=None) -> Image.Image:
    """L mask: 255 inside r_inner, fading to 0 at r_outer."""
    w, h = size
    cx, cy = center or (w / 2, h / 2)
    small = 256
    sx, sy = w / small, h / small
    m = Image.new("L", (small, small))
    px = m.load()
    for y in range(small):
        for x in range(small):
            d = math.hypot((x + 0.5) * sx - cx, (y + 0.5) * sy - cy)
            t = (d - r_inner) / max(1e-6, r_outer - r_inner)
            t = min(1.0, max(0.0, t))
            t = t * t * (3 - 2 * t)
            px[x, y] = round(255 * (1 - t))
    return m.resize((w, h), Image.BICUBIC)


def circle_mask(size, center, r) -> Image.Image:
    m = Image.new("L", size, 0)
    cx, cy = center
    ImageDraw.Draw(m).ellipse((cx - r, cy - r, cx + r, cy + r), fill=255)
    return m


def with_alpha(img: Image.Image, mask: Image.Image) -> Image.Image:
    img = img.copy()
    img.putalpha(ImageChops.multiply(img.getchannel("A"), mask))
    return img


# --------------------------------------------------------------------------- watch frame

def frame_watch(raw_path: Path, shadow: bool = True) -> Image.Image:
    """Returns a CANVAS-sized RGBA image of the screenshot inside a watch."""
    W, H = CANVAS[0] * SS, CANVAS[1] * SS
    cx, cy = CENTER[0] * SS, CENTER[1] * SS
    sr, br, cr = SCREEN_R * SS, BEZEL_R * SS, CASE_R * SS
    img = Image.new("RGBA", (W, H), (0, 0, 0, 0))

    # Soft drop shadow under the case + crown.
    if shadow:
        sh = Image.new("L", (W, H), 0)
        d = ImageDraw.Draw(sh)
        oy = 14 * SS
        d.ellipse((cx - cr, cy - cr + oy, cx + cr, cy + cr + oy), fill=150)
        sh = sh.filter(ImageFilter.GaussianBlur(16 * SS))
        black = Image.new("RGBA", (W, H), (0, 0, 0, 255))
        black.putalpha(sh)
        img = Image.alpha_composite(img, black)

    # Crown (3 o'clock), drawn before the case so the case overlaps its root.
    crown = Image.new("RGBA", (W, H), (0, 0, 0, 0))
    cw, ch = 26 * SS, 64 * SS
    x0 = cx + cr - 10 * SS
    y0 = cy - ch // 2 - 40 * SS
    grad = linear_gradient((cw, ch), (95, 99, 108, 255), (30, 32, 37, 255), 90)
    cm = Image.new("L", (cw, ch), 0)
    ImageDraw.Draw(cm).rounded_rectangle((0, 0, cw - 1, ch - 1), radius=9 * SS, fill=255)
    crown.paste(grad, (x0, y0), cm)
    cd = ImageDraw.Draw(crown)
    for i in range(1, 8):
        yy = y0 + i * ch // 8
        cd.line((x0 + 12 * SS, yy, x0 + cw - 4 * SS, yy), fill=(20, 21, 24, 200), width=max(1, SS))
    # Second, flatter button below (like the Galaxy Watch's back key).
    b2y = cy + 40 * SS - 22 * SS
    g2 = linear_gradient((cw - 6 * SS, 44 * SS), (80, 84, 92, 255), (28, 30, 34, 255), 90)
    m2 = Image.new("L", g2.size, 0)
    ImageDraw.Draw(m2).rounded_rectangle((0, 0, g2.size[0] - 1, g2.size[1] - 1), radius=8 * SS, fill=255)
    crown.paste(g2, (x0, b2y), m2)
    img = Image.alpha_composite(img, crown)

    # Metal case: diagonal gradient disc with a thin light rim on top-left.
    case = linear_gradient((2 * cr, 2 * cr), (74, 78, 88, 255), (18, 19, 23, 255), 60)
    case_layer = Image.new("RGBA", (W, H), (0, 0, 0, 0))
    case_layer.paste(case, (cx - cr, cy - cr), circle_mask((2 * cr, 2 * cr), (cr, cr), cr))
    img = Image.alpha_composite(img, case_layer)
    rim = Image.new("RGBA", (W, H), (0, 0, 0, 0))
    rd = ImageDraw.Draw(rim)
    rd.ellipse((cx - cr + SS, cy - cr + SS, cx + cr - SS, cy + cr - SS), outline=(160, 166, 178, 120), width=2 * SS)
    rim = with_alpha(rim, linear_gradient((W, H), (0, 0, 0, 255), (0, 0, 0, 0), 45).getchannel("A"))
    img = Image.alpha_composite(img, rim)
    # Inner shading ring between case and glass.
    ring = Image.new("RGBA", (W, H), (0, 0, 0, 0))
    ImageDraw.Draw(ring).ellipse((cx - br - 10 * SS, cy - br - 10 * SS, cx + br + 10 * SS, cy + br + 10 * SS), fill=(28, 29, 33, 255))
    img = Image.alpha_composite(img, ring)
    # Black glass border.
    glass = Image.new("RGBA", (W, H), (0, 0, 0, 0))
    ImageDraw.Draw(glass).ellipse((cx - br, cy - br, cx + br, cy + br), fill=(4, 4, 6, 255))
    img = Image.alpha_composite(img, glass)

    # The screen, scaled to the display size, circle-masked, with a soft fade at the very edge
    # (like the bezel shading on a real round display).
    raw = Image.open(raw_path).convert("RGBA").resize((2 * sr, 2 * sr), Image.LANCZOS)
    fade = radial_mask((2 * sr, 2 * sr), sr * 0.90, sr * 1.0)
    screen = with_alpha(raw, ImageChops.multiply(fade, circle_mask((2 * sr, 2 * sr), (sr, sr), sr)))
    disp = Image.new("RGBA", (W, H), (0, 0, 0, 0))
    disp.paste(screen, (cx - sr, cy - sr))
    img = Image.alpha_composite(img, disp)

    # Faint glass reflection (top-left).
    refl = Image.new("RGBA", (W, H), (0, 0, 0, 0))
    ImageDraw.Draw(refl).ellipse((cx - br, cy - br, cx + br, cy + br), fill=(255, 255, 255, 16))
    refl = with_alpha(refl, linear_gradient((W, H), (0, 0, 0, 255), (0, 0, 0, 0), 55).getchannel("A"))
    img = Image.alpha_composite(img, refl)

    return img.resize(CANVAS, Image.LANCZOS)


# --------------------------------------------------------------------------- hero

def hero(frames: dict[str, Image.Image]) -> Image.Image:
    W, H = 1600, 800
    bg = linear_gradient((W, H), (14, 15, 20, 255), (34, 24, 44, 255), 25)
    glow = Image.new("RGBA", (W, H), ACCENT + (0,))
    glow.putalpha(radial_mask((W, H), 0, 520, center=(1124, 420)).point(lambda v: v * 0.22))
    bg = Image.alpha_composite(bg, glow)
    glow2 = Image.new("RGBA", (W, H), (120, 110, 220, 0))
    glow2.putalpha(radial_mask((W, H), 0, 600, center=(120, 760)).point(lambda v: v * 0.14))
    bg = Image.alpha_composite(bg, glow2)

    def place(img, scale, angle, center):
        w = round(img.width * scale)
        im = img.resize((w, w), Image.LANCZOS).rotate(angle, resample=Image.BICUBIC, expand=True)
        layer = Image.new("RGBA", (W, H), (0, 0, 0, 0))
        layer.paste(im, (round(center[0] - im.width / 2), round(center[1] - im.height / 2)))
        return layer

    bg = Image.alpha_composite(bg, place(frames["home"], 0.68, 8, (850, 470)))
    bg = Image.alpha_composite(bg, place(frames["reply"], 0.68, -8, (1392, 470)))
    bg = Image.alpha_composite(bg, place(frames["chat"], 0.92, 0, (1124, 410)))

    d = ImageDraw.Draw(bg)
    x = 96
    title = font(96, "bold")
    d.text((x, 228), "Claude", font=title, fill=(245, 245, 247))
    d.text((x + d.textlength("Claude", font=title), 228), "Wear", font=title, fill=ACCENT)
    tag = font(40, "semibold")
    d.text((x, 372), "Your Claude Code chats,", font=tag, fill=(232, 232, 236))
    d.text((x, 422), "on your wrist.", font=tag, fill=(232, 232, 236))
    sub = font(25)
    d.text((x, 500), "Follow progress, get a buzz when it's done,", font=sub, fill=(170, 172, 182))
    d.text((x, 534), "reply by voice.", font=sub, fill=(170, 172, 182))
    small = font(20, "semibold")
    d.text((x, 612), "Wear OS app  +  PC bridge  ·  open source", font=small, fill=(130, 132, 142))
    return bg.convert("RGB")


# --------------------------------------------------------------------------- reply GIF

def reply_gif(path: Path) -> None:
    steps = [
        ("home", "Your desktop chats, by project"),
        ("chat-before", "Open a chat"),
        ("reply", "Tap Reply and dictate"),
        ("sending", "Relayed into the live desktop chat"),
        ("chat-after", "The answer streams back"),
    ]
    CW, CH = 600, 690
    cap = font(28, "semibold")
    num = font(20, "bold")
    card_bg = linear_gradient((CW, CH), (22, 23, 29, 255), (36, 27, 46, 255), 70)
    corner = Image.new("L", (CW, CH), 0)
    ImageDraw.Draw(corner).rounded_rectangle((0, 0, CW - 1, CH - 1), radius=36, fill=255)

    def card(name, caption, i):
        c = card_bg.copy()
        c.alpha_composite(frame_watch(RAW / f"{name}.png", shadow=True), (0, 6))
        d = ImageDraw.Draw(c)
        # Step dots + caption.
        dots_w = len(steps) * 18
        for k in range(len(steps)):
            fill = ACCENT if k == i else (90, 92, 104)
            cx = CW / 2 - dots_w / 2 + k * 18 + 6
            d.ellipse((cx - 5, 612 - 5, cx + 5, 612 + 5), fill=fill)
        tw = d.textlength(caption, font=cap)
        d.text(((CW - tw) / 2, 632), caption, font=cap, fill=(236, 236, 240))
        c.putalpha(corner)
        return c

    cards = [card(n, t, i) for i, (n, t) in enumerate(steps)]
    frames, durations = [], []
    for i, c in enumerate(cards):
        frames.append(c)
        durations.append(1600)
        nxt = cards[(i + 1) % len(cards)]
        for t in (0.33, 0.66):  # short cross-fade
            frames.append(Image.blend(c, nxt, t))
            durations.append(70)

    def to_p(im):
        rgb = Image.new("RGB", im.size, (22, 23, 29))
        rgb.paste(im, mask=im.getchannel("A"))
        p = rgb.quantize(colors=255, method=Image.Quantize.MEDIANCUT, dither=Image.Dither.NONE)
        # Index 255 = transparent rounded corners.
        alpha = im.getchannel("A").point(lambda v: 255 if v < 128 else 0)
        p.paste(255, mask=alpha)
        p.info["transparency"] = 255
        return p

    ps = [to_p(f) for f in frames]
    ps[0].save(path, save_all=True, append_images=ps[1:], duration=durations, loop=0,
               transparency=255, disposal=2, optimize=False)


# --------------------------------------------------------------------------- main

def main() -> int:
    missing = [n for n in ("home", "archive", "chat", "chat-before", "chat-after", "reply", "sending", "approval", "pair")
               if not (RAW / f"{n}.png").exists()]
    if missing:
        print("missing raw screenshots:", ", ".join(missing), "- run the Roborazzi task first (docs/tools/README.md)")
        return 1
    frames = {}
    for name in ("home", "archive", "chat", "reply", "sending", "approval", "pair"):
        frames[name] = frame_watch(RAW / f"{name}.png")
        frames[name].save(OUT / f"{name}.png", optimize=True)
        print("wrote", OUT / f"{name}.png")
    hero(frames).save(OUT / "hero.png", optimize=True)
    print("wrote", OUT / "hero.png")
    reply_gif(OUT / "reply-flow.gif")
    print("wrote", OUT / "reply-flow.gif")
    return 0


if __name__ == "__main__":
    sys.exit(main())
