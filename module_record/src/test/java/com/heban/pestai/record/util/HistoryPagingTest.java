package com.heban.pestai.record.util;

import static org.junit.Assert.assertEquals;

import com.heban.pestai.common.vo.RecordItemVO;
import com.heban.pestai.record.TestRecords;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;

public class HistoryPagingTest {

    @Test
    public void nextPageFromLoadedCount() {
        assertEquals(1, HistoryPaging.nextPage(0, 20, 0, false));
        assertEquals(2, HistoryPaging.nextPage(20, 20, 1, false));
        assertEquals(3, HistoryPaging.nextPage(40, 20, 2, false));
    }

    @Test
    public void nextPageGoesBackAfterDelete() {
        // 加载了 40 条后删除 1 条：服务器上后续记录前移，回退到第 2 页，重复的记录由 merge 去重
        assertEquals(2, HistoryPaging.nextPage(39, 20, 2, false));
        assertEquals(1, HistoryPaging.nextPage(19, 20, 1, false));
    }

    @Test
    public void nextPageSkipsAheadWhenLastPageAddedNothing() {
        // 上一次请求第 1 页去重后没有新增：改用 lastPage + 1，避免反复请求同一页
        assertEquals(2, HistoryPaging.nextPage(19, 20, 1, true));
        assertEquals(4, HistoryPaging.nextPage(40, 20, 3, true));
        // 推算结果本来就更大时取推算结果
        assertEquals(3, HistoryPaging.nextPage(40, 20, 1, true));
    }

    @Test
    public void mergeAppendsInServerOrder() {
        List<RecordItemVO> target = new ArrayList<>(TestRecords.range(100, 3));
        int added = HistoryPaging.merge(target, TestRecords.range(97, 3), Collections.emptySet());
        assertEquals(3, added);
        assertEquals(Arrays.asList(100L, 99L, 98L, 97L, 96L, 95L), ids(target));
    }

    @Test
    public void mergeSkipsDuplicatesAndExcluded() {
        List<RecordItemVO> target = new ArrayList<>(TestRecords.range(100, 3));
        // 新一页与已加载的 98 重复，97 正在删除 / 等待撤销，95 在本页中重复出现
        List<RecordItemVO> incoming = new ArrayList<>(TestRecords.range(98, 4));
        incoming.add(TestRecords.range(95, 1).get(0));
        int added = HistoryPaging.merge(target, incoming, new HashSet<>(Collections.singletonList(97L)));
        assertEquals(2, added);
        assertEquals(Arrays.asList(100L, 99L, 98L, 96L, 95L), ids(target));
    }

    @Test
    public void mergeReturnsZeroWhenAllKnown() {
        List<RecordItemVO> target = new ArrayList<>(TestRecords.range(100, 5));
        assertEquals(0, HistoryPaging.merge(target, TestRecords.range(99, 3), Collections.emptySet()));
        assertEquals(5, target.size());
    }

    private static List<Long> ids(List<RecordItemVO> list) {
        List<Long> ids = new ArrayList<>();
        for (RecordItemVO item : list) {
            ids.add(item.getId());
        }
        return ids;
    }
}
