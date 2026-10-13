package com.heban.pestai.recognize.ui.scan;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import androidx.arch.core.executor.testing.InstantTaskExecutorRule;

import com.heban.pestai.recognize.R;
import com.heban.pestai.recognize.model.PermissionState;
import com.heban.pestai.recognize.model.RecognizeMode;
import com.heban.pestai.recognize.model.ScanEvent;
import com.heban.pestai.recognize.testing.FakeRepository;
import com.heban.pestai.recognize.testing.FakeTexts;
import com.heban.pestai.recognize.testing.TrampolineSchedulerRule;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class ScanViewModelTest {

    @Rule
    public InstantTaskExecutorRule instantRule = new InstantTaskExecutorRule();
    @Rule
    public TrampolineSchedulerRule schedulerRule = new TrampolineSchedulerRule();

    private final FakeTexts texts = new FakeTexts();
    private FakeRepository repository;
    private ScanViewModel vm;
    private final List<ScanEvent> events = new ArrayList<>();

    @Before
    public void setUp() {
        repository = new FakeRepository();
        vm = new ScanViewModel(repository, texts);
        vm.getEvent().observeForever(e -> events.add(e));
    }

    @Test
    public void initMode_crop() {
        vm.initMode("CROP");
        assertEquals(RecognizeMode.CROP, vm.getMode().getValue());
        assertEquals(texts.get(R.string.scan_hint_crop), vm.getHintText().getValue());
        assertEquals(1, repository.cleanCalls);
    }

    @Test
    public void initMode_defaultsToPest() {
        vm.initMode(null);
        assertEquals(RecognizeMode.PEST, vm.getMode().getValue());
        assertEquals(texts.get(R.string.scan_hint_pest), vm.getHintText().getValue());
    }

    @Test
    public void initMode_doesNotOverrideAfterRotation() {
        vm.initMode("CROP");
        vm.selectMode(RecognizeMode.PEST);
        vm.initMode("CROP");
        assertEquals(RecognizeMode.PEST, vm.getMode().getValue());
        assertEquals("清理只在首次进入时执行", 1, repository.cleanCalls);
    }

    @Test
    public void selectMode_changesHint() {
        vm.initMode("PEST");
        vm.selectMode(RecognizeMode.CROP);
        assertEquals(RecognizeMode.CROP, vm.getMode().getValue());
        assertEquals(texts.get(R.string.scan_hint_crop), vm.getHintText().getValue());
    }

    @Test
    public void toggleFlash() {
        assertFalse(vm.getFlashOn().getValue());
        vm.toggleFlash();
        assertTrue(vm.getFlashOn().getValue());
        vm.toggleFlash();
        assertFalse(vm.getFlashOn().getValue());
    }

    @Test
    public void permission_firstCheckRequestsOnce() {
        vm.checkPermission(false);
        assertEquals(1, events.size());
        assertEquals(ScanEvent.Type.REQUEST_PERMISSION, events.get(0).type);

        // 对话框关闭后 onResume 再次检查，不再弹窗
        vm.onPermissionResult(false, true);
        vm.checkPermission(false);
        assertEquals(1, events.size());
        assertEquals(PermissionState.DENIED, vm.getPermissionState().getValue());
        assertEquals(texts.get(R.string.scan_permission_desc), vm.getPermissionDesc().getValue());
        assertEquals(texts.get(R.string.scan_permission_grant), vm.getPermissionButtonText().getValue());
        assertFalse(vm.getShutterEnabled().getValue());

        vm.onPermissionButtonClick();
        assertEquals(ScanEvent.Type.REQUEST_PERMISSION, events.get(1).type);
    }

    @Test
    public void permission_permanentlyDenied_opensSettings() {
        vm.checkPermission(false);
        vm.onPermissionResult(false, false);
        assertEquals(PermissionState.PERMANENTLY_DENIED, vm.getPermissionState().getValue());
        assertEquals(texts.get(R.string.scan_permission_desc_permanent), vm.getPermissionDesc().getValue());
        assertEquals(texts.get(R.string.scan_permission_open_settings), vm.getPermissionButtonText().getValue());
        vm.onPermissionButtonClick();
        assertEquals(ScanEvent.Type.OPEN_SETTINGS, events.get(events.size() - 1).type);

        // 从系统设置开启后返回
        vm.checkPermission(true);
        assertEquals(PermissionState.GRANTED, vm.getPermissionState().getValue());
        assertTrue(vm.getShutterEnabled().getValue());
    }

    @Test
    public void startCapture_requiresPermissionAndNotCapturing() {
        assertFalse(vm.startCapture());
        vm.checkPermission(true);
        assertTrue(vm.startCapture());
        assertTrue(vm.getCapturing().getValue());
        assertFalse(vm.getShutterEnabled().getValue());
        assertFalse("拍照完成前再次点击无效", vm.startCapture());
        vm.onCaptureFailed();
        assertFalse(vm.getCapturing().getValue());
        assertEquals(texts.get(R.string.scan_capture_failed), vm.getToast().getValue());
        assertTrue(vm.startCapture());
    }

    @Test
    public void photoCaptured_savesToAlbumWhenEnabled_andGoesAnalyzing() {
        repository.saveToAlbum = true;
        vm.initMode("CROP");
        vm.checkPermission(true);
        vm.startCapture();
        vm.onPhotoCaptured("file:///cache/scan/IMG_1.jpg");

        assertFalse(vm.getCapturing().getValue());
        assertEquals(Collections.singletonList("file:///cache/scan/IMG_1.jpg"), repository.savedToAlbum);
        ScanEvent event = events.get(events.size() - 1);
        assertEquals(ScanEvent.Type.GO_ANALYZING, event.type);
        assertEquals("file:///cache/scan/IMG_1.jpg", event.imageUri);
        assertEquals(RecognizeMode.CROP, event.mode);
    }

    @Test
    public void photoCaptured_doesNotSaveWhenDisabled() {
        vm.initMode(null);
        vm.checkPermission(true);
        vm.startCapture();
        vm.onPhotoCaptured("file:///a.jpg");
        assertTrue(repository.savedToAlbum.isEmpty());
        assertEquals(ScanEvent.Type.GO_ANALYZING, events.get(events.size() - 1).type);
    }

    @Test
    public void photoPicked_cancelStays_pickedGoesAnalyzing_withoutSaving() {
        repository.saveToAlbum = true;
        vm.initMode(null);
        vm.onPhotoPicked(null);
        assertTrue(events.isEmpty());
        vm.onPhotoPicked("content://media/picker/0/1");
        assertEquals(ScanEvent.Type.GO_ANALYZING, events.get(0).type);
        assertEquals(RecognizeMode.PEST, events.get(0).mode);
        assertTrue("相册照片不重复保存", repository.savedToAlbum.isEmpty());
    }

    @Test
    public void openHistory_andMessages() {
        vm.openHistory();
        assertEquals(ScanEvent.Type.GO_HISTORY, events.get(0).type);
        vm.onAnalyzeCanceled();
        assertEquals(texts.get(R.string.scan_canceled), vm.getToast().getValue());
        vm.onCameraError();
        assertEquals(texts.get(R.string.scan_camera_error), vm.getToast().getValue());
        vm.setHasFlash(true);
        assertTrue(vm.getHasFlash().getValue());
    }
}
