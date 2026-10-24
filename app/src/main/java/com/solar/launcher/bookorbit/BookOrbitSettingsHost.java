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

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * 2026-10-21 — Settings → Library → BookOrbit server.
 * Layman: type the server address and login, pick which libraries/collections to sync, then sync.
 * Tech: reuses NavidromeSettingsHost.Actions (same row/keyboard plumbing in MainActivity).
 */
public final class BookOrbitSettingsHost {

    // 2026-10-21 — Unused keyboard purpose ids (see grep of KEYBOARD_* = n); 34-36 free.
    public static final int KEYBOARD_URL = 34;
    public static final int KEYBOARD_USER = 35;
    public static final int KEYBOARD_PASS = 36;

    private final NavidromeSettingsHost.Actions actions;
    /** 2026-10-21 — Libraries + collections fetched for the Sync sources screen; null = fetch again. */
    private List<String[]> sources;

    public BookOrbitSettingsHost(NavidromeSettingsHost.Actions actions) {
        this.actions = actions;
    }

    public static boolean ownsKeyboard(int purpose) {
        return purpose >= KEYBOARD_URL && purpose <= KEYBOARD_PASS;
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

        // 2026-10-21 — Pick libraries/collections (replaces the single "Solar" collection).
        LinearLayout src = actions.createSettingsRow(RowKeys.BOOKORBIT_SOURCES, R.string.bookorbit_sync_sources, true);
        src.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                actions.clickFeedback();
                sources = null; // always re-read the server when entering
                buildSources();
            }
        });
        actions.addSettingsRow(src);

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
        return "";
    }

    /** 2026-10-21 — Save a typed value; URL is normalized so the preview shows what will be used. */
    public void finishKeyboard(int purpose, String text) {
        String v = text != null ? text.trim() : "";
        SharedPreferences.Editor e = actions.prefs().edit();
        if (purpose == KEYBOARD_URL) e.putString(BookOrbitSync.PREF_URL, BookOrbitClient.normalizeServerUrl(v));
        else if (purpose == KEYBOARD_USER) e.putString(BookOrbitSync.PREF_USER, v);
        else if (purpose == KEYBOARD_PASS) e.putString(BookOrbitSync.PREF_PASS, v);
        e.commit();
        sources = null; // different server/login → different libraries
    }

    /** 2026-10-21 — Keyboard title per purpose (MainActivity keyboard header). */
    public static int keyboardTitleRes(int purpose) {
        if (purpose == KEYBOARD_URL) return R.string.navidrome_settings_url;
        if (purpose == KEYBOARD_USER) return R.string.navidrome_settings_user;
        return R.string.navidrome_settings_pass;
    }

    /** 2026-10-21 — Log in and count libraries/collections on a background thread; toast the result. */
    private void testConnection() {
        final SharedPreferences p = actions.prefs();
        final Context ctx = actions.activity();
        if (!BookOrbitSync.isConfigured(p)) {
            Toast.makeText(ctx, R.string.navidrome_test_fail, Toast.LENGTH_LONG).show();
            return;
        }
        final Handler main = new Handler(Looper.getMainLooper());
        new Thread(new Runnable() {
            @Override public void run() {
                String msg;
                try {
                    BookOrbitClient c = BookOrbitSync.client(p);
                    msg = ctx.getString(R.string.bookorbit_test_ok, c.bookLibraries().length(), c.collections().length());
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

    /**
     * 2026-10-21 — Settings → BookOrbit → Sync sources: one On/Off row per library and collection.
     * Layman: tick everything you want on the player. Tech: first visit shows "Loading…" and fetches
     * /libraries + /collections off the main thread, then rebuilds this screen from the cached list.
     */
    public void buildSources() {
        actions.setSettingsSubScreen(SettingsScreens.BOOKORBIT_SOURCES);
        actions.updateStatusBarTitle();
        actions.applyReachBrowseLayoutMode();
        actions.clearSettingsRows();

        android.widget.Button back = actions.createListButton(actions.activity().getString(R.string.common_cancel_back));
        actions.styleSecondaryLabel(back);
        back.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                actions.clickFeedback();
                actions.drillSettingsBack(new Runnable() {
                    @Override public void run() { build(); }
                });
            }
        });
        actions.addSettingsRow(back);

        if (sources == null) {
            actions.addSettingsRow(actions.createSettingsRow(RowKeys.BOOKORBIT_SOURCES,
                    actions.activity().getString(R.string.bookorbit_sources_loading), false));
            fetchSources();
            return;
        }
        if (sources.isEmpty()) {
            actions.addSettingsRow(actions.createSettingsRow(RowKeys.BOOKORBIT_SOURCES,
                    actions.activity().getString(R.string.bookorbit_sources_none), false));
            return;
        }
        for (final String[] s : sources) {
            final String rowKey = RowKeys.BOOKORBIT_SOURCE_PREFIX + s[0];
            LinearLayout row = actions.createSettingsRow(rowKey, s[1], false);
            row.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    actions.clickFeedback();
                    SharedPreferences p = actions.prefs();
                    p.edit().putString(BookOrbitSync.PREF_SOURCES,
                            BookOrbitSync.toggleSource(p.getString(BookOrbitSync.PREF_SOURCES, ""), s[0])).commit();
                    actions.refreshSettingsPreview(rowKey);
                }
            });
            actions.addSettingsRow(row);
        }
    }

    /** 2026-10-21 — Background fetch of sources; rebuilds only if the user is still on that screen. */
    private void fetchSources() {
        final SharedPreferences p = actions.prefs();
        final Context ctx = actions.activity();
        final Handler main = new Handler(Looper.getMainLooper());
        new Thread(new Runnable() {
            @Override public void run() {
                List<String[]> out = null;
                String err = null;
                try {
                    out = loadSources(ctx, BookOrbitSync.client(p));
                } catch (Exception e) {
                    err = e.getMessage() != null ? e.getMessage() : ctx.getString(R.string.navidrome_test_fail);
                }
                final List<String[]> result = out;
                final String error = err;
                main.post(new Runnable() {
                    @Override public void run() {
                        if (error != null) {
                            Toast.makeText(ctx, error, Toast.LENGTH_LONG).show();
                            return;
                        }
                        sources = result;
                        if (SettingsScreens.BOOKORBIT_SOURCES.equals(actions.settingsSubScreen())) buildSources();
                    }
                });
            }
        }, "BookOrbitSources").start();
    }

    /** 2026-10-21 — [token, label] rows: libraries first, then collections (server order). */
    static List<String[]> loadSources(Context ctx, BookOrbitClient c) throws Exception {
        List<String[]> out = new ArrayList<String[]>();
        JSONArray libs = c.bookLibraries();
        for (int i = 0; i < libs.length(); i++) {
            JSONObject l = libs.getJSONObject(i);
            out.add(new String[] { BookOrbitSync.LIB + l.getInt("id"),
                    ctx.getString(R.string.bookorbit_source_library, l.optString("name", "?")) });
        }
        JSONArray cols = c.collections();
        for (int i = 0; i < cols.length(); i++) {
            JSONObject o = cols.getJSONObject(i);
            out.add(new String[] { BookOrbitSync.COL + o.getInt("id"),
                    ctx.getString(R.string.bookorbit_source_collection, o.optString("name", "?")) });
        }
        return out;
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
        if (RowKeys.BOOKORBIT_SOURCES.equals(rowKey)) {
            int n = BookOrbitSync.sourceCount(prefs);
            return n == 0 ? "-" : ctx != null ? ctx.getString(R.string.bookorbit_sources_selected, n) : String.valueOf(n);
        }
        if (rowKey.startsWith(RowKeys.BOOKORBIT_SOURCE_PREFIX)) {
            boolean on = BookOrbitSync.isSourceOn(prefs, rowKey.substring(RowKeys.BOOKORBIT_SOURCE_PREFIX.length()));
            return ctx != null ? ctx.getString(on ? R.string.common_on : R.string.common_off) : (on ? "On" : "Off");
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
