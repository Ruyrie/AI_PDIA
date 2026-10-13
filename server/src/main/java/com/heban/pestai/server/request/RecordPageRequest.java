package com.heban.pestai.server.request;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;

/** GET recognitions 的 Query 参数：mode 可空（全部），page 从 1 开始，size 默认 20、最大 50 */
public class RecordPageRequest {

    /** 空字符串按「全部」处理 */
    @Pattern(regexp = "^(PEST|CROP)?$")
    private String mode;

    @Min(1)
    private Integer page = 1;

    @Min(1)
    @Max(50)
    private Integer size = 20;

    public String getMode() {
        return mode;
    }

    public void setMode(String mode) {
        this.mode = mode;
    }

    public Integer getPage() {
        return page;
    }

    public void setPage(Integer page) {
        this.page = page;
    }

    public Integer getSize() {
        return size;
    }

    public void setSize(Integer size) {
        this.size = size;
    }
}
