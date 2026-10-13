package com.interview;
final class AppError extends RuntimeException {
    final int status;
    AppError(String message) { this(message, 400); }
    AppError(String message, int status) { super(message); this.status = status; }
}
