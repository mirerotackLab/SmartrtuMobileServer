package kr.co.mirerotack.btsever1.utils;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;

import kr.co.mirerotack.btsever1.model.DetectionResult;

import static kr.co.mirerotack.btsever1.utils.Logger.logMessage;

public class SmartRtuUtils {
    public static DetectionResult detectTargetUsingShellCommand(String command, String target, String nonTarget, boolean firstLineSkip) throws IOException, InterruptedException {
        return detectTargetUsingShellCommand(command.split(" "), target, nonTarget, firstLineSkip);
    }

    public static DetectionResult detectTargetUsingShellCommand(String[] command, String target, String nonTarget, boolean firstLineSkip) throws IOException, InterruptedException {
        Process process = Runtime.getRuntime().exec(command);
        process.waitFor();

        BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
        String line;

        if (firstLineSkip) {
            reader.readLine(); // 첫 줄 스킵
        }

        boolean isMatched = false;
        String matchedLine = null;

        while ((line = reader.readLine()) != null) {
            if (!line.contains(target)) {
                continue;
            }

            if (nonTarget != null && line.toLowerCase().contains(nonTarget)) {
                continue; // nonTarget 포함되면 무시
            }

            isMatched = true;
            matchedLine = line;
            logMessage("matchedLine : " + matchedLine);
            break;
        }

        reader.close();
        return new DetectionResult(isMatched, matchedLine);
    }

    // 설치되어 있는 패키지 찾기 (kr.co.mirerotack.* 중에서 apkdownloader 제외하고 하나 찾기)
    public static String getInstalledAppPackageName(String basePackagePrefix, String ignoreKeyword)
            throws IOException, InterruptedException {
        DetectionResult result = detectTargetUsingShellCommand("pm list packages", basePackagePrefix, ignoreKeyword, true);
        if (!result.isMatched) return null;

        int idx = result.matchedLine.lastIndexOf('.');
        if (idx == -1) return null;

        return result.matchedLine.substring(idx + 1).trim(); // "smartrtu"
    }

    // 실행중인지 확인
    public static boolean isAppRunning(String basePackagePath, String packageSuffix)
            throws IOException, InterruptedException {
        return detectTargetUsingShellCommand("ps", basePackagePath, packageSuffix, true).isMatched;
    }

    // 액티비티 이름 추출
    public static String extractMainActivityName(String fullPackageName)
            throws IOException, InterruptedException {
        DetectionResult activityResult = detectTargetUsingShellCommand(
                "dumpsys package " + fullPackageName, "activity", null, false);
        if (!activityResult.isMatched) return null;

        int idx = activityResult.matchedLine.lastIndexOf('.');
        if (idx == -1) return null;

        String line = activityResult.matchedLine.substring(idx + 1).trim(); // e.g. "MainActivity filter 410e9920"
        return line.split(" ")[0]; // "MainActivity"
    }

    // Shell 명령 실행 결과 읽기
    public static String executeAndReadShellCommand(String command, String includeKeyword, String excludeKeyword)
            throws IOException, InterruptedException {
        Process process = Runtime.getRuntime().exec(command);
        process.waitFor();

        BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
        StringBuilder output = new StringBuilder();
        String line;
        String matchedLine = null;

        while ((line = reader.readLine()) != null) {
            output.append(line).append("\n");
            if (includeKeyword != null && line.contains(includeKeyword) &&
                    (excludeKeyword == null || !line.toLowerCase().contains(excludeKeyword))) {
                matchedLine = line;
                // logMessage("matchedLine : " + matchedLine); // 필요시 외부에서 로깅
            }
        }

        reader.close();
        String result = output.toString().trim();
        return result.isEmpty() ? "Message is Empty" : result;
    }
}
