package com.heban.pestai.recognize.ui.scan;

import androidx.annotation.NonNull;

import java.util.Objects;

/**
 * S01 一个变焦档位的显示数据（由 ScanViewModel 生成，ScanActivity 只负责渲染）。
 * 当前档位显示相机的实际倍数（如两档之间时为「2.3×」），其余档位显示档位倍数。
 */
public final class ZoomChip {

    /** 点击后缩放到的倍数 */
    public final float preset;
    /** 圆形档位上的文字 */
    @NonNull
    public final String label;
    /** 是否为当前档位（放大显示、强调色文字） */
    public final boolean active;
    /** 无障碍描述，如「缩放到 2 倍」 */
    @NonNull
    public final String description;

    public ZoomChip(float preset, @NonNull String label, boolean active, @NonNull String description) {
        this.preset = preset;
        this.label = label;
        this.active = active;
        this.description = description;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof ZoomChip)) {
            return false;
        }
        ZoomChip that = (ZoomChip) o;
        return Float.compare(preset, that.preset) == 0 && active == that.active
                && label.equals(that.label) && description.equals(that.description);
    }

    @Override
    public int hashCode() {
        return Objects.hash(preset, label, active, description);
    }

    @NonNull
    @Override
    public String toString() {
        return "ZoomChip{" + label + (active ? ", active" : "") + '}';
    }
}
