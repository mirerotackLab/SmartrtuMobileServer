package kr.co.mirerotack.btsever1.ymodemServer;

import android.content.Context;

import java.io.File;

import kr.co.mirerotack.btsever1.model.YModemServerInterface;

public class YModemServerFactory {
    public enum ServerType {
        TCP,        // TCP 서버
        BLUETOOTH   // Bluetooth 서버
    }

    /**
     * 서버 타입에 따라 적절한 YModem 서버 인스턴스를 생성합니다
     *
     * @param serverType      생성할 서버 타입
     * @param apkDownloadPath APK 다운로드 경로
     * @param context         애플리케이션 컨텍스트
     * @return 생성된 서버 인스턴스
     */
    public static YModemServerInterface createServer(ServerType serverType, File apkDownloadPath, Context context) {
        switch (serverType) {
            case TCP:
                return new YModemTCPAbstractServerImpl(apkDownloadPath, context);
            case BLUETOOTH:
                return new YModemBluetoothAbstractServerImpl(apkDownloadPath, context);
            default:
                throw new IllegalArgumentException("지원하지 않는 서버 타입입니다: " + serverType);
        }
    }
}
