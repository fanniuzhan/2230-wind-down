package com.fanniuzhan.winddown;

import android.content.Context;
import android.content.SharedPreferences;
import com.fanniuzhan.winddown.core.NightSchedule;

final class SettingsStore {
    private final SharedPreferences preferences;

    SettingsStore(Context context) {
        preferences = context.getSharedPreferences("night_guard", Context.MODE_PRIVATE);
    }

    NightSchedule schedule() {
        try {
            return new NightSchedule(preferences.getBoolean("enabled", false)
                    && disclosureAccepted() && compatibilityConfirmed(),
                    preferences.getInt("start", 1350), preferences.getInt("end", 390),
                    preferences.getInt("days", 127));
        } catch (ClassCastException | IllegalArgumentException e) {
            return NightSchedule.defaults();
        }
    }

    void save(NightSchedule schedule) {
        commit(preferences.edit().putBoolean("enabled", schedule.enabled)
                .putInt("start", schedule.startMinute).putInt("end", schedule.endMinute)
                .putInt("days", schedule.daysMask));
    }

    boolean disclosureAccepted() { return bool("disclosure"); }
    void acceptDisclosure() { commit(preferences.edit().putBoolean("disclosure", true)); }
    boolean compatibilityConfirmed() { return bool("compatibility"); }
    void confirmCompatibility(boolean confirmed) {
        SharedPreferences.Editor edit = preferences.edit().putBoolean("compatibility", confirmed);
        if (!confirmed) edit.putBoolean("enabled", false);
        commit(edit);
    }
    boolean trialCompleted() { return bool("trial_completed"); }
    long trialUntil() {
        try { return preferences.getLong("trial_until", 0); }
        catch (ClassCastException e) { return 0; }
    }
    boolean isTrialActive(long now) {
        long until = trialUntil();
        return until > now && until - now <= 120_000L;
    }
    void beginTrial(long now) {
        commit(preferences.edit().putLong("trial_until", now + 60_000L)
                .putBoolean("trial_completed", false).putBoolean("compatibility", false)
                .putBoolean("enabled", false));
    }
    void finishTrialIfDue(long now) {
        long until = trialUntil();
        if (until > 0 && now >= until)
            commit(preferences.edit().putLong("trial_until", 0).putBoolean("trial_completed", true));
    }
    String lastError() {
        try { return preferences.getString("last_error", ""); }
        catch (ClassCastException e) { return "设置无法读取，请在白天重新保存。"; }
    }
    void error(String message) {
        if (!message.equals(lastError())) commit(preferences.edit().putString("last_error", message));
    }
    private boolean bool(String key) {
        try { return preferences.getBoolean(key, false); }
        catch (ClassCastException e) { return false; }
    }
    private void commit(SharedPreferences.Editor editor) {
        if (!editor.commit()) throw new IllegalStateException("无法保存设置，请检查可用空间。");
    }
}
