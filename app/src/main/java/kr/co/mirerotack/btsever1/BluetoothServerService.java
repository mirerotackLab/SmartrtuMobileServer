package kr.co.mirerotack.btsever1;

import android.app.Service;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothServerSocket;
import android.bluetooth.BluetoothSocket;
import android.content.Intent;
import android.os.IBinder;
import android.util.Log;

import com.google.gson.Gson;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Date;
import java.util.Locale;
import java.util.Set;
import java.util.TimeZone;
import java.util.UUID;

import static android.support.v4.app.ActivityCompat.startActivityForResult;


public class BluetoothServerService extends Service {

    private static final String TAG = "BluetoothServerService";
    private static final String SERVICE_NAME = "MyBluetoothServer";
    private static final UUID SERVICE_UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB"); // SPP 표준 UUID

    private BluetoothServerSocket serverSocket;
    private BluetoothSocket clientSocket;
    private final IBinder binder = new LocalBinder();
    private AcceptThread acceptThread;
    private ConnectedThread connectedThread;

    // 연결 상태 플래그
    private boolean isConnected = false;

    @Override
    public void onCreate() {
        super.onCreate();
        startAcceptThread();
        sendMessageToUI("서버 시작됨"); // ✅ 서버 시작 알림
    }

    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }

    public class LocalBinder extends android.os.Binder {
        public BluetoothServerService getService() {
            return BluetoothServerService.this;
        }
    }

    private void startAcceptThread() {
        // 이미 실행 중인 스레드가 있으면 중지
        if (acceptThread != null) {
            acceptThread.cancel();
        }

        acceptThread = new AcceptThread();
        acceptThread.start();
    }

    /**
     * 클라이언트 연결 요청을 수락하는 스레드
     */
    private class AcceptThread extends Thread {
        private boolean running = true;

        @Override
        public void run() {
            BluetoothAdapter bluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
            if (bluetoothAdapter == null) {
                sendMessageToUI("Bluetooth 어댑터 없음");
                Log.e(TAG, "Bluetooth 어댑터를 찾을 수 없음");
                return;
            }
            if (!bluetoothAdapter.isEnabled()) {
                sendMessageToUI("Bluetooth 꺼져 있음");
                Log.e(TAG, "Bluetooth가 꺼져 있음");
                return;
            }

            // 연결된 페어링 장치 로깅
            Set<BluetoothDevice> bondedDevices = bluetoothAdapter.getBondedDevices();
            for (BluetoothDevice device : bondedDevices) {
                Log.d(TAG, "Paired device: " + device.getName() + ", " + device.getAddress());
            }

            // 연결 수락 무한 루프 - 연결이 끊어지면 다시 수락 대기
            while (running) {
                try {
                    // 이전 서버 소켓이 있으면 닫기
                    if (serverSocket != null) {
                        try {
                            serverSocket.close();
                        } catch (IOException e) {
                            Log.e(TAG, "이전 서버 소켓 닫기 실패", e);
                        }
                    }

                    // 새 서버 소켓 생성
                    serverSocket = bluetoothAdapter.listenUsingRfcommWithServiceRecord(SERVICE_NAME, SERVICE_UUID);
                    sendMessageToUI("서버 소켓 생성 성공, 연결 대기 중...");
                    Log.d(TAG, "서버 소켓 생성 성공, 연결 대기 중...");

                    // 연결 수락 (블로킹 호출)
                    clientSocket = serverSocket.accept();

                    if (clientSocket != null) {
                        synchronized (BluetoothServerService.this) {
                            isConnected = true;
                            sendMessageToUI("클라이언트 연결 성공: " + clientSocket.getRemoteDevice().getName());
                            Log.d(TAG, "클라이언트 연결 성공: " + clientSocket.getRemoteDevice().getName());

                            // 이전 연결 스레드가 있으면 중지
                            if (connectedThread != null) {
                                connectedThread.cancel();
                            }

                            // 새 연결 스레드 시작
                            connectedThread = new ConnectedThread(clientSocket);
                            connectedThread.start();

                            // 서버 소켓 닫기 (한 번에 하나의 연결만 처리)
                            try {
                                serverSocket.close();
                            } catch (IOException e) {
                                Log.e(TAG, "서버 소켓 닫기 실패", e);
                            }
                        }
                    }
                } catch (IOException e) {
                    if (running) {
                        sendMessageToUI("클라이언트 연결 중 오류 발생, 재시도 중...");
                        Log.e(TAG, "accept() 에러, 재시도 중...", e);

                        // 잠시 대기 후 재시도
                        try {
                            Thread.sleep(3000);
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
                if (serverSocket != null) {
                    serverSocket.close();
                }
            } catch (IOException e) {
                Log.e(TAG, "AcceptThread 취소 중 오류", e);
            }
        }
    }

    /**
     * 연결된 클라이언트와 통신하는 스레드
     */
    private class ConnectedThread extends Thread {
        private final BluetoothSocket socket;
        private final InputStream inputStream;
        private final OutputStream outputStream;
        private boolean running = true;

        public ConnectedThread(BluetoothSocket socket) {
            this.socket = socket;
            InputStream tmpIn = null;
            OutputStream tmpOut = null;

            try {
                tmpIn = socket.getInputStream();
                tmpOut = socket.getOutputStream();
            } catch (IOException e) {
                Log.e(TAG, "소켓 스트림 얻기 실패", e);
                sendMessageToUI("소켓 스트림 얻기 실패");
            }

            inputStream = tmpIn;
            outputStream = tmpOut;
        }

        @Override
        public void run() {
            byte[] buffer = new byte[1024];
            int bytes;

            // 연결이 유지되는 동안 입력 스트림 모니터링
            while (running) {
                try {
                    // 입력 스트림에서 데이터 읽기 (블로킹 호출)
                    bytes = inputStream.read(buffer);
                    if (bytes > 0) {
                        // 수신된 명령 처리
                        final String receivedMessage = new String(buffer, 0, bytes, "UTF-8");

                        Log.d(TAG, "수신된 메시지: " + receivedMessage);
                        sendMessageToUI("수신된 메시지: " + receivedMessage);
                    }
                } catch (IOException e) {
                    Log.e(TAG, "데이터 수신 중 오류", e);
                    sendMessageToUI("데이터 수신 중 오류 발생");

                    // 연결 종료 처리
                    synchronized (BluetoothServerService.this) {
                        isConnected = false;
                    }

                    // 소켓 닫기
                    cancel();

                    // AcceptThread 재시작하여 새 연결 수락
                    startAcceptThread();
                    break;
                }
            }
        }

        /**
         * 데이터 전송 (ConnectedThread 내부에서만 호출)
         */
        public void write(byte[] buffer) throws IOException {
            try {
                outputStream.write(buffer);
                outputStream.flush();
            } catch (IOException e) {
                Log.e(TAG, "데이터 전송 중 오류", e);
                throw e; // 상위로 예외 전달
            }
        }

        public void cancel() {
            running = false;
            try {
                socket.close();
            } catch (IOException e) {
                Log.e(TAG, "ConnectedThread 소켓 닫기 실패", e);
            }
        }
    }

    public static RtuSnapshot createDummyData() {
        RtuSnapshot rtuSnapshot = new RtuSnapshot();

        SimpleDateFormat isoFormat = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US);
        isoFormat.setTimeZone(TimeZone.getTimeZone("UTC"));
        String isoTimestamp = isoFormat.format(new Date());
        rtuSnapshot.timestamp = isoTimestamp;

        rtuSnapshot.satelliteRemoteControlStatus = false;
        rtuSnapshot.ethernetStatus = true;
        rtuSnapshot.serialStatus = false;
        rtuSnapshot.aiStatus = true;
        rtuSnapshot.aoStatus = false;

        rtuSnapshot.doorOpen = false;
        rtuSnapshot.powerAvailable = true;

        rtuSnapshot.waterLevel = 100.5;
        rtuSnapshot.waterLevel2 = 101.5;
        rtuSnapshot.rainFall = 10.2;
        rtuSnapshot.batteryVoltage = 120.8;

        rtuSnapshot.rtuId = "1";
        rtuSnapshot.groupId = "1";
        rtuSnapshot.damCode = "1234567";

        rtuSnapshot.eth0UserType = true;
        rtuSnapshot.eth0IpAddress = "192.168.0.137";
        rtuSnapshot.eth0SubnetMask = "255.255.255.0";
        rtuSnapshot.eth0Gateway = "192.168.0.1";

        rtuSnapshot.eth1UserType = true;
        rtuSnapshot.eth1IpAddress = "192.168.0.135";
        rtuSnapshot.eth1SubnetMask = "255.255.255.0";
        rtuSnapshot.eth1Gateway = "192.168.0.1";

        rtuSnapshot.diData = "00000 00000 00000 00000 1 2";
        rtuSnapshot.doData = "1 1 0 0 1 0 0 1";

        rtuSnapshot.aiData = Arrays.asList(4.1, 3.9, 4.0, 4.2);
        rtuSnapshot.aoData = Arrays.asList(3.8, 4.0, 4.1, 3.9);

        rtuSnapshot.sensorAlert = false;
        rtuSnapshot.pulsePerMm = 0.5;

        return rtuSnapshot;
    }

    private void sendMessageToUI(String message) {
        Intent intent = new Intent("BT_SERVER_MESSAGE");
        intent.putExtra("message", message);
        sendBroadcast(intent);
    }

    @Override
    public void onDestroy() {
        super.onDestroy();

        // 스레드 종료
        if (connectedThread != null) {
            connectedThread.cancel();
            connectedThread = null;
        }

        if (acceptThread != null) {
            acceptThread.cancel();
            acceptThread = null;
        }

        // 소켓 닫기
        try {
            if (clientSocket != null) {
                clientSocket.close();
                clientSocket = null;
            }
            if (serverSocket != null) {
                serverSocket.close();
                serverSocket = null;
            }
        } catch (IOException e) {
            Log.e(TAG, "소켓 닫기 실패", e);
        }

        sendMessageToUI("서버 종료됨");
    }
}