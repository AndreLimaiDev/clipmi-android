import AVFoundation
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
