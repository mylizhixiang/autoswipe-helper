package com.autoswipe.helper;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.app.ActivityManager;
import android.app.ActivityManager.RunningAppProcessInfo;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Path;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.view.Display;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 红果专用自动上滑服务。
 *
 * 思路：红果是竖屏上下滑的播放流，正常情况一集播完会自己滑到下一集，
 * 但“每集之间插的贴片广告”播完后经常不会继续，于是这里用无障碍服务
 * 在合适的时机补一次上滑手势。
 *
 * 触发条件（都可在界面上开关/调参）：
 *   T1 出现“下一集 / 继续观看”按钮 -> 直接点它
 *   T2 检测到广告刚消失            -> 延迟后上滑（对应你说的广告倒计时完不滑动）
 *   T3 播放进度停滞 N 秒           -> 上滑（对应一集播完卡住不动）
 *   T4 固定间隔上滑（备用，默认关）
 */
public class AutoSwipeService extends AccessibilityService {

    private static WeakReference<AutoSwipeService> sInstance = new WeakReference<>(null);

    public static AutoSwipeService instance() {
        return sInstance.get();
    }

    private static final Pattern P_TIME = Pattern.compile("\\d{1,2}:\\d{2}");
    private static final Pattern P_EPISODE = Pattern.compile("第\\s*\\d+\\s*集");
    private static final Pattern P_EPISODE_DIGIT = Pattern.compile("第\\s*(\\d+)\\s*集");
    private static final Pattern P_SEC = Pattern.compile("\\d{1,2}\\s*(秒|s|S)");

    /**
     * 页面明确提示“现在该手动上滑了”的文案。
     * 红果遇到直播类广告时，广告结束会停在直播页并在底部显示「上滑继续看短剧」，
     * 这个提示是最可靠的触发信号（比进度条判断稳得多）。
     */
    private static final String[] SWIPE_HINT_TEXTS = {
            "上滑继续看短剧", "上滑继续观看", "上滑继续看", "上滑看下一集",
            "上滑切换下一集", "上滑查看下一个", "上滑继续"
    };
    /**
     * 直播间类广告的锚点：这种页面往往没有「广告」二字，
     * 只有「点击进入直播间」这类文案，需要单独识别。
     */
    private static final String[] LIVE_AD_TEXTS = {
            "点击进入直播间", "进入直播间", "进直播间", "进入直播", "正在直播", "直播中",
            "看直播", "直播间", "主播", "人气", "在线人数", "送礼", "去围观"
    };

    /** 用于快速检索的关键词：宁可多取一些，再在本地精确判断。 */
    private static final String[] SEARCH_KEYS = {
            "上滑", "跳过", "广告", "直播", "下一", "继续", "播放", "秒", "第", ":"
    };

    private static final Pattern P_SWIPE_HINT =
            Pattern.compile("上滑[^\\n]{0,10}(继续|下一集|下一个|看短剧|观看|切换)");

    private static final String[] AD_MARKERS = {
            "广告", "跳过", "了解更多", "查看详情", "立即下载", "摇一摇", "点击查看", "广告详情"
    };
    private static final String[] NEXT_TEXTS = {
            "下一集", "播放下一集", "看下一集", "继续观看",
            "下一章", "下一首", "下一节", "下一回", "继续收听", "继续听"
    };

    /** 播放器上表示“当前处于暂停、点一下可继续播放”的控件文案（听书类广告后需要恢复播放）。 */
    private static final String[] PLAY_TEXTS = {
            "播放", "继续", "继续播放", "继续收听", "继续听", "恢复播放", "点击播放", "开始播放"
    };

    private final Handler handler = new Handler(Looper.getMainLooper());
    private HandlerThread workerThread;
    private Handler workerHandler;

    private volatile long lastProgressChange;
    private volatile String lastProgressSig = "";
    private volatile boolean progressReliable;
    private volatile boolean adVisible;
    private volatile boolean prevAdVisible;
    private volatile boolean lastHintPresent;
    private volatile long lastAdSeenAt;
    private volatile long lastActionAt;
    private volatile long lastSkipAt;
    private volatile String lastEpisodeLabel = "";
    private volatile long enterTargetAt;
    private volatile int progressStreak;
    private volatile int progressAdvanceCount;
    private volatile long progressWindowStart;
    private volatile int progressWindowCount;
    private volatile int maxEpisodeNo;
    private volatile boolean everSawEpisodeLabel;
    private volatile long suspectDisarmAt;
    private volatile boolean suspectLogged;
    /** 全屏直播广告快滑：整页一条可见文字都读不到时，几秒就动手（不靠任何文案）。 */
    private volatile long suspectFastDisarmAt;
    private volatile long quietUntil;
    private volatile int hintStreak;
    private volatile long lastAdvancedAt;
    private volatile long lastRuleSkipLogAt;
    private final Map<String, Long> ruleLastAt = new ConcurrentHashMap<>();
    private boolean hintLogged;
    private boolean adFalsePositiveLogged;
    private volatile String lastLoggedPkg = "";
    private volatile long lastDumpAt;
    private volatile long lastStatusAt;
    private volatile long lastTickAt;
    private volatile int strategyIndex;
    private volatile int tickCount;
    private volatile boolean tickRunning;
    private volatile long tickStartedAt;
    private volatile int pinnedStrategy = -1;
    private volatile int pendingStrategy = -1;
    private volatile long pendingAt;
    private volatile String pendingEpisodeLabel;
    private volatile boolean pendingHintPresent;
    private volatile int pinnedFailCount;
    private volatile String lastScreenSig = "";
    private volatile long lastScreenChangeAt;
    private volatile long liveAdSeenAt;
    private volatile long liveStallDisarmAt;
    private volatile boolean liveStallLogged;
    private volatile long lastNullRootLogAt;
    private volatile int hintWeakStreak;
    private volatile long weakHintDisarmAt;
    private volatile long lastAutoDumpAt;
    private volatile int autoDumpCount;
    /** 看到直播锚点时这一页的「可见文字签名」，T5 兜底要求页面从那一刻起一点没变。 */
    private volatile String liveAdPageSig = "";
    private volatile long liveAdPageSigAt;
    /** T5 已经对这一页动过手了：同一页只允许补一次，页面没变就不再重复滑。 */
    private volatile String liveStallDoneSig = "";
    /** 倒计时提示的日志节流，避免每次判定都刷一行。 */
    private volatile long lastCountdownLogAt;
    private volatile int freezeCount;
    private volatile int lastStrategy;
    private volatile String lastActionScreenSig = "";
    private volatile boolean shotBusy;
    private volatile long lastShotAt;
    private volatile long lastMotionAt;
    private volatile long shotErrLoggedAt;
    private volatile boolean motionHasPrev;
    private final int[] motionCur = new int[MOTION_N * MOTION_N];
    private final int[] motionPrev = new int[MOTION_N * MOTION_N];

    private long lastFixedSwipeAt;

    private boolean master = true;
    private boolean skipAd = true;
    private boolean afterAd = true;
    private boolean idleSwipe = true;
    private int idleSec = 8;
    private boolean clickNext = true;
    private boolean afterAdPlay = true;
    private boolean hintSwipe = true;
    private boolean liveAd = true;
    private boolean keepAlive = true;
    private boolean liveStall = true;
    private int liveStallSec = 10;
    private boolean suspectAd = true;
    private int suspectSec = 15;
    private boolean suspectFast = true;
    private int suspectFastSec = 5;
    private boolean motionDetect = true;
    private int motionSec = 15;
    private boolean debugPkg = false;
    private boolean dumpScreen = false;
    private int dumpSec = 5;
    private boolean snapshotIdle = false;
    private int snapshotSec = 15;
    private boolean fixedSwipe = false;
    private int fixedSec = 45;
    private int cooldownSec = 6;
    /**
     * 弱提示兜底：文案确实在无障碍树里、位置也在屏幕内，但系统把它标成「不可见」时也动手。
     * 红果的直播广告页经常整页节点都是「不可见」，只认"可见"会把它们全部滤掉。
     */
    private boolean weakHintSwipe = true;
    /** 疑似广告 / 直播页自动抓屏：文字长时间不动时自动把现场倒进日志（60 秒最多一次，最多 8 次）。 */
    private boolean autoDump = true;
    private String[] targetPkgs = new String[]{"com.phoenix.read", "com.kylin.read"};

    /** 手势结果回调：onCompleted=系统已送达；onCancelled=被其它触摸/系统打断。 */
    private final AccessibilityService.GestureResultCallback gestureCallback =
            new AccessibilityService.GestureResultCallback() {
                @Override
                public void onCompleted(GestureDescription gestureDescription) {
                    LogBus.log("    · 手势执行完成（系统已送达）");
                }

                @Override
                public void onCancelled(GestureDescription gestureDescription) {
                    LogBus.log("    · 手势被取消（被其它触摸打断或被系统拦截）");
                }
            };

    private static final int STRATEGY_COUNT = 7;
    /** 画面静止检测：截图缩到 16x16 灰度比较，变化像素比例低于这个值就算「画面没动」。 */
    private static final int MOTION_N = 16;
    private static final int MOTION_MOVE_PERCENT = 5;
    /** 确认集数往前走了之后，安静这么久再考虑动手（防止把刚开播的这一集又滑走）。 */
    private static final long QUIET_AFTER_ADVANCE_MS = 8000L;
    /** 同一条规则连着触发时，最短间隔；期间只要集数真的前进过就立刻重新允许。 */
    private static final long RULE_REPEAT_GAP_MS = 20000L;
    /** 广告 / 提示类规则的重试间隔：这几条只可能在广告页上触发，就该允许它多试几次。 */
    private static final long HINT_RETRY_GAP_MS = 10000L;
    /** 倒计时广告文案里的秒数，例如「3秒后可继续上滑观看短剧」。 */
    private static final Pattern P_COUNTDOWN = Pattern.compile("(\\d{1,2})\\s*秒\\s*(后|可)");

    private final Runnable ticker = new Runnable() {
        @Override
        public void run() {
            tickStartedAt = System.currentTimeMillis();
            tickRunning = true;
            try {
                tick();
            } catch (Throwable t) {
                LogBus.log("tick 异常: " + t);
            } finally {
                tickRunning = false;
            }
            if (workerHandler != null) {
                workerHandler.postDelayed(this, 2000L);
            }
        }
    };

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        sInstance = new WeakReference<>(this);
        long now = System.currentTimeMillis();
        lastProgressChange = now;
        lastActionAt = 0L;
        lastFixedSwipeAt = now;
        lastSkipAt = 0L;
        lastAdSeenAt = 0L;
        enterTargetAt = 0L;
        LogBus.log("无障碍服务已连接");
        reloadPrefs();
        startWorker();
        if (workerHandler != null) {
            workerHandler.removeCallbacks(ticker);
            workerHandler.postDelayed(ticker, 800L);
        }
        LogBus.log("本进程重要性=" + myImportance() + importanceHint());
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            LogBus.log("系统低于 Android 11，不支持无障碍截图：画面静止检测不可用");
        }
        if (keepAlive) {
            KeepAliveService.start(this);
            LogBus.log("已启动保活前台服务（常驻通知，用来降低被系统冻结的概率）");
        }
    }

    private void startWorker() {
        if (workerThread != null) {
            return;
        }
        workerThread = new HandlerThread("autoswipe-worker");
        workerThread.start();
        workerHandler = new Handler(workerThread.getLooper());
        LogBus.log("已启用后台工作线程（避免拖住主线程）");
        startWatchdog();
    }

    /**
     * 独立看门狗：用来区分「我的工作线程被无障碍调用拖住」和「整个进程被 ROM 冻结」。
     * 如果日志里只有界面读取卡住、没有整个进程被暂停，说明是前者；两者都有则是后者。
     */
    private void startWatchdog() {
        Thread wd = new Thread(() -> {
            long last = System.currentTimeMillis();
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    Thread.sleep(2000L);
                } catch (InterruptedException e) {
                    return;
                }
                long now = System.currentTimeMillis();
                if (now - last > 5000L) {
                    freezeCount++;
                    LogBus.log("⚠ 整个进程曾被暂停 " + (now - last) + "ms（疑似被 ROM 冻结，累计 "
                            + freezeCount + " 次）");
                    LogBus.log("   → 冻结期间不会做任何判定；请允许本应用「自启动 / 后台活动 / 无限制省电」");
                }
                last = now;
                if (tickRunning && now - tickStartedAt > 6000L) {
                    LogBus.log("⚠ 界面读取卡住 " + (now - tickStartedAt) + "ms（无障碍调用被目标应用拖住）");
                }
            }
        }, "autoswipe-watchdog");
        wd.setDaemon(true);
        wd.start();
    }

    private void runOnMain(Runnable r) {
        handler.post(r);
    }

    private void runOnWorker(Runnable r) {
        if (workerHandler != null) {
            workerHandler.post(r);
        } else {
            handler.post(r);
        }
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        // 快速通道：系统推送的事件里自带文本，不用再跨进程读界面，
        // 因此即使红果忙到读不动界面，这个通道也可能先命中。
        if (event == null || !master) {
            return;
        }
        CharSequence pkg = event.getPackageName();
        if (pkg == null || !isTargetPackage(pkg.toString())) {
            return;
        }
        String text = eventText(event);
        if (text.isEmpty()) {
            return;
        }
        boolean hitHint = containsAny(text, SWIPE_HINT_TEXTS) || P_SWIPE_HINT.matcher(text).find();
        boolean hitLive = liveAd && containsAny(text, LIVE_AD_TEXTS);
        if (!hitHint && !hitLive) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now < quietUntil) {
            return;
        }
        if (now - lastActionAt < Math.min(cooldownSec * 1000L, 3000L)) {
            return;
        }
        String shortText = text.length() > 40 ? text.substring(0, 40) + "…" : text;
        LogBus.log("事件命中「" + shortText + "」，立即前进");
        swipeUp("事件命中");
    }

    private static String eventText(AccessibilityEvent event) {
        StringBuilder sb = new StringBuilder();
        try {
            List<CharSequence> list = event.getText();
            if (list != null) {
                for (CharSequence c : list) {
                    if (c != null) {
                        sb.append(c).append(' ');
                    }
                }
            }
            CharSequence desc = event.getContentDescription();
            if (desc != null) {
                sb.append(desc);
            }
        } catch (Throwable ignored) {
        }
        return sb.toString().trim();
    }

    private static boolean containsAny(String text, String[] keys) {
        for (String k : keys) {
            if (text.contains(k)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public void onInterrupt() {
        LogBus.log("服务被系统中断");
    }

    @Override
    public boolean onUnbind(Intent intent) {
        LogBus.log("服务已断开");
        handler.removeCallbacks(ticker);
        if (workerHandler != null) {
            workerHandler.removeCallbacks(ticker);
        }
        if (workerThread != null) {
            workerThread.quitSafely();
            workerThread = null;
            workerHandler = null;
        }
        sInstance = new WeakReference<>(null);
        return super.onUnbind(intent);
    }

    private void reloadPrefs() {
        SharedPreferences p = Prefs.get(this);
        master = p.getBoolean(Prefs.KEY_MASTER, true);
        skipAd = p.getBoolean(Prefs.KEY_SKIP_AD, true);
        afterAd = p.getBoolean(Prefs.KEY_AFTER_AD, true);
        idleSwipe = p.getBoolean(Prefs.KEY_IDLE, true);
        idleSec = clamp(p.getInt(Prefs.KEY_IDLE_SEC, 8), 3, 120);
        clickNext = p.getBoolean(Prefs.KEY_CLICK_NEXT, true);
        afterAdPlay = p.getBoolean(Prefs.KEY_AFTER_AD_PLAY, true);
        hintSwipe = p.getBoolean(Prefs.KEY_HINT_SWIPE, true);
        liveAd = p.getBoolean(Prefs.KEY_LIVE_AD, true);
        keepAlive = p.getBoolean(Prefs.KEY_KEEP_ALIVE, true);
        liveStall = p.getBoolean(Prefs.KEY_LIVE_STALL, true);
        liveStallSec = clamp(p.getInt(Prefs.KEY_LIVE_STALL_SEC, 10), 3, 120);
        suspectAd = p.getBoolean(Prefs.KEY_SUSPECT_AD, true);
        suspectSec = clamp(p.getInt(Prefs.KEY_SUSPECT_SEC, 15), 5, 300);
        suspectFast = p.getBoolean(Prefs.KEY_SUSPECT_FAST, true);
        suspectFastSec = clamp(p.getInt(Prefs.KEY_SUSPECT_FAST_SEC, 5), 3, 60);
        motionDetect = p.getBoolean(Prefs.KEY_MOTION, true);
        motionSec = clamp(p.getInt(Prefs.KEY_MOTION_SEC, 15), 5, 120);
        debugPkg = p.getBoolean(Prefs.KEY_DEBUG_PKG, false);
        dumpScreen = p.getBoolean(Prefs.KEY_DUMP_SCREEN, false);
        dumpSec = clamp(p.getInt(Prefs.KEY_DUMP_SEC, 5), 2, 120);
        snapshotIdle = p.getBoolean(Prefs.KEY_SNAPSHOT_IDLE, false);
        snapshotSec = clamp(p.getInt(Prefs.KEY_SNAPSHOT_SEC, 15), 5, 600);
        fixedSwipe = p.getBoolean(Prefs.KEY_FIXED, false);
        fixedSec = clamp(p.getInt(Prefs.KEY_FIXED_SEC, 45), 5, 900);
        cooldownSec = clamp(p.getInt(Prefs.KEY_COOLDOWN_SEC, 6), 1, 300);
        weakHintSwipe = p.getBoolean(Prefs.KEY_WEAK_HINT, true);
        autoDump = p.getBoolean(Prefs.KEY_AUTO_DUMP, true);

        String raw = p.getString(Prefs.KEY_TARGET_PKGS, Prefs.DEFAULT_TARGET_PKGS);
        if (raw != null) {
            List<String> list = new ArrayList<>();
            for (String t : raw.split(",")) {
                t = t.trim();
                if (!t.isEmpty()) {
                    list.add(t);
                }
            }
            if (!list.isEmpty()) {
                targetPkgs = list.toArray(new String[0]);
            }
        }
    }

    private void tick() {
        long tickNow = System.currentTimeMillis();
        if (lastTickAt != 0L && tickNow - lastTickAt > 4000L) {
            LogBus.log("心跳异常：距上次判定 " + (tickNow - lastTickAt)
                    + "ms（说明进程被系统暂停过，或线程被长时间占用）");
            lastActionAt = 0L;
            lastProgressChange = tickNow;
            lastScreenChangeAt = tickNow;
            LogBus.log("   → 刚恢复，已重置计时，立刻重新判定");
        }
        lastTickAt = tickNow;

        reloadPrefs();

        String fg = currentForegroundPackage();
        if (debugPkg && fg != null && !fg.equals(lastLoggedPkg) && !fg.equals(getPackageName())) {
            lastLoggedPkg = fg;
            LogBus.log("前台应用包名: " + fg);
        }

        if (!master) {
            enterTargetAt = 0L;
            return;
        }

        List<AccessibilityNodeInfo> roots = findTargetRoots();
        if (roots.isEmpty()) {
            if (enterTargetAt != 0L) {
                LogBus.log("离开目标应用，暂停判定");
            }
            enterTargetAt = 0L;
            prevAdVisible = false;
            adVisible = false;
            if (tickNow - lastNullRootLogAt > 20000L) {
                String fgPkg = currentForegroundPackage();
                if (fgPkg == null || !fgPkg.equals(getPackageName())) {
                    lastNullRootLogAt = tickNow;
                    LogBus.log("读不到目标应用界面（当前前台=" + (fgPkg == null ? "读不到" : fgPkg)
                            + "）。若你正在短剧 App 里看剧，说明无障碍读取被系统掐断："
                            + "确认状态是「已连接 ✓」，并允许本应用后台运行");
                }
            }
            return;
        }

        long now = System.currentTimeMillis();
        if (enterTargetAt == 0L) {
            enterTargetAt = now;
            lastProgressChange = now;
            lastFixedSwipeAt = now;
            progressStreak = 0;
            progressAdvanceCount = 0;
            progressWindowStart = now;
            progressWindowCount = 0;
            maxEpisodeNo = 0;
            lastMotionAt = now;
            motionHasPrev = false;
            quietUntil = 0L;
            hintStreak = 0;
            hintWeakStreak = 0;
            weakHintDisarmAt = 0L;
            suspectFastDisarmAt = 0L;
            liveAdPageSig = "";
            liveAdPageSigAt = 0L;
            liveStallDoneSig = "";
            adFalsePositiveLogged = false;
            hintLogged = false;
            lastScreenChangeAt = now;
            LogBus.log("已进入目标应用，开始判定");
        }

        Scan s = new Scan();
        int[] whForScan = screenSize();
        s.screenW = whForScan[0];
        s.screenH = whForScan[1];
        long scanStart = System.currentTimeMillis();
        for (AccessibilityNodeInfo r : roots) {
            scanFast(r, s);
        }
        s.windowCount = roots.size();
        long scanCost = System.currentTimeMillis() - scanStart;
        if (scanCost > 800L) {
            LogBus.log("扫描耗时 " + scanCost + "ms（节点 " + s.nodeCount + "，界面树偏大）");
        }

        if (s.progressSig.length() > 0) {
            progressReliable = true;
        }

        String screenSig = s.screenSig.toString();
        String visibleSig = s.visibleSig.toString();
        if (!screenSig.equals(lastScreenSig)) {
            lastScreenSig = screenSig;
            lastScreenChangeAt = now;
        }
        String sig = s.progressSig.toString();
        boolean progressChanged = !sig.equals(lastProgressSig);
        if (progressChanged) {
            lastProgressSig = sig;
            lastProgressChange = now;
            progressStreak++;
            progressAdvanceCount++;
            progressWindowCount++;
        } else {
            progressStreak = 0;
        }

        if (now - progressWindowStart > 60000L) {
            progressWindowStart = now;
            progressWindowCount = 0;
        }

        // 「进度信号=有」只代表页面上出现了 12:34 这种文字，不代表它在走；
        // 红果那种十几秒才闪一下的时间文字（约 5 次/分）也不是真进度。
        // 只有像真实播放进度那样至少 15 次/分 在走，才敢用「进度停滞」做判断。
        boolean progressTrusted = progressReliable && progressWindowCount >= 15;

        if (now - lastStatusAt > 10000L) {
            lastStatusAt = now;
            LogBus.log("[状态] pkg=" + fg
                    + " 窗口=" + s.windowCount
                    + " 命中=" + s.nodeCount
                    + " 集数=" + (s.episodeLabel == null ? "无"
                    : s.episodeLabel + (s.episodeCount > 1 ? "(共" + s.episodeCount + "个)" : "")
                    + (s.visibleEpisodeLabel == null ? "(不在屏上)" : ""))
                    + " 可见文本=" + s.visibleCount
                    + " 画面=" + motionState(now)
                    + " 广告=" + (s.adVisible ? "是" : (s.adWeakVisible ? "弱" : "否"))
                    + " 进度=" + (!progressReliable ? "无"
                    : (progressTrusted ? "在走" : "读到但不动") + "(" + progressWindowCount + "次/分)")
                    + " 上滑提示=" + (s.swipeHintNode != null ? "有"
                    : (s.swipeHintWeakNode != null ? "弱" : "无"))
                    + " 直播间=" + (s.liveAdNode != null ? "有"
                    : (s.liveAdWeakNode != null ? "弱" : "无"))
                    + " 下一集=" + (s.nextNode != null ? "有" : "无")
                    + " 播放键=" + (s.playNode != null ? "有" : "无")
                    + " 重要性=" + myImportance()
                    + " 冻结=" + freezeCount + "次"
                    + " 动作=" + (pinnedStrategy >= 0 ? ("已锁定策略" + (pinnedStrategy + 1)) : "自动轮换")
                    + (now < quietUntil ? " 安静=" + ((quietUntil - now) / 1000L + 1) + "s" : ""));
        }

        if (dumpScreen && now - lastDumpAt > dumpSec * 1000L) {
            lastDumpAt = now;
            dumpCurrentScreen();
        }

        maybeCaptureFrame(now);
        maybeAutoDump(now, s);

        // 真实广告不会推动正片进度条；若广告标记存在但进度连续两次在走，判定为误判。
        if (s.adVisible && progressStreak >= 2) {
            if (!adFalsePositiveLogged) {
                adFalsePositiveLogged = true;
                LogBus.log("广告标记疑似误判（正片进度仍在推进），已忽略广告判定");
            }
            s.adVisible = false;
        }

        if (s.episodeLabel != null) {
            everSawEpisodeLabel = true;
        }
        if (s.episodeLabel != null && !s.episodeLabel.equals(lastEpisodeLabel)) {
            // 播放页上常常同时有好几个「第N集」（上一集 / 下一集卡片），集数会在几个值
            // 之间来回跳。所以只有「集号比见过的最大值还大」才算真的往下走了一集，
            // 否则会把跳回来的那个值当成动作生效的证据（会误锁定一个没用的动作）。
            int nowNo = episodeNo(s.episodeLabel);
            boolean forward = nowNo > 0 && nowNo > maxEpisodeNo;
            if (nowNo > maxEpisodeNo) {
                maxEpisodeNo = nowNo;
            }
            lastEpisodeLabel = s.episodeLabel;
            LogBus.log("识别到 " + s.episodeLabel + (forward ? "（已前进）" : ""));
            if (forward) {
                // 集数真的往前走了 => 正片在正常播放，先安静几秒，
                // 免得盯着一个还没消失的「上滑继续观看短剧」把刚开播的这集又滑走。
                quietUntil = now + QUIET_AFTER_ADVANCE_MS;
                lastAdvancedAt = now;
                if (pendingStrategy >= 0) {
                    confirmStrategy(pendingStrategy);
                }
            }
        }

        if (pendingStrategy >= 0) {
            if (now - pendingAt > 8000L) {
                if (pendingStrategy == pinnedStrategy && pendingHintPresent) {
                    pinnedFailCount++;
                    if (pinnedFailCount >= 3) {
                        LogBus.log("策略 " + (pendingStrategy + 1) + " 连续 3 次未见效，恢复自动轮换");
                        pinnedStrategy = -1;
                        pinnedFailCount = 0;
                        strategyIndex = 0;
                    }
                }
                pendingStrategy = -1;
            } else if (pendingHintPresent && s.swipeHintNode == null && !s.adVisible) {
                confirmStrategy(pendingStrategy);
            }
        }

        lastHintPresent = s.swipeHintNode != null;
        hintStreak = (s.swipeHintNode != null) ? (hintStreak + 1) : 0;
        hintWeakStreak = (s.swipeHintWeakNode != null || s.liveAdWeakNode != null)
                ? (hintWeakStreak + 1) : 0;
        boolean adJustEnded = prevAdVisible && !s.adVisible;
        adVisible = s.adVisible;
        prevAdVisible = s.adVisible;
        if (adVisible) {
            lastAdSeenAt = now;
        }
        if (s.liveAdNode != null) {
            liveAdSeenAt = now;
            // 记住"看到直播锚点时这一页的可见文字长什么样"，T5 兜底要用它确认"还是同一页"。
            liveAdPageSig = visibleSig;
            liveAdPageSigAt = now;
        }

        // 广告页上动作要快：广告总共只播几秒到十几秒，按默认 6 秒最小间隔会白等一大截。
        long cooldown = (s.adVisible ? Math.min(cooldownSec, 2) : cooldownSec) * 1000L;

        if (skipAd && s.skipNode != null && now - lastSkipAt > 1200L) {
            if (clickNode(s.skipNode)) {
                lastSkipAt = now;
                LogBus.log("已点击「跳过」广告");
            }
        }

        // 刚刚确认过集数在往前走 => 正片在正常播放，安静期内先不动手。两个例外：
        // 1) 页面上有明确的广告标记（广告 / 跳过 / N 秒）：广告页不可能是"刚开播的正片"。
        //    红果的广告是盖在下一页上的，集数标记会先把安静期点亮，白等 8 秒广告都播一半了
        //    —— 这就是"停在广告页 7、8 秒才上滑"的原因。
        // 2) 提示「上滑继续看短剧」连续出现 3 次（约 6 秒），足够排除刚换页那一下的残留节点。
        // 而且这份保护只对「屏幕上看得见第N集」的正片页有意义：红果的全屏直播广告页读不到
        // 任何可见文字、更读不到集数，却被刚开播那几秒的安静期白白挡住（实测白等 8 秒）。
        if (now < quietUntil && !s.adVisible && s.visibleEpisodeLabel != null) {
            boolean hintOverride = hintSwipe && s.swipeHintNode != null
                    && hintStreak >= 3 && now - lastActionAt > cooldown;
            if (!hintOverride) {
                return;
            }
        }

        // T0 页面明确提示「上滑继续看短剧」（红果直播广告结束后就是这种）。
        // 广告页上这行提示就是权威信号，出现一次就动手；其它情况要求连续 2 次（约 4 秒）防残留节点。
        int needHintStreak = s.adVisible ? 1 : 2;
        if (hintSwipe && s.swipeHintNode != null && hintStreak >= needHintStreak
                && now - lastActionAt > cooldown) {
            String hintText = label(s.swipeHintNode);
            int waitSec = countdownSeconds(hintText);
            if (waitSec > 0) {
                // 红果的倒计时广告写的是「3秒后可继续上滑观看短剧」：这时候滑是白滑（红果不认），
                // 更糟的是它会白白吃掉一次"同一条规则冷却"，于是倒计时走完后还要再等半分钟才滑成。
                // 所以倒计时没走完时只记一行日志，什么也不做，也不占用冷却。
                if (now - lastCountdownLogAt > 5000L) {
                    lastCountdownLogAt = now;
                    LogBus.log("广告倒计时还有 " + waitSec + " 秒（「" + hintText + "」），等它走完再上滑");
                }
                return;
            }
            LogBus.log("检测到「" + hintText + "」提示，执行上滑");
            swipeUp("上滑提示");
            return;
        }

        // T0b 直播间类广告页：这种页面常常没有「广告」二字，只有「点击进入直播间」之类文案
        if (liveAd && s.liveAdNode != null
                && now - lastActionAt > Math.min(cooldown, 3000L)) {
            LogBus.log("检测到直播间广告「" + label(s.liveAdNode) + "」，执行前进");
            swipeUp("直播间广告");
            return;
        }

        // T0c 弱提示兜底：文案在无障碍树里、位置也在屏幕里，但系统把它标成「不可见」。
        // 红果的直播广告页经常整页节点都是这个状态（"可见性"判断会把「上滑继续看短剧」
        // 「点击进入直播间」全部滤掉，于是页面上明明写着字，日志里却是「上滑提示=无」）。
        // 连续出现 2 次（约 4 秒）才动手，动完手静默 90 秒，避免把正片连着滑走。
        if (weakHintSwipe && hintWeakStreak >= 2 && now > weakHintDisarmAt
                && now - lastActionAt > cooldown) {
            AccessibilityNodeInfo wn = s.swipeHintWeakNode != null
                    ? s.swipeHintWeakNode : s.liveAdWeakNode;
            if (wn != null) {
                weakHintDisarmAt = now + 90000L;
                LogBus.log("检测到「" + label(wn) + "」文案（位置在屏幕内、系统标成不可见，"
                        + "直播广告页常见），执行前进");
                swipeUp("弱提示");
                return;
            }
        }

        // T1 直接点“下一集”
        if (clickNext && s.nextNode != null && now - lastActionAt > cooldown) {
            if (clickNode(s.nextNode)) {
                lastActionAt = now;
                lastProgressChange = now;
                LogBus.log("已点击「下一集」按钮");
                return;
            }
        }

        // T2 广告刚结束：优先点「播放/继续播放」恢复，其次再补上滑
        if (adJustEnded && now - lastAdSeenAt > 400L && now - lastActionAt > cooldown) {
            boolean acted = false;
            if (afterAdPlay && s.playNode != null) {
                if (clickNode(s.playNode)) {
                    LogBus.log("广告已结束，点「" + label(s.playNode) + "」恢复播放");
                    acted = true;
                }
            }
            if (!acted && afterAd) {
                LogBus.log("广告已结束，补一次上滑");
                swipeUp("广告结束后");
                acted = true;
            }
            if (acted) {
                lastActionAt = now;
                lastProgressChange = now;
                return;
            }
        }

        if (adVisible) {
            return;
        }

        // T3 播放进度停滞（只有确认进度真的在走，才敢用它判断）
        if (idleSwipe) {
            if (!progressTrusted) {
                if (!hintLogged && now - enterTargetAt > 8000L) {
                    hintLogged = true;
                    LogBus.log(progressReliable
                            ? "提示：读到的时间文字走得太慢（" + progressWindowCount
                            + " 次/分），不像真实播放进度，已停用『进度停滞上滑』，避免正片播放中被误滑"
                            : "提示：读不到播放进度信号，已停用『进度停滞上滑』，避免正片播放中被误滑");
                }
            } else if (now - lastProgressChange > idleSec * 1000L && now - lastActionAt > cooldown) {
                LogBus.log("播放进度停滞 " + idleSec + " 秒，补一次上滑");
                swipeUp("进度停滞");
                return;
            }
        }

        // T3b 兜底：屏幕文字长时间毫无变化（读不到进度、也读不到提示时的最后手段）
        if (snapshotIdle && !adVisible
                && now - lastScreenChangeAt > snapshotSec * 1000L
                && now - lastActionAt > cooldown) {
            LogBus.log("屏幕 " + snapshotSec + " 秒无变化，执行上滑（兜底）");
            swipeUp("屏幕无变化");
            return;
        }

        // T5 直播广告兜底：红果的直播广告页底部写着「上滑继续看短剧」，但这行字常常不在
        // 无障碍树里（所以「上滑提示=无」），只能靠「见过直播锚点 + 整页可见文字从那一刻起
        // 一点儿没变」来判断。
        //
        // 关键（第三轮实测踩到的坑）：必须限定「现在还是那一页」。之前只要求"3 分钟内见过
        // 直播锚点"，而正片播放时页面文字同样长时间不变，于是广告一结束，这条规则就变成
        // 每 60 秒滑一次的定时器，把后面几集正片从中间切碎（12:10:25 / 12:11:27 / 12:12:28 三次）。
        if (liveStall && !adVisible && s.swipeHintNode == null && s.liveAdNode == null
                && !liveAdPageSig.isEmpty() && liveAdPageSig.equals(visibleSig)
                && !visibleSig.equals(liveStallDoneSig)
                && now - liveAdPageSigAt < 180000L
                && now > liveStallDisarmAt
                && now - lastScreenChangeAt > liveStallSec * 1000L
                && now - lastActionAt > cooldown) {
            liveStallDisarmAt = now + 60000L;
            liveStallDoneSig = visibleSig;
            LogBus.log("直播间页面文字 " + liveStallSec + " 秒无变化，补一次上滑（直播广告兜底）");
            swipeUp("直播页无变化");
            return;
        }
        if (!liveStall && !liveStallLogged && !adVisible && s.liveAdNode == null
                && !liveAdPageSig.isEmpty() && liveAdPageSig.equals(visibleSig)
                && now - liveAdPageSigAt < 180000L
                && now - lastScreenChangeAt > liveStallSec * 1000L) {
            liveStallLogged = true;
            LogBus.log("提示：检测到直播间页面文字长时间无变化（可能正卡在直播广告）。"
                    + "可在设置里打开「直播广告兜底」让它自动前进");
        }
        if (now - liveAdSeenAt > 180000L) {
            liveStallLogged = false;
        }

        // T6a 全屏直播广告快滑：红果最麻烦的那种广告——整页盖住、一条可见文字都没有
        // （直播画面是画上去的，无障碍树里一个字都不读出来）。上面的规则一条也点不着，
        // 日志里就是「广告=否 上滑提示=无 直播间=无 下一集=无 播放键=无 可见文本=0」。
        // 唯一稳定的特征：正片页左上角必定有可见的「第N集」，而这一页什么都读不到。
        // 实测（2026-10-14 20:06）这种页面连续 «集数=第79集(不在屏上) 可见文本=0»，
        // 靠 T6 兜底要等 15 秒才滑走；这里默认 5 秒。
        if (suspectFast && everSawEpisodeLabel && s.visibleCount == 0
                && s.visibleEpisodeLabel == null && s.nodeCount >= 1
                && s.swipeHintNode == null && s.liveAdNode == null
                && s.nextNode == null && s.playNode == null
                && !adVisible
                && now > suspectFastDisarmAt
                && now - lastScreenChangeAt > suspectFastSec * 1000L
                && now - lastActionAt > cooldown) {
            suspectFastDisarmAt = now + 60000L;
            LogBus.log("屏幕上 " + suspectFastSec + " 秒读不到任何可见文字、也读不到集数标签"
                    + "（树里 " + s.nodeCount + " 个节点、集数="
                    + (s.episodeLabel == null ? "无" : s.episodeLabel)
                    + "，疑似全屏直播广告），执行上滑");
            swipeUp("全屏广告页");
            return;
        }

        // T6 疑似广告 / 直播间页面兜底：
        // 红果的直播广告页几乎读不到任何文案，整页文字也不再变化，靠文案根本认不出来。
        // 关键是用「**屏幕上看得见**的集数标签」判断，而不是树里有没有：实测那个全屏直播广告页
        // 把「第8集」留在树里但整块都在屏幕外（[53,5080,881,2700]，不可见），
        // 于是「集数=第8集」一直非空，这条兜底永远不触发 —— 广告页就这么卡着不动了。
        // 正片播放时「第N集」就在左上角可见，所以这个条件不会误伤正片。
        if (suspectAd && everSawEpisodeLabel && s.visibleCount <= 4
                && s.nodeCount <= 8 && s.visibleEpisodeLabel == null
                && s.swipeHintNode == null && s.liveAdNode == null
                && s.nextNode == null && s.playNode == null
                && !adVisible
                && now > suspectDisarmAt
                && now - lastScreenChangeAt > suspectSec * 1000L
                && now - lastActionAt > cooldown) {
            suspectDisarmAt = now + 90000L;
            LogBus.log("页面上 " + suspectSec + " 秒没变化、屏幕上看不到集数标签"
                    + "（可见文本 " + s.visibleCount + " 条），判定为广告/直播页，补一次上滑（疑似广告页兜底）");
            swipeUp("疑似广告页");
            return;
        }
        if (!suspectAd && !suspectLogged && everSawEpisodeLabel
                && s.visibleCount <= 4 && s.nodeCount <= 8 && s.visibleEpisodeLabel == null && !adVisible
                && now - lastScreenChangeAt > suspectSec * 1000L) {
            suspectLogged = true;
            LogBus.log("提示：当前页读不到剧集信息且长时间无变化（很可能是直播广告页）。"
                    + "可在设置里打开「疑似广告页兜底」让它自动前进");
        }

        // T7 画面静止：连续 N 秒画面几乎一动不动 => 视频已经停住（一集播完卡住 / 卡在某处），
        // 这时补一次上滑是安全的。正片播放中画面一直在动，永远不会触发。
        // 这是唯一不依赖界面文案的办法，专治"红果什么都不暴露、读不到任何提示"的场景。
        if (motionDetect && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
                && !adVisible && s.episodeLabel != null
                && s.swipeHintNode == null && s.nextNode == null
                && lastMotionAt != 0L
                && now - lastMotionAt > motionSec * 1000L
                && now - lastActionAt > cooldown) {
            lastMotionAt = now;
            LogBus.log("画面已静止 " + motionSec + " 秒（视频停住了），补一次上滑");
            swipeUp("画面静止");
            return;
        }

        // T4 固定间隔
        if (fixedSwipe && now - lastFixedSwipeAt > fixedSec * 1000L && now - lastActionAt > cooldown) {
            lastFixedSwipeAt = now;
            LogBus.log("定时上滑（间隔 " + fixedSec + " 秒）");
            swipeUp("定时");
        }
    }

    /** 扫描结果 */
    private static final class Scan {
        int nodeCount;
        int windowCount;
        int episodeCount;
        int screenW;
        int screenH;
        StringBuilder screenSig = new StringBuilder();
        /** 只统计"屏幕上真正看得见"的文字，用来判断"还是不是同一页"（残留节点不参与）。 */
        StringBuilder visibleSig = new StringBuilder();
        /** 只在"屏幕上看得见"的节点里找的集数标签：直播广告页会把第N集留在树里但不在屏幕上。 */
        String visibleEpisodeLabel;
        /** 屏幕上真正看得见的文本条数：正片页十几条，全屏广告页往往只有一两条。 */
        int visibleCount;
        boolean adVisible;
        boolean adWeakVisible;
        AccessibilityNodeInfo skipNode;
        AccessibilityNodeInfo nextNode;
        AccessibilityNodeInfo playNode;
        AccessibilityNodeInfo swipeHintNode;
        AccessibilityNodeInfo liveAdNode;
        AccessibilityNodeInfo swipeHintWeakNode;
        AccessibilityNodeInfo liveAdWeakNode;
        StringBuilder progressSig = new StringBuilder();
        String episodeLabel;
    }

    /**
     * 用关键词检索代替整树遍历。
     * 整树遍历每访问一个节点都要跨进程一次，红果播视频时会被拖住几十秒；
     * 改成少量 findAccessibilityNodeInfosByText 调用后，每次判定只有约 10 次跨进程调用。
     */
    private void scanFast(AccessibilityNodeInfo root, Scan s) {
        tickCount++;
        for (String key : SEARCH_KEYS) {
            classifyList(root, s, key);
        }
    }

    private void classifyList(AccessibilityNodeInfo root, Scan s, String key) {
        try {
            List<AccessibilityNodeInfo> list = root.findAccessibilityNodeInfosByText(key);
            if (list == null) {
                return;
            }
            int limit = Math.min(list.size(), 30);
            for (int i = 0; i < limit; i++) {
                classify(list.get(i), s);
            }
        } catch (Throwable ignored) {
        }
    }

    private void classify(AccessibilityNodeInfo n, Scan s) {
        if (n == null) {
            return;
        }
        s.nodeCount++;
        CharSequence textCs = n.getText();
        CharSequence descCs = n.getContentDescription();
        String text = textCs == null ? "" : textCs.toString().trim();
        String desc = descCs == null ? "" : descCs.toString().trim();
        String all = text.isEmpty() ? desc : (desc.isEmpty() ? text : text + " | " + desc);
        if (all.isEmpty()) {
            return;
        }
        if (s.screenSig.length() < 4000) {
            s.screenSig.append(all).append('|');
        }

        // 只有真的显示在当前屏幕上的节点才算数：红果是上下翻页的播放流，
        // 相邻几页都在无障碍树里，上一页广告残留的「上滑继续观看短剧」「下一集」
        // 会挂着不消失；不看位置就会把它们当成"当前页有提示"，把正在播的这集又滑走。
        boolean shown = onScreen(n, s);
        if (shown) {
            s.visibleCount++;
            if (s.visibleSig.length() < 4000) {
                s.visibleSig.append(all).append('|');
            }
        }

        if (shown && s.swipeHintNode == null) {
            for (String k : SWIPE_HINT_TEXTS) {
                if (all.contains(k)) {
                    s.swipeHintNode = n;
                    break;
                }
            }
            if (s.swipeHintNode == null && P_SWIPE_HINT.matcher(all).find()) {
                s.swipeHintNode = n;
            }
        }

        if (shown && s.liveAdNode == null && liveAd) {
            for (String k : LIVE_AD_TEXTS) {
                if (all.contains(k)) {
                    s.liveAdNode = n;
                    break;
                }
            }
        }

        // 同一批文案：位置确实在屏幕里，但系统标成「不可见」的，单独记一份。
        // 红果的直播广告页 / 广告浮层经常就是这种状态——字实实在在地显示着，
        // isVisibleToUser() 却返回 false；之前只认「可见」，等于把它们全滤掉了。
        if (!shown && onScreenGeo(n, s)) {
            if (s.swipeHintWeakNode == null) {
                for (String k : SWIPE_HINT_TEXTS) {
                    if (all.contains(k)) {
                        s.swipeHintWeakNode = n;
                        break;
                    }
                }
                if (s.swipeHintWeakNode == null && P_SWIPE_HINT.matcher(all).find()) {
                    s.swipeHintWeakNode = n;
                }
            }
            if (s.liveAdWeakNode == null) {
                for (String k : LIVE_AD_TEXTS) {
                    if (all.contains(k)) {
                        s.liveAdWeakNode = n;
                        break;
                    }
                }
            }
            for (String k : AD_MARKERS) {
                if (all.contains(k)) {
                    s.adWeakVisible = true;
                    break;
                }
            }
        }

        if (shown) {
            for (String k : AD_MARKERS) {
                if (all.contains(k)) {
                    s.adVisible = true;
                    if (all.contains("跳过") && s.skipNode == null) {
                        s.skipNode = n;
                    }
                    break;
                }
            }
            if (P_SEC.matcher(text).find()) {
                s.adVisible = true;
            }
        }

        Matcher mt = P_TIME.matcher(text);
        while (mt.find()) {
            s.progressSig.append(mt.group()).append(',');
        }

        Matcher me = P_EPISODE.matcher(all);
        if (me.find()) {
            s.episodeCount++;
            if (s.episodeLabel == null) {
                s.episodeLabel = me.group();
            }
            if (shown && s.visibleEpisodeLabel == null) {
                s.visibleEpisodeLabel = me.group();
            }
        }

        if (shown && s.nextNode == null) {
            for (String k : NEXT_TEXTS) {
                if (all.contains(k)) {
                    s.nextNode = n;
                    break;
                }
            }
        }

        if (shown && s.playNode == null) {
            for (String k : PLAY_TEXTS) {
                if (all.equals(k)) {
                    s.playNode = n;
                    break;
                }
            }
        }
    }

    private void scanByTraversal(AccessibilityNodeInfo n, Scan s, int depth) {
        if (n == null || depth > 40) {
            return;
        }
        s.nodeCount++;
        if (s.nodeCount > 900) {
            return;
        }

        CharSequence textCs = n.getText();
        CharSequence descCs = n.getContentDescription();
        String text = textCs == null ? "" : textCs.toString().trim();
        String desc = descCs == null ? "" : descCs.toString().trim();
        String all = text.isEmpty() ? desc : (desc.isEmpty() ? text : text + " | " + desc);

        if (!all.isEmpty()) {
            if (s.screenSig.length() < 4000) {
                s.screenSig.append(all).append('|');
            }
            for (String k : AD_MARKERS) {
                if (all.contains(k)) {
                    s.adVisible = true;
                    if (all.contains("跳过") && s.skipNode == null) {
                        s.skipNode = n;
                    }
                    break;
                }
            }
            if (P_SEC.matcher(text).find()) {
                s.adVisible = true;
            }

            Matcher mt = P_TIME.matcher(text);
            while (mt.find()) {
                s.progressSig.append(mt.group()).append(',');
            }

            if (s.episodeLabel == null) {
                Matcher me = P_EPISODE.matcher(all);
                if (me.find()) {
                    s.episodeLabel = me.group();
                }
            }

            boolean matchedNext = false;
            if (s.nextNode == null) {
                for (String k : NEXT_TEXTS) {
                    if (all.contains(k)) {
                        s.nextNode = n;
                        matchedNext = true;
                        break;
                    }
                }
            }
            if (s.swipeHintNode == null) {
                for (String k : SWIPE_HINT_TEXTS) {
                    if (all.contains(k)) {
                        s.swipeHintNode = n;
                        break;
                    }
                }
                if (s.swipeHintNode == null && P_SWIPE_HINT.matcher(all).find()) {
                    s.swipeHintNode = n;
                }
            }

            if (!matchedNext && s.playNode == null) {
                for (String k : PLAY_TEXTS) {
                    if (all.equals(k)) {
                        s.playNode = n;
                        break;
                    }
                }
            }
        }

        String cls = n.getClassName() == null ? "" : n.getClassName().toString();
        if (cls.contains("SeekBar") || cls.contains("ProgressBar")) {
            try {
                AccessibilityNodeInfo.RangeInfo ri = n.getRangeInfo();
                if (ri != null && ri.getMax() > 0f) {
                    s.progressSig.append('r')
                            .append((int) ri.getCurrent())
                            .append('/')
                            .append((int) ri.getMax())
                            .append(';');
                }
            } catch (Throwable ignored) {
            }
        }

        int count = n.getChildCount();
        for (int i = 0; i < count; i++) {
            scanByTraversal(n.getChild(i), s, depth + 1);
        }
    }

    private String currentForegroundPackage() {
        try {
            AccessibilityNodeInfo r = getRootInActiveWindow();
            if (r != null && r.getPackageName() != null) {
                return r.getPackageName().toString();
            }
        } catch (Throwable ignored) {
        }
        try {
            List<AccessibilityWindowInfo> ws = getWindows();
            if (ws != null) {
                for (AccessibilityWindowInfo w : ws) {
                    if (w.getType() != AccessibilityWindowInfo.TYPE_APPLICATION) {
                        continue;
                    }
                    AccessibilityNodeInfo r = w.getRoot();
                    if (r != null && r.getPackageName() != null) {
                        return r.getPackageName().toString();
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static String label(AccessibilityNodeInfo n) {
        CharSequence t = n.getText();
        if (t != null && t.length() > 0) {
            return t.toString();
        }
        CharSequence d = n.getContentDescription();
        if (d != null && d.length() > 0) {
            return d.toString();
        }
        return "?";
    }

    private AccessibilityNodeInfo findTargetRoot() {
        List<AccessibilityNodeInfo> roots = findTargetRoots();
        return roots.isEmpty() ? null : roots.get(0);
    }

    /**
     * 找出目标应用所有可读的窗口根节点。
     *
     * 只读「当前活动窗口」会漏东西：红果的广告 / 直播页常常是一个覆盖在播放器
     * 之上的独立窗口（同一个包名、不同窗口），活动窗口正好是那个几乎没有文字的
     * 播放器窗口，于是「广告」「上滑继续看短剧」这些字一个都读不到。
     * 所以这里把活动窗口 + 所有目标应用的窗口一起返回。
     */
    private List<AccessibilityNodeInfo> findTargetRoots() {
        List<AccessibilityNodeInfo> out = new ArrayList<>();
        try {
            addRoot(out, getRootInActiveWindow());
        } catch (Throwable ignored) {
        }
        try {
            List<AccessibilityWindowInfo> windows = getWindows();
            if (windows != null) {
                for (AccessibilityWindowInfo w : windows) {
                    if (w.getType() != AccessibilityWindowInfo.TYPE_APPLICATION) {
                        continue;
                    }
                    addRoot(out, w.getRoot());
                }
            }
        } catch (Throwable ignored) {
        }
        return out;
    }

    private void addRoot(List<AccessibilityNodeInfo> out, AccessibilityNodeInfo r) {
        if (r == null) {
            return;
        }
        CharSequence pk = r.getPackageName();
        if (pk == null || !isTargetPackage(pk.toString())) {
            return;
        }
        for (AccessibilityNodeInfo e : out) {
            if (e.equals(r)) {
                return;
            }
        }
        out.add(r);
    }

    private boolean isTargetPackage(String pkg) {
        for (String t : targetPkgs) {
            if (pkg.equals(t) || pkg.startsWith(t + ".")) {
                return true;
            }
        }
        return false;
    }

    private boolean clickNode(AccessibilityNodeInfo node) {
        if (node == null) {
            return false;
        }
        AccessibilityNodeInfo cur = node;
        int guard = 0;
        while (cur != null && guard++ < 8) {
            try {
                if (cur.isClickable() && cur.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                    return true;
                }
            } catch (Throwable ignored) {
            }
            cur = cur.getParent();
        }
        Rect r = new Rect();
        node.getBoundsInScreen(r);
        if (r.width() > 0 && r.height() > 0) {
            return tap(r.centerX(), r.centerY());
        }
        return false;
    }

    private boolean tap(float x, float y) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            return false;
        }
        Path p = new Path();
        p.moveTo(x, y);
        GestureDescription gd = new GestureDescription.Builder()
                .addStroke(new GestureDescription.StrokeDescription(p, 0L, 60L))
                .build();
        return dispatchGesture(gd, null, null);
    }

    /**
     * 每次触发都换一种“让页面前进”的策略，用日志把结果暴露出来。
     * 有些页面不认无障碍注入的快滑，只认真手指式的慢拖，或干脆得点按钮，
     * 所以这里轮换试，让日志告诉我们哪一种对这种广告页有效。
     */
    private void swipeUp(final String reason) {
        runOnWorker(() -> doStrategy(reason));
    }

    private void doStrategy(final String reason) {
        long nowMs = System.currentTimeMillis();
        // 同一条规则 15 秒内连着来、而且中间集数并没有真的前进 => 跳过这一次。
        // 红果广告结束后那行「上滑继续观看短剧」会在树里残留十几秒，
        // 不拦一下的话刚开播的正片又会被滑走。手动测试不受这条限制。
        if (!reason.startsWith("手动")) {
            Long lastSame = ruleLastAt.get(reason);
            long gapMs = isHintReason(reason) ? HINT_RETRY_GAP_MS : RULE_REPEAT_GAP_MS;
            if (lastSame != null && lastSame > lastAdvancedAt
                    && nowMs - lastSame < gapMs) {
                if (nowMs - lastRuleSkipLogAt > 8000L) {
                    lastRuleSkipLogAt = nowMs;
                    LogBus.log("    （同一条规则 "
                            + ((gapMs - (nowMs - lastSame)) / 1000L + 1)
                            + " 秒内刚动过手、集数也没前进，这次先跳过）");
                }
                return;
            }
            ruleLastAt.put(reason, nowMs);
        }
        // 上一次动作之后页面文字一点变化都没有 => 那种动作没生效，直接换下一种，
        // 而不是拿同一招反复戳（红果的广告页就常见这种情况）。
        boolean lastDidNothing = !lastScreenSig.isEmpty()
                && lastScreenSig.equals(lastActionScreenSig)
                && nowMs - lastActionAt < 20000L;
        int strategy;
        if (lastDidNothing) {
            strategy = (lastStrategy + 1) % STRATEGY_COUNT;
            LogBus.log("    （上一次动作页面没变化，换下一种动作再试）");
        } else {
            strategy = pinnedStrategy >= 0
                    ? pinnedStrategy
                    : Math.floorMod(strategyIndex++, STRATEGY_COUNT);
        }
        lastStrategy = strategy;
        lastActionScreenSig = lastScreenSig;
        pendingStrategy = strategy;
        pendingAt = System.currentTimeMillis();
        pendingEpisodeLabel = lastEpisodeLabel;
        pendingHintPresent = (lastHintPresent);
        LogBus.log("▶ 动作策略 " + (strategy + 1) + "/" + STRATEGY_COUNT + "（" + reason + "）");
        switch (strategy) {
            case 0:
                gestureSwipe(0.85f, 0.20f, 700L, "慢拖");
                break;
            case 1:
                gestureSwipe(0.80f, 0.25f, 240L, "快滑");
                break;
            case 2:
                gestureSwipe(0.92f, 0.30f, 350L, "底部起滑");
                break;
            case 3:
                gestureSwipe(0.88f, 0.12f, 1100L, "超慢长拖");
                break;
            case 4:
                clickNextEpisodeAction();
                break;
            case 5:
                scrollForwardAction();
                break;
            default:
                doubleSwipe();
                break;
        }
        lastActionAt = System.currentTimeMillis();
        lastProgressChange = lastActionAt;
    }

    private int[] screenSize() {
        try {
            WindowManager wm = (WindowManager) getSystemService(WINDOW_SERVICE);
            DisplayMetrics dm = new DisplayMetrics();
            wm.getDefaultDisplay().getRealMetrics(dm);
            return new int[]{dm.widthPixels, dm.heightPixels};
        } catch (Throwable t) {
            DisplayMetrics dm = getResources().getDisplayMetrics();
            return new int[]{dm.widthPixels, dm.heightPixels};
        }
    }

    /**
     * 节点是不是真的显示在当前屏幕上。
     * 红果是上下翻页的播放流，相邻页都挂在无障碍树里；上一页广告残留下来的
     * 「上滑继续观看短剧」「下一集」会一直挂在树里十几秒，只看文案不看位置，
     * 就会把这些残留当成"当前页有提示"，把正在播的这一集又滑走。
     */
    private boolean onScreen(AccessibilityNodeInfo n, Scan s) {
        try {
            if (!n.isVisibleToUser()) {
                return false;
            }
        } catch (Throwable ignored) {
            return false;
        }
        try {
            Rect r = new Rect();
            n.getBoundsInScreen(r);
            if (r.width() <= 0 || r.height() <= 0) {
                return false;
            }
            if (s.screenW <= 0 || s.screenH <= 0) {
                return true;
            }
            return r.bottom > 0 && r.top < s.screenH && r.right > 0 && r.left < s.screenW;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * 节点的位置是不是落在屏幕里（不看 isVisibleToUser）。
     * 用来把两类节点分开：「上一页残留、整块都在屏幕外」的是噪音；
     * 「屏幕里确实有这块区域、只是被系统标成不可见」的往往就是红果直播广告页上的真实文案。
     */
    private boolean onScreenGeo(AccessibilityNodeInfo n, Scan s) {
        try {
            Rect r = new Rect();
            n.getBoundsInScreen(r);
            if (r.width() <= 0 || r.height() <= 0) {
                return false;
            }
            if (s.screenW <= 0 || s.screenH <= 0) {
                return true;
            }
            return r.bottom > 0 && r.top < s.screenH && r.right > 0 && r.left < s.screenW;
        } catch (Throwable t) {
            return false;
        }
    }

    private boolean gestureSwipe(final float fromY, final float toY, final long durMs, final String tag) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            runOnMain(() -> gestureSwipe(fromY, toY, durMs, tag));
            return true;
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            LogBus.log("    · " + tag + "：系统低于 Android 7，跳过");
            return false;
        }
        int[] wh = screenSize();
        int w = wh[0];
        int h = wh[1];
        if (w <= 0 || h <= 0) {
            LogBus.log("    · " + tag + "：拿不到屏幕尺寸");
            return false;
        }
        Path path = new Path();
        path.moveTo(w * 0.5f, h * fromY);
        path.lineTo(w * 0.5f, h * toY);
        GestureDescription gd = new GestureDescription.Builder()
                .addStroke(new GestureDescription.StrokeDescription(path, 0L, durMs))
                .build();
        boolean ok = dispatchGesture(gd, gestureCallback, handler);
        LogBus.log("    · " + tag + " " + durMs + "ms -> " + (ok ? "已下发" : "下发失败"));
        return ok;
    }

    private void doubleSwipe() {
        if (!gestureSwipe(0.85f, 0.25f, 260L, "双滑-第1次")) {
            return;
        }
        handler.postDelayed(() -> gestureSwipe(0.85f, 0.25f, 260L, "双滑-第2次"), 250L);
    }

    private void clickNextEpisodeAction() {
        AccessibilityNodeInfo root = findTargetRoot();
        if (root == null) {
            LogBus.log("    · 点「下一集」：读不到界面");
            return;
        }
        AccessibilityNodeInfo n = findTextNode(root, new String[]{"下一集", "下一章", "下一首"}, 0);
        if (n == null) {
            LogBus.log("    · 点「下一集」：没找到这样的按钮");
            return;
        }
        boolean ok = clickNode(n);
        LogBus.log("    · 点击「" + label(n) + "」-> " + (ok ? "已接受" : "被拒绝"));
    }

    private void scrollForwardAction() {
        AccessibilityNodeInfo root = findTargetRoot();
        if (root == null) {
            LogBus.log("    · 滚动动作：读不到界面");
            return;
        }
        AccessibilityNodeInfo scrollable = findScrollable(root, 0);
        if (scrollable == null) {
            LogBus.log("    · 滚动动作：没找到可滚动容器");
            return;
        }
        boolean ok = false;
        try {
            ok = scrollable.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD);
        } catch (Throwable ignored) {
        }
        LogBus.log("    · 滚动动作 ACTION_SCROLL_FORWARD -> " + (ok ? "已接受" : "被拒绝"));
    }

    private AccessibilityNodeInfo findScrollable(AccessibilityNodeInfo n, int depth) {
        if (n == null || depth > 30) {
            return null;
        }
        try {
            if (n.isScrollable()) {
                return n;
            }
        } catch (Throwable ignored) {
        }
        int count = n.getChildCount();
        for (int i = 0; i < count; i++) {
            AccessibilityNodeInfo r = findScrollable(n.getChild(i), depth + 1);
            if (r != null) {
                return r;
            }
        }
        return null;
    }

    private AccessibilityNodeInfo findTextNode(AccessibilityNodeInfo n, String[] keys, int depth) {
        if (n == null || depth > 30) {
            return null;
        }
        CharSequence t = n.getText();
        CharSequence d = n.getContentDescription();
        String all = (t == null ? "" : t.toString()) + "|" + (d == null ? "" : d.toString());
        for (String k : keys) {
            if (all.contains(k)) {
                return n;
            }
        }
        int count = n.getChildCount();
        for (int i = 0; i < count; i++) {
            AccessibilityNodeInfo r = findTextNode(n.getChild(i), keys, depth + 1);
            if (r != null) {
                return r;
            }
        }
        return null;
    }

    /** 供界面上的「立即测试上滑」按钮调用。 */
    public void testSwipe() {
        LogBus.log("收到手动测试请求");
        swipeUp("手动测试");
    }

    /**
     * 手动轮换：延迟若干秒后，依次把 7 种动作策略都试一遍。
     * 用法：点按钮后立刻切到红果并停在广告页，别碰屏幕，看页面在第几次动作后前进。
     */
    public void startStrategySweep(int startDelaySec) {
        strategyIndex = 0;
        LogBus.log("将在 " + startDelaySec + " 秒后逐个试动作，请立刻切到短剧 App、停在广告页且不要碰屏幕");
        for (int i = 0; i < STRATEGY_COUNT; i++) {
            final int n = i;
            handler.postDelayed(() -> {
                LogBus.log("===== 第 " + (n + 1) + " 次动作尝试 =====");
                swipeUp("手动轮换");
            }, startDelaySec * 1000L + i * 3500L);
        }
    }
    /**
     * 疑似广告 / 直播页自动抓屏。
     * 红果的直播广告页文案可能压根不在无障碍树里，只能靠截图或结构判断；这里在
     * 「文字长时间不动 + 页面文案很少」时自动留一份现场，免得用户忘了点「立即抓一次屏」。
     * 60 秒最多一次，整个会话最多 8 次。
     */
    private void maybeAutoDump(long now, Scan s) {
        if (!autoDump || !everSawEpisodeLabel) {
            return;
        }
        // 广告页本身马上就会动手，别在这里浪费 2 秒去抓屏。
        if (s.adVisible || s.liveAdNode != null) {
            return;
        }
        if (autoDumpCount >= 8 || now - lastAutoDumpAt < 120000L) {
            return;
        }
        // 全屏直播广告页整页读不到一条可见文字，判据少、留一份现场很值钱，所以这类页面
        // 把停滞阈值降到 3 秒，赶在「全屏广告快滑」（默认 5 秒）动手之前把现场抓下来。
        boolean blindPage = s.visibleCount == 0 && s.visibleEpisodeLabel == null;
        if (now - lastScreenChangeAt < (blindPage ? 3000L : 15000L)) {
            return;
        }
        boolean suspicious = s.swipeHintWeakNode != null || s.liveAdWeakNode != null
                || s.visibleEpisodeLabel == null || s.nodeCount <= 3;
        if (!suspicious) {
            return;
        }
        lastAutoDumpAt = now;
        autoDumpCount++;
        LogBus.log("[自动抓屏] 第 " + autoDumpCount + "/8 次：页面文字已 "
                + ((now - lastScreenChangeAt) / 1000L) + " 秒没变，抓一份现场（用于对比直播广告页和正片页）");
        if (blindPage) {
            LogBus.log("[自动抓屏] 这类页面整页没有可见文字（很像全屏直播广告），抓整棵树");
            dumpCurrentScreen(true);
        } else {
            dumpCurrentScreen(false);
        }
    }

    /** 排障用（按钮触发）：把当前整棵树所有文字（含位置 / 可见性）倒进日志，方便定位真实文案。 */
    private void dumpCurrentScreen() {
        dumpCurrentScreen(true);
    }

    /**
     * @param full true=整棵树所有文字；false=只打印落在屏幕里的节点（自动抓屏用，省日志）
     */
    private void dumpCurrentScreen(boolean full) {
        List<AccessibilityNodeInfo> roots = findTargetRoots();
        if (roots.isEmpty()) {
            AccessibilityNodeInfo r = null;
            try {
                r = getRootInActiveWindow();
            } catch (Throwable ignored) {
            }
            if (r == null) {
                LogBus.log("[抓屏] 读不到界面节点（root=null）");
                return;
            }
            roots.add(r);
            LogBus.log("[抓屏] 当前窗口不属于目标应用，也抓一份看看");
        }
        List<String> out = new ArrayList<>();
        StringBuilder pkgs = new StringBuilder();
        for (AccessibilityNodeInfo r : roots) {
            CharSequence pk = r.getPackageName();
            pkgs.append(pk == null ? "?" : pk.toString()).append(' ');
            collectTexts(r, out, 0, 80, full);
        }
        LogBus.log("===== [抓屏] 窗口=" + roots.size() + " pkg=" + pkgs.toString().trim()
                + " 文本条目=" + out.size() + (full ? "（整棵树）" : "（仅屏幕内）") + " =====");
        LogBus.log("[抓屏] 窗口列表=" + windowList());
        for (String line : out) {
            LogBus.log("  ・ " + line);
        }
        probeAnchors(roots);
        LogBus.log("===== [抓屏结束] =====");
    }

    /** 排障用：当前有哪些窗口、分别属于哪个包（红果的广告常常是盖在播放器上的另一个窗口）。 */
    private String windowList() {
        StringBuilder sb = new StringBuilder();
        try {
            List<AccessibilityWindowInfo> ws = getWindows();
            if (ws != null) {
                for (AccessibilityWindowInfo w : ws) {
                    AccessibilityNodeInfo r = null;
                    try {
                        r = w.getRoot();
                    } catch (Throwable ignored) {
                    }
                    CharSequence pk = r == null ? null : r.getPackageName();
                    sb.append('[').append(w.getType()).append(' ')
                            .append(pk == null ? "?" : pk).append("] ");
                }
            }
        } catch (Throwable ignored) {
        }
        return sb.toString().trim();
    }

    /**
     * 锚点探测：把所有关键文案（上滑提示 / 直播间 / 广告 / 下一集）在整棵树里搜一遍，
     * 不管它「可见」还是「不可见」，把命中的原文 + 坐标 + 可见性全打出来。
     * 这样即使是红果那种整页节点都被标成「不可见」的直播广告页，也能知道真实文案在哪。
     */
    private void probeAnchors(List<AccessibilityNodeInfo> roots) {
        String[] keys = concatKeys(SWIPE_HINT_TEXTS, LIVE_AD_TEXTS, AD_MARKERS, NEXT_TEXTS);
        for (AccessibilityNodeInfo r : roots) {
            for (String key : keys) {
                List<AccessibilityNodeInfo> hit;
                try {
                    hit = r.findAccessibilityNodeInfosByText(key);
                } catch (Throwable t) {
                    continue;
                }
                if (hit == null || hit.isEmpty()) {
                    continue;
                }
                int limit = Math.min(hit.size(), 3);
                for (int i = 0; i < limit; i++) {
                    LogBus.log("  ・[锚点] " + key + " → " + label(hit.get(i)) + geom(hit.get(i)));
                }
            }
        }
    }

    private static String[] concatKeys(String[]... groups) {
        int n = 0;
        for (String[] g : groups) {
            n += g.length;
        }
        String[] out = new String[n];
        int i = 0;
        for (String[] g : groups) {
            for (String s : g) {
                out[i++] = s;
            }
        }
        return out;
    }

    /** 界面上的「立即抓一次屏」按钮：留几秒给用户切到要抓的那一页。 */
    public void dumpOnceAfter(int delaySec) {
        LogBus.log("将在 " + delaySec + " 秒后抓一次屏，请切到要看的那一页并保持不动");
        handler.postDelayed(() -> runOnWorker(this::dumpCurrentScreen), delaySec * 1000L);
    }

    private void collectTexts(AccessibilityNodeInfo n, List<String> out, int depth, int max, boolean full) {
        if (n == null || depth > 32 || out.size() >= max) {
            return;
        }
        CharSequence textCs = n.getText();
        CharSequence descCs = n.getContentDescription();
        String text = textCs == null ? "" : textCs.toString().trim();
        String desc = descCs == null ? "" : descCs.toString().trim();
        if ((!text.isEmpty() || !desc.isEmpty()) && (full || onScreenQuick(n))) {
            String cls = n.getClassName() == null ? "" : n.getClassName().toString();
            StringBuilder sb = new StringBuilder();
            if (!text.isEmpty()) {
                sb.append("text=\"").append(text).append("\" ");
            }
            if (!desc.isEmpty()) {
                sb.append("desc=\"").append(desc).append("\" ");
            }
            sb.append("[").append(cls).append("]");
            sb.append(geom(n));
            String line = sb.toString();
            if (!out.contains(line)) {
                out.add(line);
            }
        }
        int count = n.getChildCount();
        for (int i = 0; i < count && out.size() < max; i++) {
            collectTexts(n.getChild(i), out, depth + 1, max, full);
        }
    }

    /** 只判断"这块区域在不在屏幕里"，自动抓屏时用来把屏幕外的残留节点过滤掉。 */
    private boolean onScreenQuick(AccessibilityNodeInfo n) {
        try {
            Rect r = new Rect();
            n.getBoundsInScreen(r);
            if (r.width() <= 0 || r.height() <= 0) {
                return false;
            }
            int[] wh = screenSize();
            return r.bottom > 0 && r.top < wh[1] && r.right > 0 && r.left < wh[0];
        } catch (Throwable t) {
            return false;
        }
    }

    /** 排障用：节点的可见性和屏幕坐标，用来判断是「上一页残留节点」还是「真的在屏幕上」。 */
    private static String geom(AccessibilityNodeInfo n) {
        String vis;
        try {
            vis = n.isVisibleToUser() ? "可见" : "不可见";
        } catch (Throwable t) {
            vis = "可见性读不到";
        }
        try {
            Rect r = new Rect();
            n.getBoundsInScreen(r);
            return " " + vis + " [" + r.left + "," + r.top + "," + r.right + "," + r.bottom + "]";
        } catch (Throwable t) {
            return " " + vis + " [读不到位置]";
        }
    }

    private void confirmStrategy(int strategy) {
        pendingStrategy = -1;
        if (pinnedStrategy == strategy) {
            return;
        }
        pinnedStrategy = strategy;
        pinnedFailCount = 0;
        swipeAttemptReset();
        LogBus.log("✔ 策略 " + (strategy + 1) + "/" + STRATEGY_COUNT + " 已确认有效，之后优先使用它");
    }

    private void swipeAttemptReset() {
        strategyIndex = (pinnedStrategy >= 0 ? pinnedStrategy : 0);
    }

    /**
     * 从「3秒后可继续上滑观看短剧」这类文案里取倒计时秒数；取不到（正常提示、没有数字）返回 0。
     * 倒计时没走完就上滑是白滑，还会白白吃掉一次"同一条规则冷却"，所以先判它。
     */
    private static int countdownSeconds(String text) {
        if (text == null) {
            return 0;
        }
        Matcher m = P_COUNTDOWN.matcher(text);
        if (!m.find()) {
            return 0;
        }
        try {
            return Integer.parseInt(m.group(1));
        } catch (Throwable t) {
            return 0;
        }
    }

    /** 广告 / 提示类规则：只在广告页上触发，重试间隔用短的那档。 */
    private static boolean isHintReason(String reason) {
        return reason.startsWith("上滑提示") || reason.startsWith("弱提示")
                || reason.startsWith("直播间广告") || reason.startsWith("直播页无变化");
    }

    private static int clamp(int v, int lo, int hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }

    /** 从「第 44 集」这类文案里取出集号，取不到返回 0。 */
    private static int episodeNo(String label) {
        if (label == null) {
            return 0;
        }
        try {
            Matcher m = P_EPISODE_DIGIT.matcher(label);
            return m.find() ? Integer.parseInt(m.group(1)) : 0;
        } catch (Throwable t) {
            return 0;
        }
    }

    private String motionState(long now) {
        if (!motionDetect || Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            return "关";
        }
        if (lastMotionAt == 0L) {
            return "初始化";
        }
        long still = (now - lastMotionAt) / 1000L;
        return still < 4L ? "动" : ("静" + still + "s");
    }

    /**
     * 画面静止检测：定时截一张屏，缩到 16x16 跟上一张比。
     * 变化的像素太少 => 画面没动 => 视频停住了。这是唯一不依赖文案的判断，
     * 「一集播完停住不动」时它能认出来，而正常播放时画面一直在动，永远不会误触发。
     */
    private void maybeCaptureFrame(long now) {
        if (!motionDetect || Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            return;
        }
        if (shotBusy || now - lastShotAt < 2000L) {
            return;
        }
        shotBusy = true;
        lastShotAt = now;
        try {
            takeScreenshot(Display.DEFAULT_DISPLAY, handler::post, new TakeScreenshotCallback() {
                @Override
                public void onSuccess(ScreenshotResult result) {
                    try {
                        analyseFrame(result);
                    } catch (Throwable t) {
                        LogBus.log("画面检测异常: " + t);
                    } finally {
                        try {
                            result.getHardwareBuffer().close();
                        } catch (Throwable ignored) {
                        }
                        shotBusy = false;
                    }
                }

                @Override
                public void onFailure(int errorCode) {
                    shotBusy = false;
                    long t = System.currentTimeMillis();
                    if (t - shotErrLoggedAt > 60000L) {
                        shotErrLoggedAt = t;
                        LogBus.log("取不到屏幕画面（错误码 " + errorCode + "），画面静止检测暂时无效");
                    }
                }
            });
        } catch (Throwable t) {
            shotBusy = false;
            long nowMs = System.currentTimeMillis();
            if (nowMs - shotErrLoggedAt > 60000L) {
                shotErrLoggedAt = nowMs;
                LogBus.log("截图不可用（" + t + "）。若一直这样，把「画面静止检测」关掉即可");
            }
        }
    }

    private void analyseFrame(ScreenshotResult result) {
        Bitmap hb = Bitmap.wrapHardwareBuffer(result.getHardwareBuffer(), result.getColorSpace());
        if (hb == null) {
            return;
        }
        Bitmap soft = hb.copy(Bitmap.Config.ARGB_8888, false);
        hb.recycle();
        if (soft == null) {
            return;
        }
        Bitmap small = Bitmap.createScaledBitmap(soft, MOTION_N, MOTION_N, false);
        if (small != soft) {
            soft.recycle();
        }
        small.getPixels(motionCur, 0, MOTION_N, 0, 0, MOTION_N, MOTION_N);
        small.recycle();
        if (!motionHasPrev) {
            motionHasPrev = true;
            System.arraycopy(motionCur, 0, motionPrev, 0, motionCur.length);
            return;
        }
        int total = MOTION_N * MOTION_N;
        int changed = 0;
        for (int i = 0; i < total; i++) {
            int a = motionCur[i];
            int b = motionPrev[i];
            int d = Math.abs((a & 0xFF) - (b & 0xFF))
                    + Math.abs(((a >> 8) & 0xFF) - ((b >> 8) & 0xFF))
                    + Math.abs(((a >> 16) & 0xFF) - ((b >> 16) & 0xFF));
            if (d > 36) {
                changed++;
            }
        }
        System.arraycopy(motionCur, 0, motionPrev, 0, motionCur.length);
        if (changed * 100 / total >= MOTION_MOVE_PERCENT) {
            lastMotionAt = System.currentTimeMillis();
        }
    }

    /** 当前进程的重要性：数值越大越容易被系统冻结或回收。 */
    private static int myImportance() {
        try {
            RunningAppProcessInfo info = new RunningAppProcessInfo();
            ActivityManager.getMyMemoryState(info);
            return info.importance;
        } catch (Throwable t) {
            return -1;
        }
    }

    private static String importanceHint() {
        int v = myImportance();
        if (v <= 0) {
            return "";
        }
        if (v <= 125) {
            return "（前台级，很稳）";
        }
        if (v <= 300) {
            return "（服务级，一般不会被冻结）";
        }
        return "（缓存级，最容易被国产系统冻结：必须开常驻通知 + 电池白名单 + 允许后台活动）";
    }
}
