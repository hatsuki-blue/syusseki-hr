package jp.yagi.attendqr;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

import java.util.Calendar;

/** 表示時間帯の判定とアラームの予約。 */
final class Scheduler {
    private Scheduler() {}

    static final String ACTION_START = "jp.yagi.attendqr.START";
    static final String ACTION_START_BACKUP = "jp.yagi.attendqr.START_BACKUP";
    static final String ACTION_END = "jp.yagi.attendqr.END";
    static final String ACTION_DONE = "jp.yagi.attendqr.DONE";

    /** 設定された曜日・時間帯の中にいるか（テスト表示は含まない）。 */
    static boolean inWindow(Context c) {
        if (!Prefs.enabled(c)) return false;
        Calendar cal = Calendar.getInstance();
        if ((Prefs.days(c) & (1 << Prefs.dayIndex(cal))) == 0) return false;
        int key = Prefs.dateKey(cal);
        if (Prefs.sp(c).getInt(Prefs.K_DONE, 0) == key) return false;
        if (Prefs.sp(c).getInt(Prefs.K_SKIP, 0) == key) return false;
        int m = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE);
        int s = Prefs.startMin(c), e = Prefs.endMin(c);
        return s < e && m >= s && m < e;
    }

    /** いまQRを表示すべき時間帯か。 */
    static boolean isActiveNow(Context c) {
        return Prefs.testUntil(c) > System.currentTimeMillis() || inWindow(c);
    }

    /** 現在の表示が終わる時刻(ms)。表示中でなければ 0。 */
    static long activeEnd(Context c) {
        if (!isActiveNow(c)) return 0;
        long now = System.currentTimeMillis();
        long test = Prefs.testUntil(c);
        long end = 0;
        Calendar cal = Calendar.getInstance();
        int e = Prefs.endMin(c);
        boolean inWindow = inWindow(c);
        if (inWindow) {
            cal.set(Calendar.HOUR_OF_DAY, e / 60);
            cal.set(Calendar.MINUTE, e % 60);
            cal.set(Calendar.SECOND, 0);
            cal.set(Calendar.MILLISECOND, 0);
            end = cal.getTimeInMillis();
        }
        if (test > now && test > end) end = test;
        return end;
    }

    /** 次に表示が始まる時刻(ms)。予定がなければ 0。 */
    static long nextStart(Context c) {
        if (!Prefs.enabled(c)) return 0;
        int days = Prefs.days(c);
        int s = Prefs.startMin(c), e = Prefs.endMin(c);
        if ((days & 0x7F) == 0 || s >= e) return 0;
        long now = System.currentTimeMillis();
        Calendar cal = Calendar.getInstance();
        cal.set(Calendar.HOUR_OF_DAY, s / 60);
        cal.set(Calendar.MINUTE, s % 60);
        cal.set(Calendar.SECOND, 0);
        cal.set(Calendar.MILLISECOND, 0);
        for (int i = 0; i < 8; i++) {
            if (cal.getTimeInMillis() > now && (days & (1 << Prefs.dayIndex(cal))) != 0
                    && Prefs.sp(c).getInt(Prefs.K_SKIP, 0) != Prefs.dateKey(cal)) {
                return cal.getTimeInMillis();
            }
            cal.add(Calendar.DAY_OF_MONTH, 1);
        }
        return 0;
    }

    private static PendingIntent pi(Context c, String action, int code) {
        Intent i = new Intent(c, EventReceiver.class).setAction(action);
        return PendingIntent.getBroadcast(c, code, i,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    /** 状態を現在時刻に合わせる：サービスの開始/停止と、次回アラームの予約。 */
    static void sync(Context ctx) {
        Context c = ctx.getApplicationContext();
        boolean active = isActiveNow(c);
        Intent svc = new Intent(c, QrService.class);
        // 「小さなカード」方式が動いているときは、通知カード用のサービスは使わない
        boolean useNotification = active && !CardService.active(c);
        CardService.poke();
        if (useNotification) {
            try {
                c.startForegroundService(svc);
            } catch (Exception ex) {
                // バックグラウンド制限などで開始できなかった場合は次のアラームに任せる
            }
        } else {
            try {
                c.stopService(svc);
            } catch (Exception ignored) {
            }
            c.sendBroadcast(new Intent(LockActivity.ACTION_CLOSE).setPackage(c.getPackageName()));
        }

        AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;
        PendingIntent pStart = pi(c, ACTION_START, 1);
        PendingIntent pBackup = pi(c, ACTION_START_BACKUP, 2);
        PendingIntent pEnd = pi(c, ACTION_END, 3);
        am.cancel(pStart);
        am.cancel(pBackup);
        am.cancel(pEnd);

        boolean exact = Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms();

        long next = nextStart(c);
        if (next > 0) {
            if (exact && Prefs.alarmClock(c)) {
                PendingIntent show = PendingIntent.getActivity(c, 10,
                        new Intent(c, MainActivity.class),
                        PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
                am.setAlarmClock(new AlarmManager.AlarmClockInfo(next, show), pStart);
            } else if (exact) {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next, pStart);
            } else {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next, pStart);
            }
            // 取りこぼし対策の予備アラーム（1分後）
            if (exact) {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next + 60000L, pBackup);
            } else {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next + 60000L, pBackup);
            }
        }

        long end = activeEnd(c);
        if (end > 0) {
            if (exact) {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, end + 500L, pEnd);
            } else {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, end + 500L, pEnd);
            }
        }
    }
}
