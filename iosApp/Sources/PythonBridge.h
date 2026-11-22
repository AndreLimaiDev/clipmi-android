#import <Foundation/Foundation.h>

NS_ASSUME_NONNULL_BEGIN

/// Menyalakan interpreter Python sekali (panggil dari main thread). nil = berhasil, selain itu pesan galat.
NSString * _Nullable ClipPythonStart(void);

/// Memanggil `module.function(*args)` (semua argumen teks) di thread pemanggil (thread latar). Fungsi Python harus
/// mengembalikan teks; bila gagal, hasilnya JSON `{"ok": false, "error": ...}`.
NSString *ClipPythonCall(NSString *module, NSString *function, NSArray<NSString *> *args);

NS_ASSUME_NONNULL_END
