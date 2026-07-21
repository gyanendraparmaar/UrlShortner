package com.gyanendra.urlshortener;

import java.security.SecureRandom;
import java.util.Objects;
import java.util.random.RandomGenerator;

import org.springframework.stereotype.Component;

@Component
class ShortCodeGenerator {

    static final int CODE_LENGTH = 11;
    private static final char[] BASE62_ALPHABET =
            "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ".toCharArray();

    private final RandomGenerator random;

    ShortCodeGenerator() {
        this(new SecureRandom());
    }

    ShortCodeGenerator(RandomGenerator random) {
        this.random = Objects.requireNonNull(random);
    }

    String generate() {
        StringBuilder code = new StringBuilder(CODE_LENGTH);
        for (int index = 0; index < CODE_LENGTH; index++) {
            code.append(BASE62_ALPHABET[random.nextInt(BASE62_ALPHABET.length)]);
        }
        return code.toString();
    }
}
