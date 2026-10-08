package dev.eneida.prototype;

import android.app.Activity;
import android.os.Build;
import android.os.Bundle;
import android.graphics.*;
import android.graphics.drawable.ColorDrawable;
import android.view.*;
import android.content.Context;
import java.text.SimpleDateFormat;
import java.util.*;

public final class MainActivity extends Activity {
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setBackgroundDrawable(new ColorDrawable(Color.BLACK));
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
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
        setContentView(new EneidaPrototypeView(this));
    }

    @Override public void onWindowFocusChanged(boolean focus) {
        super.onWindowFocusChanged(focus);
        if (focus && Build.VERSION.SDK_INT < 30) {
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

final class EneidaPrototypeView extends View {
    private static final float DW = 1440f, DH = 3120f;
    private enum Screen { HOME, FILES, TERMINAL, SETTINGS, QUICK, CALENDAR }
    private Screen screen = Screen.HOME;
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF r = new RectF();
    private float sx = 1f, sy = 1f, downX, downY;
    private int selectedFile = -1;
    private int terminalMode = 0;
    private boolean wifi = true, bluetooth = true, night = true, performance = false, sync = true, sound = true;

    private final int BG0 = Color.rgb(8, 13, 25);
    private final int BG1 = Color.rgb(14, 24, 43);
    private final int CARD = Color.rgb(22, 34, 56);
    private final int CARD2 = Color.rgb(27, 43, 68);
    private final int LINE = Color.rgb(56, 74, 100);
    private final int TEXT = Color.rgb(238, 244, 252);
    private final int MUTED = Color.rgb(150, 166, 190);
    private final int ACCENT = Color.rgb(92, 176, 255);
    private final int ACCENT2 = Color.rgb(111, 224, 207);
    private final int BAD = Color.rgb(255, 101, 115);

    EneidaPrototypeView(Context c) {
        super(c);
        setFocusable(true);
        setBackgroundColor(BG0);
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeWidth(2f);
        stroke.setColor(LINE);
    }

    @Override protected void onDraw(Canvas c) {
        super.onDraw(c);
        sx = getWidth() / DW;
        sy = getHeight() / DH;
        c.save(); c.scale(sx, sy);
        drawWallpaper(c);
        switch (screen) {
            case HOME: drawHome(c); break;
            case FILES: drawFiles(c); break;
            case TERMINAL: drawTerminal(c); break;
            case SETTINGS: drawSettings(c); break;
            case QUICK: drawQuick(c); break;
            case CALENDAR: drawCalendar(c); break;
        }
        drawGestureBar(c);
        c.restore();
        postInvalidateDelayed(1000);
    }

    private void drawWallpaper(Canvas c) {
        p.setShader(new LinearGradient(0, 0, DW, DH, BG0, BG1, Shader.TileMode.CLAMP));
        c.drawRect(0, 0, DW, DH, p); p.setShader(null);
        p.setColor(Color.argb(24, 92, 176, 255)); c.drawCircle(1240, 420, 520, p);
        p.setColor(Color.argb(16, 111, 224, 207)); c.drawCircle(130, 2380, 650, p);
        p.setColor(Color.argb(18, 255, 255, 255));
        for (int i=0;i<8;i++) c.drawCircle(120 + i*175, 860 + (i%2)*35, 3, p);
    }

    private void drawStatus(Canvas c, String title, String sub) {
        text(c, now("HH:mm"), 62, 120, 42, TEXT, true);
        text(c, "ENEIDA", 720, 118, 32, MUTED, true, Paint.Align.CENTER);
        pill(c, 1168, 62, 1348, 142, Color.argb(120, 31, 48, 74));
        text(c, "UI TEST", 1258, 115, 25, ACCENT2, true, Paint.Align.CENTER);
        if (title != null) {
            text(c, title, 72, 292, 62, TEXT, true);
            text(c, sub, 74, 350, 27, MUTED, false);
        }
    }

    private void drawHome(Canvas c) {
        drawStatus(c, null, null);
        text(c, now("EEEE").toUpperCase(Locale.US), 72, 420, 29, ACCENT2, true);
        text(c, now("HH:mm"), 65, 620, 154, TEXT, false);
        text(c, now("d MMMM yyyy"), 72, 704, 40, MUTED, false);
        pill(c, 70, 770, 690, 864, Color.argb(150, 27, 43, 68));
        dot(c, 118, 817, 11, ACCENT2);
        text(c, "ENEIDA NATIVE UI · PHONE PROTOTYPE", 154, 829, 25, TEXT, true);

        appCard(c, 76, 1030, 644, 1390, "FILES", "Storage & documents", "F", ACCENT);
        appCard(c, 796, 1030, 1364, 1390, "TERMINAL", "Native shell preview", ">", ACCENT2);
        appCard(c, 76, 1460, 644, 1820, "SETTINGS", "System control center", "S", Color.rgb(180,144,255));
        appCard(c, 796, 1460, 1364, 1820, "SYSTEM", "Health & hardware", "●", Color.rgb(255,190,92));

        text(c, "TODAY", 78, 1934, 28, MUTED, true);
        card(c, 74, 1978, 1366, 2262, CARD, 38);
        text(c, "SYSTEM READY", 120, 2055, 31, ACCENT2, true);
        text(c, "Prototype shell is running locally", 120, 2120, 28, TEXT, false);
        text(c, "Swipe down for Quick Settings", 120, 2181, 25, MUTED, false);
        pill(c, 1120, 2044, 1318, 2120, Color.argb(120, 92, 176, 255));
        text(c, "S25 ULTRA", 1219, 2097, 22, TEXT, true, Paint.Align.CENTER);

        drawDock(c);
    }

    private void drawDock(Canvas c) {
        card(c, 66, 2598, 1374, 2946, Color.argb(235, 16, 27, 45), 58);
        dockItem(c, 120, 2650, "F", "Files", screen == Screen.FILES);
        dockItem(c, 420, 2650, ">", "Terminal", screen == Screen.TERMINAL);
        dockItem(c, 720, 2650, "S", "Settings", screen == Screen.SETTINGS);
        dockItem(c, 1020, 2650, "≡", "Quick", screen == Screen.QUICK);
    }

    private void dockItem(Canvas c, float x, float y, String glyph, String label, boolean active) {
        if (active) pill(c, x-24, y-20, x+220, y+232, Color.argb(90, 92, 176, 255));
        circleIcon(c, x+96, y+76, 58, active ? ACCENT : CARD2, glyph);
        text(c, label, x+96, y+190, 23, active ? TEXT : MUTED, true, Paint.Align.CENTER);
    }

    private void drawFiles(Canvas c) {
        drawStatus(c, "Files", "LOCAL STORAGE · READ-ONLY PROTOTYPE");
        pill(c, 70, 408, 1370, 512, Color.argb(160, 23, 37, 60));
        text(c, "Internal / Eneida", 112, 474, 27, TEXT, true);
        text(c, "12.8 GB free", 1306, 474, 24, MUTED, false, Paint.Align.RIGHT);
        String[] names = {"Documents", "Downloads", "Screenshots", "boot.log", "system.info", "Eneida-notes.txt"};
        String[] meta = {"8 items", "14 items", "22 items", "18 KB", "4 KB", "2 KB"};
        for (int i=0;i<names.length;i++) {
            float y = 590 + i*248;
            boolean sel = selectedFile == i;
            card(c, 72, y, 1368, y+206, sel ? Color.rgb(33,55,86) : Color.argb(210,22,34,56), 30);
            if (sel) { p.setColor(ACCENT); c.drawRoundRect(new RectF(72,y+24,80,y+182),4,4,p); }
            circleIcon(c, 126, y+52, 46, i<3 ? Color.rgb(56,82,112) : Color.rgb(42,65,91), i<3 ? "□" : "·");
            text(c, names[i], 216, y+90, 31, TEXT, true);
            text(c, meta[i], 216, y+145, 24, MUTED, false);
            text(c, "›", 1308, y+126, 44, sel ? ACCENT : MUTED, false, Paint.Align.CENTER);
        }
        if (selectedFile >= 3) {
            card(c, 74, 2140, 1366, 2490, Color.argb(240,14,24,40), 34);
            text(c, "PREVIEW", 116, 2210, 24, ACCENT2, true);
            text(c, selectedFile == 3 ? "ENEIDA_ARMV7_DESKTOP_READY" : selectedFile == 4 ? "HEALTH   CORE OK" : "Prototype notes stored locally.", 116, 2296, 28, TEXT, false);
            text(c, "No files are modified by this APK.", 116, 2370, 24, MUTED, false);
        }
        drawDock(c);
    }

    private void drawTerminal(Canvas c) {
        drawStatus(c, "Terminal", "ENEIDA SHELL · INTERFACE TEST");
        card(c, 70, 410, 1370, 2384, Color.rgb(7,13,20), 34);
        text(c, "ENEIDA TERMINAL 0.1", 116, 486, 26, ACCENT2, true);
        text(c, "native shell preview / Android host", 116, 540, 23, MUTED, false);
        String[][] outputs = {
            {"$ system", "kernel     ENEIDA NATIVE", "arch       ARMV7 / H3", "health     CORE OK", "display    1440 x 3120 test viewport"},
            {"$ storage", "device     prototype sandbox", "mode       READ ONLY", "files      6 demo entries", "status     READY"},
            {"$ network", "state      PASSIVE", "tx         DISABLED", "link       simulated", "parser     NOT QUALIFIED"},
            {"$ help", "system  storage  network", "files   date     clear", "Tap a command chip below."}
        };
        float y=650;
        for (String line : outputs[terminalMode]) {
            text(c, line, 116, y, 29, line.startsWith("$") ? ACCENT : TEXT, line.startsWith("$")); y += 78;
        }
        text(c, "_", 116, y+34, 32, ACCENT2, true);
        String[] chips={"SYSTEM","STORAGE","NETWORK","HELP"};
        for(int i=0;i<4;i++){
            float x=74+i*329;
            pill(c,x,2460,x+300,2570, i==terminalMode ? Color.rgb(48,82,118):Color.rgb(24,39,61));
            text(c,chips[i],x+150,2532,22,i==terminalMode?TEXT:MUTED,true,Paint.Align.CENTER);
        }
        drawDock(c);
    }

    private void drawSettings(Canvas c) {
        drawStatus(c, "Settings", "ENEIDA SYSTEM CONTROL CENTER");
        settingsRow(c, 72, 425, "SYSTEM", "Eneida UI Prototype", "CORE OK", ACCENT2);
        settingsRow(c, 72, 680, "DATE & TIME", now("yyyy-MM-dd  HH:mm:ss"), "LOCAL", ACCENT);
        settingsRow(c, 72, 935, "DISPLAY", "Galaxy S25 Ultra viewport", "EDGE-TO-EDGE", Color.rgb(180,144,255));
        settingsRow(c, 72, 1190, "STORAGE", "Prototype sandbox", "READ ONLY", Color.rgb(255,190,92));
        settingsRow(c, 72, 1445, "USB / INPUT", "Touch + gesture navigation", "READY", ACCENT2);
        settingsRow(c, 72, 1700, "NETWORK", "No OS network control exposed", "PASSIVE", MUTED);
        card(c,72,1990,1368,2328,Color.argb(220,22,34,56),34);
        text(c,"ABOUT THIS BUILD",116,2065,25,ACCENT,true);
        text(c,"Package  dev.eneida.prototype.s25",116,2135,27,TEXT,false);
        text(c,"Purpose  interface and interaction testing",116,2190,25,MUTED,false);
        text(c,"Hardware controls are intentionally simulated.",116,2250,24,MUTED,false);
        drawDock(c);
    }

    private void settingsRow(Canvas c,float x,float y,String title,String value,String status,int color){
        card(c,x,y,1368,y+215,Color.argb(220,22,34,56),32);
        dot(c,118,y+73,10,color);
        text(c,title,150,y+82,24,MUTED,true);
        text(c,value,116,y+148,30,TEXT,false);
        pill(c,1080,y+55,1320,y+127,Color.argb(100,40,60,86));
        text(c,status,1200,y+104,20,color,true,Paint.Align.CENTER);
    }

    private void drawQuick(Canvas c) {
        drawStatus(c, "Quick Settings", "SWIPE OR TAP HOME BAR TO CLOSE");
        text(c, now("HH:mm"), 72, 570, 112, TEXT, false);
        text(c, now("EEE, d MMM"), 78, 638, 29, MUTED, false);
        toggle(c, 72, 760, "WI-FI", "Connected", wifi, 0);
        toggle(c, 738, 760, "BLUETOOTH", "On", bluetooth, 1);
        toggle(c, 72, 1100, "NIGHT", "Dark interface", night, 2);
        toggle(c, 738, 1100, "PERFORMANCE", "Balanced", performance, 3);
        toggle(c, 72, 1440, "SYNC", "Local state", sync, 4);
        toggle(c, 738, 1440, "SOUND", "Interface sounds", sound, 5);
        card(c,72,1830,1368,2170,Color.argb(220,22,34,56),34);
        text(c,"DISPLAY",116,1900,24,MUTED,true);
        text(c,"78%",1298,1900,24,TEXT,true,Paint.Align.RIGHT);
        pill(c,116,1970,1298,2020,Color.rgb(35,52,76));
        pill(c,116,1970,1038,2020,ACCENT);
        text(c,"This panel changes prototype state only.",116,2112,23,MUTED,false);
        drawDock(c);
    }

    private void toggle(Canvas c,float x,float y,String title,String sub,boolean on,int index){
        card(c,x,y,x+630,y+286,on?Color.rgb(34,66,96):Color.rgb(22,34,56),38);
        circleIcon(c,x+80,y+78,46,on?ACCENT:Color.rgb(52,65,84), on?"●":"○");
        text(c,title,x+44,y+190,27,on?TEXT:MUTED,true);
        text(c,sub,x+44,y+232,22,MUTED,false);
        pill(c,x+492,y+38,x+584,y+86,on?ACCENT:Color.rgb(55,67,84));
        dot(c,on?x+558:x+518,y+62,17,Color.WHITE);
    }

    private void drawCalendar(Canvas c) {
        drawStatus(c, "Calendar", "SYSTEM DATE VIEW");
        Calendar cal=Calendar.getInstance(); int today=cal.get(Calendar.DAY_OF_MONTH);
        int month=cal.get(Calendar.MONTH), year=cal.get(Calendar.YEAR);
        cal.set(Calendar.DAY_OF_MONTH,1);
        int first=(cal.get(Calendar.DAY_OF_WEEK)+5)%7; int days=cal.getActualMaximum(Calendar.DAY_OF_MONTH);
        String monthName=new SimpleDateFormat("MMMM yyyy",Locale.US).format(cal.getTime()).toUpperCase(Locale.US);
        text(c,monthName,72,530,46,TEXT,true);
        String[] wd={"MON","TUE","WED","THU","FRI","SAT","SUN"};
        for(int i=0;i<7;i++) text(c,wd[i],120+i*190,680,22,MUTED,true,Paint.Align.CENTER);
        float top=760;
        for(int d=1;d<=days;d++){
            int idx=first+d-1,row=idx/7,col=idx%7; float cx=120+col*190, cy=top+row*230;
            if(d==today){ p.setColor(Color.argb(100,92,176,255)); c.drawCircle(cx,cy,72,p); }
            text(c,String.valueOf(d),cx,cy+14,31,d==today?TEXT:(col>=5?ACCENT2:MUTED),d==today,Paint.Align.CENTER);
        }
        card(c,72,2220,1368,2480,Color.argb(220,22,34,56),34);
        text(c,"SYSTEM WALL CLOCK",116,2290,24,ACCENT2,true);
        text(c,now("yyyy-MM-dd  HH:mm:ss"),116,2370,34,TEXT,true);
        text(c,"Android host time · UI prototype",116,2430,23,MUTED,false);
        drawDock(c);
    }

    private void drawGestureBar(Canvas c) {
        pill(c, 550, 3036, 890, 3064, Color.argb(210,230,238,248));
    }

    @Override public boolean onTouchEvent(android.view.MotionEvent e) {
        float x=e.getX()/sx, y=e.getY()/sy;
        if(e.getAction()==MotionEvent.ACTION_DOWN){downX=x;downY=y;return true;}
        if(e.getAction()==MotionEvent.ACTION_UP){
            float dy=y-downY;
            if(downY<300 && dy>180){screen=Screen.QUICK;invalidate();return true;}
            if(downY>2850 && dy<-180){screen=Screen.HOME;invalidate();return true;}
            handleTap(x,y); invalidate(); return true;
        }
        return true;
    }

    private void handleTap(float x,float y){
        if(y>2960){screen=Screen.HOME;return;}
        if(screen==Screen.HOME){
            if(x>1120 && y<230){screen=Screen.QUICK;return;}
            if(x<620 && y>320 && y<760){screen=Screen.CALENDAR;return;}
            if(hit(x,y,76,1030,644,1390)){screen=Screen.FILES;return;}
            if(hit(x,y,796,1030,1364,1390)){screen=Screen.TERMINAL;return;}
            if(hit(x,y,76,1460,644,1820) || hit(x,y,796,1460,1364,1820)){screen=Screen.SETTINGS;return;}
        }
        if(screen==Screen.FILES){
            for(int i=0;i<6;i++){float yy=590+i*248;if(hit(x,y,72,yy,1368,yy+206)){selectedFile=i;return;}}
        }
        if(screen==Screen.TERMINAL && y>2430 && y<2605){int i=(int)((x-74)/329); if(i>=0&&i<4)terminalMode=i;}
        if(screen==Screen.QUICK){
            if(hit(x,y,72,760,702,1046))wifi=!wifi;
            else if(hit(x,y,738,760,1368,1046))bluetooth=!bluetooth;
            else if(hit(x,y,72,1100,702,1386))night=!night;
            else if(hit(x,y,738,1100,1368,1386))performance=!performance;
            else if(hit(x,y,72,1440,702,1726))sync=!sync;
            else if(hit(x,y,738,1440,1368,1726))sound=!sound;
        }
        if(y>2590 && y<2960){
            if(x<360)screen=Screen.FILES;
            else if(x<660)screen=Screen.TERMINAL;
            else if(x<960)screen=Screen.SETTINGS;
            else screen=Screen.QUICK;
        }
    }

    private boolean hit(float x,float y,float l,float t,float rr,float b){return x>=l&&x<=rr&&y>=t&&y<=b;}
    private String now(String pattern){return new SimpleDateFormat(pattern,Locale.US).format(new Date());}

    private void appCard(Canvas c,float l,float t,float rr,float b,String title,String sub,String glyph,int color){
        card(c,l,t,rr,b,Color.argb(220,22,34,56),38);
        circleIcon(c,l+86,t+88,50,Color.argb(255,39,61,91),glyph);
        p.setColor(color);c.drawRoundRect(new RectF(l+42,b-18,rr-42,b-10),4,4,p);
        text(c,title,l+42,t+210,31,TEXT,true); text(c,sub,l+42,t+258,23,MUTED,false);
    }
    private void circleIcon(Canvas c,float cx,float cy,float rad,int color,String glyph){p.setColor(color);c.drawCircle(cx,cy,rad,p);text(c,glyph,cx,cy+13,34,TEXT,true,Paint.Align.CENTER);}
    private void card(Canvas c,float l,float t,float rr,float b,int color,float radius){p.setColor(color);c.drawRoundRect(new RectF(l,t,rr,b),radius,radius,p);stroke.setColor(Color.argb(120,70,91,118));c.drawRoundRect(new RectF(l+.5f,t+.5f,rr-.5f,b-.5f),radius,radius,stroke);}
    private void pill(Canvas c,float l,float t,float rr,float b,int color){p.setColor(color);float rad=(b-t)/2f;c.drawRoundRect(new RectF(l,t,rr,b),rad,rad,p);}
    private void dot(Canvas c,float x,float y,float rad,int color){p.setColor(color);c.drawCircle(x,y,rad,p);}
    private void text(Canvas c,String s,float x,float y,float size,int color,boolean bold){text(c,s,x,y,size,color,bold,Paint.Align.LEFT);}
    private void text(Canvas c,String s,float x,float y,float size,int color,boolean bold,Paint.Align align){p.setShader(null);p.setColor(color);p.setTextSize(size);p.setTextAlign(align);p.setTypeface(bold?Typeface.create("sans-serif",Typeface.BOLD):Typeface.create("sans-serif",Typeface.NORMAL));c.drawText(s,x,y,p);}
}
