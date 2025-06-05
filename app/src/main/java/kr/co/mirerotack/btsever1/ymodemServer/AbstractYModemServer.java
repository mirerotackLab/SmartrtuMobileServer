package kr.co.mirerotack.btsever1.ymodemServer;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.Looper;

import com.google.gson.Gson;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.RandomAccessFile;

import kr.co.mirerotack.btsever1.RtuSnapshot;
import kr.co.mirerotack.btsever1.model.ApkValidationResult;
import kr.co.mirerotack.btsever1.model.InstallResult;
import kr.co.mirerotack.btsever1.model.UninstallResult;
import kr.co.mirerotack.btsever1.model.YModemServerInterface;

import static kr.co.mirerotack.btsever1.utils.DummyData.createDummyData;
import static kr.co.mirerotack.btsever1.utils.Logger.getCurrentTimestamp;
import static kr.co.mirerotack.btsever1.utils.Logger.logMessage;
import static kr.co.mirerotack.btsever1.utils.readwriteJson.dataFileName;
import static kr.co.mirerotack.btsever1.utils.readwriteJson.readJsonFile;
import static kr.co.mirerotack.btsever1.utils.readwriteJson.updateTimestampToFile;

/**
 * YModem 서버 공통 추상 클래스 - TCP와 Bluetooth의 중복 코드를 통합
 * 실제 소켓 연결 부분만 하위 클래스에서 구현하고, YModem 프로토콜 처리는 공통화
 */
public abstract class AbstractYModemServer implements YModemServerInterface {
    // YModem 프로토콜 상수들 (공통)
    protected static final byte SOH = 0x01; /* 128바이트 패킷 시작 */
    protected static final byte STX = 0x02; /* 1024바이트 패킷 시작 */
    protected static final byte EOT = 0x04; /* 전송 종료 */
    protected static final byte ACK = 0x06; /* 수신 확인 */
    protected static final byte NAK = 0x15; /* 오류 발생 */
    protected static final byte CAN = 0x18; /* 취소 */
    protected static final byte CPMEOF = 0x1A; /* 마지막 패딩 */
    protected static final byte START_ACK = 'C'; /* YModem 시작 신호 */

    // 공통 필드들
    protected File APK_PATH;
    protected String PackageBasePath = "kr.co.mirerotack";
    protected String NEW_APK_FILE_NAME = "firmware.apk";
    protected Context context;
    protected int errorCount = 0;
    protected boolean isRunning = false;

    protected Handler handler = new Handler(Looper.getMainLooper());
    protected Gson gson = new Gson();

    private Thread serverThread;

    /**
     * 공통 생성자
     * @param apkDownloadPath APK 다운로드 경로
     * @param context 애플리케이션 컨텍스트
     */
    public AbstractYModemServer(File apkDownloadPath, Context context) {
        this.APK_PATH = apkDownloadPath;
        this.context = context;
    }

    /**
     * 하위 클래스에서 구현해야 할 추상 메서드들 (서버별 고유 로직)
     */
    protected abstract void startServerSocket(int port) throws IOException;
    protected abstract Object acceptClientConnection() throws IOException;
    protected abstract InputStream getInputStream(Object clientConnection) throws IOException;
    protected abstract OutputStream getOutputStream(Object clientConnection) throws IOException;
    protected abstract void closeClientConnection(Object clientConnection);
    protected abstract String getClientInfo(Object clientConnection);

    @Override
    public void startServer(int port) {
        isRunning = true;
        serverThread = new Thread(new Runnable() {
            @Override
            public void run() {
                while (isRunning) {
                    try {
                        // 기존 서버 소켓을 먼저 정리
                        closeExistingServerSocket();

                        // 서버 소켓 시작 (하위 클래스에서 구현)
                        startServerSocket(port);

                        logMessage("==========================================================");
                        logMessage(getServerType() + " Server started on port/channel: " + port);

                        while (isRunning) {
                            logMessage("--------------------1. " + getServerType() + " Ready to receive-----------------------");

                            try {
                                if (errorCount > 3) {
                                    logMessage("[X] " + getServerType() + " Socket error occurred more than 3 times. Restarting...");
                                    errorCount = 0;
                                    break;
                                }

                                logMessage("--------------------2. " + getServerType() + " Waiting for connection---------------------");
                                Object clientConnection = acceptClientConnection(); // 하위 클래스에서 구현

                                logMessage("--------------------3. " + getServerType() + " Starting to receive--------------------");
                                logMessage("[O] " + getServerType() + " Client connected: " + getClientInfo(clientConnection));

                                // 🎯 핵심: 공통 YModem 파일 처리 로직
                                handleYModemTransmission(clientConnection);

                            } catch (IOException e) {
                                logMessage(getServerType() + " Server communication error: " + e.getMessage());
                                waitSeconds(5000);
                                break;
                            }
                        }
                    } catch (IOException e) {
                        logMessage("[X] Failed to start " + getServerType() + " server: " + e.getMessage());
                        waitSeconds(10000);
                        break;
                    } finally {
                        closeExistingServerSocket();
                    }
                }
            }
        });
        serverThread.start();
    }

    @Override
    public void stopServer() {
        isRunning = false;
        try {
            serverThread.stop();
        } catch (RuntimeException e) {
            logMessage("[X] " + getServerType() + " Server thread already stopped: " + e.getMessage());
        }
        closeExistingServerSocket();
        logMessage("[O] " + getServerType() + " server stopped");
    }

    /**
     * 🔥 핵심 메서드: YModem 파일 처리 로직 (완전히 공통화)
     * TCP든 Bluetooth든 동일한 로직으로 처리
     * @param clientConnection 클라이언트 연결 객체 (Socket 또는 BluetoothSocket)
     */
    protected void handleYModemTransmission(Object clientConnection) {
        InputStream inputStream = null;
        OutputStream outputStream = null;

        File saveDirectory = APK_PATH;
        if (!saveDirectory.exists()) saveDirectory.mkdirs();

        try {
            // 하위 클래스에서 스트림 획득 (블루투스 or TCP 서버 소켓의 in-output Stream 획득 가능
            inputStream = getInputStream(clientConnection);
            outputStream = getOutputStream(clientConnection);

            // 🎯 YModem 클래스는 수정하지 않고 그대로 사용!
            YModem yModem = new YModem(inputStream, outputStream);

            // 1️⃣ [RX] 헤더 수신
            logMessage("3. Starting to receive header...");
            File receivedHeader = yModem.receive_Header(saveDirectory, true);
            if (receivedHeader == null) {
                throw new IOException("[X] 3-101. Failed to receive header!");
            }

            logMessage("[O] 3-2. Header received successfully");
            sendByte(outputStream, ACK, "4-1. [TX] ACK");

            if (yModem.getIsSyncDataMode()) {
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

            // 2️⃣ [RX] APK 수신
            logMessage("5. Waiting for APK data...");
            File receivedFile = yModem.receive_APK(new File(""), false);

            if (!checkFileIntegrity(receivedFile, yModem.getExpectedFileSize(), outputStream))
                return;

            receivedFile = renameFile(receivedFile, NEW_APK_FILE_NAME);

            // 3️⃣ [TX] 전송 종료 신호
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
        } catch (Exception e) {
            logMessage("[X] " + getServerType() + " YModem 처리 중 오류 발생: " + e.getMessage());
            if (saveDirectory.exists()) saveDirectory.delete();
            handleError(e);
        } finally {
            try {
                if (inputStream != null) inputStream.close();
                if (outputStream != null) outputStream.close();
                closeClientConnection(clientConnection);
            } catch (Exception e) {
                logMessage("[X] " + getServerType() + " connection close error: " + e.getMessage());
            }
        }
    }

    // 🔥 이하 모든 메서드들은 완전히 공통화된 YModem 프로토콜 처리 로직
    protected boolean syncData(Context context, InputStream inputStream, OutputStream outputStream) throws IOException {
        try {
            RtuSnapshot snapshot;
            File file = new File(context.getFilesDir(), dataFileName);

            logMessage("불러올 Json 파일 절대 경로 : " + file.getAbsolutePath());
            logMessage("불러올 Json 파일 존재 여부 : " + file.exists());

            if (file.exists()) {
                String jsonString = readJsonFile(file);
                snapshot = gson.fromJson(jsonString, RtuSnapshot.class);
                logMessage("8-0. [RX] 센서 데이터: 파일에서 로드됨");

                snapshot.timestamp = getCurrentTimestamp();
                updateTimestampToFile(context, snapshot);
                logMessage("8-0. [RX] JSON 파일에 timestamp 갱신됨");
            } else {
                logMessage("8-0. [RX] RtuStatus.json 파일 없음, 더미 데이터로 대체");
                snapshot = createDummyData();
                snapshot.timestamp = getCurrentTimestamp();

                updateTimestampToFile(context, snapshot);
                logMessage("8-0. [RX] 더미 JSON 파일 생성됨");
            }

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

    protected void waitSeconds(int waitTime) {
        try {
            Thread.sleep(waitTime);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }

    protected boolean checkFileIntegrity(File receivedFile, long expectedSize, OutputStream outputStream) throws IOException {
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

    protected File renameFile(File file, String newFileName) {
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

    protected void sendByte(OutputStream outputStream, byte data, String message) throws IOException {
        outputStream.write(data);
        outputStream.flush();
        logMessage(message);
    }

    protected byte receiveByte(InputStream inputStream) throws IOException {
        byte[] buffer = new byte[1];
        if (inputStream.read(buffer) > 0) return buffer[0];
        return -1;
    }

    protected void handleError(Exception e) {
        String errorMsg = e.getMessage();
        logMessage("[X] " + getServerType() + " Socket error occurred: " + errorMsg);
        boolean isExpectedError = false;

        if (errorMsg == null) {
            logMessage("[X] errorMsg does not exist...");
            closeExistingServerSocket();
            return;
        }
        // 클라이언트가 갑자기 종료된 경우 예외 처리
        else if (errorMsg.contains("EPIPE") || errorMsg.contains("ECONNRESET")) {
            logMessage("[X] " + getServerType() + " Client connection was forcibly closed. Closing socket and waiting...");
            isExpectedError = true;
        }
        // 헤더 오류들
        else if (errorMsg.contains("Invalid YModem header")) {
            logMessage("[X] " + getServerType() + " Client sent an invalid header. Closing socket and waiting...");
            isExpectedError = true;
        } else if (errorMsg.contains("RepeatedBlockException")) {
            logMessage("[X] 5-601. " + getServerType() + " Received a duplicate of the previous block. Closing socket and waiting...");
            isExpectedError = true;
        } else if (errorMsg.contains("SynchronizationLostException")) {
            logMessage("[X] 5-602. " + getServerType() + " Block number mismatch. Closing socket and waiting...");
            isExpectedError = true;
        } else if (errorMsg.contains("InvalidBlockException")) {
            logMessage("[X] 5-603. " + getServerType() + " Calibration value mismatch or 5-604. CRC mismatch. Closing socket and waiting...");
            isExpectedError = true;
        }

        if (isExpectedError) {
            closeExistingServerSocket();
            return;
        }

        logMessage("[X] " + getServerType() + " Unhandled error occurred. Restarting server socket.");
        closeExistingServerSocket();
    }

    protected void rebootDevice() {
        try {
            Process process = Runtime.getRuntime().exec("ssu -c reboot");
            process.waitFor();
            logMessage("Device rebooting...");
        } catch (Exception e) {
            logMessage("Reboot failed: " + e.getMessage());
        }
    }

    protected String findInstalledPackageName(String target, String nonTarget) {
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

    protected ApkValidationResult ValidateAPK(String apkPath, boolean isForceUpdate) {
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

        if (installedAppPackageName != null && !apkPackageName.equals(installedAppPackageName) && isForceUpdate) {
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

    /**
     * 서버 타입 이름 반환 (하위 클래스에서 구현)
     * @return 서버 타입 문자열 (예: "TCP", "Bluetooth")
     */
    protected abstract String getServerType();
}