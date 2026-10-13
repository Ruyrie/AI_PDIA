package com.heban.pestai.ai.chat;

import android.graphics.Rect;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

/**
 * 消息间距：消息组之间 12dp；同一个用户消息组内（引用卡 / 图片 / 文字）6dp。
 */
final class ChatItemDecoration extends RecyclerView.ItemDecoration {

    private final int messageGap;
    private final int groupGap;

    ChatItemDecoration(int messageGap, int groupGap) {
        this.messageGap = messageGap;
        this.groupGap = groupGap;
    }

    @Override
    public void getItemOffsets(@NonNull Rect outRect, @NonNull View view, @NonNull RecyclerView parent,
                               @NonNull RecyclerView.State state) {
        int position = parent.getChildAdapterPosition(view);
        if (position <= 0 || !(parent.getAdapter() instanceof ChatAdapter)) {
            outRect.top = 0;
            return;
        }
        ChatAdapter adapter = (ChatAdapter) parent.getAdapter();
        boolean sameGroup = isUser(adapter.typeAt(position)) && isUser(adapter.typeAt(position - 1));
        outRect.top = sameGroup ? groupGap : messageGap;
    }

    private static boolean isUser(int type) {
        return type == ChatMessage.TYPE_QUOTE || type == ChatMessage.TYPE_USER_TEXT
                || type == ChatMessage.TYPE_USER_IMAGE;
    }
}
