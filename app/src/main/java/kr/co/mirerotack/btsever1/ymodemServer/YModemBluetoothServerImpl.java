package kr.co.mirerotack.btsever1.ymodemOverTcp;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothServerSocket;
import android.bluetooth.BluetoothSocket;
import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.google.gson.Gson;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.lang.reflect.Method;
import java.util.Set;
import java.util.UUID;

import kr.co.mirerotack.btsever1.RtuSnapshot;
import kr.co.mirerotack.btsever1.model.ApkValidationResult;
import kr.co.mirerotack.btsever1.model.InstallResult;
import kr.co.mirerotack.btsever1.model.UninstallResult;
import kr.co.mirerotack.btsever1.model.YModemServerInterface;
import kr.co.mirerotack.btsever1.ymodemServer.YModem;

import static kr.co.mirerotack.btsever1.utils.DummyData.createDummyData;
import static kr.co.mirerotack.btsever1.utils.Logger.getCurrentTimestamp;
import static kr.co.mirerotack.btsever1.utils.Logger.logMessage;

/**
 * Bluetooth 서버 구현체 - 기존 BluetoothServerService 로직을 YModem에 적용
 * 기존 TCP 서버와 동일한 YModem 프로토콜 처리 로직을 Bluetooth로 구현
 */
public class YModemBluetoothServerImpl implements YModemServerInterface {
    // YModem 프로토콜 상수들 (TCP와 동일)
    protected static final byte SOH = 0x01; /* 128바이트 패킷 시작 */
    protected static final byte STX = 0x02; /* 1024바이트 패킷 시작 */
    protected static final byte EOT = 0x04; /* 전송 종료 */
    protected static final byte ACK = 0x06; /* 수신 확인 */
    protected static final byte NAK = 0x15; /* 오류 발생 */
    protected static final byte CAN = 0x18; /* 취소 */
    protected static final byte CPMEOF = 0x1A; /* 마지막 패딩 */
    protected static final byte START_ACK = 'C'; /* YModem 시작 신호 */

    private static final String TAG = "YModemBluetoothServer";
    private static final String SERVICE_NAME = "YModemBluetoothServer";
    private static final UUID SERVICE_UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB"); // SPP 표준 UUID

    // Bluetooth 관련 필드들 (기존 코드에서 가져옴)
    private BluetoothServerSocket bluetoothServerSocket;
    private BluetoothSocket bluetoothClientSocket;
    private AcceptThread acceptThread;
    private boolean isConnected = false;

    // YModem 관련 필드들 (TCP와 동일)
    private File APK_PATH;
    private String PackageBasePath = "kr.co.mirerotack";
    private String NEW_APK_FILE_NAME = "firmware.apk";
    private static final String dataFileName = "RtuStatus.json";
    private Context context;
    private int errorCount = 0;
    private boolean isRunning = false;

    Handler handler = new Handler(Looper.getMainLooper());
    Gson gson = new Gson();

    /**
     * Bluetooth 서버 생성자
     * @param apkDownloadPath APK 다운로드 경로
     * @param context 애플리케이션 컨텍스트
     */
    public YModemBluetoothServerImpl(File apkDownloadPath, Context context) {
        this.APK_PATH = apkDownloadPath;
        this.context = context;
    }

    @Override
    public void startServer(int channel) {
        isRunning = true;
        logMessage("==========================================================");
        logMessage("Bluetooth YModem Server starting...");

        // Accept 스레드 시작 (기존 로직 활용)
        startAcceptThread();
    }

    /**
     * 클라이언트 연결 요청을 수락하는 스레드 (기존 BluetoothServerService 로직 활용)
     */
    private void startAcceptThread() {
        // 이미 실행 중인 스레드가 있으면 중지
        if (acceptThread != null) {
            acceptThread.cancel();
        }

        acceptThread = new AcceptThread();
        acceptThread.start();
    }

    /**
     * Accept 스레드 클래스 - 기존 BluetoothServerService 로직을 그대로 활용
     */
    private class AcceptThread extends Thread {
        private boolean running = true;

        @Override
        public void run() {
            BluetoothAdapter bluetoothAdapter = BluetoothAdapter.getDefaultAdapter();

            if (bluetoothAdapter == null) {
                logMessage("[X] Bluetooth 어댑터 없음");
                Log.e(TAG, "Bluetooth 어댑터를 찾을 수 없음");
                return;
            }

            Log.d(TAG, "isEnabled = " + bluetoothAdapter.isEnabled());
            Log.d(TAG, "name = " + bluetoothAdapter.getName());

            // Bluetooth 활성화 대기 로직 (기존과 동일)
            int waitTime = 0;
            while (!bluetoothAdapter.isEnabled() && waitTime < 20000) {
                try {
                    Log.e(TAG, "bluetoothAdapter.isEnabled() is false, waitTime: " + waitTime + "ms");
                    Log.d(TAG, "retry, bluetoothAdapter.enable()");
                    bluetoothAdapter.enable();
                    Thread.sleep(500);
                } catch (InterruptedException e) {
                    throw new RuntimeException(e);
                }
                waitTime += 500;
            }

            // Reflection을 통한 Bluetooth 강제 활성화 (기존과 동일)
            if (!bluetoothAdapter.isEnabled()) {
                logMessage("[X] Bluetooth 꺼져 있음");
                Log.e(TAG, "Bluetooth가 꺼져 있음");

                try {
                    Method enableMethod = BluetoothAdapter.class.getMethod("enable");
                    enableMethod.setAccessible(true);
                    boolean success = (boolean) enableMethod.invoke(bluetoothAdapter);
                    Log.d("Bluetooth", "enable() called: " + success);
                } catch (Exception e) {
                    Log.e("Bluetooth", "Reflection failed", e);
                }
            }

            // 페어링된 장치 로깅 (기존과 동일)
            Set<BluetoothDevice> bondedDevices = bluetoothAdapter.getBondedDevices();
            for (BluetoothDevice device : bondedDevices) {
                Log.d(TAG, "Paired device: " + device.getName() + ", " + device.getAddress());
            }

            // 연결 수락 무한 루프 (기존과 동일)
            while (running && isRunning) {
                try {
                    // 이전 서버 소켓이 있으면 닫기
                    if (bluetoothServerSocket != null) {
                        try {
                            bluetoothServerSocket.close();
                        } catch (IOException e) {
                            Log.e(TAG, "이전 서버 소켓 닫기 실패", e);
                        }
                    }

                    // 새 서버 소켓 생성
                    bluetoothServerSocket = bluetoothAdapter.listenUsingInsecureRfcommWithServiceRecord(SERVICE_NAME, SERVICE_UUID);
                    logMessage("[O] Bluetooth 서버 소켓 생성 성공, 연결 대기 중...");
                    Log.d(TAG, "서버 소켓 생성 성공, 연결 대기 중...");

                    // 연결 수락 (블로킹 호출)
                    bluetoothClientSocket = bluetoothServerSocket.accept();

                    if (bluetoothClientSocket != null) {
                        synchronized (YModemBluetoothServerImpl.this) {
                            isConnected = true;
                            logMessage("[O] Bluetooth 클라이언트 연결 성공: " + bluetoothClientSocket.getRemoteDevice().getName());
                            Log.d(TAG, "클라이언트 연결 성공: " + bluetoothClientSocket.getRemoteDevice().getName());

                            // 🎯 핵심: YModem 파일 처리 시작 (TCP와 동일한 로직)
                            try {
                                handleIncomingFile(bluetoothClientSocket);
                            } catch (Exception e) {
                                logMessage("[X] YModem 파일 처리 중 오류: " + e.getMessage());
                                handleError(e);
                            }

                            // 서버 소켓 닫기 (한 번에 하나의 연결만 처리)
                            try {
                                bluetoothServerSocket.close();
                            } catch (IOException e) {
                                Log.e(TAG, "서버 소켓 닫기 실패", e);
                            }
                        }
                    }
                } catch (IOException e) {
                    if (running && isRunning) {
                        if (bluetoothAdapter.isEnabled()) {
                            logMessage("[X] Bluetooth 클라이언트 연결 중 오류 발생, 재시도 중...");
                            Log.e(TAG, "accept() 에러, 재시도 중...", e);
                        } else {
                            logMessage("[X] bluetoothAdapter.isEnabled() is False...");
                            Log.e(TAG, "bluetoothAdapter.isEnabled() is False... 재시도 중...", e);
                        }

                        // 잠시 대기 후 재시도
                        try {
                            Thread.sleep(5000); // TCP보다 짧게 설정
                        } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                            running = false;
                        }
                    }
                } catch (Exception e) {
                    if (running && isRunning) {
                        logMessage("[X] Bluetooth 클라이언트 연결 중 예외 발생, 재시도 중...");
                        Log.e(TAG, "accept() 예외, 재시도 중...", e);

                        // 잠시 대기 후 재시도
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

        public void cancel() {
            running = false;
            try {
                if (bluetoothServerSocket != null) {
                    bluetoothServerSocket.close();
                }
            } catch (IOException e) {
                Log.e(TAG, "AcceptThread 취소 중 오류", e);
            }
        }
    }

    @Override
    public void closeExistingServerSocket() {
        try {
            if (bluetoothServerSocket != null) {
                bluetoothServerSocket.close();
                logMessage("[O] Bluetooth 서버 소켓 닫기 성공");
            }
            if (bluetoothClientSocket != null) {
                bluetoothClientSocket.close();
                logMessage("[O] Bluetooth 클라이언트 소켓 닫기 성공");
            }
        } catch (IOException e) {
            logMessage("[X] Bluetooth 서버 소켓 닫기 실패: " + e.getMessage());
        }
    }

    @Override
    public void stopServer() {
        isRunning = false;

        // Accept 스레드 종료
        if (acceptThread != null) {
            acceptThread.cancel();
            acceptThread = null;
        }

        closeExistingServerSocket();
        logMessage("[O] Bluetooth YModem 서버 중지됨");
    }

    @Override
    public boolean isRunning() {
        return isRunning;
    }

    /**
     * 🔥 핵심 메서드: Bluetooth 소켓으로 YModem 파일 처리 (TCP 로직과 거의 동일)
     * @param socket Bluetooth 클라이언트 소켓
     * @throws IOException 입출력 예외 발생시
     */
    private void handleIncomingFile(BluetoothSocket socket) throws IOException {
        InputStream inputStream = null;
        OutputStream outputStream = null;
        int timeoutRetries = 0;

        File saveDirectory = APK_PATH;
        if (!saveDirectory.exists()) saveDirectory.mkdirs();

        try {
            // 🎯 Bluetooth 소켓에서 스트림 획득 (TCP와 동일한 방식!)
            inputStream = socket.getInputStream();
            outputStream = socket.getOutputStream();

            logMessage("--------------------3. Bluetooth Starting to receive--------------------");

            // 🎯 YModem 클래스는 수정하지 않고 그대로 사용!
            YModem yModem = new YModem(inputStream, outputStream);

            // 1️⃣ [RX] 헤더 수신 (TCP와 완전히 동일)
            logMessage("3. Starting to receive header...");
            File receivedHeader = yModem.receive_Header(saveDirectory, true);
            if (receivedHeader == null) {
                throw new IOException("[X] 3-101. Failed to receive header!");
            }

            logMessage("[O] 3-2. Header received successfully");
            sendByte(outputStream, ACK, "4-1. [TX] ACK");

            if(yModem.getIsSyncDataMode()) {
                logMessage("handleSyncDataMode Start");
                syncData(context, inputStream, outputStream);
                return;
            }

            if (yModem.getIsRebootMode()) {
                logMessage("handleRebootMode Start");
                Process processStart = Runtime.getRuntime().exec("ssu -c reboot");
                processStart.waitFor();
                return;
            }

            // 2️⃣ [RX] APK 수신 (TCP와 완전히 동일)
            logMessage("5. Waiting for APK data...");
            File receivedFile = yModem.receive_APK(new File(""), false);

            if (!checkFileIntegrity(receivedFile, yModem.getExpectedFileSize(), outputStream))
                return;

            receivedFile = renameFile(receivedFile, NEW_APK_FILE_NAME);

            // 3️⃣ [TX] 전송 종료 신호 (TCP와 완전히 동일)
            sendByte(outputStream, EOT, "7-1. [TX] EOT");
            waitSeconds(3000);

            while (true) {
                if (receiveByte(inputStream) == EOT) {
                    logMessage("7-4. [RX] EOT");
                    break;
                }
            }

            ApkValidationResult apkValidationResult = ValidateAPK(receivedFile.getPath(), yModem.getIsForceUpdateMode());

            if (apkValidationResult.getIsUpdate()) {
                logMessage("[Update O] : " + apkValidationResult.getInstallCode() + ", " + apkValidationResult.getComment());
                logMessage("[O] APK is fine. Rebooting for update in 5 seconds.");

                handler.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        rebootDevice();
                    }
                }, 5000);
            } else {
                logMessage("[Update X] : " + apkValidationResult.getUninstallCode() + ", " + apkValidationResult.getComment());
                logMessage("[X] Update (reboot) skipped, APK file deleted.");
                receivedFile.delete();
            }
        } catch (PackageManager.NameNotFoundException e) {
            throw new RuntimeException(e);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        } catch (Exception e) {
            logMessage("[X] Bluetooth YModem 처리 중 오류 발생: " + e.getMessage());
            saveDirectory.delete();
            handleError(e);
        } finally {
            try {
                if (inputStream != null) inputStream.close();
                if (outputStream != null) outputStream.close();
                if (socket != null) {
                    socket.close();
                }
            } catch (IOException e) {
                logMessage("[X] Bluetooth Socket close error: " + e.getMessage());
            }
        }
    }

    // 🔥 이하 모든 메서드들은 TCP 버전과 완전히 동일 (YModem 프로토콜 처리)
    private boolean syncData(Context context, InputStream inputStream, OutputStream outputStream) throws IOException {
        try {
            RtuSnapshot snapshot;

            // 1. JSON 파일 존재 여부 확인 및 로드
            File file = new File(context.getFilesDir(), dataFileName);

            logMessage("불러올 Json 파일 절대 경로 : " + file.getAbsolutePath());
            logMessage("불러올 Json 파일 존재 여부 : " + file.exists());

            if (file.exists()) {
                // 1. JSON 파싱
                String jsonString = readJsonFile(file);

                snapshot = gson.fromJson(jsonString, RtuSnapshot.class);
                logMessage("8-0. [RX] 센서 데이터: 파일에서 로드됨");

                // 2. timestamp 현재 시간으로 갱신
                snapshot.timestamp = getCurrentTimestamp();

                // 3. JSON 파일에 다시 저장 (timestamp 반영)
                updateTimestampToFile(context, snapshot);

                logMessage("8-0. [RX] JSON 파일에 timestamp 갱신됨");
            } else {
                // 4. 파일이 없으면 더미 데이터 생성 후 파일로 저장
                logMessage("8-0. [RX] RtuStatus.json 파일 없음, 더미 데이터로 대체");
                snapshot = createDummyData();
                snapshot.timestamp = getCurrentTimestamp();

                updateTimestampToFile(context, snapshot);
                logMessage("8-0. [RX] 더미 JSON 파일 생성됨");
            }

            // 5. 최종적으로 파일 다시 읽어서 전송
            String finalJson = readJsonFile(file);
            byte[] dataBytes = finalJson.getBytes("UTF-8");

            outputStream.write(dataBytes);
            outputStream.flush();

            logMessage("8-1. [RX] 센서 데이터 전송 성공");
            return true;

        } catch (IOException e) {
            logMessage("8-100. [RX] 센서 데이터 전송 실패 (IOException), " + e.getCause() + ", " + e.getMessage());
            return false;
        }
    }

    private void updateTimestampToFile(Context context, RtuSnapshot snapshot) throws IOException {
        File file = new File(context.getFilesDir(), dataFileName);
        String json = gson.toJson(snapshot);

        FileOutputStream fos = new FileOutputStream(file, false);
        fos.write(json.getBytes("UTF-8"));
        fos.close();
    }

    private String readJsonFile(File file) throws IOException {
        StringBuilder builder = new StringBuilder();
        BufferedReader reader = new BufferedReader(new InputStreamReader(new FileInputStream(file), "UTF-8"));
        String line;
        while ((line = reader.readLine()) != null) {
            builder.append(line);
        }
        reader.close();
        return builder.toString();
    }

    private void waitSeconds(int waitTime) {
        try {
            Thread.sleep(waitTime);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }

    private boolean checkFileIntegrity(File receivedFile, long expectedSize, OutputStream outputStream) throws IOException {
        if (receivedFile == null) {
            logMessage("[X] File is null.");
            return false;
        }

        if (!receivedFile.exists() || receivedFile.length() == 0) {
            logMessage("[X] 5-101. Failed to receive APK file data!");
            return false;
        }

        logMessage("The expected file size is " + expectedSize + " bytes");
        RemovePadding(receivedFile.getPath(), expectedSize);

        long receivedSize = receivedFile.length();
        logMessage("The actual received file size is " + receivedSize + " bytes");

        if (expectedSize != receivedSize) {
            logMessage("[X] Data integrity verification failed! (Expected: " + expectedSize + " bytes / Received: " + receivedSize + " bytes)");
            sendByte(outputStream, NAK, "[X] 6-100. " + "[TX] NAK");
            receivedFile.delete();
            return false;
        }

        logMessage("[O] Data integrity verification successful!");
        return true;
    }

    private File renameFile(File file, String newFileName) {
        if (file == null || !file.exists()) {
            logMessage("[X] File does not exist.");
            return null;
        }

        String originalFileName = file.getName();
        File renamedFile = new File(file.getParent(), newFileName);

        boolean success = file.renameTo(renamedFile);

        if (success) {
            logMessage("[O] File name has been changed from " + originalFileName + " to " + newFileName + ": " + renamedFile.getPath());
            return renamedFile;
        } else {
            logMessage("[X] Failed to rename the file");
            return null;
        }
    }

    private void sendByte(OutputStream outputStream, byte data, String message) throws IOException {
        outputStream.write(data);
        outputStream.flush();
        logMessage(message);
    }

    private byte receiveByte(InputStream inputStream) throws IOException {
        byte[] buffer = new byte[1];
        if (inputStream.read(buffer) > 0) return buffer[0];
        return -1;
    }

    private void handleError(Exception e) {
        String errorMsg = e.getMessage();
        logMessage("[X] Bluetooth Socket error occurred: " + errorMsg);
        boolean isExpectedError = false;

        if (errorMsg == null) {
            logMessage("[X] errorMsg does not exist...");
            closeExistingServerSocket();
            return;
        }
        // 클라이언트가 갑자기 종료된 경우 예외 처리
        else if (errorMsg.contains("EPIPE") || errorMsg.contains("ECONNRESET")) {
            logMessage("[X] Bluetooth Client connection was forcibly closed. Closing socket and waiting...");
            isExpectedError = true;
        }
        // 헤더 오류들
        else if (errorMsg.contains("Invalid YModem header")) {
            logMessage("[X] Bluetooth Client sent an invalid header. Closing socket and waiting...");
            isExpectedError = true;
        } else if (errorMsg.contains("RepeatedBlockException")) {
            logMessage("[X] 5-601. Bluetooth Received a duplicate of the previous block. Closing socket and waiting...");
            isExpectedError = true;
        } else if (errorMsg.contains("SynchronizationLostException")) {
            logMessage("[X] 5-602. Bluetooth Block number mismatch. Closing socket and waiting...");
            isExpectedError = true;
        } else if (errorMsg.contains("InvalidBlockException")) {
            logMessage("[X] 5-603. Bluetooth Calibration value mismatch or 5-604. CRC mismatch. Closing socket and waiting...");
            isExpectedError = true;
        }

        if (isExpectedError == true) {
            closeExistingServerSocket();
            return;
        }

        // 기타 오류 발생 시에는 재시작 처리
        logMessage("[X] Bluetooth Unhandled error occurred. Restarting server socket.");
        closeExistingServerSocket();
    }

    private void rebootDevice() {
        try {
            Process process = Runtime.getRuntime().exec("ssu -c reboot");
            process.waitFor();
            logMessage("Device rebooting...");
        } catch (Exception e) {
            logMessage("Reboot failed: " + e.getMessage());
        }
    }

    private String findInstalledPackageName(String target, String nonTarget) {
        try {
            Process process = Runtime.getRuntime().exec("pm list packages " + target);
            BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
            String line;

            while ((line = reader.readLine()) != null) {
                if (line.startsWith("package:")) {
                    String packageName = line.replace("package:", "").trim();

                    if (!packageName.equals(nonTarget)) {
                        return packageName;
                    }
                }
            }
            reader.close();
        } catch (IOException e) {
            logMessage("[X] Error occurred while retrieving package list: " + e.getMessage());
        }

        return null;
    }

    private ApkValidationResult ValidateAPK(String apkPath, boolean isForceUpdate) {
        PackageManager pm = context.getPackageManager();
        PackageInfo apkInfo = pm.getPackageArchiveInfo(apkPath, 0);

        if (apkInfo == null) {
            return new ApkValidationResult(false, "[X] APK may be corrupted (unable to retrieve package information)", UninstallResult.APK_CORRUPTED);
        }

        int apkVersionCode = apkInfo.versionCode;
        String apkPackageName = apkInfo.packageName;
        logMessage("Package name retrieved from APK: " + apkPackageName);

        String installedAppPackageName = findInstalledPackageName(PackageBasePath, PackageBasePath + ".apkdownloader");
        logMessage("Currently installed package name: " + installedAppPackageName);

        if (installedAppPackageName != null && !apkPackageName.equals(installedAppPackageName) && isForceUpdate == true) {
            logMessage(" Package mismatch: " +
                    "Existing: " + installedAppPackageName.replace(PackageBasePath, "") +
                    ", APK: " + apkPackageName.replace(PackageBasePath, "")
            );
            return new ApkValidationResult(true, "[O] Existing app will be removed and the new APK will be installed (Force update enabled). Proceeding with APK_Version " + apkVersionCode, InstallResult.DIFFRENT_PACKAGE_NAME);
        } else if (installedAppPackageName != null && !apkPackageName.equals(installedAppPackageName)) {
            logMessage(" Package mismatch: " +
                    "Existing: " + installedAppPackageName.replace(PackageBasePath, "") +
                    ", APK: " + apkPackageName.replace(PackageBasePath, "")
            );
            return new ApkValidationResult(false, "[X] The package name of the installed app and the APK are different. Please enable force update.", UninstallResult.DIFFRENT_PACKAGE_NAME_NOT_FORCE);
        }

        PackageInfo installedAppInfo;
        try {
            installedAppInfo = pm.getPackageInfo(installedAppPackageName, 0);
        } catch (PackageManager.NameNotFoundException e) {
            return new ApkValidationResult(true, "The app " +
                    apkPackageName.replace(PackageBasePath + ".", "") + " is not installed." +
                    " Proceeding with installation using APK_Version " + apkVersionCode, InstallResult.APP_NOT_INSTALLED);
        }

        int installedVersionCode = installedAppInfo.versionCode;
        logMessage("Installed version: " + installedVersionCode + ", APK version: " + apkVersionCode);

        if (isForceUpdate) {
            return new ApkValidationResult(true, "[O] Force update: " + installedVersionCode + " -> " + apkVersionCode, InstallResult.FORCE_UPDATE);
        }

        if (apkVersionCode > installedVersionCode) {
            return new ApkValidationResult(true, "[O] Newer version (" + apkVersionCode + ") available. Proceeding with update.", InstallResult.NEW_VERSION_AVAILABLE);
        } else {
            return new ApkValidationResult(false, "[X] The app is already up to date.", UninstallResult.ALREADY_LATEST_VERSION);
        }
    }

    public static void RemovePadding(String filePath, Long expectedSize) {
        long actualSize, paddingStart;
        int remainder = 1024 - (int) (expectedSize % 1024);
        RandomAccessFile file = null;

        try {
            file = new RandomAccessFile(filePath, "rw");
            actualSize = file.length();
            paddingStart = actualSize - remainder;

            file.seek(paddingStart);
            boolean isPadded = true;

            for (int i = 0; i < remainder; i++) {
                if (file.read() != 0x1A) {
                    isPadded = false;
                    break;
                }
            }

            if (isPadded) {
                file.setLength(paddingStart);
                logMessage("[O] Removed " + remainder + " padding bytes successfully!");
            } else {
                logMessage("[O] No additional padding found.");
            }
        } catch (Exception e) {
            logMessage("[X] Error occurred while removing padding: " + e.getMessage());
        } finally {
            if (file != null) {
                try {
                    file.close();
                } catch (IOException e) { /* 무시 가능 */ }
            }
        }
    }
}