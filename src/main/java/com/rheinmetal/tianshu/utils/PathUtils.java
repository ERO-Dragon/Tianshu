package com.rheinmetal.tianshu.utils;

import com.rheinmetal.tianshu.Tianshu;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;

public class PathUtils {

    /**
     * 零拷贝黑科技：通过 Windows 目录联接解决中文路径问题
     */
    public static File getSafeModelDir(File originalDir) {
        String path = originalDir.getAbsolutePath();
        boolean hasNonAscii = false;
        
        for (char c : path.toCharArray()) {
            if (c > 127) { hasNonAscii = true; break; }
        }

        // 纯英文路径，直接放行
        if (!hasNonAscii) return originalDir;

        Tianshu.LOGGER.warn("检测到模型路径含非ASCII字符，启动零拷贝联接...");
        String tempBase = System.getProperty("java.io.tmpdir");
        // 在临时目录创建一个纯英文的“门”
        File safeDir = new File(tempBase, "tianshu_link_" + originalDir.getName().hashCode());

        // 如果“门”已经存在，直接复用，连 0.01 秒都省了
        if (safeDir.exists() && safeDir.isDirectory()) {
            return safeDir;
        }

        try {
            // 调用 Windows 底层 cmd 命令创建联接：mklink /J "纯英文门" "中文真实路径"
            ProcessBuilder pb = new ProcessBuilder(
                    "cmd", "/c", "mklink", "/J", 
                    safeDir.getAbsolutePath(), 
                    originalDir.getAbsolutePath()
            );
            pb.redirectErrorStream(true); // 把错误流合并到输出流
            Process process = pb.start();

            // 【关键】必须把命令执行的结果读出来，否则 Java 进程会假死卡住！
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    Tianshu.LOGGER.info("[系统联接]: {}", line);
                }
            }

            int exitCode = process.waitFor();
            if (exitCode == 0) {
                Tianshu.LOGGER.info("✅ 零拷贝联接创建成功: {} <-> {}", safeDir.getName(), originalDir.getName());
                return safeDir;
            } else {
                Tianshu.LOGGER.error("创建联接失败(可能系统不支持)，回退到原路径");
                return originalDir;
            }
        } catch (Exception e) {
            Tianshu.LOGGER.error("创建联接异常，回退到原路径", e);
            return originalDir;
        }
    }
}