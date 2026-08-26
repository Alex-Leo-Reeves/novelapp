// nodebridge — JNI shim that boots the embedded Node.js runtime (nodejs-mobile
// prebuilt libnode.so) inside the app on a dedicated background thread.
//
// Why this file exists:
//   The Anivexa-API worker (13 anime providers) is blocked by provider CDNs
//   when it runs from datacenter egress (Render/Vercel -> streams=0 for
//   popular anime). The repo owner's site works because the SAME worker runs
//   in the user's browser on a residential IP. This shim runs the EXACT
//   unmodified worker inside the app via an embedded Node, on the device's
//   residential network — replicating that behavior with zero porting drift.
//
// The prebuilt nodejs-mobile libnode.so exports the raw embedding API
// `int node::Start(int, char**)` (mangled `_ZN4node5StartEiPPc`) and has no
// JNI entry point, so this tiny library:
//   1. dlopens libnode.so from the app's native library dir,
//   2. dlsyms the mangled node::Start symbol,
//   3. spawns a detached pthread that calls it with ["node", main.js],
//   4. returns immediately (startup is async; the JS bridge writes
//      bridge-port.json which Kotlin polls to discover the loopback port).
//
// Deliberately compiled as the leanest possible object:
//   - No node.h / v8.h includes (we only touch the mangled C symbol, so the
//     190MB header tree was dropped entirely).
//   - statically linked libstdc++ (only NEEDED: libc, liblog, libdl).
//   - arm64-v8a only (the overwhelmingly dominant Android/TV ABI).

#include <jni.h>
#include <android/log.h>
#include <dlfcn.h>
#include <pthread.h>
#include <new>
#include <stdlib.h>
#include <string.h>
#include <signal.h>
#include <setjmp.h>

#define LOG_TAG "NodeBridge"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

// int node::Start(int argc, char** argv) — the exported embedding entry point.
typedef int (*node_start_fn)(int argc, char** argv);

struct NodeThreadArgs {
    char* libNodePath;   // absolute path to libnode.so
    char* entryPath;     // absolute path to the bridge main.js
};

static void* nodeThreadMain(void* opaque) {
    NodeThreadArgs* args = static_cast<NodeThreadArgs*>(opaque);
    if (!args) return NULL;

    // 1. Load the embedded Node runtime.
    void* handle = dlopen(args->libNodePath, RTLD_NOW | RTLD_GLOBAL);
    if (!handle) {
        LOGE("dlopen %s failed: %s", args->libNodePath, dlerror());
        free(args->libNodePath);
        free(args->entryPath);
        delete args;
        return NULL;
    }

    // 2. Resolve node::Start — dlsym takes the mangled C++ name.
    node_start_fn start = reinterpret_cast<node_start_fn>(
        dlsym(handle, "_ZN4node5StartEiPPc"));
    if (!start) {
        LOGE("dlsym node::Start failed: %s", dlerror());
        free(args->libNodePath);
        free(args->entryPath);
        delete args;
        return NULL;
    }

    // 3. Run Node on this detached thread: node /path/to/main.js
    LOGI("booting embedded Node with entry=%s", args->entryPath);
    char* argv[2];
    argv[0] = const_cast<char*>("node");
    argv[1] = args->entryPath;

    int rc = start(2, argv);
    LOGI("embedded Node exited with code %d", rc);

    // Note: Never dlclose(handle) — V8/libnode leaves background threads and
    // TLS destructors active that will segfault if the code segment is unmapped.
    free(args->libNodePath);
    free(args->entryPath);
    delete args;
    return NULL;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_alexleoreeves_novelapp_nodebridge_NodeNativeBridge_startNode(
    JNIEnv* env, jobject /*thiz*/, jstring libNodePath, jstring entryPath) {
    const char* libPath = env->GetStringUTFChars(libNodePath, NULL);
    const char* entry = env->GetStringUTFChars(entryPath, NULL);
    if (!libPath || !entry) {
        if (libPath) env->ReleaseStringUTFChars(libNodePath, libPath);
        if (entry) env->ReleaseStringUTFChars(entryPath, entry);
        return env->NewStringUTF("ERR_NOMEM");
    }

    NodeThreadArgs* args = new (std::nothrow) NodeThreadArgs();
    if (!args) {
        env->ReleaseStringUTFChars(libNodePath, libPath);
        env->ReleaseStringUTFChars(entryPath, entry);
        return env->NewStringUTF("ERR_NOMEM");
    }
    args->libNodePath = strdup(libPath);
    args->entryPath = strdup(entry);
    env->ReleaseStringUTFChars(libNodePath, libPath);
    env->ReleaseStringUTFChars(entryPath, entry);

    pthread_t thread;
    pthread_attr_t attr;
    pthread_attr_init(&attr);
    pthread_attr_setstacksize(&attr, 8 * 1024 * 1024); // 8MB stack for Node.js / V8
    if (pthread_create(&thread, &attr, nodeThreadMain, args) != 0) {
        LOGE("pthread_create failed");
        pthread_attr_destroy(&attr);
        free(args->libNodePath);
        free(args->entryPath);
        delete args;
        return env->NewStringUTF("ERR_THREAD");
    }
    pthread_attr_destroy(&attr);
    pthread_detach(thread);
    return env->NewStringUTF("OK");
}
