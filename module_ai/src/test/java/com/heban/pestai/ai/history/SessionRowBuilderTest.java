package com.heban.pestai.ai.history;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.heban.pestai.common.vo.SessionItemVO;

import org.junit.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.List;

/** 历史对话分组（今天 / 昨天 / 近 7 天 / 更早）、组内首末行与时间格式 */
public class SessionRowBuilderTest {

    static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 13, 18, 0);

    static Date at(LocalDateTime time) {
        return Date.from(time.atZone(ZoneId.systemDefault()).toInstant());
    }

    static SessionItemVO session(long id, String title, LocalDateTime updatedAt, Long recordId) {
        SessionItemVO vo = new SessionItemVO();
        vo.setSessionId(id);
        vo.setTitle(title);
        vo.setLastMessage("最后一条消息 " + id);
        vo.setMessageCount(2);
        vo.setRecordId(recordId);
        vo.setUpdatedAt(at(updatedAt));
        return vo;
    }

    @Test
    public void groupOf_boundaries() {
        LocalDate today = NOW.toLocalDate();
        assertEquals(SessionRowBuilder.GROUP_TODAY, SessionRowBuilder.groupOf(at(NOW.withHour(0)), today));
        assertEquals("服务器时间略快", SessionRowBuilder.GROUP_TODAY,
                SessionRowBuilder.groupOf(at(NOW.plusDays(1)), today));
        assertEquals(SessionRowBuilder.GROUP_YESTERDAY,
                SessionRowBuilder.groupOf(at(NOW.minusDays(1).withHour(23)), today));
        assertEquals(SessionRowBuilder.GROUP_WEEK, SessionRowBuilder.groupOf(at(NOW.minusDays(2)), today));
        assertEquals("6 天前仍是近 7 天", SessionRowBuilder.GROUP_WEEK,
                SessionRowBuilder.groupOf(at(NOW.minusDays(6).withHour(0)), today));
        assertEquals(SessionRowBuilder.GROUP_EARLIER, SessionRowBuilder.groupOf(at(NOW.minusDays(7)), today));
        assertEquals(SessionRowBuilder.GROUP_EARLIER, SessionRowBuilder.groupOf(null, today));
    }

    @Test
    public void build_groupsWithHeaders_andFirstLastFlags() {
        List<SessionItemVO> list = Arrays.asList(
                session(1, "番茄叶片发黄卷曲是什么原因？", NOW.withHour(14).withMinute(20), null),
                session(2, "追问：黄瓜霜霉病", NOW.withHour(9).withMinute(5), 2036L),
                session(3, "图片提问", NOW.minusDays(1).withHour(20), null),
                session(4, "白菜软腐病怎么预防？", NOW.minusDays(3), null),
                session(5, "蚜虫用什么药？", NOW.minusDays(5), null),
                session(6, "", NOW.minusDays(40), null));
        List<SessionRow> rows = SessionRowBuilder.build(list, NOW, 0);

        assertEquals(6 + 4, rows.size());
        SessionRow today = rows.get(0);
        assertEquals(SessionRow.TYPE_HEADER, today.getType());
        assertEquals("今天", today.getTitle());
        assertTrue("第一组", today.isFirst());

        SessionRow s1 = rows.get(1);
        assertEquals(SessionRow.TYPE_SESSION, s1.getType());
        assertEquals("14:20", s1.getTime());
        assertTrue(s1.isFirst());
        assertFalse(s1.isLast());
        assertFalse(s1.isFollowUp());
        assertEquals("最后一条消息 1", s1.getPreview());

        SessionRow s2 = rows.get(2);
        assertEquals("09:05", s2.getTime());
        assertTrue("关联识别记录显示「识别追问」", s2.isFollowUp());
        assertTrue(s2.isLast());

        assertEquals("昨天", rows.get(3).getTitle());
        assertFalse(rows.get(3).isFirst());
        SessionRow s3 = rows.get(4);
        assertEquals("昨天", s3.getTime());
        assertTrue(s3.isFirst() && s3.isLast());

        assertEquals("近 7 天", rows.get(5).getTitle());
        assertEquals("10月10日", rows.get(6).getTime());
        assertTrue(rows.get(6).isFirst());
        assertTrue(rows.get(7).isLast());

        assertEquals("更早", rows.get(8).getTitle());
        SessionRow s6 = rows.get(9);
        assertEquals("标题为空时显示「新对话」", "新对话", s6.getTitle());
        assertEquals("09月03日", s6.getTime());
    }

    @Test
    public void build_footerAndEmpty() {
        assertTrue(SessionRowBuilder.build(Collections.emptyList(), NOW, SessionRow.FOOTER_LOADING).isEmpty());
        List<SessionRow> rows = SessionRowBuilder.build(
                Collections.singletonList(session(1, "a", NOW, null)), NOW, SessionRow.FOOTER_FAILED);
        assertEquals(3, rows.size());
        assertEquals(SessionRow.TYPE_FOOTER, rows.get(2).getType());
        assertEquals(SessionRow.FOOTER_FAILED, rows.get(2).getFooterState());
    }

    @Test
    public void build_previewIsSingleLine() {
        SessionItemVO vo = session(1, "a", NOW, null);
        vo.setLastMessage("第一行\n第二行\r\n第三行");
        assertEquals("第一行 第二行 第三行", SessionRowBuilder.build(Collections.singletonList(vo), NOW, 0)
                .get(1).getPreview());
        vo.setLastMessage(null);
        assertEquals("", SessionRowBuilder.build(Collections.singletonList(vo), NOW, 0).get(1).getPreview());
    }

    @Test
    public void build_outOfOrderGroups_keepUniqueHeaderKeys() {
        List<SessionRow> rows = SessionRowBuilder.build(Arrays.asList(
                session(1, "a", NOW, null),
                session(2, "b", NOW.minusDays(1), null),
                session(3, "c", NOW, null)), NOW, 0);
        assertEquals(6, rows.size());
        assertFalse(rows.get(0).getKey().equals(rows.get(4).getKey()));
    }
}
