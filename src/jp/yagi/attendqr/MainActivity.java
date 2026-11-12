package jp.yagi.attendqr;

import android.Manifest;
import android.app.Activity;
import android.app.AlarmManager;
import android.app.AlertDialog;
import android.app.AppOpsManager;
import android.app.NotificationManager;
import android.app.TimePickerDialog;
import android.content.ComponentName;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.os.Process;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CompoundButton;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.TimePicker;
import android.widget.Toast;

import java.io.FileOutputStream;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.util.Calendar;
import java.util.List;

/** 設定画面。 */
public class MainActivity extends Activity {
    private static final int REQ_PICK = 100;
    private static final int REQ_NOTIF = 101;
    private static final int MATCH = ViewGroup.LayoutParams.MATCH_PARENT;
    private static final int WRAP = ViewGroup.LayoutParams.WRAP_CONTENT;

    // MIUI / HyperOS 独自の権限（AppOps番号）
    private static final int MIUI_OP_SHOW_ON_LOCK = 10020;
    private static final int MIUI_OP_BACKGROUND_START = 10021;

    private int accent;
    private int onAccent;
    private ScrollView scroll;
    private LinearLayout root;
    private TextView statusBig, statusSub, skipLink;
    private LinearLayout todayBox;
    private ImageView qrPreview;
    private TextView qrHint, recropBtn;
    private TextView classValue, startValue, endValue, modeValue;
    private LinearLayout permBox;
    private TextView permSummary;
    private TextView diagView;
    private boolean building;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        QrService.createChannels(this);
        buildUi();
    }

    @Override
    protected void onResume() {
        super.onResume();
        Scheduler.sync(this);
        refreshAll();
    }

    private void refreshAll() {
        refreshStatus();
        refreshToday();
        refreshQr();
        refreshValues();
        refreshPerms();
        diagView.setText("最後に開始処理が動いた時刻：" + Prefs.stamp(Prefs.sp(this).getLong(
                Prefs.K_LAST_ALARM, 0)) + "\n最後にロック画面へ表示した時刻："
                + Prefs.stamp(Prefs.sp(this).getLong(Prefs.K_LAST_SHOWN, 0)));
    }

    // ------------------------------------------------------------ helpers

    private int dp(float v) {
        return Ui.dp(this, v);
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_LONG).show();
    }

    private void changed() {
        Scheduler.sync(this);
        refreshStatus();
    }

    private LinearLayout card(String title) {
        if (title != null) {
            TextView t = Ui.text(this, title, 13, Ui.INK2, Ui.MEDIUM);
            t.setLetterSpacing(0.04f);
            t.setPadding(dp(6), dp(10), 0, dp(8));
            root.addView(t);
        }
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setBackground(Ui.round(this, Ui.CARD, 22));
        c.setPadding(dp(18), dp(16), dp(18), dp(16));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(MATCH, WRAP);
        lp.bottomMargin = dp(12);
        root.addView(c, lp);
        return c;
    }

    private View divider() {
        View v = new View(this);
        v.setBackgroundColor(Ui.LINE);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(MATCH, Math.max(1, dp(1)));
        lp.topMargin = dp(12);
        lp.bottomMargin = dp(12);
        v.setLayoutParams(lp);
        return v;
    }

    /** 「項目名 …… 値 ›」の行。値のTextViewを返す。 */
    private TextView valueRow(LinearLayout parent, String label, View.OnClickListener l) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(6), 0, dp(6));
        row.addView(Ui.text(this, label, 15, Ui.INK, Ui.REGULAR),
                new LinearLayout.LayoutParams(0, WRAP, 1f));
        TextView value = Ui.text(this, "", 15, Ui.mix(accent, Ui.INK, 0.25f), Ui.MEDIUM);
        value.setBackground(Ui.ripple(this, Ui.round(this, Ui.alpha(accent, 0x1F), 100), 100,
                Ui.alpha(accent, 0x33)));
        value.setPadding(dp(14), dp(8), dp(14), dp(8));
        value.setOnClickListener(l);
        row.addView(value);
        parent.addView(row, new LinearLayout.LayoutParams(MATCH, WRAP));
        return value;
    }

    private void tintSwitch(Switch s, int checked, int trackOff) {
        int[][] states = {{android.R.attr.state_checked}, {}};
        s.setThumbTintList(new ColorStateList(states, new int[]{checked, 0xFFFFFFFF}));
        s.setTrackTintList(new ColorStateList(states,
                new int[]{Ui.alpha(checked, 0x88), trackOff}));
    }

    private LinearLayout toggleRow(String title, String desc, boolean value,
                                   CompoundButton.OnCheckedChangeListener l) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(7), 0, dp(7));
        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        texts.addView(Ui.text(this, title, 15, Ui.INK, Ui.REGULAR));
        if (desc != null) {
            TextView d = Ui.text(this, desc, 12, Ui.INK2, Ui.REGULAR);
            d.setPadding(0, dp(4), dp(8), 0);
            d.setLineSpacing(dp(2), 1f);
            texts.addView(d);
        }
        row.addView(texts, new LinearLayout.LayoutParams(0, WRAP, 1f));
        Switch s = new Switch(this);
        s.setChecked(value);
        tintSwitch(s, accent, 0xFFC9D0DA);
        s.setOnCheckedChangeListener(l);
        row.addView(s);
        return row;
    }

    private LinearLayout prefToggle(String title, String desc, final String key, boolean value) {
        return toggleRow(title, desc, value, new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton b, boolean on) {
                if (building) return;
                Prefs.sp(MainActivity.this).edit().putBoolean(key, on).commit();
                changed();
            }
        });
    }

    // ------------------------------------------------------------ UI

    private void buildUi() {
        building = true;
        accent = Ui.accent(this);
        onAccent = Ui.onColor(accent);
        int scrollY = scroll == null ? 0 : scroll.getScrollY();

        scroll = new ScrollView(this);
        scroll.setBackgroundColor(Ui.BG);
        scroll.setFitsSystemWindows(true);
        scroll.setVerticalScrollBarEnabled(false);
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(18), dp(16), dp(32));
        scroll.addView(root);

        // ---- タイトル
        TextView title = Ui.text(this, "出席QR", 28, Ui.INK, Ui.BOLD);
        title.setPadding(dp(6), dp(6), 0, 0);
        root.addView(title);
        TextView lead = Ui.text(this, "朝のロック画面にQRコードと時間割を表示します", 13, Ui.INK2, Ui.REGULAR);
        lead.setPadding(dp(6), dp(6), 0, dp(16));
        root.addView(lead);

        buildHero();
        buildToday();
        buildQr();
        buildSettings();
        buildPerms();

        LinearLayout diag = card("動作の記録");
        diagView = Ui.text(this, "", 12, Ui.INK2, Ui.REGULAR);
        diagView.setLineSpacing(dp(4), 1f);
        diag.addView(diagView);

        setContentView(scroll);
        building = false;
        final int y = scrollY;
        scroll.post(new Runnable() {
            @Override
            public void run() {
                scroll.scrollTo(0, y);
            }
        });
    }

    private void buildHero() {
        LinearLayout hero = new LinearLayout(this);
        hero.setOrientation(LinearLayout.VERTICAL);
        hero.setBackground(Ui.gradient(this, Ui.mix(accent, Color.BLACK, 0.06f), Ui.mix(accent, 0xFF1A1440, 0.6f), 26,
                GradientDrawable.Orientation.TL_BR));
        hero.setPadding(dp(20), dp(20), dp(20), dp(18));
        LinearLayout.LayoutParams hlp = new LinearLayout.LayoutParams(MATCH, WRAP);
        hlp.bottomMargin = dp(12);
        root.addView(hero, hlp);

        int soft = Ui.alpha(Color.WHITE, 0xCC);
        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        statusSub = Ui.text(this, "", 12, soft, Ui.MEDIUM);
        statusSub.setLetterSpacing(0.04f);
        texts.addView(statusSub);
        statusBig = Ui.text(this, "", 24, Color.WHITE, Ui.BOLD);
        statusBig.setPadding(0, dp(8), 0, 0);
        texts.addView(statusBig);
        top.addView(texts, new LinearLayout.LayoutParams(0, WRAP, 1f));
        Switch enable = new Switch(this);
        enable.setChecked(Prefs.enabled(this));
        tintSwitch(enable, Color.WHITE, 0x55FFFFFF);
        enable.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton b, boolean on) {
                if (building) return;
                Prefs.sp(MainActivity.this).edit().putBoolean(Prefs.K_ENABLED, on).commit();
                changed();
            }
        });
        top.addView(enable);
        hero.addView(top, new LinearLayout.LayoutParams(MATCH, WRAP));

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        TextView preview = Ui.pill(this, "プレビュー", Color.WHITE, Ui.mix(accent, 0xFF10151C, 0.55f));
        preview.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivity(new Intent(MainActivity.this, LockActivity.class)
                        .putExtra(LockActivity.EXTRA_PREVIEW, true));
            }
        });
        TextView test = Ui.pill(this, "ロック画面でテスト", 0x33FFFFFF, Color.WHITE);
        test.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Prefs.sp(MainActivity.this).edit()
                        .putLong(Prefs.K_TEST_UNTIL, System.currentTimeMillis() + 180000L)
                        .commit();
                changed();
                toast("3分間テスト表示します。電源ボタンで画面を消し、もう一度点けてロック画面を確認してください");
            }
        });
        LinearLayout.LayoutParams p1 = new LinearLayout.LayoutParams(0, WRAP, 1.1f);
        LinearLayout.LayoutParams p2 = new LinearLayout.LayoutParams(0, WRAP, 1.6f);
        p2.leftMargin = dp(8);
        row.addView(preview, p1);
        row.addView(test, p2);
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(MATCH, WRAP);
        rlp.topMargin = dp(18);
        hero.addView(row, rlp);

        skipLink = Ui.text(this, "", 13, soft, Ui.MEDIUM);
        skipLink.setPadding(0, dp(14), 0, dp(2));
        skipLink.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                onSkipLink();
            }
        });
        hero.addView(skipLink);
    }

    private void buildToday() {
        LinearLayout c = card("きょうの時間割");
        todayBox = new LinearLayout(this);
        todayBox.setOrientation(LinearLayout.VERTICAL);
        c.addView(todayBox, new LinearLayout.LayoutParams(MATCH, WRAP));
        TextView edit = Ui.pill(this, "時間割を確認・編集", Ui.alpha(accent, 0x1F),
                Ui.mix(accent, Ui.INK, 0.25f));
        edit.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivity(new Intent(MainActivity.this, TimetableActivity.class));
            }
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(MATCH, WRAP);
        lp.topMargin = dp(12);
        c.addView(edit, lp);
    }

    private void buildQr() {
        LinearLayout c = card("出席確認用のQRコード");
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);

        FrameLayout frame = new FrameLayout(this);
        frame.setBackground(Ui.round(this, 0xFFF3F5F9, 18, Ui.LINE, 1));
        frame.setPadding(dp(8), dp(8), dp(8), dp(8));
        qrPreview = new ImageView(this);
        qrPreview.setScaleType(ImageView.ScaleType.FIT_CENTER);
        frame.addView(qrPreview, new FrameLayout.LayoutParams(MATCH, MATCH));
        row.addView(frame, new LinearLayout.LayoutParams(dp(120), dp(120)));

        LinearLayout right = new LinearLayout(this);
        right.setOrientation(LinearLayout.VERTICAL);
        right.setPadding(dp(16), 0, 0, 0);
        qrHint = Ui.text(this, "", 13, Ui.INK2, Ui.REGULAR);
        qrHint.setLineSpacing(dp(3), 1f);
        right.addView(qrHint);
        TextView pick = Ui.pill(this, "画像を選ぶ", accent, onAccent);
        pick.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                pickImage();
            }
        });
        LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(MATCH, WRAP);
        plp.topMargin = dp(12);
        right.addView(pick, plp);
        recropBtn = Ui.pill(this, "切り抜き直す", Ui.alpha(accent, 0x1F), Ui.mix(accent, Ui.INK, 0.25f));
        recropBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivity(new Intent(MainActivity.this, CropActivity.class));
            }
        });
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(MATCH, WRAP);
        clp.topMargin = dp(8);
        right.addView(recropBtn, clp);
        row.addView(right, new LinearLayout.LayoutParams(0, WRAP, 1f));
        c.addView(row, new LinearLayout.LayoutParams(MATCH, WRAP));
    }

    private void buildSettings() {
        LinearLayout c = card("表示の設定");
        modeValue = valueRow(c, "ロック画面での出し方", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                pickMode();
            }
        });
        TextView modeHint = Ui.text(this, "カード：本物のロック画面の上に、QRと時間割のカードを浮かべます。上下ドラッグで位置を変えられ、「小さく」でチップ表示にもできます（ユーザー補助の許可が必要。未許可の間は通知カードで出します）。\n通知カード：ロック画面の通知として出します。",
                12, Ui.INK2, Ui.REGULAR);
        modeHint.setLineSpacing(dp(3), 1f);
        modeHint.setPadding(0, dp(2), 0, dp(4));
        c.addView(modeHint);
        c.addView(divider());
        classValue = valueRow(c, "クラス", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                pickClass();
            }
        });
        startValue = valueRow(c, "表示を始める時刻", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                pickTime(true);
            }
        });
        endValue = valueRow(c, "表示を終える時刻", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                pickTime(false);
            }
        });

        c.addView(prefToggle("1限がない日は授業に合わせる",
                "最初の授業が3限からの日などは、表示を後ろへずらします（3限からなら 10:00〜10:40）。時間割をもとに判定します",
                Prefs.K_FOLLOW, Prefs.followFirst(this)));

        // 曜日
        TextView dl = Ui.text(this, "表示する曜日", 15, Ui.INK, Ui.REGULAR);
        dl.setPadding(0, dp(12), 0, dp(10));
        c.addView(dl);
        LinearLayout days = new LinearLayout(this);
        days.setOrientation(LinearLayout.HORIZONTAL);
        final int cur = Prefs.days(this);
        for (int d = 0; d < 7; d++) {
            final int bit = 1 << d;
            boolean on = (cur & bit) != 0;
            TextView chip = Ui.text(this, TT.DAY_NAMES[d], 14, on ? onAccent : Ui.INK2, Ui.MEDIUM);
            chip.setGravity(Gravity.CENTER);
            chip.setBackground(Ui.round(this, on ? accent : 0xFFEEF1F5, 100));
            chip.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    int now = Prefs.days(MainActivity.this);
                    Prefs.sp(MainActivity.this).edit().putInt(Prefs.K_DAYS, now ^ bit).commit();
                    Scheduler.sync(MainActivity.this);
                    rebuild();
                }
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(38), 1f);
            if (d > 0) lp.leftMargin = dp(6);
            days.addView(chip, lp);
        }
        c.addView(days, new LinearLayout.LayoutParams(MATCH, WRAP));

        c.addView(divider());

        // アクセントカラー
        TextView al = Ui.text(this, "テーマカラー", 15, Ui.INK, Ui.REGULAR);
        al.setPadding(0, 0, 0, dp(10));
        c.addView(al);
        LinearLayout sw = new LinearLayout(this);
        sw.setOrientation(LinearLayout.HORIZONTAL);
        for (int i = 0; i < Ui.ACCENTS.length; i++) {
            final int color = Ui.ACCENTS[i];
            FrameLayout ring = new FrameLayout(this);
            boolean sel = color == accent;
            ring.setBackground(Ui.round(this, Color.TRANSPARENT, 100,
                    sel ? color : Color.TRANSPARENT, 2));
            ring.setPadding(dp(5), dp(5), dp(5), dp(5));
            View dot = new View(this);
            dot.setBackground(Ui.round(this, color, 100));
            ring.addView(dot, new FrameLayout.LayoutParams(MATCH, MATCH));
            ring.setContentDescription(Ui.ACCENT_NAMES[i]);
            ring.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    Prefs.sp(MainActivity.this).edit().putInt(Prefs.K_ACCENT, color).commit();
                    rebuild();
                }
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(42), dp(42));
            lp.rightMargin = dp(8);
            sw.addView(ring, lp);
        }
        c.addView(sw);

        c.addView(divider());

        c.addView(prefToggle("最大輝度で表示", "読み取り機にかざしやすくします", Prefs.K_BRIGHT,
                Prefs.bright(this)));
        c.addView(prefToggle("確実モード", "アラーム時計と同じ仕組みで起動します（ステータスバーにアラームのアイコンが出ます）",
                Prefs.K_ALARMCLOCK, Prefs.alarmClock(this)));

        TextView tip = Ui.text(this, "ヒント：クイック設定パネルの編集から「出席QR」タイルを追加すると、時間帯の外でもワンタップで表示できます。",
                12, Ui.INK2, Ui.REGULAR);
        tip.setLineSpacing(dp(3), 1f);
        tip.setBackground(Ui.round(this, 0xFFF3F5F9, 14));
        tip.setPadding(dp(12), dp(10), dp(12), dp(10));
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(MATCH, WRAP);
        tlp.topMargin = dp(10);
        c.addView(tip, tlp);
    }

    private void buildPerms() {
        LinearLayout c = card("必要な許可");
        permSummary = Ui.text(this, "", 15, Ui.INK, Ui.MEDIUM);
        c.addView(permSummary);
        permBox = new LinearLayout(this);
        permBox.setOrientation(LinearLayout.VERTICAL);
        c.addView(permBox, new LinearLayout.LayoutParams(MATCH, WRAP));
    }

    private void rebuild() {
        buildUi();
        refreshAll();
    }

    // ------------------------------------------------------------ refresh

    private void refreshStatus() {
        long end = Scheduler.activeEnd(this);
        Calendar now = Calendar.getInstance();
        int today = Prefs.dateKey(now);
        boolean doneToday = Prefs.sp(this).getInt(Prefs.K_DONE, 0) == today;
        int skip = Prefs.sp(this).getInt(Prefs.K_SKIP, 0);
        long next = Scheduler.nextStart(this);

        if (end > 0) {
            Calendar c = Calendar.getInstance();
            c.setTimeInMillis(end);
            statusSub.setText("ロック画面に表示中");
            statusBig.setText(Prefs.hm(c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE))
                    + " まで");
        } else if (next <= 0) {
            statusSub.setText("自動表示");
            statusBig.setText("オフ");
        } else {
            Calendar c = Calendar.getInstance();
            c.setTimeInMillis(next);
            statusSub.setText(doneToday ? "きょうは出席済み ・ つぎの表示" : "つぎの表示");
            String dayLabel;
            if (Prefs.dateKey(c) == today) {
                dayLabel = "きょう";
            } else {
                dayLabel = (c.get(Calendar.MONTH) + 1) + "/" + c.get(Calendar.DAY_OF_MONTH)
                        + "(" + TT.DAY_NAMES[Prefs.dayIndex(c)] + ")";
            }
            statusBig.setText(dayLabel + " " + Prefs.hm(c.get(Calendar.HOUR_OF_DAY) * 60
                    + c.get(Calendar.MINUTE)));
        }

        // スキップ / 取り消しのリンク
        if (end > 0 && Prefs.testUntil(this) > System.currentTimeMillis()) {
            skipLink.setText("テスト表示を止める");
        } else if (doneToday) {
            skipLink.setText("きょうの「出席した」を取り消す");
        } else if (skip >= today && skip > 0) {
            skipLink.setText("スキップを取り消す（" + (skip / 100 % 100) + "/" + (skip % 100) + "）");
        } else if (next > 0 && end <= 0) {
            skipLink.setText("つぎの1回をスキップする（休みの日など）");
        } else {
            skipLink.setText("");
        }
        skipLink.setVisibility(skipLink.getText().length() > 0 ? View.VISIBLE : View.GONE);
    }

    private void onSkipLink() {
        Calendar now = Calendar.getInstance();
        int today = Prefs.dateKey(now);
        int skip = Prefs.sp(this).getInt(Prefs.K_SKIP, 0);
        if (Prefs.testUntil(this) > System.currentTimeMillis()) {
            Prefs.sp(this).edit().putLong(Prefs.K_TEST_UNTIL, 0L).commit();
        } else if (Prefs.sp(this).getInt(Prefs.K_DONE, 0) == today) {
            Prefs.sp(this).edit().remove(Prefs.K_DONE).commit();
        } else if (skip >= today && skip > 0) {
            Prefs.sp(this).edit().remove(Prefs.K_SKIP).commit();
        } else {
            long next = Scheduler.nextStart(this);
            if (next > 0) {
                Calendar c = Calendar.getInstance();
                c.setTimeInMillis(next);
                Prefs.sp(this).edit().putInt(Prefs.K_SKIP, Prefs.dateKey(c)).commit();
            }
        }
        changed();
    }

    private void refreshToday() {
        todayBox.removeAllViews();
        Calendar cal = Calendar.getInstance();
        int day = Prefs.dayIndex(cal);
        List<TT.Entry> list = TT.forDay(this, Prefs.cls(this), day);
        if (list.isEmpty()) {
            todayBox.addView(Ui.text(this, TT.DAY_NAMES[day] + "曜日は授業が登録されていません", 14, Ui.INK2,
                    Ui.REGULAR));
            return;
        }
        int prevS = -1, prevE = -1;
        for (TT.Entry e : list) {
            boolean same = e.s == prevS && e.e == prevE;
            prevS = e.s;
            prevE = e.e;
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setPadding(0, dp(6), 0, dp(6));
            TextView per = Ui.text(this, same ? "" : TT.periodLabel(e) + "限", 13,
                    Ui.mix(accent, Ui.INK, 0.25f), Ui.MEDIUM);
            row.addView(per, new LinearLayout.LayoutParams(dp(58), WRAP));
            LinearLayout body = new LinearLayout(this);
            body.setOrientation(LinearLayout.VERTICAL);
            body.addView(Ui.text(this, e.name, 15, Ui.INK, Ui.MEDIUM));
            String sub = e.room;
            if (e.teacher.length() > 0) {
                sub = sub.length() > 0 ? sub + " ・ " + e.teacher : e.teacher;
            }
            if (sub.length() > 0) {
                TextView s = Ui.text(this, sub, 12, Ui.INK2, Ui.REGULAR);
                s.setPadding(0, dp(4), 0, 0);
                body.addView(s);
            }
            row.addView(body, new LinearLayout.LayoutParams(0, WRAP, 1f));
            TextView time = Ui.text(this, same ? "" : Prefs.hm(TT.startOf(e.s)), 12, Ui.INK2,
                    Ui.REGULAR);
            row.addView(time);
            todayBox.addView(row, new LinearLayout.LayoutParams(MATCH, WRAP));
        }
    }

    private void refreshQr() {
        Bitmap b = QrService.loadQr(this, 600);
        if (b != null) {
            qrPreview.setImageBitmap(b);
            qrHint.setText("この画像をロック画面に表示します");
        } else {
            qrPreview.setImageDrawable(null);
            qrHint.setText("まだ画像が選ばれていません。スクリーンショットなどから選んでください");
        }
        recropBtn.setVisibility(Prefs.qrSourceFile(this).exists() ? View.VISIBLE : View.GONE);
    }

    private void refreshValues() {
        modeValue.setText(Prefs.mode(this) == Prefs.MODE_FLOAT ? "浮かべるカード" : "通知カード");
        classValue.setText(Prefs.cls(this));
        startValue.setText(Prefs.hm(Prefs.startMin(this)));
        endValue.setText(Prefs.hm(Prefs.endMin(this)));
    }

    // ------------------------------------------------------------ pickers

    private void pickMode() {
        final String[] items = {"浮かべるカード（ロック画面の上に表示）", "通知カード（通知として出す）"};
        new AlertDialog.Builder(this)
                .setTitle("ロック画面での出し方")
                .setSingleChoiceItems(items, Prefs.mode(this) == Prefs.MODE_FLOAT ? 0 : 1,
                        new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface d, int which) {
                                Prefs.sp(MainActivity.this).edit().putInt(Prefs.K_MODE,
                                        which == 0 ? Prefs.MODE_FLOAT : Prefs.MODE_CARD).commit();
                                d.dismiss();
                                refreshValues();
                                changed();
                                refreshPerms();
                            }
                        })
                .setNegativeButton("キャンセル", null)
                .show();
    }

    private void pickClass() {
        final List<String> classes = TT.classes(this);
        final String[] items = classes.toArray(new String[0]);
        new AlertDialog.Builder(this)
                .setTitle("クラスを選ぶ")
                .setSingleChoiceItems(items, classes.indexOf(Prefs.cls(this)),
                        new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface d, int which) {
                                Prefs.sp(MainActivity.this).edit()
                                        .putString(Prefs.K_CLASS, items[which]).commit();
                                d.dismiss();
                                refreshValues();
                                refreshToday();
                                Scheduler.sync(MainActivity.this);
                            }
                        })
                .setNegativeButton("キャンセル", null)
                .show();
    }

    private void pickTime(final boolean start) {
        int cur = start ? Prefs.startMin(this) : Prefs.endMin(this);
        new TimePickerDialog(this, new TimePickerDialog.OnTimeSetListener() {
            @Override
            public void onTimeSet(TimePicker view, int h, int m) {
                int v = h * 60 + m;
                int s = start ? v : Prefs.startMin(MainActivity.this);
                int e = start ? Prefs.endMin(MainActivity.this) : v;
                if (s >= e) {
                    toast("終了時刻は開始時刻より後にしてください");
                    return;
                }
                Prefs.sp(MainActivity.this).edit()
                        .putInt(Prefs.K_START, s).putInt(Prefs.K_END, e).commit();
                refreshValues();
                changed();
            }
        }, cur / 60, cur % 60, true).show();
    }

    private void pickImage() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("image/*");
        try {
            startActivityForResult(i, REQ_PICK);
        } catch (Exception ex) {
            try {
                Intent g = new Intent(Intent.ACTION_GET_CONTENT);
                g.setType("image/*");
                startActivityForResult(g, REQ_PICK);
            } catch (Exception ex2) {
                toast("画像を選ぶアプリが見つかりません");
            }
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_PICK || resultCode != RESULT_OK || data == null
                || data.getData() == null) {
            return;
        }
        Uri uri = data.getData();
        try {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            InputStream in = getContentResolver().openInputStream(uri);
            BitmapFactory.decodeStream(in, null, o);
            if (in != null) in.close();
            int sample = 1;
            while (Math.max(o.outWidth, o.outHeight) / sample > 3000) sample *= 2;
            BitmapFactory.Options o2 = new BitmapFactory.Options();
            o2.inSampleSize = sample;
            in = getContentResolver().openInputStream(uri);
            Bitmap bmp = BitmapFactory.decodeStream(in, null, o2);
            if (in != null) in.close();
            if (bmp == null) {
                toast("画像を読み込めませんでした");
                return;
            }
            // 元画像と、ひとまずそのままの表示用画像を保存してから切り抜き画面へ
            FileOutputStream src = new FileOutputStream(Prefs.qrSourceFile(this));
            bmp.compress(Bitmap.CompressFormat.PNG, 100, src);
            src.close();
            FileOutputStream out = new FileOutputStream(Prefs.qrFile(this));
            bmp.compress(Bitmap.CompressFormat.PNG, 100, out);
            out.close();
            startActivity(new Intent(this, CropActivity.class));
        } catch (Exception ex) {
            toast("画像を読み込めませんでした：" + ex.getMessage());
        }
    }

    // ------------------------------------------------------------ 許可

    private int permTodo;

    private void permRow(String title, String desc, Boolean ok, String btn,
                         View.OnClickListener l) {
        if (ok == null || !ok) permTodo += ok == null ? 0 : 1;
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(0, dp(12), 0, 0);

        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        int color = ok == null ? Ui.WARN : (ok ? Ui.OK : Ui.NG);
        TextView mark = Ui.text(this, ok == null ? "要確認" : (ok ? "OK" : "未許可"), 11, color,
                Ui.BOLD);
        mark.setBackground(Ui.round(this, Ui.alpha(color, 0x1F), 100));
        mark.setPadding(dp(9), dp(4), dp(9), dp(4));
        head.addView(mark);
        TextView t = Ui.text(this, title, 14, Ui.INK, Ui.MEDIUM);
        t.setPadding(dp(10), 0, 0, 0);
        t.setLineSpacing(dp(2), 1f);
        head.addView(t, new LinearLayout.LayoutParams(0, WRAP, 1f));
        box.addView(head, new LinearLayout.LayoutParams(MATCH, WRAP));

        if (ok == null || !ok) {
            TextView d = Ui.text(this, desc, 12, Ui.INK2, Ui.REGULAR);
            d.setLineSpacing(dp(3), 1f);
            d.setPadding(0, dp(8), 0, dp(8));
            box.addView(d);
            TextView b = Ui.pill(this, btn, ok == null ? Ui.alpha(accent, 0x1F) : accent,
                    ok == null ? Ui.mix(accent, Ui.INK, 0.25f) : onAccent);
            b.setOnClickListener(l);
            box.addView(b, new LinearLayout.LayoutParams(MATCH, WRAP));
        }
        permBox.addView(box, new LinearLayout.LayoutParams(MATCH, WRAP));
    }

    private void open(Intent... candidates) {
        for (Intent i : candidates) {
            try {
                startActivity(i);
                return;
            } catch (Exception ignored) {
            }
        }
        toast("設定画面を開けませんでした。設定アプリ → アプリ → 出席QR から開いてください");
    }

    private Intent appDetails() {
        return new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:" + getPackageName()));
    }

    private Intent miuiPermEditor() {
        Intent i = new Intent("miui.intent.action.APP_PERM_EDITOR");
        i.setClassName("com.miui.securitycenter",
                "com.miui.permcenter.permissions.PermissionsEditorActivity");
        i.putExtra("extra_pkgname", getPackageName());
        return i;
    }

    /** MIUI/HyperOS独自権限の状態。判定できなければ null。 */
    private Boolean miuiOp(int op) {
        try {
            AppOpsManager ops = (AppOpsManager) getSystemService(Context.APP_OPS_SERVICE);
            Method m = AppOpsManager.class.getMethod("checkOpNoThrow",
                    int.class, int.class, String.class);
            Object r = m.invoke(ops, op, Process.myUid(), getPackageName());
            return ((Integer) r) == AppOpsManager.MODE_ALLOWED;
        } catch (Throwable t) {
            return null;
        }
    }

    private boolean isXiaomi() {
        String m = (Build.MANUFACTURER + " " + Build.BRAND).toLowerCase();
        return m.contains("xiaomi") || m.contains("redmi") || m.contains("poco");
    }

    private void refreshPerms() {
        permBox.removeAllViews();
        permTodo = 0;
        final String pkgUri = "package:" + getPackageName();

        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        boolean notif = nm != null && nm.areNotificationsEnabled();
        permRow("通知", "ロック画面に出すQRカードは通知として表示します。許可後、通知設定で「ロック画面通知」もオンにしてください。",
                notif, "通知を許可する", new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(
                                Manifest.permission.POST_NOTIFICATIONS)
                                != PackageManager.PERMISSION_GRANTED
                                && shouldAskNotif()) {
                            requestPermissions(
                                    new String[]{Manifest.permission.POST_NOTIFICATIONS},
                                    REQ_NOTIF);
                        } else {
                            open(new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                                            .putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName()),
                                    appDetails());
                        }
                    }
                });

        if (Prefs.mode(this) == Prefs.MODE_FLOAT) {
            permRow("ユーザー補助：出席QR",
                    "小さなカードをロック画面に浮かべるために必要です。①まず下のボタンでアプリ情報を開き、右上の「︙」→「制限付き設定を許可」を押す（出ない場合は不要）。②次に 設定 → 追加設定 → ユーザー補助 → ダウンロードしたアプリ →「出席QR：ロック画面の小さなカード」をオンにする。",
                    CardService.connected(), "①アプリ情報を開く", new View.OnClickListener() {
                        @Override
                        public void onClick(View v) {
                            open(appDetails());
                        }
                    });
            if (!CardService.connected()) {
                TextView b2 = Ui.pill(this, "②ユーザー補助の設定を開く", accent, onAccent);
                b2.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        open(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS), appDetails());
                    }
                });
                LinearLayout.LayoutParams l2 = new LinearLayout.LayoutParams(MATCH, WRAP);
                l2.topMargin = dp(8);
                permBox.addView(b2, l2);
            }
        }

        if (isXiaomi()) {
            View.OnClickListener openMiui = new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    open(miuiPermEditor(), appDetails());
                }
            };
            permRow("Xiaomi：ロック画面に表示",
                    "「その他の権限」→「ロック画面に表示」を「常に許可」にしてください。通知カードをタップしたとき、ロックを解除せずにQRを大きく表示するために使います。",
                    miuiOp(MIUI_OP_SHOW_ON_LOCK), "その他の権限を開く", openMiui);
            permRow("Xiaomi：自動起動",
                    "再起動後や朝の時刻にアプリが自動で動けるよう「自動起動」をオンにしてください（状態はアプリから確認できないため、設定済みならそのままで構いません）。",
                    null, "自動起動の設定を開く", new View.OnClickListener() {
                        @Override
                        public void onClick(View v) {
                            Intent a = new Intent();
                            a.setComponent(new ComponentName("com.miui.securitycenter",
                                    "com.miui.permcenter.autostart.AutoStartManagementActivity"));
                            open(a, appDetails());
                        }
                    });
        }

        PowerManager pw = (PowerManager) getSystemService(POWER_SERVICE);
        boolean batt = pw != null && pw.isIgnoringBatteryOptimizations(getPackageName());
        permRow("バッテリーの制限なし",
                "省電力機能にアプリを止められないようにします。アプリ情報の「バッテリーセーバー」を「制限なし」にしてください。",
                batt, "アプリ情報を開く", new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        open(appDetails(),
                                new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
                    }
                });

        AlarmManager am = (AlarmManager) getSystemService(ALARM_SERVICE);
        boolean exact = am != null && am.canScheduleExactAlarms();
        permRow("アラームとリマインダー", "設定した時刻ちょうどに表示を始めるために使います。",
                exact, "許可画面を開く", new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        open(new Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                                Uri.parse(pkgUri)), appDetails());
                    }
                });

        if (permTodo == 0) {
            permSummary.setText("必要な許可はそろっています");
            permSummary.setTextColor(Ui.OK);
        } else {
            permSummary.setText("あと " + permTodo + " 件の許可が必要です");
            permSummary.setTextColor(Ui.NG);
        }
    }

    private boolean shouldAskNotif() {
        // 一度「許可しない」を選ぶとダイアログが出なくなるため、2回目以降は設定画面へ案内する
        boolean asked = Prefs.sp(this).getBoolean("askedNotif", false);
        Prefs.sp(this).edit().putBoolean("askedNotif", true).apply();
        return !asked || shouldShowRequestPermissionRationale(
                Manifest.permission.POST_NOTIFICATIONS);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions,
                                           int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        refreshPerms();
        Scheduler.sync(this);
    }
}
