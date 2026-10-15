package com.heban.pestai.ai.util;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;

import androidx.activity.result.PickVisualMediaRequest;
import androidx.activity.result.contract.ActivityResultContract;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.Collections;
import java.util.List;

/**
 * 系统照片选择器（只选图片），输入为本次最多可选的张数（9 − 已添加），结果总是 List&lt;Uri&gt;（取消为空列表）：
 * 可选 2 张及以上时交给 {@link ActivityResultContracts.PickMultipleVisualMedia}（多选上限不能小于 2），
 * 只剩 1 张时交给 {@link ActivityResultContracts.PickVisualMedia}（单选）。
 * <p>没有系统照片选择器的设备会退回 ACTION_OPEN_DOCUMENT，此时上限不生效，调用方需自行截断。
 * 解析时单选结果在 data、多选在 clipData；进程被回收后本对象重建、不知道上次是哪种方式时用多选的解析（两者都读取）。</p>
 */
public final class PickImagesContract extends ActivityResultContract<Integer, List<Uri>> {

    /** 上一次 createIntent 是否为单选 */
    private boolean single;

    @NonNull
    @Override
    public Intent createIntent(@NonNull Context context, @NonNull Integer maxItems) {
        PickVisualMediaRequest request = new PickVisualMediaRequest.Builder()
                .setMediaType(ActivityResultContracts.PickVisualMedia.ImageOnly.INSTANCE)
                .build();
        single = maxItems < 2;
        if (single) {
            return new ActivityResultContracts.PickVisualMedia().createIntent(context, request);
        }
        return new ActivityResultContracts.PickMultipleVisualMedia(maxItems).createIntent(context, request);
    }

    @NonNull
    @Override
    public List<Uri> parseResult(int resultCode, @Nullable Intent intent) {
        if (single) {
            Uri uri = new ActivityResultContracts.PickVisualMedia().parseResult(resultCode, intent);
            return uri == null ? Collections.emptyList() : Collections.singletonList(uri);
        }
        return new ActivityResultContracts.PickMultipleVisualMedia(AiConstants.MAX_IMAGES)
                .parseResult(resultCode, intent);
    }
}
