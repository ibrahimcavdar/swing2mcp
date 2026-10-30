package io.github.swing2mcp.agent;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Minimal class file reader: the shape of a class (super type, interfaces, fields, methods) and,
 * per method, a hash of its bytecode. The hash resolves constant-pool references to their values,
 * so a method whose code did not change keeps its hash even when edits elsewhere in the file
 * renumber the constant pool. Line numbers and debug tables are ignored on purpose.
 */
final class ClassInfo {

    static final class MethodInfo {
        final long hash;
        /** Methods of the same class called from this one (name + descriptor). */
        final Set<String> calls;
        /** Classes instantiated by this method (binary names with dots). */
        final Set<String> news;

        MethodInfo(long hash, Set<String> calls, Set<String> news) {
            this.hash = hash;
            this.calls = calls;
            this.news = news;
        }
    }

    final String name;
    final String superName;
    final List<String> interfaces = new ArrayList<String>();
    /** name -> descriptor */
    final Map<String, String> fields = new LinkedHashMap<String, String>();
    /** name + descriptor, e.g. "saveCustomer()V" */
    final Map<String, MethodInfo> methods = new LinkedHashMap<String, MethodInfo>();

    private ClassInfo(String name, String superName) {
        this.name = name;
        this.superName = superName;
    }

    // ------------------------------------------------------------------ parsing

    private static final int UTF8 = 1, INTEGER = 3, FLOAT = 4, LONG = 5, DOUBLE = 6, CLASS = 7, STRING = 8,
            FIELDREF = 9, METHODREF = 10, IMETHODREF = 11, NAME_AND_TYPE = 12, METHOD_HANDLE = 15,
            METHOD_TYPE = 16, DYNAMIC = 17, INVOKE_DYNAMIC = 18, MODULE = 19, PACKAGE = 20;

    static ClassInfo parse(byte[] bytes) throws IOException {
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes));
        if (in.readInt() != 0xCAFEBABE) {
            throw new IOException("not a class file");
        }
        in.readUnsignedShort();
        in.readUnsignedShort();
        int count = in.readUnsignedShort();
        int[] tags = new int[count];
        Object[] values = new Object[count]; // String for UTF8, int[] for references, Number for numbers
        for (int i = 1; i < count; i++) {
            int tag = in.readUnsignedByte();
            tags[i] = tag;
            switch (tag) {
                case UTF8: values[i] = in.readUTF(); break;
                case INTEGER: values[i] = in.readInt(); break;
                case FLOAT: values[i] = in.readFloat(); break;
                case LONG: values[i] = in.readLong(); i++; break;
                case DOUBLE: values[i] = in.readDouble(); i++; break;
                case CLASS: case STRING: case METHOD_TYPE: case MODULE: case PACKAGE:
                    values[i] = new int[]{in.readUnsignedShort()};
                    break;
                case FIELDREF: case METHODREF: case IMETHODREF: case NAME_AND_TYPE: case DYNAMIC: case INVOKE_DYNAMIC:
                    values[i] = new int[]{in.readUnsignedShort(), in.readUnsignedShort()};
                    break;
                case METHOD_HANDLE:
                    values[i] = new int[]{in.readUnsignedByte(), in.readUnsignedShort()};
                    break;
                default:
                    throw new IOException("unknown constant pool tag " + tag);
            }
        }
        ConstantPool pool = new ConstantPool(tags, values);
        in.readUnsignedShort(); // access flags
        String thisName = pool.className(in.readUnsignedShort());
        int superIndex = in.readUnsignedShort();
        ClassInfo info = new ClassInfo(thisName, superIndex == 0 ? null : pool.className(superIndex));
        int interfaceCount = in.readUnsignedShort();
        for (int i = 0; i < interfaceCount; i++) {
            info.interfaces.add(pool.className(in.readUnsignedShort()));
        }
        int fieldCount = in.readUnsignedShort();
        for (int i = 0; i < fieldCount; i++) {
            in.readUnsignedShort();
            String fieldName = pool.utf8(in.readUnsignedShort());
            info.fields.put(fieldName, pool.utf8(in.readUnsignedShort()));
            skipAttributes(in);
        }
        int methodCount = in.readUnsignedShort();
        for (int i = 0; i < methodCount; i++) {
            in.readUnsignedShort();
            String key = pool.utf8(in.readUnsignedShort()) + pool.utf8(in.readUnsignedShort());
            long hash = FNV_OFFSET;
            Set<String> calls = new HashSet<String>();
            Set<String> news = new HashSet<String>();
            int attributes = in.readUnsignedShort();
            for (int a = 0; a < attributes; a++) {
                String attribute = pool.utf8(in.readUnsignedShort());
                int length = in.readInt();
                byte[] data = new byte[length];
                in.readFully(data);
                if ("Code".equals(attribute)) {
                    hash = hashCode(data, pool, thisName, calls, news);
                }
            }
            info.methods.put(key, new MethodInfo(hash, calls, news));
        }
        return info;
    }

    private static void skipAttributes(DataInputStream in) throws IOException {
        int attributes = in.readUnsignedShort();
        for (int a = 0; a < attributes; a++) {
            in.readUnsignedShort();
            int length = in.readInt();
            in.skipBytes(length);
        }
    }

    // ------------------------------------------------------------------ bytecode hashing

    private static final long FNV_OFFSET = 0xcbf29ce484222325L;
    private static final long FNV_PRIME = 0x100000001b3L;

    private static long mix(long hash, int b) {
        return (hash ^ (b & 0xff)) * FNV_PRIME;
    }

    private static long mix(long hash, String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            hash = mix(hash, c >> 8);
            hash = mix(hash, c);
        }
        return mix(hash, 0);
    }

    /** Hash of a Code attribute: instructions with resolved constants, plus the exception table. */
    private static long hashCode(byte[] attribute, ConstantPool pool, String owner, Set<String> calls, Set<String> news) {
        int codeLength = readInt(attribute, 4);
        int start = 8;
        long hash = FNV_OFFSET;
        int pc = 0;
        while (pc < codeLength) {
            int at = start + pc;
            int op = attribute[at] & 0xff;
            hash = mix(hash, op);
            int length = instructionLength(attribute, start, pc, op);
            if (op == 0x12) { // ldc: u1 index
                hash = mix(hash, pool.describe(attribute[at + 1] & 0xff));
            } else if (hasConstantOperand(op)) {
                int index = readShort(attribute, at + 1);
                hash = mix(hash, pool.describe(index));
                if (op >= 0xb6 && op <= 0xb9) { // invokevirtual/special/static/interface
                    String[] ref = pool.memberRef(index);
                    if (ref[0].equals(owner)) {
                        calls.add(ref[1] + ref[2]);
                    }
                } else if (op == 0xbb) { // new
                    news.add(pool.className(index));
                }
                for (int k = 3; k < length; k++) {
                    hash = mix(hash, attribute[at + k]);
                }
            } else {
                for (int k = 1; k < length; k++) {
                    hash = mix(hash, attribute[at + k]);
                }
            }
            pc += length;
        }
        int table = start + codeLength;
        int entries = readShort(attribute, table);
        for (int e = 0; e < entries; e++) {
            int entry = table + 2 + e * 8;
            for (int k = 0; k < 6; k++) {
                hash = mix(hash, attribute[entry + k]);
            }
            int catchType = readShort(attribute, entry + 6);
            hash = mix(hash, catchType == 0 ? "any" : pool.describe(catchType));
        }
        return hash;
    }

    private static boolean hasConstantOperand(int op) {
        return op == 0x13 || op == 0x14 // ldc_w, ldc2_w
                || (op >= 0xb2 && op <= 0xba) // field access, invokes, invokedynamic
                || op == 0xbb || op == 0xbd || op == 0xc0 || op == 0xc1 // new, anewarray, checkcast, instanceof
                || op == 0xc5; // multianewarray
    }

    private static int instructionLength(byte[] b, int start, int pc, int op) {
        if (op == 0xaa || op == 0xab) { // tableswitch / lookupswitch
            int pad = (4 - ((pc + 1) % 4)) % 4;
            int base = start + pc + 1 + pad;
            if (op == 0xaa) {
                int low = readInt(b, base + 4);
                int high = readInt(b, base + 8);
                return 1 + pad + 12 + 4 * (high - low + 1);
            }
            int pairs = readInt(b, base + 4);
            return 1 + pad + 8 + 8 * pairs;
        }
        if (op == 0xc4) { // wide
            return (b[start + pc + 1] & 0xff) == 0x84 ? 6 : 4;
        }
        if (op == 0x10 || op == 0x12 || (op >= 0x15 && op <= 0x19) || (op >= 0x36 && op <= 0x3a)
                || op == 0xa9 || op == 0xbc) {
            return 2;
        }
        if (op == 0x11 || op == 0x13 || op == 0x14 || op == 0x84 || (op >= 0x99 && op <= 0xa8)
                || (op >= 0xb2 && op <= 0xb8) || op == 0xbb || op == 0xbd || op == 0xc0 || op == 0xc1
                || op == 0xc6 || op == 0xc7) {
            return 3;
        }
        if (op == 0xc5) {
            return 4;
        }
        if (op == 0xb9 || op == 0xba || op == 0xc8 || op == 0xc9) {
            return 5;
        }
        return 1;
    }

    private static int readShort(byte[] b, int at) {
        return ((b[at] & 0xff) << 8) | (b[at + 1] & 0xff);
    }

    private static int readInt(byte[] b, int at) {
        return ((b[at] & 0xff) << 24) | ((b[at + 1] & 0xff) << 16) | ((b[at + 2] & 0xff) << 8) | (b[at + 3] & 0xff);
    }

    // ------------------------------------------------------------------ constant pool

    private static final class ConstantPool {
        private final int[] tags;
        private final Object[] values;
        private final Map<Integer, String> described = new HashMap<Integer, String>();

        ConstantPool(int[] tags, Object[] values) {
            this.tags = tags;
            this.values = values;
        }

        String utf8(int index) {
            return (String) values[index];
        }

        String className(int index) {
            return utf8(((int[]) values[index])[0]).replace('/', '.');
        }

        /** {owner class, member name, descriptor} of a field or method reference. */
        String[] memberRef(int index) {
            int[] ref = (int[]) values[index];
            int[] nameAndType = (int[]) values[ref[1]];
            return new String[]{className(ref[0]), utf8(nameAndType[0]), utf8(nameAndType[1])};
        }

        /** A stable textual value of a constant, independent of its position in the pool. */
        String describe(int index) {
            String cached = described.get(index);
            if (cached != null) {
                return cached;
            }
            String result;
            Object value = values[index];
            switch (tags[index]) {
                case UTF8: result = "u:" + value; break;
                case INTEGER: case FLOAT: case LONG: case DOUBLE: result = "n:" + value; break;
                case CLASS: result = "c:" + className(index); break;
                case STRING: result = "s:" + utf8(((int[]) value)[0]); break;
                case FIELDREF: case METHODREF: case IMETHODREF: {
                    String[] ref = memberRef(index);
                    result = "m:" + ref[0] + "." + ref[1] + ref[2];
                    break;
                }
                case NAME_AND_TYPE: {
                    int[] ref = (int[]) value;
                    result = "t:" + utf8(ref[0]) + utf8(ref[1]);
                    break;
                }
                case METHOD_HANDLE: {
                    int[] ref = (int[]) value;
                    result = "h:" + ref[0] + describe(ref[1]);
                    break;
                }
                case METHOD_TYPE: result = "y:" + utf8(((int[]) value)[0]); break;
                case DYNAMIC: case INVOKE_DYNAMIC: {
                    // The bootstrap index points into a class attribute; the call site's name and
                    // type identify it well enough for change detection.
                    int[] ref = (int[]) value;
                    result = "d:" + ref[0] + describe(ref[1]);
                    break;
                }
                default: result = "?" + index;
            }
            described.put(index, result);
            return result;
        }
    }
}
