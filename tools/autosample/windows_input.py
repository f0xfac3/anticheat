"""User-run foreground controller. It never reads or patches client memory."""
from __future__ import annotations
import ctypes as C
from ctypes import wintypes as W
import time
import psutil

U = C.WinDLL("user32", use_last_error=True)
U.SetProcessDPIAware()
ULONG_PTR = W.WPARAM


class MOUSE(C.Structure):
    _fields_ = [("dx", W.LONG), ("dy", W.LONG), ("mouseData", W.DWORD), ("dwFlags", W.DWORD), ("time", W.DWORD), ("dwExtraInfo", ULONG_PTR)]


class KEY(C.Structure):
    _fields_ = [("wVk", W.WORD), ("wScan", W.WORD), ("dwFlags", W.DWORD), ("time", W.DWORD), ("dwExtraInfo", ULONG_PTR)]


class HARDWARE(C.Structure):
    _fields_ = [("uMsg", W.DWORD), ("wParamL", W.WORD), ("wParamH", W.WORD)]


class PAYLOAD(C.Union):
    _fields_ = [("mi", MOUSE), ("ki", KEY), ("hi", HARDWARE)]


class INPUT(C.Structure):
    _anonymous_ = ("payload",)
    _fields_ = [("type", W.DWORD), ("payload", PAYLOAD)]


U.SendInput.argtypes = [W.UINT, C.POINTER(INPUT), C.c_int]
U.SendInput.restype = W.UINT
U.GetForegroundWindow.restype = W.HWND
U.IsWindow.argtypes = [W.HWND]
U.GetWindowThreadProcessId.argtypes = [W.HWND, C.POINTER(W.DWORD)]
U.GetWindowTextW.argtypes = [W.HWND, W.LPWSTR, C.c_int]
U.GetClassNameW.argtypes = [W.HWND, W.LPWSTR, C.c_int]
U.GetClientRect.argtypes = [W.HWND, C.POINTER(W.RECT)]
U.ClientToScreen.argtypes = [W.HWND, C.POINTER(W.POINT)]
U.ScreenToClient.argtypes = [W.HWND, C.POINTER(W.POINT)]
U.SetForegroundWindow.argtypes = [W.HWND]
U.ShowWindow.argtypes = [W.HWND, C.c_int]
U.IsIconic.argtypes = [W.HWND]
U.GetCursorPos.argtypes = [C.POINTER(W.POINT)]

SCANS = {"forward": 0x11, "sprint": 0x1d, "jump": 0x39, "enter": 0x1c}


def describe(hwnd):
    if not hwnd or not U.IsWindow(hwnd):
        raise RuntimeError("Minecraft window no longer exists. Register the client again.")
    pid = W.DWORD()
    U.GetWindowThreadProcessId(hwnd, C.byref(pid))
    process = psutil.Process(pid.value)
    title, cls = C.create_unicode_buffer(512), C.create_unicode_buffer(256)
    U.GetWindowTextW(hwnd, title, len(title)); U.GetClassNameW(hwnd, cls, len(cls))
    rect = W.RECT(); U.GetClientRect(hwnd, C.byref(rect))
    if cls.value.lower() != "lwjgl":
        raise RuntimeError("Select a Minecraft 1.8.9 LWJGL window, in windowed mode.")
    return dict(hwnd=int(hwnd), pid=pid.value, process_started=process.create_time(), executable=process.exe(),
                title=title.value, window_class=cls.value, width=rect.right, height=rect.bottom)


def foreground_profile():
    return describe(U.GetForegroundWindow())


def gui_scale(actual):
    """Minecraft 1.8.9's automatic GUI scale for a live client rectangle."""
    scale = 1
    while (actual["width"] // (scale + 1) >= 320 and
           actual["height"] // (scale + 1) >= 240):
        scale += 1
    return scale


def standard_reconnect_points(identity):
    """Centres of stock 1.8.9 reconnect controls, independent of desktop DPI."""
    actual = describe(identity["hwnd"])
    scale = gui_scale(actual)
    width, height = actual["width"] // scale, actual["height"] // scale
    point = lambda x, y: dict(x=x * scale, y=y * scale,
                               width=actual["width"], height=actual["height"])
    return dict(back=point(width // 2, height // 2 + 26),
                server=point(width // 2, 50))


class Controller:
    def __init__(self, identity):
        self.identity = identity
        self.hwnd = identity["hwnd"]
        self.held = set()

    def verify(self, focus=True):
        if U.GetAsyncKeyState(0x77) & 0x8000:  # F8
            raise KeyboardInterrupt("F8 emergency stop")
        actual = describe(self.hwnd)
        for name in ("pid", "process_started", "executable"):
            if actual[name] != self.identity[name]:
                raise RuntimeError("Client identity changed. Register the client again.")
        if focus and (U.GetForegroundWindow() != self.hwnd or U.IsIconic(self.hwnd)):
            raise RuntimeError("Minecraft lost focus. Input released and trial excluded.")
        return actual

    def focus(self):
        self.verify(False)
        if U.IsIconic(self.hwnd):
            U.ShowWindow(self.hwnd, 9)
        U.SetForegroundWindow(self.hwnd)
        time.sleep(.4)
        self.verify()

    def send(self, event):
        if U.SendInput(1, C.byref(event), C.sizeof(INPUT)) != 1:
            raise RuntimeError("Windows rejected input. Run Minecraft and collector at the same privilege level.")

    def key(self, name, down):
        event = INPUT(type=1)
        event.ki = KEY(0, SCANS[name], 0x8 | (0 if down else 0x2), 0, 0)
        self.send(event)

    def keys(self, desired):
        self.verify()
        for name in list(self.held - desired):
            self.key(name, False)
            self.held.discard(name)
        for name in sorted(desired - self.held):
            # Track each key before sending so partial injection failure still
            # releases every possibly pressed key in the caller's finally block.
            self.held.add(name)
            self.key(name, True)

    def release(self):
        # Key-up is sent even if focus was lost, so modifiers cannot remain latched.
        for name in list(self.held):
            try:
                self.key(name, False)
            except (OSError, RuntimeError):
                pass
        self.held.clear()

    def tap(self, name):
        self.verify()
        self.held.add(name)
        try:
            self.key(name, True)
            time.sleep(.15)
        finally:
            self.key(name, False)
            self.held.discard(name)

    def mouse(self, dx, dy=0):
        self.verify()
        event = INPUT(type=0); event.mi = MOUSE(int(dx), int(dy), 0, 1, 0, 0)
        self.send(event)

    def click(self, point, twice=False):
        actual = self.verify()
        if actual["width"] != point["width"] or actual["height"] != point["height"]:
            raise RuntimeError("Window dimensions changed. Recalibrate the connection buttons.")
        p = W.POINT(point["x"], point["y"]); U.ClientToScreen(self.hwnd, C.byref(p))
        U.SetCursorPos(p.x, p.y)
        for _ in range(2 if twice else 1):
            for flag in (2, 4):
                e = INPUT(type=0); e.mi = MOUSE(0, 0, 0, flag, 0, 0); self.send(e); time.sleep(.035)
            time.sleep(.06)

    def join_server(self):
        """Click the standard 1.8.9 Multiplayer screen's Join Server button.

        Minecraft lays this screen out in scaled GUI coordinates. Deriving the
        scale from the live client rectangle keeps this independent of the
        desktop DPI and of the other client's window position.
        """
        actual = self.verify()
        scale = gui_scale(actual)
        gui_width, gui_height = actual["width"] // scale, actual["height"] // scale
        # GuiMultiplayer: x = width/2 - 154, y = height - 52, 100 x 20.
        point = dict(x=(gui_width // 2 - 104) * scale,
                     y=(gui_height - 42) * scale,
                     width=actual["width"], height=actual["height"])
        self.click(point)

    def screenshot(self, path):
        from PIL import ImageGrab
        actual = self.verify(); origin = W.POINT(0, 0); U.ClientToScreen(self.hwnd, C.byref(origin))
        ImageGrab.grab(bbox=(origin.x, origin.y, origin.x + actual["width"], origin.y + actual["height"]), all_screens=True).save(path)


def capture_point(identity, instruction):
    print(instruction + " Position the mouse and press F6. F8 cancels.", flush=True)
    c = Controller(identity)
    while True:
        if U.GetAsyncKeyState(0x77) & 0x8000:
            raise KeyboardInterrupt()
        if U.GetAsyncKeyState(0x75) & 0x8000:
            actual = c.verify(); p = W.POINT(); U.GetCursorPos(C.byref(p)); U.ScreenToClient(c.hwnd, C.byref(p))
            if not (0 <= p.x < actual["width"] and 0 <= p.y < actual["height"]):
                raise RuntimeError("Point must be inside the Minecraft window.")
            while U.GetAsyncKeyState(0x75) & 0x8000:
                time.sleep(.05)
            return dict(x=p.x, y=p.y, width=actual["width"], height=actual["height"])
        time.sleep(.05)
