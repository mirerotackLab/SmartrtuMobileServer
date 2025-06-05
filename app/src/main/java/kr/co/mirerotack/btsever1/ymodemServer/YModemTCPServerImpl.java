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
import java.util.Enumeration;

import static kr.co.mirerotack.btsever1.utils.Logger.logMessage;

/**
 * TCP 서버 구현체 - AbstractYModemServer를 상속받아 TCP 소켓 전용 로직만 구현
 * YModem 프로토콜 처리는 부모 클래스에서 공통으로 처리되므로 여기서는 TCP 연결 관리만 담당
 */
public class YModemTCPServerImpl extends AbstractYModemServer {
    private static final String TAG = "YModemTcpServer"; // 로그 출력용 태그
    private static final int SEND_RECEIVE_BUFFER_SIZE = 32 * 1024; // 송수신 버퍼 크기 (100KB)

    private Socket socket; // 클라이언트와 연결된 TCP 소켓
    private ServerSocket serverSocket; // 클라이언트 연결을 대기하는 TCP 서버 소켓

    /**
     * TCP 서버 생성자
     * @param apkDownloadPath APK 파일을 저장할 디렉토리 경로
     * @param context Android 애플리케이션 컨텍스트 (파일 시스템 접근용)
     */
    public YModemTCPServerImpl(File apkDownloadPath, Context context) {
        super(apkDownloadPath, context); // 부모 클래스의 공통 초기화 실행
    }

    /**
     * 서버 타입 이름을 반환합니다 (로그 출력용)
     * @return "TCP" 문자열
     */
    @Override
    protected String getServerType() {
        return "TCP";
    }

    /**
     * TCP 서버 소켓을 생성하고 지정된 포트에 바인딩합니다
     * @param port 바인딩할 포트 번호 (일반적으로 55556 사용)
     * @throws IOException 포트 바인딩 실패 시 예외 발생
     */
    @Override
    protected void startServerSocket(int port) throws IOException {
        serverSocket = new ServerSocket(); // 새로운 TCP 서버 소켓 생성
        serverSocket.setReuseAddress(true); // 포트 재사용 허용 (서버 재시작 시 바로 사용 가능)
        serverSocket.bind(new InetSocketAddress("0.0.0.0", port)); // 모든 네트워크 인터페이스에서 지정 포트로 바인딩
        logMessage("[O] TCP Port binding successful on " + getLocalIpAddress() + ":" + port);
    }

    /**
     * 클라이언트 연결 요청을 수락하고 연결된 소켓을 반환합니다
     * @return 연결된 클라이언트 Socket 객체
     * @throws IOException 서버 소켓 상태 이상 또는 연결 수락 실패 시 예외 발생
     */
    @Override
    protected Object acceptClientConnection() throws IOException {
        // 서버 소켓 상태 검증 (null, 닫힘, 바인딩 안됨 상태 체크)
        if (serverSocket == null || serverSocket.isClosed() || !serverSocket.isBound()) {
            throw new IOException("TCP Server socket is not ready");
        }

        socket = serverSocket.accept(); // 클라이언트 연결 대기 (블로킹 호출)
        configureSocket(socket); // 소켓 옵션 설정 (버퍼 크기 등)
        return socket; // 연결된 소켓 반환
    }

    /**
     * 클라이언트 소켓에서 입력 스트림을 획득합니다
     * @param clientConnection 클라이언트 연결 객체 (Socket으로 캐스팅됨)
     * @return 데이터 수신용 InputStream
     * @throws IOException 스트림 획득 실패 시 예외 발생
     */
    @Override
    protected InputStream getInputStream(Object clientConnection) throws IOException {
        return ((Socket) clientConnection).getInputStream();
    }

    /**
     * 클라이언트 소켓에서 출력 스트림을 획득합니다
     * @param clientConnection 클라이언트 연결 객체 (Socket으로 캐스팅됨)
     * @return 데이터 송신용 OutputStream
     * @throws IOException 스트림 획득 실패 시 예외 발생
     */
    @Override
    protected OutputStream getOutputStream(Object clientConnection) throws IOException {
        return ((Socket) clientConnection).getOutputStream();
    }

    /**
     * 클라이언트 연결을 안전하게 종료합니다
     * @param clientConnection 종료할 클라이언트 연결 객체
     */
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

    /**
     * 연결된 클라이언트의 정보를 문자열로 반환합니다
     * @param clientConnection 클라이언트 연결 객체
     * @return 클라이언트의 IP 주소와 포트 정보 (예: "/192.168.1.100:54321")
     */
    @Override
    protected String getClientInfo(Object clientConnection) {
        Socket socket = (Socket) clientConnection;
        return socket.getRemoteSocketAddress().toString(); // 원격 주소 정보 반환
    }

    /**
     * 서버 소켓을 안전하게 종료합니다
     * 서비스 종료 시나 오류 발생 시 호출되어 리소스를 정리합니다
     */
    @Override
    public void closeExistingServerSocket() {
        try {
            // 서버 소켓이 열려있는 상태인지 확인 후 종료
            if (serverSocket != null && !serverSocket.isClosed()) {
                serverSocket.close();
                logMessage("[O] TCP server socket closed successfully");
            }
        } catch (IOException e) {
            logMessage("[X] Failed to close TCP server socket: " + e.getMessage());
        }
    }

    /**
     * 서버가 현재 실행 중인지 상태를 확인합니다
     * @return 서버가 실행 중이고 소켓이 정상 상태이면 true, 아니면 false
     */
    @Override
    public boolean isRunning() {
        return isRunning && serverSocket != null && !serverSocket.isClosed();
    }

    /**
     * TCP 소켓의 성능 옵션을 설정합니다
     * @param socket 설정할 TCP 소켓
     * @throws IOException 소켓 옵션 설정 실패 시 예외 발생
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