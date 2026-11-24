"""KueVault Shrink: the window. Version 1.

Choose a folder of films, look at what can be made smaller, and start. It can run overnight: the PC
stays awake while it works, it runs gently so the PC is still usable, and it can be stopped at any
time with nothing lost. Films already done are remembered and never done twice.
"""

from __future__ import annotations

import json
import os
import queue
import sys
import threading
import time
import tkinter as tk
from pathlib import Path
from tkinter import filedialog, messagebox, ttk

import shrink_core as core

PAGE, PANEL, RAISED, ACCENT, INK, SOFT, WARN, GOOD, BLUE = "#0E1626", "#16213A", "#263A63", "#FFD23F", "#FFFFFF", "#B9C2D3", "#F28C38", "#6BD08A", "#5CCEFF"
FONT = "Segoe UI" if os.name == "nt" else "DejaVu Sans"

HOME = Path(os.environ.get("APPDATA") or Path.home() / ".config") / "KueVault Shrink"


def here(name: str) -> Path:
    return Path(getattr(sys, "_MEIPASS", Path(__file__).parent)) / name


class App:
    def __init__(self, root: tk.Tk):
        self.root = root
        self.q: "queue.Queue[tuple]" = queue.Queue()
        self.plans: list[core.Plan] = []
        self.rows: dict[str, str] = {}
        self.working = False
        self.stop_now = False
        self.stop_after = False
        self.saved = 0
        self.handled: set[str] = set()
        self.journal = core.Journal(HOME / "done.json")
        self.settings = self.load()
        self.gpu: list[str] = []

        root.title("KueVault Shrink")
        root.configure(bg=PAGE)
        root.geometry("900x680")
        root.minsize(760, 560)
        try:
            root.iconbitmap(str(here("kueshrink.ico")))
        except tk.TclError:
            pass
        self.style()
        self.build()
        root.protocol("WM_DELETE_WINDOW", self.close)
        threading.Thread(target=self.find_gpu, daemon=True).start()
        root.after(150, self.poll)

    # ------------------------------------------------------------------ Look and layout

    def style(self):
        s = ttk.Style()
        s.theme_use("clam")
        s.configure(".", background=PAGE, foreground=INK, font=(FONT, 10))
        s.configure("TFrame", background=PAGE)
        s.configure("Panel.TFrame", background=PANEL)
        s.configure("TLabel", background=PAGE, foreground=INK)
        s.configure("Panel.TLabel", background=PANEL, foreground=INK)
        s.configure("Soft.TLabel", background=PAGE, foreground=SOFT)
        s.configure("PanelSoft.TLabel", background=PANEL, foreground=SOFT)
        s.configure("Title.TLabel", background=PAGE, foreground=INK, font=(FONT, 18, "bold"))
        s.configure("Big.TLabel", background=PANEL, foreground=INK, font=(FONT, 12, "bold"))
        s.configure("TRadiobutton", background=PANEL, foreground=INK)
        s.map("TRadiobutton", background=[("active", PANEL)], foreground=[("disabled", "#5E6A80")])
        s.configure("TCheckbutton", background=PANEL, foreground=INK)
        s.map("TCheckbutton", background=[("active", PANEL)])
        s.configure("TButton", background=RAISED, foreground=INK, borderwidth=0, padding=(14, 8))
        s.map("TButton", background=[("active", "#33497A"), ("disabled", PANEL)], foreground=[("disabled", "#5E6A80")])
        s.configure("Main.TButton", background=ACCENT, foreground="#111111", font=(FONT, 10, "bold"))
        s.map("Main.TButton", background=[("active", "#FFE07A"), ("disabled", PANEL)], foreground=[("disabled", "#5E6A80")])
        s.configure("Treeview", background=PANEL, fieldbackground=PANEL, foreground=INK, rowheight=26, borderwidth=0)
        s.configure("Treeview.Heading", background=RAISED, foreground=INK, font=(FONT, 10, "bold"))
        s.map("Treeview", background=[("selected", RAISED)])
        s.configure("Horizontal.TProgressbar", background=ACCENT, troughcolor=RAISED, borderwidth=0)

    def build(self):
        top = ttk.Frame(self.root, padding=(20, 16, 20, 6))
        top.pack(fill="x")
        ttk.Label(top, text="KueVault Shrink", style="Title.TLabel").pack(anchor="w")
        ttk.Label(top, style="Soft.TLabel", wraplength=840, text=(
            "Makes films smaller before they go into KueVault: the picture is re-made in H.265, which takes about half the space "
            "of older films for the same look, and plays on phones, Fire TV and KueTV. Sound and subtitles are kept as they are. "
            "Each smaller copy is checked before it takes the film's place, and the old film is never deleted.")).pack(anchor="w", pady=(2, 0))

        box = ttk.Frame(self.root, style="Panel.TFrame", padding=14)
        box.pack(fill="x", padx=20, pady=8)
        box.columnconfigure(1, weight=1)

        ttk.Label(box, text="Films folder", style="PanelSoft.TLabel").grid(row=0, column=0, sticky="w")
        self.folder = tk.StringVar(value=self.settings.get("folder", ""))
        ttk.Label(box, textvariable=self.folder, style="Panel.TLabel").grid(row=0, column=1, sticky="w", padx=10)
        ttk.Button(box, text="Choose…", command=self.choose).grid(row=0, column=2, sticky="e")
        self.inside = tk.BooleanVar(value=self.settings.get("inside", True))
        ttk.Checkbutton(box, text="Include the folders inside it", variable=self.inside).grid(row=1, column=1, sticky="w", padx=10, pady=(2, 8))

        self.quality = tk.StringVar(value=self.settings.get("quality", "same"))
        ttk.Label(box, text="Look", style="PanelSoft.TLabel").grid(row=2, column=0, sticky="w")
        q = ttk.Frame(box, style="Panel.TFrame")
        q.grid(row=2, column=1, columnspan=2, sticky="w", padx=10)
        ttk.Radiobutton(q, text="Looks the same (recommended)", value="same", variable=self.quality).pack(side="left")
        ttk.Radiobutton(q, text="Smaller still, a little softer", value="smaller", variable=self.quality).pack(side="left", padx=14)

        self.speed = tk.StringVar(value=self.settings.get("speed", "cpu"))
        ttk.Label(box, text="Speed", style="PanelSoft.TLabel").grid(row=3, column=0, sticky="w", pady=(6, 0))
        sp = ttk.Frame(box, style="Panel.TFrame")
        sp.grid(row=3, column=1, columnspan=2, sticky="w", padx=10, pady=(6, 0))
        ttk.Radiobutton(sp, text="Smallest files (slower: a film can take an hour or more)", value="cpu", variable=self.speed).pack(side="left")
        self.gpu_button = ttk.Radiobutton(sp, text="Faster, using the graphics card (looking for one…)", value="gpu", variable=self.speed, state="disabled")
        self.gpu_button.pack(side="left", padx=14)

        self.keep = tk.StringVar(value=self.settings.get("keep", "bin"))
        ttk.Label(box, text="The old film", style="PanelSoft.TLabel").grid(row=4, column=0, sticky="w", pady=(6, 0))
        k = ttk.Frame(box, style="Panel.TFrame")
        k.grid(row=4, column=1, columnspan=2, sticky="w", padx=10, pady=(6, 0))
        ttk.Radiobutton(k, text="Goes to the Recycle Bin", value="bin", variable=self.keep).pack(side="left")
        ttk.Radiobutton(k, text="Goes into a \"Before Shrink\" folder beside it", value="keep", variable=self.keep).pack(side="left", padx=14)

        bar = ttk.Frame(self.root, padding=(20, 4))
        bar.pack(fill="x")
        self.look_button = ttk.Button(bar, text="Look at the films", command=self.look)
        self.look_button.pack(side="left")
        self.start_button = ttk.Button(bar, text="Start", style="Main.TButton", command=self.start, state="disabled")
        self.start_button.pack(side="left", padx=8)
        self.after_button = ttk.Button(bar, text="Stop after this film", command=self.stop_later, state="disabled")
        self.after_button.pack(side="left")
        self.now_button = ttk.Button(bar, text="Stop now", command=self.stop, state="disabled")
        self.now_button.pack(side="left", padx=8)

        table = ttk.Frame(self.root, padding=(20, 6))
        table.pack(fill="both", expand=True)
        self.tree = ttk.Treeview(table, columns=("size", "what"), show="tree headings", selectmode="none")
        self.tree.heading("#0", text="Film", anchor="w")
        self.tree.heading("size", text="Size", anchor="e")
        self.tree.heading("what", text="What happens", anchor="w")
        self.tree.column("#0", width=380, anchor="w")
        self.tree.column("size", width=90, anchor="e", stretch=False)
        self.tree.column("what", width=360, anchor="w")
        self.tree.tag_configure("skip", foreground=SOFT)
        self.tree.tag_configure("done", foreground=GOOD)
        self.tree.tag_configure("fault", foreground=WARN)
        self.tree.tag_configure("now", foreground=ACCENT)
        scroll = ttk.Scrollbar(table, orient="vertical", command=self.tree.yview)
        self.tree.configure(yscrollcommand=scroll.set)
        self.tree.pack(side="left", fill="both", expand=True)
        scroll.pack(side="right", fill="y")

        foot = ttk.Frame(self.root, style="Panel.TFrame", padding=(20, 10))
        foot.pack(fill="x", side="bottom")
        self.now = tk.StringVar(value="Choose your films folder, then Look at the films.")
        ttk.Label(foot, textvariable=self.now, style="Big.TLabel").pack(anchor="w")
        self.bar = ttk.Progressbar(foot, mode="determinate", maximum=1000)
        self.bar.pack(fill="x", pady=6)
        self.total = tk.StringVar(value="")
        ttk.Label(foot, textvariable=self.total, style="PanelSoft.TLabel").pack(anchor="w")

    # ------------------------------------------------------------------ Settings

    def load(self) -> dict:
        try:
            return json.loads((HOME / "settings.json").read_text(encoding="utf-8"))
        except (OSError, ValueError):
            return {}

    def save(self):
        HOME.mkdir(parents=True, exist_ok=True)
        (HOME / "settings.json").write_text(json.dumps({
            "folder": self.folder.get(), "inside": self.inside.get(), "quality": self.quality.get(), "speed": self.speed.get(), "keep": self.keep.get(),
        }), encoding="utf-8")

    def log(self, text: str):
        try:
            HOME.mkdir(parents=True, exist_ok=True)
            with open(HOME / "log.txt", "a", encoding="utf-8") as f:
                f.write(core.stamp() + "  " + text + "\n")
        except OSError:
            pass

    # ------------------------------------------------------------------ Doing

    def find_gpu(self):
        found = core.hardware()
        self.q.put(("gpu", found))

    def choose(self):
        f = filedialog.askdirectory(title="Choose the folder your films are in", initialdir=self.folder.get() or str(Path.home()))
        if f:
            self.folder.set(str(Path(f)))
            self.save()
            self.look()

    def look(self):
        folder = Path(self.folder.get())
        if not self.folder.get() or not folder.is_dir():
            messagebox.showinfo("KueVault Shrink", "Choose the folder your films are in first.")
            return
        self.save()
        self.tree.delete(*self.tree.get_children())
        self.rows.clear()
        self.plans = []
        self.start_button.state(["disabled"])
        self.look_button.state(["disabled"])
        self.now.set("Looking at the films…")
        threading.Thread(target=self.looking, args=(folder, self.inside.get()), daemon=True).start()

    def looking(self, folder: Path, inside: bool):
        films = core.films_in(folder, inside)
        for i, f in enumerate(films):
            self.q.put(("now", "Looking at the films… %d of %d" % (i + 1, len(films)), (i + 1) / max(1, len(films))))
            try:
                size = f.stat().st_size
                before = self.journal.done(f)
                if before:
                    p = core.Plan(f, size, skip="done before: " + before)
                else:
                    p = core.plan(f, core.probe(f), size)
            except (OSError, ValueError) as e:
                p = core.Plan(f, 0, skip=str(e))
            self.q.put(("planned", p, folder))
        self.q.put(("looked", len(films)))

    def start(self):
        todo = [p for p in self.plans if not p.skip]
        if not todo:
            return
        self.save()
        self.working, self.stop_now, self.stop_after = True, False, False
        for b in (self.start_button, self.look_button):
            b.state(["disabled"])
        for b in (self.after_button, self.now_button):
            b.state(["!disabled"])
        encoder = "libx265"
        if self.speed.get() == "gpu" and self.gpu:
            encoder = self.gpu[0]
        core.keep_awake(True)
        self.log("started: %d films, look %s, %s, old films to %s" % (len(todo), self.quality.get(), encoder, self.keep.get()))
        threading.Thread(target=self.working_on, args=(todo, self.quality.get(), encoder, self.keep.get()), daemon=True).start()

    def working_on(self, todo: list[core.Plan], quality: str, encoder: str, keep: str):
        for n, p in enumerate(todo):
            if self.stop_now or self.stop_after:
                break
            began = time.time()
            self.q.put(("row", str(p.path), "Shrinking…", "now"))

            def moved(f: float, p=p, n=n, began=began):
                gone = time.time() - began
                left = gone / f - gone if f > 0.01 else 0
                self.q.put(("now", "Film %d of %d · %s · %d%%%s" % (n + 1, len(todo), p.path.name, int(f * 100),
                            (" · about " + core.hours(left) + " left on this one") if f > 0.02 else ""), f))

            r = core.shrink(p, quality, encoder, keep, progress=moved, stop=lambda: self.stop_now)
            self.q.put(("result", r))
        self.q.put(("finished",))

    def stop_later(self):
        self.stop_after = True
        self.after_button.state(["disabled"])
        self.total.set("Stopping once this film is done.")

    def stop(self):
        self.stop_now = True
        self.after_button.state(["disabled"])
        self.now_button.state(["disabled"])

    def close(self):
        if self.working and not messagebox.askyesno("KueVault Shrink", "A film is being shrunk. Stop and close? The film is left untouched."):
            return
        self.stop_now = True
        core.keep_awake(False)
        self.root.after(300, self.root.destroy)

    # ------------------------------------------------------------------ The window catching up with the work

    def poll(self):
        try:
            while True:
                m = self.q.get_nowait()
                getattr(self, "on_" + m[0])(*m[1:])
        except queue.Empty:
            pass
        self.root.after(150, self.poll)

    def on_gpu(self, found: list[str]):
        self.gpu = found
        names = {"hevc_nvenc": "NVIDIA", "hevc_qsv": "Intel", "hevc_amf": "AMD"}
        if found:
            self.gpu_button.configure(text="Faster, using the " + names.get(found[0], "") + " graphics card (files a little bigger)", state="normal")
        else:
            self.gpu_button.configure(text="Faster, using the graphics card (none found on this PC)", state="disabled")
            self.speed.set("cpu")

    def on_now(self, text: str, fraction: float):
        self.now.set(text)
        self.bar["value"] = int(fraction * 1000)

    def on_planned(self, p: core.Plan, folder: Path):
        self.plans.append(p)
        try:
            name = str(p.path.relative_to(folder))
        except ValueError:
            name = p.path.name
        what = ("Left alone: " + p.skip) if p.skip else ("To shrink · %s · %dx%d → H.265 %s" % (p.codec.upper(), p.width, p.height, p.container.upper()))
        self.rows[str(p.path)] = self.tree.insert("", "end", text=name, values=(core.size(p.size), what), tags=("skip",) if p.skip else ())

    def on_looked(self, count: int):
        self.look_button.state(["!disabled"])
        todo = [p for p in self.plans if not p.skip]
        self.bar["value"] = 0
        if not count:
            self.now.set("No films in that folder.")
        elif not todo:
            self.now.set("Nothing to shrink: every film is already small or done.")
        else:
            total = sum(p.size for p in todo)
            self.now.set("%d of %d films can be made smaller (%s of films). Start when ready." % (len(todo), count, core.size(total)))
            self.total.set("Shrinking takes about as long as the film itself, or longer, for each one on the processor. It can run overnight.")
            self.start_button.state(["!disabled"])

    def on_row(self, path: str, what: str, tag: str):
        row = self.rows.get(path)
        if row:
            self.tree.set(row, "what", what)
            self.tree.item(row, tags=(tag,))
            self.tree.see(row)

    def on_result(self, r: core.Result):
        if r.state != "stopped":
            self.handled.add(str(r.path))
        if r.state == "shrunk":
            self.saved += r.before - r.after
            what = "Done: %s → %s (saved %s)" % (core.size(r.before), core.size(r.after), core.size(r.before - r.after))
            self.on_row(str(r.path), what, "done")
            self.journal.mark(r.new_path, "shrunk from " + core.size(r.before))
            self.log("shrunk " + str(r.path) + ": " + what)
        elif r.state == "skipped":
            self.on_row(str(r.path), "Left alone: " + r.said, "skip")
            if r.path.exists():
                self.journal.mark(r.path, "skipped: " + r.said)
            self.log("left " + str(r.path) + ": " + r.said)
        elif r.state == "stopped":
            self.on_row(str(r.path), "Stopped: " + r.said, "skip")
        else:
            self.on_row(str(r.path), "Not done: " + r.said, "fault")
            self.log("fault " + str(r.path) + ": " + r.said)
        self.total.set("Saved so far: " + core.size(self.saved))

    def on_finished(self):
        self.working = False
        core.keep_awake(False)
        self.bar["value"] = 0
        for b in (self.after_button, self.now_button):
            b.state(["disabled"])
        self.look_button.state(["!disabled"])
        for p in self.plans:
            if str(p.path) in self.handled and not p.skip:
                p.skip = "done"
        left = [p for p in self.plans if not p.skip]
        self.now.set(("Stopped. " if (self.stop_now or self.stop_after) else "Finished. ") + "Saved " + core.size(self.saved) + "." +
                     (" %d film%s still to do: Start again any time." % (len(left), "" if len(left) == 1 else "s") if left else ""))
        if left:
            self.start_button.state(["!disabled"])
        self.log("finished: saved " + core.size(self.saved))


def selftest() -> int:
    """Run by the build on Windows: the whole window, on a made-up film, start to finish (the old one to the Recycle Bin)."""
    import subprocess
    import tempfile
    global HOME
    d = Path(tempfile.mkdtemp(prefix="kueshrink-test-"))
    HOME = d / "settings"
    films = d / "films"
    films.mkdir()
    f = films / "Test film.mp4"
    subprocess.run([core.tool("ffmpeg"), "-v", "error", "-y", "-f", "lavfi", "-i", "testsrc2=s=640x360:r=25:d=40", "-f", "lavfi", "-i", "sine=d=40",
                    "-c:v", "libx264", "-preset", "ultrafast", "-crf", "8", "-c:a", "aac", "-shortest", str(f)], check=True, creationflags=core._FLAGS)
    before = f.stat().st_size
    root = tk.Tk()
    app = App(root)
    app.folder.set(str(films))
    app.keep.set("bin")
    app.speed.set("cpu")
    said = {"step": "look", "result": 1, "why": "didn't finish"}
    began = time.time()

    def tick():
        if time.time() - began > 600:
            said["why"] = "took over 10 minutes"
            root.destroy()
            return
        if said["step"] == "look":
            app.look()
            said["step"] = "looking"
        elif said["step"] == "looking" and app.plans and "Start" in app.now.get():
            app.start()
            said["step"] = "working"
        elif said["step"] == "working" and not app.working and app.handled:
            info = core.probe(f) if f.exists() else {}
            kinds = [s.get("codec_name") for s in info.get("streams", [])]
            after = f.stat().st_size if f.exists() else 0
            ok = kinds == ["hevc", "aac"] and 0 < after < before and not (films / core.BEFORE).exists()
            said["result"] = 0 if ok else 1
            said["why"] = "%s → %s, tracks %s, saved %s, said: %s" % (core.size(before), core.size(after), kinds, core.size(app.saved), app.now.get())
            root.destroy()
            return
        root.after(500, tick)

    root.after(1500, tick)
    root.mainloop()
    print(("SELFTEST PASSED: " if said["result"] == 0 else "SELFTEST FAILED: ") + said["why"])
    (Path.cwd() / "selftest.txt").write_text(said["why"] + "\n", encoding="utf-8")
    return said["result"]


def main():
    if "--selftest" in sys.argv:
        sys.exit(selftest())
    root = tk.Tk()
    App(root)
    root.mainloop()


if __name__ == "__main__":
    main()
