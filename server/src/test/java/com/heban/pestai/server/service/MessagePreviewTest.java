package com.heban.pestai.server.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** 历史对话 last_message：去掉 Markdown 符号、合并空白、最多 60 字、图片消息加「[图片]」。 */
class MessagePreviewTest {

    @Test
    void markdownSymbolsAreRemovedAndWhitespaceCollapsed() {
        String answer = """
                ### 处理建议
                1. 上午通风排湿，湿度控制在 **85%** 以下
                - 摘除病叶，*带出棚外*处理
                * 可选 __烯酰吗啉__ 或 `霜脲·锰锌`，~~随意加量~~
                > 注意安全间隔期
                ---
                详见[农药标签](https://example.com/label)![示意图](https://example.com/a.png)""";
        assertThat(MessagePreview.stripMarkdown(answer)).isEqualTo(
                "处理建议 1. 上午通风排湿，湿度控制在 85% 以下 摘除病叶，带出棚外处理 可选 烯酰吗啉 或 霜脲·锰锌，随意加量 "
                        + "注意安全间隔期 详见农药标签示意图");
    }

    @Test
    void codeFencesAndTablesAreFlattened() {
        String text = "```\n喷雾浓度\n```\n| 药剂 | 用量 |\n|---|---|\n| 吡虫啉 | 按标签 |";
        assertThat(MessagePreview.stripMarkdown(text)).isEqualTo("喷雾浓度 药剂 用量 吡虫啉 按标签");
    }

    @Test
    void plainSymbolsThatAreNotMarkdownAreKept() {
        assertThat(MessagePreview.stripMarkdown("3*4 株距，pH 5.5-6.5，a_b")).isEqualTo("3*4 株距，pH 5.5-6.5，a_b");
    }

    @Test
    void previewIsAtMostSixtyCharactersAndImageMessagesArePrefixed() {
        String longText = "番茄叶片发黄".repeat(20);
        assertThat(MessagePreview.of(longText, null)).hasSize(60).isEqualTo(longText.substring(0, 60));

        String withImage = MessagePreview.of(longText, "chat/20261013/a.jpg");
        assertThat(withImage).startsWith("[图片]番茄").hasSize(60);

        assertThat(MessagePreview.of("**这是什么病？**", "chat/20261013/a.jpg")).isEqualTo("[图片]这是什么病？");
        assertThat(MessagePreview.of("", "chat/20261013/a.jpg")).isEqualTo("[图片]");
        assertThat(MessagePreview.of("😀".repeat(70), null)).isEqualTo("😀".repeat(60));
    }

    @Test
    void emptyContentWithoutImageIsNull() {
        assertThat(MessagePreview.of(null, null)).isNull();
        assertThat(MessagePreview.of("  \n ", "")).isNull();
        assertThat(MessagePreview.of("**  **", null)).isNull();
    }
}
