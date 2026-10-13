package com.heban.pestai.base;

/**
 * 由主页面 Tab 中的 Fragment 实现：再次点击当前 Tab 时回调（如首页滚回顶部）。
 */
public interface TabReselectable {
    void onTabReselected();
}
