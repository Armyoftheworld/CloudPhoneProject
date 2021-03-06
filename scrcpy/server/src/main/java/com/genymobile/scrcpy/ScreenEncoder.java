package com.genymobile.scrcpy;

import android.graphics.Rect;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.os.Build;
import android.os.IBinder;
import android.view.Surface;
import com.genymobile.scrcpy.wrappers.SurfaceControl;

import java.io.FileDescriptor;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicBoolean;

public class ScreenEncoder implements Device.RotationListener {

    private static final int DEFAULT_I_FRAME_INTERVAL = 10; // seconds
    private static final int REPEAT_FRAME_DELAY_US = 100_000; // repeat after 100ms
    private static final long DECODER_WAIT_TIME_US = 5_000;

    private static final int NO_PTS = -1;

    private final AtomicBoolean rotationChanged = new AtomicBoolean();
    private final ByteBuffer headerBuffer = ByteBuffer.allocate(3 + 12 + 1 + 4);

    private int bitRate;
    private int maxFps;
    private int iFrameInterval;
    private boolean sendFrameMeta;
    private long ptsOrigin;

    public ScreenEncoder(boolean sendFrameMeta, int bitRate, int maxFps, int iFrameInterval) {
        this.sendFrameMeta = sendFrameMeta;
        this.bitRate = bitRate;
        this.maxFps = maxFps;
        this.iFrameInterval = iFrameInterval;
    }

    public ScreenEncoder(boolean sendFrameMeta, int bitRate, int maxFps) {
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
        Workarounds.prepareMainLooper();
        Workarounds.fillAppInfo();

        MediaFormat encoderFormat = createEncoderFormat(bitRate, maxFps, iFrameInterval);
        MediaFormat decoderFormat = createDecoderFormat();
        device.setRotationListener(this);
        boolean canSetProfile = true;
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
                configureCoder(decoder, decoderFormat, false);
                Surface surface = encoder.createInputSurface();
                setDisplaySurface(display, surface, contentRect, videoRect);
                encoder.start();
                decoder.start();
                try {
                    alive = encodeAndDecoder(encoder, fd, decoder);
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

    private boolean encodeAndDecoder(MediaCodec codec, FileDescriptor fd, MediaCodec decoder) throws IOException {
        boolean eof = false;
        MediaCodec.BufferInfo encoderInfo = new MediaCodec.BufferInfo();
        MediaCodec.BufferInfo decoderInfo = new MediaCodec.BufferInfo();

        while (!consumeRotationChange() && !eof) {
            int outputBufferId = codec.dequeueOutputBuffer(encoderInfo, -1);
            eof = (encoderInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
            try {
                if (consumeRotationChange()) {
                    // must restart encoding with new size
                    break;
                }
                if (outputBufferId >= 0) {
                    ByteBuffer codecBuffer = codec.getOutputBuffer(outputBufferId);
                    decodeBuffer(decoder, codecBuffer, decoderInfo, fd, encoderInfo);
                }
            } finally {
                if (outputBufferId >= 0) {
                    codec.releaseOutputBuffer(outputBufferId, false);
                }
            }
        }

        return !eof;
    }

    private void decodeBuffer(MediaCodec decoder, ByteBuffer codecBuffer, MediaCodec.BufferInfo decoderInfo,
                              FileDescriptor fd, MediaCodec.BufferInfo encoderInfo) throws IOException {
        int inputBufferIndex = decoder.dequeueInputBuffer(-1);
        if (inputBufferIndex >= 0) {
            ByteBuffer inputBuffer = decoder.getInputBuffer(inputBufferIndex);
            if (inputBuffer != null) {
                inputBuffer.clear();
                int length = codecBuffer.remaining();
                inputBuffer.put(codecBuffer);
                decoder.queueInputBuffer(inputBufferIndex, 0, length, getPts(encoderInfo), 0);
            }
        }
        int outputBufferIndex = decoder.dequeueOutputBuffer(decoderInfo, DECODER_WAIT_TIME_US);
        while (outputBufferIndex >= 0) {
            ByteBuffer outputBuffer = decoder.getOutputBuffer(outputBufferIndex);
            if (outputBuffer != null) {
                outputBuffer.position(0);
                outputBuffer.limit(decoderInfo.offset + decoderInfo.size);

                if (sendFrameMeta) {
                    writeFrameMeta(fd, encoderInfo, outputBuffer.remaining(), decoder.getOutputFormat());
                }

                IO.writeFully(fd, outputBuffer);
                decoder.releaseOutputBuffer(outputBufferIndex, false);
                outputBuffer.clear();
            }
            outputBufferIndex = decoder.dequeueOutputBuffer(decoderInfo, DECODER_WAIT_TIME_US);
        }
    }

    private long getPts(MediaCodec.BufferInfo bufferInfo) {
        long pts;
        if ((bufferInfo.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
            pts = NO_PTS; // non-media data packet
        } else {
            if (ptsOrigin == 0) {
                ptsOrigin = bufferInfo.presentationTimeUs;
            }
            pts = bufferInfo.presentationTimeUs - ptsOrigin;
        }
        return pts;
    }

    private void writeFrameMeta(FileDescriptor fd, MediaCodec.BufferInfo bufferInfo, int packetSize, MediaFormat format) throws IOException {
        headerBuffer.clear();

        headerBuffer.putShort(SocketConstants.DATA_BEGIN);
        headerBuffer.put(SocketConstants.VIDEOSTREAM_TYPE);
        // 把下面的三个值的长度也算进去
        headerBuffer.putInt(packetSize + 8 + 1 + 4);
        headerBuffer.putLong(getPts(bufferInfo));
        headerBuffer.put((byte) format.getInteger(MediaFormat.KEY_COLOR_FORMAT));
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
}
