package com.army.scrcpy.server;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;



public class AppServerHandler extends SimpleChannelInboundHandler<ByteBuf> {

    @Override
    public void channelRead0(ChannelHandlerContext ctx, ByteBuf byteBuf) throws Exception {
        String addr = ctx.channel().localAddress().toString();
        byte type = byteBuf.readByte();
        if (type == SocketConstants.CONTROLMSG_TYPE) {
            Server.writeWebSocket(addr, Unpooled.copiedBuffer(byteBuf), false);
            return;
        }
        if (type == SocketConstants.VIDEOSTREAM_TYPE) {
            int pts = (int) byteBuf.readLong();
            byte[] frame = new byte[byteBuf.readableBytes()];
            byteBuf.readBytes(frame);
            System.out.println("pts = " + pts);
            PushStreamTools.pushRawStream(frame, pts, frame.length);
        }
    }

    @Override
    public void channelReadComplete(ChannelHandlerContext ctx) throws Exception {
        super.channelReadComplete(ctx);
    }

    @Override
    public void channelRegistered(ChannelHandlerContext ctx) throws Exception {
        super.channelRegistered(ctx);
        System.out.println("appSocket channelRegistered channel = " + ctx.channel());
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        super.channelInactive(ctx);
        System.out.println("appSocket channelInactive");
    }

    @Override
    public void channelActive(ChannelHandlerContext ctx) throws Exception {
        super.channelActive(ctx);
        System.out.println("appSocket channelActive channel = " + ctx.channel());

    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
        cause.printStackTrace();
        ctx.close();
    }
}
