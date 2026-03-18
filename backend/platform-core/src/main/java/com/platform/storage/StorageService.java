package com.platform.storage;

import java.io.InputStream;

/**
 * 파일 스토리지 추상화 (PRD-114, PRD-115).
 * Profile에 따라 LocalStorageService 또는 S3StorageService가 활성화된다.
 */
public interface StorageService {

    /**
     * 파일을 저장하고 접근 경로를 반환한다.
     *
     * @param tenantId  테넌트 식별자
     * @param category  분류 (e.g. "documents", "images")
     * @param filename  원본 파일명
     * @param input     파일 데이터
     * @return 저장된 파일의 논리 경로
     */
    String save(String tenantId, String category, String filename, InputStream input);

    /**
     * 경로로부터 파일을 읽어온다.
     */
    InputStream load(String path);

    /**
     * 파일을 삭제한다.
     */
    boolean delete(String path);

    /**
     * 파일 존재 여부를 확인한다.
     */
    boolean exists(String path);
}
