package com.wickedapp.rokidtgmockup;

import android.app.Activity;
import android.os.Bundle;
import android.graphics.*;
import android.view.*;
import android.content.Context;
import android.util.AttributeSet;

public class MainActivity extends Activity {
    private MockView view;
    @Override public void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN, WindowManager.LayoutParams.FLAG_FULLSCREEN);
        view = new MockView(this);
        setContentView(view);
    }
    @Override public boolean onKeyUp(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN) { view.move(1); return true; }
        if (keyCode == KeyEvent.KEYCODE_DPAD_UP) { view.move(-1); return true; }
        if (keyCode == KeyEvent.KEYCODE_ENTER || keyCode == KeyEvent.KEYCODE_DPAD_CENTER) { view.advance(); return true; }
        if (keyCode == KeyEvent.KEYCODE_BACK) { if (view.back()) return true; }
        return super.onKeyUp(keyCode, event);
    }

    static class MockView extends View {
        final int GREEN = Color.rgb(64,255,94);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        Paint stroke40 = new Paint(Paint.ANTI_ALIAS_FLAG);
        Paint stroke80 = new Paint(Paint.ANTI_ALIAS_FLAG);
        int screen = 0; // 0 list, 1 chat, 2 composer, 3 banner
        int focus = 0;
        String[] names = {"Jane / Hermes", "Rokid Dev", "Family"};
        String[] meta = {"09:42  ●", "09:18  ○", "Yesterday  ○"};
        String[] previews = {"先看 mockup，再接 TDLib live data", "BT-PAN online · helper ready", "今晚吃饭?"};
        public MockView(Context c) { super(c); init(); }
        public MockView(Context c, AttributeSet a) { super(c,a); init(); }
        private void init() {
            setFocusable(true); requestFocus();
            p.setTypeface(Typeface.create("sans", Typeface.NORMAL));
            stroke40.setStyle(Paint.Style.STROKE); stroke40.setStrokeWidth(2); stroke40.setColor(Color.argb(102,64,255,94));
            stroke80.setStyle(Paint.Style.STROKE); stroke80.setStrokeWidth(2); stroke80.setColor(Color.argb(204,64,255,94));
        }
        void move(int d) { if (screen == 0) { focus = (focus + d + 3) % 3; invalidate(); } }
        void advance() { screen = (screen + 1) % 4; invalidate(); }
        boolean back() { if (screen > 0) { screen--; invalidate(); return true; } return false; }
        @Override protected void onDraw(Canvas c) {
            c.drawColor(Color.BLACK);
            drawGuides(c);
            if (screen == 0) drawListScreen(c);
            else if (screen == 1) drawChatScreen(c);
            else if (screen == 2) drawComposer(c);
            else drawBanner(c);
        }
        private void drawGuides(Canvas c) {
            p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(1); p.setColor(Color.argb(35,64,255,94));
            c.drawLine(0,120,480,120,p); c.drawLine(0,520,480,520,p);
        }
        private void title(Canvas c, String t, String sub, String banner) {
            text(c,t,18,48,32,255,true);
            text(c,sub,18,78,16,128,false);
            if (banner != null && banner.length() > 0) { round(c,18,84,462,112,stroke80); text(c,banner,28,105,16,255,false); }
        }
        private void drawListScreen(Canvas c) {
            title(c,"Telegram","3 chats · phone companion online via CXR","");
            rows(c);
            messages(c,338,"Enter: open · ↑↓ choose · Back: system");
        }
        private void drawChatScreen(Canvas c) {
            title(c,names[focus],"CXR live data · media hidden until selected","");
            rows(c);
            messages(c,338,"Hold Enter / two-finger double tap: dictate reply");
        }
        private void drawComposer(Canvas c) {
            title(c,"Voice reply","Phone ASR / TDLib over CXR · interim 50%","LISTENING 7s · cancel with Back");
            round(c,90,158,390,398,stroke80);
            p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(2); p.setColor(GREEN); c.drawCircle(240,244,50,p);
            text(c,"Interim: 我正在用眼鏡回覆…",150,332,18,128,false);
            text(c,"Final: 我正在用眼鏡回覆 Telegram。",132,360,18,255,false);
            text(c,"▂ ▃ ▅ ▇ ▅ ▃  discrete meter",154,394,16,255,false);
            composer(c,"Enter sends · Back returns to chat", "No waveform blobs; no large bright fill");
        }
        private void drawBanner(Canvas c) {
            title(c,"Incoming banner","Off-chat notification in top cautious zone","New · Rokid Dev: helper ready");
            round(c,42,176,438,282,stroke80);
            text(c,"Top strip notification",64,218,20,255,false);
            text(c,"Enter opens · Back dismisses",64,248,18,128,false);
            text(c,"Does not block message stream",64,274,18,128,false);
            messages(c,314,"Enter: return to list");
        }
        private void rows(Canvas c) {
            int y=128;
            for (int i=0;i<3;i++) {
                round(c,12,y,468,y+64, i==focus ? stroke80 : stroke40);
                text(c, i==focus?"▶":" ",24,y+28,18,255,false);
                text(c,names[i],50,y+27,20,255,false);
                text(c,meta[i],350,y+27,16,128,false);
                text(c,previews[i],50,y+55,18,128,false);
                y+=69;
            }
        }
        private void messages(Canvas c, int y, String comp) {
            bubble(c,12,y,432,y+52,stroke40,"Jane","Telegram 放在眼鏡上：只顯示必要訊息。");
            bubble(c,48,y+58,468,y+110,stroke80,"Me","Enter 語音，Swipe 切換，雙擊返回。");
            bubble(c,12,y+116,432,y+168,stroke40,"Jane","UI 要符合 Rokid 安全區。");
            composer(c, comp, "480×400 safe area · no fills/gradients/shadows");
        }
        private void composer(Canvas c, String a, String b) {
            round(c,18,526,462,560,stroke80); text(c,a,28,550,18,255,false); text(c,b,18,590,16,128,false);
        }
        private void bubble(Canvas c,int l,int t,int r,int b,Paint s,String peer,String body){ round(c,l,t,r,b,s); text(c,peer,l+10,t+22,16,128,false); text(c,body,l+10,t+46,18,255,false); }
        private void round(Canvas c, float l,float t,float r,float b,Paint s) { c.drawRoundRect(new RectF(l,t,r,b),12,12,s); }
        private void text(Canvas c,String s,float x,float y,int size,int alpha,boolean bold){ p.setStyle(Paint.Style.FILL); p.setColor(Color.argb(alpha,64,255,94)); p.setTextSize(size); p.setTypeface(Typeface.create("sans", bold?Typeface.BOLD:Typeface.NORMAL)); c.drawText(s,x,y,p); }
    }
}
