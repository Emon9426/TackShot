# -*- coding: utf-8 -*-
# 贴图尺寸调整验证（V2.0 Java 版）：热键 → 点击窗口吸附 → Enter → 拖右下角(等比)/拖右边(单轴)
import ctypes, ctypes.wintypes as wt, os, subprocess, time

u = ctypes.windll.user32
u.SetProcessDPIAware()
DIST = os.path.abspath(os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "dist"))
LOG = os.path.join(DIST, "tackshot.log")
LOG_START = os.path.getsize(LOG) if os.path.exists(LOG) else 0

def key(v, up=False): u.keybd_event(v, 0, 2 if up else 0, 0)
def hotkey():
    key(0x11); key(0x12); key(0x41); key(0x41, True); key(0x12, True); key(0x11, True)
def me(f): u.mouse_event(f, 0, 0, 0, 0)
def rect(hwnd):
    r = wt.RECT(); u.GetWindowRect(hwnd, ctypes.byref(r)); return r

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

    # 1) 区域截图 → 窗口吸附点击 → Enter
    hotkey(); time.sleep(0.8)
    u.SetCursorPos(1536, 912); time.sleep(0.4)
    me(2); time.sleep(0.05); me(4); time.sleep(0.4)
    key(13); key(13, True)
    time.sleep(1.0)

    # 2) 找贴图窗口（按 java 进程 PID）
    hwnd, _ = find_pin(proc.pid)
    if not hwnd:
        print("FAIL: 未找到贴图窗口"); raise SystemExit(1)
    r1 = rect(hwnd)
    print(f"贴图初始: {r1.right-r1.left}x{r1.bottom-r1.top} @({r1.left},{r1.top})")

    # 3) 拖右上角 (+180,-140) —— 应等比放大。topZone≈60、边带≈14（200% DPI），
    #     取 y=top+66 落在上边拖拽带内
    cx, cy = r1.right - 12, r1.top + 66
    u.SetCursorPos(cx, cy); time.sleep(0.25)
    pt = wt.POINT(cx, cy)
    h = u.WindowFromPoint(pt)
    print(f"按下前命中 hwnd={h} pin={hwnd} {'OK' if h == hwnd else 'MISMATCH'}")
    me(2); time.sleep(0.05)
    for i in range(1, 11):
        u.SetCursorPos(cx + 18 * i, cy - 14 * i); time.sleep(0.03)
        if i in (3, 7):
            rm = rect(hwnd)
            print(f"  拖角中 i={i}: {rm.right-rm.left}x{rm.bottom-rm.top} @({rm.left},{rm.top})")
    me(4); time.sleep(0.4)
    r2 = rect(hwnd)
    dw = (r2.right - r2.left) - (r1.right - r1.left)
    dh = (r2.bottom - r2.top) - (r1.bottom - r1.top)
    print(f"拖角后: {r2.right-r2.left}x{r2.bottom-r2.top}  Δw={dw} Δh={dh}")
    corner_ok = dw > 60 and dh > 40

    # 4) 拖右边中点 (+160, 0) —— 应只变宽
    cx, cy = r2.right - 3, (r2.top + r2.bottom) // 2
    u.SetCursorPos(cx, cy); time.sleep(0.25)
    me(2); time.sleep(0.05)
    for i in range(1, 11):
        u.SetCursorPos(cx + 16 * i, cy); time.sleep(0.03)
    me(4); time.sleep(0.4)
    r3 = rect(hwnd)
    dw2 = (r3.right - r3.left) - (r2.right - r2.left)
    dh2 = (r3.bottom - r3.top) - (r2.bottom - r2.top)
    print(f"拖边后: {r3.right-r3.left}x{r3.bottom-r3.top}  Δw={dw2} Δh={dh2}")
    edge_ok = dw2 > 60 and abs(dh2) <= 2

    print(f"RESULT: 四角等比={'PASS' if corner_ok else 'FAIL'} 四边单轴={'PASS' if edge_ok else 'FAIL'}")
    print("ALL-PASS" if (corner_ok and edge_ok) else "PARTIAL/FAIL")
finally:
    proc.terminate()
    try:
        proc.wait(5)
    except Exception:
        proc.kill()
