package com.genymobile.scrcpy;

import android.graphics.Rect;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.view.Surface;
import com.genymobile.scrcpy.wrappers.SurfaceControl;

import java.io.FileDescriptor;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * mediacodec异步解码有问题，放弃
 */
public class ScreenEncoderAsyncDecoder extends MediaCodec.Callback implements Device.RotationListener {

    private static final int DEFAULT_I_FRAME_INTERVAL = 10; // seconds
    private static final int REPEAT_FRAME_DELAY_US = 100_000; // repeat after 100ms

    private static final int NO_PTS = -1;

    private final AtomicBoolean rotationChanged = new AtomicBoolean();
    private final ByteBuffer headerBuffer = ByteBuffer.allocate(3 + 12 + 1 + 4);

    private int bitRate;
    private int maxFps;
    private int iFrameInterval;
    private boolean sendFrameMeta;
    private long ptsOrigin;
    private FileDescriptor fd;
    private boolean decoderStarted = false;

    private final static ArrayBlockingQueue<byte []> mInputDatasQueue = new ArrayBlockingQueue<>(32);

    public ScreenEncoderAsyncDecoder(boolean sendFrameMeta, int bitRate, int maxFps, int iFrameInterval) {
        this.sendFrameMeta = sendFrameMeta;
        this.bitRate = bitRate;
        this.maxFps = maxFps;
        this.iFrameInterval = iFrameInterval;
    }

    public ScreenEncoderAsyncDecoder(boolean sendFrameMeta, int bitRate, int maxFps) {
        this(sendFrameMeta, bitRate, maxFps, DEFAULT_I_FRAME_INTERVAL);
    }

    @Override
    public void onRotationChanged(int rotation) {
        rotationChanged.set(true);
    }

    public boolean consumeRotationChange() {
        return rotationChanged.getAndSet(false);
    }

    public void streamScreen(Device device, FileDescriptor fd) throws IOException {
        this.fd = fd;
        Workarounds.prepareMainLooper();
        Workarounds.fillAppInfo();

        MediaFormat encoderFormat = createEncoderFormat(bitRate, maxFps, iFrameInterval);
        MediaFormat decoderFormat = createDecoderFormat();
        HandlerThread videoDecoderThread = new HandlerThread("VideoDecoder");
        videoDecoderThread.start();
        Handler mVideoDecoderHandler = new Handler(videoDecoderThread.getLooper());
        device.setRotationListener(this);
        boolean alive;
        try {
            do {
                MediaCodec encoder = createEncoder();
                MediaCodec decoder = createDecoder();
                IBinder display = createDisplay();
                Rect contentRect = device.getScreenInfo().getContentRect();
                Rect videoRect = device.getScreenInfo().getVideoSize().toRect();
                setSize(encoderFormat, videoRect.width(), videoRect.height());
                setSize(decoderFormat, videoRect.width(), videoRect.height());
                configureCoder(encoder, encoderFormat, true);
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    decoder.setCallback(this, mVideoDecoderHandler);
                } else {
                    decoder.setCallback(this);
                }
                configureCoder(decoder, decoderFormat, false);
                Surface surface = encoder.createInputSurface();
                setDisplaySurface(display, surface, contentRect, videoRect);
                encoder.start();
                try {
                    alive = encodeAndDecoder(encoder, decoder);
                    // do not call stop() on exception, it would trigger an IllegalStateException
                    encoder.stop();
                    decoder.stop();
                } finally {
                    destroyDisplay(display);
                    encoder.release();
                    decoder.release();
                    surface.release();
                }
            } while (alive);
        } finally {
            device.setRotationListener(null);
        }
    }

    private boolean encodeAndDecoder(MediaCodec codec, MediaCodec decoder) throws IOException {
        boolean eof = false;
        MediaCodec.BufferInfo encoderInfo = new MediaCodec.BufferInfo();

        while (!consumeRotationChange() && !eof) {
            int outputBufferId = codec.dequeueOutputBuffer(encoderInfo, -1);
            eof = (encoderInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
            try {
                if (consumeRotationChange()) {
                    // must restart encoding with new size
                    break;
                }
                if (!decoderStarted) {
                    decoderStarted = true;
                    decoder.start();
                }
                if (outputBufferId >= 0) {
                    ByteBuffer codecBuffer = codec.getOutputBuffer(outputBufferId);
                    byte[] data = new byte[codecBuffer.remaining()];
                    codecBuffer.get(data);
                    mInputDatasQueue.offer(data);
                }
            } finally {
                if (outputBufferId >= 0) {
                    codec.releaseOutputBuffer(outputBufferId, false);
                }
            }
        }

        return !eof;
    }
    private void writeFrameMeta(FileDescriptor fd, int packetSize, MediaFormat format) throws IOException {
        headerBuffer.clear();

        headerBuffer.putShort(SocketConstants.DATA_BEGIN);
        headerBuffer.put(SocketConstants.VIDEOSTREAM_TYPE);
        // 把下面的三个值的长度也算进去
        headerBuffer.putInt(packetSize + 8 + 1 + 4);
        headerBuffer.putLong(0);
        headerBuffer.put((byte)format.getInteger(MediaFormat.KEY_COLOR_FORMAT));
        int width = format.getInteger(MediaFormat.KEY_WIDTH);
        int height = format.getInteger(MediaFormat.KEY_HEIGHT);
        headerBuffer.putInt(width * height);
        headerBuffer.flip();
        IO.writeFully(fd, headerBuffer);
    }

    private static MediaCodec createEncoder() throws IOException {
        return MediaCodec.createEncoderByType("video/avc");
    }

    private static MediaCodec createDecoder() throws IOException {
        return MediaCodec.createDecoderByType("video/avc");
    }

    @SuppressWarnings("checkstyle:MagicNumber")
    private static MediaFormat createEncoderFormat(int bitRate, int maxFps, int iFrameInterval) {
        MediaFormat format = new MediaFormat();
        format.setString(MediaFormat.KEY_MIME, MediaFormat.MIMETYPE_VIDEO_AVC);
        format.setInteger(MediaFormat.KEY_BIT_RATE, bitRate);
        // must be present to configure the encoder, but does not impact the actual frame rate, which is variable
        format.setInteger(MediaFormat.KEY_FRAME_RATE, 30);
        format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
        format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, iFrameInterval);
        // display the very first frame, and recover from bad quality when no new frames
        format.setLong(MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER, REPEAT_FRAME_DELAY_US); // µs
        if (maxFps > 0) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                format.setFloat(MediaFormat.KEY_MAX_FPS_TO_ENCODER, maxFps);
            } else {
                Ln.w("Max FPS is only supported since Android 10, the option has been ignored");
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            format.setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline);
            format.setInteger(MediaFormat.KEY_LEVEL, MediaCodecInfo.CodecProfileLevel.AVCLevel4);
        }
        return format;
    }

    private static MediaFormat createDecoderFormat() {
        MediaFormat format = new MediaFormat();
        format.setString(MediaFormat.KEY_MIME, MediaFormat.MIMETYPE_VIDEO_AVC);
        format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible);
        return format;
}

    private static IBinder createDisplay() {
        return SurfaceControl.createDisplay("scrcpy", true);
    }

    private static void configureCoder(MediaCodec codec, MediaFormat format, boolean encoder) {
        codec.configure(format, null, null, encoder ? MediaCodec.CONFIGURE_FLAG_ENCODE : 0);
    }

    private static void setSize(MediaFormat format, int width, int height) {
        format.setInteger(MediaFormat.KEY_WIDTH, width);
        format.setInteger(MediaFormat.KEY_HEIGHT, height);
    }

    private static void setDisplaySurface(IBinder display, Surface surface, Rect deviceRect, Rect displayRect) {
        SurfaceControl.openTransaction();
        try {
            SurfaceControl.setDisplaySurface(display, surface);
            SurfaceControl.setDisplayProjection(display, 0, deviceRect, displayRect);
            SurfaceControl.setDisplayLayerStack(display, 0);
        } finally {
            SurfaceControl.closeTransaction();
        }
    }

    private static void destroyDisplay(IBinder display) {
        SurfaceControl.destroyDisplay(display);
    }

    @Override
    public void onInputBufferAvailable(MediaCodec codec, int index) {
        Ln.d("-----------------------onInputBufferAvailable-----------------------");
        Ln.d("mInputDatasQueue.size() = " + mInputDatasQueue.size());
        if (mInputDatasQueue.isEmpty()) {
            return;
        }
        ByteBuffer inputBuffer = codec.getInputBuffer(index);
        if (inputBuffer == null) {
            codec.queueInputBuffer(index,0, 0,0,0);
            return;
        }
        inputBuffer.clear();
        byte[] bytes = mInputDatasQueue.poll();
        inputBuffer.put(bytes);
        codec.queueInputBuffer(index,0, bytes.length,0,0);
    }

    @Override
    public void onOutputBufferAvailable(MediaCodec codec, int index, MediaCodec.BufferInfo info) {
        Ln.d("-----------------------onOutputBufferAvailable-----------------------");
        try {
            ByteBuffer outputBuffer = codec.getOutputBuffer(index);
            if (outputBuffer != null && info != null && info.size > 0) {
                if (sendFrameMeta) {
                    writeFrameMeta(fd, outputBuffer.remaining(), codec.getOutputFormat(index));
                }
                IO.writeFully(fd, outputBuffer);
                outputBuffer.clear();
            }
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            codec.releaseOutputBuffer(index, false);
        }
    }

    @Override
    public void onError(MediaCodec codec, MediaCodec.CodecException e) {
        Ln.d(String.format("diagnosticInfo = %s, isRecoverable = %b", e.getDiagnosticInfo(), e.isRecoverable()));
    }

    @Override
    public void onOutputFormatChanged(MediaCodec codec, MediaFormat format) {

    }
}
