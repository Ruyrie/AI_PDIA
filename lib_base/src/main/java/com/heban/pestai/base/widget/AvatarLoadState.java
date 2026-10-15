package com.heban.pestai.base.widget;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.Objects;

/**
 * {@link AvatarView} 的头像加载状态（纯 Java，不依赖 Android，便于单元测试）。
 * <p>AvatarView 内有两个叠放的图片槽（0、1）：新头像总是加载到「后台槽」，加载成功后才与「前台槽」交换并释放旧图，
 * 切换头像（如 A06 本地预览 → 上传后的网络地址）的过程中旧头像一直显示，不会闪出昵称首字；
 * 加载失败时清除前台头像、显示昵称首字，并忘记本次请求，之后再设置同一地址会重新加载。</p>
 */
final class AvatarLoadState {

    /** 没有槽（未显示头像 / 没有进行中的加载） */
    static final int NO_SLOT = -1;

    /** {@link #request(Object)} 的处理结果，由 AvatarView 执行 */
    enum Action {
        /** 与当前请求相同（加载中或已显示），不需要处理 */
        NONE,
        /** 显示昵称首字：取消进行中的加载并清除已显示的头像 */
        SHOW_INITIAL,
        /** 前台已显示该头像：只取消后台进行中的加载 */
        KEEP_FRONT,
        /** 在 {@link #pendingSlot()} 开始加载（会替换该槽上进行中的旧加载） */
        LOAD
    }

    /** 最近一次请求的头像（已规范化）；null 表示显示首字 */
    @Nullable
    private Object requested;
    /** 前台槽正在显示的头像 */
    @Nullable
    private Object frontModel;
    private int frontSlot = NO_SLOT;
    private int pendingSlot = NO_SLOT;

    /** 空字符串视为没有头像 */
    @Nullable
    static Object normalize(@Nullable Object image) {
        if (image instanceof CharSequence && ((CharSequence) image).toString().trim().isEmpty()) {
            return null;
        }
        return image;
    }

    /**
     * 设置要显示的头像。调用方应在调用前记下 {@link #frontSlot()}、{@link #pendingSlot()}，
     * 按返回的动作释放对应的槽。
     */
    @NonNull
    Action request(@Nullable Object image) {
        Object model = normalize(image);
        if (Objects.equals(model, requested)) {
            return Action.NONE;
        }
        requested = model;
        if (model == null) {
            frontSlot = NO_SLOT;
            frontModel = null;
            pendingSlot = NO_SLOT;
            return Action.SHOW_INITIAL;
        }
        if (model.equals(frontModel)) {
            pendingSlot = NO_SLOT;
            return Action.KEEP_FRONT;
        }
        pendingSlot = frontSlot == 0 ? 1 : 0;
        return Action.LOAD;
    }

    /**
     * 某个槽加载成功。返回 true 表示它是当前等待的加载：该槽成为前台，调用方显示它并释放调用前的前台槽；
     * 返回 false 表示过期结果（已被新的请求取代），调用方忽略。
     */
    boolean onLoaded(int slot) {
        if (slot == NO_SLOT || slot != pendingSlot) {
            return false;
        }
        frontSlot = slot;
        frontModel = requested;
        pendingSlot = NO_SLOT;
        return true;
    }

    /**
     * 某个槽加载失败。返回 true 表示它是当前等待的加载：清除前台头像、显示首字，并忘记本次请求
     * （之后设置同一地址会重新加载，不会被「与当前请求相同」挡住）；返回 false 表示过期结果，调用方忽略。
     */
    boolean onFailed(int slot) {
        if (slot == NO_SLOT || slot != pendingSlot) {
            return false;
        }
        requested = null;
        frontModel = null;
        frontSlot = NO_SLOT;
        pendingSlot = NO_SLOT;
        return true;
    }

    /** 前台槽：正在显示头像的槽，没有时为 {@link #NO_SLOT} */
    int frontSlot() {
        return frontSlot;
    }

    /** 后台槽：正在加载的槽，没有时为 {@link #NO_SLOT} */
    int pendingSlot() {
        return pendingSlot;
    }

    /** 前台槽正在显示的头像 */
    @Nullable
    Object frontModel() {
        return frontModel;
    }

    /** 最近一次请求的头像 */
    @Nullable
    Object requested() {
        return requested;
    }
}
