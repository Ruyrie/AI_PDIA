package com.heban.pestai.record.util;

import androidx.annotation.NonNull;

import com.heban.pestai.common.vo.RecordItemVO;

import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 分页页码推算与按 id 去重合并（模块07 技术准备文档 2.7.4、2.9）。纯 Java，可单元测试。
 */
public final class HistoryPaging {

    private HistoryPaging() {
    }

    /**
     * 加载更多的页码：「已加载条数 ÷ 每页条数（取整）+ 1」。按已加载条数推算而不是自增页码：
     * 删除记录后服务器上的后续记录前移，推算出的页码随之回退，重复的记录由 {@link #merge} 去重，不会漏数据。
     * 上一次加载更多去重后一条也没有新增（例如其他设备在顶部插入了新记录）时，改用「上次页码 + 1」，
     * 避免反复请求同一页。
     *
     * @param loadedCount          已加载的记录条数
     * @param pageSize             每页条数
     * @param lastPage             上一次请求的页码（尚未请求过为 0）
     * @param lastPageAddedNothing 上一次加载更多去重后是否一条也没有新增
     */
    public static int nextPage(int loadedCount, int pageSize, int lastPage, boolean lastPageAddedNothing) {
        int computed = Math.max(0, loadedCount) / pageSize + 1;
        return lastPageAddedNothing ? Math.max(computed, lastPage + 1) : computed;
    }

    /**
     * 按服务器顺序把 incoming 追加到 target 末尾：id 已在 target 中出现、在 excludedIds 中
     * （正在删除 / 等待撤销的记录）或在 incoming 中重复出现的记录跳过。
     *
     * @return 实际追加的条数
     */
    public static int merge(@NonNull List<RecordItemVO> target, @NonNull List<RecordItemVO> incoming,
                            @NonNull Collection<Long> excludedIds) {
        Set<Long> seen = new HashSet<>(target.size() + incoming.size());
        for (RecordItemVO item : target) {
            seen.add(item.getId());
        }
        int added = 0;
        for (RecordItemVO item : incoming) {
            if (item == null || excludedIds.contains(item.getId()) || !seen.add(item.getId())) {
                continue;
            }
            target.add(item);
            added++;
        }
        return added;
    }
}
