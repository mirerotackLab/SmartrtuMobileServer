package kr.co.mirerotack.btsever1.ymodemOverTcp;

import android.app.ActivityManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.IBinder;

import java.io.File;

import static kr.co.mirerotack.btsever1.ymodemOverTcp.Logger.logMessage;

public class YModemTCPService extends Service {
    private static final String TAG = "TCPCOM"; // 로그 태그
    private YModemTCPServer tcpReceiver;

    private static File filesDir;

    // ✅ 애플리케이션 시작 시 `filesDir`을 설정하도록 변경
    public static void initialize(Context context) {
        if (filesDir == null) {
            filesDir = context.getFilesDir();
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        logMessage("[O] YModemTCPService has been restarted successfully");

        // ✅ TCP 서버 인스턴스 생성 및 시작
        tcpReceiver = new YModemTCPServer(filesDir, getApplicationContext());
        tcpReceiver.startServer();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        logMessage("[O] TCP server is running...");

        // ❌ 앱이 종료되어도 서비스가 자동 재시작되지 않음
        // 이미 download_apk 서비스가 1분 마다 확인하고 자동 재시작하고 있음.
        // 그리고 여기서 자동 재시작 시켜도 소켓이 초기화가 안돼서 의미가 없음.
        return START_NOT_STICKY;
        // return START_STICKY; // ✅ 앱이 종료되어도 서비스가 자동 재시작됨
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        Logger.closeLogger();
        tcpReceiver.closeExistingServerSocket();
        logMessage("[X] [SERVICE] Abnormally terminated, closing socket and restarting...");
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null; // ✅ 바인딩 불필요한 Foreground Service
    }

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
