package com.wand.tools;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

/**
 * termlib 0.0.10 decides scroll vs selection from each motion event's delta.
 * A slow finger never crosses the touch slop in one frame, so the long-press
 * timer starts a text selection. This rewrites that one comparison to use the
 * distance from the finger-down point, via {@code PtyScrollSlop}.
 */
public final class TermlibGesturePatch {
    private static final String GESTURE_CLASS =
        "org/connectbot/terminal/TerminalKt$TerminalWithAccessibility$13$6$3$1$1$1.class";
    private static final String POINTER =
        "androidx/compose/ui/input/pointer/PointerInputChange";
    private static final String SLOP = "com/wand/app/ui/terminal/PtyScrollSlop";

    public static void main(String[] args) throws IOException {
        if (args.length != 2) {
            throw new IllegalArgumentException("usage: TermlibGesturePatch <input.aar> <output.aar>");
        }
        Path input = Path.of(args[0]);
        Path output = Path.of(args[1]);
        byte[] patchedClasses = null;
        byte[] original = Files.readAllBytes(input);
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(original))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if ("classes.jar".equals(entry.getName())) {
                    patchedClasses = patchClassesJar(zip.readAllBytes());
                    break;
                }
            }
        }
        if (patchedClasses == null) throw new IllegalStateException(input + " has no classes.jar");
        Files.createDirectories(output.getParent());
        try (
            ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(original));
            ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(output))
        ) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                byte[] data = "classes.jar".equals(entry.getName()) ? patchedClasses : zip.readAllBytes();
                writeEntry(out, entry, data);
            }
        }
    }

    static byte[] patchClassesJar(byte[] classesJar) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        boolean patched = false;
        try (
            ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(classesJar));
            ZipOutputStream out = new ZipOutputStream(buffer)
        ) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                byte[] data = zip.readAllBytes();
                if (GESTURE_CLASS.equals(entry.getName())) {
                    data = patchGestureClass(data);
                    patched = true;
                }
                writeEntry(out, entry, data);
            }
        }
        if (!patched) throw new IllegalStateException("gesture class missing: " + GESTURE_CLASS);
        return buffer.toByteArray();
    }

    static byte[] patchGestureClass(byte[] bytecode) {
        ClassNode node = new ClassNode();
        new ClassReader(bytecode).accept(node, 0);
        MethodNode method = null;
        for (MethodNode candidate : node.methods) {
            if ("invokeSuspend".equals(candidate.name)) method = candidate;
        }
        if (method == null) throw new IllegalStateException("invokeSuspend missing");
        int noted = noteFingerDown(method);
        int replaced = replacePerEventSlop(method);
        if (noted != 1 || replaced != 1) {
            throw new IllegalStateException(
                "expected one down anchor and one slop check, found down=" + noted + " slop=" + replaced
            );
        }
        method.maxStack += 6;
        ClassWriter writer = new ClassWriter(0);
        node.accept(writer);
        byte[] result = writer.toByteArray();
        String utf8 = new String(result, java.nio.charset.StandardCharsets.ISO_8859_1);
        if (!utf8.contains("noteDown") || !utf8.contains("displacementSquared")) {
            throw new IllegalStateException("patched gesture class is missing PtyScrollSlop calls");
        }
        if (utf8.contains("getDistanceSquared-impl")) {
            throw new IllegalStateException("per-event slop comparison is still present");
        }
        return result;
    }

    private static int noteFingerDown(MethodNode method) {
        int found = 0;
        for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
            if (!(insn instanceof org.objectweb.asm.tree.TypeInsnNode check)) continue;
            if (check.getOpcode() != Opcodes.CHECKCAST || !POINTER.equals(check.desc)) continue;
            if (!isLoad1(previousReal(check))) continue;
            AbstractInsnNode store = nextReal(check);
            if (!(store instanceof VarInsnNode stored) || stored.getOpcode() != Opcodes.ASTORE) continue;
            if (!followedBy(store, "getMode", 6)) continue;
            InsnList call = new InsnList();
            call.add(new VarInsnNode(Opcodes.ALOAD, 0));
            call.add(new VarInsnNode(Opcodes.ALOAD, stored.var));
            call.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, POINTER, "getPosition-F1C5BW0", "()J", false));
            call.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC, SLOP, "noteDown", "(Ljava/lang/Object;J)V", false
            ));
            method.instructions.insert(store, call);
            found++;
        }
        return found;
    }

    private static int replacePerEventSlop(MethodNode method) {
        int found = 0;
        for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
            if (!(insn instanceof MethodInsnNode distance)) continue;
            if (!"getDistanceSquared-impl".equals(distance.name)) continue;
            AbstractInsnNode loaded = previousReal(distance);
            if (!(loaded instanceof VarInsnNode) || !isLongLoad(loaded)) {
                throw new IllegalStateException("slop distance is not loaded from a long local");
            }
            int changeLocal = -1;
            for (AbstractInsnNode cursor = loaded; cursor != null; cursor = previousReal(cursor)) {
                if (cursor instanceof MethodInsnNode call && "positionChange".equals(call.name)) {
                    changeLocal = varIndex(previousReal(call));
                    break;
                }
            }
            if (changeLocal < 0) throw new IllegalStateException("could not find the pointer local for positionChange");
            InsnList call = new InsnList();
            call.add(new VarInsnNode(Opcodes.ALOAD, 0));
            call.add(new VarInsnNode(Opcodes.ALOAD, changeLocal));
            call.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, POINTER, "getPosition-F1C5BW0", "()J", false));
            call.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC, SLOP, "displacementSquared", "(Ljava/lang/Object;J)F", false
            ));
            method.instructions.insert(distance, call);
            method.instructions.remove(loaded);
            method.instructions.remove(distance);
            found++;
        }
        return found;
    }

    private static final int ALOAD_0 = 42;
    private static final int LLOAD_0 = 30;

    private static boolean followedBy(AbstractInsnNode insn, String methodName, int limit) {
        AbstractInsnNode cursor = nextReal(insn);
        for (int i = 0; i < limit && cursor != null; i++) {
            if (cursor instanceof MethodInsnNode call && methodName.equals(call.name)) return true;
            cursor = nextReal(cursor);
        }
        return false;
    }

    private static boolean isLoad1(AbstractInsnNode insn) {
        return varIndex(insn) == 1 && isReferenceLoad(insn);
    }

    private static boolean isReferenceLoad(AbstractInsnNode insn) {
        if (insn == null) return false;
        int opcode = insn.getOpcode();
        return opcode == Opcodes.ALOAD || (opcode >= ALOAD_0 && opcode <= ALOAD_0 + 3);
    }

    private static boolean isLongLoad(AbstractInsnNode insn) {
        int opcode = insn.getOpcode();
        return opcode == Opcodes.LLOAD || (opcode >= LLOAD_0 && opcode <= LLOAD_0 + 3);
    }

    private static int varIndex(AbstractInsnNode insn) {
        if (insn == null) return -1;
        int opcode = insn.getOpcode();
        if (opcode >= ALOAD_0 && opcode <= ALOAD_0 + 3) return opcode - ALOAD_0;
        if (opcode >= LLOAD_0 && opcode <= LLOAD_0 + 3) return opcode - LLOAD_0;
        if (insn instanceof VarInsnNode var && (opcode == Opcodes.ALOAD || opcode == Opcodes.LLOAD || opcode == Opcodes.ASTORE)) {
            return var.var;
        }
        return -1;
    }

    private static AbstractInsnNode previousReal(AbstractInsnNode insn) {
        AbstractInsnNode cursor = insn.getPrevious();
        while (cursor != null && cursor.getOpcode() < 0) cursor = cursor.getPrevious();
        return cursor;
    }

    private static AbstractInsnNode nextReal(AbstractInsnNode insn) {
        AbstractInsnNode cursor = insn.getNext();
        while (cursor != null && cursor.getOpcode() < 0) cursor = cursor.getNext();
        return cursor;
    }

    private static void writeEntry(ZipOutputStream out, ZipEntry source, byte[] data) throws IOException {
        ZipEntry dest = new ZipEntry(source.getName());
        dest.setTime(source.getTime());
        if (source.getMethod() == ZipEntry.STORED) {
            CRC32 crc = new CRC32();
            crc.update(data);
            dest.setMethod(ZipEntry.STORED);
            dest.setSize(data.length);
            dest.setCrc(crc.getValue());
        } else {
            dest.setMethod(ZipEntry.DEFLATED);
        }
        out.putNextEntry(dest);
        out.write(data);
        out.closeEntry();
    }

    private TermlibGesturePatch() {}
}
