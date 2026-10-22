package dev.comfybatch.domain;

public class NotFoundException extends RuntimeException {
    public NotFoundException() {
        super("Not found");
    }
}
