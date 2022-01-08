package org.qortal.utils;

import org.apache.commons.io.FileUtils;
import org.apache.logging.log4j.LogManager;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

public class LoggingUtils {

    public static void fixLegacyLog4j2Properties() {
        Path log4j2PropertiesPath = Paths.get("log4j2.properties");
        if (Files.exists(log4j2PropertiesPath)) {
            try {
                String content = FileUtils.readFileToString(log4j2PropertiesPath.toFile(), "UTF-8");
                if (content.contains("${dirname:-}")) {
                    content = content.replace("${dirname:-}", "./");
                    FileUtils.writeStringToFile(log4j2PropertiesPath.toFile(), content, "UTF-8");

                    // Force reload the log4j2.properties file
                    ((org.apache.logging.log4j.core.LoggerContext) LogManager.getContext(false)).reconfigure();
                }
            } catch (IOException e) {
                // Not much we can do here
            }
        }
    }

}
