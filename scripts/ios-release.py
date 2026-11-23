"""Rilis ClipMI iOS ke repo publik clipmi-ios-releases dan perbarui source SideStore.

Pemakaian (dari Git Bash, di root repo ini):
    /d/dev/msys64/ucrt64/bin/python3.exe -I scripts/ios-release.py dist/ClipMI-ios.ipa "Catatan rilis"

Langkah:
1. Baca versi (CFBundleShortVersionString), build (CFBundleVersion), dan iOS minimum dari Info.plist di .ipa.
2. Buat GitHub Release `v<versi>-<build>` di AndreLimaiDev/clipmi-ios-releases dengan aset ClipMI-<versi>-<build>.ipa.
3. Tambahkan versi itu paling atas di source.json (SideStore menawarkan update bila versi/build teratas berbeda dari
   yang terpasang), salin ikon, lalu commit & push clone lokal repo rilis (D:/dev/clipmi-ios-releases).

Butuh gh CLI yang sudah login. Format source mengikuti AltStore ("Make a Source"); SideStore memakai format yang sama.
"""
import datetime
import json
import os
import plistlib
import shutil
import subprocess
import sys
import tempfile
import zipfile

REPO = "AndreLimaiDev/clipmi-ios-releases"
CLONE = "D:/dev/clipmi-ios-releases"
GH = "C:/Program Files/GitHub CLI/gh.exe"
ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
ICON = os.path.join(ROOT, "iosApp", "Assets.xcassets", "AppIcon.appiconset", "icon-1024.png")
RAW = f"https://raw.githubusercontent.com/{REPO}/main"

APP_DESCRIPTION = (
    "Clipping kajian dan ceramah menjadi klip vertikal 9:16: unduh dari YouTube atau galeri, transkrip, analisis AI, "
    "editor subtitle dan sampul, lalu render di iPhone. Semua data tersimpan di HP, tanpa server dan tanpa login."
)


def run(*args, cwd=None):
    print("+", " ".join(args))
    subprocess.run(args, cwd=cwd, check=True)


def info_plist(ipa):
    with zipfile.ZipFile(ipa) as z:
        name = next(n for n in z.namelist() if n.startswith("Payload/") and n.endswith(".app/Info.plist") and n.count("/") == 2)
        return plistlib.loads(z.read(name))


def privacy(plist):
    return {k: v for k, v in sorted(plist.items()) if k.endswith("UsageDescription")}


def main():
    # Konsol Windows bukan UTF-8; catatan rilis bisa berisi ⋯ atau huruf lain.
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    if len(sys.argv) < 2:
        sys.exit(__doc__)
    ipa = sys.argv[1]
    notes = sys.argv[2] if len(sys.argv) > 2 else ""
    plist = info_plist(ipa)
    version = plist["CFBundleShortVersionString"]
    build = plist["CFBundleVersion"]
    bundle = plist["CFBundleIdentifier"]
    tag = f"v{version}-{build}"
    asset = f"ClipMI-{version}-{build}.ipa"

    source_path = os.path.join(CLONE, "source.json")
    source = json.load(open(source_path, encoding="utf-8")) if os.path.exists(source_path) else None
    if source:
        for v in source["apps"][0]["versions"]:
            if v["version"] == version and v["buildVersion"] == build:
                sys.exit(f"Versi {version} ({build}) sudah dirilis. Naikkan versi/build dulu.")

    # 1. GitHub Release dengan .ipa.
    with tempfile.TemporaryDirectory() as tmp:
        copy = os.path.join(tmp, asset)
        shutil.copyfile(ipa, copy)
        run(GH, "release", "create", tag, copy, "-R", REPO, "--title", f"ClipMI iOS {version} ({build})", "--notes", notes or f"ClipMI iOS {version} ({build})")

    # 2. source.json.
    entry = {
        "version": version,
        "buildVersion": build,
        "date": datetime.date.today().isoformat(),
        "localizedDescription": notes,
        "downloadURL": f"https://github.com/{REPO}/releases/download/{tag}/{asset}",
        "size": os.path.getsize(ipa),
        "minOSVersion": plist.get("MinimumOSVersion", "16.0"),
    }
    if source is None:
        source = {
            "name": "ClipMI",
            "subtitle": "Rilis ClipMI untuk iPhone",
            "description": "Source resmi ClipMI iOS. Tambahkan di SideStore untuk memasang dan memperbarui ClipMI.",
            "iconURL": f"{RAW}/icon.png",
            "website": f"https://github.com/{REPO}",
            "tintColor": "#12A884",
            "apps": [{
                "name": "ClipMI",
                "bundleIdentifier": bundle,
                "developerName": "Andre Lima",
                "subtitle": "Clipping kajian jadi klip vertikal",
                "localizedDescription": APP_DESCRIPTION,
                "iconURL": f"{RAW}/icon.png",
                "tintColor": "#12A884",
                "category": "photo-video",
                "appPermissions": {"entitlements": [], "privacy": {}},
                "versions": [],
            }],
            "news": [],
        }
    app = source["apps"][0]
    app["bundleIdentifier"] = bundle
    app["appPermissions"]["privacy"] = privacy(plist)
    app["versions"].insert(0, entry)
    with open(source_path, "w", encoding="utf-8", newline="\n") as f:
        json.dump(source, f, ensure_ascii=False, indent=2)
        f.write("\n")
    shutil.copyfile(ICON, os.path.join(CLONE, "icon.png"))

    run("git", "add", "source.json", "icon.png", cwd=CLONE)
    run("git", "commit", "-q", "-m", f"ClipMI iOS {version} ({build})", cwd=CLONE)
    run("git", "push", "-q", "origin", "HEAD:main", cwd=CLONE)
    print(f"\nSelesai. Source SideStore: {RAW}/source.json")


if __name__ == "__main__":
    main()
