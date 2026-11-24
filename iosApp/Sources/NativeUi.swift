import ComposeApp
import UIKit

// Komponen UI asli iOS untuk layar Compose: menu (UIMenu), lembar aksi, dialog konfirmasi, lembar (sheet), pemilih
// tanggal/jam (UIDatePicker), dan haptik. Kotlin hanya mengirim data lewat MediaEngine; semua UIKit di sini.

private struct MenuItemData: Decodable {
    let title: String
    let symbol: String?
    let destructive: Bool?
    let enabled: Bool?
    let checked: Bool?
}

private func menuItems(_ json: String) -> [MenuItemData] {
    (try? JSONDecoder().decode([MenuItemData].self, from: Data(json.utf8))) ?? []
}

/// Warna dari "#RRGGBB" atau "#AARRGGBB".
private func hexColor(_ hex: String) -> UIColor {
    var text = hex.trimmingCharacters(in: .whitespaces)
    if text.hasPrefix("#") { text.removeFirst() }
    guard let value = UInt64(text, radix: 16) else { return .clear }
    if text.count == 8 {
        return UIColor(
            red: CGFloat((value >> 16) & 0xFF) / 255, green: CGFloat((value >> 8) & 0xFF) / 255,
            blue: CGFloat(value & 0xFF) / 255, alpha: CGFloat((value >> 24) & 0xFF) / 255
        )
    }
    return UIColor(red: CGFloat((value >> 16) & 0xFF) / 255, green: CGFloat((value >> 8) & 0xFF) / 255, blue: CGFloat(value & 0xFF) / 255, alpha: 1)
}

/// Tombol menu; pendengar disimpan di tombol supaya menu yang dibangun ulang memanggil Kotlin yang terbaru.
final class NativeMenuButton: UIButton {
    var listener: ChoiceListener?
}

/// Menutup lembar dengan usap ke bawah: kabari Kotlin.
private final class SheetDismissDelegate: NSObject, UIAdaptivePresentationControllerDelegate {
    let onDismiss: () -> Void
    init(onDismiss: @escaping () -> Void) { self.onDismiss = onDismiss }
    func presentationControllerDidDismiss(_ presentationController: UIPresentationController) { onDismiss() }
}

/// Lembar pemilih tanggal/jam: UIDatePicker dengan Batal dan Pakai di bilah atas.
private final class DatePickerController: UIViewController {
    private let picker: UIDatePicker
    private let onPick: (Date) -> Void

    init(picker: UIDatePicker, title: String, onPick: @escaping (Date) -> Void) {
        self.picker = picker
        self.onPick = onPick
        super.init(nibName: nil, bundle: nil)
        self.title = title
    }

    required init?(coder: NSCoder) { fatalError("init(coder:) tidak dipakai") }

    override func viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = .systemBackground
        navigationItem.leftBarButtonItem = UIBarButtonItem(systemItem: .cancel, primaryAction: UIAction { [weak self] _ in
            self?.dismiss(animated: true)
        }, menu: nil)
        navigationItem.rightBarButtonItem = UIBarButtonItem(title: "Pakai", image: nil, primaryAction: UIAction { [weak self] _ in
            guard let self else { return }
            self.onPick(self.picker.date)
            self.dismiss(animated: true)
        }, menu: nil)
        navigationItem.rightBarButtonItem?.style = .done
        picker.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(picker)
        NSLayoutConstraint.activate([
            picker.topAnchor.constraint(equalTo: view.safeAreaLayoutGuide.topAnchor, constant: 8),
            picker.leadingAnchor.constraint(equalTo: view.safeAreaLayoutGuide.leadingAnchor, constant: 12),
            picker.trailingAnchor.constraint(equalTo: view.safeAreaLayoutGuide.trailingAnchor, constant: -12),
        ])
    }
}

extension IOSMediaEngine {
    private static var presented: [Int64: UIViewController] = [:]
    private static var sheetDelegates: [Int64: SheetDismissDelegate] = [:]
    private static var nextToken: Int64 = 1

    private static func token() -> Int64 {
        defer { nextToken += 1 }
        return nextToken
    }

    // MARK: Menu

    func makeMenuButton(symbol: String, tint: String, background: String, itemsJson: String, listener: ChoiceListener) -> Any {
        let button = NativeMenuButton(type: .system)
        button.showsMenuAsPrimaryAction = true
        button.layer.cornerRadius = 12
        button.layer.masksToBounds = true
        configureMenu(button, symbol: symbol, tint: tint, background: background, itemsJson: itemsJson, listener: listener)
        return button
    }

    func updateMenuButton(button: Any, symbol: String, tint: String, background: String, itemsJson: String, listener: ChoiceListener) {
        guard let button = button as? NativeMenuButton else { return }
        configureMenu(button, symbol: symbol, tint: tint, background: background, itemsJson: itemsJson, listener: listener)
    }

    private func configureMenu(_ button: NativeMenuButton, symbol: String, tint: String, background: String, itemsJson: String, listener: ChoiceListener) {
        button.listener = listener
        let config = UIImage.SymbolConfiguration(pointSize: 17, weight: .semibold)
        button.setImage(UIImage(systemName: symbol, withConfiguration: config), for: .normal)
        button.tintColor = hexColor(tint)
        button.backgroundColor = hexColor(background)
        let actions: [UIMenuElement] = menuItems(itemsJson).enumerated().map { index, item in
            let action = UIAction(title: item.title, image: item.symbol.flatMap { UIImage(systemName: $0) }) { [weak button] _ in
                button?.listener?.chosen(index: Int32(index))
            }
            if item.destructive == true { action.attributes.insert(.destructive) }
            if item.enabled == false { action.attributes.insert(.disabled) }
            if item.checked == true { action.state = .on }
            return action
        }
        button.menu = UIMenu(children: actions)
    }

    // MARK: Lembar aksi dan dialog

    func showActionSheet(title: String?, itemsJson: String, listener: ChoiceListener) {
        guard let top = Self.topController() else {
            listener.chosen(index: -1)
            return
        }
        let sheet = UIAlertController(title: title, message: nil, preferredStyle: .actionSheet)
        for (index, item) in menuItems(itemsJson).enumerated() {
            let action = UIAlertAction(title: item.title, style: item.destructive == true ? .destructive : .default) { _ in
                listener.chosen(index: Int32(index))
            }
            action.isEnabled = item.enabled != false
            sheet.addAction(action)
        }
        sheet.addAction(UIAlertAction(title: "Batal", style: .cancel) { _ in listener.chosen(index: -1) })
        // iPad: lembar aksi tampil sebagai popover dan wajib punya titik asal.
        if let popover = sheet.popoverPresentationController {
            popover.sourceView = top.view
            popover.sourceRect = CGRect(x: top.view.bounds.midX, y: top.view.bounds.midY, width: 1, height: 1)
            popover.permittedArrowDirections = []
        }
        top.present(sheet, animated: true)
    }

    func showAlert(title: String, message: String, confirmLabel: String, destructive: Bool, listener: ChoiceListener) -> Int64 {
        guard let top = Self.topController() else { return 0 }
        let token = Self.token()
        let alert = UIAlertController(title: title, message: message, preferredStyle: .alert)
        alert.addAction(UIAlertAction(title: "Batal", style: .cancel) { _ in
            Self.presented[token] = nil
            listener.chosen(index: 0)
        })
        alert.addAction(UIAlertAction(title: confirmLabel, style: destructive ? .destructive : .default) { _ in
            Self.presented[token] = nil
            listener.chosen(index: 1)
        })
        Self.presented[token] = alert
        top.present(alert, animated: true)
        return token
    }

    // MARK: Lembar (sheet)

    func presentSheet(controller: Any, listener: ChoiceListener) -> Int64 {
        guard let sheetController = controller as? UIViewController, let top = Self.topController() else { return 0 }
        let token = Self.token()
        sheetController.modalPresentationStyle = .pageSheet
        if let sheet = sheetController.sheetPresentationController {
            sheet.detents = [.medium(), .large()]
            sheet.selectedDetentIdentifier = .large
            sheet.prefersGrabberVisible = true
            sheet.prefersScrollingExpandsWhenScrolledToEdge = true
            sheet.preferredCornerRadius = 28
        }
        let delegate = SheetDismissDelegate {
            Self.presented[token] = nil
            Self.sheetDelegates[token] = nil
            listener.chosen(index: -1)
        }
        sheetController.presentationController?.delegate = delegate
        Self.sheetDelegates[token] = delegate
        Self.presented[token] = sheetController
        top.present(sheetController, animated: true)
        return token
    }

    func dismissPresented(token: Int64) {
        Self.sheetDelegates[token] = nil
        guard let controller = Self.presented.removeValue(forKey: token) else { return }
        if controller.presentingViewController != nil { controller.dismiss(animated: true) }
    }

    // MARK: Tanggal dan jam

    func showDatePicker(mode: String, year: Int32, month: Int32, day: Int32, hour: Int32, minute: Int32, listener: DateListener) {
        guard let top = Self.topController() else { return }
        let time = mode == "time"
        let picker = UIDatePicker()
        picker.datePickerMode = time ? .time : .date
        picker.preferredDatePickerStyle = time ? .wheels : .inline
        // Bahasa Indonesia, jam 24 jam.
        picker.locale = Locale(identifier: "id_ID")
        picker.tintColor = hexColor("#12A884")
        var components = DateComponents()
        components.year = Int(year)
        components.month = Int(month)
        components.day = Int(day)
        components.hour = Int(hour)
        components.minute = Int(minute)
        picker.date = Calendar.current.date(from: components) ?? Date()
        let controller = DatePickerController(picker: picker, title: time ? "Pilih jam" : "Pilih tanggal") { date in
            let c: DateComponents = Calendar.current.dateComponents([.year, .month, .day, .hour, .minute], from: date)
            let pickedYear: Int32 = Int32(c.year ?? Int(year))
            let pickedMonth: Int32 = Int32(c.month ?? Int(month))
            let pickedDay: Int32 = Int32(c.day ?? Int(day))
            let pickedHour: Int32 = Int32(c.hour ?? Int(hour))
            let pickedMinute: Int32 = Int32(c.minute ?? Int(minute))
            listener.picked(year: pickedYear, month: pickedMonth, day: pickedDay, hour: pickedHour, minute: pickedMinute)
        }
        let navigation = UINavigationController(rootViewController: controller)
        navigation.modalPresentationStyle = .pageSheet
        if let sheet = navigation.sheetPresentationController {
            sheet.detents = time ? [.medium()] : [.large()]
            sheet.prefersGrabberVisible = true
        }
        top.present(navigation, animated: true)
    }

    // MARK: Haptik

    func haptic(kind: String) {
        switch kind {
        case "selection": UISelectionFeedbackGenerator().selectionChanged()
        case "success": UINotificationFeedbackGenerator().notificationOccurred(.success)
        case "warning": UINotificationFeedbackGenerator().notificationOccurred(.warning)
        case "error": UINotificationFeedbackGenerator().notificationOccurred(.error)
        case "medium": UIImpactFeedbackGenerator(style: .medium).impactOccurred()
        default: UIImpactFeedbackGenerator(style: .light).impactOccurred()
        }
    }
}
