package online.yudream.voxelith.sharedkernel.result;

import java.util.Optional;
import java.util.function.Function;

/**
 * 轻量结果类型，用于应用层用例的可预期失败（不替代领域内的异常）。
 */
public sealed interface Result<T> permits Result.Ok, Result.Err {

    record Ok<T>(T value) implements Result<T> {
    }

    record Err<T>(String code, String message) implements Result<T> {
    }

    static <T> Result<T> ok(T value) {
        return new Ok<>(value);
    }

    static <T> Result<T> err(String code, String message) {
        return new Err<>(code, message);
    }

    default boolean isOk() {
        return this instanceof Ok<T>;
    }

    default Optional<T> toOptional() {
        return this instanceof Ok<T> ok ? Optional.ofNullable(ok.value()) : Optional.empty();
    }

    default <R> Result<R> map(Function<T, R> mapper) {
        if (this instanceof Ok<T> ok) {
            return ok(mapper.apply(ok.value()));
        }
        @SuppressWarnings("unchecked")
        Result<R> self = (Result<R>) this;
        return self;
    }
}
