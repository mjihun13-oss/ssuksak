package com.ssuksak.app;

import android.Manifest;
import android.app.Activity;
import android.app.PendingIntent;
import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.StatFs;
import android.provider.DocumentsContract;
import android.provider.MediaStore;
import android.util.Size;
import android.webkit.JavascriptInterface;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

/**
 * 쓱싹: 화면은 assets/index.html(WebView), 파일 접근·분석·삭제는 이 클래스가 담당합니다.
 * 분석은 전부 기기 안에서만 이루어지고, ssuk.local 외 모든 네트워크 요청은 차단됩니다.
 */
public class MainActivity extends Activity {

    private static final int REQ_PERM = 1, REQ_OP = 2, REQ_TREE = 3;
    private static final int CHUNK = 300;          // 시스템 확인창 한 번에 처리할 파일 수
    private static final String HOST = "ssuk.local";
    private static final Pattern SHOT = Pattern.compile("(?i)screen\\s?shot|스크린샷|capture|캡처");

    private WebView web;
    private ContentResolver cr;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private volatile boolean scanning = false, stopFlag = false;
    private Uri backupTree;
    private final Map<String, Item> lastItems = new HashMap<>();
    private Op op;

    // ───────── 데이터 모델 ─────────
    static class Item {
        String id, name, path;
        long size, mtime;
        boolean video, shot, bad, flat, analyzed;
        double lap, bright, ar;
        long h0, h1;
    }

    static class Op {
        String mode;                       // trash | restore | delete
        List<String> ids;
        List<String> cur = new ArrayList<>();
        List<String> okIds = new ArrayList<>();
        int pos = 0;
        boolean cancelled = false;
        Op(String m, List<String> i) { mode = m; ids = i; }
    }

    // ───────── 생명주기 ─────────
    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        cr = getContentResolver();
        String t = getSharedPreferences("s", MODE_PRIVATE).getString("tree", null);
        if (t != null) backupTree = Uri.parse(t);

        web = new WebView(this);
        setContentView(web);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(false);
        web.addJavascriptInterface(new Bridge(), "Android");
        web.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView v, WebResourceRequest r) {
                return intercept(r.getUrl());
            }
        });
        web.loadUrl("https://" + HOST + "/index.html");
    }

    @Override
    public void onBackPressed() {
        web.evaluateJavascript("window.__back?window.__back():false", v -> {
            if (!"true".equals(v)) MainActivity.super.onBackPressed();
        });
    }

    @Override
    protected void onDestroy() {
        stopFlag = true;
        super.onDestroy();
    }

    // ───────── WebView 요청 처리 (페이지·썸네일) ─────────
    private WebResourceResponse resp(int code, String reason) {
        return new WebResourceResponse("text/plain", "utf-8", code, reason,
                new HashMap<String, String>(), new ByteArrayInputStream(new byte[0]));
    }

    private WebResourceResponse intercept(Uri u) {
        if (!HOST.equals(u.getHost())) return resp(403, "Blocked");   // 외부 통신 차단
        String p = u.getPath();
        try {
            if (p == null || p.equals("/") || p.equals("/index.html")) {
                return new WebResourceResponse("text/html", "utf-8", getAssets().open("index.html"));
            }
            if (p.startsWith("/t/")) {
                Bitmap bm = thumb(p.substring(3), 360);
                if (bm != null) {
                    ByteArrayOutputStream bo = new ByteArrayOutputStream();
                    bm.compress(Bitmap.CompressFormat.JPEG, 80, bo);
                    return new WebResourceResponse("image/jpeg", null, new ByteArrayInputStream(bo.toByteArray()));
                }
            }
        } catch (Throwable e) {
            // fall through
        }
        return resp(404, "Not Found");
    }

    private Uri uriOf(String id) {
        long n = Long.parseLong(id.substring(1));
        Uri base = id.charAt(0) == 'v' ? MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                                       : MediaStore.Images.Media.EXTERNAL_CONTENT_URI;
        return ContentUris.withAppendedId(base, n);
    }

    private Bitmap thumb(String id, int px) {
        try {
            return cr.loadThumbnail(uriOf(id), new Size(px, px), null);
        } catch (Throwable e) {
            return null;
        }
    }

    // ───────── JS 호출 ─────────
    private void call(String fn, String args) {
        final String code = "window." + fn + "&&window." + fn + "(" + args + ")";
        ui.post(() -> web.evaluateJavascript(code, null));
    }

    private static String q(String s) { return JSONObject.quote(s); }

    private static List<String> parseIds(String json) {
        List<String> out = new ArrayList<>();
        try {
            JSONArray a = new JSONArray(json);
            for (int i = 0; i < a.length(); i++) out.add(a.getString(i));
        } catch (Exception e) { /* ignore */ }
        return out;
    }

    // ───────── 권한 ─────────
    private String[] perms() {
        if (Build.VERSION.SDK_INT >= 33) {
            return new String[]{Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO};
        }
        return new String[]{Manifest.permission.READ_EXTERNAL_STORAGE};
    }

    private boolean hasPerm() {
        for (String p : perms()) {
            if (checkSelfPermission(p) != PackageManager.PERMISSION_GRANTED) return false;
        }
        return true;
    }

    @Override
    public void onRequestPermissionsResult(int req, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(req, permissions, results);
        if (req == REQ_PERM) call("onPerm", hasPerm() ? "true" : "false");
    }

    // ───────── 브리지 ─────────
    class Bridge {
        @JavascriptInterface public boolean hasPermission() { return hasPerm(); }

        @JavascriptInterface public void requestPermission() {
            ui.post(() -> requestPermissions(perms(), REQ_PERM));
        }

        @JavascriptInterface public String storage() {
            try {
                StatFs st = new StatFs(Environment.getDataDirectory().getPath());
                return "{\"total\":" + st.getTotalBytes() + ",\"free\":" + st.getAvailableBytes() + "}";
            } catch (Exception e) {
                return "{\"total\":0,\"free\":0}";
            }
        }

        @JavascriptInterface public void startScan() {
            new Thread(MainActivity.this::scan, "ssuk-scan").start();
        }

        @JavascriptInterface public void cancelScan() { stopFlag = true; }

        @JavascriptInterface public void trash(String idsJson) {
            final List<String> ids = parseIds(idsJson);
            ui.post(() -> runOp("trash", ids));
        }

        @JavascriptInterface public void restore(String idsJson) {
            final List<String> ids = parseIds(idsJson);
            ui.post(() -> runOp("restore", ids));
        }

        @JavascriptInterface public void deleteForever(String idsJson) {
            final List<String> ids = parseIds(idsJson);
            ui.post(() -> runOp("delete", ids));
        }

        @JavascriptInterface public void listTrash() {
            new Thread(MainActivity.this::doListTrash, "ssuk-trash").start();
        }

        @JavascriptInterface public String backupName() { return backupTree == null ? "" : treeName(backupTree); }

        @JavascriptInterface public void pickBackupDir() {
            ui.post(() -> {
                Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
                i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                        | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                        | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
                startActivityForResult(i, REQ_TREE);
            });
        }

        @JavascriptInterface public void backup(String idsJson) {
            final List<String> ids = parseIds(idsJson);
            new Thread(() -> doBackup(ids), "ssuk-backup").start();
        }
    }

    private String treeName(Uri tree) {
        try {
            String id = DocumentsContract.getTreeDocumentId(tree);   // 예: primary:Backup
            int c = id.indexOf(':');
            String name = c >= 0 ? id.substring(c + 1) : id;
            return name.isEmpty() ? "저장소 루트" : name;
        } catch (Exception e) {
            return "선택한 폴더";
        }
    }

    // ───────── 스캔 ─────────
    private void scan() {
        if (scanning) return;
        scanning = true;
        stopFlag = false;
        ExecutorService ex = null;
        try {
            call("onProg", q("사진 목록 읽는 중") + ",0,0");
            List<Item> items = new ArrayList<>();
            query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, false, items);
            query(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, true, items);

            Map<String, double[]> cache = loadCache();
            List<Item> todo = new ArrayList<>();
            for (Item it : items) {
                if (it.video) continue;
                double[] c = cache.get(it.id + "|" + it.size + "|" + it.mtime);
                if (c != null) {
                    it.lap = c[0]; it.bright = c[1]; it.ar = c[2];
                    it.h0 = (long) c[3]; it.h1 = (long) c[4];
                    it.flat = c[5] == 1; it.bad = c[5] == 2; it.analyzed = true;
                } else {
                    todo.add(it);
                }
            }

            final AtomicInteger done = new AtomicInteger(0);
            final int total = todo.size();
            if (total > 0) {
                int threads = Math.max(2, Math.min(4, Runtime.getRuntime().availableProcessors() - 1));
                ex = Executors.newFixedThreadPool(threads);
                for (final Item it : todo) {
                    ex.submit(() -> {
                        if (!stopFlag) analyze(it);
                        done.incrementAndGet();
                    });
                }
                ex.shutdown();
                while (!ex.awaitTermination(300, TimeUnit.MILLISECONDS)) {
                    call("onProg", q("사진 분석 중") + "," + done.get() + "," + total);
                    if (stopFlag) ex.shutdownNow();
                }
                call("onProg", q("사진 분석 중") + "," + total + "," + total);
            }

            saveCache(items);
            lastItems.clear();
            JSONArray chunk = new JSONArray();
            for (Item it : items) {
                lastItems.put(it.id, it);
                chunk.put(toJson(it));
                if (chunk.length() >= 400) {
                    call("onItems", chunk.toString());
                    chunk = new JSONArray();
                }
            }
            if (chunk.length() > 0) call("onItems", chunk.toString());
        } catch (Throwable e) {
            call("onScanError", q(String.valueOf(e.getMessage())));
        } finally {
            if (ex != null) ex.shutdownNow();
            scanning = false;
            call("onScanDone", "");
        }
    }

    private void query(Uri base, boolean video, List<Item> out) {
        String[] proj = {
                MediaStore.MediaColumns._ID,
                MediaStore.MediaColumns.DISPLAY_NAME,
                MediaStore.MediaColumns.SIZE,
                MediaStore.MediaColumns.DATE_MODIFIED,
                MediaStore.MediaColumns.DATE_TAKEN,
                MediaStore.MediaColumns.RELATIVE_PATH
        };
        try (Cursor c = cr.query(base, proj, null, null, MediaStore.MediaColumns.DATE_MODIFIED + " DESC")) {
            if (c == null) return;
            while (c.moveToNext()) {
                Item it = new Item();
                it.id = (video ? "v" : "i") + c.getLong(0);
                it.name = c.getString(1) == null ? "(이름 없음)" : c.getString(1);
                it.size = c.getLong(2);
                long mod = c.getLong(3) * 1000L, taken = c.getLong(4);
                it.mtime = taken > 0 ? taken : mod;
                it.path = c.getString(5) == null ? "" : c.getString(5);
                it.video = video;
                it.shot = !video && (it.path.toLowerCase().contains("screenshot")
                        || SHOT.matcher(it.name).find());
                if (it.size > 0) out.add(it);
            }
        }
    }

    private JSONObject toJson(Item it) {
        JSONObject o = new JSONObject();
        try {
            o.put("i", it.id);
            o.put("n", it.name);
            o.put("s", it.size);
            o.put("m", it.mtime);
            o.put("p", it.path);
            o.put("v", it.video);
            o.put("sh", it.shot);
            if (!it.video) {
                if (it.bad || !it.analyzed) {
                    o.put("bad", true);
                } else {
                    o.put("l", fin(it.lap));
                    o.put("b", fin(it.bright));
                    o.put("ar", fin(it.ar));
                    if (!it.flat) {
                        o.put("h0", it.h0);
                        o.put("h1", it.h1);
                    }
                }
            }
        } catch (Exception e) { /* ignore */ }
        return o;
    }

    private static double fin(double d) { return Double.isNaN(d) || Double.isInfinite(d) ? 0 : d; }

    // 흔들림(라플라시안 분산), 밝기, dHash(64bit)
    private void analyze(Item it) {
        try {
            Bitmap raw = cr.loadThumbnail(uriOf(it.id), new Size(256, 256), null);
            if (raw == null) { it.bad = true; return; }
            Bitmap b = raw;
            if (b.getConfig() != Bitmap.Config.ARGB_8888) {
                Bitmap c = b.copy(Bitmap.Config.ARGB_8888, false);
                if (c == null) { it.bad = true; return; }
                b = c;
            }
            int w = b.getWidth(), h = b.getHeight();
            if (w < 8 || h < 8) { it.bad = true; return; }
            it.ar = Math.round(((double) w / h) * 100) / 100.0;
            int max = Math.max(w, h);
            if (max > 256) {
                float sc = 256f / max;
                b = Bitmap.createScaledBitmap(b, Math.max(8, Math.round(w * sc)), Math.max(8, Math.round(h * sc)), true);
                w = b.getWidth();
                h = b.getHeight();
            }
            int[] px = new int[w * h];
            b.getPixels(px, 0, w, 0, 0, w, h);
            float[] g = new float[w * h];
            double sum = 0;
            for (int i = 0; i < g.length; i++) {
                int p = px[i];
                g[i] = 0.299f * ((p >> 16) & 255) + 0.587f * ((p >> 8) & 255) + 0.114f * (p & 255);
                sum += g[i];
            }
            it.bright = sum / g.length;

            double a = 0, sq = 0;
            long n = 0;
            for (int y = 1; y < h - 1; y++) {
                for (int x = 1; x < w - 1; x++) {
                    int i = y * w + x;
                    double l = 4 * g[i] - g[i - 1] - g[i + 1] - g[i - w] - g[i + w];
                    a += l;
                    sq += l * l;
                    n++;
                }
            }
            it.lap = n > 0 ? sq / n - (a / n) * (a / n) : 0;

            double[] cell = new double[72];
            for (int cy = 0; cy < 8; cy++) {
                for (int cx = 0; cx < 9; cx++) {
                    int x0 = cx * w / 9, x1 = Math.max(x0 + 1, (cx + 1) * w / 9);
                    int y0 = cy * h / 8, y1 = Math.max(y0 + 1, (cy + 1) * h / 8);
                    double s = 0;
                    int cnt = 0;
                    for (int y = y0; y < y1 && y < h; y++) {
                        for (int x = x0; x < x1 && x < w; x++) { s += g[y * w + x]; cnt++; }
                    }
                    cell[cy * 9 + cx] = cnt > 0 ? s / cnt : 0;
                }
            }
            long hi = 0, lo = 0;
            int k = 0;
            for (int y = 0; y < 8; y++) {
                for (int x = 0; x < 8; x++) {
                    long bit = cell[y * 9 + x] > cell[y * 9 + x + 1] ? 1 : 0;
                    if (k < 32) hi = (hi << 1) | bit; else lo = (lo << 1) | bit;
                    k++;
                }
            }
            it.h0 = hi;
            it.h1 = lo;
            it.flat = it.lap < 3;      // 단색에 가까우면 중복 판정에서 제외
            it.analyzed = true;
        } catch (Throwable e) {
            it.bad = true;
        }
    }

    // ───────── 분석 캐시 (증분 스캔) ─────────
    private File cacheFile() { return new File(getFilesDir(), "analysis.tsv"); }

    private Map<String, double[]> loadCache() {
        Map<String, double[]> m = new HashMap<>();
        File f = cacheFile();
        if (!f.exists()) return m;
        try (BufferedReader r = new BufferedReader(new InputStreamReader(new FileInputStream(f), "UTF-8"))) {
            String line;
            while ((line = r.readLine()) != null) {
                String[] p = line.split("\t");
                if (p.length < 7) continue;
                m.put(p[0], new double[]{
                        Double.parseDouble(p[1]), Double.parseDouble(p[2]), Double.parseDouble(p[3]),
                        Double.parseDouble(p[4]), Double.parseDouble(p[5]), Double.parseDouble(p[6])});
            }
        } catch (Exception e) { m.clear(); }
        return m;
    }

    private void saveCache(List<Item> items) {
        try (Writer w = new OutputStreamWriter(new FileOutputStream(cacheFile()), "UTF-8")) {
            StringBuilder sb = new StringBuilder();
            for (Item it : items) {
                if (it.video || !(it.analyzed || it.bad)) continue;
                int flag = it.bad ? 2 : (it.flat ? 1 : 0);
                sb.append(it.id).append('|').append(it.size).append('|').append(it.mtime).append('\t')
                  .append(fin(it.lap)).append('\t').append(fin(it.bright)).append('\t').append(fin(it.ar)).append('\t')
                  .append(it.h0).append('\t').append(it.h1).append('\t').append(flag).append('\n');
            }
            w.write(sb.toString());
        } catch (Exception e) { /* 캐시는 실패해도 무방 */ }
    }

    // ───────── 시스템 휴지통 / 복원 / 영구 삭제 ─────────
    private void runOp(String mode, List<String> ids) {
        if (ids.isEmpty()) return;
        op = new Op(mode, ids);
        nextChunk();
    }

    private void nextChunk() {
        if (op == null) return;
        if (op.pos >= op.ids.size()) { finishOp(); return; }
        int end = Math.min(op.pos + CHUNK, op.ids.size());
        op.cur = new ArrayList<>(op.ids.subList(op.pos, end));
        op.pos = end;
        List<Uri> uris = new ArrayList<>();
        for (String id : op.cur) uris.add(uriOf(id));
        try {
            PendingIntent pi;
            if (op.mode.equals("delete")) pi = MediaStore.createDeleteRequest(cr, uris);
            else pi = MediaStore.createTrashRequest(cr, uris, op.mode.equals("trash"));
            startIntentSenderForResult(pi.getIntentSender(), REQ_OP, null, 0, 0, 0);
        } catch (Exception e) {
            op.cancelled = true;
            finishOp();
        }
    }

    private void finishOp() {
        if (op == null) return;
        JSONArray arr = new JSONArray(op.okIds);
        call("onOpDone", q(op.mode) + "," + arr.toString() + "," + op.ids.size() + "," + op.cancelled);
        op = null;
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req == REQ_OP) {
            if (op == null) return;
            if (res == RESULT_OK) {
                op.okIds.addAll(op.cur);
                nextChunk();
            } else {
                op.cancelled = true;
                finishOp();
            }
        } else if (req == REQ_TREE) {
            if (res == RESULT_OK && data != null && data.getData() != null) {
                backupTree = data.getData();
                try {
                    getContentResolver().takePersistableUriPermission(backupTree,
                            Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
                } catch (Exception e) { /* ignore */ }
                getSharedPreferences("s", MODE_PRIVATE).edit().putString("tree", backupTree.toString()).apply();
                call("onBackupDir", q(treeName(backupTree)));
            } else {
                call("onBackupDir", q(""));
            }
        }
    }

    private void doListTrash() {
        JSONArray arr = new JSONArray();
        Uri[] bases = {MediaStore.Images.Media.EXTERNAL_CONTENT_URI, MediaStore.Video.Media.EXTERNAL_CONTENT_URI};
        for (int k = 0; k < bases.length; k++) {
            Bundle args = new Bundle();
            args.putInt(MediaStore.QUERY_ARG_MATCH_TRASHED, MediaStore.MATCH_ONLY);
            String[] proj = {
                    MediaStore.MediaColumns._ID,
                    MediaStore.MediaColumns.DISPLAY_NAME,
                    MediaStore.MediaColumns.SIZE,
                    MediaStore.MediaColumns.DATE_EXPIRES
            };
            try (Cursor c = cr.query(bases[k], proj, args, null)) {
                if (c == null) continue;
                while (c.moveToNext()) {
                    JSONObject o = new JSONObject();
                    o.put("i", (k == 1 ? "v" : "i") + c.getLong(0));
                    o.put("n", c.getString(1) == null ? "" : c.getString(1));
                    o.put("s", c.getLong(2));
                    o.put("ex", c.getLong(3) * 1000L);
                    arr.put(o);
                }
            } catch (Exception e) { /* ignore */ }
        }
        call("onTrash", arr.toString());
    }

    // ───────── 백업(복사 검증 후 휴지통) ─────────
    private long docSize(Uri doc) {
        try (Cursor c = cr.query(doc, new String[]{DocumentsContract.Document.COLUMN_SIZE}, null, null, null)) {
            if (c != null && c.moveToFirst()) return c.getLong(0);
        } catch (Exception e) { /* ignore */ }
        return -1;
    }

    private void doBackup(List<String> ids) {
        if (backupTree == null) { call("onBackupInfo", "0,0"); return; }
        Uri parent = DocumentsContract.buildDocumentUriUsingTree(backupTree, DocumentsContract.getTreeDocumentId(backupTree));
        List<String> ok = new ArrayList<>();
        int fail = 0;
        for (int k = 0; k < ids.size(); k++) {
            String id = ids.get(k);
            Item it = lastItems.get(id);
            Uri src = uriOf(id), dst = null;
            try {
                if (it == null) throw new IOException("unknown item");
                String mime = cr.getType(src);
                if (mime == null) mime = "application/octet-stream";
                dst = DocumentsContract.createDocument(cr, parent, mime, it.name);
                if (dst == null) throw new IOException("create failed");
                try (InputStream in = cr.openInputStream(src); OutputStream out = cr.openOutputStream(dst, "w")) {
                    if (in == null || out == null) throw new IOException("stream");
                    byte[] buf = new byte[1 << 16];
                    int n;
                    while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                }
                if (docSize(dst) != it.size) throw new IOException("size mismatch");
                ok.add(id);
            } catch (Exception e) {
                fail++;
                if (dst != null) {
                    try { DocumentsContract.deleteDocument(cr, dst); } catch (Exception ig) { /* ignore */ }
                }
            }
            call("onBackupProg", (k + 1) + "," + ids.size());
        }
        call("onBackupInfo", ok.size() + "," + fail);
        if (!ok.isEmpty()) {
            final List<String> done = ok;
            ui.post(() -> runOp("trash", done));
        }
    }
}
