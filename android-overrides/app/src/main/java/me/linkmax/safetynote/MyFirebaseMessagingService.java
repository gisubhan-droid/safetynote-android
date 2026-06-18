package me.linkmax.safetynote;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.core.app.NotificationCompat;

import com.google.firebase.messaging.FirebaseMessagingService;
import com.google.firebase.messaging.RemoteMessage;

import org.json.JSONObject;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * FCM 메시지 수신 및 토큰 관리 서비스
 *
 * <역할>
 *  1. onNewToken()  — FCM 토큰 갱신 시 서버에 자동 등록 (POST /api/push/register)
 *  2. onMessageReceived() — 포그라운드 수신 시 직접 알림 표시
 *     (백그라운드 / 종료 상태: OS가 알림 자동 표시)
 *
 * <알림 채널>
 *  - 채널 ID : safetynote_push
 *  - 채널명  : SafetyNOTE 알림
 *  - 중요도  : HIGH (헤드업 알림)
 *
 * <서버 연동>
 *  - 토큰 등록 API : POST {서버URL}/api/push/register  { fcm_token }
 *  - 인증 헤더    : Authorization: Bearer {저장된 JWT}
 *  - SharedPreferences에서 JWT 토큰을 읽어 사용
 */
public class MyFirebaseMessagingService extends FirebaseMessagingService {

    private static final String TAG = "FCMService";

    /** 알림 채널 ID — fcm.ts의 channel_id와 반드시 일치 */
    public static final String CHANNEL_ID = "safetynote_push";

    /** SharedPreferences 파일명 / 키 — app.js의 localStorage와 맞춤 */
    private static final String PREFS_NAME  = "SafetyNotePrefs";
    private static final String KEY_JWT     = "authToken";      // 로그인 시 저장한 JWT
    private static final String KEY_SERVER  = "serverUrl";     // NAS 서버 URL

    // ─────────────────────────────────────────────────────────────────────────
    // FCM 토큰 갱신 — 앱 설치 직후, 토큰 만료 갱신 시 호출
    // ─────────────────────────────────────────────────────────────────────────
    @Override
    public void onNewToken(@NonNull String token) {
        super.onNewToken(token);
        Log.d(TAG, "FCM 토큰 갱신: " + token.substring(0, Math.min(20, token.length())) + "...");
        // 서버에 새 토큰 등록 (백그라운드 스레드)
        new Thread(() -> registerTokenToServer(token)).start();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // FCM 메시지 수신 — 포그라운드 상태에서 호출됨
    // 백그라운드/종료 상태에서는 OS가 자동으로 알림 표시
    // ─────────────────────────────────────────────────────────────────────────
    @Override
    public void onMessageReceived(@NonNull RemoteMessage remoteMessage) {
        super.onMessageReceived(remoteMessage);
        Log.d(TAG, "FCM 수신: from=" + remoteMessage.getFrom());

        String title = null;
        String body  = null;

        // notification 필드
        if (remoteMessage.getNotification() != null) {
            title = remoteMessage.getNotification().getTitle();
            body  = remoteMessage.getNotification().getBody();
        }

        // data 필드 (notification 없거나 data-only 메시지)
        if (title == null && remoteMessage.getData().containsKey("title")) {
            title = remoteMessage.getData().get("title");
        }
        if (body == null && remoteMessage.getData().containsKey("body")) {
            body = remoteMessage.getData().get("body");
        }

        if (title == null) title = "SafetyNOTE";
        if (body  == null) body  = "";

        Log.d(TAG, "알림 표시: " + title + " / " + body);
        showNotification(title, body);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 알림 표시
    // ─────────────────────────────────────────────────────────────────────────
    private void showNotification(String title, String body) {
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;

        // Android 8.0+ 채널 생성 (이미 있으면 무시)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                "SafetyNOTE 알림",
                NotificationManager.IMPORTANCE_HIGH
            );
            channel.setDescription("결재요청, 서명요청 등 업무 알림");
            channel.enableVibration(true);
            nm.createNotificationChannel(channel);
        }

        // 탭 시 앱 실행
        Intent tapIntent = new Intent(this, MainActivity.class);
        tapIntent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent pendingIntent = PendingIntent.getActivity(
            this, 0, tapIntent,
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                ? PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT
                : PendingIntent.FLAG_UPDATE_CURRENT
        );

        // 아이콘: mipmap/ic_launcher 사용 (별도 알림 아이콘 없을 경우)
        int iconRes = getApplicationInfo().icon;

        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(iconRes)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(new NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent);

        nm.notify((int) System.currentTimeMillis(), builder.build());
    }

    // ─────────────────────────────────────────────────────────────────────────
    // FCM 토큰을 서버에 등록 (POST /api/push/register)
    //
    // SharedPreferences에서 JWT + 서버 URL을 읽어 API 호출
    // - JWT가 없으면 (미로그인 상태) 등록 생략 (로그인 후 재발급 시 재시도)
    // - 실패해도 앱 동작에 영향 없음 (조용히 로그만 기록)
    // ─────────────────────────────────────────────────────────────────────────
    private void registerTokenToServer(String fcmToken) {
        try {
            // SharedPreferences에서 JWT와 서버 URL 읽기
            android.content.SharedPreferences prefs =
                getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            String jwt       = prefs.getString(KEY_JWT, null);
            String serverUrl = prefs.getString(KEY_SERVER, null);

            if (jwt == null || jwt.isEmpty()) {
                Log.d(TAG, "JWT 없음 — 로그인 후 토큰 등록 예정");
                return;
            }

            if (serverUrl == null || serverUrl.isEmpty()) {
                // 기본 서버 URL (app.js의 API_BASE와 맞춤)
                serverUrl = "https://linkmax.myds.me:3443";
            }

            String apiUrl = serverUrl.replaceAll("/+$", "") + "/api/push/register";
            Log.d(TAG, "토큰 등록 API: " + apiUrl);

            JSONObject jsonBody = new JSONObject();
            jsonBody.put("fcm_token", fcmToken);
            byte[] postData = jsonBody.toString().getBytes(StandardCharsets.UTF_8);

            URL url = new URL(apiUrl);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setRequestProperty("Authorization", "Bearer " + jwt);
            conn.setRequestProperty("Content-Length", String.valueOf(postData.length));
            conn.setDoOutput(true);
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(10000);

            // NAS 자체서명 인증서 허용 (HttpsURLConnection에만 필요 — 아래 주석 참조)
            // 실제 자체서명 인증서 환경에서는 javax.net.ssl.HttpsURLConnection 형변환 후
            // setSSLSocketFactory / setHostnameVerifier 설정 필요.
            // 현재는 AndroidManifest usesCleartextTraffic=true 로 http 폴백 처리.

            try (OutputStream os = conn.getOutputStream()) {
                os.write(postData);
            }

            int code = conn.getResponseCode();
            Log.d(TAG, "토큰 등록 응답: HTTP " + code);

            if (code == 200) {
                Log.i(TAG, "✅ FCM 토큰 서버 등록 완료");
            } else {
                Log.w(TAG, "⚠️ 토큰 등록 실패: HTTP " + code);
            }

        } catch (Exception e) {
            Log.e(TAG, "토큰 등록 중 오류: " + e.getMessage());
        }
    }
}
