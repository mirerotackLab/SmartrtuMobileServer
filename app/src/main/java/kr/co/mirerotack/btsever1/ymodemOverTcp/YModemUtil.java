package kr.co.mirerotack.btsever1.ymodemOverTcp;

import android.util.Log;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.RandomAccessFile;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class YModemUtil {
    // 인스턴스화 방지 (private 생성자)
    private YModemUtil() {
        throw new UnsupportedOperationException("The Util class cannot be instantiated.");
    }

    public static void logReceivedPacket(byte[] packet, int packetNumber, int totalPacketSize) {
        int packetSize = packet.length;
        String hexPreview = toHexString(packet, 10, 10); // 앞 20바이트 + 뒤 20바이트 Hex 변환

        if (packetSize == 128) {
            Logger.logMessage(String.format("3-2. [RX] Progress: 100%% | Packet Number: %d", packetNumber, packetSize));
//            logMessage(String.format(EmojiSample.RX + "3-2. [RX] 진행률: 100%% | %d번 째 | packet(%d) : %s", packetNumber, packetSize, hexPreview));
        } else if (packetSize == 1024) {
            Logger.logMessage(String.format("5-2. [RX] Progress: %.1f%% | Packet Number: %d", ((packetNumber + 1) / (float) totalPacketSize) * 100, packetNumber));
//            logMessage(String.format(EmojiSample.RX + "5-2. [RX] 진행률: %.1f%% | %d번 째 | packet(%d) : %s", (packetNumber / totalPacketSize) * 100.0, packetNumber, packetSize, hexPreview));
        } else {
            Logger.logMessage("[Error] Invalid packet size, packetSize: " + packetSize);
        }
    }

    public static String toHexString(byte[] data, int prefixLength, int suffixLength) {
        if (data == null) return "null";
        if (data.length == 0) return "";

        int dataLength = data.length;
        StringBuilder sb = new StringBuilder();

        // 앞 20바이트 (prefixLength만큼)
        int prefixEnd = Math.min(prefixLength, dataLength);
        for (int i = 0; i < prefixEnd; i++) {
            sb.append(String.format("%02x", data[i]));
        }

        sb.append("...");  // 중간 생략 표시

        // 뒤 20바이트 (suffixLength만큼)
        int suffixStart = Math.max(dataLength - suffixLength, prefixEnd);
        for (int i = suffixStart; i < dataLength; i++) {
            sb.append(String.format("%02x", data[i]));
        }

        return sb.toString();
    }
}

class Timer {

    private long startTime = 0;
    private long stopTime = 0;
    private long timeout = 0;

    public Timer(long timeout) {
        this.timeout = timeout;
    }

    public Timer start() {
        this.startTime = System.currentTimeMillis();
        this.stopTime = 0;
        return this;
    }

    public void stop() {
        this.stopTime = System.currentTimeMillis();
    }

    public boolean isExpired() {
        return (System.currentTimeMillis() > startTime + timeout);
    }

    public long getStartTime() {
        return this.startTime;
    }

    public long getStopTime() {
        return this.stopTime;
    }

    public long getTotalTime() {
        return this.stopTime - this.startTime;
    }

    public long getTimeout() {
        return timeout;
    }

    public void setTimeout(long timeout) {
        this.timeout = timeout;
    }

    public boolean isWorking() {
        return (stopTime != 0);
    }
}

class Logger {
    private static final String TAG = "TCPCOM";
    public static final String logDirectory = "/data/data/kr.co.mirerotack.btsever1/files";  // ✅ 로그 디렉토리 경로
    private static String logFilePath = null;
    private static FileWriter fileWriter = null;
    private static PrintWriter printWriter = null;
    private static final long MAX_LOG_SIZE = 10 * 1024 * 1024; // 10MB
    private static final long LATEST_KEEP_LOG_SIZE = 100 * 1024; // 마지막 100KB는 유지 후, reset

    // ✅ 클래스 로딩 시 자동으로 로그 파일명 설정
    static {
        setLogFilePath();
        initLogFile();
    }

    public static void initFileWriter() throws IOException {
        if (fileWriter == null) {
            return;
        }

        fileWriter.close();
        fileWriter = null;
    }

    public static void initPrintWriter() throws IOException {
        if (printWriter == null) {
            return;
        }

        printWriter.close();
        printWriter = null;
    }

    public static void initLogFile() {
        try {
            if (fileWriter == null || printWriter == null) {
                File dir = new File(logDirectory);
                if (!dir.exists() && !dir.mkdirs()) {
                    Log.e(TAG, "[X] Failed to create log directory!");
                    return;
                }

                File file = new File(getLogFilePath());
                fileWriter = new FileWriter(file, true);
                printWriter = new PrintWriter(fileWriter);
                // logMessage("[O] Log file initialization completed: " + getLogFilePath());
            }
        } catch (IOException e) {
            Log.e(TAG, "[X] Log file initialization failed: " + e.getMessage());
        }
    }

    private static File checkSizeOfLogFile() {
        File logFile = new File(getLogFilePath());

        if (!logFile.exists() || logFile.length() <= MAX_LOG_SIZE) {
            return logFile; // 로그 파일이 존재하지 않거나 크기가 작으면 그대로 유지
        }

        RandomAccessFile raf = null;
        try {
            raf = new RandomAccessFile(logFile, "rw");
            long fileSize = raf.length();
            long keepSize = LATEST_KEEP_LOG_SIZE;
            long startPos = Math.max(0, fileSize - keepSize); // 파일 끝에서 10KB 앞 지점

            // 새로운 파일 내용을 저장할 바이트 배열
            byte[] buffer = new byte[(int) (fileSize - startPos)];
            raf.seek(startPos);
            raf.readFully(buffer);

            // 기존 파일(10MB 이상)을 비우고, 마지막 100KB 데이터만 유지
            raf.setLength(0);
            raf.seek(0);
            raf.write(buffer);

            logMessage("[W] The log file is too large (" + (MAX_LOG_SIZE/1024)/1024 + " MB) and has been reset. (Only the last " + LATEST_KEEP_LOG_SIZE/1024 + " KB are kept)");
        } catch (IOException e) {
            logMessage("[X] Error occurred while initializing the log file: " + e.getMessage());
        } finally {
            // API 15에서는 try-with-resources가 없으므로 수동으로 닫아야 함
            if (raf != null) {
                try {
                    raf.close();
                } catch (IOException e) {
                    logMessage("[X] Failed to close the log file: " + e.getMessage());
                }
            }
        }
        return logFile;
    }


    private static void setLogFilePath() {
        SimpleDateFormat sdf = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault());
        String timestamp = sdf.format(new Date());
        logFilePath = logDirectory + "/btsever1_" + timestamp + ".txt";
    }

    public static String getLogFilePath() {
        // ✅ logFileName이 비어 있으면 자동 초기화
        if (logFilePath == null || logFilePath.trim().isEmpty()) {
            setLogFilePath();
        }
        return logFilePath;
    }

    // ✅ 현재 시간을 "yyyy-MM-dd HH:mm:ss.SSS" 형식으로 변환 (한국 시간 기준)
    static String getCurrentTimestamp() {
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS");
        // sdf.setTimeZone(TimeZone.getTimeZone("Asia/Seoul"));  // ✅ 한국 시간(KST) 적용
        return sdf.format(new Date());
    }

    public static void logMessage(String message) {
        try {
            if (printWriter != null) {
                if (message.isEmpty()) {
                    message = "[NO OUTPUT]";
                }

                String timestamp = getCurrentTimestamp();  // ✅ 한국 시간 기반 타임스탬프 가져오기
                printWriter.println("[" + timestamp + "] " + message);
                printWriter.flush(); // ✅ 즉시 기록

                if (message.contains("null") || message.contains("[X]")) {
                    Log.e(TAG, "[" + timestamp + "] " + message);  // 콘솔 에러 로그 출력(확인용)
                } else {
                    Log.d(TAG, "[" + timestamp + "] " + message);  // 콘솔 정상 로그 출력
                }

            }
        } catch (Exception e) {
            Log.e(TAG, "[X] Log save failed: " + e.getMessage());
        }
    }

    public static void closeLogger() {
        try {
            if (printWriter != null) {
                printWriter.close();
                printWriter = null;
            }
            if (fileWriter != null) {
                fileWriter.close();
                fileWriter = null;
            }
        } catch (IOException e) {
            Log.e(TAG, "[X] Failed to close the log file: " + e.getMessage());
        }
    }
}