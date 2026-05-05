package io.github.jho951.assignment.job.service

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.NoSuchAlgorithmException
import org.springframework.stereotype.Service

@Service
class RequestHashService {

    fun hashImageUrl(imageUrl: String): String {
        try {
            val messageDigest = MessageDigest.getInstance("SHA-256")
            val digest = messageDigest.digest("imageUrl=$imageUrl".toByteArray(StandardCharsets.UTF_8))
            val builder = StringBuilder(digest.size * 2)
            for (value in digest) {
                builder.append(String.format("%02x", value))
            }
            return builder.toString()
        } catch (exception: NoSuchAlgorithmException) {
            throw IllegalStateException("SHA-256 algorithm is not available", exception)
        }
    }
}
