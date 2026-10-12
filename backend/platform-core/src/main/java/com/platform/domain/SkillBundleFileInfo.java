package com.platform.domain;

/**
 * CR-147: 내용을 뺀 스킬 묶음 파일 정보. 판 상세와 skill_open 목록용.
 */
public record SkillBundleFileInfo(String path, long sizeBytes, String sha256, boolean isText) {}
