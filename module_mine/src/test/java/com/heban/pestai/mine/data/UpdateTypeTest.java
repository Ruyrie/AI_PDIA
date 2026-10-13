package com.heban.pestai.mine.data;

import static org.junit.Assert.assertEquals;

import com.heban.pestai.common.vo.AppVersionVO;

import org.junit.Test;

public class UpdateTypeTest {

    @Test
    public void newerVersionIsOptional() {
        assertEquals(UpdateType.OPTIONAL, UpdateType.decide(1, version(2, false), UpdateType.NO_IGNORED));
    }

    @Test
    public void forceUpdate() {
        assertEquals(UpdateType.FORCE, UpdateType.decide(1, version(2, true), UpdateType.NO_IGNORED));
        // 强制更新不受忽略记录影响
        assertEquals(UpdateType.FORCE, UpdateType.decide(1, version(2, true), 2));
    }

    @Test
    public void notNewerIsNone() {
        assertEquals(UpdateType.NONE, UpdateType.decide(2, version(2, false), UpdateType.NO_IGNORED));
        assertEquals(UpdateType.NONE, UpdateType.decide(3, version(2, true), UpdateType.NO_IGNORED));
        assertEquals(UpdateType.NONE, UpdateType.decide(1, null, UpdateType.NO_IGNORED));
    }

    @Test
    public void ignoredVersionSilentOnStartupButShownOnManualCheck() {
        // 启动时检查：用户对版本 2 选过「以后再说」
        assertEquals(UpdateType.NONE, UpdateType.decide(1, version(2, false), 2));
        // 手动检查不使用忽略记录
        assertEquals(UpdateType.OPTIONAL, UpdateType.decide(1, version(2, false), UpdateType.NO_IGNORED));
        // 忽略的是旧版本，新版本仍提示
        assertEquals(UpdateType.OPTIONAL, UpdateType.decide(1, version(3, false), 2));
    }

    private static AppVersionVO version(int code, boolean force) {
        AppVersionVO vo = new AppVersionVO();
        vo.setVersionCode(code);
        vo.setVersionName("1." + (code - 1) + ".0");
        vo.setForceUpdate(force);
        return vo;
    }
}
