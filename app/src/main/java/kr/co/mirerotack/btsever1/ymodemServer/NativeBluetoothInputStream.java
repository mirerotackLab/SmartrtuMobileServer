package kr.co.mirerotack.btsever1.ymodemServer;

import java.io.IOException;
import java.io.InputStream;

import kr.co.mirerotack.btsever1.NativeBtServer;

import static kr.co.mirerotack.btsever1.utils.Logger.logMessage;

/**
 * JNI Bluetooth InputStream
 * 타임아웃 및 읽기 상태를 상세히 로깅
 */
public class NativeBluetoothInputStream extends InputStream {
    private static final String TAG = "NativeBTInputStream";

    // 여러 개의 데이터만 읽는 함수
    @Override
    public int read(byte[] b) throws IOException {
        String tag1 = "[ODN-" + b.length +"] JNI read";
        String tag2 = "[XDN-" + b.length +"] JNI read";

        logMessage(tag1 + " - 요청 크기: " + b.length + " bytes");

        // JNI 연결 상태 확인
        if (!NativeBtServer.nativeIsConnected()) {
            logMessage(tag2 + " 실패: 연결이 끊어진 상태");
            throw new IOException("JNI Bluetooth 연결이 끊어짐");
        }

        long startTime = System.currentTimeMillis();
        int result = NativeBtServer.nativeRead(b);     // JNI 호출
        long endTime = System.currentTimeMillis();


        long diffTime = endTime - startTime;
        logMessage(tag1 + " 결과: " + result + " bytes, " +
                "대기시간: " + diffTime / 1000 + "s " + diffTime % 1000 + "ms");

        if (result <= 0) {
            logMessage(tag2 + " 결과가 0 이하임 : " + result);
            return -1; // EOF 또는 오류
        }

        // 수신된 데이터 로깅 (처음 10바이트만)
        StringBuilder hexData = new StringBuilder();
        for (int i = 0; i < Math.min(result, 10); i++) {
            hexData.append(String.format("%02X ", b[i] & 0xFF));
        }
        logMessage(tag1 + " 수신된 데이터 (hex): " + hexData.toString());

        return result;
    }

    // 1개의 데이터만 읽는 함수
    @Override
    public int read() throws IOException {
        logMessage("[D1] JNI read 시작");

        byte[] one = new byte[1];
        int r = read(one);

        if (r <= 0) {
            logMessage("[D1] JNI read 실패: " + r);
            return -1;
        }

        int result = one[0] & 0xFF;
        logMessage("[D1] JNI read 완료: 0x" + String.format("%02X", result) + " (" + result + ")");

        return result;
    }

    @Override
    public int available() throws IOException {
        // This method should be overridden by subclasses.
        // -> 기본 자바 클래스에서 상위 클래스에 구현을 위임함.
        return NativeBtServer.nativeAvailable();
    }
}