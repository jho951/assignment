package io.github.jho951.assignment.job.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class RequestHashServiceTests {

    private final RequestHashService requestHashService = new RequestHashService();

    @Test
    void shouldReturnStableHashForSameImageUrl() {
        String imageUrl = "https://example.com/input.png";

        String first = requestHashService.hashImageUrl(imageUrl);
        String second = requestHashService.hashImageUrl(imageUrl);

        assertThat(first).isEqualTo(second);
        assertThat(first).hasSize(64);
    }

    @Test
    void shouldReturnDifferentHashesForDifferentImageUrls() {
        String first = requestHashService.hashImageUrl("https://example.com/input-1.png");
        String second = requestHashService.hashImageUrl("https://example.com/input-2.png");

        assertThat(first).isNotEqualTo(second);
    }
}
