package github.lms.lemuel.board.adapter.in.web.dto;

import github.lms.lemuel.board.application.port.in.BoardPage;

import java.util.List;
import java.util.function.Function;

public record BoardPageResponse<T>(
        List<T> content,
        int page,
        int size,
        long totalElements,
        int totalPages) {

    public static <S, T> BoardPageResponse<T> from(BoardPage<S> page, Function<S, T> mapper) {
        return new BoardPageResponse<>(
                page.content().stream().map(mapper).toList(),
                page.page(),
                page.size(),
                page.totalElements(),
                page.totalPages());
    }
}
