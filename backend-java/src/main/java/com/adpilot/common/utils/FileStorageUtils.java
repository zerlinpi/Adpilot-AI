package com.adpilot.common.utils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.UUID;

public class FileStorageUtils {

    public static String storeFile(byte[] data, String directory, String extension) throws IOException {
        Path dir = Paths.get(directory);
        Files.createDirectories(dir);
        String filename = UUID.randomUUID() + "." + extension;
        Files.write(dir.resolve(filename), data);
        return filename;
    }

    public static String generateFilename(String originalName) {
        String ext = "";
        int dot = originalName.lastIndexOf('.');
        if (dot > 0) ext = originalName.substring(dot);
        return UUID.randomUUID() + ext;
    }
}
