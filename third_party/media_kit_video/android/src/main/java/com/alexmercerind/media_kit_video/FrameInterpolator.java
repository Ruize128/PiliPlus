package com.alexmercerind.media_kit_video;

import android.app.ActivityManager;
import android.content.Context;
import android.graphics.SurfaceTexture;
import android.opengl.EGL14;
import android.opengl.EGLConfig;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.EGLExt;
import android.opengl.EGLSurface;
import android.opengl.GLES11Ext;
import android.opengl.GLES30;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;
import android.view.Choreographer;
import android.view.Surface;

import java.util.ArrayDeque;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Raises the frame rate of 24-30 fps video 2x or 4x with classic
 * motion-compensated interpolation on the GPU (OpenGL ES 3.0), for PiliPlus's 补帧.
 * <p>
 * mpv renders into {@link #inputSurface()}. For each new frame, a luma pyramid
 * is built and motion is found coarse to fine: a full search on the smallest
 * level (about 135 px tall), then refined level by level up to the analysis
 * size (the video's own resolution for 画质, at most 540p for 效率). Matching
 * is bilateral, at the midpoint between the previous frame and this one, so the
 * vectors belong to the in-between frame itself. That frame is warped from both
 * frames along them at the video's resolution, and both frames are drawn into
 * Flutter's texture surface at timestamps paced by Choreographer: the in-between
 * one first, the real one half a frame later. With a multiplier of 4, three
 * frames are warped per pair (at 1/4, 1/2 and 3/4, from the same motion) and
 * spaced evenly, which only shows on a 120 Hz screen.
 * <p>
 * Everything runs on one GL thread. Log tag: "frameinterp".
 */
final class FrameInterpolator implements SurfaceTexture.OnFrameAvailableListener, Choreographer.FrameCallback {
    private static final String TAG = "frameinterp";
    private static final int BLOCK = 8;
    private static final int COARSEST_HEIGHT = 135;
    private static final int COARSE_RANGE = 8;
    // No interpolating across a pause or seek.
    private static final long MAX_GAP_NS = 100_000_000L;
    // Sources at 40 fps and up are shown as they are.
    private static final long FAST_SOURCE_NS = 25_000_000L;
    // The first in-between frame shows about one refresh after its source frame.
    private static final long PRESENT_DELAY_NS = 17_000_000L;
    // How early a refresh may show a frame.
    private static final long DUE_SLACK_NS = 4_000_000L;

    /** OpenGL ES 3.0, which every shader here needs. */
    static boolean isSupported() {
        try {
            final ActivityManager am = (ActivityManager) MediaKitVideoPlugin.activity.getSystemService(Context.ACTIVITY_SERVICE);
            return am.getDeviceConfigurationInfo().reqGlEsVersion >= 0x30000;
        } catch (Throwable e) {
            return false;
        }
    }

    private final HandlerThread thread = new HandlerThread("media_kit_video.frameinterp");
    private final Handler handler;
    private final Surface outputSurface;

    // GL thread state.
    private EGLDisplay display = EGL14.EGL_NO_DISPLAY;
    private EGLContext context = EGL14.EGL_NO_CONTEXT;
    private EGLConfig config;
    private EGLSurface pbuffer = EGL14.EGL_NO_SURFACE;
    private EGLSurface window = EGL14.EGL_NO_SURFACE;
    private int oesTexture;
    private SurfaceTexture inputTexture;
    private Surface inputSurface;
    private Choreographer choreographer;
    private final float[] stMatrix = new float[16];

    private int copyProgram, lumaProgram, downProgram, coarseProgram, refineProgram, smoothProgram, interpolateProgram, presentProgram;
    private int width, height, analysisWidth, analysisHeight, levels;
    private int maxHeight;
    private int multiplier;
    private final int[] frames = new int[3], frameFbos = new int[3];
    // Luma pyramids, one per frame slot: [slot][level], level 0 at the analysis size.
    private final int[][] lumas = new int[2][0], lumaFbos = new int[2][0];
    // In-between frames: (multiplier - 1) per source frame, two frames' worth.
    private int[] middles = new int[0], middleFbos = new int[0];
    // Motion per pyramid level (RGBA16I: x, y in 1/8 px of that level, match cost x 1024).
    private int[] vectors = new int[0], vectorsFbos = new int[0];
    private int smoothVectors, smoothVectorsFbo;
    private int frameSlot = -1, lumaSlot = -1, middleSlot = -1;
    private boolean havePrevious;
    private long lastArrivalNs;
    private double intervalNs = 33_333_333;
    private boolean callbackPosted;
    private boolean released;
    private long lastVsyncNs;
    private double vsyncNs = 16_666_667;

    private static final class Item {
        final int texture;
        final long targetNs;

        Item(int texture, long targetNs) {
            this.texture = texture;
            this.targetNs = targetNs;
        }
    }

    private final ArrayDeque<Item> queue = new ArrayDeque<>();

    // Stats, logged every 5 s.
    private int statFrames, statMiddles, statShown, statDropped;
    private long statSinceNs = System.nanoTime();
    private String status = "";

    /**
     * @param outputSurface Flutter's texture surface; it must not be connected to
     *                      any other producer while this object lives.
     * @param maxHeight     1080 (画质): motion at the video's own resolution;
     *                      otherwise (效率) at most 540p.
     * @param multiplier    2 or 4: output frames per source frame.
     */
    FrameInterpolator(Surface outputSurface, int maxHeight, int multiplier, int width, int height) {
        this.outputSurface = outputSurface;
        this.maxHeight = maxHeight;
        this.multiplier = multiplier >= 4 ? 4 : 2;
        thread.start();
        handler = new Handler(thread.getLooper());
        final CountDownLatch ready = new CountDownLatch(1);
        final RuntimeException[] failure = new RuntimeException[1];
        handler.post(() -> {
            try {
                init(width, height);
            } catch (RuntimeException e) {
                failure[0] = e;
            }
            ready.countDown();
        });
        try {
            ready.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        if (failure[0] != null) {
            release();
            throw failure[0];
        }
    }

    /** The surface mpv renders into. */
    Surface inputSurface() {
        return inputSurface;
    }

    void setSize(int width, int height) {
        handler.post(() -> resize(width, height));
    }

    void setMultiplier(int multiplier) {
        final int value = multiplier >= 4 ? 4 : 2;
        handler.post(() -> {
            if (this.multiplier != value) {
                this.multiplier = value;
                resize(width, height);
            }
        });
    }

    void setMaxHeight(int maxHeight) {
        handler.post(() -> {
            if (this.maxHeight != maxHeight) {
                this.maxHeight = maxHeight;
                resize(width, height);
            }
        });
    }

    /**
     * Waits (up to a second) until the GL thread let go of the output surface,
     * so the caller can hand it to another producer.
     */
    void release() {
        final CountDownLatch done = new CountDownLatch(1);
        final boolean posted = handler.post(() -> {
            try {
                releaseOnGlThread();
            } finally {
                done.countDown();
            }
        });
        if (!posted) return;
        try {
            done.await(1, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void releaseOnGlThread() {
        released = true;
        if (choreographer != null) choreographer.removeFrameCallback(this);
        queue.clear();
        if (inputTexture != null) inputTexture.release();
        if (inputSurface != null) inputSurface.release();
        if (display != EGL14.EGL_NO_DISPLAY) {
            EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT);
            if (window != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, window);
            if (pbuffer != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, pbuffer);
            if (context != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(display, context);
            EGL14.eglReleaseThread();
            EGL14.eglTerminate(display);
        }
        display = EGL14.EGL_NO_DISPLAY;
        thread.quitSafely();
    }

    // ---------------------------------------------------------------- setup

    private void init(int width, int height) {
        display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
        final int[] version = new int[2];
        if (!EGL14.eglInitialize(display, version, 0, version, 1)) throw new RuntimeException("eglInitialize");
        final int[] attributes = {
                EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8,
                EGL14.EGL_RENDERABLE_TYPE, EGLExt.EGL_OPENGL_ES3_BIT_KHR,
                EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT | EGL14.EGL_PBUFFER_BIT,
                EGL14.EGL_NONE,
        };
        final EGLConfig[] configs = new EGLConfig[1];
        final int[] count = new int[1];
        if (!EGL14.eglChooseConfig(display, attributes, 0, configs, 0, 1, count, 0) || count[0] == 0) {
            throw new RuntimeException("eglChooseConfig");
        }
        config = configs[0];
        context = EGL14.eglCreateContext(display, config, EGL14.EGL_NO_CONTEXT,
                new int[]{EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE}, 0);
        if (context == EGL14.EGL_NO_CONTEXT) throw new RuntimeException("eglCreateContext");
        pbuffer = EGL14.eglCreatePbufferSurface(display, config,
                new int[]{EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE}, 0);
        if (!makeCurrent(pbuffer)) throw new RuntimeException("eglMakeCurrent: " + EGL14.eglGetError());

        copyProgram = program(COPY_FS);
        lumaProgram = program(LUMA_FS);
        downProgram = program(DOWN_FS);
        coarseProgram = program(COARSE_FS);
        refineProgram = program(REFINE_FS);
        smoothProgram = program(SMOOTH_FS);
        interpolateProgram = program(INTERPOLATE_FS);
        presentProgram = program(PRESENT_FS);

        final int[] names = new int[1];
        GLES30.glGenTextures(1, names, 0);
        oesTexture = names[0];
        GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTexture);
        GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR);
        GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR);
        inputTexture = new SurfaceTexture(oesTexture);
        inputTexture.setOnFrameAvailableListener(this, handler);
        inputSurface = new Surface(inputTexture);
        choreographer = Choreographer.getInstance();

        resize(width, height);
        setStatus("ready");
    }

    private void resize(int width, int height) {
        if (released || width <= 0 || height <= 0 || !makeCurrent(pbuffer)) return;
        inputTexture.setDefaultBufferSize(width, height);
        if (window != EGL14.EGL_NO_SURFACE) {
            EGL14.eglDestroySurface(display, window);
            window = EGL14.EGL_NO_SURFACE;
        }
        deleteTargets();
        this.width = width;
        this.height = height;
        // 画质: motion at the video's own resolution; 效率: at most 540p.
        analysisHeight = maxHeight >= 1080 ? height : Math.min(height, 540);
        analysisWidth = Math.max(BLOCK, Math.round((float) width * analysisHeight / height));
        // Halve down to about 135 px for the coarse search.
        levels = 1;
        while ((analysisHeight >> levels) >= COARSEST_HEIGHT) levels++;

        for (int i = 0; i < frames.length; i++) {
            frames[i] = texture(width, height, GLES30.GL_RGBA8, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, GLES30.GL_LINEAR);
            frameFbos[i] = framebuffer(frames[i]);
        }
        for (int slot = 0; slot < lumas.length; slot++) {
            lumas[slot] = new int[levels];
            lumaFbos[slot] = new int[levels];
            for (int level = 0; level < levels; level++) {
                lumas[slot][level] = texture(levelSize(analysisWidth, level), levelSize(analysisHeight, level),
                        GLES30.GL_R8, GLES30.GL_RED, GLES30.GL_UNSIGNED_BYTE, GLES30.GL_LINEAR);
                lumaFbos[slot][level] = framebuffer(lumas[slot][level]);
            }
        }
        middles = new int[2 * (multiplier - 1)];
        middleFbos = new int[middles.length];
        for (int i = 0; i < middles.length; i++) {
            middles[i] = texture(width, height, GLES30.GL_RGBA8, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, GLES30.GL_LINEAR);
            middleFbos[i] = framebuffer(middles[i]);
        }
        vectors = new int[levels];
        vectorsFbos = new int[levels];
        for (int level = 0; level < levels; level++) {
            vectors[level] = vectorTexture(level);
            vectorsFbos[level] = framebuffer(vectors[level]);
        }
        smoothVectors = vectorTexture(0);
        smoothVectorsFbo = framebuffer(smoothVectors);
        havePrevious = false;
        queue.clear();
        Log.i(TAG, String.format(Locale.ENGLISH, "%dx%d, %dx, motion at %dx%d in %d levels (%dx%d blocks), coarse search at %dx%d",
                width, height, multiplier, analysisWidth, analysisHeight, levels,
                blocks(analysisWidth), blocks(analysisHeight),
                levelSize(analysisWidth, levels - 1), levelSize(analysisHeight, levels - 1)));
    }

    private int vectorTexture(int level) {
        return texture(blocks(levelSize(analysisWidth, level)), blocks(levelSize(analysisHeight, level)),
                GLES30.GL_RGBA16I, GLES30.GL_RGBA_INTEGER, GLES30.GL_SHORT, GLES30.GL_NEAREST);
    }

    private void deleteTargets() {
        deleteAll(frames, frameFbos);
        deleteAll(middles, middleFbos);
        for (int slot = 0; slot < lumas.length; slot++) deleteAll(lumas[slot], lumaFbos[slot]);
        deleteAll(vectors, vectorsFbos);
        deleteAll(new int[]{smoothVectors}, new int[]{smoothVectorsFbo});
    }

    private static void deleteAll(int[] textures, int[] fbos) {
        GLES30.glDeleteTextures(textures.length, textures, 0);
        GLES30.glDeleteFramebuffers(fbos.length, fbos, 0);
    }

    private static int levelSize(int size, int level) {
        return Math.max(1, (size + (1 << level) - 1) >> level);
    }

    private static int blocks(int size) {
        return (size + BLOCK - 1) / BLOCK;
    }

    // ------------------------------------------------------------ per frame

    @Override
    public void onFrameAvailable(SurfaceTexture surfaceTexture) {
        if (released || !makeCurrent(pbuffer)) return;
        inputTexture.updateTexImage();
        if (width == 0) return;
        inputTexture.getTransformMatrix(stMatrix);

        final long now = System.nanoTime();
        final long interval = now - lastArrivalNs;
        lastArrivalNs = now;
        final boolean continuous = interval < MAX_GAP_NS;
        if (continuous) intervalNs += 0.2 * (interval - intervalNs);

        frameSlot = (frameSlot + 1) % frames.length;
        final int previousLuma = lumaSlot;
        lumaSlot = (lumaSlot + 1) % lumas.length;
        final int previousFrame = (frameSlot + frames.length - 1) % frames.length;

        // mpv's frame into our ring...
        draw(copyProgram, frameFbos[frameSlot], width, height);
        bindExternal(0, oesTexture, "tex", copyProgram);
        GLES30.glUniformMatrix4fv(GLES30.glGetUniformLocation(copyProgram, "st"), 1, false, stMatrix, 0);
        GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, 3);

        // ...and its luma pyramid.
        draw(lumaProgram, lumaFbos[lumaSlot][0], analysisWidth, analysisHeight);
        bind(0, frames[frameSlot], "tex", lumaProgram);
        GLES30.glUniform2f(GLES30.glGetUniformLocation(lumaProgram, "dstSize"), analysisWidth, analysisHeight);
        GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, 3);
        for (int level = 1; level < levels; level++) {
            draw(downProgram, lumaFbos[lumaSlot][level], levelSize(analysisWidth, level), levelSize(analysisHeight, level));
            bind(0, lumas[lumaSlot][level - 1], "tex", downProgram);
            GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, 3);
        }

        statFrames++;
        final boolean fastSource = intervalNs < FAST_SOURCE_NS;
        if (continuous && havePrevious && !fastSource && previousLuma >= 0) {
            // Full search on the coarsest level...
            final int top = levels - 1;
            draw(coarseProgram, vectorsFbos[top], blocks(levelSize(analysisWidth, top)), blocks(levelSize(analysisHeight, top)));
            bind(0, lumas[previousLuma][top], "previous", coarseProgram);
            bind(1, lumas[lumaSlot][top], "current", coarseProgram);
            GLES30.glUniform1i(GLES30.glGetUniformLocation(coarseProgram, "range"), COARSE_RANGE);
            GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, 3);

            // ...refined on every finer level from the one above.
            for (int level = top - 1; level >= 0; level--) {
                draw(refineProgram, vectorsFbos[level], blocks(levelSize(analysisWidth, level)), blocks(levelSize(analysisHeight, level)));
                bind(0, lumas[previousLuma][level], "previous", refineProgram);
                bind(1, lumas[lumaSlot][level], "current", refineProgram);
                bind(2, vectors[level + 1], "parent", refineProgram);
                GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, 3);
            }

            draw(smoothProgram, smoothVectorsFbo, blocks(analysisWidth), blocks(analysisHeight));
            bind(0, vectors[0], "vectors", smoothProgram);
            GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, 3);

            // The in-between frames, at phases k / multiplier, spaced evenly before
            // the real frame.
            final int count = multiplier - 1;
            draw(interpolateProgram, 0, width, height);
            bind(0, frames[previousFrame], "previous", interpolateProgram);
            bind(1, frames[frameSlot], "current", interpolateProgram);
            bind(2, smoothVectors, "vectors", interpolateProgram);
            GLES30.glUniform2f(GLES30.glGetUniformLocation(interpolateProgram, "analysisSize"), analysisWidth, analysisHeight);
            final int phase = GLES30.glGetUniformLocation(interpolateProgram, "phase");
            for (int k = 1; k <= count; k++) {
                middleSlot = (middleSlot + 1) % middles.length;
                GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, middleFbos[middleSlot]);
                GLES30.glUniform1f(phase, (float) k / multiplier);
                GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, 3);
                enqueue(new Item(middles[middleSlot], now + PRESENT_DELAY_NS + (long) ((k - 1) * intervalNs / multiplier)));
            }
            GLES30.glFlush();
            enqueue(new Item(frames[frameSlot], now + PRESENT_DELAY_NS + (long) (count * intervalNs / multiplier)));
            statMiddles += count;
            setStatus("active");
        } else {
            GLES30.glFlush();
            enqueue(new Item(frames[frameSlot], now));
            setStatus(fastSource ? "bypass" : "active");
        }
        havePrevious = true;
        report();
    }

    private void enqueue(Item item) {
        queue.add(item);
        // Fell behind: skip ahead rather than add latency.
        while (queue.size() > 2 * multiplier) {
            queue.poll();
            statDropped++;
        }
        if (!callbackPosted) {
            callbackPosted = true;
            choreographer.postFrameCallback(this);
        }
    }

    @Override
    public void doFrame(long frameTimeNanos) {
        callbackPosted = false;
        if (released) return;
        // The display's real refresh interval, for the log.
        final long sinceVsync = frameTimeNanos - lastVsyncNs;
        if (lastVsyncNs != 0 && sinceVsync < 50_000_000L) vsyncNs += 0.1 * (sinceVsync - vsyncNs);
        lastVsyncNs = frameTimeNanos;
        // The newest item due by this refresh; older due ones are late.
        Item show = null;
        while (!queue.isEmpty() && queue.peek().targetNs <= frameTimeNanos + DUE_SLACK_NS) {
            if (show != null) statDropped++;
            show = queue.poll();
        }
        if (show != null) present(show.texture);
        if (!queue.isEmpty()) {
            callbackPosted = true;
            choreographer.postFrameCallback(this);
        }
    }

    private void present(int texture) {
        if (window == EGL14.EGL_NO_SURFACE) {
            window = EGL14.eglCreateWindowSurface(display, config, outputSurface, new int[]{EGL14.EGL_NONE}, 0);
            if (window == EGL14.EGL_NO_SURFACE) {
                Log.w(TAG, "eglCreateWindowSurface failed: " + EGL14.eglGetError());
                return;
            }
        }
        if (!makeCurrent(window)) {
            // Flutter's surface went away: try a new window surface next time.
            EGL14.eglDestroySurface(display, window);
            window = EGL14.EGL_NO_SURFACE;
            return;
        }
        final int[] size = new int[2];
        EGL14.eglQuerySurface(display, window, EGL14.EGL_WIDTH, size, 0);
        EGL14.eglQuerySurface(display, window, EGL14.EGL_HEIGHT, size, 1);
        draw(presentProgram, 0, size[0], size[1]);
        bind(0, texture, "tex", presentProgram);
        GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, 3);
        EGL14.eglSwapBuffers(display, window);
        statShown++;
    }

    private void setStatus(String value) {
        if (!value.equals(status)) {
            status = value;
            Log.i(TAG, value);
        }
    }

    private void report() {
        final long now = System.nanoTime();
        final double seconds = (now - statSinceNs) / 1e9;
        if (seconds < 5) return;
        Log.i(TAG, String.format(Locale.ENGLISH, "%dx%d %dx (motion %dx%d): in %.1f fps, interpolated %.1f/s, out %.1f fps, %d dropped, display %.0f Hz",
                width, height, multiplier, analysisWidth, analysisHeight,
                statFrames / seconds, statMiddles / seconds, statShown / seconds, statDropped, 1e9 / vsyncNs));
        statFrames = statMiddles = statShown = statDropped = 0;
        statSinceNs = now;
    }

    // -------------------------------------------------------------- helpers

    private boolean makeCurrent(EGLSurface surface) {
        if (EGL14.eglMakeCurrent(display, surface, surface, context)) return true;
        Log.w(TAG, "eglMakeCurrent failed: " + EGL14.eglGetError());
        return false;
    }

    private static void draw(int program, int fbo, int w, int h) {
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fbo);
        GLES30.glViewport(0, 0, w, h);
        GLES30.glUseProgram(program);
    }

    private static void bind(int unit, int texture, String name, int program) {
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0 + unit);
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, texture);
        GLES30.glUniform1i(GLES30.glGetUniformLocation(program, name), unit);
    }

    private static void bindExternal(int unit, int texture, String name, int program) {
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0 + unit);
        GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texture);
        GLES30.glUniform1i(GLES30.glGetUniformLocation(program, name), unit);
    }

    private static int texture(int w, int h, int internalFormat, int format, int type, int filter) {
        final int[] names = new int[1];
        GLES30.glGenTextures(1, names, 0);
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, names[0]);
        GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, internalFormat, w, h, 0, format, type, null);
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, filter);
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, filter);
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE);
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE);
        return names[0];
    }

    private static int framebuffer(int texture) {
        final int[] names = new int[1];
        GLES30.glGenFramebuffers(1, names, 0);
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, names[0]);
        GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0, GLES30.GL_TEXTURE_2D, texture, 0);
        final int state = GLES30.glCheckFramebufferStatus(GLES30.GL_FRAMEBUFFER);
        if (state != GLES30.GL_FRAMEBUFFER_COMPLETE) throw new RuntimeException("framebuffer " + state);
        return names[0];
    }

    private static int program(String fragmentSource) {
        final int program = GLES30.glCreateProgram();
        GLES30.glAttachShader(program, shader(GLES30.GL_VERTEX_SHADER, VERTEX));
        GLES30.glAttachShader(program, shader(GLES30.GL_FRAGMENT_SHADER, fragmentSource));
        GLES30.glLinkProgram(program);
        final int[] linked = new int[1];
        GLES30.glGetProgramiv(program, GLES30.GL_LINK_STATUS, linked, 0);
        if (linked[0] == 0) throw new RuntimeException("link: " + GLES30.glGetProgramInfoLog(program));
        return program;
    }

    private static int shader(int type, String source) {
        final int shader = GLES30.glCreateShader(type);
        GLES30.glShaderSource(shader, source);
        GLES30.glCompileShader(shader);
        final int[] compiled = new int[1];
        GLES30.glGetShaderiv(shader, GLES30.GL_COMPILE_STATUS, compiled, 0);
        if (compiled[0] == 0) throw new RuntimeException("compile: " + GLES30.glGetShaderInfoLog(shader));
        return shader;
    }

    // -------------------------------------------------------------- shaders

    /** A triangle covering the target; uv is 0..1 across it. */
    private static final String VERTEX =
            "#version 300 es\n" +
            "out vec2 uv;\n" +
            "void main() {\n" +
            "  vec2 p = vec2(float((gl_VertexID << 1) & 2), float(gl_VertexID & 2));\n" +
            "  uv = p;\n" +
            "  gl_Position = vec4(p * 2.0 - 1.0, 0.0, 1.0);\n" +
            "}\n";

    private static final String COPY_FS =
            "#version 300 es\n" +
            "#extension GL_OES_EGL_image_external_essl3 : require\n" +
            "precision mediump float;\n" +
            "uniform samplerExternalOES tex;\n" +
            "uniform mat4 st;\n" +
            "in vec2 uv;\n" +
            "out vec4 color;\n" +
            "void main() { color = texture(tex, (st * vec4(uv, 0.0, 1.0)).xy); }\n";

    /** Luma at the analysis size, averaged over the source area of each pixel. */
    private static final String LUMA_FS =
            "#version 300 es\n" +
            "precision mediump float;\n" +
            "uniform sampler2D tex;\n" +
            "uniform vec2 dstSize;\n" +
            "in vec2 uv;\n" +
            "out vec4 color;\n" +
            "void main() {\n" +
            "  vec2 d = 0.25 / dstSize;\n" +
            "  vec3 c = texture(tex, uv + vec2(-d.x, -d.y)).rgb + texture(tex, uv + vec2(d.x, -d.y)).rgb\n" +
            "         + texture(tex, uv + vec2(-d.x, d.y)).rgb + texture(tex, uv + vec2(d.x, d.y)).rgb;\n" +
            "  color = vec4(dot(c * 0.25, vec3(0.299, 0.587, 0.114)), 0.0, 0.0, 1.0);\n" +
            "}\n";

    /** The next pyramid level: one bilinear tap between four texels is their average. */
    private static final String DOWN_FS =
            "#version 300 es\n" +
            "precision mediump float;\n" +
            "uniform sampler2D tex;\n" +
            "in vec2 uv;\n" +
            "out vec4 color;\n" +
            "void main() { color = vec4(texture(tex, uv).r, 0.0, 0.0, 1.0); }\n";

    /**
     * Bilateral block matching: how much previous(m - v/2) and current(m + v/2)
     * differ over the 8x8 block centered at m, sampled at 4x4 points.
     */
    private static final String COST =
            "uniform sampler2D previous;\n" +
            "uniform sampler2D current;\n" +
            "float cost(vec2 m, vec2 v) {\n" +
            "  vec2 size = vec2(textureSize(previous, 0));\n" +
            "  float sum = 0.0;\n" +
            "  for (int y = 0; y < 4; y++) {\n" +
            "    for (int x = 0; x < 4; x++) {\n" +
            "      vec2 p = m + vec2(float(x) * 2.0 - 3.0, float(y) * 2.0 - 3.0);\n" +
            "      sum += abs(texture(previous, (p - v * 0.5) / size).r - texture(current, (p + v * 0.5) / size).r);\n" +
            "    }\n" +
            "  }\n" +
            "  return sum / 16.0;\n" +
            "}\n";

    /** Out: x, y in 1/8 px of the level, and the match cost x 1024. */
    private static final String PACK =
            "ivec4 pack(vec2 v, float c) { return ivec4(ivec2(round(v * 8.0)), int(c * 1024.0), 0); }\n";

    /**
     * Full search on the coarsest level: steps of 2 over +-range, then refined
     * by 1. Equally good matches prefer small motion (flat areas).
     */
    private static final String COARSE_FS =
            "#version 300 es\n" +
            "precision highp float;\n" +
            "precision highp int;\n" +
            COST + PACK +
            "uniform int range;\n" +
            "layout(location = 0) out ivec4 color;\n" +
            "void main() {\n" +
            "  vec2 m = (floor(gl_FragCoord.xy) + 0.5) * 8.0;\n" +
            "  vec2 best = vec2(0.0);\n" +
            "  float bestCost = cost(m, best);\n" +
            "  for (int y = -range; y <= range; y += 2) {\n" +
            "    for (int x = -range; x <= range; x += 2) {\n" +
            "      vec2 v = vec2(float(x), float(y));\n" +
            "      float c = cost(m, v) + 0.0015 * (abs(v.x) + abs(v.y));\n" +
            "      if (c < bestCost) { bestCost = c; best = v; }\n" +
            "    }\n" +
            "  }\n" +
            "  vec2 center = best;\n" +
            "  for (int y = -1; y <= 1; y++) {\n" +
            "    for (int x = -1; x <= 1; x++) {\n" +
            "      vec2 v = center + vec2(float(x), float(y));\n" +
            "      float c = cost(m, v) + 0.0015 * (abs(v.x) + abs(v.y));\n" +
            "      if (c < bestCost) { bestCost = c; best = v; }\n" +
            "    }\n" +
            "  }\n" +
            "  color = pack(best, cost(m, best));\n" +
            "}\n";

    /**
     * One level finer: the coarser level's motion, doubled and interpolated at
     * this block, is the prediction. The best of it, its four parent blocks and
     * zero motion is then refined by +-2. Leaving the prediction costs a little.
     */
    private static final String REFINE_FS =
            "#version 300 es\n" +
            "precision highp float;\n" +
            "precision highp int;\n" +
            COST + PACK +
            "uniform highp isampler2D parent;\n" +
            "layout(location = 0) out ivec4 color;\n" +
            "vec2 parentAt(ivec2 i) {\n" +
            "  ivec2 last = textureSize(parent, 0) - 1;\n" +
            "  return vec2(texelFetch(parent, clamp(i, ivec2(0), last), 0).xy) / 8.0 * 2.0;\n" +
            "}\n" +
            "void main() {\n" +
            "  vec2 m = (floor(gl_FragCoord.xy) + 0.5) * 8.0;\n" +
            "  vec2 p = m * 0.5 / 8.0 - 0.5;\n" +
            "  ivec2 i = ivec2(floor(p));\n" +
            "  vec2 f = p - floor(p);\n" +
            "  vec2 a = parentAt(i), b = parentAt(i + ivec2(1, 0)), c = parentAt(i + ivec2(0, 1)), d = parentAt(i + ivec2(1, 1));\n" +
            "  vec2 prediction = round(mix(mix(a, b, f.x), mix(c, d, f.x), f.y));\n" +
            "  vec2 candidates[6] = vec2[6](prediction, round(a), round(b), round(c), round(d), vec2(0.0));\n" +
            "  vec2 best = prediction;\n" +
            "  float bestCost = 1e9;\n" +
            "  for (int k = 0; k < 6; k++) {\n" +
            "    float cc = cost(m, candidates[k]) + 0.002 * length(candidates[k] - prediction);\n" +
            "    if (cc < bestCost) { bestCost = cc; best = candidates[k]; }\n" +
            "  }\n" +
            "  vec2 center = best;\n" +
            "  for (int y = -2; y <= 2; y++) {\n" +
            "    for (int x = -2; x <= 2; x++) {\n" +
            "      vec2 v = center + vec2(float(x), float(y));\n" +
            "      float cc = cost(m, v) + 0.002 * length(v - prediction);\n" +
            "      if (cc < bestCost) { bestCost = cc; best = v; }\n" +
            "    }\n" +
            "  }\n" +
            "  color = pack(best, cost(m, best));\n" +
            "}\n";

    /** 3x3 component-wise median of the finest vectors, keeping the center's cost. */
    private static final String SMOOTH_FS =
            "#version 300 es\n" +
            "precision highp float;\n" +
            "precision highp int;\n" +
            "uniform highp isampler2D vectors;\n" +
            "layout(location = 0) out ivec4 color;\n" +
            "float median9(float a[9]) {\n" +
            "  for (int i = 0; i < 5; i++) {\n" +
            "    for (int j = i + 1; j < 9; j++) {\n" +
            "      if (a[j] < a[i]) { float t = a[i]; a[i] = a[j]; a[j] = t; }\n" +
            "    }\n" +
            "  }\n" +
            "  return a[4];\n" +
            "}\n" +
            "void main() {\n" +
            "  ivec2 c = ivec2(gl_FragCoord.xy);\n" +
            "  ivec2 last = textureSize(vectors, 0) - 1;\n" +
            "  float xs[9]; float ys[9];\n" +
            "  for (int i = 0; i < 9; i++) {\n" +
            "    ivec4 v = texelFetch(vectors, clamp(c + ivec2(i % 3 - 1, i / 3 - 1), ivec2(0), last), 0);\n" +
            "    xs[i] = float(v.x); ys[i] = float(v.y);\n" +
            "  }\n" +
            "  color = ivec4(int(median9(xs)), int(median9(ys)), texelFetch(vectors, c, 0).z, 0);\n" +
            "}\n";

    /**
     * The frame at [phase] between previous and current: both warped along the
     * motion (bilinear between block centers) and mixed, cross-faded
     * toward a plain blend where they still disagree (occlusions, failed matches).
     */
    private static final String INTERPOLATE_FS =
            "#version 300 es\n" +
            "precision highp float;\n" +
            "precision highp int;\n" +
            "uniform sampler2D previous;\n" +
            "uniform sampler2D current;\n" +
            "uniform highp isampler2D vectors;\n" +
            "uniform vec2 analysisSize;\n" +
            "uniform float phase;\n" +
            "in vec2 uv;\n" +
            "out vec4 color;\n" +
            "vec3 vectorAt(ivec2 i) {\n" +
            "  ivec2 last = textureSize(vectors, 0) - 1;\n" +
            "  ivec4 v = texelFetch(vectors, clamp(i, ivec2(0), last), 0);\n" +
            "  return vec3(vec2(v.xy) / 8.0, float(v.z) / 1024.0);\n" +
            "}\n" +
            "void main() {\n" +
            "  vec2 p = uv * analysisSize / 8.0 - 0.5;\n" +
            "  ivec2 i = ivec2(floor(p));\n" +
            "  vec2 f = p - floor(p);\n" +
            "  vec3 m = mix(mix(vectorAt(i), vectorAt(i + ivec2(1, 0)), f.x),\n" +
            "               mix(vectorAt(i + ivec2(0, 1)), vectorAt(i + ivec2(1, 1)), f.x), f.y);\n" +
            "  vec2 v = m.xy / analysisSize;\n" +
            "  vec4 a = texture(previous, uv - v * phase);\n" +
            "  vec4 b = texture(current, uv + v * (1.0 - phase));\n" +
            "  vec4 blend = mix(texture(previous, uv), texture(current, uv), phase);\n" +
            "  float err = abs(dot(a.rgb - b.rgb, vec3(0.299, 0.587, 0.114)));\n" +
            "  float w = max(smoothstep(0.06, 0.2, err), smoothstep(0.075, 0.2, m.z));\n" +
            "  color = mix(mix(a, b, phase), blend, w);\n" +
            "}\n";

    private static final String PRESENT_FS =
            "#version 300 es\n" +
            "precision mediump float;\n" +
            "uniform sampler2D tex;\n" +
            "in vec2 uv;\n" +
            "out vec4 color;\n" +
            "void main() { color = texture(tex, uv); }\n";
}
