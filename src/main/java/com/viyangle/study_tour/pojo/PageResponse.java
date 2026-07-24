package com.viyangle.study_tour.pojo;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class PageResponse<T> {
    private List<T> items;
    private long total;
    private int pageNum;
    private int pageSize;
    private int pages;

    public static <T> PageResponse<T> of(List<T> items, long total, int pageNum, int pageSize) {
        int pages = pageSize <= 0 ? 0 : (int) Math.ceil((double) total / pageSize);
        return new PageResponse<>(items, total, pageNum, pageSize, pages);
    }
}
