package com.heban.pestai.server.vo;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;

import com.heban.pestai.server.common.ApiResponse;
import com.heban.pestai.server.common.RecoverMethod;
import com.heban.pestai.server.config.JacksonConfig;
import com.heban.pestai.server.request.MessageRequest;
import com.heban.pestai.server.request.RecoverRebindRequest;
import com.heban.pestai.server.request.RecoverVerifyRequest;
import com.heban.pestai.server.request.SmsSendRequest;

/** VO 的 JSON 字段名、类型、日期格式必须与公共数据 05 完全一致（App 端 Gson 下划线命名）。 */
class VoJsonTest {

    private final ObjectMapper mapper = JacksonConfig.newObjectMapper();

    @Test
    void loginVoUsesSnakeCaseAndIsNew() throws Exception {
        UserVO user = new UserVO(10001L, "13800005678", "老李种菜", null, "江苏省 南京市 江宁区", "种植户", "番茄,黄瓜,小麦");
        JsonNode json = mapper.readTree(mapper.writeValueAsString(ApiResponse.success(new LoginVO("t", user, true))));

        assertThat(json.path("code").asInt()).isZero();
        assertThat(json.path("message").asText()).isEqualTo("ok");
        JsonNode data = json.path("data");
        assertThat(data.path("is_new").asBoolean()).isTrue();
        assertThat(data.has("new")).isFalse();
        assertThat(data.path("user").has("avatar_url")).isTrue();
        assertThat(data.path("user").path("avatar_url").isNull()).isTrue();
        assertThat(data.path("user").path("crops").asText()).isEqualTo("番茄,黄瓜,小麦");
    }

    @Test
    void recognitionVoFieldsAndFormats() throws Exception {
        RecognitionVO vo = new RecognitionVO(1024L, "PEST", "http://10.0.2.2:8080/files/rec/20261013/a.jpg", "SUCCESS",
                "黄瓜霜霉病", new BigDecimal("0.92"), "中度", null, LocalDateTime.of(2026, 10, 13, 14, 20, 5),
                "卵菌病害", "叶部", true, "依据", 3,
                List.of(new CandidateVO("黄瓜细菌性角斑病", new BigDecimal("0.06"), "也有多角形病斑", "叶背是菌脓、没有霉层"),
                        new CandidateVO("缺镁症（生理性）", new BigDecimal("0.02"), null, null)), "通义千问");
        String text = mapper.writeValueAsString(vo);
        JsonNode json = mapper.readTree(text);

        assertThat(json.fieldNames()).toIterable().containsExactlyInAnyOrder(
                "id", "mode", "image_url", "status", "top_name", "confidence", "severity", "fail_reason", "created_at",
                "category", "part", "spread_fast", "evidence", "urgency_days", "others", "model_name");
        assertThat(json.path("created_at").asText()).isEqualTo("2026-10-13 14:20:05");
        assertThat(text).contains("\"confidence\":0.92").contains("\"spread_fast\":true");
        assertThat(json.path("others").path(0).path("confidence").decimalValue()).isEqualByComparingTo("0.06");
        // 其他可能：reason / difference 字段名不变；没有时为 null（字段保留，不省略）
        assertThat(json.path("others").path(0).fieldNames()).toIterable()
                .containsExactly("name", "confidence", "reason", "difference");
        assertThat(json.path("others").path(0).path("reason").asText()).isEqualTo("也有多角形病斑");
        assertThat(json.path("others").path(0).path("difference").asText()).isEqualTo("叶背是菌脓、没有霉层");
        assertThat(json.path("others").path(1).has("reason")).isTrue();
        assertThat(json.path("others").path(1).path("reason").isNull()).isTrue();
        assertThat(json.path("others").path(1).path("difference").isNull()).isTrue();
    }

    @Test
    void pageVoUsesHasMore() throws Exception {
        String text = mapper.writeValueAsString(new PageVO<>(List.of(), 6L, false));
        assertThat(text).isEqualTo("{\"list\":[],\"total\":6,\"has_more\":false}");
    }

    @Test
    void statsSessionTicketVersionNames() throws Exception {
        assertThat(mapper.writeValueAsString(new UserStatsVO(36, 12, 18, 9, null)))
                .isEqualTo("{\"total\":36,\"pest_kinds\":12,\"ai_count\":18,\"month_count\":9,\"last_record\":null}");
        assertThat(mapper.writeValueAsString(new SessionVO(512L, 2036L, "追问：黄瓜霜霉病")))
                .isEqualTo("{\"session_id\":512,\"record_id\":2036,\"title\":\"追问：黄瓜霜霉病\"}");
        assertThat(mapper.writeValueAsString(new ResetTicketVO("abc", 600)))
                .isEqualTo("{\"reset_ticket\":\"abc\",\"expire_in\":600}");
        assertThat(mapper.writeValueAsString(new AppVersionVO(2, "1.1.0", "u", false, null)))
                .isEqualTo("{\"version_code\":2,\"version_name\":\"1.1.0\",\"download_url\":\"u\","
                        + "\"force_update\":false,\"release_note\":null}");
    }

    @Test
    void suggestionVoHasNullableCrop() throws Exception {
        assertThat(mapper.writeValueAsString(new SuggestionVO(301L, "番茄叶片发黄卷曲是什么原因？", "番茄")))
                .isEqualTo("{\"id\":301,\"text\":\"番茄叶片发黄卷曲是什么原因？\",\"crop\":\"番茄\"}");
        // 通用问题 / 未匹配：crop 为 null（字段保留，不省略）
        assertThat(mapper.writeValueAsString(new SuggestionVO(305L, "秋季大棚白粉病怎么防治？", null)))
                .isEqualTo("{\"id\":305,\"text\":\"秋季大棚白粉病怎么防治？\",\"crop\":null}");
    }

    @Test
    void recoverVosMatchDocumentFieldNames() throws Exception {
        assertThat(mapper.writeValueAsString(new RebindTicketVO("0123456789abcdef0123456789abcdef", 600, "PASSWORD")))
                .isEqualTo("{\"rebind_ticket\":\"0123456789abcdef0123456789abcdef\",\"expire_in\":600,"
                        + "\"method\":\"PASSWORD\"}");
        // 查询状态：recovery_code 为 null（字段保留，不省略）
        assertThat(mapper.writeValueAsString(new RecoveryCodeVO(false, null, null, true)))
                .isEqualTo("{\"generated\":false,\"created_at\":null,\"recovery_code\":null,\"verify_required\":true}");
        assertThat(mapper.writeValueAsString(
                new RecoveryCodeVO(true, LocalDateTime.of(2026, 10, 13, 9, 30, 0), "ABCD-EFGH-JKLM", false)))
                .isEqualTo("{\"generated\":true,\"created_at\":\"2026-10-13 09:30:00\","
                        + "\"recovery_code\":\"ABCD-EFGH-JKLM\",\"verify_required\":false}");
    }

    @Test
    void historyVosMatchDocumentFieldNames() throws Exception {
        LocalDateTime at = LocalDateTime.of(2026, 10, 13, 14, 23, 5);
        assertThat(mapper.writeValueAsString(new SessionItemVO(512L, null, "新对话", null, 1, at)))
                .isEqualTo("{\"session_id\":512,\"record_id\":null,\"title\":\"新对话\",\"last_message\":null,"
                        + "\"message_count\":1,\"updated_at\":\"2026-10-13 14:23:05\"}");

        ChatMessageVO answer = new ChatMessageVO("assistant", "答", null, null, at, "想", 1200);
        // 没有图片：image_url 为 null，image_urls 为空数组（不为 null）
        assertThat(mapper.writeValueAsString(answer))
                .isEqualTo("{\"role\":\"assistant\",\"content\":\"答\",\"image_url\":null,\"image_urls\":[],"
                        + "\"created_at\":\"2026-10-13 14:23:05\",\"reasoning_content\":\"想\",\"reasoning_ms\":1200}");
        ChatMessageVO question = new ChatMessageVO("user", "", "http://x/files/chat/a.jpg",
                List.of("http://x/files/chat/a.jpg", "http://x/files/chat/b.png"), at, null, null);
        assertThat(mapper.writeValueAsString(question))
                .isEqualTo("{\"role\":\"user\",\"content\":\"\",\"image_url\":\"http://x/files/chat/a.jpg\","
                        + "\"image_urls\":[\"http://x/files/chat/a.jpg\",\"http://x/files/chat/b.png\"],"
                        + "\"created_at\":\"2026-10-13 14:23:05\",\"reasoning_content\":null,\"reasoning_ms\":null}");

        JsonNode detail = mapper.readTree(mapper.writeValueAsString(
                new SessionDetailVO(512L, 2036L, "追问：黄瓜霜霉病", null, List.of(answer))));
        assertThat(detail.fieldNames()).toIterable()
                .containsExactly("session_id", "record_id", "title", "record", "messages");
        assertThat(detail.path("record").isNull()).isTrue();
        assertThat(detail.path("messages").path(0).path("reasoning_ms").asInt()).isEqualTo(1200);
    }

    @Test
    void requestsAreReadFromSnakeCase() throws Exception {
        MessageRequest m = mapper.readValue(
                "{\"content\":\"怎么办\",\"image_url\":\"http://x/files/chat/a.jpg\",\"regenerate\":true,\"extra\":1}",
                MessageRequest.class);
        assertThat(m.getImageUrl()).isEqualTo("http://x/files/chat/a.jpg");
        assertThat(m.getImageUrls()).isNull();
        assertThat(m.isRegenerateRequested()).isTrue();

        MessageRequest multi = mapper.readValue(
                "{\"content\":\"\",\"image_urls\":[\"http://x/files/chat/a.jpg\",\"http://x/files/chat/b.png\"]}",
                MessageRequest.class);
        assertThat(multi.getContent()).isEmpty();
        assertThat(multi.getImageUrls()).containsExactly("http://x/files/chat/a.jpg", "http://x/files/chat/b.png");
        assertThat(multi.getImageUrl()).isNull();
        assertThat(multi.isRegenerateRequested()).isFalse();

        SmsSendRequest s = mapper.readValue("{\"phone\":\"13800005678\",\"scene\":\"REBIND\"}", SmsSendRequest.class);
        assertThat(s.getScene().name()).isEqualTo("REBIND");

        RecoverVerifyRequest v = mapper.readValue("{\"old_phone\":\"13800005678\",\"method\":\"RECOVERY_CODE\","
                + "\"recovery_code\":\"abcd-efgh-jklm\"}", RecoverVerifyRequest.class);
        assertThat(v.getOldPhone()).isEqualTo("13800005678");
        assertThat(v.getMethod()).isEqualTo(RecoverMethod.RECOVERY_CODE);
        assertThat(v.getRecoveryCode()).isEqualTo("abcd-efgh-jklm");

        RecoverRebindRequest r = mapper.readValue("{\"old_phone\":\"13800005678\",\"rebind_ticket\":\"t\","
                + "\"new_phone\":\"13900001111\",\"code\":\"048215\",\"new_password\":\"newpass123\"}",
                RecoverRebindRequest.class);
        assertThat(r.getRebindTicket()).isEqualTo("t");
        assertThat(r.getNewPhone()).isEqualTo("13900001111");
        assertThat(r.getNewPassword()).isEqualTo("newpass123");
    }
}
