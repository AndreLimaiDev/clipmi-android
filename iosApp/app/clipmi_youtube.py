"""Unduh video YouTube untuk ClipMI iOS: yt-dlp di dalam aplikasi (tanpa subprocess).

Tantangan JavaScript YouTube dipecahkan plugin yt-dlp-apple-webkit-jsi (WKWebView). Video H.264 <=1080p dan audio
m4a diunduh dalam satu ekstraksi (format dipisah koma, sama dengan ClipMI Android) sebagai source.stream.mp4 dan
source.stream.m4a; penggabungan dilakukan Swift (AVFoundation). Subtitle json3 Indonesia (youtube.{bahasa}.json3) dan
youtube.info.json ditulis dalam ekstraksi yang sama untuk transkrip.

download(url, out_dir, progress_path, log_path) mengembalikan teks JSON.
"""
import json
import os
import sys
import time
import traceback

VIDEO_FORMAT = "bv*[height<=1080][vcodec^=avc1]"
AUDIO_FORMAT = "ba[ext=m4a]"
# Sama dengan YtDlpArgs.SUB_LANGS (SubtitlePicker desktop).
SUB_LANGS = ["id", "id-orig"]


class _Logger:
    """Pesan yt-dlp ke log; error terakhir disimpan untuk pesan gagal (ignoreerrors tidak melempar exception)."""

    def __init__(self):
        self.last_error = None

    def debug(self, msg):
        print(msg)

    def info(self, msg):
        print(msg)

    def warning(self, msg):
        print(msg)

    def error(self, msg):
        print(msg)
        self.last_error = msg.replace("ERROR: ", "", 1)


def _write_progress(path, fraction, status):
    tmp = path + ".tmp"
    with open(tmp, "w", encoding="utf-8") as f:
        f.write(json.dumps({"fraction": round(fraction, 4), "status": status}))
    os.replace(tmp, path)


def download(url, out_dir, progress_path, log_path):
    log = open(log_path, "a", buffering=1, encoding="utf-8")
    sys.stdout = log
    sys.stderr = log
    started = time.time()
    try:
        import certifi

        os.environ["SSL_CERT_FILE"] = certifi.where()
        import yt_dlp

        print("yt-dlp", yt_dlp.version.__version__)
        os.makedirs(out_dir, exist_ok=True)
        cache_dir = os.path.join(os.path.dirname(os.path.dirname(out_dir)), "yt-dlp-cache")
        _write_progress(progress_path, 0.02, "Membaca info video…")

        # Video lalu audio: persen kembali ke 0 di stream kedua, jadi dipetakan ke 0-85% dan 85-100%.
        state = {"stream": 0, "last": 0.0}

        def hook(d):
            if d.get("status") == "downloading":
                total = d.get("total_bytes") or d.get("total_bytes_estimate") or 0
                pct = (d.get("downloaded_bytes") or 0) * 100.0 / total if total else 0.0
                if pct + 20 < state["last"]:
                    state["stream"] += 1
                state["last"] = pct
                overall = pct * 0.85 if state["stream"] == 0 else 85 + pct * 0.15
                label = "Mengunduh video" if state["stream"] == 0 else "Mengunduh audio"
                _write_progress(progress_path, 0.05 + overall / 100 * 0.9, f"{label} {pct:.0f}%")

        logger = _Logger()
        opts = {
            "verbose": True,
            "logger": logger,
            "noplaylist": True,
            "updatetime": False,
            "cachedir": cache_dir,
            "paths": {"home": out_dir, "temp": out_dir},
            # Subtitle json3 Indonesia ditulis sebelum video (youtube.{bahasa}.json3, sama dengan Android), dalam
            # ekstraksi yang sama, jadi tantangan JS YouTube cukup sekali.
            "outtmpl": {"default": "source.stream.%(ext)s", "subtitle": "youtube.%(ext)s"},
            "writesubtitles": True,
            "writeautomaticsub": True,
            "subtitleslangs": SUB_LANGS,
            "subtitlesformat": "json3",
            # Subtitle yang gagal diunduh (misalnya 429) tidak membatalkan video; hasil diperiksa dari file.
            "ignoreerrors": True,
            "format": f"{VIDEO_FORMAT},{AUDIO_FORMAT}",
            "progress_hooks": [hook],
        }
        for name in os.listdir(out_dir):
            if name.startswith("youtube.") and name.endswith(".json3"):
                os.remove(os.path.join(out_dir, name))
        with yt_dlp.YoutubeDL(opts) as ydl:
            info = ydl.extract_info(url, download=True)
            if info:
                # Info untuk judul, durasi, dan pemilihan subtitle (SubtitlePicker), seperti youtube.info.json di Android.
                with open(os.path.join(out_dir, "youtube.info.json"), "w", encoding="utf-8") as f:
                    json.dump(ydl.sanitize_info(info), f, ensure_ascii=False)
        video = os.path.join(out_dir, "source.stream.mp4")
        audio = os.path.join(out_dir, "source.stream.m4a")
        ok = bool(info) and os.path.isfile(video) and os.path.isfile(audio)
        info = info or {}
        result = {
            "ok": ok,
            "title": info.get("title"),
            "duration": info.get("duration"),
            "video_id": info.get("id"),
            "video": video,
            "audio": audio,
            "subtitles": sorted(n for n in os.listdir(out_dir) if n.endswith(".json3")),
            "seconds": round(time.time() - started, 1),
        }
        if not ok:
            result["error"] = logger.last_error or "Video atau audio tidak terunduh."
    except BaseException as e:  # noqa: BLE001
        traceback.print_exc()
        result = {"ok": False, "error": f"{type(e).__name__}: {e}", "seconds": round(time.time() - started, 1)}
    print("== Hasil", json.dumps(result, ensure_ascii=False))
    sys.stdout = sys.__stdout__
    sys.stderr = sys.__stderr__
    log.close()
    return json.dumps(result, ensure_ascii=False)
