package com.heban.pestai.server.entity;

import static org.assertj.core.api.Assertions.assertThatCode;

import java.util.List;

import org.apache.ibatis.reflection.MetaObject;
import org.apache.ibatis.reflection.SystemMetaObject;
import org.junit.jupiter.api.Test;

import com.heban.pestai.server.admin.entity.Admin;
import com.heban.pestai.server.admin.entity.AdminLog;
import com.heban.pestai.server.admin.entity.AdminUserRow;
import com.heban.pestai.server.admin.entity.DailyCount;
import com.heban.pestai.server.admin.entity.NameCount;
import com.heban.pestai.server.admin.entity.RangeCount;

/**
 * MyBatis 通过反射读写实体：同一属性同时存在 isXxx() 与 getXxx() 会被判定为「重载的 getter」，
 * 运行时抛出 ReflectionException。这里对所有实体的每个可读属性取值一次，防止回归。
 */
class EntityReflectionTest {

    @Test
    void everyEntityPropertyIsReadableByMyBatis() {
        List<Object> entities = List.of(new User(), new SmsCode(), new ResetTicket(), new Recognition(),
                new AiSession(), new AiMessage(), new AiMessageImage(), new AiSessionSummary(), new AppVersion(), new RecognitionStats(),
                new SystemConfig(), new Admin(), new AdminLog(), new AdminUserRow(), new DailyCount(), new NameCount(),
                new RangeCount());
        for (Object entity : entities) {
            MetaObject meta = SystemMetaObject.forObject(entity);
            for (String name : meta.getGetterNames()) {
                assertThatCode(() -> meta.getValue(name))
                        .as(entity.getClass().getSimpleName() + "." + name)
                        .doesNotThrowAnyException();
            }
        }
    }
}
