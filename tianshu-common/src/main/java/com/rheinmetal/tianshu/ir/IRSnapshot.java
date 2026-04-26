package com.rheinmetal.tianshu.ir;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class IRSnapshot {
    private static final int MAGIC = 0x54495231;
    private static final int VERSION = 1;

    public final String fingerprint;
    public final String[] reverseLookupArray;
    public final String[] localizedNameArray;
    public final String[][] primaryAliasTokensArray;
    public final String[][] fallbackAliasTokensArray;
    public final int[] indexPool;
    public final long[] directoryKeys;
    public final long[] directoryValues;

    public IRSnapshot(
        String fingerprint,
        String[] reverseLookupArray,
        String[] localizedNameArray,
        String[][] primaryAliasTokensArray,
        String[][] fallbackAliasTokensArray,
        int[] indexPool,
        long[] directoryKeys,
        long[] directoryValues
    ) {
        this.fingerprint = fingerprint;
        this.reverseLookupArray = reverseLookupArray;
        this.localizedNameArray = localizedNameArray;
        this.primaryAliasTokensArray = primaryAliasTokensArray;
        this.fallbackAliasTokensArray = fallbackAliasTokensArray;
        this.indexPool = indexPool;
        this.directoryKeys = directoryKeys;
        this.directoryValues = directoryValues;
    }

    public void writeTo(DataOutputStream output) throws IOException {
        output.writeInt(MAGIC);
        output.writeInt(VERSION);
        output.writeUTF(fingerprint);
        writeStringArray(output, reverseLookupArray);
        writeStringArray(output, localizedNameArray);
        writeNestedStringArray(output, primaryAliasTokensArray);
        writeNestedStringArray(output, fallbackAliasTokensArray);
        writeIntArray(output, indexPool);
        writeLongArray(output, directoryKeys);
        writeLongArray(output, directoryValues);
    }

    public static IRSnapshot readFrom(DataInputStream input) throws IOException {
        int magic = input.readInt();
        if (magic != MAGIC) {
            throw new IOException("Invalid IR snapshot magic: " + magic);
        }
        int version = input.readInt();
        if (version != VERSION) {
            throw new IOException("Unsupported IR snapshot version: " + version);
        }
        return new IRSnapshot(
                input.readUTF(),
                readStringArray(input),
                readStringArray(input),
                readNestedStringArray(input),
                readNestedStringArray(input),
                readIntArray(input),
                readLongArray(input),
                readLongArray(input)
        );
    }
    public Map<String, List<String>> rebuildRawDictionary() {
        LinkedHashMap<String, List<String>> rawDict = new LinkedHashMap<>(reverseLookupArray.length);
        for (int i = 0; i < reverseLookupArray.length; i++) {
            List<String> aliases = new ArrayList<>();
            aliases.add(localizedNameArray[i]); // 主语言（中文原文）
            if (fallbackAliasTokensArray[i] != null && fallbackAliasTokensArray[i].length > 0) {
                aliases.add(IRBaseUtils.joinTokens(fallbackAliasTokensArray[i])); // 英文兜底拼接回去
            }
            rawDict.put(reverseLookupArray[i], aliases);
        }
        return rawDict;
    }
    private static void writeStringArray(DataOutputStream output, String[] values) throws IOException {
        output.writeInt(values.length);
        for (String value : values) {
            output.writeUTF(value == null ? "" : value);
        }
    }

    private static String[] readStringArray(DataInputStream input) throws IOException {
        int size = input.readInt();
        String[] values = new String[size];
        for (int i = 0; i < size; i++) {
            values[i] = input.readUTF();
        }
        return values;
    }

    private static void writeNestedStringArray(DataOutputStream output, String[][] values) throws IOException {
        output.writeInt(values.length);
        for (String[] entry : values) {
            writeStringArray(output, entry == null ? new String[0] : entry);
        }
    }

    private static String[][] readNestedStringArray(DataInputStream input) throws IOException {
        int size = input.readInt();
        String[][] values = new String[size][];
        for (int i = 0; i < size; i++) {
            values[i] = readStringArray(input);
        }
        return values;
    }

    private static void writeIntArray(DataOutputStream output, int[] values) throws IOException {
        output.writeInt(values.length);
        for (int value : values) {
            output.writeInt(value);
        }
    }

    private static int[] readIntArray(DataInputStream input) throws IOException {
        int size = input.readInt();
        int[] values = new int[size];
        for (int i = 0; i < size; i++) {
            values[i] = input.readInt();
        }
        return values;
    }

    private static void writeLongArray(DataOutputStream output, long[] values) throws IOException {
        output.writeInt(values.length);
        for (long value : values) {
            output.writeLong(value);
        }
    }

    private static long[] readLongArray(DataInputStream input) throws IOException {
        int size = input.readInt();
        long[] values = new long[size];
        for (int i = 0; i < size; i++) {
            values[i] = input.readLong();
        }
        return values;
    }

}
