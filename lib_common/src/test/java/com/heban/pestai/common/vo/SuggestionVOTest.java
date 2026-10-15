package com.heban.pestai.common.vo;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.google.gson.FieldNamingPolicy;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;

import org.junit.Test;

import java.lang.reflect.Type;
import java.util.List;

/**
 * SuggestionVO：新增的可空字段 crop（匹配到的用户作物）按下划线命名解析；旧版服务端不返回时为 null；
 * equals / hashCode 包含 crop。
 */
public class SuggestionVOTest {

    /** 与 NetworkModule 相同的命名策略 */
    private static final Gson GSON = new GsonBuilder()
            .setFieldNamingPolicy(FieldNamingPolicy.LOWER_CASE_WITH_UNDERSCORES)
            .create();

    @Test
    public void parse_list_withAndWithoutCrop() {
        Type type = new TypeToken<List<SuggestionVO>>() {
        }.getType();
        List<SuggestionVO> list = GSON.fromJson("["
                + "{\"id\":1,\"text\":\"番茄叶片发黄卷曲是什么原因？\",\"crop\":\"番茄\"},"
                + "{\"id\":2,\"text\":\"白粉病和霜霉病怎么区分？\",\"crop\":null},"
                + "{\"id\":3,\"text\":\"蚜虫用什么办法防治效果好？\"}]", type);
        assertEquals(3, list.size());
        assertEquals(1, list.get(0).getId());
        assertEquals("番茄叶片发黄卷曲是什么原因？", list.get(0).getText());
        assertEquals("番茄", list.get(0).getCrop());
        assertNull("显式 null", list.get(1).getCrop());
        assertNull("旧版服务端没有该字段", list.get(2).getCrop());
    }

    @Test
    public void roundTrip_keepsCrop() {
        SuggestionVO vo = suggestion(7, "黄瓜叶子发黄卷边是什么原因？", "黄瓜");
        String json = GSON.toJson(vo);
        assertTrue(json.contains("\"crop\":\"黄瓜\""));
        assertEquals(vo, GSON.fromJson(json, SuggestionVO.class));
    }

    @Test
    public void equalsAndHashCode_includeCrop() {
        SuggestionVO base = suggestion(1, "a", "番茄");
        assertEquals(base, suggestion(1, "a", "番茄"));
        assertEquals(base.hashCode(), suggestion(1, "a", "番茄").hashCode());
        assertNotEquals(base, suggestion(1, "a", "黄瓜"));
        assertNotEquals(base, suggestion(1, "a", null));
        assertNotEquals(base, suggestion(1, "b", "番茄"));
        assertNotEquals(base, suggestion(2, "a", "番茄"));
    }

    private static SuggestionVO suggestion(long id, String text, String crop) {
        SuggestionVO vo = new SuggestionVO();
        vo.setId(id);
        vo.setText(text);
        vo.setCrop(crop);
        return vo;
    }
}
