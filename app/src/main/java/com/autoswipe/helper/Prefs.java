package com.autoswipe.helper;

import android.content.Context;
import android.content.SharedPreferences;

/** SharedPreferences 读写封装。 */
public final class Prefs {

    public static final String NAME = "autoswipe_helper";

    /** 首次启动是否已确认免责声明。 */
    public static final String KEY_DISCLAIMER_OK = "disclaimer_ok";

    public static final String KEY_MASTER = "master";
    public static final String KEY_SKIP_AD = "skip_ad";
    public static final String KEY_AFTER_AD = "after_ad";
    public static final String KEY_IDLE = "idle_swipe";
    public static final String KEY_IDLE_SEC = "idle_sec";
    public static final String KEY_CLICK_NEXT = "click_next";
    public static final String KEY_AFTER_AD_PLAY = "after_ad_play";
    public static final String KEY_HINT_SWIPE = "hint_swipe";
    public static final String KEY_LIVE_AD = "live_ad";
    public static final String KEY_KEEP_ALIVE = "keep_alive";
    /** 直播间广告兜底：最近见过直播锚点、且屏幕文字长时间无变化时，补一次上滑。 */
    public static final String KEY_LIVE_STALL = "live_stall";
    public static final String KEY_LIVE_STALL_SEC = "live_stall_sec";
    /** 疑似广告/直播页兜底：读不到任何剧集信息且文字长时间不变时，补一次上滑。 */
    public static final String KEY_SUSPECT_AD = "suspect_ad";
    public static final String KEY_SUSPECT_SEC = "suspect_sec";
    /** 全屏直播广告快滑：整页读不到任何可见文字时，几秒就前进（不依赖任何文案）。 */
    public static final String KEY_SUSPECT_FAST = "suspect_fast";
    public static final String KEY_SUSPECT_FAST_SEC = "suspect_fast_sec";
    /** 画面静止检测：截图对比，画面长时间不动就补一次上滑（不依赖任何文案）。 */
    public static final String KEY_MOTION = "motion";
    public static final String KEY_MOTION_SEC = "motion_sec";
    /**
     * 弱提示兜底：提示文案在无障碍树里、位置也在屏幕内，但系统把它标成「不可见」时也动手。
     * 红果直播广告页经常整页节点都是「不可见」。
     */
    public static final String KEY_WEAK_HINT = "weak_hint";
    /** 疑似广告 / 直播页自动抓屏：文字长时间不动时自动把现场倒进日志。 */
    public static final String KEY_AUTO_DUMP = "auto_dump";
    public static final String KEY_DEBUG_PKG = "debug_pkg";
    public static final String KEY_DUMP_SCREEN = "dump_screen";
    public static final String KEY_DUMP_SEC = "dump_sec";
    public static final String KEY_SNAPSHOT_IDLE = "snapshot_idle";
    public static final String KEY_SNAPSHOT_SEC = "snapshot_sec";
    public static final String KEY_FIXED = "fixed";
    public static final String KEY_FIXED_SEC = "fixed_sec";
    public static final String KEY_COOLDOWN_SEC = "cooldown_sec";
    public static final String KEY_TARGET_PKGS = "target_pkgs";

    /**
     * 默认目标：红果免费短剧 / 红果免费漫剧 / 番茄畅听 / 番茄免费小说（听书）
     */
    public static final String DEFAULT_TARGET_PKGS =
            "com.phoenix.read,com.kylin.read,com.xs.fm,com.dragon.read";

    private Prefs() {
    }

    public static SharedPreferences get(Context c) {
        return c.getApplicationContext().getSharedPreferences(NAME, Context.MODE_PRIVATE);
    }
}
