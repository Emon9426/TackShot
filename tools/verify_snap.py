# -*- coding: utf-8 -*-
# 窗口吸附验证：悬停任务栏 → 帧转储中底部条带应变亮；点击 → 日志应出现"窗口吸附截取"
import ctypes, os, struct, subprocess, time

u = ctypes.windll.user32
u.SetProcessDPIAware()
DIST = os.path.abspath(os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "dist"))
FRAME = os.path.join(DIST, "dragframe.bmp")
LOG = os.path.join(DIST, "tackshot.log")

def read_log_tail(n=6):
    raw = open(LOG, "rb").read().decode("utf-16-le", "ignore")
    return [l for l in raw.splitlines() if l.strip()][-n:]

def region_lum(path, x0, y0, x1, y1):
    d = open(path, "rb").read()
    off = struct.unpack("<I", d[10:14])[0]
    w = struct.unpack("<i", d[18:22])[0]
    h = abs(struct.unpack("<i", d[22:26])[0])
    stride = ((w * 4 + 31) // 32) * 4
    s = n = 0
    for y in range(y0, min(y1, h), 3):
        for x in range(x0, min(x1, w), 3):
            p = off + y * stride + x * 4
            s += 0.299 * d[p+2] + 0.587 * d[p+1] + 0.114 * d[p]
            n += 1
    return s / max(n, 1)

# 1) 等初始帧生成；从日志解析候选窗口，选面积最大的那个悬停其中心
time.sleep(0.6)
cands = []
for l in read_log_tail(30):
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
if not cands:
    print("无吸附候选，FAIL"); raise SystemExit(1)
cands.sort(reverse=True)
_, cx0, cy0, cw, ch = cands[0]
print(f"最大候选: [{cx0},{cy0} {cw}x{ch}]，悬停中心 ({cx0 + cw//2},{cy0 + ch//2})")
u.SetCursorPos(cx0 + cw // 2, cy0 + ch // 2)
time.sleep(0.8)                       # MOUSEMOVE → snap 高亮重绘 → 帧转储

bar = region_lum(FRAME, cx0 + 8, cy0 + 8, cx0 + cw - 8, cy0 + ch - 8)   # 窗口内部
mid = region_lum(FRAME, 900, 300, 2200, 700)                            # 屏幕上部（遮罩暗态）
print(f"候选窗内亮度={bar:.0f}  遮罩区亮度={mid:.0f}  比值={bar/max(mid,1):.2f}")
snap_hover = bar > mid * 1.3
print("悬停高亮:", "PASS" if snap_hover else "FAIL")

# 2) 单击 → 窗口吸附截取
before = len(read_log_tail(50))
u.mouse_event(2, 0, 0, 0, 0)
time.sleep(0.08)
u.mouse_event(4, 0, 0, 0, 0)
time.sleep(0.5)
tail = read_log_tail(8)
hit = any("窗口吸附截取" in l for l in tail)
print("点击吸附:", "PASS" if hit else "FAIL", "|", [l.split("] ")[-1] for l in tail[-2:]])

# 3) Enter 确认 → 贴图
u.keybd_event(13, 0, 0, 0); u.keybd_event(13, 0, 2, 0)
time.sleep(0.9)
tail = read_log_tail(4)
fin = any("输出完成" in l for l in tail)
print("确认输出:", "PASS" if fin else "FAIL", "|", [l.split("] ")[-1] for l in tail[-2:]])
print("RESULT:", "ALL-PASS" if (snap_hover and hit and fin) else "PARTIAL/FAIL")
