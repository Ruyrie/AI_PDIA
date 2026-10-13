package com.heban.pestai.recognize.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.heban.pestai.recognize.R;
import com.heban.pestai.recognize.testing.Samples;

import org.junit.Test;

import java.util.List;

public class TagItemTest {

    @Test
    public void pest_withSpreadFast_hasThreeTags() {
        List<TagItem> tags = TagItem.listOf(Samples.cucumber());
        assertEquals(3, tags.size());
        assertEquals(TagItem.text("卵菌病害", TagItem.Style.NORMAL), tags.get(0));
        assertEquals(TagItem.text("叶部", TagItem.Style.NORMAL), tags.get(1));
        assertEquals(TagItem.res(R.string.scan_tag_spread_fast, TagItem.Style.ALERT), tags.get(2));
    }

    @Test
    public void pest_withoutSpreadFast_hasNoAlertTag() {
        List<TagItem> tags = TagItem.listOf("PEST", "真菌病害", "叶部", false);
        assertEquals(2, tags.size());
        for (TagItem tag : tags) {
            assertTrue(tag.style != TagItem.Style.ALERT);
        }
    }

    @Test
    public void crop_categoryUsesCropStyle_andNoSpreadFast() {
        List<TagItem> tags = TagItem.listOf(Samples.crop());
        assertEquals(1, tags.size());
        assertEquals(TagItem.text("杂草", TagItem.Style.CROP), tags.get(0));
    }

    @Test
    public void blankFields_areSkipped() {
        assertTrue(TagItem.listOf("PEST", " ", null, false).isEmpty());
        assertTrue(TagItem.listOf(null).isEmpty());
    }
}
