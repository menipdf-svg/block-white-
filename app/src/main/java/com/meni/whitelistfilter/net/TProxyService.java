package com.meni.whitelistfilter.net;

/**
 * JNI bridge to the hev-socks5-tunnel native library (src/main/jni).
 *
 * The library registers its natives on this exact class in JNI_OnLoad, using
 * PKGNAME/CLSNAME from src/main/jni/Application.mk. Renaming or moving this
 * class requires changing those values too.
 */
public final class TProxyService {
    private TProxyService() {
    }

    static {
        System.loadLibrary("hev-socks5-tunnel");
    }

    /** Starts the tunnel on its own native thread. Returns false if it could not start. */
    public static native boolean TProxyStartService(String configPath, int fd);

    /** Stops the tunnel and waits for its thread to finish. */
    public static native boolean TProxyStopService();

    public static native boolean TProxyIsRunning();

    /** [tx packets, tx bytes, rx packets, rx bytes] */
    public static native long[] TProxyGetStats();
}
