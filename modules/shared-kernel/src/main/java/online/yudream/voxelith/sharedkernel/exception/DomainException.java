package online.yudream.voxelith.sharedkernel.exception;

/**
 * 领域异常基类。领域层只允许抛出本类及其子类，不依赖任何框架异常。
 */
public abstract class DomainException extends RuntimeException {

    private final String code;

    protected DomainException(String code, String message) {
        super(message);
        this.code = code;
    }

    protected DomainException(String code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
