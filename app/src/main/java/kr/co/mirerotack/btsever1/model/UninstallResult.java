package kr.co.mirerotack.btsever1.model;

// ✅ APK 미설치 결과 코드
public enum UninstallResult {
    APK_CORRUPTED,           // 0. APK 손상
    ALREADY_LATEST_VERSION,  // 1. 오래된 버전의 APK
    NONE, DIFFRENT_PACKAGE_NAME_NOT_FORCE, // 2. 서로 다른 패키지 이름 (강제 업데이트 비활성화 시, 설치 안 함)
}