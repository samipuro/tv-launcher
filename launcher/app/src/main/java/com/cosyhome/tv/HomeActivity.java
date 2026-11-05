package com.cosyhome.tv;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ChangedPackages;
import android.media.tv.TvContract;
import android.media.tv.TvInputInfo;
import android.media.tv.TvInputManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.format.DateFormat;
import android.view.KeyEvent;
import android.view.View;
import android.widget.GridLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Cosy Home: a clock over a valley that follows the sun, a dock of six apps, and on Down a
 * frosted panel with every app and the TV's inputs.
 */
public class HomeActivity extends Activity implements SceneView.Listener {

    private static final long MINUTE_MS = 60_000L;
    private static final int GRID_COLUMNS = 6;
    /** Dock tiles rest nearly opaque; focus is shown by the zoom, lift and white ring. */
    private static final float DOCK_REST_ALPHA = 0.94f;

    private SceneView scene;
    private View homeLayer, appsLayer;
    private TextView clock, date, weather, headerTime, focusedName;
    private LinearLayout dock, inputs;
    private GridLayout grid;
    private GlassPanel appsPanel, inputsPanel;
    private View npCard, npOpen;
    private android.widget.ImageView npArt;
    private android.widget.ImageButton npPrev, npPlay, npNext;
    private TextView npTitle, npArtist;
    private NowPlaying nowPlaying;
    private NowPlaying.Track track;
    private android.widget.ImageButton statusInputs, statusNetwork, statusSettings;
    /** Keeps the network icon current while Home is visible. */
    private final android.net.ConnectivityManager.NetworkCallback networkCallback =
            new android.net.ConnectivityManager.NetworkCallback() {
                @Override
                public void onAvailable(android.net.Network network) {
                    runOnUiThread(() -> showNetwork());
                }

                @Override
                public void onLost(android.net.Network network) {
                    runOnUiThread(() -> showNetwork());
                }

                @Override
                public void onCapabilitiesChanged(android.net.Network network, android.net.NetworkCapabilities caps) {
                    runOnUiThread(() -> showNetwork());
                }
            };

    private List<String> dockApps, pinnedApps;
    private Set<String> hiddenApps;
    private String liveTvPkg, usbPkg, ambilightAction, place;
    private double lat, lon;
    private boolean located;
    private float density;

    private boolean appsOpen;
    private boolean resumed;
    /** The package sequence the tiles were built at, or -1 before the first build. */
    private int packageSequence = -1;
    /** Survives the activity being recreated while an app is open; resets only with the process. */
    private static int lastDock = 0;
    private static int lastGrid = 0;
    /** Preview a phase from adb: `am start -n com.cosyhome.tv/.HomeActivity --es phase day` ("auto" clears it). */
    private static SceneView.Phase forcedPhase;
    /** Preview the weather from adb: `--es weather rain` (clear, partly, cloudy, fog, drizzle, rain, heavyrain, snow, storm; "auto" clears it). */
    private static Integer forcedWeather;
    /** Demo extras for screenshots, with a weather preview: `--ei temp -5` for the weather line and `--es clock 22:40` for the clock. */
    private static Integer forcedTemp;
    private static String forcedClock;
    /** Preview the moon from adb: `--es moon full` (new, crescent, first, gibbous, full, last, waning; "auto" clears it). Shown even when the real moon is down. */
    private static Integer forcedMoon;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable minuteTick = new Runnable() {
        @Override
        public void run() {
            tick();
            // Land just after the next minute boundary so the clock never shows the old minute.
            long now = System.currentTimeMillis();
            handler.postDelayed(this, MINUTE_MS - now % MINUTE_MS + 200);
        }
    };
    private final Runnable weatherTick = new Runnable() {
        @Override
        public void run() {
            Weather.fetch(HomeActivity.this, lat, lon, () -> {
                showWeather(Weather.cached(HomeActivity.this));
                applyWeather();
            });
            handler.postDelayed(this, Weather.REFRESH_MS);
        }
    };
    private final BroadcastReceiver packageReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            // Updates send REMOVED then ADDED; wait for the new APK instead of dropping the tile.
            if (Intent.ACTION_PACKAGE_REMOVED.equals(intent.getAction())
                    && intent.getBooleanExtra(Intent.EXTRA_REPLACING, false)) return;
            // Behind an app, leave it to onResume, which asks PackageManager what changed.
            if (!resumed) return;
            loadApps();
            restoreFocus();
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_home);
        density = getResources().getDisplayMetrics().density;

        // Built from cosy.defaults.properties and cosy.properties, see app/build.gradle.
        dockApps = list(R.string.dock);
        pinnedApps = list(R.string.pinned);
        hiddenApps = new HashSet<>(list(R.string.hidden));
        liveTvPkg = getString(R.string.live_tv_package);
        usbPkg = getString(R.string.usb_package);
        ambilightAction = getString(R.string.ambilight_action);
        place = getString(R.string.location_name);
        try {
            lat = Double.parseDouble(getString(R.string.location_latitude));
            lon = Double.parseDouble(getString(R.string.location_longitude));
            located = true;
        } catch (NumberFormatException e) {
            located = false;   // no location set: fixed phase times and no weather
        }

        scene = findViewById(R.id.scene);
        homeLayer = findViewById(R.id.homeLayer);
        appsLayer = findViewById(R.id.appsLayer);
        clock = findViewById(R.id.clock);
        date = findViewById(R.id.date);
        weather = findViewById(R.id.weather);
        headerTime = findViewById(R.id.headerTime);
        focusedName = findViewById(R.id.focusedName);
        dock = findViewById(R.id.dock);
        inputs = findViewById(R.id.inputs);
        grid = findViewById(R.id.appsGrid);
        appsPanel = findViewById(R.id.appsPanel);
        inputsPanel = findViewById(R.id.inputsPanel);
        scene.setListener(this);

        findViewById(R.id.back).setOnClickListener(v -> closeApps());
        View ambilight = findViewById(R.id.ambilight);
        if (ambilightAction.isEmpty()
                || getPackageManager().resolveActivity(new Intent(ambilightAction), 0) == null) {
            ambilight.setVisibility(View.GONE);
        }
        ambilight.setOnClickListener(v -> open(new Intent(ambilightAction)));
        findViewById(R.id.settings).setOnClickListener(v -> open(new Intent(Settings.ACTION_SETTINGS)));
        // The header row is the top of the screen: Up goes nowhere rather than wrapping around.
        View.OnKeyListener topRow = (v, keyCode, e) ->
                e.getAction() == KeyEvent.ACTION_DOWN && keyCode == KeyEvent.KEYCODE_DPAD_UP;
        findViewById(R.id.back).setOnKeyListener(topRow);
        findViewById(R.id.ambilight).setOnKeyListener(topRow);
        findViewById(R.id.settings).setOnKeyListener(topRow);

        setUpNowPlaying();
        setUpStatus();
        weather.setText(weatherLine(Weather.cached(this)));
        readPhaseOverride(getIntent());
        applyWeather();

        // Registered once rather than in onResume: there the call waits about 50 ms for the
        // ActivityManager lock, which the system holds while it switches activities.
        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_PACKAGE_ADDED);
        filter.addAction(Intent.ACTION_PACKAGE_REMOVED);
        filter.addAction(Intent.ACTION_PACKAGE_CHANGED);
        filter.addDataScheme("package");
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(packageReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(packageReceiver, filter);
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        unregisterReceiver(packageReceiver);
    }

    // Purpose: find the now playing card, wire its buttons and keys, and create the media session reader.
    private void setUpNowPlaying() {
        npCard = findViewById(R.id.nowPlaying);
        npOpen = findViewById(R.id.npOpen);
        npArt = findViewById(R.id.npArt);
        npTitle = findViewById(R.id.npTitle);
        npArtist = findViewById(R.id.npArtist);
        npPrev = findViewById(R.id.npPrev);
        npPlay = findViewById(R.id.npPlay);
        npNext = findViewById(R.id.npNext);
        npArt.setClipToOutline(true);

        // Cover opens the app's player; the three buttons control playback.
        npOpen.setOnClickListener(v -> { if (track != null) NowPlaying.open(this, track); });
        npPrev.setOnClickListener(v -> { if (track != null) NowPlaying.previous(track); });
        npPlay.setOnClickListener(v -> { if (track != null) NowPlaying.toggle(track); });
        npNext.setOnClickListener(v -> { if (track != null) NowPlaying.next(track); });

        // The card is one row above the dock: Down goes back to the dock, Up to the status icons, the row ends go nowhere.
        View[] row = {npOpen, npPrev, npPlay, npNext};
        for (int i = 0; i < row.length; i++) {
            final boolean first = i == 0, last = i == row.length - 1;
            row[i].setOnKeyListener((v, keyCode, e) -> {
                if (e.getAction() != KeyEvent.ACTION_DOWN) return false;
                switch (keyCode) {
                    case KeyEvent.KEYCODE_DPAD_DOWN:
                        focusDock(lastDock);
                        return true;
                    case KeyEvent.KEYCODE_DPAD_UP:
                        statusInputs.requestFocus();
                        return true;
                    case KeyEvent.KEYCODE_DPAD_LEFT:
                        return first;
                    case KeyEvent.KEYCODE_DPAD_RIGHT:
                        return last;
                    default:
                        return false;
                }
            });
        }
        nowPlaying = new NowPlaying(this, handler, this::showTrack);
    }

    // Purpose: wire the top-right status icons (inputs, network, settings) and their remote keys.
    private void setUpStatus() {
        statusInputs = findViewById(R.id.statusInputs);
        statusNetwork = findViewById(R.id.statusNetwork);
        statusSettings = findViewById(R.id.statusSettings);

        // Inputs opens the apps panel on its inputs row; network and settings open the TV's settings pages.
        statusInputs.setOnClickListener(v -> openInputs());
        statusNetwork.setOnClickListener(v -> {
            if (!open(new Intent(Settings.ACTION_WIFI_SETTINGS))) open(new Intent(Settings.ACTION_SETTINGS));
        });
        statusSettings.setOnClickListener(v -> open(new Intent(Settings.ACTION_SETTINGS)));

        // Top row of the screen: Up and the row ends go nowhere, Down goes to the now playing card or the dock.
        View[] row = {statusInputs, statusNetwork, statusSettings};
        for (int i = 0; i < row.length; i++) {
            final boolean first = i == 0, last = i == row.length - 1;
            row[i].setOnKeyListener((v, keyCode, e) -> {
                if (e.getAction() != KeyEvent.ACTION_DOWN) return false;
                switch (keyCode) {
                    case KeyEvent.KEYCODE_DPAD_DOWN:
                        if (npCard.getVisibility() == View.VISIBLE) npPlay.requestFocus();
                        else focusDock(lastDock);
                        return true;
                    case KeyEvent.KEYCODE_DPAD_UP:
                        return true;
                    case KeyEvent.KEYCODE_DPAD_LEFT:
                        return first;
                    case KeyEvent.KEYCODE_DPAD_RIGHT:
                        return last;
                    default:
                        return false;
                }
            });
        }
    }

    // Purpose: show ethernet, Wi-Fi or offline on the network icon, from the current default network.
    private void showNetwork() {
        android.net.ConnectivityManager cm = getSystemService(android.net.ConnectivityManager.class);
        android.net.NetworkCapabilities caps = cm == null ? null : cm.getNetworkCapabilities(cm.getActiveNetwork());
        int icon = R.drawable.ic_status_offline;
        if (caps != null && caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_ETHERNET)) icon = R.drawable.ic_status_ethernet;
        else if (caps != null && caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI)) icon = R.drawable.ic_status_wifi;
        statusNetwork.setImageResource(icon);
    }

    // Purpose: open the apps panel with focus on its first input.
    private void openInputs() {
        openApps();
        if (inputs.getChildCount() > 1) inputs.getChildAt(1).requestFocus();
    }

    // Purpose: show the playing (or recently paused) track on the card, or hide the card when there is none.
    private void showTrack(NowPlaying.Track t) {
        track = t;
        if (t == null) {
            // Hiding a focused card would leave focus nowhere: hand it back to the dock first.
            if (npCard.hasFocus()) focusDock(lastDock);
            npCard.setVisibility(View.GONE);
            return;
        }

        // Cover art when the app sends one, else the app's icon.
        npTitle.setText(t.title);
        npArtist.setText(t.artist);
        npArtist.setVisibility(t.artist.isEmpty() ? View.GONE : View.VISIBLE);
        if (t.art != null) npArt.setImageBitmap(t.art);
        else {
            try {
                npArt.setImageDrawable(getPackageManager().getApplicationIcon(t.controller.getPackageName()));
            } catch (android.content.pm.PackageManager.NameNotFoundException e) {
                npArt.setImageDrawable(null);
            }
        }
        npPlay.setImageResource(t.playing ? R.drawable.ic_np_pause : R.drawable.ic_np_play);
        npCard.setVisibility(View.VISIBLE);
    }

    private static void readPhaseOverride(Intent intent) {
        // The weather and moon previews ride on the same intent as the phase preview.
        readWeatherOverride(intent);
        readMoonOverride(intent);
        String p = intent == null ? null : intent.getStringExtra("phase");
        if (p == null) return;
        try {
            forcedPhase = SceneView.Phase.valueOf(p.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            forcedPhase = null;   // "auto" or anything else: follow the sun again
        }
    }

    // Purpose: read the adb weather preview name into a WMO code; "auto" or unknown names clear it.
    private static void readWeatherOverride(Intent intent) {
        String w = intent == null ? null : intent.getStringExtra("weather");
        if (w == null) return;

        // Demo temperature and clock only live as long as the weather preview.
        forcedTemp = intent.hasExtra("temp") ? intent.getIntExtra("temp", 0) : null;
        forcedClock = intent.getStringExtra("clock");

        // One representative WMO code per preview name.
        switch (w.toLowerCase(Locale.ROOT)) {
            case "clear": forcedWeather = 0; break;
            case "partly": forcedWeather = 2; break;
            case "cloudy": forcedWeather = 3; break;
            case "fog": forcedWeather = 45; break;
            case "drizzle": forcedWeather = 51; break;
            case "rain": forcedWeather = 63; break;
            case "heavyrain": forcedWeather = 65; break;
            case "snow": forcedWeather = 73; break;
            case "storm": forcedWeather = 95; break;
            default: forcedWeather = null; forcedTemp = null; forcedClock = null; break;
        }
    }

    // Purpose: read the adb moon preview name into a step of the moon's cycle; "auto" or unknown names clear it.
    private static void readMoonOverride(Intent intent) {
        String m = intent == null ? null : intent.getStringExtra("moon");
        if (m == null) return;

        // Eighths of the cycle: new, waxing crescent, first quarter, waxing gibbous, full, then waning.
        int eighth = SceneView.MOON_STEPS / 8;
        switch (m.toLowerCase(Locale.ROOT)) {
            case "new": forcedMoon = 0; break;
            case "crescent": forcedMoon = eighth; break;
            case "first": forcedMoon = 2 * eighth; break;
            case "gibbous": forcedMoon = 3 * eighth; break;
            case "full": forcedMoon = 4 * eighth; break;
            case "last": forcedMoon = 6 * eighth; break;
            case "waning": forcedMoon = 7 * eighth; break;
            default: forcedMoon = null; break;
        }
    }

    // Purpose: hand the current (or previewed) weather code to the scene, and match the weather line to a preview.
    private void applyWeather() {
        scene.setWeather(forcedWeather != null ? forcedWeather : Weather.cachedCode(this));

        // A preview with a demo temperature shows that reading; otherwise the real one.
        if (forcedWeather != null && forcedTemp != null) showWeather(Weather.text(this, forcedTemp, forcedWeather));
        else showWeather(Weather.cached(this));
        if (clock != null) tick();
    }

    @Override
    protected void onResume() {
        super.onResume();
        resumed = true;
        // Between two resumes the package list almost never changes, so keep the tiles unless
        // PackageManager reports a change. It returns null when nothing changed.
        if (packageSequence < 0 || getPackageManager().getChangedPackages(packageSequence) != null) {
            loadApps();
        }
        buildInputs();
        // Update the clock now, then on every minute boundary.
        tick();
        long now = System.currentTimeMillis();
        handler.postDelayed(minuteTick, MINUTE_MS - now % MINUTE_MS + 200);
        if (located) handler.postDelayed(weatherTick, Weather.stale(this) ? 0 : Weather.REFRESH_MS);
        scene.setRunning(true);
        nowPlaying.start();
        showNetwork();
        android.net.ConnectivityManager cm = getSystemService(android.net.ConnectivityManager.class);
        if (cm != null) cm.registerDefaultNetworkCallback(networkCallback);
        restoreFocus();
    }

    @Override
    protected void onPause() {
        super.onPause();
        resumed = false;
        handler.removeCallbacks(minuteTick);
        handler.removeCallbacks(weatherTick);
        scene.setRunning(false);
        nowPlaying.stop();

        // Stop following the network while an app is in front.
        android.net.ConnectivityManager cm = getSystemService(android.net.ConnectivityManager.class);
        if (cm != null) {
            try {
                cm.unregisterNetworkCallback(networkCallback);
            } catch (IllegalArgumentException ignored) {
            }
        }
    }

    /** Out of sight behind an app: give the scene's 8 MB back while Netflix and friends need it. */
    @Override
    protected void onStop() {
        super.onStop();
        scene.release();
    }

    /** Home pressed while already home: back to the clock with the first dock app focused. */
    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        readPhaseOverride(intent);
        applyWeather();
        if (intent == null || !intent.hasCategory(Intent.CATEGORY_HOME)) return;
        lastDock = 0;
        if (appsOpen) closeApps();
        else focusDock(0);
    }

    @Override
    public void onBackPressed() {
        if (appsOpen) closeApps();
        // On the clock screen Back goes nowhere: this is home.
    }

    // ---- scene, clock and weather -----------------------------------------------------------------

    @Override
    public void onSceneChanged() {
        SceneView.Phase p = scene.phase();
        int glassTint = p == SceneView.Phase.DAWN ? 0x571E1A3C
                : p == SceneView.Phase.DAY ? 0x6B0E222E
                : p == SceneView.Phase.DUSK ? 0x5C1E1028 : 0x6B0A0E1E;
        GlassPanel.setSource(scene.blurred(), glassTint);
        appsPanel.invalidate();
        inputsPanel.invalidate();
        int text = p == SceneView.Phase.DAY ? 0xFF12222C
                : p == SceneView.Phase.DAWN ? 0xFFFFF8F0
                : p == SceneView.Phase.DUSK ? 0xFFFFF4EA : 0xFFEEF0F8;
        clock.setTextColor(text);
        date.setTextColor(text);
        weather.setTextColor((text & 0x00FFFFFF) | 0xD1000000);
    }

    private void tick() {
        Date now = new Date();
        Locale locale = Locale.getDefault();
        // Hours and minutes in the TV language's own style (Finnish 14.04, English 14:04 or 2:04), without AM/PM.
        String pattern = DateFormat.getBestDateTimePattern(locale, DateFormat.is24HourFormat(this) ? "Hm" : "hm")
                .replace("a", "").trim();
        SimpleDateFormat timeFormat = new SimpleDateFormat(pattern, locale);
        String hm = timeFormat.format(now);

        // A demo clock ("22:40") is shown in the same style.
        if (forcedClock != null) {
            try {
                hm = timeFormat.format(new SimpleDateFormat("H:mm", Locale.ROOT).parse(forcedClock));
            } catch (java.text.ParseException e) {
                hm = forcedClock;
            }
        }
        clock.setText(hm);
        headerTime.setText(hm);
        date.setText(new SimpleDateFormat(DateFormat.getBestDateTimePattern(locale, "EEEEdMMMM"), locale).format(now));
        scene.setPhase(forcedPhase != null ? forcedPhase : phaseAt(now.getTime()));
        showMoon(now.getTime());
    }

    // Purpose: the real moon's phase, shown only while it is above the horizon here (always, with no location set).
    private void showMoon(long now) {
        if (forcedMoon != null) {
            scene.setMoon(forcedMoon, true);
            return;
        }
        int step = (int) Math.round(Moon.phase(now) / (2 * Math.PI) * SceneView.MOON_STEPS);
        scene.setMoon(step, !located || Moon.up(now, lat, lon));
    }

    /**
     * Dawn and dusk straddle the real sunrise and sunset for the configured place, or 07:00 and
     * 19:00 when no place is set.
     */
    private SceneView.Phase phaseAt(long now) {
        long rise, set;
        if (located) {
            long[] sun = Sun.riseSet(now, lat, lon);
            rise = sun[0];
            set = sun[1];
        } else {
            Calendar c = Calendar.getInstance();
            c.setTimeInMillis(now);
            c.set(Calendar.HOUR_OF_DAY, 7);
            c.set(Calendar.MINUTE, 0);
            c.set(Calendar.SECOND, 0);
            c.set(Calendar.MILLISECOND, 0);
            rise = c.getTimeInMillis();
            set = rise + 12 * 60 * MINUTE_MS;
        }
        if (now >= rise - 40 * MINUTE_MS && now < rise + 50 * MINUTE_MS) return SceneView.Phase.DAWN;
        if (now >= rise + 50 * MINUTE_MS && now < set - 50 * MINUTE_MS) return SceneView.Phase.DAY;
        if (now >= set - 50 * MINUTE_MS && now < set + 40 * MINUTE_MS) return SceneView.Phase.DUSK;
        return SceneView.Phase.NIGHT;
    }

    private void showWeather(String text) {
        weather.setText(weatherLine(text));
    }

    private String weatherLine(String reading) {
        if (!located || reading.isEmpty()) return place;
        return place.isEmpty() ? reading : place + " · " + reading;
    }


    // ---- tiles ------------------------------------------------------------------------------------

    private void loadApps() {
        // Read before the query, so a package that changes during the build triggers another one.
        ChangedPackages changed = getPackageManager().getChangedPackages(Math.max(packageSequence, 0));
        if (changed != null) packageSequence = changed.getSequenceNumber();
        else if (packageSequence < 0) packageSequence = 0;
        // The dock and the first grid row are on screen at once: their banners are decoded now, the rest
        // on a background thread once the tiles are up.
        List<Apps.App> apps = Apps.load(this, dockApps, pinnedApps, hiddenApps, dockApps.size() + GRID_COLUMNS);
        dock.removeAllViews();
        grid.removeAllViews();

        List<Apps.App> docked = new ArrayList<>();
        for (Apps.App a : apps) if (dockApps.contains(a.pkg)) docked.add(a);

        // Six tiles fit at full size; a seventh shrinks them a little so the centred dock keeps its margins.
        boolean crowded = docked.size() > 6;
        int dockW = dp(crowded ? 104 : 115), dockH = dp(crowded ? 59 : 65), dockGap = dp(crowded ? 12 : 15);
        for (int i = 0; i < docked.size(); i++) {
            final int index = i;
            Tile t = new Tile(this, docked.get(i), DOCK_REST_ALPHA, true);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dockW, dockH);
            if (i > 0) lp.setMarginStart(dockGap);
            t.setOnTileFocused(tile -> lastDock = index);
            final boolean first = i == 0, last = i == docked.size() - 1;
            t.setOnKeyListener((v, keyCode, e) -> {
                if (e.getAction() != KeyEvent.ACTION_DOWN) return false;
                switch (keyCode) {
                    case KeyEvent.KEYCODE_DPAD_DOWN:
                        openApps();
                        return true;
                    case KeyEvent.KEYCODE_DPAD_UP:
                        // Up from the dock reaches the now playing card's play button when it is showing, else the status icons.
                        if (npCard.getVisibility() == View.VISIBLE) npPlay.requestFocus();
                        else statusInputs.requestFocus();
                        return true;
                    case KeyEvent.KEYCODE_DPAD_LEFT:
                        return first;
                    case KeyEvent.KEYCODE_DPAD_RIGHT:
                        return last;
                    default:
                        return false;
                }
            });
            dock.addView(t, lp);
        }

        int cellW = dp(120), cellH = dp(68), colGap = dp(16), rowGap = dp(14);
        for (int i = 0; i < apps.size(); i++) {
            final int index = i;
            Tile t = new Tile(this, apps.get(i), 1f, false);
            GridLayout.LayoutParams lp = new GridLayout.LayoutParams();
            lp.width = cellW;
            lp.height = cellH;
            lp.setMargins(0, 0, i % GRID_COLUMNS == GRID_COLUMNS - 1 ? 0 : colGap, rowGap);
            t.setOnTileFocused(tile -> {
                lastGrid = index;
                focusedName.setText(tile.app.label);
            });
            final int col = i % GRID_COLUMNS;
            final boolean lastInRow = col == GRID_COLUMNS - 1 || i == apps.size() - 1;
            t.setOnKeyListener((v, keyCode, e) -> {
                if (e.getAction() != KeyEvent.ACTION_DOWN) return false;
                // Stay on the row at its ends instead of wrapping to the next one.
                return (keyCode == KeyEvent.KEYCODE_DPAD_LEFT && col == 0)
                        || (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT && lastInRow);
            });
            grid.addView(t, lp);
        }

        // The deferred banners are decoded once the tiles are up, off the main thread: the panel opens
        // without decoding on its first frame, and the tiles are ready before anyone scrolls to them.
        grid.post(() -> {
            List<Apps.LazyBanner> pending = new ArrayList<>();
            for (Apps.App a : apps) {
                if (a.banner instanceof Apps.LazyBanner) pending.add((Apps.LazyBanner) a.banner);
            }
            if (pending.isEmpty()) return;
            Thread t = new Thread(() -> {
                for (Apps.LazyBanner b : pending) b.resolve();
                grid.postInvalidate();
            }, "cosy-banners");
            t.setPriority(Thread.MIN_PRIORITY);
            t.start();
        });
    }

    private void focusDock(int index) {
        if (dock.getChildCount() == 0) return;
        dock.getChildAt(Math.max(0, Math.min(index, dock.getChildCount() - 1))).requestFocus();
    }

    private void focusGrid(int index) {
        if (grid.getChildCount() == 0) {
            findViewById(R.id.back).requestFocus();
            return;
        }
        grid.getChildAt(Math.max(0, Math.min(index, grid.getChildCount() - 1))).requestFocus();
    }

    private void restoreFocus() {
        if (appsOpen) focusGrid(lastGrid);
        else focusDock(lastDock);
    }

    // ---- the apps panel ---------------------------------------------------------------------------

    private void openApps() {
        if (appsOpen) return;
        appsOpen = true;
        appsLayer.setAlpha(0f);
        appsLayer.setTranslationY(dp(16));
        appsLayer.setVisibility(View.VISIBLE);
        appsLayer.animate().alpha(1f).translationY(0f).setDuration(220).start();
        homeLayer.animate().alpha(0f).setDuration(160)
                .withEndAction(() -> homeLayer.setVisibility(View.INVISIBLE)).start();
        // The grid starts with the dock apps, so the same app stays focused.
        lastGrid = lastDock;
        focusGrid(lastGrid);
    }

    private void closeApps() {
        if (!appsOpen) return;
        appsOpen = false;
        homeLayer.setVisibility(View.VISIBLE);
        homeLayer.animate().alpha(1f).setDuration(220).start();
        appsLayer.animate().alpha(0f).translationY(dp(16)).setDuration(160)
                .withEndAction(() -> appsLayer.setVisibility(View.GONE)).start();
        focusDock(lastDock);
    }

    // ---- inputs -----------------------------------------------------------------------------------

    /** What the input chips were last built from, so a resume with the same inputs keeps them. */
    private List<String> inputsKey = Collections.emptyList();

    /**
     * Live TV, the HDMI ports in order, and USB media. Checked on every resume and rebuilt only
     * when they change: each chip costs about 4 ms to create.
     */
    private void buildInputs() {
        List<String> labels = new ArrayList<>();
        List<Runnable> actions = new ArrayList<>();
        List<String> key = new ArrayList<>();

        Intent liveTv = getPackageManager().getLeanbackLaunchIntentForPackage(liveTvPkg);
        if (liveTv != null) {
            labels.add(getString(R.string.live_tv));
            actions.add(() -> open(liveTv));
            key.add(String.valueOf(liveTv.getComponent()));
        }

        TvInputManager tim = (TvInputManager) getSystemService(TV_INPUT_SERVICE);
        if (tim != null) {
            List<TvInputInfo> hdmi = new ArrayList<>();
            for (TvInputInfo info : tim.getTvInputList()) {
                if (info.isPassthroughInput() && info.getType() == TvInputInfo.TYPE_HDMI) hdmi.add(info);
            }
            Collections.sort(hdmi, (a, b) -> a.getId().compareTo(b.getId()));
            for (int i = 0; i < hdmi.size(); i++) {
                TvInputInfo info = hdmi.get(i);
                CharSequence l = info.loadLabel(this);
                String label = l == null || l.length() == 0 ? "HDMI " + (i + 1) : l.toString();
                if (!label.toUpperCase(Locale.ROOT).contains("HDMI")) label = "HDMI " + label;
                final String id = info.getId();
                labels.add(label);
                actions.add(() -> openInput(id));
                key.add(id);
            }
        }

        Intent usb = getPackageManager().getLeanbackLaunchIntentForPackage(usbPkg);
        if (usb != null) {
            labels.add(getString(R.string.usb));
            actions.add(() -> open(usb));
            key.add(String.valueOf(usb.getComponent()));
        }

        key.addAll(labels);
        if (key.equals(inputsKey)) return;
        inputsKey = key;

        while (inputs.getChildCount() > 1) inputs.removeViewAt(1);
        for (int i = 0; i < labels.size(); i++) addInputChip(labels.get(i), actions.get(i));

        // Bottom of the screen: Down stays put.
        for (int i = 1; i < inputs.getChildCount(); i++) {
            inputs.getChildAt(i).setOnKeyListener((v, keyCode, e) ->
                    e.getAction() == KeyEvent.ACTION_DOWN && keyCode == KeyEvent.KEYCODE_DPAD_DOWN);
        }
    }

    private void addInputChip(String label, Runnable action) {
        TextView chip = new TextView(this, null, 0, R.style.Chip);
        chip.setText(label);
        chip.setOnClickListener(v -> action.run());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(34));
        lp.setMarginStart(dp(8));
        inputs.addView(chip, lp);
    }

    /** The standard way to switch to an HDMI port: VIEW its passthrough channel, which Live TV handles. */
    private void openInput(String inputId) {
        Uri uri = TvContract.buildChannelUriForPassthroughInput(inputId);
        open(new Intent(Intent.ACTION_VIEW).setDataAndType(uri, "vnd.android.cursor.item/channel"));
    }

    // Purpose: start an activity, with a short note when the TV has nothing to open it; true when it started.
    private boolean open(Intent i) {
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            startActivity(i);
            return true;
        } catch (ActivityNotFoundException | SecurityException e) {
            Toast.makeText(this, R.string.not_available, Toast.LENGTH_SHORT).show();
            return false;
        }
    }

    private List<String> list(int resId) {
        List<String> out = new ArrayList<>();
        for (String s : getString(resId).split(",")) if (!s.trim().isEmpty()) out.add(s.trim());
        return out;
    }

    private int dp(float v) {
        return Math.round(v * density);
    }
}
