package jp.yagi.attendqr;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/** 時間割データ（同梱データ＋クラスごとの編集内容）。 */
final class TT {
    private TT() {}

    static final String[] DAY_NAMES = {"月", "火", "水", "木", "金", "土", "日"};

    /** 各時限(1〜10)の開始・終了（0時からの分）。90分授業の前半/後半。 */
    private static final int[] P_START = {0, 540, 585, 640, 685, 780, 825, 880, 925, 975, 1020};
    private static final int[] P_END = {0, 585, 630, 685, 730, 825, 870, 925, 970, 1020, 1065};

    static int startOf(int period) { return P_START[clamp(period)]; }
    static int endOf(int period) { return P_END[clamp(period)]; }
    private static int clamp(int p) { return p < 1 ? 1 : (p > 10 ? 10 : p); }

    static final class Entry {
        int day;      // 月=0 ... 金=4
        int s;        // 開始時限
        int e;        // 終了時限
        String name = "";
        String teacher = "";
        String room = "";

        JSONArray toJson() {
            JSONArray a = new JSONArray();
            a.put(day); a.put(s); a.put(e); a.put(name); a.put(teacher); a.put(room);
            return a;
        }

        static Entry fromJson(JSONArray a) {
            Entry x = new Entry();
            x.day = a.optInt(0);
            x.s = a.optInt(1, 1);
            x.e = a.optInt(2, x.s);
            x.name = a.optString(3, "");
            x.teacher = a.optString(4, "");
            x.room = a.optString(5, "");
            return x;
        }
    }

    private static JSONObject bundled;

    private static synchronized JSONObject bundled(Context c) {
        if (bundled == null) {
            try {
                InputStream in = c.getAssets().open("timetable.json");
                ByteArrayOutputStream bo = new ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
                in.close();
                bundled = new JSONObject(new String(bo.toByteArray(), "UTF-8"));
            } catch (Exception ex) {
                bundled = new JSONObject();
            }
        }
        return bundled;
    }

    static String version(Context c) {
        return bundled(c).optString("version", "");
    }

    static List<String> classes(Context c) {
        List<String> out = new ArrayList<String>();
        JSONArray a = bundled(c).optJSONArray("classes");
        if (a != null) for (int i = 0; i < a.length(); i++) out.add(a.optString(i));
        return out;
    }

    static boolean isEdited(Context c, String cls) {
        return Prefs.sp(c).contains("tt_" + cls);
    }

    static List<Entry> load(Context c, String cls) {
        List<Entry> out = new ArrayList<Entry>();
        try {
            JSONArray a;
            String custom = Prefs.sp(c).getString("tt_" + cls, null);
            if (custom != null) {
                a = new JSONArray(custom);
            } else {
                JSONObject data = bundled(c).optJSONObject("data");
                a = data == null ? null : data.optJSONArray(cls);
            }
            if (a != null) {
                for (int i = 0; i < a.length(); i++) {
                    JSONArray row = a.optJSONArray(i);
                    if (row != null) out.add(Entry.fromJson(row));
                }
            }
        } catch (Exception ignored) {
        }
        sort(out);
        return out;
    }

    static void save(Context c, String cls, List<Entry> list) {
        sort(list);
        JSONArray a = new JSONArray();
        for (Entry e : list) a.put(e.toJson());
        Prefs.sp(c).edit().putString("tt_" + cls, a.toString()).apply();
    }

    static void reset(Context c, String cls) {
        Prefs.sp(c).edit().remove("tt_" + cls).apply();
    }

    static List<Entry> forDay(Context c, String cls, int day) {
        List<Entry> out = new ArrayList<Entry>();
        for (Entry e : load(c, cls)) if (e.day == day) out.add(e);
        return out;
    }

    private static void sort(List<Entry> list) {
        Collections.sort(list, new Comparator<Entry>() {
            @Override
            public int compare(Entry a, Entry b) {
                if (a.day != b.day) return a.day - b.day;
                if (a.s != b.s) return a.s - b.s;
                return a.e - b.e;
            }
        });
    }

    static String periodLabel(Entry e) {
        return e.s == e.e ? String.valueOf(e.s) : e.s + "-" + e.e;
    }

    static String timeLabel(Entry e) {
        return Prefs.hm(startOf(e.s)) + "〜" + Prefs.hm(endOf(e.e));
    }

    /** 通知カードなどに使う「きょうの要約」。[0]=見出し [1]=補足 [2]=時間割の一覧 */
    static String[] summary(Context c) {
        java.util.Calendar cal = java.util.Calendar.getInstance();
        int day = Prefs.dayIndex(cal);
        int nowMin = cal.get(java.util.Calendar.HOUR_OF_DAY) * 60
                + cal.get(java.util.Calendar.MINUTE);
        List<Entry> list = forDay(c, Prefs.cls(c), day);
        String head = "", sub = "";
        StringBuilder lines = new StringBuilder();
        int prevS = -1, prevE = -1;
        boolean found = false;
        for (int i = 0; i < list.size(); i++) {
            Entry e = list.get(i);
            boolean same = e.s == prevS && e.e == prevE;
            prevS = e.s;
            prevE = e.e;
            if (same) {
                lines.append(" / ").append(e.name);
            } else {
                if (lines.length() > 0) lines.append("\n");
                lines.append(periodLabel(e)).append("限  ").append(e.name);
            }
            int st = startOf(e.s), en = endOf(e.e);
            if (!found && nowMin < en) {
                found = true;
                boolean now = nowMin >= st;
                head = (now ? "授業中 " : "つぎ ") + e.name;
                sub = Prefs.hm(st) + "–" + Prefs.hm(en)
                        + (e.room.length() > 0 ? " ・ " + e.room : "");
            } else if (found && same && head.length() > 0 && i > 0
                    && startOf(list.get(i - 1).s) == st && head.endsWith(list.get(i - 1).name)) {
                head = head + " / " + e.name;
                sub = Prefs.hm(st) + "–" + Prefs.hm(en) + " ・ 選択科目";
            }
        }
        if (list.isEmpty()) {
            head = "きょうの授業はありません";
        } else if (!found) {
            head = "本日の授業は終了";
        }
        return new String[]{head, sub, lines.toString()};
    }
}
