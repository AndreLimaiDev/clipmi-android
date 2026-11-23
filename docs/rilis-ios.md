# Rilis ClipMI iOS (SideStore)

ClipMI iOS dipasang dan diperbarui lewat **SideStore** dari source di repo publik
[AndreLimaiDev/clipmi-ios-releases](https://github.com/AndreLimaiDev/clipmi-ios-releases) (clone lokal
`D:\dev\clipmi-ios-releases`, commit memakai email noreply GitHub).

- Source: `https://raw.githubusercontent.com/AndreLimaiDev/clipmi-ios-releases/main/source.json`
- Format: AltStore "Make a Source" (SideStore memakai format yang sama). SideStore menawarkan update bila versi atau
  build teratas di `versions` berbeda dari yang terpasang.
- Nomor build = nomor run workflow `iOS app` (`CURRENT_PROJECT_VERSION`); versi (`MARKETING_VERSION`) di
  `iosApp/project.yml`, naikkan untuk rilis besar.

## Langkah rilis

1. Push ke branch iOS; tunggu workflow **iOS app** lolos.
2. Unduh .ipa dari run itu:

   ```
   gh run download <run-id> -n ClipMI-ios-ipa -D /d/dev/tmp/ipa
   ```

3. Terbitkan (GitHub Release + source.json + ikon, lalu push repo rilis):

   ```
   /d/dev/msys64/ucrt64/bin/python3.exe -I scripts/ios-release.py /d/dev/tmp/ipa/ClipMI-ios.ipa "Catatan rilis"
   ```

4. Cek: source terbuka, ikon tampil, dan sha256 .ipa dari tautan rilis sama dengan hasil CI.

Izin privasi (`NSPhotoLibraryAddUsageDescription`, dll.) dibaca otomatis dari Info.plist .ipa; aplikasi tidak memakai
entitlement khusus.
