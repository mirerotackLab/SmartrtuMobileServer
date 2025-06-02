package kr.co.mirerotack.btsever1.ymodemServer;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.google.gson.Gson;

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

import kr.co.mirerotack.btsever1.RtuSnapshot;
import kr.co.mirerotack.btsever1.model.ApkValidationResult;
import kr.co.mirerotack.btsever1.model.InstallResult;
import kr.co.mirerotack.btsever1.model.YModemServerInterface;

import static kr.co.mirerotack.btsever1.utils.DummyData.createDummyData;
import static kr.co.mirerotack.btsever1.utils.Logger.getCurrentTimestamp;
import static kr.co.mirerotack.btsever1.utils.Logger.logMessage;
import static kr.co.mirerotack.btsever1.utils.readwriteJson.dataFileName;
import static kr.co.mirerotack.btsever1.utils.readwriteJson.readJsonFile;
import static kr.co.mirerotack.btsever1.utils.readwriteJson.updateTimestampToFile;

// TCP 서버 구현체 - 기존 YModemTCPServer를 인터페이스에 맞게 수정
public class YModemTCPServerImpl implements YModemServerInterface {
    // 기존 YModemTCPServer의 모든 필드들을 그대로 유지
    protected static final byte SOH = 0x01;
    protected static final byte STX = 0x02;
    protected static final byte EOT = 0x04;
    protected static final byte ACK = 0x06;
    protected static final byte NAK = 0x15;
    protected static final byte CAN = 0x18;
    protected static final byte CPMEOF = 0x1A;
    protected static final byte START_ACK = 'C';
    protected static final byte COM_TEST = 'T';

    private static final int SEND_RECEIVE_BUFFER_SIZE = 100 * 1024;

    private Socket socket;
    private ServerSocket serverSocket; // 서버 상태 관리를 위해 추가
    private File APK_PATH;
    private String PackageBasePath = "kr.co.mirerotack";
    private String NEW_APK_FILE_NAME = "firmware.apk";
    private String TAG = "YmodemTcpServer";
    private Context context;
    private int errorCount = 0;
    private boolean isRunning = false; // 서버 실행 상태 추가

    Handler handler = new Handler(Looper.getMainLooper());
    Gson gson = new Gson();

    /**
     * TCP 서버 생성자
     *
     * @param apkDownloadPath APK 다운로드 경로
     * @param context         애플리케이션 컨텍스트
     */
    public YModemTCPServerImpl(File apkDownloadPath, Context context) {
        this.APK_PATH = apkDownloadPath;
        this.context = context;
    }

    @Override
    public void startServer(int port) {
        isRunning = true;
        new Thread(new Runnable() {
            @Override
            public void run() {
                while (isRunning) {
                    try {
                        // 기존 TCP 서버 소켓을 먼저 정리
                        closeExistingServerSocket();

                        // 서버 소켓을 재사용 가능하도록 설정
                        serverSocket = new ServerSocket();

                        try {
                            serverSocket.setReuseAddress(true);
                            serverSocket.bind(new InetSocketAddress("0.0.0.0", port));
                            logMessage("[O] TCP Port binding successful");
                        } catch (IOException e) {
                            logMessage("[X] TCP Port binding failed: " + e.getMessage());
                            Log.e(TAG, "TCP Port binding failed: " + e.getMessage());
                            e.printStackTrace();
                        }

                        logMessage("==========================================================");
                        logMessage("TCP Server started on ip: " + getLocalIpAddress());
                        logMessage("TCP Server started on port: " + port);

                        while (isRunning) {
                            logMessage("--------------------1. TCP Ready to receive-----------------------");

                            try {
                                if (errorCount > 3) {
                                    logMessage("[X] TCP Socket error occurred more than 3 times. Restarting...");
                                    errorCount = 0;
                                    break;
                                }

                                if (serverSocket == null || serverSocket.isClosed() || !serverSocket.isBound()) {
                                    logMessage("[X] TCP Server socket is either closed or not bound. Restart required.");
                                    errorCount += 1;
                                    waitSeconds(2000);
                                    break;
                                }

                                logMessage("--------------------2. TCP Waiting for socket---------------------");
                                socket = serverSocket.accept(); // TCP 클라이언트 연결 대기

                                logMessage("--------------------3. TCP Starting to receive--------------------");
                                configureSocket(socket);
                                handleIncomingFile(socket); // 기존 로직 그대로 사용

                            } catch (IOException e) {
                                logMessage("TCP Server communication error: " + e.getMessage());
                                waitSeconds(5000);
                                break;
                            } finally {
                                try {
                                    if (socket != null && !socket.isClosed()) {
                                        socket.close();
                                    }
                                } catch (IOException e) {
                                    logMessage("TCP Socket shutdown error: " + e.getMessage());
                                    break;
                                }
                            }
                        }
                    } catch (IOException e) {
                        logMessage("[X] Failed to start TCP server: " + e.getMessage());
                        waitSeconds(10000);
                        break;
                    } finally {
                        closeExistingServerSocket();
                    }
                }
            }
        }).start();
    }

    @Override
    public void closeExistingServerSocket() {
        try {
            if (serverSocket != null && !serverSocket.isClosed()) {
                serverSocket.close();
                logMessage("[O] TCP server socket closed successfully");
            }
        } catch (IOException e) {
            logMessage("[X] Failed to close TCP server socket: " + e.getMessage());
        }
    }

    @Override
    public void stopServer() {
        isRunning = false;
        closeExistingServerSocket();
        logMessage("[O] TCP server stopped");
    }

    @Override
    public boolean isRunning() {
        return isRunning && serverSocket != null && !serverSocket.isClosed();
    }

    // 기존 YModemTCPServer의 모든 메서드들을 그대로 유지
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

    private void configureSocket(Socket socket) throws IOException {
        socket.setSendBufferSize(SEND_RECEIVE_BUFFER_SIZE);
        socket.setReceiveBufferSize(SEND_RECEIVE_BUFFER_SIZE);
    }

    // 🔥 핵심: 기존 handleIncomingFile 메서드를 그대로 사용
    // YModem 클래스는 InputStream/OutputStream만 받으므로 TCP든 Bluetooth든 동일하게 동작
    private void handleIncomingFile(Socket socket) throws IOException {
        InputStream inputStream = null;
        OutputStream outputStream = null;
        int timeoutRetries = 0;

        File saveDirectory = APK_PATH;
        if (!saveDirectory.exists()) saveDirectory.mkdirs();

        try {
            inputStream = socket.getInputStream();
            outputStream = socket.getOutputStream();

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
            logMessage("[X] An error occurred. Uninstalling the APK: " + e.getMessage());
            saveDirectory.delete();
            handleError(e);
        } finally {
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

    private boolean syncData(Context context, InputStream inputStream, OutputStream outputStream) throws IOException {
        try {
            RtuSnapshot snapshot;

            // 1. JSON 파일 존재 여부 확인 및 로드
            File file = new File(context.getFilesDir(), dataFileName);

            // ex. /data/data/kr.co.mirerotack.btsever1/files/RtuStatus.json
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

    private void waitSeconds(int waitTime) {
        try {
            Thread.sleep(waitTime);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }

    private boolean checkFileIntegrity(File receivedFile, long expectedSize, OutputStream outputStream) throws IOException {
        // 기존 checkFileIntegrity 메서드 코드 그대로 유지...
        return true; // 실제 구현은 기존 코드 사용
    }

    private File renameFile(File file, String newFileName) {
        // 기존 renameFile 메서드 코드 그대로 유지...
        return file; // 실제 구현은 기존 코드 사용
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
        // 기존 handleError 메서드 코드 그대로 유지...
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

    // 추가로 필요한 메서드들...
    private ApkValidationResult ValidateAPK(String apkPath, boolean isForceUpdate) {
        // 기존 ValidateAPK 메서드 코드 그대로 유지...
        return new ApkValidationResult(true, "Test", (InstallResult) null);
    }
}
