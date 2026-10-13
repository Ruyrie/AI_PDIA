package com.heban.pestai.server.config;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import jakarta.annotation.PostConstruct;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 大家常问问题池 pestai.ai.suggestions.*（见 suggestions.yml）：按春夏秋冬四季各配置一组问题。
 */
@ConfigurationProperties(prefix = "pestai.ai.suggestions")
public class AiSuggestionProperties {

    private List<Item> spring = new ArrayList<>();
    private List<Item> summer = new ArrayList<>();
    private List<Item> autumn = new ArrayList<>();
    private List<Item> winter = new ArrayList<>();

    /** 启动时检查 id 全局唯一、text 非空 */
    @PostConstruct
    public void validate() {
        Set<Long> ids = new HashSet<>();
        for (List<Item> pool : List.of(spring, summer, autumn, winter)) {
            for (Item item : pool) {
                if (item.getId() == null || item.getText() == null || item.getText().isBlank()) {
                    throw new IllegalStateException("大家常问配置缺少 id 或 text：" + item.getText());
                }
                if (!ids.add(item.getId())) {
                    throw new IllegalStateException("大家常问配置 id 重复：" + item.getId());
                }
            }
        }
    }

    /** month：1–12；3–5 春、6–8 夏、9–11 秋、12–2 冬 */
    public List<Item> poolOfMonth(int month) {
        if (month >= 3 && month <= 5) {
            return spring;
        }
        if (month >= 6 && month <= 8) {
            return summer;
        }
        if (month >= 9 && month <= 11) {
            return autumn;
        }
        return winter;
    }

    public List<Item> getSpring() {
        return spring;
    }

    public void setSpring(List<Item> spring) {
        this.spring = spring;
    }

    public List<Item> getSummer() {
        return summer;
    }

    public void setSummer(List<Item> summer) {
        this.summer = summer;
    }

    public List<Item> getAutumn() {
        return autumn;
    }

    public void setAutumn(List<Item> autumn) {
        this.autumn = autumn;
    }

    public List<Item> getWinter() {
        return winter;
    }

    public void setWinter(List<Item> winter) {
        this.winter = winter;
    }

    public static class Item {
        private Long id;
        private String text;
        /** 适用作物；为空表示通用问题 */
        private List<String> crops = new ArrayList<>();

        public Item() {
        }

        public Item(Long id, String text, List<String> crops) {
            this.id = id;
            this.text = text;
            this.crops = crops == null ? new ArrayList<>() : crops;
        }

        public Long getId() {
            return id;
        }

        public void setId(Long id) {
            this.id = id;
        }

        public String getText() {
            return text;
        }

        public void setText(String text) {
            this.text = text;
        }

        public List<String> getCrops() {
            return crops;
        }

        public void setCrops(List<String> crops) {
            this.crops = crops;
        }
    }
}
