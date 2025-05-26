package kr.co.mirerotack.btsever1.ymodemOverTcp;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Handler;
import android.os.Looper;
import android.text.format.Formatter;
import android.util.Log;

import com.google.gson.Gson;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.util.Enumeration;

import kr.co.mirerotack.btsever1.RtuSnapshot;

import static kr.co.mirerotack.btsever1.BluetoothServerService.createDummyData;
import static kr.co.mirerotack.btsever1.ymodemOverTcp.Logger.getLogFilePath;
import static kr.co.mirerotack.btsever1.ymodemOverTcp.Logger.initFileWriter;
import static kr.co.mirerotack.btsever1.ymodemOverTcp.Logger.initPrintWriter;
import static kr.co.mirerotack.btsever1.ymodemOverTcp.Logger.logMessage;

/**
 * YModem.<br/>
 * Block 0 contain minimal file information (only filename)<br/>
 * <p>
 * Created by Anton Sirotinkin (aesirot@mail.ru), Moscow 2014<br/>
 * I hope you will find this program useful.<br/>
 * You are free to use/modify the code for any purpose, but please leave a reference to me.<br/>
 */
class YModemTCPServer {
    protected static final byte SOH = 0x01; /* Start Of Header 128바이트 패킷 시작 */
    protected static final byte STX = 0x02; /* Start Of Text 1024바이트 패킷 시작 */
    protected static final byte EOT = 0x04; /* 전송 종료 */
    protected static final byte ACK = 0x06; /* 수신 확인 */
    protected static final byte NAK = 0x15; /* 오류 발생 */
    protected static final byte CAN = 0x18; /* 취소 */
    protected static final byte CPMEOF = 0x1A; /* 마지막 패딩 */
    protected static final byte START_ACK = 'C'; /* YModem 프로토콜 시작 신호 */
    protected static final byte COM_TEST = 'T'; /* 취소 */

    private static int PORT = 55556;

    // adb shell cat /proc/sys/net/core/rmem_max : 110592 -> 약 108KB
    // adb shell cat /proc/sys/net/core/wmem_max : 110592 -> 약 108KB
    private static final int SEND_RECEIVE_BUFFER_SIZE = 100 * 1024; // 100KB

    private Socket socket;

    private File APK_PATH;
    private String PackageBasePath = "kr.co.mirerotack";
    private String NEW_APK_FILE_NAME = "firmware.apk";
    private String TAG = "YmodemTcpServer";
    private Context context;

    private int errorCount = 0;
    Handler handler = new Handler(Looper.getMainLooper());

    public YModemTCPServer(File filesDir, Context context) {
        this.APK_PATH = filesDir;
        this.context = context;
    }

    public static String getLocalIpAddress() {
        try {
            for (Enumeration<NetworkInterface> en = NetworkInterface.getNetworkInterfaces(); en.hasMoreElements(); ) {
                NetworkInterface intf = en.nextElement();
                for (Enumeration<InetAddress> enumIpAddr = intf.getInetAddresses(); enumIpAddr.hasMoreElements(); ) {
                    InetAddress inetAddress = enumIpAddr.nextElement();
                    if (!inetAddress.isLoopbackAddress() && inetAddress instanceof Inet4Address) {
                        return inetAddress.getHostAddress();
                    }
                }
            }
        } catch (SocketException ex) {
            ex.printStackTrace();
        }
        return null;
    }


    void closeExistingServerSocket() {
        try {
            Process process = Runtime.getRuntime().exec("ps | grep 'YModemTCPServer'");
            BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));

            String line;
            while ((line = reader.readLine()) != null) {
                if (line.contains("YModemTCPServer")) {
                    logMessage("[O] Closing the existing TCP server socket...");

                    String[] tokens = line.split("\\s+");
                    String pid = tokens[1];  // PID는 두 번째 필드에 있음

                    logMessage("[O] Process ID to terminate: " + pid);
                    Runtime.getRuntime().exec("kill -9 " + pid);
                    Thread.sleep(2000);
                    logMessage("[O] Successfully shut down the existing TCP server socket!");
                    return;
                }
            }
            reader.close();
        } catch (Exception e) {
            logMessage("[X] Failed to shut down existing socket: " + e.getMessage());
        }
    }

    public void startServer() {
        new Thread(new Runnable() {
            @Override
            public void run() {
                while (true) {
                    try {
                        // ✅ 기존 TCP 서버 소켓을 먼저 정리
                        closeExistingServerSocket();

                        // ✅ 서버 소켓을 재사용 가능하도록 설정
                        ServerSocket sock = new ServerSocket();

                        try {
                            sock.setReuseAddress(true);
                            sock.bind(new InetSocketAddress("0.0.0.0", PORT));  // 외부 접근 허용
                            logMessage("[O] Port binding successful");
                        } catch (IOException e) {
                            logMessage("[X] Port binding failed: " + e.getMessage());
                            Log.e(TAG, "Port binding failed: " + e.getMessage());
                            e.printStackTrace(); // 콘솔 디버깅용
                        }

                        logMessage("                                                          ");
                        logMessage("==========================================================");
                        logMessage("Server started on ip: " + getLocalIpAddress());
                        logMessage("Server started on port: " + PORT);

                        while (true) {
                            logMessage("                                                              ");
                            logMessage("--------------------1. Ready to receive-----------------------");

                            try {
                                if (errorCount > 3) {
                                    logMessage("[X] Socket error occurred more than 3 times. However, the server will not restart; the socket will be closed and reused.");
                                    errorCount = 0;  // 오류 횟수 초기화
                                    break;  // 서비스 재시작 대신 새로운 연결 대기
                                }

                                // ✅ 소켓이 닫히거나 바인딩되지 않은 경우, accept()를 호출하지 않음
                                if (sock == null || sock.isClosed() || !sock.isBound()) {
                                    logMessage("[X] Server socket is either closed or not bound. Restart required.");

                                    errorCount += 1;
                                    waitSeconds(2000);
                                    break; // 다음 루프로 이동
                                }
                                logMessage("--------------------2. Waiting for socket---------------------");
                                socket = sock.accept();      // 새로운 클라이언트 요청이 들어올 때까지 블로킹
                                socket.setSoTimeout(5000); // 5초 타임아웃 설정

                                logMessage("--------------------3. Starting to receive--------------------");
                                configureSocket(socket);     // 송수신 버퍼 크기 및 타임아웃 설정
                                handleIncomingFile(socket);  // 데이터를 주고받는 핵심 로직 실행
                            } catch (IOException e) {
                                logMessage("Server communication error: " + e.getMessage());
                                waitSeconds(5000); // 5초 대기 후 다시 시도
                                break;
                            } finally {
                                try {
                                    if (socket != null && !socket.isClosed()) {
                                        socket.close();
                                    }
                                } catch (IOException e) {
                                    logMessage("Socket shutdown error: " + e.getMessage());
                                    break;
                                }
                            }
                        }
                    } catch (IOException e) {
                        logMessage("[X] Failed to start server: " + e.getMessage());

                        // ✅ 특정 오류일 경우 다시 시도하도록 변경
                        if (e.getMessage().contains("Socket is already bound")) {
                            logMessage("[Refresh] Existing socket failed to close, will retry in 5 seconds");
                            waitSeconds(5000);
                        } else {
                            waitSeconds(10000);
                        }
                        break;
                    } finally {
                        closeExistingServerSocket(); // ✅ 항상 기존 소켓 닫기
                    }
                }
            }
        }).start();
    }

    synchronized public static int SysCommand(String cmd) {

        int result = 0;

        try {
            Process proc = Runtime.getRuntime().exec(cmd);
            proc.waitFor();
            result = proc.exitValue();

            for (int i = 0; i < 5; i++) {
                System.out.println("exitValue : " + result);
                if (result != 0) {
                    proc = Runtime.getRuntime().exec(cmd);
                    proc.waitFor();
                    result = proc.exitValue();
                    Thread.sleep(1000);
                } else {
                    break;
                }
            }
            return result;
        } catch (Exception e) {
            e.printStackTrace();
            return result;
        }
    }

    private void configureSocket(Socket socket) throws IOException {
        // ✅ Nagle 알고리즘 비활성화 → 작은 패킷도 즉시 전송 -> 지연(Latency) 최소화 (빠른 응답)
        // ❗ 단점: 네트워크 혼잡이 발생할 수 있음 (작은 패킷이 많아질 경우)
        // socket.setTcpNoDelay(true);

        // ✅ 수신(Receive) 타임아웃 설정 → 클라이언트 응답이 없을 경우 지정된 시간 후 예외 발생
        // ❗ 너무 짧으면 정상적인 데이터 수신에도 영향을 줄 수 있음
        socket.setSoTimeout(2000);

        // ✅ 송신(Send) 버퍼 크기 설정
        // - 큰 데이터 전송 시 성능 향상 가능 (버퍼가 클수록 더 많은 데이터를 한 번에 보낼 수 있음)
        socket.setSendBufferSize(SEND_RECEIVE_BUFFER_SIZE);

        // ✅ 수신(Receive) 버퍼 크기 설정
        // - 큰 데이터 수신 시 성능 향상 가능 (버퍼가 클수록 더 많은 데이터를 한 번에 받을 수 있음)
        socket.setReceiveBufferSize(SEND_RECEIVE_BUFFER_SIZE);
    }

    private void handleIncomingFile(Socket socket) throws IOException {
        InputStream inputStream = null;
        OutputStream outputStream = null;
        int timeoutRetries = 0;

        File saveDirectory = APK_PATH;
        if (!saveDirectory.exists()) saveDirectory.mkdirs();

        try {
            inputStream = socket.getInputStream();
            outputStream = socket.getOutputStream();

            YModem yModem = new YModem(inputStream, outputStream);

            // 1️⃣ [RX] 헤더 수신
            logMessage("3. Starting to receive header...");
            File receivedHeader = yModem.receive_Header(saveDirectory, true);
            if (receivedHeader == null) {
                throw new IOException("[X] 3-101. Failed to receive header!");
            }

            logMessage("[O] 3-2. Header received successfully");
            sendByte(outputStream, ACK, "4-1. [TX] ACK");

            if(yModem.getIsSyncDataMode()) {
                logMessage("handleSyncDataMode Start");
                syncData(inputStream, outputStream);

                return;
            }

            if (yModem.getIsRebootMode()) {
                logMessage("handleRebootMode Start");
                sendAndReceiveACK(inputStream, outputStream); // ACK 주고 받은 뒤 reboot

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
                        // logMessage("재부팅 막아둠 테스트중~");
                        rebootDevice();
                    }
                }, 5000);  // (비동기) 5초 후 실행
            } else {
                logMessage("[Update X] : " + apkValidationResult.getUninstallCode() + ", " + apkValidationResult.getComment());
                logMessage("[X] Update (reboot) skipped, APK file deleted.");

                receivedFile.delete();
                // sendByte(outputStream, NAK, "[X] 9-100." + " [TX] NAK, APK 무효");
            }
        } catch (PackageManager.NameNotFoundException e) {
            throw new RuntimeException(e);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        } catch (SocketTimeoutException e) {
            logMessage("[X] Read timeout: " + e.getMessage());
            if (++timeoutRetries < 3) {
                logMessage("[Retry] Retrying... (Attempt " + timeoutRetries + "/3)");
                waitSeconds(2000);
                handleIncomingFile(socket);
            } else {
                logMessage("[X] Exceeded maximum retry attempts, restarting the app.");
            }
        } catch (Exception e) {
            logMessage("[X] An error occurred. Uninstalling the APK: " + e.getMessage());
            saveDirectory.delete(); // 오류가 발생한 apk 파일을 제거
            handleError(e);
        } finally {
            try {
                sendLogFileToClient(socket); // 로그 파일 전송
            } catch (IOException e) {
                logMessage("IOException" + e.getMessage());
            }

            try {
                if (inputStream != null) inputStream.close();
                if (outputStream != null) outputStream.close();
                if (socket != null && !socket.isClosed()) {
                    socket.close();
                }
            } catch (IOException e) {
                logMessage("[X] Socket close error: " + e.getMessage());
            }
        }
    }

    /// TCP 통신이 잘 되고 있는데 연결 상태를 체크하는 모드
    /// 통신 테스트 문자인 'T'를 송-수신 하는 것으로 통신 상태를 체크함.
    /// 2025.04.10 PingMode는 새로 추가된 RTU Info 모드가 통신 체크 까지 수행되어 필요 없어짐
    private void handlePingMode(InputStream inputStream, OutputStream outputStream) throws IOException {
        logMessage("handlePingMode Start");
        sendByte(outputStream, COM_TEST, "9-1. [TX] T");

        while (true) {
            if (receiveByte(inputStream) == COM_TEST) {
                logMessage("9-4. [RX] T");
                break;
            }
        }
    }

    /// SmartRTU 버전 정보 및 실행 상태 반환
    /// Smartrtu ps가 활성화 되어 있는지 체크함 : 활성화 -> ACK / 비활성화 -> NAK
    /// TX DATA : ['Version' 1] smartrtu APP Process Status :   Running  or  Not Running
    private void handleRtuInfoMode(InputStream inputStream, OutputStream outputStream) {
        logMessage("handleRtuInfoMode Start");
        try {
            // 1차 실행: kr.co.mirerotack 관련 프로세스 목록 가져오기 (apkDownloader 제외)  | grep -v 'kr.co.mirerotack.apkDownloader'
            DetectionResult installStatus = SmartRtuUtils.detectTargetUsingShellCommand(
        "pm list packages", PackageBasePath, context.getPackageName(), true
            );

            if (installStatus.isMatched) { // 설치되어 있는 경우
                logMessage("App Installed : O");

                int idx = installStatus.matchedLine.lastIndexOf('.');
                if (idx == -1) {
                    return;
                }

                String installedAppPackageName = installStatus.matchedLine.substring(idx + 1).trim(); // "smartrtu"

                PackageManager pm = context.getPackageManager();
                PackageInfo installedAppInfo = pm.getPackageInfo(PackageBasePath + "." + installedAppPackageName, 0);
                int installedAppVersionCode = installedAppInfo.versionCode;

                sendAndReceiveACK(inputStream, outputStream); // ACK 주고 받은 뒤, rtu info 데이터 전송 (버전, SmartRtu App 실행 상태)

                DetectionResult smartrtuAppProcessStatus = SmartRtuUtils.detectTargetUsingShellCommand("ps", PackageBasePath,
                        context.getPackageName().substring(context.getPackageName().lastIndexOf('.') + 1), true
                );

                String message = "[Version " + installedAppVersionCode + "] "
                        + installedAppPackageName + " APP Process Status : "
                        + (smartrtuAppProcessStatus.isMatched ? "Running" : "Not Running"); // 설치 상태 반환

                sendBytes(outputStream, message.getBytes("UTF-8"), "9-5. [TX] " + message);
                logMessage("Running app package: " + installedAppPackageName + ", version: " + installedAppVersionCode);

            } else { // 설치되어 있지 않은 경우
                sendByte(outputStream, NAK, "9-100. [TX] NAK, No running process related to " + PackageBasePath + " found");
                logMessage("App Installed : X");
                logMessage("- No running process related to " + PackageBasePath + " found");
            }
        } catch (IOException | InterruptedException e) {
            e.printStackTrace();
        } catch (PackageManager.NameNotFoundException e) {
            throw new RuntimeException(e);
        }
    }

    private boolean syncData(InputStream inputStream, OutputStream outputStream) throws IOException {
        try {
            RtuSnapshot dummyData = createDummyData();
            Gson gson = new Gson();
            String jsonData = gson.toJson(dummyData);
            byte[] dataBytes = jsonData.getBytes("UTF-8");

            outputStream.write(dataBytes);
            outputStream.flush();
            logMessage("[RX] 8-1. 센서 데이터 전송 성공, data : " + dataBytes.toString());

            sendAndReceiveACK(inputStream, outputStream);

            return true;
        } catch (IOException e) {
            logMessage("[RX] 8-100. 센서 데이터 전송 실패 (IOException), " + e.getCause() + ", " + e.getMessage());
            return false;
        }
    }

    /// ACk 주고 받기
    private void sendAndReceiveACK(InputStream inputStream, OutputStream outputStream) throws IOException {
        sendByte(outputStream, ACK, "9-1. [TX] ACK");

        while (true) {
            if (receiveByte(inputStream) == ACK) {
                logMessage("9-4. [RX] ACK");
                break;
            }
        }
    }

    private void closeSocket() {
        if (socket != null && !socket.isClosed()) {
            try {
                socket.close();
            } catch (IOException ex) {
                logMessage("[X] Failed to close socket: " + ex.getMessage());
            }
        }
    }

    private void handleError(Exception e) {
        String errorMsg = e.getMessage();
        logMessage("[X] Socket error occurred: " + errorMsg);
        boolean isExpectedError = false;

        if (errorMsg == null) {
            logMessage("[X] errorMsg does not exist...");
            closeSocket();
            return;
        }
        // 🔹 클라이언트가 갑자기 종료된 경우 예외 처리 (Broken Pipe, Connection Reset)
        else if (errorMsg.contains("EPIPE") || errorMsg.contains("ECONNRESET")) {
            logMessage("[X] Client connection was forcibly closed. Closing socket and waiting...");
            isExpectedError = true;
        }

        // 헤더 오류
        else if (errorMsg.contains("Invalid YModem header")) {
            logMessage("[X] Client sent an invalid header. Closing socket and waiting...");
            isExpectedError = true;
        } else if (errorMsg.contains("RepeatedBlockException")) {
            logMessage("[X] 5-601. Received a duplicate of the previous block. Closing socket and waiting...");
            isExpectedError = true;
        } else if (errorMsg.contains("SynchronizationLostException")) {
            logMessage("[X] 5-602. Block number mismatch. Closing socket and waiting...");
            isExpectedError = true;
        } else if (errorMsg.contains("InvalidBlockException")) {
            logMessage("[X] 5-603. Calibration value mismatch or 5-604. CRC mismatch. Closing socket and waiting...");
            isExpectedError = true;
        }

        if (isExpectedError == true) {
            closeSocket();
            return;
        }

        // 🔹 기타 오류 발생 시에는 재시작 처리
        logMessage("[X] Unhandled error occurred. Restarting server socket.");
        closeExistingServerSocket();
    }

    public void sendLogFileToClient(Socket socket) throws IOException {
        File logFile = null;
        FileInputStream fis = null;

        try {
            if (socket.isClosed()) {
                logMessage("Socket is closed. Log file will not be sent.");
                return;
            }

            logFile = new File(getLogFilePath());

            // 전송에 성공해서 제거된 경우
            if (!logFile.exists()) {
                logMessage("Log file does not exist, attempting to recreate it: " + logFile.getAbsolutePath());

            }

            logMessage("Starting log file transfer: " + logFile.getAbsolutePath());

            fis = new FileInputStream(logFile);
            OutputStream outputStream = socket.getOutputStream();
            byte[] buffer = new byte[1024];
            int bytesRead;

            while ((bytesRead = fis.read(buffer)) != -1) {
                outputStream.write(buffer, 0, bytesRead);
                outputStream.flush();
            }

            fis.close();
            logMessage("[O] Log file transfer completed");
        } catch (IOException e) {
            logMessage("[X] Error occurred during log file transfer: " + e.getMessage());
        } finally {
            waitSeconds(1000);

            if (logFile != null && logFile.exists()) {
                // FileWriter와 PrintWriter를 null로 초기화 시켜서 initLogFile()가 수행되도록 init
                initFileWriter();
                initPrintWriter();
                logFile.delete();
                logMessage("[O] Log file deleted and Writer reset successfully");
            }

            if (fis != null) {
                try {
                    fis.close();
                } catch (IOException e) { /* 무시 가능 */ }
            }

            socket.close();
            logMessage("8. Socket closed");
        }
    }

    public void rebootDevice() {
        try {
            Process process = Runtime.getRuntime().exec("ssu -c reboot");
            process.waitFor();
            logMessage("Device rebooting...");
        } catch (Exception e) {
            logMessage("Reboot failed: " + e.getMessage());
        }
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

    private void sendBytes(OutputStream outputStream, byte[] data, String message) throws IOException {
        outputStream.write(data);
        outputStream.flush();
        logMessage(message);
    }

    private byte receiveByte(InputStream inputStream) throws IOException {
        byte[] buffer = new byte[1];
        if (inputStream.read(buffer) > 0) return buffer[0];
        return -1;
    }

    private String findInstalledPackageName(String target, String nonTarget) {
        try {
            // ✅ pm list packages 실행
            Process process = Runtime.getRuntime().exec("pm list packages " + target);
            BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
            String line;

            while ((line = reader.readLine()) != null) {
                if (line.startsWith("package:")) {
                    String packageName = line.replace("package:", "").trim();

                    // kr.co.mirerotack.apkdownloader 패키지를 제외한 패키지 찾기
                    if (!packageName.equals(nonTarget)) {
                        return packageName; // 1개만 반환하고 종료
                    }
                }
            }
            reader.close();
        } catch (IOException e) {
            logMessage("[X] Error occurred while retrieving package list: " + e.getMessage());
        }

        return null; // ✅ 해당 패키지를 찾지 못한 경우
    }

    private ApkValidationResult ValidateAPK(String apkPath, boolean isForceUpdate) {
        PackageManager pm = context.getPackageManager();
        PackageInfo apkInfo = pm.getPackageArchiveInfo(apkPath, 0);

        if (apkInfo == null) {
            return new ApkValidationResult(false, "[X] APK may be corrupted (unable to retrieve package information)", UninstallResult.APK_CORRUPTED);

        }

        // APK 버전 정보 가져오기
        int apkVersionCode = apkInfo.versionCode;
        String apkPackageName = apkInfo.packageName;
        logMessage("Package name retrieved from APK: " + apkPackageName);

        // ✅ 현재 설치된 앱 정보 가져오기
        String installedAppPackageName = findInstalledPackageName(PackageBasePath, PackageBasePath + ".apkdownloader");  // 비교할 앱의 패키지명
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

        // ✅ 버전 비교 (versionCode 사용, API 28 이상에서는 versionName과 함께 비교 가능)
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
        int remainder = 1024 - (int) (expectedSize % 1024); // 3671001 % 1024 패딩 개수
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
                file.setLength(paddingStart); // ✅ Remove padding
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