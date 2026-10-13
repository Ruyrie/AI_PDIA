package com.heban.pestai.mine.ui.about;

import static com.heban.pestai.mine.testutil.LiveDataTestUtil.record;
import static com.heban.pestai.mine.testutil.LiveDataTestUtil.value;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import androidx.arch.core.executor.testing.InstantTaskExecutorRule;

import com.heban.pestai.mine.data.AppVersion;

import org.junit.Rule;
import org.junit.Test;

import java.util.Arrays;
import java.util.List;

public class AboutViewModelTest {

    @Rule
    public InstantTaskExecutorRule instantRule = new InstantTaskExecutorRule();

    @Test
    public void versionTextFromInstalledPackage() {
        AboutViewModel vm = new AboutViewModel(new AppVersion("1.0.0", 1));
        assertEquals("拍照识病虫 · 版本 1.0.0（1）", vm.getVersionText());
    }

    @Test
    public void checkUpdateOnlyShowsNotOpen() {
        AboutViewModel vm = new AboutViewModel(new AppVersion("1.0.0", 1));
        List<AboutEvent> events = record(vm.getEvent());

        vm.onCheckUpdate();
        vm.onCheckUpdate();
        vm.onUserAgreementClick();
        vm.onPrivacyClick();

        assertEquals(Arrays.asList(AboutEvent.NOT_OPEN, AboutEvent.NOT_OPEN,
                AboutEvent.OPEN_USER_AGREEMENT, AboutEvent.OPEN_PRIVACY), events);
        // v1.0 不请求 app/version，最新版本始终为空
        assertNull(value(vm.getLatestVersion()));
    }
}
