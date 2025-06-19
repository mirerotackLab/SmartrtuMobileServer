package kr.co.mirerotack.btsever1.ymodemServer;

import android.content.Context;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

import kr.co.mirerotack.btsever1.NativeBtServer;
import kr.co.mirerotack.btsever1.model.NativeBTStatusListener;

import static kr.co.mirerotack.btsever1.utils.Logger.logMessage;

/**
 * JNI RFCOMM Socket을 사용하는 Bluetooth 서버 구현체 (트리거 포함)
 * 기존에 만들어진 NativeBluetoothInputStream/OutputStream을 활용
 */
public class YModemBluetoothAbstractServerImpl extends YModemAbstractServer implements NativeBTStatusListener {
    private static final String TAG = "YModemBluetoothJniServer";

    // JNI 연결 상태 관리용 필드들
    private volatile boolean isClientConnected = false; // 클라이언트 연결 상태 (volatile로 스레드 안전성 보장)
    private volatile String clientMacAddress = ""; // 연결된 클라이언트 MAC 주소
    private volatile Object connectionLock = new Object(); // 연결 대기용 락 객체

    // JNI 스트림들 (이미 구현된 클래스 활용)
    private NativeBluetoothInputStream inputStream;
    private NativeBluetoothOutputStream outputStream;

    // 트리거 관련 필드
    private Thread triggerThread; // 트리거 전송 스레드
    private static final int TRIGGER_INTERVAL_MS = 1000; // 1초마다 전송

    /**
     * JNI Bluetooth 서버 생성자
     * @param apkDownloadPath APK 파일 저장 경로
     * @param context 애플리케이션 컨텍스트
     */
    public YModemBluetoothAbstractServerImpl(File apkDownloadPath, Context context) {
        super(apkDownloadPath, context);

        // 이미 구현된 JNI 스트림들 초기화
        this.inputStream = new NativeBluetoothInputStream();
        this.outputStream = new NativeBluetoothOutputStream();

        // JNI 콜백 리스너 설정
        NativeBtServer.setListener(this);
    }

    @Override
    protected String getServerType() {
        return "Bluetooth(JNI)";
    }

    /**
     * JNI RFCOMM 서버 시작
     * @param channel 사용하지 않음 (JNI에서 자동 할당)
     * @throws IOException 서버 시작 실패 시
     */
    @Override
    protected void startServerSocket(int channel) throws IOException {
        try {
            // JNI 서버를 별도 스레드에서 시작 (블로킹 호출이므로)
            new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        logMessage("[O] JNI 블루투스 서버 시작 중...");
                        int result = NativeBtServer.createBluetoothServer(); // JNI 서버 시작 (블로킹)

                        if (result == 0) {
                            logMessage("[O] JNI 블루투스 서버 시작 성공");
                        } else {
                            logMessage("[X] JNI 블루투스 서버 시작 실패: " + result);
                        }
                    } catch (Exception e) {
                        logMessage("[X] JNI 서버 시작 중 예외 발생: " + e.getMessage());
                    }
                }
            }).start();

            // 트리거 서버 시작
            // startBluetoothTriggerServer();

            logMessage("[O] JNI RFCOMM 서버 스레드가 시작되었습니다");

        } catch (Exception e) {
            throw new IOException("JNI 블루투스 서버 시작 실패: " + e.getMessage());
        }
    }

    @Override
    protected void closeServerSocket() throws IOException {
        if (!NativeBtServer.nativeIsConnected()) {
            return;
        }
        NativeBtServer.closeBluetoothServer();
    }

    /**
     * Bluetooth 트리거 서버 시작 (기존 RFCOMM 연결 재사용)
     */
    private void startBluetoothTriggerServer() {
        triggerThread = new Thread(new Runnable() {
            @Override
            public void run() {
                logMessage("[O] Bluetooth 트리거 서비스 시작 (기존 JNI 연결 재사용)");

                // 트리거 전송 루프
                while (isRunning) {
                    try {
                        // JNI 연결이 있는지 확인
                        if (!NativeBtServer.nativeIsConnected()) {
                            logMessage("[W] Bluetooth 연결 없음 - 트리거 대기 중");
                            Thread.sleep(5000); // 5초 대기 후 재확인
                            continue;
                        }

                        // 연결된 상태에서 주기적으로 트리거 데이터 전송
                        while (isRunning && NativeBtServer.nativeIsConnected()) {
                            boolean success = sendBluetoothTriggerData(outputStream, 77.7f, 123);

                            if (!success) {
                                logMessage("[X] Bluetooth 트리거 데이터 전송 실패");
                                break; // 전송 실패 시 외부 루프로 복귀하여 재연결 대기
                            }

                            Thread.sleep(TRIGGER_INTERVAL_MS); // 1초 대기
                        }

                    } catch (InterruptedException e) {
                        logMessage("[O] Bluetooth 트리거 스레드 인터럽트됨");
                        break;
                    } catch (Exception e) {
                        logMessage("[X] Bluetooth 트리거 오류: " + e.getMessage());
                        try {
                            Thread.sleep(5000); // 5초 대기 후 재시도
                        } catch (InterruptedException ie) {
                            break;
                        }
                    }
                }

                logMessage("[O] Bluetooth 트리거 서비스 종료됨");
            }
        });

        triggerThread.start();
    }

    /**
     * Bluetooth 트리거 데이터 전송
     * @param outputStream JNI 출력 스트림
     * @param waterLevel 수위 값
     * @param rtuId RTU ID
     * @return 전송 성공 시 true
     */
    private boolean sendBluetoothTriggerData(OutputStream outputStream, float waterLevel, int rtuId) {
        try {
            String triggerJson = createTriggerJson(waterLevel, rtuId);
            byte[] dataBytes = triggerJson.getBytes("UTF-8");

            // Bluetooth 프로토콜: 특별한 헤더로 YModem과 구분
            String bluetoothHeader = "TRIGGER:" + dataBytes.length + "\n";
            outputStream.write(bluetoothHeader.getBytes("UTF-8"));
            outputStream.write(dataBytes);
            outputStream.flush();

            logMessage("✔ Bluetooth 트리거 데이터 전송 성공 (" + dataBytes.length + " bytes)");
            return true;

        } catch (Exception e) {
            logMessage("❌ Bluetooth 트리거 데이터 전송 실패: " + e.getMessage());
            return false;
        }
    }

    /**
     * 트리거 JSON 데이터 생성
     * @param waterLevel 수위 값
     * @param rtuId RTU ID
     * @return JSON 문자열
     */
    private String createTriggerJson(float waterLevel, int rtuId) {
        String timestamp = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", java.util.Locale.KOREA)
                .format(new java.util.Date());

        return "{\n" +
                "  \"timestamp\": \"" + timestamp + "\",\n" +
                "  \"data\": {\n" +
                "    \"waterLevel\": " + waterLevel + ",\n" +
                "    \"rtuId\": " + rtuId + "\n" +
                "  }\n" +
                "}";
    }

    /**
     * @return 더미 객체 (실제로는 JNI 연결 상태만 중요)
     * @throws IOException 연결 대기 중 인터럽트 발생 시
     */
    /**
     * 클라이언트 연결 대기 - JNI 콜백을 통해 연결될 때까지 대기
     * @return 더미 객체 (실제로는 JNI 연결 상태만 중요)
     * @throws IOException 연결 대기 중 인터럽트 발생 시
     */
    @Override
    protected Object acceptClientConnection() throws IOException {
        synchronized (connectionLock) {
            // JNI 콜백을 통해 연결이 완료될 때까지 대기
            while (!isClientConnected && isRunning) {
                try {
                    connectionLock.wait(1000); // 1초마다 타임아웃으로 상태 확인
                } catch (InterruptedException e) {
                    throw new IOException("블루투스 연결 대기 중 인터럽트 발생");
                }
            }
        }

        if (!isRunning) {
            throw new IOException("서버가 종료되었습니다");
        }

        return "JNI_CONNECTION"; // 더미 객체 반환 (실제 소켓 객체가 아님)
    }

    /**
     * 입력 스트림 반환 - 이미 구현된 NativeBluetoothInputStream 사용
     */
    @Override
    protected InputStream getInputStream(Object clientConnection) {
        return inputStream;
    }

    /**
     * 출력 스트림 반환 - 이미 구현된 NativeBluetoothOutputStream 사용
     */
    @Override
    protected OutputStream getOutputStream(Object clientConnection) {
        return outputStream;
    }

    /**
     * 클라이언트 연결 종료 - JNI 연결 상태 초기화
     */
    @Override
    protected void closeClientConnection(Object clientConnection) {
        synchronized (connectionLock) {
            isClientConnected = false;
            clientMacAddress = "";
            logMessage("[O] JNI 클라이언트 연결이 종료되었습니다");
        }
    }

    /**
     * 클라이언트 정보 반환
     */
    @Override
    protected String getClientInfo(Object clientConnection) {
        return "Bluetooth Client (" + clientMacAddress + ")";
    }

    /**
     * 연결 상태 확인
     */
    @Override
    protected boolean isConnected(Object clientConnection) {
        return isClientConnected && NativeBtServer.nativeIsConnected();
    }

    /**
     * 서버 소켓 종료 - JNI 리소스 정리
     */
    @Override
    public void closeExistingServerSocket() {
        try {
            synchronized (connectionLock) {
                isClientConnected = false;
                connectionLock.notifyAll(); // 대기 중인 스레드들을 깨움
            }

            NativeBtServer.closeBluetoothServer(); // JNI 소켓 종료
            logMessage("[O] JNI 블루투스 서버가 정상적으로 종료되었습니다");

        } catch (Exception e) {
            logMessage("[X] JNI 블루투스 서버 종료 실패: " + e.getMessage());
        }
    }

    /**
     * 서버 실행 상태 확인
     */
    @Override
    public boolean isRunning() {
        return isRunning;
    }

    // ==================== JNI 콜백 인터페이스 구현 ====================

    /**
     * JNI에서 클라이언트 연결 시 호출되는 콜백
     * 기존 MainActivity의 nativeOnConnected 로직을 여기로 이동
     */
    @Override
    public void nativeOnConnected(String macAddress) {
        logMessage("JNI 콜백: 블루투스 클라이언트 연결됨 - " + macAddress);

        synchronized (connectionLock) {
            this.isClientConnected = true;
            this.clientMacAddress = macAddress;
            connectionLock.notifyAll();          // acceptClientConnection()에서 대기 중인 스레드를 깨움
        }

        // 여기서 바로 YModem 처리를 시작할 수도 있지만, 기존 구조(acceptClientConnection → handleYModemTransmission)를
        // 유지하기 위해 연결 상태만 업데이트하고 실제 처리는 부모 클래스에 위임함(YModemAbstractServer의 handleYModemTransmission 메서드)
    }

    /**
     * JNI에서 클라이언트 연결 해제 시 호출되는 콜백
     */
    @Override
    public void nativeOnDisconnected() {
        logMessage("JNI 콜백: 블루투스 클라이언트 연결 해제됨");

        synchronized (connectionLock) {
            this.isClientConnected = false;
            this.clientMacAddress = "";
            // connectionLock.notifyAll(); // 추후 필요 시, 대기 중인 스레드들에게 알림
        }

        // TODO: 재연결 로직이나 추가 정리 작업 수행 가능
    }
}