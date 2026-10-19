package com.fanniuzhan.winddown.core;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Locale;

/** Wall-clock intervals. Weekday bits match the Windows app: Sunday = bit zero. */
public final class NightSchedule {
    public final boolean enabled;
    public final int startMinute;
    public final int endMinute;
    public final int daysMask;

    public NightSchedule(boolean enabled, int startMinute, int endMinute, int daysMask) {
        if (startMinute < 0 || startMinute >= 1440 || endMinute < 0 || endMinute >= 1440
                || startMinute == endMinute || daysMask < 1 || daysMask > 127) {
            throw new IllegalArgumentException("请选择不同的开始、结束时间和至少一个执行日期。");
        }
        this.enabled = enabled;
        this.startMinute = startMinute;
        this.endMinute = endMinute;
        this.daysMask = daysMask;
    }

    public static NightSchedule defaults() { return new NightSchedule(false, 22 * 60 + 30, 6 * 60 + 30, 127); }

    public Window activeWindow(Instant now, ZoneId zone) {
        if (!enabled) return null;
        LocalDate today = now.atZone(zone).toLocalDate();
        for (int day = -1; day <= 0; day++) {
            Window window = windowFor(today.plusDays(day), zone);
            if (window != null && !now.isBefore(window.start) && now.isBefore(window.end)) return window;
        }
        return null;
    }

    public Window nextWindow(Instant now, ZoneId zone) {
        LocalDate today = now.atZone(zone).toLocalDate();
        for (int day = 0; day <= 14; day++) {
            Window window = windowFor(today.plusDays(day), zone);
            if (window != null && window.start.isAfter(now)) return window;
        }
        throw new IllegalStateException("无法计算下次限制时间。");
    }

    public Instant nextTransition(Instant now, ZoneId zone) {
        Window active = activeWindow(now, zone);
        return active == null ? nextWindow(now, zone).start : active.end;
    }

    private Window windowFor(LocalDate date, ZoneId zone) {
        int dayBit = date.getDayOfWeek().getValue() % 7;
        if ((daysMask & (1 << dayBit)) == 0) return null;
        ZonedDateTime start = date.atTime(LocalTime.of(startMinute / 60, startMinute % 60))
                .atZone(zone).withEarlierOffsetAtOverlap();
        LocalDate endDate = endMinute < startMinute ? date.plusDays(1) : date;
        ZonedDateTime end = endDate.atTime(LocalTime.of(endMinute / 60, endMinute % 60))
                .atZone(zone).withLaterOffsetAtOverlap();
        if (!end.toInstant().isAfter(start.toInstant())) return null;
        return new Window(start.toInstant(), end.toInstant());
    }

    public static String formatMinute(int minute) {
        return String.format(Locale.ROOT, "%02d:%02d", minute / 60, minute % 60);
    }

    public static final class Window {
        public final Instant start;
        public final Instant end;
        public Window(Instant start, Instant end) { this.start = start; this.end = end; }
    }
}
