package com.heban.pestai.mine.format;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.heban.pestai.common.vo.UserVO;

import org.junit.Test;

public class UserFormatterTest {

    @Test
    public void maskPhone_keepsFirstThreeAndLastFour() {
        assertEquals("138****5678", UserFormatter.maskPhone("13800005678"));
        assertEquals("138****5678", UserFormatter.maskPhone(" 13800005678 "));
    }

    @Test
    public void maskPhone_otherLengthUnchanged() {
        assertEquals("1380000", UserFormatter.maskPhone("1380000"));
        assertEquals("", UserFormatter.maskPhone(""));
        assertEquals("", UserFormatter.maskPhone(null));
    }

    @Test
    public void shortRegion_takesFirstTwoLevelsWithoutSuffix() {
        assertEquals("江苏 南京", UserFormatter.shortRegion("江苏省 南京市 江宁区"));
        assertEquals("山东 寿光", UserFormatter.shortRegion("山东省  寿光市"));
        assertEquals("江苏", UserFormatter.shortRegion("江苏省"));
    }

    @Test
    public void shortRegion_municipalityKeepsOne() {
        assertEquals("北京", UserFormatter.shortRegion("北京市 北京市 东城区"));
        assertEquals("上海 浦东新区", UserFormatter.shortRegion("上海市 浦东新区"));
    }

    @Test
    public void shortRegion_autonomousRegion() {
        assertEquals("广西 南宁", UserFormatter.shortRegion("广西壮族自治区 南宁市 青秀区"));
        assertEquals("内蒙古 呼和浩特", UserFormatter.shortRegion("内蒙古自治区 呼和浩特市"));
    }

    @Test
    public void shortRegion_emptyReturnsEmpty() {
        assertEquals("", UserFormatter.shortRegion(null));
        assertEquals("", UserFormatter.shortRegion("   "));
    }

    @Test
    public void avatarText_firstCharacter() {
        assertEquals("老", UserFormatter.avatarText("老李种菜"));
        assertEquals("", UserFormatter.avatarText(""));
        assertEquals("", UserFormatter.avatarText(null));
        // 补充平面字符按码点取，不截成半个代理对
        assertEquals("😀", UserFormatter.avatarText("😀小王"));
    }

    @Test
    public void phoneRegion_joinsWithDot() {
        UserVO user = user("13800005678", "江苏省 南京市 江宁区");
        assertEquals("138****5678 · 江苏 南京", UserFormatter.phoneRegion(user));
    }

    @Test
    public void phoneRegion_noRegionOnlyPhone() {
        assertEquals("138****5678", UserFormatter.phoneRegion(user("13800005678", null)));
        assertEquals("138****5678", UserFormatter.phoneRegion(user("13800005678", " ")));
        assertEquals("江苏 南京", UserFormatter.phoneRegion(user(null, "江苏省 南京市")));
        assertEquals("", UserFormatter.phoneRegion(null));
    }

    @Test
    public void displayName_andPlaceholderAvatar() {
        UserVO user = user("13800005678", null);
        user.setNickname("老李种菜");
        assertEquals("老李种菜", UserFormatter.displayName(user));
        assertFalse(UserFormatter.needsPlaceholderAvatar(user));

        user.setNickname(" ");
        assertEquals("未设置昵称", UserFormatter.displayName(user));
        assertTrue(UserFormatter.needsPlaceholderAvatar(user));

        user.setAvatarUrl("http://10.0.2.2:8080/files/avatar/1.jpg");
        assertFalse(UserFormatter.needsPlaceholderAvatar(user));

        assertEquals("", UserFormatter.displayName(null));
        assertTrue(UserFormatter.needsPlaceholderAvatar(null));
    }

    private static UserVO user(String phone, String region) {
        UserVO user = new UserVO();
        user.setPhone(phone);
        user.setRegion(region);
        return user;
    }
}
