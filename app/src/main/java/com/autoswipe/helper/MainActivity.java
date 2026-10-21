package com.autoswipe.helper;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.List;

public class MainActivity extends Activity implements LogBus.Listener {

    private TextView statusView;
    private TextView logView;
    private ScrollView logScroll;

    private CheckBox cbMaster;
    private CheckBox cbSkipAd;
    private CheckBox cbAfterAd;
    private CheckBox cbAfterAdPlay;
    private CheckBox cbHintSwipe;
    private CheckBox cbLiveAd;
    private CheckBox cbKeepAlive;
    private CheckBox cbLiveStall;
    private CheckBox cbSuspectAd;
    private CheckBox cbSuspectFast;
    private CheckBox cbMotion;
    private CheckBox cbWeakHint;
    private CheckBox cbAutoDump;
    private CheckBox cbDebugPkg;
    private CheckBox cbDumpScreen;
    private CheckBox cbSnapshotIdle;
    private CheckBox cbClickNext;
    private CheckBox cbIdle;
    private CheckBox cbFixed;

    private EditText etIdleSec;
    private EditText etFixedSec;
    private EditText etCooldownSec;
    private EditText etTargetPkgs;
    private EditText etDumpSec;
    private EditText etSnapshotSec;
    private EditText etLiveStallSec;
    private EditText etSuspectSec;
    private EditText etSuspectFastSec;
    private EditText etMotionSec;

    private int dp(float v) {
        return Math.round(getResources().getDisplayMetrics().density * v);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(buildUi());
        LogBus.log("界面已打开");
        if (Prefs.get(this).getBoolean(Prefs.KEY_DISCLAIMER_OK, false)) {
            startBackground();
        } else {
            // 首次启动先让用户看完免责声明，同意之后再申请通知权限 / 起保活服务，
            // 免得两个弹窗叠在一起。
            showDisclaimer(true);
        }
    }

    /** 通知权限 + 保活前台服务。首次启动要等用户同意免责声明之后再执行。 */
    private void startBackground() {
        ensureNotificationPermission();
        if (Prefs.get(this).getBoolean(Prefs.KEY_KEEP_ALIVE, true)) {
            KeepAliveService.start(this);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        LogBus.addListener(this);
        loadPrefsToUi();
        refreshStatus();
    }

    @Override
    protected void onPause() {
        LogBus.removeListener(this);
        super.onPause();
    }

    @Override
    public void onLog(String line) {
        runOnUiThread(() -> appendLog(line));
    }

    private void appendLog(String line) {
        if (logView == null) {
            return;
        }
        logView.append(line + "\n");
        if (logScroll != null) {
            logScroll.post(() -> logScroll.fullScroll(View.FOCUS_DOWN));
        }
    }

    /** 首次启动必须确认的免责声明：本工具与任何平台无关，禁止用于刷量等作弊用途。 */
    private static final String DISCLAIMER =
            "1. 本工具只在你自己的手机上工作：读取屏幕上的文字，在检测到广告或播放卡住时模拟一次上滑手势。"
            + "它不修改任何 App、不联网、不上传任何数据。\n\n"
            + "2. 它需要系统「无障碍服务」权限才能工作，请只在自己信任的情况下开启。\n\n"
            + "3. 本工具与任何短剧 / 听书平台无关，也没有获得它们的授权或认可。这类平台的服务条款通常"
            + "不允许自动化操作，使用后可能出现账号被限流、封禁等情况，风险请自行承担。\n\n"
            + "4. 严禁把本工具用于刷播放量、刷广告、批量挂机等作弊或牟利用途。\n\n"
            + "5. 本工具按「原样」提供，不保证永久可用（平台一改版就可能失效），也不对由此产生的"
            + "任何损失负责。\n\n"
            + "点击「我已阅读并同意」表示你接受以上内容。";

    private void showDisclaimer(boolean firstRun) {
        AlertDialog.Builder b = new AlertDialog.Builder(this)
                .setTitle("使用须知与免责声明")
                .setMessage(DISCLAIMER)
                .setNegativeButton(firstRun ? "退出" : "关闭", (d, w) -> {
                    if (firstRun) {
                        finish();
                    }
                });
        if (firstRun) {
            b.setPositiveButton("我已阅读并同意", (d, w) -> {
                Prefs.get(this).edit().putBoolean(Prefs.KEY_DISCLAIMER_OK, true).apply();
                startBackground();
            });
        }
        AlertDialog dialog = b.create();
        dialog.setCancelable(!firstRun);
        dialog.setCanceledOnTouchOutside(!firstRun);
        dialog.show();
    }

    private View buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(16), dp(16), dp(24));

        TextView title = new TextView(this);
        title.setText("自动上滑助手");
        title.setTextSize(22);
        title.setGravity(Gravity.START);
        root.addView(title);

        statusView = new TextView(this);
        statusView.setTextSize(14);
        statusView.setPadding(0, dp(8), 0, dp(8));
        root.addView(statusView);

        TextView guide = new TextView(this);
        guide.setTextSize(13);
        guide.setText("用法：\n"
                + "1. 点下面按钮打开系统「无障碍」设置，找到「自动上滑助手」并开启。\n"
                + "2. 点「加入电池优化白名单」，并在弹出的系统页面里选「无限制 / 允许」。\n"
                + "3. 荣耀 / 华为手机还要进 设置 → 应用 → 应用启动管理，把「自动上滑助手」"
                + "改成「手动管理」，允许自启动、关联启动、后台活动。\n"
                + "4. 回到短剧 App 正常看剧，什么都不用管。下面那张常驻通知不要划掉。\n"
                + "5. 如果还是不自动滑，看最下面的日志：日志会显示识别到的集数、广告、进度、"
                + "以及本进程有没有被系统冻结，按提示调开关和秒数。");
        guide.setPadding(0, dp(4), 0, dp(12));
        root.addView(guide);

        Button openSettings = new Button(this);
        openSettings.setText("打开无障碍设置");
        openSettings.setOnClickListener(v -> {
            try {
                startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
            } catch (Throwable t) {
                Toast.makeText(this, "打开失败，请手动到 设置-无障碍 里找", Toast.LENGTH_LONG).show();
            }
        });
        root.addView(openSettings);

        Button battery = new Button(this);
        battery.setText("关闭本应用的省电限制（重要）");
        battery.setOnClickListener(v -> {
            try {
                Intent i = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
                i.setData(android.net.Uri.parse("package:" + getPackageName()));
                startActivity(i);
            } catch (Throwable t) {
                Toast.makeText(this,
                        "请手动到 设置-应用-自动上滑助手-耗电管理 里选「无限制」",
                        Toast.LENGTH_LONG).show();
            }
        });
        root.addView(battery);

        Button whitelist = new Button(this);
        whitelist.setText("加入电池优化白名单（防冻结）");
        whitelist.setOnClickListener(v -> requestIgnoreBatteryOpt());
        root.addView(whitelist);

        root.addView(sectionTitle("触发规则"));

        cbMaster = check("总开关（开启自动上滑）");
        root.addView(cbMaster);

        cbSkipAd = check("自动点「跳过」广告");
        root.addView(cbSkipAd);

        cbHintSwipe = check("出现「上滑继续观看短剧」提示时自动前进");
        root.addView(cbHintSwipe);

        cbLiveAd = check("检测到直播间广告（出现「点击进入直播间」等）时自动前进");
        root.addView(cbLiveAd);

        cbKeepAlive = check("保持后台运行（常驻通知，防系统冻结；强烈建议开）");
        root.addView(cbKeepAlive);

        cbLiveStall = check("直播广告兜底：进过直播间页面后，文字长时间不动就前进");
        root.addView(cbLiveStall);
        etLiveStallSec = numberField("10");
        root.addView(row("  直播页无变化判定秒数", etLiveStallSec));

        cbSuspectAd = check("疑似广告页兜底：读不到剧集信息 + 文字长时间不动就前进（直播广告用这条）");
        root.addView(cbSuspectAd);
        etSuspectSec = numberField("15");
        root.addView(row("  疑似广告页判定秒数", etSuspectSec));

        cbSuspectFast = check("全屏直播广告快滑：整页读不到任何可见文字时，几秒就前进"
                + "（那种盖满全屏、一个字都读不到、也没有「广告」二字的直播间广告用这条，默认开）");
        root.addView(cbSuspectFast);
        etSuspectFastSec = numberField("5");
        root.addView(row("  全屏广告页判定秒数", etSuspectFastSec));

        cbMotion = check("画面静止检测：连续 N 秒画面完全不动就上滑（一集播完卡住时用，不依赖文案）");
        root.addView(cbMotion);
        etMotionSec = numberField("15");
        root.addView(row("  画面静止判定秒数", etMotionSec));

        cbWeakHint = check("弱提示兜底：文案位置在屏幕里但系统标成「不可见」时也按提示前进"
                + "（短剧 App 的直播广告页用这条，默认开）");
        root.addView(cbWeakHint);

        cbAutoDump = check("排障：疑似广告/直播页自动抓屏进日志（文字长时间不动时抓一份现场）");
        root.addView(cbAutoDump);

        cbAfterAd = check("广告结束后自动上滑（短剧类）");
        root.addView(cbAfterAd);

        cbAfterAdPlay = check("广告结束后自动点「播放/继续播放」（听书类，如番茄畅听）");
        root.addView(cbAfterAdPlay);

        cbClickNext = check("出现「下一集」按钮时自动点它");
        root.addView(cbClickNext);

        cbIdle = check("播放进度停滞时自动上滑");
        root.addView(cbIdle);
        etIdleSec = numberField("8");
        root.addView(row("  停滞判定秒数", etIdleSec));

        cbDebugPkg = check("调试：记录前台应用包名（用来确认目标 App 包名）");
        root.addView(cbDebugPkg);

        cbDumpScreen = check("排障：定时把当前屏幕文字倒进日志（抓真实文案用）");
        root.addView(cbDumpScreen);
        etDumpSec = numberField("5");
        root.addView(row("  抓屏间隔秒数", etDumpSec));

        cbSnapshotIdle = check("兜底：屏幕长时间无变化就上滑（读不到任何提示时用，默认关）");
        root.addView(cbSnapshotIdle);
        etSnapshotSec = numberField("15");
        root.addView(row("  无变化判定秒数", etSnapshotSec));

        cbFixed = check("定时自动上滑（备用方案）");
        root.addView(cbFixed);
        etFixedSec = numberField("45");
        root.addView(row("  定时间隔秒数", etFixedSec));

        root.addView(sectionTitle("其它参数"));

        etCooldownSec = numberField("6");
        root.addView(row("两次动作最小间隔（秒）", etCooldownSec));

        etTargetPkgs = new EditText(this);
        etTargetPkgs.setTextSize(13);
        etTargetPkgs.setSingleLine(false);
        etTargetPkgs.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(row("目标应用包名（逗号分隔）", null));
        root.addView(etTargetPkgs);

        LinearLayout btns = new LinearLayout(this);
        btns.setOrientation(LinearLayout.HORIZONTAL);
        btns.setPadding(0, dp(10), 0, dp(10));

        Button save = new Button(this);
        save.setText("保存设置");
        save.setOnClickListener(v -> savePrefs());
        btns.addView(save);

        Button test = new Button(this);
        test.setText("依次试 7 种动作");
        test.setOnClickListener(v -> {
            AutoSwipeService s = AutoSwipeService.instance();
            if (s == null) {
                Toast.makeText(this, "服务未运行：请先在系统无障碍里开启本应用", Toast.LENGTH_LONG).show();
                return;
            }
            s.startStrategySweep(6);
            Toast.makeText(this, "6 秒后开始，请立刻切到短剧 App 并停在广告页、不要碰屏幕", Toast.LENGTH_LONG).show();
        });
        btns.addView(test);

        Button copy = new Button(this);
        copy.setText("复制日志");
        copy.setOnClickListener(v -> {
            StringBuilder sb = new StringBuilder();
            for (String line : LogBus.snapshot()) {
                sb.append(line).append('\n');
            }
            ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            if (cm != null) {
                cm.setPrimaryClip(ClipData.newPlainText("autoswipe-log", sb.toString()));
                Toast.makeText(this, "日志已复制到剪贴板", Toast.LENGTH_LONG).show();
            }
        });
        btns.addView(copy);

        Button clear = new Button(this);
        clear.setText("清空日志");
        clear.setOnClickListener(v -> {
            LogBus.clear();
            logView.setText("");
        });
        btns.addView(clear);
        root.addView(btns);

        Button dumpNow = new Button(this);
        dumpNow.setText("立即抓一次屏（6 秒后，抓直播广告页用）");
        dumpNow.setOnClickListener(v -> {
            AutoSwipeService s = AutoSwipeService.instance();
            if (s == null) {
                Toast.makeText(this, "服务未运行：请先在系统无障碍里开启本应用", Toast.LENGTH_LONG).show();
                return;
            }
            s.dumpOnceAfter(6);
            Toast.makeText(this, "6 秒后抓一次，请切到广告页并保持不动", Toast.LENGTH_LONG).show();
        });
        root.addView(dumpNow);

        Button about = new Button(this);
        about.setText("使用须知 / 免责声明");
        about.setOnClickListener(v -> showDisclaimer(false));
        root.addView(about);

        root.addView(sectionTitle("运行日志"));

        logView = new TextView(this);
        logView.setTextSize(11);
        logView.setPadding(dp(6), dp(6), dp(6), dp(6));

        logScroll = new ScrollView(this);
        logScroll.setBackgroundColor(0x11000000);
        logScroll.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(240)));
        logScroll.addView(logView);
        root.addView(logScroll);

        for (String line : LogBus.snapshot()) {
            logView.append(line + "\n");
        }

        ScrollView outer = new ScrollView(this);
        outer.addView(root, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return outer;
    }

    private TextView sectionTitle(String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(16);
        tv.setPadding(0, dp(14), 0, dp(4));
        return tv;
    }

    private CheckBox check(String label) {
        CheckBox cb = new CheckBox(this);
        cb.setText(label);
        cb.setTextSize(14);
        cb.setPadding(0, dp(6), 0, dp(6));
        return cb;
    }

    private EditText numberField(String def) {
        EditText et = new EditText(this);
        et.setText(def);
        et.setTextSize(14);
        et.setWidth(dp(90));
        et.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        et.setGravity(Gravity.CENTER);
        return et;
    }

    private LinearLayout row(String label, View field) {
        LinearLayout ll = new LinearLayout(this);
        ll.setOrientation(LinearLayout.HORIZONTAL);
        ll.setGravity(Gravity.CENTER_VERTICAL);
        ll.setPadding(0, dp(4), 0, dp(4));
        TextView tv = new TextView(this);
        tv.setText(label);
        tv.setTextSize(14);
        tv.setLayoutParams(new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        ll.addView(tv);
        if (field != null) {
            ll.addView(field);
        }
        return ll;
    }

    private void refreshStatus() {
        boolean allowed = isServiceEnabled();
        boolean alive = AutoSwipeService.instance() != null;
        String head;
        int color;
        if (!allowed) {
            head = "无障碍服务：未开启 ✗（必须开启才能工作）";
            color = 0xFFC62828;
        } else if (!alive) {
            head = "无障碍服务：已授权但未连上 ✗（把无障碍开关关掉再打开一次）";
            color = 0xFFEF6C00;
        } else {
            head = "无障碍服务：已连接 ✓";
            color = 0xFF2E7D32;
        }
        String tail = "\n后台保活：" + (Prefs.get(this).getBoolean(Prefs.KEY_KEEP_ALIVE, true) ? "开" : "关")
                + "　电池优化白名单：" + (isIgnoringBatteryOpt() ? "已加入 ✓" : "未加入 ✗");
        statusView.setText(head + tail);
        statusView.setTextColor(color);
    }

    /** 申请把本应用加入电池优化白名单：这是国产 ROM 冻结后台的头号开关。 */
    private void requestIgnoreBatteryOpt() {
        try {
            Intent i = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
            i.setData(android.net.Uri.parse("package:" + getPackageName()));
            startActivity(i);
            Toast.makeText(this, "在系统弹窗里点「允许」", Toast.LENGTH_LONG).show();
        } catch (Throwable t) {
            try {
                startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
                Toast.makeText(this, "请在列表里找到本应用并选「不优化」", Toast.LENGTH_LONG).show();
            } catch (Throwable t2) {
                Toast.makeText(this, "请手动到 设置-应用-耗电管理 里选「无限制」",
                        Toast.LENGTH_LONG).show();
            }
        }
    }

    private boolean isIgnoringBatteryOpt() {
        try {
            android.os.PowerManager pm =
                    (android.os.PowerManager) getSystemService(POWER_SERVICE);
            return pm != null && pm.isIgnoringBatteryOptimizations(getPackageName());
        } catch (Throwable t) {
            return false;
        }
    }

    /** Android 13 起通知要用户授权，否则保活服务的常驻通知不显示。 */
    private void ensureNotificationPermission() {
        try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU
                    && checkSelfPermission("android.permission.POST_NOTIFICATIONS")
                    != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, 1001);
            }
        } catch (Throwable ignored) {
        }
    }

    private boolean isServiceEnabled() {
        try {
            String flat = Settings.Secure.getString(getContentResolver(),
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            if (flat == null || flat.isEmpty()) {
                return false;
            }
            String full = getPackageName() + "/" + AutoSwipeService.class.getName();
            String shortName = getPackageName() + "/." + AutoSwipeService.class.getSimpleName();
            for (String part : flat.split(":")) {
                if (part.equalsIgnoreCase(full) || part.equalsIgnoreCase(shortName)) {
                    return true;
                }
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    private void loadPrefsToUi() {
        SharedPreferences p = Prefs.get(this);
        cbMaster.setChecked(p.getBoolean(Prefs.KEY_MASTER, true));
        cbSkipAd.setChecked(p.getBoolean(Prefs.KEY_SKIP_AD, true));
        cbAfterAd.setChecked(p.getBoolean(Prefs.KEY_AFTER_AD, true));
        cbAfterAdPlay.setChecked(p.getBoolean(Prefs.KEY_AFTER_AD_PLAY, true));
        cbHintSwipe.setChecked(p.getBoolean(Prefs.KEY_HINT_SWIPE, true));
        cbLiveAd.setChecked(p.getBoolean(Prefs.KEY_LIVE_AD, true));
        cbKeepAlive.setChecked(p.getBoolean(Prefs.KEY_KEEP_ALIVE, true));
        cbLiveStall.setChecked(p.getBoolean(Prefs.KEY_LIVE_STALL, true));
        etLiveStallSec.setText(String.valueOf(p.getInt(Prefs.KEY_LIVE_STALL_SEC, 10)));
        cbSuspectAd.setChecked(p.getBoolean(Prefs.KEY_SUSPECT_AD, true));
        etSuspectSec.setText(String.valueOf(p.getInt(Prefs.KEY_SUSPECT_SEC, 15)));
        cbSuspectFast.setChecked(p.getBoolean(Prefs.KEY_SUSPECT_FAST, true));
        etSuspectFastSec.setText(String.valueOf(p.getInt(Prefs.KEY_SUSPECT_FAST_SEC, 5)));
        cbMotion.setChecked(p.getBoolean(Prefs.KEY_MOTION, true));
        etMotionSec.setText(String.valueOf(p.getInt(Prefs.KEY_MOTION_SEC, 15)));
        cbWeakHint.setChecked(p.getBoolean(Prefs.KEY_WEAK_HINT, true));
        cbAutoDump.setChecked(p.getBoolean(Prefs.KEY_AUTO_DUMP, true));
        cbDebugPkg.setChecked(p.getBoolean(Prefs.KEY_DEBUG_PKG, false));
        cbDumpScreen.setChecked(p.getBoolean(Prefs.KEY_DUMP_SCREEN, false));
        etDumpSec.setText(String.valueOf(p.getInt(Prefs.KEY_DUMP_SEC, 5)));
        cbClickNext.setChecked(p.getBoolean(Prefs.KEY_CLICK_NEXT, true));
        cbIdle.setChecked(p.getBoolean(Prefs.KEY_IDLE, true));
        cbFixed.setChecked(p.getBoolean(Prefs.KEY_FIXED, false));
        cbSnapshotIdle.setChecked(p.getBoolean(Prefs.KEY_SNAPSHOT_IDLE, false));
        etSnapshotSec.setText(String.valueOf(p.getInt(Prefs.KEY_SNAPSHOT_SEC, 15)));
        etIdleSec.setText(String.valueOf(p.getInt(Prefs.KEY_IDLE_SEC, 8)));
        etFixedSec.setText(String.valueOf(p.getInt(Prefs.KEY_FIXED_SEC, 45)));
        etCooldownSec.setText(String.valueOf(p.getInt(Prefs.KEY_COOLDOWN_SEC, 6)));
        etTargetPkgs.setText(p.getString(Prefs.KEY_TARGET_PKGS, Prefs.DEFAULT_TARGET_PKGS));
    }

    private void savePrefs() {
        SharedPreferences.Editor e = Prefs.get(this).edit();
        e.putBoolean(Prefs.KEY_MASTER, cbMaster.isChecked());
        e.putBoolean(Prefs.KEY_SKIP_AD, cbSkipAd.isChecked());
        e.putBoolean(Prefs.KEY_AFTER_AD, cbAfterAd.isChecked());
        e.putBoolean(Prefs.KEY_AFTER_AD_PLAY, cbAfterAdPlay.isChecked());
        e.putBoolean(Prefs.KEY_HINT_SWIPE, cbHintSwipe.isChecked());
        e.putBoolean(Prefs.KEY_LIVE_AD, cbLiveAd.isChecked());
        e.putBoolean(Prefs.KEY_KEEP_ALIVE, cbKeepAlive.isChecked());
        e.putBoolean(Prefs.KEY_LIVE_STALL, cbLiveStall.isChecked());
        e.putInt(Prefs.KEY_LIVE_STALL_SEC, parseInt(etLiveStallSec.getText().toString(), 10));
        e.putBoolean(Prefs.KEY_SUSPECT_AD, cbSuspectAd.isChecked());
        e.putInt(Prefs.KEY_SUSPECT_SEC, parseInt(etSuspectSec.getText().toString(), 15));
        e.putBoolean(Prefs.KEY_SUSPECT_FAST, cbSuspectFast.isChecked());
        e.putInt(Prefs.KEY_SUSPECT_FAST_SEC, parseInt(etSuspectFastSec.getText().toString(), 5));
        e.putBoolean(Prefs.KEY_MOTION, cbMotion.isChecked());
        e.putInt(Prefs.KEY_MOTION_SEC, parseInt(etMotionSec.getText().toString(), 15));
        e.putBoolean(Prefs.KEY_WEAK_HINT, cbWeakHint.isChecked());
        e.putBoolean(Prefs.KEY_AUTO_DUMP, cbAutoDump.isChecked());
        e.putBoolean(Prefs.KEY_DEBUG_PKG, cbDebugPkg.isChecked());
        e.putBoolean(Prefs.KEY_DUMP_SCREEN, cbDumpScreen.isChecked());
        e.putInt(Prefs.KEY_DUMP_SEC, parseInt(etDumpSec.getText().toString(), 5));
        e.putBoolean(Prefs.KEY_CLICK_NEXT, cbClickNext.isChecked());
        e.putBoolean(Prefs.KEY_IDLE, cbIdle.isChecked());
        e.putBoolean(Prefs.KEY_FIXED, cbFixed.isChecked());
        e.putBoolean(Prefs.KEY_SNAPSHOT_IDLE, cbSnapshotIdle.isChecked());
        e.putInt(Prefs.KEY_SNAPSHOT_SEC, parseInt(etSnapshotSec.getText().toString(), 15));
        e.putInt(Prefs.KEY_IDLE_SEC, parseInt(etIdleSec.getText().toString(), 8));
        e.putInt(Prefs.KEY_FIXED_SEC, parseInt(etFixedSec.getText().toString(), 45));
        e.putInt(Prefs.KEY_COOLDOWN_SEC, parseInt(etCooldownSec.getText().toString(), 6));
        String pkgs = etTargetPkgs.getText().toString().trim();
        e.putString(Prefs.KEY_TARGET_PKGS,
                pkgs.isEmpty() ? Prefs.DEFAULT_TARGET_PKGS : pkgs);
        e.apply();
        if (cbKeepAlive.isChecked()) {
            KeepAliveService.start(this);
        } else {
            KeepAliveService.stop(this);
        }
        Toast.makeText(this, "已保存", Toast.LENGTH_SHORT).show();
        LogBus.log("设置已保存");
    }

    private static int parseInt(String s, int def) {
        try {
            return Integer.parseInt(s.trim());
        } catch (Throwable t) {
            return def;
        }
    }
}
