/*
 *  Copyright 2015 The WebRTC project authors. All Rights Reserved.
 *
 *  Use of this source code is governed by a BSD-style license
 *  that can be found in the LICENSE file in the root of the source
 *  tree. An additional intellectual property rights grant can be found
 *  in the file PATENTS.  All contributing project authors may
 *  be found in the AUTHORS file in the root of the source tree.
 */

package org.webrtc;

import android.content.Context;
import android.hardware.camera2.CameraManager;
import android.media.MediaRecorder;
import android.os.Handler;
import android.os.SystemClock;
import android.view.Surface;
import android.view.WindowManager;

import org.webrtc.CameraEnumerationAndroid.CaptureFormat;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 *
  Android specific implementation of VideoCapturer.
  An instance of this class can be created by an application using
  VideoCapturerAndroid.create();
  This class extends VideoCapturer with a method to easily switch between the
  front and back camera. It also provides methods for enumerating valid device
  names.

  Threading notes: this class is called from C++ code, Android Camera callbacks, and possibly
  arbitrary Java threads. All public entry points are thread safe, and delegate the work to the
  camera thread. The internal *OnCameraThread() methods must check |camera| for null to check if
  the camera has been stopped.
 * @author Skyline
 */
public class VideoCapturerAndroid
    implements CameraVideoCapturer, android.hardware.Camera.PreviewCallback,
               SurfaceTextureHelper.OnTextureFrameAvailableListener {
  private static final String TAG = "VideoCapturerAndroid";
  private static final int CAMERA_STOP_TIMEOUT_MS = 7000;

  /**
   * Only non-null while capturing.
   */
  private android.hardware.Camera camera;
  private final AtomicBoolean isCameraRunning = new AtomicBoolean();
  /**
   * Use maybePostOnCameraThread() instead of posting directly to the handler - this way all
   * callbacks with a specifed token can be removed at once.
   */
  private volatile Handler cameraThreadHandler;
  private Context applicationContext;
  /**
   * Synchronization lock for |id|.
   */
  private final Object cameraIdLock = new Object();
  private int id;
  private android.hardware.Camera.CameraInfo info;
  private CameraStatistics cameraStatistics;
  /**
   * Remember the requested format in case we want to switch cameras.
   */
  private int requestedWidth;
  private int requestedHeight;
  private int requestedFramerate;
  /**
   * The capture format will be the closest supported format to the requested format.
   */
  private CaptureFormat captureFormat;
  private final Object pendingCameraSwitchLock = new Object();
  private volatile boolean pendingCameraSwitch;
  private CapturerObserver frameObserver = null;
  private final CameraEventsHandler eventsHandler;

  private ARCameraCapturerObserver arCameraObserver = null;
  private boolean firstFrameReported;
  /**
   *
    Arbitrary queue depth.  Higher number means more memory allocated & held,
    lower number means more sensitivity to processing time in the client (and
    potentially stalling the capturer if it runs out of buffers to write to).
   */
  private static final int NUMBER_OF_CAPTURE_BUFFERS = 3;
  private final Set<byte[]> queuedBuffers = new HashSet<byte[]>();
  private final boolean isCapturingToTexture;
  private SurfaceTextureHelper surfaceHelper;
  private final static int MAX_OPEN_CAMERA_ATTEMPTS = 3;
  private final static int OPEN_CAMERA_DELAY_MS = 500;
  private int openCameraAttempts;
  private boolean isReduceFPS;
  private boolean isUsedARCamera = true;
  private CameraManager mCameraManager;

  public void setARCameraObserver(ARCameraCapturerObserver arCameraObserver) {
    this.arCameraObserver = arCameraObserver;
  }

  public void setUsedARCamera(boolean usedARCamera) {
    isUsedARCamera = usedARCamera;
  }

  public void setReduceFPS(boolean reduceFPS) {
      isReduceFPS = reduceFPS;
  }

  /**
   * Camera error callback.
   */
  private final android.hardware.Camera.ErrorCallback cameraErrorCallback =
      new android.hardware.Camera.ErrorCallback() {
    @Override
    public void onError(int error, android.hardware.Camera camera) {
      String errorMessage;
      if (error == android.hardware.Camera.CAMERA_ERROR_SERVER_DIED) {
        errorMessage = "Camera server died!";
      } else {
        errorMessage = "Camera error: " + error;
      }
      Logging.e(TAG, errorMessage);
      if (eventsHandler != null) {
        eventsHandler.onCameraError(errorMessage);
      }
    }
  };

  public static VideoCapturerAndroid create(String name, CameraEventsHandler eventsHandler) {
    return VideoCapturerAndroid.create(name, eventsHandler, false /* captureToTexture */);
  }

  /**
   * Use ctor directly instead.
   * @param name
   * @param eventsHandler
   * @param captureToTexture
   * @return
   */
  @Deprecated
  public static VideoCapturerAndroid create(
      String name, CameraEventsHandler eventsHandler, boolean captureToTexture) {
    try {
      return new VideoCapturerAndroid(name, eventsHandler, captureToTexture);
    } catch (RuntimeException e) {
      Logging.e(TAG, "Couldn't create camera.", e);
      return null;
    }
  }

  public void printStackTrace() {
    Thread cameraThread = null;
    if (cameraThreadHandler != null) {
      cameraThread = cameraThreadHandler.getLooper().getThread();
    }
    if (cameraThread != null) {
      StackTraceElement[] cameraStackTraces = cameraThread.getStackTrace();
      if (cameraStackTraces.length > 0) {
        Logging.d(TAG, "VideoCapturerAndroid stacks trace:");
        for (StackTraceElement stackTrace : cameraStackTraces) {
          Logging.d(TAG, stackTrace.toString());
        }
      }
    }
  }

  /**
   * Switch camera to the next valid camera id. This can only be called while
   * the camera is running.
   * @param switchEventsHandler
   */
  @Override
  public void switchCamera(final CameraSwitchHandler switchEventsHandler) {
    if (android.hardware.Camera.getNumberOfCameras() < 2) {
      if (switchEventsHandler != null) {
        switchEventsHandler.onCameraSwitchError("No camera to switch to.");
      }
      return;
    }
    synchronized (pendingCameraSwitchLock) {
      if (pendingCameraSwitch) {
        // Do not handle multiple camera switch request to avoid blocking
        // camera thread by handling too many switch request from a queue.
        Logging.w(TAG, "Ignoring camera switch request.");
        if (switchEventsHandler != null) {
          switchEventsHandler.onCameraSwitchError("Pending camera switch already in progress.");
        }
        return;
      }
      pendingCameraSwitch = true;
    }
    final boolean didPost = maybePostOnCameraThread(new Runnable() {
      @Override
      public void run() {
        switchCameraOnCameraThread();
        synchronized (pendingCameraSwitchLock) {
          pendingCameraSwitch = false;
        }
        if (switchEventsHandler != null) {
          switchEventsHandler.onCameraSwitchDone(
              info.facing == android.hardware.Camera.CameraInfo.CAMERA_FACING_FRONT);
        }
      }
    });
    if (!didPost && switchEventsHandler != null) {
      switchEventsHandler.onCameraSwitchError("Camera is stopped.");
    }
  }

  @Override
  public void addMediaRecorderToCamera(MediaRecorder mediaRecorder, MediaRecorderHandler resultHandler) {

  }

  @Override
  public void removeMediaRecorderFromCamera(MediaRecorderHandler resultHandler) {

  }

  /**
   * Reconfigure the camera to capture in a new format. This should only be called while the camera is running.
   * @param width
   * @param height
   * @param framerate
   */
  @Override
  public void changeCaptureFormat(final int width, final int height, final int framerate) {
    maybePostOnCameraThread(new Runnable() {
      @Override
      public void run() {
        startPreviewOnCameraThread(width, height, framerate);
      }
    });
  }

  /**
   * Helper function to retrieve the current camera id synchronously. Note that the camera id might change at any point by switchCamera() calls.
   *
   * @return camera id
   */
  private int getCurrentCameraId() {
    synchronized (cameraIdLock) {
      return id;
    }
  }

  @Override
  public List<CaptureFormat> getSupportedFormats() {
    return Camera1Enumerator.getSupportedFormats(getCurrentCameraId());
  }

  /**
   * Returns true if this VideoCapturer is setup to capture video frames to a SurfaceTexture.
   * @return boolean
   */
  public boolean isCapturingToTexture() {
    return isCapturingToTexture;
  }

  public VideoCapturerAndroid(
      String cameraName, CameraEventsHandler eventsHandler, boolean captureToTexture) {
    if (android.hardware.Camera.getNumberOfCameras() == 0) {
      throw new RuntimeException("No cameras available");
    }
    if (cameraName == null || cameraName.equals("")) {
      this.id = 0;
    } else {
      this.id = Camera1Enumerator.getCameraIndex(cameraName);
    }
    this.eventsHandler = eventsHandler;
    isCapturingToTexture = captureToTexture;
    Logging.d(TAG, "VideoCapturerAndroid isCapturingToTexture : " + isCapturingToTexture);
  }

  private void checkIsOnCameraThread() {
    if (cameraThreadHandler == null) {
      Logging.e(TAG, "Camera is not initialized - can't check thread.");
    } else if (Thread.currentThread() != cameraThreadHandler.getLooper().getThread()) {
      throw new IllegalStateException("Wrong thread");
    }
  }

  private boolean maybePostOnCameraThread(Runnable runnable) {
    return maybePostDelayedOnCameraThread(0 /* delayMs */, runnable);
  }

  private boolean maybePostDelayedOnCameraThread(int delayMs, Runnable runnable) {
    return cameraThreadHandler != null && isCameraRunning.get()
        && cameraThreadHandler.postAtTime(
               runnable, this /* token */, SystemClock.uptimeMillis() + delayMs);
  }

  @Override
  public void dispose() {
    Logging.d(TAG, "dispose");
  }

  private boolean isInitialized() {
    return applicationContext != null && frameObserver != null;
  }

  @Override
  public void initialize(SurfaceTextureHelper surfaceTextureHelper, Context applicationContext,
      CapturerObserver frameObserver) {
    Logging.d(TAG, "initialize");
    if (applicationContext == null) {
      throw new IllegalArgumentException("applicationContext not set.");
    }
    if (frameObserver == null) {
      throw new IllegalArgumentException("frameObserver not set.");
    }
    if (isInitialized()) {
      throw new IllegalStateException("Already initialized");
    }
    this.applicationContext = applicationContext;
    this.frameObserver = frameObserver;
    this.surfaceHelper = surfaceTextureHelper;
    this.cameraThreadHandler =
        surfaceTextureHelper == null ? null : surfaceTextureHelper.getHandler();
  }


  /**
   * Note that this actually opens the camera, and Camera callbacks run on the
   * thread that calls open(), so this is done on the CameraThread.
   *
   * @param width video width
   * @param height video height
   * @param framerate video framerate
   */
  @Override
  public void startCapture(final int width, final int height, final int framerate) {
    Logging.d(TAG, "startCapture requested: " + width + "x" + height + "@" + framerate);
    if (!isInitialized()) {
      throw new IllegalStateException("startCapture called in uninitialized state");
    }
    if (surfaceHelper == null) {
      frameObserver.onCapturerStarted(false /* success */);
      if (eventsHandler != null) {
        eventsHandler.onCameraError("No SurfaceTexture created.");
      }
      return;
    }
    if (isCameraRunning.getAndSet(true)) {
      Logging.e(TAG, "Camera has already been started.");
      return;
    }
    final boolean didPost = maybePostOnCameraThread(new Runnable() {
      @Override
      public void run() {
        openCameraAttempts = 0;
        startCaptureOnCameraThread(width, height, framerate);
      }
    });
    if (!didPost) {
      frameObserver.onCapturerStarted(false);
      if (eventsHandler != null) {
        eventsHandler.onCameraError("Could not post task to camera thread.");
      }
      isCameraRunning.set(false);
    }
  }

  /**
   * start camera capturing on thread.
   * @param width
   * @param height
   * @param framerate
   */
  private void startCaptureOnCameraThread(final int width, final int height, final int framerate) {
    checkIsOnCameraThread();
    if (!isCameraRunning.get()) {
      Logging.e(TAG, "startCaptureOnCameraThread: Camera is stopped");
      return;
    }
    if (camera != null) {
      Logging.e(TAG, "startCaptureOnCameraThread: Camera has already been started.");
      return;
    }
    this.firstFrameReported = false;

    try {
      try {
        synchronized (cameraIdLock) {
          Logging.d(TAG, "Opening camera " + id);
          if (eventsHandler != null) {
            eventsHandler.onCameraOpening(Camera1Enumerator.getDeviceName(id));
          }
          camera = android.hardware.Camera.open(id);
          info = new android.hardware.Camera.CameraInfo();
          android.hardware.Camera.getCameraInfo(id, info);
        }
      } catch (RuntimeException e) {
        openCameraAttempts++;
        if (openCameraAttempts < MAX_OPEN_CAMERA_ATTEMPTS) {
          Logging.e(TAG, "Camera.open failed, retrying", e);
          maybePostDelayedOnCameraThread(OPEN_CAMERA_DELAY_MS, new Runnable() {
            @Override
            public void run() {
              startCaptureOnCameraThread(width, height, framerate);
            }
          });
          return;
        }
        throw e;
      }

      camera.setPreviewTexture(surfaceHelper.getSurfaceTexture());

      Logging.d(TAG, "Camera orientation: " + info.orientation + " .Device orientation: "
              + getDeviceOrientation());
      camera.setErrorCallback(cameraErrorCallback);
      startPreviewOnCameraThread(width, height, framerate);
      frameObserver.onCapturerStarted(true);
      if (isCapturingToTexture) {
        surfaceHelper.startListening(this);
      }

      // Start camera observer.
      cameraStatistics = new CameraStatistics(surfaceHelper, eventsHandler);
    } catch (IOException | RuntimeException e) {
      Logging.e(TAG, "startCapture failed", e);
      // Make sure the camera is released.
      stopCaptureOnCameraThread(true /* stopHandler */);
      frameObserver.onCapturerStarted(false);
      if (eventsHandler != null) {
        eventsHandler.onCameraError("Camera can not be started.");
      }
    }
  }

  /**
   * (Re)start preview with the closest supported format to |width| x |height| @ |framerate|.
   * @param width video width
   * @param height video height
   * @param framerate video framerate
   */
  private void startPreviewOnCameraThread(int width, int height, int framerate) {
    checkIsOnCameraThread();
    if (!isCameraRunning.get() || camera == null) {
      Logging.e(TAG, "startPreviewOnCameraThread: Camera is stopped");
      return;
    }
    Logging.d(
        TAG, "startPreviewOnCameraThread requested: " + width + "x" + height + "@" + framerate);

    requestedWidth = width;
    requestedHeight = height;
    requestedFramerate = framerate;

    // Find closest supported format for |width| x |height| @ |framerate|.
    final android.hardware.Camera.Parameters parameters = camera.getParameters();
    final List<CaptureFormat.FramerateRange> supportedFramerates =
        Camera1Enumerator.convertFramerates(parameters.getSupportedPreviewFpsRange());
    Logging.d(TAG, "Available fps ranges: " + supportedFramerates);

    final CaptureFormat.FramerateRange fpsRange =
        CameraEnumerationAndroid.getClosestSupportedFramerateRange(supportedFramerates, framerate);

    final List<Size> supportedPreviewSizes =
        Camera1Enumerator.convertSizes(parameters.getSupportedPreviewSizes());
    final Size previewSize =
        CameraEnumerationAndroid.getClosestSupportedSize(supportedPreviewSizes, width, height);
    Logging.d(TAG, "Available preview sizes: " + supportedPreviewSizes);

    final CaptureFormat captureFormat =
        new CaptureFormat(previewSize.width, previewSize.height, fpsRange);

    // Check if we are already using this capture format, then we don't need to do anything.
    if (captureFormat.equals(this.captureFormat)) {
      return;
    }

    // Update camera parameters.
    Logging.d(TAG, "isVideoStabilizationSupported: " + parameters.isVideoStabilizationSupported());
    if (parameters.isVideoStabilizationSupported()) {
      parameters.setVideoStabilization(true);
    }
    // Note: setRecordingHint(true) actually decrease frame rate on N5.
    // parameters.setRecordingHint(true);
    if (captureFormat.framerate.max > 0) {
      parameters.setPreviewFpsRange(captureFormat.framerate.min, captureFormat.framerate.max);
    }
    parameters.setPreviewSize(previewSize.width, previewSize.height);

    if (!isCapturingToTexture) {
      parameters.setPreviewFormat(captureFormat.imageFormat);
    }
    // Picture size is for taking pictures and not for preview/video, but we need to set it anyway
    // as a workaround for an aspect ratio problem on Nexus 7.
    final Size pictureSize = CameraEnumerationAndroid.getClosestSupportedSize(
        Camera1Enumerator.convertSizes(parameters.getSupportedPictureSizes()), width, height);
    parameters.setPictureSize(pictureSize.width, pictureSize.height);

    // Temporarily stop preview if it's already running.
    if (this.captureFormat != null) {
      camera.stopPreview();
      // Calling |setPreviewCallbackWithBuffer| with null should clear the internal camera buffer
      // queue, but sometimes we receive a frame with the old resolution after this call anyway.
      camera.setPreviewCallbackWithBuffer(null);
    }

    List<String> focusModes = parameters.getSupportedFocusModes();
    if (focusModes.contains(android.hardware.Camera.Parameters.FOCUS_MODE_CONTINUOUS_VIDEO)) {
      Logging.d(TAG, "Enable continuous auto focus mode.");
      parameters.setFocusMode(android.hardware.Camera.Parameters.FOCUS_MODE_CONTINUOUS_VIDEO);
    }

    // (Re)start preview.
    Logging.d(TAG, "Start capturing: " + captureFormat);
    this.captureFormat = captureFormat;

    camera.setParameters(parameters);
    // Calculate orientation manually and send it as CVO instead.
    camera.setDisplayOrientation(0 /* degrees */);
    if (!isCapturingToTexture) {
      queuedBuffers.clear();
      final int frameSize = captureFormat.frameSize();
      for (int i = 0; i < NUMBER_OF_CAPTURE_BUFFERS; ++i) {
        final ByteBuffer buffer = ByteBuffer.allocateDirect(frameSize);
        queuedBuffers.add(buffer.array());
        camera.addCallbackBuffer(buffer.array());
      }
      camera.setPreviewCallbackWithBuffer(this);
    }
    camera.startPreview();
  }

  /**
   * 闪光灯是否打开
   * @return true：打开；false：关闭
   */
  private boolean isFlashlightOn() {
      try {
          android.hardware.Camera.Parameters parameters = camera.getParameters();
          String flashMode = parameters.getFlashMode();
          if (flashMode.equals(android.hardware.Camera.Parameters.FLASH_MODE_TORCH)) {
            return true;
          } else {
            return false;
          }
      } catch (Exception e) {
          return false;
      }
  }

  /**
   * 是否打开闪光灯
   * @param bOpen  true: 打开闪光灯; false: 关闭闪光灯
   */
  public void openCameraTorchMode(boolean bOpen) {
    android.hardware.Camera.Parameters parameters = camera.getParameters();
    if(bOpen) {
      if(camera != null) {
        camera.getParameters().setFlashMode(parameters.FLASH_MODE_TORCH);
        camera.setParameters(camera.getParameters());
      }
    } else {
        if(camera != null) {
          camera.getParameters().setFlashMode(parameters.FLASH_MODE_OFF);
          camera.setParameters(parameters);
      }
    }
  }

    /**
     * set the camera zoom
     * @param value
     */
    public void setZoom(int value) {
        android.hardware.Camera.Parameters parameters = camera.getParameters();
        int maxZoom = parameters.getMaxZoom();
        if(value < 0) {
            return;
        }
        if(value <= maxZoom) {
            parameters.setZoom(value);
            camera.setParameters(parameters);
        } else {
            parameters.setZoom(maxZoom);
            camera.setParameters(parameters);
        }
    }

    /**
     * get the camera max camera zoom
     * @return 0
     */
    public int getMaxZoom() {
        if(null != camera) {
            return camera.getParameters().getMaxZoom();
        } else {
            return 0;
        }
    }

    /**
     * get current camera zoom
     * @return 0
     */
    public int getCameraZoom() {
        return camera.getParameters().getZoom();
    }

    /**
     * camera is support smooth zoom
     * @return 0
     */
    public boolean isSmoothZoomSupported() {
        return camera.getParameters().isSmoothZoomSupported();
    }

    /**
     * camera is support zoom
     * @return 0
     */
    public boolean isZoomSupported() {
        return camera.getParameters().isZoomSupported();
    }

  /**
   * Blocks until camera is known to be stopped.
   * @throws InterruptedException
   */
  @Override
  public void stopCapture() throws InterruptedException {
    Logging.d(TAG, "stopCapture");
    final CountDownLatch barrier = new CountDownLatch(1);
    final boolean didPost = maybePostOnCameraThread(new Runnable() {
      @Override
      public void run() {
        stopCaptureOnCameraThread(true /* stopHandler */);
        barrier.countDown();
      }
    });
    if (!didPost) {
      Logging.e(TAG, "Calling stopCapture() for already stopped camera.");
      return;
    }
    if (!barrier.await(CAMERA_STOP_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
      Logging.e(TAG, "Camera stop timeout");
      printStackTrace();
      if (eventsHandler != null) {
        eventsHandler.onCameraError("Camera stop timeout");
      }
    }
    frameObserver.onCapturerStopped();
    Logging.d(TAG, "stopCapture done");
  }

  private void stopCaptureOnCameraThread(boolean stopHandler) {
    checkIsOnCameraThread();
    Logging.d(TAG, "stopCaptureOnCameraThread");
    // Note that the camera might still not be started here if startCaptureOnCameraThread failed
    // and we posted a retry.

    // Make sure onTextureFrameAvailable() is not called anymore.
    if (surfaceHelper != null) {
      surfaceHelper.stopListening();
    }
    if (stopHandler) {
      // Clear the cameraThreadHandler first, in case stopPreview or
      // other driver code deadlocks. Deadlock in
      // android.hardware.Camera._stopPreview(Native Method) has
      // been observed on Nexus 5 (hammerhead), OS version LMY48I.
      // The camera might post another one or two preview frames
      // before stopped, so we have to check |isCameraRunning|.
      // Remove all pending Runnables posted from |this|.
      isCameraRunning.set(false);
      cameraThreadHandler.removeCallbacksAndMessages(this /* token */);
    }
    if (cameraStatistics != null) {
      cameraStatistics.release();
      cameraStatistics = null;
    }
    Logging.d(TAG, "Stop preview.");
    if (camera != null) {
      camera.stopPreview();
      camera.setPreviewCallbackWithBuffer(null);
    }
    queuedBuffers.clear();
    captureFormat = null;

    Logging.d(TAG, "Release camera.");
    if (camera != null) {
      camera.release();
      camera = null;
    }
    if (eventsHandler != null) {
      eventsHandler.onCameraClosed();
    }
    Logging.d(TAG, "stopCaptureOnCameraThread done");
  }

  private void switchCameraOnCameraThread() {
    checkIsOnCameraThread();
    if (!isCameraRunning.get()) {
      Logging.e(TAG, "switchCameraOnCameraThread: Camera is stopped");
      return;
    }
    Logging.d(TAG, "switchCameraOnCameraThread");
    stopCaptureOnCameraThread(false /* stopHandler */);
    synchronized (cameraIdLock) {
      id = (id + 1) % android.hardware.Camera.getNumberOfCameras();
    }
    startCaptureOnCameraThread(requestedWidth, requestedHeight, requestedFramerate);
    Logging.d(TAG, "switchCameraOnCameraThread done");
  }

  private int getDeviceOrientation() {
    int orientation = 0;

    WindowManager wm = (WindowManager) applicationContext.getSystemService(Context.WINDOW_SERVICE);
    switch (wm.getDefaultDisplay().getRotation()) {
      case Surface.ROTATION_90:
        orientation = 90;
        break;
      case Surface.ROTATION_180:
        orientation = 180;
        break;
      case Surface.ROTATION_270:
        orientation = 270;
        break;
      case Surface.ROTATION_0:
      default:
        orientation = 0;
        break;
    }
    return orientation;
  }

  @Override
  public boolean useBackCamera() {
    if(info == null) {
      return true;
    }
    return info.facing == android.hardware.Camera.CameraInfo.CAMERA_FACING_BACK;
  }

  private int getFrameOrientation() {
    int rotation = getDeviceOrientation();
    if (info.facing == android.hardware.Camera.CameraInfo.CAMERA_FACING_BACK) {
      rotation = 360 - rotation;
    }
    if((info.orientation + rotation) % 360 == 180) {
        return 0;
    } else {
        return (info.orientation + rotation) % 360;
    }
  }

  static int vFps = 0;

  /**
   * Called on cameraThread so must not "synchronized".
   * @param data
   * @param callbackCamera
   */
  @Override
  public void onPreviewFrame(byte[] data, android.hardware.Camera callbackCamera) {
    checkIsOnCameraThread();
    if (!isCameraRunning.get()) {
      Logging.e(TAG, "onPreviewFrame: Camera is stopped");
      return;
    }
    if (!queuedBuffers.contains(data)) {
      // |data| is an old invalid buffer.
      return;
    }
    if (camera != callbackCamera) {
      throw new RuntimeException("Unexpected camera in callback!");
    }

    if(isReduceFPS) {
        vFps ++;
        if(vFps % 2 == 0) {
            camera.addCallbackBuffer(data);
            return;
        }
    }

    final long captureTimeNs = TimeUnit.MILLISECONDS.toNanos(SystemClock.elapsedRealtime());

    if (eventsHandler != null && !firstFrameReported) {
      eventsHandler.onFirstFrameAvailable();
      firstFrameReported = true;
    }

    cameraStatistics.addFrame();

    if(null != arCameraObserver) {
      arCameraObserver.onByteBufferFrameCaptured(data, captureFormat.width, captureFormat.height, getFrameOrientation(), captureTimeNs);
    }

    if(isUsedARCamera) {
      frameObserver.onByteBufferFrameCaptured(
              data, captureFormat.width, captureFormat.height, getFrameOrientation(), captureTimeNs);
    }
    camera.addCallbackBuffer(data);
  }

  @Override
  public void onTextureFrameAvailable(int oesTextureId, float[] transformMatrix, long timestampNs) {
    checkIsOnCameraThread();
    if (!isCameraRunning.get()) {
      Logging.e(TAG, "onTextureFrameAvailable: Camera is stopped");
      surfaceHelper.returnTextureFrame();
      return;
    }
    if (eventsHandler != null && !firstFrameReported) {
      eventsHandler.onFirstFrameAvailable();
      firstFrameReported = true;
    }

    int rotation = getFrameOrientation();
    if (info.facing == android.hardware.Camera.CameraInfo.CAMERA_FACING_FRONT) {
      // Undo the mirror that the OS "helps" us with.
      // http://developer.android.com/reference/android/hardware/Camera.html#setDisplayOrientation(int)
      transformMatrix =
          RendererCommon.multiplyMatrices(transformMatrix, RendererCommon.horizontalFlipMatrix());
    }
    cameraStatistics.addFrame();
    frameObserver.onTextureFrameCaptured(captureFormat.width, captureFormat.height, oesTextureId,
        transformMatrix, rotation, timestampNs);
  }

  @Override
  public boolean isScreencast() {
    return false;
  }

  public void setByteBufferFrameCaptured(byte[] data, int width, int height, int rotation, long timeStamp) {
    if(!isUsedARCamera) {
      frameObserver.onByteBufferFrameCaptured(data, width, height, rotation, timeStamp);
    } else {
      Logging.e(TAG, "not support, please setUsedARCamera(false)!");
    }
  }
}