package com.autoswipe.helper;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;

/**
 * 常驻前台服务：只为了给系统一个「这个应用还在前台干活」的信号，
 * 降低被国产 ROM 冻结后台进程的概率（实测 Honor 会冻结普通后台的无障碍服务）。
 * 只有一条低优先级常驻通知，可随时关闭。
 */
public class KeepAliveService extends Service {

    private static final String CHANNEL_ID = "autoswipe_helper_alive";
    private static final int NOTIFICATION_ID = 1001;

    private Thread watchdog;

    public static void start(Context c) {
        try {
            Intent i = new Intent(c, KeepAliveService.class);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                c.startForegroundService(i);
            } else {
                c.startService(i);
            }
        } catch (Throwable ignored) {
        }
    }

    public static void stop(Context c) {
        try {
            c.stopService(new Intent(c, KeepAliveService.class));
        } catch (Throwable ignored) {
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        try {
            createChannel();
            startForeground(NOTIFICATION_ID, buildNotification());
            LogBus.log("保活前台服务已启动（常驻通知已显示）");
        } catch (Throwable t) {
            LogBus.log("保活服务启动失败: " + t);
        }
        startWatchdog();
        return START_STICKY;
    }

    /**
     * 独立看门狗：如果连常驻通知都挡不住冻结，这里会把它记进日志，
     * 用来区分「前台服务保住了」和「还是被 ROM 冻了」。
     */
    private void startWatchdog() {
        if (watchdog != null) {
            return;
        }
        watchdog = new Thread(() -> {
            long last = System.currentTimeMillis();
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    Thread.sleep(1000L);
                } catch (InterruptedException e) {
                    return;
                }
                long now = System.currentTimeMillis();
                if (now - last > 5000L) {
                    LogBus.log("⚠ 保活服务也被冻结 " + (now - last)
                            + "ms（常驻通知没挡住，需要到系统设置里允许自启动/后台活动）");
                }
                last = now;
            }
        }, "autoswipe-keepalive-watchdog");
        watchdog.setDaemon(true);
        watchdog.start();
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        LogBus.log("任务被划掉，保活服务尝试自动重启");
        start(this);
        super.onTaskRemoved(rootIntent);
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        LogBus.log("保活服务已停止");
        super.onDestroy();
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return;
        }
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm == null) {
            return;
        }
        NotificationChannel ch = new NotificationChannel(CHANNEL_ID, "自动上滑保活",
                NotificationManager.IMPORTANCE_MIN);
        ch.setShowBadge(false);
        nm.createNotificationChannel(ch);
    }

    private Notification buildNotification() {
        Intent open = new Intent(this, MainActivity.class);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            flags |= PendingIntent.FLAG_IMMUTABLE;
        }
        PendingIntent pi = PendingIntent.getActivity(this, 0, open, flags);

        Notification.Builder b;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            b = new Notification.Builder(this, CHANNEL_ID);
        } else {
            b = new Notification.Builder(this);
        }
        return b.setContentTitle("自动上滑助手运行中")
                .setContentText("防止系统冻结后台服务，可随时关闭")
                .setSmallIcon(R.drawable.ic_launcher)
                .setContentIntent(pi)
                .setOngoing(true)
                .setPriority(Notification.PRIORITY_MIN)
                .build();
    }
}
