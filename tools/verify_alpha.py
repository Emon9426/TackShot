# -*- coding: utf-8 -*-
# FR-4.13 透明度交互回归（V2.0 Java 版）：
#   ① 左键 ◐ 三次循环 0→25→75→0
#   ② 右键 ◐ 二级菜单：+ 步进 10/20；点值格输入 99 → 夹紧 95（永不 100%）
#   ③ Ctrl+滚轮 ±10%（95→85→75）
#   ④ 再点 ◐：75→0 循环边界
# 坐标按 Toolbar::Layout 复刻（PinHover 总宽 334 物理 px，200% DPI）
import ctypes, ctypes.wintypes as wt, os, subprocess, time

u = ctypes.windll.user32
u.SetProcessDPIAware()
DIST = os.path.abspath(os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "dist"))
LOG = os.path.join(DIST, "tackshot.log")
LOG_START = os.path.getsize(LOG) if os.path.exists(LOG) else 0

def key(v, up=False): u.keybd_event(v, 0, 2 if up else 0, 0)
def click_l(x, y):
    u.SetCursorPos(x, y); time.sleep(0.12)
    u.mouse_event(2, 0, 0, 0, 0); time.sleep(0.06)
    u.mouse_event(4, 0, 0, 0, 0); time.sleep(0.22)
def click_r(x, y):
    u.SetCursorPos(x, y); time.sleep(0.12)
    u.mouse_event(8, 0, 0, 0, 0); time.sleep(0.06)
    u.mouse_event(16, 0, 0, 0, 0); time.sleep(0.25)

def log_new():
    try:
        with open(LOG, "rb") as f:
            f.seek(LOG_START)
            return f.read().decode("utf-16-le", errors="ignore")
    except OSError:
        return ""

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

env = dict(os.environ); env["TACKSHOT_DEBUG_SHOT"] = "1"
proc = subprocess.Popen(["java", "-Dsun.java2d.uiScale.enabled=false",
                         "-jar", os.path.join(DIST, "TackShotAI.jar")], env=env, cwd=DIST)
try:
    t0 = time.time()
    while time.time() - t0 < 20 and "热键已注册：区域截图" not in log_new():
        time.sleep(0.3)
    assert "热键已注册：区域截图" in log_new(), "应用未就绪"
    time.sleep(0.5)

    # 截一块 600x300（物理），保证贴图窗口宽度容纳整条悬浮菜单（334px）
    key(0x11); key(0x12); key(0x41); key(0x41, True); key(0x12, True); key(0x11, True)
    time.sleep(0.8)
    u.SetCursorPos(1136, 812); time.sleep(0.2)
    u.mouse_event(2, 0, 0, 0, 0); time.sleep(0.1)
    u.SetCursorPos(1736, 1112); time.sleep(0.1)
    u.mouse_event(4, 0, 0, 0, 0); time.sleep(0.3)
    key(13); key(13, True)
    time.sleep(1.2)

    hwnd, r = find_pin(proc.pid)
    assert hwnd, "无贴图"
    ww, cx = r.right - r.left, (r.left + r.right) // 2
    print(f"贴图: {r.left},{r.top} {ww}x{r.bottom - r.top}")

    # ◐ 按钮：bar.x = ww/2 - 334/2，OPAQUE 中心在 bar 内偏移 269 物理
    opq_x = r.left + ww // 2 - 334 // 2 + 269
    opq_y = r.top + 32

    # 悬停出菜单
    u.SetCursorPos(cx, r.top + 240); time.sleep(0.9)

    # ① 左键循环 ×3
    for _ in range(3): click_l(opq_x, opq_y)

    # ② 右键二级菜单（值格中心对准按钮中心）
    fly_x0 = opq_x - 68
    c1 = (fly_x0 + 34, r.top + 88)               # −
    c2 = (fly_x0 + 102, r.top + 88)              # 值
    c3 = (fly_x0 + 170, r.top + 88)              # +
    click_r(opq_x, opq_y)
    click_l(*c3); click_l(*c3)                   # + ×2 → 10%, 20%
    click_l(*c2); time.sleep(0.3)                # 值格 → 输入框
    key(0x39); key(0x39); time.sleep(0.15)       # 输入 99
    key(13); key(13, True); time.sleep(0.4)      # Enter → 夹紧 95

    # ③ Ctrl+滚轮 上 ×2 → 85%, 75%
    u.SetCursorPos(cx, r.top + 240); time.sleep(0.2)
    key(0x11)
    u.mouse_event(0x0800, 0, 120, 0, 0); time.sleep(0.25)
    u.mouse_event(0x0800, 0, 120, 0, 0); time.sleep(0.3)
    key(0x11, True)

    # ④ 再点 ◐：75 → 0
    u.SetCursorPos(cx, r.top + 240); time.sleep(0.3)
    click_l(opq_x, opq_y)

    visible = bool(u.IsWindowVisible(hwnd))
    print("贴图仍可见:", visible)
    key(0x1B); key(0x1B, True); time.sleep(0.4)

    # 日志断言（只查本次运行新增段）
    text = log_new()
    expect = ["透明度循环：25%", "透明度循环：75%", "透明度循环：0%",
              "透明度二级菜单：展开",
              "透明度步进：10%", "透明度步进：20%",
              "透明度输入框：当前 20%",
              "透明度输入：99 → 夹紧 95%",
              "透明度滚轮：85%", "透明度滚轮：75%",
              "透明度循环：0%"]
    fails = [e for e in expect if e not in text]
    print("期望序列命中:", f"{len(expect) - len(fails)}/{len(expect)}")
    for e in fails: print("  缺失:", e)
    print("RESULT:", "PASS" if (not fails and visible) else "FAIL")
finally:
    proc.terminate()
    try:
        proc.wait(5)
    except Exception:
        proc.kill()
