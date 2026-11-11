package jp.yagi.attendqr;

import android.content.Context;
import android.content.SharedPreferences;

import java.io.File;
import java.util.Calendar;

/** 設定値の読み書き。 */
final class Prefs {
    private Prefs() {}

    static final String FILE = "settings";
    static final String K_START = "startMin";      // 表示開始（0時からの分）
    static final String K_END = "endMin";          // 表示終了（0時からの分）
    static final String K_DAYS = "days";           // 曜日ビット bit0=月 ... bit6=日
    static final String K_CLASS = "cls";
    static final String K_DONE = "doneDate";       // 「今日は終了」を押した日 (yyyymmdd)
    static final String K_BRIGHT = "bright";       // 表示中は最大輝度
    static final String K_WAKE = "wake";           // 開始時刻に画面を点灯
    static final String K_ALARMCLOCK = "alarmClock"; // 確実モード（アラーム時計方式）
    static final String K_ENABLED = "enabled";
    static final String K_TEST_UNTIL = "testUntil"; // テスト表示の終了時刻(ms)
    static final String K_MODE = "mode";           // 0=通知カード 1=全画面
    static final int MODE_CARD = 0, MODE_FULL = 1, MODE_FLOAT = 2;
    static final String K_CARD_SMALL = "cardSmall"; // 小さいチップで出すか
    static final String K_CARD_Y = "cardY";         // 小さなカードの位置（下端からの距離px）
    static final String K_ACCENT = "accent";       // アクセントカラー
    static final String K_SKIP = "skipDate";       // スキップする日 (yyyymmdd)
    static final String K_LAST_ALARM = "lastAlarm"; // 最後に開始処理が動いた時刻(ms)
    static final String K_LAST_SHOWN = "lastShown"; // 最後にロック画面へ出た時刻(ms)

    static SharedPreferences sp(Context c) {
        return c.getApplicationContext().getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    static int startMin(Context c) { return sp(c).getInt(K_START, 8 * 60); }
    static int endMin(Context c) { return sp(c).getInt(K_END, 9 * 60); }
    static int days(Context c) { return sp(c).getInt(K_DAYS, 0x1F); }
    static String cls(Context c) { return sp(c).getString(K_CLASS, "3J"); }
    /** 選ばれている方式（旧バージョンの「全画面」は通知カードとして扱う）。 */
    static int mode(Context c) {
        return sp(c).getInt(K_MODE, MODE_FLOAT) == MODE_CARD ? MODE_CARD : MODE_FLOAT;
    }
    static boolean bright(Context c) { return sp(c).getBoolean(K_BRIGHT, true); }
    static boolean wake(Context c) { return sp(c).getBoolean(K_WAKE, false); }
    static boolean alarmClock(Context c) { return sp(c).getBoolean(K_ALARMCLOCK, true); }
    static boolean enabled(Context c) { return sp(c).getBoolean(K_ENABLED, true); }
    static long testUntil(Context c) { return sp(c).getLong(K_TEST_UNTIL, 0L); }

    /** 切り抜き前の元画像。 */
    static File qrSourceFile(Context c) {
        return new File(c.getApplicationContext().getFilesDir(), "qr_src.png");
    }

    static String stamp(long ms) {
        if (ms <= 0) return "まだありません";
        Calendar cal = Calendar.getInstance();
        cal.setTimeInMillis(ms);
        return (cal.get(Calendar.MONTH) + 1) + "/" + cal.get(Calendar.DAY_OF_MONTH) + " "
                + hm(cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE));
    }

    static File qrFile(Context c) {
        return new File(c.getApplicationContext().getFilesDir(), "qr.png");
    }

    static int dateKey(Calendar cal) {
        return cal.get(Calendar.YEAR) * 10000 + (cal.get(Calendar.MONTH) + 1) * 100
                + cal.get(Calendar.DAY_OF_MONTH);
    }

    /** 月=0 ... 日=6 */
    static int dayIndex(Calendar cal) {
        return (cal.get(Calendar.DAY_OF_WEEK) + 5) % 7;
    }

    static String hm(int min) {
        return (min / 60) + ":" + (min % 60 < 10 ? "0" : "") + (min % 60);
    }
}
