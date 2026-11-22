#import "PythonBridge.h"
#include <Python/Python.h>

static BOOL gStarted = NO;

static NSString *StatusMessage(PyStatus status) {
    return [NSString stringWithFormat:@"%s: %s", status.func ? status.func : "Python", status.err_msg ? status.err_msg : "gagal"];
}

NSString *ClipPythonStart(void) {
    if (gStarted) return nil;
    NSString *resources = [[NSBundle mainBundle] resourcePath];
    NSString *home = [resources stringByAppendingPathComponent:@"python"];
    NSString *libDir = [home stringByAppendingPathComponent:@"lib"];
    NSString *stdlib = nil;
    for (NSString *name in [[NSFileManager defaultManager] contentsOfDirectoryAtPath:libDir error:nil]) {
        if ([name hasPrefix:@"python3."]) {
            stdlib = [libDir stringByAppendingPathComponent:name];
            break;
        }
    }
    if (stdlib == nil) return [NSString stringWithFormat:@"Pustaka standar Python tidak ditemukan di %@", libDir];

    // Pengaturan wajib dari dokumen CPython "Using Python on iOS".
    PyPreConfig preconfig;
    PyPreConfig_InitIsolatedConfig(&preconfig);
    preconfig.utf8_mode = 1;
    PyStatus status = Py_PreInitialize(&preconfig);
    if (PyStatus_Exception(status)) return StatusMessage(status);

    PyConfig config;
    PyConfig_InitIsolatedConfig(&config);
    config.buffered_stdio = 0;
    config.write_bytecode = 0;
    config.install_signal_handlers = 1;
    status = PyConfig_SetBytesString(&config, &config.home, home.UTF8String);
    if (PyStatus_Exception(status)) {
        PyConfig_Clear(&config);
        return StatusMessage(status);
    }
    config.module_search_paths_set = 1;
    NSArray<NSString *> *paths = @[
        stdlib,
        [stdlib stringByAppendingPathComponent:@"lib-dynload"],
        [resources stringByAppendingPathComponent:@"app"],
        [resources stringByAppendingPathComponent:@"app_packages"],
    ];
    for (NSString *path in paths) {
        wchar_t *wide = Py_DecodeLocale(path.UTF8String, NULL);
        status = PyWideStringList_Append(&config.module_search_paths, wide);
        PyMem_RawFree(wide);
        if (PyStatus_Exception(status)) {
            PyConfig_Clear(&config);
            return StatusMessage(status);
        }
    }
    status = Py_InitializeFromConfig(&config);
    PyConfig_Clear(&config);
    if (PyStatus_Exception(status)) return StatusMessage(status);

    // Lepaskan GIL: Python dipakai dari thread latar, main thread tetap melayani UI (dan WKWebView plugin yt-dlp).
    PyEval_SaveThread();
    gStarted = YES;
    return nil;
}

NSString *ClipPythonCall(NSString *module, NSString *function, NSArray<NSString *> *args) {
    PyGILState_STATE gil = PyGILState_Ensure();
    NSString *result = nil;
    PyObject *mod = PyImport_ImportModule(module.UTF8String);
    if (mod != NULL) {
        PyObject *fn = PyObject_GetAttrString(mod, function.UTF8String);
        if (fn != NULL) {
            PyObject *tuple = PyTuple_New((Py_ssize_t)args.count);
            for (NSUInteger i = 0; i < args.count; i++) {
                PyTuple_SET_ITEM(tuple, (Py_ssize_t)i, PyUnicode_FromString(args[i].UTF8String));
            }
            PyObject *ret = PyObject_CallObject(fn, tuple);
            Py_DECREF(tuple);
            if (ret != NULL) {
                const char *text = PyUnicode_Check(ret) ? PyUnicode_AsUTF8(ret) : NULL;
                if (text != NULL) result = [NSString stringWithUTF8String:text];
                Py_DECREF(ret);
            }
            Py_DECREF(fn);
        }
        Py_DECREF(mod);
    }
    if (result == nil) {
        NSString *message = @"galat Python tanpa pesan";
        PyObject *exc = PyErr_GetRaisedException();
        if (exc != NULL) {
            PyObject *str = PyObject_Str(exc);
            const char *text = str ? PyUnicode_AsUTF8(str) : NULL;
            if (text != NULL) message = [NSString stringWithUTF8String:text];
            Py_XDECREF(str);
            Py_DECREF(exc);
        }
        PyErr_Clear();
        NSData *json = [NSJSONSerialization dataWithJSONObject:@{@"ok": @NO, @"error": message} options:0 error:nil];
        result = [[NSString alloc] initWithData:json encoding:NSUTF8StringEncoding];
    }
    PyGILState_Release(gil);
    return result;
}
