package com.fanniuzhan.winddown;

import android.app.Activity;
import android.app.Instrumentation;
import android.app.KeyguardManager;
import android.app.UiAutomation;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.os.PowerManager;
import android.view.View;
import android.view.WindowManager;
import com.fanniuzhan.winddown.core.NightSchedule;
import java.io.ByteArrayOutputStream;
import java.io.FileInputStream;
import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.function.BooleanSupplier;

/** Dedicated-emulator checks. Both modes restore every preference they may change before finishing. */
final class ScreenRecoveryChecks {
    private static int passed;

    static void run(Instrumentation test, boolean formal) {
        passed = 0;
        Bundle result = new Bundle();
        Context context = test.getTargetContext();
        SharedPreferences preferences = context.getSharedPreferences("night_guard", Context.MODE_PRIVATE);
        Map<String, ?> original = preferences.getAll();
        boolean stateChanged = false;
        boolean success = false;
        try {
            if (preferences.getBoolean("enabled", false) || preferences.getBoolean("compatibility", false)
                    || preferences.getLong("trial_until", 0) != 0)
                throw new IllegalStateException("Requires an idle dedicated emulator with the formal schedule disabled");
            UiAutomation automation = test.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES);
            String serviceName = context.getPackageName() + "/.NightGuardService";
            String enabled = shell(automation, "settings get secure enabled_accessibility_services").trim();
            if (!enabled.matches("[A-Za-z0-9._/:]+") || !enabled.contains(context.getPackageName()))
                throw new IllegalStateException("Test accessibility must already be explicitly enabled");
            // Instrumentation restarts the target process; rebind only its already-authorized service.
            String others = enabled.replace(serviceName, "").replace(context.getPackageName() + "/"
                    + context.getPackageName() + ".NightGuardService", "").replaceAll(":+", ":")
                    .replaceAll("^:|:$", "");
            shell(automation, "settings put secure enabled_accessibility_services " + (others.isEmpty() ? "null" : others));
            shell(automation, "settings put secure enabled_accessibility_services " + enabled);
            Thread.sleep(1500L);
            await(() -> service() != null, "Authorized service reconnects");
            shell(automation, "input keyevent KEYCODE_WAKEUP");
            shell(automation, "wm dismiss-keyguard");
            shell(automation, "input keyevent KEYCODE_HOME");
            SettingsStore store = new SettingsStore(context);
            stateChanged = true;
            if (formal) {
                ZonedDateTime now = ZonedDateTime.now();
                int minute = now.getHour() * 60 + now.getMinute();
                store.acceptDisclosure();
                store.confirmCompatibility(true);
                store.save(new NightSchedule(true, (minute + 1439) % 1440, (minute + 10) % 1440, 127));
                if (!preferences.edit().putLong("trial_until", 0).commit())
                    throw new IllegalStateException("Cannot clear test trial state");
            } else store.beginTrial(System.currentTimeMillis());
            NightGuardService.refreshIfRunning();
            await(() -> attached(test), "Initial " + (formal ? "formal" : "trial") + " overlay is visible");
            if (formal) await(() -> store.schedule().enabled && store.trialUntil() == 0
                    && store.schedule().activeWindow(Instant.now(), ZoneId.systemDefault()) != null,
                    "Formal plan is active with no trial");

            // Reproduce a window removed by the system while the service's Java reference survives.
            test.runOnMainSync(() -> {
                NightGuardService service = service();
                View old = cover(service);
                service.getSystemService(WindowManager.class).removeViewImmediate(old);
            });
            await(() -> attached(test), "System-detached overlay is recreated");
            test.runOnMainSync(() -> cover(service()).setVisibility(View.INVISIBLE));
            NightGuardService.refreshIfRunning();
            await(() -> attached(test), "Invisible overlay is recreated");

            PowerManager power = context.getSystemService(PowerManager.class);
            int cycles = formal ? 3 : 2;
            for (int cycle = 1; cycle <= cycles; cycle++) {
                shell(automation, "input keyevent KEYCODE_SLEEP");
                await(() -> !power.isInteractive() && !attached(test), "Screen off yields to system lock (cycle " + cycle + ")");
                if (formal) Thread.sleep(cycle == 3 ? 70_000L : cycle * 2000L);
                shell(automation, "input keyevent KEYCODE_WAKEUP");
                shell(automation, "wm dismiss-keyguard");
                shell(automation, "input keyevent KEYCODE_HOME");
                await(() -> power.isInteractive() && attached(test), "Unlock restores overlay (cycle " + cycle + ")");
                if (formal) {
                    // Ordinary app navigation must stay restricted without reopening MainActivity.
                    shell(automation, "am start -a android.settings.SETTINGS");
                    Thread.sleep(1500L);
                    await(() -> attached(test), "Ordinary Settings app stays covered (cycle " + cycle + ")");
                }
            }
            test.runOnMainSync(() -> service().onInterrupt());
            await(() -> attached(test), "Service interrupt does not permanently remove an active restriction");
            if (formal) {
                shell(automation, "screencap -p /sdcard/winddown-formal-restored.png");
                ZonedDateTime now = ZonedDateTime.now();
                int minute = now.getHour() * 60 + now.getMinute();
                store.save(new NightSchedule(true, (minute + 1439) % 1440, (minute + 1) % 1440, 127));
                NightGuardService.refreshIfRunning();
                await(() -> attached(test), "Formal plan stays covered before its real end time");
                Instant end = store.schedule().activeWindow(Instant.now(), ZoneId.systemDefault()).end;
                shell(automation, "input keyevent KEYCODE_SLEEP");
                while (Instant.now().isBefore(end.plusSeconds(2))) Thread.sleep(250L);
                shell(automation, "input keyevent KEYCODE_WAKEUP");
                shell(automation, "wm dismiss-keyguard");
                shell(automation, "input keyevent KEYCODE_HOME");
                await(() -> power.isInteractive() && !attached(test), "Expired formal window is not resumed after unlock");
                if (!preferences.getBoolean("enabled", false) || store.trialUntil() != 0)
                    throw new AssertionError("Formal test silently switched to a trial or disabled the schedule");
            } else if (preferences.getBoolean("enabled", false)) throw new AssertionError("Formal schedule was enabled");
            result.putString("stream", "PASS: " + passed + (formal ? " formal-plan" : " trial") + " screen recovery checks.\n");
            success = true;
        } catch (Throwable error) {
            result.putString("stream", "FAIL after " + passed + " screen recovery checks: " + error
                    + "; interactive=" + context.getSystemService(PowerManager.class).isInteractive()
                    + "; keyguard=" + context.getSystemService(KeyguardManager.class).isKeyguardLocked()
                    + "; error=" + new SettingsStore(context).lastError() + "\n");
        } finally {
            if (stateChanged) {
                SharedPreferences.Editor restore = preferences.edit();
                for (String key : new String[] { "trial_until", "trial_completed", "compatibility", "enabled",
                        "start", "end", "days", "disclosure", "last_error" }) {
                    Object value = original.get(key);
                    if (value instanceof Boolean) restore.putBoolean(key, (Boolean) value);
                    else if (value instanceof Long) restore.putLong(key, (Long) value);
                    else if (value instanceof Integer) restore.putInt(key, (Integer) value);
                    else if (value instanceof String) restore.putString(key, (String) value);
                    else restore.remove(key);
                }
                if (!restore.commit()) {
                    success = false;
                    result.putString("stream", "FAIL: could not restore emulator state\n");
                }
                BoundaryScheduler.update(context);
                NightGuardService.refreshIfRunning();
            }
        }
        test.finish(success ? Activity.RESULT_OK : Activity.RESULT_CANCELED, result);
    }

    private static void await(BooleanSupplier condition, String message) throws InterruptedException {
        long until = android.os.SystemClock.elapsedRealtime() + 5000;
        while (!condition.getAsBoolean()) {
            if (android.os.SystemClock.elapsedRealtime() >= until) throw new AssertionError(message);
            Thread.sleep(100);
        }
        passed++;
    }

    private static NightGuardService service() {
        try {
            Field field = NightGuardService.class.getDeclaredField("current"); field.setAccessible(true);
            return (NightGuardService) ((WeakReference<?>) field.get(null)).get();
        } catch (ReflectiveOperationException error) { throw new IllegalStateException(error); }
    }

    private static View cover(NightGuardService service) {
        try {
            Field field = NightGuardService.class.getDeclaredField("cover"); field.setAccessible(true);
            return (View) field.get(service);
        } catch (ReflectiveOperationException error) { throw new IllegalStateException(error); }
    }

    private static boolean attached(Instrumentation test) {
        boolean[] visible = { false };
        test.runOnMainSync(() -> {
            NightGuardService service = service();
            View cover = service == null ? null : cover(service);
            visible[0] = cover != null && cover.isAttachedToWindow() && cover.getVisibility() == View.VISIBLE;
        });
        return visible[0];
    }

    private static String shell(UiAutomation automation, String command) throws Exception {
        try (FileInputStream stream = new ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand(command));
             ByteArrayOutputStream data = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[1024]; int count;
            while ((count = stream.read(buffer)) != -1) data.write(buffer, 0, count);
            return new String(data.toByteArray(), StandardCharsets.UTF_8);
        }
    }
}
