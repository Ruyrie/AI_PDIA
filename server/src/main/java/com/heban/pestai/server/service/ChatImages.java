package com.heban.pestai.server.service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.heban.pestai.server.entity.AiMessage;
import com.heban.pestai.server.entity.AiMessageImage;
import com.heban.pestai.server.mapper.AiMessageMapper;

/**
 * 带图提问的图片（一条消息最多 9 张，相对路径如 chat/20261014/{uuid}.jpg）。
 * <ul>
 *   <li>写入（AiMessageTxService.saveQuestion）：全部图片按顺序写入 t_ai_message_image，t_ai_message.image_url 存第一张
 *       （历史对话列表据此显示「[图片]」，旧版本也能读到）；</li>
 *   <li>读取：查询消息后用 {@link #attach} 一次查出这些消息的图片行，再用 {@link #of} 取每条消息的图片；
 *       没有图片行的消息（迁移 005 之前写入、未回填）退回 [image_url]。后台查看会话消息（AdminUserService）同样使用。</li>
 * </ul>
 */
public final class ChatImages {

    private ChatImages() {
    }

    /**
     * 为消息填入 imageUrls（按 sort_no）。只有 image_url 有值的消息才可能有图片行（写入时 image_url 为第一张，
     * 迁移 005 由 image_url 回填），因此只查这些消息；都没有图片时不查库。返回传入的列表本身。
     */
    public static List<AiMessage> attach(AiMessageMapper mapper, List<AiMessage> messages) {
        List<Long> ids = new ArrayList<>();
        for (AiMessage m : messages) {
            if (m.getId() != null && hasText(m.getImageUrl())) {
                ids.add(m.getId());
            }
        }
        if (ids.isEmpty()) {
            return messages;
        }
        Map<Long, List<String>> byMessage = new HashMap<>();
        List<AiMessageImage> rows = mapper.selectImagesByMessageIds(ids);
        if (rows != null) {
            for (AiMessageImage row : rows) {
                if (row.getMessageId() != null && hasText(row.getImageUrl())) {
                    byMessage.computeIfAbsent(row.getMessageId(), k -> new ArrayList<>()).add(row.getImageUrl());
                }
            }
        }
        for (AiMessage m : messages) {
            List<String> paths = byMessage.get(m.getId());
            if (paths != null) {
                m.setImageUrls(List.copyOf(paths));
            }
        }
        return messages;
    }

    /** 消息的全部图片相对路径（按顺序）：有图片行时取图片行，否则退回 [image_url]；没有图片为空列表 */
    public static List<String> of(AiMessage m) {
        if (m.getImageUrls() != null && !m.getImageUrls().isEmpty()) {
            return m.getImageUrls();
        }
        return hasText(m.getImageUrl()) ? List.of(m.getImageUrl()) : List.of();
    }

    private static boolean hasText(String s) {
        return s != null && !s.isBlank();
    }
}
