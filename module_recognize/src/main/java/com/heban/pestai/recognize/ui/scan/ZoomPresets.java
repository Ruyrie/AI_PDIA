package com.heban.pestai.recognize.ui.scan;

import androidx.annotation.NonNull;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * S01 变焦档位的纯计算（不依赖 Android 框架，便于单元测试）。
 * <ul>
 *     <li>档位：最小倍数 ≤ 0.7 时有超广角档（取 0.5 与最小倍数中较大者），固定有 1×，最大倍数 ≥ 2 / ≥ 5 时有 2× / 5×；</li>
 *     <li>当前档位：不大于当前倍数的最大档位，当前倍数先按一位小数四舍五入（1.98 显示为「2×」，也高亮 2× 档）；</li>
 *     <li>文字：一位小数、去掉末尾「.0」，不小于 1 时加「×」（「2.3×」「1×」），小于 1 时不加（「0.5」，圆形档位放得下）。</li>
 * </ul>
 */
public final class ZoomPresets {

    /** 设备最小倍数不大于此值才显示超广角档 */
    static final float ULTRA_WIDE_THRESHOLD = 0.7f;
    /** 超广角档的倍数（设备最小倍数更大时取设备最小倍数） */
    static final float ULTRA_WIDE = 0.5f;
    /** 长焦档：最大倍数达到才显示 */
    private static final float[] TELE = {2f, 5f};
    private static final String TIMES = "×";
    /** 比较一位小数时的容差 */
    private static final float EPSILON = 0.001f;

    private ZoomPresets() {
    }

    /** 按设备变焦范围生成档位（升序）；范围异常时至少返回一个档位 */
    @NonNull
    public static List<Float> of(float min, float max) {
        List<Float> list = new ArrayList<>();
        if (min <= ULTRA_WIDE_THRESHOLD) {
            list.add(Math.max(ULTRA_WIDE, min));
        }
        if (min <= 1f && max >= 1f) {
            list.add(1f);
        }
        for (float tele : TELE) {
            if (max >= tele) {
                list.add(tele);
            }
        }
        if (list.isEmpty()) {
            list.add(min);
        }
        return Collections.unmodifiableList(list);
    }

    /** 当前档位下标：不大于当前倍数（一位小数）的最大档位；比所有档位都小时取第一个，没有档位返回 -1 */
    public static int activeIndex(@NonNull List<Float> presets, float ratio) {
        if (presets.isEmpty()) {
            return -1;
        }
        float shown = tenths(ratio) / 10f;
        int index = 0;
        for (int i = 0; i < presets.size(); i++) {
            if (presets.get(i) <= shown + EPSILON) {
                index = i;
            }
        }
        return index;
    }

    /** 档位与倍数胶囊的文字：「2.3×」「1×」「0.5」 */
    @NonNull
    public static String format(float ratio) {
        String number = number(ratio);
        return tenths(ratio) >= 10 ? number + TIMES : number;
    }

    /** 不带「×」的倍数（无障碍描述用）：一位小数、去掉末尾「.0」，如「2.3」「2」「0.5」 */
    @NonNull
    public static String number(float ratio) {
        int tenths = tenths(ratio);
        if (tenths % 10 == 0) {
            return String.valueOf(tenths / 10);
        }
        return (tenths / 10) + "." + (tenths % 10);
    }

    /** 把倍数限制在设备范围内；NaN 取最小倍数 */
    public static float clamp(float ratio, float min, float max) {
        if (Float.isNaN(ratio) || ratio < min) {
            return min;
        }
        return Math.min(ratio, max);
    }

    /**
     * 点档位时的动画插值：按比例（几何）插值，1×→2× 与 2×→4× 看起来一样快；
     * fraction 为 0 / 1 时精确返回起点 / 终点。
     */
    public static float interpolate(float from, float to, float fraction) {
        if (fraction <= 0f) {
            return from;
        }
        if (fraction >= 1f) {
            return to;
        }
        if (from <= 0f || to <= 0f) {
            return from + (to - from) * fraction;
        }
        return (float) (from * Math.pow(to / from, fraction));
    }

    /** 四舍五入到十分位的整数（2.34 → 23） */
    private static int tenths(float ratio) {
        return Math.round(ratio * 10f);
    }
}
