package com.army.scrcpy.server;

import io.netty.channel.Channel;
import io.netty.channel.socket.SocketChannel;
import org.apache.commons.lang3.StringUtils;


public class SocketChannelData {

    private SocketChannel videoChannel = null;
    private SocketChannel controlChannel = null;
    private int tempPort = -1;
    private boolean allConnected = false;

    public void putChannelId(SocketChannel channel, boolean isForward) {
        // 端口号小的是先连的，即controlSocket
        int port = isForward ? channel.localAddress().getPort() : channel.remoteAddress().getPort();
        if (tempPort == -1) {
            tempPort = port;
            controlChannel = channel;
            return;
        }
        if (port > tempPort) {
            videoChannel = channel;
        } else {
            videoChannel = controlChannel;
            controlChannel = channel;
        }
        allConnected = videoChannel != null && controlChannel != null;
    }

    public boolean isVideo(Channel channel) {
        return videoChannel.id().asShortText().equals(channel.id().asShortText());
    }

    public boolean isAllConnected() {
        return allConnected;
    }

    public void writeControl(Object data) {
        if (controlChannel != null) {
            controlChannel.writeAndFlush(data);
        }
    }

    public void writeVideo(Object data) {
        if (videoChannel != null) {
            videoChannel.writeAndFlush(data);
        }
    }

    @Override
    public String toString() {
        return "SocketChannelData{" +
                "videoChannel='" + videoChannel + '\'' +
                ", controlChannel='" + controlChannel + '\'' +
                '}';
    }
}
