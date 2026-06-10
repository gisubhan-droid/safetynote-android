package me.linkmax.safetynote;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.webkit.WebResourceRequest;
import android.webkit.WebView;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.getcapacitor.BridgeActivity;
import com.getcapacitor.BridgeWebViewClient;

import java.util.ArrayList;
import java.util.List;

public class MainActivity extends BridgeActivity {

    private static final String TAG = "SafetyNOTE";
    private static final int PERM_REQUEST_CODE = 100;

    // ── 요청할 권한 목록 ──────────────────────────────────────────────────────
    private static final String[] REQUIRED_PERMISSIONS;
    static {
        List<String> perms = new ArrayList<>();
        perms.add(Manifest.permission.ACCESS_FINE_LOCATION);    // GPS (정밀)
        perms.add(Manifest.permission.ACCESS_COARSE_LOCATION);  // GPS (대략)
        perms.add(Manifest.permission.CAMERA);                  // 카메라
        // Android 13+ : 알림 권한
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            perms.add(Manifest.permission.POST_NOTIFICATIONS);
        }
        REQUIRED_PERMISSIONS = perms.toArray(new String[0]);
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // ── 권한 요청 ─────────────────────────────────────────────────────────
        requestMissingPermissions();

        // ── WebViewClient: BridgeWebViewClient 상속 ───────────────────────────
        // ✅ shouldInterceptRequest → 부모(Bridge)에 위임 → 로컬 에셋 정상 서빙
        // ✅ shouldOverrideUrlLoading → APK 다운로드 / 지도앱 / 외부 스킴 처리
        getBridge().getWebView().setWebViewClient(new BridgeWebViewClient(getBridge()) {

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                String url = request.getUrl().toString();
                Log.d(TAG, "URL 로딩 요청: " + url);

                // ── APK 다운로드 처리 ───────────────────────────────────────
                if (url.endsWith(".apk") || url.contains(".apk?") || url.contains("/apk/")) {
                    Log.d(TAG, "APK 다운로드 감지: " + url);
                    return launchExternalBrowser(url);
                }

                // ── 지도 앱 URL 스킴 처리 ──────────────────────────────────
                if (url.startsWith("tmap://"))     return launchExternalApp(url, "https://tmap.life/");
                if (url.startsWith("kakaomap://")) return launchExternalApp(url, "https://map.kakao.com/");
                if (url.startsWith("nmap://"))     return launchExternalApp(url, "https://map.naver.com/");

                // ── intent:// 스킴 처리 ─────────────────────────────────────
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

                // ── 기타 외부 스킴 (tel, mailto 등) ───────────────────────
                if (!url.startsWith("http://") && !url.startsWith("https://")) {
                    try {
                        startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
                        return true;
                    } catch (Exception e) {
                        Log.e(TAG, "외부 스킴 처리 실패: " + url);
                        return false;
                    }
                }

                // ── http/https → Bridge에 위임 ─────────────────────────────
                return super.shouldOverrideUrlLoading(view, request);
            }

            /** APK · 외부 URL → 시스템 브라우저로 열기 */
            private boolean launchExternalBrowser(String url) {
                try {
                    Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    startActivity(intent);
                    return true;
                } catch (Exception e) {
                    Log.e(TAG, "외부 브라우저 오픈 실패: " + e.getMessage());
                    return false;
                }
            }

            /** 외부 앱 실행 → 미설치 시 웹 URL로 폴백 */
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

    // ── 권한 요청 ─────────────────────────────────────────────────────────────

    /** 미허가 권한만 골라서 한번에 요청 */
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

    /** 권한 요청 결과 콜백 */
    @Override
    public void onRequestPermissionsResult(int requestCode,
                                           @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);

        if (requestCode != PERM_REQUEST_CODE) return;

        for (int i = 0; i < permissions.length; i++) {
            String perm = permissions[i];
            boolean granted = (grantResults[i] == PackageManager.PERMISSION_GRANTED);
            Log.d(TAG, "권한 결과: " + perm + " → " + (granted ? "허용" : "거부"));
        }
        // 거부된 권한이 있어도 앱은 계속 실행 (선택적 권한 처리)
    }
}
