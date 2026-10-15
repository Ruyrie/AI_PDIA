package com.heban.pestai.ai.compose;

import androidx.annotation.NonNull;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import java.util.List;

/**
 * Q01 / Q02 共用输入栏（view_ai_composer.xml + {@link ComposerBar}）的 ViewModel 接口，
 * 由 AskAiViewModel、ChatViewModel 实现：输入文字（双向绑定）、待发送图片队列（{@link ImageQueueStrip}）、
 * 按钮显示状态（{@link ComposerButtons}）与相机 /「+」/ 发送按钮的点击。文字 / 语音输入方式与「按住 说话」见 {@link VoiceComposer}。
 */
public interface ImageComposer {

    /** 输入框文字（双向绑定） */
    @NonNull
    MutableLiveData<String> getInputText();

    /** 输入栏上方的待发送图片（按加入顺序） */
    @NonNull
    LiveData<List<PendingImage>> getImages();

    /** 待发送图片张数（缩略图条显示、输入框提示文字） */
    @NonNull
    LiveData<Integer> getImageCount();

    /** 胶囊中相机 / 输入框或「按住 说话」/ 麦克风或键盘 /「+」/ 发送（停止）的显示状态 */
    @NonNull
    LiveData<ComposerButtons> getComposerButtons();

    /** 相机按钮：直接拍照（页面申请 CAMERA 权限后打开系统相机）；游客弹出登录，已满 9 张时提示 */
    void onCameraClick();

    /** 「+」按钮：直接打开系统照片选择器（可选张数 = 9 − 已添加）；游客弹出登录，已满 9 张时提示 */
    void onAlbumClick();

    /** 右侧圆形按钮：发送；Q02 生成中为停止生成 */
    void onActionClick();

    /** ✕：移除图片并取消它的上传 */
    void removeImage(long id);

    /** 点按上传失败的缩略图：重新上传 */
    void retryImage(long id);
}
