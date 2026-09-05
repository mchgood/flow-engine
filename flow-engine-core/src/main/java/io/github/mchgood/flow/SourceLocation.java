package io.github.mchgood.flow;

public record SourceLocation(String source, int line, int column) {
    @Override public String toString() { return source+":"+line+":"+column; }
}
