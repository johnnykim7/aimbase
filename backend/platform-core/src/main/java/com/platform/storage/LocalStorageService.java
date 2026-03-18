package com.platform.storage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.UUID;

/**
 * 로컬 파일시스템 기반 스토리지 구현.
 * 저장 경로: {base-path}/{tenantId}/{category}/{uuid}_{filename}
 */
@Service
@Profile("!s3")
public class LocalStorageService implements StorageService {

    private static final Logger log = LoggerFactory.getLogger(LocalStorageService.class);

    private final Path basePath;

    public LocalStorageService(@Value("${storage.local.base-path:/data/aimbase}") String basePath) {
        this.basePath = Path.of(basePath);
        log.info("LocalStorageService initialized — basePath={}", this.basePath);
    }

    @Override
    public String save(String tenantId, String category, String filename, InputStream input) {
        String storedName = UUID.randomUUID() + "_" + filename;
        Path dir = basePath.resolve(tenantId).resolve(category);
        try {
            Files.createDirectories(dir);
            Path target = dir.resolve(storedName);
            Files.copy(input, target, StandardCopyOption.REPLACE_EXISTING);
            String logicalPath = tenantId + "/" + category + "/" + storedName;
            log.debug("File saved: {}", logicalPath);
            return logicalPath;
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to save file: " + filename, e);
        }
    }

    @Override
    public InputStream load(String path) {
        Path file = basePath.resolve(path);
        try {
            return Files.newInputStream(file);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to load file: " + path, e);
        }
    }

    @Override
    public boolean delete(String path) {
        Path file = basePath.resolve(path);
        try {
            return Files.deleteIfExists(file);
        } catch (IOException e) {
            log.warn("Failed to delete file: {}", path, e);
            return false;
        }
    }

    @Override
    public boolean exists(String path) {
        return Files.exists(basePath.resolve(path));
    }
}
