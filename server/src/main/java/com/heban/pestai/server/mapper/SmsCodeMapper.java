package com.heban.pestai.server.mapper;

import java.time.LocalDateTime;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import com.heban.pestai.server.entity.SmsCode;

/** t_sms_code */
@Mapper
public interface SmsCodeMapper {

    /** 同手机号、同场景最近一条（idx_phone_scene_created 倒序扫描） */
    SmsCode selectLatest(@Param("phone") String phone, @Param("scene") String scene);

    int insert(SmsCode smsCode);

    /** 核销：条件 id 且 used = 0，返回影响行数（0 表示已被并发请求核销） */
    int markUsed(@Param("id") Long id, @Param("now") LocalDateTime now);

    /** 注销账号：删除该手机号全部场景的验证码记录（同时解除 60 秒限频，可立即重新注册） */
    int deleteByPhone(@Param("phone") String phone);
}
