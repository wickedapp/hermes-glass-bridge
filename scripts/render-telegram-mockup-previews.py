#!/usr/bin/env python3
"""Generate static PNG previews for the Rokid Telegram Sprite Ink mockup.

The real artifact is `rokid-telegram-mockup/` (Ink). These previews are for
review on a normal monitor because Rokid optical output may not be visible via
plain adb screencap.
"""
from pathlib import Path
from PIL import Image, ImageDraw, ImageFont

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / "artifacts" / "mockups"
OUT.mkdir(parents=True, exist_ok=True)
FONT_DIR = ROOT / "rokid-telegram-native" / "app" / "src" / "main" / "res" / "font"
REG = str(FONT_DIR / "harmonyos_sans_sc_regular.ttf")
MED = str(FONT_DIR / "harmonyos_sans_sc_medium.ttf")
GREEN = (64, 255, 94, 255)
GREEN50 = (64, 255, 94, 128)
GREEN40 = (64, 255, 94, 102)
GREEN80 = (64, 255, 94, 204)
BLACK = (0, 0, 0, 255)


def font(path, size):
    try:
        return ImageFont.truetype(path, size)
    except Exception:
        return ImageFont.load_default()

F32 = font(MED, 32)
F20 = font(REG, 20)
F18 = font(REG, 18)
F16 = font(REG, 16)


def text(draw, xy, s, fill=GREEN, f=F20):
    draw.text(xy, s, fill=fill, font=f)


def card(draw, xyxy, outline=GREEN40, width=2, radius=12):
    draw.rounded_rectangle(xyxy, radius=radius, outline=outline, width=width, fill=None)


def base(title, subtitle, banner=""):
    im = Image.new("RGBA", (480, 640), BLACK)
    d = ImageDraw.Draw(im)
    # safe-area guide: non-essential, faint line only for preview
    d.line((0, 120, 480, 120), fill=(64, 255, 94, 35), width=1)
    d.line((0, 520, 480, 520), fill=(64, 255, 94, 35), width=1)
    text(d, (18, 18), title, GREEN, F32)
    text(d, (18, 58), subtitle, GREEN50, F16)
    if banner:
        card(d, (18, 84, 462, 112), GREEN80)
        text(d, (28, 87), banner, GREEN, F16)
    return im, d


def draw_list(d, focus=0):
    chats = [
        ("Jane / Hermes", "09:42  ●", "先看 mockup，再接 TDLib live data"),
        ("Rokid Dev", "09:18  ○", "CXR companion · helper ready"),
        ("Family", "Yesterday  ○", "今晚吃饭?"),
    ]
    y = 128
    for i, (name, meta, preview) in enumerate(chats):
        card(d, (12, y, 468, y + 64), GREEN80 if i == focus else GREEN40)
        text(d, (24, y + 8), "▶" if i == focus else " ", GREEN, F18)
        text(d, (50, y + 5), name, GREEN, F20)
        text(d, (350, y + 7), meta, GREEN50, F16)
        text(d, (50, y + 33), preview, GREEN50, F18)
        y += 69


def draw_messages(d, y=338, composer="Enter: open · ↑↓: choose · Back: system"):
    bubbles = [
        ((12, y, 432, y + 52), GREEN40, "Jane", "Telegram 放在眼鏡上：只顯示必要訊息。"),
        ((48, y + 58, 468, y + 110), GREEN80, "Me", "Enter 語音，Swipe 切換，雙擊返回。"),
        ((12, y + 116, 432, y + 168), GREEN40, "Jane", "UI 要符合 Rokid 安全區。"),
    ]
    for box, stroke, peer, body in bubbles:
        card(d, box, stroke)
        text(d, (box[0] + 10, box[1] + 4), peer, GREEN50, F16)
        text(d, (box[0] + 10, box[1] + 24), body, GREEN, F18)
    card(d, (18, 526, 462, 560), GREEN80)
    text(d, (28, 531), composer, GREEN, F18)
    text(d, (18, 568), "480×400 safe area · no fills/gradients/shadows", GREEN50, F16)


def state_list():
    im, d = base("Telegram", "3 chats · phone companion online via CXR")
    draw_list(d, 0)
    draw_messages(d)
    return im


def state_chat():
    im, d = base("Jane / Hermes", "CXR live data · media hidden until selected")
    draw_list(d, 0)
    draw_messages(d, composer="Hold Enter / two-finger double tap: dictate reply")
    return im


def state_composer():
    im, d = base("Voice reply", "Phone ASR / TDLib over CXR · interim 50%", "LISTENING 7s · cancel with Back")
    # voice composer replaces message band
    card(d, (90, 158, 390, 398), GREEN80)
    d.ellipse((190, 194, 290, 294), outline=GREEN, width=2)
    text(d, (150, 312), "Interim: 我正在用眼鏡回覆…", GREEN50, F18)
    text(d, (132, 340), "Final: 我正在用眼鏡回覆 Telegram。", GREEN, F18)
    text(d, (154, 374), "▂ ▃ ▅ ▇ ▅ ▃  discrete meter", GREEN, F16)
    card(d, (18, 526, 462, 560), GREEN80)
    text(d, (28, 531), "Enter sends · Back returns to chat", GREEN, F18)
    text(d, (18, 568), "No waveform blobs; no large bright fill", GREEN50, F16)
    return im


def state_banner():
    im, d = base("Incoming banner", "Off-chat notification in top cautious zone", "New · Rokid Dev: helper ready")
    card(d, (42, 176, 438, 282), GREEN80)
    text(d, (64, 196), "Top strip notification", GREEN, F20)
    text(d, (64, 228), "Enter opens · Back dismisses", GREEN50, F18)
    text(d, (64, 254), "Does not block message stream", GREEN50, F18)
    draw_messages(d, y=314, composer="Enter: return to list")
    return im

states = [
    ("telegram-mockup-list.png", state_list()),
    ("telegram-mockup-chat.png", state_chat()),
    ("telegram-mockup-composer.png", state_composer()),
    ("telegram-mockup-banner.png", state_banner()),
]
for name, im in states:
    im.convert("RGB").save(OUT / name)

contact = Image.new("RGB", (960, 1280), BLACK[:3])
for idx, (_, im) in enumerate(states):
    x = 480 * (idx % 2)
    y = 640 * (idx // 2)
    contact.paste(im.convert("RGB"), (x, y))
contact.save(OUT / "telegram-mockup-contact-sheet.png")
print(OUT / "telegram-mockup-contact-sheet.png")
