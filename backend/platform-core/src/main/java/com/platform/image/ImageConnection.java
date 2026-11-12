package com.platform.image;

/** CR-184: 그림을 만들 OpenAI 연결(BIZ-230). apiKey 는 로그에 남기지 않는다. */
public record ImageConnection(String connectionId, String model, String quality, String apiKey) {

    @Override
    public String toString() {
        return "ImageConnection[connectionId=" + connectionId + ", model=" + model + ", quality=" + quality + "]";
    }
}
