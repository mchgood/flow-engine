package io.github.mchgood.flow.spi;

public record SourceLocation(String source, int line, int column) {
    @Override public String toString() { return source+":"+line+":"+column; }
}
