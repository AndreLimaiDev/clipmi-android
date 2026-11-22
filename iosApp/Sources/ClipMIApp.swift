import AVFoundation
import Photos
import PhotosUI
import ComposeApp
import SwiftUI
import UIKit
import UniformTypeIdentifiers
import Vision

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
        Self.saveToPhotos(listener: listener) {
            _ = PHAssetChangeRequest.creationRequestForAssetFromVideo(atFileURL: URL(fileURLWithPath: path))
        }
    }

    func saveImageToPhotos(path: String, listener: SaveListener) {
        Self.saveToPhotos(listener: listener) {
            _ = PHAssetChangeRequest.creationRequestForAssetFromImage(atFileURL: URL(fileURLWithPath: path))
        }
    }

    /** Izin tambah ke Photos (bila belum), lalu [change]; [listener] di main thread. */
    static func saveToPhotos(listener: SaveListener, change: @escaping () -> Void) {
        PHPhotoLibrary.requestAuthorization(for: .addOnly) { status in
            guard status == .authorized || status == .limited else {
                DispatchQueue.main.async {
                    listener.finished(error: "Izin menyimpan ke Photos ditolak. Izinkan di Pengaturan › ClipMI › Foto.")
                }
                return
            }
            PHPhotoLibrary.shared().performChanges(change) { ok, error in
                DispatchQueue.main.async { listener.finished(error: ok ? nil : (error?.localizedDescription ?? "gagal")) }
            }
        }
    }

    // MARK: Gambar

    func videoFrame(path: String, seconds: Double, output: String, maxSize: Int32) -> Bool {
        let generator = AVAssetImageGenerator(asset: AVURLAsset(url: URL(fileURLWithPath: path)))
        generator.appliesPreferredTrackTransform = true
        generator.maximumSize = CGSize(width: Int(maxSize), height: Int(maxSize))
        let tolerance = CMTime(seconds: 0.5, preferredTimescale: 600)
        generator.requestedTimeToleranceBefore = tolerance
        generator.requestedTimeToleranceAfter = tolerance
        guard let image = try? generator.copyCGImage(at: CMTime(seconds: max(0, seconds), preferredTimescale: 600), actualTime: nil),
              let data = UIImage(cgImage: image).jpegData(compressionQuality: 0.85) else { return false }
        return (try? data.write(to: URL(fileURLWithPath: output))) != nil
    }

    private var picker: ImagePicker?

    func pickImage(output: String, listener: PickListener) {
        guard let top = Self.topController() else {
            listener.finished(path: nil)
            return
        }
        var configuration = PHPickerConfiguration()
        configuration.filter = .images
        configuration.selectionLimit = 1
        let controller = PHPickerViewController(configuration: configuration)
        let delegate = ImagePicker(output: output) { [weak self] path in
            self?.picker = nil
            listener.finished(path: path)
        }
        picker = delegate
        controller.delegate = delegate
        top.present(controller, animated: true)
    }

    // MARK: Pembicara (Vision)

    func detectFaces(imagePath: String) -> String {
        guard let image = UIImage(contentsOfFile: imagePath)?.cgImage else { return "[]" }
        let request = VNDetectFaceLandmarksRequest()
        let handler = VNImageRequestHandler(cgImage: image, options: [:])
        do { try handler.perform([request]) } catch { return "[]" }
        let size = CGSize(width: image.width, height: image.height)
        var faces: [[String: Any]] = []
        for face in request.results ?? [] {
            let box = face.boundingBox
            var item: [String: Any] = ["cx": Double(box.midX), "w": Double(box.width)]
            if let inner = face.landmarks?.innerLips?.pointsInImage(imageSize: size),
               let outer = face.landmarks?.outerLips?.pointsInImage(imageSize: size),
               let left = outer.map({ $0.x }).min(), let right = outer.map({ $0.x }).max(),
               let low = inner.map({ $0.y }).min(), let high = inner.map({ $0.y }).max(),
               right - left > 1 {
                item["open"] = Double((high - low) / (right - left))
            }
            faces.append(item)
        }
        guard let data = try? JSONSerialization.data(withJSONObject: faces),
              let text = String(data: data, encoding: .utf8) else { return "[]" }
        return text
    }

    func grayPixels(imagePath: String, output: String) -> String? {
        guard let image = UIImage(contentsOfFile: imagePath)?.cgImage else { return nil }
        let width = image.width
        let height = image.height
        var bytes = [UInt8](repeating: 0, count: width * height)
        let drawn: Bool = bytes.withUnsafeMutableBytes { buffer in
            guard let context = CGContext(
                data: buffer.baseAddress, width: width, height: height, bitsPerComponent: 8, bytesPerRow: width,
                space: CGColorSpaceCreateDeviceGray(), bitmapInfo: CGImageAlphaInfo.none.rawValue
            ) else { return false }
            context.draw(image, in: CGRect(x: 0, y: 0, width: width, height: height))
            return true
        }
        guard drawn, (try? Data(bytes).write(to: URL(fileURLWithPath: output))) != nil else { return nil }
        return "\(width)x\(height)"
    }

    // MARK: File

    func shareFile(path: String) {
        guard let top = Self.topController() else { return }
        let controller = UIActivityViewController(activityItems: [URL(fileURLWithPath: path)], applicationActivities: nil)
        controller.popoverPresentationController?.sourceView = top.view
        top.present(controller, animated: true)
    }

    private var documentPicker: FilePicker?

    func pickFile(output: String, listener: PickListener) {
        guard let top = Self.topController() else {
            listener.finished(path: nil)
            return
        }
        let controller = UIDocumentPickerViewController(forOpeningContentTypes: [.zip], asCopy: true)
        let delegate = FilePicker(output: output) { [weak self] path in
            self?.documentPicker = nil
            listener.finished(path: path)
        }
        documentPicker = delegate
        controller.delegate = delegate
        top.present(controller, animated: true)
    }

    static func topController() -> UIViewController? {
        let scenes = UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }
        let windows = scenes.flatMap { $0.windows }
        var top = (windows.first(where: { $0.isKeyWindow }) ?? windows.first)?.rootViewController
        while let presented = top?.presentedViewController { top = presented }
        return top
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

/// Satu file dari aplikasi Files (salinan sementara dari sistem), dipindah ke tujuan.
final class FilePicker: NSObject, UIDocumentPickerDelegate {
    private let output: String
    private let done: (String?) -> Void

    init(output: String, done: @escaping (String?) -> Void) {
        self.output = output
        self.done = done
    }

    func documentPicker(_ controller: UIDocumentPickerViewController, didPickDocumentsAt urls: [URL]) {
        guard let url = urls.first else {
            done(nil)
            return
        }
        let target = URL(fileURLWithPath: output)
        try? FileManager.default.removeItem(at: target)
        let ok = (try? FileManager.default.moveItem(at: url, to: target)) != nil
            || (try? FileManager.default.copyItem(at: url, to: target)) != nil
        done(ok ? output : nil)
    }

    func documentPickerWasCancelled(_ controller: UIDocumentPickerViewController) {
        done(nil)
    }
}

/// Satu gambar dari PHPicker, digambar ulang tegak (orientasi EXIF diterapkan; FFmpeg tidak membacanya) sebagai JPEG.
final class ImagePicker: NSObject, PHPickerViewControllerDelegate {
    private let output: String
    private let done: (String?) -> Void

    init(output: String, done: @escaping (String?) -> Void) {
        self.output = output
        self.done = done
    }

    func picker(_ picker: PHPickerViewController, didFinishPicking results: [PHPickerResult]) {
        picker.dismiss(animated: true)
        guard let provider = results.first?.itemProvider, provider.canLoadObject(ofClass: UIImage.self) else {
            done(nil)
            return
        }
        let output = self.output
        let done = self.done
        provider.loadObject(ofClass: UIImage.self) { object, _ in
            var path: String?
            if let image = object as? UIImage, let data = ImagePicker.upright(image).jpegData(compressionQuality: 0.92),
               (try? data.write(to: URL(fileURLWithPath: output))) != nil {
                path = output
            }
            DispatchQueue.main.async { done(path) }
        }
    }

    /** Tegak dan paling besar 2160 piksel di sisi terpanjang. */
    static func upright(_ image: UIImage) -> UIImage {
        let longest = max(image.size.width, image.size.height)
        let scale = longest > 2160 ? 2160 / longest : 1
        let size = CGSize(width: (image.size.width * scale).rounded(), height: (image.size.height * scale).rounded())
        let format = UIGraphicsImageRendererFormat()
        format.scale = 1
        format.opaque = true
        return UIGraphicsImageRenderer(size: size, format: format).image { _ in image.draw(in: CGRect(origin: .zero, size: size)) }
    }
}
