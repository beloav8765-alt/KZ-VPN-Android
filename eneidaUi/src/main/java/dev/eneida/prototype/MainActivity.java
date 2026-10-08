package dev.eneida.prototype;

import android.app.Activity;
import android.content.Context;
import android.content.pm.ActivityInfo;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.os.Build;
import android.os.Bundle;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public final class MainActivity extends Activity {
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
        getWindow().setBackgroundDrawable(new ColorDrawable(Color.BLACK));
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        enterImmersive();
        setContentView(new EneidaDesktopView(this));
    }

    @Override public void onWindowFocusChanged(boolean focus) {
        super.onWindowFocusChanged(focus);
        if (focus) enterImmersive();
    }

    private void enterImmersive() {
        if (Build.VERSION.SDK_INT >= 30) {
            getWindow().setDecorFitsSystemWindows(false);
            WindowInsetsController c = getWindow().getInsetsController();
            if (c != null) {
                c.hide(WindowInsets.Type.statusBars() | WindowInsets.Type.navigationBars());
                c.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            }
        } else {
            getWindow().getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_FULLSCREEN |
                    View.SYSTEM_UI_FLAG_HIDE_NAVIGATION |
                    View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY |
                    View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN |
                    View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION |
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
        }
    }
}

final class EneidaDesktopView extends View {
    private enum AppType { FILES, TERMINAL, SETTINGS, BROWSER, APPS }

    private static final float H = 720f;
    private static final float TASKBAR_H = 54f;
    private static final float TITLE_H = 38f;

    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final List<AppWindow> windows = new ArrayList<>();

    private final int BG0 = Color.rgb(6, 13, 23);
    private final int BG1 = Color.rgb(12, 29, 48);
    private final int PANEL = Color.rgb(18, 31, 49);
    private final int PANEL2 = Color.rgb(25, 42, 64);
    private final int TITLE = Color.rgb(20, 35, 55);
    private final int BORDER = Color.rgb(55, 75, 101);
    private final int TEXT = Color.rgb(235, 242, 250);
    private final int MUTED = Color.rgb(148, 166, 188);
    private final int ACCENT = Color.rgb(80, 170, 255);
    private final int ACCENT2 = Color.rgb(101, 220, 199);
    private final int RED = Color.rgb(229, 74, 88);
    private final int ORANGE = Color.rgb(244, 177, 72);
    private final int PURPLE = Color.rgb(169, 132, 255);

    private float scale = 1f;
    private float vw = 1280f;
    private float pointerX = 32f, pointerY = 32f;
    private boolean pointerVisible = false;
    private boolean startOpen = false;
    private boolean calendarOpen = false;
    private AppWindow dragging = null;
    private float dragDx, dragDy;
    private int settingsPage = 0;
    private int selectedFile = -1;
    private int terminalPage = 0;

    EneidaDesktopView(Context context) {
        super(context);
        setFocusable(true);
        setBackgroundColor(BG0);
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeWidth(1f);
        stroke.setColor(BORDER);
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        scale = getHeight() <= 0 ? 1f : getHeight() / H;
        vw = getWidth() / scale;
        canvas.save();
        canvas.scale(scale, scale);
        drawWallpaper(canvas);
        drawDesktopShortcuts(canvas);
        drawBrandPanel(canvas);
        drawWindows(canvas);
        drawTaskbar(canvas);
        if (startOpen) drawStartMenu(canvas);
        if (calendarOpen) drawCalendar(canvas);
        if (pointerVisible) drawCursor(canvas, pointerX, pointerY);
        canvas.restore();
        postInvalidateDelayed(1000);
    }

    private void drawWallpaper(Canvas c) {
        p.setShader(new LinearGradient(0, 0, vw, H, BG0, BG1, Shader.TileMode.CLAMP));
        c.drawRect(0, 0, vw, H, p);
        p.setShader(null);
        p.setColor(Color.argb(32, 80, 170, 255));
        c.drawCircle(vw * 0.82f, 120, 260, p);
        p.setColor(Color.argb(18, 101, 220, 199));
        c.drawCircle(vw * 0.66f, 585, 360, p);
        p.setColor(Color.argb(18, 255, 255, 255));
        for (int i = 0; i < 7; i++) c.drawCircle(vw * 0.48f + i * 58, 185 + (i % 2) * 16, 2, p);
    }

    private void drawDesktopShortcuts(Canvas c) {
        desktopIcon(c, 22, 28, "F", "FILES", ACCENT, AppType.FILES);
        desktopIcon(c, 22, 126, ">", "TERMINAL", ACCENT2, AppType.TERMINAL);
        desktopIcon(c, 22, 224, "S", "SETTINGS", PURPLE, AppType.SETTINGS);
        desktopIcon(c, 22, 322, "B", "BROWSER", ORANGE, AppType.BROWSER);
    }

    private void desktopIcon(Canvas c, float x, float y, String glyph, String label, int color, AppType type) {
        boolean active = findWindow(type) != null;
        round(c, x, y, x + 112, y + 86, active ? Color.argb(86, 80, 170, 255) : Color.argb(25, 255, 255, 255), 9);
        round(c, x + 12, y + 11, x + 54, y + 53, Color.argb(210, Color.red(color), Color.green(color), Color.blue(color)), 8);
        text(c, glyph, x + 33, y + 40, 18, BG0, true, Paint.Align.CENTER);
        text(c, label, x + 12, y + 72, 13, TEXT, true, Paint.Align.LEFT);
    }

    private void drawBrandPanel(Canvas c) {
        float w = 300;
        float x = Math.max(720, vw - w - 40);
        if (isCovered(new RectF(x, 74, x + w, 200))) return;
        round(c, x, 74, x + w, 200, Color.argb(88, 15, 29, 47), 14);
        text(c, "ENEIDA", x + 24, 116, 32, TEXT, true, Paint.Align.LEFT);
        text(c, "NATIVE SYSTEM", x + 25, 143, 14, ACCENT2, true, Paint.Align.LEFT);
        text(c, "ARMV7 / H3 / LOCAL FIRST", x + 25, 176, 12, MUTED, false, Paint.Align.LEFT);
    }

    private boolean isCovered(RectF area) {
        for (AppWindow w : windows) {
            if (!w.minimized && RectF.intersects(area, w.bounds)) return true;
        }
        return false;
    }

    private void drawWindows(Canvas c) {
        for (AppWindow w : windows) if (!w.minimized) drawWindow(c, w, w == topWindow());
    }

    private void drawWindow(Canvas c, AppWindow w, boolean active) {
        RectF b = w.bounds;
        p.setShadowLayer(active ? 18f : 8f, 0, 5, Color.argb(active ? 110 : 60, 0, 0, 0));
        setLayerType(LAYER_TYPE_SOFTWARE, p);
        round(c, b.left, b.top, b.right, b.bottom, PANEL, 10);
        p.clearShadowLayer();

        round(c, b.left, b.top, b.right, b.top + TITLE_H, active ? TITLE : Color.rgb(17, 28, 43), 10);
        p.setColor(active ? ACCENT : BORDER);
        c.drawRect(b.left, b.top, b.right, b.top + 2, p);
        drawAppGlyph(c, w.type, b.left + 21, b.top + 19, 12);
        text(c, titleFor(w.type), b.left + 42, b.top + 25, 14, active ? TEXT : MUTED, true, Paint.Align.LEFT);

        RectF min = minRect(w), max = maxRect(w), close = closeRect(w);
        drawWindowButton(c, min, "—", false);
        drawWindowButton(c, max, w.maximized ? "□" : "▢", false);
        drawWindowButton(c, close, "×", true);

        p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(1f); p.setColor(active ? Color.argb(170,80,170,255) : BORDER);
        c.drawRoundRect(b, 10, 10, p); p.setStyle(Paint.Style.FILL);

        c.save();
        c.clipRect(b.left + 1, b.top + TITLE_H, b.right - 1, b.bottom - 1);
        switch (w.type) {
            case FILES: drawFilesWindow(c, w); break;
            case TERMINAL: drawTerminalWindow(c, w); break;
            case SETTINGS: drawSettingsWindow(c, w); break;
            case BROWSER: drawBrowserWindow(c, w); break;
            case APPS: drawAppsWindow(c, w); break;
        }
        c.restore();
    }

    private void drawWindowButton(Canvas c, RectF r, String glyph, boolean close) {
        int base = close ? Color.argb(55, 229, 74, 88) : Color.argb(28, 255, 255, 255);
        round(c, r.left, r.top, r.right, r.bottom, base, 5);
        text(c, glyph, r.centerX(), r.centerY() + 5, 15, close ? Color.rgb(255,184,190) : MUTED, true, Paint.Align.CENTER);
    }

    private void drawFilesWindow(Canvas c, AppWindow w) {
        RectF b = w.bounds;
        float top = b.top + TITLE_H;
        float side = Math.min(145, Math.max(108, b.width() * 0.22f));
        p.setColor(Color.rgb(15, 27, 43)); c.drawRect(b.left, top, b.left + side, b.bottom, p);
        text(c, "FILES", b.left + 18, top + 34, 12, ACCENT, true, Paint.Align.LEFT);
        String[] nav = {"Root", "Documents", "Downloads", "System"};
        for (int i = 0; i < nav.length; i++) {
            if (i == 0) round(c, b.left + 10, top + 48 + i * 38, b.left + side - 10, top + 80 + i * 38, Color.argb(70, 80, 170, 255), 6);
            text(c, nav[i], b.left + 20, top + 70 + i * 38, 12, i == 0 ? TEXT : MUTED, i == 0, Paint.Align.LEFT);
        }

        float x = b.left + side;
        p.setColor(Color.rgb(22, 37, 57)); c.drawRect(x, top, b.right, top + 44, p);
        text(c, "ENEIDA /", x + 16, top + 28, 13, TEXT, true, Paint.Align.LEFT);
        text(c, "READ ONLY", b.right - 18, top + 28, 11, ACCENT2, true, Paint.Align.RIGHT);

        String[] names = {"Documents", "Downloads", "Screenshots", "boot.log", "system.info", "Eneida-notes.txt"};
        String[] meta = {"<DIR>", "<DIR>", "<DIR>", "18 KB", "4 KB", "2 KB"};
        float rowTop = top + 55;
        float rowH = Math.max(40, Math.min(53, (b.height() - TITLE_H - 70) / 6f));
        for (int i = 0; i < names.length; i++) {
            float y = rowTop + i * rowH;
            boolean sel = selectedFile == i;
            if (sel) round(c, x + 8, y, b.right - 8, y + rowH - 3, Color.rgb(34, 57, 86), 6);
            if (sel) { p.setColor(ACCENT); c.drawRect(x + 8, y + 7, x + 11, y + rowH - 10, p); }
            text(c, i < 3 ? "□" : "·", x + 24, y + rowH * .62f, 14, i < 3 ? ACCENT : MUTED, true, Paint.Align.CENTER);
            text(c, names[i], x + 44, y + rowH * .62f, 13, TEXT, sel, Paint.Align.LEFT);
            text(c, meta[i], b.right - 18, y + rowH * .62f, 11, MUTED, false, Paint.Align.RIGHT);
        }
        if (selectedFile >= 3 && b.height() > 360) {
            float py = b.bottom - 102;
            round(c, x + 12, py, b.right - 12, b.bottom - 12, Color.rgb(10, 20, 33), 7);
            text(c, "PREVIEW", x + 25, py + 24, 10, ACCENT2, true, Paint.Align.LEFT);
            String line = selectedFile == 3 ? "ENEIDA_ARMV7_DESKTOP_READY" : selectedFile == 4 ? "HEALTH   CORE OK" : "Eneida desktop prototype notes";
            text(c, line, x + 25, py + 51, 12, TEXT, false, Paint.Align.LEFT);
            text(c, "No storage writes are performed.", x + 25, py + 74, 10, MUTED, false, Paint.Align.LEFT);
        }
    }

    private void drawTerminalWindow(Canvas c, AppWindow w) {
        RectF b = w.bounds;
        float top = b.top + TITLE_H;
        p.setColor(Color.rgb(5, 11, 17)); c.drawRect(b.left, top, b.right, b.bottom, p);
        text(c, "ENEIDA TERMINAL", b.left + 20, top + 30, 12, ACCENT2, true, Paint.Align.LEFT);
        String[][] pages = {
                {"$ system", "kernel      ENEIDA NATIVE", "platform    ALLWINNER H3 / CORTEX-A7", "arch        ARMV7", "health      CORE OK", "timer       100 HZ"},
                {"$ storage", "device      MMC0 / MICROSD", "filesystem  FAT32", "access      READ ONLY", "root        READY"},
                {"$ network", "state       PASSIVE", "link        DOWN", "rx          QUALIFICATION MODE", "tx          DISABLED"},
                {"$ help", "system   storage   network", "files    date      clear", "Tap command tabs below."}
        };
        float y = top + 64;
        for (String line : pages[terminalPage]) {
            text(c, line, b.left + 22, y, 14, line.startsWith("$") ? ACCENT : TEXT, line.startsWith("$"), Paint.Align.LEFT);
            y += 28;
        }
        text(c, "_", b.left + 22, y + 8, 14, ACCENT2, true, Paint.Align.LEFT);
        String[] chips = {"SYSTEM", "STORAGE", "NETWORK", "HELP"};
        float chipW = Math.max(78, (b.width() - 50) / 4f);
        for (int i = 0; i < 4; i++) {
            float x = b.left + 14 + i * chipW;
            round(c, x, b.bottom - 48, x + chipW - 7, b.bottom - 14, i == terminalPage ? Color.rgb(43, 75, 108) : Color.rgb(19, 34, 52), 6);
            text(c, chips[i], x + (chipW - 7) / 2, b.bottom - 26, 10, i == terminalPage ? TEXT : MUTED, true, Paint.Align.CENTER);
        }
    }

    private void drawSettingsWindow(Canvas c, AppWindow w) {
        RectF b = w.bounds;
        float top = b.top + TITLE_H;
        float side = Math.min(170, Math.max(130, b.width() * .25f));
        p.setColor(Color.rgb(14, 26, 41)); c.drawRect(b.left, top, b.left + side, b.bottom, p);
        String[] tabs = {"SYSTEM", "DATE & TIME", "STORAGE", "USB", "NETWORK"};
        for (int i = 0; i < tabs.length; i++) {
            float y = top + 18 + i * 48;
            if (i == settingsPage) round(c, b.left + 10, y, b.left + side - 10, y + 36, Color.argb(85, 80, 170, 255), 6);
            text(c, tabs[i], b.left + 22, y + 23, 11, i == settingsPage ? TEXT : MUTED, i == settingsPage, Paint.Align.LEFT);
        }
        float x = b.left + side + 22;
        float right = b.right - 22;
        text(c, tabs[settingsPage], x, top + 35, 16, TEXT, true, Paint.Align.LEFT);
        text(c, settingsSubtitle(settingsPage), x, top + 57, 10, MUTED, false, Paint.Align.LEFT);
        float y = top + 83;
        switch (settingsPage) {
            case 0:
                infoRow(c, x, right, y, "CPU", "ALLWINNER H3 / CORTEX-A7", ACCENT); y += 47;
                infoRow(c, x, right, y, "ARCH", "ARMV7 / ENEIDA NATIVE", ACCENT2); y += 47;
                infoRow(c, x, right, y, "DISPLAY", "1280 × 720 / XRGB8888", PURPLE); y += 47;
                infoRow(c, x, right, y, "HEAP", "8 MIB / CORE OK", ACCENT2); y += 47;
                infoRow(c, x, right, y, "HEALTH", "CORE OK", ACCENT2);
                break;
            case 1:
                infoRow(c, x, right, y, "SYSTEM WALL CLOCK", now("yyyy-MM-dd  HH:mm:ss"), ACCENT); y += 47;
                infoRow(c, x, right, y, "SOURCE", "H3 RTC / TIMER0", ACCENT2); y += 47;
                infoRow(c, x, right, y, "MODE", "READ ONLY", ORANGE);
                break;
            case 2:
                infoRow(c, x, right, y, "DEVICE", "MMC0 / MICROSD", ACCENT); y += 47;
                infoRow(c, x, right, y, "FILESYSTEM", "FAT32 READY", ACCENT2); y += 47;
                infoRow(c, x, right, y, "ADDRESSING", "SDHC/SDXC BLOCK", MUTED); y += 47;
                infoRow(c, x, right, y, "ACCESS", "READ ONLY", ORANGE);
                break;
            case 3:
                infoRow(c, x, right, y, "OHCI HOSTS", "4", ACCENT); y += 47;
                infoRow(c, x, right, y, "HID DEVICES", "2", ACCENT2); y += 47;
                infoRow(c, x, right, y, "MOUSE", "READY", ACCENT2); y += 47;
                infoRow(c, x, right, y, "KEYBOARD", "READY", ACCENT2);
                break;
            case 4:
                infoRow(c, x, right, y, "STATE", "PASSIVE", MUTED); y += 47;
                infoRow(c, x, right, y, "LINK", "DOWN", ORANGE); y += 47;
                infoRow(c, x, right, y, "PARSER", "RX QUALIFICATION", ACCENT); y += 47;
                infoRow(c, x, right, y, "TX", "DISABLED", ORANGE);
                break;
        }
    }

    private String settingsSubtitle(int page) {
        switch (page) {
            case 0: return "Native Orange Pi system information";
            case 1: return "RTC anchored, timer advanced";
            case 2: return "Read-only storage status";
            case 3: return "USB host and HID status";
            default: return "Passive Ethernet diagnostics";
        }
    }

    private void infoRow(Canvas c, float left, float right, float y, String key, String value, int color) {
        round(c, left, y, right, y + 38, Color.rgb(24, 41, 62), 6);
        p.setColor(color); c.drawCircle(left + 14, y + 19, 4, p);
        text(c, key, left + 27, y + 24, 10, MUTED, true, Paint.Align.LEFT);
        text(c, value, right - 12, y + 24, 11, TEXT, true, Paint.Align.RIGHT);
    }

    private void drawBrowserWindow(Canvas c, AppWindow w) {
        RectF b = w.bounds;
        float top = b.top + TITLE_H;
        p.setColor(Color.rgb(245, 248, 252)); c.drawRect(b.left, top, b.right, b.bottom, p);
        round(c, b.left + 14, top + 12, b.right - 14, top + 46, Color.rgb(225, 232, 240), 8);
        text(c, "eneida://welcome", b.left + 28, top + 34, 12, Color.rgb(60, 70, 84), false, Paint.Align.LEFT);
        text(c, "ENEIDA", b.left + 26, top + 98, 28, Color.rgb(20, 30, 44), true, Paint.Align.LEFT);
        text(c, "Native browser shell", b.left + 27, top + 124, 12, Color.rgb(100, 113, 130), false, Paint.Align.LEFT);
        round(c, b.left + 26, top + 154, b.right - 26, top + 248, Color.rgb(232, 240, 248), 10);
        text(c, "LOCAL FIRST", b.left + 44, top + 188, 12, Color.rgb(42, 106, 165), true, Paint.Align.LEFT);
        text(c, "Browser rendering is simulated in this APK.", b.left + 44, top + 217, 12, Color.rgb(67, 80, 96), false, Paint.Align.LEFT);
    }

    private void drawAppsWindow(Canvas c, AppWindow w) {
        RectF b = w.bounds;
        float top = b.top + TITLE_H;
        String[] apps = {"Files", "Terminal", "Settings", "Browser"};
        int[] colors = {ACCENT, ACCENT2, PURPLE, ORANGE};
        for (int i = 0; i < 4; i++) {
            int col = i % 2; int row = i / 2;
            float x = b.left + 24 + col * (b.width() / 2f);
            float y = top + 26 + row * 105;
            round(c, x, y, x + 64, y + 64, colors[i], 12);
            text(c, apps[i].substring(0,1), x + 32, y + 42, 22, BG0, true, Paint.Align.CENTER);
            text(c, apps[i], x, y + 84, 12, TEXT, true, Paint.Align.LEFT);
        }
    }

    private void drawTaskbar(Canvas c) {
        float y = H - TASKBAR_H;
        p.setColor(Color.argb(244, 11, 22, 36)); c.drawRect(0, y, vw, H, p);
        p.setColor(Color.argb(150, 80, 170, 255)); c.drawRect(0, y, vw, y + 1.5f, p);

        round(c, 9, y + 7, 92, y + 46, startOpen ? Color.rgb(44, 75, 105) : Color.rgb(25, 43, 64), 7);
        text(c, "ENEIDA", 50, y + 31, 12, TEXT, true, Paint.Align.CENTER);

        float x = 104;
        for (AppWindow w : windows) {
            if (x + 132 > vw - 250) break;
            boolean active = !w.minimized && w == topWindow();
            round(c, x, y + 7, x + 126, y + 46, active ? Color.rgb(38, 66, 94) : Color.rgb(20, 36, 54), 6);
            drawAppGlyph(c, w.type, x + 18, y + 27, 10);
            text(c, shortTitle(w.type), x + 35, y + 31, 11, active ? TEXT : MUTED, active, Paint.Align.LEFT);
            if (active) { p.setColor(ACCENT); c.drawRect(x + 12, y + 44, x + 114, y + 46, p); }
            x += 133;
        }

        float clockX = vw - 104;
        round(c, clockX, y + 6, vw - 9, y + 47, calendarOpen ? Color.rgb(39, 67, 94) : Color.rgb(21, 37, 55), 6);
        text(c, now("HH:mm"), vw - 56, y + 25, 13, TEXT, true, Paint.Align.CENTER);
        text(c, now("dd MMM").toUpperCase(Locale.US), vw - 56, y + 40, 8, MUTED, true, Paint.Align.CENTER);
        text(c, "USB  NET", vw - 118, y + 31, 9, ACCENT2, true, Paint.Align.RIGHT);
    }

    private void drawStartMenu(Canvas c) {
        float bottom = H - TASKBAR_H - 8;
        float left = 10, width = 330, top = bottom - 390;
        round(c, left, top, left + width, bottom, Color.argb(250, 16, 29, 46), 13);
        p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(1); p.setColor(BORDER); c.drawRoundRect(new RectF(left, top, left + width, bottom), 13, 13, p); p.setStyle(Paint.Style.FILL);
        text(c, "ENEIDA", left + 24, top + 38, 18, TEXT, true, Paint.Align.LEFT);
        text(c, "NATIVE DESKTOP", left + 24, top + 58, 9, ACCENT2, true, Paint.Align.LEFT);
        round(c, left + 18, top + 78, left + width - 18, top + 114, Color.rgb(25, 42, 63), 7);
        text(c, "Search apps and files", left + 34, top + 101, 10, MUTED, false, Paint.Align.LEFT);
        String[] names = {"Files", "Terminal", "Settings", "Browser", "All apps"};
        AppType[] types = {AppType.FILES, AppType.TERMINAL, AppType.SETTINGS, AppType.BROWSER, AppType.APPS};
        int[] colors = {ACCENT, ACCENT2, PURPLE, ORANGE, MUTED};
        for (int i = 0; i < names.length; i++) {
            float y = top + 132 + i * 47;
            round(c, left + 16, y, left + width - 16, y + 39, Color.argb(26,255,255,255), 6);
            round(c, left + 27, y + 7, left + 52, y + 32, colors[i], 6);
            text(c, names[i].substring(0,1), left + 39.5f, y + 24, 10, BG0, true, Paint.Align.CENTER);
            text(c, names[i], left + 66, y + 24, 12, TEXT, true, Paint.Align.LEFT);
            if (findWindow(types[i]) != null) text(c, "OPEN", left + width - 32, y + 24, 8, ACCENT, true, Paint.Align.RIGHT);
        }
        text(c, "Prototype · Orange Pi UI", left + 24, bottom - 18, 9, MUTED, false, Paint.Align.LEFT);
    }

    private void drawCalendar(Canvas c) {
        float width = 294, height = 244;
        float right = vw - 10, bottom = H - TASKBAR_H - 8;
        float left = right - width, top = bottom - height;
        round(c, left, top, right, bottom, Color.argb(252, 17, 31, 49), 12);
        text(c, now("MMMM yyyy").toUpperCase(Locale.US), left + 18, top + 31, 14, TEXT, true, Paint.Align.LEFT);
        text(c, now("HH:mm:ss"), right - 18, top + 31, 12, ACCENT2, true, Paint.Align.RIGHT);
        String[] days = {"MON","TUE","WED","THU","FRI","SAT","SUN"};
        for (int i=0;i<7;i++) text(c, days[i], left + 28 + i*38.5f, top + 62, 8, MUTED, true, Paint.Align.CENTER);
        int day = Integer.parseInt(now("d"));
        int start = (day - 1) % 7;
        int shown = Math.max(1, day - start - 7);
        int n = shown;
        for (int row=0; row<4; row++) {
            for (int col=0; col<7; col++) {
                float cx = left + 28 + col*38.5f, cy = top + 89 + row*34;
                if (n == day) { p.setColor(ACCENT); c.drawCircle(cx, cy-4, 12, p); }
                text(c, String.valueOf(n), cx, cy, 9, n == day ? BG0 : TEXT, n == day, Paint.Align.CENTER);
                n++;
            }
        }
        text(c, "SYSTEM CLOCK · RTC + TIMER0", left + 18, bottom - 20, 9, MUTED, true, Paint.Align.LEFT);
    }

    private void drawCursor(Canvas c, float x, float y) {
        Path path = new Path();
        path.moveTo(x, y); path.lineTo(x, y + 19); path.lineTo(x + 5, y + 14); path.lineTo(x + 10, y + 23); path.lineTo(x + 14, y + 21); path.lineTo(x + 9, y + 12); path.lineTo(x + 17, y + 11); path.close();
        p.setColor(Color.WHITE); c.drawPath(path, p);
        p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(1); p.setColor(Color.BLACK); c.drawPath(path, p); p.setStyle(Paint.Style.FILL);
    }

    @Override public boolean onTouchEvent(MotionEvent event) {
        float x = event.getX() / scale;
        float y = event.getY() / scale;
        pointerX = x; pointerY = y; pointerVisible = true;

        if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
            if (calendarOpen) {
                RectF cal = new RectF(vw - 304, H - TASKBAR_H - 252, vw - 10, H - TASKBAR_H - 8);
                if (!cal.contains(x,y)) calendarOpen = false;
            }
            if (startOpen) {
                if (handleStartClick(x,y)) { invalidate(); return true; }
                RectF start = new RectF(10, H - TASKBAR_H - 398, 340, H - TASKBAR_H - 8);
                if (!start.contains(x,y)) startOpen = false;
            }
            if (handleTaskbarClick(x,y)) { invalidate(); return true; }
            if (handleWindowClick(x,y)) { invalidate(); return true; }
            if (handleDesktopClick(x,y)) { invalidate(); return true; }
            invalidate();
            return true;
        }
        if (event.getActionMasked() == MotionEvent.ACTION_MOVE && dragging != null) {
            float w = dragging.bounds.width(), h = dragging.bounds.height();
            float nx = clamp(x - dragDx, 0, Math.max(0, vw - w));
            float ny = clamp(y - dragDy, 0, Math.max(0, H - TASKBAR_H - h));
            dragging.bounds.set(nx, ny, nx + w, ny + h);
            invalidate(); return true;
        }
        if (event.getActionMasked() == MotionEvent.ACTION_UP || event.getActionMasked() == MotionEvent.ACTION_CANCEL) {
            dragging = null;
            invalidate(); return true;
        }
        return true;
    }

    private boolean handleTaskbarClick(float x, float y) {
        float ty = H - TASKBAR_H;
        if (y < ty) return false;
        if (x >= 9 && x <= 92) { startOpen = !startOpen; calendarOpen = false; return true; }
        if (x >= vw - 104) { calendarOpen = !calendarOpen; startOpen = false; return true; }
        float bx = 104;
        for (AppWindow w : new ArrayList<>(windows)) {
            if (bx + 132 > vw - 250) break;
            if (x >= bx && x <= bx + 126) {
                if (!w.minimized && w == topWindow()) w.minimized = true;
                else { w.minimized = false; focus(w); }
                startOpen = false; return true;
            }
            bx += 133;
        }
        return true;
    }

    private boolean handleStartClick(float x, float y) {
        float bottom = H - TASKBAR_H - 8, top = bottom - 390;
        float row0 = top + 132;
        if (x < 10 || x > 340) return false;
        for (int i=0;i<5;i++) {
            float ry = row0 + i*47;
            if (y >= ry && y <= ry+39) {
                AppType[] types = {AppType.FILES,AppType.TERMINAL,AppType.SETTINGS,AppType.BROWSER,AppType.APPS};
                open(types[i]); startOpen = false; return true;
            }
        }
        return false;
    }

    private boolean handleDesktopClick(float x, float y) {
        AppType[] types = {AppType.FILES,AppType.TERMINAL,AppType.SETTINGS,AppType.BROWSER};
        for (int i=0;i<4;i++) {
            float iy = 28 + i*98;
            if (x >= 22 && x <= 134 && y >= iy && y <= iy+86) { open(types[i]); return true; }
        }
        return false;
    }

    private boolean handleWindowClick(float x, float y) {
        for (int i = windows.size() - 1; i >= 0; i--) {
            AppWindow w = windows.get(i);
            if (w.minimized || !w.bounds.contains(x,y)) continue;
            focus(w);
            if (closeRect(w).contains(x,y)) { windows.remove(w); dragging = null; return true; }
            if (minRect(w).contains(x,y)) { w.minimized = true; dragging = null; return true; }
            if (maxRect(w).contains(x,y)) { toggleMaximize(w); return true; }
            if (y <= w.bounds.top + TITLE_H) {
                if (!w.maximized) { dragging = w; dragDx = x - w.bounds.left; dragDy = y - w.bounds.top; }
                return true;
            }
            handleWindowContentClick(w,x,y);
            return true;
        }
        return false;
    }

    private void handleWindowContentClick(AppWindow w, float x, float y) {
        float top = w.bounds.top + TITLE_H;
        if (w.type == AppType.FILES) {
            float side = Math.min(145, Math.max(108, w.bounds.width()*.22f));
            float rowTop = top + 55;
            float rowH = Math.max(40, Math.min(53, (w.bounds.height()-TITLE_H-70)/6f));
            if (x >= w.bounds.left + side) {
                int idx = (int)((y-rowTop)/rowH);
                if (idx >= 0 && idx < 6) selectedFile = idx;
            }
        } else if (w.type == AppType.TERMINAL) {
            if (y >= w.bounds.bottom-56) {
                float chipW = Math.max(78,(w.bounds.width()-50)/4f);
                int idx = (int)((x-(w.bounds.left+14))/chipW);
                if (idx >=0 && idx<4) terminalPage=idx;
            }
        } else if (w.type == AppType.SETTINGS) {
            float side = Math.min(170, Math.max(130, w.bounds.width()*.25f));
            if (x <= w.bounds.left + side) {
                int idx = (int)((y-(top+18))/48f);
                if (idx>=0 && idx<5) settingsPage=idx;
            }
        } else if (w.type == AppType.APPS) {
            float relX=x-w.bounds.left, relY=y-top;
            if (relY>=20 && relY<250) {
                int col = relX < w.bounds.width()/2f ? 0:1;
                int row = relY < 130 ? 0:1;
                int idx=row*2+col;
                AppType[] types={AppType.FILES,AppType.TERMINAL,AppType.SETTINGS,AppType.BROWSER};
                if(idx>=0&&idx<4) open(types[idx]);
            }
        }
    }

    private void open(AppType type) {
        AppWindow existing = findWindow(type);
        if (existing != null) { existing.minimized=false; focus(existing); return; }
        float ox = 160 + (windows.size()%4)*38;
        float oy = 70 + (windows.size()%3)*32;
        float ww, hh;
        switch (type) {
            case SETTINGS: ww=720; hh=470; break;
            case TERMINAL: ww=700; hh=430; break;
            case BROWSER: ww=760; hh=455; break;
            case APPS: ww=520; hh=360; break;
            default: ww=760; hh=500; break;
        }
        ww=Math.min(ww, vw-70); hh=Math.min(hh, H-TASKBAR_H-40);
        ox=Math.min(ox, Math.max(20,vw-ww-30)); oy=Math.min(oy, Math.max(20,H-TASKBAR_H-hh-20));
        AppWindow w=new AppWindow(type,new RectF(ox,oy,ox+ww,oy+hh));
        windows.add(w);
    }

    private AppWindow findWindow(AppType type) {
        for (AppWindow w:windows) if(w.type==type) return w;
        return null;
    }

    private void focus(AppWindow w) {
        windows.remove(w); windows.add(w);
    }

    private AppWindow topWindow() {
        for(int i=windows.size()-1;i>=0;i--) if(!windows.get(i).minimized) return windows.get(i);
        return null;
    }

    private void toggleMaximize(AppWindow w) {
        if (!w.maximized) {
            w.restore.set(w.bounds);
            w.bounds.set(6,6,vw-6,H-TASKBAR_H-6);
            w.maximized=true;
        } else {
            w.bounds.set(w.restore);
            w.maximized=false;
        }
        dragging=null;
    }

    private RectF minRect(AppWindow w) { return new RectF(w.bounds.right-111,w.bounds.top+5,w.bounds.right-77,w.bounds.top+33); }
    private RectF maxRect(AppWindow w) { return new RectF(w.bounds.right-74,w.bounds.top+5,w.bounds.right-40,w.bounds.top+33); }
    private RectF closeRect(AppWindow w) { return new RectF(w.bounds.right-37,w.bounds.top+5,w.bounds.right-5,w.bounds.top+33); }

    private void drawAppGlyph(Canvas c, AppType t, float cx, float cy, float size) {
        int color = t==AppType.FILES?ACCENT:t==AppType.TERMINAL?ACCENT2:t==AppType.SETTINGS?PURPLE:t==AppType.BROWSER?ORANGE:MUTED;
        round(c,cx-size,cy-size,cx+size,cy+size,color,4);
        text(c,t==AppType.TERMINAL?">":t==AppType.SETTINGS?"S":t==AppType.BROWSER?"B":t==AppType.APPS?"A":"F",cx,cy+4,size, BG0,true,Paint.Align.CENTER);
    }

    private String titleFor(AppType t) {
        switch(t){case FILES:return "Files";case TERMINAL:return "Terminal";case SETTINGS:return "Settings";case BROWSER:return "Browser";default:return "Apps";}
    }
    private String shortTitle(AppType t){return titleFor(t).toUpperCase(Locale.US);}

    private String now(String pattern) { return new SimpleDateFormat(pattern, Locale.US).format(new Date()); }

    private void round(Canvas c,float l,float t,float r,float b,int color,float radius){p.setColor(color);p.setStyle(Paint.Style.FILL);c.drawRoundRect(new RectF(l,t,r,b),radius,radius,p);}
    private void text(Canvas c,String s,float x,float y,float size,int color,boolean bold,Paint.Align align){p.setShader(null);p.setStyle(Paint.Style.FILL);p.setColor(color);p.setTextSize(size);p.setTextAlign(align);p.setTypeface(bold?Typeface.create(Typeface.DEFAULT,Typeface.BOLD):Typeface.create(Typeface.DEFAULT,Typeface.NORMAL));c.drawText(s,x,y,p);}
    private float clamp(float v,float min,float max){return Math.max(min,Math.min(max,v));}

    private static final class AppWindow {
        final AppType type;
        final RectF bounds;
        final RectF restore = new RectF();
        boolean minimized=false;
        boolean maximized=false;
        AppWindow(AppType type,RectF bounds){this.type=type;this.bounds=bounds;this.restore.set(bounds);}
    }
}
