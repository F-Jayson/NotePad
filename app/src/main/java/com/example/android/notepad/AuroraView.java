/*
 * T04 新增：列表页的极光背景。
 */

package com.example.android.notepad;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.os.Build;
import android.os.SystemClock;
import android.util.AttributeSet;
import android.view.View;

/**
 * 在页面底色上绘制三团缓慢漂移的径向渐变柔光，玻璃卡片和顶部栏叠在其上方呈现通透感。
 *
 * 每团柔光的着色器只在尺寸变化时创建一次，逐帧只平移画布，避免动画中反复分配对象。
 * 动画约 30 帧／秒，窗口失去焦点或不可见时自动暂停；API 21 以下只绘制静态画面以节省性能。
 */
public class AuroraView extends View {

    // 每团柔光的基准位置（占宽高比例）、半径（占较长边比例）、漂移幅度和周期（毫秒）。
    private static final float[][] BLOBS = {
            // baseX, baseY, radius, ampX, ampY, periodMs, phase
            { 0.12f, 0.10f, 0.62f, 0.10f, 0.05f, 21000f, 0.0f },
            { 0.92f, 0.32f, 0.55f, 0.08f, 0.08f, 26000f, 1.7f },
            { 0.30f, 0.86f, 0.60f, 0.12f, 0.06f, 31000f, 3.4f },
    };
    private static final long FRAME_DELAY_MS = 33;

    private final int[] mColors;
    private final Paint[] mPaints = new Paint[BLOBS.length];
    private final float[] mRadii = new float[BLOBS.length];
    private final boolean mAnimate = Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP;
    private final long mStartTime = SystemClock.uptimeMillis();

    public AuroraView(Context context, AttributeSet attrs) {
        super(context, attrs);
        // 颜色来自 colors.xml，深浅模式各有一套。
        mColors = new int[] {
                context.getResources().getColor(R.color.np_aurora_1),
                context.getResources().getColor(R.color.np_aurora_2),
                context.getResources().getColor(R.color.np_aurora_3),
        };
        for (int i = 0; i < mPaints.length; i++) {
            mPaints[i] = new Paint(Paint.ANTI_ALIAS_FLAG);
        }
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        float base = Math.max(w, h);
        for (int i = 0; i < BLOBS.length; i++) {
            mRadii[i] = Math.max(1f, base * BLOBS[i][2]);
            // 以原点为圆心创建渐变，绘制时平移到当前位置；颜色外缘渐隐为全透明。
            int color = mColors[i];
            int transparent = color & 0x00FFFFFF;
            // 中段取一半透明度，使柔光边缘过渡更自然。
            int half = (((color >>> 24) / 2) << 24) | transparent;
            mPaints[i].setShader(new RadialGradient(0, 0, mRadii[i],
                    new int[] { color, half, transparent },
                    new float[] { 0f, 0.45f, 1f }, Shader.TileMode.CLAMP));
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        int w = getWidth();
        int h = getHeight();
        float t = mAnimate ? (SystemClock.uptimeMillis() - mStartTime) : 0f;
        for (int i = 0; i < BLOBS.length; i++) {
            float[] b = BLOBS[i];
            double angle = 2 * Math.PI * t / b[5] + b[6];
            float x = w * (b[0] + b[3] * (float) Math.sin(angle));
            float y = h * (b[1] + b[4] * (float) Math.cos(angle * 0.8));
            canvas.save();
            canvas.translate(x, y);
            canvas.drawCircle(0, 0, mRadii[i], mPaints[i]);
            canvas.restore();
        }
        // 只在窗口可见且有焦点时预约下一帧。
        if (mAnimate && isShown() && hasWindowFocus()) {
            postInvalidateDelayed(FRAME_DELAY_MS);
        }
    }

    @Override
    public void onWindowFocusChanged(boolean hasWindowFocus) {
        super.onWindowFocusChanged(hasWindowFocus);
        // 从编辑器或对话框返回后恢复漂移动画。
        if (hasWindowFocus) {
            invalidate();
        }
    }
}
