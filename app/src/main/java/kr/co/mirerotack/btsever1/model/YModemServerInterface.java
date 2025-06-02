package kr.co.mirerotack.btsever1.model;

// 통합 서버 인터페이스 - TCP와 Bluetooth 서버의 공통 동작 정의
public interface YModemServerInterface {

    /**
     * 서버를 시작합니다
     *
     * @param port TCP 포트 또는 Bluetooth 채널 번호
     */
    void startServer(int port);

    /**
     * 기존 서버 소켓을 정리합니다
     */
    void closeExistingServerSocket();

    /**
     * 서버를 완전히 종료합니다
     */
    void stopServer();

    /**
     * 서버 실행 상태를 확인합니다
     *
     * @return 실행중이면 true
     */
    boolean isRunning();
}
