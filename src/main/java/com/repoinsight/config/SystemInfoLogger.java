package com.repoinsight.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.nio.charset.Charset;
import java.nio.file.Path;

/**
 * Logs resolved system attributes at startup so operators can confirm the app
 * is running with the correct environment on any OS (Windows, macOS, Linux).
 */
@Component
@Slf4j
public class SystemInfoLogger {

    @Value("${analysis.output-dir}")
    private String outputDir;

    @Value("${github.copilot.model:gpt-4o}")
    private String copilotModel;

    @EventListener(ApplicationReadyEvent.class)
    public void logSystemInfo() {
        log.info("═══════════════════════════════════════════════════════");
        log.info(" RepoDocGen — System Information");
        log.info("═══════════════════════════════════════════════════════");
        log.info(" OS              : {} {} ({})",
                System.getProperty("os.name"),
                System.getProperty("os.version"),
                System.getProperty("os.arch"));
        log.info(" JVM             : {} {}",
                System.getProperty("java.vendor"),
                System.getProperty("java.version"));
        log.info(" Default charset : {} (should be UTF-8)",
                Charset.defaultCharset().name());
        log.info(" Temp dir        : {}", System.getProperty("java.io.tmpdir"));
        log.info(" Output dir      : {}", Path.of(outputDir).toAbsolutePath());
        log.info(" Copilot model   : {}", copilotModel);
        log.info(" Working dir     : {}", System.getProperty("user.dir"));
        log.info("═══════════════════════════════════════════════════════");

        if (!"UTF-8".equalsIgnoreCase(Charset.defaultCharset().name())) {
            log.warn("Default JVM charset is {} — recommend launching with -Dfile.encoding=UTF-8 " +
                     "to ensure source files from GitHub are decoded correctly.",
                     Charset.defaultCharset().name());
        }
    }
}
