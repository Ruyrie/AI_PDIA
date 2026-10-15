package com.heban.pestai.server.vo;

/** POST ai/asr：识别出的文字（已去掉首尾空白，不为空）。 */
public record AsrVO(String text) {
}
