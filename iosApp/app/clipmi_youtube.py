"""Unduh video YouTube untuk ClipMI iOS: yt-dlp di dalam aplikasi (tanpa subprocess).

Tantangan JavaScript YouTube dipecahkan plugin yt-dlp-apple-webkit-jsi (WKWebView). Video H.264 <=1080p dan audio
m4a diunduh dalam satu ekstraksi (format dipisah koma, sama dengan ClipMI Android) sebagai source.stream.mp4 dan
source.stream.m4a; penggabungan dilakukan Swift (AVFoundation).

download(url, out_dir, progress_path, log_path) mengembalikan teks JSON.
"""
import json
import os
import sys
import time
import traceback

VIDEO_FORMAT = "bv*[height<=1080][vcodec^=avc1]"
AUDIO_FORMAT = "ba[ext=m4a]"


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

        opts = {
            "verbose": True,
            "noplaylist": True,
            "updatetime": False,
            "cachedir": cache_dir,
            "paths": {"home": out_dir, "temp": out_dir},
            "outtmpl": "source.stream.%(ext)s",
            "format": f"{VIDEO_FORMAT},{AUDIO_FORMAT}",
            "progress_hooks": [hook],
        }
        with yt_dlp.YoutubeDL(opts) as ydl:
            info = ydl.extract_info(url, download=True)
        video = os.path.join(out_dir, "source.stream.mp4")
        audio = os.path.join(out_dir, "source.stream.m4a")
        ok = os.path.isfile(video) and os.path.isfile(audio)
        result = {
            "ok": ok,
            "title": info.get("title"),
            "duration": info.get("duration"),
            "video_id": info.get("id"),
            "video": video,
            "audio": audio,
            "seconds": round(time.time() - started, 1),
        }
        if not ok:
            result["error"] = "Video atau audio tidak terunduh."
        # Info untuk langkah berikutnya (judul, deskripsi, subtitle) seperti youtube.info.json di Android.
        with open(os.path.join(out_dir, "youtube.info.json"), "w", encoding="utf-8") as f:
            json.dump(ydl.sanitize_info(info), f, ensure_ascii=False)
    except BaseException as e:  # noqa: BLE001
        traceback.print_exc()
        result = {"ok": False, "error": f"{type(e).__name__}: {e}", "seconds": round(time.time() - started, 1)}
    print("== Hasil", json.dumps(result, ensure_ascii=False))
    sys.stdout = sys.__stdout__
    sys.stderr = sys.__stderr__
    log.close()
    return json.dumps(result, ensure_ascii=False)
