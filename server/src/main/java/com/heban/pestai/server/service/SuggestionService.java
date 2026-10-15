package com.heban.pestai.server.service;

import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.springframework.stereotype.Service;

import com.heban.pestai.server.config.AiSuggestionProperties;
import com.heban.pestai.server.mapper.UserMapper;
import com.heban.pestai.server.vo.SuggestionVO;

/**
 * 大家常问：按季节取问题池（suggestions.yml），适用作物与用户主要作物（清洗规则见 UserProfile）有交集的问题优先；
 * 以「当天在一年中的序号」对组长度取余作为起点循环挑选，同一天结果稳定、每天轮换一批，最多 3 条。
 * 按作物挑中的问题带 crop（用户作物中第一个属于该问题适用作物的），其余为 null。
 */
@Service
public class SuggestionService {

    public static final int LIMIT = 3;

    private final AiSuggestionProperties properties;
    private final UserMapper userMapper;
    private final Clock clock;

    public SuggestionService(AiSuggestionProperties properties, UserMapper userMapper, Clock clock) {
        this.properties = properties;
        this.userMapper = userMapper;
        this.clock = clock;
    }

    public List<SuggestionVO> listForUser(long userId) {
        return pick(LocalDate.now(clock), userMapper.selectCropsById(userId));
    }

    List<SuggestionVO> pick(LocalDate date, String userCrops) {
        List<AiSuggestionProperties.Item> pool = properties.poolOfMonth(date.getMonthValue());
        if (pool == null || pool.isEmpty()) {
            return List.of();
        }
        List<String> crops = UserProfile.cropsOf(userCrops);
        List<AiSuggestionProperties.Item> matched = new ArrayList<>();
        List<AiSuggestionProperties.Item> rest = new ArrayList<>();
        for (AiSuggestionProperties.Item item : pool) {
            (matchedCrop(item, crops) != null ? matched : rest).add(item);
        }
        int dayOfYear = date.getDayOfYear();
        List<SuggestionVO> result = new ArrayList<>();
        Set<Long> used = new LinkedHashSet<>();
        takeRotating(matched, crops, dayOfYear, result, used);
        takeRotating(rest, List.of(), dayOfYear, result, used);
        return result;
    }

    /** crops 为空列表时（未匹配的一组）crop 都为 null */
    private static void takeRotating(List<AiSuggestionProperties.Item> group, List<String> crops, int dayOfYear,
                                     List<SuggestionVO> result, Set<Long> used) {
        if (group.isEmpty()) {
            return;
        }
        int start = dayOfYear % group.size();
        for (int i = 0; i < group.size() && result.size() < LIMIT; i++) {
            AiSuggestionProperties.Item item = group.get((start + i) % group.size());
            if (used.add(item.getId())) {
                result.add(new SuggestionVO(item.getId(), item.getText(), matchedCrop(item, crops)));
            }
        }
    }

    /** 用户作物（按填写顺序）中第一个属于该问题适用作物的；通用问题或没有交集时为 null */
    static String matchedCrop(AiSuggestionProperties.Item item, List<String> crops) {
        if (item.getCrops() == null || item.getCrops().isEmpty()) {
            return null;
        }
        for (String crop : crops) {
            if (item.getCrops().contains(crop)) {
                return crop;
            }
        }
        return null;
    }
}
