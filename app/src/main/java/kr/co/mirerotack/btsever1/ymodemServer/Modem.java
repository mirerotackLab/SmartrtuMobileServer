package kr.co.mirerotack.btsever1.ymodemServer;

import android.util.Log;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

import kr.co.mirerotack.btsever1.utils.Logger;
import kr.co.mirerotack.btsever1.utils.TimeoutException;
import kr.co.mirerotack.btsever1.utils.Timer;
import kr.co.mirerotack.btsever1.utils.YModemCRC16;

import static kr.co.mirerotack.btsever1.utils.Logger.logMessage;

/**
 * This is core Modem class supporting XModem (and some extensions XModem-1K, XModem-CRC), and YModem.<br/>
 * YModem support is limited (currently block 0 is ignored).<br/>
 * <br/>
 * Created by Anton Sirotinkin (aesirot@mail.ru), Moscow 2014 <br/>
 * I hope you will find this program useful.<br/>
 * You are free to use/modify the code for any purpose, but please leave a reference to me.<br/>
 */
public class Modem {

    protected static final byte SOH = 0x01; /* Start Of Header 128바이트 패킷 시작 */
    protected static final byte STX = 0x02; /* Start Of Text 1024바이트 패킷 시작 */
    public static final byte EOT = 0x04; /* 전송 종료n */
    protected static final byte ACK = 0x06; /* 수신 확인 */
    protected static final byte NAK = 0x15; /* 오류 발생 */
    protected static final byte CAN = 0x18; /* 취소? */

    protected static final byte CPMEOF = 0x1A; /* 마지막 패딩 */
    protected static final byte START_ACK = 'C'; /* YModem 프로토콜 시작 신호 = 'C' */

    protected static final int MAXERRORS = 10;

    protected static final int BLOCK_TIMEOUT = 10_000;
    protected static final int REQUEST_TIMEOUT = 3_000;
    protected static final int WAIT_FOR_RECEIVER_TIMEOUT = 60_000;
    protected static final int SEND_BLOCK_TIMEOUT = 10_000;

    private final String TAG = "TCPCOM";

    private final InputStream inputStream;
    private final OutputStream outputStream;

    private final byte[] shortBlockBuffer;
    private final byte[] longBlockBuffer;

    /**
     * Constructor
     *
     * @param inputStream  stream for reading received data from other side
     * @param outputStream stream for writing data to other side
     */
    protected Modem(InputStream inputStream, OutputStream outputStream) {
        this.inputStream = inputStream;
        this.outputStream = outputStream;
        shortBlockBuffer = new byte[128];
        longBlockBuffer = new byte[512];
    }

    private int blockNumber = 0; // 📌 블록 번호 변수 추가

    public void resetBlockNumber() {
        this.blockNumber = 0;
    }

    public int getBlockNumber() {
        return blockNumber;
    }

    public void incrementBlockNumber() { // 일반적
        blockNumber = (blockNumber + 1) % 256;  // ✅ 8비트 순환 유지
    }

    public void reduceBlockNumber() { // CRC 오류로 인해 재송신이 필요할 때,
        blockNumber = (blockNumber - 1) % 256;  // ✅ 8비트 순환 유지
    }

    protected void sendByte(byte b) throws IOException {
        outputStream.write(b);
        outputStream.flush();
    }

    /**
     * Request transmission start and return first byte of "first" block from sender (block 1 for XModem, block 0 for YModem)
     *
     * @return
     * @throws IOException
     */
    protected int sendStartSignal() throws IOException, TimeoutException {
        int character;
        int errorCount = 0; // 오류 횟수 카운트

        // 1. 송신자의 첫 번째 데이터 블록 수신 대기
        Timer timer = new Timer(REQUEST_TIMEOUT); // 타임아웃 타이머 설정

        while (errorCount < MAXERRORS) {
            // 📤 전송 시작 요청 (송신자가 응답할 때까지 반복 전송)
            sendByte(START_ACK);
            logMessage("1-1. [TX] C");

            timer.start(); // 타이머 시작

            character = readByte(timer); // 📥 송신자로부터 응답 수신
            if (character == 'C') {
                logMessage("2-2. [RX] C");
            }

            try {
                while (true) {
                    character = readByte(timer); // 📥 송신자로부터 응답 수신

                    if (character == SOH || character == STX) {
                        // 📌 송신자가 데이터 블록 전송을 시작하면 해당 블록 타입(SOH/STX)을 반환
                        return character;
                    }
                }
            } catch (TimeoutException ignored) {
                // 📌 타임아웃 발생 시 재시도
                errorCount++;
            }
        }

        // 2. 최대 재시도 횟수를 초과하면 전송 실패 처리
        interruptTransmission();
        throw new RuntimeException("Timeout, no data received from transmitter");
    }

    protected int readNextBlockStart(boolean lastBlockResult) throws IOException, InvalidBlockException {
        int character = -1;
        int errorCount = 0;
        Timer timer = new Timer(BLOCK_TIMEOUT);
        while (true) {
            timer.start();
            try {
                while (true) {
                    character = readByte(timer);

                    if (character == SOH || character == STX || character == EOT) {
                        return character;
                    } else {
                        logMessage("[X] SOH, STX, EOT가 아닌 " + character + "가 들어옴.");
                    }
                }
            } catch (TimeoutException ignored) {
                // repeat last block result and wait for next block one more time
                if (++errorCount < MAXERRORS) {
                    sendByte(lastBlockResult ? ACK : NAK);
                    logMessage("100. [TX] " + (lastBlockResult ? "ACK" : "NAK") + ", First byte of data block is corrupted");
                    throw new InvalidBlockException("InvalidBlockException: The first byte (character) of the packet is not SOH, STX, or EOT -> character: " + character);
                } else {
                    interruptTransmission();
                    throw new RuntimeException("Timeout, no data received from transmitter");
                }
            }
        }
    }

    private void shortSleep() {
        try {
            Thread.sleep(10);
        } catch (InterruptedException e) {
            try {
                interruptTransmission();
            } catch (IOException ignore) {
            }
            throw new RuntimeException("Transmission was interrupted", e);
        }
    }

    /**
     * send CAN to interrupt seance
     *
     * @throws IOException
     */
    protected void interruptTransmission() throws IOException {
        sendByte(CAN);
        sendByte(CAN);
    }

    /**
     * YModem 프로토콜에서 하나의 데이터 블록을 읽는 함수
     *
     * @param blockNumber 현재 읽어야 할 블록 번호 (0부터 시작)
     * @param shortBlock  128바이트(SOH) 또는 1024바이트(STX) 블록 여부, 사실상 isHeader와 값동일
     * @param YModemCrc16 CRC16 체크 방식
     * @return 수신된 데이터 블록 (byte 배열)
     * @throws IOException                  입출력 예외 발생 시
     * @throws TimeoutException             타임아웃 발생 시
     * @throws RepeatedBlockException       동일한 블록이 중복 수신될 경우
     * @throws SynchronizationLostException 블록 동기화 오류 발생 시
     * @throws InvalidBlockException        블록 데이터 오류 발생 시
     */
    // STX(1)는 이미 읽고 호출함 + 블록번호(1) + 블록번호 보수(1) + 데이터(1024) + CRC(2)
    protected byte[] readBlock(int blockNumber, boolean shortBlock, YModemCRC16 YModemCrc16, int packet_number, int totalPacketSize)
            throws IOException, TimeoutException, RepeatedBlockException, SynchronizationLostException, InvalidBlockException {

        // 📌 1. 블록 버퍼 할당 (128바이트 or 1024바이트)
        byte[] block;
        Timer timer = new Timer(BLOCK_TIMEOUT).start(); // 타임아웃 설정

        if (shortBlock) {
            block = shortBlockBuffer; // 128바이트 버퍼
        } else {
            block = longBlockBuffer; // 1024바이트 버퍼
        }

        // 📌 2. 블록 번호 수신 (보낸 블록과 동일해야 함)
        byte character = readByte(timer); // read 블록번호(1)

        if ((character & 0xFF) == blockNumber - 1) {
            // 📌 같은 블록을 반복 수신하면, 이전 ACK 손실 가능성 있음
            Log.e(TAG, "5-601. character == blockNumber - 1 -> " + (character & 0xFF) + " == " + (blockNumber - 1));
            Log.e(TAG, "5-601. Previous block received repeatedly");
            throw new RepeatedBlockException("RepeatedBlockException : 5-601. Previous block received repeatedly");
        }

        // 📌 블록 번호가 일치하지 않으면, 데이터 동기화 오류 (패킷 손실 가능)
        if ((character & 0xFF) != blockNumber) {  // `character`를 다시 읽지 않음
            Log.e(TAG, "5-602. (character & 0xFF) != blockNumber -> " + (character & 0xFF) + " != " + blockNumber);
            Log.e(TAG, "5-602. Block number mismatch");
            throw new SynchronizationLostException("SynchronizationLostException : 5-602. Block number mismatch");
        }

        // 📌 3. 블록 번호 보정 (보낸 블록 번호의 1의 보수 값)
        byte character1 = readByte(timer); // read 보수(1)

        // 📌 보정 값이 일치하지 않으면 데이터 오류
        if ((character1 & 0xFF) != (~blockNumber & 0xFF)) {
            Log.e(TAG, "5-603. (character & 0xFF) != (~blockNumber & 0xFF) " + (character1 & 0xFF) + " != " + (~blockNumber & 0xFF));
            Log.e(TAG, "5-603. Correction value mismatch");
            throw new InvalidBlockException("InvalidBlockException : 5-603. Correction value mismatch");
        }

        // 📌 4. 실제 데이터 블록 수신
        // Java의 InputStream.read(byte[], int, int)는 최대 length 바이트를 읽을 뿐,
        // 실제로는 버퍼에 도착한 데이터만큼만 읽고 루프를 종료함
        // 특히 TCP는 스트림 기반이라 1024B 단위로 딱딱 떨어지지 않음
        // inputStream.read(block, 0, block.length);            -> 실제로 block.length 만큼 읽는 것을 보장하지 않는다.
        readFully(inputStream, block, 0, block.length);   // 정확히 1KB가 채워져야 종료됨

        int unit = Math.max(1, (totalPacketSize + 2) / 10);
        if (packet_number % unit == 0 || packet_number + 1 == totalPacketSize) {
            Logger.logReceivedPacket(block, packet_number, totalPacketSize);
        }

        // 📌 5. CRC 검증 (데이터 무결성 확인)
        int crcBytesRead = 0;
        byte[] crcBuffer = new byte[YModemCrc16.getCRCLength()];
        while (crcBytesRead < YModemCrc16.getCRCLength()) {
            crcBuffer[crcBytesRead] = readByte(timer); // read CRC(2)
            crcBytesRead++;
        }

        long calculatedCRC = YModemCrc16.calcCRC(block);
        long receivedCRC = (crcBuffer[0] << 8) | (crcBuffer[1] & 0xFF); // CRC 2바이트 조합

        if (receivedCRC < 0) { // 음수값 보정 (unsigned short 변환)
            receivedCRC = receivedCRC & 0xFFFF;
        }

        // Log.d(TAG, "calculatedCRC : " + calculatedCRC);
        // Log.d(TAG, "receivedCRC : " + receivedCRC);

        if (calculatedCRC != receivedCRC) {
            logMessage("5-604. CRC mismatch in packet " + packet_number + ", Data corrupted");
            logMessage("5-604. Expected CRC: " + calculatedCRC + ", Received CRC: " + receivedCRC);
            throw new InvalidBlockException("InvalidBlockException : 5-604. CRC mismatch, Data corrupted");

//            logMessage(EmojiSample.TX + "5-604. [TX] NAK");
//            sendByte(NAK);
//            reduceBlockNumber();
        }

        return block; // 📌 최종적으로 검증된 데이터 블록 반환
    }

    private byte readByte(Timer timer) throws IOException, TimeoutException {
        while (true) {
            if (inputStream.available() > 0) {
                int b = inputStream.read();
                return (byte) b;
            }
            if (timer.isExpired()) {
                throw new TimeoutException();
            }
            shortSleep();
        }
    }

    /**
     * 지정한 길이만큼 데이터를 InputStream에서 모두 읽어올 때까지 반복 수행합니다.
     * InputStream.read()는 요청한 바이트 수만큼 항상 읽지 않기 때문에, 전체 데이터를 확보할 때 필요합니다.
     *
     * @param in     InputStream (데이터를 읽을 대상)
     * @param buffer 데이터를 저장할 배열
     * @param offset 읽기 시작 위치
     * @param length 읽어야 할 총 바이트 수
     * @throws IOException 스트림이 예기치 않게 닫히거나 읽기 실패 시
     */
    private void readFully(InputStream in, byte[] buffer, int offset, int length) throws IOException {
        int totalRead = 0;
        while (totalRead < length) {
            int bytesRead = in.read(buffer, offset + totalRead, length - totalRead);
            if (bytesRead == -1) throw new IOException("Stream closed before reading fully");
            totalRead += bytesRead;
        }
    }


    static class RepeatedBlockException extends Exception {
        public RepeatedBlockException(String errorMsg) {
            super(errorMsg);
        }
    }

    static class SynchronizationLostException extends Exception {
        public SynchronizationLostException(String errorMsg) {
            super(errorMsg);
        }
    }

    static class InvalidBlockException extends Exception {
        public InvalidBlockException(String errorMsg) {
            super(errorMsg);
        }
    }
}