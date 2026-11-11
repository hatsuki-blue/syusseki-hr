package jp.yagi.attendqr;

import android.app.Activity;
import android.app.KeyguardManager;
import android.app.NotificationManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextClock;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;

/** ロック画面の上に出る画面：QRコード＋本日の時間割。 */
public class LockActivity extends Activity {
    static final String ACTION_CLOSE = "jp.yagi.attendqr.CLOSE";
    static final String EXTRA_WAKE = "wake";
    static final String EXTRA_PREVIEW = "preview";

    /** 画面が前面に出ているか（サービス側の保険判定に使う）。 */
    static volatile boolean visible;

    private static final long KEEP_ON_MS = 90000L;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean preview;
    private boolean receiverRegistered;
    private boolean zoomed;
    private int accent;

    private ScrollView scroll;
    private LinearLayout root;
    private View header;
    private TextView dateView;
    private TextView classChip;
    private FrameLayout qrFrame;
    private ImageView qrView;
    private TextView qrHint;
    private TextView qrCaption;
    private LinearLayout heroBox;
    private LinearLayout ttBox;
    private TextView doneBtn;

    private final Runnable releaseKeepOn = new Runnable() {
        @Override
        public void run() {
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }
    };

    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (ACTION_CLOSE.equals(intent.getAction())) {
                if (!preview) finish();
            } else {
                // 1分ごと：表示を更新し、時間帯が終わっていれば閉じる
                if (!preview && !Scheduler.isActiveNow(LockActivity.this)) {
                    finish();
                } else {
                    refresh();
                }
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setShowWhenLocked(true);
        applyIntent(getIntent());
        buildUi();
        // 画面が消えている間も「終了」の合図を受け取れるよう、生存中ずっと登録しておく
        IntentFilter f = new IntentFilter();
        f.addAction(ACTION_CLOSE);
        f.addAction(Intent.ACTION_TIME_TICK);
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(receiver, f, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(receiver, f);
        }
        receiverRegistered = true;
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        applyIntent(intent);
    }

    private void applyIntent(Intent intent) {
        preview = intent != null && intent.getBooleanExtra(EXTRA_PREVIEW, false);
        boolean wake = intent != null && intent.getBooleanExtra(EXTRA_WAKE, false);
        setTurnScreenOn(wake);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (!preview && !Scheduler.isActiveNow(this)) {
            finish();
            return;
        }
        visible = true;
        if (!preview) {
            Prefs.sp(this).edit().putLong(Prefs.K_LAST_SHOWN, System.currentTimeMillis()).apply();
        }
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm != null) nm.cancel(QrService.ID_LAUNCH);

        WindowManager.LayoutParams lp = getWindow().getAttributes();
        lp.screenBrightness = Prefs.bright(this) ? 1.0f
                : WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE;
        getWindow().setAttributes(lp);

        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        handler.removeCallbacks(releaseKeepOn);
        handler.postDelayed(releaseKeepOn, KEEP_ON_MS);

        if (accent != Ui.accent(this)) {
            buildUi();
        }
        refresh();
    }

    @Override
    protected void onPause() {
        visible = false;
        handler.removeCallbacks(releaseKeepOn);
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        visible = false;
        handler.removeCallbacksAndMessages(null);
        if (receiverRegistered) {
            try {
                unregisterReceiver(receiver);
            } catch (Exception ignored) {
            }
            receiverRegistered = false;
        }
        super.onDestroy();
    }

    // ---------------------------------------------------------------- UI

    private int dp(float v) {
        return Ui.dp(this, v);
    }

    private LinearLayout.LayoutParams lp(int w, int h) {
        return new LinearLayout.LayoutParams(w, h);
    }

    private static final int MATCH = ViewGroup.LayoutParams.MATCH_PARENT;
    private static final int WRAP = ViewGroup.LayoutParams.WRAP_CONTENT;

    private void buildUi() {
        accent = Ui.accent(this);

        scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        // 背景はステータスバーの裏まで一枚で描く
        getWindow().setBackgroundDrawable(Ui.lockBackground(this, accent));
        getWindow().setDecorFitsSystemWindows(false);
        scroll.setFitsSystemWindows(true);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setOverScrollMode(View.OVER_SCROLL_NEVER);

        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(22), dp(26), dp(22), dp(22));
        scroll.addView(root, new ScrollView.LayoutParams(MATCH, WRAP));

        // ---- 時計・日付・クラス
        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.BOTTOM);
        LinearLayout headLeft = new LinearLayout(this);
        headLeft.setOrientation(LinearLayout.VERTICAL);
        TextClock clock = new TextClock(this);
        clock.setFormat12Hour("H:mm");
        clock.setFormat24Hour("H:mm");
        clock.setTextSize(TypedValue.COMPLEX_UNIT_SP, 60);
        clock.setTextColor(Ui.D_TEXT);
        clock.setTypeface(Ui.face(Ui.LIGHT));
        clock.setIncludeFontPadding(false);
        clock.setLetterSpacing(-0.02f);
        headLeft.addView(clock);
        dateView = Ui.text(this, "", 15, Ui.mix(accent, Color.WHITE, 0.45f), Ui.MEDIUM);
        dateView.setPadding(dp(3), dp(6), 0, 0);
        dateView.setLetterSpacing(0.04f);
        headLeft.addView(dateView);
        head.addView(headLeft, new LinearLayout.LayoutParams(0, WRAP, 1f));
        classChip = Ui.text(this, "", 13, Ui.D_TEXT, Ui.MEDIUM);
        classChip.setBackground(Ui.round(this, Ui.D_GLASS, 100, Ui.D_STROKE, 1));
        classChip.setPadding(dp(14), dp(7), dp(14), dp(7));
        head.addView(classChip);
        header = head;
        root.addView(head, lp(MATCH, WRAP));

        // ---- QRコード：アクセント色のグラデーション枠＋白い台紙
        qrFrame = new FrameLayout(this);
        qrFrame.setBackground(Ui.gradient(this, accent,
                Ui.mix(accent, 0xFF9B5CFF, 0.55f), 30, GradientDrawable.Orientation.TL_BR));
        qrFrame.setPadding(dp(3), dp(3), dp(3), dp(3));
        FrameLayout paper = new FrameLayout(this);
        paper.setBackground(Ui.round(this, Color.WHITE, 27));
        paper.setPadding(dp(16), dp(16), dp(16), dp(16));
        qrView = new ImageView(this);
        qrView.setScaleType(ImageView.ScaleType.FIT_CENTER);
        paper.addView(qrView, new FrameLayout.LayoutParams(MATCH, MATCH));
        qrHint = Ui.text(this, "QR画像が未設定です\nアプリを開いて画像を選んでください", 14, 0xFF55606E,
                Ui.REGULAR);
        qrHint.setGravity(Gravity.CENTER);
        qrHint.setLineSpacing(dp(4), 1f);
        paper.addView(qrHint, new FrameLayout.LayoutParams(MATCH, MATCH));
        qrFrame.addView(paper, new FrameLayout.LayoutParams(MATCH, MATCH));
        qrFrame.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                zoomed = !zoomed;
                applyZoom();
            }
        });
        root.addView(qrFrame);

        qrCaption = Ui.text(this, "", 12, Ui.D_TEXT2, Ui.REGULAR);
        qrCaption.setGravity(Gravity.CENTER);
        qrCaption.setPadding(0, dp(10), 0, 0);
        root.addView(qrCaption, lp(MATCH, WRAP));

        // ---- いま/つぎの授業
        heroBox = new LinearLayout(this);
        heroBox.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams hlp = lp(MATCH, WRAP);
        hlp.topMargin = dp(18);
        root.addView(heroBox, hlp);

        // ---- 本日の時間割
        ttBox = new LinearLayout(this);
        ttBox.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams tlp = lp(MATCH, WRAP);
        tlp.topMargin = dp(18);
        root.addView(ttBox, tlp);

        // ---- 余白（ボタンを下に寄せる）
        root.addView(new View(this), new LinearLayout.LayoutParams(MATCH, 0, 1f));

        // ---- ボタン
        LinearLayout buttons = new LinearLayout(this);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        buttons.setGravity(Gravity.CENTER);
        TextView close = Ui.ghost(this, "閉じる", Ui.D_STROKE, Ui.D_TEXT);
        close.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });
        TextView unlock = Ui.ghost(this, "ロック解除", Ui.D_STROKE, Ui.D_TEXT);
        unlock.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                KeyguardManager km = (KeyguardManager) getSystemService(KEYGUARD_SERVICE);
                if (km != null && km.isKeyguardLocked()) {
                    km.requestDismissKeyguard(LockActivity.this,
                            new KeyguardManager.KeyguardDismissCallback() {
                                @Override
                                public void onDismissSucceeded() {
                                    finish();
                                }
                            });
                } else {
                    finish();
                }
            }
        });
        doneBtn = Ui.pill(this, "出席した", accent, Ui.onColor(accent));
        doneBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (!preview) {
                    Prefs.sp(LockActivity.this).edit()
                            .putInt(Prefs.K_DONE, Prefs.dateKey(Calendar.getInstance()))
                            .putLong(Prefs.K_TEST_UNTIL, 0L)
                            .commit();
                    Scheduler.sync(LockActivity.this);
                }
                finish();
            }
        });
        LinearLayout.LayoutParams b1 = new LinearLayout.LayoutParams(0, WRAP, 1f);
        LinearLayout.LayoutParams b2 = new LinearLayout.LayoutParams(0, WRAP, 1.25f);
        b2.leftMargin = dp(8);
        LinearLayout.LayoutParams b3 = new LinearLayout.LayoutParams(0, WRAP, 1.25f);
        b3.leftMargin = dp(8);
        buttons.addView(close, b1);
        buttons.addView(unlock, b2);
        buttons.addView(doneBtn, b3);
        LinearLayout.LayoutParams rowLp = lp(MATCH, WRAP);
        rowLp.topMargin = dp(20);
        root.addView(buttons, rowLp);

        setContentView(scroll);
        applyZoom();
    }

    private void applyZoom() {
        int screenW = getResources().getDisplayMetrics().widthPixels;
        int size = zoomed ? screenW - dp(28) : Math.min(Math.round(screenW * 0.64f), dp(290));
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(size, size);
        p.gravity = Gravity.CENTER_HORIZONTAL;
        p.topMargin = dp(zoomed ? 18 : 22);
        qrFrame.setLayoutParams(p);
        root.setPadding(dp(zoomed ? 14 : 22), dp(26), dp(zoomed ? 14 : 22), dp(22));
        int v = zoomed ? View.GONE : View.VISIBLE;
        heroBox.setVisibility(v);
        ttBox.setVisibility(v);
        qrCaption.setText(zoomed ? "タップで元の大きさに戻す" : "タップで拡大");
    }

    /** 同じ時限に入っている授業（選択科目の候補など）をまとめたもの。 */
    private static final class Slot {
        int s, e;
        final List<TT.Entry> items = new ArrayList<TT.Entry>();

        String names() {
            StringBuilder b = new StringBuilder();
            for (TT.Entry x : items) {
                if (b.length() > 0) b.append(" / ");
                b.append(x.name);
            }
            return b.toString();
        }
    }

    private static String sub(TT.Entry e) {
        String sub = e.room;
        if (e.teacher.length() > 0) sub = sub.length() > 0 ? sub + " ・ " + e.teacher : e.teacher;
        return sub;
    }

    private void refresh() {
        Calendar cal = Calendar.getInstance();
        int day = Prefs.dayIndex(cal);
        dateView.setText((cal.get(Calendar.MONTH) + 1) + "月" + cal.get(Calendar.DAY_OF_MONTH)
                + "日 " + TT.DAY_NAMES[day] + "曜日");
        String cls = Prefs.cls(this);
        classChip.setText(cls);
        doneBtn.setText(preview ? "OK" : "出席した");

        Bitmap qr = QrService.loadQr(this, 1400);
        if (qr != null) {
            BitmapDrawable d = new BitmapDrawable(getResources(), qr);
            // 小さい画像を拡大するときは、ぼかさずくっきり表示する
            d.setFilterBitmap(qr.getWidth() >= 700);
            qrView.setImageDrawable(d);
            qrHint.setVisibility(View.GONE);
        } else {
            qrView.setImageDrawable(null);
            qrHint.setVisibility(View.VISIBLE);
        }

        List<Slot> slots = new ArrayList<Slot>();
        for (TT.Entry e : TT.forDay(this, cls, day)) {
            Slot last = slots.isEmpty() ? null : slots.get(slots.size() - 1);
            if (last == null || last.s != e.s || last.e != e.e) {
                last = new Slot();
                last.s = e.s;
                last.e = e.e;
                slots.add(last);
            }
            last.items.add(e);
        }
        int nowMin = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE);

        Slot current = null, next = null;
        for (Slot s : slots) {
            int st = TT.startOf(s.s), en = TT.endOf(s.e);
            if (current == null && nowMin >= st && nowMin < en) current = s;
            if (next == null && st > nowMin) next = s;
        }

        buildHero(slots, current, next, nowMin);
        buildTimeline(slots, current, next, nowMin);
    }

    private void buildHero(List<Slot> slots, Slot current, Slot next, int nowMin) {
        heroBox.removeAllViews();
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(Ui.round(this, Ui.D_GLASS, 22, Ui.D_STROKE, 1));
        card.setPadding(dp(18), dp(16), dp(18), dp(16));
        heroBox.addView(card, lp(MATCH, WRAP));

        int tint = Ui.mix(accent, Color.WHITE, 0.35f);
        Slot show = current != null ? current : next;
        if (show == null) {
            card.addView(Ui.text(this, slots.isEmpty() ? "きょうの授業" : "おつかれさまでした", 12,
                    tint, Ui.MEDIUM));
            TextView t = Ui.text(this, slots.isEmpty() ? "登録された授業はありません" : "本日の授業はすべて終了",
                    18, Ui.D_TEXT, Ui.MEDIUM);
            t.setPadding(0, dp(8), 0, 0);
            card.addView(t);
            return;
        }

        int st = TT.startOf(show.s), en = TT.endOf(show.e);
        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        String label = current != null
                ? "授業中 ・ 残り" + Ui.remain(en - nowMin)
                : "つぎの授業 ・ あと" + Ui.remain(st - nowMin);
        top.addView(Ui.text(this, label, 12, tint, Ui.MEDIUM),
                new LinearLayout.LayoutParams(0, WRAP, 1f));
        top.addView(Ui.text(this, Prefs.hm(st) + " – " + Prefs.hm(en), 12, Ui.D_TEXT2, Ui.REGULAR));
        card.addView(top, lp(MATCH, WRAP));

        TextView name = Ui.text(this, show.names(), 21, Ui.D_TEXT, Ui.BOLD);
        name.setPadding(0, dp(9), 0, 0);
        name.setLineSpacing(dp(2), 1f);
        card.addView(name);

        String sub = show.items.size() == 1 ? sub(show.items.get(0)) : "選択科目（" + show.items.size()
                + "件の候補）";
        String per = (show.s == show.e ? String.valueOf(show.s) : show.s + "-" + show.e) + "限";
        TextView s2 = Ui.text(this, sub.length() > 0 ? per + " ・ " + sub : per, 13, Ui.D_TEXT2,
                Ui.REGULAR);
        s2.setPadding(0, dp(7), 0, 0);
        card.addView(s2);

        if (current != null && en > st) {
            float done = Math.max(0f, Math.min(1f, (nowMin - st) / (float) (en - st)));
            LinearLayout bar = new LinearLayout(this);
            bar.setOrientation(LinearLayout.HORIZONTAL);
            bar.setBackground(Ui.round(this, 0x22FFFFFF, 100));
            View fill = new View(this);
            fill.setBackground(Ui.round(this, accent, 100));
            bar.addView(fill, new LinearLayout.LayoutParams(0, MATCH, Math.max(done, 0.02f)));
            bar.addView(new View(this), new LinearLayout.LayoutParams(0, MATCH, 1f - done));
            LinearLayout.LayoutParams blp = lp(MATCH, dp(4));
            blp.topMargin = dp(14);
            card.addView(bar, blp);
        }
    }

    private void buildTimeline(List<Slot> slots, Slot current, Slot next, int nowMin) {
        ttBox.removeAllViews();
        if (slots.isEmpty()) return;

        TextView title = Ui.text(this, "きょうの時間割", 12, Ui.D_TEXT2, Ui.MEDIUM);
        title.setLetterSpacing(0.06f);
        title.setPadding(dp(2), 0, 0, dp(10));
        ttBox.addView(title);

        for (int i = 0; i < slots.size(); i++) {
            Slot s = slots.get(i);
            int st = TT.startOf(s.s), en = TT.endOf(s.e);
            boolean isCurrent = s == current;
            boolean isNext = current == null && s == next;
            boolean past = nowMin >= en;

            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);

            LinearLayout time = new LinearLayout(this);
            time.setOrientation(LinearLayout.VERTICAL);
            time.setGravity(Gravity.RIGHT);
            time.setPadding(0, dp(1), 0, 0);
            time.addView(Ui.text(this, Prefs.hm(st), 13, past ? Ui.D_TEXT3 : Ui.D_TEXT, Ui.MEDIUM));
            TextView endT = Ui.text(this, Prefs.hm(en), 11, Ui.D_TEXT3, Ui.REGULAR);
            endT.setPadding(0, dp(3), 0, 0);
            time.addView(endT);
            row.addView(time, new LinearLayout.LayoutParams(dp(44), WRAP));

            Rail rail = new Rail(this);
            rail.first = i == 0;
            rail.last = i == slots.size() - 1;
            rail.state = isCurrent || isNext ? 2 : (past ? 0 : 1);
            rail.accent = accent;
            row.addView(rail, new LinearLayout.LayoutParams(dp(30), MATCH));

            LinearLayout body = new LinearLayout(this);
            body.setOrientation(LinearLayout.VERTICAL);
            body.setPadding(0, 0, 0, dp(16));
            for (int k = 0; k < s.items.size(); k++) {
                TT.Entry e = s.items.get(k);
                TextView n = Ui.text(this, e.name, 15, past ? Ui.D_TEXT3
                        : (isCurrent || isNext ? Ui.D_TEXT : 0xFFD5DBE5), Ui.MEDIUM);
                if (k > 0) n.setPadding(0, dp(8), 0, 0);
                body.addView(n);
                String sb = sub(e);
                if (sb.length() > 0) {
                    TextView x = Ui.text(this, sb, 12, past ? Ui.D_TEXT3 : Ui.D_TEXT2, Ui.REGULAR);
                    x.setPadding(0, dp(4), 0, 0);
                    body.addView(x);
                }
            }
            row.addView(body, new LinearLayout.LayoutParams(0, WRAP, 1f));

            TextView per = Ui.text(this, (s.s == s.e ? String.valueOf(s.s) : s.s + "-" + s.e) + "限",
                    11, isCurrent || isNext ? Ui.mix(accent, Color.WHITE, 0.35f) : Ui.D_TEXT3,
                    Ui.MEDIUM);
            per.setPadding(dp(8), dp(2), 0, 0);
            row.addView(per);

            ttBox.addView(row, lp(MATCH, WRAP));
        }
    }

    /** タイムラインの縦線と丸印。 */
    private static final class Rail extends View {
        boolean first, last;
        int state; // 0=終了 1=これから 2=いま/つぎ
        int accent;
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);

        Rail(Context c) {
            super(c);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            float d = getResources().getDisplayMetrics().density;
            float cx = getWidth() / 2f;
            float cy = 8f * d;
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(1.5f * d);
            p.setColor(0x2EFFFFFF);
            if (!first) canvas.drawLine(cx, 0, cx, cy - 7f * d, p);
            if (!last) canvas.drawLine(cx, cy + 7f * d, cx, getHeight(), p);
            if (state == 2) {
                p.setStyle(Paint.Style.FILL);
                p.setColor((accent & 0x00FFFFFF) | 0x40000000);
                canvas.drawCircle(cx, cy, 7f * d, p);
                p.setColor(accent);
                canvas.drawCircle(cx, cy, 4f * d, p);
            } else if (state == 1) {
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeWidth(1.6f * d);
                p.setColor(0xFF9AA7B8);
                canvas.drawCircle(cx, cy, 3.6f * d, p);
            } else {
                p.setStyle(Paint.Style.FILL);
                p.setColor(0xFF4A5462);
                canvas.drawCircle(cx, cy, 3f * d, p);
            }
        }
    }
}
