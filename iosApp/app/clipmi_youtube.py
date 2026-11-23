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
        _activate()
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


def stats(url, cache_dir, log_path):
    """Statistik publik satu postingan tanpa mengunduh video (sama dengan PostStats.ytDlpArgs Android).

    Mengembalikan teks JSON {"ok": true, "views", "likes", "comments", "shares"} (null bila tidak ditampilkan
    platform) atau {"ok": false, "error"}.
    """
    log = open(log_path, "a", buffering=1, encoding="utf-8")
    sys.stdout = log
    sys.stderr = log
    try:
        import certifi

        os.environ["SSL_CERT_FILE"] = certifi.where()
        _activate()
        import yt_dlp

        logger = _Logger()
        opts = {
            "logger": logger,
            "noplaylist": True,
            "skip_download": True,
            "cachedir": cache_dir,
        }
        try:
            with yt_dlp.YoutubeDL(opts) as ydl:
                info = ydl.extract_info(url, download=False) or {}
        except BaseException as e:  # noqa: BLE001
            info = None
            error = logger.last_error or f"{type(e).__name__}: {e}"

        def number(key):
            value = info.get(key) if info else None
            return value if isinstance(value, int) and value >= 0 else None

        if info is None:
            result = {"ok": False, "error": error}
        else:
            result = {
                "ok": True,
                "views": number("view_count"),
                "likes": number("like_count"),
                "comments": number("comment_count"),
                "shares": number("repost_count"),
            }
    except BaseException as e:  # noqa: BLE001
        traceback.print_exc()
        result = {"ok": False, "error": f"{type(e).__name__}: {e}"}
    print("== Statistik", url, json.dumps(result, ensure_ascii=False))
    sys.stdout = sys.__stdout__
    sys.stderr = sys.__stderr__
    log.close()
    return json.dumps(result, ensure_ascii=False)


# ---------------------------------------------------------------------------------------------------------------
# Pembaruan yt-dlp (padanan tombol Perbarui di Android): wheel terbaru dari PyPI disimpan di Documents/python-updates
# dan didahulukan dari paket bawaan aplikasi. Versi bawaan yang lebih baru (setelah aplikasi diperbarui) tetap menang.
# ---------------------------------------------------------------------------------------------------------------

UPDATES_DIR = os.path.join(os.path.expanduser("~"), "Documents", "python-updates")
# Nama paket PyPI -> nama awalan file wheel / dist-info.
_PACKAGES = {"yt-dlp": "yt_dlp", "yt-dlp-ejs": "yt_dlp_ejs"}


def _version_key(text):
    parts = []
    for piece in text.split("."):
        digits = "".join(ch for ch in piece if ch.isdigit())
        parts.append(int(digits) if digits else 0)
    return tuple(parts)


def _bundled_version(prefix):
    for path in sys.path:
        if path.endswith("app_packages") and os.path.isdir(path):
            for name in os.listdir(path):
                if name.startswith(prefix + "-") and name.endswith(".dist-info"):
                    return name[len(prefix) + 1:-len(".dist-info")]
    return None


def _downloaded(prefix):
    """(versi, path) wheel unduhan untuk paket ini, atau None."""
    if not os.path.isdir(UPDATES_DIR):
        return None
    best = None
    for name in os.listdir(UPDATES_DIR):
        if name.startswith(prefix + "-") and name.endswith(".whl"):
            version = name[len(prefix) + 1:].split("-")[0]
            if best is None or _version_key(version) > _version_key(best[0]):
                best = (version, os.path.join(UPDATES_DIR, name))
    return best


def _purge_modules():
    for name in list(sys.modules):
        if name == "yt_dlp" or name.startswith("yt_dlp"):
            del sys.modules[name]


def _activate():
    """Wheel unduhan yang lebih baru dari bawaan dipasang paling depan di sys.path. Mengembalikan versi yt-dlp aktif."""
    for prefix in _PACKAGES.values():
        sys.path[:] = [p for p in sys.path if not (p.startswith(UPDATES_DIR) and os.path.basename(p).startswith(prefix + "-"))]
        found = _downloaded(prefix)
        bundled = _bundled_version(prefix)
        if found and (bundled is None or _version_key(found[0]) > _version_key(bundled)):
            sys.path.insert(0, found[1])
    active = _downloaded("yt_dlp")
    bundled = _bundled_version("yt_dlp")
    if active and (bundled is None or _version_key(active[0]) > _version_key(bundled)):
        return active[0]
    return bundled


def _pypi(name, version=None):
    import urllib.request

    url = f"https://pypi.org/pypi/{name}/json" if version is None else f"https://pypi.org/pypi/{name}/{version}/json"
    with urllib.request.urlopen(url, timeout=30) as response:
        return json.loads(response.read().decode("utf-8"))


def _fetch_wheel(data, prefix):
    import hashlib
    import urllib.request

    wheel = next(
        (u for u in data["urls"] if u.get("packagetype") == "bdist_wheel" and u["filename"].endswith("py3-none-any.whl")),
        None,
    )
    if wheel is None:
        raise RuntimeError(f"Wheel {prefix} tidak ditemukan di PyPI.")
    os.makedirs(UPDATES_DIR, exist_ok=True)
    target = os.path.join(UPDATES_DIR, wheel["filename"])
    tmp = target + ".part"
    with urllib.request.urlopen(wheel["url"], timeout=120) as response, open(tmp, "wb") as f:
        while True:
            chunk = response.read(1 << 16)
            if not chunk:
                break
            f.write(chunk)
    digest = hashlib.sha256(open(tmp, "rb").read()).hexdigest()
    if digest != wheel["digests"]["sha256"]:
        os.remove(tmp)
        raise RuntimeError(f"Checksum {wheel['filename']} tidak cocok.")
    os.replace(tmp, target)
    # Wheel lama paket yang sama dihapus.
    for name in os.listdir(UPDATES_DIR):
        path = os.path.join(UPDATES_DIR, name)
        if name.startswith(prefix + "-") and name.endswith(".whl") and path != target:
            os.remove(path)


def version():
    """{"ok", "version" (aktif), "bundled", "updated" (memakai unduhan)}."""
    active = _activate()
    bundled = _bundled_version("yt_dlp")
    return json.dumps({"ok": True, "version": active, "bundled": bundled, "updated": active != bundled})


def update():
    """Unduh yt-dlp terbaru (dan yt-dlp-ejs yang dipasangkan dengannya). {"ok", "status", "version"} atau {"ok": false, "error"}."""
    try:
        import certifi

        os.environ["SSL_CERT_FILE"] = certifi.where()
        current = _activate()
        data = _pypi("yt-dlp")
        latest = data["info"]["version"]
        if current and _version_key(latest) <= _version_key(current):
            return json.dumps({"ok": True, "status": "up_to_date", "version": current})
        _fetch_wheel(data, "yt_dlp")
        # yt-dlp mematok versi yt-dlp-ejs (extra "default"); ambil versi yang sama persis.
        pinned = None
        for requirement in data["info"].get("requires_dist") or []:
            spec = requirement.split(";")[0].replace(" ", "")
            if spec.startswith("yt-dlp-ejs=="):
                pinned = spec.split("==", 1)[1]
        bundled_ejs = _bundled_version("yt_dlp_ejs")
        if pinned and (bundled_ejs is None or _version_key(pinned) > _version_key(bundled_ejs)):
            _fetch_wheel(_pypi("yt-dlp-ejs", pinned), "yt_dlp_ejs")
        _purge_modules()
        active = _activate()
        return json.dumps({"ok": True, "status": "updated", "version": active})
    except BaseException as e:  # noqa: BLE001
        return json.dumps({"ok": False, "error": f"{type(e).__name__}: {e}"})


def reset():
    """Hapus unduhan; kembali ke yt-dlp bawaan aplikasi."""
    import shutil

    shutil.rmtree(UPDATES_DIR, ignore_errors=True)
    _purge_modules()
    return json.dumps({"ok": True, "version": _activate()})
