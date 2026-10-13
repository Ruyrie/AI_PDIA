package com.heban.pestai.home.data;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.google.gson.FieldNamingPolicy;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import com.heban.pestai.common.vo.RecordItemVO;
import com.heban.pestai.common.vo.UserVO;
import com.heban.pestai.network.ApiResponse;
import com.heban.pestai.network.di.NetworkModule;

import org.junit.Test;

import java.lang.reflect.Type;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;

/**
 * 模块04 技术准备文档 2.10「VO 解析」与 HomeRepository 的列表预处理。
 */
public class HomeDataTest {

    /** 与 NetworkModule 中的单例 Gson 配置一致 */
    private final Gson gson = new GsonBuilder()
            .setFieldNamingPolicy(FieldNamingPolicy.LOWER_CASE_WITH_UNDERSCORES)
            .setDateFormat(NetworkModule.DATE_FORMAT)
            .create();

    @Test
    public void parseRecentResponse() {
        String json = "{\"code\":0,\"message\":\"ok\",\"data\":[{"
                + "\"id\":1024,\"mode\":\"PEST\","
                + "\"image_url\":\"http://10.0.2.2:8080/files/rec/20261010/3f2b8c1e9a7d4e6fb1c05d2a7e9f4b38.jpg\","
                + "\"status\":\"SUCCESS\",\"top_name\":\"黄瓜霜霉病\",\"confidence\":0.92,\"severity\":\"中度\","
                + "\"fail_reason\":null,\"created_at\":\"2026-10-10 14:20:00\",\"unknown_field\":1}]}";
        Type type = new TypeToken<ApiResponse<List<RecordItemVO>>>() {
        }.getType();
        ApiResponse<List<RecordItemVO>> response = gson.fromJson(json, type);

        assertTrue(response.isSuccess());
        assertEquals(1, response.getData().size());
        RecordItemVO item = response.getData().get(0);
        assertEquals(1024L, item.getId());
        assertEquals("PEST", item.getMode());
        assertEquals("http://10.0.2.2:8080/files/rec/20261010/3f2b8c1e9a7d4e6fb1c05d2a7e9f4b38.jpg", item.getImageUrl());
        assertEquals("SUCCESS", item.getStatus());
        assertEquals("黄瓜霜霉病", item.getTopName());
        assertEquals(0.92, item.getConfidence(), 1e-9);
        assertEquals("中度", item.getSeverity());
        assertNull(item.getFailReason());
        Date expected = Date.from(LocalDateTime.of(2026, 10, 10, 14, 20).atZone(ZoneId.systemDefault()).toInstant());
        assertEquals(expected, item.getCreatedAt());
    }

    @Test
    public void userJsonRoundTrip() {
        UserVO user = new UserVO();
        user.setId(1001);
        user.setPhone("13800005678");
        user.setNickname("老李种菜");
        user.setAvatarUrl("http://10.0.2.2:8080/files/avatar/20261001/a.jpg");
        user.setRegion("江苏省 南京市 江宁区");
        user.setIdentity("种植户");
        user.setCrops("黄瓜,番茄");

        String json = gson.toJson(user);
        assertTrue(json.contains("\"avatar_url\""));
        UserVO back = gson.fromJson(json, UserVO.class);
        assertEquals(user, back);
    }

    @Test
    public void limit_nullAndOversizedLists() {
        assertTrue(HomeRepository.limit(null, 3).isEmpty());

        List<RecordItemVO> five = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            RecordItemVO item = new RecordItemVO();
            item.setId(i);
            five.add(item);
        }
        List<RecordItemVO> limited = HomeRepository.limit(five, 3);
        assertEquals(3, limited.size());
        assertEquals(0L, limited.get(0).getId());
        assertEquals(2L, limited.get(2).getId());

        List<RecordItemVO> two = new ArrayList<>(five.subList(0, 2));
        List<RecordItemVO> copy = HomeRepository.limit(two, 3);
        assertEquals(two, copy);
        assertNotSame(two, copy);

        List<RecordItemVO> withNull = Arrays.asList(five.get(0), null, five.get(1));
        assertEquals(2, HomeRepository.limit(withNull, 3).size());
        assertNotNull(HomeRepository.limit(new ArrayList<>(), 3));
    }
}
