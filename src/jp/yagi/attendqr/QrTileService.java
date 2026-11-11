package jp.yagi.attendqr;

import android.app.PendingIntent;
import android.content.Intent;
import android.os.Build;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;

/** クイック設定パネルのタイル：いつでもワンタップでQRを表示する。 */
public class QrTileService extends TileService {
    @Override
    public void onStartListening() {
        Tile t = getQsTile();
        if (t != null) {
            t.setState(Tile.STATE_INACTIVE);
            t.setLabel("出席QR");
            t.updateTile();
        }
    }

    @Override
    public void onClick() {
        Intent i = new Intent(this, LockActivity.class);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        i.putExtra(LockActivity.EXTRA_PREVIEW, true);
        try {
            if (Build.VERSION.SDK_INT >= 34) {
                PendingIntent pi = PendingIntent.getActivity(this, 30, i,
                        PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
                startActivityAndCollapse(pi);
            } else {
                startActivityAndCollapse(i);
            }
        } catch (Exception ex) {
            try {
                startActivity(i);
            } catch (Exception ignored) {
            }
        }
    }
}
