package com.frpc.launcher;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Binder;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;

import androidx.annotation.RequiresApi;
import androidx.core.app.NotificationCompat;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;

public class FrpcService extends Service {
    private static final String TAG = "FrpcService";
    private static final String CHANNEL_ID = "frpc_channel";
    private static final int NOTIFICATION_ID = 1;

    public static final String ACTION_START = "com.frpc.launcher.START";
    public static final String ACTION_STOP = "com.frpc.launcher.STOP";
    public static final String ACTION_ERROR = "com.frpc.launcher.ERROR";
    public static final String ACTION_OUTPUT = "com.frpc.launcher.OUTPUT";
    public static final String ACTION_STARTED = "com.frpc.launcher.STARTED";
    public static final String EXTRA_ERROR_MESSAGE = "error_message";
    public static final String EXTRA_OUTPUT_LINE = "output_line";

    private static boolean isRunning = false;
    private static String lastInfo = null;
    private static String lastError = null;
    private static int remotePort = 0;
    private Process frpcProcess;
    private final IBinder binder = new Binder();

    @Override
    public void onCreate() {
        super.onCreate();
        appContext = getApplicationContext();
        createNotificationChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null) {
            String action = intent.getAction();
            if (ACTION_START.equals(action)) {
                startFrpc();
            } else if (ACTION_STOP.equals(action)) {
                stopFrpc();
                stopSelf();
            }
        }
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "Frpc Service",
                    NotificationManager.IMPORTANCE_LOW
            );
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) {
                manager.createNotificationChannel(channel);
            }
        }
    }

    private void startFrpc() {
        if (isRunning) {
            Log.d(TAG, "Frpc already running");
            return;
        }

        try {
            // 先启动前台服务，避免超时崩溃
            Notification notification = createNotification();
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    // Android 14+ 需要指定前台服务类型
                    startForegroundWithType(NOTIFICATION_ID, notification);
                } else {
                    startForeground(NOTIFICATION_ID, notification);
                }
                Log.d(TAG, "Foreground service started");
            } catch (Exception e) {
                Log.e(TAG, "Failed to start foreground service", e);
                throw new RuntimeException("无法启动前台服务: " + e.getMessage(), e);
            }

            // 获取文件路径
            File externalDirFile = getExternalFilesDir(null);
            if (externalDirFile == null) {
                throw new IOException("无法获取应用目录");
            }
            String externalDir = externalDirFile.getAbsolutePath();
            String frpcPath = externalDir + "/frpc";
            String tomlPath = externalDir + "/frpc.toml";
            Log.d(TAG, "Frpc path: " + frpcPath);
            Log.d(TAG, "Toml path: " + tomlPath);

            // 复制frpc二进制文件到应用目录（如果不存在）
            copyFrpcBinary(frpcPath);
            
            // 复制并修改toml配置文件
            copyAndModifyToml(tomlPath);

            // 应用目录通常无法执行文件，直接复制到/data/local/tmp
            String originalFrpcPath = frpcPath;
            String tmpDir = "/data/local/tmp";
            String tmpFrpcPath = tmpDir + "/frpc_" + android.os.Process.myPid();
            
            try {
                Log.d(TAG, "Copying frpc to /data/local/tmp: " + tmpFrpcPath);
                // 使用root权限复制文件到临时目录
                Process suProcess = Runtime.getRuntime().exec("su");
                OutputStream os = suProcess.getOutputStream();
                
                // 创建目录（如果不存在）
                String mkdirCmd = "mkdir -p " + tmpDir + "\n";
                os.write(mkdirCmd.getBytes());
                os.flush();
                
                // 复制文件
                String copyCmd = "cat \"" + originalFrpcPath + "\" > \"" + tmpFrpcPath + "\"\n";
                os.write(copyCmd.getBytes());
                os.flush();
                
                // 设置执行权限
                String chmodCmd = "chmod 755 \"" + tmpFrpcPath + "\"\n";
                os.write(chmodCmd.getBytes());
                os.flush();
                
                // 退出su
                os.write("exit\n".getBytes());
                os.flush();
                os.close();
                
                int exitValue = suProcess.waitFor();
                if (exitValue == 0) {
                    File tmpFile = new File(tmpFrpcPath);
                    if (tmpFile.exists() && tmpFile.length() > 0) {
                        frpcPath = tmpFrpcPath;
                        Log.d(TAG, "Successfully copied frpc to /data/local/tmp: " + frpcPath);
                    } else {
                        throw new IOException("文件复制失败或文件为空");
                    }
                } else {
                    throw new IOException("复制文件失败，退出码: " + exitValue);
                }
            } catch (Exception e) {
                Log.e(TAG, "Failed to copy to /data/local/tmp", e);
                throw new IOException("无法复制文件到可执行目录，请确保应用有root权限: " + e.getMessage(), e);
            }

            // 生成随机name和port
            String randomName = generateRandomName();
            int randomPort = generateRandomPort();
            remotePort = randomPort; // 保存远程端口
            lastInfo = "Name: " + randomName + "\nPort: " + randomPort;
            Log.d(TAG, "Generated name: " + randomName + ", port: " + randomPort);

            // 修改toml文件
            modifyTomlFile(tomlPath, randomName, randomPort);

            // 验证文件存在
            File frpcFileCheck = new File(frpcPath);
            if (!frpcFileCheck.exists()) {
                throw new IOException("frpc文件不存在: " + frpcPath);
            }
            Log.d(TAG, "Frpc file verified: exists=" + frpcFileCheck.exists() + ", executable=" + frpcFileCheck.canExecute());
            
            // 检查文件大小（确保文件完整）
            long fileSize = frpcFileCheck.length();
            Log.d(TAG, "Frpc file size: " + fileSize + " bytes");
            if (fileSize == 0) {
                throw new IOException("frpc文件大小为0，可能文件损坏");
            }
            
            // 尝试读取文件头，检查是否是有效的ELF文件
            // 注意：使用 FileInputStream 而不是 Files.newInputStream() 以兼容 API 21+
            @SuppressWarnings({"IOResource", "resource"})  // 使用 FileInputStream 以兼容 API 21+，Files.newInputStream() 需要 API 26+
            FileInputStream fis = new FileInputStream(frpcFileCheck);
            try {
                byte[] header = new byte[4];
                int read = fis.read(header);
                if (read == 4 && header[0] == 0x7f && header[1] == 'E' && header[2] == 'L' && header[3] == 'F') {
                    Log.d(TAG, "Frpc file is a valid ELF binary");
                } else {
                    Log.w(TAG, "Frpc file may not be a valid ELF binary, header: " + bytesToHex(header));
                }
            } finally {
                try {
                    fis.close();
                } catch (IOException e) {
                    Log.w(TAG, "Failed to close file input stream", e);
                }
            }
            
            // 验证toml文件存在
            File tomlFileCheck = new File(tomlPath);
            if (!tomlFileCheck.exists()) {
                throw new IOException("toml配置文件不存在: " + tomlPath);
            }
            Log.d(TAG, "Toml file verified: " + tomlPath);

            // 启动frpc进程（始终使用su通过root权限执行，确保权限）
            Log.d(TAG, "Starting frpc process with root: " + frpcPath + " -c " + tomlPath);
            
            // 使用su通过root权限执行，确保有足够权限
            Process suProcess = Runtime.getRuntime().exec("su");
            OutputStream os = suProcess.getOutputStream();
            
            // 获取工作目录
            String parentDir = frpcFileCheck.getParent();
            if (parentDir != null) {
                // 切换到工作目录
                String cdCmd = "cd \"" + parentDir + "\"\n";
                os.write(cdCmd.getBytes());
                os.flush();
                Log.d(TAG, "Changed working directory to: " + parentDir);
            }
            
            // 先设置执行权限（以防万一）
            String chmodCmd = "chmod 755 \"" + frpcPath + "\"\n";
            os.write(chmodCmd.getBytes());
            os.flush();
            Log.d(TAG, "Set executable permission");
            
            // 执行frpc命令（使用绝对路径，确保能找到文件）
            // 注意：路径中包含空格，需要用引号包裹
            String command = "\"" + frpcPath + "\" -c \"" + tomlPath + "\"\n";
            os.write(command.getBytes());
            os.flush();
            Log.d(TAG, "Executed frpc command");
            
            // 不要关闭流，保持su进程运行
            // os.close(); // 注释掉，保持进程运行
            
            frpcProcess = suProcess;
            Log.d(TAG, "Started frpc process with su, PID: " + getProcessId(frpcProcess));
            
            // 同时读取错误输出
            new Thread(() -> {
                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(frpcProcess.getErrorStream()))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        Log.e(TAG, "frpc stderr: " + line);
                        sendOutput("[错误] " + line);
                    }
                } catch (IOException e) {
                    Log.e(TAG, "Error reading frpc stderr", e);
                }
            }).start();

            // 启动读取输出的线程
            new Thread(() -> {
                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(frpcProcess.getInputStream()))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        Log.d(TAG, "frpc: " + line);
                        // 发送输出到Activity
                        sendOutput(line);
                    }
                    // 进程退出
                    int exitCode = frpcProcess.waitFor();
                    Log.e(TAG, "Frpc process exited with code: " + exitCode);
                    sendOutput("[进程退出，退出码: " + exitCode + "]");
                    isRunning = false;
                    frpcProcess = null;
                } catch (IOException e) {
                    Log.e(TAG, "Error reading frpc output", e);
                    sendOutput("[错误: " + e.getMessage() + "]");
                } catch (InterruptedException e) {
                    Log.e(TAG, "Interrupted while waiting for process", e);
                    sendOutput("[中断: " + e.getMessage() + "]");
                }
            }).start();
            
            // 等待一小段时间检查进程是否还在运行
            Thread.sleep(500);
            // 检查进程是否还在运行（兼容所有API级别）
            boolean processAlive = isProcessAlive(frpcProcess);
            if (!processAlive) {
                int exitCode = frpcProcess.exitValue();
                throw new IOException("frpc进程立即退出，退出码: " + exitCode);
            }

            isRunning = true;
            Log.d(TAG, "Frpc started successfully");
            
            // 发送启动成功广播，更新UI
            try {
                Intent startedIntent = new Intent(ACTION_STARTED);
                sendBroadcast(startedIntent);
            } catch (Exception ex) {
                Log.e(TAG, "Failed to send started broadcast", ex);
            }

        } catch (Exception e) {
            Log.e(TAG, "Failed to start frpc", e);
            Log.e(TAG, "Exception details", e);
            isRunning = false;
            lastError = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            // 发送错误广播
            try {
                Intent errorIntent = new Intent(ACTION_ERROR);
                errorIntent.putExtra(EXTRA_ERROR_MESSAGE, lastError);
                sendBroadcast(errorIntent);
            } catch (Exception ex) {
                Log.e(TAG, "Failed to send error broadcast", ex);
            }
            stopSelf();
        }
    }
    
    private void sendOutput(String line) {
        try {
            Intent outputIntent = new Intent(ACTION_OUTPUT);
            outputIntent.putExtra(EXTRA_OUTPUT_LINE, line);
            sendBroadcast(outputIntent);
        } catch (Exception e) {
            Log.e(TAG, "Failed to send output broadcast", e);
        }
    }

    private void copyFrpcBinary(String targetPath) throws IOException {
        File targetFile = new File(targetPath);
        if (targetFile.exists() && targetFile.canExecute()) {
            return; // 已存在且可执行
        }

        // 从assets复制（必须存在）
        try (InputStream is = getAssets().open("frpc");
             FileOutputStream fos = new FileOutputStream(targetPath)) {
            byte[] buffer = new byte[8192];
            int bytesRead;
            while ((bytesRead = is.read(buffer)) != -1) {
                fos.write(buffer, 0, bytesRead);
            }
            Log.d(TAG, "Frpc binary copied from assets");
        } catch (IOException e) {
            Log.e(TAG, "Failed to copy frpc from assets", e);
            throw new IOException("frpc二进制文件未找到。请确保已将frpc文件放到app/src/main/assets/目录中。", e);
        }
    }

    private void copyAndModifyToml(String targetPath) throws IOException {
        // 优先从assets复制
        try (InputStream is = getAssets().open("frpc.toml");
             FileOutputStream fos = new FileOutputStream(targetPath)) {
            byte[] buffer = new byte[8192];
            int bytesRead;
            while ((bytesRead = is.read(buffer)) != -1) {
                fos.write(buffer, 0, bytesRead);
            }
            Log.d(TAG, "Toml config copied from assets");
        } catch (IOException e) {
            // 如果assets中没有，使用默认配置
            Log.w(TAG, "frpc.toml not found in assets, using default config");
            createDefaultToml(targetPath);
        }
    }

    private void createDefaultToml(String path) throws IOException {
        // 从设置中读取配置
        String serverAddr = SettingsActivity.getServerAddr(this);
        int serverPort = SettingsActivity.getServerPort(this);
        String authToken = SettingsActivity.getAuthToken(this);
        int localPort = SettingsActivity.getLocalPort(this);
        
        // 如果authToken为空，使用默认值
        if (authToken == null || authToken.trim().isEmpty()) {
            authToken = ConfigConstants.DEFAULT_AUTH_TOKEN;
        }
        
        String defaultConfig = "serverAddr = \"" + serverAddr + "\"\n" +
                "serverPort = " + serverPort + "\n" +
                "auth.token = \"" + authToken + "\"\n" +
                "\n" +
                "[[proxies]]\n" +
                "name = \"test-tcp\"\n" +
                "type = \"tcp\"\n" +
                "localIP = \"127.0.0.1\"\n" +
                "localPort = " + localPort + "\n" +
                "remotePort = 6000\n";
        
        try (FileOutputStream fos = new FileOutputStream(path)) {
            fos.write(defaultConfig.getBytes());
        }
    }

    private void setExecutable(String filePath) {
        File file = new File(filePath);
        // 设置执行权限（只对所有者，不是世界可读）
        if (!file.setExecutable(true, false)) {
            Log.w(TAG, "Failed to set executable permission on " + filePath);
        }
        if (!file.setReadable(true, false)) {  // 只对所有者可读，不是世界可读
            Log.w(TAG, "Failed to set readable permission on " + filePath);
        }
        if (!file.setWritable(false, false)) {
            Log.w(TAG, "Failed to set writable permission on " + filePath);
        }
        
        // 尝试使用chmod命令（只对所有者可执行和可读，不是世界可读）
        try {
            Process chmodProcess = Runtime.getRuntime().exec(new String[]{"chmod", "700", filePath});
            chmodProcess.waitFor();
            Log.d(TAG, "chmod 700 executed on " + filePath);
        } catch (Exception e) {
            Log.w(TAG, "Failed to execute chmod, using setExecutable only", e);
        }
    }
    
    private void setExecutableWithRoot(String filePath) {
        File file = new File(filePath);
        // 先尝试普通方式设置权限
        setExecutable(filePath);
        
        // 使用root权限通过su命令设置权限
        try {
            Process suProcess = Runtime.getRuntime().exec("su");
            OutputStream os = suProcess.getOutputStream();
            
            // 使用chmod设置执行权限
            String command = "chmod 700 " + filePath + "\n";
            os.write(command.getBytes());
            os.flush();
            
            // 退出su
            os.write("exit\n".getBytes());
            os.flush();
            os.close();
            
            int exitValue = suProcess.waitFor();
            if (exitValue == 0) {
                Log.d(TAG, "Successfully set executable permission with root: " + filePath);
            } else {
                Log.w(TAG, "Failed to set executable permission with root, exit code: " + exitValue);
            }
        } catch (Exception e) {
            Log.w(TAG, "Failed to set executable permission with root", e);
        }
    }
    
    @SuppressWarnings({"IOResource", "resource"})  // 使用 FileInputStream 以兼容 API 21+，Files.newInputStream() 需要 API 26+
    private void copyFile(String sourcePath, String targetPath) throws IOException {
        // 注意：使用 FileInputStream 而不是 Files.newInputStream() 以兼容 API 21+
        try (FileInputStream fis = new FileInputStream(sourcePath);
             FileOutputStream fos = new FileOutputStream(targetPath)) {
            byte[] buffer = new byte[8192];
            int bytesRead;
            while ((bytesRead = fis.read(buffer)) != -1) {
                fos.write(buffer, 0, bytesRead);
            }
            Log.d(TAG, "File copied from " + sourcePath + " to " + targetPath);
        }
    }
    
    private long getProcessId(Process process) {
        try {
            // 使用反射获取进程ID（兼容所有Android版本）
            java.lang.reflect.Field field = process.getClass().getDeclaredField("pid");
            field.setAccessible(true);
            return field.getLong(process);
        } catch (NoSuchFieldException e) {
            // 如果pid字段不存在，尝试其他方法
            try {
                // 尝试通过反射调用pid()方法（Android 8.0+）
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    java.lang.reflect.Method method = process.getClass().getDeclaredMethod("pid");
                    method.setAccessible(true);
                    Object result = method.invoke(process);
                    if (result != null) {
                        return (Long) result;
                    }
                }
            } catch (Exception ex) {
                Log.w(TAG, "Failed to get process ID via method", ex);
            }
            Log.w(TAG, "Failed to get process ID", e);
            return -1;
        } catch (Exception e) {
            Log.w(TAG, "Failed to get process ID", e);
            return -1;
        }
    }
    
    private String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format(java.util.Locale.US, "%02X ", b));
        }
        return sb.toString().trim();
    }
    
    private boolean isProcessAlive(Process process) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                // API 26+ 使用 isAlive() 方法
                return process.isAlive();
            } else {
                // API 26以下，尝试获取退出值来判断
                try {
                    process.exitValue();
                    // 如果能获取退出值，说明进程已结束
                    return false;
                } catch (IllegalThreadStateException e) {
                    // 如果抛出异常，说明进程还在运行
                    return true;
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "Failed to check if process is alive", e);
            // 默认假设进程还在运行
            return true;
        }
    }

    private String generateRandomName() {
        long timestamp = System.currentTimeMillis();
        int pid = android.os.Process.myPid();
        long combined = timestamp + pid * 1000L;
        int randomNum = (int) (combined % 100000000);
        return String.format(java.util.Locale.US, "frpc-%08d", randomNum);
    }

    private int generateRandomPort() {
        // 从设置中获取端口范围
        int minPort = SettingsActivity.getRandomPortMin(this);
        int maxPort = SettingsActivity.getRandomPortMax(this);
        
        // 确保范围有效
        if (minPort >= maxPort) {
            Log.w(TAG, "Invalid port range, using defaults");
            minPort = ConfigConstants.DEFAULT_RANDOM_PORT_MIN;
            maxPort = ConfigConstants.DEFAULT_RANDOM_PORT_MAX;
        }
        
        int portRange = maxPort - minPort + 1;
        long timestamp = System.currentTimeMillis();
        int pid = android.os.Process.myPid();
        long combined = timestamp + pid * 1000L;
        int offset = (int) (combined % portRange);
        if (offset < 0) {
            offset += portRange;
        }
        return minPort + offset;
    }

    private void modifyTomlFile(String tomlPath, String name, int port) throws IOException {
        File file = new File(tomlPath);
        StringBuilder content = new StringBuilder();
        
        // 注意：使用 FileInputStream 而不是 Files.newInputStream() 以兼容 API 21+
        @SuppressWarnings({"IOResource", "resource"})  // 使用 FileInputStream 以兼容 API 21+，Files.newInputStream() 需要 API 26+
        FileInputStream fileInputStream = new FileInputStream(file);
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(fileInputStream))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.trim().startsWith("name = ")) {
                    content.append("name = \"").append(name).append("\"\n");
                } else if (line.trim().startsWith("remotePort = ")) {
                    content.append("remotePort = ").append(port).append("\n");
                } else {
                    content.append(line).append("\n");
                }
            }
        }
        
        try (FileOutputStream fos = new FileOutputStream(file)) {
            fos.write(content.toString().getBytes());
        }
    }

    private void stopFrpc() {
        if (frpcProcess != null) {
            frpcProcess.destroy();
            try {
                frpcProcess.waitFor();
            } catch (InterruptedException e) {
                Log.e(TAG, "Error waiting for process", e);
            }
            frpcProcess = null;
        }
        isRunning = false;
        stopForeground(true);
        Log.d(TAG, "Frpc stopped");
    }

    @RequiresApi(api = Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    private void startForegroundWithType(int id, Notification notification) {
        startForeground(id, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
    }

    private Notification createNotification() {
        Intent notificationIntent = new Intent(this, MainActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(
                this, 0, notificationIntent,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("Frpc运行中")
                .setContentText("frpc服务正在后台运行")
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .build();
    }

    @Override
    public void onDestroy() {
        stopFrpc();
        super.onDestroy();
    }

    public static boolean isRunning() {
        return isRunning;
    }

    public static String getLastInfo() {
        return lastInfo;
    }

    public static String getLastError() {
        return lastError;
    }

    public static void clearError() {
        lastError = null;
    }
    
    public static String getServerAddress() {
        android.content.Context ctx = getAppContext();
        if (ctx == null) {
            // 如果 Context 还未初始化，返回默认值
            return ConfigConstants.DEFAULT_SERVER_ADDR + ":" + ConfigConstants.DEFAULT_SERVER_PORT;
        }
        String serverAddr = SettingsActivity.getServerAddr(ctx);
        int serverPort = SettingsActivity.getServerPort(ctx);
        return serverAddr + ":" + serverPort;
    }
    
    public static int getRemotePort() {
        return remotePort;
    }
    
    public static String getConnectionInfo() {
        android.content.Context ctx = getAppContext();
        if (ctx == null) {
            // 如果 Context 还未初始化，返回默认值
            return ConfigConstants.DEFAULT_SERVER_ADDR + ":" + ConfigConstants.DEFAULT_SERVER_PORT;
        }
        String serverAddr = SettingsActivity.getServerAddr(ctx);
        if (remotePort > 0) {
            return serverAddr + ":" + remotePort;
        }
        int serverPort = SettingsActivity.getServerPort(ctx);
        return serverAddr + ":" + serverPort;
    }
    
    private static android.content.Context getAppContext() {
        // 需要一个静态方法来获取Context
        // 这里使用一个静态变量来保存Context
        if (appContext == null) {
            // 如果还未初始化，尝试从其他地方获取（这种情况不应该发生）
            Log.w(TAG, "appContext is null, Service may not be initialized");
        }
        return appContext;
    }
    
    private static android.content.Context appContext;
}

