package me.linkmax.safetynote;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.webkit.WebResourceRequest;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.util.Log;

import com.getcapacitor.BridgeActivity;

public class MainActivity extends BridgeActivity {

    private static final String TAG = "SafetyNOTE";

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // WebView에서 외부 앱(지도 앱, APK 다운로드 등) 실행 허용 설정
        getBridge().getWebView().setWebViewClient(new WebViewClient() {

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                String url = request.getUrl().toString();
                Log.d(TAG, "URL 로딩 요청: " + url);

                // ── APK 다운로드 처리 ────────────────────────────────────────
                // window.open(url, '_system') 호출 시 Capacitor가 새 WebView를 열려하면
                // shouldOverrideUrlLoading에서 .apk URL을 캐치하여 다운로드 처리
                if (url.endsWith(".apk") || url.contains(".apk?") || url.contains("/apk/")) {
                    Log.d(TAG, "APK 다운로드 감지: " + url);
                    return launchExternalBrowser(url);
                }

                // ── 지도 앱 URL 스킴 처리 ──────────────────────────────────
                // T맵
                if (url.startsWith("tmap://")) {
                    return launchExternalApp(url, "https://tmap.life/");
                }
                // 카카오맵
                if (url.startsWith("kakaomap://")) {
                    return launchExternalApp(url, "https://map.kakao.com/");
                }
                // 네이버지도
                if (url.startsWith("nmap://")) {
                    return launchExternalApp(url, "https://map.naver.com/");
                }

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

                // ── 기타 외부 스킴 처리 (tel, mailto 등) ────────────────────
                if (!url.startsWith("http://") && !url.startsWith("https://")) {
                    try {
                        Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
                        startActivity(intent);
                        return true;
                    } catch (Exception e) {
                        Log.e(TAG, "외부 스킴 처리 실패: " + url);
                        return false;
                    }
                }

                // http/https는 WebView에서 정상 로드
                return false;
            }

            /**
             * APK 또는 외부 URL → 시스템 브라우저(크롬 등)에서 열기
             * 시스템 브라우저가 안드로이드 다운로드 매니저에 전달
             */
            private boolean launchExternalBrowser(String url) {
                try {
                    Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
                    // 시스템 기본 브라우저(크롬)로 강제 오픈
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    startActivity(intent);
                    Log.d(TAG, "외부 브라우저로 오픈: " + url);
                    return true;
                } catch (Exception e) {
                    Log.e(TAG, "외부 브라우저 오픈 실패: " + e.getMessage());
                    return false;
                }
            }

            /**
             * 외부 앱 실행 시도 → 앱 미설치 시 웹 URL로 폴백
             */
            private boolean launchExternalApp(String appUrl, String webFallbackUrl) {
                try {
                    Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(appUrl));
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    startActivity(intent);
                    Log.d(TAG, "외부 앱 실행 성공: " + appUrl);
                    return true;
                } catch (Exception e) {
                    Log.d(TAG, "앱 미설치, 웹으로 대체: " + webFallbackUrl);
                    try {
                        Intent webIntent = new Intent(Intent.ACTION_VIEW,
                                Uri.parse(webFallbackUrl));
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
}
