package jp.yagi.attendqr;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** アラーム・再起動・時刻変更を受けて状態を合わせ直す。 */
public class EventReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        String a = intent == null ? null : intent.getAction();
        if (Scheduler.ACTION_START.equals(a) || Scheduler.ACTION_START_BACKUP.equals(a)) {
            Prefs.sp(context).edit()
                    .putLong(Prefs.K_LAST_ALARM, System.currentTimeMillis()).apply();
        }
        if (Scheduler.ACTION_DONE.equals(a)) {
            // 通知の「出席した」ボタン：今日の表示を終える
            Prefs.sp(context).edit()
                    .putInt(Prefs.K_DONE, Prefs.dateKey(java.util.Calendar.getInstance()))
                    .putLong(Prefs.K_TEST_UNTIL, 0L)
                    .commit();
        }
        Scheduler.sync(context);
    }
}
