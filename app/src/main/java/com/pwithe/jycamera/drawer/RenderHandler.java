package com.pwithe.jycamera.drawer;

import android.graphics.SurfaceTexture;
import android.opengl.EGLContext;
import android.opengl.GLES20;
import android.opengl.Matrix;
import android.text.TextUtils;
import android.view.Surface;
import android.view.SurfaceHolder;

import com.pwithe.jycamera.CommApplication;
import com.pwithe.jycamera.R;
import com.pwithe.jycamera.utils.BitmapUtil;

import java.text.SimpleDateFormat;
import java.util.Date;

/**
 *在线程中绘制整个预览页面
 */
public final class RenderHandler implements Runnable {
	private static final String TAG = "RenderHandler";

	private final Object mSync = new Object();
	private EGLContext mShard_context;
	private boolean mIsRecordable;
	private Object mSurface;
	private int mTexId = -1;
	private float[] mMatrix = new float[32];

	private boolean mRequestSetEglContext;
	private boolean mRequestRelease;
	private int mRequestDraw;
	private int mWidth, mHeight;

	public static final RenderHandler createHandler(final String name) {
		final RenderHandler handler = new RenderHandler();
		synchronized (handler.mSync) {
			new Thread(handler, !TextUtils.isEmpty(name) ? name : TAG).start();
			try {
				handler.mSync.wait();
			} catch (final InterruptedException e) {
			}
		}
		return handler;
	}

	public final void setEglContext(final EGLContext shared_context, final int tex_id, final Object surface, final boolean isRecordable) {
		if (!(surface instanceof Surface) && !(surface instanceof SurfaceTexture) && !(surface instanceof SurfaceHolder))
			throw new RuntimeException("unsupported window type:" + surface);
		synchronized (mSync) {
			if (mRequestRelease) return;
			mShard_context = shared_context;
			mWidth = CommApplication.getInstance().getResources().getDisplayMetrics().widthPixels;
			mHeight = CommApplication.getInstance().getResources().getDisplayMetrics().heightPixels;
			mTexId = tex_id;
			mSurface = surface;
			mIsRecordable = isRecordable;
			mRequestSetEglContext = true;
			Matrix.setIdentityM(mMatrix, 0);
			Matrix.setIdentityM(mMatrix, 16);
			mSync.notifyAll();
			try {
				mSync.wait();
			} catch (final InterruptedException e) {
			}
		}
	}

	public final void draw() {
		draw(mTexId, mMatrix, null);
	}

	public final void draw(final int tex_id) {
		draw(tex_id, mMatrix, null);
	}

	public final void draw(final float[] tex_matrix) {
		draw(mTexId, tex_matrix, null);
	}

	public final void draw(final float[] tex_matrix, final float[] mvp_matrix) {
		draw(mTexId, tex_matrix, mvp_matrix);
	}

	public final void draw(final int tex_id, final float[] tex_matrix) {
		draw(tex_id, tex_matrix, null);
	}

	public final void draw(final int tex_id, final float[] tex_matrix, final float[] mvp_matrix) {
		synchronized (mSync) {
			if (mRequestRelease) return;
			mTexId = tex_id;
			if ((tex_matrix != null) && (tex_matrix.length >= 16)) {
				System.arraycopy(tex_matrix, 0, mMatrix, 0, 16);
			} else {
				Matrix.setIdentityM(mMatrix, 0);
			}
			if ((mvp_matrix != null) && (mvp_matrix.length >= 16)) {
				System.arraycopy(mvp_matrix, 0, mMatrix, 16, 16);
			} else {
				Matrix.setIdentityM(mMatrix, 16);
			}
			mRequestDraw++;
			mSync.notifyAll();
/*			try {
				mSync.wait();
			} catch (final InterruptedException e) {
			} */
		}
	}

	public boolean isValid() {
		synchronized (mSync) {
			return !(mSurface instanceof Surface) || ((Surface) mSurface).isValid();
		}
	}

	public final void release() {
		synchronized (mSync) {
			if (mRequestRelease) return;
			mRequestRelease = true;
			mSync.notifyAll();
			try {
				mSync.wait();
			} catch (final InterruptedException e) {
			}
		}
	}

	//********************************************************************************
//********************************************************************************
	private EGLBase mEgl;
	private EGLBase.EglSurface mInputSurface;
	private GLDrawer2D mDrawer;
	private WaterSignature mWaterSign;
	private BitmapUtil bitmapUtil;
	private int mSignTexId;
	private int[] mWaterTexId = new int[12];
	private int[] textureObjectIds = new int[1];
	private final static SimpleDateFormat formatter   = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
	private static Date date = null;
	private String time = ""; //时间戳

	@Override
	public final void run() {
		synchronized (mSync) {
			mRequestSetEglContext = mRequestRelease = false;
			mRequestDraw = 0;
			mSync.notifyAll();
		}
		boolean localRequestDraw;
		for (; ; ) {
			synchronized (mSync) {
				if (mRequestRelease) break;
				if (mRequestSetEglContext) {
					mRequestSetEglContext = false;
					internalPrepare();
				}
				localRequestDraw = mRequestDraw > 0;
				if (localRequestDraw) {
					mRequestDraw--;
//					mSync.notifyAll();
				}
			}
			if (localRequestDraw) {
				if ((mEgl != null) && mTexId >= 0) {
					mInputSurface.makeCurrent();
					// clear screen with yellow color so that you can see rendering rectangle
					GLES20.glClearColor(1.0f, 1.0f, 0.0f, 1.0f);
					GLES20.glClear(GLES20.GL_DEPTH_BUFFER_BIT | GLES20.GL_COLOR_BUFFER_BIT);
					GLES20.glEnable(GLES20.GL_BLEND);
					//开启GL的混合模式，即图像叠加
					GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE_MINUS_SRC_ALPHA);
					//绘制画面的长和宽
					GLES20.glViewport(0, 0, mWidth, mHeight);
					//旋转
					mDrawer.setMatrix(mMatrix, 16);
					// 绘制到预览屏幕
					mDrawer.draw(mTexId, mMatrix);
					//画静态水印
					GLES20.glViewport(20, 20, 100, 50);
					mWaterSign.drawFrame(mSignTexId);
					//添加时间水印
					date = new Date();
					time = formatter.format(date);
					drawWaterSign(time, 160, 300);
					mInputSurface.swap();
				}
			} else {
				synchronized (mSync) {
					try {
						mSync.wait();
					} catch (final InterruptedException e) {
						break;
					}
				}
			}
		}
		synchronized (mSync) {
			mRequestRelease = true;
			internalRelease();
			mSync.notifyAll();
		}
	}

	private final void internalPrepare() {
		internalRelease();
		mEgl = new EGLBase(mShard_context, false, mIsRecordable);

		mInputSurface = mEgl.createFromSurface(mSurface);

		mInputSurface.makeCurrent();

		//设置水印
		mWaterSign = new WaterSignature();
		mWaterSign.setShaderProgram(new WaterSignSProgram());
		mSignTexId = TextureHelper.loadTexture(CommApplication.getInstance(), R.mipmap.watermark);
		//初始化文字转图片所需的对象，避免多次生成新对象消耗过多内存
//		if (bitmapUtil == null) {
			bitmapUtil = CommApplication.getBitmapUtil();
			//将字符图片与纹理绑定，返回纹理id
			for (int i = 0; i < 12; i++) {
				if (i == 10) {
					mWaterTexId[i] = TextureHelper.loadTexture(bitmapUtil.textToBitmap("-"), textureObjectIds);
				} else if (i == 11) {
					mWaterTexId[i] = TextureHelper.loadTexture(bitmapUtil.textToBitmap(":"), textureObjectIds);
				} else {
					mWaterTexId[i] = TextureHelper.loadTexture(bitmapUtil.textToBitmap(i + ""), textureObjectIds);
				}
			}
//		}
		mDrawer = new GLDrawer2D();
		mSurface = null;
		mSync.notifyAll();
	}

	private final void internalRelease() {
		if (mInputSurface != null) {
			mInputSurface.release();
			mInputSurface = null;
		}
		if (mDrawer != null) {
			mDrawer.release();
			mDrawer = null;
		}
		if (mWaterSign != null) {
			mWaterSign.release();
			mWaterSign = null;
		}
		if (mEgl != null) {
			mEgl.release();
			mEgl = null;
		}
		if (date != null) {
			date = null;
		}
	}

	/**
	 * 添加时间水印
	 * 原理：将文字转成图片，使用OpenGL将图片画到视频上
	 * 实现方法：将时间水印所需的文字转化为图片（避免每次实时转换导致大量内存被消耗），如：2019-09-05 16:52:18  共16张图片。
	 * 每张图片绑定一个纹理；获取当前时间切出单个字符，使用OpenGL将对应的字符纹理画上去。
	 *
	 * @param time
	 * @param x
	 * @param y    位于屏幕的（x,y）坐标点
	 */
	private void drawWaterSign(String time, int x, int y) {
		if ("".equals(time)) {
			return;
		}
		//画水印
		GLES20.glViewport(x, y, 220, 60);
		mWaterSign.drawFrame(mWaterTexId[Integer.parseInt(time.substring(0, 1))]);
		GLES20.glViewport(x + 15, y, 220, 60);
		mWaterSign.drawFrame(mWaterTexId[Integer.parseInt(time.substring(1, 2))]);
		GLES20.glViewport(x + 15 * 2, y, 220, 60);
		mWaterSign.drawFrame(mWaterTexId[Integer.parseInt(time.substring(2, 3))]);
		GLES20.glViewport(x + 15 * 3, y, 220, 60);
		mWaterSign.drawFrame(mWaterTexId[Integer.parseInt(time.substring(3, 4))]);
		GLES20.glViewport(x + 15 * 4, y, 220, 60);
		mWaterSign.drawFrame(mWaterTexId[10]); // -
		GLES20.glViewport(x + 15 * 5, y, 220, 60);
		mWaterSign.drawFrame(mWaterTexId[Integer.parseInt(time.substring(5, 6))]);
		GLES20.glViewport(x + 15 * 6, y, 220, 60);
		mWaterSign.drawFrame(mWaterTexId[Integer.parseInt(time.substring(6, 7))]);
		GLES20.glViewport(x + 15 * 7, y, 220, 60);
		mWaterSign.drawFrame(mWaterTexId[10]); // -
		GLES20.glViewport(x + 15 * 8, y, 220, 60);
		mWaterSign.drawFrame(mWaterTexId[Integer.parseInt(time.substring(8, 9))]);
		GLES20.glViewport(x + 15 * 9, y, 220, 60);
		mWaterSign.drawFrame(mWaterTexId[Integer.parseInt(time.substring(9, 10))]);
		GLES20.glViewport(x + 15 * 11, y, 220, 60);
		mWaterSign.drawFrame(mWaterTexId[Integer.parseInt(time.substring(11, 12))]);
		GLES20.glViewport(x + 15 * 12, y, 220, 60);
		mWaterSign.drawFrame(mWaterTexId[Integer.parseInt(time.substring(12, 13))]);
		GLES20.glViewport(x + 15 * 13, y, 220, 60);
		mWaterSign.drawFrame(mWaterTexId[11]); // :
		GLES20.glViewport(x + 15 * 14, y, 220, 60);
		mWaterSign.drawFrame(mWaterTexId[Integer.parseInt(time.substring(14, 15))]);
		GLES20.glViewport(x + 15 * 15, y, 220, 60);
		mWaterSign.drawFrame(mWaterTexId[Integer.parseInt(time.substring(15, 16))]);
		GLES20.glViewport(x + 15 * 16, y, 220, 60);
		mWaterSign.drawFrame(mWaterTexId[11]); // :
		GLES20.glViewport(x + 15 * 17, y, 220, 60);
		mWaterSign.drawFrame(mWaterTexId[Integer.parseInt(time.substring(17, 18))]);
		GLES20.glViewport(x + 15 * 18, y, 220, 60);
		mWaterSign.drawFrame(mWaterTexId[Integer.parseInt(time.substring(18, 19))]);
	}

}
