package com.fanniuzhan.winddown;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.provider.AlarmClock;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/** Dedicated-emulator regression. Opens Clock but never starts a trial or creates an alarm. */
public final class ClockLaunchInstrumentation extends Instrumentation {
    private int passed;
    private boolean screenRecovery;

    @Override public void onCreate(Bundle arguments) {
        super.onCreate(arguments);
        screenRecovery = arguments != null && "true".equals(arguments.getString("screen_recovery"));
        start();
    }

    @Override public void onStart() {
        if (screenRecovery) { ScreenRecoveryChecks.run(this); return; }
        Bundle result = new Bundle();
        try {
            Context context = getTargetContext();
            check(context.checkSelfPermission("com.android.alarm.permission.SET_ALARM")
                    == PackageManager.PERMISSION_GRANTED, "Normal alarm permission is granted");
            AllowedApps allowed = new AllowedApps(context);
            List<Intent> candidates = ClockLauncher.candidates(context, allowed.clocks);
            check(!candidates.isEmpty(), "Installed system Clock has launch candidates");
            check(candidates.stream().allMatch(intent -> intent.getComponent() != null
                    && allowed.clocks.contains(intent.getComponent().getPackageName())
                    && !"android".equals(intent.getComponent().getPackageName())
                    && !AlarmClock.ACTION_SET_ALARM.equals(intent.getAction())),
                    "Every route explicitly targets an allowed clock and never sets an alarm");
            check(candidates.stream().anyMatch(intent -> Intent.ACTION_MAIN.equals(intent.getAction())),
                    "System Clock launcher fallback is discovered");

            AtomicInteger attempts = new AtomicInteger();
            check(ClockLauncher.launch(candidates, intent -> attempts.incrementAndGet()) && attempts.get() == 1,
                    "Successful launch stops further attempts");
            int originalFlags = candidates.get(0).getFlags();
            check(ClockLauncher.launch(candidates, intent -> {
                checkFlag((intent.getFlags() & Intent.FLAG_ACTIVITY_NEW_TASK) != 0);
            }) && candidates.get(0).getFlags() == originalFlags, "Service launch flags do not mutate candidates");
            attempts.set(0);
            check(candidates.size() > 1 && ClockLauncher.launch(candidates, intent -> {
                if (attempts.getAndIncrement() == 0) throw new SecurityException("Simulated vendor restriction");
            }) && attempts.get() == 2, "Rejected vendor entry falls back instead of failing");
            attempts.set(0);
            check(ClockLauncher.launch(candidates, intent -> {
                if (attempts.getAndIncrement() == 0) throw new ActivityNotFoundException("Simulated removed entry");
            }) && attempts.get() == 2, "Unavailable activity also falls back");
            attempts.set(0);
            check(!ClockLauncher.launch(candidates, intent -> {
                attempts.incrementAndGet(); throw new SecurityException("All routes denied");
            }) && attempts.get() == candidates.size(), "All denied routes return a recoverable failure");
            check(!ClockLauncher.launch(Collections.emptyList(), intent -> {
                throw new AssertionError("Empty candidates must not launch");
            }), "Missing clock cannot open another app or chooser");

            check(ClockLauncher.open(context, allowed.clocks), "Public alarm entry actually opens system Clock");
            waitForIdleSync();
            AtomicInteger launcherStarts = new AtomicInteger();
            check(ClockLauncher.launch(candidates, intent -> {
                if (!Intent.ACTION_MAIN.equals(intent.getAction()))
                    throw new SecurityException("Force the launcher fallback on this device");
                context.startActivity(intent); launcherStarts.incrementAndGet();
            }) && launcherStarts.get() == 1, "Real system Clock opens when the standard entry is rejected");
            waitForIdleSync();
            result.putString("stream", "PASS: " + passed + " clock navigation checks; no alarms or trials created.\n");
            finish(Activity.RESULT_OK, result);
        } catch (Throwable error) {
            result.putString("stream", "FAIL after " + passed + " checks: " + error + "\n");
            finish(Activity.RESULT_CANCELED, result);
        }
    }

    private void check(boolean success, String message) {
        if (!success) throw new AssertionError(message);
        passed++;
    }

    private static void checkFlag(boolean present) {
        if (!present) throw new AssertionError("NEW_TASK required for service navigation");
    }
}
