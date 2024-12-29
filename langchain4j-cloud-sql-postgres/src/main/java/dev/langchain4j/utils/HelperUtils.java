package dev.langchain4j.utils;

public class HelperUtils {

    /**
     * Ensures that the given string is not null or blank
     * @param string string to check
     * @param name  name of the string to be used in the exception message
     */
    public static String isNotBlank(String string, String name) {
        if (string == null || string.trim().isEmpty()) {
            throw illegalArgument("%s cannot be null or blank", name);
        }
        return string;
    }

    /**
     * Constructs an {@link IllegalArgumentException} with the given formatted result.
     */
    public static IllegalArgumentException illegalArgument(String format, Object... args) {
        return new IllegalArgumentException(format.formatted(args));
    }

    /**
     * Ensures that the given expression is true
     * @param n the expression to be checked
     * @param msg the message to be used in the exception
     */ 
    public static int isGreaterThanZero(Integer n, String msg) {
        if (n == null || n <= 0) {
            throw illegalArgument("%s must be greater than zero, but is: %s", msg, n);
        }
        return n;
    }
}
