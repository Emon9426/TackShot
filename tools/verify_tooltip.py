# -*- coding: utf-8 -*-
# 静止悬停验证：拖拽出选区后，把光标停到工具条按钮上；
# 检查"移动后的静止期"内 dragframe.bmp 是否被定时器再次重写（=tooltip 出现）
import ctypes, os, shutil, struct, time

u = ctypes.windll.user32
u.SetProcessDPIAware()
DIST = os.path.abspath(os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "dist"))
FRAME = os.path.join(DIST, "dragframe.bmp")

def mtime(): return os.path.getmtime(FRAME)

def bright_rect(path):
    d = open(path, "rb").read()
    off = struct.unpack("<I", d[10:14])[0]
    w = struct.unpack("<i", d[18:22])[0]
    h = abs(struct.unpack("<i", d[22:26])[0])
    stride = ((w * 4 + 31) // 32) * 4
    def lum(x, y):
        p = off + y * stride + x * 4
        return 0.299 * d[p+2] + 0.587 * d[p+1] + 0.114 * d[p]
    gx, gy = 48, 30
    pos = []
    meds = []
    for iy in range(gy):
        for ix in range(gx):
            x0, y0 = ix * w // gx, iy * h // gy
            s = n = 0
            for dy in range(0, h // gy, 9):
                for dx in range(0, w // gx, 9):
                    s += lum(x0 + dx, y0 + dy); n += 1
            meds.append(s / n)
            pos.append((ix, iy, s / n))
    meds.sort()
    med = meds[len(meds) // 2]
    pts = [(ix, iy) for ix, iy, v in pos if v > med * 1.25 + 6]
    xs = [p[0] for p in pts]; ys = [p[1] for p in pts]
    return (min(xs) * w // gx, min(ys) * h // gy,
            (max(xs) + 1) * w // gx, (max(ys) + 1) * h // gy)

# 1) 拖拽出选区（拖拽中途取一帧用于定位选区，避免松手后状态不确定）
u.SetCursorPos(1200, 900); time.sleep(0.4)
u.mouse_event(2, 0, 0, 0, 0)
for i in range(1, 21):
    u.SetCursorPos(1200 + 800 * i // 20, 900 + 500 * i // 20)
    time.sleep(0.03)
time.sleep(0.3)
shutil.copy(FRAME, FRAME + ".mid")     # 拖拽中的帧：白色高亮选区必在
u.mouse_event(4, 0, 0, 0, 0)
time.sleep(0.5)                         # 编辑态渲染完成

x0, y0, x1, y1 = bright_rect(FRAME + ".mid")
print(f"选区(拖拽中帧): x[{x0}-{x1}] y[{y0}-{y1}]")

# 2) 光标停到工具条带上（选区下方一排候选点），静止期观察帧是否被定时器重写
cx = (x0 + x1) // 2
hit = None
for dy in (10, 14, 18, 24, 30, 6):
    u.SetCursorPos(cx, y1 + dy)
    time.sleep(0.35)                # 移动引起的重绘
    t1 = mtime()
    shutil.copy(FRAME, FRAME + ".a")
    time.sleep(0.55)                # 静止 320ms 定时器应触发
    t2 = mtime()
    print(f"  停靠 y={y1+dy}: 静止前 t1={t1:.2f} 后 t2={t2:.2f} {'重写' if t2 > t1 + 0.05 else '无'}")
    if t2 > t1 + 0.05:
        hit = (cx, y1 + dy)
        shutil.copy(FRAME, FRAME + ".b")
        break

if hit:
    # 3) 对比两帧：静止期新增的深色 chip（tooltip）
    a = open(FRAME + ".a", "rb").read()
    b = open(FRAME + ".b", "rb").read()
    off = struct.unpack("<I", a[10:14])[0]
    w = struct.unpack("<i", a[18:22])[0]
    h = abs(struct.unpack("<i", a[22:26])[0])
    stride = ((w * 4 + 31) // 32) * 4
    diff = [(x, y) for y in range(0, h, 2) for x in range(0, w, 2)
            if a[off+y*stride+x*4:off+y*stride+x*4+3] != b[off+y*stride+x*4:off+y*stride+x*4+3]]
    print(f"静止期帧差像素数: {len(diff)}")
    if diff:
        xs = [p[0] for p in diff]; ys = [p[1] for p in diff]
        print(f"差异区域: x[{min(xs)}-{max(xs)}] y[{min(ys)}-{max(ys)}]（光标 {hit}）→ tooltip 已出现")
    print("PASS: 静止悬停触发重绘")
else:
    print("FAIL: 静止期无重写（定时器未触发或未命中按钮）")
