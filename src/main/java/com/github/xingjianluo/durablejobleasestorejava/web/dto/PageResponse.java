package com.github.xingjianluo.durablejobleasestorejava.web.dto;

import java.util.List;

public record PageResponse(List<?> items, String nextCursor, boolean hasMore) {
}
