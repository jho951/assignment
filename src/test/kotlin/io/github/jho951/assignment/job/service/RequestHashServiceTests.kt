package io.github.jho951.assignment.job.service

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class RequestHashServiceTests {

    private val requestHashService = RequestHashService()

    @Test
    fun shouldReturnStableHashForSameImageUrl() {
        val imageUrl = "https://example.com/input.png"

        val first = requestHashService.hashImageUrl(imageUrl)
        val second = requestHashService.hashImageUrl(imageUrl)

        assertThat(first).isEqualTo(second)
        assertThat(first).hasSize(64)
    }

    @Test
    fun shouldReturnDifferentHashesForDifferentImageUrls() {
        val first = requestHashService.hashImageUrl("https://example.com/input-1.png")
        val second = requestHashService.hashImageUrl("https://example.com/input-2.png")

        assertThat(first).isNotEqualTo(second)
    }
}
