package com.fanniuzhan.winddown.core;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/** Dependency-free regression suite. It never controls a phone or grants permissions. */
public final class NightScheduleTest {
    private static int passed;
    private static int failed;
    private static final ZoneId CHINA = ZoneId.of("Asia/Shanghai");
    private static final Set<String> PHONES = new HashSet<>(Arrays.asList("com.hihonor.incallui", "com.hihonor.contacts"));
    private static final Set<String> CLOCKS = new HashSet<>(Arrays.asList("com.hihonor.deskclock"));

    public static void main(String[] args) {
        test("first install is disabled with 22:30 to 06:30 defaults", () -> {
            NightSchedule plan = NightSchedule.defaults();
            check(!plan.enabled && plan.startMinute == 1350 && plan.endMinute == 390 && plan.daysMask == 127);
        });
        test("start boundary is inclusive", () -> check(blocked("2026-10-19T22:30:00+08:00")));
        test("one millisecond before start is allowed", () -> check(!blocked("2026-10-19T22:29:59.999+08:00")));
        test("end boundary is exclusive", () -> check(!blocked("2026-10-20T06:30:00+08:00")));
        test("one millisecond before end remains blocked", () -> check(blocked("2026-10-20T06:29:59.999+08:00")));
        test("midnight belongs to the preceding evening", () -> check(blocked("2026-10-20T00:00:00+08:00")));
        test("daytime is usable", () -> check(!blocked("2026-10-19T12:00:00+08:00")));
        test("disabled plan never restricts at night", () -> check(NightSchedule.defaults().activeWindow(at("2026-10-19T23:00:00+08:00"), CHINA) == null));
        test("Monday night extends into Tuesday morning", () -> {
            NightSchedule monday = new NightSchedule(true, 1350, 390, 2);
            check(monday.activeWindow(at("2026-10-20T05:00:00+08:00"), CHINA) != null);
        });
        test("Monday morning does not inherit Monday evening's selection", () -> {
            NightSchedule monday = new NightSchedule(true, 1350, 390, 2);
            check(monday.activeWindow(at("2026-10-19T05:00:00+08:00"), CHINA) == null);
        });
        test("Friday night extends into Saturday morning", () -> {
            NightSchedule weekdays = new NightSchedule(true, 1350, 390, 62);
            check(weekdays.activeWindow(at("2026-10-24T05:00:00+08:00"), CHINA) != null);
        });
        test("weekend nights remain free for a weekday-only plan", () -> {
            NightSchedule weekdays = new NightSchedule(true, 1350, 390, 62);
            check(weekdays.activeWindow(at("2026-10-24T23:00:00+08:00"), CHINA) == null);
            check(weekdays.activeWindow(at("2026-10-25T05:00:00+08:00"), CHINA) == null);
        });
        test("Sunday bit zero rolls over to Monday", () -> {
            NightSchedule sunday = new NightSchedule(true, 1350, 390, 1);
            check(sunday.activeWindow(at("2026-10-19T05:00:00+08:00"), CHINA) != null);
        });
        test("next transition while blocked is 06:30", () -> check(plan().nextTransition(at("2026-10-19T23:00:00+08:00"), CHINA).equals(at("2026-10-20T06:30:00+08:00"))));
        test("next transition during daytime is 22:30", () -> check(plan().nextTransition(at("2026-10-19T12:00:00+08:00"), CHINA).equals(at("2026-10-19T22:30:00+08:00"))));
        test("end time automatically advances to the next evening", () -> check(plan().nextTransition(at("2026-10-20T06:30:00+08:00"), CHINA).equals(at("2026-10-20T22:30:00+08:00"))));
        test("same-day custom interval works", () -> {
            NightSchedule work = new NightSchedule(true, 9 * 60, 10 * 60, 127);
            check(work.activeWindow(at("2026-10-19T09:30:00+08:00"), CHINA) != null);
            check(work.activeWindow(at("2026-10-19T23:30:00+08:00"), CHINA) == null);
        });
        test("zero-length interval is rejected rather than becoming all-day lockout", () -> rejects(() -> new NightSchedule(true, 390, 390, 127)));
        test("empty weekdays are rejected", () -> rejects(() -> new NightSchedule(true, 1350, 390, 0)));
        test("invalid minutes and weekday masks are rejected", () -> {
            rejects(() -> new NightSchedule(true, -1, 390, 127));
            rejects(() -> new NightSchedule(true, 1440, 390, 127));
            rejects(() -> new NightSchedule(true, 1350, 1440, 127));
            rejects(() -> new NightSchedule(true, 1350, 390, 128));
        });
        test("time formatting is 24-hour and zero-padded", () -> check("06:30".equals(NightSchedule.formatMinute(390))));
        test("next selected evening can be a week away", () -> {
            NightSchedule monday = new NightSchedule(true, 1350, 390, 2);
            check(monday.nextWindow(at("2026-10-19T23:00:00+08:00"), CHINA).start.equals(at("2026-10-26T22:30:00+08:00")));
        });
        test("system clock jumping past the end immediately restores access", () -> {
            check(blocked("2026-10-19T23:00:00+08:00"));
            check(!blocked("2026-10-20T07:00:00+08:00"));
        });
        test("timezone changes are evaluated from current local time", () -> {
            Instant now = at("2026-10-20T06:00:00+08:00");
            check(plan().activeWindow(now, CHINA) != null);
            check(plan().activeWindow(now, ZoneId.of("Asia/Tokyo")) == null);
        });
        test("spring DST night ends at the local 06:30", () -> {
            NightSchedule.Window active = plan().activeWindow(at("2026-03-08T01:00:00-05:00"), ZoneId.of("America/New_York"));
            check(active.end.equals(at("2026-03-08T06:30:00-04:00")));
            check(Duration.between(active.start, active.end).toHours() == 7);
        });
        test("autumn DST night covers both repeated hours", () -> {
            ZoneId zone = ZoneId.of("America/New_York");
            check(plan().activeWindow(at("2026-11-01T01:30:00-04:00"), zone) != null);
            check(plan().activeWindow(at("2026-11-01T01:30:00-05:00"), zone) != null);
            check(plan().activeWindow(at("2026-11-01T06:30:00-05:00"), zone) == null);
        });
        test("ordinary application is covered during restriction", () -> check(cover(true, false, false, "com.example.social")));
        test("own settings screen has no night-time exemption", () -> check(cover(true, false, false, "com.fanniuzhan.winddown")));
        test("incoming call interface is allowed", () -> check(!cover(true, false, false, "com.hihonor.incallui")));
        test("phone dialer is allowed", () -> check(!cover(true, false, false, "com.hihonor.contacts")));
        test("clock and alarm interface is allowed", () -> check(!cover(true, false, false, "com.hihonor.deskclock")));
        test("system lock screen remains under Android control", () -> check(!cover(true, true, false, "com.android.systemui")));
        test("ringing temporarily yields for call answering", () -> check(!cover(true, false, true, "com.android.systemui")));
        test("ordinary app is blocked again after ringing stops", () -> check(cover(true, false, false, "com.example.social")));
        test("unknown app cannot accidentally receive an exemption", () -> check(cover(true, false, false, null)));
        test("similar package names cannot spoof a call or clock exemption", () -> {
            check(cover(true, false, false, "com.hihonor.incallui.fake"));
            check(cover(true, false, false, "com.hihonor.deskclock.fake"));
        });
        test("all applications are usable outside the interval", () -> check(!cover(false, false, false, "com.example.social")));
        test("system settings are not an app-provided temporary exit", () -> check(cover(true, false, false, "com.android.settings")));
        System.out.println("\n" + passed + " passed, " + failed + " failed.");
        if (failed != 0) throw new AssertionError("Regression failures: " + failed);
    }

    private static boolean cover(boolean restricted, boolean locked, boolean ringing, String owner) {
        return GuardPolicy.shouldCover(restricted, locked, ringing, owner, PHONES, CLOCKS);
    }
    private static NightSchedule plan() { return new NightSchedule(true, 1350, 390, 127); }
    private static Instant at(String value) { return java.time.OffsetDateTime.parse(value).toInstant(); }
    private static boolean blocked(String value) { return plan().activeWindow(at(value), CHINA) != null; }
    private static void check(boolean condition) { if (!condition) throw new AssertionError("Assertion failed"); }
    private static void rejects(Runnable body) {
        try { body.run(); } catch (IllegalArgumentException expected) { return; }
        throw new AssertionError("Expected invalid settings to be rejected");
    }
    private static void test(String name, Runnable body) {
        try { body.run(); passed++; System.out.println("PASS " + name); }
        catch (RuntimeException | AssertionError error) { failed++; System.out.println("FAIL " + name + ": " + error); }
    }
}
