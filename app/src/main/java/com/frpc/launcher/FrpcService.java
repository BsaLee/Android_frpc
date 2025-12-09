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

            // 读取配置参数
            String serverAddr = SettingsActivity.getServerAddr(this);
            int serverPort = SettingsActivity.getServerPort(this);
            String authToken = SettingsActivity.getAuthToken(this);
            int localPort = SettingsActivity.getLocalPort(this);
            int randomPortMin = SettingsActivity.getRandomPortMin(this);
            int randomPortMax = SettingsActivity.getRandomPortMax(this);
            
            // 如果authToken为空，使用默认值
            if (authToken == null || authToken.trim().isEmpty()) {
                authToken = ConfigConstants.DEFAULT_AUTH_TOKEN;
            }
            
            // 生成随机name和port
            String randomName = generateRandomName();
            int randomPort = generateRandomPort();
            remotePort = randomPort; // 保存远程端口
            lastInfo = "Name: " + randomName + "\nPort: " + randomPort;
            Log.d(TAG, "Generated name: " + randomName + ", port: " + randomPort);

            // 输出配置参数到日志
            java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault());
            String startTime = sdf.format(new java.util.Date());
            sendOutput("========== Frpc 启动配置 ==========");
            sendOutput("启动时间: " + startTime);
            sendOutput("服务器地址: " + serverAddr);
            sendOutput("服务器端口: " + serverPort);
            sendOutput("认证令牌: " + (authToken.length() > 0 ? authToken.substring(0, Math.min(8, authToken.length())) + "..." : "未设置"));
            sendOutput("本地端口: " + localPort);
            sendOutput("随机端口范围: " + randomPortMin + " - " + randomPortMax);
            sendOutput("代理名称: " + randomName);
            sendOutput("远程端口: " + randomPort);
            sendOutput("配置文件路径: " + tomlPath);
            sendOutput("====================================");

            // 修改toml文件，使用用户设置的配置参数
            modifyTomlFile(tomlPath, serverAddr, serverPort, authToken, localPort, randomName, randomPort);
            
            // 读取并显示toml配置文件内容
            try {
                sendOutput("配置文件内容:");
                @SuppressWarnings({"IOResource", "resource"})
                FileInputStream tomlFis = new FileInputStream(tomlPath);
                try (BufferedReader tomlReader = new BufferedReader(
                        new InputStreamReader(tomlFis))) {
                    String tomlLine;
                    while ((tomlLine = tomlReader.readLine()) != null) {
                        sendOutput("  " + tomlLine);
                    }
                }
            } catch (IOException e) {
                Log.w(TAG, "Failed to read toml file content", e);
                sendOutput("无法读取配置文件内容: " + e.getMessage());
            }
            sendOutput("====================================");

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
            
            // 先设置执行权限（以防万一）
            try {
                Process chmodProcess = Runtime.getRuntime().exec(new String[]{"su", "-c", "chmod 755 \"" + frpcPath + "\""});
                int chmodExit = chmodProcess.waitFor();
                if (chmodExit == 0) {
                    Log.d(TAG, "Set executable permission");
                } else {
                    Log.w(TAG, "Failed to set executable permission, exit code: " + chmodExit);
                }
            } catch (Exception e) {
                Log.w(TAG, "Failed to set executable permission", e);
            }
            
            // 使用 su -c 执行命令，这样可以正确捕获输出
            // 构建完整的命令，包括工作目录切换
            String parentDir = frpcFileCheck.getParent();
            String fullCommand;
            if (parentDir != null) {
                // 切换到工作目录并执行命令
                fullCommand = "cd \"" + parentDir + "\" && \"" + frpcPath + "\" -c \"" + tomlPath + "\"";
            } else {
                fullCommand = "\"" + frpcPath + "\" -c \"" + tomlPath + "\"";
            }
            
            // 使用 ProcessBuilder 通过 su -c 执行，确保输出能被正确捕获
            Log.d(TAG, "Executing command: " + fullCommand);
            sendOutput("正在启动 frpc 进程...");
            sendOutput("执行命令: " + fullCommand);
            ProcessBuilder pb = new ProcessBuilder("su", "-c", fullCommand);
            // 重定向错误流到标准输出，这样所有输出都在一个流中
            pb.redirectErrorStream(false);
            frpcProcess = pb.start();
            int processId = (int) getProcessId(frpcProcess);
            Log.d(TAG, "Started frpc process with su, PID: " + processId);
            sendOutput("Frpc 进程已启动，PID: " + processId);
            sendOutput("------------------------------------");
            
            // 用于收集错误信息，以便在进程退出时分析
            final StringBuilder errorBuffer = new StringBuilder();
            final StringBuilder outputBuffer = new StringBuilder();
            
            // 同时读取错误输出
            Thread stderrThread = new Thread(() -> {
                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(frpcProcess.getErrorStream()))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        // 清理 ANSI 转义码
                        String cleanLine = removeAnsiCodes(line);
                        Log.e(TAG, "frpc stderr: " + cleanLine);
                        errorBuffer.append(cleanLine).append("\n");
                        sendOutput("[错误] " + cleanLine);
                    }
                } catch (IOException e) {
                    Log.e(TAG, "Error reading frpc stderr", e);
                }
            });
            stderrThread.start();

            // 启动读取输出的线程
            Thread stdoutThread = new Thread(() -> {
                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(frpcProcess.getInputStream()))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        // 清理 ANSI 转义码
                        String cleanLine = removeAnsiCodes(line);
                        Log.d(TAG, "frpc: " + cleanLine);
                        outputBuffer.append(cleanLine).append("\n");
                        // 发送输出到Activity
                        sendOutput(cleanLine);
                    }
                    // 等待错误输出线程结束
                    try {
                        stderrThread.join(1000); // 最多等待1秒
                    } catch (InterruptedException e) {
                        Log.w(TAG, "Interrupted while waiting for stderr thread", e);
                    }
                    
                    // 进程退出
                    int exitCode = frpcProcess.waitFor();
                    Log.e(TAG, "Frpc process exited with code: " + exitCode);
                    sendOutput("------------------------------------");
                    sendOutput("[进程退出，退出码: " + exitCode + "]");
                    
                    // 分析错误原因并提供友好提示
                    if (exitCode != 0) {
                        // 合并标准输出和错误输出进行分析
                        String allOutput = outputBuffer.toString() + errorBuffer.toString();
                        String errorSummary = analyzeError(allOutput, exitCode);
                        if (errorSummary != null && !errorSummary.isEmpty()) {
                            sendOutput("");
                            sendOutput("========== 错误分析 ==========");
                            sendOutput(errorSummary);
                            sendOutput("==============================");
                        }
                    }
                    
                    isRunning = false;
                    frpcProcess = null;
                } catch (IOException e) {
                    Log.e(TAG, "Error reading frpc output", e);
                    sendOutput("[错误: " + e.getMessage() + "]");
                } catch (InterruptedException e) {
                    Log.e(TAG, "Interrupted while waiting for process", e);
                    sendOutput("[中断: " + e.getMessage() + "]");
                }
            });
            stdoutThread.start();
            
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
    
    /**
     * 清理 ANSI 转义码（颜色代码等）
     */
    private String removeAnsiCodes(String text) {
        if (text == null) {
            return "";
        }
        // 移除 ANSI 转义序列，格式如 [0m, [1;33m, [1;34m 等
        return text.replaceAll("\u001B\\[[0-9;]*[mK]", "");
    }
    
    /**
     * 分析错误信息并提供友好的提示
     */
    private String analyzeError(String errorText, int exitCode) {
        if (errorText == null || errorText.isEmpty()) {
            return "进程异常退出，退出码: " + exitCode;
        }
        
        StringBuilder analysis = new StringBuilder();
        String lowerError = errorText.toLowerCase();
        
        // 按优先级检查错误，只显示最相关的错误类型
        
        // 1. 检查连接超时错误（最高优先级）
        if (lowerError.contains("i/o timeout") || (lowerError.contains("dial tcp") && lowerError.contains("timeout"))) {
            analysis.append("❌ 连接服务器超时\n");
            analysis.append("\n错误详情：\n");
            analysis.append("无法连接到服务器，连接请求超时。\n");
            analysis.append("\n可能的原因：\n");
            analysis.append("1. 服务器地址或端口配置错误（当前配置: 1.2.3.4:1234）\n");
            analysis.append("2. 网络连接问题（请检查设备网络连接）\n");
            analysis.append("3. 服务器防火墙阻止了连接\n");
            analysis.append("4. 服务器未运行或不可访问\n");
            analysis.append("5. 服务器地址是内网地址，但设备不在同一网络\n");
            analysis.append("\n排查步骤：\n");
            analysis.append("1. 检查设置中的服务器地址和端口是否正确\n");
            analysis.append("2. 确认设备网络连接正常（可以访问其他网站）\n");
            analysis.append("3. 如果服务器地址是公网IP，确认服务器是否正常运行\n");
            analysis.append("4. 如果服务器地址是内网IP，确认设备是否在同一网络\n");
            analysis.append("5. 尝试使用其他网络环境测试（如切换到移动数据）\n");
            analysis.append("6. 检查服务器防火墙是否允许该端口连接\n");
            return analysis.toString(); // 连接超时是主要原因，直接返回
        }
        
        // 2. 检查连接拒绝错误
        if (lowerError.contains("connection refused") || lowerError.contains("refused")) {
            analysis.append("❌ 连接被拒绝\n");
            analysis.append("\n错误详情：\n");
            analysis.append("服务器拒绝了连接请求。\n");
            analysis.append("\n可能的原因：\n");
            analysis.append("1. 服务器端口未开放或服务未运行\n");
            analysis.append("2. 服务器地址配置错误\n");
            analysis.append("3. 防火墙阻止了连接\n");
            analysis.append("\n建议：\n");
            analysis.append("- 确认服务器地址和端口正确\n");
            analysis.append("- 检查服务器是否正常运行\n");
            analysis.append("- 检查服务器防火墙设置\n");
            return analysis.toString();
        }
        
        // 3. 检查 DNS 解析错误
        if (lowerError.contains("no such host") || (lowerError.contains("dns") && lowerError.contains("error"))) {
            analysis.append("❌ DNS 解析失败\n");
            analysis.append("\n错误详情：\n");
            analysis.append("无法解析服务器地址（域名）。\n");
            analysis.append("\n可能的原因：\n");
            analysis.append("1. 服务器地址（域名）无法解析\n");
            analysis.append("2. DNS 服务器配置问题\n");
            analysis.append("3. 网络连接问题\n");
            analysis.append("\n建议：\n");
            analysis.append("- 检查服务器地址是否正确\n");
            analysis.append("- 尝试使用 IP 地址代替域名\n");
            analysis.append("- 检查设备的 DNS 设置\n");
            return analysis.toString();
        }
        
        // 4. 检查登录失败错误（只有在没有连接问题的情况下才显示）
        if (lowerError.contains("login") && lowerError.contains("fail") && 
            !lowerError.contains("timeout") && !lowerError.contains("refused")) {
            analysis.append("❌ 登录服务器失败\n");
            analysis.append("\n错误详情：\n");
            analysis.append("已连接到服务器，但认证失败。\n");
            analysis.append("\n可能的原因：\n");
            analysis.append("1. 认证令牌（auth.token）配置错误\n");
            analysis.append("2. 服务器认证配置不匹配\n");
            analysis.append("3. 服务器拒绝认证\n");
            analysis.append("\n建议：\n");
            analysis.append("- 检查设置中的认证令牌是否正确\n");
            analysis.append("- 确认服务器端的认证配置\n");
            analysis.append("- 查看日志中的配置文件内容，确认 auth.token 设置\n");
            return analysis.toString();
        }
        
        // 5. 检查配置文件错误（更精确的匹配）
        if ((lowerError.contains("config") && (lowerError.contains("error") || lowerError.contains("invalid"))) ||
            (lowerError.contains("toml") && (lowerError.contains("error") || lowerError.contains("parse")))) {
            analysis.append("❌ 配置文件错误\n");
            analysis.append("\n错误详情：\n");
            analysis.append("配置文件格式错误或无法读取。\n");
            analysis.append("\n可能的原因：\n");
            analysis.append("1. 配置文件格式错误\n");
            analysis.append("2. 配置文件路径不正确\n");
            analysis.append("3. 配置文件权限问题\n");
            analysis.append("\n建议：\n");
            analysis.append("- 检查配置文件格式是否正确\n");
            analysis.append("- 查看日志中的配置文件内容\n");
            analysis.append("- 确认配置文件路径正确\n");
            return analysis.toString();
        }
        
        // 如果没有匹配到特定错误，提供通用提示
        analysis.append("❌ 进程异常退出\n");
        analysis.append("\n退出码: ").append(exitCode).append("\n");
        analysis.append("\n建议：\n");
        analysis.append("- 查看上方的详细错误信息\n");
        analysis.append("- 检查配置是否正确\n");
        analysis.append("- 确认网络连接正常\n");
        
        return analysis.toString();
    }
    
    /**
     * 转义 shell 命令中的特殊字符
     * 将命令用单引号包裹，并转义其中的单引号
     */
    private String escapeForShell(String command) {
        // 将单引号转义为 '\'' (结束引号 + 转义的单引号 + 开始引号)
        return "'" + command.replace("'", "'\\''") + "'";
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

    private void modifyTomlFile(String tomlPath, String serverAddr, int serverPort, String authToken, 
                                 int localPort, String name, int remotePort) throws IOException {
        File file = new File(tomlPath);
        StringBuilder content = new StringBuilder();
        
        // 注意：使用 FileInputStream 而不是 Files.newInputStream() 以兼容 API 21+
        @SuppressWarnings({"IOResource", "resource"})  // 使用 FileInputStream 以兼容 API 21+，Files.newInputStream() 需要 API 26+
        FileInputStream fileInputStream = new FileInputStream(file);
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(fileInputStream))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String trimmedLine = line.trim();
                // 更新服务器地址
                if (trimmedLine.startsWith("serverAddr = ")) {
                    content.append("serverAddr = \"").append(serverAddr).append("\"\n");
                }
                // 更新服务器端口
                else if (trimmedLine.startsWith("serverPort = ")) {
                    content.append("serverPort = ").append(serverPort).append("\n");
                }
                // 更新认证令牌
                else if (trimmedLine.startsWith("auth.token = ")) {
                    content.append("auth.token = \"").append(authToken).append("\"\n");
                }
                // 更新代理名称
                else if (trimmedLine.startsWith("name = ")) {
                    content.append("name = \"").append(name).append("\"\n");
                }
                // 更新本地端口
                else if (trimmedLine.startsWith("localPort = ")) {
                    content.append("localPort = ").append(localPort).append("\n");
                }
                // 更新远程端口
                else if (trimmedLine.startsWith("remotePort = ")) {
                    content.append("remotePort = ").append(remotePort).append("\n");
                }
                // 保持其他行不变
                else {
                    content.append(line).append("\n");
                }
            }
        }
        
        try (FileOutputStream fos = new FileOutputStream(file)) {
            fos.write(content.toString().getBytes());
        }
        Log.d(TAG, "Toml file modified with user settings");
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

