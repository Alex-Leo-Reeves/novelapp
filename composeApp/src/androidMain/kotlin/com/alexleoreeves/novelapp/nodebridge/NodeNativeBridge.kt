package com.alexleoreeves.novelapp.nodebridge

/**
 * JNI bridge to libnodebridge.so — the tiny compiled shim that dlopens the
 * embedded nodejs-mobile runtime (libnode.so), dlsyms `node::Start`, and runs
 * the nodebridge worker on a detached pthread.
 *
 * The C++ symbol is `Java_com_alexleoreeves_novelapp_nodebridge_NodeNativeBridge_startNode`
 * (see nodebridge/src/node_jni.cpp). It returns immediately — startup is async,
 * and bridge-port.json appearing next to the entry script is the readiness
 * signal at the Kotlin layer.
 */
object NodeNativeBridge {

    /** True once libnodebridge.so was dlopened successfully. */
    @Volatile
    private var loaded = false

    init {
        try {
            System.loadLibrary("nodebridge")
            loaded = true
        } catch (t: Throwable) {
            android.util.Log.w("NodeBridge", "libnodebridge not bundled; using backend fallback", t)
        }
    }

    /** True when the JNI shim is available for this device's ABI. */
    val isLoaded: Boolean
        get() = loaded

    /**
     * @return "OK" when the Node thread was spawned, or an ERR_* code.
     */
    external fun startNode(libNodePath: String, entryPath: String): String
}
