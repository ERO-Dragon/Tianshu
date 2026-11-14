package com.rheinmetal.tianshu.utils;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;

public class PathUtils {

    public static File getSafeModelDir(File originalDir) {
        String path = originalDir.getAbsolutePath();
        boolean hasNonAscii = false;

        for (char c : path.toCharArray()) {
            if (c > 127) { hasNonAscii = true; break; }
        }

        if (!hasNonAscii) return originalDir;

        String tempBase = System.getProperty("java.io.tmpdir");
        File safeDir = new File(tempBase, "tianshu_link_" + originalDir.getName().hashCode());

        if (safeDir.exists() && safeDir.isDirectory()) {
            return safeDir;
        }

        try {
            ProcessBuilder pb = new ProcessBuilder(
                    "cmd", "/c", "mklink", "/J",
                    safeDir.getAbsolutePath(),
                    originalDir.getAbsolutePath()
            );
            pb.redirectErrorStream(true);
            Process process = pb.start();

            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                while (reader.readLine() != null) {
                    // consume output to prevent process hang
                }
            }

            int exitCode = process.waitFor();
            if (exitCode == 0) {
                return safeDir;
            } else {
                return originalDir;
            }
        } catch (Exception e) {
            return originalDir;
        }
    }
}
