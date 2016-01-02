package org.rakam.util;

import io.netty.handler.codec.http.HttpResponseStatus;

import javax.annotation.Nullable;

import static com.google.common.base.Preconditions.checkNotNull;


public class ValidationUtil {
    public static void checkProject(String project) {
        checkNotNull(project, "project is null");
        if(!project.matches("^[0-9A-Za-z_]+$")) {
            throw new IllegalArgumentException("Project id is not valid.");
        }
    }
    public static void checkCollection(String collection) {
        checkNotNull(collection, "collection is null");
        if(!collection.matches("^[a-zA-Z_][a-zA-Z0-9_]*$")) {
            throw new IllegalArgumentException("Collection is not valid.");
        }
    }

    public static String checkTableColumn(String column, String type) {
        checkNotNull(type, type+" is null");
        if(!column.matches("^[a-zA-Z_][a-zA-Z0-9_]*$")) {
            throw new IllegalArgumentException(type+" is not valid.");
        }
        return column;
    }

    public static void checkArgument(boolean expression, @Nullable String errorMessage) {
        if (!expression) {
            throw new RakamException(errorMessage, HttpResponseStatus.BAD_REQUEST);
        }
    }
}
