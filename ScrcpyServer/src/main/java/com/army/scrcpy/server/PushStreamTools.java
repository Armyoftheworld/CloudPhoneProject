package com.army.scrcpy.server;

/**
 * @author army
 * @version V_1.0.0
 * @date 2020-04-19
 * @description
 */
public class PushStreamTools {

    static {
        System.load("/root/Documents/CloudPhoneProject/ScrcpyServer/src/main/jni/libpushstream.so");
    }

    /**
     * @return success: 1, failure: 0
     */
    public static native int initRtmp(String rtmpUrl);

    public static native void pushRawStream(byte[] data, int pts, int frameSize);
}
