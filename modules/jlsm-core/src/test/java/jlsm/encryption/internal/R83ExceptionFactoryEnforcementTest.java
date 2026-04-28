package jlsm.encryption.internal;

import static org.junit.jupiter.api.Assertions.fail;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * R83h enforcement: production code outside {@link R83ExceptionFactory} must NOT directly construct
 * {@code TenantKekRevokedException} or {@code DomainKekRevokedException}. This test walks the
 * compiled class files in {@code modules/jlsm-core/build/classes/java/main} (i.e.,
 * {@code production} classpath only — test classes are excluded) and inspects the constant pool for
 * {@code MethodRef} entries whose owner is one of the two prohibited classes and whose name is
 * {@code <init>}. Any non-factory call site is a violation.
 *
 * <p>
 * The project does not currently use ArchUnit; a custom class-file walker is acceptable per the
 * coordinator's guidance. The walker reads JVM constant-pool form directly via
 * {@code java.lang.classfile.ClassFile} (Java 22+ standard API).
 *
 * @spec encryption.primitives-lifecycle R83h
 */
class R83ExceptionFactoryEnforcementTest {

    private static final String[] PROHIBITED_CONSTRUCTOR_OWNERS = {
            "jlsm/encryption/TenantKekRevokedException",
            "jlsm/encryption/DomainKekRevokedException", };

    /**
     * Allowed call sites. Only the centralised factory may construct these exception types
     * directly. Note: subclasses may super-construct via the constructor, so a
     * {@code TenantKekRevokedException}'s own {@code <init>} chain (which calls
     * {@code KekRevokedException.<init>}) is not flagged because the owner there is
     * {@code KekRevokedException}, not the prohibited final type.
     */
    private static final String[] ALLOWED_CALLERS = {
            "jlsm/encryption/internal/R83ExceptionFactory" };

    @Test
    void noProductionCodeConstructsRevokedExceptionsOutsideFactory() throws IOException {
        // Locate the production classes directory by walking up from a known compiled class
        // resource. R83ExceptionFactory itself lives in main, so its classloader's URL points
        // at the production output directory.
        final Path classesRoot = locateMainClassesRoot();
        final List<String> violations = new ArrayList<>();
        Files.walkFileTree(classesRoot, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs)
                    throws IOException {
                if (!file.toString().endsWith(".class")) {
                    return FileVisitResult.CONTINUE;
                }
                final String relative = classesRoot.relativize(file).toString().replace('\\', '/');
                final String internalClassName = relative.substring(0, relative.length() - 6);
                if (isAllowedCaller(internalClassName)) {
                    return FileVisitResult.CONTINUE;
                }
                inspectClass(file, internalClassName, violations);
                return FileVisitResult.CONTINUE;
            }
        });
        if (!violations.isEmpty()) {
            fail("R83h violation: direct construction of TenantKekRevokedException or "
                    + "DomainKekRevokedException outside R83ExceptionFactory:\n  - "
                    + String.join("\n  - ", violations));
        }
    }

    private static boolean isAllowedCaller(String internalName) {
        for (String allowed : ALLOWED_CALLERS) {
            if (internalName.equals(allowed) || internalName.startsWith(allowed + "$")) {
                return true;
            }
        }
        // The two prohibited classes' own methods (constructors, equals, etc.) reference
        // their own <init> indirectly via super(). We do not flag the class's own bytecode
        // — only callers OUTSIDE.
        for (String prohibited : PROHIBITED_CONSTRUCTOR_OWNERS) {
            if (internalName.equals(prohibited)) {
                return true;
            }
        }
        return false;
    }

    private static void inspectClass(Path file, String callerInternalName, List<String> violations)
            throws IOException {
        final byte[] bytes = Files.readAllBytes(file);
        // Use java.lang.classfile (JEP 466 / 484 finalised in Java 24).
        final java.lang.classfile.ClassModel model = java.lang.classfile.ClassFile.of()
                .parse(bytes);
        for (java.lang.classfile.MethodModel method : model.methods()) {
            method.code().ifPresent(code -> {
                for (java.lang.classfile.CodeElement el : code) {
                    if (!(el instanceof java.lang.classfile.instruction.InvokeInstruction inv)) {
                        continue;
                    }
                    if (inv.opcode() != java.lang.classfile.Opcode.INVOKESPECIAL) {
                        continue;
                    }
                    final String ownerName = inv.owner().asInternalName();
                    final String methodName = inv.name().stringValue();
                    if (!"<init>".equals(methodName)) {
                        continue;
                    }
                    for (String prohibited : PROHIBITED_CONSTRUCTOR_OWNERS) {
                        if (ownerName.equals(prohibited)) {
                            violations.add(
                                    callerInternalName + " :: " + method.methodName().stringValue()
                                            + " constructs " + ownerName);
                        }
                    }
                }
            });
        }
    }

    private static Path locateMainClassesRoot() {
        // R83ExceptionFactory lives in main/. Its class file location reveals the
        // production-classes root. Use ClassLoader.getResource on the .class file path so
        // we get the on-disk location (works even when tests run from build/classes/java/test
        // alongside the main classpath).
        final Class<?> probe = R83ExceptionFactory.class;
        final URL url = probe.getResource(probe.getSimpleName() + ".class");
        if (url == null) {
            fail("could not locate R83ExceptionFactory.class on the test classpath");
        }
        try {
            // url ~
            // file:/.../build/classes/java/main/jlsm/encryption/internal/R83ExceptionFactory.class
            final URI uri = url.toURI();
            final Path classFile = Path.of(uri);
            // Walk up: file → internal → encryption → jlsm → main
            return classFile.getParent().getParent().getParent().getParent();
        } catch (URISyntaxException use) {
            throw new AssertionError(use);
        }
    }
}
