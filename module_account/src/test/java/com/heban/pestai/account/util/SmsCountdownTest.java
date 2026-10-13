package com.heban.pestai.account.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import androidx.arch.core.executor.testing.InstantTaskExecutorRule;

import com.heban.pestai.account.testutil.RxTestRule;
import com.heban.pestai.account.testutil.TestEnv;

import org.junit.Rule;
import org.junit.Test;

public class SmsCountdownTest {

    @Rule
    public InstantTaskExecutorRule instantRule = new InstantTaskExecutorRule();
    @Rule
    public RxTestRule rx = new RxTestRule();

    @Test
    public void textOf() {
        assertEquals("获取验证码", SmsCountdown.textOf(0));
        assertEquals("60s 后重发", SmsCountdown.textOf(60));
        assertEquals("1s 后重发", SmsCountdown.textOf(1));
    }

    @Test
    public void countsDownSixtySecondsThenRestores() {
        SmsCountdown countdown = new SmsCountdown();
        TestEnv.record(countdown.getText());
        assertEquals("获取验证码", countdown.getText().getValue());

        countdown.start();
        assertTrue(countdown.isCounting());
        assertEquals("60s 后重发", countdown.getText().getValue());

        rx.advance(1000);
        assertEquals("59s 后重发", countdown.getText().getValue());
        rx.advance(17_000);
        assertEquals("42s 后重发", countdown.getText().getValue());
        assertEquals(Integer.valueOf(42), countdown.getRemaining().getValue());

        rx.advance(41_000);
        assertEquals("1s 后重发", countdown.getText().getValue());
        assertTrue(countdown.isCounting());

        rx.advance(1000);
        assertEquals("获取验证码", countdown.getText().getValue());
        assertFalse(countdown.isCounting());

        // 结束后不再变化
        rx.advance(10_000);
        assertEquals("获取验证码", countdown.getText().getValue());
    }

    @Test
    public void stopResetsState() {
        SmsCountdown countdown = new SmsCountdown();
        countdown.start();
        rx.advance(5000);
        countdown.stop();
        assertFalse(countdown.isCounting());
        assertEquals("获取验证码", countdown.getText().getValue());
        rx.advance(5000);
        assertEquals("获取验证码", countdown.getText().getValue());
    }
}
