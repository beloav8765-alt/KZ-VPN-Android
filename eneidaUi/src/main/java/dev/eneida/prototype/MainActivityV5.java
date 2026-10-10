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
import android.os.Bundle;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public final class MainActivityV5 extends Activity {
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        try {
            setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
            Window w = getWindow();
            w.setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN, WindowManager.LayoutParams.FLAG_FULLSCREEN);
            w.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            try {
                w.getDecorView().setSystemUiVisibility(
                        View.SYSTEM_UI_FLAG_FULLSCREEN |
                        View.SYSTEM_UI_FLAG_HIDE_NAVIGATION |
                        View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY |
                        View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN |
                        View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION |
                        View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
            } catch (Throwable ignored) {}
            setContentView(new EneidaDesktopV5View(this));
        } catch (Throwable t) {
            TextView v = new TextView(this);
            v.setBackgroundColor(Color.rgb(7, 14, 24));
            v.setTextColor(Color.WHITE);
            v.setTextSize(18f);
            v.setGravity(Gravity.CENTER);
            v.setPadding(48, 48, 48, 48);
            v.setText("ENEIDA DESKTOP V5\n\nStartup diagnostic\n" + t.getClass().getSimpleName() + "\n" + (t.getMessage() == null ? "No message" : t.getMessage()));
            setContentView(v);
        }
    }
}

final class EneidaDesktopV5View extends View {
    private enum AppType { FILES, TERMINAL, SETTINGS, BROWSER }

    private static final float H = 720f;
    private static final float TASKBAR_H = 56f;
    private static final float TITLE_H = 40f;

    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final List<AppWindow> windows = new ArrayList<>();

    private final int BG0 = Color.rgb(5, 13, 23);
    private final int BG1 = Color.rgb(9, 24, 39);
    private final int BG2 = Color.rgb(11, 33, 46);
    private final int PANEL = Color.rgb(17, 31, 48);
    private final int PANEL2 = Color.rgb(22, 39, 59);
    private final int TITLE = Color.rgb(19, 36, 56);
    private final int BORDER = Color.rgb(45, 67, 91);
    private final int TEXT = Color.rgb(238, 244, 250);
    private final int MUTED = Color.rgb(142, 160, 181);
    private final int ACCENT = Color.rgb(73, 163, 247);
    private final int ACCENT2 = Color.rgb(89, 213, 193);
    private final int RED = Color.rgb(222, 80, 92);
    private final int ORANGE = Color.rgb(239, 174, 72);
    private final int PURPLE = Color.rgb(163, 127, 245);

    private float scale = 1f;
    private float vw = 1280f;
    private boolean startOpen;
    private boolean calendarOpen;
    private AppWindow dragging;
    private float dragDx;
    private float dragDy;
    private int selectedFile = -1;
    private int settingsPage;
    private int terminalPage;
    private Throwable renderError;

    EneidaDesktopV5View(Context context) {
        super(context);
        setBackgroundColor(BG0);
        setFocusable(true);
        setClickable(true);
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int save = canvas.save();
        try {
            scale = getHeight() <= 0 ? 1f : getHeight() / H;
            if (scale <= 0f || Float.isNaN(scale) || Float.isInfinite(scale)) scale = 1f;
            vw = Math.max(960f, getWidth() / scale);
            canvas.scale(scale, scale);
            drawScene(canvas);
            renderError = null;
        } catch (Throwable t) {
            renderError = t;
        } finally {
            canvas.restoreToCount(save);
        }
        if (renderError != null) drawFailure(canvas, renderError);
        else postInvalidateDelayed(1000L);
    }

    private void drawScene(Canvas c) {
        drawWallpaper(c);
        drawShortcuts(c);
        drawBrand(c);
        for (AppWindow w : windows) if (!w.minimized) drawWindow(c, w, w == topWindow());
        drawTaskbar(c);
        if (startOpen) drawStartMenu(c);
        if (calendarOpen) drawCalendar(c);
    }

    private void drawFailure(Canvas c, Throwable t) {
        p.setShader(null); p.setStyle(Paint.Style.FILL); p.setColor(BG0);
        c.drawRect(0, 0, getWidth(), getHeight(), p);
        rawText(c, "ENEIDA DESKTOP V5", 46, 78, 28, Color.WHITE, true);
        rawText(c, "Renderer diagnostic - application stayed alive", 46, 118, 17, ACCENT2, false);
        rawText(c, t.getClass().getSimpleName(), 46, 166, 16, ORANGE, true);
        rawText(c, t.getMessage() == null ? "No message" : t.getMessage(), 46, 198, 14, Color.LTGRAY, false);
    }

    private void drawWallpaper(Canvas c) {
        p.setStyle(Paint.Style.FILL);
        p.setShader(new LinearGradient(0, 0, vw, H, BG0, BG1, Shader.TileMode.CLAMP));
        c.drawRect(0, 0, vw, H, p);
        p.setShader(null);

        p.setColor(Color.argb(20, 70, 160, 240));
        c.drawCircle(vw - 115, 112, 190, p);
        p.setColor(Color.argb(16, 89, 213, 193));
        c.drawCircle(vw * .69f, 570, 300, p);

        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(1f);
        p.setColor(Color.argb(11, 255, 255, 255));
        for (float x = 0; x < vw; x += 64) c.drawLine(x, 0, x, H - TASKBAR_H, p);
        for (float y = 0; y < H - TASKBAR_H; y += 64) c.drawLine(0, y, vw, y, p);
        p.setStyle(Paint.Style.FILL);
    }

    private void drawShortcuts(Canvas c) {
        shortcut(c, 24, 26, "FILES", AppType.FILES, ACCENT);
        shortcut(c, 24, 116, "TERMINAL", AppType.TERMINAL, ACCENT2);
        shortcut(c, 24, 206, "SETTINGS", AppType.SETTINGS, PURPLE);
        shortcut(c, 24, 296, "BROWSER", AppType.BROWSER, ORANGE);
    }

    private void shortcut(Canvas c, float x, float y, String label, AppType type, int color) {
        boolean open = findWindow(type) != null;
        round(c, x, y, x + 104, y + 74, open ? Color.argb(74, 73, 163, 247) : Color.argb(22, 255, 255, 255), 10);
        if (open) { p.setColor(ACCENT); c.drawRect(x, y + 12, x + 3, y + 62, p); }
        drawAppIcon(c, type, x + 14, y + 10, 36, color, false);
        text(c, label, x + 14, y + 62, 11, TEXT, true, Paint.Align.LEFT);
    }

    private void drawBrand(Canvas c) {
        float w = 270;
        float x = Math.max(670f, vw - w - 34f);
        RectF r = new RectF(x, 70, x + w, 174);
        if (isCovered(r)) return;
        round(c, r.left, r.top, r.right, r.bottom, Color.argb(105, 15, 30, 48), 14);
        p.setColor(ACCENT); c.drawRect(r.left, r.top, r.left + 3, r.bottom, p);
        text(c, "ENEIDA", x + 24, 108, 27, TEXT, true, Paint.Align.LEFT);
        text(c, "NATIVE SYSTEM", x + 25, 134, 12, ACCENT2, true, Paint.Align.LEFT);
        text(c, "ARMV7 / H3 / LOCAL FIRST", x + 25, 157, 10, MUTED, false, Paint.Align.LEFT);
    }

    private boolean isCovered(RectF area) {
        for (AppWindow w : windows) if (!w.minimized && RectF.intersects(area, w.bounds)) return true;
        return false;
    }

    private void drawWindow(Canvas c, AppWindow w, boolean active) {
        RectF b = w.bounds;
        round(c, b.left, b.top, b.right, b.bottom, PANEL, 11);
        round(c, b.left, b.top, b.right, b.top + TITLE_H, active ? TITLE : Color.rgb(15, 28, 43), 11);
        p.setColor(active ? ACCENT : BORDER); c.drawRect(b.left, b.top, b.right, b.top + 2, p);
        drawAppIcon(c, w.type, b.left + 12, b.top + 9, 22, colorFor(w.type), true);
        text(c, title(w.type), b.left + 42, b.top + 27, 14, active ? TEXT : MUTED, true, Paint.Align.LEFT);
        drawWindowButton(c, minRect(w), 0, false);
        drawWindowButton(c, maxRect(w), 1, false);
        drawWindowButton(c, closeRect(w), 2, true);
        p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(1f); p.setColor(active ? Color.argb(185, 73, 163, 247) : BORDER);
        c.drawRoundRect(b, 11, 11, p); p.setStyle(Paint.Style.FILL);

        int save = c.save();
        c.clipRect(b.left + 1, b.top + TITLE_H, b.right - 1, b.bottom - 1);
        if (w.type == AppType.FILES) drawFiles(c, w);
        else if (w.type == AppType.TERMINAL) drawTerminal(c, w);
        else if (w.type == AppType.SETTINGS) drawSettings(c, w);
        else drawBrowser(c, w);
        c.restoreToCount(save);
    }

    private void drawWindowButton(Canvas c, RectF r, int kind, boolean close) {
        round(c, r.left, r.top, r.right, r.bottom, close ? Color.argb(48, 222, 80, 92) : Color.argb(24, 255, 255, 255), 5);
        p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(1.5f); p.setColor(close ? Color.rgb(255, 180, 188) : MUTED);
        if (kind == 0) c.drawLine(r.left + 9, r.centerY() + 5, r.right - 9, r.centerY() + 5, p);
        else if (kind == 1) c.drawRect(r.left + 10, r.top + 8, r.right - 10, r.bottom - 8, p);
        else {
            c.drawLine(r.left + 10, r.top + 8, r.right - 10, r.bottom - 8, p);
            c.drawLine(r.right - 10, r.top + 8, r.left + 10, r.bottom - 8, p);
        }
        p.setStyle(Paint.Style.FILL);
    }

    private void drawFiles(Canvas c, AppWindow w) {
        RectF b = w.bounds;
        float top = b.top + TITLE_H;
        float side = Math.min(154, Math.max(122, b.width() * .215f));
        p.setColor(Color.rgb(13, 25, 39)); c.drawRect(b.left, top, b.left + side, b.bottom, p);

        text(c, "FILES", b.left + 18, top + 28, 10, ACCENT, true, Paint.Align.LEFT);
        String[] nav = {"Root", "Documents", "Downloads", "System"};
        for (int i = 0; i < nav.length; i++) {
            float y = top + 44 + i * 38;
            if (i == 0) round(c, b.left + 10, y, b.left + side - 10, y + 30, Color.argb(74, 73, 163, 247), 6);
            text(c, nav[i], b.left + 20, y + 20, 11, i == 0 ? TEXT : MUTED, i == 0, Paint.Align.LEFT);
        }

        float x = b.left + side;
        p.setColor(Color.rgb(21, 37, 56)); c.drawRect(x, top, b.right, top + 46, p);
        round(c, x + 12, top + 8, Math.min(b.right - 110, x + 330), top + 38, Color.rgb(15, 28, 43), 6);
        text(c, "ENEIDA  /", x + 25, top + 28, 11, TEXT, true, Paint.Align.LEFT);
        text(c, "READ ONLY", b.right - 18, top + 28, 9, ACCENT2, true, Paint.Align.RIGHT);

        String[] names = {"Documents", "Downloads", "Screenshots", "boot.log", "system.info", "Eneida-notes.txt"};
        String[] meta = {"Folder", "Folder", "Folder", "18 KB", "4 KB", "2 KB"};
        float rowTop = top + 54;
        float rowH = Math.max(41, Math.min(48, (b.height() - TITLE_H - 66) / 6f));
        for (int i = 0; i < names.length; i++) {
            float y = rowTop + i * rowH;
            boolean sel = selectedFile == i;
            if (sel) round(c, x + 8, y, b.right - 8, y + rowH - 3, Color.rgb(31, 53, 79), 6);
            if (sel) { p.setColor(ACCENT); c.drawRect(x + 8, y + 7, x + 11, y + rowH - 10, p); }
            drawFileGlyph(c, x + 20, y + 11, i < 3, sel ? ACCENT : (i < 3 ? ACCENT : MUTED));
            text(c, names[i], x + 47, y + 27, 12, TEXT, sel, Paint.Align.LEFT);
            text(c, meta[i], b.right - 18, y + 27, 10, MUTED, false, Paint.Align.RIGHT);
        }
    }

    private void drawTerminal(Canvas c, AppWindow w) {
        RectF b = w.bounds; float top = b.top + TITLE_H;
        p.setColor(Color.rgb(4, 10, 16)); c.drawRect(b.left, top, b.right, b.bottom, p);
        text(c, "ENEIDA TERMINAL", b.left + 20, top + 30, 11, ACCENT2, true, Paint.Align.LEFT);
        String[][] pages = {
                {"$ system", "kernel      ENEIDA NATIVE", "platform    ALLWINNER H3 / CORTEX-A7", "arch        ARMV7", "health      CORE OK", "timer       100 HZ"},
                {"$ storage", "device      MMC0 / MICROSD", "filesystem  FAT32", "access      READ ONLY", "root        READY"},
                {"$ network", "state       PASSIVE", "link        DOWN", "rx          QUALIFICATION MODE", "tx          DISABLED"},
                {"$ help", "system   storage   network", "files    date      clear", "Prototype command tabs"}
        };
        float y = top + 64;
        for (String line : pages[terminalPage]) { text(c, line, b.left + 22, y, 13, line.startsWith("$") ? ACCENT : TEXT, line.startsWith("$"), Paint.Align.LEFT); y += 27; }
        text(c, "_", b.left + 22, y + 6, 13, ACCENT2, true, Paint.Align.LEFT);
        String[] chips = {"SYSTEM", "STORAGE", "NETWORK", "HELP"};
        float chipW = Math.max(82, (b.width() - 50) / 4f);
        for (int i = 0; i < 4; i++) {
            float cx = b.left + 14 + i * chipW;
            round(c, cx, b.bottom - 48, cx + chipW - 7, b.bottom - 14, i == terminalPage ? Color.rgb(39, 69, 99) : Color.rgb(17, 32, 48), 6);
            text(c, chips[i], cx + (chipW - 7) / 2, b.bottom - 26, 10, i == terminalPage ? TEXT : MUTED, true, Paint.Align.CENTER);
        }
    }

    private void drawSettings(Canvas c, AppWindow w) {
        RectF b = w.bounds; float top = b.top + TITLE_H;
        float side = Math.min(176, Math.max(138, b.width() * .245f));
        p.setColor(Color.rgb(13, 25, 39)); c.drawRect(b.left, top, b.left + side, b.bottom, p);
        String[] tabs = {"SYSTEM", "DATE & TIME", "STORAGE", "USB", "NETWORK"};
        for (int i = 0; i < tabs.length; i++) {
            float y = top + 18 + i * 46;
            if (i == settingsPage) round(c, b.left + 10, y, b.left + side - 10, y + 34, Color.argb(74, 73, 163, 247), 6);
            text(c, tabs[i], b.left + 22, y + 22, 10, i == settingsPage ? TEXT : MUTED, i == settingsPage, Paint.Align.LEFT);
        }
        float x = b.left + side + 22, right = b.right - 22, y = top + 82;
        text(c, tabs[settingsPage], x, top + 33, 15, TEXT, true, Paint.Align.LEFT);
        text(c, settingsSubtitle(settingsPage), x, top + 54, 10, MUTED, false, Paint.Align.LEFT);
        if (settingsPage == 0) {
            info(c, x, right, y, "CPU", "ALLWINNER H3 / CORTEX-A7", ACCENT); y += 45;
            info(c, x, right, y, "ARCH", "ARMV7 / ENEIDA NATIVE", ACCENT2); y += 45;
            info(c, x, right, y, "DISPLAY", "1280 x 720 / XRGB8888", PURPLE); y += 45;
            info(c, x, right, y, "HEALTH", "CORE OK", ACCENT2);
        } else if (settingsPage == 1) {
            info(c, x, right, y, "SYSTEM WALL CLOCK", now("yyyy-MM-dd  HH:mm:ss"), ACCENT); y += 45;
            info(c, x, right, y, "SOURCE", "H3 RTC / TIMER0", ACCENT2); y += 45;
            info(c, x, right, y, "MODE", "READ ONLY", ORANGE);
        } else if (settingsPage == 2) {
            info(c, x, right, y, "DEVICE", "MMC0 / MICROSD", ACCENT); y += 45;
            info(c, x, right, y, "FILESYSTEM", "FAT32 READY", ACCENT2); y += 45;
            info(c, x, right, y, "ACCESS", "READ ONLY", ORANGE);
        } else if (settingsPage == 3) {
            info(c, x, right, y, "OHCI HOSTS", "4", ACCENT); y += 45;
            info(c, x, right, y, "HID DEVICES", "2", ACCENT2); y += 45;
            info(c, x, right, y, "MOUSE / KEYBOARD", "READY", ACCENT2);
        } else {
            info(c, x, right, y, "STATE", "PASSIVE", MUTED); y += 45;
            info(c, x, right, y, "LINK", "DOWN", ORANGE); y += 45;
            info(c, x, right, y, "TX", "DISABLED", ORANGE);
        }
    }

    private String settingsSubtitle(int page) {
        if (page == 0) return "Native Orange Pi system information";
        if (page == 1) return "RTC anchored, timer advanced";
        if (page == 2) return "Read-only storage status";
        if (page == 3) return "USB host and HID status";
        return "Passive Ethernet diagnostics";
    }

    private void info(Canvas c, float left, float right, float y, String key, String value, int color) {
        round(c, left, y, right, y + 37, Color.rgb(23, 40, 60), 6);
        p.setColor(color); c.drawCircle(left + 14, y + 18.5f, 3.5f, p);
        text(c, key, left + 27, y + 23, 10, MUTED, true, Paint.Align.LEFT);
        text(c, value, right - 12, y + 23, 10, TEXT, true, Paint.Align.RIGHT);
    }

    private void drawBrowser(Canvas c, AppWindow w) {
        RectF b = w.bounds; float top = b.top + TITLE_H;
        p.setColor(Color.rgb(242, 246, 250)); c.drawRect(b.left, top, b.right, b.bottom, p);
        round(c, b.left + 14, top + 12, b.right - 14, top + 44, Color.rgb(225, 232, 240), 7);
        text(c, "eneida://welcome", b.left + 28, top + 33, 11, Color.rgb(65, 76, 91), false, Paint.Align.LEFT);
        text(c, "ENEIDA", b.left + 28, top + 95, 27, Color.rgb(20, 30, 44), true, Paint.Align.LEFT);
        text(c, "Native browser shell", b.left + 29, top + 120, 11, Color.rgb(98, 111, 128), false, Paint.Align.LEFT);
        round(c, b.left + 28, top + 150, b.right - 28, top + 232, Color.rgb(230, 238, 246), 10);
        text(c, "LOCAL FIRST", b.left + 46, top + 183, 11, Color.rgb(42, 106, 165), true, Paint.Align.LEFT);
        text(c, "Browser rendering is simulated in this APK.", b.left + 46, top + 210, 11, Color.rgb(67, 80, 96), false, Paint.Align.LEFT);
    }

    private void drawTaskbar(Canvas c) {
        float y = H - TASKBAR_H;
        p.setColor(Color.argb(248, 9, 20, 32)); c.drawRect(0, y, vw, H, p);
        p.setColor(Color.argb(165, 73, 163, 247)); c.drawRect(0, y, vw, y + 1.5f, p);

        round(c, 10, y + 8, 94, y + 48, startOpen ? Color.rgb(42, 71, 101) : Color.rgb(21, 38, 57), 8);
        drawELogo(c, 21, y + 15, 26);
        text(c, "ENEIDA", 58, y + 33, 10, TEXT, true, Paint.Align.LEFT);

        float x = 105;
        for (AppWindow w : windows) {
            if (x + 126 > vw - 245) break;
            boolean active = !w.minimized && w == topWindow();
            round(c, x, y + 8, x + 122, y + 48, active ? Color.rgb(34, 61, 88) : Color.rgb(17, 32, 48), 7);
            drawAppIcon(c, w.type, x + 9, y + 15, 25, colorFor(w.type), true);
            text(c, shortTitle(w.type), x + 42, y + 33, 10, active ? TEXT : MUTED, active, Paint.Align.LEFT);
            if (active) { p.setColor(ACCENT); c.drawRect(x + 10, y + 46, x + 112, y + 48, p); }
            x += 128;
        }

        float trayRight = vw - 10;
        float clockLeft = trayRight - 92;
        round(c, clockLeft, y + 7, trayRight, y + 49, calendarOpen ? Color.rgb(35, 63, 91) : Color.rgb(17, 32, 48), 7);
        text(c, now("HH:mm"), clockLeft + 46, y + 25, 12, TEXT, true, Paint.Align.CENTER);
        text(c, now("dd MMM").toUpperCase(Locale.US), clockLeft + 46, y + 40, 8, MUTED, true, Paint.Align.CENTER);
        drawTrayIcons(c, clockLeft - 62, y + 18);
    }

    private void drawTrayIcons(Canvas c, float x, float y) {
        p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(1.4f); p.setColor(ACCENT2);
        c.drawRect(x, y + 7, x + 11, y + 17, p); c.drawLine(x + 3, y + 4, x + 3, y + 8, p); c.drawLine(x + 8, y + 4, x + 8, y + 8, p);
        c.drawLine(x + 22, y + 15, x + 26, y + 11, p); c.drawLine(x + 26, y + 11, x + 30, y + 15, p); c.drawLine(x + 24, y + 17, x + 28, y + 17, p);
        p.setStyle(Paint.Style.FILL);
    }

    private void drawStartMenu(Canvas c) {
        float bottom = H - TASKBAR_H - 9;
        float left = 10, width = 354, top = bottom - 410;
        round(c, left, top, left + width, bottom, Color.argb(252, 15, 28, 44), 14);
        p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(1f); p.setColor(BORDER); c.drawRoundRect(new RectF(left, top, left + width, bottom), 14, 14, p); p.setStyle(Paint.Style.FILL);
        drawELogo(c, left + 20, top + 20, 32);
        text(c, "ENEIDA", left + 65, top + 39, 17, TEXT, true, Paint.Align.LEFT);
        text(c, "NATIVE DESKTOP", left + 65, top + 57, 9, ACCENT2, true, Paint.Align.LEFT);

        round(c, left + 18, top + 76, left + width - 18, top + 112, Color.rgb(22, 39, 58), 8);
        drawSearch(c, left + 31, top + 88);
        text(c, "Search apps and files", left + 54, top + 99, 10, MUTED, false, Paint.Align.LEFT);
        text(c, "PINNED", left + 20, top + 139, 9, MUTED, true, Paint.Align.LEFT);

        String[] names = {"Files", "Terminal", "Settings", "Browser"};
        AppType[] types = {AppType.FILES, AppType.TERMINAL, AppType.SETTINGS, AppType.BROWSER};
        for (int i = 0; i < 4; i++) {
            float ry = top + 153 + i * 49;
            round(c, left + 16, ry, left + width - 16, ry + 40, Color.argb(25, 255, 255, 255), 7);
            drawAppIcon(c, types[i], left + 27, ry + 8, 24, colorFor(types[i]), false);
            text(c, names[i], left + 64, ry + 25, 11, TEXT, true, Paint.Align.LEFT);
            if (findWindow(types[i]) != null) text(c, "OPEN", left + width - 32, ry + 25, 8, ACCENT, true, Paint.Align.RIGHT);
        }

        p.setColor(BORDER); c.drawRect(left + 18, bottom - 58, left + width - 18, bottom - 57, p);
        text(c, "Orange Pi PC  |  ARMV7", left + 22, bottom - 35, 9, MUTED, true, Paint.Align.LEFT);
        text(c, "CORE OK", left + width - 22, bottom - 35, 9, ACCENT2, true, Paint.Align.RIGHT);
        text(c, "Prototype interface", left + 22, bottom - 17, 8, MUTED, false, Paint.Align.LEFT);
    }

    private void drawCalendar(Canvas c) {
        float width = 286, height = 236;
        float right = vw - 10, bottom = H - TASKBAR_H - 9;
        float left = right - width, top = bottom - height;
        round(c, left, top, right, bottom, Color.argb(252, 16, 30, 47), 12);
        text(c, now("MMMM yyyy").toUpperCase(Locale.US), left + 18, top + 31, 13, TEXT, true, Paint.Align.LEFT);
        text(c, now("HH:mm:ss"), right - 18, top + 31, 11, ACCENT2, true, Paint.Align.RIGHT);
        String[] days = {"MON","TUE","WED","THU","FRI","SAT","SUN"};
        for (int i = 0; i < 7; i++) text(c, days[i], left + 27 + i * 38f, top + 61, 8, MUTED, true, Paint.Align.CENTER);
        int day;
        try { day = Integer.parseInt(now("d")); } catch (Throwable ignored) { day = 1; }
        int start = (day - 1) % 7;
        int n = Math.max(1, day - start - 7);
        for (int row = 0; row < 4; row++) for (int col = 0; col < 7; col++) {
            float cx = left + 27 + col * 38f, cy = top + 89 + row * 34;
            if (n == day) { p.setColor(ACCENT); c.drawCircle(cx, cy - 4, 12, p); }
            text(c, String.valueOf(n), cx, cy, 9, n == day ? BG0 : TEXT, n == day, Paint.Align.CENTER); n++;
        }
        text(c, "SYSTEM CLOCK  |  RTC + TIMER0", left + 18, bottom - 19, 8, MUTED, true, Paint.Align.LEFT);
    }

    @Override public boolean onTouchEvent(MotionEvent e) {
        float x = e.getX() / Math.max(.001f, scale);
        float y = e.getY() / Math.max(.001f, scale);
        if (e.getActionMasked() == MotionEvent.ACTION_DOWN) return onDown(x, y);
        if (e.getActionMasked() == MotionEvent.ACTION_MOVE) { if (dragging != null) { moveDragged(x, y); invalidate(); } return true; }
        if (e.getActionMasked() == MotionEvent.ACTION_UP || e.getActionMasked() == MotionEvent.ACTION_CANCEL) { dragging = null; return true; }
        return true;
    }

    private boolean onDown(float x, float y) {
        if (calendarOpen) {
            RectF r = new RectF(vw - 296, H - TASKBAR_H - 245, vw - 10, H - TASKBAR_H - 9);
            if (!r.contains(x, y)) calendarOpen = false;
        }
        if (startOpen && handleStart(x, y)) return true;
        if (y >= H - TASKBAR_H) {
            if (x <= 98) { startOpen = !startOpen; calendarOpen = false; invalidate(); return true; }
            if (x >= vw - 104) { calendarOpen = !calendarOpen; startOpen = false; invalidate(); return true; }
            if (handleTaskbar(x)) return true;
        }
        if (!startOpen && !calendarOpen) {
            AppType shortcutType = shortcutAt(x, y);
            if (shortcutType != null) { open(shortcutType); return true; }
        }
        for (int i = windows.size() - 1; i >= 0; i--) {
            AppWindow w = windows.get(i);
            if (w.minimized || !w.bounds.contains(x, y)) continue;
            bringToFront(w);
            if (closeRect(w).contains(x, y)) { windows.remove(w); invalidate(); return true; }
            if (maxRect(w).contains(x, y)) { toggleMaximize(w); invalidate(); return true; }
            if (minRect(w).contains(x, y)) { w.minimized = true; invalidate(); return true; }
            if (y <= w.bounds.top + TITLE_H) {
                dragging = w; dragDx = x - w.bounds.left; dragDy = y - w.bounds.top; invalidate(); return true;
            }
            handleWindowBody(w, x, y); invalidate(); return true;
        }
        startOpen = false; calendarOpen = false; invalidate(); return true;
    }

    private boolean handleStart(float x, float y) {
        float bottom = H - TASKBAR_H - 9, top = bottom - 410;
        if (x < 10 || x > 364 || y < top || y > bottom) { startOpen = false; invalidate(); return false; }
        AppType[] types = {AppType.FILES, AppType.TERMINAL, AppType.SETTINGS, AppType.BROWSER};
        for (int i = 0; i < 4; i++) {
            float row = top + 153 + i * 49;
            if (y >= row && y <= row + 40) { open(types[i]); startOpen = false; return true; }
        }
        return true;
    }

    private boolean handleTaskbar(float x) {
        float pos = 105;
        for (AppWindow w : windows) {
            if (pos + 122 > vw - 245) break;
            if (x >= pos && x <= pos + 122) {
                if (w.minimized) w.minimized = false; else if (w == topWindow()) w.minimized = true;
                bringToFront(w); invalidate(); return true;
            }
            pos += 128;
        }
        return false;
    }

    private void handleWindowBody(AppWindow w, float x, float y) {
        if (w.type == AppType.FILES) {
            float top = w.bounds.top + TITLE_H;
            float side = Math.min(154, Math.max(122, w.bounds.width() * .215f));
            if (x > w.bounds.left + side && y > top + 54) {
                float rowH = Math.max(41, Math.min(48, (w.bounds.height() - TITLE_H - 66) / 6f));
                int row = (int)((y - (top + 54)) / rowH);
                if (row >= 0 && row < 6) selectedFile = row;
            }
        } else if (w.type == AppType.SETTINGS) {
            float top = w.bounds.top + TITLE_H;
            float side = Math.min(176, Math.max(138, w.bounds.width() * .245f));
            if (x <= w.bounds.left + side) {
                int row = (int)((y - (top + 18)) / 46f);
                if (row >= 0 && row < 5) settingsPage = row;
            }
        } else if (w.type == AppType.TERMINAL && y >= w.bounds.bottom - 58) {
            float chipW = Math.max(82, (w.bounds.width() - 50) / 4f);
            int row = (int)((x - (w.bounds.left + 14)) / chipW);
            if (row >= 0 && row < 4) terminalPage = row;
        }
    }

    private AppType shortcutAt(float x, float y) {
        if (x < 24 || x > 128) return null;
        if (y >= 26 && y <= 100) return AppType.FILES;
        if (y >= 116 && y <= 190) return AppType.TERMINAL;
        if (y >= 206 && y <= 280) return AppType.SETTINGS;
        if (y >= 296 && y <= 370) return AppType.BROWSER;
        return null;
    }

    private void open(AppType type) {
        AppWindow e = findWindow(type);
        if (e != null) { e.minimized = false; bringToFront(e); invalidate(); return; }
        float off = windows.size() * 22f;
        float right = Math.max(850, vw - 72);
        RectF r;
        if (type == AppType.FILES) r = new RectF(170 + off, 68 + off, Math.min(right, 950 + off), 588 + off / 2);
        else if (type == AppType.SETTINGS) r = new RectF(210 + off, 78 + off, Math.min(right, 960 + off), 590 + off / 2);
        else if (type == AppType.TERMINAL) r = new RectF(245 + off, 105 + off, Math.min(right, 900 + off), 545 + off / 2);
        else r = new RectF(220 + off, 88 + off, Math.min(right, 970 + off), 570 + off / 2);
        windows.add(new AppWindow(type, r)); invalidate();
    }

    private void bringToFront(AppWindow w) { windows.remove(w); windows.add(w); }

    private void moveDragged(float x, float y) {
        if (dragging == null || dragging.maximized) return;
        float ww = dragging.bounds.width(), hh = dragging.bounds.height();
        float l = clamp(x - dragDx, 0, Math.max(0, vw - ww));
        float t = clamp(y - dragDy, 0, Math.max(0, H - TASKBAR_H - TITLE_H));
        dragging.bounds.set(l, t, l + ww, Math.min(H - TASKBAR_H, t + hh));
    }

    private void toggleMaximize(AppWindow w) {
        if (!w.maximized) { w.restore.set(w.bounds); w.bounds.set(8, 8, vw - 8, H - TASKBAR_H - 7); w.maximized = true; }
        else { w.bounds.set(w.restore); w.maximized = false; }
    }

    private AppWindow findWindow(AppType type) { for (AppWindow w : windows) if (w.type == type) return w; return null; }
    private AppWindow topWindow() { for (int i = windows.size() - 1; i >= 0; i--) if (!windows.get(i).minimized) return windows.get(i); return null; }
    private RectF closeRect(AppWindow w) { return new RectF(w.bounds.right - 42, w.bounds.top + 7, w.bounds.right - 8, w.bounds.top + 33); }
    private RectF maxRect(AppWindow w) { return new RectF(w.bounds.right - 80, w.bounds.top + 7, w.bounds.right - 46, w.bounds.top + 33); }
    private RectF minRect(AppWindow w) { return new RectF(w.bounds.right - 118, w.bounds.top + 7, w.bounds.right - 84, w.bounds.top + 33); }

    private void drawAppIcon(Canvas c, AppType type, float x, float y, float s, int color, boolean compact) {
        round(c, x, y, x + s, y + s, Color.argb(compact ? 185 : 235, Color.red(color), Color.green(color), Color.blue(color)), Math.max(5, s * .2f));
        p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(Math.max(1.4f, s * .055f)); p.setColor(BG0);
        float l = x + s * .23f, t = y + s * .25f, r = x + s * .77f, b = y + s * .75f;
        if (type == AppType.FILES) {
            Path path = new Path(); path.moveTo(l, t + s * .12f); path.lineTo(l + s * .18f, t + s * .12f); path.lineTo(l + s * .25f, t); path.lineTo(r, t); path.lineTo(r, b); path.lineTo(l, b); path.close(); c.drawPath(path, p);
        } else if (type == AppType.TERMINAL) {
            c.drawLine(l, t + s * .12f, l + s * .15f, t + s * .25f, p); c.drawLine(l + s * .15f, t + s * .25f, l, t + s * .38f, p); c.drawLine(l + s * .23f, t + s * .38f, r, t + s * .38f, p);
        } else if (type == AppType.SETTINGS) {
            c.drawCircle(x + s * .5f, y + s * .5f, s * .17f, p); c.drawCircle(x + s * .5f, y + s * .5f, s * .32f, p);
            c.drawLine(x + s * .5f, y + s * .14f, x + s * .5f, y + s * .26f, p); c.drawLine(x + s * .5f, y + s * .74f, x + s * .5f, y + s * .86f, p); c.drawLine(x + s * .14f, y + s * .5f, x + s * .26f, y + s * .5f, p); c.drawLine(x + s * .74f, y + s * .5f, x + s * .86f, y + s * .5f, p);
        } else {
            c.drawCircle(x + s * .5f, y + s * .5f, s * .28f, p); c.drawOval(new RectF(x + s * .38f, y + s * .22f, x + s * .62f, y + s * .78f), p); c.drawLine(x + s * .23f, y + s * .5f, x + s * .77f, y + s * .5f, p);
        }
        p.setStyle(Paint.Style.FILL);
    }

    private void drawFileGlyph(Canvas c, float x, float y, boolean folder, int color) {
        p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(1.4f); p.setColor(color);
        if (folder) {
            Path path = new Path(); path.moveTo(x, y + 5); path.lineTo(x + 7, y + 5); path.lineTo(x + 10, y + 2); path.lineTo(x + 19, y + 2); path.lineTo(x + 19, y + 16); path.lineTo(x, y + 16); path.close(); c.drawPath(path, p);
        } else { c.drawRect(x + 2, y, x + 16, y + 18, p); c.drawLine(x + 11, y, x + 16, y + 5, p); }
        p.setStyle(Paint.Style.FILL);
    }

    private void drawELogo(Canvas c, float x, float y, float s) {
        round(c, x, y, x + s, y + s, ACCENT, s * .2f);
        p.setColor(BG0); float l = x + s * .27f, r = x + s * .73f;
        c.drawRect(l, y + s * .25f, r, y + s * .33f, p); c.drawRect(l, y + s * .46f, r - s * .09f, y + s * .54f, p); c.drawRect(l, y + s * .67f, r, y + s * .75f, p); c.drawRect(l, y + s * .25f, l + s * .08f, y + s * .75f, p);
    }

    private void drawSearch(Canvas c, float x, float y) {
        p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(1.5f); p.setColor(MUTED); c.drawCircle(x + 6, y + 6, 5, p); c.drawLine(x + 10, y + 10, x + 15, y + 15, p); p.setStyle(Paint.Style.FILL);
    }

    private int colorFor(AppType type) { return type == AppType.FILES ? ACCENT : type == AppType.TERMINAL ? ACCENT2 : type == AppType.SETTINGS ? PURPLE : ORANGE; }
    private String title(AppType type) { return type == AppType.FILES ? "Files" : type == AppType.TERMINAL ? "Terminal" : type == AppType.SETTINGS ? "Settings" : "Browser"; }
    private String shortTitle(AppType type) { return type == AppType.FILES ? "FILES" : type == AppType.TERMINAL ? "TERM" : type == AppType.SETTINGS ? "SETTINGS" : "BROWSER"; }

    private String now(String pattern) {
        try { return new SimpleDateFormat(pattern, Locale.US).format(new Date()); } catch (Throwable ignored) { return "--"; }
    }

    private void round(Canvas c, float l, float t, float r, float b, int color, float radius) {
        if (r <= l || b <= t) return; p.setShader(null); p.setStyle(Paint.Style.FILL); p.setColor(color); c.drawRoundRect(l, t, r, b, radius, radius, p);
    }

    private void text(Canvas c, String value, float x, float y, float size, int color, boolean bold, Paint.Align align) {
        p.setShader(null); p.setStyle(Paint.Style.FILL); p.setColor(color); p.setTextSize(size); p.setTextAlign(align); p.setTypeface(bold ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT); c.drawText(value, x, y, p);
    }

    private void rawText(Canvas c, String value, float x, float y, float size, int color, boolean bold) {
        p.setShader(null); p.setStyle(Paint.Style.FILL); p.setColor(color); p.setTextSize(size); p.setTextAlign(Paint.Align.LEFT); p.setTypeface(bold ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT); c.drawText(value, x, y, p);
    }

    private static float clamp(float v, float min, float max) { return Math.max(min, Math.min(max, v)); }

    private static final class AppWindow {
        final AppType type; final RectF bounds; final RectF restore; boolean minimized; boolean maximized;
        AppWindow(AppType type, RectF bounds) { this.type = type; this.bounds = new RectF(bounds); this.restore = new RectF(bounds); }
    }
}
