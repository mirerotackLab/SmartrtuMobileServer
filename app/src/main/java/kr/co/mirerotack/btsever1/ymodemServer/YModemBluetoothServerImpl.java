package kr.co.mirerotack.btsever1.ymodemServer;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothServerSocket;
import android.bluetooth.BluetoothSocket;
import android.content.Context;
import android.util.Log;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Method;
import java.util.Set;
import java.util.UUID;

import static kr.co.mirerotack.btsever1.utils.Logger.logMessage;

/**
 * Bluetooth 서버 구현체 - AbstractYModemServer를 상속받아 Bluetooth 전용 로직만 구현
 * TCP와 달리 Bluetooth는 별도 스레드(AcceptThread)를 통해 연결을 관리하며,
 * 페어링된 장치와의 RFCOMM 통신을 담당합니다
 */
public class YModemBluetoothServerImpl extends AbstractYModemServer {
    private static final String TAG = "YModemBluetoothServer"; // 로그 출력용 태그
    private static final String SERVICE_NAME = "YModemBluetoothServer"; // Bluetooth 서비스 이름 (클라이언트에서 검색 가능)
    private static final UUID SERVICE_UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB"); // SPP(Serial Port Profile) 표준 UUID

    private BluetoothServerSocket bluetoothServerSocket; // 클라이언트 연결을 대기하는 Bluetooth 서버 소켓
    private BluetoothSocket bluetoothClientSocket; // 연결된 클라이언트와 통신하는 Bluetooth 소켓
    private AcceptThread acceptThread; // 클라이언트 연결 수락을 담당하는 별도 스레드

    /**
     * Bluetooth 서버 생성자
     * @param apkDownloadPath APK 파일을 저장할 디렉토리 경로
     * @param context Android 애플리케이션 컨텍스트 (Bluetooth 권한 및 시스템 접근용)
     */
    public YModemBluetoothServerImpl(File apkDownloadPath, Context context) {
        super(apkDownloadPath, context); // 부모 클래스의 공통 초기화 실행
    }

    /**
     * 서버 타입 이름을 반환합니다 (로그 출력용)
     * @return "Bluetooth" 문자열
     */
    @Override
    protected String getServerType() {
        return "Bluetooth";
    }

    /**
     * Bluetooth 서버를 시작합니다
     * TCP와 달리 별도의 AcceptThread를 생성하여 연결 관리를 위임합니다
     * @param channel 사용하지 않음 (Bluetooth는 UUID로 채널 관리)
     * @throws IOException 스레드 시작 실패 시 예외 발생
     */
    @Override
    protected void startServerSocket(int channel) throws IOException {
        // Bluetooth는 별도의 AcceptThread로 처리 (TCP와 다른 비동기 방식)
        startAcceptThread();
    }

    /**
     * 클라이언트 연결을 대기합니다
     * AcceptThread에서 연결이 완료될 때까지 폴링 방식으로 대기
     * @return 연결된 BluetoothSocket 객체
     * @throws IOException 연결 대기 중 인터럽트 발생 시 예외 발생
     */
    @Override
    protected Object acceptClientConnection() throws IOException {
        // AcceptThread에서 연결이 완료될 때까지 대기 (폴링 방식)
        while (bluetoothClientSocket == null && isRunning) {
            try {
                Thread.sleep(100); // 100ms마다 연결 상태 확인
            } catch (InterruptedException e) {
                throw new IOException("Bluetooth connection interrupted");
            }
        }
        return bluetoothClientSocket; // 연결된 소켓 반환
    }

    /**
     * Bluetooth 소켓에서 입력 스트림을 획득합니다
     * @param clientConnection 클라이언트 연결 객체 (BluetoothSocket으로 캐스팅됨)
     * @return 데이터 수신용 InputStream
     * @throws IOException 스트림 획득 실패 시 예외 발생
     */
    @Override
    protected InputStream getInputStream(Object clientConnection) throws IOException {
        return ((BluetoothSocket) clientConnection).getInputStream();
    }

    /**
     * Bluetooth 소켓에서 출력 스트림을 획득합니다
     * @param clientConnection 클라이언트 연결 객체 (BluetoothSocket으로 캐스팅됨)
     * @return 데이터 송신용 OutputStream
     * @throws IOException 스트림 획득 실패 시 예외 발생
     */
    @Override
    protected OutputStream getOutputStream(Object clientConnection) throws IOException {
        return ((BluetoothSocket) clientConnection).getOutputStream();
    }

    /**
     * Bluetooth 클라이언트 연결을 안전하게 종료합니다
     * @param clientConnection 종료할 클라이언트 연결 객체
     */
    @Override
    protected void closeClientConnection(Object clientConnection) {
        try {
            if (clientConnection != null) {
                ((BluetoothSocket) clientConnection).close();
            }
        } catch (IOException e) {
            logMessage("[X] Bluetooth socket close error: " + e.getMessage());
        }
    }

    /**
     * 연결된 Bluetooth 클라이언트의 정보를 문자열로 반환합니다
     * @param clientConnection 클라이언트 연결 객체
     * @return 클라이언트 장치명과 MAC 주소 (예: "Galaxy S21 (00:11:22:33:44:55)")
     */
    @Override
    protected String getClientInfo(Object clientConnection) {
        BluetoothSocket socket = (BluetoothSocket) clientConnection;
        return socket.getRemoteDevice().getName() + " (" + socket.getRemoteDevice().getAddress() + ")";
    }

    /**
     * Bluetooth 서버의 모든 리소스를 안전하게 정리합니다
     * AcceptThread, 서버 소켓, 클라이언트 소켓을 순차적으로 종료
     */
    @Override
    public void closeExistingServerSocket() {
        try {
            // 1. AcceptThread 종료 (새로운 연결 수락 중단)
            if (acceptThread != null) {
                acceptThread.cancel();
                acceptThread = null;
            }
            // 2. 서버 소켓 종료 (연결 대기 중단)
            if (bluetoothServerSocket != null) {
                bluetoothServerSocket.close();
                logMessage("[O] Bluetooth server socket closed successfully");
            }
            // 3. 클라이언트 소켓 종료 (기존 연결 종료)
            if (bluetoothClientSocket != null) {
                bluetoothClientSocket.close();
                logMessage("[O] Bluetooth client socket closed successfully");
            }
        } catch (IOException e) {
            logMessage("[X] Failed to close Bluetooth server socket: " + e.getMessage());
        }
    }

    /**
     * Bluetooth 서버가 현재 실행 중인지 상태를 확인합니다
     * @return 서버가 실행 중이면 true, 아니면 false
     */
    @Override
    public boolean isRunning() {
        return isRunning; // 부모 클래스의 상태 플래그 사용
    }

    /**
     * AcceptThread를 시작하여 클라이언트 연결 수락을 시작합니다
     * 기존 스레드가 실행 중인 경우 먼저 종료 후 새로 시작
     */
    private void startAcceptThread() {
        // 기존 AcceptThread가 있으면 종료
        if (acceptThread != null) {
            acceptThread.cancel();
        }
        acceptThread = new AcceptThread(); // 새로운 AcceptThread 생성
        acceptThread.start(); // 스레드 시작
    }

    /**
     * Bluetooth 클라이언트 연결을 수락하고 관리하는 전용 스레드
     * TCP와 달리 Bluetooth는 어댑터 상태 관리, 페어링 확인 등 복잡한 초기화가 필요하므로
     * 별도 스레드에서 비동기로 처리합니다
     */
    private class AcceptThread extends Thread {
        private boolean running = true; // 스레드 실행 상태 플래그

        @Override
        public void run() {
            BluetoothAdapter bluetoothAdapter = BluetoothAdapter.getDefaultAdapter(); // 시스템 Bluetooth 어댑터 획득

            // 1. Bluetooth 어댑터 존재 여부 확인
            if (bluetoothAdapter == null) {
                logMessage("[X] Bluetooth 어댑터 없음");
                Log.e(TAG, "Bluetooth 어댑터를 찾을 수 없음");
                return; // Bluetooth 미지원 디바이스
            }

            Log.d(TAG, "isEnabled = " + bluetoothAdapter.isEnabled());
            Log.d(TAG, "name = " + bluetoothAdapter.getName());

            // 2. Bluetooth 활성화 대기 로직 (최대 20초 대기)
            int waitTime = 0;
            while (!bluetoothAdapter.isEnabled() && waitTime < 20000) {
                try {
                    Log.e(TAG, "bluetoothAdapter.isEnabled() is false, waitTime: " + waitTime + "ms");
                    Log.d(TAG, "retry, bluetoothAdapter.enable()");
                    bluetoothAdapter.enable(); // Bluetooth 활성화 시도
                    Thread.sleep(500); // 500ms 대기
                } catch (InterruptedException e) {
                    throw new RuntimeException(e);
                }
                waitTime += 500;
            }

            // 3. Reflection을 통한 Bluetooth 강제 활성화 (권한이 필요한 고급 기능)
            if (!bluetoothAdapter.isEnabled()) {
                logMessage("[X] Bluetooth 꺼져 있음");
                Log.e(TAG, "Bluetooth가 꺼져 있음");

                try {
                    // Java Reflection을 사용하여 시스템 권한으로 Bluetooth 강제 활성화
                    Method enableMethod = BluetoothAdapter.class.getMethod("enable");
                    enableMethod.setAccessible(true); // private 메서드 접근 허용
                    boolean success = (boolean) enableMethod.invoke(bluetoothAdapter);
                    Log.d("Bluetooth", "enable() called: " + success);
                } catch (Exception e) {
                    Log.e("Bluetooth", "Reflection failed", e);
                }
            }

            // 4. 페어링된 장치 목록 로깅 (디버깅 및 연결 가능 장치 확인용)
            Set<BluetoothDevice> bondedDevices = bluetoothAdapter.getBondedDevices();
            for (BluetoothDevice device : bondedDevices) {
                Log.d(TAG, "Paired device: " + device.getName() + ", " + device.getAddress());
            }

            // 5. 클라이언트 연결 수락 무한 루프 (서버의 핵심 로직)
            while (running && isRunning) {
                try {
                    // 이전 서버 소켓이 있으면 정리 (재연결 준비)
                    if (bluetoothServerSocket != null) {
                        try {
                            bluetoothServerSocket.close();
                        } catch (IOException e) {
                            Log.e(TAG, "이전 서버 소켓 닫기 실패", e);
                        }
                    }

                    // 새 RFCOMM 서버 소켓 생성 (Insecure 모드 사용)
                    bluetoothServerSocket = bluetoothAdapter.listenUsingInsecureRfcommWithServiceRecord(SERVICE_NAME, SERVICE_UUID);
                    logMessage("[O] Bluetooth 서버 소켓 생성 성공, 연결 대기 중...");
                    Log.d(TAG, "서버 소켓 생성 성공, 연결 대기 중...");

                    // 클라이언트 연결 수락 (블로킹 호출 - 연결될 때까지 대기)
                    bluetoothClientSocket = bluetoothServerSocket.accept();

                    if (bluetoothClientSocket != null) {
                        synchronized (YModemBluetoothServerImpl.this) {
                            logMessage("[O] Bluetooth 클라이언트 연결 성공: " + bluetoothClientSocket.getRemoteDevice().getName());
                            Log.d(TAG, "클라이언트 연결 성공: " + bluetoothClientSocket.getRemoteDevice().getName());

                            // 🎯 핵심: YModem 파일 처리 시작 (부모 클래스의 공통 로직 사용)
                            try {
                                handleYModemTransmission(bluetoothClientSocket); // YModem 프로토콜 처리
                            } catch (Exception e) {
                                logMessage("[X] YModem 파일 처리 중 오류: " + e.getMessage());
                                handleError(e); // 부모 클래스의 오류 처리 로직 호출
                            } finally {
                                // 연결 처리 완료 후 클라이언트 소켓 정리
                                try {
                                    if (bluetoothClientSocket != null) {
                                        bluetoothClientSocket.close();
                                        bluetoothClientSocket = null; // 다음 연결을 위해 null로 초기화
                                    }
                                } catch (IOException e) {
                                    Log.e(TAG, "클라이언트 소켓 닫기 실패", e);
                                }
                            }

                            // 서버 소켓 닫기 (한 번에 하나의 연결만 처리하는 정책)
                            try {
                                if (bluetoothServerSocket != null) {
                                    bluetoothServerSocket.close();
                                }
                            } catch (IOException e) {
                                Log.e(TAG, "서버 소켓 닫기 실패", e);
                            }
                        }
                    }
                } catch (IOException e) {
                    // 연결 실패 시 재시도 로직
                    if (running && isRunning) {
                        if (bluetoothAdapter.isEnabled()) {
                            logMessage("[X] Bluetooth 클라이언트 연결 중 오류 발생, 재시도 중...");
                            Log.e(TAG, "accept() 에러, 재시도 중...", e);
                        } else {
                            logMessage("[X] bluetoothAdapter.isEnabled() is False...");
                            Log.e(TAG, "bluetoothAdapter.isEnabled() is False... 재시도 중...", e);
                        }

                        // 5초 대기 후 재시도 (너무 빈번한 재시도 방지)
                        try {
                            Thread.sleep(5000);
                        } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt(); // 인터럽트 상태 복원
                            running = false; // 스레드 종료
                        }
                    }
                } catch (Exception e) {
                    // 예상치 못한 예외 발생 시 처리
                    if (running && isRunning) {
                        logMessage("[X] Bluetooth 클라이언트 연결 중 예외 발생, 재시도 중...");
                        Log.e(TAG, "accept() 예외, 재시도 중...", e);

                        // 5초 대기 후 재시도
                        try {
                            Thread.sleep(5000);
                        } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                            running = false;
                        }
                    }
                }
            }
        }

        /**
         * AcceptThread를 안전하게 종료합니다
         * 실행 중인 accept() 호출을 중단하고 서버 소켓을 닫습니다
         */
        public void cancel() {
            running = false; // 루프 종료 플래그 설정
            try {
                // 서버 소켓을 닫아서 accept() 호출을 중단시킴
                if (bluetoothServerSocket != null) {
                    bluetoothServerSocket.close();
                }
            } catch (IOException e) {
                Log.e(TAG, "AcceptThread 취소 중 오류", e);
            }
        }
    }
}