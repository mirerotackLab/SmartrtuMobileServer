package kr.co.mirerotack.btsever1.ymodemServer;

import android.app.ActivityManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.IBinder;

import java.io.File;

import kr.co.mirerotack.btsever1.model.YModemServerInterface;
import kr.co.mirerotack.btsever1.utils.Logger;

import static kr.co.mirerotack.btsever1.utils.Logger.logMessage;

/**
 * 통합 YModem 서비스 클래스 - TCP와 Bluetooth 서버를 통합 관리
 * 기존 YModemTCPService를 대체하여 두 가지 서버 타입을 모두 지원
 */
public class YModemUnifiedService extends Service {
    private static final String TAG = "YModemService"; // 로그 태그

    private YModemServerInterface yModemServer; // 현재 사용중인 서버 (인터페이스로 참조)
    private YModemServerFactory.ServerType currentServerType; // 현재 서버 타입 저장

    private static File filesDir; // APK 저장 디렉토리

    /**
     * 애플리케이션 시작 시 filesDir을 설정하도록 변경
     *
     * @param context 애플리케이션 컨텍스트
     */
    public static void initialize(Context context) {
        if (filesDir == null) {
            filesDir = context.getFilesDir();
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();

        // 설정에 따라 서버 타입 결정 (예: SharedPreferences, 설정 파일 등)
        currentServerType = getServerTypeFromConfig();

        logMessage("[O] YModemUnifiedService has been started with " + currentServerType + " server");

        // 팩토리를 통해 서버 생성 - 타입만 바꾸면 전체 로직이 변경됨!
        yModemServer = YModemServerFactory.createServer(currentServerType, filesDir, getApplicationContext());

        // 서버 시작 (TCP: 포트 55556, Bluetooth: 채널 1)
        int portOrChannel = (currentServerType == YModemServerFactory.ServerType.TCP) ? 55556 : 1;
        yModemServer.startServer(portOrChannel);
    }

    /**
     * 설정에서 서버 타입을 가져옵니다
     * 실제 구현에서는 SharedPreferences, 설정 파일, 빌드 설정 등을 사용
     *
     * @return 사용할 서버 타입
     */
    private YModemServerFactory.ServerType getServerTypeFromConfig() {
        // 예시 1: SharedPreferences 사용
        SharedPreferences prefs = getSharedPreferences("server_config", MODE_PRIVATE);
        String serverType = prefs.getString("server_type", "TCP");

        if ("BLUETOOTH".equals(serverType)) {
            return YModemServerFactory.ServerType.BLUETOOTH;
        } else {
            return YModemServerFactory.ServerType.TCP;
        }
    }

    /**
     * 런타임에 서버 타입을 변경하는 메서드
     *
     * @param newServerType 새로운 서버 타입
     */
    public void switchServerType(YModemServerFactory.ServerType newServerType) {
        if (currentServerType == newServerType) {
            logMessage("[O] Server type is already " + newServerType + ". No change needed.");
            return;
        }

        logMessage("[O] Switching server type from " + currentServerType + " to " + newServerType);

        // 기존 서버 종료
        if (yModemServer != null) {
            yModemServer.stopServer();
        }

        // 새로운 서버 생성 및 시작
        currentServerType = newServerType;
        yModemServer = YModemServerFactory.createServer(currentServerType, filesDir, getApplicationContext());

        int portOrChannel = (currentServerType == YModemServerFactory.ServerType.TCP) ? 55556 : 1;
        yModemServer.startServer(portOrChannel);

        // 설정 저장
        SharedPreferences prefs = getSharedPreferences("server_config", MODE_PRIVATE);
        prefs.edit().putString("server_type", newServerType.name()).apply();

        logMessage("[O] Server type switched successfully to " + newServerType);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        logMessage("[O] " + currentServerType + " server is running...");

        // Intent에서 서버 타입 변경 요청 확인
        if (intent != null && intent.hasExtra("switch_server_type")) {
            String requestedType = intent.getStringExtra("switch_server_type");
            try {
                YModemServerFactory.ServerType newType = YModemServerFactory.ServerType.valueOf(requestedType);
                switchServerType(newType);
            } catch (IllegalArgumentException e) {
                logMessage("[X] Invalid server type requested: " + requestedType);
            }
        }

        // 앱이 종료되어도 서비스가 자동 재시작되지 않음
        // 이미 download_apk 서비스가 1분 마다 확인하고 자동 재시작하고 있음.
        // 그리고 여기서 자동 재시작 시켜도 소켓이 초기화가 안돼서 의미가 없음.
        return START_NOT_STICKY;
        // return START_STICKY; // 앱이 종료되어도 서비스가 자동 재시작됨
    }

    @Override
    public void onDestroy() {
        super.onDestroy();

        // 서버 종료
        if (yModemServer != null) {
            yModemServer.stopServer();
        }

        Logger.closeLogger();
        logMessage("[X] [SERVICE] " + currentServerType + " server terminated");
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null; // 바인딩 불필요한 Foreground Service
    }

    /**
     * 서비스가 실행 중인지 확인하는 헬퍼 메서드
     *
     * @param context      애플리케이션 컨텍스트
     * @param serviceClass 확인할 서비스 클래스
     * @return 실행 중이면 true
     */
    private static boolean isServiceRunning(Context context, Class<?> serviceClass) {
        ActivityManager manager = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);

        for (ActivityManager.RunningServiceInfo service : manager.getRunningServices(Integer.MAX_VALUE)) {
            if (serviceClass.getName().equals(service.service.getClassName())) {
                return true;
            }
        }
        return false;
    }
}