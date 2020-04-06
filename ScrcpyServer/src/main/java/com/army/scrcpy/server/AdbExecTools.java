package com.army.scrcpy.server;

import org.apache.commons.lang3.StringUtils;
import org.zeroturnaround.exec.ProcessExecutor;
import org.zeroturnaround.exec.ProcessResult;
import org.zeroturnaround.exec.stream.LogOutputStream;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Future;

public class AdbExecTools {
    /**
     * adb在环境变量中的key
     */
    public static final String ABD_ENV_KEY = "ADB_HOME";
    public static final String SOCKET_NAME = "localabstract:scrcpy";
    public static final String DEVICE_SERVER_PATH = "/data/local/tmp/scrcpy-server.jar";

    public static boolean adbPush(String localFilePath) {
        List<String> results = doCommand("adb", "push", localFilePath, DEVICE_SERVER_PATH);
        return isSuccess(results);
    }

    public static boolean adbReverse(int localPort) {
        List<String> results = doCommand("adb", "reverse", SOCKET_NAME, "tcp:" + localPort);
        return isSuccess(results);
    }

    public static boolean adbForward(int localPort) {
        List<String> results = doCommand("adb", "forward", "tcp:" + localPort, SOCKET_NAME);
        return isSuccess(results);
    }

    /**
     * 如果socket连接断开，需要把这条命令取消掉，执行future.cancel(true)
     */
    public static Future<ProcessResult> adbProcess(boolean isForward) {
        return doCommandInBackground("adb", "shell", "CLASSPATH=" + DEVICE_SERVER_PATH,
                "app_process", "/", "com.genymobile.scrcpy.Server",
                "1.12.1", "0", "8000000", "0", isForward ? "true" : "false", "-", "true", "true");
    }

    public static void adbProcess2(boolean isForward) {
        doCommand("adb", "shell", "CLASSPATH=" + DEVICE_SERVER_PATH,
                "app_process", "/", "com.genymobile.scrcpy.Server",
                "1.12.1", "0", "8000000", "0", isForward ? "true" : "false", "-", "true", "true");
    }

    private static boolean isSuccess(List<String> results) {
        for (String result : results) {
            if (result.toLowerCase().contains("error")) {
                return false;
            }
        }
        return true;
    }

    private static String getAdbPath() {
        String adbPath = System.getenv(ABD_ENV_KEY);
        if (StringUtils.isEmpty(adbPath)) {
            throw new IllegalStateException("请在环境变量中配置adb的路径，key为ADB_HOME");
        }
        return adbPath;
    }

    private static List<String> doCommand(String... cmd) {
        String adbPath = getAdbPath();
        try {
            List<String> list = new ArrayList<>();
            new ProcessExecutor().command(cmd)
                    .directory(new File(adbPath))
                    .redirectOutput(new LogOutputStream() {
                        @Override
                        protected void processLine(String s) {
                            System.out.println(s);
                            list.add(s);
                        }
                    }).execute();
            return list;
        } catch (Exception e) {
            e.printStackTrace();
            return new ArrayList<>();
        }
    }

    private static Future<ProcessResult> doCommandInBackground(String... cmd) {
        String adbPath = getAdbPath();
        try {
            return new ProcessExecutor().command(cmd)
                    .directory(new File(adbPath))
                    .start().getFuture();
        } catch (Exception e) {
            e.printStackTrace();
        }
        return null;
    }

    private static Future<ProcessResult> resultFuture = null;

    public static void main(String[] args) throws Exception{
        adbReverse(Server.APP_PORT);
        adbPush(new File("scrcpy-server.jar").getAbsoluteFile().getAbsolutePath());
        new Thread(){
            @Override
            public void run() {
                resultFuture = adbProcess(false);
                try {
                    resultFuture.get();
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        }.start();
        new Thread(){
            @Override
            public void run() {
                try {
                    sleep(2000);
                    resultFuture.cancel(true);
                    for (int i = 0; i < 10; i++) {
                            sleep(500);
                        System.out.println(i);
                    }
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
            }
        }.start();
    }
}
