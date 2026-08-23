# -*- coding: utf-8 -*-
# 窗口吸附收紧验证 v2：悬停取帧→点击后立刻取帧→Enter，贴图尺寸应≈候选窗口尺寸
import ctypes, os, shutil, struct, time

u = ctypes.windll.user32
u.SetProcessDPIAware()
DIST = os.path.abspath(os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "dist"))
FRAME = os.path.join(DIST, "dragframe.bmp")

def log_tail(n=40):
    raw = open(os.path.join(DIST, "tackshot.log"), "rb").read().decode("utf-16-le", "ignore")
    return [l for l in raw.splitlines() if l.strip()][-n:]

def rl(path, x0, y0, x1, y1):
    d = open(path, "rb").read()
    off = struct.unpack("<I", d[10:14])[0]
    w = struct.unpack("<i", d[18:22])[0]
    h = abs(struct.unpack("<i", d[22:26])[0])
    stride = ((w * 4 + 31) // 32) * 4
    s = n = 0
    for y in range(max(0, y0), min(y1, h), 4):
        for x in range(max(0, x0), min(x1, w), 4):
            p = off + y * stride + x * 4
            s += 0.299*d[p+2] + 0.587*d[p+1] + 0.114*d[p]; n += 1
    return s / max(n, 1)

# 候选窗口（应已在日志中）
cands = []
for l in log_tail():
    if "吸附候选" in l:
        try:
            b = l.index("[", l.index("吸附候选"))
            seg = l[b + 1:l.index("]", b)]
            xy, wh = seg.split(" ")
            x, y = map(int, xy.split(","))
            w, h = map(int, wh.split("x"))
            cands.append((w * h, x, y, w, h))
        except Exception:
            pass
assert cands, "无候选"
cands.sort(reverse=True)
_, X, Y, W, H = cands[0]
print(f"目标窗口: [{X},{Y} {W}x{H}]")

cx, cy = X + W // 2, Y + H // 2
u.SetCursorPos(cx, cy)
time.sleep(0.7)
shutil.copy(FRAME, FRAME + ".hover")
in_h = rl(FRAME + ".hover", X + 20, Y + 20, X + W - 20, Y + H - 20)
out_h = rl(FRAME + ".hover", 200, Y + H + 30, 2800, min(Y + H + 110, 1915))
print(f"悬停帧: 窗内={in_h:.0f} 窗外遮罩={out_h:.0f} 比值={in_h/max(out_h,1):.2f}")

# 点击（不移动，避免拖动阈值）；吸附提交后立刻取帧
u.mouse_event(2, 0, 0, 0, 0)
time.sleep(0.05)
u.mouse_event(4, 0, 0, 0, 0)
time.sleep(0.25)
shutil.copy(FRAME, FRAME + ".edit")
in_e = rl(FRAME + ".edit", X + 20, Y + 20, X + W - 20, Y + H - 20)
out_e = rl(FRAME + ".edit", 200, Y + H + 30, 2800, min(Y + H + 110, 1915))
print(f"点击帧: 窗内={in_e:.0f} 窗外遮罩={out_e:.0f} 比值={in_e/max(out_e,1):.2f}")

u.keybd_event(13, 0, 0, 0); u.keybd_event(13, 0, 2, 0)
time.sleep(0.9)
tail = log_tail(6)
pin = [l for l in tail if "贴图创建" in l]
print("贴图日志:", [l.split("] ", 1)[-1] for l in pin])
import re
ok_pin = False
if pin:
    m = re.search(r"(\d+)x(\d+)", pin[-1])
    if m:
        pw, ph = int(m.group(1)), int(m.group(2))
        ok_pin = abs(pw - W) < max(40, W * 0.05) and abs(ph - H) < max(40, H * 0.05)
        print(f"贴图 {pw}x{ph} vs 窗口 {W}x{H} → {'匹配' if ok_pin else '不匹配'}")
hover_ok = in_h > out_h * 1.35
edit_ok = in_e > out_e * 1.35
print(f"RESULT: 悬停高亮={'PASS' if hover_ok else 'FAIL'} 吸附渲染={'PASS' if edit_ok else 'FAIL'} 尺寸={'PASS' if ok_pin else 'FAIL'}")
print("ALL-PASS" if (hover_ok and edit_ok and ok_pin) else "PARTIAL")
