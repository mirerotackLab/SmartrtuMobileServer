package kr.co.mirerotack.btsever1;

import java.util.List;

public class RtuSnapshot {

    public String timestamp;                          // 측정 시각

    // 홈 화면에 들어갈 통신 상태 값들
    public boolean aiStatus;                          // true?, AI 포트별 상태?
    public boolean aoStatus;                          // true?, AO 포트별 상태?
    public boolean ethernetStatus;                    // true, 이더넷 통신 상태
    public boolean serialStatus;                      // true, 시리얼 통신 상태
    public boolean satelliteRemoteControlStatus;      // true, 위성 원격 제어 (DO 1번 값)

    // 홈 화면에 들어갈 이벤트 감지
    public boolean doorOpen;                          // false, 도어 개방 (DI 21번 값)
    public boolean powerAvailable;                    // false, 상전 (DI 22번 값)

    // 홈 화면에 들어갈 4개 데이터 값들
    public double waterLevel;                         // 100.0, 수위
    public double waterLevel2;                        // 100.0, 수위2
    public double rainFall;                           // 100.0, 누적 우량
    public double batteryVoltage;                     // 100.0, 배터리 전압

    // 기본적인 설정 값들
    public String rtuId;                              // "1"
    public String groupId;                            // "1"
    public String damCode;                            // "1234567"

    // eth0 설정 값들
    public boolean eth0UserType;                      // true
    public String eth0IpAddress;                      // "192.168.0.137"
    public String eth0SubnetMask;                     // "255.255.255.0"
    public String eth0Gateway;                        // "192.168.0.1"

    // eth1 설정 값들
    public boolean eth1UserType;                      // true
    public String eth1IpAddress;                      // "192.168.0.137"
    public String eth1SubnetMask;                     // "255.255.255.0"
    public String eth1Gateway;                        // "192.168.0.1"

    // DI 값 설명 -> ~ 20 : 수위계 BCD 신호(데이터 4개 + 짝수 패리티 1개)
    // 21 : 도어 감시, 22 : 상용 전원
    public String diData;                             // "00000 00000 00000 00000 1 2", 수위계, 도어 감시, 상용 전원
    public String doData;                             // "1 1 0 0 1 0 0 1", 8포트 데이터

    public List<Double> aiData;                       // [4.1, 3.9, ...]
    public List<Double> aoData;                       // [4.1, 3.9, ...]

    public boolean sensorAlert;                       // ?
    public double pulsePerMm;                         // ?
}