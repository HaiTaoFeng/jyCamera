package com.pwithe.jycamera.utils;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.Typeface;

public class BitmapUtil {
    private static Bitmap bmp  = null;
    private static Canvas canvasTemp = null;
    private static Paint p;
    private static String familyName ="宋体";
    private static Typeface font;

    private static volatile BitmapUtil instance;
    public static BitmapUtil getInstance() {
        if (instance == null) {
            synchronized (BitmapUtil.class) {
                if (instance == null) {
                    instance = new BitmapUtil();
                }
            }
        }
        return instance;
    }

    private BitmapUtil(){
        bmp = Bitmap.createBitmap(320,60, Bitmap.Config.ARGB_8888);
        canvasTemp = new Canvas(bmp);
        p = new Paint();
        font = Typeface.create(familyName, Typeface.BOLD);
    }

    //文字转图片
    public static Bitmap textToBitmap(String msg){
        p.setColor(Color.WHITE);
        p.setTypeface(font);
        p.setTextSize(30);
        //设置透明画布，无背景色
        canvasTemp.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR);
        canvasTemp.drawText(msg,0,40,p);
        return bmp;
    }

    public void recyle(){
        if (bmp != null) {
            bmp.recycle();
            bmp = null;
        }
    }
}
