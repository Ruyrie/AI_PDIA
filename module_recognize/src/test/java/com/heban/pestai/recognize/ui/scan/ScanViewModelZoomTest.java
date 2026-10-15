package com.heban.pestai.recognize.ui.scan;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import androidx.arch.core.executor.testing.InstantTaskExecutorRule;

import com.heban.pestai.recognize.R;
import com.heban.pestai.recognize.model.RecognizeMode;
import com.heban.pestai.recognize.testing.FakeRepository;
import com.heban.pestai.recognize.testing.FakeTexts;
import com.heban.pestai.recognize.testing.TrampolineSchedulerRule;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

/** S01 变焦：档位条显示条件、档位数据、目标倍数的夹紧与恢复 */
public class ScanViewModelZoomTest {

    private static final float DELTA = 0.0001f;

    @Rule
    public InstantTaskExecutorRule instantRule = new InstantTaskExecutorRule();
    @Rule
    public TrampolineSchedulerRule schedulerRule = new TrampolineSchedulerRule();

    private final FakeTexts texts = new FakeTexts();
    private ScanViewModel vm;
    private final List<List<ZoomChip>> chipUpdates = new ArrayList<>();

    @Before
    public void setUp() {
        vm = new ScanViewModel(new FakeRepository(), texts);
        vm.getZoomChips().observeForever(chipUpdates::add);
        vm.initMode(null);
    }

    @Test
    public void zoomBar_hiddenUntilGrantedAndRangeKnown() {
        assertFalse(vm.getZoomBarVisible().getValue());
        assertFalse(vm.isZoomKnown());
        assertTrue(vm.getZoomChips().getValue().isEmpty());

        vm.checkPermission(true);
        assertFalse("相机未打开前不显示", vm.getZoomBarVisible().getValue());

        assertEquals("新打开页面从 1× 开始", 1f, vm.onZoomRange(0.5f, 10f), DELTA);
        assertTrue(vm.isZoomKnown());
        assertTrue(vm.getZoomBarVisible().getValue());
    }

    @Test
    public void zoomBar_hiddenWhenPermissionDenied() {
        vm.onZoomRange(1f, 8f);
        assertFalse("未授权不显示", vm.getZoomBarVisible().getValue());
        vm.checkPermission(true);
        assertTrue(vm.getZoomBarVisible().getValue());
    }

    @Test
    public void zoomBar_hiddenForFixedLens() {
        vm.checkPermission(true);
        vm.onZoomRange(1f, 1f);
        assertFalse("只有 1× 一个档位时不显示", vm.getZoomBarVisible().getValue());
    }

    @Test
    public void defaultRangeThenRealRange_showsBarAndKeepsTarget() {
        vm.checkPermission(true);
        vm.onZoomRange(1f, 8f);
        vm.requestZoom(2f);

        // 旋转重建后相机重新打开：CameraX 先给出 1–1 的默认范围，再给出真实范围
        assertTrue(vm.isZoomRangeChanged(1f, 1f));
        assertEquals(1f, vm.onZoomRange(1f, 1f), DELTA);
        assertFalse(vm.getZoomBarVisible().getValue());
        assertEquals("默认范围不改写目标倍数", 2f, vm.getZoomTarget(), DELTA);

        assertTrue(vm.isZoomRangeChanged(1f, 12.9f));
        assertEquals("真实范围到达后恢复 2×", 2f, vm.onZoomRange(1f, 12.9f), DELTA);
        assertTrue(vm.getZoomBarVisible().getValue());
        assertEquals(3, vm.getZoomChips().getValue().size());
        assertFalse(vm.isZoomRangeChanged(1f, 12.9f));
    }

    @Test
    public void cameraError_hidesZoomBar() {
        vm.checkPermission(true);
        vm.onZoomRange(1f, 8f);
        vm.onCameraError();
        assertFalse(vm.getZoomBarVisible().getValue());
        assertFalse(vm.isZoomKnown());
    }

    @Test
    public void chips_presetsLabelsAndDescriptions() {
        vm.checkPermission(true);
        vm.onZoomRange(0.5f, 10f);
        List<ZoomChip> chips = vm.getZoomChips().getValue();
        assertEquals(4, chips.size());
        assertChip(chips.get(0), 0.5f, "0.5", false, texts.get(R.string.scan_zoom_chip_desc, "0.5"));
        assertChip(chips.get(1), 1f, "1×", true, texts.get(R.string.scan_zoom_chip_desc, "1"));
        assertChip(chips.get(2), 2f, "2×", false, texts.get(R.string.scan_zoom_chip_desc, "2"));
        assertChip(chips.get(3), 5f, "5×", false, texts.get(R.string.scan_zoom_chip_desc, "5"));
    }

    @Test
    public void chips_activeShowsActualRatio() {
        vm.checkPermission(true);
        vm.onZoomRange(1f, 8f);
        vm.onZoomRatio(2.34f);
        List<ZoomChip> chips = vm.getZoomChips().getValue();
        assertEquals(3, chips.size());
        assertChip(chips.get(0), 1f, "1×", false, texts.get(R.string.scan_zoom_chip_desc, "1"));
        assertChip(chips.get(1), 2f, "2.3×", true, texts.get(R.string.scan_zoom_chip_desc_current, "2", "2.3"));
        assertChip(chips.get(2), 5f, "5×", false, texts.get(R.string.scan_zoom_chip_desc, "5"));

        vm.onZoomRatio(6f);
        chips = vm.getZoomChips().getValue();
        assertFalse(chips.get(1).active);
        assertEquals("2×", chips.get(1).label);
        assertTrue(chips.get(2).active);
        assertEquals("6×", chips.get(2).label);
    }

    @Test
    public void chips_notRepublishedWhenUnchanged() {
        vm.checkPermission(true);
        vm.onZoomRange(1f, 8f);
        int count = chipUpdates.size();
        vm.onZoomRatio(1f);
        vm.onZoomRatio(1.01f);
        assertEquals("显示内容相同（都是 1×）时不重复通知", count, chipUpdates.size());
        vm.onZoomRatio(1.2f);
        assertEquals(count + 1, chipUpdates.size());
    }

    @Test
    public void requestZoom_clampsAndIsIgnoredBeforeRangeKnown() {
        assertEquals("范围未知时不变", 1f, vm.requestZoom(3f), DELTA);
        vm.onZoomRange(0.6f, 8f);
        assertEquals(3f, vm.requestZoom(3f), DELTA);
        assertEquals(8f, vm.requestZoom(20f), DELTA);
        assertEquals(0.6f, vm.requestZoom(0.1f), DELTA);
        assertEquals(0.6f, vm.getZoomTarget(), DELTA);
    }

    @Test
    public void scaleZoom_multipliesTarget() {
        vm.onZoomRange(1f, 8f);
        assertEquals(1.5f, vm.scaleZoom(1.5f), DELTA);
        assertEquals(3f, vm.scaleZoom(2f), DELTA);
        assertEquals(8f, vm.scaleZoom(10f), DELTA);
        assertEquals(4f, vm.scaleZoom(0.5f), DELTA);
        assertEquals(1f, vm.scaleZoom(0.01f), DELTA);
    }

    @Test
    public void onZoomRange_restoresTargetAfterRebind() {
        vm.checkPermission(true);
        vm.onZoomRange(1f, 8f);
        vm.requestZoom(2.3f);
        // 相机关闭后 CameraX 复位为 1×，重新打开（旋转重建 / 回到前台）时恢复目标倍数
        assertEquals(2.3f, vm.onZoomRange(1f, 8f), DELTA);
        ZoomChip active = vm.getZoomChips().getValue().get(1);
        assertTrue(active.active);
        assertEquals("恢复前先按目标倍数显示，不闪 1×", "2.3×", active.label);
    }

    @Test
    public void onZoomRange_clampsTargetToNewRange() {
        vm.onZoomRange(1f, 10f);
        vm.requestZoom(9f);
        assertEquals(5f, vm.onZoomRange(1f, 5f), DELTA);
        assertEquals(5f, vm.getZoomTarget(), DELTA);
    }

    @Test
    public void selectMode_keepsZoom() {
        vm.checkPermission(true);
        vm.onZoomRange(1f, 8f);
        vm.requestZoom(2f);
        vm.selectMode(RecognizeMode.CROP);
        assertEquals(2f, vm.getZoomTarget(), DELTA);
        assertTrue(vm.getZoomBarVisible().getValue());
    }

    private static void assertChip(ZoomChip chip, float preset, String label, boolean active, String description) {
        assertEquals(preset, chip.preset, DELTA);
        assertEquals(label, chip.label);
        assertEquals(active, chip.active);
        assertEquals(description, chip.description);
    }
}
