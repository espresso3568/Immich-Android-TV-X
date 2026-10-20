"""Local mouse-operated remote for the project's Android TV emulator."""
import argparse
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path
from queue import Empty, Queue
import re
import shlex
import subprocess


KEYS = {"up": 19, "down": 20, "left": 21, "right": 22, "ok": 23,
        "back": 4, "home": 3, "play_pause": 85}


class EmulatorRemote:
    def __init__(self, adb, serial, avd, user=0):
        self.adb, self.serial, self.avd = adb, serial, avd
        self.user = user

    def command(self, *arguments):
        result = subprocess.run(
            [self.adb, "-s", self.serial, *arguments], capture_output=True,
            text=True, encoding="utf-8", errors="replace", timeout=12,
            creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0),
        )
        if result.returncode:
            raise RuntimeError(result.stderr.strip() or result.stdout.strip() or "ADB failed")
        return result.stdout

    def require_emulator(self):
        # Never send input to a phone, a different emulator, or an isolated QA profile.
        if self.avd not in self.command("emu", "avd", "name").splitlines():
            raise RuntimeError("The selected device is not the Immich TV Plus test emulator.")
        if self.command("shell", "am", "get-current-user").strip() != str(self.user):
            raise RuntimeError("The expected emulator user is not active.")

    def send(self, action):
        self.require_emulator()
        if action == "open_app":
            return self.command("shell", "am", "start", "-n",
                                "com.jerry.immichtv.plus/nl.giejay.android.tv.immich.MainActivity")
        return self.command("shell", "input", "keyevent", str(KEYS[action]))

    def require_text_editor(self):
        self.require_emulator()
        windows = self.command("shell", "dumpsys", "window")
        if not any("mCurrentFocus=" in line and "com.jerry.immichtv.plus/" in line
                   for line in windows.splitlines()):
            raise RuntimeError("Open a text field in Immich TV Plus first.")
        ime = self.command("shell", "dumpsys", "input_method")
        served = re.search(r"(?m)^\s*mServedView=([^\r\n]+)", ime)
        if not served or "GuidedActionEditText" not in served.group(1):
            raise RuntimeError("Select the text field and press OK first.")

    def paste_text(self, text):
        if (not text or len(text) > 4096 or not text.isascii()
                or any(ord(c) < 32 or ord(c) > 126 for c in text) or "%s" in text):
            raise ValueError("Copy only the API key or an ASCII server URL.")
        self.require_text_editor()
        # Replace the field rather than appending to an old URL or API key.
        self.command("shell", "input", "keycombination", "113", "29")  # Ctrl+A
        # Keep clipboard contents out of process arguments, logs, files and error messages.
        # POSIX quoting keeps shell metacharacters literal; -T prevents terminal echo.
        result = subprocess.run(
            [self.adb, "-s", self.serial, "shell", "-T", "sh"],
            input=("input text " + shlex.quote(text) + "\n").encode("ascii"),
            capture_output=True, timeout=30,
            creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0),
        )
        if result.returncode:
            raise RuntimeError("Text was not pasted. Check the selected field.")


def show_remote(remote):
    import tkinter as tk
    from tkinter import ttk

    root = tk.Tk()
    root.title("Immich TV Plus - PC Remote")
    panel_x = max(10, min(1140, root.winfo_screenwidth() - 370))
    panel_y = max(10, min(130, root.winfo_screenheight() - 540))
    root.geometry(f"350x490+{panel_x}+{panel_y}")
    root.resizable(False, False)
    root.attributes("-topmost", True)
    style = ttk.Style(root)
    style.configure("Remote.TButton", font=("Microsoft JhengHei UI", 13), padding=9)
    status = tk.StringVar(value="準備就緒，請點下方按鍵")
    ttk.Label(root, text="電視遙控器", font=("Microsoft JhengHei UI", 16)).pack(pady=(14, 4))
    ttk.Label(root, text="用滑鼠點按，不必點電視畫面", font=("Microsoft JhengHei UI", 10)).pack()
    pad = ttk.Frame(root)
    pad.pack(pady=12)
    results = Queue()
    worker = ThreadPoolExecutor(max_workers=1)
    pending = 0

    def deliver(action, label):
        try:
            remote.send(action)
            results.put((True, label))
        except Exception:
            results.put((False, "未送達，請確認電視視窗仍開啟"))

    def deliver_text(text):
        try:
            remote.paste_text(text)
            results.put((True, "取代並貼上文字"))
        except Exception:
            results.put((False, "貼上未完成，請先選文字欄位並按「確認」"))

    def paste_clipboard():
        nonlocal pending
        if pending:
            status.set("請等待上一個按鍵完成，再貼上")
            return
        try:
            text = root.clipboard_get().strip()
        except tk.TclError:
            status.set("剪貼簿沒有文字，請先複製 API Key")
            return
        if (not text or len(text) > 4096 or not text.isascii()
                or any(ord(c) < 32 or ord(c) > 126 for c in text) or "%s" in text):
            status.set("請只複製 API Key 或英文伺服器網址，再貼上")
            return
        pending += 1
        status.set("正在取代並貼上文字…")
        worker.submit(deliver_text, text)

    def send(action, label):
        nonlocal pending
        if pending >= 4:
            return
        pending += 1
        status.set("正在送出：" + label)
        worker.submit(deliver, action, label)

    def receive():
        nonlocal pending
        try:
            while True:
                success, message = results.get_nowait()
                pending -= 1
                status.set("已送出：" + message if success else message)
        except Empty:
            pass
        root.after(100, receive)

    for action, label, row, column in [
        ("up", "上 ▲", 0, 1), ("left", "左 ◀", 1, 0),
        ("ok", "確認", 1, 1), ("right", "右 ▶", 1, 2), ("down", "下 ▼", 2, 1),
    ]:
        ttk.Button(pad, text=label, width=6, style="Remote.TButton",
                   command=lambda a=action, t=label: send(a, t)).grid(row=row, column=column, padx=3, pady=3)
    extra = ttk.Frame(root)
    extra.pack()
    for action, label in [("back", "返回"), ("home", "首頁"), ("play_pause", "播放/暫停")]:
        ttk.Button(extra, text=label, width=8, command=lambda a=action, t=label: send(a, t)).pack(side="left", padx=3, pady=8)
    ttk.Button(root, text="開啟 Immich TV Plus", command=lambda: send("open_app", "開啟 App")).pack(pady=4)
    ttk.Button(root, text="取代並貼上剪貼簿文字", command=paste_clipboard).pack(pady=4)
    ttk.Label(root, text="先選 API Key 欄並按確認，再按貼上。", font=("Microsoft JhengHei UI", 9)).pack()
    ttk.Label(root, textvariable=status, wraplength=320).pack(pady=8)
    ttk.Label(root, text="方向鍵／Enter／Esc；Ctrl+V 取代貼上。", font=("Microsoft JhengHei UI", 9)).pack()
    root.bind_class("TVRemoteKeys", "<Control-v>", lambda e: (paste_clipboard(), "break")[1])
    for event, action in {"<Up>": "up", "<Down>": "down", "<Left>": "left",
                          "<Right>": "right", "<Return>": "ok", "<Escape>": "back"}.items():
        root.bind_class("TVRemoteKeys", event, lambda e, a=action: (send(a, a), "break")[1])

    def bind_remote_keys(widget):
        # Handle keys before a focused button's class binding to avoid double sends.
        widget.bindtags(("TVRemoteKeys", *widget.bindtags()))
        for child in widget.winfo_children():
            bind_remote_keys(child)

    bind_remote_keys(root)

    def close():
        worker.shutdown(wait=False, cancel_futures=True)
        root.destroy()

    root.protocol("WM_DELETE_WINDOW", close)
    root.after(100, receive)
    root.mainloop()


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--adb", required=True)
    parser.add_argument("--serial", default="emulator-5554")
    parser.add_argument("--avd", default="ImmichTVPlus-API36")
    parser.add_argument("--send", choices=[*KEYS, "open_app"])
    options = parser.parse_args()
    if not Path(options.adb).is_file() or not options.serial.startswith("emulator-"):
        parser.error("An existing ADB executable and an emulator serial are required.")
    remote = EmulatorRemote(options.adb, options.serial, options.avd)
    if options.send:
        remote.send(options.send)
        print("Sent: " + options.send)
    else:
        show_remote(remote)


if __name__ == "__main__":
    main()
