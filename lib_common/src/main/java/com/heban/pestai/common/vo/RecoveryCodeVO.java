package com.heban.pestai.common.vo;

import java.util.Date;
import java.util.Objects;

/**
 * 账号恢复码（user/recovery-code）。
 * 前后端共用的 VO，字段与《05-公共数据模型与示例数据》一致；JSON 使用下划线命名（Gson LOWER_CASE_WITH_UNDERSCORES）。
 */
public class RecoveryCodeVO {

    /** 是否已生成恢复码 */
    private boolean generated;
    /** 生成时间，未生成时为空 */
    private Date createdAt;
    /** 恢复码明文，只在刚生成时返回一次（如 ABCD-EFGH-JKLM），其他情况为空 */
    private String recoveryCode;
    /** 生成恢复码是否需要短信验证码：10 分钟内刚通过短信验证过手机号时为 false */
    private boolean verifyRequired = true;

    public RecoveryCodeVO() {
    }

    public boolean isGenerated() {
        return generated;
    }

    public void setGenerated(boolean generated) {
        this.generated = generated;
    }

    public Date getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Date createdAt) {
        this.createdAt = createdAt;
    }

    public String getRecoveryCode() {
        return recoveryCode;
    }

    public void setRecoveryCode(String recoveryCode) {
        this.recoveryCode = recoveryCode;
    }

    public boolean isVerifyRequired() {
        return verifyRequired;
    }

    public void setVerifyRequired(boolean verifyRequired) {
        this.verifyRequired = verifyRequired;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        RecoveryCodeVO that = (RecoveryCodeVO) o;
        return generated == that.generated
                && Objects.equals(createdAt, that.createdAt)
                && Objects.equals(recoveryCode, that.recoveryCode)
                && verifyRequired == that.verifyRequired;
    }

    @Override
    public int hashCode() {
        return Objects.hash(generated, createdAt, recoveryCode, verifyRequired);
    }
}
