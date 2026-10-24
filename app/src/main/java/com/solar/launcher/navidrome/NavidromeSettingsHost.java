package com.solar.launcher.navidrome;

import android.content.SharedPreferences;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.Toast;

import com.solar.launcher.R;
import com.solar.launcher.RowKeys;
import com.solar.launcher.SettingsScreens;

/**
 * 2026-07-06: Settings → Navidrome server URL, user, password, connection test.
 */
public final class NavidromeSettingsHost {

    public interface Actions {
        android.app.Activity activity();
        SharedPreferences prefs();
        void clickFeedback();
        LinearLayout createSettingsRow(String rowKey, int labelRes, boolean submenu);
        LinearLayout createSettingsRow(String rowKey, CharSequence title, boolean submenu);
        Button createListButton(String label);
        void styleSecondaryLabel(Button btn);
        void addSettingsRow(View row);
        void clearSettingsRows();
        void setSettingsSubScreen(String key);
        void updateStatusBarTitle();
        void applyReachBrowseLayoutMode();
        void refreshSettingsPreview(String rowKey);
        void drillSettingsBack(Runnable back);
        void openKeyboard(int purpose, String prefill);
        String previewForRow(String rowKey);
        /** 2026-10-21 — A server sync finished; MainActivity rescans the library so new files show up. */
        void onServerSyncFinished();
        /** 2026-10-21 — Which settings screen is showing (async loads skip stale rebuilds). */
        String settingsSubScreen();
        /** 2026-10-21 — Move focus to row i (keeps your place after "More…" loads a page). */
        void focusSettingsRow(int index);
    }

    public static final int KEYBOARD_URL = 17;
    public static final int KEYBOARD_USER = 18;
    public static final int KEYBOARD_PASS = 19;
    /** 2026-10-21 — Playlist sync tag entry (unused id; 20-23 belong to search/Plex). */
    public static final int KEYBOARD_SYNC_TAG = 25;

    private final Actions actions;

    public NavidromeSettingsHost(Actions actions) {
        this.actions = actions;
    }

    public void build() {
        actions.setSettingsSubScreen(SettingsScreens.NAVIDROME);
        actions.updateStatusBarTitle();
        actions.applyReachBrowseLayoutMode();
        actions.clearSettingsRows();

        Button back = actions.createListButton(actions.activity().getString(R.string.common_cancel_back));
        actions.styleSecondaryLabel(back);
        back.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                actions.clickFeedback();
                actions.drillSettingsBack(null);
            }
        });
        actions.addSettingsRow(back);

        addEditableRow(RowKeys.NAVIDROME_URL, R.string.navidrome_settings_url, KEYBOARD_URL);
        addEditableRow(RowKeys.NAVIDROME_USER, R.string.navidrome_settings_user, KEYBOARD_USER);
        addEditableRow(RowKeys.NAVIDROME_PASS, R.string.navidrome_settings_pass, KEYBOARD_PASS);

        LinearLayout test = actions.createSettingsRow(RowKeys.NAVIDROME_TEST, R.string.navidrome_settings_test, false);
        test.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                actions.clickFeedback();
                testConnection();
            }
        });
        actions.addSettingsRow(test);

        // 2026-10-21 — Sync rows: what to mirror, auto on Wi-Fi, and a manual run.
        addToggleRow(RowKeys.NAVIDROME_SYNC_STARRED, R.string.navidrome_sync_starred,
                NavidromeSync.PREF_STARRED, true);
        addEditableRow(RowKeys.NAVIDROME_SYNC_TAG, R.string.navidrome_sync_tag, KEYBOARD_SYNC_TAG);
        addToggleRow(RowKeys.NAVIDROME_SYNC_AUTO, R.string.server_sync_auto, NavidromeSync.PREF_AUTO, false);
        LinearLayout sync = actions.createSettingsRow(RowKeys.NAVIDROME_SYNC_NOW, R.string.server_sync_now, false);
        sync.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                actions.clickFeedback();
                syncNow();
            }
        });
        actions.addSettingsRow(sync);
    }

    /** 2026-10-21 — On/off row backed by a boolean pref; preview shows On/Off. */
    private void addToggleRow(final String rowKey, int labelRes, final String pref, final boolean def) {
        LinearLayout row = actions.createSettingsRow(rowKey, labelRes, false);
        row.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                actions.clickFeedback();
                SharedPreferences p = actions.prefs();
                p.edit().putBoolean(pref, !p.getBoolean(pref, def)).commit();
                actions.refreshSettingsPreview(rowKey);
            }
        });
        actions.addSettingsRow(row);
    }

    /** 2026-10-21 — Run a sync now; the row preview shows live progress, then the summary. */
    private void syncNow() {
        if (!NavidromeClient.getInstance().isConfigured()) {
            Toast.makeText(actions.activity(), R.string.navidrome_not_configured, Toast.LENGTH_LONG).show();
            return;
        }
        Toast.makeText(actions.activity(), R.string.server_sync_started, Toast.LENGTH_SHORT).show();
        NavidromeSync.runAsync(actions.activity(), actions.prefs(), new NavidromeSync.Listener() {
            @Override public void onStatus(String text) {
                actions.refreshSettingsPreview(RowKeys.NAVIDROME_SYNC_NOW);
            }
            @Override public void onDone(NavidromeSync.Result result) {
                actions.refreshSettingsPreview(RowKeys.NAVIDROME_SYNC_NOW);
                Toast.makeText(actions.activity(), actions.activity().getString(
                        R.string.server_sync_done, result.summary()), Toast.LENGTH_LONG).show();
                actions.onServerSyncFinished();
            }
            @Override public void onError(String message) {
                actions.refreshSettingsPreview(RowKeys.NAVIDROME_SYNC_NOW);
                Toast.makeText(actions.activity(), message, Toast.LENGTH_LONG).show();
            }
        });
        actions.refreshSettingsPreview(RowKeys.NAVIDROME_SYNC_NOW);
    }

    private void addEditableRow(final String rowKey, int labelRes, final int keyboardPurpose) {
        LinearLayout row = actions.createSettingsRow(rowKey, labelRes, false);
        row.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                actions.clickFeedback();
                actions.openKeyboard(keyboardPurpose, prefillFor(rowKey));
            }
        });
        actions.addSettingsRow(row);
    }

    private String prefillFor(String rowKey) {
        SharedPreferences p = actions.prefs();
        if (RowKeys.NAVIDROME_URL.equals(rowKey)) return p.getString("navidrome_url", "");
        if (RowKeys.NAVIDROME_USER.equals(rowKey)) return p.getString("navidrome_user", "");
        if (RowKeys.NAVIDROME_PASS.equals(rowKey)) return p.getString("navidrome_pass", "");
        if (RowKeys.NAVIDROME_SYNC_TAG.equals(rowKey)) return p.getString(NavidromeSync.PREF_TAG, NavidromeSync.DEFAULT_TAG);
        return "";
    }

    public void finishKeyboard(int purpose, String text) {
        SharedPreferences p = actions.prefs();
        if (purpose == KEYBOARD_SYNC_TAG) {
            // 2026-10-21 — Empty tag = don't sync playlists (starred only).
            p.edit().putString(NavidromeSync.PREF_TAG, text != null ? text.trim() : "").commit();
            actions.refreshSettingsPreview(RowKeys.NAVIDROME_SYNC_TAG);
            return;
        }
        String url = p.getString("navidrome_url", "");
        String user = p.getString("navidrome_user", "");
        String pass = p.getString("navidrome_pass", "");
        if (purpose == KEYBOARD_URL) url = text;
        else if (purpose == KEYBOARD_USER) user = text;
        else if (purpose == KEYBOARD_PASS) pass = text;
        NavidromePrefs.save(actions.activity(), p, url, user, pass);
        if (purpose == KEYBOARD_URL) actions.refreshSettingsPreview(RowKeys.NAVIDROME_URL);
        else if (purpose == KEYBOARD_USER) actions.refreshSettingsPreview(RowKeys.NAVIDROME_USER);
        else if (purpose == KEYBOARD_PASS) actions.refreshSettingsPreview(RowKeys.NAVIDROME_PASS);
    }

    private void testConnection() {
        if (!NavidromeClient.getInstance().isConfigured()) {
            Toast.makeText(actions.activity(),
                    actions.activity().getString(R.string.navidrome_not_configured),
                    Toast.LENGTH_LONG).show();
            return;
        }
        NavidromeClient.getInstance().ping(new NavidromeClient.Callback<Boolean>() {
            @Override public void onSuccess(Boolean ok) {
                Toast.makeText(actions.activity(),
                        actions.activity().getString(ok != null && ok
                                ? R.string.navidrome_test_ok : R.string.navidrome_test_fail),
                        Toast.LENGTH_SHORT).show();
            }
            @Override public void onError(String message) {
                Toast.makeText(actions.activity(), message, Toast.LENGTH_LONG).show();
            }
        });
    }

    /** 2026-10-21 — Preview incl. sync rows (On/Off needs a Context for the label). */
    public static String previewValue(android.content.Context ctx, SharedPreferences prefs, String rowKey) {
        if (prefs == null) return "";
        if (RowKeys.NAVIDROME_SYNC_STARRED.equals(rowKey) || RowKeys.NAVIDROME_SYNC_AUTO.equals(rowKey)) {
            boolean on = RowKeys.NAVIDROME_SYNC_STARRED.equals(rowKey)
                    ? prefs.getBoolean(NavidromeSync.PREF_STARRED, true)
                    : prefs.getBoolean(NavidromeSync.PREF_AUTO, false);
            return ctx != null ? ctx.getString(on ? R.string.common_on : R.string.common_off) : (on ? "On" : "Off");
        }
        if (RowKeys.NAVIDROME_SYNC_TAG.equals(rowKey)) {
            String t = prefs.getString(NavidromeSync.PREF_TAG, NavidromeSync.DEFAULT_TAG);
            return t != null && !t.isEmpty() ? t : "-";
        }
        if (RowKeys.NAVIDROME_SYNC_NOW.equals(rowKey)) {
            String st = NavidromeSync.statusLine(prefs);
            return st != null && !st.isEmpty() ? st : "-";
        }
        return previewValue(prefs, rowKey);
    }

    public static String previewValue(SharedPreferences prefs, String rowKey) {
        if (prefs == null) return "";
        if (RowKeys.NAVIDROME_URL.equals(rowKey)) {
            String u = prefs.getString("navidrome_url", "");
            return u != null && !u.isEmpty() ? u : "-";
        }
        if (RowKeys.NAVIDROME_USER.equals(rowKey)) {
            String u = prefs.getString("navidrome_user", "");
            return u != null && !u.isEmpty() ? u : "-";
        }
        if (RowKeys.NAVIDROME_PASS.equals(rowKey)) {
            String p = prefs.getString("navidrome_pass", "");
            return p != null && !p.isEmpty() ? "••••" : "-";
        }
        return "";
    }
}
