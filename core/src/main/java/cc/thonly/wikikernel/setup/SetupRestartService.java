package cc.thonly.wikikernel.setup;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 安装完成后自动重启：以原命令行（{@code java -jar xxx.jar ...}）拉起新进程。
 * 仅支持 jar 启动（IDE 里运行主类时无法自动重启，会返回 false 提示手动重启）。
 */
@Service
public class SetupRestartService {

    private static final Logger log = LoggerFactory.getLogger(SetupRestartService.class);

    /** 拉起新进程；成功返回 true（调用方负责随后 {@code System.exit(0)}）。 */
    public boolean restart() {
        String command = System.getProperty("sun.java.command", "").trim();
        if (!command.contains(".jar")) {
            log.warn("安装向导：当前不是 java -jar 启动，跳过自动重启（请手动重启）: {}", command);
            return false;
        }
        List<String> parts = splitCommand(command);
        if (parts.isEmpty()) {
            return false;
        }
        String javaExecutable = Path.of(System.getProperty("java.home"), "bin",
                isWindows() ? "java.exe" : "java").toString();
        List<String> cmd = new ArrayList<>(List.of(javaExecutable,
                "-Dfile.encoding=UTF-8", "-Dstdout.encoding=UTF-8", "-Dstderr.encoding=UTF-8", "-jar"));
        cmd.addAll(parts);
        try {
            ProcessBuilder builder = new ProcessBuilder(cmd);
            builder.directory(new File(System.getProperty("user.dir")));
            builder.inheritIO();
            builder.start();
            log.info("安装向导：已启动新进程 {}", String.join(" ", cmd));
            return true;
        } catch (IOException exception) {
            log.error("安装向导：启动新进程失败", exception);
            return false;
        }
    }

    /** 简单命令行切分（支持双引号包裹的参数）。 */
    private static List<String> splitCommand(String command) {
        List<String> parts = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < command.length(); i++) {
            char c = command.charAt(i);
            if (c == '"') {
                quoted = !quoted;
            } else if (Character.isWhitespace(c) && !quoted) {
                if (current.length() > 0) {
                    parts.add(current.toString());
                    current.setLength(0);
                }
            } else {
                current.append(c);
            }
        }
        if (current.length() > 0) {
            parts.add(current.toString());
        }
        return parts;
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }
}
