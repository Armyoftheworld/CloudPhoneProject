package com.army.scrcpy.server;

import io.netty.bootstrap.Bootstrap;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.codec.http.websocketx.BinaryWebSocketFrame;
import io.netty.handler.stream.ChunkedWriteHandler;

import java.io.File;
import java.util.HashMap;
import java.util.Map;

public class Server {

    public static final int APP_PORT = 27183;
    public static final int WEB_PORT = 27184;
    public static final int DEVICE_NAME_FIELD_LENGTH = 64;

    public static Map<String, SocketChannelData> repositoryMap = new HashMap<>(16);
    public static Map<String, String> addrMap = new HashMap<>(16);

    public static void main(String[] args) {
        try {
            startWebSocketServer();
//            prepareConnectPhone("local");
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public static void prepareConnectPhone(String localAddr) throws Exception {
        new Thread(() -> {
            try {
                String path = "/Volumes/2T/Mac/IdeaProjects/CloudPhoneProject/scrcpy/server/build/outputs/apk/debug/server-debug.apk";
//                String path = "scrcpy-server.jar";
                boolean success = AdbExecTools.adbPush(new File(path).getAbsolutePath());
                if (!success) {
                    System.out.println("adb push fail");
                    return;
                }
                boolean isForward;
                success = AdbExecTools.adbReverse(APP_PORT);
                if (!success) {
                    success = AdbExecTools.adbForward(APP_PORT);
                    isForward = success;
                } else {
                    isForward = false;
                }
                if (!success) {
                    System.out.println("adb reverse/forward fail");
                    return;
                }
                if (isForward) {
                    startAdbProcess(true);
                    // 等adbProcess启动完成
                    Thread.sleep(500);
                    startSocketClient(localAddr);
                    return;
                }
                startSocketServer(localAddr);
            } catch (Exception e) {
                e.printStackTrace();
            }
        }).start();
    }

    private static void startWebSocketServer() throws Exception {
        EventLoopGroup bossGroup = new NioEventLoopGroup(); //bossGroup就是parentGroup，是负责处理TCP/IP连接的
        EventLoopGroup workerGroup = new NioEventLoopGroup(); //workerGroup就是childGroup,是负责处理Channel(通道)的I/O事件

        ServerBootstrap sb = new ServerBootstrap();
        sb.group(bossGroup, workerGroup)
                .channel(NioServerSocketChannel.class)
                .option(ChannelOption.SO_BACKLOG, 128) //初始化服务端可连接队列,指定了队列的大小128
                .childOption(ChannelOption.SO_KEEPALIVE, true) //保持长连接
                .childHandler(new ChannelInitializer<SocketChannel>() {  // 绑定客户端连接时候触发操作
                    @Override
                    protected void initChannel(SocketChannel sh) {
                        System.out.println("正在连接中..." + sh);
                        // 设置解码器
                        sh.pipeline().addLast(new HttpServerCodec());
                        // 聚合器，使用websocket会用到
                        sh.pipeline().addLast(new HttpObjectAggregator(65536));
                        // 用于大数据的分区传输
                        sh.pipeline().addLast(new ChunkedWriteHandler());
                        sh.pipeline()
                                .addLast(new WebServerHandler()); //使用ServerHandler类来处理接收到的消息
                    }
                });
        //绑定监听端口，调用sync同步阻塞方法等待绑定操作完
        ChannelFuture future = sb.bind(Server.WEB_PORT).sync();
        if (future.isSuccess()) {
            System.out.println("webSocket服务端启动成功");
        } else {
            System.out.println("webSocket服务端启动失败");
            future.cause().printStackTrace();
            bossGroup.shutdownGracefully(); //关闭线程组
            workerGroup.shutdownGracefully();
        }
        //成功绑定到端口之后,给channel增加一个 管道关闭的监听器并同步阻塞,直到channel关闭,线程才会往下执行,结束进程。
        future.channel().closeFuture().sync();
    }

    private static void startSocketClient(String localAddr) throws Exception {
        final EventLoopGroup group = new NioEventLoopGroup();
        Bootstrap b = new Bootstrap();
        b.group(group).channel(NioSocketChannel.class)  // 使用NioSocketChannel来作为连接用的channel类
                .handler(new ChannelInitializer<SocketChannel>() { // 绑定连接初始化器
                    @Override
                    public void initChannel(SocketChannel ch) {
                        System.out.println("正在连接中..." + ch);
                        ChannelPipeline pipeline = ch.pipeline();
                        pipeline.addLast(new MessageDecoder());
                        pipeline.addLast(new ClientHandler(localAddr)); //客户端处理类

                    }
                });
        //发起异步连接请求，绑定连接端口和host信息
        final ChannelFuture videoFuture = b.connect("127.0.0.1", Server.APP_PORT).sync();

        videoFuture.addListener((ChannelFutureListener) arg0 -> {
            if (arg0.isSuccess()) {
                System.out.println("videoFuture 连接服务器成功");
            } else {
                System.out.println("videoFuture 连接服务器失败");
                arg0.cause().printStackTrace();
                group.shutdownGracefully(); //关闭线程组
            }
        });

        final ChannelFuture controlFuture = b.connect("127.0.0.1", Server.APP_PORT).sync();
        controlFuture.addListener((ChannelFutureListener) arg0 -> {
            if (arg0.isSuccess()) {
                System.out.println("controlFuture 连接服务器成功");
            } else {
                System.out.println("controlFuture 连接服务器失败");
                arg0.cause().printStackTrace();
                group.shutdownGracefully(); //关闭线程组
            }
        });
    }

    private static void startSocketServer(String localAddr) throws Exception {
        EventLoopGroup bossGroup = new NioEventLoopGroup(); //bossGroup就是parentGroup，是负责处理TCP/IP连接的
        EventLoopGroup workerGroup = new NioEventLoopGroup(); //workerGroup就是childGroup,是负责处理Channel(通道)的I/O事件

        ServerBootstrap sb = new ServerBootstrap();
        sb.group(bossGroup, workerGroup)
                .channel(NioServerSocketChannel.class)
                .option(ChannelOption.SO_BACKLOG, 128) //初始化服务端可连接队列,指定了队列的大小128
                .childOption(ChannelOption.SO_KEEPALIVE, true) //保持长连接
                .childHandler(new ChannelInitializer<SocketChannel>() {  // 绑定客户端连接时候触发操作
                    @Override
                    protected void initChannel(SocketChannel sh) {
                        System.out.println("正在连接中..." + sh);
                        putChannelId(sh, false);
                        associateWebAppAddr(localAddr, sh.localAddress().toString());
                        sh.pipeline().addLast(new MessageDecoder());
                        sh.pipeline()
                                .addLast(new AppServerHandler()); //使用ServerHandler类来处理接收到的消息
                    }
                });
        //绑定监听端口，调用sync同步阻塞方法等待绑定操作完
        ChannelFuture future = sb.bind(Server.APP_PORT).sync();
        if (future.isSuccess()) {
            System.out.println("服务端启动成功");
            startAdbProcess(false);
        } else {
            System.out.println("服务端启动失败");
            future.cause().printStackTrace();
            bossGroup.shutdownGracefully(); //关闭线程组
            workerGroup.shutdownGracefully();
        }
        //成功绑定到端口之后,给channel增加一个 管道关闭的监听器并同步阻塞,直到channel关闭,线程才会往下执行,结束进程。
        future.channel().closeFuture().sync();
    }

    public static synchronized void putChannelId(SocketChannel channel, boolean isForward) {
        String addr = isForward ? channel.remoteAddress().toString() : channel.localAddress().toString();
        Map<String, SocketChannelData> map = Server.repositoryMap;
        SocketChannelData socketChannelData = map.get(addr);
        if (socketChannelData == null) {
            socketChannelData = new SocketChannelData();
            map.put(addr, socketChannelData);
        }
        socketChannelData.putChannelId(channel, isForward);
    }

    public static void associateWebAppAddr(String webLocalAddr, String appAddr) {
        addrMap.put(webLocalAddr, appAddr);
        addrMap.put(appAddr, webLocalAddr);
    }

    public static void writeWebSocket(String appLocalAddr, ByteBuf data, boolean isVideo) {
        SocketChannelData webChannelData = repositoryMap.get(addrMap.get(appLocalAddr));
        if (webChannelData == null) {
            System.out.println("没有找到websSocket数据");
            return;
        }
        BinaryWebSocketFrame frame;
        if (isVideo) {
            data.skipBytes(8);
            byte yuvType = data.readByte();
            if (yuvType == SocketConstants.TYPE_YUV420SP) {
                int area = data.readInt();
                byte[] yuv420spData = new byte[data.readableBytes()];
                data.readBytes(yuv420spData);
                frame = new BinaryWebSocketFrame(Unpooled.wrappedBuffer(yuv420spToYuv420P(yuv420spData, area)));
            } else if (yuvType == SocketConstants.TYPE_YUV420P){
                data.skipBytes(4);
                frame = new BinaryWebSocketFrame(data);
            } else {
                System.out.println("不支持的yuv420格式，yuvType = " + yuvType);
                return;
            }
            webChannelData.writeVideo(frame);
        } else {
            frame = new BinaryWebSocketFrame(data);
            webChannelData.writeControl(frame);
        }
    }

    private static byte[] yuv420spToYuv420P(byte[] yuv420spData, int area) {
        byte[] yuv420pData = new byte[area * 3 / 2];
        int ySize = area;
        System.arraycopy(yuv420spData, 0, yuv420pData, 0, ySize);   //拷贝 Y 分量

        for (int j = 0, i = 0; j < ySize / 2; j += 2, i++) {
            yuv420pData[ySize + i] = yuv420spData[ySize + j];   //U 分量
            yuv420pData[ySize * 5 / 4 + i] = yuv420spData[ySize + j + 1];   //V 分量
        }
        return yuv420pData;
    }

    public static void writeAppControlSocket(String webLocalAddr, Object data) {
        SocketChannelData appSocketData = repositoryMap.get(addrMap.get(webLocalAddr));
        if (appSocketData == null) {
            System.out.println("没有找到appSocket数据");
            return;
        }
        appSocketData.writeControl(data);
    }

    private static void startAdbProcess(boolean isForward) {
        new Thread(() -> {
            try {
                // 需要处理当socket连接断开，把这个thread取消掉
                AdbExecTools.adbProcess2(isForward);
            } catch (Exception e) {
                e.printStackTrace();
            }
        }).start();
    }
}
