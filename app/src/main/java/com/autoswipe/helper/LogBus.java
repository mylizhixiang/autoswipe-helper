package com.autoswipe.helper;

import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;

/** 极简日志总线：服务写日志，界面实时显示，方便在手机上调试判定规则。 */
public final class LogBus {

    public interface Listener {
        void onLog(String line);
    }

    private static final int MAX_LINES = 2000;
    private static final ArrayDeque<String> BUFFER = new ArrayDeque<>();
    private static final CopyOnWriteArrayList<Listener> LISTENERS = new CopyOnWriteArrayList<>();
    private static final SimpleDateFormat FMT = new SimpleDateFormat("HH:mm:ss", Locale.US);

    private LogBus() {
    }

    public static void log(String msg) {
        String line = FMT.format(new Date()) + "  " + msg;
        synchronized (BUFFER) {
            BUFFER.addLast(line);
            while (BUFFER.size() > MAX_LINES) {
                BUFFER.removeFirst();
            }
        }
        for (Listener l : LISTENERS) {
            try {
                l.onLog(line);
            } catch (Throwable ignored) {
            }
        }
    }

    public static List<String> snapshot() {
        synchronized (BUFFER) {
            return new ArrayList<>(BUFFER);
        }
    }

    public static void clear() {
        synchronized (BUFFER) {
            BUFFER.clear();
        }
    }

    public static void addListener(Listener l) {
        if (l != null && !LISTENERS.contains(l)) {
            LISTENERS.add(l);
        }
    }

    public static void removeListener(Listener l) {
        LISTENERS.remove(l);
    }
}