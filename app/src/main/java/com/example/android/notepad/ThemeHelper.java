/*
 * T04 新增：深浅色外观的保存、应用与切换动画。
 */

package com.example.android.notepad;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Path;
import android.graphics.Region;
import android.os.Build;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.view.Window;
import android.view.WindowManager;
import android.view.animation.DecelerateInterpolator;

/**
 * 管理应用内的深浅色外观。
 *
 * 外观不引入 AppCompat：各 Activity 在 attachBaseContext 中把 uiMode 的夜间位覆盖为用户选择，
 * 系统随后自动选用 values-night、drawable-night 中的资源，布局和图形只需引用同名颜色。
 * 选择保存在 SharedPreferences 中，默认跟随系统。
 */
final class ThemeHelper {

    // 外观选择的保存位置与三种取值。
    private static final String PREFS_NAME = "appearance";
    private static final String KEY_MODE = "theme_mode";
    // 纸张样式独立于昼夜模式，切换主题时沿用同一种纸张。
    private static final String KEY_WARM_PAPER = "warm_paper";
    static final int MODE_SYSTEM = 0;
    static final int MODE_LIGHT = 1;
    static final int MODE_DARK = 2;

    // 切换外观前截下的旧界面及揭示动画圆心，供重建后的 Activity 播放过渡动画。
    private static Bitmap sRevealBitmap;
    private static float sRevealX;
    private static float sRevealY;

    private ThemeHelper() {
    }

    static int getMode(Context context) {
        return prefs(context).getInt(KEY_MODE, MODE_SYSTEM);
    }

    static void setMode(Context context, int mode) {
        // 重建 Activity 时会从同一个内存中的偏好对象读取，apply 足以保证立即可见。
        prefs(context).edit().putInt(KEY_MODE, mode).apply();
    }

    /** 所有笔记共用纸张选择，首次打开使用白纸。 */
    static boolean usesWarmPaper(Context context) {
        return prefs(context).getBoolean(KEY_WARM_PAPER, false);
    }

    static void setWarmPaper(Context context, boolean warm) {
        prefs(context).edit().putBoolean(KEY_WARM_PAPER, warm).apply();
    }

    /**
     * 在 Activity.attachBaseContext 中调用。选择“跟随系统”时不做覆盖；
     * 固定深浅色时只覆盖 uiMode，其余配置保持未定义，以免覆盖系统字号等设置。
     */
    static void applyNightMode(Activity activity, Context base) {
        int mode = getMode(base);
        if (mode == MODE_SYSTEM) {
            return;
        }
        Configuration override = new Configuration();
        // API 25 及以下 new Configuration() 会把字号设为 1.0，置 0 表示不覆盖系统字号。
        override.fontScale = 0f;
        int type = base.getResources().getConfiguration().uiMode
                & Configuration.UI_MODE_TYPE_MASK;
        int night = mode == MODE_DARK
                ? Configuration.UI_MODE_NIGHT_YES : Configuration.UI_MODE_NIGHT_NO;
        override.uiMode = type | night;
        activity.applyOverrideConfiguration(override);
    }

    /** 当前上下文实际使用的是否为深色资源。 */
    static boolean isNight(Context context) {
        return (context.getResources().getConfiguration().uiMode
                & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
    }

    /** 系统本身是否处于深色模式，用于“跟随系统”时判断切换后是否需要重建。 */
    static boolean isSystemNight(Context context) {
        return isNight(context.getApplicationContext());
    }

    /**
     * 设置系统栏。drawBehind 为 true 时内容延伸到状态栏和导航栏下方（列表页），
     * 否则系统栏使用页面底色（编辑器）。浅色模式下的深色图标由主题属性 windowLightStatusBar 提供。
     */
    @SuppressWarnings("deprecation")
    static void setupSystemBars(Activity activity, boolean drawBehind) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) {
            return;
        }
        Window window = activity.getWindow();
        window.clearFlags(WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS);
        window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
        boolean light = !isNight(activity);
        // API 23 前状态栏图标只能是白色，API 26 前导航栏图标只能是白色，浅色模式下需加半透明遮罩。
        int scrim = activity.getResources().getColor(R.color.np_bar_scrim);
        int background = activity.getResources().getColor(R.color.np_background);
        int statusColor;
        int navColor;
        if (drawBehind) {
            statusColor = light && Build.VERSION.SDK_INT < Build.VERSION_CODES.M
                    ? scrim : Color.TRANSPARENT;
            navColor = light && Build.VERSION.SDK_INT < Build.VERSION_CODES.O
                    ? scrim : Color.TRANSPARENT;
        } else {
            statusColor = light && Build.VERSION.SDK_INT < Build.VERSION_CODES.M
                    ? scrim : background;
            navColor = light && Build.VERSION.SDK_INT < Build.VERSION_CODES.O
                    ? scrim : background;
        }
        window.setStatusBarColor(statusColor);
        window.setNavigationBarColor(navColor);

        View decor = window.getDecorView();
        int flags = decor.getSystemUiVisibility();
        if (drawBehind) {
            flags |= View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION;
        }
        if (light && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            flags |= View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
        }
        if (light && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            flags |= View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
        }
        decor.setSystemUiVisibility(flags);
        if (drawBehind && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // 去掉系统为透明导航栏自动添加的白色遮罩，让极光背景延伸到底部。
            window.setNavigationBarContrastEnforced(false);
        }
    }

    /**
     * 切换外观前调用：截下当前窗口，记录揭示圆心（窗口坐标）。
     * 截图失败（如内存不足）时直接跳过动画，不影响切换本身。
     */
    static void captureForReveal(Activity activity, float centerX, float centerY) {
        releaseReveal();
        View decor = activity.getWindow().getDecorView();
        if (decor.getWidth() == 0 || decor.getHeight() == 0) {
            return;
        }
        try {
            Bitmap bitmap = Bitmap.createBitmap(decor.getWidth(), decor.getHeight(),
                    Bitmap.Config.ARGB_8888);
            decor.draw(new Canvas(bitmap));
            sRevealBitmap = bitmap;
            sRevealX = centerX;
            sRevealY = centerY;
        } catch (OutOfMemoryError e) {
            sRevealBitmap = null;
        }
    }

    /** 重建后是否有待播放的揭示动画。 */
    static boolean hasPendingReveal() {
        return sRevealBitmap != null;
    }

    /**
     * 在新 Activity 的 setContentView 之后调用：把旧界面截图盖在最上层，
     * 首帧绘制前开始从圆心向外“挖空”，新外观随圆形扩散逐渐显露。
     */
    static void playPendingReveal(Activity activity) {
        final Bitmap bitmap = sRevealBitmap;
        if (bitmap == null) {
            return;
        }
        sRevealBitmap = null;
        final ViewGroup decor = (ViewGroup) activity.getWindow().getDecorView();
        final RevealOverlay overlay = new RevealOverlay(activity, bitmap, sRevealX, sRevealY);
        decor.addView(overlay, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        overlay.getViewTreeObserver().addOnPreDrawListener(
                new ViewTreeObserver.OnPreDrawListener() {
                    @Override
                    public boolean onPreDraw() {
                        overlay.getViewTreeObserver().removeOnPreDrawListener(this);
                        overlay.start(decor);
                        return true;
                    }
                });
    }

    private static void releaseReveal() {
        if (sRevealBitmap != null) {
            sRevealBitmap.recycle();
            sRevealBitmap = null;
        }
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    /**
     * 绘制旧界面截图，并在其中挖去一个不断扩大的圆。
     */
    private static final class RevealOverlay extends View {
        private final Bitmap mBitmap;
        private final float mCenterX;
        private final float mCenterY;
        private final Path mHole = new Path();
        private float mRadius;

        RevealOverlay(Context context, Bitmap bitmap, float centerX, float centerY) {
            super(context);
            mBitmap = bitmap;
            mCenterX = centerX;
            mCenterY = centerY;
        }

        void start(final ViewGroup parent) {
            // 半径需覆盖到离圆心最远的屏幕角。
            float dx = Math.max(mCenterX, getWidth() - mCenterX);
            float dy = Math.max(mCenterY, getHeight() - mCenterY);
            float maxRadius = (float) Math.hypot(dx, dy);
            ValueAnimator animator = ValueAnimator.ofFloat(0f, maxRadius);
            animator.setDuration(620);
            animator.setInterpolator(new DecelerateInterpolator(1.6f));
            animator.addUpdateListener(animation -> {
                mRadius = (float) animation.getAnimatedValue();
                invalidate();
            });
            animator.addListener(new AnimatorListenerAdapter() {
                @Override
                public void onAnimationEnd(Animator animation) {
                    parent.removeView(RevealOverlay.this);
                    mBitmap.recycle();
                }
            });
            animator.start();
        }

        @Override
        @SuppressWarnings("deprecation")
        protected void onDraw(Canvas canvas) {
            if (mBitmap.isRecycled()) {
                return;
            }
            mHole.reset();
            mHole.addCircle(mCenterX, mCenterY, mRadius, Path.Direction.CW);
            canvas.save();
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                canvas.clipOutPath(mHole);
            } else {
                canvas.clipPath(mHole, Region.Op.DIFFERENCE);
            }
            canvas.drawBitmap(mBitmap, 0, 0, null);
            canvas.restore();
        }
    }
}
