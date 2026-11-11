package jp.yagi.attendqr;

import android.app.KeyguardManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ServiceInfo;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;

import java.io.File;

/**
 * 表示時間帯のあいだだけ動く常駐サービス。
 * 画面の消灯/点灯を監視し、ロック画面の上に LockActivity を出す。
 */
public class QrService extends Service {
    static final String CH_ONGOING = "ongoing";
    static final String CH_LAUNCH = "launch";
    static final int ID_ONGOING = 1;
    static final int ID_LAUNCH = 2;
    static final String CH_CARD = "card";
    static final int ID_CARD = 3;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean receiverRegistered;
    private boolean firstStartDone;

    private final Runnable endCheck = new Runnable() {
        @Override
        public void run() {
            if (!Scheduler.isActiveNow(QrService.this)) {
                Scheduler.sync(QrService.this);
                stopSelf();
            } else {
                scheduleEndCheck();
            }
        }
    };

    private final Runnable fallbackLaunch = new Runnable() {
        @Override
        public void run() {
            // 直接起動がOSに止められた場合の保険：全画面通知で起動する
            if (!LockActivity.visible && isLocked() && Scheduler.isActiveNow(QrService.this)) {
                postLaunchNotification();
            }
        }
    };

    private final BroadcastReceiver screenReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String a = intent.getAction();
            if (!Scheduler.isActiveNow(QrService.this)) {
                Scheduler.sync(QrService.this);
                stopSelf();
                return;
            }
            boolean full = Prefs.mode(QrService.this) == Prefs.MODE_FULL;
            if (Intent.ACTION_SCREEN_OFF.equals(a)) {
                if (full) {
                    // 消灯した瞬間にロック画面の上へ出しておく（次に点灯したとき最初から見える）
                    handler.removeCallbacks(fallbackLaunch);
                    handler.postDelayed(new Runnable() {
                        @Override
                        public void run() {
                            launchLock(false);
                        }
                    }, 250);
                }
            } else if (Intent.ACTION_SCREEN_ON.equals(a)) {
                if (full) {
                    if (isLocked() && !LockActivity.visible) {
                        launchLock(false);
                        handler.removeCallbacks(fallbackLaunch);
                        handler.postDelayed(fallbackLaunch, 1200);
                    }
                } else if (isLocked() && !LockActivity.visible) {
                    // ロック画面に「新しい通知」として出し直す（解除後に消える機種への対策）
                    postCard(true);
                }
            } else if (Intent.ACTION_USER_PRESENT.equals(a)) {
                handler.removeCallbacks(fallbackLaunch);
                cancelLaunchNotification();
            }
        }
    };

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        createChannels(this);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Notification n = buildOngoing();
        try {
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(ID_ONGOING, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
            } else {
                startForeground(ID_ONGOING, n);
            }
        } catch (Exception ex) {
            stopSelf();
            return START_NOT_STICKY;
        }

        if (!Scheduler.isActiveNow(this)) {
            stopForeground(true);
            stopSelf();
            return START_NOT_STICKY;
        }

        if (!receiverRegistered) {
            IntentFilter f = new IntentFilter();
            f.addAction(Intent.ACTION_SCREEN_OFF);
            f.addAction(Intent.ACTION_SCREEN_ON);
            f.addAction(Intent.ACTION_USER_PRESENT);
            if (Build.VERSION.SDK_INT >= 33) {
                registerReceiver(screenReceiver, f, Context.RECEIVER_NOT_EXPORTED);
            } else {
                registerReceiver(screenReceiver, f);
            }
            receiverRegistered = true;
        }

        scheduleEndCheck();

        boolean full = Prefs.mode(this) == Prefs.MODE_FULL;
        if (full) {
            cancelCard();
        }
        if (!firstStartDone) {
            firstStartDone = true;
            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            boolean interactive = pm != null && pm.isInteractive();
            if (full) {
                if (!interactive || isLocked()) {
                    boolean wake = Prefs.wake(this) && !interactive;
                    launchLock(wake);
                    if (interactive) {
                        handler.postDelayed(fallbackLaunch, 1200);
                    }
                }
            } else {
                postCard(false);
            }
        } else if (!full) {
            // 設定やQR画像が変わったときは、音を出さずに内容だけ更新する
            postCard(false);
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        if (receiverRegistered) {
            try {
                unregisterReceiver(screenReceiver);
            } catch (Exception ignored) {
            }
            receiverRegistered = false;
        }
        cancelLaunchNotification();
        cancelCard();
        sendBroadcast(new Intent(LockActivity.ACTION_CLOSE).setPackage(getPackageName()));
        super.onDestroy();
    }

    private void scheduleEndCheck() {
        handler.removeCallbacks(endCheck);
        long end = Scheduler.activeEnd(this);
        long delay = end - System.currentTimeMillis() + 300;
        if (delay < 1000) delay = 1000;
        if (delay > 60000) delay = 60000; // 時刻変更などに備えて1分ごとに再確認
        handler.postDelayed(endCheck, delay);
    }

    private boolean isLocked() {
        KeyguardManager km = (KeyguardManager) getSystemService(KEYGUARD_SERVICE);
        return km != null && km.isKeyguardLocked();
    }

    private void launchLock(boolean wake) {
        if (!Scheduler.isActiveNow(this)) return;
        Intent i = new Intent(this, LockActivity.class);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                | Intent.FLAG_ACTIVITY_NO_ANIMATION
                | Intent.FLAG_ACTIVITY_NO_USER_ACTION);
        i.putExtra(LockActivity.EXTRA_WAKE, wake);
        try {
            startActivity(i);
        } catch (Exception ignored) {
        }
    }

    static void createChannels(Context c) {
        NotificationManager nm = (NotificationManager) c.getSystemService(NOTIFICATION_SERVICE);
        if (nm == null) return;
        NotificationChannel ongoing = new NotificationChannel(CH_ONGOING, "QR表示中",
                NotificationManager.IMPORTANCE_LOW);
        ongoing.setDescription("表示時間帯のあいだ出る常駐通知（QRコード付き）");
        ongoing.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
        ongoing.setShowBadge(false);
        nm.createNotificationChannel(ongoing);

        try {
            nm.deleteNotificationChannel(CH_LAUNCH); // 旧バージョンの全画面通知用（廃止）
        } catch (Exception ignored) {
        }

        NotificationChannel card = new NotificationChannel(CH_CARD, "出席QRカード",
                NotificationManager.IMPORTANCE_HIGH);
        card.setDescription("ロック画面に出すQRコードと時間割のカード");
        card.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
        card.setSound(null, null);
        card.enableVibration(false);
        card.setShowBadge(false);
        nm.createNotificationChannel(card);
    }

    private PendingIntent lockPendingIntent(int code) {
        Intent i = new Intent(this, LockActivity.class);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_ANIMATION);
        return PendingIntent.getActivity(this, code, i,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    static Bitmap loadQr(Context c, int maxSize) {
        File f = Prefs.qrFile(c);
        if (!f.exists()) return null;
        try {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(f.getPath(), o);
            int sample = 1;
            while (Math.max(o.outWidth, o.outHeight) / sample > maxSize * 2) sample *= 2;
            BitmapFactory.Options o2 = new BitmapFactory.Options();
            o2.inSampleSize = sample;
            return BitmapFactory.decodeFile(f.getPath(), o2);
        } catch (Throwable t) {
            return null;
        }
    }

    private Notification buildOngoing() {
        long end = Scheduler.activeEnd(this);
        String until = "";
        if (end > 0) {
            java.util.Calendar cal = java.util.Calendar.getInstance();
            cal.setTimeInMillis(end);
            until = Prefs.hm(cal.get(java.util.Calendar.HOUR_OF_DAY) * 60
                    + cal.get(java.util.Calendar.MINUTE)) + " まで";
        }
        Notification.Builder b = new Notification.Builder(this, CH_ONGOING)
                .setSmallIcon(R.drawable.ic_stat)
                .setColor(Ui.accent(this))
                .setContentTitle("出席QRを表示中")
                .setContentText(until + "ロック画面に表示します")
                .setOngoing(true)
                .setShowWhen(false)
                .setOnlyAlertOnce(true)
                .setCategory(Notification.CATEGORY_STATUS)
                // 通知カード方式のときは、ロック画面にはカードだけを出す
                .setVisibility(Prefs.mode(this) == Prefs.MODE_FULL
                        ? Notification.VISIBILITY_PUBLIC : Notification.VISIBILITY_SECRET)
                .setContentIntent(lockPendingIntent(20));
        if (Build.VERSION.SDK_INT >= 31) {
            b.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE);
        }
        return b.build();
    }

    /** ロック画面に出す通知カード（QR＋つぎの授業＋時間割）。 */
    private void postCard(boolean fresh) {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm == null || !Scheduler.isActiveNow(this)) return;
        String[] sum = TT.summary(this);
        Bitmap qr = loadQr(this, 360);

        android.widget.RemoteViews big = new android.widget.RemoteViews(getPackageName(),
                R.layout.notif_big);
        if (qr != null) big.setImageViewBitmap(R.id.qr, qr);
        big.setTextViewText(R.id.head, sum[0]);
        big.setTextViewText(R.id.sub, sum[1]);
        big.setViewVisibility(R.id.sub, sum[1].length() > 0
                ? android.view.View.VISIBLE : android.view.View.GONE);
        big.setTextViewText(R.id.list, sum[2]);

        PendingIntent done = PendingIntent.getBroadcast(this, 40,
                new Intent(this, EventReceiver.class).setAction(Scheduler.ACTION_DONE),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        PendingIntent open = lockPendingIntent(22);

        String text = sum[0] + (sum[1].length() > 0 ? "　" + sum[1] : "");
        Notification.Builder b = new Notification.Builder(this, CH_CARD)
                .setSmallIcon(R.drawable.ic_stat)
                .setColor(Ui.accent(this))
                .setContentTitle("出席QR ・ タップで大きく表示")
                .setContentText(text)
                .setSubText(Prefs.cls(this))
                .setShowWhen(false)
                .setOngoing(true)
                .setOnlyAlertOnce(!fresh)
                .setCategory(Notification.CATEGORY_REMINDER)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .setContentIntent(open)
                .setStyle(new Notification.DecoratedCustomViewStyle())
                .setCustomBigContentView(big)
                .addAction(new Notification.Action.Builder(null, "QRを大きく表示", open).build())
                .addAction(new Notification.Action.Builder(null, "出席した", done).build());
        if (qr != null) b.setLargeIcon(qr);
        try {
            if (fresh) nm.cancel(ID_CARD);
            nm.notify(ID_CARD, b.build());
        } catch (Exception ignored) {
        }
    }

    private void cancelCard() {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm != null) nm.cancel(ID_CARD);
    }

    private void postLaunchNotification() {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm == null) return;
        PendingIntent pi = lockPendingIntent(21);
        Notification.Builder b = new Notification.Builder(this, CH_LAUNCH)
                .setSmallIcon(R.drawable.ic_stat)
                .setContentTitle("出席QR")
                .setContentText("タップでQRコードと時間割を表示")
                .setCategory(Notification.CATEGORY_ALARM)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .setAutoCancel(true)
                .setTimeoutAfter(20000)
                .setContentIntent(pi);
        try {
            nm.cancel(ID_LAUNCH);
            nm.notify(ID_LAUNCH, b.build());
        } catch (Exception ignored) {
        }
    }

    private void cancelLaunchNotification() {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm != null) nm.cancel(ID_LAUNCH);
    }
}
