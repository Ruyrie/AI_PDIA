package com.heban.pestai.recognize.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import com.heban.pestai.recognize.model.RecognizeState.Phase;
import com.heban.pestai.recognize.testing.Samples;

import org.junit.Test;

import java.util.Arrays;

public class RecognizeStateTest {

    @Test
    public void uploadProgress_mapsTo10To70() {
        assertEquals(10, RecognizeState.uploadProgress(0));
        assertEquals(40, RecognizeState.uploadProgress(50));
        assertEquals(70, RecognizeState.uploadProgress(100));
        assertEquals(10, RecognizeState.uploadProgress(-3));
        assertEquals(70, RecognizeState.uploadProgress(150));
    }

    @Test
    public void steps_byPhase() {
        assertEquals(Arrays.asList(StepState.RUNNING, StepState.WAITING, StepState.WAITING),
                RecognizeState.compressing().steps());
        assertEquals(Arrays.asList(StepState.DONE, StepState.RUNNING, StepState.WAITING),
                RecognizeState.uploading(1, 1, 20).steps());
        assertEquals(Arrays.asList(StepState.DONE, StepState.DONE, StepState.RUNNING),
                RecognizeState.analyzing(1, 1, 80).steps());
        assertEquals(Arrays.asList(StepState.DONE, StepState.DONE, StepState.DONE),
                RecognizeState.success(1, 1, Samples.cucumber()).steps());
    }

    @Test
    public void analyzeTick_stopsAt95() {
        RecognizeState s = RecognizeState.analyzing(10, 5, 70);
        for (int i = 0; i < 100; i++) {
            s = s.nextAnalyzeTick();
        }
        assertEquals(95, s.getProgress());
        assertSame(s, s.nextAnalyzeTick());
        assertEquals(10, s.getOriginalSize());
        assertEquals(5, s.getCompressedSize());
    }

    @Test
    public void tick_onlyInAnalyzing() {
        RecognizeState uploading = RecognizeState.uploading(1, 1, 10);
        assertSame(uploading, uploading.nextAnalyzeTick());
    }

    @Test
    public void success_andFailed() {
        RecognizeState success = RecognizeState.success(100, 50, Samples.cucumber());
        assertEquals(100, success.getProgress());
        assertEquals(Phase.SUCCESS, success.getPhase());
        assertTrue(success.getPhase().isTerminal());

        RecognizeState failed = RecognizeState.failed(RecognizeState.analyzing(100, 50, 80), 50401, "TIMEOUT", "超时");
        assertEquals(Phase.FAILED, failed.getPhase());
        assertEquals(50401, failed.getErrorCode());
        assertEquals("TIMEOUT", failed.getFailReason());
        assertEquals(80, failed.getProgress());
        assertFalse(failed.getPhase().isRunning());
        assertTrue(RecognizeState.compressing().getPhase().isRunning());
    }
}
