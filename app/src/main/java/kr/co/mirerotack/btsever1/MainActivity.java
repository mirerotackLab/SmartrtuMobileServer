package kr.co.mirerotack.btsever1;

import android.bluetooth.BluetoothAdapter;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.support.v7.app.AppCompatActivity;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.TextView;

import java.util.Arrays;

import kr.co.mirerotack.btsever1.model.NativeBTStatusListener;
import kr.co.mirerotack.btsever1.ymodemServer.YModemUnifiedService;

/**
 * 메인 액티비티 - TCP와 Bluetooth YModem 서버를 선택적으로 실행할 수 있는 통합 UI
 * 가로모드에 최적화된 레이아웃으로 서버 설정과 상태를 표시합니다
 */
public class MainActivity extends AppCompatActivity implements NativeBTStatusListener {

    // UI 컴포넌트들
    private TextView txtStatus; // 서버 상태 표시용 텍스트뷰
    private RadioGroup radioGroupServerType; // 서버 타입 선택용 라디오 그룹
    private RadioButton radioTCP, radioBluetooth; // TCP/Bluetooth 선택 라디오 버튼
    private Button btnStartServer, btnStopServer; // 서버 시작/중지 버튼

    // 권한 요청 코드
    private static final int REQUEST_BT_PERMISSIONS = 100;
    private static final String TAG = "MainActivity";
    private static final String JNI_TAG = "JNI->Java Callback";

    @Override
    public void nativeOnConnected(String macAddress) {
        Log.d(JNI_TAG, "블루투스 클라이언트 연결 감지됨 : " + macAddress);

        // Send
        byte[] data = "C".getBytes(); // 문자열 "C"를 바이트 배열로 변환

        NativeBtServer.nativeSend(data, data.length);
        Log.w(JNI_TAG, "1-1. [TX] C");

        // Receive
        byte[] buffer = new byte[1];
        int len = NativeBtServer.nativeRead(buffer);

        Log.d(JNI_TAG, "수신된 버퍼 사이즈 : " + len);
        Log.d(JNI_TAG, "수신된 버퍼 내용 : " + buffer.toString());

        if (len == 1 && buffer[0] == "C".getBytes()[0]) {
            Log.w(JNI_TAG, "2-2. [RX] C");
        }

        buffer = new byte[128];
        len = NativeBtServer.nativeRead(buffer);

        Log.d(JNI_TAG, "3-2. 수신된 버퍼 사이즈 : " + len);
        Log.d(JNI_TAG, "3-2. 수신된 버퍼 내용 : " + Arrays.toString(buffer));

        buffer = new byte[1];
        buffer[0] = 0x06; // ACK
        NativeBtServer.nativeSend(buffer, 1);
        Log.d(JNI_TAG, "4-1. [TX] ACK");

        // 헤더 수신부
        int count = 0;
        buffer = new byte[1024];

        while (true) {
            len = NativeBtServer.nativeRead(buffer);

            Log.d(JNI_TAG, "5-2. " + count++ + "번째, 길이 : " + len);

            if (len < 128) {
                Log.d(JNI_TAG, "5-2. 수신된 버퍼 내용 : " + Arrays.toString(buffer));
            }

            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
        }


        // 헤더 수신값 각 변수에 초기화하기 / 수신 데이터 예시)  init 10000 0 1 0
        // 1. 처음 들어온 값이

        // 헤더 수신값에 따라 처리할 부분

    }

    @Override
    public void nativeOnDisconnected() {
        Log.d(JNI_TAG, "블루투스 클라이언트 연결 해제 감지됨");

        // TODO : 추가 로직 작성하기 (재연결 시도 등)
    }

    /**
     * 블루투스 연결 상태 변화를 수신하는 브로드캐스트 리시버
     */
    private final BroadcastReceiver bluetoothStatusReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            final String action = intent.getAction();
            final String TAG = "BluetoothAdapter";

            if (action.equals(BluetoothAdapter.ACTION_STATE_CHANGED)) {
                final int state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE,
                        BluetoothAdapter.ERROR);
                switch (state) {
                    case BluetoothAdapter.STATE_OFF:
                        Log.w(TAG, "Bluetooth off");
                        break;
                    case BluetoothAdapter.STATE_TURNING_OFF:
                        Log.w(TAG, "Turning Bluetooth off...");
                        break;
                    case BluetoothAdapter.STATE_ON:
                        Log.w(TAG, "Bluetooth on");
                        break;
                    case BluetoothAdapter.STATE_TURNING_ON:
                        Log.w(TAG, "Turning Bluetooth on...");
                        break;
                }
            }
        }
    };

    /**
     * YModem 서버 상태 메시지를 수신하는 브로드캐스트 리시버
     */
    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String msg = intent.getStringExtra("message");
            Log.d("YModemServer", "상태 메시지: " + msg);
            if (msg != null) {
                txtStatus.setText(msg); // UI에 상태 메시지 표시
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        initUI(); // UI 컴포넌트 초기화

        // JNI 네이티브 서버 시작 (기존 기능 유지)
        startNativeBluetoothServer();

        // 브로드캐스트 수신 등록 (서비스에서 상태 변화를 업데이트하기 위함)
        registerReceiver(receiver, new IntentFilter("BT_SERVER_MESSAGE"));


        // Register for broadcasts on BluetoothAdapter state change
        IntentFilter filter = new IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED);
        registerReceiver(bluetoothStatusReceiver, filter);

        // 저장된 서버 타입 설정 로드
        loadSavedServerType();

        // 버튼 클릭 리스너 설정
        setupButtonListeners();
    }

    @Override
    protected void onResume() {
        super.onResume();
        btnStartServer.performClick(); // 선택된 서버 타입으로 시작
        // finish();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        NativeBtServer.nativeClose();

        // 브로드캐스트 리시버 해제
        try {
            unregisterReceiver(receiver);
            unregisterReceiver(bluetoothStatusReceiver);
        } catch (Exception e) {
            Log.e("MainActivity", "브로드캐스트 리시버 해제 실패", e);
        }

        Log.d("MainActivity", "MainActivity 종료됨");
    }

    /**
     * UI 컴포넌트들을 초기화합니다
     */
    private void initUI() {
        txtStatus = findViewById(R.id.txtStatus);
        radioGroupServerType = findViewById(R.id.radioGroupServerType);
        radioTCP = findViewById(R.id.radioTCP);
        radioBluetooth = findViewById(R.id.radioBluetooth);
        btnStartServer = findViewById(R.id.btnStartServer);
        btnStopServer = findViewById(R.id.btnStopServer);

        // 초기 상태 설정
        txtStatus.setText("서버 타입을 선택하고 시작 버튼을 눌러주세요.");
        btnStopServer.setEnabled(false); // 초기에는 중지 버튼 비활성화
    }

    /**
     * JNI 네이티브 Bluetooth 서버를 시작합니다 (기존 기능 유지)
     */
    private void startNativeBluetoothServer() {
        new Thread(() -> {
            // init 설정
            NativeBtServer.setListener(this);
            NativeBtServer.createBluetoothServer();  // JNI 호출
            Log.w("NativeBT", "네이티브 서버 시작 완료");
        }).start();
    }

    /**
     * 저장된 서버 타입 설정을 로드합니다
     */
    private void loadSavedServerType() {
        SharedPreferences prefs = getSharedPreferences("server_config", MODE_PRIVATE);
        String savedServerType = prefs.getString("server_type", "TCP"); // 기본값: TCP

        if ("BLUETOOTH".equals(savedServerType)) {
            radioBluetooth.setChecked(true);
        } else {
            radioTCP.setChecked(true);
        }
    }

    /**
     * 버튼 클릭 리스너들을 설정합니다
     */
    private void setupButtonListeners() {
        // 서버 시작 버튼 클릭 리스너
        btnStartServer.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startSelectedServer(); // 선택된 서버 타입으로 시작
            }
        });

        // 서버 중지 버튼 클릭 리스너
        btnStopServer.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                stopCurrentServer(); // 현재 실행 중인 서버 중지
            }
        });

        // 라디오 그룹 선택 변경 리스너
        radioGroupServerType.setOnCheckedChangeListener(new RadioGroup.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(RadioGroup group, int checkedId) {
                saveServerTypeSelection(); // 선택된 서버 타입 저장

                if (checkedId == R.id.radioTCP) {
                    txtStatus.setText("TCP 서버가 선택되었습니다. (포트: 55556)");
                } else if (checkedId == R.id.radioBluetooth) {
                    txtStatus.setText("Bluetooth 서버가 선택되었습니다. (RFCOMM)");
                }
            }
        });
    }

    /**
     * 선택된 서버 타입을 SharedPreferences에 저장합니다
     */
    private void saveServerTypeSelection() {
        String serverType = radioTCP.isChecked() ? "TCP" : "BLUETOOTH";

        SharedPreferences prefs = getSharedPreferences("server_config", MODE_PRIVATE);
        prefs.edit().putString("server_type", serverType).apply();

        Log.d("MainActivity", "서버 타입 저장됨: " + serverType);
    }

    /**
     * 선택된 서버 타입에 따라 적절한 서버를 시작합니다
     */
    private void startSelectedServer() {
        if (radioTCP.isChecked()) {
            startTCPServer(); // TCP 서버 시작
        } else if (radioBluetooth.isChecked()) {
            startBluetoothServer(); // Bluetooth 서버 시작
        } else {
            txtStatus.setText("서버 타입을 선택해주세요.");
            return;
        }

        // 버튼 상태 변경
        btnStartServer.setEnabled(false);
        btnStopServer.setEnabled(true);
        radioGroupServerType.setEnabled(false); // 서버 실행 중에는 타입 변경 불가
    }

    /**
     * TCP YModem 서버를 시작합니다
     */
    private void startTCPServer() {
        txtStatus.setText("TCP YModem 서버를 시작하는 중...");

        try {
            // 통합 서비스 초기화
            YModemUnifiedService.initialize(getApplicationContext());

            // TCP 서버로 설정하여 서비스 시작
            Intent serviceIntent = new Intent(this, YModemUnifiedService.class);
            serviceIntent.putExtra("switch_server_type", "TCP");
            startService(serviceIntent);

            txtStatus.setText("TCP YModem 서버가 시작되었습니다. (포트: 55556)");
            Log.d("MainActivity", "TCP YModem 서버 시작됨");

        } catch (Exception e) {
            txtStatus.setText("TCP 서버 시작 실패: " + e.getMessage());
            Log.e("MainActivity", "TCP 서버 시작 실패", e);
            resetButtonState(); // 버튼 상태 원복
        }
    }

    /**
     * Bluetooth YModem 서버를 시작합니다
     */
    private void startBluetoothServer() {
        txtStatus.setText("Bluetooth YModem 서버를 시작하는 중...");

        try {
            // 통합 서비스 초기화
            YModemUnifiedService.initialize(getApplicationContext());

            // Bluetooth 서버로 설정하여 서비스 시작
            Intent serviceIntent = new Intent(this, YModemUnifiedService.class);
            serviceIntent.putExtra("switch_server_type", "BLUETOOTH");
            startService(serviceIntent);

            txtStatus.setText("Bluetooth YModem 서버가 시작되었습니다. (RFCOMM)");
            Log.d("MainActivity", "Bluetooth YModem 서버 시작됨");

        } catch (Exception e) {
            txtStatus.setText("Bluetooth 서버 시작 실패: " + e.getMessage());
            Log.e("MainActivity", "Bluetooth 서버 시작 실패", e);
            resetButtonState(); // 버튼 상태 원복
        }
    }

    /**
     * 현재 실행 중인 서버를 중지합니다
     */
    private void stopCurrentServer() {
        txtStatus.setText("서버를 중지하는 중...");

        try {
            // 통합 서비스 중지
            Intent serviceIntent = new Intent(this, YModemUnifiedService.class);
            stopService(serviceIntent);

            stopService(new Intent(this, YModemUnifiedService.class));

            txtStatus.setText("서버가 중지되었습니다.");
            Log.d("MainActivity", "서버 중지됨");

        } catch (Exception e) {
            txtStatus.setText("서버 중지 실패: " + e.getMessage());
            Log.e("MainActivity", "서버 중지 실패", e);
        }

        resetButtonState(); // 버튼 상태 원복
    }

    /**
     * 버튼들의 상태를 초기 상태로 되돌립니다
     */
    private void resetButtonState() {
        btnStartServer.setEnabled(true);
        btnStopServer.setEnabled(false);
        radioGroupServerType.setEnabled(true);
    }

    /**
     * Bluetooth 어댑터 상태를 확인합니다
     * @return Bluetooth를 사용할 수 있으면 true, 아니면 false
     */
    private boolean checkBluetoothAdapter() {
        BluetoothAdapter bluetoothAdapter = BluetoothAdapter.getDefaultAdapter();

        Log.w(TAG, "bluetoothAdapter.getState() : " + bluetoothAdapter.getState());

        if (bluetoothAdapter == null) {
            txtStatus.setText("이 기기는 Bluetooth를 지원하지 않습니다.");
            return false;
        }

        return true;
    }

    /**
     * 기존 initTCP 메서드 (호환성 유지 - 사용하지 않음)
     * @deprecated 대신 startTCPServer() 사용
     */
    @Deprecated
    private void initTCP() {
        // 호환성을 위해 유지하지만 사용하지 않음
        startTCPServer();
        finish(); // UI 없이 바로 앱 종료 (기존 동작 유지)
    }
}