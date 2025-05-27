package kr.co.mirerotack.btsever1.ymodemOverTcp;

import android.net.Uri;

import java.io.BufferedOutputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.Charset;
import java.util.Arrays;

import kr.co.mirerotack.btsever1.MainActivity;

import static kr.co.mirerotack.btsever1.ymodemOverTcp.Logger.logMessage;

public class YModem {
    private Modem modem;
    private MainActivity mainActivity; // UI 업데이트를 위한 참조
    private static final String TAG = "TCPCOM"; // 로그 태그

    private String fileName = null;
    public String getFileName() {
        return fileName;
    }
    private long expectedFileSize = -1;  // 예상 파일 크기 저장 변수
    private boolean isSyncDataMode = false;
    private boolean isRebootMode = false;
    private boolean isForceUpdateMode = false;

    public long getExpectedFileSize() {
        return expectedFileSize;
    }

    public boolean getIsSyncDataMode() { return isSyncDataMode; }
    public boolean getIsRebootMode() { return isRebootMode; }
    public boolean getIsForceUpdateMode() {
        return isForceUpdateMode;
    }

    String NULL = "\u0000";
    File filePath = null;
    private byte[] block;

    /**
     * 생성자: 입력 스트림과 출력 스트림을 받아서 초기화
     *
     * @param inputStream  수신을 위한 스트림
     * @param outputStream 송신을 위한 스트림
     */
    public YModem(InputStream inputStream, OutputStream outputStream) {
        this.modem = new Modem(inputStream, outputStream);
    }

    /**
     * 여러 파일을 배치 수신 (Batch Mode)
     * receive() 메서드를 반복 호출하여 여러 파일을 연속 수신
     *
     * @param directory 저장할 디렉토리
     * @throws IOException 수신 오류 발생 시 예외 처리
     */
//    public void receiveFilesInDirectory(File directory, boolean isHeader) throws Exception {
//        while (receive(directory, true, isHeader) != null) {
//        }
//    }

    /**
     * YModem 프로토콜을 사용하여 파일을 수신하는 주요 로직
     *
     * @param file       저장할 파일 또는 디렉토리 경로
     * @param inDirectory true일 경우, 디렉토리 내부에 파일을 생성 (단일 파일 모드일 경우 false)
     * @return 저장된 파일 객체
     * @throws IOException 수신 오류 발생 시 예외 처리
     */
    public File receive_Header(File file, boolean inDirectory) throws Exception {
        block = new byte[128];
        int errorCount = 0;

        try {
            // 📥 **YModem 헤더 블록 수신 (파일명 및 크기)**
            int character = modem.requestTransmissionStart();

            block = modem.readBlock(0, (character == Modem.SOH), new YModemCRC16(), 0, 128);
            String headerString = new String(block, Charset.forName("US-ASCII")).trim();
            logMessage("[O] Received header: " + headerString);

            // 🔹 NULL(b'\x00')을 기준으로 분할하여 원하는 데이터만 필터링
            // header: b'smartrtu.apk \x00 3654326 \x00 1 \x00 0 \x00(이후 NULL 반복)'
            String[] headerParts = headerString.split(NULL);

            if (headerParts.length < 5 || headerParts[1].trim().isEmpty()) {
                logMessage("[X] Header parsing failed! Invalid data: " + headerString);
                throw new IOException("Invalid YModem header: " + headerString);
            }

            try {
                fileName = headerParts[0].trim();                            // [0] 파일 이름
                expectedFileSize = Long.parseLong(headerParts[1].trim());    // [1] 파일 크기
                isSyncDataMode = headerParts[2].trim().equals("1");          // [2] 데이터 싱크 모드
                isRebootMode = headerParts[3].trim().equals("1");            // [3] RTU 기기 재부팅
                isForceUpdateMode = headerParts[4].trim().equals("1");       // [4] 강제 업데이트 활성화
            } catch (NumberFormatException e) {
                logMessage("[X] Failed to convert file size: " + headerParts[1]);
                throw new IOException("Invalid file size in header");
            }

            logMessage("[O] [Header] File name: " + fileName + ", Expected size: " + expectedFileSize + " bytes\n"
                + ", SyncData Mode: " + getIsSyncDataMode() + ", Reboot Mode: " + getIsRebootMode() + ", Force update: " + getIsForceUpdateMode());

            // 📌 파일 저장 경로 설정 (파일 생성 X, 데이터 수신 후 저장)
            if (inDirectory) {
                filePath = new File(file, fileName);
            } else {
                filePath = file;
            }

            Uri apkUri = Uri.fromFile(filePath);
            System.out.println("APK URI: " + apkUri.toString());

        } catch (Modem.InvalidBlockException e) {
            errorCount++;
            if (errorCount == Modem.MAXERRORS) {
                modem.interruptTransmission();
                throw new IOException("Transmission aborted, error count exceeded max");
            }
            modem.sendByte(Modem.NAK);
        } catch (Modem.RepeatedBlockException | Modem.SynchronizationLostException e) {
            modem.interruptTransmission();
            throw new IOException("Fatal transmission error", e);
        } catch (TimeoutException e) {
            throw new RuntimeException(e);
        } catch (FileNotFoundException e) {
            throw new FileNotFoundException(e.getMessage());
        } catch (Exception e) {
            throw new Exception(e.getMessage());
        }

        return filePath;
    }

    /// **데이터 블록 수신 (APK 본문)**
    public File receive_APK(File file, boolean ack_mode) throws Exception {
        block = new byte[1024];
        byte[] zeroBlock = new byte[block.length];
        DataOutputStream dataOutput = null;
        boolean useCRC16 = true;

        long receivedSize = 0;
        int packet_number = 0; // 3555번째 등 디버깅에만 사용됨

        try{
            logMessage("5-0. Starting APK data reception...");
            dataOutput = new DataOutputStream(new BufferedOutputStream(new FileOutputStream(filePath)));

            modem.resetBlockNumber(); // APK 데이터 수신 전 블록 번호 초기화
            int totalPacketSize = (int) ((expectedFileSize + 1023) / 1024);

            while (true) {
                int character = modem.readNextBlockStart(useCRC16); // // read SOH(1) or STX(1) or EOT(1)

                // 데이터 전송 종료 상태인지 == EOT 체크
                if (character == Modem.EOT) {
                    logMessage("6-2. [RX] EOT4 (End of Transmission)");
                    logMessage("6-3. [TX] ACK4 ");
                    modem.sendByte(Modem.ACK);
                    break; // EOF를 받았으므로 루프 종료
                }

                byte[] dataBlock = modem.readBlock(
                    modem.getBlockNumber(), (character == Modem.SOH), new YModemCRC16(), packet_number, totalPacketSize
                );

                if (dataBlock == null) {
                    logMessage("6-400. The data block of packet " + packet_number + " is null.");
                    throw new IOException("[X] 6-400. Data block reception error!");
                }

                if (Arrays.equals(dataBlock, zeroBlock)) {
                    logMessage("5-1. The data block of packet " + packet_number + " is all 0x00.");
                }

                packet_number += 1;
                modem.incrementBlockNumber(); // ✅ 블록 번호 증가

                // 🔽 파일에 데이터 저장
                dataOutput.write(dataBlock);
                receivedSize += dataBlock.length;

                // 📤 (APK 용량 3.6MB == 3700개 패킷) 1개 패킷을 수신할 때마다 `ACK` 전송 - 30초 이상 느려지지만, 안정성은 좋아짐
                if (ack_mode) {
                    modem.sendByte(Modem.ACK);
                    // logMessage("5-2. [TX] ACK"); // 실행시간 단축을 위해 주석처리
                }
            }
            // 📤 데이터 블록 전체 수신 후 `ACK` 전송
            modem.sendByte(Modem.ACK);
            logMessage("5-3. [RX] ACK");

            logMessage("[O] 7-1. File saved successfully: " + filePath.getAbsolutePath() + " (" + receivedSize + " bytes)");
        } catch (IOException e) {
            logMessage("[TX] NAK, IOException" + e);
            modem.sendByte(modem.NAK);
            throw new IOException(e);
        } catch (TimeoutException e) {
            logMessage("[TX] NAK, TimeoutException" + e);
            modem.sendByte(modem.NAK);
            throw new TimeoutException();
        } catch (Modem.RepeatedBlockException e) {
            logMessage("[TX] NAK, RepeatedBlockException" + e);
            modem.sendByte(modem.NAK);
            throw new Modem.RepeatedBlockException("RepeatedBlockException");
        } catch (Modem.SynchronizationLostException e) {
            logMessage("[TX] NAK, SynchronizationLostException" + e);
            modem.sendByte(modem.NAK);
            throw new Modem.SynchronizationLostException("SynchronizationLostException");
        } catch (Modem.InvalidBlockException e) {
            logMessage("[TX] NAK, InvalidBlockException : Packet format mismatch" + e);
            modem.sendByte(modem.NAK);
            throw new Modem.InvalidBlockException("InvalidBlockException");
        } catch (Exception e) {
            logMessage("[TX] NAK, 알 수 없는 오류 발생 : " + e);
            modem.sendByte(modem.NAK);
            throw new Exception(e.getMessage());
        } finally {
            if (dataOutput != null) {
                dataOutput.close();
            }
        }

        return filePath;
    }
}