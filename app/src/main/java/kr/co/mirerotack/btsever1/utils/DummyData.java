package kr.co.mirerotack.btsever1.utils;

import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

import kr.co.mirerotack.btsever1.RtuSnapshot;

public class DummyData {

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
}
