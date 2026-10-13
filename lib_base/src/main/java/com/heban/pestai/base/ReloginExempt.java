package com.heban.pestai.base;

/**
 * 标记接口：位于前台时不响应全局「需要重新登录」事件的页面（闪屏、登录页等），
 * 避免重复打开登录页。由壳工程在收到事件时检查当前前台 Activity。
 */
public interface ReloginExempt {
}
