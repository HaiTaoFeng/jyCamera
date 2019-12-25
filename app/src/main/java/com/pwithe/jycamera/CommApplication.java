package com.pwithe.jycamera;

import android.app.Application;
import android.content.Context;

import com.pwithe.jycamera.utils.BitmapUtil;

/**
 * Created by ASUS on 2019/2/24.
 */

public class CommApplication extends Application {
    public static CommApplication instance;
    public static Context mContext ;
    public static BitmapUtil bitmapUtil;

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        bitmapUtil = BitmapUtil.getInstance();
    }

    public static CommApplication getInstance() {
        return instance;
    }

    public static BitmapUtil getBitmapUtil() {
        return bitmapUtil;
    }
}
