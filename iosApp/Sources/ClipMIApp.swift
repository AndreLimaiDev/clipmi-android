import AVFoundation
import Photos
import Security
import PhotosUI
import ComposeApp
import SwiftUI
import UIKit
import UniformTypeIdentifiers
import UserNotifications
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
        let engine = IOSMediaEngine()
        // Pengingat jadwal tetap tampil sebagai banner walau aplikasi sedang dibuka.
        UNUserNotificationCenter.current().delegate = engine
        let compose = MainViewControllerKt.MainViewController(engine: engine, documents: documents)
        let root = RootController(compose: compose)
        engine.root = root
        return root
    }

    func updateUIViewController(_ uiViewController: UIViewController, context: Context) {}
}

/// Layar Compose penuh dengan bilah tab asli iOS di bawahnya (Liquid Glass di iOS 26). Tab dan kapan bilah tampil
/// diatur Kotlin lewat [IOSMediaEngine.setTabBar]; ketukan diteruskan ke Kotlin lewat pendengar tab.
final class RootController: UIViewController, UITabBarDelegate {
    private let compose: UIViewController
    let tabBar = UITabBar()
    var onSelect: ((Int) -> Void)?
    private var shown = true

    private static let tabs: [(String, String, String)] = [
        ("Project", "house", "house.fill"),
        ("Jadwal", "calendar", "calendar"),
        ("Statistik", "chart.bar", "chart.bar.fill"),
        ("Pengaturan", "slider.horizontal.3", "slider.horizontal.3"),
    ]

    init(compose: UIViewController) {
        self.compose = compose
        super.init(nibName: nil, bundle: nil)
    }

    required init?(coder: NSCoder) { fatalError("init(coder:) tidak dipakai") }

    override func viewDidLoad() {
        super.viewDidLoad()
        addChild(compose)
        compose.view.frame = view.bounds
        compose.view.autoresizingMask = [.flexibleWidth, .flexibleHeight]
        view.addSubview(compose.view)
        compose.didMove(toParent: self)

        tabBar.items = Self.tabs.enumerated().map { index, tab in
            UITabBarItem(title: tab.0, image: UIImage(systemName: tab.1), selectedImage: UIImage(systemName: tab.2)).also { $0.tag = index }
        }
        tabBar.selectedItem = tabBar.items?.first
        tabBar.delegate = self
        // Warna aksen ClipMI (emerald) untuk tab aktif; latar dan efek kaca dari sistem.
        tabBar.tintColor = UIColor(red: 0x04 / 255.0, green: 0x78 / 255.0, blue: 0x57 / 255.0, alpha: 1)
        tabBar.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(tabBar)
        NSLayoutConstraint.activate([
            tabBar.leadingAnchor.constraint(equalTo: view.leadingAnchor),
            tabBar.trailingAnchor.constraint(equalTo: view.trailingAnchor),
            tabBar.bottomAnchor.constraint(equalTo: view.bottomAnchor),
        ])
    }

    func tabBar(_ tabBar: UITabBar, didSelect item: UITabBarItem) {
        onSelect?(item.tag)
    }

    /// Tab aktif dan apakah bilah tampil (disembunyikan di layar di atas tab: project, editor, pengaturan, dll.).
    func update(selected: Int, visible: Bool) {
        if let item = tabBar.items?.first(where: { $0.tag == selected }), tabBar.selectedItem !== item {
            tabBar.selectedItem = item
        }
        guard visible != shown else { return }
        shown = visible
        if visible { tabBar.isHidden = false }
        UIView.animate(withDuration: 0.22, animations: {
            self.tabBar.alpha = visible ? 1 : 0
            self.tabBar.transform = visible ? .identity : CGAffineTransform(translationX: 0, y: 24)
        }, completion: { _ in
            if !self.shown { self.tabBar.isHidden = true }
        })
    }
}

private extension UITabBarItem {
    func also(_ block: (UITabBarItem) -> Void) -> UITabBarItem {
        block(self)
        return self
    }
}

/// Bagian iOS yang tidak bisa ditulis di Kotlin common: yt-dlp lewat Python tertanam dan penggabungan AVFoundation.
final class IOSMediaEngine: NSObject, MediaEngine {
    weak var root: RootController?
    private var tabListener: TabListener?

    func setTabBar(selected: Int32, visible: Bool) {
        root?.update(selected: Int(selected), visible: visible)
    }

    func onTabSelected(listener: TabListener) {
        tabListener = listener
        root?.onSelect = { [weak self] index in self?.tabListener?.selected(index: Int32(index)) }
    }
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

    /// Folder font libass (font bawaan + font tambahan pengguna), diatur Kotlin setelah folder gabungannya siap.
    private var assFonts: String?

    func useFontsDirectory(path: String) {
        assFonts = path
    }

    func pickFont(output: String, listener: PickListener) {
        guard let top = Self.topController() else {
            listener.finished(path: nil)
            return
        }
        let controller = UIDocumentPickerViewController(forOpeningContentTypes: [.font, .data], asCopy: true)
        let delegate = FilePicker(output: output) { [weak self] path in
            self?.documentPicker = nil
            listener.finished(path: path)
        }
        documentPicker = delegate
        controller.delegate = delegate
        top.present(controller, animated: true)
    }

    // MARK: Proses panjang (layar tetap menyala, waktu tambahan di latar)

    private var backgroundTask: UIBackgroundTaskIdentifier = .invalid

    func setBusy(busy: Bool) {
        UIApplication.shared.isIdleTimerDisabled = busy
        if busy {
            guard backgroundTask == .invalid else { return }
            backgroundTask = UIApplication.shared.beginBackgroundTask(withName: "ClipMI") { [weak self] in
                guard let self = self else { return }
                UIApplication.shared.endBackgroundTask(self.backgroundTask)
                self.backgroundTask = .invalid
            }
        } else if backgroundTask != .invalid {
            UIApplication.shared.endBackgroundTask(backgroundTask)
            backgroundTask = .invalid
        }
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
    private var videoPicker: VideoPicker?
    private var sourcePicker: SourceVideoPicker?

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

    func pickStyleImage(output: String, png: Bool, maxSide: Int32, listener: PickListener) {
        guard let top = Self.topController() else {
            listener.finished(path: nil)
            return
        }
        var configuration = PHPickerConfiguration()
        configuration.filter = .images
        configuration.selectionLimit = 1
        let controller = PHPickerViewController(configuration: configuration)
        let delegate = ImagePicker(output: output, png: png, maxSide: CGFloat(maxSide)) { [weak self] path in
            self?.picker = nil
            listener.finished(path: path)
        }
        picker = delegate
        controller.delegate = delegate
        top.present(controller, animated: true)
    }

    func pickVideo(output: String, listener: PickListener) {
        guard let top = Self.topController() else {
            listener.finished(path: nil)
            return
        }
        var configuration = PHPickerConfiguration()
        configuration.filter = .videos
        configuration.selectionLimit = 1
        // Photos mengonversi HEVC/HDR ke bentuk yang paling kompatibel (H.264) sebelum diserahkan.
        configuration.preferredAssetRepresentationMode = .compatible
        let controller = PHPickerViewController(configuration: configuration)
        let delegate = VideoPicker(output: output) { [weak self] path in
            self?.videoPicker = nil
            listener.finished(path: path)
        }
        videoPicker = delegate
        controller.delegate = delegate
        top.present(controller, animated: true)
    }

    func pickSourceVideo(output: String, fromFiles: Bool, listener: ResultListener) {
        guard let top = Self.topController() else {
            listener.finished(resultJson: "{}")
            return
        }
        let delegate = SourceVideoPicker(output: output) { [weak self] result in
            self?.sourcePicker = nil
            listener.finished(resultJson: Self.json(result))
        }
        sourcePicker = delegate
        if fromFiles {
            let controller = UIDocumentPickerViewController(forOpeningContentTypes: [.movie], asCopy: true)
            controller.delegate = delegate
            top.present(controller, animated: true)
        } else {
            var configuration = PHPickerConfiguration()
            configuration.filter = .videos
            configuration.selectionLimit = 1
            // Bentuk asli (tanpa konversi): video kajian berjam-jam tidak perlu ditranskode dulu.
            configuration.preferredAssetRepresentationMode = .current
            let controller = PHPickerViewController(configuration: configuration)
            controller.delegate = delegate
            top.present(controller, animated: true)
        }
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

    /// Kanal transparan (alpha) gambar sebagai 1 byte per piksel, baris atas dulu; hasil "lebar×tinggi" atau nil.
    func alphaPixels(imagePath: String, output: String) -> String? {
        guard let image = UIImage(contentsOfFile: imagePath)?.cgImage else { return nil }
        let width = image.width
        let height = image.height
        var bytes = [UInt8](repeating: 0, count: width * height)
        let drawn: Bool = bytes.withUnsafeMutableBytes { buffer in
            guard let context = CGContext(
                data: buffer.baseAddress, width: width, height: height, bitsPerComponent: 8, bytesPerRow: width,
                space: CGColorSpaceCreateDeviceGray(), bitmapInfo: CGImageAlphaInfo.alphaOnly.rawValue
            ) else { return false }
            context.draw(image, in: CGRect(x: 0, y: 0, width: width, height: height))
            return true
        }
        guard drawn, (try? Data(bytes).write(to: URL(fileURLWithPath: output))) != nil else { return nil }
        return "\(width)x\(height)"
    }

    // MARK: Statistik postingan

    func fetchStats(url: String, directory: String, listener: ResultListener) {
        if let error = ClipPythonStart() {
            listener.finished(resultJson: Self.json(["ok": false, "error": "Python gagal menyala: \(error)"]))
            return
        }
        let thread = Thread {
            try? FileManager.default.createDirectory(atPath: directory, withIntermediateDirectories: true)
            let result = ClipPythonCall("clipmi_youtube", "stats", [url, directory + "/yt-dlp-cache", directory + "/stats.log"])
            DispatchQueue.main.async { listener.finished(resultJson: result) }
        }
        thread.stackSize = 16 * 1024 * 1024
        thread.start()
    }

    // MARK: HTTP (Mode API)

    func httpRequest(method: String, url: String, headersJson: String, body: String?, timeoutSeconds: Int32, listener: HttpListener) {
        guard let target = URL(string: url) else {
            listener.finished(status: 0, body: "", retryAfter: nil, error: "Alamat API tidak valid.")
            return
        }
        var request = URLRequest(url: target, timeoutInterval: TimeInterval(timeoutSeconds))
        request.httpMethod = method
        if let data = headersJson.data(using: .utf8),
           let headers = (try? JSONSerialization.jsonObject(with: data)) as? [String: String] {
            for (name, value) in headers { request.setValue(value, forHTTPHeaderField: name) }
        }
        if let body = body {
            request.httpBody = body.data(using: .utf8)
            request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        }
        let configuration = URLSessionConfiguration.ephemeral
        configuration.timeoutIntervalForRequest = TimeInterval(timeoutSeconds)
        configuration.timeoutIntervalForResource = TimeInterval(timeoutSeconds)
        let session = URLSession(configuration: configuration)
        session.dataTask(with: request) { data, response, error in
            let http = response as? HTTPURLResponse
            let text = data.flatMap { String(data: $0, encoding: .utf8) } ?? ""
            let retry = http?.value(forHTTPHeaderField: "retry-after")
            var failure: String?
            if let error = error as? URLError {
                failure = error.code == .timedOut ? "timeout" : error.localizedDescription
            } else if let error = error {
                failure = error.localizedDescription
            }
            DispatchQueue.main.async {
                listener.finished(status: Int32(http?.statusCode ?? 0), body: text, retryAfter: retry, error: failure)
            }
            session.finishTasksAndInvalidate()
        }.resume()
    }

    func uploadFile(url: String, headersJson: String, fieldsJson: String, filePath: String, fileField: String, mimeType: String, timeoutSeconds: Int32, listener: HttpListener) {
        guard let target = URL(string: url) else {
            listener.finished(status: 0, body: "", retryAfter: nil, error: "Alamat API tidak valid.")
            return
        }
        DispatchQueue.global(qos: .userInitiated).async {
            guard let file = FileManager.default.contents(atPath: filePath) else {
                DispatchQueue.main.async { listener.finished(status: 0, body: "", retryAfter: nil, error: "File audio tidak bisa dibaca.") }
                return
            }
            let boundary = "clipmi-" + UUID().uuidString
            var body = Data()
            func add(_ text: String) { body.append(text.data(using: .utf8)!) }
            // Urutan bidang tetap: JSON array [[nama, nilai], ...].
            if let data = fieldsJson.data(using: .utf8),
               let fields = (try? JSONSerialization.jsonObject(with: data)) as? [[String]] {
                for field in fields where field.count == 2 {
                    add("--\(boundary)\r\nContent-Disposition: form-data; name=\"\(field[0])\"\r\n\r\n\(field[1])\r\n")
                }
            }
            let name = (filePath as NSString).lastPathComponent
            add("--\(boundary)\r\nContent-Disposition: form-data; name=\"\(fileField)\"; filename=\"\(name)\"\r\nContent-Type: \(mimeType)\r\n\r\n")
            body.append(file)
            add("\r\n--\(boundary)--\r\n")

            var request = URLRequest(url: target, timeoutInterval: TimeInterval(timeoutSeconds))
            request.httpMethod = "POST"
            if let data = headersJson.data(using: .utf8),
               let headers = (try? JSONSerialization.jsonObject(with: data)) as? [String: String] {
                for (key, value) in headers { request.setValue(value, forHTTPHeaderField: key) }
            }
            request.setValue("multipart/form-data; boundary=\(boundary)", forHTTPHeaderField: "Content-Type")
            let configuration = URLSessionConfiguration.ephemeral
            configuration.timeoutIntervalForRequest = TimeInterval(timeoutSeconds)
            configuration.timeoutIntervalForResource = TimeInterval(timeoutSeconds)
            let session = URLSession(configuration: configuration)
            session.uploadTask(with: request, from: body) { data, response, error in
                let http = response as? HTTPURLResponse
                let text = data.flatMap { String(data: $0, encoding: .utf8) } ?? ""
                let retry = http?.value(forHTTPHeaderField: "retry-after")
                var failure: String?
                if let error = error as? URLError {
                    failure = error.code == .timedOut ? "timeout" : error.localizedDescription
                } else if let error = error {
                    failure = error.localizedDescription
                }
                DispatchQueue.main.async {
                    listener.finished(status: Int32(http?.statusCode ?? 0), body: text, retryAfter: retry, error: failure)
                }
                session.finishTasksAndInvalidate()
            }.resume()
        }
    }

    // MARK: Keychain

    private static let keychainService = "id.clipmi.ios.secrets"

    private func keychainQuery(_ name: String) -> [String: Any] {
        [kSecClass as String: kSecClassGenericPassword, kSecAttrService as String: Self.keychainService, kSecAttrAccount as String: name]
    }

    func secretGet(name: String) -> String? {
        var query = keychainQuery(name)
        query[kSecReturnData as String] = true
        query[kSecMatchLimit as String] = kSecMatchLimitOne
        var item: CFTypeRef?
        guard SecItemCopyMatching(query as CFDictionary, &item) == errSecSuccess, let data = item as? Data else { return nil }
        return String(data: data, encoding: .utf8)
    }

    func secretPut(name: String, value: String) {
        SecItemDelete(keychainQuery(name) as CFDictionary)
        var query = keychainQuery(name)
        query[kSecValueData as String] = value.data(using: .utf8)
        query[kSecAttrAccessible as String] = kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
        SecItemAdd(query as CFDictionary, nil)
    }

    func secretRemove(name: String) {
        SecItemDelete(keychainQuery(name) as CFDictionary)
    }

    // MARK: Pengingat jadwal

    private static let reminderPrefix = "clipmi-schedule-"

    func scheduleReminders(json: String) {
        let center = UNUserNotificationCenter.current()
        guard let data = json.data(using: .utf8),
              let items = (try? JSONSerialization.jsonObject(with: data)) as? [[String: Any]] else { return }
        center.requestAuthorization(options: [.alert, .sound, .badge]) { granted, _ in
            center.getPendingNotificationRequests { requests in
                let old = requests.map { $0.identifier }.filter { $0.hasPrefix(Self.reminderPrefix) }
                center.removePendingNotificationRequests(withIdentifiers: old)
                guard granted else { return }
                for item in items {
                    guard let id = (item["id"] as? NSNumber)?.int64Value,
                          let at = (item["at"] as? NSNumber)?.doubleValue else { continue }
                    let content = UNMutableNotificationContent()
                    content.title = item["title"] as? String ?? "Waktunya posting"
                    content.body = item["body"] as? String ?? ""
                    content.sound = .default
                    content.userInfo = ["schedule": id]
                    let date = Date(timeIntervalSince1970: at / 1000)
                    let parts = Calendar.current.dateComponents([.year, .month, .day, .hour, .minute], from: date)
                    let trigger = UNCalendarNotificationTrigger(dateMatching: parts, repeats: false)
                    center.add(UNNotificationRequest(identifier: "\(Self.reminderPrefix)\(id)", content: content, trigger: trigger))
                }
            }
        }
    }

    func remindersAllowed(listener: AllowedListener) {
        UNUserNotificationCenter.current().getNotificationSettings { settings in
            let state: String
            switch settings.authorizationStatus {
            case .authorized, .provisional, .ephemeral: state = "allowed"
            case .denied: state = "denied"
            default: state = "unknown"
            }
            DispatchQueue.main.async { listener.finished(state: state) }
        }
    }

    // MARK: File

    func shareFile(path: String) {
        guard let top = Self.topController() else { return }
        let controller = UIActivityViewController(activityItems: [URL(fileURLWithPath: path)], applicationActivities: nil)
        controller.popoverPresentationController?.sourceView = top.view
        top.present(controller, animated: true)
    }

    private var documentPicker: FilePicker?
    /// Pengingat yang diketuk sebelum Kotlin mendaftarkan pendengarnya (aplikasi dibuka dari notifikasi).
    fileprivate var pendingReminder: String?
    fileprivate var reminderListener: ResultListener?

    func onReminderTapped(listener: ResultListener) {
        reminderListener = listener
        if let id = pendingReminder {
            pendingReminder = nil
            listener.finished(resultJson: id)
        }
    }

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

    func pickTeamPack(output: String, listener: PickListener) {
        guard let top = Self.topController() else {
            listener.finished(path: nil)
            return
        }
        let controller = UIDocumentPickerViewController(forOpeningContentTypes: [.json, .plainText, .data], asCopy: true)
        let delegate = FilePicker(output: output) { [weak self] path in
            self?.documentPicker = nil
            listener.finished(path: path)
        }
        documentPicker = delegate
        controller.delegate = delegate
        top.present(controller, animated: true)
    }

    /// Gambar apa pun yang dibaca iOS (termasuk WebP/HEIC) disimpan ulang sebagai PNG atau JPEG tegak; FFmpeg di iPhone
    /// hanya membaca PNG dan JPEG.
    func convertImage(input: String, output: String, png: Bool, maxSide: Int32) -> Bool {
        guard let image = UIImage(contentsOfFile: input) else { return false }
        let upright = ImagePicker.upright(image, maxSide: CGFloat(maxSide), opaque: !png)
        guard let data = png ? upright.pngData() : upright.jpegData(compressionQuality: 0.92) else { return false }
        return (try? data.write(to: URL(fileURLWithPath: output))) != nil
    }

    func pickSubtitle(output: String, listener: PickListener) {
        guard let top = Self.topController() else {
            listener.finished(path: nil)
            return
        }
        let types = [UTType(filenameExtension: "srt"), UTType(filenameExtension: "vtt"), UTType.plainText].compactMap { $0 }
        let controller = UIDocumentPickerViewController(forOpeningContentTypes: types, asCopy: true)
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
        guard let ctx = clipmi_ass_create(width, height, assFonts ?? fontsDirectory(), nil) else { return 0 }
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

extension IOSMediaEngine: UNUserNotificationCenterDelegate {
    func userNotificationCenter(
        _ center: UNUserNotificationCenter, willPresent notification: UNNotification,
        withCompletionHandler completionHandler: @escaping (UNNotificationPresentationOptions) -> Void
    ) {
        completionHandler([.banner, .sound])
    }

    /// Pengingat jadwal diketuk: id jadwalnya diteruskan ke Kotlin (halaman siap posting).
    func userNotificationCenter(
        _ center: UNUserNotificationCenter, didReceive response: UNNotificationResponse,
        withCompletionHandler completionHandler: @escaping () -> Void
    ) {
        let identifier = response.notification.request.identifier
        if identifier.hasPrefix(Self.reminderPrefix) {
            let id = String(identifier.dropFirst(Self.reminderPrefix.count))
            DispatchQueue.main.async {
                if let listener = self.reminderListener {
                    listener.finished(resultJson: id)
                } else {
                    self.pendingReminder = id
                }
            }
        }
        completionHandler()
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

/// Video sumber project dari Photos atau Files: dipindah ke `output.<ekstensi>`; hasil `{"path", "name"}`, `{}` bila batal.
final class SourceVideoPicker: NSObject, PHPickerViewControllerDelegate, UIDocumentPickerDelegate {
    private let output: String
    private let done: ([String: Any]) -> Void

    init(output: String, done: @escaping ([String: Any]) -> Void) {
        self.output = output
        self.done = done
    }

    private static func store(_ url: URL, output: String, move: Bool) -> String? {
        let ext = url.pathExtension.isEmpty ? "mp4" : url.pathExtension.lowercased()
        let target = URL(fileURLWithPath: output + "." + ext)
        try? FileManager.default.removeItem(at: target)
        let ok = (move && (try? FileManager.default.moveItem(at: url, to: target)) != nil)
            || (try? FileManager.default.copyItem(at: url, to: target)) != nil
        return ok ? target.path : nil
    }

    func picker(_ picker: PHPickerViewController, didFinishPicking results: [PHPickerResult]) {
        picker.dismiss(animated: true)
        let type = UTType.movie.identifier
        guard let provider = results.first?.itemProvider, provider.hasItemConformingToTypeIdentifier(type) else {
            done([:])
            return
        }
        let output = self.output
        let done = self.done
        let name = provider.suggestedName ?? "Video"
        provider.loadFileRepresentation(forTypeIdentifier: type) { url, error in
            var result: [String: Any] = ["error": error?.localizedDescription ?? "Video tidak bisa dibuka."]
            // Salinan sementara sistem dihapus setelah blok ini, jadi disalin sekarang.
            if let url = url, let path = Self.store(url, output: output, move: false) {
                result = ["path": path, "name": name]
            }
            DispatchQueue.main.async { done(result) }
        }
    }

    func documentPicker(_ controller: UIDocumentPickerViewController, didPickDocumentsAt urls: [URL]) {
        guard let url = urls.first else {
            done([:])
            return
        }
        let output = self.output
        let done = self.done
        DispatchQueue.global(qos: .userInitiated).async {
            let path = Self.store(url, output: output, move: true)
            var result: [String: Any] = ["error": "Video tidak bisa disalin."]
            if let path = path { result = ["path": path, "name": url.deletingPathExtension().lastPathComponent] }
            DispatchQueue.main.async { done(result) }
        }
    }

    func documentPickerWasCancelled(_ controller: UIDocumentPickerViewController) {
        done([:])
    }
}

/// Satu video dari PHPicker (tanpa izin galeri), disalin ke tujuan sebelum salinan sementara sistem dihapus.
final class VideoPicker: NSObject, PHPickerViewControllerDelegate {
    private let output: String
    private let done: (String?) -> Void

    init(output: String, done: @escaping (String?) -> Void) {
        self.output = output
        self.done = done
    }

    func picker(_ picker: PHPickerViewController, didFinishPicking results: [PHPickerResult]) {
        picker.dismiss(animated: true)
        let type = UTType.movie.identifier
        guard let provider = results.first?.itemProvider, provider.hasItemConformingToTypeIdentifier(type) else {
            done(nil)
            return
        }
        let output = self.output
        let done = self.done
        provider.loadFileRepresentation(forTypeIdentifier: type) { url, _ in
            var path: String?
            if let url = url {
                let target = URL(fileURLWithPath: output)
                try? FileManager.default.removeItem(at: target)
                if (try? FileManager.default.copyItem(at: url, to: target)) != nil { path = output }
            }
            DispatchQueue.main.async { done(path) }
        }
    }
}

/// Satu gambar dari PHPicker, digambar ulang tegak (orientasi EXIF diterapkan; FFmpeg tidak membacanya) sebagai JPEG.
final class ImagePicker: NSObject, PHPickerViewControllerDelegate {
    private let output: String
    private let png: Bool
    private let maxSide: CGFloat
    private let done: (String?) -> Void

    init(output: String, png: Bool = false, maxSide: CGFloat = 2160, done: @escaping (String?) -> Void) {
        self.output = output
        self.png = png
        self.maxSide = maxSide
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
        let png = self.png
        let maxSide = self.maxSide
        provider.loadObject(ofClass: UIImage.self) { object, _ in
            var path: String?
            if let image = object as? UIImage {
                let upright = ImagePicker.upright(image, maxSide: maxSide, opaque: !png)
                if let data = png ? upright.pngData() : upright.jpegData(compressionQuality: 0.92),
                   (try? data.write(to: URL(fileURLWithPath: output))) != nil {
                    path = output
                }
            }
            DispatchQueue.main.async { done(path) }
        }
    }

    /** Tegak dan paling besar [maxSide] piksel di sisi terpanjang; [opaque] false = kanal transparan dipertahankan. */
    static func upright(_ image: UIImage, maxSide: CGFloat = 2160, opaque: Bool = true) -> UIImage {
        let longest = max(image.size.width, image.size.height)
        let scale = longest > maxSide ? maxSide / longest : 1
        let size = CGSize(width: (image.size.width * scale).rounded(), height: (image.size.height * scale).rounded())
        let format = UIGraphicsImageRendererFormat()
        format.scale = 1
        format.opaque = opaque
        return UIGraphicsImageRenderer(size: size, format: format).image { _ in image.draw(in: CGRect(origin: .zero, size: size)) }
    }
}
