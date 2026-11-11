package jp.yagi.attendqr;

import android.app.Activity;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.Bundle;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;

/** 取り込んだ画像からQRコードの部分だけを正方形に切り抜く画面。 */
public class CropActivity extends Activity {
    private CropView cropView;
    private Bitmap source;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        File f = Prefs.qrSourceFile(this);
        source = f.exists() ? BitmapFactory.decodeFile(f.getPath()) : null;
        if (source == null) {
            Toast.makeText(this, "画像を読み込めませんでした", Toast.LENGTH_LONG).show();
            finish();
            return;
        }
        int accent = Ui.accent(this);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xFF0A0D12);
        root.setFitsSystemWindows(true);

        TextView title = Ui.text(this, "QRコードの部分を囲んでください", 17, Ui.D_TEXT, Ui.MEDIUM);
        title.setPadding(Ui.dp(this, 22), Ui.dp(this, 20), Ui.dp(this, 22), 0);
        root.addView(title);
        TextView hint = Ui.text(this, "枠の中をドラッグで移動、四隅をドラッグで大きさを変えられます。QRのまわりに少し白い余白を残すと読み取りやすくなります。",
                12, Ui.D_TEXT2, Ui.REGULAR);
        hint.setLineSpacing(Ui.dp(this, 3), 1f);
        hint.setPadding(Ui.dp(this, 22), Ui.dp(this, 8), Ui.dp(this, 22), Ui.dp(this, 12));
        root.addView(hint);

        cropView = new CropView(this, source, accent);
        root.addView(cropView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        LinearLayout buttons = new LinearLayout(this);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        buttons.setGravity(Gravity.CENTER);
        buttons.setPadding(Ui.dp(this, 18), Ui.dp(this, 14), Ui.dp(this, 18), Ui.dp(this, 18));
        TextView whole = Ui.ghost(this, "画像全体を使う", Ui.D_STROKE, Ui.D_TEXT);
        whole.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                save(source);
            }
        });
        TextView ok = Ui.pill(this, "切り抜いて保存", accent, Ui.onColor(accent));
        ok.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Bitmap b = cropView.cropped();
                save(b != null ? b : source);
            }
        });
        LinearLayout.LayoutParams l1 = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        LinearLayout.LayoutParams l2 = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        l2.leftMargin = Ui.dp(this, 10);
        buttons.addView(whole, l1);
        buttons.addView(ok, l2);
        root.addView(buttons);

        setContentView(root);
    }

    private void save(Bitmap b) {
        try {
            FileOutputStream out = new FileOutputStream(Prefs.qrFile(this));
            b.compress(Bitmap.CompressFormat.PNG, 100, out);
            out.close();
            Scheduler.sync(this);
            Toast.makeText(this, "QR画像を保存しました", Toast.LENGTH_SHORT).show();
        } catch (Exception ex) {
            Toast.makeText(this, "保存できませんでした：" + ex.getMessage(), Toast.LENGTH_LONG).show();
        }
        finish();
    }

    /** 画像と、動かせる正方形の枠。 */
    private static final class CropView extends View {
        private final Bitmap bmp;
        private final int accent;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        private final RectF img = new RectF();   // 画面上の画像の範囲
        private final RectF sel = new RectF();   // 画面上の選択枠
        private final float density;
        private int mode;                         // 0=なし 1=移動 2=拡大縮小
        private float lastX, lastY;
        private float anchorX, anchorY;           // 拡大縮小のとき固定する角
        private boolean initialized;

        CropView(Context c, Bitmap b, int accent) {
            super(c);
            this.bmp = b;
            this.accent = accent;
            this.density = c.getResources().getDisplayMetrics().density;
        }

        @Override
        protected void onSizeChanged(int w, int h, int oldw, int oldh) {
            float pad = 20 * density;
            float aw = w - pad * 2, ah = h - pad * 2;
            if (aw <= 0 || ah <= 0) return;
            float scale = Math.min(aw / bmp.getWidth(), ah / bmp.getHeight());
            float iw = bmp.getWidth() * scale, ih = bmp.getHeight() * scale;
            img.set((w - iw) / 2f, (h - ih) / 2f, (w + iw) / 2f, (h + ih) / 2f);
            // 向きが変わっても、初回だけ中央に最大の正方形を置く
            if (!initialized || !img.contains(sel)) {
                float s = Math.min(iw, ih);
                sel.set(img.centerX() - s / 2f, img.centerY() - s / 2f,
                        img.centerX() + s / 2f, img.centerY() + s / 2f);
                initialized = true;
            }
        }

        @Override
        protected void onDraw(Canvas canvas) {
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(Color.WHITE);
            canvas.drawBitmap(bmp, null, img, paint);
            // 枠の外を暗くする
            paint.setColor(0xB0000000);
            canvas.drawRect(img.left, img.top, img.right, sel.top, paint);
            canvas.drawRect(img.left, sel.bottom, img.right, img.bottom, paint);
            canvas.drawRect(img.left, sel.top, sel.left, sel.bottom, paint);
            canvas.drawRect(sel.right, sel.top, img.right, sel.bottom, paint);
            // 枠
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(2 * density);
            paint.setColor(accent);
            canvas.drawRect(sel, paint);
            // 四隅のつまみ
            paint.setStyle(Paint.Style.FILL);
            float r = 9 * density;
            canvas.drawCircle(sel.left, sel.top, r, paint);
            canvas.drawCircle(sel.right, sel.top, r, paint);
            canvas.drawCircle(sel.left, sel.bottom, r, paint);
            canvas.drawCircle(sel.right, sel.bottom, r, paint);
            paint.setColor(Color.WHITE);
            float r2 = 4 * density;
            canvas.drawCircle(sel.left, sel.top, r2, paint);
            canvas.drawCircle(sel.right, sel.top, r2, paint);
            canvas.drawCircle(sel.left, sel.bottom, r2, paint);
            canvas.drawCircle(sel.right, sel.bottom, r2, paint);
        }

        @Override
        public boolean onTouchEvent(MotionEvent ev) {
            float x = ev.getX(), y = ev.getY();
            switch (ev.getActionMasked()) {
                case MotionEvent.ACTION_DOWN: {
                    float grab = 34 * density;
                    float[] cx = {sel.left, sel.right, sel.left, sel.right};
                    float[] cy = {sel.top, sel.top, sel.bottom, sel.bottom};
                    mode = 0;
                    for (int i = 0; i < 4; i++) {
                        if (Math.abs(x - cx[i]) < grab && Math.abs(y - cy[i]) < grab) {
                            mode = 2;
                            anchorX = cx[3 - i];
                            anchorY = cy[3 - i];
                            break;
                        }
                    }
                    if (mode == 0 && sel.contains(x, y)) mode = 1;
                    lastX = x;
                    lastY = y;
                    if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
                    return true;
                }
                case MotionEvent.ACTION_MOVE: {
                    if (mode == 1) {
                        float dx = x - lastX, dy = y - lastY;
                        dx = Math.max(img.left - sel.left, Math.min(img.right - sel.right, dx));
                        dy = Math.max(img.top - sel.top, Math.min(img.bottom - sel.bottom, dy));
                        sel.offset(dx, dy);
                        lastX = x;
                        lastY = y;
                        invalidate();
                    } else if (mode == 2) {
                        // 固定した角から指までの距離で、正方形の一辺を決める
                        float sx = x >= anchorX ? 1 : -1, sy = y >= anchorY ? 1 : -1;
                        float size = Math.max(Math.abs(x - anchorX), Math.abs(y - anchorY));
                        float maxX = sx > 0 ? img.right - anchorX : anchorX - img.left;
                        float maxY = sy > 0 ? img.bottom - anchorY : anchorY - img.top;
                        size = Math.min(size, Math.min(maxX, maxY));
                        size = Math.max(size, Math.min(48 * density, Math.min(maxX, maxY)));
                        if (size > 1) {
                            float x2 = anchorX + sx * size, y2 = anchorY + sy * size;
                            sel.set(Math.min(anchorX, x2), Math.min(anchorY, y2),
                                    Math.max(anchorX, x2), Math.max(anchorY, y2));
                            invalidate();
                        }
                    }
                    return true;
                }
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    mode = 0;
                    return true;
                default:
                    return true;
            }
        }

        Bitmap cropped() {
            if (img.width() <= 0) return null;
            float scale = bmp.getWidth() / img.width();
            int x = Math.round((sel.left - img.left) * scale);
            int y = Math.round((sel.top - img.top) * scale);
            int s = Math.round(sel.width() * scale);
            x = Math.max(0, Math.min(bmp.getWidth() - 1, x));
            y = Math.max(0, Math.min(bmp.getHeight() - 1, y));
            s = Math.min(s, Math.min(bmp.getWidth() - x, bmp.getHeight() - y));
            if (s < 8) return null;
            try {
                return Bitmap.createBitmap(bmp, x, y, s, s);
            } catch (Throwable t) {
                return null;
            }
        }
    }
}
