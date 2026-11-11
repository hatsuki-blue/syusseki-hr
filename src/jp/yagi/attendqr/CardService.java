package jp.yagi.attendqr;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.app.KeyguardManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.drawable.BitmapDrawable;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.Calendar;

/**
 * 本物のロック画面の上に、小さなカードを浮かべるためのユーザー補助サービス。
 * 画面内容の読み取りやイベントの受信は一切行わない（重ねて表示するためだけに使う）。
 */
public class CardService extends AccessibilityService {
    private static CardService instance;

    private static final int WRAP = ViewGroup.LayoutParams.WRAP_CONTENT;
    private static final int MATCH = ViewGroup.LayoutParams.MATCH_PARENT;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private WindowManager wm;
    private FrameLayout holder;
    private WindowManager.LayoutParams lp;
    private boolean shown;
    private boolean receiverRegistered;
    private int state; // 0=小さいチップ 1=カード 2=QRを大きく

    /** このサービスが有効で、かつ「小さなカード」方式が選ばれているか。 */
    static boolean active(Context c) {
        return instance != null && Prefs.mode(c) == Prefs.MODE_FLOAT;
    }

    static boolean connected() {
        return instance != null;
    }

    /** 設定や時刻が変わったときに呼ぶ。 */
    static void poke() {
        final CardService s = instance;
        if (s != null) {
            s.handler.post(new Runnable() {
                @Override
                public void run() {
                    s.update();
                }
            });
        }
    }

    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String a = intent.getAction();
            if (Intent.ACTION_SCREEN_OFF.equals(a)) {
                state = restState(); // 次に点けたときは標準の大きさから
                hide();
            } else if (Intent.ACTION_USER_PRESENT.equals(a)) {
                hide();
            } else {
                update();
            }
        }
    };

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        instance = this;
        try {
            // 画面のイベントは何も受け取らない
            AccessibilityServiceInfo info = getServiceInfo();
            if (info != null) {
                info.eventTypes = 0;
                setServiceInfo(info);
            }
        } catch (Exception ignored) {
        }
        wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        state = restState();
        if (!receiverRegistered) {
            IntentFilter f = new IntentFilter();
            f.addAction(Intent.ACTION_SCREEN_ON);
            f.addAction(Intent.ACTION_SCREEN_OFF);
            f.addAction(Intent.ACTION_USER_PRESENT);
            f.addAction(Intent.ACTION_TIME_TICK);
            if (Build.VERSION.SDK_INT >= 33) {
                registerReceiver(receiver, f, Context.RECEIVER_NOT_EXPORTED);
            } else {
                registerReceiver(receiver, f);
            }
            receiverRegistered = true;
        }
        Scheduler.sync(this);
        update();
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        // 何もしない
    }

    @Override
    public void onInterrupt() {
    }

    @Override
    public boolean onUnbind(Intent intent) {
        shutdown();
        return super.onUnbind(intent);
    }

    @Override
    public void onDestroy() {
        shutdown();
        super.onDestroy();
    }

    private void shutdown() {
        hide();
        if (receiverRegistered) {
            try {
                unregisterReceiver(receiver);
            } catch (Exception ignored) {
            }
            receiverRegistered = false;
        }
        if (instance == this) {
            instance = null;
            try {
                Scheduler.sync(this); // 通知カード方式に戻す
            } catch (Exception ignored) {
            }
        }
    }

    private int dp(float v) {
        return Ui.dp(this, v);
    }

    /** いま表示すべきかを判定して、出す/消す/内容更新を行う。 */
    private void update() {
        KeyguardManager km = (KeyguardManager) getSystemService(KEYGUARD_SERVICE);
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        boolean want = Prefs.mode(this) == Prefs.MODE_FLOAT
                && Scheduler.isActiveNow(this)
                && km != null && km.isKeyguardLocked()
                && pm != null && pm.isInteractive();
        if (want) {
            show();
        } else {
            hide();
        }
    }

    private void hide() {
        if (shown && holder != null) {
            try {
                wm.removeView(holder);
            } catch (Exception ignored) {
            }
        }
        shown = false;
        holder = null;
    }

    private void show() {
        if (wm == null) return;
        if (!shown) {
            holder = new FrameLayout(this);
            lp = new WindowManager.LayoutParams(WRAP, WRAP,
                    WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                            | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                    PixelFormat.TRANSLUCENT);
            lp.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
            lp.y = clampY(Prefs.sp(this).getInt(Prefs.K_CARD_Y, dp(190)));
            lp.setTitle("出席QR");
            try {
                wm.addView(holder, lp);
                shown = true;
                Prefs.sp(this).edit()
                        .putLong(Prefs.K_LAST_SHOWN, System.currentTimeMillis()).apply();
            } catch (Exception ex) {
                holder = null;
                return;
            }
        }
        render();
    }

    private int clampY(int y) {
        int h = getResources().getDisplayMetrics().heightPixels;
        return Math.max(dp(24), Math.min(h - dp(160), y));
    }

    /** 上下ドラッグで位置を変えられるようにする。タップは onTap へ。 */
    private void makeHandle(View v, final Runnable onTap) {
        final int slop = ViewConfiguration.get(this).getScaledTouchSlop();
        v.setOnTouchListener(new View.OnTouchListener() {
            float downY;
            int startY;
            boolean dragging;

            @Override
            public boolean onTouch(View view, MotionEvent ev) {
                switch (ev.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        downY = ev.getRawY();
                        startY = lp.y;
                        dragging = false;
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        float dy = ev.getRawY() - downY;
                        if (!dragging && Math.abs(dy) > slop) dragging = true;
                        if (dragging && shown && holder != null) {
                            lp.y = clampY(startY - Math.round(dy));
                            try {
                                wm.updateViewLayout(holder, lp);
                            } catch (Exception ignored) {
                            }
                        }
                        return true;
                    case MotionEvent.ACTION_UP:
                        if (dragging) {
                            Prefs.sp(CardService.this).edit().putInt(Prefs.K_CARD_Y, lp.y).apply();
                        } else if (onTap != null) {
                            onTap.run();
                        }
                        return true;
                    default:
                        return true;
                }
            }
        });
    }

    private void setState(int s) {
        state = s;
        // 「小さく」を選んだかどうかは次回以降も覚えておく
        if (s != 2) Prefs.sp(this).edit().putBoolean(Prefs.K_CARD_SMALL, s == 0).apply();
        render();
    }

    private int restState() {
        return Prefs.sp(this).getBoolean(Prefs.K_CARD_SMALL, false) ? 0 : 1;
    }

    private void markDone() {
        Prefs.sp(this).edit()
                .putInt(Prefs.K_DONE, Prefs.dateKey(Calendar.getInstance()))
                .putLong(Prefs.K_TEST_UNTIL, 0L)
                .commit();
        Scheduler.sync(this);
        update();
    }

    private TextView smallButton(String label, int bg, int fg) {
        TextView t = Ui.text(this, label, 13, fg, Ui.MEDIUM);
        t.setBackground(Ui.ripple(this, Ui.round(this, bg, 100), 100, Ui.alpha(fg, 0x33)));
        t.setPadding(dp(14), dp(10), dp(14), dp(10));
        t.setGravity(Gravity.CENTER);
        return t;
    }

    /** きょうの時間割（1行ずつ）。終わった授業は薄く、いまの授業は強調する。 */
    private void addTimetable(LinearLayout parent, int innerWidth, int accent) {
        Calendar cal = Calendar.getInstance();
        int nowMin = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE);
        java.util.List<TT.Entry> list = TT.forDay(this, Prefs.cls(this), Prefs.dayIndex(cal));

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackground(Ui.round(this, Ui.D_GLASS, 16));
        box.setPadding(dp(14), dp(8), dp(14), dp(8));
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(innerWidth, WRAP);
        blp.topMargin = dp(12);
        parent.addView(box, blp);

        if (list.isEmpty()) {
            TextView none = Ui.text(this, "きょうの授業は登録されていません", 13, Ui.D_TEXT2, Ui.REGULAR);
            none.setPadding(0, dp(6), 0, dp(6));
            box.addView(none);
            return;
        }

        boolean nextMarked = false;
        int i = 0, rows = 0;
        while (i < list.size() && rows < 7) {
            TT.Entry e = list.get(i);
            StringBuilder names = new StringBuilder(e.name);
            String room = e.room;
            int j = i + 1;
            while (j < list.size() && list.get(j).s == e.s && list.get(j).e == e.e) {
                names.append(" / ").append(list.get(j).name);
                room = "";
                j++;
            }
            i = j;
            rows++;

            int st = TT.startOf(e.s), en = TT.endOf(e.e);
            boolean past = nowMin >= en;
            boolean current = nowMin >= st && nowMin < en;
            boolean next = !past && !current && !nextMarked;
            if (current || next) nextMarked = true;
            boolean strong = current || next;
            int nameColor = past ? Ui.D_TEXT3 : (strong ? Ui.D_TEXT : 0xFFD0D7E1);
            int subColor = past ? Ui.D_TEXT3 : Ui.D_TEXT2;

            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(0, dp(6), 0, dp(6));

            View dot = new View(this);
            dot.setBackground(Ui.round(this, strong ? accent : (past ? 0xFF3A4350 : 0xFF6B7684),
                    100));
            LinearLayout.LayoutParams dl = new LinearLayout.LayoutParams(dp(7), dp(7));
            dl.rightMargin = dp(9);
            row.addView(dot, dl);

            TextView time = Ui.text(this, Prefs.hm(st), 13, strong
                    ? Ui.mix(accent, Color.WHITE, 0.45f) : subColor, Ui.MEDIUM);
            row.addView(time, new LinearLayout.LayoutParams(dp(46), WRAP));

            TextView name = Ui.text(this, names.toString(), 14, nameColor,
                    strong ? Ui.BOLD : Ui.MEDIUM);
            name.setSingleLine(true);
            name.setEllipsize(android.text.TextUtils.TruncateAt.END);
            row.addView(name, new LinearLayout.LayoutParams(0, WRAP, 1f));

            if (room.length() > 0) {
                TextView r = Ui.text(this, room, 11, subColor, Ui.REGULAR);
                r.setSingleLine(true);
                r.setEllipsize(android.text.TextUtils.TruncateAt.END);
                r.setMaxWidth(dp(110));
                r.setPadding(dp(8), 0, 0, 0);
                row.addView(r);
            }
            box.addView(row, new LinearLayout.LayoutParams(MATCH, WRAP));
        }
    }

    private void render() {
        if (!shown || holder == null) return;
        final int accent = Ui.accent(this);
        String[] sum = TT.summary(this);
        Bitmap qr = QrService.loadQr(this, 900);
        int screenW = getResources().getDisplayMetrics().widthPixels;
        int tint = Ui.mix(accent, Color.WHITE, 0.4f);

        holder.removeAllViews();
        LinearLayout card = new LinearLayout(this);
        card.setBackground(Ui.round(this, 0xF2141A24, state == 0 ? 100 : 28,
                Ui.alpha(Ui.mix(accent, Color.WHITE, 0.3f), 0x66), 1));

        if (state == 0) {
            // ---- 小さいチップ（「小さく」を選んだとき）
            card.setOrientation(LinearLayout.HORIZONTAL);
            card.setGravity(Gravity.CENTER_VERTICAL);
            card.setPadding(dp(8), dp(8), dp(18), dp(8));
            card.addView(qrView(qr, 5, 12), new LinearLayout.LayoutParams(dp(46), dp(46)));
            LinearLayout texts = new LinearLayout(this);
            texts.setOrientation(LinearLayout.VERTICAL);
            texts.setPadding(dp(12), 0, 0, 0);
            TextView t1 = Ui.text(this, "出席QR ・ " + sum[0], 14, Ui.D_TEXT, Ui.MEDIUM);
            t1.setSingleLine(true);
            t1.setEllipsize(android.text.TextUtils.TruncateAt.END);
            t1.setMaxWidth(Math.min(screenW - dp(150), dp(250)));
            texts.addView(t1);
            TextView t2 = Ui.text(this, sum[1].length() > 0 ? sum[1] : "タップで広げる", 12,
                    Ui.D_TEXT2, Ui.REGULAR);
            t2.setSingleLine(true);
            t2.setEllipsize(android.text.TextUtils.TruncateAt.END);
            t2.setMaxWidth(Math.min(screenW - dp(150), dp(250)));
            t2.setPadding(0, dp(4), 0, 0);
            texts.addView(t2);
            card.addView(texts);
            makeHandle(card, new Runnable() {
                @Override
                public void run() {
                    setState(1);
                }
            });
        } else if (state == 1) {
            // ---- 標準のカード：大きめのQR＋いま/つぎの授業＋きょうの時間割
            int width = Math.min(screenW - dp(24), dp(420));
            int inner = width - dp(28);
            card.setOrientation(LinearLayout.VERTICAL);
            card.setPadding(dp(14), dp(14), dp(14), dp(14));

            LinearLayout top = new LinearLayout(this);
            top.setOrientation(LinearLayout.HORIZONTAL);
            top.setGravity(Gravity.CENTER_VERTICAL);

            int qrSize = Math.min(dp(156), Math.round(inner * 0.46f));
            View q = qrView(qr, 9, 18);
            q.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    setState(2);
                }
            });
            top.addView(q, new LinearLayout.LayoutParams(qrSize, qrSize));

            LinearLayout right = new LinearLayout(this);
            right.setOrientation(LinearLayout.VERTICAL);
            right.setPadding(dp(14), 0, 0, 0);
            Calendar cal = Calendar.getInstance();
            right.addView(Ui.text(this, "出席QR ・ " + Prefs.cls(this) + " ・ "
                    + TT.DAY_NAMES[Prefs.dayIndex(cal)] + "曜", 11, tint, Ui.MEDIUM));
            TextView h = Ui.text(this, sum[0], 17, Ui.D_TEXT, Ui.BOLD);
            h.setMaxLines(2);
            h.setEllipsize(android.text.TextUtils.TruncateAt.END);
            h.setLineSpacing(dp(2), 1f);
            h.setPadding(0, dp(8), 0, 0);
            right.addView(h);
            if (sum[1].length() > 0) {
                TextView sb = Ui.text(this, sum[1], 12, Ui.D_TEXT2, Ui.REGULAR);
                sb.setMaxLines(2);
                sb.setPadding(0, dp(5), 0, 0);
                right.addView(sb);
            }
            TextView done = smallButton("出席した", accent, Ui.onColor(accent));
            done.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    markDone();
                }
            });
            LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(MATCH, WRAP);
            dlp.topMargin = dp(12);
            right.addView(done, dlp);
            LinearLayout mini = new LinearLayout(this);
            mini.setOrientation(LinearLayout.HORIZONTAL);
            TextView bigger = smallButton("QR拡大", Ui.D_GLASS, Ui.D_TEXT);
            bigger.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    setState(2);
                }
            });
            TextView smaller = smallButton("小さく", Ui.D_GLASS, Ui.D_TEXT);
            smaller.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    setState(0);
                }
            });
            LinearLayout.LayoutParams m1 = new LinearLayout.LayoutParams(0, WRAP, 1f);
            LinearLayout.LayoutParams m2 = new LinearLayout.LayoutParams(0, WRAP, 1f);
            m2.leftMargin = dp(6);
            mini.addView(bigger, m1);
            mini.addView(smaller, m2);
            LinearLayout.LayoutParams mlp = new LinearLayout.LayoutParams(MATCH, WRAP);
            mlp.topMargin = dp(6);
            right.addView(mini, mlp);
            top.addView(right, new LinearLayout.LayoutParams(0, WRAP, 1f));
            card.addView(top, new LinearLayout.LayoutParams(inner, WRAP));

            addTimetable(card, inner, accent);
            makeHandle(card, null);
        } else {
            // ---- QRを大きく
            int width = Math.min(screenW - dp(24), dp(420));
            int inner = width - dp(28);
            card.setOrientation(LinearLayout.VERTICAL);
            card.setPadding(dp(14), dp(12), dp(14), dp(14));

            LinearLayout head = new LinearLayout(this);
            head.setOrientation(LinearLayout.HORIZONTAL);
            head.setGravity(Gravity.CENTER_VERTICAL);
            TextView title = Ui.text(this, "出席QR ・ " + sum[0], 13, tint, Ui.MEDIUM);
            title.setSingleLine(true);
            title.setEllipsize(android.text.TextUtils.TruncateAt.END);
            title.setPadding(dp(2), dp(6), dp(8), dp(6));
            head.addView(title, new LinearLayout.LayoutParams(0, WRAP, 1f));
            TextView back = smallButton("戻す", Ui.D_GLASS, Ui.D_TEXT);
            back.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    setState(restState() == 0 ? 0 : 1);
                }
            });
            head.addView(back);
            card.addView(head, new LinearLayout.LayoutParams(inner, WRAP));

            View q = qrView(qr, 14, 20);
            q.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    setState(restState() == 0 ? 0 : 1);
                }
            });
            LinearLayout.LayoutParams qlp = new LinearLayout.LayoutParams(inner, inner);
            qlp.topMargin = dp(10);
            card.addView(q, qlp);

            TextView done = smallButton("出席した（今日は消す）", accent, Ui.onColor(accent));
            done.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    markDone();
                }
            });
            LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(inner, WRAP);
            dlp.topMargin = dp(12);
            card.addView(done, dlp);
            makeHandle(card, null);
        }

        holder.addView(card, new FrameLayout.LayoutParams(WRAP, WRAP));

        // QRが見えている間は明るくして読み取りやすくする
        float want = state != 0 && Prefs.bright(this) ? 1.0f
                : WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE;
        if (lp.screenBrightness != want) {
            lp.screenBrightness = want;
        }
        try {
            wm.updateViewLayout(holder, lp);
        } catch (Exception ignored) {
        }
    }

    private View qrView(Bitmap qr, float padDp, float radiusDp) {
        FrameLayout paper = new FrameLayout(this);
        paper.setBackground(Ui.round(this, Color.WHITE, radiusDp));
        paper.setPadding(dp(padDp), dp(padDp), dp(padDp), dp(padDp));
        if (qr != null) {
            ImageView iv = new ImageView(this);
            iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
            BitmapDrawable d = new BitmapDrawable(getResources(), qr);
            d.setFilterBitmap(qr.getWidth() >= 500);
            iv.setImageDrawable(d);
            paper.addView(iv, new FrameLayout.LayoutParams(MATCH, MATCH));
        } else {
            TextView t = Ui.text(this, "QR\n未設定", 10, 0xFF55606E, Ui.MEDIUM);
            t.setGravity(Gravity.CENTER);
            paper.addView(t, new FrameLayout.LayoutParams(MATCH, MATCH));
        }
        return paper;
    }
}
