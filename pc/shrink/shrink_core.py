"""KueVault Shrink: the work itself, with no window. Version 1.

Makes films smaller before they go into KueVault, on the PC, by re-making the picture in H.265 (HEVC):
about half the space of the older H.264 for the same look, and it plays on phones, Fire TV and KueTV.
Sound, subtitles, chapters and fonts are copied across untouched.

A smaller copy only takes the old file's place once it has been checked: the right length, the same
sound and subtitle tracks, three parts of it played through with no faults, and clearly smaller. The old
file then goes to the Recycle Bin, or into a "Before Shrink" folder, as the person chose: never deleted.
"""

from __future__ import annotations

import json
import os
import re
import shutil
import subprocess
import sys
import time
from dataclasses import dataclass, field
from pathlib import Path
from typing import Callable, Optional

VERSION = "1"

FILMS = {".mp4", ".m4v", ".mkv", ".avi", ".mov", ".wmv", ".mpg", ".mpeg", ".ts", ".m2ts", ".webm", ".flv", ".divx", ".3gp"}
# Kinds of picture that are already as small as H.265 makes them (or smaller): left alone.
ALREADY_SMALL = {"hevc", "av1", "vp9", "vvc"}
# Sound an MP4 can hold as it is. Anything else (DTS, TrueHD, PCM...) means the copy is made as MKV instead.
MP4_SOUND = {"aac", "ac3", "eac3", "mp3", "alac", "opus", "flac"}
# Subtitles an MKV can hold. MP4's own kind (mov_text) is turned into SRT; any other kind (TV teletext) is left out.
MKV_SUBS = {"subrip", "srt", "ass", "ssa", "hdmv_pgs_subtitle", "dvd_subtitle", "dvb_subtitle", "webvtt", "mov_text"}
# A copy is kept only when it is at most this part of the old file's size.
KEEP_IF_AT_MOST = 0.90
BEFORE = "Before Shrink"
PART = ".kueshrink-part"

# Windows: the work runs below normal priority so the PC stays usable, with no black windows popping up.
_FLAGS = (0x08000000 | 0x00004000) if os.name == "nt" else 0


def tool(name: str) -> str:
    """ffmpeg or ffprobe: the copy packed with Shrink, else one on the PC."""
    here = Path(getattr(sys, "_MEIPASS", Path(__file__).parent))
    for p in (here / "ffmpeg" / (name + ".exe"), here / (name + ".exe"), here / "ffmpeg" / name):
        if p.exists():
            return str(p)
    return shutil.which(name) or name


def run(args: list[str], timeout: Optional[float] = None) -> subprocess.CompletedProcess:
    return subprocess.run(args, capture_output=True, text=True, encoding="utf-8", errors="replace", timeout=timeout, creationflags=_FLAGS)


def probe(path: Path) -> dict:
    r = run([tool("ffprobe"), "-v", "error", "-print_format", "json", "-show_format", "-show_streams", str(path)], timeout=120)
    if r.returncode != 0:
        raise ValueError("it couldn't be read: " + (r.stderr.strip().splitlines() or ["unknown fault"])[-1])
    return json.loads(r.stdout)


def _rate(s: str) -> float:
    try:
        a, b = s.split("/")
        return float(a) / float(b) if float(b) else 0.0
    except (ValueError, AttributeError):
        return 0.0


@dataclass
class Plan:
    """What will be done to one film, or why it is left alone ([skip] not empty)."""
    path: Path
    size: int
    duration: float = 0.0
    skip: str = ""
    video_index: int = -1
    ten_bit: bool = False
    container: str = "mkv"
    audio: int = 0
    subtitles: int = 0
    audio_at: list = field(default_factory=list)
    subs_at: list = field(default_factory=list)
    subs_to_srt: bool = False
    width: int = 0
    height: int = 0
    codec: str = ""

    @property
    def out_ext(self) -> str:
        return "." + self.container


def plan(path: Path, info: dict, size: int) -> Plan:
    """Decides what to do with one film from what ffprobe said about it."""
    p = Plan(path, size)
    streams = info.get("streams", [])
    p.duration = float(info.get("format", {}).get("duration") or 0)
    videos = [s for s in streams if s.get("codec_type") == "video" and not s.get("disposition", {}).get("attached_pic")]
    if not videos:
        p.skip = "no picture in it"
        return p
    v = videos[0]
    p.video_index = int(v["index"])
    p.codec = v.get("codec_name", "")
    p.width, p.height = int(v.get("width") or 0), int(v.get("height") or 0)
    p.ten_bit = bool(re.search(r"(10|12)(le|be)?$", v.get("pix_fmt", "")))
    audio = [s for s in streams if s.get("codec_type") == "audio"]
    subs = [s for s in streams if s.get("codec_type") == "subtitle"]
    p.audio, p.subtitles = len(audio), len(subs)
    if p.codec in ALREADY_SMALL:
        p.skip = "already in a small kind (" + p.codec.upper() + ")"
        return p
    if p.duration < 30:
        p.skip = "shorter than 30 seconds"
        return p
    fps = _rate(v.get("avg_frame_rate", "")) or _rate(v.get("r_frame_rate", "")) or 25.0
    pixels = p.width * p.height * fps
    # How many bits the picture already uses per pixel. Below this it is already squeezed hard, and
    # squeezing again would cost too much of the look.
    bits = float(v.get("bit_rate") or 0) or (size * 8 / p.duration if p.duration else 0)
    if pixels and bits / pixels < 0.035:
        p.skip = "already compact"
        return p
    mp4_ok = path.suffix.lower() in {".mp4", ".m4v", ".mov"} and all(s.get("codec_name") in MP4_SOUND for s in audio) \
        and all(s.get("codec_name") == "mov_text" for s in subs)
    p.container = "mp4" if mp4_ok else "mkv"
    p.audio_at = [int(s["index"]) for s in audio]
    kept = subs if p.container == "mp4" else [s for s in subs if s.get("codec_name") in MKV_SUBS]
    p.subs_at = [int(s["index"]) for s in kept]
    p.subtitles = len(kept)
    p.subs_to_srt = p.container == "mkv" and any(s.get("codec_name") == "mov_text" for s in kept)
    return p


# The two looks: "same" is meant to look no different; "smaller" gives up a little for a smaller file.
QUALITY = {"same": {"crf": 22, "cq": 24}, "smaller": {"crf": 26, "cq": 28}}
HARDWARE = ["hevc_nvenc", "hevc_qsv", "hevc_amf"]


def hardware() -> list[str]:
    """The graphics card's H.265 makers that actually work on this PC (tried on a tiny made-up picture)."""
    found = []
    for enc in HARDWARE:
        r = run([tool("ffmpeg"), "-v", "error", "-f", "lavfi", "-i", "color=c=black:s=256x256:d=0.5", "-c:v", enc, "-f", "null", "-"], timeout=60)
        if r.returncode == 0:
            found.append(enc)
    return found


def command(p: Plan, out: Path, quality: str, encoder: str) -> list[str]:
    q = QUALITY[quality]
    args = [tool("ffmpeg"), "-hide_banner", "-nostdin", "-y", "-v", "error", "-progress", "pipe:1", "-nostats", "-i", str(p.path),
            "-map", "0:" + str(p.video_index)]
    for i in p.audio_at + p.subs_at:
        args += ["-map", "0:" + str(i)]
    if p.container == "mkv":
        args += ["-map", "0:t?"]
    args += ["-map_metadata", "0", "-map_chapters", "0", "-c", "copy"]
    if p.subs_to_srt:
        args += ["-c:s", "srt"]
    # 10-bit films (HDR among them) are made with x265, which keeps 10-bit; the graphics card only for 8-bit.
    if encoder == "libx265" or p.ten_bit:
        args += ["-c:v", "libx265", "-preset", "medium", "-crf", str(q["crf"]), "-pix_fmt", "yuv420p10le" if p.ten_bit else "yuv420p",
                 "-x265-params", "log-level=error"]
    elif encoder == "hevc_nvenc":
        args += ["-c:v", "hevc_nvenc", "-preset", "p5", "-rc", "vbr", "-cq", str(q["cq"]), "-b:v", "0", "-pix_fmt", "yuv420p"]
    elif encoder == "hevc_qsv":
        args += ["-c:v", "hevc_qsv", "-preset", "slower", "-global_quality", str(q["cq"]), "-pix_fmt", "nv12"]
    elif encoder == "hevc_amf":
        args += ["-c:v", "hevc_amf", "-quality", "quality", "-rc", "cqp", "-qp_i", str(q["cq"] - 2), "-qp_p", str(q["cq"]), "-pix_fmt", "yuv420p"]
    if p.container == "mp4":
        args += ["-tag:v", "hvc1", "-movflags", "+faststart"]
    args += ["-max_muxing_queue_size", "4096", "-f", "mp4" if p.container == "mp4" else "matroska", str(out)]
    return args


def check(p: Plan, out: Path) -> str:
    """Empty when the copy is sound: right length, same tracks, and three parts of it play with no faults."""
    try:
        info = probe(out)
    except ValueError as e:
        return "the copy " + str(e)
    streams = info.get("streams", [])
    v = [s for s in streams if s.get("codec_type") == "video"]
    if not v or v[0].get("codec_name") != "hevc":
        return "the copy has no H.265 picture"
    if sum(1 for s in streams if s.get("codec_type") == "audio") != p.audio:
        return "the copy's sound tracks don't match"
    if sum(1 for s in streams if s.get("codec_type") == "subtitle") != p.subtitles:
        return "the copy's subtitles don't match"
    d = float(info.get("format", {}).get("duration") or 0)
    if abs(d - p.duration) > max(2.0, p.duration * 0.005):
        return "the copy is %d seconds long, not %d" % (round(d), round(p.duration))
    for at in (0.0, max(0.0, p.duration / 2 - 3), max(0.0, p.duration - 8)):
        r = run([tool("ffmpeg"), "-v", "error", "-xerror", "-ss", "%.2f" % at, "-t", "5", "-i", str(out), "-f", "null", "-"], timeout=300)
        if r.returncode != 0 or r.stderr.strip():
            return "part of the copy doesn't play: " + (r.stderr.strip().splitlines() or ["fault"])[0][:120]
    return ""


@dataclass
class Result:
    path: Path
    state: str  # "shrunk", "skipped", "failed", "stopped"
    said: str = ""
    before: int = 0
    after: int = 0
    new_path: Optional[Path] = None


def free_space(folder: Path) -> int:
    return shutil.disk_usage(folder).free


def put_aside(old: Path, keep: str) -> None:
    """The old file goes where the person chose: the Recycle Bin, or the Before Shrink folder beside it."""
    if keep == "bin":
        from send2trash import send2trash
        send2trash(str(old))
        return
    folder = old.parent / BEFORE
    folder.mkdir(exist_ok=True)
    target = folder / old.name
    n = 2
    while target.exists():
        target = folder / (old.stem + " (" + str(n) + ")" + old.suffix)
        n += 1
    shutil.move(str(old), str(target))


def shrink(p: Plan, quality: str, encoder: str, keep: str,
           progress: Callable[[float], None] = lambda f: None, stop: Callable[[], bool] = lambda: False) -> Result:
    """Makes the smaller copy, checks it, and only then puts it in the old file's place."""
    if p.skip:
        return Result(p.path, "skipped", p.skip, p.size)
    folder = p.path.parent
    if free_space(folder) < p.size * 0.8 + 1_000_000_000:
        return Result(p.path, "failed", "not enough free space on that drive to make the copy", p.size)
    part = folder / (PART + "-" + p.path.stem[:80] + p.out_ext)
    try:
        proc = subprocess.Popen(command(p, part, quality, encoder), stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True,
                                encoding="utf-8", errors="replace", creationflags=_FLAGS)
        assert proc.stdout is not None
        for line in proc.stdout:
            if stop():
                proc.kill()
                proc.wait()
                return Result(p.path, "stopped", "stopped part way; the film is untouched", p.size)
            if line.startswith("out_time_us=") or line.startswith("out_time_ms="):
                try:
                    done = int(line.split("=", 1)[1]) / 1_000_000
                    if p.duration:
                        progress(min(1.0, done / p.duration))
                except ValueError:
                    pass
        err = proc.stderr.read() if proc.stderr else ""
        if proc.wait() != 0:
            return Result(p.path, "failed", "the copy couldn't be made: " + (err.strip().splitlines() or ["unknown fault"])[-1][:150], p.size)
        fault = check(p, part)
        if fault:
            return Result(p.path, "failed", fault + "; the film is untouched", p.size)
        after = part.stat().st_size
        if after > p.size * KEEP_IF_AT_MOST:
            return Result(p.path, "skipped", "the copy wasn't much smaller, so the film was left as it was", p.size, after)
        new = folder / (p.path.stem + p.out_ext)
        put_aside(p.path, keep)
        if new.exists():
            new = folder / (p.path.stem + " (shrunk)" + p.out_ext)
        os.replace(part, new)
        return Result(p.path, "shrunk", "", p.size, after, new)
    finally:
        if part.exists():
            try:
                part.unlink()
            except OSError:
                pass


def films_in(folder: Path, inside: bool) -> list[Path]:
    """Every film in [folder] (and the folders inside it when [inside]), leaving out Before Shrink and part-made copies."""
    walk = folder.rglob("*") if inside else folder.glob("*")
    out = []
    for f in walk:
        if not f.is_file() or f.suffix.lower() not in FILMS or f.name.startswith(PART):
            continue
        if BEFORE in f.relative_to(folder).parts:
            continue
        out.append(f)
    return sorted(out, key=lambda f: str(f).lower())


class Journal:
    """What has been done already, so a film is never shrunk twice and a stopped run picks up where it was."""

    def __init__(self, path: Path):
        self.path = path
        try:
            self.seen = json.loads(path.read_text(encoding="utf-8"))
        except (OSError, ValueError):
            self.seen = {}

    @staticmethod
    def key(f: Path) -> str:
        st = f.stat()
        return str(f.resolve()).lower() + "|" + str(st.st_size) + "|" + str(int(st.st_mtime))

    def done(self, f: Path) -> Optional[str]:
        return self.seen.get(self.key(f))

    def mark(self, f: Path, said: str) -> None:
        self.seen[self.key(f)] = said
        self.path.parent.mkdir(parents=True, exist_ok=True)
        self.path.write_text(json.dumps(self.seen, indent=0), encoding="utf-8")


def size(n: float) -> str:
    for unit in ("bytes", "KB", "MB", "GB", "TB"):
        if n < 1000 or unit == "TB":
            return ("%d %s" if unit == "bytes" else "%.1f %s") % (n, unit)
        n /= 1000
    return str(n)


def hours(seconds: float) -> str:
    seconds = int(seconds)
    if seconds < 60:
        return "under a minute"
    h, m = divmod(seconds // 60, 60)
    return (str(h) + " h " if h else "") + str(m) + " min"


def keep_awake(on: bool) -> None:
    """Windows: stops the PC going to sleep while films are being shrunk (the screen can still turn off)."""
    if os.name != "nt":
        return
    import ctypes
    ES_CONTINUOUS, ES_SYSTEM_REQUIRED = 0x80000000, 0x00000001
    ctypes.windll.kernel32.SetThreadExecutionState(ES_CONTINUOUS | (ES_SYSTEM_REQUIRED if on else 0))


def stamp() -> str:
    return time.strftime("%d %b %H:%M")
