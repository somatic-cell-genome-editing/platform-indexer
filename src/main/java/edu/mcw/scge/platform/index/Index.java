package edu.mcw.scge.platform.index;

import java.util.List;

public class Index {
    private static String index;
    private static String oldAlias;
    private static String newAlias;
    private static List<String> indices;

    public static String getIndex() {
        return index;
    }

    public static void setIndex(String index) {
        Index.index = index;
    }

    public static String getOldAlias() {
        return oldAlias;
    }

    public static void setOldAlias(String oldAlias) {
        Index.oldAlias = oldAlias;
    }

    public static String getNewAlias() {
        return newAlias;
    }

    public static void setNewAlias(String newAlias) {
        Index.newAlias = newAlias;
    }

    public static List<String> getIndices() {
        return indices;
    }

    public static void setIndices(List<String> indices) {
        Index.indices = indices;
    }
}
