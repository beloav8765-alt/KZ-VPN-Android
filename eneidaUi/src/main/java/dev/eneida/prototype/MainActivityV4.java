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
import android.view.MotionEvent;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.TextView;
import android.view.Gravity;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public final class MainActivityV4 extends Activity {
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        try {
            setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
            Window window = getWindow();
            window.setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN, WindowManager.LayoutParams.FLAG_FULLSCREEN);
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            safeFullscreen();
            setContentView(new EneidaDesktopV4View(this));
        } catch (Throwable t) {
            showStartupFailure(t);
        }
    }

    @Override public void onWindowFocusChanged(boolean focus) {
        super.onWindowFocusChanged(focus);
        if (focus) safeFullscreen();
    }

    private void safeFullscreen() {
        try {
            getWindow().getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_FULLSCREEN |
                    View.SYSTEM_UI_FLAG_HIDE_NAVIGATION |
                    View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY |
                    View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN |
                    View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION |
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
        } catch (Throwable ignored) {
            // Prototype must still launch even when an OEM changes immersive-mode behavior.
        }
    }

    private void showStartupFailure(Throwable t) {
        TextView fallback = new TextView(this);
        fallback.setBackgroundColor(Color.rgb(7, 14, 24));
        fallback.setTextColor(Color.WHITE);
        fallback.setTextSize(18f);
        fallback.setGravity(Gravity.CENTER);
        fallback.setPadding(48, 48, 48, 48);
        fallback.setText("ENEIDA DESKTOP V4\n\nStartup diagnostic\n" +
                t.getClass().getSimpleName() + "\n" + safeMessage(t));
        setContentView(fallback);
    }

    private static String safeMessage(Throwable t) {
        String value = t.getMessage();
        return value == null ? "No message" : value;
    }
}

final class EneidaDesktopV4View extends View {
    private enum AppType { FILES, TERMINAL, SETTINGS, BROWSER }

    private static final float H = 720f;
    private static final float TASKBAR_H = 54f;
    private static final float TITLE_H = 38f;

    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final List<AppWindow> windows = new ArrayList<>();

    private final int BG0 = Color.rgb(6, 13, 23);
    private final int BG1 = Color.rgb(12, 29, 48);
    private final int PANEL = Color.rgb(18, 31, 49);
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
    private boolean startOpen;
    private boolean calendarOpen;
    private AppWindow dragging;
    private float dragDx;
    private float dragDy;
    private int selectedFile = -1;
    private int settingsPage;
    private int terminalPage;
    private Throwable renderError;

    EneidaDesktopV4View(Context context) {
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

        if (renderError != null) {
            drawRenderFailure(canvas, renderError);
        } else {
            postInvalidateDelayed(1000L);
        }
    }

    private void drawScene(Canvas c) {
        drawWallpaper(c);
        drawDesktopShortcuts(c);
        drawBrandPanel(c);
        for (AppWindow w : windows) if (!w.minimized) drawWindow(c, w, w == topWindow());
        drawTaskbar(c);
        if (startOpen) drawStartMenu(c);
        if (calendarOpen) drawCalendar(c);
    }

    private void drawRenderFailure(Canvas c, Throwable t) {
        p.setShader(null);
        p.setStyle(Paint.Style.FILL);
        p.setColor(BG0);
        c.drawRect(0, 0, getWidth(), getHeight(), p);
        float x = 48f;
        float y = 78f;
        rawText(c, "ENEIDA DESKTOP V4", x, y, 28f, Color.WHITE, true);
        rawText(c, "Renderer diagnostic — application stayed alive", x, y + 42f, 18f, Color.rgb(101, 220, 199), false);
        rawText(c, t.getClass().getSimpleName(), x, y + 92f, 17f, Color.rgb(255, 190, 120), true);
        rawText(c, safe(t.getMessage()), x, y + 125f, 15f, Color.LTGRAY, false);
    }

    private static String safe(String s) { return s == null ? "No message" : s; }

    private void drawWallpaper(Canvas c) {
        p.setStyle(Paint.Style.FILL);
        p.setShader(new LinearGradient(0, 0, vw, H, BG0, BG1, Shader.TileMode.CLAMP));
        c.drawRect(0, 0, vw, H, p);
        p.setShader(null);
        p.setColor(Color.argb(32, 80, 170, 255));
        c.drawCircle(vw * .82f, 110, 250, p);
        p.setColor(Color.argb(18, 101, 220, 199));
        c.drawCircle(vw * .63f, 590, 360, p);
    }

    private void drawDesktopShortcuts(Canvas c) {
        desktopIcon(c, 24, 30, "F", "FILES", ACCENT, AppType.FILES);
        desktopIcon(c, 24, 126, ">", "TERMINAL", ACCENT2, AppType.TERMINAL);
        desktopIcon(c, 24, 222, "S", "SETTINGS", PURPLE, AppType.SETTINGS);
        desktopIcon(c, 24, 318, "B", "BROWSER", ORANGE, AppType.BROWSER);
    }

    private void desktopIcon(Canvas c, float x, float y, String glyph, String label, int color, AppType type) {
        boolean open = findWindow(type) != null;
        round(c, x, y, x + 112, y + 82, open ? Color.argb(82, 80, 170, 255) : Color.argb(24, 255, 255, 255), 9);
        round(c, x + 12, y + 10, x + 53, y + 51, color, 8);
        text(c, glyph, x + 32.5f, y + 38, 18, BG0, true, Paint.Align.CENTER);
        text(c, label, x + 12, y + 69, 12, TEXT, true, Paint.Align.LEFT);
    }

    private void drawBrandPanel(Canvas c) {
        float w = 310;
        float x = Math.max(690f, vw - w - 38f);
        RectF r = new RectF(x, 70, x + w, 198);
        if (isCovered(r)) return;
        round(c, r.left, r.top, r.right, r.bottom, Color.argb(90, 15, 29, 47), 14);
        text(c, "ENEIDA", x + 24, 113, 31, TEXT, true, Paint.Align.LEFT);
        text(c, "NATIVE SYSTEM", x + 25, 140, 13, ACCENT2, true, Paint.Align.LEFT);
        text(c, "ARMV7 / H3 / LOCAL FIRST", x + 25, 174, 11, MUTED, false, Paint.Align.LEFT);
    }

    private boolean isCovered(RectF area) {
        for (AppWindow w : windows) if (!w.minimized && RectF.intersects(area, w.bounds)) return true;
        return false;
    }

    private void drawWindow(Canvas c, AppWindow w, boolean active) {
        RectF b = w.bounds;
        round(c, b.left, b.top, b.right, b.bottom, PANEL, 10);
        round(c, b.left, b.top, b.right, b.top + TITLE_H, active ? TITLE : Color.rgb(16, 28, 43), 10);
        p.setColor(active ? ACCENT : BORDER);
        c.drawRect(b.left, b.top, b.right, b.top + 2, p);
        drawGlyph(c, w.type, b.left + 21, b.top + 19, 11);
        text(c, title(w.type), b.left + 42, b.top + 25, 14, active ? TEXT : MUTED, true, Paint.Align.LEFT);
        drawWindowButton(c, minRect(w), "—", false);
        drawWindowButton(c, maxRect(w), w.maximized ? "□" : "▢", false);
        drawWindowButton(c, closeRect(w), "×", true);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(1f);
        p.setColor(active ? Color.argb(175, 80, 170, 255) : BORDER);
        c.drawRoundRect(b, 10, 10, p);
        p.setStyle(Paint.Style.FILL);

        int save = c.save();
        c.clipRect(b.left + 1, b.top + TITLE_H, b.right - 1, b.bottom - 1);
        switch (w.type) {
            case FILES: drawFiles(c, w); break;
            case TERMINAL: drawTerminal(c, w); break;
            case SETTINGS: drawSettings(c, w); break;
            case BROWSER: drawBrowser(c, w); break;
        }
        c.restoreToCount(save);
    }

    private void drawWindowButton(Canvas c, RectF r, String glyph, boolean close) {
        round(c, r.left, r.top, r.right, r.bottom, close ? Color.argb(55, 229, 74, 88) : Color.argb(25, 255, 255, 255), 5);
        text(c, glyph, r.centerX(), r.centerY() + 5, 15, close ? Color.rgb(255, 184, 190) : MUTED, true, Paint.Align.CENTER);
    }

    private void drawFiles(Canvas c, AppWindow w) {
        RectF b = w.bounds;
        float top = b.top + TITLE_H;
        float side = Math.min(145, Math.max(110, b.width() * .22f));
        p.setColor(Color.rgb(14, 26, 41));
        c.drawRect(b.left, top, b.left + side, b.bottom, p);
        text(c, "FILES", b.left + 18, top + 32, 11, ACCENT, true, Paint.Align.LEFT);
        String[] nav = {"Root", "Documents", "Downloads", "System"};
        for (int i = 0; i < nav.length; i++) {
            float y = top + 48 + i * 38;
            if (i == 0) round(c, b.left + 10, y, b.left + side - 10, y + 31, Color.argb(72, 80, 170, 255), 6);
            text(c, nav[i], b.left + 20, y + 21, 11, i == 0 ? TEXT : MUTED, i == 0, Paint.Align.LEFT);
        }

        float x = b.left + side;
        p.setColor(Color.rgb(22, 37, 57));
        c.drawRect(x, top, b.right, top + 44, p);
        text(c, "ENEIDA /", x + 16, top + 28, 12, TEXT, true, Paint.Align.LEFT);
        text(c, "READ ONLY", b.right - 18, top + 28, 10, ACCENT2, true, Paint.Align.RIGHT);

        String[] names = {"Documents", "Downloads", "Screenshots", "boot.log", "system.info", "Eneida-notes.txt"};
        String[] meta = {"<DIR>", "<DIR>", "<DIR>", "18 KB", "4 KB", "2 KB"};
        float rowTop = top + 54;
        float rowH = Math.max(39, Math.min(50, (b.height() - TITLE_H - 65) / 6f));
        for (int i = 0; i < names.length; i++) {
            float y = rowTop + i * rowH;
            if (selectedFile == i) {
                round(c, x + 8, y, b.right - 8, y + rowH - 3, Color.rgb(34, 57, 86), 6);
                p.setColor(ACCENT);
                c.drawRect(x + 8, y + 7, x + 11, y + rowH - 10, p);
            }
            text(c, i < 3 ? "□" : "·", x + 25, y + rowH * .62f, 13, i < 3 ? ACCENT : MUTED, true, Paint.Align.CENTER);
            text(c, names[i], x + 44, y + rowH * .62f, 12, TEXT, selectedFile == i, Paint.Align.LEFT);
            text(c, meta[i], b.right - 18, y + rowH * .62f, 10, MUTED, false, Paint.Align.RIGHT);
        }
    }

    private void drawTerminal(Canvas c, AppWindow w) {
        RectF b = w.bounds;
        float top = b.top + TITLE_H;
        p.setColor(Color.rgb(5, 11, 17));
        c.drawRect(b.left, top, b.right, b.bottom, p);
        String[][] pages = {
                {"$ system", "kernel      ENEIDA NATIVE", "platform    ALLWINNER H3 / CORTEX-A7", "arch        ARMV7", "health      CORE OK", "timer       100 HZ"},
                {"$ storage", "device      MMC0 / MICROSD", "filesystem  FAT32", "access      READ ONLY", "root        READY"},
                {"$ network", "state       PASSIVE", "link        DOWN", "rx          QUALIFICATION MODE", "tx          DISABLED"},
                {"$ help", "system   storage   network", "files    date      clear", "Prototype command tabs"}
        };
        text(c, "ENEIDA TERMINAL", b.left + 20, top + 30, 11, ACCENT2, true, Paint.Align.LEFT);
        float y = top + 64;
        for (String line : pages[terminalPage]) {
            text(c, line, b.left + 22, y, 13, line.startsWith("$") ? ACCENT : TEXT, line.startsWith("$"), Paint.Align.LEFT);
            y += 27;
        }
        String[] chips = {"SYSTEM", "STORAGE", "NETWORK", "HELP"};
        float chipW = Math.max(78, (b.width() - 50) / 4f);
        for (int i = 0; i < 4; i++) {
            float x = b.left + 14 + i * chipW;
            round(c, x, b.bottom - 48, x + chipW - 7, b.bottom - 14, i == terminalPage ? Color.rgb(43, 75, 108) : Color.rgb(19, 34, 52), 6);
            text(c, chips[i], x + (chipW - 7) / 2, b.bottom - 26, 9, i == terminalPage ? TEXT : MUTED, true, Paint.Align.CENTER);
        }
    }

    private void drawSettings(Canvas c, AppWindow w) {
        RectF b = w.bounds;
        float top = b.top + TITLE_H;
        float side = Math.min(170, Math.max(132, b.width() * .25f));
        p.setColor(Color.rgb(14, 26, 41));
        c.drawRect(b.left, top, b.left + side, b.bottom, p);
        String[] tabs = {"SYSTEM", "DATE & TIME", "STORAGE", "USB", "NETWORK"};
        for (int i = 0; i < tabs.length; i++) {
            float y = top + 18 + i * 48;
            if (settingsPage == i) round(c, b.left + 10, y, b.left + side - 10, y + 36, Color.argb(85, 80, 170, 255), 6);
            text(c, tabs[i], b.left + 22, y + 23, 10, settingsPage == i ? TEXT : MUTED, settingsPage == i, Paint.Align.LEFT);
        }
        float x = b.left + side + 22;
        float right = b.right - 22;
        text(c, tabs[settingsPage], x, top + 35, 16, TEXT, true, Paint.Align.LEFT);
        float y = top + 79;
        if (settingsPage == 0) {
            info(c, x, right, y, "CPU", "ALLWINNER H3 / CORTEX-A7", ACCENT); y += 47;
            info(c, x, right, y, "ARCH", "ARMV7 / ENEIDA NATIVE", ACCENT2); y += 47;
            info(c, x, right, y, "DISPLAY", "1280 × 720 / XRGB8888", PURPLE); y += 47;
            info(c, x, right, y, "HEALTH", "CORE OK", ACCENT2);
        } else if (settingsPage == 1) {
            info(c, x, right, y, "SYSTEM WALL CLOCK", now("yyyy-MM-dd HH:mm:ss"), ACCENT); y += 47;
            info(c, x, right, y, "SOURCE", "H3 RTC / TIMER0", ACCENT2); y += 47;
            info(c, x, right, y, "MODE", "READ ONLY", ORANGE);
        } else if (settingsPage == 2) {
            info(c, x, right, y, "DEVICE", "MMC0 / MICROSD", ACCENT); y += 47;
            info(c, x, right, y, "FILESYSTEM", "FAT32 READY", ACCENT2); y += 47;
            info(c, x, right, y, "ACCESS", "READ ONLY", ORANGE);
        } else if (settingsPage == 3) {
            info(c, x, right, y, "OHCI HOSTS", "4", ACCENT); y += 47;
            info(c, x, right, y, "MOUSE", "READY", ACCENT2); y += 47;
            info(c, x, right, y, "KEYBOARD", "READY", ACCENT2);
        } else {
            info(c, x, right, y, "STATE", "PASSIVE", MUTED); y += 47;
            info(c, x, right, y, "LINK", "DOWN", ORANGE); y += 47;
            info(c, x, right, y, "TX", "DISABLED", ORANGE);
        }
    }

    private void info(Canvas c, float left, float right, float y, String key, String value, int color) {
        round(c, left, y, right, y + 38, Color.rgb(24, 41, 62), 6);
        p.setColor(color);
        c.drawCircle(left + 14, y + 19, 4, p);
        text(c, key, left + 27, y + 24, 9, MUTED, true, Paint.Align.LEFT);
        text(c, value, right - 12, y + 24, 10, TEXT, true, Paint.Align.RIGHT);
    }

    private void drawBrowser(Canvas c, AppWindow w) {
        RectF b = w.bounds;
        float top = b.top + TITLE_H;
        p.setColor(Color.rgb(244, 248, 252));
        c.drawRect(b.left, top, b.right, b.bottom, p);
        round(c, b.left + 14, top + 12, b.right - 14, top + 46, Color.rgb(225, 232, 240), 8);
        text(c, "eneida://welcome", b.left + 28, top + 34, 11, Color.rgb(60, 70, 84), false, Paint.Align.LEFT);
        text(c, "ENEIDA", b.left + 26, top + 98, 28, Color.rgb(20, 30, 44), true, Paint.Align.LEFT);
        text(c, "Native browser shell", b.left + 27, top + 124, 12, Color.rgb(100, 113, 130), false, Paint.Align.LEFT);
        round(c, b.left + 26, top + 154, b.right - 26, top + 248, Color.rgb(232, 240, 248), 10);
        text(c, "LOCAL FIRST", b.left + 44, top + 188, 11, Color.rgb(42, 106, 165), true, Paint.Align.LEFT);
        text(c, "Browser rendering is simulated in this APK.", b.left + 44, top + 217, 11, Color.rgb(67, 80, 96), false, Paint.Align.LEFT);
    }

    private void drawTaskbar(Canvas c) {
        float y = H - TASKBAR_H;
        p.setColor(Color.argb(244, 11, 22, 36));
        c.drawRect(0, y, vw, H, p);
        p.setColor(Color.argb(150, 80, 170, 255));
        c.drawRect(0, y, vw, y + 1.5f, p);
        round(c, 9, y + 7, 92, y + 46, startOpen ? Color.rgb(44, 75, 105) : Color.rgb(25, 43, 64), 7);
        text(c, "ENEIDA", 50, y + 31, 11, TEXT, true, Paint.Align.CENTER);

        float x = 104;
        for (AppWindow w : windows) {
            if (x + 126 > vw - 250) break;
            boolean active = !w.minimized && w == topWindow();
            round(c, x, y + 7, x + 122, y + 46, active ? Color.rgb(38, 66, 94) : Color.rgb(20, 36, 54), 6);
            drawGlyph(c, w.type, x + 18, y + 27, 9);
            text(c, shortTitle(w.type), x + 35, y + 31, 10, active ? TEXT : MUTED, active, Paint.Align.LEFT);
            x += 128;
        }
        round(c, vw - 104, y + 6, vw - 9, y + 47, calendarOpen ? Color.rgb(39, 67, 94) : Color.rgb(21, 37, 55), 6);
        text(c, now("HH:mm"), vw - 56, y + 25, 12, TEXT, true, Paint.Align.CENTER);
        text(c, now("dd MMM").toUpperCase(Locale.US), vw - 56, y + 40, 8, MUTED, true, Paint.Align.CENTER);
        text(c, "USB  NET", vw - 118, y + 31, 8, ACCENT2, true, Paint.Align.RIGHT);
    }

    private void drawStartMenu(Canvas c) {
        float bottom = H - TASKBAR_H - 8;
        float left = 10;
        float width = 326;
        float top = bottom - 370;
        round(c, left, top, left + width, bottom, Color.argb(250, 16, 29, 46), 13);
        text(c, "ENEIDA", left + 24, top + 38, 18, TEXT, true, Paint.Align.LEFT);
        text(c, "NATIVE DESKTOP", left + 24, top + 58, 9, ACCENT2, true, Paint.Align.LEFT);
        String[] names = {"Files", "Terminal", "Settings", "Browser"};
        AppType[] types = {AppType.FILES, AppType.TERMINAL, AppType.SETTINGS, AppType.BROWSER};
        int[] colors = {ACCENT, ACCENT2, PURPLE, ORANGE};
        for (int i = 0; i < 4; i++) {
            float y = top + 85 + i * 52;
            round(c, left + 16, y, left + width - 16, y + 42, Color.argb(26, 255, 255, 255), 6);
            round(c, left + 27, y + 8, left + 53, y + 34, colors[i], 6);
            text(c, names[i].substring(0, 1), left + 40, y + 25, 10, BG0, true, Paint.Align.CENTER);
            text(c, names[i], left + 68, y + 26, 12, TEXT, true, Paint.Align.LEFT);
            if (findWindow(types[i]) != null) text(c, "OPEN", left + width - 32, y + 26, 8, ACCENT, true, Paint.Align.RIGHT);
        }
        text(c, "Prototype · Orange Pi desktop UI", left + 24, bottom - 19, 9, MUTED, false, Paint.Align.LEFT);
    }

    private void drawCalendar(Canvas c) {
        float width = 290;
        float right = vw - 10;
        float bottom = H - TASKBAR_H - 8;
        float left = right - width;
        float top = bottom - 220;
        round(c, left, top, right, bottom, Color.argb(252, 17, 31, 49), 12);
        text(c, now("MMMM yyyy").toUpperCase(Locale.US), left + 18, top + 31, 13, TEXT, true, Paint.Align.LEFT);
        text(c, now("HH:mm:ss"), right - 18, top + 31, 11, ACCENT2, true, Paint.Align.RIGHT);
        text(c, now("EEEE, dd MMMM").toUpperCase(Locale.US), left + 18, top + 82, 16, TEXT, true, Paint.Align.LEFT);
        text(c, "SYSTEM CLOCK · RTC + TIMER0", left + 18, bottom - 24, 9, MUTED, true, Paint.Align.LEFT);
    }

    @Override public boolean onTouchEvent(MotionEvent e) {
        try {
            float x = e.getX() / scale;
            float y = e.getY() / scale;
            if (e.getActionMasked() == MotionEvent.ACTION_DOWN) return handleDown(x, y);
            if (e.getActionMasked() == MotionEvent.ACTION_MOVE && dragging != null) {
                moveDragged(x, y);
                invalidate();
                return true;
            }
            if (e.getActionMasked() == MotionEvent.ACTION_UP || e.getActionMasked() == MotionEvent.ACTION_CANCEL) {
                dragging = null;
                invalidate();
                return true;
            }
            return true;
        } catch (Throwable t) {
            renderError = t;
            invalidate();
            return true;
        }
    }

    private boolean handleDown(float x, float y) {
        if (startOpen && handleStartClick(x, y)) return true;
        if (calendarOpen && y < H - TASKBAR_H) calendarOpen = false;

        if (y >= H - TASKBAR_H) {
            if (x <= 96) {
                startOpen = !startOpen;
                calendarOpen = false;
                invalidate();
                return true;
            }
            if (x >= vw - 112) {
                calendarOpen = !calendarOpen;
                startOpen = false;
                invalidate();
                return true;
            }
            if (handleTaskbarClick(x)) return true;
        }

        for (int i = windows.size() - 1; i >= 0; i--) {
            AppWindow w = windows.get(i);
            if (w.minimized || !w.bounds.contains(x, y)) continue;
            bringToFront(w);
            startOpen = false;
            calendarOpen = false;
            if (closeRect(w).contains(x, y)) { windows.remove(w); invalidate(); return true; }
            if (minRect(w).contains(x, y)) { w.minimized = true; invalidate(); return true; }
            if (maxRect(w).contains(x, y)) { toggleMaximize(w); invalidate(); return true; }
            if (y <= w.bounds.top + TITLE_H) {
                dragging = w;
                dragDx = x - w.bounds.left;
                dragDy = y - w.bounds.top;
                return true;
            }
            handleWindowBody(w, x, y);
            invalidate();
            return true;
        }

        if (x >= 24 && x <= 136) {
            if (y >= 30 && y <= 112) open(AppType.FILES);
            else if (y >= 126 && y <= 208) open(AppType.TERMINAL);
            else if (y >= 222 && y <= 304) open(AppType.SETTINGS);
            else if (y >= 318 && y <= 400) open(AppType.BROWSER);
        }
        startOpen = false;
        calendarOpen = false;
        invalidate();
        return true;
    }

    private boolean handleStartClick(float x, float y) {
        float bottom = H - TASKBAR_H - 8;
        float top = bottom - 370;
        if (x < 10 || x > 336 || y < top || y > bottom) {
            startOpen = false;
            invalidate();
            return false;
        }
        AppType[] types = {AppType.FILES, AppType.TERMINAL, AppType.SETTINGS, AppType.BROWSER};
        for (int i = 0; i < 4; i++) {
            float row = top + 85 + i * 52;
            if (y >= row && y <= row + 42) {
                open(types[i]);
                startOpen = false;
                return true;
            }
        }
        return true;
    }

    private boolean handleTaskbarClick(float x) {
        float pos = 104;
        for (AppWindow w : windows) {
            if (pos + 122 > vw - 250) break;
            if (x >= pos && x <= pos + 122) {
                if (w.minimized) w.minimized = false;
                bringToFront(w);
                invalidate();
                return true;
            }
            pos += 128;
        }
        return false;
    }

    private void handleWindowBody(AppWindow w, float x, float y) {
        if (w.type == AppType.FILES) {
            float top = w.bounds.top + TITLE_H;
            float side = Math.min(145, Math.max(110, w.bounds.width() * .22f));
            if (x > w.bounds.left + side && y > top + 54) {
                float rowH = Math.max(39, Math.min(50, (w.bounds.height() - TITLE_H - 65) / 6f));
                int row = (int)((y - (top + 54)) / rowH);
                if (row >= 0 && row < 6) selectedFile = row;
            }
        } else if (w.type == AppType.SETTINGS) {
            float top = w.bounds.top + TITLE_H;
            float side = Math.min(170, Math.max(132, w.bounds.width() * .25f));
            if (x <= w.bounds.left + side) {
                int row = (int)((y - (top + 18)) / 48f);
                if (row >= 0 && row < 5) settingsPage = row;
            }
        } else if (w.type == AppType.TERMINAL && y >= w.bounds.bottom - 58) {
            float chipW = Math.max(78, (w.bounds.width() - 50) / 4f);
            int row = (int)((x - (w.bounds.left + 14)) / chipW);
            if (row >= 0 && row < 4) terminalPage = row;
        }
    }

    private void open(AppType type) {
        AppWindow existing = findWindow(type);
        if (existing != null) {
            existing.minimized = false;
            bringToFront(existing);
            invalidate();
            return;
        }
        float offset = windows.size() * 22f;
        float rightLimit = Math.max(860, vw - 70);
        RectF r;
        if (type == AppType.SETTINGS) r = new RectF(210 + offset, 80 + offset, Math.min(rightLimit, 960 + offset), 590 + offset / 2);
        else if (type == AppType.FILES) r = new RectF(180 + offset, 70 + offset, Math.min(rightLimit, 940 + offset), 590 + offset / 2);
        else if (type == AppType.TERMINAL) r = new RectF(245 + offset, 105 + offset, Math.min(rightLimit, 900 + offset), 545 + offset / 2);
        else r = new RectF(220 + offset, 88 + offset, Math.min(rightLimit, 970 + offset), 570 + offset / 2);
        windows.add(new AppWindow(type, r));
        invalidate();
    }

    private void bringToFront(AppWindow w) {
        windows.remove(w);
        windows.add(w);
    }

    private void moveDragged(float x, float y) {
        if (dragging == null || dragging.maximized) return;
        float width = dragging.bounds.width();
        float height = dragging.bounds.height();
        float left = clamp(x - dragDx, 0, Math.max(0, vw - width));
        float top = clamp(y - dragDy, 0, Math.max(0, H - TASKBAR_H - TITLE_H));
        dragging.bounds.set(left, top, left + width, Math.min(H - TASKBAR_H, top + height));
    }

    private void toggleMaximize(AppWindow w) {
        if (!w.maximized) {
            w.restore.set(w.bounds);
            w.bounds.set(8, 8, vw - 8, H - TASKBAR_H - 7);
            w.maximized = true;
        } else {
            w.bounds.set(w.restore);
            w.maximized = false;
        }
    }

    private AppWindow findWindow(AppType type) {
        for (AppWindow w : windows) if (w.type == type) return w;
        return null;
    }

    private AppWindow topWindow() {
        for (int i = windows.size() - 1; i >= 0; i--) if (!windows.get(i).minimized) return windows.get(i);
        return null;
    }

    private RectF closeRect(AppWindow w) { return new RectF(w.bounds.right - 42, w.bounds.top + 6, w.bounds.right - 8, w.bounds.top + 32); }
    private RectF maxRect(AppWindow w) { return new RectF(w.bounds.right - 80, w.bounds.top + 6, w.bounds.right - 46, w.bounds.top + 32); }
    private RectF minRect(AppWindow w) { return new RectF(w.bounds.right - 118, w.bounds.top + 6, w.bounds.right - 84, w.bounds.top + 32); }

    private void drawGlyph(Canvas c, AppType type, float x, float y, float size) {
        int color = type == AppType.FILES ? ACCENT : type == AppType.TERMINAL ? ACCENT2 : type == AppType.SETTINGS ? PURPLE : ORANGE;
        p.setColor(color);
        c.drawCircle(x, y, size, p);
        String glyph = type == AppType.FILES ? "F" : type == AppType.TERMINAL ? ">" : type == AppType.SETTINGS ? "S" : "B";
        text(c, glyph, x, y + size * .35f, Math.max(7, size), BG0, true, Paint.Align.CENTER);
    }

    private String title(AppType type) {
        switch (type) {
            case FILES: return "Files";
            case TERMINAL: return "Terminal";
            case SETTINGS: return "Settings";
            default: return "Browser";
        }
    }

    private String shortTitle(AppType type) {
        switch (type) {
            case FILES: return "FILES";
            case TERMINAL: return "TERM";
            case SETTINGS: return "SETTINGS";
            default: return "BROWSER";
        }
    }

    private String now(String pattern) {
        try { return new SimpleDateFormat(pattern, Locale.US).format(new Date()); }
        catch (Throwable ignored) { return "--"; }
    }

    private void round(Canvas c, float l, float t, float r, float b, int color, float radius) {
        if (r <= l || b <= t) return;
        p.setShader(null);
        p.setStyle(Paint.Style.FILL);
        p.setColor(color);
        c.drawRoundRect(l, t, r, b, radius, radius, p);
    }

    private void text(Canvas c, String value, float x, float y, float size, int color, boolean bold, Paint.Align align) {
        p.setShader(null);
        p.setStyle(Paint.Style.FILL);
        p.setColor(color);
        p.setTextSize(size);
        p.setTextAlign(align);
        p.setTypeface(bold ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
        c.drawText(value, x, y, p);
    }

    private void rawText(Canvas c, String value, float x, float y, float size, int color, boolean bold) {
        p.setShader(null);
        p.setStyle(Paint.Style.FILL);
        p.setColor(color);
        p.setTextSize(size);
        p.setTextAlign(Paint.Align.LEFT);
        p.setTypeface(bold ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
        c.drawText(value, x, y, p);
    }

    private static float clamp(float v, float min, float max) { return Math.max(min, Math.min(max, v)); }

    private static final class AppWindow {
        final AppType type;
        final RectF bounds;
        final RectF restore;
        boolean minimized;
        boolean maximized;

        AppWindow(AppType type, RectF bounds) {
            this.type = type;
            this.bounds = new RectF(bounds);
            this.restore = new RectF(bounds);
        }
    }
}
