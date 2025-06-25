package kr.co.mirerotack.btsever1.utils;

import android.content.Context;

import com.google.gson.Gson;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;

import kr.co.mirerotack.btsever1.model.RtuSnapshot;

public class readwriteJson {
    static Gson gson = new Gson();
    public static final String dataFileName = "RtuStatus.json";


    public static void updateTimestampToFile(Context context, RtuSnapshot snapshot) throws IOException {
        File file = new File(context.getFilesDir(), dataFileName);
        String json = gson.toJson(snapshot);

        FileOutputStream fos = new FileOutputStream(file, false);  // 덮어쓰기 모드
        fos.write(json.getBytes("UTF-8"));
        fos.close();
    }

    public static String readJsonFile(File file) throws IOException {
        StringBuilder builder = new StringBuilder();
        BufferedReader reader = new BufferedReader(new InputStreamReader(new FileInputStream(file), "UTF-8"));
        String line;
        while ((line = reader.readLine()) != null) {
            builder.append(line);
        }
        reader.close();
        return builder.toString();
    }
}
