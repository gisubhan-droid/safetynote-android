package me.linkmax.safetynote;

import android.Manifest;
import android.app.DownloadManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.webkit.JavascriptInterface;
import android.webkit.MimeTypeMap;
import android.webkit.WebResourceRequest;
import android.webkit.WebView;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.core.app.ActivityCompat;
import androidx.core.app.NotificationCompat;
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

    // SharedPreferences — MyFirebaseMessagingService 와 동일한 파일명/키
    private static final String PREFS_NAME = "SafetyNotePrefs";
    private static final String KEY_JWT    = "authToken";
    private static final String KEY_SERVER = "serverUrl";

    // APK 다운로드 추적
    private long apkDownloadId = -1;
    private BroadcastReceiver downloadReceiver;

    // 첨부파일 다운로드 추적 (BUG-011: 첨부파일 외부 앱으로 열기)
    private long attachDownloadId = -1;
    private String attachFileName  = "";
    private String attachMimeType  = "";

    // 알림 채널 (첨부파일 열기 안내용)
    private static final String ATTACH_NOTIF_CHANNEL = "attach_open";
    private static final int    ATTACH_NOTIF_ID      = 2001;

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

        // ── JS↔Java 브릿지 등록 ──────────────────────────────────────────────
        // window.SafetyNoteApp.saveAuthToken(token)  — 로그인 시 JWT 저장
        // window.SafetyNoteApp.clearAuthToken()      — 로그아웃 시 JWT 삭제
        // window.SafetyNoteApp.saveServerUrl(url)    — 서버 URL 저장 (www/index.html 연동)
        // MyFirebaseMessagingService 가 SharedPreferences("SafetyNotePrefs")에서
        // "authToken" / "serverUrl" 을 읽어 FCM 토큰 서버 등록에 사용.
        getBridge().getWebView().addJavascriptInterface(
            new SafetyNoteAppBridge(), "SafetyNoteApp"
        );
        Log.d(TAG, "JS 브릿지 등록 완료: window.SafetyNoteApp");

        // ── DownloadManager 완료 수신기 등록 ────────────────────────────────
        downloadReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                long id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1);
                if (id == apkDownloadId && id != -1) {
                    Log.d(TAG, "APK 다운로드 완료: id=" + id);
                    installDownloadedApk(id);
                } else if (id == attachDownloadId && id != -1) {
                    // BUG-011: 첨부파일 다운로드 완료 → 외부 앱으로 열기
                    Log.d(TAG, "첨부파일 다운로드 완료: id=" + id);
                    openDownloadedFile(id);
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

        // 첨부파일 열기 알림 채널 생성 (Android 8.0+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(
                ATTACH_NOTIF_CHANNEL, "첨부파일 열기",
                NotificationManager.IMPORTANCE_HIGH);
            ch.setDescription("첨부파일 다운로드 완료 후 열기 안내");
            NotificationManager nm =
                (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) nm.createNotificationChannel(ch);
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

                // ── BUG-011: 첨부파일 다운로드 → 외부 앱으로 열기 ─────────
                // /api/attachments/{id}/download 패턴 감지
                if (url.contains("/api/attachments/") && url.contains("/download")) {
                    Log.d(TAG, "첨부파일 다운로드 요청: " + url);
                    openAttachmentExternally(url);
                    return true;
                }

                // ── 지도 앱 URL 스킴 ────────────────────────────────────────
                if (url.startsWith("tmap://"))     return launchExternalApp(url, "https://tmap.life/");
                if (url.startsWith("kakaomap://")) return launchExternalApp(url, "https://map.kakao.com/");
                // BUG-012: nmap:// → 네이버지도 앱 또는 시스템 브라우저로 열기
                if (url.startsWith("nmap://"))     return launchNaverMap(url);

                // BUG-012: 네이버지도 웹 URL → 시스템 브라우저 강제 실행
                // https://map.naver.com/ 이 WebView 내부에서 열리면 SafetyNOTE 복귀 불가
                if (url.startsWith("https://map.naver.com/") || url.startsWith("http://map.naver.com/")) {
                    Log.d(TAG, "네이버지도 웹 URL 감지 → 시스템 브라우저 강제 실행: " + url);
                    return launchInSystemBrowser(url);
                }

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

    // ── BUG-012: 네이버지도 앱 또는 시스템 브라우저 실행 ────────────────────

    /**
     * nmap:// 스킴 처리
     * - 네이버지도 앱 설치됨 → 앱 직접 실행
     * - 미설치 → nmap URL에서 웹 URL 추출 후 시스템 브라우저로 강제 실행
     *   (WebView 내부 열기 방지 → SafetyNOTE 복귀 가능)
     */
    private boolean launchNaverMap(String nmapUrl) {
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(nmapUrl));
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
            Log.d(TAG, "네이버지도 앱 실행: " + nmapUrl);
            return true;
        } catch (Exception e) {
            // 네이버지도 미설치 → 웹 URL 추출 후 시스템 브라우저로
            Log.d(TAG, "네이버지도 미설치 → 웹 폴백: " + nmapUrl);
            String webUrl = extractNaverWebUrl(nmapUrl);
            return launchInSystemBrowser(webUrl);
        }
    }

    /**
     * nmap:// URL에서 검색어를 추출하여 네이버지도 웹 URL로 변환
     * 예) nmap://search?query=현장명&appname=me.linkmax.safetynote
     *   → https://map.naver.com/p/search/현장명
     */
    private String extractNaverWebUrl(String nmapUrl) {
        try {
            Uri uri = Uri.parse(nmapUrl);
            String query = uri.getQueryParameter("query");
            if (query != null && !query.isEmpty()) {
                return "https://map.naver.com/p/search/" + Uri.encode(query);
            }
            // lng/lat 좌표 방식
            String lng = uri.getQueryParameter("lng");
            String lat = uri.getQueryParameter("lat");
            if (lng != null && lat != null) {
                return "https://map.naver.com/p/search/" + lat + "," + lng;
            }
        } catch (Exception e) {
            Log.w(TAG, "nmap URL 파싱 실패: " + e.getMessage());
        }
        return "https://map.naver.com/";
    }

    /**
     * 시스템 브라우저(또는 외부 앱) 강제 실행
     * Intent.createChooser() 사용 → WebView가 핸들러 등록 방지
     * WebView 내부에서 열리지 않으므로 SafetyNOTE 복귀 가능
     */
    private boolean launchInSystemBrowser(String url) {
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            Intent chooser = Intent.createChooser(intent, "브라우저 선택");
            chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(chooser);
            Log.d(TAG, "시스템 브라우저 강제 실행: " + url);
            return true;
        } catch (Exception e) {
            Log.e(TAG, "시스템 브라우저 실행 실패: " + e.getMessage());
            return false;
        }
    }

    // ── BUG-011: 첨부파일 다운로드 후 외부 앱으로 열기 ─────────────────────

    /**
     * /api/attachments/{id}/download URL을 DownloadManager로 다운로드 후
     * 외부 앱(PDF 뷰어, 이미지 앱 등)으로 열기
     *
     * ✅ 자체서명 인증서 대응: https → http 변환
     * ✅ 파일명: URL의 filename 쿼리 파라미터 우선 사용
     * ✅ MIME: 파일 확장자 기반 자동 감지 (MimeTypeMap)
     */
    private void openAttachmentExternally(String url) {
        try {
            // filename 쿼리 파라미터에서 파일명 추출
            Uri uri = Uri.parse(url);
            String rawName = uri.getQueryParameter("filename");
            if (rawName == null || rawName.isEmpty()) {
                // URL 경로 마지막 세그먼트에서 파일명 추출
                String path = uri.getPath();
                if (path != null && path.contains("/")) {
                    String lastSeg = path.substring(path.lastIndexOf('/') + 1);
                    rawName = lastSeg.isEmpty() ? "attachment" : lastSeg;
                } else {
                    rawName = "attachment";
                }
            }
            final String fileName = rawName;

            // 파일 확장자로 MIME 타입 결정
            String ext = "";
            int dotIdx = fileName.lastIndexOf('.');
            if (dotIdx >= 0) ext = fileName.substring(dotIdx + 1).toLowerCase();
            String mimeType = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext);
            if (mimeType == null || mimeType.isEmpty()) mimeType = "application/octet-stream";
            attachMimeType = mimeType;
            attachFileName = fileName;

            // 기존 동일 파일 삭제 (재다운로드 충돌 방지)
            File destFile = new File(
                getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS),
                fileName
            );
            if (destFile.exists()) destFile.delete();

            // ✅ 자체서명 인증서 대응: NAS HTTPS → HTTP 내부 포트 변환
            // - NAS: HTTPS 포트(3443)만 외부 노출, HTTP 포트(3444)는 내부 전용
            // - DownloadManager는 WebView SSL 예외를 공유하지 않으므로 자체서명 인증서 신뢰 불가
            // - 해결: https://...:3443 → http://...:3444 로 변환 (FCM 등록과 동일 방식)
            // - GitHub 등 외부 공인 인증서 서버: https 그대로 유지
            String downloadUrl = url;
            if (downloadUrl.startsWith("https://")) {
                boolean isExternalTrustedHost =
                    downloadUrl.contains("github.com") ||
                    downloadUrl.contains("githubusercontent.com");
                if (!isExternalTrustedHost) {
                    downloadUrl = "http://" + downloadUrl.substring(8);
                    // HTTPS 전용 포트(3443) → HTTP 내부 포트(3444) 변환
                    downloadUrl = downloadUrl.replaceAll(":3443(/|\\?|$)", ":3444$1");
                    Log.d(TAG, "첨부파일: NAS URL 변환 → " + downloadUrl);
                }
            }

            DownloadManager.Request request = new DownloadManager.Request(Uri.parse(downloadUrl));
            request.setTitle(fileName);
            request.setDescription("첨부파일 다운로드 중...");
            request.setNotificationVisibility(
                DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
            request.setDestinationUri(Uri.fromFile(destFile));
            request.setAllowedOverMetered(true);
            request.setAllowedOverRoaming(true);

            // ✅ Authorization 헤더 추가: URL의 token 파라미터를 헤더로도 전달
            // - 서버는 헤더 우선, 쿼리 파라미터 폴백 방식으로 인증 처리
            // - DownloadManager가 HTTP 3444로 요청 시 헤더 인증도 함께 지원
            Uri parsedUri = Uri.parse(downloadUrl);
            String tokenParam = parsedUri.getQueryParameter("token");
            if (tokenParam != null && !tokenParam.isEmpty()) {
                request.addRequestHeader("Authorization", "Bearer " + tokenParam);
                Log.d(TAG, "첨부파일 DownloadManager: Authorization 헤더 추가");
            }

            DownloadManager dm = (DownloadManager) getSystemService(Context.DOWNLOAD_SERVICE);
            attachDownloadId = dm.enqueue(request);
            Log.d(TAG, "첨부파일 다운로드 시작: id=" + attachDownloadId
                + " file=" + fileName + " mime=" + mimeType);

        } catch (Exception e) {
            Log.e(TAG, "첨부파일 다운로드 시작 실패: " + e.getMessage());
        }
    }

    /**
     * DownloadManager 완료 후 파일을 외부 앱으로 열기
     *
     * ✅ Android 7.0+  : FileProvider URI 사용
     * ✅ Android 10+   : 백그라운드 Activity 시작 제한 우회
     *    - BroadcastReceiver 콜백은 백그라운드 컨텍스트
     *    - runOnUiThread 로 메인 스레드 이동 후 startActivity
     *    - 앱이 포그라운드면 즉시 열기, 백그라운드면 알림으로 안내
     */
    private void openDownloadedFile(long downloadId) {
        try {
            DownloadManager dm = (DownloadManager) getSystemService(Context.DOWNLOAD_SERVICE);
            DownloadManager.Query query = new DownloadManager.Query();
            query.setFilterById(downloadId);

            android.database.Cursor cursor = dm.query(query);
            if (cursor == null || !cursor.moveToFirst()) {
                Log.e(TAG, "첨부파일 DownloadManager 쿼리 실패");
                if (cursor != null) cursor.close();
                return;
            }

            int statusCol = cursor.getColumnIndex(DownloadManager.COLUMN_STATUS);
            int status = (statusCol >= 0) ? cursor.getInt(statusCol) : -1;
            cursor.close();

            if (status != DownloadManager.STATUS_SUCCESSFUL) {
                Log.e(TAG, "첨부파일 다운로드 실패 status=" + status);
                return;
            }

            File file = new File(
                getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS),
                attachFileName
            );

            if (!file.exists()) {
                Log.e(TAG, "첨부파일 없음: " + file.getAbsolutePath());
                return;
            }

            Log.d(TAG, "첨부파일 열기 준비: " + file.getAbsolutePath()
                + " mime=" + attachMimeType);

            // FileProvider URI 생성 (Android 7.0+)
            final Uri fileUri;
            final String mime = attachMimeType;
            final String fname = attachFileName;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                fileUri = FileProvider.getUriForFile(
                    MainActivity.this,
                    getPackageName() + ".fileprovider",
                    file
                );
            } else {
                fileUri = Uri.fromFile(file);
            }

            // ✅ 메인 스레드에서 실행 (Android 10+ 백그라운드 Activity 시작 제한 우회)
            runOnUiThread(() -> {
                try {
                    Intent viewIntent = new Intent(Intent.ACTION_VIEW);
                    viewIntent.setDataAndType(fileUri, mime);
                    viewIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    viewIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);

                    Intent chooser = Intent.createChooser(viewIntent, "" + fname + " 열기");
                    chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);

                    // 앱이 포그라운드면 즉시 startActivity 시도
                    try {
                        startActivity(chooser);
                        Log.d(TAG, "첨부파일 외부 앱 직접 실행 완료");
                    } catch (Exception e1) {
                        // 백그라운드 제한으로 실패 시 → 알림(Notification)으로 안내
                        Log.w(TAG, "직접 실행 실패, 알림 방식으로 전환: " + e1.getMessage());
                        showOpenFileNotification(fileUri, mime, fname);
                    }
                } catch (Exception e) {
                    Log.e(TAG, "첨부파일 열기 실패(UI스레드): " + e.getMessage(), e);
                    showOpenFileNotification(fileUri, mime, fname);
                }
            });

            // 다운로드 ID 초기화
            attachDownloadId = -1;

        } catch (Exception e) {
            Log.e(TAG, "첨부파일 열기 실패: " + e.getMessage(), e);
        }
    }

    /**
     * 파일 열기 알림 표시
     * - Android 10+ 백그라운드 Activity 제한 시 폴백
     * - 알림 탭 → 파일 뷰어 앱 실행
     */
    private void showOpenFileNotification(Uri fileUri, String mime, String fileName) {
        try {
            Intent viewIntent = new Intent(Intent.ACTION_VIEW);
            viewIntent.setDataAndType(fileUri, mime);
            viewIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            viewIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            Intent chooser = Intent.createChooser(viewIntent, fileName + " 열기");
            chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);

            int flags = PendingIntent.FLAG_UPDATE_CURRENT;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                flags |= PendingIntent.FLAG_IMMUTABLE;
            }
            PendingIntent pi = PendingIntent.getActivity(
                this, ATTACH_NOTIF_ID, chooser, flags);

            NotificationCompat.Builder builder =
                new NotificationCompat.Builder(this, ATTACH_NOTIF_CHANNEL)
                    .setSmallIcon(android.R.drawable.ic_menu_view)
                    .setContentTitle("첨부파일 다운로드 완료")
                    .setContentText(fileName + " — 탭하여 열기")
                    .setPriority(NotificationCompat.PRIORITY_HIGH)
                    .setAutoCancel(true)
                    .setContentIntent(pi);

            NotificationManager nm =
                (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) nm.notify(ATTACH_NOTIF_ID, builder.build());
            Log.d(TAG, "첨부파일 열기 알림 표시: " + fileName);
        } catch (Exception e) {
            Log.e(TAG, "알림 표시 실패: " + e.getMessage(), e);
        }
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

            // ✅ 자체서명 인증서 대응: NAS URL만 https → http 변환
            // - NAS(자체서명 인증서): DownloadManager에서 SSL 오류 → http 변환 필요
            // - GitHub/외부 공인 인증서 서버: https 그대로 유지 (http로 변환 시 차단됨)
            // NAS 판별: github.com, githubusercontent.com 등 외부 도메인 제외
            String downloadUrl = url;
            if (downloadUrl.startsWith("https://")) {
                boolean isExternalTrustedHost =
                    downloadUrl.contains("github.com") ||
                    downloadUrl.contains("githubusercontent.com") ||
                    downloadUrl.contains("objects.githubusercontent.com") ||
                    downloadUrl.contains("releases.githubusercontent.com");
                if (!isExternalTrustedHost) {
                    // NAS 자체서명 인증서 대응: https → http 변환
                    downloadUrl = "http://" + downloadUrl.substring(8);
                    // HTTPS 전용 포트(3443) → HTTP 내부 포트(3444) 변환 (FCM 등록과 동일)
                    downloadUrl = downloadUrl.replaceAll(":3443(/|\\?|$)", ":3444$1");
                    Log.d(TAG, "NAS URL 변환(APK): " + downloadUrl);
                } else {
                    Log.d(TAG, "외부 공인 서버(GitHub 등): https 유지: " + downloadUrl);
                }
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

            // ✅ NAS URL인 경우 Authorization 헤더 추가 (JWT 인증)
            // APK 다운로드 URL이 /api/dist/apk/download 형태인 경우 인증 필요
            boolean isNasUrl = !downloadUrl.contains("github.com") &&
                               !downloadUrl.contains("githubusercontent.com");
            if (isNasUrl) {
                android.content.SharedPreferences prefs =
                    getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
                String jwt = prefs.getString(KEY_JWT, null);
                if (jwt != null && !jwt.isEmpty()) {
                    request.addRequestHeader("Authorization", "Bearer " + jwt);
                    Log.d(TAG, "APK DownloadManager: Authorization 헤더 추가");
                }
            }

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
    public void onDestroy() {
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

    // ── JS↔Java 브릿지 내부 클래스 ──────────────────────────────────────────
    //
    // app.js(WebView) → window.SafetyNoteApp.XXX() 호출
    //   → SharedPreferences("SafetyNotePrefs") 읽기/쓰기
    //   → MyFirebaseMessagingService 가 동일 Prefs 에서 JWT + serverUrl 을 읽어
    //     FCM 토큰을 서버에 등록
    //
    // ⚠️  @JavascriptInterface 는 별도 스레드에서 호출될 수 있으므로
    //     UI 조작 없이 SharedPreferences 쓰기만 수행 — 스레드 안전
    private class SafetyNoteAppBridge {

        /**
         * 로그인 성공 시 호출 — JWT 를 SharedPreferences 에 저장
         *
         * JS: window.SafetyNoteApp.saveAuthToken(token)
         *
         * 저장 후 FCM 토큰을 즉시 서버에 등록 시도
         * (onNewToken 이 이미 발생했지만 JWT 없어 생략된 경우를 보완)
         */
        @JavascriptInterface
        public void saveAuthToken(String token) {
            if (token == null || token.isEmpty()) {
                Log.w(TAG, "saveAuthToken: 빈 토큰 — 무시");
                return;
            }
            SharedPreferences prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            prefs.edit().putString(KEY_JWT, token).apply();
            Log.d(TAG, "saveAuthToken: JWT 저장 완료 (앞 20자: "
                + token.substring(0, Math.min(20, token.length())) + "...)");

            // FCM 토큰 즉시 재등록 시도
            // onNewToken 이 JWT 없이 호출됐을 때를 위한 보완 처리
            com.google.firebase.messaging.FirebaseMessaging.getInstance()
                .getToken()
                .addOnSuccessListener(fcmToken -> {
                    Log.d(TAG, "FCM 토큰 재등록 시도 (로그인 직후)");
                    new Thread(() -> {
                        // MyFirebaseMessagingService 와 동일한 등록 로직 실행
                        triggerFcmRegistration(fcmToken);
                    }).start();
                })
                .addOnFailureListener(e ->
                    Log.w(TAG, "FCM 토큰 가져오기 실패: " + e.getMessage())
                );
        }

        /**
         * 로그아웃 시 호출 — JWT 를 SharedPreferences 에서 삭제
         *
         * JS: window.SafetyNoteApp.clearAuthToken()
         */
        @JavascriptInterface
        public void clearAuthToken() {
            SharedPreferences prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            prefs.edit().remove(KEY_JWT).apply();
            Log.d(TAG, "clearAuthToken: JWT 삭제 완료");
        }

        /**
         * 서버 URL 저장 — www/index.html 의 doConnect() 에서 호출 (선택적)
         * MyFirebaseMessagingService 가 serverUrl 을 읽어 API 엔드포인트 결정
         *
         * JS: window.SafetyNoteApp.saveServerUrl(url)
         */
        @JavascriptInterface
        public void saveServerUrl(String url) {
            if (url == null || url.isEmpty()) return;
            SharedPreferences prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            prefs.edit().putString(KEY_SERVER, url).apply();
            Log.d(TAG, "saveServerUrl: " + url);
        }

        /**
         * APK 직접 다운로드 — DownloadManager 로 즉시 처리
         *
         * [BUG-010-2 Fix]
         * window.open(url, '_system') 은 Capacitor 6 에서 shouldOverrideUrlLoading 을
         * 트리거하지 않는 경우가 있음. 특히 apk_url 이 /api/dist/apk/download 처럼
         * .apk 확장자로 끝나지 않는 내부 API 경로이면 URL 감지 조건도 미충족.
         *
         * 해결: JS 에서 window.SafetyNoteApp.downloadApk(url) 직접 호출
         *       → UI 스레드에서 startApkDownload() 실행 (DownloadManager)
         *
         * JS: window.SafetyNoteApp.downloadApk(url)
         */
        @JavascriptInterface
        public void downloadApk(String apkUrl) {
            if (apkUrl == null || apkUrl.isEmpty()) {
                Log.w(TAG, "downloadApk: 빈 URL — 무시");
                return;
            }
            Log.d(TAG, "downloadApk 브릿지 호출: " + apkUrl);
            // @JavascriptInterface 는 백그라운드 스레드 — DownloadManager 는 메인 스레드 필요
            runOnUiThread(() -> startApkDownload(apkUrl));
        }

        /**
         * 첨부파일 외부 앱으로 열기 — JS→Java 직접 브릿지
         *
         * [BUG-014 Fix]
         * window.open(url, '_system') 은 Capacitor 6 에서 shouldOverrideUrlLoading 을
         * 트리거하지 않아 첨부파일 다운로드가 동작하지 않음.
         * → JS 에서 window.SafetyNoteApp.openAttachment(url, fileName) 직접 호출
         * → UI 스레드에서 openAttachmentExternally() 실행 (DownloadManager)
         *
         * JS: window.SafetyNoteApp.openAttachment(absoluteUrl, fileName)
         *
         * @param attachUrl  절대 URL (https://서버/api/attachments/{id}/download?token=...)
         * @param fileName   파일명 (확장자 포함, MIME 감지 및 저장에 사용)
         */
        @JavascriptInterface
        public void openAttachment(String attachUrl, String fileName) {
            if (attachUrl == null || attachUrl.isEmpty()) {
                Log.w(TAG, "openAttachment: 빈 URL — 무시");
                return;
            }
            // fileName 이 null 이면 URL 에서 추출
            final String safeName = (fileName != null && !fileName.isEmpty())
                ? fileName : "attachment";
            Log.d(TAG, "openAttachment 브릿지 호출: url=" + attachUrl + " file=" + safeName);

            // @JavascriptInterface 는 백그라운드 스레드 → 메인 스레드에서 실행
            runOnUiThread(() -> {
                // attachFileName 을 미리 세팅 (openAttachmentExternally 내부에서도 덮어쓰지만
                // 혹시 URL 파싱이 실패할 경우 대비)
                attachFileName = safeName;
                openAttachmentExternally(attachUrl);
            });
        }
    }

    // ── FCM 토큰 서버 등록 (로그인 직후 보완용) ──────────────────────────────
    //
    // [BUG-010-1 Fix] https → http 폴백 추가:
    //   NAS 자체서명 인증서 환경에서 HttpURLConnection(HTTPS)은 SSL 오류 발생.
    //   AndroidManifest usesCleartextTraffic=true + http 로 변환하여 호출.
    private void triggerFcmRegistration(String fcmToken) {
        try {
            android.content.SharedPreferences prefs =
                getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            String jwt       = prefs.getString(KEY_JWT, null);
            String serverUrl = prefs.getString(KEY_SERVER, null);

            if (jwt == null || jwt.isEmpty()) {
                Log.w(TAG, "triggerFcmRegistration: JWT 없음 — 등록 생략");
                return;
            }
            if (serverUrl == null || serverUrl.isEmpty()) {
                serverUrl = "https://linkmax.myds.me:3443";
            }

            // ✅ [BUG-010-1 Fix v2] 자체서명 인증서 + HTTPS 전용 포트 대응
            // HttpURLConnection 은 WebView 와 달리 SSL 예외를 공유하지 않으므로
            // 자체서명 인증서에서 SSLHandshakeException 발생.
            // NAS 서버는 HTTPS(3443) 포트만 리슨하므로 http://...:3443 은 빈 응답.
            // 해결: https→http 변환 + 포트 3443→3444 (HTTP 전용 내부 포트).
            // AndroidManifest usesCleartextTraffic=true 설정으로 http 허용됨.
            String effectiveUrl = serverUrl;
            if (effectiveUrl.startsWith("https://")) {
                effectiveUrl = "http://" + effectiveUrl.substring(8);
                Log.d(TAG, "FCM 등록: https→http 변환 (자체서명 인증서 대응)");
            }
            // HTTPS 포트(3443) → HTTP 내부 포트(3444) 변환
            effectiveUrl = effectiveUrl.replaceAll(":3443(/|$)", ":3444$1");
            Log.d(TAG, "FCM 등록: 최종 서버 URL = " + effectiveUrl);

            String apiUrl = effectiveUrl.replaceAll("/+$", "") + "/api/push/register";
            Log.d(TAG, "FCM 토큰 등록 API (로그인 후): " + apiUrl);

            org.json.JSONObject body = new org.json.JSONObject();
            body.put("fcm_token", fcmToken);
            byte[] postData = body.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);

            java.net.URL url = new java.net.URL(apiUrl);
            java.net.HttpURLConnection conn = (java.net.HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setRequestProperty("Authorization", "Bearer " + jwt);
            conn.setRequestProperty("Content-Length", String.valueOf(postData.length));
            conn.setDoOutput(true);
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(10000);

            try (java.io.OutputStream os = conn.getOutputStream()) {
                os.write(postData);
            }

            int code = conn.getResponseCode();
            Log.d(TAG, "FCM 토큰 등록 응답 (로그인 후): HTTP " + code);
            if (code == 200) {
                Log.i(TAG, "✅ FCM 토큰 서버 등록 완료 (로그인 후 즉시)");
            } else {
                Log.w(TAG, "⚠️ FCM 토큰 등록 실패: HTTP " + code);
            }
        } catch (Exception e) {
            Log.e(TAG, "triggerFcmRegistration 오류: " + e.getMessage());
        }
    }
}
