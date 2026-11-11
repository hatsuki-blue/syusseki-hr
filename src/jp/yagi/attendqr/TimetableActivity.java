package jp.yagi.attendqr;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import java.util.List;

/** 選択中クラスの時間割を確認・編集する画面。 */
public class TimetableActivity extends Activity {
    private String cls;
    private List<TT.Entry> list;
    private LinearLayout root;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        cls = Prefs.cls(this);
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(Ui.BG);
        scroll.setFitsSystemWindows(true);
        scroll.setVerticalScrollBarEnabled(false);
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(18), dp(16), dp(32));
        scroll.addView(root);
        setContentView(scroll);
        reload();
    }

    private int dp(float v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                getResources().getDisplayMetrics()));
    }

    private TextView text(String s, float sp, int color, boolean bold) {
        TextView t = Ui.text(this, s, sp, color, bold ? Ui.MEDIUM : Ui.REGULAR);
        t.setLineSpacing(dp(2), 1f);
        return t;
    }

    private void reload() {
        list = TT.load(this, cls);
        root.removeAllViews();

        final int accent = Ui.accent(this);
        TextView title = Ui.text(this, "時間割　" + cls, 26, Ui.INK, Ui.BOLD);
        title.setPadding(dp(6), dp(6), 0, dp(8));
        root.addView(title);
        TextView info = text((TT.isEdited(this, cls)
                ? "編集済みの時間割です。"
                : "同梱データ（" + TT.version(this) + "）です。")
                + "行をタップすると編集・削除できます。選択科目は候補がすべて入っているので、受講しないものは削除してください。",
                12, Ui.INK2, false);
        info.setPadding(dp(6), 0, dp(6), dp(14));
        root.addView(info);

        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        TextView add = Ui.pill(this, "授業を追加", accent, Ui.onColor(accent));
        add.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                edit(null);
            }
        });
        top.addView(add, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView reset = Ui.pill(this, "同梱データに戻す", Ui.alpha(accent, 0x1F),
                Ui.mix(accent, Ui.INK, 0.25f));
        reset.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                new AlertDialog.Builder(TimetableActivity.this)
                        .setMessage(cls + " の時間割の編集内容を消して、同梱データに戻しますか？")
                        .setPositiveButton("戻す", new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface d, int w) {
                                TT.reset(TimetableActivity.this, cls);
                                reload();
                            }
                        })
                        .setNegativeButton("キャンセル", null)
                        .show();
            }
        });
        LinearLayout.LayoutParams rl = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        rl.leftMargin = dp(8);
        top.addView(reset, rl);
        root.addView(top);

        for (int day = 0; day < 7; day++) {
            boolean any = false;
            for (TT.Entry e : list) if (e.day == day) any = true;
            if (!any && day >= 5) continue;

            TextView h = text(TT.DAY_NAMES[day] + "曜日", 13, Ui.INK2, true);
            h.setPadding(dp(6), dp(20), 0, dp(8));
            root.addView(h);
            if (!any) {
                TextView none = text("（授業なし）", 13, Ui.INK2, false);
                none.setPadding(dp(6), 0, 0, 0);
                root.addView(none);
                continue;
            }
            for (final TT.Entry e : list) {
                if (e.day != day) continue;
                LinearLayout row = new LinearLayout(this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setGravity(Gravity.CENTER_VERTICAL);
                GradientDrawable g = new GradientDrawable();
                g.setColor(Color.WHITE);
                g.setCornerRadius(dp(16));
                row.setBackground(Ui.ripple(this, g, 16, 0x22000000));
                row.setPadding(dp(16), dp(12), dp(16), dp(12));

                LinearLayout left = new LinearLayout(this);
                left.setOrientation(LinearLayout.VERTICAL);
                left.addView(text(TT.periodLabel(e) + "限", 14, Ui.mix(accent, Ui.INK, 0.25f), true));
                left.addView(text(TT.timeLabel(e), 11, Ui.INK2, false));
                row.addView(left, new LinearLayout.LayoutParams(dp(96),
                        ViewGroup.LayoutParams.WRAP_CONTENT));

                LinearLayout right = new LinearLayout(this);
                right.setOrientation(LinearLayout.VERTICAL);
                right.addView(text(e.name, 15, Ui.INK, true));
                String sub = e.room;
                if (e.teacher.length() > 0) {
                    sub = sub.length() > 0 ? sub + "　" + e.teacher : e.teacher;
                }
                if (sub.length() > 0) right.addView(text(sub, 12, Ui.INK2, false));
                row.addView(right, new LinearLayout.LayoutParams(0,
                        ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

                row.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        edit(e);
                    }
                });
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                lp.bottomMargin = dp(6);
                root.addView(row, lp);
            }
        }
    }

    private EditText field(LinearLayout box, String label, String value, boolean number) {
        TextView l = text(label, 12, Ui.INK2, false);
        l.setPadding(0, dp(8), 0, 0);
        box.addView(l);
        EditText e = new EditText(this);
        e.setText(value);
        e.setSingleLine(true);
        e.setInputType(number ? InputType.TYPE_CLASS_NUMBER : InputType.TYPE_CLASS_TEXT);
        box.addView(e);
        return e;
    }

    private void edit(final TT.Entry target) {
        final boolean isNew = target == null;
        final TT.Entry e = isNew ? new TT.Entry() : target;
        if (isNew) {
            e.day = 0;
            e.s = 1;
            e.e = 2;
        }

        ScrollView sv = new ScrollView(this);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20), dp(8), dp(20), dp(8));
        sv.addView(box);

        box.addView(text("曜日", 12, Ui.INK2, false));
        final Spinner day = new Spinner(this);
        ArrayAdapter<String> ad = new ArrayAdapter<String>(this,
                android.R.layout.simple_spinner_item, TT.DAY_NAMES);
        ad.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        day.setAdapter(ad);
        day.setSelection(Math.max(0, Math.min(6, e.day)));
        box.addView(day);

        final EditText s = field(box, "開始時限（1〜10）", String.valueOf(e.s), true);
        final EditText en = field(box, "終了時限（1〜10）", String.valueOf(e.e), true);
        final EditText name = field(box, "科目名", e.name, false);
        final EditText room = field(box, "教室", e.room, false);
        final EditText teacher = field(box, "担当教員", e.teacher, false);

        AlertDialog.Builder b = new AlertDialog.Builder(this)
                .setTitle(isNew ? "授業を追加" : "授業を編集")
                .setView(sv)
                .setPositiveButton("保存", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        int ps = parse(s.getText().toString(), 1);
                        int pe = parse(en.getText().toString(), ps);
                        if (ps < 1 || ps > 10 || pe < ps || pe > 10) {
                            Toast.makeText(TimetableActivity.this,
                                    "時限は1〜10で、開始≦終了になるよう入力してください",
                                    Toast.LENGTH_LONG).show();
                            return;
                        }
                        String n = name.getText().toString().trim();
                        if (n.length() == 0) {
                            Toast.makeText(TimetableActivity.this, "科目名を入力してください",
                                    Toast.LENGTH_LONG).show();
                            return;
                        }
                        e.day = day.getSelectedItemPosition();
                        e.s = ps;
                        e.e = pe;
                        e.name = n;
                        e.room = room.getText().toString().trim();
                        e.teacher = teacher.getText().toString().trim();
                        if (isNew) list.add(e);
                        TT.save(TimetableActivity.this, cls, list);
                        reload();
                    }
                })
                .setNegativeButton("キャンセル", null);
        if (!isNew) {
            b.setNeutralButton("削除", new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface d, int w) {
                    list.remove(target);
                    TT.save(TimetableActivity.this, cls, list);
                    reload();
                }
            });
        }
        b.show();
    }

    private static int parse(String s, int def) {
        try {
            return Integer.parseInt(s.trim());
        } catch (Exception ex) {
            return def;
        }
    }
}
