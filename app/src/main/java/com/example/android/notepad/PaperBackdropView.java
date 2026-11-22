/*
 * 列表页与云备份页的点阵手账底纹背景，替代彩色渐变背景，呈现清爽克制的纸质文具质感。
 */

package com.example.android.notepad;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.view.View;

/**
 * 在素雅纯色底面上平铺精细的等距点阵手账纹路，并按网格周期点缀极轻的十字定位刻痕。
 *
 * 单个纹理瓦片在构造时仅生成一次并通过 BitmapShader 硬件平铺，无持续帧动画开销，
 * 在 API 19 至高版本系统上均可保持清晰锐利且零额外耗电。
 */
public class PaperBackdropView extends View {

    // 点阵基本间距（dp）与每隔多少个点放置一枚极轻的十字定位标记。
    private static final float GRID_SPACING_DP = 18f;
    private static final int CROSS_INTERVAL = 4;
    private static final float DOT_RADIUS_DP = 1.05f;
    private static final float CROSS_HALF_DP = 2.6f;
    private static final float CROSS_STROKE_DP = 0.85f;

    private final Paint mTilePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private Bitmap mTileBitmap;

    public PaperBackdropView(Context context, AttributeSet attrs) {
        super(context, attrs);
        buildTileShader(context);
    }

    /**
     * 生成可无缝平铺的正方形点阵纹理瓦片，深浅模式颜色分别来自 values 与 values-night。
     */
    private void buildTileShader(Context context) {
        float density = context.getResources().getDisplayMetrics().density;
        int cellPx = Math.max(8, Math.round(GRID_SPACING_DP * density));
        int tileSize = cellPx * CROSS_INTERVAL;

        mTileBitmap = Bitmap.createBitmap(tileSize, tileSize, Bitmap.Config.ARGB_8888);
        Canvas tileCanvas = new Canvas(mTileBitmap);

        int dotColor = context.getResources().getColor(R.color.np_pattern_dot);
        int crossColor = context.getResources().getColor(R.color.np_pattern_cross);

        Paint dotPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        dotPaint.setColor(dotColor);
        dotPaint.setStyle(Paint.Style.FILL);

        Paint crossPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        crossPaint.setColor(crossColor);
        crossPaint.setStyle(Paint.Style.STROKE);
        crossPaint.setStrokeWidth(Math.max(1f, CROSS_STROKE_DP * density));
        crossPaint.setStrokeCap(Paint.Cap.ROUND);

        float dotRadius = Math.max(1f, DOT_RADIUS_DP * density);
        float crossHalf = Math.max(2f, CROSS_HALF_DP * density);
        float offset = cellPx * 0.5f;

        for (int row = 0; row < CROSS_INTERVAL; row++) {
            float cy = offset + row * cellPx;
            for (int col = 0; col < CROSS_INTERVAL; col++) {
                float cx = offset + col * cellPx;
                if (row == 0 && col == 0) {
                    // 每隔 4×4 格绘制一枚极淡的十字手账刻痕，增强秩序感而不抢占内容视线。
                    tileCanvas.drawLine(cx - crossHalf, cy, cx + crossHalf, cy, crossPaint);
                    tileCanvas.drawLine(cx, cy - crossHalf, cx, cy + crossHalf, crossPaint);
                } else {
                    tileCanvas.drawCircle(cx, cy, dotRadius, dotPaint);
                }
            }
        }

        mTilePaint.setShader(new BitmapShader(
                mTileBitmap, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        // 底色由父容器 android:background="@color/np_background" 提供，此处仅叠画点阵纹路。
        canvas.drawRect(0, 0, getWidth(), getHeight(), mTilePaint);
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        mTilePaint.setShader(null);
        if (mTileBitmap != null) {
            mTileBitmap.recycle();
            mTileBitmap = null;
        }
    }
}
