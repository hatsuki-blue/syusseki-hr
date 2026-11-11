package jp.yagi.attendqr;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.graphics.drawable.RippleDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.widget.TextView;

/** 画面共通の見た目の部品。 */
final class Ui {
    private Ui() {}

    static final int REGULAR = 0, MEDIUM = 1, BOLD = 2, LIGHT = 3;

    /** 選べるアクセントカラー。 */
    static final int[] ACCENTS = {0xFF3B7BF5, 0xFF0D9488, 0xFF7C6CF0, 0xFFE84C7B, 0xFFE8750A};
    static final String[] ACCENT_NAMES = {"ブルー", "ティール", "バイオレット", "ローズ", "オレンジ"};

    // ライト（設定画面）
    static final int BG = 0xFFF3F5F9;
    static final int CARD = 0xFFFFFFFF;
    static final int INK = 0xFF121823;
    static final int INK2 = 0xFF5D6877;
    static final int LINE = 0xFFE6EAF0;
    static final int OK = 0xFF16A34A;
    static final int NG = 0xFFDC2626;
    static final int WARN = 0xFFB45309;

    // ダーク（ロック画面）
    static final int D_TEXT = 0xFFF2F5FA;
    static final int D_TEXT2 = 0xFF9AA7B8;
    static final int D_TEXT3 = 0xFF5C6878;
    static final int D_GLASS = 0x14FFFFFF;
    static final int D_STROKE = 0x24FFFFFF;

    static int accent(Context c) {
        int saved = Prefs.sp(c).getInt(Prefs.K_ACCENT, ACCENTS[0]);
        for (int a : ACCENTS) if (a == saved) return saved;
        return ACCENTS[0];
    }

    static int dp(Context c, float v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                c.getResources().getDisplayMetrics()));
    }

    static int alpha(int color, int a) {
        return (color & 0x00FFFFFF) | (a << 24);
    }

    /** 2色を混ぜる（t=0でa、t=1でb）。 */
    static int mix(int a, int b, float t) {
        int r = Math.round(Color.red(a) + (Color.red(b) - Color.red(a)) * t);
        int g = Math.round(Color.green(a) + (Color.green(b) - Color.green(a)) * t);
        int bl = Math.round(Color.blue(a) + (Color.blue(b) - Color.blue(a)) * t);
        return Color.rgb(r, g, bl);
    }

    /** アクセント色の上に置く文字色（明るい色には濃い文字）。 */
    static int onColor(int color) {
        double l = (0.299 * Color.red(color) + 0.587 * Color.green(color)
                + 0.114 * Color.blue(color)) / 255.0;
        return l > 0.66 ? 0xFF161B22 : Color.WHITE;
    }

    static Typeface face(int style) {
        switch (style) {
            case MEDIUM:
                return Typeface.create("sans-serif-medium", Typeface.NORMAL);
            case BOLD:
                return Typeface.create("sans-serif", Typeface.BOLD);
            case LIGHT:
                return Typeface.create("sans-serif-light", Typeface.NORMAL);
            default:
                return Typeface.create("sans-serif", Typeface.NORMAL);
        }
    }

    static TextView text(Context c, CharSequence s, float sp, int color, int style) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        t.setTypeface(face(style));
        t.setIncludeFontPadding(false);
        return t;
    }

    static GradientDrawable round(Context c, int color, float radiusDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(c, radiusDp));
        return g;
    }

    static GradientDrawable round(Context c, int color, float radiusDp, int stroke, float strokeDp) {
        GradientDrawable g = round(c, color, radiusDp);
        g.setStroke(Math.max(1, dp(c, strokeDp)), stroke);
        return g;
    }

    static GradientDrawable gradient(Context c, int from, int to, float radiusDp,
                                     GradientDrawable.Orientation o) {
        GradientDrawable g = new GradientDrawable(o, new int[]{from, to});
        g.setCornerRadius(dp(c, radiusDp));
        return g;
    }

    /** 押したときに波紋が出る背景にする。 */
    static Drawable ripple(Context c, Drawable content, float radiusDp, int rippleColor) {
        return new RippleDrawable(ColorStateList.valueOf(rippleColor), content,
                round(c, Color.WHITE, radiusDp));
    }

    /** 丸いボタン。 */
    static TextView pill(Context c, String label, int bg, int fg) {
        TextView t = text(c, label, 14, fg, MEDIUM);
        t.setBackground(ripple(c, round(c, bg, 100), 100, alpha(fg, 0x33)));
        t.setPadding(dp(c, 18), dp(c, 12), dp(c, 18), dp(c, 12));
        t.setGravity(Gravity.CENTER);
        t.setClickable(true);
        return t;
    }

    /** 枠だけのボタン。 */
    static TextView ghost(Context c, String label, int stroke, int fg) {
        TextView t = text(c, label, 14, fg, MEDIUM);
        t.setBackground(ripple(c, round(c, Color.TRANSPARENT, 100, stroke, 1), 100, alpha(fg, 0x33)));
        t.setPadding(dp(c, 18), dp(c, 12), dp(c, 18), dp(c, 12));
        t.setGravity(Gravity.CENTER);
        t.setClickable(true);
        return t;
    }

    /** ロック画面の背景：深い紺のグラデーションに、上からアクセント色の光。 */
    static Drawable lockBackground(Context c, int accent) {
        GradientDrawable base = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{mix(0xFF0A0F18, accent, 0.10f), 0xFF07090E, 0xFF030406});
        GradientDrawable glow = new GradientDrawable();
        glow.setShape(GradientDrawable.RECTANGLE);
        glow.setGradientType(GradientDrawable.RADIAL_GRADIENT);
        glow.setGradientCenter(0.85f, 0.0f);
        glow.setGradientRadius(dp(c, 360));
        glow.setColors(new int[]{alpha(accent, 0x55), alpha(accent, 0x00)});
        GradientDrawable glow2 = new GradientDrawable();
        glow2.setShape(GradientDrawable.RECTANGLE);
        glow2.setGradientType(GradientDrawable.RADIAL_GRADIENT);
        glow2.setGradientCenter(0.0f, 0.42f);
        glow2.setGradientRadius(dp(c, 260));
        glow2.setColors(new int[]{alpha(mix(accent, 0xFF9B5CFF, 0.5f), 0x26), 0x00000000});
        return new LayerDrawable(new Drawable[]{base, glow, glow2});
    }

    static String remain(int minutes) {
        if (minutes < 60) return minutes + "分";
        return (minutes / 60) + "時間" + (minutes % 60 == 0 ? "" : (minutes % 60) + "分");
    }
}
