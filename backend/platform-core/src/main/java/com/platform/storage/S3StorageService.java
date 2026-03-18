package com.platform.storage;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import java.io.InputStream;

/**
 * S3 기반 스토리지 구현 (Placeholder).
 * S3 SDK 의존성 추가 후 구현 예정.
 */
@Service
@Profile("s3")
public class S3StorageService implements StorageService {

    @Override
    public String save(String tenantId, String category, String filename, InputStream input) {
        // TODO: S3 SDK 추가 후 구현 (aws-sdk-java-v2 s3)
        throw new UnsupportedOperationException("S3StorageService not yet implemented — add S3 SDK dependency first");
    }

    @Override
    public InputStream load(String path) {
        throw new UnsupportedOperationException("S3StorageService not yet implemented — add S3 SDK dependency first");
    }

    @Override
    public boolean delete(String path) {
        throw new UnsupportedOperationException("S3StorageService not yet implemented — add S3 SDK dependency first");
    }

    @Override
    public boolean exists(String path) {
        throw new UnsupportedOperationException("S3StorageService not yet implemented — add S3 SDK dependency first");
    }
}
