package com.solar.launcher.bookorbit;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.Toast;

import com.solar.launcher.R;
import com.solar.launcher.RowKeys;
import com.solar.launcher.SettingsScreens;
import com.solar.launcher.navidrome.NavidromeSettingsHost;

/**
 * 2026-10-21 — Settings → Library → BookOrbit server.
 * Layman: type the server address, login and collection name, test it, then sync audiobooks.
 * Tech: reuses NavidromeSettingsHost.Actions (same row/keyboard plumbing in MainActivity).
 */
public final class BookOrbitSettingsHost {

    // 2026-10-21 — Unused keyboard purpose ids (see grep of KEYBOARD_* = n); 34-37 free.
    public static final int KEYBOARD_URL = 34;
    public static final int KEYBOARD_USER = 35;
    public static final int KEYBOARD_PASS = 36;
    public static final int KEYBOARD_COLLECTION = 37;

    private final NavidromeSettingsHost.Actions actions;

    public BookOrbitSettingsHost(NavidromeSettingsHost.Actions actions) {
        this.actions = actions;
    }

    public static boolean ownsKeyboard(int purpose) {
        return purpose >= KEYBOARD_URL && purpose <= KEYBOARD_COLLECTION;
    }

    /** 2026-10-21 — Build the rows: back, server fields, test, auto-sync, sync now. */
    public void build() {
        actions.setSettingsSubScreen(SettingsScreens.BOOKORBIT);
        actions.updateStatusBarTitle();
        actions.applyReachBrowseLayoutMode();
        actions.clearSettingsRows();

        android.widget.Button back = actions.createListButton(actions.activity().getString(R.string.common_cancel_back));
        actions.styleSecondaryLabel(back);
        back.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                actions.clickFeedback();
                actions.drillSettingsBack(null);
            }
        });
        actions.addSettingsRow(back);

        addEditableRow(RowKeys.BOOKORBIT_URL, R.string.navidrome_settings_url, KEYBOARD_URL);
        addEditableRow(RowKeys.BOOKORBIT_USER, R.string.navidrome_settings_user, KEYBOARD_USER);
        addEditableRow(RowKeys.BOOKORBIT_PASS, R.string.navidrome_settings_pass, KEYBOARD_PASS);
        addEditableRow(RowKeys.BOOKORBIT_COLLECTION, R.string.bookorbit_settings_collection, KEYBOARD_COLLECTION);

        LinearLayout test = actions.createSettingsRow(RowKeys.BOOKORBIT_TEST, R.string.navidrome_settings_test, false);
        test.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                actions.clickFeedback();
                testConnection();
            }
        });
        actions.addSettingsRow(test);

        LinearLayout auto = actions.createSettingsRow(RowKeys.BOOKORBIT_SYNC_AUTO, R.string.server_sync_auto, false);
        auto.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                actions.clickFeedback();
                SharedPreferences p = actions.prefs();
                p.edit().putBoolean(BookOrbitSync.PREF_AUTO, !p.getBoolean(BookOrbitSync.PREF_AUTO, false)).commit();
                actions.refreshSettingsPreview(RowKeys.BOOKORBIT_SYNC_AUTO);
            }
        });
        actions.addSettingsRow(auto);

        LinearLayout sync = actions.createSettingsRow(RowKeys.BOOKORBIT_SYNC_NOW, R.string.server_sync_now, false);
        sync.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                actions.clickFeedback();
                syncNow();
            }
        });
        actions.addSettingsRow(sync);
    }

    private void addEditableRow(final String rowKey, int labelRes, final int purpose) {
        LinearLayout row = actions.createSettingsRow(rowKey, labelRes, false);
        row.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                actions.clickFeedback();
                actions.openKeyboard(purpose, prefillFor(purpose));
            }
        });
        actions.addSettingsRow(row);
    }

    private String prefillFor(int purpose) {
        SharedPreferences p = actions.prefs();
        if (purpose == KEYBOARD_URL) return p.getString(BookOrbitSync.PREF_URL, "");
        if (purpose == KEYBOARD_USER) return p.getString(BookOrbitSync.PREF_USER, "");
        if (purpose == KEYBOARD_PASS) return p.getString(BookOrbitSync.PREF_PASS, "");
        if (purpose == KEYBOARD_COLLECTION) return p.getString(BookOrbitSync.PREF_COLLECTION, BookOrbitSync.DEFAULT_COLLECTION);
        return "";
    }

    /** 2026-10-21 — Save a typed value; URL is normalized so the preview shows what will be used. */
    public void finishKeyboard(int purpose, String text) {
        String v = text != null ? text.trim() : "";
        SharedPreferences.Editor e = actions.prefs().edit();
        if (purpose == KEYBOARD_URL) e.putString(BookOrbitSync.PREF_URL, BookOrbitClient.normalizeServerUrl(v));
        else if (purpose == KEYBOARD_USER) e.putString(BookOrbitSync.PREF_USER, v);
        else if (purpose == KEYBOARD_PASS) e.putString(BookOrbitSync.PREF_PASS, v);
        else if (purpose == KEYBOARD_COLLECTION) {
            e.putString(BookOrbitSync.PREF_COLLECTION, v.isEmpty() ? BookOrbitSync.DEFAULT_COLLECTION : v);
        }
        e.commit();
    }

    /** 2026-10-21 — Keyboard title per purpose (MainActivity keyboard header). */
    public static int keyboardTitleRes(int purpose) {
        if (purpose == KEYBOARD_URL) return R.string.navidrome_settings_url;
        if (purpose == KEYBOARD_USER) return R.string.navidrome_settings_user;
        if (purpose == KEYBOARD_PASS) return R.string.navidrome_settings_pass;
        return R.string.bookorbit_settings_collection;
    }

    /** 2026-10-21 — Log in and look up the collection on a background thread; toast the result. */
    private void testConnection() {
        final SharedPreferences p = actions.prefs();
        final Context ctx = actions.activity();
        if (!BookOrbitSync.isConfigured(p)) {
            Toast.makeText(ctx, R.string.navidrome_test_fail, Toast.LENGTH_LONG).show();
            return;
        }
        final String name = p.getString(BookOrbitSync.PREF_COLLECTION, BookOrbitSync.DEFAULT_COLLECTION);
        final Handler main = new Handler(Looper.getMainLooper());
        new Thread(new Runnable() {
            @Override public void run() {
                String msg;
                try {
                    BookOrbitClient c = BookOrbitSync.client(p);
                    int id = c.findCollectionId(name);
                    msg = id < 0 ? ctx.getString(R.string.bookorbit_test_missing, name)
                            : ctx.getString(R.string.bookorbit_test_ok, name, c.collectionBooks(id).size());
                } catch (Exception e) {
                    msg = e.getMessage() != null ? e.getMessage() : ctx.getString(R.string.navidrome_test_fail);
                }
                final String text = msg;
                main.post(new Runnable() {
                    @Override public void run() { Toast.makeText(ctx, text, Toast.LENGTH_LONG).show(); }
                });
            }
        }, "BookOrbitTest").start();
    }

    private void syncNow() {
        if (!BookOrbitSync.isConfigured(actions.prefs())) {
            Toast.makeText(actions.activity(), R.string.navidrome_test_fail, Toast.LENGTH_LONG).show();
            return;
        }
        Toast.makeText(actions.activity(), R.string.server_sync_started, Toast.LENGTH_SHORT).show();
        BookOrbitSync.runAsync(actions.activity(), actions.prefs(), new BookOrbitSync.Listener() {
            @Override public void onStatus(String text) {
                actions.refreshSettingsPreview(RowKeys.BOOKORBIT_SYNC_NOW);
            }
            @Override public void onDone(BookOrbitSync.Result result) {
                actions.refreshSettingsPreview(RowKeys.BOOKORBIT_SYNC_NOW);
                Toast.makeText(actions.activity(), actions.activity().getString(
                        R.string.server_sync_done, result.summary()), Toast.LENGTH_LONG).show();
                actions.onServerSyncFinished();
            }
            @Override public void onError(String message) {
                actions.refreshSettingsPreview(RowKeys.BOOKORBIT_SYNC_NOW);
                Toast.makeText(actions.activity(), message, Toast.LENGTH_LONG).show();
            }
        });
        actions.refreshSettingsPreview(RowKeys.BOOKORBIT_SYNC_NOW);
    }

    /** 2026-10-21 — Right-hand preview text for each BookOrbit row. */
    public static String previewValue(Context ctx, SharedPreferences prefs, String rowKey) {
        if (prefs == null || rowKey == null) return "";
        if (RowKeys.BOOKORBIT_URL.equals(rowKey)) return orDash(prefs.getString(BookOrbitSync.PREF_URL, ""));
        if (RowKeys.BOOKORBIT_USER.equals(rowKey)) return orDash(prefs.getString(BookOrbitSync.PREF_USER, ""));
        if (RowKeys.BOOKORBIT_PASS.equals(rowKey)) {
            String p = prefs.getString(BookOrbitSync.PREF_PASS, "");
            return p != null && !p.isEmpty() ? "••••" : "-";
        }
        if (RowKeys.BOOKORBIT_COLLECTION.equals(rowKey)) {
            return orDash(prefs.getString(BookOrbitSync.PREF_COLLECTION, BookOrbitSync.DEFAULT_COLLECTION));
        }
        if (RowKeys.BOOKORBIT_SYNC_AUTO.equals(rowKey)) {
            boolean on = prefs.getBoolean(BookOrbitSync.PREF_AUTO, false);
            return ctx != null ? ctx.getString(on ? R.string.common_on : R.string.common_off) : (on ? "On" : "Off");
        }
        if (RowKeys.BOOKORBIT_SYNC_NOW.equals(rowKey)) return orDash(BookOrbitSync.statusLine(prefs));
        return "";
    }

    private static String orDash(String s) {
        return s != null && !s.isEmpty() ? s : "-";
    }
}
