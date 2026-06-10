package me.linkmax.safetynote;

import android.Manifest;
import android.app.DownloadManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.webkit.WebResourceRequest;
import android.webkit.WebView;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.core.content.FileProvider;

import com.getcapacitor.BridgeActivity;
import com.getcapacitor.BridgeWebViewClient;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

public class MainActivity extends BridgeActivity {

    private static final String TAG = "SafetyNOTE";
    private static final int PERM_REQUEST_CODE = 100;

    // APK 다운로드 추적
    private long apkDownloadId = -1;
    private BroadcastReceiver downloadReceiver;

    // ── 런타임 요청 권한 목록 ─────────────────────────────────────────────────
    private static final String[] REQUIRED_PERMISSIONS;
    static {
        List<String> perms = new ArrayList<>();
        perms.add(Manifest.permission.ACCESS_FINE_LOCATION);
        perms.add(Manifest.permission.ACCESS_COARSE_LOCATION);
        perms.add(Manifest.permission.CAMERA);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            perms.add(Manifest.permission.POST_NOTIFICATIONS);
        }
        REQUIRED_PERMISSIONS = perms.toArray(new String[0]);
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // 권한 요청
        requestMissingPermissions();

        // ── DownloadManager 완료 수신기 등록 ────────────────────────────────
        downloadReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                long id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1);
                if (id == apkDownloadId && id != -1) {
                    Log.d(TAG, "APK 다운로드 완료: id=" + id);
                    installDownloadedApk(id);
                }
            }
        };

        // Android 13+ : RECEIVER_NOT_EXPORTED 플래그 필요
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(downloadReceiver,
                new IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE),
                Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(downloadReceiver,
                new IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE));
        }

        // ── WebViewClient: BridgeWebViewClient 상속 ───────────────────────────
        // ✅ new WebViewClient() 대신 BridgeWebViewClient 사용
        //    → shouldInterceptRequest 가 부모(Bridge)에 위임되어 www/ 로컬 에셋 정상 서빙
        //    → ERR_CONNECTION_REFUSED 발생하지 않음
        getBridge().getWebView().setWebViewClient(new BridgeWebViewClient(getBridge()) {

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                String url = request.getUrl().toString();
                Log.d(TAG, "URL 로딩 요청: " + url);

                // ── APK 다운로드: DownloadManager로 직접 처리 ───────────────
                // window.open(url, '_system') 또는 링크 클릭 모두 캐치
                if (url.endsWith(".apk") || url.contains(".apk?") || url.contains("/apk/")) {
                    Log.d(TAG, "APK 다운로드 요청: " + url);
                    startApkDownload(url);
                    return true;  // WebView에서 직접 로드하지 않음
                }

                // ── 지도 앱 URL 스킴 ────────────────────────────────────────
                if (url.startsWith("tmap://"))     return launchExternalApp(url, "https://tmap.life/");
                if (url.startsWith("kakaomap://")) return launchExternalApp(url, "https://map.kakao.com/");
                if (url.startsWith("nmap://"))     return launchExternalApp(url, "https://map.naver.com/");

                // ── intent:// 스킴 ──────────────────────────────────────────
                if (url.startsWith("intent://")) {
                    try {
                        Intent intent = Intent.parseUri(url, Intent.URI_INTENT_SCHEME);
                        if (getPackageManager().resolveActivity(intent, 0) != null) {
                            startActivity(intent);
                        } else {
                            String pkg = intent.getPackage();
                            if (pkg != null) {
                                startActivity(new Intent(Intent.ACTION_VIEW,
                                        Uri.parse("market://details?id=" + pkg)));
                            }
                        }
                        return true;
                    } catch (Exception e) {
                        Log.e(TAG, "intent:// 처리 실패: " + e.getMessage());
                        return false;
                    }
                }

                // ── 기타 외부 스킴 (tel, mailto 등) ────────────────────────
                if (!url.startsWith("http://") && !url.startsWith("https://")) {
                    try {
                        startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
                        return true;
                    } catch (Exception e) {
                        Log.e(TAG, "외부 스킴 처리 실패: " + url);
                        return false;
                    }
                }

                // ── http/https → Bridge에 위임 ──────────────────────────────
                return super.shouldOverrideUrlLoading(view, request);
            }

            /** 외부 앱 실행 → 미설치 시 웹 폴백 */
            private boolean launchExternalApp(String appUrl, String webFallbackUrl) {
                try {
                    Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(appUrl));
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    startActivity(intent);
                    return true;
                } catch (Exception e) {
                    Log.d(TAG, "앱 미설치, 웹으로 대체: " + webFallbackUrl);
                    try {
                        Intent webIntent = new Intent(Intent.ACTION_VIEW, Uri.parse(webFallbackUrl));
                        webIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        startActivity(webIntent);
                        return true;
                    } catch (Exception e2) {
                        Log.e(TAG, "웹 폴백도 실패: " + e2.getMessage());
                        return false;
                    }
                }
            }
        });
    }

    // ── APK 다운로드 (DownloadManager) ───────────────────────────────────────

    /**
     * DownloadManager로 APK 직접 다운로드
     *
     * ✅ 자체서명 인증서(NAS HTTPS) 대응:
     *    - https → http 프로토콜 변환 시도
     *      (AndroidManifest usesCleartextTraffic=true 설정과 함께 동작)
     *    - DownloadManager는 WebView SSL 예외와 별개로 동작하므로
     *      자체서명 인증서를 직접 신뢰할 수 없음 → HTTP 변환이 가장 안전
     *
     * ✅ 다운로드 흐름:
     *    startApkDownload() → DownloadManager.enqueue()
     *    → onApkDownloadStarted() JS 콜백 → 사용자에게 진행 안내
     *    → BroadcastReceiver(ACTION_DOWNLOAD_COMPLETE) → installDownloadedApk()
     *    → FileProvider URI → ACTION_VIEW 설치 인텐트
     */
    private void startApkDownload(String url) {
        try {
            // 기존 다운로드 파일 삭제 (재시도 시 충돌 방지)
            File destFile = new File(
                getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS),
                "safetynote-update.apk"
            );
            if (destFile.exists()) {
                destFile.delete();
                Log.d(TAG, "기존 APK 삭제: " + destFile.getAbsolutePath());
            }

            // ✅ 자체서명 인증서 대응: https → http 변환
            // NAS의 자체서명 HTTPS는 DownloadManager에서 SSL 오류 발생 가능
            // AndroidManifest.xml usesCleartextTraffic=true 설정으로 HTTP 허용
            String downloadUrl = url;
            if (downloadUrl.startsWith("https://")) {
                downloadUrl = "http://" + downloadUrl.substring(8);
                Log.d(TAG, "자체서명 인증서 대응: https → http 변환: " + downloadUrl);
            }

            DownloadManager.Request request = new DownloadManager.Request(Uri.parse(downloadUrl));
            request.setTitle("Safety NOTE 업데이트");
            request.setDescription("새 버전을 다운로드 중...");
            request.setNotificationVisibility(
                DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
            request.setDestinationUri(Uri.fromFile(destFile));
            // HTTP 허용 (cleartext)
            request.setAllowedOverMetered(true);
            request.setAllowedOverRoaming(true);

            DownloadManager dm = (DownloadManager) getSystemService(Context.DOWNLOAD_SERVICE);
            apkDownloadId = dm.enqueue(request);
            Log.d(TAG, "APK 다운로드 시작: id=" + apkDownloadId + " url=" + downloadUrl);

            // WebView JS에 다운로드 시작 알림
            getBridge().getWebView().post(() ->
                getBridge().getWebView().evaluateJavascript(
                    "if(window.onApkDownloadStarted) window.onApkDownloadStarted();", null)
            );

        } catch (Exception e) {
            Log.e(TAG, "APK 다운로드 시작 실패: " + e.getMessage());
            // WebView에 실패 알림
            getBridge().getWebView().post(() ->
                getBridge().getWebView().evaluateJavascript(
                    "if(window.onApkDownloadFailed) window.onApkDownloadFailed();", null)
            );
        }
    }

    /**
     * 다운로드 완료 후 APK 설치 인텐트 실행
     *
     * ✅ Android 7.0+ : FileProvider URI 사용 (직접 file:// 불가 → FileUriExposedException)
     * ✅ Android 8.0+ : REQUEST_INSTALL_PACKAGES 권한 필요 (AndroidManifest에 추가됨)
     * ✅ file_paths.xml : res/xml/file_paths.xml 에 external-files-path 정의 필수
     */
    private void installDownloadedApk(long downloadId) {
        try {
            DownloadManager dm = (DownloadManager) getSystemService(Context.DOWNLOAD_SERVICE);
            DownloadManager.Query query = new DownloadManager.Query();
            query.setFilterById(downloadId);

            android.database.Cursor cursor = dm.query(query);
            if (cursor == null) {
                Log.e(TAG, "DownloadManager 쿼리 실패");
                return;
            }

            if (!cursor.moveToFirst()) {
                Log.e(TAG, "DownloadManager 결과 없음");
                cursor.close();
                return;
            }

            int statusCol = cursor.getColumnIndex(DownloadManager.COLUMN_STATUS);
            int status = (statusCol >= 0) ? cursor.getInt(statusCol) : -1;
            cursor.close();

            Log.d(TAG, "APK 다운로드 상태: " + status);

            if (status != DownloadManager.STATUS_SUCCESSFUL) {
                Log.e(TAG, "APK 다운로드 실패 status=" + status);
                // WebView에 실패 알림
                getBridge().getWebView().post(() ->
                    getBridge().getWebView().evaluateJavascript(
                        "if(window.onApkDownloadFailed) window.onApkDownloadFailed();", null)
                );
                return;
            }

            File apkFile = new File(
                getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS),
                "safetynote-update.apk"
            );

            if (!apkFile.exists()) {
                Log.e(TAG, "APK 파일 없음: " + apkFile.getAbsolutePath());
                getBridge().getWebView().post(() ->
                    getBridge().getWebView().evaluateJavascript(
                        "if(window.onApkDownloadFailed) window.onApkDownloadFailed();", null)
                );
                return;
            }

            Log.d(TAG, "APK 파일 확인: " + apkFile.getAbsolutePath()
                + " (" + apkFile.length() + " bytes)");

            Intent installIntent = new Intent(Intent.ACTION_VIEW);
            installIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                // Android 7.0+ : FileProvider 사용
                // AndroidManifest.xml provider authority = ${applicationId}.fileprovider
                // res/xml/file_paths.xml 에 external-files-path 정의 필수
                Uri apkUri = FileProvider.getUriForFile(
                    MainActivity.this,
                    getPackageName() + ".fileprovider",
                    apkFile
                );
                installIntent.setDataAndType(apkUri,
                    "application/vnd.android.package-archive");
                installIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                Log.d(TAG, "FileProvider URI: " + apkUri.toString());
            } else {
                // Android 6.0 이하 : 직접 file URI
                installIntent.setDataAndType(
                    Uri.fromFile(apkFile),
                    "application/vnd.android.package-archive"
                );
            }

            startActivity(installIntent);
            Log.d(TAG, "APK 설치 인텐트 실행 완료");

        } catch (Exception e) {
            Log.e(TAG, "APK 설치 실패: " + e.getMessage(), e);
            getBridge().getWebView().post(() ->
                getBridge().getWebView().evaluateJavascript(
                    "if(window.onApkDownloadFailed) window.onApkDownloadFailed();", null)
            );
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (downloadReceiver != null) {
            try { unregisterReceiver(downloadReceiver); } catch (Exception ignored) {}
        }
    }

    // ── 런타임 권한 요청 ─────────────────────────────────────────────────────

    private void requestMissingPermissions() {
        List<String> missing = new ArrayList<>();
        for (String perm : REQUIRED_PERMISSIONS) {
            if (ContextCompat.checkSelfPermission(this, perm)
                    != PackageManager.PERMISSION_GRANTED) {
                missing.add(perm);
            }
        }
        if (!missing.isEmpty()) {
            ActivityCompat.requestPermissions(
                this,
                missing.toArray(new String[0]),
                PERM_REQUEST_CODE
            );
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode,
                                           @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != PERM_REQUEST_CODE) return;
        for (int i = 0; i < permissions.length; i++) {
            boolean granted = (grantResults[i] == PackageManager.PERMISSION_GRANTED);
            Log.d(TAG, "권한 결과: " + permissions[i] + " → " + (granted ? "허용" : "거부"));
        }
    }
}
