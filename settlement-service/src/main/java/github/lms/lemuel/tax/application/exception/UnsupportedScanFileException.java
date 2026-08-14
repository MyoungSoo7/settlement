package github.lms.lemuel.tax.application.exception;

import github.lms.lemuel.common.exception.BusinessException;
import github.lms.lemuel.common.exception.ErrorCode;

/** 허용 목록 밖의 파일 형식 — 415. 형식 판단은 클라이언트 선언이 아니라 서버 허용 목록이 정본이다. */
public class UnsupportedScanFileException extends BusinessException {

    public UnsupportedScanFileException(String contentType) {
        super(ErrorCode.TAX_SCAN_UNSUPPORTED_FILE,
                "지원하지 않는 스캔 파일 형식입니다: " + contentType);
    }
}
