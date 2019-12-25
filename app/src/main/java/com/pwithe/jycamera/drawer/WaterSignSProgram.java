package com.pwithe.jycamera.drawer;


import android.opengl.GLES20;
import android.util.Log;


/**
 * Created by fenghaitao on 2019/9/12.
 */

public class WaterSignSProgram{
    private static final String TAG = "WaterSignSProgram";
    private static int programId;
    private static final String VERTEX_SHADER =
            "uniform mat4 uMVPMatrix;\n" +
                    "attribute vec4 aPosition;\n" +
                    "attribute vec4 aTextureCoord;\n" +
                    "varying vec2 vTextureCoord;\n" +
                    "void main() {\n" +
                    "    gl_Position = uMVPMatrix * aPosition;\n" +
                    "    vTextureCoord = aTextureCoord.xy;\n" +
                    "}\n";

    private static final String FRAGMENT_SHADER =
            "precision mediump float;\n" +
                    "varying vec2 vTextureCoord;\n" +
                    "uniform sampler2D sTexture;\n" +
                    "void main() {\n" +
                    "    gl_FragColor = texture2D(sTexture, vTextureCoord);\n" +
                    "}\n";

    public WaterSignSProgram() {
        programId = loadShader(VERTEX_SHADER, FRAGMENT_SHADER);

        uMVPMatrixLoc = GLES20.glGetUniformLocation(programId, "uMVPMatrix");
        checkLocation(uMVPMatrixLoc, "uMVPMatrix");
        aPositionLoc = GLES20.glGetAttribLocation(programId, "aPosition");
        checkLocation(aPositionLoc, "aPosition");
        aTextureCoordLoc = GLES20.glGetAttribLocation(programId, "aTextureCoord");
        checkLocation(aTextureCoordLoc, "aTextureCoord");
        sTextureLoc = GLES20.glGetUniformLocation(programId, "sTexture");
        checkLocation(sTextureLoc, "sTexture");
    }

    public int uMVPMatrixLoc;
    public int aPositionLoc;
    public int aTextureCoordLoc;
    public int sTextureLoc;

    public static void checkLocation(int location, String label) {
        if (location < 0) {
            throw new RuntimeException("Unable to locate '" + label + "' in program");
        }
    }

    /**
     * 加载编译连接阴影
     * @param vss source of vertex shader
     * @param fss source of fragment shader
     * @return
     */
    public static int loadShader(final String vss, final String fss) {
        Log.v(TAG, "loadShader:");
        int vs = GLES20.glCreateShader(GLES20.GL_VERTEX_SHADER);
        GLES20.glShaderSource(vs, vss);
        GLES20.glCompileShader(vs);
        final int[] compiled = new int[1];
        GLES20.glGetShaderiv(vs, GLES20.GL_COMPILE_STATUS, compiled, 0);
        if (compiled[0] == 0) {
            Log.e(TAG, "Failed to compile vertex shader:"
                    + GLES20.glGetShaderInfoLog(vs));
            GLES20.glDeleteShader(vs);
            vs = 0;
        }

        int fs = GLES20.glCreateShader(GLES20.GL_FRAGMENT_SHADER);
        GLES20.glShaderSource(fs, fss);
        GLES20.glCompileShader(fs);
        GLES20.glGetShaderiv(fs, GLES20.GL_COMPILE_STATUS, compiled, 0);
        if (compiled[0] == 0) {
            Log.w(TAG, "Failed to compile fragment shader:"
                    + GLES20.glGetShaderInfoLog(fs));
            GLES20.glDeleteShader(fs);
            fs = 0;
        }

        final int program = GLES20.glCreateProgram();
        GLES20.glAttachShader(program, vs);
        GLES20.glAttachShader(program, fs);
        GLES20.glLinkProgram(program);

        return program;
    }

    /**
     * terminatinng, this should be called in GL context
     */
    public static void release() {
        if (programId >= 0)
            GLES20.glDeleteProgram(programId);
        programId = -1;
    }
}