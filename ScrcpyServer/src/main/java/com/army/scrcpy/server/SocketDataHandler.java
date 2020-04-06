package com.army.scrcpy.server;

import io.netty.buffer.ByteBuf;


public class SocketDataHandler {

    public static void readDeviceIndo(ByteBuf byteBuf) {
        int length = Server.DEVICE_NAME_FIELD_LENGTH;
        byte[] bytes = new byte[length];
        byteBuf.readBytes(bytes, 0, length);
        int width = byteBuf.readUnsignedByte() << 8 | byteBuf.readUnsignedByte();
        int height = byteBuf.readUnsignedByte() << 8 | byteBuf.readUnsignedByte();
        String deviceName = new String(bytes).trim();
        System.out.println(String.format("deviceName = %s, width = %d, height = %d",
                deviceName, width, height));
    }
}
