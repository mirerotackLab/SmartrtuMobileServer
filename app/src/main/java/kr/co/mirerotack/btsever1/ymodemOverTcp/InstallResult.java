package kr.co.mirerotack.btsever1.ymodemOverTcp;

// ✅ APK 설치 결과 코드
public enum InstallResult {
    FORCE_UPDATE,            // 0. 강제 업데이트
    DIFFRENT_PACKAGE_NAME,   // 1. 설치된 앱과 APK의 패키지 명이 다름 (제거 후, 설치)
    APP_NOT_INSTALLED,       // 2. 앱이 설치되어 있지 않음
    NEW_VERSION_AVAILABLE,   // 3. 새로운 버전의 APK
    NONE,
}
