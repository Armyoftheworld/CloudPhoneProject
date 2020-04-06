package com.genymobile.scrcpy;

import android.net.LocalServerSocket;
import android.net.LocalSocket;
import android.net.LocalSocketAddress;
import com.army.scrcpy.server.ControlMessageProto;

import java.io.*;
import java.nio.ByteBuffer;

public final class DesktopConnection implements Closeable {

    private static final int DEVICE_NAME_FIELD_LENGTH = 64;

    private static final String SOCKET_NAME = "scrcpy";

    private final LocalSocket videoSocket;
    private final FileDescriptor videoFd;
    private final FileDescriptor controlFd;

    private final LocalSocket controlSocket;
    private final InputStream controlInputStream;
    private final OutputStream controlOutputStream;

    private final ControlMessageReader reader = new ControlMessageReader();
    private final DeviceMessageWriter writer = new DeviceMessageWriter();

    private DesktopConnection(LocalSocket videoSocket, LocalSocket controlSocket) throws IOException {
        this.videoSocket = videoSocket;
        this.controlSocket = controlSocket;
        controlInputStream = controlSocket.getInputStream();
        controlOutputStream = controlSocket.getOutputStream();
        videoFd = videoSocket.getFileDescriptor();
        controlFd = controlSocket.getFileDescriptor();
    }

    private static LocalSocket connect(String abstractName) throws IOException {
        LocalSocket localSocket = new LocalSocket();
        localSocket.connect(new LocalSocketAddress(abstractName));
        return localSocket;
    }

    public static DesktopConnection open(Device device, boolean tunnelForward) throws IOException {
        LocalSocket videoSocket;
        LocalSocket controlSocket;
        if (tunnelForward) {
            LocalServerSocket localServerSocket = new LocalServerSocket(SOCKET_NAME);
            try {
                controlSocket = localServerSocket.accept();
                // send one byte so the client may read() to detect a connection error
//                videoSocket.getOutputStream().write(8);
                try {
                    videoSocket = localServerSocket.accept();
                } catch (IOException | RuntimeException e) {
                    controlSocket.close();
                    throw e;
                }
            } finally {
                localServerSocket.close();
            }
        } else {
            controlSocket = connect(SOCKET_NAME);
            try {
                videoSocket = connect(SOCKET_NAME);
            } catch (IOException | RuntimeException e) {
                controlSocket.close();
                throw e;
            }
        }

        DesktopConnection connection = new DesktopConnection(videoSocket, controlSocket);
        Size videoSize = device.getScreenInfo().getVideoSize();
        connection.send(Device.getDeviceName(), videoSize.getWidth(), videoSize.getHeight());
        return connection;
    }

    public void close() throws IOException {
        videoSocket.shutdownInput();
        videoSocket.shutdownOutput();
        videoSocket.close();
        controlSocket.shutdownInput();
        controlSocket.shutdownOutput();
        controlSocket.close();
    }

    @SuppressWarnings("checkstyle:MagicNumber")
    private void send(String deviceName, int width, int height) throws IOException {
//        byte[] buffer = new byte[DEVICE_NAME_FIELD_LENGTH + 4];
//
//        byte[] deviceNameBytes = deviceName.getBytes(StandardCharsets.UTF_8);
//        int len = StringUtils.getUtf8TruncationIndex(deviceNameBytes, DEVICE_NAME_FIELD_LENGTH - 1);
//        System.arraycopy(deviceNameBytes, 0, buffer, 0, len);
//        // byte[] are always 0-initialized in java, no need to set '\0' explicitly
//
//        buffer[DEVICE_NAME_FIELD_LENGTH] = (byte) (width >> 8);
//        buffer[DEVICE_NAME_FIELD_LENGTH + 1] = (byte) width;
//        buffer[DEVICE_NAME_FIELD_LENGTH + 2] = (byte) (height >> 8);
//        buffer[DEVICE_NAME_FIELD_LENGTH + 3] = (byte) height;
        ControlMessageProto.ControlMessage controlMessage = ControlMessageProto.ControlMessage.newBuilder()
                .setText(deviceName)
                .setType(SocketConstants.TYPE_DEVICE_INFO)
                .setHeight(height)
                .setWidth(width)
                .build();
        byte[] bytes = controlMessage.toByteArray();
        ByteBuffer byteBuffer = ByteBuffer.allocate(2 + 1 + 4 + bytes.length);
        byteBuffer.putShort(SocketConstants.DATA_BEGIN);
        byteBuffer.put(SocketConstants.CONTROLMSG_TYPE);
        byteBuffer.putInt(bytes.length);
        byteBuffer.put(bytes);
        byteBuffer.flip();
        IO.writeFully(controlFd, byteBuffer);
    }

    public FileDescriptor getVideoFd() {
        return videoFd;
    }

    public ControlMessage receiveControlMessage() throws IOException {
        ControlMessage msg = reader.next();
        while (msg == null) {
            reader.readFrom(controlInputStream);
            msg = reader.next();
        }
        Ln.d(msg.toString());
        return msg;
    }

    public ControlMessageProto.ControlMessage newReceiveControlMessage() throws IOException {
        ControlMessageProto.ControlMessage msg = reader.newNext();
        while (msg == null) {
            reader.readFrom(controlInputStream);
            msg = reader.newNext();
        }
        Ln.d("msg.getType() = " + msg.getType());
        return msg;
    }

    public void sendDeviceMessage(DeviceMessage msg) throws IOException {
        writer.writeTo(msg, controlOutputStream);
    }
}
