import AVFoundation
import Photos
import ComposeApp
import SwiftUI
import UIKit

@main
struct ClipMIApp: App {
    var body: some Scene {
        WindowGroup {
            ComposeView().ignoresSafeArea()
        }
    }
}

/// Layar Compose Multiplatform (composeApp) di dalam SwiftUI.
struct ComposeView: UIViewControllerRepresentable {
    func makeUIViewController(context: Context) -> UIViewController {
        let documents = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0].path
        // Pratinjau editor bersuara walau tombol senyap aktif (seperti aplikasi video).
        try? AVAudioSession.sharedInstance().setCategory(.playback)
        return MainViewControllerKt.MainViewController(engine: IOSMediaEngine(), documents: documents)
    }

    func updateUIViewController(_ uiViewController: UIViewController, context: Context) {}
}

/// Bagian iOS yang tidak bisa ditulis di Kotlin common: yt-dlp lewat Python tertanam dan penggabungan AVFoundation.
final class IOSMediaEngine: NSObject, MediaEngine {
    func downloadYouTube(url: String, directory: String, listener: DownloadListener) {
        if let error = ClipPythonStart() {
            listener.finished(resultJson: Self.json(["ok": false, "error": "Python gagal menyala: \(error)"]))
            return
        }
        UIApplication.shared.isIdleTimerDisabled = true
        let progressPath = directory + "/progress.json"
        let logPath = directory + "/youtube.log"
        // Progres ditulis Python ke file; dibaca berkala di main thread.
        let timer = Timer.scheduledTimer(withTimeInterval: 0.5, repeats: true) { _ in
            guard let data = FileManager.default.contents(atPath: progressPath),
                  let object = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any],
                  let fraction = object["fraction"] as? Double else { return }
            listener.progress(fraction: fraction, status: object["status"] as? String ?? "")
        }
        // Stack besar: yt-dlp dan regex Python bisa rekursif dalam; stack bawaan thread latar hanya 512 KB.
        let thread = Thread {
            var result = ClipPythonCall("clipmi_youtube", "download", [url, directory, progressPath, logPath])
            if let data = result.data(using: .utf8),
               var object = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any],
               object["ok"] as? Bool == true,
               let video = object["video"] as? String,
               let audio = object["audio"] as? String {
                DispatchQueue.main.async { listener.progress(fraction: 0.97, status: "Menggabungkan video dan audio…") }
                let output = directory + "/source.mp4"
                if let error = Self.merge(video: video, audio: audio, output: output) {
                    object = ["ok": false, "error": "Gagal menggabungkan: \(error)"]
                } else {
                    try? FileManager.default.removeItem(atPath: video)
                    try? FileManager.default.removeItem(atPath: audio)
                    let size = ((try? FileManager.default.attributesOfItem(atPath: output))?[.size] as? NSNumber)?.doubleValue ?? 0
                    object["file"] = "source.mp4"
                    object["mb"] = (size / 1_048_576 * 10).rounded() / 10
                }
                result = Self.json(object)
            }
            DispatchQueue.main.async {
                timer.invalidate()
                UIApplication.shared.isIdleTimerDisabled = false
                listener.finished(resultJson: result)
            }
        }
        thread.stackSize = 16 * 1024 * 1024
        thread.start()
    }

    // MARK: Render

    func fontsDirectory() -> String {
        (Bundle.main.resourcePath ?? "") + "/fonts"
    }

    /// FreeType lewat text_measure_core.c (file yang sama dengan Android).
    func textWidth(fontPath: String, points: Double, text: String) -> Double {
        let bytes = Array(text.utf8)
        return bytes.withUnsafeBufferPointer { clipmi_text_width(fontPath, points, $0.baseAddress, $0.count) }
    }

    func probeVideo(path: String) -> String? {
        let asset = AVURLAsset(url: URL(fileURLWithPath: path))
        guard let track = asset.tracks(withMediaType: .video).first else { return nil }
        let size = track.naturalSize.applying(track.preferredTransform)
        return Self.json([
            "width": Int(abs(size.width).rounded()),
            "height": Int(abs(size.height).rounded()),
            "fps": Double(track.nominalFrameRate),
            "has_audio": !asset.tracks(withMediaType: .audio).isEmpty,
            "duration": CMTimeGetSeconds(asset.duration),
        ])
    }

    /// FFmpeg di dalam proses (ClipMIFFmpeg.xcframework). Progres dari `-progress progress.txt` (out_time_us).
    func runFfmpeg(args: [String], directory: String, durationSeconds: Double, listener: RenderListener) {
        UIApplication.shared.isIdleTimerDisabled = true
        let progressPath = directory + "/progress.txt"
        let logPath = directory + "/ffmpeg.log"
        try? FileManager.default.removeItem(atPath: progressPath)
        let timer = Timer.scheduledTimer(withTimeInterval: 0.5, repeats: true) { _ in
            guard durationSeconds > 0,
                  let text = try? String(contentsOfFile: progressPath, encoding: .utf8),
                  let line = text.split(separator: "\n").last(where: { $0.hasPrefix("out_time_us=") }),
                  let micros = Double(line.dropFirst("out_time_us=".count)) else { return }
            listener.progress(fraction: min(1, max(0, micros / 1_000_000 / durationSeconds)))
        }
        let full = ["-hide_banner", "-nostdin", "-nostats", "-progress", "progress.txt"] + args
        let thread = Thread {
            var cArgs: [UnsafePointer<CChar>?] = full.map { UnsafePointer(strdup($0)) }
            let code = cArgs.withUnsafeMutableBufferPointer { buffer in
                clipmi_ffmpeg_run(Int32(full.count), buffer.baseAddress, directory, logPath)
            }
            cArgs.forEach { free(UnsafeMutableRawPointer(mutating: $0)) }
            let log = (try? String(contentsOfFile: logPath, encoding: .utf8)) ?? ""
            let tail = log.split(separator: "\n").suffix(12).joined(separator: "\n")
            DispatchQueue.main.async {
                timer.invalidate()
                UIApplication.shared.isIdleTimerDisabled = false
                listener.finished(code: code, logTail: tail)
            }
        }
        thread.stackSize = 16 * 1024 * 1024
        thread.start()
    }

    func cancelFfmpeg() {
        clipmi_ffmpeg_cancel()
    }

    func saveVideoToPhotos(path: String, listener: SaveListener) {
        PHPhotoLibrary.requestAuthorization(for: .addOnly) { status in
            guard status == .authorized || status == .limited else {
                DispatchQueue.main.async {
                    listener.finished(error: "Izin menyimpan ke Photos ditolak. Izinkan di Pengaturan › ClipMI › Foto.")
                }
                return
            }
            PHPhotoLibrary.shared().performChanges({
                _ = PHAssetChangeRequest.creationRequestForAssetFromVideo(atFileURL: URL(fileURLWithPath: path))
            }) { ok, error in
                DispatchQueue.main.async { listener.finished(error: ok ? nil : (error?.localizedDescription ?? "gagal")) }
            }
        }
    }

    // MARK: libass (pratinjau)

    func assCreate(width: Int32, height: Int32) -> Int64 {
        guard let ctx = clipmi_ass_create(width, height, fontsDirectory(), nil) else { return 0 }
        return Int64(Int(bitPattern: ctx))
    }

    func assLoad(handle: Int64, ass: String) -> Bool {
        guard let ctx = OpaquePointer(bitPattern: Int(handle)) else { return false }
        let bytes = Array(ass.utf8)
        return bytes.withUnsafeBufferPointer { buffer in
            buffer.withMemoryRebound(to: CChar.self) { clipmi_ass_load(ctx, $0.baseAddress, $0.count) == 1 }
        }
    }

    func assRender(handle: Int64, timeMs: Int64, pixels: Int64, width: Int32, height: Int32, clear: Bool) -> Int32 {
        guard let ctx = OpaquePointer(bitPattern: Int(handle)),
              let buffer = UnsafeMutablePointer<UInt8>(bitPattern: Int(pixels)) else { return -1 }
        return clipmi_ass_render(ctx, timeMs, buffer, UInt32(width) * 4, width, height, clear ? 1 : 0, 1)
    }

    func assDestroy(handle: Int64) {
        clipmi_ass_destroy(OpaquePointer(bitPattern: Int(handle)))
    }

    // MARK: Gabung video

    /// Video H.264 dan audio AAC menjadi satu MP4 tanpa encode ulang (padanan MediaMuxer di Android). nil = berhasil.
    static func merge(video: String, audio: String, output: String) -> String? {
        let semaphore = DispatchSemaphore(value: 0)
        var failure: String?
        Task.detached {
            do {
                let videoAsset = AVURLAsset(url: URL(fileURLWithPath: video))
                let audioAsset = AVURLAsset(url: URL(fileURLWithPath: audio))
                guard let videoTrack = try await videoAsset.loadTracks(withMediaType: .video).first,
                      let audioTrack = try await audioAsset.loadTracks(withMediaType: .audio).first else {
                    failure = "track video atau audio tidak ditemukan"
                    semaphore.signal()
                    return
                }
                let duration = try await videoAsset.load(.duration)
                let audioDuration = try await audioAsset.load(.duration)
                let composition = AVMutableComposition()
                let compositionVideo = composition.addMutableTrack(withMediaType: .video, preferredTrackID: kCMPersistentTrackID_Invalid)
                let compositionAudio = composition.addMutableTrack(withMediaType: .audio, preferredTrackID: kCMPersistentTrackID_Invalid)
                try compositionVideo?.insertTimeRange(CMTimeRange(start: .zero, duration: duration), of: videoTrack, at: .zero)
                try compositionAudio?.insertTimeRange(CMTimeRange(start: .zero, duration: CMTimeMinimum(duration, audioDuration)), of: audioTrack, at: .zero)
                compositionVideo?.preferredTransform = try await videoTrack.load(.preferredTransform)
                try? FileManager.default.removeItem(atPath: output)
                guard let session = AVAssetExportSession(asset: composition, presetName: AVAssetExportPresetPassthrough) else {
                    failure = "ekspor tidak tersedia"
                    semaphore.signal()
                    return
                }
                session.outputURL = URL(fileURLWithPath: output)
                session.outputFileType = .mp4
                await withCheckedContinuation { (continuation: CheckedContinuation<Void, Never>) in
                    session.exportAsynchronously { continuation.resume() }
                }
                if session.status != .completed {
                    failure = session.error?.localizedDescription ?? "status \(session.status.rawValue)"
                }
            } catch {
                failure = error.localizedDescription
            }
            semaphore.signal()
        }
        semaphore.wait()
        return failure
    }

    static func json(_ object: [String: Any]) -> String {
        guard let data = try? JSONSerialization.data(withJSONObject: object),
              let text = String(data: data, encoding: .utf8) else { return "{\"ok\": false, \"error\": \"JSON\"}" }
        return text
    }
}
