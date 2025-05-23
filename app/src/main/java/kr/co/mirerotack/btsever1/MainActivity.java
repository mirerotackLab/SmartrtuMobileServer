package kr.co.mirerotack.btsever1;

import android.Manifest;
import android.bluetooth.BluetoothAdapter;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.support.v4.app.ActivityCompat;
import android.support.v4.content.ContextCompat;
import android.support.v7.app.AppCompatActivity;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;

import kr.co.mirerotack.btsever1.ymodemOverTcp.YModemTCPService;


public class MainActivity extends AppCompatActivity {

    private BluetoothServerService bluetoothServerService;
    private boolean isBound = false;
    private TextView txtStatus;

    private static final int REQUEST_BT_PERMISSIONS = 100;

    // ✅ 브로드캐스트 수신기
    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String msg = intent.getStringExtra("message");
            Log.d("newEvent", "msg : " + msg);
            txtStatus.setText(msg);
        }
    };

    // ✅ 서비스 연결 콜백
    private final ServiceConnection connection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            BluetoothServerService.LocalBinder binder = (BluetoothServerService.LocalBinder) service;
            bluetoothServerService = binder.getService();
            isBound = true;
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            isBound = false;
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // initTCP();

        txtStatus = findViewById(R.id.txtStatus);

        // ✅ 브로드캐스트 수신 등록, Service에서 상태 변화를 업데이트 하기 위함
        registerReceiver(receiver, new IntentFilter("BT_SERVER_MESSAGE"));

        Button btnStartServer = findViewById(R.id.btnStartServer);
        Button btnSendData = findViewById(R.id.btnSendData);

        btnStartServer.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                // 권한 체크 후 권한이 없으면 요청하고 중단
                if (!checkBluetoothPermissions()) {
                    txtStatus.setText("블루투스 권한 요청 중...");
                    return; // 권한 요청은 onRequestPermissionsResult에서 처리
                }

                startBluetoothServer(); // 권한이 있으면, 블루투스 서버 시작 메서드 호출
            }
        });

        btnSendData.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
            if (isBound && bluetoothServerService != null) {
                bluetoothServerService.sendSensorData(); // ✅ 데이터 전송
            }
            }
        });
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (isBound) {
            unbindService(connection);
            isBound = false;
        }
        unregisterReceiver(receiver);
    }

    private void initTCP() {
        // ✅ Context를 통해 파일 디렉토리 초기화
        YModemTCPService.initialize(getApplicationContext());

        // ✅ 서비스 시작 (백그라운드에서 TCP 서버 실행)
        startService(new Intent(this, YModemTCPService.class));

        // ✅ UI 없이 바로 앱 종료
        finish();
    }

    // ✅ 권한 체크 및 요청
    private boolean checkBluetoothPermissions() {
        boolean allPermissionsGranted = true;

        if (Build.VERSION.SDK_INT >= 31) { // Android 12 이상
            Log.d("BluetoothServerService", "Android 12 이상");
            // Android 12 이상에서 필요한 블루투스 권한 체크
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED ||
                    ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED ||
                    ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_ADVERTISE) != PackageManager.PERMISSION_GRANTED) {

                ActivityCompat.requestPermissions(this, new String[]{
                        Manifest.permission.BLUETOOTH_CONNECT,
                        Manifest.permission.BLUETOOTH_SCAN,
                        Manifest.permission.BLUETOOTH_ADVERTISE
                }, REQUEST_BT_PERMISSIONS);
                allPermissionsGranted = false;
            }
        } else { // Android 11 이하
            Log.d("BluetoothServerService", "Android 11 이하");
            // Android 11 이하에서 필요한 블루투스 권한 체크
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH) != PackageManager.PERMISSION_GRANTED ||
                    ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_ADMIN) != PackageManager.PERMISSION_GRANTED ||
                    ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {

                ActivityCompat.requestPermissions(this, new String[]{
                        Manifest.permission.BLUETOOTH,
                        Manifest.permission.BLUETOOTH_ADMIN,
                        Manifest.permission.ACCESS_FINE_LOCATION
                }, REQUEST_BT_PERMISSIONS);
                allPermissionsGranted = false;
            }
        }

        return allPermissionsGranted;
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);

        if (requestCode == REQUEST_BT_PERMISSIONS) {
            // 모든 권한이 승인되었는지 확인
            boolean allGranted = true;
            for (int result : grantResults) {
                if (result != PackageManager.PERMISSION_GRANTED) {
                    allGranted = false;
                    break;
                }
            }

            if (allGranted) { // 모든 권한이 승인되면 서버 시작
                startBluetoothServer();
            } else {
                txtStatus.setText("블루투스 권한 필요. 설정에서 허용해주세요.");
            }
        }
    }

    // ✅ 블루투스 서버 시작 로직
    private void startBluetoothServer() {
        // 블루투스 어댑터 확인
        BluetoothAdapter bluetoothAdapter = BluetoothAdapter.getDefaultAdapter();

        if (bluetoothAdapter == null) {
            txtStatus.setText("이 기기는 블루투스를 지원하지 않습니다.");
            return;
        }

        if (!bluetoothAdapter.isEnabled()) {
            if (txtStatus.getText() == "[X] Bluetooth Status is OFF.....") {
                txtStatus.setText("[X] Bluetooth Status is OFF");
            } else {
                txtStatus.setText("[X] Bluetooth Status is OFF.....");
            }


            return;
        } else {
            txtStatus.setText("[O] Bluetooth Status is ON");
        }

        // 블루투스 활성화 확인
//        if (!bluetoothAdapter.isEnabled()) {
//            Intent enableBtIntent = new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE);
//            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) {
//                startActivityForResult(enableBtIntent, 1);
//            } else {
//                txtStatus.setText("블루투스 권한이 필요합니다.");
//            }
//            return;
//        }

        // 탐색 가능 모드 설정
//        if (bluetoothAdapter.getScanMode() != BluetoothAdapter.SCAN_MODE_CONNECTABLE_DISCOVERABLE) {
//            Intent discoverableIntent = new Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE);
//            discoverableIntent.putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, 300);
//            startActivity(discoverableIntent);
//        }

        // 서버 서비스 시작 및 바인드
        Intent intent = new Intent(MainActivity.this, BluetoothServerService.class);
        startService(intent);
        bindService(intent, connection, Context.BIND_AUTO_CREATE);
    }
}
