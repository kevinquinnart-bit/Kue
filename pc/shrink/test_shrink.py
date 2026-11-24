"""KueVault Shrink's checks, run with real ffmpeg on made-up films. Version 1."""

import subprocess
from pathlib import Path

import pytest

import shrink_core as core


def make(path: Path, extra_in: list, extra_out: list, seconds: int = 40):
    """A made-up film: a moving test picture with sound, squeezed only lightly (as an old H.264 rip is)."""
    args = [core.tool("ffmpeg"), "-v", "error", "-y", "-f", "lavfi", "-i", "testsrc2=s=640x360:r=25:d=%d" % seconds,
            "-f", "lavfi", "-i", "sine=f=440:d=%d" % seconds] + extra_in + ["-map", "0:v", "-map", "1:a"] + extra_out + [str(path)]
    subprocess.run(args, check=True)


@pytest.fixture
def folder(tmp_path):
    return tmp_path


def test_mp4_film_shrinks_and_old_one_kept_aside(folder):
    f = folder / "Old Film.mp4"
    make(f, [], ["-c:v", "libx264", "-preset", "ultrafast", "-crf", "8", "-c:a", "aac"])
    p = core.plan(f, core.probe(f), f.stat().st_size)
    assert p.skip == "", p.skip
    assert p.container == "mp4"
    r = core.shrink(p, "same", "libx265", "keep")
    assert r.state == "shrunk", r.said
    assert r.new_path == folder / "Old Film.mp4"
    assert r.after < r.before * 0.9
    assert (folder / core.BEFORE / "Old Film.mp4").exists()
    info = core.probe(r.new_path)
    assert [s["codec_name"] for s in info["streams"]] == ["hevc", "aac"]
    # The part-made copy never stays behind.
    assert not any(x.name.startswith(core.PART) for x in folder.iterdir())


def test_mkv_with_subtitles_and_ac3_keeps_every_track(folder):
    srt = folder / "subs.srt"
    srt.write_text("1\n00:00:01,000 --> 00:00:04,000\nHello\n\n2\n00:00:30,000 --> 00:00:33,000\nAgain\n", encoding="utf-8")
    f = folder / "With subs.mkv"
    make(f, ["-i", str(srt)], ["-map", "2:s", "-c:v", "libx264", "-preset", "ultrafast", "-crf", "8", "-c:a", "ac3", "-c:s", "srt"])
    p = core.plan(f, core.probe(f), f.stat().st_size)
    assert p.container == "mkv" and p.audio == 1 and p.subtitles == 1
    r = core.shrink(p, "same", "libx265", "keep")
    assert r.state == "shrunk", r.said
    kinds = [s["codec_name"] for s in core.probe(r.new_path)["streams"]]
    assert kinds == ["hevc", "ac3", "subrip"]


def test_mov_with_pcm_sound_becomes_mkv(folder):
    f = folder / "Camera.mov"
    make(f, [], ["-c:v", "libx264", "-preset", "ultrafast", "-crf", "8", "-c:a", "pcm_s16le"])
    p = core.plan(f, core.probe(f), f.stat().st_size)
    assert p.container == "mkv"
    r = core.shrink(p, "smaller", "libx265", "keep")
    assert r.state == "shrunk", r.said
    assert r.new_path.suffix == ".mkv"


def test_already_h265_is_left_alone(folder):
    f = folder / "New.mkv"
    make(f, [], ["-c:v", "libx265", "-preset", "ultrafast", "-x265-params", "log-level=error", "-c:a", "aac"], seconds=31)
    p = core.plan(f, core.probe(f), f.stat().st_size)
    assert p.skip.startswith("already in a small kind")
    assert core.shrink(p, "same", "libx265", "keep").state == "skipped"


def test_short_clip_and_compact_film_left_alone(folder):
    f = folder / "Clip.mp4"
    make(f, [], ["-c:v", "libx264", "-preset", "ultrafast", "-c:a", "aac"], seconds=10)
    assert core.plan(f, core.probe(f), f.stat().st_size).skip == "shorter than 30 seconds"
    g = folder / "Tiny.mp4"
    make(g, [], ["-c:v", "libx264", "-b:v", "60k", "-c:a", "aac"])
    assert core.plan(g, core.probe(g), g.stat().st_size).skip == "already compact"


def test_a_broken_copy_never_replaces_the_film(folder):
    f = folder / "Film.mp4"
    make(f, [], ["-c:v", "libx264", "-preset", "ultrafast", "-crf", "8", "-c:a", "aac"])
    p = core.plan(f, core.probe(f), f.stat().st_size)
    # Pretend the film is far longer than it is: the copy's length won't match, so the film must stay.
    p.duration = p.duration + 60
    r = core.shrink(p, "same", "libx265", "keep")
    assert r.state == "failed" and "untouched" in r.said
    assert f.exists() and not (folder / core.BEFORE).exists()


def test_stopping_leaves_the_film_untouched(folder):
    f = folder / "Film.mp4"
    make(f, [], ["-c:v", "libx264", "-preset", "ultrafast", "-crf", "8", "-c:a", "aac"])
    p = core.plan(f, core.probe(f), f.stat().st_size)
    r = core.shrink(p, "same", "libx265", "keep", stop=lambda: True)
    assert r.state == "stopped"
    assert f.exists() and not any(x.name.startswith(core.PART) for x in folder.iterdir())


def test_finding_films_and_the_journal(folder):
    (folder / "a.mkv").write_bytes(b"x")
    (folder / "notes.txt").write_bytes(b"x")
    (folder / core.BEFORE).mkdir()
    (folder / core.BEFORE / "old.mkv").write_bytes(b"x")
    (folder / "inside").mkdir()
    (folder / "inside" / "b.MP4").write_bytes(b"x")
    assert [f.name for f in core.films_in(folder, inside=False)] == ["a.mkv"]
    assert [f.name for f in core.films_in(folder, inside=True)] == ["a.mkv", "b.MP4"]
    j = core.Journal(folder / "j.json")
    j.mark(folder / "a.mkv", "skipped: already compact")
    assert core.Journal(folder / "j.json").done(folder / "a.mkv") == "skipped: already compact"
    assert j.done(folder / "inside" / "b.MP4") is None


def test_words():
    assert core.size(1_500_000_000) == "1.5 GB"
    assert core.hours(30) == "under a minute"
    assert core.hours(3 * 3600 + 5 * 60) == "3 h 5 min"
