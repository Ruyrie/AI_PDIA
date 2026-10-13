package com.heban.pestai.mine.ui;

/**
 * M01 我的页的一次性事件，由 MineFragment 执行跳转或弹窗。
 */
public enum MineEvent {
    /** S01 拍照识别 */
    GO_SCAN,
    /** R03 识别记录（全部） */
    GO_HISTORY,
    /** R03 识别记录，预选「病虫害」筛选（统计格「识别出的病虫害」） */
    GO_HISTORY_PEST,
    /** 切换到 AI 问答 Tab */
    GO_AI_TAB,
    /** M02 设置 */
    GO_SETTINGS,
    /** M03 关于 */
    GO_ABOUT,
    /** A03 登录（游客点「登录 / 注册」） */
    GO_LOGIN,
    /** A12 账号恢复码（未生成提醒卡片「去生成」） */
    GO_RECOVERY_CODE,
    /** 游客点需要登录的入口：弹出「登录后才能使用」 */
    NEED_LOGIN
}
