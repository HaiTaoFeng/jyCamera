package com.pwithe.jycamera.camera;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.SurfaceTexture;
import android.hardware.Camera;
import android.opengl.EGL14;
import android.opengl.GLES20;
import android.opengl.GLException;
import android.opengl.GLSurfaceView;
import android.opengl.Matrix;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.util.AttributeSet;
import android.util.Log;
import android.view.Display;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.WindowManager;

import com.pwithe.jycamera.CommApplication;
import com.pwithe.jycamera.R;
import com.pwithe.jycamera.drawer.GLDrawer2D;
import com.pwithe.jycamera.drawer.TextureHelper;
import com.pwithe.jycamera.drawer.WaterSignSProgram;
import com.pwithe.jycamera.drawer.WaterSignature;
import com.pwithe.jycamera.record.MediaMuxerWrapper;
import com.pwithe.jycamera.record.MediaVideoEncoder;
import com.pwithe.jycamera.utils.BitmapUtil;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.lang.ref.WeakReference;
import java.nio.IntBuffer;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

/**
 * GLSurfaceView的子类，用于显示相机预览并将视频帧写入捕获曲面
 */
public final class CameraGLView extends GLSurfaceView {

	private static final String TAG = "CameraGLView";

	private static final int CAMERA_ID = 0;

	private static final int SCALE_STRETCH_FIT = 0;
	private static final int SCALE_KEEP_ASPECT_VIEWPORT = 1;
	private static final int SCALE_KEEP_ASPECT = 2;
	private static final int SCALE_CROP_CENTER = 3;

	private final CameraSurfaceRenderer mRenderer;
	private boolean mHasSurface;
	private CameraHandler mCameraHandler = null;
	private int mVideoWidth, mVideoHeight;
	private int mRotation;
	private int mScaleMode = SCALE_STRETCH_FIT;


	public CameraGLView(final Context context) {
		this(context, null, 0);
	}

	public CameraGLView(final Context context, final AttributeSet attrs) {
		this(context, attrs, 0);
	}

	public CameraGLView(final Context context, final AttributeSet attrs, final int defStyle) {
		super(context, attrs);
		Log.v(TAG, "CameraGLView:");
		mRenderer = new CameraSurfaceRenderer(this);
		setEGLContextClientVersion(2);	// GLES 2.0, API >= 8
		setRenderer(mRenderer);
	}


	@Override
	public void onResume() {
		Log.v(TAG, "onResume:");
		super.onResume();
		if (mHasSurface) {
			if (mCameraHandler == null) {
				Log.v(TAG, "surface already exist");
				startPreview(getWidth(),  getHeight());
			}
		}
	}

	@Override
	public void onPause() {
		Log.v(TAG, "onPause:");
		if (mCameraHandler != null) {
			// 停止预览
			mCameraHandler.stopPreview(false);
		}
		super.onPause();
	}

	public void setScaleMode(final int mode) {
		if (mScaleMode != mode) {
			mScaleMode = mode;
			queueEvent(new Runnable() {
				@Override
				public void run() {
					mRenderer.updateViewport();
				}
			});
		}
	}

	public int getScaleMode() {
		return mScaleMode;
	}

	public void setVideoSize(final int width, final int height) {
		if ((mRotation % 180) == 0) {
			mVideoWidth = width;
			mVideoHeight = height;

		} else {
			mVideoWidth = height;
			mVideoHeight = width;
		}
		queueEvent(new Runnable() {
			@Override
			public void run() {
				mRenderer.updateViewport();
			}
		});
	}

	public int getVideoWidth() {
		return mVideoWidth;
	}

	public int getVideoHeight() {
		return mVideoHeight;
	}

	public SurfaceTexture getSurfaceTexture() {
		Log.v(TAG, "getSurfaceTexture:");
		return mRenderer != null ? mRenderer.mSTexture : null;
	}

	@Override
	public void surfaceDestroyed(final SurfaceHolder holder) {
		Log.v(TAG, "surfaceDestroyed:");
		if (mCameraHandler != null) {
			// 销毁surface
			mCameraHandler.stopPreview(true);
		}
		mCameraHandler = null;
		mHasSurface = false;
		mRenderer.onSurfaceDestroyed();
		super.surfaceDestroyed(holder);
	}

	public void setVideoEncoder(final MediaVideoEncoder encoder) {
//		Log.v(TAG, "setVideoEncoder:tex_id=" + mRenderer.hTex + ",encoder=" + encoder);
		queueEvent(new Runnable() {
			@Override
			public void run() {
				synchronized (mRenderer) {
					if (encoder != null) {
						encoder.setEglContext(EGL14.eglGetCurrentContext(), mRenderer.hTex);
					}
					mRenderer.mVideoEncoder = encoder;
				}
			}
		});
	}

//********************************************************************************
//********************************************************************************
	private synchronized void startPreview(final int width, final int height) {
		if (mCameraHandler == null) {
			final CameraThread thread = new CameraThread(this);
			thread.start();
			mCameraHandler = thread.getHandler();
		}
		mCameraHandler.startPreview(1280, 720/*width, height*/);
	}

	/**
	 * glsurfaceView渲染器
	 */
	private static final class CameraSurfaceRenderer
		implements GLSurfaceView.Renderer,
					SurfaceTexture.OnFrameAvailableListener {	// API >= 11

		private final WeakReference<CameraGLView> mWeakParent;
		private SurfaceTexture mSTexture;	// API >= 11
		private int hTex;
		private GLDrawer2D mDrawer;
		private final float[] mStMatrix = new float[16];
		private final float[] mMvpMatrix = new float[16];
		private MediaVideoEncoder mVideoEncoder;
		private WaterSignature mWaterSign;
		private BitmapUtil bitmapUtil;
		private int mSignTexId;


		public CameraSurfaceRenderer(final CameraGLView parent) {
			Log.v(TAG, "CameraSurfaceRenderer:");
			mWeakParent = new WeakReference<CameraGLView>(parent);
			Matrix.setIdentityM(mMvpMatrix, 0);
			mWaterSign = new WaterSignature();
			bitmapUtil = CommApplication.getBitmapUtil();
		}

		@Override
		public void onSurfaceCreated(final GL10 unused, final EGLConfig config) {
			Log.v(TAG, "onSurfaceCreated:");
			// This renderer required OES_EGL_image_external extension
			final String extensions = GLES20.glGetString(GLES20.GL_EXTENSIONS);	// API >= 8
			//判断系统是否支持OES_EGL_image_external
			if (!extensions.contains("OES_EGL_image_external"))
				throw new RuntimeException("This system does not support OES_EGL_image_external.");
			// 创建 textur ID
			hTex = GLDrawer2D.initTex();
			// 通过textur ID创建SurfaceTexture
			mSTexture = new SurfaceTexture(hTex);
			mSTexture.setOnFrameAvailableListener(this);
			// 使用黄色清除界面
			GLES20.glClearColor(1.0f, 1.0f, 0.0f, 1.0f);
			final CameraGLView parent = mWeakParent.get();
			if (parent != null) {
				parent.mHasSurface = true;
			}
			// 为预览显示创建对象
			mDrawer = new GLDrawer2D();
			mDrawer.setMatrix(mMvpMatrix, 0);
			//设置水印
            if (mWaterSign == null) {
                mWaterSign = new WaterSignature();
            }
            //设置阴影
			mWaterSign.setShaderProgram(new WaterSignSProgram());
			mSignTexId = TextureHelper.loadTexture(CommApplication.getInstance(), R.mipmap.watermark);
		}

		@Override
		public void onSurfaceChanged(final GL10 unused, final int width, final int height) {
			Log.v(TAG, String.format("onSurfaceChanged:(%d,%d)", width, height));
			//如果至少with或height为零，则此视图的初始化仍在进行中。
			if ((width == 0) || (height == 0)) return;
			updateViewport();
			final CameraGLView parent = mWeakParent.get();
			if (parent != null) {
				parent.startPreview(width, height);
			}
		}

		/**
		 * 当glsurface上下文被销毁时
		 */
		public void onSurfaceDestroyed() {
			Log.v(TAG, "onSurfaceDestroyed:");
			if (mDrawer != null) {
				mDrawer.release();
				mDrawer = null;
			}
			if (mWaterSign != null) {
				mWaterSign.release();
				mWaterSign = null;
			}
			if (mSTexture != null) {
				mSTexture.release();
				mSTexture = null;
			}
			GLDrawer2D.deleteTex(hTex);
			WaterSignature.deleteTex(mSignTexId);
			//回收bitmap
			bitmapUtil.recyle();
		}

		private final void updateViewport() {
			final CameraGLView parent = mWeakParent.get();
			if (parent != null) {
				final int view_width = parent.getWidth();
				final int view_height = parent.getHeight();
				GLES20.glViewport(0, 0, view_width, view_height);
				GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
				final double video_width = parent.mVideoWidth;
				final double video_height = parent.mVideoHeight;
				if (video_width == 0 || video_height == 0) return;
				Matrix.setIdentityM(mMvpMatrix, 0);
				final double view_aspect = view_width / (double)view_height;
				Log.i(TAG, String.format("view(%d,%d)%f,video(%1.0f,%1.0f)", view_width, view_height, view_aspect, video_width, video_height));
				switch (parent.mScaleMode) {
				case SCALE_STRETCH_FIT:
					break;
				case SCALE_KEEP_ASPECT_VIEWPORT:
				{
					final double req = video_width / video_height;
					int x, y;
					int width, height;
					if (view_aspect > req) {
						// 如果视图宽度大于相机图像，则根据视图高度计算绘图区域的宽度
						y = 0;
						height = view_height;
						width = (int)(req * view_height);
						x = (view_width - width) / 2;
					} else {
						// 如果视图高于相机图像，则根据视图宽度计算绘图区域的高度
						x = 0;
						width = view_width;
						height = (int)(view_width / req);
						y = (view_height - height) / 2;
					}
					// 设置视区以绘制相机图像的保持纵横比
					Log.v(TAG, String.format("xy(%d,%d),size(%d,%d)", x, y, width, height));
					GLES20.glViewport(x, y, width, height);
					break;
				}
				case SCALE_KEEP_ASPECT:
				case SCALE_CROP_CENTER:
				{
					final double scale_x = view_width / video_width;
					final double scale_y = view_height / video_height;
					final double scale = (parent.mScaleMode == SCALE_CROP_CENTER
						? Math.max(scale_x,  scale_y) : Math.min(scale_x, scale_y));
					final double width = scale * video_width;
					final double height = scale * video_height;
					Log.v(TAG, String.format("size(%1.0f,%1.0f),scale(%f,%f),mat(%f,%f)",
						width, height, scale_x, scale_y, width / view_width, height / view_height));
					Matrix.scaleM(mMvpMatrix, 0, (float)(width / view_width), (float)(height / view_height), 1.0f);
					break;
				}
				}
				if (mDrawer != null)
					mDrawer.setMatrix(mMvpMatrix, 0);
			}
		}

		private volatile boolean requesrUpdateTex = false;
		private boolean flip = true;
		private int[] fTexture = new int[1];
		/**
		 * 绘图到glsurface
		 * 我们将rendermode设置为glsurfaceview.rendermode_when_dirty，
		 * 仅当调用requestrender时调用此方法（=需要更新纹理时）
		 * 如果不在脏时设置rendermode，则此方法的最大调用速度为60fps。
		 */
		@Override
		public void onDrawFrame(final GL10 unused) {
			GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
			GLES20.glEnable(GLES20.GL_BLEND);
			GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE_MINUS_SRC_ALPHA);
			if (requesrUpdateTex) {
				requesrUpdateTex = false;
				// 更新texture（来自相机）
				mSTexture.updateTexImage();
				// 获取texture矩阵
				mSTexture.getTransformMatrix(mStMatrix);
			}
			// 绘制到预览屏幕
			final CameraGLView parent = mWeakParent.get();
			final int view_width = parent.getWidth();
			final int view_height = parent.getHeight();
			GLES20.glViewport(0, 0, view_width, view_height);
			mDrawer.draw(hTex, mStMatrix);
			if (isPhoto) {
				isPhoto = false;
				savePicture(view_width,view_height);
			}
			//画水印（非动态）
//			GLES20.glViewport(20, 20, 288, 120);
//			mWaterSign.drawFrame(mSignTexId);

			//画水印
//			GLES20.glViewport(160, 300, 320, 60);
//			mWaterSign.drawFrame(mWaterTexId);
			flip = !flip;
			if (flip) {	// ~30fps
				synchronized (this) {
					if (mVideoEncoder != null) {
						// 通知捕获线程相机帧可用。
//						mVideoEncoder.frameAvailableSoon(mStMatrix);
						mVideoEncoder.frameAvailableSoon(mStMatrix, mMvpMatrix);
					}
				}
			}
		}

		@Override
		public void onFrameAvailable(final SurfaceTexture st) {
			requesrUpdateTex = true;
//			final CameraGLView parent = mWeakParent.get();
//			if (parent != null)
//				parent.requestRender();
		}
		/**
		 * 标记是否需要获取当前一帧图片
		 */
		private boolean isPhoto = false;
		public void setPhoto(boolean photo) {
			isPhoto = photo;
		}
	}

	/**
	 * 拍照方法
	 * 实现原理：因为视频一直在绘制画面，拍照时我们只需在视频绘制的那一瞬间的画面保存下来。
	 * 第一步、使用glReadPixels()方法读gl的屏幕像素点到内存；
	 * 第二步、将数据转化成bitmap；
	 * 第三步、将bitmap保存为图片。
	 *
	 * 缺点：虽然是可以边录制边拍照，但是在生成图片的一瞬间视频会有卡顿
	 */
	public void takePicture() {
		if (mRenderer != null) {
			mRenderer.setPhoto(true);
		}
	}
	public static void savePicture(int mWidth,int mHeight) {
		Bitmap mBitmap = createBitmapFromGLSurface(mWidth,mHeight);
		ByteArrayOutputStream bos = new ByteArrayOutputStream();
		mBitmap.compress(Bitmap.CompressFormat.JPEG, 100, bos);
		byte[] bitmapdata = bos.toByteArray();
		ByteArrayInputStream fis = new ByteArrayInputStream(bitmapdata);
		String path = MediaMuxerWrapper.getCaptureFile(Environment.DIRECTORY_MOVIES, ".png").toString();
		try {
			File tmpFile = new File(path);
			FileOutputStream fos = new FileOutputStream(tmpFile);
			byte[] buf = new byte[1024];
			int len;
			while ((len = fis.read(buf)) > 0) {
				fos.write(buf, 0, len);
			}
			fis.close();
			fos.close();
		} catch (FileNotFoundException e) {
			e.printStackTrace();
		} catch (IOException e) {
			e.printStackTrace();
		}
	}

	//将数据转化成Bitmap
	private static Bitmap createBitmapFromGLSurface(int mWidth, int mHeight){
		int w = mWidth;
		int h = mHeight;
		int bitmapBuffer[] = new int[(int) (w * h)];
		int bitmapSource[] = new int[(int) (w * h)];
		IntBuffer buffer = IntBuffer.wrap(bitmapBuffer);
		buffer.position(0);
		try {
			GLES20.glReadPixels(0, 0, w, h, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, buffer);
			for (int i = 0; i < h; i++) {
				for (int j = 0; j < w; j++) {
					int pix = bitmapBuffer[i * w + j];
					int pb = (pix >> 16) & 0xff;
					int pr = (pix << 16) & 0x00ff0000;
					int pix1 = (pix & 0xff00ff00) | pr | pb;
					bitmapSource[(h - i - 1) * w + j] = pix1;
				}
			}
		}catch (GLException e){
			e.printStackTrace();
			return null;
		}
		Bitmap inBitmap = null;
		if (inBitmap == null || !inBitmap.isMutable()
				|| inBitmap.getWidth() != w || inBitmap.getHeight() != h) {
			inBitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
		}
		inBitmap.copyPixelsFromBuffer(buffer);
		return Bitmap.createBitmap(bitmapSource, w, h, Bitmap.Config.ARGB_8888);
	}

	/**
	 * 异步摄像机操作的处理程序类
	 */
	private static final class CameraHandler extends Handler {
		private static final int MSG_PREVIEW_START = 1;
		private static final int MSG_PREVIEW_STOP = 2;
		private CameraThread mThread;

		public CameraHandler(final CameraThread thread) {
			mThread = thread;
		}

		public void startPreview(final int width, final int height) {
			sendMessage(obtainMessage(MSG_PREVIEW_START, width, height));
		}

		/**
		 * 请求停止相机预览
		 * @param needWait 需要等待停止相机预览
		 */
		public void stopPreview(final boolean needWait) {
			synchronized (this) {
				sendEmptyMessage(MSG_PREVIEW_STOP);
				if (needWait && mThread.mIsRunning) {
					try {
						Log.d(TAG, "等待摄像头线程终止 ");
						wait();
					} catch (final InterruptedException e) {
					}
				}
			}
		}

		/**
		 * 摄像头线程的消息处理程序
		 */
		@Override
		public void handleMessage(final Message msg) {
			switch (msg.what) {
			case MSG_PREVIEW_START:
				mThread.startPreview(msg.arg1, msg.arg2);
				break;
			case MSG_PREVIEW_STOP:
				mThread.stopPreview();
				synchronized (this) {
					notifyAll();
				}
				Looper.myLooper().quit();
				mThread = null;
				break;
			default:
				throw new RuntimeException("unknown message:what=" + msg.what);
			}
		}
	}

	/**
	 * 相机预览异步操作线程
	 */
	private static final class CameraThread extends Thread {
    	private final Object mReadyFence = new Object();
    	private final WeakReference<CameraGLView> mWeakParent;
    	private CameraHandler mHandler;
    	private volatile boolean mIsRunning = false;
		private Camera mCamera;
		private boolean mIsFrontFace;

    	public CameraThread(final CameraGLView parent) {
			super("Camera thread");
    		mWeakParent = new WeakReference<CameraGLView>(parent);
    	}

    	public CameraHandler getHandler() {
            synchronized (mReadyFence) {
            	try {
            		mReadyFence.wait();
            	} catch (final InterruptedException e) {
                }
            }
            return mHandler;
    	}

    	/**
    	 * 消息循环
		 *准备循环器并为此线程创建处理程序
    	 */
		@Override
		public void run() {
            Log.d(TAG, "Camera thread start");
            Looper.prepare();
            synchronized (mReadyFence) {
                mHandler = new CameraHandler(this);
                mIsRunning = true;
                mReadyFence.notify();
            }
            Looper.loop();
            Log.d(TAG, "Camera thread finish");
            synchronized (mReadyFence) {
                mHandler = null;
                mIsRunning = false;
            }
		}

		/**
		 * 开始相机预览
		 * @param width
		 * @param height
		 */
		private final void startPreview(final int width, final int height) {
			Log.v(TAG, "startPreview:");
			final CameraGLView parent = mWeakParent.get();
			if ((parent != null) && (mCamera == null)) {
				//这是一个示例项目，因此只需使用0作为相机ID。
				// 最好选择摄像头可用
				try {
					mCamera = Camera.open(CAMERA_ID);
					final Camera.Parameters params = mCamera.getParameters();
					final List<String> focusModes = params.getSupportedFocusModes();
					if (focusModes.contains(Camera.Parameters.FOCUS_MODE_CONTINUOUS_VIDEO)) {
						params.setFocusMode(Camera.Parameters.FOCUS_MODE_CONTINUOUS_VIDEO);
					} else if(focusModes.contains(Camera.Parameters.FOCUS_MODE_AUTO)) {
						params.setFocusMode(Camera.Parameters.FOCUS_MODE_AUTO);
					} else {
						Log.i(TAG, "Camera does not support autofocus");
					}
					// 让我们试试最快的帧速率。你将接近60帧每秒，但你的设备会变热。
					final List<int[]> supportedFpsRange = params.getSupportedPreviewFpsRange();
//					final int n = supportedFpsRange != null ? supportedFpsRange.size() : 0;
//					int[] range;
//					for (int i = 0; i < n; i++) {
//						range = supportedFpsRange.get(i);
//						Log.i(TAG, String.format("supportedFpsRange(%d)=(%d,%d)", i, range[0], range[1]));
//					}
					final int[] max_fps = supportedFpsRange.get(supportedFpsRange.size() - 1);
					Log.i(TAG, String.format("fps:%d-%d", max_fps[0], max_fps[1]));
					params.setPreviewFpsRange(max_fps[0], max_fps[1]);
					params.setRecordingHint(true);
					// 请求最接近支持的预览大小
					final Camera.Size closestSize = getClosestSupportedSize(
						params.getSupportedPreviewSizes(), width, height);
					params.setPreviewSize(closestSize.width, closestSize.height);
					// 在nexus7上为纵横比问题请求最接近的图片大小
					final Camera.Size pictureSize = getClosestSupportedSize(
						params.getSupportedPictureSizes(), width, height);
					params.setPictureSize(pictureSize.width, pictureSize.height);
					// 根据设备方向旋转相机预览
					setRotation(params);
					mCamera.setParameters(params);
					// 获取实际预览大小
					final Camera.Size previewSize = mCamera.getParameters().getPreviewSize();
					Log.i(TAG, String.format("previewSize(%d, %d)", previewSize.width, previewSize.height));
					//通过保持相机预览的纵横比来调整视图大小。
					// 这里不是UI线程，我们应该请求父视图执行。
					parent.post(new Runnable() {
						@Override
						public void run() {
							parent.setVideoSize(previewSize.width, previewSize.height);
						}
					});
					final SurfaceTexture st = parent.getSurfaceTexture();
					st.setDefaultBufferSize(previewSize.width, previewSize.height);
					mCamera.setPreviewTexture(st);
				} catch (final IOException e) {
					Log.e(TAG, "startPreview:", e);
					if (mCamera != null) {
						mCamera.release();
						mCamera = null;
					}
				} catch (final RuntimeException e) {
					Log.e(TAG, "startPreview:", e);
					if (mCamera != null) {
						mCamera.release();
						mCamera = null;
					}
				}
				if (mCamera != null) {
					// 开始相机预览显示
					mCamera.startPreview();
				}
			}
		}

		private static Camera.Size getClosestSupportedSize(List<Camera.Size> supportedSizes, final int requestedWidth, final int requestedHeight) {
			return (Camera.Size) Collections.min(supportedSizes, new Comparator<Camera.Size>() {

				private int diff(final Camera.Size size) {
					return Math.abs(requestedWidth - size.width) + Math.abs(requestedHeight - size.height);
				}

				@Override
				public int compare(final Camera.Size lhs, final Camera.Size rhs) {
					return diff(lhs) - diff(rhs);
				}
			});

		}

		/**
		 * 停止相机预览
		 */
		private void stopPreview() {
			Log.v(TAG, "stopPreview:");
			if (mCamera != null) {
				mCamera.stopPreview();
		        mCamera.release();
		        mCamera = null;
			}
			final CameraGLView parent = mWeakParent.get();
			if (parent == null) return;
			parent.mCameraHandler = null;
		}

		/**
		 * 根据设备方向旋转预览屏幕
		 * @param params
		 */
		private final void setRotation(final Camera.Parameters params) {
			Log.v(TAG, "setRotation:");
			final CameraGLView parent = mWeakParent.get();
			if (parent == null) return;

			final Display display = ((WindowManager)parent.getContext()
				.getSystemService(Context.WINDOW_SERVICE)).getDefaultDisplay();
			final int rotation = display.getRotation();
			int degrees = 0;
			switch (rotation) {
				case Surface.ROTATION_0: degrees = 0; break;
				case Surface.ROTATION_90: degrees = 90; break;
				case Surface.ROTATION_180: degrees = 180; break;
				case Surface.ROTATION_270: degrees = 270; break;
			}
			// 了解摄像头是前置摄像头还是后置摄像头
			final Camera.CameraInfo info =
					new android.hardware.Camera.CameraInfo();
				android.hardware.Camera.getCameraInfo(CAMERA_ID, info);
			mIsFrontFace = (info.facing == Camera.CameraInfo.CAMERA_FACING_FRONT);
			if (mIsFrontFace) {	// 前置摄像头
				degrees = (info.orientation + degrees) % 360;
				degrees = (360 - degrees) % 360;  // reverse
			} else {  // 后置摄像头
				degrees = (info.orientation - degrees + 360) % 360;
			}
			// 应用旋转设置
			mCamera.setDisplayOrientation(degrees);
			parent.mRotation = degrees;
			// 此方法无法调用，相机停止在某些设备上工作。
//			params.setRotation(degrees);
		}
	}
}
