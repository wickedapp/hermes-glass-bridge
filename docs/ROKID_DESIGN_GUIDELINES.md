# Rokid Glasses UI Design Guidelines

Source: official Rokid design spec, `custom.rokid.com/prod/rokid_web/57e35cd3ae294d16b1b8fc8dcbb1b7c7/pc/cn/5a71b66dbc1e4689886c7aa437299f2b.html`. This file captures the constraints that apply to anything we render on the glasses display (CXR CustomView / CustomApp surfaces, native APKs, Sprite Ink mini-apps). Numbers and Chinese terms quoted as-is so the spec is verifiable against the source.

## 1. Canvas & safe area (画布 / 边距布局)

| Item | Value |
|---|---|
| Optical resolution (光机实际大小) | **480 × 640 px** |
| Best display area (最佳显示区域) | **480 × 400 px**, vertically centered |
| Top "use cautiously" zone (160px 显示效果不佳慎用) | top 160 px — display quality is poor; place secondary content only |
| Bottom "use cautiously" zone (80px 显示效果不佳慎用) | bottom 80 px — same as above |
| Left / right margin (左/右边距 贴边 0px) | 0 px — content goes edge-to-edge horizontally |

**Implication:** treat the screen as if it were `480 × 400` centered in a `480 × 640` frame. Everything actionable, anything the user must read, must sit inside the central 480 × 400 region. Top and bottom strips are usable but only for ambient / decorative / status content the user doesn't have to focus on.

## 2. Typography (字体字号)

Typeface: **HarmonyOS Sans SC** (华为鸿蒙), `Regular` and `Medium` weights. Same family for Chinese and Latin scripts.

| Level | Use | Font size | Line height | Weight |
|---|---|---|---|---|
| 一级 L1 | Hero / page title | 32 px | 40 px | Regular or Medium |
| 二级 L2 | Section header / primary chat body | 24 px | 32 px | Regular or Medium |
| 三级 L3 | Standard list row / body | 20 px | 26 px | Regular or Medium |
| 四级 L4 | Secondary label / metadata | 18 px | 24 px | Regular or Medium |
| 五级 L5 | Caption / smallest acceptable | 16 px | 22 px | Regular or Medium |

**Rules of thumb:**
- 16 px is the absolute floor — anything smaller is unreadable on the optical engine.
- Don't mix more than 3 levels on one screen.
- CJK is the design baseline; Latin renders comfortably at the same sizes.

## 3. Color & strokes (用色描边)

**Background:** pure black (`#000000`). Black = transparent on AR glasses — every black pixel becomes "see-through" to the real world. There is no "light mode."

**Primary foreground:** `#40FF5E` (Rokid green) at **100% opacity** (建议 / recommended). Lower opacity on large fills produces visible mosaic artifacts at low brightness (低亮度下大面积色块会显示马赛克).

**Hard prohibitions (禁忌):**
- **No gradients** (禁用渐变).
- **No large bright-color fills** (禁用大面积高亮色). Use stroked outlines, not filled blocks, for anything bigger than an icon.

**Strokes (描边):**
- Minimum stroke width: **1.5 px** (描边 ≥ 1.5 px). Allowed values 1.5 / 2 / 4 px.
- Common corner radius: **12 px** (圆角值常用 12px).

**Interaction states (use stroke opacity, not fill):**
| State | Stroke opacity |
|---|---|
| 常态 Normal | 40% |
| 选中 Selected | 80% |
| 按下 Pressed | 100% |

## 4. Icons (Icon)

- Style: **line / outline only** (线性图片 — 识别清晰，更省电). No filled / "glyph" icons; outlines render sharper on the optical engine and draw less power.
- **Sizes:**
  | Size | Use |
  |---|---|
  | 40 × 40 px | App-tile / launcher icon (应用) |
  | 20 × 20 px | Regular UI icon (常规) — toolbars, list-row affordances |
  | 16 × 16 px | Smallest acceptable (最小) — inline indicators |
- **Two-tone option (双色 icon, 建议):** primary at 100%, secondary at 50% of the same green. Use to differentiate icon parts (e.g., handset vs. waves on a phone-call icon).
- **Pixel-grid alignment is mandatory** (图标设计贴合像素格). 1 px off → blurry on the optical engine. Always design and export icons at integer pixel boundaries — no sub-pixel paths, no half-stroke offsets.

## 5. Layout & motion (derived rules)

The source images cover canvas, type, color and icons. The following are *derived* defaults consistent with those constraints and the way Rokid's own first-party UI (launcher, Hi Rokid) behaves; revisit if the doc later specifies otherwise.

- **Status / chrome:** top status bar should sit inside the upper "cautious" 160 px. Treat anything in that band as non-essential.
- **List rows:** L3 (20 / 26) for primary, L4 (18 / 24) for secondary. Row height ≥ 64 px so a focused row's outline reads at 1.5 px stroke without visual collision.
- **Cards:** stroked rectangle, `12 px` corner radius, `1.5 px` stroke, no fill. Selected state = same card with 80% stroke. Avoid stacking >2 cards vertically inside the safe area.
- **Motion:** prefer instant state changes or short crossfades (≤ 150 ms). No bouncing, no parallax, no large translate — they read as motion sickness through the optical engine.
- **No glow / blur / shadow** — the optical engine cannot render soft edges cleanly; they appear as halos.

## 6. Input modalities (project-specific)

These glasses (`RG-glasses`, Android 12) expose:

- **Bluetooth keyboard / remote**: standard Android key events.
- **Glasses-side touchpad** (if present on this SKU): single tap, double tap, swipe up/down/back. Surface only as fallback.
- **Voice** via the Sprite Ink runtime's `SpeechRecognition` global (NXP RT600 + iFlytek), Chinese wake word **"乐奇" (lè qí)**. Inside a CXR CustomApp on the glasses, prefer routing audio to the phone-side companion (which has CPU headroom and 4G/5G) for STT, and only use the on-device path when the phone is unreachable.
- **No head-tracking, no hand-tracking** on this SKU.

## 7. Applied to the Telegram client (project-specific reading)

Concrete defaults the implementation should start from. Revisit only with reason.

- **Background:** `#000000` everywhere. Never a colored canvas.
- **Primary text & strokes:** `#40FF5E` at 100% opacity. Inactive / metadata: same green at 50%.
- **No filled message bubbles.** Use stroked rectangles (12 px radius, 1.5 px stroke). Own messages vs. received: differentiate by stroke opacity (own = 80%, received = 40%) or by alignment (own right, received left), not by fill color.
- **Chat-list row:** 480 px wide, ≥ 64 px tall. Title = L3 / 20 px. Last-message preview + timestamp = L4 / 18 px at 50% green. Unread indicator = 16 × 16 stroked circle, 100% green, right-aligned.
- **Open chat (single conversation):** vertically scrolling stream inside the central 480 × 400. Top 160 px = peer name + "voice active" pill + connection state. Bottom 80 px = composer hint or transcript draft. The middle band is where users actually read.
- **Composer:** show the live ASR transcript in L3 (20 / 26). Final transcript flips from 50% green (interim) to 100% green (final) before send.
- **Voice-note recording overlay:** stroked outline ring centered, level-meter via a discrete bar (no filled blob). Cancel/send hints in L4 outside the ring.
- **Icons:** all 20 × 20 line icons from the spec's icon set (mic / mic-off / send / back / settings / unread / voice-note / photo placeholder). Export pixel-aligned.
- **No emoji rendering as bitmap** — they're inherently filled and will look muddy. Either skip emoji in MVP or render them as monochrome line glyphs.

## 8. Verification checklist before merging UI

- [ ] Background pixel-sampled to `#000000`, zero non-black fills > 32 × 32 px.
- [ ] All text ≥ 16 px, line-height per table in §2.
- [ ] All strokes ≥ 1.5 px and pixel-grid aligned.
- [ ] All critical content inside the `480 × 400` safe area.
- [ ] No gradients, no shadows, no glow filters.
- [ ] Tested on real device via `scrcpy` mirror — read at arm's length, not just on the Mac screen.
