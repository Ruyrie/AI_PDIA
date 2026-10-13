package com.heban.pestai.recognize.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

public class SeverityLevelTest {

    @Test
    public void of_mapsFourLevels() {
        assertEquals(SeverityLevel.HEALTHY, SeverityLevel.of("健康"));
        assertEquals(SeverityLevel.MILD, SeverityLevel.of("轻度"));
        assertEquals(SeverityLevel.MODERATE, SeverityLevel.of("中度"));
        assertEquals(SeverityLevel.SEVERE, SeverityLevel.of("较重"));
    }

    @Test
    public void of_nullAndUnknown_returnNull() {
        assertNull(SeverityLevel.of(null));
        assertNull(SeverityLevel.of(""));
        assertNull(SeverityLevel.of("极重"));
    }

    @Test
    public void colorsAndIcons_matchDesign() {
        assertEquals(com.google.android.material.R.attr.colorPrimaryContainer, SeverityLevel.HEALTHY.bgAttr);
        assertEquals(com.google.android.material.R.attr.colorSecondaryContainer, SeverityLevel.MILD.bgAttr);
        assertEquals(com.heban.pestai.base.R.attr.colorWarningContainer, SeverityLevel.MODERATE.bgAttr);
        assertEquals(com.heban.pestai.base.R.attr.colorOnWarningContainer, SeverityLevel.MODERATE.fgAttr);
        assertEquals(com.google.android.material.R.attr.colorErrorContainer, SeverityLevel.SEVERE.bgAttr);
        assertEquals(com.heban.pestai.base.R.drawable.ic_check_circle, SeverityLevel.HEALTHY.iconRes);
        assertEquals(com.heban.pestai.base.R.drawable.ic_info, SeverityLevel.MILD.iconRes);
        assertEquals(com.heban.pestai.base.R.drawable.ic_warning, SeverityLevel.MODERATE.iconRes);
        assertEquals(com.heban.pestai.base.R.drawable.ic_error, SeverityLevel.SEVERE.iconRes);
    }
}
