package com.heban.pestai.recognize.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.heban.pestai.common.vo.CandidateVO;
import com.heban.pestai.recognize.testing.Samples;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Set;

/**
 * 「其他可能」行模型：可展开判断、箭头占位、空白文字处理、展开状态与 DiffUtil 用的 equals。
 */
public class OtherItemTest {

    private static final String LABEL = "与黄瓜霜霉病的区别";
    private static final Set<String> NONE = Collections.emptySet();

    @Test
    public void listOf_nullOrEmpty() {
        assertTrue(OtherItem.listOf(null, LABEL, NONE).isEmpty());
        assertTrue(OtherItem.listOf(Collections.emptyList(), LABEL, NONE).isEmpty());
        assertFalse(OtherItem.anyExpandable(null));
        assertFalse(OtherItem.anyExpandable(Collections.emptyList()));
    }

    @Test
    public void listOf_skipsNullCandidates_keepsOrder() {
        List<OtherItem> items = OtherItem.listOf(Arrays.asList(Samples.candidate("a", 0.1), null,
                Samples.candidate("b", 0.05)), LABEL, NONE);
        assertEquals(2, items.size());
        assertEquals("a", items.get(0).getName());
        assertEquals("b", items.get(1).getName());
    }

    @Test
    public void expandable_onlyWithNonBlankText_trimmed() {
        List<OtherItem> items = OtherItem.listOf(Arrays.asList(
                Samples.candidate("a", 0.1, "  依据  ", null),
                Samples.candidate("b", 0.1, null, "\n区别\t"),
                Samples.candidate("c", 0.1, " ", ""),
                Samples.candidate("d", 0.1)), LABEL, NONE);

        assertTrue(items.get(0).isExpandable());
        assertEquals("依据", items.get(0).getReason());
        assertNull(items.get(0).getDifference());

        assertTrue(items.get(1).isExpandable());
        assertNull(items.get(1).getReason());
        assertEquals("区别", items.get(1).getDifference());

        assertFalse(items.get(2).isExpandable());
        assertNull(items.get(2).getReason());
        assertNull(items.get(2).getDifference());

        assertFalse(items.get(3).isExpandable());
        assertTrue(OtherItem.anyExpandable(items));
        assertEquals(LABEL, items.get(0).getDifferenceLabel());
    }

    @Test
    public void reserveChevron_onlyWhenAnyRowExpandable() {
        List<OtherItem> mixed = OtherItem.listOf(Arrays.asList(Samples.candidate("a", 0.1, "依据", null),
                Samples.candidate("b", 0.1)), LABEL, NONE);
        assertTrue(mixed.get(0).isReserveChevron());
        assertTrue(mixed.get(1).isReserveChevron());

        List<OtherItem> plain = OtherItem.listOf(Arrays.asList(Samples.candidate("a", 0.1),
                Samples.candidate("b", 0.1)), LABEL, NONE);
        assertFalse(plain.get(0).isReserveChevron());
        assertFalse(OtherItem.anyExpandable(plain));
    }

    @Test
    public void expanded_byName_andOnlyIfExpandable() {
        Set<String> expanded = new java.util.HashSet<>(Arrays.asList("a", "b"));
        List<OtherItem> items = OtherItem.listOf(Arrays.asList(Samples.candidate("a", 0.1, "依据", null),
                Samples.candidate("b", 0.1), Samples.candidate("c", 0.1, "依据", "区别")), LABEL, expanded);
        assertTrue(items.get(0).isExpanded());
        assertFalse("不可展开的行即使在集合中也不展开", items.get(1).isExpanded());
        assertFalse(items.get(2).isExpanded());
    }

    @Test
    public void equals_includesExpandedAndExplanation_forDiffUtil() {
        CandidateVO c = Samples.candidate("a", 0.1, "依据", "区别");
        OtherItem collapsed = OtherItem.listOf(Collections.singletonList(c), LABEL, NONE).get(0);
        OtherItem collapsedAgain = OtherItem.listOf(Collections.singletonList(c), LABEL, NONE).get(0);
        OtherItem expanded = OtherItem.listOf(Collections.singletonList(c), LABEL,
                Collections.singleton("a")).get(0);
        assertEquals(collapsed, collapsedAgain);
        assertEquals(collapsed.hashCode(), collapsedAgain.hashCode());
        assertNotEquals(collapsed, expanded);

        OtherItem otherReason = OtherItem.listOf(Collections.singletonList(
                Samples.candidate("a", 0.1, "另一个依据", "区别")), LABEL, NONE).get(0);
        assertNotEquals(collapsed, otherReason);
        OtherItem otherLabel = OtherItem.listOf(Collections.singletonList(c), "与另一个结果的区别", NONE).get(0);
        assertNotEquals(collapsed, otherLabel);
    }

    @Test
    public void hasExplanation() {
        assertTrue(OtherItem.hasExplanation(Samples.candidate("a", 0.1, "x", null)));
        assertTrue(OtherItem.hasExplanation(Samples.candidate("a", 0.1, null, "y")));
        assertFalse(OtherItem.hasExplanation(Samples.candidate("a", 0.1, "", "  ")));
        assertFalse(OtherItem.hasExplanation(Samples.candidate("a", 0.1)));
    }
}
