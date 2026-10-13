package com.heban.pestai.base;

/**
 * 页面状态：加载中、有数据、空数据、出错。
 * 出错时的提示文案由 ViewModel 另用一个 LiveData&lt;String&gt; 保存。
 */
public enum UiState {
    LOADING,
    SUCCESS,
    EMPTY,
    ERROR
}
