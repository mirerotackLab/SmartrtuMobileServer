package kr.co.mirerotack.btsever1.ymodemServer;

import android.content.Context;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Enumeration;
import java.util.Locale;

import static kr.co.mirerotack.btsever1.utils.Logger.logMessage;

/**
 * TCP 서버 구현체 - 트리거 기능 포함
 * YModem(55556)과 트리거(55557) 두 개의 포트를 사용
 */
public class YModemTCPAbstractServerImpl extends YModemAbstractServer {
    private static final String TAG = "YModemTcpServer";
    private static final int SEND_RECEIVE_BUFFER_SIZE = 32 * 1024; // 송수신 버퍼 크기 (32KB)

    private Socket socket; // 클라이언트와 연결된 TCP 소켓
    private ServerSocket serverSocket; // 클라이언트 연결을 대기하는 TCP 서버 소켓

    // 트리거 관련 변수들
    private static final int TRIGGER_PORT = 55557; // 트리거 전용 포트
    private static final int TRIGGER_INTERVAL_MS = 1000; // 1초마다 전송

    private Thread triggerThread; // 트리거 전송 스레드
    private ServerSocket triggerServerSocket; // 트리거 전용 서버 소켓

    /**
     * TCP 서버 생성자
     * @param apkDownloadPath APK 파일 저장 경로
     * @param context 애플리케이션 컨텍스트
     */
    public YModemTCPAbstractServerImpl(File apkDownloadPath, Context context) {
        super(apkDownloadPath, context); // 부모 클래스의 공통 초기화 실행
    }

    @Override
    protected String getServerType() {
        return "TCP";
    }

    /**
     * TCP 서버 소켓 시작 (YModem + 트리거)
     */
    @Override
    protected void startServerSocket(int port) throws IOException {
        // 1. YModem 서버 소켓 시작
        serverSocket = new ServerSocket();
        serverSocket.setReuseAddress(true);
        serverSocket.bind(new InetSocketAddress("0.0.0.0", port));
        logMessage("[O] TCP YModem 포트 바인딩 성공: " + getLocalIpAddress() + ":" + port);

        // 2. 트리거 서버 시작
        startTriggerServer();
    }

    @Override
    protected void closeServerSocket() throws IOException {
        if (serverSocket == null) {
            return;
        }

        logMessage("서버 소켓을 종료합니다.");
        serverSocket.close();
        serverSocket = null;
    }

    /**
     * 트리거 서버 시작 (별도 포트 사용)
     */
    private void startTriggerServer() {
        triggerThread = new Thread(new Runnable() {
            @Override
            public void run() {
                logMessage("[O] TCP 트리거 서버 시작 (포트: " + TRIGGER_PORT + ")");

                try {
                    // 트리거 전용 서버 소켓 생성
                    triggerServerSocket = new ServerSocket(TRIGGER_PORT);
                    logMessage("[O] TCP 트리거 포트 바인딩 성공: " + getLocalIpAddress() + ":" + TRIGGER_PORT);

                } catch (IOException e) {
                    logMessage("[X] TCP 트리거 서버 소켓 생성 실패: " + e.getMessage());
                    return;
                }

                // 트리거 클라이언트 연결 및 데이터 전송 루프
                while (isRunning) {
                    Socket triggerClient = null;
                    OutputStream triggerOut = null;

                    try {
                        // 트리거 클라이언트 연결 대기
                        logMessage("[O] TCP 트리거 클라이언트 연결 대기 중...");
                        triggerClient = triggerServerSocket.accept();

                        // 소켓 성능 최적화
                        triggerClient.setTcpNoDelay(true); // 실시간 전송을 위한 Nagle 알고리즘 비활성화
                        triggerClient.setKeepAlive(true);

                        triggerOut = triggerClient.getOutputStream();
                        logMessage("[O] TCP 트리거 클라이언트 연결됨: " + triggerClient.getRemoteSocketAddress());

                        // 연결된 클라이언트에게 주기적으로 트리거 데이터 전송
                        while (isRunning && triggerClient.isConnected() && !triggerClient.isClosed()) {
                            boolean success = sendTcpTriggerData(triggerOut, 77.7f, 123);

                            if (!success) {
                                logMessage("[X] TCP 트리거 데이터 전송 실패 - 클라이언트 연결 종료");
                                break;
                            }

                            Thread.sleep(TRIGGER_INTERVAL_MS); // 1초 대기
                        }

                    } catch (IOException e) {
                        logMessage("[X] TCP 트리거 연결 오류: " + e.getMessage());
                        waitSeconds(5000); // 5초 후 재시도

                    } catch (InterruptedException e) {
                        logMessage("[O] TCP 트리거 스레드 인터럽트됨");
                        break;

                    } finally {
                        // 트리거 클라이언트 연결 정리
                        if (triggerOut != null) {
                            try {
                                triggerOut.close();
                            } catch (IOException e) { /* 무시 */ }
                        }
                        if (triggerClient != null && !triggerClient.isClosed()) {
                            try {
                                triggerClient.close();
                                logMessage("[O] TCP 트리거 클라이언트 연결 종료됨");
                            } catch (IOException e) { /* 무시 */ }
                        }
                    }
                }

                // 트리거 서버 소켓 정리
                if (triggerServerSocket != null && !triggerServerSocket.isClosed()) {
                    try {
                        triggerServerSocket.close();
                        logMessage("[O] TCP 트리거 서버 소켓 종료됨");
                    } catch (IOException e) {
                        logMessage("[X] TCP 트리거 서버 소켓 종료 실패: " + e.getMessage());
                    }
                }
            }
        });

        triggerThread.start();
    }

    /**
     * TCP 트리거 데이터 전송
     * @param outputStream 출력 스트림
     * @param waterLevel 수위 값
     * @param rtuId RTU ID
     * @return 전송 성공 시 true
     */
    private boolean sendTcpTriggerData(OutputStream outputStream, float waterLevel, int rtuId) {
        try {
            String triggerJson = createTriggerJson(waterLevel, rtuId);
            byte[] dataBytes = triggerJson.getBytes("UTF-8");

            // TCP 프로토콜: 데이터 길이 헤더 + JSON 데이터
            String lengthHeader = dataBytes.length + "\n";
            outputStream.write(lengthHeader.getBytes("UTF-8"));
            outputStream.write(dataBytes);
            outputStream.flush();

            logMessage("✔ TCP 트리거 데이터 전송 성공 (" + dataBytes.length + " bytes)");
            return true;

        } catch (IOException e) {
            logMessage("❌ TCP 트리거 데이터 전송 실패: " + e.getMessage());
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
        String timestamp = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.KOREA)
                .format(new Date());

        return "{\n" +
                "  \"timestamp\": \"" + timestamp + "\",\n" +
                "  \"data\": {\n" +
                "    \"waterLevel\": " + waterLevel + ",\n" +
                "    \"rtuId\": " + rtuId + "\n" +
                "  }\n" +
                "}";
    }

    // ==================== 기존 YModem 관련 메서드들 ====================

    @Override
    protected Object acceptClientConnection() throws IOException {
        // 서버 소켓 상태 검증 (null, 닫힘, 바인딩 안됨 상태 체크)
        if (serverSocket == null || serverSocket.isClosed() || !serverSocket.isBound()) {
            throw new IOException("TCP Server socket is not ready");
        }

        socket = serverSocket.accept();   // 클라이언트 연결 대기 (블로킹 호출)
        configureSocket(socket);          // 소켓 옵션 설정 (버퍼 크기 등)
        return socket;                    // 연결된 소켓 반환
    }

    @Override
    protected boolean isConnected(Object clientConnection) {
        return ((Socket) clientConnection).isConnected();
    }

    @Override
    protected InputStream getInputStream(Object clientConnection) throws IOException {
        return ((Socket) clientConnection).getInputStream();
    }

    @Override
    protected OutputStream getOutputStream(Object clientConnection) throws IOException {
        return ((Socket) clientConnection).getOutputStream();
    }

    @Override
    protected void closeClientConnection(Object clientConnection) {
        try {
            // 소켓이 null이 아니고 닫히지 않은 상태인지 확인 후 종료
            if (clientConnection != null && !((Socket) clientConnection).isClosed()) {
                ((Socket) clientConnection).close();
            }
        } catch (IOException e) {
            logMessage("[X] TCP socket close error: " + e.getMessage());
        }
    }

    @Override
    protected String getClientInfo(Object clientConnection) {
        Socket socket = (Socket) clientConnection;
        return socket.getRemoteSocketAddress().toString(); // 원격 주소 정보 반환
    }

    /**
     * 서버 소켓 종료 (YModem + 트리거 모두 종료)
     */
    @Override
    public void closeExistingServerSocket() {
        try {
            // 1. 트리거 스레드 종료
            if (triggerThread != null && triggerThread.isAlive()) {
                triggerThread.interrupt();
                try {
                    triggerThread.join(1000); // 최대 1초 대기
                } catch (InterruptedException e) { /* 무시 */ }
            }

            // 2. 트리거 서버 소켓 종료
            if (triggerServerSocket != null && !triggerServerSocket.isClosed()) {
                triggerServerSocket.close();
            }

            // 3. YModem 서버 소켓 종료
            if (serverSocket != null && !serverSocket.isClosed()) {
                serverSocket.close();
                logMessage("[O] TCP server socket closed successfully");
            }
        } catch (IOException e) {
            logMessage("[X] Failed to close TCP server socket: " + e.getMessage());
        }
    }

    @Override
    public boolean isRunning() {
        return isRunning && serverSocket != null && !serverSocket.isClosed();
    }

    /**
     * TCP 소켓의 성능 옵션을 설정합니다
     */
    private void configureSocket(Socket socket) throws IOException {
        // 송신 버퍼 크기 설정 (큰 파일 전송 시 성능 향상)
        socket.setSendBufferSize(SEND_RECEIVE_BUFFER_SIZE);
        // 수신 버퍼 크기 설정 (큰 파일 수신 시 성능 향상)
        socket.setReceiveBufferSize(SEND_RECEIVE_BUFFER_SIZE);
    }

    /**
     * 현재 디바이스의 로컬 IP 주소를 조회합니다
     * 여러 네트워크 인터페이스 중에서 루프백이 아닌 IPv4 주소를 찾아 반환
     * @return 디바이스의 IP 주소 문자열 (예: "192.168.1.100"), 찾지 못하면 null
     */
    public static String getLocalIpAddress() {
        try {
            // 모든 네트워크 인터페이스를 순회
            for (Enumeration<NetworkInterface> en = NetworkInterface.getNetworkInterfaces(); en.hasMoreElements(); ) {
                NetworkInterface intf = en.nextElement(); // 네트워크 인터페이스 획득

                // 해당 인터페이스의 모든 IP 주소를 순회
                for (Enumeration<InetAddress> enumIpAddr = intf.getInetAddresses(); enumIpAddr.hasMoreElements(); ) {
                    InetAddress inetAddress = enumIpAddr.nextElement();

                    // 루프백 주소가 아니고 IPv4 주소인 경우 반환
                    if (!inetAddress.isLoopbackAddress() && inetAddress instanceof Inet4Address) {
                        return inetAddress.getHostAddress(); // IP 주소 문자열 반환
                    }
                }
            }
        } catch (SocketException ex) {
            ex.printStackTrace(); // 네트워크 인터페이스 조회 실패 시 스택 트레이스 출력
        }
        return null; // IP 주소를 찾지 못한 경우 null 반환
    }
}