package com.platform.skill;

/**
 * CR-147: 묶음을 올릴 때 거부하지 않고 남기는 경고 (T3-17 3.2 의 7).
 * 판에 코드로 저장하고 문구는 화면이 정한다.
 */
public enum SkillBundleWarningCode {
    /** 안내서가 묶음 안 폴더의 파일을 가리키는데 그 파일이 없다. */
    MISSING_REFERENCE,
    /** 벗겨낸 최상위 폴더 이름과 앞머리 name 이 다르다. */
    FOLDER_NAME_MISMATCH,
    /** scripts 폴더에 실행 컨테이너가 돌리지 못하는 확장자의 파일이 있다. */
    UNSUPPORTED_SCRIPT
}
