package github.lms.lemuel.commondata.domain;

public class DataSourceNotFoundException extends RuntimeException {

    public DataSourceNotFoundException(String code) {
        super("데이터소스를 찾을 수 없습니다: " + code);
    }
}
