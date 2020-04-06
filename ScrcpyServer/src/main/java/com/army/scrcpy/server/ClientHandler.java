package com.army.scrcpy.server;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.socket.SocketChannel;


public class ClientHandler extends SimpleChannelInboundHandler<ByteBuf> {

    private int count = 1;
    private boolean hasReadDevice = false;
    private String webLocalAddr;

    public ClientHandler(String localAddr) {
        this.webLocalAddr = localAddr;
    }

    //处理服务端返回的数据
    @Override
    protected void channelRead0(ChannelHandlerContext ctx, ByteBuf byteBuf) {
        String addr = ctx.channel().remoteAddress().toString();
        byte type = byteBuf.readByte();
        Server.writeWebSocket(addr, byteBuf, type == SocketConstants.VIDEOSTREAM_TYPE);
    }


    @Override
    public void channelRegistered(ChannelHandlerContext ctx) throws Exception {
        super.channelRegistered(ctx);
        System.out.println("channelRegistered channel = " + ctx.channel());
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        super.channelInactive(ctx);
        System.out.println("channelInactive");
    }

    @Override
    public void channelActive(ChannelHandlerContext ctx) throws Exception {
        super.channelActive(ctx);
        Server.associateWebAppAddr(webLocalAddr, ctx.channel().remoteAddress().toString());
        Server.putChannelId((SocketChannel) ctx.channel(), true);
        System.out.println("channelActive channel = " + ctx.channel() + "\t"
                + Server.repositoryMap.get(ctx.channel().remoteAddress().toString()));
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
        cause.printStackTrace();
        ctx.close();
    }


}
