# -*- coding: utf-8 -*-
# 残影回归验证（V2.0 Java 版）：贴图悬停出菜单→移开隐藏→顶部悬浮区必须完全透明（alpha 全 0）
# 自包含：启动 dist/TackShotAI.jar（TACKSHOT_DEBUG_SHOT=1）→ 热键 → 拖选 → Enter → 断言 → 结束进程
import ctypes, ctypes.wintypes as wt, os, struct, subprocess, time

u = ctypes.windll.user32
u.SetProcessDPIAware()
DIST = os.path.abspath(os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "dist"))
FRAME = os.path.join(DIST, "pinframe.bmp")
LOG = os.path.join(DIST, "tackshot.log")
# 只读本次运行新增的日志段（日志跨运行追加，整读会命中旧行导致误判就绪）
LOG_START = os.path.getsize(LOG) if os.path.exists(LOG) else 0

def key(v, up=False): u.keybd_event(v, 0, 2 if up else 0, 0)
def me(f): u.mouse_event(f, 0, 0, 0, 0)
def hotkey():
    key(0x11); key(0x12); key(0x41); key(0x41, True); key(0x12, True); key(0x11, True)

def log_text():
    try:
        with open(LOG, "rb") as f:
            f.seek(LOG_START)
            return f.read().decode("utf-16-le", errors="ignore")
    except OSError:
        return ""

def strip_alpha_sum(path):
    d = open(path, "rb").read()
    off = struct.unpack("<I", d[10:14])[0]
    w = struct.unpack("<i", d[18:22])[0]
    h = abs(struct.unpack("<i", d[22:26])[0])
    bpp = struct.unpack("<H", d[28:30])[0]
    stride = ((w * bpp + 31) // 32) * 4
    # 只统计悬浮区内部：y < 56（200% DPI 下 topZone=60，y>=60 已是黑边框/图像）
    top = 0
    for y in range(0, min(56, h)):
        for x in range(0, w, 2):
            top += d[off + y * stride + x * 4 + 3]   # alpha
    return top, w, h

# 按 PID + 可见 + 尺寸 找贴图窗口（Java 版窗口类为 SunAwt*，无固定类名）
def find_pin(pid, timeout=10):
    t0 = time.time()
    while time.time() - t0 < timeout:
        res = []
        @ctypes.WINFUNCTYPE(wt.BOOL, wt.HWND, wt.LPARAM)
        def cb(hwnd, lp):
            p = wt.DWORD()
            u.GetWindowThreadProcessId(hwnd, ctypes.byref(p))
            if p.value == pid and u.IsWindowVisible(hwnd):
                r = wt.RECT(); u.GetWindowRect(hwnd, ctypes.byref(r))
                if (r.right - r.left) >= 50 and (r.bottom - r.top) >= 50:
                    res.append((hwnd, r))
            return True
        u.EnumWindows(cb, 0)
        if res:
            return res[0]
        time.sleep(0.3)
    return None, None

# ---- 启动 Java 版 ----
env = dict(os.environ); env["TACKSHOT_DEBUG_SHOT"] = "1"
proc = subprocess.Popen(["java", "-Dsun.java2d.uiScale.enabled=false",
                         "-jar", os.path.join(DIST, "TackShotAI.jar")],
                        env=env, cwd=DIST)
try:
    t0 = time.time()
    while time.time() - t0 < 20 and "热键已注册：区域截图" not in log_text():
        time.sleep(0.3)
    assert "热键已注册：区域截图" in log_text(), "应用未就绪"
    time.sleep(0.5)

    hotkey(); time.sleep(0.8)
    u.SetCursorPos(1336, 712); time.sleep(0.2)
    me(2); time.sleep(0.1)
    u.SetCursorPos(1536, 912); time.sleep(0.1)   # 拖出 400x400（200% DPI）
    me(4); time.sleep(0.3)
    key(13); key(13, True)
    time.sleep(1.2)

    hwnd, r = find_pin(proc.pid)
    assert hwnd, "无贴图"
    cx = (r.left + r.right) // 2
    icy = (r.top + r.bottom) // 2 + 60
    print(f"贴图窗口: {r.left},{r.top} {r.right-r.left}x{r.bottom-r.top}")

    # 0) 先移开，等菜单隐藏，取"干净"基线
    u.SetCursorPos(r.right + 150, r.top + 250); time.sleep(1.3)
    a0, w, h = strip_alpha_sum(FRAME)
    print(f"基线（菜单隐藏）悬浮区 alpha 总和 = {a0}，帧 {w}x{h}（应 =0）")

    # 1) 悬停出菜单
    u.SetCursorPos(cx, icy); time.sleep(0.9)
    a1, _, _ = strip_alpha_sum(FRAME)
    print(f"菜单显示时悬浮区 alpha 总和 = {a1}（应 >0）")

    # 2) 移开 → 500ms 隐藏 → 悬浮区必须回到全透明
    u.SetCursorPos(r.right + 150, r.top + 250); time.sleep(1.3)
    a2, _, _ = strip_alpha_sum(FRAME)
    print(f"菜单隐藏后悬浮区 alpha 总和 = {a2}（必须 =0）")
    print("RESULT:", "PASS-残影已清" if (a1 > 0 and a2 == 0 and a0 == 0) else
          ("FAIL-仍有残影" if a2 > 0 else "CHECK-菜单未出现?"))
finally:
    proc.terminate()
    try:
        proc.wait(5)
    except Exception:
        proc.kill()
