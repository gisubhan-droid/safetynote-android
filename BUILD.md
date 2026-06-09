# Safety NOTE Android APK 빌드 가이드

## 사전 준비 (PC에 설치)
1. [Node.js 18+](https://nodejs.org) 설치
2. [Android Studio](https://developer.android.com/studio) 설치
   - Android Studio 설치 시 SDK도 함께 설치됨
   - SDK: Android 14 (API 34) 선택

## 빌드 순서

### 1. 패키지 설치
```bash
npm install
```

### 2. Android 플랫폼 추가
```bash
npx cap add android
```

### 3. 웹 에셋 동기화
```bash
npx cap sync android
```

### 4. APK 빌드
```bash
# Debug APK (테스트용, 서명 불필요)
cd android && ./gradlew assembleDebug

# Release APK (배포용, 키스토어 서명 필요)
cd android && ./gradlew assembleRelease
```

### 5. APK 위치
```
android/app/build/outputs/apk/debug/app-debug.apk
android/app/build/outputs/apk/release/app-release-unsigned.apk
```

## Release APK 서명 (배포용)

### 키스토어 생성 (최초 1회)
```bash
keytool -genkey -v -keystore safetynote.keystore \
  -alias safetynote -keyalg RSA -keysize 2048 -validity 10000
```

### APK 서명
```bash
# zipalign
zipalign -v 4 app-release-unsigned.apk app-release-aligned.apk

# apksigner
apksigner sign --ks safetynote.keystore \
  --ks-key-alias safetynote \
  --out app-release-signed.apk \
  app-release-aligned.apk
```

## 앱 설정 정보
- **앱 ID**: me.linkmax.safetynote
- **앱 이름**: Safety NOTE
- **서버 URL**: https://linkmax.myds.me:3443
- **최소 Android**: 5.1 (API 22)
- **타겟 Android**: 14 (API 34)

## 권한
- 인터넷
- GPS (위치정보)
- 카메라
- 파일 읽기/쓰기
- 알림
